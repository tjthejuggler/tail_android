#!/usr/bin/env python3
"""Upgrade the ascended white-combo lizard tiers (7-12) strip artwork.

Problem this fixes (user feedback):
  * t7 was a flat hue-recolor of t6 (same geometry, zero new features).
  * The t7..t12 ladder did not monotonically escalate in magnificence.
  * t10/t11/t12 had GREEN ornament contamination on non-green tiers.

What it does:
  * Uses the CURRENT tier-6 white elder (user-approved) as the identity
    reference and regenerates t7..t12 as an strictly ascending ascension
    ladder with a cumulative feature grammar (every tier keeps all the
    previous tier's features and adds new ones).
  * Only rewrites app/src/main/res/drawable-nodpi/tier_bar_lizard_t{7..12}.png
    (2048x512 RGBA, right-anchored, user-matte alpha).  POSES, THE MANIFEST
    AND TIERS 0-6 ARE NEVER TOUCHED.
  * Old strips are backed up once to wallpaper_gen/raw/ascend_backup/.

Pipeline (proven pieces from gen_lizard_ages.py + gen_lizard_poses.py):
  t6 strip -> crop -> flat chroma canvas (2048x720) -> EDIT
  primary:   /v1/chat/completions  google/gemini-3-pro-image  (Nano Banana Pro)
  fallback:  /v1/images/generations nano-banana-2-edit
  -> hue-based chroma key -> validation (aspect / edge-touch / accent
  discipline incl. anti-green gate) -> black 2048x512 strip, right-anchored,
  stretch ladder -> black flood-fill user matte -> final RGBA.

Usage:  python3 upgrade_ascended_lizards.py [tier ...]   (default 7..12)
"""
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
RAW_DIR = BASE / "raw" / "ascend"
BACKUP_DIR = BASE / "raw" / "ascend_backup"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
REF_STRIP = OUT_DIR / "tier_bar_lizard_t6.png"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

CHAT_URL = "https://api.ppq.ai/v1/chat/completions"
CHAT_MODEL = "google/gemini-3-pro-image"          # Nano Banana Pro (best editor)
GEN_URL = "https://api.ppq.ai/v1/images/generations"
GEN_MODEL = "nano-banana-2-edit"                   # proven fallback
MAX_RETRIES = 4

STRIP_W, STRIP_H = 2048, 512
CANVAS_W, CANVAS_H = 2048, 720                     # wide reference canvas

CHROMA_CANDIDATES = [
    ("pure cyan", (0, 255, 255)),
    ("pure magenta", (255, 0, 255)),
    ("electric blue", (0, 0, 255)),
    ("pure red", (255, 0, 0)),
    ("pure yellow", (255, 255, 0)),
    ("electric green", (0, 255, 0)),
]

# tier -> (accent phrase, hue band for validation, stretch, length phrase)
TIERS = {
    7:  ("glowing crimson red",      (350, 12),  1.28, "noticeably longer"),
    8:  ("glowing warm orange",      (15, 55),   1.30, "longer still"),
    9:  ("glowing emerald green",    (80, 170),  1.31, "strikingly longer"),
    10: ("glowing electric blue",    (195, 255), 1.32, "extraordinarily long"),
    11: ("glowing magenta pink",     (285, 345), 1.33, "magnificently serpentine"),
    12: ("glowing radiant gold",     (40, 65),   1.34, "colossally long, horizon-spanning"),
}

IDENTITY = (
    "This exact robot is a metallic mecha-chameleon: a side-profile android "
    "reptile built of segmented silver gunmetal armour plates, with softly "
    "rounded organic silhouette lines, a domed sensor head with one large "
    "circular glowing camera eye, small clawed gripping feet, and its "
    "signature tightly coiled spiral tail made of tapered armour segments. "
    "It must remain unmistakably THE SAME individual robot: same head shape, "
    "same eye design, same coiled spiral tail with the same number of visible "
    "coil turns, same silver-and-dark-metal material palette, same detailed "
    "sci-fi mecha concept-art style with clean specular highlights and soft "
    "emissive glow. Exactly ONE chameleon, no duplicates, no reflections, no "
    "other creatures, no text, no watermarks, no ground, no scenery."
)

BG_RULE = (
    "CRITICAL BACKGROUND RULE: the background behind the robot must be one "
    "single constant pure flat colour: exactly {hex} ({name}), with absolutely "
    "no gradient, no vignette, no texture, no glow spilling into it, no "
    "shadows on it, no environment. Every pixel that is not the robot itself "
    "must be exactly that one solid constant colour, all the way to the "
    "image edges."
)

COLOUR_RULE = (
    "COLOUR DISCIPLINE: the ONLY colours in the whole image are silver/gunmetal "
    "metal, pure luminous WHITE glow, and {accent}. Its primary glowing "
    "elements (eye core, torso conduits, main tail strips, flank bars) glow "
    "pure luminous white; its secondary accents (cabling, indicator dots, "
    "connectors, trim lines) and ALL new ornaments glow {accent}. There must "
    "be absolutely NO other hue anywhere in the image."
)

SPAN_RULE = (
    "COMPOSITION: the robot is EXTREMELY elongated horizontally -- an epic "
    "panoramic serpentine silhouette whose snout reaches near the left edge "
    "and whose coiled tail tip reaches near the right edge, spanning at least "
    "90% of the image width in one continuous sweeping S-curve of body. Its "
    "height stays modest: never taller than in the reference image, all "
    "growth is strictly lengthwise. Nothing may be cropped by the frame. "
)

# Cumulative ascension grammar: each tier's feature text INCLUDES all
# previous tiers' features, refined and amplified.
FEATURES = {
    7: (
        "ASCENSION RANK 1: CRIMSON WARRIOR. Elevate this serene white elder "
        "into a warrior form: angular blade-like crest fins of polished "
        "gunmetal rise along its spine and along the outer edge of its tail "
        "coils, each blade edged with {accent} light; glowing {accent} energy "
        "veins trace the panel seams across its torso and haunches; a thin "
        "{accent} ring surrounds its big circular white eye core; layered "
        "warrior pauldrons cap its shoulders. It looks disciplined, battle-"
        "ready and far more impressive than the plain elder."
    ),
    8: (
        "ASCENSION RANK 2: FORGE-MASTER. Everything the warrior had, now "
        "heavier and refined: twin rows of blade crests along spine and tail, "
        "seam energy veins, the eye ring, and massive layered pauldrons. NEW: "
        "a molten furnace core glows {accent} in its chest behind armoured "
        "ribs; small {accent} forge sparks float in the air around its body; "
        "small exhaust vents stud its tail coils, venting faint {accent} "
        "ember light. It looks like a living foundry, grander than the "
        "warrior."
    ),
    9: (
        "ASCENSION RANK 3: GROVE-MAGE. It keeps the forge-master's twin crest "
        "rows, seam veins, eye ring, furnace-chest core and pauldrons, now "
        "elegant and organic. NEW: a tall translucent canopy of large fin-"
        "like fronds blooms along its back, each frond edged with {accent} "
        "light; delicate {accent} filigree vines wrap around its tail coils; "
        "tiny glowing {accent} spore motes drift in the air around its body. "
        "It looks like a living grove-sage, more wondrous than the forge-"
        "master."
    ),
    10: (
        "ASCENSION RANK 4: STORM-SAVANT. It keeps the grove-mage's crest "
        "canopy, seam veins, eye ring, chest core, pauldrons and tail vines, "
        "now crystalline and electric. NEW: glowing {accent} halo rings "
        "encircle its tail coils; crackling {accent} lightning arcs leap "
        "between its crest fins; small crystalline {accent} shards slowly "
        "orbit its body. It looks like a walking storm deity, more awe-"
        "inspiring than the grove-mage."
    ),
    11: (
        "ASCENSION RANK 5: HEART-MENDER. It keeps the storm-savant's canopy, "
        "halo rings, lightning fins, seam veins, eye ring, chest core, "
        "pauldrons, vines and shards, now softened into grace. NEW: a lotus "
        "corona of broad metallic petals blooms around its head, each petal "
        "tipped with {accent} glow; soft flowing {accent} aurora ribbons "
        "undulate along the entire length of its body; its chest core becomes "
        "a radiant gem-like heart. It looks serenely divine, more beautiful "
        "than the storm-savant."
    ),
    12: (
        "ASCENSION RANK 6: SUN-KING, THE PINNACLE OF THE ENTIRE SERIES. It "
        "keeps every feature of all previous ranks -- canopy, halo rings, "
        "lightning fins, seam veins, eye ring, chest core, pauldrons, vines, "
        "shards, petal corona and aurora ribbons -- all now resplendent in "
        "gold. NEW: a solar crown of {accent} rays blazes around its head; "
        "multiple large concentric {accent} halo rings stack behind its body; "
        "ornate golden filigree cape-plates drape along its back. It is the "
        "single most radiant, ornate, breathtaking, awe-inspiring design of "
        "the whole ascension -- a deific sun-monarch."
    ),
}


# ----------------------------------------------------------------------------
# reference + chroma
# ----------------------------------------------------------------------------

def load_reference() -> Image.Image:
    im = Image.open(REF_STRIP).convert("RGBA")
    bb = im.getbbox()
    if bb is None:
        raise RuntimeError("reference t6 strip has no content")
    return im.crop(bb)


def chroma_for(lizard: Image.Image, avoid_word: str):
    px = lizard.resize((128, 128)).load()
    samples = [px[x, y][:3] for y in range(128) for x in range(128)
               if px[x, y][3] > 200]
    best, best_score = None, -1
    for name, c in CHROMA_CANDIDATES:
        if avoid_word in name:
            continue
        score = min((abs(c[0] - r) + abs(c[1] - g) + abs(c[2] - b)
                     for r, g, b in samples), default=0)
        if score > best_score:
            best, best_score = (name, c), score
    return best


def ref_on_chroma(lizard: Image.Image, chroma) -> Image.Image:
    canvas = Image.new("RGB", (CANVAS_W, CANVAS_H), chroma)
    scale = min(CANVAS_W / lizard.width, (CANVAS_H * 0.94) / lizard.height)
    lz = lizard.resize((max(1, round(lizard.width * scale)),
                        max(1, round(lizard.height * scale))), Image.LANCZOS)
    canvas.paste(lz, ((CANVAS_W - lz.width) // 2, (CANVAS_H - lz.height) // 2),
                 lz)
    return canvas


def build_prompt(tier: int, accent: str, chroma_name: str, chroma_hex: str,
                 length: str) -> str:
    bg = BG_RULE.format(hex=chroma_hex, name=chroma_name)
    col = COLOUR_RULE.format(accent=accent)
    feat = FEATURES[tier].format(accent=accent)
    length_rule = (
        f"LENGTH: this form is {length} than the reference elder from snout "
        "to tail-tip, and grander and more ornate than every previous rank."
    )
    return (f"{feat} {IDENTITY} {col} {SPAN_RULE} {length_rule} {bg} "
            f"Output the finished illustration.")


# ----------------------------------------------------------------------------
# generation (two proven transports)
# ----------------------------------------------------------------------------

def post_json(url, payload, headers, timeout=300):
    req = urllib.request.Request(
        url, data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", **headers}, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def download(url):
    with urllib.request.urlopen(urllib.request.Request(url), timeout=120) as r:
        return r.read()


def b64_png(im: Image.Image) -> str:
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return base64.b64encode(buf.getvalue()).decode()


def parse_image_bytes(resp: dict) -> bytes:
    msg = resp["choices"][0]["message"]
    images = msg.get("images") or []
    if images:
        item = images[0]
        url = item.get("image_url", {}).get("url") if isinstance(item, dict) \
            else None
        if url and url.startswith("data:"):
            return base64.b64decode(url.split(",", 1)[1])
        if url:
            return download(url)
        if isinstance(item, dict) and item.get("b64_json"):
            return base64.b64decode(item["b64_json"])
    audio = msg.get("audio") or {}
    if audio.get("data"):
        return base64.b64decode(audio["data"])
    raise ValueError(f"no image in response: {str(resp)[:200]}")


def gen_via_chat(prompt, data_url, api_key):
    payload = {
        "model": CHAT_MODEL,
        "messages": [{
            "role": "user",
            "content": [
                {"type": "image_url", "image_url": {"url": data_url}},
                {"type": "text", "text": prompt},
            ],
        }],
    }
    return parse_image_bytes(post_json(CHAT_URL, payload,
                                       {"Authorization": f"Bearer {api_key}"}))


def gen_via_images(prompt, data_url, api_key):
    payload = {
        "model": GEN_MODEL, "prompt": prompt, "image_url": data_url,
        "aspect_ratio": "21:9", "n": 1,
    }
    resp = post_json(GEN_URL, payload, {"Authorization": f"Bearer {api_key}"})
    data = resp.get("data") or []
    if not data:
        raise ValueError(f"no data: {str(resp)[:200]}")
    item = data[0]
    if item.get("b64_json"):
        return base64.b64decode(item["b64_json"])
    if item.get("url"):
        return download(item["url"])
    raise ValueError("no b64_json/url in item")


def edit_image(prompt, src: Image.Image, api_key) -> Image.Image:
    data_url = f"data:image/png;base64,{b64_png(src)}"
    last = None
    for attempt in range(1, MAX_RETRIES + 1):
        for fn in (gen_via_chat, gen_via_images):
            try:
                raw = fn(prompt, data_url, api_key)
                im = Image.open(io.BytesIO(raw)).convert("RGB")
                im.load()
                return im
            except (urllib.error.HTTPError, urllib.error.URLError,
                    TimeoutError, ValueError, OSError, KeyError) as e:
                last = e
                print(f"    {fn.__name__} failed: {e}", flush=True)
        wait = min(2 ** attempt, 60)
        print(f"  attempt {attempt}/{MAX_RETRIES} failed; retry in {wait}s",
              flush=True)
        time.sleep(wait)
    raise RuntimeError(f"edit failed after {MAX_RETRIES} attempts: {last}")


# ----------------------------------------------------------------------------
# chroma key -> alpha (hue based, proven from gen_lizard_ages.py)
# ----------------------------------------------------------------------------

def chroma_key(im: Image.Image, chroma, tol=60) -> Image.Image:
    w, h = im.size
    rgb = im.convert("RGB")
    px = rgb.load()
    cr, cg, cb = chroma

    def is_key(x, y):
        r, g, b = px[x, y]
        return (abs(r - cr) + abs(g - cg) + abs(b - cb)) <= tol

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

    r2 = tol * 1.6
    for y in range(h):
        for x in range(w):
            if ap[x, y]:
                rr, gg, bb = px[x, y]
                if (abs(rr - cr) + abs(gg - cg) + abs(bb - cb)) <= r2:
                    ap[x, y] = 0

    seen = bytearray(w * h)
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
            if len(comp) < 64:
                for x, y in comp:
                    ap[x, y] = 0

    mask = alpha.point(lambda v: 255 if v > 0 else 0)
    mask = mask.filter(ImageFilter.MinFilter(5))
    mask = mask.filter(ImageFilter.GaussianBlur(1.2))
    out = rgb.copy().convert("RGBA")
    out.putalpha(mask)
    return out


# ----------------------------------------------------------------------------
# validation + strip composition
# ----------------------------------------------------------------------------

def aspect_of(keyed: Image.Image) -> float:
    bb = keyed.getbbox()
    if not bb:
        return 0.0
    return (bb[2] - bb[0]) / max(1, bb[3] - bb[1])


def touches_edge(keyed: Image.Image, m=0.01) -> bool:
    """VERTICAL cropping only: the span prompt demands the snout/tail to
    reach the horizontal edges, so left/right contact is expected."""
    bb = keyed.getbbox()
    h = keyed.size[1]
    return bb[1] <= round(h * m) or bb[3] >= round(h * (1 - m))


def accent_stats(keyed: Image.Image, lo, hi):
    """Return (accent_frac, green_frac) over OPAQUE saturated bright pixels.
    The hue band may wrap past 360 (e.g. 350..12)."""
    small = keyed.resize((256, 144))
    accent = green = total = 0
    for (r, g, b, a) in small.getdata():
        if a < 128:
            continue
        hh, ss, vv = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
        if ss > 0.40 and vv > 0.40:
            total += 1
            deg = hh * 360
            in_band = (lo <= deg <= hi) if lo <= hi else (deg >= lo or deg <= hi)
            if in_band:
                accent += 1
            elif 80 <= deg <= 170:
                green += 1
    if total == 0:
        return 0.0, 0.0
    return accent / total, green / total


CYAN_LO, CYAN_HI = 168, 262   # chroma-key spill band (pure cyan key + glow tint)
GREEN_LO, GREEN_HI = 80, 167  # mint/green contamination band


def despill(keyed: Image.Image, accent_band) -> Image.Image:
    """Re-hue cyan/green-band pixels to the tier accent, keeping sat/val.
    Fixes chroma-key spill: cyan flecks on t8, cyan ribbon edges on t11,
    mint crown rays and halo rings on t12.  Skipped for blue-tier t10 whose
    accent legitimately lives inside the cyan band, and for green-tier t9.
    Low-saturation silver/white metal is untouched."""
    lo, hi = accent_band
    width = (hi - lo) % 360.0 or 360.0
    target = (lo + width / 2.0) % 360.0
    out = keyed.copy()
    opx = out.load()
    w, h = keyed.size
    for y in range(h):
        for x in range(w):
            r, g, b, a = opx[x, y]
            if a == 0:
                continue
            hh, ss, vv = colorsys.rgb_to_hsv(r / 255, g / 255, b / 255)
            deg = hh * 360.0
            if ss > 0.25 and vv > 0.15 and \
                    ((CYAN_LO <= deg <= CYAN_HI)
                     or (GREEN_LO <= deg <= GREEN_HI)):
                nr, ng, nb = colorsys.hsv_to_rgb(target / 360.0, ss, vv)
                opx[x, y] = (round(nr * 255), round(ng * 255),
                             round(nb * 255), a)
    return out


def user_matte(rgb_im: Image.Image) -> Image.Image:
    """The user's recipe: binary flood fill of near-black (<8) from the
    borders -> alpha 0; everything else opaque. No feather."""
    w, h = rgb_im.size
    px = rgb_im.load()

    def is_bg(x, y):
        r, g, b = px[x, y]
        return max(r, g, b) < 8

    void = bytearray(w * h)
    q = deque()
    for x in range(w):
        for y in (0, h - 1):
            if is_bg(x, y):
                void[y * w + x] = 1
                q.append((x, y))
    for y in range(h):
        for x in (0, w - 1):
            if is_bg(x, y):
                void[y * w + x] = 1
                q.append((x, y))
    while q:
        x, y = q.popleft()
        for nx, ny in ((x - 1, y), (x + 1, y), (x, y - 1), (x, y + 1)):
            if 0 <= nx < w and 0 <= ny < h:
                i = ny * w + nx
                if not void[i] and is_bg(nx, ny):
                    void[i] = 1
                    q.append((nx, ny))
    out = rgb_im.convert("RGBA")
    opx = out.load()
    for y in range(h):
        for x in range(w):
            if void[y * w + x]:
                r, g, b, _ = opx[x, y]
                opx[x, y] = (r, g, b, 0)
    return out


def to_strip(keyed: Image.Image, stretch: float) -> Image.Image:
    bb = keyed.getbbox()
    animal = keyed.crop(bb)
    if stretch > 1.0:
        animal = animal.resize((round(animal.width * stretch), animal.height),
                               Image.LANCZOS)
    scale = STRIP_H / animal.height
    aw = round(animal.width * scale)
    if aw > STRIP_W:
        scale = STRIP_W / animal.width
        animal = animal.resize((STRIP_W, round(animal.height * scale)),
                               Image.LANCZOS)
        aw = STRIP_W
    else:
        animal = animal.resize((aw, STRIP_H), Image.LANCZOS)
    canvas = Image.new("RGB", (STRIP_W, STRIP_H), (0, 0, 0))
    canvas.paste(animal, (STRIP_W - aw, (STRIP_H - STRIP_H) // 2), animal)
    return user_matte(canvas)


def backup_existing(tier: int):
    src = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
    if not src.exists():
        return
    dst = BACKUP_DIR / src.name
    if not dst.exists():  # keep the ORIGINAL, never overwrite the backup
        dst.write_bytes(src.read_bytes())
        print(f"  backed up original -> {dst.name}", flush=True)


# ----------------------------------------------------------------------------

def upgrade_tier(tier: int, api_key: str, reprocess: bool = False) -> bool:
    accent, (lo, hi), stretch, length = TIERS[tier]
    avoid = {"glowing crimson red": "red", "glowing warm orange": "yellow",
             "glowing emerald green": "green", "glowing electric blue": "blue",
             "glowing magenta pink": "red", "glowing radiant gold": "yellow"}[accent]
    ref = load_reference()
    name, chroma = chroma_for(ref, avoid)
    hexc = "#{:02X}{:02X}{:02X}".format(*chroma)
    print(f"[t{tier:02d}] accent={accent} chroma={name} {hexc}", flush=True)

    RAW_DIR.mkdir(parents=True, exist_ok=True)
    BACKUP_DIR.mkdir(parents=True, exist_ok=True)
    src = ref_on_chroma(ref, chroma)
    src.save(RAW_DIR / f"src_t{tier}.png")
    prompt = build_prompt(tier, accent, name, hexc, length)
    (RAW_DIR / f"prompt_t{tier}.txt").write_text(prompt)

    want_aspect = 2.3
    attempts = 3
    best, best_asp = None, -1.0
    for a in range(1, attempts + 1):
        cached = RAW_DIR / f"raw_t{tier}_a{a}.png"
        if reprocess and cached.exists():
            raw = Image.open(cached).convert("RGB")
        else:
            raw = edit_image(prompt, src, api_key)
            raw.save(cached)
        keyed = chroma_key(raw, chroma)
        if keyed.getbbox() is None:
            print(f"  attempt {a}: chroma key removed everything", flush=True)
            continue
        if accent != "glowing electric blue":
            keyed = despill(keyed, (lo, hi))
        keyed.save(RAW_DIR / f"keyed_t{tier}_a{a}.png")
        asp = aspect_of(keyed)
        edge = touches_edge(keyed)
        af, gf = accent_stats(keyed, lo, hi)
        print(f"  attempt {a}: aspect={asp:.2f} edge_touch={edge} "
              f"accent={af:.2f} green={gf:.2f}", flush=True)
        # colour discipline: accent must dominate; green banned unless accent
        clean = af >= 0.30 and (gf < 0.08 or accent == "glowing emerald green")
        if edge or not clean:
            if best is None:
                best, best_asp = keyed, asp  # last resort
            continue
        if asp > best_asp:
            best, best_asp = keyed, asp
        if asp >= want_aspect:
            break
        time.sleep(1.0)

    if best is None:
        print(f"[t{tier:02d}] FAILED: no usable attempt", flush=True)
        return False
    best.save(RAW_DIR / f"keyed_t{tier}.png")
    backup_existing(tier)
    out = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
    to_strip(best, stretch).save(out, "PNG")
    print(f"[t{tier:02d}] saved {out.name} {out.stat().st_size} bytes "
          f"(aspect {best_asp:.2f})", flush=True)
    return True


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    reprocess = "--reprocess" in sys.argv
    tiers = [int(a) for a in args] or [7, 8, 9, 10, 11, 12]
    api_key = API_KEY_FILE.read_text().strip()
    if not api_key and not reprocess:
        print("ERROR: empty API key", file=sys.stderr)
        sys.exit(1)
    failed = []
    for t in tiers:
        if t not in TIERS:
            print(f"skip t{t}: not an ascended tier", flush=True)
            continue
        try:
            if not upgrade_tier(t, api_key, reprocess):
                failed.append(t)
        except Exception as e:
            failed.append(t)
            print(f"[t{t:02d}] FAILED: {e}", flush=True)
        time.sleep(1.5)
    if failed:
        print(json.dumps({"failed": failed}), flush=True)
        sys.exit(2)
    print("all requested tiers upgraded", flush=True)


if __name__ == "__main__":
    main()
