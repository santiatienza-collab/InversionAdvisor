package com.inversionadvisor.ui.screener

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inversionadvisor.data.repository.MarketRepository
import com.inversionadvisor.data.repository.StockUniverseRepository
import com.inversionadvisor.domain.indicators.BuyOpportunityAnalyzer
import com.inversionadvisor.domain.indicators.SellTimingAnalyzer
import com.inversionadvisor.domain.indicators.SellTimingAssessment
import com.inversionadvisor.domain.indicators.TechnicalAnalysis
import com.inversionadvisor.domain.model.Candle
import com.inversionadvisor.domain.model.ChartRange
import com.inversionadvisor.domain.model.Symbols
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Rangos que puede elegir el usuario en la pantalla de detalle. */
val DETAIL_CHART_RANGES = listOf(ChartRange.ONE_DAY, ChartRange.ONE_WEEK, ChartRange.ONE_MONTH, ChartRange.SIX_MONTHS, ChartRange.ONE_YEAR, ChartRange.FIVE_YEARS)

data class StockDetailUiState(
    val symbol: String = "",
    val selectedRange: ChartRange = ChartRange.ONE_YEAR,
    val candles: List<Candle> = emptyList(),
    /** Media móvil de 20 periodos — SOLO para 6 meses, 1 año y 5 años (antes SMA10, cambiada a
     *  SMA20 a petición expresa; antes se calculaba para todos los rangos, ahora solo para
     *  estos 3); en el resto de rangos (1D/1S/1M) se queda vacía. */
    val sma20: List<Double?> = emptyList(),
    /** Media móvil de 50 periodos — SOLO para 6 meses, 1 año y 5 años (antes SMA40, cambiada a
     *  SMA50 a petición expresa; antes solo se calculaba para 5 años). */
    val sma50: List<Double?> = emptyList(),
    /** Cierre de ayer — SOLO para el rango de 1 día, para que la variación mostrada coincida
     *  con la del banner de ganadores/perdedores (los dos comparan contra lo mismo: el cierre
     *  de ayer, no la apertura de hoy). Null en el resto de rangos, o si no se pudo obtener. */
    val yesterdayClose: Double? = null,
    /** AÑADIDO — precio en vivo, sacado de la MISMA petición y el MISMO cálculo que
     *  yesterdayClose (ver MarketRepository.fetchTodayQuoteChange), y a su vez el mismo que usa
     *  el banner de ganadores/perdedores para "el precio de hoy". SOLO para el rango de 1 día;
     *  la UI lo usa en vez del cierre de la última vela cacheada para que el precio/% mostrados
     *  en la cabecera del gráfico 1D coincidan siempre con los del banner (antes podían
     *  divergir: la última vela de 5 min podía quedarse un poco por detrás del precio en vivo
     *  real — reportado por el usuario con Adobe, +2,12% en el banner vs -0,09% en la ficha). */
    val todayLivePrice: Double? = null,
    /** AÑADIDO — límites (epoch segundos, inicio a fin) de la sesión regular de mercado de HOY
     *  para este símbolo, SOLO para 1 DÍA — para que la UI pueda distinguir las velas de
     *  horario extendido (pre-market/after-hours) y pintarlas con un color apagado, pedido
     *  expresamente así. null si aún no ha llegado, si Yahoo no lo dio, o si el rango no es
     *  1 DÍA (los demás rangos no tienen horario extendido que distinguir). */
    val regularSessionBounds: Pair<Long, Long>? = null,
    /** true cuando el gráfico de 1 DÍA no tenía datos frescos de hoy y se ha caído a mostrar
     *  la sesión de AYER completa en su lugar — pedido expresamente así ("puedes mostrar el
     *  del día anterior") como respaldo ante fallos de la petición de datos de hoy. La
     *  interfaz avisa de esto en vez de dar a entender que es la sesión de hoy en vivo. */
    val isShowingYesterdayFallback: Boolean = false,
    val isLoading: Boolean = true,
    val error: String? = null,
    /** "Momento idóneo para la venta": RSI + volumen + volatilidad propia + PER del stock vs sector + cruce SMA20. */
    val sellTiming: SellTimingAssessment? = null,
    /** Capitalización bursátil ("Valor Empresa" en la UI) — null si aún no ha llegado o no se ha podido sacar. */
    val marketCap: Double? = null,
    /** Ingresos netos de 3 años consecutivos hasta el actual — cuál se muestra lo decide la UI con pestañas. */
    val netIncomeCurrentYear: Double? = null,
    val netIncomePreviousYear: Double? = null,
    val netIncomeTwoYearsAgo: Double? = null,
    /** "Análisis de opciones de compra": riesgo/recompensa combinando sector, RSI, PER,
     *  caída, agotamiento, volumen, volatilidad propia, techo/suelo, soportes/resistencias y velas. */
    val buyOpportunityAnalysis: BuyOpportunityAnalyzer.Analysis? = null,
    /** Fecha (o rango) de la próxima publicación de resultados trimestrales, tal cual la
     *  muestra Yahoo — null si no tenemos el dato (Yahoo no lo da para ese símbolo, o aún no
     *  ha llegado la petición). */
    val nextEarningsDateLabel: String? = null,
    /** Fuerza relativa REAL del stock frente al S&P 500 en el rango de gráfica seleccionado —
     *  NO es el "RS Rating" de Investor's Business Daily (esa es una métrica propia y
     *  registrada de IBD, que compara cada stock contra miles de valores — no existe ninguna
     *  fuente gratuita de ese dato exacto). Esto es el cálculo real de este stock: su
     *  rentabilidad menos la del S&P 500 en el mismo periodo, en puntos porcentuales. Positivo
     *  = lo ha batido, negativo = se ha quedado por detrás. Null para oro/cripto/divisas/bonos
     *  (no tiene sentido comparar contra el S&P 500) o si aún no hay dato suficiente. */
    val relativeStrengthVsSpyPercent: Double? = null
)

/**
 * Carga las velas del símbolo para el rango elegido (1D/1S/1M/1A/5A) y
 * calcula su media de 10 periodos, para pintar la gráfica de detalle:
 * precio + SMA arriba, volumen en un panel separado abajo (estilo
 * TradingView). Cambiar de rango vuelve a pedir (o lee de caché) las velas
 * correspondientes.
 *
 * También calcula "Momento idóneo para la venta" (SellTimingAnalyzer) a
 * partir de velas de 1 año (independiente del rango que el usuario esté
 * mirando en la gráfica) combinadas con: volatilidad propia del stock (ya
 * no VIX), el PER del propio stock, el PER medio de su sector y su rango
 * típico (buscados vía [stockUniverseRepository], que ya sabe a qué ETF
 * sectorial pertenece cada símbolo).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StockDetailViewModel(
    private val symbol: String,
    private val marketRepository: MarketRepository,
    /** null para oro/bitcoin/ethereum (no tienen sector ni PER) — en ese caso no se pide PER. */
    private val stockUniverseRepository: StockUniverseRepository? = null
) : ViewModel() {

    private val _selectedRange = MutableStateFlow(ChartRange.ONE_YEAR)
    private val _isLoading = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _sectorEtf = MutableStateFlow<String?>(null)
    private val _yesterdayClose = MutableStateFlow<Double?>(null)
    // AÑADIDO — ver StockDetailUiState.todayLivePrice para el porqué.
    private val _todayLivePrice = MutableStateFlow<Double?>(null)
    // Se rellena una vez en init (ver más abajo) — el propio cálculo compara los 11 sectores
    // entre sí, así que no tiene sentido repetirlo por cada campo que cambie, solo una vez.
    private val _sectorFavorability = MutableStateFlow(com.inversionadvisor.data.repository.MarketRepository.SectorContext(emptyMap(), null, emptyMap()))

    /**
     * SOLO para 1 DÍA: si hoy no hay datos frescos (candles vacío — sea por lo que sea, sin
     * poder confirmar la causa exacta pese a varios intentos), se cae a mostrar la sesión de
     * AYER COMPLETA en vez de un gráfico roto o a 0% — pedido expresamente así ("puedes mostrar
     * el del día anterior, para solucionar todos los problemas que tengo ahora"). Se saca del
     * rango de 1 SEMANA (mucho más robusto, varios días de margen), filtrando solo las velas
     * del último día de calendario presente ahí. El resto de rangos no cambian.
     */
    private val candlesFlow = _selectedRange.flatMapLatest { range ->
        if (range == ChartRange.ONE_DAY) {
            combine(
                marketRepository.observeCandles(symbol, ChartRange.ONE_DAY),
                marketRepository.observeCandles(symbol, ChartRange.ONE_WEEK)
            ) { todayCandles, weekCandles ->
                if (todayCandles.isNotEmpty()) {
                    todayCandles
                } else {
                    val lastDay = weekCandles.lastOrNull()?.datetime?.take(10)
                    if (lastDay != null) weekCandles.filter { it.datetime.take(10) == lastDay } else weekCandles
                }
            }
        } else {
            marketRepository.observeCandles(symbol, range)
        }
    }

    /** AÑADIDO — horario extendido del gráfico 1D (ver StockDetailUiState.regularSessionBounds). */
    private val regularSessionBoundsFlow = _selectedRange.flatMapLatest { range ->
        if (range == ChartRange.ONE_DAY) {
            marketRepository.observeRegularSessionBounds(symbol, ChartRange.ONE_DAY)
        } else {
            flowOf(null)
        }
    }

    /**
     * true cuando el gráfico de 1 DÍA está mostrando el respaldo de "ayer" (ver candlesFlow) —
     * calculado APARTE, sin efectos secundarios dentro de un combine(), para saber si hay que
     * avisar en la cabecera de que lo que se ve es la sesión de ayer, no la de hoy en vivo.
     */
    private val isShowingYesterdayFallbackFlow = _selectedRange.flatMapLatest { range ->
        if (range == ChartRange.ONE_DAY) {
            marketRepository.observeCandles(symbol, ChartRange.ONE_DAY).map { it.isEmpty() }
        } else {
            flowOf(false)
        }
    }

    // Velas DIARIAS reales, solo para SMA20/SMA50 "de verdad" (20/50 días de mercado, no
    // semanas) — el gráfico en sí sigue usando velas semanales para la línea de precio (ver
    // MarketRepository.observeDailyCandlesForSma para el porqué). emptyList() para los rangos
    // que no tienen SMA (1D/1S/1M) — no hace falta pedir nada ahí.
    private val dailyCandlesForSmaFlow = _selectedRange.flatMapLatest { range ->
        if (range == ChartRange.SIX_MONTHS || range == ChartRange.ONE_YEAR || range == ChartRange.FIVE_YEARS) {
            marketRepository.observeDailyCandlesForSma(symbol, range)
        } else {
            flowOf(emptyList())
        }
    }

    // SPY en el MISMO rango que esté seleccionado — para la fuerza relativa real (ver
    // StockDetailUiState.relativeStrengthVsSpyPercent). null para oro/cripto/divisas/bonos
    // (stockUniverseRepository == null), no tiene sentido comparar esos contra el S&P 500.
    private val spyCandlesForRangeFlow = _selectedRange.flatMapLatest { range ->
        if (stockUniverseRepository == null) {
            flowOf(emptyList())
        } else {
            marketRepository.observeCandles(Symbols.SP500_BENCHMARK, range)
        }
    }

    private val stockPeFlow = marketRepository.observeStockPe(symbol)
    private val sectorAveragePeFlow = _sectorEtf.flatMapLatest { etf ->
        if (etf == null) {
            flowOf(null)
        } else {
            marketRepository.observeSectorPeRatios().map { peMap -> peMap[etf] }
        }
    }
    // Mismo rango típico por sector que ya usa "Análisis de opciones de compra" — para que
    // "Momento idóneo para la venta" compare el PER con el mismo criterio.
    private val sectorTypicalPeRangeFlow = _sectorEtf.map { etf -> etf?.let { Symbols.SECTOR_TYPICAL_PE_RANGES[it] } }

    // Volatilidad propia del stock (ya NO el VIX) — misma fórmula que usa la fórmula de compra
    // para su categoría "Riesgo" (TechnicalAnalysis.ownVolatilityRatio). Fear & Greed y AAII
    // se añaden para los bonos "Teoría de la Opinión Contraria" (ver SellTimingAnalyzer) — con
    // 6 flujos que combinar, se hace en dos pasos (Kotlin no tiene combine() de 6 directo).
    private data class SellTimingInputs(
        val candles: List<Candle>,
        val fiveYearCandles: List<Candle>,
        val stockPe: Double?,
        val sectorAveragePe: Double?,
        val sectorTypicalPeRange: Pair<Double, Double>?,
        val fearGreed: com.inversionadvisor.domain.model.CnnFearGreedStatus?
    )

    private val sellTimingFlow = combine(
        marketRepository.observeCandles(symbol, ChartRange.ONE_YEAR),
        // Velas de 5 años — SOLO para "Momento idóneo para la venta" (Hombro-Cabeza-Hombro en su
        // ventana completa de 6 meses-5 años, y Cruce de la Muerte SMA50/SMA200, que necesita
        // ~200 semanas de histórico) — pedido expresamente así, y aceptable en coste porque esta
        // pantalla analiza UN solo stock, no un escaneo de mercado (ver ScreenerScreen.kt, donde
        // se mantiene la ventana de 1 año para no multiplicar peticiones por cientos de
        // candidatos). Refrescada en el init{} de este ViewModel (refreshYahooCandlesIfStale).
        marketRepository.observeCandles(symbol, ChartRange.FIVE_YEARS),
        stockPeFlow,
        sectorAveragePeFlow,
        sectorTypicalPeRangeFlow,
        marketRepository.observeCnnFearGreed()
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        SellTimingInputs(
            candles = values[0] as List<Candle>,
            fiveYearCandles = values[1] as List<Candle>,
            stockPe = values[2] as Double?,
            sectorAveragePe = values[3] as Double?,
            sectorTypicalPeRange = values[4] as Pair<Double, Double>?,
            fearGreed = values[5] as com.inversionadvisor.domain.model.CnnFearGreedStatus?
        )
    }.combine(marketRepository.observeAaiiSentiment()) { inputs, aaii ->
        SellTimingAnalyzer.analyze(
            candles = inputs.candles,
            stockPe = inputs.stockPe,
            sectorAveragePe = inputs.sectorAveragePe,
            sectorTypicalPeRange = inputs.sectorTypicalPeRange,
            fearGreedScore = inputs.fearGreed?.score?.toDouble(),
            aaiiBullishPercent = aaii?.bullishPercent,
            aaiiNeutralPercent = aaii?.neutralPercent,
            aaiiBearishPercent = aaii?.bearishPercent,
            fiveYearCandles = inputs.fiveYearCandles,
            symbol = symbol
        )
    }

    /**
     * "Análisis de opciones de compra": misma idea que "Momento idóneo para la
     * venta" pero mirando la cara opuesta (comprar, no vender) — reutiliza las
     * MISMAS velas de 1 año, el mismo PER del stock y el mismo PER medio del
     * sector que ya se piden arriba, sin ninguna llamada de red adicional.
     */
    private val buyOpportunityFlow = combine(
        marketRepository.observeCandles(symbol, ChartRange.ONE_YEAR),
        stockPeFlow,
        sectorAveragePeFlow,
        _sectorEtf,
        marketRepository.observeCompanyFinancials(symbol),
        marketRepository.observeCandles(Symbols.SP500_BENCHMARK, ChartRange.ONE_YEAR),
        _sectorFavorability,
        marketRepository.observeEarningsDate(symbol),
        // Velas DIARIAS, fijas a ONE_YEAR (independiente del rango que tenga seleccionado el
        // usuario en el gráfico) — para el cruce dorada/muerte SMA20-SMA50 con precisión real,
        // ver comentario completo en BuyOpportunityAnalyzer.dailyCandlesForCross. Reutiliza la
        // MISMA fuente que ya usa el gráfico para 1A, sin petición nueva si el usuario ya está
        // viendo el gráfico en ese rango (comparten caché). AHORA TAMBIÉN se usa para el
        // patrón de bandera "a corto plazo" (ver BuyOpportunityAnalyzer, categoría Bandera).
        marketRepository.observeDailyCandlesForSma(symbol, ChartRange.ONE_YEAR),
        // Velas MENSUALES — NUEVO, solo para el patrón de bandera "a largo plazo". Se piden
        // siempre en la ficha de un stock individual (pedido expresamente así: en Top10/
        // Futuras Compras NO se piden, por el coste de pedirlas para 100-150 candidatos a la
        // vez — aquí, para un solo símbolo, no hay ese problema).
        marketRepository.observeMonthlyCandlesForFlag(symbol),
        // NUEVO — pedido expresamente: reflejar cuándo el "PER" mostrado viene en realidad del
        // Forward P/E (fallback, ver FinvizPeParser.parseStockPe) porque el beneficio de los
        // últimos 12 meses es negativo — caso real: Lumentum.
        marketRepository.observeHasNegativeTrailingEarnings(symbol),
        // NUEVO — pedido expresamente (fallo real: HCH casi nunca se detectaba en "Análisis de
        // opciones de compra" por usar solo 1 año de velas) — reutiliza la MISMA fuente que ya
        // carga "Momento idóneo para la venta" (ver sellTimingFlow más arriba), sin petición nueva.
        marketRepository.observeCandles(symbol, ChartRange.FIVE_YEARS)
    ) { values ->
        val yearCandles = values[0] as List<Candle>
        val stockPe = values[1] as Double?
        val sectorAveragePe = values[2] as Double?
        val sectorEtf = values[3] as String?
        val financials = values[4] as com.inversionadvisor.data.local.entities.CompanyFinancialsEntity?
        val spyCandles = values[5] as List<Candle>
        val sectorContext = values[6] as com.inversionadvisor.data.repository.MarketRepository.SectorContext
        val sectorFavorability = sectorContext.isSectorInFavor
        val earningsDate = values[7] as com.inversionadvisor.data.local.entities.EarningsDateEntity?
        val dailyCandlesForCross = values[8] as List<Candle>
        val monthlyCandlesForFlag = values[9] as List<Candle>
        val hasNegativeTrailingEarnings = values[10] as Boolean
        val hchCandles = values[11] as List<Candle>
        val sectorName = sectorEtf?.let { Symbols.SECTOR_ETFS[it] }
        // Mismo diagnóstico que en ScreenerRepository.scanSymbol — para comparar la vela
        // final y el RSI que ve cada camino cuando dan valores distintos para el mismo símbolo.
        android.util.Log.i(
            "RsiDiagnostic",
            "[FICHA] $symbol: ${yearCandles.size} velas, última=${yearCandles.lastOrNull()?.let { "${it.datetime} cierre=${it.close}" }}, RSI14=${TechnicalAnalysis.calculateRsi(yearCandles).lastOrNull()}"
        )
        // Ingresos ascendentes — mismo dato ya cargado para "Datos de la empresa" en esta misma
        // ficha, ahora también entra en el cálculo de riesgo/recompensa, igual que en Top 10.
        val incomeGrowing = financials?.netIncomeCurrentYear?.let { current ->
            financials.netIncomeTwoYearsAgo?.let { twoYearsAgo -> current > twoYearsAgo }
        }
        // NUEVO — pedido expresamente (mejoras #1 y #3, conectadas ya en scanner-cli): el
        // cambio % del S&P 500 sale gratis de spyCandles (ya se cargaba aquí para otra cosa),
        // y la caída % del propio sector sale de sectorContext (misma vela que isSectorInFavor).
        val benchmarkYearChangePercent = spyCandles.takeIf { it.size >= 2 }
            ?.let { (it.last().close - it.first().close) / it.first().close * 100 }
        BuyOpportunityAnalyzer.analyze(
            candles = yearCandles,
            sectorName = sectorName,
            stockPe = stockPe,
            sectorAveragePe = sectorAveragePe,
            spyCandles = spyCandles.ifEmpty { null },
            incomeGrowing = incomeGrowing,
            sectorInFavor = sectorEtf?.let { sectorFavorability[it] },
            sectorEtf = sectorEtf,
            earningsWithinThreeWeeks = marketRepository.isEarningsWithinThreeWeeks(earningsDate),
            symbol = symbol,
            dailyCandlesForCross = dailyCandlesForCross,
            monthlyCandlesForFlag = monthlyCandlesForFlag.ifEmpty { null },
            hasNegativeTrailingEarnings = hasNegativeTrailingEarnings,
            benchmarkYearChangePercent = benchmarkYearChangePercent,
            sectorDeclinePercent = sectorEtf?.let { sectorContext.sectorDeclinePercentByEtf[it] },
            hchCandles = hchCandles.ifEmpty { null }
        )
    }

    val uiState: StateFlow<StockDetailUiState> = combine(
        _selectedRange,
        candlesFlow,
        _isLoading,
        _error,
        sellTimingFlow,
        marketRepository.observeCompanyFinancials(symbol),
        buyOpportunityFlow,
        marketRepository.observeEarningsDate(symbol),
        spyCandlesForRangeFlow,
        dailyCandlesForSmaFlow,
        _yesterdayClose,
        _todayLivePrice,
        isShowingYesterdayFallbackFlow,
        regularSessionBoundsFlow
    ) { values ->
        val range = values[0] as ChartRange
        val candles = values[1] as List<Candle>
        val isLoading = values[2] as Boolean
        val error = values[3] as String?
        val sellTiming = values[4] as SellTimingAssessment?
        val financials = values[5] as com.inversionadvisor.data.local.entities.CompanyFinancialsEntity?
        val buyOpportunity = values[6] as BuyOpportunityAnalyzer.Analysis?
        val earningsDateForLabel = values[7] as com.inversionadvisor.data.local.entities.EarningsDateEntity?
        val spyCandlesForRange = values[8] as List<Candle>
        val dailyCandlesForSma = values[9] as List<Candle>
        val yesterdayClose = values[10] as Double?
        val todayLivePrice = values[11] as Double?
        val isShowingYesterdayFallback = values[12] as Boolean
        @Suppress("UNCHECKED_CAST")
        val regularSessionBounds = values[13] as Pair<Long, Long>?
        // SMA20/SMA50 de verdad (20/50 días de mercado) calculadas sobre las velas DIARIAS, y
        // luego alineadas con las fechas de las velas SEMANALES que usa el gráfico — para cada
        // vela semanal, se coge el valor de la media diaria en la fecha diaria más cercana
        // (igual o anterior) a esa semana. Ver comentario completo en alignDailySmaToWeeklyDates.
        val dailySma20 = TechnicalAnalysis.simpleMovingAverage(dailyCandlesForSma, period = 20)
        val dailySma50 = TechnicalAnalysis.simpleMovingAverage(dailyCandlesForSma, period = 50)
        StockDetailUiState(
            symbol = symbol,
            selectedRange = range,
            candles = candles,
            sma20 = if (range == ChartRange.SIX_MONTHS || range == ChartRange.ONE_YEAR || range == ChartRange.FIVE_YEARS) {
                TechnicalAnalysis.alignDailySmaToWeeklyDates(candles, dailyCandlesForSma, dailySma20)
            } else {
                emptyList()
            },
            sma50 = if (range == ChartRange.SIX_MONTHS || range == ChartRange.ONE_YEAR || range == ChartRange.FIVE_YEARS) {
                TechnicalAnalysis.alignDailySmaToWeeklyDates(candles, dailyCandlesForSma, dailySma50)
            } else {
                emptyList()
            },
            yesterdayClose = yesterdayClose,
            todayLivePrice = todayLivePrice,
            regularSessionBounds = regularSessionBounds,
            isShowingYesterdayFallback = isShowingYesterdayFallback,
            isLoading = isLoading,
            error = error,
            sellTiming = sellTiming,
            marketCap = financials?.marketCap,
            netIncomeCurrentYear = financials?.netIncomeCurrentYear,
            netIncomePreviousYear = financials?.netIncomePreviousYear,
            netIncomeTwoYearsAgo = financials?.netIncomeTwoYearsAgo,
            buyOpportunityAnalysis = buyOpportunity,
            nextEarningsDateLabel = earningsDateForLabel?.earningsDateText,
            relativeStrengthVsSpyPercent = computeRelativeStrengthVsSpy(candles, spyCandlesForRange)
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = StockDetailUiState(symbol = symbol)
    )

    init {
        loadRange(ChartRange.ONE_YEAR)
        // Las 6 tareas de aquí abajo son independientes entre sí — antes iban todas en la
        // MISMA corrutina, una detrás de otra (esperar AAII, LUEGO Fear&Greed, LUEGO
        // resultados, LUEGO SPY, LUEGO velas diarias, LUEGO velas mensuales), así que los
        // avisos en neón de "Análisis de opciones de compra" (que dependen de varias de estas)
        // tardaban varios segundos en aparecer, sumando el tiempo de las 6 una a una. Ahora
        // cada una va suelta, en paralelo — mismo principio que ya se aplicó más abajo para
        // "sector"/PER por sector/datos de empresa.
        viewModelScope.launch {
            // OJO: la carga de velas de ONE_YEAR ya la dispara loadRange() justo arriba —
            // llamar aquí OTRA VEZ a refreshYahooCandlesIfStale(symbol, ONE_YEAR) sería una
            // segunda petición concurrente para el MISMO símbolo+rango, que competía con la
            // primera al borrar e insertar en Room casi a la vez (limpia-inserta-limpia-inserta
            // entrelazados) y podía dejar la caché de velas vacía o a medias — la causa real de
            // que algunos stocks del IBEX 35 (p.ej. Repsol) mostraran "sin datos suficientes" en
            // Busca.
            runCatching { marketRepository.refreshAaiiSentimentIfStale() }
        }
        viewModelScope.launch {
            runCatching { marketRepository.refreshCnnFearGreedIfStale() }
        }
        viewModelScope.launch {
            // Velas de 5 años — para el Hombro-Cabeza-Hombro (ventana completa 6 meses-5 años)
            // y el Cruce de la Muerte SMA50/SMA200 de "Momento idóneo para la venta" (ver
            // sellTimingFlow). Si el usuario ya está viendo el gráfico en "5A", comparte caché
            // con esto, sin petición extra.
            runCatching { marketRepository.refreshYahooCandlesIfStale(symbol, ChartRange.FIVE_YEARS) }
        }
        viewModelScope.launch {
            // Fecha de próximos resultados — para la penalización "resultados en menos de 3
            // semanas" de "Análisis de opciones de compra".
            runCatching { marketRepository.refreshEarningsDateIfStale(symbol) }
        }
        viewModelScope.launch {
            // SPY — para "aceleración" (fuerza relativa 1M/3M/6M) en "Análisis de opciones de
            // compra", misma referencia de mercado que ya usa Top10Repository. Suele ser un
            // acierto de caché la mayoría de las veces (SPY se pide en muchos otros sitios de
            // la app), así que esto rara vez añade una petición de red de verdad.
            runCatching { marketRepository.refreshYahooCandlesIfStale(Symbols.SP500_BENCHMARK, ChartRange.ONE_YEAR) }
        }
        viewModelScope.launch {
            // Velas DIARIAS para el cruce dorada/muerte SMA20-SMA50 de "Análisis de opciones de
            // compra" — FIJO a ONE_YEAR, independiente del rango que el usuario tenga
            // seleccionado en el gráfico (encontrado con ADP/PAYX mostrando un cruce que en
            // realidad había pasado en mayo — el cálculo usaba velas semanales, mucho más
            // bastas, que puede marcar su propio cruce en una fecha distinta a la real). Si el
            // usuario está viendo el gráfico en 1A, comparte caché con esto, sin petición extra.
            runCatching { marketRepository.refreshDailyCandlesForSmaIfStale(symbol, ChartRange.ONE_YEAR) }
        }
        viewModelScope.launch {
            // Velas MENSUALES para el patrón de bandera "a largo plazo" — solo se pide aquí
            // (ficha de un stock individual), no en Top10/Futuras Compras por el coste de
            // pedirla para 100-150 candidatos a la vez.
            runCatching { marketRepository.refreshMonthlyCandlesForFlagIfStale(symbol) }
        }
        if (stockUniverseRepository != null) {
            // Las 3 tareas de aquí abajo son independientes entre sí, pero antes iban una
            // detrás de otra en la misma corrutina (esperar PER del stock, LUEGO esperar PER de
            // todos los sectores, y SOLO ENTONCES buscar el sector) — así que "sector"/"PER por
            // sector" se quedaban esperando a dos peticiones de red que no tenían nada que ver
            // con encontrar el sector en sí. findSectorEtf() suele ser rápido (lectura de la
            // caché del universo de acciones, sin red la mayoría de las veces) — ahora va suelto,
            // sin esperar a nada, así que "sector" aparece en cuanto esté listo de verdad.
            viewModelScope.launch {
                val etf = runCatching { stockUniverseRepository.findSectorEtf(symbol) }.getOrNull()
                _sectorEtf.value = etf
            }
            viewModelScope.launch {
                runCatching { marketRepository.refreshStockPeIfStale(symbol) }
            }
            viewModelScope.launch {
                runCatching { marketRepository.refreshSectorPeRatiosIfStale() }
            }
            viewModelScope.launch {
                runCatching { marketRepository.refreshCompanyFinancialsIfStale(symbol) }
            }
            viewModelScope.launch {
                // "Sector en auge" — el MISMO cálculo que usa el escáner (compara los 11
                // sectores entre sí), para que el riesgo/recompensa de esta ficha coincida con
                // el de Top10/Futuras compras para este mismo símbolo. Antes esto no se
                // calculaba aquí (demasiado caro traer los 11 sectores solo para ver un stock,
                // se decía) y por eso los números no cuadraban — el coste real suele ser bajo
                // si Panel/Análisis ya se han abierto antes (misma caché de velas compartida).
                runCatching { marketRepository.computeSectorContext() }.getOrNull()?.let {
                    _sectorFavorability.value = it
                }
            }
        }
        startOneDayLiveRefreshLoop()
    }

    /**
     * Bucle en segundo plano que refresca el gráfico cada minuto MIENTRAS el usuario tenga
     * seleccionado el rango de 1 DÍA — pedido expresamente ("que el gráfico 1D se actualice lo
     * antes que pueda, salvo el retraso de Yahoo"). Sin esto, bajar el TTL
     * (CacheConfig.CANDLE_TTL_INTRADAY_MILLIS) no serviría de nada si el usuario se queda
     * quieto mirando el mismo gráfico: loadRange() solo se dispara al CAMBIAR de rango, no por
     * sí solo con el tiempo. Al estar atado a viewModelScope, se para solo al salir de la
     * ficha. Si el rango seleccionado NO es 1 DÍA, este bucle no hace nada (comprueba y
     * duerme), no gasta peticiones de más en el resto de rangos.
     */
    private fun startOneDayLiveRefreshLoop() {
        viewModelScope.launch {
            while (true) {
                delay(120_000L)
                if (_selectedRange.value == ChartRange.ONE_DAY) {
                    loadRange(ChartRange.ONE_DAY)
                }
            }
        }
    }

    fun selectRange(range: ChartRange) {
        if (_selectedRange.value == range) return
        _selectedRange.value = range
        loadRange(range)
    }

    private fun loadRange(range: ChartRange) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                // Para 1 DÍA: petición SIEMPRE fresca, sin mirar la caché (refreshYahooCandles
                // en vez de refreshYahooCandlesIfStale) — pedido expresamente así ("que haga
                // una petición en ese momento del valor de hoy"), en vez de fiarse de la caché
                // con su margen (que podía dejar datos de horas o incluso del día anterior
                // sirviéndose sin refrescar). Solo se fuerza para 1 DÍA — el resto de rangos
                // (semanas/meses/años) siguen con su caché normal, no hace falta pedirlos de
                // nuevo cada vez que se abren, y así no se satura de peticiones.
                if (range == ChartRange.ONE_DAY) {
                    marketRepository.refreshYahooCandles(symbol, range)
                } else {
                    marketRepository.refreshYahooCandlesIfStale(symbol, range)
                }
                // SPY del MISMO rango — para la fuerza relativa real (ver
                // computeRelativeStrengthVsSpy). No se pide para oro/cripto/divisas/bonos
                // (stockUniverseRepository == null), no tiene sentido esa comparación ahí.
                if (stockUniverseRepository != null) {
                    runCatching { marketRepository.refreshYahooCandlesIfStale(Symbols.SP500_BENCHMARK, range) }
                }
                // Velas diarias reales, solo para SMA20/SMA50 "de verdad" — ver
                // alignDailySmaToWeeklyDates. No falla la carga de la gráfica si esto falla
                // (runCatching): sin esto, el precio se ve igual, solo faltarían las medias.
                if (range == ChartRange.SIX_MONTHS || range == ChartRange.ONE_YEAR || range == ChartRange.FIVE_YEARS) {
                    runCatching { marketRepository.refreshDailyCandlesForSmaIfStale(symbol, range) }
                }
                // Cierre de ayer — SOLO para 1 día, para que la variación mostrada coincida con
                // la del banner de ganadores/perdedores (ver MarketRepository.fetchYesterdayClose).
                // CAMBIADO — ahora se pide junto con el precio en vivo en UNA sola llamada
                // (fetchTodayQuoteChange), en vez de fetchYesterdayClose por separado: así la
                // cabecera del gráfico usa el MISMO precio en vivo que el banner, no el cierre
                // de la última vela cacheada (ver StockDetailUiState.todayLivePrice).
                if (range == ChartRange.ONE_DAY) {
                    val todayChange = runCatching { marketRepository.fetchTodayQuoteChange(symbol) }.getOrNull()
                    _yesterdayClose.value = todayChange?.yesterdayClose
                    _todayLivePrice.value = todayChange?.currentPrice
                } else {
                    _yesterdayClose.value = null
                    _todayLivePrice.value = null
                }
                // Respaldo de "sesión de ayer" para 1 DÍA (ver candlesFlow/isShowingYesterdayFallbackFlow)
                // — se pide SIEMPRE que se cargue 1 DÍA, no solo cuando falla, para que el
                // respaldo ya esté listo en caché si hiciera falta, sin esperar a un segundo
                // viaje de red en el momento en que se detecta el fallo.
                if (range == ChartRange.ONE_DAY) {
                    runCatching { marketRepository.refreshYahooCandlesIfStale(symbol, ChartRange.ONE_WEEK) }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "No se pudo cargar la gráfica de $symbol"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Fuerza relativa REAL frente al S&P 500 (ver comentario completo en
     * StockDetailUiState.relativeStrengthVsSpyPercent) — rentabilidad del stock menos
     * rentabilidad de SPY, ambas en el MISMO periodo (el rango de gráfica seleccionado), en
     * puntos porcentuales. Positivo = el stock ha batido al S&P 500 en ese periodo.
     */
    private fun computeRelativeStrengthVsSpy(stockCandles: List<Candle>, spyCandles: List<Candle>): Double? {
        if (stockCandles.size < 2 || spyCandles.size < 2) return null
        val stockFirst = stockCandles.first().close
        val stockLast = stockCandles.last().close
        val spyFirst = spyCandles.first().close
        val spyLast = spyCandles.last().close
        if (stockFirst == 0.0 || spyFirst == 0.0) return null
        val stockReturn = (stockLast - stockFirst) / stockFirst * 100
        val spyReturn = (spyLast - spyFirst) / spyFirst * 100
        return stockReturn - spyReturn
    }

    /**
     * Para cada vela SEMANAL (la que usa el gráfico para la línea de precio), busca en las
     * velas DIARIAS la más reciente cuya fecha sea IGUAL O ANTERIOR a esa semana, y coge el
     * valor de la media en ese punto — así una SMA20/SMA50 calculada con precisión sobre datos
     * DIARIOS (20/50 días de mercado de verdad) se puede superponer sobre el eje semanal del
     * gráfico, en vez de calcularla directamente sobre las velas semanales (que daría 20/50
     * SEMANAS, no días — el problema real que se corrigió aquí).
     *
     * Las fechas (`datetime`) son cadenas ISO-8601 (java.time.Instant.toString(), p. ej.
     * "2026-08-25T00:00:00Z") — comparables directamente como texto sin parsear a objetos de
     * fecha, porque ese formato ordena igual alfabéticamente que cronológicamente.
     */
    class Factory(
        private val symbol: String,
        private val marketRepository: MarketRepository,
        private val stockUniverseRepository: StockUniverseRepository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return StockDetailViewModel(symbol, marketRepository, stockUniverseRepository) as T
        }
    }
}
