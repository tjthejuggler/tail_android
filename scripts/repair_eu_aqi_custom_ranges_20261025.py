#!/usr/bin/env python3
"""
Repair for the custom-point-ranges history wipe of 'EU AQI (max)' (2026-10-25).

Bug: enabling Custom Point Runs recalculateHabitPointsForCustomRanges() overwrote
the habit's ENTIRE primary-key history with the computed points tiers,
destroying the raw EU AQI values (stored x10 by the environment sync).

Fix applied in-app (non-destructive secondary-slot model) + this script:
  1. Restore the raw values from the pre-corruption restic snapshot
     (1c693e52, 2026-10-24) into `secondary_value:EU AQI (max)` — the
     canonical true-value store used by the fixed code.
  2. Keep the current primary-key values (the tiers the app computed with the
     user's final ranges at toggle time). If ranges are edited later, the app
     now recomputes tiers FROM the secondary slot — never losing truth again.

Usage:
    python3 scripts/repair_eu_aqi_custom_ranges_20261025.py [--apply]
Without --apply the script runs in dry-run mode and only reports.
"""
import json
import shutil
import sys
import time
from pathlib import Path

DB = Path("/home/twain/habitsdb/habitsdb.txt")
SNAPSHOT = Path("/tmp/habitsdb_restore/home/twain/habitsdb/habitsdb.txt")
HABIT = "EU AQI (max)"
SEC_KEY = f"secondary_value:{HABIT}"

APPLY = "--apply" in sys.argv


def main() -> None:
    cur = json.loads(DB.read_text())
    old = json.loads(SNAPSHOT.read_text())

    raw_hist = old.get(HABIT)
    if not raw_hist:
        sys.exit(f"snapshot has no history for {HABIT!r}")

    tiers = cur.get(HABIT, {})
    # Dates that exist in the snapshot but NOT in the current tier history
    # (shouldn't happen — the wipe kept every date — but restore them anyway
    # by copying the raw value into the primary key as a fallback).
    missing_tier = sorted(set(raw_hist) - set(tiers))
    # Recent dates present in current but after the snapshot (e.g. today):
    # keep the current tier; the raw value arrives from the next env sync.
    new_dates = sorted(set(tiers) - set(raw_hist))

    cur_sec = cur.get(SEC_KEY, {})
    already_ok = all(cur_sec.get(d) == v for d, v in raw_hist.items())

    print(f"raw days in snapshot : {len(raw_hist)}")
    print(f"tier days in current : {len(tiers)}")
    print(f"secondary slot now   : {len(cur_sec)} entries, "
          f"already correct: {already_ok}")
    print(f"dates missing tiers  : {missing_tier[:5]}"
          f"{' ...' if len(missing_tier) > 5 else ''} ({len(missing_tier)})")
    print(f"dates newer than snap: {new_dates}")

    if not APPLY:
        print("\nDRY RUN — re-run with --apply to write changes.")
        return

    backup = DB.with_name(f"habitsdb.txt.bak_custom_ranges_{time.strftime('%Y%m%d_%H%M%S')}")
    shutil.copy2(DB, backup)
    print(f"backup written: {backup}")

    cur[SEC_KEY] = dict(sorted(raw_hist.items()))
    for d in missing_tier:
        tiers[d] = raw_hist[d]  # fallback: raw value as the stored count
    cur[HABIT] = dict(sorted(tiers.items()))

    DB.write_text(json.dumps(cur, indent=2))
    print(f"applied: {len(raw_hist)} raw values restored to {SEC_KEY!r}; "
          f"primary tiers kept ({len(tiers)} days).")


if __name__ == "__main__":
    main()
