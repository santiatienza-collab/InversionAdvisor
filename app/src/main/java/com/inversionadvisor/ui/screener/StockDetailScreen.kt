package com.inversionadvisor.ui.screener

import android.graphics.Color as AndroidColor
import android.view.MotionEvent
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter
import com.github.mikephil.charting.listener.ChartTouchListener
import com.github.mikephil.charting.listener.OnChartGestureListener
import com.inversionadvisor.data.repository.MarketRepository
import com.inversionadvisor.domain.indicators.BuyOpportunityAnalyzer
import com.inversionadvisor.domain.indicators.SellTimingAssessment
import com.inversionadvisor.domain.indicators.SellTimingFactor
import com.inversionadvisor.domain.model.Candle
import com.inversionadvisor.domain.model.ChartRange
import com.inversionadvisor.domain.model.RiskLevel
import com.inversionadvisor.ui.common.SectionHeader
import com.inversionadvisor.ui.common.BlinkingNeonBadge
import com.inversionadvisor.ui.common.toColor
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pantalla de detalle de un activo (stock del screener, oro, bitcoin,
 * ethereum...): gráfica de precio + SMA de 10 periodos con panel de
 * volumen sincronizado en el eje X (pan/zoom conjunto, estilo
 * TradingView). El usuario puede cambiar el rango temporal con las
 * pestañas de arriba — [ranges] decide cuáles se ofrecen (por defecto
 * 1S/1M/1A/5A, usado tanto por el screener como por oro/bitcoin/ethereum
 * en el panel).
 */
@Composable
fun StockDetailScreen(
    symbol: String,
    marketRepository: MarketRepository,
    onBack: () -> Unit,
    displayTitle: String = symbol,
    /** Nombre del universo al que pertenece el stock (p. ej. "S&P 500", "Nasdaq-100", "IBEX 35")
     *  — pedido expresamente para mostrarlo junto al ticker. null si no aplica (oro/bitcoin/etc.,
     *  que no pertenecen a ningún índice de estos). */
    indexDisplayName: String? = null,
    ranges: List<ChartRange> = DETAIL_CHART_RANGES,
    /** "Momento idóneo para la venta" solo tiene sentido para stocks del screener, no para oro/bitcoin/ethereum del panel. */
    showSellTiming: Boolean = true,
    /** Estrellita de favoritos: null = no se muestra (p.ej. desde Análisis u oro/bitcoin/ethereum). */
    isFavorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null,
    /** Panel de volumen bajo el precio — false para índices sin volumen real (p. ej. el VIX,
     *  pedido expresamente así: el volumen de un índice de volatilidad no aporta nada). */
    showVolume: Boolean = true
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: StockDetailViewModel = viewModel(
        key = "stock_detail_$symbol",
        factory = StockDetailViewModel.Factory(
            symbol = symbol,
            marketRepository = marketRepository,
            // oro/bitcoin/ethereum (showSellTiming=false) no tienen sector ni PER, no hace falta.
            stockUniverseRepository = if (showSellTiming) {
                com.inversionadvisor.di.ServiceLocator.provideStockUniverseRepository(context)
            } else {
                null
            }
        )
    )
    val state by viewModel.uiState.collectAsState()

    // NUEVO — pedido expresamente: mismo azul índigo + sombra gris en toda la ficha (nombre del
    // stock Y las pestañas de rango del gráfico) — antes estaba definido solo dentro del bloque
    // del título, sin alcance para la pestaña de rango de más abajo.
    val colorIndigo = Color(0xFF3F51B5)
    val sombraGris = androidx.compose.ui.graphics.Shadow(
        color = Color.Gray,
        offset = androidx.compose.ui.geometry.Offset(1.5f, 1.5f),
        blurRadius = 3f
    )

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "Volver")
            }
            Column(modifier = Modifier.weight(1f)) {
                // NUEVO — pedido expresamente: antes displayTitle era una sola cadena larga
                // ("TICKER — Nombre completo") en un único Text a tamaño titleLarge, que quedaba
                // amontonado y se recortaba mal con nombres largos. Ahora, si viene con ese
                // separador (caso de un stock normal — Búsqueda/Screener), se parte en dos líneas
                // distintas: el ticker arriba (grande, negrita) y el nombre completo debajo (más
                // pequeño, una sola línea con "..." si no cabe) — ambos en azul oscuro. Si NO
                // trae el separador (caso de oro/bitcoin/ethereum desde el Dashboard, que ya
                // traen su propio texto descriptivo autoconclusivo), se muestra tal cual, como
                // antes, sin forzar el partido en dos líneas.
                val separador = " — "
                val indiceSeparador = displayTitle.indexOf(separador)
                if (indiceSeparador > 0) {
                    val ticker = displayTitle.substring(0, indiceSeparador)
                    val nombre = displayTitle.substring(indiceSeparador + separador.length)
                    // REORDENADO a petición expresa: el nombre de la empresa va ARRIBA (grande,
                    // negrita) y el ticker ABAJO (pequeño) — antes era al revés. Los dos en azul
                    // índigo con una sombra gris discreta.
                    Text(
                        nombre,
                        style = MaterialTheme.typography.titleLarge.copy(shadow = sombraGris),
                        fontWeight = FontWeight.Bold,
                        color = colorIndigo,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ticker,
                            style = MaterialTheme.typography.bodyMedium.copy(shadow = sombraGris),
                            color = colorIndigo,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                        if (indexDisplayName != null) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = colorIndigo.copy(alpha = 0.12f),
                                modifier = Modifier.padding(start = 6.dp)
                            ) {
                                Text(
                                    indexDisplayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colorIndigo,
                                    maxLines = 1,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                } else {
                    Text(
                        displayTitle,
                        style = MaterialTheme.typography.titleLarge.copy(shadow = sombraGris),
                        fontWeight = FontWeight.Bold,
                        color = colorIndigo,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            if (isFavorite != null && onToggleFavorite != null) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        Icons.Filled.Star,
                        contentDescription = if (isFavorite) "Quitar de favoritos" else "Guardar en favoritos",
                        tint = if (isFavorite) Color(0xFFFFB300) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                    )
                }
            }
        }

        TabRow(selectedTabIndex = ranges.indexOf(state.selectedRange).coerceAtLeast(0)) {
            ranges.forEach { range ->
                Tab(
                    selected = state.selectedRange == range,
                    onClick = { viewModel.selectRange(range) },
                    text = {
                        Text(
                            range.label,
                            style = MaterialTheme.typography.bodyMedium.copy(shadow = sombraGris),
                            color = colorIndigo,
                            fontWeight = if (state.selectedRange == range) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }
        }

        state.error?.let { error ->
            // Antes este aviso solo se mostraba cuando NO había ningún dato en absoluto
            // (state.candles.isEmpty()) — si quedaba un gráfico ANTIGUO en caché de un
            // intento anterior (aunque fuera de antes de estos arreglos), el error se
            // ocultaba del todo detrás del gráfico viejo y no había forma de verlo. Ahora
            // se muestra siempre que el último refresco haya fallado, haya o no datos
            // (nuevos o antiguos) que enseñar debajo.
            Text(
                "No se ha podido actualizar: $error",
                color = Color(0xFFC62828),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }

        when {
            state.isLoading && state.candles.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                }
            }
            state.candles.size < 2 -> {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.error != null) {
                            "No hay gráfica que mostrar (ver aviso en rojo arriba)."
                        } else {
                            "Sin datos suficientes todavía para pintar la gráfica."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            else -> {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    PriceAndVolumeChart(
                        candles = state.candles,
                        sma20 = state.sma20,
                        range = state.selectedRange,
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        sma50 = state.sma50,
                        relativeStrengthVsSpyPercent = state.relativeStrengthVsSpyPercent,
                        yesterdayClose = state.yesterdayClose,
                        todayLivePrice = state.todayLivePrice,
                        regularSessionBounds = state.regularSessionBounds,
                        isShowingYesterdayFallback = state.isShowingYesterdayFallback,
                        showVolume = showVolume,
                        doubleTopBottomResult = state.buyOpportunityAnalysis?.doubleTopBottomResult,
                        tripleTopBottomResult = state.buyOpportunityAnalysis?.tripleTopBottomResult
                    )
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                        if (showSellTiming) {
                            Column(modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                                CompanySnapshotCard(
                                    symbol = symbol,
                                    price = state.candles.lastOrNull()?.close,
                                    marketCap = state.marketCap,
                                    netIncomeCurrentYear = state.netIncomeCurrentYear,
                                    netIncomePreviousYear = state.netIncomePreviousYear,
                                    netIncomeTwoYearsAgo = state.netIncomeTwoYearsAgo,
                                    nextEarningsDateLabel = state.nextEarningsDateLabel
                                )
                            }
                            SectionHeader("Análisis de opciones de compra")
                            Column(modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)) {
                                // NUEVO — pedido expresamente: antes se pasaba state.buyOpportunityAnalysis
                                // directo, y como se recalcula varias veces según van llegando los
                                // datos (velas, PER, patrones, HCH...), el número iba "saltando"
                                // delante del usuario. Ahora se espera a que la señal deje de
                                // cambiar durante 400ms antes de mostrarla — mientras tanto, se ve
                                // "Calculando…" en vez de números intermedios que luego cambian.
                                val analisisEstable = state.buyOpportunityAnalysis
                                    .let { actual -> produceState<com.inversionadvisor.domain.indicators.BuyOpportunityAnalyzer.Analysis?>(null, actual) {
                                        value = null
                                        kotlinx.coroutines.delay(400)
                                        value = actual
                                    } }
                                BuyOpportunityCard(analisisEstable.value)
                            }
                            SectionHeader("Momento idóneo para la venta")
                            Column(modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
                                val sellTimingEstable = state.sellTiming
                                    .let { actual -> produceState<SellTimingAssessment?>(null, actual) {
                                        value = null
                                        kotlinx.coroutines.delay(400)
                                        value = actual
                                    } }
                                SellTimingCard(sellTimingEstable.value)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Punto de color de la leyenda propia de PriceAndVolumeChart — "dimmed" (atenuado) cuando esa
 *  serie está oculta, para que se note visualmente que está desactivada. */
@Composable
private fun LegendDot(color: Color, dimmed: Boolean = false) {
    Surface(
        modifier = Modifier.size(10.dp),
        shape = RoundedCornerShape(50),
        color = if (dimmed) color.copy(alpha = 0.3f) else color
    ) {}
}

@Composable
internal fun PriceAndVolumeChart(candles: List<Candle>, sma20: List<Double?>, range: ChartRange, modifier: Modifier = Modifier, showVolume: Boolean = true, priceChartHeightDp: Int = 260, sma50: List<Double?> = emptyList(), relativeStrengthVsSpyPercent: Double? = null, sma50InitiallyVisible: Boolean = true, yesterdayClose: Double? = null, todayLivePrice: Double? = null, isShowingYesterdayFallback: Boolean = false, doubleTopBottomResult: com.inversionadvisor.domain.indicators.DoubleTopBottomResult? = null, tripleTopBottomResult: com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomResult? = null, regularSessionBounds: Pair<Long, Long>? = null) {
    val labels = remember(candles, range) { candles.map { it.chartLabel(range) } }

    val priceColor = MaterialTheme.colorScheme.primary.toArgb()
    val smaColor = AndroidColor.parseColor("#FF6D00")
    val sma50Color = AndroidColor.parseColor("#00BCD4") // azul cian, distinto de la SMA20 (naranja), del precio y de las velas alcistas/bajistas (verde/rojo)
    val upColor = AndroidColor.parseColor("#2E7D32")
    val downColor = AndroidColor.parseColor("#C62828")
    val gridColor = AndroidColor.parseColor("#E0E0E0")
    val labelColor = AndroidColor.parseColor("#616161")
    // AÑADIDO — horario extendido (pre-market/after-hours) del gráfico 1D, pedido expresamente
    // así: gris apagado, neutro, para que se note a simple vista que esas velas son fuera de la
    // sesión regular (precio más "saltarín", menos fiable, volumen normalmente mucho menor) sin
    // mezclarlo con los colores de subida/bajada normales.
    val extendedHoursColor = AndroidColor.parseColor("#B0BEC5")

    /**
     * true si esta vela cae FUERA de la sesión regular (pre-market o after-hours) — solo tiene
     * sentido para 1 DÍA y cuando se conocen los límites reales de la sesión (ver
     * MarketRepository.observeRegularSessionBounds); en cualquier otro caso, siempre false (no
     * hay horario extendido que distinguir en 1S/1M/6M/1A/5A, y sin los límites no hay forma de
     * saber dónde empieza/acaba la sesión regular de ESTE símbolo en concreto).
     *
     * EXCLUIDO el modo respaldo (isShowingYesterdayFallback): ahí las velas son de AYER, pero
     * regularSessionBounds refleja la sesión de HOY (la del intento de petición que falló y
     * llevó al respaldo) — comparar unas contra otra pintaría TODA la sesión de respaldo como
     * "fuera de horario" por error, ya que ninguna vela de ayer caería dentro de la ventana de
     * hoy.
     */
    fun isExtendedHours(candle: Candle): Boolean {
        if (range != ChartRange.ONE_DAY || regularSessionBounds == null || isShowingYesterdayFallback) return false
        val epochSecond = try { java.time.Instant.parse(candle.datetime).epochSecond } catch (e: Exception) { return false }
        return epochSecond < regularSessionBounds.first || epochSecond > regularSessionBounds.second
    }

    // Visibilidad de cada media, controlada por la leyenda propia de abajo (clicable) — la
    // leyenda NATIVA de MPAndroidChart no admite clic para ocultar/mostrar series, así que se
    // desactiva (legend.isEnabled = false más abajo) y se sustituye por esta fila de Compose.
    var sma20Visible by remember(candles) { mutableStateOf(true) }
    // Visibilidad inicial configurable — pedido expresamente así para índices/divisas/bonos:
    // la SMA50 empieza DESACTIVADA ahí (sma50InitiallyVisible=false), y solo se activa
    // tocando la leyenda. En la ficha de un stock se queda con el valor por defecto (true,
    // visible desde el principio, sin cambios de comportamiento ahí).
    var sma50Visible by remember(candles) { mutableStateOf(sma50InitiallyVisible) }

    // Valor actual (última vela del rango cargado), en verde si ha subido o en rojo si ha
    // bajado FRENTE A LA PRIMERA VELA DE ESE MISMO RANGO (la variación del rango temporal
    // seleccionado, no la del día). Se pinta como cabecera ANTES de la gráfica, no encima —
    // se pidió así para que no se pise con las velas ni la leyenda del gráfico. Pedido para
    // todas las gráficas de la app: al vivir aquí, en el componente compartido, se aplica a la
    // vez a la ficha de cada stock, los índices de mercado, Divisas y Bonos USA.
    //
    // EXCEPCIÓN para el rango de 1 DÍA: si se pasa yesterdayClose Y NO se está en modo
    // respaldo (isShowingYesterdayFallback), se usa yesterdayClose como referencia (el cierre
    // de AYER) en vez de la primera vela de hoy (la apertura) — pedido expresamente así, para
    // que la variación coincida con la que muestra el banner de ganadores/perdedores.
    // Encontrado con CrowdStrike: un valor que abre con un hueco grande (tras resultados, por
    // ejemplo) mostraba dos números distintos según de dónde se mirara.
    //
    // EN MODO RESPALDO (candles ya son de AYER, no de hoy — ver candlesFlow en el ViewModel):
    // yesterdayClose ya NO tiene sentido como referencia (sería comparar el cierre de ayer
    // contra sí mismo, dando ~0%) — se vuelve a la primera vela del propio respaldo (la
    // apertura de AYER), para mostrar "cómo fue la sesión de ayer" en vez de un 0% falso.
    //
    // CORREGIDO — el fallo real: currentValue y firstValue se calculaban por SEPARADO, cada
    // uno con su propia condición. Si todayLivePrice fallaba (p. ej. un timeout puntual) pero
    // yesterdayClose SÍ había llegado bien, currentValue caía a la vela vieja cacheada MIENTRAS
    // firstValue seguía usando el cierre de ayer recién pedido — mezclando un valor actual
    // desfasado con una referencia fresca, justo la combinación que puede dar un % con el
    // signo equivocado (una vela de hace rato puede estar por debajo de ayer aunque el precio
    // en vivo de verdad ya esté por encima). Ahora currentValue y firstValue se deciden JUNTOS,
    // como una pareja: o los DOS son en vivo (todayLivePrice + yesterdayClose, cuando ambos han
    // llegado bien), o los DOS son de las velas cacheadas (candles.first/last) — nunca se mezcla
    // uno en vivo con otro de caché.
    val useLiveTodayReference = range == ChartRange.ONE_DAY && todayLivePrice != null && yesterdayClose != null && !isShowingYesterdayFallback
    val currentValue = if (useLiveTodayReference) todayLivePrice else candles.lastOrNull()?.close
    val firstValue = if (useLiveTodayReference) {
        yesterdayClose
    } else {
        candles.firstOrNull()?.close
    }
    val isUp = if (currentValue != null && firstValue != null) currentValue >= firstValue else true
    val valueColor = if (isUp) Color(0xFF2E7D32) else Color(0xFFC62828)
    val percentChange = if (currentValue != null && firstValue != null && firstValue != 0.0) {
        (currentValue - firstValue) / firstValue * 100
    } else null

    Column(modifier = modifier) {
        // Aviso de que se está mostrando el respaldo de "ayer" — pedido expresamente así, para
        // no dar a entender que es la sesión de hoy en vivo cuando en realidad no se pudieron
        // cargar datos frescos de hoy.
        if (range == ChartRange.ONE_DAY && isShowingYesterdayFallback) {
            Text(
                "Sin datos de hoy todavía — mostrando la sesión de ayer",
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFEF6C00),
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        // Cabecera propia ANTES de la gráfica (no encima, para que no se pise con las velas ni
        // con la leyenda) — valor + variación del rango + flechita, alineado a la izquierda,
        // como parte integrada de la tarjeta, no flotando encima del dibujo.
        if (currentValue != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                Text(
                    "%.2f".format(currentValue),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = valueColor
                )
                percentChange?.let {
                    Text(
                        "${if (isUp) "▲" else "▼"} ${if (it >= 0) "+" else ""}${"%.2f".format(it)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = valueColor,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }

        // Fuerza relativa REAL frente al S&P 500 en este mismo rango — "si tenemos el dato"
        // (null para oro/cripto/divisas/bonos, o si aún no hay suficientes velas). NO es el "RS
        // Rating" de Investor's Business Daily (esa es una métrica propia y registrada de IBD,
        // que compara cada stock contra miles de valores — no existe ninguna fuente gratuita de
        // ese dato exacto); esto es el cálculo real de este stock frente al S&P 500.
        relativeStrengthVsSpyPercent?.let { rs ->
            val rsColor = if (rs >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
            Text(
                "Fuerza relativa vs S&P 500: ${if (rs >= 0) "+" else ""}${"%.1f".format(rs)} pp",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = rsColor,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }

        // Leyenda propia, clicable — sustituye a la leyenda nativa de MPAndroidChart (que no
        // admite clic). Cada entrada muestra su color y, al tocarla, oculta/muestra esa serie
        // en la gráfica. "Precio" no es clicable (siempre visible, es la serie principal).
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
            LegendDot(color = Color(priceColor))
            Text("Precio", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 4.dp, end = 12.dp))
            if (sma20.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { sma20Visible = !sma20Visible }
                ) {
                    LegendDot(color = Color(smaColor), dimmed = !sma20Visible)
                    Text(
                        "SMA 20",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sma20Visible) Color.Unspecified else Color(0xFF9E9E9E),
                        modifier = Modifier.padding(start = 4.dp, end = 12.dp)
                    )
                }
            }
            if (sma50.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { sma50Visible = !sma50Visible }
                ) {
                    LegendDot(color = Color(sma50Color), dimmed = !sma50Visible)
                    Text(
                        "SMA 50",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (sma50Visible) Color.Unspecified else Color(0xFF9E9E9E),
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }

        AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL

                val priceChart = LineChart(ctx).apply {
                    tag = "priceChart"
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(ctx, priceChartHeightDp))
                    description.isEnabled = false
                    legend.isEnabled = false // leyenda propia de Compose de arriba, esta se desactiva
                    axisRight.isEnabled = false
                    axisLeft.gridColor = gridColor
                    axisLeft.textColor = labelColor
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    xAxis.granularity = 1f
                    xAxis.setDrawGridLines(false)
                    xAxis.textColor = labelColor
                    xAxis.labelRotationAngle = -45f
                    setScaleYEnabled(false)
                    setPinchZoom(false)
                }

                val volumeChart = BarChart(ctx).apply {
                    tag = "volumeChart"
                    // showVolume=false (Bonos USA: ^TNX/^TYX/^MOVE no tienen datos de volumen
                    // reales) — altura 0 en vez de quitar la vista, así el resto del código
                    // (findViewWithTag, el sincronizador de ejes, el listener de gestos) sigue
                    // funcionando igual sin tener que comprobar nulos por todas partes.
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, if (showVolume) dpToPx(ctx, 120) else 0)
                    description.isEnabled = false
                    legend.isEnabled = false
                    axisRight.isEnabled = false
                    axisLeft.gridColor = gridColor
                    axisLeft.textColor = labelColor
                    axisLeft.axisMinimum = 0f
                    xAxis.position = XAxis.XAxisPosition.BOTTOM
                    xAxis.granularity = 1f
                    xAxis.setDrawGridLines(false)
                    xAxis.textColor = labelColor
                    xAxis.labelRotationAngle = -45f
                    setScaleYEnabled(false)
                    setPinchZoom(false)
                    // El panel de volumen no se toca directamente: sigue el pan/zoom
                    // del panel de precio (ver ChartXAxisSync), como en TradingView.
                    setTouchEnabled(false)
                }

                priceChart.onChartGestureListener = object : OnChartGestureListener {
                    override fun onChartGestureStart(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {}
                    override fun onChartGestureEnd(me: MotionEvent?, lastPerformedGesture: ChartTouchListener.ChartGesture?) {
                        ChartXAxisSync.syncXAxis(priceChart, volumeChart)
                    }
                    override fun onChartLongPressed(me: MotionEvent?) {}
                    override fun onChartDoubleTapped(me: MotionEvent?) {}
                    override fun onChartSingleTapped(me: MotionEvent?) {}
                    override fun onChartFling(me1: MotionEvent?, me2: MotionEvent?, velocityX: Float, velocityY: Float) {}
                    override fun onChartScale(me: MotionEvent?, scaleX: Float, scaleY: Float) {
                        ChartXAxisSync.syncXAxis(priceChart, volumeChart)
                    }
                    override fun onChartTranslate(me: MotionEvent?, dX: Float, dY: Float) {
                        ChartXAxisSync.syncXAxis(priceChart, volumeChart)
                    }
                }

                addView(priceChart)
                addView(volumeChart)
            }
        },
        update = { root ->
            val priceChart = root.findViewWithTag<LineChart>("priceChart")
            val volumeChart = root.findViewWithTag<BarChart>("volumeChart")

            val priceEntries = candles.mapIndexed { i, c -> Entry(i.toFloat(), c.close.toFloat()) }
            val priceSet = LineDataSet(priceEntries, "Precio").apply {
                // CAMBIADO — colors (por segmento) en vez de color (uno solo) para poder pintar
                // de gris apagado los tramos de horario extendido (ver isExtendedHours arriba),
                // pedido expresamente así. Fuera de 1 DÍA (o sin límites de sesión conocidos),
                // isExtendedHours siempre da false, así que esto no cambia nada en el resto de
                // rangos — se queda con el color normal de siempre en todos los puntos.
                colors = candles.map { if (isExtendedHours(it)) extendedHoursColor else priceColor }
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 2f
            }

            val smaEntries = if (sma20Visible) sma20.mapIndexedNotNull { i, v -> v?.let { Entry(i.toFloat(), it.toFloat()) } } else emptyList()
            val smaSet = LineDataSet(smaEntries, "SMA 20").apply {
                color = smaColor
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 2f
            }

            val sma50Entries = if (sma50Visible) sma50.mapIndexedNotNull { i, v -> v?.let { Entry(i.toFloat(), it.toFloat()) } } else emptyList()
            val sma50Set = LineDataSet(sma50Entries, "SMA 50").apply {
                color = sma50Color
                setDrawCircles(false)
                setDrawValues(false)
                lineWidth = 2f
            }

            // Círculos del doble techo/suelo — pedido expresamente así: dos círculos (rojos
            // para doble techo, verdes para doble suelo) en las velas EXACTAS donde se
            // detectaron los dos picos/valles. El patrón se detecta sobre las velas SEMANALES
            // de 1 año (las mismas que usa toda la fórmula), así que aquí se busca, dentro de
            // las velas que se están mostrando AHORA en este gráfico, cuál es la más cercana a
            // cada una de esas dos fechas — si el usuario está viendo un rango muy distinto
            // (p. ej. 1 día) donde esas fechas ni siquiera aparecen, sencillamente no se dibuja
            // nada (lista vacía), en vez de arriesgarse a marcar una vela equivocada.
            val doubleTopBottomEntries = mutableListOf<Entry>()
            val doubleTopBottomWickEntries1 = mutableListOf<Entry>()
            val doubleTopBottomWickEntries2 = mutableListOf<Entry>()
            var doubleTopBottomIsTop = true
            if (doubleTopBottomResult != null && candles.isNotEmpty()) {
                val esTop = doubleTopBottomResult.pattern == com.inversionadvisor.domain.indicators.DoubleTopBottomPattern.DOUBLE_TOP
                fun closestIndexTo(isoDate: String, targetPrice: Double?): Int? {
                    val target = try { java.time.Instant.parse(isoDate) } catch (e: Exception) { return null }
                    var bestIndex = -1
                    var bestDiffMillis = Long.MAX_VALUE
                    candles.forEachIndexed { i, c ->
                        val candleInstant = try { java.time.Instant.parse(c.datetime) } catch (e: Exception) { return@forEachIndexed }
                        val diff = kotlin.math.abs(java.time.Duration.between(target, candleInstant).toMillis())
                        if (diff < bestDiffMillis) {
                            bestDiffMillis = diff
                            bestIndex = i
                        }
                    }
                    // Margen de 5 días — si la vela más cercana en ESTE gráfico está más lejos
                    // que eso de la fecha real del patrón, es que este rango no es compatible
                    // (p. ej. viendo 1 semana mientras el patrón es de hace meses).
                    if (bestIndex < 0 || bestDiffMillis > 5L * 24 * 60 * 60 * 1000) return null

                    // NUEVO — pedido expresamente, señalización poco precisa: el patrón se
                    // detecta sobre velas SEMANALES (cuya fecha es el LUNES de esa semana), pero
                    // el máximo/mínimo real pudo darse cualquier día de esa semana. Si el gráfico
                    // muestra velas de más resolución (diarias), quedarse solo con "la más
                    // cercana en fecha" podía marcar un día distinto al del pico/valle real.
                    // Ahora, dentro de una ventana de ±4 velas alrededor de esa fecha, se afina
                    // buscando la vela cuyo máximo (techo) o mínimo (suelo) esté más cerca del
                    // precio real del patrón — esa es la vela donde de verdad ocurrió el extremo.
                    if (targetPrice == null || targetPrice <= 0.0) return bestIndex
                    val ventanaInicio = (bestIndex - 4).coerceAtLeast(0)
                    val ventanaFin = (bestIndex + 4).coerceAtMost(candles.size - 1)
                    var mejorIndice = bestIndex
                    var mejorDiferenciaPrecio = Double.MAX_VALUE
                    for (i in ventanaInicio..ventanaFin) {
                        val valorVela = if (esTop) candles[i].high else candles[i].low
                        val diferencia = kotlin.math.abs(valorVela - targetPrice)
                        if (diferencia < mejorDiferenciaPrecio) {
                            mejorDiferenciaPrecio = diferencia
                            mejorIndice = i
                        }
                    }
                    return mejorIndice
                }
                val idx1 = doubleTopBottomResult.firstDate?.let { closestIndexTo(it, doubleTopBottomResult.firstPrice) }
                val idx2 = doubleTopBottomResult.secondDate?.let { closestIndexTo(it, doubleTopBottomResult.secondPrice) }
                val price1 = doubleTopBottomResult.firstPrice
                val price2 = doubleTopBottomResult.secondPrice
                if (idx1 != null && idx2 != null && price1 != null && price2 != null) {
                    doubleTopBottomEntries += Entry(idx1.toFloat(), price1.toFloat())
                    doubleTopBottomEntries += Entry(idx2.toFloat(), price2.toFloat())
                    doubleTopBottomIsTop = esTop
                    // NUEVO — pedido expresamente: como esta gráfica es una línea de solo
                    // cierres (sin mechas visibles), un máximo/mínimo real bastante distinto del
                    // cierre de esa semana (una mecha larga y momentánea) hacía que el círculo
                    // pareciera "flotar" fuera de la línea, dando sensación de error aunque el
                    // dato fuera correcto. Estos dos segmentos verticales finos (cierre → círculo)
                    // dejan claro de un vistazo que es una mecha, no un desajuste.
                    doubleTopBottomWickEntries1 += Entry(idx1.toFloat(), candles[idx1].close.toFloat())
                    doubleTopBottomWickEntries1 += Entry(idx1.toFloat(), price1.toFloat())
                    doubleTopBottomWickEntries2 += Entry(idx2.toFloat(), candles[idx2].close.toFloat())
                    doubleTopBottomWickEntries2 += Entry(idx2.toFloat(), price2.toFloat())
                }
            }
            val doubleTopBottomSet = LineDataSet(doubleTopBottomEntries, "Patrón").apply {
                val markerColor = if (doubleTopBottomIsTop) AndroidColor.parseColor("#C62828") else AndroidColor.parseColor("#2E7D32")
                color = AndroidColor.TRANSPARENT // sin línea conectando los dos puntos, solo los círculos
                lineWidth = 0f
                setDrawCircles(true)
                setCircleColor(markerColor)
                circleRadius = 7f
                setDrawCircleHole(true)
                circleHoleColor = AndroidColor.WHITE
                circleHoleRadius = 3f
                setDrawValues(false)
            }
            // NUEVO — las dos "mechas" finas (cierre → máximo/mínimo real) del patrón, ver
            // comentario más arriba. Un LineDataSet por punto para que cada segmento vertical
            // sea independiente (si fueran los 4 puntos en un mismo dataset, se dibujaría
            // también una línea diagonal no deseada entre el primer y el segundo punto).
            fun wickDataSet(entries: List<Entry>): LineDataSet = LineDataSet(entries, "").apply {
                val markerColor = if (doubleTopBottomIsTop) AndroidColor.parseColor("#C62828") else AndroidColor.parseColor("#2E7D32")
                color = markerColor
                lineWidth = 1.5f
                setDrawCircles(false)
                setDrawValues(false)
                setDrawFilled(false)
                mode = LineDataSet.Mode.LINEAR
                // NUEVO — línea SÓLIDA en vez de discontinua: enableDashedLine puede no
                // renderizarse en vistas con aceleración por hardware (limitación conocida de
                // DashPathEffect en Android), que es probablemente por lo que no se veía nada.
            }
            val doubleTopBottomWickSet1 = wickDataSet(doubleTopBottomWickEntries1)
            val doubleTopBottomWickSet2 = wickDataSet(doubleTopBottomWickEntries2)

            // NUEVO — pedido expresamente: el triple techo/suelo NUNCA se dibujaba (fallo real
            // detectado: "solo pintan dos círculos, tienen que pintar tres" — esos dos eran los
            // del doble techo/suelo, que puede coincidir a la vez). Misma lógica que el doble de
            // arriba, con un tercer punto y su propia mecha.
            val tripleTopBottomEntries = mutableListOf<Entry>()
            val tripleTopBottomWickEntries1 = mutableListOf<Entry>()
            val tripleTopBottomWickEntries2 = mutableListOf<Entry>()
            val tripleTopBottomWickEntries3 = mutableListOf<Entry>()
            var tripleTopBottomIsTop = true
            if (tripleTopBottomResult != null && candles.isNotEmpty()) {
                val esTopTriple = tripleTopBottomResult.pattern == com.inversionadvisor.domain.indicators.UptrendDetector.TripleTopBottomPattern.TRIPLE_TOP
                fun closestIndexToTriple(isoDate: String, targetPrice: Double?): Int? {
                    val target = try { java.time.Instant.parse(isoDate) } catch (e: Exception) { return null }
                    var bestIndex = -1
                    var bestDiffMillis = Long.MAX_VALUE
                    candles.forEachIndexed { i, c ->
                        val candleInstant = try { java.time.Instant.parse(c.datetime) } catch (e: Exception) { return@forEachIndexed }
                        val diff = kotlin.math.abs(java.time.Duration.between(target, candleInstant).toMillis())
                        if (diff < bestDiffMillis) {
                            bestDiffMillis = diff
                            bestIndex = i
                        }
                    }
                    if (bestIndex < 0 || bestDiffMillis > 5L * 24 * 60 * 60 * 1000) return null
                    if (targetPrice == null || targetPrice <= 0.0) return bestIndex
                    val ventanaInicio = (bestIndex - 4).coerceAtLeast(0)
                    val ventanaFin = (bestIndex + 4).coerceAtMost(candles.size - 1)
                    var mejorIndice = bestIndex
                    var mejorDiferenciaPrecio = Double.MAX_VALUE
                    for (i in ventanaInicio..ventanaFin) {
                        val valorVela = if (esTopTriple) candles[i].high else candles[i].low
                        val diferencia = kotlin.math.abs(valorVela - targetPrice)
                        if (diferencia < mejorDiferenciaPrecio) {
                            mejorDiferenciaPrecio = diferencia
                            mejorIndice = i
                        }
                    }
                    return mejorIndice
                }
                val idxT1 = tripleTopBottomResult.firstDate?.let { closestIndexToTriple(it, tripleTopBottomResult.firstPrice) }
                val idxT2 = tripleTopBottomResult.secondDate?.let { closestIndexToTriple(it, tripleTopBottomResult.secondPrice) }
                val idxT3 = tripleTopBottomResult.thirdDate?.let { closestIndexToTriple(it, tripleTopBottomResult.thirdPrice) }
                val priceT1 = tripleTopBottomResult.firstPrice
                val priceT2 = tripleTopBottomResult.secondPrice
                val priceT3 = tripleTopBottomResult.thirdPrice
                if (idxT1 != null && idxT2 != null && idxT3 != null && priceT1 != null && priceT2 != null && priceT3 != null) {
                    tripleTopBottomEntries += Entry(idxT1.toFloat(), priceT1.toFloat())
                    tripleTopBottomEntries += Entry(idxT2.toFloat(), priceT2.toFloat())
                    tripleTopBottomEntries += Entry(idxT3.toFloat(), priceT3.toFloat())
                    tripleTopBottomIsTop = esTopTriple
                    tripleTopBottomWickEntries1 += Entry(idxT1.toFloat(), candles[idxT1].close.toFloat())
                    tripleTopBottomWickEntries1 += Entry(idxT1.toFloat(), priceT1.toFloat())
                    tripleTopBottomWickEntries2 += Entry(idxT2.toFloat(), candles[idxT2].close.toFloat())
                    tripleTopBottomWickEntries2 += Entry(idxT2.toFloat(), priceT2.toFloat())
                    tripleTopBottomWickEntries3 += Entry(idxT3.toFloat(), candles[idxT3].close.toFloat())
                    tripleTopBottomWickEntries3 += Entry(idxT3.toFloat(), priceT3.toFloat())
                }
            }
            val tripleTopBottomSet = LineDataSet(tripleTopBottomEntries, "Patrón triple").apply {
                // Mismos colores que el doble, pero un tono distinto (violeta) para diferenciarlo
                // a simple vista si los dos patrones coinciden a la vez en el mismo gráfico.
                val markerColor = if (tripleTopBottomIsTop) AndroidColor.parseColor("#8E24AA") else AndroidColor.parseColor("#00838F")
                color = AndroidColor.TRANSPARENT
                lineWidth = 0f
                setDrawCircles(true)
                setCircleColor(markerColor)
                circleRadius = 7f
                setDrawCircleHole(true)
                circleHoleColor = AndroidColor.WHITE
                circleHoleRadius = 3f
                setDrawValues(false)
            }
            fun wickDataSetTriple(entries: List<Entry>): LineDataSet = LineDataSet(entries, "").apply {
                val markerColor = if (tripleTopBottomIsTop) AndroidColor.parseColor("#8E24AA") else AndroidColor.parseColor("#00838F")
                color = markerColor
                lineWidth = 1.5f
                setDrawCircles(false)
                setDrawValues(false)
                setDrawFilled(false)
                mode = LineDataSet.Mode.LINEAR
            }
            val tripleTopBottomWickSet1 = wickDataSetTriple(tripleTopBottomWickEntries1)
            val tripleTopBottomWickSet2 = wickDataSetTriple(tripleTopBottomWickEntries2)
            val tripleTopBottomWickSet3 = wickDataSetTriple(tripleTopBottomWickEntries3)

            priceChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            priceChart.data = LineData(
                priceSet, smaSet, sma50Set,
                doubleTopBottomWickSet1, doubleTopBottomWickSet2, doubleTopBottomSet,
                tripleTopBottomWickSet1, tripleTopBottomWickSet2, tripleTopBottomWickSet3, tripleTopBottomSet
            )
            priceChart.invalidate()

            val volumeEntries = candles.mapIndexed { i, c -> BarEntry(i.toFloat(), (c.volume ?: 0L).toFloat()) }
            // CAMBIADO — mismo criterio de horario extendido que la línea de precio (ver
            // isExtendedHours arriba): esas barras se pintan de gris apagado en vez de
            // verde/rojo, para que quede claro a simple vista que ese volumen es de fuera de la
            // sesión regular (normalmente mucho menor y menos representativo).
            val volumeColors = candles.map { if (isExtendedHours(it)) extendedHoursColor else if (it.close >= it.open) upColor else downColor }
            val volumeSet = BarDataSet(volumeEntries, "Volumen").apply {
                colors = volumeColors
                setDrawValues(false)
            }
            volumeChart.xAxis.valueFormatter = IndexAxisValueFormatter(labels)
            volumeChart.data = BarData(volumeSet).apply { barWidth = 0.7f }
            volumeChart.invalidate()

            // Encaja ambos gráficos al rango completo al (re)cargar datos nuevos (cambio de símbolo o de rango temporal).
            priceChart.fitScreen()
            ChartXAxisSync.syncXAxis(priceChart, volumeChart)
        }
        )
    }
}

/**
 * Precio, capitalización bursátil ("Valor Empresa") e ingresos netos del
 * stock — datos de la propia empresa, no señales de venta, por eso van en su
 * propia tarjeta separada de SellTimingCard. Los ingresos netos tienen un
 * desplegable Trimestral/Anual (por defecto Anual).
 */
@Composable
private fun CompanySnapshotCard(
    symbol: String,
    price: Double?,
    marketCap: Double?,
    netIncomeCurrentYear: Double?,
    netIncomePreviousYear: Double?,
    netIncomeTwoYearsAgo: Double?,
    nextEarningsDateLabel: String? = null
) {
    // 0 = año en curso (TTM), 1 = año anterior, 2 = hace dos años.
    var selectedYearOffset by remember { mutableStateOf(0) }
    val netIncome = when (selectedYearOffset) {
        1 -> netIncomePreviousYear
        2 -> netIncomeTwoYearsAgo
        else -> netIncomeCurrentYear
    }
    val currencySymbol = inferCurrencySymbol(symbol)
    val currentCalendarYear = remember { java.time.LocalDate.now().year }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Datos de la empresa", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.padding(top = 10.dp))

            CompanySnapshotRow("Precio", price?.let { "$currencySymbol${"%.2f".format(it)}" } ?: "—")
            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            CompanySnapshotRow("Valor Empresa", formatAbbreviatedCurrency(marketCap, currencySymbol))
            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            Text("Ingresos netos", style = MaterialTheme.typography.bodyMedium)
            // 3 pestañas de año, en vez del desplegable Anual/Trimestral que había
            // antes — el año en curso es TTM (últimos 12 meses), no necesariamente
            // un año fiscal ya cerrado, así que se marca como tal en su etiqueta.
            Row(modifier = Modifier.padding(top = 6.dp, bottom = 4.dp)) {
                listOf(
                    0 to "$currentCalendarYear (TTM)",
                    1 to "${currentCalendarYear - 1}",
                    2 to "${currentCalendarYear - 2}"
                ).forEach { (offset, label) ->
                    val selected = selectedYearOffset == offset
                    Surface(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .clickable { selectedYearOffset = offset },
                        shape = RoundedCornerShape(6.dp),
                        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
            Text(
                formatAbbreviatedCurrency(netIncome, currencySymbol),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )

            // Próxima publicación de resultados trimestrales — "si tenemos el dato" (Yahoo no
            // lo da para todos los símbolos, y solo se pide para stocks reales, no oro/cripto).
            // En su propia línea debajo de la etiqueta (no en la misma fila que el resto de
            // filas de este panel) — pedido expresamente así.
            nextEarningsDateLabel?.let { dateLabel ->
                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("Próxima publicación de resultados", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        dateLabel,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompanySnapshotRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
    }
}

/** "3200000000000" (símbolo americano, USD) -> "$3,20 T" · con moneda europea -> "€850,00 M". */
private fun formatAbbreviatedCurrency(value: Double?, currencySymbol: String): String {
    if (value == null) return "—"
    val abs = kotlin.math.abs(value)
    val (divided, suffix) = when {
        abs >= 1e12 -> value / 1e12 to "T"
        abs >= 1e9 -> value / 1e9 to "B"
        abs >= 1e6 -> value / 1e6 to "M"
        else -> value to ""
    }
    return "$currencySymbol${"%.2f".format(divided)}${if (suffix.isNotEmpty()) " $suffix" else ""}"
}

/**
 * Heurística sencilla a partir del sufijo del símbolo (mismo criterio que ya
 * usa el resto de la app para el IBEX 35): ".MC" (Bolsa de Madrid) = euros,
 * cualquier otro símbolo de esta ficha (mercados de EE.UU.) = dólares. No es
 * una detección real de la moneda que reporta Yahoo para cada valor
 * concreto, es una aproximación por mercado.
 */
private fun inferCurrencySymbol(symbol: String): String = if (symbol.endsWith(".MC")) "€" else "$"

/**
 * "Análisis de opciones de compra": mismo semáforo global riesgo/recompensa
 * que tenía "Consejos de inversión", pero para este stock en concreto — ver
 * BuyOpportunityAnalyzer para el desglose completo de qué entra en el
 * cálculo. null mientras no haya velas suficientes todavía.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) // FlowRow — ver comentario donde se usa
private fun BuyOpportunityCard(analysis: com.inversionadvisor.domain.indicators.BuyOpportunityAnalyzer.Analysis?) {
    if (analysis == null) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text(
                "Calculando (o velas insuficientes todavía)…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    val rewardColor = Color(0xFF2E7D32)
    val riskColor = Color(0xFFC62828)
    val neutralColor = Color(0xFF616161)
    // Vuelta a la escala 0-100, con 4 colores según lo buena/mala que sea la inversión:
    // 0-25 rojo, 25-50 naranja, 50-75 amarillo, 75-100 verde.
    val ratioColor = when {
        analysis.riskRewardRatio < 25 -> riskColor
        analysis.riskRewardRatio < 50 -> Color(0xFFEF6C00)
        analysis.riskRewardRatio < 75 -> Color(0xFFF9A825)
        else -> rewardColor
    }
    // "Bocadillo informativo" al tocar el número grande — antes estaba en las filas de
    // "Recompensa X/100"/"Riesgo X/100", que se quitaron a petición expresa por dar lugar a
    // confusiones (dos números sueltos aparte del principal). El bocadillo sigue disponible,
    // combinando las dos listas (qué sumó y qué restó) en un solo sitio.
    var openBreakdown by remember { mutableStateOf(false) }
    if (openBreakdown) {
        CalculationBreakdownDialog("Cómo se calculó (0-100)", analysis.rewardBreakdown + analysis.riskBreakdown, onDismiss = { openBreakdown = false })
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Riesgo/recompensa", style = MaterialTheme.typography.labelMedium)
                    // Sector en badge azul, como se pidió — solo si hay dato (oro/cripto no tienen sector).
                    analysis.sectorName?.let { sector ->
                        Surface(
                            modifier = Modifier.padding(top = 4.dp),
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF1565C0).copy(alpha = 0.15f)
                        ) {
                            Text(
                                sector,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1565C0),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = ratioColor.copy(alpha = 0.15f),
                    modifier = Modifier.clickable { openBreakdown = true }
                ) {
                    Text(
                        "%.0f".format(analysis.riskRewardRatio) + " ℹ",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = ratioColor,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
            // Veredicto general sobre el número grande — pedido aparte de los comentarios de recompensa/riesgo.
            Text(
                analysis.overallComment,
                style = MaterialTheme.typography.bodySmall,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                color = ratioColor,
                modifier = Modifier.padding(top = 6.dp)
            )
            // QUITADO a petición expresa: el aviso de "puede diferir de Futuras compras/Top10"
            // ya no aplica — los 4 sitios (scanner-cli, escaneo en directo, esta ficha y las
            // tarjetas de Valores Alcistas) están sincronizados con los mismos parámetros
            // (benchmark, sector, HCH a 5 años, etc.), así que ya no debería diferir por diseño.

            // Penalizaciones y bonos APARTE (SMA50, aceleración de caída, resultados
            // trimestrales, divergencia momentum/precio, cruz dorada) — aviso en neón
            // intermitente, distinto del semáforo normal de arriba: ROJO para penalizaciones,
            // VERDE para bonos (antes ambos salían en rojo, sin distinción).
            // FlowRow en vez de Row normal — CORREGIDO: con varios avisos activos a la vez
            // (p. ej. Oracle o Adobe con SMA50 Y resultados próximos al mismo tiempo), el texto
            // conjunto no siempre cabía en el ancho de pantalla, y un Row normal no hace salto
            // de línea — se comportaba de forma inconsistente según cuántos avisos hubiera,
            // dejando a veces un hueco en blanco desproporcionado. FlowRow pasa a la siguiente
            // línea cuando hace falta, sin dejar huecos raros.
            if (analysis.penaltyWarnings.isNotEmpty() || analysis.bonusWarnings.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    analysis.bonusWarnings.forEach { warning ->
                        com.inversionadvisor.ui.common.BlinkingNeonBadge(
                            warning,
                            positive = true,
                            // NUEVO — pedido expresamente: este aviso concreto es AMBIGUO (fortaleza
                            // relativa, pero riesgo de "recolocarse" a la baja con el sector) — naranja
                            // en vez de verde, para no darlo como una señal puramente positiva.
                            overrideColor = if (warning.startsWith("Ha caído bastante menos que su propio sector")) Color(0xFFFFA000) else null
                        )
                    }
                    analysis.penaltyWarnings.forEach { warning ->
                        com.inversionadvisor.ui.common.BlinkingNeonBadge(warning)
                    }
                }
            }

            androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

            // RSI con etiqueta cualitativa (alto/bajo), no solo el número — lo que se pidió.
            val (rsiLabel, rsiColor) = when {
                analysis.rsi14 == null -> "—" to neutralColor
                analysis.rsi14 >= 70 -> "Sobrecompra" to riskColor
                analysis.rsi14 >= 55 -> "Zona alta" to Color(0xFFEF6C00)
                analysis.rsi14 >= 30 -> "Zona normal" to rewardColor
                else -> "Sobreventa" to Color(0xFF1565C0)
            }
            BuyOpportunityDetailRow(
                "RSI 14",
                analysis.rsi14?.let { "%.0f".format(it) } ?: "—",
                badgeText = if (analysis.rsi14 != null) rsiLabel else null,
                badgeColor = rsiColor
            )
            PerVsSectorRow(analysis, rewardColor, riskColor)
            BuyOpportunityDetailRow(
                "Caída desde máximo reciente",
                analysis.declineFromRecentHighPercent?.let { "%.0f".format(it) + "%" + if (analysis.significantDecline) " (>15%)" else "" } ?: "—",
                badgeColor = if (analysis.significantDecline) Color(0xFF1565C0) else neutralColor
            )
            BuyOpportunityDetailRow(
                "Figura de agotamiento",
                if (analysis.exhaustionDetected) "Sí (confianza ${analysis.exhaustionConfidence}%)" else "No detectada",
                badgeColor = if (analysis.exhaustionDetected) rewardColor else neutralColor
            )
            BuyOpportunityDetailRow(
                "Volumen",
                analysis.volumeRatio?.let { "${"%.0f".format(it * 100)}% de la media" + if (analysis.unusualVolume) " (inusual)" else "" } ?: "—",
                badgeColor = if (analysis.unusualVolume) riskColor else rewardColor
            )
            BuyOpportunityDetailRow(
                "Volatilidad propia",
                analysis.stockVolatilityRatio?.let { "${"%.1f".format(it)}x lo habitual" + if (analysis.unusualVolatility) " (inusual)" else "" } ?: "—",
                badgeColor = if (analysis.unusualVolatility) riskColor else rewardColor
            )
            BuyOpportunityDetailRow(
                "Techo (máx. del año)",
                analysis.percentFromYearHigh?.let { "%.0f".format(it) + "%" + if (analysis.nearCeiling) " (muy cerca)" else "" } ?: "—",
                badgeColor = if (analysis.nearCeiling) riskColor else neutralColor
            )
            BuyOpportunityDetailRow(
                "Suelo (mín. del año)",
                analysis.percentAboveYearLow?.let { "+${"%.0f".format(it)}%" + if (analysis.nearFloor) " (muy cerca)" else "" } ?: "—",
                badgeColor = if (analysis.nearFloor) rewardColor else neutralColor
            )
            BuyOpportunityDetailRow(
                "Resistencia más cercana",
                analysis.nearestResistancePercent?.let { "%.1f%% por encima".format(it) } ?: "—",
                badgeColor = Color(0xFFEF6C00)
            )
            BuyOpportunityDetailRow(
                "Soporte más cercano",
                analysis.nearestSupportPercent?.let { "%.1f%% por debajo".format(it) } ?: "—",
                badgeColor = Color(0xFF1565C0)
            )

            if (analysis.summary.isNotEmpty()) {
                androidx.compose.material3.HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                analysis.summary.forEach { line ->
                    Text("• $line", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 2.dp))
                }
            }
        }
    }
}

/** Verde si el PER está en línea con el sector, naranja si se aleja moderado, rojo si se aleja mucho — mismos cortes que el resto de la app. */
private fun peDeviationColor(stockPe: Double?, sectorAveragePe: Double?, lowColor: Color, highColor: Color): Color {
    if (stockPe == null || sectorAveragePe == null || sectorAveragePe <= 0.0) return Color(0xFF616161)
    val deviation = kotlin.math.abs((stockPe - sectorAveragePe) / sectorAveragePe * 100)
    return when {
        deviation <= 15.0 -> lowColor
        deviation <= 40.0 -> Color(0xFFEF6C00)
        else -> highColor
    }
}

@Composable
private fun BuyOpportunityDetailRow(label: String, value: String, badgeText: String? = null, badgeColor: Color = Color(0xFF616161)) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (badgeText != null) {
                Surface(shape = RoundedCornerShape(50), color = badgeColor.copy(alpha = 0.15f), modifier = Modifier.padding(end = 6.dp)) {
                    Text(badgeText, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = badgeColor, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                }
            } else {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .background(badgeColor, RoundedCornerShape(50))
                )
            }
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Fila de "PER vs sector" — CORREGIDO: cuando el beneficio de los últimos 12 meses es negativo
 * (analysis.hasNegativeTrailingEarnings), antes el aviso se metía como texto pegado al valor
 * numérico ("23.4x (sector 20.1x) ⚠️ beneficio TTM negativo..."), lo que descuadraba la fila —
 * al ser una fila con las dos columnas separadas (SpaceBetween, ver BuyOpportunityDetailRow), un
 * texto tan largo a la derecha no quedaba alineado con el resto de filas de la tarjeta.
 *
 * Ahora, en ese caso, la fila se vuelve PULSABLE: el valor numérico se queda corto y alineado
 * igual que el resto (sin ningún texto extra pegado), con un icono "ⓘ" al lado que indica que
 * hay más información; al tocar la fila, se despliega una línea aparte DEBAJO con la explicación
 * completa. Si no hay beneficio negativo, la fila es idéntica a como era antes (no pulsable, sin
 * icono).
 */
@Composable
private fun PerVsSectorRow(analysis: BuyOpportunityAnalyzer.Analysis, rewardColor: Color, riskColor: Color) {
    var expanded by remember { mutableStateOf(false) }
    val tieneAviso = analysis.hasNegativeTrailingEarnings
    val valueText = if (analysis.stockPe != null && analysis.sectorAveragePe != null) {
        "${"%.1f".format(analysis.stockPe)}x (sector ${"%.1f".format(analysis.sectorAveragePe)}x)"
    } else "—"
    val badgeColor = if (tieneAviso) riskColor else peDeviationColor(analysis.stockPe, analysis.sectorAveragePe, rewardColor, riskColor)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .let { if (tieneAviso) it.clickable { expanded = !expanded } else it }
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("PER vs sector", style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(8.dp)
                        .background(badgeColor, RoundedCornerShape(50))
                )
                Text(valueText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                if (tieneAviso) {
                    Text(
                        if (expanded) " ⓘ ▲" else " ⓘ ▾",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = riskColor,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
        if (tieneAviso && expanded) {
            Text(
                "⚠️ Beneficio de los últimos 12 meses negativo — el PER mostrado es una estimación basada en el beneficio FUTURO esperado (Forward P/E), no en resultados ya reportados.",
                style = MaterialTheme.typography.labelSmall,
                color = riskColor,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)
            )
        }
    }
}

/**
 * Tarjeta de "Momento idóneo para la venta": semáforo global (basado en la
 * media de los 4 factores) + desglose de cada señal (sobrecompra, volumen,
 * sentimiento AAII, volatilidad). null mientras no haya velas suficientes
 * (RSI 14 necesita al menos 15) o mientras se cargan los indicadores de
 * mercado.
 */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class) // FlowRow — ver comentario donde se usa
private fun SellTimingCard(assessment: SellTimingAssessment?) {
    if (assessment == null) {
        Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Text(
                "Calculando señales de venta (o velas insuficientes todavía)…",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp)
            )
        }
        return
    }

    // Semáforo de 4 tramos calculado directamente sobre la puntuación (0-24 rojo, 25-49
    // naranja, 50-74 amarillo, 75-100 verde) — RiskLevel solo tiene 3 niveles, así que este
    // color se calcula aparte en vez de usar assessment.overallLevel.toColor().
    val sellTimingColor = when {
        assessment.overallScore < 25 -> Color(0xFFC62828)
        assessment.overallScore < 50 -> Color(0xFFEF6C00)
        assessment.overallScore < 75 -> Color(0xFFF9A825)
        else -> Color(0xFF2E7D32)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = sellTimingColor.copy(alpha = 0.08f))
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(8.dp), color = sellTimingColor) {
                    Text(
                        "${assessment.overallScore}/100",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    assessment.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 10.dp).weight(1f)
                )
            }

            // Bonos "Teoría de la Opinión Contraria" (Fear & Greed / AAII) — se pintan como
            // aviso en neón VERDE intermitente (antes rojo, sin distinción — corregido a
            // petición expresa: son BONOS, no penalizaciones), distinto del semáforo normal
            // de los 5 factores de arriba, para que quede claro que son un plus aparte.
            // FlowRow en vez de Row normal — mismo motivo que en "Análisis de opciones de
            // compra": con varios bonos activos a la vez, un Row normal no ajustaba línea.
            if (assessment.contrarianWarnings.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    assessment.contrarianWarnings.forEach { warning -> BlinkingNeonBadge(warning, positive = true) }
                }
            }

            // Avisos del subgrupo NUEVO "Patrones técnicos de venta" (Hombro-Cabeza-Hombro,
            // Doble Techo, Divergencia Bajista Precio/RSI, Cruce de la Muerte SMA50/SMA200) —
            // en neón ROJO (positive = false), son señales de venta, no bonos.
            if (assessment.sellPatternWarnings.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    assessment.sellPatternWarnings.forEach { warning -> BlinkingNeonBadge(warning, positive = false) }
                }
            }

            Spacer(modifier = Modifier.padding(top = 12.dp))

            assessment.factors.forEach { factor -> SellTimingFactorRow(factor) }
        }
    }
}

@Composable
private fun SellTimingFactorRow(factor: SellTimingFactor) {
    // Un factor "sin dato todavía" (includedInScore=false) se pinta en gris, no con
    // el color de riesgo — ese color solo tiene sentido cuando hay un dato real
    // detrás; en gris queda claro de un vistazo que no es una señal real, solo
    // "todavía no ha llegado".
    val dotColor = if (factor.includedInScore) factor.riskLevel.toColor() else Color(0xFFBDBDBD)
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Surface(
            modifier = Modifier.size(10.dp).padding(top = 4.dp),
            shape = RoundedCornerShape(50),
            color = dotColor
        ) {}
        Column(modifier = Modifier.padding(start = 10.dp).weight(1f)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(factor.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(factor.valueText, style = MaterialTheme.typography.bodyMedium)
            }
            Text(factor.detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Etiqueta del eje X adaptada al rango:
 *  - 1D: hora (velas intradía de 5 min, todas del mismo día — la fecha no aporta nada).
 *  - 1S y 1M: fecha con día (velas diarias/semi-intradía, varios días distintos).
 *  - 1A y 5A: igual que antes (fecha con día — sirve igual para velas semanales).
 */
private fun Candle.chartLabel(range: ChartRange): String {
    return if (range == ChartRange.ONE_DAY) {
        val dt = runCatching { LocalDateTime.parse(datetime, DateTimeFormatter.ISO_DATE_TIME) }.getOrNull()
        dt?.let { "%02d:%02d".format(it.hour, it.minute) } ?: datetime.take(16)
    } else {
        val date = runCatching { LocalDate.parse(datetime.take(10)) }.getOrNull() ?: return datetime.take(10)
        val month = date.month.getDisplayName(TextStyle.SHORT, Locale("es", "ES"))
        "${date.dayOfMonth} $month"
    }
}

private fun dpToPx(context: android.content.Context, dp: Int): Int =
    (dp * context.resources.displayMetrics.density).toInt()

/**
 * "Bocadillo informativo" con el desglose línea a línea de cómo se ha calculado Recompensa o
 * Riesgo — se abre al tocar esos valores, tanto aquí como en Top10 (ver ScreenerScreen.kt,
 * mismo composable reutilizado). Pensado para poder ir viendo qué está pesando cada indicador
 * y afinar la fórmula con el tiempo, tal como se pidió.
 */
@Composable
internal fun CalculationBreakdownDialog(title: String, lines: List<String>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cerrar") }
        },
        title = { Text(title, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                lines.forEachIndexed { index, line ->
                    val isTotal = line.startsWith("RECOMPENSA TOTAL") || line.startsWith("RIESGO TOTAL") || line.contains("riesgo neutro por defecto")
                    Text(
                        "• $line",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (isTotal) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.padding(top = if (index == 0) 0.dp else 6.dp)
                    )
                }
            }
        }
    )
}
