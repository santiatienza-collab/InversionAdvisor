package com.inversionadvisor.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Cliente HTTP genérico para Investing.com — respaldo de "Ingresos netos" para
 * el IBEX 35 (.MC), que SEC EDGAR no cubre (ver MarketRepository.fetchNetIncomesFromInvesting).
 * Igual que WikipediaIndexApi, @Url pasa siempre la URL absoluta de la página
 * de resumen financiero de cada empresa (ver IbexInvestingSlugs).
 */
interface InvestingApi {
    @GET
    suspend fun getPageHtml(@Url url: String): ResponseBody
}
