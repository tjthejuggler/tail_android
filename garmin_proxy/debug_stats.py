#!/usr/bin/env python3
"""
Debug script to check what fields are available in get_stats_and_body response.
"""

import os
import sys
from datetime import datetime
from pathlib import Path

# Load environment variables
env_path = Path(__file__).parent / ".env"
if env_path.exists():
    from dotenv import load_dotenv
    load_dotenv(env_path)

from garminconnect import Garmin

TOKEN_STORE = os.getenv("GARMINTOKENS", os.path.expanduser("~/.garminconnect"))

def main():
    print("Connecting to Garmin using saved tokens...")
    
    try:
        client = Garmin()
        client.login(TOKEN_STORE)
        print("✓ Login successful")
        
        # Test a few dates
        test_dates = ["2026-06-24", "2026-06-23", "2026-06-22"]
        
        for date_str in test_dates:
            print(f"\n{'='*60}")
            print(f"Fetching stats for {date_str}...")
            print('='*60)
            
            try:
                stats = client.get_stats_and_body(date_str)
                
                # Print all keys
                print(f"\nAll available keys in get_stats_and_body response:")
                for key in sorted(stats.keys()):
                    value = stats[key]
                    if isinstance(value, dict):
                        print(f"  {key}: <dict with {len(value)} keys>")
                    elif isinstance(value, list):
                        print(f"  {key}: <list with {len(value)} items>")
                    else:
                        print(f"  {key}: {value}")
                
                # Look specifically for active minutes related fields
                print(f"\nActive minutes related fields:")
                for key in stats.keys():
                    if 'active' in key.lower() or 'minute' in key.lower() or 'intensity' in key.lower():
                        print(f"  {key}: {stats[key]}")
                
            except Exception as e:
                print(f"✗ Error fetching stats for {date_str}: {e}")
                
    except Exception as e:
        print(f"✗ Error: {e}")
        sys.exit(1)

if __name__ == "__main__":
    main()