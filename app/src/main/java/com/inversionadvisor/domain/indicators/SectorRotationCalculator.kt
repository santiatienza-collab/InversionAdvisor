package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Candle
import com.inversionadvisor.domain.model.RotationHorizon
import com.inversionadvisor.domain.model.SectorHorizonReturn
import com.inversionadvisor.domain.model.SectorPerformance

/**
 * Calcula, para cada ETF sectorial, su variación de precio y su FUERZA
 * RELATIVA frente al S&P 500 en varios horizontes (1 mes, 3 meses, 6 meses)
 * — la técnica estándar para identificar rotación sectorial: no basta con
 * que un sector suba, tiene que subir MÁS que el mercado en general para
 * considerarse "en auge" de verdad. Se calculan varios horizontes a la vez
 * porque las rotaciones sectoriales reales suelen tardar varios meses en
 * confirmarse — una ventana de un solo mes puede ser ruido.
 *
 * Opera sobre velas SEMANALES (ChartRange.ONE_YEAR da 52 semanas, de sobra
 * para cubrir hasta el horizonte de 6 meses/26 semanas) de una sola
 * petición por símbolo, sin tener que pedir varios rangos distintos a la
 * API para cada horizonte.
 *
 * "En auge" (isConsistentLeader) exige que LOS TRES plazos sean positivos a
 * la vez — pedido expresamente así, antes bastaba con que la MEDIA fuera
 * positiva (un sector podía colarse como "en auge" con 1 mes muy flojo
 * compensado por un 6 meses muy fuerte, o al revés). averageRelativeStrengthPercent
 * (usado para ORDENAR sectores por fuerza, no para la condición "en auge" en
 * sí) es ahora una media PONDERADA, no una media simple — dando más peso al
 * plazo de 1 mes que al de 3, y más al de 3 que al de 6 (pedido expresamente
 * así: 1 mes=50 puntos, 3 meses=35 puntos, 6 meses=15 puntos, sobre 100).
 */
object SectorRotationCalculator {

    /** Pesos de la media ponderada (suman 100) — 1 mes pesa más que 3, y 3 más que 6. */
    private val HORIZON_WEIGHTS: Map<RotationHorizon, Double> = mapOf(
        RotationHorizon.ONE_MONTH to 50.0,
        RotationHorizon.THREE_MONTHS to 35.0,
        RotationHorizon.SIX_MONTHS to 15.0
    )

    fun calculate(
        candlesBySymbol: Map<String, List<Candle>>,
        sectorNames: Map<String, String>,
        benchmarkCandles: List<Candle>
    ): List<SectorPerformance> {
        val benchmarkReturns = RotationHorizon.entries.mapNotNull { horizon ->
            periodReturn(benchmarkCandles, horizon.weeksBack)?.let { horizon to it }
        }.toMap()

        return candlesBySymbol.mapNotNull { (symbol, candles) ->
            val returns = RotationHorizon.entries.mapNotNull { horizon ->
                val sectorReturn = periodReturn(candles, horizon.weeksBack) ?: return@mapNotNull null
                val benchmarkReturn = benchmarkReturns[horizon] ?: return@mapNotNull null
                horizon to SectorHorizonReturn(
                    changePercent = sectorReturn,
                    relativeStrengthPercent = sectorReturn - benchmarkReturn
                )
            }.toMap()

            // Si no se pudo calcular ni un solo horizonte (velas insuficientes), se descarta el sector.
            if (returns.isEmpty()) return@mapNotNull null

            // Media PONDERADA (50/35/15) en vez de media simple — si falta algún horizonte
            // (velas insuficientes para ese plazo en concreto), se renormaliza sobre los pesos
            // de los horizontes SÍ disponibles, para no dividir entre 100 con menos del 100%
            // de peso real presente.
            val totalWeightAvailable = returns.keys.sumOf { HORIZON_WEIGHTS[it] ?: 0.0 }
            val weightedAverage = if (totalWeightAvailable > 0.0) {
                returns.entries.sumOf { (horizon, r) -> r.relativeStrengthPercent * (HORIZON_WEIGHTS[horizon] ?: 0.0) } / totalWeightAvailable
            } else {
                returns.values.map { it.relativeStrengthPercent }.average()
            }

            SectorPerformance(
                etfSymbol = symbol,
                sectorName = sectorNames[symbol] ?: symbol,
                returns = returns,
                averageRelativeStrengthPercent = weightedAverage,
                isConsistentLeader = returns.size == RotationHorizon.entries.size &&
                    returns.values.all { it.relativeStrengthPercent > 0 }
            )
        }.sortedByDescending { it.averageRelativeStrengthPercent }
    }

    /** Variación de precio entre la última vela y la de hace [weeksBack] semanas, en %. */
    private fun periodReturn(candles: List<Candle>, weeksBack: Int): Double? {
        if (candles.size < weeksBack + 1) return null
        val recent = candles.last()
        val past = candles[candles.size - 1 - weeksBack]
        if (past.close == 0.0) return null
        return (recent.close - past.close) / past.close * 100
    }
}
