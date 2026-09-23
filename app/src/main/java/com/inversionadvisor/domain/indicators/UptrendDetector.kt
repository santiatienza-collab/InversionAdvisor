package com.inversionadvisor.domain.indicators

import com.inversionadvisor.domain.model.Candle

/**
 * Detecta si un stock tiene una tendencia alcista "de fondo" clara durante
 * el último año — a diferencia de BullishStreakScreener (que exige 10
 * semanas SEGUIDAS al alza, muy estricto), este filtro es el que alimenta
 * las pestañas de mercado del screener (S&P 500 / Nasdaq-100 / IBEX 35):
 * cualquier stock cuya trayectoria del año, tomada en conjunto, sea
 * claramente alcista, aunque haya tenido semanas sueltas a la baja.
 *
 * Combina 4 señales sobre velas SEMANALES (ChartRange.ONE_YEAR, ~52 velas):
 *  1) El precio actual está por encima de su media de 40 semanas.
 *  2) Esa media de 40 semanas tiene pendiente positiva (la tendencia de
 *     fondo sigue subiendo, no está aplanada o girando a la baja).
 *  3) La regresión lineal del precio de las últimas 52 semanas tiene
 *     pendiente positiva y un ajuste (R²) razonable — evita "tendencias"
 *     que en realidad son solo ruido lateral con suerte.
 *  4) El mínimo de la segunda mitad del año no es sensiblemente más bajo
 *     que el de la primera mitad (mínimos crecientes, no un simple rebote
 *     técnico sobre una base que seguía cayendo).
 */

/**
 * Resultado de detectar divergencia alcista MOMENTUM/PRECIO (el momentum, medido con RSI,
 * sube mientras el precio baja) — el MISMO patrón técnico de base, interpretado de forma
 * distinta según el contexto de tendencia en el que aparece. Ver
 * UptrendDetector.detectMomentumPriceDivergence().
 */
enum class MomentumPriceDivergence {
    /** Sin divergencia detectada — no aplica ni bono ni penalización. */
    NONE,
    /** Al final de una tendencia BAJISTA LARGA: posible suelo de mercado y cambio a tendencia
     *  alcista. Fiabilidad ALTA — BONUS +5. */
    LONG_DOWNTREND_POSSIBLE_BOTTOM,
    /** Durante un retroceso DENTRO de una tendencia alcista ya establecida: posible final de
     *  la corrección temporal, para reanudar la subida. Fiabilidad MUY ALTA — BONUS +5. */
    UPTREND_PULLBACK_ENDING,
    /** En mitad de una tendencia bajista FUERTE que todavía no muestra signos de agotamiento
     *  claro: posible rebote técnico pasajero (trampa de toros). Fiabilidad MEDIA/BAJA —
     *  PENALIZA -5. */
    DOWNTREND_BULL_TRAP_RISK
}

/** Resultado de detectFlagPattern() — ver esa función para la definición completa. */
enum class FlagPattern { NONE, BULLISH, BEARISH }

/** Resultado de detectDoubleTopOrBottom() — ver esa función para la definición completa. */
enum class DoubleTopBottomPattern { NONE, DOUBLE_TOP, DOUBLE_BOTTOM }

/**
 * Resultado completo de detectDoubleTopOrBottom() — además de qué patrón (o ninguno), las
 * fechas y precios EXACTOS de los dos picos (doble techo) o valles (doble suelo) detectados,
 * para poder mostrar la fecha junto al aviso y dibujar los dos círculos en el gráfico.
 */
data class DoubleTopBottomResult(
    val pattern: DoubleTopBottomPattern,
    val firstDate: String? = null,
    val secondDate: String? = null,
    val firstPrice: Double? = null,
    val secondPrice: Double? = null
)

object UptrendDetector {

    /**
     * Detecta un patrón de "bandera" (alcista o bajista) en las ÚLTIMAS 15 velas — pedido
     * expresamente así, aplicable tanto a velas DIARIAS (bandera "a corto plazo") como
     * MENSUALES (bandera "a largo plazo"), pasando la serie de velas que corresponda.
     *
     * Una bandera es un patrón de dos fases dentro de esas 15 velas:
     *  1) "MÁSTIL" (las primeras ~8 velas): un movimiento fuerte y bastante continuo en una
     *     dirección (subida para alcista, bajada para bajista) — al menos un 8% de cambio.
     *  2) "BANDERA" (las últimas ~8 velas, con solape en la vela central): una consolidación
     *     en un rango MUCHO más estrecho que el del mástil (si no, sería solo "sigue
     *     moviéndose igual", no una pausa) — con una deriva ligera en sentido CONTRARIO al
     *     mástil, pero SIN retroceder más de la mitad de lo que subió/bajó el mástil (si
     *     retrocede más de la mitad, ya no es una "pausa", es una reversión).
     *
     * AVISO HONESTO: esto es una APROXIMACIÓN con umbrales fijos razonables, no un detector de
     * patrones chartistas riguroso (ese tipo de reconocimiento visual es un problema mucho más
     * complejo, típicamente resuelto con visión por computador sobre el propio gráfico, no con
     * unos pocos umbrales sobre precios de cierre/máximos/mínimos) — mismo espíritu que el resto
     * de aproximaciones ya existentes en la app (p. ej. "toppingApprox" en Top10Calculator).
     */
    fun detectFlagPattern(candles: List<Candle>): FlagPattern? {
        if (candles.size < 15) return null
        val window = candles.takeLast(15)
        val poleCandles = window.subList(0, 8)   // velas 0-7: el "mástil"
        val flagCandles = window.subList(7, 15)  // velas 7-14: la "bandera" (solapa en la 7, la bisagra entre las dos fases)

        val poleStart = poleCandles.first().close
        val poleEnd = poleCandles.last().close
        if (poleStart == 0.0 || poleEnd == 0.0) return FlagPattern.NONE
        val poleChangePercent = (poleEnd - poleStart) / poleStart * 100
        val poleRangePercent = kotlin.math.abs(poleChangePercent)

        val flagHigh = flagCandles.maxOf { it.high }
        val flagLow = flagCandles.minOf { it.low }
        val flagRangePercent = if (poleEnd != 0.0) (flagHigh - flagLow) / poleEnd * 100 else 0.0

        val flagStart = flagCandles.first().close
        val flagEnd = flagCandles.last().close
        if (flagStart == 0.0) return FlagPattern.NONE
        val flagChangePercent = (flagEnd - flagStart) / flagStart * 100

        val isBullFlag = poleChangePercent >= 8.0 &&
            flagRangePercent < poleRangePercent * 0.6 &&
            flagChangePercent > -poleChangePercent * 0.5 &&
            flagChangePercent < poleChangePercent * 0.3

        val isBearFlag = poleChangePercent <= -8.0 &&
            flagRangePercent < poleRangePercent * 0.6 &&
            flagChangePercent < -poleChangePercent * 0.5 &&
            flagChangePercent > poleChangePercent * 0.3

        return when {
            isBullFlag -> FlagPattern.BULLISH
            isBearFlag -> FlagPattern.BEARISH
            else -> FlagPattern.NONE
        }
    }

    /**
     * Cuenta cuántas de las ÚLTIMAS 6 velas MENSUALES cerraron en negativo (cierre < apertura
     * de ESE mes) — pedido expresamente para penalizar tendencias bajistas CONSOLIDADAS, que la
     * categoría "Tendencia" (solo mira los últimos 3 meses, y se anula si hay cualquier giro de
     * corto plazo) puede dejar sin penalizar aunque la debilidad de fondo siga ahí. null si no
     * hay al menos 6 velas mensuales disponibles.
     */

    /**
     * Detecta un patrón de "doble techo" (bajista) o "doble suelo" (alcista) sobre las velas
     * SEMANALES ya cargadas para el resto de la fórmula (1 año) — sin pedir ningún dato nuevo.
     *
     * DOBLE TECHO: dos máximos SIMILARES (a menos de una tolerancia de entre el 1% y el 3% de
     * diferencia entre sí, según la volatilidad propia del activo — ver
     * peakToleranceForVolatility) separados por un valle intermedio significativo (que baje al
     * menos un 5% respecto a ambos picos — si no, sería solo una meseta, no dos picos de verdad
     * diferenciados), con un máximo de 6 MESES (26 semanas, pedido expresamente así) entre los
     * dos picos. DOBLE SUELO es el espejo exacto, con mínimos en vez de máximos.
     *
     * AVISO HONESTO: aproximación con umbrales fijos razonables, no un reconocimiento visual
     * riguroso de patrones chartistas — mismo espíritu que detectFlagPattern.
     */
    fun detectDoubleTopOrBottom(candles: List<Candle>, debugSymbol: String? = null): DoubleTopBottomResult {
        if (candles.size < 10) return DoubleTopBottomResult(DoubleTopBottomPattern.NONE)
        // PULIDO A PETICIÓN EXPRESA — encontrado que la versión anterior daba demasiados falsos
        // positivos: bastaba con que los dos picos/valles fueran parecidos ENTRE SÍ, sin
        // importar en qué parte del rango del año estuvieran (podían ser dos picos a mitad de
        // camino, sin haber tocado ninguna resistencia/soporte real). Ahora, ADEMÁS de lo de
        // antes, se exige que los dos picos (doble techo) estén cerca del MÁXIMO DE TODO EL AÑO
        // — resistencia real, no un pico cualquiera — y que los dos valles (doble suelo) estén
        // cerca del MÍNIMO DE TODO EL AÑO — soporte real.
        val yearHigh = candles.maxOf { it.high }
        val yearLow = candles.minOf { it.low }
        if (yearHigh <= 0.0 || yearLow <= 0.0) return DoubleTopBottomResult(DoubleTopBottomPattern.NONE)

        // AMPLIADO A PETICIÓN EXPRESA tras el caso real de BG: antes la ventana de búsqueda de
        // picos/valles era de solo 32 semanas (~7,5 meses), aunque el "máximo del año" (yearHigh)
        // se calculaba sobre el año COMPLETO (52 semanas) — así que un pico más alto entre la
        // semana 33 y la 52 quedaba completamente fuera de la búsqueda, aunque estuviera dentro
        // del año. Ahora la ventana es el año completo, coherente con yearHigh/yearLow.
        val window = candles // AMPLIADO de takeLast(32) a todo el año — caso real BG, un pico más alto entre semana 33 y 52 quedaba fuera de la búsqueda

        // NUEVO, A PETICIÓN EXPRESA — tolerancia de similitud entre los dos picos/valles ya no
        // es un 3% fijo: varía entre 1% y 3% según la volatilidad propia del activo (ver
        // peakToleranceForVolatility). Un activo tranquilo exige que los dos máximos/mínimos
        // sean casi idénticos; uno muy volátil necesita más margen para no descartar patrones
        // válidos solo por su ruido normal.
        val peakTolerance = peakToleranceForVolatility(window)

        val localMaxima = mutableListOf<Int>()
        val localMinima = mutableListOf<Int>()
        for (i in 2 until window.size - 2) {
            if ((i - 2..i + 2).all { j -> j == i || window[j].high <= window[i].high }) localMaxima.add(i)
            if ((i - 2..i + 2).all { j -> j == i || window[j].low >= window[i].low }) localMinima.add(i)
        }

        if (debugSymbol != null) {
            android.util.Log.i(
                "DoubleTopDiagnostic",
                "$debugSymbol: yearHigh=${"%.2f".format(yearHigh)} yearLow=${"%.2f".format(yearLow)} tolerancia=${"%.2f".format(peakTolerance)}% — " +
                    "picos: " + localMaxima.joinToString(" | ") { "${window[it].datetime.take(10)}=${"%.2f".format(window[it].high)}" } +
                    " — valles: " + localMinima.joinToString(" | ") { "${window[it].datetime.take(10)}=${"%.2f".format(window[it].low)}" }
            )
        }

        // CORREGIDO — pedido expresamente: la versión anterior recorría las combinaciones de
        // picos/valles de la MÁS ANTIGUA a la MÁS RECIENTE, quedándose con la PRIMERA que
        // encajara — no necesariamente la pareja más reciente o más evidente a la vista (casos
        // reales: NVIDIA y CMCSA). Ahora se prueba primero desde los picos/valles más recientes,
        // igual que ya hace detectHeadAndShoulders.
        for (i in localMaxima.indices.reversed()) {
            // CORREGIDO — pedido expresamente tras los casos reales de WDAY, CMCSA y BG: antes se
            // devolvía el PRIMER candidato que encajara para esta fecha reciente, y luego se
            // elegía el de PICO MÁS ALTO entre los válidos — pero la definición correcta de doble
            // techo no es "el más alto", es el más SIMÉTRICO (los dos picos al mismo nivel). Ahora
            // se comprueban TODOS los candidatos válidos para esta misma fecha reciente y se
            // elige el par con MENOR diferencia porcentual entre los dos picos.
            var mejorJ = -1
            var mejorDiff = Double.MAX_VALUE
            for (j in (i - 1) downTo 0) {
                val idx1 = localMaxima[j]
                val idx2 = localMaxima[i]
                if (idx2 - idx1 > 26) continue // FIJADO a petición expresa: la ventana de formación completa (primer a segundo pico/valle) es de 6 meses (26 semanas), con granularidad semanal
                val peak1 = window[idx1].high
                val peak2 = window[idx2].high
                // NUEVO — ambos picos deben estar cerca del máximo de TODO el año (dentro de un
                // 3%), no solo parecidos entre sí — si no, no son una resistencia real.
                if ((yearHigh - peak1) / yearHigh * 100 > 3.0) continue
                if ((yearHigh - peak2) / yearHigh * 100 > 3.0) continue
                val avgPeak = (peak1 + peak2) / 2
                if (avgPeak <= 0.0) continue
                val levelDiffPercent = kotlin.math.abs(peak1 - peak2) / avgPeak * 100
                if (levelDiffPercent > peakTolerance) continue // los dos picos deben estar a un nivel similar entre sí (tolerancia según volatilidad)
                // NUEVO — pedido expresamente tras el caso real de CMCSA: antes solo se exigía
                // que cada pico fuera el máximo de una ventana MUY estrecha (±2 semanas), lo que
                // dejaba pasar picos suaves/redondeados que apenas destacaban de sus vecinos
                // inmediatos (el valle intermedio podía ser profundo y aun así el segundo pico en
                // sí mismo no "parecer" un pico real). Ahora, ADEMÁS, cada pico debe destacar un
                // mínimo razonable sobre una ventana más ANCHA (±5 semanas) — ver prominenciaDePico.
                if (prominenciaDePico(window, idx1) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                if (prominenciaDePico(window, idx2) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                // NUEVO — pedido expresamente tras el caso real de NVIDIA: el patrón que se
                // quedaba tenía los dos picos a solo 3 semanas uno de otro, con un "valle" que en
                // realidad no era ningún mínimo local identificado (localMinima) — solo el precio
                // más bajo de esas pocas velas, una caída de mentira, no una recuperación real del
                // mercado entre dos techos. Ahora se exige que exista un mínimo local GENUINO
                // (de los ya detectados arriba) entre los dos picos, no solo calcular el punto
                // más bajo de cualquier tramo de velas.
                if (localMinima.none { it in (idx1 + 1) until idx2 }) continue
                val neckline = (idx1..idx2).minOf { window[it].low }
                val troughDropPercent = (avgPeak - neckline) / avgPeak * 100
                if (troughDropPercent < 10.0) continue // AUMENTADO de 5% a 10% — pedido expresamente, casos con un valle intermedio apenas visible (poco pronunciado)
                // NUEVO — pedido expresamente (definición citada por el usuario): "el retroceso
                // debe abarcar al menos un tercio del impulso previo". Antes solo exigíamos un
                // 10% fijo sobre la media de los picos, sin relacionarlo con lo que costó subir
                // hasta el primer pico — un valle del 10% es insuficiente si el primer pico venía
                // de una subida enorme (habría que retroceder mucho más para ser "significativo"
                // de verdad). Se busca el mínimo (swing low) en las hasta 20 semanas antes del
                // primer pico, y se exige que la caída hasta el valle intermedio sea al menos un
                // tercio de esa subida previa.
                val inicioImpulso = (idx1 - 20).coerceAtLeast(0)
                val swingLowAntesPico1 = (inicioImpulso..idx1).minOf { window[it].low }
                val impulsoPrevio = peak1 - swingLowAntesPico1
                if (impulsoPrevio > 0 && (avgPeak - neckline) < impulsoPrevio / 3.0) continue
                // CONFIRMACIÓN OBLIGATORIA — pedida expresamente así: el patrón NO EXISTE hasta
                // que el precio ROMPE la línea de cuello (este valle intermedio) DESPUÉS del
                // segundo pico. Hace falta una vela que CIERRE por debajo de esa línea — sin
                // esto, es indistinguible de un simple lateral con dos picos parecidos (la
                // causa real de los falsos positivos detectados). Si el precio solo TOCA la
                // línea pero ninguna vela cierra por debajo (rebota), el patrón queda
                // invalidado — aquí, simplemente no se detecta nada (NONE), como corresponde.
                val confirmedBreakdown = (idx2 + 1 until window.size).any { k -> window[k].close < neckline }
                if (!confirmedBreakdown) continue
                // NUEVO — pedido expresamente: "si el patrón se ha producido hace más de 6 meses
                // desde que se consolidó no entraría en la puntuación". Se busca la primera vela
                // que rompió la línea de cuello (la confirmación en sí) y, si desde entonces han
                // pasado 26 semanas o más, el patrón se descarta — sigue probando otras
                // combinaciones en vez de devolver un patrón ya "viejo".
                val idxConfirmacion = (idx2 + 1 until window.size).first { k -> window[k].close < neckline }
                val semanasDesdeConfirmacion = window.size - 1 - idxConfirmacion
                if (semanasDesdeConfirmacion > CADUCIDAD_PATRON_SEMANAS) {
                    if (debugSymbol != null) {
                        android.util.Log.i(
                            "DoubleTopDiagnostic",
                            "$debugSymbol: descartado por caducidad — confirmado hace $semanasDesdeConfirmacion semanas (> $CADUCIDAD_PATRON_SEMANAS)"
                        )
                    }
                    continue
                }
                if (levelDiffPercent < mejorDiff) {
                    mejorJ = j
                    mejorDiff = levelDiffPercent
                }
            }
            if (mejorJ >= 0) {
                val idx1 = localMaxima[mejorJ]
                val idx2 = localMaxima[i]
                val peak1 = window[idx1].high
                val peak2 = window[idx2].high
                val neckline = (idx1..idx2).minOf { window[it].low }
                if (debugSymbol != null) {
                    android.util.Log.i(
                        "DoubleTopDiagnostic",
                        "$debugSymbol: DOBLE TECHO ENCONTRADO — ${window[idx1].datetime.take(10)}(${"%.2f".format(peak1)}) y " +
                            "${window[idx2].datetime.take(10)}(${"%.2f".format(peak2)}), neckline=${"%.2f".format(neckline)}"
                    )
                }
                return DoubleTopBottomResult(
                    pattern = DoubleTopBottomPattern.DOUBLE_TOP,
                    firstDate = window[idx1].datetime,
                    secondDate = window[idx2].datetime,
                    firstPrice = peak1,
                    secondPrice = peak2
                )
            }
        }

        for (i in localMinima.indices.reversed()) {
            // Mismo motivo que en el doble techo (casos reales WDAY/CMCSA/BG) — se comprueban
            // TODOS los candidatos válidos para esta fecha reciente y se elige el par MÁS
            // SIMÉTRICO (menor diferencia porcentual entre los dos valles), no el más bajo ni
            // el primero que encaje.
            var mejorJ = -1
            var mejorDiff = Double.MAX_VALUE
            for (j in (i - 1) downTo 0) {
                val idx1 = localMinima[j]
                val idx2 = localMinima[i]
                if (idx2 - idx1 > 26) continue // FIJADO a petición expresa: la ventana de formación completa (primer a segundo pico/valle) es de 6 meses (26 semanas), con granularidad semanal
                val trough1 = window[idx1].low
                val trough2 = window[idx2].low
                // NUEVO — ambos valles deben estar cerca del mínimo de TODO el año (dentro de un
                // 3%), no solo parecidos entre sí — si no, no son un soporte real.
                if ((trough1 - yearLow) / yearLow * 100 > 3.0) continue
                if ((trough2 - yearLow) / yearLow * 100 > 3.0) continue
                val avgTrough = (trough1 + trough2) / 2
                if (avgTrough <= 0.0) continue
                val levelDiffPercent = kotlin.math.abs(trough1 - trough2) / avgTrough * 100
                if (levelDiffPercent > peakTolerance) continue // tolerancia según volatilidad, ver arriba
                // NUEVO — mismo motivo que en el doble techo (ver comentario de arriba, caso real
                // CMCSA): cada valle debe destacar un mínimo razonable sobre una ventana más
                // ancha (±5 semanas), no solo ser el mínimo de una ventana muy estrecha.
                if (prominenciaDeValle(window, idx1) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                if (prominenciaDeValle(window, idx2) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                // Mismo motivo que en el doble techo (caso real NVIDIA) — se exige que exista
                // un máximo local GENUINO entre los dos valles, no solo el punto más alto de
                // cualquier tramo de velas.
                if (localMaxima.none { it in (idx1 + 1) until idx2 }) continue
                val neckline = (idx1..idx2).maxOf { window[it].high }
                val peakRisePercent = (neckline - avgTrough) / avgTrough * 100
                if (peakRisePercent < 10.0) continue // AUMENTADO de 5% a 10% — pedido expresamente tras el caso real de WDAY, cuyo doble suelo casi no se apreciaba
                // NUEVO — mismo motivo que en el doble techo (ver comentario de arriba): el
                // rebote intermedio debe ser al menos un tercio de la caída previa hasta el
                // primer valle, no solo un 10% fijo sobre la media de los valles.
                val inicioCaida = (idx1 - 20).coerceAtLeast(0)
                val swingHighAntesValle1 = (inicioCaida..idx1).maxOf { window[it].high }
                val caidaPrevia = swingHighAntesValle1 - trough1
                if (caidaPrevia > 0 && (neckline - avgTrough) < caidaPrevia / 3.0) continue
                // CONFIRMACIÓN OBLIGATORIA — espejo exacto del doble techo: una vela debe
                // CERRAR POR ENCIMA de la línea de cuello (el pico intermedio) DESPUÉS del
                // segundo valle. Sin esta ruptura, no se detecta nada.
                val confirmedBreakout = (idx2 + 1 until window.size).any { k -> window[k].close > neckline }
                if (!confirmedBreakout) continue
                // Mismo motivo que en el doble techo (ver comentario de arriba) — consistencia.
                val idxConfirmacion = (idx2 + 1 until window.size).first { k -> window[k].close > neckline }
                val semanasDesdeConfirmacion = window.size - 1 - idxConfirmacion
                if (semanasDesdeConfirmacion > CADUCIDAD_PATRON_SEMANAS) {
                    if (debugSymbol != null) {
                        android.util.Log.i(
                            "DoubleTopDiagnostic",
                            "$debugSymbol: descartado por caducidad — confirmado hace $semanasDesdeConfirmacion semanas (> $CADUCIDAD_PATRON_SEMANAS)"
                        )
                    }
                    continue
                }
                if (levelDiffPercent < mejorDiff) {
                    mejorJ = j
                    mejorDiff = levelDiffPercent
                }
            }
            if (mejorJ >= 0) {
                val idx1 = localMinima[mejorJ]
                val idx2 = localMinima[i]
                val trough1 = window[idx1].low
                val trough2 = window[idx2].low
                val neckline = (idx1..idx2).maxOf { window[it].high }
                if (debugSymbol != null) {
                    android.util.Log.i(
                        "DoubleTopDiagnostic",
                        "$debugSymbol: DOBLE SUELO ENCONTRADO — ${window[idx1].datetime.take(10)}(${"%.2f".format(trough1)}) y " +
                            "${window[idx2].datetime.take(10)}(${"%.2f".format(trough2)}), neckline=${"%.2f".format(neckline)}"
                    )
                }
                return DoubleTopBottomResult(
                    pattern = DoubleTopBottomPattern.DOUBLE_BOTTOM,
                    firstDate = window[idx1].datetime,
                    secondDate = window[idx2].datetime,
                    firstPrice = trough1,
                    secondPrice = trough2
                )
            }
        }

        if (debugSymbol != null) {
            android.util.Log.i("DoubleTopDiagnostic", "$debugSymbol: ningún patrón encontrado tras revisar todas las combinaciones")
        }
        return DoubleTopBottomResult(DoubleTopBottomPattern.NONE)
    }

    enum class TripleTopBottomPattern { NONE, TRIPLE_TOP, TRIPLE_BOTTOM }

    data class TripleTopBottomResult(
        val pattern: TripleTopBottomPattern,
        val firstDate: String? = null,
        val secondDate: String? = null,
        val thirdDate: String? = null,
        val firstPrice: Double? = null,
        val secondPrice: Double? = null,
        val thirdPrice: Double? = null
    )

    /**
     * Triple techo / triple suelo — pedido expresamente como patrón NUEVO, aparte del doble
     * techo/suelo (no lo sustituye, se comprueban los dos de forma independiente). Misma lógica
     * que detectDoubleTopOrBottom, pero con TRES picos/valles en vez de dos: los tres deben
     * estar cerca del máximo/mínimo del año (dentro de un 3%) y ser de altura similar entre sí
     * (tolerancia dinámica según volatilidad, igual que el doble techo/suelo), con DOS
     * valles/picos intermedios (entre 1º-2º y entre 2º-3º) suficientemente pronunciados — mismo
     * umbral del 10% ya usado en el doble techo/suelo. Confirmación obligatoria: una vela debe
     * CERRAR por debajo (triple techo) o por encima (triple suelo) de la línea de cuello después
     * del tercer pico/valle — sin ruptura, no se detecta nada, igual que el doble techo/suelo.
     */
    fun detectTripleTopOrBottom(candles: List<Candle>): TripleTopBottomResult {
        if (candles.size < 15) return TripleTopBottomResult(TripleTopBottomPattern.NONE)
        val yearHigh = candles.maxOf { it.high }
        val yearLow = candles.minOf { it.low }
        if (yearHigh <= 0.0 || yearLow <= 0.0) return TripleTopBottomResult(TripleTopBottomPattern.NONE)

        // Ventana algo mayor que la del doble techo/suelo (32) — hacen falta 3 picos/valles en
        // vez de 2, el patrón entero necesita más tiempo para formarse.
        val window = candles.takeLast(40)
        val peakTolerance = peakToleranceForVolatility(window)

        val localMaxima = mutableListOf<Int>()
        val localMinima = mutableListOf<Int>()
        for (i in 2 until window.size - 2) {
            if ((i - 2..i + 2).all { j -> j == i || window[j].high <= window[i].high }) localMaxima.add(i)
            if ((i - 2..i + 2).all { j -> j == i || window[j].low >= window[i].low }) localMinima.add(i)
        }

        // CORREGIDO — mismo motivo que en detectDoubleTopOrBottom (casos reales WDAY/CMCSA):
        // antes se devolvía el PRIMER trío que encajara, sin comprobar si había otro combo más
        // legítimo (más cercano de verdad al máximo del año). Ahora, para cada tercer pico
        // (el más reciente), se comprueban TODAS las parejas de primer/segundo pico válidas y se
        // elige la de media más alta entre las tres.
        for (k in localMaxima.indices.reversed()) {
            var mejorI = -1
            var mejorJ = -1
            var mejorAvgPeak = -1.0
            for (j in (k - 1) downTo 1) {
                for (i in (j - 1) downTo 0) {
                    val idx1 = localMaxima[i]; val idx2 = localMaxima[j]; val idx3 = localMaxima[k]
                    if (idx3 - idx1 > 40) continue
                    val peak1 = window[idx1].high; val peak2 = window[idx2].high; val peak3 = window[idx3].high
                    if ((yearHigh - peak1) / yearHigh * 100 > 3.0) continue
                    if ((yearHigh - peak2) / yearHigh * 100 > 3.0) continue
                    if ((yearHigh - peak3) / yearHigh * 100 > 3.0) continue
                    val avgPeak = (peak1 + peak2 + peak3) / 3
                    if (avgPeak <= 0.0) continue
                    val maxDiff = maxOf(
                        kotlin.math.abs(peak1 - peak2), kotlin.math.abs(peak2 - peak3), kotlin.math.abs(peak1 - peak3)
                    ) / avgPeak * 100
                    if (maxDiff > peakTolerance) continue
                    // Mismo motivo que en detectDoubleTopOrBottom (caso real CMCSA) — cada uno de
                    // los 3 picos debe destacar por sí mismo, no solo depender de valles profundos.
                    if (prominenciaDePico(window, idx1) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    if (prominenciaDePico(window, idx2) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    if (prominenciaDePico(window, idx3) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    // Mismo motivo que en detectDoubleTopOrBottom (caso real NVIDIA) — exige un
                    // valle local GENUINO entre cada par de picos consecutivos.
                    if (localMinima.none { it in (idx1 + 1) until idx2 }) continue
                    if (localMinima.none { it in (idx2 + 1) until idx3 }) continue
                    val valle1 = (idx1..idx2).minOf { window[it].low }
                    val valle2 = (idx2..idx3).minOf { window[it].low }
                    if ((avgPeak - valle1) / avgPeak * 100 < 10.0) continue
                    if ((avgPeak - valle2) / avgPeak * 100 < 10.0) continue
                    val neckline = minOf(valle1, valle2)
                    val confirmedBreakdown = (idx3 + 1 until window.size).any { m -> window[m].close < neckline }
                    if (!confirmedBreakdown) continue
                    // Mismo motivo que en el doble techo/suelo — consistencia.
                    val idxConfirmacion = (idx3 + 1 until window.size).first { m -> window[m].close < neckline }
                    val semanasDesdeConfirmacion = window.size - 1 - idxConfirmacion
                    if (semanasDesdeConfirmacion > CADUCIDAD_PATRON_SEMANAS) continue
                    if (avgPeak > mejorAvgPeak) {
                        mejorI = i
                        mejorJ = j
                        mejorAvgPeak = avgPeak
                    }
                }
            }
            if (mejorI >= 0) {
                val idx1 = localMaxima[mejorI]; val idx2 = localMaxima[mejorJ]; val idx3 = localMaxima[k]
                return TripleTopBottomResult(
                    pattern = TripleTopBottomPattern.TRIPLE_TOP,
                    firstDate = window[idx1].datetime, secondDate = window[idx2].datetime, thirdDate = window[idx3].datetime,
                    firstPrice = window[idx1].high, secondPrice = window[idx2].high, thirdPrice = window[idx3].high
                )
            }
        }

        // Mismo motivo que en el triple techo de arriba (casos reales WDAY/CMCSA).
        for (k in localMinima.indices.reversed()) {
            var mejorI = -1
            var mejorJ = -1
            var mejorAvgTrough = Double.MAX_VALUE
            for (j in (k - 1) downTo 1) {
                for (i in (j - 1) downTo 0) {
                    val idx1 = localMinima[i]; val idx2 = localMinima[j]; val idx3 = localMinima[k]
                    if (idx3 - idx1 > 40) continue
                    val trough1 = window[idx1].low; val trough2 = window[idx2].low; val trough3 = window[idx3].low
                    if ((trough1 - yearLow) / yearLow * 100 > 3.0) continue
                    if ((trough2 - yearLow) / yearLow * 100 > 3.0) continue
                    if ((trough3 - yearLow) / yearLow * 100 > 3.0) continue
                    val avgTrough = (trough1 + trough2 + trough3) / 3
                    if (avgTrough <= 0.0) continue
                    val maxDiff = maxOf(
                        kotlin.math.abs(trough1 - trough2), kotlin.math.abs(trough2 - trough3), kotlin.math.abs(trough1 - trough3)
                    ) / avgTrough * 100
                    if (maxDiff > peakTolerance) continue
                    // Mismo motivo que en detectDoubleTopOrBottom (caso real CMCSA) — cada uno de
                    // los 3 valles debe destacar por sí mismo, no solo depender de picos intermedios altos.
                    if (prominenciaDeValle(window, idx1) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    if (prominenciaDeValle(window, idx2) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    if (prominenciaDeValle(window, idx3) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    // Mismo motivo que en detectDoubleTopOrBottom (caso real NVIDIA) — exige un
                    // pico local GENUINO entre cada par de valles consecutivos.
                    if (localMaxima.none { it in (idx1 + 1) until idx2 }) continue
                    if (localMaxima.none { it in (idx2 + 1) until idx3 }) continue
                    val pico1 = (idx1..idx2).maxOf { window[it].high }
                    val pico2 = (idx2..idx3).maxOf { window[it].high }
                    if ((pico1 - avgTrough) / avgTrough * 100 < 10.0) continue
                    if ((pico2 - avgTrough) / avgTrough * 100 < 10.0) continue
                    val neckline = maxOf(pico1, pico2)
                    val confirmedBreakout = (idx3 + 1 until window.size).any { m -> window[m].close > neckline }
                    if (!confirmedBreakout) continue
                    // Mismo motivo que en el doble techo/suelo — consistencia.
                    val idxConfirmacion = (idx3 + 1 until window.size).first { m -> window[m].close > neckline }
                    val semanasDesdeConfirmacion = window.size - 1 - idxConfirmacion
                    if (semanasDesdeConfirmacion > CADUCIDAD_PATRON_SEMANAS) continue
                    if (avgTrough < mejorAvgTrough) {
                        mejorI = i
                        mejorJ = j
                        mejorAvgTrough = avgTrough
                    }
                }
            }
            if (mejorI >= 0) {
                val idx1 = localMinima[mejorI]; val idx2 = localMinima[mejorJ]; val idx3 = localMinima[k]
                return TripleTopBottomResult(
                    pattern = TripleTopBottomPattern.TRIPLE_BOTTOM,
                    firstDate = window[idx1].datetime, secondDate = window[idx2].datetime, thirdDate = window[idx3].datetime,
                    firstPrice = window[idx1].low, secondPrice = window[idx2].low, thirdPrice = window[idx3].low
                )
            }
        }

        return TripleTopBottomResult(TripleTopBottomPattern.NONE)
    }

    /** Resultado de detectTripleTopForSellTiming() — mismos estados que HeadAndShouldersState
     *  (NONE/FORMING/CONFIRMED), reutilizados aquí por comodidad. */
    data class TripleTopSellResult(
        val state: HeadAndShouldersState,
        val firstDate: String? = null,
        val secondDate: String? = null,
        val thirdDate: String? = null
    )

    /**
     * Variante de detectTripleTopOrBottom (solo el lado "techo", igual que
     * detectDoubleTopForSellTiming) que distingue el estado "en formación" — para el bono de
     * "Momento idóneo para la venta". Un triple SUELO no se comprueba aquí — es señal de compra,
     * no de venta, igual que el doble suelo tampoco entra en este análisis.
     */
    fun detectTripleTopForSellTiming(candles: List<Candle>): TripleTopSellResult {
        if (candles.size < 15) return TripleTopSellResult(HeadAndShouldersState.NONE)
        val yearHigh = candles.maxOf { it.high }
        if (yearHigh <= 0.0) return TripleTopSellResult(HeadAndShouldersState.NONE)
        val window = candles.takeLast(40)
        val peakTolerance = peakToleranceForVolatility(window)
        val localMaxima = mutableListOf<Int>()
        val localMinima = mutableListOf<Int>()
        for (i in 2 until window.size - 2) {
            if ((i - 2..i + 2).all { j -> j == i || window[j].high <= window[i].high }) localMaxima.add(i)
            if ((i - 2..i + 2).all { j -> j == i || window[j].low >= window[i].low }) localMinima.add(i)
        }
        // Mismo motivo que en detectTripleTopOrBottom (casos reales WDAY/CMCSA) — consistencia.
        for (k in localMaxima.indices.reversed()) {
            var mejorI = -1
            var mejorJ = -1
            var mejorAvgPeak = -1.0
            for (j in (k - 1) downTo 1) {
                for (i in (j - 1) downTo 0) {
                    val idx1 = localMaxima[i]; val idx2 = localMaxima[j]; val idx3 = localMaxima[k]
                    if (idx3 - idx1 > 40) continue
                    val peak1 = window[idx1].high; val peak2 = window[idx2].high; val peak3 = window[idx3].high
                    if ((yearHigh - peak1) / yearHigh * 100 > 3.0) continue
                    if ((yearHigh - peak2) / yearHigh * 100 > 3.0) continue
                    if ((yearHigh - peak3) / yearHigh * 100 > 3.0) continue
                    val avgPeak = (peak1 + peak2 + peak3) / 3
                    if (avgPeak <= 0.0) continue
                    val maxDiff = maxOf(
                        kotlin.math.abs(peak1 - peak2), kotlin.math.abs(peak2 - peak3), kotlin.math.abs(peak1 - peak3)
                    ) / avgPeak * 100
                    if (maxDiff > peakTolerance) continue
                    // Mismo motivo que en detectDoubleTopOrBottom (caso real CMCSA) — consistencia.
                    if (prominenciaDePico(window, idx1) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    if (prominenciaDePico(window, idx2) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    if (prominenciaDePico(window, idx3) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                    // Mismo motivo que en detectDoubleTopOrBottom (caso real NVIDIA) — consistencia.
                    if (localMinima.none { it in (idx1 + 1) until idx2 }) continue
                    if (localMinima.none { it in (idx2 + 1) until idx3 }) continue
                    val valle1 = (idx1..idx2).minOf { window[it].low }
                    val valle2 = (idx2..idx3).minOf { window[it].low }
                    if ((avgPeak - valle1) / avgPeak * 100 < 10.0) continue
                    if ((avgPeak - valle2) / avgPeak * 100 < 10.0) continue
                    if (avgPeak > mejorAvgPeak) {
                        mejorI = i
                        mejorJ = j
                        mejorAvgPeak = avgPeak
                    }
                }
            }
            if (mejorI >= 0) {
                val idx1 = localMaxima[mejorI]; val idx2 = localMaxima[mejorJ]; val idx3 = localMaxima[k]
                val valle1 = (idx1..idx2).minOf { window[it].low }
                val valle2 = (idx2..idx3).minOf { window[it].low }
                val neckline = minOf(valle1, valle2)
                val confirmedBreakdown = (idx3 + 1 until window.size).any { m -> window[m].close < neckline }
                // NUEVO — pedido expresamente: si la ruptura ocurrió hace más de 6 meses
                // (CADUCIDAD_PATRON_SEMANAS), el patrón ya no cuenta — se prueba con cabezas
                // más antiguas (siguiente k) en vez de devolver una ruptura "vieja" como si
                // fuera una señal reciente.
                val estaCaducado = confirmedBreakdown && (window.size - 1 - (idx3 + 1 until window.size).first { m -> window[m].close < neckline }) > CADUCIDAD_PATRON_SEMANAS
                if (!estaCaducado) {
                    return TripleTopSellResult(
                        state = if (confirmedBreakdown) HeadAndShouldersState.CONFIRMED else HeadAndShouldersState.FORMING,
                        firstDate = window[idx1].datetime, secondDate = window[idx2].datetime, thirdDate = window[idx3].datetime
                    )
                }
            }
        }
        return TripleTopSellResult(HeadAndShouldersState.NONE)
    }

    /** % mínimo que un pico/valle del doble o triple techo/suelo debe destacar sobre una ventana
     *  más ancha que la usada para detectarlo como máximo/mínimo local — pedido expresamente tras
     *  el caso real de CMCSA (segundo pico del doble techo poco pronunciado, pese a que el valle
     *  intermedio sí lo era). Ver prominenciaDePico/prominenciaDeValle. */
    private const val UMBRAL_PROMINENCIA_PICO_VALLE = 3.0

    /** Semanas desde la ruptura de la línea de cuello a partir de las cuales el doble/triple
     *  techo o suelo se considera "caducado" y deja de puntuar — pedido expresamente: "si el
     *  patrón se ha producido hace más de 6 meses desde que se consolidó no entraría en la
     *  puntuación de bonus y penalizaciones". A diferencia del HCH (78 semanas, pensado para un
     *  patrón que puede abarcar hasta 5 años), aquí el patrón entero como mucho separa sus dos
     *  extremos 45 semanas — 6 meses de margen tras la ruptura ya deja todo el patrón bien
     *  dentro del año de datos que se analiza. */
    private const val CADUCIDAD_PATRON_SEMANAS = 26

    /**
     * Cuánto destaca un pico sobre una ventana MÁS ANCHA (±[ladoSemanas] semanas, 5 por defecto)
     * que la usada para detectarlo como máximo local (±2 semanas en detectDoubleTopOrBottom) —
     * pedido expresamente: antes solo se exigía ser el máximo de esa ventana muy estrecha, lo que
     * dejaba pasar picos suaves/redondeados que apenas destacaban de sus vecinos inmediatos (caso
     * real: CMCSA). Se mide como la caída hasta el mínimo de esa ventana ancha, en % sobre el
     * propio pico — un pico genuino sobresale claramente incluso mirando más lejos a los lados;
     * uno plano/redondeado apenas baja del nivel del pico ni alejándose de él.
     */
    private fun prominenciaDePico(window: List<Candle>, idx: Int, ladoSemanas: Int = 5): Double {
        val inicio = (idx - ladoSemanas).coerceAtLeast(0)
        val fin = (idx + ladoSemanas).coerceAtMost(window.size - 1)
        val minEntorno = (inicio..fin).minOf { window[it].low }
        val pico = window[idx].high
        return if (pico > 0.0) (pico - minEntorno) / pico * 100 else 0.0
    }

    /** Espejo de prominenciaDePico() para un valle (doble/triple suelo). */
    private fun prominenciaDeValle(window: List<Candle>, idx: Int, ladoSemanas: Int = 5): Double {
        val inicio = (idx - ladoSemanas).coerceAtLeast(0)
        val fin = (idx + ladoSemanas).coerceAtMost(window.size - 1)
        val maxEntorno = (inicio..fin).maxOf { window[it].high }
        val valle = window[idx].low
        return if (valle > 0.0) (maxEntorno - valle) / maxEntorno * 100 else 0.0
    }

    /**
     * Tolerancia (1%-3%) para la diferencia entre los dos picos/valles de un doble techo/suelo,
     * en función de la volatilidad histórica del propio activo — pedido expresamente así.
     * Se mide como la desviación típica de los retornos semanales (%) de la ventana de velas ya
     * analizada por detectDoubleTopOrBottom. Umbrales fijos razonables (mismo espíritu que el
     * resto de aproximaciones de este detector, ver AVISO HONESTO más arriba): stdev <= 2% ->
     * tolerancia mínima (1%, activo tranquilo, exige picos casi idénticos), stdev >= 6% ->
     * tolerancia máxima (3%, activo muy volátil, más margen de ruido), interpolación lineal
     * entre medio.
     */
    private fun peakToleranceForVolatility(window: List<Candle>): Double {
        val closes = window.map { it.close }.filter { it > 0.0 }
        if (closes.size < 3) return 2.0 // sin datos suficientes, punto medio del rango pedido
        val returns = (1 until closes.size).map { i -> (closes[i] - closes[i - 1]) / closes[i - 1] * 100 }
        val mean = returns.average()
        val variance = returns.sumOf { (it - mean) * (it - mean) } / returns.size
        val stdev = kotlin.math.sqrt(variance)
        val minStdev = 2.0
        val maxStdev = 6.0
        val ratio = ((stdev - minStdev) / (maxStdev - minStdev)).coerceIn(0.0, 1.0)
        return 1.0 + ratio * 2.0
    }

    fun countBearishMonthsInLast6(candles: List<Candle>, debugSymbol: String? = null): Int? {
        // CORREGIDO DE NUEVO — la agrupación por mes (evitar contar dos veces el mes en curso
        // si Yahoo devuelve varias velas para él) SIGUE haciendo falta, se mantiene. Pero la
        // DEFINICIÓN de "mes en negativo" vuelve a como estaba al principio: cierre del mes
        // POR DEBAJO de la apertura de ESE MISMO mes — es la definición estándar de "vela
        // roja" en cualquier gráfico de velas (incluido el propio Yahoo), no una comparación
        // contra el cierre del mes anterior. El cambio a "mes anterior" fue un error de
        // interpretación mío en un intento previo, revertido tras confirmar con el usuario qué
        // meses concretos marca Yahoo como negativos (abril, junio y agosto), que solo
        // encajan con esta definición, no con la de "mes anterior".
        val byMonth = candles.mapNotNull { candle ->
            try {
                val date = java.time.Instant.parse(candle.datetime).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                Triple(date.year, date.monthValue, candle)
            } catch (e: Exception) {
                null
            }
        }
            .groupBy { it.first to it.second }
            .values
            .map { group -> group.maxByOrNull { java.time.Instant.parse(it.third.datetime) }!!.third } // la más reciente de cada mes
            .sortedBy { it.datetime }

        if (byMonth.size < 6) return null
        val last6 = byMonth.takeLast(6)
        val count = last6.count { it.close < it.open }
        // Diagnóstico — para ver EXACTAMENTE qué 6 velas mensuales (fecha + apertura + cierre)
        // está usando la función, mes a mes, y confirmar o descartar si el recuento coincide
        // con lo esperado. Solo se activa si se pasa debugSymbol.
        if (debugSymbol != null) {
            val detalle = last6.joinToString(" | ") { "${it.datetime.take(10)} apertura=${"%.2f".format(it.open)} cierre=${"%.2f".format(it.close)}" }
            android.util.Log.i(
                "BearishMonthsDiagnostic",
                "$debugSymbol: recuento=$count de 6 — velas usadas tras agrupar por mes (fecha, apertura, cierre): $detalle"
            )
        }
        return count
    }

    /**
     * "28 may 2026" a partir de un datetime ISO-8601 (java.time.Instant.toString(), el formato
     * que usa Candle.datetime) — para mostrar la fecha de un cruce de medias en el aviso en
     * neón, en vez de solo "hay cruce sí/no". Null-safe: si el texto no se puede interpretar,
     * devuelve el propio texto crudo tal cual (mejor eso que reventar o mostrar nada).
     */
    fun formatCandleDateForDisplay(datetime: String): String {
        return try {
            val instant = java.time.Instant.parse(datetime)
            val date = java.time.LocalDate.ofInstant(instant, java.time.ZoneOffset.UTC)
            val formatter = java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale("es", "ES"))
            date.format(formatter)
        } catch (e: Exception) {
            datetime
        }
    }

    data class UptrendSignal(
        /** Variación total del precio en el año, en %. */
        val yearChangePercent: Double,
        /** Bondad del ajuste de la regresión lineal (0..1) — cuanto más alto, más "limpia" es la subida. */
        val trendQuality: Double,
        /** true si el precio actual sigue por encima de su media de 40 semanas. */
        val aboveTrendSma: Boolean
    )

    fun evaluate(
        candles: List<Candle>,
        minWeeks: Int = 40,
        trendSmaWeeks: Int = 40,
        smaSlopeLookbackWeeks: Int = 10,
        minTrendQuality: Double = 0.5,
        maxLowerLowToleragePercent: Double = 5.0
    ): UptrendSignal? {
        if (candles.size < minWeeks) return null

        val window = candles.takeLast(52).ifEmpty { candles }
        val closes = window.map { it.close }

        val smaSeries = TechnicalAnalysis.simpleMovingAverage(window, trendSmaWeeks)
        val currentSma = smaSeries.lastOrNull() ?: return null
        val currentPrice = closes.last()
        val aboveSma = currentPrice > currentSma

        val referenceIndex = (smaSeries.size - 1 - smaSlopeLookbackWeeks).coerceAtLeast(0)
        val referenceSma = smaSeries.getOrNull(referenceIndex) ?: return null
        val smaSlopePositive = currentSma > referenceSma

        if (!aboveSma || !smaSlopePositive) return null

        val (slope, rSquared) = linearRegression(closes)
        if (slope <= 0 || rSquared < minTrendQuality) return null

        val firstHalf = closes.take(closes.size / 2)
        val secondHalf = closes.takeLast(closes.size / 2)
        val higherLows = if (firstHalf.isEmpty() || secondHalf.isEmpty()) {
            true
        } else {
            secondHalf.min() >= firstHalf.min() * (1 - maxLowerLowToleragePercent / 100)
        }
        if (!higherLows) return null

        // NUEVO — pedido expresamente (mejora #2): la regresión de arriba mira TODO el año, así
        // que un stock podía seguir etiquetado "tendencia alcista clara" varias semanas después
        // de haber empezado a girar de verdad a la baja. Se exige, además, que las últimas 5
        // semanas no muestren una caída clara (más de un 6% desde hace 5 semanas) — si la
        // muestran, ya no cuenta como tendencia alcista "actual", aunque el año entero cuadre.
        if (closes.size > 5) {
            val hace5Semanas = closes[closes.size - 1 - 5]
            val cambioUltimas5Semanas = (closes.last() - hace5Semanas) / hace5Semanas * 100
            if (cambioUltimas5Semanas < -6.0) return null
        }

        val yearChangePercent = (closes.last() - closes.first()) / closes.first() * 100

        return UptrendSignal(
            yearChangePercent = yearChangePercent,
            trendQuality = rSquared,
            aboveTrendSma = aboveSma
        )
    }

    /**
     * NUEVO — pedido expresamente (mejora B): el espejo EXACTO de evaluate() de arriba, pero
     * mirando la CAÍDA en vez de la subida — antes solo existía un bono para "tendencia alcista
     * clara a largo plazo" (52 semanas), sin ninguna penalización si el largo plazo era
     * claramente bajista (asimetría real). Mismas 5 condiciones, todas invertidas:
     *  1. Precio por DEBAJO de su SMA(40)
     *  2. SMA(40) con pendiente NEGATIVA
     *  3. Regresión lineal del año: pendiente NEGATIVA y R² ≥ 0,5 (caída "limpia", no errática)
     *  4. Máximos DESCENDENTES (mismo margen de tolerancia que "mínimos ascendentes")
     *  5. Las últimas 5 semanas no pueden mostrar un rebote claro (>6%) — un stock que ya está
     *     rebotando con fuerza no es una "tendencia bajista actual", aunque el año entero cuadre.
     * Devuelve simplemente true/false (a diferencia de evaluate(), que devuelve un UptrendSignal
     * completo) — para esta penalización solo hace falta saber SI hay tendencia bajista clara,
     * no su magnitud exacta.
     */
    fun evaluateDowntrend(
        candles: List<Candle>,
        minWeeks: Int = 40,
        trendSmaWeeks: Int = 40,
        smaSlopeLookbackWeeks: Int = 10,
        minTrendQuality: Double = 0.5,
        maxHigherHighToleragePercent: Double = 5.0
    ): Boolean {
        if (candles.size < minWeeks) return false

        val window = candles.takeLast(52).ifEmpty { candles }
        val closes = window.map { it.close }

        val smaSeries = TechnicalAnalysis.simpleMovingAverage(window, trendSmaWeeks)
        val currentSma = smaSeries.lastOrNull() ?: return false
        val currentPrice = closes.last()
        val belowSma = currentPrice < currentSma

        val referenceIndex = (smaSeries.size - 1 - smaSlopeLookbackWeeks).coerceAtLeast(0)
        val referenceSma = smaSeries.getOrNull(referenceIndex) ?: return false
        val smaSlopeNegative = currentSma < referenceSma

        if (!belowSma || !smaSlopeNegative) return false

        val (slope, rSquared) = linearRegression(closes)
        if (slope >= 0 || rSquared < minTrendQuality) return false

        val firstHalf = closes.take(closes.size / 2)
        val secondHalf = closes.takeLast(closes.size / 2)
        val lowerHighs = if (firstHalf.isEmpty() || secondHalf.isEmpty()) {
            true
        } else {
            secondHalf.max() <= firstHalf.max() * (1 + maxHigherHighToleragePercent / 100)
        }
        if (!lowerHighs) return false

        if (closes.size > 5) {
            val hace5Semanas = closes[closes.size - 1 - 5]
            val cambioUltimas5Semanas = (closes.last() - hace5Semanas) / hace5Semanas * 100
            if (cambioUltimas5Semanas > 6.0) return false
        }

        return true
    }

    /** Regresión lineal simple sobre una serie; devuelve (pendiente, R²). */
    private fun linearRegression(values: List<Double>): Pair<Double, Double> {
        val n = values.size
        if (n < 2) return 0.0 to 0.0
        val xs = (0 until n).map { it.toDouble() }
        val xMean = xs.average()
        val yMean = values.average()
        val denominator = xs.sumOf { (it - xMean) * (it - xMean) }
        if (denominator == 0.0) return 0.0 to 0.0
        val slope = xs.indices.sumOf { (xs[it] - xMean) * (values[it] - yMean) } / denominator
        val intercept = yMean - slope * xMean
        val predicted = xs.map { slope * it + intercept }
        val ssRes = values.indices.sumOf { (values[it] - predicted[it]).let { d -> d * d } }
        val ssTot = values.sumOf { (it - yMean) * (it - yMean) }
        val r2 = if (ssTot == 0.0) 0.0 else 1 - (ssRes / ssTot)
        return slope to r2.coerceIn(0.0, 1.0)
    }

    /**
     * Versión ligera de evaluate(), para confirmar SOLO la tendencia de los últimos ~3 meses
     * (13 semanas) — sin exigir la media de 40 semanas de evaluate() (no cabría calcularla con
     * solo 13 velas). Se usa en Top10Calculator para que la categoría "Tendencia" solo sume
     * cuando, además del RSI en zona normal, la tendencia reciente sea claramente alcista.
     */
    fun isClearlyBullishShortTerm(candles: List<Candle>, weeks: Int = 13, minTrendQuality: Double = 0.5): Boolean {
        if (candles.size < weeks) return false
        val closes = candles.takeLast(weeks).map { it.close }
        val (slope, rSquared) = linearRegression(closes)
        return slope > 0 && rSquared >= minTrendQuality
    }

    /**
     * Detecta si una caída se está ACELERANDO claramente: compara el retorno de las últimas
     * [recentWeeks] semanas contra el retorno de las [priorWeeks] semanas anteriores a esas —
     * si las dos son negativas (cayendo en ambos periodos) y la caída reciente es al menos 1,5
     * veces más pronunciada que la anterior, se considera una aceleración clara.
     *
     * Heurística propia — no hay un estándar único para "aceleración", este es el criterio
     * elegido: recentReturn ≤ -3% (para no disparar con ruido pequeño) Y recentReturn ≤
     * priorReturn × 1.5 (recuerda: ambos son negativos, así que "más negativo" = más caída).
     *
     * Se usa como penalización APARTE de la fórmula de 7 categorías (no toca sus pesos) — resta
     * 10 puntos al final si se cumple, ver Top10Calculator.score(declineAccelerating=...).
     */
    fun isDeclineAcceleratingClearly(candles: List<Candle>, recentWeeks: Int = 4, priorWeeks: Int = 4): Boolean {
        if (candles.size < recentWeeks + priorWeeks + 1) return false
        val closes = candles.map { it.close }
        val n = closes.size
        val recentReturn = (closes[n - 1] - closes[n - 1 - recentWeeks]) / closes[n - 1 - recentWeeks]
        val priorReturn = (closes[n - 1 - recentWeeks] - closes[n - 1 - recentWeeks - priorWeeks]) / closes[n - 1 - recentWeeks - priorWeeks]
        return recentReturn <= -0.03 && priorReturn < 0.0 && recentReturn <= priorReturn * 1.5
    }

    /**
     * Cruce DESCENDENTE de la SMA20 por debajo de la SMA50 ("cruz de la muerte") — CORREGIDO:
     * antes comprobaba lo contrario (SMA50 cruzando por debajo de la SMA20), que no es la
     * definición correcta. La cruz de la muerte es la media CORTA (20) cruzando por debajo de
     * la media LARGA (50) — el espejo exacto de la cruz dorada de abajo (SMA20 por ENCIMA de la
     * SMA50). Últimas [lookbackPeriods] VELAS (no necesariamente semanas — depende de qué velas
     * se le pasen: semanales en Top10/Futuras Compras, diarias en la ficha de un stock, ver
     * BuyOpportunityAnalyzer.dailyCandlesForCross): cualquiera de esas transiciones vale, no
     * hace falta que sea justo en la última vela cargada.
     *
     * Devuelve la FECHA (datetime de la vela) en la que se produjo el cruce — null si no hubo
     * ninguno en la ventana. Antes devolvía solo Boolean; cambiado a petición expresa para poder
     * mostrar la fecha exacta en el aviso en neón, no solo "hay cruce sí/no".
     */
    fun findSma20CrossedBelowSma50Date(candles: List<Candle>, shortLength: Int = 20, longLength: Int = 50, lookbackPeriods: Int = 2): String? {
        val sma20 = TechnicalAnalysis.simpleMovingAverage(candles, shortLength)
        val sma50 = TechnicalAnalysis.simpleMovingAverage(candles, longLength)
        val n = candles.size
        if (n < 2 || sma20.size < 2 || sma50.size < 2) return null
        for (i in maxOf(1, n - lookbackPeriods) until n) {
            val prevSma20 = sma20.getOrNull(i - 1) ?: continue
            val currSma20 = sma20.getOrNull(i) ?: continue
            val prevSma50 = sma50.getOrNull(i - 1) ?: continue
            val currSma50 = sma50.getOrNull(i) ?: continue
            if (prevSma20 >= prevSma50 && currSma20 < currSma50) return candles[i].datetime
        }
        return null
    }

    /**
     * Cruce ASCENDENTE de la SMA20 por encima de la SMA50 ("cruz dorada") — lo contrario de
     * findSma20CrossedBelowSma50Date de arriba. Mismo criterio de ventana: las últimas
     * [lookbackPeriods] velas, cualquiera de esas transiciones vale. Devuelve la FECHA del
     * cruce, null si no hubo ninguno — ver comentario completo arriba.
     */
    fun findSma20CrossedAboveSma50Date(candles: List<Candle>, shortLength: Int = 20, longLength: Int = 50, lookbackPeriods: Int = 2): String? {
        val sma20 = TechnicalAnalysis.simpleMovingAverage(candles, shortLength)
        val sma50 = TechnicalAnalysis.simpleMovingAverage(candles, longLength)
        val n = candles.size
        if (n < 2 || sma20.size < 2 || sma50.size < 2) return null
        for (i in maxOf(1, n - lookbackPeriods) until n) {
            val prevSma20 = sma20.getOrNull(i - 1) ?: continue
            val currSma20 = sma20.getOrNull(i) ?: continue
            val prevSma50 = sma50.getOrNull(i - 1) ?: continue
            val currSma50 = sma50.getOrNull(i) ?: continue
            if (prevSma20 <= prevSma50 && currSma20 > currSma50) return candles[i].datetime
        }
        return null
    }

    /**
     * Detecta divergencia alcista MOMENTUM/PRECIO: el momentum (RSI 14) sube mientras el
     * precio baja, en la ventana de las últimas [divergenceWindow] semanas — señal clásica de
     * que la caída está perdiendo fuerza, aunque el precio todavía no lo refleje.
     *
     * Heurística propia — no hay un único estándar para "divergencia": se mide con una
     * regresión lineal sobre las últimas [divergenceWindow] semanas, tanto del precio de
     * cierre como del RSI — divergencia = pendiente del precio negativa Y pendiente del RSI
     * positiva, ambas con un ajuste (R²) razonable (≥0.3) para no disparar con ruido.
     *
     * El MISMO patrón se interpreta distinto según el contexto de tendencia de fondo:
     *  - Si hay una tendencia alcista de 52 semanas ya establecida (evaluate() != null): es un
     *    retroceso DENTRO de esa subida -> UPTREND_PULLBACK_ENDING (fiabilidad muy alta).
     *  - Si no, y el cambio de precio de las últimas [longTermWindow] semanas es una caída
     *    fuerte (≤-20%): es el final de una tendencia bajista larga -> posible suelo ->
     *    LONG_DOWNTREND_POSSIBLE_BOTTOM (fiabilidad alta).
     *  - Si no, sigue en mitad de una bajada sin agotamiento claro -> posible trampa de toros
     *    -> DOWNTREND_BULL_TRAP_RISK (fiabilidad media/baja).
     */
    fun detectMomentumPriceDivergence(candles: List<Candle>, divergenceWindow: Int = 8, longTermWindow: Int = 26): MomentumPriceDivergence {
        if (candles.size < longTermWindow + 20) return MomentumPriceDivergence.NONE // margen para RSI(14) + ventana larga

        val rsiSeries = TechnicalAnalysis.calculateRsi(candles, period = 14)
        val recentRsiRaw = rsiSeries.takeLast(divergenceWindow)
        if (recentRsiRaw.any { it == null } || recentRsiRaw.size < divergenceWindow) return MomentumPriceDivergence.NONE
        val recentRsi = recentRsiRaw.map { it!! }
        val recentCloses = candles.takeLast(divergenceWindow).map { it.close }

        val (priceSlope, priceR2) = linearRegression(recentCloses)
        val (rsiSlope, rsiR2) = linearRegression(recentRsi)
        val hasDivergence = priceSlope < 0 && rsiSlope > 0 && priceR2 >= 0.3 && rsiR2 >= 0.3
        if (!hasDivergence) return MomentumPriceDivergence.NONE

        val establishedUptrend = evaluate(candles) != null
        if (establishedUptrend) return MomentumPriceDivergence.UPTREND_PULLBACK_ENDING

        val longTermCloses = candles.takeLast(minOf(longTermWindow, candles.size)).map { it.close }
        val longTermChangePercent = (longTermCloses.last() - longTermCloses.first()) / longTermCloses.first() * 100
        return if (longTermChangePercent <= -20.0) {
            MomentumPriceDivergence.LONG_DOWNTREND_POSSIBLE_BOTTOM
        } else {
            MomentumPriceDivergence.DOWNTREND_BULL_TRAP_RISK
        }
    }

    // =====================================================================================
    // NUEVOS PATRONES — pedidos expresamente para penalizaciones/bonificaciones adicionales:
    // Hombro-Cabeza-Hombro, Doble Techo (variante con estado "en formación" para "Momento
    // idóneo para la venta"), Divergencia Bajista Precio/RSI y Cruce de la Muerte SMA50/SMA200.
    // =====================================================================================

    /** Resultado de detectHeadAndShoulders() — ver esa función para la definición completa. */
    enum class HeadAndShouldersState {
        /** Sin patrón detectado. */
        NONE,
        /** Los 3 picos (hombro-cabeza-hombro) y la línea clavicular están identificados, pero
         *  el precio TODAVÍA no ha cerrado por debajo de la línea clavicular — aviso AMARILLO,
         *  mitad de puntos. */
        FORMING,
        /** El precio ya ha cerrado por debajo de la línea clavicular tras el hombro derecho —
         *  aviso ROJO, puntos completos. */
        CONFIRMED
    }

    data class HeadAndShouldersResult(
        val state: HeadAndShouldersState,
        val leftShoulderDate: String? = null,
        val headDate: String? = null,
        val rightShoulderDate: String? = null,
        val neckline: Double? = null
    )

    /**
     * Detecta un patrón de "Hombro-Cabeza-Hombro" (bajista) sobre velas SEMANALES, con una
     * distancia entre el hombro izquierdo y el hombro derecho de entre 6 MESES (26 semanas) y 5
     * AÑOS (260 semanas) — pedido expresamente así. Reutiliza la MISMA tolerancia dinámica
     * (1%-3% según volatilidad propia del activo) que ya usa detectDoubleTopOrBottom, y el mismo
     * detector de picos/valles (TechnicalAnalysis.findSwingPoints) que el resto de la app.
     *
     * Dos estados posibles (ver HeadAndShouldersState):
     *  - FORMING: los 3 picos (hombro1 < cabeza > hombro2, con hombros de altura similar) y los
     *    dos valles de la línea clavicular (también de altura similar entre sí) ya están
     *    identificados, pero el precio no ha roto la línea clavicular todavía.
     *  - CONFIRMED: además de lo anterior, alguna vela CERRÓ por debajo de la línea clavicular
     *    después del hombro derecho.
     *
     * AVISO HONESTO: aproximación con umbrales fijos razonables (mismo espíritu que
     * detectFlagPattern/detectDoubleTopOrBottom), no un reconocimiento visual riguroso de
     * patrones chartistas.
     *
     * "MEMORIA DILUIDA" (pedido expresamente): si el patrón sigue SIN CONFIRMAR y han pasado
     * 78 semanas (1,5 años) o más desde el hombro derecho con el precio lateralizando en un
     * rango estrecho (sin dirección clara), se descarta — pasado ese tiempo sin que nada ocurra,
     * la relevancia del patrón para el mercado se da por diluida.
     *
     * @param candles velas semanales — cuanto mayor sea el histórico pasado (hasta 5 años),
     *   más atrás puede buscar el patrón; con solo 1 año de velas (Top10/Futuras Compras), el
     *   límite de 5 años nunca se alcanza y el patrón se busca igualmente dentro de esa ventana
     *   más corta, sin coste de red adicional (decidido así expresamente para no multiplicar
     *   las peticiones de red en los escaneos de mercado).
     */
    fun detectHeadAndShoulders(candles: List<Candle>, lookback: Int = 3, debugSymbol: String? = null): HeadAndShouldersResult {
        val minWeeksBetweenShoulders = 26   // 6 meses
        val maxWeeksBetweenShoulders = 260  // 5 años
        val extrapolacionMaxSemanas = 52  // tope de 1 año prolongando la línea clavicular inclinada
        val prominenciaMinimaCabeza = 3.0 // % mínimo que la cabeza debe superar al hombro más alto
        val umbralMemoriaDiluidaSemanas = 78 // 1,5 años — punto medio del "1 a 2 años" pedido expresamente
        val rangoLateralMaximoPercent = 15.0 // por debajo de esto se considera "lateralizando, sin dirección clara"
        if (candles.size < lookback * 2 + 5) return HeadAndShouldersResult(HeadAndShouldersState.NONE)

        val swings = TechnicalAnalysis.findSwingPoints(candles, lookback)
        val peaks = swings.filter { it.type == SwingType.HIGH }
        val valleys = swings.filter { it.type == SwingType.LOW }
        if (debugSymbol != null) {
            android.util.Log.i(
                "HchDiagnostic",
                "$debugSymbol: ${candles.size} velas, ${peaks.size} picos detectados (lookback=$lookback) — " +
                    peaks.joinToString(" | ") { "${it.datetime.take(10)}=${"%.2f".format(it.price)}" }
            )
        }
        if (peaks.size < 3) return HeadAndShouldersResult(HeadAndShouldersState.NONE)

        val tolerance = peakToleranceForVolatility(candles)
        if (debugSymbol != null) {
            android.util.Log.i("HchDiagnostic", "$debugSymbol: tolerancia=${"%.2f".format(tolerance)}%")
        }

        // CORREGIDO — la versión anterior exigía que los 3 picos fueran CONSECUTIVOS en la
        // lista de swing points (peaks[i], peaks[i+1], peaks[i+2]), sin ningún otro pico entre
        // medias. En la práctica, con velas SEMANALES sobre una ventana de 6 meses a 5 años,
        // casi siempre hay picos menores (ruido) entre el hombro izquierdo, la cabeza y el
        // hombro derecho reales — bastaba uno solo para que el patrón, aunque fuera clarísimo a
        // simple vista, se descartara por completo.
        //
        // SEGUNDO FALLO CORREGIDO — el primer intento de arreglo comprobaba la distancia de
        // CADA hombro a la cabeza por separado contra el rango 6 meses-5 años (obligando a que
        // el patrón entero durase mínimo 1 año), en vez de comprobar la distancia TOTAL entre
        // los dos hombros (lo pedido y confirmado expresamente: "la distancia entre el hombro
        // izquierdo y el hombro derecho debe estar dentro de tu rango de 6 meses - 5 años") —
        // caso real que se perdía por esto: el HCH de Oracle, más compacto que 1 año entero.
        // Ahora se prueban parejas de hombros (izquierdo, derecho) EMPEZANDO por los más
        // cercanos a la cabeza (los candidatos más naturales), comprobando la distancia TOTAL
        // entre ambos — sin exigir que sean el pico inmediatamente anterior/siguiente en la
        // lista, así que los picos menores intermedios no rompen la detección.
        // Se recorren las cabezas candidatas EMPEZANDO por la más reciente, para quedarse con el
        // patrón más actual si hay varios válidos en el histórico.
        for (headIdxInPeaks in peaks.indices.reversed()) {
            val cabeza = peaks[headIdxInPeaks]

            // Ordenados por cercanía a la cabeza primero — los hombros reales suelen ser el
            // pico prominente más próximo a cada lado, no uno lejano en el tiempo.
            val izquierdaCandidatas = peaks.filter { it.index < cabeza.index && it.price < cabeza.price }
                .sortedByDescending { it.index }
            val derechaCandidatas = peaks.filter { it.index > cabeza.index && it.price < cabeza.price }
                .sortedBy { it.index }
            if (izquierdaCandidatas.isEmpty() || derechaCandidatas.isEmpty()) continue

            // NUEVO — mismo motivo que en detectDoubleTopOrBottom (pedido expresamente,
            // definición citada por el usuario: los dos hombros deben ser prácticamente
            // simétricos). Antes se devolvía la PRIMERA pareja de hombros válida (la más cercana
            // a la cabeza), sin comprobar si había otra más simétrica un poco más lejos. Ahora se
            // comprueban TODAS las parejas válidas para esta cabeza y se elige la de MENOR
            // diferencia porcentual entre los dos hombros.
            var mejorH1: SwingPoint? = null
            var mejorH2: SwingPoint? = null
            var mejorNeckline = 0.0
            var mejorConfirmado = false
            var mejorDiff = Double.MAX_VALUE

            for (h1 in izquierdaCandidatas) {
                for (h2 in derechaCandidatas) {
                    val distanciaTotal = h2.index - h1.index
                    if (distanciaTotal < minWeeksBetweenShoulders || distanciaTotal > maxWeeksBetweenShoulders) continue

                    // CORREGIDO — fallo real encontrado con Oracle: la cabeza solo se comparaba
                    // contra los DOS hombros elegidos, no contra el resto de picos del tramo. Si
                    // había picos más altos entre medias (que no se usaban como hombro por no
                    // encajar en tolerancia), el algoritmo se conformaba con una "cabeza" que en
                    // realidad no era el punto más alto del tramo — un falso positivo que además
                    // se encontraba ANTES que el patrón real (se recorre de más reciente a más
                    // antiguo y se para en el primero que encaja), así que la cabeza de verdad
                    // nunca llegaba a evaluarse.
                    // SEGUNDO FALLO CORREGIDO — pedido expresamente ("los hombros deben estar por
                    // debajo de la cabeza, veo que esto no siempre se está cumpliendo"): la
                    // comprobación de arriba solo miraba otros PICOS ya detectados
                    // (peaks.filter{...}), no las velas reales del tramo. findSwingPoints exige
                    // que un pico sea el máximo de su propia ventana local (lookback=3 a cada
                    // lado) para registrarse — una vela con un máximo (high) más alto que la
                    // cabeza, pero que quedaba cerca del borde de los datos o de otro pico
                    // todavía más alto cercano, podía no llegar nunca a registrarse como "pico" y
                    // se colaba sin comprobar. Ahora se exige que NINGUNA vela real del tramo
                    // (no solo los picos ya detectados) supere el máximo (high) de la cabeza.
                    val maxEnTramo = candles.subList(h1.index, h2.index + 1).maxOf { it.high }
                    if (cabeza.price < maxEnTramo) continue

                    // NUEVO — fallo real encontrado con WMB: la cabeza solo tenía que ser MÁS
                    // ALTA que los hombros, sin importar por cuánto. Con una cabeza apenas un
                    // 4.4% por encima de los hombros, el patrón detectado no era un HCH real,
                    // era una simple escalera de máximos ascendentes que coincidía por casualidad
                    // con dos niveles parecidos a los lados. Se exige ahora que la cabeza destaque
                    // un mínimo razonable sobre el más alto de los dos hombros.
                    val alturaHombros = kotlin.math.max(h1.price, h2.price)
                    val prominenciaCabeza = (cabeza.price - alturaHombros) / alturaHombros * 100
                    if (prominenciaCabeza < prominenciaMinimaCabeza) {
                        if (debugSymbol != null) {
                            android.util.Log.i(
                                "HchDiagnostic",
                                "$debugSymbol: descartado por poca prominencia de cabeza — h1=${h1.datetime.take(10)}(${h1.price}) " +
                                    "cabeza=${cabeza.datetime.take(10)}(${cabeza.price}) h2=${h2.datetime.take(10)}(${h2.price}) " +
                                    "prominencia=${"%.2f".format(prominenciaCabeza)}% < mínimo=${prominenciaMinimaCabeza}%"
                            )
                        }
                        continue
                    }

                    // NUEVO — fallo más grave encontrado con WMB: nunca se comprobaba qué pasaba
                    // con el precio DESPUÉS del hombro derecho. WMB siguió subiendo tras su
                    // supuesta "cabeza" (61.06) hasta máximos históricos muy por encima (78.92) —
                    // si el precio ha cerrado por encima de la propia cabeza en cualquier momento
                    // posterior al hombro derecho, la tesis bajista del HCH queda invalidada por
                    // completo (el "techo" no era tal techo), así que el patrón se descarta
                    // directamente, sea cual sea el resultado de la línea clavicular.
                    val invalidadoPorNuevoMaximo = (h2.index + 1 until candles.size).any { k -> candles[k].close > cabeza.price }
                    if (invalidadoPorNuevoMaximo) {
                        if (debugSymbol != null) {
                            android.util.Log.i(
                                "HchDiagnostic",
                                "$debugSymbol: descartado por invalidación (nuevo máximo tras la cabeza) — h1=${h1.datetime.take(10)} " +
                                    "cabeza=${cabeza.datetime.take(10)}(${cabeza.price}) h2=${h2.datetime.take(10)}"
                            )
                        }
                        continue
                    }

                    // Hombros de altura similar — tolerancia dinámica (1%-3% según volatilidad,
                    // igual que el doble techo/suelo), ampliada ×1.2 aquí: caso real de Oracle,
                    // con hombros a un 3.31% de diferencia y tolerancia base de 2.97%, un patrón
                    // clarísimo a simple vista fallaba por un margen mínimo. Un HCH abarca mucho
                    // más tiempo que un doble techo, así que es razonable que sus dos hombros
                    // puedan diferir algo más entre sí.
                    val diffHombros = kotlin.math.abs(h1.price - h2.price) / h1.price * 100
                    if (diffHombros > tolerance * 1.2) {
                        if (debugSymbol != null && diffHombros < tolerance * 2.0) {
                            android.util.Log.i(
                                "HchDiagnostic",
                                "$debugSymbol: descartado por hombros — h1=${h1.datetime.take(10)}(${h1.price}) " +
                                    "cabeza=${cabeza.datetime.take(10)}(${cabeza.price}) h2=${h2.datetime.take(10)}(${h2.price}) " +
                                    "diffHombros=${"%.2f".format(diffHombros)}% > tolerancia×1.2=${"%.2f".format(tolerance * 1.2)}%"
                            )
                        }
                        continue
                    }

                    // Valles de la línea clavicular — CORREGIDO: la versión anterior exigía que
                    // los dos valles tuvieran una altura PARECIDA entre sí (mismo criterio que el
                    // doble techo/suelo), pero eso no es cómo es un HCH real. Caso real de Oracle
                    // que esto descartaba: valle izquierdo en 216 y valle derecho en 134 (38% de
                    // diferencia) — perfectamente normal en un patrón que abarca muchos meses y
                    // puede tener una caída del mercado de fondo entre medias. La línea clavicular
                    // de un HCH casi nunca es horizontal, suele estar INCLINADA — así que ahora se
                    // interpola una línea recta entre los dos valles (por fecha/índice, no un
                    // promedio plano) y la ruptura se compara contra el valor de ESA línea en cada
                    // punto, no contra un nivel fijo.
                    val valleIzquierdo = valleys.filter { it.index in h1.index..cabeza.index }.minByOrNull { it.price }
                    val valleDerecho = valleys.filter { it.index in cabeza.index..h2.index }.minByOrNull { it.price }
                    if (valleIzquierdo == null || valleDerecho == null || valleDerecho.index == valleIzquierdo.index) {
                        if (debugSymbol != null) {
                            android.util.Log.i(
                                "HchDiagnostic",
                                "$debugSymbol: descartado por falta de valle — h1=${h1.datetime.take(10)} " +
                                    "cabeza=${cabeza.datetime.take(10)} h2=${h2.datetime.take(10)}"
                            )
                        }
                        continue
                    }

                    val pendienteNeckline = (valleDerecho.price - valleIzquierdo.price).toDouble() /
                        (valleDerecho.index - valleIzquierdo.index)
                    // LÍMITE A LA EXTRAPOLACIÓN — pedido expresamente: sin esto, una pendiente
                    // pronunciada entre los dos valles se prolonga indefinidamente hacia el
                    // futuro, alcanzando niveles cada vez menos realistas (incluso negativos a
                    // muy largo plazo) y pudiendo hacer que el patrón nunca llegue a
                    // "confirmarse" por mucho que el precio siga cayendo con normalidad. A partir
                    // de extrapolacionMaxSemanas semanas después del valle derecho, la línea se
                    // aplana en el valor que tenía en ese punto, en vez de seguir bajando/subiendo.
                    fun necklineEn(index: Int): Double {
                        val indexLimitado = index.coerceAtMost(valleDerecho.index + extrapolacionMaxSemanas)
                        return valleIzquierdo.price + pendienteNeckline * (indexLimitado - valleIzquierdo.index)
                    }

                    // Valor de la neckline en el hombro derecho — el que se muestra/guarda como
                    // referencia (la línea sigue existiendo e inclinándose más allá de ese punto,
                    // pero este es el valor más representativo para mostrar en la interfaz).
                    val neckline = necklineEn(h2.index)

                    // Confirmación: alguna vela CIERRA por debajo de la línea clavicular (en SU
                    // propio punto de la línea inclinada, no un valor fijo) después del hombro
                    // derecho — mismo espíritu de "ruptura obligatoria" que detectDoubleTopOrBottom,
                    // adaptado a una línea con pendiente.
                    val confirmado = (h2.index + 1 until candles.size).any { k -> candles[k].close < necklineEn(k) }

                    // NUEVO — pedido expresamente: "si han pasado más de 1 a 2 años y el precio
                    // ha estado lateralizando (en rango) sin dirección clara, la memoria del
                    // mercado sobre ese patrón se diluye". Solo se aplica cuando el patrón sigue
                    // SIN CONFIRMAR (si ya rompió la clavicular, la ruptura es un hecho reciente
                    // y consumado, no algo que se "diluya" con el tiempo) — si desde el hombro
                    // derecho han pasado ≥78 semanas (1,5 años) y en todo ese tiempo el precio se
                    // ha movido dentro de un rango estrecho (<15%, sin subir a nuevos máximos ni
                    // romper la clavicular), se considera que el patrón ya no es una señal
                    // relevante y se descarta — se sigue probando con otras combinaciones de
                    // hombros/cabeza en vez de devolver este resultado "caducado".
                    if (!confirmado) {
                        val semanasDesdeHombroDerecho = candles.size - 1 - h2.index
                        if (semanasDesdeHombroDerecho >= umbralMemoriaDiluidaSemanas) {
                            val ventanaPosterior = candles.subList(h2.index, candles.size)
                            val maxPosterior = ventanaPosterior.maxOf { it.high }
                            val minPosterior = ventanaPosterior.minOf { it.low }
                            val precioMedioPosterior = (maxPosterior + minPosterior) / 2.0
                            val rangoPercent = if (precioMedioPosterior > 0) (maxPosterior - minPosterior) / precioMedioPosterior * 100 else 100.0
                            if (rangoPercent < rangoLateralMaximoPercent) {
                                if (debugSymbol != null) {
                                    android.util.Log.i(
                                        "HchDiagnostic",
                                        "$debugSymbol: descartado por memoria diluida — $semanasDesdeHombroDerecho semanas sin confirmar desde el hombro derecho, " +
                                            "precio lateralizando (rango ${"%.1f".format(rangoPercent)}% < $rangoLateralMaximoPercent%)"
                                    )
                                }
                                continue
                            }
                        }
                    } else {
                        // NUEVO — pedido expresamente: el HCH CONFIRMADO no tenía ningún límite
                        // de caducidad (a diferencia del doble/triple techo-suelo, que sí caducan
                        // 26 semanas después de la ruptura) — así que una ruptura de hace años
                        // seguía penalizando para siempre. Se usa el mismo umbral de 78 semanas
                        // (1,5 años) que ya usa la "memoria diluida" de arriba, y no las 26
                        // semanas del doble/triple, porque el HCH es un patrón bastante más
                        // grande y estructural (ventana de 6 meses a 5 años entre hombros) —
                        // aplicarle un plazo pensado para un patrón mucho más corto no tendría
                        // sentido.
                        val semanasDesdeConfirmacion = candles.size - 1 - (h2.index + 1 until candles.size).first { k -> candles[k].close < necklineEn(k) }
                        if (semanasDesdeConfirmacion > umbralMemoriaDiluidaSemanas) {
                            if (debugSymbol != null) {
                                android.util.Log.i(
                                    "HchDiagnostic",
                                    "$debugSymbol: descartado por caducidad — confirmado hace $semanasDesdeConfirmacion semanas (> $umbralMemoriaDiluidaSemanas)"
                                )
                            }
                            continue
                        }
                    }

                    if (diffHombros < mejorDiff) {
                        mejorH1 = h1
                        mejorH2 = h2
                        mejorNeckline = neckline
                        mejorConfirmado = confirmado
                        mejorDiff = diffHombros
                    }
                }
            }

            if (mejorH1 != null && mejorH2 != null) {
                if (debugSymbol != null) {
                    android.util.Log.i(
                        "HchDiagnostic",
                        "$debugSymbol: PATRÓN ENCONTRADO — h1=${mejorH1.datetime.take(10)}(${mejorH1.price}) " +
                            "cabeza=${cabeza.datetime.take(10)}(${cabeza.price}) h2=${mejorH2.datetime.take(10)}(${mejorH2.price}) " +
                            "neckline=${"%.2f".format(mejorNeckline)} confirmado=$mejorConfirmado"
                    )
                }

                return HeadAndShouldersResult(
                    state = if (mejorConfirmado) HeadAndShouldersState.CONFIRMED else HeadAndShouldersState.FORMING,
                    leftShoulderDate = mejorH1.datetime,
                    headDate = cabeza.datetime,
                    rightShoulderDate = mejorH2.datetime,
                    neckline = mejorNeckline
                )
            }
        }
        if (debugSymbol != null) {
            android.util.Log.i("HchDiagnostic", "$debugSymbol: ningún patrón encontrado tras revisar todas las combinaciones")
        }
        return HeadAndShouldersResult(HeadAndShouldersState.NONE)
    }

    /** Resultado de detectDoubleTopForSellTiming() — ver esa función para la definición completa. */
    data class DoubleTopSellResult(
        val state: HeadAndShouldersState, // reutiliza NONE/FORMING/CONFIRMED, mismo significado
        val firstDate: String? = null,
        val secondDate: String? = null,
        val neckline: Double? = null
    )

    /**
     * Variante de detectDoubleTopOrBottom() (solo el lado "techo", no "suelo") que además
     * distingue el estado "en formación" — pedida expresamente para el bono de "Momento idóneo
     * para la venta" (distinto del uso ya existente en Top10/Futuras Compras, que solo penaliza
     * cuando el patrón está CONFIRMADO). Misma ventana (6 meses / 32 velas semanales), misma
     * tolerancia dinámica y mismo criterio de "cerca del máximo del año" que la función original
     * — se mantiene detectDoubleTopOrBottom() intacta (la usan Top10/Futuras Compras) y se añade
     * esta como una función independiente para no cambiar ningún comportamiento ya existente.
     */
    fun detectDoubleTopForSellTiming(candles: List<Candle>): DoubleTopSellResult {
        if (candles.size < 10) return DoubleTopSellResult(HeadAndShouldersState.NONE)
        val yearHigh = candles.maxOf { it.high }
        if (yearHigh <= 0.0) return DoubleTopSellResult(HeadAndShouldersState.NONE)

        val window = candles // AMPLIADO de takeLast(32) a todo el año — caso real BG, un pico más alto entre semana 33 y 52 quedaba fuera de la búsqueda
        val peakTolerance = peakToleranceForVolatility(window)

        val localMaxima = mutableListOf<Int>()
        val localMinima = mutableListOf<Int>()
        for (i in 2 until window.size - 2) {
            if ((i - 2..i + 2).all { j -> j == i || window[j].high <= window[i].high }) localMaxima.add(i)
            if ((i - 2..i + 2).all { j -> j == i || window[j].low >= window[i].low }) localMinima.add(i)
        }

        for (i in localMaxima.indices.reversed()) {
            // Mismo motivo que en detectDoubleTopOrBottom (casos reales WDAY/CMCSA/BG) — se
            // comprueban todos los candidatos válidos para esta fecha reciente y se elige el
            // par más simétrico (menor diferencia entre los dos picos) entre ellos.
            var mejorJ = -1
            var mejorDiff = Double.MAX_VALUE
            for (j in (i - 1) downTo 0) {
                val idx1 = localMaxima[j]
                val idx2 = localMaxima[i]
                if (idx2 - idx1 > 26) continue // FIJADO a petición expresa: la ventana de formación completa (primer a segundo pico/valle) es de 6 meses (26 semanas), con granularidad semanal
                val peak1 = window[idx1].high
                val peak2 = window[idx2].high
                if ((yearHigh - peak1) / yearHigh * 100 > 3.0) continue
                if ((yearHigh - peak2) / yearHigh * 100 > 3.0) continue
                val avgPeak = (peak1 + peak2) / 2
                if (avgPeak <= 0.0) continue
                val levelDiffPercent = kotlin.math.abs(peak1 - peak2) / avgPeak * 100
                if (levelDiffPercent > peakTolerance) continue
                // Mismo motivo que en detectDoubleTopOrBottom (caso real CMCSA) — consistencia.
                if (prominenciaDePico(window, idx1) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                if (prominenciaDePico(window, idx2) < UMBRAL_PROMINENCIA_PICO_VALLE) continue
                // Mismo motivo que en detectDoubleTopOrBottom (caso real NVIDIA) — consistencia.
                if (localMinima.none { it in (idx1 + 1) until idx2 }) continue
                val neckline = (idx1..idx2).minOf { window[it].low }
                val troughDropPercent = (avgPeak - neckline) / avgPeak * 100
                if (troughDropPercent < 10.0) continue // AUMENTADO de 5% a 10%, igual que en detectDoubleTopOrBottom — consistencia
                // Mismo motivo que en detectDoubleTopOrBottom (retroceso relativo al impulso
                // previo, citado expresamente por el usuario) — consistencia.
                val inicioImpulso = (idx1 - 20).coerceAtLeast(0)
                val swingLowAntesPico1 = (inicioImpulso..idx1).minOf { window[it].low }
                val impulsoPrevio = peak1 - swingLowAntesPico1
                if (impulsoPrevio > 0 && (avgPeak - neckline) < impulsoPrevio / 3.0) continue
                if (levelDiffPercent < mejorDiff) {
                    mejorJ = j
                    mejorDiff = levelDiffPercent
                }
            }
            if (mejorJ >= 0) {
                val idx1 = localMaxima[mejorJ]
                val idx2 = localMaxima[i]
                val neckline = (idx1..idx2).minOf { window[it].low }
                // A DIFERENCIA de detectDoubleTopOrBottom: NO se exige ruptura confirmada para
                // devolver un resultado — si no hay ruptura todavía, es FORMING; si la hay, CONFIRMED.
                val confirmedBreakdown = (idx2 + 1 until window.size).any { k -> window[k].close < neckline }
                // NUEVO — mismo motivo que en detectTripleTopForSellTiming (pedido expresamente):
                // si la ruptura ocurrió hace más de 6 meses, el patrón ya no cuenta — se prueba
                // con hombros más antiguos (siguiente i) en vez de devolver una ruptura vieja.
                val estaCaducado = confirmedBreakdown && (window.size - 1 - (idx2 + 1 until window.size).first { k -> window[k].close < neckline }) > CADUCIDAD_PATRON_SEMANAS
                if (!estaCaducado) {
                    return DoubleTopSellResult(
                        state = if (confirmedBreakdown) HeadAndShouldersState.CONFIRMED else HeadAndShouldersState.FORMING,
                        firstDate = window[idx1].datetime,
                        secondDate = window[idx2].datetime,
                        neckline = neckline
                    )
                }
            }
        }
        return DoubleTopSellResult(HeadAndShouldersState.NONE)
    }

    /**
     * Detecta divergencia BAJISTA precio/RSI: el precio hace un máximo más alto mientras el RSI
     * hace un máximo más bajo (pérdida de momentum en plena subida) — señal clásica de posible
     * agotamiento alcista, usada SOLO en "Momento idóneo para la venta" (a diferencia de
     * detectMomentumPriceDivergence, que detecta el patrón espejo — alcista — para el lado de
     * compra).
     *
     * Busca los DOS ÚLTIMOS picos de precio dentro de las últimas [windowWeeks] semanas (8-12
     * semanas pedido, se usa 10 como punto medio). Para reducir falsos positivos, exige que el
     * RSI de AMBOS picos esté en zona alta (> [rsiHighThreshold], 60 pedido expresamente) — una
     * divergencia en zona baja de RSI es mucho menos fiable como señal de venta.
     */
    data class BearishDivergenceResult(
        val detected: Boolean,
        val firstPeakDate: String? = null,
        val secondPeakDate: String? = null
    )

    fun detectBearishPriceRsiDivergence(
        candles: List<Candle>,
        windowWeeks: Int = 10,
        rsiHighThreshold: Double = 60.0
    ): BearishDivergenceResult {
        val NO_DIVERGENCE = BearishDivergenceResult(false)
        val rsiSeries = TechnicalAnalysis.calculateRsi(candles, period = 14)
        if (candles.size < windowWeeks + 14) return NO_DIVERGENCE

        val inicio = (candles.size - windowWeeks).coerceAtLeast(0)
        val subCandles = candles.subList(inicio, candles.size)
        val subRsi = rsiSeries.subList(inicio, candles.size)

        val swings = TechnicalAnalysis.findSwingPoints(subCandles, lookback = 2)
        val picos = swings.filter { it.type == SwingType.HIGH }
        if (picos.size < 2) return NO_DIVERGENCE

        val pico1 = picos[picos.size - 2]
        val pico2 = picos[picos.size - 1]
        val rsiPico1 = subRsi.getOrNull(pico1.index) ?: return NO_DIVERGENCE
        val rsiPico2 = subRsi.getOrNull(pico2.index) ?: return NO_DIVERGENCE

        val precioSube = pico2.price > pico1.price
        val rsiBaja = rsiPico2 < rsiPico1
        val ambosEnZonaAlta = rsiPico1 > rsiHighThreshold && rsiPico2 > rsiHighThreshold

        return if (precioSube && rsiBaja && ambosEnZonaAlta) {
            BearishDivergenceResult(true, pico1.datetime, pico2.datetime)
        } else {
            NO_DIVERGENCE
        }
    }

    /**
     * Cruce DESCENDENTE de la SMA50 por debajo de la SMA200 ("cruz de la muerte" clásica de
     * medio/largo plazo, sobre velas SEMANALES — DISTINTA de findSma20CrossedBelowSma50Date de
     * arriba, que es la cruz de la muerte de SMA20/SMA50 ya usada en Top10/Futuras Compras; esta
     * es una señal nueva e independiente, pedida expresamente solo para "Momento idóneo para la
     * venta"). SMA200 sobre velas semanales necesita ~200 semanas (~4 años) de histórico, así que
     * esta función solo da señal real con velas de rango FIVE_YEARS.
     *
     * Ventana de detección de [lookbackPeriods] semanas (4 semanas pedido expresamente, más
     * amplia que las 2 semanas del cruce SMA20/SMA50 — al ser medias mucho más lentas, la señal
     * se considera "vigente" durante más tiempo tras producirse).
     */
    fun findSma50CrossedBelowSma200Date(candles: List<Candle>, shortLength: Int = 50, longLength: Int = 200, lookbackPeriods: Int = 4): String? {
        val sma50 = TechnicalAnalysis.simpleMovingAverage(candles, shortLength)
        val sma200 = TechnicalAnalysis.simpleMovingAverage(candles, longLength)
        val n = candles.size
        if (n < 2 || sma50.size < 2 || sma200.size < 2) return null
        for (i in maxOf(1, n - lookbackPeriods) until n) {
            val prevSma50 = sma50.getOrNull(i - 1) ?: continue
            val currSma50 = sma50.getOrNull(i) ?: continue
            val prevSma200 = sma200.getOrNull(i - 1) ?: continue
            val currSma200 = sma200.getOrNull(i) ?: continue
            if (prevSma50 >= prevSma200 && currSma50 < currSma200) return candles[i].datetime
        }
        return null
    }
}
