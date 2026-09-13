package com.inversionadvisor.domain.indicators

import org.jsoup.Jsoup

/** Una fila de la tabla histórica de sentimiento de AAII. */
data class AaiiSentimentRow(
    val reportedDateLabel: String,
    val bullishPercent: Double,
    val neutralPercent: Double,
    val bearishPercent: Double
)

/**
 * Parsea la tabla HTML de resultados históricos de la Encuesta de Sentimiento
 * de AAII (aaii.com/sentimentsurvey/sent_results) — busca la tabla que tenga
 * "Bullish" y "Bearish" en sus primeras celdas y extrae fecha + 3 porcentajes
 * de cada fila.
 *
 * Este HTML ahora se obtiene vía WebView (ver HiddenWebViewScraper), no con una
 * petición HTTP directa: la página está detrás de un reto anti-bots que exige
 * ejecutar JavaScript, así que una petición HTTP simple recibe una página de
 * bloqueo en vez de la tabla real.
 */
object AaiiSentimentHtmlParser {

    /**
     * Devuelve hasta [maxRows] filas de datos, de más reciente a más
     * antigua. `maxRows = 1` solo necesita el dato actual; un valor mayor
     * sirve para pintar un sparkline de tendencia del spread alcista/
     * bajista, sin ninguna llamada de red extra: la propia página ya trae
     * varias semanas.
     */
    fun parseRecentRows(html: String, maxRows: Int): List<AaiiSentimentRow> {
        val doc = Jsoup.parse(html)

        val table = doc.select("table").firstOrNull { table ->
            val headerText = table.select("th, td").take(6).joinToString(" ") { it.text() }
            headerText.contains("Bullish", ignoreCase = true) &&
                headerText.contains("Bearish", ignoreCase = true)
        } ?: run {
            val title = doc.title().ifBlank { "(sin título)" }
            val snippet = doc.body()?.text()?.take(200) ?: "(sin cuerpo)"
            throw IllegalStateException(
                "AAII: no se encontró la tabla de sentimiento en el HTML recibido — título: \"$title\", inicio del texto: \"$snippet\""
            )
        }

        val rows = table.select("tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 4) return@mapNotNull null
            val dateLabel = cells[0].text().trim()
            val bullish = cells[1].text().replace("%", "").trim().toDoubleOrNull()
            val neutral = cells[2].text().replace("%", "").trim().toDoubleOrNull()
            val bearish = cells[3].text().replace("%", "").trim().toDoubleOrNull()
            if (bullish == null || neutral == null || bearish == null) return@mapNotNull null
            AaiiSentimentRow(dateLabel, bullish, neutral, bearish)
        }

        if (rows.isEmpty()) {
            throw IllegalStateException("AAII: tabla de sentimiento encontrada pero sin filas de datos parseables")
        }
        return rows.take(maxRows)
    }

    fun parseLatestRow(html: String): AaiiSentimentRow = parseRecentRows(html, maxRows = 1).first()
}
