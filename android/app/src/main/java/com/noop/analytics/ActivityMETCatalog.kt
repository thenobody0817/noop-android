package com.noop.analytics

/*
 * ActivityMETCatalog.kt - what a session COSTS when nothing recorded what it cost. Kotlin twin of
 * `StrandAnalytics.ActivityMETCatalog`.
 *
 * Pure, DB-free, no clock. It answers one question: given a sport name and a duration, roughly how much
 * energy did that session take? It exists because a real gap has a real consequence - a Hevy strength
 * session and an imported lift log both arrive with `energyKcal == null`, and a day whose only activity is
 * such a session used to contribute nothing at all to the energy total.
 *
 * Three boundaries keep this honest:
 *
 *  - It is a LAST resort. `EnergyEngine` and its callers reach for a recorded figure first, then a
 *    heart-rate estimate, and only then this table. A MET value is a population average for an activity
 *    NAME; heart rate is evidence about the person who did it.
 *  - Its output is marked as estimated all the way to the screen (`ActivityContribution.isEstimated`).
 *    A table lookup must never arrive looking like a measurement.
 *  - A MET is a GROSS multiple of resting metabolism, so the figure it produces still contains the bout's
 *    resting energy. It is therefore handed over as a gross quantity and converted exactly once, in
 *    `EnergyEngine`, like every other gross source.
 *
 * Values are the published activity costs from the Compendium of Physical Activities (Ainsworth et al.;
 * 2024 Adult Compendium), rounded to the table's own precision and picked at the GENERAL entry for each
 * sport rather than a competitive one - the person logging "Tennis" is far more often playing than
 * competing, and the general entry is the conservative read.
 */
object ActivityMETCatalog {

    /**
     * What an unrecognised sport costs. Deliberately low: 3.5 MET is brisk-walking effort, which is roughly
     * the least a person is doing when they bother to log a session at all, and underpaying an unknown
     * activity is the error this file would rather make.
     *
     * Twin of Swift `ActivityMETCatalog.defaultMET`.
     */
    const val DEFAULT_MET = 3.5

    /**
     * Gross energy for a session of `sport` lasting `seconds`, in kcal, or null without a body mass.
     *
     * 1 MET is 1 kcal per kg of body mass per hour by definition, which is the whole of the arithmetic
     * here - the judgement all sits in the table.
     *
     * The Swift twin is `ActivityMETCatalog.grossKcal`.
     */
    @JvmStatic
    fun grossKcal(sport: String, seconds: Double, weightKg: Double): Double? {
        if (!(seconds > 0) || !seconds.isFinite() || !(weightKg > 0) || !weightKg.isFinite()) return null
        return met(sport) * weightKg * (seconds / 3_600.0)
    }

    /**
     * The MET value for a sport label, or [DEFAULT_MET] for anything the table does not name.
     *
     * Matching normalises the way the app's own `WorkoutSource.sportKey` does (lowercased, spaces removed)
     * and then, on a miss, once more without punctuation - so "Open-water swim", "open water swim" and
     * "Openwater Swim" are the same activity rather than three, and a free-text sport (the picker is a
     * suggestion set, not a whitelist) still has a chance of landing.
     *
     * The Swift twin is `ActivityMETCatalog.met(forSport:)`.
     */
    @JvmStatic
    fun met(sport: String): Double {
        val key = normalized(sport)
        table[key]?.let { return it }
        val plain = key.filter { it.isLetter() || it.isNumberLike() }
        return table[plain] ?: DEFAULT_MET
    }

    /**
     * Swift's `Character.isNumber`, which is broader than Kotlin's `Char.isDigit()`.
     *
     * `isDigit()` tests the Unicode Nd category only. Swift's `isNumber` also admits No and Nl, so
     * `²` and `Ⅷ` survive there and are stripped here — a silent divergence between the two platforms
     * on the punctuation-stripping pass. No sport label contains either character, so this is currently
     * unreachable, but "currently unreachable" is how the next divergence starts.
     */
    /**
     * Kotlin-only: Swift spells this inline as `$0.isNumber` inside its own `filter`, so there is no
     * Swift declaration to pair with. Kept as a named function rather than an inline lambda because the
     * Unicode range set is long enough to deserve a name and a comment.
     */
    private fun Char.isNumberLike(): Boolean = isDigit() || this in '²'..'³' || this in '¹'..'⁹'
        || this in 'ⅰ'..'ⅻ' || this in 'Ⅰ'..'Ⅻ' || this == '½' || this == '¼' || this == '¾'

    /**
     * Keys are `normalized(...)` spellings of `WorkoutCatalog.all`, which is the set of sports the app
     * itself offers. A broader port would be hundreds of rows for activities that never reach it.
     *
     * The Swift twin is `ActivityMETCatalog.normalized(_:)`, which is `private` on both sides.
     */
    private fun normalized(sport: String): String =
        sport.lowercase().filter { !it.isWhitespace() }

    private val table: Map<String, Double> = mapOf(
        // Foot
        "running" to 8.3, "treadmillrun" to 8.3, "walking" to 3.5, "treadmillwalk" to 3.5,
        "nordicwalking" to 4.8, "hiking" to 6.0, "rucking" to 7.0, "snowshoeing" to 7.5,
        "stairclimber" to 9.0, "jumprope" to 12.3, "parkour" to 8.0,
        // Wheels and water
        "cycling" to 7.5, "indoorcycle" to 7.0, "spinning" to 8.5, "mountainbiking" to 8.5,
        "openwaterswim" to 7.0, "poolswim" to 6.0, "rowing" to 7.0, "rowmachine" to 7.0,
        "kayaking" to 5.0, "sailing" to 3.0, "standuppaddleboard" to 6.0, "surfing" to 3.0,
        "scubadiving" to 7.0, "waterpolo" to 10.0, "kiteboarding" to 6.0, "fishing" to 3.5,
        // Gym
        "elliptical" to 5.0, "hiit" to 8.0, "crossfit" to 8.0, "bootcamp" to 8.0, "calisthenics" to 6.0,
        "strength" to 5.0, "bodybuilding" to 5.0, "weightlifting" to 5.0, "powerlifting" to 6.0,
        // The labels sessions actually arrive with. The lookup is exact, so the native logger's, Hevy's and
        // Oura's "Strength Training" and Apple's two strength types all missed "strength" and were priced at
        // the unknown-sport default instead.
        "strengthtraining" to 5.0, "traditionalstrengthtraining" to 5.0,
        "functionalstrengthtraining" to 5.0, "weighttraining" to 5.0, "resistancetraining" to 5.0,
        "gymnastics" to 3.8, "climbing" to 7.5,
        // Low intensity
        "yoga" to 2.5, "pilates" to 3.0, "stretching" to 2.3, "meditation" to 1.3, "gaming" to 1.5,
        "darts" to 2.5, "billiards" to 2.5, "bowling" to 3.0, "golf" to 4.8, "discgolf" to 3.0,
        "archery" to 3.5, "curling" to 4.0, "hunting" to 5.0, "skydiving" to 3.5, "motorracing" to 3.0,
        "motocross" to 4.0, "wheelchair" to 4.0, "horsebackriding" to 5.5,
        // Combat
        "boxing" to 7.8, "kickboxing" to 10.3, "martialarts" to 10.3, "jiujitsu" to 10.3, "judo" to 10.3,
        "muaythai" to 10.3, "fencing" to 6.0, "paintball" to 6.0,
        // Ball and court
        "basketball" to 6.5, "soccer" to 7.0, "baseball" to 5.0, "softball" to 5.0, "volleyball" to 4.0,
        "sandvolleyball" to 8.0, "badminton" to 5.5, "tennis" to 7.3, "squash" to 7.3, "racquetball" to 7.0,
        "tabletennis" to 4.0, "handball" to 12.0, "netball" to 6.0, "pickleball" to 4.5, "padel" to 6.0,
        "spikeball" to 6.0, "frisbee" to 3.0, "polo" to 8.0,
        // Field
        "icehockey" to 8.0, "fieldhockey" to 7.8, "lacrosse" to 8.0, "rugby" to 8.3,
        "americanfootball" to 8.0, "australianfootball" to 8.0, "gaelicfootball" to 8.0,
        "cricket" to 4.8, "hurling/camogie" to 8.0, "hurlingcamogie" to 8.0,
        // Ice, snow, board
        "skiing" to 7.0, "snowboarding" to 5.3, "iceskating" to 7.0, "inlineskating" to 7.5,
        "skateboarding" to 5.0,
        // Movement to music
        "dancing" to 5.0, "ballet" to 5.0, "breakdancing" to 7.3, "cheerleading" to 6.0,
    )
}