#!/usr/bin/env python3
"""
Tail Bridge dashboard — read-only PC-side status UI.

Serves a single-page dashboard (static/dashboard.html) at "/" and exposes one
aggregated, auth-protected snapshot endpoint that the page polls:

    GET /api/v1/dashboard → server info, client last-seen, sources + health,
                            PC-widget config & pending queue, chess status,
                            persistent history log, live request activity

Everything is informational only — the dashboard never mutates bridge state.

Two extra pieces of plumbing live here:

  * an HTTP middleware that records every non-dashboard API request (actor,
    path, latency) into an in-memory ring buffer — this powers the "what is
    connected" cards and the live activity feed. Requests are classified by
    origin: loopback → "pc" (the bubble widget / local tools), anything else
    → "phone" (the Android app on the LAN);
  * an append-only history log (~/.config/tail_bridge/bridge_history.json,
    capped) that survives restarts. bridge_server call-sites append
    "what did what when" entries via note(): config pushes, queued widget
    events, phone acks, live chess analyses, server starts.
"""

from __future__ import annotations

import json
import os
import re
import sqlite3
import subprocess
import threading
import time
import urllib.request
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from fastapi import FastAPI, HTTPException, Request, Security
from fastapi.responses import FileResponse

import ipaddress

SCRIPT_DIR = Path(__file__).resolve().parent
STATIC_DIR = SCRIPT_DIR / "static"

STATE_DIR = Path.home() / ".config" / "tail_bridge"
HISTORY_PATH = STATE_DIR / "bridge_history.json"
HISTORY_MAX = 1000          # persisted "what did what when" entries
ACTIVITY_MAX = 200          # in-memory request ring buffer

# Every PC-widget event the bridge has ever queued (with ack/correction
# state) — the dashboard's editable widget history. The queue itself is
# pruned on acks, so THIS file is what makes history browsable/editable.
WIDGET_HISTORY_PATH = STATE_DIR / "pc_widget_history.json"
WIDGET_HISTORY_MAX = 500

_LOOPBACK = {"127.0.0.1", "::1", "localhost"}

# ── Cline agent live feed ─────────────────────────────────────────────────────
# The Pack app's backend (packd) runs on the same VPS as the remote-role
# bridge. It executes a headless Cline CLI agent on other codebases and
# streams the agent output into its jobs.db (one row per job). The snapshot
# below reads that DB read-only (never holds packd's write lock) and lists
# any live `cline --json` process, so the dashboard can show what the agent
# is doing right now.
PACKD_JOBS_DB = Path(os.environ.get("PACKD_JOBS_DB", "/srv/packd/jobs.db"))
_ACTIVE_STATUSES = {"queued", "running", "deploying"}


def _cline_processes() -> list:
    """Live `cline` processes on this machine (pid, uptime, command)."""
    try:
        out = subprocess.run(["ps", "-eo", "pid,etimes,args"],
                             capture_output=True, text=True, timeout=5).stdout
    except Exception:
        return []
    procs = []
    for ln in (out.splitlines() or [])[1:]:
        if "cline" not in ln or "--json" not in ln:
            continue
        parts = ln.strip().split(None, 2)
        if len(parts) < 3:
            continue
        try:
            procs.append({"pid": int(parts[0]), "elapsed_sec": int(parts[1]),
                          "cmd": parts[2][:250]})
        except ValueError:
            continue
    return procs


def _cline_snapshot() -> dict:
    """Best-effort view of packd jobs + live Cline processes. Never raises."""
    if not PACKD_JOBS_DB.exists():
        return {"available": False}
    try:
        con = sqlite3.connect(f"file:{PACKD_JOBS_DB}?mode=ro", uri=True,
                              timeout=3)
        jobs = []
        for (raw,) in con.execute("SELECT json FROM jobs"):
            try:
                j = json.loads(raw)
                if isinstance(j, dict):
                    jobs.append(j)
            except ValueError:
                continue
        con.close()
    except Exception:
        return {"available": False}
    jobs.sort(key=lambda j: j.get("created") or 0, reverse=True)
    active = next((j for j in jobs if j.get("status") in _ACTIVE_STATUSES), None)
    recent = [{"id": j.get("id"), "project": j.get("project"),
               "status": j.get("status"),
               "created": _iso(j["created"]) if j.get("created") else None,
               "summary": (j.get("summary") or "")[:300]}
              for j in jobs[:5]]
    active_out = None
    if active:
        active_out = {
            "id": active.get("id"),
            "project": active.get("project"),
            "instruction": (active.get("instruction") or "")[:400],
            "status": active.get("status"),
            "created": _iso(active["created"]) if active.get("created") else None,
            "log_tail": (active.get("log") or "")[-4000:],
        }
    return {"available": True, "db": str(PACKD_JOBS_DB),
            "processes": _cline_processes(), "active": active_out,
            "recent": recent}

# Deployment role: "full" (laptop — movies + PC widget + everything) or
# "remote" (always-on server — device-independent features only: garmin,
# tailcue, backups, chess). Set via TAIL_BRIDGE_ROLE in tail_bridge/.env.
ROLE = os.environ.get("TAIL_BRIDGE_ROLE", "full")
DEVICE_NAME = os.environ.get("TAIL_BRIDGE_DEVICE_NAME",
                             "laptop" if ROLE == "full" else "server")
# Cross-link targets (shown in the dashboard header):
TAILCUE_APP_URL = os.environ.get("TAILCUE_APP_URL", "")
# The other bridge (laptop ↔ remote server) — health-probed for the
# devices panel so each dashboard shows whether the peer bridge is up.
PEER_BRIDGE_URL = os.environ.get("PEER_BRIDGE_URL", "").rstrip("/")

# User-Agent the peer probe sends. Also the discriminator the activity
# middleware uses so peer probes are never misclassified as "phone".
PEER_PROBE_UA = "tail-bridge-peer-probe"

# How long after its last contact each device still counts as "connected".
# Phone: TailCueSyncWorker polls every 15 min (+ bubble long-poll) → 45 min.
# PC widget: polls continuously while running → 5 min.
# TailCue server: pushes warnings every 6 h + heartbeat each worker tick → 8 h.
DEVICE_ONLINE_SEC: Dict[str, int] = {
    "phone": 45 * 60,
    "pc": 5 * 60,
    "tailcue": 8 * 3600,
}

_started_at = time.time()
_activity: deque = deque(maxlen=ACTIVITY_MAX)
_last_seen: Dict[str, float] = {}
_last_request: Dict[str, str] = {}

# Cached peer-bridge snapshot probe (never block the snapshot on the network).
_peer_probe: Dict[str, Any] = {"at": 0.0, "result": None}
_PEER_PROBE_TTL = 30.0
_PEER_PROBE_TIMEOUT = 8.0

_history: List[Dict[str, Any]] = []
_history_lock = threading.Lock()


# ── small helpers ─────────────────────────────────────────────────────────────

def _iso(ts: float) -> str:
    return datetime.fromtimestamp(ts).isoformat(timespec="seconds")


def _read_json(path: Path) -> dict:
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
            return data if isinstance(data, dict) else {}
    except (OSError, ValueError):
        return {}


def _actor_of(client: str, path: str, user_agent: str = "") -> str:
    """Classify a request by origin.

    dashboard → the web UI itself; tailcue → the TailCue server (identified
    by its endpoint family, works for both loopback and remote origins);
    pc → loopback callers (bubble widget / local tools); phone → the Android
    app (LAN / Tailscale).
    """
    if (path == "/" or path.startswith("/dashboard")
            or path.startswith("/api/v1/dashboard") or path == "/favicon.ico"):
        return "dashboard"
    if PEER_PROBE_UA in user_agent:
        return "peer_bridge"
    if path.startswith("/api/v1/tailcue/"):
        return "tailcue"
    if client in _LOOPBACK:
        return "pc"
    return "phone"


# ── persistent history log ────────────────────────────────────────────────────

def _load_history() -> None:
    global _history
    entries = _read_json(HISTORY_PATH).get("entries")
    _history = [e for e in entries if isinstance(e, dict)] if isinstance(entries, list) else []


def _persist_history() -> None:
    try:
        HISTORY_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp = HISTORY_PATH.with_suffix(".json.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"version": 1, "updated_at": _iso(time.time()),
                       "entries": _history}, f, indent=2)
            f.write("\n")
        tmp.replace(HISTORY_PATH)
    except OSError:
        pass  # history is best-effort; never break a request over it


def note(actor: str, action: str, text: str, data: Optional[dict] = None) -> None:
    """Append one 'what did what when' entry (survives restarts).

    Actions used today: server_start, config_push, event_queued,
    events_acked, chess_analyzed.
    """
    entry: Dict[str, Any] = {"ts": _iso(time.time()), "actor": actor,
                             "action": action, "text": text}
    if data:
        entry["data"] = data
    with _history_lock:
        _history.append(entry)
        if len(_history) > HISTORY_MAX:
            del _history[: len(_history) - HISTORY_MAX]
        _persist_history()


# ── persistent PC-widget event history ────────────────────────────────────────

_widget_history: List[Dict[str, Any]] = []
_widget_history_lock = threading.Lock()


def _load_widget_history() -> None:
    global _widget_history
    entries = _read_json(WIDGET_HISTORY_PATH).get("events")
    _widget_history = ([e for e in entries if isinstance(e, dict)]
                       if isinstance(entries, list) else [])


def _persist_widget_history() -> None:
    try:
        WIDGET_HISTORY_PATH.parent.mkdir(parents=True, exist_ok=True)
        tmp = WIDGET_HISTORY_PATH.with_suffix(".json.tmp")
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump({"version": 1, "updated_at": _iso(time.time()),
                       "events": _widget_history}, f, indent=2)
            f.write("\n")
        tmp.replace(WIDGET_HISTORY_PATH)
    except OSError:
        pass  # best-effort store; never break a request over it


def widget_history_add(event: dict) -> None:
    """Record one freshly queued widget event (state 'queued')."""
    entry = dict(event)
    entry["state"] = "queued"
    with _widget_history_lock:
        _widget_history.append(entry)
        if len(_widget_history) > WIDGET_HISTORY_MAX:
            del _widget_history[: len(_widget_history) - WIDGET_HISTORY_MAX]
        _persist_widget_history()


def widget_history_mark_acked(ids) -> None:
    """Flip the given event ids to 'acked' once the phone applied them."""
    idset = {i for i in ids if isinstance(i, str)}
    if not idset:
        return
    with _widget_history_lock:
        for e in _widget_history:
            if e.get("id") in idset and e.get("state") != "acked":
                e["state"] = "acked"
                e["acked_at"] = _iso(time.time())
        _persist_widget_history()


def widget_history_mark_corrected(ref_id: str, correction: str) -> None:
    """Badge the original event a session_edit/session_delete refers to."""
    if not ref_id:
        return
    with _widget_history_lock:
        for e in _widget_history:
            if e.get("id") == ref_id:
                e["correction"] = correction
        _persist_widget_history()


def widget_history_update_values(event_id: str, date: str, start: str,
                                 end: str, minutes: int) -> bool:
    """Rewrite stored values after an edit (both in-place and corrections)."""
    with _widget_history_lock:
        for e in _widget_history:
            if e.get("id") == event_id:
                e["date"] = date
                e["start"] = start
                e["end"] = end
                e["minutes"] = max(0, int(minutes or 0))
                _persist_widget_history()
                return True
    return False


def widget_history_find(event_id: str) -> Optional[dict]:
    with _widget_history_lock:
        for e in _widget_history:
            if e.get("id") == event_id:
                return dict(e)
    return None


def widget_history_entries(limit: int = 100) -> list:
    """Newest-first widget event history for the dashboard."""
    with _widget_history_lock:
        return list(reversed(_widget_history[-limit:]))


# ── snapshot builder ──────────────────────────────────────────────────────────

def _peer_health_cached(cache_only: bool = False) -> Optional[dict]:
    """Peer-bridge snapshot probe, cached for _PEER_PROBE_TTL seconds."""
    if not PEER_BRIDGE_URL:
        return None
    now = time.time()
    # cache_only: answering a peer probe must never trigger a fresh probe
    # back to the peer (probe -> snapshot -> probe -> ... nesting, which
    # used to blow the timeout); serve whatever is cached, stale or absent.
    if cache_only:
        return _peer_probe["result"]
    if now - _peer_probe["at"] < _PEER_PROBE_TTL and _peer_probe["result"] is not None:
        return _peer_probe["result"]
    result: Dict[str, Any]
    try:
        # Full peer snapshot (devices + sources) so the laptop dashboard can
        # merge the remote bridge into one single view; falls back to plain
        # /health on peers that predate the aggregated endpoint.
        headers = {"User-Agent": PEER_PROBE_UA}
        body: Dict[str, Any]
        try:
            req = urllib.request.Request(PEER_BRIDGE_URL + "/api/v1/dashboard",
                                         headers=headers)
            with urllib.request.urlopen(req, timeout=_PEER_PROBE_TIMEOUT) as resp:
                body = json.loads(resp.read().decode("utf-8", "replace"))
            server = body.get("server") or {}
            result = {"online": True, "status": server.get("status", "?"),
                      "version": server.get("version", "?"),
                      "role": server.get("role", "?"),
                      "device_name": server.get("device_name", "?"),
                      "devices": [d for d in (body.get("devices") or [])
                                  if isinstance(d, dict)
                                  and d.get("id") != "peer_bridge"],
                      "sources": [{"name": s.get("name"), "health": s.get("health")}
                                  for s in (body.get("sources") or [])
                                  if isinstance(s, dict)],
                      "pc_widget": body.get("pc_widget") or {},
                      "pc_widget_history": (body.get("pc_widget_history") or [])[:100],
                      "cline": body.get("cline") or {},
                      "checked_at": _iso(now)}
        except Exception:  # noqa: BLE001 - older peer: plain /health only
            req = urllib.request.Request(PEER_BRIDGE_URL + "/health",
                                         headers=headers)
            with urllib.request.urlopen(req, timeout=_PEER_PROBE_TIMEOUT) as resp:
                body = json.loads(resp.read().decode("utf-8", "replace"))
            result = {"online": True, "status": body.get("status", "?"),
                      "version": body.get("version", "?"),
                      "role": body.get("role", "?"),
                      "checked_at": _iso(now)}
    except Exception as exc:  # noqa: BLE001 — any failure just means "offline"
        result = {"online": False, "error": str(exc)[:200],
                  "checked_at": _iso(now)}
    _peer_probe["at"] = now
    _peer_probe["result"] = result
    return result


def _device_entry(now: float, device_id: str, label: str) -> dict:
    """One devices-panel row: last contact + connected/not for a known device."""
    info = _client_info(now, device_id)
    threshold = DEVICE_ONLINE_SEC.get(device_id, 30 * 60)
    return {
        "id": device_id,
        "label": label,
        "last_seen": info["last_seen"],
        "age_sec": info["age_sec"],
        "last_request": info["last_request"],
        "online": info["age_sec"] is not None and info["age_sec"] < threshold,
        "online_threshold_sec": threshold,
    }


def _client_info(now: float, actor: str) -> dict:
    ts = _last_seen.get(actor)
    return {
        "last_seen": _iso(ts) if ts else None,
        "age_sec": round(now - ts, 1) if ts else None,
        "last_request": _last_request.get(actor),
    }


def _build_snapshot(version: str,
                    get_sources: Callable[[], Dict[str, Any]],
                    get_pending_events: Callable[[], list],
                    config_path: Path,
                    chess_service: Callable[[], Any],
                    peer_cache_only: bool = False) -> dict:
    now = time.time()

    sources_out = []
    for name, source in get_sources().items():
        try:
            health = source.health()
        except Exception as exc:
            health = {"status": "error", "error": str(exc)}
        try:
            latest = source.get_latest()
        except Exception:
            latest = None
        try:
            recent = source.get_recent(8)
        except Exception:
            recent = []
        sources_out.append({"name": name, "description": source.description,
                            "health": health, "latest": latest, "recent": recent})

    config = _read_json(config_path) or {"version": 1, "habits": []}
    pending = get_pending_events()

    chess: Dict[str, Any] = {"available": False, "status": None, "recent": []}
    try:
        service = chess_service()
        chess = {"available": True, "status": service.status(),
                 "recent": service.recent(8)}
    except Exception as exc:
        chess = {"available": False, "status": None, "recent": [],
                 "error": str(exc)}

    with _history_lock:
        history = list(_history[-60:])

    # The "pc" row only exists where local tools actually run. On a
    # remote-role bridge no loopback client ever appears, so the row would
    # sit permanently (and misleadingly) offline - hide it there.
    devices = [_device_entry(now, "phone", "Android app (phone)")]
    if ROLE == "full":
        devices.append(_device_entry(now, "pc", "PC bubble widget / local tools"))
    devices.append(_device_entry(now, "tailcue", "TailCue server"))
    peer = _peer_health_cached(cache_only=peer_cache_only)
    if peer is not None:
        devices.append({
            "id": "peer_bridge",
            "label": f"Peer bridge ({PEER_BRIDGE_URL})",
            "last_seen": peer.get("checked_at"),
            "age_sec": None,
            "last_request": "/health probe",
            "online": bool(peer.get("online")),
            "peer": peer,
        })

        # Server-side fold of the peer's device rows: the Connected devices
        # panel must be accurate on BOTH bridges. A device that only talks to
        # the peer (e.g. the PC bubble widget, which always uses the laptop
        # bridge) must still show as connected here — even if the client-side
        # merge in dashboard.html sees a partial or stale peer list. Rows that
        # exist locally get their online flag OR-ed with the peer's; rows the
        # peer saw but this bridge never did are appended (via_peer=True).
        if peer.get("online"):
            by_id = {d["id"]: d for d in devices}
            for pd in (peer.get("devices") or []):
                if not isinstance(pd, dict):
                    continue
                pid = pd.get("id")
                if not pid or pid == "peer_bridge":
                    continue
                row = by_id.get(pid)
                if row is None:
                    merged = {**pd, "via_peer": True}
                    devices.append(merged)
                    by_id[pid] = merged
                elif pd.get("online"):
                    row["online"] = True
                    # Keep whichever sighting is fresher for last-seen display.
                    pl, rl = pd.get("last_seen"), row.get("last_seen")
                    if pl and (not rl or str(pl) > str(rl)):
                        row["last_seen"] = pl
                        row["age_sec"] = pd.get("age_sec")
                        row["last_request"] = (
                            f"{pd.get('last_request') or '—'} (via peer)")
                        row["via_peer"] = True

    return {
        "peer": peer,
        "server": {
            "status": "running",
            "version": version,
            "started_at": _iso(_started_at),
            "uptime_sec": int(now - _started_at),
            "now": _iso(now),
            "role": ROLE,
            "device_name": DEVICE_NAME,
            "tailcue_app_url": TAILCUE_APP_URL,
            "peer_bridge_url": PEER_BRIDGE_URL,
        },
        "clients": {
            "phone": _client_info(now, "phone"),
            "pc": _client_info(now, "pc"),
            "tailcue": _client_info(now, "tailcue"),
        },
        "devices": devices,
        "sources": sources_out,
        "pc_widget": {
            "config": config,
            "pending_events": pending,
            "pending_count": len(pending),
        },
        "chess": chess,
        "history": history,
        "pc_widget_history": widget_history_entries(100),
        "activity": list(_activity)[-40:],
        "cline": _cline_snapshot(),
    }


# ── installer ─────────────────────────────────────────────────────────────────

_DAY_RE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_TIME_RE = re.compile(r"^\d{2}:\d{2}:\d{2}$")


def _require_private(request) -> None:
    """Dashboard endpoints serve private-network clients only."""
    host = request.client.host if request.client else ""
    try:
        ip = ipaddress.ip_address(host.split("%")[0])
    except ValueError:
        ip = None
    allowed = ip is not None and (
        ip.is_loopback or ip.is_private or ip.is_link_local
        or ip in ipaddress.ip_network("100.64.0.0/10"))
    if not allowed:
        raise HTTPException(status_code=403,
                            detail="dashboard only available on the local network")


def _orig_of(entry: dict) -> dict:
    """The `orig` payload a correction event must carry — the entry
    exactly as the phone originally applied it."""
    return {
        "date": str(entry.get("date") or ""),
        "start": str(entry.get("start") or ""),
        "end": str(entry.get("end") or ""),
        "minutes": max(0, int(entry.get("minutes") or 0)),
        "kind": str(entry.get("kind") or "tap"),
    }


def install(app: FastAPI,
            version: str,
            get_sources: Callable[[], Dict[str, Any]],
            get_pending_events: Callable[[], list],
            config_path: Path,
            chess_service: Callable[[], Any],
            queue_event: Callable[[dict], dict],
            edit_pending: Callable[[str, str, str, str, int], bool],
            delete_pending: Callable[[str], bool],
            verify_key: Optional[Callable[..., Any]] = None) -> None:
    """Attach the dashboard routes + activity middleware to the bridge app.

    verify_key is the bridge's X-App-Auth dependency (optional — the
    heartbeat endpoint is only mounted when it is provided).
    """

    _load_history()
    _load_widget_history()
    note("bridge", "server_start", "Bridge server started")

    @app.middleware("http")
    async def _record_activity(request, call_next):
        started = time.time()
        response = await call_next(request)
        try:
            client = request.client.host if request.client else ""
            path = request.url.path
            actor = _actor_of(client, path,
                              request.headers.get("user-agent", ""))
            # /api/v1/heartbeat names its own device in the body — the handler
            # records it. Classifying it here too would credit the caller's
            # network origin (e.g. TailCue on the VPS looks like "phone").
            if (actor != "dashboard" and actor != "peer_bridge"
                    and path != "/api/v1/heartbeat"):
                _activity.append({
                    "ts": _iso(time.time()),
                    "actor": actor,
                    "method": request.method,
                    "path": path,
                    "client": client,
                    "status": response.status_code,
                    "ms": round((time.time() - started) * 1000),
                })
                _last_seen[actor] = time.time()
                _last_request[actor] = f"{request.method} {path}"
        except Exception:
            pass  # telemetry must never break a request
        return response

    @app.get("/", include_in_schema=False)
    def index():
        return FileResponse(STATIC_DIR / "dashboard.html",
                            headers={"Cache-Control": "no-store"})

    @app.get("/dashboard", include_in_schema=False)
    def dashboard_page():
        return FileResponse(STATIC_DIR / "dashboard.html",
                            headers={"Cache-Control": "no-store"})

    if verify_key is not None:
        @app.post("/api/v1/heartbeat", tags=["dashboard"])
        def heartbeat(request: Request, payload: Dict[str, Any],
                      _key: Any = Security(verify_key)):
            """Explicit device heartbeat — records a contact for the devices panel.

            Auth-required (X-App-Auth, same shared secret). Body:
            {"device": "phone" | "tailcue" | "pc" | any label}. Used by clients
            that otherwise wouldn't touch the bridge often enough to look
            "connected" (e.g. TailCue's worker tick every 6 h).
            """
            device = str(payload.get("device") or "").strip().lower()
            if not device:
                raise HTTPException(status_code=400, detail="'device' is required")
            now = time.time()
            _last_seen[device] = now
            _last_request[device] = "heartbeat"
            return {"ok": True, "device": device, "at": _iso(now)}

    @app.get("/api/v1/dashboard", tags=["dashboard"])
    def dashboard_snapshot(request: Request):
        """One aggregated snapshot for the PC dashboard UI.

        No token — the page must 'just open' in a browser. Access is instead
        restricted to private-network clients (loopback / RFC1918 LAN /
        Tailscale CGNAT 100.x / link-local), so the endpoint is never
        reachable from arbitrary public addresses.
        """
        _require_private(request)
        # Peer probes get cache-only peer data so two bridges polling each
        # other never nest blocking probes.
        ua = request.headers.get("user-agent", "")
        return _build_snapshot(version, get_sources, get_pending_events,
                               config_path, chess_service,
                               peer_cache_only=PEER_PROBE_UA in ua)

    # ── PC-widget event editor (the dashboard replacement for the bubble
    # widget's old right-click "Today's history" dialog) ─────────────────
    #
    # Semantics mirror pc_widget_sync.append_correction_event exactly:
    #   · still-PENDING event  → edited/deleted in place (the phone never
    #     applied it, so no correction is needed);
    #   · already-ACKED event  → a session_edit / session_delete correction
    #     is queued carrying ref_id + `orig` (the values the phone did
    #     apply), which the phone inverts before applying the fix.

    @app.post("/api/v1/dashboard/garmin_fetch", tags=["dashboard"])
    def dashboard_garmin_fetch(request: Request, payload: Dict[str, Any]):
        """Force a Garmin fetch/sync of recent data from the dashboard.

        Runs garmin_proxy/fetch_data.py in the background (token-based,
        never logs in) and refreshes garmin_cache.json — the same cache
        the bridge serves to the phone. Outcome lands in the Garmin
        source's health() on the next snapshot poll.
        """
        _require_private(request)
        source = get_sources().get("garmin")
        if source is None or not hasattr(source, "trigger_fetch"):
            raise HTTPException(status_code=404, detail="garmin source not registered")
        try:
            days = max(1, min(30, int(payload.get("days") or 1)))
        except (TypeError, ValueError):
            days = 1
        force = bool(payload.get("force", True))
        result = source.trigger_fetch(days=days, force=force)
        note("dashboard", "garmin_fetch",
             f"Garmin fetch triggered from dashboard ({days} day(s), "
             f"force={force}): {result.get('status')}")
        return result

    @app.post("/api/v1/dashboard/widget_edit", tags=["dashboard"])
    def dashboard_widget_edit(request: Request, payload: Dict[str, Any]):
        """Edit one PC-widget event (queue or history) from the dashboard."""
        _require_private(request)
        event_id = str(payload.get("id") or "").strip()
        date = str(payload.get("date") or "").strip()
        start = str(payload.get("start") or "").strip()
        end = str(payload.get("end") or "").strip()
        try:
            minutes = max(0, int(payload.get("minutes") or 0))
        except (TypeError, ValueError):
            minutes = -1
        if not event_id:
            raise HTTPException(status_code=400, detail="'id' is required")
        if (not _DAY_RE.match(date) or not _TIME_RE.match(start)
                or not _TIME_RE.match(end) or minutes < 0):
            raise HTTPException(status_code=400,
                                detail="'date' (YYYY-MM-DD), 'start'/'end' "
                                       "(HH:MM:SS) and 'minutes' (int) are required")
        entry = widget_history_find(event_id)
        if entry is None:
            raise HTTPException(status_code=404, detail="unknown event id")
        if entry.get("correction") == "deleted":
            raise HTTPException(status_code=400, detail="event already deleted")

        # 1) Still pending → rewrite in place, nothing was applied yet.
        if edit_pending(event_id, date, start, end, minutes):
            widget_history_update_values(event_id, date, start, end, minutes)
            note("dashboard", "event_edited",
                 f"Edited {entry.get('habit')} in the pending queue → "
                 f"{start}–{end} ({minutes} min)")
            return {"ok": True, "mode": "in_place"}

        # 2) Already acked → queue a session_edit correction.
        correction = queue_event({
            "habit": entry.get("habit") or "?",
            "kind": "session_edit",
            "date": date, "start": start, "end": end, "minutes": minutes,
            "ref_id": event_id, "orig": _orig_of(entry),
        })
        widget_history_update_values(event_id, date, start, end, minutes)
        note("dashboard", "event_edited",
             f"Correction queued for {entry.get('habit')} → "
             f"{start}–{end} ({minutes} min)")
        return {"ok": True, "mode": "correction",
                "correction_id": correction.get("id")}

    @app.post("/api/v1/dashboard/widget_delete", tags=["dashboard"])
    def dashboard_widget_delete(request: Request, payload: Dict[str, Any]):
        """Delete one PC-widget event (queue or history) from the dashboard."""
        _require_private(request)
        event_id = str(payload.get("id") or "").strip()
        if not event_id:
            raise HTTPException(status_code=400, detail="'id' is required")

        # 1) Still pending → drop it before the phone ever sees it.
        if delete_pending(event_id):
            widget_history_mark_corrected(event_id, "deleted")
            note("dashboard", "event_deleted",
                 "Removed a pending event before the phone saw it")
            return {"ok": True, "mode": "in_place"}

        entry = widget_history_find(event_id)
        if entry is None:
            raise HTTPException(status_code=404, detail="unknown event id")
        if entry.get("correction") == "deleted":
            raise HTTPException(status_code=400, detail="event already deleted")

        # 2) Already acked → queue a session_delete correction.
        queue_event({
            "habit": entry.get("habit") or "?",
            "kind": "session_delete",
            "date": entry.get("date") or "",
            "start": entry.get("start") or "",
            "end": entry.get("end") or "",
            "minutes": int(entry.get("minutes") or 0),
            "ref_id": event_id, "orig": _orig_of(entry),
        })
        note("dashboard", "event_deleted",
             f"Delete correction queued for {entry.get('habit')} "
             f"({entry.get('start')}–{entry.get('end')})")
        return {"ok": True, "mode": "correction"}
