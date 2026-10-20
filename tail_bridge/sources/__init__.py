"""
Source registry for the Tail Bridge.

To register a new source, add it to get_all_sources():
    from .my_thing import MyThingSource
    sources.append(MyThingSource())
"""

from __future__ import annotations

import os
from typing import List

from .base import BridgeSource
from .movies import MovieSource
from .garmin import GarminSource
from .tailcue import TailCueSource


def get_all_sources() -> List[BridgeSource]:
    """Return all registered bridge data sources.

    TAIL_BRIDGE_ROLE=remote (the always-on server deployment) skips the
    movie source: it reads the laptop's KDE Activity DB, which does not
    exist on the server. Everything else (garmin, tailcue, and future
    device-independent sources) registers on both deployments.
    """
    role = os.environ.get("TAIL_BRIDGE_ROLE", "full")
    sources: List[BridgeSource] = []
    if role != "remote":
        sources.append(MovieSource())
    sources.append(GarminSource())
    sources.append(TailCueSource())
    # ── Future sources go here ──────────────────────────────────────────
    # MusicSource(),
    # BookSource(),
    return sources

__all__ = ["BridgeSource", "get_all_sources"]
