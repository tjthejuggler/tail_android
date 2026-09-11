#!/usr/bin/env python3
"""Dumps the on-device Phase-2 audit history + v3 results to diagnose why
session-based correlation subsections are empty (join audit-game-run)."""
import json
import re
import subprocess

CUTOFF = 1788818400000  # SURVIVAL_UNLIMITED_CUTOFF_MS

QUOT = chr(38) + "quot;"      # "
LT = chr(38) + "lt;"          # <
GT = chr(38) + "gt;"          # >
AMP = chr(38) + "amp;"        # &


def adb_xml(pref_file):
    return subprocess.run(
        ["adb", "shell", "run-as", "com.example.tail",
         "cat", "shared_prefs/" + pref_file + ".xml"],
        capture_output=True, text=True,
    ).stdout


def string_value(raw, key):
    m = re.search(
        '<string name="' + re.escape(key) + '">(.*?)</string>', raw, re.S)
    if not m:
        return None
    v = m.group(1)
    v = v.replace(QUOT, '"').replace(LT, "<").replace(GT, ">")
    v = v.replace(AMP, chr(38))
    return v


def main():
    raw = adb_xml("tail_chess_phase2")
    audits_raw = string_value(raw, "audit_history")
    if not audits_raw:
        print("NO audit_history in tail_chess_phase2.xml")
        return
    audits = json.loads(audits_raw)
    print("=== audits: %d ===" % len(audits))
    for a in audits[-10:]:
        print(a["timestamp"],
              "POST" if a["timestamp"] >= CUTOFF else "pre",
              a.get("timeControl"), "acc=", a.get("caps2Accuracy"),
              "counted=", a.get("accuracyCounted"),
              "acpl=", a.get("analysisAcpl", "ABSENT"),
              "blun=", a.get("blunders", "ABSENT"),
              "unf=", a.get("unforcedBlunders", "ABSENT"),
              "gameId=", repr(a.get("gameId", ""))[:20])

    raw3 = adb_xml("tail_chess_readiness_v3")
    results_raw = string_value(raw3, "results")
    if not results_raw:
        print("NO results in tail_chess_readiness_v3.xml")
        return
    results = json.loads(results_raw)
    print()
    print("=== v3 results: %d ===" % len(results))
    post = [r for r in results if r["timestamp"] >= CUTOFF
            and r.get("verdict") != "FAIL_REFLEX"]
    print("eligible (post-cutoff, non-reflex-fail): %d" % len(post))
    for r in results[-8:]:
        print(r["timestamp"],
              "POST" if r["timestamp"] >= CUTOFF else "pre",
              r.get("verdict"), "passed=", r.get("passed"),
              "rt=", r.get("meanRtMs", "ABSENT"))

    log_raw = subprocess.run(
        ["adb", "shell",
         "run-as com.example.tail cat files/chess_readiness_log.json"],
        capture_output=True, text=True).stdout
    try:
        log = json.loads(log_raw)
        games = log.get("games", [])
    except Exception:
        games = []
        print("(could not parse chess_readiness_log.json - skipping join)")
    if games:
        print()
        print("=== games in log: %d ===" % len(games))
        rated = [g for g in games if g.get("rated", True)]
        for r in post[-6:]:
            win = [g for g in rated
                   if r["timestamp"] < g["endTimeMs"]
                   <= r["timestamp"] + 6 * 3600000]
            print("run %d: following rated games = %d" % (r["timestamp"], len(win)))
        joins = 0
        for g in rated[-50:]:
            near = [a for a in audits
                    if abs(a["timestamp"] - g["endTimeMs"]) <= 60000]
            if near:
                joins += 1
        print("last-50 rated games with a joined audit: %d" % joins)


if __name__ == "__main__":
    main()
