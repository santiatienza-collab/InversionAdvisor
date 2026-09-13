package com.inversionadvisor.data.repository

import com.inversionadvisor.data.local.dao.ScreenerDao
import com.inversionadvisor.data.local.dao.Top10Dao
import com.inversionadvisor.data.local.entities.Top10EntryEntity
import com.inversionadvisor.domain.indicators.BuyOpportunityAnalyzer
import com.inversionadvisor.domain.indicators.FactorGrade
import com.inversionadvisor.domain.indicators.Top10Calculator
import com.inversionadvisor.domain.indicators.Top10Factor
import com.inversionadvisor.domain.model.BuyOpportunity
import com.inversionadvisor.domain.model.Top10Entry
import com.inversionadvisor.domain.model.UptrendCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * "Top 10": las 10 mejores acciones para invertir combinando todos los indicadores (ver
 * Top10Calculator) — a propósito, NO dispara ningún escaneo de los 3 mercados por su cuenta.
 * Depende por completo de que ya se haya pulsado "Analizar" en las pestañas S&P 500,
 * Nasdaq-100 e IBEX 35 — si algún mercado no se ha analizado nunca, sus stocks simplemente no
 * entran en el cálculo.
 *
 *  1) Reúne los resultados YA guardados en Room de los 3 mercados (tendencia alcista + futuras
 *     compras) — solo lectura, cero peticiones de red en este paso.
 *  2) Calcula una puntuación provisional con lo que ya sale gratis (tendencia/agotamiento,
 *     sector en auge, RSI, volumen — TODO ello sacado de los datos guardados del último
 *     "Analizar", que pueden llevar tiempo) para TODOS los candidatos que pasan el filtro, solo
 *     para decidir el ORDEN en el que se analizan a fondo — no descarta a nadie.
 *  3) Para TODOS ellos: llama a BuyOpportunityAnalyzer.analyze() — LA MISMA función que usa la
 *     ficha de detalle de un stock suelto, con las velas recién descargadas AHORA MISMO (no las
 *     guardadas del último "Analizar"). Esto es lo importante: antes Top10 reconstruía su
 *     propio conjunto de datos (tendencia/RSI/sector guardados del escaneo, potencialmente
 *     desactualizados) por separado de la ficha del stock (que siempre calcula todo al
 *     momento) — con la misma fórmula pero datos de fechas distintas, podían no coincidir. Ahora
 *     se llama literalmente a la misma función con las mismas velas frescas, así que para el
 *     mismo stock en el mismo momento el resultado es el mismo, no solo "parecido".
 *     PER SÍ se pide en esta fase (reactivado a petición expresa, para que "Valoración" — 20%
 *     del peso de la fórmula — funcione en Top10) — es la petición más lenta de las que quedan,
 *     una por candidato. Ingresos sigue sin pedirse (no forma parte de la fórmula nueva).
 *  4) Se queda con los 10 mejores por la puntuación final y los guarda en Room con fecha.
 */
class Top10Repository(
    private val screenerDao: ScreenerDao,
    private val top10Dao: Top10Dao,
    private val marketRepository: MarketRepository
) {
    fun observeTop10(): Flow<List<Top10Entry>> =
        top10Dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getLastUpdatedAt(): Long? = top10Dao.getOldestUpdatedAtOnce()

    /**
     * Se llama SOLO al pulsar el botón — nunca automáticamente. [onProgress] informa del
     * avance de la fase profunda (análisis completo, con velas frescas, de cada candidato).
     *
     * Envuelto en NetworkActivityTracker.trackHeavyNetworkActivity — ver esa clase: este
     * escaneo pide MUCHAS peticiones de red a la vez (PER, velas diarias, velas mensuales, por
     * cada candidato), lo bastante como para que Android detecte caídas de conexión puntuales
     * que no son reales.
     */
    suspend fun refresh(onProgress: suspend (done: Int, total: Int) -> Unit) =
        com.inversionadvisor.data.connectivity.NetworkActivityTracker.trackHeavyNetworkActivity {
            refreshInternal(onProgress)
        }

    private suspend fun refreshInternal(onProgress: suspend (done: Int, total: Int) -> Unit) = coroutineScope {
        val uptrends = screenerDao.getAllUptrendCandidatesOnce().map { it.toUptrendDomain() }
        val opportunities = screenerDao.getAllBuyOpportunitiesOnce().map { it.toOpportunityDomain() }
        // TERCER CAJÓN, NUEVO — candidatos que no tienen ni tendencia alcista clara ni caída con
        // agotamiento, pero sí una puntuación provisional decente (ver Top10GenericCandidateEntity
        // y ScreenerRepository.scanSymbol) — pedido expresamente para que un stock como SanDisk
        // (85 puntos en la fórmula completa) no se quede fuera de Top10 solo por no encajar en
        // ninguna de las otras dos formas.
        val genericCandidates = screenerDao.getAllTop10GenericCandidatesOnce()

        data class Candidate(
            val symbol: String,
            val name: String,
            val indexName: String,
            val sectorEtf: String?,
            val sectorName: String?,
            val provisionalScore: Double,
            // NUEVO — pedido expresamente: cuando isCompleteFromRemoteScan es true, estos campos
            // YA SON el resultado final (vienen del JSON de scanner-cli, fórmula completa) — la
            // fase profunda de más abajo los usa tal cual, sin pedir nada en directo al móvil
            // para este candidato.
            val isCompleteFromRemoteScan: Boolean = false,
            val rewardScore: Double? = null,
            val riskScore: Double? = null,
            val analystSummary: String? = null,
            val penaltyWarningsCsv: String? = null,
            val bonusWarningsCsv: String? = null,
            val factorsCsv: String? = null,
            val rewardBreakdownText: String? = null,
            val riskBreakdownText: String? = null
        )

        // Agrupado por símbolo — un mismo símbolo puede venir de más de uno de los tres cajones
        // a la vez (p. ej. tendencia alcista Y además puntuación genérica alta); se combina toda
        // la información disponible en una sola entrada por símbolo antes de puntuar.
        data class Agrupado(
            var uptrend: UptrendCandidate? = null,
            var opportunity: BuyOpportunity? = null,
            var generic: com.inversionadvisor.data.local.entities.Top10GenericCandidateEntity? = null
        )
        val porSimbolo = mutableMapOf<String, Agrupado>()
        uptrends.forEach { porSimbolo.getOrPut(it.symbol) { Agrupado() }.uptrend = it }
        opportunities.forEach { porSimbolo.getOrPut(it.symbol) { Agrupado() }.opportunity = it }
        genericCandidates.forEach { porSimbolo.getOrPut(it.symbol) { Agrupado() }.generic = it }

        val pool = porSimbolo.mapNotNull { (symbol, grupo) ->
            val uptrend = grupo.uptrend
            val opportunity = grupo.opportunity
            val generic = grupo.generic

            // NUEVO — pedido expresamente: si el cajón genérico ya trae la puntuación COMPLETA
            // (importada del JSON de scanner-cli), se usa tal cual para este candidato — ni
            // siquiera hace falta la recomputación "provisional" de más abajo, que es solo una
            // aproximación barata pensada para cuando SÍ hará falta la fase profunda después.
            if (generic?.isCompleteFromRemoteScan == true) {
                return@mapNotNull Candidate(
                    symbol = symbol,
                    name = generic.name,
                    indexName = generic.indexName,
                    sectorEtf = generic.sectorEtf,
                    sectorName = generic.sectorName,
                    provisionalScore = generic.provisionalScore,
                    isCompleteFromRemoteScan = true,
                    rewardScore = generic.rewardScore,
                    riskScore = generic.riskScore,
                    analystSummary = generic.analystSummary,
                    penaltyWarningsCsv = generic.penaltyWarningsCsv,
                    bonusWarningsCsv = generic.bonusWarningsCsv,
                    factorsCsv = generic.factorsCsv,
                    rewardBreakdownText = generic.rewardBreakdownText,
                    riskBreakdownText = generic.riskBreakdownText
                )
            }

            val rsi = uptrend?.rsi14 ?: opportunity?.rsi14 ?: generic?.rsi14
            val volumeRatio = uptrend?.volumeRatio ?: opportunity?.volumeRatio ?: generic?.volumeRatio
            // Puntuación provisional (con los datos GUARDADOS del último "Analizar") —
            // solo para decidir el orden de la fase profunda, no el resultado final.
            // requireSignal SOLO se exige cuando el candidato viene de tendencia u
            // oportunidad — un candidato que llega ÚNICAMENTE del cajón genérico ya pasó su
            // propio umbral de puntuación en el escaneo, no hace falta exigirle además tendencia
            // o caída aquí (eso es justo lo que se pidió invertir).
            val provisional = Top10Calculator.score(
                trendQuality = uptrend?.trendQuality,
                yearChangePercent = uptrend?.yearChangePercent,
                aboveTrendSma = null,
                isSectorInFavor = uptrend?.isSectorInFavor ?: opportunity?.isSectorInFavor ?: generic?.isSectorInFavor,
                longTermDecline = opportunity?.exhaustionSignal,
                shortTermPullback = null,
                rsi14 = rsi, volumeRatio = volumeRatio, stockVolatilityRatio = null,
                stockPe = null, sectorAveragePe = null, sectorTypicalPeRange = null,
                percentFromYearHigh = null, nearestSupportPercent = null, nearestResistancePercent = null,
                candlestickPattern = null, marketTrap = null, macd = null,
                hasLongTermUptrend = uptrend != null || generic?.hasClearUptrend == true,
                requireSignal = uptrend != null || opportunity != null
            ) ?: return@mapNotNull null
            Candidate(
                symbol = symbol,
                name = uptrend?.name ?: opportunity?.name ?: generic?.name ?: symbol,
                indexName = uptrend?.indexName ?: opportunity?.indexName ?: generic?.indexName ?: "",
                sectorEtf = uptrend?.sectorEtf ?: opportunity?.sectorEtf ?: generic?.sectorEtf,
                sectorName = uptrend?.sectorName ?: opportunity?.sectorName ?: generic?.sectorName,
                provisionalScore = provisional.combinedScore
            )
        }
            .sortedByDescending { it.provisionalScore }
            // REVERTIDO — el intento anterior de acelerar esto (quedarse solo con los
            // DEEP_PHASE_POOL_LIMIT mejores por puntuación PROVISIONAL antes de analizar a
            // fondo) tenía un fallo real de correctitud, confirmado con un caso concreto: la
            // puntuación provisional NO incluye Valoración (PER), Riesgo (volatilidad), Volumen/
            // MACD ni Giro (patrones de vela) — 4 de las 7 categorías — así que un candidato
            // podía verse flojo ahí y quedar fuera del recorte, aunque remontara fuerte en el
            // cálculo completo con esas categorías que le faltaban. Eso es justo lo que pasó
            // (SanDisk/Western Digital con más de 75 puntos quedaron fuera; Analog Devices con
            // 70 sí entró, por haber sobrevivido al recorte provisional). Se vuelve a analizar
            // el pool ENTERO — la corrección del resultado importa más que la velocidad aquí.

        if (pool.isEmpty()) {
            top10Dao.clear()
            return@coroutineScope
        }

        // SPY UNA sola vez, no por candidato — igual que hace la ficha de un stock suelto.
        val spyCandles = try {
            marketRepository.refreshYahooCandlesIfStale(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
            marketRepository.observeCandles(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
        } catch (e: Exception) {
            emptyList()
        }
        // "Sector en auge" UNA sola vez para todos — mismo cálculo que ahora usa también la
        // ficha de un stock suelto (ver MarketRepository.computeSectorFavorability).
        val sectorFavorability = runCatching { marketRepository.computeSectorFavorability() }.getOrDefault(emptyMap())
        // PER medio del sector UNA sola vez para todos (respaldo de "Valoración" para los
        // sectores sin rango típico guardado).
        marketRepository.refreshSectorPeRatiosIfStale()
        val sectorPeMap = runCatching { marketRepository.observeSectorPeRatios().first() }.getOrDefault(emptyMap())

        // El intento de pedir el PER del pool entero EN LOTE (refreshStockPesInBulk, vía el
        // screener de Finviz filtrado por ticker) se QUITÓ de aquí — con la evidencia real de
        // que Top10 empeoró en vez de mejorar (11 min, más que antes) al añadirlo, lo más
        // probable es que ese endpoint concreto no esté devolviendo lo esperado (una URL o
        // estructura de tabla distinta a la asumida, sin haberlo podido comprobar en un
        // dispositivo real) — así que en vez de insistir con algo sin verificar, se revierte:
        // vuelta al PER por candidato de siempre, con el límite de peticiones del cliente de
        // Finviz subido de verdad (ver NetworkModule.finvizApi) como la corrección real esta
        // vez. La función refreshStockPesInBulk se deja en MarketRepository por si se retoma
        // más adelante, pero ya no se llama desde aquí.

        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(0, pool.size)
        val semaphore = Semaphore(DEEP_PHASE_CONCURRENCY)

        val finalEntries = pool.map { candidate ->
            async {
                // NUEVO — pedido expresamente: si este candidato ya trae la puntuación COMPLETA
                // (del JSON remoto), se construye el resultado final directamente con esos
                // datos, SIN pedir nada en directo al móvil (ni velas, ni PER, nada) — esto es
                // lo que hace que Top10 sea casi instantáneo cuando todo el pool viene del JSON.
                if (candidate.isCompleteFromRemoteScan) {
                    onProgress(doneCount.incrementAndGet(), pool.size)
                    return@async Top10EntryEntity(
                        symbol = candidate.symbol,
                        name = candidate.name,
                        indexName = candidate.indexName,
                        sectorName = candidate.sectorName,
                        rewardScore = candidate.rewardScore ?: 0.0,
                        riskScore = candidate.riskScore ?: 0.0,
                        combinedScore = candidate.provisionalScore,
                        analystSummary = candidate.analystSummary ?: "",
                        // NUEVO — antes esto salía siempre vacío en la vía rápida (el JSON no
                        // traía el desglose todavía) — ahora si el candidato lo trae, se usa
                        // igual que en el escaneo en directo.
                        factorsCsv = candidate.factorsCsv ?: emptyList<Top10Factor>().toCsv(),
                        rewardBreakdownText = candidate.rewardBreakdownText ?: "",
                        riskBreakdownText = candidate.riskBreakdownText ?: "",
                        penaltyWarningsText = (candidate.penaltyWarningsCsv?.split("||")?.filter { it.isNotBlank() } ?: emptyList()).joinToString("\n"),
                        bonusWarningsText = (candidate.bonusWarningsCsv?.split("||")?.filter { it.isNotBlank() } ?: emptyList()).joinToString("\n"),
                        rank = 0,
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                }
                semaphore.withPermit {
                    // Velas y PER son dos peticiones INDEPENDIENTES entre sí — antes iban una
                    // detrás de otra (esperando a que terminaran las velas para empezar el PER),
                    // ahora van en paralelo, a la mitad de tiempo de espera por candidato.
                    val candlesDeferred = async {
                        try {
                            marketRepository.refreshYahooCandlesIfStale(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                            marketRepository.observeCandles(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    val stockPeDeferred = async {
                        // PER del stock, uno por candidato (el intento de pedirlo en lote se
                        // revirtió, ver comentario más arriba). allowWebViewFallback=false: si
                        // Finviz no tiene el PER de alguno, se acepta "sin dato" SOLO para ese
                        // candidato en concreto — el resto de categorías le siguen puntuando con
                        // normalidad. La ficha de un stock suelto y "Futuras compras" siguen con
                        // el respaldo de WebView activado, al no ser un cálculo por lotes.
                        try {
                            marketRepository.refreshStockPeIfStale(candidate.symbol, allowWebViewFallback = false)
                            marketRepository.observeStockPe(candidate.symbol).first()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val candles = candlesDeferred.await()
                    val stockPe = stockPeDeferred.await()
                    val sectorAveragePe = candidate.sectorEtf?.let { sectorPeMap[it] }

                    // CAMBIADO A PETICIÓN EXPRESA: antes solo se pedían velas diarias cuando el
                    // cruce SEMANAL (barato) ya encontraba algo que confirmar — ahora se piden
                    // SIEMPRE, para todos los candidatos, para que el cruce de medias Y el
                    // patrón de bandera (corto plazo) salgan con datos coherentes en todos los
                    // apartados, no solo "a veces". Esto tarda más — cada candidato pide ahora
                    // dos peticiones nuevas (diaria + mensual) en vez de cero o una — pero es lo
                    // que se pidió, aceptando ese coste a cambio de cobertura completa.
                    val dailyCandlesForCross = try {
                        marketRepository.refreshDailyCandlesForSmaIfStale(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                        marketRepository.observeDailyCandlesForSma(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                    } catch (e: Exception) {
                        null
                    }
                    // Velas MENSUALES — para el patrón de bandera "a largo plazo". Caché de 24h
                    // (CacheConfig.CANDLE_TTL_MONTHLY_MILLIS) — un mes no cambia hasta que pasa
                    // un mes entero, así que los escaneos repetidos el mismo día no vuelven a
                    // pagar esta petición.
                    val monthlyCandlesForFlag = try {
                        marketRepository.refreshMonthlyCandlesForFlagIfStale(candidate.symbol)
                        marketRepository.observeMonthlyCandlesForFlag(candidate.symbol).first()
                    } catch (e: Exception) {
                        null
                    }

                    // LA MISMA función que usa la ficha de detalle de un stock — ver cabecera
                    // de la clase para el porqué. Ingresos siguen sin pedirse (no forman parte
                    // de la fórmula nueva de 7 categorías, ver Top10Calculator).
                    val analysis = BuyOpportunityAnalyzer.analyze(
                        candles = candles,
                        sectorName = candidate.sectorName,
                        stockPe = stockPe,
                        sectorAveragePe = sectorAveragePe,
                        spyCandles = spyCandles.ifEmpty { null },
                        incomeGrowing = null,
                        sectorInFavor = candidate.sectorEtf?.let { sectorFavorability[it] },
                        sectorEtf = candidate.sectorEtf,
                        dailyCandlesForCross = dailyCandlesForCross,
                        monthlyCandlesForFlag = monthlyCandlesForFlag?.ifEmpty { null }
                    )

                    onProgress(doneCount.incrementAndGet(), pool.size)

                    analysis?.let {
                        Top10EntryEntity(
                            symbol = candidate.symbol,
                            name = candidate.name,
                            indexName = candidate.indexName,
                            sectorName = candidate.sectorName,
                            rewardScore = it.rewardScore,
                            riskScore = it.riskScore,
                            combinedScore = it.riskRewardRatio,
                            analystSummary = it.analystSummary,
                            factorsCsv = it.factors.toCsv(),
                            rewardBreakdownText = it.rewardBreakdown.joinToString("\n"),
                            riskBreakdownText = it.riskBreakdown.joinToString("\n"),
                            penaltyWarningsText = it.penaltyWarnings.joinToString("\n"),
                            bonusWarningsText = it.bonusWarnings.joinToString("\n"),
                            rank = 0,
                            updatedAtEpochMillis = System.currentTimeMillis()
                        )
                    }
                }
            }
        }.mapNotNull { runCatching { it.await() }.getOrNull() }

        val top10 = finalEntries
            .sortedByDescending { it.combinedScore }
            .take(TOP_COUNT)
            .mapIndexed { index, entry -> entry.copy(rank = index + 1) }

        top10Dao.clear()
        top10Dao.insertAll(top10)
    }

    companion object {
        /** Cuántos candidatos de la fase profunda se procesan A LA VEZ — no cuántos se procesan
         *  en total (sin tope, se evalúan todos). */
        /** BAJADO DE NUEVO — de 25 a 12: desde que cada candidato pide TAMBIÉN velas diarias y
         *  mensuales (para la Bandera y la tendencia bajista consolidada), 25 a la vez suponía
         *  demasiadas conexiones de red simultáneas, saturando la red del móvil lo bastante como
         *  para que Android detectara caídas de conexión puntuales (banner "conexión perdida" en
         *  bucle) y para que otras peticiones en curso (como los ingresos netos por WebView)
         *  fallasen más de la cuenta compitiendo por esa misma red saturada. Top10 tardará algo
         *  más con este cambio, a cambio de ser más fiable. */
        private const val DEEP_PHASE_CONCURRENCY = 12
        private const val TOP_COUNT = 10
    }
}

private fun com.inversionadvisor.data.local.entities.UptrendCandidateEntity.toUptrendDomain(): UptrendCandidate = UptrendCandidate(
    symbol = symbol, name = name, sectorEtf = sectorEtf, sectorName = sectorName, indexName = indexName,
    yearChangePercent = yearChangePercent, trendQuality = trendQuality, rsi14 = rsi14, volumeRatio = volumeRatio,
    isSectorInFavor = isSectorInFavor
)

private fun com.inversionadvisor.data.local.entities.BuyOpportunityEntity.toOpportunityDomain(): BuyOpportunity {
    val exhaustionSignal = com.inversionadvisor.domain.indicators.ExhaustionSignal(
        detected = true,
        confidenceScore = confidenceScore,
        reasons = reasonsCsv.split("||").filter { it.isNotBlank() },
        recentHigh = recentHigh,
        recentLow = recentLow,
        recentLowDate = recentLowDate,
        declinePercentFromRecentHigh = declinePercentFromRecentHigh,
        nextResistanceTarget = nextResistanceTarget,
        recoveryProgressToResistancePercent = recoveryProgressToResistancePercent
    )
    val riskLevel = if (confidenceScore >= 65) com.inversionadvisor.domain.model.RiskLevel.LOW else com.inversionadvisor.domain.model.RiskLevel.MEDIUM
    return BuyOpportunity(
        symbol = symbol, name = name, sectorEtf = sectorEtf, sectorName = sectorName, indexName = indexName,
        isSectorInFavor = isSectorInFavor, exhaustionSignal = exhaustionSignal, allTimeHighInfo = null,
        riskLevel = riskLevel, rsi14 = rsi14, volumeRatio = volumeRatio
    )
}

/** "label|grade|valor" separados por ";;" — Room no guarda listas de objetos directamente. */
private fun List<Top10Factor>.toCsv(): String =
    joinToString(";;") { "${it.label}|${it.grade.name}|${it.valueText}" }

private fun String.toFactors(): List<Top10Factor> {
    if (isBlank()) return emptyList()
    return split(";;").mapNotNull { chunk ->
        val parts = chunk.split("|")
        if (parts.size != 3) return@mapNotNull null
        val grade = runCatching { FactorGrade.valueOf(parts[1]) }.getOrDefault(FactorGrade.UNKNOWN)
        Top10Factor(label = parts[0], grade = grade, valueText = parts[2])
    }
}

private fun Top10EntryEntity.toDomain(): Top10Entry = Top10Entry(
    symbol = symbol, name = name, indexName = indexName, sectorName = sectorName,
    rewardScore = rewardScore, riskScore = riskScore, combinedScore = combinedScore,
    analystSummary = analystSummary, factors = factorsCsv.toFactors(),
    rewardBreakdown = rewardBreakdownText.split("\n").filter { it.isNotBlank() },
    riskBreakdown = riskBreakdownText.split("\n").filter { it.isNotBlank() },
    penaltyWarnings = penaltyWarningsText.split("\n").filter { it.isNotBlank() },
    bonusWarnings = bonusWarningsText.split("\n").filter { it.isNotBlank() },
    rank = rank, updatedAtEpochMillis = updatedAtEpochMillis
)
