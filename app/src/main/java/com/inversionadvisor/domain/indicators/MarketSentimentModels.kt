package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.RiskLevel

data class FearGreedResult(
    /** 0-100. 0 = miedo extremo, 100 = codicia extrema. */
    val score: Int,
    val label: String,
    val riskLevel: RiskLevel
)

data class LiquidityStatus(
    /** Volumen de hoy relativo a su media — 1.0 = normal. */
    val relativeVolume: Double,
    val riskLevel: RiskLevel
)


