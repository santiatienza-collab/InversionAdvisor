package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Últimos N precios de cierre (7 días, vía CoinGecko) para pintar un
 * sparkline ilustrativo junto a la cotización de Bitcoin/Ethereum.
 * pricesCsv guarda los valores separados por coma en vez de crear una
 * tabla aparte por precio: es un dato puramente ilustrativo, no hace falta
 * consultarlo punto a punto.
 */
@Entity(tableName = "crypto_sparkline")
data class CryptoSparklineEntity(
    @PrimaryKey val symbol: String,
    val pricesCsv: String,
    val updatedAtEpochMillis: Long
)
