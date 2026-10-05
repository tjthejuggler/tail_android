#!/usr/bin/env python3
"""One-shot restore (2026-10-05): recover wiped habit timestamps.

CONTEXT: habit_timestamps.json lost 59 of 82 habits between 2026-10-04 15:45
and 2026-10-05 (see scripts/ + ADR: non-atomic saveAll write + parse-failure
→ emptyMap fallback across processes). The AI-assistant safety backup taken
2026-10-04 15:45 (files/ai_assistant/habit_timestamps.backup.json) still has
the pre-wipe data.

REPAIR:
  * live file  = authoritative for habits/days it still has (it also contains
    everything logged after the backup was taken)
  * AI backup  = restores every habit/day the live file lost
  * duplicate-identical-times corruption in the backup (an old bug that wrote
    `amount` copies of the same time) is capped at the habitsdb count for that
    habit/day, so a 170x '07:37:20' Water day with count 23 becomes 23 copies.

Usage:
    python3 scripts/restore_habit_timestamps_20261005.py <live.json> <backup.json> <habitsdb.json> [out.json] [--apply]

--apply pushes the merged result to the phone (SAFE method: adb push to
/data/local/tmp + run-as cat, after force-stopping the app). Without it, runs
locally and prints a diff summary.
"""

import json
import subprocess
import sys

APP = "com.example.tail"
REMOTE_PREFS = "files/habit_timestamps.json"


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def cap_duplicates(times, count):
    """Collapse runs of identical times down to at most `count` copies total.

    Distinct times are always kept (they encode separate events); only the
    pathological repeat runs (old amount-bug) are capped.
    """
    if count is None or len(times) <= count:
        return times
    from collections import Counter
    freq = Counter(times)
    # keep every distinct time at least once, then distribute the remainder
    # proportionally to the observed runs (order preserved).
    distinct = sorted(freq)
    if len(distinct) >= count:
        return distinct[:count]
    out = []
    remaining = count
    per = {t: 1 for t in distinct}
    left = count - len(distinct)
    # hand out extra copies to the biggest runs first
    for t in sorted(distinct, key=lambda t: -freq[t]):
        while left > 0 and per[t] < freq[t]:
            per[t] += 1
            left -= 1
    for t in sorted(freq):
        out.extend([t] * per[t])
    return sorted(out)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    apply = "--apply" in sys.argv
    if len(args) < 3:
        sys.exit(__doc__)
    live, backup, habitsdb = (load(p) for p in args[:3])

    merged, restored_days, capped_days = {}, 0, 0
    habits = set(live) | set(backup)
    for h in habits:
        days = {}
        for day in set(live.get(h, {})) | set(backup.get(h, {})):
            if day in live.get(h, {}):
                times = list(live[h][day])
                src = "live"
            else:
                times = list(backup[h][day])
                src = "backup"
                restored_days += 1
            count = habitsdb.get(h, {}).get(day)
            capped = cap_duplicates(times, count)
            if len(capped) != len(times):
                capped_days += 1
            days[day] = sorted(capped)
        merged[h] = days

    print(f"habits: live={len(live)} backup={len(backup)} merged={len(merged)}")
    print(f"habit-days restored from backup: {restored_days}")
    print(f"habit-days duplicate-capped:     {capped_days}")
    print(f"merged timestamps total:         "
          f"{sum(len(t) for d in merged.values() for t in d.values())}")
    w = merged.get("Water", {})
    print(f"Water days after merge: {len(w)} (latest: {max(w) if w else '-'})")

    out_path = args[3] if len(args) > 3 else "/tmp/tail_ts/merged.json"
    with open(out_path, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False)
    print(f"wrote {out_path}")

    if not apply:
        print("[DRY RUN] re-run with --apply to push to the phone")
        return

    subprocess.run(["adb", "shell", "am", "force-stop", APP], check=False)
    tmp_remote = "/data/local/tmp/tail_ts_restore.json"
    subprocess.run(["adb", "push", out_path, tmp_remote], check=True)
    full = f"/data/data/{APP}/{REMOTE_PREFS}"
    subprocess.run(
        ["adb", "shell", f"run-as {APP} sh -c 'cat {tmp_remote} > {full}'"],
        check=True)
    subprocess.run(["adb", "shell", "rm", "-f", tmp_remote], check=False)
    print("restored to phone (app force-stopped; it will re-read on next launch)")


if __name__ == "__main__":
    main()
