package com.inversionadvisor.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Cliente del endpoint de gráficos no oficial de Yahoo Finance, usado aquí
 * únicamente para el volumen diario del S&P 500 (símbolo ^GSPC) y calcular
 * el volumen relativo del mercado. Sin API key, sin límite documentado, pero
 * tampoco con soporte oficial de Yahoo.
 */
interface YahooFinanceApi {

    @GET("v8/finance/chart/{symbol}")
    suspend fun getChart(
        @Path("symbol") symbol: String,
        @Query("range") range: String,
        @Query("interval") interval: String,
        // Añadido — sin esto, Yahoo puede NO incluir el bloque "adjclose" (cierre ajustado por
        // splits/dividendos) en la respuesta, dejando solo "close" (sin ajustar). Es
        // exactamente lo que parece estar pasando con el split de Monster Beverage: el arreglo
        // de código para preferir adjclose no sirve de nada si adjclose ni siquiera viene en la
        // respuesta. "div,splits" fuerza a Yahoo a incluir esos eventos y el cierre ajustado.
        @Query("events") events: String = "div,splits",
        // AÑADIDO — horario extendido (pre-market/after-hours) del gráfico 1D, pedido
        // expresamente así. false por defecto (preserva el comportamiento de siempre en el
        // resto de rangos/usos); MarketRepository.fetchCandlesOnce lo pone a true SOLO para
        // ChartRange.ONE_DAY.
        @Query("includePrePost") includePrePost: Boolean = false
    ): YahooChartResponseDto
}
