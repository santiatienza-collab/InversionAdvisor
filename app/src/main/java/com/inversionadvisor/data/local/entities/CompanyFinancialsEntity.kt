package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caché de los datos de empresa que se muestran en "Momento idóneo para la
 * venta": precio, capitalización bursátil ("Valor Empresa" en la app) e
 * ingresos netos de los 3 últimos años (el más reciente es TTM — últimos 12
 * meses, no necesariamente un año fiscal ya cerrado).
 */
@Entity(tableName = "company_financials")
data class CompanyFinancialsEntity(
    @PrimaryKey val symbol: String,
    val price: Double?,
    val marketCap: Double?,
    /** Año en curso (TTM, últimos 12 meses). */
    val netIncomeCurrentYear: Double?,
    /** Un año antes de netIncomeCurrentYear. */
    val netIncomePreviousYear: Double?,
    /** Dos años antes de netIncomeCurrentYear. */
    val netIncomeTwoYearsAgo: Double?,
    val updatedAtEpochMillis: Long
)
