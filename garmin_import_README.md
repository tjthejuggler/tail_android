# Garmin Historic Data Import Script

Processes a Garmin GDPR ZIP export and generates a JSON file compatible with the Tail Android app's Garmin cache format.

## Features

This script extracts the following metrics from Garmin exports:

- **VO2 Max** - From `DI-Connect-Metrics/ActivityVo2Max.json` or `.FIT` files (message type 140)
- **Fitness Age** - From `DI-Connect-Wellness/*fitnessAgeData.json`
- **Resting Heart Rate** - From `DI-Connect-Aggregator/UDSFile*.json`
- **HRV Last Night** - From `DI-Connect-Wellness/*HRV*.json` or `.FIT` files
- **HRV Weekly Average** - Computed as 7-day rolling average from last night HRV
- **Sleep Score** - From `DI-Connect-Wellness/*_sleepData.json`
- **Sleep Duration & Stages** - Deep, light, REM, awake minutes
- **Respiration Rate** - From sleep data
- **Sleep Stress** - From sleep data
- **Steps** - From `DI-Connect-Aggregator/UDSFile*.json`
- **Distance** - Total distance in meters
- **Calories** - Total kilocalories burned
- **Active Minutes** - Moderate + vigorous intensity minutes
- **Run/Bike/Swim Minutes** - Per-sport activity minutes from `_summarizedActivities.json` durations, categorised by activity type
- **Floors Climbed** - From elevation data
- **Min/Max Heart Rate** - Daily heart rate extremes
- **Stress Level** - From `StressDetailSummary` or `GarminStressDetailSummary` files
- **Altitude Ascent** - From `DI-Connect-Fitness/*_summarizedActivities.json`

## Installation

### Requirements

- Python 3.8+
- `fitparse` library (for parsing binary `.FIT` files)

### Install Dependencies

The script uses a virtual environment for dependency management:

```bash
# Using the project's venv (already set up)
venv/bin/python3 -m pip install fitparse

# Or install globally with pipx
pipx install fitparse
```

## Usage

```bash
# Using the project's venv
venv/bin/python3 garmin_import.py <path_to_garmin_export.zip> [output.json]

# Or with system Python (if fitparse is installed globally)
python3 garmin_import.py <path_to_garmin_export.zip> [output.json]
```

### Example

```bash
python garmin_import.py garmin_export.zip garmin_import.json
```

If no output file is specified, the script defaults to `garmin_import.json`.

## Output Format

The script generates a JSON file with the following structure:

```json
{
  "VO2_MAX": {"2024-01-01": 45, "2024-01-02": 46, ...},
  "FITNESS_AGE": {"2024-01-01": 32, ...},
  "RESTING_HR": {"2024-01-01": 58, ...},
  "HRV_LAST_NIGHT": {"2024-01-01": 65, ...},
  "HRV_WEEKLY_AVG": {"2024-01-01": 67, ...},
  "SLEEP_SCORE": {"2024-01-01": 85, ...},
  "SLEEP_DURATION_MINUTES": {"2024-01-01": 480, ...},
  "DEEP_SLEEP_MINUTES": {"2024-01-01": 90, ...},
  "LIGHT_SLEEP_MINUTES": {"2024-01-01": 240, ...},
  "REM_SLEEP_MINUTES": {"2024-01-01": 120, ...},
  "AWAKE_MINUTES": {"2024-01-01": 30, ...},
  "RESPIRATION_RATE": {"2024-01-01": 14, ...},
  "SLEEP_STRESS": {"2024-01-01": 25, ...},
  "STEPS": {"2024-01-01": 10000, ...},
  "DISTANCE_METERS": {"2024-01-01": 8000, ...},
  "CALORIES": {"2024-01-01": 2200, ...},
  "ACTIVE_MINUTES": {"2024-01-01": 45, ...},
  "RUN_MINUTES": {"2024-01-01": 30, ...},
  "BIKE_MINUTES": {"2024-01-01": 60, ...},
  "SWIM_MINUTES": {"2024-01-01": 20, ...},
  "FLOORS_CLIMBED": {"2024-01-01": 12, ...},
  "MIN_HR": {"2024-01-01": 52, ...},
  "MAX_HR": {"2024-01-01": 145, ...},
  "STRESS_LEVEL": {"2024-01-01": 35, ...},
  "ALTITUDE_ASCENT": {"2024-01-01": 150, ...},
  "ACTIVITY_START_TIMES": {
    "RUN_MINUTES": {"2024-01-01": "07:12:33", ...},
    "BIKE_MINUTES": {"2024-01-01": "18:45:00", ...},
    "SWIM_MINUTES": {"2024-01-01": "06:30:00", ...}
  }
}
```

### ACTIVITY_START_TIMES

The `ACTIVITY_START_TIMES` section maps each sport to the **earliest
device-local start time-of-day** ("HH:MM:SS") per day, extracted from
`_summarizedActivities.json` `startTimeLocal` (with a GMT/begin-timestamp
fallback). The app's Garmin JSON import merges it into its
`activity_times.json` cache, which the schedule timeline uses to place
historic run/bike/swim blocks at their real watch start time. JSONs
compiled with older versions of this script lack this section — recompile
from the GDPR ZIP to obtain it.

## Garmin Export Structure

The script processes files from the following directories in your Garmin GDPR export:

- `DI-Connect-Wellness/` - Sleep, HRV, fitness age data
- `DI-Connect-Aggregator/` - Daily metrics (RHR, steps, distance, etc.)
- `DI-Connect-Metrics/` - VO2 Max data
- `DI-Connect-Fitness/` - Activity data (altitude ascent)
- `DI-Connect-Uploaded-Files/` - Binary `.FIT` files (VO2 Max, HRV_STATUS)

## Timezone Handling

The script uses **Europe/Dublin** as the default timezone for converting Garmin timestamps. You can modify the `TARGET_TZ` constant in the script to use your local timezone:

```python
TARGET_TZ = ZoneInfo("America/New_York")  # Example: Change to your timezone
```

## Troubleshooting

### Missing Metrics

If certain metrics are missing from the output:

1. **VO2 Max**: Check if `ActivityVo2Max.json` exists in `DI-Connect-Metrics/`. If not, the script will try to extract from `.FIT` files. Note: `.FIT` files are often in separate ZIP parts (_2.zip, _3.zip) for large exports.
2. **HRV**: Check for `GarminHrvSummary_*.json` files or `.FIT` files with HRV_STATUS data. The script now supports both CalendarDate and epoch timestamp formats.
3. **Stress Level**: Look for stress JSON files in `DI-Connect-Wellness/`. The script now calculates daily averages from 3-minute buckets when legacy keys are missing.
4. **Altitude Ascent**: Requires activity data in `DI-Connect-Fitness/*_summarizedActivities.json`. Supports both ISO timestamps and Unix epoch timestamps.
5. **Multi-Part Exports**: Large Garmin exports are split into multiple ZIP files. Run the script against each part to get complete data.

### FIT File Parsing

If you see a warning about `fitparse` not being available, install it with:

```bash
pip install fitparse
```

Without `fitparse`, the script will still process JSON files but won't be able to extract data from binary `.FIT` files.

## Version History

- **2026-06-22** - Garmin investigation + future-date fix:
  - **Future-date requests (Android)**: `GarminRepository.fetchMonthData`
    looped `1..daysInMonth` for every month including the current one, so it
    requested days that haven't happened yet (the "next 7 days into the future"
    requests). The day loop is now capped at today for the current month; past
    months are unaffected.
  - **"garmin value" showing "1" on some days (display glitch, fixed)**: The
    generated `garmin_import.json` is correct (verified: 2026-05-15 = 12,366
    steps; zero days have a literal 1-step total), so this was never a data bug.
    The "garmin value" in the habit detail panel was an editable
    `OutlinedTextField` with a no-op `onValueChange` for Garmin-linked habits.
    A controlled `TextField` keeps its own internal text buffer from first
    composition; when `garminMonthlyData` arrived/updated asynchronously the
    field could latch a stale value (e.g. "1") and never refresh — typing any
    digit forced the controlled path and snapped it to the correct number.
    Since this value is read-only by design, it is now rendered as a plain
    `Text` label (HabitGridScreen.kt), which always reflects the live derived
    value. The editable field remains only for the divider "true value" case.
  - **Note on rate limiting**: A 7-day proxy refetch is safe — the proxy
    resumes from saved OAuth tokens (`fetch_data.py`) and performs no login, so
    it never triggers Garmin's login throttle; a polite 1 s delay separates
    calls and a 15-minute interval gates whole runs (bypassable with `force`).
    The ZIP/JSON import is retained as the deep-history source because a full
    multi-year proxy backlog would be thousands of reads and risk 429s.

- **2026-06-20 (later)** - Distance display and activity date fixes:
  - **Activity date bug**: Activities were assigned to the wrong calendar date
    because GMT/begin timestamps were converted to `TARGET_TZ` (Dublin).
    Late-night Eastern activities (e.g. 22:07 EDT) therefore rolled over
    to the next day. Activity dates now derive from `startTimeLocal` (the
    device's wall-clock epoch), read as UTC so the local calendar date is
    preserved regardless of the machine's timezone. GMT/begin timestamps
    remain a fallback only.
  - **Verified**: 2020-07-16 ascent now 1256 m and 2020-07-17 now 961 m (the
    two late-night July 16th activities no longer leak into July 17th).
  - **Distance display**: The Android app now displays Garmin distance as
    whole-number kilometres (e.g. `12 km`) instead of metres, via
    `GarminType.formatDisplayValue`. This applies to the habit square
    (all-time high), the detail panel "garmin value", and the graphs/stats
    popup (day/week/month/year rolling averages). Stored/imported values stay
    in metres.

- **2026-06-20** - Fixed critical bug:
  - Altitude Ascent was incorrectly treating Garmin's centimeter values as meters (100x inflation)
  - Added proper unit conversion: elevationGain (cm) → meters
  - Created diagnostic script `diagnose_ascent_data.py` for troubleshooting

- **2026-06-19** - Added support for:
  - Stress Level extraction from StressDetailSummary files
  - Altitude Ascent from activity summaries
  - FIT file parsing for VO2 Max (message type 140)
  - FIT file parsing for HRV_STATUS data