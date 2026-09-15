package com.inversionadvisor.domain.model

import com.inversionadvisor.domain.indicators.AllTimeHighInfo
import com.inversionadvisor.domain.indicators.ExhaustionSignal
import com.inversionadvisor.domain.indicators.SupportResistanceLevel

/**
 * Ficha técnica de un símbolo (stock o índice) pensada para mostrarse a nivel
 * informativo en su pantalla de detalle: dónde está respecto a su máximo
 * histórico y cuáles son sus zonas de soporte/resistencia más relevantes.
 */
data class TechnicalProfile(
    val symbol: String,
    val allTimeHighInfo: AllTimeHighInfo?,
    val supportResistanceLevels: List<SupportResistanceLevel>,
    val exhaustionSignal: ExhaustionSignal?
)

/**
 * Candidato con tendencia alcista clara en el último año (ver UptrendDetector),
 * criterio principal de las pestañas de mercado del screener (S&P 500 /
 * Nasdaq-100 / IBEX 35): filtro menos estricto que "10 semanas alcistas",
 * pensado para listar TODOS los stocks del universo con una trayectoria
 * de fondo alcista, no solo los que están en plena racha ahora mismo.
 */
data class UptrendCandidate(
    val symbol: String,
    val name: String,
    val sectorEtf: String,
    val sectorName: String,
    val indexName: String,
    val yearChangePercent: Double,
    val trendQuality: Double,
    /** RSI 14 del último cierre en el momento del escaneo — null si no se pudo calcular. */
    val rsi14: Double? = null,
    /** Volumen de la última vela ÷ media de las 20 anteriores — null si no se pudo calcular. */
    val volumeRatio: Double? = null,
    /** Si el sector de este stock está en rotación favorable (fuerza relativa media > 0 frente al S&P 500) en el momento del escaneo. */
    val isSectorInFavor: Boolean = false
) {
    /**
     * NUEVO — pedido expresamente: "Valores Alcistas" (antes "Tendencia alcista") ahora muestra
     * la puntuación completa con el MISMO formato que "Futuras compras" — misma función
     * Top10Calculator.score() que usan Top10 y la ficha de cada stock. Espejo exacto de
     * BuyOpportunity.unifiedScore() de más abajo, con los mismos parámetros opcionales
     * (PER/patrones/etc. en directo, calculados por la propia tarjeta) — la única diferencia real
     * es que aquí trendQuality/yearChangePercent/aboveTrendSma SÍ vienen ya guardados del
     * escaneo (por eso "Tendencia" nunca sale "sin dato" aquí, a diferencia de en
     * BuyOpportunity), y longTermDecline se pasa aparte porque UptrendCandidate no lo guarda.
     */
    fun unifiedScore(
        aboveTrendSma: Boolean? = null,
        longTermDecline: ExhaustionSignal? = null,
        stockPe: Double? = null,
        sectorAveragePe: Double? = null,
        sectorTypicalPeRange: Pair<Double, Double>? = null,
        stockVolatilityRatio: Double? = null,
        percentFromYearHigh: Double? = null,
        nearestSupportPercent: Double? = null,
        nearestResistancePercent: Double? = null,
        candlestickPattern: com.inversionadvisor.domain.indicators.CandlestickPattern? = null,
        marketTrap: com.inversionadvisor.domain.indicators.MarketTrapType? = null,
        macd: com.inversionadvisor.domain.indicators.TechnicalAnalysis.MacdResult? = null,
        shortTermBullish: Boolean? = null,
        declineAccelerating: Boolean = false,
        deathCrossDate: String? = null,
        earningsWithinThreeWeeks: Boolean = false,
        momentumPriceDivergence: com.inversionadvisor.domain.indicators.MomentumPriceDivergence = com.inversionadvisor.domain.indicators.MomentumPriceDivergence.NONE,
        goldenCrossDate: String? = null,
        shortTermFlagPattern: com.inversionadvisor.domain.indicators.FlagPattern? = null,
        longTermFlagPattern: com.inversionadvisor.domain.indicators.FlagPattern? = null,
        consolidatedBearishMonthsCount: Int? = null,
        doubleTopBottomResult: com.inversionadvisor.domain.indicators.DoubleTopBottomResult = com.inversionadvisor.domain.indicators.DoubleTopBottomResult(com.inversionadvisor.domain.indicators.DoubleTopBottomPattern.NONE),
        hchResult: com.inversionadvisor.domain.indicators.UptrendDetector.HeadAndShouldersResult = com.inversionadvisor.domain.indicators.UptrendDetector.HeadAndShouldersResult(com.inversionadvisor.domain.indicators.UptrendDetector.HeadAndShouldersState.NONE),
        tripleTopBottomResult: com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomResult = com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomResult(com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomPattern.NONE),
        benchmarkYearChangePercent: Double? = null,
        sectorDeclinePercent: Double? = null,
        /** CORREGIDO — fallo real detectado (Repsol/APA con puntuación muy distinta entre esta
         *  tarjeta y la ficha del stock): antes se usaban SIEMPRE trendQuality/yearChangePercent/
         *  isSectorInFavor GUARDADOS del último escaneo (posiblemente de horas atrás), mientras
         *  que la ficha del stock los recalculaba en directo — y hasLongTermUptrend estaba
         *  FIJADO a true siempre (venía de cuando la selección de esta lista era por tendencia
         *  genuina; ahora que es por puntuación, un candidato puede estar aquí sin tener de
         *  verdad una tendencia alcista confirmada). Estos 4 overrides, si se pasan, sustituyen
         *  a los valores guardados por unos recién calculados — null (por defecto) mantiene el
         *  comportamiento de antes, por compatibilidad.
         */
        trendQualityFresco: Double? = null,
        yearChangePercentFresco: Double? = null,
        isSectorInFavorFresco: Boolean? = null,
        hasLongTermUptrendFresco: Boolean? = null,
        /** CORREGIDO — mismo tipo de fallo encontrado con MTB/Endesa/ArcelorMittal tras el
         *  primer arreglo: rsi14/volumeRatio también se leían SIEMPRE del último escaneo
         *  guardado (this.rsi14/this.volumeRatio), y shortTermPullback (el "Giro" a corto
         *  plazo, 12 semanas) ni siquiera se calculaba — se pasaba fijo a null, mientras que la
         *  ficha del stock SÍ lo calcula (ExhaustionDetector.detect con lookbackForHigh=12).
         *  Igual que los 4 anteriores: null (por defecto) mantiene el comportamiento de antes. */
        rsi14Fresco: Double? = null,
        volumeRatioFresco: Double? = null,
        shortTermPullbackFresco: ExhaustionSignal? = null
    ): com.inversionadvisor.domain.indicators.Top10Calculator.ScoredCandidate? =
        com.inversionadvisor.domain.indicators.Top10Calculator.score(
            trendQuality = trendQualityFresco ?: trendQuality,
            yearChangePercent = yearChangePercentFresco ?: yearChangePercent,
            aboveTrendSma = aboveTrendSma,
            isSectorInFavor = isSectorInFavorFresco ?: isSectorInFavor,
            longTermDecline = longTermDecline,
            shortTermPullback = shortTermPullbackFresco,
            rsi14 = rsi14Fresco ?: rsi14,
            volumeRatio = volumeRatioFresco ?: volumeRatio,
            stockVolatilityRatio = stockVolatilityRatio,
            stockPe = stockPe,
            sectorAveragePe = sectorAveragePe,
            sectorTypicalPeRange = sectorTypicalPeRange,
            percentFromYearHigh = percentFromYearHigh,
            nearestSupportPercent = nearestSupportPercent,
            nearestResistancePercent = nearestResistancePercent,
            candlestickPattern = candlestickPattern,
            marketTrap = marketTrap,
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
            hasLongTermUptrend = hasLongTermUptrendFresco ?: true,
            tripleTopBottomResult = tripleTopBottomResult,
            benchmarkYearChangePercent = benchmarkYearChangePercent,
            sectorDeclinePercent = sectorDeclinePercent,
            requireSignal = false
        )
}

/** Metadatos del último escaneo de UN mercado (una pestaña: S&P 500 / Nasdaq-100 / IBEX 35). */
data class ScreenerRunMeta(
    val indexName: String,
    val lastRunEpochMillis: Long,
    val symbolsScanned: Int,
    val totalSymbols: Int
)

/** Estado de auto-actualización de un índice del universo (S&P 500, Nasdaq-100, IBEX 35). */
data class StockIndexMeta(
    val indexName: String,
    val displayName: String,
    val updatedAtEpochMillis: Long,
    val count: Int
)
data class BuyOpportunity(
    val symbol: String,
    val name: String?,
    val sectorEtf: String?,
    val sectorName: String?,
    val indexName: String,
    val isSectorInFavor: Boolean,
    val exhaustionSignal: ExhaustionSignal,
    val allTimeHighInfo: AllTimeHighInfo?,
    val riskLevel: RiskLevel,
    /** RSI 14 del último cierre en el momento del escaneo — null si no se pudo calcular. */
    val rsi14: Double? = null,
    /** Volumen de la última vela ÷ media de las 20 anteriores — null si no se pudo calcular. */
    val volumeRatio: Double? = null
) {
    /** Prioridad de la oportunidad: sector en auge + señal fuerte pesa más. */
    val priorityScore: Int
        get() {
            var score = exhaustionSignal.confidenceScore
            if (isSectorInFavor) score += 20
            return score.coerceIn(0, 100)
        }

    /**
     * Puntuación con la fórmula unificada (Top10Calculator, las mismas 7 categorías que usan
     * Top10 y la ficha de cada stock) — con los datos LIGEROS que ya trae este objeto del
     * escaneo (RSI, volumen, sector en auge, señal de agotamiento), sin PER/velas frescas (eso
     * saldría carísimo para toda una lista de golpe). Se usa tanto para el orden de la lista
     * ("Futuras compras" ordenado de más a menos puntuación) como para el número que se
     * muestra en cada tarjeta — la MISMA función en los dos sitios, para que no puedan
     * desincronizarse entre sí.
     */
    /**
     * @param stockPe/sectorAveragePe/sectorTypicalPeRange opcionales — si se pasan (PER real
     *   del stock, obtenido aparte por la tarjeta que lo muestra), la categoría "Valoración"
     *   entra en el cálculo; si no, sale "sin dato" (comportamiento de antes, usado para el
     *   orden de la lista, que no espera a ningún PER para no ser lento).
     */
    fun unifiedScore(
        stockPe: Double? = null,
        sectorAveragePe: Double? = null,
        sectorTypicalPeRange: Pair<Double, Double>? = null,
        stockVolatilityRatio: Double? = null,
        percentFromYearHigh: Double? = null,
        nearestSupportPercent: Double? = null,
        nearestResistancePercent: Double? = null,
        candlestickPattern: com.inversionadvisor.domain.indicators.CandlestickPattern? = null,
        marketTrap: com.inversionadvisor.domain.indicators.MarketTrapType? = null,
        macd: com.inversionadvisor.domain.indicators.TechnicalAnalysis.MacdResult? = null,
        shortTermBullish: Boolean? = null,
        declineAccelerating: Boolean = false,
        deathCrossDate: String? = null,
        earningsWithinThreeWeeks: Boolean = false,
        momentumPriceDivergence: com.inversionadvisor.domain.indicators.MomentumPriceDivergence = com.inversionadvisor.domain.indicators.MomentumPriceDivergence.NONE,
        goldenCrossDate: String? = null,
        shortTermFlagPattern: com.inversionadvisor.domain.indicators.FlagPattern? = null,
        longTermFlagPattern: com.inversionadvisor.domain.indicators.FlagPattern? = null,
        consolidatedBearishMonthsCount: Int? = null,
        doubleTopBottomResult: com.inversionadvisor.domain.indicators.DoubleTopBottomResult = com.inversionadvisor.domain.indicators.DoubleTopBottomResult(com.inversionadvisor.domain.indicators.DoubleTopBottomPattern.NONE),
        hchResult: com.inversionadvisor.domain.indicators.UptrendDetector.HeadAndShouldersResult = com.inversionadvisor.domain.indicators.UptrendDetector.HeadAndShouldersResult(com.inversionadvisor.domain.indicators.UptrendDetector.HeadAndShouldersState.NONE),
        tripleTopBottomResult: com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomResult = com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomResult(com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomPattern.NONE),
        /** NUEVO — pedido expresamente tras detectar que esta tarjeta (recálculo en directo,
         *  separado del JSON importado) no tenía estos dos datos, mientras que la ficha del
         *  stock sí — causaba incoherencia entre lo que se veía aquí y al entrar al stock. */
        benchmarkYearChangePercent: Double? = null,
        sectorDeclinePercent: Double? = null
    ): com.inversionadvisor.domain.indicators.Top10Calculator.ScoredCandidate? =
        com.inversionadvisor.domain.indicators.Top10Calculator.score(
            trendQuality = null,
            yearChangePercent = null,
            aboveTrendSma = null,
            isSectorInFavor = isSectorInFavor,
            longTermDecline = exhaustionSignal,
            shortTermPullback = null,
            rsi14 = rsi14,
            volumeRatio = volumeRatio,
            stockVolatilityRatio = stockVolatilityRatio,
            stockPe = stockPe,
            sectorAveragePe = sectorAveragePe,
            sectorTypicalPeRange = sectorTypicalPeRange,
            percentFromYearHigh = percentFromYearHigh,
            nearestSupportPercent = nearestSupportPercent,
            nearestResistancePercent = nearestResistancePercent,
            candlestickPattern = candlestickPattern,
            marketTrap = marketTrap,
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
            tripleTopBottomResult = tripleTopBottomResult,
            benchmarkYearChangePercent = benchmarkYearChangePercent,
            sectorDeclinePercent = sectorDeclinePercent,
            requireSignal = false
        )
}

/**
 * Una entrada del "Top 10" — ver Top10Calculator para el desglose completo de qué entra en
 * [combinedScore]. [analystSummary] está redactado como lo explicaría un analista, no solo
 * números sueltos.
 */
data class Top10Entry(
    val symbol: String,
    val name: String,
    val indexName: String,
    val sectorName: String?,
    val rewardScore: Double,
    val riskScore: Double,
    val combinedScore: Double,
    val analystSummary: String,
    /** Los mismos factores del cálculo, en formato esquemático — ver Top10Factor/FactorGrade. */
    val factors: List<com.inversionadvisor.domain.indicators.Top10Factor>,
    /** Desglose línea a línea de recompensa/riesgo — para el bocadillo informativo al tocar esos valores. */
    val rewardBreakdown: List<String>,
    val riskBreakdown: List<String>,
    /** Avisos en neón ROJO intermitente (penalizaciones aparte: SMA50, cruce de la muerte,
     *  resultados próximos, bandera bajista...) — ahora también se muestran en el propio panel
     *  de Top10, no solo en Futuras Compras y en la ficha del stock. */
    val penaltyWarnings: List<String> = emptyList(),
    /** Avisos en neón VERDE intermitente (bonos aparte: cruz dorada, bandera alcista,
     *  divergencia momentum/precio favorable...). */
    val bonusWarnings: List<String> = emptyList(),
    val rank: Int,
    val updatedAtEpochMillis: Long
)

