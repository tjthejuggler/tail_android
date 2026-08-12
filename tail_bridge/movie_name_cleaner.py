#!/usr/bin/env python3
"""
Reusable movie / series filename → clean title parser.

Extracted from clean_video_history.py so that both the one-shot history
cleaner and the live movie_watcher daemon share the exact same logic.

Public API:
    clean_filename(filepath) → MovieInfo(title, season, episode, raw)

Where:
    title   = "Show Name S01E05"  or  "Movie Title (2021)"  or  "Some Film"
    season  = int or None
    episode = int or None
    raw     = original basename (without directory path)
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Optional


@dataclass
class MovieInfo:
    title: str
    season: Optional[int] = None
    episode: Optional[int] = None
    raw: str = ""


# ── helpers ──────────────────────────────────────────────────────────────────

def get_basename(filepath: str) -> str:
    """Extract the filename from any path style (/, mtp:/, https://, trash:/)."""
    fp = filepath.replace('\\', '/')
    if '/' in fp:
        return fp.rsplit('/', 1)[-1]
    return fp


def strip_extension(name: str) -> str:
    return re.sub(r'\.(mkv|mp4|avi|mov|webm|m4v|flv|wmv|ts|m2ts)$', '', name, flags=re.IGNORECASE)


# Acronym pattern: single letters separated by dots, e.g. "T.R.o.K.H" or "V.H.S"
ACRONYM_RE = re.compile(r'(?<![A-Za-z])([A-Za-z](?:\.[A-Za-z]){2,})(?![A-Za-z])')


def fix_acronyms(text: str) -> str:
    """Collapse dotted acronyms: T.R.o.K.H → TRoKH, V.H.S → VHS."""
    def _replace(m):
        return m.group(1).replace('.', '')
    return ACRONYM_RE.sub(_replace, text)


def clean_separators(text: str) -> str:
    """Replace dots / underscores with spaces, collapse whitespace."""
    text = fix_acronyms(text)
    text = text.replace('.', ' ').replace('_', ' ')
    text = re.sub(r'\s+', ' ', text).strip()
    return text


def strip_trailing_sep(text: str) -> str:
    """Remove trailing dashes, spaces, colons — NOT parens/brackets."""
    return re.sub(r'[\s\-:_]+$', '', text).strip()


def normalize_case(text: str) -> str:
    """Title-case with apostrophe fix: 'widow's bay' → 'Widow's Bay'."""
    text = text.title()
    # Fix Python .title() breaking on apostrophes: "Widow'S" → "Widow's"
    text = re.sub(r"'([A-Z])", lambda m: "'" + m.group(1).lower(), text)
    return text


# Regex to find S##E## (case-insensitive, flexible separators)
SE_PATTERN = re.compile(r'[Ss](\d{1,2})\s*[Ee](\d{1,3})')

# Year pattern (1950-2050), optionally in brackets/parens
YEAR_PATTERN = re.compile(r'[\(\[]?(19[5-9]\d|20[0-4]\d|2050)[\)\]]?')

# Quality / release tags to strip from movie names without years
QUALITY_TAGS = re.compile(
    r'(?i)\b('
    r'\d{3,4}p|480|540|720|1080|2160|4k|uhd|hdr|'
    r'webrip|web-dl|web\sdl|web|brrip|bluray|blu-ray|hdtv|hdts|hdscr|'
    r'screener|scr|cam|tc|dvdrip|dvd|hdcam|'
    r'hevc|x265|h265|x264|h264|h\.264|avc|'
    r'aac|ac3|ddp5\.1|dd5\.1|ddp|dd|dts|5\.1|2\.0|10bit|6ch|'
    r'atmos|truehd|eac3|'
    r'proper|repack|extended|unrated|remastered|internal|readnfo|'
    r'complete|dual|audio|multi|subs|sub|esub|hc|vostfr|'
    r'tagalogue|tagalog|danish|german|hindi|french|'
    r'split|scenes|scene|'
    r'yify|yts|rarbg|etrg|mkvcage|galaxyrg|galaxytv|rmteam|shaanig|'
    r'jyk|sujaidr|world|sunscreen|rgb|lama|neonoir|megusta|'
    r'ethel|bae|jff|edith|bone|flux|yello|ghost|silence|'
    r'darkflix|elite|rubik|psa|evo|bia|accomplishedyak|successfulcrab|'
    r'rbb|c1nem4|ws'
    r')\b'
)

# Known release group / site prefixes to strip
SITE_PREFIX = re.compile(r'(?i)^www\.\S+\s*-\s*')


def clean_filename(filepath: str) -> MovieInfo:
    """
    Parse a video filepath and return a MovieInfo with the cleaned title.

    Tries (in order):
      1. TV pattern: S##E##  →  "Show Name S01E05"
      2. Movie with year    →  "Movie Title (2021)"
      3. Fallback            →  strip quality tags, best-effort clean
    """
    basename = get_basename(filepath)
    # Strip leading junk like "0-" from trash paths
    basename = re.sub(r'^\d+-', '', basename)
    name = strip_extension(basename)

    # ── Try TV: S##E## ──────────────────────────────────────────────────────
    se = SE_PATTERN.search(name)
    if se:
        season = int(se.group(1))
        episode = int(se.group(2))
        show_part = name[:se.start()]
        show_part = clean_separators(show_part)
        show_part = SITE_PREFIX.sub('', show_part).strip()
        show_part = strip_trailing_sep(show_part)
        # Strip trailing year from show name
        show_part = re.sub(r'\s*\(?(19[5-9]\d|20[0-4]\d)\)?$', '', show_part).strip()
        show_part = strip_trailing_sep(show_part)
        if show_part:
            show_part = normalize_case(show_part)
            title = f"{show_part} S{season:02d}E{episode:02d}"
            return MovieInfo(title=title, season=season, episode=episode, raw=basename)

    # ── Try movie with year ─────────────────────────────────────────────────
    yr = YEAR_PATTERN.search(name)
    if yr:
        movie_part = name[:yr.start()]
        movie_part = clean_separators(movie_part)
        movie_part = SITE_PREFIX.sub('', movie_part).strip()
        movie_part = strip_trailing_sep(movie_part)
        year = yr.group(1)
        if movie_part:
            movie_part = normalize_case(movie_part)
            title = f"{movie_part} ({year})"
            return MovieInfo(title=title, season=None, episode=None, raw=basename)

    # ── Fallback: strip quality tags from the whole name ────────────────────
    cleaned = clean_separators(name)
    cleaned = SITE_PREFIX.sub('', cleaned).strip()
    # Iteratively strip quality tags
    prev = None
    while prev != cleaned:
        prev = cleaned
        cleaned = QUALITY_TAGS.sub('', cleaned)
        cleaned = re.sub(r'\s+', ' ', cleaned).strip()
        cleaned = strip_trailing_sep(cleaned)
    # Strip release-group suffix after dash: "Name-GROUP"
    cleaned = re.sub(r'-\s*[A-Za-z0-9]+$', '', cleaned).strip()
    cleaned = re.sub(r'\[.*?\]', '', cleaned).strip()
    cleaned = re.sub(r'\s+', ' ', cleaned).strip()
    cleaned = strip_trailing_sep(cleaned)
    if not cleaned:
        cleaned = clean_separators(name)
    cleaned = normalize_case(cleaned)
    return MovieInfo(title=cleaned, season=None, episode=None, raw=basename)
