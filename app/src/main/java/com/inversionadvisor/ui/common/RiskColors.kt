package com.inversionadvisor.ui.common

import androidx.compose.ui.graphics.Color
import com.inversionadvisor.domain.model.RiskLevel

/** Verde = bueno para invertir, ámbar = precaución, rojo = riesgo alto. */
fun RiskLevel.toColor(): Color = when (this) {
    RiskLevel.LOW -> Color(0xFF2E7D32)
    RiskLevel.MEDIUM -> Color(0xFFF9A825)
    RiskLevel.HIGH -> Color(0xFFC62828)
}
