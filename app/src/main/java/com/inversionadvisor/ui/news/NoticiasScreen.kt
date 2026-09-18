package com.inversionadvisor.ui.news

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.inversionadvisor.domain.model.FUENTES_NOTICIAS
import com.inversionadvisor.domain.model.Sentimiento
import com.inversionadvisor.domain.model.Titular
import kotlinx.coroutines.launch

private enum class FiltroSentimiento(val etiqueta: String) {
    TODAS("Todas"),
    POSITIVAS("Positivas"),
    NEGATIVAS("Negativas")
}

private val VerdePositivo = Color(0xFF2E7D32)
// CAMBIADO a petición expresa (tema oscuro "Grafito + Verde-azulado neón"): las variantes
// "Claro" (pensadas para fondo claro, pastel casi blanco) pasan a ser variantes OSCURAS del
// mismo tono — mismo papel (fondo de chip sin seleccionar, arranque del degradado tras cada
// titular, pista de la barra de sentimiento), pero coherentes con el resto del tema oscuro en
// vez de un parche claro sobre fondo oscuro.
private val VerdePositivoFondo = Color(0xFF1B3A22)
private val RojoNegativo = Color(0xFFC62828)
private val RojoNegativoFondo = Color(0xFF3A1B1E)
private val GrisNeutro = Color(0xFF616161)
private val GrisNeutroFondo = Color(0xFF2A2D33)

/**
 * Identidad visual de cada medio — pedido expresamente para que la sección de Noticias sea
 * "más visual, colorida, amena y llamativa" (antes cada pestaña era visualmente idéntica salvo
 * por el nombre). Colores inspirados en la identidad de marca de cada medio (no son logos ni
 * imágenes, solo un color de acento y un emoji), para que de un vistazo se distinga de qué
 * fuente viene cada titular sin tener que leer el nombre.
 */
private data class EstiloFuente(val colorAcento: Color, val emoji: String)

@Composable
private fun estiloParaFuente(nombreFuente: String): EstiloFuente = when {
    nombreFuente.contains("WSJ") -> EstiloFuente(Color(0xFF1A1A2E), "\uD83D\uDCB0")
    nombreFuente.contains("Financial Times") -> EstiloFuente(Color(0xFF990F3D), "\uD83D\uDCB9")
    nombreFuente.contains("NYT") -> EstiloFuente(Color(0xFF000000), "\uD83D\uDDDE\uFE0F")
    nombreFuente.contains("Bloomberg") -> EstiloFuente(Color(0xFFFF6600), "\u26A1")
    else -> EstiloFuente(MaterialTheme.colorScheme.primary, "\uD83D\uDCF0")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NoticiasScreen(viewModel: NoticiasViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    val pagerState = rememberPagerState(pageCount = { FUENTES_NOTICIAS.size })
    val scope = rememberCoroutineScope()

    Column(modifier = Modifier.fillMaxSize()) {
        // Deslizable (a diferencia del TabRow fijo de las pestañas principales de
        // MainActivity): con 4 periódicos, alguno con nombre largo ("Financial Times"),
        // puede no caber todo centrado en pantallas estrechas.
        // Cada pestaña lleva ahora el emoji + color de acento de su medio (ver
        // estiloParaFuente), para que la fila de pestañas en sí ya sea colorida, no solo texto
        // plano — y el indicador de la pestaña seleccionada usa ese mismo color de acento.
        val estiloSeleccionado = estiloParaFuente(FUENTES_NOTICIAS[pagerState.currentPage].nombre)
        ScrollableTabRow(
            selectedTabIndex = pagerState.currentPage,
            edgePadding = 12.dp,
            contentColor = estiloSeleccionado.colorAcento
        ) {
            FUENTES_NOTICIAS.forEachIndexed { index, fuente ->
                val estilo = estiloParaFuente(fuente.nombre)
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = {
                        Text("${estilo.emoji} ${fuente.nombre}", fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Normal)
                    },
                    selectedContentColor = estilo.colorAcento,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (uiState.cargando) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val fuente = FUENTES_NOTICIAS[page]
                PaginaFuente(fuente.nombre, uiState.titularesPorFuente[fuente.nombre].orEmpty())
            }
        }
    }
}

@Composable
private fun PaginaFuente(nombreFuente: String, titulares: List<Titular>) {
    var filtro by remember(nombreFuente) { mutableStateOf(FiltroSentimiento.TODAS) }
    val estiloFuente = estiloParaFuente(nombreFuente)

    val positivas = titulares.count { it.sentimiento == Sentimiento.POSITIVO }
    val negativas = titulares.count { it.sentimiento == Sentimiento.NEGATIVO }
    val neutras = titulares.count { it.sentimiento == Sentimiento.NEUTRO }

    val titularesFiltrados = when (filtro) {
        FiltroSentimiento.TODAS -> titulares
        FiltroSentimiento.POSITIVAS -> titulares.filter { it.sentimiento == Sentimiento.POSITIVO }
        FiltroSentimiento.NEGATIVAS -> titulares.filter { it.sentimiento == Sentimiento.NEGATIVO }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (titulares.isNotEmpty()) {
            BannerResumenSentimiento(estiloFuente, positivas, negativas, neutras)
        }

        // Filtro Todas/Positivas/Negativas, con el recuento de cada categoría en la propia
        // etiqueta del chip — rediseñados con emoji y relleno de color incluso sin seleccionar,
        // para que sean más vistosos que un chip de contorno plano.
        // Filtro Todas/Positivas/Negativas — pedido expresamente adaptar el tamaño de las 3
        // pestañas para que encajen bien (antes "Negativas" no cabía en pantallas estrechas al
        // sumar emoji + texto + contador): ahora las 3 se reparten el ancho a partes iguales
        // (weight(1f) cada una) y, SOLO en estas pestañas, sin emoji — el emoji de sentimiento
        // se mantiene en el resto de la pantalla (insignia de cada titular, banner de resumen).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = filtro == FiltroSentimiento.TODAS,
                onClick = { filtro = FiltroSentimiento.TODAS },
                label = {
                    Text(
                        "Todas (${titulares.size})",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    selectedContainerColor = estiloFuente.colorAcento,
                    selectedLabelColor = Color.White
                )
            )
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = filtro == FiltroSentimiento.POSITIVAS,
                onClick = { filtro = FiltroSentimiento.POSITIVAS },
                label = {
                    Text(
                        "Positivas ($positivas)",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = VerdePositivoFondo,
                    selectedContainerColor = VerdePositivo,
                    // CAMBIADO a petición expresa (tema oscuro): el fondo sin seleccionar pasó a
                    // ser un verde OSCURO (VerdePositivoFondo) — el mismo verde oscuro de acento
                    // como color de texto encima ya no se leería (oscuro sobre oscuro), así que
                    // el texto sin seleccionar pasa a un verde CLARO (mismo tono que ya usa el
                    // Panel para "sube", Color(0xFF69F0AE)).
                    labelColor = Color(0xFF69F0AE),
                    selectedLabelColor = Color.White
                )
            )
            FilterChip(
                modifier = Modifier.weight(1f),
                selected = filtro == FiltroSentimiento.NEGATIVAS,
                onClick = { filtro = FiltroSentimiento.NEGATIVAS },
                label = {
                    Text(
                        "Negativas ($negativas)",
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = RojoNegativoFondo,
                    selectedContainerColor = RojoNegativo,
                    // Mismo motivo que arriba — rojo claro (mismo tono que ya usa el Panel para
                    // "baja", Color(0xFFFF8A80)) en vez del rojo oscuro de acento.
                    labelColor = Color(0xFFFF8A80),
                    selectedLabelColor = Color.White
                )
            )
        }

        if (titularesFiltrados.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (titulares.isEmpty()) "\uD83D\uDCED No se han podido cargar titulares de esta fuente" else "Sin titulares en esta categoría",
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(titularesFiltrados) { titular ->
                    TarjetaTitular(titular, estiloFuente)
                }
            }
        }
    }
}

/**
 * Banner con el "tono del día" de la fuente — pedido expresamente para que la pantalla sea más
 * amena, no solo una lista de texto: un emoji grande y una frase que resumen si la sesión de
 * este medio ha sido mayoritariamente positiva, negativa o mixta, más una barra apilada de
 * colores (verde/gris/rojo) que muestra la proporción exacta de un vistazo.
 */
@Composable
private fun BannerResumenSentimiento(estiloFuente: EstiloFuente, positivas: Int, negativas: Int, neutras: Int) {
    val (emoji, frase) = when {
        positivas > negativas * 3 / 2 -> "\uD83D\uDE80" to "Sesión mayoritariamente positiva"
        negativas > positivas * 3 / 2 -> "\u26C8\uFE0F" to "Sesión mayoritariamente negativa"
        else -> "\u2696\uFE0F" to "Sentimiento mixto en esta sesión"
    }
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(14.dp),
        color = estiloFuente.colorAcento.copy(alpha = 0.08f)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, fontSize = 26.sp)
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text(frase, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "$positivas positivas · $neutras neutras · $negativas negativas",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            // Barra apilada — cada segmento pesa proporcionalmente a su recuento; un total de 0
            // ya se descarta arriba (el banner no se muestra si no hay titulares).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .background(GrisNeutroFondo, shape = RoundedCornerShape(4.dp))
            ) {
                if (positivas > 0) {
                    Box(modifier = Modifier.weight(positivas.toFloat()).fillMaxSize().background(VerdePositivo))
                }
                if (neutras > 0) {
                    Box(modifier = Modifier.weight(neutras.toFloat()).fillMaxSize().background(GrisNeutro))
                }
                if (negativas > 0) {
                    Box(modifier = Modifier.weight(negativas.toFloat()).fillMaxSize().background(RojoNegativo))
                }
            }
        }
    }
}

private data class EstiloSentimiento(val acento: Color, val fondo: Color, val emoji: String, val etiqueta: String)

private fun estiloParaSentimiento(sentimiento: Sentimiento): EstiloSentimiento = when (sentimiento) {
    Sentimiento.POSITIVO -> EstiloSentimiento(VerdePositivo, VerdePositivoFondo, "\uD83D\uDCC8", "Positiva")
    Sentimiento.NEGATIVO -> EstiloSentimiento(RojoNegativo, RojoNegativoFondo, "\uD83D\uDCC9", "Negativa")
    Sentimiento.NEUTRO -> EstiloSentimiento(GrisNeutro, GrisNeutroFondo, "\u2796", "Neutra")
}

@Composable
private fun TarjetaTitular(titular: Titular, estiloFuente: EstiloFuente) {
    val uriHandler = LocalUriHandler.current
    val estiloSentimiento = estiloParaSentimiento(titular.sentimiento)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { runCatching { uriHandler.openUri(titular.url) } },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(estiloSentimiento.fondo, MaterialTheme.colorScheme.surface),
                        endX = 320f
                    )
                )
        ) {
            Row(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                // Insignia circular grande con el emoji del sentimiento — mucho más llamativa
                // que la fina barra de acento de la versión anterior.
                Surface(
                    modifier = Modifier.size(40.dp),
                    shape = CircleShape,
                    color = estiloSentimiento.acento
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(estiloSentimiento.emoji, fontSize = 18.sp)
                    }
                }
                Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(shape = RoundedCornerShape(6.dp), color = estiloSentimiento.acento) {
                            Text(
                                estiloSentimiento.etiqueta.uppercase(),
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                        titular.fecha?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        text = titular.texto,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        "Toca para leer la noticia \u2192",
                        style = MaterialTheme.typography.labelSmall,
                        color = estiloFuente.colorAcento,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}
