package com.inversionadvisor.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inversionadvisor.data.repository.MarketMoversRepository
import com.inversionadvisor.data.repository.MarketRepository
import com.inversionadvisor.di.CurrencyPreferenceStore
import com.inversionadvisor.domain.indicators.SectorRotationCalculator
import com.inversionadvisor.domain.indicators.VolatilityAnalyzer
import com.inversionadvisor.domain.indicators.CorrelationAnalyzer
import com.inversionadvisor.domain.model.AaiiSentiment
import com.inversionadvisor.domain.model.Candle
import com.inversionadvisor.domain.model.ChartRange
import com.inversionadvisor.domain.model.CnnFearGreedStatus
import com.inversionadvisor.domain.model.CorrelationStatus
import com.inversionadvisor.domain.model.Currency
import com.inversionadvisor.domain.model.DisplayCurrency
import com.inversionadvisor.domain.model.MarketMover
import com.inversionadvisor.domain.model.Quote
import com.inversionadvisor.domain.model.RotationHorizon
import com.inversionadvisor.domain.model.SectorPerformance
import com.inversionadvisor.domain.model.Symbols
import com.inversionadvisor.domain.model.VixStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface DashboardUiState {
    object Loading : DashboardUiState

    data class Content(
        val vixStatus: VixStatus?,
        val vixHistory: List<Float>,
        /** Índice de correlación (^COR3M) — pedido expresamente así, justo debajo del VIX. */
        val correlationStatus: CorrelationStatus?,
        val correlationHistory: List<Float>,
        val cnnFearGreed: CnnFearGreedStatus?,
        val aaiiSentiment: AaiiSentiment?,
        val goldQuote: Quote?,
        val goldHistory: List<Float>,
        val bitcoinQuote: Quote?,
        val bitcoinHistory: List<Float>,
        val ethereumQuote: Quote?,
        val ethereumHistory: List<Float>,
        val sectorPerformance: List<SectorPerformance>,
        val selectedRotationHorizon: RotationHorizon,
        val isSectorRotationLoading: Boolean,
        val displayCurrency: DisplayCurrency,
        val ratesToUsd: Map<Currency, Double>,
        val refreshError: String?,
        /** Activo (oro/bitcoin/ethereum) actualmente abierto en la pantalla de detalle, o null si estamos en el panel. */
        val selectedAsset: SelectedAsset?,
        /** Top ganadores/perdedores del día (universo SP500+NASDAQ100+IBEX35) para el banner rotatorio. */
        val topGainers: List<MarketMover>,
        val topLosers: List<MarketMover>
    ) : DashboardUiState
}

/** Símbolo real usado para pedir las velas (ticker de Yahoo) + título legible para la pantalla de detalle. */
/** [showVolume]: false para índices sin volumen real (el VIX) — pedido expresamente así. */
data class SelectedAsset(val chartSymbol: String, val displayTitle: String, val showVolume: Boolean = true)

class DashboardViewModel(
    private val repository: MarketRepository,
    private val marketMoversRepository: MarketMoversRepository
) : ViewModel() {

    private val _sectorPerformance = MutableStateFlow<List<SectorPerformance>>(emptyList())
    private val _selectedRotationHorizon = MutableStateFlow(RotationHorizon.ONE_MONTH)
    private val _isSectorRotationLoading = MutableStateFlow(true)
    private val _refreshError = MutableStateFlow<String?>(null)
    private val _selectedAsset = MutableStateFlow<SelectedAsset?>(null)

    private data class BaseData(
        val vixQuote: Quote?,
        val sectorQuotes: List<Quote>,
        val goldQuote: Quote?,
        val bitcoinQuote: Quote?,
        val ethereumQuote: Quote?,
        val ratesToUsd: Map<Currency, Double>,
        /** Índice de correlación (^COR3M) — pedido expresamente así, justo debajo del VIX. */
        val correlationQuote: Quote? = null
    )

    private val baseDataFlow = combine(
        repository.observeQuote(Symbols.VIX),
        repository.observeQuotes(Symbols.SECTOR_ETFS.keys.toList()),
        repository.observeQuote(Symbols.GOLD),
        repository.observeQuote(Symbols.BITCOIN),
        repository.observeQuote(Symbols.ETHEREUM)
    ) { vix, sectorQuotes, gold, bitcoin, ethereum ->
        BaseData(vix, sectorQuotes, gold, bitcoin, ethereum, emptyMap())
    }

    private val baseDataWithRatesFlow = combine(
        baseDataFlow,
        repository.observeRatesToUsd(),
        repository.observeQuote(Symbols.CORRELATION_INDEX)
    ) { base, rates, correlationQuote -> base.copy(ratesToUsd = rates, correlationQuote = correlationQuote) }

    /** kotlinx.coroutines.combine() solo tiene sobrecarga tipada hasta 5 flows,
     *  así que se agrupan primero los dos indicadores de sentimiento. */
    private data class SentimentData(
        val cnnFearGreed: CnnFearGreedStatus?,
        val aaiiSentiment: AaiiSentiment?
    )

    private val sentimentFlow = combine(
        repository.observeCnnFearGreed(),
        repository.observeAaiiSentiment()
    ) { fearGreed, aaiiSentiment -> SentimentData(fearGreed, aaiiSentiment) }

    /**
     * Históricos puramente ilustrativos para los sparklines de cada tarjeta.
     * VIX/oro salen de las velas ya cacheadas en Room (misma infraestructura
     * que la rotación sectorial); bitcoin/ethereum, del sparkline de 7 días
     * de CoinGecko.
     */
    private data class ChartsData(
        val vixHistory: List<Float>,
        val correlationHistory: List<Float>,
        val goldHistory: List<Float>,
        val bitcoinHistory: List<Float>,
        val ethereumHistory: List<Float>
    )

    private val chartsFlow = combine(
        repository.observeCandles(Symbols.VIX, ChartRange.ONE_MONTH),
        repository.observeCandles(Symbols.CORRELATION_INDEX, ChartRange.ONE_MONTH),
        repository.observeCandles(Symbols.GOLD, ChartRange.ONE_MONTH),
        repository.observeCryptoSparkline(Symbols.BITCOIN),
        repository.observeCryptoSparkline(Symbols.ETHEREUM)
    ) { vixCandles, correlationCandles, goldCandles, btcPrices, ethPrices ->
        ChartsData(
            vixHistory = vixCandles.map { it.close.toFloat() },
            correlationHistory = correlationCandles.map { it.close.toFloat() },
            goldHistory = goldCandles.map { it.close.toFloat() },
            bitcoinHistory = btcPrices.map { it.toFloat() },
            ethereumHistory = ethPrices.map { it.toFloat() }
        )
    }

    /** Otra capa más para no superar el límite de 5 flows tipados de combine(). */
    private data class ExtrasData(val sentiment: SentimentData, val charts: ChartsData)

    private val extrasFlow = combine(sentimentFlow, chartsFlow) { sentiment, charts -> ExtrasData(sentiment, charts) }

    private data class SectorRotationState(
        val performance: List<SectorPerformance>,
        val selectedHorizon: RotationHorizon
    )

    private val sectorRotationFlow = combine(
        _sectorPerformance,
        _selectedRotationHorizon
    ) { performance, horizon -> SectorRotationState(performance, horizon) }

    /** Agrupa lo que sobraba para no pasar de 5 flows tipados en el combine() principal. */
    private data class MiscState(
        val displayCurrency: DisplayCurrency,
        val error: String?,
        val selectedAsset: SelectedAsset?
    )

    private val miscFlow = combine(
        CurrencyPreferenceStore.displayCurrency,
        _refreshError,
        _selectedAsset
    ) { currency, error, asset -> MiscState(currency, error, asset) }

    private data class MarketMoversState(
        val topGainers: List<MarketMover>,
        val topLosers: List<MarketMover>
    )

    private val marketMoversFlow = combine(
        marketMoversRepository.observeTopGainers(),
        marketMoversRepository.observeTopLosers()
    ) { gainers, losers ->
        // Diagnóstico — última pieza que falta: qué recibe la interfaz EXACTAMENTE, tal
        // cual sale de la consulta Flow, justo antes de pintarse en el banner. Si aquí YA
        // sale solo IBEX35, el problema está en la consulta/tabla; si aquí sale una mezcla
        // pero el banner solo muestra IBEX35, el problema estaría en el propio banner.
        android.util.Log.i(
            "MarketMoversDiagnostic",
            "TOP GAINERS recibidos por la interfaz: " + gainers.joinToString { "${it.symbol}=${"%.2f".format(it.changePercent)}%" }
        )
        android.util.Log.i(
            "MarketMoversDiagnostic",
            "TOP LOSERS recibidos por la interfaz: " + losers.joinToString { "${it.symbol}=${"%.2f".format(it.changePercent)}%" }
        )
        MarketMoversState(gainers, losers)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        baseDataWithRatesFlow,
        extrasFlow,
        sectorRotationFlow,
        miscFlow,
        marketMoversFlow
    ) { base, extras, sectorRotation, misc, movers ->
        // VIX real de Yahoo (ya no un proxy): 5 niveles (ver VolatilityAnalyzer).
        val vixStatus = base.vixQuote?.let { VolatilityAnalyzer.analyzeRealVix(it) }
        // Índice de correlación (^COR3M) — pedido expresamente así, justo debajo del VIX.
        val correlationStatus = base.correlationQuote?.let { CorrelationAnalyzer.analyze(it) }

        DashboardUiState.Content(
            vixStatus = vixStatus,
            vixHistory = extras.charts.vixHistory,
            correlationStatus = correlationStatus,
            correlationHistory = extras.charts.correlationHistory,
            cnnFearGreed = extras.sentiment.cnnFearGreed,
            aaiiSentiment = extras.sentiment.aaiiSentiment,
            goldQuote = base.goldQuote,
            goldHistory = extras.charts.goldHistory,
            bitcoinQuote = base.bitcoinQuote,
            bitcoinHistory = extras.charts.bitcoinHistory,
            ethereumQuote = base.ethereumQuote,
            ethereumHistory = extras.charts.ethereumHistory,
            sectorPerformance = sectorRotation.performance,
            selectedRotationHorizon = sectorRotation.selectedHorizon,
            isSectorRotationLoading = _isSectorRotationLoading.value,
            displayCurrency = misc.displayCurrency,
            ratesToUsd = base.ratesToUsd,
            refreshError = misc.error,
            selectedAsset = misc.selectedAsset,
            topGainers = movers.topGainers,
            topLosers = movers.topLosers
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = DashboardUiState.Loading
    )

    init {
        refresh()
        startLiveMoversRefreshLoop()
    }

    /**
     * Bucle en segundo plano que refresca SOLO los símbolos ya mostrados en el banner de
     * ganadores/perdedores cada minuto — pedido expresamente ("banner en tiempo real, con la
     * variación de precio día a día"). Al estar atado a viewModelScope, se para solo cuando el
     * usuario sale del Panel (el ViewModel se limpia), sin tener que cancelarlo a mano. El
     * escaneo COMPLETO del universo (~600 símbolos) sigue con su propia cadencia mucho más
     * lenta (10 min, en refresh() de arriba) — este bucle NO lo sustituye, solo mantiene "en
     * vivo" lo que YA se está mostrando; ver MarketMoversRepository.refreshCurrentlyShownIfStale
     * para el razonamiento completo de por qué no se puede hacer esto con el universo entero.
     */
    private fun startLiveMoversRefreshLoop() {
        viewModelScope.launch {
            while (true) {
                delay(120_000L)
                runCatching { marketMoversRepository.refreshCurrentlyShownIfStale() }
            }
        }
    }

    /**
     * Las 10 fuentes de datos del panel son independientes entre sí (van a APIs y
     * clientes HTTP distintos: Yahoo, CoinGecko, CNN, AAII...), así que se lanzan
     * TODAS a la vez con `async` en vez de una detrás de otra con `await`
     * secuencial. Antes, cada `runIsolated` esperaba a que terminara la anterior
     * antes de empezar la siguiente — con 10 pasos en fila (dos de ellos pesados:
     * rotación sectorial y el escaneo del banner), el tiempo total era la SUMA de
     * los 10, no el máximo. `errors` se escribe desde varias corrutinas a la vez,
     * así que va protegida con un Mutex.
     */
    fun refresh() {
        viewModelScope.launch {
            val errors = mutableListOf<String>()
            val errorsMutex = Mutex()

            suspend fun runIsolated(label: String, block: suspend () -> Unit) {
                try {
                    block()
                } catch (e: Exception) {
                    errorsMutex.withLock { errors += "$label: ${e.message ?: "error desconocido"}" }
                }
            }

            coroutineScope {
                listOf(
                    async { runIsolated("Resumen de mercado (Yahoo)") { repository.refreshMarketOverview() } },
                    async { runIsolated("Cripto (CoinGecko)") { repository.refreshCryptoQuotesIfStale() } },
                    async { runIsolated("Miedo y codicia (CNN)") { repository.refreshCnnFearGreedIfStale() } },
                    async { runIsolated("Sentimiento AAII") { repository.refreshAaiiSentimentIfStale() } },
                    async { runIsolated("Gráfico VIX") { repository.refreshYahooCandlesIfStale(Symbols.VIX, ChartRange.ONE_MONTH) } },
                    async { runIsolated("Gráfico correlación") { repository.refreshYahooCandlesIfStale(Symbols.CORRELATION_INDEX, ChartRange.ONE_MONTH) } },
                    async { runIsolated("Gráfico oro") { repository.refreshYahooCandlesIfStale(Symbols.GOLD, ChartRange.ONE_MONTH) } },
                    async { runIsolated("Gráfico Bitcoin") { repository.refreshCryptoSparklineIfStale(Symbols.BITCOIN) } },
                    async { runIsolated("Gráfico Ethereum") { repository.refreshCryptoSparklineIfStale(Symbols.ETHEREUM) } },
                    async { runIsolated("Rotación sectorial") { refreshSectorRotation() } },
                    async { runIsolated("Top ganadores/perdedores") { marketMoversRepository.refreshIfStale() } }
                ).awaitAll()
            }

            _refreshError.value = errors.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }
    }

    /**
     * Refresca las velas SEMANALES de 1 año (ChartRange.ONE_YEAR) de los 13
     * ETFs sectoriales + el benchmark S&P 500 vía Yahoo Finance. Los sectores
     * se piden TODOS A LA VEZ con `async` (antes era un `for` secuencial, uno
     * detrás de otro — con 13 peticiones de red esa espera se notaba mucho:
     * el tiempo total era la suma de las 13, no la del más lento). El
     * benchmark va aparte y primero porque hace falta para calcular la
     * fuerza relativa de cada sector.
     */
    private suspend fun refreshSectorRotation() {
        _isSectorRotationLoading.value = true

        var benchmarkCandles: List<Candle> = emptyList()
        try {
            repository.refreshYahooCandlesIfStale(Symbols.SP500_BENCHMARK, ChartRange.ONE_YEAR)
            benchmarkCandles = repository.observeCandles(Symbols.SP500_BENCHMARK, ChartRange.ONE_YEAR).first()
        } catch (e: Exception) {
            // Sin benchmark no se puede calcular fuerza relativa; se sigue intentando
            // con los sectores por si el fallo era puntual, pero el resultado saldrá vacío.
        }

        val candlesBySymbol = coroutineScope {
            Symbols.SECTOR_ETFS.keys.map { symbol ->
                async {
                    try {
                        repository.refreshYahooCandlesIfStale(symbol, ChartRange.ONE_YEAR)
                        symbol to repository.observeCandles(symbol, ChartRange.ONE_YEAR).first()
                    } catch (e: Exception) {
                        // Un fallo puntual en un sector no debe tumbar el resto del ranking
                        null
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }

        // La fuerza relativa (el dato principal) ya puede pintarse ahora mismo —
        // no hace falta esperar al PER para quitar el "cargando".
        _sectorPerformance.value = if (benchmarkCandles.isEmpty()) {
            emptyList()
        } else {
            SectorRotationCalculator.calculate(candlesBySymbol, Symbols.SECTOR_ETFS, benchmarkCandles)
        }
        _isSectorRotationLoading.value = false

        // PER medio de cada sector: 13 peticiones a Twelve Data, UNA A UNA, cada
        // una esperando su turno en el límite de créditos del plan gratuito
        // (7 créditos/min) — puede tardar 1-2 minutos en total. Antes esto estaba
        // ANTES de quitar "isSectorRotationLoading", así que la rotación entera se
        // quedaba con el spinner puesto todo ese tiempo (parecía colgada). Ahora
        // corre EN PARALELO, sin bloquear nada: la fuerza relativa ya está en
        // pantalla, y el PER de cada sector aparece solo cuando vaya llegando.
        viewModelScope.launch {
            val sectorPe = try {
                repository.refreshSectorPeRatiosIfStale()
                repository.observeSectorPeRatios().first()
            } catch (e: Exception) {
                emptyMap()
            }
            if (sectorPe.isNotEmpty()) {
                _sectorPerformance.value = _sectorPerformance.value.map { it.copy(averagePE = sectorPe[it.etfSymbol]) }
            }
        }
    }

    fun selectRotationHorizon(horizon: RotationHorizon) {
        _selectedRotationHorizon.value = horizon
    }

    /** Abre la pantalla de detalle (gráfica 1S/1M/1A) del oro, bitcoin o ethereum. */
    fun selectGold() {
        _selectedAsset.value = SelectedAsset(Symbols.GOLD, "Oro")
    }

    fun selectBitcoin() {
        _selectedAsset.value = SelectedAsset(Symbols.BITCOIN_CHART, "Bitcoin")
    }

    fun selectEthereum() {
        _selectedAsset.value = SelectedAsset(Symbols.ETHEREUM_CHART, "Ethereum")
    }

    /** Pedido expresamente así: al tocar la tarjeta del VIX en el Panel, abre su gráfico de
     *  1 año con SMA50 (StockDetailScreen ya la muestra visible por defecto en ese rango). */
    fun selectVix() {
        _selectedAsset.value = SelectedAsset(Symbols.VIX, "VIX", showVolume = false)
    }

    /** Igual que selectVix() — pedido expresamente así, sin volumen tampoco. */
    fun selectCorrelation() {
        _selectedAsset.value = SelectedAsset(Symbols.CORRELATION_INDEX, "Correlación (S&P 500)", showVolume = false)
    }

    fun clearAssetSelection() {
        _selectedAsset.value = null
    }

    fun toggleDisplayCurrency() {
        CurrencyPreferenceStore.toggle()
    }

    class Factory(
        private val repository: MarketRepository,
        private val marketMoversRepository: MarketMoversRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DashboardViewModel(repository, marketMoversRepository) as T
        }
    }
}
