package n7.bondcast.bonding.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import n7.srtla.scheduler.Transport

internal class ConnectivityNetworkProvider(context: Context) : NetworkProvider {

    private val connectivityManager =
        requireNotNull(context.getSystemService(ConnectivityManager::class.java))
    private val callbacks = mutableListOf<ConnectivityManager.NetworkCallback>()

    override fun start(onAvailable: (Network, Transport) -> Unit, onLost: (Network) -> Unit) {
        for ((capability, transport) in TRANSPORTS) {
            val request = NetworkRequest.Builder()
                .addTransportType(capability)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = onAvailable(network, transport)
                override fun onLost(network: Network) = onLost(network)
            }
            runCatching { connectivityManager.requestNetwork(request, callback) }
                .onSuccess { callbacks.add(callback) }
                .onFailure { Log.w(TAG, "requestNetwork $transport failed", it) }
        }
    }

    override fun stop() {
        for (callback in callbacks) runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        callbacks.clear()
    }

    private companion object {
        const val TAG = "SrtlaNet"
        val TRANSPORTS = listOf(
            NetworkCapabilities.TRANSPORT_CELLULAR to Transport.CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI to Transport.WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET to Transport.ETHERNET,
        )
    }
}
