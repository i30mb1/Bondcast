package n7.srtla

import n7.srtla.protocol.GroupId
import n7.srtla.protocol.PacketType
import n7.srtla.protocol.SrtInspector
import n7.srtla.protocol.SrtlaCodec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SrtlaCodecTest {

    @Test
    fun keepaliveIsTwoBytes() {
        val ka = SrtlaCodec.keepalive()
        assertEquals(2, ka.size)
        assertEquals(PacketType.SRTLA_KEEPALIVE, SrtInspector.srtType(ka, ka.size))
    }

    @Test
    fun reg1And2Layout() {
        val id = GroupId.random(Random(1))
        val reg1 = SrtlaCodec.reg1(id)
        assertEquals(PacketType.REG1_LEN, reg1.size)
        assertEquals(PacketType.SRTLA_REG1, SrtInspector.srtType(reg1, reg1.size))
        assertContentEquals(id, reg1.copyOfRange(2, 2 + PacketType.SRTLA_ID_LEN))

        val reg2 = SrtlaCodec.reg2(id)
        assertEquals(PacketType.SRTLA_REG2, SrtInspector.srtType(reg2, reg2.size))
        assertContentEquals(id, SrtlaCodec.reg2Id(reg2, reg2.size))
    }

    @Test
    fun reg2IdRejectsWrongLength() {
        assertNull(SrtlaCodec.reg2Id(ByteArray(10), 10))
    }

    @Test
    fun groupIdFirstHalfMatch() {
        val sender = GroupId.random(Random(2))
        val receiver = TestPackets.receiverId(sender)
        assertTrue(GroupId.firstHalfMatches(receiver, sender))
        val other = GroupId.random(Random(3))
        assertTrue(!GroupId.firstHalfMatches(other, sender))
    }
}
