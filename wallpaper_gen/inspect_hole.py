#!/usr/bin/env python3
"""Visualise interior-hole anatomy of one morph strip: RGB crop, alpha crop,
hole overlay. Saves side-by-side PNGs to /tmp for inspection."""
from collections import deque
from pathlib import Path

from PIL import Image

P = Path("app/src/main/res/drawable-nodpi/tier_bar_lizard_m3_p34.png")
im = Image.open(P).convert("RGBA")
w, h = im.size
px = im.load()

trans = bytearray(w * h)
for i in range(w * h):
    if px[i % w, i // w][3] < 250:
        trans[i] = 1

outside = bytearray(w * h)
dq = deque()
for x in range(w):
    for y in (0, h - 1):
        i = y * w + x
        if trans[i]:
            outside[i] = 1
            dq.append(i)
for y in range(h):
    for x in (0, w - 1):
        i = y * w + x
        if trans[i]:
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

# biggest interior hole bbox
seen = bytearray(w * h)
best = []
for start in range(w * h):
    if trans[start] and not outside[start] and not seen[start]:
        comp = []
        dq2 = deque([start])
        seen[start] = 1
        while dq2:
            i = dq2.popleft()
            comp.append(i)
            x, y = i % w, i // w
            for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
                j = ny * w + nx
                if 0 <= j < w * h and trans[j] and not outside[j] \
                        and not seen[j]:
                    seen[j] = 1
                    dq2.append(j)
        if len(comp) > len(best):
            best = comp
xs = [i % w for i in best]
ys = [i // w for i in best]
x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
print(f"largest interior hole: {len(best)}px bbox=({x0},{y0},{x1},{y1})")
pad = 60
box = (max(0, x0 - pad), max(0, y0 - pad), min(w, x1 + pad), min(h, y1 + pad))
crop = im.crop(box)
cx0, cy0 = box[0], box[1]

rgb = crop.convert("RGB")
alpha = crop.getchannel("A").point(lambda v: 255 - v)  # invert: holes white
overlay = rgb.copy()
opx = overlay.load()
cpx = crop.load()
for y in range(crop.height):
    for x in range(crop.width):
        r, g, b, a = cpx[x, y]
        if a < 250:
            opx[x, y] = (255, 0, 0) if a == 0 else (255, 165, 0)

W = crop.width
sheet = Image.new("RGB", (W * 3 + 20, crop.height), (40, 40, 40))
sheet.paste(rgb, (0, 0))
sheet.paste(alpha.convert("RGB"), (W + 10, 0))
sheet.paste(overlay, (2 * W + 20, 0))
sheet.save("/tmp/hole_anatomy_m3_p34.png")
print("saved /tmp/hole_anatomy_m3_p34.png")

# stats for that hole: RGB under it
rs = [px[i % w, i // w] for i in best[:5000]]
mr = tuple(sum(c[k] for c in rs) // len(rs) for k in range(4))
print(f"hole mean RGBA = {mr}")
