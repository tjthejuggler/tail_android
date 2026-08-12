#!/usr/bin/env python3
"""
Movie Watcher Daemon — polls the KDE Activity Manager database for new video
plays, cleans the filenames into human-readable titles, and maintains a cache
file that the Tail Bridge server serves to the Android app.

Design:
  • Polls the KDE Activity SQLite DB every N seconds (default 60).
  • Tracks a high-water-mark (last-seen `start` timestamp) so only NEW
    entries are processed on each poll — O(new rows), not O(all rows).
  • Cleans each filename using movie_name_cleaner (same logic as the
    one-shot clean_video_history.py).
  • Captures BOTH start and end times from the KDE DB.
  • A single movie title watched multiple times (started/stopped/restarted)
    accumulates all its sessions — nothing is dropped.
  • Writes movie_cache.json atomically (temp file + rename).
  • On first run (no state file), does a full backfill of all video history.

Cache format (movie_cache.json):
  {
    "movies": [
      {
        "title": "All Her Fault S01E02",
        "season": 1,
        "episode": 2,
        "raw": "All.Her.Fault.S01E02.1080p.mkv",
        "date": "2026-02-11",
        "sessions": [
          {"start": "2026-02-11 17:07:51", "end": "2026-02-11 18:30:00",
           "start_unix": 1769238471, "end_unix": 1769243400,
           "duration_min": 82}
        ],
        "last_watched": "2026-02-11 17:07:51",
        "total_watch_min": 82
      },
      ...
    ],
    "metadata": {
      "last_updated": "2026-08-12T11:45:00",
      "total_entries": 1234,
      "last_seen_start": 1769238471
    }
  }

State file (movie_watcher_state.json):
  {"last_seen_start": 1769238471}
"""

from __future__ import annotations

import json
import logging
import os
import sqlite3
import sys
import time
from collections import OrderedDict
from datetime import datetime, timezone
from pathlib import Path
from typing import List, Dict, Any, Optional, Tuple

# Allow running from anywhere — resolve paths relative to this file
SCRIPT_DIR = Path(__file__).resolve().parent

# Import the shared cleaner
sys.path.insert(0, str(SCRIPT_DIR))
from movie_name_cleaner import clean_filename, MovieInfo

# ── Configuration ────────────────────────────────────────────────────────────

KDE_DB_PATH = os.path.expanduser(
    os.environ.get("KDE_ACTIVITY_DB",
                   "~/.local/share/kactivitymanagerd/resources/database")
)
CACHE_FILE = os.environ.get(
    "MOVIE_CACHE_FILE",
    str(SCRIPT_DIR / "movie_cache.json")
)
STATE_FILE = os.environ.get(
    "MOVIE_WATCHER_STATE",
    str(SCRIPT_DIR / "movie_watcher_state.json")
)

VIDEO_EXTS = ['mp4', 'mkv', 'avi', 'mov', 'm4v', 'webm', 'flv', 'wmv', 'ts', 'm2ts']
VIDEO_CONDITIONS = " OR ".join([f"targettedResource LIKE '%.{ext}'" for ext in VIDEO_EXTS])

POLL_INTERVAL_SEC = int(os.environ.get("MOVIE_POLL_INTERVAL", "60"))

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] movie_watcher: %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)
logger = logging.getLogger(__name__)


# ── Time helpers ─────────────────────────────────────────────────────────────

def _unix_to_str(unix_ts: int) -> str:
    """Convert a unix timestamp to local datetime string."""
    if not unix_ts or unix_ts <= 0:
        return ""
    return datetime.fromtimestamp(unix_ts, tz=timezone.utc).astimezone().strftime(
        "%Y-%m-%d %H:%M:%S"
    )


def _unix_to_date(unix_ts: int) -> str:
    """Convert a unix timestamp to local date string (YYYY-MM-DD)."""
    if not unix_ts or unix_ts <= 0:
        return ""
    return datetime.fromtimestamp(unix_ts, tz=timezone.utc).astimezone().strftime(
        "%Y-%m-%d"
    )


def _duration_min(start: int, end: int) -> Optional[int]:
    """Return duration in minutes, or None if end is missing/invalid."""
    if not end or end <= 0 or end < start:
        return None
    return round((end - start) / 60)


# ── KDE Activity DB ──────────────────────────────────────────────────────────

def query_new_videos(since_start: int = 0) -> List[Tuple[int, int, str]]:
    """
    Query the KDE Activity DB for video events with start > since_start.
    Returns list of (start, end, targettedResource) sorted ascending by start.
    """
    if not os.path.exists(KDE_DB_PATH):
        logger.warning(f"KDE Activity DB not found at {KDE_DB_PATH}")
        return []

    conn = sqlite3.connect(f"file:{KDE_DB_PATH}?mode=ro", uri=True)
    try:
        cursor = conn.cursor()
        query = f"""
            SELECT start, end, targettedResource
            FROM ResourceEvent
            WHERE ({VIDEO_CONDITIONS})
              AND start > ?
            ORDER BY start ASC
        """
        cursor.execute(query, (since_start,))
        return cursor.fetchall()
    except sqlite3.OperationalError as e:
        logger.error(f"Error querying KDE DB: {e}")
        return []
    finally:
        conn.close()


def query_all_videos() -> List[Tuple[int, int, str]]:
    """Full backfill: query ALL video events, newest first."""
    if not os.path.exists(KDE_DB_PATH):
        logger.warning(f"KDE Activity DB not found at {KDE_DB_PATH}")
        return []

    conn = sqlite3.connect(f"file:{KDE_DB_PATH}?mode=ro", uri=True)
    try:
        cursor = conn.cursor()
        query = f"""
            SELECT start, end, targettedResource
            FROM ResourceEvent
            WHERE ({VIDEO_CONDITIONS})
            ORDER BY start DESC
        """
        cursor.execute(query)
        return cursor.fetchall()
    except sqlite3.OperationalError as e:
        logger.error(f"Error querying KDE DB: {e}")
        return []
    finally:
        conn.close()


# ── Cache management ─────────────────────────────────────────────────────────

def load_cache() -> Dict[str, Any]:
    """Load the movie cache. Returns empty structure if missing/corrupt."""
    try:
        if os.path.exists(CACHE_FILE):
            with open(CACHE_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
    except Exception as e:
        logger.error(f"Error loading cache: {e}")
    return {"movies": [], "metadata": {"last_updated": "", "total_entries": 0, "last_seen_start": 0}}


def save_cache(cache: Dict[str, Any]) -> None:
    """Write cache atomically (temp file + rename)."""
    tmp = CACHE_FILE + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(cache, f, indent=2, ensure_ascii=False)
    os.replace(tmp, CACHE_FILE)


def load_state() -> int:
    """Load the last-seen-start high-water mark. Returns 0 if no state."""
    try:
        if os.path.exists(STATE_FILE):
            with open(STATE_FILE, "r") as f:
                return json.load(f).get("last_seen_start", 0)
    except Exception:
        pass
    return 0


def save_state(last_seen_start: int) -> None:
    tmp = STATE_FILE + ".tmp"
    with open(tmp, "w") as f:
        json.dump({"last_seen_start": last_seen_start}, f)
    os.replace(tmp, STATE_FILE)


# ── Processing ───────────────────────────────────────────────────────────────

def build_session(start: int, end: int) -> Dict[str, Any]:
    """Build a session record from raw start/end unix timestamps."""
    return {
        "start": _unix_to_str(start),
        "end": _unix_to_str(end),
        "start_unix": start,
        "end_unix": end if end and end > 0 else None,
        "duration_min": _duration_min(start, end),
    }


def process_rows(rows: List[Tuple[int, int, str]]) -> List[Dict[str, Any]]:
    """
    Process raw DB rows into movie entries with sessions.

    Multiple rows for the same title on the same calendar day are MERGED:
    each row becomes a session within the same movie entry. This means if
    you start a film, stop it, restart it later — all sessions are preserved.
    """
    # Group by (date, cleaned_title) → list of (start, end) sessions
    groups: Dict[Tuple[str, str], Dict[str, Any]] = OrderedDict()

    for start, end, filepath in rows:
        info: MovieInfo = clean_filename(filepath)
        if not info.title or info.title.strip().isspace():
            continue

        date_str = _unix_to_date(start)
        key = (date_str, info.title)

        if key not in groups:
            groups[key] = {
                "title": info.title,
                "season": info.season,
                "episode": info.episode,
                "raw": info.raw,
                "date": date_str,
                "sessions": [],
                "_max_start": start,
            }

        entry = groups[key]
        entry["sessions"].append(build_session(start, end))
        if start > entry["_max_start"]:
            entry["_max_start"] = start

    # Finalise: compute derived fields, sort sessions, strip internal fields
    result = []
    for entry in groups.values():
        sessions = entry["sessions"]
        # Sort sessions by start time within each entry
        sessions.sort(key=lambda s: s["start_unix"])

        # Derive last_watched and total watch time
        last_session = sessions[-1]
        entry["last_watched"] = last_session["start"]
        entry["total_watch_min"] = sum(
            s["duration_min"] for s in sessions if s["duration_min"] is not None
        ) or None

        # Clean up internal field
        entry.pop("_max_start", None)
        result.append(entry)

    return result


def merge_entries(existing: List[Dict[str, Any]],
                  new: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """
    Merge new entries into existing cache entries.

    If a new entry has the same (date, title) as an existing one, their
    sessions are combined (new sessions appended). Otherwise, the new entry
    is prepended (newest-first ordering is maintained by the caller).
    """
    # Index existing by (date, title) for fast lookup
    index: Dict[Tuple[str, str], int] = {}
    for i, e in enumerate(existing):
        index[(e.get("date", ""), e.get("title", ""))] = i

    truly_new = []
    for entry in new:
        key = (entry.get("date", ""), entry.get("title", ""))
        if key in index:
            # Merge sessions into the existing entry
            idx = index[key]
            existing[idx]["sessions"].extend(entry["sessions"])
            # Re-sort sessions by start time
            existing[idx]["sessions"].sort(key=lambda s: s.get("start_unix", 0))
            # Update derived fields
            sessions = existing[idx]["sessions"]
            existing[idx]["last_watched"] = sessions[-1]["start"]
            existing[idx]["total_watch_min"] = sum(
                s["duration_min"] for s in sessions if s["duration_min"] is not None
            ) or None
        else:
            truly_new.append(entry)

    return truly_new, existing


def backfill() -> Dict[str, Any]:
    """
    Full backfill from the KDE DB. Processes all video history.
    Used on first run (no state file).
    """
    logger.info("Starting full backfill from KDE Activity DB...")
    rows = query_all_videos()
    logger.info(f"Found {len(rows)} total video events in KDE DB")

    entries = process_rows(rows)
    last_start = max((r[0] for r in rows), default=0)

    logger.info(f"Processed into {len(entries)} unique movie/day entries")

    # Sort newest-first by last_watched (derived from the latest session start)
    entries.sort(key=lambda e: e["last_watched"], reverse=True)

    cache = {
        "movies": entries,
        "metadata": {
            "last_updated": datetime.now().astimezone().strftime("%Y-%m-%dT%H:%M:%S"),
            "total_entries": len(entries),
            "last_seen_start": last_start,
        }
    }
    save_cache(cache)
    save_state(last_start)
    logger.info(f"Backfill complete: {len(entries)} entries cached, last_seen_start={last_start}")
    return cache


def poll_once() -> int:
    """
    Incremental poll: fetch only new entries since last_seen_start.
    Returns the number of new entries added (not counting session merges).
    """
    last_seen = load_state()
    rows = query_new_videos(since_start=last_seen)
    if not rows:
        return 0

    logger.info(f"Found {len(rows)} new video event(s) since start={last_seen}")

    new_entries = process_rows(rows)
    max_start = max((r[0] for r in rows), default=last_seen)

    if not new_entries:
        if max_start > last_seen:
            save_state(max_start)
        return 0

    # Load existing cache and merge
    cache = load_cache()
    existing = cache.get("movies", [])

    truly_new, merged_existing = merge_entries(existing, new_entries)

    if truly_new:
        # Sort new entries newest-first and prepend
        truly_new.sort(key=lambda e: e["last_watched"], reverse=True)
        cache["movies"] = truly_new + merged_existing
    else:
        cache["movies"] = merged_existing
        logger.info("All new events were additional sessions for existing entries")

    cache["metadata"]["last_updated"] = datetime.now().astimezone().strftime("%Y-%m-%dT%H:%M:%S")
    cache["metadata"]["total_entries"] = len(cache["movies"])
    cache["metadata"]["last_seen_start"] = max_start

    save_cache(cache)
    save_state(max_start)

    added = len(truly_new)
    merged_sessions = len(new_entries) - added
    if added:
        for e in truly_new[:5]:
            logger.info(f"  + {e['last_watched']}  {e['title']}  "
                        f"({len(e['sessions'])} session(s))")
        if added > 5:
            logger.info(f"  ... and {added - 5} more")
    if merged_sessions:
        logger.info(f"  Merged {merged_sessions} additional session(s) into existing entries")
    return added


# ── Main loop ────────────────────────────────────────────────────────────────

def run_forever():
    """Main daemon loop. Polls every POLL_INTERVAL_SEC."""
    logger.info(f"Movie watcher starting. DB={KDE_DB_PATH}, poll={POLL_INTERVAL_SEC}s")

    # First-run backfill if no state
    if load_state() == 0:
        logger.info("No state file found — performing full backfill")
        backfill()
    else:
        # Make sure cache exists
        if not os.path.exists(CACHE_FILE):
            logger.info("Cache missing but state exists — performing full backfill")
            backfill()

    logger.info(f"Entering poll loop (interval={POLL_INTERVAL_SEC}s)")
    while True:
        try:
            n = poll_once()
            if n:
                logger.info(f"Added {n} new movie(s) to cache")
        except Exception as e:
            logger.error(f"Poll error: {e}", exc_info=True)

        time.sleep(POLL_INTERVAL_SEC)


def run_once():
    """Single poll cycle (for testing or cron-style invocation)."""
    if load_state() == 0:
        backfill()
    else:
        n = poll_once()
        logger.info(f"Added {n} new movie(s)")


if __name__ == "__main__":
    if "--once" in sys.argv:
        run_once()
    else:
        run_forever()
