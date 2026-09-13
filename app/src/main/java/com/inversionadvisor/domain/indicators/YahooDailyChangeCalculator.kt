package com.inversionadvisor.domain.indicators

import com.inversionadvisor.data.remote.YahooChartResultDto

/**
 * ÚNICA fuente de verdad para "precio actual vs cierre de ayer" — antes esta misma lógica
 * estaba DUPLICADA por separado en MarketRepository.fetchYesterdayClose (usado por la ficha de
 * un stock) y MarketMoversRepository.fetchMoverForSymbol (usado por el banner de
 * ganadores/perdedores del Dashboard) — dos copias casi idénticas pero mantenidas por separado,
 * cada una con sus propios ajustes a lo largo del tiempo. El resultado: para un mismo stock, el
 * banner y la ficha de detalle podían mostrar dos % de variación DISTINTOS en el mismo momento
 * — reportado por el usuario con Adobe (+2,12% en el banner, -0,09% en la ficha) — no porque
 * ninguno de los dos estuviera "mal" en sí, sino porque cada uno calculaba con su propia
 * petición, en su propio momento, y (el riesgo real) con su propia fórmula que podía ir
 * divergiendo con el tiempo si se tocaba una copia y no la otra.
 *
 * Ahora ambos sitios llaman a esta MISMA función sobre su propia respuesta de Yahoo (cada uno
 * sigue usando su propio cliente HTTP — yahooFinanceApi para la ficha, yahooFinanceMoversApi
 * para el banner — eso se queda así a propósito, por los límites de peticiones/concurrencia de
 * cada uno, ver NetworkModule), así que la FÓRMULA nunca puede divergir. Sigue habiendo un
 * margen de tiempo entre una petición y otra (cada sitio tiene su propia caché con su propio
 * TTL), así que un desfase pequeño y pasajero sigue siendo posible si justo se piden en
 * instantes distintos durante un movimiento de precio muy rápido — pero ya no por una fórmula
 * distinta, que era el riesgo real de que la diferencia fuera grande y persistente.
 */
object YahooDailyChangeCalculator {

    data class Result(val currentPrice: Double, val yesterdayClose: Double) {
        val changePercent: Double get() = (currentPrice - yesterdayClose) / yesterdayClose * 100
    }

    /**
     * A partir del resultado de una petición "5d"/"1d" a Yahoo (v8/finance/chart), calcula el
     * precio actual y el cierre de AYER. null si no hay datos suficientes.
     *
     * Usa las marcas de tiempo REALES de cada vela (no su posición en el array) para saber con
     * certeza cuál es "hoy" y cuál es "ayer" — ver el histórico de comentarios de
     * fetchYesterdayClose/fetchMoverForSymbol para el porqué (fallo real encontrado con
     * CrowdStrike: asumir por posición sumaba un día extra de movimiento cuando Yahoo aún no
     * había añadido la vela de hoy en el momento de la petición).
     */
    fun compute(chartResult: YahooChartResultDto): Result? {
        val close = chartResult.meta?.regularMarketPrice ?: return null
        val rawCloses = chartResult.indicators?.quote?.firstOrNull()?.close ?: emptyList()
        val rawTimestamps = chartResult.timestamp ?: emptyList()
        // Emparejados ANTES de filtrar los huecos (cierres null) — si se filtrara "closes" por
        // su cuenta, se desalinearía frente a "timestamps" (mismo índice, tamaños distintos).
        val datedCloses = rawTimestamps.zip(rawCloses).mapNotNull { (ts, c) -> c?.let { ts to it } }
        if (datedCloses.size < 2) return null

        // Se compara la ANTIGÜEDAD en horas desde AHORA MISMO (no fechas de calendario en UTC,
        // que falla de forma distinta según la zona horaria de cada bolsa) — se considera "ya
        // cerrada de verdad" cualquier vela de al menos 18 horas.
        val nowEpochSeconds = System.currentTimeMillis() / 1000
        val closedIndex = datedCloses.indexOfLast { (ts, _) -> (nowEpochSeconds - ts) > (18 * 3600) }
        if (closedIndex < 0) return null
        val mostRecentClosedClose = datedCloses[closedIndex].second

        // Fin de semana/festivo: si el precio en vivo coincide con el último cierre ya
        // completado, comparar esa sesión contra sí misma daría siempre 0% — se compara esa
        // última sesión contra la ANTERIOR a ella (p. ej., en fin de semana: viernes contra
        // jueves).
        val yesterdayClose = if (kotlin.math.abs(close - mostRecentClosedClose) < 0.0001) {
            // AÑADIDO — diagnóstico: reportado por el usuario que ADBE se quedó en 0% cuando
            // debería estar en +2% — si esta rama se dispara en un día normal de mercado (no
            // fin de semana/festivo), es la causa: "close" (el precio en vivo,
            // meta.regularMarketPrice) puede no reflejar movimiento fuera de la sesión regular
            // (pre-market/after-hours) — coincide con el cierre de la sesión regular ya
            // terminada aunque el precio SÍ se haya movido después, en horario extendido.
            android.util.Log.i(
                "CompanyFinancialsFetch",
                "YahooDailyChangeCalculator: precio en vivo ($close) == último cierre completado ($mostRecentClosedClose) — se compara contra la sesión ANTERIOR a esa en su lugar"
            )
            if (closedIndex < 1) return null
            datedCloses[closedIndex - 1].second
        } else {
            mostRecentClosedClose
        }
        return Result(currentPrice = close, yesterdayClose = yesterdayClose)
    }
}
