# Apnea Secondary Value + Fallback — Wags Integration Instructions

**Date:** 2026-08-12  
**Audience:** Wags developer  
**Prerequisite:** Read `SECONDARY_VALUE_WAGS_SPEC.md` first — it covers the general
secondary-value mechanism. This document covers the **apnea-specific** changes.

---

## What Changed in Tail

Tail now supports two new features for apnea habits:

### 1. Secondary Value (existing feature, now enabled for apnea)

Each apnea habit can track **two values per day**:

| Slot             | Key in habitsdb.txt             | What it stores       |
|------------------|---------------------------------|----------------------|
| Primary (Value 1)| `<habitName>`                   | **Minutes** held     |
| Secondary (Value 2) | `secondary_value:<habitName>` | **Session count**    |

This is the exact same pattern already used for Meditations.

### 2. Fallback to Secondary (NEW feature)

When enabled for a habit, Tail uses the **secondary value (session count)** for
points calculation on days where the **primary value (minutes) is zero**.

**How it works:**
- If minutes > 0 for a day → points are calculated normally from minutes.
- If minutes = 0 but sessions > 0 → points = sessions (raw value, no divider).
- This also affects streak/average calculations — a day with 0 minutes but
  3 sessions counts as "done" for streak purposes.

**Tail side setup (user does this in Tail's edit menu):**
1. Long-press an apnea habit → Edit
2. Enable **"Secondary value"** toggle
3. Enable **"Fallback to secondary"** toggle (appears only when secondary value is on)

---

## What Wags Needs To Change

### Summary

Wags currently sends only **minutes** (primary value) for apnea sessions.
It needs to **also send session count = 1** as the secondary value, exactly
like it already does for meditation.

### Affected Slots

All four apnea activity slots need this change:

| Slot              | ViewModel file                          | Current call                                    |
|-------------------|-----------------------------------------|-------------------------------------------------|
| `FREE_HOLD`       | `FreeHoldActiveScreen.kt` / `ApneaViewModel.kt` | `sendHabitIncrementWithMinutes(Slot.FREE_HOLD, mins)` |
| `TABLE_TRAINING`  | `ApneaViewModel.kt`                     | `sendHabitIncrementWithMinutes(Slot.TABLE_TRAINING, mins)` |
| `PROGRESSIVE_O2`  | `ProgressiveO2ViewModel.kt`             | `sendHabitIncrementWithMinutes(Slot.PROGRESSIVE_O2, mins)` |
| `MIN_BREATH`      | `MinBreathViewModel.kt`                 | `sendHabitIncrementWithMinutes(Slot.MIN_BREATH, mins)` |

### Change 1: Real-time session completion

After every `sendHabitIncrementWithMinutes(slot, minutes)` call for an apnea
slot, add a `sendSecondaryValueIncrement(slot, 1)` call.

**Example (FreeHoldActiveScreen.kt, around line 808-809):**

```kotlin
// BEFORE:
val freeHoldMinutes = HabitIntegrationRepository.millisToMinutes(duration)
habitRepo.sendHabitIncrementWithMinutes(Slot.FREE_HOLD, freeHoldMinutes)

// AFTER:
val freeHoldMinutes = HabitIntegrationRepository.millisToMinutes(duration)
habitRepo.sendHabitIncrementWithMinutes(Slot.FREE_HOLD, freeHoldMinutes)
habitRepo.sendSecondaryValueIncrement(Slot.FREE_HOLD, 1)
```

**Repeat for all four slots** in their respective ViewModels:

```kotlin
// ApneaViewModel.kt — table training (around line 648):
habitRepo.sendHabitIncrementWithMinutes(Slot.TABLE_TRAINING, tableHoldMinutes)
habitRepo.sendSecondaryValueIncrement(Slot.TABLE_TRAINING, 1)

// ApneaViewModel.kt — free hold (around line 1128):
habitRepo.sendHabitIncrementWithMinutes(Slot.FREE_HOLD, freeHoldMinutes)
habitRepo.sendSecondaryValueIncrement(Slot.FREE_HOLD, 1)

// ProgressiveO2ViewModel.kt (around line 1001):
habitRepo.sendHabitIncrementWithMinutes(Slot.PROGRESSIVE_O2, holdMinutes)
habitRepo.sendSecondaryValueIncrement(Slot.PROGRESSIVE_O2, 1)

// MinBreathViewModel.kt (around line 1047):
habitRepo.sendHabitIncrementWithMinutes(Slot.MIN_BREATH, holdMinutes)
habitRepo.sendSecondaryValueIncrement(Slot.MIN_BREATH, 1)
```

### Change 2: Backfill (HabitBackfillManager.kt)

In `HabitBackfillManager.backfill()`, the apnea section (around line 140-193)
currently only builds minutes-per-date maps. You need to **also build
sessions-per-date maps** and send them as secondary values.

**Add session counting alongside the existing minute counting:**

```kotlin
// Add these maps next to the existing *MinutesByDate maps:
val freeHoldSessionsByDate = mutableMapOf<String, Int>()
val tableTrainingSessionsByDate = mutableMapOf<String, Int>()
val progressiveO2SessionsByDate = mutableMapOf<String, Int>()
val minBreathSessionsByDate = mutableMapOf<String, Int>()

// In the loop over apneaRecords (around line 155-168):
for (record in apneaRecords) {
    val dateStr = epochMsToDateStr(record.timestamp, zone)
    val minutes = HabitIntegrationRepository.millisToMinutes(record.durationMs)
    val targetMinuteMap = when (record.tableType) {
        null             -> freeHoldMinutesByDate
        "O2", "CO2"      -> tableTrainingMinutesByDate
        "PROGRESSIVE_O2" -> progressiveO2MinutesByDate
        "MIN_BREATH"     -> minBreathMinutesByDate
        else             -> null
    }
    if (targetMinuteMap != null) {
        targetMinuteMap[dateStr] = (targetMinuteMap[dateStr] ?: 0) + minutes
        // Also count sessions:
        val targetSessionMap = when (record.tableType) {
            null             -> freeHoldSessionsByDate
            "O2", "CO2"      -> tableTrainingSessionsByDate
            "PROGRESSIVE_O2" -> progressiveO2SessionsByDate
            "MIN_BREATH"     -> minBreathSessionsByDate
            else             -> null
        }
        targetSessionMap?.let { it[dateStr] = (it[dateStr] ?: 0) + 1 }
    }
}
```

**Then send the session counts as secondary values** (after each primary send):

```kotlin
// Free hold (after line 177):
if (!freeHoldSkipped && freeHoldMinutesByDate.isNotEmpty()) {
    habitRepo.sendHabitValuesForDates(Slot.FREE_HOLD, freeHoldMinutesByDate)
    kotlinx.coroutines.delay(500)
    habitRepo.sendSecondaryValuesForDates(Slot.FREE_HOLD, freeHoldSessionsByDate)
}

// Table training (after line 182):
if (!tableTrainingSkipped && tableTrainingMinutesByDate.isNotEmpty()) {
    habitRepo.sendHabitValuesForDates(Slot.TABLE_TRAINING, tableTrainingMinutesByDate)
    kotlinx.coroutines.delay(500)
    habitRepo.sendSecondaryValuesForDates(Slot.TABLE_TRAINING, tableTrainingSessionsByDate)
}

// Progressive O₂ (after line 187):
if (!progressiveO2Skipped && progressiveO2MinutesByDate.isNotEmpty()) {
    habitRepo.sendHabitValuesForDates(Slot.PROGRESSIVE_O2, progressiveO2MinutesByDate)
    kotlinx.coroutines.delay(500)
    habitRepo.sendSecondaryValuesForDates(Slot.PROGRESSIVE_O2, progressiveO2SessionsByDate)
}

// Min breath (after line 192):
if (!minBreathSkipped && minBreathMinutesByDate.isNotEmpty()) {
    habitRepo.sendHabitValuesForDates(Slot.MIN_BREATH, minBreathMinutesByDate)
    kotlinx.coroutines.delay(500)
    habitRepo.sendSecondaryValuesForDates(Slot.MIN_BREATH, minBreathSessionsByDate)
}
```

**Note:** The 500ms delay between primary and secondary sends is important —
Tail processes broadcasts serially through a mutex, and the secondary write
needs the primary to finish first.

### Change 3: Update BackfillResult (optional but recommended)

Add session count fields to `BackfillResult` for display in the backfill
summary dialog, following the same pattern as `meditationSessions`.

---

## No Changes Needed

- **`HabitIntegrationRepository.kt`** — The `sendSecondaryValueIncrement()`
  and `sendSecondaryValuesForDates()` methods already exist and work for any
  slot. No new methods needed.
- **Settings UI** — The habit slot mapping UI already works for all slots.
  The user configures which Tail habit each Wags slot maps to.
- **Tail DataStore settings** — Tail handles enabling secondary value and
  fallback via its own UI. Wags doesn't need to know about these toggles.

---

## Tail Habits (Row 6)

The four apnea habits in Tail are:

```
"Apnea walked", "Apnea practiced", "Apnea apb", "Apnea spb"
```

The user maps Wags slots to these habits in Wags Settings → Habit Integration.
Typical mapping:

| Wags Slot           | Tail Habit        | Primary (min)  | Secondary (sessions) |
|---------------------|-------------------|----------------|----------------------|
| `FREE_HOLD`         | "Apnea walked"    | walk hold time | 1 per session        |
| `TABLE_TRAINING`    | "Apnea practiced" | table hold time| 1 per session        |
| `PROGRESSIVE_O2`    | "Apnea practiced" | O₂ hold time   | 1 per session        |
| `MIN_BREATH`        | "Apnea practiced" | min-breath time| 1 per session        |

(Note: multiple Wags slots may map to the same Tail habit. Tail's max-merge
strategy handles this correctly.)

---

## Testing Checklist

1. **Real-time:** Complete an apnea session in Wags → check Tail shows both
   minutes (Value 1) and session count (Value 2) for today.
2. **Fallback:** On a day with 0 minutes but >0 sessions, verify Tail shows
   points equal to the session count.
3. **Backfill:** Run "Send historical data" in Wags settings → verify both
   minutes and session counts appear for past dates in Tail.
4. **Graph:** In Tail's graph mode for an apnea habit with secondary value
   enabled, verify three buttons appear: Points / Value 1 / Value 2.
