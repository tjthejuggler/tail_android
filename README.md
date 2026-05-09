# Tail — Habit Tracker Android App

**Last updated:** 2026-05-09T12:10Z

A native Android habit tracking app built with Kotlin + Jetpack Compose. Maintains full data compatibility with the desktop PyQt widget system by sharing the same `habitsdb_phone.txt` JSON file.

---

## Features

- **8×10 habit grid** — 76 habits in exact order matching the desktop app
- **Color-coded buttons** — 7 color tiers based on today's count (red → orange → green → blue → pink → yellow → glass)
- **Real habit icons** — 269 PNG icons from the original `py_habits_widget` project imported as Android drawables, tinted white; mapped via `HABIT_ICON` table; custom overrides supported per habit
- **Corner stats** — top-left: all-time high day, bottom-left: streak/antistreak, bottom-right: longest streak
- **Custom input mode** — long-press any habit to toggle; shows numeric dialog instead of simple +1
- **Default custom input habits:** Pushups, Situps, Squats, Cold Shower Widget, Sweat
- **SAF file access** — pick `habitsdb_phone.txt` from any location; persistent URI permission stored
- **Historical DB support** — optionally pick `habitsdb_without_phone_totals.txt` to merge full history; phone DB takes precedence on date conflicts; stats (streaks, ATH) reflect all-time data
- **Settings screen** — change files, toggle custom input per habit, reset to defaults
- **Dark theme** by default
- **Edit mode** — tap ✏️ to enter edit mode; select a habit to reorder, delete, or change its icon
- **Debounced reorder** — rapid taps on ← / → accumulate into a single move applied 300 ms after the last tap; the selection highlight moves instantly on every tap so the UI stays responsive without freezing
- **Instant habit color change** — tapping a habit does a zero-cost targeted `todayCount` update on the existing list first (no calculations), so the button recolors on the same frame; a full background rebuild (`Dispatchers.Default`) then updates streak/ATH stats without touching the main thread; the file write also happens in the background
- **Delete habit** — in edit mode, select a habit → tap 🗑 Delete → confirm; removes from screen order only (JSON data files are untouched)
- **Add habit to JSON files** — when adding a new habit via the placeholder cell, it is automatically written to all currently configured JSON files (`habitsdb_phone.txt`, `habitsdb.txt`, `habitsdb_without_phone_totals.txt`)
- **Icon picker** — in edit mode, select a habit → tap 🎨 Icon → scrollable 6-column grid of all 269 available icons; tap to assign, "No icon" to clear override
- **Conditional habit type** *(added 2026-03-24T16:57Z)* — in edit mode, select a habit → toggle **Conditional** on → tap **Set Links** to open a multi-select popup of all other habits; any habits chosen are auto-incremented by +1 whenever the conditional habit is tapped; the linked set is shown inline in the edit bar and persisted to DataStore
- **DataStore habit-name migration** *(added 2026-03-29T03:02Z)* — one-time migration renames legacy "Launch Pushups/Situps/Squats Widget" to "Pushups"/"Situps"/"Squats" across all persisted DataStore keys (custom input set, habit order, screens, icon maps, dividers, etc.); runs automatically on first launch after update; guarded by a boolean flag so it only executes once
- **World-map "where I was" timeline** *(added 2026-05-06T14:05Z)* — small globe icon next to ⚙️ in the top bar opens a landscape map screen. Shows continents drawn from a 75 KB Natural Earth polygon asset (`assets/world_land.json`), plus the dim trail of every day with a known location. A small person marker animates between days as the timeline progresses. Bottom timeline has a draggable slider, ⏸/▶ play button, and `« / »` speed buttons (0.5×, 1×, 2×, 5×, 15×, 30×). Side info box shows location label + habits-done / streak / total-points for the selected day. Selected date is shared bidirectionally between the grid and map screens via `HabitViewModel.selectedDate`, so navigation in either direction preserves the day. Coordinates source: `LocationRepository.daily_coords` SharedPrefs key, populated by today's GPS fix or back-filled by [`scripts/seed_locations_from_timeline.py`](scripts/seed_locations_from_timeline.py:1) from a Google Maps Timeline export.
- **Full backup & restore** *(added 2026-05-08T17:12Z)* — `Settings → Backup & Restore` exports every piece of user-entered data (app settings, advice items, location history + ignored countries, debug saved-notes, habits database, habit timestamps, AI icons, per-habit text logs, dated-entry sources, subtype data, timed sessions, voice-note markdown) into a single self-contained JSON file via SAF `CreateDocument`. The matching **Import Backup** button reads the file and OVERWRITES every persistent data source on this device after a confirmation dialog. Implemented in [`BackupManager`](app/src/main/java/com/example/tail/data/backup/BackupManager.kt:1) with the wire format defined in [`BackupModels`](app/src/main/java/com/example/tail/data/backup/BackupModels.kt:1) (`schemaVersion` + `magic` marker for forward compatibility). AI-icon PNGs are embedded as base64. Per-habit external file content is embedded by habit name and re-written through the original SAF URIs (best-effort if still granted). Tasker / screens-relay / chess.com cache are deliberately skipped because they auto-regenerate.
- **Lock-screen habit list widget** *(added 2026-05-09T12:10Z)* — placeable on the lock screen (or home screen as a fallback). Starts in a slim **collapsed bar** that says *"Tap to open habits"*. A **single tap arms** it (background turns orange, label changes to *"Tap again to open habits"*); a **second tap within 1.5 s expands** it. If the user does nothing, an [`AlarmManager`](app/src/main/java/com/example/tail/widget/HabitListWidgetProvider.kt:380) auto-disarm reverts the bar to the safe collapsed state — so pulling the phone out of a pocket can never accidentally increment anything. Expanded view is a **scrollable [`ListView`](app/src/main/res/layout/widget_expanded.xml:1)** of every habit (icon + name, ~8 visible per panel, dark themed). Tapping a normal habit increments by 1 in the background; tapping a **text-input habit** launches [`WidgetInputActivity`](app/src/main/java/com/example/tail/widget/WidgetInputActivity.kt:1) — a transparent trampoline that shows the same [`TextInputDialog`](app/src/main/java/com/example/tail/ui/TextInputDialog.kt:1) as the main app, then saves the entry + increments the habit. Smart **widget-local ordering**: any habit you tap from the widget jumps to the top; an exception is **max-one habits** (binary done/not-done): when you tap one of those it goes to the very *bottom* until midnight, dimmed, then returns to its normal position the next day. State lives in a separate per-widget [`DataStore`](app/src/main/java/com/example/tail/widget/WidgetPreferences.kt:1) (`recent_<id>` + `max1tap_<id>` + `expanded_<id>` keys) so multiple widget instances don't share state and the main app's settings are never modified. The collection adapter is [`HabitListRemoteViewsService`](app/src/main/java/com/example/tail/widget/HabitListRemoteViewsService.kt:1) which snapshots settings + per-widget prefs on every `notifyAppWidgetViewDataChanged()` call.

---

## Architecture

```
app/src/main/java/com/example/tail/
├── data/
│   ├── HabitModels.kt        # Data classes, HABIT_ORDER list, DEFAULT_CUSTOM_INPUT_HABITS
│   ├── HabitCalculator.kt    # Streak/antistreak/ATH calculations, display value adjustments
│   ├── HabitsRepository.kt   # JSON read/write via SAF URI (Gson), habit list builder
│   └── SettingsRepository.kt # DataStore Preferences (file URI, custom input set)
├── ipc/
│   ├── HabitsContentProvider.kt  # Read-only ContentProvider: exposes habit list to same-keystore apps
│   └── HabitIncrementReceiver.kt # BroadcastReceiver: increments a habit when triggered by same-keystore app
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

## IPC API (Inter-Process Communication)

*Added: 2026-03-12T20:12Z — Updated: 2026-03-12T21:52Z*

Tail exposes a secure IPC API so other apps you own (signed with the **same keystore**) can read the habit list and trigger increments without any user interaction.

### Security

A custom `signature`-level permission gates all IPC access:

```xml
android:name="com.example.tail.permission.TAIL_INTEGRATION"
android:protectionLevel="signature"
```

The calling app must declare `<uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />` in its manifest. Android will only grant it if both APKs share the same signing certificate.

**Important — Tail also holds its own permission.** Tail's manifest includes:
```xml
<uses-permission android:name="com.example.tail.permission.TAIL_INTEGRATION" />
```
This is required because the calling app passes the permission string as the `receiverPermission` argument to `sendBroadcast(intent, PERMISSION_TAIL)`. That argument tells Android to only deliver the broadcast to receivers that **hold** the permission. Since Tail declares the permission, it must also explicitly hold it via `<uses-permission>` — Android does not automatically grant a declaring app its own signature permission.

---

### 1. ContentProvider — Read the Habit List

**Authority:** `com.example.tail.provider`
**URI:** `content://com.example.tail.provider/habits`
**Columns:**

| Column | Type | Description |
|--------|------|-------------|
| `habit_id` | `Int` | 0-based index in the current display order |
| `habit_name` | `String` | Habit name as stored in the JSON database |

**Example (Kotlin):**

```kotlin
val uri = Uri.parse("content://com.example.tail.provider/habits")
val cursor = contentResolver.query(uri, null, null, null, null)
cursor?.use {
    while (it.moveToNext()) {
        val id   = it.getInt(it.getColumnIndexOrThrow("habit_id"))
        val name = it.getString(it.getColumnIndexOrThrow("habit_name"))
        Log.d("IPC", "[$id] $name")
    }
}
```

`insert`, `update`, and `delete` throw `UnsupportedOperationException` — the provider is read-only.

---

### 2. BroadcastReceiver — Increment a Habit

**Action:** `com.example.tail.ACTION_INCREMENT_HABIT`
**Extra:** `EXTRA_HABIT_ID` — the habit to increment (String name **or** Int index)

The receiver calls `HabitsRepository.incrementHabit()` which does an atomic read-modify-write on `habitsdb_phone.txt` for today's date, incrementing the count by 1.

**Example (Kotlin):**

```kotlin
// By name
val intent = Intent("com.example.tail.ACTION_INCREMENT_HABIT").apply {
    setPackage("com.example.tail")          // explicit target — required on Android 8+
    putExtra("EXTRA_HABIT_ID", "Flossed")
}
sendBroadcast(intent, "com.example.tail.permission.TAIL_INTEGRATION")

// By index (0-based)
val intent2 = Intent("com.example.tail.ACTION_INCREMENT_HABIT").apply {
    setPackage("com.example.tail")
    putExtra("EXTRA_HABIT_ID", 74)          // Int extra → resolved to habit name at that index
}
sendBroadcast(intent2, "com.example.tail.permission.TAIL_INTEGRATION")
```

> **Note:** Pass the permission string as the second argument to `sendBroadcast()` so Android enforces that only Tail can receive it (defence-in-depth on the sender side).

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
| Pushups               | count ÷ 30 (rounded) |
| Situps                | count ÷ 50 (rounded) |
| Squats                | count ÷ 30 (rounded) |
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

## Seeding daily locations from a Google Maps Timeline export *(added 2026-05-06T10:13Z)*

The app records one coarse "City, Region, Country" label per calendar day in the [`tail_location_prefs`](app/src/main/java/com/example/tail/data/LocationRepository.kt:17) SharedPreferences file (key `daily_locations`, value = JSON map of `YYYY-MM-DD` → label).  To backfill years of history from a Google Maps Timeline export, use [`scripts/seed_locations_from_timeline.py`](scripts/seed_locations_from_timeline.py:1).

```bash
source venv/bin/activate
python scripts/seed_locations_from_timeline.py \
    --timeline /path/to/Timeline.json
```

What the script does:
- Picks one representative coordinate per date from the export.  `visit` segments (Google's "you stayed here") win over `timelinePath` / `activity` / `rawSignals` points; ties are broken by dwell-time.
- Rounds coordinates to ~110 m and reverse-geocodes via OpenStreetMap Nominatim (1 req/sec, no API key, persistent disk cache in `scripts/_seed_locations_out/geocode_cache.json` so re-runs are free).
- Pulls the existing on-device prefs, **preserves** every entry already there, and only fills in dates the device doesn't have yet.  Pass `--overwrite-existing` to flip that behaviour, or `--no-merge-device` to ignore the device entirely.
- Pushes the merged XML back via `adb` + `run-as com.example.tail`, then `am force-stop`s the app so it re-reads SharedPreferences on next launch.
- Use `--dry-run` to generate `scripts/_seed_locations_out/{merged.json, tail_location_prefs.xml}` without touching the device.

Typical scale on a 12-year export: ~3,600 dated entries collapsing into ~700 unique geocode lookups (~12 min on a cold cache, ~1 s when the cache is warm).

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
