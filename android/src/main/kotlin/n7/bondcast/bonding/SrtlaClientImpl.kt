package n7.bondcast.bonding

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import n7.bondcast.bonding.io.LinkIoLoop
import n7.bondcast.bonding.net.networkProvider
import n7.srtla.protocol.GroupId
import n7.srtla.scheduler.SrtlaScheduler

internal class SrtlaClientImpl(private val context: Context) : SrtlaClient {

    private var loop: LinkIoLoop? = null

    @Volatile
    private var startEpoch = 0

    private val _links = MutableStateFlow<List<LinkInfo>>(emptyList())
    override val links: StateFlow<List<LinkInfo>> = _links.asStateFlow()

    override suspend fun start(target: SrtlaTarget): Int = withContext(Dispatchers.IO) {
        loop?.let { runCatching { it.stop() } }
        loop = null
        val epoch = ++startEpoch
        val scheduler = SrtlaScheduler(GroupId.random())
        val newLoop = LinkIoLoop(
            target = target,
            scheduler = scheduler,
            provider = networkProvider(context),
            onLinksSnapshot = { snapshot -> if (epoch == startEpoch) _links.value = snapshot },
        )
        val port = newLoop.start()
        loop = newLoop
        port
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        startEpoch++
        loop?.stop()
        loop = null
        _links.value = emptyList()
    }
}
