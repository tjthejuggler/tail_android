#!/usr/bin/env python3
"""One-shot repair: correct the erroneous Phase 2 verdict for chess.com game
173512925270 in the phone's Tail prefs (pulled via adb run-as).

Background: the desktop Stockfish analysis classified four mate-conversion
moves as "blunders" (mate-score folding artifact, see chess_analysis.py fix),
which pushed the v3 audit to PIVOT_TO_DRILLS (yellow). chess.com's own review
of the same game found 0 blunders. This script rewrites the two on-phone
ledger entries for that game to the verdict the corrected analysis yields:
CONTINUE_RATED, strain 0.

Usage: pass --write to actually modify the files; default is a dry run.
"""

import re
import sys

TS = "1787672897000"  # game-end timestamp identifying the ledger entries

# XML entity for a double quote, assembled at runtime so no tooling in the
# pipeline can "helpfully" unescape it.
Q = "&" + "quot;"

FIXES = [
    # (path, [(old, new), ...])
    (
        "/tmp/tail_prefs/tail_chess_phase2.xml",
        [
            ("PIVOT_TO_DRILLS", "CONTINUE_RATED"),
            (Q + "strain" + Q + ":50", Q + "strain" + Q + ":0"),
        ],
    ),
    (
        "/tmp/tail_prefs/tail_chess_phase2_v2.xml",
        [
            ("PIVOT_TO_DRILLS", "CONTINUE_RATED"),
            (Q + "strain" + Q + ":25", Q + "strain" + Q + ":0"),
        ],
    ),
]


def main() -> int:
    in_place = "--write" in sys.argv
    for path, changes in FIXES:
        with open(path) as f:
            txt = f.read()
        # The single XML-escaped JSON entry containing this game's timestamp.
        pat = re.compile(r"\{" + re.escape(Q) + r"[^{}]*" + TS + r"[^{}]*\}")
        matches = pat.findall(txt)
        if len(matches) != 1:
            print(f"FAIL {path}: expected 1 entry for {TS}, found {len(matches)}")
            return 1
        old = matches[0]
        new = old
        for a, b in changes:
            if a not in new:
                print(f"FAIL {path}: pattern not found: {a}")
                return 1
            new = new.replace(a, b)
        if in_place:
            with open(path, "w") as f:
                f.write(txt.replace(old, new))
            print(f"FIXED {path}")
        else:
            print(f"DRY-RUN {path}:")
            print("  old:", old)
            print("  new:", new)
    return 0


if __name__ == "__main__":
    sys.exit(main())
