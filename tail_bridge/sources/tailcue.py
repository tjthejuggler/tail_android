"""TailCue cues — predictive warnings pushed from TailCue to the phone.

TailCue (PC) enqueues cues; the Tail Android app polls for pending ones,
acks them, and posts 👍/👎/note feedback which TailCue consumes back. The store
is a small JSON file so it survives bridge restarts and is inspectable from
the bridge dashboard.

Endpoints (registered in bridge_server.py, X-App-Auth protected):
    POST /api/v1/tailcue/cues                      — TailCue enqueues cues
    GET  /api/v1/tailcue/cues/pending?limit=20     — phone fetches unacked cues
    POST /api/v1/tailcue/cues/{cid}/ack            — phone displayed the cue
    POST /api/v1/tailcue/cues/{cid}/feedback       — phone 👍/👎/note
    GET  /api/v1/tailcue/cues/feedback?take=50     — TailCue consumes feedback
"""
from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional

from .base import BridgeSource

STORE_PATH = Path.home() / ".config" / "tail_bridge" / "tailcue_cues.json"
MAX_CUES = 500
MAX_FEEDBACK = 500

_lock = threading.Lock()


def _now() -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%S", time.gmtime())


def _load() -> Dict[str, Any]:
    if not STORE_PATH.exists():
        return {"cues": [], "feedback": []}
    try:
        return json.loads(STORE_PATH.read_text())
    except (json.JSONDecodeError, OSError):
        return {"cues": [], "feedback": []}


def _save(store: Dict[str, Any]) -> None:
    STORE_PATH.parent.mkdir(parents=True, exist_ok=True)
    STORE_PATH.write_text(json.dumps(store, indent=1))


def enqueue_cues(cues: List[Dict[str, Any]]) -> int:
    """Add cues (deduped by id). Returns how many were actually added."""
    added = 0
    with _lock:
        store = _load()
        known = {c["id"] for c in store["cues"]}
        for c in cues:
            cid = str(c.get("id", "")).strip()
            if not cid or cid in known:
                continue
            store["cues"].append({
                "id": cid,
                "created_at": c.get("created_at") or _now(),
                "family": c.get("family", ""),
                "severity": c.get("severity", "info"),
                "title": c.get("title", "")[:200],
                "body": c.get("body", "")[:2000],
                "advice": c.get("advice", "")[:2000],
                "queued_at": _now(),
                "acked": False,
                "acked_at": None,
            })
            known.add(cid)
            added += 1
        store["cues"] = store["cues"][-MAX_CUES:]
        _save(store)
    return added


def pending_cues(limit: int = 20) -> List[Dict[str, Any]]:
    with _lock:
        store = _load()
        return [c for c in store["cues"] if not c.get("acked")][-limit:]


def ack_cue(cid: str) -> bool:
    with _lock:
        store = _load()
        for c in store["cues"]:
            if c["id"] == cid and not c.get("acked"):
                c["acked"] = True
                c["acked_at"] = _now()
                _save(store)
                return True
        return False


def add_feedback(cid: str, rating: int, comment: str) -> bool:
    with _lock:
        store = _load()
        if not any(c["id"] == cid for c in store["cues"]):
            return False
        store["feedback"].append({"id": cid, "rating": int(rating),
                                  "comment": (comment or "")[:2000],
                                  "ts": _now(), "consumed": False})
        store["feedback"] = store["feedback"][-MAX_FEEDBACK:]
        _save(store)
        return True


def take_feedback(take: int = 50) -> List[Dict[str, Any]]:
    """Return and mark-consumed the unconsumed feedback items (TailCue pulls)."""
    with _lock:
        store = _load()
        out = []
        for f in store["feedback"]:
            if not f.get("consumed") and len(out) < take:
                f["consumed"] = True
                out.append({k: f[k] for k in ("id", "rating", "comment", "ts")})
        if out:
            _save(store)
        return out


class TailCueSource(BridgeSource):
    """Standard latest/recent/health views over the cue store."""

    @property
    def name(self) -> str:
        return "tailcue"

    @property
    def description(self) -> str:
        return "TailCue predictive warnings (cues) for the phone"

    def get_latest(self) -> Optional[Dict[str, Any]]:
        with _lock:
            store = _load()
        return store["cues"][-1] if store["cues"] else None

    def get_recent(self, limit: int = 10) -> List[Dict[str, Any]]:
        with _lock:
            store = _load()
        return list(reversed(store["cues"]))[:limit]

    def health(self) -> Dict[str, Any]:
        with _lock:
            store = _load()
        return {"status": "ok",
                "total_cues": len(store["cues"]),
                "pending": sum(1 for c in store["cues"] if not c.get("acked")),
                "unconsumed_feedback": sum(1 for f in store["feedback"]
                                           if not f.get("consumed"))}
