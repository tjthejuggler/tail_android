#!/usr/bin/env python3
"""Generate the 13 tier variants of the metallic mecha-lizard widget art.

Uses PPQ's image-edit model (google/gemini-2.5-flash-image via
/v1/chat/completions with an image input) to recolor the two source images:

  full_lizard_primary_green_secondary_green.png   (green glow accents only)
  full_lizard_primary_green_secondary_orange.png  (green primary + orange
                                                   secondary accents)

Tier mapping (see ic_launcher_tier_colors.xml / habitPointsTier):
  0 red    1 orange  2 green  3 blue   4 pink   5 yellow  6 white
  7 w/red  8 w/orange 9 w/green 10 w/blue 11 w/pink 12 w/yellow

Tiers 0-5: from the green/green image, recolor green -> tier colour.
Tier 6:    from green/orange image, green -> white AND orange -> white.
Tiers 7-12: from green/orange image, green -> white, orange -> combo colour.

Each result is saved raw to wallpaper_gen/raw/lizard_tier{N}.png and then
composited onto a 4:1 black strip (animal extracted, fitted, right-anchored)
at app/src/main/res/drawable-nodpi/tier_bar_lizard_t{N}.png.

Resumable: existing valid outputs are skipped.
"""
import base64
import io
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
RAW_DIR = BASE / "raw"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
SRC_GG = Path("/home/twain/Downloads/full_lizard_primary_green_secondary_green.png")
SRC_GO = Path("/home/twain/Downloads/full_lizard_primary_green_secondary_orange.png")
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

URL = "https://api.ppq.ai/v1/chat/completions"
MODEL = "google/gemini-2.5-flash-image"
MAX_RETRIES = 4

COLORS = {
    "red": "glowing red",
    "orange": "glowing orange",
    "green": "glowing green",
    "blue": "glowing blue",
    "pink": "glowing pink/magenta",
    "yellow": "glowing yellow",
    "white": "glowing white",
}

# tier -> (source, primary, secondary)
VARIANTS = {
    0:  (SRC_GG, "red",    None),
    1:  (SRC_GG, "orange", None),
    2:  (None,   None,     None),   # green/green used as-is
    3:  (SRC_GG, "blue",   None),
    4:  (SRC_GG, "pink",   None),
    5:  (SRC_GG, "yellow", None),
    6:  (SRC_GO, "white",  "white"),
    7:  (SRC_GO, "white",  "red"),
    8:  (None,   None,     None),   # white/orange == source green/orange? No:
                                    # primary must be white. Edited below.
    9:  (SRC_GO, "white",  "green"),
    10: (SRC_GO, "white",  "blue"),
    11: (SRC_GO, "white",  "pink"),
    12: (SRC_GO, "white",  "yellow"),
}
# Tier 8 needs green->white, orange stays orange:
VARIANTS[8] = (SRC_GO, "white", "orange")


def valid_png(path: Path) -> bool:
    if not path.exists() or path.stat().st_size == 0:
        return False
    try:
        with Image.open(path) as im:
            im.verify()
        return True
    except Exception:
        return False


def edit_image(api_key: str, src: Path, prompt: str) -> Image.Image:
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
    last_err = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            req = urllib.request.Request(
                URL, data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json",
                         "Authorization": f"Bearer {api_key}"},
                method="POST")
            with urllib.request.urlopen(req, timeout=300) as r:
                resp = json.loads(r.read())
            images = resp["choices"][0]["message"].get("images") or []
            if not images:
                raise ValueError(f"no images in response: {str(resp)[:200]}")
            item = images[0]
            url = item.get("image_url", {}).get("url") if isinstance(item, dict) else None
            if url and url.startswith("data:"):
                img_bytes = base64.b64decode(url.split(",", 1)[1])
            elif url:
                with urllib.request.urlopen(
                        urllib.request.Request(url), timeout=120) as r:
                    img_bytes = r.read()
            elif isinstance(item, dict) and item.get("b64_json"):
                img_bytes = base64.b64decode(item["b64_json"])
            else:
                raise ValueError(f"unrecognized image item: {str(item)[:200]}")
            im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
            im.load()
            return im
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
                ValueError, OSError, KeyError) as e:
            last_err = e
            wait = min(2 ** attempt, 60)
            print(f"  attempt {attempt}/{MAX_RETRIES} failed: {e}; "
                  f"retrying in {wait}s", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"edit failed after {MAX_RETRIES} attempts: {last_err}")


def animal_bbox(im: Image.Image):
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


def to_strip(im: Image.Image, out: Path):
    bbox = animal_bbox(im)
    animal = im.crop(bbox) if bbox else im
    H = 512
    W = H * 4
    scale = min(H / animal.height, W / animal.width)
    aw = round(animal.width * scale)
    ah = round(animal.height * scale)
    animal = animal.resize((aw, ah), Image.LANCZOS)
    canvas = Image.new("RGB", (W, H), (0, 0, 0))
    canvas.paste(animal, (W - aw, (H - ah) // 2))
    canvas.save(out, "PNG")


def prompt_for(primary: str, secondary: str | None, src_is_go: bool) -> str:
    keep = ("Keep the silver metallic body plates, the exact pose, coiled "
            "spiral tail, proportions, lighting, and the pure solid black "
            "background exactly the same. Only change the glowing accent "
            "colours. Output the edited image.")
    if src_is_go:
        return (f"Recolor this robotic chameleon: change every glowing green "
                f"element (eye core, torso conduits, tail strips, flank bars) "
                f"to {COLORS[primary]}, and change every orange element "
                f"(cabling, small indicator dots, connectors) to "
                f"{COLORS[secondary]}. {keep}")
    return (f"Recolor this robotic chameleon: change every glowing green "
            f"element (eye core, conduits, tail strips, joint lights, flank "
            f"bars) to {COLORS[primary]}. {keep}")


def main():
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    api_key = API_KEY_FILE.read_text().strip()
    if not api_key:
        print("ERROR: empty API key", file=sys.stderr)
        sys.exit(1)

    # Copy the two sources into raw/ for provenance.
    gg_raw = RAW_DIR / "lizard_src_green_green.png"
    go_raw = RAW_DIR / "lizard_src_green_orange.png"
    if not gg_raw.exists():
        Image.open(SRC_GG).convert("RGB").save(gg_raw)
    if not go_raw.exists():
        Image.open(SRC_GO).convert("RGB").save(go_raw)

    failures = []
    for tier in range(13):
        out = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
        if valid_png(out):
            print(f"[t{tier:02d}] skip (exists)", flush=True)
            continue
        src, primary, secondary = VARIANTS[tier]
        try:
            if src is None and tier == 2:
                im = Image.open(SRC_GG).convert("RGB")
            else:
                src_path = go_raw if src == SRC_GO else gg_raw
                prompt = prompt_for(primary, secondary, src == SRC_GO)
                print(f"[t{tier:02d}] editing (primary={primary} "
                      f"secondary={secondary}) ...", flush=True)
                im = edit_image(api_key, src_path, prompt)
                im.save(RAW_DIR / f"lizard_tier{tier}.png")
            to_strip(im, out)
            print(f"[t{tier:02d}] saved {out.name} {out.stat().st_size} bytes",
                  flush=True)
        except Exception as e:
            failures.append({"tier": tier, "error": str(e)})
            print(f"[t{tier:02d}] FAILED: {e}", flush=True)
        time.sleep(1.5)

    print(f"\nSummary: failed={len(failures)}", flush=True)
    if failures:
        print(json.dumps(failures, indent=2), flush=True)
        sys.exit(2)


if __name__ == "__main__":
    main()
