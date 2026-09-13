package com.inversionadvisor.data.remote

import retrofit2.http.GET
import retrofit2.http.Path

/**
 * Cliente del endpoint no oficial que usa CNN para su propio gráfico de
 * Fear & Greed. No requiere API key. `startDate` marca desde cuándo se pide
 * el histórico (formato yyyy-MM-dd); el objeto `fear_and_greed` de la
 * respuesta siempre trae el valor MÁS RECIENTE, independientemente de la fecha.
 */
interface CnnFearGreedApi {

    @GET("index/fearandgreed/graphdata/{startDate}")
    suspend fun getGraphData(
        @Path("startDate") startDate: String
    ): CnnFearGreedResponseDto
}
