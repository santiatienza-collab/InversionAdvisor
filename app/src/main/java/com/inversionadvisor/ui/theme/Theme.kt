package com.inversionadvisor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Tema oscuro "Grafito + Verde-azulado neón" — pedido expresamente ("menús, pestañas,
 * desplegables y fondos claros" a fondos oscuros, "algo llamativo, corporativo y elegante",
 * "todo homogéneo como un tema sólido"). Un único ColorScheme aplicado en la raíz
 * (MainActivity, ver InversionAdvisorTheme más abajo) para que TODO lo que ya usa los valores
 * por defecto de Material3 (Card/Surface/Scaffold sin colores propios, TabRow, DropdownMenu,
 * OutlinedTextField, AlertDialog, el color de texto por defecto...) se oscurezca de golpe, de
 * forma coherente, en vez de ir parcheando pantalla por pantalla.
 *
 * DELIBERADAMENTE NO TOCADO — pedido expresamente: los colores de los paneles de indicadores
 * (semáforos de Fear & Greed/MOVE, AAII, rotación sectorial...) y de las tarjetas de candidatos
 * (verde/ámbar/rojo de riesgo-recompensa, RiskColors.kt, FactorGrade, badges de sentimiento...)
 * son hardcoded a propósito en sus propios ficheros, no vienen de MaterialTheme.colorScheme —
 * así que este cambio de tema NO los afecta, seguirán exactamente igual.
 */
val GraphiteBackground = Color(0xFF16181D)
val GraphiteSurface = Color(0xFF202329)
val GraphiteSurfaceVariant = Color(0xFF262A31)
val GraphiteOutline = Color(0xFF3A3D45)
val NeonTeal = Color(0xFF2DD4BF)
val NeonTealDim = Color(0xFF1B4B46)
val TextLight = Color(0xFFF0F0F0)
val TextLightDim = Color(0xFFB7BAC1)
/** Texto/iconos SOBRE el acento turquesa (que es un color claro/brillante) — oscuro para que
 *  haya contraste de verdad, nunca texto claro sobre este acento. */
val OnNeonTeal = Color(0xFF07211D)
val DarkErrorLight = Color(0xFFFF6B6B)
val DarkErrorContainer = Color(0xFF3B0A0A)

private val InversionAdvisorDarkColorScheme = darkColorScheme(
    primary = NeonTeal,
    onPrimary = OnNeonTeal,
    primaryContainer = NeonTealDim,
    onPrimaryContainer = TextLight,
    secondary = NeonTeal,
    onSecondary = OnNeonTeal,
    background = GraphiteBackground,
    onBackground = TextLight,
    surface = GraphiteSurface,
    onSurface = TextLight,
    surfaceVariant = GraphiteSurfaceVariant,
    onSurfaceVariant = TextLightDim,
    outline = GraphiteOutline,
    error = DarkErrorLight,
    onError = DarkErrorContainer,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkErrorLight
)

@Composable
fun InversionAdvisorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InversionAdvisorDarkColorScheme, content = content)
}
