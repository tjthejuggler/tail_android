#!/usr/bin/env python3
"""Re-process the fixed tier-1 lizard strip.

The fixed t1 art (1949x688, solid black background, tail no longer cut
off) sits unprocessed in drawable-nodpi. This script runs it through the
same pipeline as gen_lizard_tiers.py / fix_lizard_strips.py, but scales
the animal to a target width of 900px — quite a bit larger than t0
(~590px) and smaller than t2 (~1260px) — then right-anchors and
vertically centers it on the standard 2048x512 canvas and applies real
alpha.
"""
import sys
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
OUT = BASE.parent / "app/src/main/res/drawable-nodpi/tier_bar_lizard_t1.png"
RAW = BASE / "raw/lizard_tier1_fixed.png"
TARGET_W = 900


def bbox(im):
    w, h = im.size
    px = im.load()
    minx, miny, maxx, maxy = w, h, -1, -1
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y][:3]
            if r > 24 or g > 24 or b > 24:
                if x < minx: minx = x
                if x > maxx: maxx = x
                if y < miny: miny = y
                if y > maxy: maxy = y
    if maxx < 0:
        return None
    return (minx, miny, maxx + 1, maxy + 1)


def apply_alpha(im):
    """Same flood-fill transparency as fix_lizard_strips.apply_alpha."""
    im = im.convert("RGB")
    w, h = im.size
    px = im.load()
    void = bytearray(w * h)

    def is_bg(x, y):
        r, g, b = px[x, y]
        return max(r, g, b) < 40

    q = []
    for x in range(w):
        for y in (0, h - 1):
            if is_bg(x, y) and not void[y * w + x]:
                void[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_bg(x, y) and not void[y * w + x]:
                void[y * w + x] = 1
                q.append((x, y))
    head = 0
    while head < len(q):
        x, y = q[head]; head += 1
        for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not void[i] and is_bg(nx, ny):
                    void[i] = 1
                    q.append((nx, ny))

    for _ in range(3):
        to_clear = []
        for y in range(h):
            for x in range(w):
                i = y * w + x
                if not void[i]:
                    continue
                for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
                    if 0 <= nx < w and 0 <= ny < h and not void[ny * w + nx]:
                        to_clear.append(i)
                        break
        if not to_clear:
            break
        for i in to_clear:
            void[i] = 0

    out = Image.new("RGBA", (w, h))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y]
            if void[y * w + x]:
                opx[x, y] = (r, g, b, 0)
            else:
                m = max(r, g, b)
                a = 255 if m >= 64 else int(m * 255 / 64)
                opx[x, y] = (r, g, b, a)
    return out


def alpha_bbox(im):
    """Bounding box of the animal using the source's own alpha channel."""
    a = im.split()[3]
    return a.point(lambda v: 255 if v > 8 else 0).getbbox()


def main():
    # The fixed art already ships with real alpha: solid body, transparent
    # background. Use that alpha directly — no flood-fill, no brightness
    # feather (which made dark body plates semi-transparent).
    src = Image.open(RAW).convert("RGBA")

    bb = alpha_bbox(src)
    print("fixed t1 canvas:", src.size, "alpha bbox:", bb)
    if bb is None:
        sys.exit("no animal found")
    animal = src.crop(bb)
    aw, ah = animal.size
    scale = TARGET_W / aw
    aw2, ah2 = round(aw * scale), round(ah * scale)
    if ah2 > 512:
        scale = 512 / ah
        aw2, ah2 = round(aw * scale), 512
    animal = animal.resize((aw2, ah2), Image.LANCZOS)

    W, H = 2048, 512
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    canvas.paste(animal, (W - aw2, (H - ah2) // 2), animal)
    canvas.save(OUT, "PNG")

    # Sanity: any fully-opaque-body pixels in the source must stay opaque.
    solid = sum(1 for v in animal.split()[3].getdata() if v == 255)
    total = aw2 * ah2
    print(f"saved {OUT} animal {aw2}x{ah2} at x={W-aw2}..{W}, "
          f"y={(H-ah2)//2}..{(H-ah2)//2+ah2}; "
          f"opaque px in scaled animal: {solid}/{total}")


if __name__ == "__main__":
    main()
