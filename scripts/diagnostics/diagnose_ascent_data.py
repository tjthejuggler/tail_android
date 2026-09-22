#!/usr/bin/env python3
"""
Diagnostic script to examine raw altitude ascent data from Garmin export.
Helps identify unit conversion issues.
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
            dt = datetime.fromtimestamp(ts_seconds, tz=ZoneInfo("UTC"))
        else:
            timestamp_str = str(timestamp_raw).strip()
            if " " in timestamp_str:
                timestamp_str = timestamp_str.replace(" ", "T")
            ts = timestamp_str.split('.')[0]
            dt = datetime.strptime(ts, "%Y-%m-%dT%H:%M:%S")
            dt = dt.replace(tzinfo=ZoneInfo("UTC"))
        
        dt_local = dt.astimezone(TARGET_TZ)
        return dt_local.strftime("%Y-%m-%d")
    except Exception:
        return None

def analyze_ascent_data(zip_path):
    print(f"Analyzing ascent data from: {zip_path}\n")
    
    with zipfile.ZipFile(zip_path, 'r') as z:
        # Find activity summary files
        activity_files = [f for f in z.namelist() 
                         if 'summarizedactivities' in f.lower() and f.endswith('.json')]
        
        if not activity_files:
            print("No activity summary files found!")
            return
        
        print(f"Found {len(activity_files)} activity summary file(s)\n")
        
        all_ascent_values = []
        
        for file_name in activity_files:
            print(f"Processing: {file_name}")
            
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
                
                # Handle case where activity_array might be a single activity dict
                if not isinstance(activity_array, list):
                    activity_array = [activity_array]
                
                for entry in activity_array if isinstance(activity_array, list) else [activity_array]:
                    # Get activity date
                    start_time = (entry.get("startTimeGMT") or
                                  entry.get("startTimeGmt") or
                                  entry.get("startTimeLocal") or
                                  entry.get("beginTimestamp") or
                                  entry.get("startTime"))
                    
                    if not start_time:
                        continue
                    
                    date = extract_local_date(start_time)
                    if not date:
                        continue
                    
                    # Extract elevation gain - try all key variations
                    elevation_gain = (entry.get("elevationGain") or
                                      entry.get("totalElevationGain") or
                                      entry.get("ascent") or
                                      entry.get("elevationGainMeters"))
                    
                    if elevation_gain and elevation_gain > 0:
                        all_ascent_values.append({
                            'date': date,
                            'value': elevation_gain,
                            'value_as_cm': elevation_gain,
                            'value_as_meters': elevation_gain / 100,
                            'value_as_feet': (elevation_gain / 100) / 0.3048,
                            'activity_type': entry.get('activityType', 'unknown'),
                            'activity_name': entry.get('name', 'unknown')
                        })
            
            except Exception as e:
                print(f"  Error processing file: {e}")
        
        # Analyze the values
        if not all_ascent_values:
            print("\nNo ascent values found!")
            return
        
        print(f"\n{'='*80}")
        print(f"TOTAL ASCENT VALUES FOUND: {len(all_ascent_values)}")
        print(f"{'='*80}\n")
        
        # Show sample entries
        print("SAMPLE ENTRIES (first 10):")
        print(f"{'Date':<15} {'Raw (cm)':<15} {'As Meters':<15} {'As Feet':<15} {'Activity Type':<20} {'Activity Name'}")
        print("-" * 120)
        
        for i, entry in enumerate(all_ascent_values[:10]):
            print(f"{entry['date']:<15} {entry['value']:<15.0f} {entry['value_as_meters']:<15.1f} "
                  f"{entry['value_as_feet']:<15.1f} {entry['activity_type']:<20} {entry['activity_name']}")
        
        # Statistics
        values = [e['value'] for e in all_ascent_values]
        print(f"\n{'='*80}")
        print("STATISTICS:")
        print(f"{'='*80}")
        print(f"Min value: {min(values):.0f}")
        print(f"Max value: {max(values):.0f}")
        print(f"Mean value: {sum(values)/len(values):.0f}")
        print(f"Median value: {sorted(values)[len(values)//2]:.0f}")
        
        # Unit analysis
        print(f"\n{'='*80}")
        print("UNIT ANALYSIS (raw values appear to be in CENTIMETERS):")
        print(f"{'='*80}")
        
        # If values are in centimeters, most should be < 50000 (500 meters)
        cm_count = sum(1 for v in values if v < 50000)
        print(f"Values < 50000 (likely cm, i.e., < 500m ascent): {cm_count}/{len(values)} ({cm_count/len(values)*100:.1f}%)")
        
        # If values were in meters, reasonable daily ascent is < 5000 meters
        meter_count = sum(1 for v in values if 100 <= v < 5000)
        print(f"Values 100-5000 (if these were meters): {meter_count}/{len(values)} ({meter_count/len(values)*100:.1f}%)")
        
        # Show conversion examples
        print(f"\n{'='*80}")
        print("CONVERSION EXAMPLES (raw cm → meters → feet):")
        print(f"{'='*80}")
        for entry in all_ascent_values[:5]:
            print(f"{entry['date']} ({entry['activity_name']}): {entry['value']:.0f} cm = "
                  f"{entry['value_as_meters']:.1f} m = {entry['value_as_feet']:.1f} ft")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 diagnose_ascent_data.py <path_to_garmin_export.zip>")
        sys.exit(1)
    
    analyze_ascent_data(sys.argv[1])