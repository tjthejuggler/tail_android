#!/usr/bin/env python3
"""Age-progressed lizard tier artwork for the home-screen widget.

Every tier is generated from the CURRENT pink lizard (tier 4), exactly as it
appears in app/src/main/res/drawable-nodpi/tier_bar_lizard_t4.png, so the
identity stays continuous.  Tier 4 itself is NEVER regenerated.

Age ladder (habit tier -> life stage):
  0 red     hatchling, maximum neoteny / cuteness
  1 orange  toddler
  2 green   juvenile
  3 blue    adolescent
  4 pink    ADULT  -- untouched, this is the reference
  5 yellow  prime adult, longer & more muscular (same height)
  6 white   enlightened elder, longer still
  7..12     white + accent combos: ascended forms, progressively longer,
            more ornate / futuristic / magnificent (never taller than t4)

Transparency strategy (the "smart" part):
  The widget strips carry real alpha.  The generator cannot output alpha, so
  before each request the pink lizard is composited onto a flat, constant,
  pure chroma background colour that appears NOWHERE in the lizard itself
  (verified by pixel-distance check).  The prompt explicitly demands that
  same constant pure background colour.  Afterwards the background is
  removed programmatically (flood fill from the borders + strict chroma
  pass + edge feather) and real alpha is restored, matching the existing
  widget strip format (2048x512 RGBA, right-anchored).

Model: PPQ `nano-banana-2-edit` via /v1/images/generations (image_url =
base64 data URL + prompt).  Falls back to `google/gemini-2.5-flash-image`
via /v1/chat/completions (the endpoint the earlier scripts used) if the
generations endpoint refuses data URLs.

Resumable: valid outputs are skipped.  Raw keepsakes in wallpaper_gen/raw/.
"""
import base64
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
RAW_DIR = BASE / "raw" / "ages"
OUT_DIR = BASE.parent / "app/src/main/res/drawable-nodpi"
PINK = OUT_DIR / "tier_bar_lizard_t4.png"
API_KEY_FILE = Path("/home/twain/Projects/small_scripts/ppq_imageGen_apikey.txt")

GEN_URL = "https://api.ppq.ai/v1/images/generations"
GEN_MODEL = "nano-banana-2-edit"
CHAT_URL = "https://api.ppq.ai/v1/chat/completions"
CHAT_MODEL = "google/gemini-2.5-flash-image"
MAX_RETRIES = 5

STRIP_W, STRIP_H = 2048, 512

# Chroma candidates: garish, fully saturated colours.  The script picks, per
# tier, the candidate with the greatest minimum distance to every sampled
# pixel of the pink lizard, and additionally avoids the tier's own accent
# colour so the glow never matches the key.
CHROMA_CANDIDATES = [
    ("electric green", (0, 255, 0)),
    ("pure cyan", (0, 255, 255)),
    ("pure magenta", (255, 0, 255)),
    ("electric blue", (0, 0, 255)),
    ("pure yellow", (255, 255, 0)),
    ("pure red", (255, 0, 0)),
]

# tier -> (height factor vs pink, accent description for prompt)
# Height factor <= 1.0 always: nothing is ever taller than the pink adult.
TIERS = {
    0:  (0.52, "red",
         "glowing red"),
    1:  (0.68, "orange",
         "glowing warm orange"),
    2:  (0.80, "green",
         "glowing green"),
    3:  (0.92, "blue",
         "glowing blue"),
    5:  (1.00, "yellow",
         "glowing yellow"),
    6:  (1.00, "white",
         "glowing white"),
    7:  (1.00, "white+red",
         "glowing white primary with glowing red secondary accents"),
    8:  (1.00, "white+orange",
         "glowing white primary with glowing warm orange secondary accents"),
    9:  (1.00, "white+green",
         "glowing white primary with glowing green secondary accents"),
    10: (1.00, "white+blue",
         "glowing white primary with glowing blue secondary accents"),
    11: (1.00, "white+pink",
         "glowing white primary with glowing pink secondary accents"),
    12: (1.00, "white+yellow",
         "glowing white primary with glowing golden yellow secondary accents"),
}


# ----------------------------------------------------------------------------
# shared helpers
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


# ----------------------------------------------------------------------------
# reference (pink) lizard handling
# ----------------------------------------------------------------------------

def load_pink_lizard():
    """Return the pink lizard as tight-cropped RGBA from the t4 strip."""
    im = Image.open(PINK).convert("RGBA")
    bbox = im.getbbox()  # non-zero (incl. alpha) bounding box
    return im.crop(bbox)


def chroma_for(lizard: Image.Image, avoid_names):
    """Pick the chroma colour with max min-distance to lizard pixels,
    skipping candidates whose name overlaps the tier's accent colour."""
    px = lizard.resize((128, 128)).load()
    samples = [px[x, y][:3] for y in range(128) for x in range(128)
               if px[x, y][3] > 200]
    best, best_score = None, -1
    for name, c in CHROMA_CANDIDATES:
        if any(a in name for a in avoid_names):
            continue
        score = min((abs(c[0]-r) + abs(c[1]-g) + abs(c[2]-b)
                     for r, g, b in samples), default=0)
        if score > best_score:
            best, best_score = (name, c), score
    return best


def pink_on_chroma(lizard: Image.Image, chroma, wide=False):
    """16:9 canvas (or ultra-wide for post-pink tiers), lizard centred,
    on flat chroma.  Wide canvases scale the pink lizard to fill the full
    width so the model anchors on its elongated silhouette."""
    W, H = (2048, 720) if wide else (1536, 864)
    canvas = Image.new("RGB", (W, H), chroma)
    if wide:
        scale = W / lizard.width
    else:
        scale = (H * 0.55) / lizard.height
    lz = lizard.resize((max(1, round(lizard.width * scale)),
                        max(1, round(lizard.height * scale))), Image.LANCZOS)
    canvas.paste(lz, ((W - lz.width) // 2, (H - lz.height) // 2), lz)
    return canvas


def lizard_aspect(keyed: Image.Image) -> float:
    bb = keyed.getbbox()
    if not bb:
        return 0.0
    return (bb[2] - bb[0]) / max(1, bb[3] - bb[1])


# ----------------------------------------------------------------------------
# prompts -- one masterpiece per life stage
# ----------------------------------------------------------------------------

IDENTITY = (
    "This exact robot is a metallic mecha-chameleon: a side-profile android "
    "reptile built of segmented silver gunmetal armour plates, with softly "
    "rounded organic silhouette lines, a domed sensor head with one large "
    "circular glowing camera eye, small clawed gripping feet, and its "
    "signature tightly coiled spiral tail made of tapered armour segments "
    "ending in a rounded tip. It must remain unmistakably THE SAME "
    "individual robot in every image: same head shape, same eye design, "
    "same coiled spiral tail with the same number of visible coil turns, "
    "same silver-and-dark-metal material palette, same art style (detailed "
    "sci-fi mecha concept-art illustration with clean specular highlights "
    "and soft emissive glow), same calm friendly personality. Do not change "
    "the art style, do not change the materials, do not add text, "
    "watermarks, logos, ground, shadows, props, scenery or other creatures."
)

BG_RULE = (
    "CRITICAL BACKGROUND RULE: the background behind the robot must be one "
    "single constant pure flat colour: exactly {hex} ({name}), with "
    "absolutely no gradient, no vignette, no texture, no glow spilling "
    "into it, no shadows on it, no environment. Every pixel that is not "
    "the robot itself must be exactly that one solid constant colour, "
    "all the way to the image edges."
)


def prompt_for(tier, accent, chroma_name, chroma_hex):
    bg = BG_RULE.format(hex=chroma_hex, name=chroma_name)

    if tier == 0:  # red -- hatchling
        stage = (
            "Life stage: BRAND-NEW HATCHLING BABY. Depict this EXACT same "
            "robot as an adorably tiny newborn hatchling, roughly half the "
            "adult's size, MAXIMISING neoteny and cuteness purely through "
            "PROPORTION, never through style change: oversized round head "
            "with a huge sparkling eye, chubby rounded armour segments, a "
            "fat stubby spiral tail with fewer chunkier coil turns, stubby "
            "little legs, big curious doe-eyed expression, slightly wobbly "
            "baby posture. STYLE IS SACRED: this must be rendered in the "
            "identical detailed sci-fi mecha concept-art style, the "
            "identical realistic silver gunmetal metallic materials, "
            "identical panel linework, rivets, specular highlights and "
            "emissive glow treatment as the reference adult -- the same "
            "engineering language, only proportionally babyfied. Do NOT "
            "make it a cartoon, a toy, a plush, a chibi drawing or a "
            "sticker; it is the same hard-surface industrial machine, just "
            "newly born. All of its glowing elements (eye core, torso "
            "conduits, tail strips, joint lights, flank bars) glow a warm "
            "{accent} red. Heart-melting, precious, irresistibly cute."
        )
    elif tier == 1:  # orange -- toddler
        stage = (
            "Life stage: TODDLER. The same robot a little older and a little "
            "bigger than the newborn hatchling but still clearly a baby: "
            "still cutely proportioned with a large round head, big bright "
            "eyes, soft chubby segments and a chubby spiral tail, but now "
            "standing more confidently and playfully, like a toddler taking "
            "proud waddling steps. All glowing elements (eye core, conduits, "
            "tail strips, joint lights, flank bars) glow warm {accent} "
            "orange, like late-afternoon sunset light."
        )
    elif tier == 2:  # green -- juvenile
        stage = (
            "Life stage: JUVENILE CHILD. The same robot grown into a lively "
            "young juvenile: noticeably bigger and longer than the toddler, "
            "body proportions starting to lengthen toward adulthood, head "
            "still slightly large and endearing, segments firmer and more "
            "defined, spiral tail longer with more turns, posture curious "
            "and energetic like a young explorer. All glowing elements "
            "(eye core, conduits, tail strips, joint lights, flank bars) "
            "glow a fresh {accent} green."
        )
    elif tier == 3:  # blue -- adolescent
        stage = (
            "Life stage: ADOLESCENT TEEN. The same robot as a near-grown "
            "teenager: body almost adult-sized but just slightly slimmer "
            "and lankier, a hint of gawky teenage proportions, head "
            "proportions nearly adult, armour segments defined and athletic, "
            "spiral tail nearly full length, posture confident and a little "
            "rebellious. All glowing elements (eye core, conduits, tail "
            "strips, joint lights, flank bars) glow a cool electric "
            "{accent} blue."
        )
    elif tier == 5:  # yellow -- prime adult, longer/more muscular
        stage = (
            "Life stage: PRIME ADULT, ASCENDING. The same robot at peak "
            "physical adulthood: exactly the SAME HEIGHT as the reference "
            "adult but noticeably LONGER from snout to tail-tip, its body "
            "stretched elegantly lengthwise like a grand Serpentine dragon-"
            "lizard, with broader more muscular shoulders and haunches, "
            "thicker powerful limbs, a longer more muscular neck, and a "
            "magnificently longer spiral tail with extra coil turns. It "
            "looks strong, proud and radiant. All glowing elements (eye "
            "core, conduits, tail strips, joint lights, flank bars) glow a "
            "brilliant {accent} golden yellow like inner sunlight. IMPORTANT: "
            "the robot must NOT be taller than in the reference image -- all "
            "growth is lengthwise only."
        )
    elif tier == 6:  # white -- enlightened elder
        stage = (
            "Life stage: ENLIGHTENED ELDER. The same robot, now an awakened "
            "sage: same height as the reference adult but grown even LONGER "
            "lengthwise, its elongated serene body draped in flowing "
            "segments like ceremonial robes of metal, subtle intricate "
            "filigree etched into the armour plates, gentle wisdom in its "
            "eye, the spiral tail longer and more intricate like a sacred "
            "mandala of coils. All glowing elements (eye core, conduits, "
            "tail strips, joint lights, flank bars) glow a pure luminous "
            "{accent} white radiance, softly divine. IMPORTANT: the robot "
            "must NOT be taller than in the reference image -- growth is "
            "lengthwise only."
        )
    else:  # 7..12 -- ascended white+accent forms, progressively grander
        order = tier - 6  # 1..6
        lengths = [
            "somewhat longer", "clearly longer", "strikingly long",
            "extraordinarily long and sweeping", "magnificently serpentine "
            "in length", "colossally long, an epic horizon-spanning serpentine"
            " form"]
        evolutions = [
            "with newly intricately engineered joint assemblies and faint "
            "holographic circuit etchings",
            "with layered overlapping futuristic armour, elegant energy "
            "conduits and small floating accent fragments",
            "with soaring fin-like crests, luminous filigree, graceful "
            "energy ribbons trailing along its length",
            "with magnificent ornate plating, radiant halo-like rings around "
            "the spiral tail, and crystalline light-forged details",
            "with awe-inspiring cathedral-like architecture of metal, "
            "orbiting light shards, and an aura of quiet transcendence",
            "with ultimate transcendent design: a celestial serpentine "
            "masterpiece of white armour, flowing energy veils, multiple "
            "halo rings, and an enlightened almost-deific presence",
        ]
        stage = (
            f"Life stage: ASCENDED FORM, RANK {order} of 6. The same robot "
            f"in an advanced enlightened evolution: exactly the SAME HEIGHT "
            f"as the reference adult but {lengths[order-1]} from snout to "
            f"tail-tip -- all growth is strictly lengthwise, never upward. "
            f"It is more muscular, more majestic and more impressively "
            f"futuristic than before {evolutions[order-1]}. Its primary "
            "glowing elements (eye core, torso conduits, main tail strips, "
            "flank bars) glow pure luminous white, while its secondary "
            f"accents (cabling, small indicator dots, connectors, trim "
            f"lines) glow {accent}. IMPORTANT: the robot must NOT be taller "
            "than in the reference image."
        )
        accent_txt = accent
        # for 7..12 the accent phrase is already a full description
        return build_prompt(stage, accent_txt, bg, tier)

    return build_prompt(stage, accent, bg, tier)


def build_prompt(stage, accent, bg, tier=0):
    span = ""
    if tier >= 5:
        span = (
            "COMPOSITION: the robot must be drawn EXTREMELY elongated "
            "horizontally -- an epic panoramic serpentine silhouette whose "
            "snout reaches near the left edge of the frame and whose tail "
            "tip reaches near the right edge, spanning at least 90% of the "
            "image width in one continuous sweeping S-curve of body, while "
            "its height stays modest and never exceeds the reference. "
            "Nothing cropped. "
        )
    else:
        span = ("Composition: full body visible in side profile, centred in "
                "a wide horizontal frame, nothing cropped, and leave a "
                "generous margin of pure background colour on ALL four "
                "sides -- no part of the robot may touch or be cut off by "
                "the image edge. ")
    return (
        f"{stage} {IDENTITY} Accent colours for this stage: {accent}. "
        f"{span}{bg} Output the finished illustration."
    )


# ----------------------------------------------------------------------------
# generation
# ----------------------------------------------------------------------------

def gen_via_images(prompt, data_url, api_key, aspect="16:9"):
    payload = {
        "model": GEN_MODEL,
        "prompt": prompt,
        "image_url": data_url,
        "aspect_ratio": aspect,
        "n": 1,
    }
    resp = post_json(GEN_URL, payload, {"Authorization": f"Bearer {api_key}"})
    data = resp.get("data") or []
    if not data:
        raise ValueError(f"no data: {str(resp)[:200]}")
    item = data[0]
    if item.get("b64_json"):
        return base64.b64decode(item["b64_json"])
    url = item.get("url")
    if not url:
        raise ValueError("no b64_json/url in item")
    return download(url)


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
    resp = post_json(CHAT_URL, payload, {"Authorization": f"Bearer {api_key}"})
    images = resp["choices"][0]["message"].get("images") or []
    if not images:
        raise ValueError(f"no images: {str(resp)[:200]}")
    item = images[0]
    url = item.get("image_url", {}).get("url") if isinstance(item, dict) else None
    if url and url.startswith("data:"):
        return base64.b64decode(url.split(",", 1)[1])
    if url:
        return download(url)
    if isinstance(item, dict) and item.get("b64_json"):
        return base64.b64decode(item["b64_json"])
    raise ValueError("unrecognized image item")


def edit_image(prompt, src: Image.Image, api_key, aspect="16:9") -> Image.Image:
    data_url = f"data:image/png;base64,{b64_png(src)}"
    last_err = None
    for attempt in range(1, MAX_RETRIES + 1):
        for fn in (gen_via_images, gen_via_chat):
            try:
                img_bytes = fn(prompt, data_url, api_key, aspect) \
                    if fn is gen_via_images else fn(prompt, data_url, api_key)
                im = Image.open(io.BytesIO(img_bytes)).convert("RGB")
                im.load()
                return im
            except (urllib.error.HTTPError, urllib.error.URLError,
                    TimeoutError, ValueError, OSError, KeyError) as e:
                last_err = e
                print(f"    {fn.__name__} failed: {e}", flush=True)
        wait = min(2 ** attempt, 60)
        print(f"  attempt {attempt}/{MAX_RETRIES} failed; retry in {wait}s",
              flush=True)
        time.sleep(wait)
    raise RuntimeError(f"edit failed after {MAX_RETRIES} attempts: {last_err}")


# ----------------------------------------------------------------------------
# chroma-key -> alpha
# ----------------------------------------------------------------------------

def chroma_key(im: Image.Image, chroma, tol=60) -> Image.Image:
    """Flood fill chroma from borders + strict global chroma removal,
    2px erode of the kept region and 2px feathered edge -> RGBA."""
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
        for nx, ny in ((x+1, y), (x-1, y), (x, y+1), (x, y-1)):
            if 0 <= nx < w and 0 <= ny < h and not visited[ny * w + nx] \
                    and is_key(nx, ny):
                visited[ny * w + nx] = 1
                dq.append((nx, ny))

    alpha = Image.new("L", (w, h), 255)
    ap = alpha.load()
    for y in range(h):
        for x in range(w):
            if visited[y * w + x]:
                ap[x, y] = 0
            elif is_key(x, y):
                # stray interior chroma (should not happen; safety)
                ap[x, y] = 0

    # drop leftover chroma flecks: wider tolerance around the key colour
    r2 = tol * 1.6
    for y in range(h):
        for x in range(w):
            if ap[x, y]:
                rr, gg, bb = px[x, y]
                if (abs(rr - cr) + abs(gg - cg) + abs(bb - cb)) <= r2:
                    ap[x, y] = 0

    # despeckle: remove opaque islands smaller than 64 px
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
                for nx, ny in ((x+1, y), (x-1, y), (x, y+1), (x, y-1)):
                    j = ny * w + nx
                    if 0 <= nx < w and 0 <= ny < h and not seen[j] \
                            and ap[nx, ny]:
                        seen[j] = 1
                        dq2.append((nx, ny))
            if len(comp) < 64:
                for x, y in comp:
                    ap[x, y] = 0

    # erode the kept region 2px to kill chroma fringe, then feather
    mask = alpha.point(lambda v: 255 if v > 0 else 0)
    mask = mask.filter(ImageFilter.MinFilter(5))       # 2px erosion
    mask = mask.filter(ImageFilter.GaussianBlur(1.2))  # soft edge
    out = rgb.copy().convert("RGBA")
    out.putalpha(mask)
    return out


# post-pink tiers: gentle horizontal elongation so each is longer than the
# pink adult (mechanical segmented bodies tolerate this well)
STRETCH = {5: 1.24, 6: 1.26, 7: 1.28, 8: 1.30,
           9: 1.31, 10: 1.32, 11: 1.33, 12: 1.34}


def to_strip(lizard_rgba: Image.Image, height_factor: float,
             pink_h: float, pink_w: float, stretch=1.0):
    bbox = lizard_rgba.getbbox()
    animal = lizard_rgba.crop(bbox) if bbox else lizard_rgba
    if stretch > 1.0:
        animal = animal.resize(
            (round(animal.width * stretch), animal.height), Image.LANCZOS)
    target_h = round(STRIP_H * height_factor)
    target_h = min(target_h, STRIP_H)  # NEVER taller than the strip/pink
    scale = target_h / animal.height
    # length cap: keep at most the full strip width
    aw = round(animal.width * scale)
    if aw > STRIP_W:
        aw = STRIP_W
        scale = aw / animal.width
        target_h = round(animal.height * scale)
    animal = animal.resize((aw, target_h), Image.LANCZOS)
    canvas = Image.new("RGBA", (STRIP_W, STRIP_H), (0, 0, 0, 0))
    canvas.paste(animal, (STRIP_W - aw, (STRIP_H - target_h) // 2), animal)
    return canvas


def valid_png(path: Path) -> bool:
    if not path.exists() or path.stat().st_size == 0:
        return False
    try:
        with Image.open(path) as im:
            im.verify()
        return True
    except Exception:
        return False


# ----------------------------------------------------------------------------

def main():
    only = {int(a) for a in sys.argv[1:]} if len(sys.argv) > 1 else None
    RAW_DIR.mkdir(parents=True, exist_ok=True)
    api_key = API_KEY_FILE.read_text().strip()
    if not api_key:
        print("ERROR: empty API key", file=sys.stderr)
        sys.exit(1)

    pink = load_pink_lizard()
    print(f"pink lizard: {pink.size}", flush=True)

    failures = []
    for tier in sorted(TIERS):
        out = OUT_DIR / f"tier_bar_lizard_t{tier}.png"
        if only is not None and tier not in only:
            continue
        if valid_png(out):
            print(f"[t{tier:02d}] skip (exists)", flush=True)
            continue
        factor, key, accent = TIERS[tier]
        avoid = [k for k in ("red", "orange", "green", "blue", "pink",
                             "yellow", "white") if k in key]
        name, chroma = chroma_for(pink, avoid)
        hexc = "#{:02X}{:02X}{:02X}".format(*chroma)
        print(f"[t{tier:02d}] chroma={name} {hexc} accent={accent}",
              flush=True)

        src = pink_on_chroma(pink, chroma, wide=(tier >= 5))
        src.save(RAW_DIR / f"src_t{tier}.png")
        prompt = prompt_for(tier, accent, name, hexc)
        (RAW_DIR / f"prompt_t{tier}.txt").write_text(prompt)

        # post-pink tiers must be LONGER than the pink adult (aspect >= 2.5);
        # keep the most elongated of up to 3 attempts.  ALL tiers retry when
        # the animal touches the frame edge (cropped snout / tail).
        want_aspect = 2.5 if tier >= 5 else 0.0
        attempts = 3
        best_keyed, best_asp = None, -1.0
        try:
            for a in range(1, attempts + 1):
                raw = edit_image(prompt, src, api_key,
                                 aspect=("21:9" if tier >= 5 else "16:9"))
                raw.save(RAW_DIR / f"raw_t{tier}_a{a}.png")
                keyed = chroma_key(raw, chroma)
                bb = keyed.getbbox()
                if bb is None:
                    raise ValueError("chroma key removed everything")
                w0, h0 = keyed.size
                m = 0.01
                touches = (bb[0] <= round(w0 * m) or bb[1] <= round(h0 * m)
                           or bb[2] >= round(w0 * (1 - m))
                           or bb[3] >= round(h0 * (1 - m)))
                asp = lizard_aspect(keyed)
                print(f"[t{tier:02d}] attempt {a}: aspect={asp:.2f} "
                      f"edge_touch={touches}", flush=True)
                if touches:
                    if best_keyed is None:
                        best_keyed, best_asp = keyed, asp  # last resort
                    continue
                if asp > best_asp:
                    best_keyed, best_asp = keyed, asp
                if asp >= want_aspect:
                    break
                time.sleep(1.0)
            keyed = best_keyed
            keyed.save(RAW_DIR / f"keyed_t{tier}.png")
            strip = to_strip(keyed, factor, pink.height, pink.width,
                             STRETCH.get(tier, 1.0))
            strip.save(out, "PNG")
            print(f"[t{tier:02d}] saved {out.name} {out.stat().st_size} bytes"
                  f" (aspect {best_asp:.2f})", flush=True)
        except Exception as e:
            failures.append({"tier": tier, "error": str(e)})
            print(f"[t{tier:02d}] FAILED: {e}", flush=True)
        time.sleep(1.5)

    print(f"\nSummary: failed={len(failures)}", flush=True)
    if failures:
        print(json.dumps(failures, indent=2), flush=True)
        sys.exit(2)


if __name__ == "__main__":
    main()
