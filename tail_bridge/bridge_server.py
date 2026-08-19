#!/usr/bin/env python3
"""
Tail Bridge Server — Unified PC↔Phone communication protocol.

This FastAPI server is the single entry point for all "tethered" features that
share data between the desktop and the Tail Android app. It generalises the
proven Garmin proxy pattern into a reusable source-registration system.

═══════════════════════════════════════════════════════════════════════════════
PROTOCOL
═══════════════════════════════════════════════════════════════════════════════

Every data source implements the BridgeSource interface (see sources/base.py).
The server auto-generates three endpoints per source:

    GET /api/v1/{source}/latest        → most recent item
    GET /api/v1/{source}/recent?limit=N → N most recent items
    GET /api/v1/{source}/health         → source-specific health

Global endpoints:

    GET /health                → server alive + list of registered sources
    GET /api/v1/sources        → list of source names and descriptions

All endpoints require the X-App-Auth header (shared secret token).

═══════════════════════════════════════════════════════════════════════════════
ADDING A NEW SOURCE (future feature)
═══════════════════════════════════════════════════════════════════════════════

1. Create sources/my_thing.py with a class extending BridgeSource.
2. Register it in sources/__init__.py (one line).
3. Done — the server auto-generates the routes.

On the Android side:
1. Add a data class for the response.
2. Call BridgeClient.fetch("{source}/latest", ...).
3. Hook into the UI.
"""

from __future__ import annotations

import json
import logging
import os
import sys
import time
import uuid
from datetime import datetime
from pathlib import Path
from typing import Dict, List, Optional, Any

from fastapi import FastAPI, HTTPException, Security
from fastapi.security import APIKeyHeader
from fastapi.middleware.cors import CORSMiddleware

# Resolve script directory for imports
SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))

from sources import get_all_sources, BridgeSource

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] bridge: %(message)s")
logger = logging.getLogger(__name__)

# ── App setup ────────────────────────────────────────────────────────────────

app = FastAPI(title="Tail Bridge", version="1.0.0")

# Allow CORS for local network access
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Auth ─────────────────────────────────────────────────────────────────────

API_KEY = os.getenv("ANDROID_PROXY_KEY", "")
api_key_header = APIKeyHeader(name="X-App-Auth")


def verify_key(api_key: str = Security(api_key_header)):
    """Dependency: validates the X-App-Auth header against the shared secret."""
    if not API_KEY:
        raise HTTPException(status_code=503, detail="Server not configured (ANDROID_PROXY_KEY not set)")
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Forbidden: Invalid App Token")
    return api_key


# ── Source registry ──────────────────────────────────────────────────────────

_sources: Dict[str, BridgeSource] = {}


def register_sources():
    """Discover and register all sources."""
    global _sources
    for source in get_all_sources():
        _sources[source.name] = source
        logger.info(f"Registered source: {source.name} ({source.description})")


register_sources()


# ── Global endpoints ─────────────────────────────────────────────────────────

@app.get("/health")
def health():
    """Server health check. Does NOT require auth."""
    return {
        "status": "running",
        "sources": {name: s.description for name, s in _sources.items()},
        "version": "1.0.0",
    }


@app.get("/api/v1/sources")
def list_sources(api_key: str = Security(verify_key)):
    """List all registered data sources."""
    return {
        "sources": [
            {"name": s.name, "description": s.description}
            for s in _sources.values()
        ]
    }


# ── Dynamic source endpoints ─────────────────────────────────────────────────
# These are generated once at startup for each registered source.

def _make_source_endpoints(source: BridgeSource):
    """Create the /latest, /recent, /health endpoints for a source."""

    source_name = source.name

    @app.get(f"/api/v1/{source_name}/latest", tags=[source_name])
    def get_latest(api_key: str = Security(verify_key)):
        """Return the most recent item from this source."""
        item = source.get_latest()
        if item is None:
            raise HTTPException(
                status_code=404,
                detail=f"No data available from {source_name}"
            )
        return item

    @app.get(f"/api/v1/{source_name}/recent", tags=[source_name])
    def get_recent(limit: int = 10, api_key: str = Security(verify_key)):
        """Return the N most recent items from this source."""
        limit = max(1, min(limit, 100))  # clamp
        items = source.get_recent(limit)
        return {
            "source": source_name,
            "items": items,
            "count": len(items),
            "limit": limit,
        }

    @app.get(f"/api/v1/{source_name}/health", tags=[source_name])
    def get_source_health(api_key: str = Security(verify_key)):
        """Return health status for this source."""
        return source.health()


for _source in _sources.values():
    _make_source_endpoints(_source)


# ── Movie-specific convenience endpoints ─────────────────────────────────────
# These provide richer queries specific to the movie use-case while still
# going through the source abstraction.

@app.get("/api/v1/movies/suggest", tags=["movies"])
def suggest_movie(
    exclude: str = "",
    api_key: str = Security(verify_key)
):
    """
    Returns the most recent movie not in the exclude list.
    `exclude` is a comma-separated list of titles to skip.
    """
    source = _sources.get("movies")
    if source is None:
        raise HTTPException(status_code=404, detail="Movie source not registered")

    excluded = {t.strip() for t in exclude.split(",") if t.strip()}
    recent = source.get_recent(20)

    for item in recent:
        title = item.get("title", "")
        if title and title not in excluded:
            return item

    if recent:
        return recent[0]
    raise HTTPException(status_code=404, detail="No movies available")


# ── PC bubble widget endpoints ────────────────────────────────────────────────
# The PC floating bubble widget (py_habits_widget/pc_bubble_widget.py) times
# habits on the desktop; the phone app toggles which habits appear on it.
# The bridge is the single meeting point (and the single writer of both state
# files, so there are never sync conflicts):
#
#   phone  → POST /pc_widget/config   (which habits the widget should show)
#   widget → GET  /pc_widget/config   (localhost poll, same auth token)
#   widget → POST /pc_widget/event    (timer session / tap, server assigns id)
#   phone  → GET  /pc_widget/events   (pull everything not yet acked)
#   phone  → POST /pc_widget/acks     (applied event ids; bridge prunes them)
#
# Delivery is at-least-once + acks = effectively-once: if the ack POST fails
# the phone re-pulls and re-applies on the next poll.

PC_WIDGET_STATE_DIR = Path.home() / ".config" / "tail_bridge"
PC_WIDGET_CONFIG_PATH = PC_WIDGET_STATE_DIR / "pc_widget_config.json"
PC_WIDGET_EVENTS_PATH = PC_WIDGET_STATE_DIR / "pc_habit_events.json"
PC_WIDGET_MAX_EVENTS = 500  # safety valve if the phone never acks


def _pc_widget_read(path: Path) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _pc_widget_write(path: Path, payload: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2)
        f.write("\n")
    tmp.replace(path)


@app.get("/api/v1/pc_widget/config", tags=["pc_widget"])
def pc_widget_get_config(api_key: str = Security(verify_key)):
    """Current PC-widget habit squares (empty list until the phone pushes one)."""
    return _pc_widget_read(PC_WIDGET_CONFIG_PATH) or {"version": 1, "habits": []}


@app.post("/api/v1/pc_widget/config", tags=["pc_widget"])
def pc_widget_set_config(payload: Dict[str, Any], api_key: str = Security(verify_key)):
    """Phone pushes the habit squares the PC widget should show."""
    habits = payload.get("habits")
    if not isinstance(habits, list):
        raise HTTPException(status_code=400, detail="body must contain a 'habits' list")
    clean = []
    for h in habits:
        if not isinstance(h, dict):
            continue
        name = h.get("name")
        if isinstance(name, str) and name.strip():
            div = h.get("divider")
            inv = h.get("inverted_binary")
            nop = h.get("no_points")
            clean.append({
                "name": name,
                "icon": h.get("icon") if isinstance(h.get("icon"), str) else None,
                "minutes_primary": bool(h.get("minutes_primary", False)),
                # effective-points inputs (phone: habitDividers /
                # invertedBinaryHabits / noPointsHabits); None = phone
                # hasn't sent the field yet → widget falls back to its
                # overrides/backup layers
                "divider": div if isinstance(div, int) and not isinstance(div, bool) and div >= 1 else None,
                "inverted_binary": inv if isinstance(inv, bool) else None,
                "no_points": nop if isinstance(nop, bool) else None,
            })
    body = {
        "version": 1,
        "updated_at": datetime.now().isoformat(timespec="seconds"),
        "habits": clean,
    }
    _pc_widget_write(PC_WIDGET_CONFIG_PATH, body)
    logger.info(f"pc_widget config updated: {len(clean)} habits")
    return {"ok": True, "count": len(clean)}


@app.post("/api/v1/pc_widget/event", tags=["pc_widget"])
def pc_widget_add_event(payload: Dict[str, Any], api_key: str = Security(verify_key)):
    """PC widget appends one habit event; the bridge assigns its id."""
    habit = payload.get("habit")
    if not isinstance(habit, str) or not habit.strip():
        raise HTTPException(status_code=400, detail="'habit' is required")
    event = {
        "id": "pc-{}-{}".format(int(time.time() * 1000), uuid.uuid4().hex[:6]),
        "habit": habit,
        "kind": payload.get("kind") if payload.get("kind") in ("session", "tap") else "tap",
        "date": payload.get("date") or datetime.now().strftime("%Y-%m-%d"),
        "start": payload.get("start") or datetime.now().strftime("%H:%M:%S"),
        "end": payload.get("end") or datetime.now().strftime("%H:%M:%S"),
        "minutes": max(0, int(payload.get("minutes") or 0)),
    }
    events = _pc_widget_read(PC_WIDGET_EVENTS_PATH).get("events")
    events = [e for e in events if isinstance(e, dict)] if isinstance(events, list) else []
    events.append(event)
    if len(events) > PC_WIDGET_MAX_EVENTS:
        events = events[-PC_WIDGET_MAX_EVENTS:]
    _pc_widget_write(PC_WIDGET_EVENTS_PATH, {
        "version": 1,
        "updated_at": datetime.now().isoformat(timespec="seconds"),
        "events": events,
    })
    logger.info(f"pc_widget event queued: {event['habit']} ({event['kind']}, {event['minutes']}m)")
    return {"ok": True, "id": event["id"]}


@app.get("/api/v1/pc_widget/events", tags=["pc_widget"])
def pc_widget_get_events(api_key: str = Security(verify_key)):
    """Phone pulls every event not yet acked (acks prune, so this is the queue)."""
    events = _pc_widget_read(PC_WIDGET_EVENTS_PATH).get("events")
    events = [e for e in events if isinstance(e, dict)] if isinstance(events, list) else []
    return {"version": 1, "events": events, "count": len(events)}


@app.post("/api/v1/pc_widget/acks", tags=["pc_widget"])
def pc_widget_acks(payload: Dict[str, Any], api_key: str = Security(verify_key)):
    """Phone confirms applied events; the bridge removes them from the queue."""
    processed = payload.get("processed")
    if not isinstance(processed, list):
        raise HTTPException(status_code=400, detail="body must contain a 'processed' list")
    acked = {p for p in processed if isinstance(p, str)}
    events = _pc_widget_read(PC_WIDGET_EVENTS_PATH).get("events")
    events = [e for e in events if isinstance(e, dict)] if isinstance(events, list) else []
    remaining = [e for e in events if e.get("id") not in acked]
    if len(remaining) != len(events):
        _pc_widget_write(PC_WIDGET_EVENTS_PATH, {
            "version": 1,
            "updated_at": datetime.now().isoformat(timespec="seconds"),
            "events": remaining,
        })
    logger.info(f"pc_widget acks: {len(acked)} ids, {len(remaining)} still queued")
    return {"ok": True, "remaining": len(remaining)}


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("TAIL_BRIDGE_PORT", "8001"))
    uvicorn.run(app, host="0.0.0.0", port=port)
