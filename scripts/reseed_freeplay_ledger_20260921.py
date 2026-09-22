#!/usr/bin/env python3
"""One-shot freeplay ledger reseed (2026-09-21, evening).

Root cause of the wrong balance display: the morning retro-fix script wrote
`<int name="balance">2</int>` (text-node form). Android SharedPreferences
require the `value="2"` ATTRIBUTE — the malformed entry was dropped by the
XML parser, the app found no balance and lazily re-initialized it to 1
(the first-week grant), and a stale pre-update process later wrote
balance=3. Display consequently showed 1, then 3.

The store has since been redesigned to a DERIVED balance
(granted_total − spent + refunded) that cannot desynchronize. This script
seeds the new scheme's facts correctly (proper value attributes, app
force-stopped FIRST so no stale process overwrites the file):

  - granted_total = 3  → derived balance = 3 − 2 spent = 1
    (2 seeded credits − the retro session at 15:36 − the test session
    the user started at 19:09 = 1 left)
  - ledger = both usage entries, UNSETTLED. The app settles each when its
    60-minute window expires; the 15:36 session (net −13 bullet chess960)
    will stay spent, the live 19:09 session can still earn its refund.

Idempotent: re-running rewrites the same file.
"""

import base64
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

APP = "com.example.tail"
FREEPLAY_PREFS = "shared_prefs/tail_chess_freeplay.xml"

# The two spent sessions (testTimestamp = the v1 freeplay entry stamps).
USAGE = [
    1789997799310,   # 15:36:39 retro session (net −13, stays spent)
    1790010595895,   # 19:09:55 live test session (window pending at seed)
]
GRANTED_TOTAL = 3


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


def fix(serial):
    print(f"── device {serial}")
    # 1. Force-stop FIRST: a running pre-update process must not rewrite
    #    the prefs file after we seed it.
    subprocess.run(["adb", "-s", serial, "shell", f"am force-stop {APP}"],
                   check=True, stdout=subprocess.DEVNULL)

    # 2. Read the CURRENT ledger (preserve any live-session entries).
    b64 = subprocess.run(
        ["adb", "-s", serial, "shell",
         f"run-as {APP} base64 {FREEPLAY_PREFS}"],
        check=True, stdout=subprocess.PIPE).stdout.decode()
    existing = []
    try:
        root = ET.fromstring(base64.b64decode("".join(b64.split())).decode())
        node = next((n for n in root.findall("./string")
                     if n.get("name") == "ledger"), None)
        if node is not None and node.text:
            existing = [u["testTimestamp"] for u in json.loads(node.text)]
    except Exception:
        pass

    merged = list(dict.fromkeys(existing + USAGE))

    # 3. Write the corrected prefs file (value ATTRIBUTES this time).
    root = ET.Element("map")
    ET.SubElement(root, "int", {
        "name": "granted_total", "value": str(GRANTED_TOTAL)
    })
    ET.SubElement(root, "long", {
        "name": "last_accrual_ms", "value": str(int(time.time() * 1000))
    })
    ET.SubElement(root, "string", {"name": "ledger"}).text = json.dumps(
        [{"timestamp": ts, "testTimestamp": ts} for ts in merged]
    )
    data = ('<?xml version=\'1.0\' encoding=\'utf-8\' standalone=\'yes\' ?>\n'
            + ET.tostring(root, encoding="unicode")).encode()

    b64payload = base64.b64encode(data).decode()
    tmp = f"/data/local/tmp/tail_fp_reseed_{int(time.time())}.b64"
    subprocess.run(["adb", "-s", serial, "shell", f"cat > {tmp}"],
                   input=(b64payload + "\n").encode(), check=True,
                   stdout=subprocess.DEVNULL)
    subprocess.run(
        ["adb", "-s", serial, "shell",
         f"run-as {APP} sh -c 'base64 -d {tmp} > {FREEPLAY_PREFS}'"],
        check=True, stdout=subprocess.DEVNULL)
    subprocess.run(["adb", "-s", serial, "shell", f"rm {tmp}"],
                   check=True, stdout=subprocess.DEVNULL)

    print(f" granted_total={GRANTED_TOTAL}, ledger={merged} "
          f"→ derived balance {GRANTED_TOTAL - len(merged)}")


def main():
    serials = pick_serials()
    print(f"freeplay ledger reseed on {len(serials)} device(s)")
    for s in serials:
        fix(s)
    print("done — apps force-stopped; next open reads the seeded ledger")


if __name__ == "__main__":
    sys.exit(main())
