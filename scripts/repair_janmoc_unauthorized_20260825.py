#!/usr/bin/env python3
"""One-shot repair for the JanMoc game wrongly flagged unauthorized (2026-08-25).

Bug: authorization was evaluated at the game's END time. The GREEN test at
17:36:33 local opened a 60-minute window (expires 18:36:33). The game vs
JanMoc started ~18:32 (inside the window) but ended 18:42:06 — six minutes
past expiry — so the log marked it authorized=false and Chess Guard applied
a 24-hour penalty.

This script:
  1. flips that game's log entry to authorized=true (stateAtPlay/ccrsAtPlay
     were already correct: GREEN_LIGHT / 85), and
  2. clears the violation penalty for that game (it is the only penalty).

Run on the pulled copies in /tmp/chess_repair, then push back with adb.
"""

import json
import re
import sys

GAME_KEY = "1787676126|janmoc|600"
LOG = "/tmp/chess_repair/chess_readiness_log.json"
PREFS = "/tmp/chess_repair/tail_chess_readiness.xml"


def repair_log() -> None:
    log = json.load(open(LOG))
    games = log.get("games", [])
    fixed = 0
    for g in games:
        if g.get("key") == GAME_KEY and g.get("authorized") is False:
            g["authorized"] = True
            fixed += 1
    if fixed != 1:
        sys.exit(f"ERROR: expected exactly 1 game entry to fix, found {fixed}")
    json.dump(log, open(LOG, "w"), separators=(",", ":"))
    print(f"log: {GAME_KEY} -> authorized=true")


def repair_prefs() -> None:
    raw = open(PREFS).read()
    # Build the " entity at runtime so no toolchain mangles it.
    q = "&" + "quot;"
    empty = "[" + "]"
    pattern = (
        r'(name="violation_penalties">).*?(</string>)'
    )
    new_raw, n = re.subn(pattern, r"\g<1>" + empty + r"\g<2>", raw, count=1, flags=re.S)
    if n != 1:
        sys.exit("ERROR: violation_penalties entry not found")
    if q not in raw and "violation_penalties" in raw:
        # Sanity: original used entities; the replacement is plain [] which
        # needs no escaping, so nothing else to preserve.
        pass
    open(PREFS, "w").write(new_raw)
    print("prefs: violation_penalties -> []")


if __name__ == "__main__":
    repair_log()
    repair_prefs()
    print("repair OK")
