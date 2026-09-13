package com.inversionadvisor.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Búsqueda/autocompletar de tickers de Yahoo — misma familia no oficial que
 * v8/finance/chart (que funciona sin autenticación), a diferencia de
 * v7/finance/quote (401, necesita un "crumb" que esta app no tiene). No
 * verificado en vivo si este endpoint concreto también funciona sin login;
 * primera vez que se usa en la app.
 *
 * Se usa para el "Universo Personalizado": buscar CUALQUIER valor del
 * mercado (no solo los que ya están en S&P 500 / Nasdaq-100 / IBEX 35), para
 * poder añadirlo a una lista propia — por ejemplo valores recientes que
 * todavía no están en ningún índice grande (Tempus AI, Pony AI...).
 */
interface YahooSearchApi {
    @GET("v1/finance/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("quotesCount") quotesCount: Int = 15,
        @Query("newsCount") newsCount: Int = 0
    ): YahooSearchResponseDto
}
