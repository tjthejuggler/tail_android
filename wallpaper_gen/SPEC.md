# Wallpaper Generation Spec — "tail" Habit Tracker (100 AI wallpapers)

Pipeline task directory: `wallpaper_gen/` (this dir). This file is the single source of
truth for later generation steps. No API keys are stored here — see §4.

---

## 1. Example image analysis (`~/Pictures/Wallpapers/mon_backup/`)

Folder contents: exactly `result_1.png` … `result_56.png` (56 files, no other files).

### Dimensions
- Every image: **1440 × 3088 px, PNG, portrait** (phone wallpaper, ~1:2.14 aspect).

### Layout (visually + pixel-scan verified)
- **Canvas background**: solid **black (0,0,0)** around the artwork.
- **Animal label**: bold **white sans-serif** text, horizontally centered, at roughly
  **15–19% of canvas height** (≈460–590 px from top), sitting in the black area just
  above the artwork. Examples: result_1 = "Armadillo", result_21 = "Umbrella Bird",
  result_56 = "Gazelle".
- **Artwork**: full canvas width (1440 px), square-ish (1440 px tall) for early
  indices; the AI itself renders the whole 1440×3088 canvas including the black
  letterbox bars and the label text (i.e. the label is part of the generated image,
  not composited afterwards).

### Vertical placement vs. index (pixel-scan of all 56)
Measured top row of non-black content:

| N | letter | top offset (px) |
|---|--------|-----------------|
| 1 | A | 0 |
| 2 | B | 29 |
| 3 | C | 58 |
| 14 | N | 377 |
| 18 | R | 493 |
| 19+ | … | ~509–511 (saturated) |

Rule observed: **top offset = 29 × (N−1), clamped at ≈511 px** (≈16.5% of height).
From N≈19 onward the offset saturates and the AI instead lets the artwork grow
taller than square (result_56 artwork is bottom-anchored, filling to y=3034).
For the new 100-image run, the prompt should explicitly state the vertical offset
fraction so the model doesn't saturate: e.g. step ≈ 1% of height per letter index
and wrap back to top every 26 letters.

## 2. Letter mapping rule (confirmed)

- Image N (1–100): **letter = chr(ord('A') + ((N−1) % 26))**
- Verified: result_1 → "Armadillo" (A ✓), result_21 → "Umbrella Bird" (U ✓).
  result_56 → expected D but label reads "Gazelle" (G ✗ — old set drifted; new
  prompts must enforce the letter explicitly).
- Image index 0 (points = 0) has **no animal** (0-point wallpaper is animal-free).
- Vertical placement: square image's top offset **steps down gradually with each
  letter index ((N−1) % 26), wrapping back to the top** after Z.

## 3. Color progression (points → color)

From `app/src/main/java/com/example/tail/ui/HabitColors.kt` (tiers) and
`~/Projects/py_habits_widget/habitdb_streak_finder.py` (point-range bands,
`ranges = [0.5, 13.5, 20.5, 31, 41.5, 48.5, 55.5, 62.5]`,
`colors = [red, orange, green, blue, pink, yellow, white]`):

| Points (daily total) | Tier color | Exact hex (app tier color) | Vivid border variant |
|---|---|---|---|
| 0–13   | Red    | `#3D1515` | `#CC3333` |
| 14–20  | Orange | `#7A3800` | `#E07020` |
| 21–30  | Green  | `#1A4020` | `#33AA55` |
| 31–41  | Blue   | `#102255` | `#3366DD` |
| 42–48  | Pink   | `#901060` | `#DD44AA` |
| 49–55  | Yellow | `#B8B000` | `#DDCC00` |
| 56+    | White/Glass | `#D0D0E0` | `#FFFFFF` |

Wallpaper resolution in the app (`WallpaperRefresher.kt`): image index = rounded
points clamped to [1, maxIndex] — i.e. **result_N.png is shown when daily points ≈ N**
(metric selectable: today / 7-day avg / 30-day avg). With 100 images, points 0 →
clamped to 1 unless a result_0 exists; spec: image 0 = no-animal wallpaper for
0 points (generate `result_0.png` too, or accept clamp).

## 4. Generation API (from `~/Projects/small_scripts/ppq_grok_imagine_test.py`)

- **Endpoint**: `POST https://api.ppq.ai/v1/images/generations`
- **Model string**: `"grok-imagine-image-2"` (quality/resolution passed as separate
  request fields, e.g. quality `low`/`medium`, resolution `1k`/`2k`)
- **Request format** (JSON body):
  ```json
  {
    "model": "grok-imagine-image-2",
    "prompt": "...",
    "quality": "low",
    "resolution": "1k",
    "aspect_ratio": "16:9",
    "n": 1
  }
  ```
  Headers: `Content-Type: application/json`, `Authorization: Bearer <API_KEY>`.
- **API key**: read at runtime from
  `/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt` (single line, strip
  whitespace). **NEVER copy the key into this repo.**
- **Response**: JSON with `cost` field and `data: [ { "url": ... } ]` — images are
  returned as **download URLs** (not b64); fetch bytes with a plain GET (script used
  300 s timeout for generation, 120 s for download, 2 s sleep between calls,
  stdlib `urllib` only).
- Note: aspect_ratio in the test was `16:9`; for phone wallpapers we need portrait
  (try `9:16` / `9:19.5` or generate square `1:1` and composite onto a
  1440×3088 black canvas with the label drawn via PIL — compositing guarantees
  exact dimensions and layout).

## 5. Habits

Complete canonical list (79 habits, row-major order matching the Android app) is in
[`habits.txt`](habits.txt) — source: `HABIT_ORDER` in
`~/Projects/py_habits_widget/habit_models.py` (mirrors the app grid). Later steps
must use these exact names (e.g. "Fiction Video Intake", "Meditations", "Chess",
"Apnea practiced", "Pushups", "Pushups"/"Situps", "Tracked Sleep", "Flossed", …).

## 6. Output requirements for the generation step (summary)

- 101 images: `result_0.png` (no animal, 0 points) + `result_1.png` … `result_100.png`
- 1440 × 3088 px PNG, black canvas
- Letter rule: `chr(ord('A') + ((N-1) % 26))`; animal name label in bold white
  sans-serif centered at ~15% height
- Vertical offset of the square artwork steps down per letter index, wraps after Z
- Color mood of each image should follow the §3 point-band color of its index
  (red-ish for 1–13, orange 14–20, green 21–30, blue 31–41, pink 42–48,
  yellow 49–55, white/bright 56+)
