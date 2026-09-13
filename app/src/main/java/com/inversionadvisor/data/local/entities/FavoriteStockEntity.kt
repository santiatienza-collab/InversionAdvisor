package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Un stock guardado en favoritos desde la pestaña "Busca" (pulsando la
 * estrellita). No se acumula histórico: solo existe una fila por símbolo,
 * se borra al quitarlo de favoritos.
 */
@Entity(tableName = "favorite_stocks")
data class FavoriteStockEntity(
    @PrimaryKey val symbol: String,
    val name: String,
    val indexName: String,
    val sectorEtf: String,
    val addedAtEpochMillis: Long
)
