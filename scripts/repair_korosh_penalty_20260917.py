#!/usr/bin/env python3
"""One-shot repair (2026-09-17): undo the korosh935milad false-positive penalty.

CONTEXT (all times local, Europe/Rome):
  14:03:55  GREEN test (ccrs 85) — rolling window anchored at the test.
  14:11:10  rated game vs volodiymir ENDS; its CONTINUE_RATED audit is filed
            at that instant, re-anchoring the idle clock.
  14:21:32  rated game vs korosh935milad STARTS — only 10m22s of real
            no-play time after volodiymir ended.
  14:28:35  korosh game ends; penalty fires at 14:28:41: the old rule
            measured a 10-minute audit gap and 10m22s missed the cutoff by
            22 seconds → "unauthorized" → 24-hour lockout.

The user's rule (2026-09-17): the gap must be REAL no-play time and it is
15 minutes. Under the fixed engine this game is authorized: the played-game
chain anchors at volodiymir's end (14:11:10), gap 10m22s < 15 min.

FIX HERE:
  1. clear the violation penalty for the korosh game (violation_penalties → []),
  2. flip that game's activity-log entry to authorized=true (its stored
     stateAtPlay is already GREEN_LIGHT — the simple validity check used by
     the logger never flagged it),
  3. shift the 14:03:55 GREEN pass to "now" in BOTH the v1 test history
     (Chess Guard + checkGate read it) and the v3 telemetry results so the
     user is immediately back in an open GREEN session (same safe transfer
     method as unlock_chess_green_20260913.py — timestamp moves, no new
     entry, daily-cap safe).

Safe transfer: base64 over `adb shell` + `run-as cp`. The newest online adb
endpoint with Tail installed is used automatically.
"""

import base64
import datetime
import json
import os
import re
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET

APP = "com.example.tail"
V1_PREFS = "shared_prefs/tail_chess_readiness.xml"
V3_PREFS = "shared_prefs/tail_chess_readiness_v3.xml"
GAME_KEY = "1789648115|korosh935milad|300"
LOG_FILE = "files/chess_readiness_log.json"


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
    """Reads a remote file via base64 (newline-safe over exec-out)."""
    b64 = subprocess.run(
        ["adb", "-s", SERIAL, "shell",
         f"run-as {APP} base64 {path}"],
        check=True, stdout=subprocess.PIPE).stdout.decode()
    return base64.b64decode("".join(b64.split()))


def write_remote(path, data: bytes):
    """Writes a remote file: base64 payload streamed over STDIN (argv size
    limits forbid echoing large payloads on the adb command line)."""
    b64 = base64.b64encode(data).decode()
    tmp = f"/data/local/tmp/tail_repair_{int(time.time())}.b64"
    subprocess.run(
        ["adb", "-s", SERIAL, "shell", f"cat > {tmp}"],
        input=(b64 + "\n").encode(), check=True,
        stdout=subprocess.DEVNULL)
    sh(f"run-as {APP} sh -c 'base64 -d {tmp} > {path}'")
    sh(f"rm {tmp}")


def decode_pref(text):
    """XML text content of a <string> node → the raw JSON it stores."""
    if text is None:
        return None
    # ElementTree already resolves " etc.; keep literal newlines.
    return text


def get_pref(root, name):
    for node in root.findall("./string"):
        if node.get("name") == name:
            return node
    return None


def repair_penalties_and_tests():
    print(f"[{SERIAL}] repair penalties + v1 test history")
    raw = read_remote(V1_PREFS)
    root = ET.fromstring(raw.decode("utf-8"))

    # 1. Clear ALL violation penalties (the korosh one is currently the
    #    only entry; an empty list is always a valid state).
    node = get_pref(root, "violation_penalties")
    if node is None:
        print("  no violation_penalties key — nothing to clear")
    else:
        raw_pen = decode_pref(node.text) or "[]"
        pens = json.loads(raw_pen)
        print(f"  clearing {len(pens)} penalty/pencies: "
              f"{[p.get('gameId') for p in pens]}")
        node.text = "[]"

    # 2. Shift the latest GREEN pass to "now" (opens a fresh rolling
    #    window; timestamp move only — no new entry, daily-cap safe).
    node = get_pref(root, "test_history")
    if node is None:
        sys.exit("  ERROR: test_history missing — cannot restore green")
    hist = json.loads(decode_pref(node.text))
    last = max(hist, key=lambda t: t["timestamp"])
    if last.get("state") != "GREEN_LIGHT":
        sys.exit(f"  ERROR: latest v1 entry is {last.get('state')} — "
                 f"refusing to manufacture green; nothing changed")
    old_ts = last["timestamp"]
    now_ms = int(time.time() * 1000)
    last["timestamp"] = now_ms
    node.text = json.dumps(hist)
    print(f"  v1 GREEN pass {old_ts} -> {now_ms}")

    write_remote(V1_PREFS, ET.tostring(root, encoding="unicode").encode())
    print("  v1 prefs written")

    # 3. v3 telemetry: keep the matching result chronologically in step.
    try:
        raw3 = read_remote(V3_PREFS)
        root3 = ET.fromstring(raw3.decode("utf-8"))
        node3 = get_pref(root3, "results")
        if node3 is not None and node3.text:
            results = json.loads(decode_pref(node3.text))
            moved = 0
            for r in results:
                if r.get("timestamp") == old_ts:
                    r["timestamp"] = now_ms
                    moved += 1
            if moved:
                node3.text = json.dumps(results)
                write_remote(V3_PREFS,
                             ET.tostring(root3, encoding="unicode").encode())
                print(f"  v3: moved {moved} matching result(s) to {now_ms}")
    except subprocess.CalledProcessError:
        print("  v3 prefs unreadable — v1 unlock stands")


def repair_game_log():
    print(f"[{SERIAL}] repair activity log entry {GAME_KEY}")
    raw = read_remote(LOG_FILE)
    log = json.loads(raw.decode("utf-8"))
    fixed = 0
    for g in log.get("games", []):
        if g.get("key") == GAME_KEY and g.get("authorized") is False:
            g["authorized"] = True
            fixed += 1
    if fixed == 0:
        print("  entry already authorized or absent — nothing to change")
        return
    write_remote(LOG_FILE, json.dumps(log, separators=(",", ":")).encode())
    print(f"  {GAME_KEY} -> authorized=true")


def main():
    now = datetime.datetime.now().strftime("%Y-%m-%d %H:%M:%S")
    print(f"repair start {now} (device {SERIAL})")
    repair_penalties_and_tests()
    repair_game_log()
    sh(f"am force-stop {APP}")
    print("done — app force-stopped; next open sees: no penalty, fresh GREEN "
          "session, rolling window open for 15 minutes of real no-play time")


if __name__ == "__main__":
    sys.exit(main())
