#!/usr/bin/env python3
"""One-shot repair (2026-09-07): fix inflated targetReachedMs in v3 results.

CONTEXT: since the "keep going to 5:00" change, a survival run continues
past the gate target. A bug in FloatingBubbleService.onSurvivalPass made
EVERY pass at-or-after the target overwrite targetReachedMs, so any run
where extra puzzles were answered recorded the time of the LAST pass
before the run ended instead of the first pass that reached the target —
corrupting the time-to-gate-target readiness datapoint.

REPAIR: the per-puzzle telemetry log (survival_events) is intact. Puzzle
stopwatches are contiguous (puzzle N+1's stopwatch starts the instant
puzzle N's PASS is pressed; puzzle 1 starts at ▶ START), so the true
time-to-target = sum of durationMs of the PASS events with
puzzle_index <= target within the same session. PASS results whose
stored targetReachedMs disagrees with that sum by > 500 ms are corrected;
everything else is left untouched. No data is deleted.

The engine-side latch fix (!survivalGatePassed guard) prevents this from
recurring, so this script should not need to run again.

Usage:
    python3 scripts/repair_survival_time_to_target_20260907.py          # dry run
    python3 scripts/repair_survival_time_to_target_20260907.py --apply  # write back

Uses the SAFE transfer method (base64 over `adb shell` + `run-as cat`),
never piping stdin into `run-as sh -c 'cat > file'`.
"""

import base64
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

APP = "com.example.tail"
PREFS = "shared_prefs/tail_chess_readiness_v3.xml"
TOLERANCE_MS = 500


def sh(cmd: str) -> str:
    # Single quoted string: adb shell re-parses args, so an argv list
    # would lose the sh -c quoting and run as the adb (shell) user.
    return subprocess.run(
        ["adb", "shell", f"run-as {APP} sh -c '{cmd}'"],
        check=True, stdout=subprocess.PIPE).stdout.decode()


def pull_prefs() -> ET.Element:
    raw = subprocess.run(
        ["adb", "exec-out", "run-as", APP, "cat", PREFS],
        check=True, stdout=subprocess.PIPE).stdout
    if not raw.strip():
        sys.exit("prefs file empty — is the device connected / app installed?")
    return ET.fromstring(raw.decode("utf-8"))


def push_prefs(root: ET.Element) -> None:
    xml_bytes = ET.tostring(root, encoding="utf-8")
    tmp_local = "/tmp/tail_v3_repair.xml"
    tmp_remote = "/data/local/tmp/tail_v3_repair.xml"
    with open(tmp_local, "wb") as f:
        f.write(xml_bytes)
    subprocess.run(["adb", "push", tmp_local, tmp_remote], check=True)
    # run-as cp so the file ends up owned by the app uid.
    full = f"/data/data/{APP}/{PREFS}"
    sh(f"cat {tmp_remote} > {full}")
    subprocess.run(["adb", "shell", "rm", "-f", tmp_remote], check=False)
    # Force a prefs reload so a running app instance doesn't overwrite
    # the file from its stale in-memory copy on its next write.
    subprocess.run(["adb", "shell", "am", "force-stop", APP], check=False)


def main() -> None:
    apply = "--apply" in sys.argv
    root = pull_prefs()

    results_node = root.find("./string[@name='results']")
    events_node = root.find("./string[@name='survival_events']")
    if results_node is None or not results_node.text:
        sys.exit("no v3 results found — nothing to repair")
    results = json.loads(results_node.text)
    events = json.loads(events_node.text) if events_node is not None and \
        events_node.text else []

    # session -> {index -> durationMs} for PASS events only
    pass_durations: dict = {}
    for e in events:
        if e.get("verdict") == "PASS":
            pass_durations.setdefault(e.get("session"), {})[e.get("index")] = \
                e.get("durationMs", 0)

    changed = 0
    for r in results:
        if r.get("verdict") != "PASS" or r.get("targetReachedMs", 0) <= 0:
            continue
        target = r.get("target", 0)
        durs = pass_durations.get(r.get("sessionStartedAt"), {})
        recomputed = sum(durs.get(i, 0) for i in range(1, target + 1))
        if recomputed <= 0:
            print(f"  ⚠ session {r.get('sessionStartedAt')}: no telemetry "
                  f"events for target 1..{target} — left as-is")
            continue
        stored = r.get("targetReachedMs", 0)
        if abs(stored - recomputed) > TOLERANCE_MS:
            print(f"  session {r.get('sessionStartedAt')}: "
                  f"targetReachedMs {stored} -> {recomputed} "
                  f"(target={target}, passed={r.get('passed')})")
            r["targetReachedMs"] = recomputed
            changed += 1
        else:
            print(f"  session {r.get('sessionStartedAt')}: OK "
                  f"({stored} ms)")

    if changed == 0:
        print("all PASS records already consistent — nothing to do")
        return
    if not apply:
        print(f"\nDRY RUN — {changed} record(s) would be corrected. "
              f"Re-run with --apply to write them.")
        return

    results_node.text = json.dumps(results)
    push_prefs(root)
    print(f"\nApplied: {changed} record(s) corrected in {PREFS}")


if __name__ == "__main__":
    main()
