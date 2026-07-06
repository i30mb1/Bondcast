package n7.srtla

import n7.srtla.protocol.PacketType

internal object TestPackets {

    fun putU16(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 1] = (v and 0xFF).toByte()
    }

    fun putU32(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    fun srtData(seqnum: Int, size: Int = 64): ByteArray {
        val buf = ByteArray(size)
        putU32(buf, 0, seqnum)
        return buf
    }

    fun srtAck(lastAck: Int): ByteArray {
        val buf = ByteArray(20)
        putU16(buf, 0, PacketType.SRT_ACK)
        putU32(buf, 16, lastAck)
        return buf
    }

    fun srtNakSingle(vararg seqnums: Int): ByteArray {
        val buf = ByteArray(16 + seqnums.size * 4)
        putU16(buf, 0, PacketType.SRT_NAK)
        seqnums.forEachIndexed { i, sn -> putU32(buf, 16 + i * 4, sn) }
        return buf
    }

    fun srtNakRange(start: Int, end: Int): ByteArray {
        val buf = ByteArray(16 + 8)
        putU16(buf, 0, PacketType.SRT_NAK)
        putU32(buf, 16, start or (1 shl 31))
        putU32(buf, 20, end)
        return buf
    }

    fun srtlaAck(vararg seqnums: Int): ByteArray {
        val buf = ByteArray(4 + seqnums.size * 4)
        putU16(buf, 0, PacketType.SRTLA_ACK)
        seqnums.forEachIndexed { i, sn -> putU32(buf, 4 + i * 4, sn) }
        return buf
    }

    fun keepalive(): ByteArray {
        val buf = ByteArray(2)
        putU16(buf, 0, PacketType.SRTLA_KEEPALIVE)
        return buf
    }

    fun reg2(id: ByteArray): ByteArray {
        val buf = ByteArray(PacketType.REG2_LEN)
        putU16(buf, 0, PacketType.SRTLA_REG2)
        id.copyInto(buf, destinationOffset = 2)
        return buf
    }

    fun reg3(): ByteArray {
        val buf = ByteArray(PacketType.REG3_LEN)
        putU16(buf, 0, PacketType.SRTLA_REG3)
        return buf
    }

    fun regNgp(): ByteArray {
        val buf = ByteArray(2)
        putU16(buf, 0, PacketType.SRTLA_REG_NGP)
        return buf
    }

    fun receiverId(senderId: ByteArray): ByteArray {
        val id = senderId.copyOf()
        val half = PacketType.SRTLA_ID_LEN / 2
        for (i in half until PacketType.SRTLA_ID_LEN) id[i] = (i and 0x7F).toByte()
        return id
    }
}
