package n7.bondcast.bonding

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import n7.bondcast.bonding.io.LinkIoLoop
import n7.srtla.protocol.GroupId
import n7.srtla.scheduler.SrtlaScheduler

internal class SrtlaClientImpl : SrtlaClient {

    private var loop: LinkIoLoop? = null

    override suspend fun start(target: SrtlaTarget): Int = withContext(Dispatchers.IO) {
        val scheduler = SrtlaScheduler(GroupId.random())
        val newLoop = LinkIoLoop(target, scheduler)
        val port = newLoop.start()
        loop = newLoop
        port
    }

    override suspend fun stop(): Unit = withContext(Dispatchers.IO) {
        loop?.stop()
        loop = null
    }
}
