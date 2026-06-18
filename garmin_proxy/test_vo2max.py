#!/usr/bin/env python3
"""
Direct test script to fetch today's VO2max data from Garmin.
This bypasses the proxy to isolate whether Garmin has VO2max data available.
"""

import os
import sys
from datetime import datetime
from garminconnect import Garmin

# Garmin credentials
EMAIL = os.environ.get("GARMIN_EMAIL", "tjthejuggler@gmail.com")
PASSWORD = os.environ.get("GARMIN_PASSWORD", "Zaq123edc")

def main():
    print(f"Connecting to Garmin as {EMAIL}...")
    
    try:
        # Initialize Garmin client
        garmin = Garmin(EMAIL, PASSWORD)
        garmin.login()
        print("✓ Login successful")
        
        # Get today's date
        today = datetime.now().strftime("%Y-%m-%d")
        print(f"\nFetching data for {today}...")
        
        # Try to get VO2max data
        print("\n--- Attempting to fetch VO2max ---")
        try:
            vo2max_data = garmin.get_user_metrics(today)
            print(f"Raw user_metrics response: {vo2max_data}")
            
            if vo2max_data:
                # Look for VO2max in the response
                if "vo2Max" in vo2max_data:
                    print(f"✓ VO2max found: {vo2max_data['vo2Max']}")
                elif "vo2_max" in vo2max_data:
                    print(f"✓ VO2max found: {vo2max_data['vo2_max']}")
                else:
                    print("✗ VO2max not found in user_metrics")
                    print(f"Available keys: {list(vo2max_data.keys())}")
            else:
                print("✗ No data returned from get_user_metrics")
                
        except Exception as e:
            print(f"✗ Error fetching VO2max: {e}")
        
        # Try alternative methods
        print("\n--- Trying alternative: get_stats_and_body ---")
        try:
            stats = garmin.get_stats_and_body(today)
            print(f"Raw stats response: {stats}")
            
            if stats:
                # Look for VO2max in various possible locations
                if "vo2Max" in stats:
                    print(f"✓ VO2max found in stats: {stats['vo2Max']}")
                elif "vo2_max" in stats:
                    print(f"✓ VO2max found in stats: {stats['vo2_max']}")
                elif "maxVO2" in stats:
                    print(f"✓ VO2max found in stats (maxVO2): {stats['maxVO2']}")
                else:
                    print("✗ VO2max not found in stats")
                    print(f"Available keys: {list(stats.keys())}")
            else:
                print("✗ No data returned from get_stats_and_body")
                
        except Exception as e:
            print(f"✗ Error fetching stats: {e}")
        
        # Try training status (which the health check uses)
        print("\n--- Trying training_status (used by health check) ---")
        try:
            training_status = garmin.get_training_status(today)
            print(f"Training status: {training_status}")
            
            # Look for VO2max in the expected location
            if "mostRecentVO2Max" in training_status:
                vo2max_obj = training_status["mostRecentVO2Max"]
                print(f"✓ mostRecentVO2Max found: {vo2max_obj}")
                if isinstance(vo2max_obj, dict):
                    if "genericValue" in vo2max_obj:
                        print(f"  → genericValue: {vo2max_obj['genericValue']}")
                    else:
                        print(f"  Available keys: {list(vo2max_obj.keys())}")
            else:
                print("✗ mostRecentVO2Max not found in training_status")
                print(f"Available keys: {list(training_status.keys())}")
                
        except Exception as e:
            print(f"✗ Error fetching training_status: {e}")
        
        # Check HRV data
        print("\n--- Checking HRV data ---")
        try:
            hrv_data = garmin.get_hrv_data(today)
            print(f"HRV raw response: {hrv_data}")
        except Exception as e:
            print(f"✗ Error fetching HRV: {e}")
        
        # Check sleep data
        print("\n--- Checking sleep data ---")
        try:
            sleep_data = garmin.get_sleep_data(today)
            print(f"Sleep raw response: {sleep_data}")
        except Exception as e:
            print(f"✗ Error fetching sleep: {e}")
        
        # List all available methods for debugging
        print("\n--- Available GarminConnect methods ---")
        methods = [m for m in dir(garmin) if not m.startswith('_')]
        vo2_related = [m for m in methods if 'vo2' in m.lower() or 'vo2max' in m.lower()]
        if vo2_related:
            print(f"VO2-related methods: {vo2_related}")
        else:
            print("No VO2-related methods found")
        
    except Exception as e:
        print(f"✗ Login or general error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()