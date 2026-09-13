package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caché local de la última cotización conocida de un símbolo.
 * Permite mostrar datos al instante mientras se refresca en red,
 * y no gastar cuota de la API en cada apertura de pantalla.
 */
@Entity(tableName = "quotes")
data class QuoteEntity(
    @PrimaryKey val symbol: String,
    val name: String?,
    val close: Double,
    val previousClose: Double,
    val changePercent: Double,
    val volume: Long,
    val averageVolume: Long,
    val fiftyTwoWeekLow: Double?,
    val fiftyTwoWeekHigh: Double?,
    val currency: String?,
    val updatedAtEpochMillis: Long
)
