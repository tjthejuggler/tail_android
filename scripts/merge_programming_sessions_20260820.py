#!/usr/bin/env python3
"""
merge_programming_sessions_20260820.py

One-off data migration (2026-08-20): the PC bubble widget's window
auto-detect used to stop/start the "Programming sessions" timer on every
focus change, producing bursts of sub-minute increments that were each
sent to the phone as separate taps (cluttering the increment history).
The widget now waits for a grace period of inactivity before completing
a session, so bursts within that window count as ONE increment (60 s
initially, then raised to 180 s the same day — this script was re-run
with --gap 180 on the already-merged data, which is safe because chain
starts of the coarser partition are a subset of the finer one).

This script retroactively merges today's fragmented increments:

  1. habit_timestamps.json (phone, app-internal):
     chains of timestamps where consecutive starts are <= the gap apart
     collapse into the first timestamp of each chain.
  2. habitsdb.txt (phone /storage/emulated/0/Documents/habitsdb/ +
     PC ~/habitsdb/ synced copy):
     "Programming sessions" count for today drops by the number of
     merged-away timestamps (one increment per chain). The
     "minutes:Programming sessions" slot is left untouched — the merged
     bursts were sub-minute taps that never carried minutes.

Usage:
    python3 merge_programming_sessions_20260820.py [--gap 180]      # dry-run
    python3 merge_programming_sessions_20260820.py --apply [--gap 180]
"""

import json
import subprocess
import sys
from datetime import datetime

HABIT = 'Programming sessions'
TARGET_DATE = '2026-08-20'
MERGE_GAP_S = 180         # consecutive starts <= gap apart -> same session

PKG = 'com.example.tail'
PHONE_HABITSDB = '/storage/emulated/0/Documents/habitsdb/habitsdb.txt'
PC_HABITSDB = '/home/twain/habitsdb/habitsdb.txt'

STAMPS_LOCAL = '/tmp/habit_timestamps_phone.json'
HABITSDB_LOCAL = '/tmp/habitsdb_phone_live.txt'


def sh(cmd, input_bytes=None, binary=False):
    """Run a shell command, return stdout (str or bytes)."""
    res = subprocess.run(cmd, shell=True, input=input_bytes,
                         capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(f'{cmd!r} failed: {res.stderr.decode()[:400]}')
    return res.stdout if binary else res.stdout.decode()


def pull_timestamps():
    """Binary-safe pull of the app-internal timestamps file.

    `adb shell cat` runs through a pty that corrupts LF -> CRLF, so the
    exec-out transport is used instead.
    """
    return sh(f'adb exec-out run-as {PKG} cat files/habit_timestamps.json',
              binary=True)


def push_timestamps(payload: bytes):
    """Push bytes into the app-internal file without pty corruption.

    adb push cannot target app-internal storage, and stdin through the
    pty mangles newlines — so stage the file in /data/local/tmp and copy
    it in as the app via run-as.
    """
    open('/tmp/hts_fixed.json', 'wb').write(payload)
    sh('adb push /tmp/hts_fixed.json /data/local/tmp/hts_fixed.json')
    sh(f'adb shell run-as {PKG} cp /data/local/tmp/hts_fixed.json '
       f'files/habit_timestamps.json')
    sh('adb shell rm /data/local/tmp/hts_fixed.json')


def to_secs(t):
    h, m, s = t.split(':')
    return int(h) * 3600 + int(m) * 60 + int(s)


def merge_runs(times, gap):
    """Collapse chains of timestamps whose consecutive gap <= `gap`.

    Chaining is transitive (mirrors the widget's new grace logic: every
    resume within the grace period extends the same session). Returns
    the kept timestamps, a parallel list of keep/merge flags for the
    sorted input, and the number merged away.
    """
    kept, flags, merged_away, prev = [], [], 0, None
    for t in sorted(times):
        if prev is not None and to_secs(t) - to_secs(prev) <= gap:
            merged_away += 1          # same session as the previous start
            flags.append(False)
        else:
            kept.append(t)
            flags.append(True)
        prev = t
    return kept, flags, merged_away


def dump_identical(raw: bytes, data) -> bool:
    """The app (Gson pretty / json.dump indent=2) writes no trailing
    newline — a faithful round-trip must reproduce the raw bytes."""
    return json.dumps(data, indent=2).encode() == raw


def gap_from_argv():
    if '--gap' in sys.argv:
        i = sys.argv.index('--gap')
        return int(sys.argv[i + 1])
    return MERGE_GAP_S


def main():
    apply = '--apply' in sys.argv
    gap = gap_from_argv()

    # ── fresh pulls ────────────────────────────────────────────────────
    ts_raw = pull_timestamps()
    open(STAMPS_LOCAL, 'wb').write(ts_raw)
    sh(f'adb pull {PHONE_HABITSDB} {HABITSDB_LOCAL}')
    db_raw = open(HABITSDB_LOCAL, 'rb').read()

    stamps = json.loads(ts_raw)
    habits = json.loads(db_raw)

    # formatting fidelity: a full re-dump must be byte-identical, so the
    # only diff the phone/PC sync sees is the merged values themselves
    for name, raw, data in (('habit_timestamps.json', ts_raw, stamps),
                            ('habitsdb.txt', db_raw, habits)):
        if not dump_identical(raw, data):
            sys.exit(f'ABORT: {name} does not round-trip with indent=2 — '
                     'hand-off to a targeted text edit needed')

    today = stamps.get(HABIT, {}).get(TARGET_DATE, [])
    if not today:
        sys.exit(f'No {HABIT!r} timestamps for {TARGET_DATE} — nothing to do')

    kept, flags, merged_away = merge_runs(today, gap)
    print(f'{HABIT!r} {TARGET_DATE} (gap {gap}s): {len(today)} timestamps '
          f'-> {len(kept)} (merging {merged_away})')
    for t, keep in zip(sorted(today), flags):
        print(f'  {"keep" if keep else "MERGE":5s} {t}')

    count_key = habits.get(HABIT, {}).get(TARGET_DATE)
    if count_key is None:
        sys.exit(f'ABORT: no {HABIT!r} count for {TARGET_DATE} in habitsdb')
    new_count = count_key - merged_away
    print(f'habitsdb count {TARGET_DATE}: {count_key} -> {new_count} '
          f'(minutes slot untouched: '
          f'{habits.get("minutes:" + HABIT, {}).get(TARGET_DATE)})')
    if new_count < 0:
        sys.exit('ABORT: merge would drive the count negative')

    if not apply or merged_away == 0:
        print('\nDry-run complete (no changes written). '
              'Re-run with --apply to push the fix.')
        return

    # ── build fixed payloads ──────────────────────────────────────────
    stamps[HABIT][TARGET_DATE] = kept
    habits[HABIT][TARGET_DATE] = new_count
    stamps_out = json.dumps(stamps, indent=2).encode()
    habits_out = json.dumps(habits, indent=2).encode()

    stamp = datetime.now().strftime('%Y%m%d_%H%M%S')
    for path in (STAMPS_LOCAL, HABITSDB_LOCAL, PC_HABITSDB):
        sh(f'cp {path} {path}.backup_{stamp}')
    print(f'backups written with suffix .backup_{stamp}')

    # ── push: phone internal timestamps file ──────────────────────────
    push_timestamps(stamps_out)

    # ── push: habitsdb — phone shared-storage copy AND PC synced copy ─
    open('/tmp/habitsdb_fixed.txt', 'wb').write(habits_out)
    sh(f'adb push /tmp/habitsdb_fixed.txt {PHONE_HABITSDB}')
    open(PC_HABITSDB, 'wb').write(habits_out)

    # ── verify: re-pull both and confirm ──────────────────────────────
    ts_check = json.loads(pull_timestamps())
    assert ts_check[HABIT][TARGET_DATE] == kept, 'timestamp push mismatch!'
    sh(f'adb pull {PHONE_HABITSDB} /tmp/habitsdb_verify.txt')
    db_check = json.load(open('/tmp/habitsdb_verify.txt'))
    assert db_check[HABIT][TARGET_DATE] == new_count, 'habitsdb push mismatch!'
    assert json.load(open(PC_HABITSDB))[HABIT][TARGET_DATE] == new_count, \
        'PC habitsdb write mismatch!'
    print('verified on phone (timestamps + habitsdb) and PC habitsdb ✔')


if __name__ == '__main__':
    main()
