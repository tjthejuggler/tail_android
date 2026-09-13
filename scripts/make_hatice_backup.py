#!/usr/bin/env python3
"""Build a minimal Tail backup file for Hatice containing ONLY locations.

Hatice's days are the days the "With Someone" habit was logged with
person == "Hatice". The who-log lives in the phone's text-input file:
  /sdcard/Documents/Obsid/noteVault/tail/with_someone.txt
  (JSON map: "YYYY-MM-DD HH:MM:SS" -> person name)

Read-only inputs (never modified):
  * /tmp/with_someone.txt          — copy of the who-log (pulled via
                                     `adb shell cat`, read-only).
  * /tmp/tail_location_prefs.xml   — copy of the phone's
                                     shared_prefs/tail_location_prefs.xml
                                     (pulled via `adb shell run-as ... cat`,
                                     read-only).

Output: hatice_backup.json — a valid BackupBundle JSON with empty settings /
habitsDb / etc., so restoring it into a fresh install only writes the location
maps. Format mirrors BackupManager.readLocationsSection()/applyLocations():
  labels: date -> "City, Region, Country"
  coords: date -> "lat,lon"
"""

import html
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path

WITH_SOMEONE_LOG = Path("/tmp/with_someone.txt")
LOCATION_PREFS = Path("/tmp/tail_location_prefs.xml")
OUT = Path(__file__).resolve().parent.parent / "hatice_backup.json"

PERSON = "hatice"

SCHEMA_VERSION = 1
MAGIC = "tail-backup"


def load_hatice_days() -> list[str]:
    log = json.loads(WITH_SOMEONE_LOG.read_text())
    days = sorted({
        stamp.split(" ")[0]
        for stamp, who in log.items()
        if who.strip().lower() == PERSON
    })
    if not days:
        sys.exit(f"ERROR: no '{PERSON}' days found in {WITH_SOMEONE_LOG} — refusing to build an empty backup")
    return days


def load_location_prefs() -> tuple[dict, dict]:
    xml = LOCATION_PREFS.read_text()

    def section(key: str) -> dict:
        m = re.search(r'<string name="%s">(.*?)</string>' % key, xml, re.S)
        return json.loads(html.unescape(m.group(1))) if m else {}

    return section("daily_locations"), section("daily_coords")


def main() -> None:
    hatice_days = load_hatice_days()
    labels, coords = load_location_prefs()

    sel_labels = {d: labels[d] for d in hatice_days if d in labels}
    sel_coords = {d: coords[d] for d in hatice_days if d in coords}

    missing = [d for d in hatice_days if d not in labels]
    print(f"'With Someone' days: {len(hatice_days)}")
    print(f"  with location label: {len(sel_labels)}")
    print(f"  with coords:         {len(sel_coords)}")
    print(f"  without any location data (skipped): {len(missing)}")
    if missing:
        print(f"  e.g. {missing[:5]}")

    bundle = {
        "schemaVersion": SCHEMA_VERSION,
        "appVersion": "manual-locations-only",
        "exportedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "magic": MAGIC,
        "locations": {
            "labels": dict(sorted(sel_labels.items())),
            "coords": dict(sorted(sel_coords.items())),
            "ignoredCountries": [],
            "ignoredCountriesSeeded": False,
        },
    }

    OUT.write_text(json.dumps(bundle, ensure_ascii=False, indent=2))
    print(f"Wrote {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
