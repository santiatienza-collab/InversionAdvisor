package com.inversionadvisor.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.inversionadvisor.ui.theme.NeonTeal
import kotlinx.coroutines.launch

/**
 * Botón flotante "volver arriba" — pedido expresamente para TODAS las pantallas con scroll de la
 * app. Transparente (no un FAB sólido corriente) para no tapar contenido de debajo ni competir
 * visualmente con los botones normales de la app — solo un círculo tenue con una flecha, que
 * aparece/desaparece con un fundido suave según se hace scroll.
 *
 * Colócalo dentro de un Box que envuelva el LazyColumn/Column con scroll, alineado
 * Alignment.BottomEnd — ver el uso en DashboardScreen/ScreenerScreen/NoticiasScreen/
 * SearchScreen/StockDetailScreen.
 */
@Composable
fun ScrollToTopButtonForLazyList(state: LazyListState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    // Visible solo cuando ya se ha bajado un poco — no tiene sentido mostrarlo si ya se está arriba.
    val visible by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 400 } }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        ScrollToTopCircle(onClick = { scope.launch { state.animateScrollToItem(0) } })
    }
}

@Composable
fun ScrollToTopButtonForScrollState(state: ScrollState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val visible by remember { derivedStateOf { state.value > 400 } }
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        ScrollToTopCircle(onClick = { scope.launch { state.animateScrollTo(0) } })
    }
}

@Composable
private fun ScrollToTopCircle(onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Surface(
        shape = CircleShape,
        // Transparente de verdad (no un FAB sólido) — pedido expresamente así, para que no
        // contraste ni tape contenido, solo un círculo tenue sobre el fondo oscuro del tema.
        color = Color.Black.copy(alpha = 0.35f),
        modifier = Modifier
            .padding(16.dp)
            .size(44.dp)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Icon(
            Icons.Default.KeyboardArrowUp,
            contentDescription = "Volver arriba",
            tint = NeonTeal,
            modifier = Modifier.padding(10.dp)
        )
    }
}
