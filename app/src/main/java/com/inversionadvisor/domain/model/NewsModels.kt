package com.inversionadvisor.domain.model

enum class Sentimiento { POSITIVO, NEGATIVO, NEUTRO }

data class Titular(
    val texto: String,
    val url: String,
    val fecha: String?,
    val sentimiento: Sentimiento
)

data class FuenteNoticias(
    val nombre: String,
    val rssUrl: String
)

/**
 * Feeds RSS de la sección de mercados de cada medio — solo titular + enlace, nunca el
 * cuerpo del artículo: es lo único que estos medios publican de forma abierta (WSJ, FT y
 * Bloomberg tienen paywall para el resto del contenido).
 *
 * SIN VERIFICAR EN VIVO todavía si las 4 URLs siguen activas — los medios cambian sus rutas
 * de RSS de vez en cuando; si una fuente concreta sale siempre vacía en la pestaña de
 * Noticias, lo primero a revisar es si esta URL sigue siendo la correcta.
 */
val FUENTES_NOTICIAS = listOf(
    FuenteNoticias("WSJ Markets", "https://feeds.a.dj.com/rss/RSSMarketsMain.xml"),
    FuenteNoticias("Financial Times", "https://www.ft.com/markets?format=rss"),
    FuenteNoticias("NYT Business", "https://rss.nytimes.com/services/xml/rss/nyt/Business.xml"),
    FuenteNoticias("Bloomberg Markets", "https://feeds.bloomberg.com/markets/news.rss")
)
