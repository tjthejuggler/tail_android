# Progress — Tail

**Last updated:** 2026-03-28T22:18Z

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
