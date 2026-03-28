#!/usr/bin/env python3
"""
Seed Pullups habit data into habitsdb.txt and fix inflated Pushups values.

This script:
1. Parses Pullups.md to get per-day pullup totals and subtype breakdowns
2. Creates a "Pullups" habit entry in habitsdb.txt
3. Subtracts (pullup_total × 4) from "Launch Pushups Widget" for each date
4. Writes subtype breakdown to pullups_subtypes.json
5. Cross-checks with Pushups.md for verification
"""

import json
import re
import shutil
from collections import defaultdict
from pathlib import Path

# === Paths ===
NOTE_VAULT = Path("/home/twain/noteVault")
HABITSDB_DIR = Path("/home/twain/habitsdb")
PULLUPS_MD = NOTE_VAULT / "Pullups.md"
PUSHUPS_MD = NOTE_VAULT / "Pushups.md"
HABITSDB = HABITSDB_DIR / "habitsdb.txt"
HABITSDB_BACKUP = HABITSDB_DIR / "habitsdb_backup_before_pullups_seed.txt"
SUBTYPES_OUT = NOTE_VAULT / "tail" / "pullups_subtypes.json"

# Subtype letter → full name
SUBTYPE_MAP = {
    "c": "chinups",
    "w": "wide",
    "p": "pullups",
    "d": "dip",
    "n": "neutral",
}

PUSHUPS_HABIT_KEY = "Launch Pushups Widget"
PULLUPS_HABIT_KEY = "Pullups"


def parse_pullups(path: Path) -> tuple[dict[str, int], dict[str, dict[str, int]]]:
    """Parse Pullups.md and return (date→total, date→{subtype→count})."""
    totals: dict[str, int] = defaultdict(int)
    subtypes: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))

    # Pattern: YYYY-MM-DD HH:MM:SS > Nc
    pattern = re.compile(r"^(\d{4}-\d{2}-\d{2})\s+\d{2}:\d{2}:\d{2}\s+>\s+(\d+)([cwpdn])\s*$")

    with open(path, "r") as f:
        for line_num, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip():
                continue
            m = pattern.match(line)
            if not m:
                print(f"  WARNING: Could not parse Pullups.md line {line_num}: {line!r}")
                continue
            date_str = m.group(1)
            count = int(m.group(2))
            subtype_letter = m.group(3)
            subtype_name = SUBTYPE_MAP[subtype_letter]

            totals[date_str] += count
            subtypes[date_str][subtype_name] += count

    return dict(totals), {d: dict(v) for d, v in subtypes.items()}


def parse_pushups_md(path: Path) -> dict[str, int]:
    """Parse Pushups.md for cross-checking. Returns date→total."""
    totals: dict[str, int] = defaultdict(int)
    # Pattern: YYYY-MM-DD HH:MM:SS letter N
    pattern = re.compile(r"^(\d{4}-\d{2}-\d{2})\s+\d{2}:\d{2}:\d{2}\s+\w\s+(\d+)\s*$")

    with open(path, "r") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line.strip():
                continue
            m = pattern.match(line)
            if m:
                date_str = m.group(1)
                count = int(m.group(2))
                totals[date_str] += count

    return dict(totals)


def main():
    print("=" * 60)
    print("PULLUPS SEEDING SCRIPT")
    print("=" * 60)

    # --- Step 1: Parse Pullups.md ---
    print("\n[1] Parsing Pullups.md...")
    pullup_totals, pullup_subtypes = parse_pullups(PULLUPS_MD)
    print(f"    Found {len(pullup_totals)} dates with pullup data")
    print(f"    Total pullup entries: {sum(pullup_totals.values())}")
    print(f"    Date range: {min(pullup_totals.keys())} to {max(pullup_totals.keys())}")

    # Show a few sample dates
    print("\n    Sample pullup data (first 5 dates):")
    for date in sorted(pullup_totals.keys())[:5]:
        print(f"      {date}: total={pullup_totals[date]}, subtypes={pullup_subtypes[date]}")

    # --- Step 2: Parse Pushups.md for cross-checking ---
    print("\n[2] Parsing Pushups.md for cross-checking...")
    pushups_md_totals = parse_pushups_md(PUSHUPS_MD)
    print(f"    Found {len(pushups_md_totals)} dates with pushup data in Pushups.md")

    # --- Step 3: Load habitsdb.txt ---
    print("\n[3] Loading habitsdb.txt...")
    with open(HABITSDB, "r") as f:
        habits_data = json.load(f)
    print(f"    Loaded {len(habits_data)} habits")

    if PUSHUPS_HABIT_KEY not in habits_data:
        print(f"    ERROR: '{PUSHUPS_HABIT_KEY}' not found in habitsdb.txt!")
        return

    pushups_habit = habits_data[PUSHUPS_HABIT_KEY]
    print(f"    '{PUSHUPS_HABIT_KEY}' has {len(pushups_habit)} date entries")

    # Check if Pullups already exists
    if PULLUPS_HABIT_KEY in habits_data:
        print(f"    WARNING: '{PULLUPS_HABIT_KEY}' already exists in habitsdb.txt! Will overwrite.")

    # --- Step 4: Create backup ---
    print(f"\n[4] Creating backup at {HABITSDB_BACKUP}...")
    shutil.copy2(HABITSDB, HABITSDB_BACKUP)
    print("    Backup created.")

    # --- Step 5: Fix Pushups (subtract pullup×4) ---
    print(f"\n[5] Fixing '{PUSHUPS_HABIT_KEY}' (subtracting pullup_total × 4)...")
    warnings = []
    changes = []
    for date in sorted(pullup_totals.keys()):
        pullup_total = pullup_totals[date]
        subtract_amount = pullup_total * 4

        if date in pushups_habit:
            old_val = pushups_habit[date]
            new_val = old_val - subtract_amount
            if new_val < 0:
                warnings.append(
                    f"    ⚠ {date}: {old_val} - {subtract_amount} = {new_val} "
                    f"(would go negative! clamping to 0)"
                )
                new_val = 0
            pushups_habit[date] = new_val
            changes.append((date, old_val, new_val, subtract_amount))
        else:
            # Date exists in pullups but not in pushups habit — nothing to subtract
            pass

    print(f"    Modified {len(changes)} dates")
    if warnings:
        print(f"    {len(warnings)} warnings:")
        for w in warnings:
            print(w)

    # Show some changes
    print("\n    Sample changes (last 10):")
    for date, old_val, new_val, sub in changes[-10:]:
        print(f"      {date}: {old_val} → {new_val} (subtracted {sub})")

    # --- Step 6: Create Pullups habit entry ---
    print(f"\n[6] Creating '{PULLUPS_HABIT_KEY}' habit entry...")

    # For dates in pushups habit but not in pullups, add 0
    pullups_entry = {}
    all_pushup_dates = set(pushups_habit.keys())
    all_pullup_dates = set(pullup_totals.keys())

    for date in sorted(all_pushup_dates | all_pullup_dates):
        pullups_entry[date] = pullup_totals.get(date, 0)

    habits_data[PULLUPS_HABIT_KEY] = pullups_entry
    print(f"    Created with {len(pullups_entry)} date entries")
    print(f"    ({len(all_pullup_dates)} with actual data, "
          f"{len(pullups_entry) - len(all_pullup_dates)} zero-filled from pushups dates)")

    # --- Step 7: Write habitsdb.txt ---
    print(f"\n[7] Writing updated habitsdb.txt...")
    with open(HABITSDB, "w") as f:
        json.dump(habits_data, f, indent=2)
    print("    Done.")

    # --- Step 8: Write subtype data ---
    print(f"\n[8] Writing subtype data to {SUBTYPES_OUT}...")
    SUBTYPES_OUT.parent.mkdir(parents=True, exist_ok=True)
    # Sort by date, and sort subtypes within each date
    sorted_subtypes = {}
    for date in sorted(pullup_subtypes.keys()):
        sorted_subtypes[date] = dict(sorted(pullup_subtypes[date].items()))
    with open(SUBTYPES_OUT, "w") as f:
        json.dump(sorted_subtypes, f, indent=2)
    print(f"    Written {len(sorted_subtypes)} date entries.")

    # --- Step 9: Cross-check ---
    print("\n[9] Cross-checking with Pushups.md...")
    print("    Dates that appear in BOTH Pushups.md and Pullups.md:")
    overlap_dates = sorted(set(pushups_md_totals.keys()) & set(pullup_totals.keys()))
    if overlap_dates:
        for date in overlap_dates:
            pullup_total = pullup_totals[date]
            pushup_md_total = pushups_md_totals[date]
            # Find the change we made
            change_info = next((c for c in changes if c[0] == date), None)
            if change_info:
                _, old_val, new_val, sub = change_info
                print(f"      {date}:")
                print(f"        Pullups.md total: {pullup_total}")
                print(f"        Pushups.md total: {pushup_md_total}")
                print(f"        habitsdb before: {old_val}, after: {new_val} (subtracted {sub})")
                print(f"        Expected pushups-only value ≈ {pushup_md_total}")
                diff = abs(new_val - pushup_md_total)
                if diff == 0:
                    print(f"        ✓ MATCH")
                else:
                    print(f"        Δ difference: {diff}")
            else:
                print(f"      {date}: pullups={pullup_total}, pushups_md={pushup_md_total} "
                      f"(no change in habitsdb — date may not have existed)")
    else:
        print("    No overlapping dates found.")

    # Also show recent dates for general verification
    print("\n    Recent Pushups habit values (after fix):")
    recent_pushup_dates = sorted(
        [d for d in pushups_habit.keys() if d >= "2025-11"],
    )
    for date in recent_pushup_dates[-15:]:
        pullup_val = pullup_totals.get(date, 0)
        pushup_md_val = pushups_md_totals.get(date, "-")
        print(f"      {date}: habitsdb={pushups_habit[date]}, "
              f"pullups_total={pullup_val}, pushups_md={pushup_md_val}")

    print("\n" + "=" * 60)
    print("DONE! Summary:")
    print(f"  - Backup: {HABITSDB_BACKUP}")
    print(f"  - Pullups habit added with {len(pullups_entry)} dates")
    print(f"  - Pushups habit fixed for {len(changes)} dates")
    print(f"  - Subtype data: {SUBTYPES_OUT}")
    print("=" * 60)


if __name__ == "__main__":
    main()
