package com.inversionadvisor.domain.indicators

import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.inversionadvisor.BuildConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Traduce titulares de inglés a español, con dos niveles de calidad:
 *
 * 1) DeepL (si hay DEEPL_API_KEY en local.properties) — traducción de MUCHA más calidad
 *    (motor profesional, el mismo que usan herramientas de traducción dedicadas), gratis
 *    hasta 500.000 caracteres/mes. Traduce el LOTE ENTERO de titulares en una sola petición.
 * 2) ML Kit Translate (respaldo, si no hay clave o DeepL falla) — EN EL DISPOSITIVO, sin
 *    coste ni configuración, pero de calidad notablemente peor (motor ligero on-device):
 *    esto es lo que hacía que los titulares se vieran "nefastos" en la versión anterior, que
 *    usaba solo esto.
 *
 * Si ambos fallan (sin red la primera vez que ML Kit necesita descargar su modelo, cuota de
 * DeepL agotada, etc.), se devuelve el texto ORIGINAL en inglés — un titular sin traducir es
 * preferible a que la pestaña de Noticias se quede sin nada.
 */
object NewsTranslator {

    private val mlKitTranslator by lazy {
        val options = TranslatorOptions.Builder()
            .setSourceLanguage(TranslateLanguage.ENGLISH)
            .setTargetLanguage(TranslateLanguage.SPANISH)
            .build()
        Translation.getClient(options)
    }

    /** Traduce una lista de titulares A LA VEZ — una sola petición a DeepL si hay clave. */
    suspend fun traducirLote(textos: List<String>): List<String> {
        if (textos.isEmpty()) return textos

        val apiKey = BuildConfig.DEEPL_API_KEY
        if (apiKey.isNotBlank()) {
            try {
                return traducirConDeepL(textos, apiKey)
            } catch (e: Exception) {
                android.util.Log.w("NewsTranslate", "DeepL falló, cae a ML Kit on-device: ${e.message ?: e::class.simpleName}")
            }
        }

        return textos.map { traducirConMlKit(it) }
    }

    private suspend fun traducirConDeepL(textos: List<String>, apiKey: String): List<String> {
        val respuesta = com.inversionadvisor.data.remote.NetworkModule.deepLApi.translate(
            authKey = apiKey,
            texts = textos
        )
        val traducciones = respuesta.translations
        // Si DeepL devuelve menos traducciones de las pedidas (no debería, pero por si acaso),
        // se rellena con el original en vez de desalinear el orden con NewsRepository.
        return textos.indices.map { i -> traducciones?.getOrNull(i)?.text ?: textos[i] }
    }

    private suspend fun traducirConMlKit(texto: String): String {
        if (texto.isBlank()) return texto
        return try {
            asegurarModeloMlKitDescargado()
            suspendCancellableCoroutine { continuation ->
                mlKitTranslator.translate(texto)
                    .addOnSuccessListener { resultado -> continuation.resume(resultado) }
                    .addOnFailureListener { error -> continuation.resumeWithException(error) }
            }
        } catch (e: Exception) {
            android.util.Log.w("NewsTranslate", "Fallo traduciendo titular con ML Kit: ${e.message ?: e::class.simpleName}")
            texto
        }
    }

    private suspend fun asegurarModeloMlKitDescargado() {
        suspendCancellableCoroutine<Unit> { continuation ->
            mlKitTranslator.downloadModelIfNeeded()
                .addOnSuccessListener { continuation.resume(Unit) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }
    }
}
