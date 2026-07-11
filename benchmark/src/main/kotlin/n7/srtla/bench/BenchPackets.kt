package n7.srtla.bench

import n7.srtla.protocol.PacketType
import n7.srtla.scheduler.SchedulerActionSink
import n7.srtla.scheduler.SchedulerEvent
import n7.srtla.scheduler.SrtlaScheduler
import n7.srtla.scheduler.Transport

/** Sink без аллокаций для бенчей: копит контрольную сумму, чтобы JIT не выкинул onEvent. */
class CountingSink : SchedulerActionSink {
    var count: Long = 0
    override fun sendOnLink(linkId: Int, data: ByteArray, length: Int): Boolean {
        count += length + linkId
        return true
    }

    override fun sendToLocal(data: ByteArray, length: Int) {
        count += length
    }
}

object BenchPackets {

    fun putU16be(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    fun putU32be(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    fun localGroupId(): ByteArray = ByteArray(PacketType.SRTLA_ID_LEN) { (it and 0x7F).toByte() }

    fun reg2Matching(localGroupId: ByteArray): ByteArray {
        val buf = ByteArray(PacketType.REG2_LEN)
        putU16be(buf, 0, PacketType.SRTLA_REG2)
        localGroupId.copyInto(buf, destinationOffset = 2)
        val half = PacketType.SRTLA_ID_LEN / 2
        for (i in half until PacketType.SRTLA_ID_LEN) buf[2 + i] = ((i + 3) and 0x7F).toByte()
        return buf
    }

    fun reg3(): ByteArray {
        val buf = ByteArray(PacketType.REG3_LEN)
        putU16be(buf, 0, PacketType.SRTLA_REG3)
        return buf
    }

    fun srtData(seqnum: Int, size: Int): ByteArray {
        val buf = ByteArray(size)
        putU32be(buf, 0, seqnum)
        return buf
    }

    fun srtAck(lastAck: Int): ByteArray {
        val buf = ByteArray(20)
        putU16be(buf, 0, PacketType.SRT_ACK)
        putU32be(buf, 16, lastAck)
        return buf
    }

    fun srtNakSingle(vararg seqnums: Int): ByteArray {
        val buf = ByteArray(16 + seqnums.size * 4)
        putU16be(buf, 0, PacketType.SRT_NAK)
        seqnums.forEachIndexed { i, sn -> putU32be(buf, 16 + i * 4, sn) }
        return buf
    }

    fun srtNakRange(start: Int, end: Int): ByteArray {
        val buf = ByteArray(16 + 8)
        putU16be(buf, 0, PacketType.SRT_NAK)
        putU32be(buf, 16, start or (1 shl 31))
        putU32be(buf, 20, end)
        return buf
    }

    fun srtlaAck(vararg seqnums: Int): ByteArray {
        val buf = ByteArray(4 + seqnums.size * 4)
        putU16be(buf, 0, PacketType.SRTLA_ACK)
        seqnums.forEachIndexed { i, sn -> putU32be(buf, 4 + i * 4, sn) }
        return buf
    }

    fun establishedScheduler(nLinks: Int, nowNanos: Long): SrtlaScheduler {
        val gid = localGroupId()
        val scheduler = SrtlaScheduler(gid)
        for (i in 1..nLinks) scheduler.onEvent(SchedulerEvent.LinkUp(i, Transport.CELLULAR), nowNanos)
        scheduler.onEvent(SchedulerEvent.LinkPacket(1, reg2Matching(gid), PacketType.REG2_LEN), nowNanos)
        for (i in 1..nLinks) scheduler.onEvent(SchedulerEvent.LinkPacket(i, reg3(), PacketType.REG3_LEN), nowNanos)
        return scheduler
    }
}
