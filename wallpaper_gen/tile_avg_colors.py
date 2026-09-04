#!/usr/bin/env python3
"""Average chromatic colour of each habit tile image.

Ignores near-grey and near-black pixels (low HSV saturation / value) so the
average reflects the tile's actual hue rather than its dark glass background.

Usage: python3 tile_avg_colors.py [--dir raw_habit_tiles] [--sat 0.25] [--val 0.15]
"""
import argparse
import colorsys
import json
import os

from PIL import Image

TILES = ["red", "orange", "green", "blue", "yellow", "pink", "glass"]


def chromatic_average(path: str, sat_min: float, val_min: float) -> tuple[int, int, int] | None:
    im = Image.open(path).convert("RGBA")
    im.thumbnail((256, 256))
    r_sum = g_sum = b_sum = n = 0
    for r, g, b, a in im.getdata():
        if a < 128:
            continue
        h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if s < sat_min or v < val_min:
            continue  # grey or black — skip
        r_sum += r
        g_sum += g
        b_sum += b
        n += 1
    if n == 0:
        return None
    return r_sum // n, g_sum // n, b_sum // n


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=os.path.join(os.path.dirname(__file__), "raw_habit_tiles"))
    ap.add_argument("--sat", type=float, default=0.25, help="min HSV saturation (0-1)")
    ap.add_argument("--val", type=float, default=0.15, help="min HSV value (0-1)")
    args = ap.parse_args()

    results = {}
    for name in TILES:
        path = os.path.join(args.dir, f"{name}.png")
        if not os.path.exists(path):
            print(f"{name}: MISSING")
            continue
        avg = chromatic_average(path, args.sat, args.val)
        if avg is None:
            print(f"{name}: no chromatic pixels")
            continue
        hexc = "#%02X%02X%02X" % avg
        results[name] = "0xFF%02X%02X%02X" % avg
        print(f"{name}: {hexc}  (Compose: 0xFF{hexc[1:]})")

    print("\nJSON:", json.dumps(results))


if __name__ == "__main__":
    main()
