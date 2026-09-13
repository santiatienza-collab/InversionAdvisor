package com.inversionadvisor.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.ArrayDeque

/**
 * Limita las peticiones salientes a `maxRequests` por `windowMillis`, para no
 * superar el rate-limit del plan gratuito de la API (Twelve Data free: 8 req/min).
 * Si se alcanza el límite, bloquea el hilo de red (nunca el hilo principal,
 * OkHttp ya ejecuta los interceptors en su propio dispatcher) hasta que haya hueco,
 * en vez de dejar que la petición falle con un 429.
 */
class RateLimitInterceptor(
    private val maxRequests: Int = 7, // margen de seguridad bajo el límite real de 8/min
    private val windowMillis: Long = 60_000L
) : Interceptor {

    private val timestamps = ArrayDeque<Long>()
    private val lock = Any()

    override fun intercept(chain: Interceptor.Chain): Response {
        synchronized(lock) {
            pruneOldTimestamps()
            if (timestamps.size >= maxRequests) {
                val waitMillis = windowMillis - (System.currentTimeMillis() - timestamps.peekFirst())
                if (waitMillis > 0) Thread.sleep(waitMillis)
                pruneOldTimestamps()
            }
            timestamps.addLast(System.currentTimeMillis())
        }
        return chain.proceed(chain.request())
    }

    private fun pruneOldTimestamps() {
        val now = System.currentTimeMillis()
        while (timestamps.isNotEmpty() && now - timestamps.peekFirst() > windowMillis) {
            timestamps.pollFirst()
        }
    }
}
