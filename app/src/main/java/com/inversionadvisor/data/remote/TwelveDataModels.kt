package com.inversionadvisor.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * DTOs que mapean 1:1 la respuesta JSON de la API de Twelve Data.
 * https://twelvedata.com/docs
 */

@JsonClass(generateAdapter = true)
data class TwelveDataStatisticsDto(
    val statistics: TwelveDataStatisticsInnerDto?,
    // Igual que QuoteDto: Twelve Data devuelve "status"/"code" cuando hay error en vez de HTTP 4xx/5xx.
    val status: String? = null,
    val code: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class TwelveDataStatisticsInnerDto(
    @Json(name = "valuations_metrics") val valuationsMetrics: TwelveDataValuationsMetricsDto?
)

@JsonClass(generateAdapter = true)
data class TwelveDataValuationsMetricsDto(
    @Json(name = "trailing_pe") val trailingPe: Double?
)

@JsonClass(generateAdapter = true)
data class QuoteDto(
    val symbol: String? = null,
    val name: String?,
    val exchange: String?,
    val currency: String?,
    val open: String?,
    val high: String?,
    val low: String?,
    val close: String?,
    val volume: String?,
    @Json(name = "previous_close") val previousClose: String?,
    @Json(name = "change") val change: String?,
    @Json(name = "percent_change") val percentChange: String?,
    @Json(name = "average_volume") val averageVolume: String?,
    @Json(name = "fifty_two_week") val fiftyTwoWeek: FiftyTwoWeekDto?,
    // Twelve Data devuelve un campo "status" o "code" cuando hay error
    val status: String? = null,
    val code: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class FiftyTwoWeekDto(
    val low: String?,
    val high: String?,
    @Json(name = "low_change") val lowChange: String?,
    @Json(name = "high_change") val highChange: String?,
    @Json(name = "low_change_percent") val lowChangePercent: String?,
    @Json(name = "high_change_percent") val highChangePercent: String?,
    val range: String?
)

@JsonClass(generateAdapter = true)
data class TimeSeriesResponseDto(
    val meta: TimeSeriesMetaDto?,
    val values: List<CandleDto>?,
    val status: String? = null,
    val code: Int? = null,
    val message: String? = null
)

@JsonClass(generateAdapter = true)
data class TimeSeriesMetaDto(
    val symbol: String,
    val interval: String,
    val currency: String?,
    @Json(name = "exchange_timezone") val exchangeTimezone: String?,
    val exchange: String?,
    val type: String?
)

@JsonClass(generateAdapter = true)
data class CandleDto(
    val datetime: String,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String?
)
