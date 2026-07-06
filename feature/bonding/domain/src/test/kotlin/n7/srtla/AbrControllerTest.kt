package n7.srtla

import n7.srtla.abr.AbrConfig
import n7.srtla.abr.AbrController
import n7.srtla.abr.AbrSample
import n7.srtla.abr.abrController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AbrControllerTest {

    private val t0 = 1_000_000_000_000L

    private val config = AbrConfig(
        minKbps = 1_000,
        maxKbps = 6_000,
        sndBufHighMs = 1_000,
        sndBufLowMs = 300,
        decreaseFactor = 0.8,
        increaseStepKbps = 500,
        increaseIntervalNanos = 2_000_000_000L,
        decreaseCooldownNanos = 1_000_000_000L,
    )

    private fun at(ms: Long) = t0 + ms * 1_000_000L

    private fun controller(): AbrController = abrController(config).also { it.reset(config.maxKbps) }

    private fun AbrController.sample(nowMs: Long, sndBufferMs: Int, pktLossTotal: Int) =
        onSample(AbrSample(at(nowMs), sndBufferMs, pktLossTotal))

    @Test
    fun dropOnHighSndBuf() {
        val abr = controller()
        val d = abr.sample(0, sndBufferMs = 2_000, pktLossTotal = 0)
        assertTrue(d.changed)
        assertEquals(4_800, d.targetKbps)
    }

    @Test
    fun lossDoesNotTriggerDrop() {
        val abr = controller()
        val d = abr.sample(0, sndBufferMs = 50, pktLossTotal = 42)
        assertFalse(d.changed)
        assertEquals(6_000, d.targetKbps)
    }

    @Test
    fun holdWhenCalmAtCeiling() {
        val abr = controller()
        var last = 6_000
        for (i in 0..10) {
            val d = abr.sample(i * 1_000L, sndBufferMs = 100, pktLossTotal = 0)
            assertFalse(d.changed)
            last = d.targetKbps
        }
        assertEquals(6_000, last)
    }

    @Test
    fun slowIncreaseAfterDrop() {
        val abr = controller()
        abr.sample(0, sndBufferMs = 2_000, pktLossTotal = 0) // → 4800, lastIncrease anchored at t0

        val tooSoon = abr.sample(1_000, sndBufferMs = 100, pktLossTotal = 0)
        assertFalse(tooSoon.changed)
        assertEquals(4_800, tooSoon.targetKbps)

        val up = abr.sample(2_000, sndBufferMs = 100, pktLossTotal = 0)
        assertTrue(up.changed)
        assertEquals(5_300, up.targetKbps)
    }

    @Test
    fun cooldownBlocksRapidDrops() {
        val abr = controller()
        val first = abr.sample(0, sndBufferMs = 2_000, pktLossTotal = 0)
        assertEquals(4_800, first.targetKbps)

        val blocked = abr.sample(500, sndBufferMs = 2_000, pktLossTotal = 0)
        assertFalse(blocked.changed)
        assertEquals(4_800, blocked.targetKbps)

        val second = abr.sample(1_000, sndBufferMs = 2_000, pktLossTotal = 0)
        assertTrue(second.changed)
        assertEquals(3_840, second.targetKbps)
    }

    @Test
    fun clampsToFloor() {
        val abr = controller()
        var now = 0L
        var last = 6_000
        repeat(20) {
            last = abr.sample(now, sndBufferMs = 2_000, pktLossTotal = 0).targetKbps
            now += 1_000
        }
        assertEquals(config.minKbps, last)

        val atFloor = abr.sample(now, sndBufferMs = 2_000, pktLossTotal = 0)
        assertFalse(atFloor.changed)
        assertEquals(config.minKbps, atFloor.targetKbps)
    }

    @Test
    fun clampsToCeilingOnRecovery() {
        val abr = controller()
        abr.sample(0, sndBufferMs = 2_000, pktLossTotal = 0) // → 4800
        var now = 2_000L
        var last = 4_800
        repeat(20) {
            last = abr.sample(now, sndBufferMs = 100, pktLossTotal = 0).targetKbps
            now += 2_000
        }
        assertEquals(config.maxKbps, last)
    }

    @Test
    fun lossDoesNotBlockRecovery() {
        val abr = controller()
        abr.sample(0, sndBufferMs = 2_000, pktLossTotal = 0)
        val d = abr.sample(2_000, sndBufferMs = 50, pktLossTotal = 30)
        assertTrue(d.changed)
        assertEquals(5_300, d.targetKbps)
    }

    @Test
    fun resetRestoresMax() {
        val abr = controller()
        abr.sample(0, sndBufferMs = 2_000, pktLossTotal = 0)
        abr.sample(1_000, sndBufferMs = 2_000, pktLossTotal = 0) // dropped twice → 3840
        abr.reset(config.maxKbps)
        val d = abr.sample(2_000, sndBufferMs = 100, pktLossTotal = 0)
        assertFalse(d.changed)
        assertEquals(config.maxKbps, d.targetKbps)
    }

    @Test
    fun negativeSndBufIsIgnored() {
        val abr = controller()
        // мусор из libsrt: ни вниз, ни вверх, ни влияния на счётчики
        val d = abr.sample(0, sndBufferMs = -1_827, pktLossTotal = 0)
        assertFalse(d.changed)
        assertEquals(6_000, d.targetKbps)
    }

    @Test
    fun singleModerateSpikeDoesNotDrop() {
        val abr = controller()
        // выброс между high и 2×high: одиночный семпл не считается перегрузкой
        val first = abr.sample(0, sndBufferMs = 1_500, pktLossTotal = 0)
        assertFalse(first.changed)

        val calm = abr.sample(1_000, sndBufferMs = 100, pktLossTotal = 0)
        assertEquals(6_000, calm.targetKbps)
    }

    @Test
    fun sustainedModerateSpikeDropsOnSecondSample() {
        val abr = controller()
        abr.sample(0, sndBufferMs = 1_500, pktLossTotal = 0)
        val second = abr.sample(1_000, sndBufferMs = 1_500, pktLossTotal = 0)
        assertTrue(second.changed)
        assertEquals(4_800, second.targetKbps)
    }

    @Test
    fun lossSpikeTriggersDrop() {
        val abr = controller()
        abr.sample(0, sndBufferMs = 50, pktLossTotal = 0)
        // +200 потерь за секунду при спокойном буфере — канал теряет, режем
        val d = abr.sample(1_000, sndBufferMs = 50, pktLossTotal = 200)
        assertTrue(d.changed)
        assertEquals(4_800, d.targetKbps)
    }

    @Test
    fun lossCounterResetIsNotASpike() {
        val abr = controller()
        abr.sample(0, sndBufferMs = 50, pktLossTotal = 500)
        // реконнект: счётчик потерь нового сокета меньше прошлого — не всплеск
        val d = abr.sample(1_000, sndBufferMs = 50, pktLossTotal = 3)
        assertFalse(d.changed)
        assertEquals(6_000, d.targetKbps)
    }

    @Test
    fun noIncreaseWhileBufferMidRange() {
        val abr = controller()
        abr.sample(0, sndBufferMs = 2_000, pktLossTotal = 0) // → 4800
        // buffer between low (300) and high (1000): neither congested nor clear-to-raise
        val d = abr.sample(5_000, sndBufferMs = 600, pktLossTotal = 0)
        assertFalse(d.changed)
        assertEquals(4_800, d.targetKbps)
    }
}
