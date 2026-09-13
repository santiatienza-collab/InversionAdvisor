package com.inversionadvisor.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Una fila de https://www.sec.gov/files/company_tickers.json — mapeo oficial ticker -> CIK
 * (identificador de empresa en EDGAR), publicado por la propia SEC. El JSON completo es un
 * objeto con claves numéricas arbitrarias ("0", "1", "2"...) y estas filas como valor; por eso
 * se pide como Map<String, SecCompanyTickerDto> en vez de una lista.
 */
@JsonClass(generateAdapter = true)
data class SecCompanyTickerDto(
    @Json(name = "cik_str") val cikStr: Long? = null,
    val ticker: String? = null,
    val title: String? = null
)

/** Respuesta de companyconcept — un concepto XBRL (p. ej. "NetIncomeLoss") para una empresa. */
@JsonClass(generateAdapter = true)
data class SecCompanyConceptDto(
    val units: Map<String, List<SecCompanyConceptUnitDto>>? = null
)

/**
 * Un valor reportado de un concepto XBRL — "val" es palabra reservada en Kotlin, de ahí el
 * @Json(name = "val") para mapearlo a "value". [form] identifica el tipo de informe ("10-K" =
 * anual, "10-Q" = trimestral); solo interesan los "10-K" para los 3 años de ingresos netos.
 * [end] es la fecha de cierre del periodo — la clave para deduplicar (una misma cifra anual
 * puede aparecer repetida en varios informes trimestrales que la citan como comparativa).
 */
@JsonClass(generateAdapter = true)
data class SecCompanyConceptUnitDto(
    val end: String? = null,
    @Json(name = "val") val value: Double? = null,
    val form: String? = null,
    val fp: String? = null,
    val fy: Int? = null,
    val filed: String? = null
)
