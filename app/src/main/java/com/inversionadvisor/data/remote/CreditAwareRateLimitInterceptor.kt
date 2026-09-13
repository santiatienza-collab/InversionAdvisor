package com.inversionadvisor.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.ArrayDeque

/**
 * Limita las peticiones a Twelve Data por CRÉDITOS consumidos, no por número
 * de peticiones HTTP. Según su documentación, "API credits cost 1 per
 * symbol" — una sola llamada a /quote con varios símbolos separados por coma
 * gasta tantos créditos como símbolos lleve en una única petición. Contar
 * solo peticiones HTTP (como hacía RateLimitInterceptor) no protege de un
 * 429 si una llamada batch grande agota el límite por minuto de golpe.
 *
 * El límite real del plan gratuito es de 8 créditos/min — se deja margen
 * de seguridad. Además, MarketRepository trocea los batches grandes en
 * bloques pequeños para que ninguna petición individual necesite más
 * créditos de los que caben en la ventana.
 */
class CreditAwareRateLimitInterceptor(
    private val maxCreditsPerWindow: Int = 7,
    private val windowMillis: Long = 60_000L
) : Interceptor {

    private data class CreditUsage(val timestampMillis: Long, val credits: Int)

    private val usageLog = ArrayDeque<CreditUsage>()
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val credits = estimateCredits(request.url.queryParameter("symbol"))

        synchronized(lock) {
            pruneOldUsage()
            var creditsInWindow = usageLog.sumOf { it.credits }

            while (creditsInWindow + credits > maxCreditsPerWindow && usageLog.isNotEmpty()) {
                val oldest = usageLog.peekFirst()
                val waitMillis = windowMillis - (System.currentTimeMillis() - oldest.timestampMillis)
                if (waitMillis > 0) Thread.sleep(waitMillis)
                pruneOldUsage()
                creditsInWindow = usageLog.sumOf { it.credits }
            }

            usageLog.addLast(CreditUsage(System.currentTimeMillis(), credits))
        }
        return chain.proceed(request)
    }

    private fun estimateCredits(symbolParam: String?): Int {
        if (symbolParam.isNullOrBlank()) return 1
        return symbolParam.split(",").size
    }

    private fun pruneOldUsage() {
        val now = System.currentTimeMillis()
        while (usageLog.isNotEmpty() && now - usageLog.peekFirst().timestampMillis > windowMillis) {
            usageLog.pollFirst()
        }
    }
}
