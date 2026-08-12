"""
Source registry for the Tail Bridge.

To register a new source, add it to get_all_sources():
    from .my_thing import MyThingSource
    sources.append(MyThingSource())
"""

from __future__ import annotations

from typing import List

from .base import BridgeSource
from .movies import MovieSource
from .garmin import GarminSource


def get_all_sources() -> List[BridgeSource]:
    """Return all registered bridge data sources."""
    sources: List[BridgeSource] = [
        MovieSource(),
        GarminSource(),
        # ── Future sources go here ──────────────────────────────────────────
        # MusicSource(),
        # BookSource(),
    ]
    return sources

__all__ = ["BridgeSource", "get_all_sources"]
