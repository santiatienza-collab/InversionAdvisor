package com.inversionadvisor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inversionadvisor.data.local.entities.IndexUniverseEntryEntity
import com.inversionadvisor.data.local.entities.IndexUniverseMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface StockUniverseDao {

    @Query("DELETE FROM index_universe_entry WHERE indexName = :indexName")
    suspend fun clearIndex(indexName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<IndexUniverseEntryEntity>)

    @Query("SELECT * FROM index_universe_entry")
    suspend fun getAllOnce(): List<IndexUniverseEntryEntity>

    // Prioridad FIJA por fiabilidad de la fuente, no por fecha de refresco (el intento anterior,
    // "el índice más reciente", tenía un fallo real: si el Nasdaq-100 tenía guardada una fila
    // antigua con un sector mal calculado (de antes de corregir SimpleSectorMapper, o de cuando
    // el S&P 500 aún no se había analizado nunca) y esa fila resultaba tener fecha MÁS reciente
    // que la del S&P 500, se prefería aunque fuera peor dato — exactamente el caso real que
    // describiste con Adobe). Ahora: S&P 500 siempre gana si el símbolo está ahí (clasificación
    // GICS real de Wikipedia), luego IBEX 35 (también trae una etiqueta de sector real de su
    // propia tabla), y el Nasdaq-100 el último recurso (depende de adivinar por el nombre de la
    // empresa cuando no coincide con el S&P 500 — ver correctNasdaq100SectorsFromSp500).
    @Query("""
        SELECT * FROM index_universe_entry
        WHERE symbol = :symbol
        ORDER BY CASE indexName
            WHEN 'SP500' THEN 1
            WHEN 'IBEX35' THEN 2
            WHEN 'NASDAQ100' THEN 3
            ELSE 4
        END ASC
        LIMIT 1
    """)
    suspend fun getBySymbolOnce(symbol: String): IndexUniverseEntryEntity?

    @Query("SELECT * FROM index_universe_entry WHERE indexName = :indexName")
    suspend fun getByIndexOnce(indexName: String): List<IndexUniverseEntryEntity>

    @Query("SELECT * FROM index_universe_entry")
    fun observeAll(): Flow<List<IndexUniverseEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: IndexUniverseMetaEntity)

    @Query("SELECT * FROM index_universe_meta")
    fun observeMeta(): Flow<List<IndexUniverseMetaEntity>>

    @Query("SELECT * FROM index_universe_meta WHERE indexName = :indexName")
    suspend fun getMetaOnce(indexName: String): IndexUniverseMetaEntity?
}
