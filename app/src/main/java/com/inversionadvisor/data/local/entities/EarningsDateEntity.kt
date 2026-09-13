package com.inversionadvisor.data.local.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Caché de la fecha (o rango de fechas) de la próxima publicación de resultados trimestrales,
 * por símbolo.
 *
 * SEGUNDA VERSIÓN — cambiada de fuente por completo: la primera usaba v7/finance/quote de
 * Yahoo (campo earningsTimestamp), pero ese endpoint exige un "crumb" + cookie de sesión que
 * esta app no implementa, y devolvía 401 "Invalid Cookie"/"Invalid Crumb" de forma silenciosa
 * en todas las peticiones — por eso nunca llegaba a mostrarse nada. Ahora se extrae del HTML ya
 * renderizado de la página de cotización (la misma que ya se pide para Valor Empresa e Ingresos
 * netos, vía WebView oculto — ver YahooQuotePageParser.parseEarningsDateText), sin ninguna
 * petición de red nueva.
 *
 * earningsDateText: el texto TAL CUAL lo muestra Yahoo (normalmente un rango, "28 oct - 2 nov,
 * 2026", no un día exacto) — se guarda así en vez de forzarlo a un único timestamp, para no
 * inventar precisión que la propia fuente no da. Null si no se ha podido extraer.
 */
@Entity(tableName = "earnings_date")
data class EarningsDateEntity(
    @PrimaryKey val symbol: String,
    val earningsDateText: String?,
    val updatedAtEpochMillis: Long
)
