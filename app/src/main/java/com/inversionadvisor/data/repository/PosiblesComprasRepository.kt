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
 * aumenta el volumen de operaciones drásticamente" — esto es EXACTAMENTE lo que ya comprueba
 * ExhaustionDetector.detect() (ver ese fichero): caída ≥7,5%/10% según plazo (tendencia
 * bajista), + al menos 2 de estos 4 indicios de agotamiento con el giro ya en marcha (mínimos
 * ascendentes, RSI en sobreventa, volumen creciendo, y recuperado >10% hacia la próxima
 * resistencia — el "separado un 10% del suelo" pedido). No se combina con Valores Alcistas ni
 * con el cajón genérico — solo entra aquí quien tenga esta señal de agotamiento detectada.
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
        val opportunities = screenerDao.getAllBuyOpportunitiesOnce().map { it.toOpportunityDomainLocal() }

        data class Candidate(
            val symbol: String,
            val name: String,
            val indexName: String,
            val sectorEtf: String?,
            val sectorName: String?,
            val provisionalScore: Double
        )

        val pool = opportunities.mapNotNull { opportunity ->
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
        }.sortedByDescending { it.provisionalScore }

        if (pool.isEmpty()) {
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
        onProgress(0, pool.size)
        val semaphore = Semaphore(DEEP_PHASE_CONCURRENCY)

        val finalEntries = pool.map { candidate ->
            async {
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

                    onProgress(doneCount.incrementAndGet(), pool.size)

                    analysis?.let {
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
    }
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
