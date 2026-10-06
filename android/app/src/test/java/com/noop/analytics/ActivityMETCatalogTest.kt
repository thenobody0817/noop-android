package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MET table and what a session costs when nothing recorded it. Kotlin port of the first five cases
 * of `ActivityMETCatalogTests.swift`, which is the part that tests THIS twin.
 *
 * The Swift file's last two cases are deliberately absent: they test
 * `Calories.estimateBoutCalories(averageHR:…)`, the average-heart-rate estimator that OUTRANKS this
 * table. That is a different engine's twin, and porting it here would claim a pairing this file does
 * not own. It ports with that engine.
 *
 * The exhaustive parity half is [ActivityMetCatalogParityOracleTest] — 1,950 cases against the Swift
 * build's own stdout, which covers the normalisation order far more widely than five hand-written cases
 * can.
 */
class ActivityMETCatalogTest {

    private val profile = UserProfile(weightKg = 80.0, heightCm = 180.0, age = 30.0, sex = "male")

    /** The definition the whole table rests on, pinned once so a refactor cannot quietly rescale it. */
    @Test fun `one MET-hour costs one kcal per kilogram`() {
        val met = ActivityMETCatalog.met("Strength")
        assertEquals(met * 80, ActivityMETCatalog.grossKcal("Strength", 3_600.0, 80.0)!!, 0.001)
        assertEquals(met * 80 / 2, ActivityMETCatalog.grossKcal("Strength", 1_800.0, 80.0)!!, 0.001)
    }

    /** Free text is allowed everywhere in the app, so the table must answer without inventing. */
    @Test fun `sport names match regardless of spelling and unknown ones fall back`() {
        val expected = ActivityMETCatalog.met("Open-water swim")
        for (spelling in listOf("open-water swim", "Open-Water Swim", "openwaterswim", "Open water swim")) {
            assertEquals(spelling, expected, ActivityMETCatalog.met(spelling), 0.0)
        }
        assertEquals(ActivityMETCatalog.DEFAULT_MET, ActivityMETCatalog.met("Underwater basket weaving"), 0.0)
        assertEquals(ActivityMETCatalog.DEFAULT_MET, ActivityMETCatalog.met(""), 0.0)
    }

    /**
     * The native logger's, Hevy's and Oura's labels all missed the `strength` key and were priced at
     * the unknown-sport default, which under-pays the most common logged activity in the app.
     */
    @Test fun `the strength labels sessions arrive with all find the strength entry`() {
        for (label in listOf(
            "Strength Training", "Traditional Strength Training", "TraditionalStrengthTraining",
            "Functional strength training", "strength_training", "Weight Training",
        )) {
            assertEquals(label, ActivityMETCatalog.met("Strength"), ActivityMETCatalog.met(label), 0.0)
        }
    }

    /** The table is a physiological ordering, not an alphabetical accident. */
    @Test fun `the table orders activities the way physiology does`() {
        val ladder = listOf("Meditation", "Yoga", "Walking", "Strength", "Running", "Jump rope")
        val values = ladder.map { ActivityMETCatalog.met(it) }
        assertEquals("MET should climb along $ladder", values.sorted(), values)
        assertTrue("every step must be distinct", values.distinct().size == values.size)
    }

    @Test fun `no body mass means no estimate`() {
        assertNull(ActivityMETCatalog.grossKcal("Running", 3_600.0, 0.0))
        assertNull(ActivityMETCatalog.grossKcal("Running", 0.0, 80.0))
        assertNull(ActivityMETCatalog.grossKcal("Running", Double.NaN, 80.0))
    }
}