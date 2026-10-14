#!/usr/bin/env python3
"""
Injury (episode) migration — desktop side (2026-10-14).

Converts the legacy daily "Injured" habit into the structured episode format,
matching the in-app one-time migration (performInjuryEpisodeMigration) so the
synced data and the phone converge on the same result. Idempotent.

What it does:
  1. habitsdb.txt["Injured"]: collapses the 68 daily status rows (severity
     countdown 7..0) into the derived injured_today flag — 1 on every day of
     the single real episode (onset 2026-08-06 .. resolved 2026-08-27, the
     last day with points), 0 elsewhere. All existing report readers keep
     working unchanged.
  2. Injured.json (text log): deletes every text row on a zero-point day —
     the 39 phantom "Pulled muscle in upper left leg" rows of
     2026-08-28 → 2026-10-05. Rows on real episode days are kept.
  3. Writes noteVault/tail/Injured_episodes.json — the machine-readable
     episode store the research pipeline reads (injury_onset events for the
     event-study engine; report type event_study, event="injury_onset").

The episode is seeded with the user-confirmed metadata:
  type=pulled_muscle, body_part=upper_leg, side=left, severity=unknown(null).

Usage:
  python3 scripts/migrate_injury_episode_20261014.py [--dry-run]
"""

import argparse
import datetime
import json
import shutil
from pathlib import Path

HABITS_DB = Path("/home/twain/habitsdb/habitsdb.txt")
TEXT_LOG = Path("/home/twain/noteVault/tail/Injured.json")
EPISODES_FILE = Path("/home/twain/noteVault/tail/Injured_episodes.json")
HABIT = "Injured"
KNOWN_ONSET = "2026-08-06"

KNOWN_EPISODE = {
    "id": KNOWN_ONSET,
    "onsetDate": KNOWN_ONSET,
    "resolvedDate": "2026-08-27",
    "type": "pulled_muscle",
    "bodyPart": "upper_leg",
    "bodyPartDetail": "",
    "side": "left",
    "severity": None,
    "note": "Pulled muscle in upper left leg",
}


def d(s):
    return datetime.date.fromisoformat(s)


def spans_of(points_days):
    """Contiguous runs of (date) → list of (onset, last) inclusive."""
    spans = []
    start = prev = None
    for day in points_days:
        if start is None or (day - prev).days != 1:
            if start is not None:
                spans.append((start, prev))
            start = day
        prev = day
    if start is not None:
        spans.append((start, prev))
    return spans


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    db = json.loads(HABITS_DB.read_text())
    entries = db.get(HABIT)
    if entries is None:
        print(f"No '{HABIT}' habit in habitsdb — nothing to migrate.")
        return

    points_days = sorted(d(x) for x, v in entries.items() if v and v > 0)
    spans = spans_of(points_days)
    print(f"'{HABIT}': {len(entries)} daily rows, {len(points_days)} point days, "
          f"{len(spans)} contiguous span(s): {[(a.isoformat(), b.isoformat()) for a, b in spans]}")

    # Build episode list: the known historical episode gets confirmed metadata.
    episodes = []
    for onset, last in spans:
        if onset.isoformat() == KNOWN_ONSET:
            ep = dict(KNOWN_EPISODE)
            ep["resolvedDate"] = last.isoformat()  # last day with points (2026-08-27)
            episodes.append(ep)
        else:
            episodes.append({
                "id": onset.isoformat(), "onsetDate": onset.isoformat(),
                "resolvedDate": last.isoformat(), "type": "other", "bodyPart": "other",
                "bodyPartDetail": "", "side": "na", "severity": None, "note": "",
            })
    # Respect an already-migrated episodes file (idempotent merge by id).
    existing = []
    if EPISODES_FILE.exists():
        try:
            existing = json.loads(EPISODES_FILE.read_text())
        except Exception:
            existing = []
    merged = {e["id"]: e for e in existing}
    for e in episodes:
        merged.setdefault(e["id"], e)
    episodes_out = sorted(merged.values(), key=lambda e: e["onsetDate"])

    # 1. Derived daily flag rewrite.
    def inside(day):
        return any(e["onsetDate"] <= day.isoformat()
                   and day.isoformat() <= (e["resolvedDate"] or datetime.date.today().isoformat())
                   for e in episodes_out)

    new_entries = {day: (1 if inside(d(day)) else 0) for day, v in entries.items()}
    changed_db = new_entries != entries
    print(f"habitsdb flag rewrite: {sum(new_entries.values())} injured days (1), "
          f"{sum(1 for v in new_entries.values() if v == 0)} zero days; changed={changed_db}")

    # 2. Text-log phantom-row cleanup (rows on zero-point days).
    phantom = []
    text_log = {}
    if TEXT_LOG.exists():
        text_log = json.loads(TEXT_LOG.read_text())
        phantom = [ts for ts in text_log if new_entries.get(ts[:10], 0) == 0]
        print(f"text log: {len(text_log)} rows, {len(phantom)} phantom rows on unchecked days")

    # 3. Write everything.
    if args.dry_run:
        print("DRY RUN — no files written.")
        return

    stamp = datetime.datetime.now().strftime("%Y%m%d_%H%M%S")
    if changed_db:
        shutil.copy(HABITS_DB, HABITS_DB.with_name(f"habitsdb.txt.bak_pre_injury_episode_{stamp}"))
        db[HABIT] = new_entries
        HABITS_DB.write_text(json.dumps(db, indent=2, sort_keys=True))
        print(f"habitsdb.txt rewritten (backup habitsdb.txt.bak_pre_injury_episode_{stamp})")
    if phantom:
        shutil.copy(TEXT_LOG, TEXT_LOG.with_name(f"Injured.json.bak_pre_injury_episode_{stamp}"))
        for ts in phantom:
            del text_log[ts]
        TEXT_LOG.write_text(json.dumps(text_log, indent=2, sort_keys=True))
        print(f"Injured.json: deleted {len(phantom)} phantom rows (backup saved)")
    EPISODES_FILE.write_text(json.dumps(episodes_out, indent=2))
    print(f"Injured_episodes.json written with {len(episodes_out)} episode(s):")
    for e in episodes_out:
        print(f"  onset={e['onsetDate']} resolved={e['resolvedDate']} type={e['type']} "
              f"body={e['bodyPart']} side={e['side']} severity={e['severity']}")


if __name__ == "__main__":
    main()
