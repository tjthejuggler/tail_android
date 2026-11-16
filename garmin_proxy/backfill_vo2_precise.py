#!/usr/bin/env python3
"""
Backfill garmin_cache.json with the PRECISE (one-decimal) VO2max history.

Walks back from today in 300-day GraphQL vo2MaxScalar chunks (the endpoint
rejects ranges over ~1 year, 366d verified OK / 731d rejected) until data
runs out, then forward-fills every calendar day with the last change value —
Garmin only emits a row when the value changes, so in-between days must
inherit the previous row's value to form a continuous series.

Auth is token-resumed from ~/.garminconnect (NO login) exactly like
fetch_data.py, so this is rate-limit safe. ~9 requests for 7 years of
history.

Usage:
    ./venv/bin/python backfill_vo2_precise.py [--until 2020-01-01]
"""

import json
import time
import argparse
import datetime
from pathlib import Path
from typing import Dict, Optional

from garminconnect import Garmin

CACHE_FILE = Path(__file__).parent / "garmin_cache.json"
TOKEN_STORE = Path.home() / ".garminconnect"
CHUNK_DAYS = 300          # < the ~1y API limit
INTER_REQUEST_DELAY = 1.0  # polite pacing, mirrors fetch_data.py


def query_chunk(client: Garmin, start: str, end: str) -> Optional[list]:
    query = f'query{{vo2MaxScalar(startDate:"{start}", endDate:"{end}")}}'
    try:
        resp = client.query_garmin_graphql({"query": query})
    except Exception as e:
        print(f"  chunk {start}..{end}: query failed: {e}")
        return None
    if (resp or {}).get("errors"):
        return None
    rows = ((resp or {}).get("data") or {}).get("vo2MaxScalar")
    if isinstance(rows, str):
        rows = json.loads(rows)
    return rows if isinstance(rows, list) else None


def main() -> None:
    parser = argparse.ArgumentParser(description="Backfill precise VO2max history")
    parser.add_argument("--until", default="2020-01-01",
                        help="Walk back until this date (default: 2020-01-01)")
    args = parser.parse_args()

    if not TOKEN_STORE.exists():
        print("No token store — run auth_bridge.py first")
        raise SystemExit(2)
    client = Garmin()
    client.login(str(TOKEN_STORE))
    print("Token resume OK")

    with open(CACHE_FILE) as f:
        cache = json.load(f)
    data = cache.setdefault("data", {})

    # ── Phase 1: collect every change-row from today back to --until ──
    # rows: {date: precise_value} — only days Garmin recorded a change.
    changes: Dict[str, float] = {}
    end = datetime.date.today()
    until = datetime.date.fromisoformat(args.until)
    while end > until:
        start = max(end - datetime.timedelta(days=CHUNK_DAYS), until)
        rows = query_chunk(client, start.isoformat(), end.isoformat())
        if rows is None:
            print(f"  chunk {start}..{end}: rejected/failed, stopping walk")
            break
        got = 0
        for row in rows:
            g = row.get("generic") if isinstance(row, dict) else None
            if not isinstance(g, dict):
                continue
            d, v = g.get("calendarDate"), g.get("vo2MaxPreciseValue")
            if isinstance(d, str) and len(d) >= 10 and isinstance(v, (int, float)):
                changes[d[:10]] = float(v)
                got += 1
        print(f"  chunk {start}..{end}: {got} change-rows")
        if got == 0:
            break  # walked past the beginning of the data
        end = start
        time.sleep(INTER_REQUEST_DELAY)

    if not changes:
        print("No precise VO2max data returned — aborting without changes.")
        raise SystemExit(1)

    first_day = min(changes)
    last_day = max(changes)
    print(f"\nCollected {len(changes)} change-days: {first_day} .. {last_day}")

    # ── Phase 2: forward-fill every calendar day from first_day to today ──
    # A day inherits the most recent change ON OR BEFORE it. Days before the
    # first row stay untouched (no value existed yet).
    #
    # Days absent from the cache get a SPARSE entry ({"date", "vo2_max_precise"})
    # so the /api/v1/vo2-precise endpoint can serve the full history. Sparse
    # days are inert for the day-by-day endpoints: they carry no "vo2_max"
    # (or any other metric) key, so the app's per-day fetch path skips them —
    # only the dedicated precise-series endpoint reads vo2_max_precise.
    filled = 0
    updated = 0
    created = 0
    day = datetime.date.fromisoformat(first_day)
    today = datetime.date.today()
    carried: Optional[float] = None
    while day <= today:
        ds = day.isoformat()
        v = changes.get(ds)
        if v is not None:
            carried = v
        if carried is not None:
            entry = data.get(ds)
            if entry is None:
                data[ds] = {"date": ds, "vo2_max_precise": carried}
                created += 1
                filled += 1
            elif entry.get("vo2_max_precise") != carried:
                entry["vo2_max_precise"] = carried
                updated += 1
                filled += 1
            else:
                filled += 1
        day += datetime.timedelta(days=1)

    print(f"Forward-filled {filled} days "
          f"({updated} updated, {created} sparse days created)")

    cache.setdefault("metadata", {})["vo2_precise_backfill"] = (
        f"{len(changes)} changes {first_day}..{last_day} @ "
        f"{datetime.datetime.now().isoformat(timespec='seconds')}"
    )
    with open(CACHE_FILE, "w") as f:
        json.dump(cache, f, indent=2)
    print(f"Cache saved: {CACHE_FILE}")


if __name__ == "__main__":
    main()
