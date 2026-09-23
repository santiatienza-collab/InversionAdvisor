package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Candle
import kotlin.math.abs

/**
 * Señal de "agotamiento de la caída": no basta con que el precio haya caído
 * un 10-15%, hay que ver indicios de que la presión vendedora se está agotando
 * y que el precio ya está recuperando terreno hacia la próxima resistencia.
 */
data class ExhaustionSignal(
    val detected: Boolean,
    /** 0-100. >=40 se considera señal razonable, >=65 señal fuerte (varios indicios coinciden). */
    val confidenceScore: Int,
    val reasons: List<String>,
    val recentHigh: Double,
    val recentLow: Double,
    val recentLowDate: String,
    val declinePercentFromRecentHigh: Double,
    val nextResistanceTarget: Double?,
    /** % del camino recorrido entre el suelo y la próxima resistencia (0-100). */
    val recoveryProgressToResistancePercent: Double?
)

object ExhaustionDetector {

    /** % mínimo que el precio actual debe haberse alejado por encima del suelo reciente para
     *  considerar que hay una recuperación sólida (no solo una vela suelta cerrando algo mejor). */
    private const val MIN_REBOTE_DESDE_SUELO_PERCENT = 5.0

    /** R² mínimo de la pendiente bajista entre el máximo y el suelo — por debajo de esto se
     *  considera que los dos extremos son casualidad de un rango lateral, no una tendencia
     *  bajista real. Mismo umbral que TechnicalAnalysis.detectMacdPriceDivergence. */
    private const val MIN_DECLINE_R2 = 0.3

    /**
     * @param candles histórico ordenado ascendente (recomendado: velas semanales de 1-2 años,
     *                o diarias de varios meses).
     * @param minDeclinePercent caída mínima desde el máximo reciente para considerarlo candidato.
     * @param lookbackForHigh cuántas velas hacia atrás buscar el "máximo reciente" de referencia.
     *   CAMBIADO de 52 a 26 semanas (6 meses) a petición expresa: un máximo de hace casi un año
     *   ya no es una referencia relevante para "ahora mismo" — con 26 semanas, el "giro" que se
     *   detecta responde a movimientos más recientes, no a un máximo desactualizado de meses
     *   atrás. Sigue siendo bastante más amplio que la ventana corta (12 semanas), para no
     *   solaparse del todo con ella.
     */
    fun detect(
        candles: List<Candle>,
        minDeclinePercent: Double = 10.0,
        lookbackForHigh: Int = 26,
        // NUEVO — pedido expresamente (casos reales Logista, Acerinox, ArcelorMittal en IBEX
        // 35): este detector no tenía ningún diagnóstico, a diferencia de los patrones de
        // techo/suelo. Con debugSymbol se ve exactamente en qué paso se descarta cada candidato.
        debugSymbol: String? = null
    ): ExhaustionSignal? {
        if (candles.size < 30) {
            if (debugSymbol != null) android.util.Log.i("ExhaustionDiagnostic", "$debugSymbol: descartado — menos de 30 velas (${candles.size})")
            return null
        }

        val recentHighCandle = candles.takeLast(lookbackForHigh).maxByOrNull { it.high } ?: return null
        val recentHighIndex = candles.indexOf(recentHighCandle)
        val afterHigh = candles.subList(recentHighIndex, candles.size)
        if (afterHigh.size < 5) {
            if (debugSymbol != null) android.util.Log.i("ExhaustionDiagnostic", "$debugSymbol: descartado — menos de 5 velas tras el máximo reciente")
            return null
        }

        val recentLowCandle = afterHigh.minByOrNull { it.low } ?: return null
        val declinePercent = (recentLowCandle.low - recentHighCandle.high) / recentHighCandle.high * 100
        if (declinePercent > -minDeclinePercent) {
            if (debugSymbol != null) {
                android.util.Log.i(
                    "ExhaustionDiagnostic",
                    "$debugSymbol: descartado — caída insuficiente (${"%.1f".format(declinePercent)}%%, hace falta ≥${minDeclinePercent}%% en las últimas $lookbackForHigh semanas) — " +
                        "máximo reciente ${recentHighCandle.datetime.take(10)}=${recentHighCandle.high}, mínimo posterior ${recentLowCandle.datetime.take(10)}=${recentLowCandle.low}"
                )
            }
            return null // no ha caído lo suficiente todavía
        }

        // NUEVO — pedido expresamente ("algunos valores de los candidatos son muy laterales"):
        // hasta ahora, la "caída" solo comparaba el máximo Y el mínimo del tramo (dos puntos),
        // sin comprobar que el camino ENTRE ellos fuera de verdad una tendencia bajista y no
        // ruido de un rango lateral que por casualidad tocó esos dos extremos (p. ej. un valor
        // oscilando entre 90 y 105 varias veces, donde cualquier par techo-suelo puede dar un
        // 15%+ de "caída" sin que haya ninguna tendencia real). Se exige que los CIERRES de las
        // velas entre el máximo y el suelo tengan una pendiente bajista limpia de verdad (mismo
        // criterio de regresión lineal + R² que ya usa detectMacdPriceDivergence) — un R² bajo
        // significa que los precios van "para arriba y para abajo" sin dirección clara, aunque
        // los dos extremos por sí solos parezcan una caída.
        val tramoDeclive = afterHigh.subList(0, afterHigh.indexOf(recentLowCandle) + 1)
        if (tramoDeclive.size >= 4) {
            val closes = tramoDeclive.map { it.close }
            val n = closes.size
            val xs = (0 until n).map { it.toDouble() }
            val xMean = xs.average()
            val yMean = closes.average()
            val num = xs.indices.sumOf { (xs[it] - xMean) * (closes[it] - yMean) }
            val den = xs.sumOf { (it - xMean) * (it - xMean) }
            val slope = if (den == 0.0) 0.0 else num / den
            val predicted = xs.map { yMean + slope * (it - xMean) }
            val ssRes = closes.indices.sumOf { (closes[it] - predicted[it]) * (closes[it] - predicted[it]) }
            val ssTot = closes.sumOf { (it - yMean) * (it - yMean) }
            val r2 = if (ssTot == 0.0) 0.0 else 1 - ssRes / ssTot
            if (slope >= 0 || r2 < MIN_DECLINE_R2) {
                if (debugSymbol != null) {
                    android.util.Log.i(
                        "ExhaustionDiagnostic",
                        "$debugSymbol: descartado — caída lateral, sin tendencia bajista limpia (pendiente=${"%.4f".format(slope)}, R²=${"%.2f".format(r2)} < $MIN_DECLINE_R2)"
                    )
                }
                return null
            }
        }

        val reasons = mutableListOf<String>()
        // CORREGIDO — fallo real detectado (casos MERLIN, ACS en IBEX 35): antes esta caída
        // por sí sola ya sumaba 25 de los 40 necesarios para "detected", así que bastaba con
        // UN SOLO indicio débil de recuperación (a veces pura casualidad en un tramo corto,
        // sin ninguna figura de giro real) para cruzar el umbral, aunque el stock siguiera
        // claramente bajista de fondo. Ahora la caída se sigue mostrando (es informativa, forma
        // parte de confidenceScore), pero YA NO cuenta para decidir "detected" — hace falta que
        // los indicios de recuperación por sí solos (mínimos ascendentes + RSI + volumen +
        // progreso hacia resistencia) sumen al menos 40, es decir, que varios coincidan de
        // verdad, no solo uno.
        val declineScore = 25
        var recoveryScore = 0

        reasons += "Caída de ${"%.1f".format(abs(declinePercent))}%% desde el máximo reciente (${recentHighCandle.datetime})"

        // Mínimos ascendentes tras el suelo => pérdida de momentum bajista
        val lowIndex = candles.indexOf(recentLowCandle)
        val afterLow = candles.subList(lowIndex, candles.size)
        var hayMinimosAscendentes = false
        if (afterLow.size >= 3) {
            val lows = afterLow.map { it.low }
            val higherLowSteps = (1 until lows.size).count { lows[it] >= lows[it - 1] }
            if (higherLowSteps >= (lows.size - 1) * 0.6) {
                hayMinimosAscendentes = true
                reasons += "Mínimos ascendentes tras el suelo del ${recentLowCandle.datetime} (la presión vendedora se agota)"
                recoveryScore += 20
            }
        }

        // RSI en sobreventa en el momento del suelo — OJO: esto es el RSI de AQUEL momento
        // pasado (cuando se marcó el mínimo), no el RSI de ahora mismo — son dos lecturas
        // distintas y ambas correctas, pero fácil de confundir si no se distingue bien en el
        // texto (el RSI de hoy puede haber subido mucho desde entonces, como es lo esperable
        // si de verdad hay una recuperación en marcha).
        val rsis = TechnicalAnalysis.calculateRsi(candles)
        val lowRsi = rsis.getOrNull(lowIndex)
        if (lowRsi != null && lowRsi < 40) {
            reasons += "En el mínimo del ${recentLowCandle.datetime.take(10)} el RSI llegó a sobreventa (%.0f) — no es el RSI de ahora, que puede ser distinto".format(lowRsi)
            recoveryScore += 15
        }

        // Volumen: más volumen en la recuperación que en la caída previa (posible acumulación)
        val avgVolBeforeLow = candles.subList(maxOf(0, lowIndex - 5), maxOf(1, lowIndex))
            .mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.average()
        val avgVolAfterLow = afterLow.mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.average()
        if (avgVolBeforeLow != null && avgVolAfterLow != null && avgVolAfterLow > avgVolBeforeLow) {
            reasons += "Volumen en aumento durante la recuperación (posible acumulación)"
            recoveryScore += 15
        }

        // Progreso de recuperación hacia la próxima resistencia
        val levels = TechnicalAnalysis.detectSupportResistanceLevels(candles)
        val currentPrice = candles.last().close
        val nextResistance = levels
            .filter { it.type == LevelType.RESISTANCE && it.price > currentPrice }
            .minByOrNull { it.price }
            ?.price

        // NUEVO — pedido expresamente (mejora #4): antes cualquier resistencia por encima del
        // precio actual valía, aunque estuviera pegada al suelo — con eso, "recuperar el 10% del
        // camino" era casi gratis si la resistencia más cercana estaba muy cerca. Ahora se exige
        // que la resistencia esté al menos un 6% por encima del suelo, para que el "camino"
        // hacia ella sea de verdad significativo, no una distancia minúscula.
        val distanciaResistenciaDesdeElSuelo = nextResistance?.let { (it - recentLowCandle.low) / recentLowCandle.low * 100 }
        val resistenciaSuficientementeLejos = distanciaResistenciaDesdeElSuelo != null && distanciaResistenciaDesdeElSuelo >= 6.0

        val recoveryProgress = if (resistenciaSuficientementeLejos) {
            nextResistance?.let { resistance ->
                val range = resistance - recentLowCandle.low
                if (range <= 0) null
                else ((currentPrice - recentLowCandle.low) / range * 100).coerceIn(0.0, 100.0)
            }
        } else null

        if (recoveryProgress != null && recoveryProgress > 10) {
            reasons += "Ya recuperado un %.0f%% del camino hacia la próxima resistencia (%.2f)".format(
                recoveryProgress, nextResistance
            )
            recoveryScore += 25
        }

        // CORREGIDO — fallo real reportado: candidatos cuya última vela ES el mínimo reciente
        // (precio todavía haciendo mínimos ahora mismo, sin ninguna vela de reacción posterior)
        // podían marcar detected=true igualmente, con el "progreso de recuperación" calculado
        // contra el cierre de esa MISMA vela (currentPrice = candles.last().close, que es la vela
        // del propio suelo) — no es un giro real, es solo que esa vela cerró algo por encima de
        // su mínimo intra-semana, cosa que pasa casi siempre y no indica ningún cambio de
        // tendencia. Un giro de verdad necesita, como mínimo, una vela POSTERIOR al suelo que
        // confirme que el precio ha dejado de hacer mínimos — si no hay ninguna todavía, se
        // descarta aquí explícitamente, sin importar lo que sumen los demás indicios.
        val hayVelaPosteriorAlSuelo = lowIndex < candles.lastIndex
        if (!hayVelaPosteriorAlSuelo && debugSymbol != null) {
            android.util.Log.i(
                "ExhaustionDiagnostic",
                "$debugSymbol: forzado detected=false — la última vela ES el mínimo reciente, sin ninguna vela de reacción posterior todavía"
            )
        }

        // NUEVO — pedido expresamente: "además del giro tiene que haber una recuperación de la
        // tendencia alcista sólida". Antes, cualquier combinación de indicios que sumara ≥40
        // bastaba (p. ej. RSI en sobreventa + progreso hacia resistencia, SIN que hubiera mínimos
        // ascendentes de verdad) — eso podía dar detected=true con una sola vela de rebote que
        // luego no se sostiene. Ahora se exigen, ADEMÁS del recoveryScore≥40 y de que haya vela
        // posterior al suelo, dos condiciones que sí representan una recuperación sólida y no un
        // simple rebote puntual:
        //  1) Mínimos ascendentes tras el suelo (hayMinimosAscendentes) — la propia serie de
        //     precios, no un indicador derivado, confirma que la presión vendedora se agota de
        //     forma sostenida en el tiempo, no en una sola vela.
        //  2) Rebote mínimo real desde el suelo (≥5%) — el precio actual tiene que haberse
        //     alejado de verdad del mínimo, no solo haber cerrado por encima de su propio mínimo
        //     intra-vela (ver el arreglo de "hayVelaPosteriorAlSuelo" más arriba, que ya cubría el
        //     caso de una sola vela; esto cubre el caso de varias velas con un rebote insignificante).
        val reboteDesdeElSueloPercent = if (recentLowCandle.low > 0) (currentPrice - recentLowCandle.low) / recentLowCandle.low * 100 else 0.0
        val hayReboteSolido = reboteDesdeElSueloPercent >= MIN_REBOTE_DESDE_SUELO_PERCENT
        val hayRecuperacionSolida = hayMinimosAscendentes && hayReboteSolido

        if (debugSymbol != null) {
            android.util.Log.i(
                "ExhaustionDiagnostic",
                "$debugSymbol: caída=${"%.1f".format(declinePercent)}%% (desde ${recentHighCandle.datetime.take(10)} hasta el suelo ${recentLowCandle.datetime.take(10)}) — " +
                    "recoveryScore=$recoveryScore/75 (hace falta ≥40) — mínimosAscendentes=$hayMinimosAscendentes — " +
                    "rebote=${"%.1f".format(reboteDesdeElSueloPercent)}%% (hace falta ≥$MIN_REBOTE_DESDE_SUELO_PERCENT%%) — " +
                    "detected=${recoveryScore >= 40 && hayVelaPosteriorAlSuelo && hayRecuperacionSolida} — razones: ${reasons.joinToString(" | ")}"
            )
        }

        return ExhaustionSignal(
            detected = recoveryScore >= 40 && hayVelaPosteriorAlSuelo && hayRecuperacionSolida,
            confidenceScore = (declineScore + recoveryScore).coerceIn(0, 100),
            reasons = reasons,
            recentHigh = recentHighCandle.high,
            recentLow = recentLowCandle.low,
            recentLowDate = recentLowCandle.datetime,
            declinePercentFromRecentHigh = declinePercent,
            nextResistanceTarget = nextResistance,
            recoveryProgressToResistancePercent = recoveryProgress
        )
    }
}
