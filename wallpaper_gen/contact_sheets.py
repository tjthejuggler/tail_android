#!/usr/bin/env python3
"""Build small contact sheets: all env layers stacked, all sky layers stacked."""
from pathlib import Path

from PIL import Image

OUT = Path("app/src/main/res/drawable-nodpi")


def sheet(prefix, out_path):
    files = sorted(OUT.glob(f"{prefix}*.png"))
    files = [p for p in files if not p.name.endswith("~")]
    th_w = 1024
    th_h = 256
    rows = len(files)
    canvas = Image.new("RGB", (th_w, rows * (th_h + 4)), (25, 25, 25))
    y = 0
    for p in files:
        im = Image.open(p).convert("RGBA")
        im.thumbnail((th_w, th_h), Image.LANCZOS)
        bg = Image.new("RGBA", im.size, (60, 60, 60, 255))  # grey shows alpha
        bg.alpha_composite(im)
        canvas.paste(bg.convert("RGB"), (0, y))
        y += th_h + 4
    canvas.save(out_path)
    print(f"{out_path}: {rows} rows")


sheet("tier_bar_env_t", "/tmp/env_sheet.png")
sheet("tier_bar_sky_t", "/tmp/sky_sheet.png")
