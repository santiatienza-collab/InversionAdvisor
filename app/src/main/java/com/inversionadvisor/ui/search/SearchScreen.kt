package com.inversionadvisor.ui.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.inversionadvisor.data.repository.MarketRepository
import com.inversionadvisor.domain.model.MarketUniverse
import com.inversionadvisor.domain.model.StockUniverseEntry
import com.inversionadvisor.ui.screener.StockDetailScreen

/**
 * Pestaña "Busca": buscador de cualquier stock de los 3 universos (S&P 500,
 * Nasdaq-100, IBEX 35) por nombre o ticker. Al encontrarlo se abre la misma
 * pantalla de detalle que usa Análisis (gráfica + SMA 10 + volumen +
 * "Momento idóneo para la venta"), con una estrellita para guardarlo en
 * favoritos. Sin búsqueda activa, muestra los favoritos guardados.
 */
@Composable
fun SearchScreen(viewModel: SearchViewModel, marketRepository: MarketRepository) {
    val state by viewModel.uiState.collectAsState()

    val selectedSymbol = state.selectedSymbol
    if (selectedSymbol != null) {
        // NUEVO — pedido expresamente: mostrar el nombre completo de la acción además del
        // ticker al entrar en la ficha. Se busca en los resultados de búsqueda y en favoritos
        // (ambas son List<StockUniverseEntry>, que ya tienen el nombre), no hace falta pedir
        // nada nuevo a la red.
        val nombreCompleto = (state.results + state.favorites).firstOrNull { it.symbol == selectedSymbol }?.name
        val indexEntry = (state.results + state.favorites).firstOrNull { it.symbol == selectedSymbol }
        val indexDisplayName = com.inversionadvisor.domain.model.MarketUniverse.values()
            .firstOrNull { it.indexName == indexEntry?.indexName }?.displayName
        StockDetailScreen(
            symbol = selectedSymbol,
            marketRepository = marketRepository,
            onBack = viewModel::clearStockSelection,
            displayTitle = if (nombreCompleto != null) "$selectedSymbol — $nombreCompleto" else selectedSymbol,
            indexDisplayName = indexDisplayName,
            // De state.favorites (observado con collectAsState), no de viewModel.isFavorite()
            // directamente — mismo fallo que se corrigió en Análisis: si no, la estrellita no
            // se actualiza al tocarla hasta volver atrás y reentrar.
            isFavorite = state.favorites.any { it.symbol == selectedSymbol },
            onToggleFavorite = { viewModel.toggleFavoriteBySymbol(selectedSymbol) }
        )
        return
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Nombre o ticker (p. ej. Apple o AAPL)") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.onQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Borrar búsqueda")
                    }
                }
            },
            singleLine = true
        )

        state.error?.let { error ->
            Text(
                "No se pudo cargar el universo de mercados: $error",
                color = com.inversionadvisor.ui.theme.DarkErrorLight,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (state.query.isBlank()) {
                item {
                    Text(
                        "Favoritos",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
                if (state.favorites.isEmpty()) {
                    item {
                        Text(
                            "Todavía no has guardado ningún stock. Busca uno por nombre o ticker y pulsa la estrella para guardarlo.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                } else {
                    items(state.favorites, key = { it.symbol }) { entry ->
                        StockResultRow(
                            entry = entry,
                            isFavorite = true,
                            onClick = { viewModel.selectStock(entry.symbol) },
                            onToggleFavorite = { viewModel.toggleFavorite(entry) }
                        )
                    }
                }
            } else if (state.isLoadingUniverse) {
                item {
                    Text("Cargando el universo de mercados…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (state.results.isEmpty()) {
                item {
                    Text("Sin resultados para \"${state.query}\" en S&P 500/Nasdaq-100/IBEX 35.", style = MaterialTheme.typography.bodyMedium)
                }
                item {
                    // Búsqueda ampliada: solo se ofrece cuando la local no encontró nada — no
                    // tiene sentido consultar red si ya hay resultados locales de sobra.
                    if (!state.hasSearchedWholeMarket) {
                        Button(onClick = viewModel::searchWholeMarket, enabled = !state.isSearchingWholeMarket) {
                            Text("Buscar en todo el mercado")
                        }
                    }
                }
                if (state.isSearchingWholeMarket) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
                state.wholeMarketSearchError?.let { error ->
                    item {
                        Text(
                            "No se pudo buscar: $error",
                            color = com.inversionadvisor.ui.theme.DarkErrorLight,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (state.wholeMarketResults.isNotEmpty()) {
                    item {
                        Text(
                            "Resultados en todo el mercado",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(state.wholeMarketResults, key = { it.symbol }) { entry ->
                        StockResultRow(
                            entry = entry,
                            isFavorite = state.favorites.any { it.symbol == entry.symbol },
                            onClick = { viewModel.selectStock(entry.symbol) },
                            onToggleFavorite = { viewModel.toggleFavorite(entry) }
                        )
                    }
                } else if (state.hasSearchedWholeMarket && !state.isSearchingWholeMarket && state.wholeMarketSearchError == null) {
                    item {
                        Text("Tampoco se encontró nada en el mercado completo.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            } else {
                items(state.results, key = { it.symbol }) { entry ->
                    StockResultRow(
                        entry = entry,
                        isFavorite = state.favorites.any { it.symbol == entry.symbol },
                        onClick = { viewModel.selectStock(entry.symbol) },
                        onToggleFavorite = { viewModel.toggleFavorite(entry) }
                    )
                }
            }
        }
    }
}

@Composable
private fun StockResultRow(
    entry: StockUniverseEntry,
    isFavorite: Boolean,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(entry.symbol, style = MaterialTheme.typography.bodySmall)
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    ) {
                        Text(
                            entry.indexDisplayName(),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    Icons.Filled.Star,
                    contentDescription = if (isFavorite) "Quitar de favoritos" else "Guardar en favoritos",
                    tint = if (isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                )
            }
        }
    }
}

private fun StockUniverseEntry.indexDisplayName(): String =
    MarketUniverse.entries.find { it.indexName == indexName }?.displayName
        ?: indexName.ifBlank { "Otro mercado" }
