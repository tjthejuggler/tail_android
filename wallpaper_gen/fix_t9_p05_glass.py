#!/usr/bin/env python3
"""One-off: neutralize magenta-tinted glass in lizard_pose_t9_p05.png.

The terrarium pose systematically picks up the reference canvas's magenta
background INSIDE the glass jar (the model tints transparent glass with it).
Tier 9's palette is silver + green only — pink/magenta appears nowhere in
the identity — so re-huing magenta-band pixels to neutral gray (keeping
luminance) is safe and deterministic. Operates on the ALREADY-KEYED final
(preserves alpha).
"""
import colorsys
from pathlib import Path

from PIL import Image

P = Path(__file__).resolve().parent.parent / \
    "app/src/main/res/drawable-nodpi/lizard_pose_t9_p05.png"

im = Image.open(P).convert("RGBA")
px = im.load()
w, h = im.size
changed = 0
for y in range(h):
    for x in range(w):
        r, g, b, a = px[x, y]
        if a == 0:
            continue
        hh, ss, vv = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        deg = hh * 360
        # magenta/pink band incl. pale glass tint; skip near-white highlights
        if 265 <= deg <= 345 and ss > 0.10 and vv > 0.25:
            m = round(255 * vv)
            px[x, y] = (m, m, m, a)
            changed += 1
im.save(P, "PNG")
print(f"neutralized {changed} magenta-tinted pixels")
