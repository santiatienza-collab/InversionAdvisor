package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Variación diaria de un stock del universo (S&P 500 / Nasdaq-100 / IBEX 35),
 * usada para el banner rotatorio de top ganadores/perdedores del panel
 * principal. La tabla se vacía y se rellena entera en cada refresco (ver
 * MarketMoversRepository.refreshIfStale), no se acumula histórico.
 */
@Entity(tableName = "market_movers")
data class MarketMoverEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val changePercent: Double,
    val price: Double,
    val updatedAtEpochMillis: Long
)

/** Marca de tiempo del último refresco COMPLETO del universo para el banner (fila única, id=0). */
@Entity(tableName = "market_movers_meta")
data class MarketMoversMetaEntity(
    @PrimaryKey val id: Int = 0,
    val updatedAtEpochMillis: Long
)
