package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The #345 "May be incomplete" caveat, on the wearer's-own-typical rule. Kotlin twin of Swift
 * `SleepSparseNoteGateTests`.
 *
 * WHY THE RULE CHANGED, which is the interesting part. The original compared a sparse night against a
 * fixed 8 hours, which says "eight hours is enough" equally to someone who sleeps six and to someone who
 * sleeps nine — wrong for half of everyone. It also warned on the sparse flag alone, which on one wearer's
 * history fired on 35 of 56 computed nights and was right on 2 of them.
 *
 * So the caveat is now personal: it stands only when the night is genuinely short against the wearer's own
 * recent nights, or — with no typical yet — against a floor.
 */
class SleepSparseNoteGateTest {

    private fun applies(sparse: Boolean, asleepMin: Double, typical: Double?, partial: Boolean = false) =
        stageSparseNoteApplies(sparse, asleepMin, typical, partial)

    @Test fun `a night that was not staged sparsely earns nothing`() {
        assertFalse(applies(sparse = false, asleepMin = 120.0, typical = 480.0))
    }

    @Test fun `a long night is not warned about however sparse its motion`() {
        // 8h+ against an 8h typical: sparse, but a normal length for this wearer.
        for (hours in listOf(8.0, 10.5, 12.0, 12.75)) {
            assertFalse(
                "a ${hours}h night cannot honestly be captioned as possibly reading short",
                applies(sparse = true, asleepMin = hours * 60.0, typical = 480.0),
            )
        }
    }

    @Test fun `a night well under the wearer's own typical is a warning`() {
        // 70% of an 8h typical is 5h36m, so 5h is short for this wearer — the case a flat 8h
        // threshold cannot see, because 5h is under both.
        assertTrue(applies(sparse = true, asleepMin = 5.0 * 60.0, typical = 480.0))
    }

    @Test fun `the same night is fine for someone who sleeps five hours`() {
        assertFalse(applies(sparse = true, asleepMin = 5.0 * 60.0, typical = 300.0))
    }

    @Test fun `with no typical yet a sparse short night still reads short`() {
        // Nothing to compare against, so the absolute floor stands in.
        assertTrue(applies(sparse = true, asleepMin = 180.0, typical = null))
        assertFalse(applies(sparse = true, asleepMin = 300.0, typical = null))
    }

    @Test fun `a partial timeline already states the stronger fact, so this steps down`() {
        // Two warnings on one night is worse than one accurate one: the partial-timeline note is
        // measured, this one is inferred.
        assertFalse(applies(sparse = true, asleepMin = 120.0, typical = 480.0, partial = true))
    }

    @Test fun `the typical needs five scored nights before it means anything`() {
        fun metric(min: Double) = DailyMetric(deviceId = "d", day = "2026-01-01", totalSleepMin = min)
        assertNull(
            "four nights is not a typical",
            typicalAsleepMin(listOf(metric(480.0), metric(470.0), metric(490.0), metric(500.0))),
        )
        assertEquals(480.0, typicalAsleepMin(List(5) { metric(480.0) })!!, 0.001)
    }
}