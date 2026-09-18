#!/usr/bin/env python3
"""Remove leftover magenta/pink chroma remnants from the env (scenery)
widget layers (tier_bar_env_t*.png).

Defect: gen_scene_layers.py keyed the magenta negative space with edge
flood fill only. Remnants survive as
  * magenta patches enclosed between grass blades / inside negative-space
    pockets (visible as pink bits over the sky in the composed widget), and
  * pink fringe halos on silhouette edges (esp. the blue tiers).

Ground truth from the raw sources (wallpaper_gen/raw/env_*.png):
  * every non-pink tier's art contains ZERO magenta-family colour
    (red rocks are deep crimson with a LOW blue channel; yellow/orange/
    green/blue/glass art all fail the magenta test) ->
    GLOBAL relaxed-magenta removal is safe;
  * the pink tier (4) and its mirror (11) legitimately use bright magenta
    blades -> only strict-magenta regions CONNECTED TO TRANSPARENCY are
    removed there, nothing else.

Fringe despill (non-pink tiers): borderline magenta-tinted pixels adjacent
to removed/transparent areas are recoloured from clean neighbours instead
of being punched out, keeping silhouette edges smooth.

Originals are backed up once to wallpaper_gen/raw/alpha_fix_backup/.

Usage:
  python3 wallpaper_gen/fix_env_magenta.py --dry-run
  python3 wallpaper_gen/fix_env_magenta.py
"""
import argparse
from collections import deque
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
BACKUP = BASE / "raw" / "alpha_fix_backup"

PINK_TIERS = {4, 11}


def strict_magenta(r, g, b):
    return r > 170 and b > 170 and g < 90 and abs(r - b) < 70


def relaxed_magenta(r, g, b):
    mn = min(r, b)
    return mn > 110 and g < 0.72 * mn and abs(r - b) < 110


def borderline_magenta(r, g, b):
    mn = min(r, b)
    return mn > 90 and g < 0.80 * mn and abs(r - b) < 120


def flood_from_transparent(w, h, px, grow_test):
    """Flood from alpha<128 pixels through grow_test pixels."""
    seen = bytearray(w * h)
    dq = deque()
    for y in range(h):
        for x in range(w):
            i = y * w + x
            if not seen[i] and px[x, y][3] < 128:
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


def clean(path: Path, dry: bool):
    tier = int(path.stem.rsplit("t", 1)[1])
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()
    is_pink = tier in PINK_TIERS

    removed = []
    if is_pink:
        stain = flood_from_transparent(w, h, px, strict_magenta)
        for y in range(h):
            for x in range(w):
                i = y * w + x
                if stain[i]:
                    r, g, b, a = px[x, y]
                    px[x, y] = (r, g, b, 0)
                    removed.append(i)
    else:
        # global relaxed removal + despill bookkeeping
        near = bytearray(w * h)   # px adjacent to removed/transparent
        despilled = 0
        # pass 1: remove strong magenta
        for y in range(h):
            for x in range(w):
                i = y * w + x
                r, g, b, a = px[x, y]
                if a == 0:
                    continue
                if relaxed_magenta(r, g, b):
                    px[x, y] = (r, g, b, 0)
                    removed.append(i)
        rem_set = set(removed)
        # pass 2: recolour borderline px near removals/transparency
        for y in range(h):
            for x in range(w):
                i = y * w + x
                r, g, b, a = px[x, y]
                if a == 0 or i in rem_set or not borderline_magenta(r, g, b):
                    continue
                touch = False
                for dy in (-2, -1, 0, 1, 2):
                    for dx in (-2, -1, 0, 1, 2):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < w and 0 <= ny < h:
                            j = ny * w + nx
                            if j in rem_set or px[nx, ny][3] < 128:
                                touch = True
                                break
                    else:
                        continue
                    break
                if not touch:
                    continue
                sr = sg = sb = sn = 0
                for dy in (-2, -1, 0, 1, 2):
                    for dx in (-2, -1, 0, 1, 2):
                        nx, ny = x + dx, y + dy
                        if 0 <= nx < w and 0 <= ny < h:
                            j = ny * w + nx
                            if j not in rem_set and px[nx, ny][3] > 200 \
                                    and not relaxed_magenta(*px[nx, ny][:3]) \
                                    and not borderline_magenta(
                                        *px[nx, ny][:3]):
                                pr, pg, pb, _ = px[nx, ny]
                                sr += pr; sg += pg; sb += pb; sn += 1
                if sn >= 3:
                    px[x, y] = (sr // sn, sg // sn, sb // sn, a)
                    despilled += 1
        removed.extend([None] * despilled)  # count despill in total

    tag = "DRY" if dry else "FIXED"
    print(f"[{tag}] {path.name}: removed={len(removed)}px "
          f"({'pink-tier flood-strict' if is_pink else 'global relaxed + despill'})",
          flush=True)
    if removed and not dry:
        if not (BACKUP / path.name).exists():
            import shutil
            BACKUP.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, BACKUP / path.name)
        im.save(path, "PNG")
    return len(removed)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()
    files = sorted(p for p in OUT_DIR.glob("tier_bar_env_t*.png")
                   if not p.name.endswith("~"))
    print(f"{len(files)} env layers")
    total = 0
    for p in files:
        total += clean(p, args.dry_run)
    print(f"\nSummary: files={len(files)} px_changed={total}")


if __name__ == "__main__":
    main()
