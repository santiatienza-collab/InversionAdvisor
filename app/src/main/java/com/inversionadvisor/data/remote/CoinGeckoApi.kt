package com.inversionadvisor.data.remote

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * CoinGecko no requiere API key en su tier gratuito (límite ~10-30 req/min).
 * https://www.coingecko.com/en/api/documentation
 */
interface CoinGeckoApi {

    @GET("simple/price")
    suspend fun getSimplePrice(
        @Query("ids") ids: String = "bitcoin,ethereum",
        @Query("vs_currencies") vsCurrencies: String = "usd",
        @Query("include_24hr_change") include24hChange: Boolean = true,
        @Query("include_market_cap") includeMarketCap: Boolean = true,
        @Query("include_24hr_vol") include24hVol: Boolean = true
    ): Map<String, CoinPriceDto>

    /** coinId: "bitcoin", "ethereum", etc. (id de CoinGecko, no el ticker). */
    @GET("coins/{coinId}/market_chart")
    suspend fun getMarketChart(
        @Path("coinId") coinId: String,
        @Query("vs_currency") vsCurrency: String = "usd",
        @Query("days") days: String // "1","7","30","365","max"
    ): MarketChartDto
}

@JsonClass(generateAdapter = true)
data class CoinPriceDto(
    val usd: Double?,
    val usd_market_cap: Double?,
    val usd_24h_vol: Double?,
    val usd_24h_change: Double?
)

@JsonClass(generateAdapter = true)
data class MarketChartDto(
    val prices: List<List<Double>>?, // [timestampMillis, price]
    val total_volumes: List<List<Double>>?
)
