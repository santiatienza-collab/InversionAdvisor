package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Resultado del screener "Tendencia alcista clara" (UptrendDetector),
 * criterio principal de cada pestaña de mercado.
 *
 * Clave compuesta (indexName, symbol): un mismo símbolo puede aparecer en
 * más de un índice (p. ej. AAPL en SP500 y NASDAQ100), y cada pestaña de
 * mercado necesita su propia fila para poder filtrar por indexName.
 */
@Entity(tableName = "screener_uptrend", primaryKeys = ["indexName", "symbol"])
data class UptrendCandidateEntity(
    val indexName: String,
    val symbol: String,
    val name: String,
    val sectorEtf: String,
    val sectorName: String,
    val yearChangePercent: Double,
    val trendQuality: Double,
    /** RSI 14 en el momento del escaneo — se calcula gratis a partir de las mismas velas que
     *  ya se piden para detectar la tendencia, sin ninguna petición extra. Se usa en "Consejos
     *  de inversión" como señal de riesgo (sobrecompra), no se muestra en el screener normal. */
    val rsi14: Double?,
    /** Igual que rsi14 — gratis, misma vela. Ver TechnicalAnalysis.volumeRatio. */
    val volumeRatio: Double?,
    /** Igual criterio que BuyOpportunityEntity.isSectorInFavor — antes solo la tenían las "futuras compras", ahora también la tendencia alcista, para "Consejos de inversión". */
    val isSectorInFavor: Boolean,
    val updatedAtEpochMillis: Long
)

/**
 * Resultado del screener "Futuras compras" (caída + agotamiento). Guarda
 * de forma aplanada la señal de ExhaustionDetector y el AllTimeHighInfo
 * asociado, para poder reconstruirlos sin volver a pedir las velas.
 */
@Entity(tableName = "screener_buy_opportunity", primaryKeys = ["indexName", "symbol"])
data class BuyOpportunityEntity(
    val indexName: String,
    val symbol: String,
    val name: String,
    val sectorEtf: String?,
    val sectorName: String?,
    val isSectorInFavor: Boolean,
    val confidenceScore: Int,
    /** Razones legibles de ExhaustionDetector, separadas por "||". */
    val reasonsCsv: String,
    val recentHigh: Double,
    val recentLow: Double,
    val recentLowDate: String,
    val declinePercentFromRecentHigh: Double,
    val nextResistanceTarget: Double?,
    val recoveryProgressToResistancePercent: Double?,
    val allTimeHigh: Double?,
    val allTimeHighDate: String?,
    val percentFromAth: Double?,
    /** Igual que en UptrendCandidateEntity — gratis, misma fuente. */
    val rsi14: Double?,
    val volumeRatio: Double?,
    val updatedAtEpochMillis: Long
)

/**
 * Marca de tiempo del último escaneo de UN mercado (una fila por pestaña:
 * SP500 / NASDAQ100 / IBEX35), ya que ahora "Analizar mercado" escanea solo
 * la pestaña activa, no los tres universos a la vez.
 */
@Entity(tableName = "screener_run_meta")
data class ScreenerRunMetaEntity(
    @PrimaryKey val indexName: String,
    val lastRunEpochMillis: Long,
    val symbolsScanned: Int,
    val totalSymbols: Int
)

/**
 * TERCER CAJÓN de candidatos para Top10 — NUEVO, pedido expresamente tras detectar que un stock
 * con muy buena puntuación en la fórmula completa (caso real: SanDisk, 85 puntos) podía quedarse
 * FUERA de Top10 por completo, simplemente por no encajar en ninguna de las dos formas que ya usa
 * el screener para clasificar (screener_uptrend: tendencia alcista clara de 52 semanas; o
 * screener_buy_opportunity: caída con agotamiento). Esos dos cajones SIGUEN existiendo tal cual
 * (alimentan sus propias pestañas del screener, sin tocar), este es un tercero, independiente,
 * pensado solo para que Top10Repository tenga un candidato de CUALQUIER stock con puntuación
 * decente, aunque no tenga ni tendencia clara ni caída con agotamiento.
 *
 * Invierte el orden pedido expresamente: antes se filtraba por FORMA (tendencia/caída) y solo
 * los que pasaban ese filtro llegaban a puntuarse; ahora se filtra por PUNTUACIÓN (provisionalScore,
 * calculada en el propio escaneo con los datos ya baratos que este obtiene) y el filtro de
 * "tendencia alcista clara" (hasClearUptrend) pasa a aplicarse DESPUÉS, dentro del cálculo de
 * Top10, como bono aparte — no como condición de entrada. Ver Top10Repository y
 * Top10Calculator (parámetro hasLongTermUptrend).
 */
@Entity(tableName = "screener_top10_generic", primaryKeys = ["indexName", "symbol"])
data class Top10GenericCandidateEntity(
    val indexName: String,
    val symbol: String,
    val name: String,
    val sectorEtf: String?,
    val sectorName: String?,
    val isSectorInFavor: Boolean,
    val rsi14: Double?,
    val volumeRatio: Double?,
    /** Puntuación provisional (0-100), calculada en el propio escaneo con datos ya baratos
     *  (RSI, volumen, sector en auge, señal de agotamiento si la hay) — sirve para decidir qué
     *  candidatos entran en este cajón (ver UMBRAL_ENTRADA_TOP10_GENERICO en ScreenerRepository),
     *  NO es la puntuación final de Top10 (esa la recalcula Top10Repository con datos frescos y
     *  la fórmula completa, como con los otros dos cajones). */
    val provisionalScore: Double,
    /** true si UptrendDetector.evaluate() detectó una tendencia alcista clara de 52 semanas —
     *  guardado aquí para poder aplicarlo DESPUÉS como bono en Top10Calculator, en vez de como
     *  filtro de entrada. */
    val hasClearUptrend: Boolean,
    /** NUEVO — pedido expresamente: true SOLO cuando esta fila viene de importFromRemoteJson()
     *  (el JSON de scanner-cli, calculado con la fórmula COMPLETA de Top10Calculator, no una
     *  versión barata) — cuando es true, Top10Repository puede usar directamente los campos de
     *  abajo como resultado FINAL, sin volver a pedir nada en directo al móvil. Cuando es false
     *  (valor por defecto, caso del escaneo en directo de siempre), Top10Repository sigue
     *  haciendo su fase profunda como siempre — provisionalScore ahí es solo orientativo, no el
     *  resultado final. */
    val isCompleteFromRemoteScan: Boolean = false,
    val rewardScore: Double? = null,
    val riskScore: Double? = null,
    val ratingOutOf10: Int? = null,
    val analystSummary: String? = null,
    /** Avisos separados por "||" — mismo formato que reasonsCsv en BuyOpportunityEntity. */
    val penaltyWarningsCsv: String? = null,
    val bonusWarningsCsv: String? = null,
    /** Mismo formato que Top10EntryEntity.factorsCsv (ver Top10Repository.kt, toCsv/toFactors):
     *  "label|GRADE|valueText" por factor, separados por ";;". */
    val factorsCsv: String? = null,
    val rewardBreakdownText: String? = null,
    val riskBreakdownText: String? = null,
    val updatedAtEpochMillis: Long
)
