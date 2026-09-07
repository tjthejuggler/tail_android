#!/usr/bin/env python3
"""One-off: rebase lizard_pose_t12_p07 (solar_garden) onto the real dummy row.

The APPROVED old raw drew a 3x2 grid of squares with the lizard standing on
the UPPER row, while the pose def declares one bottom dummy row
[(2,0),(2,1)]. Every faithful transplant inherits that layout, so verify()'s
floating check (lizard must touch the LOWEST dummy row's top face) rejects
it. Text LAYOUT OVERRIDE clauses cannot beat the visual evidence of image 1.

Deterministic fix, NO API calls: take the cached transplanted raw (new
lizard), hue-key out the cyan squares + magenta background, crop the opaque
lizard+props layer, and re-composite it on a fresh magenta canvas with the
two manifest dummy squares drawn EXACTLY on their cell rects, the layer's
bottom contact line shifted onto the square top face (+10px toe grip).
Then transplant_poses.py --tier 12 --only 7 --reprocess runs the normal
postprocess + verify for free.
"""
import colorsys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

BASE = Path(__file__).resolve().parent
RAW = BASE / "raw" / "poses" / "raw_t12_p07.png"

PX = 512
W, H = 2 * PX, 3 * PX            # canvas: 2 cols x 3 rows
DUMMY_TOP = 2 * PX               # row 2 top face
MAGENTA = (255, 0, 255)
CYAN = (0, 255, 255)


def hue_mask(im: Image.Image, key: tuple) -> Image.Image:
    hsv = im.convert("HSV")
    hpx = hsv.getchannel("H").load()
    spx = hsv.getchannel("S").load()
    vpx = hsv.getchannel("V").load()
    kh = colorsys.rgb_to_hsv(*[v / 255 for v in key])[0] * 255
    mask = Image.new("L", im.size, 0)
    mpx = mask.load()
    for y in range(im.height):
        for x in range(im.width):
            dv = abs(hpx[x, y] - kh)
            dv = min(dv, 255 - dv)
            if dv <= 26 and spx[x, y] >= 70 and vpx[x, y] >= 100:
                mpx[x, y] = 255
    return mask.filter(ImageFilter.MaxFilter(5))   # catch AA fringes


raw = Image.open(RAW).convert("RGB")
layer = raw.convert("RGBA")
layer.paste((0, 0, 0, 0), hue_mask(raw, CYAN))
layer.paste((0, 0, 0, 0), hue_mask(raw, MAGENTA))

bbox = layer.getchannel("A").getbbox()
if not bbox:
    raise SystemExit("no opaque layer left after keying")
l, t, r, b = bbox
lw, lh = r - l, b - t
print(f"layer bbox {bbox} ({lw}x{lh})")
if lw > W:
    s = (W * 0.98) / lw
    layer = layer.resize((round(lw * s), round(lh * s)), Image.LANCZOS)
    l, t, r, b = layer.getchannel("A").getbbox()
    lw, lh = r - l, b - t
    print(f"resized layer to {lw}x{lh}")

canvas = Image.new("RGB", (W, H), MAGENTA)
draw = ImageDraw.Draw(canvas)
for c in (0, 1):                                   # dummies (2,0) (2,1)
    draw.rectangle([c * PX, DUMMY_TOP, (c + 1) * PX - 1, H - 1],
                   fill=CYAN)

dx = (W - lw) // 2
dy = DUMMY_TOP - (b - t) + 10                      # bottom contact + toe grip
canvas.paste(layer.crop((l, t, r, b)), (dx, dy), layer.crop((l, t, r, b)))
canvas.save(RAW, "PNG")
print(f"rebased raw saved: bottom {DUMMY_TOP + 10}px "
      f"(was {b}px), offset ({dx},{dy})")
