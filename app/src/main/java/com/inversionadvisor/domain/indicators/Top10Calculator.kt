package com.inversionadvisor.domain.indicators

/** Para pintar cada factor como un chip de color en la interfaz — GOOD=verde, NEUTRAL=naranja, BAD=rojo, UNKNOWN=gris (sin dato). */
enum class FactorGrade { GOOD, NEUTRAL, BAD, UNKNOWN }

data class Top10Factor(val label: String, val grade: FactorGrade, val valueText: String)

/**
 * ÚNICA fórmula de riesgo/recompensa de toda la app — la usan "Top 10", "Futuras compras" y
 * "Análisis de opciones de compra" en la ficha de cada stock.
 *
 * SEGUNDA VERSIÓN de la fórmula, sustituyendo por completo la anterior (aditiva, con pesos en
 * puntos sueltos) por el modelo de 7 categorías pedido explícitamente por el usuario, cada una
 * puntuada de -1 (negativo) a +1 (positivo) y ponderada según esta tabla:
 *
 * TERCERA VERSIÓN (esta): se quitó "Impulso" (caída % desde máximos) y se separó "Tendencia"
 * en dos categorías independientes — Tendencia (solo los últimos 3 meses) y RSI (aparte, ya
 * no mezclado con la tendencia) — a petición expresa:
 *
 *  Categoría        Indicador                          Peso
 *  Macrotendencia   Sector en auge (fuerza relativa)    15%
 *  Tendencia        Tendencia de los últimos 3 meses    15%
 *  RSI              RSI(14), zona neutra vs extremos    15%
 *  Valoración       PER (+ deuda — SIN DATO, ver abajo) 15%
 *  Riesgo           Volatilidad propia                  15%
 *  Giro             Agotamiento (volumen/velas)         15%
 *  Volumen          Volumen de ruptura / MACD           10%
 *                                                        ────
 *                                                        100%
 *
 * CORREGIDO — esta tabla estaba desactualizada (quedó de una versión anterior de la fórmula,
 * antes de un reparto posterior a pesos casi iguales) y no coincidía con las constantes
 * WEIGHT_* reales del código, que son las que de verdad se aplican. La tabla de arriba ya
 * refleja los pesos reales — si se cambia algún WEIGHT_*, hay que actualizar también esta tabla
 * a la vez, para que no se repita el desajuste.
 *
 * PENALIZACIÓN APARTE (fuera de la tabla de pesos, no forma parte de las 7 categorías): si la
 * caída se está acelerando claramente (UptrendDetector.isDeclineAcceleratingClearly), se restan
 * 10 puntos a la puntuación final ya calculada — a petición expresa, "como caso aparte, sin
 * tocar nada de la fórmula".
 *
 * PENALIZACIÓN APARTE — Hombro-Cabeza-Hombro (UptrendDetector.detectHeadAndShoulders, ventana de
 * 6 meses a 5 años): -7 puntos si está EN FORMACIÓN (aviso amarillo), -15 si está CONFIRMADO
 * (ruptura de la línea clavicular, aviso rojo). El mismo patrón, detectado sobre otras velas,
 * SUMA puntos en "Momento idóneo para la venta" — ver SellTimingAnalyzer.
 *
 * Puntuación final = Σ (valor_categoría[-1..+1] × peso) ∈ [-1, +1], convertida a 0-100 para
 * mantener la misma escala que el resto de la app: combinedScore = (total + 1) × 50, menos la
 * penalización de aceleración de caída si aplica.
 * Recompensa/Riesgo (para no romper la interfaz existente, que ya tenía estos dos conceptos
 * separados) se derivan del mismo cálculo: Recompensa = suma de la parte POSITIVA de cada
 * categoría × su peso × 100; Riesgo = suma de la parte NEGATIVA (en valor absoluto) × su peso
 * × 100 — los dos entre 0 y 100.
 *
 * AVISOS HONESTOS — piezas que la tabla pedía y no se han podido cubrir del todo:
 *  - "Salud financiera (Deuda)" en Valoración: NO hay ninguna fuente de datos de deuda o
 *    apalancamiento en esta app — el criterio de Valoración se calcula solo con PER (frente al
 *    PER medio del sector vía Finviz, y frente a los rangos típicos por sector que se guardaron
 *    aparte en Symbols.SECTOR_TYPICAL_PE_RANGES).
 *  - "Agotamiento alcista (techo)" en Giro: el detector de agotamiento de esta app
 *    (ExhaustionDetector) solo está construido para el lado bajista (caídas que se agotan, para
 *    comprar el rebote) — no existe un detector espejo para techos. Se aproxima con una
 *    heurística más simple (patrón de vela bajista + RSI alto + cerca de máximos), pero no es
 *    un detector completo como el de caídas.
 *  - MACD: primera vez que se calcula en la app, y sobre velas SEMANALES (no diarias) — ver
 *    TechnicalAnalysis.calculateMacd.
 *
 * Heurística propia de esta app — no es un modelo validado con datos históricos.
 */
object Top10Calculator {

    const val MIN_DECLINE_PERCENT = 15.0
    const val MIN_SHORT_TERM_DECLINE_PERCENT = 5.0
    const val SHORT_TERM_LOOKBACK_WEEKS = 12

    private const val WEIGHT_MACROTENDENCIA = 0.15
    private const val WEIGHT_TENDENCIA = 0.15
    private const val WEIGHT_RSI = 0.15
    private const val WEIGHT_VALORACION = 0.15
    private const val WEIGHT_RIESGO = 0.15
    private const val WEIGHT_GIRO = 0.15
    private const val WEIGHT_VOLUMEN = 0.10

    /** Convierte la puntuación (0-100) a la escala 1-10 (sin usar en la interfaz ahora mismo,
     *  se dejó por si se retoma). */
    fun ratingOutOf10(combinedScore: Double): Int = Math.round(combinedScore / 10.0).toInt().coerceIn(1, 10)

    data class ScoredCandidate(
        val rewardScore: Double,
        val riskScore: Double,
        val combinedScore: Double,
        val ratingOutOf10: Int,
        val analystSummary: String,
        val factors: List<Top10Factor>,
        val rewardBreakdown: List<String>,
        val riskBreakdown: List<String>,
        /** Etiquetas de las PENALIZACIONES apartes que se han activado (aceleración de caída,
         *  SMA50, resultados trimestrales, posible trampa de toros) — la interfaz las pinta
         *  como aviso en neón ROJO intermitente. Vacía si ninguna se ha activado. */
        val penaltyWarnings: List<String> = emptyList(),
        /** Etiquetas de los BONOS apartes que se han activado (divergencia momentum/precio
         *  favorable, cruce alcista SMA20/SMA50 — "cruz dorada") — la interfaz las pinta como
         *  aviso en neón VERDE intermitente. Separados de penaltyWarnings a petición expresa,
         *  antes ambos compartían la misma lista (y por tanto el mismo color rojo, incluso
         *  para los bonos, lo cual no tenía sentido). Vacía si ninguno se ha activado. */
        val bonusWarnings: List<String> = emptyList(),
        /** Info estructurada (fechas + precios) del doble techo/suelo detectado — pedido
         *  expresamente así para poder dibujar los dos círculos (rojos/verdes) en el gráfico,
         *  no solo mostrar el texto del aviso. null si no se ha detectado ningún patrón. */
        val doubleTopBottomResult: DoubleTopBottomResult? = null,
        /** Patrón de Hombro-Cabeza-Hombro detectado (ver UptrendDetector.detectHeadAndShoulders)
         *  — null si no se ha detectado ninguno (NONE). */
        val hchResult: UptrendDetector.HeadAndShouldersResult? = null,
        /** Triple techo/suelo detectado (ver UptrendDetector.detectTripleTopOrBottom) — null si
         *  no se ha detectado ninguno. */
        val tripleTopBottomResult: UptrendDetector.TripleTopBottomResult? = null
    )

    /**
     * @param requireSignal true (comportamiento de "Top 10"): descarta el candidato (devuelve
     *   null) si no hay ni tendencia alcista ni caída+agotamiento de al menos [MIN_DECLINE_PERCENT].
     *   false: puntúa siempre (comportamiento de "Análisis de opciones de compra"/"Futuras compras").
     * @param aboveTrendSma true si el precio sigue por encima de su media de tendencia (40 semanas) — pieza de "Tendencia".
     * @param macd resultado de TechnicalAnalysis.calculateMacd sobre las mismas velas — pieza de "Volumen/MACD".
     */
    fun score(
        trendQuality: Double?,
        yearChangePercent: Double?,
        aboveTrendSma: Boolean?,
        isSectorInFavor: Boolean?,
        longTermDecline: ExhaustionSignal?,
        shortTermPullback: ExhaustionSignal?,
        rsi14: Double?,
        volumeRatio: Double?,
        stockVolatilityRatio: Double?,
        stockPe: Double?,
        sectorAveragePe: Double?,
        sectorTypicalPeRange: Pair<Double, Double>?,
        percentFromYearHigh: Double?,
        /** Ya no se usa dentro del cálculo — era solo para "Impulso", quitada de la fórmula.
         *  Se deja el parámetro para no tener que tocar las 4 llamadas que ya lo pasan. */
        nearestSupportPercent: Double?,
        nearestResistancePercent: Double?,
        candlestickPattern: CandlestickPattern?,
        marketTrap: MarketTrapType?,
        macd: TechnicalAnalysis.MacdResult?,
        /** true si UptrendDetector.isClearlyBullishShortTerm() detecta tendencia claramente
         *  alcista en las últimas ~13 semanas (3 meses) — decide por sí sola la categoría
         *  "Tendencia" (ya no depende del RSI, que ahora es su propia categoría aparte). Con
         *  valor por defecto null para no romper llamadas existentes; si no se pasa, "Tendencia"
         *  sale como "sin dato". */
        shortTermBullish: Boolean? = null,
        /** Penalización APARTE de las 7 categorías (no toca ningún peso) — si
         *  UptrendDetector.isDeclineAcceleratingClearly() detecta que la caída se está
         *  acelerando claramente, resta 10 puntos a la puntuación final ya calculada. */
        declineAccelerating: Boolean = false,
        /** Penalización APARTE — la SMA20 cruza por debajo de la SMA50 ("cruz de la muerte",
         *  cambiado a petición expresa de "precio cruza SMA50" a esto)
         *  (UptrendDetector.findSma20CrossedBelowSma50Date) resta 20 puntos a la puntuación
         *  final ya calculada. Cambiado de Boolean a String? (la fecha del cruce, null si no
         *  hubo) a petición expresa, para poder mostrar la fecha exacta en el aviso en neón. */
        deathCrossDate: String? = null,
        /** Penalización APARTE — próxima publicación de resultados trimestrales dentro de 3
         *  semanas resta 10 puntos a la puntuación final ya calculada. */
        earningsWithinThreeWeeks: Boolean = false,
        /** Bono/penalización APARTE — divergencia alcista momentum/precio (ver
         *  UptrendDetector.detectMomentumPriceDivergence). NONE = sin efecto. */
        momentumPriceDivergence: MomentumPriceDivergence = MomentumPriceDivergence.NONE,
        /** Bono APARTE — cruce ascendente de la SMA20 por encima de la SMA50 ("cruz dorada",
         *  UptrendDetector.findSma20CrossedAboveSma50Date) suma 15 puntos a la puntuación final
         *  ya calculada. Cambiado de Boolean a String? (la fecha del cruce) — ver deathCrossDate. */
        goldenCrossDate: String? = null,
        /** Bono/penalización APARTE (no una categoría ponderada de la tabla de 100%, pese a lo
         *  que decía antes este comentario) — patrón de "bandera" en las últimas 15 velas
         *  DIARIAS ("a corto plazo"), ver UptrendDetector.detectFlagPattern. null = sin datos
         *  suficientes (menos de 15 velas diarias). BULLISH suma 15 puntos a la puntuación final
         *  ya calculada (o solo aviso informativo si el de largo plazo ya es decisivo, ver más
         *  abajo), BEARISH resta 15. */
        shortTermFlagPattern: FlagPattern? = null,
        /** Bono/penalización APARTE — igual que shortTermFlagPattern (±15 puntos), pero sobre
         *  las últimas 15 velas MENSUALES ("a largo plazo"), ver
         *  MarketRepository.observeMonthlyCandlesForFlag. Puede coexistir con
         *  shortTermFlagPattern (uno, otro, ambos, o ninguno) — si los dos son decisivos, el de
         *  largo plazo es el que lleva los puntos; el otro queda como aviso informativo. */
        longTermFlagPattern: FlagPattern? = null,
        /** Penalización APARTE — tendencia bajista CONSOLIDADA: 4 o más de los últimos 6 meses
         *  cerraron en negativo (ver UptrendDetector.countBearishMonthsInLast6). Resta 20 puntos
         *  a la puntuación final ya calculada. Pedido expresamente porque la categoría
         *  "Tendencia" (solo mira 3 meses, y se anula ante cualquier giro de corto plazo) puede
         *  dejar sin penalizar una debilidad de fondo que lleva más tiempo consolidándose. */
        consolidatedBearishMonthsCount: Int? = null,
        /** Penalización/bono APARTE — patrón de "doble techo" (bajista, -15 puntos) o "doble
         *  suelo" (alcista, +15 puntos) detectado en las últimas velas semanales, con un máximo
         *  de 6 meses entre los dos picos/valles (ver UptrendDetector.detectDoubleTopOrBottom).
         *  Ahora es el resultado COMPLETO (con fechas y precios de los dos puntos, no solo si
         *  hay patrón), para poder mostrar la fecha junto al aviso — pedido expresamente así. */
        doubleTopBottomResult: DoubleTopBottomResult = DoubleTopBottomResult(DoubleTopBottomPattern.NONE),
        /** Penalización APARTE, pedida expresamente — patrón de "Hombro-Cabeza-Hombro"
         *  (bajista), detectado sobre una ventana de 6 meses a 5 años (ver
         *  UptrendDetector.detectHeadAndShoulders). Dos estados: EN FORMACIÓN (aviso amarillo,
         *  resta 7 puntos) y CONFIRMADO (ruptura de la línea clavicular ya ocurrida, aviso rojo,
         *  resta 15 puntos). NONE (valor por defecto) no penaliza nada. */
        hchResult: UptrendDetector.HeadAndShouldersResult = UptrendDetector.HeadAndShouldersResult(UptrendDetector.HeadAndShouldersState.NONE),
        /** Bono APARTE, pedido expresamente — antes "tendencia alcista clara" (UptrendDetector.
         *  evaluate(), regresión de 52 semanas con R²≥0.5) era un FILTRO DE ENTRADA: si un stock
         *  no la cumplía (y tampoco tenía caída con agotamiento), ni siquiera se puntuaba con la
         *  fórmula completa en Top10 — caso real que esto excluía injustamente: SanDisk, con 85
         *  puntos en la fórmula completa, fuera de Top10 por no encajar en ninguna de las dos
         *  formas. Invertido a petición expresa: ahora CUALQUIER candidato con puntuación
         *  decente entra en el pool (ver Top10GenericCandidateEntity), y la tendencia alcista
         *  clara se aplica AQUÍ, como bono (+10) si se cumple — ya no excluye a nadie por su
         *  ausencia. */
        hasLongTermUptrend: Boolean = false,
        /** Triple techo/suelo, pedido expresamente como patrón APARTE del doble techo/suelo —
         *  ambos se comprueban de forma independiente, no se excluyen entre sí. Triple techo:
         *  -20 puntos. Triple suelo: +15 puntos. */
        tripleTopBottomResult: UptrendDetector.TripleTopBottomResult = UptrendDetector.TripleTopBottomResult(UptrendDetector.TripleTopBottomPattern.NONE),
        requireSignal: Boolean = true
    ): ScoredCandidate? {
        val longTermQualifies = longTermDecline != null && longTermDecline.declinePercentFromRecentHigh >= MIN_DECLINE_PERCENT
        val hasTrend = trendQuality != null
        if (requireSignal && !hasTrend && !longTermQualifies) return null

        val analystParts = mutableListOf<String>()
        val factors = mutableListOf<Top10Factor>()
        val rewardBreakdown = mutableListOf<String>()
        val riskBreakdown = mutableListOf<String>()
        var weightedTotal = 0.0
        var rewardAccumulated = 0.0
        var riskAccumulated = 0.0

        fun addCategory(name: String, weight: Double, value: Double?, explanation: String) {
            if (value == null) {
                rewardBreakdown += "$name (peso ${"%.0f".format(weight * 100)}%): sin dato, no puntúa"
                factors += Top10Factor(name, FactorGrade.UNKNOWN, "—")
                return
            }
            val v = value.coerceIn(-1.0, 1.0)
            val contribution = v * weight
            weightedTotal += contribution
            val contributionPoints = contribution * 100
            if (contributionPoints >= 0) rewardAccumulated += contributionPoints else riskAccumulated += -contributionPoints
            val sign = if (contributionPoints >= 0) "+" else ""
            val line = "$name (peso ${"%.0f".format(weight * 100)}%): $explanation → valor ${"%.1f".format(v)} × ${"%.0f".format(weight * 100)}% = $sign${"%.2f".format(contributionPoints)} pts"
            if (contributionPoints >= 0) rewardBreakdown += line else riskBreakdown += line
            val grade = if (v >= 0.4) FactorGrade.GOOD else if (v >= -0.4) FactorGrade.NEUTRAL else FactorGrade.BAD
            factors += Top10Factor(name, grade, if (v > 0) "+${"%.1f".format(v)}" else "${"%.1f".format(v)}")
            analystParts += "$name: $explanation"
        }

        // ---- 1. Macrotendencia (15%): sector en auge ----
        val macroValue = when (isSectorInFavor) {
            true -> 1.0
            false -> -1.0
            null -> null
        }
        addCategory(
            "Macrotendencia", WEIGHT_MACROTENDENCIA, macroValue,
            when (isSectorInFavor) { true -> "sector líder, en rotación favorable"; false -> "sector rezagado frente al mercado"; null -> "" }
        )

        // ---- Señal de agotamiento bajista/giro al alza — se calcula aquí porque la usan DOS
        // categorías distintas (Tendencia, más abajo, y Giro, más adelante), para no calcularla
        // dos veces por separado.
        val bearishExhaustion = (longTermDecline != null && longTermDecline.detected) || (shortTermPullback != null && shortTermPullback.detected)

        // ---- 2. Tendencia (15%): tendencia de los últimos 3 meses (separada del RSI, que
        // ahora es su propia categoría aparte, ver más abajo) ----
        // Alcista -> suma. Bajista -> resta, A MENOS que haya un giro al alza detectado
        // (bearishExhaustion) — en ese caso no resta, queda neutra (el giro en sí ya se premia
        // aparte en la categoría "Giro").
        val trendValue = when {
            shortTermBullish == null -> null
            shortTermBullish -> 1.0
            bearishExhaustion -> 0.0 // bajista, pero con giro al alza detectado: no resta
            else -> -1.0 // bajista sin giro: resta
        }
        addCategory(
            "Tendencia", WEIGHT_TENDENCIA, trendValue,
            when {
                shortTermBullish == true -> "tendencia claramente alcista en los últimos 3 meses"
                shortTermBullish == false && bearishExhaustion -> "tendencia bajista en los últimos 3 meses, pero con agotamiento/giro al alza detectado — no penaliza"
                shortTermBullish == false -> "tendencia bajista en los últimos 3 meses, sin señales de giro"
                else -> "sin dato de tendencia de los últimos 3 meses"
            }
        )

        // ---- RSI (15%): categoría propia, separada de Tendencia ----
        // Zona neutra (31-69) -> suma. Sobrecompra (≥70) o sobreventa (≤30) -> resta. Siempre
        // uno de los dos, nunca neutro (salvo sin dato).
        val rsiValue = when {
            rsi14 == null -> null
            rsi14 >= 70.0 || rsi14 <= 30.0 -> -1.0
            else -> 1.0
        }
        addCategory(
            "RSI", WEIGHT_RSI, rsiValue,
            when {
                rsi14 != null && rsi14 >= 70.0 -> "RSI en sobrecompra (${"%.0f".format(rsi14)})"
                rsi14 != null && rsi14 <= 30.0 -> "RSI en sobreventa (${"%.0f".format(rsi14)})"
                rsi14 != null -> "RSI en zona neutra (${"%.0f".format(rsi14)})"
                else -> "sin dato de RSI"
            }
        )

        // ---- 3. Valoración (15%): PER (deuda sin datos, ver cabecera) ----
        // CAMBIADO a petición expresa: ya no es simétrico con un tramo neutro en medio — ahora
        // SOLO se resta cuando el PER está claramente por ENCIMA del rango/margen (caro de
        // verdad); tanto por debajo del rango como DENTRO de él (antes neutro) SUMAN los dos
        // igual — el razonamiento pedido es que "dentro del rango típico" ya es una valoración
        // razonable, no algo neutro, así que también merece sumar.
        val valuationValue = when {
            stockPe == null -> null
            sectorTypicalPeRange != null -> when {
                stockPe > sectorTypicalPeRange.second -> -1.0
                else -> 1.0
            }
            sectorAveragePe != null && sectorAveragePe > 0.0 -> {
                val deviation = (stockPe - sectorAveragePe) / sectorAveragePe
                when {
                    deviation > 0.40 -> -1.0
                    else -> 1.0
                }
            }
            else -> null
        }
        addCategory(
            "Valoración PER", WEIGHT_VALORACION, valuationValue,
            if (stockPe != null) {
                val comparison = when {
                    sectorTypicalPeRange != null -> "rango típico del sector ${"%.0f".format(sectorTypicalPeRange.first)}-${"%.0f".format(sectorTypicalPeRange.second)}x"
                    sectorAveragePe != null -> "PER medio del sector ${"%.1f".format(sectorAveragePe)}x (sin rango típico guardado para este sector)"
                    else -> "sin referencia de sector"
                }
                "PER ${"%.1f".format(stockPe)}x frente a $comparison · deuda sin dato, no incluida"
            } else ""
        )

        // ---- 4. Riesgo (15%): volatilidad propia ----
        // Reescrito a petición expresa: ASIMÉTRICO — solo resta si la volatilidad es MUY alta;
        // volatilidad normal o controlada ya NO suma (se quitó el bonus que había antes), no
        // hace nada.
        val riskCatValue = when {
            stockVolatilityRatio == null -> null
            stockVolatilityRatio > 1.8 -> -1.0
            else -> 0.0
        }
        addCategory(
            "Riesgo (volatilidad)", WEIGHT_RIESGO, riskCatValue,
            stockVolatilityRatio?.let { "volatilidad propia ${"%.2f".format(it)}x lo habitual" } ?: ""
        )

        // ---- 5. Impulso: QUITADA de la fórmula a petición expresa (antes 15%, caída % desde
        // máximos) — ese peso se repartió entre la nueva categoría RSI (15%, ver arriba).

        // ---- 6. Giro (15%): agotamiento (volumen/velas) ----
        // bearishExhaustion ya se calculó arriba (la usa también "Tendencia", para no
        // duplicar el cálculo).
        val nearCeilingApprox = percentFromYearHigh != null && percentFromYearHigh >= -5.0
        // REFORZADO a petición expresa: antes exigía SIEMPRE vela bajista o trampa alcista, a la
        // vez que RSI≥65 y cerca de máximos — con eso, el lado bajista casi nunca disparaba en la
        // práctica. Ahora hay un segundo camino, más simple: RSI MUY sobrecomprado (≥75) cerca de
        // máximos ya cuenta por sí solo, aunque no haya ninguna vela ni trampa concretas — un
        // aviso igual de razonable de agotamiento alcista.
        val toppingApprox = rsi14 != null && rsi14 >= 65.0 && nearCeilingApprox && (
            candlestickPattern == CandlestickPattern.SHOOTING_STAR ||
            candlestickPattern == CandlestickPattern.BEARISH_ENGULFING ||
            marketTrap == MarketTrapType.BULL_TRAP ||
            rsi14 >= 75.0
        )
        val turnValue = when {
            bearishExhaustion -> 1.0 // agotamiento bajista, giro al alza
            toppingApprox -> -1.0 // aproximación de agotamiento alcista (techo)
            else -> 0.0
        }
        addCategory(
            "Giro", WEIGHT_GIRO, turnValue,
            when {
                bearishExhaustion -> "agotamiento bajista detectado (posible giro al alza)"
                toppingApprox -> "indicios de techo (patrón bajista + RSI alto cerca de máximos) — aproximación, no un detector completo"
                else -> "sin señal de giro clara"
            }
        )

        // ---- 7. Volumen (10%): volumen de ruptura / MACD ----
        val macdBullish = macd?.let { it.macdLine > it.signalLine }
        val volumeValue = when {
            volumeRatio == null && macdBullish == null -> null
            volumeRatio != null && volumeRatio > 1.3 && macdBullish == true -> 1.0
            volumeRatio != null && volumeRatio < 0.7 -> -1.0
            macdBullish == false && volumeRatio != null && volumeRatio > 1.3 -> -1.0 // volumen alto pero MACD bajista: ruptura sin confirmar
            else -> 0.0
        }
        addCategory(
            "Volumen / MACD", WEIGHT_VOLUMEN, volumeValue,
            (volumeRatio?.let { "volumen ${"%.0f".format(it * 100)}% de su media" } ?: "sin dato de volumen") +
                (macdBullish?.let { if (it) ", MACD alcista" else ", MACD bajista" } ?: ", MACD sin dato (semanal, ver aviso)")
        )

        // Escala nueva, centrada en 0: -100 (todas las categorías en contra) a +100 (todas a
        // favor). El 0 es el punto neutro de verdad — antes la fórmula era (total+1)×50, que
        // convertía el neutro en 50 y comprimía todo lo malo dentro de 0-100 sin dejar sitio a
        // los negativos: un stock realmente malo, con un total ligeramente negativo, podía
        // salir con 40 puntos — parecía mediocre, no malo. Ahora un total negativo da
        // directamente una puntuación negativa, tal como se pidió.
        // Vuelta a la escala 0-100 de antes, a petición expresa (se probó la de -100/+100 y no
        // convenció) — el 0 vuelve a ser "todo en contra" y el 100 "todo a favor", con el
        // neutro en 50.
        val combinedBeforePenalty = ((weightedTotal + 1.0) * 50.0).coerceIn(0.0, 100.0)
        val reward = rewardAccumulated.coerceIn(0.0, 100.0)
        val risk = riskAccumulated.coerceIn(0.0, 100.0)

        rewardBreakdown += "PUNTUACIÓN TOTAL (0-100) = (${"%.2f".format(weightedTotal)} + 1) × 50 = ${"%.1f".format(combinedBeforePenalty)}"

        // Penalizaciones y bonos APARTE, fuera de las 7 categorías y sus pesos — se aplican
        // DESPUÉS de la puntuación ya calculada arriba, no tocan weightedTotal para nada.
        // Separados en dos listas (antes compartían una sola, y por tanto el mismo color rojo
        // en la interfaz, incluso los bonos — corregido a petición expresa): penaltyWarnings
        // (neón ROJO) y bonusWarnings (neón VERDE).
        var totalPenalty = 0.0
        var totalBonus = 0.0
        val penaltyWarnings = mutableListOf<String>()
        val bonusWarnings = mutableListOf<String>()
        if (declineAccelerating) {
            totalPenalty += 10.0
            penaltyWarnings += "Caída acelerando claramente -10"
        }
        if (deathCrossDate != null) {
            totalPenalty += 15.0
            penaltyWarnings += "SMA20 cruza por debajo de la SMA50 (${UptrendDetector.formatCandleDateForDisplay(deathCrossDate)}) -15"
        }
        if (consolidatedBearishMonthsCount != null && consolidatedBearishMonthsCount >= 5) {
            totalPenalty += 15.0
            penaltyWarnings += "Tendencia bajista consolidada ($consolidatedBearishMonthsCount de los últimos 6 meses en negativo) -15"
        }
        // CORREGIDO a petición expresa: el triple techo/suelo "absorbe" al doble cuando
        // coinciden — antes los dos se sumaban/restaban a la vez (un triple techo casi siempre
        // implica que también hay un doble techo dentro de esos mismos 3 picos, así que se
        // estaba contando la misma señal dos veces). Ahora el doble SOLO puntúa si NO hay
        // ningún triple detectado a la vez.
        if (tripleTopBottomResult.pattern == UptrendDetector.TripleTopBottomPattern.NONE) {
            if (doubleTopBottomResult.pattern == DoubleTopBottomPattern.DOUBLE_TOP) {
                totalPenalty += 15.0
                val fecha1 = doubleTopBottomResult.firstDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
                val fecha2 = doubleTopBottomResult.secondDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
                penaltyWarnings += "Patrón de Doble Techo Detectado ($fecha1 y $fecha2) -15"
            } else if (doubleTopBottomResult.pattern == DoubleTopBottomPattern.DOUBLE_BOTTOM) {
                totalBonus += 10.0
                val fecha1 = doubleTopBottomResult.firstDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
                val fecha2 = doubleTopBottomResult.secondDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
                bonusWarnings += "Patrón de Doble Suelo Detectado ($fecha1 y $fecha2) +10"
            }
        }
        if (tripleTopBottomResult.pattern == UptrendDetector.TripleTopBottomPattern.TRIPLE_TOP) {
            totalPenalty += 20.0
            val fecha1 = tripleTopBottomResult.firstDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
            val fecha2 = tripleTopBottomResult.secondDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
            val fecha3 = tripleTopBottomResult.thirdDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
            penaltyWarnings += "Patrón de Triple Techo Detectado ($fecha1, $fecha2 y $fecha3) -20"
        } else if (tripleTopBottomResult.pattern == UptrendDetector.TripleTopBottomPattern.TRIPLE_BOTTOM) {
            totalBonus += 15.0
            val fecha1 = tripleTopBottomResult.firstDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
            val fecha2 = tripleTopBottomResult.secondDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
            val fecha3 = tripleTopBottomResult.thirdDate?.let { UptrendDetector.formatCandleDateForDisplay(it) }
            bonusWarnings += "Patrón de Triple Suelo Detectado ($fecha1, $fecha2 y $fecha3) +15"
        }
        if (earningsWithinThreeWeeks) {
            totalPenalty += 10.0
            penaltyWarnings += "Resultados trimestrales en menos de 3 semanas -10"
        }
        when (hchResult.state) {
            UptrendDetector.HeadAndShouldersState.CONFIRMED -> {
                totalPenalty += 15.0
                val fechas = listOfNotNull(hchResult.leftShoulderDate, hchResult.headDate, hchResult.rightShoulderDate)
                    .joinToString(", ") { UptrendDetector.formatCandleDateForDisplay(it) }
                penaltyWarnings += "Patrón de Hombro-Cabeza-Hombro Confirmado ($fechas) -15"
            }
            UptrendDetector.HeadAndShouldersState.FORMING -> {
                totalPenalty += 7.0
                penaltyWarnings += "Patrón de Hombro-Cabeza-Hombro en Formación -7"
            }
            UptrendDetector.HeadAndShouldersState.NONE -> {}
        }
        if (goldenCrossDate != null) {
            totalBonus += 10.0
            bonusWarnings += "Cruz dorada (${UptrendDetector.formatCandleDateForDisplay(goldenCrossDate)}): SMA20 cruza por encima de la SMA50 +10"
        }
        if (hasLongTermUptrend) {
            totalBonus += 10.0
            bonusWarnings += "Tendencia alcista clara confirmada (52 semanas) +10"
        }
        // Bandera — SACADA DE LA FÓRMULA (antes categoría con peso del 15%) y convertida en
        // bono/penalización APARTE, a petición expresa: +15 si es alcista, -15 si es bajista.
        // Misma prioridad que antes: si hay patrón a LARGO plazo (mensual), ese es el que
        // puntúa (un patrón mensual refleja un movimiento más grande y estructural que uno
        // diario); si no lo hay pero SÍ a corto plazo, puntúa ese. Los dos se siguen avisando
        // por separado en neón, aunque solo uno de ellos sume/reste puntos.
        val effectiveFlagPattern = when {
            longTermFlagPattern == FlagPattern.BULLISH || longTermFlagPattern == FlagPattern.BEARISH -> longTermFlagPattern
            shortTermFlagPattern == FlagPattern.BULLISH || shortTermFlagPattern == FlagPattern.BEARISH -> shortTermFlagPattern
            else -> FlagPattern.NONE
        }
        if (effectiveFlagPattern == FlagPattern.BULLISH) {
            totalBonus += 15.0
        } else if (effectiveFlagPattern == FlagPattern.BEARISH) {
            totalPenalty += 15.0
        }
        // Avisos en neón del patrón de bandera — el que gana la prioridad (largo plazo si es
        // decisivo, si no corto plazo) lleva los puntos ya sumados/restados arriba anotados en
        // el propio texto; el otro, si lo hay, es solo informativo (sin anotación de puntos).
        val longTermFlagDecisive = longTermFlagPattern == FlagPattern.BULLISH || longTermFlagPattern == FlagPattern.BEARISH
        if (shortTermFlagPattern == FlagPattern.BULLISH) {
            bonusWarnings += "Patrón de Bandera Alcista a Corto Plazo" + if (!longTermFlagDecisive) " +15" else ""
        } else if (shortTermFlagPattern == FlagPattern.BEARISH) {
            penaltyWarnings += "Patrón de Bandera Bajista a Corto Plazo" + if (!longTermFlagDecisive) " -15" else ""
        }
        if (longTermFlagPattern == FlagPattern.BULLISH) {
            bonusWarnings += "Patrón de Bandera Alcista a Largo Plazo +15"
        } else if (longTermFlagPattern == FlagPattern.BEARISH) {
            penaltyWarnings += "Patrón de Bandera Bajista a Largo Plazo -15"
        }
        // Divergencia alcista momentum/precio — el mismo patrón, tres lecturas distintas según
        // el contexto (ver MomentumPriceDivergence).
        when (momentumPriceDivergence) {
            MomentumPriceDivergence.LONG_DOWNTREND_POSSIBLE_BOTTOM -> {
                totalBonus += 5.0
                bonusWarnings += "Posible suelo tras tendencia bajista larga +5"
            }
            MomentumPriceDivergence.UPTREND_PULLBACK_ENDING -> {
                totalBonus += 5.0
                bonusWarnings += "Posible fin de retroceso en tendencia alcista +5"
            }
            MomentumPriceDivergence.DOWNTREND_BULL_TRAP_RISK -> {
                totalPenalty += 5.0
                penaltyWarnings += "Posible rebote técnico pasajero (trampa de toros) -5"
            }
            MomentumPriceDivergence.NONE -> {}
        }
        val combined = (combinedBeforePenalty - totalPenalty + totalBonus).coerceIn(0.0, 100.0)
        if (penaltyWarnings.isNotEmpty() || bonusWarnings.isNotEmpty()) {
            riskBreakdown += "PENALIZACIONES/BONOS APARTE (no forman parte de las 7 categorías): ${(penaltyWarnings + bonusWarnings).joinToString("; ")} → ${"%.1f".format(combinedBeforePenalty)} pasa a ${"%.1f".format(combined)}"
        }
        riskBreakdown += "Nota: el Riesgo aquí es la suma de las categorías con contribución negativa, en valor absoluto — no un cálculo aparte."

        val ratingOutOf10 = ratingOutOf10(combined)
        val summary = analystParts.filter { it.isNotBlank() }.joinToString(". ") { it.replaceFirstChar { c -> c.uppercase() } } + "."

        return ScoredCandidate(reward, risk, combined, ratingOutOf10, summary, factors, rewardBreakdown, riskBreakdown, penaltyWarnings, bonusWarnings, doubleTopBottomResult.takeIf { it.pattern != DoubleTopBottomPattern.NONE }, hchResult.takeIf { it.state != UptrendDetector.HeadAndShouldersState.NONE }, tripleTopBottomResult.takeIf { it.pattern != UptrendDetector.TripleTopBottomPattern.NONE })
    }
}
