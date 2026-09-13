package com.inversionadvisor.data.repository

import com.inversionadvisor.data.local.dao.FavoritesDao
import com.inversionadvisor.data.local.entities.FavoriteStockEntity
import com.inversionadvisor.domain.model.StockUniverseEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Stocks guardados en favoritos desde la pestaña "Busca". Independiente del
 * screener: un favorito es simplemente un símbolo que el usuario quiere
 * tener a mano, sin relación con los criterios de "tendencia alcista" o
 * "futuras compras" del screener.
 */
class FavoritesRepository(private val dao: FavoritesDao) {

    fun observeFavorites(): Flow<List<StockUniverseEntry>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun addFavorite(entry: StockUniverseEntry) {
        dao.insert(
            FavoriteStockEntity(
                symbol = entry.symbol,
                name = entry.name,
                indexName = entry.indexName,
                sectorEtf = entry.sectorEtf,
                addedAtEpochMillis = System.currentTimeMillis()
            )
        )
    }

    suspend fun removeFavorite(symbol: String) {
        dao.deleteBySymbol(symbol)
    }
}

private fun FavoriteStockEntity.toDomain(): StockUniverseEntry = StockUniverseEntry(
    symbol = symbol,
    name = name,
    sectorEtf = sectorEtf,
    indexName = indexName
)
