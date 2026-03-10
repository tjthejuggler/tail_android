# Tail — Habit Tracker Android App

**Last updated:** 2026-03-10T18:24Z

A native Android habit tracking app built with Kotlin + Jetpack Compose. Maintains full data compatibility with the desktop PyQt widget system by sharing the same `habitsdb_phone.txt` JSON file.

---

## Features

- **8×10 habit grid** — 76 habits in exact order matching the desktop app
- **Color-coded buttons** — 7 color tiers based on today's count (red → orange → green → blue → pink → yellow → glass)
- **Real habit icons** — 79 PNG icons from the original `py_habits_widget` project imported as Android drawables, tinted white; mapped via `IconFinder.py` habit→icon table
- **Corner stats** — top-left: all-time high day, bottom-left: streak/antistreak, bottom-right: longest streak
- **Custom input mode** — long-press any habit to toggle; shows numeric dialog instead of simple +1
- **Default custom input habits:** Launch Pushups Widget, Launch Situps Widget, Launch Squats Widget, Cold Shower Widget, Sweat
- **SAF file access** — pick `habitsdb_phone.txt` from any location; persistent URI permission stored
- **Historical DB support** — optionally pick `habitsdb_without_phone_totals.txt` to merge full history; phone DB takes precedence on date conflicts; stats (streaks, ATH) reflect all-time data
- **Settings screen** — change files, toggle custom input per habit, reset to defaults
- **Dark theme** by default

---

## Architecture

```
app/src/main/java/com/example/tail/
├── data/
│   ├── HabitModels.kt        # Data classes, HABIT_ORDER list, DEFAULT_CUSTOM_INPUT_HABITS
│   ├── HabitCalculator.kt    # Streak/antistreak/ATH calculations, display value adjustments
│   ├── HabitsRepository.kt   # JSON read/write via SAF URI (Gson), habit list builder
│   └── SettingsRepository.kt # DataStore Preferences (file URI, custom input set)
├── ui/
│   ├── HabitViewModel.kt     # StateFlow<List<Habit>>, StateFlow<AppSettings>, increment/toggle
│   ├── HabitColors.kt        # Color tiers, drawable icon map (HABIT_ICON), getHabitColor(), getHabitIconRes()
│   ├── HabitButton.kt        # Single habit cell composable (tap/long-press, corner numbers)
│   ├── IncrementDialog.kt    # Custom amount dialog (+1/+5/+10/+30/+50 quick buttons)
│   ├── HabitGridScreen.kt    # Main 8×10 grid screen with file picker + nav
│   ├── SettingsScreen.kt     # File location + per-habit custom input toggles
│   └── theme/                # Material3 theme (Color, Theme, Type)
└── MainActivity.kt           # NavHost: "grid" ↔ "settings", dark theme
```

---

## Data Format

Compatible with `habitsdb_phone.txt` used by the desktop PyQt widget:

```json
{
  "Habit Name": {
    "2026-01-05": 1,
    "2026-01-06": 0,
    "2026-01-07": 2
  }
}
```

---

## Special Habit Adjustments (for icon color only)

| Habit | Adjustment |
|-------|-----------|
| Launch Pushups Widget | count ÷ 30 (rounded) |
| Launch Situps Widget  | count ÷ 50 (rounded) |
| Launch Squats Widget  | count ÷ 30 (rounded) |
| Sweat                 | count ÷ 15 (rounded) |
| Cold Shower Widget    | if 0 < count < 3 → set to 3; then ÷ 3 (rounded) |

---

## Setup

1. Build and install the APK on your Android device
2. Open the app — tap the 📂 icon in the top bar
3. Navigate to your `habitsdb_phone.txt` file (e.g. in Syncthing/Obsidian vault)
4. Grant persistent read+write permission
5. The grid loads immediately
6. *(Optional)* Go to Settings → **Change Historical File** → pick `habitsdb_without_phone_totals.txt` to include full historical data in streak/ATH stats

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| Jetpack Compose BOM | 2024.09.00 | UI framework |
| Navigation Compose | 2.7.7 | Screen navigation |
| DataStore Preferences | 1.1.1 | Settings persistence |
| Gson | 2.10.1 | JSON parsing |
| DocumentFile | 1.0.1 | SAF file access |
| Lifecycle ViewModel Compose | 2.8.0 | MVVM |
| Coroutines Android | 1.7.3 | Async I/O |

---

## Habit Grid Order

76 habits in 8 columns × 10 rows (positions 77–80 are empty):

| # | Habit | # | Habit |
|---|-------|---|-------|
| 1 | Juggle lights | 2 | Unique juggle |
| 3 | Juggling record broke | 4 | Dream acted |
| 5 | Sleep watch | 6 | Apnea walked |
| 7 | Cold Shower Widget | 8 | Programming sessions |
| ... | *(see `HABIT_ORDER` in HabitModels.kt)* | 76 | Memory practice |
