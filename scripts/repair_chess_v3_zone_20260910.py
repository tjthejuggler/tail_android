#!/usr/bin/env python3
"""One-shot repair (2026-09-10, late afternoon): re-zone the 15:27 survival
run from RED to YELLOW under the corrected zone rule.

CONTEXT: the survival gate timed out with 18/19 solved (95% of the enforced
bar). The old mapping failed ANY timeout to RED_LIGHT regardless of the
solved count, while a strike at the same count would have been YELLOW.

ENGINE FIX (same commit): a failed survival run is now zoned by the
solved-count ratio at the moment of failure — strike and timeout are
treated identically. solved ≥ ½ enforced bar → YELLOW (ccrs 65, marginal
30-min band, casual play allowed); solved < ½ → RED. Reflex fail stays RED.

This script flips today's 15:27:42 FAIL_TIMEOUT history entry (18 passed,
bar 19, ratio 36/19 ≥ 1) from RED_LIGHT/40 to YELLOW_LIGHT/65 in BOTH the
shared v1 test history (what Chess Guard + checkGate read) and the v3
telemetry results log (state/ccrs fields only — the verdict stays
FAIL_TIMEOUT for honest telemetry). Safe transfer method (base64 over
`adb shell` + `run-as cp`).
"""

import base64
import datetime
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

import os

APP = "com.example.tail"
V1_PREFS = "shared_prefs/tail_chess_readiness.xml"
V3_PREFS = "shared_prefs/tail_chess_readiness_v3.xml"

# Pin the online device when several adb endpoints (incl. stale/offline
# ones) are registered — otherwise adb aborts with "more than one device".
_serials = subprocess.run(["adb", "devices"], check=True,
                          stdout=subprocess.PIPE).stdout.decode()
_online = [ln.split()[0] for ln in _serials.splitlines()
           if ln.strip().endswith("\tdevice")]
if _online:
    os.environ["ANDROID_SERIAL"] = _online[0]
    print(f"using device {_online[0]}")

# The 15:27:42 local run today: FAIL_TIMEOUT, 18 passed, bar 19.
# Located by verdict+count within today (timestamps carry milliseconds).
TODAY = datetime.date(2026, 9, 10)
TARGET_VERDICT = "FAIL_TIMEOUT"
TARGET_PASSED = 18
TARGET_BAR = 19


def sh(cmd: str) -> str:
    return subprocess.run(["adb", "shell", cmd], check=True,
                          stdout=subprocess.PIPE).stdout.decode()


def read_prefs(path: str) -> bytes:
    return subprocess.run(
        ["adb", "exec-out", "run-as", APP, "sh", "-c", f"cat {path}"],
        check=True, stdout=subprocess.PIPE).stdout


def write_prefs(path: str, xml_text: str) -> None:
    # The v3 prefs file is >100 KB — inlining base64 over argv overflows
    # "Argument list too long". Stream through a local file + adb push.
    local = "/tmp/repair_prefs.xml"
    with open(local, "w") as f:
        f.write(xml_text)
    subprocess.run(["adb", "push", local, "/data/local/tmp/repair.xml"],
                   check=True, stdout=subprocess.DEVNULL)
    sh(f"run-as {APP} cp /data/local/tmp/repair.xml {path}")
    sh("rm /data/local/tmp/repair.xml")
    import os
    os.remove(local)


def main() -> None:
    # ── locate the exact timestamp from the v3 telemetry log ───────────────
    raw = read_prefs(V3_PREFS)
    if not raw.strip():
        print("v3 prefs file empty — nothing to repair")
        return
    root = ET.fromstring(raw.decode("utf-8"))
    node = root.find("./string[@name='results']")
    if node is None or not node.text:
        print("no v3 results found — nothing to repair")
        return
    results = json.loads(node.text)

    ts = 0
    for r in results:
        day = datetime.datetime.fromtimestamp(
            r["timestamp"] / 1000).date()
        if (day == TODAY and r.get("verdict") == TARGET_VERDICT
                and r.get("passed") == TARGET_PASSED
                and r.get("target") == TARGET_BAR):
            ts = r["timestamp"]

    if ts == 0:
        print("no matching v3 result — nothing to repair")
        return

    # ── 1. v3 telemetry: state + ccrs only (verdict kept for honesty) ──────
    for r in results:
        if r.get("timestamp") == ts:
            print(f"v3 result @ "
                  f"{datetime.datetime.fromtimestamp(ts / 1000)}: "
                  f"state={r['state']} ccrs={r['ccrs']} → YELLOW_LIGHT/65 "
                  f"(verdict {r['verdict']} kept)")
            r["state"] = "YELLOW_LIGHT"
            r["ccrs"] = 65
    node.text = json.dumps(results)
    write_prefs(V3_PREFS, ET.tostring(root, encoding="unicode"))

    # ── 2. shared v1 test history (what Chess Guard + checkGate read) ──────
    raw = read_prefs(V1_PREFS)
    if not raw.strip():
        print("v1 prefs file empty — v3 already repaired")
        sh(f"am force-stop {APP}")
        return
    root = ET.fromstring(raw.decode("utf-8"))
    node = root.find("./string[@name='test_history']")
    changed = 0
    if node is not None and node.text:
        hist = json.loads(node.text)
        for t in hist:
            if t.get("timestamp") == ts:
                print(f"v1 entry @ "
                      f"{datetime.datetime.fromtimestamp(ts / 1000)}: "
                      f"state={t['state']} ccrs={t['ccrs']} → "
                      f"YELLOW_LIGHT/65")
                t["state"] = "YELLOW_LIGHT"
                t["ccrs"] = 65
                changed += 1
        if changed:
            node.text = json.dumps(hist)
            write_prefs(V1_PREFS, ET.tostring(root, encoding="unicode"))

    sh(f"am force-stop {APP}")
    print(f"re-zoned entry @ {ts} to YELLOW_LIGHT/65 "
          f"(v1 entries changed: {changed}) and force-stopped {APP}")


if __name__ == "__main__":
    sys.exit(main())
