package com.inversionadvisor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inversionadvisor.data.local.entities.MarketMoverEntity
import com.inversionadvisor.data.local.entities.MarketMoversMetaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MarketMoversDao {

    @Query("DELETE FROM market_movers")
    suspend fun clear()

    /** Limpieza de cierre de un escaneo: borra symbols que NO se hayan tocado en este
     *  escaneo (p. ej. porque fallaron o salieron del universo) sin tener que vaciar
     *  la tabla al principio — así el banner nunca se queda vacío mientras dura el escaneo. */
    @Query("DELETE FROM market_movers WHERE updatedAtEpochMillis < :cutoffEpochMillis")
    suspend fun deleteOlderThan(cutoffEpochMillis: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<MarketMoverEntity>)

    /** Solo subidas (>0%), de mayor a menor variación. */
    @Query("SELECT * FROM market_movers WHERE changePercent > 0 ORDER BY changePercent DESC LIMIT :limit")
    fun observeTopGainers(limit: Int): Flow<List<MarketMoverEntity>>

    /** Solo bajadas (<0%), de mayor a menor caída. */
    @Query("SELECT * FROM market_movers WHERE changePercent < 0 ORDER BY changePercent ASC LIMIT :limit")
    fun observeTopLosers(limit: Int): Flow<List<MarketMoverEntity>>

    /** Versiones "de una vez" (no Flow) de las dos de arriba — para el refresco rápido del
     *  banner "en tiempo real" (solo los símbolos que YA están mostrándose), que necesita
     *  saber cuáles son ESOS símbolos concretos antes de pedir sus precios de nuevo. */
    @Query("SELECT * FROM market_movers WHERE changePercent > 0 ORDER BY changePercent DESC LIMIT :limit")
    suspend fun getTopGainersOnce(limit: Int): List<MarketMoverEntity>

    @Query("SELECT * FROM market_movers WHERE changePercent < 0 ORDER BY changePercent ASC LIMIT :limit")
    suspend fun getTopLosersOnce(limit: Int): List<MarketMoverEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(meta: MarketMoversMetaEntity)

    @Query("SELECT * FROM market_movers_meta WHERE id = 0")
    suspend fun getMetaOnce(): MarketMoversMetaEntity?
}
