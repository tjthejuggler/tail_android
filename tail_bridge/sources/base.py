"""
Base interface for Tail Bridge data sources.

Every tethered feature implements this interface. The bridge server
auto-generates REST endpoints for each registered source.

To add a new source:
  1. Subclass BridgeSource in sources/my_thing.py
  2. Instantiate it in sources/__init__.py:get_all_sources()
  3. Done — /api/v1/my_thing/latest, /recent, /health are live.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import Any, Dict, List, Optional


class BridgeSource(ABC):
    """Abstract base for all bridge data sources."""

    @property
    @abstractmethod
    def name(self) -> str:
        """URL-safe identifier (e.g. 'movies', 'music', 'books')."""

    @property
    @abstractmethod
    def description(self) -> str:
        """Human-readable description shown in /api/v1/sources."""

    @abstractmethod
    def get_latest(self) -> Optional[Dict[str, Any]]:
        """
        Return the most recent item, or None if no data.
        The return value is serialized directly to JSON.
        """

    @abstractmethod
    def get_recent(self, limit: int = 10) -> List[Dict[str, Any]]:
        """
        Return up to `limit` most-recent items (newest first).
        """

    @abstractmethod
    def health(self) -> Dict[str, Any]:
        """
        Return a health/status dict for this source.
        Should include at minimum: {'status': 'ok'|'error', ...}
        """
