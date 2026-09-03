#!/usr/bin/env python3
"""Regenerate ONLY tier 1 (orange) lizard strip.

wallpaper_gen/raw/lizard_tier1.png (the accepted output of the earlier
fix_lizard_strips.py run) is a BAD regeneration: a smaller animal whose
coiled tail only has ~1.5 turns and is vertically sheared — the "chopped
tail" seen in the widget. No pixel shift can repair missing geometry, so
this script re-runs the original gen_lizard_tiers.py edit for tier 1 only
(green -> glowing orange on lizard_src_green_green.png), with STRICT
validation:

  * bbox coverage >= 75 % of the source's (same animal size/framing)
  * landscape aspect >= 1.1
  * accent hue check: recoloured pixels must be orange, not green
  * completed-spiral check: no long vertical solid run at the right edge
    of the raw (a cut tube shows up as a tall 1-px-wide run)

The strip is composited with a 24 px baked-in right margin (slightly
smaller animal, complete tail clear of the edge), then the same edge
flood-fill alpha matte is applied. Only tier_bar_lizard_t1.png is touched.
A backup of the current file is kept as tier_bar_lizard_t1.png.bak.
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
OUT = BASE.parent / "app/src/main/res/drawable-nodpi/tier_bar_lizard_t1.png"
SRC = RAW / "lizard_src_green_green.png"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")
URL = "https://api.ppq.ai/v1/chat/completions"
MODEL = "google/gemini-2.5-flash-image"
MAX_RETRIES = 5
PAD_R = 24


def coverage(im):
    bb = im.getbbox()
    if bb is None:
        return 0.0, 0.0
    x0, y0, x1, y1 = bb
    return ((x1 - x0) * (y1 - y0)) / (im.width * im.height), (x1 - x0) / max(1, y1 - y0)


def max_right_edge_run(im, thr=90):
    """Longest vertical run of bright pixels in the last 3 columns — a cut
    tube shows as a tall run; a natural spiral edge is ragged/short."""
    px = im.load()
    w, h = im.size
    worst = 0
    for x in range(w - 3, w):
        best = cur = 0
        for y in range(h):
            r, g, b = px[x, y][:3]
            if max(r, g, b) > thr:
                cur += 1
                best = max(best, cur)
            else:
                cur = 0
        worst = max(worst, best)
    return worst


def accent_is_orange(im):
    """Sample saturated pixels; hue must sit in the orange band and there
    must be no significant green population."""
    im = im.convert("RGB").resize((256, 144))
    orange = green = total = 0
    for r, g, b in im.getdata():
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if s > 0.45 and v > 0.45:
            total += 1
            deg = h * 360
            if 15 <= deg <= 55:
                orange += 1
            elif 80 <= deg <= 180:
                green += 1
    if total == 0:
        return False, 0.0, 0
    return green / total < 0.05 and orange / total > 0.30, orange / total, total


def edit_image(api_key, prompt):
    img_b64 = base64.b64encode(SRC.read_bytes()).decode()
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
    last = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            req = urllib.request.Request(
                URL, data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json",
                         "Authorization": f"Bearer {api_key}"}, method="POST")
            with urllib.request.urlopen(req, timeout=300) as r:
                resp = json.loads(r.read())
            msg = resp["choices"][0]["message"]
            images = msg.get("images") or []
            data = None
            if images:
                item = images[0]
                url = item.get("image_url", {}).get("url") if isinstance(item, dict) else None
                if url and url.startswith("data:"):
                    data = base64.b64decode(url.split(",", 1)[1])
                elif url:
                    with urllib.request.urlopen(urllib.request.Request(url), timeout=120) as r:
                        data = r.read()
                elif isinstance(item, dict) and item.get("b64_json"):
                    data = base64.b64decode(item["b64_json"])
            elif isinstance(msg.get("audio"), dict) and msg["audio"].get("data"):
                # The proxy sometimes misroutes the image into message.audio.data.
                data = base64.b64decode(msg["audio"]["data"])
                if not data.startswith(b"\x89PNG"):
                    data = None
            if not data:
                raise ValueError(f"no image in response: keys={list(msg.keys())}")
            im = Image.open(io.BytesIO(data)).convert("RGB")
            im.load()
            return im
        except Exception as e:
            last = e
            wait = min(2 ** attempt, 30)
            print(f"  attempt {attempt}/{MAX_RETRIES} failed: {e}; retry in {wait}s", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"edit failed: {last}")


def to_strip(im):
    bb = im.getbbox()
    animal = im.crop(bb)
    H = 512
    W = H * 4
    scale = min(H / animal.height, (W - PAD_R) / animal.width)
    aw, ah = round(animal.width * scale), round(animal.height * scale)
    animal = animal.resize((aw, ah), Image.LANCZOS)
    canvas = Image.new("RGB", (W, H), (0, 0, 0))
    canvas.paste(animal, (W - PAD_R - aw, (H - ah) // 2))
    return canvas


def apply_alpha(im):
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
    for _ in range(3):
        to_clear = []
        for y in range(h):
            for x in range(w):
                i = y * w + x
                if void[i] and any(
                    0 <= nx < w and 0 <= ny < h and not void[ny * w + nx]
                    for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1))
                ):
                    to_clear.append(i)
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
                m = max(r, g, b)
                a = 255 if m >= 64 else int(m * 255 / 64)
                opx[x, y] = (r, g, b, a)
    return out


PROMPT = (
    "Recolor this robotic chameleon: change every glowing green element "
    "(eye core, conduits, tail strips, joint lights, flank bars) to glowing "
    "orange. Keep the silver metallic body plates, the EXACT same animal "
    "size and framing (the chameleon filling the same portion of the frame), "
    "the exact pose, the complete coiled spiral tail with all of its coils "
    "fully visible and nothing cropped, proportions, lighting, and the pure "
    "solid black background exactly the same. Only change the glowing "
    "accent colour. Output the edited image."
)


def main():
    api_key = API_KEY_FILE.read_text().strip()
    src = Image.open(SRC).convert("RGB")
    src_cov, _ = coverage(src)
    print(f"source coverage={src_cov:.2f}", flush=True)

    for attempt in range(1, 4):
        im = edit_image(api_key, PROMPT)
        cov, asp = coverage(im)
        edge = max_right_edge_run(im)
        ok_orange, orange_frac, n = accent_is_orange(im)
        print(f"[t1 attempt {attempt}] coverage={cov:.2f} (need>={src_cov*0.75:.2f}) "
              f"aspect={asp:.2f} edge_run={edge} orange_frac={orange_frac:.2f}", flush=True)
        if cov < src_cov * 0.75 or asp < 1.1:
            print("  rejected: wrong size/framing", flush=True)
            continue
        if edge > h * 0.25:
            print("  rejected: tail looks cut at right edge", flush=True)
            continue
        if not ok_orange:
            print("  rejected: accents not orange", flush=True)
            continue
        im.save(RAW / "lizard_tier1.png")  # replaces the bad raw
        strip = apply_alpha(to_strip(im))
        if OUT.exists():
            OUT.with_suffix(".png.bak").write_bytes(OUT.read_bytes())
        strip.save(OUT, "PNG")
        ab = strip.split()[3].getbbox()
        print(f"[t1] OK: saved {OUT.name} alpha_bbox={ab}", flush=True)
        return 0
    print("ALL ATTEMPTS REJECTED — existing t1 left untouched", flush=True)
    return 2


if __name__ == "__main__":
    sys.exit(main())
