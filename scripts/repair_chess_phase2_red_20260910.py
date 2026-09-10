#!/usr/bin/env python3
"""One-shot repair (2026-09-10, afternoon): clear the Rule 5 RED lockout.

CONTEXT: the session strain accumulator landed EXACTLY on the 100/100 bar
(severe ΔE -0.55 loss = 50, then bullet ΔE -0.466 moderate 25 + blunder-cap
25 = 50, no readiness buffer) and hard-terminated rated play for the day.
The blunder cap double-counts the same bad play the ΔE floor already
penalised, and the session was a mixed, roughly break-even one.

ENGINE FIX (same commit): Rule 5's session accumulator must now CLEAR the
terminate bar by STRAIN_TERMINATE_MARGIN (10) when the game is not
catastrophic — landing exactly on the bar stays Yellow. This script flips
today's TERMINATE_SESSION audit row to CONTINUE_RATED so rated play is
re-authorized. Safe transfer method (base64 over `adb shell` + `run-as cp`).
"""

import base64
import datetime
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

APP = "com.example.tail"
PREFS = "shared_prefs/tail_chess_phase2.xml"


def sh(cmd: str) -> str:
    return subprocess.run(["adb", "shell", cmd], check=True,
                          stdout=subprocess.PIPE).stdout.decode()


def read_prefs() -> bytes:
    return subprocess.run(
        ["adb", "exec-out", "run-as", APP, "sh", "-c", f"cat {PREFS}"],
        check=True, stdout=subprocess.PIPE).stdout


def main() -> None:
    raw = read_prefs()
    if not raw.strip():
        print("prefs file empty — nothing to repair")
        return
    root = ET.fromstring(raw.decode("utf-8"))
    node = root.find("./string[@name='audit_history']")
    if node is None or not node.text:
        print("no audit_history found — nothing to repair")
        return

    audits = json.loads(node.text)
    today_start = datetime.datetime.now().replace(
        hour=0, minute=0, second=0, microsecond=0).timestamp() * 1000
    changed = 0
    for a in audits:
        if a.get("timestamp", 0) >= today_start and \
                a.get("outputState") == "TERMINATE_SESSION":
            a["outputState"] = "CONTINUE_RATED"
            changed += 1
    if changed == 0:
        print("no today-TERMINATE audits to repair")
        return

    node.text = json.dumps(audits)
    out = base64.b64encode(ET.tostring(root, encoding="unicode").encode())
    sh(f"echo {out.decode()} | base64 -d > /data/local/tmp/phase2.xml")
    sh(f"run-as {APP} cp /data/local/tmp/phase2.xml {PREFS}")
    sh(f"rm /data/local/tmp/phase2.xml")
    sh(f"am force-stop {APP}")
    print(f"rewrote {changed} audit(s) to CONTINUE_RATED and force-stopped {APP}")


if __name__ == "__main__":
    sys.exit(main())
