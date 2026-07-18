package n7.bondcast.bonding.io

import android.net.Network
import android.util.Log
import n7.bondcast.bonding.LinkInfo
import n7.bondcast.bonding.SrtlaTarget
import n7.bondcast.bonding.net.NetworkProvider
import n7.srtla.protocol.PacketType
import n7.srtla.protocol.SrtInspector
import n7.srtla.scheduler.RegState
import n7.srtla.scheduler.SchedulerActionSink
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
    private val onLinksSnapshot: (List<LinkInfo>) -> Unit = {},
) : SchedulerActionSink {
    private val selector: Selector = Selector.open()
    private val local: DatagramChannel = DatagramChannel.open()
    private val links = LinkedHashMap<Int, BondingLink>()
    private val networkToId = HashMap<Network, Int>()
    private val pendingEvents = ConcurrentLinkedQueue<NetworkEvent>()
    private val closed = AtomicBoolean(false)
    private val scratch = ByteArray(PacketType.MTU + 512)

    // единый direct-буфер отправки: sink пишет прямо в сокет без ByteBuffer.wrap на каждый пакет.
    // io-поток однопоточный, put/flip/write синхронны — можно переиспользовать между действиями
    private val sendBuf = ByteBuffer.allocateDirect(PacketType.MTU + 512)
    private var nextLinkId = 1
    private var thread: Thread? = null
    private var lastSnapshotNanos = 0L
    private var lastSummaryNanos = 0L

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
        // ждём завершения io-потока (закрытие Selector/каналов идёт в нём) — иначе при stop→start
        // старый Selector и local-канал кратко живут рядом с новым. stop зовётся на Dispatchers.IO
        runCatching { thread?.join(JOIN_TIMEOUT_MS) }
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
                scheduler.onEvent(SchedulerEvent.Tick(now), now, this)
                lastTickNanos = now
                if (now - lastSnapshotNanos >= SNAPSHOT_NANOS) {
                    publishSnapshot(now)
                    lastSnapshotNanos = now
                }
                if (now - lastSummaryNanos >= SUMMARY_NANOS) {
                    logSummary()
                    lastSummaryNanos = now
                }
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
            val address = runCatching { network.getByName(target.host) }.getOrNull() ?: remote.address
            val dest = InetSocketAddress(address, remote.port)
            channel.connect(dest)
            val key = channel.register(selector, SelectionKey.OP_READ)
            val id = nextLinkId++
            val link = BondingLink(id, network, transport, channel, key)
            key.attach(link)
            links[id] = link
            networkToId[network] = id
            Log.i(TAG, "link #$id up: $transport → $dest")
            scheduler.onEvent(SchedulerEvent.LinkUp(id, transport), now, this)
            publishSnapshot(now)
        } catch (t: Throwable) {
            Log.w(TAG, "addLink failed for $transport", t)
        }
    }

    private fun removeLink(network: Network, now: Long) {
        val id = networkToId[network] ?: return
        val link = links.remove(id)
        networkToId.remove(network)
        scheduler.onEvent(SchedulerEvent.LinkDown(id), now, this)
        if (link != null) {
            runCatching { link.key.cancel() }
            runCatching { link.channel.close() }
        }
        publishSnapshot(now)
    }

    private fun readLocal(buf: ByteBuffer, now: Long) {
        try {
            buf.clear()
            val src = local.receive(buf) ?: return
            if (callerAddress == null) Log.i(TAG, "локальный SRT-кейлер подключился: $src")
            callerAddress = src
            buf.flip()
            val n = buf.remaining()
            buf.get(scratch, 0, n)
            scheduler.onEvent(SchedulerEvent.LocalSrtPacket(scratch, n), now, this)
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
            val count = buf.remaining()
            buf.get(scratch, 0, count)
            logRegPacket("←", link, scratch, count)
            scheduler.onEvent(SchedulerEvent.LinkPacket(link.id, scratch, count), now, this)
        } catch (t: Throwable) {
            if (now - link.lastReadErrorNanos >= ERROR_LOG_THROTTLE_NANOS) {
                link.lastReadErrorNanos = now
                Log.w(TAG, "readLink failed on #${link.id} ${link.transport}: $t")
            }
        }
    }

    override fun sendOnLink(linkId: Int, data: ByteArray, length: Int): Boolean {
        val link = links[linkId] ?: return false
        return try {
            logRegPacket("→", link, data, length)
            sendBuf.clear()
            sendBuf.put(data, 0, length)
            sendBuf.flip()
            val sent = link.channel.write(sendBuf)
            if (sent > 0) {
                link.bytesSent += sent
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            Log.w(TAG, "sendOnLink failed", t)
            false
        }
    }

    override fun sendToLocal(data: ByteArray, length: Int) {
        val caller = callerAddress ?: return
        try {
            sendBuf.clear()
            sendBuf.put(data, 0, length)
            sendBuf.flip()
            local.send(sendBuf, caller)
        } catch (t: Throwable) {
            Log.w(TAG, "sendToLocal failed", t)
        }
    }

    /** Пересчитывает скорость каждого линка и публикует снимок для UI. Только io-поток. */
    private fun publishSnapshot(now: Long) {
        val infos = links.values.map { link ->
            val elapsed = now - link.lastRateNanos
            if (link.lastRateNanos != 0L && elapsed > 0) {
                link.sendRateKbps = ((link.bytesSent - link.lastRateBytes) * 8_000_000L / elapsed).toInt()
            }
            link.lastRateBytes = link.bytesSent
            link.lastRateNanos = now
            val reg = scheduler.regOf(link.id) ?: RegState.NONE
            if (reg != link.lastLoggedReg) {
                Log.i(TAG, "link #${link.id} ${link.transport}: ${link.lastLoggedReg} → $reg")
                link.lastLoggedReg = reg
            }
            LinkInfo(
                id = link.id,
                transport = link.transport,
                reg = reg,
                sendRateKbps = link.sendRateKbps,
                inFlight = scheduler.inFlightOf(link.id),
                window = scheduler.windowOf(link.id),
            )
        }
        onLinksSnapshot(infos)
    }

    /** Однострочная сводка для диагностики по логам. */
    private fun logSummary() {
        val linksSummary = links.values.joinToString(" | ") { link ->
            "#${link.id} ${link.transport} ${scheduler.regOf(link.id)} ${link.sendRateKbps}kbps"
        }.ifEmpty { "нет линков" }
        Log.i(
            TAG,
            "сводка: target=$remote group=${if (scheduler.isEstablished) "est" else "PENDING"} " +
                "caller=${if (callerAddress != null) "да" else "НЕТ"} :: $linksSummary",
        )
    }

    /** Логирует только регистрационные srtla-пакеты (REG1..REG_NAK), не данные и не keepalive. */
    private fun logRegPacket(direction: String, link: BondingLink, data: ByteArray, len: Int) {
        val name = when (SrtInspector.srtType(data, len)) {
            PacketType.SRTLA_REG1 -> "REG1"
            PacketType.SRTLA_REG2 -> "REG2"
            PacketType.SRTLA_REG3 -> "REG3"
            PacketType.SRTLA_REG_ERR -> "REG_ERR"
            PacketType.SRTLA_REG_NGP -> "REG_NGP"
            PacketType.SRTLA_REG_NAK -> "REG_NAK"
            else -> return
        }
        Log.i(TAG, "$direction link #${link.id} ${link.transport}: $name")
    }

    private fun closeQuietly() {
        if (!closed.compareAndSet(false, true)) return
        for (link in links.values) runCatching { link.channel.close() }
        links.clear()
        networkToId.clear()
        runCatching { local.close() }
        runCatching { selector.close() }
        onLinksSnapshot(emptyList())
    }

    private companion object {
        const val TAG = "SrtlaIo"
        const val TICK_MS = 200L
        const val JOIN_TIMEOUT_MS = 500L
        const val TICK_NANOS = 200_000_000L
        const val SNAPSHOT_NANOS = 1_000_000_000L
        const val SUMMARY_NANOS = 5_000_000_000L
        const val ERROR_LOG_THROTTLE_NANOS = 2_000_000_000L
    }
}
