#!/usr/bin/env python3
"""
Seed the Tail app's daily location store with data from a Google Maps
Timeline JSON export.

Strategy
--------
1. Parse the Timeline export and bucket all coordinates by calendar date.
   Priority for "where I was on day X":
     a) Longest `visit` on that date (Google's own "you stayed here" calls).
     b) Otherwise, the modal coordinate (rounded to ~110 m grid) from
        `timelinePath` / `activity` / `rawSignals` points on that date.
2. Round the chosen coordinate to ~3 decimal places (~110 m) and reverse-
   geocode through OpenStreetMap Nominatim.  Results are cached on disk so
   re-runs cost zero network calls and clusters of nearby days share a
   single lookup (typical home/work clusters collapse thousands of days
   into tens of geocodes).
3. Format results as "Place, Region, Country" to match the existing
   convention in `tail_location_prefs.xml`.
4. Pull the existing prefs file from the device, MERGE (existing keys win
   so manual edits / current real-time entries survive), and push the
   merged file back.  Force-stop the app so SharedPreferences re-reads on
   next launch.

Usage
-----
    source venv/bin/activate
    python scripts/seed_locations_from_timeline.py \\
        --timeline /home/twain/noteVault/transfer/Timeline.json

Add `--dry-run` to skip the adb push step (writes the merged JSON to
`scripts/_seed_locations_out/merged.json` only).

Add `--no-merge-device` to ignore what is currently on the device and
write only the timeline-derived data (existing entries are still
preserved if you keep the default behaviour).
"""

from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from datetime import date, datetime, timedelta
from pathlib import Path
from typing import Iterable

# --- Constants ---------------------------------------------------------------

PACKAGE = "com.example.tail"
PREFS_FILE = "tail_location_prefs.xml"
PREFS_KEY = "daily_locations"
# Companion key holding "lat,lon" strings keyed by the same date strings.
# The world-map screen plots a person marker per day from this map.
PREFS_COORDS_KEY = "daily_coords"

# OSM Nominatim public endpoint.  Usage policy: <= 1 req/sec, custom UA.
NOMINATIM_URL = "https://nominatim.openstreetmap.org/reverse"
USER_AGENT = "tail-app-timeline-seed/1.0 (personal use)"

# 3 decimals ~= 110 m at the equator.  Adequate for "what city was I in".
COORD_ROUND = 3

OUT_DIR = Path(__file__).resolve().parent / "_seed_locations_out"
CACHE_FILE = OUT_DIR / "geocode_cache.json"
MERGED_FILE = OUT_DIR / "merged.json"
DERIVED_FILE = OUT_DIR / "derived_from_timeline.json"
PREFS_LOCAL = OUT_DIR / PREFS_FILE


# --- Timeline parsing --------------------------------------------------------

LATLNG_RE = re.compile(r"(-?\d+\.\d+)\s*°?\s*,\s*(-?\d+\.\d+)\s*°?")


def parse_latlng(value: str | None) -> tuple[float, float] | None:
    """Parse a Google-Timeline-style coord string `"40.011°, -105.229°"`."""
    if not value:
        return None
    m = LATLNG_RE.search(value)
    if not m:
        return None
    return float(m.group(1)), float(m.group(2))


def parse_iso_date(ts: str) -> date | None:
    """Return the calendar date of an ISO-8601 timestamp (preserving its TZ)."""
    if not ts:
        return None
    try:
        # Python 3.11+ accepts the trailing offset directly.
        return datetime.fromisoformat(ts).date()
    except ValueError:
        # Fall back to a tolerant manual parse.
        try:
            return datetime.strptime(ts[:10], "%Y-%m-%d").date()
        except ValueError:
            return None


def iter_segment_points(seg: dict) -> Iterable[tuple[date, float, float, str]]:
    """Yield (date, lat, lon, source_kind) for every point we can extract."""
    start_d = parse_iso_date(seg.get("startTime", ""))
    end_d = parse_iso_date(seg.get("endTime", ""))

    # ── visit ───────────────────────────────────────────────────────────────
    visit = seg.get("visit")
    if visit:
        latlng = (
            visit.get("topCandidate", {})
            .get("placeLocation", {})
            .get("latLng")
        )
        coords = parse_latlng(latlng)
        if coords and start_d and end_d:
            # Spread the visit across every date it overlaps.
            d = start_d
            while d <= end_d:
                yield d, coords[0], coords[1], "visit"
                d += timedelta(days=1)
        return

    # ── activity ────────────────────────────────────────────────────────────
    activity = seg.get("activity")
    if activity:
        for end_key in ("start", "end"):
            coords = parse_latlng(activity.get(end_key, {}).get("latLng"))
            if coords and start_d:
                yield start_d, coords[0], coords[1], "activity"
        return

    # ── timelinePath ────────────────────────────────────────────────────────
    path = seg.get("timelinePath")
    if path:
        for pt in path:
            coords = parse_latlng(pt.get("point"))
            if not coords:
                continue
            d = parse_iso_date(pt.get("time", "")) or start_d
            if d:
                yield d, coords[0], coords[1], "path"
        return


def iter_raw_signal_points(raw: dict) -> Iterable[tuple[date, float, float, str]]:
    """rawSignals carry GPS fixes too — useful when semanticSegments is sparse."""
    pos = raw.get("position")
    if not pos:
        return
    coords = parse_latlng(pos.get("LatLng") or pos.get("latLng"))
    if not coords:
        return
    d = parse_iso_date(pos.get("timestamp", ""))
    if d:
        yield d, coords[0], coords[1], "raw"


def extract_daily_coords(timeline: dict) -> dict[str, tuple[float, float]]:
    """Pick a single representative (lat, lon) for each calendar date."""
    # date_str -> { "visit_seconds": {(round_lat, round_lon): seconds},
    #               "point_counts":  Counter[(round_lat, round_lon)] }
    by_date: dict[str, dict] = defaultdict(
        lambda: {"visit_seconds": defaultdict(float), "point_counts": Counter()}
    )

    # --- visits with their durations get heaviest weight ---
    for seg in timeline.get("semanticSegments", []) or []:
        visit = seg.get("visit")
        if visit:
            latlng = (
                visit.get("topCandidate", {})
                .get("placeLocation", {})
                .get("latLng")
            )
            coords = parse_latlng(latlng)
            if not coords:
                continue
            try:
                start = datetime.fromisoformat(seg["startTime"])
                end = datetime.fromisoformat(seg["endTime"])
            except (KeyError, ValueError):
                continue
            # Distribute visit duration across the dates it overlaps.
            cur = start
            while cur < end:
                day_end = datetime.combine(
                    (cur + timedelta(days=1)).date(), datetime.min.time(), tzinfo=cur.tzinfo
                )
                slice_end = min(day_end, end)
                seconds = (slice_end - cur).total_seconds()
                key = (round(coords[0], COORD_ROUND), round(coords[1], COORD_ROUND))
                by_date[cur.date().isoformat()]["visit_seconds"][key] += seconds
                cur = slice_end
            continue

        # Otherwise count individual points.
        for d, lat, lon, _src in iter_segment_points(seg):
            key = (round(lat, COORD_ROUND), round(lon, COORD_ROUND))
            by_date[d.isoformat()]["point_counts"][key] += 1

    # rawSignals supplement
    for raw in timeline.get("rawSignals", []) or []:
        for d, lat, lon, _src in iter_raw_signal_points(raw):
            key = (round(lat, COORD_ROUND), round(lon, COORD_ROUND))
            by_date[d.isoformat()]["point_counts"][key] += 1

    chosen: dict[str, tuple[float, float]] = {}
    for date_str, buckets in by_date.items():
        # 1) Visit with the longest dwell time wins.
        if buckets["visit_seconds"]:
            best = max(buckets["visit_seconds"].items(), key=lambda kv: kv[1])
            chosen[date_str] = best[0]
            continue
        # 2) Otherwise modal point on that date.
        if buckets["point_counts"]:
            best = buckets["point_counts"].most_common(1)[0]
            chosen[date_str] = best[0]
    return chosen


# --- Reverse geocoding -------------------------------------------------------


def load_cache() -> dict[str, dict]:
    if CACHE_FILE.exists():
        try:
            return json.loads(CACHE_FILE.read_text())
        except json.JSONDecodeError:
            print(f"  WARNING: Corrupt cache at {CACHE_FILE}, ignoring.")
    return {}


def save_cache(cache: dict[str, dict]) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    CACHE_FILE.write_text(json.dumps(cache, indent=2, ensure_ascii=False))


def reverse_geocode(lat: float, lon: float, cache: dict[str, dict]) -> dict | None:
    """Look up `lat,lon` in Nominatim with a disk cache and 1 req/s rate limit."""
    key = f"{lat:.{COORD_ROUND}f},{lon:.{COORD_ROUND}f}"
    if key in cache:
        return cache[key]

    params = {
        "lat": f"{lat}",
        "lon": f"{lon}",
        "format": "jsonv2",
        "zoom": "10",  # ~city-level resolution
        "addressdetails": "1",
        "accept-language": "en",
    }
    url = f"{NOMINATIM_URL}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            data = json.loads(resp.read().decode("utf-8"))
    except Exception as exc:  # noqa: BLE001 — surface but don't crash the run
        print(f"  WARNING: geocode failed for {key}: {exc}")
        data = None

    cache[key] = data  # cache failures as None too — saves retry storms
    save_cache(cache)
    time.sleep(1.05)  # respect Nominatim's 1 req/s usage policy
    return data


def format_address(address: dict | None) -> str | None:
    """Format a Nominatim `address` dict as `"Place, Region, Country"`."""
    if not address:
        return None
    addr = address.get("address") or {}
    place = (
        addr.get("city")
        or addr.get("town")
        or addr.get("village")
        or addr.get("hamlet")
        or addr.get("suburb")
        or addr.get("city_district")
        or addr.get("municipality")
        or addr.get("county")
    )
    region = addr.get("state") or addr.get("region") or addr.get("province")
    country = addr.get("country")
    parts = [p for p in (place, region, country) if p]
    if not parts:
        return None
    return ", ".join(parts)


# --- Device I/O --------------------------------------------------------------


def adb(*args: str) -> str:
    """Run an adb command and return its stdout."""
    res = subprocess.run(
        ["adb", *args],
        check=True,
        capture_output=True,
        text=True,
    )
    return res.stdout


def _pull_device_prefs_xml() -> str | None:
    """Returns the raw prefs XML from the device, or None if not yet present."""
    try:
        return subprocess.run(
            ["adb", "shell", "run-as", PACKAGE, "cat", f"shared_prefs/{PREFS_FILE}"],
            check=True,
            capture_output=True,
            text=True,
        ).stdout
    except subprocess.CalledProcessError:
        return None


def _extract_prefs_key(xml: str, key: str) -> dict[str, str]:
    """Extract a single <string name=KEY> JSON map from a prefs XML blob."""
    m = re.search(rf'<string name="{re.escape(key)}">(.*?)</string>', xml, re.DOTALL)
    if not m:
        return {}
    raw = m.group(1)
    decoded = (
        raw.replace("&quot;", '"').replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    )
    try:
        return json.loads(decoded)
    except json.JSONDecodeError:
        print(f"  WARNING: could not parse existing on-device {key} JSON.")
        return {}


def pull_device_prefs() -> dict[str, str]:
    """Pull the existing label prefs JSON map from the device, or {} if absent."""
    xml = _pull_device_prefs_xml()
    return _extract_prefs_key(xml, PREFS_KEY) if xml else {}


def pull_device_coords() -> dict[str, str]:
    """Pull the existing daily_coords JSON map from the device, or {} if absent."""
    xml = _pull_device_prefs_xml()
    return _extract_prefs_key(xml, PREFS_COORDS_KEY) if xml else {}


def _xml_escape(s: str) -> str:
    return (
        s.replace("&", "&amp;").replace('"', "&quot;").replace("<", "&lt;").replace(">", "&gt;")
    )


def build_prefs_xml(daily_map: dict[str, str], coords_map: dict[str, str]) -> str:
    """Build a SharedPreferences XML carrying BOTH the labels and the coords."""
    labels_payload = json.dumps(daily_map, ensure_ascii=False, separators=(",", ":"))
    coords_payload = json.dumps(coords_map, ensure_ascii=False, separators=(",", ":"))
    return (
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n"
        "<map>\n"
        f'    <string name="{PREFS_KEY}">{_xml_escape(labels_payload)}</string>\n'
        f'    <string name="{PREFS_COORDS_KEY}">{_xml_escape(coords_payload)}</string>\n'
        "</map>\n"
    )


def push_prefs(xml_path: Path) -> None:
    """Push the prefs XML to the app's private dir via run-as."""
    # Stage on /data/local/tmp first (run-as can't read external files directly
    # on every Android version).
    remote_tmp = f"/data/local/tmp/{PREFS_FILE}"
    subprocess.run(["adb", "push", str(xml_path), remote_tmp], check=True)

    # Move into the app's prefs dir using run-as, then fix permissions.
    # NOTE: `adb shell` re-tokenises the remote command on whitespace, so the
    # whole shell line must be passed as a SINGLE argv token after `run-as
    # PACKAGE sh -c`.  We achieve that by joining the entire pipeline into one
    # string and quoting it with shlex.quote.
    remote_cmd = (
        f"cp {remote_tmp} shared_prefs/{PREFS_FILE} && "
        f"chmod 660 shared_prefs/{PREFS_FILE}"
    )
    subprocess.run(
        [
            "adb", "shell",
            f"run-as {PACKAGE} sh -c {shlex.quote(remote_cmd)}",
        ],
        check=True,
    )
    subprocess.run(["adb", "shell", "rm", remote_tmp], check=False)

    # Force-stop so the next launch re-reads SharedPreferences.
    subprocess.run(["adb", "shell", "am", "force-stop", PACKAGE], check=False)


# --- Main --------------------------------------------------------------------


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument(
        "--timeline",
        required=True,
        type=Path,
        help="Path to Google Maps Timeline export (Timeline.json).",
    )
    ap.add_argument("--dry-run", action="store_true", help="Skip adb push.")
    ap.add_argument(
        "--no-merge-device",
        action="store_true",
        help="Ignore the prefs currently on the device when merging.",
    )
    ap.add_argument(
        "--overwrite-existing",
        action="store_true",
        help=(
            "Allow timeline-derived entries to overwrite existing entries on the "
            "device.  Default behaviour PRESERVES anything already set."
        ),
    )
    args = ap.parse_args()

    OUT_DIR.mkdir(parents=True, exist_ok=True)

    print(f"[1/5] Loading timeline from {args.timeline} ...")
    timeline = json.loads(args.timeline.read_text())
    seg_count = len(timeline.get("semanticSegments", []) or [])
    raw_count = len(timeline.get("rawSignals", []) or [])
    print(f"      semanticSegments={seg_count}  rawSignals={raw_count}")

    print("[2/5] Choosing one representative coordinate per date ...")
    daily_coords = extract_daily_coords(timeline)
    print(f"      {len(daily_coords)} dates with coordinates")

    # Group dates by their rounded coordinate so we only geocode unique points.
    coord_to_dates: dict[tuple[float, float], list[str]] = defaultdict(list)
    for d, coord in daily_coords.items():
        coord_to_dates[coord].append(d)
    print(f"      {len(coord_to_dates)} unique coordinate clusters to geocode")

    print("[3/5] Reverse-geocoding via Nominatim (cached) ...")
    cache = load_cache()
    coord_to_label: dict[tuple[float, float], str] = {}
    n = len(coord_to_dates)
    for idx, (coord, dates) in enumerate(sorted(coord_to_dates.items()), start=1):
        lat, lon = coord
        result = reverse_geocode(lat, lon, cache)
        label = format_address(result)
        if label:
            coord_to_label[coord] = label
            print(f"      ({idx}/{n}) {coord} → {label}  [{len(dates)} dates]")
        else:
            print(f"      ({idx}/{n}) {coord} → <no label>  [{len(dates)} dates]")
    save_cache(cache)

    derived: dict[str, str] = {}
    derived_coords: dict[str, str] = {}
    for d, coord in daily_coords.items():
        label = coord_to_label.get(coord)
        if label:
            derived[d] = label
            # Always emit the rounded coords too — these power the world-map
            # screen.  Format as "lat,lon" to match LocationRepository.kt.
            derived_coords[d] = f"{coord[0]},{coord[1]}"
    DERIVED_FILE.write_text(json.dumps(derived, indent=2, ensure_ascii=False, sort_keys=True))
    print(f"      {len(derived)} dated labels written to {DERIVED_FILE}")

    print("[4/5] Merging with on-device prefs ...")
    if args.no_merge_device:
        existing: dict[str, str] = {}
        existing_coords: dict[str, str] = {}
    else:
        existing = pull_device_prefs()
        existing_coords = pull_device_coords()
    print(f"      device currently has {len(existing)} label entries, {len(existing_coords)} coord entries")

    if args.overwrite_existing:
        merged = {**existing, **derived}
        merged_coords = {**existing_coords, **derived_coords}
        # ^ Wait — that ORDER means timeline wins if both exist? No: rightmost
        # wins in dict spread, so {**existing, **derived} → derived wins.
        # That's what overwrite-existing requests.
    else:
        # Preserve existing entries; only add timeline data for dates the
        # device doesn't already have.
        merged = {**derived, **existing}
        merged_coords = {**derived_coords, **existing_coords}

    # Sort newest-first to match what we saw on the device (makes diffs nice).
    merged = dict(sorted(merged.items(), reverse=True))
    merged_coords = dict(sorted(merged_coords.items(), reverse=True))
    MERGED_FILE.write_text(json.dumps(merged, indent=2, ensure_ascii=False))
    print(
        f"      merged → {len(merged)} label entries, "
        f"{len(merged_coords)} coord entries  ({MERGED_FILE})"
    )

    print("[5/5] Building prefs XML ...")
    xml = build_prefs_xml(merged, merged_coords)
    PREFS_LOCAL.write_text(xml)
    print(f"      wrote {PREFS_LOCAL}")

    if args.dry_run:
        print("--dry-run: skipping adb push.")
        return 0

    print("       pushing to device ...")
    push_prefs(PREFS_LOCAL)
    print("       done.  App was force-stopped; relaunch to see new data.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
