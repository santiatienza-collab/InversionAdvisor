package com.inversionadvisor.ui.screener

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inversionadvisor.data.repository.FavoritesRepository
import com.inversionadvisor.data.repository.ScreenerRepository
import com.inversionadvisor.data.repository.StockUniverseRepository
import com.inversionadvisor.data.repository.Top10Repository
import com.inversionadvisor.domain.model.BuyOpportunity
import com.inversionadvisor.domain.model.MarketUniverse
import com.inversionadvisor.domain.model.ScreenerRunMeta
import com.inversionadvisor.domain.model.StockIndexMeta
import com.inversionadvisor.domain.model.StockUniverseEntry
import com.inversionadvisor.domain.model.Top10Entry
import com.inversionadvisor.domain.model.UptrendCandidate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Subpestañas dentro de cada mercado de acciones (S&P 500/Nasdaq-100/IBEX 35) — pedidas
 *  expresamente para separar lo que antes iba todo junto en una sola página larga. */
enum class AnalysisSubTab(val displayName: String) {
    INDICE("Índice"),
    // RENOMBRADA a petición expresa: "Tendencia alcista" → "Valores Alcistas".
    VALORES_ALCISTAS("Valores Alcistas")
    // QUITADA a petición expresa (de momento): "Futuras compras" — al cambiar el criterio de
    // "Futuras compras" a "mayor puntuación", se solapaba casi del todo con Valores Alcistas, así
    // que se retira la pestaña por ahora. El resto de la lógica (BuyOpportunity, ExhaustionDetector,
    // el flujo buyOpportunities de ScreenerRepository) se deja INTACTA, sin tocar — solo se
    // oculta esta pestaña concreta, fácil de volver a añadir si hace falta más adelante.
}

data class ScreenerUiState(
    val selectedMarket: MarketUniverse = MarketUniverse.SP500,
    /** Subpestaña activa dentro del mercado actual — pedida expresamente, ver AnalysisSubTab.
     *  Compartida entre los 3 mercados de acciones (no se resetea a "Índice" al cambiar de
     *  S&P 500 a Nasdaq-100, por ejemplo — si estabas viendo "Futuras compras", sigues ahí). */
    val selectedAnalysisSubTab: AnalysisSubTab = AnalysisSubTab.INDICE,
    val uptrendCandidates: List<UptrendCandidate> = emptyList(),
    val buyOpportunities: List<BuyOpportunity> = emptyList(),
    val lastRun: ScreenerRunMeta? = null,
    val indexMeta: List<StockIndexMeta> = emptyList(),
    val isRunning: Boolean = false,
    val progressDone: Int = 0,
    val progressTotal: Int = 0,
    val error: String? = null,
    /** Símbolo actualmente abierto en la pantalla de detalle (gráfica + SMA + volumen), o null si estamos en la lista. */
    val selectedSymbol: String? = null,
    /** Símbolos guardados en favoritos — se lee de forma reactiva (a través de collectAsState) para
     *  que la estrellita de la ficha de detalle se actualice al momento al tocarla, sin tener que
     *  volver atrás y reentrar para que se refresque. */
    val favoriteSymbols: Set<String> = emptySet(),
    /** Cuántas filas mostrar en "Tendencia alcista"/"Futuras compras" para el mercado ACTUAL —
     *  vive aquí (no en un remember{} de la pantalla) para que sobreviva a cambiar de pestaña
     *  (Panel/Busca) y volver a Análisis, en vez de resetear siempre a los 5 primeros. */
    val uptrendVisibleCount: Int = COLLAPSED_ITEM_COUNT,
    val opportunitiesVisibleCount: Int = COLLAPSED_ITEM_COUNT,
    /** Punto de scroll exacto (índice + desplazamiento en píxeles del primer elemento visible)
     *  de la lista de Análisis del mercado ACTUAL — para que, al entrar en la gráfica de un
     *  stock o cambiar a otra pestaña (Panel/Busca) y volver, la lista vuelva a aparecer
     *  exactamente donde estaba, sin tener que volver a hacer scroll. */
    val scrollIndex: Int = 0,
    val scrollOffset: Int = 0,
    /** "Top 10" — se calcula solo al pulsar el botón, nunca solo, y solo con lo que ya haya en
     *  Room de haber pulsado "Analizar" antes en los 3 mercados (ver Top10Repository). */
    val top10Entries: List<Top10Entry> = emptyList(),
    val isCalculatingTop10: Boolean = false,
    val top10ProgressDone: Int = 0,
    val top10ProgressTotal: Int = 0,
    val top10Error: String? = null,
    val top10LastUpdatedAt: Long? = null
)

/** Cuántas filas se muestran de entrada en las listas de Análisis, antes de "Ver más". */
const val COLLAPSED_ITEM_COUNT = 5

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenerViewModel(
    private val repository: ScreenerRepository,
    /** null = sin estrellita de favoritos en la ficha de detalle (compatibilidad hacia atrás si algún día se instancia sin estas dependencias). */
    private val favoritesRepository: FavoritesRepository? = null,
    private val stockUniverseRepository: StockUniverseRepository? = null,
    /** null = pestaña "Top 10" deshabilitada (compatibilidad hacia atrás). */
    private val top10Repository: Top10Repository? = null
) : ViewModel() {

    private val _isRunning = MutableStateFlow(false)
    private val _progress = MutableStateFlow(0 to 0)
    private val _error = MutableStateFlow<String?>(null)
    private val _selectedMarket = MutableStateFlow(MarketUniverse.SP500)
    private val _selectedAnalysisSubTab = MutableStateFlow(AnalysisSubTab.INDICE)
    private val _selectedSymbol = MutableStateFlow<String?>(null)
    private val _isCalculatingTop10 = MutableStateFlow(false)
    private val _top10Progress = MutableStateFlow(0 to 0)
    private val _top10Error = MutableStateFlow<String?>(null)
    private val _top10LastUpdatedAt = MutableStateFlow<Long?>(null)
    // Un contador por mercado (no uno global) — cada pestaña de mercado mantiene su propio
    // "punto de lectura" independiente de las demás.
    private val _uptrendVisibleCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    private val _opportunitiesVisibleCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    // Igual, para el punto de scroll exacto — un (índice, desplazamiento) por mercado.
    private val _scrollPositions = MutableStateFlow<Map<String, Pair<Int, Int>>>(emptyMap())

    // Se mantiene aparte (Eagerly) para que isFavorite()/toggleFavoriteBySymbol()
    // siempre lean el valor más reciente sin depender de qué más esté suscrito al
    // uiState en ese momento — mismo patrón que ya usa SearchViewModel.
    private val favoritesState: StateFlow<List<StockUniverseEntry>> =
        (favoritesRepository?.observeFavorites() ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** kotlinx.coroutines.combine() solo tiene sobrecarga tipada hasta 5 flows,
     *  así que se agrupa primero el estado de ejecución. */
    private data class RunStatus(val isRunning: Boolean, val progress: Pair<Int, Int>, val error: String?)

    private val runStatusFlow = combine(_isRunning, _progress, _error) { running, progress, error ->
        RunStatus(running, progress, error)
    }

    /** Resultados del mercado actualmente seleccionado (pestaña), re-suscritos cada vez que cambia. */
    private data class MarketResults(
        val uptrends: List<UptrendCandidate>,
        val opportunities: List<BuyOpportunity>,
        val runMeta: ScreenerRunMeta?
    )

    private val marketResultsFlow = _selectedMarket.flatMapLatest { market ->
        combine(
            // sample(300), no debounce: durante "Analizar", Room puede emitir de forma casi
            // continua (cada símbolo que termina se guarda al momento) — debounce esperaría a
            // un hueco de silencio para emitir, y con inserts casi seguidos eso significaría NO
            // ver nada en vivo hasta que terminase todo el escaneo (justo lo contrario de lo que
            // se quiere). sample() emite el último valor cada 300ms sí o sí, así se sigue viendo
            // la lista crecer en vivo, en pasos suaves, sin recalcular en cada insert individual.
            repository.observeUptrendCandidates(market.indexName).sample(300),
            repository.observeBuyOpportunities(market.indexName).sample(300),
            repository.observeRunMeta(market.indexName)
        ) { uptrends, opportunities, runMeta ->
            // El ordenado (con la fórmula unificada, que hace bastantes cálculos de texto) vive
            // AQUÍ, no en el combine grande de más abajo — este flujo SOLO se recalcula cuando
            // cambian los datos de Room de verdad (o se cambia de mercado), no en cada píxel de
            // scroll. Antes estaba en el combine grande (11 entradas, una de ellas la posición
            // de scroll, que cambia constantemente) y volvía a ordenar TODA la lista con la
            // fórmula nueva en cada tick de scroll — eso era la causa real de los "trompicones".
            val sortedOpportunities = opportunities.sortedByDescending { it.unifiedScore()?.combinedScore ?: 0.0 }
            MarketResults(uptrends, sortedOpportunities, runMeta)
        }
    }

    private val top10EntriesState: StateFlow<List<Top10Entry>> =
        (top10Repository?.observeTop10() ?: flowOf(emptyList()))
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Igual: se agrupa el estado del Top 10 (4 flows) para no pasar de la sobrecarga tipada de 5. */
    private data class Top10Status(val isCalculating: Boolean, val progress: Pair<Int, Int>, val error: String?, val lastUpdatedAt: Long?)

    private val top10StatusFlow = combine(
        _isCalculatingTop10, _top10Progress, _top10Error, _top10LastUpdatedAt
    ) { isCalculating, progress, error, lastUpdatedAt -> Top10Status(isCalculating, progress, error, lastUpdatedAt) }

    val uiState: StateFlow<ScreenerUiState> = combine(
        _selectedMarket,
        marketResultsFlow,
        repository.observeIndexMeta(),
        runStatusFlow,
        _selectedSymbol,
        favoritesState,
        _uptrendVisibleCounts,
        _opportunitiesVisibleCounts,
        _scrollPositions,
        top10EntriesState,
        top10StatusFlow,
        _selectedAnalysisSubTab
    ) { values ->
        val market = values[0] as MarketUniverse
        val results = values[1] as MarketResults
        val indexMeta = values[2] as List<StockIndexMeta>
        val status = values[3] as RunStatus
        val selectedSymbol = values[4] as String?
        val favorites = values[5] as List<StockUniverseEntry>
        val uptrendVisibleCounts = values[6] as Map<String, Int>
        val opportunitiesVisibleCounts = values[7] as Map<String, Int>
        val scrollPositions = values[8] as Map<String, Pair<Int, Int>>
        val top10Entries = values[9] as List<Top10Entry>
        val top10Status = values[10] as Top10Status
        val selectedAnalysisSubTab = values[11] as AnalysisSubTab
        val scrollPosition = scrollPositions[market.indexName] ?: (0 to 0)
        ScreenerUiState(
            selectedMarket = market,
            selectedAnalysisSubTab = selectedAnalysisSubTab,
            uptrendCandidates = results.uptrends.sortedWith(
                compareByDescending<UptrendCandidate> { it.trendQuality }.thenByDescending { it.yearChangePercent }
            ),
            // Ya viene ordenado por la fórmula unificada desde marketResultsFlow — no se
            // reordena aquí (ver el comentario en ese flujo para el porqué).
            buyOpportunities = results.opportunities,
            lastRun = results.runMeta,
            indexMeta = indexMeta,
            isRunning = status.isRunning,
            progressDone = status.progress.first,
            progressTotal = status.progress.second,
            error = status.error,
            selectedSymbol = selectedSymbol,
            favoriteSymbols = favorites.map { it.symbol }.toSet(),
            uptrendVisibleCount = uptrendVisibleCounts[market.indexName] ?: COLLAPSED_ITEM_COUNT,
            opportunitiesVisibleCount = opportunitiesVisibleCounts[market.indexName] ?: COLLAPSED_ITEM_COUNT,
            scrollIndex = scrollPosition.first,
            scrollOffset = scrollPosition.second,
            top10Entries = top10Entries,
            isCalculatingTop10 = top10Status.isCalculating,
            top10ProgressDone = top10Status.progress.first,
            top10ProgressTotal = top10Status.progress.second,
            top10Error = top10Status.error,
            top10LastUpdatedAt = top10Status.lastUpdatedAt
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ScreenerUiState()
    )

    init {
        viewModelScope.launch {
            _top10LastUpdatedAt.value = top10Repository?.getLastUpdatedAt()
        }
    }

    fun selectMarket(market: MarketUniverse) {
        _selectedMarket.value = market
    }

    fun selectAnalysisSubTab(tab: AnalysisSubTab) {
        _selectedAnalysisSubTab.value = tab
    }

    /** Abre la pantalla de detalle (gráfica + SMA + volumen) de un stock del screener. */
    fun selectStock(symbol: String) {
        _selectedSymbol.value = symbol
    }

    fun clearStockSelection() {
        _selectedSymbol.value = null
    }

    fun showMoreUptrend(totalAvailable: Int) {
        val market = _selectedMarket.value.indexName
        val current = _uptrendVisibleCounts.value[market] ?: COLLAPSED_ITEM_COUNT
        _uptrendVisibleCounts.value = _uptrendVisibleCounts.value + (market to (current + COLLAPSED_ITEM_COUNT).coerceAtMost(totalAvailable))
    }

    fun collapseUptrend() {
        val market = _selectedMarket.value.indexName
        _uptrendVisibleCounts.value = _uptrendVisibleCounts.value + (market to COLLAPSED_ITEM_COUNT)
    }

    fun showMoreOpportunities(totalAvailable: Int) {
        val market = _selectedMarket.value.indexName
        val current = _opportunitiesVisibleCounts.value[market] ?: COLLAPSED_ITEM_COUNT
        _opportunitiesVisibleCounts.value = _opportunitiesVisibleCounts.value + (market to (current + COLLAPSED_ITEM_COUNT).coerceAtMost(totalAvailable))
    }

    fun collapseOpportunities() {
        val market = _selectedMarket.value.indexName
        _opportunitiesVisibleCounts.value = _opportunitiesVisibleCounts.value + (market to COLLAPSED_ITEM_COUNT)
    }

    /**
     * Se llama continuamente mientras se hace scroll en la lista de Análisis (ver
     * ScreenerScreen: snapshotFlow sobre el LazyListState) — así, cuando se entra en la
     * gráfica de un stock o se cambia a otra pestaña (lo que destruye ese LazyListState),
     * ya queda guardado el último punto exacto, sin depender de capturar un evento de
     * "salida" concreto.
     */
    fun saveScrollPosition(index: Int, offset: Int) {
        val market = _selectedMarket.value.indexName
        _scrollPositions.value = _scrollPositions.value + (market to (index to offset))
    }

    /**
     * Escanea SOLO el mercado de la pestaña activa (~500 símbolos en S&P
     * 500, ~100 en Nasdaq-100, 35 en IBEX 35). El usuario lo dispara con un
     * botón porque, con el rate limit de Yahoo, puede tardar más de un
     * minuto — se muestra progreso en vivo mientras corre, y los resultados
     * van apareciendo en la lista según se van encontrando (ver
     * ScreenerRepository.runFullScreen), no todos de golpe al terminar.
     */
    fun runScreener() {
        if (_isRunning.value) return
        val market = _selectedMarket.value
        viewModelScope.launch {
            _isRunning.value = true
            _error.value = null
            _progress.value = 0 to 0
            try {
                // NUEVO — pedido expresamente: se intenta PRIMERO el JSON ya calculado y
                // publicado por scanner-cli (casi instantáneo) — solo si eso falla (sin
                // conexión, el workflow nunca ha corrido, GITHUB_REPO_PATH sin configurar
                // todavía...) se cae al escaneo en directo de siempre, exactamente como
                // funcionaba antes de esto. El usuario no tiene que elegir nada — mismo botón,
                // mismo comportamiento visible, solo más rápido cuando el JSON está disponible.
                val importado = repository.importFromRemoteJson(market.indexName)
                if (!importado) {
                    repository.runFullScreen(market.indexName) { done, total -> _progress.value = done to total }
                }
            } catch (e: Exception) {
                _error.value = e.message ?: "Error desconocido al analizar el mercado"
            } finally {
                _isRunning.value = false
            }
        }
    }

    /** Para la estrellita de favoritos en la ficha de detalle abierta desde Análisis. */
    fun isFavorite(symbol: String): Boolean = favoritesState.value.any { it.symbol == symbol }

    /**
     * Igual que en Busca, pero resolviendo la ficha completa (nombre, sector,
     * índice) a partir del símbolo — el screener, a diferencia de Busca, no
     * tiene ya cargado el universo completo en memoria, así que se busca en
     * caché vía stockUniverseRepository.findEntry(). Si el universo nunca se
     * llegó a cargar (nunca se abrió Busca ni Análisis con datos), no hay
     * nada que guardar y la estrellita simplemente no hace nada.
     */
    fun toggleFavoriteBySymbol(symbol: String) {
        val favRepo = favoritesRepository ?: return
        val uniRepo = stockUniverseRepository ?: return
        viewModelScope.launch {
            if (isFavorite(symbol)) {
                favRepo.removeFavorite(symbol)
            } else {
                val entry = uniRepo.findEntry(symbol) ?: return@launch
                favRepo.addFavorite(entry)
            }
        }
    }

    /**
     * Se llama SOLO al pulsar el botón de la pestaña "Top 10" — nunca automáticamente (a
     * diferencia de un intento anterior de una función parecida, que sí se disparaba sola y
     * causaba confusión). Lee lo que ya haya en Room de los 3 mercados; si nunca se ha pulsado
     * "Analizar" en ninguno, el resultado sale vacío, no dispara ningún escaneo por su cuenta.
     */
    fun calculateTop10() {
        val repo = top10Repository ?: return
        if (_isCalculatingTop10.value) return
        viewModelScope.launch {
            _isCalculatingTop10.value = true
            _top10Error.value = null
            _top10Progress.value = 0 to 0
            try {
                repo.refresh { done, total -> _top10Progress.value = done to total }
                _top10LastUpdatedAt.value = repo.getLastUpdatedAt()
            } catch (e: Exception) {
                _top10Error.value = e.message ?: "Error desconocido al calcular el Top 10"
            } finally {
                _isCalculatingTop10.value = false
            }
        }
    }

    class Factory(
        private val repository: ScreenerRepository,
        private val favoritesRepository: FavoritesRepository? = null,
        private val stockUniverseRepository: StockUniverseRepository? = null,
        private val top10Repository: Top10Repository? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return ScreenerViewModel(repository, favoritesRepository, stockUniverseRepository, top10Repository) as T
        }
    }
}
