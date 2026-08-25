#!/usr/bin/env python3
"""One-shot: re-analyze chess.com game 173512925270 with the fixed
win%-based classifier and replace the artifact-laden cached row.

The original depth-12 analysis classified four mate-conversion moves as
blunders (mate-score folding artifact) and reported ACPL ~490. chess.com's
Game Review of the same game: 0 blunders. This script rebuilds the PGN from
the stored per-move data (SAN + clock comments), deletes the cached row, and
re-analyzes at the maximum live depth so the registry — and any future phone
fetch of this game — carries sane numbers.
"""

import io
import json
import os
import sqlite3
import sys

BRIDGE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "tail_bridge")
sys.path.insert(0, BRIDGE)

# Local repair run: no desktop notification, no chess-coach push.
os.environ["CHESS_ANALYSIS_NOTIFY"] = "0"
os.environ["CHESS_COACH_INGEST_URL"] = ""

import chess  # noqa: E402
import chess.pgn  # noqa: E402

import chess_analysis as ca  # noqa: E402

GAME_ID = "173512925270"
DB = os.path.join(BRIDGE, "data", "chess_analysis.db")


def load_stored() -> dict:
    conn = sqlite3.connect(DB)
    row = conn.execute(
        "SELECT json FROM analyses WHERE game_id = ?", (GAME_ID,)
    ).fetchone()
    conn.close()
    if not row:
        raise SystemExit(f"no stored analysis for {GAME_ID}")
    return json.loads(row[0])


def build_pgn(stored: dict) -> str:
    headers = stored.get("metadata", {})
    lines = []
    for k, v in headers.items():
        safe = str(v).replace('"', "")
        lines.append(f'[{k} "{safe}"]')
    lines.append("")

    def clk(sec):
        if sec is None:
            return None
        sec = max(0, int(round(sec)))
        h, m, s = sec // 3600, (sec % 3600) // 60, sec % 60
        return "{[%clk " + f"{h}:{m:02d}:{s:02d}" + "]}"

    tokens = []
    for i, mv in enumerate(stored["all_moves"]):
        if i % 2 == 0:
            tokens.append(f"{i // 2 + 1}.")
        tokens.append(mv["move"])
        c = clk(mv.get("clock_sec"))
        if c:
            tokens.append(c)
    tokens.append(headers.get("Result", "*"))
    lines.append(" ".join(tokens))
    return "\n".join(lines) + "\n"


def main() -> int:
    stored = load_stored()
    old = stored["stats"]
    pgn_text = build_pgn(stored)

    game = chess.pgn.read_game(io.StringIO(pgn_text))
    if game is None:
        raise SystemExit("reconstructed PGN unreadable")
    if ca.game_id_of(game) != GAME_ID:
        raise SystemExit(f"game_id mismatch: {ca.game_id_of(game)}")
    if not game.variations:
        raise SystemExit("reconstructed PGN has no moves")

    # Drop the stale row so the service re-analyzes instead of dedup-hitting.
    conn = sqlite3.connect(DB)
    conn.execute("DELETE FROM analyses WHERE game_id = ?", (GAME_ID,))
    conn.commit()
    conn.close()

    summary = ca.ChessAnalysisService(db_path=DB).analyze(
        pgn_text, game_id=GAME_ID, username="jugglah", depth=ca.LIVE_DEPTH_MAX
    )

    print("OLD (depth 12, fixed-cp thresholds):")
    print(f"  user  blunders={old['white_blunders']} unforced={old['white_unforced_blunders']}"
          f" mistakes={old['white_mistakes']} inaccuracies={old['white_inaccuracies']}"
          f" acpl={old['white_acpl']:.1f}")
    u = summary["user"]
    print("NEW (depth 18, win% classification):")
    print(f"  user  blunders={u['blunders']} unforced={u['unforced_blunders']}"
          f" mistakes={u['mistakes']} inaccuracies={u['inaccuracies']} acpl={u['acpl']}")
    print(f"  verdict for the v3 audit (rapid maxBlunders=1): "
          f"unforced {u['unforced_blunders']} >= 1 -> "
          f"{'VIOLATION' if (u['unforced_blunders'] or 0) >= 1 else 'no violation (GREEN)'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
