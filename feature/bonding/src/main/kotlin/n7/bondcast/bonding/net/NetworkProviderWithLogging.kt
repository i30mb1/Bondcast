package n7.bondcast.bonding.net

import android.net.Network
import android.util.Log
import n7.srtla.scheduler.Transport

internal class NetworkProviderWithLogging(private val delegate: NetworkProvider) : NetworkProvider {

    override fun start(onAvailable: (Network, Transport) -> Unit, onLost: (Network) -> Unit) {
        delegate.start(
            onAvailable = { network, transport ->
                Log.i(TAG, "link up: $transport $network")
                onAvailable(network, transport)
            },
            onLost = { network ->
                Log.i(TAG, "link lost: $network")
                onLost(network)
            },
        )
    }

    override fun stop() {
        Log.i(TAG, "stop network provider")
        delegate.stop()
    }

    private companion object {
        const val TAG = "SrtlaNet"
    }
}
