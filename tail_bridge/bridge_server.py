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

import asyncio
import json
import logging
import os
import sys
import time
import urllib.error
import urllib.request
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

import dashboard  # read-only PC status UI (routes + activity + history log)

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
#   widget → POST /pc_widget/event    kind="toggle_pc_widget_habit"
#                                      (settings-screen habit picker: flip a
#                                      "PC widget" toggle ON/OFF in the app)
#   phone  → GET  /pc_widget/events   (pull everything not yet acked)
#   phone  → POST /pc_widget/acks     (applied event ids; bridge prunes them)
#
# Delivery is at-least-once + acks = effectively-once: if the ack POST fails
# the phone re-pulls and re-applies on the next poll.

PC_WIDGET_STATE_DIR = Path.home() / ".config" / "tail_bridge"
PC_WIDGET_CONFIG_PATH = PC_WIDGET_STATE_DIR / "pc_widget_config.json"
PC_WIDGET_EVENTS_PATH = PC_WIDGET_STATE_DIR / "pc_habit_events.json"
PC_WIDGET_MAX_EVENTS = 500  # safety valve if the phone never acks

# Set whenever an event is queued, so long-polling phones (the bubble
# service's pc_widget/events/wait loop) wake up instantly.
PC_WIDGET_EVENT_SIGNAL = asyncio.Event()


def _pc_widget_pending_events() -> list:
    """Un-acked events right now (acks prune, so this is the queue)."""
    events = _pc_widget_read(PC_WIDGET_EVENTS_PATH).get("events")
    return [e for e in events if isinstance(e, dict)] if isinstance(events, list) else []


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
    all_raw = payload.get("all_habits")
    all_habits = ([n.strip() for n in all_raw
                   if isinstance(n, str) and n.strip()]
                  if isinstance(all_raw, list) else [])
    body = {
        "version": 1,
        "updated_at": datetime.now().isoformat(timespec="seconds"),
        "habits": clean,
        # the phone's FULL habit catalog — the PC settings screen's
        # habit-picker source (empty on older app versions)
        "all_habits": all_habits,
    }
    _pc_widget_write(PC_WIDGET_CONFIG_PATH, body)
    logger.info(f"pc_widget config updated: {len(clean)} habits")
    dashboard.note("phone", "config_push",
                   f"Phone pushed PC-widget config: {len(clean)} habit(s) enabled")
    return {"ok": True, "count": len(clean)}


def _pc_widget_queue_event(payload: Dict[str, Any], actor: str = "pc") -> Dict[str, Any]:
    """Queue one PC-widget event and assign its id.

    Shared by the authed widget endpoint and the dashboard's editor
    (actor labels who queued it). Returns the stored event dict.
    """
    habit = payload.get("habit")
    if not isinstance(habit, str) or not habit.strip():
        raise HTTPException(status_code=400, detail="'habit' is required")
    kind = payload.get("kind")
    event = {
        "id": "pc-{}-{}".format(int(time.time() * 1000), uuid.uuid4().hex[:6]),
        "habit": habit,
        "kind": kind if kind in ("session", "tap", "toggle_pc_widget_habit",
                                 "session_edit", "session_delete") else "tap",
        "date": payload.get("date") or datetime.now().strftime("%Y-%m-%d"),
        "start": payload.get("start") or datetime.now().strftime("%H:%M:%S"),
        "end": payload.get("end") or datetime.now().strftime("%H:%M:%S"),
        "minutes": max(0, int(payload.get("minutes") or 0)),
    }
    if kind == "toggle_pc_widget_habit":
        # settings-screen habit picker: the ABSOLUTE desired state, so
        # at-least-once redelivery stays idempotent on the phone
        event["enabled"] = bool(payload.get("enabled", True))
    if kind in ("session_edit", "session_delete"):
        # history corrections: the phone undoes `orig` (exactly what it
        # originally applied) and, for session_edit, applies this event's
        # corrected times; ref_id ties the correction to the original
        # bridge event id
        event["ref_id"] = str(payload.get("ref_id") or "")
        orig = payload.get("orig")
        event["orig"] = orig if isinstance(orig, dict) else {}
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
    dashboard.note(actor, "event_queued",
                   f"{event['habit']}: {event['kind']} · {event['minutes']} min "
                   f"({event['start']}–{event['end']})",
                   data={"id": event["id"], "kind": event["kind"],
                         "minutes": event["minutes"]})
    dashboard.widget_history_add(event)
    if event["kind"] in ("session_edit", "session_delete"):
        dashboard.widget_history_mark_corrected(
            event["ref_id"],
            "edited" if event["kind"] == "session_edit" else "deleted")
    PC_WIDGET_EVENT_SIGNAL.set()
    return event


def _pc_widget_edit_pending(event_id: str, date: str, start: str,
                            end: str, minutes: int) -> bool:
    """Rewrite a STILL-PENDING event in place — the phone never applied
    it, so no correction event is needed. False when the id isn't queued
    (already acked → caller falls back to a session_edit correction)."""
    events = _pc_widget_pending_events()
    changed = False
    for e in events:
        if e.get("id") == event_id:
            e["date"] = date
            e["start"] = start
            e["end"] = end
            e["minutes"] = max(0, int(minutes or 0))
            changed = True
    if changed:
        _pc_widget_write(PC_WIDGET_EVENTS_PATH, {
            "version": 1,
            "updated_at": datetime.now().isoformat(timespec="seconds"),
            "events": events,
        })
        PC_WIDGET_EVENT_SIGNAL.set()
    return changed


def _pc_widget_delete_pending(event_id: str) -> bool:
    """Drop a STILL-PENDING event from the queue (phone never sees it)."""
    events = _pc_widget_pending_events()
    remaining = [e for e in events if e.get("id") != event_id]
    if len(remaining) == len(events):
        return False
    _pc_widget_write(PC_WIDGET_EVENTS_PATH, {
        "version": 1,
        "updated_at": datetime.now().isoformat(timespec="seconds"),
        "events": remaining,
    })
    return True


@app.post("/api/v1/pc_widget/event", tags=["pc_widget"])
async def pc_widget_add_event(payload: Dict[str, Any], api_key: str = Security(verify_key)):
    """PC widget appends one habit event; the bridge assigns its id.

    (async so it can wake the pc_widget/events/wait long-pollers on the
    event loop — the file writes it does are tiny and local.)
    """
    event = _pc_widget_queue_event(payload, actor="pc")
    return {"ok": True, "id": event["id"]}


@app.get("/api/v1/pc_widget/events", tags=["pc_widget"])
def pc_widget_get_events(api_key: str = Security(verify_key)):
    """Phone pulls every event not yet acked (acks prune, so this is the queue)."""
    return {"version": 1, "events": _pc_widget_pending_events(),
            "count": len(_pc_widget_pending_events())}


@app.get("/api/v1/pc_widget/events/wait", tags=["pc_widget"])
async def pc_widget_wait_events(timeout: int = 50, api_key: str = Security(verify_key)):
    """
    Long-poll variant of GET /pc_widget/events for the phone's bubble
    service: returns pending events at once, or holds the request open
    until one is queued (or `timeout` s pass, capped at 120). The phone
    re-issues the call immediately, so a PC-side timer stop is picked up
    ~instantly instead of on the next fixed poll.
    """
    # clear() BEFORE reading: an event queued between the two still wakes
    # the wait below — no lost wakeups.
    PC_WIDGET_EVENT_SIGNAL.clear()
    events = _pc_widget_pending_events()
    if not events:
        try:
            await asyncio.wait_for(
                PC_WIDGET_EVENT_SIGNAL.wait(),
                timeout=max(1, min(int(timeout), 120)))
        except asyncio.TimeoutError:
            pass
        events = _pc_widget_pending_events()
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
    if acked:
        dashboard.widget_history_mark_acked(acked)
        dashboard.note("phone", "events_acked",
                       f"Phone applied {len(acked)} event(s); "
                       f"{len(remaining)} still queued")
    return {"ok": True, "remaining": len(remaining)}


# ── Chess analysis (Tail-owned Stockfish service) ─────────────────────────────
#
# Fully standalone: the bridge runs its own Stockfish analysis (python-chess),
# cached in a local SQLite registry keyed by canonical game_id. The Tail
# bundle needs no chess-coach installation. As a courtesy, every fresh
# analysis is pushed best-effort to chess-coach's /ingest endpoint (when it
# happens to be running) so chess-coach never re-analyses those games.

try:
    from chess_analysis import get_service as _chess_analysis_service
except Exception as _exc:  # python-chess or stockfish missing → phone falls back
    _chess_analysis_import_error = str(_exc)

    def _chess_analysis_service():
        raise HTTPException(
            status_code=503,
            detail=f"chess analysis unavailable: {_chess_analysis_import_error}",
        )


@app.get("/api/v1/chess_analysis/status", tags=["chess_analysis"])
def chess_analysis_status(api_key: str = Security(verify_key)):
    """Tail-owned analysis service status (cache size, engine, busy flag)."""
    return _chess_analysis_service().status()


@app.post("/api/v1/chess_analysis/analyze", tags=["chess_analysis"])
def chess_analysis_analyze(payload: Dict[str, Any], api_key: str = Security(verify_key)):
    """Analyse a PGN with local Stockfish. Returns per-side blunder/ACPL stats.

    Body: {pgn: str, game_id?: str, username?: str, depth?: int}
    Dedup via the SQLite registry — a cached game returns instantly, a new
    one runs live analysis (seconds). Blocking call; FastAPI runs it in the
    threadpool.
    """
    pgn = payload.get("pgn")
    if not isinstance(pgn, str) or not pgn.strip():
        raise HTTPException(status_code=400, detail="body must contain a 'pgn' string")
    service = _chess_analysis_service()
    try:
        result = service.analyze(
            pgn_text=pgn,
            game_id=str(payload.get("game_id") or ""),
            username=str(payload.get("username") or ""),
            depth=payload.get("depth"),
        )
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except FileNotFoundError:
        raise HTTPException(status_code=503, detail="stockfish binary not found")
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"analysis failed: {exc}")
    if not result.get("cached"):
        dashboard.note("phone", "chess_analyzed",
                       f"Stockfish analysed {result.get('game_id') or 'game'} "
                       f"(depth {result.get('depth')}, "
                       f"{result.get('engine_ms')} ms)")
    return result


# ── Dashboard (read-only PC status UI) ────────────────────────────────────────
# Serves static/dashboard.html at "/" and exposes GET /api/v1/dashboard —
# one aggregated snapshot (clients, sources, widget config/queue, chess,
# history, live activity). Informational only; no controls.

dashboard.install(
    app,
    version=app.version,
    get_sources=lambda: _sources,
    get_pending_events=_pc_widget_pending_events,
    config_path=PC_WIDGET_CONFIG_PATH,
    chess_service=lambda: _chess_analysis_service(),
    queue_event=lambda payload: _pc_widget_queue_event(payload, actor="dashboard"),
    edit_pending=_pc_widget_edit_pending,
    delete_pending=_pc_widget_delete_pending,
)


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("TAIL_BRIDGE_PORT", "8001"))
    uvicorn.run(app, host="0.0.0.0", port=port)
