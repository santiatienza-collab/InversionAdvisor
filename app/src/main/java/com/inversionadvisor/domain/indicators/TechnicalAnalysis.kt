package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Candle
import kotlin.math.abs

data class SwingPoint(
    val index: Int,
    val datetime: String,
    val price: Double,
    val type: SwingType
)

enum class SwingType { HIGH, LOW }

enum class LevelType { SUPPORT, RESISTANCE }

data class SupportResistanceLevel(
    val price: Double,
    val touches: Int,
    val type: LevelType
)

data class AllTimeHighInfo(
    val allTimeHigh: Double,
    val allTimeHighDate: String,
    val currentPrice: Double,
    /** Negativo si el precio actual está por debajo del máximo histórico del rango cargado. */
    val percentFromAth: Double,
    val isNewAllTimeHigh: Boolean
)

/**
 * Cálculos de análisis técnico "puro" (sin dependencias de red/DB), operan
 * sobre una lista de velas ya ordenada cronológicamente (ascendente).
 *
 * Nota sobre "máximo histórico": está acotado al rango de velas que le pases
 * (p. ej. 5 años). Para un ATH real de toda la vida del stock habría que pedir
 * el máximo outputsize disponible en la API para ese símbolo.
 */
object TechnicalAnalysis {

    /**
     * Para cada vela SEMANAL (la que usa un gráfico para la línea de precio), busca en las
     * velas DIARIAS la más reciente cuya fecha sea IGUAL O ANTERIOR a esa semana, y coge el
     * valor de la media en ese punto — así una SMA20/SMA50 calculada con precisión sobre datos
     * DIARIOS (20/50 días de mercado de verdad) se puede superponer sobre el eje semanal del
     * gráfico, en vez de calcularla directamente sobre las velas semanales (que daría 20/50
     * SEMANAS, no días). Función COMPARTIDA — usada tanto por la ficha de un stock como por la
     * tarjeta genérica de índices/divisas/bonos (antes esta última no la usaba, y por eso tenía
     * el mismo problema que ya se había corregido en la ficha del stock).
     *
     * Las fechas (`datetime`) son cadenas ISO-8601 (java.time.Instant.toString(), p. ej.
     * "2026-08-25T00:00:00Z") — comparables directamente como texto sin parsear a objetos de
     * fecha, porque ese formato ordena igual alfabéticamente que cronológicamente.
     */
    fun alignDailySmaToWeeklyDates(weeklyCandles: List<Candle>, dailyCandles: List<Candle>, dailySmaValues: List<Double?>): List<Double?> {
        if (dailyCandles.isEmpty() || dailySmaValues.isEmpty()) return List(weeklyCandles.size) { null }
        return weeklyCandles.map { weeklyCandle ->
            var bestIndex = -1
            for (i in dailyCandles.indices) {
                if (dailyCandles[i].datetime <= weeklyCandle.datetime) {
                    bestIndex = i
                } else {
                    break
                }
            }
            if (bestIndex in dailySmaValues.indices) dailySmaValues[bestIndex] else null
        }
    }

    /**
     * MACD(12,26,9) — primera vez que se usa en la app, para el criterio "Volumen de ruptura /
     * MACD" de la fórmula de ponderación. OJO: aquí las velas de 1 año son SEMANALES (52), no
     * diarias — así que este es un MACD calculado sobre semanas, no el MACD diario habitual de
     * cualquier plataforma de bolsa. Devuelve null si no hay suficientes velas (hacen falta al
     * menos 26+9 para tener señal).
     */
    data class MacdResult(val macdLine: Double, val signalLine: Double, val histogram: Double)

    fun calculateMacd(candles: List<Candle>, fastPeriod: Int = 12, slowPeriod: Int = 26, signalPeriod: Int = 9): MacdResult? {
        if (candles.size < slowPeriod + signalPeriod) return null
        val closes = candles.map { it.close }

        fun ema(values: List<Double>, period: Int): List<Double> {
            val k = 2.0 / (period + 1)
            val result = mutableListOf(values.first())
            for (i in 1 until values.size) {
                result += values[i] * k + result[i - 1] * (1 - k)
            }
            return result
        }

        val fastEma = ema(closes, fastPeriod)
        val slowEma = ema(closes, slowPeriod)
        val macdSeries = fastEma.indices.map { fastEma[it] - slowEma[it] }
        val signalSeries = ema(macdSeries, signalPeriod)
        val macdLine = macdSeries.last()
        val signalLine = signalSeries.last()
        return MacdResult(macdLine, signalLine, macdLine - signalLine)
    }

    /**
     * NUEVO — pedido expresamente: histograma MACD completo, alineado vela a vela (a diferencia
     * de [calculateMacd], que solo da el último valor) — para poder DIBUJARLO como panel propio
     * debajo del volumen, igual que hace cualquier plataforma de bolsa. Los primeros
     * (slowPeriod-1) valores son técnicamente EMA "calentando" (no una media de verdad todavía,
     * les falta historial) — se devuelven igualmente por simplicidad de índice (mismo tamaño que
     * [candles]), la interfaz simplemente no dibuja gran cosa ahí al no haber datos suficientes
     * antes.
     */
    fun calculateMacdSeries(candles: List<Candle>, fastPeriod: Int = 12, slowPeriod: Int = 26, signalPeriod: Int = 9): List<MacdResult> {
        if (candles.size < slowPeriod + signalPeriod) return emptyList()
        val closes = candles.map { it.close }

        fun ema(values: List<Double>, period: Int): List<Double> {
            val k = 2.0 / (period + 1)
            val result = mutableListOf(values.first())
            for (i in 1 until values.size) {
                result += values[i] * k + result[i - 1] * (1 - k)
            }
            return result
        }

        val fastEma = ema(closes, fastPeriod)
        val slowEma = ema(closes, slowPeriod)
        val macdSeries = fastEma.indices.map { fastEma[it] - slowEma[it] }
        val signalSeries = ema(macdSeries, signalPeriod)
        return macdSeries.indices.map { i -> MacdResult(macdSeries[i], signalSeries[i], macdSeries[i] - signalSeries[i]) }
    }

    /** Aviso de divergencia entre el MACD y el precio — ver [detectMacdPriceDivergence]. */
    enum class MacdPriceDivergence {
        NONE,
        /** Precio subiendo, MACD bajando — el rally pierde fuelle (el impulso ya no acompaña al
         *  precio, aunque este siga subiendo). */
        BEARISH,
        /** Precio bajando, MACD subiendo — la presión vendedora se agota (el impulso ya no
         *  acompaña la caída, aunque el precio siga bajando). */
        BULLISH
    }

    /** Resultado completo de [detectMacdPriceDivergence]: la divergencia en sí (definida SOLO
     *  por MACD y precio, la definición clásica de análisis técnico) más si el volumen la
     *  refuerza o no — ver comentario de la función para el porqué de separar ambas cosas. */
    data class MacdDivergenceResult(
        val divergence: MacdPriceDivergence,
        /** true si, ADEMÁS de la divergencia MACD/precio, el volumen de ese mismo tramo está
         *  decreciendo de forma real — participación menguando, una confirmación adicional, NO
         *  un requisito para que haya divergencia. false (no "sin dato") cuando divergence es
         *  NONE, ya que ahí la pregunta no aplica. */
        val volumenConfirma: Boolean
    )

    /**
     * NUEVO — pedido expresamente: "detecte cuando haya una divergencia entre MACD y el precio,
     * observando el volumen de operaciones, y muestre un aviso amarillo cuando se dé el caso".
     *
     * CAMBIADO a petición expresa tras una pregunta directa del usuario ("la divergencia debe
     * tener en cuenta MACD y precio, ¿el volumen hay que considerarlo también?"): la divergencia
     * MACD/precio es, por definición de análisis técnico, un patrón que se define SOLO con esas
     * dos series — el volumen NO forma parte de la definición clásica. La primera versión de
     * esta función exigía el volumen menguante como requisito OBLIGATORIO (gate) — eso podía
     * tapar divergencias MACD/precio reales y evidentes solo porque el volumen no bajaba lo
     * suficiente. Ahora el volumen es una CONFIRMACIÓN APARTE, no bloqueante: la divergencia se
     * detecta con MACD+precio únicamente, y volumenConfirma añade un matiz ("reforzada por el
     * volumen") cuando también cae, sin que su ausencia impida el aviso.
     *
     * Divergencia clásica: precio y MACD moviéndose en direcciones opuestas durante la misma
     * ventana reciente, con una tendencia limpia de verdad (no ruido) en las dos, y un
     * movimiento de precio mínimo real — mismo criterio de regresión lineal (pendiente + R²)
     * que ya usa UptrendDetector.detectMomentumPriceDivergence con el RSI.
     */
    // RECALIBRADO a petición expresa ("la divergencia debe ser más evidente o pronunciada para
    // detectarla"): la primera versión solo exigía que las dos pendientes (precio y MACD)
    // tuvieran SIGNOS opuestos con una tendencia mínimamente limpia (R²≥0.3) — bastaba un
    // movimiento pequeño y ruidoso para marcar divergencia. Ahora se exige, además de los signos
    // opuestos: ventana más larga (14 en vez de 10 velas) y R²≥0.5 en precio y MACD (una
    // tendencia limpia de verdad, no un zigzag que por casualidad tiene pendiente de un signo),
    // y que el precio se haya movido un mínimo real (≥3%) en la ventana.
    private const val MACD_DIVERGENCE_WINDOW = 14
    private const val MACD_DIVERGENCE_MIN_R2 = 0.5
    private const val MACD_DIVERGENCE_MIN_PRICE_MOVE_PERCENT = 3.0
    private const val MACD_DIVERGENCE_MIN_VOLUME_DROP_PERCENT = 20.0

    fun detectMacdPriceDivergence(candles: List<Candle>, window: Int = MACD_DIVERGENCE_WINDOW): MacdDivergenceResult {
        val macdSeries = calculateMacdSeries(candles)
        if (macdSeries.size < window || candles.size < window) return MacdDivergenceResult(MacdPriceDivergence.NONE, false)

        val recentCandles = candles.takeLast(window)
        val recentMacd = macdSeries.takeLast(window).map { it.macdLine }

        fun linreg(values: List<Double>): Pair<Double, Double> {
            val n = values.size
            val xs = (0 until n).map { it.toDouble() }
            val xMean = xs.average()
            val yMean = values.average()
            val num = xs.indices.sumOf { (xs[it] - xMean) * (values[it] - yMean) }
            val den = xs.sumOf { (it - xMean) * (it - xMean) }
            if (den == 0.0) return 0.0 to 0.0
            val slope = num / den
            val predicted = xs.map { yMean + slope * (it - xMean) }
            val ssRes = values.indices.sumOf { (values[it] - predicted[it]) * (values[it] - predicted[it]) }
            val ssTot = values.sumOf { (it - yMean) * (it - yMean) }
            val r2 = if (ssTot == 0.0) 0.0 else 1 - ssRes / ssTot
            return slope to r2
        }

        val closes = recentCandles.map { it.close }
        val (priceSlope, priceR2) = linreg(closes)
        val (macdSlope, macdR2) = linreg(recentMacd)

        val priceMovePercent = if (closes.first() != 0.0) kotlin.math.abs(closes.last() - closes.first()) / closes.first() * 100 else 0.0
        val tendenciasClaras = priceR2 >= MACD_DIVERGENCE_MIN_R2 && macdR2 >= MACD_DIVERGENCE_MIN_R2 &&
            priceMovePercent >= MACD_DIVERGENCE_MIN_PRICE_MOVE_PERCENT

        val divergence = when {
            !tendenciasClaras -> MacdPriceDivergence.NONE
            priceSlope > 0 && macdSlope < 0 -> MacdPriceDivergence.BEARISH
            priceSlope < 0 && macdSlope > 0 -> MacdPriceDivergence.BULLISH
            else -> MacdPriceDivergence.NONE
        }
        if (divergence == MacdPriceDivergence.NONE) return MacdDivergenceResult(MacdPriceDivergence.NONE, false)

        // Volumen — solo se calcula/importa si YA hay divergencia por MACD+precio; aquí es
        // confirmación aparte, no condición para detectarla (ver comentario de cabecera).
        val recentVolumes = recentCandles.mapNotNull { it.volume?.toDouble() }
        val volumenConfirma = if (recentVolumes.size == window) {
            val mitad = recentVolumes.size / 2
            val volMediaPrimeraMitad = recentVolumes.take(mitad).average()
            val volMediaSegundaMitad = recentVolumes.takeLast(recentVolumes.size - mitad).average()
            val volumenCaidaPercent = if (volMediaPrimeraMitad > 0) (volMediaPrimeraMitad - volMediaSegundaMitad) / volMediaPrimeraMitad * 100 else 0.0
            volumenCaidaPercent >= MACD_DIVERGENCE_MIN_VOLUME_DROP_PERCENT
        } else false

        return MacdDivergenceResult(divergence, volumenConfirma)
    }

    /** % de variación entre el cierre de hace [n] velas y el cierre actual — null si no hay suficientes velas. */
    fun returnOverLastNCandles(candles: List<Candle>, n: Int): Double? {
        if (candles.size <= n) return null
        val past = candles[candles.size - 1 - n].close
        val current = candles.last().close
        if (past == 0.0) return null
        return (current - past) / past * 100
    }

    /**
     * Patrón de vela más reciente (martillo/estrella fugaz/envolventes/doji) — compartido entre
     * BuyOpportunityAnalyzer y Top10Calculator/Top10Repository para que ambos usen exactamente
     * la misma detección, no dos copias que puedan divergir.
     */
    fun detectCandlestickPattern(candles: List<Candle>): CandlestickPattern? {
        if (candles.isEmpty()) return null
        val last = candles.last()
        val body = kotlin.math.abs(last.close - last.open)
        val range = last.high - last.low
        if (range <= 0.0) return null
        val upperWick = last.high - maxOf(last.open, last.close)
        val lowerWick = minOf(last.open, last.close) - last.low

        if (body / range < 0.1) return CandlestickPattern.DOJI
        if (lowerWick >= body * 2 && upperWick <= body * 0.5) return CandlestickPattern.HAMMER
        if (upperWick >= body * 2 && lowerWick <= body * 0.5) return CandlestickPattern.SHOOTING_STAR

        if (candles.size >= 2) {
            val prev = candles[candles.size - 2]
            val prevBody = kotlin.math.abs(prev.close - prev.open)
            val prevIsBearish = prev.close < prev.open
            val prevIsBullish = prev.close > prev.open
            val lastIsBullish = last.close > last.open
            val lastIsBearish = last.close < last.open

            if (lastIsBullish && prevIsBearish && last.open <= prev.close && last.close >= prev.open && body > prevBody) {
                return CandlestickPattern.BULLISH_ENGULFING
            }
            if (lastIsBearish && prevIsBullish && last.open >= prev.close && last.close <= prev.open && body > prevBody) {
                return CandlestickPattern.BEARISH_ENGULFING
            }
        }
        return null
    }

    /** Trampa de mercado (ruptura de soporte/resistencia que no se sostiene) — compartida, ver detectCandlestickPattern. */
    fun detectTrap(candles: List<Candle>, levels: List<SupportResistanceLevel>): MarketTrapType? {
        if (candles.size < 10) return null
        val recentWindow = candles.takeLast(10)
        val current = recentWindow.last()
        val previousInWindow = recentWindow.dropLast(1)

        for (level in levels.filter { it.type == LevelType.RESISTANCE }) {
            val brokeAbove = previousInWindow.any { it.close > level.price * 1.005 }
            if (brokeAbove && current.close < level.price) return MarketTrapType.BULL_TRAP
        }
        for (level in levels.filter { it.type == LevelType.SUPPORT }) {
            val brokeBelow = previousInWindow.any { it.close < level.price * 0.995 }
            if (brokeBelow && current.close > level.price) return MarketTrapType.BEAR_TRAP
        }
        return null
    }

    /**
     * Volumen de la última vela ÷ media de las [lookback] anteriores — 1.0 =
     * volumen normal, >1 = por encima de lo habitual. Misma lógica que ya
     * usaba SellTimingAnalyzer.volumeFactor en solitario; se saca aquí para
     * poder reutilizarla también en el escaneo del screener (gratis, mismas
     * velas ya descargadas) sin duplicar el cálculo.
     */
    /**
     * OJO — usa la SEGUNDA vela por el final (la última semana YA CERRADA), no la última (que
     * en el rango de "1 año" de esta app es la semana EN CURSO, sin terminar). Antes se
     * comparaba el volumen acumulado de una semana a medias contra la media de 20 semanas
     * completas — eso hacía que el ratio saliera sistemáticamente bajo (casi siempre <0,7)
     * salvo que se consultara el viernes al cierre, disparando el "riesgo alto"/negativo casi
     * siempre sin reflejar el volumen real del stock.
     */
    fun volumeRatio(candles: List<Candle>, lookback: Int = 20): Double? {
        if (candles.size < lookback + 2) return null
        val closedCandles = candles.dropLast(1) // quita la semana en curso, sin cerrar
        val recent = closedCandles.takeLast(lookback + 1)
        val lastVolume = recent.last().volume ?: return null
        val baseline = recent.dropLast(1).mapNotNull { it.volume }
        if (baseline.isEmpty()) return null
        val avgVolume = baseline.average()
        if (avgVolume == 0.0) return null
        return lastVolume / avgVolume
    }

    /**
     * Desviación de la volatilidad RECIENTE (últimas [recentWindow] velas) frente a la
     * volatilidad HABITUAL de este mismo stock (últimas [baselineWindow]) — no el VIX de
     * mercado, es la volatilidad propia comparada consigo mismo. >1 = se está moviendo más de
     * lo habitual PARA ÉL. Se saca aquí (compartida) para no duplicarla entre
     * BuyOpportunityAnalyzer y Top10Repository.
     */
    fun ownVolatilityRatio(candles: List<Candle>, recentWindow: Int = 4, baselineWindow: Int = 26, debugSymbol: String? = null): Double? {
        if (candles.size < baselineWindow + 1) return null
        // FALLO REAL ENCONTRADO (con Monster Beverage y su split de julio 2026): Yahoo puede
        // aplicar el ajuste por split de forma INCONSISTENTE entre 2-3 semanas contiguas, justo
        // en la transición — un zigzag artificial (p. ej. -48%, +87%, -48% en 3 semanas
        // seguidas) que no es un movimiento de mercado real, es un artefacto de los propios
        // datos. Se descartan los rendimientos semana-a-semana descabellados (más de un 40% en
        // una sola semana, positivo o negativo) al calcular la desviación — no se intenta
        // "adivinar" cuál sería el valor correcto de esa vela (probado, y es fácil corregir la
        // vela equivocada, dejando el problema igual o peor), simplemente no se deja que ese
        // punto aislado, casi con toda seguridad un artefacto, infle la desviación.
        val maxPlausibleWeeklyReturn = 0.40
        fun stdDevOfReturns(window: List<Candle>): Double? {
            if (window.size < 2) return null
            val allReturns = window.zipWithNext { a, b -> if (a.close != 0.0) (b.close - a.close) / a.close else 0.0 }
            val returns = allReturns.filter { kotlin.math.abs(it) <= maxPlausibleWeeklyReturn }
            if (returns.size < 2) return null // casi todo descartado, sin datos fiables suficientes
            val mean = returns.average()
            val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
            return kotlin.math.sqrt(variance)
        }
        val baselineCandles = candles.takeLast(baselineWindow)
        val recentCandles = candles.takeLast(recentWindow)
        val baseline = stdDevOfReturns(baselineCandles) ?: return null
        val recent = stdDevOfReturns(recentCandles) ?: return null
        if (baseline == 0.0) return null
        val ratio = recent / baseline
        // Diagnóstico opcional — para poder ver los cierres y rendimientos semanales REALES que
        // producen un ratio sorprendente (p. ej. Monster Beverage marcando 0.1), en vez de tener
        // que confiar a ciegas en el resultado final. Solo se activa si se pasa debugSymbol.
        if (debugSymbol != null) {
            val baselineCloses = baselineCandles.map { "%.2f".format(it.close) }
            val recentCloses = recentCandles.map { "%.2f".format(it.close) }
            android.util.Log.i(
                "VolatilityDiagnostic",
                "$debugSymbol: ratio=${"%.3f".format(ratio)} (recienteDesv=${"%.4f".format(recent)} / baseDesv=${"%.4f".format(baseline)}) — " +
                    "cierres base (${baselineWindow} sem): $baselineCloses — cierres reciente (${recentWindow} sem): $recentCloses"
            )
        }
        return ratio
    }

    /** Detecta máximos/mínimos locales comparando cada vela con `lookback` velas a cada lado. */
    fun findSwingPoints(candles: List<Candle>, lookback: Int = 3): List<SwingPoint> {
        if (candles.size < lookback * 2 + 1) return emptyList()
        val points = mutableListOf<SwingPoint>()
        for (i in lookback until candles.size - lookback) {
            val window = candles.subList(i - lookback, i + lookback + 1)
            val current = candles[i]
            if (current.high == window.maxOf { it.high }) {
                points += SwingPoint(i, current.datetime, current.high, SwingType.HIGH)
            }
            if (current.low == window.minOf { it.low }) {
                points += SwingPoint(i, current.datetime, current.low, SwingType.LOW)
            }
        }
        return points
    }

    /**
     * Agrupa swing points cercanos en zonas de soporte/resistencia. Dos toques
     * dentro de `clusterTolerancePercent` se consideran el mismo nivel; solo se
     * devuelven niveles con al menos `minTouches` toques (más fiables).
     */
    fun detectSupportResistanceLevels(
        candles: List<Candle>,
        lookback: Int = 3,
        clusterTolerancePercent: Double = 1.5,
        minTouches: Int = 2
    ): List<SupportResistanceLevel> {
        val swings = findSwingPoints(candles, lookback)
        val highs = swings.filter { it.type == SwingType.HIGH }.map { it.price }
        val lows = swings.filter { it.type == SwingType.LOW }.map { it.price }

        fun cluster(prices: List<Double>, type: LevelType): List<SupportResistanceLevel> {
            if (prices.isEmpty()) return emptyList()
            val sorted = prices.sorted()
            val clusters = mutableListOf<MutableList<Double>>()
            for (p in sorted) {
                val last = clusters.lastOrNull()
                if (last != null && abs(p - last.average()) / last.average() * 100 <= clusterTolerancePercent) {
                    last += p
                } else {
                    clusters += mutableListOf(p)
                }
            }
            return clusters
                .filter { it.size >= minTouches }
                .map { SupportResistanceLevel(price = it.average(), touches = it.size, type = type) }
        }

        return cluster(highs, LevelType.RESISTANCE) + cluster(lows, LevelType.SUPPORT)
    }

    /** Máximo histórico dentro del rango de velas cargado, y si el precio actual lo acaba de superar. */
    fun analyzeAllTimeHigh(candles: List<Candle>): AllTimeHighInfo? {
        if (candles.isEmpty()) return null
        val athCandle = candles.maxByOrNull { it.high } ?: return null
        val current = candles.last()
        val pctFromAth = (current.close - athCandle.high) / athCandle.high * 100
        return AllTimeHighInfo(
            allTimeHigh = athCandle.high,
            allTimeHighDate = athCandle.datetime,
            currentPrice = current.close,
            percentFromAth = pctFromAth,
            isNewAllTimeHigh = current.close >= athCandle.high
        )
    }

    /** RSI clásico de Wilder (periodo 14 por defecto). Devuelve null para las primeras `period` velas. */
    fun calculateRsi(candles: List<Candle>, period: Int = 14): List<Double?> {
        if (candles.size <= period) return candles.map { null }
        val rsis = MutableList<Double?>(candles.size) { null }
        var avgGain = 0.0
        var avgLoss = 0.0
        for (i in 1..period) {
            val change = candles[i].close - candles[i - 1].close
            if (change > 0) avgGain += change else avgLoss += -change
        }
        avgGain /= period
        avgLoss /= period
        rsis[period] = rsiFromAverages(avgGain, avgLoss)
        for (i in period + 1 until candles.size) {
            val change = candles[i].close - candles[i - 1].close
            val gain = if (change > 0) change else 0.0
            val loss = if (change < 0) -change else 0.0
            avgGain = (avgGain * (period - 1) + gain) / period
            avgLoss = (avgLoss * (period - 1) + loss) / period
            rsis[i] = rsiFromAverages(avgGain, avgLoss)
        }
        return rsis
    }

    private fun rsiFromAverages(avgGain: Double, avgLoss: Double): Double {
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100 - (100 / (1 + rs))
    }

    fun simpleMovingAverage(candles: List<Candle>, period: Int): List<Double?> {
        return candles.indices.map { i ->
            if (i < period - 1) null
            else candles.subList(i - period + 1, i + 1).map { it.close }.average()
        }
    }
}
