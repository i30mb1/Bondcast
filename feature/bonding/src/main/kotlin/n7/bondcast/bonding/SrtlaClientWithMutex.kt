package n7.bondcast.bonding

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class SrtlaClientWithMutex(private val delegate: SrtlaClient) : SrtlaClient {

    private val mutex = Mutex()

    override val links: StateFlow<List<LinkInfo>> get() = delegate.links

    override suspend fun start(target: SrtlaTarget): Int = mutex.withLock { delegate.start(target) }

    override suspend fun stop(): Unit = mutex.withLock { delegate.stop() }
}
