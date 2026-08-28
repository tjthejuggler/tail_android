"""
Garmin health-metrics data source for the Tail Bridge.

Reads from the same ``garmin_cache.json`` that ``garmin_proxy/fetch_data.py``
populates, so Garmin data flows through the unified bridge alongside movies
and any future sources — without requiring a separate HTTP hop to the
Garmin proxy (port 8000).

Exposes the standard BridgeSource endpoints:
    GET /api/v1/garmin/latest        → most recent day's metrics
    GET /api/v1/garmin/recent?limit=N → N most recent days
    GET /api/v1/garmin/health         → cache + connection status

Plus one control used by the dashboard:
    trigger_fetch(days, force) → runs garmin_proxy/fetch_data.py in a
    background thread (using the garmin_proxy venv interpreter when
    available) and records the outcome in garmin_proxy/fetch_status.json.
"""

from __future__ import annotations

import json
import logging
import os
import subprocess
import sys
import threading
import time
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from .base import BridgeSource

logger = logging.getLogger(__name__)

# garmin_cache.json lives in garmin_proxy/ (sibling of tail_bridge/)
_DEFAULT_CACHE = Path(__file__).resolve().parent.parent.parent / "garmin_proxy" / "garmin_cache.json"
CACHE_FILE = Path(os.environ.get("GARMIN_CACHE_FILE", str(_DEFAULT_CACHE)))

GARMIN_PROXY_DIR = CACHE_FILE.parent
FETCH_SCRIPT = GARMIN_PROXY_DIR / "fetch_data.py"
LAST_FETCH_FILE = GARMIN_PROXY_DIR / ".last_fetch_timestamp"
FETCH_STATUS_FILE = GARMIN_PROXY_DIR / "fetch_status.json"
FETCH_LOG_FILE = GARMIN_PROXY_DIR / "fetch_status.log"
TOKEN_STORE = Path(os.environ.get("GARMINTOKENS", str(Path.home() / ".garminconnect")))
MIN_FETCH_INTERVAL_SECONDS = 15 * 60  # mirrors fetch_data.py
FETCH_TIMEOUT_SECONDS = 300           # mirrors garmin_proxy/app.py


def _garmin_python() -> str:
    """Interpreter that has garminconnect installed.

    Prefers the garmin_proxy venv (the bridge's own venv does not carry
    garminconnect); falls back to the current interpreter.
    """
    venv_py = GARMIN_PROXY_DIR / "venv" / "bin" / "python"
    if venv_py.exists():
        return str(venv_py)
    return sys.executable


class GarminSource(BridgeSource):
    """Serves cached Garmin health metrics through the bridge."""

    def __init__(self) -> None:
        self._fetch_lock = threading.Lock()

    @property
    def name(self) -> str:
        return "garmin"

    @property
    def description(self) -> str:
        return "Garmin health metrics (VO2 max, HRV, sleep, steps, etc.) from garmin_cache.json"

    # ── Cache helpers ──────────────────────────────────────────────────────

    def _load_cache(self) -> Dict[str, Any]:
        try:
            if os.path.exists(CACHE_FILE):
                with open(CACHE_FILE, "r", encoding="utf-8") as f:
                    return json.load(f)
        except Exception as e:
            logger.error(f"Error loading Garmin cache: {e}")
        return {"data": {}, "metadata": {}}

    def _sorted_dates(self, cache: Dict[str, Any]) -> List[str]:
        """Return date keys sorted newest-first."""
        dates = list(cache.get("data", {}).keys())
        dates.sort(reverse=True)
        return dates

    # ── Fetch status (shared with fetch_data.py runs triggered here) ──────

    def _read_fetch_status(self) -> Dict[str, Any]:
        try:
            if FETCH_STATUS_FILE.exists():
                with open(FETCH_STATUS_FILE, "r", encoding="utf-8") as f:
                    data = json.load(f)
                    return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            pass
        return {}

    def _write_fetch_status(self, status: Dict[str, Any]) -> None:
        try:
            FETCH_STATUS_FILE.write_text(
                json.dumps(status, indent=2) + "\n", encoding="utf-8")
        except OSError:
            pass  # best-effort status side-channel

    def _last_fetch_ts(self) -> Optional[float]:
        try:
            if LAST_FETCH_FILE.exists():
                return float(LAST_FETCH_FILE.read_text().strip())
        except (OSError, ValueError):
            pass
        return None

    # ── Manual fetch trigger (dashboard control) ───────────────────────────

    def trigger_fetch(self, days: int = 1, force: bool = True) -> Dict[str, Any]:
        """Run garmin_proxy/fetch_data.py in the background.

        Returns immediately with a started/rejected status; the outcome is
        recorded in fetch_status.json once the subprocess exits, so the
        dashboard's next snapshot picks it up.
        """
        if not FETCH_SCRIPT.exists():
            return {"status": "error",
                    "message": f"fetch script not found: {FETCH_SCRIPT}"}

        # One fetch at a time — a second click while running is a no-op.
        if not self._fetch_lock.acquire(blocking=False):
            return {"status": "already_running",
                    "message": "A Garmin fetch is already in progress"}

        def _run() -> None:
            try:
                self._write_fetch_status({
                    "state": "running",
                    "started_at": datetime.now().isoformat(timespec="seconds"),
                    "days": days, "force": force,
                })
                cmd = [_garmin_python(), str(FETCH_SCRIPT),
                       "--days", str(days)]
                if force:
                    cmd.append("--force")
                proc = subprocess.run(
                    cmd, cwd=str(GARMIN_PROXY_DIR),
                    capture_output=True, text=True,
                    timeout=FETCH_TIMEOUT_SECONDS)
                tail = "\n".join((proc.stdout or "").strip().splitlines()[-5:])
                if proc.returncode == 0:
                    state, msg = "ok", tail or "fetch completed"
                else:
                    err = "\n".join((proc.stderr or "").strip().splitlines()[-5:])
                    state, msg = "error", err or tail or f"exit code {proc.returncode}"
                self._write_fetch_status({
                    "state": state,
                    "finished_at": datetime.now().isoformat(timespec="seconds"),
                    "days": days, "force": force,
                    "returncode": proc.returncode,
                    "message": msg,
                })
                try:
                    FETCH_LOG_FILE.write_text(
                        (proc.stdout or "") + "\n--- stderr ---\n" + (proc.stderr or ""),
                        encoding="utf-8")
                except OSError:
                    pass
            except subprocess.TimeoutExpired:
                self._write_fetch_status({
                    "state": "error",
                    "finished_at": datetime.now().isoformat(timespec="seconds"),
                    "days": days, "force": force,
                    "message": f"fetch timed out after {FETCH_TIMEOUT_SECONDS}s",
                })
            except Exception as e:
                self._write_fetch_status({
                    "state": "error",
                    "finished_at": datetime.now().isoformat(timespec="seconds"),
                    "message": f"fetch failed: {e}",
                })
            finally:
                self._fetch_lock.release()

        threading.Thread(target=_run, daemon=True,
                         name="garmin-force-fetch").start()
        logger.info(f"Garmin fetch triggered from dashboard (days={days}, force={force})")
        return {"status": "fetch_started",
                "message": f"Fetch started for {days} day(s) in the background"}

    # ── BridgeSource interface ─────────────────────────────────────────────

    def get_latest(self) -> Optional[Dict[str, Any]]:
        cache = self._load_cache()
        dates = self._sorted_dates(cache)
        if not dates:
            return None
        return cache["data"][dates[0]]

    def get_recent(self, limit: int = 10) -> List[Dict[str, Any]]:
        cache = self._load_cache()
        dates = self._sorted_dates(cache)
        return [cache["data"][d] for d in dates[:limit]]

    def health(self) -> Dict[str, Any]:
        now = time.time()
        cache = self._load_cache()
        dates = self._sorted_dates(cache)
        meta = cache.get("metadata", {})

        last_fetch = self._last_fetch_ts()
        if last_fetch is None:
            rate_limited, next_in = False, None
        else:
            elapsed = now - last_fetch
            rate_limited = elapsed < MIN_FETCH_INTERVAL_SECONDS
            next_in = max(0, int(MIN_FETCH_INTERVAL_SECONDS - elapsed)) if rate_limited else None

        last_sync = meta.get("last_updated", "")
        sync_age = None
        if last_sync:
            try:
                sync_age = round(now - datetime.fromisoformat(last_sync).timestamp(), 1)
            except ValueError:
                pass

        latest_date = dates[0] if dates else ""
        today = datetime.now().date().isoformat()

        fetch_status = self._read_fetch_status()
        # A stale "running" entry (e.g. bridge killed mid-fetch) self-expires.
        if fetch_status.get("state") == "running":
            started = fetch_status.get("started_at", "")
            try:
                if now - datetime.fromisoformat(started).timestamp() > FETCH_TIMEOUT_SECONDS + 60:
                    fetch_status["state"] = "stale"
            except ValueError:
                fetch_status["state"] = "stale"

        try:
            cache_size_kb = round(CACHE_FILE.stat().st_size / 1024, 1)
        except OSError:
            cache_size_kb = None

        return {
            "status": "ok" if dates else "no_data",
            "total_days": len(dates),
            "latest_date": latest_date,
            "latest_is_today": latest_date == today,
            "today": today,
            "last_updated": last_sync,
            "sync_age_sec": sync_age,
            "last_fetch": (datetime.fromtimestamp(last_fetch)
                           .isoformat(timespec="seconds")) if last_fetch else "",
            "fetch_age_sec": round(now - last_fetch, 1) if last_fetch else None,
            "rate_limited": rate_limited,
            "next_fetch_in_sec": next_in,
            "token_store": str(TOKEN_STORE),
            "token_store_exists": TOKEN_STORE.exists(),
            "fetch_status": fetch_status,
            "fetch_script_exists": FETCH_SCRIPT.exists(),
            "cache_file": str(CACHE_FILE),
            "cache_exists": os.path.exists(CACHE_FILE),
            "cache_size_kb": cache_size_kb,
        }
