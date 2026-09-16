package com.inversionadvisor.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Provee las instancias de Retrofit para cada API externa.
 * Manual DI (sin Hilt) para mantener el esqueleto simple; se puede migrar
 * a Hilt más adelante sin tocar el resto de capas.
 */
object NetworkModule {

    private val moshi: Moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private fun okHttpClient(extraInterceptor: okhttp3.Interceptor? = null): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val builder = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(logging)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
        extraInterceptor?.let { builder.addInterceptor(it) }
        return builder.build()
    }

    /**
     * AÑADIDO — sustituto de NetworkActivityTracker.isBusy (ver esa clase para el porqué): anota
     * la marca de tiempo en cuanto llega CUALQUIER respuesta de red, en TODOS los clientes de la
     * app (se añade a cada OkHttpClient.Builder() de este fichero), sin necesidad de identificar
     * "esto es un escaneo pesado" en ningún sitio en concreto. Se cuenta como respuesta válida
     * cualquier HTTP recibido, incluso un error 4xx/5xx — la prueba de que la red funciona a
     * nivel de transporte es que la respuesta llegó, el código de estado es cosa de la propia
     * API. Solo si chain.proceed() lanza una excepción (verdadero fallo de red: sin conexión,
     * DNS, timeout) NO se anota nada, que es justo la señal real que le interesa al banner de
     * conexión.
     */
    private val responseTimestampInterceptor = okhttp3.Interceptor { chain ->
        val response = chain.proceed(chain.request())
        com.inversionadvisor.data.connectivity.NetworkActivityTracker.markSuccessfulNetworkResponse()
        response
    }

    val twelveDataApi: TwelveDataApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.twelvedata.com/")
            .client(okHttpClient(extraInterceptor = CreditAwareRateLimitInterceptor()))
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TwelveDataApi::class.java)
    }

    /**
     * Financial Modeling Prep — API financiera estructurada (JSON limpio, sin HTML). SIN USO
     * ACTUALMENTE: se probó para los ingresos netos de "Datos de Empresa" en vez de scrapear
     * Yahoo, pero su endpoint de income-statement devuelve HTTP 402 (Payment Required) en el
     * plan gratuito — no viable sin pagar un plan de FMP. Se deja el cliente listo (igual que
     * MarketRepository.fmpApiKey quitado del constructor) por si en el futuro se decide pagar
     * esa suscripción; hasta entonces MarketRepository ha vuelto al scraping de Yahoo. Rate
     * limit conservador por si se retoma.
     */
    val financialModelingPrepApi: FinancialModelingPrepApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(RateLimitInterceptor(maxRequests = 30, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://financialmodelingprep.com/stable/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(FinancialModelingPrepApi::class.java)
    }

    /**
     * SEC EDGAR — API pública, oficial y gratuita (sin API key) para los ingresos netos de
     * "Datos de Empresa": JSON directo de los propios informes 10-K, sin HTML que parsear y sin
     * WebView. Cubre prácticamente todo EE.UU. (S&P 500, Nasdaq 100); NO cubre el IBEX 35
     * (empresas españolas no reportan a la SEC) — para esos símbolos MarketRepository sigue
     * usando el scraping de Yahoo como respaldo.
     *
     * La SEC pide identificarse con un User-Agent descriptivo con contacto real (política de
     * "fair access") — se usa BuildConfig.SEC_EDGAR_CONTACT_EMAIL si está configurado
     * (local.properties), y si no, un valor genérico de repuesto (funciona igual, pero
     * conviene poner un contacto real si se usa con frecuencia). Límite oficial: 10
     * peticiones/segundo por IP — el límite de aquí se queda muy por debajo, sin necesidad real
     * de acercarse a ese máximo.
     */
    val secEdgarApi: SecEdgarApi by lazy {
        val contact = com.inversionadvisor.BuildConfig.SEC_EDGAR_CONTACT_EMAIL
            .takeIf { it.isNotBlank() }
            ?: "contacto no configurado (ver SEC_EDGAR_CONTACT_EMAIL en local.properties)"
        val userAgentInterceptor = okhttp3.Interceptor { chain ->
            val request = chain.request().newBuilder()
                .header("User-Agent", "InversionAdvisor/1.0 ($contact)")
                .header("Accept", "application/json")
                .build()
            chain.proceed(request)
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(userAgentInterceptor)
            .addInterceptor(RateLimitInterceptor(maxRequests = 60, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://www.sec.gov/") // @Url en cada llamada manda la URL absoluta completa (www.sec.gov o data.sec.gov)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(SecEdgarApi::class.java)
    }

    val coinGeckoApi: CoinGeckoApi by lazy {
        Retrofit.Builder()
            .baseUrl("https://api.coingecko.com/api/v3/")
            .client(okHttpClient())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CoinGeckoApi::class.java)
    }

    /**
     * Tanto CNN como Yahoo bloquean o penalizan peticiones sin un User-Agent
     * de navegador real Y sin un Referer que apunte a su propio dominio —
     * esto último es habitual en protecciones anti-scraping y muy probable
     * causa de que estas dos fuentes no respondieran.
     */
    private fun browserHeadersInterceptor(referer: String) = okhttp3.Interceptor { chain ->
        val request = chain.request().newBuilder()
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .header("Accept", "application/json, text/plain, */*")
            .header("Referer", referer)
            .build()
        chain.proceed(request)
    }

    val cnnFearGreedApi: CnnFearGreedApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(browserHeadersInterceptor("https://edition.cnn.com/markets/fear-and-greed"))
            .addInterceptor(RateLimitInterceptor(maxRequests = 10, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://production.dataviz.cnn.io/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(CnnFearGreedApi::class.java)
    }

    val yahooFinanceApi: YahooFinanceApi by lazy {
        // Dispatcher propio con más concurrencia: por defecto OkHttp solo permite 5
        // conexiones simultáneas al mismo host, y este cliente ahora comparte carga
        // entre la ficha de detalle de un stock Y la rotación sectorial del panel
        // (~14 peticiones a la vez) — con el límite por defecto, las peticiones de
        // "abrir un stock" se quedaban en cola detrás de esas 14 aunque hubiera
        // margen de sobra en el rate limit de abajo.
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 24
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .dispatcher(dispatcher)
            .addInterceptor(browserHeadersInterceptor("https://finance.yahoo.com/"))
            // Subido de 40 a 90/min: la evidencia real de esta sesión (decenas de
            // peticiones seguidas a este mismo endpoint, todas con 200 OK y sin un
            // solo 429) muestra que Yahoo tolera bien ráfagas — el límite de 40 era
            // un margen de seguridad demasiado conservador para lo que ahora pide
            // el panel (rotación sectorial + VIX + oro, ~16 peticiones de golpe en
            // cada refresco) más lo que pida a la vez la ficha de un stock.
            // CAMBIADO otra vez a petición expresa — fallo real confirmado con logs (NVIDIA
            // tardando 26s en abrir): "Valores Alcistas" ahora hace bastante más trabajo en
            // directo por candidato (RSI, volumen, patrones, HCH a 5 años, sector...) que
            // antes, agotando el límite de 90/min con solo hacer scroll por la lista — dejando
            // las peticiones de "abrir un stock" en cola varios segundos detrás. Subido a
            // 200/min, con el mismo respaldo de arriba: Yahoo tolera bien las ráfagas.
            .addInterceptor(RateLimitInterceptor(maxRequests = 200, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://query1.finance.yahoo.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(YahooFinanceApi::class.java)
    }

    // yahooQuoteApi (v7/finance/quote) QUITADO — devolvía 401 "Invalid Cookie"/"Invalid Crumb"
    // de forma silenciosa (ese endpoint concreto exige un crumb+cookie de sesión que esta app no
    // implementa, a diferencia de v8/finance/chart, que sí funciona sin eso). La fecha de
    // resultados se saca ahora del HTML ya renderizado de la página de cotización — ver
    // YahooQuotePageParser.parseEarningsDateText y MarketRepository.refreshEarningsDateIfStale.

    /**
     * Búsqueda libre de tickers (v1/finance/search) — para el "Universo
     * Personalizado": encontrar cualquier valor del mercado, no solo los que
     * ya están en S&P 500/Nasdaq-100/IBEX 35. Mismo cliente base que el de
     * las gráficas (headers de navegador, sin autenticación) — no verificado
     * en vivo si este endpoint concreto también funciona así.
     */
    val yahooSearchApi: YahooSearchApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(browserHeadersInterceptor("https://finance.yahoo.com/"))
            .addInterceptor(RateLimitInterceptor(maxRequests = 30, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://query1.finance.yahoo.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(YahooSearchApi::class.java)
    }

    // NOTA: yahooQuoteApi (v7/finance/quote, PER) y yahooOptionsApi (v7/finance/
    // options, PCR) se han retirado — ambos devolvían 401 (necesitan un "crumb"/
    // cookie de autenticación que esta app no tiene, a diferencia de v8/finance/
    // chart). El PER se intentó con Twelve Data (/statistics, 403 — fundamentales
    // bloqueados en el plan gratuito) y con la página HTML pública de Yahoo por
    // HTTP simple (carga pero sin datos parseables, incluso para símbolos con PER
    // de sobra conocido como AMZN — Yahoo los rellena con JavaScript después de
    // cargar). Ahora se obtiene vía el mismo WebView oculto que ya se montó para
    // AAII (ver HiddenWebViewScraper en MarketRepository), que sí ejecuta ese
    // JavaScript — ya no hace falta un cliente HTTP aparte para esto.

    /**
     * Finviz (finviz.com), sin API key, para el PER — ver FinvizApi para el
     * porqué (confirmado accesible sin bloqueo, a diferencia de los intentos
     * anteriores con Yahoo/Twelve Data).
     *
     * Rate limit subido de 20 a 90/min — el de 20 se puso pensando en un uso puntual (sectores
     * una vez, stock suelto de vez en cuando), pero Top10 pide el PER de 100-150+ candidatos en
     * la misma pasada, y con 20/min esa cola sola tardaba 7-8 minutos, fuera cual fuera la
     * concurrencia del resto del cálculo (el límite está en la capa de red, por debajo de eso).
     * PRUEBA EXPERIMENTAL, sin confirmar en vivo que Finviz lo tolere bien a este volumen (a
     * diferencia del límite de yahooFinanceApi, subido en su día con evidencia real de la propia
     * sesión) — si empiezan a verse muchos fallos/vacíos de PER en los logs (etiqueta
     * "PeRatioFetch"), puede ser señal de que Finviz está limitando por su cuenta y habría que
     * bajarlo otra vez.
     */
    val finvizApi: FinvizApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(browserHeadersInterceptor("https://finviz.com/"))
            .addInterceptor(RateLimitInterceptor(maxRequests = 90, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://finviz.com/")
            .client(client)
            .build()
            .create(FinvizApi::class.java)
    }

    /**
     * NUEVO — pedido expresamente: lee el JSON publicado por scanner-cli (ver ese módulo) en
     * GitHub, en vez de escanear en el propio móvil. baseUrl aquí es un relleno obligatorio de
     * Retrofit (nunca se usa de verdad, ya que ScanResultApi.getScanResult() recibe la URL
     * completa con @Url) — cualquier URL válida vale, se deja la misma que ya usa raw.
     */
    val scanResultApi: com.inversionadvisor.data.remote.ScanResultApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://raw.githubusercontent.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(com.inversionadvisor.data.remote.ScanResultApi::class.java)
    }

    /**
     * Cliente Yahoo específico para el escaneo masivo del screener
     * (cientos de símbolos). El cliente normal (yahooFinanceApi) se queda
     * con su límite conservador de 40/min para no arriesgar la fiabilidad
     * del dashboard; este es más permisivo Y sube el límite de conexiones
     * simultáneas por host de OkHttp (por defecto solo 5), que si no sería
     * el verdadero cuello de botella aunque se lancen más corrutinas en
     * paralelo desde ScreenerRepository.
     */
    val yahooFinanceBulkApi: YahooFinanceApi by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 24
            maxRequestsPerHost = 24
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .dispatcher(dispatcher)
            .addInterceptor(browserHeadersInterceptor("https://finance.yahoo.com/"))
            .addInterceptor(RateLimitInterceptor(maxRequests = 200, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://query1.finance.yahoo.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(YahooFinanceApi::class.java)
    }

    /**
     * Cliente Yahoo DEDICADO para el banner de top ganadores/perdedores,
     * separado a propósito de yahooFinanceBulkApi (que usa el screener):
     * si compartieran cliente, un escaneo de ~600 símbolos para el banner
     * saturaría el mismo rate limit y pool de conexiones que necesita el
     * escaneo del screener, y ambos empezarían a fallar/colgarse a la vez
     * (como pasó). Límites más conservadores que el bulk del screener,
     * porque aquí se escanea el universo COMPLETO de golpe (no una sola
     * pestaña), con más margen para no provocar bloqueos de Yahoo.
     */
    val yahooFinanceMoversApi: YahooFinanceApi by lazy {
        val dispatcher = okhttp3.Dispatcher().apply {
            maxRequests = 30
            maxRequestsPerHost = 30
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .dispatcher(dispatcher)
            // Subido de 220 a 320/min: con ~600-700 símbolos únicos en el universo
            // combinado, 220/min obligaba a un mínimo de ~3 minutos SOLO por el
            // límite de peticiones, aparte de la espera por lotes que ya se ha
            // quitado más arriba (ver MarketMoversRepository). 320/min sigue
            // bastante por debajo de lo que tumbó Yahoo la vez anterior (ver
            // comentario de la clase); si vuelve a bloquear peticiones, bajar
            // este número antes que subir la concurrencia.
            .addInterceptor(RateLimitInterceptor(maxRequests = 320, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://query1.finance.yahoo.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(YahooFinanceApi::class.java)
    }

    /**
     * NOTA: el cliente de AAII (scraping HTML) se ha retirado — su página y su
     * archivo .xls de descarga están detrás de un firewall anti-bots que exige
     * ejecutar JavaScript, imposible de superar con una petición HTTP simple
     * (confirmado: ambos devuelven la misma página de bloqueo). El indicador de
     * sentimiento alcista/bajista ahora sale del ratio put/call del propio feed
     * de CNN Fear & Greed (ver cnnFearGreedApi más abajo y
     * MarketRepository.refreshAaiiSentiment).
     */

    /**
     * Stooq (histórico de precios, CSV, sin API key) — último recurso cuando
     * Yahoo devuelve histórico truncado para un valor concreto (ver
     * MarketRepository.fetchCandlesFromStooq) y Twelve Data no cubre esa
     * bolsa con el plan gratuito. Fuente sin documentación oficial ni límites
     * publicados; se deja un margen conservador para no arriesgarse a que
     * bloquee peticiones o devuelva "Exceeded the daily hits limit".
     */
    val stooqApi: StooqApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(browserHeadersInterceptor("https://stooq.com/"))
            .addInterceptor(RateLimitInterceptor(maxRequests = 15, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://stooq.com/")
            .client(client)
            .build()
            .create(StooqApi::class.java)
    }

    /**
     * Constituyentes de índices: S&P 500 e IBEX 35 vía las tablas públicas
     * de Wikipedia (sin protección anti-scraping conocida); Nasdaq-100 vía
     * slickcharts.com (ver StockUniverseRepository). A diferencia de
     * Wikipedia, slickcharts sí puede bloquear peticiones sin pinta de
     * navegador real, así que se reutiliza el mismo interceptor de headers
     * que ya usan CNN/Yahoo/AAII — no le hace ningún daño a las peticiones
     * a Wikipedia y evita que las de slickcharts se bloqueen. El timeout es
     * más largo porque estas páginas son grandes.
     */
    val wikipediaIndexApi: WikipediaIndexApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(browserHeadersInterceptor("https://www.slickcharts.com/"))
            .addInterceptor(RateLimitInterceptor(maxRequests = 10, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://en.wikipedia.org/") // @Url en cada llamada pasa la ruta completa (Wikipedia o slickcharts)
            .client(client)
            .build()
            .create(WikipediaIndexApi::class.java)
    }

    /**
     * NUEVO — Investing.com, respaldo de "Ingresos netos" para el IBEX 35 (.MC) cuando el
     * scraping de Yahoo no trae dato (ver MarketRepository.fetchNetIncomesFromInvesting).
     * SIN CONFIRMAR EN VIVO si Investing.com bloquea este volumen de peticiones (tiene fama de
     * protección Cloudflare en algunas rutas) — de ahí el rate limit conservador y
     * InvestingFinancialsParser.isBlockedOrErrorPage para detectarlo pronto si pasa.
     */
    val investingApi: InvestingApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(browserHeadersInterceptor("https://www.google.com/"))
            .addInterceptor(RateLimitInterceptor(maxRequests = 20, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://www.investing.com/") // @Url en cada llamada pasa la ruta completa
            .client(client)
            .build()
            .create(InvestingApi::class.java)
    }

    /**
     * NUEVO — feeds RSS de mercados de WSJ/FT/NYT/Bloomberg para la sección Noticias (ver
     * NewsRepository). Solo titular + enlace: es lo único que estos medios publican abierto,
     * el cuerpo del artículo queda tras el paywall de cada uno.
     */
    val newsRssApi: NewsRssApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(browserHeadersInterceptor("https://www.google.com/"))
            .addInterceptor(RateLimitInterceptor(maxRequests = 20, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://feeds.a.dj.com/") // @Url en cada llamada pasa la ruta completa de cada feed
            .client(client)
            .build()
            .create(NewsRssApi::class.java)
    }

    /**
     * NUEVO — API gratuita de DeepL para traducir los titulares de Noticias (ver
     * NewsTranslator). A diferencia del resto de clientes de este fichero, esta SÍ necesita
     * clave (DEEPL_API_KEY en local.properties) — sin clave, NewsTranslator ni siquiera llega
     * a usar este cliente, cae directo a ML Kit on-device.
     */
    val deepLApi: DeepLApi by lazy {
        val client = OkHttpClient.Builder()
            .addInterceptor(responseTimestampInterceptor)
            .addInterceptor(RateLimitInterceptor(maxRequests = 20, windowMillis = 60_000L))
            .addInterceptor(HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC })
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        Retrofit.Builder()
            .baseUrl("https://api-free.deepl.com/")
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(DeepLApi::class.java)
    }
}
