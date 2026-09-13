package com.inversionadvisor.data.repository

import com.inversionadvisor.domain.indicators.NewsRssParser
import com.inversionadvisor.domain.indicators.NewsTranslator
import com.inversionadvisor.domain.model.FuenteNoticias
import com.inversionadvisor.domain.model.Titular
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Repositorio de la sección Noticias: descarga los 4 feeds RSS de mercados en paralelo y
 * los parsea a Titular. Sin caché en Room a propósito — a diferencia de cotizaciones/PER,
 * los titulares son efímeros y se quieren siempre frescos al abrir la pestaña; el coste de
 * red es bajo (4 peticiones pequeñas).
 */
class NewsRepository(
    private val newsRssApi: com.inversionadvisor.data.remote.NewsRssApi = com.inversionadvisor.data.remote.NetworkModule.newsRssApi
) {

    suspend fun obtenerTitulares(fuente: FuenteNoticias, limite: Int = 15): List<Titular> {
        return try {
            val xml = newsRssApi.getFeedXml(fuente.rssUrl).string()
            // El sentimiento se clasifica sobre el titular en INGLÉS (NewsSentimentClassifier
            // usa palabras clave en inglés) y SOLO DESPUÉS se traduce el texto a mostrar — si se
            // tradujera antes de clasificar, el clasificador no reconocería nada.
            val titularesEnIngles = NewsRssParser.parse(xml, limite)
            val traducidos = NewsTranslator.traducirLote(titularesEnIngles.map { it.texto })
            titularesEnIngles.mapIndexed { i, titular -> titular.copy(texto = traducidos.getOrElse(i) { titular.texto }) }
        } catch (e: Exception) {
            // Fuente caída, RSS cambiado o timeout: la pestaña de esa fuente mostrará "sin
            // titulares" en vez de romper toda la pantalla de Noticias.
            android.util.Log.w("NewsFetch", "${fuente.nombre}: ${e.message ?: e::class.simpleName}")
            emptyList()
        }
    }

    /**
     * CORREGIDO — la versión anterior llamaba a "async"/"coroutineScope" con el nombre
     * completo (kotlinx.coroutines.async{...}) en vez de importarlos; async es una función
     * de EXTENSIÓN sobre CoroutineScope, y así cualificada el compilador no siempre resuelve
     * bien el receptor implícito, lo que rompía la inferencia de tipos de todo el bloque
     * (de ahí el error "inferred type is Unit but Map<...> was expected"). Con las funciones
     * importadas y usadas sin cualificar (mismo patrón que ya usa MarketRepository en sus
     * propios "coroutineScope { val x = async {...} }"), infiere bien el tipo.
     */
    suspend fun obtenerTodasLasFuentes(): Map<String, List<Titular>> = coroutineScope {
        val deferreds = com.inversionadvisor.domain.model.FUENTES_NOTICIAS.map { fuente ->
            async { fuente.nombre to obtenerTitulares(fuente) }
        }
        deferreds.awaitAll().toMap()
    }
}
