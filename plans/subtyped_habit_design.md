# Subtyped Habit Type — Design Document

*Created: 2026-03-28*

## Overview

A **subtyped habit** has an ordered list of named subtypes (e.g. Pullups → `["chinups", "wide", "pullups", "dip", "neutral"]`). Tapping it opens a dialog where the user enters counts per subtype. The main `habitsdb.txt` stores the **total** (sum of all subtypes) for backward compatibility; a separate per-habit JSON file stores the per-subtype breakdown.

---

## 1. Data Model Changes — `AppSettings`

Add three new fields to [`AppSettings`](app/src/main/java/com/example/tail/data/HabitModels.kt:78) following the exact pattern of existing types:

```kotlin
/** Habits that have the "subtyped" feature enabled. */
val subtypedHabits: Set<String> = emptySet(),

/** Maps habit name → ordered list of subtype names. */
val habitSubtypes: Map<String, List<String>> = emptyMap(),

/** Maps habit name → SAF URI string for the per-habit subtype data JSON file. */
val subtypeDataFileUris: Map<String, String> = emptyMap(),
```

These mirror the pattern of [`conditionalHabits`](app/src/main/java/com/example/tail/data/HabitModels.kt:181) / [`conditionalLinkedHabits`](app/src/main/java/com/example/tail/data/HabitModels.kt:187) and [`textInputFileUris`](app/src/main/java/com/example/tail/data/HabitModels.kt:133).

---

## 2. Subtype Data File Format

Each subtyped habit gets its own JSON file (accessed via SAF URI, like text-input log files). Format:

```json
{
  "2026-03-28": {
    "chinups": 10,
    "wide": 5,
    "pullups": 8
  },
  "2026-03-27": {
    "dip": 15
  }
}
```

- Type: `Map<String, Map<String, Int>>` — outer key = `"YYYY-MM-DD"`, inner key = subtype name, value = count.
- Only subtypes with count > 0 need to be stored for a given date.
- The sum of all inner values for a date **must** equal the total stored in `habitsdb.txt` for that date.

---

## 3. New Repository — `SubtypeDataRepository`

Create [`SubtypeDataRepository.kt`](app/src/main/java/com/example/tail/data/SubtypeDataRepository.kt) following the same pattern as [`TextInputRepository`](app/src/main/java/com/example/tail/data/TextInputRepository.kt:29):

```kotlin
class SubtypeDataRepository {

    /** Load the full subtype data map from the SAF URI. */
    suspend fun loadSubtypeData(uri: Uri, context: Context): Map<String, Map<String, Int>>

    /** Save a day's subtype breakdown. Reads existing file, merges the day, writes back. */
    suspend fun saveSubtypeDay(
        uri: Uri,
        context: Context,
        date: String,           // "YYYY-MM-DD"
        breakdown: Map<String, Int>  // subtype name → count
    )

    /** Load just today's breakdown (convenience). */
    suspend fun loadTodayBreakdown(uri: Uri, context: Context): Map<String, Int>
}
```

Uses Gson for JSON serialization, same as [`TextInputRepository`](app/src/main/java/com/example/tail/data/TextInputRepository.kt:13).

---

## 4. SettingsRepository Changes

In [`SettingsRepository.kt`](app/src/main/java/com/example/tail/data/SettingsRepository.kt):

### New DataStore keys

```kotlin
private val KEY_SUBTYPED_HABITS = stringSetPreferencesKey("subtyped_habits")
private val KEY_HABIT_SUBTYPES = stringPreferencesKey("habit_subtypes")
private val KEY_SUBTYPE_DATA_FILE_URIS = stringPreferencesKey("subtype_data_file_uris")
```

### Serialization for `habitSubtypes: Map<String, List<String>>`

Reuse the existing `PAIR_SEP` / `KV_SEP` scheme. The list of subtypes is comma-separated (with `\,` escaping, same as [`encodeLinkedHabitsMap`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:122)):

```
"Pullups\x00chinups,wide,pullups,dip,neutral|||Squats\x00front,back,goblet"
```

New helpers: `encodeSubtypesMap` / `decodeSubtypesMap` — nearly identical to [`encodeLinkedHabitsMap`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:122) / [`decodeLinkedHabitsMap`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:128) but producing `List<String>` instead of `Set<String>` (order matters).

`subtypeDataFileUris` uses the existing [`encodeFileUriMap`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:70) / [`decodeFileUriMap`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:73).

### New `settingsFlow` mapping

Add to the [`settingsFlow`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:149) map block:

```kotlin
subtypedHabits = prefs[KEY_SUBTYPED_HABITS] ?: emptySet(),
habitSubtypes = decodeSubtypesMap(prefs[KEY_HABIT_SUBTYPES] ?: ""),
subtypeDataFileUris = decodeFileUriMap(prefs[KEY_SUBTYPE_DATA_FILE_URIS] ?: ""),
```

### New save methods

```kotlin
suspend fun saveSubtypedHabits(habits: Set<String>)
suspend fun saveHabitSubtypes(subtypes: Map<String, List<String>>)
suspend fun saveSubtypeDataFileUris(uris: Map<String, String>)
```

Each follows the exact pattern of [`saveConditionalHabits`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:304) / [`saveConditionalLinkedHabits`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:311) / [`saveTextInputFileUris`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:256).

---

## 5. ViewModel Changes

In [`HabitViewModel.kt`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt):

### New instance

```kotlin
private val subtypeDataRepo = SubtypeDataRepository()
```

### New methods

| Method | Purpose | Pattern follows |
|--------|---------|-----------------|
| `toggleSubtyped(habitName)` | Toggle subtyped on/off. When turning off, also remove from `habitSubtypes` and `subtypeDataFileUris`. When turning on, also add to `customInputHabits` (subtyped implies custom input). | [`toggleConditional()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:544) |
| `setHabitSubtypes(habitName, subtypes: List<String>)` | Set the ordered subtype list for a habit. | [`setConditionalLinks()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:558) |
| `saveSubtypeDataFileUri(habitName, uri)` | Save the SAF URI for the subtype data file. | Pattern of text-input file URI saving |
| `loadTodaySubtypeBreakdown(habitName, callback)` | Load today's per-subtype counts from the data file. Used by the dialog. | [`loadTextOptions()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt) |
| `saveSubtypeIncrement(habitName, breakdown: Map<String, Int>)` | Save the per-subtype breakdown to the data file AND increment the habit total in `habitsdb.txt` by the sum. | Combines file write + [`incrementHabit()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt) |

### Mutual exclusion logic in `toggleSubtyped`

When enabling subtyped:
- Add habit to `customInputHabits` (it shows a dialog on tap)
- Remove from `textInputHabits` if present (not combinable)
- Remove from `datedEntryHabits` if present (not combinable)

When disabling subtyped:
- Remove from `habitSubtypes` map
- Remove from `subtypeDataFileUris` map
- Optionally remove from `customInputHabits` (user can re-enable manually)

---

## 6. UI Changes

### 6a. New Dialog — `SubtypeIncrementDialog.kt`

Create [`SubtypeIncrementDialog.kt`](app/src/main/java/com/example/tail/ui/SubtypeIncrementDialog.kt) following the style of [`IncrementDialog`](app/src/main/java/com/example/tail/ui/IncrementDialog.kt):

```
┌─────────────────────────────────┐
│  Pullups                        │
│  Today total: 23                │
│─────────────────────────────────│
│  chinups   [___] +1  +5  +10   │
│  wide      [___] +1  +5  +10   │
│  pullups   [___] +1  +5  +10   │
│  dip       [___] +1  +5  +10   │
│  neutral   [___] +1  +5  +10   │
│─────────────────────────────────│
│  Adding: 0                      │
│              Cancel    OK       │
└─────────────────────────────────┘
```

**Props:**

```kotlin
@Composable
fun SubtypeIncrementDialog(
    habitName: String,
    currentTotalToday: Int,
    subtypes: List<String>,
    onConfirm: (Map<String, Int>) -> Unit,  // subtype → count entered
    onDismiss: () -> Unit
)
```

**Behavior:**
- Each subtype row has: label, `OutlinedTextField` (number input), quick-add buttons (+1, +5, +10)
- "Adding: N" shows the live sum of all subtype inputs
- OK is enabled when the sum > 0
- `onConfirm` passes the full `Map<String, Int>` of non-zero entries
- Uses `Dialog` (custom layout) like [`TextInputDialog`](app/src/main/java/com/example/tail/ui/TextInputDialog.kt:55) for consistent dark styling
- Scrollable via `LazyColumn` if many subtypes

### 6b. Dialog State in `HabitGridScreen`

Add a new state holder in [`HabitGridScreen`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt), following the `textInputDialogState` pattern:

```kotlin
data class SubtypeDialogState(
    val habit: Habit,
    val subtypes: List<String>
)

var subtypeDialogState by remember { mutableStateOf<SubtypeDialogState?>(null) }
```

### 6c. Tap Behavior in `onHabitClick`

Add a new `when` branch in the [`onHabitClick`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:349) lambda, **before** the text-input check:

```kotlin
habit.name in settings.subtypedHabits -> {
    val subtypes = settings.habitSubtypes[habit.name] ?: emptyList()
    if (subtypes.isNotEmpty()) {
        subtypeDialogState = SubtypeDialogState(habit = habit, subtypes = subtypes)
    }
}
```

### 6d. Dialog Rendering

Below the existing `textInputDialogState?.let { ... }` block, add:

```kotlin
subtypeDialogState?.let { state ->
    SubtypeIncrementDialog(
        habitName = state.habit.name,
        currentTotalToday = state.habit.rawTodayCount,
        subtypes = state.subtypes,
        onConfirm = { breakdown ->
            viewModel.saveSubtypeIncrement(state.habit.name, breakdown)
            subtypeDialogState = null
        },
        onDismiss = { subtypeDialogState = null }
    )
}
```

### 6e. EditModeControlBar Toggle

Add a "Subtyped" toggle section in [`EditModeControlBar`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:763) after the Conditional toggle, following the exact same pattern:

**New parameters to `EditModeControlBar`:**
```kotlin
subtypedHabits: Set<String>,
habitSubtypes: Map<String, List<String>>,
subtypeDataFileUris: Map<String, String>,
onToggleSubtyped: (String) -> Unit,
onEditSubtypes: (String) -> Unit,
onPickSubtypeDataFile: (String) -> Unit,
```

**Toggle UI** (same structure as the Conditional toggle):
- Switch: "Subtyped" label, subtitle shows subtype count or "No subtypes"
- When enabled, show:
  - Subtypes list with edit button (opens a simple editor dialog)
  - Data file link button (SAF file picker, same pattern as text-input file picker)

### 6f. Subtype List Editor Dialog

A simple dialog for editing the ordered list of subtype names. Could be a text area where each line is a subtype name, or a list with add/remove/reorder. **Simplest approach**: a single `OutlinedTextField` where subtypes are entered as comma-separated values. On OK, split by comma, trim, and save.

---

## 7. File List — What to Create/Modify

### New files

| File | Purpose |
|------|---------|
| [`app/src/main/java/com/example/tail/data/SubtypeDataRepository.kt`](app/src/main/java/com/example/tail/data/SubtypeDataRepository.kt) | Read/write per-habit subtype JSON data files |
| [`app/src/main/java/com/example/tail/ui/SubtypeIncrementDialog.kt`](app/src/main/java/com/example/tail/ui/SubtypeIncrementDialog.kt) | Composable dialog for entering per-subtype counts |

### Modified files

| File | Changes |
|------|---------|
| [`app/src/main/java/com/example/tail/data/HabitModels.kt`](app/src/main/java/com/example/tail/data/HabitModels.kt) | Add `subtypedHabits`, `habitSubtypes`, `subtypeDataFileUris` to `AppSettings` |
| [`app/src/main/java/com/example/tail/data/SettingsRepository.kt`](app/src/main/java/com/example/tail/data/SettingsRepository.kt) | Add 3 DataStore keys, `encodeSubtypesMap`/`decodeSubtypesMap` helpers, flow mapping, 3 save methods |
| [`app/src/main/java/com/example/tail/ui/HabitViewModel.kt`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt) | Add `SubtypeDataRepository` instance, 5 new methods (toggle, set subtypes, save URI, load breakdown, save increment) |
| [`app/src/main/java/com/example/tail/ui/HabitGridScreen.kt`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt) | Add `SubtypeDialogState`, new `onHabitClick` branch, dialog rendering, `EditModeControlBar` params + toggle section, SAF file picker for subtype data file |

---

## 8. Implementation Order

1. `AppSettings` fields in `HabitModels.kt`
2. `SettingsRepository.kt` — keys, serialization, flow, save methods
3. `SubtypeDataRepository.kt` — new file
4. `HabitViewModel.kt` — new methods
5. `SubtypeIncrementDialog.kt` — new file
6. `HabitGridScreen.kt` — dialog state, tap behavior, dialog rendering, EditModeControlBar additions
