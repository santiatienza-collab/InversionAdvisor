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
     */
    fun detect(
        candles: List<Candle>,
        minDeclinePercent: Double = 10.0,
        lookbackForHigh: Int = 52
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
        var score = 0

        reasons += "Caída de ${"%.1f".format(abs(declinePercent))}%% desde el máximo reciente (${recentHighCandle.datetime})"
        score += 25

        // Mínimos ascendentes tras el suelo => pérdida de momentum bajista
        val lowIndex = candles.indexOf(recentLowCandle)
        val afterLow = candles.subList(lowIndex, candles.size)
        if (afterLow.size >= 3) {
            val lows = afterLow.map { it.low }
            val higherLowSteps = (1 until lows.size).count { lows[it] >= lows[it - 1] }
            if (higherLowSteps >= (lows.size - 1) * 0.6) {
                reasons += "Mínimos ascendentes tras el suelo del ${recentLowCandle.datetime} (la presión vendedora se agota)"
                score += 20
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
            score += 15
        }

        // Volumen: más volumen en la recuperación que en la caída previa (posible acumulación)
        val avgVolBeforeLow = candles.subList(maxOf(0, lowIndex - 5), maxOf(1, lowIndex))
            .mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.average()
        val avgVolAfterLow = afterLow.mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.average()
        if (avgVolBeforeLow != null && avgVolAfterLow != null && avgVolAfterLow > avgVolBeforeLow) {
            reasons += "Volumen en aumento durante la recuperación (posible acumulación)"
            score += 15
        }

        // Progreso de recuperación hacia la próxima resistencia
        val levels = TechnicalAnalysis.detectSupportResistanceLevels(candles)
        val currentPrice = candles.last().close
        val nextResistance = levels
            .filter { it.type == LevelType.RESISTANCE && it.price > currentPrice }
            .minByOrNull { it.price }
            ?.price

        val recoveryProgress = nextResistance?.let { resistance ->
            val range = resistance - recentLowCandle.low
            if (range <= 0) null
            else ((currentPrice - recentLowCandle.low) / range * 100).coerceIn(0.0, 100.0)
        }

        if (recoveryProgress != null && recoveryProgress > 10) {
            reasons += "Ya recuperado un %.0f%% del camino hacia la próxima resistencia (%.2f)".format(
                recoveryProgress, nextResistance
            )
            score += 25
        }

        return ExhaustionSignal(
            detected = score >= 40,
            confidenceScore = score.coerceIn(0, 100),
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
