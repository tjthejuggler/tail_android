#!/usr/bin/env python3
"""
Seed pushups subtype data from Pushups.md and habitsdb.txt.

Creates /home/twain/habitsdb/pushups_subtypes.json with per-date subtype breakdowns.

Logic:
- Dates with entries in Pushups.md use those subtype breakdowns
- If Pushups.md total < habitsdb total, remainder goes to "standard"
- Dates without Pushups.md entries get entire count as "standard"
- Dates with count 0 are skipped
"""

import json
import re
from collections import defaultdict
from pathlib import Path

# === Paths ===
PUSHUPS_MD = Path("/home/twain/noteVault/Pushups.md")
HABITSDB = Path("/home/twain/habitsdb/habitsdb.txt")
OUTPUT = Path("/home/twain/habitsdb/pushups_subtypes.json")

PUSHUPS_HABIT_KEY = "Pushups"

# Letter → subtype name
SUBTYPE_MAP = {
    "s": "standard",
    "n": "standard",  # treat n same as s
    "c": "closed",
    "w": "wide",
    "d": "decline",
    "i": "invert",
}


def parse_pushups_md(path: Path) -> dict[str, dict[str, int]]:
    """Parse Pushups.md → {date: {subtype: count}}."""
    result: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    pattern = re.compile(
        r"^(\d{4}-\d{2}-\d{2})\s+\d{2}:\d{2}:\d{2}\s+([scwdin])\s+(\d+)\s*$"
    )

    with open(path, "r") as f:
        for line_num, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line.strip():
                continue
            m = pattern.match(line)
            if not m:
                print(f"  WARNING: Could not parse Pushups.md line {line_num}: {line!r}")
                continue
            date_str = m.group(1)
            letter = m.group(2)
            count = int(m.group(3))
            subtype = SUBTYPE_MAP[letter]
            result[date_str][subtype] += count

    return {d: dict(v) for d, v in result.items()}


def load_habitsdb_pushups(path: Path) -> dict[str, int]:
    """Load habitsdb.txt and return {date: count} for Pushups."""
    with open(path, "r") as f:
        data = json.load(f)

    if PUSHUPS_HABIT_KEY not in data:
        raise KeyError(f"'{PUSHUPS_HABIT_KEY}' not found in habitsdb.txt")

    return data[PUSHUPS_HABIT_KEY]


def build_subtypes(
    habitsdb_pushups: dict[str, int],
    md_subtypes: dict[str, dict[str, int]],
) -> dict[str, dict[str, int]]:
    """Merge habitsdb totals with Pushups.md subtype breakdowns."""
    result: dict[str, dict[str, int]] = {}

    for date, total in sorted(habitsdb_pushups.items()):
        if total == 0:
            continue

        if date in md_subtypes:
            breakdown = dict(md_subtypes[date])
            md_total = sum(breakdown.values())
            remainder = total - md_total
            if remainder > 0:
                breakdown["standard"] = breakdown.get("standard", 0) + remainder
            elif remainder < 0:
                print(
                    f"  WARNING: Pushups.md total ({md_total}) > habitsdb total "
                    f"({total}) for {date}. Using Pushups.md breakdown as-is."
                )
            result[date] = breakdown
        else:
            result[date] = {"standard": total}

    return result


def main():
    print("=" * 60)
    print("PUSHUPS SUBTYPES SEEDING SCRIPT")
    print("=" * 60)

    # Step 1: Parse Pushups.md
    print("\n[1] Parsing Pushups.md...")
    md_subtypes = parse_pushups_md(PUSHUPS_MD)
    md_dates = len(md_subtypes)
    md_entries = sum(sum(v.values()) for v in md_subtypes.values())
    print(f"    Found {md_dates} dates with subtype data")
    print(f"    Total reps from Pushups.md: {md_entries}")
    if md_subtypes:
        all_subtypes = set()
        for v in md_subtypes.values():
            all_subtypes.update(v.keys())
        print(f"    Subtypes found: {sorted(all_subtypes)}")

    # Step 2: Load habitsdb pushups
    print("\n[2] Loading habitsdb.txt pushups data...")
    habitsdb_pushups = load_habitsdb_pushups(HABITSDB)
    nonzero = {d: c for d, c in habitsdb_pushups.items() if c > 0}
    print(f"    Total dates in habitsdb: {len(habitsdb_pushups)}")
    print(f"    Non-zero dates: {len(nonzero)}")
    print(f"    Total reps from habitsdb: {sum(nonzero.values())}")

    # Step 3: Build merged subtypes
    print("\n[3] Building subtype breakdown...")
    subtypes = build_subtypes(habitsdb_pushups, md_subtypes)
    dates_from_md = sum(1 for d in subtypes if d in md_subtypes)
    dates_default = len(subtypes) - dates_from_md
    print(f"    Dates with Pushups.md breakdown: {dates_from_md}")
    print(f"    Dates defaulting to standard: {dates_default}")
    print(f"    Total dates in output: {len(subtypes)}")

    # Step 4: Write output
    print(f"\n[4] Writing to {OUTPUT}...")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT, "w") as f:
        json.dump(subtypes, f, indent=2, sort_keys=True)
    print(f"    Written {OUTPUT.stat().st_size} bytes")

    print("\n" + "=" * 60)
    print("DONE")
    print("=" * 60)


if __name__ == "__main__":
    main()
