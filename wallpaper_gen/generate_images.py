#!/usr/bin/env python3
"""Generate 100 square wallpaper images (raw) via PPQ grok-imagine-image-2 API.

Resumable: skips n whose wallpaper_gen/raw/result_<n>.png exists as a valid PNG.
API key read at runtime from /home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt.
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
PROMPT_FILES = [BASE / "prompts_00_49.json", BASE / "prompts_50_99.json"]
FAILURES_FILE = BASE / "gen_failures.json"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

URL = "https://api.ppq.ai/v1/images/generations"
MODEL = "grok-imagine-image-2"
MAX_RETRIES = 5
SLEEP_BETWEEN = 1.5


def load_prompts():
    prompts = {}
    for f in PROMPT_FILES:
        for item in json.loads(f.read_text()):
            prompts[item["n"]] = item["prompt"]
    return prompts


def valid_png(path: Path) -> bool:
    if not path.exists() or path.stat().st_size == 0:
        return False
    try:
        with Image.open(path) as im:
            im.verify()
        return True
    except Exception:
        return False


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
    """Return raw image bytes from a response data item (b64 or url)."""
    if item.get("b64_json"):
        return base64.b64decode(item["b64_json"])
    url = item.get("url")
    if not url:
        raise ValueError(f"no b64_json or url in response item: {str(item)[:200]}")
    return get_bytes(url)


def to_square_png(img_bytes: bytes) -> Image.Image:
    im = Image.open(io.BytesIO(img_bytes))
    im.load()
    w, h = im.size
    if w != h:
        side = min(w, h)
        left = (w - side) // 2
        top = (h - side) // 2
        im = im.crop((left, top, left + side, top + side))
    return im.convert("RGB")


def generate_one(n, prompt, api_key):
    headers = {"Authorization": f"Bearer {api_key}"}
    payload = {
        "model": MODEL,
        "prompt": prompt,
        "quality": "low",
        "resolution": "1k",
        "aspect_ratio": "1:1",
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
            im = to_square_png(img_bytes)
            out = RAW_DIR / f"result_{n}.png"
            im.save(out, "PNG")
            return out, im.size
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
                ValueError, OSError) as e:
            last_err = e
            wait = min(2 ** attempt, 60)
            print(f"  n={n} attempt {attempt}/{MAX_RETRIES} failed: {e}; "
                  f"retrying in {wait}s", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"n={n} failed after {MAX_RETRIES} attempts: {last_err}")


def main():
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    api_key = API_KEY_FILE.read_text().strip()
    if not api_key:
        print("ERROR: empty API key", file=sys.stderr)
        sys.exit(1)
    prompts = load_prompts()
    missing = [n for n in range(100) if n not in prompts]
    if missing:
        print(f"ERROR: prompts missing for n={missing}", file=sys.stderr)
        sys.exit(1)

    skipped = done = 0
    failures = []
    for n in range(100):
        out = RAW_DIR / f"result_{n}.png"
        if valid_png(out):
            skipped += 1
            continue
        print(f"[{n:02d}/99] generating ...", flush=True)
        try:
            path, size = generate_one(n, prompts[n], api_key)
            done += 1
            print(f"[{n:02d}/99] saved {path.name} {size} "
                  f"({path.stat().st_size} bytes)", flush=True)
        except Exception as e:
            failures.append({"n": n, "error": str(e)})
            print(f"[{n:02d}/99] FAILED: {e}", flush=True)
        time.sleep(SLEEP_BETWEEN)

    FAILURES_FILE.write_text(json.dumps(failures, indent=2))
    print(f"\nSummary: generated={done} skipped(existing)={skipped} "
          f"failed={len(failures)}", flush=True)
    if failures:
        print(f"Failures logged to {FAILURES_FILE}", flush=True)
        sys.exit(2)


if __name__ == "__main__":
    main()
