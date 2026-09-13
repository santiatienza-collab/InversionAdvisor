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
                                    val viewModel: DashboardViewModel = viewModel(
                                        factory = DashboardViewModel.Factory(marketRepository, marketMoversRepository)
                                    )
                                    DashboardScreen(viewModel, marketRepository)
                                }
                                1 -> {
                                    val viewModel: ScreenerViewModel = viewModel(
                                        factory = ScreenerViewModel.Factory(screenerRepository, favoritesRepository, stockUniverseRepository, top10Repository)
                                    )
                                    ScreenerScreen(viewModel, marketRepository)
                                }
                                2 -> {
                                    val viewModel: SearchViewModel = viewModel(
                                        factory = SearchViewModel.Factory(stockUniverseRepository, favoritesRepository)
                                    )
                                    SearchScreen(viewModel, marketRepository)
                                }
                                else -> {
                                    val viewModel: NoticiasViewModel = viewModel(
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
