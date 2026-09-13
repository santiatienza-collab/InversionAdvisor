package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Titular
import org.jsoup.Jsoup
import org.jsoup.parser.Parser

/**
 * Parsea el XML de un feed RSS estándar (<item><title>/<link>/<pubDate>) a la lista de
 * Titular del dominio, clasificando el sentimiento de cada uno con NewsSentimentClassifier.
 * Mismo patrón que el resto de parsers de la app (Jsoup por texto/estructura, sin asumir
 * más de lo necesario sobre el HTML/XML de alrededor).
 */
object NewsRssParser {

    fun parse(xml: String, limite: Int = 15): List<Titular> {
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val items = doc.select("item")

        return items.take(limite).mapNotNull { item ->
            val titulo = item.selectFirst("title")?.text()
            if (titulo.isNullOrBlank()) return@mapNotNull null

            Titular(
                texto = titulo,
                url = item.selectFirst("link")?.text().orEmpty(),
                fecha = item.selectFirst("pubDate")?.text(),
                sentimiento = NewsSentimentClassifier.clasificar(titulo)
            )
        }
    }
}
