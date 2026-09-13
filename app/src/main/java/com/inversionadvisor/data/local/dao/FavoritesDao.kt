package com.inversionadvisor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inversionadvisor.data.local.entities.FavoriteStockEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoritesDao {

    @Query("SELECT * FROM favorite_stocks ORDER BY addedAtEpochMillis DESC")
    fun observeAll(): Flow<List<FavoriteStockEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: FavoriteStockEntity)

    @Query("DELETE FROM favorite_stocks WHERE symbol = :symbol")
    suspend fun deleteBySymbol(symbol: String)
}
