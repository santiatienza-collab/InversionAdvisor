package com.inversionadvisor.data.local

/**
 * Cuánto tiempo se considera "fresco" cada tipo de dato antes de volver a
 * pedirlo a la API. Ajustado para exprimir el plan gratuito de Twelve Data
 * (800 peticiones/día, 8/min) sin perder datos relevantes.
 */
object CacheConfig {
    /** Cotizaciones (precio, VIX, sectores, índices, forex). */
    const val QUOTE_TTL_MILLIS = 3 * 60_000L // 3 min

    /** Velas intradía (1D/1S con intervalos de minutos). */
    /** Bajado de 5 a 1 min a petición expresa ("que el gráfico 1D se actualice lo antes que
     *  pueda") — Yahoo (fuente gratuita/no oficial) ya tiene su propio retraso de origen de
     *  ~15-20 min en horario de mercado (confirmado con documentación externa, no es algo que
     *  se pueda arreglar desde aquí sin cambiar a un proveedor de pago) — pedir más rápido que
     *  eso no trae datos más nuevos, PERO 1 min sigue siendo mucho más frecuente que ese
     *  retraso de origen, así que se recoge la actualización de Yahoo en cuanto esté
     *  disponible, sin esperar de más por este lado. */
    const val CANDLE_TTL_INTRADAY_MILLIS = 60_000L // 1 min

    /** Velas diarias/semanales (1M/1A/5A) — cambian mucho más despacio. */
    const val CANDLE_TTL_LONG_TERM_MILLIS = 60 * 60_000L // 1 hora
    /** Solo para las velas MENSUALES del patrón de bandera "a largo plazo" — un mes no cambia
     *  hasta que pasa un mes entero, así que no hace falta refrescarlo cada hora como el resto
     *  de velas. Con 24h de margen, un mismo día solo se paga el coste de pedirlas una vez,
     *  aunque se repita el escaneo de Top10/Futuras Compras varias veces esa jornada. */
    const val CANDLE_TTL_MONTHLY_MILLIS = 24 * 60 * 60_000L // 24 horas

    /** Sentimiento AAII: la encuesta solo se publica 1 vez/semana (jueves). */
    const val AAII_SENTIMENT_TTL_MILLIS = 12 * 60 * 60_000L // 12 horas

    /** Sparkline de cripto (7 días vía CoinGecko) — dato ilustrativo, no hace falta refrescarlo tan a menudo como el precio. */
    const val CRYPTO_SPARKLINE_TTL_MILLIS = 15 * 60_000L // 15 min

    /** Universo de índices (S&P 500, Nasdaq-100, IBEX 35) — cambia unas pocas veces al año, 7 días es de sobra. */
    const val INDEX_UNIVERSE_TTL_MILLIS = 7 * 24 * 60 * 60_000L // 7 días

    /**
     * Top ganadores/perdedores del banner rotatorio: implica consultar de
     * golpe el universo entero (SP500+NASDAQ100+IBEX35, varios cientos de
     * símbolos), así que se refresca con menos frecuencia que una cotización
     * normal para no machacar la API en cada apertura del panel.
     */
    const val MARKET_MOVERS_TTL_MILLIS = 10 * 60_000L // 10 min
    /** Refresco rápido "en tiempo real" — SOLO de los símbolos que ya están en el banner ahora
     *  mismo (~30, no los ~600 del universo completo), pedido expresamente así ("banner en
     *  tiempo real, con la variación de precio día a día"). Cada minuto es razonable: ~30
     *  símbolos caben de sobra dentro de cualquier límite de peticiones, sin repetir el
     *  escaneo completo del universo (que sigue con su propia cadencia de 10 min, arriba). */
    /** Refresco rápido "en tiempo real" — SOLO de los símbolos que ya están en el banner ahora
     *  mismo (~30, no los ~600 del universo completo), pedido expresamente así ("banner en
     *  tiempo real, con la variación de precio día a día"). Bajado de 1 a 2 min tras una
     *  sospecha de saturación de peticiones — Yahoo (fuente gratuita) ya tiene su propio
     *  retraso de origen de ~15-20 min, así que 2 min sigue siendo mucho más frecuente que eso,
     *  con más margen de seguridad frente al límite de peticiones. */
    const val MARKET_MOVERS_LIVE_TTL_MILLIS = 120_000L // 2 min

    /** PER (trailing P/E) de ETFs sectoriales y de stocks individuales — solo cambia con resultados trimestrales, TTL larga. */
    const val PE_RATIO_TTL_MILLIS = 6 * 60 * 60_000L // 6 horas
    /** Fecha de resultados trimestrales — no cambia de un día para otro salvo aviso puntual de
     *  la empresa, TTL larga (24h) para no pedir de más. */
    const val EARNINGS_DATE_TTL_MILLIS = 24 * 60 * 60_000L // 24 horas
}
