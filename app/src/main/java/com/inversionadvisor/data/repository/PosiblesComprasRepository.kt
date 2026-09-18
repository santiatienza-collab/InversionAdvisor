package com.inversionadvisor.data.repository

import com.inversionadvisor.data.local.dao.PosiblesComprasDao
import com.inversionadvisor.data.local.dao.ScreenerDao
import com.inversionadvisor.data.local.entities.PosiblesComprasEntryEntity
import com.inversionadvisor.domain.indicators.BuyOpportunityAnalyzer
import com.inversionadvisor.domain.indicators.FactorGrade
import com.inversionadvisor.domain.indicators.Top10Calculator
import com.inversionadvisor.domain.indicators.Top10Factor
import com.inversionadvisor.domain.model.Top10Entry
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * "Posibles Compras" — NUEVO, pedido expresamente: recupera el espíritu de la antigua pestaña
 * "Futuras compras" (retirada por solaparse con Valores Alcistas cuando ese pasó a ser por
 * puntuación) — pero como su propio "Top 20", combinando los 4 mercados guardados, con el
 * mismo patrón que Top10Repository (ver ese fichero para el razonamiento detallado de cada
 * paso — aquí solo se documentan las diferencias).
 *
 * CRITERIO DE ENTRADA (pedido expresamente, textual): "valores que están en tendencia bajista,
 * pero dicha tendencia se agota, se produce un giro al alza, se ha separado del suelo un 10% y
 * aumenta el volumen de operaciones drásticamente". ExhaustionDetector.detect() (ver ese
 * fichero) comprueba la caída + el giro (mínimos ascendentes, RSI en sobreventa, volumen
 * creciendo RELATIVO al tramo previo, recuperado >10% hacia resistencia) con un umbral bajo por
 * diseño (basta con 2 de 4 indicios sumando 40/75) — bueno para uso informativo en la ficha de
 * un stock suelto, pero DEMASIADO PERMISIVO como filtro de entrada aquí, dejando pasar
 * candidatos que no encajaban de verdad (casos reales: CPRI/SHOE muy bajistas sin giro real,
 * NVDA en plena tendencia alcista, AMI lateral). CORREGIDO Y ENDURECIDO (tres rondas — la
 * segunda a petición expresa: "quiero que el agotamiento, el volumen y sobre todo el giro al
 * alza sean más pronunciados"; la tercera, RECALIBRADA con datos reales tras comprobar que la
 * segunda dejaba el pool en 0/389 candidatos — ver el porqué exacto en el comentario junto al
 * primer filtro más abajo) aquí y en el origen (ScreenerRepository.scanSymbol,
 * scanner-cli/Main.kt), con 3 comprobaciones EXTRA obligatorias, no solo de puntuación:
 *  1) Se excluye cualquier candidato con tendencia alcista de fondo CONFIRMADA — un parón
 *     dentro de una tendencia alcista no es "una bajista que se agota", pertenece a Valores
 *     Alcistas, no aquí.
 *  2) Volumen (TechnicalAnalysis.volumeRatio: última semana vs media de 20 semanas) ≥1,0 — la
 *     semana actual ya iguala o supera su propia media reciente, un cambio real de
 *     comportamiento (sobre velas SEMANALES, exigir más de 1,0-1,3 deja el pool casi vacío,
 *     comprobado con datos reales).
 *  3) Caída ≥15% (Top10Calculator.MIN_DECLINE_PERCENT), no el 10% mínimo interno del detector —
 *     "tendencia bajista" de verdad, no un vaivén cualquiera.
 *  4) confidenceScore ≥70 (recoveryScore ≥45 de 75, frente al ≥40 mínimo bruto que ya exige
 *     "detected" por sí solo) — algo más exigente sin llegar a pedir 3 de 4 indicios a la vez.
 * No se combina con Valores Alcistas ni con el cajón genérico — solo entra aquí quien tenga la
 * señal de agotamiento detectada Y pase las 4 comprobaciones.
 */
class PosiblesComprasRepository(
    private val screenerDao: ScreenerDao,
    private val posiblesComprasDao: PosiblesComprasDao,
    private val marketRepository: MarketRepository
) {
    fun observePosiblesCompras(): Flow<List<Top10Entry>> =
        posiblesComprasDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getLastUpdatedAt(): Long? = posiblesComprasDao.getOldestUpdatedAtOnce()

    suspend fun refresh(onProgress: suspend (done: Int, total: Int) -> Unit) =
        com.inversionadvisor.data.connectivity.NetworkActivityTracker.trackHeavyNetworkActivity {
            refreshInternal(onProgress)
        }

    private suspend fun refreshInternal(onProgress: suspend (done: Int, total: Int) -> Unit) = coroutineScope {
        // ÚNICO cajón aquí — a diferencia de Top10Repository, que combina tendencia+genérico.
        // "Como era antes Futuras compras": solo cuenta el agotamiento detectado.
        val opportunitiesEntities = screenerDao.getAllBuyOpportunitiesOnce()
        android.util.Log.i("PosiblesComprasDiagnostic", "Oportunidades guardadas en Room (los 4 mercados): ${opportunitiesEntities.size}")
        // NUEVO — mismo cajón que ya usa Top10Repository (ver ese fichero) para el atajo rápido:
        // cuando el escaneo viene del JSON remoto de scanner-cli, este cajón ya trae la
        // puntuación COMPLETA de cada candidato calculada de fondo (fórmula de 7 categorías con
        // PER, velas diarias/mensuales, etc.) — así que, si un símbolo de "Posibles Compras"
        // también está aquí con isCompleteFromRemoteScan=true, NO hace falta repetir esa fase
        // profunda en directo (candles+PER+diarias+mensuales, una petición de red detrás de
        // otra por candidato) — es el motivo real de la lentitud reportada: Posibles Compras
        // hacía SIEMPRE la fase profunda en directo para los 60 candidatos, Top10 dejó de
        // hacerlo hace tiempo con este mismo atajo.
        val genericCandidates = screenerDao.getAllTop10GenericCandidatesOnce().associateBy { it.symbol }

        data class Candidate(
            val symbol: String,
            val name: String,
            val indexName: String,
            val sectorEtf: String?,
            val sectorName: String?,
            val provisionalScore: Double,
            val isCompleteFromRemoteScan: Boolean = false,
            val rewardScore: Double? = null,
            val riskScore: Double? = null,
            val analystSummary: String? = null,
            val penaltyWarningsCsv: String? = null,
            val bonusWarningsCsv: String? = null,
            val factorsCsv: String? = null,
            val rewardBreakdownText: String? = null,
            val riskBreakdownText: String? = null
        )

        val pool = opportunitiesEntities.mapNotNull { entity ->
            val generic = genericCandidates[entity.symbol]

            // CORREGIDO — fallo real detectado (CPRI/SHOE muy bajistas sin giro real, AMI
            // lateral, NVDA directamente alcista, todos colándose aquí): el criterio textual de
            // esta pantalla ("tendencia bajista que se agota... aumenta el volumen
            // drásticamente") nunca se comprobaba de verdad en varios puntos — se corrige aquí,
            // reforzado además en el propio origen (ScreenerRepository.scanSymbol y
            // scanner-cli/Main.kt), para que el efecto sea inmediato incluso con datos ya
            // guardados de antes de este arreglo, sin esperar al próximo escaneo automático.
            // ENDURECIDO a petición expresa ("quiero que el agotamiento, el volumen y sobre todo
            // el giro al alza sean más pronunciados") — CORREGIDO tras comprobar con datos
            // reales del último escaneo (389 oportunidades → 0 pasaban): la primera versión de
            // este endurecimiento exigía volumeRatio ≥1,5 Y confidenceScore ≥75 Y
            // recoveryProgress ≥20 A LA VEZ — sobre velas SEMANALES, volumeRatio (última semana
            // vs media de 20 semanas) rara vez pasa de 1,0 incluso en recuperaciones reales (de
            // 358 candidatos con agotamiento detectado, solo 6 llegaban a 1,3) y exigir además
            // el progreso de recuperación como obligatorio descartaba candidatos fuertes que
            // simplemente no tenían una resistencia detectada 6% por encima del suelo (dato
            // estructural, no señal de que no haya giro). Recalibrado con los datos reales del
            // escaneo: volumen ≥1,0 (la semana actual ya iguala o supera su propia media de 20
            // semanas — sigue siendo un cambio real de comportamiento, no ruido) + confianza
            // ≥70 (sube el mínimo bruto de 65 sin exigir 3 de 4 indicios a la vez) + caída
            // ≥15%; el progreso de recuperación se queda como lo que ya era antes (parte del
            // confidenceScore, informativo) en vez de filtro aparte obligatorio.
            val volumeRatio = entity.volumeRatio ?: generic?.volumeRatio
            if (volumeRatio == null || volumeRatio < ScreenerRepository.MIN_VOLUME_RATIO_FOR_BUY_OPPORTUNITY) return@mapNotNull null
            if (generic?.hasClearUptrend == true) return@mapNotNull null
            // Caída de verdad significativa (15%, Top10Calculator.MIN_DECLINE_PERCENT) — no el
            // 10% mínimo interno por defecto con el que ExhaustionDetector ya considera "candidato".
            if (entity.declinePercentFromRecentHigh > -com.inversionadvisor.domain.indicators.Top10Calculator.MIN_DECLINE_PERCENT) return@mapNotNull null
            // Confianza global algo más alta que el mínimo bruto (65) que exige "detected" por
            // sí solo — sin llegar a exigir 3 de 4 indicios a la vez (ver comentario de arriba).
            if (entity.confidenceScore < ScreenerRepository.MIN_CONFIDENCE_SCORE_FOR_BUY_OPPORTUNITY) return@mapNotNull null
            // NUEVO — pedido expresamente: "solo candidatos con MACD alcista, descarta los MACD
            // bajistas, como criterio añadido a los que ya teníamos" — histograma MACD > 0. Con
            // entidades de escaneos anteriores a este cambio (macdHistogram = null), se descarta
            // por precaución (null no se puede asumir alcista) hasta el próximo escaneo.
            if ((entity.macdHistogram ?: -1.0) <= 0.0) return@mapNotNull null

            // NUEVO — pedido expresamente: criterio profesional de confluencia técnica (no basta
            // "vela + volumen" por sí solos) — ver contarConfirmaciones() al final del fichero
            // para el detalle de cada una de las 4. Con datos de scans anteriores a este cambio
            // (candlestickPattern/dailyVolumeRatio/etc. = null), esto descarta al candidato —
            // hace falta el PRÓXIMO escaneo (automático o en directo) para que vuelva a evaluarse
            // con los campos nuevos ya calculados.
            val confirmaciones = contarConfirmaciones(
                candlestickPattern = entity.candlestickPattern,
                doubleTopBottomPattern = entity.doubleTopBottomPattern,
                reasonsCsv = entity.reasonsCsv,
                dailyVolumeRatio = entity.dailyVolumeRatio,
                momentumPriceDivergence = entity.momentumPriceDivergence,
                nearestSupportPercent = entity.nearestSupportPercent
            )
            android.util.Log.i(
                "PosiblesComprasDiagnostic",
                "${entity.symbol}: confirmaciones=${confirmaciones.total}/4 (giro=${confirmaciones.patronDeGiro} " +
                    "volumen=${confirmaciones.volumenAlto} divergencia=${confirmaciones.divergenciaOSobreventa} " +
                    "soporte=${confirmaciones.soporteRelevante}) válida=${confirmaciones.esCombinacionValida}"
            )
            if (!confirmaciones.esCombinacionValida) return@mapNotNull null

            if (generic?.isCompleteFromRemoteScan == true) {
                return@mapNotNull Candidate(
                    symbol = entity.symbol,
                    name = generic.name,
                    indexName = generic.indexName,
                    sectorEtf = generic.sectorEtf,
                    sectorName = generic.sectorName,
                    provisionalScore = generic.provisionalScore,
                    isCompleteFromRemoteScan = true,
                    rewardScore = generic.rewardScore,
                    riskScore = generic.riskScore,
                    analystSummary = generic.analystSummary,
                    penaltyWarningsCsv = generic.penaltyWarningsCsv,
                    bonusWarningsCsv = generic.bonusWarningsCsv,
                    factorsCsv = generic.factorsCsv,
                    rewardBreakdownText = generic.rewardBreakdownText,
                    riskBreakdownText = generic.riskBreakdownText
                )
            }

            val opportunity = entity.toOpportunityDomainLocal()
            val provisional = Top10Calculator.score(
                trendQuality = null,
                yearChangePercent = null,
                aboveTrendSma = null,
                isSectorInFavor = opportunity.isSectorInFavor,
                longTermDecline = opportunity.exhaustionSignal,
                shortTermPullback = null,
                rsi14 = opportunity.rsi14, volumeRatio = opportunity.volumeRatio, stockVolatilityRatio = null,
                stockPe = null, sectorAveragePe = null, sectorTypicalPeRange = null,
                percentFromYearHigh = null, nearestSupportPercent = null, nearestResistancePercent = null,
                candlestickPattern = null, marketTrap = null, macd = null,
                hasLongTermUptrend = false,
                requireSignal = true
            ) ?: return@mapNotNull null
            Candidate(
                symbol = opportunity.symbol,
                name = opportunity.name ?: opportunity.symbol,
                indexName = opportunity.indexName,
                sectorEtf = opportunity.sectorEtf,
                sectorName = opportunity.sectorName,
                provisionalScore = provisional.combinedScore
            )
        }

        // Los que ya vienen completos del JSON remoto no cuestan nada (sin red) — solo se
        // recorta el subconjunto que SÍ necesita la fase profunda en directo (candidatos de un
        // escaneo en directo sin pasar por importFromRemoteJson todavía).
        val readyEntries = pool.filter { it.isCompleteFromRemoteScan }
        val toAnalyzeLive = pool.filter { !it.isCompleteFromRemoteScan }
            .sortedByDescending { it.provisionalScore }
            // NUEVO — pedido expresamente tras detectar el problema real de escala: sin límite,
            // este subconjunto podía tener cientos de candidatos y analizarlos TODOS en directo
            // (velas, PER, velas diarias/mensuales por cada uno) tardaba varios minutos y
            // competía con el límite de peticiones por minuto de Yahoo. Se limita a mano: los 60
            // mejores por puntuación PROVISIONAL (barata, sin red) van al análisis completo.
            .take(DEEP_PHASE_POOL_LIMIT)
        val finalPool = readyEntries + toAnalyzeLive
        android.util.Log.i(
            "PosiblesComprasDiagnostic",
            "Pool final: ${readyEntries.size} listos del JSON remoto (sin red) + ${toAnalyzeLive.size} necesitan análisis en directo"
        )

        if (finalPool.isEmpty()) {
            posiblesComprasDao.clear()
            return@coroutineScope
        }

        val spyCandles = try {
            marketRepository.refreshYahooCandlesIfStale(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
            marketRepository.observeCandles(com.inversionadvisor.domain.model.Symbols.SP500_BENCHMARK, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
        } catch (e: Exception) {
            emptyList()
        }
        val sectorContext = runCatching { marketRepository.computeSectorContext() }
            .getOrDefault(MarketRepository.SectorContext(emptyMap(), null, emptyMap()))
        val sectorFavorability = sectorContext.isSectorInFavor
        val benchmarkYearChangePercent = spyCandles.takeIf { it.size >= 2 }
            ?.let { (it.last().close - it.first().close) / it.first().close * 100 }
        marketRepository.refreshSectorPeRatiosIfStale()
        val sectorPeMap = runCatching { marketRepository.observeSectorPeRatios().first() }.getOrDefault(emptyMap())

        val doneCount = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(0, finalPool.size)
        val semaphore = Semaphore(DEEP_PHASE_CONCURRENCY)

        val finalEntries = finalPool.map { candidate ->
            async {
                // Atajo rápido (ver comentario de genericCandidates más arriba) — sin esto NI
                // UN candidato del JSON remoto se libraba de las 4 peticiones de red por
                // símbolo, aunque ya tuviera la puntuación completa calculada de fondo.
                if (candidate.isCompleteFromRemoteScan) {
                    onProgress(doneCount.incrementAndGet(), finalPool.size)
                    return@async PosiblesComprasEntryEntity(
                        symbol = candidate.symbol,
                        name = candidate.name,
                        indexName = candidate.indexName,
                        sectorName = candidate.sectorName,
                        rewardScore = candidate.rewardScore ?: 0.0,
                        riskScore = candidate.riskScore ?: 0.0,
                        combinedScore = candidate.provisionalScore,
                        analystSummary = candidate.analystSummary ?: "",
                        factorsCsv = candidate.factorsCsv ?: emptyList<Top10Factor>().toCsvLocal(),
                        rewardBreakdownText = candidate.rewardBreakdownText ?: "",
                        riskBreakdownText = candidate.riskBreakdownText ?: "",
                        penaltyWarningsText = (candidate.penaltyWarningsCsv?.split("||")?.filter { it.isNotBlank() } ?: emptyList()).joinToString("\n"),
                        bonusWarningsText = (candidate.bonusWarningsCsv?.split("||")?.filter { it.isNotBlank() } ?: emptyList()).joinToString("\n"),
                        rank = 0,
                        updatedAtEpochMillis = System.currentTimeMillis()
                    )
                }
                semaphore.withPermit {
                    val candlesDeferred = async {
                        try {
                            marketRepository.refreshYahooCandlesIfStale(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                            marketRepository.observeCandles(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    val stockPeDeferred = async {
                        try {
                            marketRepository.refreshStockPeIfStale(candidate.symbol, allowWebViewFallback = false)
                            marketRepository.observeStockPe(candidate.symbol).first()
                        } catch (e: Exception) {
                            null
                        }
                    }
                    val candles = candlesDeferred.await()
                    val stockPe = stockPeDeferred.await()
                    val sectorAveragePe = candidate.sectorEtf?.let { sectorPeMap[it] }

                    val dailyCandlesForCross = try {
                        marketRepository.refreshDailyCandlesForSmaIfStale(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR)
                        marketRepository.observeDailyCandlesForSma(candidate.symbol, com.inversionadvisor.domain.model.ChartRange.ONE_YEAR).first()
                    } catch (e: Exception) {
                        null
                    }
                    val monthlyCandlesForFlag = try {
                        marketRepository.refreshMonthlyCandlesForFlagIfStale(candidate.symbol)
                        marketRepository.observeMonthlyCandlesForFlag(candidate.symbol).first()
                    } catch (e: Exception) {
                        null
                    }

                    val analysis = BuyOpportunityAnalyzer.analyze(
                        candles = candles,
                        sectorName = candidate.sectorName,
                        stockPe = stockPe,
                        sectorAveragePe = sectorAveragePe,
                        spyCandles = spyCandles.ifEmpty { null },
                        incomeGrowing = null,
                        sectorInFavor = candidate.sectorEtf?.let { sectorFavorability[it] },
                        sectorEtf = candidate.sectorEtf,
                        dailyCandlesForCross = dailyCandlesForCross,
                        monthlyCandlesForFlag = monthlyCandlesForFlag?.ifEmpty { null },
                        benchmarkYearChangePercent = benchmarkYearChangePercent,
                        sectorDeclinePercent = candidate.sectorEtf?.let { sectorContext.sectorDeclinePercentByEtf[it] }
                    )

                    onProgress(doneCount.incrementAndGet(), finalPool.size)

                    // Repite aquí las mismas comprobaciones de la entrada al pool (ver más
                    // arriba) con datos FRESCOS recién descargados — el filtro de arriba usaba lo
                    // guardado del último escaneo, que puede haber quedado desactualizado desde
                    // entonces (p. ej. un giro que se veía hace días y ya no se ve ahora).
                    // BuyOpportunityAnalyzer.Analysis no expone declinePercentFromRecentHigh ni
                    // recoveryProgressToResistancePercent directamente, así que se recalcula
                    // ExhaustionDetector sobre las MISMAS velas ya descargadas arriba (candles),
                    // sin ninguna petición de red extra.
                    val exhaustionFresco = com.inversionadvisor.domain.indicators.ExhaustionDetector.detect(candles)
                    // NUEVO — pedido expresamente: MACD alcista con datos FRESCOS, sobre las
                    // MISMAS velas ya descargadas arriba, sin petición extra.
                    val macdFresco = com.inversionadvisor.domain.indicators.TechnicalAnalysis.calculateMacd(candles)
                    // Confluencia técnica (ver contarConfirmaciones()) con datos FRESCOS —
                    // volumen diario sobre dailyCandlesForCross, ya descargadas arriba para el
                    // cruce SMA20/50, sin petición extra.
                    val confirmacionesFrescas = analysis?.let {
                        contarConfirmaciones(
                            candlestickPattern = it.candlestickPattern?.name,
                            doubleTopBottomPattern = it.doubleTopBottomResult?.pattern?.name,
                            reasonsCsv = exhaustionFresco?.reasons?.joinToString("||") ?: "",
                            dailyVolumeRatio = dailyCandlesForCross?.let { daily -> com.inversionadvisor.domain.indicators.TechnicalAnalysis.volumeRatio(daily) },
                            momentumPriceDivergence = com.inversionadvisor.domain.indicators.UptrendDetector.detectMomentumPriceDivergence(candles).name,
                            nearestSupportPercent = it.nearestSupportPercent
                        )
                    }

                    val pasaFiltroFresco = analysis != null &&
                        !analysis.hasConfirmedUptrend &&
                        (analysis.volumeRatio ?: 0.0) >= ScreenerRepository.MIN_VOLUME_RATIO_FOR_BUY_OPPORTUNITY &&
                        exhaustionFresco != null &&
                        exhaustionFresco.declinePercentFromRecentHigh <= -com.inversionadvisor.domain.indicators.Top10Calculator.MIN_DECLINE_PERCENT &&
                        exhaustionFresco.confidenceScore >= ScreenerRepository.MIN_CONFIDENCE_SCORE_FOR_BUY_OPPORTUNITY &&
                        (macdFresco?.histogram ?: 0.0) > 0.0 &&
                        confirmacionesFrescas?.esCombinacionValida == true

                    analysis?.takeIf { pasaFiltroFresco }?.let {
                        PosiblesComprasEntryEntity(
                            symbol = candidate.symbol,
                            name = candidate.name,
                            indexName = candidate.indexName,
                            sectorName = candidate.sectorName,
                            rewardScore = it.rewardScore,
                            riskScore = it.riskScore,
                            combinedScore = it.riskRewardRatio,
                            analystSummary = it.analystSummary,
                            factorsCsv = it.factors.toCsvLocal(),
                            rewardBreakdownText = it.rewardBreakdown.joinToString("\n"),
                            riskBreakdownText = it.riskBreakdown.joinToString("\n"),
                            penaltyWarningsText = it.penaltyWarnings.joinToString("\n"),
                            bonusWarningsText = it.bonusWarnings.joinToString("\n"),
                            rank = 0,
                            updatedAtEpochMillis = System.currentTimeMillis()
                        )
                    }
                }
            }
        }.mapNotNull { runCatching { it.await() }.getOrNull() }
        android.util.Log.i("PosiblesComprasDiagnostic", "Entradas finales tras la fase profunda: ${finalEntries.size}")

        val top20 = finalEntries
            .sortedByDescending { it.combinedScore }
            .take(TOP_COUNT)
            .mapIndexed { index, entry -> entry.copy(rank = index + 1) }

        posiblesComprasDao.clear()
        posiblesComprasDao.insertAll(top20)
    }

    companion object {
        private const val DEEP_PHASE_CONCURRENCY = 12
        private const val TOP_COUNT = 20
        private const val DEEP_PHASE_POOL_LIMIT = 60
    }
}

// ---- Confluencia técnica (pedido expresamente, criterio profesional de análisis técnico) ----
//
// "Una vela o figura de agotamiento bajista con giro al alza y mucho volumen es una señal, no
// una razón suficiente para comprar. Lo que la convierte en una buena oportunidad es que otras
// cosas la confirmen [...] Yo exigiría al menos cuatro puntos alineados: patrón de giro
// confirmado, volumen alto en el giro, divergencia en RSI o MACD y soporte relevante. Con eso la
// señal es razonable. Con solo la vela y el volumen, es una apuesta." — se implementan las 4
// categorías EXACTAS pedidas, cada una con lo que ya calcula el resto de la app (sin duplicar
// detectores).
//  1) Patrón de giro: vela HAMMER/BULLISH_ENGULFING, o doble suelo, o mínimos ascendentes ya
//     detectados por ExhaustionDetector (su razón "Mínimos ascendentes..." en reasonsCsv).
//  2) Volumen alto en el giro: volumen DIARIO ≥1,5x su media de 20 sesiones — la cifra exacta
//     pedida ("1,5 veces la de 20 periodos"), sobre velas diarias (no semanales: ver
//     dailyVolumeRatio en BuyOpportunityEntity y el porqué en su comentario).
//  3) Divergencia en RSI/momentum: UptrendDetector.detectMomentumPriceDivergence == BULLISH, o
//     RSI en sobreventa en el mínimo (razón "RSI llegó a sobreventa" ya detectada por
//     ExhaustionDetector).
//  4) Soporte relevante: TechnicalAnalysis.detectSupportResistanceLevels encontró un soporte a
//     MAX_DISTANCIA_SOPORTE_PERCENT o menos por debajo del precio actual — el giro ocurre SOBRE
//     un soporte de verdad, no en el aire.
//
// RECALIBRADO a petición expresa tras comprobar con datos reales (escaneo completo de SP500) que
// exigir las 4 a la vez sobre el pool ya reducido por los filtros previos (caída ≥15% +
// exhaustion) deja el resultado en 0 casi siempre — de 501 símbolos, solo 5 pasaban esos filtros
// previos, y ninguno llegaba a 4/4 (3 llegaban a 3/4). Se baja a exigir 3 de las 4, pero NO las 4
// tratadas como intercambiables: en mi opinión, dentro de las 4, dos son imprescindibles y dos son
// confirmación adicional —
//  - IMPRESCINDIBLES (si falta cualquiera de estas dos, no hay señal, por muchas otras que haya):
//    el PATRÓN DE GIRO (sin él no hay tesis de reversión, solo una caída que podría seguir cayendo)
//    y el VOLUMEN ALTO (sin él no hay forma de distinguir un giro real de ruido de precio — es
//    justo la distinción que hace el usuario: "con solo la vela y el volumen, es una apuesta",
//    o sea ni siquiera el volumen solo basta, pero SIN volumen la apuesta es aún peor).
//  - CONFIRMACIÓN ADICIONAL (con que se cumpla una de las dos ya vale, no hacen falta las dos):
//    la DIVERGENCIA RSI/MOMENTUM y el SOPORTE RELEVANTE — ambas añaden contexto y reducen falsos
//    positivos, pero son más "ruidosas"/casuales que las dos de arriba (un soporte relevante a
//    veces es simplemente estructura del gráfico, no señal de giro; una divergencia técnica no
//    siempre está presente incluso en giros reales) — exigir las DOS a la vez, además de las
//    imprescindibles, sería de nuevo demasiado restrictivo (equivaldría a las 4 obligatorias).
// Resultado práctico: patrón de giro Y volumen alto obligatorios, más al menos una de
// (divergencia, soporte) — es decir, 3 de 4 pero con las dos más importantes fijas, no
// intercambiables por cualquier combinación de 3.
//
// Gestión del riesgo (stop bajo el mínimo del giro, ratio ≥2:1, tamaño de posición 1-2%, entrada
// escalonada) queda fuera de este filtro a propósito: es una decisión de EJECUCIÓN de quien
// invierte, no un criterio de qué candidatos mostrar en la lista.
private const val MIN_DAILY_VOLUME_RATIO_CONFIRMACION = 1.5
private const val MAX_DISTANCIA_SOPORTE_PERCENT = 8.0

/** Las 4 señales de confluencia por separado — ver comentario de arriba sobre cuáles son
 *  imprescindibles (patronDeGiro, volumenAlto) y cuáles son confirmación adicional (con que se
 *  cumpla una de las dos alcanza: divergenciaOSobreventa, soporteRelevante). */
private data class Confirmaciones(
    val patronDeGiro: Boolean,
    val volumenAlto: Boolean,
    val divergenciaOSobreventa: Boolean,
    val soporteRelevante: Boolean
) {
    val total: Int get() = listOf(patronDeGiro, volumenAlto, divergenciaOSobreventa, soporteRelevante).count { it }

    /** Patrón de giro y volumen alto obligatorios, más al menos una de (divergencia, soporte). */
    val esCombinacionValida: Boolean get() = patronDeGiro && volumenAlto && (divergenciaOSobreventa || soporteRelevante)
}

private fun contarConfirmaciones(
    candlestickPattern: String?,
    doubleTopBottomPattern: String?,
    reasonsCsv: String,
    dailyVolumeRatio: Double?,
    momentumPriceDivergence: String?,
    nearestSupportPercent: Double?
): Confirmaciones {
    val patronDeGiro = candlestickPattern == "HAMMER" || candlestickPattern == "BULLISH_ENGULFING" ||
        doubleTopBottomPattern == "DOUBLE_BOTTOM" ||
        reasonsCsv.contains("ascendentes", ignoreCase = true)

    val volumenAlto = dailyVolumeRatio != null && dailyVolumeRatio >= MIN_DAILY_VOLUME_RATIO_CONFIRMACION

    val divergenciaOSobreventa = momentumPriceDivergence == "BULLISH" || reasonsCsv.contains("sobreventa", ignoreCase = true)

    val soporteRelevante = nearestSupportPercent != null && nearestSupportPercent <= MAX_DISTANCIA_SOPORTE_PERCENT

    return Confirmaciones(patronDeGiro, volumenAlto, divergenciaOSobreventa, soporteRelevante)
}

private fun com.inversionadvisor.data.local.entities.BuyOpportunityEntity.toOpportunityDomainLocal(): com.inversionadvisor.domain.model.BuyOpportunity {
    val exhaustionSignal = com.inversionadvisor.domain.indicators.ExhaustionSignal(
        detected = true,
        confidenceScore = confidenceScore,
        reasons = reasonsCsv.split("||").filter { it.isNotBlank() },
        recentHigh = recentHigh,
        recentLow = recentLow,
        recentLowDate = recentLowDate,
        declinePercentFromRecentHigh = declinePercentFromRecentHigh,
        nextResistanceTarget = nextResistanceTarget,
        recoveryProgressToResistancePercent = recoveryProgressToResistancePercent
    )
    val riskLevel = if (confidenceScore >= 65) com.inversionadvisor.domain.model.RiskLevel.LOW else com.inversionadvisor.domain.model.RiskLevel.MEDIUM
    return com.inversionadvisor.domain.model.BuyOpportunity(
        symbol = symbol, name = name, sectorEtf = sectorEtf, sectorName = sectorName, indexName = indexName,
        isSectorInFavor = isSectorInFavor, exhaustionSignal = exhaustionSignal, allTimeHighInfo = null,
        riskLevel = riskLevel, rsi14 = rsi14, volumeRatio = volumeRatio
    )
}

private fun List<Top10Factor>.toCsvLocal(): String =
    joinToString(";;") { "${it.label}|${it.grade.name}|${it.valueText}" }

private fun String.toFactorsLocal(): List<Top10Factor> {
    if (isBlank()) return emptyList()
    return split(";;").mapNotNull { chunk ->
        val parts = chunk.split("|")
        if (parts.size != 3) return@mapNotNull null
        val grade = runCatching { FactorGrade.valueOf(parts[1]) }.getOrDefault(FactorGrade.UNKNOWN)
        Top10Factor(label = parts[0], grade = grade, valueText = parts[2])
    }
}

private fun PosiblesComprasEntryEntity.toDomain(): Top10Entry = Top10Entry(
    symbol = symbol, name = name, indexName = indexName, sectorName = sectorName,
    rewardScore = rewardScore, riskScore = riskScore, combinedScore = combinedScore,
    analystSummary = analystSummary, factors = factorsCsv.toFactorsLocal(),
    rewardBreakdown = rewardBreakdownText.split("\n").filter { it.isNotBlank() },
    riskBreakdown = riskBreakdownText.split("\n").filter { it.isNotBlank() },
    penaltyWarnings = penaltyWarningsText.split("\n").filter { it.isNotBlank() },
    bonusWarnings = bonusWarningsText.split("\n").filter { it.isNotBlank() },
    rank = rank, updatedAtEpochMillis = updatedAtEpochMillis
)
