package n7.bondcast.bonding.net

import android.content.Context
import android.net.Network
import n7.srtla.scheduler.Transport

internal interface NetworkProvider {

    fun start(onAvailable: (Network, Transport) -> Unit, onLost: (Network) -> Unit)

    fun stop()
}

internal fun networkProvider(context: Context): NetworkProvider =
    NetworkProviderWithLogging(ConnectivityNetworkProvider(context.applicationContext))
