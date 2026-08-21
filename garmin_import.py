#!/usr/bin/env python3
"""
Garmin Historic Data Import Script

Processes a Garmin GDPR ZIP export and generates a JSON file compatible with
the Tail Android app's Garmin cache format.

This script extracts all available daily metrics from the ZIP archive and
outputs them in a format that can be imported directly into the app.

Supported Metrics:
    - VO2_MAX: From ActivityVo2Max.json or .FIT files (message type 140)
    - FITNESS_AGE: From fitnessAgeData.json
    - RESTING_HR: From UDSFile daily metrics
    - HRV_LAST_NIGHT: From HRV JSON files or .FIT files (HRV_STATUS)
    - HRV_WEEKLY_AVG: Computed 7-day rolling average
    - SLEEP_SCORE: From sleepData.json
    - SLEEP_DURATION_MINUTES: Total sleep time
    - DEEP_SLEEP_MINUTES, LIGHT_SLEEP_MINUTES, REM_SLEEP_MINUTES, AWAKE_MINUTES
    - RESPIRATION_RATE: Average breathing rate during sleep
    - SLEEP_STRESS: Average stress during sleep
    - STEPS: Daily step count
    - DISTANCE_METERS: Total distance traveled
    - CALORIES: Total kilocalories burned
    - ACTIVE_MINUTES: Moderate + vigorous intensity minutes
    - RUN_MINUTES, BIKE_MINUTES, SWIM_MINUTES: Per-sport activity minutes
      (from _summarizedActivities.json durations, categorised by activityType.typeKey)
    - ACTIVITY_START_TIMES: Earliest per-sport start time-of-day per day
      (from _summarizedActivities.json startTimeLocal); the app's Garmin JSON
      import merges it into its activity-times cache so historic activity
      blocks are placed at their real watch start time
    - FLOORS_CLIMBED: Elevation climbed in meters (from floorsAscendedInMeters)
    - MIN_HR, MAX_HR: Daily heart rate extremes
    - STRESS_LEVEL: From StressDetailSummary files
    - ALTITUDE_ASCENT_METERS: From _summarizedActivities.json (elevationGain in cm, converted to m)

Usage:
    python garmin_import.py <path_to_garmin_export.zip> [output.json]

Requirements:
    pip install fitparse  # Required for .FIT file parsing

See garmin_import_README.md for detailed documentation.
"""

import zipfile
import json
import sys
from datetime import datetime
from zoneinfo import ZoneInfo
from typing import Dict, Any, Optional
from collections import defaultdict
from pathlib import Path

# Try to import fitparse for .FIT file parsing
try:
    from fitparse import FitFile
    FITPARSE_AVAILABLE = True
except ImportError:
    FITPARSE_AVAILABLE = False
    print("Warning: fitparse library not available. Install with: pip install fitparse")

# Configuration
TARGET_TZ = ZoneInfo("Europe/Dublin")  # Using Dublin as default, can be changed


def categorise_activity_type(type_key: str) -> Optional[str]:
    """Map a Garmin activityType.typeKey to a minute-bucket key, or None.

    Returns "RUN_MINUTES", "BIKE_MINUTES", "SWIM_MINUTES" (matching GarminType
    enum names) or None. Substring matching covers the many Garmin sub-types
    (trail_running, mountain_biking, open_water_swimming, virtual_run, etc.).
    Kept in sync with garmin_proxy/fetch_data.py:_categorise_activity.
    """
    tk = (type_key or "").lower()
    if "swim" in tk:
        return "SWIM_MINUTES"
    if "cycl" in tk or "bik" in tk or tk in ("virtual_ride", "bmx", "e_bike"):
        return "BIKE_MINUTES"
    if "run" in tk:
        return "RUN_MINUTES"
    return None


class GarminDataExtractor:
    """Extracts and normalizes Garmin health data from GDPR export ZIP."""
    
    def __init__(self, zip_path: str):
        self.zip_path = zip_path
        self.data: Dict[str, Dict[str, Any]] = defaultdict(dict)
        self.hrv_values: Dict[str, int] = {}  # Store for weekly baseline calculation
        self.processed_activity_ids = set()  # Track processed activities to avoid duplicates
        # Accumulate raw seconds per sport for run/bike/swim minute buckets.
        # Finalised to whole minutes in finalize_activity_minutes().
        self.activity_seconds: Dict[str, Dict[str, float]] = {
            "RUN_MINUTES": {},
            "BIKE_MINUTES": {},
            "SWIM_MINUTES": {},
        }
        # Earliest device-local start time-of-day ("HH:MM:SS") per sport per
        # date, emitted as the JSON's ACTIVITY_START_TIMES section.
        self.activity_start_times: Dict[str, Dict[str, str]] = {
            "RUN_MINUTES": {},
            "BIKE_MINUTES": {},
            "SWIM_MINUTES": {},
        }
    
    def extract_local_datetime(self, timestamp_raw: Any) -> Optional[datetime]:
        """Convert Garmin timestamp (ISO or Epoch) to a local datetime."""
        if not timestamp_raw:
            return None
        try:
            # Handle Unix Epoch
            if isinstance(timestamp_raw, (int, float)):
                # Garmin sometimes uses milliseconds, sometimes seconds
                ts_seconds = timestamp_raw / 1000 if timestamp_raw > 2e9 else timestamp_raw
                # For activity data, startTimeGMT is in UTC, startTimeLocal is in device local time
                # Both are Unix timestamps, so we treat them as UTC and convert to TARGET_TZ
                dt_utc = datetime.fromtimestamp(ts_seconds, tz=ZoneInfo("UTC"))
                return dt_utc.astimezone(TARGET_TZ)
            # Handle String formats
            timestamp_str = str(timestamp_raw).strip()
            if " " in timestamp_str:
                timestamp_str = timestamp_str.replace(" ", "T")

            ts = timestamp_str.split('.')[0]
            dt = datetime.strptime(ts, "%Y-%m-%dT%H:%M:%S")
            # For ISO strings, assume they're already in local time
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=TARGET_TZ)
            else:
                dt = dt.astimezone(TARGET_TZ)
            return dt
        except Exception:
            return None

    def extract_local_date(self, timestamp_raw: Any) -> Optional[str]:
        """Convert Garmin timestamp (ISO or Epoch) to local YYYY-MM-DD format."""
        dt = self.extract_local_datetime(timestamp_raw)
        return dt.strftime("%Y-%m-%d") if dt else None
    
    def process_sleep_data(self, file_name: str, z: zipfile.ZipFile):
        """Process sleep data from DI-Connect-Wellness/*_sleepData.json files."""
        try:
            data = json.loads(z.read(file_name).decode('utf-8'))
            sleep_events = data if isinstance(data, list) else data.get('sleepDatas', [])
            
            for event in sleep_events:
                end_time = event.get("sleepEndTimestampGMT")
                if not end_time:
                    continue
                
                date = self.extract_local_date(end_time)
                if not date:
                    continue
                
                # Extract sleep score from nested structure
                sleep_scores = event.get("sleepScores", {})
                sleep_score = sleep_scores.get("overallScore")
                if sleep_score and sleep_score > 0:
                    self.data["SLEEP_SCORE"][date] = int(sleep_score)
                
                # Extract sleep stages (in seconds, convert to minutes)
                deep = event.get("deepSleepSeconds", 0)
                light = event.get("lightSleepSeconds", 0)
                rem = event.get("remSleepSeconds", 0)

                # Sleep length: prefer the explicit duration field, but the GDPR
                # export's sleep events don't carry durationInSeconds — fall back
                # to the sum of the asleep stages (deep + light + REM), which is
                # exactly what Garmin's live API reports as sleepTimeSeconds.
                duration = event.get("durationInSeconds", 0)
                if not duration:
                    duration = deep + light + rem
                if duration > 0:
                    self.data["SLEEP_DURATION_MINUTES"][date] = duration // 60

                if deep > 0:
                    self.data["DEEP_SLEEP_MINUTES"][date] = deep // 60

                if light > 0:
                    self.data["LIGHT_SLEEP_MINUTES"][date] = light // 60

                if rem > 0:
                    self.data["REM_SLEEP_MINUTES"][date] = rem // 60
                
                awake = event.get("awakeSleepSeconds", 0)
                if awake > 0:
                    self.data["AWAKE_MINUTES"][date] = awake // 60
                
                # Extract average respiration (breathing rate)
                avg_resp = event.get("averageRespiration")
                if avg_resp and avg_resp > 0:
                    self.data["RESPIRATION_RATE"][date] = int(avg_resp)
                
                # Extract sleep stress
                sleep_stress = event.get("avgSleepStress")
                if sleep_stress and sleep_stress > 0:
                    self.data["SLEEP_STRESS"][date] = int(sleep_stress)
                
        except Exception as e:
            print(f"Warning: Failed to process sleep data from {file_name}: {e}")
    
    def process_rhr_data(self, file_name: str, z: zipfile.ZipFile):
        """Process daily metrics from DI-Connect-Aggregator/UDSFile*.json files.
        These files contain comprehensive daily health data including steps, distance, calories, floors, etc."""
        try:
            data = json.loads(z.read(file_name).decode('utf-8'))
            entries = data if isinstance(data, list) else []
            
            for entry in entries:
                date = entry.get("calendarDate")
                if not date:
                    continue
                
                # Resting Heart Rate
                rhr = entry.get("restingHeartRate")
                if rhr:
                    self.data["RESTING_HR"][date] = int(rhr)
                
                # Steps
                steps = entry.get("totalSteps")
                if steps and steps > 0:
                    self.data["STEPS"][date] = int(steps)
                
                # Distance (in meters)
                distance = entry.get("totalDistanceMeters")
                if distance and distance > 0:
                    self.data["DISTANCE_METERS"][date] = int(distance)
                
                # Calories
                calories = entry.get("totalKilocalories")
                if calories and calories > 0:
                    self.data["CALORIES"][date] = int(calories)
                
                # Active minutes (moderate + vigorous intensity)
                moderate = entry.get("moderateIntensityMinutes", 0)
                vigorous = entry.get("vigorousIntensityMinutes", 0)
                active_minutes = moderate + vigorous
                if active_minutes > 0:
                    self.data["ACTIVE_MINUTES"][date] = int(active_minutes)
                
                # Floors climbed (convert meters to approximate floors: 1 floor ≈ 3 meters)
                floors_meters = entry.get("floorsAscendedInMeters")
                if floors_meters and floors_meters > 0:
                    self.data["FLOORS_CLIMBED"][date] = int(floors_meters)
                
                # Min/Max heart rate
                min_hr = entry.get("minHeartRate")
                if min_hr and min_hr > 0:
                    self.data["MIN_HR"][date] = int(min_hr)
                
                max_hr = entry.get("maxHeartRate")
                if max_hr and max_hr > 0:
                    self.data["MAX_HR"][date] = int(max_hr)
                
                # Stress level (if available)
                stress_data = entry.get("allDayStress", {})
                if isinstance(stress_data, dict):
                    avg_stress = stress_data.get("averageStressLevel")
                    if avg_stress and avg_stress > 0:
                        self.data["STRESS_LEVEL"][date] = int(avg_stress)
        except Exception as e:
            print(f"Warning: Failed to process daily data from {file_name}: {e}")
    
    def process_vo2_data(self, file_name: str, z: zipfile.ZipFile):
        """Process VO2 Max and Fitness Age from DI-Connect-Metrics/*ActivityVo2Max*.json files."""
        try:
            data = json.loads(z.read(file_name).decode('utf-8'))
            entries = data if isinstance(data, list) else data.get('activityVo2MaxList', [])
            
            for entry in entries:
                generic = entry.get("generic", {})
                vo2 = generic.get("vo2MaxValue")
                age = generic.get("fitnessAge")
                timestamp = generic.get("timestampGMT")
                
                if vo2 and timestamp:
                    date = self.extract_local_date(timestamp)
                    if date:
                        self.data["VO2_MAX"][date] = int(vo2)
                
                if age and timestamp:
                    date = self.extract_local_date(timestamp)
                    if date:
                        self.data["FITNESS_AGE"][date] = int(age)
        except Exception as e:
            print(f"Warning: Failed to process VO2 data from {file_name}: {e}")
    
    def process_fitness_age_data(self, file_name: str, z: zipfile.ZipFile):
        """Process Fitness Age from DI-Connect-Wellness/*fitnessAgeData.json files."""
        try:
            data = json.loads(z.read(file_name).decode('utf-8'))
            entries = data if isinstance(data, list) else [data]
            
            for entry in entries:
                # Fitness age data has an asOfDateGmt field
                date_str = entry.get("asOfDateGmt")
                if not date_str:
                    continue
                
                # Extract date from timestamp like "2022-02-21T00:00:00.0"
                try:
                    date = date_str.split('T')[0]
                    bio_age = entry.get("currentBioAge")
                    if bio_age:
                        # Garmin returns fitness age as decimal (e.g., 37.03692930253995)
                        # Store as hundredths of a year (e.g., 3704 for 37.04) to preserve 2 decimal places
                        # while keeping the data structure integer-based for JSON compatibility
                        self.data["FITNESS_AGE"][date] = int(round(float(bio_age) * 100))
                except Exception:
                    pass
        except Exception as e:
            print(f"Warning: Failed to process fitness age data from {file_name}: {e}")
    
    def process_hrv_data(self, file_name: str, z: zipfile.ZipFile):
        """Process HRV data from GarminHrvSummary files."""
        try:
            data = json.loads(z.read(file_name).decode('utf-8'))
            hrv_array = data if isinstance(data, list) else [data]
            
            for entry in hrv_array:
                # Check for CalendarDate first, fall back to timestamp conversion
                date = entry.get("calendarDate", entry.get("CalendarDate"))
                if not date:
                    ts = entry.get("startTimeInSeconds", entry.get("StartTimeInSeconds"))
                    date = self.extract_local_date(ts)
                
                if not date:
                    continue
                
                # Extract using Garmin's newest keys
                hrv_val = entry.get("LastNightAvg", entry.get("lastNightAvg", entry.get("nightlyAverage", entry.get("value"))))
                
                if hrv_val:
                    self.hrv_values[date] = int(hrv_val)
                    self.data["HRV_LAST_NIGHT"][date] = int(hrv_val)
        except Exception as e:
            print(f"Warning: Failed to process HRV data from {file_name}: {e}")
    
    def process_stress_data(self, file_name: str, z: zipfile.ZipFile):
        """Process stress data from StressDetailSummary files."""
        try:
            data = json.loads(z.read(file_name).decode('utf-8'))
            stress_array = data if isinstance(data, list) else [data]
            
            for entry in stress_array:
                date = entry.get("calendarDate", entry.get("CalendarDate"))
                if not date:
                    continue
                
                # Try the legacy keys first
                stress_val = entry.get("averageStressLevel", entry.get("stressLevel", entry.get("avgStress")))
                if stress_val and stress_val > 0:
                    self.data["STRESS_LEVEL"][date] = int(stress_val)
                    continue
                
                # Calculate from the 3-minute buckets if legacy keys are gone
                buckets = entry.get("TimeOffsetStressLevelValues", entry.get("timeOffsetStressLevelValues", {}))
                if isinstance(buckets, dict) and buckets:
                    # Filter out -1 and -2 (unmeasured/active states)
                    valid_scores = [int(v) for v in buckets.values() if int(v) >= 0]
                    if valid_scores:
                        avg_stress = sum(valid_scores) / len(valid_scores)
                        self.data["STRESS_LEVEL"][date] = int(avg_stress)
                        
        except Exception as e:
            print(f"Warning: Failed to process stress data from {file_name}: {e}")
    
    def process_activity_data(self, file_name: str, z: zipfile.ZipFile):
        """Process activity data (altitude ascent, VO2 Max) from _summarizedActivities.json files."""
        try:
            data = json.loads(z.read(file_name).decode('utf-8'))
            
            # Handle different activity data structures
            # Garmin wraps activities in a list with a "summarizedActivitiesExport" key
            if isinstance(data, list) and len(data) > 0:
                activity_array = data[0].get("summarizedActivitiesExport", [])
            elif isinstance(data, dict):
                activity_array = data.get("summarizedActivitiesExport",
                                         data.get("activities",
                                                data.get("activityList", [])))
            else:
                activity_array = []
            
            for entry in activity_array if isinstance(activity_array, list) else [activity_array]:
                # Skip already processed activities (deduplication)
                activity_id = entry.get("activityId")
                if activity_id and activity_id in self.processed_activity_ids:
                    continue
                if activity_id:
                    self.processed_activity_ids.add(activity_id)
                # Determine the activity's calendar date.
                #
                # An activity belongs to the date on which it happened in the
                # *device's* local timezone (e.g. a 22:07 EDT run is July 16th,
                # even though that is already July 17th in UTC/Dublin).
                #
                # Garmin gives us `startTimeLocal`, a Unix epoch that already
                # encodes the device's wall-clock time. Reading it as if it were
                # UTC (no further tz conversion) therefore yields the correct
                # local calendar date. We prefer it over the GMT/begin timestamps
                # precisely to avoid late-night activities being shifted to the
                # next day when converted to TARGET_TZ.
                date = None
                wall_dt = None  # device wall-clock start (for activity start times)
                start_time_local = entry.get("startTimeLocal")
                if start_time_local:
                    ts_seconds = start_time_local / 1000 if start_time_local > 2e9 else start_time_local
                    wall_dt = datetime.fromtimestamp(ts_seconds, tz=ZoneInfo("UTC"))
                    date = wall_dt.strftime("%Y-%m-%d")
                else:
                    # Fallback: GMT/begin timestamps converted to TARGET_TZ.
                    start_time = (entry.get("startTimeGMT") or
                                  entry.get("startTimeGmt") or
                                  entry.get("beginTimestamp") or
                                  entry.get("startTime"))
                    wall_dt = self.extract_local_datetime(start_time)
                    date = wall_dt.strftime("%Y-%m-%d") if wall_dt else None
                if not date:
                    continue
                
                # Extract elevation gain (altitude ascent) - try multiple key variations
                # Garmin stores elevationGain in CENTIMETERS, need to convert to meters
                elevation_gain = (entry.get("elevationGain") or
                                  entry.get("totalElevationGain") or
                                  entry.get("ascent") or
                                  entry.get("elevationGainMeters"))
                if elevation_gain and elevation_gain > 0:
                    # Convert from centimeters to meters (divide by 100)
                    ascent_meters = elevation_gain / 100
                    # Sum up altitude ascent for the day
                    if date in self.data["ALTITUDE_ASCENT_METERS"]:
                        self.data["ALTITUDE_ASCENT_METERS"][date] += int(ascent_meters)
                    else:
                        self.data["ALTITUDE_ASCENT_METERS"][date] = int(ascent_meters)
                
                # Extract VO2 Max from activity data (some activities have this)
                vo2_value = (entry.get("vO2MaxValue") or
                           entry.get("vo2MaxValue") or
                           entry.get("vo2_max"))
                if vo2_value and vo2_value > 0:
                    self.data["VO2_MAX"][date] = int(vo2_value)

                # Categorise activity into run/bike/swim and accumulate duration
                # (seconds). Converted to whole minutes in finalize_activity_minutes().
                #
                # Duration units differ between data sources:
                #   - GDPR ZIP export: "duration" is in milliseconds
                #   - Live Connect API: "durationInSeconds" is in seconds
                # The GDPR export has no durationInSeconds field, so when only
                # "duration" is present we convert ms → s.
                duration_raw = entry.get("durationInSeconds")
                if duration_raw:
                    duration_s = float(duration_raw)
                else:
                    duration_ms = entry.get("duration")
                    duration_s = float(duration_ms) / 1000 if duration_ms else 0.0
                if duration_s > 0:
                    # activityType shape differs between sources:
                    #   - GDPR ZIP export: a plain string (e.g. "running")
                    #   - Live Connect API: a dict with a "typeKey" field
                    atype_raw = entry.get("activityType")
                    if isinstance(atype_raw, dict):
                        type_key = atype_raw.get("typeKey", "")
                    elif isinstance(atype_raw, str):
                        type_key = atype_raw
                    else:
                        type_key = ""
                    cat = categorise_activity_type(type_key)
                    if cat:
                        self.activity_seconds[cat][date] = (
                            self.activity_seconds[cat].get(date, 0) + duration_s
                        )
                        # Earliest start time-of-day per sport+date — the
                        # app places the day's activity block at this time.
                        if wall_dt is not None:
                            tod = wall_dt.strftime("%H:%M:%S")
                            prev = self.activity_start_times[cat].get(date)
                            if prev is None or tod < prev:
                                self.activity_start_times[cat][date] = tod
        except Exception as e:
            print(f"Warning: Failed to process activity data from {file_name}: {e}")
    
    def process_fit_file(self, file_name: str, z: zipfile.ZipFile):
        """Process binary .FIT files for VO2 Max and HRV_STATUS data."""
        if not FITPARSE_AVAILABLE:
            return
        
        try:
            fit_data = z.read(file_name)
            fit_file = FitFile(fit_data)
            
            for message in fit_file:
                # VO2 Max is in message type 140
                if message.mesg_type == 140:
                    vo2_value = message.get('effective_vo2_max') or message.get('vo2_max')
                    timestamp = message.get('timestamp')
                    
                    if vo2_value and timestamp:
                        # Convert timestamp to date
                        try:
                            if isinstance(timestamp, datetime):
                                dt = timestamp
                            else:
                                # Handle Garmin timestamp format
                                dt = datetime.strptime(str(timestamp), "%Y-%m-%dT%H:%M:%S")
                            
                            # Convert to local timezone
                            if dt.tzinfo is None:
                                dt = dt.replace(tzinfo=ZoneInfo("UTC"))
                            dt_local = dt.astimezone(TARGET_TZ)
                            date = dt_local.strftime("%Y-%m-%d")
                            
                            self.data["VO2_MAX"][date] = int(vo2_value)
                        except Exception:
                            pass
                
                # HRV Status data
                elif message.mesg_name == 'hrv_status' or 'hrv' in str(message.mesg_type).lower():
                    hrv_value = message.get('nightly_hrv') or message.get('avg_hrv') or message.get('hrv_value')
                    timestamp = message.get('timestamp')
                    
                    if hrv_value and timestamp:
                        try:
                            if isinstance(timestamp, datetime):
                                dt = timestamp
                            else:
                                dt = datetime.strptime(str(timestamp), "%Y-%m-%dT%H:%M:%S")
                            
                            if dt.tzinfo is None:
                                dt = dt.replace(tzinfo=ZoneInfo("UTC"))
                            dt_local = dt.astimezone(TARGET_TZ)
                            date = dt_local.strftime("%Y-%m-%d")
                            
                            self.hrv_values[date] = int(hrv_value)
                            self.data["HRV_LAST_NIGHT"][date] = int(hrv_value)
                        except Exception:
                            pass
        except Exception as e:
            print(f"Warning: Failed to process FIT file {file_name}: {e}")
    
    def compute_hrv_weekly_baseline(self):
        """Compute 7-day rolling average for HRV."""
        if not self.hrv_values:
            return
        
        sorted_dates = sorted(self.hrv_values.keys())
        
        for i, current_date in enumerate(sorted_dates):
            current_dt = datetime.strptime(current_date, "%Y-%m-%d")
            recent_hrvs = []
            
            # Look back up to 7 days
            for j in range(max(0, i - 6), i + 1):
                lookback_date = sorted_dates[j]
                lookback_dt = datetime.strptime(lookback_date, "%Y-%m-%d")
                if (current_dt - lookback_dt).days <= 7:
                    val = self.hrv_values.get(lookback_date)
                    if val:
                        recent_hrvs.append(val)
            
            if recent_hrvs:
                baseline = sum(recent_hrvs) // len(recent_hrvs)
                self.data["HRV_WEEKLY_AVG"][current_date] = baseline
    
    def process_zip(self):
        """Main processing loop for the ZIP archive."""
        print(f"Processing Garmin export: {self.zip_path}")
        
        with zipfile.ZipFile(self.zip_path, 'r') as z:
            file_count = 0
            for file_name in z.namelist():
                file_count += 1
                
                # Convert to lowercase once for safe, bulletproof matching
                fname_lower = file_name.lower()
                
                # Process sleep data
                if "di-connect-wellness" in fname_lower and "sleepdata" in fname_lower and fname_lower.endswith(".json"):
                    self.process_sleep_data(file_name, z)
                
                # Process RHR data
                elif "di-connect-aggregator" in fname_lower and "udsfile" in fname_lower and fname_lower.endswith(".json"):
                    self.process_rhr_data(file_name, z)
                
                # Process VO2 Max data from JSON
                elif "di-connect-metrics" in fname_lower and "vo2max" in fname_lower and fname_lower.endswith(".json"):
                    self.process_vo2_data(file_name, z)
                
                # Process Fitness Age data
                elif "di-connect-wellness" in fname_lower and "fitnessage" in fname_lower and fname_lower.endswith(".json"):
                    self.process_fitness_age_data(file_name, z)
                
                # Process HRV data from JSON
                elif "di-connect-wellness" in fname_lower and "hrv" in fname_lower and fname_lower.endswith(".json"):
                    self.process_hrv_data(file_name, z)
                
                # Process stress data from StressDetailSummary files
                elif "di-connect-wellness" in fname_lower and "stress" in fname_lower and fname_lower.endswith(".json"):
                    self.process_stress_data(file_name, z)
                
                # Process activity data (altitude ascent) from summarized activities
                elif "di-connect-fitness" in fname_lower and "summarizedactivities" in fname_lower and fname_lower.endswith(".json"):
                    self.process_activity_data(file_name, z)
                
                # Process binary .FIT files for VO2 Max and HRV_STATUS
                elif fname_lower.endswith(".fit"):
                    self.process_fit_file(file_name, z)
        
        print(f"Processed {file_count} files")
        
        # Compute HRV weekly baseline
        self.compute_hrv_weekly_baseline()

        # Convert accumulated activity seconds into whole-minute daily buckets
        self.finalize_activity_minutes()

    def finalize_activity_minutes(self):
        """Convert accumulated activity seconds into whole-minute daily buckets.

        Only days with at least one minute of a sport are emitted, so a rest day
        has no entry (the linked habit is left untouched rather than zeroed).
        """
        for cat, days in self.activity_seconds.items():
            for date_str, secs in days.items():
                mins = int(secs // 60)
                if mins > 0:
                    self.data[cat][date_str] = mins
    
    def get_output(self) -> Dict[str, Dict[str, int]]:
        """Return the extracted data in the app's cache format."""
        return dict(self.data)
    
    def print_summary(self):
        """Print a summary of extracted metrics."""
        print("\n=== Extracted Metrics Summary ===")
        for metric_name, dates in self.data.items():
            if dates:
                sorted_dates = sorted(dates.keys())
                print(f"{metric_name}: {len(dates)} entries from {sorted_dates[0]} to {sorted_dates[-1]}")
        for cat, days in sorted(self.activity_start_times.items()):
            if days:
                print(f"{cat} start times: {len(days)} days")


def main():
    if len(sys.argv) < 2:
        print("Usage: python garmin_import.py <path_to_garmin_export.zip> [output.json]")
        sys.exit(1)
    
    zip_path = sys.argv[1]
    output_path = sys.argv[2] if len(sys.argv) > 2 else "garmin_import.json"
    
    extractor = GarminDataExtractor(zip_path)
    extractor.process_zip()
    extractor.print_summary()
    
    # Write output
    output_data = extractor.get_output()
    # Activity start times ride along in a dedicated section; the app's
    # Garmin JSON import merges them into its activity-times cache so
    # historic run/bike/swim blocks land at their real watch start time.
    start_times = {cat: dict(days) for cat, days in extractor.activity_start_times.items() if days}
    if start_times:
        output_data["ACTIVITY_START_TIMES"] = start_times
    with open(output_path, 'w') as f:
        json.dump(output_data, f, indent=2)
    
    print(f"\n✓ Successfully wrote import data to {output_path}")
    print(f"  Total metrics extracted: {len(output_data)}")
    
    # Count total entries
    total_entries = sum(len(dates) for dates in output_data.values())
    print(f"  Total data points: {total_entries}")


if __name__ == "__main__":
    main()