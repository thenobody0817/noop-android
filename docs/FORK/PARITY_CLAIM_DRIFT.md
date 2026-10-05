# Stale parity twin claims on the restored Android tree

`parity_ledger` reports **17 scan errors** on this tree, all of one kind: a `twin` claim in the
restored Android code (or, in two cases, the restored Swift code) that names a symbol the Forge fork
does not have. These are not new debt introduced by the port — they are the honest result of
restoring Android onto a tree whose Apple side moved on, and they were red before any of the 36-table
work began.

Each claim is a promise that two files implement the same behaviour. A claim naming a symbol that no
longer exists is a promise nobody can check. The ledger refuses to evaluate the baseline while they
are unresolved, which is why it prints "Baseline not evaluated" rather than a debt count.

## The four unresolved attached claims

These are the blocking ones: a claim attached to a *declaration* that cannot be paired.

| Claimed Swift symbol | Android site | What the fork actually has |
|---|---|---|
| `Whoop5RR.legacyUnscorableNight` | `protocol/Whoop5RR.kt` | Restructured. The judgement moved to `StrandAnalytics.UniversalTrace.rrTransportLine(strictWhoop5:firstRecordedUnix:firstLabelledUnix:)`. The fork's `Whoop5RR` is a deliberately reduced surface — `milliseconds(ticks:)` and `usesCanonicalSource(model:brand:hasTaggedIntervals:)` only. |
| `firstScorableWhoop5RRTimestamp` | `data/WhoopDao.kt:194`, `data/WhoopRepository.kt:1386` | Gone. The fork replaced upstream's "first scorable timestamp" gate with the `rrTransport` model above (recipe AI-12: choose a delivery path per five-minute segment). This is the same change that added the `transport` column. |
| `legacyWhoop5RRWithheld` | `data/WhoopDao.kt:718` | Gone, same restructure. |
| `WhoopStore.scorableWhoop5Channels` | `data/WhoopDao.kt:141` | Gone, same restructure. |

So the R-R cluster is one change seen four times: upstream modelled "which channels are scorable, and
from when", and the fork replaced that with a transport label per interval. Android still implements
the old model and still *uses* `legacyUnscorableNight` in production (`ui/TodayScreen.kt:1200`), so
this is real divergence, not a stale comment.

## The dead cross-references

Document-level rather than attached to a declaration, so they do not block the baseline.

| Claim names | Site | Status in the fork |
|---|---|---|
| `WhoopStore.replaceCoachMessages` | `data/WhoopDao.kt:1494` | Absent. The fork has no `coachMessage` table in Swift — it exists only in stale oracle JSON. The fork moved Coach state to `Strand/AI/CoachTranscriptStore.swift` and `CoachSemanticMemory`. |
| `AICoachError.isKeyRejection` | `ai/AiCoach.kt:1203` | Absent. |
| `BiofeedbackPrefs.checkInLockedPace` | `ui/BiofeedbackPrefs.kt:73` | Absent; the fork keeps biofeedback in `Strand/Data/BehaviorStore.swift` and `Strand/App/AppModel.swift`. |
| `SleepView.stageSparseNoteApplies` | `ui/SleepScreen.kt:1770` | Absent, and no `SparseNote` concept survives anywhere in the fork. |
| `SleepSparseNoteGateTests` | `ui/SleepSparseNoteGateTest.kt` | Absent — the whole feature is gone. |
| `testTheChannelIsChosenPerHourAcrossANightAndADay` | `data/OuraOneChannelReadSqliteTest.kt` | Absent; the fork's `Whoop5RRTests` has `testAllUnsignedWordsMatchExecutedSwiftOracle`, `testWireBoundsRawUnitsAndExtractedProvenance`, `testTransportCodes`. |
| `RrBatchTimestampsTest` (Kotlin) | `Packages/WhoopProtocol/.../RrBatchTimestampsTests.swift` | The Kotlin twin of a Swift test that still exists. This one is the opposite problem: the Android side of the pair is missing. |
| `LocalDate.now().toEpochDay()` (Kotlin) | `Strand/AI/AICoach.swift` | Not a symbol — a claim written against an expression rather than a name. |

## What each one actually costs

They are not all the same, and that distinction is the point of writing this down:

- **Features the fork dropped** (`SleepSparseNoteGate`, sparse-note staging): the Android twin should
  go with them. Keeping a gate that guards nothing is worse than no gate.
- **Features the fork redesigned** (the whole R-R labelling cluster): the Android logic needs to be
  reimplemented against the transport model, not deleted. `legacyUnscorableNight` is still called in
  production and still decides what the Today screen claims about a night.
- **Claims written against expressions** (`LocalDate.now().toEpochDay()`): a bug in the claim, not in
  either implementation. It should name a declaration.
- **Missing twins** (`RrBatchTimestampsTest`): the Swift test exists and the Kotlin one is absent.

Deciding these properly is per-symbol design work, not a mechanical repair, which is why this is a
document rather than a patch. The honest sequencing is to take the redesign cluster first, since it
is the only category where Android currently computes something the fork computes differently.