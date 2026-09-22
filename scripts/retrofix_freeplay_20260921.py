#!/usr/bin/env python3
"""One-shot freeplay retro-fix + grant (2026-09-21).

The last readiness "test" of 2026-09-21 (the 15:36:39 v1 entry, verdict
PASS at CCRS 85 in the v3 telemetry) was NOT actually passed — the user
failed the survival gate at puzzle 16 but wanted to keep playing, and
devised the weekly-freeplay system for exactly this situation. This script
rewrites history to match reality under the new system:

  1. v1 test history (`tail_chess_readiness.xml`): the 15:36:39 GREEN_LIGHT
     entry becomes the FAILED test (RED_LIGHT, ccrs 40 — fail-strike below
     half the bar at puzzle 16 of target 20) stamped one second EARLIER,
     and a new flagged freeplay entry (GREEN_LIGHT, ccrs 85, freeplay=true)
     takes its place at 15:36:39. Authorization state is unchanged — the
     post-game audit still sees a pass-grade CCRS — but the record now
     honestly shows the failed test + the freeplay that authorized play.
  2. v3 telemetry (`tail_chess_readiness_v3.xml`): the matching 15:36:39
     result becomes FAIL_STRIKE / RED_LIGHT / ccrs 40 with passed=16.
  3. Detailed activity log (`files/chess_readiness_log.json`): the test
     entries are mirrored (the log seeds itself from v1 history and would
     otherwise re-import the stale PASS), and every game whose readiness
     context resolves to the 15:36:39 entry gets `freeplayAtPlay: true` so
     the freeplay-vs-pass comparison covers it retroactively.
  4. Freeplay ledger (`tail_chess_freeplay.xml`): seeded with a usage entry
     for the retro-fix and a balance of 2 credits (accrual anchored now).

Idempotent: re-running detects the already-fixed state and exits cleanly.
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
FREEPLAY_PREFS = "shared_prefs/tail_chess_freeplay.xml"
LOG_FILE = "files/chess_readiness_log.json"

# The session being rewritten: the v1/v3 entry stamped 2026-09-21 15:36:39
# local (Europe/Rome, UTC+2) = 13:36:39 UTC.
BAD_TS = 1789997799310
FAILED_PASS = 16          # puzzles actually solved before the strike
FAILED_TARGET = 20        # enforced bar
NEW_CCRS = 40             # RED (below half the enforced bar solved)
GOOD_CCRS = 85            # the pass-grade CCRS the freeplay carries
SEED_BALANCE = 2          # credits granted to the user now


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


def make_adb(serial):
    def sh(cmd):
        return subprocess.run(["adb", "-s", serial, "shell", cmd], check=True,
                              stdout=subprocess.PIPE).stdout.decode()

    def read_remote(path):
        b64 = subprocess.run(
            ["adb", "-s", serial, "shell", f"run-as {APP} base64 {path}"],
            check=True, stdout=subprocess.PIPE).stdout.decode()
        return base64.b64decode("".join(b64.split()))

    def write_remote(path, data: bytes):
        b64 = base64.b64encode(data).decode()
        tmp = f"/data/local/tmp/tail_freeplay_{int(time.time())}.b64"
        subprocess.run(["adb", "-s", serial, "shell", f"cat > {tmp}"],
                       input=(b64 + "\n").encode(), check=True,
                       stdout=subprocess.DEVNULL)
        sh(f"run-as {APP} sh -c 'base64 -d {tmp} > {path}'")
        sh(f"rm {tmp}")

    return sh, read_remote, write_remote


def prefs_node(root, name):
    return next((n for n in root.findall("./string")
                 if n.get("name") == name), None)


def fix(serial):
    sh, read_remote, write_remote = make_adb(serial)
    print(f"── device {serial}")

    # ── 1 + 2. v1 test history & v3 telemetry ────────────────────────────
    root = ET.fromstring(read_remote(V1_PREFS).decode("utf-8"))
    node = prefs_node(root, "test_history")
    if node is None or not node.text:
        sys.exit(f"{serial}: ERROR: v1 test_history missing")
    hist = json.loads(node.text)

    # Idempotency: a freeplay entry at the original slot means v1/v3 are
    # already rewritten (the freeplay GRANT keeps that timestamp).
    already = next((t for t in hist
                    if t.get("timestamp") == BAD_TS and t.get("freeplay")), None)
    if already is not None:
        print(" already fixed (freeplay entry present) — skipping v1/v3")
        fixed_already = True
    else:
        bad = next((t for t in hist if t.get("timestamp") == BAD_TS), None)
        if bad is None:
            sys.exit(f"{serial}: ERROR: entry {BAD_TS} not found; "
                     "refusing to guess")
        fixed_already = False

    if not fixed_already:
        # 1a. The failed test, stamped 1 s before the freeplay grant.
        bad["timestamp"] = BAD_TS - 1000
        bad["state"] = "RED_LIGHT"
        bad["ccrs"] = NEW_CCRS
        # 1b. The freeplay authorization takes the original slot.
        hist.append({
            "timestamp": BAD_TS,
            "ccrs": GOOD_CCRS,
            "state": "GREEN_LIGHT",
            "freeplay": True,
        })
        hist.sort(key=lambda t: t["timestamp"])
        node.text = json.dumps(hist)
        write_remote(V1_PREFS, ET.tostring(root, encoding="unicode").encode())
        print(f" v1: PASS@{BAD_TS} -> RED fail(puzzles {FAILED_PASS}/"
              f"{FAILED_TARGET})@{BAD_TS - 1000} + freeplay GREEN@{BAD_TS}")

        # v3 telemetry: keep the failed run honest (same 1 s shift).
        try:
            root3 = ET.fromstring(read_remote(V3_PREFS).decode("utf-8"))
            node3 = prefs_node(root3, "results")
            if node3 is not None and node3.text:
                results = json.loads(node3.text)
                moved = False
                for r in results:
                    if r.get("timestamp") == BAD_TS:
                        r["timestamp"] = BAD_TS - 1000
                        r["verdict"] = "FAIL_STRIKE"
                        r["state"] = "RED_LIGHT"
                        r["ccrs"] = NEW_CCRS
                        r["passed"] = FAILED_PASS
                        moved = True
                if moved:
                    node3.text = json.dumps(results)
                    write_remote(V3_PREFS, ET.tostring(root3,
                                                       encoding="unicode").encode())
                    print(f" v3: result rewritten to FAIL_STRIKE "
                          f"(passed {FAILED_PASS}/{FAILED_TARGET})")
        except subprocess.CalledProcessError:
            print(" v3 prefs unreadable — v1 fix stands")

    # ── 3. Detailed activity log (tests mirror + games marked) ───────────
    try:
        log = json.loads(read_remote(LOG_FILE).decode("utf-8"))
    except subprocess.CalledProcessError:
        log = None
    if log is not None:
        changed = False

        tests = log.get("tests", [])
        # Old PASS copy of the same test (both timestamps).
        tests = [t for t in tests if t.get("timestamp") not in (BAD_TS, BAD_TS - 1000)
                 or t.get("freeplay")]
        if not any(t.get("timestamp") == BAD_TS - 1000 and not t.get("freeplay")
                   for t in tests):
            tests.append({
                "timestamp": BAD_TS - 1000, "ccrs": NEW_CCRS,
                "state": "RED_LIGHT", "sSleep": 0, "sClarity": 0,
                "pPuzzle": 0, "pRush": 0, "sleepScore": 0,
                "sleepFromGarmin": False, "stress": 0, "focus": 0,
                "energy": 0, "puzzleTimesSec": [], "rushScore": 0,
                "rushStrikes": 0, "rushAllTimeHigh": 0,
                "sessionStartedAt": BAD_TS - 1000 - 171000,
            })
            changed = True
        if not any(t.get("timestamp") == BAD_TS and t.get("freeplay")
                   for t in tests):
            tests.append({
                "timestamp": BAD_TS, "ccrs": GOOD_CCRS,
                "state": "GREEN_LIGHT", "sSleep": 0, "sClarity": 0,
                "pPuzzle": 0, "pRush": 0, "sleepScore": 0,
                "sleepFromGarmin": False, "stress": 0, "focus": 0,
                "energy": 0, "puzzleTimesSec": [], "rushScore": 0,
                "rushStrikes": 0, "rushAllTimeHigh": 0,
                "sessionStartedAt": BAD_TS - 171000, "freeplay": True,
            })
            changed = True
        tests.sort(key=lambda t: t.get("timestamp", 0))
        log["tests"] = tests

        # Mark games authorized by this entry as freeplay play.
        marked = 0
        for g in log.get("games", []):
            start_ms = g.get("endTimeMs", 0) - int(g.get("minutes", 0.0) * 60_000)
            if (g.get("authorized") and g.get("stateAtPlay") == "GREEN_LIGHT"
                    and BAD_TS - 1000 <= start_ms <= BAD_TS + 60 * 60 * 1000):
                if not g.get("freeplayAtPlay"):
                    g["freeplayAtPlay"] = True
                    marked += 1
                    changed = True
        if changed:
            write_remote(LOG_FILE, json.dumps(log).encode())
            print(f" log: tests mirrored, {marked} game(s) marked freeplay")
        else:
            print(" log: already up to date")
    else:
        print(" log: unreadable/absent — will self-seed from v1 on next open")

    # ── 4. Freeplay ledger: usage record + balance 2 ─────────────────────
    fp_root = ET.Element("map")
    led = [{"timestamp": BAD_TS, "testTimestamp": BAD_TS}]
    ET.SubElement(fp_root, "string", {"name": "ledger"}).text = json.dumps(led)
    ET.SubElement(fp_root, "int", {"name": "balance"}).text = str(SEED_BALANCE)
    ET.SubElement(fp_root, "long",
                  {"name": "last_accrual_ms"}).text = str(int(time.time() * 1000))
    write_remote(FREEPLAY_PREFS, ET.tostring(fp_root, encoding="unicode").encode())
    print(f" freeplay: balance seeded to {SEED_BALANCE}, usage recorded")

    sh(f"am force-stop {APP}")
    print(" app force-stopped — changes take effect on next open")


def main():
    serials = pick_serials()
    print(f"freeplay retro-fix on {len(serials)} device(s): "
          "failed test restored, freeplay recorded, balance 2")
    for s in serials:
        fix(s)
    print("done")


if __name__ == "__main__":
    sys.exit(main())
