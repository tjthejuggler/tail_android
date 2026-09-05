#!/usr/bin/env python3
"""Per-tier POSE SETS for the shimmer lizard (pure data module).

Imported by gen_lizard_poses.py as POSES_BY_TIER. Every tier gets the three
BASIC postures (walk / face_on / curled — the lizard's "normal life",
re-worded per age) plus UNIQUE poses whose props/toys/activities match the
tier's age and persona. Unique poses NEVER repeat across tiers.

Layout templates (dummy coords are (row, col), row 0 = top; canvases stay
<= 5 cols x 4 rows because the in-app solver grid is 8x10):
    stand   : 2x2, dummies [(1,0),(1,1)]        — on-block poses
    stand_t : 2x3, dummies [(2,0),(2,1)]        — upright prop poses
    gap     : 3x2, dummies [(1,0),(1,2)]        — props spanning between blocks
    stage   : 3x4, dummies [(3,0),(3,1),(3,2)]  — showpiece finale poses
    stairs  : 3x3, dummies [(2,0),(2,1),(1,1),(1,2),(0,2)]
    stretch : 5x2, dummies [(1,0)..(1,4)]       — long horizontal poses

Rules (ADR "pose SCALE consistency" + README 2026-09-05):
  · bulk bands are posture-derived: low-slung 0.30-0.55, standing 0.30-0.65,
    upright 0.48-0.75, curled 0.70-0.95, levitation 0.55-0.80;
  · grounded poses must physically touch the blocks; only inherently
    mid-air actions set anchored="airborne";
  · glowing props take the lizard's OWN accent colour, never a chroma-key
    colour — spell it out in "avoid" whenever a prop could tempt the model
    (tier 0/1/3/5/6/8/10: magenta bg + green squares; tier 4/7/11: BLUE bg;
    tier 2/9: YELLOW squares; tier 12: CYAN squares — see BG_KEYS/SQ_KEYS).

Counts: tiers 0-5 get 10 poses; rare tiers 6-12 get 12.
"""

# ── layout helpers ───────────────────────────────────────────────────────────
def _walk(pose):
    return dict(name="walk", cols=2, rows=2,
                dummies=[(1, 0), (1, 1)], bulk=(0.30, 0.45), pose=pose)


def _face_on(pose):
    return dict(name="face_on", cols=2, rows=2,
                dummies=[(1, 0), (1, 1)], bulk=(0.55, 0.75), pose=pose)


def _curled(name, pose, **kw):
    d = dict(name=name, cols=2, rows=3, dummies=[(2, 0), (2, 1)],
             bulk=(0.7, 0.95), pose=pose)
    d.update(kw)
    return d


def _onblock(name, pose, bulk=(0.4, 0.6), **kw):
    d = dict(name=name, cols=2, rows=2, dummies=[(1, 0), (1, 1)],
             bulk=bulk, pose=pose)
    d.update(kw)
    return d


def _upright(name, pose, bulk=(0.5, 0.7), **kw):
    d = dict(name=name, cols=2, rows=3, dummies=[(2, 0), (2, 1)],
             bulk=bulk, pose=pose)
    d.update(kw)
    return d


def _gap(name, pose, bulk=(0.4, 0.6), **kw):
    d = dict(name=name, cols=3, rows=2, dummies=[(1, 0), (1, 2)],
             bulk=bulk, pose=pose)
    d.update(kw)
    return d


def _stage(name, pose, bulk=(0.45, 0.65), **kw):
    d = dict(name=name, cols=3, rows=4, dummies=[(3, 0), (3, 1), (3, 2)],
             bulk=bulk, pose=pose)
    d.update(kw)
    return d


# ─────────────────────────────────────────────────────────────────────────────
POSES_BY_TIER = {

    # ── tier 0 — RED BABY: nursery things, tiny toys, first steps ────────────
    0: [
        _walk("classic side-profile walking pose but with baby proportions — "
              "oversized head, short legs, a wide slightly-clumsy stance — "
              "body horizontal and compact, all four feet on the top edge of "
              "the block, short tail raised in a wobbly S-curve behind"),
        _upright("face_on",
                 "seen face-on with baby proportions: a big round head and "
                 "huge curious glowing eyes dominating the frame, body "
                 "foreshortened and narrow below the head, front feet "
                 "planted on the block, head tilted slightly"),
        _curled("nap",
                "curled up asleep on top of the block under a tiny soft grey "
                "blanket draped over his coiled body, just his head poking "
                "out from under one corner, the whole coiled body fully "
                "ABOVE the block's top edge, eye closed, breathing slowly "
                "and contentedly",
                avoid="The blanket is soft neutral grey felt — never magenta "
                      "or green."),
        _upright("eggshell",
                 "a newborn hatchling sitting INSIDE the bottom half of a "
                 "cream-coloured speckled eggshell that rests on the block's "
                 "top face, the shell rim around his lower belly, a few tiny "
                 "shell fragments scattered on the block beside it, looking "
                 "out at the world in wonder"),
        _onblock("windup",
                 "chasing a tiny tin wind-up mouse across the block's top "
                 "face, body low and stretched mid-pounce with front legs "
                 "reaching for the toy just ahead of his nose, the toy's "
                 "little wind-up key sticking up",
                 bulk=(0.25, 0.45)),
        _upright("bottle",
                 "sitting upright on the block holding a big translucent "
                 "baby bottle in both front feet, drinking from the nipple "
                 "with eyes closed in bliss, tail coiled flat on the block "
                 "behind him",
                 avoid="The bottle holds plain white milk; the bottle body "
                       "is clear glass with a pale grey cap — never magenta "
                       "or green."),
        _onblock("pacifier",
                 "sitting upright on the block sucking contentedly on a tiny "
                 "red pacifier held in both front feet, cheeks rounded, eyes "
                 "half closed, tail loosely coiled beside him"),
        _upright("duck",
                 "riding a classic yellow tin duck toy that stands on the "
                 "block's top face, sitting upright on the duck's flat "
                 "back gripping a small handle, the duck's flat feet "
                 "firmly on the block, the whole duck-and-rider stack "
                 "LOW and compact — the lizard's head stays well below "
                 "the top of the canvas",
                 bulk=(0.5, 0.7),
                 avoid="The duck is bright tin yellow with an orange beak "
                       "— never magenta, never green."),
        _gap("swing",
             "lying in a tiny steel cradle that hangs by two thin ropes "
             "from a horizontal brushed-steel bar spanning the gap between "
             "the two blocks, the cradle and lizard sagging in the gap "
             "BETWEEN the blocks never in front of them, only the rope "
             "ends touch the blocks' top faces, rocking happily",
             bulk=(0.35, 0.55)),
        _upright("balloon",
                 "lifting OFF the block, pulled upward by a single round "
                 "red balloon on a pale string tied to the tip of his tail, "
                 "his toes just grazing the block's top face, body tilted "
                 "upward mid-liftoff with front legs spread wide",
                 bulk=(0.45, 0.7), anchored="airborne",
                 avoid="The balloon is his own red accent colour with a "
                       "matte surface and no glow — never magenta, never "
                       "green."),
    ],

    # ── tier 1 — ORANGE young kid: SHIPPED ART — the 10 defs below are the
    #    original phase-2 set, byte-identical to what generated the shipped
    #    lizard_pose_t1_p00..09.png files. Do not modify.
    1: [
        dict(name="walk", cols=2, rows=2,
             dummies=[(1, 0), (1, 1)],
             bulk=(0.30, 0.45),
             pose=("classic side-profile walking pose, body horizontal and "
                   "compact (about twice as long as tall), all four feet on "
                   "the top edge of the green block, tail raised in a gentle "
                   "S-curve behind")),
        dict(name="face_on", cols=2, rows=2,
             dummies=[(1, 0), (1, 1)],
             bulk=(0.55, 0.75),
             pose=("seen face-on, looking straight at the viewer, body "
                   "foreshortened and narrow, front feet on the green block, "
                   "head tilted slightly with one glowing eye prominent")),
        dict(name="curled", cols=2, rows=3,
             dummies=[(2, 0), (2, 1)],
             bulk=(0.7, 0.95),
             pose=("curled up like a napping cat, body coiled in a tight "
                   "spiral resting ON TOP of the green block, the whole "
                   "coiled body fully ABOVE the block's top edge, tail "
                   "wrapped around the upper part of the body, eye half "
                   "closed and content")),
        dict(name="stairs", cols=3, rows=3,
             dummies=[(2, 0), (2, 1), (1, 1), (1, 2), (0, 2)],
             bulk=(0.4, 0.58),
             pose=("climbing the staircase of green blocks, body stretched "
                   "diagonally along the stair steps, front legs gripping the "
                   "highest step, belly touching the stair corners, tail "
                   "trailing down to the lower left")),
        dict(name="stretched", cols=5, rows=2,
             dummies=[(1, 0), (1, 1), (1, 2), (1, 3), (1, 4)],
             bulk=(0.3, 0.45),
             pose=("fully stretched out flat on its belly along the top of "
                   "the row of green blocks, legs splayed out to the sides "
                   "like a basking lizard, long and thin, head at one end, "
                   "tail extending straight to the other end")),
        dict(name="phone", cols=2, rows=3,
             dummies=[(2, 0), (2, 1)],
             bulk=(0.5, 0.7),
             avoid=("The smartphone's screen glows the SAME warm orange "
                    "accent colour as the chameleon's lights — never pink, "
                    "magenta or green."),
             pose=("sitting upright on TOP of the green block, both hind "
                   "feet planted flat on the block's top face, the whole "
                   "body ABOVE the block's top edge, holding a tiny "
                   "metallic smartphone in its front feet at chest height, "
                   "staring at the glowing screen, tail coiled flat on the "
                   "block BEHIND the body for balance — nothing hangs off "
                   "the front or floats beside the block")),
        dict(name="pipe", cols=3, rows=2,
             dummies=[(1, 0), (1, 2)],
             bulk=(0.35, 0.55),
             pose=("draped lazily over a horizontal brushed-steel pipe that "
                   "runs between the two green blocks, belly on the pipe, "
                   "front legs hanging down on one side, head and tail "
                   "drooping over the other side")),
        dict(name="hammock", cols=3, rows=2,
             dummies=[(1, 0), (1, 2)],
             bulk=(0.4, 0.6),
             avoid=("The hammock is woven from dull grey steel rope — a "
                    "neutral dark metallic grey mesh, never pink, magenta, "
                    "purple or green."),
             pose=("lying in a tiny steel-mesh hammock slung between the "
                   "TOPS of the two green blocks, the whole chameleon and "
                   "hammock sagging in the gap BETWEEN the blocks, never "
                   "in front of them; only the rope ends touch the blocks' "
                   "top faces, limbs dangling over the hammock edges, "
                   "relaxed and satisfied expression")),
        dict(name="pushup", cols=2, rows=2,
             dummies=[(1, 0), (1, 1)],
             bulk=(0.48, 0.65),
             pose=("doing a push-up workout on top of the green block, chest "
                   "low to the block, rear raised, gripping a tiny metallic "
                   "dumbbell in one front foot, straining with effort")),
        dict(name="treasure", cols=3, rows=4,
             dummies=[(3, 0), (3, 1), (3, 2)],
             bulk=(0.45, 0.65),
             pose=("standing proudly on top of a small hoard of treasure — "
                   "glowing orange gems, gold coins and a tiny trophy cup "
                   "piled on the green platform block — chest puffed out, "
                   "head held high, tail curled regally, the showpiece pose")),
    ],

    # ── tier 2 — GREEN kid: school + play (YELLOW dummy squares!) ────────────
    2: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, tail "
              "raised in a gentle S-curve behind, energetic kid's stride"),
        _face_on("seen face-on, looking straight at the viewer, body "
                 "foreshortened and narrow, front feet on the block, head "
                 "tilted slightly with one glowing eye prominent"),
        _curled("curled",
                "curled up like a napping cat, body coiled in a tight "
                "spiral resting ON TOP of the block, the whole coiled body "
                "fully ABOVE the block's top edge, tail wrapped around the "
                "upper part of the body, eye half closed and content"),
        _onblock("skateboard",
                 "standing sideways on a skateboard parked on the block's "
                 "top face, both feet on the deck, knees bent for balance, "
                 "tail stretched out behind as a counterweight, looking "
                 "down the imaginary slope",
                 bulk=(0.28, 0.5),
                 avoid="The skateboard is brushed steel with small wheels "
                       "in his green accent colour — never yellow, never "
                       "magenta."),
        _upright("backpack",
                 "standing tall wearing a tiny steel backpack with "
                 "buckles, holding an oversized pencil upright like a "
                 "staff in one front foot, proud school-kid stance"),
        _gap("fishing",
             "sitting on the edge of the left block with his legs "
             "dangling into the gap, holding a slim fishing rod that "
             "reaches out over the gap, a thin line dropping from the rod "
             "tip to a small round red bobber hanging between the blocks, "
             "patient and focused"),
        _upright("controller",
                 "sitting upright on the block leaning slightly forward, "
                 "gripping a chunky game controller in both front feet "
                 "with thumbs on the buttons, eyes locked ahead in "
                 "concentration, tail flicking behind",
                 avoid="The controller is dark charcoal plastic with "
                       "buttons glowing his green accent colour — never "
                       "yellow, never magenta."),
        _upright("magnifier",
                 "standing on the block holding a big round magnifying "
                 "glass up to one eye with one front foot, hunched "
                 "curiously over something tiny on the block's top face, "
                 "the other front foot shading like a scout"),
        _upright("slingshot",
                 "crouched low on the block in a hunter's stance, aiming a "
                 "small Y-branch wooden slingshot sideways with a pebble "
                 "in the pouch, one eye squinting along the band"),
        _onblock("soccer",
                 "dribbling a small classic black-and-white football "
                 "across the block's top face, one foot planted on top of "
                 "the ball mid-tap, body leaning into the run, tail out "
                 "for balance"),
    ],

    # ── tier 3 — BLUE teen: music, gadgets, games (magenta + green keys) ─────
    3: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, tail "
              "raised in a gentle S-curve behind, easy teenage stride"),
        _face_on("seen face-on, looking straight at the viewer, body "
                 "foreshortened and narrow, front feet on the block, head "
                 "tilted slightly with one glowing eye prominent"),
        _curled("curled",
                "curled up like a napping cat, body coiled in a tight "
                "spiral resting ON TOP of the block, the whole coiled body "
                "fully ABOVE the block's top edge, tail wrapped around the "
                "upper part of the body, eye half closed and content"),
        _upright("headphones",
                 "standing on the block wearing big over-ear headphones, "
                 "eyes closed, head bobbing to the beat, one foot tapping "
                 "the block's top face, holding a small slim music player "
                 "in one front foot",
                 avoid="The headphones are matte black with a small LED "
                       "glowing his blue accent colour — never magenta, "
                       "never green."),
        _upright("vr",
                 "standing on the block wearing a chunky VR headset "
                 "strapped over his eyes, both front arms raised and "
                 "feeling the air in front of him, mouth slightly open "
                 "in wonder, tail stretched out behind for balance",
                 bulk=(0.5, 0.7),
                 avoid="The VR headset is matte dark grey with a small "
                       "LED glowing his blue accent colour — never "
                       "magenta, never green."),
        _upright("laptop",
                 "sitting hunched over a tiny laptop resting on his folded "
                 "legs, typing with both front feet, face lit by the "
                 "screen, utterly absorbed",
                 avoid="The laptop is dark grey and its screen glows his "
                       "own blue accent colour — never magenta, never "
                       "green."),
        _upright("chemistry",
                 "standing at a tiny chemistry set on the block — a small "
                 "rack of test tubes and one round flask on a stand — "
                 "lifting one vial up to eye level with tongs, examining "
                 "it like a young scientist",
                 avoid="The liquids glow his blue accent colour — never "
                       "magenta, never green."),
        _gap("paper_air",
             "standing upright on the left block in a thrower's stance, "
             "one front foot cocked back holding a crisp white paper "
             "airplane about to launch it across the gap, two finished "
             "paper airplanes already resting on the right block"),
        _upright("basketball",
                 "standing tall on the block, both arms extended high "
                 "overhead slamming a small orange basketball down into a "
                 "steel hoop mounted on a slim pole rising from behind "
                 "the block, tiny net, back arched with effort",
                 bulk=(0.55, 0.75)),
        _gap("mural",
             "standing on the left block shaking a spray can, painting a "
             "small graffiti tag on a free-standing brushed-steel sheet "
             "propped up on the right block, cap off, mid-spray",
             avoid="The graffiti tag glows his blue accent colour on the "
                   "steel — never magenta, never green."),
    ],

    # ── tier 4 — PINK young adult: city life, self-care (BLUE background!) ──
    4: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, tail "
              "raised in a gentle S-curve behind, confident grown-up "
              "stride"),
        _face_on("seen face-on, looking straight at the viewer, body "
                 "foreshortened and narrow, front feet on the block, head "
                 "tilted slightly with one glowing eye prominent"),
        _curled("curled",
                "curled up like a napping cat, body coiled in a tight "
                "spiral resting ON TOP of the block, the whole coiled body "
                "fully ABOVE the block's top edge, tail wrapped around the "
                "upper part of the body, eye half closed and content"),
        _upright("selfie",
                 "holding a slim smartphone out at arm's length in one "
                 "front foot, striking a playful pose for a selfie with "
                 "the other foot on his hip, looking at the phone",
                 bulk=(0.32, 0.52),
                 avoid="The phone screen glows his pink accent colour — "
                       "never blue, never green."),
        _upright("karaoke",
                 "standing on the block holding a classic handheld "
                 "microphone up to his open mouth mid-song, the other arm "
                 "flung out dramatically, head tipped back",
                 bulk=(0.55, 0.75)),
        _onblock("ramen",
                 "sitting cross-legged on the block with a big steaming "
                 "ramen bowl between his front feet, lifting a tangle of "
                 "noodles with slim chopsticks, slurping happily",
                 bulk=(0.45, 0.65)),
        _upright("dj",
                 "standing behind a tiny turntable deck set on the block, "
                 "one front foot scrubbing the vinyl record, the other "
                 "foot tweaking a small mixer fader, head nodding to the "
                 "rhythm",
                 bulk=(0.32, 0.52),
                 avoid="The deck is dark grey metal; its tiny LED glows "
                       "his pink accent colour — never blue, never green."),
        _onblock("yoga",
                 "holding a serene one-legged tree-pose yoga balance on "
                 "the block's top face, palms pressed together at his "
                 "chest, eyes closed, tail curled in a calm spiral",
                 bulk=(0.45, 0.65)),
        _upright("watering",
                 "tilting a small steel watering can over three tiny "
                 "potted flowers standing in a row on the block, one "
                 "flower just opening",
                 avoid="The blooms glow his pink accent colour; foliage is "
                       "muted dark grey-green — never pure green #00FF00, "
                       "never blue."),
        _onblock("polaroid",
                 "sitting on the block beside a messy pile of developed "
                 "instant photos, holding a chunky instant camera in both "
                 "front feet, studying the shot he just took",
                 bulk=(0.45, 0.65),
                 avoid="The photos are small white-bordered prints with "
                       "faint pastel images — no blue glow anywhere."),
    ],

    # ── tier 5 — YELLOW adult: work & hobby mastery (magenta + green keys) ───
    5: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, tail "
              "raised in a gentle S-curve behind, steady grown-up stride"),
        _face_on("seen face-on, looking straight at the viewer, body "
                 "foreshortened and narrow, front feet on the block, head "
                 "tilted slightly with one glowing eye prominent"),
        _curled("curled",
                "curled up like a napping cat, body coiled in a tight "
                "spiral resting ON TOP of the block, the whole coiled body "
                "fully ABOVE the block's top edge, tail wrapped around the "
                "upper part of the body, eye half closed and content"),
        _onblock("coffee",
                 "standing relaxed on the block holding a steaming white "
                 "mug in both front feet, savouring the aroma with eyes "
                 "half closed, a tiny folded newspaper lying on the block "
                 "beside him",
                 bulk=(0.22, 0.42)),
        _gap("repair",
             "kneeling on the left block, repairing a small vertical "
             "valve assembly mounted on a brushed-steel pipe that runs "
             "up from the right block's top face, a leather toolbelt "
             "around his hips, turning a big spanner on the valve with "
             "both front feet, head lowered to the work",
             bulk=(0.24, 0.44)),
        _gap("grill",
             "standing on the left block tending a small kettle grill, "
             "turning two skewers with tiny tongs, a thin ribbon of smoke "
             "rising from the grill into the empty gap",
             avoid="The skewers hold a red tomato slice and mushrooms — "
                   "nothing pure green, nothing magenta."),
        _upright("commute",
                 "standing tall on the block ready for work, holding a "
                 "tiny briefcase in one front foot with a closed "
                 "umbrella hooked over the other arm, composed and "
                 "punctual"),
        _upright("chess",
                 "sitting at a tiny chess board resting on the block, "
                 "leaning on one elbow, moving a piece with one careful "
                 "claw while studying the board deep in thought"),
        _upright("guitar",
                 "sitting on the block's edge cradling a tiny warm-brown "
                 "acoustic guitar, one foot strumming the strings, eyes "
                 "closed, tail keeping time behind him"),
        _upright("easel",
                 "standing at a small wooden easel set on the block, "
                 "finishing a brush stroke on a small canvas",
                 avoid="The canvas shows a tiny simple sun in his own "
                       "yellow accent colour on a plain background — the "
                       "paint never magenta, never pure green."),
    ],

    # ── tier 6 — PURE WHITE elder mystic: rare, serene, mystical ─────────────
    6: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, tail "
              "raised in a calm unhurried S-curve, the dignified slow "
              "stride of an elder"),
        _face_on("seen face-on, looking straight at the viewer with a "
                 "serene ancient gaze, body foreshortened and narrow, "
                 "front feet on the block, head level and wise"),
        _curled("curled",
                "curled up in a perfect serene spiral asleep on top of "
                "the block, the whole coiled body fully ABOVE the block's "
                "top edge, tail wrapped around the upper body, eye closed "
                "in deep peaceful meditation-sleep"),
        _upright("meditate",
                 "LEVITATING cross-legged in mid-air just above the "
                 "block, legs folded in a lotus position, front paws "
                 "resting palm-up on his knees, eyes closed, floating "
                 "perfectly still in deep meditation",
                 bulk=(0.6, 0.8), anchored="airborne"),
        _upright("tea",
                 "kneeling formally at a tiny porcelain tea ceremony set "
                 "on the block, gracefully pouring a thin arc of tea from "
                 "a small pot into a cup, utterly focused and calm"),
        _upright("calligraphy",
                 "standing over a small cream scroll laid flat on the "
                 "block, drawing one long ink brush stroke with a "
                 "slender brush held delicately in one front foot, the "
                 "brush tip just touching the paper"),
        _upright("crystal_ball",
                 "standing behind a small crystal ball resting on a "
                 "low steel ring stand on the block, both front feet "
                 "curled around it, peering deep into the glass",
                 avoid="The crystal ball is clear glass with the faintest "
                       "pearl-white inner shimmer — never magenta, never "
                       "green."),
        _upright("bonsai",
                 "holding tiny steel shears, carefully pruning a "
                 "miniature bonsai tree growing in a shallow ceramic "
                 "dish on the block, one claw gently steadying a "
                 "branch",
                 avoid="The bonsai foliage is muted dark grey-green, "
                       "trunk warm brown — never pure green #00FF00, "
                       "never magenta."),
        _upright("staff",
                 "standing tall and dignified gripping a gnarled wooden "
                 "staff taller than himself planted on the block, a "
                 "smooth pale pearl orb seated in the staff's top crook, "
                 "chin high",
                 bulk=(0.6, 0.8),
                 avoid="The orb is matte pale pearl with no glow, no "
                       "halo — it is seated in the crook, never floating."),
        _onblock("hourglass",
                 "standing beside a brass hourglass nearly as tall as "
                 "himself resting on the block, one claw resting on its "
                 "frame, watching the last white sand trickle, "
                 "contemplative",
                 bulk=(0.35, 0.55)),
        _onblock("incense",
                 "sitting serenely before a small brass dish holding a "
                 "coiled incense spiral on the block, a single thin "
                 "ribbon of grey smoke rising straight up from the "
                 "coil, eyes closed",
                 bulk=(0.3, 0.5)),
        _upright("starmap",
                 "standing upright on the block holding a cream star "
                 "chart scroll unrolled in both front feet at chest "
                 "height, one claw tracing a constellation of small gold "
                 "stars on the parchment, head tilted thoughtfully",
                 bulk=(0.5, 0.7),
                 avoid="The chart parchment is cream with small gold "
                       "stars — never magenta, never green."),
    ],

    # ── tier 7 — WHITE+RED warrior: very rare, martial legend (BLUE bg!) ─────
    7: [
        _walk("classic side-profile marching pose, body horizontal and "
              "compact, all four feet striking the top edge of the block, "
              "tail straight and purposeful behind, chest out — a "
              "soldier's march"),
        _face_on("seen face-on, a battle-hardened stare straight at the "
                 "viewer, body foreshortened and narrow, front feet "
                 "planted wide on the block, head lowered slightly like a "
                 "duellist"),
        _curled("curled",
                "curled up asleep ON TOP of the block with his tail "
                "wrapped around a sheathed tiny sword lying beside him "
                "like a trusted comrade, the coiled body fully ABOVE the "
                "block's top edge, one eye cracked open, sleeping "
                "vigilant"),
        _stage("knight_guard",
               "standing vigilant guard on a small pile of gold coins "
               "and gems heaped on the platform blocks, a tiny sword "
               "raised in one front foot and a round steel shield on the "
               "other arm, head swivelled alert, the guardian pose",
               bulk=(0.5, 0.7),
               avoid="The gems glow his red accent colour — never blue, "
                     "never green."),
        _gap("sparring",
             "mid-strike against a straw training dummy mounted on a "
             "wooden post on the right block, wooden practice sword "
             "extended, body coiled in a lunging stance on the left "
             "block, small straw bits flying"),
        _upright("war_drum",
                 "standing wide-legged beating a large deep-red war drum "
                 "with two small mallets, both arms mid-swing, mouth "
                 "open in a battle cry",
                 bulk=(0.5, 0.7),
                 avoid="The drum shell is his red accent colour with "
                       "plain rawhide skin — never blue, never green."),
        _upright("banner",
                 "standing tall holding a slim pole planted on the "
                 "block, a long flowing red banner unfurling from the "
                 "pole's top and streaming sideways in the wind, chin "
                 "high and proud",
                 bulk=(0.55, 0.75),
                 avoid="The banner is his red accent colour — never "
                       "blue, never green."),
        _gap("catapult",
             "standing at a small wooden catapult parked on the left "
             "block aimed across the gap, a single stone in the "
             "bucket, one foot on the release lever, leaning back with "
             "the arm cocked"),
        _upright("armory",
                 "polishing a steel breastplate set on a wooden display "
                 "stand on the block with a soft cloth in one front "
                 "foot, a steel helmet with a red plume resting beside "
                 "it, craftsman's focus",
                 avoid="The helmet plume is his red accent colour — "
                       "never blue, never green."),
        _upright("shield_wall",
                 "standing sentinel behind two steel shields planted "
                 "upright in a row on the block like a miniature shield "
                 "wall, a slim banner pole upright in one front foot "
                 "resting on the block, helmet on his head, vigilant "
                 "sideways stance",
                 bulk=(0.5, 0.7),
                 avoid="The shields are plain polished steel with a thin "
                       "rim line in his red accent colour — never blue, "
                       "never green."),
        _upright("torch",
                 "standing tall holding a burning wooden torch aloft in "
                 "one front foot, the flame streaming sideways, the "
                 "other arm shielding his face from the heat, feet "
                 "planted wide",
                 bulk=(0.55, 0.75),
                 avoid="The flame burns warm orange-red like his own "
                       "accents with a tiny natural flicker — never "
                       "blue, never green."),
        _stage("victory_roar",
               "standing on a fallen enemy steel helmet used as a "
               "podium on the platform blocks, head thrown back roaring "
               "in triumph, sword raised high in one front foot, tail "
               "lashing, the victory pose",
               bulk=(0.55, 0.75)),
    ],

    # ── tier 8 — WHITE+ORANGE forge-master: very rare, master craftsman ──────
    8: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, tail "
              "raised in a gentle S-curve behind, the unhurried walk of "
              "a master on his way to the forge"),
        _face_on("seen face-on, looking straight at the viewer, body "
                 "foreshortened and narrow, front feet on the block, "
                 "head tilted slightly with one glowing eye prominent"),
        _curled("curled",
                "curled up like a napping cat, body coiled in a tight "
                "spiral resting ON TOP of the block, the whole coiled "
                "body fully ABOVE the block's top edge, tail wrapped "
                "around the upper part of the body, eye half closed "
                "and content"),
        _upright("anvil",
                 "hammering a small glowing ingot on a tiny steel anvil "
                 "set on the block, hammer raised mid-swing in one front "
                 "foot, the other steadying the ingot with tongs, a few "
                 "tiny sparks flying",
                 avoid="The ingot and sparks glow his orange accent "
                       "colour — never magenta, never green."),
        _upright("quench",
                 "standing UPRIGHT on the block like the phone-scrolling "
                 "pose, both hind feet planted flat on the block's top "
                 "face and the whole body ABOVE the block's top edge, "
                 "holding a glowing hot horseshoe in long tongs raised "
                 "at chest height in one front foot, inspecting it, "
                 "nothing touching the ground except his own feet",
                 bulk=(0.5, 0.7),
                 avoid="The horseshoe glows his orange accent colour — "
                       "never magenta, never green. No bucket, no bench, "
                       "no floor — only the standing lizard holding the "
                       "tongs."),
        _upright("gears",
                 "assembling a small sculpture of interlocked brass "
                 "gears standing on the block, carefully placing the "
                 "topmost gear with two claws, tongue tip in "
                 "concentration"),
        _upright("blueprint",
                 "standing upright on the block holding a small "
                 "blueprint scroll half-unrolled in both front feet at "
                 "chest height, studying the thin white schematic lines "
                 "on the muted steel-blue paper, deep in design thought",
                 bulk=(0.5, 0.7),
                 avoid="The blueprint paper is muted steel-blue with "
                       "thin white lines — no vivid glow, never magenta, "
                       "never pure green. It is a hand-held SCROLL, not "
                       "a sheet pinned flat — no table, no floor."),
        _upright("kiln",
                 "sliding a small clay pot into a squat little brick "
                 "kiln on the block with a long wooden paddle, the "
                 "kiln's front vent glowing warmly"),
        _gap("furnace",
             "standing on the left block working a long steel rod into "
             "a small squat furnace standing on the right block, the "
             "furnace's round mouth glowing hot, leaning into the "
             "work",
             avoid="The furnace mouth and rod tip glow his orange "
                   "accent colour — never magenta, never green."),
        _upright("clockwork",
                 "hunched over a half-built clockwork bird lying on the "
                 "block, placing a tiny gear with long thin tweezers in "
                 "one front foot, brass springs coiled beside"),
        _upright("molten",
                 "tipping a small steel crucible with tongs, pouring a "
                 "thin ribbon of molten metal into a coin-shaped mold "
                 "set on the block, face lit by the pour",
                 avoid="The molten stream glows his orange accent "
                       "colour — never magenta, never green."),
        # NOTE: bulk band includes the full-height armor mannequin standing
        # beside the lizard — the bulk metric measures the whole silhouette.
        _stage("armor",
               "standing proudly beside a display stand holding his "
               "finished masterwork steel armor on the platform blocks, "
               "one claw resting on the breastplate, head high, the "
               "proud craftsman's presentation pose",
               bulk=(0.5, 1.3),
               avoid="The armor has subtle warm etched lines in his "
                     "orange accent colour — never magenta, never "
                     "green."),
    ],

    # ── tier 9 — WHITE+GREEN alchemist: very rare, nature-mage (YELLOW sq!) ──
    9: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, tail "
              "raised in a gentle S-curve behind, a naturalist's quiet "
              "walk"),
        _face_on("seen face-on, looking straight at the viewer, body "
                 "foreshortened and narrow, front feet on the block, "
                 "head tilted slightly with one glowing eye prominent"),
        _curled("curled",
                "curled up like a napping cat, body coiled in a tight "
                "spiral resting ON TOP of the block, the whole coiled "
                "body fully ABOVE the block's top edge, tail wrapped "
                "around the upper part of the body, eye half closed "
                "and content"),
        _upright("flasks",
                 "tending a small row of round glass flasks with "
                 "bubbling liquids set on the block, giving one flask a "
                 "gentle swirl with a claw, tiny bubbles rising",
                 bulk=(0.24, 0.44),
                 avoid="The liquids glow his green accent colour — "
                       "never yellow, never magenta."),
        _onblock("mortar",
                 "grinding dried leaves in a small stone mortar with a "
                 "pestle held in both front feet, a few scattered leaf "
                 "crumbs beside it, focused on the work",
                 bulk=(0.45, 0.65),
                 avoid="The leaves are muted dark grey-green — never "
                       "pure green #00FF00, never yellow."),
        _upright("terrarium",
                 "leaning over an open glass terrarium jar set on the "
                 "block, snipping a tiny plant inside with small "
                 "shears, misted glass, gardener's patience",
                 bulk=(0.5, 0.75),
                 avoid="The plants inside are muted grey-green with "
                       "small white blossoms — never pure green, never "
                       "yellow."),
        _onblock("infusion",
                 "holding a single large leaf above a slim glass vial, "
                 "squeezing it so a glowing drop of sap falls into the "
                 "vial, watching the drop fall",
                 bulk=(0.5, 0.7),
                 avoid="The sap drop glows his green accent colour — "
                       "never yellow, never magenta."),
        _onblock("butterfly",
                 "standing perfectly still with one front foot extended, "
                 "a delicate glowing butterfly with antenna perched on "
                 "his claw, gazing at it fondly — his familiar",
                 bulk=(0.32, 0.52),
                 avoid="The butterfly's wings glow his green accent "
                       "colour — never yellow, never magenta."),
        _upright("crystal_chamber",
                 "watching a cluster of small raw crystals growing in "
                 "an open glass case on the block, one palm flat on the "
                 "glass, face close and wondering",
                 bulk=(0.35, 0.55),
                 avoid="The crystals glow his green accent colour — "
                       "never yellow, never magenta."),
        _onblock("smoke_div",
                 "sitting before a small brass dish on the block, "
                 "reading the twisting swirl of pale smoke rising from "
                 "it, face faintly lit, one claw raised as if counting "
                 "the swirls",
                 bulk=(0.45, 0.65),
                 avoid="The smoke is pale neutral grey — never yellow, "
                       "never magenta."),
        _onblock("elixir",
                 "tilting a tiny crystal goblet to sip a glowing "
                 "elixir, one claw raised in a connoisseur's judging "
                 "gesture, eyes narrowed in evaluation",
                 bulk=(0.45, 0.65),
                 avoid="The elixir glows his green accent colour — "
                       "never yellow, never magenta."),
        # NOTE: bulk band includes the tall stone arch standing behind the
        # lizard — the bulk metric measures the whole silhouette.
        _stage("ruin_garden",
               "standing amid a tiny overgrown stone ruin — two weathered "
               "arched wall fragments and fallen columns on the platform "
               "blocks — wrapped in vines and small glowing blossoms, "
               "tending it like a sacred garden with shears in one foot",
               bulk=(0.5, 1.5),
               avoid="The vines are muted dark grey-green and the "
                     "blossoms glow his green accent colour — never "
                     "yellow, never magenta."),
    ],

    # ── tier 10 — WHITE+BLUE storm savant: very rare, storm scientist ────────
    10: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, tail "
              "raised in a gentle S-curve behind, a brisk scholar's "
              "walk"),
        _face_on("seen face-on, looking straight at the viewer, body "
                 "foreshortened and narrow, front feet on the block, "
                 "head tilted slightly with one glowing eye prominent"),
        _curled("curled",
                "curled up like a napping cat, body coiled in a tight "
                "spiral resting ON TOP of the block, the whole coiled "
                "body fully ABOVE the block's top edge, tail wrapped "
                "around the upper part of the body, eye half closed "
                "and content"),
        _upright("tesla",
                 "standing beside a small brass tesla coil set on the "
                 "block, one claw raised — a thin electric arc snapping "
                 "from the coil's top to his claw tip, hair-raised "
                 "delight on his face",
                 bulk=(0.4, 0.6),
                 avoid="The arc glows his pale blue accent colour — "
                       "never magenta, never green."),
        _onblock("ice",
                 "chiselling a small half-finished ice sculpture of a "
                 "tiny lizard standing on the block, a slim chisel in "
                 "one front foot mid-tap, ice chips scattered around "
                 "the base",
                 bulk=(0.28, 0.48),
                 avoid="The ice is clear with a faint cold blue tint "
                       "like his accents — never magenta, never "
                       "green."),
        _onblock("lightning_bottle",
                 "holding up a corked glass bottle in both front feet "
                 "in which a tiny branching lightning bolt is trapped, "
                 "examining it like a prized specimen",
                 bulk=(0.3, 0.5),
                 avoid="The trapped bolt glows his pale blue accent "
                       "colour — never magenta, never green."),
        _gap("aurora",
             "standing upright on the left block peering through a "
             "brass telescope aimed up and across the gap, a tall "
             "chart board pinned with aurora diagrams standing on the "
             "right block, one claw hooked behind his back"),
        _onblock("snowglobe",
                 "holding a glass snow globe in both front feet, "
                 "giving it a shake — a tiny blizzard swirling inside "
                 "over a miniature landscape, eyes wide with wonder",
                 bulk=(0.5, 0.7),
                 avoid="The snow is soft white and the globe's water "
                       "has the faintest cold blue tint — never "
                       "magenta, never green."),
        _upright("satellite",
                 "leaning in to whisper into the feed horn of a small "
                 "satellite dish mounted on a tripod stand on the "
                 "block, one claw adjusting the dish's angle, "
                 "conspiring with the sky"),
        _upright("static",
                 "standing tall with one front foot raised palm-down "
                 "above three small steel bolts and nuts that hover in "
                 "mid-air beneath his palm, suspended by static "
                 "electricity, concentrated frown",
                 bulk=(0.5, 0.7),
                 avoid="Only the bolts hover — a faint pale blue "
                       "static crackle may flicker between palm and "
                       "bolts, never magenta, never green."),
        _gap("prism",
             "holding a small glass prism up in one front foot on the "
             "left block so a thin beam passing over the gap splits "
             "into a soft pastel spectrum fanning onto the right "
             "block, head tilted following the colours",
             avoid="The spectrum is a faint muted pastel wash — its "
                   "bands are soft and unsaturated, never pure "
                   "saturated green or magenta."),
        _stage("thunder_forge",
               "standing with a hammer raised high over a small "
               "anvil set on the platform blocks, about to strike a "
               "blade resting on it — the blade crackles with tiny "
               "pale blue arcs, storm-forging stance, muscles "
               "tensed",
               bulk=(0.55, 0.75),
               avoid="The arcs on the blade glow his pale blue accent "
                     "colour — never magenta, never green."),
    ],

    # ── tier 11 — WHITE+PINK heart-mender: very rare, gentle healer (BLUE bg!)
    11: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, "
              "tail raised in a gentle S-curve behind, a gentle "
              "caregiver's soft walk"),
        _face_on("seen face-on, a warm kind gaze straight at the viewer, "
                 "body foreshortened and narrow, front feet on the "
                 "block, head tilted fondly"),
        _curled("curled",
                "curled up like a napping cat, body coiled in a tight "
                "spiral resting ON TOP of the block, the whole coiled "
                "body fully ABOVE the block's top edge, tail wrapped "
                "around the upper part of the body, eye half closed "
                "and content"),
        _upright("bandage",
                 "kneeling to carefully wrap a tiny white bandage "
                 "around the tail of a little clockwork mouse sitting "
                 "on the block, tongue tip in concentration, the mouse "
                 "calm in his gentle grip",
                 avoid="The mouse is grey steel with one small warm "
                       "white eye-light — never blue, never green."),
        _onblock("origami",
                 "sitting on the block mid-fold of a crisp paper crane, "
                 "two finished white paper cranes standing neatly in a "
                 "row beside him",
                 bulk=(0.45, 0.65),
                 avoid="The paper is soft cream and pale pink — never "
                       "blue, never green."),
        _upright("flower_crown",
                 "weaving a small flower crown held in both front "
                 "feet, one half-finished hoop of stems with tiny "
                 "blooms, picking the next bloom from a small pile on "
                 "the block",
                 avoid="The blooms glow his pink accent colour; stems "
                       "are muted grey-green — never pure green "
                       "#00FF00, never blue."),
        _onblock("music_box",
                 "sitting with eyes closed beside an open brass music "
                 "box on the block, its little lid up and cylinder "
                 "pins visible, head swaying gently to the tune",
                 bulk=(0.3, 0.5)),
        _upright("carousel",
                 "standing UPRIGHT on the block, both hind feet planted "
                 "flat on the block's top face, winding the brass key on "
                 "the side of a small carousel that stands on the block "
                 "beside him — a tiny canopy atop a slim pole with two "
                 "little painted horses hanging from it — watching it "
                 "turn with a fond smile, body tall and close to the "
                 "carousel",
                 bulk=(0.5, 0.7),
                 avoid="The carousel canopy is faded red-and-gold painted "
                       "tin — never blue, never green. No circus tent, "
                       "no floor — the carousel pole stands directly on "
                       "the block."),
        _onblock("doves",
                 "crouching to scatter seeds from one palm for two "
                 "plump white doves pecking on the block's top face "
                 "beside him, cooing quietly",
                 bulk=(0.3, 0.5),
                 avoid="The doves are soft white with grey wing tips — "
                       "never blue, never green."),
        _upright("love_letter",
                 "hunched over a small cream letter laid flat on the "
                 "block, writing with a slender white quill, a stick "
                 "of red sealing wax and a tiny stamp beside the page",
                 bulk=(0.28, 0.48)),
        _upright("quilt",
                 "sitting with a small patchwork quilt draped across "
                 "his folded legs, pulling a needle and thread through "
                 "a seam with careful stitches"),
        _upright("hearts_halo",
                 "floating cross-legged in mid-air just above the "
                 "block, front paws resting open on his knees, eyes "
                 "closed — several small soft glowing heart shapes "
                 "hover at different heights around him",
                 bulk=(0.6, 0.8), anchored="airborne",
                 avoid="The floating hearts are the pose itself and "
                       "must stay: each glows his pink accent colour, "
                       "soft and small — never blue, never green, no "
                       "other halo."),
    ],

    # ── tier 12 — WHITE+YELLOW sun-king: rarest, legendary showstopper
    #    (CYAN dummy squares!)
    12: [
        _walk("classic side-profile walking pose, body horizontal and "
              "compact, all four feet on the top edge of the block, "
              "tail raised in a commanding S-curve behind, the slow "
              "regal stride of a king reviewing his realm"),
        _face_on("seen face-on, a majestic imperial stare straight at "
                 "the viewer, body foreshortened and narrow, front "
                 "feet planted wide on the block, chin high"),
        # NOTE: bulk band includes the gold-coin mound under him.
        _curled("curled",
                "curled up asleep ON TOP of the block on a small mound "
                "of gold coins heaped on the block's top face, the "
                "coiled body fully ABOVE the block's top edge, tail "
                "wrapped around the coins, one eye half open — a "
                "dragon-king's nap",
                bulk=(0.7, 1.45)),
        _stage("throne",
               "seated at ease on a small gold-and-steel throne set on "
               "the platform blocks, a radiant crown on his head and a "
               "slim sceptre resting against his shoulder, one forearm "
               "on the armrest, the enthroned king pose",
               bulk=(0.6, 2.0),
               avoid="The crown's rays and sceptre ornament gleam his "
                     "golden accent colour — never cyan, never "
                     "green."),
        _upright("sun_orb",
                 "standing tall with both hind feet planted on the "
                 "block's top face, one front claw raised with a small "
                 "glowing golden sun orb levitating as a compact glowing "
                 "ball just above the palm, the other arm spread in a "
                 "presenting gesture, feet stay planted on the block",
                 bulk=(0.55, 0.75),
                 avoid="The orb glows his golden accent colour and is "
                       "the ONLY floating element — never cyan, never "
                       "green."),
        dict(name="chariot", cols=5, rows=2,
             dummies=[(1, 0), (1, 1), (1, 2), (1, 3), (1, 4)],
             bulk=(0.35, 0.55),
             pose=("standing upright in a tiny golden chariot whose "
                   "wheels rest on the long row of blocks, reins "
                   "looped over both front feet, pulled by two small "
                   "steel robo-beetles whose feet are planted on the "
                   "blocks ahead of the chariot, mid-parade")),
        _upright("conductor",
                 "conducting with a slender white baton held high in "
                 "one front foot, the other arm sweeping low, two "
                 "thin golden ribbon trails of light arcing from the "
                 "baton tip following the sweep",
                 bulk=(0.55, 0.75),
                 avoid="The ribbons are thin trails in his golden "
                       "accent colour — never cyan, never green."),
        _upright("solar_garden",
                 "kneeling to adjust one of a row of tiny solar "
                 "panels on slim stalks planted on the block like "
                 "flowers, angling its face upward, gardener's "
                 "delicacy"),
        _upright("star_scale",
                 "steadying a small brass balance scale with one "
                 "claw while two tiny glowing golden stars sit in "
                 "its pans, leaning in to compare their glow like a "
                 "mint-master weighing coin",
                 bulk=(0.35, 0.55)),
        # NOTE: bulk band includes the tall egg between his legs.
        _upright("phoenix_egg",
                 "sitting curled around a large speckled egg held "
                 "between his front legs, a fine crack running "
                 "across the shell with golden light glowing "
                 "through it, watching it hatch in awe",
                 bulk=(0.5, 1.5)),
        _upright("orrery",
                 "turning the crank of a small brass orrery on the "
                 "block — tiny planets on slender arms circling a "
                 "central golden sphere, one claw mid-turn, tracing "
                 "the heavens"),
        _stage("hoard_finale",
               "standing triumphant atop his ultimate hoard piled on "
               "the platform blocks — a great golden sun disc "
               "propped upright behind him and mementos of every "
               "age heaped around: a tiny anvil, a flask, a small "
               "sword, a mini guitar, a music box — one claw raised "
               "high, the legendary finale pose",
               bulk=(0.55, 1.3),
               avoid="Every memento keeps its own normal material "
                     "colours and the sun disc gleams his golden "
                     "accent — nothing cyan, nothing green, nothing "
                     "magenta anywhere in the pile."),
    ],
}
