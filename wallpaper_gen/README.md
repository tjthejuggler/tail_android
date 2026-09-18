# Wallpaper Generator Pipeline

Generates 100 phone wallpapers (1440x3088) pairing A–Z animal artwork with
habit-tracking letters, stepped down the canvas by letter index.

## Pipeline

1. **Spec** — `SPEC.md` defines the design goals and constraints.
2. **Concepts** — `habits.txt` (habit list) + `animal_pool.json` (candidate
   animals) were used to derive `concepts_*.json`: one entry per wallpaper `n`
   (0–99) with the animal `label` (initial letter = `chr(ord('A') + ((n-1) % 26))`
   for n ≥ 1; n=0 is unlabeled).
3. **Prompts** — the concepts were expanded into image-generation prompts in
   `prompts_00_49.json` and `prompts_50_99.json`.
4. **`generate_images.py`** — calls the image-generation API (key read from
   `~/Projects/small_scripts/ppq_imageGen_apikey.txt`, never stored here) to
   produce 100 square PNGs in `raw/result_0.png` … `result_99.png`.
   `gen_failures.json` records any failed/retried generations.
5. **`compose_final.py`** — composes each final wallpaper: 1440x3088 black
   canvas, the 1440x1440 artwork pasted at y-offset `60 * L` (capped at 1648),
   where `L = (n-1) % 26`, and the animal label drawn in white above the
   artwork. Output: `final/result_0.png` … `result_99.png`.

## File layout

| Path | Purpose |
|---|---|
| `SPEC.md` | Design spec |
| `habits.txt` | Habit list input |
| `animal_pool.json` | Candidate animals |
| `concepts_*.json` | Per-wallpaper concept + label |
| `prompts_*.json` | Image-generation prompts |
| `generate_images.py` | API image generation → `raw/` |
| `compose_final.py` | Composition → `final/` |
| `gen_failures.json` | Generation failure log |
| `raw/` | 100 square PNGs (gitignored) |
| `final/` | 100 1440x3088 wallpapers (gitignored) |

## Regenerating

```bash
python3 generate_images.py   # needs API key file + network; fills raw/
python3 compose_final.py     # PIL only; fills final/
```

`compose_final.py` verifies every output is 1440x3088 and prints an offset
summary; rerun it alone any time to rebuild `final/` from `raw/`.

## Lizard per-point growth morphs (widget) — ALL 12 SPANS — 2026-09-17

`gen_lizard_morph.py` generates the point-by-point GROWTH art between two
tier milestones: the shape eases from the smaller milestone lizard toward
the next one while the glow colour stays the YOUNGEST tier's colour
(colour is points-based; only shape morphs). Outputs
`app/src/main/res/drawable-nodpi/tier_bar_lizard_m{tier}_p{NN}.png`
(2048x512 RGBA, right-anchored, vertically centred — same format as the
milestone strips). The widget (`TierBarWidgetProvider.lizardBitmap`)
prefers the per-point asset and falls back to the milestone strip.

**RECURSIVE MIDPOINT BISECTION (user protocol, final design).** Earlier
chained "one small step from previous" generation failed: the edit model
is BIMODAL — it copies the previous image or snaps to the distant target
(13 rolls: 4 baby-copies, then one giant leap). The working protocol asks
for the age EXACTLY BETWEEN two already-close references:

1. (0,14) -> p07, the exact middle of the two ORIGINAL milestones.
2. (0,7) -> p03 and (7,14) -> p10, then quarters, eighths, ... until all
   13 slots exist (`bracket_tree()` spans-biggest-first; every blend's two
   references already exist on disk). Because each new image blends two
   nearly-identical neighbours, adjacent-picture jumps are structurally
   impossible (measured silhouette IoU 0.70–0.92 between neighbours).
3. Prompt: "age exactly halfway between IMAGE 1 (younger) and IMAGE 2
   (older)"; ALL glow stays the YOUNGER one's colour (red). Validation
   rejects duplicates, edge clipping, sparse renders, glow violations and
   out-of-bracket proportions; accepted blends get a mild horizontal
   stretch onto the exact linear aspect schedule (`geometric_correct`),
   then strips are scaled to the linear height schedule (`to_strip`).
4. `--verify` re-checks every strip on disk (height ±6px, aspect ±0.03,
   glow discipline).

Chroma background: pure blue `#0000FF` (measured: zero glow pixels in the
200–260° hue band on the red/orange strips). Milestones (p0 = shipped t0
strip, p14 = shipped t1 strip) are NEVER regenerated.

Commands:
```bash
python3 wallpaper_gen/gen_lizard_morph.py               # full tree + strips + verify
python3 wallpaper_gen/gen_lizard_morph.py --build-only  # strips from cached blends
python3 wallpaper_gen/gen_lizard_morph.py --only 12     # redo one blend
python3 wallpaper_gen/gen_lizard_morph.py --verify      # ladder check only
```
First set shipped: red→orange, points 01–13 (`tier_bar_lizard_m0_p01..13`).
Reroll history: p12 redone once (tail-coil pose noise vs p11).

### Full rollout (same day, later session)

Extended to ALL 12 spans (86 blend strips total: 13+6+9+10+6×8):
`tier_bar_lizard_m{tier}_p{point}.png` for every non-milestone point value.
Glow discipline per span: keep the FROM tier's palette (e.g. orange stays
orange through span 1), ban the TO tier's new hue until the milestone.
Best-of-N selection by neighbour silhouette IoU (accept >= 0.75, else keep
the highest-IoU pass) smooths endings; span 0 p13 rerolled with it
(12→13 IoU 0.705, 13→14 0.790). Adult spans (>= 3) use a 2048×720
reference canvas — at 1536×864 the longer lizards clipped the frame edges.
Measured span ladder: aspects 2.23→2.80 (t0→t1) rising smoothly to
3.57 (t11→t12); heights follow the milestone schedule within ±6px.
Verify: `python3 wallpaper_gen/gen_lizard_morph.py --verify` (86/86 PASS).

## Lizard tier strips (widget)

`tier_bar_lizard_t{0..12}.png` in `app/src/main/res/drawable-nodpi/` are the
widget's metallic mecha-lizard strip variants. Built by `gen_lizard_tiers.py`
/ `recolor_lizard_combos.py` / `gen_lizard_ages.py`; pose art comes from
`gen_lizard_poses.py` (separate assets, untouched by the strip tools).

### 2026-09-06 ascension upgrade (`upgrade_ascended_lizards.py`)

User verdict: t7 was a flat hue-recolor of t6 (boring), and the t8..t12
ladder didn't monotonically escalate (t10/t11/t12 also had green/cyan
ornament contamination). `upgrade_ascended_lizards.py` regenerated
t7..t12 from the approved t6 elder as identity reference with a cumulative
ascension grammar:

| tier | persona | features |
|---|---|---|
| 7 | crimson warrior | blade crests, seam veins, eye ring, pauldrons |
| 8 | forge-master | + furnace chest core, sparks, exhaust vents |
| 9 | grove-mage | + frond canopy, tail vines, spore motes |
| 10 | storm-savant | + halo tail rings, lightning arcs, orbiting shards |
| 11 | heart-mender | + lotus head corona, aurora ribbons, gem heart |
| 12 | sun-king | + solar crown rays, stacked halos, gold filigree cape |

Each tier keeps all previous features. Pipeline: wide chroma canvas →
`google/gemini-3-pro-image` edit (Nano Banana Pro) → hue-based chroma key →
despill (re-hues cyan/green contamination to the tier accent; skipped for
t9/t10 whose accents legitimately live in those bands) → validation
(vertical-crop check, accent dominance, green ban) → 2048x512 black strip,
right-anchored, stretch ladder 1.28→1.34 → user-matte alpha (black flood
fill). Originals backed up in `raw/ascend_backup/`; cached raws in
`raw/ascend/` allow `--reprocess` with zero API calls. Poses/manifest/Kotlin
untouched.

### 2026-09-06 t7 POSE re-roll (matching the new crimson warrior)

After the strip upgrade above, tier 7's 12 pose canvases still showed the OLD
hue-recolor lizard. Re-ran `gen_lizard_poses.py --tier 7 --only N` once per
pose (N = 0..11, one at a time with visual QA after each); the pipeline used
the NEW crimson-warrior strip as the identity reference automatically.
Rerolls needed: p02 curled (3× — erase-notch/floating defects), p08 armory
(3× — undersized lizard), p09 shield_wall (2× — undersized). All finals
verified: exact 512px/cell grid geometry, 75–94% transparent, no blue-key
spill, manifest `tiers.7` regenerated (12 poses, correct dummy cells/bulk
bands). OLD t7 poses + raws + manifest snapshot backed up in
`raw/poses_backup_t7_20260906/` for rollback.
