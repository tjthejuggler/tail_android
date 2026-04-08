# System Patterns — Tail

**Last updated:** 2026-03-28T15:47Z

## Architecture

MVVM (Model-View-ViewModel) with Jetpack Compose UI.

```
app/src/main/java/com/example/tail/
├── data/
│   ├── HabitModels.kt        # Data classes, HABIT_ORDER, type aliases
│   ├── HabitCalculator.kt    # Streak/antistreak/ATH calculations
│   ├── HabitsRepository.kt   # JSON read/write via SAF URI (Gson)
│   ├── SettingsRepository.kt  # DataStore Preferences
│   ├── TextInputRepository.kt # Per-habit text-log JSON files
│   ├── DatedEntryRepository.kt # Dated entry file parsing (dream journal format)
│   ├── AiIconRepository.kt   # AI-generated icon storage (PNG files + JSON index)
│   └── AiIconGeneratorService.kt # AI image API client + post-processing
├── ipc/
│   ├── HabitsContentProvider.kt  # Read-only ContentProvider for same-keystore apps
│   └── HabitIncrementReceiver.kt # BroadcastReceiver for habit increments
├── ui/
│   ├── HabitViewModel.kt     # Central ViewModel (1577 lines — largest file)
│   ├── HabitColors.kt        # Color tiers, drawable icon map
│   ├── HabitButton.kt        # Single habit cell composable
│   ├── HabitGridScreen.kt    # Main 8×10 grid screen
│   ├── SettingsScreen.kt     # Configuration screen
│   ├── AppStatsScreen.kt     # Analytics/stats screen (1400 lines)
│   ├── GraphsScreen.kt       # Graph visualizations (941 lines)
│   ├── IncrementDialog.kt    # Custom amount dialog
│   ├── CalendarPickerDialog.kt # Date picker
│   ├── StreakGraphPopup.kt   # Streak visualization popup
│   ├── TextInputDialog.kt    # Text entry dialog
│   └── theme/                # Material3 theme
├── ShareTextActivity.kt      # Share sheet integration activity
└── MainActivity.kt           # NavHost entry point
```

## Key Design Patterns

### 1. Repository Pattern
- `HabitsRepository` — JSON file I/O via SAF URIs
- `SettingsRepository` — DataStore Preferences for app config
- `TextInputRepository` — Per-habit text log files
- `DatedEntryRepository` — Dated entry file parsing with change detection

### 2. StateFlow-based State Management
- `HabitViewModel` owns `StateFlow<List<Habit>>` and `StateFlow<AppSettings>`
- UI collects flows via `collectAsState()`
- Instant UI updates via targeted state mutations, followed by background full rebuilds

### 3. Two-Phase Update Strategy
- **Phase 1 (instant):** Zero-cost targeted `todayCount` update on existing list → button recolors immediately
- **Phase 2 (background):** Full rebuild on `Dispatchers.Default` for streak/ATH stats, file write on `Dispatchers.IO`

### 4. SAF (Storage Access Framework)
- Files accessed via persistent URI permissions (no direct file paths)
- Works with Syncthing, Obsidian vaults, or any file provider

### 5. IPC Security Model
- Custom `signature`-level permission: `com.example.tail.permission.TAIL_INTEGRATION`
- Only apps signed with the same keystore can access ContentProvider or send increment broadcasts
- Tail must also hold its own permission for broadcast delivery

### 6. Navigation
- Jetpack Navigation Compose with routes: `grid`, `settings`, `app_stats`
- Day navigation via selectedDate state in ViewModel

### 7. Multiple Screens
- `HabitScreen` data class with id, name, and ordered habit list
- Screen layout persisted to external JSON file (`screens_layout.json`)

## Data Flow

```
User Tap → ViewModel.incrementHabit()
  → Phase 1: Update todayCount in _habits StateFlow (instant)
  → Phase 2: Background coroutine
    → HabitsRepository.incrementHabit() (read-modify-write JSON)
    → HabitCalculator recalculates streaks/ATH
    → Update _habits StateFlow with full stats
```

## File Format

```json
{
  "Habit Name": {
    "2026-01-05": 1,
    "2026-01-06": 0,
    "2026-01-07": 2
  }
}
```
