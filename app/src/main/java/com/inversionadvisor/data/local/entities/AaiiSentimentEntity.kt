package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Fila única (id fijo = 0) que se sobreescribe en cada refresco. No hace
 * falta guardar histórico local como tabla propia: spreadHistoryCsv guarda
 * ya el spread de las últimas semanas (separado por comas, orden
 * cronológico) solo para pintar el sparkline — sale de la misma página que
 * ya se scrapea para el dato actual.
 */
@Entity(tableName = "aaii_sentiment")
data class AaiiSentimentEntity(
    @PrimaryKey val id: Int = 0,
    val reportedDateLabel: String,
    val bullishPercent: Double,
    val neutralPercent: Double,
    val bearishPercent: Double,
    val spreadHistoryCsv: String,
    val updatedAtEpochMillis: Long
)
