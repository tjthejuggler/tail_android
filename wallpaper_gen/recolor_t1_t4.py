#!/usr/bin/env python3
"""Deterministically rebuild tiers 1 (orange) and 4 (pink) from the good
t2 (green) strip via HSV hue rotation, preserving the existing alpha matte.
The edit model kept returning a smaller, differently-posed animal for these
two tiers, so we no longer rely on it for single-colour variants."""
import colorsys
from pathlib import Path

from PIL import Image

BASE = Path(__file__).resolve().parent
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
SRC = OUT_DIR / "tier_bar_lizard_t2.png"

# tier -> target hue in degrees
TARGETS = {1: 30.0, 4: 330.0}  # orange, pink/magenta

GREEN_LO, GREEN_HI = 70.0, 170.0  # degrees, matches recolor_lizard_combos.py


def shift_hue(px, target_hue_deg):
    r, g, b = px[:3]
    h, s, v = colorsys.rgb_to_hsv(r / 255.0, g / 255.0, b / 255.0)
    hue = h * 360.0
    if GREEN_LO <= hue <= GREEN_HI and s > 0.18:
        nh = target_hue_deg / 360.0
        nr, ng, nb = colorsys.hsv_to_rgb(nh, min(1.0, s * 1.15), v)
        return (int(nr * 255), int(ng * 255), int(nb * 255), px[3])
    return px


def main():
    src = Image.open(SRC).convert("RGBA")
    w, h = src.size
    for tier, hue in TARGETS.items():
        out = Image.new("RGBA", (w, h))
        spx, opx = src.load(), out.load()
        for y in range(h):
            for x in range(w):
                opx[x, y] = shift_hue(spx[x, y], hue)
        dest = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
        out.save(dest, "PNG")
        print(f"[t{tier}] hue-shifted to {hue:.0f}deg -> {dest.name}")


if __name__ == "__main__":
    main()
