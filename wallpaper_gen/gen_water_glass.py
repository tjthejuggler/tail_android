#!/usr/bin/env python3
"""Generate the drinking-glass artwork for the combined-scene widget's
water-habit progress glass.

Uses the same PPQ grok-imagine-image-2 API as the wallpaper batch
(generate_images.py) and the chameleon (gen_widget_chameleon.py).

Two assets are generated:

  1. scene_water_glass.png  — a tall elegant drinking glass (empty, no
     water inside), standing on a solid magenta (#FF00FF) background so
     the magenta can be keyed out to transparency at build time (same
     pipeline as the scene_tall_env_t* layers).
  2. scene_water_fill.png   — a seamless blue water surface/body texture
     used to fill the glass's interior at runtime; also magenta-keyed.

Raw generations are cached in wallpaper_gen/raw_water/ — delete a file
there to regenerate it.
"""
import argparse
import base64
import io
import json
import sys
import time
import urllib.error
import urllib.request
from collections import deque
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
RAW = BASE / "raw_water"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"
MAX_RETRIES = 5
SLEEP_BETWEEN = 1.5

GLASS_PROMPT = (
    "A single tall elegant drinking glass, empty, no water inside, no liquid, "
    "front view, perfectly centered vertical composition, photorealistic "
    "product render. Straight-sided slightly tapered tumbler made of thick "
    "clear crystal glass with visible refraction, specular highlights and "
    "rim light along both edges, a subtle thick glass base, soft caustics "
    "on the ground beneath it. Style matches a cozy storybook sci-fi game "
    "scene: clean lines, gentle glow, high detail. The glass occupies about "
    "85% of the image height. Background is a perfectly flat uniform pure "
    "magenta (#FF00FF) fill, no shadows, no gradient, no texture, nothing "
    "else in frame. No text."
)

WATER_PROMPT = (
    "Seamless tileable texture of pure drinking water seen from directly "
    "above: gentle ripples and soft caustic light patterns, luminous clear "
    "blue-cyan tones with white highlights, very smooth and clean, "
    "storybook game-art style, soft glow. Perfectly tileable, no seams, no "
    "bubbles clusters, no objects, no text. Background entirely water."
)


def post_json(payload, headers):
    req = urllib.request.Request(
        URL, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **headers}, method="POST")
    with urllib.request.urlopen(req, timeout=300) as resp:
        return json.loads(resp.read().decode())


def get_bytes(url):
    req = urllib.request.Request(url)
    with urllib.request.urlopen(req, timeout=120) as resp:
        return resp.read()


def fetch_image_bytes(item, headers):
    if item.get("b64_json"):
        return base64.b64decode(item["b64_json"])
    url = item.get("url")
    if not url:
        raise ValueError(f"no b64_json or url in response item: {str(item)[:200]}")
    return get_bytes(url)


def generate_one(name: str, prompt: str, aspect: str, api_key: str) -> Image.Image:
    """Generate (or reuse the cached raw of) one image."""
    cache = RAW / f"{name}.png"
    if cache.exists():
        try:
            with Image.open(cache) as im:
                im.load()
            print(f"cached: {cache}")
            return im.copy()
        except Exception:
            cache.unlink()

    headers = {"Authorization": f"Bearer {api_key}"}
    payload = {
        "model": MODEL,
        "prompt": prompt,
        "quality": "low",
        "resolution": "1k",
        "aspect_ratio": aspect,
        "n": 1,
    }
    last_err = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            result = post_json(payload, headers)
            data = result.get("data") or []
            if not data:
                raise ValueError(f"empty data in response: {str(result)[:200]}")
            img_bytes = fetch_image_bytes(data[0], headers)
            im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
            RAW.mkdir(parents=True, exist_ok=True)
            im.save(cache, "PNG")
            print(f"generated: {cache} {im.size}")
            return im
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
                ValueError, OSError) as e:
            last_err = e
            wait = min(2 ** attempt, 60)
            print(f"attempt {attempt}/{MAX_RETRIES} failed: {e}; retrying in {wait}s",
                  flush=True)
            time.sleep(wait)
    raise SystemExit(f"FAILED after {MAX_RETRIES} attempts ({name}): {last_err}")


# ── magenta keying (same relaxed/strict scheme as fix_env_magenta.py) ──────

def strict_magenta(r, g, b):
    return r > 170 and b > 170 and g < 90 and abs(r - b) < 70


def relaxed_magenta(r, g, b):
    mn = min(r, b)
    return mn > 110 and g < 0.72 * mn and abs(r - b) < 110


def key_out_magenta(im: Image.Image, pink_content: bool) -> Image.Image:
    """Turn the flat magenta backdrop into transparency.

    Global relaxed removal for art that legitimately contains no magenta
    (the glass), connectivity-limited strict removal otherwise.
    """
    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            hit = (relaxed_magenta(r, g, b) if not pink_content
                   else strict_magenta(r, g, b))
            if hit:
                px[x, y] = (r, g, b, 0)
    return im


def despill_edges(im: Image.Image) -> Image.Image:
    """Recolour magenta-fringe pixels that sit next to transparency from
    their clean neighbours, keeping silhouette edges smooth."""
    w, h = im.size
    px = im.load()
    changed = True
    passes = 0
    while changed and passes < 4:
        changed = False
        passes += 1
        for y in range(h):
            for x in range(w):
                r, g, b, a = px[x, y]
                if a == 0:
                    continue
                mn = min(r, b)
                if mn > 90 and g < 0.80 * mn and abs(r - b) < 120:
                    # candidate fringe — average the nearby clean pixels
                    clean = []
                    for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                        if 0 <= nx < w and 0 <= ny < h:
                            nr, ng, nb, na = px[nx, ny]
                            nmn = min(nr, nb)
                            if na > 0 and not (nmn > 90 and ng < 0.80 * nmn
                                               and abs(nr - nb) < 120):
                                clean.append((nr, ng, nb))
                    if clean:
                        cr = sum(c[0] for c in clean) // len(clean)
                        cg = sum(c[1] for c in clean) // len(clean)
                        cb = sum(c[2] for c in clean) // len(clean)
                        px[x, y] = (cr, cg, cb, a)
                        changed = True
    return im


def trim_to_content(im: Image.Image, pad: int = 8) -> Image.Image:
    """Crop away fully transparent margins, keep a small pad."""
    bbox = im.getbbox()
    if bbox is None:
        return im
    l, t, r, b = bbox
    l = max(0, l - pad); t = max(0, t - pad)
    r = min(im.width, r + pad); b = min(im.height, b + pad)
    return im.crop((l, t, r, b))


def neutralize_tint(im: Image.Image) -> Image.Image:
    """Convert surviving magenta TINT into neutral translucent glass.

    A clear glass shows the magenta backdrop through its interior, so
    after keying, interior pixels keep a strong pink cast. For every
    remaining pixel whose green channel lags min(red, blue) (the magenta
    signature), the colour is replaced by its neutral luminance and the
    alpha is reduced in proportion to the tint strength:
      tint > 150 (pure backdrop seen through the glass) → almost fully
      transparent; mild tint (glass walls / caustics) → lightly veiled.
    Untinted pixels (white highlights, rim light) keep full opacity.
    """
    im = im.convert("RGBA")
    w, h = im.size
    px = im.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a == 0:
                continue
            tint = min(r, b) - g
            if tint > 8:
                lum = int(0.299 * r + 0.587 * g + 0.114 * b)
                # Cool the highlight slightly toward ice-blue instead of
                # leaving grey: a water glass reads better with a hint of
                # cyan than with pure neutral grey.
                cool = max(0, min(255, lum - 6))
                if tint > 150:
                    new_a = 26
                else:
                    new_a = 200 - tint          # 8 → 192 … 150 → 50
                new_a = max(26, min(a, new_a))
                px[x, y] = (lum, lum, cool, new_a)
    return im


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--skip-glass", action="store_true")
    ap.add_argument("--skip-water", action="store_true")
    ap.add_argument("--only-raw", action="store_true",
                    help="stop after caching raw generations (no keying)")
    args = ap.parse_args()

    api_key = API_KEY_FILE.read_text().strip()
    if not api_key:
        print("ERROR: empty API key", file=sys.stderr)
        sys.exit(1)

    if not args.skip_glass:
        glass = generate_one("water_glass", GLASS_PROMPT, "9:16", api_key)
        if not args.only_raw:
            glass = key_out_magenta(glass, pink_content=False)
            glass = neutralize_tint(glass)
            glass = despill_edges(glass)
            glass = trim_to_content(glass)
            OUT_DIR.mkdir(parents=True, exist_ok=True)
            out = OUT_DIR / "scene_water_glass.png"
            glass.save(out, "PNG")
            print(f"saved {out} {glass.size} ({out.stat().st_size} bytes)")

    if not args.skip_water:
        water = generate_one("water_fill", WATER_PROMPT, "1:1", api_key)
        if not args.only_raw:
            water = water.resize((512, 512), Image.LANCZOS)
            OUT_DIR.mkdir(parents=True, exist_ok=True)
            out = OUT_DIR / "scene_water_fill.png"
            water.save(out, "PNG")
            print(f"saved {out} {water.size} ({out.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
