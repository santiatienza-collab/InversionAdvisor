package com.inversionadvisor.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.inversionadvisor.data.local.entities.AaiiSentimentEntity
import com.inversionadvisor.data.local.entities.CandleEntity
import com.inversionadvisor.data.local.entities.CandleFetchMetaEntity
import com.inversionadvisor.data.local.entities.CryptoSparklineEntity
import com.inversionadvisor.data.local.entities.EarningsDateEntity
import com.inversionadvisor.data.local.entities.FearGreedEntity
import com.inversionadvisor.data.local.entities.CompanyFinancialsEntity
import com.inversionadvisor.data.local.entities.MarketVolumeEntity
import com.inversionadvisor.data.local.entities.PeRatioEntity
import com.inversionadvisor.data.local.entities.QuoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MarketDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuote(quote: QuoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertQuotes(quotes: List<QuoteEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeRatios(rows: List<PeRatioEntity>)

    @Query("SELECT * FROM pe_ratio WHERE symbol = :symbol")
    suspend fun getPeRatioOnce(symbol: String): PeRatioEntity?

    @Query("SELECT * FROM pe_ratio WHERE symbol IN (:symbols)")
    suspend fun getPeRatiosOnce(symbols: List<String>): List<PeRatioEntity>

    @Query("SELECT * FROM pe_ratio WHERE symbol IN (:symbols)")
    fun observePeRatios(symbols: List<String>): Flow<List<PeRatioEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEarningsDate(row: EarningsDateEntity)

    @Query("SELECT * FROM earnings_date WHERE symbol = :symbol")
    suspend fun getEarningsDateOnce(symbol: String): EarningsDateEntity?

    @Query("SELECT * FROM earnings_date WHERE symbol = :symbol")
    fun observeEarningsDate(symbol: String): Flow<EarningsDateEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCompanyFinancials(row: CompanyFinancialsEntity)

    @Query("SELECT * FROM company_financials WHERE symbol = :symbol")
    suspend fun getCompanyFinancialsOnce(symbol: String): CompanyFinancialsEntity?

    @Query("SELECT * FROM company_financials WHERE symbol = :symbol")
    fun observeCompanyFinancials(symbol: String): Flow<CompanyFinancialsEntity?>

    @Query("SELECT * FROM quotes WHERE symbol = :symbol")
    fun observeQuote(symbol: String): Flow<QuoteEntity?>

    @Query("SELECT * FROM quotes WHERE symbol IN (:symbols)")
    fun observeQuotes(symbols: List<String>): Flow<List<QuoteEntity>>

    /** Consulta puntual (no-Flow), usada solo para comprobar si el dato está desactualizado. */
    @Query("SELECT * FROM quotes WHERE symbol = :symbol")
    suspend fun getQuoteOnce(symbol: String): QuoteEntity?

    @Query("SELECT * FROM quotes WHERE symbol IN (:symbols)")
    suspend fun getQuotesOnce(symbols: List<String>): List<QuoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandles(candles: List<CandleEntity>)

    @Query("SELECT * FROM candles WHERE symbol = :symbol AND interval = :interval ORDER BY datetime ASC")
    fun observeCandles(symbol: String, interval: String): Flow<List<CandleEntity>>

    @Query("DELETE FROM candles WHERE symbol = :symbol AND interval = :interval")
    suspend fun clearCandles(symbol: String, interval: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandleFetchMeta(meta: CandleFetchMetaEntity)

    @Query("SELECT * FROM candle_fetch_meta WHERE `key` = :key")
    suspend fun getCandleFetchMeta(key: String): CandleFetchMetaEntity?

    // AÑADIDO — horario extendido del gráfico 1D: la UI necesita observar los límites de la
    // sesión regular en vivo (no solo leerlos una vez), para que se actualicen si el usuario
    // reabre el gráfico y llega una fecha/sesión distinta.
    @Query("SELECT * FROM candle_fetch_meta WHERE `key` = :key")
    fun observeCandleFetchMeta(key: String): kotlinx.coroutines.flow.Flow<CandleFetchMetaEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFearGreed(entity: FearGreedEntity)

    @Query("SELECT * FROM fear_greed WHERE id = 0")
    fun observeFearGreed(): Flow<FearGreedEntity?>

    @Query("SELECT * FROM fear_greed WHERE id = 0")
    suspend fun getFearGreedOnce(): FearGreedEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMarketVolume(entity: MarketVolumeEntity)

    @Query("SELECT * FROM market_volume WHERE symbol = :symbol")
    fun observeMarketVolume(symbol: String): Flow<MarketVolumeEntity?>

    @Query("SELECT * FROM market_volume WHERE symbol = :symbol")
    suspend fun getMarketVolumeOnce(symbol: String): MarketVolumeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAaiiSentiment(entity: AaiiSentimentEntity)

    @Query("SELECT * FROM aaii_sentiment WHERE id = 0")
    fun observeAaiiSentiment(): Flow<AaiiSentimentEntity?>

    @Query("SELECT * FROM aaii_sentiment WHERE id = 0")
    suspend fun getAaiiSentimentOnce(): AaiiSentimentEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCryptoSparkline(entity: CryptoSparklineEntity)

    @Query("SELECT * FROM crypto_sparkline WHERE symbol = :symbol")
    fun observeCryptoSparkline(symbol: String): Flow<CryptoSparklineEntity?>

    @Query("SELECT * FROM crypto_sparkline WHERE symbol = :symbol")
    suspend fun getCryptoSparklineOnce(symbol: String): CryptoSparklineEntity?
}
