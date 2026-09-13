package com.inversionadvisor.data.remote

import retrofit2.http.GET
import retrofit2.http.Url

/**
 * Endpoints de la API pública de SEC EDGAR (data.sec.gov / www.sec.gov) — JSON oficial,
 * SIN necesidad de API key ni de parsear HTML. Documentación:
 * https://www.sec.gov/search-filings/edgar-application-programming-interfaces
 *
 * Se usa para los ingresos netos de "Datos de Empresa" en vez del scraping de Yahoo (ver
 * MarketRepository.refreshCompanyFinancialsInternal) para cualquier símbolo que la SEC cubra —
 * en la práctica, todo EE.UU. (S&P 500, Nasdaq 100). NO cubre el IBEX 35 (empresas españolas no
 * reportan a la SEC), que sigue usando el scraping de Yahoo como respaldo.
 *
 * Las dos peticiones van a hosts DISTINTOS (www.sec.gov para el mapeo ticker->CIK, data.sec.gov
 * para los datos XBRL) — por eso ambos métodos usan @Url con la URL absoluta completa en vez de
 * una ruta relativa a un solo baseUrl.
 *
 * La SEC pide (política de "fair access", no aplicada con un bloqueo inmediato pero sí
 * recomendada con firmeza) identificarse con un User-Agent descriptivo con contacto real — ver
 * NetworkModule.secEdgarApi, que añade esa cabecera con el valor de SEC_EDGAR_CONTACT_EMAIL
 * (local.properties). Límite oficial: 10 peticiones/segundo por IP.
 */
interface SecEdgarApi {

    /**
     * Mapeo oficial ticker -> CIK, ~800KB. Se pide UNA sola vez por sesión de la app y se
     * guarda en memoria (ver SecEdgarTickerCache) — no cambia con la frecuencia suficiente
     * como para justificar pedirlo en cada símbolo.
     */
    @GET
    suspend fun getCompanyTickers(
        @Url url: String = "https://www.sec.gov/files/company_tickers.json"
    ): Map<String, SecCompanyTickerDto>

    /**
     * Historial completo de un concepto XBRL (p. ej. "NetIncomeLoss") para una empresa. [url]
     * se construye en MarketRepository con el CIK ya con padding a 10 dígitos:
     * "https://data.sec.gov/api/xbrl/companyconcept/CIK{cik10digitos}/us-gaap/{concepto}.json"
     */
    @GET
    suspend fun getCompanyConcept(@Url url: String): SecCompanyConceptDto
}
