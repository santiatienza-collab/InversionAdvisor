package com.inversionadvisor.ui.screener

import android.graphics.Matrix
import com.github.mikephil.charting.charts.Chart

/**
 * Sincroniza SOLO el eje X (pan y zoom horizontal) entre dos gráficas de
 * MPAndroidChart, dejando cada una con su propia escala vertical — así el
 * panel de precio y el panel de volumen (con rangos de valores muy
 * distintos) se desplazan y hacen zoom juntos, tal y como se ve en
 * TradingView, sin que el volumen "aplaste" la escala del precio.
 */
object ChartXAxisSync {

    /** Copia el desplazamiento/zoom horizontal de [source] a [dest]. */
    fun syncXAxis(source: Chart<*>, dest: Chart<*>) {
        val sourceValues = FloatArray(9)
        source.viewPortHandler.matrixTouch.getValues(sourceValues)

        val destValues = FloatArray(9)
        dest.viewPortHandler.matrixTouch.getValues(destValues)

        destValues[Matrix.MSCALE_X] = sourceValues[Matrix.MSCALE_X]
        destValues[Matrix.MTRANS_X] = sourceValues[Matrix.MTRANS_X]
        destValues[Matrix.MSKEW_X] = sourceValues[Matrix.MSKEW_X]

        val newMatrix = Matrix()
        newMatrix.setValues(destValues)
        dest.viewPortHandler.refresh(newMatrix, dest, true)
    }
}
