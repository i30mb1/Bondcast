package n7.bondcast.bonding

import android.content.Context

internal interface SrtlaClient {

    suspend fun start(target: SrtlaTarget): Int

    suspend fun stop()
}

internal fun srtlaClient(context: Context): SrtlaClient =
    SrtlaClientWithLogging(SrtlaClientWithMutex(SrtlaClientImpl(context)))
