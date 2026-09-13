package com.inversionadvisor.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * SIN USAR — se deja el fichero por si en el futuro se resuelve el problema de crumb/cookie,
 * pero AHORA MISMO ningún código de la app llama a esta interfaz.
 *
 * Cliente del endpoint no oficial v7/finance/quote de Yahoo Finance — se probó para traer la
 * fecha de resultados trimestrales (earningsTimestamp), pero devuelve 401 "Invalid Cookie"/
 * "Invalid Crumb" de forma sistemática: este endpoint concreto exige un crumb + cookie de
 * sesión que esta app no implementa (a diferencia de v8/finance/chart, que sí funciona sin
 * eso — es el que usa el resto de la app). Es un problema YA CONOCIDO en este mismo proyecto
 * (ver la nota histórica en NetworkModule sobre el mismo fallo con el PER).
 *
 * La fecha de resultados se obtiene ahora de otra forma — ver
 * YahooQuotePageParser.parseEarningsDateText (scrapea el HTML ya renderizado de la página de
 * cotización, la misma que ya se pide para Valor Empresa/Ingresos netos).
 */
interface YahooQuoteApi {

    @GET("v7/finance/quote")
    suspend fun getQuote(
        @Query("symbols") symbols: String,
        @Query("fields") fields: String = "earningsTimestamp"
    ): YahooQuoteResponseDto
}
