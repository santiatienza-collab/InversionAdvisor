package com.inversionadvisor.data.repository

import com.inversionadvisor.data.local.CacheConfig
import com.inversionadvisor.data.local.dao.ScreenerDao
import com.inversionadvisor.data.local.dao.StockUniverseDao
import com.inversionadvisor.data.local.entities.IndexUniverseEntryEntity
import com.inversionadvisor.data.local.entities.IndexUniverseMetaEntity
import com.inversionadvisor.data.remote.WikipediaIndexApi
import com.inversionadvisor.data.remote.YahooSearchApi
import com.inversionadvisor.domain.indicators.GicsSectorMapper
import com.inversionadvisor.domain.indicators.IndexConstituentRow
import com.inversionadvisor.domain.indicators.IndexConstituentsHtmlParser
import com.inversionadvisor.domain.indicators.SimpleSectorMapper
import com.inversionadvisor.domain.model.StockIndexMeta
import com.inversionadvisor.domain.model.StockUniverse
import com.inversionadvisor.domain.model.StockUniverseEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Universo de stocks del screener, auto-actualizado: en vez de una lista
 * fija embebida en el código, se scrapea la tabla de constituyentes de
 * cada índice desde su página fuente y se cachea en Room con TTL de una
 * semana — la composición de estos índices cambia solo unas pocas veces al
 * año, así que no hace falta consultarla más a menudo.
 *
 * S&P 500 e IBEX 35 se scrapean de Wikipedia (misma técnica que ya se usa
 * para AAII). Nasdaq-100 usa una fuente distinta, slickcharts.com/nasdaq100:
 * su página de Wikipedia ya no incluye la tabla completa de constituyentes
 * embebida (solo un aviso remitiendo a enlaces externos), así que dejó de
 * servir para mantener la lista sola; slickcharts sí publica la tabla
 * completa y en vivo, así que Nasdaq-100 también se auto-actualiza cada
 * semana igual que los otros dos mercados.
 *
 * Los tres mercados tienen además una lista estática de respaldo embebida
 * en StockUniverse (foto fija) por si la fuente en vivo falla (sin red,
 * cambio de formato de la página...), así el screener nunca se queda sin
 * universo que analizar — pero mientras la fuente en vivo funcione, es
 * ella la que manda, no la lista estática.
 */
class StockUniverseRepository(
    private val wikipediaIndexApi: WikipediaIndexApi,
    private val dao: StockUniverseDao,
    private val yahooSearchApi: YahooSearchApi = com.inversionadvisor.data.remote.NetworkModule.yahooSearchApi,
    /** null solo en tests/usos aislados sin base de datos completa — en la app real siempre se
     *  pasa (ver ServiceLocator). Respaldo de findSectorEtf() para símbolos que esta tabla
     *  (scrapeada de Wikipedia/slickcharts, solo S&P 500/Nasdaq-100/IBEX 35) no tiene. */
    private val screenerDao: ScreenerDao? = null
) {

    fun observeIndexMeta(): Flow<List<StockIndexMeta>> =
        dao.observeMeta().map { list -> list.map { it.toDomain() } }

    private fun isStale(cachedAtMillis: Long?, maxAgeMillis: Long): Boolean =
        cachedAtMillis == null || System.currentTimeMillis() - cachedAtMillis > maxAgeMillis

    suspend fun refreshAllIfStale(maxAgeMillis: Long = CacheConfig.INDEX_UNIVERSE_TTL_MILLIS) {
        for (index in INDEX_SOURCES) {
            try {
                val meta = dao.getMetaOnce(index.name)
                if (isStale(meta?.updatedAtEpochMillis, maxAgeMillis)) {
                    refreshIndex(index)
                }
            } catch (e: Exception) {
                // Si falla la actualización de un índice, se sigue usando lo que hubiera
                // en caché (o el respaldo estático si nunca se ha podido refrescar) — un
                // fallo de red no debe dejar el screener sin universo.
            }
        }
    }

    private suspend fun refreshIndexIfStale(indexName: String, maxAgeMillis: Long = CacheConfig.INDEX_UNIVERSE_TTL_MILLIS) {
        val index = INDEX_SOURCES.find { it.name == indexName } ?: return
        try {
            val meta = dao.getMetaOnce(index.name)
            if (isStale(meta?.updatedAtEpochMillis, maxAgeMillis)) {
                refreshIndex(index)
            }
        } catch (e: Exception) {
            // Igual que en refreshAllIfStale: un fallo de red no debe dejar el
            // screener sin universo, se sigue usando lo que hubiera en caché.
        }
    }

    private suspend fun refreshIndex(index: IndexSource) {
        val html = wikipediaIndexApi.getPageHtml(index.sourceUrl).string()
        val rows = IndexConstituentsHtmlParser.parse(html, index.tableSelector)
        var entries = rows.mapNotNull { row -> index.toEntry(row) }
        if (entries.isEmpty()) throw IllegalStateException("${index.name}: 0 filas parseadas, se descarta el refresco")
        // Corrección de sectores del Nasdaq-100 (ver comentario de correctNasdaq100SectorsFromSp500)
        // — se hace aquí, después de parsear pero antes de guardar, para que lo que se guarde en
        // caché ya sea el sector corregido.
        if (index.name == "NASDAQ100") {
            entries = correctNasdaq100SectorsFromSp500(entries)
        }
        dao.clearIndex(index.name)
        dao.insertEntries(entries)
        dao.upsertMeta(IndexUniverseMetaEntity(index.name, System.currentTimeMillis(), entries.size))
    }

    /**
     * Corrige el sector del Nasdaq-100 usando la clasificación GICS real del S&P 500, para los
     * símbolos que están en los dos índices a la vez (la mayoría de los grandes valores del
     * Nasdaq-100 — Apple, Adobe, Microsoft...— también están en el S&P 500).
     *
     * FALLO ENCONTRADO Y CORREGIDO: slickcharts.com (la fuente del Nasdaq-100, ver comentario
     * de INDEX_SOURCES) no da clasificación GICS, así que el sector se adivinaba por palabras
     * clave DENTRO DEL PROPIO NOMBRE de la empresa (SimpleSectorMapper) — "tecnología",
     * "banco", "farmacéutica"... El problema: el nombre de una empresa casi nunca describe su
     * sector de forma literal ("Apple Inc.", "Adobe Inc." no contienen ninguna palabra clave de
     * tecnología), así que caían todas en el valor por defecto, "XLI" (Industrial) — de ahí que
     * Apple y Adobe salieran como industriales.
     *
     * AVISO HONESTO — el límite de esta corrección: solo funciona si el S&P 500 ya se ha
     * analizado alguna vez antes (para tener con qué comparar). Si nunca has analizado el S&P
     * 500, un stock del Nasdaq-100 que también esté ahí seguirá con el sector adivinado por
     * nombre (el mismo comportamiento de antes) hasta que analices el S&P 500 en algún momento.
     */
    private suspend fun correctNasdaq100SectorsFromSp500(entries: List<IndexUniverseEntryEntity>): List<IndexUniverseEntryEntity> {
        val sp500BySymbol = dao.getByIndexOnce("SP500").associateBy { it.symbol }
        return entries.map { entry ->
            sp500BySymbol[entry.symbol]?.let { entry.copy(sectorEtf = it.sectorEtf) } ?: entry
        }
    }

    /**
     * Universo de UN SOLO mercado (una pestaña del screener): solo refresca
     * y devuelve ese índice, no los otros dos — así "Analizar" escanea
     * únicamente el universo de la pestaña activa. Cada entrada conserva el
     * índice (indexName) al que pertenece, así que un mismo símbolo
     * presente en varios índices (p. ej. AAPL en SP500 y NASDAQ100) puede
     * aparecer en ambas pestañas.
     */
    suspend fun getUniverseForScreening(indexName: String): List<StockUniverseEntry> {
        refreshIndexIfStale(indexName)
        val cached = dao.getByIndexOnce(indexName).map { StockUniverseEntry(it.symbol, it.name, it.sectorEtf, it.indexName) }
        if (cached.isNotEmpty()) return cached
        return when (indexName) {
            "SP500" -> StockUniverse.SP500
            "NASDAQ100" -> StockUniverse.NASDAQ100
            "IBEX35" -> StockUniverse.IBEX35
            else -> emptyList()
        }
    }

    /**
     * Universo COMBINADO de los 3 mercados (SP500 + NASDAQ100 + IBEX35),
     * deduplicado por símbolo, para la búsqueda de la pestaña "Busca" —
     * a diferencia de getUniverseForScreening (una pestaña a la vez), aquí
     * se necesitan los tres a la vez para poder buscar en cualquiera de
     * ellos desde un único cuadro de búsqueda.
     */
    suspend fun getFullUniverseForSearch(): List<StockUniverseEntry> {
        val combined = buildList {
            addAll(getUniverseForScreening("SP500"))
            addAll(getUniverseForScreening("NASDAQ100"))
            addAll(getUniverseForScreening("IBEX35"))
        }
        return combined.distinctBy { it.symbol }
    }

    /**
     * ETF sectorial (Symbols.SECTOR_ETFS) de un stock por su símbolo, para
     * comparar su PER con el PER medio de su sector en "Momento idóneo para
     * la venta". No refresca nada (usa lo que ya haya en caché) — si el
     * universo aún no se ha cargado nunca, devuelve null y el llamador
     * simplemente no muestra la comparación de PER sectorial.
     */
    suspend fun findSectorEtf(symbol: String): String? =
        dao.getBySymbolOnce(symbol)?.sectorEtf
            ?: screenerDao?.let { sd ->
                sd.findGenericSectorOnce(symbol)?.sectorEtf
                    ?: sd.findBuyOpportunitySectorOnce(symbol)?.sectorEtf
                    ?: sd.findUptrendSectorOnce(symbol)?.sectorEtf
            }

    /**
     * Ficha completa (symbol/name/sectorEtf/indexName) de un stock por su
     * símbolo — para poder guardarlo en favoritos desde sitios que solo
     * conocen el símbolo (p. ej. la estrellita de la ficha de detalle
     * abierta desde Análisis, que hasta ahora no la tenía). Igual que
     * findSectorEtf, no refresca nada: si el universo no se ha cargado
     * nunca, devuelve null.
     */
    suspend fun findEntry(symbol: String): StockUniverseEntry? =
        dao.getBySymbolOnce(symbol)?.let { StockUniverseEntry(it.symbol, it.name, it.sectorEtf, it.indexName) }

    /**
     * Búsqueda libre contra TODO el mercado (v1/finance/search de Yahoo), no
     * solo S&P 500/Nasdaq-100/IBEX 35 — para poder encontrar valores que no
     * pertenecen a ninguno de esos tres índices, como OPVs recientes (Tempus
     * AI, Pony AI...). Fusionada con la pestaña "Busca": se llama cuando la
     * búsqueda normal (contra el universo ya cacheado en memoria) no
     * encuentra nada.
     *
     * NO VERIFICADO EN VIVO — primera vez que se usa este endpoint en la
     * app; si devuelve vacío o falla sistemáticamente, revisar con Logcat.
     * Se filtra a quoteType == "EQUITY" (descarta ETFs, índices, divisas,
     * criptomonedas... que también puede devolver este endpoint).
     */
    suspend fun searchWholeMarket(query: String): List<StockUniverseEntry> {
        if (query.isBlank()) return emptyList()
        val response = yahooSearchApi.search(query.trim())
        return response.quotes.orEmpty()
            .filter { it.quoteType.equals("EQUITY", ignoreCase = true) }
            .mapNotNull { quote ->
                val symbol = quote.symbol ?: return@mapNotNull null
                val name = quote.longname ?: quote.shortname ?: return@mapNotNull null
                StockUniverseEntry(symbol = symbol, name = name, sectorEtf = "", indexName = "")
            }
    }

    private data class IndexSource(
        val name: String,
        val sourceUrl: String,
        /** Selector CSS de la tabla de constituyentes en esta fuente concreta (ver IndexConstituentsHtmlParser). */
        val tableSelector: String = "table.wikitable",
        val toEntry: (IndexConstituentRow) -> IndexUniverseEntryEntity?
    )

    companion object {
        private val INDEX_SOURCES = listOf(
            IndexSource(
                name = "SP500",
                sourceUrl = "https://en.wikipedia.org/wiki/List_of_S%26P_500_companies"
            ) { row ->
                row.gicsSector?.let { sector ->
                    val etf = GicsSectorMapper.mapToEtf(sector, row.gicsSubIndustry)
                    IndexUniverseEntryEntity("SP500", row.ticker.replace(".", "-"), row.company, etf)
                }
            },
            IndexSource(
                name = "NASDAQ100",
                // slickcharts.com en vez de Wikipedia (ver comentario de clase): publica la
                // tabla completa de los 102 componentes en una tabla HTML normal, no
                // "wikitable", de ahí el tableSelector distinto.
                sourceUrl = "https://www.slickcharts.com/nasdaq100",
                tableSelector = "table"
            ) { row ->
                // slickcharts no da clasificación GICS (solo # / Company / Symbol / Weight /
                // Price...), así que el sector se estima por palabras clave del nombre
                // (SimpleSectorMapper, la misma técnica que ya usa IBEX 35).
                val etf = row.gicsSector?.let { sector -> GicsSectorMapper.mapToEtf(sector, row.gicsSubIndustry) }
                    ?: SimpleSectorMapper.mapToEtf(row.company)
                IndexUniverseEntryEntity("NASDAQ100", row.ticker.replace(".", "-"), row.company, etf)
            },
            IndexSource(
                name = "IBEX35",
                sourceUrl = "https://en.wikipedia.org/wiki/IBEX_35"
            ) { row ->
                row.simpleSector?.let { sector ->
                    val etf = SimpleSectorMapper.mapToEtf(sector)
                    // El ticker de la tabla de IBEX 35 ya viene en formato Yahoo (sufijo .MC), no se toca.
                    IndexUniverseEntryEntity("IBEX35", row.ticker, row.company, etf)
                }
            }
        )
    }
}

private fun IndexUniverseMetaEntity.toDomain(): StockIndexMeta = StockIndexMeta(
    indexName = indexName,
    displayName = when (indexName) {
        "SP500" -> "S&P 500"
        "NASDAQ100" -> "Nasdaq-100"
        "IBEX35" -> "IBEX 35"
        else -> indexName
    },
    updatedAtEpochMillis = updatedAtEpochMillis,
    count = count
)
