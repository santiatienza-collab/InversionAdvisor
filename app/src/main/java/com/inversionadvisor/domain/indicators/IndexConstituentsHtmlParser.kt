package com.inversionadvisor.domain.indicators

import org.jsoup.Jsoup

data class IndexConstituentRow(
    val ticker: String,
    val company: String,
    /** Solo presentes si la tabla usa taxonomía GICS (S&P 500, Nasdaq-100). */
    val gicsSector: String?,
    val gicsSubIndustry: String?,
    /** Solo presente si la tabla usa una etiqueta de sector simple (IBEX 35). */
    val simpleSector: String?
)

/**
 * Parsea la tabla de constituyentes de un índice a partir del HTML de su
 * página fuente (Wikipedia para S&P 500/IBEX 35, slickcharts.com para
 * Nasdaq-100 — ver comentario de StockUniverseRepository sobre por qué
 * Nasdaq-100 usa una fuente distinta). El selector de tabla es
 * configurable porque cada sitio marca la tabla de forma distinta
 * (Wikipedia: `table.wikitable`; slickcharts: `table` a secas), pero el
 * resto de la lógica es la misma para cualquier fuente: cabecera con
 * columnas de ticker y de compañía, más un mínimo razonable de filas de
 * datos para no confundirla con tablas secundarias de la misma página
 * (en Wikipedia, tablas de "cambios históricos"; en slickcharts, la
 * tablita de ETFs de referencia).
 */
object IndexConstituentsHtmlParser {

    private val TICKER_HEADERS = setOf("symbol", "ticker", "ticker symbol")
    private val COMPANY_HEADERS = setOf("security", "company")
    private const val MIN_DATA_ROWS = 20

    fun parse(html: String, tableSelector: String = "table.wikitable"): List<IndexConstituentRow> {
        val doc = Jsoup.parse(html)
        val tables = doc.select(tableSelector)

        for (table in tables) {
            val headerRow = table.select("tr").firstOrNull { it.select("th").isNotEmpty() } ?: continue
            val headersLower = headerRow.select("th").map { it.text().trim().lowercase() }

            val tickerIndex = headersLower.indexOfFirst { it in TICKER_HEADERS }
            val companyIndex = headersLower.indexOfFirst { it in COMPANY_HEADERS }
            if (tickerIndex == -1 || companyIndex == -1) continue

            val gicsSectorIndex = headersLower.indexOfFirst { it == "gics sector" }
            val gicsSubIndustryIndex = headersLower.indexOfFirst { it == "gics sub-industry" }
            val simpleSectorIndex = headersLower.indexOfFirst { it == "sector" }

            val dataRows = table.select("tr")
                .filter { row -> row.select("td").size > maxOf(tickerIndex, companyIndex) }

            if (dataRows.size < MIN_DATA_ROWS) continue // no parece la tabla principal de constituyentes

            return dataRows.mapNotNull { row ->
                val cells = row.select("td")
                val ticker = cells.getOrNull(tickerIndex)?.text()?.trim().orEmpty()
                val company = cells.getOrNull(companyIndex)?.text()?.trim().orEmpty()
                if (ticker.isBlank() || company.isBlank()) return@mapNotNull null
                IndexConstituentRow(
                    ticker = ticker,
                    company = company,
                    gicsSector = gicsSectorIndex.takeIf { it >= 0 }?.let { cells.getOrNull(it)?.text()?.trim() },
                    gicsSubIndustry = gicsSubIndustryIndex.takeIf { it >= 0 }?.let { cells.getOrNull(it)?.text()?.trim() },
                    simpleSector = simpleSectorIndex.takeIf { it >= 0 }?.let { cells.getOrNull(it)?.text()?.trim() }
                )
                // .distinctBy(ticker): por si la tabla trae el mismo símbolo repetido (nota al
                // pie con el ticker duplicado, fila de cambios históricos con las mismas
                // columnas, etc.) — se queda con la primera aparición. Esta es la explicación
                // más probable de los resultados repetidos que se veían en el screener: si un
                // símbolo estaba dos veces en el universo, salía dos veces escaneado y dos
                // veces en la lista de resultados.
            }.distinctBy { it.ticker }
        }
        throw IllegalStateException("No se encontró la tabla de constituyentes en el HTML recibido")
    }
}
