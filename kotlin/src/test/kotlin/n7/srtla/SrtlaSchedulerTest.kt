package n7.srtla

import n7.srtla.protocol.GroupId
import n7.srtla.protocol.PacketType
import n7.srtla.protocol.SrtInspector
import n7.srtla.scheduler.SchedulerAction
import n7.srtla.scheduler.SchedulerEvent
import n7.srtla.scheduler.SrtlaScheduler
import n7.srtla.scheduler.Transport
import n7.srtla.scheduler.WindowParams
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SrtlaSchedulerTest {

    private val senderId = GroupId.random(Random(42))
    private val t0 = 1_000_000_000_000L
    private val params = WindowParams.DEFAULT
    private val defWindow = params.windowDef * params.windowMult

    private fun newScheduler() = SrtlaScheduler(senderId, params)

    private fun List<SchedulerAction>.sends() = filterIsInstance<SchedulerAction.SendOnLink>()
    private fun List<SchedulerAction>.locals() = filterIsInstance<SchedulerAction.SendToLocal>()
    private fun SchedulerAction.SendOnLink.type() = SrtInspector.srtType(data, data.size)

    private fun SrtlaScheduler.bringUp(linkId: Int, transport: Transport, now: Long, first: Boolean) {
        onEvent(SchedulerEvent.LinkUp(linkId, transport), now)
        if (first) {
            val reg2 = TestPackets.reg2(TestPackets.receiverId(senderId))
            onEvent(SchedulerEvent.LinkPacket(linkId, reg2, reg2.size), now)
        }
        val reg3 = TestPackets.reg3()
        onEvent(SchedulerEvent.LinkPacket(linkId, reg3, reg3.size), now)
    }

    @Test
    fun firstLinkSendsReg1ThenActivatesOnReg3() {
        val s = newScheduler()
        val up = s.onEvent(SchedulerEvent.LinkUp(1, Transport.WIFI), t0)
        assertEquals(1, up.sends().size)
        assertEquals(PacketType.SRTLA_REG1, up.sends().first().type())

        val reg2 = TestPackets.reg2(TestPackets.receiverId(senderId))
        val afterReg2 = s.onEvent(SchedulerEvent.LinkPacket(1, reg2, reg2.size), t0)
        assertEquals(PacketType.SRTLA_REG2, afterReg2.sends().first().type())
        assertFalse(s.isActive(1))

        val reg3 = TestPackets.reg3()
        s.onEvent(SchedulerEvent.LinkPacket(1, reg3, reg3.size), t0)
        assertTrue(s.isActive(1))
    }

    @Test
    fun secondLinkRegistersWithReg2() {
        val s = newScheduler()
        s.bringUp(1, Transport.WIFI, t0, first = true)
        val up = s.onEvent(SchedulerEvent.LinkUp(2, Transport.CELLULAR), t0)
        assertEquals(PacketType.SRTLA_REG2, up.sends().first().type())
        val reg3 = TestPackets.reg3()
        s.onEvent(SchedulerEvent.LinkPacket(2, reg3, reg3.size), t0)
        assertTrue(s.isActive(2))
    }

    @Test
    fun dataBalancesAcrossLinks() {
        val s = newScheduler()
        s.bringUp(1, Transport.WIFI, t0, first = true)
        s.bringUp(2, Transport.CELLULAR, t0, first = false)

        val p1 = TestPackets.srtData(100)
        val p2 = TestPackets.srtData(101)
        assertEquals(1, s.onEvent(SchedulerEvent.LocalSrtPacket(p1, p1.size), t0).sends().size)
        s.onEvent(SchedulerEvent.LocalSrtPacket(p2, p2.size), t0)

        assertEquals(1, s.inFlightOf(1))
        assertEquals(1, s.inFlightOf(2))
    }

    @Test
    fun disabledLinkIsExcluded() {
        val s = newScheduler()
        s.bringUp(1, Transport.WIFI, t0, first = true)
        s.bringUp(2, Transport.CELLULAR, t0, first = false)
        s.setLinkEnabled(1, false)

        val p = TestPackets.srtData(200)
        s.onEvent(SchedulerEvent.LocalSrtPacket(p, p.size), t0)

        assertEquals(0, s.inFlightOf(1))
        assertEquals(1, s.inFlightOf(2))
    }

    @Test
    fun srtlaAckDecrementsInFlight() {
        val s = newScheduler()
        s.bringUp(1, Transport.WIFI, t0, first = true)

        val p = TestPackets.srtData(500)
        s.onEvent(SchedulerEvent.LocalSrtPacket(p, p.size), t0)
        assertEquals(1, s.inFlightOf(1))

        val ack = TestPackets.srtlaAck(500)
        s.onEvent(SchedulerEvent.LinkPacket(1, ack, ack.size), t0)
        assertEquals(0, s.inFlightOf(1))
        assertEquals(defWindow + 1, s.windowOf(1))
    }

    @Test
    fun nakShrinksWindowAndForwards() {
        val s = newScheduler()
        s.bringUp(1, Transport.WIFI, t0, first = true)

        val p = TestPackets.srtData(700)
        s.onEvent(SchedulerEvent.LocalSrtPacket(p, p.size), t0)

        val nak = TestPackets.srtNakSingle(700)
        val out = s.onEvent(SchedulerEvent.LinkPacket(1, nak, nak.size), t0)
        assertEquals(defWindow - params.windowDecr, s.windowOf(1))
        assertEquals(1, out.locals().size)
    }

    @Test
    fun srtAckResyncsInFlight() {
        val s = newScheduler()
        s.bringUp(1, Transport.WIFI, t0, first = true)

        for (sn in intArrayOf(10, 11, 12)) {
            val p = TestPackets.srtData(sn)
            s.onEvent(SchedulerEvent.LocalSrtPacket(p, p.size), t0)
        }
        assertEquals(3, s.inFlightOf(1))

        val ack = TestPackets.srtAck(12)
        val out = s.onEvent(SchedulerEvent.LinkPacket(1, ack, ack.size), t0)
        assertEquals(1, s.inFlightOf(1))
        assertEquals(1, out.locals().size)
    }

    @Test
    fun keepaliveSentWhenIdle() {
        val s = newScheduler()
        s.bringUp(1, Transport.WIFI, t0, first = true)
        s.bringUp(2, Transport.CELLULAR, t0, first = false)

        val out = s.onEvent(SchedulerEvent.Tick(t0 + 1_500_000_000L), t0 + 1_500_000_000L)
        val keepalives = out.sends().filter { it.type() == PacketType.SRTLA_KEEPALIVE }
        assertEquals(2, keepalives.size)
        assertTrue(keepalives.all { it.data.size == 2 })
    }

    @Test
    fun reg1RotatesToNextLinkWhenRegistrationStalls() {
        val s = newScheduler()
        // первый линк (cellular) не может достучаться до сервера
        val up1 = s.onEvent(SchedulerEvent.LinkUp(1, Transport.CELLULAR), t0)
        assertEquals(PacketType.SRTLA_REG1, up1.sends().first().type())
        assertEquals(1, up1.sends().first().linkId)
        // второй линк поднялся, пока регистрация висит на первом — молчит
        val up2 = s.onEvent(SchedulerEvent.LinkUp(2, Transport.WIFI), t0)
        assertTrue(up2.sends().isEmpty())

        // ответа нет — по дедлайну регистрация переезжает на следующий линк
        val later = t0 + params.connTimeoutNanos + 1
        val out = s.onEvent(SchedulerEvent.Tick(later), later)
        val reg1s = out.sends().filter { it.type() == PacketType.SRTLA_REG1 }
        assertEquals(1, reg1s.size)
        assertEquals(2, reg1s.first().linkId)

        // REG2 с рабочего линка завершает регистрацию: оба линка получают REG2
        val reg2 = TestPackets.reg2(TestPackets.receiverId(senderId))
        val afterReg2 = s.onEvent(SchedulerEvent.LinkPacket(2, reg2, reg2.size), later)
        assertEquals(2, afterReg2.sends().size)
        assertTrue(afterReg2.sends().all { it.type() == PacketType.SRTLA_REG2 })
    }

    @Test
    fun timedOutLinkResendsReg2AndDropsActive() {
        val s = newScheduler()
        s.bringUp(1, Transport.WIFI, t0, first = true)
        assertTrue(s.isActive(1))

        val late = t0 + 5_000_000_000L
        val out = s.onEvent(SchedulerEvent.Tick(late), late)
        assertEquals(PacketType.SRTLA_REG2, out.sends().first().type())
        assertFalse(s.isActive(1))
    }
}
