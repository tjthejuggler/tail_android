#!/usr/bin/env python3
"""
One-off repair (2026-10-07): reconstruct lost sleep_data.json bed/wake times
from habit_timestamps.json.

CONTEXT: sleep_data.json on the phone was wiped for the nights the user
manually logged between ~2026-09-24 and 2026-10-04 (same non-atomic
saveAll/emptyMap bug that hit habit_timestamps.json on 2026-10-05). The
auto-backups do NOT include sleep_data.json (perHabitFiles only covers
datedEntry/subtypeData/textInput/timedData), but the bed/wake TIMES survive
verbatim in habit_timestamps.json ("Sleep"/"Wake" habit -> date -> ["HH:mm:ss"]).

REPAIR: for every Sleep/Wake timestamp date that has no bed/wake in
sleep_data.json, add a record with the time converted to minutes-since-
midnight. Existing records are never touched. Temp/conditions/survey answers
for those nights are NOT recoverable and are left absent.
"""

import json
import re
import subprocess
import sys

APP = "com.example.tail"
REMOTE = "files/sleep_data.json"
LOCAL = "/tmp/sleep_data_repair.json"


def hhmmss_to_min(t):
    m = re.match(r"^(\d{1,2}):(\d{2}):(\d{2})$", t)
    return int(m.group(1)) * 60 + int(m.group(2)) if m else None


def main():
    out = subprocess.run(["adb", "exec-out", "run-as", APP, "cat", REMOTE],
                         capture_output=True)
    sleep_data = json.loads(out.stdout or b"{}")
    out = subprocess.run(["adb", "exec-out", "run-as", APP,
                          "cat", "files/habit_timestamps.json"],
                         capture_output=True)
    ts = json.loads(out.stdout or b"{}")

    fixed = 0
    for habit, time_key, list_key in (("Sleep", "bed", "beds"),
                                      ("Wake", "wake", "wakes")):
        for date_str, times in sorted(ts.get(habit, {}).items()):
            rec = sleep_data.setdefault(habit, {}).setdefault(date_str, {})
            if rec.get(time_key) is not None or rec.get(list_key):
                continue  # existing record — never touch
            mins = [hhmmss_to_min(t) for t in times if hhmmss_to_min(t) is not None]
            if not mins:
                continue
            rec[time_key] = mins[0] if habit == "Sleep" else mins[-1]
            rec[list_key] = mins
            fixed += 1
            print(f"  {habit} {date_str}: {list_key}={mins}")

    if not fixed:
        print("Nothing to repair.")
        return
    if "--apply" not in sys.argv:
        print(f"\n{fixed} records would be added. DRY RUN — re-run with --apply.")
        return

    open(LOCAL, "w").write(json.dumps(sleep_data, ensure_ascii=False, indent=2))
    subprocess.run(["adb", "shell", "am", "force-stop", APP], check=True)
    subprocess.run(["adb", "push", LOCAL, "/data/local/tmp/sdr.json"], check=True,
                   capture_output=True)
    subprocess.run(["adb", "shell", "run-as", APP, "cp",
                    "/data/local/tmp/sdr.json", REMOTE], check=True)
    subprocess.run(["adb", "shell", "rm", "/data/local/tmp/sdr.json"], check=True)
    print(f"Repaired {fixed} records and pushed {REMOTE}.")


if __name__ == "__main__":
    main()
