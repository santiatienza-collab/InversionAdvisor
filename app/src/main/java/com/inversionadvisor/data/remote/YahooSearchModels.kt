package com.inversionadvisor.data.remote

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class YahooSearchResponseDto(
    val quotes: List<YahooSearchQuoteDto>?
)

@JsonClass(generateAdapter = true)
data class YahooSearchQuoteDto(
    val symbol: String?,
    val shortname: String?,
    val longname: String?,
    val exchange: String?,
    val quoteType: String?
)
