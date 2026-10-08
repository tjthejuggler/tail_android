#!/usr/bin/env python3
"""
One-off (2026-10-07): import historical sleep data from the Sleep2 app export
into the Tail sleep-suite habits ("Sleep" = bed-time variant, "Wake" =
wake-time variant).

SOURCE
    /home/twain/Downloads/breathlessthan@202609281148(in).csv
    213 nights, 2026-02-19 .. 2026-09-28, single user (breathlessthan@gmail.com).

FIELD MAPPING (Sleep2 -> Tail SleepRecord, see
core-data/.../health/SleepDataRepository.kt)
    bed_time      -> Sleep.beds/bed   (INTENDED bedtime as told to the app,
                                      NOT the HR-detected sleep onset in
                                      sleep_time) keyed on diary_date - 1
                                      (evening date; Tail sessions are keyed
                                      by the bed entry's date)
    wake_time     -> Wake.wakes/wake  keyed on diary_date (morning date)
    sleep_interruption_count    -> Wake.awakenings
    sleep_interruption_minutes  -> Wake.awakeMin
    sleep_quality (1-10)         -> Wake.quality
    sleep_factors (tag list)     -> Sleep.conditions (comma-joined; German
                                    catalog names translated to English via
                                    Sleep2_factors.csv)
    NOTE: the Sleep2 export contains NO room-temperature column (all 113
    columns checked), so no temp is imported.

    Stat variants (stat_sleep_interruption_count etc.) are used as fallback
    when the subjective fields are empty.

SAFETY RULES (per user request)
    * Existing Tail sleep_data.json fields are NEVER overwritten — the import
      only fills fields that are null/absent (SleepRecord.merge semantics).
    * Existing habitsdb.txt counts for Sleep/Wake are never changed; counts
      are only ADDED for dates that have none.
    * habit_timestamps.json only gets new times appended for dates that had
      none (stamp = bed HH:mm:ss on the bed date / wake HH:mm:ss on the wake
      date).

USAGE
    python3 scripts/import_sleep2_20261007.py            # dry run (local files)
    python3 scripts/import_sleep2_20261007.py --pull     # pull live files via adb run-as
    python3 scripts/import_sleep2_20261007.py --apply    # pull, patch, push back
"""

import csv
import json
import re
import shutil
import subprocess
import sys
from datetime import date, timedelta
from pathlib import Path

CSV_PATH = Path("/home/twain/Downloads/breathlessthan@202609281148(in).csv")
FACTORS_PATH = Path("/home/twain/Downloads/Sleep2_factors.csv")

HABITSDB = Path("/home/twain/habitsdb/habitsdb.txt")
BACKUP_SUFFIX = ".bak_pre_sleep2_import_20261007"

APP = "com.example.tail"
SLEEP_JSON_REMOTE = "files/sleep_data.json"
TIMESTAMPS_REMOTE = "files/habit_timestamps.json"

LOCAL_SLEEP = Path("/tmp/sleep_data_current.json")
LOCAL_TS = Path("/tmp/habit_timestamps_current.json")

SLEEP_HABIT = "Sleep"
WAKE_HABIT = "Wake"

# Manual translations for factor tags seen in the data that are not (or not
# cleanly) covered by Sleep2_factors.csv.
EXTRA_TRANSLATIONS = {
    "Bettpartner:in": "Bed partner",
    "Bewegung (im Freien)": "Movement (outdoors)",
    "(Arbeits)freier Tag": "Work-free day",
    "Mittagschlaf": "Nap",
    "langer Tagesschlaf": "Long daytime sleep",
    "Morgens Sonne": "Morning sunlight",
    "Zu hell, warm oder laut": "Too bright, warm, or noisy",
    "erhöhtes Arbeitspensum": "Increased workload",
    "erhöhter Stress": "Increased stress",
    "erhöhte Bildschirmzeit": "Increased screen time",
    "Smartphone/Tablet im Bett": "Smartphone/Tablet in bed",
    "gesunde Ernährung": "Healthy nutrition",
    "schlechte Ernährung": "Unhealthy nutrition",
    "späte Abendmahlzeit": "Late evening meal",
    "Krankheitssymptome": "Illness symptoms",
    "Psychische Belastungen": "Psychological stress",
    "erhöhte Motivation": "Increased motivation",
    "Andere Medikamente": "Other medications",
    "LowEnergy Tag": "Low-energy day",
    "Konflikte, Streit oder Sorgen": "Conflicts, arguments, or worries",
    "Soziale Kontakte": "Social contacts",
}


def fix_mojibake(s: str) -> str:
    """Repair UTF-8-read-as-latin1 double encoding ('FreizeitaktivitÃ¤t')."""
    if not s:
        return s
    try:
        fixed = s.encode("latin-1").decode("utf-8")
        # only accept when it removed the tell-tale Ã/Â artifacts
        if "Ã" not in fixed and "Â" not in fixed and ("Ã" in s or "Â" in s):
            return fixed
        return fixed if ("Ã" in s or "Â" in s) else s
    except (UnicodeEncodeError, UnicodeDecodeError):
        return s


def strip_quotes(s: str) -> str:
    s = s.strip()
    if len(s) >= 2 and s[0] == "'" and s[-1] == "'":
        return s[1:-1]
    return s


def hhmm_to_minutes(t: str):
    t = strip_quotes(t)
    if not t:
        return None
    m = re.match(r"^(\d{1,2}):(\d{2})(?::(\d{2}))?$", t)
    if not m:
        return None
    h, mi = int(m.group(1)), int(m.group(2))
    if h > 23 or mi > 59:
        return None
    return h * 60 + mi


def parse_bool(s: str):
    return strip_quotes(s).upper() == "TRUE"


def parse_int(s: str):
    s = strip_quotes(s)
    if not s:
        return None
    try:
        v = float(s)
        return int(round(v))
    except ValueError:
        return None


def load_factors_map() -> dict:
    """id -> English name, from the ';'-separated factors CSV."""
    mapping = {}
    if FACTORS_PATH.exists():
        with open(FACTORS_PATH, encoding="utf-8-sig", errors="replace") as f:
            for line in f:
                parts = line.rstrip("\n").split(";")
                if len(parts) >= 3 and len(parts[0]) == 36:
                    de = fix_mojibake(parts[1].strip())
                    en = fix_mojibake(parts[2].strip())
                    if en:
                        mapping[de] = en
    mapping.update(EXTRA_TRANSLATIONS)
    return mapping


def translate_conditions(factors_json: str, factors_map: dict):
    """Parse the sleep_factors JSON list and map to English condition names."""
    raw = strip_quotes(factors_json or "")
    if not raw:
        return None
    try:
        tags = json.loads(raw)
    except json.JSONDecodeError:
        return None
    if not isinstance(tags, list):
        return None
    out = []
    for tag in tags:
        if not isinstance(tag, str) or not tag.strip():
            continue
        tag = fix_mojibake(tag.strip())
        out.append(factors_map.get(tag, tag))
    # dedupe, keep order
    seen, deduped = set(), []
    for t in out:
        if t not in seen:
            seen.add(t)
            deduped.append(t)
    return ", ".join(deduped) if deduped else None


def sh(cmd: list, binary=False):
    res = subprocess.run(cmd, capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(f"{cmd} failed: {res.stderr.decode(errors='replace')[:300]}")
    return res.stdout if binary else res.stdout.decode(errors="replace")


def pull(device: str):
    # NOTE: `run-as sh -c 'cat ...` truncates large files over exec-out;
    # the direct `run-as cat` form is binary-safe (verified 699807/699807 B).
    for local, remote in ((LOCAL_SLEEP, SLEEP_JSON_REMOTE), (LOCAL_TS, TIMESTAMPS_REMOTE)):
        out = subprocess.run(
            ["adb", "-s", device, "exec-out", "run-as", APP, "cat", remote],
            capture_output=True)
        local.write_bytes(out.stdout)
    print(f"Pulled {SLEEP_JSON_REMOTE} ({LOCAL_SLEEP.stat().st_size} B) "
          f"and {TIMESTAMPS_REMOTE} ({LOCAL_TS.stat().st_size} B)")


def push(device: str):
    subprocess.run(["adb", "-s", device, "shell", "am", "force-stop", APP], check=True)
    for local, remote in ((LOCAL_SLEEP, SLEEP_JSON_REMOTE), (LOCAL_TS, TIMESTAMPS_REMOTE)):
        tmp = "/data/local/tmp/" + Path(remote).name
        subprocess.run(["adb", "-s", device, "push", str(local), tmp], check=True, capture_output=True)
        subprocess.run(["adb", "-s", device, "shell", "run-as", APP, "cp", tmp, remote], check=True)
        subprocess.run(["adb", "-s", device, "shell", "rm", tmp], check=True)
    print("Pushed sleep_data.json + habit_timestamps.json to phone (app force-stopped first).")


def merge_record(existing: dict, patch: dict) -> dict:
    """Tail SleepRecord.merge semantics: non-null patch fields win."""
    merged = dict(existing)
    for k, v in patch.items():
        if v is not None:
            merged[k] = v
    return merged


def main():
    apply_mode = "--apply" in sys.argv
    pull_mode = apply_mode or "--pull" in sys.argv

    device = None
    if pull_mode:
        devices = [l.split("\t")[0] for l in sh(["adb", "devices"]).splitlines()
                   if "\tdevice" in l]
        if not devices:
            print("ERROR: no adb device online. Retry when the phone is reachable.")
            sys.exit(1)
        device = devices[0]
        pull(device)

    factors_map = load_factors_map()

    # ── Load sources ──────────────────────────────────────────────────────
    nights = []
    with open(CSV_PATH, encoding="utf-8-sig") as f:
        for row in csv.DictReader(f):
            d = strip_quotes(row.get("diary_date") or "")
            m = re.match(r"^(\d{4})-(\d{2})-(\d{2})$", d)
            if not m:
                continue
            diary = date(int(m.group(1)), int(m.group(2)), int(m.group(3)))
            nights.append((diary, row))
    nights.sort(key=lambda x: x[0])
    print(f"Sleep2 nights: {len(nights)} ({nights[0][0]} .. {nights[-1][0]})")

    if not LOCAL_SLEEP.exists() or LOCAL_SLEEP.stat().st_size == 0:
        print(f"ERROR: {LOCAL_SLEEP} missing/empty — run with --pull while the phone is online.")
        sys.exit(1)
    sleep_data = json.loads(LOCAL_SLEEP.read_text() or "{}")
    if not LOCAL_TS.exists() or LOCAL_TS.stat().st_size == 0:
        print(f"ERROR: {LOCAL_TS} missing/empty — run with --pull while the phone is online.")
        sys.exit(1)
    timestamps = json.loads(LOCAL_TS.read_text() or "{}")

    habitsdb = json.loads(HABITSDB.read_text())
    sleep_habit_data = sleep_data.setdefault(SLEEP_HABIT, {})
    wake_habit_data = sleep_data.setdefault(WAKE_HABIT, {})
    sleep_counts = habitsdb.setdefault(SLEEP_HABIT, {})
    wake_counts = habitsdb.setdefault(WAKE_HABIT, {})
    sleep_ts = timestamps.setdefault(SLEEP_HABIT, {})
    wake_ts = timestamps.setdefault(WAKE_HABIT, {})

    stats = {"bed_new": 0, "bed_filled": 0, "bed_kept": 0,
             "wake_new": 0, "wake_filled": 0, "wake_kept": 0,
             "cond_added": 0, "survey_filled": 0}

    def hhmmss(minutes: int) -> str:
        return f"{minutes // 60:02d}:{minutes % 60:02d}:00"

    for diary, row in nights:
        bed_date = (diary - timedelta(days=1)).isoformat()
        wake_date = diary.isoformat()

        bed_min = hhmm_to_minutes(row.get("bed_time") or "")
        wake_min = hhmm_to_minutes(row.get("wake_time") or "")
        conditions = translate_conditions(row.get("sleep_factors") or "", factors_map)

        awakenings = parse_int(row.get("sleep_interruption_count") or "")
        if awakenings is None:
            awakenings = parse_int(row.get("stat_sleep_interruption_count") or "")
        awake_min = parse_int(row.get("sleep_interruption_minutes") or "")
        if awake_min is None:
            awake_min = parse_int(row.get("stat_sleep_interruption_minutes") or "")
        quality = parse_int(row.get("sleep_quality") or "")
        if quality is not None and not (1 <= quality <= 10):
            quality = None

        # ── Sleep (bed) half ──────────────────────────────────────────────
        if bed_min is not None:
            existing = sleep_habit_data.get(bed_date, {})
            existing_beds = existing.get("beds") or ([existing["bed"]] if existing.get("bed") is not None else [])
            patch = {}
            if not existing_beds:
                patch["bed"] = bed_min
                patch["beds"] = [bed_min]
                stats["bed_new"] += 1
            else:
                stats["bed_kept"] += 1  # never change existing bed times
            if conditions:
                have = [c.strip().lower() for c in (existing.get("conditions") or "").split(",") if c.strip()]
                new = [c.strip() for c in conditions.split(",") if c.strip()]
                add = [c for c in new if c.lower() not in have]
                if add:
                    merged = (existing.get("conditions") or "").strip()
                    patch["conditions"] = (merged + ", " if merged else "") + ", ".join(add)
                    stats["cond_added"] += 1
            if patch:
                sleep_habit_data[bed_date] = merge_record(existing, patch)
            # habitsdb count + timestamp only when the date was untouched
            if bed_date not in sleep_counts:
                sleep_counts[bed_date] = 1
                sleep_ts.setdefault(bed_date, [])
                if hhmmss(bed_min) not in sleep_ts[bed_date]:
                    sleep_ts[bed_date].append(hhmmss(bed_min))

        # ── Wake half ─────────────────────────────────────────────────────
        if wake_min is not None:
            existing = wake_habit_data.get(wake_date, {})
            existing_wakes = existing.get("wakes") or ([existing["wake"]] if existing.get("wake") is not None else [])
            patch = {}
            if not existing_wakes:
                patch["wake"] = wake_min
                patch["wakes"] = [wake_min]
                stats["wake_new"] += 1
            else:
                stats["wake_kept"] += 1
            # survey fields: only fill gaps, never overwrite
            for field, val in (("awakenings", awakenings),
                               ("awakeMin", awake_min),
                               ("quality", quality)):
                if val is not None and existing.get(field) is None:
                    patch[field] = val
                    stats["survey_filled"] += 1
            if patch:
                wake_habit_data[wake_date] = merge_record(existing, patch)
            if wake_date not in wake_counts:
                wake_counts[wake_date] = 1
                wake_ts.setdefault(wake_date, [])
                if hhmmss(wake_min) not in wake_ts[wake_date]:
                    wake_ts[wake_date].append(hhmmss(wake_min))

    print("Stats:", json.dumps(stats))
    print(f"Sleep habit dates now: {len(sleep_habit_data)}, Wake habit dates now: {len(wake_habit_data)}")

    # sample of imported rows for eyeball verification
    sample = sorted(sleep_habit_data)[:3] + sorted(sleep_habit_data)[-3:]
    for d in sample:
        print(" Sleep", d, json.dumps(sleep_habit_data[d], ensure_ascii=False))
    sample = sorted(wake_habit_data)[:3] + sorted(wake_habit_data)[-3:]
    for d in sample:
        print(" Wake ", d, json.dumps(wake_habit_data[d], ensure_ascii=False))

    if not apply_mode:
        print("\nDRY RUN — nothing written. Re-run with --apply to write + push.")
        return

    # ── Write back ────────────────────────────────────────────────────────
    shutil.copy2(HABITSDB, str(HABITSDB) + BACKUP_SUFFIX)
    HABITSDB.write_text(json.dumps(habitsdb, ensure_ascii=False, indent=2))
    print(f"habitsdb.txt updated (backup: {HABITSDB.name}{BACKUP_SUFFIX})")

    LOCAL_SLEEP.write_text(json.dumps(sleep_data, ensure_ascii=False, indent=2))
    LOCAL_TS.write_text(json.dumps(timestamps, ensure_ascii=False, indent=2))
    push(device)
    print("Done. Open the Tail app and check the sleep timeline graph.")


if __name__ == "__main__":
    main()
