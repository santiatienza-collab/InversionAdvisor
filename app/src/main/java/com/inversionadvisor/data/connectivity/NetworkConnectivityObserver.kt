package com.inversionadvisor.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Observa si el dispositivo tiene conexión a Internet — usa
 * NetworkCapabilities.NET_CAPABILITY_INTERNET vía ConnectivityManager.NetworkCallback,
 * envuelto en un Flow para poder usarlo directamente desde Compose con collectAsState().
 *
 * CORREGIDO — encontrado con el aviso de "conexión perdida/recuperada" saltando en bucle
 * incluso con WiFi estable: antes también exigía NET_CAPABILITY_VALIDATED (Android revalida
 * este indicador periódicamente por su cuenta, incluso con una conexión que nunca ha dejado
 * de funcionar, quitándolo y volviéndolo a poner en cuestión de segundos) — eso disparaba el
 * aviso constantemente sin que hubiera ningún cambio real. Ahora solo se exige
 * NET_CAPABILITY_INTERNET (¿hay una red capaz de dar acceso a Internet?, no "¿se ha
 * revalidado hace un momento?"), mucho más estable para este uso.
 *
 * Para el aviso de "conexión perdida/recuperada" en la parte superior de la pantalla (ver
 * ConnectivityBanner).
 */
class NetworkConnectivityObserver(context: Context) {
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    fun observe(): Flow<Boolean> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(true)
            }

            override fun onLost(network: Network) {
                trySend(false)
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            }

            override fun onUnavailable() {
                trySend(false)
            }
        }

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, callback)

        // Estado inicial, para no esperar al primer evento de cambio para saber si hay conexión
        // ya mismo al arrancar.
        val activeNetwork = connectivityManager.activeNetwork
        val activeCapabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val initiallyConnected = activeCapabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        trySend(initiallyConnected)

        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}
