package com.noop.analytics

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs

/**
 * BYTE-IDENTICAL PARITY ORACLE for `ActivityMETCatalog`, against the compiled Swift twin.
 *
 * The expected block is not a re-derivation of the rule.
 * `Tools/oracle/gen_activity_met_catalog_oracle.sh` compiles
 * `Packages/StrandAnalytics/Sources/StrandAnalytics/ActivityMETCatalog.swift` STANDALONE (it imports
 * Foundation and nothing else), drives it over 30 sport labels x 8 durations x 8 body masses — 1,920
 * `grossKcal` cases plus 30 `met` cases — and commits its own answers as
 * `src/test/resources/activity_met_catalog_oracle.json`. The Swift suite reaches that same file by
 * walking up from `#filePath`, so neither platform can drift alone.
 *
 * WHY THIS GRID IS MOSTLY NULL. 1,800 of the 1,920 `grossKcal` cases return nil, and that is the
 * honest shape: `seconds` and `weightKg` are rejected on <= 0, NaN and infinity, so only the positive
 * combinations produce a figure. A grid dominated by rejections is still worth pinning — the rejection
 * boundary is where the two implementations differ, because Kotlin's `!(x > 0)` and Swift's `guard
 * x > 0` must agree on NaN, and a narrower grid would not notice if they didn't.
 *
 * NAMED INPUTS. JSON has no NaN or Infinity literal, so the grid names its values and BOTH platforms map
 * the same names to the same doubles. Changing a mapping on one side fails 1,950 cases rather than one.
 */
class ActivityMetCatalogParityOracleTest {

    private fun gridDouble(name: String): Double? = when (name) {
        "nil" -> null
        "0" -> 0.0
        "neg" -> -1.0
        "3600" -> 3600.0
        "7200" -> 7200.0
        "nan" -> Double.NaN
        "inf" -> Double.POSITIVE_INFINITY
        "neginf" -> Double.NEGATIVE_INFINITY
        else -> throw AssertionError("oracle names a duration the grid does not define: $name")
    }

    private fun oracle(): JSONObject =
        JSONObject(javaClass.classLoader!!.getResource("activity_met_catalog_oracle.json")!!.readText())

    @Test
    fun `every grossKcal case matches the compiled Swift twin`() {
        val root = oracle()
        val cases = root.getJSONArray("grossKcal")
        assertTrue("the fixture must not be empty", cases.length() > 0)

        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val sport = c.getString("sport")
            val seconds = gridDouble(c.getString("seconds"))
            val weight = gridDouble(c.getString("weightKg"))
            val label = "sport=\"$sport\" seconds=${c.getString("seconds")} weightKg=${c.getString("weightKg")}"

            val out = ActivityMETCatalog.grossKcal(sport, seconds ?: 0.0, weight ?: 0.0)
            if (c.isNull("kcal")) {
                assertEquals("$label should not resolve", null, out)
                continue
            }
            val want = c.getDouble("kcal")
            if (out == null) {
                fail("$label should have resolved to $want but resolved to null")
            }
            assertTrue("$label: expected $want, got $out", abs(out!! - want) < 1e-6)
        }
    }

    @Test
    fun `every met case matches the compiled Swift twin`() {
        val root = oracle()
        val cases = root.getJSONArray("met")
        val want = root.getJSONObject("producedBy").getDouble("defaultMET")
        assertEquals(
            "the twin's DEFAULT_MET constant must equal the oracle's",
            want,
            ActivityMETCatalog.DEFAULT_MET,
            0.0,
        )
        for (i in 0 until cases.length()) {
            val c = cases.getJSONObject(i)
            val sport = c.getString("sport")
            assertEquals("met for \"$sport\"", c.getDouble("met"), ActivityMETCatalog.met(sport), 1e-9)
        }
    }

    /**
     * The self-defence guard: a fixture that silently loses cases still passes the loops above, so assert
     * the cross product is whole and that the grid still contains the cases that distinguish the two
     * normalisation passes.
     */
    @Test
    fun `the fixture still covers the whole cross product and both lookup passes`() {
        val root = oracle()
        val produced = root.getJSONObject("producedBy")
        val metCases = root.getJSONArray("met")
        val sportCount = produced.getInt("sportCount")
        assertEquals("the sport list and its declared count must agree", sportCount, metCases.length())

        // 8 durations x 8 body masses per sport, from the harness's grids.
        assertEquals(sportCount * 8 * 8, root.getJSONArray("grossKcal").length())

        // The punctuation pass must stay represented, or the second-stage lookup goes untested: Swift's
        // `isNumber` admits No/Nl characters that Kotlin's `isDigit()` does not, and the strip is the only
        // place that divergence can show.
        val withSlash = (0 until metCases.length()).any {
            metCases.getJSONObject(it).getString("sport").contains('/')
        }
        assertTrue("the grid must include a label reachable only via the punctuation pass", withSlash)
    }
}