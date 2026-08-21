#!/usr/bin/env python3
"""
One-off (2026-08-21): backfill SLEEP_DURATION_MINUTES into the historical
Garmin import JSON.

The original GDPR export zip is gone, but the import JSON it produced contains
the per-night sleep stages (DEEP/LIGHT/REM minutes). Garmin's sleep length
(sleepTimeSeconds in the live API) is exactly deep + light + REM — awake time
excluded — so the duration for every historic night can be reconstructed from
the stages already in the file.

Writes the new SLEEP_DURATION_MINUTES section in place (a .bak backup is kept)
so the app's "import Garmin JSON" can pick it up. Re-importing is safe: the
app merges per-month and only adds the new metric.

Usage:
    python3 scripts/seed_sleep_duration_20260821.py [path/to/garmin_import.json]
"""

import json
import shutil
import sys
from pathlib import Path

DEFAULT_PATH = Path("/home/twain/noteVault/transfer/garmin_import.json")


def main() -> None:
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_PATH
    if not path.exists():
        print(f"Import JSON not found: {path}")
        sys.exit(1)

    with open(path) as f:
        data = json.load(f)

    deep = data.get("DEEP_SLEEP_MINUTES", {})
    light = data.get("LIGHT_SLEEP_MINUTES", {})
    rem = data.get("REM_SLEEP_MINUTES", {})

    dates = set(deep) | set(light) | set(rem)
    if not dates:
        print("No sleep-stage data found — nothing to backfill.")
        sys.exit(1)

    duration = {}
    for d in sorted(dates):
        total = deep.get(d, 0) + light.get(d, 0) + rem.get(d, 0)
        if total > 0:
            duration[d] = total

    backup = path.with_suffix(path.suffix + ".bak")
    shutil.copy2(path, backup)

    data["SLEEP_DURATION_MINUTES"] = duration
    with open(path, "w") as f:
        json.dump(data, f, indent=2)

    items = sorted(duration.items())
    print(f"Backfilled {len(duration)} nights ({items[0][0]} .. {items[-1][0]})")
    print(f"Sample: {items[0][0]} = {items[0][1]} min "
          f"({items[0][1] // 60}h{items[0][1] % 60:02d}m)")
    print(f"Backup written to {backup}")


if __name__ == "__main__":
    main()
