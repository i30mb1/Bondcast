package n7.bondcast.bonding.io

import android.util.Log
import n7.bondcast.bonding.SrtlaTarget
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

internal class LinkIoLoop(
    private val target: SrtlaTarget,
    private val scheduler: SrtlaScheduler,
    private val clock: () -> Long = { System.nanoTime() },
) {
    private val selector: Selector = Selector.open()
    private val local: DatagramChannel = DatagramChannel.open()
    private val link: DatagramChannel = DatagramChannel.open()
    private var thread: Thread? = null

    @Volatile
    private var running = false
    private var callerAddress: SocketAddress? = null

    fun start(): Int {
        local.configureBlocking(false)
        local.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
        local.register(selector, SelectionKey.OP_READ)

        link.configureBlocking(false)
        link.connect(InetSocketAddress(target.host, target.port))
        link.register(selector, SelectionKey.OP_READ)

        dispatch(scheduler.onEvent(SchedulerEvent.LinkUp(LINK_ID, Transport.UNKNOWN), clock()))

        running = true
        thread = Thread({ loop() }, "srtla-io").apply {
            isDaemon = true
            start()
        }
        return (local.localAddress as InetSocketAddress).port
    }

    fun stop() {
        running = false
        selector.wakeup()
        thread?.join(1_000)
        closeQuietly()
    }

    private fun loop() {
        val buf = ByteBuffer.allocateDirect(PacketType.MTU + 512)
        var lastTickNanos = clock()
        while (running) {
            selector.select(TICK_MS)
            if (!running) break
            val now = clock()
            val keys = selector.selectedKeys()
            val iterator = keys.iterator()
            while (iterator.hasNext()) {
                val key = iterator.next()
                iterator.remove()
                if (!key.isValid || !key.isReadable) continue
                if (key.channel() === local) readLocal(buf, now) else readLink(buf, now)
            }
            if (now - lastTickNanos >= TICK_NANOS) {
                dispatch(scheduler.onEvent(SchedulerEvent.Tick(now), now))
                lastTickNanos = now
            }
        }
        closeQuietly()
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

    private fun readLink(buf: ByteBuffer, now: Long) {
        try {
            buf.clear()
            val n = link.read(buf)
            if (n <= 0) return
            buf.flip()
            val data = ByteArray(buf.remaining())
            buf.get(data)
            dispatch(scheduler.onEvent(SchedulerEvent.LinkPacket(LINK_ID, data, data.size), now))
        } catch (t: Throwable) {
            Log.w(TAG, "readLink failed", t)
        }
    }

    private fun dispatch(actions: List<SchedulerAction>) {
        for (action in actions) {
            try {
                when (action) {
                    is SchedulerAction.SendOnLink -> link.write(ByteBuffer.wrap(action.data))
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
        runCatching { local.close() }
        runCatching { link.close() }
        runCatching { selector.close() }
    }

    private companion object {
        const val TAG = "SrtlaIo"
        const val LINK_ID = 1
        const val TICK_MS = 200L
        const val TICK_NANOS = 200_000_000L
    }
}
