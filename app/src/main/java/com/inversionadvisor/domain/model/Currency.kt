package com.inversionadvisor.domain.model

/**
 * Divisas nativas que pueden devolver los distintos símbolos (stocks USD,
 * algunos índices en su divisa local, etc.) más las dos divisas de
 * visualización que pidió el usuario: EUR y USD.
 */
enum class Currency(val code: String, val symbol: String) {
    USD("USD", "$"),
    EUR("EUR", "€"),
    GBP("GBP", "£"),
    JPY("JPY", "¥"),
    HKD("HKD", "HK$");

    companion object {
        fun fromCode(code: String?): Currency? = entries.find { it.code.equals(code, ignoreCase = true) }
    }
}

/** Las dos divisas entre las que el usuario puede alternar la visualización de la app. */
enum class DisplayCurrency(val currency: Currency) {
    USD(Currency.USD),
    EUR(Currency.EUR)
}

/**
 * Pares de forex que la app necesita tener frescos para poder convertir
 * cualquier instrumento (stocks/oro/bitcoin en USD, algunos índices en su
 * divisa local) a EUR o USD.
 *
 * Nota importante: los ÍNDICES bursátiles (S&P500, IBEX35, Nikkei...) se
 * expresan en "puntos de índice", no en una cantidad monetaria real —
 * convertir su valor a EUR/USD no tiene sentido financiero, así que la
 * conversión de divisa se aplica solo a instrumentos con precio monetario
 * real: acciones, oro y bitcoin.
 */
object ForexPairs {
    const val EUR_USD = "EURUSD=X"
    const val GBP_USD = "GBPUSD=X"
    const val USD_JPY = "USDJPY=X"
    const val USD_HKD = "USDHKD=X"

    val ALL = listOf(EUR_USD, GBP_USD, USD_JPY, USD_HKD)
}
