package com.inversionadvisor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inversionadvisor.data.local.entities.BuyOpportunityEntity
import com.inversionadvisor.data.local.entities.ScreenerRunMetaEntity
import com.inversionadvisor.data.local.entities.UptrendCandidateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ScreenerDao {

    @Query("DELETE FROM screener_uptrend WHERE indexName = :indexName")
    suspend fun clearUptrendCandidates(indexName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUptrendCandidates(items: List<UptrendCandidateEntity>)

    @Query("SELECT * FROM screener_uptrend WHERE indexName = :indexName ORDER BY trendQuality DESC, yearChangePercent DESC")
    fun observeUptrendCandidates(indexName: String): Flow<List<UptrendCandidateEntity>>

    /** Los 3 mercados juntos — para "Consejos de inversión", que compara entre S&P 500/Nasdaq-100/IBEX 35 a la vez, no dentro de una sola pestaña. */
    @Query("SELECT * FROM screener_uptrend")
    suspend fun getAllUptrendCandidatesOnce(): List<UptrendCandidateEntity>

    @Query("DELETE FROM screener_buy_opportunity WHERE indexName = :indexName")
    suspend fun clearBuyOpportunities(indexName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBuyOpportunities(items: List<BuyOpportunityEntity>)

    @Query("SELECT * FROM screener_buy_opportunity WHERE indexName = :indexName ORDER BY confidenceScore DESC")
    fun observeBuyOpportunities(indexName: String): Flow<List<BuyOpportunityEntity>>

    /** Igual que getAllUptrendCandidatesOnce pero para "Futuras compras". */
    @Query("SELECT * FROM screener_buy_opportunity")
    suspend fun getAllBuyOpportunitiesOnce(): List<BuyOpportunityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRunMeta(meta: ScreenerRunMetaEntity)

    @Query("SELECT * FROM screener_run_meta WHERE indexName = :indexName")
    fun observeRunMeta(indexName: String): Flow<ScreenerRunMetaEntity?>

    // ---- Tercer cajón de candidatos para Top10 (por puntuación, ver Top10GenericCandidateEntity) ----

    @Query("DELETE FROM screener_top10_generic WHERE indexName = :indexName")
    suspend fun clearTop10GenericCandidates(indexName: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTop10GenericCandidates(items: List<com.inversionadvisor.data.local.entities.Top10GenericCandidateEntity>)

    /** Los 3 mercados juntos — mismo patrón que getAllUptrendCandidatesOnce/getAllBuyOpportunitiesOnce, para que Top10Repository los combine. */
    @Query("SELECT * FROM screener_top10_generic")
    suspend fun getAllTop10GenericCandidatesOnce(): List<com.inversionadvisor.data.local.entities.Top10GenericCandidateEntity>
}
