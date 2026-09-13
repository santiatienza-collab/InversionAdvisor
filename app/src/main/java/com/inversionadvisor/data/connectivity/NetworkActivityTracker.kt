package com.inversionadvisor.data.connectivity

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Dos cosas separadas, con propósitos distintos:
 *
 * 1) isActivelyScanning — ¿hay tráfico masivo compitiendo AHORA MISMO por el WebView? (ver
 *    MarketRepository.refreshCompanyFinancialsIfStale/refreshStockPeIfStale, que esperan a que
 *    baje antes de lanzar su propia petición). Usa un contador de referencias (liveActiveCount)
 *    que baja al instante en cuanto el ÚLTIMO escaneo solapado termina de verdad, sin margen de
 *    gracia — necesita ser exacto, no aproximado (un margen aquí solo retrasaría sin motivo la
 *    petición de quien espera).
 *
 * 2) hadSuccessfulNetworkResponseRecently() — sustituye al antiguo "isBusy" que usaba
 *    ConnectivityBanner para no parpadear durante un escaneo pesado (Top10, "Analizar" un
 *    mercado: la avalancha de peticiones satura la red lo bastante como para que Android
 *    detecte caídas puntuales que no son reales). La versión anterior llevaba la cuenta de "qué
 *    partes de la app son un escaneo pesado" con un contador propio y un margen de gracia fijo
 *    de 60s tras terminar — frágil (dos veces ha dado problemas: primero esperaba ese margen de
 *    más antes de dejar pedir los ingresos netos, y su arreglo aún podía quedarse atascado en
 *    true para siempre con escaneos solapados).
 *
 *    AHORA, en vez de llevar la cuenta de "qué es un escaneo pesado" en ningún sitio, se anota
 *    la marca de tiempo de la ÚLTIMA respuesta de red real que ha recibido CUALQUIER cliente de
 *    la app (ver responseTimestampInterceptor, añadido a todos los OkHttpClient de
 *    NetworkModule, y HiddenWebViewScraper para el tráfico por WebView) — sin necesidad de que
 *    cada sitio nuevo recuerde envolver su código en nada especial, funciona automáticamente
 *    para cualquier tráfico futuro. Si ha llegado una respuesta real hace muy poco,
 *    ConnectivityBanner descarta como ruido un aviso de "conexión perdida" que llegue justo
 *    después — y si de verdad se corta la red, esta marca de tiempo deja de refrescarse sola
 *    (no hay respuestas que anotar) y el banner vuelve a funcionar con normalidad sin que haga
 *    falta ningún aviso de "escaneo terminado".
 */
object NetworkActivityTracker {

    // ---- 1) isActivelyScanning: para el WebView, sin cambios respecto al arreglo anterior ----

    private val liveActiveCount = AtomicInteger(0)
    private val _isActivelyScanning = MutableStateFlow(false)
    val isActivelyScanning: StateFlow<Boolean> = _isActivelyScanning

    /** Envuelve cualquier operación de red masiva (decenas/cientos de peticiones a la vez). */
    suspend fun <T> trackHeavyNetworkActivity(block: suspend () -> T): T {
        if (liveActiveCount.getAndIncrement() == 0) _isActivelyScanning.value = true
        try {
            return block()
        } finally {
            if (liveActiveCount.decrementAndGet() == 0) _isActivelyScanning.value = false
        }
    }

    // ---- 2) Sustituto de isBusy: marca de tiempo de la última respuesta de red real ----

    private val lastSuccessfulResponseAtMillis = AtomicLong(0L)

    /**
     * Llamado por responseTimestampInterceptor (todos los clientes OkHttp) y por
     * HiddenWebViewScraper en cuanto llega CUALQUIER respuesta de red (incluso un HTTP 4xx/5xx
     * cuenta — el código de estado es cosa de la API, la prueba de que la red funciona a nivel
     * de transporte es que la respuesta llegó, no que el contenido sea el esperado).
     */
    fun markSuccessfulNetworkResponse() {
        lastSuccessfulResponseAtMillis.set(System.currentTimeMillis())
    }

    /**
     * ¿ha llegado una respuesta de red real en los últimos [withinMillis] ms? Usado por
     * ConnectivityBanner para descartar como ruido un aviso de "conexión perdida" que llega
     * justo después de tráfico real — ver el comentario de la clase.
     */
    fun hadSuccessfulNetworkResponseRecently(withinMillis: Long = 10_000L): Boolean =
        System.currentTimeMillis() - lastSuccessfulResponseAtMillis.get() < withinMillis
}
