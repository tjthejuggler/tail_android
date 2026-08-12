"""
Movie data source for the Tail Bridge.

Reads from movie_cache.json (maintained by movie_watcher.py) and exposes
the latest / recent movies through the BridgeSource interface.
"""

from __future__ import annotations

import json
import logging
import os
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional

from .base import BridgeSource

logger = logging.getLogger(__name__)

SCRIPT_DIR = Path(__file__).resolve().parent.parent.parent
CACHE_FILE = os.environ.get(
    "MOVIE_CACHE_FILE",
    str(Path(__file__).resolve().parent.parent / "movie_cache.json")
)


class MovieSource(BridgeSource):
    """Serves movie/series watch history from the watcher's cache."""

    @property
    def name(self) -> str:
        return "movies"

    @property
    def description(self) -> str:
        return "Movies and TV series watched on the desktop (KDE Activity Monitor)"

    def _load_cache(self) -> Dict[str, Any]:
        try:
            if os.path.exists(CACHE_FILE):
                with open(CACHE_FILE, "r", encoding="utf-8") as f:
                    return json.load(f)
        except Exception as e:
            logger.error(f"Error loading movie cache: {e}")
        return {"movies": [], "metadata": {}}

    def get_latest(self) -> Optional[Dict[str, Any]]:
        cache = self._load_cache()
        movies = cache.get("movies", [])
        if not movies:
            return None
        return movies[0]  # cache is newest-first

    def get_recent(self, limit: int = 10) -> List[Dict[str, Any]]:
        cache = self._load_cache()
        movies = cache.get("movies", [])
        return movies[:limit]

    def health(self) -> Dict[str, Any]:
        cache = self._load_cache()
        movies = cache.get("movies", [])
        meta = cache.get("metadata", {})
        return {
            "status": "ok" if movies else "no_data",
            "total_entries": len(movies),
            "last_updated": meta.get("last_updated", ""),
            "cache_file": CACHE_FILE,
            "cache_exists": os.path.exists(CACHE_FILE),
        }
