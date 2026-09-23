package com.inversionadvisor.ui.common

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Aviso en neón" — pedido expresamente así para las penalizaciones y bonos que se añaden
 * APARTE de las fórmulas (Fear & Greed, AAII, resultados trimestrales, cruce de medias,
 * divergencia momentum/precio, patrón de bandera, tendencia bajista consolidada...): un color
 * más vivo que los normales de la app, para que destaque como "esto es distinto de las
 * categorías normales de la fórmula".
 *
 * SIN PARPADEO — quitado a petición expresa (antes usaba rememberInfiniteTransition para
 * parpadear de verdad). Ahora el color neón es fijo, sin animación.
 *
 * [positive]: true para BONOS (suman puntos) -> neón VERDE; false para PENALIZACIONES (restan
 * puntos) -> neón ROJO.
 */
@Composable
fun BlinkingNeonBadge(text: String, modifier: Modifier = Modifier, positive: Boolean = false, overrideColor: Color? = null) {
    // Rojo/verde neón (más vivos/saturados que los "normales" de la app) — a propósito
    // distintos de RiskColors para que no se confundan visualmente con el semáforo habitual.
    // NUEVO — pedido expresamente: overrideColor permite un tercer color (naranja) para avisos
    // AMBIGUOS que no son ni claramente positivos ni claramente negativos — si se pasa, gana
    // sobre el verde/rojo de "positive".
    val neonColor = overrideColor ?: if (positive) Color(0xFF00E676) else Color(0xFFFF1744)
    // CAMBIADO a petición expresa (tema oscuro): el fondo neón sólido contrastaba demasiado con
    // el resto de la app — ahora se oscurece igual que los chips de factores de Top10 (ver
    // FactorGrade.toColor() en ScreenerScreen.kt: fondo = color.copy(alpha=0.18f), texto = el
    // mismo color a tope) — incluso conservando el mismo tono (naranja sigue naranja, rojo sigue
    // rojo, verde sigue verde), solo con el contraste ya pensado para fondo oscuro.
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = neonColor.copy(alpha = 0.18f),
        modifier = modifier
    ) {
        Text(
            text,
            color = neonColor,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
