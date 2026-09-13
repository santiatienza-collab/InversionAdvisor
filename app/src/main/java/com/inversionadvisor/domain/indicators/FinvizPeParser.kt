package com.inversionadvisor.domain.indicators

import org.jsoup.Jsoup

/**
 * Parsea el PER de las páginas públicas de Finviz.
 */
object FinvizPeParser {

    /**
     * Mapeo de los 11 sectores GICS que usa la tabla de Finviz a los ETFs
     * sectoriales de Symbols.SECTOR_ETFS. Finviz no separa semiconductores
     * (SMH) de tecnología, ni defensa/armamento (ITA) de industrial — para
     * esos dos se usa el PER del sector padre como APROXIMACIÓN (Tecnología
     * para SMH, Industrial para ITA), no el dato específico de ese ETF.
     */
    private val financeGroupToEtf = mapOf(
        "Basic Materials" to "XLB",
        "Communication Services" to "XLC",
        "Consumer Cyclical" to "XLY",
        "Consumer Defensive" to "XLP",
        "Energy" to "XLE",
        "Financial" to "XLF",
        "Healthcare" to "XLV",
        "Industrials" to "XLI",
        "Real Estate" to "XLRE",
        "Technology" to "XLK",
        "Utilities" to "XLU"
    )

    /**
     * Devuelve el PER por ETF sectorial a partir de la tabla de grupos de
     * Finviz — incluye SMH (= PER de Tecnología, aproximado) e ITA (= PER de
     * Industrial, aproximado) además de los 11 sectores con ETF propio.
     */
    fun parseSectorPe(html: String): Map<String, Double> {
        val doc = Jsoup.parse(html)
        val table = doc.select("table").firstOrNull { table ->
            val headerText = table.select("th, td").take(20).joinToString(" ") { it.text() }
            headerText.contains("P/E", ignoreCase = false) && headerText.contains("Name", ignoreCase = true)
        } ?: throw IllegalStateException("Finviz: no se encontró la tabla de PER por sector en el HTML recibido")

        val headerCells = table.select("tr").first()?.select("th, td")?.map { it.text().trim() }
            ?: throw IllegalStateException("Finviz: tabla de sectores sin fila de cabecera")
        val nameColumnIndex = headerCells.indexOfFirst { it.equals("Name", ignoreCase = true) }
        val peColumnIndex = headerCells.indexOfFirst { it.equals("P/E", ignoreCase = true) }
        if (nameColumnIndex < 0 || peColumnIndex < 0) {
            throw IllegalStateException("Finviz: no se encontraron las columnas Name/P-E esperadas (cabecera: $headerCells)")
        }

        val bySectorName = mutableMapOf<String, Double>()
        for (row in table.select("tr").drop(1)) {
            val cells = row.select("td")
            if (cells.size <= maxOf(nameColumnIndex, peColumnIndex)) continue
            val sectorName = cells[nameColumnIndex].text().trim()
            val pe = cells[peColumnIndex].text().trim().toDoubleOrNull() ?: continue
            bySectorName[sectorName] = pe
        }
        if (bySectorName.isEmpty()) {
            throw IllegalStateException("Finviz: tabla de sectores encontrada pero sin filas de datos parseables")
        }

        val result = mutableMapOf<String, Double>()
        for ((sectorName, etf) in financeGroupToEtf) {
            bySectorName[sectorName]?.let { result[etf] = it }
        }
        // Aproximaciones: SMH y ITA no tienen fila propia en Finviz — se usa el
        // PER de su sector padre (Tecnología / Industrial) como sustituto.
        bySectorName["Technology"]?.let { result["SMH"] = it }
        bySectorName["Industrials"]?.let { result["ITA"] = it }
        return result
    }

    /** PER de un stock individual desde la tabla "snapshot" de su página de cotización en Finviz. */
    /**
     * Lee el valor numérico de la celda inmediatamente siguiente a una etiqueta dada, en la
     * tabla-resumen ("snapshot") de un stock en Finviz. Factorizado para reutilizarlo con
     * distintas etiquetas (P/E, Forward P/E...).
     */
    private fun readSnapshotValue(doc: org.jsoup.nodes.Document, label: String): Double? {
        val labelCell = doc.select("td").firstOrNull { it.text().trim().equals(label, ignoreCase = true) }
            ?: return null
        val valueCell = labelCell.nextElementSibling() ?: return null
        return valueCell.text().trim().toDoubleOrNull()
    }

    fun parseStockPe(html: String): Double? {
        val doc = Jsoup.parse(html)
        readSnapshotValue(doc, "P/E")?.let { return it }
        // NUEVO — fallback a Forward P/E, pedido expresamente tras comprobar con log real que
        // Finviz muestra literalmente "P/E -" (guion, sin dato) para stocks con beneficio de los
        // últimos 12 meses negativo o no significativo (caso real: Lumentum) — el parser
        // funcionaba bien, es que no hay trailing P/E que parsear. En su ausencia, el Forward
        // P/E (basado en el beneficio ESTIMADO del año que viene, no en el ya reportado) es una
        // referencia de valoración razonable, mucho mejor que dejar la categoría "Valoración"
        // sin ningún dato. Aviso: esto mezcla trailing y forward P/E bajo un mismo número sin
        // distinguirlo en la interfaz — si se quiere diferenciar visualmente cuál es cuál, habría
        // que propagar un flag "esForward" por todo el flujo (PeRatioEntity, observeStockPe,
        // Top10Calculator...), cambio más invasivo que se dejó fuera de este arreglo puntual.
        return readSnapshotValue(doc, "Forward P/E")
    }

    /**
     * EPS de los últimos 12 meses ("EPS (ttm)" en el snapshot de Finviz) — pedido expresamente
     * para poder distinguir cuándo el trailing P/E no existe porque el beneficio de los últimos
     * 12 meses es NEGATIVO (en vez de, por ejemplo, datos simplemente no disponibles). Caso real:
     * Lumentum.
     */
    fun parseEpsTtm(html: String): Double? {
        val doc = Jsoup.parse(html)
        return readSnapshotValue(doc, "EPS (ttm)")
    }

    /**
     * PER de VARIOS stocks a la vez, desde la tabla del screener filtrado por ticker (ver
     * FinvizApi.getScreenerHtml) — mismo patrón que parseSectorPe (buscar la tabla por sus
     * columnas de cabecera, no asumir nada de la estructura HTML de alrededor), pero indexando
     * por columna "Ticker" en vez de "Name".
     */
    fun parseScreenerPeByTicker(html: String): Map<String, Double> {
        val doc = Jsoup.parse(html)
        val table = doc.select("table").firstOrNull { table ->
            val headerText = table.select("th, td").take(20).joinToString(" ") { it.text() }
            headerText.contains("P/E", ignoreCase = false) && headerText.contains("Ticker", ignoreCase = true)
        } ?: return emptyMap()

        val headerCells = table.select("tr").first()?.select("th, td")?.map { it.text().trim() } ?: return emptyMap()
        val tickerColumnIndex = headerCells.indexOfFirst { it.equals("Ticker", ignoreCase = true) }
        val peColumnIndex = headerCells.indexOfFirst { it.equals("P/E", ignoreCase = true) }
        if (tickerColumnIndex < 0 || peColumnIndex < 0) return emptyMap()

        val result = mutableMapOf<String, Double>()
        for (row in table.select("tr").drop(1)) {
            val cells = row.select("td")
            if (cells.size <= maxOf(tickerColumnIndex, peColumnIndex)) continue
            val ticker = cells[tickerColumnIndex].text().trim()
            val pe = cells[peColumnIndex].text().trim().toDoubleOrNull() ?: continue
            if (ticker.isNotEmpty()) result[ticker] = pe
        }
        return result
    }
}
