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
