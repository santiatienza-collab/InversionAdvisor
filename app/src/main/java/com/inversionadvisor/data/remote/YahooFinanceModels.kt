package com.inversionadvisor.data.remote

import com.squareup.moshi.JsonClass

/**
 * DTOs para el endpoint NO OFICIAL de gráficos de Yahoo Finance
 * (https://query1.finance.yahoo.com/v8/finance/chart/{symbol}). Se usa como
 * fuente principal del dashboard (cotizaciones y velas): sin API key y sin
 * límite de créditos documentado, a cambio de no tener soporte oficial —
 * puede cambiar de forma sin aviso.
 */

@JsonClass(generateAdapter = true)
data class YahooChartResponseDto(
    val chart: YahooChartDto
)

@JsonClass(generateAdapter = true)
data class YahooChartDto(
    val result: List<YahooChartResultDto>?,
    val error: YahooChartErrorDto?
)

@JsonClass(generateAdapter = true)
data class YahooChartErrorDto(
    val code: String?,
    val description: String?
)

@JsonClass(generateAdapter = true)
data class YahooChartResultDto(
    val meta: YahooMetaDto,
    val timestamp: List<Long>?,
    val indicators: YahooIndicatorsDto?
)

@JsonClass(generateAdapter = true)
data class YahooMetaDto(
    val currency: String?,
    val symbol: String,
    val exchangeName: String?,
    val regularMarketPrice: Double?,
    val previousClose: Double?,
    val chartPreviousClose: Double?,
    val regularMarketVolume: Long?,
    val fiftyTwoWeekHigh: Double?,
    val fiftyTwoWeekLow: Double?,
    // AÑADIDO — horario extendido (pre-market/after-hours) del gráfico 1D, pedido expresamente
    // así. Solo viene relleno si la petición incluye includePrePost=true (ver
    // YahooFinanceApi.getChart). Da los límites REALES de la sesión regular para ESTE símbolo y
    // ESTE día concreto (varía por bolsa: Bolsa de Madrid no es lo mismo que NYSE, y dentro de
    // NYSE varía con el horario de verano) — se usa para saber qué velas son "fuera de horario"
    // sin tener que asumir un horario fijo a mano, que fallaría para el IBEX 35 o cualquier
    // símbolo no estadounidense.
    val currentTradingPeriod: YahooCurrentTradingPeriodDto?
)

@JsonClass(generateAdapter = true)
data class YahooCurrentTradingPeriodDto(
    val pre: YahooTradingSessionDto?,
    val regular: YahooTradingSessionDto?,
    val post: YahooTradingSessionDto?
)

@JsonClass(generateAdapter = true)
data class YahooTradingSessionDto(
    val start: Long?, // epoch segundos
    val end: Long?
)

@JsonClass(generateAdapter = true)
data class YahooIndicatorsDto(
    val quote: List<YahooQuoteArraysDto>?,
    /**
     * Serie de cierres AJUSTADOS (por dividendos/splits), en un bloque
     * aparte de `quote` en la respuesta real de Yahoo. Para bolsas no
     * estadounidenses (p. ej. el IBEX 35, sufijo .MC) es habitual que
     * `quote[0].close` venga con huecos en `null` en velas SEMANALES de
     * rangos largos (1A/5A) mientras que `adjclose` sí trae el dato — sin
     * cubrir este caso, esas velas se descartaban y el gráfico se quedaba
     * con muy pocos puntos ("sin datos suficientes"). Se usa como
     * respaldo en toCandleEntities.
     */
    val adjclose: List<YahooAdjCloseArrayDto>?
)

@JsonClass(generateAdapter = true)
data class YahooAdjCloseArrayDto(
    val adjclose: List<Double?>?
)

@JsonClass(generateAdapter = true)
data class YahooQuoteArraysDto(
    val open: List<Double?>?,
    val high: List<Double?>?,
    val low: List<Double?>?,
    val close: List<Double?>?,
    val volume: List<Long?>?
)
