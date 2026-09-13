package com.inversionadvisor.domain.indicators

import org.jsoup.Jsoup

/**
 * Extrae "Ingresos netos" (Net Income) del HTML de la página de estados
 * financieros de Yahoo (finance.yahoo.com/quote/SYMBOL/financials).
 *
 * RESTAURADO — la app pasó brevemente por Financial Modeling Prep (ver commit anterior), pero
 * su endpoint de income-statement devuelve HTTP 402 (Payment Required) en el plan gratuito: ya
 * no está disponible sin pagar. Se vuelve a este scraping de Yahoo, a petición expresa,
 * afinando la estrategia de reintento para que sea lo más rápido y fiable posible dentro de lo
 * que da de sí un scraping (ver MarketRepository.refreshCompanyFinancialsInternal).
 *
 * CONFIRMADO con datos reales de Logcat para AMZN (etiqueta
 * "CompanyFinancialsFetch"), que descartó las dos suposiciones anteriores:
 *  - La página real está en INGLÉS ("Net Income Common Stockholders"), no en
 *    español — a diferencia de la página de cotización (PER/Valor Empresa),
 *    que sí venía en español. Se aceptan ambas etiquetas por si acaso.
 *  - Los números NO vienen "en miles" con separador de punto/coma
 *    ("90.798.000") como se asumió — vienen YA ABREVIADOS con sufijo T/B/M/K
 *    directamente en el texto: "-2.72B 30.43B 59.25B 77.67B 135.28B". El
 *    punto ahí es un decimal de verdad (2,72 mil millones), no un separador
 *    de miles — por eso multiplicar por 1.000 después de quitar el punto
 *    daba cifras absurdas como "-272.000" en vez de "-2.720.000.000".
 *  - Las 5 cifras de esa fila van de la más ANTIGUA a la más RECIENTE
 *    (izquierda a derecha) — la última (135.28B) es el TTM (últimos 12
 *    meses, no necesariamente un año fiscal cerrado). Para mostrar 3 años
 *    consecutivos hasta el actual se cogen las 3 últimas columnas de esa
 *    fila: TTM (año en curso), la anterior (año anterior) y la de antes
 *    (hace dos años).
 */
object YahooFinancialsParser {

    private val netIncomeLabels = listOf(
        "Net Income Common Stockholders",
        "Ingresos netos por accionistas ordinarios"
    )

    /**
     * Las últimas [count] columnas de la fila de ingresos netos, de la más
     * ANTIGUA a la más RECIENTE (es decir, el último elemento de la lista es
     * el año en curso/TTM) — con su sufijo T/B/M/K ya expandido a un número
     * real. Lista vacía si no se encuentra la fila en ninguna aparición de la
     * etiqueta.
     *
     * Recorre TODAS las apariciones de la etiqueta en la página, no solo la
     * primera — puede haber una aparición sin números cerca (p. ej. una
     * cabecera de columnas) y los datos de verdad en OTRA aparición más abajo.
     *
     * CORREGIDO — encontrado con Logcat real de ADBE: Yahoo NO SIEMPRE pinta esta tabla en el
     * mismo formato. A veces sale abreviada con letra ("7.23B"), pero OTRAS veces (visto en un
     * segundo intento real, con la etiqueta encontrada y datos de verdad al lado) sale como
     * número completo con comas de millar, "en miles" ("7,229,000" = 7.229 mil millones) — y el
     * analizador solo sabía leer el primer formato, así que en esos casos no encontraba nada
     * aunque los datos SÍ estaban en la página. Ahora, si el formato abreviado no da resultado,
     * se prueba también con números completos con comas (interpretados "en miles", como es
     * habitual en los estados financieros) antes de rendirse.
     */
    fun parseRecentAnnualNetIncomes(html: String, count: Int = 3): List<Double> {
        val text = Jsoup.parse(html).text()
        for (label in netIncomeLabels) {
            var searchFrom = 0
            while (true) {
                val index = text.indexOf(label, searchFrom, ignoreCase = true)
                if (index < 0) break
                val after = text.substring(index + label.length, minOf(text.length, index + label.length + 200))

                val abbreviated = Regex("""(-?[0-9]+\.?[0-9]*)\s*([TBMK])\b""").findAll(after).toList()
                if (abbreviated.size >= 2) { // con 1 sola columna no hay forma de distinguirla de una cabecera suelta
                    val values = abbreviated.takeLast(count).mapNotNull { match ->
                        val value = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
                        val multiplier = when (match.groupValues[2]) {
                            "T" -> 1e12
                            "B" -> 1e9
                            "M" -> 1e6
                            "K" -> 1e3
                            else -> 1.0
                        }
                        value * multiplier
                    }
                    if (values.isNotEmpty()) return values
                }

                // Formato alternativo — número completo con comas de millar, "en miles"
                // ("7,229,000" -> 7.229.000.000, es decir ×1.000). Se exige al menos 2 grupos de
                // coma (p. ej. "7,229,000") para no confundir con un número corto sin formatear.
                val commaFormatted = Regex("""(-?[0-9]{1,3}(?:,[0-9]{3}){2,})\b""").findAll(after).toList()
                if (commaFormatted.size >= 2) {
                    val values = commaFormatted.takeLast(count).mapNotNull { match ->
                        match.groupValues[1].replace(",", "").toDoubleOrNull()?.times(1000.0)
                    }
                    if (values.isNotEmpty()) return values
                }

                searchFrom = index + label.length
            }
        }
        return emptyList()
    }

    /**
     * Detecta la página de error GENÉRICA de Yahoo ("Oops, something went wrong") — confirmado
     * con Logcat real que aparece de forma CONSISTENTE para varios símbolos seguidos (no
     * puntual), señal de que la sesión/IP está temporalmente bloqueada por Yahoo, no de que la
     * tabla tarde en pintarse. A diferencia de una tabla vacía por timing (donde SÍ tiene
     * sentido reintentar con más espera de renderizado), reintentar contra ESTA página no
     * ayuda — solo prolonga el bloqueo y satura el sistema con peticiones que no tienen ninguna
     * posibilidad real de éxito. Ver el cortacircuitos en
     * MarketRepository.refreshCompanyFinancialsInternal.
     */
    fun isErrorPage(html: String): Boolean {
        val text = Jsoup.parse(html).text()
        return text.contains("Oops, something went wrong", ignoreCase = true)
    }

    /**
     * Solo para diagnóstico: TODAS las apariciones de la etiqueta en la página
     * (no solo la primera), cada una con ~150 caracteres de contexto tras ella
     * — para ver de un vistazo si hay una aparición con números reales y otra
     * sin ellos (p. ej. una cabecera de tabla vs la fila de datos de verdad),
     * en vez de quedarnos solo con la primera.
     */
    fun debugAllLabelOccurrences(html: String): List<String> {
        val text = Jsoup.parse(html).text()
        val results = mutableListOf<String>()
        for (label in netIncomeLabels) {
            var searchFrom = 0
            while (true) {
                val index = text.indexOf(label, searchFrom, ignoreCase = true)
                if (index < 0) break
                val after = text.substring(index + label.length, minOf(text.length, index + label.length + 150))
                results += "[$label @$index] → \"$after\""
                searchFrom = index + label.length
                if (results.size >= 6) return results // límite de seguridad, no hace falta más para diagnosticar
            }
        }
        return results
    }
}
