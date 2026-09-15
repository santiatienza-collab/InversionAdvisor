package com.inversionadvisor.ui.screener

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inversionadvisor.data.repository.MarketRepository
import com.inversionadvisor.domain.model.BuyOpportunity
import com.inversionadvisor.domain.indicators.Top10Calculator
import com.inversionadvisor.domain.model.MarketUniverse
import com.inversionadvisor.domain.model.RiskLevel
import com.inversionadvisor.domain.model.UptrendCandidate
import com.inversionadvisor.ui.common.SectionHeader
import com.inversionadvisor.ui.common.toColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Pantalla de Análisis: una pestaña por universo de mercado (S&P 500 /
 * Nasdaq-100 / IBEX 35). El botón "Analizar" escanea SOLO la pestaña
 * activa. Cada sección con más de 5 resultados se pliega tras las primeras
 * 5 tarjetas, con un botón "Ver más" que despliega de 5 en 5 (no todo de
 * golpe) para reducir el scroll. Al tocar cualquier candidato se abre su
 * gráfica de detalle (precio + SMA + volumen).
 */
@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class) // ExposedDropdownMenuBox/ExposedDropdownMenu (el desplegable de índices) siguen marcadas como experimentales en esta versión de Material3.
fun ScreenerScreen(viewModel: ScreenerViewModel, marketRepository: MarketRepository) {
    val state by viewModel.uiState.collectAsState()

    val selectedSymbol = state.selectedSymbol
    if (selectedSymbol != null) {
        // NUEVO — pedido expresamente: mostrar el nombre completo de la acción además del
        // ticker al entrar en la ficha. Se busca en las 3 listas ya cargadas en pantalla
        // (candidatos de tendencia, oportunidades de compra, Top10), todas tienen el nombre.
        val nombreCompleto = state.uptrendCandidates.firstOrNull { it.symbol == selectedSymbol }?.name
            ?: state.buyOpportunities.firstOrNull { it.symbol == selectedSymbol }?.name
            ?: state.top10Entries.firstOrNull { it.symbol == selectedSymbol }?.name
        val indexNameCruda = state.uptrendCandidates.firstOrNull { it.symbol == selectedSymbol }?.indexName
            ?: state.buyOpportunities.firstOrNull { it.symbol == selectedSymbol }?.indexName
            ?: state.top10Entries.firstOrNull { it.symbol == selectedSymbol }?.indexName
        val indexDisplayName = com.inversionadvisor.domain.model.MarketUniverse.values()
            .firstOrNull { it.indexName == indexNameCruda }?.displayName
        StockDetailScreen(
            symbol = selectedSymbol,
            marketRepository = marketRepository,
            onBack = viewModel::clearStockSelection,
            displayTitle = if (nombreCompleto != null) "$selectedSymbol — $nombreCompleto" else selectedSymbol,
            indexDisplayName = indexDisplayName,
            // Se lee de state.favoriteSymbols (parte del uiState observado con collectAsState),
            // no de viewModel.isFavorite() directamente — así, al tocar la estrellita, la
            // recomposición ocurre en cuanto cambia el estado, sin tener que volver atrás y
            // reentrar para que se refresque.
            isFavorite = selectedSymbol in state.favoriteSymbols,
            onToggleFavorite = { viewModel.toggleFavoriteBySymbol(selectedSymbol) }
        )
        return
    }

    // NUEVO — pedido expresamente: mismo color índigo con sombra gris que el nombre del stock,
    // reutilizado aquí para las pestañas superiores y para el desplegable de índices de abajo.
    val colorIndigoTabs = Color(0xFF3F51B5)
    val sombraGrisTabs = Shadow(color = Color.Gray, offset = Offset(1.5f, 1.5f), blurRadius = 3f)

    // NUEVO — pedido expresamente: las 4 pestañas de mercados de acciones (S&P 500/Nasdaq-100/
    // IBEX 35/Russell 2000) se sustituyen por UNA sola pestaña "Índices" — dentro de ella, un
    // desplegable elige cuál de los 4 se muestra (ver más abajo). Divisas/Bonos/Top10 se quedan
    // como pestañas propias, sin cambios.
    val gruposDeIndices = setOf(MarketUniverse.SP500, MarketUniverse.NASDAQ100, MarketUniverse.IBEX35, MarketUniverse.RUSSELL2000)
    val esUnMercadoDeIndices = state.selectedMarket in gruposDeIndices
    data class PestañaSuperior(val etiqueta: String, val seleccionada: Boolean, val alPulsar: () -> Unit)
    val pestañasSuperiores = listOf(
        PestañaSuperior("Índices", esUnMercadoDeIndices) {
            if (!esUnMercadoDeIndices) viewModel.selectMarket(MarketUniverse.SP500)
        },
        PestañaSuperior(MarketUniverse.DIVISAS.displayName, state.selectedMarket == MarketUniverse.DIVISAS) { viewModel.selectMarket(MarketUniverse.DIVISAS) },
        PestañaSuperior(MarketUniverse.BONOS.displayName, state.selectedMarket == MarketUniverse.BONOS) { viewModel.selectMarket(MarketUniverse.BONOS) },
        PestañaSuperior(MarketUniverse.TOP10.displayName, state.selectedMarket == MarketUniverse.TOP10) { viewModel.selectMarket(MarketUniverse.TOP10) }
    )

    // NUEVO — pedido expresamente: declarado aquí (no dentro del "if" del desplegable) para que
    // también sea accesible más abajo, dentro del LazyColumn, donde se decide si se muestra la
    // gráfica y Valores Alcistas o no.
    // NUEVO — pedido expresamente: ahora vive en el ViewModel (state.indiceElegidoManualmente),
    // no en un remember{} local — así sobrevive a cambiar de pestaña de la app y volver.

    Column(modifier = Modifier.fillMaxWidth()) {
        // CAMBIADO a petición expresa: ya no debe deslizar NI estirarse a partes iguales —
        // fila fija (Row normal, no TabRow/ScrollableTabRow) con cada pestaña ocupando solo el
        // espacio que necesita (padding reducido), repartidas de forma uniforme pero compacta.
        // El tamaño de la FUENTE no cambia, solo el padding alrededor de cada una.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            pestañasSuperiores.forEach { pestaña ->
                Surface(
                    modifier = Modifier.clickable(onClick = pestaña.alPulsar),
                    shape = RoundedCornerShape(8.dp),
                    color = if (pestaña.seleccionada) colorIndigoTabs.copy(alpha = 0.12f) else Color.Transparent
                ) {
                    Text(
                        pestaña.etiqueta,
                        style = MaterialTheme.typography.bodyMedium.copy(shadow = sombraGrisTabs),
                        color = colorIndigoTabs,
                        fontWeight = if (pestaña.seleccionada) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // QUITADAS a petición expresa: las subpestañas "Índice"/"Valores Alcistas" que había
        // dentro de S&P 500/Nasdaq-100/IBEX 35 — ahora todo va seguido en la única pestaña
        // "Índices" (desplegable + gráfica + Valores Alcistas, ver más abajo), sin subpestañas.
        if (esUnMercadoDeIndices) {
            // CAMBIADO a petición expresa: ahora se muestra S&P 500 desde el principio (valor
            // por defecto de state.selectedMarket e indiceElegidoManualmente, ver ViewModel) —
            // el desplegable ya no sirve para "revelar" el contenido, solo para CAMBIAR de
            // índice. state.indiceElegidoManualmente se mantiene por si en algún punto hiciera
            // falta un estado "sin elegir" (queda en true siempre desde el arranque).
            var desplegableAbierto by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(
                expanded = desplegableAbierto,
                onExpandedChange = { desplegableAbierto = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = if (state.indiceElegidoManualmente) state.selectedMarket.displayName else "",
                    onValueChange = {},
                    readOnly = true,
                    label = {
                        Text(
                            "Elige un índice bursátil",
                            style = MaterialTheme.typography.bodyMedium.copy(shadow = sombraGrisTabs),
                            color = colorIndigoTabs
                        )
                    },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = desplegableAbierto) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(
                    expanded = desplegableAbierto,
                    onDismissRequest = { desplegableAbierto = false }
                ) {
                    listOf(MarketUniverse.SP500, MarketUniverse.NASDAQ100, MarketUniverse.IBEX35, MarketUniverse.RUSSELL2000).forEach { indice ->
                        DropdownMenuItem(
                            text = { Text(indice.displayName) },
                            onClick = {
                                viewModel.selectMarket(indice)
                                viewModel.marcarIndiceElegido()
                                desplegableAbierto = false
                            }
                        )
                    }
                }
            }
        }

        // key(selectedMarket): un LazyListState distinto por mercado — si no, cambiar de
        // SP500 a IBEX35 arrastraría el scroll de uno al otro, que no tiene sentido (son
        // listas de contenido totalmente distinto). Se inicializa con la posición guardada en
        // el ViewModel (0,0 la primera vez), y snapshotFlow reenvía cada cambio de scroll hacia
        // el ViewModel en tiempo real — así, si se entra en la gráfica de un stock o se cambia
        // de pestaña (lo que destruye este LazyListState), la última posición ya quedó
        // guardada de antemano, sin depender de capturar el momento exacto de salida.
        val listState = key(state.selectedMarket) {
            rememberLazyListState(
                initialFirstVisibleItemIndex = state.scrollIndex,
                initialFirstVisibleItemScrollOffset = state.scrollOffset
            )
        }
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> viewModel.saveScrollPosition(index, offset) }
        }
        val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
        // Visible solo tras un scroll largo de verdad (más de 3 elementos), no desde el
        // principio — se pidió "botón transparente para volver arriba tras un scroll largo".
        // remember(listState), NO remember{} a secas: listState cambia de objeto cada vez que
        // se cambia de mercado (key(state.selectedMarket) más arriba crea uno nuevo) — sin la
        // key aquí, este derivedStateOf se quedaba vigilando el listState VIEJO para siempre
        // tras el primer cambio de pestaña, así que dejaba de reaccionar al scroll real (el
        // botón "se moría" después de cambiar de pestaña una vez, exactamente el fallo reportado).
        val showScrollToTop by remember(listState) {
            androidx.compose.runtime.derivedStateOf { listState.firstVisibleItemIndex > 3 }
        }

        Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.selectedMarket == MarketUniverse.TOP10) {
                item {
                    Top10Section(
                        state = state,
                        onCalculateClick = viewModel::calculateTop10,
                        onEntryClick = { symbol -> viewModel.selectStock(symbol) }
                    )
                }
                return@LazyColumn
            }
            if (state.selectedMarket == MarketUniverse.DIVISAS) {
                // showVolume=false: pares de divisas (EUR/USD, etc.) no tienen un volumen de
                // "operaciones" real en el sentido de una acción — es un mercado descentralizado
                // sin un único libro de órdenes, así que tampoco había datos útiles aquí.
                // priceChartHeightDp reducido un 25% (260dp → 195dp), pedido a propósito solo
                // para Divisas y Bonos USA, no para el resto de gráficas de la app.
                items(com.inversionadvisor.domain.model.Symbols.CURRENCY_CHART_SYMBOLS, key = { it.second }) { (label, symbol) ->
                    GenericChartCard(marketRepository, symbol, "$label ($symbol)", showVolume = false, priceChartHeightDp = 195)
                }
            } else if (state.selectedMarket == MarketUniverse.BONOS) {
                // Orden pedido explícitamente: panel de tipo de interés arriba del todo, luego
                // las gráficas de 10 y 30 años, luego el semáforo del MOVE, y al final la
                // gráfica del MOVE en sí (antes iba: las 3 gráficas seguidas y el semáforo al
                // final — se reordena para que el semáforo quede pegado a su gráfica, no lejos).
                // showVolume=false: ^TNX/^TYX/^MOVE son índices de rendimiento, no valores que
                // se compren/vendan — no hay datos de volumen reales que mostrar aquí.
                item { InterestRatePanelCard(marketRepository) }
                items(com.inversionadvisor.domain.model.Symbols.US_BOND_CHART_SYMBOLS.take(2), key = { it.second }) { (label, symbol) ->
                    GenericChartCard(marketRepository, symbol, "$label ($symbol)", showVolume = false, priceChartHeightDp = 195)
                }
                item { MoveIndexSemaphoreCard(marketRepository) }
                val moveEntry = com.inversionadvisor.domain.model.Symbols.US_BOND_CHART_SYMBOLS.last()
                item { GenericChartCard(marketRepository, moveEntry.second, "${moveEntry.first} (${moveEntry.second})", showVolume = false, priceChartHeightDp = 195) }
            } else if (state.indiceElegidoManualmente) {
                // QUITADA la comprobación de subpestaña "Índice" — ya no existen subpestañas.
                // NUEVO — solo se muestra tras elegir un índice en el desplegable de arriba.
                item { IndexOverviewChartCard(marketRepository, state.selectedMarket) }
                // OCULTADO a petición expresa: el botón "Analizar" ya no se muestra en ningún
                // sitio — S&P 500/Nasdaq-100/IBEX 35/Russell 2000 (y luego Top10) se cargan
                // solos al abrir la app (ver MainActivity), así que ya no hace falta pulsar
                // nada a mano. CompactScreenerHeader se deja definida (código sin usar, por si
                // hiciera falta recuperarla más adelante), pero ya no se llama desde aquí.
            }

            state.error?.let { error ->
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                        Text(
                            "No se pudo completar el análisis: $error",
                            modifier = Modifier.padding(12.dp),
                            color = Color(0xFFC62828)
                        )
                    }
                }
            }

            // QUITADO a petición expresa: el aviso "Russell 2000 no tiene una lista fiable" ya
            // no es correcto — scanner-cli ahora descarga y analiza el CSV de holdings del ETF
            // IWM (ver fetchRussell2000Universe en Main.kt), así que sí tiene candidatos vía
            // JSON. Se deja caer al mismo flujo de "Valores Alcistas" que S&P 500/Nasdaq-100/
            // IBEX 35, más abajo.
            if (state.selectedMarket == MarketUniverse.DIVISAS || state.selectedMarket == MarketUniverse.BONOS) {
                // Divisas/Bonos son solo gráficas, ni tienen universo de acciones que escanear —
                // no hay "Tendencia alcista" ni "Futuras compras" que mostrar aquí.
                return@LazyColumn
            }

            // A partir de aquí solo llegan S&P 500/Nasdaq-100/IBEX 35 (Top10/Russell 2000/
            // Divisas/Bonos ya han salido con return@LazyColumn más arriba).
            // QUITADA la comprobación de subpestaña — ya no existen. NUEVO — gateado con
            // indiceElegidoManualmente: sin elegir nada en el desplegable, nada de esto se ve.
            if (state.indiceElegidoManualmente) {
                // NUEVO — pedido expresamente: separación visual antes de "Valores Alcistas",
                // para que quede claro que es una sección aparte de la gráfica de arriba.
                item {
                    HorizontalDivider(
                        modifier = Modifier.padding(vertical = 8.dp),
                        thickness = 1.dp,
                        color = colorIndigoTabs.copy(alpha = 0.25f)
                    )
                }
                item {
                    // NUEVO — pedido expresamente: mismo formato (fuente/tamaño/color índigo con
                    // sombra gris) que ya se usa para el nombre de la empresa en la ficha del stock.
                    Column {
                        Text(
                            "Valores Alcistas",
                            style = MaterialTheme.typography.titleLarge.copy(shadow = sombraGrisTabs),
                            fontWeight = FontWeight.Bold,
                            color = colorIndigoTabs
                        )
                        Text(
                            "Los mejores valores para el índice ${state.selectedMarket.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Gray
                        )
                    }
                }
                if (state.uptrendCandidates.isEmpty()) {
                    item {
                        EmptyScreenerHint(
                            state.isRunning,
                            state.lastRun != null,
                            "Ningún valor de ${state.selectedMarket.displayName} con puntuación de 55 o más en el último análisis."
                        )
                    }
                } else {
                    expandableSection(
                        items = state.uptrendCandidates,
                        visibleCount = state.uptrendVisibleCount,
                        onShowMore = { viewModel.showMoreUptrend(state.uptrendCandidates.size) },
                        onCollapse = viewModel::collapseUptrend,
                        key = { it.symbol }
                    ) { candidate ->
                        UptrendCard(candidate, marketRepository, onClick = { viewModel.selectStock(candidate.symbol) })
                    }
                }
            }
        }

        // Botón flotante transparente para volver al principio tras un scroll largo — pedido
        // para SP500/Nasdaq-100/IBEX 35, pero se deja disponible en cualquier pestaña de
        // Análisis (no molesta en las que tienen listas cortas, ahí simplemente no llega a
        // aparecer porque nunca se pasa de 3 elementos de scroll).
        androidx.compose.animation.AnimatedVisibility(
            visible = showScrollToTop,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                modifier = Modifier
                    .size(48.dp)
                    .clickable { coroutineScope.launch { listState.animateScrollToItem(0) } }
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Volver arriba", tint = Color.White)
                }
            }
        }
        }
    }
}

/**
 * Muestra como mucho [visibleCount] tarjetas y, si hay más, añade un botón
 * "Ver más" que despliega de [COLLAPSED_ITEM_COUNT] en [COLLAPSED_ITEM_COUNT]
 * (no todas de golpe) — así una lista de 50 candidatos no obliga a cargar
 * ni hacer scroll por todas a la vez. Al llegar al final se puede volver a
 * plegar con "Ver menos".
 */
private fun <T> LazyListScope.expandableSection(
    items: List<T>,
    visibleCount: Int,
    onShowMore: () -> Unit,
    onCollapse: () -> Unit,
    key: (T) -> Any,
    itemContent: @Composable (T) -> Unit
) {
    val visible = items.take(visibleCount)
    items(visible, key = key) { itemContent(it) }

    if (items.size > COLLAPSED_ITEM_COUNT) {
        val allShown = visibleCount >= items.size
        item {
            TextButton(
                onClick = if (allShown) onCollapse else onShowMore,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    if (allShown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.padding(start = 4.dp))
                Text(if (allShown) "Ver menos" else "Ver ${(items.size - visibleCount).coerceAtMost(COLLAPSED_ITEM_COUNT)} más (quedan ${items.size - visibleCount})")
            }
        }
    }
}

@Composable
private fun CompactScreenerHeader(state: ScreenerUiState, onRunClick: () -> Unit) {
    // NUEVO — pedido expresamente: versión reducida de ScreenerHeader (más abajo) — sin el
    // título grande ni el bloque de info del universo (eso ya se ve en la gráfica/desplegable
    // de arriba), botón más bajo (40dp en vez de 52dp), pensada para ir al FINAL de la página.
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Button(
                onClick = onRunClick,
                enabled = !state.isRunning,
                modifier = Modifier.fillMaxWidth().height(40.dp),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    // NUEVO — pedido expresamente: azul índigo, mismo tono que el resto de la UI.
                    containerColor = Color(0xFF3F51B5),
                    contentColor = Color.White,
                    disabledContainerColor = Color(0xFF3F51B5).copy(alpha = 0.5f),
                    disabledContentColor = Color.White
                )
            ) {
                if (state.isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    Text("Analizando ${state.selectedMarket.displayName}…", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.padding(start = 6.dp))
                    Text("Analizar ${state.selectedMarket.displayName}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                }
            }
            if (state.isRunning) {
                Spacer(modifier = Modifier.padding(top = 8.dp))
                val progressFraction = if (state.progressTotal > 0) state.progressDone.toFloat() / state.progressTotal else 0f
                LinearProgressIndicator(
                    progress = progressFraction,
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                    strokeCap = StrokeCap.Round
                )
                Text(
                    "${state.progressDone} / ${state.progressTotal} valores",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                state.lastRun?.let { lastRun ->
                    val formatter = remember(lastRun.lastRunEpochMillis) { SimpleDateFormat("d MMM HH:mm", Locale("es", "ES")) }
                    Text(
                        "Último análisis: ${formatter.format(Date(lastRun.lastRunEpochMillis))} · ${lastRun.symbolsScanned} valores",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenerHeader(state: ScreenerUiState, onRunClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("Análisis · ${state.selectedMarket.displayName}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.padding(top = 6.dp))

            val currentIndexMeta = state.indexMeta.find { it.indexName == state.selectedMarket.indexName }
            if (currentIndexMeta == null) {
                Text("Universo: ${state.selectedMarket.displayName}", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    "${currentIndexMeta.displayName}: ${currentIndexMeta.count} valores",
                    style = MaterialTheme.typography.bodySmall
                )
                val formatter = remember(currentIndexMeta.updatedAtEpochMillis) { SimpleDateFormat("d MMM yyyy", Locale("es", "ES")) }
                Text(
                    "Universo auto-actualizado desde Wikipedia · ${formatter.format(Date(currentIndexMeta.updatedAtEpochMillis))}",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.padding(top = 16.dp))

            // En Russell 2000 no hay "Analizar" — sin lista de constituyentes fiable, ver aviso
            // en la propia pestaña (ScreenerScreen). El botón no tendría nada que escanear.
            val isChartOnlyMarket = state.selectedMarket == MarketUniverse.RUSSELL2000 ||
                state.selectedMarket == MarketUniverse.DIVISAS ||
                state.selectedMarket == MarketUniverse.BONOS
            if (!isChartOnlyMarket) {
                Button(
                    onClick = onRunClick,
                    enabled = !state.isRunning,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        disabledContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                        disabledContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    if (state.isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.padding(start = 10.dp))
                        Text("Analizando ${state.selectedMarket.displayName}…", fontWeight = FontWeight.Bold)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.padding(start = 8.dp))
                        Text("Analizar ${state.selectedMarket.displayName}", fontWeight = FontWeight.Bold)
                    }
                }

                if (state.isRunning) {
                    Spacer(modifier = Modifier.padding(top = 14.dp))
                    val progressFraction = if (state.progressTotal > 0) state.progressDone.toFloat() / state.progressTotal else 0f
                    LinearProgressIndicator(
                        progress = progressFraction,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                        strokeCap = StrokeCap.Round
                    )
                    Text(
                        "${state.progressDone} / ${state.progressTotal} valores",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                } else {
                    state.lastRun?.let { lastRun ->
                        Spacer(modifier = Modifier.padding(top = 14.dp))
                        val formatter = remember(lastRun.lastRunEpochMillis) {
                            SimpleDateFormat("d MMM HH:mm", Locale("es", "ES"))
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        ) {
                            Text(
                                // Aclaración añadida a propósito: los datos de esta lista (incluido el RSI de
                                // cada stock) son una FOTO FIJA de este momento, no se actualizan solos — si el
                                // precio se mueve después de "Analizar", esta lista se queda con los valores de
                                // entonces hasta que se vuelva a pulsar el botón. La ficha de detalle de cada
                                // stock sí calcula el RSI al momento, así que pueden no coincidir sin que sea un
                                // error — es la misma acción en dos fechas distintas.
                                "Último análisis: ${formatter.format(Date(lastRun.lastRunEpochMillis))} · ${lastRun.symbolsScanned} valores escaneados. " +
                                    "Los datos de esta lista no se actualizan solos — vuelve a pulsar \"Analizar\" para refrescarlos.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyScreenerHint(isRunning: Boolean, hasRunBefore: Boolean, emptyMessage: String) {
    val text = when {
        isRunning -> "Analizando el mercado, los resultados irán apareciendo cuando termine…"
        hasRunBefore -> emptyMessage
        else -> "Todavía no se ha lanzado ningún análisis en esta pestaña. Pulsa \"Analizar\" para empezar."
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) // FlowRow — ver comentario donde se usa
private fun UptrendCard(candidate: UptrendCandidate, marketRepository: MarketRepository, onClick: () -> Unit) {
    // REESCRITA POR COMPLETO a petición expresa: "Valores Alcistas" (antes "Tendencia alcista")
    // ahora muestra toda la información en directo y la puntuación con el MISMO formato que
    // "Futuras compras" — mismo patrón exacto que BuyOpportunityCard de más abajo, adaptado a
    // UptrendCandidate (que ya trae trendQuality/yearChangePercent del escaneo, a diferencia de
    // BuyOpportunity).
    var stockPe by remember(candidate.symbol) { mutableStateOf<Double?>(null) }
    var sectorAveragePe by remember(candidate.symbol) { mutableStateOf<Double?>(null) }
    var stockVolatilityRatio by remember(candidate.symbol) { mutableStateOf<Double?>(null) }
    var percentFromYearHigh by remember(candidate.symbol) { mutableStateOf<Double?>(null) }
    var nearestSupportPercent by remember(candidate.symbol) { mutableStateOf<Double?>(null) }
    var nearestResistancePercent by remember(candidate.symbol) { mutableStateOf<Double?>(null) }
    var candlestickPattern by remember(candidate.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.CandlestickPattern?>(null) }
    var marketTrap by remember(candidate.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.MarketTrapType?>(null) }
    var shortTermBullish by remember(candidate.symbol) { mutableStateOf<Boolean?>(null) }
    var declineAccelerating by remember(candidate.symbol) { mutableStateOf(false) }
    var deathCrossDate by remember(candidate.symbol) { mutableStateOf<String?>(null) }
    var earningsWithinThreeWeeks by remember(candidate.symbol) { mutableStateOf(false) }
    var momentumPriceDivergence by remember(candidate.symbol) { mutableStateOf(com.inversionadvisor.domain.indicators.MomentumPriceDivergence.NONE) }
    var goldenCrossDate by remember(candidate.symbol) { mutableStateOf<String?>(null) }
    var shortTermFlagPattern by remember(candidate.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.FlagPattern?>(null) }
    var longTermFlagPattern by remember(candidate.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.FlagPattern?>(null) }
    var consolidatedBearishMonthsCount by remember(candidate.symbol) { mutableStateOf<Int?>(null) }
    var doubleTopBottomResult by remember(candidate.symbol) { mutableStateOf(com.inversionadvisor.domain.indicators.DoubleTopBottomResult(com.inversionadvisor.domain.indicators.DoubleTopBottomPattern.NONE)) }
    var tripleTopBottomResult by remember(candidate.symbol) { mutableStateOf(com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomResult(com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomPattern.NONE)) }
    var macd by remember(candidate.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.TechnicalAnalysis.MacdResult?>(null) }
    var benchmarkYearChangePercent by remember(candidate.symbol) { mutableStateOf<Double?>(null) }
    var sectorDeclinePercent by remember(candidate.symbol) { mutableStateOf<Double?>(null) }
    var hchCandles by remember(candidate.symbol) { mutableStateOf<List<com.inversionadvisor.domain.model.Candle>>(emptyList()) }
    // NUEVO — pedido expresamente (fallo real: HCH no aparecía penalizado porque hchCandles, la
    // vela de 5 años, a veces llegaba DESPUÉS de que ya se hubiera calculado hchResult con la de
    // 1 año dentro del propio bloque de la corrutina — una vez calculado ahí, no se recalculaba
    // solo porque hchCandles cambiara después). Ahora se guarda la vela de 1 año como estado
    // (oneYearCandles) y hchResult se calcula FUERA de la corrutina, con "remember" reactivo a
    // los dos (oneYearCandles Y hchCandles) — así, venga cual venga antes, en cuanto ambas estén
    // disponibles, se recalcula solo, sin depender del orden de llegada.
    var oneYearCandles by remember(candidate.symbol) { mutableStateOf<List<com.inversionadvisor.domain.model.Candle>>(emptyList()) }
    val hchResult = remember(oneYearCandles, hchCandles) {
        com.inversionadvisor.domain.indicators.UptrendDetector.detectHeadAndShoulders(hchCandles.ifEmpty { oneYearCandles })
    }
    var aboveTrendSma by remember(candidate.symbol) { mutableStateOf<Boolean?>(null) }
    // NUEVO — para que "Giro" (agotamiento/techo) también cuente aquí, igual que en Futuras
    // Compras — un valor alcista también puede mostrar indicios de techo si está sobrecomprado.
    var longTermDecline by remember(candidate.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.ExhaustionSignal?>(null) }

    LaunchedEffect(candidate.symbol) {
        runCatching { marketRepository.refreshStockPeIfStale(candidate.symbol) }
        runCatching { marketRepository.refreshSectorPeRatiosIfStale() }
        runCatching { marketRepository.refreshYahooCandlesIfStale(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR) }
        launch {
            marketRepository.observeStockPe(candidate.symbol).collect { stockPe = it }
        }
        launch {
            marketRepository.observeSectorPeRatios().collect { map ->
                sectorAveragePe = map[candidate.sectorEtf]
            }
        }
        launch {
            marketRepository.observeEarningsDate(candidate.symbol).collect { entity ->
                earningsWithinThreeWeeks = marketRepository.isEarningsWithinThreeWeeks(entity)
            }
        }
        launch {
            runCatching {
                marketRepository.refreshYahooCandlesBulkIfStale(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                val spyCandles = marketRepository.observeCandles(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                benchmarkYearChangePercent = spyCandles.takeIf { it.size >= 2 }
                    ?.let { (it.last().close - it.first().close) / it.first().close * 100 }
            }
            runCatching {
                val sectorContext = marketRepository.computeSectorContext()
                sectorDeclinePercent = sectorContext.sectorDeclinePercentByEtf[candidate.sectorEtf]
            }
        }
        launch {
            runCatching {
                marketRepository.refreshYahooCandlesIfStale(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.FIVE_YEARS)
                hchCandles = marketRepository.observeCandles(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.FIVE_YEARS).first()
            }
        }
        launch {
            marketRepository.observeCandles(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).collect { candles ->
                oneYearCandles = candles
                stockVolatilityRatio = com.inversionadvisor.domain.indicators.TechnicalAnalysis.ownVolatilityRatio(candles)
                macd = com.inversionadvisor.domain.indicators.TechnicalAnalysis.calculateMacd(candles)
                longTermDecline = com.inversionadvisor.domain.indicators.ExhaustionDetector.detect(candles)
                if (candles.isNotEmpty()) {
                    val currentPrice = candles.last().close
                    val yearHigh = candles.maxOf { it.high }
                    percentFromYearHigh = if (yearHigh > 0) (currentPrice - yearHigh) / yearHigh * 100 else null
                    val sma40 = com.inversionadvisor.domain.indicators.TechnicalAnalysis.simpleMovingAverage(candles, 40).lastOrNull()
                    aboveTrendSma = sma40?.let { currentPrice > it }
                    val levels = com.inversionadvisor.domain.indicators.TechnicalAnalysis.detectSupportResistanceLevels(candles)
                    val resistancesAbove = levels.filter { it.type == com.inversionadvisor.domain.indicators.LevelType.RESISTANCE && it.price >= currentPrice }
                    val supportsBelow = levels.filter { it.type == com.inversionadvisor.domain.indicators.LevelType.SUPPORT && it.price <= currentPrice }
                    nearestResistancePercent = resistancesAbove.minByOrNull { it.price }?.let { (it.price - currentPrice) / currentPrice * 100 }
                    nearestSupportPercent = supportsBelow.maxByOrNull { it.price }?.let { (currentPrice - it.price) / currentPrice * 100 }
                    candlestickPattern = com.inversionadvisor.domain.indicators.TechnicalAnalysis.detectCandlestickPattern(candles)
                    marketTrap = com.inversionadvisor.domain.indicators.TechnicalAnalysis.detectTrap(candles, levels)
                    shortTermBullish = com.inversionadvisor.domain.indicators.UptrendDetector.isClearlyBullishShortTerm(candles)
                    declineAccelerating = com.inversionadvisor.domain.indicators.UptrendDetector.isDeclineAcceleratingClearly(candles)
                    deathCrossDate = com.inversionadvisor.domain.indicators.UptrendDetector.findSma20CrossedBelowSma50Date(candles)
                    goldenCrossDate = com.inversionadvisor.domain.indicators.UptrendDetector.findSma20CrossedAboveSma50Date(candles)
                    doubleTopBottomResult = com.inversionadvisor.domain.indicators.UptrendDetector.detectDoubleTopOrBottom(candles)
                    // hchResult ya NO se asigna aquí — se calcula reactivamente más arriba
                    // (remember(oneYearCandles, hchCandles)), ver el comentario junto a esa
                    // declaración para el motivo (fallo real corregido).
                    tripleTopBottomResult = com.inversionadvisor.domain.indicators.UptrendDetector.detectTripleTopOrBottom(candles)
                    try {
                        marketRepository.refreshDailyCandlesForSmaIfStale(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                        val dailyCandles = marketRepository.observeDailyCandlesForSma(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                        if (dailyCandles.size >= 51) {
                            deathCrossDate = com.inversionadvisor.domain.indicators.UptrendDetector.findSma20CrossedBelowSma50Date(dailyCandles, lookbackPeriods = 10)
                            goldenCrossDate = com.inversionadvisor.domain.indicators.UptrendDetector.findSma20CrossedAboveSma50Date(dailyCandles, lookbackPeriods = 10)
                        }
                        shortTermFlagPattern = com.inversionadvisor.domain.indicators.UptrendDetector.detectFlagPattern(dailyCandles)
                    } catch (e: Exception) {
                        // Sin confirmación diaria, se deja el resultado semanal.
                    }
                    try {
                        marketRepository.refreshMonthlyCandlesForFlagIfStale(candidate.symbol)
                        val monthlyCandles = marketRepository.observeMonthlyCandlesForFlag(candidate.symbol).first()
                        longTermFlagPattern = com.inversionadvisor.domain.indicators.UptrendDetector.detectFlagPattern(monthlyCandles)
                        consolidatedBearishMonthsCount = com.inversionadvisor.domain.indicators.UptrendDetector.countBearishMonthsInLast6(monthlyCandles, debugSymbol = candidate.symbol)
                    } catch (e: Exception) {
                        // Sin patrón mensual si falla, no corta el resto.
                    }
                    momentumPriceDivergence = com.inversionadvisor.domain.indicators.UptrendDetector.detectMomentumPriceDivergence(candles)
                }
            }
        }
    }
    val sectorTypicalPeRange = com.inversionadvisor.domain.model.Symbols.SECTOR_TYPICAL_PE_RANGES[candidate.sectorEtf]

    val scored = remember(candidate, stockPe, sectorAveragePe, stockVolatilityRatio, percentFromYearHigh, nearestSupportPercent, nearestResistancePercent, candlestickPattern, marketTrap, macd, shortTermBullish, declineAccelerating, deathCrossDate, earningsWithinThreeWeeks, momentumPriceDivergence, goldenCrossDate, shortTermFlagPattern, longTermFlagPattern, consolidatedBearishMonthsCount, doubleTopBottomResult, hchResult, tripleTopBottomResult, benchmarkYearChangePercent, sectorDeclinePercent, aboveTrendSma, longTermDecline) {
        candidate.unifiedScore(
            aboveTrendSma = aboveTrendSma,
            longTermDecline = longTermDecline,
            stockPe = stockPe,
            sectorAveragePe = sectorAveragePe,
            sectorTypicalPeRange = sectorTypicalPeRange,
            stockVolatilityRatio = stockVolatilityRatio,
            percentFromYearHigh = percentFromYearHigh,
            nearestSupportPercent = nearestSupportPercent,
            nearestResistancePercent = nearestResistancePercent,
            candlestickPattern = candlestickPattern,
            marketTrap = marketTrap,
            macd = macd,
            shortTermBullish = shortTermBullish,
            declineAccelerating = declineAccelerating,
            deathCrossDate = deathCrossDate,
            earningsWithinThreeWeeks = earningsWithinThreeWeeks,
            momentumPriceDivergence = momentumPriceDivergence,
            goldenCrossDate = goldenCrossDate,
            shortTermFlagPattern = shortTermFlagPattern,
            longTermFlagPattern = longTermFlagPattern,
            consolidatedBearishMonthsCount = consolidatedBearishMonthsCount,
            doubleTopBottomResult = doubleTopBottomResult,
            hchResult = hchResult,
            tripleTopBottomResult = tripleTopBottomResult,
            benchmarkYearChangePercent = benchmarkYearChangePercent,
            sectorDeclinePercent = sectorDeclinePercent
        )
    }
    val ratingColor = scored?.let {
        when {
            it.combinedScore < 25 -> Color(0xFFC62828)
            it.combinedScore < 50 -> Color(0xFFEF6C00)
            it.combinedScore < 75 -> Color(0xFFF9A825)
            else -> Color(0xFF2E7D32)
        }
    } ?: RiskLevel.LOW.toColor()
    var openBreakdown by remember { mutableStateOf(false) }
    if (openBreakdown && scored != null) {
        CalculationBreakdownDialog("Cómo se calculó (0-100)", scored.rewardBreakdown + scored.riskBreakdown, onDismiss = { openBreakdown = false })
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ratingColor.copy(alpha = 0.10f)),
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        candidate.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(candidate.symbol, style = MaterialTheme.typography.bodySmall)
                }
                if (scored != null) {
                    Surface(
                        modifier = Modifier.width(76.dp).clickable { openBreakdown = true },
                        shape = RoundedCornerShape(14.dp),
                        color = ratingColor
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Puntuación ℹ",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "%.0f".format(scored.combinedScore),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    ConfidenceBadge((candidate.trendQuality * 100).toInt(), RiskLevel.LOW.toColor())
                }
            }

            if (scored != null && (scored.penaltyWarnings.isNotEmpty() || scored.bonusWarnings.isNotEmpty())) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    scored.bonusWarnings.forEach { warning ->
                        com.inversionadvisor.ui.common.BlinkingNeonBadge(warning, positive = true)
                    }
                    scored.penaltyWarnings.forEach { warning ->
                        com.inversionadvisor.ui.common.BlinkingNeonBadge(warning)
                    }
                }
            }

            Spacer(modifier = Modifier.padding(top = 8.dp))

            Text(
                "${if (candidate.yearChangePercent >= 0) "+" else ""}${"%.1f".format(candidate.yearChangePercent)}% en el último año — calidad de tendencia ${"%.0f".format(candidate.trendQuality * 100)}%",
                style = MaterialTheme.typography.bodyMedium
            )

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (candidate.isSectorInFavor) RiskLevel.LOW.toColor() else Color(0xFF9E9E9E)
                ) {
                    Text(
                        if (candidate.isSectorInFavor) "${candidate.sectorName} · sector en auge" else candidate.sectorName,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) // FlowRow — ver comentario donde se usa
private fun BuyOpportunityCard(opportunity: BuyOpportunity, marketRepository: MarketRepository, onClick: () -> Unit) {
    val signal = opportunity.exhaustionSignal

    // PER por tarjeta — se pide UNA vez por símbolo (LaunchedEffect con key = símbolo, no se
    // repite en cada recomposición ni al hacer scroll). Si ya estaba en caché (visto antes en
    // la ficha del stock, o en un cálculo de Top10), sale casi al instante; si no, tarda lo que
    // tarde esa petición en concreto — se aceptó ese compromiso a propósito.
    var stockPe by remember(opportunity.symbol) { mutableStateOf<Double?>(null) }
    var sectorAveragePe by remember(opportunity.symbol) { mutableStateOf<Double?>(null) }
    // Volatilidad propia, distancia al máximo del año (para "Giro"), patrón de vela, trampa de
    // mercado y MACD — antes iban SIEMPRE a null aquí (nunca se calculaban, no era un problema
    // del bocadillo). Todo esto sale de las MISMAS velas de 1 año, sin ninguna petición de red
    // adicional — se calcula en cuanto llegan las velas (que suelen estar ya en
    // caché si el mercado se analizó hace poco).
    var stockVolatilityRatio by remember(opportunity.symbol) { mutableStateOf<Double?>(null) }
    var percentFromYearHigh by remember(opportunity.symbol) { mutableStateOf<Double?>(null) }
    var nearestSupportPercent by remember(opportunity.symbol) { mutableStateOf<Double?>(null) }
    var nearestResistancePercent by remember(opportunity.symbol) { mutableStateOf<Double?>(null) }
    var candlestickPattern by remember(opportunity.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.CandlestickPattern?>(null) }
    var marketTrap by remember(opportunity.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.MarketTrapType?>(null) }
    var shortTermBullish by remember(opportunity.symbol) { mutableStateOf<Boolean?>(null) }
    var declineAccelerating by remember(opportunity.symbol) { mutableStateOf(false) }
    var deathCrossDate by remember(opportunity.symbol) { mutableStateOf<String?>(null) }
    var earningsWithinThreeWeeks by remember(opportunity.symbol) { mutableStateOf(false) }
    var momentumPriceDivergence by remember(opportunity.symbol) { mutableStateOf(com.inversionadvisor.domain.indicators.MomentumPriceDivergence.NONE) }
    var goldenCrossDate by remember(opportunity.symbol) { mutableStateOf<String?>(null) }
    var shortTermFlagPattern by remember(opportunity.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.FlagPattern?>(null) }
    var longTermFlagPattern by remember(opportunity.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.FlagPattern?>(null) }
    var consolidatedBearishMonthsCount by remember(opportunity.symbol) { mutableStateOf<Int?>(null) }
    var doubleTopBottomResult by remember(opportunity.symbol) { mutableStateOf(com.inversionadvisor.domain.indicators.DoubleTopBottomResult(com.inversionadvisor.domain.indicators.DoubleTopBottomPattern.NONE)) }
    var hchResult by remember(opportunity.symbol) { mutableStateOf(com.inversionadvisor.domain.indicators.UptrendDetector.HeadAndShouldersResult(com.inversionadvisor.domain.indicators.UptrendDetector.HeadAndShouldersState.NONE)) }
    var tripleTopBottomResult by remember(opportunity.symbol) { mutableStateOf(com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomResult(com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomPattern.NONE)) }
    var macd by remember(opportunity.symbol) { mutableStateOf<com.inversionadvisor.domain.indicators.TechnicalAnalysis.MacdResult?>(null) }
    // NUEVO — pedido expresamente tras detectar incoherencia con la ficha del stock (que ya
    // tenía estos dos datos): comparación con el mercado (S&P 500) y con el propio sector.
    var benchmarkYearChangePercent by remember(opportunity.symbol) { mutableStateOf<Double?>(null) }
    var sectorDeclinePercent by remember(opportunity.symbol) { mutableStateOf<Double?>(null) }
    // NUEVO — pedido expresamente (fallo real: HCH casi nunca se detectaba aquí por usar solo
    // las velas de 1 año, cuando la propia función necesita hasta 5 años para hombros que
    // tardaron más de un año en formarse).
    var hchCandles by remember(opportunity.symbol) { mutableStateOf<List<com.inversionadvisor.domain.model.Candle>>(emptyList()) }
    LaunchedEffect(opportunity.symbol) {
        runCatching { marketRepository.refreshStockPeIfStale(opportunity.symbol) }
        runCatching { marketRepository.refreshSectorPeRatiosIfStale() }
        runCatching { marketRepository.refreshYahooCandlesIfStale(opportunity.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR) }
        // La PETICIÓN de fecha de resultados (refreshEarningsDateIfStale) se QUITÓ de aquí —
        // encontrado con un registro de diagnóstico real: este LaunchedEffect vive en cada
        // tarjeta de "Futuras compras", que entra y sale de composición constantemente al hacer
        // scroll (LeftCompositionCancellationException en el registro) — cancelaba la petición
        // al WebView (2,5+ segundos) antes de que terminara, así que la fecha nunca llegaba a
        // guardarse ni aquí ni, por tanto, al abrir después la ficha del stock. Se deja solo la
        // LECTURA (más abajo, observeEarningsDate) — si ya está en caché de una visita anterior
        // a la ficha del stock (que sí usa un viewModelScope estable, resistente a esto), esta
        // tarjeta la aprovecha para la penalización; si no, sale "sin dato", sin arriesgarse a
        // cancelar la petición a medias.
        launch {
            marketRepository.observeStockPe(opportunity.symbol).collect { stockPe = it }
        }
        launch {
            marketRepository.observeSectorPeRatios().collect { map ->
                sectorAveragePe = opportunity.sectorEtf?.let { map[it] }
            }
        }
        launch {
            marketRepository.observeEarningsDate(opportunity.symbol).collect { entity ->
                earningsWithinThreeWeeks = marketRepository.isEarningsWithinThreeWeeks(entity)
            }
        }
        // NUEVO — pedido expresamente: mismo cálculo que ya usa la ficha del stock (comparación
        // con el S&P 500 y con el propio sector) — antes esta tarjeta no lo pedía, así que el
        // número que se veía aquí no coincidía con el de la ficha para el mismo símbolo.
        launch {
            runCatching {
                marketRepository.refreshYahooCandlesBulkIfStale(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                val spyCandles = marketRepository.observeCandles(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                benchmarkYearChangePercent = spyCandles.takeIf { it.size >= 2 }
                    ?.let { (it.last().close - it.first().close) / it.first().close * 100 }
            }
            runCatching {
                val sectorContext = marketRepository.computeSectorContext()
                sectorDeclinePercent = opportunity.sectorEtf?.let { sectorContext.sectorDeclinePercentByEtf[it] }
            }
        }
        launch {
            runCatching {
                marketRepository.refreshYahooCandlesIfStale(opportunity.symbol, com.inversionadvisor.domain.model.ChartRange.FIVE_YEARS)
                hchCandles = marketRepository.observeCandles(opportunity.symbol, com.inversionadvisor.domain.model.ChartRange.FIVE_YEARS).first()
            }
        }
        launch {
            marketRepository.observeCandles(opportunity.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).collect { candles ->
                stockVolatilityRatio = com.inversionadvisor.domain.indicators.TechnicalAnalysis.ownVolatilityRatio(candles)
                macd = com.inversionadvisor.domain.indicators.TechnicalAnalysis.calculateMacd(candles)
                if (candles.isNotEmpty()) {
                    val currentPrice = candles.last().close
                    val yearHigh = candles.maxOf { it.high }
                    percentFromYearHigh = if (yearHigh > 0) (currentPrice - yearHigh) / yearHigh * 100 else null
                    val levels = com.inversionadvisor.domain.indicators.TechnicalAnalysis.detectSupportResistanceLevels(candles)
                    val resistancesAbove = levels.filter { it.type == com.inversionadvisor.domain.indicators.LevelType.RESISTANCE && it.price >= currentPrice }
                    val supportsBelow = levels.filter { it.type == com.inversionadvisor.domain.indicators.LevelType.SUPPORT && it.price <= currentPrice }
                    nearestResistancePercent = resistancesAbove.minByOrNull { it.price }?.let { (it.price - currentPrice) / currentPrice * 100 }
                    nearestSupportPercent = supportsBelow.maxByOrNull { it.price }?.let { (currentPrice - it.price) / currentPrice * 100 }
                    candlestickPattern = com.inversionadvisor.domain.indicators.TechnicalAnalysis.detectCandlestickPattern(candles)
                    marketTrap = com.inversionadvisor.domain.indicators.TechnicalAnalysis.detectTrap(candles, levels)
                    shortTermBullish = com.inversionadvisor.domain.indicators.UptrendDetector.isClearlyBullishShortTerm(candles)
                    declineAccelerating = com.inversionadvisor.domain.indicators.UptrendDetector.isDeclineAcceleratingClearly(candles)
                    deathCrossDate = com.inversionadvisor.domain.indicators.UptrendDetector.findSma20CrossedBelowSma50Date(candles)
                    goldenCrossDate = com.inversionadvisor.domain.indicators.UptrendDetector.findSma20CrossedAboveSma50Date(candles)
                    // Doble techo/doble suelo — usa las MISMAS velas semanales ya cargadas
                    // arriba, sin pedir ningún dato nuevo.
                    doubleTopBottomResult = com.inversionadvisor.domain.indicators.UptrendDetector.detectDoubleTopOrBottom(candles)
                    // Hombro-Cabeza-Hombro — CAMBIADO a petición expresa: ahora usa hchCandles (5
                    // años, ver arriba), no "candles" (1 año) — antes casi nunca se detectaba
                    // nada porque la propia función necesita hasta 5 años para encontrar hombros
                    // que tardaron más de un año en formarse.
                    hchResult = com.inversionadvisor.domain.indicators.UptrendDetector.detectHeadAndShoulders(hchCandles.ifEmpty { candles })
                    tripleTopBottomResult = com.inversionadvisor.domain.indicators.UptrendDetector.detectTripleTopOrBottom(candles)
                    // CAMBIADO A PETICIÓN EXPRESA: antes solo se pedían velas diarias cuando el
                    // cruce SEMANAL (barato) ya encontraba algo que confirmar — ahora se piden
                    // SIEMPRE, para que el cruce de medias Y el patrón de bandera (corto plazo)
                    // salgan con datos coherentes en todos los apartados. Tarda más, aceptado
                    // expresamente a cambio de cobertura completa.
                    try {
                        marketRepository.refreshDailyCandlesForSmaIfStale(opportunity.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                        val dailyCandles = marketRepository.observeDailyCandlesForSma(opportunity.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                        if (dailyCandles.size >= 51) {
                            deathCrossDate = com.inversionadvisor.domain.indicators.UptrendDetector.findSma20CrossedBelowSma50Date(dailyCandles, lookbackPeriods = 10)
                            goldenCrossDate = com.inversionadvisor.domain.indicators.UptrendDetector.findSma20CrossedAboveSma50Date(dailyCandles, lookbackPeriods = 10)
                        }
                        shortTermFlagPattern = com.inversionadvisor.domain.indicators.UptrendDetector.detectFlagPattern(dailyCandles)
                    } catch (e: Exception) {
                        // Si falla la confirmación, se deja el resultado semanal (más basto,
                        // pero mejor que perder el aviso del todo por un fallo de red puntual).
                    }
                    // Velas MENSUALES — NUEVO, ahora también se piden siempre aquí, para el
                    // patrón de bandera "a largo plazo". Caché de 24h (un mes no cambia hasta
                    // que pasa un mes entero), así que las repeticiones del mismo día no vuelven
                    // a pagar esta petición.
                    try {
                        marketRepository.refreshMonthlyCandlesForFlagIfStale(opportunity.symbol)
                        val monthlyCandles = marketRepository.observeMonthlyCandlesForFlag(opportunity.symbol).first()
                        longTermFlagPattern = com.inversionadvisor.domain.indicators.UptrendDetector.detectFlagPattern(monthlyCandles)
                        consolidatedBearishMonthsCount = com.inversionadvisor.domain.indicators.UptrendDetector.countBearishMonthsInLast6(monthlyCandles, debugSymbol = opportunity.symbol)
                    } catch (e: Exception) {
                        // Igual que arriba — sin patrón mensual si falla, no corta el resto.
                    }
                    momentumPriceDivergence = com.inversionadvisor.domain.indicators.UptrendDetector.detectMomentumPriceDivergence(candles)
                }
            }
        }
    }
    val sectorTypicalPeRange = opportunity.sectorEtf?.let { com.inversionadvisor.domain.model.Symbols.SECTOR_TYPICAL_PE_RANGES[it] }

    // MISMA función que usa el orden de la lista (BuyOpportunity.unifiedScore) — el ORDEN de la
    // lista no espera a nada de esto (se calculó ya, sin ellos, para que sea rápido); esta
    // tarjeta en concreto sí los añade en cuanto llegan, así que el número que ves aquí puede
    // afinarse un poco después de que la lista ya esté ordenada — no reordena la lista sola.
    val scored = remember(opportunity, stockPe, sectorAveragePe, stockVolatilityRatio, percentFromYearHigh, nearestSupportPercent, nearestResistancePercent, candlestickPattern, marketTrap, macd, shortTermBullish, declineAccelerating, deathCrossDate, earningsWithinThreeWeeks, momentumPriceDivergence, goldenCrossDate, shortTermFlagPattern, longTermFlagPattern, consolidatedBearishMonthsCount, doubleTopBottomResult, hchResult, tripleTopBottomResult, benchmarkYearChangePercent, sectorDeclinePercent) {
        opportunity.unifiedScore(
            stockPe = stockPe,
            sectorAveragePe = sectorAveragePe,
            sectorTypicalPeRange = sectorTypicalPeRange,
            stockVolatilityRatio = stockVolatilityRatio,
            percentFromYearHigh = percentFromYearHigh,
            nearestSupportPercent = nearestSupportPercent,
            nearestResistancePercent = nearestResistancePercent,
            candlestickPattern = candlestickPattern,
            marketTrap = marketTrap,
            macd = macd,
            shortTermBullish = shortTermBullish,
            declineAccelerating = declineAccelerating,
            deathCrossDate = deathCrossDate,
            earningsWithinThreeWeeks = earningsWithinThreeWeeks,
            momentumPriceDivergence = momentumPriceDivergence,
            goldenCrossDate = goldenCrossDate,
            shortTermFlagPattern = shortTermFlagPattern,
            longTermFlagPattern = longTermFlagPattern,
            consolidatedBearishMonthsCount = consolidatedBearishMonthsCount,
            doubleTopBottomResult = doubleTopBottomResult,
            hchResult = hchResult,
            tripleTopBottomResult = tripleTopBottomResult,
            benchmarkYearChangePercent = benchmarkYearChangePercent,
            sectorDeclinePercent = sectorDeclinePercent
        )
    }
    // Vuelta a la escala 0-100, 4 colores: 0-25 rojo, 25-50 naranja, 50-75 amarillo, 75-100 verde.
    val ratingColor = scored?.let {
        when {
            it.combinedScore < 25 -> Color(0xFFC62828)
            it.combinedScore < 50 -> Color(0xFFEF6C00)
            it.combinedScore < 75 -> Color(0xFFF9A825)
            else -> Color(0xFF2E7D32)
        }
    } ?: opportunity.riskLevel.toColor()
    // "Bocadillo informativo" al tocar el número de puntuación — mismo diálogo compartido que
    // Top10 y la ficha del stock (CalculationBreakdownDialog).
    var openBreakdown by remember { mutableStateOf(false) }
    if (openBreakdown && scored != null) {
        CalculationBreakdownDialog("Cómo se calculó (0-100)", scored.rewardBreakdown + scored.riskBreakdown, onDismiss = { openBreakdown = false })
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = ratingColor.copy(alpha = 0.10f)),
        onClick = onClick
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // weight(1f) + elipsis: un nombre de compañía muy largo (p. ej.
                // "International Business Machines Corporation") se trunca en vez
                // de empujar o deformar el badge de confianza de al lado.
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        opportunity.name ?: opportunity.symbol,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(opportunity.symbol, style = MaterialTheme.typography.bodySmall)
                }
                if (scored != null) {
                    Surface(
                        modifier = Modifier.width(76.dp).clickable { openBreakdown = true },
                        shape = RoundedCornerShape(14.dp),
                        color = ratingColor
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "Puntuación ℹ",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "%.0f".format(scored.combinedScore),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    ConfidenceBadge(signal.confidenceScore, opportunity.riskLevel.toColor())
                }
            }

            // Penalizaciones y bonos APARTE (SMA50, aceleración de caída, resultados
            // trimestrales, divergencia momentum/precio, cruz dorada) — aviso en neón
            // intermitente, pedido expresamente distinto del semáforo normal: ROJO para
            // penalizaciones, VERDE para bonos (antes ambos salían en rojo, sin distinción).
            // FlowRow en vez de Row normal — mismo motivo que en "Análisis de opciones de
            // compra": con varios avisos activos a la vez, un Row normal no ajustaba línea, lo
            // que ocasionalmente dejaba un hueco en blanco desproporcionado en el panel.
            if (scored != null && (scored.penaltyWarnings.isNotEmpty() || scored.bonusWarnings.isNotEmpty())) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    scored.bonusWarnings.forEach { warning ->
                        com.inversionadvisor.ui.common.BlinkingNeonBadge(warning, positive = true)
                    }
                    scored.penaltyWarnings.forEach { warning ->
                        com.inversionadvisor.ui.common.BlinkingNeonBadge(warning)
                    }
                }
            }

            Spacer(modifier = Modifier.padding(top = 8.dp))

            Text(
                "Caída de ${"%.1f".format(kotlin.math.abs(signal.declinePercentFromRecentHigh))}% desde el máximo reciente",
                style = MaterialTheme.typography.bodyMedium
            )
            signal.recoveryProgressToResistancePercent?.let { progress ->
                Text(
                    "Recuperado un %.0f%% del camino hacia la próxima resistencia".format(progress),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            opportunity.sectorName?.let { sectorName ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (opportunity.isSectorInFavor) RiskLevel.LOW.toColor() else Color(0xFF9E9E9E)
                    ) {
                        Text(
                            if (opportunity.isSectorInFavor) "$sectorName · sector en auge" else sectorName,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (signal.reasons.isNotEmpty()) {
                Column(modifier = Modifier.padding(top = 8.dp)) {
                    signal.reasons.forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidenceBadge(confidenceScore: Int, color: Color) {
    // Ancho fijo pero SIN estar dentro de una fila sin weight: el nombre de al
    // lado ya usa weight(1f) + ellipsis, así que este badge nunca se ve
    // comprimido ni desbordado aunque el título sea muy largo.
    Surface(
        modifier = Modifier.width(76.dp),
        shape = RoundedCornerShape(14.dp),
        color = color
    ) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Confianza",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f),
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$confidenceScore%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * Gráfica de resumen (precio + SMA 10 arriba, volumen abajo — igual que la
 * ficha de detalle de un stock) para el índice/ETF representativo del
 * universo de la pestaña activa: SPY para S&P 500, QQQ para Nasdaq-100,
 * ^IBEX para IBEX 35 (ver Symbols.UNIVERSE_INDEX_SYMBOLS). Con pestañas de
 * rango (1D/1S/1M/1A/5A), igual que en la ficha de detalle de un stock —
 * mismo DETAIL_CHART_RANGES que ya se usa ahí.
 */
@Composable
private fun IndexOverviewChartCard(marketRepository: MarketRepository, market: MarketUniverse) {
    val symbol = com.inversionadvisor.domain.model.Symbols.UNIVERSE_INDEX_SYMBOLS[market.indexName] ?: return
    GenericChartCard(marketRepository, symbol, "${market.displayName} ($symbol)", market)
}

/**
 * Igual que IndexOverviewChartCard pero para un símbolo cualquiera, no atado a un
 * MarketUniverse — se usa en Divisas y Bonos USA, donde cada pestaña muestra VARIAS
 * gráficas (una por divisa/bono), no una sola por pestaña como en los mercados de acciones.
 */
@Composable
internal fun GenericChartCard(marketRepository: MarketRepository, symbol: String, title: String, rangeKey: Any = symbol, showVolume: Boolean = true, priceChartHeightDp: Int = 260) {
    // remember(rangeKey): al cambiar de pestaña, el rango vuelve a 1A en vez de arrastrar el
    // de la pestaña anterior — rangeKey por defecto es el propio símbolo, así cada gráfica
    // dentro de una misma pestaña (p. ej. las 5 de Divisas) mantiene su rango independiente.
    var selectedRange by remember(rangeKey) { mutableStateOf(com.inversionadvisor.domain.model.ChartRange.ONE_YEAR) }

    androidx.compose.runtime.LaunchedEffect(symbol, selectedRange) {
        // Para 1 DÍA: petición SIEMPRE fresca, sin mirar la caché — mismo motivo que en la
        // ficha de un stock (ver StockDetailViewModel.loadRange).
        if (selectedRange == com.inversionadvisor.domain.model.ChartRange.ONE_DAY) {
            runCatching { marketRepository.refreshYahooCandles(symbol, selectedRange) }
        } else {
            runCatching { marketRepository.refreshYahooCandlesIfStale(symbol, selectedRange) }
        }
        // Velas diarias reales, solo para SMA50 "de verdad" (50 días de mercado, no semanas) —
        // MISMO fallo que ya se corrigió en la ficha de un stock (ver
        // TechnicalAnalysis.alignDailySmaToWeeklyDates), que aquí se había quedado sin
        // aplicar. Solo tiene sentido para los rangos con SMA (6M/1A/5A) — el resto (1D/1S/1M)
        // no tienen SMA en ningún sitio de la app.
        val range = selectedRange
        if (range == com.inversionadvisor.domain.model.ChartRange.SIX_MONTHS ||
            range == com.inversionadvisor.domain.model.ChartRange.ONE_YEAR ||
            range == com.inversionadvisor.domain.model.ChartRange.FIVE_YEARS
        ) {
            runCatching { marketRepository.refreshDailyCandlesForSmaIfStale(symbol, range) }
        }
    }
    var yesterdayClose by remember(rangeKey) { mutableStateOf<Double?>(null) }
    var todayLivePrice by remember(rangeKey) { mutableStateOf<Double?>(null) }
    androidx.compose.runtime.LaunchedEffect(symbol, selectedRange) {
        // Cierre de ayer + precio en vivo — SOLO para 1 día, para que la variación mostrada
        // coincida con la del banner de ganadores/perdedores (ver
        // MarketRepository.fetchTodayQuoteChange y el mismo uso en la ficha de un stock).
        if (selectedRange == com.inversionadvisor.domain.model.ChartRange.ONE_DAY) {
            val todayChange = runCatching { marketRepository.fetchTodayQuoteChange(symbol) }.getOrNull()
            yesterdayClose = todayChange?.yesterdayClose
            todayLivePrice = todayChange?.currentPrice
        } else {
            yesterdayClose = null
            todayLivePrice = null
        }
    }
    val candles by marketRepository.observeCandles(symbol, selectedRange)
        .collectAsState(initial = emptyList())
    // AÑADIDO — horario extendido del gráfico 1D (ver StockDetailViewModel.regularSessionBoundsFlow
    // para el mismo patrón en la ficha de un stock).
    val regularSessionBounds by (
        if (selectedRange == com.inversionadvisor.domain.model.ChartRange.ONE_DAY) {
            marketRepository.observeRegularSessionBounds(symbol, com.inversionadvisor.domain.model.ChartRange.ONE_DAY)
        } else {
            kotlinx.coroutines.flow.flowOf(null)
        }
    ).collectAsState(initial = null)
    val dailyCandlesForSma by (
        if (selectedRange == com.inversionadvisor.domain.model.ChartRange.SIX_MONTHS ||
            selectedRange == com.inversionadvisor.domain.model.ChartRange.ONE_YEAR ||
            selectedRange == com.inversionadvisor.domain.model.ChartRange.FIVE_YEARS
        ) {
            marketRepository.observeDailyCandlesForSma(symbol, selectedRange)
        } else {
            kotlinx.coroutines.flow.flowOf(emptyList())
        }
    ).collectAsState(initial = emptyList())
    // Para índices/divisas/bonos (esta tarjeta genérica, distinta de la ficha de un stock):
    // SMA20 y SMA50 — pedido expresamente así (antes SIN SMA20). La SMA50 empieza DESACTIVADA
    // (se activa tocando la leyenda, ver sma50InitiallyVisible más abajo); la SMA20 empieza
    // visible, igual que en la ficha de un stock. Las dos calculadas sobre las velas DIARIAS y
    // alineadas al eje semanal del gráfico, no sobre las velas semanales directamente (eso
    // daría medias de 20/50 SEMANAS, no de 20/50 días).
    val sma20 = remember(candles, dailyCandlesForSma) {
        val dailySma20 = com.inversionadvisor.domain.indicators.TechnicalAnalysis.simpleMovingAverage(dailyCandlesForSma, period = 20)
        com.inversionadvisor.domain.indicators.TechnicalAnalysis.alignDailySmaToWeeklyDates(candles, dailyCandlesForSma, dailySma20)
    }
    val sma50 = remember(candles, dailyCandlesForSma) {
        val dailySma50 = com.inversionadvisor.domain.indicators.TechnicalAnalysis.simpleMovingAverage(dailyCandlesForSma, period = 50)
        com.inversionadvisor.domain.indicators.TechnicalAnalysis.alignDailySmaToWeeklyDates(candles, dailyCandlesForSma, dailySma50)
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Row(modifier = Modifier.padding(bottom = 8.dp)) {
                DETAIL_CHART_RANGES.forEach { range ->
                    val selected = range == selectedRange
                    Surface(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clickable { selectedRange = range },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                    ) {
                        Text(
                            range.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            if (candles.size < 2) {
                Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            } else {
                PriceAndVolumeChart(
                    candles = candles,
                    sma20 = sma20,
                    range = selectedRange,
                    modifier = Modifier.fillMaxWidth(),
                    showVolume = showVolume,
                    priceChartHeightDp = priceChartHeightDp,
                    sma50 = sma50,
                    sma50InitiallyVisible = false,
                    yesterdayClose = yesterdayClose,
                    todayLivePrice = todayLivePrice,
                    regularSessionBounds = regularSessionBounds
                )
            }
        }
    }
}

/**
 * Semáforo explicando el nivel actual del índice MOVE (volatilidad implícita de los bonos del
 * Tesoro de EE. UU., el "VIX de los bonos") — pedido aparte de la gráfica en sí. Cortes y
 * colores tal cual se especificaron (el naranja para el rango "normal" es intencional, no un
 * error: aquí el naranja no significa alerta, es solo el color asignado a ese tramo).
 */
@Composable
private fun MoveIndexSemaphoreCard(marketRepository: MarketRepository) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { marketRepository.refreshYahooQuoteIfStale("^MOVE") }
    }
    val quote by marketRepository.observeQuote("^MOVE").collectAsState(initial = null)
    val moveValue = quote?.close

    data class Zone(val label: String, val range: String, val color: Color, val description: String)
    val zones = listOf(
        Zone("Calma extrema", "por debajo de 80", Color(0xFF2E7D32), "Indica mucha tranquilidad o complacencia en el mercado de renta fija."),
        Zone("Valores normales", "entre 80 y 120", Color(0xFFEF6C00), "Reflejan un mercado de deuda estable y previsiones de cambios suaves en los tipos de interés."),
        Zone("Valores altos / fuera de lo normal", "por encima de 120", Color(0xFFC62828), "Señala gran nerviosismo, incertidumbre elevada y miedo a cambios agresivos por parte de los bancos centrales, alta inflación o crisis sistémicas.")
    )
    val activeZoneIndex = when {
        moveValue == null -> null
        moveValue < 80.0 -> 0
        moveValue <= 120.0 -> 1
        else -> 2
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Índice MOVE — ¿qué significa el nivel actual?", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (moveValue != null) {
                    val zoneColor = activeZoneIndex?.let { zones[it].color } ?: Color(0xFF616161)
                    Surface(shape = RoundedCornerShape(8.dp), color = zoneColor.copy(alpha = 0.15f)) {
                        Text(
                            "%.1f".format(moveValue),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = zoneColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.padding(top = 10.dp))

            zones.forEachIndexed { index, zone ->
                if (index > 0) androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                val isActive = activeZoneIndex == index
                Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .padding(top = 4.dp, end = 10.dp)
                            .size(10.dp)
                            .background(zone.color, RoundedCornerShape(50))
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "${zone.label} (${zone.range})",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                            color = if (isActive) zone.color else MaterialTheme.colorScheme.onSurface
                        )
                        Text(zone.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

/**
 * Panel azul con el tipo de interés de referencia de EE. UU., arriba del todo en Bonos USA
 * (pedido explícitamente en ese orden). OJO — sigue sin ser el tipo oficial de la Reserva
 * Federal (el "Fed Funds Rate" en sí, publicado por la Fed, no cotiza como un valor en Yahoo):
 * esa serie exacta necesitaría otra fuente aparte (p. ej. FRED, no integrada en esta app).
 *
 * Se usa la letra del Tesoro a 3 meses (^IRX) en vez del bono a 10 años (^TNX, lo que había
 * antes) — el corto plazo sigue mucho más de cerca la política de la Fed que el largo plazo,
 * que además incorpora expectativas de inflación y prima a plazo, no solo el tipo actual. Con
 * el bono a 10 años salía un 4,69%, muy alejado del tipo real de la Fed en este momento — con
 * el de 3 meses debería quedar mucho más cerca.
 */
@Composable
private fun InterestRatePanelCard(marketRepository: MarketRepository) {
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { marketRepository.refreshYahooQuoteIfStale("^IRX") }
    }
    val quote by marketRepository.observeQuote("^IRX").collectAsState(initial = null)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Tipo de interés (EE. UU.)", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.White)
            Text(
                quote?.close?.let { "%.2f".format(it) + "%" } ?: "—",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                "Letra del Tesoro a 3 meses (^IRX) — sigue de cerca el tipo de la Fed, pero no es la cifra oficial publicada por la Reserva Federal.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Pestaña "Top 10": las 10 mejores acciones para invertir combinando tendencia, sector,
 * ingresos ascendentes, RSI, PER vs sector, caída+agotamiento, volumen y volatilidad propia
 * (ver Top10Calculator para el desglose completo). A propósito, NO se calcula sola — hace
 * falta pulsar el botón, y solo tiene datos si ya se analizaron antes S&P 500, Nasdaq-100 e
 * IBEX 35 (el aviso de abajo lo explica).
 */
@Composable
private fun Top10Section(
    state: ScreenerUiState,
    onCalculateClick: () -> Unit,
    onEntryClick: (String) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Top10 - Las acciones más rentables", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // QUITADO a petición expresa: el aviso "hace falta haber pulsado Analizar antes" — ya no
        // aplica, S&P 500/Nasdaq-100/IBEX 35 (y luego Top10) se cargan solos al abrir la app
        // (ver MainActivity), no hace falta pulsar nada a mano para tener datos aquí.

        // OCULTADO a petición expresa: el botón "Calcular Top 10" — se calcula solo tras
        // analizar los 3 mercados, ver MainActivity. Se mantiene el indicador de progreso de
        // abajo, útil mientras corre el cálculo automático al abrir la app.

        if (state.isCalculatingTop10) {
            Spacer(modifier = Modifier.padding(top = 12.dp))
            val fraction = if (state.top10ProgressTotal > 0) state.top10ProgressDone.toFloat() / state.top10ProgressTotal else 0f
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth().height(6.dp))
            Text(
                if (state.top10ProgressTotal > 0) "Analizando candidatos: ${state.top10ProgressDone}/${state.top10ProgressTotal}" else "Preparando…",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        state.top10Error?.let {
            Text("No se pudo calcular: $it", color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
        }

        state.top10LastUpdatedAt?.let { updatedAt ->
            val formatter = remember(updatedAt) { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("es", "ES")) }
            Text(
                "Último cálculo: ${formatter.format(java.util.Date(updatedAt))}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 10.dp)
            )
        }

        if (state.top10Entries.isEmpty() && !state.isCalculatingTop10 && state.top10Error == null) {
            Text(
                "Todavía no hay resultados — se calculará solo en cuanto los 3 mercados terminen de analizarse.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 16.dp)
            )
        }

        state.top10Entries.forEach { entry ->
            Spacer(modifier = Modifier.padding(top = 12.dp))
            Top10EntryCard(entry, onClick = { onEntryClick(entry.symbol) })
        }
    }
}

private fun com.inversionadvisor.domain.indicators.FactorGrade.toColor(): Color = when (this) {
    com.inversionadvisor.domain.indicators.FactorGrade.GOOD -> Color(0xFF2E7D32)
    com.inversionadvisor.domain.indicators.FactorGrade.NEUTRAL -> Color(0xFFEF6C00)
    com.inversionadvisor.domain.indicators.FactorGrade.BAD -> Color(0xFFC62828)
    com.inversionadvisor.domain.indicators.FactorGrade.UNKNOWN -> Color(0xFF9E9E9E)
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) // FlowRow — ver comentario donde se usa
private fun Top10EntryCard(entry: com.inversionadvisor.domain.model.Top10Entry, onClick: () -> Unit) {
    // Vuelta a la escala 0-100, 4 colores: 0-25 rojo, 25-50 naranja, 50-75 amarillo, 75-100 verde.
    val scoreColor = when {
        entry.combinedScore < 25 -> Color(0xFFC62828)
        entry.combinedScore < 50 -> Color(0xFFEF6C00)
        entry.combinedScore < 75 -> Color(0xFFF9A825)
        else -> Color(0xFF2E7D32)
    }
    // "Bocadillo informativo" al tocar el número de puntuación — mismo diálogo compartido que
    // la ficha del stock (CalculationBreakdownDialog, en StockDetailScreen.kt). Ya no hay chips
    // separados de "Recompensa"/"Riesgo" con la fórmula nueva (son 7 categorías, no dos
    // números aparte), así que el bocadillo se engancha al número grande de arriba, combinando
    // las dos listas (qué sumó y qué restó).
    var openBreakdown by remember { mutableStateOf(false) }
    if (openBreakdown) {
        CalculationBreakdownDialog(
            "Cómo se calculó (0-100)",
            entry.rewardBreakdown + entry.riskBreakdown,
            onDismiss = { openBreakdown = false }
        )
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Surface(shape = RoundedCornerShape(50), color = scoreColor.copy(alpha = 0.15f), modifier = Modifier.size(28.dp)) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text("${entry.rank}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = scoreColor)
                    }
                }
                Column(modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                    Text("${entry.name} (${entry.symbol})", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    entry.sectorName?.let {
                        Surface(shape = RoundedCornerShape(50), color = Color(0xFF1565C0).copy(alpha = 0.15f), modifier = Modifier.padding(top = 2.dp)) {
                            Text(it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0), modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = scoreColor.copy(alpha = 0.15f),
                    modifier = Modifier.clickable { openBreakdown = true }
                ) {
                    Text(
                        "${"%.0f".format(entry.combinedScore)} ℹ",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = scoreColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Chips en barra deslizable horizontal, en turquesa — vuelta a este formato, pedido
            // explícitamente en vez de la tabla fija que había antes.
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(top = 10.dp)
                    .horizontalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                // "Valoración" ya no se filtra — PER se volvió a pedir en Top10Repository
                // para que esta categoría funcione (20% del peso de la fórmula).
                entry.factors.forEach { factor ->
                    Surface(shape = RoundedCornerShape(8.dp), color = TOP10_TURQUOISE.copy(alpha = 0.15f)) {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text(factor.label, style = MaterialTheme.typography.labelSmall, color = TOP10_TURQUOISE)
                            Text(factor.valueText, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = factor.grade.toColor())
                        }
                    }
                }
            }

            Text(entry.analystSummary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))

            // Avisos en neón — mismos que en Futuras Compras y en la ficha del stock, ahora
            // también aquí en el propio panel de Top10 (antes solo se calculaban y guardaban,
            // pero no se pintaban en esta tarjeta en concreto). Verde para bonos, rojo para
            // penalizaciones.
            if (entry.penaltyWarnings.isNotEmpty() || entry.bonusWarnings.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    entry.bonusWarnings.forEach { warning ->
                        com.inversionadvisor.ui.common.BlinkingNeonBadge(warning, positive = true)
                    }
                    entry.penaltyWarnings.forEach { warning ->
                        com.inversionadvisor.ui.common.BlinkingNeonBadge(warning)
                    }
                }
            }
        }
    }
}

/** Turquesa pedido explícitamente para la tabla fija de factores de Top 10. */
private val TOP10_TURQUOISE = Color(0xFF00897B)
