package com.inversionadvisor.data.local.entities

import androidx.room.Entity

/**
 * Vela histórica cacheada, clave compuesta por símbolo+intervalo+fecha
 * para poder guardar 1d/1w/1m/1y/5y del mismo símbolo sin colisionar.
 */
@Entity(
    tableName = "candles",
    primaryKeys = ["symbol", "interval", "datetime"]
)
data class CandleEntity(
    val symbol: String,
    // Pese al nombre del campo (heredado), NO es el intervalo crudo de la API
    // (p. ej. "1week"), sino ChartRange.cacheKey — el nombre del enum
    // (ONE_YEAR, FIVE_YEARS...). Dos rangos pueden compartir el mismo
    // intervalo de API (ONE_YEAR y FIVE_YEARS son ambos "1week") pero
    // representan históricos distintos, así que necesitan cada uno su
    // propio hueco en la caché — ver ChartRange.cacheKey.
    val interval: String,
    val datetime: String, // ISO-ish string tal cual la devuelve Twelve Data
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long?
)
