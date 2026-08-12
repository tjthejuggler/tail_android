#!/usr/bin/env python3
"""
Seed Fiction Video Intake habit with historical movie/TV data.

Reads a cleaned JSON of KDE video-watching history, filters out non-movie
content (chess courses, garbage filenames, YouTube downloads), then:

  1. Merges all kept entries into the text-input JSON file
     (fiction_videos.txt) — format: { "YYYY-MM-DD HH:MM:SS": "Title" }
  2. Updates habitsdb.txt "Fiction Video Intake" daily counts using
     MAX(existing, text-entry-count) so existing data is never reduced.

Both files are synced to the phone via Syncthing, so the seeded data
appears on the phone automatically.

Usage:
    python3 scripts/seed_movie_history.py            # full run
    python3 scripts/seed_movie_history.py --dry-run   # preview only, no writes

Idempotent: safe to run multiple times (MAX strategy means re-runs are no-ops).
"""

import json
import re
import shutil
import sys
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path

# ── Paths ────────────────────────────────────────────────────────────────────

SOURCE_JSON = Path("/home/twain/Projects/small_scripts/kde_video_history.json")
HABITSDB_DIR = Path("/home/twain/habitsdb")
HABITSDB = HABITSDB_DIR / "habitsdb.txt"
TEXT_INPUT_FILE = Path("/home/twain/noteVault/tail/fiction_videos.txt")

HABIT_KEY = "Fiction Video Intake"

# ── Filtering ────────────────────────────────────────────────────────────────

# Chess course chapter keywords (lowercased substrings)
_CHESS_KEYWORDS = [
    "castling", "pawn-play", "pawn play", "position-of-the", "position of the",
    "unprotected-pawns", "unprotected pawns", "tactical-alertness",
    "tactical alertness", "mirroring", "starting-position", "starting position",
    "restricting-the", "restricting the", "castle-as", "castle as",
    "activating-the", "activating the", "what-to-avoid", "what to avoid",
    "how-to-get-the-most", "how to get the most",
]


def is_chess_course(title: str) -> bool:
    """Detect chess course chapter videos (hyphenated Capitalized-Words)."""
    tl = title.lower()
    if any(kw in tl for kw in _CHESS_KEYWORDS):
        return True
    # Pattern: digits + hyphenated capitalized words (e.g. '04Castling-Rules')
    if re.match(r"^\d{1,2}[A-Z][a-zA-Z]+(-[A-Z][a-zA-Z]+)+", title):
        return True
    # Bare chapter names from the chess course
    if title.strip() in ("Introduction",):
        return True
    return False


def is_garbage(title: str) -> bool:
    """Detect garbage / corrupted filenames."""
    t = title.strip()
    if len(t) <= 3:
        return True
    # Hex / UUID patterns
    if re.match(r"^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}", t):
        return True
    # Scene## (porn scene labels)
    if re.match(r"^Scene\d{1,3}$", t, re.IGNORECASE):
        return True
    # Short alphanumeric gibberish (no spaces, no year, no SxxExx)
    if len(t) <= 12 and re.match(r"^[a-zA-Z0-9]+$", t):
        if not re.search(r"(20\d{2}|19\d{2}|S\d+E\d+)", t):
            # Not a simple capitalized word like "Dave"
            if not re.match(r"^[A-Z][a-z]{3,}$", t):
                return True
    # Bare technical labels
    if t in ("Vid", "Ph", "Ktkl", "Cassette Playing"):
        return True
    return False


def is_youtube(title: str) -> bool:
    """Detect YouTube download artifacts."""
    tl = title.lower()
    return any(kw in tl for kw in ["ytdown", "youtube"])


def should_filter(title: str) -> str | None:
    """Return filter category if title should be excluded, else None."""
    if is_chess_course(title):
        return "chess"
    if is_garbage(title):
        return "garbage"
    if is_youtube(title):
        return "youtube"
    return None


# ── Main ─────────────────────────────────────────────────────────────────────

def main():
    dry_run = "--dry-run" in sys.argv

    print("=" * 70)
    print("MOVIE HISTORY SEED — Fiction Video Intake")
    print(f"  Mode: {'DRY RUN (no writes)' if dry_run else 'LIVE'}")
    print("=" * 70)

    # ── Step 1: Load source JSON ──────────────────────────────────────────
    print(f"\n[1] Loading source JSON: {SOURCE_JSON}")
    with open(SOURCE_JSON, "r", encoding="utf-8") as f:
        raw = json.load(f)
    print(f"    {len(raw)} entries loaded")
    print(f"    Date range: {min(raw.keys())[:10]} to {max(raw.keys())[:10]}")

    # ── Step 2: Filter non-movie content ──────────────────────────────────
    print("\n[2] Filtering non-movie content...")
    filtered_log = defaultdict(list)
    kept = {}
    for ts, title in raw.items():
        cat = should_filter(title)
        if cat:
            filtered_log[cat].append((ts, title))
        else:
            kept[ts] = title

    total_filtered = sum(len(v) for v in filtered_log.values())
    print(f"    Kept: {len(kept)} entries")
    print(f"    Filtered: {total_filtered} entries")
    for cat, items in sorted(filtered_log.items()):
        unique = sorted(set(t for _, t in items))
        print(f"      {cat}: {len(items)} entries ({len(unique)} unique titles)")
        for t in unique[:8]:
            print(f"        - {t}")
        if len(unique) > 8:
            print(f"        ... and {len(unique) - 8} more")

    # ── Step 3: Load existing text-input file ──────────────────────────────
    print(f"\n[3] Loading existing text-input file: {TEXT_INPUT_FILE}")
    with open(TEXT_INPUT_FILE, "r", encoding="utf-8") as f:
        existing = json.load(f)
    print(f"    {len(existing)} existing entries")

    # ── Step 4: Merge ──────────────────────────────────────────────────────
    print("\n[4] Merging entries...")
    # Union: kept JSON entries + existing entries (no data lost)
    merged = {**kept, **existing}
    overlap = len(kept) + len(existing) - len(merged)
    print(f"    Merged total: {len(merged)} entries")
    print(f"    Overlap (same timestamp): {overlap}")

    # Per-day breakdown
    day_entries = defaultdict(list)
    for ts, title in merged.items():
        day_entries[ts[:10]].append((ts, title))
    for day in day_entries:
        day_entries[day].sort(key=lambda x: x[0])

    print(f"    Unique days: {len(day_entries)}")

    # ── Step 5: Load habitsdb ──────────────────────────────────────────────
    print(f"\n[5] Loading habitsdb: {HABITSDB}")
    with open(HABITSDB, "r", encoding="utf-8") as f:
        habits = json.load(f)

    if HABIT_KEY not in habits:
        print(f"    WARNING: '{HABIT_KEY}' not found in habitsdb — creating new entry")
        habits[HABIT_KEY] = {}

    fvi = habits[HABIT_KEY]
    print(f"    '{HABIT_KEY}': {len(fvi)} existing dates")

    # ── Step 6: Compute habitsdb changes ───────────────────────────────────
    print("\n[6] Computing habitsdb changes (MAX strategy)...")
    changes = []
    for day in sorted(day_entries.keys()):
        text_count = len(day_entries[day])
        old = fvi.get(day, 0)
        new = max(old, text_count)
        if new > old:
            changes.append((day, old, new, text_count))

    brand_new = [c for c in changes if c[1] == 0]
    increased = [c for c in changes if c[1] > 0]
    print(f"    Dates changed: {len(changes)}")
    print(f"      Brand-new (was 0/missing): {len(brand_new)}")
    print(f"      Increased: {len(increased)}")
    print(f"      Unchanged: {len(fvi) - len(increased)}")

    total_old = sum(fvi.get(d, 0) for d in fvi)
    # Compute new total
    new_fvi = dict(fvi)
    for day, old, new, _ in changes:
        new_fvi[day] = new
    total_new = sum(new_fvi.values())
    print(f"    Total count: {total_old} → {total_new} (+{total_new - total_old})")

    # Show sample changes
    if increased:
        print(f"\n    Sample increased dates (first 10):")
        for day, old, new, tc in increased[:10]:
            titles = [t for _, t in day_entries[day]]
            print(f"      {day}: {old} → {new}  ({', '.join(titles[:3])}{'...' if len(titles)>3 else ''})")

    if dry_run:
        print("\n" + "=" * 70)
        print("DRY RUN COMPLETE — no files were modified.")
        print("Run without --dry-run to apply changes.")
        print("=" * 70)
        return

    # ── Step 7: Create backups ─────────────────────────────────────────────
    timestamp = datetime.now().strftime("%Y-%m-%d_%H%M%S")
    print(f"\n[7] Creating backups (timestamp: {timestamp})...")

    hdb_backup = HABITSDB_DIR / f"habitsdb_backup_pre_movie_seed_{timestamp}.txt"
    txt_backup = TEXT_INPUT_FILE.parent / f"fiction_videos_backup_pre_movie_seed_{timestamp}.txt"

    shutil.copy2(HABITSDB, hdb_backup)
    print(f"    habitsdb backup: {hdb_backup}")
    shutil.copy2(TEXT_INPUT_FILE, txt_backup)
    print(f"    text-input backup: {txt_backup}")

    # ── Step 8: Write merged text-input file ───────────────────────────────
    print(f"\n[8] Writing merged text-input file: {TEXT_INPUT_FILE}")
    sorted_merged = dict(sorted(merged.items()))
    with open(TEXT_INPUT_FILE, "w", encoding="utf-8") as f:
        json.dump(sorted_merged, f, indent=2, ensure_ascii=False)
    print(f"    Written {len(sorted_merged)} entries (sorted chronologically)")

    # ── Step 9: Write updated habitsdb ─────────────────────────────────────
    print(f"\n[9] Writing updated habitsdb: {HABITSDB}")
    habits[HABIT_KEY] = new_fvi
    with open(HABITSDB, "w", encoding="utf-8") as f:
        json.dump(habits, f, indent=2, ensure_ascii=False)
    print(f"    Updated '{HABIT_KEY}': {len(new_fvi)} dates, {total_new} total count")

    # ── Summary ────────────────────────────────────────────────────────────
    print("\n" + "=" * 70)
    print("SEED COMPLETE!")
    print("=" * 70)
    print(f"  Text-input file:  {len(existing)} → {len(merged)} entries")
    print(f"  habitsdb changes: {len(changes)} dates ({len(brand_new)} new, {len(increased)} increased)")
    print(f"  Total count:      {total_old} → {total_new}")
    print(f"\n  Backups:")
    print(f"    {hdb_backup}")
    print(f"    {txt_backup}")
    print(f"\n  Files will sync to phone via Syncthing automatically.")
    print(f"  The app's internal backup (text_input_backups/) will update on")
    print(f"  next app launch when it detects the external file has more data.")


if __name__ == "__main__":
    main()
