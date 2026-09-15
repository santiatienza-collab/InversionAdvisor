package com.inversionadvisor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
            // CAMBIADO a petición expresa: antes se cargaban SP500 → NASDAQ100 → IBEX35 uno
            // detrás de otro (secuencial) — si SP500 tardaba, daba la sensación de que los
            // otros dos "no cargaban solos" cuando en realidad solo estaban esperando su turno.
            // Ahora los 3 se lanzan A LA VEZ (en paralelo), y Top10 espera a que los 3 terminen.
            // NUEVO — pedido expresamente: Russell 2000 añadido ahora que scanner-cli sabe
            // conseguir su universo (CSV de holdings del ETF IWM, ver
            // fetchRussell2000Universe en scanner-cli/Main.kt). Si el JSON fallara para este
            // mercado en concreto, el escaneo en directo de refugio simplemente no encuentra
            // nada (StockUniverseRepository no tiene lista local para Russell 2000), sin
            // errores ni caídas — es seguro incluirlo aquí igual que los otros 3.
            listOf(MarketUniverse.SP500, MarketUniverse.NASDAQ100, MarketUniverse.IBEX35, MarketUniverse.RUSSELL2000).map { market ->
                async {
                    val importado = runCatching { screenerRepository.importFromRemoteJson(market.indexName) }.getOrDefault(false)
                    if (!importado) {
                        runCatching { screenerRepository.runFullScreen(market.indexName) { _, _ -> } }
                    }
                }
            }.awaitAll()
            runCatching { top10Repository.refresh { _, _ -> } }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var selectedTab by remember { mutableStateOf(0) }
                    val tabTitles = listOf("Panel", "Análisis", "Busca", "Noticias")

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
                            // NUEVO — pedido expresamente: mismo azul índigo + sombra gris que
                            // el resto de pestañas de la app (nombre del stock, pestañas de
                            // mercado y subpestañas de Análisis).
                            val colorIndigo = Color(0xFF3F51B5)
                            val sombraGris = Shadow(color = Color.Gray, offset = Offset(1.5f, 1.5f), blurRadius = 3f)
                            TabRow(selectedTabIndex = selectedTab) {
                                tabTitles.forEachIndexed { index, title ->
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

                            when (selectedTab) {
                                0 -> {
                                    // NUEVO — pedido expresamente tras fallo real confirmado: el
                                    // estado del ViewModel (mercado elegido, scroll...) se estaba
                                    // perdiendo al cambiar de pestaña y volver — señal de que
                                    // viewModel() sin clave explícita no estaba devolviendo
                                    // siempre la MISMA instancia. Con "key" a mano, no hay
                                    // ambigüedad posible: siempre la misma, pase lo que pase.
                                    val viewModel: DashboardViewModel = viewModel(
                                        key = "dashboard",
                                        factory = DashboardViewModel.Factory(marketRepository, marketMoversRepository)
                                    )
                                    DashboardScreen(viewModel, marketRepository)
                                }
                                1 -> {
                                    val viewModel: ScreenerViewModel = viewModel(
                                        key = "screener",
                                        factory = ScreenerViewModel.Factory(this@MainActivity, screenerRepository, favoritesRepository, stockUniverseRepository, top10Repository)
                                    )
                                    ScreenerScreen(viewModel, marketRepository)
                                }
                                2 -> {
                                    val viewModel: SearchViewModel = viewModel(
                                        key = "search",
                                        factory = SearchViewModel.Factory(stockUniverseRepository, favoritesRepository)
                                    )
                                    SearchScreen(viewModel, marketRepository)
                                }
                                else -> {
                                    val viewModel: NoticiasViewModel = viewModel(
                                        key = "noticias",
                                        factory = NoticiasViewModel.Factory(newsRepository)
                                    )
                                    NoticiasScreen(viewModel)
                                }
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
