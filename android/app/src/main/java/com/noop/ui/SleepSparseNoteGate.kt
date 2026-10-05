package com.noop.ui

/**
 * The sparse-coverage caveat thresholds (#345). Kotlin twin of Swift `SleepView`'s
 * `sparseShortFraction` / `sparseShortFloorMin`.
 */
internal object SleepSparseNoteGate {
    /**
     * A sparse night reads as possibly cut short only below this share of the wearer's TYPICAL sleep,
     * not below a fixed number of hours.
     *
     * A fixed threshold says "eight hours is enough" equally to someone who sleeps six and to someone
     * who sleeps nine, so it is wrong for half of everyone. The wearer's own recent nights are the only
     * honest reference, and they are already computed.
     */
    const val SPARSE_SHORT_FRACTION = 0.70

    /**
     * With no typical yet — fewer than five scored nights — there is nothing to compare against, so a
     * sparse night under this many minutes asleep still reads short.
     */
    const val SPARSE_SHORT_FLOOR_MIN = 240.0
}