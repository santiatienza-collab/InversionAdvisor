package com.inversionadvisor.data.remote

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Cliente de Stooq (https://stooq.com), fuente de histórico de precios GRATUITA
 * y sin API key, usada aquí como ÚLTIMO recurso cuando Yahoo devuelve un
 * histórico truncado para un valor (ver comentario en MarketRepository sobre
 * `firstTradeDate` reciente para ELE.MC) y Twelve Data no cubre la bolsa en
 * cuestión con el plan gratuito (caso de Bolsa de Madrid).
 *
 * OJO — sin verificar en vivo: Stooq no tiene documentación oficial de API, así
 * que el sufijo de símbolo para Bolsa de Madrid (aquí ".mc" en minúsculas,
 * como "ele.mc") es la convención más probable por analogía con otras bolsas
 * (".uk", ".de", ".us"...), pero no se ha podido confirmar consultando el
 * servidor real. Si devuelve "N/D" o una tabla vacía, el símbolo no es
 * correcto y habría que ajustar el sufijo.
 *
 * Devuelve CSV en texto plano (cabecera "Date,Open,High,Low,Close,Volume"),
 * no JSON — por eso ResponseBody crudo, sin converter factory, igual que
 * AaiiSentimentApi.
 */
interface StooqApi {

    @GET("q/d/l/")
    suspend fun getDailyHistoryCsv(
        @Query("s") symbol: String,
        @Query("i") interval: String = "d" // d=diario, w=semanal, m=mensual
    ): ResponseBody
}
