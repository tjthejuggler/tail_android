#!/usr/bin/env python3
"""Generate three DISTINCT dark steel panel textures via PPQ grok-imagine-2.

  steel_topbar.png — riveted plating band for the top date/action bar
  steel_tabs.png   — long brushed-steel band for the scrolling screen-name row
  steel_advice.png — diamond-plate square for the advice banner (any height)

Each has its own metal character so no two surfaces look alike. Bands are
cropped from the centre of a 1024px square generation. Dark & muted so light
text stays readable.
"""
import base64
import io
import json
import time
import urllib.request
from pathlib import Path

from PIL import Image, ImageEnhance, ImageFilter, ImageStat

BASE = Path(__file__).resolve().parent
RAW_DIR = BASE / "raw_habit_tiles"
OUT_DIR = BASE.parent / "app/src/main/res/drawable"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"

BASE_PROMPT = (
    "{look} Overall DARK and MUTED (near #22262B average tone) so light text "
    "stays readable on top. Flat frontal view, fills the entire frame edge to "
    "edge, no borders, no text, no vignette, no objects."
)

PANELS = {
    # name → (raw name, crop (l,t,r,b) fraction, prompt look, output width)
    "steel_topbar": (
        "raw_topbar.png", (0.0, 0.42, 1.0, 0.58),
        "Seamless industrial steel plating with rows of large round rivet "
        "heads and hex bolts along the edges, brushed grain between them, "
        "soft specular glints on each rivet, subtle diagonal lighting.",
        1024,
    ),
    "steel_tabs": (
        "raw_tabs3.png", (0.0, 0.38, 1.0, 0.62),
        "Smooth dark brushed stainless steel band, fine silky horizontal "
        "brush grain, very subtle soft sheen gradient, elegant minimal "
        "industrial finish, no rivets, no bolts, no marks — calm refined "
        "metal surface.",
        1024,
    ),
    "silver_metal": (
        "raw_silver.png", None,
        "Seamless tileable brushed SILVER steel texture, fine horizontal "
        "brush grain, subtle diagonal sheen, bright polished metal with "
        "gentle tonal variation, elegant modern brushed aluminium finish. "
        "Flat frontal view, fills the entire frame edge to edge, no borders, "
        "no text, no vignette, no objects.",
        512,
    ),
    "steel_advice": (
        "raw_advice2.png", (0.0, 0.25, 1.0, 0.75),
        "Seamless dark gunmetal diamond tread plate / checker plate metal, "
        "large raised diamond pattern, bold scratches and oil-sheen "
        "gradients, moody industrial finish, high contrast highlights on "
        "the raised diamonds.",
        1024,
    ),
}


def load_key():
    return API_KEY_FILE.read_text().strip()


def generate(prompt, headers):
    payload = {"model": MODEL, "prompt": prompt, "n": 1,
               "size": "1024x1024", "response_format": "b64_json"}
    req = urllib.request.Request(
        URL, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **headers}, method="POST")
    with urllib.request.urlopen(req, timeout=300) as resp:
        data = json.loads(resp.read().decode())
    return base64.b64decode(data["data"][0]["b64_json"])


def main():
    headers = {"Authorization": f"Bearer {load_key()}"}
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    failures = []
    for name, (raw_name, crop, look, out_w) in PANELS.items():
        raw_path = RAW_DIR / raw_name
        try:
            if not (raw_path.exists() and raw_path.stat().st_size > 0):
                prompt = BASE_PROMPT.format(look=look)
                for attempt in range(5):
                    try:
                        raw_path.write_bytes(generate(prompt, headers))
                        break
                    except Exception as e:
                        print(f"retry {name} ({attempt + 1}): {e}")
                        time.sleep(3)
                else:
                    failures.append(name)
                    continue
            img = Image.open(io.BytesIO(raw_path.read_bytes())).convert("RGB")
            if crop:
                w, h = img.size
                l, t, r, b = crop
                img = img.crop((int(l * w), int(t * h), int(r * w), int(b * h)))
            if img.width > out_w:
                img = img.resize((out_w, int(img.height * out_w / img.width)),
                                 Image.LANCZOS)
            if name == "silver_metal":
                # Habit-square-style normalized grayscale overlay (mean 128)
                # so the app can tint/overlay it on any base colour.
                gray = img.convert("L")
                stats = ImageStat.Stat(gray)
                sd, mean = stats.stddev[0], stats.mean[0]
                k = 30.0 / sd if sd > 1 else 1.0
                lut = [max(0, min(255, int(128 + (v - mean) * k))) for v in range(256)]
                gray.point(lut).save(OUT_DIR / "silver_metal.png")
                print(f"ok silver_metal (overlay) -> {OUT_DIR / 'silver_metal.png'}")
                time.sleep(1.5)
                continue
            if name == "steel_tabs":
                # Heavy blur = subtle, seam-free band: blurring removes all
                # mirror-concat artifacts and distinctive marks, leaving a
                # calm dark brushed gradient that scrolls without any visible
                # repetition.
                img = img.filter(ImageFilter.GaussianBlur(6))
                img = ImageEnhance.Brightness(img).enhance(0.85)
                img = ImageEnhance.Contrast(img).enhance(0.8)
            else:
                # Top bar / advice: BARELY metal — grayscale, blurred, then
                # mostly mixed into flat dark grey so only a ghost of the
                # texture remains.
                img = img.convert("L").convert("RGB")
                img = img.filter(ImageFilter.GaussianBlur(4))
                flat = Image.new("RGB", img.size, (58, 58, 62))
                img = Image.blend(flat, img, 0.35)
            out = OUT_DIR / f"{name}.png"
            img.save(out)
            print(f"ok {name} {img.size} -> {out}")
        except Exception as e:
            print(f"failed {name}: {e}")
            failures.append(name)
        time.sleep(1.5)
    if failures:
        raise SystemExit(f"FAILED: {failures}")
    print("all steel panels generated")


if __name__ == "__main__":
    main()
