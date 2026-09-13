package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caché de PER (trailing P/E) por símbolo — sirve tanto para los 13 ETFs
 * sectoriales (Symbols.SECTOR_ETFS) como para stocks individuales, en la
 * misma tabla (los tickers no colisionan entre sí). trailingPe puede ser
 * null (Yahoo no siempre lo da, p. ej. empresas sin beneficios).
 */
@Entity(tableName = "pe_ratio")
data class PeRatioEntity(
    @PrimaryKey val symbol: String,
    val trailingPe: Double?,
    /** true si el EPS de los últimos 12 meses (Finviz "EPS (ttm)") es negativo — pedido
     *  expresamente para que quede reflejado cuando trailingPe en realidad viene del Forward P/E
     *  (fallback, ver FinvizPeParser.parseStockPe) porque Finviz muestra "P/E -" al no poder
     *  calcular un trailing P/E con beneficio negativo. Caso real: Lumentum. Por defecto false —
     *  también lo es para los stocks .MC (Yahoo WebView), que de momento no comprueban esto. */
    val hasNegativeTrailingEarnings: Boolean = false,
    val updatedAtEpochMillis: Long
)
