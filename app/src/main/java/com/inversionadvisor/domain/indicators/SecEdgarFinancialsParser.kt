package com.inversionadvisor.domain.indicators

import com.inversionadvisor.data.remote.SecCompanyConceptUnitDto

/**
 * Extrae los ingresos netos anuales (de más antiguo a más reciente) de la respuesta de
 * companyconcept de SEC EDGAR — ver SecEdgarApi. A diferencia de YahooFinancialsParser, aquí no
 * hay HTML que parsear: el filtrado es solo quedarse con los informes anuales correctos y
 * deduplicar restataciones (una misma cifra puede aparecer varias veces si la empresa la volvió
 * a reportar en informes posteriores).
 */
object SecEdgarFinancialsParser {

    /**
     * Tags XBRL a probar, en orden — no todas las empresas usan la misma etiqueta para
     * "ingresos netos": "NetIncomeLoss" es la más común con diferencia, "ProfitLoss" aparece en
     * algunos emisores extranjeros que reportan con taxonomía distinta, y
     * "NetIncomeLossAvailableToCommonStockholdersBasic" es el último recurso para empresas que
     * solo reportan el resultado YA neto de dividendos preferentes. Sin confirmar en vivo la
     * cobertura exacta de cada una — si un símbolo concreto sigue sin datos, la primera sospecha
     * es que usa un tag distinto de estos tres.
     */
    val NET_INCOME_TAGS = listOf(
        "NetIncomeLoss",
        "ProfitLoss",
        "NetIncomeLossAvailableToCommonStockholdersBasic"
    )

    /**
     * De la lista completa de valores reportados de un concepto (todos los trimestres y años,
     * con posibles duplicados por restataciones), se queda solo con los informes ANUALES —
     * "10-K"/"10-K/A" (empresas de EE.UU.) o "20-F"/"20-F/A" (el equivalente anual para
     * "foreign private issuers": empresas extranjeras que cotizan en EE.UU. pero están
     * constituidas fuera, como Nebius Group N.V., holandesa — CORREGIDO tras confirmar con
     * Logcat real que NBIS se quedaba sin datos: la SEC SÍ los tenía, pero el filtro solo
     * aceptaba "10-K" y los descartaba todos en silencio) — y, para cada fecha de cierre de año
     * distinta, con el valor de la presentación MÁS RECIENTE (por si la cifra se restató más
     * tarde — se prefiere el dato corregido al original). Devuelve de más antiguo a más
     * reciente, como el resto de la app espera.
     */
    fun parseRecentAnnualNetIncomes(units: Map<String, List<SecCompanyConceptUnitDto>>?, count: Int = 3): List<Double> {
        val usd = units?.get("USD") ?: return emptyList()
        val annualReports = usd.filter { row ->
            val form = row.form
            form != null && (form.startsWith("10-K") || form.startsWith("20-F")) && row.end != null && row.value != null
        }
        val latestPerFiscalYearEnd = annualReports
            .groupBy { it.end }
            .mapValues { (_, rows) -> rows.maxByOrNull { it.filed ?: "" } }
            .values
            .filterNotNull()
        return latestPerFiscalYearEnd
            .sortedBy { it.end }
            .takeLast(count)
            .mapNotNull { it.value }
    }
}
