package com.inversionadvisor.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Finviz (finviz.com), fuente GRATUITA sin API key para el PER — confirmado
 * accesible sin bloqueo (probado en vivo: la tabla de PER por sector devuelve
 * datos reales, no una página de aviso/bloqueo). Se usa tras varios intentos
 * fallidos con Yahoo (401 en su API, sin datos en su HTML ni siquiera vía
 * WebView) y Twelve Data (403, fundamentales solo en plan de pago).
 *
 * getSectorGroupsHtml: UNA sola petición trae el PER de los 11 sectores GICS
 * de golpe (ver FinvizSectorPeParser) — mucho más simple que pedir cada ETF
 * sectorial por separado.
 *
 * getStockQuoteHtml: PER de un stock individual. Sin verificar en vivo si
 * esta página concreta también está accesible sin bloqueo (un intento de
 * comprobarlo directamente devolvió 404, aunque la página aparece indexada
 * por buscadores) — por eso en MarketRepository se usa como primer intento
 * con el WebView de Yahoo como respaldo, no como única vía.
 *
 * getScreenerHtml: PER de VARIOS stocks a la vez (hasta ~20) — ver comentario
 * completo donde se declara, más abajo. Es lo que usa Top10 para no tener
 * que pedir el PER de cada candidato por separado.
 */
interface FinvizApi {
    @GET("groups")
    suspend fun getSectorGroupsHtml(
        @Query("g") group: String = "sector",
        @Query("v") view: String = "120", // 120 = vista "Valuation" (incluye P/E)
        @Query("o") order: String = "name"
    ): ResponseBody

    @GET("quote.ashx")
    suspend fun getStockQuoteHtml(@Query("t") ticker: String): ResponseBody

    /**
     * Screener filtrado a una lista de tickers concretos, EN UNA SOLA PETICIÓN — hasta ~20
     * tickers por página en el plan gratuito de Finviz. Añadido para el PER en lote de Top10:
     * antes se pedía el PER de cada candidato POR SEPARADO (con un límite de 20 peticiones/min
     * en este mismo cliente, 150+ candidatos tardaban 7-8 minutos solo en esta cola) — ahora,
     * agrupando de 20 en 20, un pool de 150 candidatos son ~8 peticiones en vez de 150+.
     */
    @GET("screener.ashx")
    suspend fun getScreenerHtml(
        @Query("t") tickers: String, // lista separada por comas, p. ej. "AAPL,MSFT,GOOGL"
        @Query("v") view: String = "121" // 121 = vista "Valuation" (incluye P/E), misma familia que "120" de arriba
    ): ResponseBody
}
