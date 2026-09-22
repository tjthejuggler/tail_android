#!/usr/bin/env python3
"""
Diagnostic script to list all files in a Garmin export ZIP.
Helps identify which files contain the missing metrics.
"""

import zipfile
import sys
from collections import defaultdict

def analyze_zip(zip_path):
    print(f"Analyzing: {zip_path}\n")
    
    with zipfile.ZipFile(zip_path, 'r') as z:
        files = z.namelist()
        
        # Categorize files
        categories = defaultdict(list)
        
        for f in files:
            f_lower = f.lower()
            
            if 'sleepdata' in f_lower and f.endswith('.json'):
                categories['Sleep Data'].append(f)
            elif 'hrv' in f_lower and f.endswith('.json'):
                categories['HRV Data'].append(f)
            elif 'stress' in f_lower and f.endswith('.json'):
                categories['Stress Data'].append(f)
            elif 'vo2max' in f_lower or 'vo2' in f_lower:
                categories['VO2 Max'].append(f)
            elif 'summarizedactivities' in f_lower:
                categories['Activity Summaries'].append(f)
            elif f.endswith('.fit'):
                categories['FIT Files'].append(f)
            elif 'udsfile' in f_lower:
                categories['Daily Aggregator'].append(f)
            elif 'fitnessage' in f_lower:
                categories['Fitness Age'].append(f)
        
        # Print results
        print(f"Total files: {len(files)}\n")
        print("=" * 60)
        
        for category, file_list in sorted(categories.items()):
            print(f"\n{category} ({len(file_list)} files):")
            for f in file_list[:5]:  # Show first 5
                print(f"  - {f}")
            if len(file_list) > 5:
                print(f"  ... and {len(file_list) - 5} more")
        
        # Check for missing categories
        expected = ['Sleep Data', 'HRV Data', 'Stress Data', 'VO2 Max', 'Activity Summaries', 'FIT Files']
        missing = [cat for cat in expected if cat not in categories]
        
        if missing:
            print("\n" + "=" * 60)
            print("\nMissing categories (may be in other ZIP parts):")
            for cat in missing:
                print(f"  - {cat}")
        
        # Check directory structure
        print("\n" + "=" * 60)
        print("\nTop-level directories:")
        dirs = set()
        for f in files:
            parts = f.split('/')
            if len(parts) > 1:
                dirs.add(parts[0])
        for d in sorted(dirs):
            print(f"  - {d}/")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python3 diagnose_garmin_zip.py <path_to_garmin_export.zip>")
        sys.exit(1)
    
    analyze_zip(sys.argv[1])