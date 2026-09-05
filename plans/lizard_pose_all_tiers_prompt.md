# TASK: Generate pose art for ALL lizard tiers (Phase 3) — full prompt for a fresh agent

You are working in the Android app repo at `/home/twain/AndroidStudioProjects/tail`
(package `com.example.tail`), a steel/metal-themed habit tracker. A previous
session completed "Phase 2": generating 10 shimmer-lizard POSE images for the
ORANGE lizard only (tier 1), plus all the pipeline and app integration work.
Your job is **Phase 3: run pose generation for every remaining tier (0, 2, 3,
4, 5, 6, 7, 8, 9, 10, 11, 12), with per-tier unique pose design**, verify
everything, and leave the docs updated. The pipeline, manifest format and app
integration are ALL already built and battle-tested — read carefully, because
every rule below was learned by hitting a real failure this session.

---

## 1. What already exists (DO NOT rebuild any of this)

### Generation pipeline (complete, resumable, rule-encrusted)
- [`wallpaper_gen/gen_lizard_poses.py`](../wallpaper_gen/gen_lizard_poses.py) — THE pipeline. READ IT FULLY FIRST. It contains, all working:
  - ppq.ai image-EDIT calls: `https://api.ppq.ai/v1/chat/completions`, model
    `google/gemini-3-pro-image` (Nano Banana Pro — the strongest editor on
    ppq.ai; the weaker `gemini-2.5-flash-image` produced duplicate lizards,
    pink halos and chroma-colored props, do not go back to it).
  - API key: read from `/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt`.
  - Response parsing quirk: the returned PNG may arrive under
    `message.images` OR as base64 in `message.audio.data` — the script
    handles both. Retries with exponential backoff.
  - **Chroma reference builder** (`build_reference`): solid magenta
    `#FF00FF` background + BORDERLESS solid green `#00FF00` dummy squares
    (each exactly one grid cell, 512 px/cell) + the tier's original strip
    pasted as identity reference. Do NOT add borders to the squares — a
    darker border taught the model to draw leftover outlines.
  - **HUE-based chroma keying** (`key_out`) + despill pass — NOT exact-RGB
    keying (the model never reproduces exact key RGBs; exact matching left
    outlines and tinted blocks). Green tiers must not use green squares:
    `SECONDARY_KEYS = {2: cyan, 9: cyan}` already handles that.
  - **Grid-fit affine correction** (`align_to_grid`): warps the raw so the
    painted dummy squares land exactly on the manifest's cell rects
    (scale factors clamped 0.75–1.35 to reject false green detections).
  - **Overlap erase**: after keying, every pixel inside each dummy-cell rect
    (inset ~4.7% = the real square face) is erased, making it STRUCTURALLY
    IMPOSSIBLE for the lizard to draw in front of habit squares in-app.
  - **Size normalizer** (`normalize_lizard_size`): measures the lizard's
    vertical bulk (P90 of per-column opaque runs, dummy cells excluded) and
    rescales the lizard layer about its BOTTOM CONTACT LINE into the pose's
    `bulk=(lo, hi)` band (in cells). Factor clamped [0.8, 3.0] (source is
    12× oversampled vs on-screen, so upscaling is safe) plus a canvas
    fit-cap. Beyond 3× → regenerate instead.
  - **verify()** checks: dummy cells empty, lizard not sparse, FLOATING
    check (grounded poses must touch the surface within 35% of a cell),
    bulk-in-band check, top-edge clipping check.
  - **Manifest writer** (`write_manifest`): merges per-tier into
    `app/src/main/assets/lizard_pose_manifest.json`
    (`{"px_per_cell": 512, "tiers": {"1": [{file, name, cols, rows,
    anchored, bulk, dummies, cells}, ...]}}`). `cells` are row strings,
    '1' = dummy square (must land on an OCCUPIED habit square), '0' = lizard
    cell (must be EMPTY).
  - CLI: `--tier N` (default 1), `--only N` (regen one pose index),
    `--reprocess` (re-run postprocess from cached raws with NO API calls),
    `--model` (override). Outputs cached in `wallpaper_gen/raw/poses/`
    (`ref_*, raw_*, pose_t{tier}_p{NN}.png`), finals written to
    `app/src/main/res/drawable-nodpi/lizard_pose_t{tier}_p{NN}.png`.
  - Python 3.12, PIL only (NO numpy installed — don't add it).

### App integration (complete, tier-generic, NO Kotlin changes expected)
- [`LizardPerch.kt`](../app/src/main/java/com/example/tail/ui/LizardPerch.kt):
  `LizardVariant` (with `dummyCells` + `isPoseCanvas`), `SharedPerchState`
  (process-wide roll state so the forward+return shimmer LEG PAIR always
  shows the SAME spot+pose on every panel), `buildPoseVariants`,
  `loadLizardPoseAssets` (reads the manifest + `lizard_pose_t{tier}_p*`
  drawables by tier), `randomLizardPerch` (pose canvases: exact
  dummy-cell↔occupied-cell coincidence; strip variants: legacy surface
  rules; only variants that actually FIT are eligible; keeps last perch
  rather than going blank).
- [`SteelPanel.kt`](../app/src/main/java/com/example/tail/ui/SteelPanel.kt):
  loads poses for `TierStateStore.load(context).dayTier` and always bakes
  the phase-1 strip's 8 rotations as fallback (wall-climbing + hanging are
  strip-only niches); draws pose canvases pixel-exactly on the flat lattice
  AFTER `drawContent()` so habit squares can never cover the lizard.
- Tier 1 is fully shipped and verified on device. **Do not regress it.**
- `TierStateStore.load(context).dayTier` (0–12) picks which tier's poses are
  shown — it advances as habit points grow, so after generating everything
  the user sees different poses over time automatically.

### Tier table (from `gen_lizard_tiers.py` — age/size ladder)
| tier | color | persona |
|------|-------|---------|
| 0 | red | baby (smallest) |
| 1 | orange | young kid — DONE, shipped |
| 2 | green | kid/teen (GREEN accents → cyan square key!) |
| 3 | blue | teen |
| 4 | pink | teen/young adult |
| 5 | yellow | adult (largest colored) |
| 6 | pure white | rare — elder/mystic |
| 7 | white+red | very rare — warrior |
| 8 | white+orange | very rare — artisan/forge-master |
| 9 | white+green | very rare — alchemist/nature-mage (GREEN accents → cyan square key!) |
| 10 | white+blue | very rare — storm/ice savant |
| 11 | white+pink | very rare — heart-mender |
| 12 | white+yellow | rarest — sun-king showstopper |

---

## 2. THE HARD-WON RULES (every one was a real failure this session)

1. **Chroma keys**: bg = pure magenta `#FF00FF`; dummy squares = pure green
   `#00FF00` EXCEPT tiers 2 and 9 (green-accented lizards) → use cyan
   `#00FFFF` squares (`SECONDARY_KEYS` already maps this). Keys appear ONLY
   as flat fills, never on the subject, its glow, eye, or props.
2. **Prompt hard clauses** (already in `prompt_for()` — keep them all):
   exactly ONE chameleon (no duplicates/reflections/mini copies); no glow,
   halo, orb, aura or light ring around subject or props; keys only as flat
   fills; squares stay exactly where they are, same size, unmoved; whole
   background flat magenta edge to edge; chameleon physically touches the
   squares (believable gravity); chameleon entirely INSIDE the canvas, never
   covering a square except gripping its surface; chameleon stays ENTIRELY
   ABOVE the square TOP edges (only toes grip the top face; explicitly
   hanging parts are the exception); **SCALE clause** — "this is a LARGE
   chameleon, torso about AS TALL AS ONE GREEN SQUARE, never small relative
   to the squares".
3. **Anchoring rule**: every pose is physically anchored (standing on /
   hanging under / draped over) UNLESS the pose's action inherently requires
   mid-air (hang-glider, mid-jump, levitating meditation) — those set
   `"anchored": "airborne"` in the pose def. Grounded poses that float are a
   defect → regenerate.
4. **Scale rule**: pose variety = posture variety, never animal size. Each
   pose def carries `bulk=(lo, hi)` (torso height in cells). Bands are
   posture-derived: stretched/pipe/hammock/low-slung ~0.30–0.55; standing/
   walking/treasure ~0.30–0.65; upright (phone/pushup/face_on) ~0.48–0.75;
   curled ~0.70–0.95. Derive the band from the posture, verify against the
   first good roll, and let the normalizer enforce it.
5. **Overlap rule**: the model tends to draw body parts in front of the
   squares; prompt demands staying above the top edges, and the per-cell
   erase makes any residual overlap harmless (it reads as BEHIND the
   square). Regenerate poses with overlap warnings >400px — heavy erase
   cuts become visible.
6. **Reference squares must be borderless** solid key color.
7. **Accept/reject loops**: generation is stochastic. Wrap each pose in a
   retry loop that deletes `app/src/main/res/drawable-nodpi/lizard_pose_t{N}_p{NN}.png`
   + `wallpaper_gen/raw/poses/raw_t{N}_p{NN}.png` and re-runs
   `--only NN` until the script's WARN lines are clean (bulk, floating,
   clipping) and a density floor passes (file size >300 KB has worked as a
   quick sparseness gate for detailed poses like the hammock). Expect
   ~1–3 rolls per pose; more for complex prop poses.
8. **Vision QA**: after each tier, LOOK at every image yourself (native
   vision) checking: exactly one lizard, prop present and correct, no
   chroma-colored contamination on subject/props, correct pose, not cut off,
   believable contact. Also run the programmatic final check (the
   spill/bulk/floating script appears in git history and README; or rewrite
   it — it reads the manifest and checks each PNG).

---

## 3. YOUR MAIN CODE TASK: per-tier pose design

`pose_defs()` currently returns ONE static list of 10 tier-1 poses. Restructure it to:

```python
POSES_BY_TIER = {tier: [dict(...), ...], ...}   # tier int -> list of pose dicts
def pose_defs(tier): return POSES_BY_TIER[tier]
```
and update `main()` accordingly (the `--only N` index then refers to the
tier's own list). Pose dicts keep the same keys: `name, cols, rows, dummies,
pose, bulk, anchored (optional), avoid (optional)`.

### Design philosophy (from the user, verbatim intent)
- **Basic poses may repeat across tiers** (they're the "normal life" of the
  lizard): side-profile walk, face-on/foreshortened, curled-up nap, fully
  stretched. Keep 3–4 of these per tier, re-worded slightly per age.
- **The rest are UNIQUE per tier** — props, tools, toys, activities that
  match the lizard's AGE and PERSONALITY. Never repeat a unique pose across
  tiers. Widen variety: think hobbies, jobs, games, foods, tiny machines,
  sports, music, magic.
- **Age arc**: baby tier (0) = baby things; kids/teens (1–4) = play, school,
  gadgets; adult (5) = work/hobby mastery; white elder (6) = mystic; white+
  color combos (7–12) = EXCEPTIONALLY unique, impressive, unbelievable
  showpieces — these are the rarest tiers and must look legendary.
- 10 poses for tiers 0–5; **12 poses for tiers 6–12** (they're rare, give
  them more). Keep canvas sizes ≤ 5 cols × 4 rows (the solver grid is
  8×10; bigger canvases rarely fit).

### Suggested starting sets (improve/replace freely — variety is the goal)

- **t0 red baby**: walk; curled nap under a tiny blanket; face-on; sitting
  inside a half eggshell (hatching); chasing a tiny wind-up toy; holding a
  baby bottle; tiny pacifier; riding a toy duck; in a tiny cradle swing
  (anchored); balloon tied to tail (airborne).
- **t2 green kid** (cyan squares!): walk; curled; face-on; skateboard on a
  ramp-block; backpack + pencil; fishing rod off a block edge; game
  controller; magnifying glass bug-hunt; slingshot; soccer ball dribble.
- **t3 blue teen**: walk; curled; face-on; headphones + music player;
  bicycle lean; laptop typing; chemistry set; paper airplane fleet;
  basketball dunk on a block hoop; spray-paint mural (spray can).
- **t4 pink young-adult**: walk; curled; face-on; selfie with tiny phone;
  karaoke mic; ramen bowl + chopsticks; vinyl turntable DJ; yoga pose;
  watering tiny flowers; Polaroid camera + photo pile.
- **t5 yellow adult**: walk; curled; face-on; coffee mug + newspaper;
  toolbelt wrenching a pipe; grill with tiny skewers; briefcase + umbrella;
  chess board mid-game; guitar; telescope stargazing.
- **t6 pure white elder (rare — mystical begins)**: meditation levitation
  (airborne — the session's planned future pose, it finally ships here);
  tea ceremony; calligraphy brush; crystal ball scrying; bonsai pruning;
  staff with glowing orb; hourglass time-mage; incense coils; star map;
  koi pond watching; moon-gazing with crown; floating rune ring (airborne).
- **t7 white+red warrior**: knight guarding the hoard (tiny sword + shield);
  forge bellows; sparring dummy drills; war-drum; banner pole; catapult;
  armory rack polish; gladiator pose on block podium; siege-ladder climb;
  torch-wallas; victory roar on fallen helm; epic treasure-guard finale.
- **t8 white+orange forge-master**: anvil hammering glowing ingot; blowtorch
  welding; gear sculpture; steam-punk engine repair; blueprint + compass;
  kiln with pottery; furnace poke; lava-lamp lab; clockwork bird assembly;
  molten pour; spark grinder; masterwork armor display finale.
- **t9 white+green alchemist** (cyan squares!): bubbling flasks; herb
  drying rack; mortar & pestle; terrarium tending; potion shelf mixing;
  glowing plant infusion; butterfly familiar; crystal growth chamber;
  vine-wrapped staff; smoke swirl divination; elixir tasting; overgrown
  ruin garden finale.
- **t10 white+blue storm savant**: tesla coil with arcs; ice sculpture;
  lightning-in-a-bottle; telescope + aurora chart; Faraday cage demo;
  snow-globe blizzard; satellite dish whisper; static levitation of bolts
  (airborne); rain drum; prism rainbow split; circuit-board city; thunder
  anvil strike finale.
- **t11 white+pink heart-mender**: bandaging a tiny wounded robot mouse;
  origami crane flock; flower crown weaving; music-box lullaby; balloon
  heart gift; feeding doves; love-letter + quill; bubble-blowing; patchwork
  quilt sewing; carousel wind-up; halo of floating hearts (airborne); grand
  humane society finale.
- **t12 white+yellow sun-king (rarest — go all out)**: enthroned on a stack
  of blocks with radiant crown; holding a tiny glowing sun orb (airborne
  levitation of the orb); golden chariot pulled by two robo-beetles;
  coronation with robot court bowing; sunrise conductor with baton and
  light ribbons; solar-panel garden tending; gilded scale weighing stars;
  phoenix egg hatching; hourglass of ages; light-beam harp; world-globe
  orrery; the ultimate hoard: sun disc + all previous tiers' props piled
  around him.

Each pose def needs concrete `dummies=[(row, col), ...]` (row 0 = top) and
`cols`/`rows` (canvas cells) with the lizard physically interacting with
those dummy cells; keep 2–4 dummies, canvas ≤5×4. Reuse the dummy layouts
from the existing tier-1 defs as templates.

---

## 4. Execution plan

1. **Read first**: `wallpaper_gen/gen_lizard_poses.py` (fully),
   `wallpaper_gen/gen_lizard_tiers.py` (tier colors), the two lizard ADRs
   (`manage_adr` get on project `home-twain-AndroidStudioProjects-tail`;
   if the project isn't indexed, `index_repository` with the ABSOLUTE path
   first), the 2026-09-05 entries in `README.md`, and skim
   `app/src/main/java/com/example/tail/ui/LizardPerch.kt` +
   `SteelPanel.kt` lizard sections.
2. **Refactor** `pose_defs()` → `POSES_BY_TIER` per §3; update `main()`.
   Keep tier 1's existing 10 defs EXACTLY as they are (shipped art).
3. **Generate tier by tier** (0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12) —
   `python3 wallpaper_gen/gen_lizard_poses.py --tier N`. The script is
   resumable (skips existing outputs). Budget ~1–3 rolls per pose; use
   `--only NN` retry loops for rejects. Watch for: hue warnings on green
   tiers (2/9 must use cyan squares — verify in the reference PNG), duplicate
   lizards (reject), props painted in key colors (reject), missing props
   (reject), floating (reject), bulk out of band (normalizer fixes ≤3×,
   else reject). Vision-audit EVERY image; be strict — a bad sprite ships
   to the phone.
4. **After each tier**: run the final programmatic check (spill inside dummy
   cells == 0, bulk in band, floating ok), fix rejects, then move on.
5. **App side**: expected to need NO changes — `loadLizardPoseAssets` and
   `buildPoseVariants` are tier-generic and the manifest merges per tier.
   If anything Kotlin-side needs touching, keep changes minimal and rebuild.
6. **Deploy once at the end** (or per tier if you prefer):
   `./gradlew installDebug` (SM-S918U1 connected). Note: the phone shows the
   poses of the USER'S CURRENT dayTier, so they won't see all tiers at once —
   that's by design (tier = habit streak).
7. **Docs**: append a timestamped entry to `README.md` (top of the changelog
   section, style of the 2026-09-05 entries) listing every tier's pose names;
   update the lizard ADR via `manage_adr` (mode=update, project
   `home-twain-AndroidStudioProjects-tail`) with tier count, pose themes per
   tier, and any new failure modes discovered.
8. **Never** touch `memory-bank/` (other modes own it), never remove tier 1
   assets, never regress the shipped behavior (pair-stable perch, no
   blank shimmers, lizard never behind squares, size/anchoring rules).

## 5. Facts you'll need

- Workspace: `/home/twain/AndroidStudioProjects/tail`; shell bash; run all
  python from repo root. Python 3.12 + Pillow installed; NO numpy.
- API: ppq.ai `/v1/chat/completions`, model `google/gemini-3-pro-image`,
  key file `/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt`.
  Each pose ≈ 1–3 paid calls; total for 12 tiers ≈ 140–160 poses — budget
  accordingly and lean on `--reprocess` (free) for post-only changes.
- PX_PER_CELL=512; finals are RGBA PNGs in
  `app/src/main/res/drawable-nodpi/`; manifest in `app/src/main/assets/`.
- Build/deploy: `./gradlew installDebug` (~2–4 min).
- If codebase-memory graph tools fail with "project not found", run
  `index_repository` with the absolute path `/home/twain/AndroidStudioProjects/tail`.

Begin by reading the pipeline script and the current manifest, then refactor
`pose_defs`, then generate tier 0 and show the results before continuing to
the rest (the user likes to eyeball each tier's set).
