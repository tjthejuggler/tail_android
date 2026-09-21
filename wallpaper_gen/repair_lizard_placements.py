#!/usr/bin/env python3
"""Deterministic placement repair for the 2026-09-20 audit findings.

Two defect classes leaked into drawable-nodpi because verify() only WARNS:

  FLOAT — grounded pose whose lowest pixel hangs >35% of a cell above the
          dummy row's top face (t2_p06, t4_p06, t7_p02, t8_p06). Fix:
          translate the whole opaque layer DOWN so its contact line lands
          GRIP px into the band above the square's visible face (same
          toe-grip precedent as fix_t12_p07_rebase.py), then re-erase the
          visible-face rects. ALREADY APPLIED 2026-09-20 — do not re-run
          (the shift is not idempotent); kept here for the record.

  SMALL — measured vertical bulk below lo*0.85 of the pose's (raised) bulk
          band (t5_p03, t6_p10, t7_p06, t8_p02, t9_p03, t10_p04, t11_p01,
          t11_p03, t11_p04, t11_p09). Fix: scale the lizard layer up toward
          the band midpoint and RE-LAYOUT the canvas wider (3-4 cols) —
          normalize_lizard_size() cannot do this because its horizontal
          fit-cap pins the layer to its old centre; a bigger lizard needs a
          bigger canvas (2026-09-20 policy: "3 or 4 squares width instead
          of 1 or 2"). The dummy-cell RUN under the feet is extended so
          every contact point stays on a real habit square, then the face
          rects are re-erased. The script prints each pose's new
          (cols, dummies) — pose_sets.py is patched to match by hand so
          defs stay the source of truth, then the manifest is rewritten.

No API calls; operates on FINALS only — cached raws and manual colour fixes
(e.g. t9_p05 glass) stay untouched. Pre-repair finals are backed up in
raw/poses_backup_20260920/. Re-run audit_lizard_placements.py after.
"""
from math import ceil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from gen_lizard_poses import (OUT_DIR, RAW_DIR, PX_PER_CELL, MANIFEST,
                              lizard_bulk_cells, verify, write_manifest)
from pose_sets import POSES_BY_TIER

INSET = round(PX_PER_CELL * 0.047)   # visible-square-face inset (== postprocess)
GRIP = 12                            # px of toe grip above the visible face
OVER = 1.02                          # min upscale worth doing
MAX_COLS = 4                         # 2026-09-20 policy cap (user-approved)


def lowest_opaque_row(im: Image.Image) -> int:
    px = im.load()
    for y in range(im.height - 1, -1, -1):
        for x in range(0, im.width, 2):
            if px[x, y][3] > 40:
                return y
    return -1


def re_erase_faces(im: Image.Image, dummies) -> None:
    """HARD GUARANTEE: dummy-cell face rects must stay transparent."""
    draw = ImageDraw.Draw(im)
    for (r, c) in dummies:
        x0 = c * PX_PER_CELL + INSET
        y0 = r * PX_PER_CELL + INSET
        x1 = (c + 1) * PX_PER_CELL - INSET
        y1 = (r + 1) * PX_PER_CELL - INSET
        draw.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(0, 0, 0, 0))


def fix_float(im: Image.Image, p: dict) -> Image.Image:
    """APPLIED 2026-09-20 — kept for the record; NOT idempotent."""
    bottom = lowest_opaque_row(im)
    dummy_top = max(r for (r, _) in p["dummies"]) * PX_PER_CELL
    shift = dummy_top - bottom + GRIP
    bbox = im.getchannel("A").getbbox()
    layer = im.crop(bbox)
    im.paste((0, 0, 0, 0), (0, 0, im.width, im.height))
    im.alpha_composite(layer, (bbox[0], bbox[1] + shift))
    re_erase_faces(im, p["dummies"])
    print(f"    float fix: shifted layer down {shift}px")
    return im


def contact_span(layer: Image.Image) -> tuple:
    """Opaque x-range of the layer's bottom 8% (the feet/toes)."""
    px = layer.load()
    y0 = max(0, layer.height - max(8, round(layer.height * 0.08)))
    lo, hi = None, None
    for y in range(y0, layer.height):
        for x in range(0, layer.width, 2):
            if px[x, y][3] > 40:
                lo = x if lo is None else min(lo, x)
                hi = x if hi is None else max(hi, x)
    if lo is None:                       # fallback: full layer width
        return 0, layer.width
    return lo, hi


def fix_small(im: Image.Image, p: dict):
    """Scale the lizard toward its band midpoint on a wider canvas.

    Returns (new_image, new_cols, new_dummies). The dummy row is kept;
    columns are re-chosen to cover the scaled contact span, so every foot
    lands on a real habit square in-app.
    """
    lo, hi = p["bulk"]
    b = lizard_bulk_cells(im, p)
    f = max(OVER, min(2.0, ((lo + hi) / 2) / max(b, 0.05)))
    old_cols, old_rows = p["cols"], p["rows"]
    dummy_row = max(r for (r, _) in p["dummies"])
    dummy_top = dummy_row * PX_PER_CELL
    bbox = im.getchannel("A").getbbox()
    layer = im.crop(bbox)
    cl, cr = contact_span(layer)
    span = max(cr - cl, PX_PER_CELL // 2)          # contact width, >= 0.5 cell

    # pick the smallest allowed canvas that fits body AND contact run
    chosen = None
    for cols in range(old_cols, MAX_COLS + 1):
        f_cap = min(f,
                    cols * PX_PER_CELL * 0.98 / layer.width,
                    (dummy_top + GRIP - 2) / layer.height)
        f_cap = max(1.0, f_cap)
        need_w = span * f_cap + 80                 # + margin for the run
        n_d = min(cols, max(2, ceil(need_w / PX_PER_CELL)))
        if f_cap >= f * 0.98 or cols == MAX_COLS:
            chosen = (cols, max(1.0, f_cap), n_d)
            break
    cols, f, n_d = chosen
    nw, nh = max(1, round(layer.width * f)), max(1, round(layer.height * f))
    layer = layer.resize((nw, nh), Image.LANCZOS)

    # new dummy run: cover the scaled contact span, clamped into the canvas
    W, H = cols * PX_PER_CELL, old_rows * PX_PER_CELL
    ccx = (bbox[0] + (cl + cr) / 2) * f            # scaled contact centre x
    c0 = max(0, min(cols - n_d,
                    round(ccx / PX_PER_CELL - n_d / 2)))
    dummies = [(dummy_row, c) for c in range(c0, c0 + n_d)]
    dcx = (c0 + n_d / 2) * PX_PER_CELL             # dummy-run centre

    canvas = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    nx0 = max(0, min(W - nw, round(dcx - nw / 2)))
    ny0 = max(1, dummy_top + GRIP - nh)            # bottom contact line
    canvas.alpha_composite(layer, (nx0, ny0))
    re_erase_faces(canvas, dummies)
    after = lizard_bulk_cells(canvas, {"dummies": dummies})
    print(f"    small fix: bulk {b:.2f} -> {after:.2f} "
          f"(band {lo}..{hi}, f={f:.2f}, canvas {old_cols}->{cols} cols, "
          f"dummies {dummies})")
    return canvas, cols, dummies


FLOAT = {2: [6], 4: [6], 7: [2], 8: [6]}          # applied 2026-09-20
SMALL = {5: [3], 6: [10], 7: [6], 8: [2], 9: [3],
         10: [4], 11: [1, 3, 4, 9]}
RUN = "small"          # "float" already applied; flipping this re-shifts!
NEW_LAYOUT = {}        # tier -> {pose_idx: (cols, dummies)} printed for defs


def main() -> None:
    for tier in sorted(set(FLOAT) | set(SMALL)):
        defs = POSES_BY_TIER[tier]
        dirty = False
        for i, p in enumerate(defs):
            kind = ("float" if i in FLOAT.get(tier, [])
                    else "small" if i in SMALL.get(tier, []) else None)
            if kind is None or kind != RUN:
                continue
            out = OUT_DIR / f"lizard_pose_t{tier}_p{i:02d}.png"
            im = Image.open(out).convert("RGBA")
            print(f"[t{tier} p{i:02d} {p['name']}] {kind}")
            if kind == "float":
                im = fix_float(im, p)
            else:
                im, cols, dummies = fix_small(im, p)
                NEW_LAYOUT.setdefault(tier, {})[i] = (cols, dummies)
                p["cols"], p["dummies"] = cols, dummies   # for verify/save
                dirty = True
            a = im.getchannel("A").filter(ImageFilter.MinFilter(3))
            a = a.filter(ImageFilter.GaussianBlur(0.8))
            im.putalpha(a)
            verify(im, p)
            im.save(out)
            cache = RAW_DIR / f"pose_t{tier}_p{i:02d}.png"
            if cache.exists():
                im.save(cache)
        if dirty:
            write_manifest(tier, defs)               # new cols/cells
    if NEW_LAYOUT:
        print("\nPATCH pose_sets.py with:")
        for tier, m in NEW_LAYOUT.items():
            for i, (cols, dummies) in m.items():
                print(f"  t{tier} p{i:02d}: cols={cols}, "
                      f"dummies={dummies}")
    print("done — re-run audit_lizard_placements.py")


if __name__ == "__main__":
    main()
