#!/usr/bin/env python3
"""
Diagnostic script to investigate July 17th, 2020 ascent data discrepancy.
"""

import zipfile
import json
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

TARGET_TZ = ZoneInfo("Europe/Dublin")

def extract_local_date(timestamp_raw):
    """Convert Garmin timestamp to local YYYY-MM-DD format."""
    if not timestamp_raw:
        return None
    try:
        if isinstance(timestamp_raw, (int, float)):
            ts_seconds = timestamp_raw / 1000 if timestamp_raw > 2e9 else timestamp_raw
            dt_utc = datetime.fromtimestamp(ts_seconds, tz=ZoneInfo("UTC"))
            dt_local = dt_utc.astimezone(TARGET_TZ)
            return dt_local.strftime("%Y-%m-%d")
        else:
            timestamp_str = str(timestamp_raw).strip()
            if " " in timestamp_str:
                timestamp_str = timestamp_str.replace(" ", "T")
            ts = timestamp_str.split('.')[0]
            dt = datetime.strptime(ts, "%Y-%m-%dT%H:%M:%S")
            if dt.tzinfo is None:
                dt = dt.replace(tzinfo=TARGET_TZ)
            else:
                dt = dt.astimezone(TARGET_TZ)
        return dt.strftime("%Y-%m-%d")
    except Exception:
        return None

def analyze_july17(zip_path):
    print(f"Analyzing July 17th, 2020 from: {zip_path}\n")
    
    with zipfile.ZipFile(zip_path, 'r') as z:
        # Find activity summary files
        activity_files = [f for f in z.namelist() 
                         if 'summarizedactivities' in f.lower() and f.endswith('.json')]
        
        july17_activities = []
        
        for file_name in activity_files:
            try:
                data = json.loads(z.read(file_name).decode('utf-8'))
                
                # Handle different activity data structures
                if isinstance(data, list) and len(data) > 0:
                    activity_array = data[0].get("summarizedActivitiesExport", [])
                elif isinstance(data, dict):
                    activity_array = data.get("summarizedActivitiesExport",
                                             data.get("activities",
                                                    data.get("activityList", [])))
                else:
                    activity_array = []
                
                # Handle case where activity_array might be a single activity dict
                if not isinstance(activity_array, list):
                    activity_array = [activity_array]
                
                for entry in activity_array:
                    # Get activity date - use same logic as garmin_import.py
                    start_time = (entry.get("startTimeGMT") or
                                  entry.get("startTimeGmt") or
                                  entry.get("beginTimestamp") or
                                  entry.get("startTime"))
                    
                    # Only use startTimeLocal as a fallback, and handle it specially
                    if not start_time:
                        start_time_local = entry.get("startTimeLocal")
                        if start_time_local:
                            # startTimeLocal is a Unix timestamp representing local time
                            # Use it directly without timezone conversion to get the correct date
                            ts_seconds = start_time_local / 1000 if start_time_local > 2e9 else start_time_local
                            dt = datetime.fromtimestamp(ts_seconds)
                            date = dt.strftime("%Y-%m-%d")
                        else:
                            continue
                    else:
                        date = extract_local_date(start_time)
                        if not date:
                            continue
                    
                    # Only look at July 17th, 2020
                    if date != "2020-07-17":
                        continue
                    
                    # Extract elevation gain - try all key variations
                    elevation_gain = (entry.get("elevationGain") or
                                      entry.get("totalElevationGain") or
                                      entry.get("ascent") or
                                      entry.get("elevationGainMeters"))
                    
                    if elevation_gain and elevation_gain > 0:
                        july17_activities.append({
                            'name': entry.get('name', 'unknown'),
                            'activity_type': entry.get('activityType', 'unknown'),
                            'raw_cm': elevation_gain,
                            'meters': elevation_gain / 100,
                            'feet': (elevation_gain / 100) / 0.3048,
                            'start_time_gmt': entry.get('startTimeGMT'),
                            'start_time_local': entry.get('startTimeLocal'),
                            'distance': entry.get('distance')
                        })
            
            except Exception as e:
                print(f"  Error processing file {file_name}: {e}")
        
        if not july17_activities:
            print("No activities found for July 17th, 2020!")
            return
        
        print(f"{'='*100}")
        print(f"ACTIVITIES FOR JULY 17TH, 2020 (found {len(july17_activities)} activities)")
        print(f"{'='*100}\n")
        
        total_meters = 0
        for i, activity in enumerate(july17_activities, 1):
            print(f"Activity {i}: {activity['name']}")
            print(f"  Type: {activity['activity_type']}")
            print(f"  Elevation Gain: {activity['raw_cm']:.0f} cm = {activity['meters']:.1f} m = {activity['feet']:.1f} ft")
            if activity['distance']:
                print(f"  Distance: {activity['distance']:.0f} m")
            print(f"  Start Time (GMT): {activity['start_time_gmt']}")
            print(f"  Start Time (Local): {activity['start_time_local']}")
            print()
            total_meters += activity['meters']
        
        print(f"{'='*100}")
        print(f"TOTAL ASCENT FOR JULY 17TH, 2020: {total_meters:.0f} meters")
        print(f"{'='*100}")
        
        # Check what the JSON file has
        print(f"\nChecking garmin_import.json for July 17th, 2020...")
        try:
            with open('garmin_import.json', 'r') as f:
                import_data = json.load(f)
                json_value = import_data.get('ALTITUDE_ASCENT_METERS', {}).get('2020-07-17')
                print(f"JSON file value: {json_value} meters")
                print(f"Difference: {json_value - total_meters:.0f} meters")
        except Exception as e:
            print(f"Error reading JSON file: {e}")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 diagnose_july17.py <path_to_garmin_export.zip>")
        sys.exit(1)
    
    analyze_july17(sys.argv[1])