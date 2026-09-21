#!/usr/bin/env python3
"""One-off: enlarge t11_p04 (origami) with headroom — pass 2 (2026-09-20).

Pass 1 (repair_lizard_placements.py SMALL) scaled origami only 1.27x: the
sitting lizard nearly fills the space above its dummy row in the 2-row
canvas, so the height cap clipped the upscale (bulk 0.34 vs band 0.45..0.65).
Fix: take the ORIGINAL final (backed up pre-repair), add one EMPTY row ABOVE
it (canvas 2x2 -> 4x3, dummy row 1 -> 2) so the scaled lizard has headroom,
then scale toward the band midpoint and lay a fresh dummy-cell run under the
scaled contact span. Same deterministic composite approach as
fix_t12_p07_rebase.py; no API calls. Manifest for tier 11 is rewritten with
the new geometry; pose_sets.py's origami def is patched to match by hand.
"""
from math import ceil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from gen_lizard_poses import (OUT_DIR, RAW_DIR, PX_PER_CELL, MANIFEST,
                              lizard_bulk_cells, verify, write_manifest)
from pose_sets import POSES_BY_TIER

INSET = round(PX_PER_CELL * 0.047)
GRIP = 12

OLD = RAW_DIR.parent / "poses_backup_20260920" / "lizard_pose_t11_p04.png"
TIER, IDX = 11, 4
NEW_COLS, NEW_ROWS = 4, 3
DUMMY_ROW = 2

p = POSES_BY_TIER[TIER][IDX]
orig = Image.open(OLD).convert("RGBA")
layer = orig.crop(orig.getchannel("A").getbbox())
lo, hi = p["bulk"]
b = lizard_bulk_cells(orig, p)
f = max(1.02, min(((lo + hi) / 2) / max(b, 0.05),
                  (DUMMY_ROW * PX_PER_CELL + GRIP - 2) / layer.height,
                  NEW_COLS * PX_PER_CELL * 0.98 / layer.width))
nw, nh = max(1, round(layer.width * f)), max(1, round(layer.height * f))
layer = layer.resize((nw, nh), Image.LANCZOS)

# contact span of the scaled layer (bottom 8%)
px = layer.load()
y0 = max(0, layer.height - max(8, round(layer.height * 0.08)))
clo, chi = None, None
for y in range(y0, layer.height):
    for x in range(0, layer.width, 2):
        if px[x, y][3] > 40:
            clo = x if clo is None else min(clo, x)
            chi = x if chi is None else max(chi, x)
span = max(chi - clo, PX_PER_CELL // 2)

W, H = NEW_COLS * PX_PER_CELL, NEW_ROWS * PX_PER_CELL
dummy_top = DUMMY_ROW * PX_PER_CELL
n_d = max(2, min(NEW_COLS, ceil((span + 80) / PX_PER_CELL)))
# layer centre over canvas centre; contact centre follows the body
bcx = W / 2 + (clo + chi) / 2 - layer.width / 2
c0 = max(0, min(NEW_COLS - n_d, round(bcx / PX_PER_CELL - n_d / 2)))
dummies = [(DUMMY_ROW, c) for c in range(c0, c0 + n_d)]

canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
nx0 = max(0, min(W - nw, round(W / 2 - nw / 2)))
ny0 = max(1, dummy_top + GRIP - nh)
canvas.alpha_composite(layer, (nx0, ny0))
draw = ImageDraw.Draw(canvas)
for (r, c) in dummies:
    x0 = c * PX_PER_CELL + INSET
    y0 = r * PX_PER_CELL + INSET
    x1 = (c + 1) * PX_PER_CELL - INSET
    y1 = (r + 1) * PX_PER_CELL - INSET
    draw.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(0, 0, 0, 0))
a = canvas.getchannel("A").filter(ImageFilter.MinFilter(3))
a = a.filter(ImageFilter.GaussianBlur(0.8))
canvas.putalpha(a)

p["cols"], p["rows"], p["dummies"] = NEW_COLS, NEW_ROWS, dummies
verify(canvas, p)
out = OUT_DIR / f"lizard_pose_t{TIER}_p{IDX:02d}.png"
canvas.save(out)
cache = RAW_DIR / f"pose_t{TIER}_p{IDX:02d}.png"
if cache.exists():
    canvas.save(cache)
write_manifest(TIER, POSES_BY_TIER[TIER])
after = lizard_bulk_cells(canvas, p)
print(f"origami: bulk {b:.2f} -> {after:.2f} (band {lo}..{hi}, f={f:.2f}, "
      f"canvas 4x3, dummies={dummies})")
print(f"PATCH pose_sets.py t{TIER} p{IDX:02d}: cols=4, "
      f"rows=3, dummies={dummies}")
