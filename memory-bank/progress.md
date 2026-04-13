# Progress — Tail

## 2026-04-13 21:11
- [x] Fix: Voice activities now call `moveTaskToBack(true)` after `finish()` to prevent minimizing the current foreground app
- [x] Fix: Ready vibration increased to 150ms, added explicit `VIBRATE` permission

## 2026-04-13 20:44
- [x] Ready vibration for voice features: added `vibrateReady()` method (80ms single pulse) to both `VoiceHabitService.kt` and `VoiceNoteService.kt`, called from `onReadyForSpeech` callback so user knows when to start speaking

## 2026-04-13 20:29
- [x] Voice Note Dictation feature: dictate notes by voice via Samsung Routines, prepended to a markdown file
  - New files: `VoiceNoteActivity.kt` (transparent activity for Samsung Routines), `VoiceNoteService.kt` (ForegroundService with SpeechRecognizer, 30s timeout, prepend to file with timestamp header, double-pulse vibration)
  - Modified: `HabitModels.kt` (2 new AppSettings fields: `voiceNoteEnabled`, `voiceNoteFileUri`)
  - Modified: `SettingsRepository.kt` (2 new DataStore keys + save methods)
  - Modified: `HabitViewModel.kt` (`saveVoiceNoteEnabled()`, `saveVoiceNoteFileUri()`)
  - Modified: `SettingsScreen.kt` ("📝 Voice Note Dictation" section with enable toggle + SAF file picker)
  - Modified: `AndroidManifest.xml` (VoiceNoteActivity + VoiceNoteService declarations)
  - Modified: `shortcuts.xml` (added "Voice Note" shortcut), `strings.xml` (shortcut labels)

## 2026-04-13 19:13
- [x] Voice Trigger Habit Increment feature: increment habits by speaking trigger words via Samsung Routines
  - New files: `VoiceHabitReceiver.kt` (BroadcastReceiver for `ACTION_VOICE_HABIT`), `VoiceHabitService.kt` (ForegroundService with SpeechRecognizer, 8s timeout, wake lock, trigger matching, confirmation vibration), `VoiceTriggerActivity.kt` (transparent activity for Samsung Routines app action), `res/xml/shortcuts.xml` (App Shortcuts for Samsung Routines discovery)
  - Modified: `HabitModels.kt` (3 new AppSettings fields: `voiceTriggerEnabled`, `voiceTriggerHabits`, `voiceTriggerWords`)
  - Modified: `SettingsRepository.kt` (3 new DataStore keys + migration + save methods)
  - Modified: `HabitViewModel.kt` (`saveVoiceTriggerEnabled()`, `toggleVoiceTrigger()`, `setVoiceTriggerWords()`)
  - Modified: `SettingsScreen.kt` (global "🎤 Voice Trigger" enable/disable section)
  - Modified: `HabitGridScreen.kt` (per-habit toggle + trigger words input + ℹ info button + `VoiceTriggerInfoDialog` with Samsung Routines setup instructions)
  - Modified: `AndroidManifest.xml` (`RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK` permissions + service/receiver/activity declarations + shortcuts meta-data)
  - Modified: `strings.xml` (shortcut label strings)
  - Architecture plan: `plans/voice-trigger-habit-increment.md`

## 2026-04-11 17:08
- [x] Portrait orientation lock for main screens (grid, settings, app stats)
  - Added `android:screenOrientation="portrait"` to `MainActivity` in `AndroidManifest.xml`
  - Updated `StreakGraphPopup.kt` `onDispose` to restore `SCREEN_ORIENTATION_PORTRAIT` instead of `UNSPECIFIED`
  - Graph popups still force landscape via `requestedOrientation` override, then return to portrait on dismiss
  - Modified: `AndroidManifest.xml`, `StreakGraphPopup.kt`

## 2026-04-11 16:07
- [x] App Stats UX improvements:
  - Moved "App Stats" button from settings body to a bar chart icon in the Settings TopAppBar
  - Rewrote `StreakGraphPopup.kt`: fullscreen landscape, pinch-to-zoom (1×–20×), drag-to-pan, professional dark theme with gradient fill, glow line, gold 7-day MA legend, auto-formatting Y labels
  - Made "Total habit points (all time)" clickable in AppStatsScreen Overview — shows cumulative points graph over time
  - Added `dailyCumulativePoints` to `AppStats` data class (running sum of daily totals)
  - Modified: `SettingsScreen.kt`, `StreakGraphPopup.kt`, `AppStatsScreen.kt`

## 2026-04-10 12:42
- [x] Bug fix: chess.com auto-increment not triggering conditional linked habits
  - Root cause: `applyChessComData()` in `HabitViewModel.kt` bypassed the conditional habit logic entirely
  - Fix: track `datesActivated` (dates where chess habit count went 0→non-zero), then propagate +1 to each conditional linked habit for those dates (respecting `maxOneHabits` cap)
  - Works for both regular polling and full backlog fetch
  - Modified: `HabitViewModel.kt` (`applyChessComData()` only)

## 2026-04-10 02:17
- [x] Screen-move dropdown: replaced per-screen buttons in edit mode control bar with a single `→ Screen ▾` dropdown. All screen names are listed in a `DropdownMenu`; selecting one moves the habit there. Fixes overflow when many screens exist.
  - Modified: `HabitGridScreen.kt` (`EditModeControlBar` — added `moveToScreenExpanded` state, replaced button loop with `Box { Button + DropdownMenu }`)

## 2026-04-09 11:23
- [x] Color-tier border system: after reaching Glass (count 6), counts 7–12 show Glass bg + vivid colored border cycling through Red→Orange→Green→Blue→Pink→Yellow. Count 13+ stays Glass+Glass border.
  - Modified: `HabitColors.kt` (added `HabitStyle` data class, `getHabitStyle()`, 7 bright border color constants)
  - Modified: `HabitButton.kt` (uses `getHabitStyle()`, applies tier border as base layer under mode borders)

## 2026-04-09 00:07
- [x] Chess.com Integration feature: opt-in feature linking habits to chess.com game/puzzle activity
  - New files: `ChessComService.kt` (API client), `ChessComRepository.kt` (data processing + caching)
  - Modified: `HabitModels.kt` (4 new AppSettings fields), `SettingsRepository.kt` (4 new DataStore keys + save methods), `SettingsScreen.kt` (chess.com settings section with enable/username/minutes-per-increment/backlog), `HabitGridScreen.kt` (chess.com toggle + type dropdown in edit mode), `HabitViewModel.kt` (chess.com state + polling + backlog + habit linking)
  - 5 activity types: Bullet, Blitz, Rapid, Puzzles (Slow), Puzzles (Rush)
  - Configurable minutes-per-increment for each type
  - Automatic 15-minute polling for current month data
  - "Fetch Entire Backlog" for retroactive history fill
  - Monthly archive caching in `files/chess_com_cache/`

## 2026-04-08 22:16
- [x] Replaced red ✕ delete overlay with long-press gesture on AI icons. Tap selects, long-press triggers delete confirmation dialog. Removed "tap ✕ to delete" hint, replaced with "long-press to delete". Uses `combinedClickable` from `ExperimentalFoundationApi`.

## 2026-04-08 21:26
- [x] Added auto-trim to AI icon post-processing: finds bounding box of non-transparent pixels, crops with ~4% padding, makes square, then scales to 64x64. Icons now fill the available space instead of being tiny with large margins.

## 2026-04-08 19:59
- [x] Fixed AI icon white background issue: strengthened prompt with explicit #000000 background emphasis, added auto-detection in post-processing that samples edge pixels to determine if background is light or dark, inverts logic accordingly so icons always come out white-on-transparent

## 2026-04-08 18:41
- [x] AI Icon Generation feature: opt-in feature for generating habit icons via OpenAI-compatible image generation API
  - New files: `AiIconRepository.kt` (icon storage/retrieval), `AiIconGeneratorService.kt` (API calls + post-processing)
  - Modified: `SettingsRepository.kt` (6 new DataStore keys), `HabitModels.kt` (6 new AppSettings fields), `SettingsScreen.kt` (AI settings section with toggle + config fields + model/quality dropdowns), `HabitViewModel.kt` (AI icon state + methods), `HabitGridScreen.kt` (icon picker with AI section + generate button + delete confirmation), `HabitButton.kt` (file-based AI icon rendering), `AndroidManifest.xml` (INTERNET permission)
  - Post-processing converts AI images to white-on-transparent PNGs (matching existing icon style)
  - Icons stored in `files/ai_icons/` with JSON index, shown in icon picker under "🤖 AI Generated Icons" section
  - Delete AI icons with confirmation popup showing icon preview
  - Model/quality dropdowns in settings with API model fetching

## 2026-04-04 13:08
- [x] Hidden screens feature: screens can be toggled "hidden" in edit mode (no habit selected). Hidden screen names are invisible in the tab bar when not active. Persisted as `hiddenScreens: Set<String>` in DataStore.
- [x] Screen reorder feature: ◀/▶ buttons in edit mode to move screens left/right in the tab bar. Uses `reorderScreen()` in ViewModel.
- [x] Disabled habits feature: habits can be toggled "disabled" in edit mode (habit selected, SETTINGS section). Disabled habits show red ✕ overlay. Excluded from streak/anti-streak aggregates in stats. Anti-streak continues growing while disabled. "Disabled habits" counter in App Stats Overview.

## 2026-04-04
- [x] Fixed: IPC habit increments (via `HabitIncrementReceiver`) now immediately update `total_habits.txt` (Tasker stats file). Root cause: `writeTaskerFile()` only existed in `HabitViewModel` and was never called from the broadcast receiver. Fix: added `writeTaskerFile()` helper directly in `HabitIncrementReceiver` that reads fresh DB from disk after increment and writes the stats file.


**Last updated:** 2026-03-31T18:57Z

## What Works

- ✅ 8×10 habit grid with 76+ habits in correct order
- ✅ Color-coded buttons (7 tiers based on daily count)
- ✅ 269 PNG icons from desktop project, tinted white
- ✅ Corner stats (ATH, streak/antistreak, longest streak)
- ✅ Custom input mode (numeric dialog for configured habits)
- ✅ SAF file access with persistent URI permissions
- ✅ Historical DB support (merge full history for stats)
- ✅ Settings screen (file config, per-habit toggles, screen management)
- ✅ Dark theme (always on)
- ✅ Edit mode (reorder, delete, change icon)
- ✅ Debounced reorder with instant visual feedback
- ✅ Instant habit color change (two-phase update)
- ✅ Delete habit (screen order only, JSON untouched)
- ✅ Add habit to JSON files
- ✅ Icon picker (6-column grid, 269 icons)
- ✅ Conditional habit type with linked auto-increment
- ✅ IPC API (ContentProvider + BroadcastReceiver)
- ✅ Signature-level permission security
- ✅ Day navigation (forward/backward)
- ✅ Multiple named screens/pages
- ✅ App Stats screen with rolling averages and ATH
- ✅ Graphs screen with visualizations
- ✅ Share text integration (system share sheet → text-input habit)
- ✅ Dated entry parsing (dream journal format)
- ✅ Text input per-habit log files
- ✅ Calendar picker dialog
- ✅ Subtyped habit type (per-subtype increment dialog, separate data file)
- ✅ Pullups habit seeded with historical data from Pullups.md
- ✅ Pullups subtypes: chinups, wide, pullups, dip, neutral

## What's Left / Known Issues

- ⚠️ `HabitViewModel.kt` (1,577 lines) exceeds modularity guideline
- ⚠️ `AppStatsScreen.kt` (1,400 lines) exceeds modularity guideline
- ⚠️ `GraphsScreen.kt` (941 lines) exceeds modularity guideline
- No automated tests beyond example stubs

## Architecture Decisions Made

| Decision | Rationale |
|----------|-----------|
| JSON files over Room/SQLite | Desktop compatibility with PyQt widget |
| SAF over direct file paths | Works with Syncthing, Obsidian, any provider |
| Single ViewModel | Simplicity — all habit state in one place |
| Gson for JSON | Lightweight, well-known, sufficient for flat JSON |
| DataStore for settings | Modern replacement for SharedPreferences |
| Signature permission for IPC | Secure same-developer app communication |
| Two-phase update | Instant UI feedback + background stat calculation |

## Evolution Timeline

| Date | Milestone |
|------|-----------|
| 2026-03-08 | Architecture design phase started |
| 2026-03-12 | IPC API added (ContentProvider + BroadcastReceiver) |
| 2026-03-24 | Conditional habit type added |
| 2026-03-26 | Integration guide updated |
| 2026-03-28 | Memory bank initialized |
| 2026-03-28 | Subtyped habit type added; Pullups habit seeded with subtype data |
| 2026-03-31 | Fixed anti-streak mismatch between App Stats count and streak graph "Current" value |
