package com.inversionadvisor.data.webview

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.withLock
import org.json.JSONTokener
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Carga una página en un WebView OCULTO (nunca se añade a ninguna pantalla
 * visible) y devuelve su HTML ya renderizado, tras dejar que se ejecute su
 * JavaScript — a diferencia de una petición HTTP normal (OkHttp), esto puede
 * ver contenido que solo aparece DESPUÉS de ejecutar JavaScript: tanto retos
 * anti-bots que exigen un navegador de verdad (AAII) como páginas modernas
 * que cargan sus datos por JavaScript en vez de traerlos ya en el HTML
 * inicial (la página de cotización de Yahoo, usada para el PER — ver
 * MarketRepository.refreshStockPeIfStale). También intenta pulsar
 * automáticamente un aviso de cookies/RGPD si aparece (Yahoo suele mostrar
 * uno a dispositivos en la UE antes de dejar ver la página real) — busca
 * botones con texto tipo "Aceptar todo" en varios idiomas y hace clic en el
 * primero que encuentra. Nada de esto tiene garantía de funcionar en ningún
 * caso — algunos sistemas anti-bots también detectan WebViews automatizados
 * sin interacción humana real, y un aviso de cookies con una estructura
 * distinta a la esperada no se detectaría — pero es la única vía que tiene
 * alguna posibilidad con las herramientas de Android.
 *
 * WebView SOLO se puede usar desde el hilo principal (main thread); por eso
 * todo el manejo de WebView aquí ocurre en un Handler(Looper.getMainLooper()),
 * aunque la función se llame desde una corrutina de fondo.
 */
class HiddenWebViewScraper(private val appContext: Context) {

    /**
     * [extraWaitMillis]: tiempo de margen tras el "onPageFinished" del
     * WebView antes de leer el HTML — muchos retos anti-bots resuelven su
     * JavaScript unos segundos DESPUÉS de que la página termine de cargar
     * (temporizadores, redirecciones); leer el HTML demasiado pronto
     * capturaría todavía la página de verificación, no la real.
     *
     * [overallTimeoutMillis]: límite de seguridad — si tras este tiempo total
     * no se ha resuelto nada (p. ej. porque el aviso de cookies tiene una
     * estructura que el clic automático no reconoce y la página nunca
     * redirige a la real), se lee lo que haya en pantalla en ese momento en
     * vez de dejar la corrutina esperando para siempre.
     *
     * [extraClickPhrases]: frases adicionales de botones a pulsar antes de leer
     * el HTML (además del aviso de cookies, que siempre se intenta) — p. ej.
     * "Quarterly"/"Trimestral" en la página de estados financieros de Yahoo,
     * para cambiar de la vista anual (la que carga por defecto) a la
     * trimestral. Se pulsa DESPUÉS del aviso de cookies y se espera otro poco
     * ([extraWaitMillis] de nuevo) antes de leer.
     *
     * [minAcceptableHtmlLength]: si al leer el HTML (con el margen normal ya
     * cumplido) el contenido sale más corto que esto, se interpreta como "la
     * página de verdad (con sus datos ya cargados por JavaScript) todavía no
     * ha terminado de pintarse" — pasa de forma intermitente en páginas SPA
     * pesadas, el mismo margen de espera unas veces basta y otras no. En vez
     * de devolver ese HTML a medias, se espera [extraWaitMillis] una segunda
     * vez y se reintenta UNA sola vez más antes de rendirse con lo que haya.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetchRenderedHtml(
        url: String,
        extraWaitMillis: Long = 5_000L,
        overallTimeoutMillis: Long = 15_000L,
        extraClickPhrases: List<String> = emptyList(),
        minAcceptableHtmlLength: Int = 150_000
    ): String = webViewMutex.withLock {
        fetchRenderedHtmlInternal(url, extraWaitMillis, overallTimeoutMillis, extraClickPhrases, minAcceptableHtmlLength)
    }

    private suspend fun fetchRenderedHtmlInternal(
        url: String,
        extraWaitMillis: Long,
        overallTimeoutMillis: Long,
        extraClickPhrases: List<String>,
        minAcceptableHtmlLength: Int
    ): String =
        suspendCancellableCoroutine { continuation ->
            val mainHandler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var retriedForShortContent = false

            fun extractAndResolve(view: WebView) {
                if (!continuation.isActive) return
                view.evaluateJavascript("document.documentElement.outerHTML") { rawResult ->
                    try {
                        // evaluateJavascript devuelve un literal JSON (con comillas y
                        // escapes) en vez del string en crudo — JSONTokener lo decodifica.
                        val html = JSONTokener(rawResult).nextValue() as String
                        if (!continuation.isActive) return@evaluateJavascript
                        if (html.length < minAcceptableHtmlLength && !retriedForShortContent) {
                            // Contenido sospechosamente corto (probable JavaScript aún sin
                            // terminar de pintar) — se da un margen extra y se reintenta UNA vez.
                            retriedForShortContent = true
                            mainHandler.postDelayed({
                                if (webView != null) extractAndResolve(view)
                            }, extraWaitMillis)
                        } else {
                            continuation.resume(html)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }

            mainHandler.post {
                val view = WebView(appContext)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.userAgentString =
                    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/125.0.0.0 Mobile Safari/537.36"

                // Límite de seguridad: si nada se resuelve antes, se lee lo que haya.
                mainHandler.postDelayed({
                    webView?.let { extractAndResolve(it) }
                }, overallTimeoutMillis)

                view.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        // AÑADIDO — sustituto de isBusy (ver NetworkActivityTracker): onPageFinished
                        // solo se llama si la petición llegó a buen puerto a nivel de red (aunque
                        // el CONTENIDO sea una página de error de Yahoo, eso es cosa de la API, no
                        // de la conectividad) — cuenta igual que una respuesta HTTP normal para que
                        // ConnectivityBanner descarte como ruido un parpadeo justo después.
                        com.inversionadvisor.data.connectivity.NetworkActivityTracker.markSuccessfulNetworkResponse()

                        // Intenta pulsar un aviso de cookies/RGPD si lo hay — no hace nada si
                        // no existe ningún botón con ese texto en la página.
                        view.evaluateJavascript(CONSENT_CLICK_JS, null)

                        if (extraClickPhrases.isEmpty()) {
                            mainHandler.postDelayed({
                                if (webView != null) extractAndResolve(view)
                            }, extraWaitMillis)
                        } else {
                            // Primero el margen normal (para que la página cargue y se cierre
                            // el aviso de cookies), LUEGO el clic adicional (p. ej. "Trimestral"),
                            // y solo entonces otro margen antes de leer — si se pulsara ya el
                            // aviso de cookies podría seguir tapando el botón buscado.
                            mainHandler.postDelayed({
                                view.evaluateJavascript(buildClickJs(extraClickPhrases), null)
                                mainHandler.postDelayed({
                                    if (webView != null) extractAndResolve(view)
                                }, extraWaitMillis)
                            }, extraWaitMillis)
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        errorCode: Int,
                        description: String?,
                        failingUrl: String?
                    ) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(IllegalStateException("WebView error $errorCode: $description"))
                        }
                    }
                }
                // Cabecera "Referer" — sin esto, la petición llega como una navegación
                // directa sin ningún contexto previo (típico de un bot: un usuario real casi
                // siempre llega desde un buscador o la portada del propio sitio, no
                // "aparece" directamente en una página de resultados). No hay garantía de que
                // esto evite ningún bloqueo anti-bot — son sistemas pensados para detectar
                // automatización real, no solo esta cabecera — pero es una mejora barata y sin
                // ningún riesgo de romper nada que ya funcionaba.
                val referer = try {
                    val parsed = android.net.Uri.parse(url)
                    "${parsed.scheme}://${parsed.host}/"
                } catch (e: Exception) {
                    null
                }
                if (referer != null) {
                    view.loadUrl(url, mapOf("Referer" to referer))
                } else {
                    view.loadUrl(url)
                }
            }

            continuation.invokeOnCancellation {
                mainHandler.post { webView?.destroy() }
            }
        }

    companion object {
        // AÑADIDO — encontrado con Logcat real (ServiceNow, NOW): dos peticiones a este mismo
        // scraper (PER del stock y datos de la empresa, ambas usando WebView) se lanzaban EN
        // PARALELO desde corrutinas separadas, compitiendo por el WebView del hilo principal a
        // la vez — las dos fallaban devolviendo la página de error de Yahoo ("Oops, something
        // went wrong"), de forma consistente, no intermitente. Este candado serializa TODAS las
        // llamadas a fetchRenderedHtml desde cualquier punto de la app — solo una carga de
        // WebView a la vez, las demás esperan su turno en cola, en vez de competir entre sí.
        private val webViewMutex = kotlinx.coroutines.sync.Mutex()

        /**
         * Busca un botón/enlace con texto de "aceptar todas las cookies" en varios
         * idiomas (Yahoo puede servir el aviso en español o en inglés según la
         * región) y hace clic en el primero que encuentra. Envuelto en try/catch
         * de JS para que un fallo aquí nunca rompa la carga de la página real.
         */
        private const val CONSENT_CLICK_JS = """
            (function() {
                try {
                    var phrases = ['aceptar todo', 'aceptar todas', 'accept all', 'i agree', 'agree', 'aceptar y continuar', 'aceptar'];
                    var candidates = document.querySelectorAll('button, a, input[type=submit], input[type=button]');
                    for (var i = 0; i < candidates.length; i++) {
                        var el = candidates[i];
                        var text = ((el.innerText || el.value || '') + '').trim().toLowerCase();
                        if (!text) continue;
                        for (var j = 0; j < phrases.length; j++) {
                            if (text.indexOf(phrases[j]) !== -1) {
                                el.click();
                                return;
                            }
                        }
                    }
                } catch (e) {}
            })();
        """

        /** Genera el mismo tipo de script de clic que CONSENT_CLICK_JS pero con frases a medida. */
        private fun buildClickJs(phrases: List<String>): String {
            val phrasesJs = phrases.joinToString(",") { "'${it.lowercase().replace("'", "\\'")}'" }
            return """
                (function() {
                    try {
                        var phrases = [$phrasesJs];
                        var candidates = document.querySelectorAll('button, a, input[type=submit], input[type=button], [role=tab]');
                        for (var i = 0; i < candidates.length; i++) {
                            var el = candidates[i];
                            var text = ((el.innerText || el.value || '') + '').trim().toLowerCase();
                            if (!text) continue;
                            for (var j = 0; j < phrases.length; j++) {
                                if (text.indexOf(phrases[j]) !== -1) {
                                    el.click();
                                    return;
                                }
                            }
                        }
                    } catch (e) {}
                })();
            """
        }
    }
}
