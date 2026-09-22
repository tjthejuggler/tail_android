#!/usr/bin/env python3
"""
import_meditation_data.py

Imports meditation sessions and minutes from meditation_output.json (extracted
from Garmin GDPR ZIP) into the Tail habits database (habitsdb.txt).

Strategy:
  - Minutes  → primary value slot   ("Meditations" habit)
  - Sessions → secondary value slot ("secondary_value:Meditations" key)

Uses max() merge so existing higher values (e.g. Wags-imported minutes) are
never overwritten.  Safe to run repeatedly — idempotent.

Usage:
  python3 import_meditation_data.py [meditation_json] [habitsdb_txt]

Defaults:
  meditation_json = ./meditation_output.json
  habitsdb_txt    = ~/habitsdb/habitsdb.txt
"""

import json
import os
import sys
from datetime import date, datetime

HABIT_NAME = "Meditations"
SECONDARY_KEY = f"secondary_value:{HABIT_NAME}"

DEFAULT_MEDITATION_JSON = os.path.join(os.path.dirname(__file__), "meditation_output.json")
DEFAULT_HABITSDB = os.path.expanduser("~/habitsdb/habitsdb.txt")


def load_json(path):
    with open(path, "r") as f:
        return json.load(f)


def save_json(path, data):
    with open(path, "w") as f:
        json.dump(data, f, indent=4)


def main():
    meditation_json_path = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_MEDITATION_JSON
    habitsdb_path = sys.argv[2] if len(sys.argv) > 2 else DEFAULT_HABITSDB

    # ── Load data ────────────────────────────────────────────────────────
    print(f"Loading meditation data from: {meditation_json_path}")
    meditation_data = load_json(meditation_json_path)
    daily = meditation_data.get("daily", {})
    print(f"  → {len(daily)} days of meditation data")

    print(f"Loading habits DB from: {habitsdb_path}")
    db = load_json(habitsdb_path)
    print(f"  → {len(db)} habit keys in DB")

    # ── Ensure habit entries exist ───────────────────────────────────────
    if HABIT_NAME not in db:
        db[HABIT_NAME] = {}
        print(f"  → Created primary slot '{HABIT_NAME}'")
    if SECONDARY_KEY not in db:
        db[SECONDARY_KEY] = {}
        print(f"  → Created secondary slot '{SECONDARY_KEY}'")

    primary_entries = db[HABIT_NAME]
    secondary_entries = db[SECONDARY_KEY]

    # ── Merge meditation data ────────────────────────────────────────────
    minutes_added = 0
    minutes_updated = 0
    sessions_added = 0
    sessions_updated = 0
    minutes_skipped = 0
    sessions_skipped = 0

    for date_str, info in sorted(daily.items()):
        garmin_minutes = info.get("minutes", 0)
        garmin_sessions = info.get("sessions", 0)

        # Primary slot: minutes (max merge)
        existing_minutes = primary_entries.get(date_str, 0)
        if garmin_minutes > existing_minutes:
            primary_entries[date_str] = garmin_minutes
            if existing_minutes == 0:
                minutes_added += 1
            else:
                minutes_updated += 1
        else:
            minutes_skipped += 1

        # Secondary slot: sessions (max merge)
        existing_sessions = secondary_entries.get(date_str, 0)
        if garmin_sessions > existing_sessions:
            secondary_entries[date_str] = garmin_sessions
            if existing_sessions == 0:
                sessions_added += 1
            else:
                sessions_updated += 1
        else:
            sessions_skipped += 1

    # ── Summary ──────────────────────────────────────────────────────────
    total_minutes = sum(primary_entries.values())
    total_sessions = sum(secondary_entries.values())

    print()
    print("═" * 60)
    print("IMPORT SUMMARY")
    print("═" * 60)
    print(f"  Primary slot ('{HABIT_NAME}'):")
    print(f"    Minutes added (new dates):   {minutes_added}")
    print(f"    Minutes updated (higher):    {minutes_updated}")
    print(f"    Minutes skipped (existing ≥): {minutes_skipped}")
    print(f"    Total days in primary:       {len(primary_entries)}")
    print(f"    Total minutes in primary:    {total_minutes}")
    print()
    print(f"  Secondary slot ('{SECONDARY_KEY}'):")
    print(f"    Sessions added (new dates):   {sessions_added}")
    print(f"    Sessions updated (higher):    {sessions_updated}")
    print(f"    Sessions skipped (existing ≥): {sessions_skipped}")
    print(f"    Total days in secondary:     {len(secondary_entries)}")
    print(f"    Total sessions in secondary:  {total_sessions}")
    print("═" * 60)

    # ── Save ─────────────────────────────────────────────────────────────
    print(f"\nSaving to: {habitsdb_path}")
    save_json(habitsdb_path, db)
    print("Done! The data will sync to your phone via Syncthing.")
    print()
    print("NOTE: Enable 'Secondary value' for 'Meditations' in the Tail edit")
    print("      screen to see the session count as Value 2 in graphs.")


if __name__ == "__main__":
    main()
