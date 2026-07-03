package n7.bondcast.bonding.io

import android.net.Network
import android.util.Log
import n7.bondcast.bonding.SrtlaTarget
import n7.bondcast.bonding.net.NetworkProvider
import n7.srtla.protocol.PacketType
import n7.srtla.scheduler.SchedulerAction
import n7.srtla.scheduler.SchedulerEvent
import n7.srtla.scheduler.SrtlaScheduler
import n7.srtla.scheduler.Transport
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

private sealed interface NetworkEvent {
    class Available(val network: Network, val transport: Transport) : NetworkEvent
    class Lost(val network: Network) : NetworkEvent
}

internal class LinkIoLoop(
    private val target: SrtlaTarget,
    private val scheduler: SrtlaScheduler,
    private val provider: NetworkProvider,
    private val clock: () -> Long = { System.nanoTime() },
) {
    private val selector: Selector = Selector.open()
    private val local: DatagramChannel = DatagramChannel.open()
    private val links = LinkedHashMap<Int, BondingLink>()
    private val networkToId = HashMap<Network, Int>()
    private val pendingEvents = ConcurrentLinkedQueue<NetworkEvent>()
    private val closed = AtomicBoolean(false)
    private var nextLinkId = 1
    private var thread: Thread? = null

    @Volatile
    private var running = false
    private var callerAddress: SocketAddress? = null
    private lateinit var remote: InetSocketAddress

    fun start(): Int {
        try {
            remote = InetSocketAddress(InetAddress.getByName(target.host), target.port)
            local.configureBlocking(false)
            local.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
            local.register(selector, SelectionKey.OP_READ)

            running = true
            provider.start(
                onAvailable = { network, transport ->
                    pendingEvents.add(NetworkEvent.Available(network, transport))
                    runCatching { selector.wakeup() }
                },
                onLost = { network ->
                    pendingEvents.add(NetworkEvent.Lost(network))
                    runCatching { selector.wakeup() }
                },
            )

            thread = Thread({ loop() }, "srtla-io").apply {
                isDaemon = true
                start()
            }
            return (local.localAddress as InetSocketAddress).port
        } catch (t: Throwable) {
            running = false
            runCatching { provider.stop() }
            closeQuietly()
            throw t
        }
    }

    fun stop() {
        running = false
        runCatching { provider.stop() }
        runCatching { selector.wakeup() }
        thread?.join(2_000)
    }

    private fun loop() {
        val buf = ByteBuffer.allocateDirect(PacketType.MTU + 512)
        var lastTickNanos = clock()
        while (running) {
            selector.select(TICK_MS)
            if (!running) break
            val now = clock()
            drainNetworkEvents(now)
            val keys = selector.selectedKeys()
            val iterator = keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                iterator.remove()
                if (!key.isValid || !key.isReadable) continue
                if (key.channel() === local) readLocal(buf, now) else readLink(key, buf, now)
            }
            if (now - lastTickNanos >= TICK_NANOS) {
                dispatch(scheduler.onEvent(SchedulerEvent.Tick(now), now))
                lastTickNanos = now
            }
        }
        closeQuietly()
    }

    private fun drainNetworkEvents(now: Long) {
        while (true) {
            val event = pendingEvents.poll() ?: break
            when (event) {
                is NetworkEvent.Available -> addLink(event.network, event.transport, now)
                is NetworkEvent.Lost -> removeLink(event.network, now)
            }
        }
    }

    private fun addLink(network: Network, transport: Transport, now: Long) {
        if (networkToId.containsKey(network)) return
        try {
            val channel = DatagramChannel.open()
            channel.configureBlocking(false)
            network.bindSocket(channel.socket())
            channel.connect(remote)
            val key = channel.register(selector, SelectionKey.OP_READ)
            val id = nextLinkId++
            val link = BondingLink(id, network, transport, channel, key)
            key.attach(link)
            links[id] = link
            networkToId[network] = id
            dispatch(scheduler.onEvent(SchedulerEvent.LinkUp(id, transport), now))
        } catch (t: Throwable) {
            Log.w(TAG, "addLink failed for $transport", t)
        }
    }

    private fun removeLink(network: Network, now: Long) {
        val id = networkToId[network] ?: return
        val link = links.remove(id)
        networkToId.remove(network)
        scheduler.onEvent(SchedulerEvent.LinkDown(id), now)
        if (link != null) {
            runCatching { link.key.cancel() }
            runCatching { link.channel.close() }
        }
    }

    private fun readLocal(buf: ByteBuffer, now: Long) {
        try {
            buf.clear()
            val src = local.receive(buf) ?: return
            callerAddress = src
            buf.flip()
            val data = ByteArray(buf.remaining())
            buf.get(data)
            dispatch(scheduler.onEvent(SchedulerEvent.LocalSrtPacket(data, data.size), now))
        } catch (t: Throwable) {
            Log.w(TAG, "readLocal failed", t)
        }
    }

    private fun readLink(key: SelectionKey, buf: ByteBuffer, now: Long) {
        val link = key.attachment() as? BondingLink ?: return
        try {
            buf.clear()
            val n = link.channel.read(buf)
            if (n <= 0) return
            buf.flip()
            val data = ByteArray(buf.remaining())
            buf.get(data)
            dispatch(scheduler.onEvent(SchedulerEvent.LinkPacket(link.id, data, data.size), now))
        } catch (t: Throwable) {
            Log.w(TAG, "readLink failed", t)
        }
    }

    private fun dispatch(actions: List<SchedulerAction>) {
        for (action in actions) {
            try {
                when (action) {
                    is SchedulerAction.SendOnLink -> {
                        val link = links[action.linkId] ?: continue
                        link.channel.write(ByteBuffer.wrap(action.data))
                    }
                    is SchedulerAction.SendToLocal -> {
                        val caller = callerAddress ?: continue
                        local.send(ByteBuffer.wrap(action.data), caller)
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "dispatch failed", t)
            }
        }
    }

    private fun closeQuietly() {
        if (!closed.compareAndSet(false, true)) return
        for (link in links.values) runCatching { link.channel.close() }
        links.clear()
        networkToId.clear()
        runCatching { local.close() }
        runCatching { selector.close() }
    }

    private companion object {
        const val TAG = "SrtlaIo"
        const val TICK_MS = 200L
        const val TICK_NANOS = 200_000_000L
    }
}
