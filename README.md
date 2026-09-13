# Inversion Advisor — Fase 1: estructura base + capa de datos

## Qué incluye esta fase
- Proyecto Android (Kotlin + Jetpack Compose) con Gradle Kotlin DSL.
- Cliente Retrofit para **Twelve Data** (stocks, oro, VIX, ETFs sectoriales, índices mundiales).
- Cliente Retrofit para **CoinGecko** (Bitcoin, sin API key).
- Caché local con **Room** (`quotes`, `candles`) — la UI lee siempre de Room vía `Flow`,
  y `MarketRepository.refresh*()` es quien actualiza esa caché desde la red.
- `MainActivity` de prueba que refresca y pinta el VIX en pantalla, para verificar
  que la cadena API → Room → Compose funciona de punta a punta.

## Cómo arrancarlo
1. Crea una cuenta gratuita en https://twelvedata.com/ y copia tu API key.
2. Copia `local.properties.example` como `local.properties` y rellena:
   - `sdk.dir` con la ruta a tu Android SDK.
   - `TWELVE_DATA_API_KEY` con tu clave.
   - `FMP_API_KEY` con una clave gratuita de https://financialmodelingprep.com/developer/docs/ (ingresos netos de "Datos de Empresa"; sin uso actualmente, ver comentarios en MarketRepository — el plan gratuito ya no cubre el endpoint necesario).
   - `SEC_EDGAR_CONTACT_EMAIL` con tu email real (ingresos netos de EE.UU. sin scraping, vía la API pública de SEC EDGAR — no hace falta clave, solo identificarte).
3. Abre la carpeta `InversionAdvisor/` en Android Studio (Koala o superior) y deja que sincronice Gradle.
4. Ejecuta en un emulador o dispositivo — deberías ver el valor del VIX en pantalla.

## Estructura
```
data/remote/     -> DTOs y clientes Retrofit (Twelve Data, CoinGecko)
data/local/      -> Entidades y DAO de Room (caché)
data/repository/ -> MarketRepository: une red + caché, expone Flow<Quote>/Flow<Candle>
domain/model/    -> Modelos limpios usados por la UI (Quote, Candle, ChartRange, Symbols...)
di/              -> ServiceLocator (DI manual, migrable a Hilt más adelante)
```

## Módulo de análisis técnico (domain/indicators)
- `TechnicalAnalysis`: swing points, clustering de soportes/resistencias, máximo
  histórico dentro del rango cargado (ATH), RSI de Wilder, media móvil simple.
- `ExhaustionDetector`: para la sección "Futuras compras". No basta con una caída
  del 10-15%; combina varias señales (mínimos ascendentes, RSI en sobreventa,
  volumen creciente en la recuperación, % ya recuperado hacia la próxima
  resistencia) en un score de confianza 0-100.
- `TechnicalProfile` / `BuyOpportunity` (domain/model/ScreenerModels.kt): estructuras
  para mostrar, a nivel informativo, cuándo un stock o índice está en máximo
  histórico y dónde están sus soportes/resistencias más relevantes.

Nota: el ATH detectado está acotado al rango de velas que se le pase (p. ej. 5
años). Para un máximo histórico "de toda la vida" habría que pedir el
`outputsize` máximo disponible en Twelve Data para ese símbolo.

## Conversión de divisas (EUR/USD)
- `Currency` / `DisplayCurrency` (domain/model/Currency.kt): divisas soportadas y
  las dos de visualización que pidió el usuario.
- `ForexPairs`: pares que se refrescan (EUR/USD, GBP/USD, USD/JPY, USD/HKD),
  necesarios para poder pivotar cualquier divisa nativa a EUR o USD.
- `CurrencyConverter` (domain/indicators): convierte siempre pivotando sobre USD.
- `MarketRepository.refreshExchangeRates()` / `observeRatesToUsd()`: refresca y
  expone los tipos de cambio como `Flow<Map<Currency, Double>>`.
- `Quote.closeIn(target, ratesToUsd)`: da el precio de cierre ya convertido.

**Importante**: la conversión se aplica a instrumentos con precio monetario real
(acciones, oro, bitcoin). Los **índices bursátiles** (S&P500, IBEX35, Nikkei...)
se expresan en puntos de índice, no en una cantidad de dinero — convertir su
valor a EUR/USD no tiene sentido financiero, así que no se les aplica.

## Eficiencia de red y de código
El plan gratuito de Twelve Data tiene límites estrictos (800 peticiones/día, 8/min),
así que toda la capa de datos está diseñada para gastar el mínimo posible:

- **Batch siempre que se pueda**: `refreshMarketOverview()` agrupa VIX + oro + los
  11 ETFs sectoriales + los 8 índices mundiales + los 4 pares forex en **una sola**
  llamada a la API (`getQuoteBatch`), en vez de 20+ peticiones sueltas.
- **Cache-aware (stale-while-revalidate)**: `refreshQuoteIfStale` /
  `refreshQuoteBatchIfStale` / `refreshCandlesIfStale` comprueban primero la marca
  de tiempo en Room y solo llaman a la red si el dato ha caducado (`CacheConfig`:
  3 min para cotizaciones, 5 min para velas intradía, 1 hora para velas
  diarias/semanales). Si nada ha caducado, no se hace ninguna petición.
- **Refresco en background, no en cada pantalla**: `MarketRefreshWorker`
  (WorkManager) refresca el resumen de mercado cada 15 min de forma periódica;
  abrir/cerrar pantallas no dispara llamadas repetidas — la UI siempre lee
  primero de Room vía `Flow`.
- **Rate limiter de seguridad**: `RateLimitInterceptor` en el cliente OkHttp de
  Twelve Data limita a 7 peticiones/min (por debajo del límite real de 8) como
  red de seguridad, aunque la lógica de arriba ya minimiza las llamadas.
- **Velas cacheadas por símbolo+intervalo** (`candle_fetch_meta`): cada rango de
  gráfica (1D/1S/1M/1A/5A) se refresca de forma independiente y solo cuando
  corresponde, no se repiten peticiones si el usuario cambia de pestaña y vuelve.

## Limitación del plan gratuito: índices de EE.UU.
Confirmado en pruebas reales: `/indices?country=United States` devuelve **0
resultados** en el plan gratuito de Twelve Data — ni el VIX, ni el S&P 500, ni
el Dow Jones, ni el Nasdaq están disponibles como índice "crudo" en ese plan.
Los índices internacionales (IBEX, DAX, FTSE, Nikkei, Hang Seng) sí lo están.

**Solución aplicada**: usar ETFs líquidos como proxy, disponibles en el plan
gratuito igual que cualquier acción normal:
- VIX → `VIXY` (ProShares VIX Short-Term Futures ETF) — `Symbols.VIX_PROXY`
- S&P 500 → `SPY`
- Dow Jones → `DIA`
- Nasdaq → `QQQ` (sigue el Nasdaq-100, no el Composite exacto — es la aproximación estándar)

Si en el futuro se contrata un plan de pago con acceso a índices reales, basta
con cambiar estos símbolos en `Symbols` — el resto del código no cambia.

## Dashboard (ui/dashboard)
- `DashboardViewModel`: combina en un único `StateFlow` las cotizaciones de
  VIXY, SPY, los 11 ETFs sectoriales, el oro y los tipos de cambio; dispara
  `refreshMarketOverview()` (1 sola llamada batch) y el cálculo de rotación
  sectorial al arrancar.
- `DashboardScreen`: tarjetas en semáforo (verde/ámbar/rojo) para volatilidad,
  sentimiento de mercado (miedo/avaricia), liquidez, alcistas/bajistas y oro
  (con conversión EUR/USD en vivo), más el ranking de rotación sectorial de
  las últimas 2 semanas.
- Indicadores calculados en `domain/indicators/MarketSentimentCalculators.kt`
  y `SectorRotationCalculator.kt`:
  - **Volatilidad**: variación diaria de VIXY (proxy del VIX real, ver nota
    de la fase 3 sobre la limitación del plan gratuito).
  - **Miedo/avaricia**: índice propio 0-100 combinando volatilidad (40%),
    amplitud de sectores en verde (30%) y momentum de SPY (30%).
  - **Liquidez**: volumen relativo de SPY frente a su media.
  - **Alcistas/bajistas**: sectores en positivo vs negativo hoy (aproximación
    mientras no exista un screener con todos los componentes de un índice).
  - **Rotación sectorial**: variación de cada ETF sectorial en las últimas
    10 sesiones de trading, rankeada de mayor a menor.

## Fuentes externas del dashboard (fase 5)
Dos de los indicadores del dashboard usan fuentes externas en vez de un
cálculo propio, tal y como se pidió:

- **Miedo/avaricia**: el Fear & Greed Index real de CNN, vía el mismo
  endpoint no oficial que usa `edition.cnn.com/markets/fear-and-greed` para
  su propio gráfico (`data/remote/CnnFearGreedApi.kt`). Sin API key, con
  User-Agent de navegador (si no, CNN puede bloquear la petición) y su propio
  rate limiter conservador. Se cachea en Room (`fear_greed`) igual que el resto.
- **Volumen de mercado**: histórico diario del S&P 500 (`^GSPC`) desde el
  endpoint de gráficos no oficial de Yahoo Finance
  (`data/remote/YahooFinanceApi.kt`). La app calcula el volumen relativo
  (hoy vs media del último mes) — Yahoo solo da los datos crudos, el cálculo
  es nuestro. Se cachea en Room (`market_volume`).

**Importante**: ninguno de los dos es un endpoint oficial ni documentado por
CNN o Yahoo — son los mismos que usa la comunidad para scraping, y pueden
cambiar de forma o dejar de funcionar sin aviso. Si eso pasa, el dashboard no
se rompe: como todo lee de Room vía `Flow`, sigue mostrando el último valor
cacheado y el aviso de error de refresco arriba de la pantalla.

El resto de indicadores (volatilidad vía VIXY, alcistas/bajistas por
sectores, oro con conversión EUR/USD, rotación sectorial) siguen igual que
en la versión anterior, con Twelve Data.

## Corrección: error 429 por límite de créditos (no de peticiones)
Twelve Data cobra **créditos por símbolo**, no por petición HTTP: una sola
llamada batch a `/quote` con 16 símbolos (oro + 11 sectores + 4 forex, como
hacía `refreshMarketOverview()`) gasta 16 créditos de golpe, muy por encima
del límite de 8 créditos/min del plan gratuito — de ahí el 429, aunque desde
fuera pareciera "solo una petición".

**Corregido con dos cambios:**
- `CreditAwareRateLimitInterceptor` sustituye al `RateLimitInterceptor`
  simple para el cliente de Twelve Data: cuenta créditos reales (nº de
  símbolos en el parámetro `symbol`, separados por coma), no peticiones.
- `MarketRepository.refreshQuoteBatchIfStale()` trocea cualquier lista larga
  de símbolos en bloques de máximo 6 (`MAX_SYMBOLS_PER_BATCH`), para que
  ninguna petición individual necesite de entrada más créditos de los que
  caben en la ventana de un minuto.

**Nota**: la primera vez que se abre la app (o tras un cambio de esquema de
la base de datos, que borra la caché), el refresco inicial puede tardar
varios minutos en completarse — quote batches + las 13 llamadas de velas
sectoriales suman más créditos de los que da la ventana de un minuto, así que
el interceptor los espacia en vez de dispararlos todos de golpe. Es
esperable y no un error; en refrescos posteriores, con la mayoría de datos ya
frescos en caché, apenas se notará.

## Ajustes tras las primeras pruebas reales
- **Sectores ampliados**: se añadieron `SMH` (semiconductores) y `XLC`
  (comunicación/medios) como sectores propios en `Symbols.SECTOR_ETFS` — antes
  quedaban mezclados dentro de "Tecnología" (`XLK`), que ahora solo cubre
  software/hardware/servicios tech, no semiconductores. Son ahora 13 ETFs
  sectoriales en total.
- **Oro en EUR/USD arreglado**: Twelve Data no siempre rellena el campo
  `currency` para símbolos tipo par (XAU/USD) — se dedujo del propio símbolo
  como fallback (`inferCurrencyFromPairSymbol` en el repositorio). Sin esto,
  el botón de divisa nunca encontraba una divisa nativa para el oro.
- **Oro en onzas y en kg**: `GoldConversion.TROY_OUNCES_PER_KG` convierte el
  precio por onza troy (la unidad estándar de XAU/USD) a precio por kg.
- **Bitcoin y Ethereum** añadidos al dashboard, vía CoinGecko
  (`refreshCryptoQuotes()`), guardados como cotizaciones normales — así
  heredan gratis toda la caché y la conversión EUR/USD ya construidas.
- **Volumen de mercado quitado del panel principal** (a petición del
  usuario, se verá a nivel de cada stock más adelante). El código de
  `MarketRepository`/`MarketDao` para ello se deja intacto por si se reutiliza.
- **Rotación sectorial: fix del 429**. Dos cambios:
  1. `refreshMarketOverview()` ya no pide los índices mundiales (no se
     muestran en ningún sitio todavía) — menos créditos gastados de entrada.
  2. Las 13 llamadas de velas sectoriales ahora se espacian con una pausa fija
     de ~8.6s entre cada una (`SECTOR_FETCH_DELAY_MILLIS` en el ViewModel), en
     vez de depender solo del rate limiter reactivo. Tarda 1-2 minutos en
     completarse la primera vez, pero no debería volver a dar 429.

## Migración a Yahoo Finance (fuente principal del dashboard)
A petición del usuario, tras ver que Twelve Data obligaba a espaciar las 13
llamadas de velas sectoriales (~2 min por el límite de créditos del plan
gratuito), el dashboard se migró a Yahoo Finance como fuente principal:

- **VIX real, no proxy**: con Yahoo se puede pedir `^VIX` directamente — ya
  no hace falta el ETF `VIXY` como sustituto. `VolatilityAnalyzer.analyzeRealVix()`
  recupera el umbral clásico de 20 puntos que se pidió al principio del
  proyecto (`analyze()` con el proxy se deja como método legado, por si algún
  día hiciera falta volver a Twelve Data).
- **Oro**: `GC=F` (futuro del oro, Comex) en vez de `XAU/USD`.
- **Forex**: `EURUSD=X`, `GBPUSD=X`, `USDJPY=X`, `USDHKD=X` (formato Yahoo).
- **ETFs sectoriales**: mismos tickers (XLK, SMH, XLC, XLE...), Yahoo los
  soporta igual que Twelve Data.
- **Velas para la rotación sectorial**: `ChartRange.toYahooRangeAndInterval()`
  traduce cada rango a los parámetros de Yahoo. Sin límite de créditos
  documentado, las 13 llamadas ya no necesitan pausas artificiales entre
  sí — solo el rate limiter de red como red de seguridad (40 peticiones/min).
- **Twelve Data se deja intacto en el código**, sin usarse en el dashboard
  por ahora — disponible para cuando se construya el buscador de stocks, por
  si hace falta algún dato que Yahoo no cubra bien.
- **CNN Fear & Greed y CoinGecko (bitcoin/ethereum) no cambian** — ninguno
  de los dos dependía de Twelve Data.

## Próximas fases (según lo acordado)
1. ✅ Estructura base + capa de datos
2. ✅ Módulo de indicadores técnicos (soporte/resistencia, ATH, agotamiento de caída)
3. ✅ Conversión de divisas EUR/USD
4. ✅ Capa de eficiencia de red (batch, caché con TTL, worker periódico, rate limiter)
5. ✅ Dashboard con los indicadores en semáforo + selector EUR/USD + CNN Fear&Greed + volumen de Yahoo
6. Buscador de stocks con gráficas (1D/1S/1M/1A/5A) + ficha técnica visual
7. Screener completo (10 semanas alcistas / futuras compras) usando la rotación sectorial ya calculada

## Nota
Esta app es una herramienta de análisis técnico, no constituye asesoramiento
financiero ni garantiza resultados de inversión.
