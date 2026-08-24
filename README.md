# Tail — Habit Tracker Android App

**Last updated:** 2026-08-24T16:33Z

A native Android habit tracking app built with Kotlin + Jetpack Compose. Maintains full data compatibility with the desktop PyQt widget system by sharing the same `habitsdb_phone.txt` JSON file.

> **📖 Desktop infrastructure guide:** See [`DESKTOP_SERVICES.md`](DESKTOP_SERVICES.md:1) for the complete documentation of the PC-side supervisor, bridge protocol, movie tracking pipeline, and how to add new PC↔Phone features.

---31e8e7a8

## Features

- **🏋️ Weights habit type — machine/free weight × reps logging with kg/lb graph toggle** *(added 2026-08-24T16:33Z)* — a new special habit type for strength training. Enable it in edit mode: select a habit → expand **SPECIAL HABIT TYPES** → toggle **🏋️ Weights Habit** on ([WeightsToggleSection](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:4048)). Tapping the habit then opens the new [WeightsInputDialog](app/src/main/java/com/example/tail/ui/WeightsInputDialog.kt:32) instead of incrementing: a **kg/lb unit toggle**, a decimal **weight** field, an integer **reps** field, and a **Machine/Free** toggle — weight and reps are stored as two separate values. **Storage:** weights are canonicalised to integer **grams** (1 g precision — kg and lb entries round-trip losslessly) via [kgToGrams()](app/src/main/java/com/example/tail/data/HabitModels.kt:752) / `lbToGrams()`, then written in one atomic multi-key pass by [saveWeightsSlotsForDate()](app/src/main/java/com/example/tail/data/HabitsRepository.kt:721) into four secondary-value slots: machine weight → `secondary_value:` (day's heaviest wins — max-merge), machine reps → `secondary_value2:` (additive), free weight → `secondary_value3:`, free reps → `secondary_value4:`; the habit's own count still goes through the normal +1 increment path (timestamps, conditional feeds) via [saveWeightsEntry()](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:3499). **Graph:** four metric buttons (Machine Wt, Free Wt, Machine Reps, Free Reps) — by default **both weight curves are shown together**, each individually toggleable to filter machine vs free. A **kg/lb pill toggle** in the graph header auto-converts every stored gram value to the chosen display unit at read time ([gramsToDisplayTenths()](app/src/main/java/com/example/tail/data/HabitModels.kt:763) — ×10-scaled tenths so the Int-only graph pipeline keeps one decimal; [setGraphWeightUnit()](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:6229)), so history logged in one unit renders correctly in the other. When both are shown together the distinction is subtle but readable: **machine series draw filled dots, free-weight series draw hollow (stroked) dots** and reuse the machine colour at 55 % alpha; axis labels, tooltips and stats totals format weights with one decimal + unit suffix. Weights habits are excluded from JugCoach slot auto-detection, and renames + Google-Drive backups propagate the type set. 7 unit tests in [`WeightsConversionTest`](app/src/test/java/com/example/tail/WeightsConversionTest.kt:1).
- **Long-press deep links — paste any URI (obsidian://, tel:, https://…)** *(added 2026-08-24T15:04Z)* — the habit long-press "URL" action is now a first-class deep-link launcher: paste ANY URI into the long-press URL field and a longhold opens it directly in the target app — e.g. `obsidian://open?vault=My Vault&file=Notes/My Note.md` opens that exact note in Obsidian (Android resolves the scheme; the optional "Open in app" selector is still available to force a specific package). New pure helpers in [`LongPressUri.kt`](app/src/main/java/com/example/tail/data/LongPressUri.kt:1): [normalizeLongPressUri()](app/src/main/java/com/example/tail/data/LongPressUri.kt:73) trims clipboard whitespace, unwraps `<uri>`/`"uri"` copies, detects any scheme by RFC 3986 grammar (the old `"://"`-substring check mangled scheme-only URIs like `tel:` into `https://tel:…`), prefixes `https://` only for bare domains, and percent-encodes pasted-but-illegal characters (spaces → `%20`, non-ASCII, stray `%`) while preserving structure chars and existing `%XX` escapes — idempotently, at save time ([`setHabitLongPressUrl`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:11544)) and again at launch so legacy entries benefit. The launch site ([`HabitGridScreen`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:1253)) adds `FLAG_ACTIVITY_NEW_TASK` and toasts "No app found that opens …" when the scheme has no handler instead of failing silently. The editor ([`LongPressActionSection`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:3606)) shows a live scheme hint under the field plus a **Build Obsidian note URI** helper (vault + file path → encoded `obsidian://open` link via [buildObsidianOpenUri()](app/src/main/java/com/example/tail/data/LongPressUri.kt:52), spaces handled automatically). 16 unit tests in [`LongPressUriTest`](app/src/test/java/com/example/tail/LongPressUriTest.kt:1).
- **♟ Chess Phase 2 v2 — research-based post-game audit** *(added 2026-08-24T13:24Z)* — the post-game system that judges each rated game you share to Tail (continue / yellow / red) now has a second engine built from the 2026-08-24 research synthesis, selectable independently of the pre-game readiness version (Settings → ♟ Chess Readiness → **Post-Game Audit Version**: v1 or v2, freely combinable with either pre-game engine — the v2 audit consumes only the game itself plus your personal history). v1 is completely untouched and stays the default. v2 rules ([`ChessPhase2V2Engine`](app/src/main/java/com/example/tail/widget/ChessPhase2V2Engine.kt:76)): **R1** hard fatigue ceiling — yellow > 90 min, red > 120 min of cumulative session play; **R2** loss-chasing — a single loss NEVER stops you (bounce-back effect), 2 consecutive → yellow, ≥ 3 → red; **R3** tilt vector — personal Z-scores of move speed (parsed from the PGN `[%clk]` clocks) × accuracy deficit against rolling baselines of your last 100 games per time control (min 20 samples before it gates; population norms are never used), red at Z ≤ −1.5 speed AND Z ≥ +1.5 deficit on a LOSS, yellow at ±1.0, with a **circadian relaxation** 20:00–04:00 (your night norm is ~10 % faster, so the speed baseline is relaxed to avoid flagging normal night play); **R4** chronic overload — ACWR (7-day games vs 28-day weekly average), yellow ≥ 1.3, red ≥ 1.5, gated until 14 distinct playing days exist; **R5** hysteresis — yellow persists until you prove recovery (win/draw + accuracy at/above your norm + 15 min elapsed); HRV override intentionally omitted (no wearable sample at decision time). Verdicts reuse v1's `OutputState` names, so the entire enforcement stack — Chess Guard, yellow warning screen, red blocking wall — works unchanged; v2 also writes compatibility audit records (with ΔE and mapped strain) to the shared history so switching back to v1 mid-history is safe, while its own baselines/ledger accumulate in [`ChessPhase2V2Store`](app/src/main/java/com/example/tail/widget/ChessPhase2V2Store.kt:1). The share-screen verdict ([`ResultContentV2`](app/src/main/java/com/example/tail/ChessGameShareActivity.kt:452)) shows the fired rules, your speed/accuracy Z-scores, ACWR and the report's intervention copy; the status overlay explains the v2 kick-out rules. 52 unit tests in [`ChessPhase2V2EngineTest`](app/src/test/java/com/example/tail/ChessPhase2V2EngineTest.kt:1) cover every rule boundary, the circadian math, PGN clock parsing and the game mapping — they caught a real bug where the deficit Z-score double-subtracted the baseline mean and silently never fired tilt.
- **Quick Capture: fire-and-forget TAP capture inside the full voice+camera screen** *(added 2026-08-24T11:16Z)* — the quick capture keeps its full voice+camera combo ([`MediaCaptureActivity`](app/src/main/java/com/example/tail/MediaCaptureActivity.kt:201): voice starts immediately — say a habit or note and the screen closes itself; HOLD the shutter for the tandem-teaching photo+voice flow), and TAPPING the shutter (or a gallery photo) is now FIRE-AND-FORGET: [`capturePhoto`](app/src/main/java/com/example/tail/MediaCaptureActivity.kt:542) resolves the deterministic target (explicit habit extra, else the single camera-eligible habit), attaches to the active meal group so close-succession captures merge into one meal, enqueues for the background [`VisionProcessingWorker`](app/src/main/java/com/example/tail/data/meal/VisionProcessingWorker.kt:76), toasts "📸 Captured & Queued" and finishes — no Processing screen, no result overlay, no approval; anything the AI can't act on lands in the Quick Capture History with a notification on next app open. The static launcher shortcut points back at `MediaCaptureActivity` so every entry point (Tasker's frozen explicit intent, pinned shortcuts, Samsung Routines, long-press menu) gets identical behaviour — the earlier redirect-to-camera-only approach was reverted because it silently dropped the voice half of the quick capture. The camera-only [`QuickCaptureActivity`](app/src/main/java/com/example/tail/QuickCaptureActivity.kt:72) remains the target for tapping a camera-enabled habit on today's grid. Environment: the 2026-08-24 Ubuntu upgrade left `java-21-openjdk` JRE-only (no `javac`), breaking Gradle compiles — the Gradle daemon pins (project + `~/.gradle`) now point at the full JDK 17.
- **Quick Capture: fire-and-forget meal camera + review fallback** *(added 2026-08-23T19:50Z)* — the launcher "Quick Capture" shortcut now opens [`QuickCaptureActivity`](app/src/main/java/com/example/tail/QuickCaptureActivity.kt:70) directly (previously it opened MediaCaptureActivity's *inline* classification pipeline, which used a different LLM prompt — with habit-list guessing — than the meal-habit camera and blocked on a wait-then-approve screen; that mismatch was why a shortcut capture could fail to be recognised while the same meal photo from the habit's own camera worked). The capture is now fully deterministic: when exactly one camera habit is enabled, its name is written onto the queue item at capture time and the background [`VisionProcessingWorker`](app/src/main/java/com/example/tail/data/meal/VisionProcessingWorker.kt:54) runs the exact meal pipeline the manual editor uses (plain food-analysis prompt, no habit guessing), creates the meal log, increments the habit, and merges close-succession captures into one meal — snap, lock the screen, done. **Tap-to-capture:** tapping a camera-enabled habit on today's grid now opens the camera immediately instead of incrementing directly (the increment comes from the vision pipeline, so courses never double-count). **Fallback:** any capture the AI can't act on (not recognised as food, no camera habit matched, or processing failed after 3 retries) moves to `NEEDS_REVIEW` — the image is kept in the Quick Capture History ([`QuickCaptureHistoryScreen`](app/src/main/java/com/example/tail/ui/QuickCaptureHistoryScreen.kt:62), reachable from a top banner on the grid and from a system notification posted on next app open), where the intended habit can be assigned and the capture retried or deleted. Review items are never auto-cleaned, stale PROCESSING items are recovered on the next worker pass, and the worker now uses `APPEND_OR_REPLACE` so a capture enqueued mid-pass is never missed. The voice+camera combo screen remains available via `ACTION_MEDIA_CAPTURE` (Tasker).
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
- **App icons as habit icons — reliable B/W variant + full-size rendering** *(added 2026-08-23T06:13Z)* — the icon picker's 📱 Apps section stores `app:<package>` (full-colour launcher icon) or `app:<package>#mono` (black/white notification-style icon). True notification small-icons of *other* apps are not accessible via any public Android API, so the B/W variant uses the adaptive icon's **monochrome layer** — the layer Android itself uses for themed icons and notification badges (API 33+) — falling back to a greyscale render of the foreground glyph. Fixes in [`loadAppIconBitmap()`](app/src/main/java/com/example/tail/data/AppIconRepository.kt:119): (1) mono glyphs are now always forced to pure white — light-coloured monochrome layers (e.g. Inuit's light-blue one) previously leaked through in colour; (2) every app-icon variant is cropped to its visible content ([`cropToContent()`](app/src/main/java/com/example/tail/data/AppIconRepository.kt:205)) so the glyph fills the 20 dp habit cell like the built-in icons instead of rendering ~40 % smaller inside the uncropped 108 dp adaptive canvas; (3) the greyscale fallback now renders the adaptive **foreground** layer instead of the whole icon (which produced a grey square). Applies everywhere app icons render: habit grid, home-screen widget and picker previews.
- **Garmin health integration** *(added 2026-06-16T13:31Z, updated 2026-06-16T15:28Z, updated 2026-06-18T16:36Z)* — in Settings → **❤️ Garmin Integration**, configure your Garmin proxy URL and app token, then set thresholds for health metrics (VO2 Max, Fitness Age, Resting HR, HRV, Sleep Score). Link habits to Garmin metric types in edit mode. The app polls every 15 minutes and automatically increments habits when metrics meet or exceed your thresholds. Use "Fetch Entire Backlog" to retroactively fill historical data from the Garmin API. Use "Import Historic Data" to import data from a Garmin GDPR ZIP export processed by the desktop script. Use the "Test Connection" button to verify the full connection chain (proxy server, app token, Garmin API, and data availability) before enabling. Requires deploying the Python proxy (see `garmin_proxy/` directory).
- **Secondary value retirement — breathing habits to built-in Minutes/Sessions** *(added 2026-08-22T06:06Z)* — one-time migration converts Meditations, Resonance Breathing and Until Contraction from the legacy Wags layout (minutes = primary + sessions in `secondary_value:` with divider + points fallback) to sessions-primary: sessions are the primary value and sole points source, minutes live in the first-class `minutes:` slot, and Wags sends one atomic Protocol v3 broadcast (`EXTRA_SESSIONS` + `EXTRA_MINUTES`; `EXTRA_SESSIONS=0` is the minutes-only variant used for duration corrections and ended-early contraction tables). "Apnea practiced" is deliberately untouched (still fed by the O2/CO2 Tables conditional links). The generic "Secondary value" / "Fallback to secondary" edit-screen toggles are removed — the secondary-value track is now reserved for the special multi-value integrations (chess.com, JugCoach, movie-bridge IMDb ratings) that manage their slots automatically. Deploy Tail and Wags together: install Tail first (runs the migration), then Wags.
- **GitHub repo integration** *(added 2026-08-13T11:00Z)* — in Settings → **🐙 GitHub Integration**, enable the feature (optionally add a Personal Access Token for 5 000 req/hr instead of 60/hr). Then in edit mode, select a habit → toggle the **🐙 GitHub** section → paste a public GitHub repo URL (e.g. `https://github.com/owner/repo`) → tap **Apply**. The app automatically backfills the entire commit history into the habit's daily values. The default metric is **Lines Changed** (additions + deletions); other options are Commits, Additions, and Deletions. The app polls every 30 minutes for new activity. Per-commit line stats are cached locally so re-fetches are fast and rate-limit-friendly.
- **Resonance Breathing secondary value (sessions) + legacy migration** *(added 2026-08-14T14:17Z)* — wags now sends **session counts** for resonance breathing as the secondary value (`secondary_value:Resonance Breathing`) alongside minutes, exactly like Meditations and the apnea habits (see [`RESONANCE_SECONDARY_VALUE_WAGS_SPEC.md`](RESONANCE_SECONDARY_VALUE_WAGS_SPEC.md:1)). No receiver changes were needed — the IPC path is fully generic. A one-time flag-guarded migration ([`performResonanceSecondaryMigration()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:846)) moves pre-2026-08-08 **session counts** (primary values ≤ 3, written before wags switched to minutes) into the secondary slot and zeroes the primary, and auto-enables the Value 2 track for the habit. Optional: enable "Fallback to secondary" on the habit to let session counts drive points on days with 0 minutes.
- **JugCoach session metrics (Protocol v3) + history backfill** *(added 2026-08-16T15:47Z, backfill added 2026-08-16T16:26Z)* — JugCoach completed runs now report their **full breakdown** to the habit mapped to *Used JugCoach* in one atomic broadcast ([`JugCoachSessionReceiver.kt`](app/src/main/java/com/example/tail/ipc/JugCoachSessionReceiver.kt:63)), replacing the old binary "+1" ping. Alongside the usual count (+1 per run, max-1 cap respected), six cumulative daily metrics are stored in numbered secondary-value slots: total seconds juggled, total catches, seconds/catches in runs that ended in a **catch**, and seconds/catches in runs that ended in a **drop** (see [`JUGCOACH_SESSION_SPEC.md`](JUGCOACH_SESSION_SPEC.md:1)). Detection is key-presence based — the six graph metric buttons appear automatically in stats mode once JugCoach has sent data, no settings toggle needed. Writes are Mutex-serialised and go through the atomic multi-key [`incrementHabitSlots()`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:620), so rapid-fire run saves can't lose increments; habit renames propagate to all six slots. JugCoach's Tail settings also gained a **"Backfill History to Tail"** button: it aggregates the entire run history by local day and sends it as `ACTION_JUGCOACH_BACKFILL`, which SETs absolute per-day totals for the count and all six slots via [`setHabitSlotsForDates()`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:720) — idempotent, safe to re-run after remapping or restoring a backup.
- **Sharable text-input habits (system share sheet)** *(added 2026-08-14T13:51Z)* — share selected text from **anywhere on the phone** straight into a habit. In edit mode, select a text-input habit → toggle **Sharable** on (under "Text input", next to "Options"). The habit then appears in the picker shown by [`ShareTextActivity`](app/src/main/java/com/example/tail/ShareTextActivity.kt:68) when you use the system **Share → tail** sheet: pick the habit and the shared text is saved as a timestamped entry in its text-log JSON file, the habit count is incremented by 1, and an internal backup is refreshed. Turning "Text input" off automatically removes the habit from the sharable set; renames are propagated. The toggle UI lives in [`SharableTextToggle`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:2870), extracted from `EditModeControlBar` to stay under the JVM method-size limit.
- **Fix: "Don't affect points" now excluded from Tasker stats file + Recalculate button** *(fixed 2026-06-19T09:46Z, repaired 2026-06-19T09:53Z)* — the Tasker stats relay file (`today` / `avg7` / `avg30` in the file set under Settings) was summing **all** habits, ignoring the per-habit "Don't affect points" (`noPointsHabits`) setting. This caused Garmin-imported habits to inflate the relayed totals even though they were marked as not affecting points. Root cause: the four `writeTaskerFile()` implementations ([`HabitViewModel`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:423), [`HabitIncrementReceiver`](app/src/main/java/com/example/tail/ipc/HabitIncrementReceiver.kt:171), [`VoiceHabitService`](app/src/main/java/com/example/tail/ipc/VoiceHabitService.kt:516), [`SmartVoiceService`](app/src/main/java/com/example/tail/ipc/SmartVoiceService.kt:627)) each re-implemented the total and none applied `noPointsHabits` (unlike the in-app stats in [`computeAppStats`](app/src/main/java/com/example/tail/ui/AppStatsScreen.kt:1035) / [`getDailyTotals`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:537), which already excluded them). Fix: a single shared [`buildTaskerStatsContent()`](app/src/main/java/com/example/tail/data/HabitCalculator.kt:253) helper now computes the file content, skips `noPointsHabits`, and is called by all four sites. **Note on data integrity:** the in-app point totals were never actually corrupted in storage — they already excluded `noPointsHabits` and recompute on the fly; only the historic Garmin import stored raw metric values (steps ≈ 16M, distance ≈ 21M, etc.) under their own habit names, which inflated *only* the Tasker file's averages. A new **🔄 Recalculate Stats File Now** button in `Settings → Tasker Stats File` (calls [`HabitViewModel.refreshTaskerStatsFile()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:457)) lets you rewrite the file on demand without waiting for the next increment.
- **Chess Guard — hard enforcement of the Chess Readiness gate** *(added 2026-08-23T11:43Z, trust-window revision 12:10Z, casual-YELLOW + 24 h penalty revision 12:42Z)* — the readiness system's traffic-light verdicts are now PHYSICALLY enforced instead of advisory. A new accessibility service ([`ChessGuardService`](app/src/main/java/com/example/tail/widget/ChessGuardService.kt:80), enabled manually under Settings → Accessibility → Tail, or via the button in Settings → ♟ Chess Readiness) watches for the configured chess app being opened; whenever the pure policy ([`ChessEnforcementPolicy.evaluate()`](app/src/main/java/com/example/tail/widget/ChessEnforcementPolicy.kt:110)) says the app is blocked, the guard instantly performs GLOBAL_ACTION_HOME and shows a full-screen lock wall (overlay-first via [`ChessGuardWallOverlay`](app/src/main/java/com/example/tail/widget/ChessGuardWallOverlay.kt:1), activity fallback [`ChessGuardLockActivity`](app/src/main/java/com/example/tail/widget/ChessGuardLockActivity.kt:29) — the SYSTEM_ALERT_WINDOW overlay is immune to the Android 10+ background-activity-launch blocks that silently swallowed the activity launch on One UI) explaining why and counting down live to the moment the next test becomes possible ("Can re-test in 43 min (12:53)"). Policy: an active GREEN session (≤ 60 min) unlocks everything; **YELLOW opens the app for casual play only** (unrated games, bots, puzzles) — the guard shows a full-screen yellow warning on entry spelling out that one detected rated game costs a 24-hour total lockout; RED rest-locks, re-test cool-downs, the daily test cap and active penalties block with a live countdown; **the trust window** — once the engine's re-test gate opens the app also unlocks without a GREEN pass, because the readiness test itself happens inside the chess app; an in-progress readiness test at its puzzle/rush steps is always allowed even during a rest period (anti-deadlock pass, bounded by the session's 10-min step timeout). **Automatic violation detection (24 h penalty):** every game the app learns about — post-game share, deferred reconciler, or the monthly archive poll — flows through [`ChessReadinessLogStore.logGames()`](app/src/main/java/com/example/tail/widget/ChessReadinessLogStore.kt:104), which feeds each NEW game to [`ChessGuardPenalty`](app/src/main/java/com/example/tail/widget/ChessGuardPenalty.kt:1): a RATED game ending outside a valid green window, ANY game ending during a lockout or active penalty, or any game played instead of testing in the bare trust window appends a penalty that locks the ENTIRE app (testing included) for 24 hours, overriding even a fresh GREEN pass; deduped per game and exempt for games that predate enforcement. State changes are broadcast (`com.example.tail.CHESS_GUARD_STATE`) for optional Tasker/MacroDroid escalation.
- **Chess.com integration** *(added 2026-03-12T20:12Z)* — in Settings → **♟ Chess.com Integration**, enter your username and set minutes-per-increment for Bullet/Blitz/Rapid games. Link habits to game types in edit mode. The app polls every 15 minutes and automatically increments habits based on your chess activity. Use "Fetch Entire Backlog" to retroactively fill historical data. **Feed & timestamp fixes** *(fixed 2026-08-18T15:21Z)* — the auto-sync now respects the conditional **"Feed points"** setting (feeds the divider-applied points delta, identical to tap feeds — previously it fed raw MINUTES into linked habits, so a "Chess" aggregator habit mirrored e.g. Blitz's minute count instead of its points), and records ONE timestamp per new GAME instead of one per minute. A one-time flag-guarded trim ([`performChessTimestampTrim()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:1094)) cuts existing per-minute timestamp piles on chess.com-linked habits down to each day's game count.
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
- **Per-change snapshot safety net + truncation-wipe root-cause fix** *(added 2026-07-05T09:50Z)* — a second, more severe wipe occurred: `habitsdb.txt` collapsed from several MB to ~100 KB overnight, losing all points. **Root cause:** [`saveDatabase`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:139) wrote via `openOutputStream(uri, "wt")`, which **truncates the file to zero and then streams** the multi-MB JSON. An interruption mid-stream (process kill / OOM / device sleep) leaves a truncated fragment — the wipe. The daily auto-backup could not save it because it captured the *already-wiped* file on that morning's first launch, and the anti-shrinkage guard was bypassed because a truncated file fails to parse (so its `onDisk` check became `null` and skipped). **Multi-level fix:**
    - New [`HabitsSnapshotManager`](app/src/main/java/com/example/tail/data/backup/HabitsSnapshotManager.kt:1) writes a content-addressed DB snapshot to the app's **private internal storage** (immune to SAF/Syncthing corruption) on **every successful save**, and snapshots the healthy on-disk state **before every overwrite**. Because [`saveDatabase`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:139) is the single choke point for all write paths (UI, widget, voice services, receivers, backup restore), every path gains this automatically.
    - **GFS-style retention** (keep-all last hour → newest-per-hour for a day → newest-per-day for a week → newest-per-week beyond, hard-capped at 200 files / 40 MB) gives dense recent history without unbounded disk growth.
    - Writes go through a single [`writeJsonToUri`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:210) helper that uses the universally-supported SAF `"wt"` mode, flushes explicitly, and fsyncs the file descriptor when the provider exposes a real `FileOutputStream`; the post-save snapshot is only recorded after a **confirmed** write. *(An earlier build tried the `"wts"` sync-mode string, but Samsung's SAF provider rejects it with an exception — which silently dropped writes and made increments "disappear" on reopen; reverted 2026-07-05T10:10Z.)*
    - New **Automatic snapshots (wipe recovery)** block in `Settings → Backup & Restore` ([`SnapshotRestoreSection`](app/src/main/java/com/example/tail/ui/SnapshotRestoreSection.kt:41)) lists each snapshot with its entry count + size and offers one-tap restore via [`restoreDatabaseRaw`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:243) (which itself snapshots the current state first, so a restore is reversible).
- **Lock-screen habit list widget** *(added 2026-05-09T12:10Z)* — placeable on the lock screen (or home screen as a fallback). Starts in a slim **collapsed bar** that says *"Tap to open habits"*. A **single tap arms** it (background turns orange, label changes to *"Tap again to open habits"*); a **second tap within 1.5 s expands** it. If the user does nothing, an [`AlarmManager`](app/src/main/java/com/example/tail/widget/HabitListWidgetProvider.kt:380) auto-disarm reverts the bar to the safe collapsed state — so pulling the phone out of a pocket can never accidentally increment anything. Expanded view is a **scrollable [`ListView`](app/src/main/res/layout/widget_expanded.xml:1)** of every habit (icon + name, ~8 visible per panel, dark themed). Tapping a normal habit increments by 1 in the background; tapping a **text-input habit** launches [`WidgetInputActivity`](app/src/main/java/com/example/tail/widget/WidgetInputActivity.kt:1) — a transparent trampoline that shows the same [`TextInputDialog`](app/src/main/java/com/example/tail/ui/TextInputDialog.kt:1) as the main app, then saves the entry + increments the habit. Smart **widget-local ordering**: any habit you tap from the widget jumps to the top; an exception is **max-one habits** (binary done/not-done): when you tap one of those it goes to the very *bottom* until midnight, dimmed, then returns to its normal position the next day. State lives in a separate per-widget [`DataStore`](app/src/main/java/com/example/tail/widget/WidgetPreferences.kt:1) (`recent_<id>` + `max1tap_<id>` + `expanded_<id>` keys) so multiple widget instances don't share state and the main app's settings are never modified. The collection adapter is [`HabitListRemoteViewsService`](app/src/main/java/com/example/tail/widget/HabitListRemoteViewsService.kt:1) which snapshots settings + per-widget prefs on every `notifyAppWidgetViewDataChanged()` call.
- **Multi-select text entry options + time-stamped entries** *(added 2026-08-05T15:46Z)* — text-input habits with "options" enabled now support **multi-select**: the past-entries list uses checkboxes so the user can select as many entries as desired, each saved as a separate timestamped log entry. A **Material3 TimePicker** was added to [`TextInputDialog`](app/src/main/java/com/example/tail/ui/TextInputDialog.kt:51) so the user can associate a specific time-of-day with entries (defaults to current time for today, noon for past dates). Previously, past-date entries were always stored at noon. New repository method [`appendMultipleTextEntries`](app/src/main/java/com/example/tail/data/TextInputRepository.kt:83) saves N texts atomically with 1-second-offset timestamps to avoid key collisions. New ViewModel methods [`saveTextEntries`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:2768) and [`setTextEntriesForDateWithRollForward`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:1100) handle multi-entry saving with proper count incrementing and roll-forward support.
- **Habit app associations (long-press to launch apps)** *(added 2026-08-10T11:44Z)* — any habit can now be associated with one or more installed apps. In edit mode, selecting a habit reveals a new **📱 App Association** section where the user can pick apps from a searchable list of all installed apps, reorder them with ▲/▼ arrows, and remove them. Habits with associations show a blue **↗** indicator in the top-right corner (same style as standalone app-link cells). **Long-pressing** a habit with associations behaves differently: a single associated app launches directly (no dialog); multiple apps show a picker dialog listing them in their defined order. Habits without associations retain the original long-press behaviour (increment without timestamp). Stored in DataStore as `habitAppAssociations` (`Map<String, List<String>>` habit name → ordered package names) via [`saveHabitAppAssociations`](app/src/main/java/com/example/tail/data/SettingsRepository.kt:1058). New composables [`AssociatedAppRow`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:1797) and [`AssociatedAppLauncherDialog`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:1896) handle the edit-mode list and the multi-app picker respectively.
- **Subtype & timed data internalized** *(added 2026-08-15T16:22Z)* — subtype breakdowns and timed-session entries no longer live in per-habit external SAF files. [`SubtypeDataRepository`](app/src/main/java/com/example/tail/data/SubtypeDataRepository.kt:1) and [`TimedDataRepository`](app/src/main/java/com/example/tail/data/TimedDataRepository.kt:1) were rewritten to store everything in a single app-internal JSON file each (`files/subtype_data.json`, `files/timed_data.json`), keyed by habit name, serialized by a process-wide Mutex (same pattern as `HabitTimestampRepository`). The per-habit "Data file" picker was removed from the edit-mode subtypes UI, and the `subtypeDataFileUris` / `timedDataFileUris` settings are no longer consulted. A one-time flag-guarded migration ([`SubtypeTimedMigrator`](app/src/main/java/com/example/tail/data/SubtypeTimedMigrator.kt:1), DataStore flag `migration_subtype_timed_internalized`) runs on app start and before voice-service subtype writes: it imports any legacy external files (sum-merge for subtype counts, key-merge with internal-wins for timed entries) and **leaves the external originals untouched** so they remain as a manual safety copy. Backups are unaffected in coverage: [`BackupManager`](app/src/main/java/com/example/tail/data/backup/BackupManager.kt:349) now reads/writes the internal stores directly, so subtype/timed data is still embedded in every backup and restored. Habit renames now propagate to both internal stores. External files that stay external by design: `habitsdb.txt` (shared with the desktop widget), `total_habits.txt` (Tasker), `screens_layout.json`, dated-entry sources, text logs, and the voice-note file.
- **"The Orrery" — triple-metric generative loading animation** *(added 2026-08-16T07:21Z)* — the loading screen is now a composed art piece driven by **three habit metrics at once**, each owning its own layer of the animation. The **30-day monthly average** (primary) selects the core archetype and its colour: Ember (red) → Twin Flames (orange) → Comet (green) → Atom — three tilted elliptical electron orbits (blue) → Rose Window (pink) → Binary Suns (yellow) → Supernova with spectrum ring, eight twinkles, rays and core glow (white). The **7-day weekly average** (secondary) grows an orbital halo around the core in the weekly tier colour: faint ring → first satellite → tilted ellipse with two satellites → twin counter-rotating rings + three twinkling satellites → shimmer gradient ring + four satellites → full six-satellite swarm with precessing spectrum ring. **Today's points** (minor) ignite a central spark in the daily tier colour: steady dot → breathing dot → sparkle cross → micro-moons (1/2/3) → expanding ripples. All three metrics climb the same 7 colour tiers used across the app (14/21/31/42/49/56 boundaries), giving 7 × 7 × 7 = **343 distinct combinations**; when all three tiers align on the same non-zero colour, a hidden **resonance** flourish adds slow blended ripples through the whole piece. Implementation: [`HabitLoadingSpinner`](app/src/main/java/com/example/tail/ui/HabitLoadingSpinner.kt:1) orchestrates four animation phases and dispatches to layer renderers in [`HabitLoadingLayers`](app/src/main/java/com/example/tail/ui/HabitLoadingLayers.kt:1); metrics come from [`HabitViewModel.getLoadingMetrics()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:7986) (single 30-day scan) exposed as a retained `loadingMetrics` StateFlow so tiers stay correct mid-load. Tier logic is unit-tested in [`HabitLoadingSpinnerTest`](app/src/test/java/com/example/tail/HabitLoadingSpinnerTest.kt:1). **Cold-start cache** *(added 2026-08-16T09:01Z)* — the metrics are persisted to a small SharedPreferences cache (`loading_metrics_cache`) whenever they are recomputed for today, and hydrated synchronously at ViewModel construction, so a cold launch shows likely-correct tiers on the very first frame instead of flashing tier-0 red for the 1–2 s before the DB loads. Yesterday's averages are trusted across midnight (they move by only 1/30th and 1/7th per day), while the cached daily total is only trusted when the cache was written today; browsing history dates never writes the cache.

- **Podcast habit type (automatic listening-time tracking)** *(added 2026-08-16T12:40Z)* — in edit mode, select a habit (e.g. *Podcast finished*) → toggle the **🎙 Podcast** section on → tap **Select App** to choose the podcast app to watch. While that app is actually **playing** audio (detected via its media session by [`PodcastPlaybackTracker`](app/src/main/java/com/example/tail/widget/PodcastPlaybackTracker.kt:1), polled by [`WidgetTriggerService`](app/src/main/java/com/example/tail/widget/WidgetTriggerService.kt:1) every 2 s), elapsed minutes are automatically written — atomically, sessions = 0 — to the habit's minutes slot (`secondary_value:<habit>`, the same slot the bubble timer writes). First-time setup makes **minutes the primary value for points** (`widgetTimerMinutesPrimary`) with the habit's tapped count (podcasts finished) as the **points fallback** on zero-minute days; display labels become "Podcasts"/"Minutes". Requires notification-listener access (the same *Tail* toggle in system notification-access settings already used for Spotify detection — the section shows a ⚠️ warning + Grant button until enabled). The bubble widget is auto-enabled over the podcast app so the **manual widget timer remains the fallback** exactly like other apps when auto-detection can't run. Listening state is persisted across monitor restarts; deconfiguring a habit mid-listening flushes the accumulated block. **Episode logging** *(added 2026-08-16T13:22Z)*: the media session also exposes episode metadata, so the first time a NEW episode is detected playing (deduplicated by title+show — pausing/resuming never duplicates), `"Episode Title — Show Name (NN min)"` is appended to the habit's text-entry log (when text entry is configured), replacing the manual copy-the-description workflow. The full episode description is not exposed by media sessions; title + show + duration (+ media URI when the app provides one) identify the episode instead.
- **First-class minutes slot for every habit + universal Primary value selector** *(added 2026-08-18T15:01Z)* — every habit now implicitly has a dedicated **minutes slot** — a `minutes:<habit>` key in the habits DB ([`minutesKey()`](app/src/main/java/com/example/tail/data/HabitModels.kt:237)) — written atomically alongside the session count by every timer feature (bubble widget timer, PC widget, media/podcast tracking) via [`incrementHabitWithMinutes()`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:578). The app now *knows* these values are minutes instead of overloading the generic Value2 slot. Edit mode gained a universal **Primary value: [Sessions] [Minutes]** section ([`PrimaryValueSection()`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:3460)) with explicit fallback semantics: minutes-primary habits automatically fall back to session-derived points on 0-minute days; sessions-primary habits get a "Fallback to minutes" switch. It includes a direct "minutes today" editor ([`getMinutesTodayCount()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:3368) / `setHabitMinutesCount()`), and graphs gained a **Minutes** metric (`GRAPH_METRIC_MINUTES`) backed by `GraphDataPoint.minutesValue`. A one-time flag-guarded migration ([`performMinutesSlotMigration()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:1040)) max-merges timer minutes out of the legacy `secondary_value:` slot into `minutes:`, removes the stale legacy data/settings, and auto-sets *Good Posture* to minutes-primary. Legacy Value2 users (chess.com, Wags, JugCoach, IMDb) are untouched — their behaviour is bit-identical. The fallback slot resolution is data-driven ([`fallbackSlotKey()`](app/src/main/java/com/example/tail/data/HabitModels.kt:245)): a habit with legacy `secondary_value:` data but no Value2-track membership (chess.com games, JugCoach seconds, never-migrated Value2 minutes) keeps reading that slot as its fallback, exactly as before the migration. **Edit-panel refinements** *(2026-08-18T15:49Z)* — the Primary value section now shows for **every** habit: toggling *Secondary value* on no longer hides it (the wording adapts to "second value tracked alongside", and the "Fallback to minutes" switch defers to the "Fallback to secondary" toggle so the two never compete). The confusing "Legacy Value2 track (chess, Wags, JugCoach)" note is replaced with plain-language labels ("Tracks a second value alongside points" / "Single value only"), and the daily minutes input moved from the bottom of the edit panel to the **top, beside the value editor** — labelled "minutes:", rendered by [`EditModeMinutesEditorRow()`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:6076) for plain/divider habits (timer habits keep the Minutes/Sessions dropdown, media habits their native minutes editor, Garmin/secondary-value habits store their values elsewhere).

---

## Architecture

```
app/src/main/java/com/example/tail/
├── data/
│   ├── HabitModels.kt        # Data classes, HABIT_ORDER list, DEFAULT_CUSTOM_INPUT_HABITS
│   ├── HabitCalculator.kt    # Streak/antistreak/ATH calculations, display value adjustments
│   ├── HabitsRepository.kt   # JSON read/write via SAF URI (Gson), habit list builder
│   ├── LocationRepository.kt # GPS location fetch, reverse-geocode, daily + secondary location storage
│   ├── ChessComRepository.kt # Chess.com API client, full-archive sweeps, minutes→increments conversion
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

*Added: 2026-03-12T20:12Z — Updated: 2026-08-23T06:00Z*

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

**Optional extras:**

| Extra | Type | Description |
|-------|------|-------------|
| `EXTRA_MINUTES` | `Int` | v2 — minutes to add instead of the default +1 (WAGS durations). |
| `EXTRA_SESSIONS` | `Int` | v3 — session count for sessions-primary habits (WAGS apnea). |
| `EXTRA_TIMESTAMP` | `Long` | v5 — epoch-millis of the moment the event actually happened (Inuit answer time). The schedule-timeline timestamp is stamped at THAT date/time instead of receive time, so late deliveries land on the correct day. |

---

### 3. BroadcastReceiver — Set Habit Values (backfill) + Answer Times

**Action:** `com.example.tail.ACTION_SET_HABIT_VALUES`
**Extras:**

| Extra | Type | Description |
|-------|------|-------------|
| `EXTRA_HABIT_ID` | `String` | habit name |
| `EXTRA_VALUES_JSON` | `String` | `{"yyyy-MM-dd": <count:Int>, ...}` — REPLACES each date's stored value (idempotent backfill) |
| `EXTRA_TIMES_JSON` | `String` | v5, optional — `{"yyyy-MM-dd": ["HH:mm:ss", ...], ...}` — REPLACES each date's schedule-timeline timestamps with the exact times the units were recorded (Inuit sends one time per answered question) |

When `EXTRA_TIMES_JSON` is present, Tail replaces the habit's timestamps for every listed date via `HabitTimestampRepository.setTimestampsForDay`, so backfilled history shows on the schedule timeline at the time of day it actually happened. Close-succession timestamps (≤ 30-minute gaps, chained transitively) merge into a single session block on the timeline — a run of questions answered back-to-back renders as ONE block labelled ×N, not one chip per question. Absent extra → behaviour unchanged (WAGS / Skin Tracker compatibility).

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

## Garmin "garmin value" / points data flow *(fixed 2026-06-22T07:35Z)*

The "garmin value" field shown under each Garmin-linked habit is **read-only** and is
fed exclusively from the on-disk monthly cache in the app's internal storage
(`files/garmin_cache/{YYYY}_{MM}.json`). Habit **points** are then derived from that
value (threshold rule, or custom point ranges if enabled). Users cannot type into the
garmin value field — only imports and the laptop proxy/fetch pipeline can change it.

**Regression fixed (2026-06-21):** all garmin values were showing `-`. Root cause was a
state-replacement bug — the once-a-day 7-day proxy poll did
`_garminMonthlyData.value = monthData`, which **replaced** the full multi-year in-memory
map with only the last 7 days, so every older date rendered `-`. The on-disk cache was
never wiped (5 years / 17.5k dates remained intact), so **no reimport was required**.

What changed:
- The 7-day poll now **merges** fresh data into the displayed map instead of replacing
  it (`mergeIntoGarminMonthlyData`), and **writes the fresh days through to the cache**
  (`GarminRepository.mergeAndCacheDailyData`) so recent data survives restarts.
- **"Test Connection" is now the authoritative "sync from laptop" button** — it fetches
  the entire proxy backlog (whatever your laptop script has cached) and merges it, with
  laptop values winning for the dates they cover. Historic JSON data is preserved.
- **Laptop proxy/fetch data wins on conflict**: when the proxy reports a value that
  differs from what is cached for the same date, the fresh value overwrites the cached
  one (both in memory and on disk).
- `applyGarminData` now recomputes each day's point **deterministically** from the
  current garmin value and writes the result even when it is `0`, so a corrected value
  from the laptop flips the point both up **and** down (previously a downward correction
  left a stale point).
- "Import Historic Data" remains the authoritative path for the deep past: it
  `clearCache()`s then re-imports `garmin_import.json` (back to 2019/2020), so a reimport
  cleanly overwrites whatever was there. The proxy "Fetch Entire Backlog" button clears
  the cache and re-fetches from Garmin's API (useful for a full refresh from Garmin).

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

- **Tail Bridge — PC↔Phone movie tracking + Garmin integration** *(added 2026-08-12T14:40Z, updated 2026-08-13T10:32Z)* — a desktop daemon ([`movie_watcher.py`](tail_bridge/movie_watcher.py:1)) polls the KDE Activity Manager SQLite database every 60 s, detects when videos are played, cleans filenames into readable titles via [`movie_name_cleaner.py`](tail_bridge/movie_name_cleaner.py:1), and maintains a cache with full session tracking (start time, end time, duration per session — multiple sessions preserved if a film is started/stopped/restarted). A FastAPI bridge server ([`bridge_server.py`](tail_bridge/bridge_server.py:1)) on port 8001 exposes the data via a source-registration protocol. **Garmin health metrics are now served through the bridge** via [`sources/garmin.py`](tail_bridge/sources/garmin.py:1), which reads the same `garmin_cache.json` as the Garmin proxy — no separate HTTP hop needed. On the phone, when you tap a movie-linked text-input habit, the app fetches the latest watched movie from the desktop and pre-fills the [`TextInputDialog`](app/src/main/java/com/example/tail/ui/TextInputDialog.kt:67) with a "🎬 Suggested from desktop" label. Already-logged titles are excluded so you always get the next untracked movie. **No manual URL/token setup needed** — the bridge auto-derives its connection from the Garmin settings (same host, port 8001, same auth token). Settings → **🎬 Bridge** section to enable the toggle and Test Connection. Edit panel → **🎬 Movie Bridge** toggle to link a habit. See [`DESKTOP_SERVICES.md`](DESKTOP_SERVICES.md:1) for the full architecture guide.
- **IMDb Ratings via OMDb API** *(added 2026-08-13T10:32Z)* — movie-linked habits now fetch IMDb ratings automatically from the [OMDb API](https://omdbapi.com/). When a movie/episode entry is logged, [`OmdbService`](app/src/main/java/com/example/tail/data/OmdbService.kt:1) parses the title (handling `S05E14` episode patterns and `(148 min)` suffixes), queries OMDb, and caches the rating × 10 as an integer (e.g. 8.8 → 88) in [`ImdbRatingCache`](app/src/main/java/com/example/tail/data/ImdbRatingCache.kt:1) (`imdb_ratings_cache.json` in app internal storage). The daily average IMDb rating for each day is stored as a secondary value under `secondary_value:<habitName>`. **Graph integration**: select the "IMDb Avg" metric in the graph to see a daily average rating line; tapping a data point shows individual ⭐ ratings next to each movie/episode name in the popup. **Backlog fetch**: Settings → **🎬 Bridge** → "Fetch IMDb Backlog" button processes all previously-watched entries, respecting the 990 calls/day free-tier limit — click again the next day to resume. API key is entered in the same Bridge section and stored via DataStore (never hardcoded). Get a free key at [omdbapi.com/apikey.aspx](https://omdbapi.com/apikey.aspx).
- **Travel Stats screen** *(added 2026-08-14T16:16Z, updated 17:40Z)* — the world-map screen gained a 📊 bar-chart button (top-right, next to the gear) opening a full **Travel Stats** screen ([`MapStatsScreen.kt`](app/src/main/java/com/example/tail/ui/MapStatsScreen.kt:1)) styled like App Stats (dark navy cards, gold titles). It aggregates the daily location history in one off-thread pass ([`computeTravelStats`](app/src/main/java/com/example/tail/ui/map/TravelStats.kt:1)) and shows: summary chips (countries / cities / continents / total km), a **time-range selector (1M · 1Y · 5Y · 10Y · All)** — 1M shows daily buckets with dates on the axis, 1Y monthly, 5Y/10Y/All get a Monthly ↔ Yearly granularity toggle — bar charts of **new countries, new cities, new continents, and distance traveled** per period (custom Canvas charts, [`StatsCharts.kt`](app/src/main/java/com/example/tail/ui/map/StatsCharts.kt:1)) with **sp-to-px sized axis text** (2-digit year labels), **horizontal swipe panning** of the fixed windows back in time, and **tap-a-bar detail popups** listing the exact places first-visited (with dates) or the biggest hops + total km of that period; **expandable leaderboards** (most-visited countries / cities, days per continent — top 8 with a "Show all (N)" toggle in the header), plus a Records section (longest stay in one place, biggest single hop, northern-/southernmost points, unique places, days tracked). Continents are classified from the daily coords via a bounding-box heuristic ([`continentForCoord`](app/src/main/java/com/example/tail/ui/map/TravelStats.kt:82)); distance is great-circle km between consecutive tracked days (haversine, same as the map's dedup). Country counting respects the existing ignored-countries list. New ViewModel accessor [`getAllStoredLabelsParsed()`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:7244) mirrors the coords snapshot pattern. *(Update 16:26Z: fixed a bug where `buildPeriods` returned the zero-filled skeleton instead of the aggregated buckets, so charts rendered with no bars.)* *(Update 16:54Z: assumed (*) locations now count — days with no record inherit the last known city, country and continent (fill-forward through today, field-level so ignored-country labels never inherit a foreign country); x-axis labels are measured with `measureText` and thinned by real width so 10Y and All show every year (All at 9 sp) with no right-edge overlap on 1M or 1Y, and the 1M chart shows day-of-month numbers only.)* *(Update 17:40Z: fixed the 1M tap bug — pointerInput was keyed on list size, which never changes while panning a fixed 30-day window, so taps fired stale closures pointing at pre-pan days; gesture handlers now use rememberUpdatedState, popups dismiss on pan, and tapping an empty bucket no longer opens an empty popup. Also, country aliases are canonicalised (USA, US, U.S., U.S.A. → United States) in both the stats and the map screen country counting, with the ignore list matched against both spellings.)*
- **IMDb rating match-rate overhaul** *(added 2026-08-14T13:07Z)* — fixed the mass-missing IMDb ratings. Root causes found by auditing the real `movie_cache.json` corpus (860 unique titles): (1) the `(2024)` year suffix was never stripped, so 76% of movie queries hit OMDb as `t=Anora (2024)` — an exact-match parameter — and failed; (2) scene-release names lose apostrophes/colons (`The Handmaids Tale`, `A Quiet Place Day One`) which exact match also rejects; (3) any transient failure (network error, HTTP 401 rate-limit) was permanently negative-cached as "no rating". [`OmdbService`](app/src/main/java/com/example/tail/data/OmdbService.kt:1) now (a) extracts the year and passes it as `&y=` with `&type=` hints, (b) falls back to IMDb's free key-less suggestion endpoint (`v2.sg.media-imdb.com/suggestion`) for fuzzy title→ID resolution — scoring candidates by normalised Levenshtein similarity + token containment + year/type agreement (empirically validated at 15/15 correct resolutions on tricky real titles, junk correctly rejected) — then fetches the rating by the resolved `i=<ttID>`, and (c) returns a sealed [`OmdbOutcome`](app/src/main/java/com/example/tail/data/OmdbService.kt:60) (Found/NotFound/Transient) so only definitive misses are negative-cached; transient errors retry later. [`ImdbRatingCache`](app/src/main/java/com/example/tail/data/ImdbRatingCache.kt:1) additionally persists resolved IMDb IDs per show (episodes of one show resolve once) and supports clearing failed lookups. Settings → Bridge gains a **"Retry Failed Lookups"** button that un-poisons all cached zeros and refetches with the new matcher — run it once after updating. Unit tests: [`OmdbServiceTest`](app/src/test/java/com/example/tail/OmdbServiceTest.kt:1).
- **Unified desktop supervisor** *(added 2026-08-12T14:40Z)* — all PC-side services (Garmin proxy, Garmin fetch timers, Tail Bridge, movie watcher) are now managed by a single Python process supervisor ([`tail_supervisor.py`](tail_supervisor.py:1)) reading from a TOML config ([`tail_services.toml`](tail_services.toml:1)). Only ONE systemd user service (`tail-supervisor.service`) is in autostart. Adding a new service = add a `[[service]]` block to the TOML and restart. The installer ([`install_supervisor.sh`](install_supervisor.sh:1)) creates venvs, installs dependencies, stops old individual services, and starts the supervisor — one command for fresh installs. See [`DESKTOP_SERVICES.md`](DESKTOP_SERVICES.md:1) for details.
- **Complete backup coverage + Google Drive backups** *(added 2026-08-15T15:32Z)* — the backup bundle now captures **everything**: all ~90 `AppSettings` fields (previously ~40), meal logs + meal images, `vision_queue.json`, `debug_tail.json`, and the chess readiness/phase2 SharedPreferences. New **Google Drive Backup** section in Settings → Backup & Restore: Google login, auto-daily-upload toggle, manual "Back up to Drive now", and restore/delete of Drive backups. Drive backups use the exact same `BackupBundle` JSON as local exports. One-time Google Cloud Console registration is required — see **Google Drive backup setup** below.
- **Chess readiness v3.0 — adaptive percentile gate** *(added 2026-08-18T16:38Z)* — the Phase 1 readiness test no longer judges against a fixed 85/70 bar (which was strict enough to tempt skipping the test entirely). [`ChessReadinessEngine`](app/src/main/java/com/example/tail/widget/ChessReadinessEngine.kt:37) now (a) scores **fine-grained tiers**: sleep from the raw Garmin score (6 tiers), clarity from the slider average (6 tiers), rated puzzles across **7 speed tiers** (quickness/slowness matters), Puzzle Rush across 6 ratio bands with a 3-pt/strike penalty; (b) derives the GREEN (rated play) and YELLOW (casual play) bars from **your own last ≤15 tests in a rolling 21-day window** — the 60th/35th percentile ([`computeThresholds`](app/src/main/java/com/example/tail/widget/ChessReadinessEngine.kt:415)). Doing *relatively* better than usual now authorizes play, and a run of weak tests automatically lowers the bar. Safety rails: **strict absolute cutoffs** (score ≥ 80 is ALWAYS Green, ≥ 55 always Yellow; the bar can never ratchet above them) and **floors** (Green ≥ 45, Yellow ≥ 30 so a slump can't erode the gate); with < 5 recent tests a gentle cold-start default (75/55) applies. The result screen now shows the sub-score breakdown and the exact pass bar it used ("Green ≥ 62 · Yellow ≥ 47 (your last 12 tests)"). Rate limiting unchanged (4/day, 60-min cooldown, scaled rest locks — now keyed off the recorded state name with a legacy ≥ 70 fallback). Unit tests: [`ChessReadinessEngineTest`](app/src/test/java/com/example/tail/ChessReadinessEngineTest.kt:1) (49 tests).
- **Chess Phase 2 audit v2.0 — evidence-weighted, personally adaptive** *(added 2026-08-18T19:21Z)* — a single ordinary loss no longer ends the session. Under v1.0, ANY loss to an equal opponent (ΔE = −0.5) breached the fixed −0.35 "severe" threshold and terminated the session instantly, and one prior yellow audit did the same. [`ChessPhase2Engine`](app/src/main/java/com/example/tail/widget/ChessPhase2Engine.kt:51) v2.0 rebuilds the decision in the same spirit as readiness v3.0: (a) **personal ΔE floors** — the severe/moderate bars are the p10/p25 percentiles of your own last ≤15 audited games within 21 days ([`computeDeltaFloors`](app/src/main/java/com/example/tail/widget/ChessPhase2Engine.kt:337)), clamped so they never tighten past −0.45/−0.15 or loosen past −0.75/−0.50; someone who routinely posts −0.5 games isn't flagged for a −0.5 game. (b) **Strain accumulation** — each game adds 0–100 strain (severe-for-you ΔE = 50, moderate = 25, accuracy-drop violation +25, unforced-blunder violation +25); a single bad game can at most PIVOT (pause rated play) — TERMINATION requires the session total to cross 100. (c) **Readiness buffer** — the pre-game Phase 1 CCRS is now an input: a strong test (CCRS ≥ 85) raises the termination bar by up to 30 points and can absorb ONE moderate-or-severe dip in an otherwise clean session entirely (verdict "READINESS BUFFER ABSORBED", still cleared to play). (d) **Hard cutoffs kept** — ΔE ≤ −0.75 (losing to a far weaker opponent), one game with result+accuracy+blunders ALL bad (strain 100), and the 60-minute capacity ceiling still terminate immediately. The audit result screen shows the strain tally ("Strain 50 · session 50/100 (+25 readiness)") and the classification is now personal ("Severe for you — bottom 10% of your games"); the Chess Status overlay shows the live session-strain gauge, your personal ΔE bars, and the updated kick-out rules. Old audits migrate automatically (legacy verdicts map to 50/100 strain). Unit tests: [`ChessPhase2EngineTest`](app/src/test/java/com/example/tail/ChessPhase2EngineTest.kt:1) (30 tests).

- **Chess readiness stats — full-account history backfill + correct variant rating pools** *(added 2026-08-20T11:00Z)* — the Chess Readiness stats screen was only showing a few months of games ("Games logged" / "Played before any test existed" far too small, rating charts starting late). Root cause: the readiness activity log ([`ChessReadinessLogStore`](app/src/main/java/com/example/tail/widget/ChessReadinessLogStore.kt:37)) was fed only by current-month polling and the manual Settings "Fetch Entire Backlog" button — and the old backlog path skipped any month found in the (now removed) aggregate cache, so those months never reached the log. Fixes: (a) [`ChessComRepository.fetchAllArchiveGames`](app/src/main/java/com/example/tail/data/ChessComRepository.kt:238) sweeps EVERY monthly archive back to account creation and hands the raw games of every month to the log (deduped); the useless per-month aggregate cache was removed. (b) A **one-time automatic backfill** runs before the polling loop on first launch ([`backfillChessComHistoryOnce`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:7304)); a marker in the log file makes it run once per username, and a sweep with failed months stays unmarked and retries next launch — habit data is untouched (the manual button remains the deliberate reset+reapply path). (c) [`ChessComService.httpGet`](app/src/main/java/com/example/tail/data/ChessComService.kt:303) now retries 429/5xx/network errors with backoff and archive requests are paced (250 ms), so long sweeps survive chess.com rate limiting. (d) The log's event cap rose 20k → 50k so an entire account history fits. (e) **Variant rating pools are now correct**: chess.com gives Chess960 (and other variants) a SINGLE rating regardless of speed, so [`ratingPoolKey`](app/src/main/java/com/example/tail/data/ChessReadinessStatsCalculator.kt:479) merges all speeds of a variant into one pool with one continuous rating chain — no more phantom separate "Chess960 · Blitz" / "Chess960 · Rapid" charts (Standard chess keeps per-speed pools). Unit tests: [`ChessReadinessStatsCalculatorTest`](app/src/test/java/com/example/tail/ChessReadinessStatsCalculatorTest.kt:1).

- **Chess readiness stats — live refresh on resume + Standard/Chess960 variant selector** *(added 2026-08-20T11:22Z)* — the stats screen loaded its data exactly once per composition, so numbers seen while the (new) full-history backfill was still running stayed stale ("375 games logged" even though the log already held the entire 6 458-game account history — verified against the chess.com archives API: 10 monthly archives, account created 2025-11-04). [`ChessReadinessStatsScreen`](app/src/main/java/com/example/tail/ui/ChessReadinessStatsScreen.kt:125) now keys its load `LaunchedEffect` off a resume counter bumped by a `LocalLifecycleOwner` `ON_RESUME` observer, so returning to the screen always shows the current log. The two ratings sections ("📜 Rating History — Entire History" and "🏆 Rating Impact") also gained a **Standard / Chess960 segmented selector** placed directly above the charts (test-related sections above stay unfiltered — they are variant-independent): Standard shows one pool per speed (bullet/blitz/rapid), while each variant shows its single merged pool; the default selection is the most-played variant, and the "Overall" advantage rows in Rating Impact now respect the selection.

- **Chess readiness — 8 tests/day + "Rating Since Readiness System" graph with clickable change markers** *(added 2026-08-20T12:32Z)* — two changes: (a) the rolling 24-hour test cap was raised **4 → 8** ([`MAX_DAILY_TESTS`](app/src/main/java/com/example/tail/widget/ChessReadinessEngine.kt:55)); the 60-min Green/Yellow cool-down and scaled Red recovery locks are unchanged. (b) A new registry, [`ChessReadinessSystemChanges`](app/src/main/java/com/example/tail/widget/ChessReadinessSystemChanges.kt:27), records every deliberate readiness-SYSTEM rule change (timestamp + full description) as an append-only list in code — currently seeded with readiness v3.0 (adaptive percentile gate), Phase 2 audit v2.0 (evidence-weighted), and the 8/day cap. The stats screen gained a **"📜 Rating Since Readiness System ♟"** section under the entire-history charts (same variant selector applies): the rating timeline zoomed to the first recorded readiness test onward, with a solid gold "♟ start" line at adoption and one gold ◆ per registry entry — **tap a ◆ to open a popup describing that change and its date** ([`RatingSinceSystemChart`](app/src/main/java/com/example/tail/ui/ChessReadinessStatsScreen.kt:1025), tap hit-tested against the shared x-mapping via `pointerInput`/`detectTapGestures`). Future system changes: append one `ReadinessSystemChange` to `ALL` and the marker appears automatically.

- **Chess share-audit fix + deferred game pipeline** *(added 2026-08-22T08:32Z)* — sharing a just-finished game right after passing the readiness test failed with “Game not found yet” even though the link worked in a browser. Root cause (verified against the live chess.com API): the monthly archives of the two players update **independently** — the game was already published under the opponent (`dinmuhamed_055`) while the owner's own archive (`jugglah`) was ~16 h stale, and there is **no by-ID endpoint** on chess.com's public API. Fixes/pipeline: (a) [`ChessComService.findGameById`](app/src/main/java/com/example/tail/data/ChessComService.kt:207) now searches the current+previous month of **every** player; the opponent's name comes from the share text itself ([`ChessGameAuditMapper.parseShareUsernames`](app/src/main/java/com/example/tail/widget/ChessGameAuditMapper.kt:85) — “jugglah vs Dinmuhamed_055”), and the archive JSON is symmetric so a match under either player is complete. (b) When the game is in NO archive yet, the share is parked in the new pending queue ([`ChessPendingGameStore`](app/src/main/java/com/example/tail/widget/ChessPendingGameStore.kt:33)) instead of being lost. (c) The new [`ChessDeferredGameReconciler`](app/src/main/java/com/example/tail/widget/ChessDeferredGameReconciler.kt:41) drains that queue on every chess.com poll and whenever the share sheet opens, and classifies each fetched game by the readiness state **at the moment the game ended** (not at share time): authorized (GREEN test inside its 60-min window, no Yellow/Red audit since) → full Phase 2 audit stamped at the game's end time so sessions/strain/ΔE history stay chronological; otherwise → recorded as **unapproved play** in the Chess Readiness compliance stats. The share dialog's old “rated play not authorized right now” gate was replaced by this game-time classification, so a game shared after its window expired is still handled correctly. Games never shared at all keep flowing through the existing poll → [`ChessReadinessLogStore.logGames`](app/src/main/java/com/example/tail/widget/ChessReadinessLogStore.kt:109) → APPROVED/DENIED/EXPIRED compliance buckets. Unit tests: [`ChessDeferredGameReconcilerTest`](app/src/test/java/com/example/tail/ChessDeferredGameReconcilerTest.kt:1) (8 tests), [`ChessGameAuditMapperTest`](app/src/test/java/com/example/tail/ChessGameAuditMapperTest.kt:1) (+3 tests).

- **Minutes editing crash fix — editing a media habit's minutes OOM-crashed the app** *(added 2026-08-21T11:17Z)* — editing the music habit's minutes in the edit bar was impossible (the field snapped every keystroke back out) and ended in an `OutOfMemoryError` (heap 254/256 MB, ~25 IO threads stuck on GC). Two coupled defects, both fixed: (a) [`setHabitMinutesCount`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:3716) reloaded and re-parsed the ENTIRE habits DB on every keystroke (plus a second full reload via the `HabitIncrementBus` collector); concurrent multi-MB Gson parses piled up on `Dispatchers.IO` and exhausted the heap. It now mirrors [`setHabitSecondaryCount`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:2537): synchronous in-memory `cachedPhoneDb` update + background `persistDatabase`, serialized by a `Mutex` so keystroke bursts never overlap full-file writes and each write persists the latest cached state. (b) The editor rows ([`EditModeValueEditorRow`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:6425), [`EditModeMinutesEditorRow`](app/src/main/java/com/example/tail/ui/HabitGridScreen.kt:6633)) coerced the field text back to the model value on recomposition — but the model lags the debounced write, so the coercion used a STALE value and reverted every keystroke mid-edit (which made the user retype, feeding the parse storm). Text↔model sync now happens only while the field is UNFOCUSED; while focused the field is authoritative and external changes re-sync on blur.

- **Apnea habits migrated to sessions-primary (Protocol v3 `EXTRA_SESSIONS`)** *(added 2026-08-21T16:12Z)* — the five apnea habits (*Apnea apb, Progressive O2, Apnea Min Breath, O2 Tables, CO2 Tables*) were switched from the legacy Wags minutes-primary layout (minutes in the primary slot with a divider, sessions in the custom `secondary_value:` slot with fallback) to **sessions as the main value type and the basis for points, with no divider**. Minutes are still tracked and charted via the built-in minutes value type (`minutes:<habit>` slot + `minutesEnabledHabits`). A one-time flag-guarded migration ([`performApneaSessionsPrimaryMigration`](app/src/main/java/com/example/tail/ui/HabitViewModel.kt:1330), driven by the pure [`swapToSessionsPrimary`](app/src/main/java/com/example/tail/data/HabitModels.kt:342)) swapped the slots in-place: sessions → primary (minutes-only days infer 1 session so done-days/streak history is preserved), old primary minutes → `minutes:` slot (max-merged), `secondary_value:` keys removed; it also cleans the settings sets (drops secondary-value/fallback/minutes-primary/divider entries, enables minutes, sets the value-1 label to "sessions"). The swap is idempotent (safe across migration retries — covered by [`ApneaSessionsPrimaryMigrationTest.kt`](app/src/test/java/com/example/tail/ApneaSessionsPrimaryMigrationTest.kt)). Wags now sends **one atomic broadcast per completed session** (`EXTRA_SESSIONS` + `EXTRA_MINUTES`, handled by [`HabitIncrementReceiver`](app/src/main/java/com/example/tail/ipc/HabitIncrementReceiver.kt:1) via [`incrementHabitWithMinutes`](app/src/main/java/com/example/tail/data/HabitsRepository.kt:588)) instead of separate primary + secondary writes, and its backfill writes sessions to primary + minutes to the `minutes:` slot — the old slots can never be re-polluted. Migration ran on-device and was verified in the synced DB (control habits untouched).

- **Chess readiness v3.1 — form-relative scoring, user-adjustable ~30 % pass target, calibrated 10-point survey** *(added 2026-08-23T16:45Z, target corrected + made a setting 17:10Z)* — three coupled fixes to the readiness gate. (a) **Form-relative objective scoring**: as your chess.com puzzle rating rises the served puzzles get harder, so the old absolute speed tiers mechanically eroded the puzzle sub-score — [`ratedPuzzleScore`](app/src/main/java/com/example/tail/widget/ChessReadinessEngine.kt:620) now scores the ratio of today's average solve time to your recent average (last ≤10 tests, min 3; the absolute tiers remain only as the cold-start fallback), and Puzzle Rush is measured against the recent **median** instead of the all-time high ([`rushScore`](app/src/main/java/com/example/tail/widget/ChessReadinessEngine.kt:663)), whose one-way ratchet made typical sessions look ever weaker. (b) **User-adjustable Green pass-rate target** *(corrected same day — the original "~70 %" request was a misspeak for the opposite)*: the Green bar sits at the (1 − target) percentile of the recent window ([`computeThresholds`](app/src/main/java/com/example/tail/widget/ChessReadinessEngine.kt:461)) — v3.0's 60th-percentile bar passed ~40 % by construction; the corrected 30 % default places Green at the 70th percentile (top-of-form days only), with Yellow trailing 25 percentile points below. The target is a **setting** (Settings → ♟ Chess Readiness → Green Pass-Rate Target, 5–95 % in 5 % steps, persisted in [`ChessReadinessStore`](app/src/main/java/com/example/tail/widget/ChessReadinessStore.kt:256)) and applying a change requires a confirm step that warns against frequent changes and shows how long the current target has been held plus the readiness tests and games logged under it — enough time at one value to see how the rating responds. The absolute cutoffs (80/55), floors (45/30) and cold-start defaults are unchanged. (c) **10-point calibrated survey**: the self-survey sliders are now 1–10 (defaults 6) with all stored past results **2×-migrated** idempotently in every persistence layer (in-progress session via the `clarityV3` flag in [`ChessReadinessStore`](app/src/main/java/com/example/tail/widget/ChessReadinessStore.kt:335), the permanent log via the `surveyScale10` marker in [`ChessReadinessLogStore`](app/src/main/java/com/example/tail/widget/ChessReadinessLogStore.kt:315)). The survey's weight in the clarity sub-score is now **earned by accuracy**: [`surveyCalibration`](app/src/main/java/com/example/tail/widget/ChessReadinessEngine.kt:758) compares your reported clarity to what the objective puzzle+rush results implied across the last ≤20 paired tests (min 6) and computes an MAE; the weight runs 0.35–1.0 (0.35 + 0.65·(1−MAE/3)) and the effective clarity blends your report with the objective anchor proportionally — honestly reporting a bad day keeps full voice, while consistently inflated reports shrink toward what the board actually showed. The result screen prints the live trust line ("Survey trust 100 % · avg gap 0.4 (last 12 tests)"), and each finished test persists its telemetry (clarity average, puzzle seconds, rush score, sub-scores) into the rolling history so calibration and baselines survive restarts. Registry: v3.1 + v3.1.1 entries in [`ChessReadinessSystemChanges`](app/src/main/java/com/example/tail/widget/ChessReadinessSystemChanges.kt:42) (each appears as a ◆ marker on the "Rating Since Readiness System" chart). Unit tests: [`ChessReadinessEngineTest`](app/src/test/java/com/example/tail/ChessReadinessEngineTest.kt:1).

- **Chess Guard fix — Phase 2 audits now limit an active session (missing yellow warning)** *(fixed 2026-08-23T17:10Z)* — passing the readiness test and then playing a bad first game left the app FULLY unlocked for the whole 60-minute Green window: the guard's policy ([`ChessEnforcementPolicy.evaluate`](app/src/main/java/com/example/tail/widget/ChessEnforcementPolicy.kt:119)) only looked at the last Phase 1 test, so the Phase 2 audit's PIVOT_TO_DRILLS verdict ("rated play prohibited") was invisible to it — re-entering the chess app showed no full-screen yellow casual-only warning and rated play stayed authorized. The policy now also takes the latest Phase 2 audit ([`ChessPhase2Store`](app/src/main/java/com/example/tail/widget/ChessPhase2Store.kt:139)): a PIVOT verdict filed AFTER the authorizing test downgrades the session to YELLOW_SESSION (casual only — the guard fires the full-screen warning on every entry, and a detected rated game still costs the 24 h penalty), and a TERMINATE_SESSION verdict blocks the app entirely (new `SESSION_TERMINATED` reason, wall + HOME kick) until the re-test cool-down ends. Audits older than the last test are ignored (a fresh pass re-authorizes play), mirroring the deferred reconciler's "no Yellow/Red audit since the green test" authorization rule; [`appendAudit`](app/src/main/java/com/example/tail/widget/ChessPhase2Store.kt:139) now fires [`ChessGuardNotifier`](app/src/main/java/com/example/tail/widget/ChessEnforcementPolicy.kt:230) so external automation stays in sync the moment an audit lands. Unit tests: [`ChessEnforcementPolicyTest`](app/src/test/java/com/example/tail/ChessEnforcementPolicyTest.kt:1) (+6 tests).

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

---

## Google Drive backup setup *(added 2026-08-15T15:32Z)*

Google Sign-In with the `drive.file` scope only works after the app is registered
as an OAuth client in Google Cloud Console. Without this, the account picker opens,
an account can be chosen, and sign-in then fails with **code 10 (DEVELOPER_ERROR)**.

One-time setup (any Google account, ~5 minutes):

1. Open <https://console.cloud.google.com/> and create (or pick) a project, e.g. `tail`.
2. **APIs & Services → Library** → search **Google Drive API** → **Enable**.
3. **APIs & Services → OAuth consent screen** → User type **External** → fill in
   App name (`Tail`), user support email, developer contact email → Save.
   (Personal use: leave it in *Testing*; no scope verification needed for `drive.file`
   with only your own account.)
4. **Add yourself as a test user** — on the same OAuth consent screen page, under
   **Test users** → **+ Add users** → add the exact Google account you sign in with
   in the app. *Required:* while the app is in *Testing* status, Google blocks
   everyone else with `Error 403: access_denied` ("app is currently being tested,
   can only be accessed by developer-approved testers").
5. **APIs & Services → Credentials → Create credentials → OAuth client ID → Android**:
   - Package name: `com.example.tail`
   - SHA-1 (debug keystore): `D3:3B:39:6C:CE:65:75:42:4C:44:99:37:5C:0F:E9:FF:54:04:CD:00`
6. Wait ~5–10 minutes for propagation, then in the app: Settings → Backup & Restore
   → **Sign in with Google**.

Notes:

- The debug SHA-1 comes from `~/.android/debug.keystore`:
  `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android`
- After creating the client, Google shows a **Client ID / secret** and offers a
  JSON download — the app does **not** need them. Android OAuth clients are matched
  automatically via package name + SHA-1; the secret is only used by server-side
  "Web application" flows. If you keep the JSON, keep it out of the repo.
- A release build signed with a different keystore needs its own SHA-1 added as a
  second entry under the same OAuth client.
- **Testing-mode token expiry:** while the consent screen is in *Testing*, Google
  expires grants after ~7 days, so Drive may occasionally ask to re-consent. To
  avoid this, publish the app (*OAuth consent screen → Publish app* → confirm).
  With the non-restricted `drive.file` scope this works unverified for personal
  use — the first sign-in shows an "unverified app" screen: tap
  **Advanced → Go to Tail (unsafe)** and continue.
- If a Drive operation ever fails with "Google needs you to approve Drive access",
  the app automatically opens the consent screen on the next tap — approve and retry.
