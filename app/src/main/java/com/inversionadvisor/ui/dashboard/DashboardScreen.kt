package com.inversionadvisor.ui.dashboard

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.inversionadvisor.data.repository.MarketRepository
import com.inversionadvisor.domain.model.AaiiSentiment
import com.inversionadvisor.domain.model.ChartRange
import com.inversionadvisor.domain.model.CnnFearGreedStatus
import com.inversionadvisor.domain.model.Currency
import com.inversionadvisor.domain.model.DisplayCurrency
import com.inversionadvisor.domain.model.Symbols
import com.inversionadvisor.domain.model.GoldConversion
import com.inversionadvisor.domain.model.MarketMover
import com.inversionadvisor.domain.model.Quote
import com.inversionadvisor.domain.model.RiskLevel
import com.inversionadvisor.domain.model.RotationHorizon
import com.inversionadvisor.domain.model.SectorPerformance
import com.inversionadvisor.domain.model.VixStatus
import com.inversionadvisor.ui.common.SectionHeader
import com.inversionadvisor.ui.common.toColor
import com.inversionadvisor.ui.screener.StockDetailScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Mini gráfica de línea puramente ilustrativa (sin ejes ni etiquetas) que
 * acompaña el valor numérico de cada tarjeta del panel. Se dibuja con
 * Canvas nativo de Compose para no añadir ninguna librería de gráficas al
 * proyecto — con 2-8 puntos (velas recientes, semanas de AAII, etc.) es
 * más que suficiente para dar una idea visual de la tendencia.
 */
@Composable
private fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier.width(72.dp).height(32.dp)) {
    if (values.size < 2) return
    Canvas(modifier = modifier) {
        val minValue = values.min()
        val maxValue = values.max()
        val range = (maxValue - minValue).takeIf { it > 0f } ?: 1f
        val stepX = size.width / (values.size - 1)

        val points = values.mapIndexed { index, value ->
            Offset(x = index * stepX, y = size.height - ((value - minValue) / range) * size.height)
        }

        val linePath = Path().apply {
            moveTo(points.first().x, points.first().y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
        }
        val fillPath = Path().apply {
            addPath(linePath)
            lineTo(points.last().x, size.height)
            lineTo(points.first().x, size.height)
            close()
        }

        drawPath(fillPath, color = color.copy(alpha = 0.15f), style = Fill)
        drawPath(linePath, color = color, style = Stroke(width = 3f))
    }
}

/**
 * Referencia rápida (dato general de mercado, no en tiempo real ni calculado
 * por la app) de en qué rango suele moverse el PER de cada gran sector — para
 * poder interpretar de un vistazo si el PER real de un sector (el que sí se
 * calcula, en la ficha de cada stock) está caro, barato o en línea con lo
 * habitual para ese tipo de negocio.
 */
private data class SectorPeRange(val sectorLabel: String, val minPe: Double, val maxPe: Double, val note: String) {
    val midPe: Double get() = (minPe + maxPe) / 2
    val rangeLabel: String get() = "${"%.0f".format(minPe)}x – ${"%.0f".format(maxPe)}x"
}

private val TYPICAL_SECTOR_PE_RANGES = listOf(
    SectorPeRange("Tecnología / Software", 28.0, 40.0, "Altas expectativas de crecimiento e innovación"),
    SectorPeRange("Consumo cíclico / discrecional", 22.0, 30.0, ""),
    SectorPeRange("Salud", 18.0, 25.0, ""),
    SectorPeRange("Energía", 13.0, 21.0, ""),
    SectorPeRange("Servicios públicos (utilities)", 15.0, 16.0, ""),
    SectorPeRange("Financiero", 11.0, 15.0, ""),
    SectorPeRange("Materiales básicos", 24.0, 25.0, ""),
    // NUEVO — pedido expresamente, faltaba en esta lista (aunque ya estaba en
    // Symbols.SECTOR_TYPICAL_PE_RANGES, usado para la puntuación — esta lista de aquí es aparte,
    // solo para esta tarjeta informativa del Panel).
    SectorPeRange("Industrial", 18.0, 24.0, "")
)

/** Paleta fija (una por sector, en el mismo orden que TYPICAL_SECTOR_PE_RANGES) para el gráfico de burbujas. */
private val SECTOR_PE_BUBBLE_COLORS = listOf(
    Color(0xFF5E35B1), // Tecnología — morado
    Color(0xFFEF6C00), // Consumo cíclico — naranja
    Color(0xFF00897B), // Salud — verde azulado
    Color(0xFFC62828), // Energía — rojo
    Color(0xFFFDD835), // Utilities — amarillo
    Color(0xFF1E88E5), // Financiero — azul
    Color(0xFF6D4C41), // Materiales básicos — marrón
    Color(0xFF546E7A)  // Industrial — gris azulado
)

@Composable
private fun TypicalSectorPeRangesCard() {
    var expanded by remember { mutableStateOf(false) }
    val tealColor = Color(0xFF00695C)
    val elegantFont = FontFamily.Serif

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = tealColor.copy(alpha = 0.10f))
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Info PER",
                        style = MaterialTheme.typography.labelLarge,
                        fontFamily = elegantFont,
                        fontWeight = FontWeight.Bold,
                        color = tealColor
                    )
                    Text(
                        "Rangos típicos de PER por sector, para comparar",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = elegantFont
                    )
                }
                Text(
                    if (expanded) "▲" else "▼",
                    style = MaterialTheme.typography.titleMedium,
                    color = tealColor,
                    fontWeight = FontWeight.Bold
                )
            }
            if (expanded) {
                Column(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    Text(
                        "Referencia general de mercado, no un cálculo en tiempo real",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = elegantFont,
                        modifier = Modifier.padding(bottom = 14.dp)
                    )
                    SectorPeBarChart(elegantFont, tealColor)
                }
            }
        }
    }
}

/**
 * Gráfico de barras horizontales: una barra de color por sector, con el
 * ancho representando el rango de PER (desde el mínimo hasta el máximo
 * típico), colocada sobre un eje común para poder comparar a simple vista
 * qué sectores suelen cotizar más caro (barras más a la derecha) y cuáles
 * más barato (más a la izquierda).
 */
@Composable
private fun SectorPeBarChart(fontFamily: FontFamily, axisColor: Color) {
    val minAxis = 0.0
    val maxAxis = TYPICAL_SECTOR_PE_RANGES.maxOf { it.maxPe } + 2.0
    val axisSpan = (maxAxis - minAxis).takeIf { it > 0.0 } ?: 1.0

    Column(modifier = Modifier.fillMaxWidth()) {
        TYPICAL_SECTOR_PE_RANGES.forEachIndexed { index, range ->
            val color = SECTOR_PE_BUBBLE_COLORS.getOrElse(index) { axisColor }
            val startFraction = ((range.minPe - minAxis) / axisSpan).toFloat().coerceIn(0f, 1f)
            val endFraction = ((range.maxPe - minAxis) / axisSpan).toFloat().coerceIn(0f, 1f)

            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        range.sectorLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = fontFamily,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp)
                    )
                    Text(
                        range.rangeLabel,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                }
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(color.copy(alpha = 0.15f))
                ) {
                    Row(modifier = Modifier.fillMaxSize()) {
                        Spacer(modifier = Modifier.weight(startFraction.coerceAtLeast(0.0001f)))
                        Box(
                            modifier = Modifier
                                .weight((endFraction - startFraction).coerceAtLeast(0.02f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(7.dp))
                                .background(color)
                        )
                        Spacer(modifier = Modifier.weight((1f - endFraction).coerceAtLeast(0.0001f)))
                    }
                }
                if (range.note.isNotEmpty()) {
                    Text(
                        range.note,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = fontFamily,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
        }
    }
}

private enum class SectorTableSortColumn { SECTOR, STRENGTH, CHANGE, PE }

/**
 * Tabla ordenable de sectores: columnas Sector / Fuerza relativa / PER, cada
 * celda de fuerza relativa con su semáforo de color (mismo criterio que el
 * resto de la app). Tocar una cabecera cambia el orden (y si ya estaba
 * ordenado por esa columna, invierte ascendente/descendente); tocar una fila
 * la resalta un momento (todo dentro de la propia tabla, ya no hace falta
 * ninguna sección aparte con el listado completo de sectores).
 */
@Composable
private fun SectorSortableTable(sectors: List<SectorPerformance>, horizon: RotationHorizon) {
    if (sectors.isEmpty()) return
    var sortColumn by remember { mutableStateOf(SectorTableSortColumn.STRENGTH) }
    var ascending by remember { mutableStateOf(false) }
    var highlightedEtf by remember { mutableStateOf<String?>(null) }
    // Colapsada por defecto: solo los 5 primeros según el orden activo, con un "Ver todos"
    // para desplegar el resto — se pidió para no tener que hacer scroll por los 11 sectores.
    var showAll by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    fun onHeaderClick(column: SectorTableSortColumn) {
        if (sortColumn == column) {
            ascending = !ascending
        } else {
            sortColumn = column
            ascending = false
        }
    }

    fun onRowClick(etfSymbol: String) {
        coroutineScope.launch {
            highlightedEtf = etfSymbol
            delay(1_200L)
            if (highlightedEtf == etfSymbol) highlightedEtf = null
        }
    }

    val sorted = remember(sectors, horizon, sortColumn, ascending) {
        val comparator = when (sortColumn) {
            SectorTableSortColumn.SECTOR -> compareBy<SectorPerformance> { it.sectorName }
            SectorTableSortColumn.STRENGTH -> compareBy { it.relativeStrengthPercent(horizon) }
            SectorTableSortColumn.CHANGE -> compareBy { it.changePercent(horizon) }
            // PER null se manda siempre al final, en ambos sentidos de orden — no tiene
            // sentido intercalarlo entre valores reales solo por ser "0" o "infinito".
            SectorTableSortColumn.PE -> compareBy<SectorPerformance> { it.averagePE == null }.thenBy { it.averagePE ?: 0.0 }
        }
        sectors.sortedWith(if (ascending) comparator else comparator.reversed())
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Tabla de sectores", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text(
                "Toca una cabecera para ordenar, o una fila para resaltarla",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )

            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                SectorTableHeaderCell("Sector", Modifier.weight(1.4f), sortColumn == SectorTableSortColumn.SECTOR, ascending) { onHeaderClick(SectorTableSortColumn.SECTOR) }
                SectorTableHeaderCell("Var.", Modifier.weight(0.7f), sortColumn == SectorTableSortColumn.CHANGE, ascending) { onHeaderClick(SectorTableSortColumn.CHANGE) }
                SectorTableHeaderCell("Fuerza rel.", Modifier.weight(0.9f), sortColumn == SectorTableSortColumn.STRENGTH, ascending) { onHeaderClick(SectorTableSortColumn.STRENGTH) }
                SectorTableHeaderCell("PER", Modifier.weight(0.6f), sortColumn == SectorTableSortColumn.PE, ascending) { onHeaderClick(SectorTableSortColumn.PE) }
            }
            androidx.compose.material3.HorizontalDivider()

            val visibleSectors = if (showAll) sorted else sorted.take(3)
            visibleSectors.forEachIndexed { index, sector ->
                if (index > 0) androidx.compose.material3.HorizontalDivider()
                val strength = sector.relativeStrengthPercent(horizon)
                val change = sector.changePercent(horizon)
                val highlightColor by animateColorAsState(
                    targetValue = if (highlightedEtf == sector.etfSymbol) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent,
                    label = "sectorTableRowHighlight"
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(highlightColor, RoundedCornerShape(8.dp))
                        .clickable { onRowClick(sector.etfSymbol) }
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1.4f).padding(end = 8.dp)) {
                        Text(
                            sector.sectorName,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(sector.etfSymbol, style = MaterialTheme.typography.bodySmall)
                    }
                    // Variación en solitario del sector (sin comparar con el mercado) — es un
                    // dato distinto de la fuerza relativa: un sector puede subir (variación
                    // positiva) y aun así ir peor que el mercado (fuerza relativa negativa), o
                    // al revés. Color simple verde/rojo por signo, no el semáforo de 3 niveles
                    // que sí lleva la fuerza relativa (esta columna no tiene esa lectura).
                    Text(
                        "${if (change >= 0) "+" else ""}${"%.1f".format(change)}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (change >= 0) Color(0xFF2E7D32) else Color(0xFFC62828),
                        modifier = Modifier.weight(0.7f)
                    )
                    Box(modifier = Modifier.weight(0.9f)) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = sector.riskLevel(horizon).toColor().copy(alpha = 0.18f)
                        ) {
                            Text(
                                "${if (strength >= 0) "+" else ""}${"%.1f".format(strength)}%",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = sector.riskLevel(horizon).toColor(),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    Text(
                        sector.averagePE?.let { "${"%.0f".format(it)}x" } ?: "—",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(0.6f)
                    )
                }
            }

            if (sorted.size > 3) {
                Text(
                    if (showAll) "Ver solo los 3 primeros ▲" else "Ver todos los sectores (${sorted.size}) ▼",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showAll = !showAll }
                        .padding(top = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun SectorTableHeaderCell(label: String, modifier: Modifier, isActive: Boolean, ascending: Boolean, onClick: () -> Unit) {
    Row(
        modifier = modifier.clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
        )
        if (isActive) {
            Text(
                if (ascending) " ▲" else " ▼",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}


/**
 * Selector de horizonte temporal (1 mes / 3 meses / 6 meses) para la
 * rotación sectorial. Las rotaciones de verdad suelen tardar varios meses
 * en confirmarse.
 */
@Composable
private fun RotationHorizonSelector(selected: RotationHorizon, onSelect: (RotationHorizon) -> Unit) {
    TabRow(selectedTabIndex = RotationHorizon.entries.indexOf(selected)) {
        RotationHorizon.entries.forEach { horizon ->
            Tab(
                selected = selected == horizon,
                onClick = { onSelect(horizon) },
                text = { Text(horizon.label) }
            )
        }
    }
}

/**
 * Resalta hasta 4 sectores con mayor fuerza relativa frente al S&P 500 EN
 * EL HORIZONTE SELECCIONADO arriba (1M/3M/6M) — antes esta tarjeta usaba
 * siempre la media de los 3 horizontes sin importar qué pestaña de
 * horizonte estuviera activa, así que no coincidía con lo que mostraba el
 * gráfico de barras de abajo (que sí respeta el horizonte elegido): un
 * sector podía verse "fuerte" en la pestaña de 1 mes en el gráfico de
 * barras y no aparecer aquí (o al revés), pareciendo que la app no
 * mostraba realmente los sectores más fuertes. Ahora ambos widgets usan
 * el mismo criterio y horizonte, así que siempre coinciden.
 *
 * Solo entran sectores que baten realmente al mercado en ESE horizonte
 * (fuerza relativa > 0%); si menos de 4 lo consiguen, se muestran menos de
 * 4 (nunca se rellena el hueco con un sector plano o en negativo solo por
 * completar el TOP 4). Si además el sector bate al mercado en TODOS los
 * horizontes a la vez (1M, 3M y 6M), se marca como "líder consistente": la
 * señal más clara de que hay una rotación sectorial sostenida en marcha,
 * no un repunte puntual del horizonte que se esté mirando ahora.
 */
/**
 * "Líder relativo del momento" — pedido expresamente para garantizar que SIEMPRE haya un sector
 * destacado, incluso cuando ninguno cumple el criterio estricto de "en auge" (isConsistentLeader:
 * ganar al S&P 500 en 1M, 3M Y 6M a la vez). Muestra el sector con mejor fuerza relativa
 * PONDERADA (averageRelativeStrengthPercent — ya calculada así en SectorRotationCalculator,
 * dando más peso a 1 mes que a 3, y más a 3 que a 6), sea o no un "líder consistente" de verdad.
 *
 * Etiqueta y color DELIBERADAMENTE distintos de "Líder consistente (1M-6M)" (verde, en
 * StrongestSectorsCard) para que quede claro que es un dato distinto: aquí no se exige ganar al
 * mercado en los tres plazos, solo es el mejor relativo ahora mismo — puramente informativo, NO
 * entra en la puntuación de Top10/Futuras Compras (que sigue usando solo el criterio estricto).
 */
@Composable
private fun LiderRelativoDelMomentoCard(sectors: List<SectorPerformance>) {
    // La lista ya llega ordenada por averageRelativeStrengthPercent descendente (ver
    // SectorRotationCalculator.calculate) — el primero es, por definición, el líder relativo.
    val lider = sectors.firstOrNull() ?: return
    val colorAcento = Color(0xFF6A1B9A) // morado — distinto del verde de "líder consistente"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = colorAcento.copy(alpha = 0.10f)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(6.dp), color = colorAcento) {
                        Text(
                            "LÍDER RELATIVO DEL MOMENTO",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    lider.sectorName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Text(
                    if (lider.isConsistentLeader) {
                        "También cumple el criterio estricto (gana al S&P 500 en 1M, 3M y 6M a la vez)."
                    } else {
                        "El mejor por fuerza relativa ponderada ahora mismo, aunque no gane al S&P 500 en los 3 plazos a la vez."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                "${if (lider.averageRelativeStrengthPercent >= 0) "+" else ""}${"%.1f".format(lider.averageRelativeStrengthPercent)}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = colorAcento
            )
        }
    }
}

@Composable
private fun StrongestSectorsCard(sectors: List<SectorPerformance>, horizon: RotationHorizon) {
    if (sectors.isEmpty()) return
    val strongest = sectors
        .filter { it.relativeStrengthPercent(horizon) > 0.0 }
        .sortedByDescending { it.relativeStrengthPercent(horizon) }
        .take(4)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1565C0).copy(alpha = 0.12f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                "Sectores más fuertes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1565C0)
            )
            Text(
                "Fuerza relativa frente al S&P 500 en el horizonte de ${horizon.label} — mismo criterio que el gráfico de barras de abajo",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.padding(top = 10.dp))
            if (strongest.isEmpty()) {
                Text(
                    "Ningún sector está batiendo al S&P 500 en el horizonte de ${horizon.label} ahora mismo.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            strongest.forEachIndexed { index, sector ->
                // Fondo verde neón FIJO en TODA la fila (no solo el número) para el puesto 1,
                // SOLO en el plazo de 1 mes — pedido expresamente así. SIN PARPADEO (quitado a
                // petición expresa, junto con el resto de avisos en neón de la app).
                val highlightRow = index == 0 && horizon == RotationHorizon.ONE_MONTH
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (highlightRow) {
                                Modifier.background(
                                    Color(0xFF00E676).copy(alpha = 0.35f),
                                    RoundedCornerShape(8.dp)
                                )
                            } else {
                                Modifier
                            }
                        )
                        .padding(vertical = 4.dp, horizontal = if (highlightRow) 6.dp else 0.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(
                                sector.sectorName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (sector.isConsistentLeader) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = RiskLevel.LOW.toColor()
                                ) {
                                    Text(
                                        "Líder consistente (1M-6M)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        "${if (sector.relativeStrengthPercent(horizon) >= 0) "+" else ""}${"%.1f".format(sector.relativeStrengthPercent(horizon))}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (sector.relativeStrengthPercent(horizon) >= 0) RiskLevel.LOW.toColor() else RiskLevel.HIGH.toColor()
                    )
                }
            }
        }
    }
}

/**
 * Banner rotatorio de top ganadores/perdedores del día (universo SP500 +
 * NASDAQ100 + IBEX35): fondo azul, texto blanco, desplazamiento continuo de
 * derecha a izquierda estilo "ticker" de cadena financiera. El contenido se
 * duplica una vez y se desplaza un ancho completo de contenido con
 * animación infinita lineal — al llegar al final del primer bloque ya se
 * ve el segundo (idéntico) empezando, así el bucle es continuo sin salto
 * visible.
 */
@Composable
private fun MarketMoversBanner(topGainers: List<MarketMover>, topLosers: List<MarketMover>) {
    val items = remember(topGainers, topLosers) {
        topGainers.map { it to true } + topLosers.map { it to false }
    }
    if (items.isEmpty()) return

    var contentWidthPx by remember { mutableStateOf(0) }
    val infiniteTransition = rememberInfiniteTransition(label = "market_movers_ticker")
    val offsetPx by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = if (contentWidthPx > 0) contentWidthPx.toFloat() else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = (items.size * 2500).coerceAtLeast(8000),
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "market_movers_offset"
    )

    Surface(
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF0D47A1)
    ) {
        Row(
            modifier = Modifier
                .wrapContentWidth(unbounded = true)
                .offset { IntOffset(x = -offsetPx.roundToInt(), y = 0) }
                .onSizeChanged { size -> if (contentWidthPx == 0) contentWidthPx = size.width / 2 }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // El contenido se repite 2 veces seguidas: mientras el primer bloque
            // sale por la izquierda, el segundo (idéntico) ya está entrando por
            // la derecha, dando la sensación de un bucle continuo sin cortes.
            repeat(2) {
                items.forEach { (mover, isGainer) -> MarketMoverTickerItem(mover, isGainer) }
            }
        }
    }
}

@Composable
private fun MarketMoverTickerItem(mover: MarketMover, isGainer: Boolean) {
    // Ganadores en verde, perdedores en rojo, sobre el fondo azul del banner —
    // el símbolo se mantiene en blanco para que el ticker siga leyéndose como
    // un bloque uniforme, pero la cifra/% (lo importante de un vistazo) lleva
    // el color de sentido: verde sube, rojo baja.
    val changeColor = if (isGainer) Color(0xFF69F0AE) else Color(0xFFFF8A80)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            mover.symbol,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Text(
            "${if (isGainer) "▲" else "▼"} ${if (mover.changePercent >= 0) "+" else ""}${"%.1f".format(mover.changePercent)}%",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = changeColor,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
fun DashboardScreen(viewModel: DashboardViewModel, marketRepository: MarketRepository) {
    val state by viewModel.uiState.collectAsState()

    Surface(modifier = Modifier.fillMaxWidth()) {
        when (val current = state) {
            is DashboardUiState.Loading -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                }
            }
            is DashboardUiState.Content -> {
                val selectedAsset = current.selectedAsset
                if (selectedAsset != null) {
                    StockDetailScreen(
                        symbol = selectedAsset.chartSymbol,
                        marketRepository = marketRepository,
                        onBack = viewModel::clearAssetSelection,
                        displayTitle = selectedAsset.displayTitle,
                        // Correlación (^COR3M): CONFIRMADO con el error real de la app — Yahoo
                        // (la API pública de gráficos que usa la app, /v8/finance/chart/, no la
                        // web) solo devuelve 1 SOLA VELA para este símbolo a 5 AÑOS, sea cual sea
                        // el intervalo probado (semanal/mensual/diario), y TwelveData (el
                        // respaldo) ni siquiera reconoce el símbolo (404). PERO ese registro solo
                        // probó bajo el rango "5y" — 1 AÑO usa una petición a Yahoo DISTINTA
                        // ("1y", más habitual, la misma que usan la mayoría de acciones normales
                        // de la app) que nunca se ha confirmado que falle. Se reactiva 1A a
                        // probar; 5A se mantiene excluido, que es el único con fallo confirmado.
                        // Si 1A también sale vacío, avísame con el mismo tipo de registro de
                        // antes para confirmarlo igual que hicimos con 5A.
                        ranges = if (selectedAsset.chartSymbol == Symbols.CORRELATION_INDEX) {
                            listOf(ChartRange.ONE_DAY, ChartRange.ONE_WEEK, ChartRange.ONE_MONTH, ChartRange.ONE_YEAR)
                        } else {
                            listOf(ChartRange.ONE_DAY, ChartRange.ONE_WEEK, ChartRange.ONE_MONTH, ChartRange.ONE_YEAR, ChartRange.FIVE_YEARS)
                        },
                        showSellTiming = false,
                        showVolume = selectedAsset.showVolume
                    )
                } else {
                    DashboardContent(
                        current,
                        onToggleCurrency = viewModel::toggleDisplayCurrency,
                        onSelectRotationHorizon = viewModel::selectRotationHorizon,
                        onSelectGold = viewModel::selectGold,
                        onSelectBitcoin = viewModel::selectBitcoin,
                        onSelectEthereum = viewModel::selectEthereum
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardContent(
    state: DashboardUiState.Content,
    onToggleCurrency: () -> Unit,
    onSelectRotationHorizon: (RotationHorizon) -> Unit,
    onSelectGold: () -> Unit,
    onSelectBitcoin: () -> Unit,
    onSelectEthereum: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Panel de mercado", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }

        if (state.topGainers.isNotEmpty() || state.topLosers.isNotEmpty()) {
            item { MarketMoversBanner(state.topGainers, state.topLosers) }
        } else {
            // Primera vez / caché vacía todavía: el escaneo del universo completo tarda
            // un rato en dar sus primeros resultados (ver MarketMoversRepository), así
            // que se avisa en vez de dejar un hueco que parezca que el banner no existe.
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Text(
                        "Cargando top ganadores/perdedores…",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        state.refreshError?.let { error ->
            item {
                // CAMBIADO a petición expresa (tema oscuro): antes rosa clarito con texto rojo
                // oscuro, pensado para fondo claro.
                Card(colors = CardDefaults.cardColors(containerColor = com.inversionadvisor.ui.theme.DarkErrorContainer)) {
                    Text(
                        "No se pudo actualizar: $error",
                        modifier = Modifier.padding(12.dp),
                        color = com.inversionadvisor.ui.theme.DarkErrorLight
                    )
                }
            }
        }

        // ---- 1. Indicadores de sentimiento/riesgo: Miedo y Codicia, Alcistas/Bajistas, VIX ----
        item { SectionHeader("Indicadores de mercado") }
        item { FearGreedCard(state.cnnFearGreed) }
        item { AaiiSentimentCard(state.aaiiSentiment) }
        item { VolatilityCard(state.vixStatus) }
        item { CorrelationCard(state.correlationStatus) }

        // ---- 2. Divisa + Oro/Bitcoin/Ethereum: toca cualquiera de las 3 tarjetas para ver su gráfica 1S/1M/1A ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Oro, Bitcoin y Ethereum", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Toca una tarjeta para ver su gráfica", style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onToggleCurrency) {
                    Text(if (state.displayCurrency == DisplayCurrency.USD) "Ver en EUR" else "Ver en USD")
                }
            }
        }
        item { GoldCard(state.goldQuote, state.displayCurrency, state.ratesToUsd, state.goldHistory, onClick = onSelectGold) }
        item { CryptoCard("Bitcoin", state.bitcoinQuote, state.displayCurrency, state.ratesToUsd, state.bitcoinHistory, onClick = onSelectBitcoin) }
        item { CryptoCard("Ethereum", state.ethereumQuote, state.displayCurrency, state.ratesToUsd, state.ethereumHistory, onClick = onSelectEthereum) }

        // ---- 3. Rotación sectorial ----
        item { SectionHeader("Rotación sectorial") }

        // "Líder relativo del momento" — pedido expresamente: el criterio estricto de "en auge"
        // (isConsistentLeader, ganar al S&P 500 en 1M, 3M Y 6M a la vez) puede dejar a veces
        // NINGÚN sector marcado, si el mercado está en una rotación poco clara. Esto se muestra
        // SIEMPRE (no solo cuando no hay ningún líder consistente), con una etiqueta y color
        // distintos para no confundirlo con "Líder consistente (1M-6M)": es simplemente el mejor
        // sector por fuerza relativa PONDERADA ahora mismo, cumpla o no los 3 plazos a la vez. No
        // afecta a la puntuación de Top10/Futuras Compras (que sigue usando solo el criterio
        // estricto) — es puramente informativo.
        if (state.sectorPerformance.isNotEmpty()) {
            item { LiderRelativoDelMomentoCard(state.sectorPerformance) }
        }

        if (state.sectorPerformance.isNotEmpty()) {
            item { RotationHorizonSelector(state.selectedRotationHorizon, onSelectRotationHorizon) }
            item {
                SectorSortableTable(
                    sectors = state.sectorPerformance,
                    horizon = state.selectedRotationHorizon
                )
            }
            item { StrongestSectorsCard(state.sectorPerformance, state.selectedRotationHorizon) }
        }

        if (state.sectorPerformance.isEmpty()) {
            item {
                Column(horizontalAlignment = Alignment.Start) {
                    if (state.isSectorRotationLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.height(20.dp))
                            Spacer(modifier = Modifier.padding(start = 8.dp))
                            Text("Calculando rotación sectorial… puede tardar 1-2 minutos", style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text("No se pudo calcular la rotación sectorial todavía.", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        } else {
            item { TypicalSectorPeRangesCard() }
        }
    }
}

@Composable
private fun IndicatorCard(
    title: String,
    riskLevel: RiskLevel,
    mainText: String,
    subtitle: String,
    history: List<Float> = emptyList(),
    onClick: (() -> Unit)? = null,
    /** Color explícito, para indicadores con más de 3 niveles (VIX, Correlación) que
     *  necesitan más granularidad que la que da RiskLevel por sí solo. Si es null, se sigue
     *  usando riskLevel.toColor() como hasta ahora — no afecta a ninguna tarjeta existente. */
    overrideColor: Color? = null
) {
    val effectiveColor = overrideColor ?: riskLevel.toColor()
    val cardModifier = Modifier.fillMaxWidth()
    val cardColors = CardDefaults.cardColors(containerColor = effectiveColor.copy(alpha = 0.12f))
    val cardShape = RoundedCornerShape(12.dp)
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.height(40.dp),
                shape = RoundedCornerShape(20.dp),
                color = effectiveColor
            ) {
                Spacer(modifier = Modifier.padding(horizontal = 6.dp))
            }
            Spacer(modifier = Modifier.padding(start = 6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                Text(mainText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
            if (history.size >= 2) {
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Sparkline(values = history, color = effectiveColor)
            }
        }
    }

    // Solo las tarjetas con onClick (oro/bitcoin/ethereum) llevan el overload
    // "clicable" de Card (con ripple) — el resto se queda como Card normal,
    // sin dar la falsa impresión de que se puede tocar.
    if (onClick != null) {
        Card(onClick = onClick, modifier = cardModifier, shape = cardShape, colors = cardColors) { content() }
    } else {
        Card(modifier = cardModifier, shape = cardShape, colors = cardColors) { content() }
    }
}

/**
 * Velocímetro/semáforo horizontal de colores — pedido expresamente así para VIX y Correlación,
 * en vez de la gráfica con clic que tenían antes: de un solo vistazo, sin tocar nada, se ve en
 * qué zona de color está el valor actual y dónde cae exactamente dentro de ella (la marca
 * vertical). [zones] son las zonas de color de izquierda a derecha, cada una como (límite
 * SUPERIOR de esa zona, color) — la primera zona empieza en [displayMin]. [value] se recorta a
 * [displayMin]/[displayMax] para la posición de la marca, aunque el número mostrado aparte sea
 * el real (puede quedar fuera de este rango visual en casos extremos).
 */
@Composable
private fun HorizontalGauge(value: Double, displayMin: Double, displayMax: Double, zones: List<Pair<Double, Color>>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxWidth().height(18.dp)) {
        val barWidth = size.width
        val barHeight = size.height
        val range = (displayMax - displayMin).coerceAtLeast(0.0001)
        var zoneStart = displayMin
        val corner = androidx.compose.ui.geometry.CornerRadius(6f, 6f)
        for ((zoneEnd, color) in zones) {
            val startX = ((zoneStart - displayMin) / range * barWidth).toFloat().coerceIn(0f, barWidth)
            val endX = ((zoneEnd - displayMin) / range * barWidth).toFloat().coerceIn(0f, barWidth)
            if (endX > startX) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(startX, 0f),
                    size = androidx.compose.ui.geometry.Size(endX - startX, barHeight),
                    cornerRadius = corner
                )
            }
            zoneStart = zoneEnd
        }
        // Marca vertical blanca con borde oscuro — la posición exacta del valor actual.
        val markerX = (((value.coerceIn(displayMin, displayMax) - displayMin) / range) * barWidth).toFloat()
        drawLine(color = Color.Black, start = Offset(markerX, -4f), end = Offset(markerX, barHeight + 4f), strokeWidth = 6f)
        drawLine(color = Color.White, start = Offset(markerX, -2f), end = Offset(markerX, barHeight + 2f), strokeWidth = 3f)
    }
}

@Composable
private fun VolatilityCard(vixStatus: VixStatus?) {
    if (vixStatus == null) {
        IndicatorCard("Volatilidad (VIX)", RiskLevel.MEDIUM, "—", "Cargando…")
        return
    }
    val subtitle = if (vixStatus.isProxy) {
        "Proxy ${vixStatus.proxySymbol}: variación diaria ${"%.1f".format(vixStatus.changePercent)}%"
    } else {
        "${vixStatus.tierLabel}  ·  variación diaria ${"%.1f".format(vixStatus.changePercent)}%"
    }
    // Color propio de 5 niveles — más granular que los 3 de RiskLevel, para distinguir
    // "alarma alta" de "alarma extrema" visualmente, no solo por el texto.
    val vixColor = when {
        vixStatus.value < 12.0 -> Color(0xFF2196F3) // azul — complacencia extrema (calma anómala)
        vixStatus.value < 20.0 -> Color(0xFF2E7D32) // verde — normal, estable
        vixStatus.value < 30.0 -> Color(0xFFF9A825) // amarillo — alarma moderada
        vixStatus.value < 45.0 -> Color(0xFFE64A19) // naranja/rojo — alarma alta (pánico)
        else -> Color(0xFFB71C1C) // rojo oscuro — alarma extrema (crisis sistémica)
    }
    Column {
        IndicatorCard(
            title = "Volatilidad (VIX)",
            riskLevel = vixStatus.riskLevel,
            mainText = "%.2f".format(vixStatus.value),
            subtitle = subtitle,
            overrideColor = vixColor
        )
        HorizontalGauge(
            value = vixStatus.value,
            displayMin = 0.0,
            displayMax = 60.0,
            zones = listOf(
                12.0 to Color(0xFF2196F3),
                20.0 to Color(0xFF2E7D32),
                30.0 to Color(0xFFF9A825),
                45.0 to Color(0xFFE64A19),
                60.0 to Color(0xFFB71C1C)
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }
}

/**
 * Índice de correlación (^COR3M) — pedido expresamente así, justo debajo del VIX, para detectar
 * la dispersión entre acciones del S&P 500. Ver CorrelationAnalyzer para la tabla de rangos.
 *
 * SIN clic ni gráfica — pedido expresamente así, sustituido por el velocímetro horizontal de
 * HorizontalGauge, que muestra la zona de un vistazo sin tener que entrar a ningún sitio.
 */
@Composable
private fun CorrelationCard(correlationStatus: com.inversionadvisor.domain.model.CorrelationStatus?) {
    if (correlationStatus == null) {
        IndicatorCard("Correlación (dispersión S&P 500)", RiskLevel.MEDIUM, "—", "Cargando…")
        return
    }
    val value = correlationStatus.value
    // 5 colores propios, igual que el VIX — no encajan en los 3 niveles de RiskLevel.
    val correlationColor = when {
        value < 15.0 -> Color(0xFF2E7D32) // verde intenso — dispersión extrema, sana
        value < 30.0 -> Color(0xFF7CB342) // verde claro — dispersión alta, normal y saludable
        value < 45.0 -> Color(0xFFF9A825) // amarillo — zona de transición
        value < 65.0 -> Color(0xFFEF6C00) // naranja — correlación elevada, precaución
        else -> Color(0xFFC62828) // rojo — correlación muy alta, pánico/capitulación
    }
    val riskLevel = when {
        value < 30.0 -> RiskLevel.LOW
        value < 45.0 -> RiskLevel.MEDIUM
        else -> RiskLevel.HIGH
    }
    Column {
        IndicatorCard(
            title = "Correlación (dispersión S&P 500)",
            riskLevel = riskLevel,
            mainText = "%.1f".format(value),
            subtitle = "${correlationStatus.tierLabel}  ·  variación diaria ${"%.1f".format(correlationStatus.changePercent)}%",
            overrideColor = correlationColor
        )
        HorizontalGauge(
            value = value,
            displayMin = 0.0,
            displayMax = 100.0,
            zones = listOf(
                15.0 to Color(0xFF2E7D32),
                30.0 to Color(0xFF7CB342),
                45.0 to Color(0xFFF9A825),
                65.0 to Color(0xFFEF6C00),
                100.0 to Color(0xFFC62828)
            ),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun FearGreedCard(result: CnnFearGreedStatus?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = (result?.riskLevel ?: RiskLevel.MEDIUM).toColor().copy(alpha = 0.12f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Miedo y Codicia (CNN)", style = MaterialTheme.typography.labelLarge)
            if (result == null) {
                Spacer(modifier = Modifier.padding(top = 8.dp))
                Text("Cargando…", style = MaterialTheme.typography.bodyMedium)
            } else {
                FearGreedGauge(
                    score = result.score,
                    label = result.label,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * Medidor de media circunferencia (0-100) con aguja, al estilo del velocímetro
 * de un coche — igual que el gráfico de miedo/codicia de la propia web de CNN
 * (cnn.com/markets/fear-and-greed). El arco se pinta en 5 tramos de color fijos
 * (miedo extremo -> codicia extrema, mismos cortes que CNN: 25/45/55/75) y la
 * aguja gira según el score actual.
 */
@Composable
private fun FearGreedGauge(score: Int, label: String, modifier: Modifier = Modifier) {
    val zoneColors = listOf(
        Color(0xFFB71C1C), // miedo extremo
        Color(0xFFEF6C00), // miedo
        Color(0xFFFBC02D), // neutral
        Color(0xFF9CCC65), // codicia
        Color(0xFF2E7D32)  // codicia extrema
    )
    // Cortes EXACTOS pedidos expresamente: 0-25 / 26-44 / 45-55 / 56-75 / 76-100 — encontrado y
    // corregido: antes se dividían los 5 tramos en partes IGUALES de 20 puntos cada uno (180°/5),
    // que no coincidía con estos cortes reales — de ahí que una puntuación de 55 (Neutral, según
    // estos umbrales) pudiera caer visualmente en el tramo equivocado del arco.
    val zoneBoundaries = listOf(0, 25, 44, 55, 75, 100)
    val needleColor = MaterialTheme.colorScheme.onSurface

    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            val strokeWidth = 22.dp.toPx()
            val diameter = minOf(size.width, size.height * 2f) - strokeWidth
            val radius = diameter / 2f
            val center = Offset(size.width / 2f, size.height - strokeWidth / 2f)
            val arcTopLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = androidx.compose.ui.geometry.Size(diameter, diameter)

            // 5 tramos de ANCHO VARIABLE, cada uno proporcional a su rango de puntuación real
            // (25/19/11/20/25 puntos), no partes iguales — de 180° (izquierda, miedo extremo) a
            // 360°/0° (derecha, codicia extrema), semicírculo superior.
            var currentAngle = 180f
            zoneColors.forEachIndexed { index, zoneColor ->
                val zoneWidthPoints = zoneBoundaries[index + 1] - zoneBoundaries[index]
                val sweepForThisZone = (zoneWidthPoints / 100f) * 180f
                drawArc(
                    color = zoneColor,
                    startAngle = currentAngle,
                    sweepAngle = sweepForThisZone,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = androidx.compose.ui.graphics.StrokeCap.Butt)
                )
                currentAngle += sweepForThisZone
            }

            // Aguja: 180° = score 0 (izquierda), 0°/360° = score 100 (derecha).
            val needleAngleDeg = 180f - (score.coerceIn(0, 100) / 100f) * 180f
            val needleAngleRad = Math.toRadians(needleAngleDeg.toDouble())
            val needleLength = radius - strokeWidth * 0.3f
            val needleEnd = Offset(
                x = center.x + (needleLength * kotlin.math.cos(needleAngleRad)).toFloat(),
                y = center.y - (needleLength * kotlin.math.sin(needleAngleRad)).toFloat()
            )
            drawLine(
                color = needleColor,
                start = center,
                end = needleEnd,
                strokeWidth = 5.dp.toPx(),
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawCircle(color = needleColor, radius = 7.dp.toPx(), center = center)
        }
        Text("$score/100", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AaiiSentimentCard(sentiment: AaiiSentiment?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = (sentiment?.riskLevel ?: RiskLevel.MEDIUM).toColor().copy(alpha = 0.12f)
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    modifier = Modifier.height(32.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = (sentiment?.riskLevel ?: RiskLevel.MEDIUM).toColor()
                ) {
                    Spacer(modifier = Modifier.padding(horizontal = 5.dp))
                }
                Spacer(modifier = Modifier.padding(start = 8.dp))
                Text("Alcistas / Bajistas (AAII)", style = MaterialTheme.typography.labelLarge)
            }
            if (sentiment == null) {
                Spacer(modifier = Modifier.padding(top = 8.dp))
                Text("Cargando…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(
                    "Actualizado ${sentiment.reportedDateLabel}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                )
                BullBearBarChart(
                    bullishPercent = sentiment.bullishPercent,
                    neutralPercent = sentiment.neutralPercent,
                    bearishPercent = sentiment.bearishPercent
                )
            }
        }
    }
}

/** Una única barra dividida en 3 segmentos de color (alcista verde, neutral gris, bajista rojo), con la leyenda debajo. */
@Composable
private fun BullBearBarChart(bullishPercent: Double, neutralPercent: Double, bearishPercent: Double) {
    val bullColor = Color(0xFF2E7D32)
    val neutralColor = Color(0xFF9E9E9E)
    val bearColor = Color(0xFFC62828)
    val total = (bullishPercent + neutralPercent + bearishPercent).takeIf { it > 0.0 } ?: 1.0

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .clip(RoundedCornerShape(6.dp))
        ) {
            if (bullishPercent > 0.0) {
                Box(modifier = Modifier.weight((bullishPercent / total).toFloat()).fillMaxHeight().background(bullColor))
            }
            if (neutralPercent > 0.0) {
                Box(modifier = Modifier.weight((neutralPercent / total).toFloat()).fillMaxHeight().background(neutralColor))
            }
            if (bearishPercent > 0.0) {
                Box(modifier = Modifier.weight((bearishPercent / total).toFloat()).fillMaxHeight().background(bearColor))
            }
        }
        Spacer(modifier = Modifier.padding(top = 8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SentimentLegendItem("Alcista", bullishPercent, bullColor)
            SentimentLegendItem("Neutral", neutralPercent, neutralColor)
            SentimentLegendItem("Bajista", bearishPercent, bearColor)
        }
    }
}

@Composable
private fun SentimentLegendItem(label: String, percent: Double, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.padding(start = 4.dp))
        Text(
            "$label ${"%.0f".format(percent)}%",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun GoldCard(
    goldQuote: Quote?,
    displayCurrency: DisplayCurrency,
    ratesToUsd: Map<Currency, Double>,
    history: List<Float>,
    onClick: () -> Unit
) {
    if (goldQuote == null) {
        IndicatorCard("Oro (XAU/USD)", RiskLevel.MEDIUM, "—", "Cargando…", onClick = onClick)
        return
    }
    val convertedPerOz = goldQuote.closeIn(displayCurrency.currency, ratesToUsd) ?: goldQuote.close
    val currencySymbol = displayCurrency.currency.symbol
    val perOzText = "$currencySymbol%.2f / oz".format(convertedPerOz)
    val perKgText = "$currencySymbol%.2f / kg".format(convertedPerOz * GoldConversion.TROY_OUNCES_PER_KG)
    val risk = if (goldQuote.changePercent >= 0) RiskLevel.LOW else RiskLevel.MEDIUM
    IndicatorCard(
        title = "Oro",
        riskLevel = risk,
        mainText = perOzText,
        subtitle = "$perKgText  ·  variación diaria ${"%.2f".format(goldQuote.changePercent)}%",
        history = history,
        onClick = onClick
    )
}

@Composable
private fun CryptoCard(
    name: String,
    quote: Quote?,
    displayCurrency: DisplayCurrency,
    ratesToUsd: Map<Currency, Double>,
    history: List<Float>,
    onClick: () -> Unit
) {
    if (quote == null) {
        IndicatorCard(name, RiskLevel.MEDIUM, "—", "Cargando…", onClick = onClick)
        return
    }
    val converted = quote.closeIn(displayCurrency.currency, ratesToUsd) ?: quote.close
    val currencySymbol = displayCurrency.currency.symbol
    val risk = if (quote.changePercent >= 0) RiskLevel.LOW else RiskLevel.MEDIUM
    IndicatorCard(
        title = name,
        riskLevel = risk,
        mainText = "$currencySymbol%.2f".format(converted),
        subtitle = "Variación 24h ${"%.2f".format(quote.changePercent)}%",
        history = history,
        onClick = onClick
    )
}
