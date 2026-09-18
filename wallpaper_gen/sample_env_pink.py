#!/usr/bin/env python3
"""Sample magenta-family pixel populations in env t4/t11 (pink tiers) and
t0/t7 (strict-magenta pockets) to find a chroma-vs-art discriminator."""
from pathlib import Path

from PIL import Image

OUT = Path("app/src/main/res/drawable-nodpi")


def hist(name, pred):
    im = Image.open(OUT / name).convert("RGBA")
    px = im.load()
    w, h = im.size
    buckets = {}
    for y in range(0, h, 2):
        for x in range(0, w, 2):
            r, g, b, a = px[x, y]
            if a < 128:
                continue
            if r > 140 and b > 100 and g < 0.75 * min(r, b):
                key = (r // 24 * 24, g // 24 * 24, b // 24 * 24)
                buckets[key] = buckets.get(key, 0) + 1
    print(f"\n{name}: magenta-family buckets (quantised 24)")
    for k, v in sorted(buckets.items(), key=lambda kv: -kv[1])[:14]:
        print(f"   RGB~{k}: {v}")


for f in ["tier_bar_env_t4.png", "tier_bar_env_t11.png",
          "tier_bar_env_t0.png", "tier_bar_env_t7.png",
          "tier_bar_env_t10.png"]:
    hist(f, None)
