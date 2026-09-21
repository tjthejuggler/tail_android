#!/usr/bin/env python3
"""Audit EVERY shipped lizard pose final for floating contact + undersize.

2026-09-20 sweep. verify() in gen_lizard_poses.py only WARNS about floating /
out-of-band bulk at generation time and ships the file anyway, so defects
leaked into drawable-nodpi over the tiers ("lizard floats above the blocks",
"later lizards are tiny"). This tool re-measures every FINAL the way the app
composites it (512 src px per cell, dummies = manifest/def cells) and reports:

  · FLOAT   — grounded pose whose lowest opaque pixel sits > 35% of a cell
              above the top face of its lowest dummy row (verify()'s own
              threshold). Multi-row dummy layouts (stairs) and airborne
              poses are excluded — a uniform contact line doesn't exist.
  · SMALL   — measured vertical bulk below lo * 0.85 of the pose's (current,
              possibly raised) bulk band — the same tolerance verify() uses.
  · GEO     — bitmap size != cols*512 x rows*512, or manifest cells drift
              from the pose def's dummies.

Read-only: prints a per-pose report + summary; repairs belong in
repair_lizard_placements.py so the two passes stay reviewable.
"""
import json
from pathlib import Path

from PIL import Image

from gen_lizard_poses import OUT_DIR, MANIFEST, PX_PER_CELL, lizard_bulk_cells
from pose_sets import POSES_BY_TIER

FLOAT_TOL = 0.35          # fraction of a cell (== verify()'s threshold)
BAND_TOL = 0.85           # lo * BAND_TOL == smallest acceptable bulk
REPAIR_TIERS = (0, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)  # t1 shipped, frozen


def lowest_opaque_row(im: Image.Image) -> int:
    px = im.load()
    for y in range(im.height - 1, -1, -1):
        for x in range(0, im.width, 2):
            if px[x, y][3] > 40:
                return y
    return -1


def main() -> None:
    manifest = json.loads(MANIFEST.read_text()) if MANIFEST.exists() else {}
    m_tiers = manifest.get("tiers", {})
    n_float = n_small = n_geo = n_ok = 0
    print(f"{'pose':<22}{'bulk':>6} {'band':>12}  {'gapPx':>6}  flags")
    for tier in sorted(POSES_BY_TIER):
        defs = POSES_BY_TIER[tier]
        m_poses = m_tiers.get(str(tier), [])
        for i, p in enumerate(defs):
            tag = f"t{tier}_p{i:02d}:{p['name']}"
            path = OUT_DIR / f"lizard_pose_t{tier}_p{i:02d}.png"
            if not path.exists():
                print(f"{tag:<22}  MISSING")
                n_geo += 1
                continue
            im = Image.open(path).convert("RGBA")
            flags = []
            # GEO: exact canvas geometry
            if (im.width, im.height) != (p["cols"] * PX_PER_CELL,
                                         p["rows"] * PX_PER_CELL):
                flags.append(f"GEO size={im.width}x{im.height}")
            # GEO: manifest drift vs def dummies
            if i < len(m_poses):
                m_dum = {tuple(d) for d in m_poses[i]["dummies"]}
                if m_dum != {tuple(d) for d in p["dummies"]}:
                    flags.append(f"GEO manifest dummies drift {sorted(m_dum)}")
            dummies = {tuple(d) for d in p["dummies"]}
            rows_with_dummies = {r for (r, _) in dummies}
            single_row = len(rows_with_dummies) == 1
            # SMALL: vertical bulk vs band (same metric as the normalizer)
            lo, hi = p.get("bulk") or (None, None)
            if lo:
                b = lizard_bulk_cells(im, p)
                if b < lo * BAND_TOL:
                    flags.append(f"SMALL bulk={b:.2f} < {lo * BAND_TOL:.2f}")
                    n_small += 1
            # FLOAT: grounded + single dummy row + gap > tol
            if p.get("anchored", "below") != "airborne" and dummies \
                    and single_row and tier in REPAIR_TIERS:
                bottom = lowest_opaque_row(im)
                dummy_top = max(rows_with_dummies) * PX_PER_CELL
                gap = dummy_top - bottom
                if gap > PX_PER_CELL * FLOAT_TOL:
                    flags.append(f"FLOAT gap={gap}px "
                                 f"({gap / PX_PER_CELL:.0%} cell)")
                    n_float += 1
            if flags:
                for f in flags:
                    if not f.startswith(("SMALL", "FLOAT")):
                        n_geo += 1
                bulk_txt = (f"{b:.2f}" if lo else "-")
                band_txt = f"{lo}..{hi}" if lo else "-"
                print(f"{tag:<22}{bulk_txt:>6} {band_txt:>12}  "
                      f"{'':>6}  {'; '.join(flags)}")
            else:
                n_ok += 1
    print(f"\nOK {n_ok} | FLOAT {n_float} | SMALL {n_small} | GEO {n_geo}")


if __name__ == "__main__":
    main()
