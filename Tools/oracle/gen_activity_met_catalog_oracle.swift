import Foundation

// Tools/oracle/gen_activity_met_catalog_oracle.swift — regenerates activity_met_catalog_oracle.json.
//
// Compiled standalone against the real Packages/StrandAnalytics/Sources/StrandAnalytics/ActivityMETCatalog.swift
// and driven over a fixed grid, so the fixture is the Swift build's own answer rather than a
// re-derivation of it. Run by Tools/oracle/gen_activity_met_catalog_oracle.sh on any machine with a
// Swift toolchain; the result is committed and read by BOTH the Kotlin and the Swift test.
//
// The grid names its inputs because JSON has no NaN or Infinity literal. Both platforms map the same
// names to the same doubles, so the names are the shared contract for the inputs.
//
// COVERAGE. `met(forSport:)` is asked three things: a key that is in the table, a key that is only
// reachable after punctuation is stripped, and a key that is in neither. The grid deliberately
// includes labels with spaces, hyphens, slashes, mixed case and surrounding whitespace, because the
// normalisation order (lowercase -> strip whitespace -> miss -> strip non-alphanumerics) is where a
// port is most likely to differ, and a table lookup that silently fell through to the default would
// under-price a session rather than fail.

/// Grid values, in a fixed order. "nil" is Swift's absent Double?.
let DOUBLE_NAMES = ["nil", "0", "neg", "3600", "7200", "nan", "inf", "neginf"]

func double(_ name: String) -> Double? {
    switch name {
    case "nil": return nil
    case "0": return 0.0
    case "neg": return -1.0
    case "3600": return 3600.0
    case "7200": return 7200.0
    case "nan": return .nan
    case "inf": return .infinity
    case "neginf": return -.infinity
    default: fatalError("unknown grid name \(name)")
    }
}

/// Sport labels covering every branch of the two-stage lookup.
let SPORTS = [
    // In the table as written.
    "running", "yoga", "strength", "swimming", "cycling", "squash", "rowing", "hiit",
    // Reachable only after the whitespace pass (these are the keys WITH the space removed).
    "Open water swim", "Trail running", "Indoor cycling", "Strength training",
    // Reachable only after the punctuation pass ("hurling/camogie" is a literal table key).
    "hurling/camogie", "Open-water swim", "Powerlifting - Heavy",
    // Case and surrounding whitespace, which normalisation must absorb.
    "  Running  ", "YOGA", "StrengthTraining", "traditional strength training",
    // Genuinely absent: must reach defaultMET, including after every strip.
    "quidditch", "underwater basket weaving", "", "   ", "!!!", "42",
    // Adjacent to real keys, to catch a prefix match that should not happen.
    "runningx", "yoga-ish", "swimm", "str", "cycl",
]

var lines: [String] = []
lines.append("{")
lines.append("  \"note\": \"Produced by compiling Packages/StrandAnalytics/Sources/StrandAnalytics/ActivityMETCatalog.swift standalone and calling grossKcal / met(forSport:) over the grids below. Inputs are named rather than literal because JSON has no NaN or Infinity; both platforms map the same names to the same doubles. Read by ActivityMetCatalogParityOracleTest.kt and ActivityMETCatalogTests.swift from the same committed file.\",")
lines.append("  \"producedBy\": {")
lines.append("    \"toolchain\": \"Swift " + swiftVersionString + "\",")
lines.append("    \"sourceCommit\": \"" + sourceCommit + "\",")
lines.append("    \"defaultMET\": \(ActivityMETCatalog.defaultMET),")
lines.append("    \"sportCount\": \(SPORTS.count)")
lines.append("  },")
lines.append("  \"grossKcal\": [")
for (si, sport) in SPORTS.enumerated() {
    for secondsName in DOUBLE_NAMES {
        for weightName in DOUBLE_NAMES {
            lines.append("      {\"sport\": \"\(sport)\", \"seconds\": \"\(secondsName)\", \"weightKg\": \"\(weightName)\", \"kcal\": " + {
                let v = ActivityMETCatalog.grossKcal(sport: sport, seconds: double(secondsName) ?? 0, weightKg: double(weightName) ?? 0)
                // A nil result, or nil passed THROUGH (secondsName "nil"), both print as null.
                if let v = v { return String(format: "%.6f", v) }
                return "null"
            }() + "}" + (si == SPORTS.count - 1 && secondsName == DOUBLE_NAMES.last! && weightName == DOUBLE_NAMES.last! ? "" : ","))
        }
    }
}
lines.append("  ],")
lines.append("  \"met\": [")
for (i, sport) in SPORTS.enumerated() {
    lines.append("    {\"sport\": \"\(sport)\", \"met\": \(String(format: "%.2f", ActivityMETCatalog.met(forSport: sport)))}" + (i == SPORTS.count - 1 ? "" : ","))
}
lines.append("  ]")
lines.append("}")

print(lines.joined(separator: "\n"), terminator: "")
FileHandle.standardError.write("sports=\(SPORTS.count) grossKcal=\(SPORTS.count * DOUBLE_NAMES.count * DOUBLE_NAMES.count)\n".data(using: .utf8)!)