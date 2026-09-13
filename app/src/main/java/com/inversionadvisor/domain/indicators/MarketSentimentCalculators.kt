package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Quote
import com.inversionadvisor.domain.model.RiskLevel
import kotlin.math.roundToInt

/**
 * Analiza la volatilidad. Con Yahoo Finance ya se puede pedir el VIX real
 * (símbolo ^VIX). Umbrales de 5 niveles — pedido expresamente así (más granular que el clásico
 * de 3 niveles usado al principio del proyecto): <12 complacencia extrema, 12-20 entorno
 * normal/estable, 20-30 alarma moderada, 30-45 alarma alta (pánico), >45 alarma extrema
 * (crisis sistémica).
 */
object VolatilityAnalyzer {

    fun analyzeRealVix(vixQuote: Quote): com.inversionadvisor.domain.model.VixStatus {
        val value = vixQuote.close
        val (riskLevel, tierLabel) = when {
            value < 12.0 -> RiskLevel.LOW to "Complacencia extrema"
            value < 20.0 -> RiskLevel.LOW to "Entorno normal, estable"
            value < 30.0 -> RiskLevel.MEDIUM to "Alarma moderada"
            value < 45.0 -> RiskLevel.HIGH to "Alarma alta (pánico)"
            else -> RiskLevel.HIGH to "Alarma extrema (crisis sistémica)"
        }
        return com.inversionadvisor.domain.model.VixStatus(
            value = value,
            changePercent = vixQuote.changePercent,
            riskLevel = riskLevel,
            alertTriggered = value >= 20.0,
            isProxy = false,
            proxySymbol = null,
            tierLabel = tierLabel
        )
    }

    /** Legado: análisis basado en un ETF proxy (p. ej. VIXY) cuando no se tiene el VIX real. */
    fun analyze(vixyQuote: Quote): com.inversionadvisor.domain.model.VixStatus {
        val changePercent = vixyQuote.changePercent
        val riskLevel = when {
            changePercent >= 5.0 -> RiskLevel.HIGH
            changePercent >= 2.0 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return com.inversionadvisor.domain.model.VixStatus(
            value = vixyQuote.close,
            changePercent = changePercent,
            riskLevel = riskLevel,
            alertTriggered = riskLevel == RiskLevel.HIGH,
            isProxy = true,
            proxySymbol = vixyQuote.symbol
        )
    }
}

/**
 * Índice de miedo/avaricia propio (0-100), combinando:
 *  - Volatilidad (40%): la subida de VIXY hoy, invertida (más VIXY = más miedo)
 *  - Amplitud (30%): % de ETFs sectoriales en verde hoy
 *  - Momentum (30%): variación de SPY hoy, como proxy del S&P 500
 */
object FearGreedCalculator {
    fun calculate(
        vixyChangePercent: Double,
        sectorChangesPercent: List<Double>,
        spyChangePercent: Double
    ): FearGreedResult {
        val volatilityScore = (50 - vixyChangePercent * 5).coerceIn(0.0, 100.0)
        val breadthScore = if (sectorChangesPercent.isNotEmpty()) {
            (sectorChangesPercent.count { it > 0 }.toDouble() / sectorChangesPercent.size) * 100
        } else 50.0
        val momentumScore = (50 + spyChangePercent * 10).coerceIn(0.0, 100.0)

        val score = ((volatilityScore * 0.4) + (breadthScore * 0.3) + (momentumScore * 0.3))
            .roundToInt().coerceIn(0, 100)

        val (label, risk) = when {
            score < 25 -> "Miedo extremo" to RiskLevel.HIGH
            score < 45 -> "Miedo" to RiskLevel.MEDIUM
            score < 55 -> "Neutral" to RiskLevel.MEDIUM
            score < 75 -> "Codicia" to RiskLevel.LOW
            else -> "Codicia extrema" to RiskLevel.MEDIUM // extremos en ambos lados implican riesgo de giro
        }
        return FearGreedResult(score, label, risk)
    }
}

/**
 * Liquidez aproximada por el volumen relativo de SPY frente a su media —
 * un volumen muy por debajo de lo normal se interpreta como más riesgo
 * (movimientos más erráticos, peor ejecución de órdenes).
 */
object LiquidityAnalyzer {
    fun analyze(spyQuote: Quote): LiquidityStatus {
        val rv = spyQuote.relativeVolume
        val risk = when {
            rv < 0.5 -> RiskLevel.HIGH
            rv < 0.8 -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
        return LiquidityStatus(relativeVolume = rv, riskLevel = risk)
    }
}

/**
 * Nivel de riesgo asociado al sentimiento AAII (alcistas/neutral/bajistas).
 * Lectura contraria, como es habitual con este indicador: mucho pesimismo
 * (spread muy negativo) se interpreta como oportunidad; mucho optimismo
 * (spread muy positivo) como señal de precaución, no como algo "bueno".
 * Umbrales basados en los rangos históricos típicos del bull-bear spread.
 */
object AaiiSentimentRiskMapper {
    fun mapRisk(bullBearSpread: Double): RiskLevel = when {
        bullBearSpread <= -20.0 -> RiskLevel.LOW      // pesimismo extremo -> contrarian: oportunidad
        bullBearSpread >= 20.0 -> RiskLevel.HIGH       // optimismo extremo -> contrarian: precaución
        else -> RiskLevel.MEDIUM
    }
}

/**
 * Traduce la calificación en inglés que da CNN ("extreme fear", "fear",
 * "neutral", "greed", "extreme greed") a español y a un nivel de riesgo.
 */
object CnnFearGreedRatingMapper {
    fun mapRating(ratingRaw: String): Pair<String, RiskLevel> = when (ratingRaw.trim().lowercase()) {
        "extreme fear" -> "Miedo extremo" to RiskLevel.HIGH
        "fear" -> "Miedo" to RiskLevel.MEDIUM
        "neutral" -> "Neutral" to RiskLevel.MEDIUM
        "greed" -> "Codicia" to RiskLevel.LOW
        "extreme greed" -> "Codicia extrema" to RiskLevel.MEDIUM // extremos en ambos lados = riesgo de giro
        else -> ratingRaw to RiskLevel.MEDIUM
    }

    /**
     * Clasificación por PUNTUACIÓN (0-100), con los umbrales exactos pedidos expresamente — en
     * vez de fiarse de la etiqueta en inglés que da la propia CNN (mapRating de arriba), que
     * podría no coincidir exactamente con estos cortes. Se usa para los bonos "Teoría de la
     * Opinión Contraria" de SellTimingAnalyzer.
     *
     *  0-25   Miedo extremo
     *  26-44  Miedo
     *  45-55  Neutral
     *  56-75  Codicia
     *  76-100 Codicia extrema
     */
    fun mapScore(score: Double): String = when {
        score <= 25.0 -> "extreme fear"
        score <= 44.0 -> "fear"
        score <= 55.0 -> "neutral"
        score <= 75.0 -> "greed"
        else -> "extreme greed"
    }

    /**
     * Igual que mapScore(), pero directo a etiqueta en español + nivel de riesgo — para mostrar
     * en la interfaz (velocímetro del Panel, etc.), con los MISMOS umbrales de puntuación.
     * Encontrado y corregido: el velocímetro usaba la etiqueta de mapRating() (el texto en
     * inglés de la propia CNN), que podía no coincidir con estos cortes — de ahí que marcara
     * "Codicia" con una puntuación de 55, cuando 55 es Neutral según estos umbrales.
     */
    fun mapScoreToLabel(score: Double): Pair<String, RiskLevel> = when (mapScore(score)) {
        "extreme fear" -> "Miedo extremo" to RiskLevel.HIGH
        "fear" -> "Miedo" to RiskLevel.MEDIUM
        "neutral" -> "Neutral" to RiskLevel.MEDIUM
        "greed" -> "Codicia" to RiskLevel.LOW
        else -> "Codicia extrema" to RiskLevel.MEDIUM
    }
}

/**
 * Índice de correlación implícita del S&P 500 (^COR3M) — mide cuánto se mueven a la vez las
 * acciones del índice. Tabla de rangos ajustada y corregida a petición expresa (versión más
 * técnicamente robusta que la primera propuesta):
 *   0-15: correlación mínima/dispersión extrema — mercado de selección de acciones, entorno
 *         ideal para gestión activa, máxima calma o complacencia.
 *   15-30: correlación baja-media/dispersión alta — comportamiento diferenciado, el estado
 *          normal y más saludable de un mercado alcista estructurado.
 *   30-45: zona de transición — el mercado empieza a reaccionar más a factores macro comunes
 *          que a fundamentales individuales.
 *   45-65: correlación elevada — el mercado se mueve en bloque, señal de estrés latente o
 *          rotación sectorial violenta.
 *   65-100: correlación muy alta/dispersión mínima — pánico generalizado o capitulación,
 *           movimiento sistémico, riesgo extremo.
 */
object CorrelationAnalyzer {
    fun analyze(quote: Quote): com.inversionadvisor.domain.model.CorrelationStatus {
        val value = quote.close
        val tierLabel = when {
            value < 15.0 -> "Correlación mínima, dispersión extrema — mercado de selección de acciones (stock-picker)"
            value < 30.0 -> "Correlación baja-media — comportamiento diferenciado, estado normal y saludable"
            value < 45.0 -> "Zona de transición — reacción a factores macro comunes"
            value < 65.0 -> "Correlación elevada — el mercado se mueve en bloque (estrés latente)"
            else -> "Correlación muy alta — pánico generalizado o capitulación"
        }
        return com.inversionadvisor.domain.model.CorrelationStatus(
            value = value,
            changePercent = quote.changePercent,
            tierLabel = tierLabel
        )
    }
}
