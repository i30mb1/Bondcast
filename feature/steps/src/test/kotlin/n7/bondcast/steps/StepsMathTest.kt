package n7.bondcast.steps

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StepsMathTest {

    @Test
    fun withoutBaselineShowsRawSensorValue() {
        assertEquals(12345, StepsMath.displayed(raw = 12345, baseline = 0))
    }

    @Test
    fun baselineSubtractsWhatWasWalkedBefore() {
        assertEquals(300, StepsMath.displayed(raw = 12345, baseline = 12045))
    }

    @Test
    fun freshBaselineShowsZero() {
        assertEquals(0, StepsMath.displayed(raw = 12345, baseline = 12345))
    }

    /** Датчик обнуляется при перезагрузке телефона — старая база больше сырого числа. */
    @Test
    fun afterRebootFallsBackToRawInsteadOfNegative() {
        assertEquals(42, StepsMath.displayed(raw = 42, baseline = 12345))
    }

    @Test
    fun groupsDigitsByThousands() {
        assertEquals("0", StepsMath.format(0))
        assertEquals("999", StepsMath.format(999))
        assertEquals("1 000", StepsMath.format(1000))
        assertEquals("12 345", StepsMath.format(12345))
        assertEquals("1 234 567", StepsMath.format(1234567))
    }

    @Test
    fun rebootIsDetectedOnlyWhenBaselineIsAhead() {
        assertTrue(StepsMath.baselineStale(raw = 42, baseline = 12345))
        assertFalse(StepsMath.baselineStale(raw = 12345, baseline = 12345))
        assertFalse(StepsMath.baselineStale(raw = 12345, baseline = 0))
    }
}
