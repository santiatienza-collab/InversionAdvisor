package com.inversionadvisor.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Endpoints de Twelve Data que usa la app.
 * Documentación: https://twelvedata.com/docs
 *
 * Símbolos útiles:
 *  - Stocks: "AAPL", "MSFT", "TSLA"...
 *  - Índices: "SPX" (S&P500), "IXIC" (Nasdaq), "DJI" (Dow Jones), "IBEX" (IBEX35),
 *             "GDAXI" (DAX), "N225" (Nikkei)
 *  - Volatilidad: "VIX"
 *  - Oro: "XAU/USD"
 *  - ETFs sectoriales para rotación: "XLK" (tecnología), "SMH" (semiconductores),
 *    "XLC" (comunicación/medios), "XLE" (energía), "XLF" (banca), "XLI" (industrial),
 *    "ITA" (defensa/armamento), "XLV" (salud), etc.
 *  - Bitcoin: se consulta aparte vía CoinGecko (no necesita API key), ver CoinGeckoApi.kt
 */
interface TwelveDataApi {

    @GET("quote")
    suspend fun getQuote(
        @Query("symbol") symbol: String,
        @Query("apikey") apiKey: String
    ): QuoteDto

    @GET("time_series")
    suspend fun getTimeSeries(
        @Query("symbol") symbol: String,
        @Query("interval") interval: String, // "1min","1day","1week", etc.
        @Query("outputsize") outputSize: Int, // nº de velas a devolver
        @Query("apikey") apiKey: String,
        // Necesario para valores del IBEX 35: Twelve Data no reconoce el sufijo ".MC" de
        // Yahoo, así que para esos símbolos hay que mandar el ticker SIN sufijo (p. ej.
        // "REP") más este código de mercado ("XMAD" = Bolsa de Madrid) por separado.
        @Query("mic_code") micCode: String? = null
    ): TimeSeriesResponseDto

    /** Útil para pedir varios símbolos de golpe (sectores, índices mundiales) separados por coma */
    @GET("quote")
    suspend fun getQuoteBatch(
        @Query("symbol") symbolsCommaSeparated: String,
        @Query("apikey") apiKey: String
    ): Map<String, QuoteDto>

    /**
     * PER (trailing P/E) y otros fundamentales — sustituye a v7/finance/quote de
     * Yahoo, que devolvía 401 (necesita autenticación que esta app no tiene).
     * Un símbolo por llamada (no admite batch como /quote).
     *
     * OJO — coste real sin confirmar: la documentación de Twelve Data no deja
     * claro si este endpoint cuesta 1 crédito (como /quote y /time_series) o
     * más por ser un endpoint de fundamentales; el interceptor de esta app
     * (CreditAwareRateLimitInterceptor) lo trata como 1 crédito por ahora. Si
     * el plan gratuito empieza a dar 429 en las llamadas de PER, es la primera
     * sospecha — revisar el consumo real en el panel de Twelve Data.
     */
    @GET("statistics")
    suspend fun getStatistics(
        @Query("symbol") symbol: String,
        @Query("apikey") apiKey: String,
        @Query("mic_code") micCode: String? = null
    ): TwelveDataStatisticsDto
}
