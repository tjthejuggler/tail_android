#!/usr/bin/env python3
"""
Debug timestamp conversion for July 16-17 activities.
"""

import zipfile
import json
from datetime import datetime
from zoneinfo import ZoneInfo

with zipfile.ZipFile('/home/twain/Downloads/ca0839b0-30e7-4582-81e8-e07974700451_1.zip', 'r') as z:
    for fname in z.namelist():
        if 'summarizedactivities' in fname.lower() and fname.endswith('.json'):
            data = json.loads(z.read(fname).decode('utf-8'))
            if isinstance(data, list):
                activities = data[0].get('summarizedActivitiesExport', [])
            else:
                activities = data.get('summarizedActivitiesExport', [])
            
            print(f'Debugging timestamp conversion (found {len(activities)} activities):')
            for act in activities:
                local = act.get('startTimeLocal')
                if local:
                    ts = local / 1000
                    dt_naive = datetime.fromtimestamp(ts)
                    date_str = dt_naive.strftime('%Y-%m-%d')
                    
                    # Only show July 16-18 activities
                    if date_str in ['2020-07-16', '2020-07-17', '2020-07-18']:
                        name = act.get('name', 'unknown')
                        elev = act.get('elevationGain', 0) / 100
                        print(f'{name[:30]:30} local={local/1000:.0f}  naive_date={date_str}  elev={elev:.0f}m')
            break

print('\\nExpected:')
print('  July 16th: 138 + 119 = 257m (McCreary 2 + Oneida)')
print('  July 17th: 248 + 21 + 190 + 243 + 84 + 175 = 961m')
print('  Total: 1218m')