#!/usr/bin/env python3
"""
Seed the historical glucose/ketone/GKI blood-test backlog into Tail.

Inputs : the verbatim historical log below (embedded).
Outputs:
  1. files/blood_tests/Blood_Test.json on the phone (via adb run-as) — merged
     with any existing entries (keeps the 2020-02-04 thyroid panel).
  2. /home/twain/habitsdb/habitsdb.txt — "Blood Test" per-date counts set to
     the total number of measured items that day (backup written first).

Item conventions: Glucose (mg/dL), Ketones (mmol/L), GKI (no unit).
Values are parsed from the trailing comma-separated numeric tokens of each
line; fast-hour durations ("85hr", "14:45hr", "~12 hr") and note text
("6 mile run", "3 days") are excluded. 2018-style lines use explicit labels
keto(x) / glu(x) / glucose(x) / gki(x); continuation lines starting with "-"
inherit the date from the header line above them.
"""

import json
import re
import shutil
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

APP_PKG = "com.example.tail"
DEVICE_JSON = "files/blood_tests/Blood_Test.json"
HABITSDB = Path("/home/twain/habitsdb/habitsdb.txt")
HABITSDB_BACKUP = Path("/home/twain/habitsdb/habitsdb_backup_before_bloodtest_seed.txt")
TMP_DIR = Path(__file__).resolve().parent.parent / ".tmp"
LOCAL_JSON = TMP_DIR / "Blood_Test.json"
HABIT_KEY = "Blood Test"

RAW_LOG = """
1/23/21 - 80,.3
1/22/21 - 99,.6
1/21/21 - 73,.8
1/19/21 - 70,1.6
1/16/21 - 12hr fast, high fast low to moderate carbs, moderate excercise, .8,89,6.18gki
1/11/21 - 16hr fast, high fat, low carb, moderate excercise,  71, .8, 4.93gki
1/9/21 - 13hr fast, high fat, moderate carbs, moderate excercise, 76, .4, 10.56gki 
1/8/21 - 17hr fast, moderate endurance exc lately, moderate fat/carb, .9, 72, 4.44gki
1/4/21 - 13hr fast, light run yesterday, moderate fat and carb, 73, .7, 5.79
1/3/21 - 11hr fast, rest day, high fat, low carb, .7, 95, 7.54
1/2/21 - 13hr fast, high fat, moderate carb yesterday, ran half marathon, 67, 1.8, 2.07
12/30/20 - 13hr fast, same.., 103, .2, 28.61
12/28/20 - 13hr fast, same as last entry, 95, .2, 26.39
12/25/20 - 14hr fast, same diet and exercise as last entry 86, .1, 47.8
12/20/20 - 12hr fast, been eating low sugar, moderate carb, moderate fat, a good amount of endurance excessive .4, 73, 10.19
12/8/20 - 14hr fast, been eating low sugar, normal carbs and fat, intermittent fasting, 74, .2, 20.56
10/28/20 - 10hr fast, been eating high fat, but not strict keto, regular moderate cardio, .2, 86, 23.89
9/29/20 - 13hr fast, eating moderate keto lately, moderate cardio, .5, 98, 10.89gki
8/24/20 - 16hr fast, 6 mile run yesterday, and 260 mile bike ride over 3 days before that, eating not extreme keto,  1.9, 89, 2.6gki
8/14/20 - 16hr fast, 5 mile walk yesterday, 5k aerobic run day before, staying on keto diet, 90, .8, 6.25gki
8/12/20 - 15hr fast, anaerobic 5k yesterday, went back to high fat yesterday, 93, .6, 8.61
8/11/20 - 14hr fast, heavy carb and low fat yesterday, 36min hiit yesterday, 100, .4, 13.89gki
8/10/20 - 13hr fast, heavy carb and low fat yesterday, 8 mile aerobic run yesterday, .9, 95, 5.86gki
8/9/20 - 13hr fast, keto, no moderate cardio yesterday, 102, .7, 8.1gki
8/8/20 - 16hr fast, keto and few calories yesterday bc threw up from spicy sauce, moderate cardio yesterday, 96, .8, 6.67gki
8/6/20 - 14hr fast, keto, hour after moderate cardio, 99, .6, 9.17gki
8/6/20 - 11hr fast, keto, moderate cardio yesterday, 101, .7, 8.01gki
8/5/20 - 10hr fast, keto, moderate cardio yesterday, 94, 1.1, 4.75gki
8/4/20 - 11hr fast, keto, but had a low carb cider yesterday, biked 70 miles yesterday, 105, .5, 11.66gki 
8/3/20 - 11hr fast, keto after several days of no cardio, 96, 1, 5.33gki
8/2/20 - 12hr, 107, .8, 7.43gki
8/1/20 - 11hr, 1.5, 97, 3.59gki
7/31/20 - 8hr, yesterday keto with super slightly high late night protein, 99, 1.1, 5gki
7/30/20 - 11hr fast, yesterday was a rest day, 1.5, 95, 3.52gki
7/29/20 - 13hr fast, 80 mile bike yesterday, very keto diet, 2.9, 76, 1.46gki
7/28/20 - 11hr fast, 3 gels yesterday during 5hr 50 mile cycle, high fat/protein after cycle, .8, 91, 6.32gki
7/27/20 - 11.5hr fast, just woke up, .8, 90, 6.25gki
7/26/20 - 2hr fast, just before bed, .5, 86, 9.55gki
7/26/20 - 6hr after honey and a nap,   .5, 106
7/26/20 - 2hr after honey, .2, 100
7/26/20 - 1hr after 2 tablespoons honey, .2, 122
7/26/20 - up all night trpn and eating little snacks, .6, 89
7/25/20 - 17hr fast, 1hr after 6 mile walk, 80, .6, 7.4gki
7/25/20 - 13hr fast, just woke up, 97, .6, 8.98gki
7/24/20 - 15:45hr fast, 1hr after ice bath, 83, .4, 11.53gki
7/24/20 - 14:45hr fast, immediately after ice bath, 91, .6, 8.43gki
7/24/20 - 14hr fast, 96, .5, 10.66gki
7/23/20 - 16hr fast, 83, .7, 6.58gki
7/23/20 - 15hr fast, just walked 5 miles, 89, .2, 24.72gki
7/23/20 - 6hr fast, just woke after ~5hrs of sleep, 90, .5, 10gki
7/22/20  - 1hr after eating in a 5hr keto feeding window, .6, 92, 8.51gki
7/22/20 - 42hrs, right before eating, 75 1.6, 2.6gki
7/22/20 - 38hr, went for short walk, lay around watching TV 81, 1.2, 3.75gki
7/22/20 - 32hr, just woke up, 1.4, 85, 3.37gki
7/21/20 - 20hr, been eating carbs lately,   .7, 82, 6.5
Breaking from bike touring
5/15/20 - 14hr fast, 103, .5, 11.44
5/14/20 - 85hr 58, 2.6, 1.24gki
5/13/20 - 75hr 59, 2.9, 1.13gki
5/13/20 - 62hr 58, 3.2, 1gki 
5/12/20 - 52hr 63, 2.5, 1.4gki
5/12/20 - 36hr, 81, .7, 6.428gki
5/11/20 - 29hr fast, 78, .7, 6.2gki
5/11/20 - 16hr fast, 99, .3, 18.3gki
5/5/20 - 15hr fast, after moderate excersise, low carb diet 97, .8
4/27/20 - 27hr fast, 89, .8
4/19/20 - 13hr fast, eating moderate fat and moderate carbs 101, .2
3/26/20 - 15hr fast, eating moderate high fat, moderate carb for the last few weeks, 98, .2
2/19/20 - 15hr fast, not eating especially keto, yesterday was quite carby, 86, .5
2/14/20 - 14hr fast high fat diet lately, but also moderate carbs, and doing 5hr feeding 97, .4
2/9/20 - 8hr fast after 8hr heavy eating after big fast ended 94, 2.5
2/8/20 - 82hr fast, 56, 3.9
2/7/20 - 60hr fast, 48, 3.8
2/6/20 - 31hr fast 2.8, 65
2/4/20 - ~12 hr fast, not very keto, but high fat intake lately .4, 95
1/23/20 - 15hr fast, moderate excercise, not very keto, .1, 98
1/10/20 - 15hr fast, not very keto day, .3, 106
1/7/20 - ~13hr fast, keto, .7, 100
1/1/20 - 13hr fast, keto, .4, 86
12/29/19 - 14hr fast, keto, .3, 92
12/26/19 - 10hr fast, higher than average carbs yesterday, .3, 106
12/23/19 - 13hr fast, still keto, .3, 104
12/22/19 - 12hr fast, been keto for a few days, .9, 98
12/17/19 - 17hr fast, little while after bike ride,  .2, 93
12/16/19 - 11hr fast, .3, 87
TRANSITIONING TO KETO
12/15/19 - 2hr fast, .2, 115

8/8/18 - 13hr fast, resting, keto(2), glucose(80)
8/8/18 - after large avacado salad and nut butter, resting, keto(0.9)
8/8/18 - after 1 scoop ketocana + resteraunt thai red curry, keto(2.2)
8/9/18 - 16hr fast, after 15min after excercise, keto(.3), glucose(110)
8/9/18 - 1hr after large avacado salad, glucose(83), keto(.4)
8/9/18 - 2hr after salad, keto(1.2), glucose (88)
8/10/18 - 12hr fast, resting, keto(.9)
8/10/18 - 6hr fast, resting, evening, keto(2.9)
8/11/18 - 4hr fast since resteraunt thai peanut curry with 5 tablespoons of coconut oil, after poker, evening, keto(1.7), glucose(78)
8/11/18 - 1hr after last test, just finished hot bath followed by cold shower, keto(1.9)
8/13/18 - late night, 7hr fast, keto(1.2)
8/13/18 - 4hr fast, resting, after cold shower, keto(3.7), glucose(68)
8/14/18 - 10hr fast, resting, keto(3.4)
8/14/18 - 1.5hrs since final meal, resting, keto(2.5)
8/16/18 - 5hr fast, resting, keto(2.7)
8/18/18 - 6hr fast, resting, keto(6.1), glucose (75)
8/19/18 - 17hr fast, resting, keto(2.4),  glucose(79)
8/19/18 - just after light meal, keto(3.2), glucose(88) 
8/21/18 - fasting 14hr, keto(2.8),glu(73)
8/22/18 - fasting 10hr, just woke up, keto(4.5), glu(77)
8/25/18 - fasting 4hrs, but only ate a little sugary chocolate and nuts, before that tasted additional 6 hours, but had more sugary chocolate and nuts and vegan cookie bite and blueberries, keto (3.1)
8/26/18 - 5hr fast,keto(1.6),glu(85)
8/28/18 -16hr fast,woke up, meditated, did short apnea training, pooped, keto(1.3),glu(93)
8/29/18 -16hr fast, just woke up, keto(2.0),glu(86)
8/29/18 -6hr fast, keto(2.1), glu(85)
8/30/18 - 21hr fast, keto(2.1),glu(69)
8/30/18 - 27hr fast, keto(3.0),glu(62)
9/1/18 - 4hr fast,keto(1.3),glu(97)
9/4/18
 - 17hr fast,keto(1.1),glu(89)
- 22hr fast,keto(1.6),glu(70)
- 28hr fast,keto(2.2)
9/5/18 
- 36hr fast, keto(2.7),glu(69), gki(1.4)
- 45hr fast, keto(2.8), glu(67), gki(1.33)
- 49hr fast, keto(4.6), glu(55), gki(.66)
9/6/18 
- 54.5hr fast, keto(4.9), glu(58), gki(.66) 
- 62hr fast, keto(3.4),glu(68), gki(1.11)
-66hr fast,after workout,keto(2.4), glu(93),gki(2.15)
-72hr fast,keto(3.4), glu(74), gki(1.2)
-75.5hr fast,keto(3.9),glu(62),gki(.88)
9/7/18 
- 86hr fast, just woke up, keto(3.8), glu(51), gki(.75)
- 92hr fast, keto(3.8), glu(64),gki(.94)
-99.75hr fast, keto(6.9),glu(45),gki(.36)
9/8/18
-108hr fast, keto(4.9),glu(49),gki(.56)
9/9/18
-125hr fast, keto(6.1), glu(44), gki(.4)
-144.5hr fast keto(6.3), glu(42), gki(.37)
-155.5 fast keto(5.6), glu(43), gki(.43)
9/10/18
6:20pm small keto meals all day, keto(6.6), glu(70), gki(.59) 
9/11/18
9:15a 14hr fast, keto(4.2), glu(79), gki(1.04)
9/13/18
11:50 7hr fast, keto(3.8), glu(74), gki(1.08) 
9/19/18 11hr fast, but I have been eating moderate carbs for several days now, keto(.3), glu(81)
"""

# duration / clock tokens never treated as measurements
NON_VALUE = re.compile(r"^~?\d+(?:\.\d+)?(?::\d{1,2})?\s*(?:hr|hrs)$", re.I)
CLOCK = re.compile(r"^\d{1,2}:\d{2}(?:\s*(?:a|am|p|pm))?$", re.I)
DATE_RE = re.compile(r"^(\d{1,2})/(\d{1,2})/(\d{2})\b")
LABEL_RE = re.compile(
    r"(keto(?:nes)?)\s*\(\s*([\d.]+)\s*\)|(glu(?:cose)?)\s*\(\s*([\d.]+)\s*\)|gki\s*\(\s*([\d.]+)\s*\)",
    re.I,
)


def to_iso(m, d, y2):
    return f"{2000 + int(y2):04d}-{int(m):02d}-{int(d):02d}"


def clean_token(tok: str) -> list[str] | None:
    """Return the numeric value string(s) inside a trailing token, or None if
    the token carries no value (pure note text). A token is a value carrier
    when it's entirely numeric/duration chunks ("75 1.6", "62hr 58") — in
    which case every numeric chunk counts — or when it merely ENDS with a
    number ("ended 94", "lately .4", "excessive .4") — in which case only the
    trailing number counts and the words stay in the note."""
    stripped = tok
    if stripped.lower().endswith("gki"):
        stripped = stripped[:-3]
    parts = stripped.split()
    vals: list[str] = []
    all_numeric = True
    for p in parts:
        if NON_VALUE.match(p) or CLOCK.match(p):
            continue
        try:
            float(p)
        except ValueError:
            all_numeric = False
        else:
            vals.append(p)
    if all_numeric and vals:
        return vals
    # fallback: token ends with a bare number glued to note words
    m = re.search(r"(\d+(?:\.\d+)?|\.\d+)\s*$", stripped)
    return [m.group(1)] if m else None


def classify(values: list[str], labeled_gki: str | None):
    """Assign glucose / ketones / gki from ordered bare numeric tokens."""
    glucose = ketones = gki = None
    if labeled_gki is not None:
        gki = labeled_gki
    for v in values:
        f = float(v)
        if f >= 40:
            if glucose is None:
                glucose = v
            elif gki is None:
                gki = v
        else:  # ketone range (0.1–6.9, sometimes bare "1")
            if ketones is None:
                ketones = v
            elif gki is None:
                gki = v
    return glucose, ketones, gki


def parse_log(raw: str):
    entries = []  # (date, note, {Glucose,Ketones,GKI})
    unparsed = []
    current_date = None
    for raw_line in raw.strip().splitlines():
        line = raw_line.strip()
        if not line:
            continue
        dm = DATE_RE.match(line)
        if dm:
            current_date = to_iso(*dm.groups())
            body = line[dm.end():].lstrip(" -–").strip()
        else:
            body = line.lstrip("- ").strip() if line.startswith("-") else line
            if current_date is None:
                unparsed.append(raw_line)
                continue
            if not line.startswith("-") and not re.search(r"\d", line):
                continue  # section header ("Breaking from bike touring")
            if not body:
                continue
        if not body:
            continue

        # labeled 2018-style values
        labeled = {"ketones": None, "glucose": None, "gki": None}
        note = body
        for m in LABEL_RE.finditer(body):
            if m.group(1):
                labeled["ketones"] = m.group(2)
            elif m.group(3):
                labeled["glucose"] = m.group(4)
            else:
                labeled["gki"] = m.group(5)
            note = note.replace(m.group(0), " ")
        note = re.sub(r"\s{2,}", " ", note).strip(" ,.")

        # trailing bare numeric tokens (2019-21 style)
        values: list[str] = []
        trailing_gki = None
        toks = [t for t in note.split(",") if t != ""]
        # walk tokens from the end while they're purely numeric
        i = len(toks)
        while i > 0:
            vals = clean_token(toks[i - 1])
            if vals is None:
                break
            values = vals + values
            i -= 1
        if values:
            # rebuild the note: drop the value digits but keep any note words
            # glued to them ("31hr fast2.8" → "31hr fast", "ended94" → "ended")
            leftovers = []
            for j in range(i, len(toks)):
                t = toks[j]
                for v in clean_token(t) or []:
                    t = t[::-1].replace(v[::-1], "", 1)[::-1]
                t = t.strip(" ,.")
                if t.lower() in {"gki", "~", ""}:
                    continue
                if re.search(r"[A-Za-z]", t):
                    leftovers.append(t)
            note = ",".join(toks[:i] + leftovers).strip(" ,.")
            # a bare trailing "gki"-suffixed value keeps its label
            g = re.search(r"([\d.]+)\s*gki\s*$", body, re.I)
            trailing_gki = g.group(1) if g else None
            glucose, ketones, gki = classify(values, trailing_gki)
            if labeled["ketones"]:
                ketones = ketones or labeled["ketones"]
            if labeled["glucose"]:
                glucose = glucose or labeled["glucose"]
        else:
            glucose, ketones, gki = (
                labeled["glucose"], labeled["ketones"], labeled["gki"],
            )

        items = {}
        if glucose is not None:
            items["Glucose"] = (float(glucose), "mg/dL")
        if ketones is not None:
            items["Ketones"] = (float(ketones), "mmol/L")
        if gki is not None:
            items["GKI"] = (float(gki), None)
        if not items:
            unparsed.append(raw_line)
            continue
        entries.append((current_date, note if note else None, items))
    return entries, unparsed


def main():
    print("=" * 60)
    print("BLOOD TEST BACKLOG SEED")
    print("=" * 60)
    dry_run = "--dry-run" in sys.argv
    entries, unparsed = parse_log(RAW_LOG)
    print(f"\n[1] Parsed {len(entries)} entries "
          f"({min(e[0] for e in entries)} → {max(e[0] for e in entries)}), "
          f"{sum(len(e[2]) for e in entries)} items total")
    counts: dict[str, int] = defaultdict(int)
    for _, _, items in entries:
        pass
    for date, _, items in entries:
        counts[date] += len(items)
    print(f"    {len(counts)} distinct dates; sample:")
    if dry_run:
        print(f"\n[DRY RUN] Full list of everything that would be injected "
              f"({sum(len(e[2]) for e in entries)} items):\n")
        last_date = None
        for date, note, items in sorted(entries, key=lambda e: e[0]):
            vals = ", ".join(
                f"{n}={v}{' ' + u if u else ''}" for n, (v, u) in sorted(items.items())
            )
            prefix = date if date != last_date else " " * 10
            last_date = date
            print(f"  {prefix}  {vals}")
            if note:
                print(f"  {' ' * 10}  note: {note}")
        print(f"\n[DRY RUN] Per-date habit counts that would be written "
              f"(count = total items that day, incl. existing entries):")
        day_counts: dict[str, int] = defaultdict(int)
        for date, _, items in entries:
            day_counts[date] += len(items)
        for date in sorted(day_counts):
            print(f"  {date}: +{day_counts[date]}")
        if unparsed:
            print(f"\n  ⚠ {len(unparsed)} UNPARSED lines:")
            for u in unparsed:
                print(f"    {u!r}")
        print("\n[DRY RUN] Nothing was written.")
        return 0 if not unparsed else 1
    for date, note, items in entries[:3] + entries[-3:]:
        print(f"      {date}: {items}" + (f"  | note: {note!r}" if note else ""))
    if unparsed:
        print(f"\n    ⚠ {len(unparsed)} UNPARSED lines:")
        for u in unparsed:
            print(f"      {u!r}")

    # ── load device JSON (keeps existing entries, e.g. 2020-02-04 panel) ──
    print("\n[2] Pulling current Blood_Test.json from device…")
    res = subprocess.run(
        ["adb", "exec-out", "run-as", APP_PKG, "cat", DEVICE_JSON],
        capture_output=True, text=True,
    )
    existing = json.loads(res.stdout) if res.stdout.strip() else []
    # drop any previous run's seed entries so a re-run fully replaces them
    existing = [e for e in existing if not str(e.get("id", "")).startswith("seed-")]
    existing_ids = {e.get("id") for e in existing}

    new_entries = []
    for date, note, items in entries:
        new_entries.append({
            "id": f"seed-{date}-{len(new_entries)}-{abs(hash(json.dumps(items, sort_keys=True))) % 99999}",
            "date": date,
            "time": None,
            "source": "seed",
            "rawText": None,
            "note": note,
            "items": [
                {"name": name, "value": value,
                 "unit": unit, "range": None, "flag": None}
                for name, (value, unit) in sorted(items.items())
            ],
        })
    new_entries = [e for e in new_entries if e["id"] not in existing_ids]
    merged = sorted(
        existing + new_entries,
        key=lambda e: (e["date"], e.get("time") or ""),
        reverse=True,
    )
    print(f"    existing={len(existing)} new={len(new_entries)} merged={len(merged)}")

    # ── update habitsdb counts ──
    print("\n[3] Updating habitsdb.txt (backup first)…")
    shutil.copy2(HABITSDB, HABITSDB_BACKUP)
    with open(HABITSDB) as f:
        habits = json.load(f)
    bt = habits.setdefault(HABIT_KEY, {})
    day_items: dict[str, int] = defaultdict(int)
    for e in merged:
        day_items[e["date"]] += len(e["items"])
    changed = 0
    for date, n in sorted(day_items.items()):
        if bt.get(date) != n:
            print(f"      {date}: {bt.get(date, 0)} → {n}")
            bt[date] = n
            changed += 1
    with open(HABITSDB, "w") as f:
        json.dump(habits, f, indent=2)
    print(f"    habitsdb updated ({changed} dates changed); backup: {HABITSDB_BACKUP}")

    # ── write + push JSON ──
    print("\n[4] Writing merged JSON and pushing to device…")
    TMP_DIR.mkdir(exist_ok=True)
    with open(LOCAL_JSON, "w") as f:
        json.dump(merged, f, indent=2)
    subprocess.run(["adb", "push", str(LOCAL_JSON), "/data/local/tmp/Blood_Test.json"], check=True)
    subprocess.run(
        ["adb", "shell", "run-as", APP_PKG, "cp",
         "/data/local/tmp/Blood_Test.json", DEVICE_JSON],
        check=True,
    )
    subprocess.run(["adb", "shell", "rm", "/data/local/tmp/Blood_Test.json"], check=True)
    print("    pushed ✓ (restart the Tail app if it's running so it re-reads the file)")

    print("\n" + "=" * 60)
    print(f"DONE — {len(new_entries)} entries / {sum(counts.values())} items seeded")
    print("=" * 60)
    return 0 if not unparsed else 1


if __name__ == "__main__":
    sys.exit(main())
