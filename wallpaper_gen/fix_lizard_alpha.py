#!/usr/bin/env python3
"""Repair broken alpha channels in the lizard widget art.

Defect: chroma-keying / brightness feathering ate parts of the CREATURE:
  * tier_bar_lizard_m*_p*.png  -- morph strips keyed on pure-blue chroma;
    the lizard's own blue-glow accents (span m3 = blue tier, m10 = white/blue)
    and dark metal enclosed regions were keyed/eroded to transparent,
    leaving holes in the tail coil and body plates.
  * tier_bar_lizard_t<N>.png   -- milestone strips: brightness feather made
    every dark metal pixel semi-transparent (up to ~134k semi px/strip).
  * lizard_pose_t<N>_p<NN>.png -- keyed on magenta bg + green dummy squares
    with tol=150; pink-family art (tiers 4/11) and green accents (2/9) can
    be eaten the same way.

The RGB channels are intact under every hole (verified), so the repair is
alpha-only:

  1. Flood fill from the image borders through pixels with alpha < 128
     -> OUTSIDE (real background).
  2. Enclosed components (alpha < 250, not outside) = candidate holes.
  3. A component is a LEGITIMATE void if its RGB matches the strip's
     background signature:
       - m* strips / pose canvases: keyed chroma (blue #0000FF, magenta
         #FF00FF, green #00FF00) within RGB distance 100 on >=55% of px.
       - t* milestone strips: near-black (max RGB < 40) on >=55% of px.
     Legitimate voids keep their transparency.
  4. Everything else (creature detail) is restored to fully opaque.

Originals are backed up once to wallpaper_gen/raw/alpha_fix_backup/.

Usage:
  python3 wallpaper_gen/fix_lizard_alpha.py --dry-run   # report only
  python3 wallpaper_gen/fix_lizard_alpha.py             # apply
"""
import argparse
import colorsys
from collections import deque
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
BACKUP = BASE / "raw" / "alpha_fix_backup"

import re

M_RE = re.compile(r"^tier_bar_lizard_m\d+_p\d+\.png$")
T_RE = re.compile(r"^tier_bar_lizard_t\d+\.png$")
P_RE = re.compile(r"^lizard_pose_t\d+_p\d+\.png$")

# chroma signatures: name -> (r, g, b)
CHROMAS = {
    "blue": (0, 0, 255),
    "magenta": (255, 0, 255),
    "green": (0, 255, 0),
}
CHROMA_DIST = 100
CHROMA_FRAC = 0.55
DARK_FRAC = 0.55

# pose sprites only: the dummy-square erase rects are (0,0,0,0). A fully
# transparent pure-black component there is an erased square, not a hole.
BLACK_VOID_OK = False


def near(r, g, b, c, dist):
    dr, dg, db = r - c[0], g - c[1], b - c[2]
    return dr * dr + dg * dg + db * db <= dist * dist


def flood_outside(w, h, px):
    """Flood from borders through alpha<128. Returns outside bytearray."""
    outside = bytearray(w * h)
    dq = deque()
    for x in range(w):
        for y in (0, h - 1):
            i = y * w + x
            if px[x, y][3] < 128 and not outside[i]:
                outside[i] = 1
                dq.append(i)
    for y in range(h):
        for x in (0, w - 1):
            i = y * w + x
            if px[x, y][3] < 128 and not outside[i]:
                outside[i] = 1
                dq.append(i)
    while dq:
        i = dq.popleft()
        x, y = i % w, i // w
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h:
                j = ny * w + nx
                if not outside[j] and px[nx, ny][3] < 128:
                    outside[j] = 1
                    dq.append(j)
    return outside


def components_of(cand, w, h):
    seen = bytearray(w * h)
    comps = []
    for start in range(w * h):
        if not cand[start] or seen[start]:
            continue
        comp = []
        dq = deque([start])
        seen[start] = 1
        while dq:
            i = dq.popleft()
            comp.append(i)
            x, y = i % w, i // w
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                j = ny * w + nx
                if 0 <= j < w * h and cand[j] and not seen[j]:
                    seen[j] = 1
                    dq.append(j)
        comps.append(comp)
    return comps


def is_void_morph(comp, px):
    n = len(comp)
    hits = 0
    for i in comp:
        x, y = i % w_, i // w_
        r, g, b, a = px[x, y]
        if any(near(r, g, b, c, CHROMA_DIST) for c in CHROMAS.values()):
            hits += 1
        elif BLACK_VOID_OK and a == 0 and max(r, g, b) < 40:
            hits += 1
    return hits >= n * CHROMA_FRAC


def is_void_milestone(comp, px):
    n = len(comp)
    hits = 0
    for i in comp:
        x, y = i % w_, i // w_
        r, g, b, a = px[x, y]
        if max(r, g, b) < 40:
            hits += 1
    return hits >= n * DARK_FRAC


w_ = 0  # module-level width used by the void testers


def repair_file(path: Path, dry: bool):
    global w_
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    w_ = w
    px = im.load()

    global BLACK_VOID_OK
    is_morph = bool(M_RE.match(path.name))
    is_pose = bool(P_RE.match(path.name))
    BLACK_VOID_OK = is_pose
    void_test = is_void_morph if (is_morph or is_pose) else is_void_milestone

    outside = flood_outside(w, h, px)

    cand = bytearray(w * h)
    for i in range(w * h):
        if not outside[i] and px[i % w, i // w][3] < 250:
            cand[i] = 1

    comps = components_of(cand, w, h)
    repaired = 0
    kept_void = 0
    for comp in comps:
        if void_test(comp, px):
            kept_void += len(comp)
            continue
        for i in comp:
            x, y = i % w, i // w
            r, g, b, a = px[x, y]
            if a != 255:
                px[x, y] = (r, g, b, 255)
                repaired += 1

    semi_inside = sum(
        1 for i in range(w * h)
        if not outside[i] and px[i % w, i // w][3] not in (0, 255))

    status = "DRY" if dry else "FIXED"
    print(f"[{status}] {path.name}: holes_repaired={repaired} "
          f"void_kept={kept_void} semi_remaining={semi_inside}",
          flush=True)
    if repaired and not dry:
        if not (BACKUP / path.name).exists():
            BACKUP.mkdir(parents=True, exist_ok=True)
            import shutil
            shutil.copy2(path, BACKUP / path.name)
        im.save(path, "PNG")
    return repaired


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--skip-poses", action="store_true")
    args = ap.parse_args()

    files = [p for p in sorted(OUT_DIR.iterdir())
             if T_RE.match(p.name) or M_RE.match(p.name)
             or (P_RE.match(p.name) and not args.skip_poses)]
    print(f"{len(files)} candidate files")
    total = 0
    touched = 0
    for p in files:
        n = repair_file(p, args.dry_run)
        total += n
        touched += 1 if n else 0
    print(f"\nSummary: files={len(files)} touched={touched} "
          f"px_repaired={total}")


if __name__ == "__main__":
    main()
