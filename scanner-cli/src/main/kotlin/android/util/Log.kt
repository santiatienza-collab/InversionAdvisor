package android.util

/**
 * STUB — no es el android.util.Log real (eso solo existe dentro de Android). Los ficheros de
 * domain/indicators compartidos con la app llaman a android.util.Log.i/.w para los logs de
 * diagnóstico (HchDiagnostic, DoubleTopDiagnostic, PeRatioFetch...) que solo tienen sentido
 * dentro de la app (Logcat). Aquí, en el escáner de línea de comandos, esos mismos logs se
 * mandan a la salida estándar en vez de perderse — útil también para depurar el propio escáner
 * si hace falta ver qué patrón se descartó y por qué, igual que en la app.
 */
object Log {
    fun i(tag: String, msg: String): Int {
        println("[$tag] $msg")
        return 0
    }

    fun w(tag: String, msg: String): Int {
        println("[$tag] AVISO: $msg")
        return 0
    }
}
