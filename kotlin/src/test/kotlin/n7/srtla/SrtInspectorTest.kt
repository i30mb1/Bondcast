package n7.srtla

import n7.srtla.protocol.PacketType
import n7.srtla.protocol.SrtInspector
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SrtInspectorTest {

    @Test
    fun dataSeqnumForDataPacket() {
        assertEquals(1234, SrtInspector.srtDataSeqnum(TestPackets.srtData(1234), 64))
        assertEquals(0, SrtInspector.srtDataSeqnum(TestPackets.srtData(0), 64))
        assertEquals(0x7FFFFFFF, SrtInspector.srtDataSeqnum(TestPackets.srtData(0x7FFFFFFF), 64))
    }

    @Test
    fun controlPacketHasNoDataSeqnum() {
        assertEquals(-1, SrtInspector.srtDataSeqnum(TestPackets.srtAck(5), 20))
        assertEquals(-1, SrtInspector.srtDataSeqnum(TestPackets.keepalive(), 2))
    }

    @Test
    fun srtTypeReadsFirstTwoBytesBigEndian() {
        assertEquals(PacketType.SRT_ACK, SrtInspector.srtType(TestPackets.srtAck(1), 20))
        assertEquals(PacketType.SRTLA_KEEPALIVE, SrtInspector.srtType(TestPackets.keepalive(), 2))
        assertEquals(PacketType.SRTLA_REG3, SrtInspector.srtType(TestPackets.reg3(), 2))
    }

    @Test
    fun ackLastAckAtOffset16() {
        assertEquals(9999, SrtInspector.srtAckLastAck(TestPackets.srtAck(9999), 20))
    }

    @Test
    fun srtlaAckSeqnumsSkipTypeWord() {
        assertContentEquals(intArrayOf(10, 20, 30), SrtInspector.srtlaAckSeqnums(TestPackets.srtlaAck(10, 20, 30), 16))
    }

    @Test
    fun nakSingleSeqnums() {
        assertContentEquals(intArrayOf(7, 9), SrtInspector.nakLostSeqnums(TestPackets.srtNakSingle(7, 9), 24))
    }

    @Test
    fun nakRangeExpands() {
        assertContentEquals(intArrayOf(100, 101, 102, 103), SrtInspector.nakLostSeqnums(TestPackets.srtNakRange(100, 103), 24))
    }
}
