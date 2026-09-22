#!/usr/bin/env python3
"""
TEMPORARY SCRIPT: Extract meditation sessions and minutes from a Garmin
GDPR ZIP export.

Garmin records meditation / breathwork as activities inside the ZIP's
DI-Connect-Fitness/_summarizedActivities.json file(s). Each activity has an
activityType string (e.g. "meditation", "breath_work") and a duration in
milliseconds. This script finds those activities and aggregates:

  - sessions count per day
  - total minutes per day

Usage:
    python3 garmin_meditation_extract.py <path_to_garmin_export.zip> [options]

Options:
    --output PATH        Output JSON file (default: meditation_output.json)
    --include-yoga       Also include yoga/mindfulness activities
    --types KEY1,KEY2    Override the meditation activity-type keys to match
                         (comma-separated, e.g. "meditation,breath_work")
"""

import sys
import json
import zipfile
import argparse
from pathlib import Path
from datetime import datetime, timezone
from collections import defaultdict
from typing import Dict, Any, Optional, Set, List


# Default activity-type strings that count as meditation.
# In the GDPR ZIP, activityType is a plain lowercase string.
DEFAULT_MEDITATION_TYPES: Set[str] = {
    "meditation",
    "breath_work",
    "breathwork",
}

# Activity types that are meditation-adjacent (printed for review, not included
# unless --include-yoga is passed).
YOGA_TYPES: Set[str] = {
    "yoga",
    "mindfulness",
    "guided_breathing",
}


# --------------------------------------------------------------------------- #
# Helpers
# --------------------------------------------------------------------------- #
def extract_local_date(entry: dict) -> Optional[str]:
    """Extract YYYY-MM-DD from an activity's startTimeLocal.

    In the GDPR ZIP, startTimeLocal is a Unix epoch (ms) that already encodes
    the device's wall-clock time. Reading it as UTC reproduces the correct
    local calendar date (same trick garmin_import.py relies on).
    """
    start_time_local = entry.get("startTimeLocal")
    if start_time_local:
        ts_seconds = start_time_local / 1000 if start_time_local > 2e9 else start_time_local
        dt = datetime.fromtimestamp(ts_seconds, tz=timezone.utc)
        return dt.strftime("%Y-%m-%d")
    # Fallbacks
    for key in ("startTimeGMT", "beginTimestamp"):
        val = entry.get(key)
        if isinstance(val, (int, float)) and val:
            ts = val / 1000 if val > 2e9 else val
            return datetime.fromtimestamp(ts, tz=timezone.utc).date().isoformat()
    return None


def get_type_key(entry: dict) -> str:
    """Extract the activityType string (lowercased) from an activity.

    In the GDPR ZIP, activityType is a plain string (e.g. "running").
    """
    atype = entry.get("activityType")
    if isinstance(atype, str):
        return atype.lower()
    if isinstance(atype, dict):
        return (atype.get("typeKey") or "").lower()
    return ""


def get_duration_seconds(entry: dict) -> float:
    """Get activity duration in seconds.

    GDPR ZIP: "duration" is in milliseconds (no durationInSeconds field).
    """
    d = entry.get("durationInSeconds")
    if d and d > 0:
        return float(d)
    d_ms = entry.get("duration")
    if d_ms and d_ms > 0:
        return float(d_ms) / 1000.0
    return 0.0


# --------------------------------------------------------------------------- #
# Main
# --------------------------------------------------------------------------- #
def main():
    parser = argparse.ArgumentParser(
        description="Extract meditation sessions and minutes from a Garmin GDPR ZIP export."
    )
    parser.add_argument("zip_path", help="Path to the Garmin GDPR export .zip file")
    parser.add_argument("--output", default="meditation_output.json",
                        help="Output JSON file (default: meditation_output.json)")
    parser.add_argument("--include-yoga", action="store_true",
                        help="Also include yoga/mindfulness activities")
    parser.add_argument("--types", default=None,
                        help="Override meditation type keys (comma-separated)")
    args = parser.parse_args()

    # Build the set of type keys to match
    if args.types:
        match_types = {t.strip().lower() for t in args.types.split(",") if t.strip()}
    else:
        match_types = set(DEFAULT_MEDITATION_TYPES)
    if args.include_yoga:
        match_types |= YOGA_TYPES

    zip_path = Path(args.zip_path)
    if not zip_path.exists():
        print(f"Error: ZIP file not found: {zip_path}")
        sys.exit(1)

    print(f"Processing Garmin export: {zip_path}")
    print(f"Meditation type keys to match: {sorted(match_types)}")
    print()

    # ------------------------------------------------------------------ #
    # 1. Scan the ZIP for relevant files
    # ------------------------------------------------------------------ #
    all_activities: List[dict] = []
    meditation_related_files: List[str] = []

    with zipfile.ZipFile(zip_path, "r") as z:
        file_names = z.namelist()

        # Quick scan: flag any files whose name mentions meditation/breathwork
        for fn in file_names:
            fn_lower = fn.lower()
            if any(kw in fn_lower for kw in ("meditat", "breath", "mindful")):
                meditation_related_files.append(fn)

        # Process _summarizedActivities.json files (where activities live)
        activity_files = [
            fn for fn in file_names
            if "summarizedactivities" in fn.lower()
            and "di-connect-fitness" in fn.lower()
            and fn.lower().endswith(".json")
        ]

        print(f"Found {len(activity_files)} summarized-activities file(s):")
        for fn in activity_files:
            print(f"  • {fn}")

        for fn in activity_files:
            try:
                data = json.loads(z.read(fn).decode("utf-8"))
            except Exception as e:
                print(f"  Warning: Failed to parse {fn}: {e}")
                continue

            # Unwrap the Garmin container structure
            if isinstance(data, list) and len(data) > 0:
                activity_array = data[0].get("summarizedActivitiesExport", [])
            elif isinstance(data, dict):
                activity_array = (
                    data.get("summarizedActivitiesExport")
                    or data.get("activities")
                    or data.get("activityList")
                    or []
                )
            else:
                activity_array = []

            if isinstance(activity_array, list):
                all_activities.extend(activity_array)

    print(f"\nTotal activities found in ZIP: {len(all_activities)}")

    if meditation_related_files:
        print(f"\nFiles with meditation/breathwork in the name (for reference):")
        for fn in meditation_related_files:
            print(f"  ⚠ {fn}")

    # ------------------------------------------------------------------ #
    # 2. Print all activity types for verification
    # ------------------------------------------------------------------ #
    type_counts: Dict[str, int] = defaultdict(int)
    for act in all_activities:
        type_counts[get_type_key(act)] += 1

    print("\n── All activity types found ──────────────────────────────────")
    for tk, count in sorted(type_counts.items(), key=lambda x: -x[1]):
        marker = ""
        if tk in match_types:
            marker = "  ◆ MATCHED"
        elif tk in YOGA_TYPES:
            marker = "  ◇ yoga (use --include-yoga)"
        print(f"  {tk:45s} {count:5d}{marker}")
    print("─────────────────────────────────────────────────────────────\n")

    # ------------------------------------------------------------------ #
    # 3. Filter for meditation activities
    # ------------------------------------------------------------------ #
    meditation_acts = [a for a in all_activities if get_type_key(a) in match_types]
    print(f"Meditation/breathwork activities found: {len(meditation_acts)}")

    if not meditation_acts:
        print(
            "\nNo meditation activities found with the current type keys.\n"
            "Check the activity-type list above — if meditation is logged under\n"
            "a different key, re-run with --types=<that_key>."
        )

    # ------------------------------------------------------------------ #
    # 4. Aggregate per day + build session detail
    # ------------------------------------------------------------------ #
    daily: Dict[str, Dict[str, int]] = defaultdict(lambda: {"sessions": 0, "minutes": 0})
    sessions_detail = []
    seen_ids: Set = set()

    for act in meditation_acts:
        # Deduplicate by activityId
        act_id = act.get("activityId")
        if act_id and act_id in seen_ids:
            continue
        if act_id:
            seen_ids.add(act_id)

        date_str = extract_local_date(act)
        if not date_str:
            continue

        duration_s = get_duration_seconds(act)
        minutes = int(duration_s // 60)

        daily[date_str]["sessions"] += 1
        daily[date_str]["minutes"] += minutes

        sessions_detail.append({
            "date": date_str,
            "duration_seconds": int(duration_s),
            "duration_minutes": minutes,
            "activity_type": get_type_key(act),
            "activity_name": act.get("activityName", ""),
            "activity_id": act_id,
        })

    # Sort outputs
    daily_sorted = dict(sorted(daily.items()))
    sessions_detail.sort(key=lambda x: (x["date"], x["activity_id"] or 0))

    total_sessions = sum(d["sessions"] for d in daily.values())
    total_minutes = sum(d["minutes"] for d in daily.values())

    # ------------------------------------------------------------------ #
    # 5. Write output JSON
    # ------------------------------------------------------------------ #
    output = {
        "metadata": {
            "description": "Meditation sessions and minutes extracted from Garmin GDPR ZIP export",
            "source_zip": str(zip_path),
            "generated": datetime.now(timezone.utc).isoformat(),
            "activity_types_matched": sorted(match_types),
            "total_activities_scanned": len(all_activities),
            "total_meditation_sessions": total_sessions,
            "total_meditation_minutes": total_minutes,
            "days_with_meditation": len(daily),
        },
        "daily": daily_sorted,
        "sessions_detail": sessions_detail,
    }

    output_path = Path(args.output)
    with open(output_path, "w") as f:
        json.dump(output, f, indent=2)

    # ------------------------------------------------------------------ #
    # 6. Summary
    # ------------------------------------------------------------------ #
    print()
    print("═══════════════════════════════════════════════════════════════")
    print(f"  Output written to: {output_path.resolve()}")
    print(f"  Total meditation sessions: {total_sessions}")
    print(f"  Total meditation minutes:  {total_minutes}")
    print(f"  Days with meditation:      {len(daily)}")
    if daily_sorted:
        first_date = next(iter(daily_sorted))
        last_date = next(reversed(daily_sorted))
        print(f"  Date range of data:        {first_date} → {last_date}")
    print("═══════════════════════════════════════════════════════════════")

    # Preview
    if daily_sorted:
        items = list(daily_sorted.items())
        print("\nPreview (first 5 days):")
        for d, v in items[:5]:
            print(f"  {d}  sessions={v['sessions']}  minutes={v['minutes']}")
        if len(items) > 10:
            print("  ...")
            print("Preview (last 5 days):")
            for d, v in items[-5:]:
                print(f"  {d}  sessions={v['sessions']}  minutes={v['minutes']}")


if __name__ == "__main__":
    main()
