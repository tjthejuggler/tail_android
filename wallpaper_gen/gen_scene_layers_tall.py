#!/usr/bin/env python3
"""Generate the TALL (4x2) combined-widget scene layers.

Companion to gen_scene_layers.py (which serves the 4x1 tier bar). This
variant renders for the new CombinedSceneWidgetProvider:

- SKY  (monthly tier): 2:1 panoramic sky art per palette colour, with a
  DETAIL LADDER — red is deliberately plain, each successive palette
  colour is more interesting/detailed, glass the most intricate.
- GROUND (weekly tier): 3:1 chroma-keyed terrain silhouette (same magenta
  negative-space trick), detail also escalating up the ladder.

7 unique colours -> 13 tier variants (tiers 7-12 reuse tiers 0-5 hues,
tier 6 = glass), mirroring ic_launcher_tier_colors.xml.

Output: app/src/main/res/drawable-nodpi/scene_tall_sky_t{N}.png (1600x800)
and scene_tall_env_t{N}.png (1536x512, real alpha). Existing tier_bar_*
assets are NOT touched — the old widget keeps its art.

The magenta cleanup from fix_env_magenta.py is folded in: non-pink tiers
get global relaxed-magenta removal + fringe despill; pink tiers (4/11)
only lose strict-magenta regions connected to transparency.

Usage:
  python3 wallpaper_gen/gen_scene_layers_tall.py            # full run
  python3 wallpaper_gen/gen_scene_layers_tall.py --tier 0   # one palette
  (raw generations are cached in raw_tall/; delete a raw file to regen)
"""
import argparse
import base64
import io
import json
import time
import urllib.request
from collections import deque
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
RAW = BASE / "raw_tall"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")
GEN_URL = "https://api.ppq.ai/v1/images/generations"
GEN_MODEL = "grok-imagine-image-2"

# unique palette: idx -> (name, colour description)  — mirrors gen_scene_layers.py
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

SKY_W, SKY_H = 1600, 800     # 2:1
ENV_W, ENV_H = 1536, 512     # 3:1 (bottom-anchored ground band)

# ── detail ladder: idx -> (sky descriptor, ground descriptor) ────────────
# red = most boring … glass = most intricate (user spec).
SKY_DETAIL = {
    0: ("extremely minimal and plain: a single flat smooth gradient with "
        "almost no features, boring and boring"),
    1: ("simple and sparse: one or two soft flat clouds, mostly empty"),
    2: ("moderately detailed: layered soft clouds and a few faint stars"),
    3: ("fairly detailed: layered clouds, scattered faint stars, subtle "
        "glow bands, a few tiny distant birds"),
    4: ("very detailed: glowing nebula patches, layered luminous clouds, "
        "one shooting star, drifting sparkle motes"),
    5: ("highly detailed: dramatic layered clouds, crepuscular sunbeams, "
        "floating light orbs, dense star field near the top"),
    6: ("maximally intricate: crystalline cloud formations, iridescent "
        "shimmer bands, aurora ribbons, dense star clusters and drifting "
        "glowing particles throughout"),
}
GROUND_DETAIL = {
    0: ("one single flat smooth ridge with no detail"),
    1: ("two simple flat ridges with sparse grass tufts"),
    2: ("layered ridges, rocks and small plants"),
    3: ("layered rocky ridges, bushes, tall grasses and a twisted branch"),
    4: ("layered rocky ridges, flowering plants, curling vines and a "
        "small mushroom cluster"),
    5: ("richly layered terrain: rocky outcrops, tall grasses, ferns, "
        "mushrooms and glowing seed pods"),
    6: ("richly layered fantastical terrain: crystalline rock formations, "
        "ferns, glowing mushrooms, luminescent flowers and fine grass "
        "blades, maximum silhouette detail"),
}


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


def crop_aspect(im, tw, th, anchor_y=0.5):
    """Center-crop to tw:th aspect; anchor_y 0=top … 1=bottom for the
    vertical slack (ground layers anchor low so terrain stays in frame)."""
    w, h = im.size
    target_h = (w * th) // tw
    if target_h <= h:
        y0 = int((h - target_h) * anchor_y)
        im = im.crop((0, y0, w, y0 + target_h))
    else:
        target_w = (h * tw) // th
        x0 = (w - target_w) // 2
        im = im.crop((x0, 0, x0 + target_w, h))
    return im.resize((tw, th), Image.LANCZOS)


# ── magenta chroma-key + cleanup (gen_scene_layers + fix_env_magenta) ────
def strict_magenta(r, g, b):
    return r > 170 and b > 170 and g < 90 and abs(r - b) < 70


def relaxed_magenta(r, g, b):
    mn = min(r, b)
    return mn > 110 and g < 0.72 * mn and abs(r - b) < 110


def borderline_magenta(r, g, b):
    mn = min(r, b)
    return mn > 90 and g < 0.80 * mn and abs(r - b) < 120


def edge_flood_key(im):
    """Original gen_scene_layers keying: flood from frame edges through
    strict-magenta pixels. Kept for the pink tiers' first pass."""
    w, h = im.size
    px = im.load()
    void = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if strict_magenta(*px[x, y][:3]):
                void[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if strict_magenta(*px[x, y][:3]):
                void[y * w + x] = 1
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not void[i] and strict_magenta(*px[nx, ny][:3]):
                    void[i] = 1
                    q.append((nx, ny))
    out = Image.new("RGBA", (w, h))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y][:3]
            opx[x, y] = (r, g, b, 0 if void[y * w + x] else 255)
    return out


def flood_from_transparent(im, grow_test):
    """Flood from alpha<128 pixels through grow_test pixels -> stain mask."""
    w, h = im.size
    px = im.load()
    seen = bytearray(w * h)
    dq = deque()
    for y in range(h):
        for x in range(w):
            i = y * w + x
            if px[x, y][3] < 128:
                seen[i] = 1
                dq.append(i)
    while dq:
        i = dq.popleft()
        x, y = i % w, i // w
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h:
                j = ny * w + nx
                if not seen[j] and grow_test(*px[nx, ny][:3]):
                    seen[j] = 1
                    dq.append(j)
    return seen


def clean_env(im, is_pink):
    """fix_env_magenta.py logic inlined: pink tiers keep their own magenta
    art (only connectivity-limited strict removal); all other tiers get
    global relaxed removal plus fringe despill recolouring."""
    if is_pink:
        stain = flood_from_transparent(im, strict_magenta)
        px = im.load()
        w, h = im.size
        for y in range(h):
            for x in range(w):
                if stain[y * w + x]:
                    r, g, b, a = px[x, y]
                    px[x, y] = (r, g, b, 0)
        return im
    px = im.load()
    w, h = im.size
    rem = set()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            if a and relaxed_magenta(r, g, b):
                px[x, y] = (r, g, b, 0)
                rem.add(y * w + x)
    for y in range(h):
        for x in range(w):
            i = y * w + x
            r, g, b, a = px[x, y]
            if a == 0 or i in rem or not borderline_magenta(r, g, b):
                continue
            touch = False
            for dy in (-2, -1, 0, 1, 2):
                for dx in (-2, -1, 0, 1, 2):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < w and 0 <= ny < h:
                        if (ny * w + nx) in rem or px[nx, ny][3] < 128:
                            touch = True
                            break
                else:
                    continue
                break
            if touch:
                # recolour from nearest clean opaque neighbour
                repl = None
                for dist in range(3, 25):
                    for dy in range(-dist, dist + 1):
                        for dx in range(-dist, dist + 1):
                            nx, ny = x + dx, y + dy
                            if 0 <= nx < w and 0 <= ny < h:
                                nr, ng, nb, na = px[nx, ny]
                                if na > 200 and not borderline_magenta(nr, ng, nb):
                                    repl = (nr, ng, nb, 255)
                                    break
                        else:
                            continue
                        break
                    if repl:
                        break
                if repl:
                    px[x, y] = repl
    return im


def process_env(raw_im, is_pink):
    im = edge_flood_key(crop_aspect(raw_im, ENV_W, ENV_H, anchor_y=0.75))
    return clean_env(im, is_pink)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", type=int, default=None,
                    help="generate only this palette idx (0-6)")
    ap.add_argument("--sky-only", action="store_true")
    ap.add_argument("--env-only", action="store_true")
    args = ap.parse_args()

    RAW.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    indices = [args.tier] if args.tier is not None else sorted(PALETTE)

    skies, envs = {}, {}
    for idx in indices:
        name, col = PALETTE[idx]
        is_pink = idx == 4

        # ---- sky ----
        if not args.env_only:
            raw_path = RAW / f"sky_{name}.png"
            if raw_path.exists():
                im = Image.open(raw_path).convert("RGB")
            else:
                prompt_sky = (
                    f"Wide panoramic stylized digital-art sky in {col} tones, "
                    f"{SKY_DETAIL[idx]}: soft gradient base, horizontal band "
                    f"composition filling the whole frame edge to edge. No "
                    f"ground, no horizon line, no characters, no text. "
                    f"Painterly, atmospheric.")
                for attempt in range(3):
                    try:
                        im = generate(prompt_sky)
                        im.save(raw_path)
                        break
                    except Exception as e:
                        print(f"[sky {name}] attempt {attempt+1} failed: {e}",
                              flush=True)
                        time.sleep(3)
                else:
                    im = None
            if im is not None:
                skies[idx] = crop_aspect(im, SKY_W, SKY_H)
                print(f"[sky {name}] OK", flush=True)

        # ---- ground ----
        if not args.sky_only:
            raw_path = RAW / f"env_{name}.png"
            if raw_path.exists():
                raw_im = Image.open(raw_path).convert("RGB")
            else:
                prompt_env = (
                    f"Flat stylized silhouette of a landscape foreground along "
                    f"the bottom of the frame: {GROUND_DETAIL[idx]} all "
                    f"rendered in {col} tones, covering only the lower third "
                    f"of the image. The ENTIRE upper area / negative space "
                    f"must be one solid flat uniform PURE MAGENTA fill, "
                    f"exactly hex #FF00FF, with no gradient, no texture, no "
                    f"clouds in it. High contrast between silhouette and "
                    f"magenta background. No text, no characters.")
                for attempt in range(3):
                    try:
                        raw_im = generate(prompt_env)
                        raw_im.save(raw_path)
                        break
                    except Exception as e:
                        print(f"[env {name}] attempt {attempt+1} failed: {e}",
                              flush=True)
                        time.sleep(3)
                else:
                    raw_im = None
            if raw_im is not None:
                envs[idx] = process_env(raw_im, is_pink)
                print(f"[env {name}] OK", flush=True)

    # ---- write 13 tier copies per layer ----
    # Skies are opaque -> lossy WebP (4-92 KB each, identical at widget
    # scale). Grounds carry alpha -> palette-quantized PNG (mode P).
    SKY_SIZE = (1024, 512)
    def save_sky(im, path):
        im.resize(SKY_SIZE, Image.LANCZOS)\
          .save(path, "WEBP", quality=85, method=6)
    def save_env(im, path):
        im.quantize(colors=256, dither=Image.Dither.FLOYDSTEINBERG)\
          .save(path, "PNG")
    for tier in range(13):
        idx = TIER_MAP[tier]
        if idx in skies:
            save_sky(skies[idx], OUT_DIR / f"scene_tall_sky_t{tier}.webp")
        if idx in envs:
            save_env(envs[idx], OUT_DIR / f"scene_tall_env_t{tier}.png")
    # clear supersized leftovers from a previous run
    for old in OUT_DIR.glob("scene_tall_sky_t*.png"):
        old.unlink()
    print("[done] tier copies written", flush=True)


if __name__ == "__main__":
    main()
