#!/usr/bin/env python3
"""Generate metallic habit-tile textures via PPQ grok-imagine-image-2 API.

One tile per habit background tier colour — each a DIFFERENT metal so every
tier has its own visible character: red = hammered copper, orange = bronze,
green = aged verdigris, blue = blued gunmetal steel, pink = anodised magenta
aluminium, yellow = brushed brass, glass = polished/brushed aluminium.

Raw 1024px generations are kept in wallpaper_gen/raw_habit_tiles/ for
inspection. Post-processing produces a grayscale overlay whose mean
luminance is neutral (128) so the app multiply/overlay-tints it with the
exact tier colour. Texture is deliberately COARSE (broad streaks, big
hammer marks) so it survives being scaled down to a ~40dp grid cell.

Output: app/src/main/res/drawable/habit_tile_<name>.png (256×256 grayscale).
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
MAX_RETRIES = 5
SIZE = 256
TARGET_SD = 30.0  # post-stretch stddev — clearly visible grain after blending

# name → (hex base colour, distinct metal look)
TILES = {
    "red":    ("#3D1515", "hammered copper with visible dimpled hammer-mark dents and broad warm streaks"),
    "orange": ("#7A3800", "cast bronze with rough sand-cast pits and wide molten pour streaks"),
    "green":  ("#1A4020", "aged verdigris copper with mottled patina patches and broad weathered streaks"),
    "blue":   ("#102255", "blued gunmetal steel with bold horizontal forging streaks and faint temper gradients"),
    "pink":   ("#901060", "anodised magenta aluminium with wide iridescent bands and large brushed sweeps"),
    "yellow": ("#B8B000", "brushed brass with prominent long diagonal brush strokes and soft golden sheen bands"),
    "glass":  ("#D0D0E0", "brushed aluminium with clean wide parallel grain bands and a soft diagonal polish sheen"),
}

PROMPT_TEMPLATE = (
    "Extreme close-up macro photograph filling the entire frame with a flat "
    "sheet of dark {metal}. The metal's base tone is {hex}. Clearly visible "
    "metallic surface detail: {detail}. Texture features must be LARGE and "
    "BOLD (each streak or mark spanning a quarter to half of the frame), high "
    "local contrast, like a macro photo where you can see every mark. Slight "
    "diagonal lighting, specular highlights glinting off the marks. No "
    "objects, no borders, no text, no vignette, edge-to-edge surface only."
)


def load_key():
    return API_KEY_FILE.read_text().strip()


def generate(prompt, headers):
    payload = {
        "model": MODEL,
        "prompt": prompt,
        "n": 1,
        "size": "1024x1024",
        "response_format": "b64_json",
    }
    req = urllib.request.Request(
        URL, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **headers}, method="POST")
    with urllib.request.urlopen(req, timeout=300) as resp:
        data = json.loads(resp.read().decode())
    return base64.b64decode(data["data"][0]["b64_json"])


def postprocess(raw_bytes, out_path):
    img = Image.open(io.BytesIO(raw_bytes)).convert("RGB")
    img = img.resize((SIZE, SIZE), Image.LANCZOS)
    gray = img.convert("L")
    # Keep the character: stretch to target stddev so the bold marks stay
    # bold, then re-center the mean on neutral 128 so Overlay-tinting keeps
    # the tier colour's overall brightness unchanged.
    stats = ImageStat.Stat(gray)
    sd, mean = stats.stddev[0], stats.mean[0]
    if sd > 1:
        k = TARGET_SD / sd
        lut = [max(0, min(255, int(128 + (v - mean) * k))) for v in range(256)]
        gray = gray.point(lut)
    else:
        lut = [max(0, min(255, int(128 + (v - mean)))) for v in range(256)]
        gray = gray.point(lut)
    gray.save(out_path)


def main():
    headers = {"Authorization": f"Bearer {load_key()}"}
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    failures = []
    for name, (hexcol, metal) in TILES.items():
        raw_path = RAW_DIR / f"{name}.png"
        out = OUT_DIR / f"habit_tile_{name}.png"
        try:
            if raw_path.exists() and raw_path.stat().st_size > 0:
                raw_bytes = raw_path.read_bytes()
            else:
                prompt = PROMPT_TEMPLATE.format(metal=metal, hex=hexcol, detail=metal)
                raw_bytes = None
                for attempt in range(MAX_RETRIES):
                    try:
                        raw_bytes = generate(prompt, headers)
                        raw_path.write_bytes(raw_bytes)
                        break
                    except Exception as e:
                        print(f"retry {name} ({attempt + 1}): {e}")
                        time.sleep(3)
                if raw_bytes is None:
                    failures.append(name)
                    continue
            postprocess(raw_bytes, out)
            s = ImageStat.Stat(Image.open(out).convert("L"))
            print(f"ok {name} (raw: {raw_path.name}, sd {s.stddev[0]:.1f})")
        except Exception as e:
            print(f"postprocess failed {name}: {e}")
            failures.append(name)
        time.sleep(1.5)
    if failures:
        print(f"FAILED: {failures}")
        raise SystemExit(1)
    print("all tiles generated")


if __name__ == "__main__":
    main()
