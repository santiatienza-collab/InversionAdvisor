package com.inversionadvisor.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Una fila del income statement anual de Financial Modeling Prep — solo se leen los campos que
 * usa la app (ingresos netos). El endpoint devuelve MUCHOS más campos (ingresos totales, EBITDA,
 * EPS...), no declarados aquí a propósito: Moshi con KotlinJsonAdapterFactory ignora los campos
 * del JSON que no están en la data class, así que no hace falta listarlos todos.
 */
@JsonClass(generateAdapter = true)
data class FmpIncomeStatementDto(
    val date: String? = null,
    val symbol: String? = null,
    @Json(name = "calendarYear") val calendarYear: String? = null,
    val period: String? = null,
    @Json(name = "netIncome") val netIncome: Double? = null
)
