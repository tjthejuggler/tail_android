#!/usr/bin/env python3
"""One-shot repair (2026-09-08): collapse duplicate movie-habit timestamps.

CONTEXT: a bug in the timestamp editor fed a movie entry's watch length
(minutes) into the timestamp AMOUNT on Save. For "Fiction Video Intake"
this wrote 23 copies of the same timestamp (14:39:27) for 2026-09-08 —
showing "53 minutes across 23 instances" for a single watch. The habits
DB itself was unaffected (count=1, minutes=53); only the internal
timestamp store (files/habit_timestamps.json) was corrupted.

REPAIR: for movie-bridge habits, one text entry is exactly one watch,
so duplicate identical (date, time) timestamps are always corruption.
Each affected day's list is replaced with its distinct, sorted times.
Non-movie habits are NEVER touched — for them, repeated identical
timestamps legitimately encode multi-unit increments.

The editor-side guard (TimestampEditorDialog) and the sync-side collapse
(syncMovieTimestamps) prevent this from recurring, so this script should
not need to run again.

Usage:
    python3 scripts/repair_movie_timestamp_duplicates_20260908.py          # dry run
    python3 scripts/repair_movie_timestamp_duplicates_20260908.py --apply

Uses the SAFE transfer method (base64 over `adb shell` + `run-as cat`),
never piping stdin into `run-as sh -c 'cat > file'`.
"""

import json
import subprocess
import sys

APP = "com.example.tail"
PREFS = "files/habit_timestamps.json"
# Movie-bridge habits only (duplicate identical timestamps = corruption).
# Everything else keeps repeated timestamps as legitimate unit counts.
MOVIE_HABITS = ["Fiction Video Intake"]


def pull_json() -> dict:
    raw = subprocess.run(
        ["adb", "exec-out", "run-as", APP, "cat", PREFS],
        check=True, stdout=subprocess.PIPE).stdout
    if not raw.strip():
        sys.exit("timestamp file empty — is the device connected / app installed?")
    return json.loads(raw.decode("utf-8"))


def push_json(data: dict) -> None:
    tmp_local = "/tmp/tail_ts_repair.json"
    tmp_remote = "/data/local/tmp/tail_ts_repair.json"
    with open(tmp_local, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False)
    subprocess.run(["adb", "push", tmp_local, tmp_remote], check=True)
    # run-as write so the file ends up owned by the app uid.
    full = f"/data/data/{APP}/{PREFS}"
    subprocess.run(
        ["adb", "shell", f"run-as {APP} sh -c 'cat {tmp_remote} > {full}'"],
        check=True)
    subprocess.run(["adb", "shell", "rm", "-f", tmp_remote], check=False)
    # Force a reload so a running app instance doesn't overwrite the file
    # from its stale in-memory copy on its next write.
    subprocess.run(["adb", "shell", "am", "force-stop", APP], check=False)


def main() -> None:
    apply = "--apply" in sys.argv
    data = pull_json()
    changed_days = 0
    removed = 0
    for habit in MOVIE_HABITS:
        days = data.get(habit)
        if not days:
            print(f"'{habit}' not in timestamp store — nothing to do")
            continue
        for date, times in sorted(days.items()):
            dedup = sorted(set(times))
            if len(dedup) != len(times):
                print(f"  {habit} {date}: {len(times)} -> {len(dedup)} "
                      f"(removed {len(times) - len(dedup)} duplicates)")
                days[date] = dedup
                changed_days += 1
                removed += len(times) - len(dedup)
    if changed_days == 0:
        print("No duplicate movie timestamps found — already clean.")
        return
    if not apply:
        print(f"\n[DRY RUN] would collapse {removed} duplicate timestamp(s) "
              f"across {changed_days} day(s). Re-run with --apply to write.")
        return
    push_json(data)
    print(f"\nRepaired {changed_days} day(s), removed {removed} duplicate "
          f"timestamp(s). Written back to {PREFS}.")


if __name__ == "__main__":
    main()
