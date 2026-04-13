# Active Context — Tail

**Last updated:** 2026-04-13T21:11Z

## Recent Changes (as of 2026-04-13 21:11)

- **Fix: Voice activities use moveTaskToBack** (2026-04-13 21:11) — Activities now call `finish()` then `moveTaskToBack(true)` to push the empty task to the back immediately, preventing the current foreground app from being minimized. Combined with `taskAffinity=""`, `noHistory="true"`, and transparent theme in manifest.
- **Fix: Ready vibration now 150ms** (2026-04-13 21:08) — Increased `vibrateReady()` pulse from 80ms to 150ms for better noticeability. Added explicit `VIBRATE` permission to manifest.

## Recent Changes (as of 2026-04-13 20:44)

- **Ready vibration for voice features** (2026-04-13 20:44) — Added `vibrateReady()` (short 150ms pulse) to both `VoiceHabitService` and `VoiceNoteService`, fired in `onReadyForSpeech` callback so the user knows when the recognizer is listening and they can start speaking. Distinct from the confirmation vibrations (200ms single pulse for voice trigger, double-pulse waveform for voice note).

## Recent Changes (as of 2026-04-13 20:29)

- **Voice Note Dictation** (2026-04-13 20:29) — New feature allowing voice-dictated notes to be prepended to a markdown file, triggered via Samsung Routines:
  - **Settings section**: "📝 Voice Note Dictation" with enable toggle + SAF file picker for the notes.md file
  - **VoiceNoteActivity** (`VoiceNoteActivity.kt`): Transparent activity launched by Samsung Routines via App Shortcuts
  - **VoiceNoteService** (`ipc/VoiceNoteService.kt`): ForegroundService with SpeechRecognizer, 30-second timeout, prepends dictated text to file with `## YYYY-MM-DD HH:MM:SS` header, confirmation vibration (double pulse)
  - **App Shortcut**: "Voice Note" in `shortcuts.xml` for Samsung Routines discovery
  - **Data layer**: `voiceNoteEnabled`, `voiceNoteFileUri` in AppSettings + DataStore persistence
  - New files: `VoiceNoteActivity.kt`, `VoiceNoteService.kt`
  - Modified: `HabitModels.kt`, `SettingsRepository.kt`, `HabitViewModel.kt`, `SettingsScreen.kt`, `AndroidManifest.xml`, `shortcuts.xml`, `strings.xml`

- **Voice Trigger Habit Increment** (2026-04-13 19:13) — New feature allowing habits to be incremented via voice trigger words, activated through Samsung Routines:
  - **Global toggle** in Settings screen: "🎤 Voice Trigger" enable/disable section
  - **Per-habit toggle** in Edit Mode SETTINGS: "🎤 Voice Trigger" with ℹ info button + comma-separated trigger words input
  - **VoiceTriggerInfoDialog**: Complete step-by-step Samsung Routines setup instructions
  - **VoiceHabitReceiver** (`ipc/VoiceHabitReceiver.kt`): BroadcastReceiver for `ACTION_VOICE_HABIT`, exported without permission (Samsung Routines compatibility)
  - **VoiceHabitService** (`ipc/VoiceHabitService.kt`): ForegroundService with `FOREGROUND_SERVICE_TYPE_MICROPHONE`, SpeechRecognizer, wake lock, 8-second timeout, trigger word matching, habit increment with conditional propagation, Tasker file update, confirmation vibration
  - **VoiceTriggerActivity** (`VoiceTriggerActivity.kt`): Transparent activity launched by Samsung Routines via App Shortcuts — immediately starts VoiceHabitService and finishes
  - **App Shortcuts** (`res/xml/shortcuts.xml`): Static shortcut "Voice Trigger" so Samsung Routines can discover and use it as an app action
  - **Data layer**: `voiceTriggerEnabled`, `voiceTriggerHabits`, `voiceTriggerWords` in AppSettings + DataStore persistence
  - **Permissions**: `RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `WAKE_LOCK`
  - New files: `VoiceHabitReceiver.kt`, `VoiceHabitService.kt`, `VoiceTriggerActivity.kt`, `res/xml/shortcuts.xml`
  - Modified: `HabitModels.kt`, `SettingsRepository.kt`, `HabitViewModel.kt`, `SettingsScreen.kt`, `HabitGridScreen.kt`, `AndroidManifest.xml`, `strings.xml`

## Recent Changes (as of 2026-04-11 17:08)

- **Portrait orientation lock** (2026-04-11 17:08) — Locked the main habits screen, app stats screen, and settings screen to portrait mode:
  - Added `android:screenOrientation="portrait"` to `MainActivity` in `AndroidManifest.xml`
  - Updated `StreakGraphPopup.kt` to restore to `SCREEN_ORIENTATION_PORTRAIT` (instead of `UNSPECIFIED`) on dismiss, so graphs still open in landscape but return to portrait when closed
  - Modified: `AndroidManifest.xml`, `StreakGraphPopup.kt`

## Recent Changes (as of 2026-04-11 16:07)

- **App Stats UX improvements** (2026-04-11 16:07) — Three related changes to the stats/graph experience:
  1. **Settings screen**: Removed the "App Stats" button from the settings body. Added a bar chart icon (📊) to the Settings TopAppBar actions area for quick access.
  2. **Graph popup overhaul** (`StreakGraphPopup.kt`): Completely rewritten for a professional look:
     - Always forces fullscreen landscape orientation (restores on dismiss)
     - Removed time period preset buttons (1M/3M/6M/1Y/Max) — replaced with pinch-to-zoom (1×–20×) and drag-to-pan
     - Dark gradient background, glow effect on line, gradient fill under curve
     - Chart border, improved axis labels with `sans-serif-light` typeface
     - Y-axis labels auto-format large numbers (1K, 2.5K, 1.0M)
     - 7-day moving average shown as gold dashed line with legend
     - Data dots only shown when zoomed in enough (≤60 visible days)
     - Close button as icon (X) in top-right corner
     - Zoom level indicator shown when zoomed in
  3. **Cumulative points graph** (`AppStatsScreen.kt`): "Total habit points (all time)" in the Overview section is now clickable (gold color with 📈 icon). Opens a graph showing cumulative points over time. Added `dailyCumulativePoints` field to `AppStats` data class, computed as running sum of daily totals.
  - Modified: `SettingsScreen.kt`, `StreakGraphPopup.kt` (full rewrite), `AppStatsScreen.kt`

**Last updated:** 2026-04-10T12:42Z

## Recent Changes (as of 2026-04-10 12:42)

- **Chess.com + conditional habit bug fix** (2026-04-10 12:42) — Fixed `applyChessComData` in `HabitViewModel.kt` not triggering conditional linked habit increments when chess.com auto-updates a chess-type habit.
  - Root cause: `applyChessComData` wrote counts directly to the DB for chess-linked habits but never checked `conditionalHabits`/`conditionalLinkedHabits`, so the general chess habit was never updated.
  - Fix: after updating each chess-linked habit's count per date, track which dates transitioned from `0 → non-zero` (`datesActivated`). If the chess habit is a conditional habit, iterate its linked habits and increment each by 1 for those dates (respecting `maxOneHabits` cap).
  - This works correctly for both regular 15-min polling and the full backlog fetch (since backlog resets chess habits to 0 first, all active dates become `0 → non-zero`).
  - Modified: `HabitViewModel.kt` — `applyChessComData()` function only.

**Last updated:** 2026-04-10T02:17Z

## Recent Changes (as of 2026-04-10 02:17)

- **Screen-move dropdown** (2026-04-10 02:17) — Replaced the row of per-screen buttons in the edit mode control bar with a single `→ Screen ▾` dropdown (`DropdownMenu` / `DropdownMenuItem`). Tapping the button opens a scrollable list of all other screen names; selecting one moves the habit there. Eliminates the overflow problem when many screens exist.
  - Modified: `HabitGridScreen.kt` — added `moveToScreenExpanded` state var, replaced `otherScreenIndices.forEach { Button(...) }` with a `Box { Button + DropdownMenu }`.

**Last updated:** 2026-04-09T11:23Z

## Current State

The app is in active development with a mature feature set. The core habit grid, file I/O, IPC API, edit mode, and analytics screens are all implemented and functional. AI icon generation and chess.com integration are opt-in features.

## Recent Changes (as of 2026-04-09 11:23)

- **Color-tier border system** (2026-04-09 11:23) — Extended the habit color progression beyond the 7th tier (Glass/white):
  - Counts 0–6: unchanged (Red → Orange → Green → Blue → Pink → Yellow → Glass)
  - Counts 7–12: Glass background + vivid colored border cycling through Red → Orange → Green → Blue → Pink → Yellow
  - Count 13+: Glass background + Glass border (stays permanently)
  - Added `HabitStyle` data class (background + optional borderColor) and `getHabitStyle()` function in `HabitColors.kt`
  - `HabitButton.kt` applies tier border as a base layer; mode-specific borders (edit, info, graph, etc.) overlay on top
  - Bright border color variants defined separately from the dark background colors for visibility against Glass

## Recent Changes (as of 2026-04-09 00:07)

- **Chess.com Integration** (2026-04-09) — New opt-in feature that links habits to chess.com activity data:
  - Settings screen section with enable toggle, username input, minutes-per-increment for 5 activity types (Bullet, Blitz, Rapid, Puzzles Slow, Puzzles Rush)
  - `ChessComService.kt` — Low-level API client for chess.com public API (archives, games, stats)
  - `ChessComRepository.kt` — Data processing layer with monthly archive caching, per-day minutes computation, increment calculation
  - Edit mode panel: chess.com toggle with dropdown to select which activity type a habit tracks
  - Automatic polling every 15 minutes for current month data
  - "Fetch Entire Backlog" button in settings to retroactively fill habit history from all chess.com archives
  - Data cached in `files/chess_com_cache/` as JSON files per month
  - Habit counts are set (not incremented) — chess.com data is authoritative for linked habits
  - Uses `HttpURLConnection` with proper `User-Agent` header per chess.com API requirements

## Recent Changes (as of 2026-04-08 22:16)

- **AI Icon Long-Press Delete** (2026-04-08 22:16) — Replaced red ✕ overlay with long-press gesture for deleting AI icons:
  - AI icons now use `combinedClickable`: tap to select, long-press to trigger delete confirmation
  - Removed red ✕ overlay from each AI icon tile
  - Changed hint text from "tap ✕ to delete" to "long-press to delete"
  - Delete confirmation dialog still shows icon preview and prompt text

- **AI Icon Auto-Trim** (2026-04-08 21:26) — Added auto-trim to post-processing so generated icons fill the available space:
  - After converting to white-on-transparent, finds bounding box of non-transparent pixels
  - Crops to that bounding box with ~4% padding, makes it square to avoid distortion
  - Then scales to 64x64 — icons now appear much larger and more visible

- **AI Icon Background Fix** (2026-04-08 19:59) — Fixed white background issue on AI-generated icons:
  - Strengthened prompt to heavily emphasize pure black (#000000) background with explicit hex codes
  - Added auto-detection in post-processing: samples edge pixels to determine if background is light or dark
  - If model ignores prompt and generates white/light background, post-processing inverts logic (dark pixels → icon, light → transparent)
  - Result: white-on-transparent icons regardless of what the AI model produces

## Recent Changes (as of 2026-04-08 18:41)

- **AI Icon Generation** (2026-04-08) — New opt-in feature allowing users to generate habit icons via an OpenAI-compatible image generation API. Includes:
  - Settings screen section with toggle, API key, base URL, endpoint, model dropdown, quality dropdown
  - `AiIconRepository` stores generated icons as white-on-transparent PNGs in internal storage with JSON index
  - `AiIconGeneratorService` calls the API, post-processes images to white-on-transparent
  - Icon picker dialog shows AI-generated icons in a separate "🤖 AI Generated Icons" section with inline prompt input
  - Delete AI icons with confirmation popup showing icon preview
  - Model/quality dropdowns in settings with API model fetching (GET /v1/media/models)
  - `HabitButton` supports rendering file-based AI icons (icon IDs starting with "ai_")
  - INTERNET permission added to AndroidManifest.xml
  - All settings persisted in DataStore (enabled, apiKey, baseUrl, endpoint, model, quality)

## Recent Changes (as of 2026-04-04 13:08)

- **Hidden screens** (2026-04-04) — Screens can be toggled "hidden" via edit mode (no habit selected). A hidden screen's name is invisible in the tab bar when not active; when selected it shows normally. Persisted as `hiddenScreens: Set<String>` (screen IDs) in DataStore.
- **Screen reorder** (2026-04-04) — In edit mode, ◀/▶ buttons appear around the active screen tab to move it left/right. Uses `reorderScreen(fromIndex, toIndex)` in ViewModel.
- **Disabled habits** (2026-04-04) — Habits can be toggled "disabled" via edit mode (habit selected, in SETTINGS section). Disabled habits show a red ✕ overlay in the top-left corner of their grid cell. Their streak/anti-streak values are excluded from aggregate stats in AppStatsScreen. Anti-streak continues to grow even while disabled. A "Disabled habits" counter appears in the Overview section of App Stats when > 0.
- **IPC Tasker file fix** (2026-04-04) — `HabitIncrementReceiver` now writes `total_habits.txt` immediately after incrementing a habit via IPC broadcast.

## Recent Changes (as of 2026-03-28)

- **Conditional habit type** (2026-03-24) — Habits can be configured as "conditional" with linked habits that auto-increment when the conditional habit is tapped
- **Graphs screen** — Visual graph representations of habit data (941 lines)
- **App Stats screen** — Detailed analytics with rolling averages, all-time highs (1400 lines)
- **Share text integration** — ShareTextActivity allows sharing text from any app to a text-input habit
- **Dated entry repository** — Parses dated entry files (dream journal format) with file-size-based change detection
- **Text input repository** — Per-habit text log JSON files

## Current Focus

Based on open tabs and visible files, the current work appears focused on:
- `GraphsScreen.kt` — Graph visualizations (currently visible)
- IPC integration with Wags companion app
- Text input and share functionality

## Known Large Files (potential refactoring candidates)

| File | Lines | Notes |
|------|-------|-------|
| `HabitViewModel.kt` | 1,577 | Central ViewModel — exceeds 500-line guideline |
| `AppStatsScreen.kt` | 1,400 | Analytics screen — exceeds 500-line guideline |
| `GraphsScreen.kt` | 941 | Graph visualizations — exceeds 500-line guideline |

## Active Decisions

- JSON files (not Room/SQLite) for data storage — maintains desktop compatibility
- SAF for file access — works with Syncthing and any file provider
- Single ViewModel pattern — `HabitViewModel` handles all state
- Signature-level IPC — secure inter-app communication with Wags

## Open Considerations

- The three largest files significantly exceed the 500-line modularity guideline
- `HabitViewModel.kt` at 1,577 lines is the most critical candidate for decomposition
