package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caché del último valor conocido del Fear & Greed Index de CNN. Solo hay
 * una fila (id fijo) porque es un dato único, no por símbolo.
 *
 * previousClose/OneWeek/OneMonth ya vienen en la misma respuesta de CNN
 * (no son una llamada aparte) — se guardan solo para poder pintar un
 * sparkline ilustrativo junto al valor actual.
 */
@Entity(tableName = "fear_greed")
data class FearGreedEntity(
    @PrimaryKey val id: Int = 0,
    val score: Double,
    val rating: String,
    val previousClose: Double?,
    val previousOneWeek: Double?,
    val previousOneMonth: Double?,
    val updatedAtEpochMillis: Long
)
