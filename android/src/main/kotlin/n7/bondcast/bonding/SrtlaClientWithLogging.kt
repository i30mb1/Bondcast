package n7.bondcast.bonding

import android.util.Log

internal class SrtlaClientWithLogging(private val delegate: SrtlaClient) : SrtlaClient {

    override suspend fun start(target: SrtlaTarget): Int {
        Log.i(TAG, "start bonding → ${target.host}:${target.port}")
        val port = delegate.start(target)
        Log.i(TAG, "local relay listening on 127.0.0.1:$port")
        return port
    }

    override suspend fun stop() {
        Log.i(TAG, "stop bonding")
        delegate.stop()
    }

    private companion object {
        const val TAG = "SrtlaClient"
    }
}
