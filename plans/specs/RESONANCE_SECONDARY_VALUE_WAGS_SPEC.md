# Resonance Breathing Secondary Value (Sessions) — Wags Integration Instructions

**Date:** 2026-08-14  
**Audience:** Wags developer  
**Prerequisite:** Read `SECONDARY_VALUE_WAGS_SPEC.md` first — it covers the general
secondary-value mechanism. `APNEA_SECONDARY_VALUE_WAGS_SPEC.md` is the closest
analogue to this change (apnea got the same treatment on 2026-08-12).

---

## Background

Tail habits can track **two values per day**:

| Slot               | Key in habitsdb.txt               | Resonance Breathing |
|--------------------|-----------------------------------|---------------------|
| Primary (Value 1)  | `Resonance Breathing`             | **Minutes**         |
| Secondary (Value 2)| `secondary_value:Resonance Breathing` | **Session count** |

- **Meditation** has sent both values since 2026-08-10 (`dca62bb`).
- **Apnea** has sent both values since 2026-08-12 (`8dee1b1`).
- **Resonance breathing** has sent only **minutes** (primary) since the
  2026-08-08 protocol-v2 change (`cfe563a`). Before that date it sent only
  session counts (+1). It never sent both at once — this change fixes that.

The end state: resonance breathing behaves **exactly like meditation** —
minutes → primary, session count → secondary.

---

## Status: the Wags changes are ALREADY IMPLEMENTED in this repo

All changes below are already committed to the working tree. This document
describes what was changed so you can review, test, and deploy it.

### No new IPC methods were needed

`HabitIntegrationRepository` already had everything required:

- [`sendSecondaryValueIncrement(slot, value)`](../wags/app/src/main/java/com/example/wags/data/ipc/HabitIntegrationRepository.kt:321) —
  fires the normal increment broadcast with `EXTRA_HABIT_ID` prefixed with
  `secondary_value:`, so Tail writes to the Value 2 slot.
- [`sendSecondaryValuesForDates(slot, dateValues)`](../wags/app/src/main/java/com/example/wags/data/ipc/HabitIntegrationRepository.kt:342) —
  same thing for the SET/backfill broadcast.

Both are slot-agnostic; only the resonance call sites were missing.

### Change 1: Real-time session completion (3 files, 4 call sites)

Everywhere wags sends `sendHabitIncrementWithMinutes(Slot.RESONANCE_BREATHING, minutes)`,
a `sendSecondaryValueIncrement(Slot.RESONANCE_BREATHING, 1)` now follows:

| File | Function | Notes |
|------|----------|-------|
| `ui/breathing/BreathingViewModel.kt` | `stopSession()` | Normal resonance session end |
| `ui/breathing/AssessmentRunViewModel.kt` | `collectOrchestratorState()` — `RfOrchestratorState.Complete` branch | RF assessment finished |
| `ui/breathing/AssessmentRunViewModel.kt` | `collectOrchestratorState()` — `RfOrchestratorState.SlidingDone` branch | Sliding RF assessment finished |
| `ui/breathing/AssessmentRunViewModel.kt` | `finishEarly()` | "End Early & Save" path |

Pattern (identical to meditation in `MeditationViewModel.stopSession()`):

```kotlin
val minutes = HabitIntegrationRepository.secondsToMinutes(durationSeconds)
habitRepo.sendHabitIncrementWithMinutes(Slot.RESONANCE_BREATHING, minutes)  // Value 1
habitRepo.sendSecondaryValueIncrement(Slot.RESONANCE_BREATHING, 1)         // Value 2
```

### Change 2: Backfill (`data/ipc/HabitBackfillManager.kt`)

`backfill()` now also builds a **sessions-per-date** map for resonance
(counting both normal resonance sessions **and** RF assessments, since both
feed the same slot) and sends it as the secondary value:

```kotlin
val resonanceMinutesByDate  = mutableMapOf<String, Int>()
val resonanceSessionsByDate = mutableMapOf<String, Int>()   // NEW

// in both loops (resonance sessions + RF assessments):
resonanceMinutesByDate[dateStr]  = (resonanceMinutesByDate[dateStr] ?: 0) + minutes
resonanceSessionsByDate[dateStr] = (resonanceSessionsByDate[dateStr] ?: 0) + 1   // NEW

val resonanceSkipped = habitRepo.getHabitId(Slot.RESONANCE_BREATHING).isBlank()
if (!resonanceSkipped && resonanceMinutesByDate.isNotEmpty()) {
    habitRepo.sendHabitValuesForDates(Slot.RESONANCE_BREATHING, resonanceMinutesByDate)
    kotlinx.coroutines.delay(500)   // let Tail's mutex-serialised receiver finish
    habitRepo.sendSecondaryValuesForDates(Slot.RESONANCE_BREATHING, resonanceSessionsByDate)  // NEW
}
```

**The 500 ms delay matters:** Tail processes IPC broadcasts serially through a
mutex; the secondary write must not overtake the primary write.

`BackfillResult` gained a `resonanceSessions` field (included in `totalSessions`).
No UI changes were needed — the summary dialog only uses the totals.

---

## What was done on the Tail side

- **No protocol/receiver changes.** Tail's `HabitIncrementReceiver` and
  `HabitValueSetReceiver` are fully generic: any `EXTRA_HABIT_ID` that starts
  with `secondary_value:` is written to the Value 2 slot. This is the same
  path meditation and apnea already use.
- **One-time data migration** (`HabitViewModel.performResonanceSecondaryMigration()`):
  before 2026-08-08, wags wrote **session counts** (+1) into the primary slot.
  The migration moves those stale counts (pre-2026-08-08 values ≤ 3) to
  `secondary_value:Resonance Breathing` and zeroes the primary slot, so old
  days show "0 min / 1 session" instead of "1 min". Unlike the apnea migration,
  it only moves small values — larger pre-cutoff values are legitimate minutes
  written by the wags backfill. It runs once, guarded by a DataStore flag
  (`migration_resonance_secondary_done`).
- **Auto-enables the Value 2 track** for "Resonance Breathing" (same setting
  the user enabled manually for Meditations), so the sessions column/graph
  button appears without manual setup.
- **Optional user step (not code):** if you want days with 0 minutes but >0
  sessions to count for points, enable **"Fallback to secondary"** for the
  habit in Tail's edit screen (long-press habit → Edit). This mirrors the
  optional apnea setup.

---

## Deployment / rollout order

1. Deploy the **Tail** update first (or same time). Old Tail builds simply
   ignore nothing — the secondary broadcast writes a `secondary_value:` key
   that older Tails already understand (the mechanism shipped with the
   meditation change on 2026-08-10). Order is not critical, but Tail-first
   ensures the migration runs before new session counts arrive.
2. Deploy the **Wags** update.
3. In Wags: Settings → Tail App Integration → **run "Send historical data"
   (backfill) once** so past resonance sessions populate the secondary slot
   for every date that has a resonance/RF record.

## Testing checklist

1. **Real-time session:** complete a resonance breathing session in Wags →
   Tail shows today's minutes (Value 1) **and** session count 1 (Value 2).
2. **RF assessment:** complete (or end early) a resonance-frequency
   assessment → same double write.
3. **Backfill:** run "Send historical data" → past dates show both minutes
   and session counts in Tail's graph (Points / Value 1 / Value 2 buttons).
4. **Migration:** after opening Tail once, pre-August-2026 days that showed
   "1" now show 0 minutes / 1 session (verify in the graph's Value 2 mode).
5. **Idempotency:** running the backfill twice produces identical data
   (Tail SETs per-date values; the migration is flag-guarded).
