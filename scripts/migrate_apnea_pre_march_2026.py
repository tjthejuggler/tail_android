#!/usr/bin/env python3
"""
One-time migration script for apnea habits.

Problem:
  Before March 12, 2026, the habits "Apnea apb" and "Apnea practiced" stored
  **session counts** (small integers like 1–5) in the primary slot, because
  there was no wags integration yet to provide minutes data.

  After March 12, 2026, wags started writing **minutes** to the primary slot
  (larger values like 9–15).

  Now that tail supports secondary values + fallback, the old session-count
  data needs to be moved from the primary slot to the secondary_value slot
  so that:
    - Primary (minutes) = 0 for those old dates (no minutes data existed)
    - Secondary (sessions) = the old session count
    - Fallback kicks in: points = session count for those days

What this script does:
  For habits "Apnea apb" and "Apnea practiced":
    For every date < 2026-03-12 where primary value > 0:
      1. Move the value to secondary_value:<habitName>[date] (max merge)
      2. Set primary value to 0

Usage:
  python3 scripts/migrate_apnea_pre_march_2026.py
  python3 scripts/migrate_apnea_pre_march_2026.py --dry-run   # preview only
  python3 scripts/migrate_apnea_pre_march_2026.py --db /path/to/habitsdb.txt
"""

import json
import os
import shutil
from datetime import date

DEFAULT_DB = os.path.expanduser("~/habitsdb/habitsdb.txt")
CUTOFF_DATE = "2026-03-12"
HABITS_TO_MIGATE = ["Apnea apb", "Apnea practiced"]


def migrate(db_path: str, dry_run: bool = False):
    print(f"Loading database from: {db_path}")
    with open(db_path, "r") as f:
        db = json.load(f)

    total_moved = 0

    for habit_name in HABITS_TO_MIGATE:
        primary_key = habit_name
        secondary_key = f"secondary_value:{habit_name}"

        if primary_key not in db:
            print(f"  WARNING: '{primary_key}' not found in database, skipping.")
            continue

        primary_entries = db[primary_key]
        secondary_entries = db.get(secondary_key, {})

        moved = 0
        for date_str, value in list(primary_entries.items()):
            if date_str >= CUTOFF_DATE:
                continue  # Only migrate dates before the cutoff
            if value <= 0:
                continue  # Skip zero entries

            # Move to secondary (max merge)
            existing_secondary = secondary_entries.get(date_str, 0)
            secondary_entries[date_str] = max(existing_secondary, value)

            # Zero out primary
            primary_entries[date_str] = 0
            moved += 1

        # Update the database
        db[primary_key] = primary_entries
        if secondary_entries:
            db[secondary_key] = secondary_entries

        total_moved += moved
        print(f"  {habit_name}: moved {moved} dates from primary → secondary "
              f"(pre-{CUTOFF_DATE})")

    print(f"\nTotal dates migrated: {total_moved}")

    if dry_run:
        print("\n[DRY RUN] No changes written.")
        return

    # Backup
    backup_path = db_path + f".backup_pre_apnea_migration_{date.today().isoformat()}"
    shutil.copy2(db_path, backup_path)
    print(f"Backup saved to: {backup_path}")

    # Write
    with open(db_path, "w") as f:
        json.dump(db, f, indent=2, sort_keys=True)
    print(f"Database written to: {db_path}")


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Migrate pre-March-2026 apnea session counts to secondary value slot")
    parser.add_argument("--db", default=DEFAULT_DB, help=f"Path to habitsdb.txt (default: {DEFAULT_DB})")
    parser.add_argument("--dry-run", action="store_true", help="Preview changes without writing")
    args = parser.parse_args()

    print(f"=== Apnea Data Migration ===")
    print(f"Habits: {HABITS_TO_MIGATE}")
    print(f"Cutoff: dates before {CUTOFF_DATE}")
    print(f"Mode: {'DRY RUN' if args.dry_run else 'LIVE'}")
    print()

    migrate(args.db, dry_run=args.dry_run)
