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
