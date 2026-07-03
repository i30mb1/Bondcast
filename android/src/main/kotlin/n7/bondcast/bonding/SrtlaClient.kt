package n7.bondcast.bonding

internal interface SrtlaClient {

    suspend fun start(target: SrtlaTarget): Int

    suspend fun stop()
}

internal fun srtlaClient(): SrtlaClient =
    SrtlaClientWithLogging(SrtlaClientWithMutex(SrtlaClientImpl()))
