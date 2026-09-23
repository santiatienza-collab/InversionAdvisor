package com.inversionadvisor.domain.indicators

import org.jsoup.Jsoup

/**
 * NUEVO — pedido expresamente ("solución alternativa definitiva a Ingresos Netos, Yahoo falla
 * mucho aquí"): segunda fuente INDEPENDIENTE de Yahoo para "Ingresos netos" — stockanalysis.com,
 * que cubre bolsas de EE.UU. (a diferencia de Investing.com, que en esta app solo tiene slugs
 * mapeados para el IBEX 35 — ver IbexInvestingSlugs.kt). No es una solución "definitiva" en el
 * sentido de infalible (sigue siendo scraping, sin API de pago no hay garantía absoluta), pero al
 * ser un sitio distinto con su propia infraestructura, es MUY improbable que falle en el mismo
 * momento que Yahoo — cuando Yahoo bloquea la sesión (página "Oops, something went wrong"),
 * stockanalysis.com normalmente responde con normalidad, y viceversa.
 *
 * Mismo espíritu que YahooFinancialsParser: texto plano de la página ya renderizada, buscando la
 * etiqueta de la fila de ingresos netos y extrayendo los últimos [count] valores numéricos cerca.
 * stockanalysis.com muestra los años en columnas de la más RECIENTE a la más ANTIGUA (al revés
 * que Yahoo) — importante para no invertir accidentalmente "año en curso" y "hace dos años".
 */
object StockAnalysisFinancialsParser {

    private val netIncomeLabels = listOf("Net Income", "Net Income Common")

    /**
     * Los últimos [count] años de ingresos netos, de la más ANTIGUA a la más RECIENTE (mismo
     * orden de salida que YahooFinancialsParser.parseRecentAnnualNetIncomes, para que
     * MarketRepository pueda tratarlas de forma intercambiable) — lista vacía si no se encuentra
     * la fila.
     */
    fun parseRecentAnnualNetIncomes(html: String, count: Int = 3): List<Double> {
        val text = Jsoup.parse(html).text()
        for (label in netIncomeLabels) {
            var searchFrom = 0
            while (true) {
                val index = text.indexOf(label, searchFrom, ignoreCase = true)
                if (index < 0) break
                val after = text.substring(index + label.length, minOf(text.length, index + label.length + 250))

                // Formato abreviado con sufijo T/B/M/K, igual que Yahoo — y paréntesis para
                // negativos, "(123.4M)", convención habitual en tablas financieras.
                val abbreviated = Regex("""\(?(-?[0-9]+\.?[0-9]*)\)?\s*([TBMK])\b""").findAll(after).toList()
                if (abbreviated.size >= 2) {
                    // stockanalysis.com: más RECIENTE primero (izquierda) — se invierte para
                    // devolver de más antigua a más reciente, como espera MarketRepository.
                    val values = abbreviated.take(count).reversed().mapNotNull { match ->
                        val raw = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                        val negativo = after.substring(
                            (match.range.first - 1).coerceAtLeast(0),
                            match.range.first.coerceAtLeast(0).coerceAtMost(after.length)
                        ).contains("(")
                        val multiplier = when (match.groupValues[2]) {
                            "T" -> 1e12; "B" -> 1e9; "M" -> 1e6; "K" -> 1e3; else -> 1.0
                        }
                        (if (negativo && raw > 0) -raw else raw) * multiplier
                    }
                    if (values.isNotEmpty()) return values
                }
                searchFrom = index + label.length
            }
        }
        return emptyList()
    }

    /** Mismo espíritu que YahooFinancialsParser.isErrorPage/InvestingFinancialsParser — página
     *  de bloqueo/captcha/error en vez de la tabla real. */
    fun isBlockedOrErrorPage(html: String): Boolean {
        val text = Jsoup.parse(html).text()
        return text.contains("Access Denied", ignoreCase = true) ||
            text.contains("Just a moment", ignoreCase = true) || // interstitial típico de Cloudflare
            text.contains("verify you are human", ignoreCase = true)
    }
}
