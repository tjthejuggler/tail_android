#!/usr/bin/env python3
"""One-shot migration (2026-10-11): merge 'Galantamine' into 'Took pills'.

Galantamine was a standalone pill-taking habit; since every galantamine
dose is also a pill taken, its history moves into 'Took pills':

  1. habitsdb.txt (phone /storage/emulated/0/Documents/habitsdb/ + PC
     ~/habitsdb/ synced copy): 'Took pills' day counts += the Galantamine
     count for that day (4 events on 2026-03-28/04-03/04-29/08-27);
     the 'Galantamine' key is then DELETED.
  2. pills.txt (external text log, phone Documents/Obsid/noteVault/tail/
     + PC ~/noteVault/tail/ synced copy): one "Galantamine" text entry is
     added per event at 12:00:00 (noon = the project's convention for
     date-only entries — no timestamps were ever recorded for Galantamine,
     see files/habit_timestamps.json which has 0 Galantamine stamps).
  3. Internal mirror files/text_input_backups/Took_pills.json on the
     phone gets the same 4 entries.
  4. tail_settings.preferences_pb (Jetpack DataStore): 'Galantamine' is
     removed from every |||-joined habit list it appears in (the HRV and
     trashcan screens) by replacing the element with an empty placeholder
     "" — byte-identical to what the in-app deleteHabit does — via a
     proper protobuf wire-format rewrite (length prefixes recomputed).

habit_timestamps.json needs no edit (zero Galantamine stamps exist).

Usage:
    python3 scripts/migrate_galantamine_to_tookpills_20261011.py          # dry-run
    python3 scripts/migrate_galantamine_to_tookpills_20261011.py --apply
"""

import json
import shutil
import subprocess
import sys
import time

APP = "com.example.tail"
SRC = "Galantamine"
DST = "Took pills"
EVENT_TIME = "12:00:00"

PC_HABITSDB = "/home/twain/habitsdb/habitsdb.txt"
PHONE_HABITSDB = "/storage/emulated/0/Documents/habitsdb/habitsdb.txt"
PC_PILLS = "/home/twain/noteVault/tail/pills.txt"
PHONE_PILLS = "/storage/emulated/0/Documents/Obsid/noteVault/tail/pills.txt"
PHONE_INTERNAL = "files/text_input_backups/Took_pills.json"
PHONE_SETTINGS = "files/datastore/tail_settings.preferences_pb"

WORK = "/tmp/gal_migrate"


def sh(cmd, binary=False):
    res = subprocess.run(cmd, shell=True, capture_output=True)
    if res.returncode != 0:
        raise RuntimeError(f"{cmd!r} failed: {res.stderr.decode()[:400]}")
    return res.stdout if binary else res.stdout.decode()


def pull_internal(remote, local):
    """Binary-safe pull of an app-internal file (exec-out avoids pty LF mangling)."""
    data = sh(f"adb exec-out run-as {APP} cat {remote}", binary=True)
    open(local, "wb").write(data)
    return data


def push_internal(local, remote):
    """Stage in /data/local/tmp and copy in as the app via run-as."""
    tmp = "/data/local/tmp/gal_mig.bin"
    sh(f"adb push {local} {tmp}")
    sh(f"adb shell run-as {APP} cp {tmp} {remote}")
    sh(f"adb shell rm -f {tmp}")


# ── protobuf wire-format rewrite ───────────────────────────────────────────
# Jetpack DataStore preferences_pb schema (subset actually used):
#   message Preferences { repeated Preference settings = 1; }
#   message Preference { string key = 1; oneof value { StringProto string = 2; ... } }
#   message StringProto  { bytes value = 1; }
# We parse into a generic tree, patch StringProto payloads, re-serialize.
# Round-trip of parse+serialize with no patch must be byte-identical (checked).

def read_varint(buf, i):
    shift = 0
    val = 0
    while True:
        b = buf[i]
        i += 1
        val |= (b & 0x7F) << shift
        if not b & 0x80:
            return val, i
        shift += 7


def write_varint(val):
    out = bytearray()
    while True:
        b = val & 0x7F
        val >>= 7
        if val:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def parse_pb(buf):
    """[(field_no, wire_type, varint_value|bytes_value, raw_of_value)]"""
    out = []
    i = 0
    n = len(buf)
    while i < n:
        tag, i = read_varint(buf, i)
        fno, wt = tag >> 3, tag & 7
        if wt == 0:
            val, i = read_varint(buf, i)
            out.append((fno, wt, val))
        elif wt == 2:
            ln, i = read_varint(buf, i)
            out.append((fno, wt, bytes(buf[i:i + ln])))
            i += ln
        elif wt == 5:
            out.append((fno, wt, buf[i:i + 4]))
            i += 4
        elif wt == 1:
            out.append((fno, wt, buf[i:i + 8]))
            i += 8
        else:
            raise ValueError(f"wire type {wt}")
    return out


def ser_pb(fields):
    out = bytearray()
    for fno, wt, val in fields:
        out += write_varint(fno << 3 | wt)
        if wt == 0:
            out += write_varint(val)
        elif wt == 2:
            out += write_varint(len(val)) + val
        elif wt == 5:
            out += val
        elif wt == 1:
            out += val
    return bytes(out)


def pb_roundtrip_ok(blob):
    return ser_pb(parse_pb(blob)) == blob


def patch_string_settings(blob, elem):
    """Replace `elem` with '' inside every |||-joined string preference value."""
    n_patched = 0

    def patch_pref(pref_fields):
        nonlocal n_patched
        new = []
        for fno, wt, val in pref_fields:
            if fno == 2 and wt == 2:
                # String value wrapper (verified on-device): its field 5
                # holds the raw string bytes of the |||-joined habit list.
                inner = parse_pb(val)
                inner2 = []
                for ifno, iwt, ival in inner:
                    # Habit lists are joined with runs of '|' ('|||' flat
                    # order, '|' inside the screen serialization) with '' as
                    # the empty-slot placeholder. Splitting on a single '|'
                    # preserves every empty token, so replacing the exact
                    # element token and rejoining is byte-identical to the
                    # in-app delete for both formats.
                    if ifno == 5 and iwt == 2 and b"\x00" in ival:
                        # Icon map: 'habit\x00icon|||habit\x00icon' — entries
                        # for the deleted habit drop whole.
                        entries = ival.split(b"|||")
                        kept = [e for e in entries
                                if e.split(b"\x00")[0] != elem.encode()]
                        if len(kept) != len(entries):
                            ival = b"|||".join(kept)
                            n_patched += 1
                    elif ifno == 5 and iwt == 2 and elem.encode() in ival.split(b"|"):
                        parts = ival.split(b"|")
                        ival = b"|".join(b"" if p == elem.encode() else p
                                         for p in parts)
                        n_patched += 1
                    inner2.append((ifno, iwt, ival))
                val = ser_pb(inner2)
            new.append((fno, wt, val))
        return new

    top = parse_pb(blob)
    top2 = []
    for fno, wt, val in top:
        if fno == 1 and wt == 2:
            val = ser_pb(patch_pref(parse_pb(val)))
        top2.append((fno, wt, val))
    return ser_pb(top2), n_patched


def main():
    apply = "--apply" in sys.argv
    sh(f"mkdir -p {WORK}")

    # Fresh pull of everything we touch (exec-out avoids pty LF mangling).
    habitsdb = json.loads(sh(f"adb exec-out cat {PHONE_HABITSDB}", binary=True))
    pills = json.loads(open(PC_PILLS, encoding="utf-8").read())
    internal = json.loads(pull_internal(PHONE_INTERNAL, f"{WORK}/internal.json"))
    settings_pb = pull_internal(PHONE_SETTINGS, f"{WORK}/settings.pb")

    src_days = {d: v for d, v in habitsdb.get(SRC, {}).items() if v}
    print(f"{SRC} events: {src_days}")
    print(f"{DST} counts on those days (before): "
          f"{{{', '.join(f'{d}: {habitsdb[DST].get(d, 0)}' for d in src_days)}}}")
    assert src_days, "no Galantamine events found — nothing to migrate"
    assert pills.keys() == internal.keys(), "external/internal pills log out of sync"

    # 1+2+3: habitsdb merge + delete, text entries.
    for d, cnt in src_days.items():
        habitsdb[DST][d] = habitsdb[DST].get(d, 0) + cnt
    del habitsdb[SRC]
    for d in src_days:
        key = f"{d} {EVENT_TIME}"
        n = 0
        while key in pills:  # collision safety
            n += 1
            key = f"{d} 12:00:{n:02d}"
        pills[key] = SRC
        internal[key] = SRC

    # 4: settings pb
    if not pb_roundtrip_ok(settings_pb):
        sys.exit("FATAL: pb round-trip mismatch — refusing to touch settings")
    new_pb, n_patched = patch_string_settings(settings_pb, SRC)
    print(f"settings pb: {n_patched} string preference(s) patched")
    assert n_patched >= 1, "expected Galantamine in the habit_screens setting"

    print(f"{DST} counts on those days (after):  "
          f"{{{', '.join(f'{d}: {habitsdb[DST][d]}' for d in src_days)}}}")
    print(f"new pills entries: {[(k, v) for k, v in pills.items() if v == SRC]}")
    print(f"habitsdb habits after: {len(habitsdb)} ('{SRC}' removed: {SRC not in habitsdb})")
    print(f"total pills entries: {len(pills)} (was {len(pills) - len(src_days)})")

    if not apply:
        print("[DRY RUN] re-run with --apply to push everything")
        return

    ts = time.strftime("%Y%m%d_%H%M%S")
    for p in (PC_HABITSDB, PC_PILLS):
        shutil.copy2(p, f"{p}.bak_gal_mig_{ts}")
    open(f"{WORK}/settings.pb.bak", "wb").write(settings_pb)

    # Write new local payloads.
    json.dump(habitsdb, open(f"{WORK}/habitsdb.json", "w"), indent=2, ensure_ascii=False)
    json.dump(pills, open(PC_PILLS, "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    json.dump(internal, open(f"{WORK}/internal.json", "w"), indent=2, ensure_ascii=False)
    open(f"{WORK}/settings_new.pb", "wb").write(new_pb)

    # Force-stop the app so nothing races the writes.
    sh(f"adb shell am force-stop {APP}")

    # Push: external habitsdb + pills (adb push), internal (run-as), settings pb.
    sh(f"adb push {WORK}/habitsdb.json {PHONE_HABITSDB}")
    sh(f"adb push {PC_PILLS} {PHONE_PILLS}")
    push_internal(f"{WORK}/internal.json", PHONE_INTERNAL)
    push_internal(f"{WORK}/settings_new.pb", PHONE_SETTINGS)
    # PC habitsdb copy stays in sync via Syncthing — write the same content.
    shutil.copy2(f"{WORK}/habitsdb.json", PC_HABITSDB)

    print("pushed: phone habitsdb.txt, phone pills.txt, internal Took_pills.json, "
          "tail_settings.preferences_pb; PC copies updated")
    print("VERIFY below (phone is source of truth):")

    # ── verification pass ──
    ok = True
    v_hab = json.loads(sh(f"adb exec-out cat {PHONE_HABITSDB}", binary=True))
    v_pills_raw = sh(f"adb exec-out cat {PHONE_PILLS}", binary=True)
    v_pills = json.loads(v_pills_raw)
    v_int = json.loads(pull_internal(PHONE_INTERNAL, f"{WORK}/verify_int.json"))
    v_pb = pull_internal(PHONE_SETTINGS, f"{WORK}/verify.pb")

    def check(name, cond):
        nonlocal ok
        print(("  OK  " if cond else "  FAIL") + f" {name}")
        ok = ok and cond

    check(f"'{SRC}' absent from phone habitsdb", SRC not in v_hab)
    check("merged day counts correct",
          all(v_hab[DST].get(d) == habitsdb[DST][d] for d in src_days))
    gal_entries = {k: v for k, v in v_pills.items() if v == SRC}
    check(f"{len(src_days)} Galantamine entries in phone pills.txt",
          len(gal_entries) == len(src_days) and
          set(gal_entries) == {k for k in pills if pills[k] == SRC})
    check("internal mirror has them too",
          all(k in v_int and v_int[k] == SRC for k in gal_entries))
    check("PC pills.txt == phone pills.txt",
          open(PC_PILLS, "rb").read() == v_pills_raw)
    check("settings pb parses & round-trips", pb_roundtrip_ok(v_pb))
    check("no 'Galantamine' string left in settings pb", b"Galantamine" not in v_pb)
    print("ALL VERIFIED" if ok else "VERIFICATION FAILED — inspect backups")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
