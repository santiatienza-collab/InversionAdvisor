package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Candle
import com.inversionadvisor.domain.model.RiskLevel

/**
 * Un factor individual del análisis de "momento idóneo para la venta"
 * (RSI, volumen, volatilidad propia, PER vs sector, cruce con la SMA20).
 * [riskLevel] aquí se lee como "señal de venta": HIGH = rojo (suma puntos,
 * señal de vender), LOW = verde (no suma). Ya no se usa MEDIUM a nivel de
 * factor individual — cada uno es binario (rojo o verde), como se pidió.
 *
 * [points]: los puntos que aporta ESTE factor a la puntuación final (0-100,
 * suma directa, no promedio) — ver SellTimingAnalyzer.analyze().
 *
 * [includedInScore]: false cuando el factor se muestra en gris solo porque
 * TODAVÍA no hay dato (p. ej. el PER no ha llegado de Finviz) — no porque
 * haya una señal real. Un factor sin dato aporta 0 puntos (igual que uno
 * con dato que no dispara), así que el aviso "sin dato" es solo visual.
 */
data class SellTimingFactor(
    val label: String,
    val valueText: String,
    val riskLevel: RiskLevel,
    val detail: String,
    val points: Int = 0,
    val includedInScore: Boolean = true
)

data class SellTimingAssessment(
    val factors: List<SellTimingFactor>,
    val overallLevel: RiskLevel,
    val overallScore: Int, // 0-100, SUMA directa de los puntos de cada factor MÁS los bonos aparte
    val summary: String,
    /** Etiquetas de los bonos "Teoría de la Opinión Contraria" que se han activado (Fear &
     *  Greed / AAII) — la interfaz las pinta como aviso en neón rojo intermitente. Vacía si
     *  ninguno se ha activado. */
    val contrarianWarnings: List<String> = emptyList(),
    /** Etiquetas del subgrupo NUEVO "Patrones técnicos de venta" (Hombro-Cabeza-Hombro, Doble
     *  Techo, Triple Techo, Divergencia Bajista Precio/RSI, Cruce de la Muerte SMA50/SMA200) que
     *  se han activado — la interfaz las pinta como aviso en neón rojo intermitente, igual que
     *  contrarianWarnings pero en lista aparte. Si se activó la regla de saturación (2+ de los
     *  5 patrones a la vez), se añade un aviso adicional indicándolo. Vacía si ninguno se ha
     *  activado. */
    val sellPatternWarnings: List<String> = emptyList()
)

/**
 * SEGUNDA VERSIÓN de esta fórmula, reescrita por completo a petición expresa — sustituye la
 * anterior (promedio de niveles de riesgo, con VIX como fuente de volatilidad) por una suma
 * directa de puntos, cada factor binario (rojo/verde):
 *
 *  Factor                       Condición para SUMAR (rojo)             Puntos
 *  RSI (14)                     ≥70 (sobrecompra) o ≤30 (sobreventa)     25
 *  Volumen de operaciones       Por encima de su media de 20 semanas     20
 *  Volatilidad propia           Por encima de su media (YA NO usa VIX)   20
 *  PER vs sector                Por encima del rango típico/media        20
 *  Cruce con la SMA20           Cruce descendente reciente               15
 *                                                                        ────
 *                                                                        100 máx.
 *
 * BONOS APARTE (Teoría de la Opinión Contraria) — a petición expresa, NO forman parte de la
 * tabla de arriba ni de sus pesos, se suman DESPUÉS a la puntuación ya calculada:
 *  Fear & Greed (CNN) = "Codicia"          -> +5
 *  Fear & Greed (CNN) = "Codicia extrema"  -> +15
 *  Sentimiento AAII alcista (el % más alto de los tres) -> +15
 * Los tres, con aviso en neón rojo intermitente en la interfaz (ver contrarianWarnings).
 *
 * SUBGRUPO NUEVO — "Patrones técnicos de venta" (pedido expresamente), 5 factores INDEPENDIENTES
 * de la tabla de 5 categorías de arriba (no comparten sus pesos), con su PROPIA regla de
 * saturación:
 *  Patrón                              Estado                    Puntos
 *  Hombro-Cabeza-Hombro (6m-5a)         En formación (amarillo)   +7
 *                                       Confirmado (rojo)         +15
 *  Doble Techo                          En formación (amarillo)   +7
 *                                       Confirmado (rojo)         +15
 *  Triple Techo                        En formación (amarillo)   +10
 *                                       Confirmado (rojo)         +20
 *  Divergencia Bajista Precio/RSI       Detectada (rojo)          +10
 *  Cruce de la Muerte SMA50/SMA200      Detectado (rojo)          +10
 *
 * REGLA DE SATURACIÓN (pedida expresamente): si 2 O MÁS de estos 5 patrones están activos a la
 * vez (en cualquiera de sus estados, no solo "confirmado"), el subtotal de este subgrupo se
 * SATURA a 100, en vez de sumar sus puntos individuales — lo que en la práctica lleva la
 * puntuación final a 100 (el máximo) al pasar por el coerceIn(0,100) de más abajo.
 *
 * El Hombro-Cabeza-Hombro aquí se detecta preferentemente sobre 5 AÑOS de velas semanales (ver
 * parámetro fiveYearCandles) — el MISMO patrón, detectado sobre otras velas (solo 1 año), RESTA
 * puntos en Stocks/Top10/Futuras Compras (ver Top10Calculator). El Doble Techo y el Triple Techo
 * también tienen su propia versión (con penalización, no bono) en Top10Calculator, sobre otras
 * velas. La Divergencia Bajista y el Cruce de la Muerte SMA50/SMA200 son EXCLUSIVOS de "Momento
 * idóneo para la venta", no afectan a ningún otro sitio.
 *
 * Puntuación final = (suma de los 5 factores + bonos aparte).coerceIn(0, 100). Semáforo FINAL,
 * independiente del color de cada factor:
 *   0-24  rojo    "No es momento de vender ahora"
 *   25-49 naranja
 *   50-74 amarillo "Buen momento para la venta"
 *   75-100 verde   "Momento idóneo para la venta"
 *
 * CAMBIO respecto a la versión anterior: la volatilidad ya NO usa el VIX (volatilidad general
 * del mercado) — ahora usa la volatilidad PROPIA del stock (TechnicalAnalysis.ownVolatilityRatio,
 * la misma que ya usa la fórmula de compra para su categoría "Riesgo") frente a su propia media
 * histórica, tal como se pidió.
 *
 * No pide datos por sí mismo: recibe las velas del stock ya cargadas y los indicadores ya
 * calculados en otra parte de la app, para no duplicar peticiones de red.
 */
object SellTimingAnalyzer {

    private const val VOLUME_LOOKBACK = 20
    private const val SMA_PERIOD = 20

    fun analyze(
        candles: List<Candle>,
        stockPe: Double? = null,
        sectorAveragePe: Double? = null,
        sectorTypicalPeRange: Pair<Double, Double>? = null,
        /** Puntuación 0-100 de CNN Fear & Greed — la clasificación (miedo/codicia extrema...)
         *  se calcula aquí mismo por umbrales de puntuación (CnnFearGreedRatingMapper.mapScore),
         *  pedido expresamente así en vez de fiarse de la etiqueta en inglés que da la propia
         *  CNN. Ver CnnFearGreedStatus.score. */
        fearGreedScore: Double? = null,
        aaiiBullishPercent: Double? = null,
        aaiiNeutralPercent: Double? = null,
        aaiiBearishPercent: Double? = null,
        /** Velas SEMANALES de hasta 5 años (ChartRange.FIVE_YEARS), opcional — para detectar el
         *  Hombro-Cabeza-Hombro en su ventana completa (6 meses-5 años) y el Cruce de la Muerte
         *  SMA50/SMA200 (SMA200 necesita ~200 semanas de histórico). Si no se pasan, ambas
         *  detecciones caen a `candles` (1 año) — el HCH sigue buscando dentro de esa ventana
         *  más corta, y el cruce SMA50/SMA200 sencillamente no tendrá datos suficientes y no
         *  disparará señal. */
        fiveYearCandles: List<Candle>? = null,
        /** Símbolo del stock, opcional — solo para el registro de diagnóstico
         *  "HchDiagnostic" (ver UptrendDetector.detectHeadAndShoulders). No afecta al cálculo. */
        symbol: String? = null
    ): SellTimingAssessment? {
        if (candles.size < 15) return null // RSI 14 necesita al menos 15 velas

        val rsi = rsiFactor(candles)
        val volume = volumeFactor(candles)
        val volatility = volatilityFactor(candles)
        val pe = peFactor(stockPe, sectorAveragePe, sectorTypicalPeRange)
        val smaCross = smaCrossFactor(candles)

        val factors = listOfNotNull(rsi, volume, volatility, pe, smaCross)
        if (factors.isEmpty()) return null

        // Suma directa de puntos — un factor "sin dato todavía" aporta 0, igual que uno con
        // dato que no dispara (ver comentario de SellTimingFactor.includedInScore).
        val baseScore = factors.sumOf { it.points }

        // Bonos APARTE (Teoría de la Opinión Contraria) — no tocan la tabla de 5 factores.
        // Clasificación por PUNTUACIÓN (0-25 miedo extremo, 26-44 miedo, 45-55 neutral, 56-75
        // codicia, 76-100 codicia extrema) — ver CnnFearGreedRatingMapper.mapScore.
        var bonusPoints = 0
        val contrarianWarnings = mutableListOf<String>()
        if (fearGreedScore != null) {
            when (CnnFearGreedRatingMapper.mapScore(fearGreedScore)) {
                "extreme greed" -> {
                    bonusPoints += 15
                    contrarianWarnings += "Codicia extrema (CNN Fear & Greed) +15"
                }
                "greed" -> {
                    bonusPoints += 5
                    contrarianWarnings += "Codicia (CNN Fear & Greed) +5"
                }
            }
        }
        if (aaiiBullishPercent != null && aaiiNeutralPercent != null && aaiiBearishPercent != null &&
            aaiiBullishPercent > aaiiNeutralPercent && aaiiBullishPercent > aaiiBearishPercent
        ) {
            bonusPoints += 15
            contrarianWarnings += "Sentimiento AAII alcista +15"
        }

        // ---- SUBGRUPO NUEVO: "Patrones técnicos de venta" (ver comentario de cabecera) ----
        val hchCandles = fiveYearCandles?.takeIf { it.isNotEmpty() } ?: candles
        val hchResult = UptrendDetector.detectHeadAndShoulders(hchCandles, debugSymbol = symbol)
        val doubleTopResult = UptrendDetector.detectDoubleTopForSellTiming(candles)
        val tripleTopResult = UptrendDetector.detectTripleTopForSellTiming(candles)
        val bearishDivergence = UptrendDetector.detectBearishPriceRsiDivergence(candles)
        val deathCross200Date = UptrendDetector.findSma50CrossedBelowSma200Date(hchCandles)

        val sellPatternFactors = mutableListOf<SellTimingFactor>()
        val sellPatternWarnings = mutableListOf<String>()

        when (hchResult.state) {
            UptrendDetector.HeadAndShouldersState.CONFIRMED -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Hombro-Cabeza-Hombro",
                    valueText = "Confirmado",
                    riskLevel = RiskLevel.HIGH,
                    detail = "Ruptura de la línea clavicular ya confirmada.",
                    points = 15
                )
                sellPatternWarnings += "Hombro-Cabeza-Hombro Confirmado +15"
            }
            UptrendDetector.HeadAndShouldersState.FORMING -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Hombro-Cabeza-Hombro",
                    valueText = "En formación",
                    riskLevel = RiskLevel.MEDIUM,
                    detail = "3 picos detectados, línea clavicular todavía sin romper.",
                    points = 7
                )
                sellPatternWarnings += "Hombro-Cabeza-Hombro en Formación +7"
            }
            UptrendDetector.HeadAndShouldersState.NONE -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Hombro-Cabeza-Hombro", valueText = "—", riskLevel = RiskLevel.LOW,
                    detail = "Sin patrón detectado (ventana 6 meses-5 años).", points = 0
                )
            }
        }

        when (doubleTopResult.state) {
            UptrendDetector.HeadAndShouldersState.CONFIRMED -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Doble Techo", valueText = "Confirmado", riskLevel = RiskLevel.HIGH,
                    detail = "Ruptura del soporte intermedio ya confirmada.", points = 15
                )
                sellPatternWarnings += "Doble Techo Confirmado +15"
            }
            UptrendDetector.HeadAndShouldersState.FORMING -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Doble Techo", valueText = "En formación", riskLevel = RiskLevel.MEDIUM,
                    detail = "Dos picos similares detectados, soporte todavía sin romper.", points = 7
                )
                sellPatternWarnings += "Doble Techo en Formación +7"
            }
            UptrendDetector.HeadAndShouldersState.NONE -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Doble Techo", valueText = "—", riskLevel = RiskLevel.LOW,
                    detail = "Sin patrón detectado.", points = 0
                )
            }
        }

        when (tripleTopResult.state) {
            UptrendDetector.HeadAndShouldersState.CONFIRMED -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Triple Techo", valueText = "Confirmado", riskLevel = RiskLevel.HIGH,
                    detail = "Ruptura del soporte tras el tercer techo ya confirmada.", points = 20
                )
                sellPatternWarnings += "Triple Techo Confirmado +20"
            }
            UptrendDetector.HeadAndShouldersState.FORMING -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Triple Techo", valueText = "En formación", riskLevel = RiskLevel.MEDIUM,
                    detail = "Tres picos similares detectados, soporte todavía sin romper.", points = 10
                )
                sellPatternWarnings += "Triple Techo en Formación +10"
            }
            UptrendDetector.HeadAndShouldersState.NONE -> {
                sellPatternFactors += SellTimingFactor(
                    label = "Triple Techo", valueText = "—", riskLevel = RiskLevel.LOW,
                    detail = "Sin patrón detectado.", points = 0
                )
            }
        }

        if (bearishDivergence.detected) {
            sellPatternFactors += SellTimingFactor(
                label = "Divergencia Bajista Precio/RSI", valueText = "Detectada", riskLevel = RiskLevel.HIGH,
                detail = "El precio sube mientras el RSI baja (RSI > 60 en ambos picos): pérdida de momentum.",
                points = 10
            )
            sellPatternWarnings += "Divergencia Bajista Precio/RSI +10"
        } else {
            sellPatternFactors += SellTimingFactor(
                label = "Divergencia Bajista Precio/RSI", valueText = "—", riskLevel = RiskLevel.LOW,
                detail = "Sin divergencia detectada en las últimas semanas.", points = 0
            )
        }

        if (deathCross200Date != null) {
            sellPatternFactors += SellTimingFactor(
                label = "Cruce de la Muerte (SMA50/SMA200)", valueText = "Detectado", riskLevel = RiskLevel.HIGH,
                detail = "SMA50 cruzó por debajo de la SMA200 (${UptrendDetector.formatCandleDateForDisplay(deathCross200Date)}).",
                points = 10
            )
            sellPatternWarnings += "Cruce de la Muerte SMA50/SMA200 (${UptrendDetector.formatCandleDateForDisplay(deathCross200Date)}) +10"
        } else {
            sellPatternFactors += SellTimingFactor(
                label = "Cruce de la Muerte (SMA50/SMA200)", valueText = "—", riskLevel = RiskLevel.LOW,
                detail = "Sin cruce reciente (necesita ~4 años de histórico semanal).", points = 0
            )
        }

        // Regla de saturación: 2 o más de los 5 patrones activos -> el subgrupo entero sube a 100.
        val activeCount = listOf(
            hchResult.state != UptrendDetector.HeadAndShouldersState.NONE,
            doubleTopResult.state != UptrendDetector.HeadAndShouldersState.NONE,
            tripleTopResult.state != UptrendDetector.HeadAndShouldersState.NONE,
            bearishDivergence.detected,
            deathCross200Date != null
        ).count { it }
        val sellPatternRawPoints = sellPatternFactors.sumOf { it.points }
        val sellPatternSubtotal = if (activeCount >= 2) 100 else sellPatternRawPoints.coerceIn(0, 100)
        if (activeCount >= 2) {
            sellPatternWarnings += "2 o más patrones activos a la vez: puntuación de patrones saturada a 100"
        }

        val score = (baseScore + bonusPoints + sellPatternSubtotal).coerceIn(0, 100)

        // Semáforo final, en sus 4 tramos — independiente del color rojo/verde de cada factor
        // individual (esos son binarios, este final tiene 4 niveles).
        val overallLevel = when {
            score >= 50 -> RiskLevel.HIGH   // amarillo y verde -> más señal de venta que de aguante
            score >= 25 -> RiskLevel.MEDIUM // naranja
            else -> RiskLevel.LOW           // rojo -> no es momento de vender
        }
        val summary = when {
            score >= 75 -> "Momento idóneo para la venta."
            score >= 50 -> "Buen momento para la venta."
            score >= 25 -> "Señales mixtas — ni un buen momento para vender ni una zona tranquila."
            else -> "No es momento de vender ahora."
        }

        return SellTimingAssessment(
            factors = factors + sellPatternFactors,
            overallLevel = overallLevel,
            overallScore = score,
            summary = summary,
            contrarianWarnings = contrarianWarnings,
            sellPatternWarnings = sellPatternWarnings
        )
    }

    /** RSI(14): sobrecompra (≥70) o sobreventa (≤30) suman 25 puntos; entre 30 y 70, 0. */
    private fun rsiFactor(candles: List<Candle>): SellTimingFactor {
        val rsi = TechnicalAnalysis.calculateRsi(candles, period = 14).lastOrNull { it != null }
        if (rsi == null) {
            return SellTimingFactor(
                label = "RSI (14)",
                valueText = "—",
                riskLevel = RiskLevel.LOW,
                detail = "Sin datos suficientes para calcular el RSI todavía.",
                points = 0,
                includedInScore = false
            )
        }
        val extreme = rsi >= 70.0 || rsi <= 30.0
        val detail = when {
            rsi >= 70.0 -> "RSI en sobrecompra (${"%.0f".format(rsi)}, ≥70)."
            rsi <= 30.0 -> "RSI en sobreventa (${"%.0f".format(rsi)}, ≤30)."
            else -> "RSI en zona neutra (${"%.0f".format(rsi)}, entre 30 y 70)."
        }
        return SellTimingFactor(
            label = "RSI (14)",
            valueText = "%.0f".format(rsi),
            riskLevel = if (extreme) RiskLevel.HIGH else RiskLevel.LOW,
            detail = detail,
            points = if (extreme) 25 else 0
        )
    }

    /** Volumen de operaciones por encima de su media de 20 semanas: suma 20 puntos. */
    private fun volumeFactor(candles: List<Candle>): SellTimingFactor? {
        val ratio = TechnicalAnalysis.volumeRatio(candles, VOLUME_LOOKBACK) ?: return null
        val above = ratio > 1.0
        val detail = if (above) {
            "Volumen por encima de su media de las últimas $VOLUME_LOOKBACK semanas (${"%.0f".format(ratio * 100)}%)."
        } else {
            "Volumen dentro o por debajo de su media (${"%.0f".format(ratio * 100)}%)."
        }
        return SellTimingFactor(
            label = "Volumen de operaciones",
            valueText = "${"%.0f".format(ratio * 100)}% de la media",
            riskLevel = if (above) RiskLevel.HIGH else RiskLevel.LOW,
            detail = detail,
            points = if (above) 20 else 0
        )
    }

    /**
     * Volatilidad PROPIA del stock (ya NO el VIX, que es volatilidad general de mercado) —
     * misma función que ya usa la fórmula de compra para su categoría "Riesgo"
     * (TechnicalAnalysis.ownVolatilityRatio), frente a la media histórica del propio stock.
     * Por encima de su media: suma 20 puntos.
     */
    private fun volatilityFactor(candles: List<Candle>): SellTimingFactor? {
        val ratio = TechnicalAnalysis.ownVolatilityRatio(candles) ?: return null
        val above = ratio > 1.0
        val detail = if (above) {
            "Volatilidad propia por encima de su media habitual (${"%.2f".format(ratio)}x)."
        } else {
            "Volatilidad propia dentro o por debajo de su media habitual (${"%.2f".format(ratio)}x)."
        }
        return SellTimingFactor(
            label = "Volatilidad propia",
            valueText = "${"%.2f".format(ratio)}x",
            riskLevel = if (above) RiskLevel.HIGH else RiskLevel.LOW,
            detail = detail,
            points = if (above) 20 else 0
        )
    }

    /**
     * PER del stock frente al rango típico de su sector (Symbols.SECTOR_TYPICAL_PE_RANGES) —
     * mismo criterio que ya usa la fórmula de compra. Si el sector no tiene rango típico
     * guardado, se compara contra el PER medio del sector en vivo (Finviz) como respaldo. Por
     * encima del rango/media: suma 20 puntos. Por debajo o dentro: 0.
     */
    private fun peFactor(stockPe: Double?, sectorAveragePe: Double?, sectorTypicalPeRange: Pair<Double, Double>?): SellTimingFactor {
        if (stockPe == null) {
            return SellTimingFactor(
                label = "PER del stock vs sector",
                valueText = "—",
                riskLevel = RiskLevel.LOW,
                detail = "Sin PER del stock todavía.",
                points = 0,
                includedInScore = false
            )
        }
        val (above, comparisonText) = when {
            sectorTypicalPeRange != null -> {
                val ref = "rango típico del sector ${"%.0f".format(sectorTypicalPeRange.first)}-${"%.0f".format(sectorTypicalPeRange.second)}x"
                (stockPe > sectorTypicalPeRange.second) to ref
            }
            sectorAveragePe != null && sectorAveragePe > 0.0 -> {
                (stockPe > sectorAveragePe) to "PER medio del sector ${"%.1f".format(sectorAveragePe)}x (sin rango típico guardado)"
            }
            else -> {
                return SellTimingFactor(
                    label = "PER del stock vs sector",
                    valueText = "%.1f".format(stockPe),
                    riskLevel = RiskLevel.LOW,
                    detail = "Sin referencia de sector (ni rango típico ni media) todavía.",
                    points = 0,
                    includedInScore = false
                )
            }
        }
        val detail = if (above) {
            "PER ${"%.1f".format(stockPe)}x por encima del $comparisonText."
        } else {
            "PER ${"%.1f".format(stockPe)}x dentro o por debajo del $comparisonText."
        }
        return SellTimingFactor(
            label = "PER del stock vs sector",
            valueText = "%.1f".format(stockPe),
            riskLevel = if (above) RiskLevel.HIGH else RiskLevel.LOW,
            detail = detail,
            points = if (above) 20 else 0
        )
    }

    /**
     * Cruce del precio con su media móvil de 20 semanas, mirando las DOS ÚLTIMAS velas
     * cerradas (dos últimas transiciones semana-a-semana) — ampliado a petición expresa: antes
     * solo miraba la última vela, y un cruce descendente que ya había ocurrido la semana
     * anterior (pero donde el precio ya no seguía cruzando en la semana más reciente) se perdía
     * por completo. Ahora cuenta como cruce descendente si ocurrió en CUALQUIERA de esas dos
     * transiciones. Ascendente: 0. Sin cruce en ninguna de las dos: 0 (no hay señal).
     */
    private fun smaCrossFactor(candles: List<Candle>): SellTimingFactor {
        val sma = TechnicalAnalysis.simpleMovingAverage(candles, SMA_PERIOD)
        if (candles.size < 2 || sma.size < 2) {
            return SellTimingFactor(
                label = "Cruce con la SMA20",
                valueText = "—",
                riskLevel = RiskLevel.LOW,
                detail = "Velas insuficientes para calcular la SMA20 todavía.",
                points = 0,
                includedInScore = false
            )
        }
        val n = candles.size

        // Una "transición" es el paso de una vela cerrada a la siguiente — se comprueban las
        // dos últimas (semana pasada -> esta semana, y hace dos semanas -> semana pasada) si
        // hay velas suficientes; si solo hay 2 velas en total, se cae al comportamiento de
        // antes (una sola transición).
        data class Transition(val prevClose: Double, val prevSma: Double, val currClose: Double, val currSma: Double)
        val transitionIndices = listOfNotNull(n - 1, if (n >= 3) n - 2 else null) // más reciente primero
        val transitions = transitionIndices.mapNotNull { i ->
            val prevSma = sma.getOrNull(i - 1)
            val currSma = sma.getOrNull(i)
            if (prevSma == null || currSma == null) null
            else Transition(candles[i - 1].close, prevSma, candles[i].close, currSma)
        }
        if (transitions.isEmpty()) {
            return SellTimingFactor(
                label = "Cruce con la SMA20",
                valueText = "—",
                riskLevel = RiskLevel.LOW,
                detail = "Sin SMA20 suficiente todavía.",
                points = 0,
                includedInScore = false
            )
        }

        // El más reciente (transitions[0]) manda para el texto mostrado ("hace 1 semana" vs
        // "hace 2 semanas"); pero para decidir SI hubo cruce, cuenta cualquiera de las dos.
        val descendingIndex = transitions.indexOfFirst { it.prevClose >= it.prevSma && it.currClose < it.currSma }
        val ascendingIndex = transitions.indexOfFirst { it.prevClose <= it.prevSma && it.currClose > it.currSma }
        val descendingCross = descendingIndex >= 0
        val ascendingCross = !descendingCross && ascendingIndex >= 0

        val (valueText, detail, points) = when {
            descendingCross && descendingIndex == 0 ->
                Triple("Descendente", "El precio ha cruzado por debajo de su media móvil de 20 semanas la última semana.", 15)
            descendingCross ->
                Triple("Descendente", "El precio cruzó por debajo de su media móvil de 20 semanas hace 2 semanas.", 15)
            ascendingCross && ascendingIndex == 0 ->
                Triple("Ascendente", "El precio ha cruzado por encima de su media móvil de 20 semanas la última semana.", 0)
            ascendingCross ->
                Triple("Ascendente", "El precio cruzó por encima de su media móvil de 20 semanas hace 2 semanas.", 0)
            else -> Triple("Sin cruce reciente", "Sin cruce con la SMA20 en las últimas 2 semanas.", 0)
        }
        return SellTimingFactor(
            label = "Cruce con la SMA20",
            valueText = valueText,
            riskLevel = if (descendingCross) RiskLevel.HIGH else RiskLevel.LOW,
            detail = detail,
            points = points
        )
    }
}
