package n7.srtla.scheduler

public enum class RegState { NONE, WAIT_REG2, WAIT_REG3, ACTIVE }

internal class LinkState(
    val id: Int,
    val transport: Transport,
    var priority: Int,
    private val params: WindowParams,
) {
    var window: Int = params.windowDef * params.windowMult
    var inFlight: Int = 0
    val pktLog: IntArray = IntArray(params.pktLogSize) { -1 }
    var pktIdx: Int = 0
    var lastRcvdNanos: Long = 0L
    var lastSentNanos: Long = 0L
    var reg: RegState = RegState.NONE
    var enabled: Boolean = true

    fun reset() {
        window = params.windowMin * params.windowMult
        inFlight = 0
        pktLog.fill(-1)
        pktIdx = 0
        lastRcvdNanos = 0L
        lastSentNanos = 0L
    }

    fun timedOut(nowNanos: Long): Boolean = lastRcvdNanos + params.connTimeoutNanos < nowNanos

    fun logPacket(seqnum: Int) {
        pktLog[pktIdx] = seqnum
        pktIdx = (pktIdx + 1) % params.pktLogSize
        inFlight++
    }
}
