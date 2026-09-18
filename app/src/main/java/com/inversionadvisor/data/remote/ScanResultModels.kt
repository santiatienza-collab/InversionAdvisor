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
    val candidates: List<ScanCandidateDto> = emptyList(),
    val top10: List<ScanCandidateDto> = emptyList()
)

@JsonClass(generateAdapter = true)
data class FactorDto(val label: String, val grade: String, val valueText: String)

// CORREGIDO — fallo real encontrado: Moshi exige que la CLAVE exista en el JSON incluso para
// campos de tipo nullable, SALVO que tengan un valor por defecto en el constructor — "String?"
// sin "= null" no es lo mismo que opcional. Sin este valor por defecto, si el JSON publicado en
// GitHub no incluye todavía una clave nueva (por ejemplo, porque el workflow no ha vuelto a
// correr desde el último cambio del escáner), el parseo entero de ESE candidato falla, la
// importación completa se cae, y la app cae en silencio al escaneo lento de siempre — el
// síntoma real detectado ("vuelve a ser lenta"). Con "= null" en todos los nullable, un campo
// que falte en un JSON más antiguo simplemente sale sin dato para ESE campo, sin romper nada más.
@JsonClass(generateAdapter = true)
data class ScanCandidateDto(
    val symbol: String,
    val name: String,
    val sectorEtf: String,
    val sectorName: String? = null,
    val indexName: String,
    val rsi14: Double? = null,
    val volumeRatio: Double? = null,
    val dailyVolumeRatio: Double? = null,
    val isUptrend: Boolean = false,
    val trendQuality: Double? = null,
    val yearChangePercent: Double? = null,
    val aboveTrendSma: Boolean? = null,
    val isBuyOpportunity: Boolean = false,
    val exhaustionConfidence: Int? = null,
    val exhaustionReasons: List<String>? = null,
    val declinePercentFromRecentHigh: Double? = null,
    val nextResistanceTarget: Double? = null,
    val recentHigh: Double? = null,
    val recentLow: Double? = null,
    val recentLowDate: String? = null,
    val recoveryProgressToResistancePercent: Double? = null,
    val stockPe: Double? = null,
    val hasNegativeTrailingEarnings: Boolean = false,
    val sectorAveragePe: Double? = null,
    val isSectorInFavor: Boolean = false,
    val hchState: String = "NONE",
    val doubleTopBottomPattern: String = "NONE",
    val tripleTopBottomPattern: String = "NONE",
    val stockVolatilityRatio: Double? = null,
    val percentFromYearHigh: Double? = null,
    val nearestSupportPercent: Double? = null,
    val nearestResistancePercent: Double? = null,
    val candlestickPattern: String? = null,
    val marketTrap: String? = null,
    val macdHistogram: Double? = null,
    val shortTermBullish: Boolean = false,
    val declineAccelerating: Boolean = false,
    val deathCrossDate: String? = null,
    val goldenCrossDate: String? = null,
    val momentumPriceDivergence: String = "NONE",
    val shortTermFlagPattern: String? = null,
    val longTermFlagPattern: String? = null,
    val consolidatedBearishMonthsCount: Int? = null,
    val combinedScore: Double? = null,
    val rewardScore: Double? = null,
    val riskScore: Double? = null,
    val ratingOutOf10: Int? = null,
    val summary: String? = null,
    val penaltyWarnings: List<String>? = null,
    val bonusWarnings: List<String>? = null,
    val factors: List<FactorDto>? = null,
    val rewardBreakdown: List<String>? = null,
    val riskBreakdown: List<String>? = null
)
