#!/usr/bin/env python3
"""Compose final wallpapers: 1440x3088 black canvas, 1440x1440 artwork
placed at a stepped y-offset by letter index, with animal label near top."""
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

BASE = Path(__file__).parent
RAW = BASE / "raw"
OUT = BASE / "final"
OUT.mkdir(exist_ok=True)

CANVAS_W, CANVAS_H = 1440, 3088
ART = 1440
STEP = 60  # 60 * 25 = 1500 <= 1648 max offset -> never off-canvas
MAX_OFFSET = CANVAS_H - ART  # 1648

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
]
FONT_PATH = next((p for p in FONT_CANDIDATES if Path(p).exists()), None)
if FONT_PATH is None:
    raise SystemExit("No bold DejaVu font found")

FONT_SIZE = 100
LETTER_SPACING = 12  # px extra between characters


def load_labels():
    labels = {}
    for f in sorted(BASE.glob("concepts_*.json")):
        for c in json.loads(f.read_text()):
            labels[c["n"]] = c.get("label")
    return labels


def draw_spaced_text(draw, text, cx, y, font, fill=(255, 255, 255)):
    """Draw text centered at cx with letter-spacing; returns total width."""
    widths = [draw.textlength(ch, font=font) for ch in text]
    total = sum(widths) + LETTER_SPACING * (len(text) - 1)
    x = cx - total / 2
    for ch, w in zip(text, widths):
        draw.text((x, y), ch, font=font, fill=fill)
        x += w + LETTER_SPACING
    return total


def main():
    labels = load_labels()
    font = ImageFont.truetype(FONT_PATH, FONT_SIZE)

    offsets = {}
    for n in range(100):
        L = (n - 1) % 26 if n >= 1 else 0
        offsets[n] = min(STEP * L, MAX_OFFSET)

    ok, bad = 0, []
    for n in range(100):
        src = RAW / f"result_{n}.png"
        if not src.exists():
            bad.append((n, "missing raw"))
            continue
        art = Image.open(src).convert("RGB").resize((ART, ART), Image.LANCZOS)

        canvas = Image.new("RGB", (CANVAS_W, CANVAS_H), (0, 0, 0))
        y = offsets[n]
        canvas.paste(art, (0, y))

        label = labels.get(n)
        if label:  # N=0 has no label
            # Place label in black area above artwork, ~17% canvas height
            # (matches example's 15-19% region), or fixed near top if art at 0.
            ly = max(60, y - 230)
            if ly < 100:
                ly = 100
            draw_spaced_text(ImageDraw.Draw(canvas), label, CANVAS_W / 2, ly, font)

        out = OUT / f"result_{n}.png"
        canvas.save(out, "PNG")
        # verify
        w, h = Image.open(out).size
        if (w, h) == (CANVAS_W, CANVAS_H):
            ok += 1
        else:
            bad.append((n, f"{w}x{h}"))

    print(f"Composed {ok}/100 wallpapers at {CANVAS_W}x{CANVAS_H} in {OUT}")
    if bad:
        print("PROBLEMS:", bad)
    else:
        print("Verification: all 100 outputs are 1440x3088 PNG. OK")
    # offset summary
    print("Offsets: N=0:%d, N=1:%d, N=26:%d, N=27:%d, N=99:%d (L=%d)" % (
        offsets[0], offsets[1], offsets[26], offsets[27], offsets[99], (99 - 1) % 26))


if __name__ == "__main__":
    main()
