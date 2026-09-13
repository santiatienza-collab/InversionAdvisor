package com.inversionadvisor.data.repository

import com.inversionadvisor.data.local.CacheConfig
import com.inversionadvisor.data.local.dao.MarketMoversDao
import com.inversionadvisor.data.local.entities.MarketMoverEntity
import com.inversionadvisor.data.local.entities.MarketMoversMetaEntity
import com.inversionadvisor.data.remote.YahooFinanceApi
import com.inversionadvisor.domain.model.MarketMover
import com.inversionadvisor.domain.model.StockUniverseEntry
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Top ganadores/perdedores del día sobre el universo de stocks (S&P 500 +
 * Nasdaq-100 + IBEX 35, deduplicado por símbolo) para el banner rotatorio
 * del panel principal.
 *
 * Usa un cliente Yahoo DEDICADO (yahooFinanceMoversApi), separado del
 * cliente "bulk" que usa el screener — así un escaneo pesado (~600
 * símbolos, universo completo) nunca compite por el mismo rate limit ni
 * pool de conexiones que necesita el escaneo del screener (ver
 * NetworkModule). Cada petición aquí es mucho más ligera que la del
 * screener (que pide 52 velas semanales por símbolo): solo se necesita el
 * precio actual y el cierre anterior, ambos ya vienen en el bloque `meta`
 * de cualquier respuesta del endpoint de gráficos, sin importar el rango
 * pedido.
 */
class MarketMoversRepository(
    private val yahooFinanceMoversApi: YahooFinanceApi,
    private val stockUniverseRepository: StockUniverseRepository,
    private val dao: MarketMoversDao
) {

    fun observeTopGainers(limit: Int = 15): Flow<List<MarketMover>> =
        dao.observeTopGainers(limit).map { list -> list.map { it.toDomain() } }

    fun observeTopLosers(limit: Int = 15): Flow<List<MarketMover>> =
        dao.observeTopLosers(limit).map { list -> list.map { it.toDomain() } }

    private fun isStale(cachedAtMillis: Long?, maxAgeMillis: Long): Boolean =
        cachedAtMillis == null || System.currentTimeMillis() - cachedAtMillis > maxAgeMillis

    /**
     * Marca de tiempo del último refresco "en vivo" (solo los símbolos ya mostrados) — EN
     * MEMORIA, no en la base de datos: es un dato efímero (si se pierde al cerrar la app, sin
     * problema, se refresca de nuevo enseguida) y así se evita añadir una tabla nueva solo para
     * esto. Si hay varias instancias del repositorio (no debería, pero por si acaso) cada una
     * llevaría su propia cuenta — consecuencia aceptable para un dato puramente de cadencia.
     */
    @Volatile
    private var lastLiveRefreshEpochMillis: Long? = null

    /**
     * Pide el precio actual y el cierre de ayer de UN símbolo — misma lógica exacta que ya
     * usaba el escaneo completo, extraída aquí para poder reutilizarla también en el refresco
     * "en vivo" (solo los símbolos ya mostrados) sin duplicar el código.
     */
    private suspend fun fetchMoverForSymbol(symbol: String, name: String, updatedAtEpochMillis: Long): MarketMoverEntity? {
        return try {
            // Precio de HOY EN VIVO (se mueve mientras el mercado está abierto) comparado
            // contra el cierre de AYER.
            //
            // CAMBIADO — delega en YahooDailyChangeCalculator (ver esa clase para el porqué):
            // esta función tenía su PROPIA copia de esta lógica, separada de la que usa
            // MarketRepository.fetchYesterdayClose/fetchTodayQuoteChange para la ficha de un
            // stock — dos copias que, con el tiempo, podían divergir sin que nadie se diera
            // cuenta hasta ver dos % distintos para el mismo stock en el banner y en su ficha
            // (reportado por el usuario con Adobe). Se sigue usando yahooFinanceMoversApi (el
            // cliente HTTP dedicado a esto, con sus propios límites de concurrencia) para la
            // PETICIÓN — solo el CÁLCULO sobre la respuesta ya recibida es ahora compartido.
            val response = yahooFinanceMoversApi.getChart(symbol = symbol, range = "5d", interval = "1d")
            val chartResult = response.chart.result?.firstOrNull() ?: return null
            val result = com.inversionadvisor.domain.indicators.YahooDailyChangeCalculator.compute(chartResult)
            if (result == null) {
                // Diagnóstico — para ver si hay un patrón (p. ej. solo símbolos de EE.UU.) en
                // los que Yahoo no da suficientes velas con marca de tiempo, causa real de que
                // ese símbolo desaparezca del banner en ese escaneo.
                val rawCloses = chartResult.indicators?.quote?.firstOrNull()?.close ?: emptyList()
                val rawTimestamps = chartResult.timestamp ?: emptyList()
                android.util.Log.w(
                    "MarketMoversDiagnostic",
                    "$symbol: DESCARTADO por datos insuficientes — rawCloses.size=${rawCloses.size}, rawTimestamps.size=${rawTimestamps.size}"
                )
                return null
            }
            val close = result.currentPrice
            val previousClose = result.yesterdayClose
            if (previousClose == 0.0) return null
            val changePercent = result.changePercent
            // Diagnóstico — un +/-15% en un solo día es infrecuente para un stock normal, pero
            // movimientos reales así SÍ ocurren (confirmado con Moderna, +170% real en una
            // sesión) — se deja el registro solo con fines informativos, no descarta nada.
            if (kotlin.math.abs(changePercent) > 15.0) {
                android.util.Log.w(
                    "MarketMoversDiagnostic",
                    "$symbol: changePercent=${"%.1f".format(changePercent)}% — " +
                        "precio en vivo=$close, cierre(ayer)=$previousClose"
                )
            }
            MarketMoverEntity(
                symbol = symbol,
                name = name,
                changePercent = changePercent,
                price = close,
                updatedAtEpochMillis = updatedAtEpochMillis
            )
        } catch (e: Exception) {
            // Un fallo puntual en un símbolo (delisted, sin datos, timeout...) no debe cortar
            // el escaneo completo de los demás.
            null
        }
    }

    suspend fun refreshIfStale(maxAgeMillis: Long = CacheConfig.MARKET_MOVERS_TTL_MILLIS) = coroutineScope {
        val meta = dao.getMetaOnce()
        if (!isStale(meta?.updatedAtEpochMillis, maxAgeMillis)) return@coroutineScope

        val now = System.currentTimeMillis()

        // IMPORTANTE: se marca el intento COMO YA HECHO desde ya (antes de escanear),
        // pase lo que pase. Si no se hiciera esto y el escaneo fallase entero (p.ej.
        // Yahoo bloqueando el volumen de peticiones), la marca de tiempo nunca se
        // actualizaría y refreshIfStale() repetiría el escaneo COMPLETO del universo
        // en cada refresco del panel — esto es justo lo que estaba saturando el mismo
        // tipo de peticiones que necesita el Análisis y tumbando ambas cosas a la vez.
        dao.upsertMeta(MarketMoversMetaEntity(updatedAtEpochMillis = now))

        val sp500Entries = try { stockUniverseRepository.getUniverseForScreening("SP500") } catch (e: Exception) { emptyList() }
        val nasdaq100Entries = try { stockUniverseRepository.getUniverseForScreening("NASDAQ100") } catch (e: Exception) { emptyList() }
        val ibex35Entries = try { stockUniverseRepository.getUniverseForScreening("IBEX35") } catch (e: Exception) { emptyList() }
        // Diagnóstico — confirma de entrada, ANTES de intentar pedir ningún precio, si los
        // tres universos se están recogiendo bien por su cuenta. Si alguno sale a 0 aquí, el
        // problema está en StockUniverseRepository, no en el escaneo de precios de abajo.
        android.util.Log.i(
            "MarketMoversDiagnostic",
            "Universos recogidos — SP500: ${sp500Entries.size}, NASDAQ100: ${nasdaq100Entries.size}, IBEX35: ${ibex35Entries.size}"
        )
        val universe = (sp500Entries + nasdaq100Entries + ibex35Entries).distinctBy { it.symbol }

        if (universe.isEmpty()) return@coroutineScope

        val semaphore = Semaphore(SCAN_CONCURRENCY)
        var anySuccess = false

        suspend fun scanAndSave(entries: List<StockUniverseEntry>, saveIncrementally: Boolean): List<MarketMoverEntity> {
            val pending = entries.map { entry ->
                async {
                    semaphore.withPermit {
                        val marketMoverEntity = fetchMoverForSymbol(entry.symbol, entry.name, now)
                        // Guardado incremental por símbolo (no por lote): en cuanto un valor
                        // tiene resultado, se escribe ya — así el banner se va llenando según
                        // van llegando las respuestas más rápidas, en vez de esperar a que el
                        // más lento del lote termine antes de guardar nada.
                        if (marketMoverEntity != null && saveIncrementally) {
                            dao.upsertAll(listOf(marketMoverEntity))
                            anySuccess = true
                        }
                        marketMoverEntity
                    }
                }
            }
            val results = pending.awaitAll().filterNotNull()
            if (!saveIncrementally && results.isNotEmpty()) {
                dao.upsertAll(results)
                anySuccess = true
            }
            return results
        }

        // PASADA RÁPIDA primero: un puñado de valores muy líquidos y conocidos (los
        // más propensos a acabar siendo top ganador/perdedor de todas formas), todos
        // a la vez en un solo lote — así el banner tiene contenido reconocible en
        // segundos, en vez de tener que esperar a que el escaneo del universo entero
        // (varios cientos de símbolos, sujeto al rate limit) vaya llegando por orden.
        val priorityEntries = universe.filter { it.symbol in PRIORITY_SYMBOLS }
        val restEntries = universe - priorityEntries.toSet()

        val priorityResults = if (priorityEntries.isNotEmpty()) {
            scanAndSave(priorityEntries, saveIncrementally = false)
        } else {
            emptyList()
        }

        // Resto del universo: TODO de una vez (no en lotes secuenciales de 40 en 40,
        // que obligaban a esperar a que el símbolo más lento de cada lote terminara
        // antes de lanzar el siguiente lote). El semáforo de arriba (SCAN_CONCURRENCY)
        // ya limita cuántas peticiones van a la vez, así que lanzar todo junto no
        // sobrecarga nada — simplemente mantiene esas 20 peticiones en marcha sin
        // huecos de espera entre lotes, y cada símbolo se guarda en cuanto llega.
        val restResults = scanAndSave(restEntries, saveIncrementally = true)

        // DIAGNÓSTICO — resumen por universo, pedido tras el aviso de "el banner solo
        // muestra IBEX35": para saber con certeza si es que los símbolos de EE.UU.
        // están fallando de verdad en la petición, o si es otra cosa (caché vieja,
        // etc.) — un solo vistazo a esta línea en el Logcat lo confirma o lo descarta.
        run {
            val allResults = priorityResults + restResults
            val universeIbexCount = universe.count { it.symbol.endsWith(".MC") }
            val universeUsCount = universe.size - universeIbexCount
            val savedIbexCount = allResults.count { it.symbol.endsWith(".MC") }
            val savedUsCount = allResults.size - savedIbexCount
            android.util.Log.i(
                "MarketMoversDiagnostic",
                "RESUMEN DEL ESCANEO — universo: $universeUsCount EE.UU. + $universeIbexCount IBEX35 " +
                    "(total ${universe.size}) — guardados con éxito: $savedUsCount EE.UU. + $savedIbexCount IBEX35 " +
                    "(total ${allResults.size})"
            )
            // NUEVO — rango de variación calculado, por separado para EE.UU. e IBEX35: si los
            // de EE.UU. salen todos con % pequeños (cerca de 0) mientras el IBEX35 tiene
            // movimientos más grandes, es un día/momento donde el IBEX35 tiene más movimiento
            // real — no un fallo, el top 15 lo refleja correctamente. Si en cambio los de
            // EE.UU. tuvieran rangos normales (varios %) y aun así no aparecieran, sería otra
            // cosa (ordenación, límite de la consulta...).
            val usChanges = allResults.filterNot { it.symbol.endsWith(".MC") }.map { it.changePercent }
            val ibexChanges = allResults.filter { it.symbol.endsWith(".MC") }.map { it.changePercent }
            android.util.Log.i(
                "MarketMoversDiagnostic",
                "RANGO DE VARIACIÓN — EE.UU.: min=${"%.2f".format(usChanges.minOrNull() ?: 0.0)}% " +
                    "max=${"%.2f".format(usChanges.maxOrNull() ?: 0.0)}% — " +
                    "IBEX35: min=${"%.2f".format(ibexChanges.minOrNull() ?: 0.0)}% " +
                    "max=${"%.2f".format(ibexChanges.maxOrNull() ?: 0.0)}%"
            )
        }

        // Limpieza final: quita del banner cualquier símbolo que NO se haya podido
        // actualizar en ESTE escaneo (falló, o salió del universo) — se hace al
        // terminar, no al principio, para que la tabla nunca esté vacía mientras el
        // escaneo está en marcha. Si el escaneo entero falló, no se toca nada (mejor
        // un banner con datos algo viejos que uno vacío).
        if (anySuccess) {
            dao.deleteOlderThan(now)
        }
    }

    /**
     * Refresco "en tiempo real" pedido expresamente ("banner en tiempo real, con la variación
     * de precio día a día") — SOLO de los símbolos que YA están en el banner ahora mismo
     * (normalmente ~30: 15 ganadores + 15 perdedores), no del universo completo (~600). Al ser
     * tan pocos símbolos, cabe de sobra pedirlo cada minuto (ver
     * CacheConfig.MARKET_MOVERS_LIVE_TTL_MILLIS) sin acercarse a ningún límite de peticiones.
     *
     * NO puede descubrir símbolos NUEVOS que aún no estén en el banner — para eso sigue
     * haciendo falta refreshIfStale() con su cadencia más lenta (10 min), que si recorre el
     * universo entero. Pensados para convivir: éste da la sensación de tiempo real para lo que
     * ya se ve, el otro se encarga de detectar quién entra o sale del top con el tiempo.
     */
    suspend fun refreshCurrentlyShownIfStale(maxAgeMillis: Long = CacheConfig.MARKET_MOVERS_LIVE_TTL_MILLIS) = coroutineScope {
        if (!isStale(lastLiveRefreshEpochMillis, maxAgeMillis)) return@coroutineScope
        lastLiveRefreshEpochMillis = System.currentTimeMillis()

        val currentEntities = (dao.getTopGainersOnce(30) + dao.getTopLosersOnce(30)).distinctBy { it.symbol }
        if (currentEntities.isEmpty()) return@coroutineScope

        val now = System.currentTimeMillis()
        val semaphore = Semaphore(SCAN_CONCURRENCY)
        val results = currentEntities.map { entity ->
            async {
                semaphore.withPermit { fetchMoverForSymbol(entity.symbol, entity.name, now) }
            }
        }.awaitAll().filterNotNull()

        if (results.isNotEmpty()) {
            dao.upsertAll(results)
        }
    }

    companion object {
        private const val SCAN_CONCURRENCY = 30

        /**
         * Valores muy líquidos de los tres universos (SP500 + Nasdaq-100 + IBEX 35),
         * escaneados primero para que el banner tenga contenido reconocible casi de
         * inmediato. Si alguno no aparece en el universo actual (cambios de
         * composición del índice) simplemente se ignora, sin error.
         */
        private val PRIORITY_SYMBOLS = setOf(
            // S&P 500 / Nasdaq-100: mega-caps más seguidas
            "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "GOOG", "META", "TSLA", "AVGO",
            "JPM", "V", "MA", "UNH", "XOM", "JNJ", "WMT", "PG", "HD", "COST",
            "NFLX", "AMD", "ADBE", "CRM", "ORCL", "PEP", "KO", "BAC", "DIS", "INTC",
            // IBEX 35: blue chips más seguidos
            "SAN.MC", "BBVA.MC", "IBE.MC", "ITX.MC", "TEF.MC", "REP.MC", "FER.MC",
            "ACS.MC", "AENA.MC", "CABK.MC", "AMS.MC", "CLNX.MC", "IAG.MC", "MTS.MC"
        )
    }
}

private fun MarketMoverEntity.toDomain(): MarketMover = MarketMover(
    symbol = symbol,
    name = name,
    changePercent = changePercent,
    price = price
)
