package n7.srtla.scheduler

import n7.srtla.protocol.GroupId
import n7.srtla.protocol.PacketType
import n7.srtla.protocol.SrtInspector
import n7.srtla.protocol.SrtlaCodec
import kotlin.math.max
import kotlin.math.min

public class SrtlaScheduler(
    localGroupId: ByteArray,
    private val params: WindowParams = WindowParams.DEFAULT,
) {
    init {
        require(localGroupId.size == PacketType.SRTLA_ID_LEN) { "group id must be ${PacketType.SRTLA_ID_LEN} bytes" }
    }

    private var groupId: ByteArray = localGroupId.copyOf()
    private var groupEstablished: Boolean = false
    private var pendingReg2: LinkState? = null
    private var pendingReg2DeadlineNanos: Long = 0L
    private var lastReg1LinkId: Int = -1
    private val links = LinkedHashMap<Int, LinkState>()
    private val pktLogSize: Int = params.pktLogSize

    public val activeLinkCount: Int get() = links.values.count { it.reg == RegState.ACTIVE }

    /** Установлена ли srtla-группа (получен REG2 от сервера). */
    public val isEstablished: Boolean get() = groupEstablished

    /** Горячий путь: пишет действия прямо в sink, без List и объектов SchedulerAction на пакет. */
    public fun onEvent(event: SchedulerEvent, nowNanos: Long, sink: SchedulerActionSink) {
        when (event) {
            is SchedulerEvent.LinkUp -> onLinkUp(event, nowNanos, sink)
            is SchedulerEvent.LinkDown -> onLinkDown(event)
            is SchedulerEvent.LocalSrtPacket -> onLocalSrtPacket(event, nowNanos, sink)
            is SchedulerEvent.LinkPacket -> onLinkPacket(event, nowNanos, sink)
            is SchedulerEvent.Tick -> onTick(event.nowNanos, sink)
        }
    }

    /** Совместимость с тестами/бенчами: собирает действия в свежий список. Не горячий путь. */
    public fun onEvent(event: SchedulerEvent, nowNanos: Long): List<SchedulerAction> {
        val out = ArrayList<SchedulerAction>(2)
        onEvent(
            event,
            nowNanos,
            object : SchedulerActionSink {
                override fun sendOnLink(linkId: Int, data: ByteArray, length: Int): Boolean {
                    out.add(SchedulerAction.SendOnLink(linkId, data, length))
                    return true
                }

                override fun sendToLocal(data: ByteArray, length: Int) {
                    out.add(SchedulerAction.SendToLocal(data, length))
                }
            },
        )
        return out
    }

    private fun onLinkUp(event: SchedulerEvent.LinkUp, nowNanos: Long, sink: SchedulerActionSink) {
        val link = LinkState(event.linkId, event.transport, event.priority, params)
        links[event.linkId] = link
        if (groupEstablished) {
            link.reg = RegState.WAIT_REG3
            link.lastSentNanos = nowNanos
            sink.sendOnLink(link.id, SrtlaCodec.reg2(groupId))
        } else {
            selectPendingReg2(nowNanos, sink)
        }
    }

    /**
     * Выбирает следующий линк для регистрации группы (REG1) по кругу от прошлого кандидата —
     * мёртвый линк не должен монополизировать регистрацию.
     */
    private fun selectPendingReg2(nowNanos: Long, sink: SchedulerActionSink) {
        if (groupEstablished || pendingReg2 != null || links.isEmpty()) return
        val ordered = links.values.toList()
        val lastIdx = ordered.indexOfFirst { it.id == lastReg1LinkId }
        val candidate = ordered[(lastIdx + 1) % ordered.size]
        pendingReg2 = candidate
        pendingReg2DeadlineNanos = nowNanos + params.connTimeoutNanos
        lastReg1LinkId = candidate.id
        candidate.reg = RegState.WAIT_REG2
        candidate.lastSentNanos = nowNanos
        sink.sendOnLink(candidate.id, SrtlaCodec.reg1(groupId))
    }

    private fun onLinkDown(event: SchedulerEvent.LinkDown) {
        val link = links.remove(event.linkId) ?: return
        if (pendingReg2 === link) pendingReg2 = null
    }

    private fun onLocalSrtPacket(event: SchedulerEvent.LocalSrtPacket, nowNanos: Long, sink: SchedulerActionSink) {
        val link = selectConn(nowNanos) ?: return
        if (sink.sendOnLink(link.id, event.data, event.length)) {
            val sn = SrtInspector.srtDataSeqnum(event.data, event.length)
            if (sn >= 0) link.logPacket(sn)
        }
    }

    private fun onLinkPacket(event: SchedulerEvent.LinkPacket, nowNanos: Long, sink: SchedulerActionSink) {
        val link = links[event.linkId] ?: return
        val data = event.data
        val len = event.length
        val type = SrtInspector.srtType(data, len)

        if (type == PacketType.SRTLA_REG_NGP) {
            val noActive = links.values.none { !it.timedOut(nowNanos) }
            if (noActive && pendingReg2 == null) {
                // сервер группу не нашёл, а линк живой (пакет только что пришёл) — регистрируемся с него
                pendingReg2 = link
                pendingReg2DeadlineNanos = nowNanos + params.connTimeoutNanos
                lastReg1LinkId = link.id
                link.reg = RegState.WAIT_REG2
                link.lastSentNanos = nowNanos
                sink.sendOnLink(link.id, SrtlaCodec.reg1(groupId))
            }
            return
        }

        if (type == PacketType.SRTLA_REG2) {
            if (pendingReg2 !== link) return
            val received = SrtlaCodec.reg2Id(data, len) ?: return
            if (!GroupId.firstHalfMatches(received, groupId)) return
            groupId = received
            groupEstablished = true
            pendingReg2 = null
            for (l in links.values) {
                l.reg = RegState.WAIT_REG3
                l.lastSentNanos = nowNanos
                sink.sendOnLink(l.id, SrtlaCodec.reg2(groupId))
            }
            return
        }

        link.lastRcvdNanos = nowNanos

        when (type) {
            PacketType.SRT_ACK -> {
                registerSrtAck(SrtInspector.srtAckLastAck(data, len))
                sink.sendToLocal(data, len)
            }

            PacketType.SRT_NAK -> {
                SrtInspector.nakLostSeqnums(data, len) { registerNak(it) }
                sink.sendToLocal(data, len)
            }

            PacketType.SRTLA_ACK -> {
                SrtInspector.srtlaAckSeqnums(data, len) { registerSrtlaAck(it) }
            }

            PacketType.SRTLA_KEEPALIVE -> Unit

            PacketType.SRTLA_REG3 -> {
                link.reg = RegState.ACTIVE
            }

            PacketType.SRTLA_REG_ERR, PacketType.SRTLA_REG_NAK, PacketType.SRTLA_REG1 -> Unit

            else -> sink.sendToLocal(data, len)
        }
    }

    private fun onTick(nowNanos: Long, sink: SchedulerActionSink) {
        // дедлайн фиксируется в момент выбора кандидата и НЕ сдвигается ресендами:
        // иначе мёртвый линк держит регистрацию вечно
        if (pendingReg2 != null && pendingReg2DeadlineNanos < nowNanos) {
            pendingReg2 = null
        }
        selectPendingReg2(nowNanos, sink)
        for (link in links.values) {
            if (link.timedOut(nowNanos)) {
                if (link.lastRcvdNanos > 0L) link.reset()
                // после reset() линк остаётся timedOut до первого входящего пакета,
                // поэтому ресенд REG2 обязан троттлиться — иначе шторм на каждый тик
                if (groupEstablished && link.lastSentNanos + params.reg2ResendNanos < nowNanos) {
                    link.reg = RegState.WAIT_REG3
                    link.lastSentNanos = nowNanos
                    sink.sendOnLink(link.id, SrtlaCodec.reg2(groupId))
                }
                // при неустановленной группе REG1 шлёт только выбранный кандидат (ротация выше)
            } else if (link.lastSentNanos + params.idleNanos < nowNanos) {
                link.lastSentNanos = nowNanos
                sink.sendOnLink(link.id, SrtlaCodec.keepalive())
            }
        }
    }

    private fun selectConn(nowNanos: Long): LinkState? {
        var best: LinkState? = null
        var maxScore = -1
        for (link in links.values) {
            if (!link.enabled) continue
            if (link.timedOut(nowNanos)) continue
            val score = link.window / (link.inFlight + 1)
            if (score > maxScore) {
                best = link
                maxScore = score
            }
        }
        if (best != null) best.lastSentNanos = nowNanos
        return best
    }

    private fun registerSrtAck(ack: Int) {
        if (ack < 0) return
        for (link in links.values) {
            val log = link.pktLog
            val skip = link.pktIdx
            var count = 0
            var idx = 0
            while (idx < pktLogSize) {
                if (idx != skip) {
                    val value = log[idx]
                    if (value < 0 || seqAcked(value, ack)) log[idx] = -1 else count++
                }
                idx++
            }
            link.inFlight = count
        }
    }

    private fun registerNak(packet: Int) {
        for (link in links.values) {
            var i = prevIdx(link.pktIdx)
            while (i != link.pktIdx) {
                if (link.pktLog[i] == packet) {
                    link.pktLog[i] = -1
                    link.window -= params.windowDecr
                    link.window = max(link.window, params.windowMin * params.windowMult)
                    return
                }
                i = prevIdx(i)
            }
        }
    }

    private fun registerSrtlaAck(ack: Int) {
        var found = false
        for (link in links.values) {
            if (!found) {
                var i = prevIdx(link.pktIdx)
                while (i != link.pktIdx) {
                    if (link.pktLog[i] == ack) {
                        found = true
                        if (link.inFlight > 0) link.inFlight--
                        link.pktLog[i] = -1
                        if (link.inFlight * params.windowMult > link.window) {
                            link.window += params.windowIncr - 1
                        }
                        break
                    }
                    i = prevIdx(i)
                }
            }
            if (link.lastRcvdNanos != 0L) {
                link.window += 1
                link.window = min(link.window, params.windowMax * params.windowMult)
            }
        }
    }

    private fun prevIdx(idx: Int): Int {
        val i = idx - 1
        return if (i < 0) pktLogSize - 1 else i
    }

    private fun seqAcked(value: Int, ack: Int): Boolean = ((ack - value) and 0x7FFFFFFF) in 1..0x3FFFFFFF

    public fun setLinkEnabled(linkId: Int, enabled: Boolean) {
        links[linkId]?.enabled = enabled
    }

    public fun setLinkPriority(linkId: Int, priority: Int) {
        links[linkId]?.priority = priority
    }

    public fun windowOf(linkId: Int): Int = links[linkId]?.window ?: -1

    public fun inFlightOf(linkId: Int): Int = links[linkId]?.inFlight ?: -1

    public fun isActive(linkId: Int): Boolean = links[linkId]?.reg == RegState.ACTIVE

    public fun regOf(linkId: Int): RegState? = links[linkId]?.reg

    internal fun linkState(linkId: Int): LinkState? = links[linkId]
}
