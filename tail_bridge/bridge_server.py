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

import logging
import os
import sys
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


if __name__ == "__main__":
    import uvicorn
    port = int(os.environ.get("TAIL_BRIDGE_PORT", "8001"))
    uvicorn.run(app, host="0.0.0.0", port=port)
