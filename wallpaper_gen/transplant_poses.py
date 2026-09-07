#!/usr/bin/env python3
"""Transplant the NEW ascended lizard art into the APPROVED old pose art.

Background (2026-09-07): the white-combo tiers 7-12 got upgraded strip art
(upgrade_ascended_lizards.py).  Tiers 7-10 and t11 p00-p07 were re-generated
from scratch with the standard ref-canvas pipeline, but the user directed the
remaining poses (t11 p08-p11, t12 p00-p11) be handled differently: the OLD
pose compositions were already approved, so instead of re-imagining them we
send a TWO-image edit to ppq.ai:

  image 1 = the OLD raw generation (chroma canvas, OLD lizard in the pose)
            from wallpaper_gen/raw/poses_backup_t8_t12_20260906/
  image 2 = the NEW tier strip's lizard, alpha-cropped and pasted on the
            tier's flat background-key colour (identity reference)

Prompt: reproduce image 1 exactly (pose, squares, props, background) but the
chameleon itself becomes the NEW character from image 2.  The returned image
then goes through the UNCHANGED standard postprocess (grid align, hue keying,
despill, size normalizer, per-cell erase) + verify() + manifest writer
imported from gen_lizard_poses.py.

The old raws use the same chroma scheme as the current per-tier keys
(t11: blue bg + green squares; t12: magenta bg + cyan squares), so no key
changes are needed.

Usage:
  python3 wallpaper_gen/transplant_poses.py --tier 11            # p08..p11
  python3 wallpaper_gen/transplant_poses.py --tier 12            # all 12
  python3 wallpaper_gen/transplant_poses.py --tier 11 --only 8   # one pose
  python3 wallpaper_gen/transplant_poses.py --tier 12 --reprocess
"""
import argparse
import base64
import io
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from PIL import Image

from gen_lizard_poses import (
    MAX_RETRIES,
    OUT_DIR,
    POSES_BY_TIER,
    RAW_DIR,
    TIER_COLORS,
    URL,
    bg_key_for,
    key_words,
    postprocess,
    valid_png,
    verify,
    write_manifest,
)

BASE = Path(__file__).resolve().parent
BACKUP = BASE / "raw" / "poses_backup_t8_t12_20260906"

# targets: tier -> list of pose indices still missing new-art finals
TARGETS = {
    11: [8, 9, 10, 11],
    12: list(range(12)),
}


def crop_strip_lizard(tier: int) -> Image.Image:
    """Alpha-crop the NEW lizard out of the current tier strip."""
    strip = Image.open(OUT_DIR / f"tier_bar_lizard_t{tier}.png").convert("RGBA")
    bbox = strip.getchannel("A").getbbox()
    if not bbox:
        raise RuntimeError(f"strip t{tier} has no opaque content")
    return strip.crop(bbox)


def identity_canvas(tier: int, size: tuple) -> Image.Image:
    """The NEW lizard on a flat bg-key canvas (identity reference image)."""
    lizard = crop_strip_lizard(tier)
    W, H = size
    canvas = Image.new("RGB", size, bg_key_for(tier))
    # scale the lizard to ~78% of the canvas width, centred
    tgt_w = int(W * 0.78)
    scale = tgt_w / lizard.width
    lz = lizard.resize((tgt_w, max(1, int(lizard.height * scale))),
                       Image.LANCZOS)
    if lz.height > H * 0.92:  # ultra-wide strips (t12): cap by height too
        scale = (H * 0.92) / lz.height
        lz = lizard.resize((max(1, round(lizard.width * scale)),
                            max(1, round(H * 0.92))), Image.LANCZOS)
    canvas.paste(lz, ((W - lz.width) // 2, (H - lz.height) // 2), lz)
    return canvas


def transplant_prompt(p: dict, tier: int) -> str:
    colour = TIER_COLORS[tier]
    bg_hex, sq_hex, sq_word = key_words(tier)
    sq_upper = sq_word.upper()
    return (
        f"You are given TWO images.\n"
        f"FIRST IMAGE: approved concept art of a silver metallic robotic "
        f"chameleon with glowing {colour} accents, in a specific pose on "
        f"flat solid {sq_hex} squares over a flat solid {bg_hex} "
        f"background. Its composition is FINAL: keep the exact same camera, "
        f"the exact same pose, the exact same placement, size, orientation "
        f"and props.\n"
        f"SECOND IMAGE: the CURRENT official design of that same chameleon "
        f"character. This newer design REPLACES the older lizard drawn in "
        f"the first image.\n"
        f"TASK: reproduce the FIRST image exactly — identical {sq_word} "
        f"squares in identical positions and sizes, identical flat {bg_hex} "
        f"background, identical pose, identical props — with ONE change "
        f"only: the chameleon itself must be the NEW character design shown "
        f"in the SECOND image (same silver gunmetal segmented armour, same "
        f"head shape, same camera eye, same coiled spiral tail, same "
        f"{colour} glow as the second image), holding the first image's "
        f"pose in the same spot at the same scale with the same contact "
        f"points.\n"
        f"Hard requirements:\n"
        f"- Exactly ONE single chameleon in the whole image. No duplicates, "
        f"no reflections, no second smaller copy, no leftover of the OLD "
        f"design.\n"
        f"- The chameleon stays ENTIRELY ABOVE the TOP EDGES of the "
        f"{sq_word} squares (except its toes gripping the top face and, "
        f"when the pose dangles, the explicitly hanging parts). No body "
        f"part IN FRONT of a {sq_word} square.\n"
        f"- SCALE: torso (belly to back, excluding curled tail) about AS "
        f"TALL AS ONE {sq_upper} SQUARE — exactly as large as the lizard "
        f"in the first image.\n"
        f"- Keep every prop from the first image present, unchanged in "
        f"kind and placement. Do not drop or invent props.\n"
        f"- The {sq_word} squares stay exactly where they are, same flat "
        f"solid {sq_hex} colour, same size, each exactly filling its grid "
        f"cell.\n"
        f"- The entire background stays flat solid {bg_hex} edge to edge — "
        f"no gradient, texture, scenery or shadows.\n"
        f"- The key colours ({bg_hex} background, {sq_hex} squares) appear "
        f"ONLY as those flat fills — never on the chameleon, its glow, its "
        f"eye, or any prop.\n"
        + (f"- LAYOUT OVERRIDE: {p['layout']}\n" if p.get("layout") else "")
        + (f"- {p['avoid']}\n" if p.get("avoid") else "")
        + f"Output the edited image."
    )


def edit_image_multi(api_key: str, images, prompt: str) -> Image.Image:
    """ppq.ai image-edit with MULTIPLE reference images (chat completions)."""
    content = []
    for im in images:
        buf = io.BytesIO()
        im.save(buf, "PNG")
        b64 = base64.b64encode(buf.getvalue()).decode()
        content.append({"type": "image_url",
                        "image_url": {"url": f"data:image/png;base64,{b64}"}})
    content.append({"type": "text", "text": prompt})
    payload = {"model": "google/gemini-3-pro-image",
               "messages": [{"role": "user", "content": content}]}
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
            msg = resp["choices"][0]["message"]
            imgs = msg.get("images") or []
            if not imgs:
                audio = msg.get("audio") or {}
                if isinstance(audio, dict) and audio.get("data"):
                    return Image.open(io.BytesIO(
                        base64.b64decode(audio["data"]))).convert("RGB")
                raise ValueError(f"no images: {str(resp)[:200]}")
            item = imgs[0]
            url = item.get("image_url", {}).get("url") \
                if isinstance(item, dict) else None
            if url and url.startswith("data:"):
                data = base64.b64decode(url.split(",", 1)[1])
            elif url:
                with urllib.request.urlopen(urllib.request.Request(url),
                                            timeout=120) as r:
                    data = r.read()
            elif isinstance(item, dict) and item.get("b64_json"):
                data = base64.b64decode(item["b64_json"])
            else:
                raise ValueError(f"unrecognized item: {str(item)[:200]}")
            im = Image.open(io.BytesIO(data)).convert("RGB")
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", type=int, required=True, choices=[11, 12])
    ap.add_argument("--only", type=int, default=-1,
                    help="transplant only pose index N")
    ap.add_argument("--attempts", type=int, default=3,
                    help="regen attempts per pose until verify() is clean")
    ap.add_argument("--reprocess", action="store_true",
                    help="re-run postprocess from cached raws, no API calls")
    args = ap.parse_args()
    tier = args.tier

    api_key = Path("/home/twain/Projects/small_scripts/"
                   "ppq_imageGen_apikey.txt").read_text().strip()
    defs = POSES_BY_TIER[tier]
    RAW_DIR.mkdir(parents=True, exist_ok=True)

    # identity reference is per-tier constant — build once
    iden = None

    failures = []
    for i in TARGETS[tier]:
        if args.only >= 0 and i != args.only:
            continue
        p = defs[i]
        out = OUT_DIR / f"lizard_pose_t{tier}_p{i:02d}.png"
        raw_path = RAW_DIR / f"raw_t{tier}_p{i:02d}.png"

        if args.reprocess:
            if not valid_png(raw_path):
                print(f"[t{tier} p{i:02d}] no raw cached, skip", flush=True)
                continue
            print(f"[t{tier} p{i:02d} {p['name']}] reprocessing ...",
                  flush=True)
            im = postprocess(Image.open(raw_path), p, tier)
            im.save(out)
            verify(im, p)
            print(f"[t{tier} p{i:02d}] saved {out.name}", flush=True)
            continue

        if valid_png(out):
            print(f"[t{tier} p{i:02d} {p['name']}] skip (exists)", flush=True)
            continue

        old_raw_path = BACKUP / f"raw_t{tier}_p{i:02d}.png"
        if not valid_png(old_raw_path):
            failures.append({"tier": tier, "pose": i,
                             "error": f"missing backup raw {old_raw_path}"})
            continue
        if iden is None:
            iden = identity_canvas(tier, Image.open(old_raw_path).size)
            iden.save(RAW_DIR / f"iden_t{tier}.png")

        print(f"[t{tier} p{i:02d} {p['name']}] transplanting ...", flush=True)
        ok = False
        for attempt in range(1, args.attempts + 1):
            try:
                raw = edit_image_multi(
                    api_key,
                    [Image.open(old_raw_path).convert("RGB"), iden],
                    transplant_prompt(p, tier))
                raw.save(raw_path)
                im = postprocess(raw, p, tier)
                problems = verify(im, p)  # prints WARN lines, returns bool
                im.save(RAW_DIR / f"pose_t{tier}_p{i:02d}.png")
                out.write_bytes(
                    (RAW_DIR / f"pose_t{tier}_p{i:02d}.png").read_bytes())
                kb = out.stat().st_size // 1024
                print(f"[t{tier} p{i:02d}] attempt {attempt}: saved "
                      f"{out.name} ({kb} KB) verify_ok={problems}", flush=True)
                if problems and kb >= 100:   # sparse-pose floor ~100 KB
                    ok = True
                    break
                print(f"[t{tier} p{i:02d}] verify failed — regenerating",
                      flush=True)
                out.unlink()
            except Exception as e:
                failures.append({"tier": tier, "pose": i,
                                 "attempt": attempt, "error": str(e)})
                print(f"[t{tier} p{i:02d}] attempt {attempt} FAILED: {e}",
                      flush=True)
            time.sleep(1.5)
        if not ok:
            failures.append({"tier": tier, "pose": i,
                             "error": "exhausted attempts"})
        time.sleep(1.5)

    write_manifest(tier, defs)
    print(f"\nSummary: failed={len(failures)}", flush=True)
    if failures:
        print(json.dumps(failures, indent=2), flush=True)
        sys.exit(2)


if __name__ == "__main__":
    main()
