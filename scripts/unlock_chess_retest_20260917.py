#!/usr/bin/env python3
"""One-shot re-test unlock (2026-09-17, evening): open the readiness-test
gate immediately.

CONTEXT: after the korosh repair the account held a fresh GREEN pass
(time-shifted to 16:50:00 local). Its 60-minute validity holds the re-test
gate shut until 17:50 (checkGate: a new test unlocks exactly when the
previous pass stops authorizing play). The user wants to take the test NOW.

FIX HERE: time-shift the latest GREEN pass (v1 test_history + the matching
v3 telemetry result) to "now − 61 min", so the pass's validity has expired
and the re-test gate is OPEN. No entry added (daily-cap safe); only the
timestamp moves. Safe transfer: base64 over adb stdin (argv-limit safe).
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
MIN_AGE_MS = 61 * 60_000  # 61 minutes back → validity expired by 1 minute


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
    tmp = f"/data/local/tmp/tail_retest_{int(time.time())}.b64"
    subprocess.run(["adb", "-s", SERIAL, "shell", f"cat > {tmp}"],
                   input=(b64 + "\n").encode(), check=True,
                   stdout=subprocess.DEVNULL)
    sh(f"run-as {APP} sh -c 'base64 -d {tmp} > {path}'")
    sh(f"rm {tmp}")


def main():
    new_ts = int(time.time() * 1000) - MIN_AGE_MS
    print(f"device {SERIAL}: shifting latest GREEN pass to now-61min = {new_ts}")

    root = ET.fromstring(read_remote(V1_PREFS).decode("utf-8"))
    node = next((n for n in root.findall("./string")
                 if n.get("name") == "test_history"), None)
    if node is None or not node.text:
        sys.exit("ERROR: v1 test_history missing")
    hist = json.loads(node.text)
    if not hist:
        sys.exit("ERROR: v1 test_history empty")
    last = max(hist, key=lambda t: t["timestamp"])
    if last.get("state") != "GREEN_LIGHT":
        sys.exit(f"ERROR: latest v1 entry is {last.get('state')} — "
                 f"refusing to move it; nothing changed")
    old_ts = last["timestamp"]
    if old_ts <= new_ts:
        sys.exit("latest GREEN pass is already old enough — gate should "
                 "already be open; nothing changed")
    last["timestamp"] = new_ts
    node.text = json.dumps(hist)
    write_remote(V1_PREFS, ET.tostring(root, encoding="unicode").encode())
    print(f"v1: GREEN pass {old_ts} -> {new_ts} (validity expired)")

    moved = 0
    try:
        root3 = ET.fromstring(read_remote(V3_PREFS).decode("utf-8"))
        node3 = next((n for n in root3.findall("./string")
                      if n.get("name") == "results"), None)
        if node3 is not None and node3.text:
            results = json.loads(node3.text)
            for r in results:
                if r.get("timestamp") == old_ts:
                    r["timestamp"] = new_ts
                    moved += 1
            if moved:
                node3.text = json.dumps(results)
                write_remote(V3_PREFS,
                             ET.tostring(root3, encoding="unicode").encode())
    except subprocess.CalledProcessError:
        print("v3 prefs unreadable — v1 unlock stands")
    print(f"v3: moved {moved} matching result(s)")

    sh(f"am force-stop {APP}")
    print("done — app force-stopped; the re-test gate is OPEN (a fresh test "
          "can be taken right now)")


if __name__ == "__main__":
    sys.exit(main())
