package com.inversionadvisor.data.remote

import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.POST

/**
 * API gratuita de DeepL ("DeepL API Free", api-free.deepl.com) — traduce los titulares de la
 * sección Noticias con mucha más calidad que el traductor on-device (ML Kit), que se usa como
 * respaldo si no hay clave configurada (ver NewsTranslator).
 *
 * @Field("text") con una List<String> manda VARIOS titulares en la MISMA petición (DeepL
 * acepta varios parámetros "text" repetidos) — traducir los ~15 titulares de una fuente de
 * una sola vez en vez de uno a uno, para no gastar una petición por titular.
 */
interface DeepLApi {
    @FormUrlEncoded
    @POST("v2/translate")
    suspend fun translate(
        @Field("auth_key") authKey: String,
        @Field("text") texts: List<String>,
        @Field("target_lang") targetLang: String = "ES",
        @Field("source_lang") sourceLang: String = "EN"
    ): DeepLResponseDto
}
