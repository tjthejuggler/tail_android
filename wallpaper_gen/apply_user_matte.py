#!/usr/bin/env python3
"""Apply the USER's manual background-removal recipe to all tier strips.

Their t6 (tier_bar_lizard_t6_bg_removed.png) reveals the recipe: binary
flood fill from the image edges through PURE-BLACK pixels (max channel < 8)
-> alpha 0. Everything else stays fully opaque (alpha 255) — no dilation,
no feather, so the dim glow around the animal is preserved.

t6 itself is replaced by the user's file; the other 12 strips get the same
alpha recomputed from their (unchanged) RGB data."""
from collections import deque
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parent.parent / "app/src/main/res/drawable-nodpi"
USER_T6 = OUT / "tier_bar_lizard_t6_bg_removed.png"


def user_alpha(rgb_im):
    w, h = rgb_im.size
    px = rgb_im.load()
    void = bytearray(w * h)

    def is_bg(x, y):
        r, g, b = px[x, y]
        return max(r, g, b) < 8

    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_bg(x, y):
                void[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_bg(x, y):
                void[y * w + x] = 1
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for nx, ny in ((x-1, y), (x+1, y), (x, y-1), (x, y+1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not void[i] and is_bg(nx, ny):
                    void[i] = 1
                    q.append((nx, ny))
    return void


def apply(tier):
    src = OUT / f"tier_bar_lizard_t{tier}.png"
    im = Image.open(src).convert("RGBA")
    w, h = im.size
    px = im.load()
    void = user_alpha(im.convert("RGB"))
    out = Image.new("RGBA", (w, h))
    opx = out.load()
    for y in range(h):
        for x in range(w):
            r, g, b, _ = px[x, y]
            opx[x, y] = (r, g, b, 0 if void[y * w + x] else 255)
    out.save(src, "PNG")


def main():
    # t6: adopt the user's file verbatim
    Image.open(USER_T6).convert("RGBA").save(OUT / "tier_bar_lizard_t6.png", "PNG")
    print("[t6] adopted user's manual bg-removed file")
    for tier in range(13):
        if tier == 6:
            continue
        apply(tier)
        print(f"[t{tier}] user-matte alpha applied", flush=True)


if __name__ == "__main__":
    main()
