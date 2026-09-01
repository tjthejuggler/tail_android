#!/usr/bin/env python3
"""Generate the 3-layer widget scene layers:

- SKY (deepest background, keyed to MONTHLY points color): wide panoramic
  sky art in each of the 7 unique tier colors.
- SURROUNDINGS (middle layer, keyed to WEEKLY points color): silhouette
  landscape rendered on a PURE MAGENTA (#FF00FF) negative space so the
  background can be removed by trivial chroma-keying (magenta never
  appears in the art itself).

7 unique colors -> 13 tier variants (tiers 7-12 reuse tier 0-5 hues,
tier 6 = glass). Output: drawable-nodpi/tier_bar_sky_t{N}.png and
tier_bar_env_t{N}.png, 2048x512 (4:1), env with real alpha."""
import base64
import io
import json
import time
import urllib.request
from collections import deque
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
RAW = BASE / "raw"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")
GEN_URL = "https://api.ppq.ai/v1/images/generations"
GEN_MODEL = "grok-imagine-image-2"

# unique palette: idx -> (name, colour description)
PALETTE = {
    0: ("red",    "deep crimson red"),
    1: ("orange", "warm orange"),
    2: ("green",  "emerald green"),
    3: ("blue",   "rich azure blue"),
    4: ("pink",   "vivid magenta pink"),
    5: ("yellow", "golden yellow"),
    6: ("glass",  "pale silver-white with a cool lavender tint"),
}
# tier -> palette idx (mirrors ic_launcher_tier_colors.xml)
TIER_MAP = [0, 1, 2, 3, 4, 5, 6, 0, 1, 2, 3, 4, 5]

W, H = 2048, 512


def post(url, payload, headers):
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.loads(r.read())


def generate(prompt):
    api_key = API_KEY_FILE.read_text().strip()
    resp = post(GEN_URL, {
        "model": GEN_MODEL,
        "prompt": prompt,
        "n": 1,
        "size": "1792x1024",
        "aspect_ratio": "16:9",
        "quality": "low",
    }, {"Content-Type": "application/json",
        "Authorization": f"Bearer {api_key}"})
    item = resp["data"][0]
    url = (item.get("url") or
           item.get("b64_json") and "data:image/png;base64," + item["b64_json"])
    if url.startswith("data:"):
        img_bytes = base64.b64decode(url.split(",", 1)[1])
    else:
        with urllib.request.urlopen(
                urllib.request.Request(url), timeout=120) as r:
            img_bytes = r.read()
    im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    im.load()
    return im


def center_crop_4to1(im):
    w, h = im.size
    target_h = w // 4
    if target_h <= h:
        y0 = (h - target_h) // 2
        im = im.crop((0, y0, w, y0 + target_h))
    else:
        target_w = h * 4
        x0 = (w - target_w) // 2
        im = im.crop((x0, 0, x0 + target_w, h))
    return im.resize((W, H), Image.LANCZOS)


def is_magenta(r, g, b):
    return r > 170 and b > 170 and g < 90 and abs(r - b) < 70


def chroma_key_magenta(im):
    """Flood fill from edges through magenta pixels -> alpha 0; any other
    magenta inside also keyed (simple global pass) since the art itself
    contains no magenta-family hues except the pink tier — for the pink
    tier the silhouette itself is pinkish, so edge flood-fill only."""
    w, h = im.size
    px = im.load()
    void = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_magenta(*px[x, y][:3]):
                void[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_magenta(*px[x, y][:3]):
                void[y * w + x] = 1
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not void[i] and is_magenta(*px[nx, ny][:3]):
                    void[i] = 1
                    q.append((nx, ny))
    out = Image.new("RGBA", (w, h))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y][:3]
            opx[x, y] = (r, g, b, 0 if void[y * w + x] else 255)
    return out


def main():
    RAW.mkdir(parents=True, exist_ok=True)
    skies, envs = {}, {}
    for idx, (name, col) in PALETTE.items():
        # ---- sky ----
        prompt_sky = (
            f"Wide panoramic stylized digital-art sky in {col} tones: soft "
            f"gradient clouds, faint stars, gentle glow, horizontal band "
            f"composition filling the whole frame edge to edge. No ground, "
            f"no horizon line objects, no characters, no text. Painterly, "
            f"calm, atmospheric.")
        for attempt in range(3):
            try:
                im = generate(prompt_sky)
                im.save(RAW / f"sky_{name}.png")
                skies[idx] = center_crop_4to1(im)
                print(f"[sky {name}] OK", flush=True)
                break
            except Exception as e:
                print(f"[sky {name}] attempt {attempt+1} failed: {e}", flush=True)
                time.sleep(3)
        # ---- surroundings ----
        prompt_env = (
            f"Flat stylized silhouette of a landscape foreground along the "
            f"bottom of the frame: layered rocks, low plants, branch shapes "
            f"and grassy ridges rendered in {col} tones, covering only the "
            f"lower third of the image. The ENTIRE upper area / negative "
            f"space must be one solid flat uniform PURE MAGENTA fill, "
            f"exactly hex #FF00FF, with no gradient, no texture, no clouds "
            f"in it. High contrast between silhouette and magenta "
            f"background. No text, no characters.")
        for attempt in range(3):
            try:
                im = generate(prompt_env)
                im.save(RAW / f"env_{name}.png")
                envs[idx] = chroma_key_magenta(center_crop_4to1(im))
                print(f"[env {name}] OK", flush=True)
                break
            except Exception as e:
                print(f"[env {name}] attempt {attempt+1} failed: {e}", flush=True)
                time.sleep(3)

    for tier in range(13):
        idx = TIER_MAP[tier]
        skies[idx].save(OUT_DIR / f"tier_bar_sky_t{tier}.png", "PNG")
        envs[idx].save(OUT_DIR / f"tier_bar_env_t{tier}.png", "PNG")
        print(f"[t{tier}] sky+env written (palette {idx})", flush=True)


if __name__ == "__main__":
    main()
