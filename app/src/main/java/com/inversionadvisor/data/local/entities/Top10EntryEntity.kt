package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Top 10 calculado — se recalcula al pulsar el botón, no automáticamente (ver Top10Repository).
 *  factorsCsv: factores serializados como "label|grade|valor;;label|grade|valor;;..." — Room no
 *  guarda listas de objetos directamente, así que se aplana a texto y se reconstruye al leer.
 *  rewardBreakdownText/riskBreakdownText: cada línea del desglose separada por salto de línea.
 *  penaltyWarningsText/bonusWarningsText: igual, una línea por aviso — para los avisos en neón
 *  rojo/verde que ahora también se muestran en el propio panel de Top10, no solo en Futuras
 *  Compras y en la ficha del stock. */
@Entity(tableName = "top10_entry")
data class Top10EntryEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val indexName: String,
    val sectorName: String?,
    val rewardScore: Double,
    val riskScore: Double,
    val combinedScore: Double,
    val analystSummary: String,
    val factorsCsv: String,
    val rewardBreakdownText: String,
    val riskBreakdownText: String,
    val penaltyWarningsText: String = "",
    val bonusWarningsText: String = "",
    val rank: Int,
    val updatedAtEpochMillis: Long
)
