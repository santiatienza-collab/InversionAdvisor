package com.inversionadvisor.data.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Caché en memoria del mapeo oficial ticker -> CIK de SEC EDGAR (company_tickers.json, ~800KB)
 * — se pide UNA sola vez por proceso de la app (no cambia con la frecuencia suficiente como
 * para justificar pedirlo en cada símbolo) y se reutiliza para todos los stocks de EE.UU. que se
 * consulten después. Vive mientras vive el proceso — no se persiste a disco (Room) a propósito,
 * para no complicar el esquema de la base de datos por un dato que se puede volver a pedir
 * gratis en el próximo arranque de la app sin coste real.
 */
object SecEdgarTickerCache {
    private val mutex = Mutex()

    @Volatile
    private var cikByTicker: Map<String, Long>? = null

    /** null si el símbolo no está en el mapeo de la SEC (p. ej. IBEX 35, o un ticker que la SEC no reconoce). */
    suspend fun getCik(api: SecEdgarApi, symbol: String): Long? {
        val loaded = cikByTicker ?: mutex.withLock {
            // Doble comprobación — otra corrutina pudo haber terminado de cargarlo mientras
            // esta esperaba el mutex.
            cikByTicker ?: run {
                val fetched = try {
                    api.getCompanyTickers().values.mapNotNull { entry ->
                        val ticker = entry.ticker?.uppercase() ?: return@mapNotNull null
                        val cik = entry.cikStr ?: return@mapNotNull null
                        ticker to cik
                    }.toMap()
                } catch (e: Exception) {
                    android.util.Log.w("CompanyFinancialsFetch", "SEC EDGAR: fallo cargando company_tickers.json: ${e.message ?: e::class.simpleName}")
                    emptyMap()
                }
                cikByTicker = fetched
                fetched
            }
        }
        return loaded[symbol.uppercase()]
    }
}
