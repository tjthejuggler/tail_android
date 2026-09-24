#!/usr/bin/env python3
"""
optimize_lizard_art.py — resize + palette-quantize the lizard pose/tier art.

The pose/tier PNGs ship at 1024–2048 px wide but render at a few hundred
physical pixels, so they are heavily oversampled. This script:

  1. BACKS UP the current res/drawable-nodpi PNGs to
     archive/drawable-nodpi_fullres_backup/ (idempotent — never overwrites
     an existing backup, so re-runs keep the true originals).
  2. Resizes poses (lizard_pose_*) so the max dimension is --pose-max (512).
  3. Resizes tier strips (tier_bar_*) to --strip-width (1024×256).
  4. Quantizes everything to a palette (--colors, alpha-aware), which is
     where most of the byte savings come from.
  5. Writes a side-by-side before/after preview PNG per class so quality
     can be judged visually.

Run:  python3 scripts/optimize_lizard_art.py [--apply]
      without --apply it processes only a SAMPLE set and writes previews
      to /tmp/lizard_preview/ plus a size report.
"""
import argparse
import os
import shutil
import sys

from PIL import Image

SRC = "app/src/main/res/drawable-nodpi"
BACKUP = "archive/drawable-nodpi_fullres_backup"
PREVIEW = "/tmp/lizard_preview"

POSE_MAX = 512      # max dimension for lizard_pose_* (source 1024–1536)
STRIP_WIDTH = 1024  # tier strips are 2048×512 → 1024×256
COLORS = 128        # palette size (alpha-aware)

def backup():
    os.makedirs(BACKUP, exist_ok=True)
    n = 0
    for f in os.listdir(SRC):
        if not f.endswith(".png"):
            continue
        dst = os.path.join(BACKUP, f)
        if not os.path.exists(dst):  # never overwrite the true original
            shutil.copy2(os.path.join(SRC, f), dst)
            n += 1
    print(f"backup: {n} new file(s) copied to {BACKUP}")

def optimize(im: Image.Image, is_strip: bool) -> Image.Image:
    w, h = im.size
    if is_strip:
        scale = STRIP_WIDTH / w
    else:
        scale = min(1.0, POSE_MAX / max(w, h))
    if scale < 1.0:
        im = im.resize((max(1, round(w * scale)), max(1, round(h * scale))),
                       Image.LANCZOS)
    # Alpha-aware palette quantization; dithering keeps gradients smooth.
    return im.quantize(colors=COLORS, method=Image.FASTOCTREE, dither=Image.FLOYDSTEINBERG)

def classify(name: str) -> str:
    if name.startswith("lizard_pose_"):
        return "pose"
    if name.startswith("tier_bar_"):
        return "strip"
    return "other"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true",
                    help="write optimized files into res/ (default: sample+preview only)")
    args = ap.parse_args()

    backup()
    os.makedirs(PREVIEW, exist_ok=True)

    files = sorted(f for f in os.listdir(SRC) if f.endswith(".png"))
    if args.apply:
        targets = files
    else:
        # Sample: one pose, one tier strip, one sky, one env, one morph
        def first(prefix):
            return next((f for f in files if f.startswith(prefix)), None)
        targets = [f for f in [
            first("lizard_pose_"), first("tier_bar_lizard_t1"),
            first("tier_bar_lizard_m0_p"), first("tier_bar_sky_t1"),
            first("tier_bar_env_t1"),
        ] if f]
        if not targets:
            print("nothing to sample"); sys.exit(1)

    tot_before = tot_after = 0
    for f in targets:
        src_p = os.path.join(SRC, f)
        before = os.path.getsize(src_p)
        im = Image.open(src_p).convert("RGBA")
        out = optimize(im, classify(f) == "strip")

        tot_before += before
        # Measure optimized size via a temp encode.
        tmp = os.path.join(PREVIEW, "tmp_" + f)
        out.save(tmp, optimize=True)
        after = os.path.getsize(tmp)
        tot_after += after

        if args.apply:
            shutil.move(tmp, src_p)
            print(f"  {f}: {before//1024} KB → {after//1024} KB")
        else:
            # Side-by-side preview: original downscaled to display-ish size
            # above the optimized version at the same size.
            disp_w = 256
            disp_h = max(1, round(im.size[1] * disp_w / im.size[0]))
            a = im.resize((disp_w, disp_h), Image.LANCZOS)
            b = out.convert("RGBA").resize((disp_w, disp_h), Image.LANCZOS)
            canvas = Image.new("RGBA", (disp_w, disp_h * 2 + 8), (24, 24, 24, 255))
            canvas.paste(a, (0, 0))
            canvas.paste(b, (0, disp_h + 8))
            prev_p = os.path.join(PREVIEW, "preview_" + f)
            canvas.save(prev_p)
            os.remove(tmp)
            print(f"  {f}: {before//1024} KB → {after//1024} KB  (preview: {prev_p})")

    print(f"\nsample totals: {tot_before/1048576:.1f} MB → {tot_after/1048576:.1f} MB "
          f"({tot_after/max(tot_before,1)*100:.0f}%)")
    if not args.apply:
        print("sample only — re-run with --apply to write all optimized files")

if __name__ == "__main__":
    main()
