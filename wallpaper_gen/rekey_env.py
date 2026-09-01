#!/usr/bin/env python3
"""Re-key the surroundings strips correctly.

The model filled the negative space with its own uniform pink (~248,7,187)
regardless of tier, so: sample the actual corner colour and flood-fill from
the edges through pixels within tolerance of it. For the red/pink tiers the
art itself is too close to that colour, so those are rebuilt by hue-shifting
the keyed GREEN surroundings (green range never collides with the pink bg)."""
import colorsys
from collections import deque
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
RAW = BASE / "raw"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
TIER_MAP = [0, 1, 2, 3, 4, 5, 6, 0, 1, 2, 3, 4, 5]
GREEN_LO, GREEN_HI = 70.0, 170.0
TARGET_HUE = {0: 355.0, 4: 320.0}  # palette idx -> hue degrees


def sample_bg(im):
    px = im.load()
    pts = [(x, y) for x in (10, 60, 200) for y in (5, 30)]
    rs = sorted(px[x, y][0] for x, y in pts)[len(pts)//2]
    gs = sorted(px[x, y][1] for x, y in pts)[len(pts)//2]
    bs = sorted(px[x, y][2] for x, y in pts)[len(pts)//2]
    return rs, gs, bs


def key(im, tol=80):
    w, h = im.size
    px = im.load()
    br, bg_, bb = sample_bg(im)
    sq = tol * tol

    def is_bg(x, y):
        r, g, b = px[x, y][:3]
        return (r-br)**2 + (g-bg_)**2 + (b-bb)**2 <= sq

    void = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_bg(x, y):
                void[y*w+x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_bg(x, y):
                void[y*w+x] = 1
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny*w + nx
                if not void[i] and is_bg(nx, ny):
                    void[i] = 1
                    q.append((nx, ny))
    out = Image.new("RGBA", (w, h))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b = px[x, y][:3]
            opx[x, y] = (r, g, b, 0 if void[y*w+x] else 255)
    return out


def hue_shift(im, target_deg):
    w, h = im.size
    px, out = im.load(), Image.new("RGBA", (w, h))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, a = px[x, y]
            hh, s, v = colorsys.rgb_to_hsv(r/255, g/255, b/255)
            if GREEN_LO <= hh*360 <= GREEN_HI and s > 0.15:
                nr, ng, nb = colorsys.hsv_to_rgb(target_deg/360, min(1, s*1.1), v)
                opx[x, y] = (int(nr*255), int(ng*255), int(nb*255), a)
            else:
                opx[x, y] = (r, g, b, a)
    return out


def crop_4to1(im):
    # BOTTOM-anchored crop: the silhouette hugs the bottom edge of the raw
    # image, so a centre crop would slice it off.
    w, h = im.size
    th = w // 4
    if th <= h:
        im = im.crop((0, h - th, w, h))
    return im.resize((2048, 512), Image.LANCZOS)


def main():
    keyed = {}
    for idx, name in enumerate(["red", "orange", "green", "blue",
                                "pink", "yellow", "glass"]):
        raw = Image.open(RAW / f"env_{name}.png").convert("RGB")
        k = key(crop_4to1(raw))
        # opaque fraction as sanity
        w, h = k.size
        px = k.load()
        op = sum(1 for y in range(0, h, 4) for x in range(0, w, 4)
                 if px[x, y][3] == 255)
        frac = op / ((w*h)//16)
        print(f"[env {name}] keyed opaque={frac:.2f}", flush=True)
        keyed[idx] = (k, frac)

    # red(0)/pink(4) built from green(2)
    for idx in (0, 4):
        if keyed[idx][1] < 0.10:  # art got eaten -> rebuild from green
            k = hue_shift(keyed[2][0], TARGET_HUE[idx])
            w, h = k.size
            px = k.load()
            op = sum(1 for y in range(0, h, 4) for x in range(0, w, 4)
                     if px[x, y][3] == 255)
            keyed[idx] = (k, op / ((w*h)//16))
            print(f"[env {'red' if idx==0 else 'pink'}] rebuilt from green "
                  f"opaque={keyed[idx][1]:.2f}", flush=True)

    for tier in range(13):
        keyed[TIER_MAP[tier]][0].save(
            OUT_DIR / f"tier_bar_env_t{tier}.png", "PNG")
    print("all 13 env strips written")


if __name__ == "__main__":
    main()
