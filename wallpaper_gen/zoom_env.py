#!/usr/bin/env python3
"""Zoom into magenta-contaminated regions of env layers; also dump colour
samples to calibrate the chroma-vs-art test for the pink tier."""
from pathlib import Path

from PIL import Image

OUT = Path("app/src/main/res/drawable-nodpi")


def zoom(name, box, out):
    im = Image.open(OUT / name).convert("RGBA")
    bg = Image.new("RGBA", im.size, (60, 60, 60, 255))
    bg.alpha_composite(im)
    crop = bg.convert("RGB").crop(box)
    crop = crop.resize((crop.width * 2, crop.height * 2), Image.NEAREST)
    crop.save(out)
    print(out, crop.size)


zoom("tier_bar_env_t10.png", (0, 150, 700, 340), "/tmp/env_t10_left.png")
zoom("tier_bar_env_t10.png", (1450, 150, 2048, 340), "/tmp/env_t10_right.png")
zoom("tier_bar_env_t4.png", (0, 300, 700, 512), "/tmp/env_t4_bottom.png")
zoom("tier_bar_env_t4.png", (1200, 350, 2048, 512), "/tmp/env_t4_br.png")

# colour samples in the t4 magenta rows 410-420
im = Image.open(OUT / "tier_bar_env_t4.png").convert("RGBA")
px = im.load()
samples = []
for y in range(405, 425):
    for x in range(0, 2048, 4):
        r, g, b, a = px[x, y]
        if r > 170 and b > 170 and g < 90 and abs(r - b) < 70:
            samples.append((x, y, (r, g, b)))
print(f"strict magenta samples in t4 rows 405-425: {len(samples)}")
for s in samples[:20]:
    print(s)
