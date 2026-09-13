package com.inversionadvisor.domain.model

/** Símbolos de referencia usados en toda la app (ampliables). */
object Symbols {
    // Dashboard migrado a Yahoo Finance (sin límite de créditos, mucho más
    // rápido para las 13 llamadas de velas sectoriales que antes con Twelve
    // Data). Con Yahoo ya no hace falta el proxy VIXY: se usa el VIX real.
    const val VIX = "^VIX"
    /** Índice de correlación implícita a 3 meses de CBOE — mide cuánto se mueven a la vez las
     *  acciones del S&P 500 (0 = dispersión total/máxima independencia, 100 = correlación
     *  perfecta/todas moviéndose igual). Disponible gratis en Yahoo Finance, igual que el VIX. */
    const val CORRELATION_INDEX = "^COR3M"
    const val GOLD = "GC=F" // Futuro del oro (Comex) — el ticker de oro más fiable en Yahoo

    // Símbolos usados para guardar los precios de CoinGecko como cotizaciones normales
    const val BITCOIN = "BTC"
    const val ETHEREUM = "ETH"

    /**
     * Tickers de Yahoo Finance para las gráficas históricas de bitcoin/ethereum
     * (distintos de BITCOIN/ETHEREUM de arriba, que son la clave con la que se
     * guarda la cotización de CoinGecko) — se usan solo para pedir velas con
     * ChartRange (1S/1M/1A...) en la pantalla de detalle, igual que el oro.
     */
    const val BITCOIN_CHART = "BTC-USD"
    const val ETHEREUM_CHART = "ETH-USD"

    /** Benchmark de mercado (S&P 500, proxy ETF) usado para medir la fuerza RELATIVA de cada sector en la rotación sectorial. */
    const val SP500_BENCHMARK = "SPY"

    /** Un ETF/índice representativo por universo, para la gráfica de resumen al inicio de cada pestaña de Análisis. */
    val UNIVERSE_INDEX_SYMBOLS = mapOf(
        "SP500" to "SPY",
        "NASDAQ100" to "QQQ",
        "IBEX35" to "^IBEX",
        "RUSSELL2000" to "IWM"
    )

    /**
     * Divisas: no hay un "índice" propiamente dicho para cada moneda salvo el dólar (DXY, una
     * cesta ponderada frente a varias divisas) — para el resto se usa su cambio frente al
     * dólar, que es lo que de verdad se puede graficar. Sin verificar en vivo (primera vez que
     * se usan estos tickers en la app).
     */
    val CURRENCY_CHART_SYMBOLS = listOf(
        "Índice Dólar (DXY)" to "DX-Y.NYB",
        "Euro / Dólar" to "EURUSD=X",
        "Libra / Dólar" to "GBPUSD=X",
        "Dólar / Yen" to "JPY=X",
        "Dólar / Yuan" to "CNY=X"
    )

    /** Bonos USA — tampoco verificado en vivo. */
    val US_BOND_CHART_SYMBOLS = listOf(
        "Bono a 10 años (rendimiento)" to "^TNX",
        "Bono a 30 años (rendimiento)" to "^TYX",
        "Índice MOVE (volatilidad de bonos)" to "^MOVE"
    )

    // ETFs sectoriales para calcular rotación sectorial (mismos tickers en Yahoo)
    val SECTOR_ETFS = mapOf(
        "XLK" to "Tecnología",
        "SMH" to "Semiconductores",
        "XLC" to "Comunicación / Medios",
        "XLE" to "Energía",
        "XLF" to "Banca / Finanzas",
        "XLI" to "Industrial",
        "ITA" to "Defensa / Armamento",
        "XLV" to "Salud",
        "XLP" to "Consumo básico",
        "XLY" to "Consumo discrecional",
        "XLU" to "Utilities",
        "XLB" to "Materiales",
        "XLRE" to "Inmobiliario"
    )

    /**
     * Rangos típicos de PER por sector, dados directamente por el usuario (no verificados por
     * mi parte, ni sacados de ninguna fuente en vivo) — pensados como banda de referencia
     * ESTÁTICA para el criterio "Valoración" de la fórmula de ponderación nueva, complementando
     * (no sustituyendo) el PER medio del sector que ya se calcula en vivo vía Finviz.
     *
     * Solo cubre 8 de los 13 sectores de SECTOR_ETFS eran dados por el usuario — Semiconductores,
     * Comunicación, Defensa, Consumo básico e Inmobiliario se quedaban sin rango típico propio
     * (no los dio el usuario); "Consumo Cíclico/Discrecional" del usuario se asignó a XLY, y
     * "Financiero" a XLF. Materiales Básicos (XLB) e Industrial (XLI) añadidos después.
     *
     * AÑADIDOS por Claude (SMH, XLC, ITA, XLP, XLRE) — NO dados por el usuario, a diferencia del
     * resto de esta tabla: estimaciones razonables SIN VERIFICAR contra ninguna fuente en vivo,
     * para que ningún sector se quede sin ninguna referencia — corregir en cuanto se tenga un
     * dato real, igual que ya pasó con XLB (empezó en 4,4x, se corrigió a 24-25x). Aviso aparte
     * para XLRE (REITs): su PER suele salir distorsionado por cómo contabilizan la depreciación
     * inmobiliaria — la comparación aquí es más orientativa que en el resto de sectores.
     */
    val SECTOR_TYPICAL_PE_RANGES = mapOf(
        "XLK" to (28.0 to 40.0),  // Tecnología / Software
        "XLY" to (22.0 to 30.0),  // Consumo cíclico / discrecional
        "XLV" to (18.0 to 25.0),  // Salud / cuidado de la salud
        "XLU" to (15.0 to 16.0),  // Utilities
        "XLF" to (11.0 to 15.0),  // Financiero
        "XLE" to (13.0 to 21.0),  // Energía
        "XLB" to (24.0 to 25.0),  // Materiales Básicos — dato dado por el usuario
        "XLI" to (18.0 to 24.0),  // Industrial — ESTIMACIÓN de Claude, sin verificar
        "SMH" to (25.0 to 35.0),  // Semiconductores — ESTIMACIÓN de Claude, sin verificar
        "XLC" to (18.0 to 25.0),  // Comunicación / Medios — ESTIMACIÓN de Claude, sin verificar
        "ITA" to (18.0 to 24.0),  // Defensa / Armamento — ESTIMACIÓN de Claude, sin verificar
        "XLP" to (18.0 to 22.0),  // Consumo básico — ESTIMACIÓN de Claude, sin verificar
        "XLRE" to (30.0 to 40.0)  // Inmobiliario (REITs) — ESTIMACIÓN de Claude, sin verificar, ver aviso arriba
    )

    /**
     * PER medio de respaldo por sector, para cuando Finviz no trae dato en vivo para ese ETF
     * (reportado por el usuario: pasa con frecuencia con XLB/Materiales Básicos, p. ej. en
     * empresas de cobre como Southern Copper, y con algunos valores del IBEX 35) — SOLO se usa
     * si el PER en vivo (Finviz, ver MarketRepository.observeSectorPeRatios) falta; en cuanto
     * Finviz vuelve a traer el dato real de un sector, ese respaldo deja de usarse para él.
     *
     * Valor dado directamente por el usuario (no verificado por mi parte ni sacado de ninguna
     * fuente en vivo) SOLO para XLB — el resto (XLI, SMH, XLC, ITA, XLP, XLRE) son el punto medio
     * de sus rangos típicos de arriba, añadidos por Claude, sin verificar — corregir si se tiene
     * un dato real. Antes el resto de sectores se quedaba sin badge de PER cuando Finviz fallaba;
     * ahora al menos hay una referencia aproximada para los 13.
     */
    val SECTOR_PE_FALLBACK = mapOf(
        "XLB" to 24.5,  // Materiales Básicos — dato dado por el usuario
        "XLI" to 21.0,  // Industrial — ESTIMACIÓN de Claude
        "SMH" to 30.0,  // Semiconductores — ESTIMACIÓN de Claude
        "XLC" to 21.5,  // Comunicación / Medios — ESTIMACIÓN de Claude
        "ITA" to 21.0,  // Defensa / Armamento — ESTIMACIÓN de Claude
        "XLP" to 20.0,  // Consumo básico — ESTIMACIÓN de Claude
        "XLRE" to 35.0  // Inmobiliario (REITs) — ESTIMACIÓN de Claude
    )

    // Índices bursátiles mundiales — pendientes de migrar a Yahoo (^GSPC, ^DJI,
    // ^IXIC, ^IBEX, ^GDAXI, ^FTSE, ^N225, ^HSI); todavía no se muestran en
    // ningún sitio de la app, así que no se piden hasta que se construya esa
    // sección para no gastar peticiones de más.
    val WORLD_INDICES = mapOf(
        "SPY" to "S&P 500 (EE.UU., proxy ETF)",
        "QQQ" to "Nasdaq 100 (EE.UU., proxy ETF)",
        "DIA" to "Dow Jones (EE.UU., proxy ETF)",
        "IBEX" to "IBEX 35 (España)",
        "GDAXI" to "DAX (Alemania)",
        "FTSE" to "FTSE 100 (Reino Unido)",
        "N225" to "Nikkei 225 (Japón)",
        "HSI" to "Hang Seng (Hong Kong)"
    )
}

enum class RiskLevel { LOW, MEDIUM, HIGH }

/** Variación diaria de un stock del universo, para el banner de top ganadores/perdedores. */
data class MarketMover(
    val symbol: String,
    val name: String,
    val changePercent: Double,
    val price: Double
)

data class Quote(
    val symbol: String,
    val name: String?,
    val close: Double,
    val previousClose: Double,
    val changePercent: Double,
    val volume: Long,
    val averageVolume: Long,
    val fiftyTwoWeekLow: Double?,
    val fiftyTwoWeekHigh: Double?,
    val currency: Currency?,
    val updatedAtEpochMillis: Long
) {
    /** Volumen relativo al promedio de 20 días — >1.5 se considera actividad inusual. */
    val relativeVolume: Double
        get() = if (averageVolume > 0) volume.toDouble() / averageVolume else 0.0

    /**
     * Precio de cierre convertido a la divisa objetivo, o null si falta la divisa
     * nativa del instrumento o el tipo de cambio necesario todavía no está cacheado.
     * No se aplica a índices bursátiles (se expresan en puntos, no en dinero real).
     */
    fun closeIn(target: Currency, ratesToUsd: Map<Currency, Double>): Double? {
        val nativeCurrency = currency ?: return null
        return com.inversionadvisor.domain.indicators.CurrencyConverter.convert(
            amount = close,
            from = nativeCurrency,
            to = target,
            ratesToUsd = ratesToUsd
        )
    }
}

data class Candle(
    val datetime: String,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?
)

enum class ChartRange(val interval: String, val outputSize: Int, val label: String) {
    ONE_DAY("5min", 78, "1D"),      // ~78 velas de 5min en una sesión de 6.5h
    ONE_WEEK("30min", 65, "1S"),
    ONE_MONTH("1day", 22, "1M"),    // Yahoo se pide en velas de 1h y se agregan a 4h en la app (ver fetchCandlesWithFallback) — Yahoo no tiene un intervalo nativo de 4h.
    SIX_MONTHS("1week", 26, "6M"),  // NUEVO — velas semanales, igual que 1A/5A, para que SMA20/SMA50 tengan sentido sobre este rango.
    ONE_YEAR("1week", 52, "1A"),
    FIVE_YEARS("1week", 260, "5A")
}

/**
 * Clave única de caché/almacenamiento en Room para este rango. NO es lo
 * mismo que `interval`: ONE_YEAR y FIVE_YEARS comparten el mismo intervalo
 * de API ("1week"), así que usar `interval` como clave de caché hacía que
 * un rango pisara los datos cacheados del otro (mismo símbolo, misma
 * "interval", velas y cantidad de historial distintos). `name` del enum
 * (ONE_YEAR / FIVE_YEARS / ...) es único por rango, así cada uno tiene su
 * propio hueco en la caché sin colisionar.
 */
val ChartRange.cacheKey: String get() = name

/** Parámetros equivalentes de Yahoo Finance (range/interval) para cada ChartRange. */
fun ChartRange.toYahooRangeAndInterval(): Pair<String, String> = when (this) {
    ChartRange.ONE_DAY -> "1d" to "5m"
    ChartRange.ONE_WEEK -> "5d" to "30m"
    ChartRange.ONE_MONTH -> "1mo" to "1h"
    ChartRange.SIX_MONTHS -> "6mo" to "1wk"
    ChartRange.ONE_YEAR -> "1y" to "1wk"
    ChartRange.FIVE_YEARS -> "5y" to "1wk"
}

/** Resultado del cálculo de volatilidad, con su nivel de riesgo asociado. */
data class VixStatus(
    val value: Double,
    val changePercent: Double,
    val riskLevel: RiskLevel,
    val alertTriggered: Boolean, // true si el riesgo es alto
    /** true si `value` viene de un ETF proxy (p. ej. VIXY) y no del VIX real en puntos. */
    val isProxy: Boolean,
    val proxySymbol: String?,
    /** Etiqueta descriptiva del nivel — 5 niveles pedidos expresamente así, más granular que
     *  RiskLevel (solo 3): "Complacencia extrema" (<12), "Entorno normal, estable" (12-20),
     *  "Alarma moderada" (20-30), "Alarma alta (pánico)" (30-45), "Alarma extrema (crisis
     *  sistémica)" (>45). */
    val tierLabel: String = ""
)

/**
 * Índice de correlación implícita del S&P 500 (^COR3M) — pedido expresamente para el panel de
 * mercado, justo debajo del VIX, para detectar la dispersión entre acciones. Rango real 0-100
 * (no -1 a +1). Ver CorrelationAnalyzer para la tabla completa de rangos/significado (versión
 * corregida y ajustada a petición expresa): 0-15 dispersión extrema (sana), 15-30 dispersión
 * alta (estado normal saludable), 30-45 zona de transición, 45-65 correlación elevada (estrés
 * latente), 65-100 correlación muy alta (pánico/capitulación).
 */
data class CorrelationStatus(
    val value: Double,
    val changePercent: Double,
    val tierLabel: String
)

data class SectorPerformance(
    val etfSymbol: String,
    val sectorName: String,
    /** Variación y fuerza relativa (vs S&P 500) en cada horizonte calculado (2 semanas / 1 mes / 3 meses / 6 meses). */
    val returns: Map<RotationHorizon, SectorHorizonReturn>,
    /**
     * Media de la fuerza relativa vs S&P 500 en TODOS los horizontes — el
     * indicador clave para saber qué sectores están realmente "fuertes"
     * ahora mismo, no solo en una ventana corta y ruidosa. Se usa para
     * ordenar la lista (de más a menos fuerte).
     */
    val averageRelativeStrengthPercent: Double,
    /**
     * true si el sector bate al S&P 500 en TODOS los horizontes a la vez
     * (2S, 1M, 3M, 6M) — señal de una rotación sectorial sostenida, no un
     * repunte puntual de un par de semanas.
     */
    val isConsistentLeader: Boolean,
    /** PER medio (trailing P/E) del ETF sectorial — null si Yahoo no lo ha dado todavía o no aplica. */
    val averagePE: Double? = null
) {
    fun changePercent(horizon: RotationHorizon): Double = returns[horizon]?.changePercent ?: 0.0
    fun relativeStrengthPercent(horizon: RotationHorizon): Double = returns[horizon]?.relativeStrengthPercent ?: 0.0

    fun riskLevel(horizon: RotationHorizon): RiskLevel {
        val changePercent = changePercent(horizon)
        return when {
            changePercent <= -5.0 -> RiskLevel.HIGH
            changePercent < 0.0 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
}

/** Variación de precio y fuerza relativa (vs S&P 500) de un sector en un horizonte concreto. */
data class SectorHorizonReturn(
    val changePercent: Double,
    /** changePercent del sector menos el del S&P 500 en el mismo periodo: positivo = el sector está "ganando" al mercado. */
    val relativeStrengthPercent: Double
)

/**
 * Horizontes temporales de la rotación sectorial. Las rotaciones sectoriales
 * de verdad (no ruido de corto plazo) suelen tardar varios meses en
 * confirmarse, así que se calculan sobre la MISMA serie de velas semanales
 * (no hace falta pedir varios rangos distintos a la API).
 */
enum class RotationHorizon(val label: String, val weeksBack: Int) {
    ONE_MONTH("1 mes", 4),
    THREE_MONTHS("3 meses", 13),
    SIX_MONTHS("6 meses", 26)
}

/** Factor de conversión de onza troy a kilogramo, para mostrar el oro también por kg. */
object GoldConversion {
    /** 1 kg = 1000g / 31.1034768 g por onza troy. */
    const val TROY_OUNCES_PER_KG = 32.150747
}

/** Símbolos usados en las llamadas a Yahoo Finance (formato con ^ para índices). */
object YahooSymbols {
    const val SP500 = "^GSPC"
}

/**
 * Fear & Greed Index real de CNN (no el calculado internamente por la app).
 * history: hasta 4 puntos (1 mes, 1 semana, cierre anterior, actual) que ya
 * trae la propia respuesta de CNN — no hace falta ninguna llamada extra.
 */
data class CnnFearGreedStatus(
    val score: Int,
    val ratingRaw: String,
    val label: String,
    val riskLevel: RiskLevel,
    val history: List<Double>,
    val updatedAtEpochMillis: Long
)

/** Volumen de mercado relativo, calculado a partir del histórico diario de Yahoo Finance. */
data class MarketVolume(
    val symbol: String,
    val relativeVolume: Double,
    val todayVolume: Long,
    val averageVolume: Long,
    val updatedAtEpochMillis: Long
) {
    val riskLevel: RiskLevel
        get() = when {
            relativeVolume < 0.5 -> RiskLevel.HIGH
            relativeVolume < 0.8 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
}
