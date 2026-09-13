package com.inversionadvisor.domain.indicators

import org.jsoup.Jsoup

/**
 * Extrae PER, Valor Empresa (Enterprise Value) e Ingresos Netos (TTM) del
 * HTML de finance.yahoo.com/quote/SYMBOL — verificado contra el texto real
 * de la página en español para AMZN, que muestra (entre otros):
 * "Capitalización de mercado · 2.83T · Valor de empresa · 2.93T · P/E
 * últimos 12 meses · 21.13 · ... Ingresos netos disponibles (ttm) 135,28MM".
 *
 * Extracción por TEXTO PLANO de la página (doc.text()), no navegando el DOM
 * en busca de "el elemento hermano" — la primera versión de este parser
 * intentaba eso y fallaba sistemáticamente porque la estructura real no
 * encajaba con lo asumido. Buscar la etiqueta en el texto y capturar el
 * número que viene justo después es más robusto frente a cómo esté montada
 * la maquetación exacta.
 *
 * "Valor Empresa" en la app = "Valor de empresa" de Yahoo (Enterprise
 * Value), que es un campo DISTINTO de la capitalización de mercado — más
 * fiel a lo que se pidió, así que es el que se usa aquí.
 */
object YahooQuotePageParser {

    private val jsonRawPeRegex = Regex(""""trailingPE"\s*:\s*\{\s*"raw"\s*:\s*(-?[0-9]+\.?[0-9]*)""")

    private val trailingPeLabels = listOf("P/E últimos 12 meses", "PE Ratio (TTM)", "Precio/utilidad (últimos 12 meses)")
    private val enterpriseValueLabels = listOf("Valor de empresa", "Enterprise Value")
    private val marketCapLabels = listOf("Capitalización de mercado", "Cap. bursátil", "Market Cap")
    private val netIncomeTtmLabels = listOf("Ingresos netos disponibles", "Net Income Avi to Common", "Ingresos netos")
    // CONFIRMADO contra el registro real que mandaste — "Resultados de ganancias" era otra
    // parte de la página (un banner, no el dato en sí); la etiqueta real de la tabla de
    // estadísticas es "Earnings Date", EN INGLÉS tal cual, aunque el resto de la página esté en
    // español — igual que pasa con "P/E" (ver trailingPeLabels), que también se queda en
    // inglés. Texto real visto: "Earnings Date Oct 21, 2026".
    private val earningsDateLabels = listOf("Earnings Date")

    fun parseTrailingPe(html: String): Double? {
        jsonRawPeRegex.find(html)?.let { match ->
            return match.groupValues[1].toDoubleOrNull()
        }
        return findNumberAfterLabel(html, trailingPeLabels)
    }

    /** "Valor Empresa" en la app — ver comentario de cabecera. Si no aparece, cae a capitalización de mercado. */
    fun parseMarketCap(html: String): Double? {
        return findAbbreviatedNumberAfterLabel(html, enterpriseValueLabels)
            ?: findAbbreviatedNumberAfterLabel(html, marketCapLabels)
    }

    /** Ingresos netos de los últimos 12 meses (TTM) — no es la misma cifra que "anual" (año fiscal cerrado) ni "trimestral", pero es un dato real disponible en esta misma página sin coste extra. */
    fun parseNetIncomeTtm(html: String): Double? =
        findAbbreviatedNumberAfterLabel(html, netIncomeTtmLabels)

    /**
     * Fecha/hora de la próxima publicación de resultados trimestrales, CONFIRMADO contra la
     * página real (búsqueda web) — importante: Yahoo NO siempre muestra una fecha con día/mes/
     * año. El formato real varía:
     * Fecha de la próxima publicación de resultados trimestrales — CONFIRMADO contra un
     * registro real (logcat, con la etiqueta "Earnings Date" y el valor "Oct 21, 2026" tal
     * cual): la tabla de estadísticas de Yahoo usa "Earnings Date" en inglés, con el mes
     * abreviado en inglés + día + coma + año ("Oct 21, 2026"), a veces con rango
     * ("Oct 21 - Oct 26, 2026"). Se captura ese patrón exacto, no una ventana de texto libre.
     */
    private val earningsDateValueRegex = Regex(
        """[A-Za-z]{3,9}\.?\s+\d{1,2}(?:\s*[-–—]\s*[A-Za-z]{3,9}\.?\s+\d{1,2})?,\s*\d{4}"""
    )

    fun parseEarningsDateText(html: String): String? {
        val text = Jsoup.parse(html).text()
        for (label in earningsDateLabels) {
            val index = text.indexOf(label, ignoreCase = true)
            if (index < 0) continue
            val after = text.substring(index + label.length).trim()
            if (after.isEmpty()) continue
            earningsDateValueRegex.find(after.take(60))?.let { return it.value.trim() }
        }
        return null
    }

    /**
     * Diagnóstico — SOLO para cuando parseEarningsDateText() no encuentra nada con las
     * etiquetas conocidas (que llevan sin verificar contra una página real desde el principio,
     * a diferencia de PER/Valor Empresa/Ingresos netos). Busca "resultados" o "earnings" en
     * CUALQUIER parte del texto, sin asumir la etiqueta exacta, y devuelve el trozo de texto
     * alrededor de cada aparición — para poder ver el texto REAL que usa la página (vía logcat,
     * etiqueta "CompanyFinancialsFetch") y ajustar la etiqueta con precisión, en vez de seguir
     * adivinando a ciegas.
     */
    fun findEarningsRelatedTextForDebug(html: String): List<String> {
        val text = Jsoup.parse(html).text()
        val keywords = listOf("resultados", "earnings")
        val windows = mutableListOf<String>()
        for (keyword in keywords) {
            var searchFrom = 0
            while (windows.size < 6) {
                val index = text.indexOf(keyword, searchFrom, ignoreCase = true)
                if (index < 0) break
                val start = maxOf(0, index - 20)
                val end = minOf(text.length, index + 80)
                windows.add(text.substring(start, end))
                searchFrom = index + keyword.length
            }
        }
        return windows
    }

    /**
     * Busca [labels] en el TEXTO PLANO de la página y captura el primer número
     * (con posible sufijo T/B/M/K, o "MM" a la española) que aparece justo
     * después — sin asumir nada sobre la estructura HTML de alrededor.
     */
    private fun findAbbreviatedNumberAfterLabel(html: String, labels: List<String>): Double? {
        val text = Jsoup.parse(html).text()
        for (label in labels) {
            val index = text.indexOf(label, ignoreCase = true)
            if (index < 0) continue
            val after = text.substring(index + label.length)
            val match = Regex("""^[^0-9\-]{0,15}(-?[0-9][0-9.,]*)\s*([TBMK]{1,2})?""").find(after) ?: continue
            val number = parseLocaleAwareNumber(match.groupValues[1]) ?: continue
            val suffix = match.groupValues[2].uppercase()
            val multiplier = when (suffix) {
                "T" -> 1e12
                "B" -> 1e9
                "MM" -> 1e9 // notación española de "miles de millones"
                "M" -> 1e6
                "K" -> 1e3
                else -> 1.0
            }
            return number * multiplier
        }
        return null
    }

    private fun findNumberAfterLabel(html: String, labels: List<String>): Double? {
        val text = Jsoup.parse(html).text()
        for (label in labels) {
            val index = text.indexOf(label, ignoreCase = true)
            if (index < 0) continue
            val after = text.substring(index + label.length)
            val match = Regex("""^[^0-9\-]{0,15}(-?[0-9][0-9.,]*)""").find(after) ?: continue
            return parseLocaleAwareNumber(match.groupValues[1])
        }
        return null
    }

    /**
     * CORREGIDO — SOSPECHA a raíz de "no se ve el PER" para stocks del IBEX 35 (.MC): el
     * parseo anterior solo entendía el formato estadounidense (quitaba comas y dejaba el punto
     * como decimal), verificado únicamente contra AMZN en la versión en ESPAÑOL de Yahoo (ver
     * comentario de cabecera), que muestra los NÚMEROS en formato estadounidense pese a tener
     * las ETIQUETAS traducidas. Si Yahoo presenta los números de un ticker .MC en formato
     * español de verdad ("21,13" en vez de "21.13"), el parser anterior lo leía como "2113" —
     * un valor que cualquier filtro de PER razonable descarta como absurdo, y de ahí que no se
     * mostrara nada.
     *
     * SIN CONFIRMAR EN VIVO todavía contra un .MC real — si el problema persiste tras este
     * cambio, el siguiente paso es loguear el texto crudo capturado (como ya hace
     * YahooFinancialsParser.debugAllLabelOccurrences) para ver el formato real recibido.
     *
     * Soporta tanto "1,234.56" (coma de millares, punto decimal) como "1.234,56" (punto de
     * millares, coma decimal): el separador que aparece MÁS A LA DERECHA se trata como decimal.
     */
    private fun parseLocaleAwareNumber(raw: String): Double? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null

        val lastComma = trimmed.lastIndexOf(',')
        val lastDot = trimmed.lastIndexOf('.')

        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> {
                if (lastComma > lastDot) {
                    trimmed.replace(".", "").replace(',', '.')
                } else {
                    trimmed.replace(",", "")
                }
            }
            lastComma >= 0 -> {
                // Solo coma: decimal si quedan 1-2 dígitos tras ella ("21,13"), separador de
                // millares si quedan exactamente 3 ("21,130").
                val decimalsAfter = trimmed.length - lastComma - 1
                if (decimalsAfter in 1..2) trimmed.replace(',', '.') else trimmed.replace(",", "")
            }
            lastDot >= 0 -> {
                // Solo punto: decimal salvo que haya más de un punto o queden exactamente 3
                // dígitos tras el último (separador de millares europeo, "2.834" = 2834).
                val dotCount = trimmed.count { it == '.' }
                val digitsAfterLastDot = trimmed.length - lastDot - 1
                if (dotCount > 1 || digitsAfterLastDot == 3) trimmed.replace(".", "") else trimmed
            }
            else -> trimmed
        }
        return normalized.toDoubleOrNull()
    }
}
