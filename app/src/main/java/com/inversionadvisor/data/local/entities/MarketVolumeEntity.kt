package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Volumen de mercado (por símbolo, normalmente ^GSPC) calculado a partir del
 * histórico de volúmenes diarios de Yahoo Finance: volumen de hoy frente a
 * la media de los días anteriores.
 */
@Entity(tableName = "market_volume")
data class MarketVolumeEntity(
    @PrimaryKey val symbol: String,
    val relativeVolume: Double,
    val todayVolume: Long,
    val averageVolume: Long,
    val updatedAtEpochMillis: Long
)
