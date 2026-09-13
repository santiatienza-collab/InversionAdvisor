package com.inversionadvisor.domain.indicators

import org.jsoup.Jsoup

/**
 * Extrae ingresos netos de Investing.com — respaldo para el IBEX 35 (.MC): SEC EDGAR no
 * cubre empresas no estadounidenses (ver SecEdgarFinancialsParser) y el scraping de Yahoo
 * (YahooFinancialsParser) puede fallar o venir vacío para estos símbolos.
 *
 * CORREGIDO — CONFIRMADO mirando la página real (acerinox-financial-summary): la tabla
 * "Net Income" de la página de resumen financiero (.../equities/{slug}-financial-summary)
 * está BLOQUEADA tras el plan de pago "Pro" de Investing.com — las celdas muestran
 * literalmente "aa.aa" con un enlace "Unlock", no números reales, para NINGÚN valor. No es
 * un fallo de parseo: esos datos concretos no están disponibles gratis en esa página.
 *
 * En su lugar, se usa la página de "Income Statement" (.../equities/{slug}-income-statement),
 * que SÍ publica en texto plano (sin paywall) la cifra del ÚLTIMO AÑO FISCAL COMPLETO como
 * parte de un bloque de preguntas frecuentes SEO — confirmado el mismo patrón de texto para
 * varias empresas de distintos países/divisas ("For the full year, {empresa} reported net
 * income of {cifra} million {divisa}."). Esto da COMO MUCHO 1 año (el último cerrado), no 3
 * — sensiblemente peor que el objetivo original, pero es lo único verificable gratis en este
 * sitio; el resto de "Datos de Empresa" sigue funcionando igual con lo que ya haya.
 */
object InvestingFinancialsParser {

    /**
     * Único valor real y gratuito disponible en Investing.com para ingresos netos anuales: el
     * del último año fiscal cerrado, sacado del texto de preguntas frecuentes de la página de
     * Income Statement. Devuelve null si el patrón de texto no aparece (página cambiada, o
     * empresa sin esta sección).
     */
    fun parseFullYearNetIncomeFromFaq(html: String): Double? {
        val text = Jsoup.parse(html).text()
        val indice = text.indexOf("full year", ignoreCase = true)
        if (indice < 0) return null
        val ventana = text.substring(indice, minOf(text.length, indice + 200))
        val match = Regex(
            """net income of (-?[0-9]{1,3}(?:,[0-9]{3})*\.[0-9]+)\s*(billion|million)""",
            RegexOption.IGNORE_CASE
        ).find(ventana) ?: return null
        val numero = match.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        val multiplicador = if (match.groupValues[2].equals("billion", ignoreCase = true)) 1_000_000_000.0 else 1_000_000.0
        return numero * multiplicador
    }

    fun isBlockedOrErrorPage(html: String): Boolean {
        val text = Jsoup.parse(html).text()
        return text.contains("Attention Required", ignoreCase = true) ||
            text.contains("Access Denied", ignoreCase = true) ||
            text.contains("Just a moment", ignoreCase = true) // pantalla de challenge de Cloudflare
    }
}
