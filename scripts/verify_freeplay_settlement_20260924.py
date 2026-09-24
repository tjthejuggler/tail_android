#!/usr/bin/env python3
"""Freeplay settlement verification / repair (2026-09-24).

User report: "I used a freeplay ~1h ago, played two STANDARD blitz games
(+6 then −3 = net +3), but the ticket was still charged."

Diagnosis (from the on-device ledger, BEFORE any change):
  the latest ledger entry (testTimestamp 1790236959907 = 08:02:39 UTC)
  already read {"refunded": true, "netRatingChange": 3} — the refund HAD
  landed, computed from the two standard-chess games (ratingAfter 853 →
  859 → 856), settled at 08:57 UTC when the 15-minute idle-close fired.
  The derived balance was ALREADY back at 2 (3 granted weeks − 1
  outstanding net-0 session from 2026-09-21). The report was a
  VISIBILITY failure: between spending (balance shows 3−N) and the
  settlement landing 15+ minutes later, nothing on screen says "this
  credit comes back" — so a provisional spend reads as a permanent one.

What this script does:
  1. Reads the on-device ledger + the chess activity log.
  2. Recomputes every unsettled session's net rating change with the
     SAME pool rules as the app (per variant × speed, pre-session
     baseline, start-inside-window membership) — pure re-derivation,
     no trust in stored state.
  3. Refunds (marks refunded=true, netRatingChange=net) any completed
     session whose net ≥ +1 that the app has not yet settled, and
     prints the derived balance before/after.

It deliberately does NOT blindly increment anything: the ledger is
derived state and the balance is derived from it, so a wrong "+= 1"
would desynchronize the very accounting the store was redesigned
(2026-09-21) to make desync-proof.

Usage:  python3 scripts/verify_freeplay_settlement_20260924.py [--fix]
"""

import json
import subprocess
import sys
from datetime import datetime, timezone

APP = "com.example.tail"
FREEPLAY_PREFS = "shared_prefs/tail_chess_freeplay.xml"
LOG_FILE = "files/chess_readiness_log.json"

VALIDITY_MS = 60 * 60 * 1000
IDLE_CLOSE_MS = 15 * 60 * 1000
REFUND_THRESHOLD = 1
MAX_STOCK = 3


def adb(*args: str) -> str:
    return subprocess.run(
        ["adb", *args], check=True, stdout=subprocess.PIPE
    ).stdout.decode()


def pick_serial() -> str:
    out = adb("devices")
    for line in out.splitlines():
        if line.strip().endswith("\tdevice"):
            serial = line.split()[0]
            probe = subprocess.run(
                ["adb", "-s", serial, "shell", f"pm list packages {APP}"],
                stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            if f"package:{APP}".encode() in probe.stdout:
                return serial
    sys.exit("no online device with the Tail app found")


def read_prefs(serial: str) -> str:
    return adb("-s", serial, "shell",
               f"run-as {APP} cat {FREEPLAY_PREFS}")


def read_log(serial: str) -> dict:
    raw = adb("-s", serial, "shell",
              f"run-as {APP} cat {LOG_FILE}")
    # `adb shell` may keep \r\n line endings — strip them for the parser.
    return json.loads(raw.replace("\r\n", "\n").strip())


def write_prefs(serial: str, xml: str):
    # Force-stop first so no live process overwrites the file afterwards
    # (same discipline as reseed_freeplay_ledger_20260921.py).
    subprocess.run(["adb", "-s", serial, "shell",
                    f"am force-stop {APP}"], check=True)
    subprocess.run(["adb", "-s", serial, "shell",
                    f"run-as {APP} sh -c 'cat > {FREEPLAY_PREFS}'"],
                   input=xml.encode(), check=True)


def net_rating_change(games: list, freeplay_ts: int) -> int | None:
    """Port of freeplaySessionNetRatingChange (ChessFreeplaySettlement.kt)."""
    window_end = freeplay_ts + VALIDITY_MS
    pools: dict[str, dict] = {}

    def in_session(g):
        start = g["endTimeMs"] - int(g.get("minutes", 0) * 60_000)
        return (g.get("rated") and freeplay_ts <= start <= window_end and
                (g.get("freeplayAtPlay") or g.get("authorized")))

    for g in sorted(games, key=lambda x: x["endTimeMs"]):
        if not g.get("rated"):
            continue
        pool = pools.setdefault(f"{g.get('variant')}|{g.get('type')}",
                                {"baseline": None, "first": None, "last": None})
        start = g["endTimeMs"] - int(g.get("minutes", 0) * 60_000)
        ra = g.get("ratingAfter")
        if in_session(g):
            if pool["first"] is None:
                pool["first"] = ra
            if ra is not None:
                pool["last"] = ra
        elif start < freeplay_ts and ra is not None:
            pool["baseline"] = ra

    net, any_pool = 0, False
    for p in pools.values():
        if p["baseline"] is None or p["first"] is None:
            continue
        net += (p["last"] if p["last"] is not None else p["first"]) - p["baseline"]
        any_pool = True
    return net if any_pool else None


def main():
    fix = "--fix" in sys.argv
    serial = pick_serial()
    xml = read_prefs(serial)

    import xml.etree.ElementTree as ET
    root = ET.fromstring(xml)
    ledger_raw = root.find("./string[@name='ledger']").text
    weeks_raw = root.find("./string[@name='grant_weeks']").text
    ledger = json.loads(ledger_raw)
    granted_weeks = json.loads(weeks_raw)

    log = read_log(serial)
    games = log.get("games", [])

    def fmt(ts):
        return datetime.fromtimestamp(ts / 1000, tz=timezone.utc).strftime(
            "%Y-%m-%d %H:%M:%S UTC")

    def balance(entries):
        spent = sum(1 for e in entries if not e.get("refunded"))
        return min(max(len(granted_weeks) - spent, 0), MAX_STOCK)

    print(f"granted weeks : {granted_weeks} ({len(granted_weeks)} credits)")
    print(f"ledger        : {len(ledger)} entries, derived balance = "
          f"{balance(ledger)}")
    for e in ledger:
        net = e.get("netRatingChange")
        state = ("refunded" if e.get("refunded") else
                 ("KEPT (net " + str(net) + ")" if net is not None
                  else "PROVISIONAL — not settled yet"))
        print(f"  {fmt(e['testTimestamp'])}  net={net}  → {state}")

    # Re-derive unsettled sessions from the raw game log.
    fixed = 0
    for e in ledger:
        if e.get("netRatingChange") is not None:
            continue
        ts = e["testTimestamp"]
        last_end = max(
            (g["endTimeMs"] for g in games
             if g.get("rated") and ts <=
             g["endTimeMs"] - int(g.get("minutes", 0) * 60_000)
             <= ts + VALIDITY_MS and
             (g.get("freeplayAtPlay") or g.get("authorized"))),
            default=None)
        settle_at = (min(ts + VALIDITY_MS, last_end + IDLE_CLOSE_MS)
                     if last_end else ts + VALIDITY_MS)
        import time
        if settle_at > time.time() * 1000:
            print(f"  {fmt(ts)}: session still in progress — skipping")
            continue
        net = net_rating_change(games, ts)
        refund = net is not None and net >= REFUND_THRESHOLD
        print(f"  {fmt(ts)}: recomputed net={net} → "
              f"{'REFUND' if refund else 'KEPT'}")
        if refund and fix:
            e["refunded"] = True
            e["netRatingChange"] = net
            fixed += 1

    if fixed and fix:
        ledger_el = root.find("./string[@name='ledger']")
        ledger_el.text = json.dumps(ledger)
        # keep xml:space so "/& escaping round-trips like the app
        if "space" not in ledger_el.attrib:
            ledger_el.set("{http://www.w3.org/XML/1998/namespace}space",
                          "preserve")
        write_prefs(serial, ET.tostring(root, encoding="unicode"))
        print(f"fixed {fixed} unsettled session(s) on device")
    elif fixed:
        print(f"{fixed} session(s) DESERVE a refund but the app has not "
              f"settled yet — re-run with --fix to apply")
    else:
        print("no unsettled session owes a refund — ledger is consistent")

    print(f"final derived balance: {balance(ledger)}")


if __name__ == "__main__":
    main()
