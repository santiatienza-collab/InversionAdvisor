package com.inversionadvisor.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.inversionadvisor.di.ServiceLocator
import java.util.concurrent.TimeUnit

/**
 * Refresca en background el resumen de mercado (VIX, oro, sectores, índices
 * mundiales, forex) usando refreshMarketOverview(), que ya agrupa todo en una
 * sola llamada batch y descarta lo que sigue fresco.
 *
 * Por qué 15 min: una llamada batch cada 15 min = ~96 peticiones/día en el
 * peor caso (mercado moviéndose todo el rato), muy por debajo del límite de
 * 800/día del plan gratuito de Twelve Data, dejando margen para las peticiones
 * puntuales del buscador y el screener.
 */
class MarketRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = ServiceLocator.provideMarketRepository(applicationContext)
        return try {
            repository.refreshMarketOverview()
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "market_refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MarketRefreshWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            // KEEP: si ya hay un trabajo periódico programado, no lo duplica ni lo reinicia.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
