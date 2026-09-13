package com.inversionadvisor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inversionadvisor.data.local.entities.Top10EntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface Top10Dao {

    @Query("SELECT * FROM top10_entry ORDER BY rank ASC")
    fun observeAll(): Flow<List<Top10EntryEntity>>

    @Query("SELECT MIN(updatedAtEpochMillis) FROM top10_entry")
    suspend fun getOldestUpdatedAtOnce(): Long?

    @Query("DELETE FROM top10_entry")
    suspend fun clear()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Top10EntryEntity>)
}
