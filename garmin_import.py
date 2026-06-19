#!/usr/bin/env python3
"""
Garmin Historic Data Import Script

Processes a Garmin GDPR ZIP export and generates a JSON file compatible with
the Tail Android app's Garmin cache format.

This script extracts all available daily metrics from the ZIP archive and
outputs them in a format that can be imported directly into the app.

Usage:
    python garmin_import.py <path_to_garmin_export.zip> [output.json]

Output format:
    {
        "VO2_MAX": {"2024-01-01": 45, "2024-01-02": 46, ...},
        "FITNESS_AGE": {"2024-01-01": 32, ...},
        "RESTING_HR": {"2024-01-01": 58, ...},
        "HRV_LAST_NIGHT": {"2024-01-01": 65, ...},
        "HRV_WEEKLY_AVG": {"2024-01-01": 67, ...},
        "SLEEP_SCORE": {"2024-01-01": 85, ...},
        "STEPS": {"2024-01-01": 10000, ...},  // Additional metric
        "ALTITUDE_ASCENT": {"2024-01-01": 150, ...},  // Additional metric
        ...
    }
"""

import zipfile
import json
import sys
from datetime import datetime
from zoneinfo import ZoneInfo
from typing import Dict, Any, Optional
from collections import defaultdict

# Configuration
TARGET_TZ = ZoneInfo("Europe/Dublin")  # Using Dublin as default, can be changed


class GarminDataExtractor:
    """Extracts and normalizes Garmin health data from GDPR export ZIP."""
    
    def __init__(self, zip_path: str):
        self.zip_path = zip_path
        self.data: Dict[str, Dict[str, Any]] = defaultdict(dict)
        self.hrv_values: Dict[str, int] = {}  # Store for weekly baseline calculation
    
    def extract_local_date(self, timestamp_str: Optional[str]) -> Optional[str]:
        """Convert Garmin timestamp to local YYYY-MM-DD format."""
        if not timestamp_str:
            return None
        try:
            # Handle various timestamp formats
            ts = timestamp_str.split('.')[0]
            dt = datetime.strptime(ts, "%Y-%m-%dT%H:%M:%S")
            dt = dt.replace(tzinfo=ZoneInfo("UTC"))
            dt_local = dt.astimezone(TARGET_TZ)
            return dt_local.strftime("%Y-%m-%d")
        except Exception:
            return None
    
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
                duration = event.get("durationInSeconds", 0)
                if duration > 0:
                    self.data["SLEEP_DURATION_MINUTES"][date] = duration // 60
                
                deep = event.get("deepSleepSeconds", 0)
                if deep > 0:
                    self.data["DEEP_SLEEP_MINUTES"][date] = deep // 60
                
                light = event.get("lightSleepSeconds", 0)
                if light > 0:
                    self.data["LIGHT_SLEEP_MINUTES"][date] = light // 60
                
                rem = event.get("remSleepSeconds", 0)
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
                        self.data["FITNESS_AGE"][date] = int(bio_age)
                except Exception:
                    pass
        except Exception as e:
            print(f"Warning: Failed to process fitness age data from {file_name}: {e}")
    
    def process_hrv_data(self, file_name: str, z: zipfile.ZipFile):
        """Process HRV data from DI-Connect-Wellness/*HRV*.json files."""
        try:
            data = json.loads(z.read(file_name).decode('utf-8'))
            
            # Handle different HRV data structures
            if isinstance(data, dict):
                hrv_array = data.get("hrv", data.get("hrvSummary", []))
            else:
                hrv_array = data
            
            for entry in hrv_array if isinstance(hrv_array, list) else [hrv_array]:
                hrv_val = entry.get("nightlyAverage", entry.get("lastNightAvg", entry.get("value")))
                date = entry.get("calendarDate")
                
                if hrv_val and date:
                    self.hrv_values[date] = int(hrv_val)
                    self.data["HRV_LAST_NIGHT"][date] = int(hrv_val)
        except Exception as e:
            print(f"Warning: Failed to process HRV data from {file_name}: {e}")
    
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
                
                # Process sleep data
                if "DI-Connect-Wellness" in file_name and file_name.endswith("_sleepData.json"):
                    self.process_sleep_data(file_name, z)
                
                # Process RHR data
                elif "DI-Connect-Aggregator" in file_name and "UDSFile" in file_name and file_name.endswith(".json"):
                    self.process_rhr_data(file_name, z)
                
                # Process VO2 Max data
                elif "DI-Connect-Metrics" in file_name and "ActivityVo2Max" in file_name and file_name.endswith(".json"):
                    self.process_vo2_data(file_name, z)
                
                # Process Fitness Age data
                elif "DI-Connect-Wellness" in file_name and "fitnessAgeData" in file_name and file_name.endswith(".json"):
                    self.process_fitness_age_data(file_name, z)
                
                # Process HRV data
                elif "DI-Connect-Wellness" in file_name and "HRV" in file_name.upper() and file_name.endswith(".json"):
                    self.process_hrv_data(file_name, z)
                
                # Process daily summary data (steps, altitude, etc.) - REMOVED, now handled in UDSFile
                elif "DI-Connect-Daily" in file_name and file_name.endswith(".json"):
                    self.process_daily_summary(file_name, z)
        
        print(f"Processed {file_count} files")
        
        # Compute HRV weekly baseline
        self.compute_hrv_weekly_baseline()
    
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
    with open(output_path, 'w') as f:
        json.dump(output_data, f, indent=2)
    
    print(f"\n✓ Successfully wrote import data to {output_path}")
    print(f"  Total metrics extracted: {len(output_data)}")
    
    # Count total entries
    total_entries = sum(len(dates) for dates in output_data.values())
    print(f"  Total data points: {total_entries}")


if __name__ == "__main__":
    main()