#!/usr/bin/env python3
"""Repair the 'Chess' aggregate habit history in habitsdb.txt.

Chess is a conditional aggregate habit: 7 source habits feed it their
divider-applied points (feed-points semantics, no feed-max-one cap, all
links on the default Points slot). Its history was corrupted by:
  (a) chess.com backlog fetches resetting linked habits and re-feeding the
      full minutes history into Chess on every fetch,
  (b) IPC increment paths (receiver/voice/PC widget) feeding a flat +1,
  (c) 'Rapid Chess' missing from conditional_feed_points_habits so its raw
      minutes fed Chess instead of divider-applied points.

This script recomputes, for every date >= 2025-11-04:
    Chess[d] = sum(applyDivider(source[d], divider) for the 7 sources)
keeping the 13 pre-cutoff manual-era days unchanged. Days where Chess > 0
but no source was active become 0 (Chess must only move when sources move).

applyDivider mirrors HabitModels.kt exactly:
    divider <= 1 -> raw passthrough
    raw <= 0     -> 0
    otherwise    -> max(floor(raw/divider + 0.5), 1)   [Kotlin Math.round = half-up]

The writer must reproduce the file byte-for-byte (Gson pretty print:
2-space indent, sorted keys, no trailing newline) — verified with a
round-trip check before any modification is written.
"""
import json
import math
import os
import shutil
import sys
import time

DB = '/home/twain/habitsdb/habitsdb.txt'
CUTOFF = '2025-11-04'

# Verified against on-device DataStore (2026-08-20):
# conditional_linked_habits -> Chess, habit_dividers, conditional_feed_points_habits
SOURCES = {
    'Slow Chess Puzzle': 15,
    'Fast Chess Puzzle': 15,
    'Bullet Chess': 30,
    'Blitz Chess': 30,
    'Rapid Chess': 30,
    'Long Chess': 1,   # no divider in settings -> default 1
    'Chess Video': 1,  # no divider in settings -> default 1
}

SPOT_CHECKS = ['2025-11-10', '2026-08-12', '2026-08-16', '2026-08-19', '2026-08-20',
               '2025-11-05', '2025-11-06', '2026-02-02', '2026-02-25',
               '2026-03-12', '2026-03-18', '2026-03-19']


def apply_divider(raw: int, divider: int) -> int:
    if divider <= 1:
        return raw
    if raw <= 0:
        return 0
    return max(math.floor(raw / divider + 0.5), 1)  # Kotlin Math.round: half-up


def expected_chess(src_maps, date: str) -> int:
    return sum(apply_divider(src_maps[name].get(date, 0), div)
               for name, div in SOURCES.items())


def main() -> int:
    with open(DB, encoding='utf-8') as f:
        original_text = f.read()
    db = json.loads(original_text)

    # ── Round-trip fidelity check: our writer must reproduce the file exactly ──
    # NOTE: the file is in Gson LinkedHashMap insertion order (mostly, but NOT
    # fully, alphabetical) — so we must NOT sort keys; dicts preserve order.
    canonical = json.dumps(db, indent=2, ensure_ascii=False)
    if canonical != original_text:
        n = min(len(canonical), len(original_text))
        pos = next((i for i in range(n) if canonical[i] != original_text[i]), n)
        print(f'FIDELITY CHECK FAILED at byte {pos} of {len(original_text)}:')
        print(f'  original : {original_text[pos:pos+80]!r}')
        print(f'  canonical: {canonical[pos:pos+80]!r}')
        return 1
    print(f'fidelity check OK ({len(original_text)} bytes reproduced exactly)')

    chess = dict(db.get('Chess', {}))
    src_maps = {name: db.get(name, {}) for name in SOURCES}
    missing = [name for name in SOURCES if name not in db]
    if missing:
        print(f'WARNING: sources with no DB key: {missing}')

    pre_cutoff = sorted(d for d in chess if d < CUTOFF)
    print(f'pre-cutoff Chess days preserved unchanged: {len(pre_cutoff)} '
          f'({pre_cutoff[0] if pre_cutoff else "-"}..{pre_cutoff[-1] if pre_cutoff else "-"})')

    dates = {d for d in chess if d >= CUTOFF}
    for m in src_maps.values():
        dates |= {d for d in m if d >= CUTOFF}

    changes = []
    for d in sorted(dates):
        exp = expected_chess(src_maps, d)
        old = chess.get(d)
        if old is None:
            if exp > 0:
                chess[d] = exp
                changes.append((d, 0, exp))
            continue
        if old != exp:
            changes.append((d, old, exp))
            chess[d] = exp

    total_before = sum(v for k, v in db.get('Chess', {}).items())
    total_after = sum(chess.values())
    print(f'days examined >= {CUTOFF}: {len(dates)}')
    print(f'days changed: {len(changes)}')
    print(f'Chess total: {total_before} -> {total_after} (delta {total_after - total_before})')

    print('\nspot checks (date: old -> new | sources raw):')
    for d in SPOT_CHECKS:
        raw = {n: src_maps[n].get(d, 0) for n in SOURCES if src_maps[n].get(d, 0)}
        old = db.get('Chess', {}).get(d)
        new = chess.get(d, 0)
        print(f'  {d}: {old} -> {new}   raw={raw}')

    if not changes:
        print('\nnothing to change — DB already consistent')
        return 0

    # ── Backup, then atomic write ──
    bak = f"{DB}.bak_chess_{time.strftime('%Y%m%d_%H%M%S')}"
    shutil.copy2(DB, bak)
    print(f'\nbackup: {bak}')

    # Rebuild Chess preserving the original entry order (dates are stored in
    # chronological insertion order); brand-new dates get appended at the end.
    original_order = list(db.get('Chess', {}).keys())
    new_chess = {}
    for d in original_order:
        new_chess[d] = chess[d]
    for d in sorted(set(chess) - set(original_order)):
        new_chess[d] = chess[d]
    db['Chess'] = new_chess
    out = json.dumps(db, indent=2, ensure_ascii=False)
    tmp = DB + '.tmp_repair'
    with open(tmp, 'w', encoding='utf-8') as f:
        f.write(out)
    os.replace(tmp, DB)
    print(f'wrote {DB} ({len(out)} bytes)')

    # ── Post-write verification: reload and recompute ──
    with open(DB, encoding='utf-8') as f:
        check_db = json.loads(f.read())
    check_chess = check_db.get('Chess', {})
    bad = [d for d in dates if check_chess.get(d, 0) != expected_chess(src_maps, d)]
    preserved = all(check_chess.get(d) == v
                    for d, v in json.loads(original_text).get('Chess', {}).items()
                    if d < CUTOFF)
    print(f'post-write verify: mismatches remaining = {len(bad)}, '
          f'pre-cutoff preserved = {preserved}')
    if bad or not preserved:
        print('VERIFICATION FAILED — restore backup!')
        shutil.copy2(bak, DB)
        return 1
    print('OK: all days >= cutoff now equal sum of source points; '
          'pre-cutoff days untouched')
    return 0


if __name__ == '__main__':
    sys.exit(main())
