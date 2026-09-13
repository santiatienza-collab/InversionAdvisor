package com.inversionadvisor.domain.indicators

/**
 * Clasificador básico de titulares financieros por palabras clave (en inglés, ya que las 4
 * fuentes de Noticias lo son). No requiere red ni coste de API — si más adelante resulta
 * demasiado tosco, la alternativa es mandar el lote de titulares a un modelo de lenguaje
 * pidiendo clasificación en JSON, con más latencia y coste.
 */
object NewsSentimentClassifier {

    private val palabrasPositivas = listOf(
        "rally", "rallies", "surge", "surges", "soar", "soars", "jump", "jumps",
        "climb", "climbs", "rebound", "rebounds", "record high", "record profit",
        "beats estimates", "beats expectations", "upgrade", "upgrades",
        "raises guidance", "raises forecast", "outperforms", "gains", "rises", "rose",
        "optimism", "recovery", "boosts", "surpasses"
    )

    private val palabrasNegativas = listOf(
        "plunge", "plunges", "slump", "slumps", "tumble", "tumbles", "slide", "slides",
        "sinks", "sink", "sell-off", "selloff", "misses estimates", "misses expectations",
        "profit warning", "downgrade", "downgrades", "cuts guidance", "cuts forecast",
        "warns", "warning", "layoffs", "bankruptcy", "recession fears", "crisis",
        "falls", "fell", "drops", "losses"
    )

    fun clasificar(titular: String): com.inversionadvisor.domain.model.Sentimiento {
        val texto = titular.lowercase()
        val positivas = palabrasPositivas.count { texto.contains(it) }
        val negativas = palabrasNegativas.count { texto.contains(it) }

        return when {
            positivas > negativas -> com.inversionadvisor.domain.model.Sentimiento.POSITIVO
            negativas > positivas -> com.inversionadvisor.domain.model.Sentimiento.NEGATIVO
            else -> com.inversionadvisor.domain.model.Sentimiento.NEUTRO
        }
    }
}
