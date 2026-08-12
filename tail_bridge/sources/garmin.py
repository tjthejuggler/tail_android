"""
Garmin health-metrics data source for the Tail Bridge.

Reads from the same ``garmin_cache.json`` that ``garmin_proxy/fetch_data.py``
populates, so Garmin data flows through the unified bridge alongside movies
and any future sources — without requiring a separate HTTP hop to the
Garmin proxy (port 8000).

Exposes the standard BridgeSource endpoints:
    GET /api/v1/garmin/latest        → most recent day's metrics
    GET /api/v1/garmin/recent?limit=N → N most recent days
    GET /api/v1/garmin/health         → cache status
"""

from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from .base import BridgeSource

logger = logging.getLogger(__name__)

# garmin_cache.json lives in garmin_proxy/ (sibling of tail_bridge/)
_DEFAULT_CACHE = Path(__file__).resolve().parent.parent.parent / "garmin_proxy" / "garmin_cache.json"
CACHE_FILE = os.environ.get("GARMIN_CACHE_FILE", str(_DEFAULT_CACHE))


class GarminSource(BridgeSource):
    """Serves cached Garmin health metrics through the bridge."""

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
        cache = self._load_cache()
        dates = self._sorted_dates(cache)
        meta = cache.get("metadata", {})
        return {
            "status": "ok" if dates else "no_data",
            "total_days": len(dates),
            "latest_date": dates[0] if dates else "",
            "last_updated": meta.get("last_updated", ""),
            "cache_file": CACHE_FILE,
            "cache_exists": os.path.exists(CACHE_FILE),
        }
