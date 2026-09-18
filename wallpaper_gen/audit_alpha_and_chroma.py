#!/usr/bin/env python3
"""Audit widget art for two defect classes:

1. Lizard strips (tier_bar_lizard_t*.png, tier_bar_lizard_m*_p*.png):
   transparent / semi-transparent regions that are NOT connected to the
   image border = interior "holes" (keyed-out glow, eroded dark metal).
   Reports each enclosed hole: area, share of opaque body, mean RGB and a
   guess whether it is chroma-blue background or creature colour.

2. Sky / env layers (tier_bar_sky_t*.png, tier_bar_env_t*.png):
   leftover magenta/pink chroma or pink-ish patches (magenta family hues),
   plus alpha stats for env.

Run:  python3 wallpaper_gen/audit_alpha_and_chroma.py
"""
import colorsys
from collections import deque
from pathlib import Path

from PIL import Image

OUT_DIR = Path(__file__).resolve().parent.parent / \
    "app/src/main/res/drawable-nodpi"


def is_chroma_blue(r, g, b):
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    return 200 <= h * 360 <= 260 and s >= 0.45 and v >= 0.55


def components(mask, w, h):
    """Yield connected components (4-neighbour) of True cells."""
    seen = bytearray(w * h)
    for start in range(w * h):
        if not mask[start] or seen[start]:
            continue
        comp = []
        dq = deque([start])
        seen[start] = 1
        while dq:
            i = dq.popleft()
            comp.append(i)
            x, y = i % w, i // w
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                if 0 <= nx < w and 0 <= ny < h:
                    j = ny * w + nx
                    if mask[j] and not seen[j]:
                        seen[j] = 1
                        dq.append(j)
        yield comp


def audit_lizard(path: Path):
    im = Image.open(path).convert("RGBA")
    w, h = im.size
    px = im.load()
    trans = bytearray(w * h)   # alpha < 250
    semi = 0
    for y in range(h):
        for x in range(w):
            a = px[x, y][3]
            if a < 250:
                trans[y * w + x] = 1
                if 0 < a:
                    semi += 1

    outside = bytearray(w * h)
    dq = deque()
    for x in range(w):
        for y in (0, h - 1):
            i = y * w + x
            if trans[i] and not outside[i]:
                outside[i] = 1
                dq.append(i)
    for y in range(h):
        for x in (0, w - 1):
            i = y * w + x
            if trans[i] and not outside[i]:
                outside[i] = 1
                dq.append(i)
    while dq:
        i = dq.popleft()
        x, y = i % w, i // w
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h:
                j = ny * w + nx
                if trans[j] and not outside[j]:
                    outside[j] = 1
                    dq.append(j)

    hole_mask = bytearray(w * h)
    n_hole = 0
    for i in range(w * h):
        if trans[i] and not outside[i]:
            hole_mask[i] = 1
            n_hole += 1

    opaque = sum(1 for y in range(h) for x in range(w)
                 if px[x, y][3] >= 250)
    holes = sorted(components(hole_mask, w, h), key=len, reverse=True)
    print(f"\n== {path.name}  {w}x{h}")
    print(f"   opaque={opaque}  outside_transparent={sum(outside)}  "
          f"interior_hole_px={n_hole}  interior_semi_px={semi}")
    for comp in holes[:8]:
        rs = gs = bs = 0
        blueish = 0
        for i in comp:
            x, y = i % w, i // w
            r, g, b, a = px[x, y]
            rs += r; gs += g; bs += b
            if is_chroma_blue(r, g, b):
                blueish += 1
        n = len(comp)
        mr, mg, mb = rs // n, gs // n, bs // n
        kind = ("CHROMA-BLUE" if blueish > n * 0.5 else
                "dark/black" if max(mr, mg, mb) < 40 else "creature-colour")
        print(f"   hole: area={n} ({n / max(1, opaque) * 100:.2f}% of body) "
              f"meanRGB=({mr},{mg},{mb}) blueish={blueish * 100 // n}% -> {kind}")


def audit_background(path: Path):
    im = Image.open(path)
    w, h = im.size
    has_alpha = im.mode == "RGBA"
    px = im.load()
    magenta = pinkish = transparent = semi = 0
    magenta_rows = {}
    for y in range(h):
        for x in range(w):
            p = px[x, y]
            r, g, b = p[0], p[1], p[2]
            if has_alpha:
                if p[3] == 0:
                    transparent += 1
                    continue
                if p[3] < 250:
                    semi += 1
            hh, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            deg = hh * 360
            if r > 170 and b > 170 and g < 90 and abs(r - b) < 70:
                magenta += 1
                magenta_rows[y] = magenta_rows.get(y, 0) + 1
            elif 270 <= deg < 340 and s > 0.30 and v > 0.45:
                pinkish += 1
    tot = w * h
    print(f"\n== {path.name}  {w}x{h} mode={im.mode}")
    print(f"   magenta_px={magenta} ({magenta / tot * 100:.2f}%)  "
          f"pinkish_px={pinkish} ({pinkish / tot * 100:.2f}%)  "
          f"transparent={transparent} semi={semi}")
    if magenta_rows:
        rows = sorted(magenta_rows.items(), key=lambda kv: -kv[1])[:5]
        for y, c in rows:
            print(f"   magenta row y={y}: {c}px")


def main():
    lizards = sorted(OUT_DIR.glob("tier_bar_lizard_t*.png")) + \
        sorted(OUT_DIR.glob("tier_bar_lizard_m*.png"))
    lizards = [p for p in lizards if not p.name.endswith("~")]
    bgs = sorted(OUT_DIR.glob("tier_bar_sky_t*.png")) + \
        sorted(OUT_DIR.glob("tier_bar_env_t*.png"))
    bgs = [p for p in bgs if not p.name.endswith("~")]
    print(f"lizard strips: {len(lizards)}   backgrounds: {len(bgs)}")
    for p in lizards:
        audit_lizard(p)
    for p in bgs:
        audit_background(p)


if __name__ == "__main__":
    main()
