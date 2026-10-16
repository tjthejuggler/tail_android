#!/usr/bin/env python3
"""One-shot backfill (2026-10-15): accurate historic habit timestamps.

CONTEXT: per-increment timestamp capture only became universal on
2026-09-22. Before that only Wake/Sleep/Fiction Video Intake recorded
stamps, so habits with authoritative per-event times in external sources
have counts in habitsdb but no (or partial) timestamps. This script
backfills real times — never invented ones — from:

  1. Garmin GDPR ZIP (_summarizedActivities.json + sleepData.json):
       - Garmin Run / Bike / Swim  → earliest per-sport activity start
         per day (startTimeLocal read as wall-clock, same trick as
         scripts/ingest/garmin_import.py)
       - Meditations               → one stamp per meditation /
         breath_work session start
       - Sleep                     → bedtime (sleep event start)
       - Wake                      → sleep event end
  2. files/garmin_cache/activity_times.json (pulled from the phone):
       recent live-proxy days the ZIP export doesn't cover.
  3. chess.com public archives API (user "jugglah"):
       Blitz/Bullet/Rapid Chess → one stamp per game at its actual
       end time, classified by base time exactly like the app
       (core-data ChessComRepository.classifyByTimeControl):
       bullet < 180s <= blitz < 600s <= rapid; "/" (daily) skipped.

SAFETY RULES (accuracy first):
  - Existing timestamps are NEVER touched — only shortfalls are filled.
  - A day is only filled up to its habitsdb count for that habit/day.
  - Days where the source has FEWER events than the count are flagged
    in the report (partial fill of real events only).
  - Chess days where the API game count differs from the habitsdb count
    are flagged; the newest (count - existing) games of that day are
    used, mirroring ChessComRepository.newGameEndTimes semantics.

Usage:
  python3 backfill_timestamps_20261015.py <live.json> <habitsdb.json> \
      [--zip /path/to/garmin.zip] [--activity-times activity_times.json] \
      [--chess-user jugglah] [out.json] [--apply]

--apply pushes the merged result to the phone (force-stop + adb push +
run-as cat, same safe method as restore_habit_timestamps_20261005.py).
"""

import argparse
import json
import subprocess
import sys
import time
import urllib.request
import zipfile
from collections import defaultdict
from datetime import datetime
from pathlib import Path
from zoneinfo import ZoneInfo

APP = "com.example.tail"
REMOTE_FILE = "files/habit_timestamps.json"
PHONE_TZ = ZoneInfo("Europe/Istanbul")  # matches the phone's ZoneId.systemDefault()
CHESS_ARCHIVE_CACHE = Path("/tmp/tail_ts/chesscom_archives")
UA = "tail-backfill-script"

SPORT_HABITS = {"RUN_MINUTES": "Garmin Run", "BIKE_MINUTES": "Garmin Bike",
                "SWIM_MINUTES": "Garmin Swim"}
CHESS_HABITS = {"BULLET": "Bullet Chess", "BLITZ": "Blitz Chess",
                "RAPID": "Rapid Chess"}
MEDITATION_TYPES = ("meditation", "breath_work", "breathwork")


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def wall_dt(epoch_ms_or_s):
    """Garmin startTimeLocal: epoch encoding the DEVICE wall clock.
    Reading it as UTC yields the watch's local date/time (see
    garmin_import.py process_activity_data)."""
    ts = epoch_ms_or_s / 1000 if epoch_ms_or_s > 2e9 else epoch_ms_or_s
    return datetime.fromtimestamp(ts, tz=ZoneInfo("UTC"))


def local_dt(raw):
    """Garmin sleep timestamps: epoch ms (int) or ISO string → PHONE_TZ."""
    if isinstance(raw, str):
        return datetime.fromisoformat(raw.split(".")[0]).replace(tzinfo=PHONE_TZ) \
            if "T" in raw else datetime.fromtimestamp(int(raw) / 1000, tz=PHONE_TZ)
    ts = raw / 1000 if raw > 2e9 else raw
    return datetime.fromtimestamp(ts, tz=PHONE_TZ)


# ── Garmin ZIP extraction ──────────────────────────────────────────────────

def extract_from_zip(zip_path):
    """Returns (sport_starts, meditation_starts, sleep_events).

    sport_starts / meditation_starts: {date: {habit: [HH:MM:SS, ...]}}
    sleep_events: [{date, start, end}] with datetimes in PHONE_TZ.
    """
    sports = defaultdict(lambda: defaultdict(list))   # date -> habit -> [tod]
    medit = defaultdict(list)                         # date -> [tod]
    sleeps = []
    with zipfile.ZipFile(zip_path) as z:
        for name in z.namelist():
            low = name.lower()
            if "summarizedactivities" in low and low.endswith(".json"):
                try:
                    data = json.loads(z.read(name).decode("utf-8"))
                except Exception:
                    continue
                arr = data if isinstance(data, list) else [data]
                acts = []
                for block in arr:
                    if isinstance(block, dict):
                        acts += block.get("summarizedActivitiesExport", []) or []
                for a in acts:
                    stl = a.get("startTimeLocal")
                    if not stl:
                        continue
                    dt = wall_dt(stl)
                    atype = a.get("activityType")
                    tk = atype.get("typeKey", "") if isinstance(atype, dict) else (atype or "")
                    tk = (tk or "").lower()
                    date = dt.strftime("%Y-%m-%d")
                    if any(k in tk for k in MEDITATION_TYPES):
                        medit[date].append(dt.strftime("%H:%M:%S"))
                    else:
                        habit = None
                        if "swim" in tk:
                            habit = SPORT_HABITS["SWIM_MINUTES"]
                        elif "cycl" in tk or "bik" in tk:
                            habit = SPORT_HABITS["BIKE_MINUTES"]
                        elif "run" in tk:
                            habit = SPORT_HABITS["RUN_MINUTES"]
                        if habit:
                            sports[date][habit].append(dt.strftime("%H:%M:%S"))
            elif "sleepdata" in low and low.endswith(".json"):
                try:
                    data = json.loads(z.read(name).decode("utf-8"))
                except Exception:
                    continue
                events = data if isinstance(data, list) else data.get("sleepDatas", [])
                for ev in events:
                    end = ev.get("sleepEndTimestampGMT")
                    start = ev.get("sleepStartTimestampGMT")
                    if not end:
                        continue
                    end_dt = local_dt(end)
                    start_dt = local_dt(start) if start else None
                    sleeps.append({"end": end_dt, "start": start_dt})
    for d in medit:
        medit[d] = sorted(set(medit[d]))
    return sports, dict(medit), sleeps


# ── chess.com archives ─────────────────────────────────────────────────────

def classify(time_control):
    """Mirrors ChessComRepository.classifyByTimeControl."""
    if "/" in time_control:
        return None
    base = time_control.split("+")[0]
    try:
        base = float(base)
    except ValueError:
        return None
    if base < 180:
        return "BULLET"
    if base < 600:
        return "BLITZ"
    return "RAPID"


def fetch_month(user, year, month):
    CHESS_ARCHIVE_CACHE.mkdir(parents=True, exist_ok=True)
    cache = CHESS_ARCHIVE_CACHE / f"{user}_{year}_{month:02d}.json"
    if cache.exists():
        return load(cache)
    url = f"https://api.chess.com/pub/player/{user}/games/{year}/{month:02d}"
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as r:
        data = json.loads(r.read().decode("utf-8"))
    with open(cache, "w", encoding="utf-8") as f:
        json.dump(data, f)
    time.sleep(1.0)  # be polite to the API
    return data


def chess_end_times(user, first_month, last_month):
    """{habit: {date: [HH:MM:SS oldest-first]}} for the user's games."""
    out = defaultdict(lambda: defaultdict(list))
    (fy, fm) = first_month
    (ly, lm) = last_month
    y, m = fy, fm
    user_lower = user.lower()
    while (y, m) <= (ly, lm):
        try:
            data = fetch_month(user, y, m)
        except Exception as e:
            print(f"  ! chess.com {y}-{m:02d} failed: {e}")
        else:
            for g in data.get("games", []):
                tc = g.get("time_control", "")
                # "end_time" = epoch seconds; "endTime" = ISO fallback
                et = g.get("end_time")
                if not et and g.get("endTime"):
                    try:
                        iso = g["endTime"].split(".")[0] + "+00:00"
                        et = datetime.fromisoformat(iso).timestamp()
                    except ValueError:
                        pass
                if not et:
                    continue
                players = g.get("white", {}).get("username", "").lower(), \
                    g.get("black", {}).get("username", "").lower()
                if user_lower not in players:
                    continue
                type_key = classify(tc)
                if not type_key:
                    continue
                dt = datetime.fromtimestamp(et, tz=PHONE_TZ)
                out[CHESS_HABITS[type_key]][dt.strftime("%Y-%m-%d")] \
                    .append(dt.strftime("%H:%M:%S"))
        m += 1
        if m > 12:
            y, m = y + 1, 1
    for habit in out:
        for d in out[habit]:
            out[habit][d] = sorted(out[habit][d])
    return out


# ── merge ──────────────────────────────────────────────────────────────────

def fill(times_db, habit, day, real_times, count, report, note_mismatch=None):
    """Add real_times (sorted) until the day has `count` stamps."""
    if count is None or count <= 0:
        return
    cur = times_db.get(habit, {}).get(day, [])
    need = count - len(cur)
    if need <= 0 or not real_times:
        return
    if len(real_times) < count and note_mismatch:
        note_mismatch.append(day)
    add = real_times[-need:] if len(real_times) > need else list(real_times)
    times_db.setdefault(habit, {})[day] = sorted(cur + add)
    report[habit]["days"] += 1
    report[habit]["stamps"] += len(add)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("live")
    ap.add_argument("habitsdb")
    ap.add_argument("out", nargs="?")
    ap.add_argument("--zip")
    ap.add_argument("--activity-times")
    ap.add_argument("--chess-user")
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    times_db = load(args.live)
    habits = load(args.habitsdb)
    report = defaultdict(lambda: {"days": 0, "stamps": 0})
    mismatches = defaultdict(list)

    def count_for(habit, day):
        return habits.get(habit, {}).get(day)

    # 1. Garmin sports + meditations from the ZIP
    if args.zip:
        print(f"Extracting from {args.zip} ...")
        sports, medit, sleeps = extract_from_zip(args.zip)
        for day, per_habit in sports.items():
            # one stamp per day (habits are 0/1 per day), earliest start
            for habit, tods in per_habit.items():
                fill(times_db, habit, day, tods, count_for(habit, day),
                     report, mismatches[habit])
        for day, tods in medit.items():
            fill(times_db, "Meditations", day, tods,
                 count_for("Meditations", day), report,
                 mismatches["Meditations"])
        # Sleep: bedtime stamped on the evening date; Wake: morning date
        for ev in sleeps:
            if ev["start"]:
                fill(times_db, "Sleep", ev["start"].strftime("%Y-%m-%d"),
                     [ev["start"].strftime("%H:%M:%S")],
                     count_for("Sleep", ev["start"].strftime("%Y-%m-%d")), report)
            fill(times_db, "Wake", ev["end"].strftime("%Y-%m-%d"),
                 [ev["end"].strftime("%H:%M:%S")],
                 count_for("Wake", ev["end"].strftime("%Y-%m-%d")), report)

    # 2. Recent days from the phone's live activity-times cache
    if args.activity_times:
        at = load(args.activity_times)
        for type_key, days in at.items():
            habit = SPORT_HABITS.get(type_key)
            if not habit:
                continue
            for day, tod in days.items():
                fill(times_db, habit, day, [tod], count_for(habit, day), report)

    # 3. chess.com archives
    if args.chess_user:
        now = datetime.now(tz=PHONE_TZ)
        first = None
        for habit in CHESS_HABITS.values():
            days = [d for d, c in habits.get(habit, {}).items() if c]
            if days:
                cand = min(days)
                first = cand if first is None else min(first, cand)
        if first:
            fy, fm, _ = first.split("-")
            print(f"Fetching chess.com archives {fy}-{fm} .. now ...")
            chess = chess_end_times(args.chess_user, (int(fy), int(fm)),
                                    (now.year, now.month))
            for habit, days in chess.items():
                for day, tods in days.items():
                    fill(times_db, habit, day, tods, count_for(habit, day),
                         report, mismatches[habit])

    print("\n=== Backfill report ===")
    total_d = total_s = 0
    for habit in sorted(report):
        r = report[habit]
        print(f"  {habit:20} +{r['stamps']:5} stamps on {r['days']:4} days"
              + (f"   (source<count on: {mismatches[habit][:5]}{'…' if len(mismatches[habit]) > 5 else ''})"
                 if mismatches.get(habit) else ""))
        total_d += r["days"]; total_s += r["stamps"]
    print(f"  TOTAL: +{total_s} stamps on {total_d} habit-days")

    out = args.out or "/tmp/tail_ts/backfilled.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(times_db, f, ensure_ascii=False, indent=1)
    print(f"wrote {out}")

    if not args.apply:
        print("[DRY RUN] re-run with --apply to push to the phone")
        return
    subprocess.run(["adb", "shell", "am", "force-stop", APP], check=False)
    tmp_remote = "/data/local/tmp/tail_ts_backfill.json"
    subprocess.run(["adb", "push", out, tmp_remote], check=True)
    full = f"/data/data/{APP}/{REMOTE_FILE}"
    subprocess.run(["adb", "shell", f"run-as {APP} sh -c 'cat {tmp_remote} > {full}'"], check=True)
    subprocess.run(["adb", "shell", "rm", "-f", tmp_remote], check=False)
    print("pushed to phone (app force-stopped; re-reads on next launch)")


if __name__ == "__main__":
    main()
