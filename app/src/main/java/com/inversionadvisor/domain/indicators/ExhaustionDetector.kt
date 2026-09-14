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
        lookbackForHigh: Int = 26
    ): ExhaustionSignal? {
        if (candles.size < 30) return null

        val recentHighCandle = candles.takeLast(lookbackForHigh).maxByOrNull { it.high } ?: return null
        val recentHighIndex = candles.indexOf(recentHighCandle)
        val afterHigh = candles.subList(recentHighIndex, candles.size)
        if (afterHigh.size < 5) return null

        val recentLowCandle = afterHigh.minByOrNull { it.low } ?: return null
        val declinePercent = (recentLowCandle.low - recentHighCandle.high) / recentHighCandle.high * 100
        if (declinePercent > -minDeclinePercent) return null // no ha caído lo suficiente todavía

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
        if (afterLow.size >= 3) {
            val lows = afterLow.map { it.low }
            val higherLowSteps = (1 until lows.size).count { lows[it] >= lows[it - 1] }
            if (higherLowSteps >= (lows.size - 1) * 0.6) {
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

        return ExhaustionSignal(
            detected = recoveryScore >= 40,
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
