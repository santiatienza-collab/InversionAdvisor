package com.inversionadvisor.domain.indicators

/**
 * Traduce el "GICS Sector"/"GICS Sub-Industry" que usan las tablas de
 * Wikipedia de S&P 500 y Nasdaq-100 al ETF sectorial de Symbols.SECTOR_ETFS,
 * separando semiconductores y defensa/armamento (como se pidió) a partir de
 * la sub-industria, ya que GICS no los trata como sectores de primer nivel.
 */
object GicsSectorMapper {
    fun mapToEtf(gicsSector: String, gicsSubIndustry: String?): String {
        val sub = (gicsSubIndustry ?: "").lowercase()
        return when (gicsSector.trim()) {
            "Information Technology" -> if ("semiconductor" in sub) "SMH" else "XLK"
            "Communication Services" -> "XLC"
            "Energy" -> "XLE"
            "Financials" -> "XLF"
            "Industrials" -> if ("aerospace" in sub || "defense" in sub) "ITA" else "XLI"
            "Health Care" -> "XLV"
            "Consumer Staples" -> "XLP"
            "Consumer Discretionary" -> "XLY"
            "Utilities" -> "XLU"
            "Materials" -> "XLB"
            "Real Estate" -> "XLRE"
            else -> "XLK"
        }
    }
}

/**
 * El IBEX 35 no usa taxonomía GICS en Wikipedia, solo una etiqueta de
 * sector informal ("Construction", "Financial Services", "Oil and Gas"...).
 * Mapeo por palabras clave a falta de una clasificación estándar.
 */
object SimpleSectorMapper {
    fun mapToEtf(sectorLabel: String): String {
        val s = sectorLabel.lowercase()
        return when {
            "bank" in s || "financial" in s || "insurance" in s -> "XLF"
            "real estate" in s || "reit" in s -> "XLRE"
            "solar" in s || "renewable" in s || "utilit" in s || "electric" in s -> "XLU"
            "oil" in s || "gas" in s -> "XLE"
            "energy" in s -> "XLU" // p.ej. eléctricas ("Energy" genérico en la tabla) -> utility
            "telecommunication" in s || "communication" in s -> "XLC"
            "pharma" in s || "health" in s || "biotech" in s -> "XLV"
            "semiconductor" in s -> "SMH"
            "technology" in s || "software" in s -> "XLK"
            "steel" in s || "material" in s || "mining" in s || "chemical" in s -> "XLB"
            "textile" in s || "cloth" in s || "cosmetic" in s || "tourism" in s -> "XLY"
            "staple" in s || "food" in s || "beverage" in s -> "XLP"
            "aviation" in s || "airline" in s || "defense" in s || "aerospace" in s -> "ITA"
            else -> "XLI" // construcción, infraestructura, industrial, logística, manufactura...
        }
    }
}
