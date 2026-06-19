# Tail — Habit Tracker Android App

**Last updated:** 2026-06-19T10:00Z

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
- **Garmin health integration** *(added 2026-06-16T13:31Z, updated 2026-06-16T15:28Z, updated 2026-06-18T16:36Z)* — in Settings → **❤️ Garmin Integration**, configure your Garmin proxy URL and app token, then set thresholds for health metrics (VO2 Max, Fitness Age, Resting HR, HRV, Sleep Score). Link habits to Garmin metric types in edit mode. The app polls every 15 minutes and automatically increments habits when metrics meet or exceed your thresholds. Use "Fetch Entire Backlog" to retroactively fill historical data from the Garmin API. Use "Import Historic Data" to import data from a Garmin GDPR ZIP export processed by the desktop script. Use the "Test Connection" button to verify the full connection chain (proxy server, app token, Garmin API, and data availability) before enabling. Requires deploying the Python proxy (see `garmin_proxy/` directory).
- **Fix: "Don't affect points" now excluded from Tasker stats file + Recalculate button** *(fixed 2026-06-19T09:46Z, repaired 2026-06-19T09:53Z)* — the Tasker stats relay file (`today` / `avg7` / `avg30` in the file set under Settings) was summing **all** habits, ignoring the per-habit "Don't affect points" (`noPointsHabits`) setting. This caused Garmin-imported habits to inflate the relayed totals even though they were marked as not affecting points. Root cause: the four `writeTaskerFile()` implementations ([`HabitViewModel`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:423), [`HabitIncrementReceiver`](app/src/main/java/com/example/tail/ipc/HabitIncrementReceiver.kt:171), [`VoiceHabitService`](app/src/main/java/com/example/tail/ipc/VoiceHabitService.kt:516), [`SmartVoiceService`](app/src/main/java/com/example/tail/ipc/SmartVoiceService.kt:627)) each re-implemented the total and none applied `noPointsHabits` (unlike the in-app stats in [`computeAppStats`](app/src/main/java/com/example/tail/ui/AppStatsScreen.kt:1035) / [`getDailyTotals`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:537), which already excluded them). Fix: a single shared [`buildTaskerStatsContent()`](app/src/main/java/com/example/tail/data/HabitCalculator.kt:253) helper now computes the file content, skips `noPointsHabits`, and is called by all four sites. **Note on data integrity:** the in-app point totals were never actually corrupted in storage — they already excluded `noPointsHabits` and recompute on the fly; only the historic Garmin import stored raw metric values (steps ≈ 16M, distance ≈ 21M, etc.) under their own habit names, which inflated *only* the Tasker file's averages. A new **🔄 Recalculate Stats File Now** button in `Settings → Tasker Stats File` (calls [`HabitViewModel.refreshTaskerStatsFile()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:457)) lets you rewrite the file on demand without waiting for the next increment.
- **Chess.com integration** *(added 2026-03-12T20:12Z)* — in Settings → **♟ Chess.com Integration**, enter your username and set minutes-per-increment for Bullet/Blitz/Rapid games. Link habits to game types in edit mode. The app polls every 15 minutes and automatically increments habits based on your chess activity. Use "Fetch Entire Backlog" to retroactively fill historical data.
- **DataStore habit-name migration** *(added 2026-03-29T03:02Z)* — one-time migration renames legacy "Launch Pushups/Situps/Squats Widget" to "Pushups"/"Situps"/"Squats" across all persisted DataStore keys (custom input set, habit order, screens, icon maps, dividers, etc.); runs automatically on first launch after update; guarded by a boolean flag so it only executes once
- **World-map "where I was" timeline** *(added 2026-05-06T14:05Z)* — small globe icon next to ⚙️ in the top bar opens a landscape map screen. Shows continents drawn from a 75 KB Natural Earth polygon asset (`assets/world_land.json`), plus the dim trail of every day with a known location. A small person marker animates between days as the timeline progresses. Bottom timeline has a draggable slider, ⏸/▶ play button, and `« / »` speed buttons (0.5×, 1×, 2×, 5×, 15×, 30×, 60×, 120×, Auto). Side info box shows location label + habits-done / streak / total-points for the selected day. Selected date is shared bidirectionally between the grid and map screens via `HabitViewModel.selectedDate`, so navigation in either direction preserves the day. Coordinates source: `LocationRepository.daily_coords` SharedPrefs key, populated by today's GPS fix or back-filled by [`scripts/seed_locations_from_timeline.py`](scripts/seed_locations_from_timeline.py:1) from a Google Maps Timeline export.
- **Secondary locations** *(added 2026-05-24T19:41Z, updated 2026-05-24T19:50Z)* — each time the app is opened (foregrounded), the current GPS position is logged as a **secondary location** for that day. These are stored alongside the main daily location in [`LocationRepository`](app/src/main/java/com/example/tail/data/LocationRepository.kt:1) (SharedPrefs key `secondary_locations`, date → JSON array of `{lat, lon, label, time}`). Only unique labels are kept per day (opening the app from the same place twice doesn't duplicate). Labels are derived from the preferred auto-candidate index (same method as the main daily location). Each secondary location also records the **time of day** (minutes since midnight) when it was logged.
- **Info mode merged into stats mode** *(added 2026-06-03T07:26Z)* — the standalone info mode (ℹ button) has been removed. Habit information panels are now shown underneath the graph in stats mode (📊), one for each selected habit. The stats panel remains half-screen height and is scrollable to accommodate multiple info sections.
  - **Analog clock** — a small analog clock overlay on the world map shows the time of the current secondary location. When a day has secondary locations, the clock hands point to the time of the last visited secondary location. When a day has no secondary locations, the clock hands spin rapidly. During Auto playback, the clock advances through each secondary location's time as the marker visits them.
  - **"All" checkbox** — toggles between showing all locations (primary + secondary) and primary-only mode. When unchecked, secondary dots are hidden, the clock spins, and playback skips secondary traversal — each day gets exactly one location.
  - **Manual entry ("+" button)** — tap the "+" button on the map to open a dialog where you can paste a Google Maps address or place name. The app forward-geocodes it via [`geocodeLocationLabel()`](app/src/main/java/com/example/tail/data/LocationRepository.kt:488) and adds it as a secondary location for the selected day. You can also set the time of visit (HH:MM).
  - During Auto playback, the marker traverses secondary locations at the current speed (quick traversal) before advancing to the next day — only novel **main** locations trigger the slow pause, keeping the same behaviour as before.
- **Full backup & restore** *(added 2026-05-08T17:12Z)* — `Settings → Backup & Restore` exports every piece of user-entered data (app settings, advice items, location history + ignored countries, debug saved-notes, habits database, habit timestamps, AI icons, per-habit text logs, dated-entry sources, subtype data, timed sessions, voice-note markdown) into a single self-contained JSON file via SAF `CreateDocument`. The matching **Import Backup** button reads the file and OVERWRITES every persistent data source on this device after a confirmation dialog. Implemented in [`BackupManager`](app/src/main/java/com/example/tail/data/backup/BackupManager.kt:1) with the wire format defined in [`BackupModels`](app/src/main/java/com/example/tail/data/backup/BackupModels.kt:1) (`schemaVersion` + `magic` marker for forward compatibility). AI-icon PNGs are embedded as base64. Per-habit external file content is embedded by habit name and re-written through the original SAF URIs (best-effort if still granted). Tasker / screens-relay / chess.com cache are deliberately skipped because they auto-regenerate.
- **Database-wipe protections & automatic daily backups** *(added 2026-05-14T05:43Z)* — in response to a near-total database wipe where a transient Syncthing/SAF load failure caused the app to overwrite `habitsdb.txt` with an empty skeleton, the file I/O layer was hardened and a once-per-day automatic backup was added. Root-cause fix in [`HabitsRepository`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:1):
    - New [`HabitsLoadResult`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:24) sealed type distinguishes `Success` / `UriNotReadable` / `ParseFailure` / `IoFailure` so callers can tell "file is genuinely empty" from "load failed".
    - [`ensureDaysExist`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:178) and [`incrementHabitForDate`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:278) now throw [`HabitsLoadFailedException`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:56) instead of silently writing a skeleton on top of a failed load. The existing `try/catch` blocks in [`HabitViewModel.onAppForegrounded`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:1941) and [`catchUpAndLoad`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:333) naturally preserve the cached in-memory DB on failure.
    - [`saveDatabase`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:130) gained an **anti-shrinkage guard**: if the on-disk file has > 50 entries and the new payload has fewer than half as many entries, the write is BLOCKED and logged loudly. Last line of defence against ever wiping a healthy DB.
    - Once-per-day automatic backup runs on `ON_START` via [`AutoBackupManager`](app/src/main/java/com/example/tail/data/backup/AutoBackupManager.kt:1) BEFORE any habit DB read/write, so the on-disk state is captured in its sync-stable pre-launch form. File name: `tail_auto_backup_YYYY-MM-DD.json` in a user-picked SAF tree folder (typically a Syncthing-synced folder so backups propagate to PC automatically). The UI for picking the folder, viewing every existing backup with size + date, and deleting old backups is in [`AutoBackupSection`](app/src/main/java/com/example/tail/ui/AutoBackupSection.kt:1) inside `Settings → Backup & Restore`. Old backups are NEVER auto-deleted — the user prunes them manually. Manual "Back up now" button is also available for testing or mid-day backups.
- **Lock-screen habit list widget** *(added 2026-05-09T12:10Z)* — placeable on the lock screen (or home screen as a fallback). Starts in a slim **collapsed bar** that says *"Tap to open habits"*. A **single tap arms** it (background turns orange, label changes to *"Tap again to open habits"*); a **second tap within 1.5 s expands** it. If the user does nothing, an [`AlarmManager`](app/src/main/java/com/example/tail/widget/HabitListWidgetProvider.kt:380) auto-disarm reverts the bar to the safe collapsed state — so pulling the phone out of a pocket can never accidentally increment anything. Expanded view is a **scrollable [`ListView`](app/src/main/res/layout/widget_expanded.xml:1)** of every habit (icon + name, ~8 visible per panel, dark themed). Tapping a normal habit increments by 1 in the background; tapping a **text-input habit** launches [`WidgetInputActivity`](app/src/main/java/com/example/tail/widget/WidgetInputActivity.kt:1) — a transparent trampoline that shows the same [`TextInputDialog`](app/src/main/java/com/example/tail/ui/TextInputDialog.kt:1) as the main app, then saves the entry + increments the habit. Smart **widget-local ordering**: any habit you tap from the widget jumps to the top; an exception is **max-one habits** (binary done/not-done): when you tap one of those it goes to the very *bottom* until midnight, dimmed, then returns to its normal position the next day. State lives in a separate per-widget [`DataStore`](app/src/main/java/com/example/tail/widget/WidgetPreferences.kt:1) (`recent_<id>` + `max1tap_<id>` + `expanded_<id>` keys) so multiple widget instances don't share state and the main app's settings are never modified. The collection adapter is [`HabitListRemoteViewsService`](app/src/main/java/com/example/tail/widget/HabitListRemoteViewsService.kt:1) which snapshots settings + per-widget prefs on every `notifyAppWidgetViewDataChanged()` call.

---

## Architecture

```
app/src/main/java/com/example/tail/
├── data/
│   ├── HabitModels.kt        # Data classes, HABIT_ORDER list, DEFAULT_CUSTOM_INPUT_HABITS
│   ├── HabitCalculator.kt    # Streak/antistreak/ATH calculations, display value adjustments
│   ├── HabitsRepository.kt   # JSON read/write via SAF URI (Gson), habit list builder
│   ├── LocationRepository.kt # GPS location fetch, reverse-geocode, daily + secondary location storage
│   ├── ChessComRepository.kt # Chess.com API client, game history caching, minutes→increments conversion
│   ├── ChessComService.kt    # Low-level HTTP client for chess.com public API
│   ├── GarminRepository.kt  # Garmin health metrics client, monthly caching, threshold→increments conversion, historic data import
│   └── SettingsRepository.kt # DataStore Preferences (file URI, custom input set, chess.com/garmin settings)
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

## Garmin Integration Setup *(updated 2026-06-16T14:07Z)*

The Garmin integration automatically syncs your health metrics from Garmin Connect to your habits. It runs a Python proxy on your local computer that fetches data from Garmin and makes it available to your Android app over Wi-Fi.

### Quick Start (Automatic Setup)

**1. Start the Python Proxy on your computer**

First, generate a secure app token (this is essentially a password you choose):

```bash
# Option 1: Use openssl (Linux/macOS)
openssl rand -hex 16

# Option 2: Use Python
python -c "import secrets; print(secrets.token_hex(16))"

# Option 3: Just pick a long random string
# Example: "my-secret-garmin-token-abc123xyz"
```

Then start the proxy with your Garmin credentials and the generated token:

```bash
cd garmin_proxy
pip install -r requirements.txt
export GARMIN_EMAIL="your.email@example.com"
export GARMIN_PASSWORD="your_garmin_password"
export ANDROID_PROXY_KEY="paste-your-generated-token-here"
python app.py
```

The proxy will start on `http://0.0.0.0:8000` and listen for connections from your phone.

**2. Find your computer's IP address**

- **Linux**: `ip addr show | grep "inet " | grep -v 127.0.0.1`
- **macOS**: `ipconfig getifaddr en0`
- **Windows**: `ipconfig | findstr "IPv4"`

Example output: `192.168.1.100`

**3. Configure the Android app**

1. Open Settings → Garmin Integration
2. Enable Garmin Integration
3. Enter your proxy URL: `http://192.168.1.100:8000` (use your actual IP)
4. Enter the app token: **paste the same token you generated in step 1**
5. Set thresholds for each metric (e.g., VO2 Max ≥ 45 = 1 point)

**4. Link habits to Garmin metrics**

1. Go to Edit Mode on the main screen
2. Select a habit
3. Choose a Garmin metric type from the dropdown
4. The habit will now auto-increment based on your Garmin data

### How It Works

- **Automatic Sync**: The app polls your proxy once a day while Garmin integration is enabled. By default, it fetches **yesterday's** data (today's data is incomplete since the day hasn't finished) *(updated 2026-06-19T07:02Z)*
- **On-Demand Sync**: Clicking "Test Connection" in Settings runs a full connection health check and, on success, immediately fetches and applies yesterday's Garmin data to linked habits *(updated 2026-06-19T07:02Z)*
- **Backlog Sync**: Use "Fetch Entire Backlog" to import up to 2 years of historical data from the Garmin API
- **Historic Data Import**: Use "Import Historic Data" to import data from a Garmin GDPR ZIP export. This is useful for filling in your history with past data that may not be available through the Garmin API *(added 2026-06-18T16:36Z)*
- **Threshold System**: Each day where your metric meets or exceeds the threshold = 1 increment
- **Local Network Only**: Your Garmin credentials stay on your computer; the app only receives processed metrics
- **Secure**: The app token prevents unauthorized access - only your phone with the correct token can access your data

### Available Metrics

| Metric | Description | Typical Threshold |
|--------|-------------|-------------------|
| **VO2 Max** | Cardiovascular fitness score | 45-55 |
| **Fitness Age** | Biological age based on fitness level | ≤ your actual age |
| **Resting HR** | Resting heart rate in BPM | ≤ 60 |
| **HRV Last Night** | Heart rate variability from last night | ≥ 50 ms |
| **HRV Weekly Avg** | Average heart rate variability over 7 days | ≥ 50 ms |
| **Sleep Score** | Overall sleep quality score (0-100) | ≥ 80 |
| **Steps** | Daily step count | ≥ 10,000 |
| **Altitude Ascent** | Total elevation climbed in meters | ≥ 100 |
| **Distance** | Total distance traveled in meters | ≥ 5,000 |
| **Calories** | Total calories burned | ≥ 2,000 |
| **Active Minutes** | Total active minutes | ≥ 30 |
| **Floors Climbed** | Total floors climbed | ≥ 10 |
| **Min HR** | Minimum heart rate in BPM | ≤ 50 |
| **Max HR** | Maximum heart rate in BPM | ≤ 180 |
| **Stress Level** | Average daily stress level (0-100) | ≤ 50 |

### Troubleshooting

**No data appearing for linked habits?** The Garmin integration fetches data from Garmin's training status API. If Garmin rate-limits your account (HTTP 429), data won't be available until the limit expires. Fixed data extraction paths *(updated 2026-06-18T18:20Z)*:
- VO2max: `mostRecentVO2Max.generic.vo2MaxValue`
- Resting HR: `stats_and_body.restingHeartRate`
- HRV last night: `sleep_data.avgOvernightHrv`
- Sleep score: `sleep_data.dailySleepDTO.sleepScores.overall.value`
- Fitness age: `get_my_fitness_age().fitnessAge` (dedicated microservice)
- HRV weekly avg: `get_hrv_data().hrvSummary.weeklyAvg` with fallback to baseline history calculation
- Steps: `stats_and_body.steps`
- Altitude ascent: `stats_and_body.elevationGain`
- Distance: `stats_and_body.distance`
- Calories: `stats_and_body.calories`
- Active minutes: `stats_and_body.activeMinutes`
- Floors climbed: `stats_and_body.floorsClimbed`

**Connection Issues:**
- Ensure your phone and computer are on the same Wi-Fi network
- Check that the Python proxy is running (`python app.py`)
- Verify the IP address is correct
- Try accessing `http://YOUR_IP:8000/health` in a browser on your phone

**403 Forbidden:**
- Check that the app token in Settings matches the `ANDROID_PROXY_KEY` environment variable
- Remember: YOU create this token - it's not generated for you
- Restart the proxy after changing environment variables

**No Data Showing:**
- Verify your Garmin credentials are correct
- Ensure you have recent data in Garmin Connect (sync your Garmin device first)
- Check the proxy logs for errors

**App Won't Connect:**
- Android may block cleartext HTTP by default. The app includes a network security config that allows local network HTTP
- If still blocked, try using HTTPS (requires SSL certificate setup)

### Running the Proxy as a Service (Linux)

To keep the proxy running in the background as a user service *(updated 2026-06-18T07:49Z)*:

```bash
# Create a systemd user service file
nano ~/.config/systemd/user/garmin-proxy.service
```

Add this content (replace paths with your actual paths):
```ini
[Unit]
Description=Garmin Proxy for Tail App
After=network.target

[Service]
Type=simple
WorkingDirectory=/home/twain/AndroidStudioProjects/tail/garmin_proxy
Environment="GARMIN_EMAIL=your.email@example.com"
Environment="GARMIN_PASSWORD=your_garmin_password"
Environment="ANDROID_PROXY_KEY=paste-your-generated-token-here"
ExecStart=/bin/sh -c 'cd /home/twain/AndroidStudioProjects/tail/garmin_proxy && /home/twain/AndroidStudioProjects/tail/garmin_proxy/venv/bin/uvicorn app:app --host 0.0.0.0 --port 8000'
Restart=on-failure
RestartSec=10

[Install]
WantedBy=default.target
```

Then:
```bash
systemctl --user daemon-reload
systemctl --user enable garmin-proxy
systemctl --user start garmin-proxy
systemctl --user status garmin-proxy
```
```bash
sudo systemctl daemon-reload
sudo systemctl enable garmin-proxy
sudo systemctl start garmin-proxy
```

---

## Importing Historic Garmin Data *(added 2026-06-18T16:36Z)*

If you have a Garmin GDPR export ZIP file with your historical health data, you can import it directly into the app to fill in your history with past data.

### Step 1: Process the Garmin ZIP Export

Use the provided Python script to extract and convert your Garmin data:

```bash
python garmin_import.py /path/to/Garmin_Export.zip
```

This will create a `garmin_import.json` file containing all your daily health metrics in a format compatible with the app.

**Supported Metrics:**
- VO2 Max
- Fitness Age
- Resting Heart Rate
- HRV Last Night
- HRV Weekly Average (computed from 7-day rolling average)
- Sleep Score
- Sleep Duration (minutes)
- Sleep Stages (Deep, Light, REM, Awake minutes)
- Steps
- Altitude Ascent (meters)
- Distance (meters)
- Calories
- Active Minutes
- Floors Climbed

### Step 2: Import into the App

1. Transfer the `garmin_import.json` file to your Android device
2. Open Settings → Garmin Integration
3. Tap the **"Import Historic Data"** button
4. Select the JSON file from your device storage
5. The app will process the file and apply the data to your linked habits

**Note:** The import merges with existing data - it won't overwrite days that already have Garmin data from the API. If you want to completely refresh your Garmin history, clear the Garmin cache first (this is done automatically when using "Fetch Entire Backlog").

### Timezone Handling

The import script uses the Europe/Dublin timezone by default. If you're in a different timezone, edit the `TARGET_TZ` constant in `garmin_import.py`:

```python
TARGET_TZ = ZoneInfo("Your/Timezone")  # e.g., "America/New_York"
```

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
