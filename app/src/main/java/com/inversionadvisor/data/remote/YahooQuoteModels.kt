package com.inversionadvisor.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs para v7/finance/quote — endpoint NO OFICIAL distinto del v8/finance/chart que ya usa el
 * resto de la app (ese es solo para velas). Este trae más campos "de ficha" por símbolo,
 * incluida la fecha de la próxima publicación de resultados (earningsTimestamp) — usado
 * específicamente para la penalización "resultados en menos de 3 semanas".
 */

@JsonClass(generateAdapter = true)
data class YahooQuoteResponseDto(
    @Json(name = "quoteResponse") val quoteResponse: YahooQuoteResponseBodyDto?
)

@JsonClass(generateAdapter = true)
data class YahooQuoteResponseBodyDto(
    val result: List<YahooQuoteResultDto>?
)

@JsonClass(generateAdapter = true)
data class YahooQuoteResultDto(
    val symbol: String?,
    // En SEGUNDOS, no milisegundos — así es como lo da Yahoo en este endpoint concreto.
    @Json(name = "earningsTimestamp") val earningsTimestamp: Long?
)
