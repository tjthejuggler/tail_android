#!/usr/bin/env python3
"""Generate the wide mecha-chameleon artwork for the tier-bar widget.

Uses the same PPQ grok-imagine-image-2 API as the wallpaper batch
(generate_images.py). The app icon is a robotic chameleon tail; this
generates the whole animal that tail belongs to, on a pure-black
background so it can be SCREEN-blended over the tier gradient at
runtime. Output: app/src/main/res/drawable-nodpi/tier_bar_chameleon.png
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
OUT = BASE.parent / "app/src/main/res/drawable-nodpi/tier_bar_chameleon.png"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"
MAX_RETRIES = 5

PROMPT = (
    "A full-body cyberpunk mecha chameleon robot, side profile, standing on the "
    "right side of a very wide horizontal composition, facing left. Its tail is "
    "the centerpiece: a tightly coiled clockwise spiral of segmented metallic "
    "armor plates, tapering from a thick base with a circular mechanical joint "
    "hub to a small rounded end-cap, about 1.5-2 full turns. Materials: metallic "
    "steel-blue and gunmetal gray armor plates with bright cyan/teal glowing "
    "edges and rim light, deep indigo/purple shadows in crevices, small glowing "
    "amber/orange LED dots along the segments, teal glowing seams between "
    "plates, dark navy/black panel linework, rivets and mechanical joints. The "
    "body matches the tail: same segmented mecha chameleon armor, cyan glow "
    "accents, amber sensor eyes, perched calmly with the spiral tail raised and "
    "prominent. Detailed digital illustration, sci-fi mecha concept art style "
    "with specular highlights and emissive glow. Pure solid black background, "
    "no environment, no text. Wide panoramic banner composition with the animal "
    "and tail occupying the right two-thirds, empty black space on the left."
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


def main():
    OUT.parent.mkdir(parents=True, exist_ok=True)
    api_key = API_KEY_FILE.read_text().strip()
    if not api_key:
        print("ERROR: empty API key", file=sys.stderr)
        sys.exit(1)

    headers = {"Authorization": f"Bearer {api_key}"}
    payload = {
        "model": MODEL,
        "prompt": PROMPT,
        "quality": "low",
        "resolution": "1k",
        "aspect_ratio": "16:9",
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
            im.save(OUT, "PNG")
            print(f"saved {OUT} {im.size} ({OUT.stat().st_size} bytes)")
            return
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
                ValueError, OSError) as e:
            last_err = e
            wait = min(2 ** attempt, 60)
            print(f"attempt {attempt}/{MAX_RETRIES} failed: {e}; retrying in {wait}s",
                  flush=True)
            time.sleep(wait)
    print(f"FAILED after {MAX_RETRIES} attempts: {last_err}", file=sys.stderr)
    sys.exit(2)


if __name__ == "__main__":
    main()
