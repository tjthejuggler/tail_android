# JugCoach Session Integration Spec (Protocol v3)

**Added:** 2026-08-16

## Overview

JugCoach previously reported habit usage to Tail with a plain binary "+1"
broadcast (`ACTION_INCREMENT_HABIT`) mapped to the **Used JugCoach** event.
Protocol v3 replaces that ping for completed runs with a single
**session broadcast** that atomically writes the binary count **plus six
juggling metrics** to the same mapped habit:

| Metric | Slot key | Graph metric id | Default label |
|---|---|---|---|
| Total seconds juggled | `secondary_value:<habit>` | `jugcoach_time` | Time (s) |
| Total catches | `secondary_value2:<habit>` | `jugcoach_catches` | Catches |
| Seconds in catch-ended runs | `secondary_value3:<habit>` | `jugcoach_time_catch` | Time·Catch (s) |
| Seconds in drop-ended runs | `secondary_value4:<habit>` | `jugcoach_time_drop` | Time·Drop (s) |
| Catches in catch-ended runs | `secondary_value5:<habit>` | `jugcoach_catches_catch` | Catches·Catch |
| Catches in drop-ended runs | `secondary_value6:<habit>` | `jugcoach_catches_drop` | Catches·Drop |

The habit's own (primary) count keeps its binary semantics: +1 per completed
run, still respecting the **max-1** cap if the habit has it enabled. The six
metric slots are cumulative daily totals and bypass the cap (same behaviour
as the WAGS `EXTRA_MINUTES` slot).

## Broadcast contract

```kotlin
val intent = Intent("com.example.tail.ACTION_JUGCOACH_SESSION").apply {
    setPackage("com.example.tail")
    putExtra("EXTRA_HABIT_ID", habitName)          // String, exact habit name
    putExtra("EXTRA_SECONDS_TOTAL", seconds)        // Int ≥ 0
    putExtra("EXTRA_SECONDS_CATCH", secondsCatch)   // Int ≥ 0 (0 if run ended in drop)
    putExtra("EXTRA_SECONDS_DROP", secondsDrop)     // Int ≥ 0 (0 if run ended in catch)
    putExtra("EXTRA_CATCHES_TOTAL", catches)        // Int ≥ 0
    putExtra("EXTRA_CATCHES_CATCH", catchesCatch)   // Int ≥ 0 (0 if run ended in drop)
    putExtra("EXTRA_CATCHES_DROP", catchesDrop)     // Int ≥ 0 (0 if run ended in catch)
}
context.sendBroadcast(intent, "com.example.tail.permission.TAIL_INTEGRATION")
```

- Sender must be signed with the same keystore as Tail (signature permission).
- Missing / zero / negative extras are coerced to 0; slots with a 0 amount are
  simply not written that day.
- A run with **no duration and no catch data** (both null) still produces the
  binary "+1" — full backward compatibility with the old ping.

## Storage format

Same `habitsdb_phone.txt` JSON as the other secondary-value integrations
(`Map<habitKey, Map<dateStr, count>>`):

```json
{
  "Juggling":                     { "2026-08-16": 1 },
  "secondary_value:Juggling":     { "2026-08-16": 940 },
  "secondary_value2:Juggling":    { "2026-08-16": 612 },
  "secondary_value3:Juggling":    { "2026-08-16": 610 },
  "secondary_value4:Juggling":    { "2026-08-16": 330 },
  "secondary_value5:Juggling":    { "2026-08-16": 398 },
  "secondary_value6:Juggling":    { "2026-08-16": 214 }
}
```

All `secondary_valueN:` keys are hidden from the habit list
(`isSecondaryValueKey()`) and are renamed together with the habit
(`HabitsRepository.renameHabit` walks `SECONDARY_VALUE_SLOT_PREFIXES`).

## Tail implementation

| Piece | Location |
|---|---|
| Slot prefixes + helpers (`secondaryValueSlotKey`, …) | [`HabitModels.kt`](app/src/main/java/com/example/tail/data/HabitModels.kt:199) |
| Graph metric ids + default labels | [`HabitModels.kt`](app/src/main/java/com/example/tail/data/HabitModels.kt:296) |
| Atomic multi-key write `incrementHabitSlots()` | [`HabitsRepository.kt`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:620) |
| Receiver (Mutex-serialised, max-1 aware) | [`JugCoachSessionReceiver.kt`](app/src/main/java/com/example/tail/ipc/JugCoachSessionReceiver.kt:63) |
| Manifest registration | [`AndroidManifest.xml`](app/src/main/AndroidManifest.xml:195) |
| Graph wiring (`GraphDataPoint`, `getGraphData`, `getAvailableMetrics`, `metricValueOf`) | [`HabitViewModel.kt`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:4874) |
| Graph display + labels | [`GraphsScreen.kt`](app/src/main/java/com/example/tail/ui/GraphsScreen.kt:1) |

**Detection is key-presence based:** a habit is treated as a JugCoach habit
when any of slots 2–6 exists in the DB (`isJugcoachHabit()`). The six graph
metric buttons then appear automatically in stats mode — no settings toggle.

**Concurrency:** the receiver serialises processing with a `Mutex` (same
pattern as `HabitValueSetReceiver`) and performs the whole 7-key update as
one read-modify-write via `incrementHabitSlots()`, so rapid-fire run saves
cannot interleave or lose increments.

## JugCoach implementation

| Piece | Location |
|---|---|
| `fireTailJugglingSession()` sender | [`TailIntegration.kt`](../JugCoach/app/src/main/java/com/example/jugcoach/util/TailIntegration.kt:146) |
| Call site in `saveCompletedRun()` | [`AppStateManager.kt`](../JugCoach/app/src/main/java/com/example/jugcoach/common/state/AppStateManager.kt:1520) |

JugCoach resolves the habit from the existing **Used JugCoach** mapping
(`tail_habit_mappings` SharedPreferences) — no new mapping or settings UI.
Null `catches` / `durationSeconds` are sent as 0. The catch/drop split is
derived from the run's `isCleanEnd` flag. The separate **Record Broken**
event continues to use the plain `fireTailEvent()` +1 path.

## History backfill

**Added:** 2026-08-16 (same day, second iteration)

JugCoach's Tail integration settings screen has a **"Backfill History to
Tail"** button that retroactively sends the ENTIRE run history (all
patterns, all runs) to the mapped habit:

- **JugCoach side** — [`SettingsViewModel.backfillTailHistory()`](../JugCoach/app/src/main/java/com/example/jugcoach/ui/settings/SettingsViewModel.kt:1074)
  loads every pattern via `patternDao.getAllPatterns()`, aggregates the runs
  by **local calendar day** (run timestamps → device timezone → date), and
  sends one broadcast via
  [`backfillTailJugglingHistory()`](../JugCoach/app/src/main/java/com/example/jugcoach/util/TailIntegration.kt:216).
  A status line under the button reports progress/result; the button is
  disabled while running.

- **Tail side** — `com.example.tail.ACTION_JUGCOACH_BACKFILL` (handled by the
  same [`JugCoachSessionReceiver`](app/src/main/java/com/example/tail/ipc/JugCoachSessionReceiver.kt:63),
  registered for both actions). The payload is
  `EXTRA_VALUES_JSON = {"yyyy-MM-dd": {"runs":n,"s":n,"c":n,"sc":n,"sd":n,"cc":n,"cd":n}, …}`.

- **SET semantics, idempotent** — Tail writes absolute per-day totals via
  [`setHabitSlotsForDates()`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:720)
  (one atomic read-modify-write for all 7 keys), so re-running the backfill
  simply overwrites the same values. Safe to use after restoring a backup or
  re-mapping the habit. The habit's own count is set to the day's **run
  count** (capped at 1 for max-1 habits); slots get the raw totals.

- Days with no runs, no seconds and no catches are omitted; unparseable
  dates are skipped. If today was among the backfilled days the Tasker
  stats file is refreshed.
