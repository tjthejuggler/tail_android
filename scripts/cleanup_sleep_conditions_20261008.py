#!/usr/bin/env python3
"""
One-off cleanup (2026-10-08): curate sleep-conditions tags in sleep_data.json.

1. TEMPERATURE TAGS -> temp field. Tags like "26ish AC", "23ish AC", "19ish AC",
   "30ish" mean "room was roughly 26C (AC on)" etc. They are removed from the
   conditions list and written into the record's `temp` (tenths of degC) —
   but ONLY when the record has no temp yet (manually entered temps win).
   Plausibility window 16..32 degC; anything else (e.g. "45ish") is left as a
   tag and reported for manual review.

2. HABIT-COVERED TAGS REMOVED. Tags whose information is already tracked by
   dedicated habits are dropped from conditions (they only clutter the
   suggestion chips): No Coffee (Coffee/No Coffee habits), Sports and
   Movement (outdoors) (workout/run habits), Sauna (Sweat habit).

3. FRAGMENT REPAIR. The German catalog name "Konflikte, Streit oder Sorgen"
   contains commas, so the 2026-10-07 import split it into three chips
   ("Conflicts", "arguments", "or worries"); these are re-joined into the
   single tag "Conflicts, arguments, or worries". "Bed Partner" is normalized
   to "Bed partner".

Only the Sleep habit's records are touched; bed/wake times, survey fields
and existing temps are never modified.
"""

import json
import re
import subprocess
import sys

APP = "com.example.tail"
REMOTE = "files/sleep_data.json"
LOCAL = "/tmp/sleep_data_cleanup.json"

TEMP_TAG = re.compile(r"^(\d{2})(?:ish|c)?\s*(?:ac)?$", re.IGNORECASE)
HABIT_COVERED = {"no coffee", "sports", "sport", "movement (outdoors)", "sauna"}
CONFLICT_SEQ = ("conflicts", "arguments", "or worries")


def main():
    out = subprocess.run(["adb", "exec-out", "run-as", APP, "cat", REMOTE],
                         capture_output=True)
    data = json.loads(out.stdout or b"{}")

    stats = {"temp_set": 0, "temp_kept_manual": 0, "tags_removed": 0,
             "fragments_joined": 0, "casing_fixed": 0, "odd_temp_tags": set(),
             "conditions_cleared": 0}

    for date_str, rec in sorted(data.get("Sleep", {}).items()):
        conds = [c.strip() for c in (rec.get("conditions") or "").split(",") if c.strip()]
        if not conds and rec.get("temp") is None:
            continue

        # 1. temperature extraction
        kept = []
        for c in conds:
            m = TEMP_TAG.match(c)
            if m:
                val = int(m.group(1))
                if 16 <= val <= 32:
                    if rec.get("temp") is None:
                        rec["temp"] = val * 10
                        stats["temp_set"] += 1
                    else:
                        stats["temp_kept_manual"] += 1
                    continue
                else:
                    stats["odd_temp_tags"].add(c)
            kept.append(c)

        # 2. habit-covered removal
        before = len(kept)
        kept = [c for c in kept if c.lower() not in HABIT_COVERED]
        stats["tags_removed"] += before - len(kept)

        # 3a. re-join the split "Conflicts, arguments, or worries" fragments
        joined = []
        i = 0
        while i < len(kept):
            if (i + 2 < len(kept) and
                    tuple(x.lower() for x in kept[i:i + 3]) == CONFLICT_SEQ):
                joined.append("Conflicts, arguments, or worries")
                i += 3
                stats["fragments_joined"] += 1
            else:
                joined.append(kept[i])
                i += 1

        # 3b. casing normalization
        joined = [("Bed partner" if c == "Bed Partner" else c) for c in joined]
        stats["casing_fixed"] += sum(1 for a, b in zip(joined, kept) if a != b)

        if joined:
            rec["conditions"] = ", ".join(joined)
        else:
            rec.pop("conditions", None)
            stats["conditions_cleared"] += 1

    if stats["odd_temp_tags"]:
        print("REVIEW (left as tags, out of 16-32C range):", stats["odd_temp_tags"])
    stats["odd_temp_tags"] = len(stats["odd_temp_tags"])
    print("Stats:", json.dumps(stats))

    if "--apply" not in sys.argv:
        print("DRY RUN — re-run with --apply to push.")
        return

    open(LOCAL, "w").write(json.dumps(data, ensure_ascii=False, indent=2))
    subprocess.run(["adb", "shell", "am", "force-stop", APP], check=True)
    subprocess.run(["adb", "push", LOCAL, "/data/local/tmp/sdc.json"], check=True,
                   capture_output=True)
    subprocess.run(["adb", "shell", "run-as", APP, "cp",
                    "/data/local/tmp/sdc.json", REMOTE], check=True)
    subprocess.run(["adb", "shell", "rm", "/data/local/tmp/sdc.json"], check=True)
    print(f"Cleaned and pushed {REMOTE}.")


if __name__ == "__main__":
    main()
