package com.inversionadvisor.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Endpoints de Financial Modeling Prep (financialmodelingprep.com) que usa la app.
 * Documentación: https://site.financialmodelingprep.com/developer/docs/stable/income-statement
 *
 * CORREGIDO — la app usaba el endpoint LEGACY (api/v3/income-statement/{symbol}), que FMP tiene
 * en fase de retirada (su propia documentación avisa: "la jubilación completa de esta
 * documentación se espera en 2025/2026", y ya estamos en esa ventana) — confirmado con la
 * documentación en vivo que el reemplazo "stable" cambia de ruta Y de forma de mandar el
 * símbolo: ya NO va en la URL (.../income-statement/AAPL) sino como parámetro de consulta
 * (.../income-statement?symbol=AAPL). Con la ruta antigua, lo más probable es que la API
 * respondiera con error o vacío de forma silenciosa — la causa más plausible de que no
 * aparecieran los ingresos netos en "Datos de Empresa" tras la primera migración.
 *
 * SUSTITUYE al scraping de la página de estados financieros de Yahoo
 * (finance.yahoo.com/quote/SYMBOL/financials, ver YahooFinancialsParser, ya retirado) para los
 * ingresos netos de "Datos de Empresa" — pedido expresamente así: JSON limpio y estructurado en
 * UNA petición HTTP normal, sin WebView, sin parsear HTML, sin reintentos de varios segundos
 * cada uno.
 *
 * Mismo formato de símbolo que Yahoo/el resto de la app (p. ej. "AAPL", "REP.MC" para el IBEX
 * 35 con el sufijo de la Bolsa de Madrid) — FMP usa los mismos sufijos de mercado que Yahoo para
 * la mayoría de bolsas internacionales, así que el símbolo se manda tal cual, sin conversión
 * (a diferencia de Twelve Data, que necesita quitar el ".MC" y mandar un mic_code aparte). SIN
 * CONFIRMAR EN VIVO para el IBEX 35 en concreto — si algún símbolo no diera datos, la primera
 * sospecha es un sufijo de mercado distinto al esperado, revisar con el símbolo exacto que use
 * la app en Logcat (etiqueta "CompanyFinancialsFetch").
 */
interface FinancialModelingPrepApi {

    /**
     * Income statement anual — se pide con limit=3 para traer directamente los 3 años más
     * recientes (el más reciente primero, al revés que la tabla de Yahoo que se leía de más
     * antiguo a más reciente). El plan gratuito de FMP cubre este endpoint para EE.UU.; la
     * cobertura de otras bolsas (como el IBEX 35) depende del plan contratado — sin confirmar
     * en vivo con una clave real.
     */
    @GET("income-statement")
    suspend fun getAnnualIncomeStatement(
        @Query("symbol") symbol: String,
        @Query("period") period: String = "annual",
        @Query("limit") limit: Int = 3,
        @Query("apikey") apiKey: String
    ): List<FmpIncomeStatementDto>
}
