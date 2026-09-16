package com.inversionadvisor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inversionadvisor.data.local.entities.PosiblesComprasEntryEntity
import kotlinx.coroutines.flow.Flow

/** Mismo patrón exacto que Top10Dao — ver ese fichero. */
@Dao
interface PosiblesComprasDao {

    @Query("SELECT * FROM posibles_compras_entry ORDER BY rank ASC")
    fun observeAll(): Flow<List<PosiblesComprasEntryEntity>>

    @Query("SELECT MIN(updatedAtEpochMillis) FROM posibles_compras_entry")
    suspend fun getOldestUpdatedAtOnce(): Long?

    @Query("DELETE FROM posibles_compras_entry")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<PosiblesComprasEntryEntity>)
}
