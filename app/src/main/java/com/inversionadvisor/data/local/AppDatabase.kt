package com.inversionadvisor.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.inversionadvisor.data.local.dao.FavoritesDao
import com.inversionadvisor.data.local.dao.MarketDao
import com.inversionadvisor.data.local.dao.MarketMoversDao
import com.inversionadvisor.data.local.dao.ScreenerDao
import com.inversionadvisor.data.local.dao.StockUniverseDao
import com.inversionadvisor.data.local.dao.Top10Dao
import com.inversionadvisor.data.local.entities.AaiiSentimentEntity
import com.inversionadvisor.data.local.entities.BuyOpportunityEntity
import com.inversionadvisor.data.local.entities.CandleEntity
import com.inversionadvisor.data.local.entities.CandleFetchMetaEntity
import com.inversionadvisor.data.local.entities.CryptoSparklineEntity
import com.inversionadvisor.data.local.entities.EarningsDateEntity
import com.inversionadvisor.data.local.entities.FavoriteStockEntity
import com.inversionadvisor.data.local.entities.FearGreedEntity
import com.inversionadvisor.data.local.entities.IndexUniverseEntryEntity
import com.inversionadvisor.data.local.entities.IndexUniverseMetaEntity
import com.inversionadvisor.data.local.entities.MarketMoverEntity
import com.inversionadvisor.data.local.entities.MarketMoversMetaEntity
import com.inversionadvisor.data.local.entities.CompanyFinancialsEntity
import com.inversionadvisor.data.local.entities.MarketVolumeEntity
import com.inversionadvisor.data.local.entities.PeRatioEntity
import com.inversionadvisor.data.local.entities.QuoteEntity
import com.inversionadvisor.data.local.entities.ScreenerRunMetaEntity
import com.inversionadvisor.data.local.entities.Top10EntryEntity
import com.inversionadvisor.data.local.entities.Top10GenericCandidateEntity
import com.inversionadvisor.data.local.entities.PosiblesComprasEntryEntity
import com.inversionadvisor.data.local.dao.PosiblesComprasDao
import com.inversionadvisor.data.local.entities.UptrendCandidateEntity

@Database(
    entities = [
        QuoteEntity::class,
        CandleEntity::class,
        CandleFetchMetaEntity::class,
        FearGreedEntity::class,
        MarketVolumeEntity::class,
        AaiiSentimentEntity::class,
        CryptoSparklineEntity::class,
        BuyOpportunityEntity::class,
        UptrendCandidateEntity::class,
        ScreenerRunMetaEntity::class,
        IndexUniverseEntryEntity::class,
        IndexUniverseMetaEntity::class,
        MarketMoverEntity::class,
        MarketMoversMetaEntity::class,
        FavoriteStockEntity::class,
        PeRatioEntity::class,
        CompanyFinancialsEntity::class,
        Top10EntryEntity::class,
        EarningsDateEntity::class,
        Top10GenericCandidateEntity::class,
        PosiblesComprasEntryEntity::class
    ],
    // NUEVO — pedido expresamente: tabla nueva para "Posibles Compras" (recupera el espíritu de
    // "Futuras compras" como su propio Top20) — fallbackToDestructiveMigration se encarga, sin
    // migración manual.
    version = 32,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun marketDao(): MarketDao
    abstract fun screenerDao(): ScreenerDao
    abstract fun stockUniverseDao(): StockUniverseDao
    abstract fun marketMoversDao(): MarketMoversDao
    abstract fun favoritesDao(): FavoritesDao
    abstract fun top10Dao(): Top10Dao
    abstract fun posiblesComprasDao(): PosiblesComprasDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "inversion_advisor.db"
                )
                    // Proyecto en desarrollo, sin usuarios reales todavía: es más simple
                    // borrar y recrear la caché local que escribir migraciones para cada
                    // cambio de esquema. Antes de publicar, sustituir por migraciones reales.
                    .fallbackToDestructiveMigration()
                    .build().also { INSTANCE = it }
            }
        }
    }
}
