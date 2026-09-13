package com.inversionadvisor.data.remote

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Url

/**
 * NUEVO — pedido expresamente: lee el JSON que publica scanner-cli (ver ese módulo y
 * .github/workflows/scan.yml) en vez de escanear cientos de stocks en el propio móvil. Los
 * nombres y tipos de campo tienen que coincidir EXACTAMENTE con ScanCandidateJson/ScanResultJson
 * de scanner-cli — si se cambia un campo en un lado, hay que cambiarlo en el otro (no comparten
 * código directamente: el módulo :scanner-cli reutiliza domain/ de la app, pero la app no
 * depende de :scanner-cli al revés, así que este DTO es una copia deliberada, no automática).
 */
interface ScanResultApi {
    // @Url absoluta (no relativa a un baseUrl fijo) — pedido así porque la URL cambia según el
    // mercado (scan-SP500.json, scan-NASDAQ100.json, scan-IBEX35.json) y según dónde publique
    // cada usuario su propio fork/repo.
    @GET
    suspend fun getScanResult(@Url url: String): ScanResultDto
}

@JsonClass(generateAdapter = true)
data class ScanResultDto(
    val generatedAtEpochMillis: Long,
    val indexName: String,
    val totalSymbols: Int,
    val symbolsWithData: Int,
    val candidates: List<ScanCandidateDto>,
    val top10: List<ScanCandidateDto>
)

@JsonClass(generateAdapter = true)
data class ScanCandidateDto(
    val symbol: String,
    val name: String,
    val sectorEtf: String,
    val sectorName: String?,
    val indexName: String,
    val rsi14: Double?,
    val volumeRatio: Double?,
    val isUptrend: Boolean,
    val trendQuality: Double?,
    val yearChangePercent: Double?,
    val aboveTrendSma: Boolean?,
    val isBuyOpportunity: Boolean,
    val exhaustionConfidence: Int?,
    val exhaustionReasons: List<String>?,
    val declinePercentFromRecentHigh: Double?,
    val nextResistanceTarget: Double?,
    val recentHigh: Double?,
    val recentLow: Double?,
    val recentLowDate: String?,
    val recoveryProgressToResistancePercent: Double?,
    val stockPe: Double?,
    val hasNegativeTrailingEarnings: Boolean,
    val sectorAveragePe: Double?,
    val isSectorInFavor: Boolean,
    val hchState: String,
    val doubleTopBottomPattern: String,
    val tripleTopBottomPattern: String,
    val stockVolatilityRatio: Double?,
    val percentFromYearHigh: Double?,
    val nearestSupportPercent: Double?,
    val nearestResistancePercent: Double?,
    val candlestickPattern: String?,
    val marketTrap: String?,
    val macdHistogram: Double?,
    val shortTermBullish: Boolean,
    val declineAccelerating: Boolean,
    val deathCrossDate: String?,
    val goldenCrossDate: String?,
    val momentumPriceDivergence: String,
    val shortTermFlagPattern: String?,
    val longTermFlagPattern: String?,
    val consolidatedBearishMonthsCount: Int?,
    val combinedScore: Double?,
    val rewardScore: Double?,
    val riskScore: Double?,
    val ratingOutOf10: Int?,
    val summary: String?,
    val penaltyWarnings: List<String>?,
    val bonusWarnings: List<String>?
)
