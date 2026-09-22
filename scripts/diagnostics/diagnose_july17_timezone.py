#!/usr/bin/env python3
"""
Check timezone conversion for July 17th, 2020 activities.
"""

import zipfile
import json
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

TARGET_TZ = ZoneInfo("Europe/Dublin")

def analyze_timezone(zip_path):
    print(f"Analyzing timezone conversion for July 17th, 2020 from: {zip_path}\n")
    
    with zipfile.ZipFile(zip_path, 'r') as z:
        activity_files = [f for f in z.namelist() 
                         if 'summarizedactivities' in f.lower() and f.endswith('.json')]
        
        for file_name in activity_files:
            try:
                data = json.loads(z.read(file_name).decode('utf-8'))
                
                if isinstance(data, list) and len(data) > 0:
                    activity_array = data[0].get("summarizedActivitiesExport", [])
                elif isinstance(data, dict):
                    activity_array = data.get("summarizedActivitiesExport",
                                             data.get("activities",
                                                    data.get("activityList", [])))
                else:
                    activity_array = []
                
                if not isinstance(activity_array, list):
                    activity_array = [activity_array]
                
                for entry in activity_array:
                    start_time_local = entry.get("startTimeLocal")
                    start_time_gmt = entry.get("startTimeGMT")
                    
                    if not start_time_local:
                        continue
                    
                    # Convert local time to date
                    try:
                        ts_seconds = start_time_local / 1000
                        dt_local = datetime.fromtimestamp(ts_seconds, tz=ZoneInfo("UTC"))
                        dt_local = dt_local.astimezone(TARGET_TZ)
                        date_local = dt_local.strftime("%Y-%m-%d %H:%M:%S")
                    except:
                        continue
                    
                    # Convert GMT time to date
                    if start_time_gmt:
                        try:
                            ts_seconds = start_time_gmt / 1000
                            dt_gmt = datetime.fromtimestamp(ts_seconds, tz=ZoneInfo("UTC"))
                            dt_gmt = dt_gmt.astimezone(TARGET_TZ)
                            date_gmt = dt_gmt.strftime("%Y-%m-%d %H:%M:%S")
                        except:
                            date_gmt = "N/A"
                    else:
                        date_gmt = "N/A"
                    
                    # Only show activities around July 17th
                    if "2020-07-16" in date_local or "2020-07-17" in date_local or "2020-07-18" in date_local:
                        elevation_gain = entry.get("elevationGain")
                        if elevation_gain:
                            print(f"Activity: {entry.get('name', 'unknown')}")
                            print(f"  Local Time: {date_local}")
                            print(f"  GMT Time:   {date_gmt}")
                            print(f"  Elevation:  {elevation_gain/100:.1f} m")
                            print()
            
            except Exception as e:
                pass

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 diagnose_july17_timezone.py <path_to_garmin_export.zip>")
        sys.exit(1)
    
    analyze_timezone(sys.argv[1])