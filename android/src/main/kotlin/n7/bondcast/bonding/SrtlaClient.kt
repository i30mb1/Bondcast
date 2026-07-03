package n7.bondcast.bonding

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

internal interface SrtlaClient {

    /** Живой снимок линков бондинга (пустой, когда бондинг не запущен). */
    val links: StateFlow<List<LinkInfo>>

    suspend fun start(target: SrtlaTarget): Int

    suspend fun stop()
}

internal fun srtlaClient(context: Context): SrtlaClient =
    SrtlaClientWithLogging(SrtlaClientWithMutex(SrtlaClientImpl(context)))
