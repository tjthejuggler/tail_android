#!/usr/bin/env python3
"""Fix the tier lizard strips:

1. Regenerate tiers 1 (orange), 4 (pink), 5 (yellow) — the edit model
   returned those with a smaller animal in frame. Validation: the animal
   bbox must cover a similar share of the image as the green source
   (bbox area / image area >= 60% of the source's coverage) and be
   landscape-ish (aspect >= 1.1).

2. Convert every strip's pure-black background to REAL alpha (flood fill
   from the image edges through near-black pixels, then dilate the kept
   region 3px outward and feather the boundary) so the Android widget can
   draw the lizard fully opaque with zero runtime processing.

Overwrites app/src/main/res/drawable-nodpi/tier_bar_lizard_t{N}.png in place
(RGBA). Raw keepsakes go to wallpaper_gen/raw/.
"""
import base64
import colorsys
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
RAW = BASE / "raw"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
SRC_GG = RAW / "lizard_src_green_green.png"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")
URL = "https://api.ppq.ai/v1/chat/completions"
MODEL = "google/gemini-2.5-flash-image"

REGEN = {1: "red-orange glowing orange", 4: "glowing pink/magenta", 5: "glowing yellow"}
# ^ tier -> target colour description (tier 1 prompt below is specialized)


def bbox(im):
    w, h = im.size
    px = im.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if r > 24 or g > 24 or b > 24:
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
    if maxx < 0:
        return None
    return (minx, miny, maxx + 1, maxy + 1)


def coverage(im):
    bb = bbox(im)
    if bb is None:
        return 0.0, 0.0
    x0, y0, x1, y1 = bb
    w, h = im.size
    area = (x1 - x0) * (y1 - y0)
    return area / (w * h), (x1 - x0) / max(1, (y1 - y0))


def edit_image(api_key, src, prompt):
    img_b64 = base64.b64encode(src.read_bytes()).decode()
    payload = {
        "model": MODEL,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image_url",
                 "image_url": {"url": f"data:image/png;base64,{img_b64}"}},
                {"type": "text", "text": prompt},
            ],
        }],
    }
    req = urllib.request.Request(
        URL, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}"}, method="POST")
    with urllib.request.urlopen(req, timeout=300) as r:
        resp = json.loads(r.read())
    images = resp["choices"][0]["message"].get("images") or []
    if not images:
        raise ValueError("no images in response")
    item = images[0]
    url = item.get("image_url", {}).get("url") if isinstance(item, dict) else None
    if url and url.startswith("data:"):
        img_bytes = base64.b64decode(url.split(",", 1)[1])
    elif url:
        with urllib.request.urlopen(urllib.request.Request(url), timeout=120) as r:
            img_bytes = r.read()
    else:
        raise ValueError("unrecognized image item")
    im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
    im.load()
    return im


def to_strip(im):
    bb = bbox(im)
    animal = im.crop(bb) if bb else im
    H = 512
    W = H * 4
    scale = min(H / animal.height, W / animal.width)
    aw = round(animal.width * scale)
    ah = round(animal.height * scale)
    animal = animal.resize((aw, ah), Image.LANCZOS)
    canvas = Image.new("RGB", (W, H), (0, 0, 0))
    canvas.paste(animal, (W - aw, (H - ah) // 2))
    return canvas


def apply_alpha(im):
    """Flood-fill near-black background from the edges → alpha 0.
    Everything else fully opaque. 3px dilation of the kept region +
    2px feather at the boundary for smooth edges."""
    im = im.convert("RGB")
    w, h = im.size
    px = im.load()
    void = bytearray(w * h)

    def is_bg(x, y):
        r, g, b = px[x, y]
        return max(r, g, b) < 40

    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_bg(x, y) and not void[y * w + x]:
                void[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_bg(x, y) and not void[y * w + x]:
                void[y * w + x] = 1
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not void[i] and is_bg(nx, ny):
                    void[i] = 1
                    q.append((nx, ny))

    # Dilate the KEPT (non-void) region 3px: erode the void mask.
    for _ in range(3):
        to_clear = []
        for y in range(h):
            for x in range(w):
                i = y * w + x
                if not void[i]:
                    continue
                # any kept neighbour? -> becomes kept
                for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
                    if 0 <= nx < w and 0 <= ny < h and not void[ny * w + nx]:
                        to_clear.append(i)
                        break
        if not to_clear:
            break
        for i in to_clear:
            void[i] = 0

    out = Image.new("RGBA", (w, h))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if void[y * w + x]:
                opx[x, y] = (r, g, b, 0)
            else:
                # Feather: near-boundary kept pixels get partial alpha based
                # on brightness so glow edges fade smoothly.
                m = max(r, g, b)
                a = 255 if m >= 64 else int(m * 255 / 64)
                opx[x, y] = (r, g, b, a)
    return out


def main():
    RAW.mkdir(parents=True, exist_ok=True)
    api_key = API_KEY_FILE.read_text().strip()

    src = Image.open(SRC_GG).convert("RGB")
    src_cov, src_aspect = coverage(src)
    print(f"source coverage={src_cov:.2f} aspect={src_aspect:.2f}")

    for tier, colour in REGEN.items():
        out = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
        prompt = (
            f"Recolor this robotic chameleon: change every glowing green "
            f"element (eye core, conduits, tail strips, joint lights, flank "
            f"bars) to {colour}. Keep the silver metallic body plates, the "
            f"exact same animal size and framing (the chameleon filling the "
            f"same portion of the frame), the exact pose, coiled spiral "
            f"tail, proportions, lighting, and the pure solid black "
            f"background exactly the same. Only change the glowing accent "
            f"colour. Output the edited image."
        )
        done = False
        for attempt in range(1, 4):
            try:
                im = edit_image(api_key, SRC_GG, prompt)
                cov, asp = coverage(im)
                print(f"[t{tier}] attempt {attempt}: coverage={cov:.2f} "
                      f"aspect={asp:.2f}", flush=True)
                if cov >= src_cov * 0.75 and asp >= 1.1:
                    im.save(RAW / f"lizard_tier{tier}.png")
                    to_strip(im).save(out, "PNG")
                    print(f"[t{tier}] regenerated OK", flush=True)
                    done = True
                    break
            except Exception as e:
                print(f"[t{tier}] attempt {attempt} failed: {e}", flush=True)
            time.sleep(3)
        if not done:
            print(f"[t{tier}] WARNING: kept existing image", flush=True)

    # Apply real alpha to all 13 strips.
    for tier in range(13):
        p = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
        im = Image.open(p).convert("RGB")
        apply_alpha(im).save(p, "PNG")
        print(f"[t{tier:02d}] alpha applied {p.stat().st_size} bytes", flush=True)


if __name__ == "__main__":
    main()
