#!/usr/bin/env python3
"""
One-time adjustment: water bottle is 10% smaller than believed.

Scales every historical value of the "Water" habit (stored in ml) by 0.9,
so all recorded water intake becomes 10% smaller.

- "Morning Water" is a 0/1 flag habit and is NOT touched.
- Creates a timestamped backup before writing.
- Rounds to the nearest integer ml.

Usage:
  python3 scripts/adjust_water_volume_20260828.py --dry-run   # preview only
  python3 scripts/adjust_water_volume_20260828.py             # apply
  python3 scripts/adjust_water_volume_20260828.py --db /path/to/habitsdb.txt
"""

import argparse
import json
import shutil
from datetime import datetime
from pathlib import Path

DEFAULT_DB = Path.home() / "habitsdb" / "habitsdb.txt"
HABIT_KEY = "Water"
SCALE = 0.9


def adjust(db_path: Path, dry_run: bool) -> None:
    print(f"Loading database from: {db_path}")
    db = json.loads(db_path.read_text())

    if HABIT_KEY not in db:
        raise SystemExit(f"ERROR: habit '{HABIT_KEY}' not found in database.")

    entries = db[HABIT_KEY]
    changed = 0
    total_before = 0
    total_after = 0
    for date_str, value in entries.items():
        if not isinstance(value, (int, float)) or value <= 0:
            continue
        new_value = round(value * SCALE)
        total_before += value
        total_after += new_value
        if new_value != value:
            print(f"  {date_str}: {value} -> {new_value}")
            entries[date_str] = new_value
            changed += 1

    print(f"\nDates adjusted: {changed}")
    print(f"Lifetime total: {total_before} ml -> {total_after} ml "
          f"({total_after - total_before:+d} ml)")

    if dry_run:
        print("\n[DRY RUN] No changes written.")
        return

    backup = db_path.with_name(
        f"habitsdb_backup_before_water_scale_{datetime.now():%Y%m%d_%H%M%S}.txt"
    )
    shutil.copy2(db_path, backup)
    print(f"Backup written to: {backup}")

    db_path.write_text(json.dumps(db))
    print(f"Database written to: {db_path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db", type=Path, default=DEFAULT_DB)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()
    adjust(args.db, args.dry_run)


if __name__ == "__main__":
    main()
