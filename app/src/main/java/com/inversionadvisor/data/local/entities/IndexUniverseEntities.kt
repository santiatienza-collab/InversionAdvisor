package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un componente de un índice (S&P 500, Nasdaq-100, IBEX 35), obtenido de la
 * tabla de constituyentes de Wikipedia. Se borra e inserta entera por
 * índice en cada refresco (ver StockUniverseRepository), no se acumula.
 */
@Entity(tableName = "index_universe_entry", primaryKeys = ["indexName", "symbol"])
data class IndexUniverseEntryEntity(
    val indexName: String, // "SP500" | "NASDAQ100" | "IBEX35"
    val symbol: String,
    val name: String,
    val sectorEtf: String
)

/** Marca de tiempo del último refresco logrado para cada índice, y cuántos valores trajo. */
@Entity(tableName = "index_universe_meta")
data class IndexUniverseMetaEntity(
    @PrimaryKey val indexName: String,
    val updatedAtEpochMillis: Long,
    val count: Int
)
