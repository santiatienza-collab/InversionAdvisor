package com.inversionadvisor.domain.model

/**
 * Snapshot semanal de la Encuesta de Sentimiento de AAII (American
 * Association of Individual Investors): % de inversores particulares que se
 * declaran alcistas, neutrales o bajistas sobre los próximos 6 meses.
 *
 * HISTORIAL DE LA FUENTE DE ESTE DATO — AAII puso un firewall anti-bots (reto
 * que exige JavaScript) delante de su página pública, que bloqueaba cualquier
 * petición HTTP simple (tanto la página como su .xls de descarga). Durante un
 * tiempo se sustituyó por una aproximación calculada a partir del ratio
 * put/call de CNN Fear & Greed — pero no era el dato real de AAII, solo una
 * métrica relacionada. Ahora se vuelve a obtener el dato REAL de AAII vía un
 * WebView oculto (ver MarketRepository.refreshAaiiSentiment y
 * HiddenWebViewScraper), que sí ejecuta JavaScript y puede llegar a resolver el
 * reto — sin garantía total: algunos sistemas anti-bots también detectan
 * WebViews automatizados sin interacción humana.
 *
 * Se actualiza una vez por semana (se publica los jueves), a diferencia del
 * resto de indicadores del dashboard que son casi en tiempo real.
 *
 * bullBearSpread = bullishPercent - bearishPercent. Es la lectura clásica:
 * un spread muy negativo (mucho miedo) se suele leer como señal contraria
 * alcista, y un spread muy positivo (mucha euforia) como señal de precaución.
 *
 * spreadHistory: el spread de las últimas semanas (orden cronológico,
 * antigua -> reciente), para pintar un sparkline. Sale de la misma tabla
 * HTML que ya se scrapea para el dato actual, sin llamadas extra.
 */
data class AaiiSentiment(
    val reportedDateLabel: String,
    val bullishPercent: Double,
    val neutralPercent: Double,
    val bearishPercent: Double,
    val bullBearSpread: Double,
    val spreadHistory: List<Double>,
    val updatedAtEpochMillis: Long
) {
    /** Ver AaiiSentimentRiskMapper para el criterio (lectura contraria). */
    val riskLevel: RiskLevel
        get() = com.inversionadvisor.domain.indicators.AaiiSentimentRiskMapper.mapRisk(bullBearSpread)
}
