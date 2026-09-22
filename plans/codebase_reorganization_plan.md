# Tail Codebase Reorganization Plan

_Created 2026-09-22 — goal: make the codebase tractable for both humans and LLM-assisted editing._

## Why LLM edits are slow today (diagnosis)

Measured on 2026-09-22:

| Metric | Value |
|---|---|
| Kotlin source lines (app + core-data) | **145,534** |
| Kotlin files | 318 |
| Files over 500 lines | **82** |
| Largest file ([`HabitGridScreenSections.kt`](../app/src/main/java/com/example/tail/ui/HabitGridScreenSections.kt)) | **4,255 lines** |
| Lines in flat `ui` package | 73,678 across 106 files (no sub-packages) |
| HabitViewModel concept | **~14,000 lines across 16 files** |

Five compounding causes:

1. **Monster files.** A 4,000-line file costs ~40K tokens to read and ~40K+ to rewrite.
   A one-line change requires loading and re-emitting the whole file — slow, expensive,
   and truncation-prone. The top 10 files alone are ~30K lines.
2. **The god class.** [`HabitViewModel.kt`](../app/src/main/java/com/example/tail/ui/HabitViewModel.kt:208)
   holds meals, search, weights, notifications, vision memory, Garmin polling, chess,
   movies, sleep, locations, voice… and 15 satellite files
   (`HabitViewModelMeals.kt`, `HabitViewModelMovies.kt`, …) bolt features on via
   extension functions over `internal val` mutable state. To change *anything* safely,
   an LLM must read the main file **plus** every satellite that touches the same state.
   The 2026-08-29 split treated file size (IR-lowering symptom), not structure.
3. **Flat 106-file `ui` package.** Structure discovery means grepping/scanning dozens
   of unrelated files per edit; filename prefixes are the only organization.
4. **Root-directory noise.** ~40 loose scripts, one-off `diagnose_*.py`/`repair_*.py`
   files, data JSONs (`sauna_history.json`, `hatice_backup.json` — git-tracked), spec
   MDs, and supervisor files sit next to `gradlew`, polluting every fresh session's
   file listing and search results.
5. **No composition root.** No DI framework; `HabitViewModel` takes 9 constructor
   params with `LocationRepository(context)` default-constructed inside — wiring is
   implicit, so an LLM must trace construction by hand to know what depends on what.

Target state: **≤ ~500 lines per file, ≤ ~15 files per package, one concept per file,
explicit feature-owned state.**

---

## Phase 0 — Repo hygiene (1–2 h, near-zero risk, immediate payoff)

Move, don't refactor. Update `.gitignore` while at it.

| Items | Destination |
|---|---|
| `diagnose_*.py`, `debug_*.py`, `test_garmin_*.py`, `test_zai_vision.py` | `scripts/diagnostics/` |
| `garmin_fetcher.py`, `garmin_import.py`, `garmin_meditation_extract.py`, `import_meditation_data.py`, `parse_dreams.py` | `scripts/ingest/` (add README pointers) |
| `sauna_history*.json`, `hatice_backup.json`, `dream_recorded_history.json`, `garmin_cache.json`, `garmin_import.json`, `garmin_fetcher_state.json`, `meditation_output.json` | `archive/data/` + `git rm --cached` (they are tracked backups; keep local, untrack) |
| `SECONDARY_VALUE_WAGS_SPEC.md`, `APNEA_SECONDARY_VALUE_WAGS_SPEC.md`, `RESONANCE_SECONDARY_VALUE_WAGS_SPEC.md`, `JUGCOACH_SESSION_SPEC.md` | `plans/specs/` |
| `tail_supervisor.py`, `tail_services.toml`, `install_supervisor.sh`, `tail-supervisor.service` | `supervisor/` |
| `supervisor.log`, `.lsp_mcp.port`, `*.local.json` | add to `.gitignore` |
| `zai_API_KEY.txt` | move outside the repo (already git-ignored, but a key in a workspace is one `git add -f` away from a leak) |
| `DESKTOP_SERVICES.md`, `garmin_*_README.md` | merge links into root `README.md` or keep beside their code in `garmin_proxy/` |

Also: `app/src/main/java/com/example/tail/data/SmartOpenStore.kt` is a data-layer
class living in the `app` module while everything else is in `core-data` — move it.

## Phase 1 — Package the `ui` package (mechanical, half a day)

Pure file moves + package/import line updates. No logic changes. Compile after each
batch. Suggested sub-packages (all 106 files map cleanly by prefix):

| New package | Files (by current name prefix) |
|---|---|
| `ui.grid` | `HabitGrid*`, `HabitButton`, `HabitAskFlash`, `MovieConfirmFlash`, `MovieMinutesEditor`, `IncrementDialog`, `SubtypeIncrementDialog`, `WeightsInputDialog`, `CalendarPickerDialog`, `HabitIncrementConfirmActivity`, `HabitIncrementToast` |
| `ui.chess` | `ChessReadiness*` (10 files), `ChessReflexSections` |
| `ui.stats` | `AppStatsScreen`, `MapStatsScreen`, `GraphsScreen`, `StreakGraphPopup`, `SleepTimelineChart` |
| `ui.settings` | `SettingsScreen`, all `*SettingsSection`, `*SettingsDialog`, `SnapshotRestoreSection`, `TextInputOptionsEditorDialog` |
| `ui.meals` | `MealDetailScreen`, `MealEditorContent`, `MealSettingsSection`, `VisionMemorySection` |
| `ui.advice` | `Advice*` (4 files) |
| `ui.loading` | `HabitLoading*` (6 files) |
| `ui.map` | existing sub-package: fold `MapScreen`, `LocationEditDialog` in |
| `ui.common` | `SteelPanel`, `WheelPicker`, `MarkdownText`, `HabitColors`, `PointTierColors`, `RepeatIconButton`, `TimestampEditorDialog`, `QuickTimestampEditorDialog`, `TextInputDialog`, `SectionExpansionStore`, `SpeechRecognition`, `HabitsDataChangedBus`, `VoiceNoteBus`, `VoiceTranscriptBus`, `HabitHaptics` |
| `ui.viewmodel` | all 16 `HabitViewModel*` files (Phase 2 dismantles them) |

Do the same pass on `core-data` `data/` (78 files): `data.chess`, `data.health`
(Garmin/sleep), `data.media` (IMDb/Spotify/iTunes/TextIcon), `data.movie`,
`data.sources` (GitHub/ChessCom), `data.habit` (repos/models/calculators),
`data.settings`. Keep the existing `backup/`, `meal/`, `ai/`, `assist/`, `debug/`.

Mirror the structure under `app/src/test` (54 test files currently in one flat package).

## Phase 2 — Dismantle the HabitViewModel god class (the big win, 1–2 weeks incremental)

Replace "one class + 15 extension files sharing `internal var` state" with
**per-feature state holders** (Android's recommended state-holder pattern). Each
feature owns its `StateFlow`s and repos; `HabitViewModel` composes them and exposes
only cross-feature glue:

```
class MealsStateHolder(repo: VisionRepository, scope: CoroutineScope) {
    val queueItems: StateFlow<List<VisionQueueItem>>
    val todayCalories: StateFlow<Int>
    fun saveMealSettings(...) { ... }
}
class HabitViewModel(meals: MealsStateHolder, search: SearchStateHolder, ...) : ViewModel()
```

Order of extraction (smallest blast radius first, compile + run tests after each):

1. `SearchStateHolder` (query/filters/results — self-contained, fields at
   [`HabitViewModel.kt:307-392`](../app/src/main/java/com/example/tail/ui/HabitViewModel.kt:307))
2. `GarminStateHolder` (from `HabitViewModelGarmin.kt`, 1,016 lines)
3. `MoviesStateHolder` (`HabitViewModelMovies.kt`, 1,337 lines)
4. `MealsStateHolder` (`HabitViewModelMeals.kt`, 803 lines + meal state in main file)
5. `ChessStateHolder`, `SleepStateHolder`, `LocationsStateHolder`, `VoiceStateHolder`, …

As each moves: state fields become `val` in the holder, extension functions become
methods, `internal` visibility disappears. Delete the satellite file when empty.

## Phase 3 — Split the monster composables (rolling, alongside features)

One public composable per file, helpers `private` in that file:

- [`HabitGridScreenSections.kt`](../app/src/main/java/com/example/tail/ui/HabitGridScreenSections.kt)
  (4,255 lines, **27 composables**) → `ui/grid/sections/` one file per section.
- [`SettingsScreen.kt`](../app/src/main/java/com/example/tail/ui/SettingsScreen.kt)
  (3,417 lines, 26 composables) → one file per settings section (the `*Section.kt`
  pattern already exists — extend it).
- [`FloatingBubbleService.kt`](../app/src/main/java/com/example/tail/widget/FloatingBubbleService.kt)
  (3,277 lines) → extract chess/game reconciliation and overlay rendering into
  plain classes (follow the existing
  [`ChessDeferredGameReconciler.kt`](../app/src/main/java/com/example/tail/widget/ChessDeferredGameReconciler.kt)
  precedent); service keeps only Android lifecycle plumbing.
- [`MediaCaptureActivity.kt`](../app/src/main/java/com/example/tail/MediaCaptureActivity.kt)
  (2,369 lines, root package) → move to `capture/` package, extract camera/vision logic.
- `HabitGridScreen.kt`, `MapScreen.kt`, `AppStatsScreen.kt`, `GraphsScreen.kt`,
  `ChessReadinessStatsScreen.kt` → same treatment.
- [`SettingsRepository.kt`](../core-data/src/main/java/com/example/tail/data/SettingsRepository.kt)
  (1,958 lines) → split into typed settings sections backed by one DataStore/SharedPreferences.
- [`HabitModels.kt`](../core-data/src/main/java/com/example/tail/data/HabitModels.kt)
  (1,869 lines) → split models by feature area.

## Phase 4 — Composition root (half a day, after Phase 2 starts)

Add a manual-DI `AppContainer` created in [`TailApplication.kt`](../app/src/main/java/com/example/tail/TailApplication.kt):
repos + state holders constructed once, passed to ViewModels via
`viewModelScope`-safe factory. Kills the 9-arg constructor and the
`LocationRepository(context)` default-init smell. (Full Hilt optional — manual
container is enough and dependency-free.)

## Phase 5 — Guardrails so it stays good (1 h)

- `scripts/check_file_size.sh`: fail if any `.kt` > 800 lines or any package > 20 files; run before commits (and keep `scripts/split_large_kt_files.py` for the mechanical part).
- Convention, written into `README.md`: **vertical slices** — `ui/<feature>/` holds the screen, its sections, and its state holder; shared widgets go to `ui/common/` only when used by ≥ 2 features; data layer mirrors in `core-data`.
- Rule for future LLM work: when a file crosses ~600 lines during a feature, split it *in the same task*.

## Sequencing & risk notes

- Phases 0–1 are pure moves: do them in one sitting, `./gradlew :app:compileDebugKotlin :core-data:compileDebugKotlin` after each batch. Use `git mv` to preserve history.
- Phase 2 is behavior-preserving refactoring; the 54 existing tests are the safety net. One feature per commit.
- Expect LLM edit speed to improve immediately after Phase 0/1 (smaller context per edit), and dramatically after Phase 2/3 (feature-scoped files mean an LLM reads 1–3 small files instead of scanning 16 satellites + a 4K-line hub).
- Do **not** create more `*Extensions.kt` satellites as a stopgap — that pattern is what produced the current worst-of-both-worlds state.
