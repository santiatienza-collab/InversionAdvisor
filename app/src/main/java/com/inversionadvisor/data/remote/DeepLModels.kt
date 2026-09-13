package com.inversionadvisor.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class DeepLResponseDto(
    val translations: List<DeepLTranslationDto>?
)

@JsonClass(generateAdapter = true)
data class DeepLTranslationDto(
    @Json(name = "detected_source_language") val detectedSourceLanguage: String?,
    val text: String?
)
