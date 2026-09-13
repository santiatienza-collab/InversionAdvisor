package com.inversionadvisor.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inversionadvisor.data.repository.FavoritesRepository
import com.inversionadvisor.data.repository.StockUniverseRepository
import com.inversionadvisor.domain.model.StockUniverseEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    /** Resultados que coinciden con [query] por nombre o ticker, limitados a un puñado para no saturar la lista. */
    val results: List<StockUniverseEntry> = emptyList(),
    val favorites: List<StockUniverseEntry> = emptyList(),
    val isLoadingUniverse: Boolean = true,
    val error: String? = null,
    /** Símbolo actualmente abierto en la pantalla de detalle (gráfica + SMA + volumen + venta), o null si estamos en la lista. */
    val selectedSymbol: String? = null,
    /** Resultados de la búsqueda libre contra Yahoo (todo el mercado, no solo S&P 500/Nasdaq-100/IBEX 35) — se
     *  ofrece automáticamente cuando la búsqueda normal (contra el universo ya cacheado) no encuentra nada, para
     *  valores como Tempus AI o Pony AI, que no pertenecen a ninguno de esos tres índices. */
    val wholeMarketResults: List<StockUniverseEntry> = emptyList(),
    val isSearchingWholeMarket: Boolean = false,
    val wholeMarketSearchError: String? = null,
    /** true una vez que se ha lanzado ya la búsqueda ampliada para el texto actual — para no repetirla sola en cada tecla. */
    val hasSearchedWholeMarket: Boolean = false
)

/**
 * Pestaña "Busca": carga el universo combinado de los 3 mercados (S&P 500 +
 * Nasdaq-100 + IBEX 35) una vez al entrar y filtra en memoria por nombre o
 * ticker según se escribe — no hace falta re-consultar red en cada tecla,
 * el universo entero cabe de sobra en memoria (~1000 símbolos).
 *
 * Para valores que NO están en ninguno de esos 3 mercados (OPVs recientes,
 * small caps...), hay una búsqueda ampliada contra Yahoo
 * (stockUniverseRepository.searchWholeMarket) que sí consulta red — se
 * dispara a mano (botón "Buscar en todo el mercado"), solo cuando la
 * búsqueda local no encuentra nada, no en cada tecla. Guardar uno de esos
 * resultados usa el mismo mecanismo de favoritos que el resto de la app —
 * no hay una lista separada para esto.
 */
class SearchViewModel(
    private val stockUniverseRepository: StockUniverseRepository,
    private val favoritesRepository: FavoritesRepository
) : ViewModel() {

    private var fullUniverse: List<StockUniverseEntry> = emptyList()

    private val _query = MutableStateFlow("")
    private val _results = MutableStateFlow<List<StockUniverseEntry>>(emptyList())
    private val _isLoadingUniverse = MutableStateFlow(true)
    private val _error = MutableStateFlow<String?>(null)
    private val _selectedSymbol = MutableStateFlow<String?>(null)
    private val _wholeMarketResults = MutableStateFlow<List<StockUniverseEntry>>(emptyList())
    private val _isSearchingWholeMarket = MutableStateFlow(false)
    private val _wholeMarketSearchError = MutableStateFlow<String?>(null)
    private val _hasSearchedWholeMarket = MutableStateFlow(false)

    // Se mantiene aparte (Eagerly) para que isFavorite()/toggleFavorite() siempre lean
    // el valor más reciente por .value, sin depender de que uiState tenga un colector activo.
    private val favoritesState: StateFlow<List<StockUniverseEntry>> = favoritesRepository.observeFavorites()
        .stateIn(scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = emptyList())

    /** kotlinx.coroutines.combine() solo tiene sobrecarga tipada hasta 5 flows,
     *  así que se agrupan primero en varios bloques. */
    private data class ResultsState(
        val query: String,
        val results: List<StockUniverseEntry>,
        val favorites: List<StockUniverseEntry>
    )

    private val resultsFlow = combine(
        _query,
        _results,
        favoritesState
    ) { query, results, favorites -> ResultsState(query, results, favorites) }

    private data class StatusState(
        val isLoadingUniverse: Boolean,
        val error: String?,
        val selectedSymbol: String?
    )

    private val statusFlow = combine(
        _isLoadingUniverse,
        _error,
        _selectedSymbol
    ) { isLoadingUniverse, error, selectedSymbol -> StatusState(isLoadingUniverse, error, selectedSymbol) }

    private data class WholeMarketState(
        val results: List<StockUniverseEntry>,
        val isSearching: Boolean,
        val error: String?,
        val hasSearched: Boolean
    )

    private val wholeMarketFlow = combine(
        _wholeMarketResults,
        _isSearchingWholeMarket,
        _wholeMarketSearchError,
        _hasSearchedWholeMarket
    ) { results, isSearching, error, hasSearched -> WholeMarketState(results, isSearching, error, hasSearched) }

    val uiState: StateFlow<SearchUiState> = combine(
        resultsFlow,
        statusFlow,
        wholeMarketFlow
    ) { results, status, wholeMarket ->
        SearchUiState(
            query = results.query,
            results = results.results,
            favorites = results.favorites,
            isLoadingUniverse = status.isLoadingUniverse,
            error = status.error,
            selectedSymbol = status.selectedSymbol,
            wholeMarketResults = wholeMarket.results,
            isSearchingWholeMarket = wholeMarket.isSearching,
            wholeMarketSearchError = wholeMarket.error,
            hasSearchedWholeMarket = wholeMarket.hasSearched
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SearchUiState()
    )

    init {
        loadUniverse()
    }

    private fun loadUniverse() {
        viewModelScope.launch {
            _isLoadingUniverse.value = true
            _error.value = null
            try {
                fullUniverse = stockUniverseRepository.getFullUniverseForSearch()
            } catch (e: Exception) {
                _error.value = e.message ?: "No se pudo cargar el universo de mercados"
            } finally {
                _isLoadingUniverse.value = false
            }
        }
    }

    fun onQueryChange(query: String) {
        _query.value = query
        // Se limpia la búsqueda ampliada anterior al cambiar de texto — sus resultados eran
        // de OTRA búsqueda, no tiene sentido dejarlos pegados mientras se escribe algo nuevo.
        _wholeMarketResults.value = emptyList()
        _wholeMarketSearchError.value = null
        _hasSearchedWholeMarket.value = false
        _results.value = if (query.isBlank()) {
            emptyList()
        } else {
            val needle = query.trim().lowercase()
            fullUniverse
                .filter { it.symbol.lowercase().contains(needle) || it.name.lowercase().contains(needle) }
                .sortedWith(
                    // Coincidencias exactas o al principio del ticker primero (buscar "AAPL" debe
                    // traer Apple antes que cualquier stock cuyo nombre contenga "aapl" por casualidad).
                    compareByDescending<StockUniverseEntry> { it.symbol.equals(query.trim(), ignoreCase = true) }
                        .thenByDescending { it.symbol.lowercase().startsWith(needle) }
                        .thenBy { it.name }
                )
                .take(30)
        }
    }

    /**
     * Búsqueda ampliada contra Yahoo, para valores que NO están en S&P
     * 500/Nasdaq-100/IBEX 35 (OPVs recientes, small caps...) — se dispara a
     * mano (botón), no automáticamente al escribir, para no lanzar una
     * petición de red en cada tecla.
     */
    fun searchWholeMarket() {
        val query = _query.value
        if (query.isBlank()) return
        viewModelScope.launch {
            _isSearchingWholeMarket.value = true
            _wholeMarketSearchError.value = null
            try {
                _wholeMarketResults.value = stockUniverseRepository.searchWholeMarket(query)
            } catch (e: Exception) {
                _wholeMarketSearchError.value = e.message ?: "No se pudo buscar en el mercado completo"
            } finally {
                _isSearchingWholeMarket.value = false
                _hasSearchedWholeMarket.value = true
            }
        }
    }

    fun selectStock(symbol: String) {
        _selectedSymbol.value = symbol
    }

    fun clearStockSelection() {
        _selectedSymbol.value = null
    }

    fun isFavorite(symbol: String): Boolean = favoritesState.value.any { it.symbol == symbol }

    fun toggleFavorite(entry: StockUniverseEntry) {
        viewModelScope.launch {
            if (isFavorite(entry.symbol)) {
                favoritesRepository.removeFavorite(entry.symbol)
            } else {
                favoritesRepository.addFavorite(entry)
            }
        }
    }

    /** Igual que [toggleFavorite] pero resolviendo la ficha completa a partir del símbolo — para la estrellita de la pantalla de detalle, que solo conoce el símbolo abierto. */
    fun toggleFavoriteBySymbol(symbol: String) {
        val entry = fullUniverse.find { it.symbol == symbol }
            ?: favoritesState.value.find { it.symbol == symbol }
            ?: _wholeMarketResults.value.find { it.symbol == symbol }
            ?: return
        toggleFavorite(entry)
    }

    class Factory(
        private val stockUniverseRepository: StockUniverseRepository,
        private val favoritesRepository: FavoritesRepository
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SearchViewModel(stockUniverseRepository, favoritesRepository) as T
        }
    }
}
