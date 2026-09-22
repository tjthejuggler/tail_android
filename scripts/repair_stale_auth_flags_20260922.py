#!/usr/bin/env python3
"""One-shot repair (2026-09-22): fix stale authorized=false game flags.

CONTEXT (user report, all times local Europe/Rome):
  The "Compliance Over Time" chart showed red "no fresh test" bars on
  2026-09-10 and 2026-09-11 even though the Chess Guard applied NO penalties
  on those days (violation_penalties holds only the 09-21 lemlemmel entry).
  Root cause: the activity-log logger resolved the stored `authorized` flag
  with a flat-60-minute-validity check while enforcement had already moved
  to the rolling idle-gap window (30-minute era, commits d784dec 09-09 /
  0d31833 09-12) — the divergence fixed in the app on 2026-09-22.

Games mislabeled by the logger (replay against the era rule):
  09-10 10:16  idle 17.1 min < 30  → authorized
  09-10 10:18  idle  1.2 min < 30  → authorized
  09-11 20:36  idle  6.0 min < 30  → authorized
  09-11 20:46  idle  5.2 min < 30  → authorized
  09-10 21:28  idle 30.4 min (24 s past the era close; the guard evaluated
               this game at the time and did NOT penalize it — the stored
               flag came from the buggy logger check, not enforcement)

FIX HERE:
  1. flip `authorized` to true on those 5 game entries in the activity log
     (stateAtPlay is already GREEN_LIGHT on all of them),
  2. leave everything else untouched — tests, rush sessions, penalties.

Safe transfer: base64 over `adb shell` + `run-as`. The newest online adb
endpoint with Tail installed is used automatically.
"""

import base64
import datetime
import json
import subprocess
import sys
import time

APP = "com.example.tail"
LOG_FILE = "files/chess_readiness_log.json"
TZ = datetime.timezone(datetime.timedelta(hours=2))

# Dedupe keys of the mislabeled games (exact, unambiguous)
TARGET_KEYS = {
    "1789028176|bond457|60",          # 09-10 10:16  idle 17.1 min < 30
    "1789028311|kinglevijun|60",      # 09-10 10:18  idle  1.2 min < 30
    "1789068506|dsinelnikovmailru|60",# 09-10 21:28  guard-evaluated, not penalized
    "1789151766|chayanbd369|300",     # 09-11 20:36  idle  6.0 min < 30
    "1789152379|krupin1956q|300",     # 09-11 20:46  idle  5.2 min < 30
}


def pick_serial():
    out = subprocess.run(["adb", "devices"], check=True,
                         stdout=subprocess.PIPE).stdout.decode()
    serials = [ln.split()[0] for ln in out.splitlines()
               if ln.strip().endswith("\tdevice")]
    for s in serials:
        probe = subprocess.run(
            ["adb", "-s", s, "shell", f"pm list packages {APP}"],
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL).stdout.decode()
        if f"package:{APP}" in probe:
            return s
    sys.exit(f"no online device with {APP} found")


SERIAL = pick_serial()


def sh(cmd):
    return subprocess.run(["adb", "-s", SERIAL, "shell", cmd], check=True,
                          stdout=subprocess.PIPE).stdout.decode()


def read_remote(path):
    b64 = subprocess.run(
        ["adb", "-s", SERIAL, "shell", f"run-as {APP} base64 {path}"],
        check=True, stdout=subprocess.PIPE).stdout.decode()
    return base64.b64decode("".join(b64.split()))


def write_remote(path, data: bytes):
    b64 = base64.b64encode(data).decode()
    tmp = f"/data/local/tmp/tail_repair_{int(time.time())}.b64"
    subprocess.run(
        ["adb", "-s", SERIAL, "shell", f"cat > {tmp}"],
        input=(b64 + "\n").encode(), check=True,
        stdout=subprocess.DEVNULL)
    sh(f"run-as {APP} sh -c 'base64 -d {tmp} > {path}'")
    sh(f"rm {tmp}")


def main():
    raw = read_remote(LOG_FILE)
    root = json.loads(raw)
    games = root.get("games", [])
    print(f"loaded {len(games)} games")

    changed = 0
    for g in games:
        if g.get("authorized", False):
            continue
        if g.get("key") not in TARGET_KEYS:
            continue
        g["authorized"] = True
        changed += 1
        t = datetime.datetime.fromtimestamp(g["endTimeMs"] / 1000, TZ)
        print(f"  flipped {t.strftime('%m-%d %H:%M:%S')} key={g['key']}")
    already = len(TARGET_KEYS) - changed
    if already:
        print(f"{already} target(s) already authorized (previous run)")

    if changed == 0:
        print("nothing to change")
        return

    backup = f"{LOG_FILE}.bak_20260922"
    sh(f"run-as {APP} cp {LOG_FILE} {backup}")
    write_remote(LOG_FILE, json.dumps(root, ensure_ascii=False).encode())
    print(f"repaired {changed} games; backup at {backup}")


if __name__ == "__main__":
    main()
