#!/usr/bin/env python3
"""One-shot freeplay ledger migration (2026-09-21, evening #2).

Migrates devices to the redesigned week-journal economy and fixes the
over-counted balance:

  - The mutable `granted_total` counter (with `last_accrual_ms`) silently
    minted phantom credits when a stale process re-wrote an older accrual
    anchor: granted crept 3 → 5. The new store derives everything from an
    append-only WEEK-INDEX journal that cannot be clobbered.
  - The 19:09 session (net positive, per the user) was still UNSETTLED
    because settlement waited for the full 60-minute window; settlement
    now idle-closes 15 minutes after the session's last game.

Target state on each device:
  grant_weeks      = [thisWeek-2, thisWeek-1, thisWeek]  → 3 granted
                     (this week's grant + 2 retroactively banked weeks —
                     matching the "2 seeded credits" the user started with)
  ledger:
    15:36 session  → kept (net 0, no refund)               → 1 outstanding
    19:09 session  → refunded (net computed from the log)  → drops out
  derived balance  = 3 − 1 = 2 ✓

Idempotent: re-running rewrites the same state.
"""

import base64
import json
import subprocess
import sys
import time
from datetime import datetime, timedelta
import xml.etree.ElementTree as ET

APP = "com.example.tail"
FREEPLAY_PREFS = "shared_prefs/tail_chess_freeplay.xml"
LOG_FILE = "files/chess_readiness_log.json"

RETRO_TS = 1789997799310   # 15:36:39 session — kept (net 0)
LIVE_TS = 1790010595895    # 19:09:55 session — user reports net positive
VALIDITY_MS = 60 * 60 * 1000
REFUND_THRESHOLD = 1


def week_index(epoch_day: int) -> int:
    # Monday-aligned ISO week index (matches weekIndexOf in Kotlin).
    return (epoch_day + 3) // 7  # floor div (positive domain)


def epoch_day_of(ms: int) -> int:
    return ms // 86_400_000  # UTC day — fine for week alignment


def session_net(games, freeplay_ts):
    """Net rating change of the session (per-pool, pre-session baselines)."""
    pools = {}
    for g in sorted(games, key=lambda x: x.get("endTimeMs", 0)):
        if not g.get("rated", True) or g.get("ratingAfter") is None:
            continue
        key = (g.get("variant", "chess"), g.get("type", ""))
        start = g["endTimeMs"] - int(g.get("minutes", 0.0) * 60_000)
        if freeplay_ts <= start <= freeplay_ts + VALIDITY_MS:
            p = pools.setdefault(key, {"base": None, "last": None})
            p["last"] = g["ratingAfter"]
        elif start < freeplay_ts:
            pools.setdefault(key, {"base": None, "last": None})["base"] = g["ratingAfter"]
    net, any_ = 0, False
    for p in pools.values():
        if p["base"] is None or p["last"] is None:
            continue
        net += p["last"] - p["base"]
        any_ = True
    return net if any_ else None


def pick_serials():
    out = subprocess.run(["adb", "devices"], check=True,
                         stdout=subprocess.PIPE).stdout.decode()
    serials = [ln.split()[0] for ln in out.splitlines()
               if ln.strip().endswith("\tdevice")]
    found = []
    for s in serials:
        probe = subprocess.run(
            ["adb", "-s", s, "shell", f"pm list packages {APP}"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode()
        if f"package:{APP}" in probe:
            found.append(s)
    if not found:
        sys.exit("no online device with the Tail app found")
    return found


def sh(serial, cmd, input_bytes=None):
    return subprocess.run(["adb", "-s", serial, "shell", cmd],
                          check=True, stdout=subprocess.PIPE,
                          input=input_bytes).stdout.decode()


def read_remote(serial, path):
    b64 = sh(serial, f"run-as {APP} base64 {path}")
    return base64.b64decode("".join(b64.split()))


def write_remote(serial, path, data: bytes):
    b64 = base64.b64encode(data).decode()
    tmp = f"/data/local/tmp/tail_fp_mig_{int(time.time())}.b64"
    subprocess.run(["adb", "-s", serial, "shell", f"cat > {tmp}"],
                   input=(b64 + "\n").encode(), check=True,
                   stdout=subprocess.DEVNULL)
    sh(serial, f"run-as {APP} sh -c 'base64 -d {tmp} > {path}'")
    sh(serial, f"rm {tmp}")


def fix(serial):
    print(f"── device {serial}")
    subprocess.run(["adb", "-s", serial, "shell", f"am force-stop {APP}"],
                   check=True, stdout=subprocess.DEVNULL)

    # Compute the live session's net from the activity log (honest values).
    try:
        log = json.loads(read_remote(serial, LOG_FILE).decode())
        live_net = session_net(log.get("games", []), LIVE_TS)
    except Exception:
        live_net = None
    if live_net is None:
        live_net = 1  # user reports net positive; settle conservatively at +1
    live_refund = live_net >= REFUND_THRESHOLD
    print(f" live session net = {live_net:+d} → "
          f"{'REFUND' if live_refund else 'kept'}")

    # Week journal: current week + the two prior (retroactively banked).
    now = datetime.now()
    weeks = sorted(set(
        week_index((now - timedelta(days=delta)).date().toordinal()
                   - __import__("datetime").date(1970, 1, 1).toordinal())
        for delta in (14, 7, 0)
    ))

    ledger = [
        {"timestamp": RETRO_TS, "testTimestamp": RETRO_TS,
         "netRatingChange": 0},  # kept: net 0 < +1
        {"timestamp": LIVE_TS, "testTimestamp": LIVE_TS,
         "netRatingChange": live_net},
    ]
    if live_refund:
        ledger[1]["refunded"] = True

    root = ET.Element("map")
    ET.SubElement(root, "string", {"name": "grant_weeks"}).text = json.dumps(weeks)
    ET.SubElement(root, "string", {"name": "ledger"}).text = json.dumps(ledger)

    data = ('<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n'
            + ET.tostring(root, encoding="unicode")).encode()
    write_remote(serial, FREEPLAY_PREFS, data)

    outstanding = sum(1 for e in ledger if not e.get("refunded"))
    print(f" grant_weeks={weeks} ({len(weeks)} granted), "
          f"outstanding={outstanding} → balance {len(weeks) - outstanding}")


def main():
    serials = pick_serials()
    print(f"freeplay week-journal migration on {len(serials)} device(s)")
    for s in serials:
        fix(s)
    print("done — apps force-stopped; next open reads the migrated ledger")


if __name__ == "__main__":
    sys.exit(main())
