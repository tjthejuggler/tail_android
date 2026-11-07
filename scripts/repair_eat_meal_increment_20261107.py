#!/usr/bin/env python3
"""One-shot repair (2026-11-07): backfill the missed Eat meal increment.

CONTEXT: the 16:57:49 quick-capture meal ("Falafel & Beluga Lentil Power
Bowl", meal log 89589dc9) saved its meal-log card but the habit increment
+ timestamp silently failed — the habits DB load hit a transient
MalformedJsonException (truncated half-synced file, see qc_diag.log
2026-11-07 16:58:00). The file parses fine again; only the +1 and the
16:57:49 stamp are missing.

REPAIR:
  * habitsdb.txt        : Eat['2026-11-07'] += 1
  * habit_timestamps.json: append "16:57:49" to Eat['2026-11-07']

Method (SAFE, same as restore_habit_timestamps_20261005.py):
force-stop the app, adb push the habits DB directly to its Documents path,
push the timestamps file through /data/local/tmp + run-as, then the app is
restarted manually (or by opening it) and reloads both files fresh.

Usage: python3 scripts/repair_eat_meal_increment_20261107.py [--apply]
Without --apply, only verifies local copies in .tmp/.
"""

import json
import subprocess
import sys
from pathlib import Path

APP = "com.example.tail"
HABIT = "Eat"
DATE = "2026-11-07"
STAMP = "16:57:49"
PHONE_DB = "/sdcard/Documents/habitsdb/habitsdb.txt"
LOCAL_DB = Path(".tmp/habitsdb_phone.txt")
LOCAL_TS = Path(".tmp/habit_timestamps.json")


def sh(*cmd: str) -> str:
    return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout


def main() -> None:
    apply = "--apply" in sys.argv

    # ── timestamps (idempotency guard: re-running must not double-apply) ──
    ts = json.loads(LOCAL_TS.read_text())
    already_applied = STAMP in ts.get(HABIT, {}).get(DATE, [])

    # ── habits DB ────────────────────────────────────────────────────────
    db = json.loads(LOCAL_DB.read_text())
    cur = db[HABIT].get(DATE)
    assert isinstance(cur, int), f"unexpected {HABIT}[{DATE}]={cur!r}"
    if not already_applied:
        print(f"habitsdb: {HABIT}[{DATE}] {cur} -> {cur + 1}")
        db[HABIT][DATE] = cur + 1
    else:
        print(f"already applied (stamp {STAMP} present) — count left at {cur}")
    LOCAL_DB.write_text(json.dumps(db, ensure_ascii=False))

    # ── timestamps ───────────────────────────────────────────────────────
    day = ts.setdefault(HABIT, {}).setdefault(DATE, [])
    if STAMP not in day:
        day.append(STAMP)
        day.sort()
    LOCAL_TS.write_text(json.dumps(ts, ensure_ascii=False))
    print(f"timestamps: {HABIT}[{DATE}] = {day}")

    if not apply:
        print("dry-run only — re-run with --apply to push to the phone")
        return

    sh("adb", "shell", "am", "force-stop", APP)
    # habits DB — direct file on shared storage
    sh("adb", "push", str(LOCAL_DB), PHONE_DB)
    # timestamps — app-internal file via /data/local/tmp + run-as
    sh("adb", "push", str(LOCAL_TS), "/data/local/tmp/hts.json")
    sh("adb", "shell", f"run-as {APP} sh -c 'cat /data/local/tmp/hts.json > files/habit_timestamps.json'")
    sh("adb", "shell", "rm", "/data/local/tmp/hts.json")
    print("pushed — open the app to reload (Eat today should now show +1 and the 16:57:49 chip)")


if __name__ == "__main__":
    main()
