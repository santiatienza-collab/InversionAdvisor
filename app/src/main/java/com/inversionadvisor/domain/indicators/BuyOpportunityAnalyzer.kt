package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Candle

enum class CandlestickPattern { HAMMER, SHOOTING_STAR, BULLISH_ENGULFING, BEARISH_ENGULFING, DOJI }
enum class MarketTrapType { BULL_TRAP, BEAR_TRAP }

/**
 * "Análisis de opciones de compra": el mismo análisis para un stock suelto que "Top 10" hace
 * para todo el mercado — llama a LA MISMA función (Top10Calculator.score, con
 * requireSignal=false: aquí se analiza lo que se esté viendo, no se descarta nada).
 *
 * Antes esta pantalla tenía su PROPIA fórmula de riesgo/recompensa, distinta de la de "Top 10"
 * (diferentes factores, diferentes pesos) — daban números distintos para el mismo stock, lo
 * cual era incongruente. Ahora es una única fórmula en toda la app; lo único que cambia entre
 * las dos pantallas es qué datos tiene cada una a mano en su contexto (aquí se calculan
 * tendencia/momentum/agotamiento sobre la marcha con las mismas velas que ya se cargan para la
 * gráfica; el sector en auge no se conoce aquí de forma barata — ver parámetro sectorInFavor
 * más abajo — así que ese factor concreto puede quedar como "sin dato" en esta pantalla aunque
 * sí lo tenga Top 10, que sí lo trae ya calculado del escaneo del mercado).
 */
object BuyOpportunityAnalyzer {

    data class Analysis(
        val sectorName: String?,
        val rewardScore: Double,
        val riskScore: Double,
        val riskRewardRatio: Double,
        val rsi14: Double?,
        val stockPe: Double?,
        val sectorAveragePe: Double?,
        val declineFromRecentHighPercent: Double?,
        val significantDecline: Boolean,
        val exhaustionDetected: Boolean,
        val exhaustionConfidence: Int?,
        val exhaustionReasons: List<String>,
        val volumeRatio: Double?,
        val unusualVolume: Boolean,
        val stockVolatilityRatio: Double?,
        val unusualVolatility: Boolean,
        val percentFromYearHigh: Double?,
        val nearCeiling: Boolean,
        val percentAboveYearLow: Double?,
        val nearFloor: Boolean,
        val nearestSupportPercent: Double?,
        val nearestResistancePercent: Double?,
        val candlestickPattern: CandlestickPattern?,
        val trap: MarketTrapType?,
        val summary: List<String>,
        val factors: List<Top10Factor>,
        val overallComment: String,
        /** El párrafo del analista, tal cual lo genera Top10Calculator — se añade para que
         *  Top10Repository pueda llamar a analyze() directamente (ver más abajo) y reutilizar
         *  este mismo texto en vez de generar el suyo por separado. */
        val analystSummary: String,
        /** Desglose línea a línea de recompensa/riesgo — para el bocadillo informativo al tocar esos valores. */
        val rewardBreakdown: List<String>,
        val riskBreakdown: List<String>,
        /** Penalizaciones APARTE activadas (SMA50, aceleración de caída, resultados
         *  trimestrales, posible trampa de toros) — la interfaz las pinta como aviso en neón
         *  ROJO intermitente. */
        val penaltyWarnings: List<String> = emptyList(),
        /** Bonos APARTE activados (divergencia momentum/precio favorable, cruz dorada
         *  SMA20/SMA50) — la interfaz las pinta como aviso en neón VERDE intermitente. */
        val bonusWarnings: List<String> = emptyList(),
        /** Info estructurada (fechas + precios) del doble techo/suelo detectado — pedido
         *  expresamente así para dibujar los dos círculos en el gráfico. null si no hay patrón. */
        val doubleTopBottomResult: DoubleTopBottomResult? = null,
        /** Patrón de Hombro-Cabeza-Hombro detectado sobre las mismas velas de 1 año ya cargadas
         *  (ver UptrendDetector.detectHeadAndShoulders) — null si no se ha detectado ninguno. */
        val hchResult: UptrendDetector.HeadAndShouldersResult? = null,
        /** true si el beneficio de los últimos 12 meses es negativo — ver parámetro del mismo
         *  nombre en analyze(). Puramente informativo para la interfaz. */
        val hasNegativeTrailingEarnings: Boolean = false,
        /** Triple techo/suelo detectado (ver UptrendDetector.detectTripleTopOrBottom) — null si
         *  no se ha detectado ninguno. */
        val tripleTopBottomResult: UptrendDetector.TripleTopBottomResult? = null
    )

    /**
     * @param incomeGrowing ya no se usa en la fórmula nueva (no hay categoría de ingresos en el
     *   modelo de 7 categorías) — se mantiene el parámetro por compatibilidad con quien lo
     *   llame, pero no afecta al cálculo.
     * @param sectorEtf símbolo del ETF del sector (p. ej. "XLK") — para buscar su rango típico
     *   de PER en Symbols.SECTOR_TYPICAL_PE_RANGES, parte del criterio "Valoración".
     * @param sectorInFavor si el sector de este stock está en rotación favorable — opcional; no
     *   se calcula aquí por defecto porque exigiría traer la fuerza relativa de TODOS los
     *   sectores solo para ver un stock suelto (caro para algo que se abre a menudo). Si se
     *   tiene ya calculado en otro sitio, se puede pasar.
     */
    fun analyze(
        candles: List<Candle>,
        sectorName: String?,
        stockPe: Double?,
        sectorAveragePe: Double?,
        spyCandles: List<Candle>? = null,
        incomeGrowing: Boolean? = null,
        sectorInFavor: Boolean? = null,
        sectorEtf: String? = null,
        /** true si la próxima publicación de resultados trimestrales cae dentro de las
         *  próximas 3 semanas — ver MarketRepository.isEarningsWithinThreeWeeks(). No se
         *  calcula aquí porque requiere una petición de red aparte (v7/finance/quote), que esta
         *  función no hace por sí misma (recibe todo ya calculado, como el resto de parámetros). */
        earningsWithinThreeWeeks: Boolean = false,
        /** Solo para el registro de diagnóstico de "Volatilidad propia" (VolatilityDiagnostic) —
         *  opcional, no afecta al cálculo. */
        symbol: String? = null,
        /** Velas DIARIAS reales (opcional) para el cruce dorada/muerte SMA20-SMA50 — CORREGIDO:
         *  encontrado con ADP y PAYX mostrando "cruz dorada" cuando el gráfico (que sí usa
         *  precisión de días) mostraba el cruce real en mayo. El problema: sin esto, el cruce se
         *  calcula sobre `candles` (semanales), mucho más basto, que puede marcar SU PROPIO
         *  cruce en una fecha distinta al cruce real que se ve en el gráfico. Si se pasan velas
         *  diarias aquí (la ficha de un stock ya las tiene cargadas para el gráfico, sin coste
         *  extra), se usan ESAS para el cruce, con más precisión — si no se pasan (Top10 y
         *  Futuras Compras, que no piden velas diarias por cada candidato para no repetir el
         *  problema de rendimiento ya resuelto con el PER), se sigue usando `candles`
         *  (semanales) como respaldo, menos preciso pero sin coste de red añadido. */
        dailyCandlesForCross: List<Candle>? = null,
        /** Velas MENSUALES (opcional) para el patrón de bandera "a largo plazo" — ver
         *  MarketRepository.observeMonthlyCandlesForFlag/refreshMonthlyCandlesForFlagIfStale.
         *  Si no se pasan (p. ej. no se pidieron todavía, o falló la petición), esa mitad de la
         *  categoría "Bandera" sale como "sin dato" — no descarta ni penaliza, solo no cuenta
         *  ese lado (el de corto plazo, si hay datos, sigue contando igual). */
        monthlyCandlesForFlag: List<Candle>? = null,
        /** true si el beneficio de los últimos 12 meses es negativo (EPS ttm de Finviz) —
         *  pedido expresamente para reflejar cuándo stockPe en realidad viene del Forward P/E
         *  (fallback, ver FinvizPeParser.parseStockPe), ya que con beneficio negativo no existe
         *  un trailing P/E real que mostrar. Caso real: Lumentum. Puramente informativo — no
         *  cambia ningún cálculo de puntuación, solo se propaga hasta la interfaz. */
        hasNegativeTrailingEarnings: Boolean = false,
        /** NUEVO — pedido expresamente (mejoras #1 y #3, opción C), conectadas ya en
         *  scanner-cli — ver Top10Calculator.score() para el detalle exacto de cuándo suman o
         *  restan. null = sin dato, no se aplica nada. */
        benchmarkYearChangePercent: Double? = null,
        sectorDeclinePercent: Double? = null
    ): Analysis? {
        if (candles.size < 15) return null

        val rsi14 = TechnicalAnalysis.calculateRsi(candles).lastOrNull()
        val volumeRatio = TechnicalAnalysis.volumeRatio(candles)
        val stockVolatilityRatio = TechnicalAnalysis.ownVolatilityRatio(candles, debugSymbol = symbol)
        val exhaustion = ExhaustionDetector.detect(candles)
        val levels = TechnicalAnalysis.detectSupportResistanceLevels(candles)
        val currentPrice = candles.last().close
        val yearHigh = candles.maxOf { it.high }
        val yearLow = candles.minOf { it.low }
        val percentFromYearHigh = if (yearHigh > 0) (currentPrice - yearHigh) / yearHigh * 100 else null
        val percentAboveYearLow = if (yearLow > 0) (currentPrice - yearLow) / yearLow * 100 else null
        val pattern = TechnicalAnalysis.detectCandlestickPattern(candles)
        val trap = TechnicalAnalysis.detectTrap(candles, levels)

        // Patrón de bandera — corto plazo sobre velas DIARIAS (reutiliza dailyCandlesForCross,
        // la MISMA serie que ya se pide para el cruce SMA20/SMA50 con precisión, sin ninguna
        // petición nueva aparte), largo plazo sobre velas MENSUALES (nuevas, ver
        // monthlyCandlesForFlag). Ver UptrendDetector.detectFlagPattern.
        val shortTermFlagPattern = dailyCandlesForCross?.let { UptrendDetector.detectFlagPattern(it) }
        val longTermFlagPattern = monthlyCandlesForFlag?.let { UptrendDetector.detectFlagPattern(it) }
        val consolidatedBearishMonthsCount = monthlyCandlesForFlag?.let { UptrendDetector.countBearishMonthsInLast6(it, debugSymbol = symbol) }
        // Doble techo/doble suelo — usa las MISMAS velas semanales (candles) ya cargadas para
        // el resto de la fórmula, sin pedir ningún dato nuevo.
        val doubleTopBottomResult = UptrendDetector.detectDoubleTopOrBottom(candles, debugSymbol = symbol)

        val resistancesAbove = levels.filter { it.type == LevelType.RESISTANCE && it.price >= currentPrice }
        val supportsBelow = levels.filter { it.type == LevelType.SUPPORT && it.price <= currentPrice }
        val nearestResistancePercent = resistancesAbove.minByOrNull { it.price }?.let { (it.price - currentPrice) / currentPrice * 100 }
        val nearestSupportPercent = supportsBelow.maxByOrNull { it.price }?.let { (currentPrice - it.price) / currentPrice * 100 }

        val declinePercent = exhaustion?.declinePercentFromRecentHigh
        val significantDecline = declinePercent != null && declinePercent >= Top10Calculator.MIN_DECLINE_PERCENT
        val unusualVolume = volumeRatio != null && volumeRatio >= 1.5
        val unusualVolatility = stockVolatilityRatio != null && stockVolatilityRatio >= 1.5
        val nearCeiling = percentFromYearHigh != null && percentFromYearHigh >= -3.0
        val nearFloor = percentAboveYearLow != null && percentAboveYearLow <= 3.0

        // Tendencia + corrección a corto plazo + MACD — mismos detectores que usa
        // Top10Repository, calculados aquí sobre la marcha con las mismas velas de la gráfica.
        val uptrendSignal = UptrendDetector.evaluate(candles)
        val shortTermPullback = ExhaustionDetector.detect(
            candles,
            minDeclinePercent = Top10Calculator.MIN_SHORT_TERM_DECLINE_PERCENT,
            lookbackForHigh = Top10Calculator.SHORT_TERM_LOOKBACK_WEEKS
        )
        val macd = TechnicalAnalysis.calculateMacd(candles)
        val sectorTypicalPeRange = sectorEtf?.let { com.inversionadvisor.domain.model.Symbols.SECTOR_TYPICAL_PE_RANGES[it] }
        val shortTermBullish = UptrendDetector.isClearlyBullishShortTerm(candles)
        val declineAccelerating = UptrendDetector.isDeclineAcceleratingClearly(candles)
        // Si hay velas diarias disponibles (ficha de un stock), se usan para el cruce con más
        // precisión — si no, se cae a las semanales (Top10/Futuras Compras, sin coste extra).
        // 10 días ≈ 2 semanas de mercado (5 sesiones/semana) — misma ventana real de tiempo que
        // las 2 velas semanales de respaldo, solo que contada en el granulado correcto.
        val crossSourceCandles = if (dailyCandlesForCross != null && dailyCandlesForCross.size >= 51) dailyCandlesForCross else candles
        val usingDailyPrecision = crossSourceCandles === dailyCandlesForCross
        val deathCrossDate = UptrendDetector.findSma20CrossedBelowSma50Date(crossSourceCandles, lookbackPeriods = if (usingDailyPrecision) 10 else 2)
        val goldenCrossDate = UptrendDetector.findSma20CrossedAboveSma50Date(crossSourceCandles, lookbackPeriods = if (usingDailyPrecision) 10 else 2)
        val momentumPriceDivergence = UptrendDetector.detectMomentumPriceDivergence(candles)
        // Hombro-Cabeza-Hombro — sobre las MISMAS velas de 1 año ya cargadas para el resto de la
        // fórmula, sin petición de red adicional (ver decisión expresa: en Top10/Futuras Compras
        // se acepta la ventana de 1 año en vez de los 5 años completos, por coste de red al
        // escanear cientos de candidatos a la vez).
        val hchResult = UptrendDetector.detectHeadAndShoulders(candles)
        val tripleTopBottomResult = UptrendDetector.detectTripleTopOrBottom(candles)

        // LA MISMA función que usa Top10 — ver cabecera de la clase.
        val scored = Top10Calculator.score(
            trendQuality = uptrendSignal?.trendQuality,
            yearChangePercent = uptrendSignal?.yearChangePercent,
            aboveTrendSma = uptrendSignal?.aboveTrendSma,
            isSectorInFavor = sectorInFavor,
            longTermDecline = exhaustion,
            shortTermPullback = shortTermPullback,
            rsi14 = rsi14,
            volumeRatio = volumeRatio,
            stockVolatilityRatio = stockVolatilityRatio,
            stockPe = stockPe,
            sectorAveragePe = sectorAveragePe,
            sectorTypicalPeRange = sectorTypicalPeRange,
            percentFromYearHigh = percentFromYearHigh,
            nearestSupportPercent = nearestSupportPercent,
            nearestResistancePercent = nearestResistancePercent,
            candlestickPattern = pattern,
            marketTrap = trap,
            macd = macd,
            shortTermBullish = shortTermBullish,
            declineAccelerating = declineAccelerating,
            deathCrossDate = deathCrossDate,
            earningsWithinThreeWeeks = earningsWithinThreeWeeks,
            momentumPriceDivergence = momentumPriceDivergence,
            goldenCrossDate = goldenCrossDate,
            shortTermFlagPattern = shortTermFlagPattern,
            longTermFlagPattern = longTermFlagPattern,
            consolidatedBearishMonthsCount = consolidatedBearishMonthsCount,
            doubleTopBottomResult = doubleTopBottomResult,
            hchResult = hchResult,
            hasLongTermUptrend = uptrendSignal != null,
            hasLongTermDowntrend = UptrendDetector.evaluateDowntrend(candles),
            tripleTopBottomResult = tripleTopBottomResult,
            benchmarkYearChangePercent = benchmarkYearChangePercent,
            sectorDeclinePercent = sectorDeclinePercent,
            requireSignal = false
        ) ?: return null

        val summary = mutableListOf<String>()
        if (significantDecline) summary += "Caída de ${"%.0f".format(declinePercent)}% desde el máximo reciente (supera el 15%)"
        if (exhaustion != null && exhaustion.detected) summary += "Señal de agotamiento (confianza ${exhaustion.confidenceScore}%)"
        if (unusualVolume) summary += "Volumen fuera de lo normal (${"%.0f".format((volumeRatio ?: 0.0) * 100)}% de la media)"
        if (unusualVolatility) summary += "Volatilidad propia fuera de lo normal para este stock"
        if (nearCeiling) summary += "Muy cerca de su máximo del año (techo)"
        if (nearFloor) summary += "Muy cerca de su mínimo del año (suelo)"
        when (trap) {
            MarketTrapType.BULL_TRAP -> summary += "Posible trampa alcista: rompió una resistencia pero ya ha vuelto a cerrar por debajo"
            MarketTrapType.BEAR_TRAP -> summary += "Posible trampa bajista: rompió un soporte pero ya ha recuperado por encima"
            null -> {}
        }
        when (pattern) {
            CandlestickPattern.HAMMER -> summary += "Vela de martillo (posible giro al alza)"
            CandlestickPattern.SHOOTING_STAR -> summary += "Vela de estrella fugaz (posible giro a la baja)"
            CandlestickPattern.BULLISH_ENGULFING -> summary += "Envolvente alcista"
            CandlestickPattern.BEARISH_ENGULFING -> summary += "Envolvente bajista"
            CandlestickPattern.DOJI -> summary += "Doji (indecisión)"
            null -> {}
        }

        // Vuelta a la escala 0-100 — 4 niveles, en línea con los 4 colores de la interfaz.
        val overallComment = when {
            scored.combinedScore >= 75 -> "Relación riesgo/recompensa muy favorable — la recompensa pesa claramente más que el riesgo detectado."
            scored.combinedScore >= 50 -> "Relación favorable — pesa algo más la recompensa que el riesgo."
            scored.combinedScore >= 25 -> "Relación desfavorable — pesa algo más el riesgo que la recompensa."
            else -> "Relación muy desfavorable — el riesgo y las señales en contra pesan claramente más que la recompensa. Mala inversión según esta fórmula."
        }

        return Analysis(
            sectorName = sectorName,
            rewardScore = scored.rewardScore,
            riskScore = scored.riskScore,
            riskRewardRatio = scored.combinedScore,
            rsi14 = rsi14,
            stockPe = stockPe,
            sectorAveragePe = sectorAveragePe,
            declineFromRecentHighPercent = declinePercent,
            significantDecline = significantDecline,
            exhaustionDetected = exhaustion?.detected ?: false,
            exhaustionConfidence = exhaustion?.confidenceScore,
            exhaustionReasons = exhaustion?.reasons ?: emptyList(),
            volumeRatio = volumeRatio,
            unusualVolume = unusualVolume,
            stockVolatilityRatio = stockVolatilityRatio,
            unusualVolatility = unusualVolatility,
            percentFromYearHigh = percentFromYearHigh,
            nearCeiling = nearCeiling,
            percentAboveYearLow = percentAboveYearLow,
            nearFloor = nearFloor,
            nearestSupportPercent = nearestSupportPercent,
            nearestResistancePercent = nearestResistancePercent,
            candlestickPattern = pattern,
            trap = trap,
            summary = summary,
            factors = scored.factors,
            overallComment = overallComment,
            analystSummary = scored.analystSummary,
            rewardBreakdown = scored.rewardBreakdown,
            riskBreakdown = scored.riskBreakdown,
            penaltyWarnings = scored.penaltyWarnings,
            bonusWarnings = scored.bonusWarnings,
            doubleTopBottomResult = scored.doubleTopBottomResult,
            hchResult = scored.hchResult,
            hasNegativeTrailingEarnings = hasNegativeTrailingEarnings,
            tripleTopBottomResult = scored.tripleTopBottomResult
        )
    }
}
