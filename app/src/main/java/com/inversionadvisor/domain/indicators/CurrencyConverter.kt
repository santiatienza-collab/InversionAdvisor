package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Currency

/**
 * Convierte importes entre divisas usando USD como pivote.
 *
 * @param ratesToUsd mapa "1 unidad de esta divisa = X USD". Ej: EUR -> 1.08
 *                   significa que 1 EUR = 1.08 USD. USD -> 1.0 siempre.
 */
object CurrencyConverter {

    fun convert(
        amount: Double,
        from: Currency,
        to: Currency,
        ratesToUsd: Map<Currency, Double>
    ): Double? {
        if (from == to) return amount
        val fromRate = if (from == Currency.USD) 1.0 else ratesToUsd[from] ?: return null
        val toRate = if (to == Currency.USD) 1.0 else ratesToUsd[to] ?: return null
        val amountInUsd = amount * fromRate
        return amountInUsd / toRate
    }

    /**
     * Construye el mapa de tasas "a USD" a partir de las cotizaciones de forex
     * ya cacheadas (EURUSD=X, GBPUSD=X, USDJPY=X, USDHKD=X...).
     * Para pares invertidos tipo USDJPY=X, la tasa "1 JPY = X USD" es 1 / close.
     */
    fun buildRatesToUsd(rawPairRates: Map<String, Double>): Map<Currency, Double> {
        val rates = mutableMapOf(Currency.USD to 1.0)
        rawPairRates["EURUSD=X"]?.let { rates[Currency.EUR] = it }
        rawPairRates["GBPUSD=X"]?.let { rates[Currency.GBP] = it }
        rawPairRates["USDJPY=X"]?.let { if (it != 0.0) rates[Currency.JPY] = 1.0 / it }
        rawPairRates["USDHKD=X"]?.let { if (it != 0.0) rates[Currency.HKD] = 1.0 / it }
        return rates
    }
}
