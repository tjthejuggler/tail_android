#!/usr/bin/env python3
"""Programmatic two-tone recolor for the white+colour combo lizard tiers (6-12).

The ppq edit model consolidates two glow colours into one, so the white-combo
variants are produced deterministically instead: take the green-primary /
orange-secondary source, and hue-replace
  green glow  -> white (desaturated, luminance kept)
  orange glow -> the tier's combo colour
Tier 6 (white/white) sends BOTH to white.

Outputs overwrite app/src/main/res/drawable-nodpi/tier_bar_lizard_t{6..12}.png
as 4:1 strips (same geometry as gen_lizard_tiers.py's to_strip()).
"""
import colorsys
import sys
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
SRC = Path("/home/twain/Downloads/full_lizard_primary_green_secondary_orange.png")
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"

# tier -> (combo rgb) ; tier 6 = None meaning everything -> white
COMBOS = {
    6:  None,                # white / white
    7:  (204, 51, 51),       # red
    8:  (224, 112, 32),      # orange
    9:  (51, 170, 85),       # green
    10: (51, 102, 221),      # blue
    11: (221, 68, 170),      # pink
    12: (221, 204, 0),       # yellow
}

# Hue windows (degrees) for the two source accent colours.
GREEN_LO, GREEN_HI = 70, 170
ORANGE_LO, ORANGE_HI = 5, 55
SAT_MIN = 0.25   # below this the pixel is silver/gray metal — leave alone
VAL_MIN = 0.15   # below this it's background/panel lines — leave alone


def animal_bbox(im):
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
    return (minx, miny, maxx + 1, maxy + 1) if maxx >= 0 else None


def to_strip(im):
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
    return canvas


def recolor(im, combo_rgb):
    """Hue-replace green -> white, orange -> combo_rgb (in place on a copy)."""
    src = im.copy()
    out = Image.new("RGB", src.size)
    spx, opx = src.load(), out.load()
    w, h = src.size
    cr, cg, cb = combo_rgb if combo_rgb else (255, 255, 255)
    ch, cs, cv = colorsys.rgb_to_hsv(cr / 255, cg / 255, cb / 255)
    for y in range(h):
        for x in range(w):
            r, g, b = spx[x, y]
            hh, ss, vv = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if vv > VAL_MIN and ss > SAT_MIN:
                deg = hh * 360.0
                if GREEN_LO <= deg <= GREEN_HI:
                    # green -> white: keep brightness, drop saturation
                    nr, ng, nb = colorsys.hsv_to_rgb(0.0, 0.0, vv)
                    opx[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255))
                    continue
                if ORANGE_LO <= deg <= ORANGE_HI and combo_rgb is not None:
                    # orange -> combo colour, keep brightness/saturation shape
                    ns = min(ss, cs if cs > 0 else 1.0)
                    nv = vv if cs > 0 else 1.0
                    if combo_rgb == (255, 255, 255):
                        ns = 0.0
                    nr, ng, nb = colorsys.hsv_to_rgb(ch, ns, nv)
                    opx[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255))
                    continue
                if ORANGE_LO <= deg <= ORANGE_HI and combo_rgb is None:
                    # tier 6: orange -> white too
                    nr, ng, nb = colorsys.hsv_to_rgb(0.0, 0.0, vv)
                    opx[x, y] = (round(nr * 255), round(ng * 255), round(nb * 255))
                    continue
            opx[x, y] = (r, g, b)
    return out


def main():
    base = to_strip(Image.open(SRC).convert("RGB"))
    base.save(BASE / "raw" / "lizard_src_go_strip.png")
    for tier, combo in COMBOS.items():
        out = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
        recolor(base, combo).save(out, "PNG")
        print(f"[t{tier:02d}] saved {out.name} {out.stat().st_size} bytes",
              flush=True)


if __name__ == "__main__":
    main()
