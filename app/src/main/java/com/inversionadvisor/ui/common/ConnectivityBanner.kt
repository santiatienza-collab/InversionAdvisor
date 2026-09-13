package com.inversionadvisor.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.inversionadvisor.data.connectivity.NetworkConnectivityObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce

private enum class ConnectivityBannerKind { LOST, RESTORED }

/**
 * Banner en la parte superior de la pantalla, pedido expresamente así: rojo con "Se ha perdido
 * la conexión a Internet" al perderla, verde con "Conexión a Internet recuperada" al
 * recuperarla — cada uno desaparece solo a los 5 segundos.
 *
 * NO se muestra nada en el arranque de la app aunque no haya conexión en ese momento — solo
 * avisa de un CAMBIO de estado (de conectado a desconectado, o al revés), no del estado inicial
 * (que ya se ve de sobra por los propios datos que no cargan).
 *
 * CAMBIADO — a petición expresa ("elimina isBusy, dame alternativas") — ya no depende de que la
 * app sepa de antemano "esto es un escaneo pesado" (frágil: dos veces se ha quedado mal
 * calculado, ver histórico de NetworkActivityTracker). Ahora, al ver un cambio de estado, se
 * comprueba si ha llegado una respuesta de red REAL hace muy poco (ver
 * NetworkActivityTracker.hadSuccessfulNetworkResponseRecently) — si es así, el cambio se
 * descarta como ruido (típico de una avalancha de peticiones durante Top10/"Analizar" un
 * mercado, que satura la red lo bastante como para que Android detecte caídas puntuales que no
 * son reales), sin importar qué parte de la app generó ese tráfico. Si de verdad se corta la
 * red, no hay respuestas que anotar, así que esta comprobación deja de descartar nada por sí
 * sola — no hace falta ningún aviso de "ya ha terminado" para que el banner vuelva a funcionar.
 *
 * Cuando se descarta un "perdida" como ruido, tampoco se actualiza el estado "anterior" que se
 * compara en la siguiente vuelta — así, si la app nunca llegó a considerar que la conexión se
 * había perdido de verdad, tampoco muestra luego un "recuperada" que no vendría a cuento.
 */
@Composable
fun ConnectivityBanner(
    observer: NetworkConnectivityObserver,
    modifier: Modifier = Modifier
) {
    var banner by remember { mutableStateOf<ConnectivityBannerKind?>(null) }

    LaunchedEffect(observer) {
        var previous: Boolean? = null
        observer.observe().debounce(6_000L).collectLatest { connected ->
            val prev = previous
            if (prev == null) {
                previous = connected
                return@collectLatest // estado inicial al arrancar, no avisa
            }
            // Ruido probable (avalancha de peticiones de la app saturando la red un instante,
            // no una caída real) — se ignora ESTE cambio por completo: ni se muestra el banner
            // ni se actualiza "previous", como si no hubiera pasado.
            if (com.inversionadvisor.data.connectivity.NetworkActivityTracker.hadSuccessfulNetworkResponseRecently()) {
                return@collectLatest
            }
            previous = connected
            if (prev && !connected) {
                banner = ConnectivityBannerKind.LOST
            } else if (!prev && connected) {
                banner = ConnectivityBannerKind.RESTORED
            }
        }
    }

    LaunchedEffect(banner) {
        if (banner != null) {
            delay(5_000L)
            banner = null
        }
    }

    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier
    ) {
        val kind = banner
        val (color, text) = when (kind) {
            ConnectivityBannerKind.LOST -> Color(0xFFC62828) to "Se ha perdido la conexión a Internet"
            ConnectivityBannerKind.RESTORED -> Color(0xFF2E7D32) to "Conexión a Internet recuperada"
            null -> return@AnimatedVisibility
        }
        Surface(color = color, modifier = Modifier.fillMaxWidth()) {
            Text(
                text,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
            )
        }
    }
}
