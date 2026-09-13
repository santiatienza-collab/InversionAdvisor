package com.inversionadvisor.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs para el endpoint NO OFICIAL que usa la propia web de CNN
 * (edition.cnn.com/markets/fear-and-greed) para pintar su gráfico. No hay
 * documentación pública de CNN para esto — es el mismo endpoint que usa la
 * comunidad de Python para scrapear el índice. Puede cambiar sin aviso.
 */

@JsonClass(generateAdapter = true)
data class CnnFearGreedResponseDto(
    @Json(name = "fear_and_greed") val fearAndGreed: CnnFearGreedCurrentDto?,
    // Sub-indicador de ratio put/call: uno de los 7 componentes que ya trae este
    // mismo endpoint (reutilizado aquí para el indicador de sentimiento alcista/
    // bajista, en sustitución de AAII — ver comentario en
    // MarketRepository.refreshAaiiSentiment). Mismo formato 0-100 que el índice
    // principal: alto = mucha compra de "calls" (codicia/optimismo), bajo = mucha
    // compra de "puts" (miedo/pesimismo).
    @Json(name = "put_call_options") val putCallOptions: CnnFearGreedCurrentDto?
)

@JsonClass(generateAdapter = true)
data class CnnFearGreedCurrentDto(
    val score: Double?,
    val rating: String?,
    val timestamp: String?,
    @Json(name = "previous_close") val previousClose: Double?,
    @Json(name = "previous_1_week") val previousOneWeek: Double?,
    @Json(name = "previous_1_month") val previousOneMonth: Double?,
    @Json(name = "previous_1_year") val previousOneYear: Double?
)
