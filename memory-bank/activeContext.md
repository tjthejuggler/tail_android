# Active Context — Tail

**Last updated:** 2026-04-08T19:25Z

## Current State

The app is in active development with a mature feature set. The core habit grid, file I/O, IPC API, edit mode, and analytics screens are all implemented and functional. AI icon generation was just added as an opt-in feature.

## Recent Changes (as of 2026-04-08 21:26)

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
