package com.inversionadvisor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.lifecycleScope
import com.inversionadvisor.domain.model.MarketUniverse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import com.inversionadvisor.data.connectivity.NetworkConnectivityObserver
import com.inversionadvisor.di.ServiceLocator
import com.inversionadvisor.ui.common.ConnectivityBanner
import com.inversionadvisor.ui.dashboard.DashboardScreen
import com.inversionadvisor.ui.dashboard.DashboardViewModel
import com.inversionadvisor.ui.news.NoticiasScreen
import com.inversionadvisor.ui.news.NoticiasViewModel
import com.inversionadvisor.ui.screener.ScreenerScreen
import com.inversionadvisor.ui.screener.ScreenerViewModel
import com.inversionadvisor.ui.search.SearchScreen
import com.inversionadvisor.ui.search.SearchViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val marketRepository = ServiceLocator.provideMarketRepository(applicationContext)
        val screenerRepository = ServiceLocator.provideScreenerRepository(applicationContext)
        val marketMoversRepository = ServiceLocator.provideMarketMoversRepository(applicationContext)
        val stockUniverseRepository = ServiceLocator.provideStockUniverseRepository(applicationContext)
        val favoritesRepository = ServiceLocator.provideFavoritesRepository(applicationContext)
        val top10Repository = ServiceLocator.provideTop10Repository(applicationContext)
        val posiblesComprasRepository = ServiceLocator.providePosiblesComprasRepository(applicationContext)
        val newsRepository = ServiceLocator.provideNewsRepository()
        val connectivityObserver = NetworkConnectivityObserver(applicationContext)

        // NUEVO — pedido expresamente: al abrir la app, se importan los 3 mercados (S&P 500,
        // Nasdaq-100, IBEX 35) y luego se recalcula Top10, todo en segundo plano — el usuario no
        // tiene que ir pestaña por pestaña pulsando "Analizar" a mano cada vez que abre la app.
        // Igual que en ScreenerViewModel.runScreener(): se intenta PRIMERO el JSON (rápido), y
        // solo si falla se cae al escaneo en directo de ese mercado en concreto — nunca deja un
        // mercado sin datos por completo. lifecycleScope se cancela solo si la Activity se
        // destruye a media carga, sin dejar corrutinas huérfanas corriendo de fondo.
        lifecycleScope.launch {
            android.util.Log.i("AutoLoadDiagnostic", "1) Arranca la carga automática")
            // CAMBIADO a petición expresa: antes se cargaban SP500 → NASDAQ100 → IBEX35 uno
            // detrás de otro (secuencial) — si SP500 tardaba, daba la sensación de que los
            // otros dos "no cargaban solos" cuando en realidad solo estaban esperando su turno.
            // Ahora los 3 se lanzan A LA VEZ (en paralelo), y Top10/Posibles Compras esperan a
            // que los 4 mercados terminen.
            // NUEVO — pedido expresamente: Russell 2000 añadido ahora que scanner-cli sabe
            // conseguir su universo (CSV de holdings del ETF IWM, ver
            // fetchRussell2000Universe en scanner-cli/Main.kt). Si el JSON fallara para este
            // mercado en concreto, el escaneo en directo de refugio simplemente no encuentra
            // nada (StockUniverseRepository no tiene lista local para Russell 2000), sin
            // errores ni caídas — es seguro incluirlo aquí igual que los otros 3.
            listOf(MarketUniverse.SP500, MarketUniverse.NASDAQ100, MarketUniverse.IBEX35, MarketUniverse.RUSSELL2000).map { market ->
                async {
                    android.util.Log.i("AutoLoadDiagnostic", "  -> empieza ${market.indexName}")
                    val importado = runCatching { screenerRepository.importFromRemoteJson(market.indexName) }.getOrDefault(false)
                    if (!importado) {
                        runCatching { screenerRepository.runFullScreen(market.indexName) { _, _ -> } }
                    }
                    android.util.Log.i("AutoLoadDiagnostic", "  <- termina ${market.indexName} (importado=$importado)")
                }
            }.awaitAll()
            android.util.Log.i("AutoLoadDiagnostic", "2) Los 4 mercados han terminado, empieza Top10/Posibles Compras")
            // NUEVO — Top10 y Posibles Compras en paralelo entre sí también, ya que son
            // independientes (leen los mismos datos ya guardados, pero calculan cada uno lo suyo).
            // NUEVO — pedido expresamente (fallo real: "Posibles Compras" se quedaba siempre en
            // "sin resultados" sin ningún error visible): runCatching aquí se tragaba cualquier
            // excepción en silencio, sin pasarla al estado del ViewModel (eso solo lo hace
            // calculatePosiblesCompras(), el botón manual, no esta carga automática) — se deja
            // un registro para poder ver la causa real en el log si vuelve a pasar.
            listOf(
                async {
                    runCatching { top10Repository.refresh { _, _ -> } }
                        .onFailure { android.util.Log.e("AutoLoadDiagnostic", "Top10.refresh() falló", it) }
                },
                async {
                    android.util.Log.i("AutoLoadDiagnostic", "  -> empieza PosiblesCompras.refresh()")
                    runCatching { posiblesComprasRepository.refresh { _, _ -> } }
                        .onFailure { android.util.Log.e("AutoLoadDiagnostic", "PosiblesCompras.refresh() falló", it) }
                    android.util.Log.i("AutoLoadDiagnostic", "  <- termina PosiblesCompras.refresh()")
                }
            ).awaitAll()
            android.util.Log.i("AutoLoadDiagnostic", "3) Carga automática completa")
        }

        setContent {
            com.inversionadvisor.ui.theme.InversionAdvisorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var selectedTab by remember { mutableStateOf(0) }
                    val tabTitles = listOf("Panel", "Análisis", "Busca", "Noticias")

                    // CAMBIADO a petición expresa, tras varios intentos fallidos con viewModel()
                    // dentro del when (clave explícita, SavedStateHandle...): los 4 ViewModel se
                    // crean AQUÍ, AL MISMO NIVEL que selectedTab — que ya sabemos con certeza que
                    // sobrevive al cambio de pestaña, porque es la propia variable que decide qué
                    // pestaña se ve. Poniendo los ViewModel exactamente en ese mismo sitio (no
                    // dentro de las ramas del when, que se activan/desactivan), quedan protegidos
                    // por la misma garantía, sin depender de ningún mecanismo interno de caché de
                    // viewModel() que evidentemente no se estaba comportando como cabía esperar.
                    val dashboardViewModel: DashboardViewModel = viewModel(
                        factory = DashboardViewModel.Factory(marketRepository, marketMoversRepository)
                    )
                    val screenerViewModel: ScreenerViewModel = viewModel(
                        factory = ScreenerViewModel.Factory(this@MainActivity, screenerRepository, favoritesRepository, stockUniverseRepository, top10Repository, posiblesComprasRepository)
                    )
                    val searchViewModel: SearchViewModel = viewModel(
                        factory = SearchViewModel.Factory(stockUniverseRepository, favoritesRepository)
                    )
                    val noticiasViewModel: NoticiasViewModel = viewModel(
                        factory = NoticiasViewModel.Factory(newsRepository)
                    )

                    // Box en vez de Column directamente — para poder superponer el banner de
                    // conexión ENCIMA de todo el contenido (pestañas incluidas), pedido
                    // expresamente "en la parte superior de la pantalla", no dentro de una
                    // pantalla en concreto.
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            // TabRow normal (no deslizante) — con 4 pestañas fijas debería seguir
                            // cabiendo de sobra en pantalla (era 3, con "Panel"/"Análisis"/"Busca"
                            // ya centradas antes; "Noticias" no es mucho más larga). La versión
                            // deslizante (ScrollableTabRow) las dejaba pegadas a la izquierda, sin
                            // centrar, que es justo lo que se pidió corregir — si con las 4 vuelve
                            // a apretarse en pantallas estrechas, esa es la primera señal de que
                            // haría falta volver a ScrollableTabRow aquí (no confundir con el
                            // ScrollableTabRow interno de NoticiasScreen, que es un nivel distinto:
                            // las 4 fuentes DENTRO de la pestaña Noticias, no las pestañas principales).
                            // CAMBIADO a petición expresa (tema oscuro "Grafito + Verde-azulado
                            // neón"): mismo acento turquesa + sombra gris que el resto de
                            // pestañas de la app (nombre del stock, pestañas de mercado y
                            // subpestañas de Análisis) — antes era azul índigo, pensado para
                            // fondo claro.
                            val colorIndigo = com.inversionadvisor.ui.theme.NeonTeal
                            val sombraGris = Shadow(color = Color.Black.copy(alpha = 0.6f), offset = Offset(1.5f, 1.5f), blurRadius = 3f)

                            // NUEVO — pedido expresamente: el desplegable de secciones de Análisis
                            // (Índices/Top10/Posibles Compras/Divisas/Bonos, antes una cabecera
                            // aparte dentro de ScreenerScreen) ahora CUELGA de la propia pestaña
                            // "Análisis" de este TabRow — mismo patrón que el desplegable "Elige un
                            // índice bursátil" (DropdownMenu anclado a un Box que envuelve el
                            // elemento que lo dispara), para que se lea de verdad como si emergiera
                            // de la pestaña, no de un control aparte debajo de ella.
                            val screenerState by screenerViewModel.uiState.collectAsState()
                            val gruposDeIndicesAnalisis = setOf(MarketUniverse.SP500, MarketUniverse.NASDAQ100, MarketUniverse.IBEX35, MarketUniverse.RUSSELL2000)
                            data class SeccionAnalisis(val etiqueta: String, val seleccionada: Boolean, val alPulsar: () -> Unit)
                            val seccionesAnalisis = listOf(
                                SeccionAnalisis("Índices", screenerState.selectedMarket in gruposDeIndicesAnalisis) {
                                    if (screenerState.selectedMarket !in gruposDeIndicesAnalisis) screenerViewModel.selectMarket(screenerViewModel.ultimoIndiceElegido)
                                },
                                SeccionAnalisis(MarketUniverse.TOP10.displayName, screenerState.selectedMarket == MarketUniverse.TOP10) { screenerViewModel.selectMarket(MarketUniverse.TOP10) },
                                SeccionAnalisis(MarketUniverse.POSIBLES_COMPRAS.displayName, screenerState.selectedMarket == MarketUniverse.POSIBLES_COMPRAS) { screenerViewModel.selectMarket(MarketUniverse.POSIBLES_COMPRAS) },
                                SeccionAnalisis(MarketUniverse.DIVISAS.displayName, screenerState.selectedMarket == MarketUniverse.DIVISAS) { screenerViewModel.selectMarket(MarketUniverse.DIVISAS) },
                                SeccionAnalisis(MarketUniverse.BONOS.displayName, screenerState.selectedMarket == MarketUniverse.BONOS) { screenerViewModel.selectMarket(MarketUniverse.BONOS) }
                            )
                            var menuAnalisisAbierto by remember { mutableStateOf(false) }

                            TabRow(selectedTabIndex = selectedTab) {
                                tabTitles.forEachIndexed { index, title ->
                                    if (index == 1) {
                                        Box {
                                            Tab(
                                                selected = selectedTab == index,
                                                onClick = {
                                                    selectedTab = index
                                                    menuAnalisisAbierto = true
                                                },
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Text(
                                                            title,
                                                            style = MaterialTheme.typography.bodyMedium.copy(shadow = sombraGris),
                                                            color = colorIndigo,
                                                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                        Icon(
                                                            Icons.Default.KeyboardArrowDown,
                                                            contentDescription = null,
                                                            tint = colorIndigo,
                                                            modifier = Modifier.padding(start = 2.dp).size(16.dp)
                                                        )
                                                    }
                                                }
                                            )
                                            DropdownMenu(
                                                expanded = menuAnalisisAbierto,
                                                onDismissRequest = { menuAnalisisAbierto = false }
                                            ) {
                                                seccionesAnalisis.forEach { seccion ->
                                                    DropdownMenuItem(
                                                        text = {
                                                            Text(
                                                                seccion.etiqueta,
                                                                color = colorIndigo,
                                                                fontWeight = if (seccion.seleccionada) FontWeight.Bold else FontWeight.Normal
                                                            )
                                                        },
                                                        onClick = {
                                                            seccion.alPulsar()
                                                            menuAnalisisAbierto = false
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    } else {
                                        Tab(
                                            selected = selectedTab == index,
                                            onClick = { selectedTab = index },
                                            text = {
                                                Text(
                                                    title,
                                                    style = MaterialTheme.typography.bodyMedium.copy(shadow = sombraGris),
                                                    color = colorIndigo,
                                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                                )
                                            }
                                        )
                                    }
                                }
                            }

                            when (selectedTab) {
                                0 -> DashboardScreen(dashboardViewModel, marketRepository)
                                1 -> ScreenerScreen(screenerViewModel, marketRepository)
                                2 -> SearchScreen(searchViewModel, marketRepository)
                                else -> NoticiasScreen(noticiasViewModel)
                            }
                        }

                        ConnectivityBanner(
                            observer = connectivityObserver,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}
