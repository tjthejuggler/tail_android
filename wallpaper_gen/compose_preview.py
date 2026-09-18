#!/usr/bin/env python3
"""Compose the widget scene the same way TierBarWidgetProvider.buildBackground
does (sky full-frame, env bottom-anchored, lizard right-anchored) for visual
verification. Outputs PNGs to /tmp for several tier combos."""
from pathlib import Path

from PIL import Image

OUT = Path("app/src/main/res/drawable-nodpi")

W, H = 2048, 512


def compose(month_t, week_t, day_t, day_points, out_name):
    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 255))
    sky = Image.open(OUT / f"tier_bar_sky_t{month_t}.png").convert("RGBA")
    canvas.alpha_composite(sky.resize((W, H), Image.LANCZOS))
    env = Image.open(OUT / f"tier_bar_env_t{week_t}.png").convert("RGBA")
    canvas.alpha_composite(env.resize((W, H), Image.LANCZOS))
    lizard = Image.open(
        OUT / f"tier_bar_lizard_m{day_t}_p{day_points:02d}.png") \
        .convert("RGBA")
    lw = lizard.width
    canvas.alpha_composite(lizard, (W - lw, (H - lizard.height) // 2))
    canvas.convert("RGB").save(f"/tmp/{out_name}", "PNG")
    print(f"/tmp/{out_name}")


# the user's case: blue month tier (t3), blue week tier (t10)? check pink
compose(3, 10, 3, 34, "compose_blue_blue.png")     # month=blue week=blue
compose(3, 4, 3, 34, "compose_blue_pinkweek.png")  # month=blue week=pink
compose(4, 4, 4, 43, "compose_pink_pink.png")      # all pink
compose(0, 7, 0, 5, "compose_red_red.png")         # red month+week
