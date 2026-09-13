package com.inversionadvisor.di

import com.inversionadvisor.domain.model.DisplayCurrency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Preferencia de divisa de visualización (EUR/USD), compartida por toda la
 * app en vez de vivir dentro de un ViewModel concreto — así el botón de
 * cambio de divisa afecta por igual al dashboard, al buscador de stocks y a
 * la vista de bitcoin cuando existan, sin duplicar el interruptor.
 *
 * Se aplica SOLO a instrumentos con precio monetario real: acciones, oro y
 * bitcoin. Los índices bursátiles se expresan en puntos, no en dinero, así
 * que nunca se les aplica conversión aunque cambie esta preferencia (ver
 * Quote.closeIn en domain/model/MarketModels.kt).
 */
object CurrencyPreferenceStore {
    private val _displayCurrency = MutableStateFlow(DisplayCurrency.USD)
    val displayCurrency: StateFlow<DisplayCurrency> = _displayCurrency

    fun toggle() {
        _displayCurrency.value = when (_displayCurrency.value) {
            DisplayCurrency.USD -> DisplayCurrency.EUR
            DisplayCurrency.EUR -> DisplayCurrency.USD
        }
    }
}
