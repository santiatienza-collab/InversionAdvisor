package com.inversionadvisor.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Cliente HTTP genérico de "descarga esta página y dame el HTML crudo",
 * usado para las fuentes de constituyentes de índices (Wikipedia para
 * S&P 500/IBEX 35, slickcharts.com para Nasdaq-100 — ver
 * StockUniverseRepository). @Url siempre pasa una URL absoluta, por eso el
 * baseUrl del Retrofit asociado es solo un formalismo.
 */
interface WikipediaIndexApi {
    @GET
    suspend fun getPageHtml(@Url url: String): ResponseBody
}
