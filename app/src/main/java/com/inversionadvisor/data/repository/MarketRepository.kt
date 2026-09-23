package com.inversionadvisor.data.repository

import com.inversionadvisor.BuildConfig
import com.inversionadvisor.data.local.CacheConfig
import com.inversionadvisor.data.local.dao.MarketDao
import com.inversionadvisor.data.local.entities.CandleEntity
import com.inversionadvisor.data.local.entities.CandleFetchMetaEntity
import com.inversionadvisor.data.local.entities.CryptoSparklineEntity
import com.inversionadvisor.data.local.entities.FearGreedEntity
import com.inversionadvisor.data.local.entities.MarketVolumeEntity
import com.inversionadvisor.data.local.entities.PeRatioEntity
import com.inversionadvisor.data.local.entities.QuoteEntity
import com.inversionadvisor.data.local.entities.AaiiSentimentEntity
import com.inversionadvisor.domain.indicators.AaiiSentimentHtmlParser
import com.inversionadvisor.domain.indicators.CnnFearGreedRatingMapper
import com.inversionadvisor.domain.indicators.FinvizPeParser
import com.inversionadvisor.domain.indicators.YahooFinancialsParser
import com.inversionadvisor.domain.indicators.YahooQuotePageParser
import com.inversionadvisor.data.remote.CnnFearGreedApi
import com.inversionadvisor.data.remote.CoinGeckoApi
import com.inversionadvisor.data.remote.QuoteDto
import com.inversionadvisor.data.remote.TwelveDataApi
import com.inversionadvisor.data.remote.YahooChartResultDto
import com.inversionadvisor.data.remote.YahooFinanceApi
import com.inversionadvisor.domain.indicators.CurrencyConverter
import com.inversionadvisor.domain.model.AaiiSentiment
import com.inversionadvisor.domain.model.Candle
import com.inversionadvisor.domain.model.ChartRange
import com.inversionadvisor.domain.model.CnnFearGreedStatus
import com.inversionadvisor.domain.model.Currency
import com.inversionadvisor.domain.model.ForexPairs
import com.inversionadvisor.domain.model.MarketVolume
import com.inversionadvisor.domain.model.Quote
import com.inversionadvisor.domain.model.Symbols
import com.inversionadvisor.domain.model.cacheKey
import com.inversionadvisor.domain.model.toYahooRangeAndInterval
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Única puerta de entrada a los datos de mercado para el resto de la app (ViewModels).
 *
 * Estrategia de eficiencia:
 *  - La UI SIEMPRE lee de Room vía Flow (observeQuote/observeCandles) — nunca espera a la red.
 *  - Los métodos refresh*IfStale() comprueban primero la marca de tiempo en caché y solo
 *    llaman a la API si el dato ha caducado (ver CacheConfig).
 *  - El dashboard usa Yahoo Finance como fuente principal (sin límite de créditos
 *    documentado, mucho más rápido que Twelve Data para las 13 velas sectoriales
 *    de la rotación). Twelve Data se deja disponible para cuando se construya
 *    el buscador de stocks, por si hace falta un dato que Yahoo no dé bien.
 */
class MarketRepository(
    private val twelveDataApi: TwelveDataApi = com.inversionadvisor.data.remote.NetworkModule.twelveDataApi,
    private val coinGeckoApi: CoinGeckoApi = com.inversionadvisor.data.remote.NetworkModule.coinGeckoApi,
    private val cnnFearGreedApi: CnnFearGreedApi = com.inversionadvisor.data.remote.NetworkModule.cnnFearGreedApi,
    private val yahooFinanceApi: YahooFinanceApi = com.inversionadvisor.data.remote.NetworkModule.yahooFinanceApi,
    private val yahooFinanceBulkApi: YahooFinanceApi = com.inversionadvisor.data.remote.NetworkModule.yahooFinanceBulkApi,
    // yahooQuoteApi (v7/finance/quote) YA NO SE USA — devolvía 401 de forma silenciosa (exige
    // crumb+cookie que esta app no implementa), ver comentario de EarningsDateEntity. Se
    // sustituyó por scrapear la página de cotización ya renderizada (misma fuente que PER/Valor
    // Empresa/Ingresos netos). Se deja el parámetro quitado del todo, no solo sin usar, para que
    // quien lea el constructor no piense que sigue en pie.
    private val stooqApi: com.inversionadvisor.data.remote.StooqApi = com.inversionadvisor.data.remote.NetworkModule.stooqApi,
    private val finvizApi: com.inversionadvisor.data.remote.FinvizApi = com.inversionadvisor.data.remote.NetworkModule.finvizApi,
    // NUEVO — API pública y gratuita de SEC EDGAR para los ingresos netos de "Datos de
    // Empresa" (ver refreshCompanyFinancialsInternal): JSON oficial de los 10-K, sin HTML que
    // parsear ni WebView, para cualquier símbolo de EE.UU. — el scraping de Yahoo se queda como
    // respaldo solo para lo que la SEC no cubre (IBEX 35, símbolos sin CIK).
    private val secEdgarApi: com.inversionadvisor.data.remote.SecEdgarApi = com.inversionadvisor.data.remote.NetworkModule.secEdgarApi,
    // NUEVO — respaldo de "Ingresos netos" específico para el IBEX 35 (.MC), que SEC EDGAR no
    // cubre y donde el scraping de Yahoo puede seguir sin traer dato (ver
    // fetchNetIncomesFromInvesting, usado solo cuando lo anterior falla).
    private val investingApi: com.inversionadvisor.data.remote.InvestingApi = com.inversionadvisor.data.remote.NetworkModule.investingApi,
    // fmpApi (Financial Modeling Prep) QUITADO — se probó brevemente para los ingresos netos de
    // "Datos de Empresa" (JSON limpio en vez de scrapear Yahoo), pero su endpoint de
    // income-statement devuelve HTTP 402 (Payment Required) en el plan gratuito: no es viable
    // sin pagar. Vuelta al scraping de Yahoo (ver refreshCompanyFinancialsInternal), con una
    // estrategia de reintento más eficiente que la versión original. El cliente
    // (NetworkModule.financialModelingPrepApi) y la interfaz se dejan sin usar, no borrados, por
    // si en el futuro se decide pagar un plan de FMP — mismo criterio que yahooQuoteApi arriba.
    /**
     * null = no se puede scrapear AAII (por ejemplo si en el futuro se construye
     * MarketRepository fuera de ServiceLocator, sin Context disponible) — en ese
     * caso refreshAaiiSentiment lanza un error explícito en vez de fallar de
     * forma más confusa dentro del propio scraper.
     */
    private val hiddenWebViewScraper: com.inversionadvisor.data.webview.HiddenWebViewScraper? = null,
    private val dao: MarketDao
) {
    private val apiKey: String get() = BuildConfig.TWELVE_DATA_API_KEY

    /**
     * Un Mutex por cada clave símbolo+rango, para que dos llamadas concurrentes a
     * refreshYahooCandles(Bulk)IfStale del MISMO símbolo+rango (p. ej. abrir el
     * mismo stock casi a la vez desde Análisis y desde Busca) nunca se solapen.
     * Sin esto, dos peticiones a la vez podían intercalar sus propios
     * "borrar caché -> insertar velas nuevas" (dao.clearCandles + dao.upsertCandles)
     * y dejar la tabla vacía o a medias — la causa real de que algunos stocks del
     * IBEX 35 (p. ej. Repsol) mostraran "sin datos suficientes" en Busca. Con el
     * mutex, la segunda llamada espera a que termine la primera y luego ve que
     * el dato ya está fresco (no relanza la petición).
     */
    private val candleRefreshMutexes = ConcurrentHashMap<String, Mutex>()

    private fun candleMutexFor(key: String): Mutex = candleRefreshMutexes.getOrPut(key) { Mutex() }

    // ---- Cotizaciones (lectura, agnóstica de la fuente) ----

    fun observeQuote(symbol: String): Flow<Quote?> =
        dao.observeQuote(symbol).map { it?.toDomain() }

    fun observeQuotes(symbols: List<String>): Flow<List<Quote>> =
        dao.observeQuotes(symbols).map { list -> list.map { it.toDomain() } }

    private fun isStale(cachedAtMillis: Long?, maxAgeMillis: Long): Boolean =
        cachedAtMillis == null || System.currentTimeMillis() - cachedAtMillis > maxAgeMillis

    // ---- Cotizaciones y velas vía Yahoo Finance (fuente principal del dashboard) ----

    suspend fun refreshYahooQuote(symbol: String) {
        val response = yahooFinanceApi.getChart(symbol = symbol, range = "5d", interval = "1d")
        val result = response.chart.result?.firstOrNull()
            ?: throw IllegalStateException("Yahoo Finance error para $symbol: ${response.chart.error?.description}")
        dao.upsertQuote(result.toQuoteEntity(symbol))
    }

    suspend fun refreshYahooQuoteIfStale(symbol: String, maxAgeMillis: Long = CacheConfig.QUOTE_TTL_MILLIS) {
        val cached = dao.getQuoteOnce(symbol)
        if (isStale(cached?.updatedAtEpochMillis, maxAgeMillis)) refreshYahooQuote(symbol)
    }

    /**
     * Yahoo no soporta pedir varios símbolos en una sola llamada (a diferencia
     * de Twelve Data), así que se hace uno a uno — pero al no tener límite de
     * créditos documentado no hace falta espaciarlos con pausas largas. Un
     * fallo puntual en un símbolo no corta el resto.
     */
    suspend fun refreshYahooQuotesIfStale(symbols: List<String>, maxAgeMillis: Long = CacheConfig.QUOTE_TTL_MILLIS) {
        val distinctSymbols = symbols.distinct()
        val cached = dao.getQuotesOnce(distinctSymbols).associateBy { it.symbol }
        for (symbol in distinctSymbols) {
            if (isStale(cached[symbol]?.updatedAtEpochMillis, maxAgeMillis)) {
                try {
                    refreshYahooQuote(symbol)
                } catch (e: Exception) {
                    // Seguimos con el resto de símbolos aunque uno falle
                }
            }
        }
    }

    /** Refresca de una sola vez VIX, oro, ETFs sectoriales y pares forex vía Yahoo Finance. */
    suspend fun refreshMarketOverview(maxAgeMillis: Long = CacheConfig.QUOTE_TTL_MILLIS) {
        val symbols = buildSet {
            add(Symbols.VIX)
            add(Symbols.CORRELATION_INDEX)
            add(Symbols.GOLD)
            addAll(Symbols.SECTOR_ETFS.keys)
            addAll(ForexPairs.ALL)
        }
        refreshYahooQuotesIfStale(symbols.toList(), maxAgeMillis)
    }

    fun observeCandles(symbol: String, range: ChartRange): Flow<List<Candle>> =
        dao.observeCandles(symbol, range.cacheKey).map { list -> list.map { it.toDomain() } }

    /**
     * AÑADIDO — horario extendido del gráfico 1D: límites (epoch segundos) de la sesión
     * regular de mercado, para que la UI pueda distinguir qué velas son pre-market/after-hours
     * y pintarlas con un color apagado. Solo tiene sentido para ChartRange.ONE_DAY — en el
     * resto de rangos siempre será null (fetchCandlesOnce solo los guarda para 1 DÍA).
     */
    fun observeRegularSessionBounds(symbol: String, range: ChartRange): Flow<Pair<Long, Long>?> =
        dao.observeCandleFetchMeta(candleCacheKey(symbol, range.cacheKey)).map { meta ->
            val start = meta?.regularSessionStartEpochSeconds
            val end = meta?.regularSessionEndEpochSeconds
            if (start != null && end != null) start to end else null
        }

    suspend fun refreshYahooCandles(symbol: String, range: ChartRange) {
        val (yahooRange, yahooInterval) = range.toYahooRangeAndInterval()
        // allowExternalFallbacks=true: este método lo usan la ficha de detalle de un
        // stock y el dashboard (VIX/oro/rotación sectorial) — símbolos sueltos, uno
        // cada vez, nunca un escaneo masivo — así que aquí sí compensa esperar a
        // Twelve Data/Stooq si Yahoo se queda corto. El escaneo del screener usa
        // refreshYahooCandlesBulk más abajo, que deja el valor por defecto (false).
        val candles = fetchCandlesWithFallback(yahooFinanceApi, symbol, yahooRange, yahooInterval, range, allowExternalFallbacks = true)
        dao.clearCandles(symbol, range.cacheKey)
        dao.upsertCandles(candles)
        // Para 1 DÍA, fetchCandlesOnce YA ha escrito el meta (con los límites de la sesión
        // regular incluidos, ver comentario ahí) — escribir aquí OTRA VEZ lo pisaría con una
        // versión sin esos límites (los dos comparten la misma clave). Para el resto de rangos,
        // fetchCandlesOnce no toca el meta, así que aquí sigue haciendo falta como siempre.
        if (range != ChartRange.ONE_DAY) {
            dao.upsertCandleFetchMeta(
                CandleFetchMetaEntity(key = candleCacheKey(symbol, range.cacheKey), fetchedAtEpochMillis = System.currentTimeMillis())
            )
        }
    }

    /**
     * Pide las velas y, si vienen vacías o casi vacías, va probando alternativas
     * cada vez más robustas antes de rendirse:
     *  1) El intervalo pedido tal cual en Yahoo (p. ej. "1wk" para 1A/5A).
     *  2) Un intervalo más agregado servido YA agregado por Yahoo ("1mo").
     *  3) Velas DIARIAS de Yahoo (el intervalo más fiable del endpoint no oficial)
     *     agregadas NOSOTROS a semanas/meses en Kotlin (ver resampleDaily), en vez
     *     de depender del agregado semanal/mensual que hace el propio servidor.
     *  4) ÚLTIMO RECURSO, si hay API key de Twelve Data configurada: un proveedor
     *     de datos COMPLETAMENTE DISTINTO. Si el problema de Repsol/Endesa/
     *     Ferrovial fuera algo propio de los datos de Yahoo para esos valores (no
     *     solo de cómo los agrega), esto lo esquiva del todo al no depender en
     *     nada de Yahoo.
     *
     * Los pasos 1-3 fallaban sistemáticamente para varios valores del IBEX 35 con
     * dividendo flexible/scrip (Repsol, Endesa, Ferrovial...): Yahoo devuelve mal
     * sus datos para estos símbolos en el propio servidor, en varios intervalos a
     * la vez — el problema no es solo el agregado, puede ser el dato en origen.
     */
    private suspend fun fetchCandlesWithFallback(
        api: YahooFinanceApi,
        symbol: String,
        yahooRange: String,
        yahooInterval: String,
        chartRange: ChartRange,
        // Twelve Data y Stooq solo tienen sentido cuando el usuario está mirando UN
        // stock concreto en detalle (ficha con gráfica de 1A/5A) — es donde de verdad
        // hace falta el histórico completo. En el escaneo MASIVO del screener (ver
        // refreshYahooCandlesBulk, cientos de símbolos a la vez) activarlos disparaba
        // una petición a Stooq (limitada a 15/min) por CADA símbolo del IBEX que
        // Yahoo diera corto — con ~35 valores, el escaneo se podía quedar esperando
        // minutos enteros por turnos de la cola. Por defecto false (bulk); la ficha
        // de detalle lo pasa explícitamente a true.
        allowExternalFallbacks: Boolean = false
    ): List<CandleEntity> = coroutineScope {
        // Se guarda el motivo de cada intento que no dio suficientes datos, para
        // poder mostrarlo en el mensaje de error final si NINGUNO funciona — así,
        // en vez de un genérico "sin datos", se puede ver exactamente en qué capa
        // (Yahoo semanal/mensual/diario, o Twelve Data) y por qué está fallando.
        val attemptNotes = mutableListOf<String>()

        // CLAVE: "suficientes velas" NO puede ser simplemente ">= 2". Para ELE.MC en
        // 5y/1wk, Yahoo respondía 200 OK con un JSON de apenas 1362 bytes — 2 o 3
        // velas sueltas en vez de las ~260 esperadas para 5 años de semanas. Con el
        // umbral en 2, ese intento se daba por bueno de inmediato: se pintaba un
        // gráfico casi vacío (sin ningún error, porque "técnicamente" había
        // funcionado) y de paso se CANCELABA la petición a Twelve Data que iba en
        // paralelo, que quizás sí habría traído el histórico completo. chartRange.
        // outputSize es el nº de velas que se esperan para este rango (52 para 1A,
        // 260 para 5A); exigir al menos una cuarta parte de eso descarta las
        // respuestas "vacías a medias" sin ser tan estricto como para rechazar un
        // hueco puntual normal (festivo, símbolo con menos historial, etc.).
        val minAcceptableCandles = maxOf(2, chartRange.outputSize / 4)

        // Twelve Data se lanza YA, en paralelo con toda la cadena de Yahoo de abajo
        // (son proveedores y clientes HTTP distintos, no compiten por el mismo
        // rate limit) — antes esperaba a que los 3 intentos de Yahoo terminaran
        // primero, sumando su tiempo al de Twelve Data.
        //
        // EXCLUIDOS los símbolos ".MC" (IBEX 35): confirmado por log que Twelve Data
        // devuelve 404 para ELE.MC con la cuenta gratuita — su plan gratuito solo
        // cubre mercado de EE.UU. con acceso completo; el resto de bolsas del mundo
        // (incluida Bolsa de Madrid) van con "símbolos de prueba" limitados, y estos
        // valores del IBEX no están entre ellos. Intentarlo solo consigue hacer
        // esperar hasta un minuto entero (por el límite de 8 créditos/min) para un
        // 404 seguro. Si en algún momento se amplía el plan de Twelve Data, quitar
        // esta exclusión para que vuelva a intentarse.
        val twelveDataDeferred = if (
            allowExternalFallbacks &&
            (chartRange == ChartRange.ONE_YEAR || chartRange == ChartRange.FIVE_YEARS) &&
            apiKey.isNotBlank() &&
            !symbol.endsWith(".MC")
        ) {
            async { runCatching { fetchCandlesFromTwelveData(symbol, chartRange) } }
        } else {
            null
        }

        // Stooq (CSV, sin API key) se lanza igual de en paralelo, SOLO para símbolos
        // ".MC" (justo el hueco que deja Twelve Data arriba) y solo para 1A/5A. Es
        // un intento "a ver si suena la flauta": el formato de símbolo de Stooq para
        // Bolsa de Madrid no se ha podido verificar en vivo (ver StooqApi) — si
        // devuelve vacío o "N/D", el aviso de error lo dirá explícitamente.
        val stooqDeferred = if (
            allowExternalFallbacks &&
            (chartRange == ChartRange.ONE_YEAR || chartRange == ChartRange.FIVE_YEARS) &&
            symbol.endsWith(".MC")
        ) {
            async { runCatching { fetchCandlesFromStooq(symbol, chartRange) } }
        } else {
            null
        }

        val primary = try {
            val fetched = fetchCandlesOnce(api, symbol, yahooRange, yahooInterval, chartRange)
            // 1M pide velas de 1h a Yahoo (no tiene intervalo nativo de 4h) y aquí se
            // agregan a bloques de 4h — mismo motivo que el agregado diario->semanal de
            // más abajo: reduce el nº de velas a algo legible sin depender de un
            // intervalo que Yahoo no ofrece.
            if (yahooInterval == "1h") resampleFixedHours(fetched, hours = 4) else fetched
        } catch (e: Exception) {
            attemptNotes += "Yahoo $yahooInterval: ${e.message ?: e::class.simpleName}"
            emptyList()
        }
        if (primary.size >= minAcceptableCandles) {
            twelveDataDeferred?.cancel()
            stooqDeferred?.cancel()
            return@coroutineScope primary
        }
        if (primary.isNotEmpty()) attemptNotes += "Yahoo $yahooInterval: solo ${primary.size} vela(s) (mínimo $minAcceptableCandles)"

        val serverFallbackInterval = when (yahooInterval) {
            "1wk" -> "1mo"
            "1h" -> "1d"   // si las velas horarias de 1M fallan, mejor diario que nada
            "30m" -> "1d"
            "5m" -> "30m"
            else -> null
        }

        val serverFallback = if (serverFallbackInterval != null) {
            try {
                fetchCandlesOnce(api, symbol, yahooRange, serverFallbackInterval, chartRange)
            } catch (e: Exception) {
                attemptNotes += "Yahoo $serverFallbackInterval: ${e.message ?: e::class.simpleName}"
                emptyList()
            }
        } else {
            emptyList()
        }
        // El fallback mensual tiene, como es lógico, MENOS velas que el semanal para el
        // mismo periodo (5 años en meses son ~60, no ~260) — usar el mismo umbral que
        // arriba sería descartar un mensual perfectamente bueno. Se le exige al menos
        // una cuarta parte de LO QUE ESE INTERVALO PUEDE DAR razonablemente (aprox. 1/4
        // de lo semanal, ya que hay ~4 semanas por mes).
        val serverFallbackMin = maxOf(2, minAcceptableCandles / 4)
        if (serverFallback.size >= serverFallbackMin) {
            twelveDataDeferred?.cancel()
            stooqDeferred?.cancel()
            return@coroutineScope serverFallback
        }
        if (serverFallback.isNotEmpty()) attemptNotes += "Yahoo $serverFallbackInterval: solo ${serverFallback.size} vela(s) (mínimo $serverFallbackMin)"

        // Velas diarias + agregado propio a semana/mes. Solo aplica cuando el
        // intervalo pedido era semanal o mensual (1A/5A) — para 1S/1M ya se está
        // pidiendo algo igual o más granular que diario, así que este paso no
        // aporta nada nuevo.
        if (yahooInterval == "1wk" || yahooInterval == "1mo") {
            val daily = try {
                fetchCandlesOnce(api, symbol, yahooRange, "1d", chartRange)
            } catch (e: Exception) {
                attemptNotes += "Yahoo 1d: ${e.message ?: e::class.simpleName}"
                emptyList()
            }
            val resampled = resampleDaily(daily, toWeekly = yahooInterval == "1wk")
            if (resampled.size >= minAcceptableCandles) {
                twelveDataDeferred?.cancel()
                stooqDeferred?.cancel()
                return@coroutineScope resampled
            }
            if (daily.isNotEmpty()) {
                attemptNotes += "Yahoo 1d: ${daily.size} vela(s) diarias → ${resampled.size} tras agregar (mínimo $minAcceptableCandles)"
            }
        }

        // Ya ha terminado toda la cadena de Yahoo — ahora sí toca esperar (si no
        // habían terminado ya, mientras tanto) a Twelve Data y Stooq.
        val twelveDataResult = twelveDataDeferred?.await()?.fold(
            onSuccess = { it },
            onFailure = { e ->
                attemptNotes += "Twelve Data: ${e.message ?: e::class.simpleName}"
                emptyList()
            }
        ) ?: emptyList()
        if (twelveDataDeferred == null) {
            if (symbol.endsWith(".MC")) {
                attemptNotes += "Twelve Data: omitido (plan gratuito sin cobertura BME)"
            } else {
                attemptNotes += "Twelve Data: sin API key configurada (local.properties)"
            }
        } else if (twelveDataResult.size >= minAcceptableCandles) {
            stooqDeferred?.cancel()
            return@coroutineScope twelveDataResult
        } else if (twelveDataResult.isNotEmpty()) {
            attemptNotes += "Twelve Data: solo ${twelveDataResult.size} vela(s) (mínimo $minAcceptableCandles)"
        }

        val stooqResult = stooqDeferred?.await()?.fold(
            onSuccess = { it },
            onFailure = { e ->
                attemptNotes += "Stooq: ${e.message ?: e::class.simpleName}"
                emptyList()
            }
        ) ?: emptyList()
        if (stooqDeferred != null) {
            if (stooqResult.size >= minAcceptableCandles) {
                return@coroutineScope stooqResult
            } else if (stooqResult.isNotEmpty()) {
                attemptNotes += "Stooq: solo ${stooqResult.size} vela(s) (mínimo $minAcceptableCandles)"
            }
        }

        // Último recurso de verdad: si NADA llegó al umbral "bueno" pero alguna fuente
        // trajo AL MENOS 2 velas (lo mínimo que el gráfico puede pintar), mejor eso
        // que nada — pero solo se llega aquí si todo lo anterior fue claramente
        // insuficiente, nunca como salida rápida.
        val best = listOf(primary, serverFallback, twelveDataResult, stooqResult).maxByOrNull { it.size } ?: emptyList()
        if (best.size >= 2) return@coroutineScope best

        // Si NINGUNA fuente dio datos utilizables (ni siquiera Twelve Data), hay que
        // lanzar explícitamente: de lo contrario refreshYahooCandles() borraría la
        // caché existente (dao.clearCandles) y la sustituiría por una lista vacía o
        // insuficiente, dejando el símbolo "en blanco" hasta el próximo refresco en
        // vez de conservar los últimos datos válidos y reintentar más tarde. El
        // mensaje incluye el motivo de cada intento para poder diagnosticar sin
        // depender de logs.
        throw IllegalStateException(
            "Sin datos para $symbol (rango $yahooRange) — " + attemptNotes.joinToString(" · ")
        )
    }

    /**
     * Igual que refreshCandles() (Twelve Data) pero devolviendo la lista sin tocar
     * Room, para poder usarse como fallback dentro de fetchCandlesWithFallback.
     * Twelve Data no entiende el sufijo ".MC" de Yahoo para el IBEX 35: si el
     * símbolo lo lleva, se manda el ticker sin sufijo + mic_code=XMAD (Bolsa de
     * Madrid) por separado.
     */
    private suspend fun fetchCandlesFromTwelveData(symbol: String, chartRange: ChartRange): List<CandleEntity> {
        val (tickerForTwelveData, micCode) = if (symbol.endsWith(".MC")) {
            symbol.removeSuffix(".MC") to "XMAD"
        } else {
            symbol to null
        }
        val response = twelveDataApi.getTimeSeries(
            symbol = tickerForTwelveData,
            interval = chartRange.interval,
            outputSize = chartRange.outputSize,
            apiKey = apiKey,
            micCode = micCode
        )
        if (response.status == "error" || response.code != null) {
            throw IllegalStateException("Twelve Data error para $symbol: ${response.message}")
        }
        return response.values.orEmpty().map { it.toEntity(symbol, chartRange.cacheKey) }
    }

    /**
     * Último recurso para símbolos ".MC" cuando Yahoo devuelve histórico truncado:
     * Stooq, en CSV diario, agregado a semanas por nosotros con la misma
     * resampleDaily() que ya se usa para el fallback diario de Yahoo. Se pide
     * siempre diario (no semanal/mensual directamente) porque es el formato de
     * Stooq más simple de parsear con garantías, y de paso reutiliza el mismo
     * agregador ya probado.
     *
     * OJO: el sufijo de símbolo (`ele.mc` en minúsculas) es la convención más
     * probable pero NO se ha podido confirmar en vivo — ver aviso en StooqApi.
     * Si Stooq no reconoce el símbolo, su CSV de respuesta es una única línea
     * "Date,Open,High,Low,Close,Volume\nN/D,N/D,N/D,N/D,N/D,N/D" (sin datos),
     * que aquí se detecta y se trata como fallo, no como 0 velas silenciosas.
     */
    private suspend fun fetchCandlesFromStooq(symbol: String, chartRange: ChartRange): List<CandleEntity> {
        val stooqSymbol = symbol.removeSuffix(".MC").lowercase() + ".mc"
        val body = stooqApi.getDailyHistoryCsv(symbol = stooqSymbol, interval = "d").string()
        val lines = body.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (lines.size <= 1 || body.contains("N/D") || body.contains("Exceeded the daily hits limit")) {
            throw IllegalStateException("Stooq sin datos para $stooqSymbol (posible símbolo incorrecto o límite diario)")
        }
        // Cabecera esperada: Date,Open,High,Low,Close,Volume
        val daily = lines.drop(1).mapNotNull { line ->
            val cols = line.split(",")
            if (cols.size < 6) return@mapNotNull null
            val open = cols[1].toDoubleOrNull() ?: return@mapNotNull null
            val high = cols[2].toDoubleOrNull() ?: return@mapNotNull null
            val low = cols[3].toDoubleOrNull() ?: return@mapNotNull null
            val close = cols[4].toDoubleOrNull() ?: return@mapNotNull null
            val volume = cols[5].toLongOrNull()
            // Stooq da fecha "yyyy-MM-dd" sin hora — se ancla a medianoche UTC para
            // que resampleDaily() pueda parsearla igual que las de Yahoo (Instant ISO).
            val datetime = try {
                java.time.LocalDate.parse(cols[0]).atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toString()
            } catch (e: Exception) {
                return@mapNotNull null
            }
            CandleEntity(
                symbol = symbol,
                interval = chartRange.cacheKey,
                datetime = datetime,
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume
            )
        }
        return resampleDaily(daily, toWeekly = true)
    }

    /**
     * Agrupa velas DIARIAS en velas semanales (lunes-domingo, semana ISO) o
     * mensuales, para cuando el agregado semanal/mensual del propio Yahoo viene
     * roto. open = apertura del primer día del grupo; close = cierre del
     * último; high/low = máximo/mínimo del grupo; volume = suma del grupo.
     */
    private fun resampleDaily(daily: List<CandleEntity>, toWeekly: Boolean): List<CandleEntity> {
        if (daily.isEmpty()) return emptyList()
        val weekFields = java.time.temporal.WeekFields.ISO
        val groups = linkedMapOf<String, MutableList<CandleEntity>>()
        for (candle in daily) {
            val instant = try {
                java.time.Instant.parse(candle.datetime)
            } catch (e: Exception) {
                continue
            }
            val date = instant.atZone(java.time.ZoneOffset.UTC).toLocalDate()
            val bucketKey = if (toWeekly) {
                "${date.get(weekFields.weekBasedYear())}-W${date.get(weekFields.weekOfWeekBasedYear())}"
            } else {
                "${date.year}-${date.monthValue}"
            }
            groups.getOrPut(bucketKey) { mutableListOf() }.add(candle)
        }
        return groups.values.mapNotNull { group ->
            if (group.isEmpty()) return@mapNotNull null
            val sorted = group.sortedBy { it.datetime }
            CandleEntity(
                symbol = sorted.first().symbol,
                interval = sorted.first().interval,
                datetime = sorted.last().datetime,
                open = sorted.first().open,
                high = sorted.maxOf { it.high },
                low = sorted.minOf { it.low },
                close = sorted.last().close,
                volume = sorted.mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.sum()
            )
        }.sortedBy { it.datetime }
    }

    /**
     * Agrupa velas HORARIAS en bloques de [hours] horas (p. ej. 4h), por bucket de
     * tiempo absoluto UTC — Yahoo no tiene un intervalo nativo de 4h, así que se
     * pide 1h y se agrega aquí. Mismo criterio OHLCV que resampleDaily: open del
     * primer punto del bloque, close del último, high/low máximo/mínimo, volumen
     * sumado.
     */
    private fun resampleFixedHours(hourly: List<CandleEntity>, hours: Int): List<CandleEntity> {
        if (hourly.isEmpty()) return emptyList()
        val bucketSeconds = hours * 3600L
        val groups = linkedMapOf<Long, MutableList<CandleEntity>>()
        for (candle in hourly) {
            val instant = try {
                java.time.Instant.parse(candle.datetime)
            } catch (e: Exception) {
                continue
            }
            val bucketKey = instant.epochSecond / bucketSeconds
            groups.getOrPut(bucketKey) { mutableListOf() }.add(candle)
        }
        return groups.values.mapNotNull { group ->
            if (group.isEmpty()) return@mapNotNull null
            val sorted = group.sortedBy { it.datetime }
            CandleEntity(
                symbol = sorted.first().symbol,
                interval = sorted.first().interval,
                datetime = sorted.last().datetime,
                open = sorted.first().open,
                high = sorted.maxOf { it.high },
                low = sorted.minOf { it.low },
                close = sorted.last().close,
                volume = sorted.mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.sum()
            )
        }.sortedBy { it.datetime }
    }

    private suspend fun fetchCandlesOnce(
        api: YahooFinanceApi,
        symbol: String,
        yahooRange: String,
        yahooInterval: String,
        chartRange: ChartRange
    ): List<CandleEntity> {
        // AÑADIDO — horario extendido SOLO para 1 DÍA, pedido expresamente así ("añade horario
        // extendido, y un color apagado fuera de horas"). El resto de rangos no lo necesitan
        // (no son velas intradía) y activarlo ahí no tendría ningún efecto visible, así que se
        // deja en false por defecto en todos los demás casos.
        val response = api.getChart(symbol = symbol, range = yahooRange, interval = yahooInterval, includePrePost = chartRange == ChartRange.ONE_DAY)
        val result = response.chart.result?.firstOrNull()
            ?: throw IllegalStateException("Yahoo Finance error para $symbol: ${response.chart.error?.description}")
        if (chartRange == ChartRange.ONE_DAY) {
            // Límites REALES de la sesión regular para este símbolo y este día concreto (varían
            // por bolsa y por horario de verano/invierno) — se guardan aparte, no en cada vela,
            // para que la UI pueda distinguir qué velas son horario extendido sin tener que
            // asumir un horario fijo a mano (que fallaría para el IBEX 35 u otros símbolos no
            // estadounidenses). Ver CandleFetchMetaEntity.regularSession*.
            val regular = result.meta.currentTradingPeriod?.regular
            dao.upsertCandleFetchMeta(
                CandleFetchMetaEntity(
                    key = candleCacheKey(symbol, chartRange.cacheKey),
                    fetchedAtEpochMillis = System.currentTimeMillis(),
                    regularSessionStartEpochSeconds = regular?.start,
                    regularSessionEndEpochSeconds = regular?.end
                )
            )
        }
        return result.toCandleEntities(symbol, chartRange.cacheKey)
    }

    suspend fun refreshYahooCandlesIfStale(symbol: String, range: ChartRange) {
        val maxAge = if (range == ChartRange.ONE_DAY || range == ChartRange.ONE_WEEK) {
            CacheConfig.CANDLE_TTL_INTRADAY_MILLIS
        } else {
            CacheConfig.CANDLE_TTL_LONG_TERM_MILLIS
        }
        val key = candleCacheKey(symbol, range.cacheKey)
        candleMutexFor(key).withLock {
            val meta = dao.getCandleFetchMeta(key)
            if (isStale(meta?.fetchedAtEpochMillis, maxAge)) {
                refreshYahooCandles(symbol, range)
            }
        }
    }

    private fun candleCacheKey(symbol: String, rangeKey: String) = "$symbol:$rangeKey"

    /**
     * Cierre de AYER (sesión ya cerrada, la más reciente antes de hoy) — determinado por FECHA
     * REAL de cada vela diaria, no por posición en el array (mismo método, y mismo motivo, que
     * MarketMoversRepository ya usa para el banner — encontrado y confirmado con CrowdStrike:
     * asumir siempre "la última vela es hoy, a medias" fallaba cuando Yahoo aún no había
     * añadido la vela de hoy en el momento de la petición). Usado para que el gráfico de 1 DÍA
     * muestre la MISMA variación que el banner de ganadores/perdedores — antes el gráfico
     * comparaba contra la PRIMERA vela de hoy (la apertura), que puede diferir mucho del cierre
     * de ayer si el valor abrió con un hueco grande (como pasó con CrowdStrike tras resultados).
     *
     * Sin caché propia a propósito — es una sola petición ligera (igual que el banner: solo
     * "meta" + cierres, no velas completas), pedida una vez por apertura de gráfico en 1 DÍA,
     * no en un bucle por cientos de candidatos, así que no hace falta la infraestructura de
     * caché que sí necesitan las velas normales.
     */
    suspend fun fetchYesterdayClose(symbol: String): Double? {
        return try {
            val response = yahooFinanceApi.getChart(symbol, range = "5d", interval = "1d")
            val chartResult = response.chart.result?.firstOrNull() ?: return null
            // CAMBIADO — ahora delega en YahooDailyChangeCalculator, la misma lógica que usa
            // MarketMoversRepository.fetchMoverForSymbol para el banner de ganadores/perdedores:
            // antes esta función tenía su PROPIA copia de esta lógica, y con el tiempo podía
            // divergir de la del banner sin que nadie se diera cuenta hasta ver dos % distintos
            // para el mismo stock (reportado por el usuario con Adobe). Ver el comentario de esa
            // clase para el detalle completo.
            com.inversionadvisor.domain.indicators.YahooDailyChangeCalculator.compute(chartResult)?.yesterdayClose
        } catch (e: Exception) {
            null
        }
    }

    /**
     * AÑADIDO — misma petición que fetchYesterdayClose, pero devuelve TAMBIÉN el precio actual
     * (no solo el cierre de ayer), calculados los dos de una sola vez con
     * YahooDailyChangeCalculator. Se usa en la cabecera del gráfico 1D de la ficha de un stock
     * (ver StockDetailViewModel) para que el precio y el % mostrados ahí usen el MISMO precio en
     * vivo que el banner de ganadores/perdedores — antes esa cabecera usaba el cierre de la
     * ÚLTIMA VELA de 5 minutos ya cacheada, que podía quedarse un poco por detrás del precio en
     * vivo real (aparte del propio retraso de origen de Yahoo), sumando una segunda fuente de
     * discrepancia con el banner además de la del cierre de ayer.
     */
    suspend fun fetchTodayQuoteChange(symbol: String): com.inversionadvisor.domain.indicators.YahooDailyChangeCalculator.Result? {
        return try {
            val response = yahooFinanceApi.getChart(symbol, range = "5d", interval = "1d")
            val chartResult = response.chart.result?.firstOrNull() ?: return null
            val result = com.inversionadvisor.domain.indicators.YahooDailyChangeCalculator.compute(chartResult)
            // AÑADIDO — diagnóstico: se registra SIEMPRE, también en éxito (antes solo se
            // registraba el fallo) — reportado por el usuario que la cabecera de ADBE se quedó
            // en 0% en vez de +2%, y sin ver los valores exactos que se calcularon (precio en
            // vivo, cierre de ayer) no hay forma de saber si la causa es que los dos salieron
            // iguales (posible caso límite de fin de semana/mercado cerrado en
            // YahooDailyChangeCalculator, ver su comentario) o algún otro fallo.
            if (result == null) {
                android.util.Log.w("CompanyFinancialsFetch", "fetchTodayQuoteChange($symbol): sin resultado (ver YahooDailyChangeCalculator.compute)")
            } else {
                android.util.Log.i(
                    "CompanyFinancialsFetch",
                    "fetchTodayQuoteChange($symbol): precio en vivo=${result.currentPrice}, cierre(ayer)=${result.yesterdayClose}, %=${"%.2f".format(result.changePercent)}"
                )
            }
            result
        } catch (e: Exception) {
            android.util.Log.w("CompanyFinancialsFetch", "fetchTodayQuoteChange($symbol): ${e.message ?: e::class.simpleName}")
            null
        }
    }

    // ---- Velas DIARIAS reales, SOLO para SMA20/SMA50 "de verdad" en 6M/1A/5A ----
    //
    // El gráfico usa velas SEMANALES para la línea de precio en estos 3 rangos (menos datos,
    // más rápido) — pero una "SMA20"/"SMA50" de verdad se mide en DÍAS de mercado, no en
    // semanas (pedido expresamente así, con "más precisión aunque sean más datos"). Se piden
    // velas diarias APARTE, solo para calcular esas dos medias, y se muestran superpuestas
    // sobre el eje de la gráfica semanal (ver alignDailySmaToWeeklyDates en
    // StockDetailViewModel). Caché propia, con su propia clave — no comparte tabla con las
    // velas semanales del mismo rango, aunque sea el mismo símbolo.
    //
    // Sin la cadena de respaldos de fetchCandlesWithFallback (Twelve Data/Stooq) a propósito:
    // es un dato SECUNDARIO/complementario (la media superpuesta), no la serie principal de
    // precio — si Yahoo falla aquí, la media simplemente no se dibuja esta vez, pero el precio
    // y el resto de la gráfica no se ven afectados en absoluto.
    // FALLO ENCONTRADO Y CORREGIDO para 6M: pedir solo "6mo" de velas DIARIAS (~126) no daba
    // margen de arranque suficiente para la SMA50 (necesita 50 días solo para tener su PRIMER
    // valor) — dejaba sin media casi el primer 40% del rango visible (un hueco grande al
    // principio del gráfico). Se pide "1y" de margen de arranque (igual que ya usa 1A) — el
    // RANGO VISIBLE sigue siendo 6 meses (las velas semanales de precio, sin tocar), este
    // "1y" es solo de dónde saca datos la SMA para tener margen de sobra ANTES de esos 6 meses.
    private val dailySmaSourceRangeAndKey = mapOf(
        ChartRange.SIX_MONTHS to ("1y" to "SIX_MONTHS_DAILY_SMA"),
        ChartRange.ONE_YEAR to ("1y" to "ONE_YEAR_DAILY_SMA"),
        ChartRange.FIVE_YEARS to ("5y" to "FIVE_YEARS_DAILY_SMA")
    )

    fun observeDailyCandlesForSma(symbol: String, range: ChartRange): Flow<List<Candle>> {
        val cacheKey = dailySmaSourceRangeAndKey[range]?.second ?: return kotlinx.coroutines.flow.flowOf(emptyList())
        return dao.observeCandles(symbol, cacheKey).map { list -> list.map { it.toDomain() } }
    }

    suspend fun refreshDailyCandlesForSmaIfStale(symbol: String, range: ChartRange, maxAgeMillis: Long = CacheConfig.CANDLE_TTL_LONG_TERM_MILLIS) {
        val (yahooRange, cacheKey) = dailySmaSourceRangeAndKey[range] ?: return
        val key = candleCacheKey(symbol, cacheKey)
        candleMutexFor(key).withLock {
            val meta = dao.getCandleFetchMeta(key)
            if (!isStale(meta?.fetchedAtEpochMillis, maxAgeMillis)) return@withLock
            try {
                val response = yahooFinanceApi.getChart(symbol, range = yahooRange, interval = "1d")
                val result = response.chart.result?.firstOrNull() ?: return@withLock
                val entities = result.toCandleEntities(symbol, cacheKey)
                // CORREGIDO: si la respuesta viene "técnicamente bien" (result no nulo) pero sin
                // ninguna vela útil (p. ej. huecos raros de datos para ese símbolo en concreto),
                // NO se marca como hecha con éxito — antes SÍ se marcaba, dejando la caché
                // "atascada" durante 1 hora entera sin haber guardado ni una sola vela (mismo
                // patrón que ya se encontró y corrigió con la fecha de resultados). Así, un fallo
                // parcial se puede reintentar en la siguiente carga, en vez de esperar la TTL.
                if (entities.isEmpty()) {
                    android.util.Log.w("DailySmaFetch", "$symbol ($range): respuesta sin velas útiles, no se marca como hecha (se reintentará)")
                    return@withLock
                }
                dao.clearCandles(symbol, cacheKey)
                dao.upsertCandles(entities)
                dao.upsertCandleFetchMeta(CandleFetchMetaEntity(key = key, fetchedAtEpochMillis = System.currentTimeMillis()))
            } catch (e: Exception) {
                android.util.Log.w("DailySmaFetch", "$symbol ($range): ${e.message ?: e::class.simpleName}")
            }
        }
    }

    // ---- Velas MENSUALES reales, SOLO para el patrón de "bandera a largo plazo" ----
    //
    // Nueva granularidad, no usada en ningún otro sitio de la app hasta ahora — pedida
    // expresamente para comprobar el patrón de bandera (UptrendDetector.detectFlagPattern)
    // sobre las últimas 15 velas MENSUALES, además de sobre las diarias (que ya existían para
    // la SMA). Rango fijo "10y" (unos 120 meses) — de sobra para tener las 15 últimas velas
    // mensuales completas, con mucho margen. Caché propia, misma mecánica que las diarias.
    private val MONTHLY_FLAG_CACHE_KEY_SUFFIX = "MONTHLY_FLAG"
    private val MONTHLY_FLAG_YAHOO_RANGE = "10y"

    fun observeMonthlyCandlesForFlag(symbol: String): Flow<List<Candle>> {
        val cacheKey = "${symbol}_$MONTHLY_FLAG_CACHE_KEY_SUFFIX"
        return dao.observeCandles(symbol, cacheKey).map { list -> list.map { it.toDomain() } }
    }

    suspend fun refreshMonthlyCandlesForFlagIfStale(symbol: String, maxAgeMillis: Long = CacheConfig.CANDLE_TTL_MONTHLY_MILLIS) {
        val cacheKey = "${symbol}_$MONTHLY_FLAG_CACHE_KEY_SUFFIX"
        val key = candleCacheKey(symbol, cacheKey)
        candleMutexFor(key).withLock {
            val meta = dao.getCandleFetchMeta(key)
            if (!isStale(meta?.fetchedAtEpochMillis, maxAgeMillis)) return@withLock
            try {
                val response = yahooFinanceApi.getChart(symbol, range = MONTHLY_FLAG_YAHOO_RANGE, interval = "1mo")
                val result = response.chart.result?.firstOrNull() ?: return@withLock
                val entities = result.toCandleEntities(symbol, cacheKey)
                if (entities.isEmpty()) {
                    android.util.Log.w("MonthlyFlagFetch", "$symbol: respuesta sin velas útiles, no se marca como hecha (se reintentará)")
                    return@withLock
                }
                dao.clearCandles(symbol, cacheKey)
                dao.upsertCandles(entities)
                dao.upsertCandleFetchMeta(CandleFetchMetaEntity(key = key, fetchedAtEpochMillis = System.currentTimeMillis()))
            } catch (e: Exception) {
                android.util.Log.w("MonthlyFlagFetch", "$symbol: ${e.message ?: e::class.simpleName}")
            }
        }
    }

    /**
     * Igual que refreshYahooCandles/refreshYahooCandlesIfStale, pero con el
     * cliente "bulk" (más concurrencia y rate limit más alto) — pensado
     * para el escaneo masivo del screener sobre cientos de símbolos.
     * Escribe en la MISMA caché de Room que el resto de la app, así que
     * un símbolo ya consultado por el dashboard no se vuelve a pedir.
     */
    suspend fun refreshYahooCandlesBulk(symbol: String, range: ChartRange) {
        val (yahooRange, yahooInterval) = range.toYahooRangeAndInterval()
        val candles = fetchCandlesWithFallback(yahooFinanceBulkApi, symbol, yahooRange, yahooInterval, range)
        dao.clearCandles(symbol, range.cacheKey)
        dao.upsertCandles(candles)
        dao.upsertCandleFetchMeta(
            CandleFetchMetaEntity(key = candleCacheKey(symbol, range.cacheKey), fetchedAtEpochMillis = System.currentTimeMillis())
        )
    }

    suspend fun refreshYahooCandlesBulkIfStale(symbol: String, range: ChartRange) {
        val maxAge = if (range == ChartRange.ONE_DAY || range == ChartRange.ONE_WEEK) {
            CacheConfig.CANDLE_TTL_INTRADAY_MILLIS
        } else {
            CacheConfig.CANDLE_TTL_LONG_TERM_MILLIS
        }
        val key = candleCacheKey(symbol, range.cacheKey)
        candleMutexFor(key).withLock {
            val meta = dao.getCandleFetchMeta(key)
            if (isStale(meta?.fetchedAtEpochMillis, maxAge)) {
                refreshYahooCandlesBulk(symbol, range)
            }
        }
    }

    // ---- Cotizaciones y velas vía Twelve Data (sin usar en el dashboard por
    // ahora; se deja disponible para el futuro buscador de stocks) ----

    suspend fun refreshQuote(symbol: String) {
        val dto = twelveDataApi.getQuote(symbol = symbol, apiKey = apiKey)
        if (dto.status == "error" || dto.code != null) {
            throw IllegalStateException("Twelve Data error para $symbol: ${dto.message}")
        }
        dao.upsertQuote(dto.toEntity(symbol))
    }

    suspend fun refreshQuoteIfStale(symbol: String, maxAgeMillis: Long = CacheConfig.QUOTE_TTL_MILLIS) {
        val cached = dao.getQuoteOnce(symbol)
        if (isStale(cached?.updatedAtEpochMillis, maxAgeMillis)) {
            refreshQuote(symbol)
        }
    }

    suspend fun refreshQuoteBatch(symbols: List<String>) {
        if (symbols.isEmpty()) return
        val result = twelveDataApi.getQuoteBatch(
            symbolsCommaSeparated = symbols.distinct().joinToString(","),
            apiKey = apiKey
        )
        val entities = result.mapNotNull { (symbol, dto) ->
            if (dto.status == "error" || dto.code != null) null else dto.toEntity(symbol)
        }
        dao.upsertQuotes(entities)
    }

    suspend fun refreshQuoteBatchIfStale(symbols: List<String>, maxAgeMillis: Long = CacheConfig.QUOTE_TTL_MILLIS) {
        if (symbols.isEmpty()) return
        val distinctSymbols = symbols.distinct()
        val cached = dao.getQuotesOnce(distinctSymbols).associateBy { it.symbol }
        val staleSymbols = distinctSymbols.filter { isStale(cached[it]?.updatedAtEpochMillis, maxAgeMillis) }
        if (staleSymbols.isEmpty()) return
        staleSymbols.chunked(MAX_SYMBOLS_PER_BATCH).forEach { chunk -> refreshQuoteBatch(chunk) }
    }

    suspend fun refreshCandles(symbol: String, range: ChartRange) {
        val response = twelveDataApi.getTimeSeries(
            symbol = symbol,
            interval = range.interval,
            outputSize = range.outputSize,
            apiKey = apiKey
        )
        if (response.status == "error" || response.code != null) {
            throw IllegalStateException("Twelve Data error para $symbol: ${response.message}")
        }
        val candles = response.values.orEmpty().map { it.toEntity(symbol, range.cacheKey) }
        dao.clearCandles(symbol, range.cacheKey)
        dao.upsertCandles(candles)
        dao.upsertCandleFetchMeta(
            CandleFetchMetaEntity(key = candleCacheKey(symbol, range.cacheKey), fetchedAtEpochMillis = System.currentTimeMillis())
        )
    }

    suspend fun refreshCandlesIfStale(symbol: String, range: ChartRange) {
        val maxAge = if (range == ChartRange.ONE_DAY || range == ChartRange.ONE_WEEK) {
            CacheConfig.CANDLE_TTL_INTRADAY_MILLIS
        } else {
            CacheConfig.CANDLE_TTL_LONG_TERM_MILLIS
        }
        val meta = dao.getCandleFetchMeta(candleCacheKey(symbol, range.cacheKey))
        if (isStale(meta?.fetchedAtEpochMillis, maxAge)) {
            refreshCandles(symbol, range)
        }
    }

    // ---- Cripto (CoinGecko) — se guardan como cotizaciones normales para
    // reutilizar toda la infraestructura de caché y conversión EUR/USD ----

    suspend fun refreshCryptoQuotes() {
        val prices = coinGeckoApi.getSimplePrice(ids = "bitcoin,ethereum")
        val entities = prices.mapNotNull { (coinGeckoId, priceDto) ->
            val symbol = when (coinGeckoId) {
                "bitcoin" -> Symbols.BITCOIN
                "ethereum" -> Symbols.ETHEREUM
                else -> null
            } ?: return@mapNotNull null
            val close = priceDto.usd ?: return@mapNotNull null
            val changePercent = priceDto.usd_24h_change ?: 0.0
            val previousClose = if (changePercent != -100.0) close / (1 + changePercent / 100.0) else close
            QuoteEntity(
                symbol = symbol,
                name = if (symbol == Symbols.BITCOIN) "Bitcoin" else "Ethereum",
                close = close,
                previousClose = previousClose,
                changePercent = changePercent,
                volume = (priceDto.usd_24h_vol ?: 0.0).toLong(),
                averageVolume = 0L,
                fiftyTwoWeekLow = null,
                fiftyTwoWeekHigh = null,
                currency = "USD",
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        }
        dao.upsertQuotes(entities)
    }

    suspend fun refreshCryptoQuotesIfStale(maxAgeMillis: Long = CacheConfig.QUOTE_TTL_MILLIS) {
        val symbols = listOf(Symbols.BITCOIN, Symbols.ETHEREUM)
        val cached = dao.getQuotesOnce(symbols).associateBy { it.symbol }
        val anyStale = symbols.any { isStale(cached[it]?.updatedAtEpochMillis, maxAgeMillis) }
        if (anyStale) refreshCryptoQuotes()
    }

    /** Sparkline ilustrativo de 7 días (no confundir con la cotización en tiempo real). */
    fun observeCryptoSparkline(symbol: String): Flow<List<Double>> =
        dao.observeCryptoSparkline(symbol).map { it?.toPriceList().orEmpty() }

    suspend fun refreshCryptoSparkline(symbol: String) {
        val coinGeckoId = when (symbol) {
            Symbols.BITCOIN -> "bitcoin"
            Symbols.ETHEREUM -> "ethereum"
            else -> throw IllegalArgumentException("Sin sparkline de CoinGecko soportado para $symbol")
        }
        val chart = coinGeckoApi.getMarketChart(coinId = coinGeckoId, days = "7")
        val prices = chart.prices.orEmpty().map { it[1] }
        dao.upsertCryptoSparkline(
            CryptoSparklineEntity(
                symbol = symbol,
                pricesCsv = prices.joinToString(","),
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun refreshCryptoSparklineIfStale(symbol: String, maxAgeMillis: Long = CacheConfig.CRYPTO_SPARKLINE_TTL_MILLIS) {
        val cached = dao.getCryptoSparklineOnce(symbol)
        if (isStale(cached?.updatedAtEpochMillis, maxAgeMillis)) refreshCryptoSparkline(symbol)
    }

    // ---- Conversión de divisas (EUR/USD), vía Yahoo Finance ----

    suspend fun refreshExchangeRates() {
        refreshYahooQuotesIfStale(ForexPairs.ALL)
    }

    fun observeRatesToUsd(): Flow<Map<Currency, Double>> =
        dao.observeQuotes(ForexPairs.ALL).map { entities ->
            val rawPairRates = entities.associate { it.symbol to it.close }
            CurrencyConverter.buildRatesToUsd(rawPairRates)
        }

    // ---- Fear & Greed Index de CNN ----

    fun observeCnnFearGreed(): Flow<CnnFearGreedStatus?> =
        dao.observeFearGreed().map { it?.toDomain() }

    suspend fun refreshCnnFearGreed() {
        val startDate = java.time.LocalDate.now().minusDays(30).toString()
        val response = cnnFearGreedApi.getGraphData(startDate)
        val current = response.fearAndGreed
            ?: throw IllegalStateException("CNN Fear & Greed: respuesta vacía o formato inesperado")
        dao.upsertFearGreed(
            FearGreedEntity(
                score = current.score ?: 0.0,
                rating = current.rating ?: "unknown",
                previousClose = current.previousClose,
                previousOneWeek = current.previousOneWeek,
                previousOneMonth = current.previousOneMonth,
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun refreshCnnFearGreedIfStale(maxAgeMillis: Long = CacheConfig.QUOTE_TTL_MILLIS) {
        val cached = dao.getFearGreedOnce()
        if (isStale(cached?.updatedAtEpochMillis, maxAgeMillis)) refreshCnnFearGreed()
    }

    // ---- Fecha de próxima publicación de resultados trimestrales ----
    //
    // SEGUNDA VERSIÓN — cambiada de fuente por completo (ver comentario de EarningsDateEntity):
    // v7/finance/quote de Yahoo devolvía 401 "Invalid Cookie"/"Invalid Crumb" de forma
    // silenciosa (exige un crumb+cookie de sesión que esta app no implementa) — por eso nunca
    // llegaba a mostrarse nada. Ahora se extrae del HTML ya renderizado de la página de
    // cotización, la MISMA que ya se pide para Valor Empresa e Ingresos netos (ver
    // refreshCompanyFinancialsInternal) — reutilizando esa lectura, sin petición nueva.

    fun observeEarningsDate(symbol: String): Flow<com.inversionadvisor.data.local.entities.EarningsDateEntity?> =
        dao.observeEarningsDate(symbol)

    /**
     * Guarda el texto de fecha de resultados ya extraído por refreshCompanyFinancialsInternal
     * (que es quien realmente pide y descodifica la página) — separado en su propia función
     * para poder llamarlo también desde refreshEarningsDateIfStale si esa caché concreta ha
     * caducado pero la de "Datos de la empresa" en general no (evita quedarse sin la fecha de
     * resultados solo porque el resto de datos de esa tarjeta siguen frescos).
     */
    private suspend fun saveEarningsDateText(symbol: String, text: String?) {
        dao.upsertEarningsDate(
            com.inversionadvisor.data.local.entities.EarningsDateEntity(
                symbol = symbol,
                earningsDateText = text,
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun refreshEarningsDateIfStale(symbol: String, maxAgeMillis: Long = CacheConfig.EARNINGS_DATE_TTL_MILLIS) {
        val cached = dao.getEarningsDateOnce(symbol)
        val hasText = cached?.earningsDateText != null
        // Con texto ya guardado: TTL normal (24h, [maxAgeMillis]). SIN texto (fila inexistente,
        // o "ya lo intenté, no encontré nada"): TTL más corta (2 min) — para no quedarse sin
        // reintentar durante todo un día si el motivo real era un fallo temporal, o una
        // etiqueta que se acababa de arreglar en el código mientras la fila vacía seguía
        // "fresca" según su propia marca de tiempo (esto es justo lo que pasó: se corrigió la
        // lógica de extracción, pero como ya había una fila vacía guardada de un intento
        // anterior, con menos de 24h, nunca se volvía a intentar). Tampoco se reintenta en
        // CADA visita si de verdad Yahoo no tiene el dato para ese símbolo — 1h de por medio.
        val effectiveMaxAge = if (hasText) maxAgeMillis else 2 * 60_000L
        if (!isStale(cached?.updatedAtEpochMillis, effectiveMaxAge)) return
        // NO lanza su propia carga de WebView (sería una SEGUNDA carga de la misma página,
        // cara) — delega en refreshCompanyFinancialsIfStale, que ya pide esa misma página y
        // ahora también guarda la fecha de resultados de ahí (ver quoteDeferred más abajo).
        if (!hasText) {
            // Sin texto guardado todavía (fila inexistente, o existente pero vacía) — fuerza el
            // refresco de "Datos de la empresa" aunque ESA caché siga fresca (maxAgeMillis=0),
            // para no quedarse sin fecha de resultados hasta que esa otra TTL (6h) caduque por
            // su cuenta.
            refreshCompanyFinancialsIfStale(symbol, maxAgeMillis = 0L)
        } else {
            refreshCompanyFinancialsIfStale(symbol)
        }
    }

    /**
     * true si el texto de fecha de resultados guardado contiene un año/mes/día que cae dentro
     * de las próximas 3 semanas desde ahora — false si no hay texto guardado, o si no se puede
     * extraer una fecha reconocible de él (mejor no penalizar de más que inventar una fecha).
     *
     * AVISO HONESTO: es una extracción best-effort sobre texto libre (Yahoo suele dar un RANGO,
     * "28 oct - 2 nov, 2026", no un único día) — busca el PRIMER día+mes+año reconocible dentro
     * del texto guardado. Con meses en español o inglés, abreviados o completos.
     */
    /**
     * El texto de fecha de resultados suele ser RELATIVO cuando está próximo ("Hoy a las 6
     * p.m.", "en 2 días") — confirmado contra la página real (ver YahooQuotePageParser) — así
     * que primero se comprueba eso (obviamente "dentro de 3 semanas" si dice "hoy" o "en" pocos
     * días), y solo si no encaja ahí se intenta una fecha absoluta día/mes/año, para las
     * publicaciones más lejanas en el tiempo.
     */
    fun isEarningsWithinThreeWeeks(entity: com.inversionadvisor.data.local.entities.EarningsDateEntity?): Boolean {
        val text = entity?.earningsDateText ?: return false
        val lower = text.lowercase()
        if (lower.startsWith("hoy") || lower.startsWith("mañana")) return true
        Regex("""en\s+(\d{1,2})\s+d[ií]as?""").find(lower)?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: return@let
            if (days in 0..21) return true
        }
        Regex("""in\s+(\d{1,2})\s+days?""").find(lower)?.let { match ->
            val days = match.groupValues[1].toIntOrNull() ?: return@let
            if (days in 0..21) return true
        }
        val date = parseApproximateDate(text) ?: return false
        val today = java.time.LocalDate.now()
        val in3Weeks = today.plusWeeks(3)
        return !date.isBefore(today) && !date.isAfter(in3Weeks)
    }

    private val monthNameToNumber = mapOf(
        "ene" to 1, "jan" to 1, "feb" to 2, "mar" to 3, "abr" to 4, "apr" to 4,
        "may" to 5, "jun" to 6, "jul" to 7, "ago" to 8, "aug" to 8,
        "sep" to 9, "sept" to 9, "oct" to 10, "nov" to 11, "dic" to 12, "dec" to 12
    )

    /** Busca "día + nombre de mes + año" (en cualquier orden común) dentro de un texto libre. */
    private fun parseApproximateDate(text: String): java.time.LocalDate? {
        val match = Regex("""(\d{1,2})\s*([A-Za-zñÑ]{3,9})\.?\s*,?\s*(\d{4})""").find(text)
            ?: Regex("""([A-Za-zñÑ]{3,9})\.?\s+(\d{1,2}),?\s*(\d{4})""").find(text)
            ?: return null
        val groups = match.groupValues
        val (day, monthText, year) = if (groups[1].toIntOrNull() != null) {
            Triple(groups[1], groups[2], groups[3])
        } else {
            Triple(groups[2], groups[1], groups[3])
        }
        val monthNumber = monthNameToNumber[monthText.take(3).lowercase()] ?: return null
        return try {
            java.time.LocalDate.of(year.toInt(), monthNumber, day.toInt())
        } catch (e: Exception) {
            null
        }
    }

    // ---- Sentimiento AAII real: % de inversores alcistas/neutrales/bajistas ----
    //
    // AAII no tiene API JSON pública y su página está detrás de un firewall
    // anti-bots que exige ejecutar JavaScript — una petición HTTP simple (OkHttp)
    // recibe una página de verificación en vez de la tabla real (confirmado:
    // tanto la página como su .xls de descarga directa dan el mismo bloqueo).
    // La única vía con alguna posibilidad es cargar la página en un WebView
    // OCULTO (ver HiddenWebViewScraper), que sí ejecuta JavaScript de verdad y
    // puede llegar a resolver el reto — SIN GARANTÍA: algunos sistemas
    // anti-bots también detectan WebViews automatizados. Si sigue fallando,
    // el mensaje de error trae el título/inicio del HTML recibido (ver
    // AaiiSentimentHtmlParser) para poder diagnosticar qué está pasando.
    //
    // TTL propia y larga (ver CacheConfig.AAII_SENTIMENT_TTL_MILLIS): la
    // encuesta solo se publica 1 vez/semana (jueves), y cada refresco es
    // relativamente costoso (carga una página web entera en un WebView).

    fun observeAaiiSentiment(): Flow<AaiiSentiment?> =
        dao.observeAaiiSentiment().map { it?.toDomain() }

    suspend fun refreshAaiiSentiment() {
        val scraper = hiddenWebViewScraper
            ?: throw IllegalStateException("AAII: sin WebView disponible (MarketRepository construido sin Context)")
        val html = scraper.fetchRenderedHtml(
            "https://www.aaii.com/sentimentsurvey/sent_results",
            // Umbral bajo a propósito: esta página es una tabla sencilla, no una SPA
            // pesada como las de Yahoo — el umbral por defecto (150.000, pensado para
            // esas) haría reintentar sin necesidad aquí, solo alargando la espera.
            minAcceptableHtmlLength = 5_000
        )
        val recentRows = AaiiSentimentHtmlParser.parseRecentRows(html, maxRows = 8)
        val latest = recentRows.first()
        // recentRows viene más reciente primero; el sparkline se pinta de
        // izquierda (antigua) a derecha (reciente), así que se invierte.
        val spreadHistoryChronological = recentRows.reversed().map { it.bullishPercent - it.bearishPercent }
        dao.upsertAaiiSentiment(
            AaiiSentimentEntity(
                reportedDateLabel = latest.reportedDateLabel,
                bullishPercent = latest.bullishPercent,
                neutralPercent = latest.neutralPercent,
                bearishPercent = latest.bearishPercent,
                spreadHistoryCsv = spreadHistoryChronological.joinToString(","),
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun refreshAaiiSentimentIfStale(maxAgeMillis: Long = CacheConfig.AAII_SENTIMENT_TTL_MILLIS) {
        val cached = dao.getAaiiSentimentOnce()
        if (isStale(cached?.updatedAtEpochMillis, maxAgeMillis)) refreshAaiiSentiment()
    }

    // ---- Volumen de mercado (Yahoo Finance) — no se usa en el dashboard,
    // se deja disponible para cuando se muestre a nivel de stock individual ----

    fun observeMarketVolume(symbol: String): Flow<MarketVolume?> =
        dao.observeMarketVolume(symbol).map { it?.toDomain() }

    suspend fun refreshMarketVolume(symbol: String) {
        val response = yahooFinanceApi.getChart(symbol = symbol, range = "1mo", interval = "1d")
        val result = response.chart.result?.firstOrNull()
            ?: throw IllegalStateException("Yahoo Finance error para $symbol: ${response.chart.error?.description}")
        val volumes = result.indicators?.quote?.firstOrNull()?.volume?.filterNotNull().orEmpty()
        if (volumes.size < 2) throw IllegalStateException("Yahoo Finance: histórico de volumen insuficiente para $symbol")

        val today = volumes.last()
        val average = volumes.dropLast(1).average()
        val relativeVolume = if (average > 0) today / average else 1.0

        dao.upsertMarketVolume(
            MarketVolumeEntity(
                symbol = symbol,
                relativeVolume = relativeVolume,
                todayVolume = today,
                averageVolume = average.toLong(),
                updatedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun refreshMarketVolumeIfStale(symbol: String, maxAgeMillis: Long = CacheConfig.QUOTE_TTL_MILLIS) {
        val cached = dao.getMarketVolumeOnce(symbol)
        if (isStale(cached?.updatedAtEpochMillis, maxAgeMillis)) refreshMarketVolume(symbol)
    }

    // ---- PER (trailing P/E): ETFs sectoriales + stocks individuales ----
    //
    // Usa v7/finance/quote de Yahoo (endpoint distinto al de las velas), que sí
    // trae fundamentales como trailingPE. Una sola tabla en Room (pe_ratio) sirve
    // tanto para los 13 ETFs sectoriales como para cualquier stock individual.

    // ---- PER (trailing P/E): ETFs sectoriales + stocks individuales ----
    //
    // Quinto intento tras cuatro callejones sin salida:
    //  1) Yahoo v7/finance/quote → 401 (necesita autenticación que no tenemos).
    //  2) Twelve Data /statistics → 403 (fundamentales bloqueados en el plan
    //     gratuito, confirmado — falla igual para ETFs que para acciones
    //     sueltas como AMZN).
    //  3) Página HTML de Yahoo vía petición HTTP simple → sin datos parseables,
    //     ni siquiera para AMZN — Yahoo carga esos datos con JavaScript
    //     después de la carga inicial.
    //  4) Página HTML de Yahoo vía WebView (con clic automático en el aviso de
    //     cookies) → tampoco, sin datos parseables ni para AMZN.
    // Ahora se usa Finviz (finviz.com), confirmado accesible sin bloqueo:
    //  - Sectores: UNA sola petición a su tabla de grupos por sector trae el
    //    PER de los 11 sectores GICS de golpe (ver FinvizPeParser) — mucho más
    //    simple y rápido que pedir cada ETF por separado. SMH (semiconductores)
    //    e ITA (defensa) no tienen fila propia en Finviz, así que se les asigna
    //    el PER de su sector padre (Tecnología / Industrial) como APROXIMACIÓN,
    //    no el dato específico de ese ETF.
    //  - Stock individual: su página de cotización — sin confirmar en vivo si
    //    esta página en concreto también es accesible (un intento directo de
    //    comprobarlo dio 404), así que se usa como primer intento con el
    //    WebView de Yahoo como respaldo si Finviz falla.

    /** PER medio de cada ETF sectorial ya en caché — no dispara ningún refresco. */
    fun observeSectorPeRatios(): Flow<Map<String, Double>> =
        dao.observePeRatios(Symbols.SECTOR_ETFS.keys.toList()).map { rows ->
            val live = rows.mapNotNull { row -> row.trailingPe?.let { row.symbol to it } }.toMap()
            // AÑADIDO — cuando Finviz no trae PER en vivo para un sector (ver
            // Symbols.SECTOR_PE_FALLBACK), se usa el valor de respaldo para que el sector no se
            // quede sin badge de PER; en cuanto Finviz vuelve a traer el dato real, ese vale
            // siempre por encima del respaldo (live tiene prioridad, el fallback solo rellena
            // huecos).
            Symbols.SECTOR_PE_FALLBACK + live
        }

    suspend fun refreshSectorPeRatiosIfStale(maxAgeMillis: Long = CacheConfig.PE_RATIO_TTL_MILLIS) {
        // Basta con comprobar uno de los ETFs (se refrescan siempre todos juntos
        // en una sola petición a Finviz, así que envejecen todos a la vez).
        val sample = dao.getPeRatioOnce(Symbols.SECTOR_ETFS.keys.first())
        if (!isStale(sample?.updatedAtEpochMillis, maxAgeMillis)) return

        // AÑADIDO — reintento, igual que ya se hizo con los ingresos netos de Yahoo: la
        // ausencia intermitente de PER por sector en algunos stocks (reportada por el usuario,
        // confirmada como sana la mayoría de las veces en "Momento idóneo para la venta") no
        // parece un fallo de parseo — los nombres de sector de Finviz coinciden exactamente con
        // los que espera el analizador — sino que Finviz puede degradar/limitar peticiones
        // automatizadas de forma puntual (mismo patrón que ya vimos con AAII). Antes, un solo
        // fallo dejaba TODOS los sectores sin dato hasta que caducara la caché entera. Ahora se
        // reintenta hasta 2 veces más antes de rendirse.
        suspend fun attemptFetch(): Map<String, Double> {
            val html = finvizApi.getSectorGroupsHtml().string()
            return FinvizPeParser.parseSectorPe(html)
        }

        var peByEtf: Map<String, Double> = emptyMap()
        var lastError: Exception? = null
        for (attempt in 1..3) {
            try {
                peByEtf = attemptFetch()
                if (peByEtf.size >= Symbols.SECTOR_ETFS.size) break // completo, no hace falta reintentar más
                android.util.Log.w("PeRatioFetch", "Sector PE: intento $attempt trajo solo ${peByEtf.size} de ${Symbols.SECTOR_ETFS.size} sectores, reintentando")
            } catch (e: Exception) {
                lastError = e
                android.util.Log.w("PeRatioFetch", "Sector PE (Finviz) intento $attempt: ${e.message ?: e::class.simpleName}")
            }
        }

        if (peByEtf.isEmpty() && lastError != null) {
            // Los 3 intentos fallaron del todo — se guarda igualmente un registro con
            // trailingPe=null y la marca de tiempo de AHORA para que la TTL evite reintentar en
            // bucle en cada refresco si el fallo es persistente.
            val now = System.currentTimeMillis()
            dao.upsertPeRatios(Symbols.SECTOR_ETFS.keys.map { PeRatioEntity(symbol = it, trailingPe = null, updatedAtEpochMillis = now) })
            return
        }

        val now = System.currentTimeMillis()
        val rows = Symbols.SECTOR_ETFS.keys.map { etfSymbol ->
            PeRatioEntity(symbol = etfSymbol, trailingPe = peByEtf[etfSymbol], updatedAtEpochMillis = now)
        }
        dao.upsertPeRatios(rows)
        val missing = Symbols.SECTOR_ETFS.keys.filter { it !in peByEtf }
        if (missing.isNotEmpty()) {
            android.util.Log.w("PeRatioFetch", "Sector PE: sin dato para $missing tras 3 intentos parseando la tabla de Finviz")
        }
    }

    /**
     * PER de UN stock concreto (ficha de detalle / Momento idóneo para la venta).
     * Finviz primero; si falla o no trae dato, se prueba con el WebView de Yahoo
     * como respaldo (más lento, pero con más posibilidades si Finviz falla para
     * ese símbolo en concreto).
     */
    suspend fun refreshStockPeIfStale(
        symbol: String,
        maxAgeMillis: Long = CacheConfig.PE_RATIO_TTL_MILLIS,
        /** false para saltarse el respaldo de WebView si Finviz falla — pensado para Top10, que
         *  pide el PER de decenas/cientos de candidatos A LA VEZ: cada WebView es una instancia
         *  de navegador entera, atada al hilo principal (Android exige que WebView se use ahí);
         *  con muchos candidatos a la vez cayendo en este respaldo, no se paralelizan de verdad
         *  entre sí — se pisan en el mismo hilo, con una espera fija de 2,5s cada uno, y eso es
         *  lo que realmente hacía lento el cálculo de Top10, no el número de candidatos. Con
         *  false, si Finviz no tiene el PER de un candidato, se acepta "sin dato" para él en
         *  vez de pagar ese coste — el resto de categorías de la fórmula le siguen puntuando
         *  con normalidad, "Valoración" sale como sin dato SOLO para ese candidato en concreto
         *  (igual que ya podía pasar antes si ni Finviz ni Yahoo tenían el PER). La ficha de un
         *  stock suelto y "Futuras compras" siguen con el respaldo activado (true) — ahí sí se
         *  puede permitir esa espera, al no ser un cálculo por lotes. */
        allowWebViewFallback: Boolean = true
    ) {
        val cached = dao.getPeRatioOnce(symbol)
        if (!isStale(cached?.updatedAtEpochMillis, maxAgeMillis)) return

        // NUEVO — Finviz es un screener centrado en el mercado de EE.UU.: no tiene cobertura
        // del IBEX 35, así que para ".MC" se salta directamente esta llamada (ahorra una
        // petición que siempre va a fallar) y se va derecho al respaldo de WebView de Yahoo.
        val esIbex = symbol.endsWith(".MC")
        var hasNegativeTrailingEarnings = false
        val finvizPe = if (esIbex) {
            null
        } else {
            try {
                val html = finvizApi.getStockQuoteHtml(symbol).string()
                val pe = FinvizPeParser.parseStockPe(html)
                // NUEVO — pedido expresamente: distinguir cuándo el trailing P/E no existe
                // porque el beneficio de los últimos 12 meses es NEGATIVO (caso real: Lumentum),
                // en vez de dejarlo pasar en silencio con el fallback a Forward P/E sin ningún
                // rastro de por qué el trailing no estaba. Se guarda en PeRatioEntity y se
                // muestra como aviso en la interfaz (ver StockDetailScreen).
                val epsTtm = FinvizPeParser.parseEpsTtm(html)
                hasNegativeTrailingEarnings = epsTtm != null && epsTtm < 0.0
                // NUEVO — diagnóstico para el camino de Finviz (Lumentum y otros reportados con
                // "PER vs sector" fallando): antes solo existía este log de contexto para el
                // camino de Yahoo WebView (.MC, ver más abajo) — sin él, si Finviz cambia algo en
                // su maquetación o la etiqueta real no es exactamente "P/E", no había forma de
                // saberlo sin adivinar a ciegas.
                if (pe == null) {
                    val bodyText = org.jsoup.Jsoup.parse(html).text()
                    val indice = bodyText.indexOf("P/E", ignoreCase = true)
                    val contexto = if (indice >= 0) bodyText.substring(indice, minOf(bodyText.length, indice + 80)) else "(\"P/E\" no aparece en el texto de la página)"
                    android.util.Log.w("PeRatioFetch", "Stock PE Finviz ($symbol): sin PER parseable — contexto tras \"P/E\": \"$contexto\"")
                }
                pe
            } catch (e: Exception) {
                android.util.Log.w("PeRatioFetch", "Stock PE Finviz ($symbol): ${e.message ?: e::class.simpleName}")
                null
            }
        }
        if (finvizPe != null) {
            dao.upsertPeRatios(listOf(PeRatioEntity(symbol = symbol, trailingPe = finvizPe, hasNegativeTrailingEarnings = hasNegativeTrailingEarnings, updatedAtEpochMillis = System.currentTimeMillis())))
            return
        }

        if (!allowWebViewFallback) {
            dao.upsertPeRatios(listOf(PeRatioEntity(symbol = symbol, trailingPe = null, hasNegativeTrailingEarnings = hasNegativeTrailingEarnings, updatedAtEpochMillis = System.currentTimeMillis())))
            return
        }

        val scraper = hiddenWebViewScraper
        if (scraper == null) {
            dao.upsertPeRatios(listOf(PeRatioEntity(symbol = symbol, trailingPe = null, hasNegativeTrailingEarnings = hasNegativeTrailingEarnings, updatedAtEpochMillis = System.currentTimeMillis())))
            return
        }
        // Mismo motivo que en refreshCompanyFinancialsIfStale: si hay un escaneo pesado en
        // marcha, se espera a que termine antes de competir por el WebView con esa avalancha
        // de tráfico. CORREGIDO — usa isActivelyScanning, NO isBusy: isBusy se queda "ocupado"
        // 60s DE MÁS tras terminar (a propósito, para el banner de conexión), y esperar eso
        // aquí colgaba esta petición mucho después de que el escaneo real hubiera acabado.
        if (com.inversionadvisor.data.connectivity.NetworkActivityTracker.isActivelyScanning.value) {
            android.util.Log.i("PeRatioFetch", "$symbol: hay un escaneo pesado en marcha, esperando a que termine antes de usar el WebView")
            com.inversionadvisor.data.connectivity.NetworkActivityTracker.isActivelyScanning.first { !it }
        }
        try {
            val html = scraper.fetchRenderedHtml("https://finance.yahoo.com/quote/$symbol/", extraWaitMillis = 2_500L)
            val pe = YahooQuotePageParser.parseTrailingPe(html)
            dao.upsertPeRatios(listOf(PeRatioEntity(symbol = symbol, trailingPe = pe, updatedAtEpochMillis = System.currentTimeMillis())))
            if (pe == null) {
                android.util.Log.w("PeRatioFetch", "Stock PE ($symbol): Finviz y Yahoo (WebView) sin PER parseable")
                if (esIbex) {
                    // AÑADIDO — diagnóstico específico para IBEX 35: si esto sigue fallando tras
                    // el arreglo de formato numérico europeo (ver YahooQuotePageParser), hace
                    // falta ver el texto real que llegó para ajustar la etiqueta/formato exactos,
                    // mismo patrón que ya usa CompanyFinancialsFetch con debugAllLabelOccurrences.
                    val bodyText = org.jsoup.Jsoup.parse(html).text()
                    val indice = bodyText.indexOf("P/E", ignoreCase = true)
                    val contexto = if (indice >= 0) bodyText.substring(indice, minOf(bodyText.length, indice + 80)) else "(\"P/E\" no aparece en el texto de la página)"
                    android.util.Log.w("PeRatioFetch", "Stock PE IBEX ($symbol): contexto tras \"P/E\": \"$contexto\"")
                }
            }
        } catch (e: Exception) {
            // Igual que arriba: sin PER, el resto de "Momento idóneo para la venta" sigue funcionando.
            // Se guarda el intento fallido para no repetir en bucle hasta que pase la TTL.
            android.util.Log.w("PeRatioFetch", "Stock PE Yahoo WebView ($symbol): ${e.message ?: e::class.simpleName}")
            dao.upsertPeRatios(listOf(PeRatioEntity(symbol = symbol, trailingPe = null, updatedAtEpochMillis = System.currentTimeMillis())))
        }
    }

    /**
     * PER en LOTE, para el pool entero de candidatos de Top10 de una vez — se agrupan de 20 en
     * 20 (el límite del screener de Finviz en el plan gratuito) y se piden EN PARALELO entre sí
     * (el rate limit del cliente, 20/min, ya reparte las peticiones si hicieran falta más de
     * 20 en la misma ventana). Con esto, un pool de 150 candidatos son ~8 peticiones en vez de
     * 150 — antes esto era el cuello de botella real del cálculo de Top10 (con el límite de
     * 20/min de este mismo cliente, 150+ peticiones individuales tardaban 7-8 minutos solo aquí).
     *
     * Solo guarda en caché los símbolos que Finviz SÍ trae con PER — los que no aparecen en el
     * lote (o el lote entero falla) se dejan tal cual estén en caché; el llamador sigue usando
     * refreshStockPeIfStale(allowWebViewFallback=false) para cada uno después, que ya no hace
     * nada si el símbolo ya se resolvió aquí (comprueba la caché primero) y si no, se acepta
     * "sin dato" para ÉL EN CONCRETO, sin bloquear al resto — ver comentario de esa función.
     */
    suspend fun refreshStockPesInBulk(symbols: List<String>, maxAgeMillis: Long = CacheConfig.PE_RATIO_TTL_MILLIS) {
        val staleSymbols = symbols.distinct().filter { symbol ->
            isStale(dao.getPeRatioOnce(symbol)?.updatedAtEpochMillis, maxAgeMillis)
        }
        if (staleSymbols.isEmpty()) return

        coroutineScope {
            staleSymbols.chunked(20).map { chunk ->
                async {
                    try {
                        val html = finvizApi.getScreenerHtml(tickers = chunk.joinToString(",")).string()
                        val peByTicker = FinvizPeParser.parseScreenerPeByTicker(html)
                        if (peByTicker.isNotEmpty()) {
                            val now = System.currentTimeMillis()
                            dao.upsertPeRatios(peByTicker.map { (symbol, pe) -> PeRatioEntity(symbol = symbol, trailingPe = pe, updatedAtEpochMillis = now) })
                        }
                        Unit
                    } catch (e: Exception) {
                        android.util.Log.w("PeRatioFetch", "PER en lote (${chunk.size} símbolos): ${e.message ?: e::class.simpleName}")
                        Unit
                    }
                }
            }.awaitAll()
        }
    }

    fun observeStockPe(symbol: String): Flow<Double?> =
        dao.observePeRatios(listOf(symbol)).map { rows -> rows.firstOrNull()?.trailingPe }

    /** true si el beneficio de los últimos 12 meses (EPS ttm en Finviz) es negativo — pedido
     *  expresamente para reflejarlo en la interfaz cuando el "PER" mostrado en realidad viene
     *  del Forward P/E (fallback, ver FinvizPeParser.parseStockPe), ya que Finviz no puede
     *  calcular un trailing P/E con beneficio negativo. Caso real: Lumentum. Siempre false para
     *  los stocks .MC (Yahoo WebView), que de momento no comprueban esto. */
    fun observeHasNegativeTrailingEarnings(symbol: String): Flow<Boolean> =
        dao.observePeRatios(listOf(symbol)).map { rows -> rows.firstOrNull()?.hasNegativeTrailingEarnings ?: false }

    // ---- Valor Empresa (capitalización) e Ingresos Netos — "Momento idóneo para la venta" ----
    //
    // El precio del stock NO se pide aquí — ya está disponible en las velas que se
    // cargan para la gráfica (última vela = precio actual), sin llamada extra.
    //
    // Capitalización: se lee de la MISMA página de cotización que el PER (mismo
    // WebView, mismo HTML — sin petición extra), reutilizando YahooQuotePageParser.
    //
    // Ingresos netos (3 años): PRIMERO SEC EDGAR (JSON oficial, sin HTML ni WebView — ver
    // fetchNetIncomesFromSecEdgar), y solo si no hay dato (símbolo del IBEX 35, o sin CIK/tag
    // XBRL reconocido) se cae al scraping de Yahoo (ver comentario de yearsDeferred en
    // refreshCompanyFinancialsInternal) — Financial Modeling Prep se probó brevemente pero su
    // income-statement devuelve 402 (Payment Required) en el plan gratuito. La estrategia de
    // reintento del scraping de respaldo es más eficiente que la original (espera de
    // renderizado progresiva, cortacircuitos si Yahoo bloquea la sesión) — si sigue fallando de
    // forma sistemática para un símbolo, revisar con Logcat, etiqueta "CompanyFinancialsFetch".

    fun observeCompanyFinancials(symbol: String): Flow<com.inversionadvisor.data.local.entities.CompanyFinancialsEntity?> =
        dao.observeCompanyFinancials(symbol)

    suspend fun refreshCompanyFinancialsIfStale(symbol: String, maxAgeMillis: Long = CacheConfig.PE_RATIO_TTL_MILLIS) {
        // Log de entrada SIEMPRE, pase lo que pase después — así se puede distinguir
        // "la función no se ha llegado a llamar" de "se llamó pero algo interno fue
        // silencioso", que es justo lo que no se podía diferenciar hasta ahora.
        android.util.Log.i("CompanyFinancialsFetch", "refreshCompanyFinancialsIfStale($symbol) — entrando")
        // AÑADIDO — pedido expresamente así: si hay un escaneo pesado en marcha (Top10 o
        // "Analizar" un mercado, ver NetworkActivityTracker), esta petición al WebView espera a
        // que termine antes de intentarlo — competir contra esa avalancha de tráfico es una
        // causa plausible de que Yahoo bloquee la respuesta con más frecuencia justo en esos
        // momentos. Sin límite de espera aquí: si el escaneo tarda mucho, se espera lo que haga
        // falta, mejor que fallar por las prisas.
        //
        // CORREGIDO — usaba isBusy, que se queda "ocupado" 60s DE MÁS tras terminar de verdad
        // (a propósito, solo para que el banner de conexión no parpadee, ver
        // NetworkActivityTracker). Esperar A ESO aquí no tenía sentido: el motivo de esperar era
        // no competir con tráfico REAL, y ese tráfico ya no existía durante esos 60s de margen
        // — solo hacía que "Datos de Empresa" se quedara colgado sin ingresos netos mucho
        // después de que el escaneo hubiera acabado de verdad (reportado por el usuario con
        // Logcat real, GEHC). Ahora usa isActivelyScanning, que baja al instante.
        if (com.inversionadvisor.data.connectivity.NetworkActivityTracker.isActivelyScanning.value) {
            android.util.Log.i("CompanyFinancialsFetch", "$symbol: hay un escaneo pesado en marcha, esperando a que termine antes de pedir esto")
            com.inversionadvisor.data.connectivity.NetworkActivityTracker.isActivelyScanning.first { !it }
        }
        try {
            val cached = dao.getCompanyFinancialsOnce(symbol)
            // CORREGIDO — encontrado con un caso real (HAL): un resultado INCOMPLETO (faltan
            // años porque un intento anterior no consiguió los 3, quizás de antes de que
            // existiera el reintento actual) se guardaba con la MISMA validez de 6 horas que
            // uno completo — así que, aunque el mecanismo de reintento mejorase después, la
            // caché de un fallo antiguo bloqueaba cualquier intento nuevo hasta que caducara del
            // todo. Ahora, si el resultado guardado está incompleto (falta 2024 o 2025), se usa
            // un margen mucho más corto (10 minutos) en vez de las 6 horas completas — así un
            // dato incompleto se reintenta pronto, sin tener que esperar a que caduque la
            // caché entera ni borrar los datos de la app a mano.
            val cachedIsIncomplete = cached != null && (cached.netIncomePreviousYear == null || cached.netIncomeTwoYearsAgo == null)
            val effectiveMaxAge = if (cachedIsIncomplete) 10 * 60_000L else maxAgeMillis
            if (!isStale(cached?.updatedAtEpochMillis, effectiveMaxAge)) {
                android.util.Log.i("CompanyFinancialsFetch", "$symbol: caché todavía fresca (actualizada hace menos de ${effectiveMaxAge / 60_000} min${if (cachedIsIncomplete) ", dato INCOMPLETO, margen reducido" else ""}), no se repite la petición")
                return
            }
            val scraper = hiddenWebViewScraper
            if (scraper == null) {
                android.util.Log.w("CompanyFinancialsFetch", "$symbol: hiddenWebViewScraper es null — MarketRepository se construyó sin Context (revisar ServiceLocator/StockDetailScreen)")
                dao.upsertCompanyFinancials(
                    com.inversionadvisor.data.local.entities.CompanyFinancialsEntity(
                        symbol = symbol, price = null, marketCap = null,
                        netIncomeCurrentYear = null, netIncomePreviousYear = null, netIncomeTwoYearsAgo = null,
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                )
                return
            }
            android.util.Log.i("CompanyFinancialsFetch", "$symbol: caché caducada o inexistente, lanzando las 2 peticiones al WebView")
            refreshCompanyFinancialsInternal(symbol, scraper)
        } catch (e: Exception) {
            // Última red de seguridad: si algo revienta ANTES de llegar a los try/catch de
            // cada campo (p. ej. un fallo de Room al leer/escribir la caché), esto es lo
            // único que lo habría dejado ver — antes se perdía sin dejar ni rastro.
            android.util.Log.e("CompanyFinancialsFetch", "$symbol: excepción no esperada en refreshCompanyFinancialsIfStale: ${e.message ?: e::class.simpleName}", e)
        }
    }

    /**
     * Ingresos netos anuales (3 años, de más antiguo a más reciente) desde SEC EDGAR — JSON
     * oficial, SIN scraping ni WebView. Lista vacía si el símbolo no tiene CIK conocido (IBEX
     * 35, o un ticker que la SEC no reconoce) o si ninguno de los tags XBRL probados trae datos
     * — en ambos casos, el llamante cae al scraping de Yahoo como respaldo.
     */
    private suspend fun fetchNetIncomesFromSecEdgar(symbol: String): List<Double> {
        if (symbol.endsWith(".MC")) return emptyList() // IBEX 35 no reporta a la SEC
        val cik = try {
            com.inversionadvisor.data.remote.SecEdgarTickerCache.getCik(secEdgarApi, symbol)
        } catch (e: Exception) {
            android.util.Log.w("CompanyFinancialsFetch", "SEC EDGAR ($symbol): fallo resolviendo CIK: ${e.message ?: e::class.simpleName}")
            null
        }
        if (cik == null) {
            android.util.Log.i("CompanyFinancialsFetch", "SEC EDGAR ($symbol): sin CIK conocido, cae al scraping de Yahoo")
            return emptyList()
        }
        val cikPadded = cik.toString().padStart(10, '0')
        for (tag in com.inversionadvisor.domain.indicators.SecEdgarFinancialsParser.NET_INCOME_TAGS) {
            try {
                val concept = secEdgarApi.getCompanyConcept("https://data.sec.gov/api/xbrl/companyconcept/CIK$cikPadded/us-gaap/$tag.json")
                val result = com.inversionadvisor.domain.indicators.SecEdgarFinancialsParser.parseRecentAnnualNetIncomes(concept.units, count = 3)
                if (result.isNotEmpty()) return result
            } catch (e: Exception) {
                // 404 = esta empresa no reporta este tag en concreto — se prueba el siguiente
                // de la lista, es el comportamiento esperado para la mayoría de empresas (solo
                // la primera, "NetIncomeLoss", suele acertar).
            }
        }
        // AÑADIDO — diagnóstico: antes, si la SEC tenía CIK pero ninguno de los 3 tags daba
        // resultado, esto volvía vacío EN SILENCIO — causa real de que NBIS (Nebius, un "foreign
        // private issuer" que reporta con 20-F en vez de 10-K) se quedara sin datos sin dejar
        // ninguna pista de por qué en el log, más allá del cortacircuitos de Yahoo actuando
        // después. Con este aviso, la próxima vez que un símbolo se quede así, se sabrá de
        // entrada si el problema es la SEC (este log) o el scraping de después.
        android.util.Log.i("CompanyFinancialsFetch", "SEC EDGAR ($symbol): CIK=$cik encontrado pero ningún tag dio datos anuales — cae al scraping de Yahoo")
        return emptyList()
    }

    /**
     * NUEVO — respaldo de "Ingresos netos" para IBEX 35 (.MC) cuando el scraping de Yahoo
     * (YahooFinancialsParser, dentro del bucle de reintentos de refreshCompanyFinancialsInternal)
     * también se queda sin datos. Solo se llama para símbolos con slug conocido en
     * IBEX35_INVESTING_SLUGS — si el símbolo no está ahí, se acepta "sin dato" igual que hoy,
     * sin más intentos.
     *
     * AVISO HONESTO — este respaldo da COMO MUCHO 1 año (el último fiscal cerrado), no los 3
     * años que da Yahoo/SEC EDGAR cuando funcionan: ver el comentario de cabecera de
     * InvestingFinancialsParser sobre por qué la tabla completa de Investing.com está detrás
     * de su plan de pago. Un año es mejor que ninguno, pero no hay forma gratuita conocida de
     * sacar los 3 de esta fuente.
     *
     * CORREGIDO — CONFIRMADO con Logcat real (TEF.MC): la petición HTTP normal (investingApi,
     * vía OkHttp/Retrofit) recibía un 403 — Investing.com tiene protección anti-bots (tipo
     * Cloudflare) que bloquea peticiones sin pinta de navegador real, igual que ya pasaba con
     * Yahoo. Se usa el mismo WebView oculto que ya usa Yahoo (scraper.fetchRenderedHtml) en vez
     * de investingApi — investingApi se deja sin usar, no se borra, por si en el futuro
     * Investing.com deja de bloquear peticiones simples.
     *
     * Circuito de seguridad propio (investingBlockedUntilMillis), mismo patrón que
     * isYahooFinancialsBlocked/markYahooFinancialsBlocked: si Investing.com responde con una
     * pantalla de bloqueo (Cloudflare u otro) incluso con el WebView, se corta durante 10
     * minutos en vez de seguir insistiendo contra un bloqueo activo.
     */
    private suspend fun fetchNetIncomesFromInvesting(
        symbol: String,
        scraper: com.inversionadvisor.data.webview.HiddenWebViewScraper
    ): List<Double> {
        val slug = com.inversionadvisor.domain.indicators.IBEX35_INVESTING_SLUGS[symbol] ?: return emptyList()
        if (isInvestingBlocked()) {
            android.util.Log.i("CompanyFinancialsFetch", "Investing.com ($symbol): cortacircuitos activo (bloqueo detectado hace poco), no se intenta")
            return emptyList()
        }
        return try {
            val html = scraper.fetchRenderedHtml("https://www.investing.com/equities/$slug-income-statement", extraWaitMillis = 4_000L)
            if (com.inversionadvisor.domain.indicators.InvestingFinancialsParser.isBlockedOrErrorPage(html)) {
                android.util.Log.w("CompanyFinancialsFetch", "Investing.com ($symbol): página de bloqueo detectada — cortacircuitos activado 10 min")
                markInvestingBlocked()
                return emptyList()
            }
            val ultimoAnioFiscal = com.inversionadvisor.domain.indicators.InvestingFinancialsParser.parseFullYearNetIncomeFromFaq(html)
            val result = listOfNotNull(ultimoAnioFiscal)
            android.util.Log.i("CompanyFinancialsFetch", "Ingresos netos ($symbol, Investing.com, solo último año fiscal): resultado=$result")
            result
        } catch (e: Exception) {
            android.util.Log.w("CompanyFinancialsFetch", "Investing.com ($symbol): ${e.message ?: e::class.simpleName}")
            emptyList()
        }
    }

    // NUEVO — pedido expresamente ("solución alternativa definitiva a Ingresos Netos, Yahoo
    // falla mucho aquí"): respaldo UNIVERSAL (no solo IBEX 35 vía Investing.com) — stockanalysis.com,
    // ver StockAnalysisFinancialsParser para el porqué. Mismo patrón exacto que
    // fetchNetIncomesFromInvesting (cortacircuitos propio, no repetir contra un bloqueo activo).
    private suspend fun fetchNetIncomesFromStockAnalysis(
        symbol: String,
        scraper: com.inversionadvisor.data.webview.HiddenWebViewScraper
    ): List<Double> {
        if (isStockAnalysisBlocked()) {
            android.util.Log.i("CompanyFinancialsFetch", "stockanalysis.com ($symbol): cortacircuitos activo (bloqueo detectado hace poco), no se intenta")
            return emptyList()
        }
        return try {
            // stockanalysis.com espera el ticker "pelado" (sin sufijo de bolsa, p. ej. ".MC")
            // — símbolos así (mercados no cubiertos por este sitio) simplemente no encontrarán
            // la tabla y devolverán lista vacía, sin gastar más que 1 petición.
            val html = scraper.fetchRenderedHtml("https://stockanalysis.com/stocks/$symbol/financials/", extraWaitMillis = 3_500L)
            if (com.inversionadvisor.domain.indicators.StockAnalysisFinancialsParser.isBlockedOrErrorPage(html)) {
                android.util.Log.w("CompanyFinancialsFetch", "stockanalysis.com ($symbol): página de bloqueo detectada — cortacircuitos activado 10 min")
                markStockAnalysisBlocked()
                return emptyList()
            }
            val result = com.inversionadvisor.domain.indicators.StockAnalysisFinancialsParser.parseRecentAnnualNetIncomes(html, count = 3)
            android.util.Log.i("CompanyFinancialsFetch", "Ingresos netos 3 años ($symbol, stockanalysis.com): resultado=$result")
            result
        } catch (e: Exception) {
            android.util.Log.w("CompanyFinancialsFetch", "stockanalysis.com ($symbol): ${e.message ?: e::class.simpleName}")
            emptyList()
        }
    }

    private suspend fun refreshCompanyFinancialsInternal(symbol: String, scraper: com.inversionadvisor.data.webview.HiddenWebViewScraper) {
        // 2 peticiones en paralelo (cotización + estados financieros) — se quitó la
        // de "trimestral" (con su clic al botón, la más lenta de las 3 que había
        // antes) a petición expresa: ahora en vez de anual/trimestral se muestran 3
        // años consecutivos hasta el actual, todos sacados de la MISMA fila de la
        // página de estados financieros (una sola petición, sin clics).
        coroutineScope {
            val quoteDeferred = async {
                var marketCap: Double? = null
                var netIncomeTtm: Double? = null
                try {
                    val html = scraper.fetchRenderedHtml("https://finance.yahoo.com/quote/$symbol/", extraWaitMillis = 2_500L)
                    marketCap = YahooQuotePageParser.parseMarketCap(html)
                    netIncomeTtm = YahooQuotePageParser.parseNetIncomeTtm(html)
                    // Fecha de resultados extraída de esta MISMA página ya cargada, sin petición
                    // aparte — ver comentario de EarningsDateEntity para el porqué del cambio.
                    val earningsDateText = YahooQuotePageParser.parseEarningsDateText(html)
                    saveEarningsDateText(symbol, earningsDateText)
                    if (earningsDateText == null) {
                        // Diagnóstico — las etiquetas de fecha de resultados llevan sin
                        // verificar contra una página real desde el principio; esto imprime el
                        // texto REAL alrededor de "resultados"/"earnings" en esta página, para
                        // poder ajustar la etiqueta con precisión en vez de seguir adivinando.
                        val debugWindows = YahooQuotePageParser.findEarningsRelatedTextForDebug(html)
                        android.util.Log.w(
                            "CompanyFinancialsFetch",
                            "$symbol: fecha de resultados NO encontrada. Texto cercano a \"resultados\"/\"earnings\": $debugWindows"
                        )
                    }
                    if (marketCap == null || netIncomeTtm == null) {
                        android.util.Log.w(
                            "CompanyFinancialsFetch",
                            "$symbol: marketCap=$marketCap, netIncomeTtm=$netIncomeTtm — título: \"${parseHtmlTitle(html)}\", longitud del HTML: ${html.length}"
                        )
                    }
                } catch (e: Exception) {
                    android.util.Log.w("CompanyFinancialsFetch", "Página de cotización ($symbol): ${e.message ?: e::class.simpleName}")
                }
                marketCap to netIncomeTtm
            }

            val yearsDeferred = async {
                // NUEVO — se prueba PRIMERO SEC EDGAR (JSON oficial, sin HTML ni WebView, ver
                // fetchNetIncomesFromSecEdgar) antes de recurrir al scraping de Yahoo. Cubre
                // prácticamente todo EE.UU.; el IBEX 35 (símbolos ".MC") no está en la SEC, así
                // que esos van derechos al scraping de Yahoo sin perder tiempo probando esto.
                val fromSec = fetchNetIncomesFromSecEdgar(symbol)
                if (fromSec.isNotEmpty()) {
                    android.util.Log.i("CompanyFinancialsFetch", "Ingresos netos 3 años ($symbol, SEC EDGAR): resultado=$fromSec — sin scraping de Yahoo, no hizo falta")
                    return@async fromSec
                }

                // VUELTA a Yahoo (WebView) — a petición expresa: el income-statement de
                // Financial Modeling Prep (usado brevemente, ver comentario de
                // YahooFinancialsParser) devuelve HTTP 402 en el plan gratuito, así que no es
                // viable sin pagar. Se restaura el scraping de la página de estados financieros
                // de Yahoo, pero con una estrategia de reintento más eficiente que la original:
                //
                //  - ANTES: 3 intentos, SIEMPRE con la misma espera de renderizado (5s), y
                //    esperas FIJAS entre reintentos (15s, luego 20s) — 50s de peor caso total,
                //    sin adaptarse a la causa real del fallo.
                //  - AHORA: la espera de renderizado SUBE en cada reintento (5s -> 8s -> 12s),
                //    partiendo de la sospecha más probable (la tabla tarda en pintar sus
                //    columnas, no necesariamente un bloqueo anti-bots) — y las esperas ENTRE
                //    intentos bajan (6s, 10s) porque no hay evidencia de que haga falta tanto
                //    margen como con AAII (que sí iba detrás de un firewall anti-bots de
                //    verdad). Peor caso total: ~41s en vez de 50s, y con más probabilidad de
                //    éxito en el segundo intento porque la tabla tiene más tiempo real para
                //    pintarse, no solo más tiempo de espera "a ciegas".
                //  - AÑADIDO — cortacircuitos: si el PRIMER intento ya devuelve la página de
                //    error genérica de Yahoo (no una tabla vacía, un bloqueo real de sesión), se
                //    corta ahí mismo — reintentar más veces contra un bloqueo activo no tiene
                //    ninguna posibilidad de éxito, y activa el cortacircuitos global (10 min)
                //    para que los SIGUIENTES símbolos ni siquiera lo intenten mientras dure.
                //
                //  - CORREGIDO — este "return@async emptyList()" cuando el cortacircuitos ya
                //    estaba activo (de un símbolo anterior) salía de la función ENTERA sin
                //    pasar nunca por el respaldo de Investing.com de más abajo: durante esos 10
                //    minutos, TODOS los .MC se quedaban sin ingresos netos aunque Investing.com
                //    funcionara perfectamente. Ahora, si es un .MC con slug conocido, se salta
                //    directamente a Investing.com en vez de rendirse.
                if (isYahooFinancialsBlocked()) {
                    android.util.Log.i("CompanyFinancialsFetch", "Ingresos netos 3 años ($symbol): cortacircuitos activo (bloqueo de Yahoo detectado hace poco), no se intenta")
                    if (symbol.endsWith(".MC")) {
                        return@async fetchNetIncomesFromInvesting(symbol, scraper)
                    }
                    return@async emptyList<Double>()
                }

                data class Attempt(val extraWaitMillis: Long, val delayBeforeMillis: Long)
                val attempts = listOf(
                    Attempt(extraWaitMillis = 5_000L, delayBeforeMillis = 0L),
                    Attempt(extraWaitMillis = 8_000L, delayBeforeMillis = 6_000L),
                    Attempt(extraWaitMillis = 12_000L, delayBeforeMillis = 10_000L)
                )

                suspend fun attemptFetch(extraWaitMillis: Long): List<Double> {
                    val html = scraper.fetchRenderedHtml("https://finance.yahoo.com/quote/$symbol/financials/", extraWaitMillis = extraWaitMillis)
                    if (YahooFinancialsParser.isErrorPage(html)) {
                        android.util.Log.w("CompanyFinancialsFetch", "Ingresos netos 3 años ($symbol): página de error de Yahoo (\"Oops, something went wrong\") — bloqueo de sesión, no timing")
                        markYahooFinancialsBlocked()
                        return emptyList()
                    }
                    val result = YahooFinancialsParser.parseRecentAnnualNetIncomes(html, count = 3)
                    android.util.Log.i("CompanyFinancialsFetch", "Ingresos netos 3 años ($symbol): resultado=$result, apariciones de la etiqueta: ${YahooFinancialsParser.debugAllLabelOccurrences(html)}")
                    // Diagnóstico — cuando la etiqueta no aparece NI UNA VEZ (ni en inglés ni en
                    // español), hace falta ver qué página ha llegado de verdad (¿es realmente
                    // financials de Yahoo? ¿un aviso de cookies? ¿una redirección a otra cosa?)
                    // — sin esto, "apariciones: []" no dice POR QUÉ está vacío.
                    if (result.isEmpty()) {
                        val bodyText = org.jsoup.Jsoup.parse(html).text()
                        android.util.Log.w(
                            "CompanyFinancialsFetch",
                            "Ingresos netos 3 años ($symbol): HTML de ${html.length} caracteres, texto visible de ${bodyText.length} caracteres, primeros 300: \"${bodyText.take(300)}\""
                        )
                    }
                    return result
                }

                val bestFromYahoo = try {
                    var best: List<Double> = emptyList()
                    for ((index, attempt) in attempts.withIndex()) {
                        if (attempt.delayBeforeMillis > 0L) kotlinx.coroutines.delay(attempt.delayBeforeMillis)
                        val result = attemptFetch(attempt.extraWaitMillis)
                        if (result.size > best.size) best = result
                        if (best.size >= 3) break // completo, no hace falta seguir intentando
                        if (isYahooFinancialsBlocked()) {
                            android.util.Log.w("CompanyFinancialsFetch", "Ingresos netos 3 años ($symbol): cortacircuitos activado en el intento ${index + 1}, se corta aquí sin más reintentos")
                            break
                        }
                        android.util.Log.w("CompanyFinancialsFetch", "Ingresos netos 3 años ($symbol): intento ${index + 1}/${attempts.size} trajo ${result.size} de 3")
                    }
                    best
                } catch (e: Exception) {
                    android.util.Log.w("CompanyFinancialsFetch", "Ingresos netos 3 años ($symbol): ${e.message ?: e::class.simpleName}")
                    emptyList()
                }

                // NUEVO — si Yahoo se queda sin nada Y es un símbolo del IBEX 35 con slug
                // conocido de Investing.com, se prueba ahí antes de rendirse del todo.
                // Si Yahoo se queda sin nada Y es un símbolo del IBEX 35 con slug conocido de
                // Investing.com, se prueba ahí antes de rendirse del todo (da como mucho 1 año,
                // ver comentario de InvestingFinancialsParser).
                if (bestFromYahoo.isEmpty() && symbol.endsWith(".MC")) {
                    val fromInvesting = fetchNetIncomesFromInvesting(symbol, scraper)
                    if (fromInvesting.isNotEmpty()) return@async fromInvesting
                }
                // NUEVO — pedido expresamente ("Yahoo falla mucho aquí, solución definitiva"):
                // si TODO lo anterior (SEC EDGAR, Yahoo, e Investing.com si era .MC) se ha
                // quedado sin nada, se prueba stockanalysis.com como último respaldo universal
                // — fuente distinta, con su propia infraestructura, así que un bloqueo de sesión
                // de Yahoo no la afecta. No tiene sentido para .MC (cubre bolsas de EE.UU., ver
                // StockAnalysisFinancialsParser), así que ahí no se intenta.
                if (bestFromYahoo.isEmpty() && !symbol.endsWith(".MC")) {
                    val fromStockAnalysis = fetchNetIncomesFromStockAnalysis(symbol, scraper)
                    if (fromStockAnalysis.isNotEmpty()) return@async fromStockAnalysis
                }
                bestFromYahoo
            }

            val (marketCap, netIncomeTtm) = quoteDeferred.await()
            // parseRecentAnnualNetIncomes devuelve de más antiguo a más reciente — el
            // último es el año en curso (TTM), por eso se lee la lista al revés.
            val years = yearsDeferred.await().reversed()
            val netIncomeCurrentYear = years.getOrNull(0) ?: netIncomeTtm // si la tabla falló del todo, el TTM de la página de cotización sirve de respaldo para "año en curso"
            val netIncomePreviousYear = years.getOrNull(1)
            val netIncomeTwoYearsAgo = years.getOrNull(2)

            dao.upsertCompanyFinancials(
                com.inversionadvisor.data.local.entities.CompanyFinancialsEntity(
                    symbol = symbol,
                    price = null, // el precio se lee de las velas ya cargadas, no de aquí
                    marketCap = marketCap,
                    netIncomeCurrentYear = netIncomeCurrentYear,
                    netIncomePreviousYear = netIncomePreviousYear,
                    netIncomeTwoYearsAgo = netIncomeTwoYearsAgo,
                    updatedAtEpochMillis = System.currentTimeMillis()
                )
            )
            if (marketCap == null && netIncomeCurrentYear == null) {
                android.util.Log.w("CompanyFinancialsFetch", "Sin ningún dato para $symbol — revisar con Logcat completo (etiqueta CompanyFinancialsFetch)")
            }
        }
    }

    /** Extrae el <title> del HTML para diagnóstico (¿la página que cargó era la esperada?). */
    private fun parseHtmlTitle(html: String): String {
        val match = Regex("<title[^>]*>(.*?)</title>", RegexOption.IGNORE_CASE).find(html)
        return match?.groupValues?.get(1)?.trim()?.take(120) ?: "(sin <title>)"
    }

    /**
     * NUEVO — pedido expresamente: versión ampliada de computeSectorFavorability() (ver más
     * abajo, que ahora delega aquí) — además de isSectorInFavor, también da el cambio % del
     * S&P 500 en el año y la caída % de cada sector desde su máximo de 26 semanas (mismas velas
     * ya cargadas para "Macrotendencia", sin ninguna petición de red adicional) — para las
     * mejoras #1 y #3 (comparación con el mercado y con el propio sector), conectadas ya en
     * scanner-cli y ahora también aquí, en el escaneo en directo y en la ficha del stock.
     */
    data class SectorContext(
        val isSectorInFavor: Map<String, Boolean>,
        val benchmarkYearChangePercent: Double?,
        val sectorDeclinePercentByEtf: Map<String, Double?>
    )

    private fun declineFromRecentHigh(candles: List<Candle>, semanas: Int = 26): Double? {
        if (candles.size < 5) return null
        val ventana = candles.takeLast(semanas)
        val maximoReciente = ventana.maxOf { it.high }
        val precioActual = candles.last().close
        if (maximoReciente <= 0.0) return null
        return (maximoReciente - precioActual) / maximoReciente * 100
    }

    suspend fun computeSectorContext(): SectorContext = coroutineScope {
        val benchmarkCandles = try {
            refreshYahooCandlesBulkIfStale(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
            observeCandles(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
        } catch (e: Exception) {
            emptyList()
        }
        if (benchmarkCandles.isEmpty()) return@coroutineScope SectorContext(emptyMap(), null, emptyMap())

        val sectorCandles = com.inversionadvisor.domain.model.Symbols.SECTOR_ETFS.keys.map { etfSymbol ->
            async {
                try {
                    refreshYahooCandlesBulkIfStale(etfSymbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                    etfSymbol to observeCandles(etfSymbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                } catch (e: Exception) {
                    null
                }
            }
        }.let { kotlinx.coroutines.awaitAll(*it.toTypedArray()) }.filterNotNull().toMap()

        val sectorPerformance = com.inversionadvisor.domain.indicators.SectorRotationCalculator.calculate(
            sectorCandles, com.inversionadvisor.domain.model.Symbols.SECTOR_ETFS, benchmarkCandles
        )
        val isSectorInFavor = sectorPerformance.associate { it.etfSymbol to it.isConsistentLeader }
        val benchmarkYearChangePercent = benchmarkCandles.takeIf { it.size >= 2 }
            ?.let { (it.last().close - it.first().close) / it.first().close * 100 }
        val sectorDeclinePercentByEtf = sectorCandles.mapValues { (_, velas) -> declineFromRecentHigh(velas) }
        SectorContext(isSectorInFavor, benchmarkYearChangePercent, sectorDeclinePercentByEtf)
    }

    /**
     * "Sector en auge": compara los 11 sectores entre sí (fuerza relativa media 2S/1M/3M/6M
     * frente al S&P 500 — ver SectorRotationCalculator). Antes esto solo vivía dentro de
     * ScreenerRepository (privado, solo para el escaneo completo) — se saca aquí, compartido,
     * para que la ficha de un stock suelto (BuyOpportunityAnalyzer, vía StockDetailViewModel)
     * también pueda saber si SU sector está en auge, con el MISMO cálculo que usa el escáner,
     * en vez de quedarse siempre sin este dato (que era la causa real de que el riesgo/recompensa
     * no coincidiera entre la ficha del stock y Top10/Futuras compras para el mismo símbolo).
     *
     * Reutiliza la MISMA caché de velas semanales de 1 año que ya usan el escáner y el
     * dashboard — si cualquiera de los dos ya las pidió recientemente, esto no vuelve a
     * gastarlas (solo lectura de Room), pero si nunca se cargaron, sí implica 12 peticiones de
     * red reales (11 sectores + el S&P 500) la primera vez.
     *
     * MANTENIDA por compatibilidad — ahora delega en computeSectorContext(), la versión rica de
     * arriba, para no duplicar la lógica.
     */
    suspend fun computeSectorFavorability(): Map<String, Boolean> = computeSectorContext().isSectorInFavor

    companion object {
        /** Nº máximo de símbolos por llamada batch a Twelve Data (sin usar por defecto ahora mismo). */
        private const val MAX_SYMBOLS_PER_BATCH = 6

        // AÑADIDO — cortacircuitos para la página de estados financieros de Yahoo: cuando esa
        // página devuelve su error genérico ("Oops, something went wrong" — ver
        // YahooFinancialsParser.isErrorPage), confirmado con Logcat real que pasa de forma
        // CONSISTENTE para varios símbolos seguidos (WMB, HAL, NOW), no puntual — es la señal de
        // un bloqueo temporal de sesión/IP por parte de Yahoo, no de que la tabla tarde en
        // pintarse. Sin esto, cada símbolo nuevo repetía los 3 intentos completos (con sus
        // esperas crecientes) contra un bloqueo que ya se sabía activo, saturando el sistema
        // sin ninguna posibilidad real de éxito (reportado expresamente por el usuario). Con
        // este cortacircuitos: en cuanto se detecta la página de error UNA vez, se corta la
        // reintentos para ESE símbolo al instante, y se guarda un margen de enfriamiento de 10
        // minutos durante el cual NINGÚN símbolo nuevo ni siquiera lo intenta — se reintenta
        // solo pasado ese margen, dándole tiempo real a Yahoo para levantar el bloqueo en vez de
        // seguir insistiendo y (probablemente) prolongarlo.
        private val yahooFinancialsBlockedUntilMillis = java.util.concurrent.atomic.AtomicLong(0L)

        private fun isYahooFinancialsBlocked(): Boolean =
            System.currentTimeMillis() < yahooFinancialsBlockedUntilMillis.get()

        private fun markYahooFinancialsBlocked() {
            val until = System.currentTimeMillis() + 10 * 60_000L
            yahooFinancialsBlockedUntilMillis.set(until)
            android.util.Log.w("CompanyFinancialsFetch", "Página de error de Yahoo detectada — cortacircuitos activado, sin más intentos hasta dentro de 10 minutos")
        }

        // NUEVO — mismo patrón que el cortacircuitos de Yahoo, para Investing.com (ver
        // fetchNetIncomesFromInvesting).
        private val investingBlockedUntilMillis = java.util.concurrent.atomic.AtomicLong(0L)

        private fun isInvestingBlocked(): Boolean =
            System.currentTimeMillis() < investingBlockedUntilMillis.get()

        private fun markInvestingBlocked() {
            investingBlockedUntilMillis.set(System.currentTimeMillis() + 10 * 60_000L)
        }

        // NUEVO — mismo patrón, para stockanalysis.com (ver fetchNetIncomesFromStockAnalysis).
        private val stockAnalysisBlockedUntilMillis = java.util.concurrent.atomic.AtomicLong(0L)

        private fun isStockAnalysisBlocked(): Boolean =
            System.currentTimeMillis() < stockAnalysisBlockedUntilMillis.get()

        private fun markStockAnalysisBlocked() {
            stockAnalysisBlockedUntilMillis.set(System.currentTimeMillis() + 10 * 60_000L)
        }
    }
}

// ---- Mappers ----

private fun QuoteDto.toEntity(symbolOverride: String): QuoteEntity = QuoteEntity(
    symbol = symbolOverride,
    name = name,
    close = close?.toDoubleOrNull() ?: 0.0,
    previousClose = previousClose?.toDoubleOrNull() ?: 0.0,
    changePercent = percentChange?.toDoubleOrNull() ?: 0.0,
    volume = volume?.toLongOrNull() ?: 0L,
    averageVolume = averageVolume?.toLongOrNull() ?: 0L,
    fiftyTwoWeekLow = fiftyTwoWeek?.low?.toDoubleOrNull(),
    fiftyTwoWeekHigh = fiftyTwoWeek?.high?.toDoubleOrNull(),
    currency = currency ?: inferCurrencyFromPairSymbol(symbolOverride),
    updatedAtEpochMillis = System.currentTimeMillis()
)

/**
 * Twelve Data no siempre rellena el campo "currency" para símbolos tipo par
 * (p. ej. XAU/USD): la cotización está en la divisa de la derecha de la
 * barra, así que se deduce del propio símbolo si el campo viene vacío.
 */
private fun inferCurrencyFromPairSymbol(symbol: String): String? {
    val quoteCode = symbol.substringAfter('/', missingDelimiterValue = "")
    return quoteCode.takeIf { Currency.fromCode(it) != null }
}

private fun YahooChartResultDto.toQuoteEntity(symbolOverride: String): QuoteEntity {
    val close = meta.regularMarketPrice ?: 0.0
    val previousClose = meta.previousClose ?: meta.chartPreviousClose ?: close
    val changePercent = if (previousClose != 0.0) (close - previousClose) / previousClose * 100 else 0.0
    return QuoteEntity(
        symbol = symbolOverride,
        name = null,
        close = close,
        previousClose = previousClose,
        changePercent = changePercent,
        volume = meta.regularMarketVolume ?: 0L,
        averageVolume = 0L,
        fiftyTwoWeekLow = meta.fiftyTwoWeekLow,
        fiftyTwoWeekHigh = meta.fiftyTwoWeekHigh,
        currency = meta.currency,
        updatedAtEpochMillis = System.currentTimeMillis()
    )
}

private fun YahooChartResultDto.toCandleEntities(symbolOverride: String, intervalLabel: String): List<CandleEntity> {
    val timestamps = timestamp ?: return emptyList()
    val quoteArrays = indicators?.quote?.firstOrNull() ?: return emptyList()
    // FALLO REAL ENCONTRADO Y CORREGIDO: antes se usaba `close` (SIN ajustar por splits) como
    // fuente principal, y `adjclose` (SÍ ajustado) solo como respaldo cuando `close` venía
    // null — exactamente al revés de lo que debería ser. Con un split reciente (confirmado con
    // Monster Beverage, split 2x1 el 8 de julio de 2026), los precios de ANTES del split se
    // quedan sin dividir, creando una caída falsa de ~50% justo esa semana — que corrompía
    // TODO lo que se calcula sobre estas velas mientras esa semana cayera dentro de la ventana
    // (RSI, medias móviles, volatilidad propia, tendencia...), no solo la volatilidad donde se
    // detectó. Ahora `adjclose` (ajustado) es la fuente PRINCIPAL para el cierre.
    //
    // Yahoo NO da open/high/low ya ajustados por separado (solo el cierre) — así que, cuando
    // hay cierre ajustado disponible, se calcula el FACTOR de ajuste (ajustado ÷ sin ajustar) en
    // ese punto y se aplica igual a open/high/low, para que los 4 valores de la vela queden
    // coherentes entre sí (si no, el cierre podría quedar fuera del rango [low, high] de esa
    // misma vela, justo en la semana del split).
    val adjCloseArray = indicators.adjclose?.firstOrNull()?.adjclose
    // Diagnóstico — para confirmar con datos reales si adjclose llega de verdad y si difiere
    // de close (indicio de un split/dividendo ajustado). Si esto NO aparece en el log, o
    // aparece con "adjclose SIN DATOS", significa que Yahoo no está devolviendo ese bloque
    // pese al parámetro events=div,splits añadido — haría falta investigar por otra vía.
    if (adjCloseArray == null) {
        android.util.Log.w("CandleAdjustDiagnostic", "$symbolOverride ($intervalLabel): adjclose SIN DATOS en la respuesta — se usará close sin ajustar")
    } else {
        val diffs = quoteArrays.close?.indices?.mapNotNull { i ->
            val raw = quoteArrays.close.getOrNull(i)
            val adj = adjCloseArray.getOrNull(i)
            if (raw != null && adj != null && raw != 0.0 && kotlin.math.abs(adj - raw) / raw > 0.01) {
                "idx=$i close=$raw adjclose=$adj" 
            } else null
        } ?: emptyList()
        android.util.Log.i(
            "CandleAdjustDiagnostic",
            "$symbolOverride ($intervalLabel): adjclose SÍ presente (${adjCloseArray.size} puntos), ${diffs.size} puntos difieren >1% de close" +
                if (diffs.isNotEmpty()) " — ejemplos: ${diffs.take(3)}" else ""
        )
    }
    return timestamps.indices.mapNotNull { i ->
        val rawClose = quoteArrays.close?.getOrNull(i)
        val adjClose = adjCloseArray?.getOrNull(i)
        val close = adjClose ?: rawClose ?: return@mapNotNull null
        val adjustmentFactor = if (adjClose != null && rawClose != null && rawClose != 0.0) adjClose / rawClose else 1.0
        CandleEntity(
            symbol = symbolOverride,
            interval = intervalLabel,
            datetime = java.time.Instant.ofEpochSecond(timestamps[i]).toString(),
            open = (quoteArrays.open?.getOrNull(i)?.times(adjustmentFactor)) ?: close,
            high = (quoteArrays.high?.getOrNull(i)?.times(adjustmentFactor)) ?: close,
            low = (quoteArrays.low?.getOrNull(i)?.times(adjustmentFactor)) ?: close,
            close = close,
            volume = quoteArrays.volume?.getOrNull(i)
        )
    }
}

private fun QuoteEntity.toDomain(): Quote = Quote(
    symbol = symbol,
    name = name,
    close = close,
    previousClose = previousClose,
    changePercent = changePercent,
    volume = volume,
    averageVolume = averageVolume,
    fiftyTwoWeekLow = fiftyTwoWeekLow,
    fiftyTwoWeekHigh = fiftyTwoWeekHigh,
    currency = Currency.fromCode(currency),
    updatedAtEpochMillis = updatedAtEpochMillis
)

private fun com.inversionadvisor.data.remote.CandleDto.toEntity(symbol: String, interval: String): CandleEntity = CandleEntity(
    symbol = symbol,
    interval = interval,
    datetime = datetime,
    open = open.toDoubleOrNull() ?: 0.0,
    high = high.toDoubleOrNull() ?: 0.0,
    low = low.toDoubleOrNull() ?: 0.0,
    close = close.toDoubleOrNull() ?: 0.0,
    volume = volume?.toLongOrNull()
)

private fun CandleEntity.toDomain(): Candle = Candle(
    datetime = datetime,
    open = open,
    high = high,
    low = low,
    close = close,
    volume = volume
)

private fun FearGreedEntity.toDomain(): CnnFearGreedStatus {
    // mapScoreToLabel (por puntuación) en vez de mapRating (el texto en inglés de la propia
    // CNN) — encontrado y corregido: podían no coincidir (p. ej. la CNN podía decir "greed" en
    // un punto donde, según los umbrales 25/44/55/75 pedidos, todavía es "neutral").
    val (label, riskLevel) = CnnFearGreedRatingMapper.mapScoreToLabel(score)
    // Orden cronológico para el sparkline: 1 mes -> 1 semana -> cierre anterior -> actual.
    val history = listOfNotNull(previousOneMonth, previousOneWeek, previousClose, score)
    return CnnFearGreedStatus(
        // CORREGIDO — encontrado con un caso real (55 mostrado, 56 el valor real de CNN):
        // ".toInt()" en Kotlin TRUNCA hacia cero, no redondea — un score real de 55,7 se
        // quedaba en 55 en vez de 56. kotlin.math.roundToInt() sí redondea al entero más
        // cercano, como se espera al mostrar "el número entero" de un valor con decimales.
        score = kotlin.math.round(score).toInt(),
        ratingRaw = rating,
        label = label,
        riskLevel = riskLevel,
        history = history,
        updatedAtEpochMillis = updatedAtEpochMillis
    )
}

private fun AaiiSentimentEntity.toDomain(): AaiiSentiment = AaiiSentiment(
    reportedDateLabel = reportedDateLabel,
    bullishPercent = bullishPercent,
    neutralPercent = neutralPercent,
    bearishPercent = bearishPercent,
    bullBearSpread = bullishPercent - bearishPercent,
    spreadHistory = spreadHistoryCsv.split(",").mapNotNull { it.toDoubleOrNull() },
    updatedAtEpochMillis = updatedAtEpochMillis
)

private fun CryptoSparklineEntity.toPriceList(): List<Double> =
    pricesCsv.split(",").mapNotNull { it.toDoubleOrNull() }

private fun MarketVolumeEntity.toDomain(): MarketVolume = MarketVolume(
    symbol = symbol,
    relativeVolume = relativeVolume,
    todayVolume = todayVolume,
    averageVolume = averageVolume,
    updatedAtEpochMillis = updatedAtEpochMillis
)
