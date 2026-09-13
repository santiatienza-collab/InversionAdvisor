package com.inversionadvisor.di

import android.content.Context
import com.inversionadvisor.data.local.AppDatabase
import com.inversionadvisor.data.remote.NetworkModule
import com.inversionadvisor.data.repository.FavoritesRepository
import com.inversionadvisor.data.repository.MarketMoversRepository
import com.inversionadvisor.data.repository.MarketRepository
import com.inversionadvisor.data.repository.ScreenerRepository
import com.inversionadvisor.data.repository.StockUniverseRepository
import com.inversionadvisor.data.repository.Top10Repository

/**
 * Localizador de servicios simple. Cuando el proyecto crezca (más repositorios,
 * más pantallas) esto se puede sustituir por Hilt sin cambiar las capas de arriba.
 */
object ServiceLocator {

    @Volatile
    private var marketRepository: MarketRepository? = null

    @Volatile
    private var stockUniverseRepository: StockUniverseRepository? = null

    @Volatile
    private var screenerRepository: ScreenerRepository? = null

    @Volatile
    private var marketMoversRepository: MarketMoversRepository? = null

    @Volatile
    private var favoritesRepository: FavoritesRepository? = null

    fun provideMarketRepository(context: Context): MarketRepository {
        return marketRepository ?: synchronized(this) {
            marketRepository ?: MarketRepository(
                dao = AppDatabase.getInstance(context).marketDao(),
                hiddenWebViewScraper = com.inversionadvisor.data.webview.HiddenWebViewScraper(context.applicationContext)
            ).also { marketRepository = it }
        }
    }

    /** Público (no solo para ServiceLocator): la pestaña "Busca" también necesita el universo de los 3 mercados. */
    fun provideStockUniverseRepository(context: Context): StockUniverseRepository {
        return stockUniverseRepository ?: synchronized(this) {
            stockUniverseRepository ?: StockUniverseRepository(
                wikipediaIndexApi = NetworkModule.wikipediaIndexApi,
                dao = AppDatabase.getInstance(context).stockUniverseDao()
            ).also { stockUniverseRepository = it }
        }
    }

    fun provideScreenerRepository(context: Context): ScreenerRepository {
        return screenerRepository ?: synchronized(this) {
            screenerRepository ?: ScreenerRepository(
                marketRepository = provideMarketRepository(context),
                screenerDao = AppDatabase.getInstance(context).screenerDao(),
                stockUniverseRepository = provideStockUniverseRepository(context)
            ).also { screenerRepository = it }
        }
    }

    fun provideMarketMoversRepository(context: Context): MarketMoversRepository {
        return marketMoversRepository ?: synchronized(this) {
            marketMoversRepository ?: MarketMoversRepository(
                yahooFinanceMoversApi = NetworkModule.yahooFinanceMoversApi,
                stockUniverseRepository = provideStockUniverseRepository(context),
                dao = AppDatabase.getInstance(context).marketMoversDao()
            ).also { marketMoversRepository = it }
        }
    }

    fun provideFavoritesRepository(context: Context): FavoritesRepository {
        return favoritesRepository ?: synchronized(this) {
            favoritesRepository ?: FavoritesRepository(
                dao = AppDatabase.getInstance(context).favoritesDao()
            ).also { favoritesRepository = it }
        }
    }

    @Volatile
    private var top10Repository: Top10Repository? = null

    fun provideTop10Repository(context: Context): Top10Repository {
        return top10Repository ?: synchronized(this) {
            top10Repository ?: Top10Repository(
                screenerDao = AppDatabase.getInstance(context).screenerDao(),
                top10Dao = AppDatabase.getInstance(context).top10Dao(),
                marketRepository = provideMarketRepository(context)
            ).also { top10Repository = it }
        }
    }

    @Volatile
    private var newsRepository: com.inversionadvisor.data.repository.NewsRepository? = null

    /** Sin dependencia de Context/Room a propósito (ver NewsRepository): los titulares no se cachean. */
    fun provideNewsRepository(): com.inversionadvisor.data.repository.NewsRepository {
        return newsRepository ?: synchronized(this) {
            newsRepository ?: com.inversionadvisor.data.repository.NewsRepository()
                .also { newsRepository = it }
        }
    }
}
