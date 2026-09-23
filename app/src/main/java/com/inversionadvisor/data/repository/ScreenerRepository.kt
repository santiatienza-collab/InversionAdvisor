package com.inversionadvisor.data.repository

import com.inversionadvisor.data.local.dao.ScreenerDao
import com.inversionadvisor.data.local.entities.BuyOpportunityEntity
import com.inversionadvisor.data.local.entities.ScreenerRunMetaEntity
import com.inversionadvisor.data.local.entities.UptrendCandidateEntity
import com.inversionadvisor.domain.indicators.AllTimeHighInfo
import com.inversionadvisor.domain.indicators.ExhaustionDetector
import com.inversionadvisor.domain.indicators.ExhaustionSignal
import com.inversionadvisor.domain.indicators.SectorRotationCalculator
import com.inversionadvisor.domain.indicators.TechnicalAnalysis
import com.inversionadvisor.domain.indicators.UptrendDetector
import com.inversionadvisor.domain.model.BuyOpportunity
import com.inversionadvisor.domain.model.ChartRange
import com.inversionadvisor.domain.model.RiskLevel
import com.inversionadvisor.domain.model.ScreenerRunMeta
import com.inversionadvisor.domain.model.StockIndexMeta
import com.inversionadvisor.domain.model.StockUniverseEntry
import com.inversionadvisor.domain.model.Symbols
import com.inversionadvisor.domain.model.UptrendCandidate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Screener sobre el universo de cada mercado (S&P 500 / Nasdaq-100 / IBEX 35,
 * ver StockUniverseRepository, que los mantiene auto-actualizados desde
 * Wikipedia), con dos criterios por pestaña:
 *  - "Tendencia alcista clara": trayectoria de fondo alcista en el último año (UptrendDetector).
 *  - "Futuras compras": caída del 10-15%+ con señal de agotamiento (ExhaustionDetector),
 *    marcando además si el sector del stock está en rotación favorable.
 *
 * runFullScreen(indexName) escanea SOLO el mercado indicado (la pestaña
 * activa), no los tres universos a la vez — el usuario dispara el análisis
 * con un botón por pestaña. Reutiliza MarketRepository para las velas
 * (misma caché de Room que ya usa el resto de la app), pero a través de
 * refreshYahooCandlesBulkIfStale, que usa un cliente Yahoo dedicado con más
 * concurrencia y un rate limit más alto que el del dashboard.
 */
class ScreenerRepository(
    private val marketRepository: MarketRepository,
    private val screenerDao: ScreenerDao,
    private val stockUniverseRepository: StockUniverseRepository,
    private val scanResultApi: com.inversionadvisor.data.remote.ScanResultApi = com.inversionadvisor.data.remote.NetworkModule.scanResultApi
) {

    fun observeUptrendCandidates(indexName: String): Flow<List<UptrendCandidate>> =
        screenerDao.observeUptrendCandidates(indexName).map { list -> list.map { it.toDomain() } }

    fun observeBuyOpportunities(indexName: String): Flow<List<BuyOpportunity>> =
        screenerDao.observeBuyOpportunities(indexName).map { list -> list.map { it.toDomain() } }

    fun observeRunMeta(indexName: String): Flow<ScreenerRunMeta?> =
        screenerDao.observeRunMeta(indexName).map { it?.toDomain() }

    fun observeIndexMeta(): Flow<List<StockIndexMeta>> = stockUniverseRepository.observeIndexMeta()

    /**
     * Escanea EN PARALELO (ver SCAN_CONCURRENCY) el universo de un único
     * mercado (indexName: "SP500" | "NASDAQ100" | "IBEX35"). Cada símbolo
     * se procesa de forma aislada (try/catch propio) para que un fallo
     * puntual no tumbe el resto del escaneo.
     *
     * RESULTADOS PROGRESIVOS: cada símbolo se guarda en Room en cuanto
     * termina su propio escaneo, no se espera a que terminen los ~500 para
     * guardar de golpe al final — como la pantalla observa Room con un
     * Flow (observeUptrendCandidates/observeBuyOpportunities), los
     * resultados van apareciendo en la lista según se van encontrando, en
     * vez de una pantalla vacía hasta que el escaneo entero termina.
     *
     * Envuelto en NetworkActivityTracker.trackHeavyNetworkActivity — ver esa clase: este
     * escaneo pide cientos de peticiones de red a la vez, lo bastante como para que Android
     * detecte caídas de conexión puntuales que no son reales.
     */
    /**
     * NUEVO — pedido expresamente: descarga el JSON que scanner-cli ya calculó y publicó (ver
     * ese módulo y .github/workflows/scan.yml), y rellena las MISMAS tablas de Room que rellena
     * runFullScreen() al escanear en directo — así toda la pantalla (Tendencia alcista, Futuras
     * compras, Top10) funciona exactamente igual después de esto, sin ningún cambio en la UI,
     * solo que "Analizar" pasa de tardar minutos a tardar lo que tarda bajar un archivo pequeño.
     *
     * Devuelve true si consiguió importar algo, false si falló (sin conexión, el workflow
     * todavía no ha corrido nunca, GITHUB_REPO_PATH sin configurar...) — en ese caso, quien
     * llame a esto debe caer al escaneo en directo de siempre (ver ScreenerViewModel.runScreener),
     * no simplemente dejar la pantalla vacía.
     */
    suspend fun importFromRemoteJson(indexName: String): Boolean {
        val url = "https://raw.githubusercontent.com/$GITHUB_REPO_PATH/data/scan-$indexName.json"
        return try {
            val resultado = scanResultApi.getScanResult(url)
            val ahora = System.currentTimeMillis()

            screenerDao.clearUptrendCandidates(indexName)
            screenerDao.clearBuyOpportunities(indexName)
            screenerDao.clearTop10GenericCandidates(indexName)

            // CAMBIADO a petición expresa: "Valores Alcistas" (antes "Tendencia alcista") ya NO
            // selecciona por tendencia limpia (UptrendDetector.evaluate()) — ahora selecciona por
            // PUNTUACIÓN, con el mismo umbral (≥55) que ya usa el "cajón genérico" de Top10, para
            // ser coherente con ese otro criterio ya establecido. isUptrend (el campo original)
            // se SIGUE guardando tal cual en la entidad (yearChangePercent/trendQuality), para
            // que la fórmula de puntuación (hasLongTermUptrend) siga funcionando igual — solo
            // cambia QUIÉN aparece en la lista, no lo que cuenta como "tendencia alcista clara"
            // de cara al bono/penalización de esa categoría.
            val uptrends = resultado.candidates.filter { (it.combinedScore ?: 0.0) >= 55.0 }.map { c ->
                UptrendCandidateEntity(
                    indexName = indexName,
                    symbol = c.symbol,
                    name = c.name,
                    sectorEtf = c.sectorEtf,
                    sectorName = c.sectorName ?: c.sectorEtf,
                    yearChangePercent = c.yearChangePercent ?: 0.0,
                    trendQuality = c.trendQuality ?: 0.0,
                    rsi14 = c.rsi14,
                    volumeRatio = c.volumeRatio,
                    isSectorInFavor = c.isSectorInFavor,
                    updatedAtEpochMillis = ahora
                )
            }
            if (uptrends.isNotEmpty()) screenerDao.insertUptrendCandidates(uptrends)

            val opportunities = resultado.candidates.filter { it.isBuyOpportunity }.map { c ->
                BuyOpportunityEntity(
                    indexName = indexName,
                    symbol = c.symbol,
                    name = c.name,
                    sectorEtf = c.sectorEtf,
                    sectorName = c.sectorName,
                    isSectorInFavor = c.isSectorInFavor,
                    confidenceScore = c.exhaustionConfidence ?: 0,
                    reasonsCsv = (c.exhaustionReasons ?: emptyList()).joinToString("||"),
                    recentHigh = c.recentHigh ?: 0.0,
                    recentLow = c.recentLow ?: 0.0,
                    recentLowDate = c.recentLowDate ?: "",
                    declinePercentFromRecentHigh = c.declinePercentFromRecentHigh ?: 0.0,
                    nextResistanceTarget = c.nextResistanceTarget,
                    recoveryProgressToResistancePercent = c.recoveryProgressToResistancePercent,
                    // NUEVO — el escáner todavía no calcula el máximo histórico (necesitaría
                    // pedir el rango "max" de Yahoo, aparte de 1y/1d/1mo) — se deja sin dato,
                    // igual que hace la app cuando falta.
                    allTimeHigh = null,
                    allTimeHighDate = null,
                    percentFromAth = null,
                    rsi14 = c.rsi14,
                    volumeRatio = c.volumeRatio,
                    // NUEVO — confluencia técnica para Posibles Compras (ver comentario en
                    // BuyOpportunityEntity y PosiblesComprasRepository).
                    dailyVolumeRatio = c.dailyVolumeRatio,
                    candlestickPattern = c.candlestickPattern,
                    doubleTopBottomPattern = c.doubleTopBottomPattern,
                    momentumPriceDivergence = c.momentumPriceDivergence,
                    nearestSupportPercent = c.nearestSupportPercent,
                    macdHistogram = c.macdHistogram,
                    updatedAtEpochMillis = ahora
                )
            }
            if (opportunities.isNotEmpty()) screenerDao.insertBuyOpportunities(opportunities)

            // Tercer cajón (ver Top10GenericCandidateEntity) — CORREGIDO: antes se filtraba por
            // combinedScore >= 55 (mismo umbral del escaneo en directo), pero eso dejaba SIN
            // puntuación completa guardada a cualquier candidato de Tendencia alcista/Futuras
            // compras que no llegara a esos 55 puntos — Top10Repository no encontraba de dónde
            // tomar el atajo rápido para ellos y los recalculaba en directo (la lentitud real
            // detectada). Como aquí TODOS los candidatos ya tienen su puntuación calculada
            // gratis, se guarda para todos, sin filtro.
            val genericos = resultado.candidates.filter { it.combinedScore != null }.map { c ->
                com.inversionadvisor.data.local.entities.Top10GenericCandidateEntity(
                    indexName = indexName,
                    symbol = c.symbol,
                    name = c.name,
                    sectorEtf = c.sectorEtf,
                    sectorName = c.sectorName,
                    isSectorInFavor = c.isSectorInFavor,
                    rsi14 = c.rsi14,
                    volumeRatio = c.volumeRatio,
                    provisionalScore = c.combinedScore ?: 0.0,
                    hasClearUptrend = c.isUptrend,
                    isCompleteFromRemoteScan = true,
                    rewardScore = c.rewardScore,
                    riskScore = c.riskScore,
                    ratingOutOf10 = c.ratingOutOf10,
                    analystSummary = c.summary,
                    penaltyWarningsCsv = c.penaltyWarnings?.joinToString("||"),
                    bonusWarningsCsv = c.bonusWarnings?.joinToString("||"),
                    // NUEVO — mismo formato que usa Top10Repository (label|GRADE|valueText,
                    // separados por ";;") para que el desglose "Cómo se calculó" funcione igual
                    // que si viniera de un escaneo en directo.
                    factorsCsv = c.factors?.joinToString(";;") { "${it.label}|${it.grade}|${it.valueText}" },
                    rewardBreakdownText = c.rewardBreakdown?.joinToString("\n"),
                    riskBreakdownText = c.riskBreakdown?.joinToString("\n"),
                    updatedAtEpochMillis = ahora
                )
            }
            if (genericos.isNotEmpty()) screenerDao.insertTop10GenericCandidates(genericos)

            screenerDao.upsertRunMeta(
                ScreenerRunMetaEntity(
                    indexName = indexName,
                    lastRunEpochMillis = resultado.generatedAtEpochMillis,
                    symbolsScanned = resultado.symbolsWithData,
                    totalSymbols = resultado.totalSymbols
                )
            )
            true
        } catch (e: Exception) {
            android.util.Log.w("RemoteScanImport", "$indexName: fallo al importar JSON remoto (${e.message ?: e::class.simpleName})")
            false
        }
    }

    suspend fun runFullScreen(indexName: String, onProgress: suspend (done: Int, total: Int) -> Unit) =
        com.inversionadvisor.data.connectivity.NetworkActivityTracker.trackHeavyNetworkActivity {
            runFullScreenInternal(indexName, onProgress)
        }

    private suspend fun runFullScreenInternal(indexName: String, onProgress: suspend (done: Int, total: Int) -> Unit) = coroutineScope {
        val sectorContext = marketRepository.computeSectorContext()
        val sectorInFavor = sectorContext.isSectorInFavor
        // .distinctBy(symbol): red de seguridad además del arreglo en el parser — si por lo
        // que sea quedara algún símbolo duplicado en el universo (p. ej. caché antigua de
        // antes de este arreglo), aquí se corta antes de escanearlo dos veces.
        val universe = stockUniverseRepository.getUniverseForScreening(indexName).distinctBy { it.symbol }
        val now = System.currentTimeMillis()
        val total = universe.size
        val doneCount = AtomicInteger(0)
        val semaphore = Semaphore(SCAN_CONCURRENCY)

        // Se limpia UNA vez al principio (no al final) — los resultados de la ejecución
        // anterior desaparecen en cuanto se pulsa "Analizar", y la lista se va rellenando
        // desde cero según van llegando los nuevos, en vez de sustituir todo de golpe al terminar.
        screenerDao.clearUptrendCandidates(indexName)
        screenerDao.clearBuyOpportunities(indexName)
        screenerDao.clearTop10GenericCandidates(indexName)

        universe.map { entry ->
            async {
                val result = semaphore.withPermit {
                    try {
                        scanSymbol(entry, sectorInFavor, sectorContext, now)
                    } catch (e: Exception) {
                        // Un fallo puntual en un símbolo (delisted, sin datos, timeout...)
                        // no debe cortar el escaneo completo de los demás.
                        null
                    }
                }
                // Se inserta AQUÍ, símbolo a símbolo, en cuanto se tiene el resultado —
                // no se acumula en una lista para insertar toda junta al final.
                result?.uptrend?.let { screenerDao.insertUptrendCandidates(listOf(it)) }
                result?.opportunity?.let { screenerDao.insertBuyOpportunities(listOf(it)) }
                result?.genericCandidate?.let { screenerDao.insertTop10GenericCandidates(listOf(it)) }
                onProgress(doneCount.incrementAndGet(), total)
            }
        }.awaitAll()

        screenerDao.upsertRunMeta(
            ScreenerRunMetaEntity(indexName = indexName, lastRunEpochMillis = now, symbolsScanned = universe.size, totalSymbols = universe.size)
        )
    }

    private data class ScanResult(
        val opportunity: BuyOpportunityEntity?,
        val uptrend: UptrendCandidateEntity?,
        val genericCandidate: com.inversionadvisor.data.local.entities.Top10GenericCandidateEntity?
    )

    private suspend fun scanSymbol(
        entry: StockUniverseEntry,
        sectorInFavor: Map<String, Boolean>,
        sectorContext: com.inversionadvisor.data.repository.MarketRepository.SectorContext,
        now: Long
    ): ScanResult? {
        marketRepository.refreshYahooCandlesBulkIfStale(entry.symbol, ChartRange.ONE_YEAR)
        val candles = marketRepository.observeCandles(entry.symbol, ChartRange.ONE_YEAR).first()
        val sectorName = Symbols.SECTOR_ETFS[entry.sectorEtf] ?: entry.sectorEtf
        // Gratis: misma vela ya descargada arriba, sin petición extra — se usa en "Consejos de
        // inversión" (riesgo de sobrecompra / actividad inusual), no se enseña en la lista normal del screener.
        val rsi14 = TechnicalAnalysis.calculateRsi(candles).lastOrNull()
        val volumeRatio = TechnicalAnalysis.volumeRatio(candles)
        // Diagnóstico temporal: para comparar contra el mismo cálculo hecho desde la ficha de
        // detalle de un stock (ver StockDetailViewModel) cuando dan RSI distinto para el mismo
        // símbolo — con esto se ve si es la MISMA vela final (misma fecha/cierre) en los dos
        // sitios, o si de verdad son datos distintos.
        android.util.Log.i(
            "RsiDiagnostic",
            "[ESCANER] ${entry.symbol}: ${candles.size} velas, última=${candles.lastOrNull()?.let { "${it.datetime} cierre=${it.close}" }}, RSI14=$rsi14"
        )

        val uptrendSignal = UptrendDetector.evaluate(candles)

        val exhaustion = ExhaustionDetector.detect(candles)
        // CORREGIDO — fallo real (CPRI/SHOE/NVDA colándose en Posibles Compras, ver
        // PosiblesComprasRepository): detected==true por sí solo no basta — se exige además
        // que NO haya a la vez tendencia alcista confirmada (un parón dentro de una tendencia
        // alcista no es "una bajista que se agota") y que el volumen esté de verdad muy por
        // encima de la media. ENDURECIDO Y LUEGO RECALIBRADO a petición expresa ("quiero que el
        // agotamiento, el volumen y sobre todo el giro al alza sean más pronunciados" — y tras
        // comprobar con datos reales que la primera versión dejaba el pool en 0 candidatos, ver
        // comentario junto a las constantes en el companion object para el razonamiento exacto
        // de cada cifra). Mismas cifras en scanner-cli/Main.kt y PosiblesComprasRepository.
        // NUEVO — confluencia técnica para Posibles Compras (ver PosiblesComprasRepository):
        // patrón de giro, doble suelo, divergencia y soporte cercano ya salían gratis de estas
        // MISMAS velas semanales (candlestickPatternProvisional/doubleTopBottomResultProvisional
        // se reutilizan más abajo para el cajón genérico, sin recalcularlos dos veces). El
        // volumen DIARIO es la única pieza que necesita una petición nueva (no se pedían velas
        // diarias en el escaneo en directo hasta ahora) — solo en este camino de respaldo (el
        // JSON remoto, la vía normal, no la necesita: ya viene calculada por scanner-cli).
        val candlestickPatternProvisional = TechnicalAnalysis.detectCandlestickPattern(candles)
        val doubleTopBottomResultProvisional = UptrendDetector.detectDoubleTopOrBottom(candles)
        val momentumPriceDivergenceProvisional = UptrendDetector.detectMomentumPriceDivergence(candles)
        val levelsProvisional = TechnicalAnalysis.detectSupportResistanceLevels(candles)
        val nearestSupportPercentProvisional = levelsProvisional
            .filter { it.type == com.inversionadvisor.domain.indicators.LevelType.SUPPORT && it.price <= candles.last().close }
            .maxByOrNull { it.price }?.let { (candles.last().close - it.price) / candles.last().close * 100 }
        val dailyVolumeRatioProvisional = try {
            marketRepository.refreshDailyCandlesForSmaIfStale(entry.symbol, ChartRange.ONE_YEAR)
            TechnicalAnalysis.volumeRatio(marketRepository.observeDailyCandlesForSma(entry.symbol, ChartRange.ONE_YEAR).first())
        } catch (e: Exception) {
            null
        }
        // NUEVO — pedido expresamente: "solo candidatos con MACD alcista, descarta los MACD
        // bajistas, como criterio añadido a los que ya teníamos" — histograma > 0 = alcista.
        val macdProvisional = TechnicalAnalysis.calculateMacd(candles)

        // QUITADO el volumen SEMANAL de este gate a petición expresa ("por qué mido volumen
        // semanal y diario, con uno me vale, el más restrictivo") — el volumen DIARIO (≥1,5x,
        // dentro de la confluencia técnica de PosiblesComprasRepository) es el único filtro de
        // volumen que queda; medía lo mismo dos veces y el semanal (≥1,0) era el más débil de
        // los dos, casi no descartaba nada por sí solo.
        val opportunity = if (exhaustion != null && exhaustion.detected && uptrendSignal == null &&
            exhaustion.declinePercentFromRecentHigh <= -com.inversionadvisor.domain.indicators.Top10Calculator.MIN_DECLINE_PERCENT &&
            exhaustion.confidenceScore >= MIN_CONFIDENCE_SCORE_FOR_BUY_OPPORTUNITY &&
            (macdProvisional?.histogram ?: 0.0) > 0.0) {
            val athInfo = TechnicalAnalysis.analyzeAllTimeHigh(candles)
            BuyOpportunityEntity(
                indexName = entry.indexName,
                symbol = entry.symbol,
                name = entry.name,
                sectorEtf = entry.sectorEtf,
                sectorName = sectorName,
                isSectorInFavor = sectorInFavor[entry.sectorEtf] ?: false,
                confidenceScore = exhaustion.confidenceScore,
                reasonsCsv = exhaustion.reasons.joinToString("||"),
                recentHigh = exhaustion.recentHigh,
                recentLow = exhaustion.recentLow,
                recentLowDate = exhaustion.recentLowDate,
                declinePercentFromRecentHigh = exhaustion.declinePercentFromRecentHigh,
                nextResistanceTarget = exhaustion.nextResistanceTarget,
                recoveryProgressToResistancePercent = exhaustion.recoveryProgressToResistancePercent,
                allTimeHigh = athInfo?.allTimeHigh,
                allTimeHighDate = athInfo?.allTimeHighDate,
                percentFromAth = athInfo?.percentFromAth,
                rsi14 = rsi14,
                volumeRatio = volumeRatio,
                dailyVolumeRatio = dailyVolumeRatioProvisional,
                candlestickPattern = candlestickPatternProvisional?.name,
                doubleTopBottomPattern = doubleTopBottomResultProvisional.pattern.name,
                momentumPriceDivergence = momentumPriceDivergenceProvisional.name,
                nearestSupportPercent = nearestSupportPercentProvisional,
                macdHistogram = macdProvisional?.histogram,
                updatedAtEpochMillis = now
            )
        } else null

        // ---- TERCER CAJÓN, NUEVO: candidato genérico para Top10 por PUNTUACIÓN ----
        // Pedido expresamente tras detectar que un stock con muy buena puntuación en la fórmula
        // completa (caso real: SanDisk, 85 puntos) podía quedarse fuera de Top10 por completo,
        // simplemente por no tener ni tendencia alcista clara ni caída con agotamiento. Se
        // calcula aquí una puntuación PROVISIONAL (con los datos ya baratos de este escaneo —
        // sin PER, sin volatilidad propia, sin velas diarias/mensuales, esas las añade después
        // Top10Repository en su fase profunda) y, si supera el umbral, se guarda como candidato
        // — independientemente de si tiene tendencia clara o caída con agotamiento. La tendencia
        // alcista clara (hasClearUptrend) YA NO decide si el stock entra o no: se guarda aquí
        // para aplicarse DESPUÉS como bono en Top10Calculator (parámetro hasLongTermUptrend).
        // NUEVO — pedido expresamente (fallo real: HCH no aparecía penalizado, faltaba en este
        // 4º sitio) — se calculan aquí también los 3 patrones de techo/suelo, gratis con las
        // MISMAS velas semanales de 1 año ya cargadas (sin ninguna petición nueva). HCH usa
        // aquí solo 1 año (no 5, a diferencia de scanner-cli/ficha del stock/tarjeta) porque
        // pedir 5 años extra para cientos de símbolos en el escaneo en directo saldría caro —
        // es una aproximación más pobre, aceptada a propósito para esta puntuación PROVISIONAL
        // (la tarjeta, al mostrarse, recalcula todo en directo con los 5 años completos).
        val hchResultProvisional = UptrendDetector.detectHeadAndShoulders(candles)
        // doubleTopBottomResultProvisional ya se calculó más arriba (se reutiliza aquí, ver
        // comentario junto a la entrada de Posibles Compras).
        val tripleTopBottomResultProvisional = UptrendDetector.detectTripleTopOrBottom(candles)
        val provisionalTop10Score = com.inversionadvisor.domain.indicators.Top10Calculator.score(
            trendQuality = uptrendSignal?.trendQuality,
            yearChangePercent = uptrendSignal?.yearChangePercent,
            aboveTrendSma = uptrendSignal?.aboveTrendSma,
            isSectorInFavor = sectorInFavor[entry.sectorEtf],
            longTermDecline = exhaustion,
            shortTermPullback = null,
            rsi14 = rsi14,
            volumeRatio = volumeRatio,
            stockVolatilityRatio = null,
            stockPe = null,
            sectorAveragePe = null,
            sectorTypicalPeRange = null,
            percentFromYearHigh = null,
            nearestSupportPercent = null,
            nearestResistancePercent = null,
            candlestickPattern = null,
            marketTrap = null,
            macd = null,
            hchResult = hchResultProvisional,
            doubleTopBottomResult = doubleTopBottomResultProvisional,
            tripleTopBottomResult = tripleTopBottomResultProvisional,
            hasLongTermUptrend = uptrendSignal != null,
            hasLongTermDowntrend = UptrendDetector.evaluateDowntrend(candles),
            benchmarkYearChangePercent = sectorContext.benchmarkYearChangePercent,
            sectorDeclinePercent = sectorContext.sectorDeclinePercentByEtf[entry.sectorEtf],
            requireSignal = false
        )?.combinedScore

        // CAMBIADO a petición expresa: "Valores Alcistas" (antes "Tendencia alcista") ya NO
        // selecciona por tendencia limpia (uptrendSignal != null) — ahora selecciona por
        // PUNTUACIÓN, con el mismo umbral (≥55, UMBRAL_ENTRADA_TOP10_GENERICO) que ya usa el
        // "cajón genérico" de Top10, para ser coherente con ese otro criterio ya establecido.
        // uptrendSignal se sigue calculando arriba y pasando a hasLongTermUptrend más abajo —
        // solo cambia QUIÉN aparece en la lista, no lo que cuenta como "tendencia alcista clara"
        // de cara al bono/penalización de esa categoría en la fórmula.
        val uptrend = if (provisionalTop10Score != null && provisionalTop10Score >= UMBRAL_ENTRADA_TOP10_GENERICO) {
            UptrendCandidateEntity(
                indexName = entry.indexName,
                symbol = entry.symbol,
                name = entry.name,
                sectorEtf = entry.sectorEtf,
                sectorName = sectorName,
                yearChangePercent = uptrendSignal?.yearChangePercent ?: 0.0,
                trendQuality = uptrendSignal?.trendQuality ?: 0.0,
                rsi14 = rsi14,
                volumeRatio = volumeRatio,
                isSectorInFavor = sectorInFavor[entry.sectorEtf] ?: false,
                updatedAtEpochMillis = now
            )
        } else null

        val genericCandidate = if (provisionalTop10Score != null && provisionalTop10Score >= UMBRAL_ENTRADA_TOP10_GENERICO) {
            com.inversionadvisor.data.local.entities.Top10GenericCandidateEntity(
                indexName = entry.indexName,
                symbol = entry.symbol,
                name = entry.name,
                sectorEtf = entry.sectorEtf,
                sectorName = sectorName,
                isSectorInFavor = sectorInFavor[entry.sectorEtf] ?: false,
                rsi14 = rsi14,
                volumeRatio = volumeRatio,
                provisionalScore = provisionalTop10Score,
                hasClearUptrend = uptrendSignal != null,
                updatedAtEpochMillis = now
            )
        } else null

        return if (opportunity == null && uptrend == null && genericCandidate == null) null
        else ScanResult(opportunity, uptrend, genericCandidate)
    }

    /**
     * Rotación sectorial (mismos 13 ETFs y benchmark S&P 500 que usa el
     * dashboard, con la MISMA caché de velas semanales de 1 año — así que
     * si el dashboard ya las pidió, el escaneo no vuelve a gastarlas) para
     * saber qué sectores están en auge, también en paralelo. Ahora vive en
     * MarketRepository (compartido con la ficha de un stock suelto, ver
     * StockDetailViewModel) — antes era una copia privada solo de aquí.
     * CORREGIDO — este envoltorio ya no se usaba (se llama directamente a
     * marketRepository.computeSectorContext() más arriba, la versión rica), se quita para no
     * dejar código muerto.
     */

    companion object {
        /**
         * Nº de símbolos escaneados a la vez. Acotado con Semaphore para no
         * disparar cientos de corrutinas a la vez; el propio rate limiter
         * del cliente HTTP "bulk" (200/min) es el límite real de fondo.
         */
        private const val SCAN_CONCURRENCY = 20

        /**
         * PENDIENTE DE CONFIGURAR — pedido expresamente: sustituir por tu usuario/repositorio
         * real de GitHub (el mismo donde está el workflow .github/workflows/scan.yml) antes de
         * compilar, con el formato "usuario/repositorio/rama" (p. ej.
         * "santi123/InversionAdvisor/main"). Sin esto configurado bien, importFromRemoteJson()
         * fallará siempre (404) y la app caerá automáticamente al escaneo en directo de siempre
         * — no rompe nada, simplemente no aprovecha la ventaja de velocidad hasta que se rellene.
         */
        private const val GITHUB_REPO_PATH = "santiatienza-collab/InversionAdvisor/main"

        /** Umbral de puntuación PROVISIONAL (0-100) para entrar en el cajón genérico de Top10
         *  — pedido expresamente "filtrar a los que tengan más puntuación": 55 es un punto
         *  medio razonable (por encima del neutro 50), pensado para no llenar la tabla de
         *  candidatos mediocres que de todas formas nunca entrarían en el Top 10 final, sin
         *  ser tan alto como para volver a excluir candidatos fuertes por poco. */
        private const val UMBRAL_ENTRADA_TOP10_GENERICO = 55.0

        // Criterio de entrada de "Posibles Compras" — ver comentario en scanSymbol() para el
        // razonamiento completo. Mismas cifras en scanner-cli/Main.kt y
        // PosiblesComprasRepository.kt.
        /** "aumenta el volumen de operaciones" — RECALIBRADO con datos reales tras dejar el pool
         *  en 0/389 candidatos con 1,5 (el mismo umbral que "fuera de lo normal" en otros sitios
         *  de la app, pero ESE se calcula sobre velas del rango que se esté viendo, no siempre
         *  semanales). Aquí volumeRatio compara la ÚLTIMA SEMANA contra su propia media de 20
         *  semanas — con datos reales, de 358 candidatos con agotamiento ya detectado, solo 6
         *  llegaban a 1,3 y apenas 4 a 1,5 (mediana real: 0,37). 1,0 (la semana actual iguala o
         *  supera su propia media reciente) sigue siendo un cambio de comportamiento real, sin
         *  vaciar el pool. */
        const val MIN_VOLUME_RATIO_FOR_BUY_OPPORTUNITY = 1.0

        /** confidenceScore = 25 (caída) + recoveryScore (0-75). RECALIBRADO de 75 a 70 (mismo
         *  motivo que el volumen: 75 exigía recoveryScore ≥50, es decir 3 de los 4 indicios a la
         *  vez, demasiado poco frecuente combinado con el resto de filtros) — 70 (recoveryScore
         *  ≥45) sigue siendo más exigente que el mínimo bruto (≥40) que ya exige detected==true
         *  por sí solo, sin ser tan raro como para vaciar el pool. */
        const val MIN_CONFIDENCE_SCORE_FOR_BUY_OPPORTUNITY = 70
    }
}

// ---- Mappers ----

private fun UptrendCandidateEntity.toDomain(): UptrendCandidate = UptrendCandidate(
    symbol = symbol,
    name = name,
    sectorEtf = sectorEtf,
    sectorName = sectorName,
    indexName = indexName,
    yearChangePercent = yearChangePercent,
    trendQuality = trendQuality,
    rsi14 = rsi14,
    volumeRatio = volumeRatio,
    isSectorInFavor = isSectorInFavor
)

private fun BuyOpportunityEntity.toDomain(): BuyOpportunity {
    val exhaustionSignal = ExhaustionSignal(
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
    val allTimeHighInfo = allTimeHigh?.let { ath ->
        val pct = percentFromAth ?: 0.0
        AllTimeHighInfo(
            allTimeHigh = ath,
            allTimeHighDate = allTimeHighDate ?: "",
            currentPrice = ath * (1 + pct / 100),
            percentFromAth = pct,
            isNewAllTimeHigh = pct >= 0.0
        )
    }
    // Señal fuerte (varios indicios coinciden) = verde; señal razonable = ámbar.
    val riskLevel = if (confidenceScore >= 65) RiskLevel.LOW else RiskLevel.MEDIUM

    return BuyOpportunity(
        symbol = symbol,
        name = name,
        sectorEtf = sectorEtf,
        sectorName = sectorName,
        indexName = indexName,
        isSectorInFavor = isSectorInFavor,
        exhaustionSignal = exhaustionSignal,
        allTimeHighInfo = allTimeHighInfo,
        riskLevel = riskLevel,
        rsi14 = rsi14,
        volumeRatio = volumeRatio
    )
}

private fun ScreenerRunMetaEntity.toDomain(): ScreenerRunMeta = ScreenerRunMeta(
    indexName = indexName,
    lastRunEpochMillis = lastRunEpochMillis,
    symbolsScanned = symbolsScanned,
    totalSymbols = totalSymbols
)
