package com.inversionadvisor.domain.indicators

/**
 * Mapeo de ticker (.MC) al slug que usa Investing.com en la URL de su página
 * de "resumen financiero" (.../equities/{slug}-financial-summary) — ver
 * MarketRepository.fetchNetIncomesFromInvesting.
 *
 * Slugs sacados en vivo de la tabla de componentes del IBEX 35 de Investing.com
 * (es.investing.com/indices/spain-35-components, consultada en septiembre de 2026),
 * cruzados con los tickers .MC que ya usa esta misma app en su lista de respaldo
 * (ver StockUniverse.IBEX35) — a diferencia del primer intento, estos 35 SÍ están
 * verificados contra la página real, no adivinados.
 *
 * IMPORTANTE:
 * - La composición del IBEX 35 se revisa cada 6 meses y los slugs pueden cambiar si
 *   una empresa cambia de nombre — si alguno empieza a fallar, lo primero es
 *   comprobar la URL real en investing.com antes de asumir un bug en el scraper.
 * - Que el slug exista no garantiza que esa página tenga la tabla "Net Income"
 *   rellena — eso lo comprueba en tiempo de ejecución
 *   InvestingFinancialsParser.parseRecentAnnualNetIncomes, devolviendo lista vacía
 *   si no la encuentra.
 */
val IBEX35_INVESTING_SLUGS: Map<String, String> = mapOf(
    "ACS.MC" to "acs-cons-y-serv",
    "ACX.MC" to "acerinox",
    "AMS.MC" to "amadeus",
    "ANA.MC" to "acciona-sa",
    "ANE.MC" to "corp-acciona-energias-renovables",
    "BBVA.MC" to "bbva",
    "BKT.MC" to "bankinter",
    "CABK.MC" to "caixabank-sa",
    "CLNX.MC" to "cellnex-telecom",
    "COL.MC" to "inmob-colonial",
    "AENA.MC" to "aena-aeropuertos-sa",
    "ELE.MC" to "endesa",
    "ENG.MC" to "enagas",
    "FDR.MC" to "fluidra-sa",
    "FER.MC" to "grupo-ferrovial",
    "GRF.MC" to "grifols",
    "IAG.MC" to "intl.-cons.-air-grp",
    "IBE.MC" to "iberdrola",
    "IDR.MC" to "indra-sistemas",
    "ITX.MC" to "inditex",
    "LOG.MC" to "logista",
    "MAP.MC" to "mapfre",
    "MRL.MC" to "merlin-properties-sa",
    "MTS.MC" to "arcelormittal-reg",
    "NTGY.MC" to "gas-natural-sdg",
    "PUIG.MC" to "puig-brands-sa",
    "RED.MC" to "red-electrica",
    "REP.MC" to "repsol-ypf",
    "ROVI.MC" to "laboratorios-farmaceuticos-rovi-sa",
    "SAB.MC" to "bco-de-sabadell",
    "SAN.MC" to "banco-santander",
    "SCYR.MC" to "sacyr-valle",
    "SLR.MC" to "solaria-energia-y-medio-ambiente",
    "TEF.MC" to "telefonica",
    "UNI.MC" to "unicaja-banco-sa"
)
