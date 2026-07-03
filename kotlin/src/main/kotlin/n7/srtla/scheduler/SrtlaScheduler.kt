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
    private val links = LinkedHashMap<Int, LinkState>()

    public val activeLinkCount: Int get() = links.values.count { it.reg == RegState.ACTIVE }

    public fun onEvent(event: SchedulerEvent, nowNanos: Long): List<SchedulerAction> = when (event) {
        is SchedulerEvent.LinkUp -> onLinkUp(event, nowNanos)
        is SchedulerEvent.LinkDown -> onLinkDown(event)
        is SchedulerEvent.LocalSrtPacket -> onLocalSrtPacket(event, nowNanos)
        is SchedulerEvent.LinkPacket -> onLinkPacket(event, nowNanos)
        is SchedulerEvent.Tick -> onTick(event.nowNanos)
    }

    private fun onLinkUp(event: SchedulerEvent.LinkUp, nowNanos: Long): List<SchedulerAction> {
        val link = LinkState(event.linkId, event.transport, event.priority, params)
        links[event.linkId] = link
        return when {
            !groupEstablished && pendingReg2 == null -> {
                pendingReg2 = link
                link.reg = RegState.WAIT_REG2
                link.lastSentNanos = nowNanos
                listOf(SchedulerAction.SendOnLink(link.id, SrtlaCodec.reg1(groupId)))
            }
            groupEstablished -> {
                link.reg = RegState.WAIT_REG3
                link.lastSentNanos = nowNanos
                listOf(SchedulerAction.SendOnLink(link.id, SrtlaCodec.reg2(groupId)))
            }
            else -> emptyList()
        }
    }

    private fun onLinkDown(event: SchedulerEvent.LinkDown): List<SchedulerAction> {
        val link = links.remove(event.linkId) ?: return emptyList()
        if (pendingReg2 === link) pendingReg2 = null
        return emptyList()
    }

    private fun onLocalSrtPacket(event: SchedulerEvent.LocalSrtPacket, nowNanos: Long): List<SchedulerAction> {
        val link = selectConn(nowNanos) ?: return emptyList()
        val payload = exact(event.data, event.length)
        val sn = SrtInspector.srtDataSeqnum(event.data, event.length)
        if (sn >= 0) link.logPacket(sn)
        return listOf(SchedulerAction.SendOnLink(link.id, payload))
    }

    private fun onLinkPacket(event: SchedulerEvent.LinkPacket, nowNanos: Long): List<SchedulerAction> {
        val link = links[event.linkId] ?: return emptyList()
        val data = event.data
        val len = event.length
        val type = SrtInspector.srtType(data, len)

        if (type == PacketType.SRTLA_REG_NGP) {
            val noActive = links.values.none { !it.timedOut(nowNanos) }
            if (noActive && pendingReg2 == null) {
                pendingReg2 = link
                link.reg = RegState.WAIT_REG2
                link.lastSentNanos = nowNanos
                return listOf(SchedulerAction.SendOnLink(link.id, SrtlaCodec.reg1(groupId)))
            }
            return emptyList()
        }

        if (type == PacketType.SRTLA_REG2) {
            if (pendingReg2 !== link) return emptyList()
            val received = SrtlaCodec.reg2Id(data, len) ?: return emptyList()
            if (!GroupId.firstHalfMatches(received, groupId)) return emptyList()
            groupId = received
            groupEstablished = true
            pendingReg2 = null
            val actions = ArrayList<SchedulerAction>(links.size)
            for (l in links.values) {
                l.reg = RegState.WAIT_REG3
                l.lastSentNanos = nowNanos
                actions.add(SchedulerAction.SendOnLink(l.id, SrtlaCodec.reg2(groupId)))
            }
            return actions
        }

        link.lastRcvdNanos = nowNanos

        return when (type) {
            PacketType.SRT_ACK -> {
                registerSrtAck(SrtInspector.srtAckLastAck(data, len))
                listOf(SchedulerAction.SendToLocal(exact(data, len)))
            }
            PacketType.SRT_NAK -> {
                for (lost in SrtInspector.nakLostSeqnums(data, len)) registerNak(lost)
                listOf(SchedulerAction.SendToLocal(exact(data, len)))
            }
            PacketType.SRTLA_ACK -> {
                for (ack in SrtInspector.srtlaAckSeqnums(data, len)) registerSrtlaAck(ack)
                emptyList()
            }
            PacketType.SRTLA_KEEPALIVE -> emptyList()
            PacketType.SRTLA_REG3 -> {
                link.reg = RegState.ACTIVE
                emptyList()
            }
            PacketType.SRTLA_REG_ERR, PacketType.SRTLA_REG_NAK, PacketType.SRTLA_REG1 -> emptyList()
            else -> listOf(SchedulerAction.SendToLocal(exact(data, len)))
        }
    }

    private fun onTick(nowNanos: Long): List<SchedulerAction> {
        val actions = ArrayList<SchedulerAction>()
        val pending = pendingReg2
        if (pending != null && pending.lastSentNanos + params.connTimeoutNanos < nowNanos) {
            pendingReg2 = null
        }
        for (link in links.values) {
            if (link.timedOut(nowNanos)) {
                if (link.lastRcvdNanos > 0L) link.reset()
                when {
                    !groupEstablished && pendingReg2 == null -> {
                        pendingReg2 = link
                        link.reg = RegState.WAIT_REG2
                        link.lastSentNanos = nowNanos
                        actions.add(SchedulerAction.SendOnLink(link.id, SrtlaCodec.reg1(groupId)))
                    }
                    pendingReg2 == null -> {
                        link.reg = RegState.WAIT_REG3
                        link.lastSentNanos = nowNanos
                        actions.add(SchedulerAction.SendOnLink(link.id, SrtlaCodec.reg2(groupId)))
                    }
                    pendingReg2 === link -> {
                        link.lastSentNanos = nowNanos
                        actions.add(SchedulerAction.SendOnLink(link.id, SrtlaCodec.reg1(groupId)))
                    }
                }
            } else if (link.lastSentNanos + params.idleNanos < nowNanos) {
                link.lastSentNanos = nowNanos
                actions.add(SchedulerAction.SendOnLink(link.id, SrtlaCodec.keepalive()))
            }
        }
        return actions
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
            var count = 0
            var i = prevIdx(link.pktIdx)
            while (i != link.pktIdx) {
                val value = link.pktLog[i]
                if (value < ack) link.pktLog[i] = -1 else count++
                i = prevIdx(i)
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

    private fun prevIdx(idx: Int): Int = (idx - 1 + params.pktLogSize) % params.pktLogSize

    private fun exact(data: ByteArray, len: Int): ByteArray = if (len == data.size) data else data.copyOf(len)

    public fun setLinkEnabled(linkId: Int, enabled: Boolean) {
        links[linkId]?.enabled = enabled
    }

    public fun setLinkPriority(linkId: Int, priority: Int) {
        links[linkId]?.priority = priority
    }

    public fun windowOf(linkId: Int): Int = links[linkId]?.window ?: -1

    public fun inFlightOf(linkId: Int): Int = links[linkId]?.inFlight ?: -1

    public fun isActive(linkId: Int): Boolean = links[linkId]?.reg == RegState.ACTIVE

    internal fun linkState(linkId: Int): LinkState? = links[linkId]
}
