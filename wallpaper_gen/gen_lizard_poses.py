#!/usr/bin/env python3
"""PHASE 2 — generate per-grid-cell POSE art for the shimmer lizard.

For one tier at a time (this run: tier 1 = ORANGE), generates 10 poses of the
SAME lizard character seen in tier_bar_lizard_t{tier}.png, each composed on a
cell-grid canvas (N cols x M rows @ PX_PER_CELL px/cell) that includes NEUTRAL
DUMMY SQUARES the lizard physically interacts with. In-app only the lizard
(and non-square props) are composited; the dummy squares represent the real
habit squares and are removed by chroma keying.

Chroma convention (documented — verify against new tiers before reuse):
  · BACKGROUND key  = pure magenta #FF00FF  (lizard is silver + orange; no
    magenta anywhere in tiers 0-12 palettes)
  · DUMMY-SQUARE key = pure green #00FF00   (green is NOT a key for tier 1;
    tiers that contain green accents (t2, t9) must switch this key — see
    SECONDARY_KEYS below)

Workflow per pose:
  1. Build the reference canvas: solid magenta bg + flat green dummy squares
     (full cells) + the ORIGINAL tier strip pasted (as a transparent overlay)
     near the intended pose location, so the edit model keeps the character.
  2. Send to PPQ image-edit (google/gemini-2.5-flash-image via
     /v1/chat/completions, same as gen_lizard_tiers.py) with a pose prompt.
  3. Post-process: resize back to the exact canvas size, chroma-key BOTH keys
     out, clean/feather alpha, verify per-cell occupancy vs the manifest.
  4. Save lizard_pose_t{tier}_p{NN}.png into app res/drawable-nodpi (FULL
     canvas — cell alignment must be preserved) + raw copies in
     wallpaper_gen/raw/poses/ (resumable cache). Manifest written to
     app/src/main/assets/lizard_pose_manifest.json.

Manifest cell encoding ("cells"): one string per canvas row, '1' = cell must
be an OCCUPIED habit square in-app (dummy), '0' = cell must be EMPTY (lizard
footprint / free space). The app solver places the pose so every '1' lands on
a real habit square and every '0' on an empty cell.

ANCHORING RULE (important): every pose MUST be physically anchored to the
dummy squares — standing on, hanging under, draped over — UNLESS the pose's
ACTION inherently requires mid-air (hang-glider, mid-jump, levitating
meditation for a future tier). For those, set "anchored": "airborne" in the
pose def so the app (and reviewers) know the float is intentional; the
default is anchored. Grounded poses whose feet do not touch a dummy square
are a generation defect — regenerate them.

SCALE-CONSISTENCY RULE (important): the ANIMAL is always the same physical
size — one canvas cell == one real habit square, so the chameleon's torso
must be roughly AS TALL AS ONE SQUARE in every pose. What varies between
poses is the animal's POSTURE (how many cells it spans), never its scale.
The edit model loves to shrink the subject when the canvas grows (the face-
on and push-up poses came out half-size) — two defences:
  1. prompt clause "torso about as tall as one green square" (below);
  2. postprocess SIZE NORMALIZER: measures the lizard's vertical bulk
     (P90 of per-column contiguous opaque runs, lizard cells only) and
     rescales the lizard layer about its bottom contact line into the
     pose's "bulk": (min, max) band in cells. Contact stays planted
     (anchored at the lowest point); growth into dummy cells is cut by the
     per-cell erase, growth past canvas edges is clipped.
Upscale factor is clamped at 3.0x (source is 12x oversampled, so detail
loss is negligible); beyond that, regenerate. — a big upscale means the model drew a cartoonishly small lizard and
detail is lost.

Usage:
  python3 gen_lizard_poses.py            # tier 1 (orange)
  python3 gen_lizard_poses.py --tier 5   # later rounds (NOT run this session)
"""
import argparse
import colorsys
import base64
import io
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from pose_sets import POSES_BY_TIER

BASE = Path(__file__).resolve().parent
RAW_DIR = BASE / "raw" / "poses"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
ASSETS_DIR = BASE.parent / "app/src/main/assets"
MANIFEST = ASSETS_DIR / "lizard_pose_manifest.json"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

URL = "https://api.ppq.ai/v1/chat/completions"
MODEL = "google/gemini-3-pro-image"   # Nano Banana Pro — best edit model on ppq.ai
MAX_RETRIES = 5
PX_PER_CELL = 512

BG_KEY = (255, 0, 255)     # magenta — background
SQ_KEY = (0, 255, 0)       # green   — dummy squares
BG_TOL = 150               # RGB distance threshold for keying
SQ_TOL = 150

TIER_COLORS = {  # tier -> descriptive colour name for prompts
    0: "red", 1: "orange", 2: "green", 3: "blue", 4: "pink",
    5: "yellow", 6: "white", 7: "white with red accents",
    8: "white with orange accents", 9: "white with green accents",
    10: "white with blue accents", 11: "white with pink accents",
    12: "white with yellow accents",
}

# Keys per tier — chosen from HUE histograms of the actual tier strips so no
# key's tolerance band (key hue ±37deg at sat>=70) ever touches the lizard's
# own accent glow:
#   · t4/t11 pink accents sit at hue ~330deg and t7 red at ~340-347deg — both
#     inside/near the magenta band (263-337deg) -> pure BLUE #0000FF background.
#   · t2 (teal-green, 126-183deg) and t9 (aqua swirls, 133-180deg) collide with
#     BOTH the green (83-157deg) and cyan (143-217deg) square bands -> pure
#     YELLOW #FFFF00 dummy squares (nothing saturated below 105deg in either).
#   · t12 "yellow" accents measure green-gold at 114-120deg — dead centre of
#     the green square band -> pure CYAN #00FFFF dummy squares (clear by 23deg+).
BG_KEYS = {4: (0, 0, 255), 7: (0, 0, 255), 11: (0, 0, 255)}
SQ_KEYS = {2: (255, 255, 0), 9: (255, 255, 0), 12: (0, 255, 255)}

KEY_LABEL = {  # key RGB -> (prompt hex name, upper-case colour word)
    (255, 0, 255): ("pure magenta #FF00FF", "MAGENTA"),
    (0, 0, 255): ("pure blue #0000FF", "BLUE"),
    (0, 255, 0): ("pure green #00FF00", "GREEN"),
    (255, 255, 0): ("pure yellow #FFFF00", "YELLOW"),
    (0, 255, 255): ("pure cyan #00FFFF", "CYAN"),
}


def bg_key_for(tier: int) -> tuple:
    return BG_KEYS.get(tier, BG_KEY)


def key_words(tier: int):
    """(bg prompt phrase, square prompt phrase, square colour word lower)."""
    bg = KEY_LABEL[bg_key_for(tier)][0]
    sq, word = KEY_LABEL[sq_key_for(tier)][0], KEY_LABEL[sq_key_for(tier)][1]
    return bg, sq, word.lower()


# ── helpers ──────────────────────────────────────────────────────────────────
def valid_png(path: Path) -> bool:
    if not path.exists() or path.stat().st_size == 0:
        return False
    try:
        with Image.open(path) as im:
            im.verify()
        return True
    except Exception:
        return False


def sq_key_for(tier: int) -> tuple:
    return SQ_KEYS.get(tier, SQ_KEY)


def build_reference(strip: Image.Image, p: dict, tier: int) -> Image.Image:
    W, H = p["cols"] * PX_PER_CELL, p["rows"] * PX_PER_CELL
    canvas = Image.new("RGB", (W, H), BG_KEY)
    key = sq_key_for(tier)
    # flat dummy squares filling entire cells, thin darker border for read-
    # ability; post-keying tolerances cover the border pixels.
    draw = ImageDraw.Draw(canvas)
    for (r, c) in p["dummies"]:
        x0, y0 = c * PX_PER_CELL, r * PX_PER_CELL
        x1, y1 = x0 + PX_PER_CELL, y0 + PX_PER_CELL
        draw.rectangle([x0, y0, x1 - 1, y1 - 1], fill=key)
    # paste the ORIGINAL strip as identity reference: scaled to ~80% of the
    # canvas width, sitting just above the lowest dummy row (a plausible
    # starting stance).
    strip = strip.convert("RGBA")
    tgt_w = int(W * 0.8)
    scale = tgt_w / strip.width
    strip2 = strip.resize((tgt_w, max(1, int(strip.height * scale))),
                          Image.LANCZOS)
    dummy_bottom = max(r for (r, _) in p["dummies"])
    y = max(0, dummy_bottom * PX_PER_CELL - strip2.height)
    canvas.paste(strip2, ((W - strip2.width) // 2, y), strip2)
    return canvas


def prompt_for(p: dict, tier: int) -> str:
    colour = TIER_COLORS[tier]
    bg_hex, sq_hex, sq_word = key_words(tier)
    sq_upper = sq_word.upper()
    dummy_txt = ", ".join(f"row {r + 1} column {c + 1}" for (r, c) in p["dummies"])
    return (
        f"This image shows a silver metallic robotic chameleon with glowing "
        f"{colour} accents, standing on flat solid {sq_hex} squares over a "
        f"flat solid {bg_hex} background. The image is a strict "
        f"{p['cols']}-column by {p['rows']}-row grid of equal square cells; "
        f"each {sq_word} square exactly fills one whole grid cell (currently "
        f"at: {dummy_txt}).\n"
        f"Keep EXACTLY the same robot chameleon character — same silver "
        f"metallic plates, same {colour} glowing accents, same head shape, "
        f"same coiled spiral tail, same rendering style and lighting.\n"
        f"Re-pose it as: {p['pose']}.\n"
        f"Hard requirements:\n"
        f"- Exactly ONE single chameleon in the whole image. Do not duplicate "
        f"it, do not add reflections or a second smaller copy.\n"
        f"- The chameleon stays ENTIRELY ABOVE the TOP EDGES of the "
        f"{sq_word} squares (except its toes gripping the top face and, when "
        f"the pose dangles, the explicitly hanging parts). No body part may "
        f"be drawn IN FRONT of a {sq_word} square or extend down across its "
        f"face.\n"
        f"- SCALE: this is a LARGE chameleon — its torso (belly to back, "
        f"excluding the curled tail) is about AS TALL AS ONE {sq_upper} "
        f"SQUARE. Never draw it small relative to the squares; a tiny lizard "
        f"next to big squares is wrong.\n"
        f"- No glow, halo, aura, orb, light ring or coloured background glow "
        f"around the chameleon or any prop — props are plain metallic.\n"
        f"- The colours of the keys — the {bg_hex} background fill and the "
        f"{sq_hex} square fill — appear ONLY as those flat fills. They must "
        f"never appear on the chameleon, its glow, its eye, or any prop.\n"
        f"- The {sq_word} squares stay exactly where they are, same flat "
        f"solid {sq_hex} colour, same size, each exactly filling its grid "
        f"cell — do not move, resize, rotate or restyle them.\n"
        f"- The entire background outside the squares stays flat solid "
        f"{bg_hex}, filling the whole canvas edge to edge — no gradient, no "
        f"texture, no scenery, no shadows on the background.\n"
        f"- The chameleon's feet/belly/tail must physically touch the faces "
        f"and edges of the {sq_word} squares (gravity must look "
        f"believable).\n"
        f"- The chameleon must fit fully inside the canvas without touching "
        f"the outer edges, and must never overlap or cover a {sq_word} "
        f"square except where it grips its surface.\n"
        + (f"- {p['avoid']}\n" if p.get("avoid") else "")
        + f"Output the edited image."
    )


def edit_image(api_key: str, ref: Image.Image, prompt: str,
               model: str = MODEL) -> Image.Image:
    buf = io.BytesIO()
    ref.save(buf, "PNG")
    img_b64 = base64.b64encode(buf.getvalue()).decode()
    payload = {
        "model": model,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image_url",
                 "image_url": {"url": f"data:image/png;base64,{img_b64}"}},
                {"type": "text", "text": prompt},
            ],
        }],
    }
    last_err = None
    for attempt in range(1, MAX_RETRIES + 1):
        try:
            req = urllib.request.Request(
                URL, data=json.dumps(payload).encode(),
                headers={"Content-Type": "application/json",
                         "Authorization": f"Bearer {api_key}"},
                method="POST")
            with urllib.request.urlopen(req, timeout=300) as r:
                resp = json.loads(r.read())
            msg = resp["choices"][0]["message"]
            images = msg.get("images") or []
            if not images:
                # some ppq builds return the PNG b64 under message.audio.data
                audio = msg.get("audio") or {}
                if isinstance(audio, dict) and audio.get("data"):
                    img_bytes = base64.b64decode(audio["data"])
                    im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
                    im.load()
                    return im
                raise ValueError(f"no images in response: {str(resp)[:200]}")
            item = images[0]
            url = item.get("image_url", {}).get("url") if isinstance(item, dict) else None
            if url and url.startswith("data:"):
                img_bytes = base64.b64decode(url.split(",", 1)[1])
            elif url:
                with urllib.request.urlopen(
                        urllib.request.Request(url), timeout=120) as r:
                    img_bytes = r.read()
            elif isinstance(item, dict) and item.get("b64_json"):
                img_bytes = base64.b64decode(item["b64_json"])
            else:
                raise ValueError(f"unrecognized image item: {str(item)[:200]}")
            im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
            im.load()
            return im
        except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError,
                ValueError, OSError, KeyError) as e:
            last_err = e
            wait = min(2 ** attempt, 60)
            print(f"  attempt {attempt}/{MAX_RETRIES} failed: {e}; "
                  f"retrying in {wait}s", flush=True)
            time.sleep(wait)
    raise RuntimeError(f"edit failed after {MAX_RETRIES} attempts: {last_err}")


def key_out(im: Image.Image, key: tuple, tol: int) -> Image.Image:
    """HUE-based chroma removal.

    The edit model never reproduces the exact key RGB (it shades, gradients
    and anti-aliases the fills), so exact-match keying leaves outlines and
    tinted blocks behind. Instead: build a mask of pixels whose HUE is close
    to the key hue (magenta ~300deg, green ~120deg) AND that are reasonably
    saturated, then cut them out. Low-saturation / non-key-hue pixels (the
    silver body, orange glow, gold coins) are untouched.
    """
    hsv = im.convert("HSV")
    h_ch, s_ch, v_ch = hsv.split()
    # hue near key hue (wrap-aware) AND saturation above threshold AND
    # VALUE bright enough to be background/square fill: dark body shadows
    # (gunmetal with a mauve/olive cast) can carry key-ish hue + moderate
    # saturation, and without the V floor the key shreds the lizard itself
    # (t2 walk bulk fell 1.0 -> 0.16 -> 0.0). Chroma fills are always bright.
    kh = colorsys.rgb_to_hsv(*[v / 255 for v in key])[0] * 255
    hpx, spx, vpx = h_ch.load(), s_ch.load(), v_ch.load()
    w, h = im.size
    mask = Image.new("L", (w, h), 0)
    mpx = mask.load()
    for y in range(h):
        for x in range(w):
            dv = abs(hpx[x, y] - kh)
            dv = min(dv, 255 - dv)
            if dv <= 26 and spx[x, y] >= 70 and vpx[x, y] >= 100:
                mpx[x, y] = 255
    # dilate 2px to catch anti-aliased fringes
    mask = mask.filter(ImageFilter.MaxFilter(5))
    im.paste((0, 0, 0, 0), mask)
    return im


def align_to_grid(im: Image.Image, p: dict, tier: int) -> Image.Image:
    """Warp the raw so the painted dummy squares land EXACTLY on cell rects.

    The model draws the dummy squares approximately (a few % off in position
    and scale). Without correction the lizard's feet/belly contact points
    hang in mid-air next to the real habit squares in-app. We detect the
    dummy-square mass by the TIER'S square key hue (green by default, yellow
    for t2/t9, cyan for t12), compare its bbox with the manifest's dummy-cell
    union rect, and affine-warp (resize + translate) so they coincide. The
    lizard moves with the warp, so all contact points stay believable.
    """
    dummies = p.get("dummies") or []
    if not dummies:
        return im
    w, h = im.size
    key = sq_key_for(tier)
    hsv = im.convert("HSV")
    hpx = hsv.getchannel("H").load()
    spx = hsv.getchannel("S").load()
    vpx = hsv.getchannel("V").load()
    kh = colorsys.rgb_to_hsv(*[v / 255 for v in key])[0] * 255
    mask = Image.new("L", (w, h), 0)
    mpx = mask.load()
    for y in range(0, h, 4):
        for x in range(0, w, 4):
            dv = abs(hpx[x, y] - kh)
            dv = min(dv, 255 - dv)
            if dv <= 26 and spx[x, y] >= 70 and vpx[x, y] >= 100:
                mpx[x, y] = 255
    bbox = mask.getbbox()
    if not bbox:
        return im
    xs = [c for (_, c) in dummies]
    rs = [r for (r, _) in dummies]
    ex0, ey0 = min(xs) * PX_PER_CELL, min(rs) * PX_PER_CELL
    ex1, ey1 = (max(xs) + 1) * PX_PER_CELL, (max(rs) + 1) * PX_PER_CELL
    ew, eh = ex1 - ex0, ey1 - ey0
    bw, bh = bbox[2] - bbox[0], bbox[3] - bbox[1]
    if bw <= 0 or bh <= 0 or bw < ew * 0.4 or bh < eh * 0.4:
        return im
    sx, sy = ew / bw, eh / bh
    # The green mask can catch model-tinted pixels OUTSIDE the true dummy
    # rect, inflating the bbox; an oversized bbox would then SHRINK the whole
    # scene (p04/p05 regression). Only trust near-unit scale factors.
    if not (0.75 <= sx <= 1.35 and 0.75 <= sy <= 1.35):
        return im
    im._grid_bbox = (ex0, ey0, ex1, ey1)
    im2 = im.resize((max(1, round(w * sx)), max(1, round(h * sy))),
                    Image.LANCZOS)
    offx = round(ex0 - bbox[0] * sx)
    offy = round(ey0 - bbox[1] * sy)
    canvas = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    canvas.paste(im2, (offx, offy))
    return canvas


def lizard_bulk_cells(im: Image.Image, p: dict) -> float:
    """P90 of per-column contiguous opaque runs (in cells), lizard cells only."""
    px = im.load()
    W, H = im.size
    dummies = {(r, c) for (r, c) in p.get("dummies") or []}
    runs = []
    for x in range(0, W, 8):
        best = cur = 0
        for y in range(H):
            if px[x, y][3] > 40 and (y // PX_PER_CELL, x // PX_PER_CELL) not in dummies:
                cur += 1
                best = max(best, cur)
            else:
                cur = 0
        if best > PX_PER_CELL * 0.08:
            runs.append(best)
    if not runs:
        return 0.0
    runs.sort()
    return runs[int(len(runs) * 0.9)] / PX_PER_CELL


def normalize_lizard_size(im: Image.Image, p: dict) -> Image.Image:
    """Scale the LIZARD LAYER so its vertical bulk matches the pose band.

    The edit model shrinks the subject as the canvas grows (walk came out
    0.18 cells vs the 0.45+ target). Rescale the lizard about its bottom
    contact line (gravity anchor preserved) into the band midpoint, factor
    clamped to [0.8, 1.8]: beyond that the model drew a wrong-sized lizard
    and the pose should be REGENERATED instead of upscaled.
    """
    lo, hi = p.get("bulk") or (None, None)
    if not lo:
        return im
    b = lizard_bulk_cells(im, p)
    target = (lo + hi) / 2
    # clamp 3.0: source is 512px/cell vs ~40px on-screen (12x oversampled),
    # so even a 3x upscale stays crisp on device. Beyond that, regenerate.
    f = max(0.8, min(3.0, target / max(b, 0.05)))
    if abs(f - 1.0) < 0.06:
        return im
    px = im.load()
    W, H = im.size
    # lizard bbox (non-dummy opaque pixels)
    l, t, r, bo = W, H, -1, -1
    for y in range(0, H, 2):
        for x in range(0, W, 2):
            if px[x, y][3] > 40 and (y // PX_PER_CELL, x // PX_PER_CELL) not in {q for q in p.get("dummies") or []}:
                l = min(l, x); r = max(r, x)
                t = min(t, y); bo = max(bo, y)
    if r <= l or bo <= t:
        return im
    layer = im.crop((l, t, r + 1, bo + 1))
    # Fit guarantee: cap the factor so the scaled layer fits the canvas
    # around the anchor (bottom line + horizontal centre) — a lizard cut off
    # at an edge is worse than a slightly small lizard.
    cx = (l + r) // 2
    max_w = 2 * min(cx, W - cx)
    max_h = bo + 1  # from top edge down to the contact line
    f = min(f, max_w / layer.width, max_h / layer.height)
    f = max(f, 0.8)
    nw, nh = max(1, round(layer.width * f)), max(1, round(layer.height * f))
    layer = layer.resize((nw, nh), Image.LANCZOS)
    # anchor: keep the BOTTOM contact line and horizontal centre fixed
    nx0 = cx - nw // 2
    ny0 = bo + 1 - nh
    im.paste((0, 0, 0, 0), (l, t, r + 1, bo + 1))
    im.alpha_composite(layer, (nx0, ny0))
    return im


def postprocess(raw: Image.Image, p: dict, tier: int) -> Image.Image:
    W, H = p["cols"] * PX_PER_CELL, p["rows"] * PX_PER_CELL
    im = raw.resize((W, H), Image.LANCZOS).convert("RGBA")
    im = align_to_grid(im, p, tier)
    im = key_out(im, bg_key_for(tier), BG_TOL)
    im = key_out(im, sq_key_for(tier), SQ_TOL)
    # overlap report BEFORE the erase: how much lizard the model baked into
    # the square area (after keying). Large values mean the pose was drawn
    # "in front of" the squares and will get visibly cut — regenerate it.
    px = im.load()
    inset = round(PX_PER_CELL * 0.047)
    overlap = 0
    for (r, c) in p.get("dummies") or []:
        for y in range(r * PX_PER_CELL + inset, (r + 1) * PX_PER_CELL - inset, 8):
            for x in range(c * PX_PER_CELL + inset,
                           (c + 1) * PX_PER_CELL - inset, 8):
                if px[x, y][3] > 40:
                    overlap += 1
    if overlap > 400:
        print(f"    WARN overlap={overlap} px inside square area "
              f"(pose drawn in front of squares) — consider regen")
    # SIZE CONSISTENCY: scale the lizard layer into the pose's bulk band
    im = normalize_lizard_size(im, p)
    # despill: neutralize remaining strongly magenta/green-dominant pixels
    # (edge halos, glow contamination) instead of deleting them.
    px = im.load()
    w, h = im.size
    for y in range(h):
        for x in range(w):
            r, g, b, a4 = px[x, y]
            if a4 == 0:
                continue
            if bg_key_for(tier) == BG_KEY and min(r, b) > 60 and \
                    g < 0.72 * min(r, b):
                # magenta despill (default bg): neutralize red+blue dominant
                m = (r + b) // 2
                px[x, y] = (m, (2 * g + m) // 3, m, a4)
            elif bg_key_for(tier) == (0, 0, 255) and b > 80 and \
                    r < 0.7 * b and g < 0.7 * b:
                # blue bg despill (t4/t7/t11): neutralize blue-dominant halo
                # WITHOUT touching the pink/red accents (r or g high).
                px[x, y] = (r, g, (2 * b + r) // 3, a4)
            elif sq_key_for(tier) == SQ_KEY and g > 80 and r < 0.7 * g \
                    and b < 0.7 * g:
                # green square despill (default squares)
                px[x, y] = ((2 * r + g) // 3, g, (2 * b + g) // 3, a4)
    # HARD GUARANTEE: nothing may cover a habit square in-app. Erase every
    # dummy CELL rect (inset to the real visible square face, ~4.7% cell =
    # the grid's 2dp padding) so any lizard part the model drew overlapping
    # the squares is cut at the face line — in-app it then reads as the body
    # passing BEHIND the square, never in front of it. Poses should keep
    # overlap minimal anyway (prompt requires staying above the square tops).
    draw = ImageDraw.Draw(im)
    inset = round(PX_PER_CELL * 0.047)
    for (r, c) in p.get("dummies") or []:
        x0 = c * PX_PER_CELL + inset
        y0 = r * PX_PER_CELL + inset
        x1 = (c + 1) * PX_PER_CELL - inset
        y1 = (r + 1) * PX_PER_CELL - inset
        draw.rectangle([x0, y0, x1 - 1, y1 - 1], fill=(0, 0, 0, 0))
    # edge cleanup: shrink the alpha channel 1px, then feather.
    a = im.getchannel("A").filter(ImageFilter.MinFilter(3))
    a = a.filter(ImageFilter.GaussianBlur(0.8))
    im.putalpha(a)
    return im


def cell_coverage(im: Image.Image) -> list:
    """Per-cell fraction of non-transparent pixels (rows x cols)."""
    px = im.load()
    out = []
    for r in range(im.height // PX_PER_CELL):
        row = []
        for c in range(im.width // PX_PER_CELL):
            n = tot = 0
            for y in range(r * PX_PER_CELL + 64, (r + 1) * PX_PER_CELL - 64, 16):
                for x in range(c * PX_PER_CELL + 64, (c + 1) * PX_PER_CELL - 64, 16):
                    tot += 1
                    if px[x, y][3] > 40:
                        n += 1
            row.append(n / max(1, tot))
        out.append(row)
    return out


def verify(im: Image.Image, p: dict) -> bool:
    cov = cell_coverage(im)
    ok = True
    dummies = set(p["dummies"])
    for r, row in enumerate(cov):
        for c, v in enumerate(row):
            if (r, c) in dummies and v > 0.25:
                print(f"    WARN dummy cell ({r},{c}) still has content "
                      f"({v:.0%})")
                ok = False
    lizard_cells = sum(v for (r, row) in enumerate(cov) for c, v in enumerate(row)
                       if (r, c) not in dummies)
    if lizard_cells <= 0.02 * len(cov):
        print("    WARN lizard appears empty")
        ok = False
    # FLOATING CHECK: unless the pose is explicitly airborne, the lowest
    # lizard pixel must sit within 35% of a cell above the top face of the
    # lowest dummy row (i.e. touching / nearly touching the surface).
    if p.get("anchored", "below") != "airborne" and dummies:
        px = im.load()
        bottom = -1
        for y in range(im.height - 1, -1, -2):
            row_has = False
            for x in range(0, im.width, 2):
                if px[x, y][3] > 40:
                    row_has = True
                    break
            if row_has:
                bottom = y
                break
        lowest_dummy_top = max(r for (r, _) in p["dummies"]) * PX_PER_CELL
        gap = lowest_dummy_top - bottom
        if gap > PX_PER_CELL * 0.35:
            print(f"    WARN pose FLOATS: lizard bottom {bottom}px is "
                  f"{gap}px above the surface face")
            ok = False
    # EDGE-CLIP check: the TOP edge must be clean (a head/back cut off is
    # always a defect). Left/right/bottom edges may only be touched by thin
    # trailing elements (tail tip, dangling toes) — tolerance 25% of a cell.
    # (px is bound HERE: the floating check above only binds it for grounded
    # poses, and airborne poses skip that branch entirely.)
    Wv, Hv = im.size
    px = im.load()
    top_cut = sum(1 for x in range(0, Wv, 4) if px[x, 0][3] > 40)
    edge_cut = sum(1 for x in range(0, Wv, 4) if px[x, Hv - 1][3] > 40) + \
        sum(1 for y in range(0, Hv, 4) if px[0, y][3] > 40 or px[Wv - 1, y][3] > 40)
    if top_cut > 2:
        print(f"    WARN lizard CLIPPED at TOP edge ({top_cut} px) — regen")
        ok = False
    if edge_cut > PX_PER_CELL * 0.25 / 4:
        print(f"    WARN heavy edge clipping ({edge_cut} px) — regen")
        ok = False
    # SCALE check: bulk must land in the pose's band (after normalizing)
    lo, hi = p.get("bulk") or (None, None)
    if lo:
        b = lizard_bulk_cells(im, p)
        if not (lo * 0.85 <= b <= hi * 1.15):
            print(f"    WARN bulk {b:.2f} outside band ({lo}..{hi}) — "
                  f"lizard size inconsistent with other poses")
            ok = False
    return ok


def write_manifest(tier: int, defs: list):
    MANIFEST.parent.mkdir(parents=True, exist_ok=True)
    data = {}
    if MANIFEST.exists():
        try:
            data = json.loads(MANIFEST.read_text())
        except Exception:
            data = {}
    data["px_per_cell"] = PX_PER_CELL
    tiers = data.setdefault("tiers", {})
    poses = []
    for i, p in enumerate(defs):
        cells = []
        for r in range(p["rows"]):
            cells.append("".join(
                "1" if (r, c) in set(p["dummies"]) else "0"
                for c in range(p["cols"])))
        poses.append({"file": f"lizard_pose_t{tier}_p{i:02d}",
                      "name": p["name"], "cols": p["cols"], "rows": p["rows"],
                      "anchored": p.get("anchored", "below"),
                      "bulk": list(p.get("bulk", [])),
                      "dummies": [list(d) for d in p["dummies"]],
                      "cells": cells})
    tiers[str(tier)] = poses
    MANIFEST.write_text(json.dumps(data, indent=2))
    print(f"manifest → {MANIFEST}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--tier", type=int, default=1)
    ap.add_argument("--only", type=int, default=-1,
                    help="generate only pose index N")
    ap.add_argument("--model", type=str, default=MODEL,
                    help="image-edit model id on ppq.ai")
    ap.add_argument("--reprocess", action="store_true",
                    help="re-run postprocess from cached raws, no API calls")
    args = ap.parse_args()
    tier = args.tier

    strip_path = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
    if not valid_png(strip_path):
        print(f"ERROR: missing strip {strip_path}", file=sys.stderr)
        sys.exit(1)
    strip = Image.open(strip_path)
    api_key = API_KEY_FILE.read_text().strip()
    if not api_key:
        print("ERROR: empty API key", file=sys.stderr)
        sys.exit(1)
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    if tier not in POSES_BY_TIER:
        print(f"ERROR: no pose set defined for tier {tier} "
              f"(defined: {sorted(POSES_BY_TIER)})", file=sys.stderr)
        sys.exit(1)
    defs = POSES_BY_TIER[tier]
    failures = []
    for i, p in enumerate(defs):
        if args.only >= 0 and i != args.only:
            continue
        out = OUT_DIR / f"lizard_pose_t{tier}_p{i:02d}.png"
        if valid_png(out) and not args.reprocess:
            print(f"[t{tier} p{i:02d} {p['name']}] skip (exists)", flush=True)
            continue
        raw_path = RAW_DIR / f"raw_t{tier}_p{i:02d}.png"
        if args.reprocess:
            if not valid_png(raw_path):
                print(f"[t{tier} p{i:02d}] no raw cached, skip", flush=True)
                continue
            print(f"[t{tier} p{i:02d} {p['name']}] reprocessing ...", flush=True)
            raw = Image.open(raw_path)
            im = postprocess(raw, p, tier)
            im.save(out)
            print(f"[t{tier} p{i:02d}] saved {out.name}", flush=True)
            continue
        print(f"[t{tier} p{i:02d} {p['name']}] generating ...", flush=True)
        try:
            ref = build_reference(strip, p, tier)
            ref.save(RAW_DIR / f"ref_t{tier}_p{i:02d}.png")
            raw = edit_image(api_key, ref, prompt_for(p, tier), args.model)
            raw.save(RAW_DIR / f"raw_t{tier}_p{i:02d}.png")
            im = postprocess(raw, p, tier)
            im.save(RAW_DIR / f"pose_t{tier}_p{i:02d}.png")
            verify(im, p)
            out.write_bytes((RAW_DIR / f"pose_t{tier}_p{i:02d}.png").read_bytes())
            print(f"[t{tier} p{i:02d}] saved {out.name} "
                  f"{out.stat().st_size} bytes", flush=True)
        except Exception as e:
            failures.append({"tier": tier, "pose": i, "error": str(e)})
            print(f"[t{tier} p{i:02d}] FAILED: {e}", flush=True)
        time.sleep(1.5)

    write_manifest(tier, defs)
    print(f"\nSummary: failed={len(failures)}", flush=True)
    if failures:
        print(json.dumps(failures, indent=2), flush=True)
        sys.exit(2)


if __name__ == "__main__":
    main()
