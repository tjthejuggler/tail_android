# Phase 2 — Lizard Pose Generation (complete hand-off prompt)

Copy everything below the line into a fresh CMM-Code session.

---

You are working in the Android app repo at `/home/twain/AndroidStudioProjects/tail` (package `com.example.tail`). This is a habit-tracker app ("tail") with a steel/metal theme. Your job is **Phase 2 of the "shimmer lizard" feature**: generating 10 new lizard POSE images for the ORANGE lizard only (verification round), wiring them into the app's random perching system, and validating on a real device. Once the user confirms orange works, a later step repeats generation for all other lizard colors — but NOT in this session unless the user says so.

## 1. Background: what already exists (Phase 1, complete and working)

- The app's main habit screen draws a faint "ghost lattice" of glass squares behind the UI (`app/src/main/java/com/example/tail/ui/SteelPanel.kt`, `Modifier.ghostGlassSquares`). An idle "shimmer" wave sweeps across the whole lattice in random directions (always in pairs: a forward leg immediately followed by its opposite return leg — see `HabitGridScreen.kt` lines ~570-600).
- A lizard (the tier-bar widget lizard, drawable `tier_bar_lizard_t{0..12}`) used to sit centered on the deep back-wall horizon; Phase 1 changed this so the lizard is randomly PERCHED like a real lizard under gravity, on every other shimmer leg (stable for the forward+return pair):
  - **STAND** on top of a horizontal run of occupied habit squares, **HANG** upside-down under them, or **CLIMB** the side of a stack of squares or the phone's left/right screen edge.
  - The habit grid is 8 columns × 10 rows (`GRID_COLUMNS=8`, `GRID_ROWS=10`, 80 cells; empty-name habits and missing entries are empty cells). The real grid publishes its occupancy via `GhostSceneState.publish(...)` in `HabitGridScreen.kt` (~line 3658).
  - Solver + variant model: `app/src/main/java/com/example/tail/ui/LizardPerch.kt`. `buildLizardVariants()` bakes 8 variants (rotations 0/90/180/270 × mirrored) from the single tier strip and derives each variant's cell FOOTPRINT from the alpha bounding box (aspect → 1 cell tall × N wide, or 1 wide × N tall). `randomLizardPerch()` enumerates every valid (footprint-empty + surface-solid) placement and picks one at random.
  - Drawing: in `SteelPanel.kt` (~line 698, section "Lizard — random perched placement"). The perch is drawn at TRUE FLAT-GRID geometry (`anchorTop + row*cell`, `outerPad + col*cell`) — the exact lattice the real habit buttons occupy — sliced into sliver-cells that each sample the shimmer wave at their fractional lattice (row, col) so the reveal stays locked to the shimmer line. Drawn only during a sweep (invisible at rest), max alpha ~0.45.
- Reference material for how lizard art was previously generated: the `wallpaper_gen/` folder contains the python generation pipeline (e.g. `gen_lizard_tiers.py`, `gen_lizard_ages.py`, `gen_widget_chameleon.py`) used to create the existing tier strips. Read those first to learn conventions (API usage, prompts, post-processing, output paths).

## 2. What the lizard assets are

- One horizontal 4:1 transparent strip per tier, e.g. `app/src/main/res/drawable*/tier_bar_lizard_t0.png` … `tier_bar_lizard_t12.png`. The tiers go from small young lizards (low tiers, red/orange) up to big impressive ones (yellow, white, white+color combos at the top). Determine which tier index(es) are ORANGE by inspecting the generation scripts in `wallpaper_gen/` and/or the PNGs themselves (`gen_lizard_tiers.py` should document the color order). Orange is likely one of the first few tiers.
- The in-app lizard is chosen by `TierStateStore.load(context).dayTier` (see `SteelPanel.kt` ~line 259).

## 3. The 10 new poses (orange only this round)

Generate 10 distinct poses of the SAME orange lizard, matching the existing art style (compare with the current tier strips — same rendering style, lighting, and level of detail). Poses progress from plain to spectacular:

1. Classic side-profile walk (similar to current strip but tighter, ~2×1 cells)
2. Face-on / looking at the viewer, body foreshortened — small footprint (~1×1 or 2×1 cells)
3. Partially curled up / coiled like a cat napping (~2×2 cells)
4. Climbing stairs — body spanning a staircase of squares (e.g. 3×2 cells, belly on a diagonal run)
5. Fully stretched out flat along a wall edge (1×4 cells)
6. Lizard with a prop: holding/looking at a tiny smartphone (~2×2)
7. Lizard draped over a pipe running between squares (~3×2)
8. Lizard in a tiny hammock slung between two squares (~3×2)
9. Lizard doing a push-up / work-out on top of a square, small dumbbell prop (~2×1)
10. Showstopper: orange lizard perched proudly on a small treasure hoard / trophy on top of a square platform (~3×3)

(Feel free to refine/embellish these into better concrete prompt ideas — e.g. chess-piece props, meditation pose with tiny incense, fishing rod dangling off a square's edge — as long as the progression plain→impressive is kept and every pose is physically anchored to grid squares. For future reference: higher tiers (yellow, white, white+color) will get even MORE impressive and unique poses — keep the prompt templates parameterized by tier/color so this scales.)

### CRITICAL: "dummy squares"

Each pose image must include NEUTRAL DUMMY SQUARES that the lizard interacts with — standing on, hanging under, climbing. These represent the app's habit squares and MUST be exactly the right size: each dummy square occupies exactly ONE grid cell in the pose's cell canvas. The lizard's contact points (feet, belly, tail) must rest believably against the dummy squares' faces/edges. In-app, only the lizard is composited (habit squares themselves are real UI); the dummy squares are removed together with the background (see chroma technique below).

### Canvas convention

- Compose each pose on a cell-grid canvas: N×M cells at a fixed pixels-per-cell (suggest 512 px/cell, e.g. a 3×3 pose = 1536×1536). The manifest (below) records N, M and where the dummy squares are, so the app can verify scaling.
- Name outputs e.g. `lizard_pose_t{tier}_p{00..09}.png` (+ a `lizard_pose_manifest.json`).

## 4. Generation pipeline requirements

- Use the **best image generation/edit model available on ppq.ai** (check their current model list; use the strongest image EDIT/variation model so the lizard's identity, color and style stay consistent with the original strip).
- ppq.ai cannot reliably produce transparency, so use this chroma workflow:
  1. Programmatically composite the ORIGINAL orange lizard strip onto a SOLID BACKGROUND COLOR that is completely absent from the lizard (pick something like pure magenta #FF00FF or a saturated green — verify against the strip's actual palette; document the choice).
  2. Send that composited reference to ppq.ai with a pose-specific prompt that explicitly instructs: keep the same lizard character/style/color; render the pose; include the neutral dummy squares at the specified sizes; return the image on the SAME solid background color, filling the entire background with it.
  3. Post-process: remove the chroma background AND the dummy squares (same removal pass — make the dummy squares the same chroma color, or a second documented key color if that proves easier for the model), feather/clean edges (the `wallpaper_gen/` scripts likely already have bg-removal utilities to reuse), and verify with an alpha-bbox scan + a per-cell occupancy check against the manifest.
- Write the pipeline as a reusable python script in `wallpaper_gen/` (e.g. `gen_lizard_poses.py`) so the remaining tiers later are just a config change. Cache API results; log failures like the existing scripts do.
- API keys: look for existing ppq.ai credentials in the repo/env the same way `wallpaper_gen/generate_images.py` obtains them; if missing, ask the user.

## 5. App integration (must be done this session)

Phase 1 derives footprints from the bitmap aspect — that no longer works for arbitrary poses. Update `LizardPerch.kt`:

- Replace `buildLizardVariants()`'s aspect-derived footprints with per-pose METADATA from the manifest: for each pose: grid footprint (cells the LIZARD occupies, excluding dummy-square cells), the supported surface side(s) (BELOW/ABOVE/LEFT/RIGHT — some poses may support several, e.g. a pose generated on top of squares supports BELOW; mirrored/rotated baking can derive others ONLY when it stays physically sensible), and cells-per-side for scaling.
- Keep `randomLizardPerch()`'s solver contract (footprint-empty + surface-solid + random pick) — extend `LizardVariant` to carry arbitrary `rows`/`cols` and possibly multiple allowed surfaces.
- Asset loading in `SteelPanel.kt`: for the current dayTier, load all available `lizard_pose_t{tier}_p*` variants (fallback to the classic strip's 8 baked variants when a tier has no poses yet). The pose bitmap is drawn scaled so ONE canvas cell == ONE lattice cell, positioned by the pose's footprint metadata, using the existing flat-grid draw path and sliver shimmer sampling (do not regress: lizard must NEVER be covered by habit squares; stable across the forward+return leg pair).
- The pose canvas is anchored so the dummy-square positions in the canvas align exactly with the real occupied habit squares chosen by the solver — i.e. placement validation must also check that the pose's dummy squares land on cells that are actually occupied (solid), and the lizard's footprint cells are empty.

## 6. Verification & delivery

1. Run the orange pose generation (all 10 poses).
2. Verify programmatically: transparency, correct footprint occupancy vs manifest, no chroma residue.
3. Integrate, build with `./gradlew installDebug`, and hand the phone to the user to watch several shimmer cycles: lizard should appear in a random valid spot every OTHER leg (stable per pair), in all 10 poses over time, never covered by squares.
4. Do NOT generate the other colors yet — wait for the user's confirmation. Keep `gen_lizard_poses.py` ready so the other tiers are a re-run.
5. Keep README.md updated with a timestamped entry, and persist the key design decisions via the codebase-memory MCP (`manage_adr`) if available.

