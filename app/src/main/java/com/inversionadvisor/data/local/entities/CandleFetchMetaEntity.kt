package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Registra la última vez que se pidieron a la API las velas de un símbolo+intervalo
 * concreto (key = "AAPL:1day"). Permite decidir si hace falta refrescar sin tener
 * que volver a tocar la red — evita peticiones repetidas al reabrir una pantalla.
 */
@Entity(tableName = "candle_fetch_meta")
data class CandleFetchMetaEntity(
    @PrimaryKey val key: String,
    val fetchedAtEpochMillis: Long,
    // AÑADIDO — horario extendido del gráfico 1D, pedido expresamente así ("añade horario
    // extendido, y un color apagado fuera de horas"). Límites REALES de la sesión regular
    // (epoch segundos) para ese símbolo y ese día concreto, sacados de Yahoo
    // (meta.currentTradingPeriod.regular) — varían por bolsa y por horario de verano/invierno,
    // así que no se pueden asumir a mano sin fallar para el IBEX 35 u otros símbolos no
    // estadounidenses. null en todos los rangos que no sean 1 DÍA, o si Yahoo no los dio.
    val regularSessionStartEpochSeconds: Long? = null,
    val regularSessionEndEpochSeconds: Long? = null
)
