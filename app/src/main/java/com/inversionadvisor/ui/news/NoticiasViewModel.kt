package com.inversionadvisor.ui.news

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.inversionadvisor.data.repository.NewsRepository
import com.inversionadvisor.domain.model.Titular
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class NoticiasUiState(
    val cargando: Boolean = true,
    val titularesPorFuente: Map<String, List<Titular>> = emptyMap()
)

class NoticiasViewModel(
    private val newsRepository: NewsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NoticiasUiState())
    val uiState: StateFlow<NoticiasUiState> = _uiState

    init {
        cargar()
    }

    fun cargar() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(cargando = true)
            val resultado = runCatching { newsRepository.obtenerTodasLasFuentes() }.getOrDefault(emptyMap())
            _uiState.value = NoticiasUiState(cargando = false, titularesPorFuente = resultado)
        }
    }

    class Factory(private val newsRepository: NewsRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return NoticiasViewModel(newsRepository) as T
        }
    }
}
