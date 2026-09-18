#!/usr/bin/env python3
"""Growth-morph lizard strips via RECURSIVE MIDPOINT BISECTION -- all tiers.

User protocol (verbatim intent):
  Take the two ORIGINAL milestone images of a tier span and make the one
  directly in between them in age, keeping the YOUNGER one's highlight
  colour.  That becomes the exact middle point.  Then do it again between
  the youngest and that middle one, and between the middle one and the
  oldest.  Keep making the one directly in the middle of each bracket until
  every point value is filled.  The glow colour NEVER changes inside a
  span -- it switches only when the next colour zone is entered (at the
  milestone itself).

Because every new image blends two already-nearly-identical neighbours,
the step between adjacent pictures is always tiny -- a huge jump is
structurally impossible.  On top of the protocol, each blend uses
BEST-OF-N selection by neighbour-similarity (silhouette IoU vs both
bracket images): the first attempt passing all hard constraints with
IoU >= 0.75 wins; otherwise the highest-IoU pass is kept, so endings and
beginnings stay as smooth as the model allows.

Spans (habitPointsTier boundaries): t0->t1 (14 steps), t1->t2 (7),
t2->t3 (10), t3->t4 (10), t4..t12 (6 each).  Milestones are the shipped
tier_bar_lizard_t{N}.png strips and are NEVER regenerated.  Outputs:
  app/src/main/res/drawable-nodpi/tier_bar_lizard_m{tier}_p{point:02d}.png
(2048x512 RGBA, right-anchored, vertically centred -- widget format).
TierBarWidgetProvider resolves m{dayTier}_p{todayPoints} directly.

Model: ppq.ai google/gemini-3-pro-image (fallback gemini-2.5-flash-image).
Background chroma: pure blue #0000FF (absent from all single-hue tier
glows; white-tier glows are desaturated and equally unaffected).

CLI:
  python3 gen_lizard_morph.py --span 0..11    # one span (default: all)
  python3 gen_lizard_morph.py --only 13       # redo one global point
  python3 gen_lizard_morph.py --build-only    # strips from cached blends
  python3 gen_lizard_morph.py --verify        # full-ladder check only
  python3 gen_lizard_morph.py --dry-run       # prompts only
"""
import argparse
import base64
import colorsys
import io
import json
import sys
import time
import urllib.error
import urllib.request
from collections import deque
from pathlib import Path

from PIL import Image, ImageFilter

BASE = Path(__file__).resolve().parent
RAW_DIR = BASE / "raw" / "morph"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

CHAT_URL = "https://api.ppq.ai/v1/chat/completions"
CHAT_MODEL = "google/gemini-3-pro-image"
FALLBACK_MODEL = "google/gemini-2.5-flash-image"
MAX_RETRIES = 4

STRIP_W, STRIP_H = 2048, 512
CHROMA_NAME, CHROMA, CHROMA_HEX = "pure blue", (0, 0, 255), "#0000FF"


def ref_canvas(span: int):
    """Longer adult lizards need a wider stage or the model fills the frame
    edge-to-edge (measured: spans >= 3 clipped at 1536x864)."""
    return (2048, 720) if span >= 3 else (1536, 864)

BOUNDARIES = [0, 14, 21, 31, 42, 49, 56, 63, 70, 77, 84, 91, 98]
TIERS = 12
ACCEPT_IOU = 0.75          # neighbour-similarity fast accept
CORR_MIN, CORR_MAX = 0.88, 1.14

# glow hue families (degrees)
HUE_BANDS = {
    "red": [(335, 360), (0, 18)],
    "orange": [(18, 45)],
    "yellow": [(45, 75)],
    "green": [(75, 170)],
    "blue": [(170, 260)],
    "pink": [(260, 335)],
    "white": [],
}
# tier -> (primary glow, secondary glow)
TIER_GLOW = {0: ("red", None), 1: ("orange", None), 2: ("green", None),
             3: ("blue", None), 4: ("pink", None), 5: ("yellow", None),
             6: ("white", None), 7: ("white", "red"),
             8: ("white", "orange"), 9: ("white", "green"),
             10: ("white", "blue"), 11: ("white", "pink"),
             12: ("white", "yellow")}


def in_family(deg: int, family: str) -> bool:
    for lo, hi in HUE_BANDS[family]:
        if lo <= deg < hi or (hi == 360 and lo <= deg):
            return True
    return False


# ----------------------------------------------------------------------------
# brackets / schedule
# ----------------------------------------------------------------------------

def bracket_tree(steps: int):
    order, level = [], [(0, steps)]
    while level:
        nxt = []
        for a, b in level:
            m = (a + b) // 2
            if a < m < b:
                order.append((a, m, b))
                nxt += [(a, m), (m, b)]
        level = nxt
    return order


def span_steps(a: int) -> int:
    return BOUNDARIES[a + 1] - BOUNDARIES[a]


_milestones: dict = {}


def milestone(tier: int) -> Image.Image:
    if tier not in _milestones:
        strip = Image.open(OUT_DIR / f"tier_bar_lizard_t{tier}.png") \
            .convert("RGBA")
        _milestones[tier] = strip.crop(strip.getbbox())
    return _milestones[tier]


def sched_height(span: int, rel: int) -> float:
    h0, h1 = milestone(span).height, milestone(span + 1).height
    return h0 + (h1 - h0) * rel / span_steps(span)


def sched_aspect(span: int, rel: int) -> float:
    m0, m1 = milestone(span), milestone(span + 1)
    a0, a1 = m0.width / m0.height, m1.width / m1.height
    return a0 + (a1 - a0) * rel / span_steps(span)


# ----------------------------------------------------------------------------
# image plumbing
# ----------------------------------------------------------------------------

def on_chroma(lizard: Image.Image, height_px: int, canvas_size) -> Image.Image:
    w, h = canvas_size
    scale = height_px / lizard.height
    lz = lizard.resize((max(1, round(lizard.width * scale)),
                        max(1, height_px)), Image.LANCZOS)
    canvas = Image.new("RGB", (w, h), CHROMA)
    canvas.paste(lz, ((w - lz.width) // 2, (h - lz.height) // 2), lz)
    return canvas


def b64_png(im: Image.Image) -> str:
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return base64.b64encode(buf.getvalue()).decode()


def edit_via_chat(prompt: str, refs, api_key: str, model: str) -> bytes:
    content = [{"type": "image_url",
                "image_url": {"url": f"data:image/png;base64,{b64_png(r)}"}}
               for r in refs]
    content.append({"type": "text", "text": prompt})
    payload = {"model": model,
               "messages": [{"role": "user", "content": content}]}
    req = urllib.request.Request(
        CHAT_URL, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {api_key}"}, method="POST")
    with urllib.request.urlopen(req, timeout=300) as r:
        resp = json.loads(r.read())
    msg = resp["choices"][0]["message"]
    imgs = msg.get("images") or []
    if not imgs:
        audio = msg.get("audio") or {}
        if isinstance(audio, dict) and audio.get("data"):
            return base64.b64decode(audio["data"])
        raise ValueError(f"no images: {str(resp)[:200]}")
    item = imgs[0]
    url = item.get("image_url", {}).get("url") \
        if isinstance(item, dict) else None
    if url and url.startswith("data:"):
        return base64.b64decode(url.split(",", 1)[1])
    if url:
        with urllib.request.urlopen(urllib.request.Request(url),
                                    timeout=120) as r:
            return r.read()
    if isinstance(item, dict) and item.get("b64_json"):
        return base64.b64decode(item["b64_json"])
    raise ValueError("unrecognized image item")


def edit_image(prompt: str, refs, api_key: str) -> Image.Image:
    last = None
    for attempt in range(1, MAX_RETRIES + 1):
        for model in (CHAT_MODEL, FALLBACK_MODEL):
            try:
                data = edit_via_chat(prompt, refs, api_key, model)
                im = Image.open(io.BytesIO(data)).convert("RGB")
                im.load()
                return im
            except (urllib.error.HTTPError, urllib.error.URLError,
                    TimeoutError, ValueError, OSError, KeyError) as e:
                last = e
                print(f"    {model} failed: {e}", flush=True)
        wait = min(2 ** attempt, 60)
        print(f"  attempt {attempt}/{MAX_RETRIES} failed; retry in {wait}s",
              flush=True)
        time.sleep(wait)
    raise RuntimeError(f"edit failed after {MAX_RETRIES} attempts: {last}")


def is_key_rgb(r, g, b) -> bool:
    h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
    return 200 <= h * 360 <= 260 and s >= 0.45 and v >= 0.55


def chroma_key(im: Image.Image):
    """-> (keyed RGBA, n_significant_components)."""
    w, h = im.size
    rgb = im.convert("RGB")
    px = rgb.load()

    def is_key(x, y):
        return is_key_rgb(*px[x, y])

    visited = bytearray(w * h)
    dq = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_key(x, y) and not visited[y * w + x]:
                visited[y * w + x] = 1
                dq.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_key(x, y) and not visited[y * w + x]:
                visited[y * w + x] = 1
                dq.append((x, y))
    while dq:
        x, y = dq.popleft()
        for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
            if 0 <= nx < w and 0 <= ny < h and not visited[ny * w + nx] \
                    and is_key(nx, ny):
                visited[ny * w + nx] = 1
                dq.append((nx, ny))

    alpha = Image.new("L", (w, h), 255)
    ap = alpha.load()
    for y in range(h):
        for x in range(w):
            if visited[y * w + x] or is_key(x, y):
                ap[x, y] = 0
    for y in range(h):          # despill blue fringe on neutral metal
        for x in range(w):
            if ap[x, y]:
                r, g, b = px[x, y]
                if b > r * 1.25 and b > g * 1.25:
                    px[x, y] = (r, g, max(r, g))

    seen = bytearray(w * h)
    comps = []
    for sy in range(h):
        for sx in range(w):
            i0 = sy * w + sx
            if seen[i0] or ap[sx, sy] == 0:
                continue
            comp, dq2 = [], deque([(sx, sy)])
            seen[i0] = 1
            while dq2:
                x, y = dq2.popleft()
                comp.append((x, y))
                for nx, ny in ((x + 1, y), (x - 1, y), (x, y + 1), (x, y - 1)):
                    j = ny * w + nx
                    if 0 <= nx < w and 0 <= ny < h and not seen[j] \
                            and ap[nx, ny]:
                        seen[j] = 1
                        dq2.append((nx, ny))
            comps.append(comp)
    if not comps:
        raise ValueError("chroma key removed everything")
    main = max(comps, key=len)
    sig = [c for c in comps if len(c) >= 0.01 * len(main)]
    for comp in comps:
        if len(comp) < max(64, 0.005 * len(main)):
            for x, y in comp:
                ap[x, y] = 0
    mask = alpha.point(lambda v: 255 if v > 0 else 0)
    mask = mask.filter(ImageFilter.MinFilter(5))
    mask = mask.filter(ImageFilter.GaussianBlur(1.2))
    out = rgb.copy().convert("RGBA")
    out.putalpha(mask)
    return out, len(sig)


# ----------------------------------------------------------------------------
# validation
# ----------------------------------------------------------------------------

def glow_family_fractions(keyed: Image.Image) -> dict:
    px = keyed.resize((256, 256)).load()
    counts = {k: 0 for k in HUE_BANDS}
    counts["none"] = 0
    for y in range(256):
        for x in range(256):
            r, g, b, a = px[x, y]
            if a < 200:
                continue
            h, s, v = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            if s < 0.40 or v < 0.45:
                continue
            deg = int(h * 360)
            for fam, bands in HUE_BANDS.items():
                if bands and in_family(deg, fam):
                    counts[fam] += 1
                    break
            else:
                counts["none"] += 1
    tot = sum(counts.values())
    return {k: (v / tot if tot else 0.0) for k, v in counts.items()}


def silhouette_iou(a: Image.Image, b: Image.Image) -> float:
    masks = []
    for im in (a, b):
        bb = im.getbbox()
        if bb is None:
            return 0.0
        crop = im.crop(bb).getchannel("A").point(
            lambda v: 255 if v > 128 else 0)
        masks.append(crop.resize((64, 64), Image.LANCZOS).load())
    pa, pb = masks
    inter = uni = 0
    for y in range(64):
        for x in range(64):
            ma, mb = pa[x, y] > 0, pb[x, y] > 0
            if ma and mb:
                inter += 1
            if ma or mb:
                uni += 1
    return inter / uni if uni else 0.0


def validate_blend(keyed, n_comp, younger, older, span, rel):
    """Hard constraints: one lizard, margins, density, between-brackets
    proportions, glow discipline (keep FROM colours, ban NEW TO hues)."""
    reasons = []
    bb = keyed.getbbox()
    if bb is None:
        return False, ["empty image"], {}
    w, hgt = bb[2] - bb[0], bb[3] - bb[1]
    m = 0.01
    if bb[0] <= round(keyed.width * m) or bb[1] <= round(keyed.height * m) \
            or bb[2] >= round(keyed.width * (1 - m)) \
            or bb[3] >= round(keyed.height * (1 - m)):
        reasons.append("touches frame edge")
    if n_comp > 1:
        reasons.append(f"{n_comp} creatures detected")
    density = sum(keyed.getchannel("A").histogram()[200:]) / max(1, w * hgt)
    if density < 0.15:
        reasons.append(f"too sparse ({density:.2f})")
    if hgt < 140:
        reasons.append(f"render too small (h={hgt})")

    asp = w / max(1, hgt)
    corr = sched_aspect(span, rel) / asp
    if not CORR_MIN <= corr <= CORR_MAX:
        lo = min(younger.width / younger.height, older.width / older.height)
        hi = max(younger.width / younger.height, older.width / older.height)
        where = ("older than the OLDER bracket" if asp > hi else
                 "younger than the YOUNGER bracket" if asp < lo
                 else "off-schedule")
        reasons.append(f"aspect {asp:.2f} outside brackets "
                       f"[{lo:.2f},{hi:.2f}] (corr {corr:.2f}) -- {where}")

    frac = glow_family_fractions(keyed)
    keep = [c for c in TIER_GLOW[span] if c and HUE_BANDS[c]]
    to_new = [c for c in TIER_GLOW[span + 1] if c and HUE_BANDS[c]
              and c not in keep]
    chroma_tot = sum(frac[c] for c in HUE_BANDS if HUE_BANDS[c])
    if keep and chroma_tot > 0:
        kept = sum(frac[c] for c in keep)
        if kept / max(1e-6, chroma_tot) < 0.55:
            reasons.append(f"glow drift from {keep}: kept={kept:.2f}")
    for c in to_new:
        if frac[c] > 0.22:
            reasons.append(f"banned {c} glow present: {frac[c]:.2f}")

    metrics = {"w": w, "h": hgt, "aspect": round(asp, 2),
               "corr": round(corr, 2),
               "glow": {k: round(v, 2) for k, v in frac.items() if v >= 0.05}}
    return not reasons, reasons, metrics


def geometric_correct(keyed: Image.Image, span: int, rel: int):
    bb = keyed.getbbox()
    crop = keyed.crop(bb)
    f = sched_aspect(span, rel) / (crop.width / crop.height)
    if not CORR_MIN <= f <= CORR_MAX:
        raise ValueError(f"correction factor {f:.2f} out of range")
    return crop.resize((round(crop.width * f), crop.height), Image.LANCZOS)


def to_strip(keyed: Image.Image, span: int, rel: int) -> Image.Image:
    bb = keyed.getbbox()
    animal = keyed.crop(bb)
    ah = round(sched_height(span, rel))
    scale = ah / animal.height
    aw = round(animal.width * scale)
    if aw > STRIP_W:
        aw = STRIP_W
        ah = round(animal.height * (aw / animal.width))
    animal = animal.resize((aw, ah), Image.LANCZOS)
    canvas = Image.new("RGBA", (STRIP_W, STRIP_H), (0, 0, 0, 0))
    canvas.paste(animal, (STRIP_W - aw, (STRIP_H - ah) // 2), animal)
    return canvas


# ----------------------------------------------------------------------------
# slots
# ----------------------------------------------------------------------------

def keyed_path(point: int) -> Path:
    return RAW_DIR / f"keyed_p{point:02d}.png"


def strip_name(span: int, point: int) -> str:
    return f"tier_bar_lizard_m{span}_p{point:02d}.png"


def span_of_point(point: int) -> int:
    for t in range(TIERS):
        if BOUNDARIES[t] <= point < BOUNDARIES[t + 1]:
            return t
    raise ValueError(f"point {point} out of range")


def valid_png(path: Path) -> bool:
    if not path.exists() or path.stat().st_size == 0:
        return False
    try:
        with Image.open(path) as im:
            im.verify()
        return True
    except Exception:
        return False


def slot_image(span: int, rel: int, point: int | None = None):
    if rel <= 0:
        return milestone(span)
    if rel >= span_steps(span):
        return milestone(span + 1)
    if point is None:
        raise ValueError("interior slot needs its global point number")
    return Image.open(keyed_path(point)).convert("RGBA")


# ----------------------------------------------------------------------------
# prompt
# ----------------------------------------------------------------------------

def glow_phrase(tier: int) -> str:
    primary, secondary = TIER_GLOW[tier]
    if secondary:
        return (f"glowing {primary} primary accents with glowing "
                f"{secondary} secondary accents (cabling, indicator dots, "
                "connectors)")
    return f"all glowing accents glow {primary}"


def prompt_for(span: int, younger_img, older_img) -> str:
    keep = glow_phrase(span)
    new_primary, new_secondary = TIER_GLOW[span + 1]
    new_fam = [c for c in (new_secondary, new_primary) if c
               and HUE_BANDS[c] and c not in
               [x for x in TIER_GLOW[span] if x]]
    ban = (f" Absolutely no {' nor '.join(new_fam)} accents anywhere -- "
           "the glow palette never changes with age." if new_fam else
           " Do not introduce any new accent colours.")
    return (
        "You are given TWO separate reference images. EACH shows exactly "
        "ONE chameleon. They depict the SAME robotic mecha-chameleon "
        "character (silver gunmetal segmented armour plates, domed head, "
        "one large round camera eye, small clawed feet, coiled spiral "
        "tail, detailed sci-fi mecha concept-art style) at two different "
        "AGES.\n"
        "IMAGE 1 (first image): the YOUNGER age.\n"
        "IMAGE 2 (second image): the OLDER age.\n\n"
        "TASK: draw this exact same chameleon at the age EXACTLY BETWEEN "
        "these two -- precisely halfway between the younger and the "
        "older.\n"
        "He must be a believable 50/50 age-blend of IMAGE 1 and IMAGE 2: "
        "take IMAGE 1's body and move it exactly halfway toward IMAGE 2 -- "
        "legs halfway in length, torso halfway as long, neck halfway as "
        "defined, head halfway as large relative to the body, spiral tail "
        "coil halfway as open, overall build halfway between the younger "
        "and older builds. He must look clearly OLDER than IMAGE 1 and "
        "clearly YOUNGER than IMAGE 2.\n\n"
        "STRICT RULES:\n"
        "- Exactly ONE chameleon alone in the output. No duplicates, no "
        "reflections, no miniature copies, no extra creatures, and never "
        "draw the two references side by side.\n"
        f"- GLOW (unchanged from IMAGE 1): {keep}.{ban}\n"
        "- Keep the identical art style, materials, panel linework, "
        "rivets, side-profile walking pose facing left, and individual "
        "identity of the reference.\n"
        "- No text, watermarks, logos, ground, shadows, props or "
        "scenery.\n"
        f"- CRITICAL BACKGROUND RULE: the background must be one single "
        f"constant pure flat colour: exactly {CHROMA_HEX} ({CHROMA_NAME}), "
        "no gradient, no vignette, no texture, no glow spilling into it. "
        "Every pixel that is not the robot must be exactly that colour, "
        "all the way to the edges.\n"
        "- Full body visible, centred, nothing cropped, generous margin of "
        "pure background on all four sides.\n"
        "Output the finished illustration."
    )


# ----------------------------------------------------------------------------
# one blend, best-of-N by neighbour similarity
# ----------------------------------------------------------------------------

def ensure_blend(span: int, a: int, mid: int, b: int, api_key: str,
                 attempts: int, dry_run: bool) -> None:
    steps = span_steps(span)
    point = BOUNDARIES[span] + mid
    if valid_png(keyed_path(point)):
        print(f"[m{span}p{point:02d}] cached", flush=True)
        return
    canvas_size = ref_canvas(span)
    younger = slot_image(span, a, BOUNDARIES[span] + a)
    older = slot_image(span, b, BOUNDARIES[span] + b)
    ref_young = on_chroma(younger, round(sched_height(span, a)), canvas_size)
    ref_old = on_chroma(older, round(sched_height(span, b)), canvas_size)
    prompt = prompt_for(span, younger, older)
    (RAW_DIR / f"prompt_p{point:02d}.txt").write_text(prompt)
    if dry_run:
        print(f"[m{span}p{point:02d}] PROMPT ({a},{b}):\n{prompt}\n",
              flush=True)
        return
    ref_young.save(RAW_DIR / f"ref_young_p{point:02d}.png")
    ref_old.save(RAW_DIR / f"ref_old_p{point:02d}.png")

    best = None          # (iou, keyed)
    for at in range(1, attempts + 1):
        raw = edit_image(prompt, [ref_young, ref_old], api_key)
        raw.save(RAW_DIR / f"raw_p{point:02d}_a{at}.png")
        keyed, n_comp = chroma_key(raw)
        ok, reasons, metrics = validate_blend(keyed, n_comp, younger, older,
                                              span, mid)
        iou = min(silhouette_iou(keyed, younger),
                  silhouette_iou(keyed, older))
        print(f"[m{span}p{point:02d}] attempt {at} ({a},{b}): "
              f"iou={iou:.3f} {metrics}"
              f"{'' if ok else '  REJECT: ' + '; '.join(reasons)}",
              flush=True)
        if ok:
            if iou >= ACCEPT_IOU:
                best = (iou, keyed)
                break
            if best is None or iou > best[0]:
                best = (iou, keyed)
        time.sleep(1.0)
    if best is None:
        raise RuntimeError(f"blend m{span}p{point:02d}: all {attempts} "
                           f"attempts rejected")
    exact = geometric_correct(best[1], span, mid)
    exact.save(keyed_path(point))
    print(f"[m{span}p{point:02d}] accepted (iou {best[0]:.3f}) -> aspect "
          f"{sched_aspect(span, mid):.3f}", flush=True)


def build_ladder(spans):
    for span in spans:
        steps = span_steps(span)
        for rel in range(1, steps):
            point = BOUNDARIES[span] + rel
            if not valid_png(keyed_path(point)):
                raise RuntimeError(f"blend {point} missing")
            strip = to_strip(Image.open(keyed_path(point)).convert("RGBA"),
                             span, rel)
            out = OUT_DIR / strip_name(span, point)
            strip.save(out, "PNG")
        print(f"[span {span}->{span + 1}] strips rebuilt", flush=True)


def verify_ladder(spans) -> int:
    ok = True
    for span in spans:
        steps = span_steps(span)
        for rel in range(1, steps):
            point = BOUNDARIES[span] + rel
            p = OUT_DIR / strip_name(span, point)
            if not valid_png(p):
                print(f"[m{span}p{point:02d}] MISSING/INVALID")
                ok = False
                continue
            im = Image.open(p).convert("RGBA")
            bb = im.getbbox()
            w, hgt = bb[2] - bb[0], bb[3] - bb[1]
            asp, a_exp = w / max(1, hgt), sched_aspect(span, rel)
            h_exp = sched_height(span, rel)
            frac = glow_family_fractions(im.crop(bb))
            keep = [c for c in TIER_GLOW[span] if c and HUE_BANDS[c]]
            to_new = [c for c in TIER_GLOW[span + 1] if c and HUE_BANDS[c]
                      and c not in keep]
            chroma_tot = sum(frac[c] for c in HUE_BANDS if HUE_BANDS[c])
            issues = []
            if abs(asp - a_exp) > 0.03:
                issues.append(f"asp {asp:.3f} != {a_exp:.3f}")
            if abs(hgt - h_exp) > 6:
                issues.append(f"h {hgt} != {h_exp:.0f}")
            if keep and chroma_tot and \
                    sum(frac[c] for c in keep) / chroma_tot < 0.45:
                issues.append(f"glow drift from {keep}")
            for c in to_new:
                if frac[c] > 0.30:
                    issues.append(f"banned {c}: {frac[c]:.2f}")
            if issues:
                ok = False
                print(f"[m{span}p{point:02d}] FAIL: {'; '.join(issues)}")
            else:
                print(f"[m{span}p{point:02d}] ok (h={hgt} asp={asp:.3f})")
    print("LADDER:", "PASS" if ok else "FAIL")
    return 0 if ok else 1


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--span", type=int, default=-1,
                    help="tier pair start N (N->N+1); default all spans")
    ap.add_argument("--only", type=int, default=-1,
                    help="redo one global point value")
    ap.add_argument("--attempts", type=int, default=4)
    ap.add_argument("--build-only", action="store_true")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    spans = [args.span] if args.span != -1 else list(range(TIERS))

    if args.verify:
        sys.exit(verify_ladder(spans))
    if args.build_only:
        build_ladder(spans)
        sys.exit(verify_ladder(spans))

    api_key = ""
    if not args.dry_run:
        api_key = API_KEY_FILE.read_text().strip()
        if not api_key:
            print("ERROR: empty API key", file=sys.stderr)
            sys.exit(1)

    if args.only != -1:
        span = span_of_point(args.only)
        rel = args.only - BOUNDARIES[span]
        tree = [(a, m, b) for a, m, b in bracket_tree(span_steps(span))
                if m == rel]
        if not tree:
            print(f"point {args.only} is a milestone, not a blend",
                  file=sys.stderr)
            sys.exit(1)
        a, m, b = tree[0]
        ensure_blend(span, a, m, b, api_key, args.attempts, args.dry_run)
        if not args.dry_run:
            strip = to_strip(Image.open(keyed_path(args.only))
                             .convert("RGBA"), span, m)
            strip.save(OUT_DIR / strip_name(span, args.only), "PNG")
        return

    for span in spans:
        print(f"=== span {span}->{span + 1} "
              f"({glow_phrase(span)}) ===", flush=True)
        for a, m, b in bracket_tree(span_steps(span)):
            ensure_blend(span, a, m, b, api_key, args.attempts, args.dry_run)
        if not args.dry_run:
            build_ladder([span])

    if not args.dry_run:
        sys.exit(verify_ladder(spans))


if __name__ == "__main__":
    main()
