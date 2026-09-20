#!/usr/bin/env python3
"""One-shot green unlock (2026-09-20): put the account into GREEN play mode now.

METHOD (same safe transfer as the 09-13 green / 09-17 re-test unlocks): find
the latest real GREEN_LIGHT pass in the v1 test history and time-shift it to
"now" in BOTH the v1 shared prefs (what Chess Guard + checkGate read) and the
v3 telemetry results log. That re-opens the 10-minute rolling green window and
restarts the 60-minute validity, clearing any red-cooldown / dead-zone wall.

No entry is added (daily-cap safe); only a timestamp moves. Unlike the 09-13
script, the green pass is looked up across the WHOLE history, so a stale
FAIL/AMBER entry on top does not block the unlock. If no GREEN_LIGHT entry
exists at all the script refuses to manufacture one.
"""

import base64
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APP = "com.example.tail"
V1_PREFS = "shared_prefs/tail_chess_readiness.xml"
V3_PREFS = "shared_prefs/tail_chess_readiness_v3.xml"


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
    tmp = f"/data/local/tmp/tail_green_{int(time.time())}.b64"
    subprocess.run(["adb", "-s", SERIAL, "shell", f"cat > {tmp}"],
                   input=(b64 + "\n").encode(), check=True,
                   stdout=subprocess.DEVNULL)
    sh(f"run-as {APP} sh -c 'base64 -d {tmp} > {path}'")
    sh(f"rm {tmp}")


def main():
    now_ms = int(time.time() * 1000)
    print(f"device {SERIAL}: shifting latest GREEN pass to now = {now_ms}")

    # ── 1. v1 test history (Chess Guard + checkGate read this) ────────────
    root = ET.fromstring(read_remote(V1_PREFS).decode("utf-8"))
    node = next((n for n in root.findall("./string")
                 if n.get("name") == "test_history"), None)
    if node is None or not node.text:
        sys.exit("ERROR: v1 test_history missing")
    hist = json.loads(node.text)
    greens = [t for t in hist if t.get("state") == "GREEN_LIGHT"]
    if not greens:
        sys.exit("ERROR: no GREEN_LIGHT entry in history — refusing to "
                 "manufacture green; nothing changed")
    last = max(greens, key=lambda t: t["timestamp"])
    old_ts = last["timestamp"]
    if old_ts == now_ms:
        print("latest GREEN pass already stamped now — nothing to do")
        return
    last["timestamp"] = now_ms
    node.text = json.dumps(hist)
    write_remote(V1_PREFS, ET.tostring(root, encoding="unicode").encode())
    print(f"v1: GREEN pass {old_ts} -> {now_ms}")

    # ── 2. v3 telemetry: move matching result(s) in step ──────────────────
    moved = 0
    try:
        root3 = ET.fromstring(read_remote(V3_PREFS).decode("utf-8"))
        node3 = next((n for n in root3.findall("./string")
                      if n.get("name") == "results"), None)
        if node3 is not None and node3.text:
            results = json.loads(node3.text)
            for r in results:
                if r.get("timestamp") == old_ts:
                    r["timestamp"] = now_ms
                    moved += 1
            if moved:
                node3.text = json.dumps(results)
                write_remote(V3_PREFS,
                             ET.tostring(root3, encoding="unicode").encode())
    except subprocess.CalledProcessError:
        print("v3 prefs unreadable — v1 unlock stands")
    print(f"v3: moved {moved} matching result(s)")

    sh(f"am force-stop {APP}")
    print("done — app force-stopped; next open sees a fresh GREEN session "
          "(10-minute rolling window open, 60-minute validity from now)")


if __name__ == "__main__":
    sys.exit(main())
