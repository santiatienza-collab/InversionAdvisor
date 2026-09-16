package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/** "Posibles Compras" — NUEVO, pedido expresamente: recupera el espíritu de la antigua pestaña
 *  "Futuras compras", pero como Top20 propio — las 20 mejores oportunidades de compra
 *  combinando los 4 mercados, no solo el que esté seleccionado. Misma forma que
 *  Top10EntryEntity (ver ese fichero para el porqué de cada campo), tabla aparte porque el
 *  CRITERIO DE ENTRADA es distinto (agotamiento + giro al alza, no tendencia alcista
 *  confirmada) — ver PosiblesComprasRepository. */
@Entity(tableName = "posibles_compras_entry")
data class PosiblesComprasEntryEntity(
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
