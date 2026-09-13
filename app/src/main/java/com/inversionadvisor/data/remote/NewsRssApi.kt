package com.inversionadvisor.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Cliente HTTP genérico para los feeds RSS de mercados de WSJ/FT/NYT/Bloomberg
 * (ver NewsRepository) — mismo patrón que WikipediaIndexApi/InvestingApi: @Url
 * pasa siempre la URL absoluta del feed en cuestión.
 */
interface NewsRssApi {
    @GET
    suspend fun getFeedXml(@Url url: String): ResponseBody
}
