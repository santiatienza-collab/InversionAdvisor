package com.inversionadvisor.scanner

import com.inversionadvisor.domain.indicators.DoubleTopBottomResult
import com.inversionadvisor.domain.indicators.ExhaustionDetector
import com.inversionadvisor.domain.indicators.FinvizPeParser
import com.inversionadvisor.domain.indicators.SectorRotationCalculator
import com.inversionadvisor.domain.indicators.TechnicalAnalysis
import com.inversionadvisor.domain.indicators.Top10Calculator
import com.inversionadvisor.domain.indicators.UptrendDetector
import com.inversionadvisor.domain.model.Candle
import com.inversionadvisor.domain.model.MarketUniverse
import com.inversionadvisor.domain.model.StockUniverse
import com.inversionadvisor.domain.model.StockUniverseEntry
import com.inversionadvisor.domain.model.Symbols
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.io.File
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * FASE 3 — pedido expresamente "100% completo". Añade, sobre las Fases 1 y 2 (PER, sectores,
 * patrones, puntuación base):
 *  - Velas DIARIAS (1 año) por símbolo → patrón de bandera a corto plazo y cruce SMA20/SMA50
 *    con precisión diaria (cruz de la muerte / cruz dorada), igual que hace la app cuando tiene
 *    velas diarias disponibles.
 *  - Velas MENSUALES (10 años) por símbolo → patrón de bandera a largo plazo y el conteo de
 *    meses bajistas consolidados (4+ de los últimos 6 meses en negativo).
 *  - Divergencia momentum/precio (sobre las velas semanales ya cargadas, sin coste extra).
 *
 * EL ÚNICO HUECO QUE QUEDA, y por qué — para no fingir un 100% que no es tal: el bono
 * "resultados trimestrales en menos de 3 semanas" (Top10Calculator.score, parámetro
 * earningsWithinThreeWeeks) depende, en la app, de un WebView de Android que renderiza la
 * página de Yahoo y extrae la fecha de un texto libre (ver
 * MarketRepository.refreshCompanyFinancialsIfStale) — no es una petición HTTP simple con
 * parseo, es un navegador embebido de verdad. Reproducir eso aquí exigiría añadir un navegador
 * headless (Selenium/Playwright), una dependencia bastante más pesada y frágil que todo lo
 * demás de este script — se deja fuera a propósito, con este aviso, en vez de fingir que está
 * resuelto. El resto de la fórmula (7 categorías + todos los bonos/penalizaciones aparte menos
 * este) sí es equivalente a lo que calcula la app.
 *
 * AVISO DE RENDIMIENTO — cada símbolo ahora hace 3 peticiones a Yahoo (semanal + diaria +
 * mensual) en vez de 1, así que el escaneo completo tarda más que en la Fase 2 (aprox. el
 * triple). Para un mercado de ~500 símbolos, cuenta con que el job de GitHub Actions tarde
 * bastantes minutos más — sigue siendo gratis y automático, lo instantáneo es que la APP no
 * tenga que esperar a esto, solo descargar el JSON ya calculado.
 */

// ---- Yahoo (velas) — igual que en la Fase 1 ----

private interface YahooChartApiCli {
    @GET("v8/finance/chart/{symbol}")
    suspend fun getChart(
        @Path("symbol") symbol: String,
        @Query("range") range: String,
        @Query("interval") interval: String,
        @Query("events") events: String = "div,splits"
    ): YahooChartResponseCli
}

@JsonClass(generateAdapter = true)
private data class YahooChartResponseCli(val chart: YahooChartCli)
@JsonClass(generateAdapter = true)
private data class YahooChartCli(val result: List<YahooChartResultCli>?)
@JsonClass(generateAdapter = true)
private data class YahooChartResultCli(val timestamp: List<Long>?, val indicators: YahooIndicatorsCli?)
@JsonClass(generateAdapter = true)
private data class YahooIndicatorsCli(val quote: List<YahooQuoteCli>?, val adjclose: List<YahooAdjCloseCli>?)
@JsonClass(generateAdapter = true)
private data class YahooQuoteCli(val open: List<Double?>?, val high: List<Double?>?, val low: List<Double?>?, val close: List<Double?>?, val volume: List<Long?>?)
@JsonClass(generateAdapter = true)
private data class YahooAdjCloseCli(val adjclose: List<Double?>?)

private fun YahooChartResultCli.toCandles(): List<Candle> {
    val timestamps = timestamp ?: return emptyList()
    val quoteArrays = indicators?.quote?.firstOrNull() ?: return emptyList()
    val adjCloseArray = indicators.adjclose?.firstOrNull()?.adjclose
    return timestamps.indices.mapNotNull { i ->
        val rawClose = quoteArrays.close?.getOrNull(i)
        val adjClose = adjCloseArray?.getOrNull(i)
        val close = adjClose ?: rawClose ?: return@mapNotNull null
        val factor = if (adjClose != null && rawClose != null && rawClose != 0.0) adjClose / rawClose else 1.0
        Candle(
            datetime = Instant.ofEpochSecond(timestamps[i]).toString(),
            open = (quoteArrays.open?.getOrNull(i)?.times(factor)) ?: close,
            high = (quoteArrays.high?.getOrNull(i)?.times(factor)) ?: close,
            low = (quoteArrays.low?.getOrNull(i)?.times(factor)) ?: close,
            close = close,
            volume = quoteArrays.volume?.getOrNull(i)
        )
    }
}

// ---- Finviz (PER) — mismos endpoints que FinvizApi.kt de la app ----

private interface FinvizApiCli {
    @GET("groups")
    suspend fun getSectorGroupsHtml(
        @Query("g") group: String = "sector",
        @Query("v") view: String = "120",
        @Query("o") order: String = "name"
    ): ResponseBody

    @GET("quote.ashx")
    suspend fun getStockQuoteHtml(@Query("t") ticker: String): ResponseBody

    @GET("screener.ashx")
    suspend fun getScreenerHtml(
        @Query("t") tickers: String,
        @Query("v") view: String = "121"
    ): ResponseBody
}

// ---- Forma del JSON de salida ----

@JsonClass(generateAdapter = true)
data class FactorJson(val label: String, val grade: String, val valueText: String)

@JsonClass(generateAdapter = true)
data class ScanCandidateJson(
    val symbol: String,
    val name: String,
    val sectorEtf: String,
    val sectorName: String?,
    val indexName: String,
    val rsi14: Double?,
    val volumeRatio: Double?,
    val isUptrend: Boolean,
    val trendQuality: Double?,
    val yearChangePercent: Double?,
    val aboveTrendSma: Boolean?,
    val isBuyOpportunity: Boolean,
    val exhaustionConfidence: Int?,
    val exhaustionReasons: List<String>?,
    val declinePercentFromRecentHigh: Double?,
    val nextResistanceTarget: Double?,
    val recentHigh: Double?,
    val recentLow: Double?,
    val recentLowDate: String?,
    val recoveryProgressToResistancePercent: Double?,
    val stockPe: Double?,
    val hasNegativeTrailingEarnings: Boolean,
    val sectorAveragePe: Double?,
    val isSectorInFavor: Boolean,
    val hchState: String,
    val doubleTopBottomPattern: String,
    val tripleTopBottomPattern: String,
    val stockVolatilityRatio: Double?,
    val percentFromYearHigh: Double?,
    val nearestSupportPercent: Double?,
    val nearestResistancePercent: Double?,
    val candlestickPattern: String?,
    val marketTrap: String?,
    val macdHistogram: Double?,
    val shortTermBullish: Boolean,
    val declineAccelerating: Boolean,
    val deathCrossDate: String?,
    val goldenCrossDate: String?,
    val momentumPriceDivergence: String,
    val shortTermFlagPattern: String?,
    val longTermFlagPattern: String?,
    val consolidatedBearishMonthsCount: Int?,
    val combinedScore: Double?,
    val rewardScore: Double?,
    val riskScore: Double?,
    val ratingOutOf10: Int?,
    val summary: String?,
    val penaltyWarnings: List<String>?,
    val bonusWarnings: List<String>?,
    // NUEVO — pedido expresamente: antes esta info no se exportaba, así que el desglose "Cómo
    // se calculó" quedaba vacío en la app para los candidatos importados del JSON. Con esto,
    // ese desglose funciona igual venga el dato de un escaneo en directo o del JSON.
    val factors: List<FactorJson>?,
    val rewardBreakdown: List<String>?,
    val riskBreakdown: List<String>?
)

@JsonClass(generateAdapter = true)
data class ScanResultJson(
    val generatedAtEpochMillis: Long,
    val indexName: String,
    val totalSymbols: Int,
    val symbolsWithData: Int,
    val candidates: List<ScanCandidateJson>,
    val top10: List<ScanCandidateJson>
)

private const val SCAN_CONCURRENCY = 15

fun main(args: Array<String>) = runBlocking {
    val marketArg = args.getOrNull(0) ?: "SP500"
    val market = MarketUniverse.entries.firstOrNull { it.indexName == marketArg }
        ?: error("Mercado desconocido: '$marketArg'. Usa SP500, NASDAQ100 o IBEX35.")
    val universe: List<StockUniverseEntry> = when (market) {
        MarketUniverse.SP500 -> StockUniverse.SP500
        MarketUniverse.NASDAQ100 -> StockUniverse.NASDAQ100
        MarketUniverse.IBEX35 -> StockUniverse.IBEX35
        else -> error("El mercado '$marketArg' no tiene universo de acciones que escanear.")
    }
    println("Escaneando ${universe.size} símbolos de ${market.displayName}...")

    val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val yahooApi = Retrofit.Builder()
        .baseUrl("https://query1.finance.yahoo.com/")
        .client(client)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(YahooChartApiCli::class.java)
    val finvizApi = Retrofit.Builder()
        .baseUrl("https://finviz.com/")
        .client(client)
        .build()
        .create(FinvizApiCli::class.java)

    suspend fun fetchWeeklyCandles(symbol: String): List<Candle> = try {
        val respuesta = yahooApi.getChart(symbol, range = "1y", interval = "1wk")
        respuesta.chart.result?.firstOrNull()?.toCandles() ?: emptyList()
    } catch (e: Exception) {
        println("  $symbol: fallo al pedir velas semanales (${e.message ?: e::class.simpleName})")
        emptyList()
    }

    // NUEVO — pedido expresamente, últimas piezas para el 100%: velas DIARIAS (1 año, igual que
    // Top10Repository.refreshDailyCandlesForSmaIfStale con ChartRange.ONE_YEAR → range="1y") para
    // el patrón de bandera a corto plazo y el cruce SMA20/SMA50 con precisión diaria, y
    // MENSUALES (10 años, igual que MarketRepository.refreshMonthlyCandlesForFlagIfStale) para
    // el patrón de bandera a largo plazo y el conteo de meses bajistas consolidados.
    suspend fun fetchDailyCandles(symbol: String): List<Candle> = try {
        val respuesta = yahooApi.getChart(symbol, range = "1y", interval = "1d")
        respuesta.chart.result?.firstOrNull()?.toCandles() ?: emptyList()
    } catch (e: Exception) {
        println("  $symbol: fallo al pedir velas diarias (${e.message ?: e::class.simpleName})")
        emptyList()
    }

    suspend fun fetchMonthlyCandles(symbol: String): List<Candle> = try {
        val respuesta = yahooApi.getChart(symbol, range = "10y", interval = "1mo")
        respuesta.chart.result?.firstOrNull()?.toCandles() ?: emptyList()
    } catch (e: Exception) {
        println("  $symbol: fallo al pedir velas mensuales (${e.message ?: e::class.simpleName})")
        emptyList()
    }

    println("Calculando rotación sectorial...")
    val sectorEtfCandles: Map<String, List<Candle>> = Symbols.SECTOR_ETFS.keys.associateWith { etf ->
        fetchWeeklyCandles(etf)
    }
    val benchmarkCandles = fetchWeeklyCandles(Symbols.SP500_BENCHMARK)
    val sectorPerformance = if (benchmarkCandles.isEmpty()) {
        println("  aviso: sin velas del S&P 500 (${Symbols.SP500_BENCHMARK}) — isSectorInFavor saldrá false para todos")
        emptyList()
    } else {
        SectorRotationCalculator.calculate(sectorEtfCandles, Symbols.SECTOR_ETFS, benchmarkCandles)
    }
    val isSectorInFavor: Map<String, Boolean> = sectorPerformance.associate { it.etfSymbol to it.isConsistentLeader }

    // NUEVO — pedido expresamente (mejoras #1 y #3, opción C): estas dos cuentas son gratis, ya
    // se tienen las velas cargadas de la sección de rotación sectorial de arriba.
    fun declineFromRecentHigh(velas: List<Candle>, semanas: Int = 26): Double? {
        if (velas.size < 5) return null
        val ventana = velas.takeLast(semanas)
        val maximoReciente = ventana.maxOf { it.high }
        val precioActual = velas.last().close
        if (maximoReciente <= 0.0) return null
        return (maximoReciente - precioActual) / maximoReciente * 100
    }
    val benchmarkYearChangePercent: Double? = benchmarkCandles.takeIf { it.size >= 2 }
        ?.let { (it.last().close - it.first().close) / it.first().close * 100 }
    val sectorDeclinePercentByEtf: Map<String, Double?> = sectorEtfCandles.mapValues { (_, velas) -> declineFromRecentHigh(velas) }

    println("Pidiendo PER por sector (Finviz)...")
    val sectorAveragePe: Map<String, Double> = try {
        val html = finvizApi.getSectorGroupsHtml().string()
        val live = FinvizPeParser.parseSectorPe(html)
        Symbols.SECTOR_PE_FALLBACK + live
    } catch (e: Exception) {
        println("  aviso: PER de sector falló (${e.message ?: e::class.simpleName}) — solo se usará el respaldo fijo")
        Symbols.SECTOR_PE_FALLBACK
    }

    println("Pidiendo velas y calculando patrones de ${universe.size} símbolos...")
    val semaphore = Semaphore(SCAN_CONCURRENCY)
    val done = AtomicInteger(0)

    data class Parcial(
        val entry: StockUniverseEntry,
        val candles: List<Candle>,
        val rsi14: Double?,
        val volumeRatio: Double?,
        val uptrend: UptrendDetector.UptrendSignal?,
        val exhaustion: com.inversionadvisor.domain.indicators.ExhaustionSignal?,
        val hchResult: UptrendDetector.HeadAndShouldersResult,
        val doubleTopBottomResult: DoubleTopBottomResult,
        val tripleTopBottomResult: UptrendDetector.TripleTopBottomResult,
        // NUEVO — pedido expresamente: estos 4 y los 3 de después en realidad NO necesitan velas
        // DIARIAS nuevas — BuyOpportunityAnalyzer.kt (la app) los calcula sobre las MISMAS velas
        // SEMANALES que ya se piden para el resto, así que se añaden aquí sin ninguna petición
        // de red extra.
        val stockVolatilityRatio: Double?,
        val percentFromYearHigh: Double?,
        val nearestSupportPercent: Double?,
        val nearestResistancePercent: Double?,
        val candlestickPattern: com.inversionadvisor.domain.indicators.CandlestickPattern?,
        val marketTrap: com.inversionadvisor.domain.indicators.MarketTrapType?,
        val macd: TechnicalAnalysis.MacdResult?,
        val shortTermBullish: Boolean,
        val shortTermPullback: com.inversionadvisor.domain.indicators.ExhaustionSignal?,
        val declineAccelerating: Boolean,
        // NUEVO — últimas piezas para el 100%, necesitan velas diarias/mensuales (ver
        // fetchDailyCandles/fetchMonthlyCandles más arriba).
        val shortTermFlagPattern: com.inversionadvisor.domain.indicators.FlagPattern?,
        val longTermFlagPattern: com.inversionadvisor.domain.indicators.FlagPattern?,
        val consolidatedBearishMonthsCount: Int?,
        val deathCrossDate: String?,
        val goldenCrossDate: String?,
        val momentumPriceDivergence: com.inversionadvisor.domain.indicators.MomentumPriceDivergence,
        val hasLongTermDowntrend: Boolean
    )

    val parciales = universe.map { entry ->
        async(Dispatchers.IO) {
            val resultado = semaphore.withPermit {
                val candles = fetchWeeklyCandles(entry.symbol)
                if (candles.size < 15) return@withPermit null
                val levels = TechnicalAnalysis.detectSupportResistanceLevels(candles)
                val currentPrice = candles.last().close
                val yearHigh = candles.maxOf { it.high }
                val percentFromYearHigh = if (yearHigh > 0) (currentPrice - yearHigh) / yearHigh * 100 else null
                val resistancesAbove = levels.filter { it.type == com.inversionadvisor.domain.indicators.LevelType.RESISTANCE && it.price >= currentPrice }
                val supportsBelow = levels.filter { it.type == com.inversionadvisor.domain.indicators.LevelType.SUPPORT && it.price <= currentPrice }

                val dailyCandles = fetchDailyCandles(entry.symbol)
                val monthlyCandles = fetchMonthlyCandles(entry.symbol)
                // Mismo criterio que BuyOpportunityAnalyzer: con precisión diaria si hay al menos
                // 51 velas diarias (hacen falta 50 para la SMA50), si no se cae a las semanales.
                val crossSourceCandles = if (dailyCandles.size >= 51) dailyCandles else candles
                val usingDailyPrecision = crossSourceCandles === dailyCandles

                Parcial(
                    entry = entry,
                    candles = candles,
                    rsi14 = TechnicalAnalysis.calculateRsi(candles).lastOrNull(),
                    volumeRatio = TechnicalAnalysis.volumeRatio(candles),
                    uptrend = UptrendDetector.evaluate(candles),
                    exhaustion = ExhaustionDetector.detect(candles),
                    hchResult = UptrendDetector.detectHeadAndShoulders(candles),
                    doubleTopBottomResult = UptrendDetector.detectDoubleTopOrBottom(candles),
                    tripleTopBottomResult = UptrendDetector.detectTripleTopOrBottom(candles),
                    stockVolatilityRatio = TechnicalAnalysis.ownVolatilityRatio(candles),
                    percentFromYearHigh = percentFromYearHigh,
                    nearestResistancePercent = resistancesAbove.minByOrNull { it.price }?.let { (it.price - currentPrice) / currentPrice * 100 },
                    nearestSupportPercent = supportsBelow.maxByOrNull { it.price }?.let { (currentPrice - it.price) / currentPrice * 100 },
                    candlestickPattern = TechnicalAnalysis.detectCandlestickPattern(candles),
                    marketTrap = TechnicalAnalysis.detectTrap(candles, levels),
                    macd = TechnicalAnalysis.calculateMacd(candles),
                    shortTermBullish = UptrendDetector.isClearlyBullishShortTerm(candles),
                    shortTermPullback = ExhaustionDetector.detect(
                        candles,
                        minDeclinePercent = Top10Calculator.MIN_SHORT_TERM_DECLINE_PERCENT,
                        lookbackForHigh = Top10Calculator.SHORT_TERM_LOOKBACK_WEEKS
                    ),
                    declineAccelerating = UptrendDetector.isDeclineAcceleratingClearly(candles),
                    shortTermFlagPattern = dailyCandles.takeIf { it.isNotEmpty() }?.let { UptrendDetector.detectFlagPattern(it) },
                    longTermFlagPattern = monthlyCandles.takeIf { it.isNotEmpty() }?.let { UptrendDetector.detectFlagPattern(it) },
                    consolidatedBearishMonthsCount = monthlyCandles.takeIf { it.isNotEmpty() }?.let { UptrendDetector.countBearishMonthsInLast6(it) },
                    deathCrossDate = UptrendDetector.findSma20CrossedBelowSma50Date(crossSourceCandles, lookbackPeriods = if (usingDailyPrecision) 10 else 2),
                    goldenCrossDate = UptrendDetector.findSma20CrossedAboveSma50Date(crossSourceCandles, lookbackPeriods = if (usingDailyPrecision) 10 else 2),
                    momentumPriceDivergence = UptrendDetector.detectMomentumPriceDivergence(candles),
                    hasLongTermDowntrend = UptrendDetector.evaluateDowntrend(candles)
                )
            }
            val hechos = done.incrementAndGet()
            if (hechos % 25 == 0) println("  ...$hechos/${universe.size}")
            resultado
        }
    }.awaitAll().filterNotNull()

    println("Pidiendo PER individual en lotes de 20 (Finviz)...")
    val stockPeBySymbol = mutableMapOf<String, Double>()
    val negativeEarningsBySymbol = mutableSetOf<String>()
    for (lote in parciales.map { it.entry.symbol }.chunked(20)) {
        try {
            val html = finvizApi.getScreenerHtml(tickers = lote.joinToString(",")).string()
            stockPeBySymbol += FinvizPeParser.parseScreenerPeByTicker(html)
        } catch (e: Exception) {
            println("  aviso: PER en lote falló para ${lote.take(3)}... (${e.message ?: e::class.simpleName})")
        }
    }
    val sinPeDelLote = parciales.map { it.entry.symbol }.filterNot { it in stockPeBySymbol }
    for (symbol in sinPeDelLote) {
        semaphore.withPermit {
            try {
                val html = finvizApi.getStockQuoteHtml(symbol).string()
                FinvizPeParser.parseStockPe(html)?.let { stockPeBySymbol[symbol] = it }
                val eps = FinvizPeParser.parseEpsTtm(html)
                if (eps != null && eps < 0.0) negativeEarningsBySymbol += symbol
            } catch (e: Exception) {
                // Sin PER para este símbolo concreto — Top10Calculator ya sabe tratar
                // "Valoración" como "sin dato" cuando stockPe es null.
            }
        }
    }

    println("Calculando puntuación (Top10Calculator)...")
    val candidatos = parciales.map { p ->
        val stockPe = stockPeBySymbol[p.entry.symbol]
        val sectorPe = sectorAveragePe[p.entry.sectorEtf]
        val sectorRange = Symbols.SECTOR_TYPICAL_PE_RANGES[p.entry.sectorEtf]
        val enFavor = isSectorInFavor[p.entry.sectorEtf] ?: false

        val scored = Top10Calculator.score(
            trendQuality = p.uptrend?.trendQuality,
            yearChangePercent = p.uptrend?.yearChangePercent,
            aboveTrendSma = p.uptrend?.aboveTrendSma,
            isSectorInFavor = enFavor,
            longTermDecline = p.exhaustion,
            shortTermPullback = p.shortTermPullback,
            rsi14 = p.rsi14,
            volumeRatio = p.volumeRatio,
            stockVolatilityRatio = p.stockVolatilityRatio,
            stockPe = stockPe,
            sectorAveragePe = sectorPe,
            sectorTypicalPeRange = sectorRange,
            percentFromYearHigh = p.percentFromYearHigh,
            nearestSupportPercent = p.nearestSupportPercent,
            nearestResistancePercent = p.nearestResistancePercent,
            candlestickPattern = p.candlestickPattern,
            marketTrap = p.marketTrap,
            macd = p.macd,
            shortTermBullish = p.shortTermBullish,
            declineAccelerating = p.declineAccelerating,
            deathCrossDate = p.deathCrossDate,
            goldenCrossDate = p.goldenCrossDate,
            momentumPriceDivergence = p.momentumPriceDivergence,
            shortTermFlagPattern = p.shortTermFlagPattern,
            longTermFlagPattern = p.longTermFlagPattern,
            consolidatedBearishMonthsCount = p.consolidatedBearishMonthsCount,
            doubleTopBottomResult = p.doubleTopBottomResult,
            hchResult = p.hchResult,
            hasLongTermUptrend = p.uptrend != null,
            hasLongTermDowntrend = p.hasLongTermDowntrend,
            tripleTopBottomResult = p.tripleTopBottomResult,
            benchmarkYearChangePercent = benchmarkYearChangePercent,
            sectorDeclinePercent = sectorDeclinePercentByEtf[p.entry.sectorEtf],
            requireSignal = false
        )

        ScanCandidateJson(
            symbol = p.entry.symbol,
            name = p.entry.name,
            sectorEtf = p.entry.sectorEtf,
            sectorName = Symbols.SECTOR_ETFS[p.entry.sectorEtf],
            indexName = market.indexName,
            rsi14 = p.rsi14,
            volumeRatio = p.volumeRatio,
            isUptrend = p.uptrend != null,
            trendQuality = p.uptrend?.trendQuality,
            yearChangePercent = p.uptrend?.yearChangePercent,
            aboveTrendSma = p.uptrend?.aboveTrendSma,
            isBuyOpportunity = p.exhaustion?.detected == true,
            exhaustionConfidence = p.exhaustion?.confidenceScore,
            exhaustionReasons = p.exhaustion?.reasons,
            declinePercentFromRecentHigh = p.exhaustion?.declinePercentFromRecentHigh,
            nextResistanceTarget = p.exhaustion?.nextResistanceTarget,
            recentHigh = p.exhaustion?.recentHigh,
            recentLow = p.exhaustion?.recentLow,
            recentLowDate = p.exhaustion?.recentLowDate,
            recoveryProgressToResistancePercent = p.exhaustion?.recoveryProgressToResistancePercent,
            stockPe = stockPe,
            hasNegativeTrailingEarnings = p.entry.symbol in negativeEarningsBySymbol,
            sectorAveragePe = sectorPe,
            isSectorInFavor = enFavor,
            hchState = p.hchResult.state.name,
            doubleTopBottomPattern = p.doubleTopBottomResult.pattern.name,
            tripleTopBottomPattern = p.tripleTopBottomResult.pattern.name,
            stockVolatilityRatio = p.stockVolatilityRatio,
            percentFromYearHigh = p.percentFromYearHigh,
            nearestSupportPercent = p.nearestSupportPercent,
            nearestResistancePercent = p.nearestResistancePercent,
            candlestickPattern = p.candlestickPattern?.name,
            marketTrap = p.marketTrap?.name,
            macdHistogram = p.macd?.histogram,
            shortTermBullish = p.shortTermBullish,
            declineAccelerating = p.declineAccelerating,
            deathCrossDate = p.deathCrossDate,
            goldenCrossDate = p.goldenCrossDate,
            momentumPriceDivergence = p.momentumPriceDivergence.name,
            shortTermFlagPattern = p.shortTermFlagPattern?.name,
            longTermFlagPattern = p.longTermFlagPattern?.name,
            consolidatedBearishMonthsCount = p.consolidatedBearishMonthsCount,
            combinedScore = scored?.combinedScore,
            rewardScore = scored?.rewardScore,
            riskScore = scored?.riskScore,
            ratingOutOf10 = scored?.ratingOutOf10,
            summary = scored?.analystSummary,
            penaltyWarnings = scored?.penaltyWarnings,
            bonusWarnings = scored?.bonusWarnings,
            factors = scored?.factors?.map { FactorJson(it.label, it.grade.name, it.valueText) },
            rewardBreakdown = scored?.rewardBreakdown,
            riskBreakdown = scored?.riskBreakdown
        )
    }.sortedByDescending { it.combinedScore ?: -1.0 }

    val resultadoFinal = ScanResultJson(
        generatedAtEpochMillis = System.currentTimeMillis(),
        indexName = market.indexName,
        totalSymbols = universe.size,
        symbolsWithData = candidatos.size,
        candidates = candidatos,
        top10 = candidatos.filter { it.combinedScore != null }.take(10)
    )

    val adapter = moshi.adapter(ScanResultJson::class.java).indent("  ")
    val outputPath = args.getOrNull(1) ?: "scan-${market.indexName}.json"
    val outputFile = File(outputPath)
    // NUEVO — pedido expresamente tras el fallo real "FileNotFoundException": la carpeta "data/"
    // no existe todavía en un repositorio recién creado (nadie la ha creado a mano nunca) —
    // File.writeText() no crea carpetas que falten por su cuenta, solo escribe el archivo si el
    // directorio que lo contiene ya existe. mkdirs() la crea (junto con cualquier carpeta
    // intermedia que hiciera falta) si no está, y no hace nada si ya existe.
    outputFile.parentFile?.mkdirs()
    outputFile.writeText(adapter.toJson(resultadoFinal))
    println("Listo — ${candidatos.size}/${universe.size} símbolos escritos en $outputPath")
}
