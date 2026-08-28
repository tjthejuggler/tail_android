#!/usr/bin/env python3
"""
Tail Bridge chess analysis — self-owned Stockfish analysis for the Tail app.

The Tail bundle (phone app + tail_bridge) is fully standalone: it needs only
python-chess and a Stockfish binary — NOT the chess-coach program. The JSON
output format is ported 1:1 from chess-coach's analyzer so every fresh
analysis is pushed (best-effort, fire-and-forget) to chess-coach's /ingest
endpoint, but the move CLASSIFICATION deliberately diverges from chess-coach's
original fixed-centipawn thresholds (50/200/500 cp):

  fixed cp thresholds count a mate-in-2 → mate-in-7 conversion as a 500 cp
  "blunder", because eval_to_cp() folds mates onto the linear ±10000 scale.
  Real reviewers (chess.com, lichess) classify on a saturating
  WIN-PROBABILITY curve, where every won position sits near 100% and
  conversions between winning states barely register. This module does the
  same (see cp_to_win_percent), which:

  * eliminates phantom blunders in won/winning positions (the artifact that
    once yellow-flagged a clean 1-0 conversion game),
  * keeps counts roughly aligned with chess.com's Game Review, and
  * lets the bands scale by time control — faster games forgive larger
    swings (TC_BAND_SCALE), mirroring the phone app's per-tier maxBlunders
    calibration (bullet 3 / blitz 2 / rapid 1).

Dedup: a SQLite registry keyed by canonical game_id (PGN Link header, with a
date/players/utctime fallback). A game is never analysed twice.

Env knobs:
  STOCKFISH_PATH          (default /usr/games/stockfish)
  CHESS_ANALYSIS_DB       (default <tail_bridge>/data/chess_analysis.db)
  CHESS_ANALYSIS_THREADS  (default min(4, cpu_count)) — modest: the PC may
                          also be running chess-coach's own engine
  CHESS_ANALYSIS_HASH_MB  (default 128)
  CHESS_COACH_INGEST_URL  (default http://127.0.0.1:8011/ingest; "" disables)
  CHESS_ANALYSIS_NOTIFY   (default 1) — desktop notification via notify-send
                          after each live analysis; "0" disables
"""

from __future__ import annotations

import io
import json
import math
import os
import sqlite3
import subprocess
import threading
import time
import urllib.error
import urllib.request

import chess
import chess.engine
import chess.pgn

BRIDGE_DIR = os.path.dirname(os.path.abspath(__file__))

STOCKFISH_PATH = os.getenv("STOCKFISH_PATH", "/usr/games/stockfish")
DB_PATH = os.getenv(
    "CHESS_ANALYSIS_DB", os.path.join(BRIDGE_DIR, "data", "chess_analysis.db")
)
ENGINE_THREADS = int(os.getenv("CHESS_ANALYSIS_THREADS", str(min(4, os.cpu_count() or 1))))
ENGINE_HASH_MB = int(os.getenv("CHESS_ANALYSIS_HASH_MB", "128"))
CHESS_COACH_INGEST_URL = os.getenv("CHESS_COACH_INGEST_URL", "http://127.0.0.1:8011/ingest")
NOTIFY_ENABLED = os.getenv("CHESS_ANALYSIS_NOTIFY", "1") != "0"

LIVE_DEPTH_DEFAULT = 12
LIVE_DEPTH_MAX = 18
INGEST_TIMEOUT_SEC = 3
NOTIFY_TIMEOUT_SEC = 5

# ── Win-probability classification (chess.com / lichess semantics) ──────────

# Logistic slope of the cp → win% curve — the constant lichess and chess.com
# use for "winning chances".
WIN_PERCENT_LAMBDA = 0.00368208

# Base classification bands: how many win-PERCENTAGE-POINTs a move must cost
# the mover (rapid calibration — the strictest tier, slow games leave no
# excuse). A 30-point drop ≈ throwing away a completely winning position.
INACCURACY_DROP_PP = 10.0
MISTAKE_DROP_PP = 20.0
BLUNDER_DROP_PP = 30.0

# Faster time controls forgive proportionally larger swings — a 1-minute
# game legitimately contains wilder eval swings than a 10-minute game. The
# multiplier scales all three bands (a bullet blunder needs 45 pp, not 30).
TC_BAND_SCALE = {"bullet": 1.5, "blitz": 1.25, "rapid": 1.0}

# Per-tier remaining clock (seconds) below which a blunder is time-scramble,
# not an unforced mental lapse. Same 10/20/45 calibration as the Tail app's
# ChessPhase2Engine.TimeControl.scrambleSec.
TC_SCRAMBLE_SEC = {"bullet": 10.0, "blitz": 20.0, "rapid": 45.0}

# A blunder is UNFORCED only when the position was still competitive for the
# mover: mover win% above this before the move (≈ −100 cp, chess-coach's
# original bar, expressed on the win% curve).
UNFORCED_MIN_WIN_PERCENT = 40.0

# Cap on the per-move cp loss that feeds ACPL, so mate-score folding cannot
# explode the average (per-move evals in the JSON stay raw).
ACPL_CP_LOSS_CAP = 1000.0


def cp_to_win_percent(cp: float) -> float:
    """Centipawns (white POV, mates folded onto ±10000) → white win prob 0-100.

    Saturating by design: mate-in-2, mate-in-11 and +9 all sit near 100, so
    conversions between winning states cost almost nothing — the mate-folding
    artifact that plagued fixed-cp thresholds disappears.
    """
    return 100.0 / (1.0 + math.exp(-WIN_PERCENT_LAMBDA * cp))


def time_control_tier(time_control_header: str) -> str:
    """PGN TimeControl header ("600", "180+2", "60", …) → "bullet"/"blitz"/"rapid".

    Mirrors the phone app's ChessGameAuditMapper.timeControlFor: base seconds
    < 180 → bullet, < 600 → blitz, else rapid. Unknown/daily → rapid (the
    strictest calibration — safest default).
    """
    tc = (time_control_header or "").strip()
    if not tc or "/" in tc:
        return "rapid"
    base = tc.split("+")[0]
    try:
        base_seconds = float(base)
    except ValueError:
        return "rapid"
    if base_seconds < 180:
        return "bullet"
    if base_seconds < 600:
        return "blitz"
    return "rapid"


# ── Analyzer (ported 1:1 from chess-coach scripts/analyzer.py) ────────────────

class GameAnalyzer:
    """Single-pass Stockfish game analyzer — chess-coach output format."""

    def __init__(self, engine_path, game, depth=18, threads=None, hash_mb=128):
        self.engine_path = engine_path
        self.depth = depth
        self.threads = threads if threads is not None else ENGINE_THREADS
        self.hash_mb = hash_mb
        self.game = game
        self.board = game.board()
        self.engine = None
        self._last_best = None

    def start_engine(self):
        self.engine = chess.engine.SimpleEngine.popen_uci(self.engine_path)
        self.engine.configure({"Threads": self.threads, "Hash": self.hash_mb})

    def stop_engine(self):
        if self.engine:
            try:
                self.engine.quit()
            except Exception:
                pass
            self.engine = None

    @staticmethod
    def eval_to_cp(score):
        """PovScore (white) → centipawns, mates folded onto the ±10000 scale."""
        if score.is_mate():
            moves_to_mate = score.mate()
            if moves_to_mate > 0:
                return 10000 - (moves_to_mate * 100)
            return -10000 - (moves_to_mate * 100)
        return score.score()

    def analyse_position(self, board):
        """One engine call → (cp_from_white, best_move_uci_or_None)."""
        info = self.engine.analyse(board, chess.engine.Limit(depth=self.depth))
        cp = self.eval_to_cp(info["score"].white())
        best = info.get("pv")[0] if info.get("pv") else None
        return cp, best

    @staticmethod
    def classify_move(drop_pp, tier, move_san, best_move_san):
        """Classify one move from its win-percentage-point cost to the mover.

        @param drop_pp  win% before − win% after, from the MOVER's point of
                        view (≥ 0), already on the saturating curve.
        @param tier     "bullet" / "blitz" / "rapid" — scales the bands.
        """
        if move_san and best_move_san and move_san == best_move_san and move_san.endswith('#'):
            return "Best/Good"
        scale = TC_BAND_SCALE.get(tier, 1.0)
        if drop_pp < INACCURACY_DROP_PP * scale:
            return "Best/Good"
        if drop_pp < MISTAKE_DROP_PP * scale:
            return "Inaccuracy"
        if drop_pp < BLUNDER_DROP_PP * scale:
            return "Mistake"
        return "Blunder"

    @staticmethod
    def determine_game_phase(board):
        piece_count = 0
        for piece_type in [chess.QUEEN, chess.ROOK, chess.BISHOP, chess.KNIGHT]:
            piece_count += len(board.pieces(piece_type, chess.WHITE))
            piece_count += len(board.pieces(piece_type, chess.BLACK))
        queens = len(board.pieces(chess.QUEEN, chess.WHITE)) + len(board.pieces(chess.QUEEN, chess.BLACK))
        if board.fullmove_number <= 12 and piece_count >= 12:
            return "opening"
        if piece_count <= 6 or (queens == 0 and piece_count <= 10):
            return "endgame"
        return "middlegame"

    @staticmethod
    def calculate_heuristics(board):
        """'First Principles' metrics — computed only for key moments."""
        metrics = {}
        colors = [chess.WHITE, chess.BLACK]
        for color in colors:
            back_rank = 0 if color == chess.WHITE else 7
            minor_pieces = [chess.KNIGHT, chess.BISHOP]
            developed_count = 0
            total_minors = 0
            for piece_type in minor_pieces:
                for square in board.pieces(piece_type, color):
                    total_minors += 1
                    if chess.square_rank(square) != back_rank:
                        developed_count += 1
            metrics[f"development_{'white' if color == chess.WHITE else 'black'}"] = {
                "developed": developed_count,
                "total": total_minors,
                "ratio": developed_count / total_minors if total_minors > 0 else 0
            }
        for color in colors:
            king_square = board.king(color)
            shield = 0
            if king_square is not None:
                for delta in [-9, -8, -7, -1, 1, 7, 8, 9]:
                    s = king_square + delta
                    if 0 <= s < 64:
                        sq = chess.square(s % 8, s // 8)
                        piece = board.piece_at(sq)
                        if piece and piece.piece_type == chess.PAWN and piece.color == color:
                            shield += 1
            metrics[f"king_shield_{'white' if color == chess.WHITE else 'black'}"] = shield
        piece_values = {chess.PAWN: 1, chess.KNIGHT: 3, chess.BISHOP: 3,
                        chess.ROOK: 5, chess.QUEEN: 9}
        material = {chess.WHITE: 0, chess.BLACK: 0}
        for color in colors:
            for piece_type, value in piece_values.items():
                material[color] += len(board.pieces(piece_type, color)) * value
        metrics["material_white"] = material[chess.WHITE]
        metrics["material_black"] = material[chess.BLACK]
        return metrics

    def analyze(self):
        self.start_engine()
        results = {
            "metadata": dict(self.game.headers),
            "key_moments": [],
            "all_moves": [],
            "stats": {
                "white_acpl": 0, "black_acpl": 0,
                "white_blunders": 0, "black_blunders": 0,
                "white_mistakes": 0, "black_mistakes": 0,
                "white_inaccuracies": 0, "black_inaccuracies": 0,
                "white_unforced_blunders": 0, "black_unforced_blunders": 0,
                "white_moves": 0, "black_moves": 0,
            },
            "phase_stats": {
                "opening": {"white_errors": 0, "black_errors": 0, "moves": 0},
                "middlegame": {"white_errors": 0, "black_errors": 0, "moves": 0},
                "endgame": {"white_errors": 0, "black_errors": 0, "moves": 0},
            },
            "id": None,
        }
        link = self.game.headers.get("Link", "")
        if link:
            results["id"] = link.split("/")[-1]

        white_cpl_sum = 0.0
        black_cpl_sum = 0.0
        white_moves = 0
        black_moves = 0
        board = self.board
        tier = time_control_tier(self.game.headers.get("TimeControl", ""))

        try:
            node = self.game
            prev_cp, self._last_best = self.analyse_position(board)
            ply_count = 0
            while node.variations:
                next_node = node.variation(0)
                move = next_node.move
                is_white = board.turn == chess.WHITE
                player_color = "White" if is_white else "Black"
                san_move = board.san(move)

                before_cp = prev_cp
                best_uci = self._last_best

                board.push(move)
                curr_cp, self._last_best = self.analyse_position(board)

                if is_white:
                    diff = curr_cp - before_cp
                    cp_loss = -diff if diff < 0 else 0
                else:
                    diff = before_cp - curr_cp
                    cp_loss = -diff if diff < 0 else 0

                # Mover-POV win probabilities on the saturating curve — the
                # classification input that mate-score folding cannot distort.
                if is_white:
                    win_before = cp_to_win_percent(before_cp)
                    win_after = cp_to_win_percent(curr_cp)
                else:
                    win_before = 100.0 - cp_to_win_percent(before_cp)
                    win_after = 100.0 - cp_to_win_percent(curr_cp)
                drop_pp = max(0.0, win_before - win_after)

                best_move_san = None
                if best_uci is not None:
                    try:
                        board.pop()
                        best_move_san = board.san(best_uci)
                        board.push(move)
                    except Exception:
                        best_move_san = None

                game_phase = self.determine_game_phase(board)

                if is_white:
                    white_cpl_sum += min(cp_loss, ACPL_CP_LOSS_CAP)
                    white_moves += 1
                else:
                    black_cpl_sum += min(cp_loss, ACPL_CP_LOSS_CAP)
                    black_moves += 1

                classification = self.classify_move(drop_pp, tier, san_move, best_move_san)

                side = "white" if is_white else "black"
                if classification == "Blunder":
                    results["stats"][f"{side}_blunders"] += 1
                elif classification == "Mistake":
                    results["stats"][f"{side}_mistakes"] += 1
                elif classification == "Inaccuracy":
                    results["stats"][f"{side}_inaccuracies"] += 1

                results["phase_stats"][game_phase]["moves"] += 1
                if classification in ["Blunder", "Mistake", "Inaccuracy"]:
                    results["phase_stats"][game_phase][f"{side}_errors"] += 1

                clock_sec = next_node.clock()

                # Unforced blunder: blunder while the position was still
                # competitive for the mover and not in the tier's time
                # scramble (10/20/45 s — the phone app's scrambleSec).
                scramble_sec = TC_SCRAMBLE_SEC.get(tier, 45.0)
                unforced = (
                    classification == "Blunder"
                    and win_before > UNFORCED_MIN_WIN_PERCENT
                    and (clock_sec is None or clock_sec >= scramble_sec)
                )
                if unforced:
                    results["stats"][f"{side}_unforced_blunders"] += 1

                move_data = {
                    "ply": ply_count + 1,
                    "move_number": (ply_count // 2) + 1,
                    "move": san_move,
                    "color": player_color,
                    "eval_before": before_cp,
                    "eval_after": curr_cp,
                    "cp_loss": cp_loss,
                    "win_drop_pp": round(drop_pp, 1),
                    "classification": classification,
                    "best_move": best_move_san,
                    "game_phase": game_phase,
                    "fen": board.fen(),
                    "clock_sec": clock_sec,
                    "unforced_blunder": unforced,
                }
                results["all_moves"].append(move_data)

                if classification in ["Blunder", "Mistake"]:
                    results["key_moments"].append({
                        "ply": ply_count + 1,
                        "move_number": (ply_count // 2) + 1,
                        "move": san_move,
                        "san": san_move,
                        "color": player_color,
                        "eval_before": before_cp,
                        "eval_after": curr_cp,
                        "cp_loss": cp_loss,
                        "classification": classification,
                        "best_move": best_move_san,
                        "game_phase": game_phase,
                        "heuristics": self.calculate_heuristics(board),
                        "fen": board.fen(),
                        "clock_sec": clock_sec,
                        "unforced_blunder": unforced,
                    })

                prev_cp = curr_cp
                node = next_node
                ply_count += 1
        finally:
            self.stop_engine()

        results["stats"]["white_acpl"] = white_cpl_sum / white_moves if white_moves > 0 else 0
        results["stats"]["black_acpl"] = black_cpl_sum / black_moves if black_moves > 0 else 0
        results["stats"]["white_moves"] = white_moves
        results["stats"]["black_moves"] = black_moves
        return results


# ── Service: cache + dedup + summary + chess-coach push ───────────────────────

def game_id_of(game) -> str:
    """Canonical id: PGN Link header last segment, else date/players/utctime."""
    link = game.headers.get("Link", "")
    if link:
        return link.split("/")[-1]
    date = game.headers.get("Date", "unknown")
    white = game.headers.get("White", "unknown")
    black = game.headers.get("Black", "unknown")
    t = game.headers.get("UTCTime", "")
    return f"{date}_{white}_vs_{black}_{t}".replace(" ", "_").replace("/", "-").replace(":", "-")


def summary_of(results: dict, username: str, cached: bool, engine_ms: int) -> dict:
    """Compact per-side summary — the exact contract the Tail app expects."""
    stats = results.get("stats", {})
    meta = results.get("metadata", {})
    user_lower = (username or "").strip().lower()
    white = (meta.get("White") or "").strip().lower()
    black = (meta.get("Black") or "").strip().lower()
    if user_lower and user_lower == white:
        user_side = "white"
    elif user_lower and user_lower == black:
        user_side = "black"
    elif user_lower and user_lower in white:
        user_side = "white"
    elif user_lower and user_lower in black:
        user_side = "black"
    else:
        user_side = None

    def side_stats(side: str) -> dict:
        return {
            "acpl": round(stats.get(f"{side}_acpl", 0.0), 2),
            "blunders": stats.get(f"{side}_blunders", 0),
            "mistakes": stats.get(f"{side}_mistakes", 0),
            "inaccuracies": stats.get(f"{side}_inaccuracies", 0),
            "unforced_blunders": stats.get(f"{side}_unforced_blunders"),
            "moves": stats.get(f"{side}_moves"),
        }

    user = side_stats(user_side) if user_side else None
    return {
        "game_id": results.get("id"),
        "cached": cached,
        "depth": results.get("_depth", None),
        "engine_ms": engine_ms,
        "user_side": user_side,
        "white": side_stats("white"),
        "black": side_stats("black"),
        "user": user,
    }


def notify_analysis_done(results: dict, elapsed_ms: int) -> bool:
    """Desktop notification after each live Stockfish analysis.

    Fire-and-forget: any failure (no notify-send, no notification daemon,
    headless session) is silently ignored — the analysis result itself is
    already safely stored.
    """
    if not NOTIFY_ENABLED:
        return False
    try:
        meta = results.get("metadata", {})
        stats = results.get("stats", {})
        white = meta.get("White") or "?"
        black = meta.get("Black") or "?"
        depth = results.get("_depth") or "?"
        wb = stats.get("white_blunders", 0)
        bb = stats.get("black_blunders", 0)
        body = (
            f"{white} vs {black}\n"
            f"depth {depth} · {elapsed_ms / 1000.0:.1f}s · "
            f"blunders W {wb} / B {bb} → sent to phone"
        )
        subprocess.run(
            ["notify-send", "-a", "Tail Bridge", "-i", "stockfish",
             "♟ Stockfish analyzed a game", body],
            timeout=NOTIFY_TIMEOUT_SEC,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        return True
    except Exception:
        return False


def push_to_chess_coach(results: dict) -> bool:
    """Best-effort, fire-and-forget handoff so chess-coach never re-analyses.

    Posts the full-format JSON to chess-coach's /ingest endpoint. Any failure
    (not running, timeout, error) is silently ignored — the push is a pure
    optimization for when the user does run chess-coach.
    """
    if not CHESS_COACH_INGEST_URL:
        return False
    try:
        req = urllib.request.Request(
            CHESS_COACH_INGEST_URL,
            data=json.dumps(results).encode("utf-8"),
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=INGEST_TIMEOUT_SEC):
            return True
    except Exception:
        return False


class ChessAnalysisService:
    """Thread-safe owner of the SQLite analysis registry."""

    def __init__(self, db_path: str = DB_PATH, engine_path: str = STOCKFISH_PATH):
        self.db_path = db_path
        self.engine_path = engine_path
        os.makedirs(os.path.dirname(db_path), exist_ok=True)
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._conn.execute(
            "CREATE TABLE IF NOT EXISTS analyses ("
            " game_id TEXT PRIMARY KEY,"
            " json TEXT NOT NULL,"
            " depth INTEGER,"
            " analyzed_at REAL NOT NULL)"
        )
        self._conn.commit()
        self._db_lock = threading.Lock()
        self._game_locks: dict[str, threading.Lock] = {}
        self._game_locks_guard = threading.Lock()
        self._last_live_ms = 0.0
        self._live_in_flight = 0
        self._recent_failures: list[dict] = []
        self._state_lock = threading.Lock()

    # -- registry -----------------------------------------------------------

    def _load_cached(self, game_id: str) -> dict | None:
        with self._db_lock:
            row = self._conn.execute(
                "SELECT json FROM analyses WHERE game_id = ?", (game_id,)
            ).fetchone()
        if not row:
            return None
        try:
            return json.loads(row[0])
        except Exception:
            return None  # corrupt row → re-analyse below

    def _store(self, results: dict, game_id: str, depth: int):
        with self._db_lock:
            self._conn.execute(
                "INSERT OR IGNORE INTO analyses (game_id, json, depth, analyzed_at)"
                " VALUES (?, ?, ?, ?)",
                (game_id, json.dumps(results), depth, time.time()),
            )
            self._conn.commit()

    def _game_lock(self, game_id: str) -> threading.Lock:
        with self._game_locks_guard:
            lock = self._game_locks.get(game_id)
            if lock is None:
                lock = threading.Lock()
                self._game_locks[game_id] = lock
            return lock

    # -- public API ----------------------------------------------------------

    def analyze(self, pgn_text: str, game_id: str = "", username: str = "",
                depth: int | None = None) -> dict:
        """Analyse (or return cached) one game → summary contract dict.

        Raises ValueError on bad input (caller maps to HTTP 400).
        """
        depth = int(depth or LIVE_DEPTH_DEFAULT)
        depth = max(8, min(depth, LIVE_DEPTH_MAX))
        if not pgn_text or not pgn_text.strip():
            raise ValueError("pgn is required")

        game = chess.pgn.read_game(io.StringIO(pgn_text))
        if game is None:
            raise ValueError("unreadable PGN")

        # Canonical id from the PGN drives dedup — a caller id that disagrees
        # must never split one game into two registry entries.
        canonical = game_id_of(game)
        if canonical:
            game_id = canonical
        if not game_id:
            raise ValueError("no game_id: PGN has no Link header and none was supplied")

        with self._state_lock:
            self._last_live_ms = time.time() * 1000.0
            self._live_in_flight += 1
        try:
            lock = self._game_lock(game_id)
            with lock:
                # 1) Dedup: an existing analysis is the answer.
                cached = self._load_cached(game_id)
                if cached is not None:
                    return summary_of(cached, username, cached=True, engine_ms=0)

                # 2) Live analysis at requested depth.
                started = time.time()
                results = GameAnalyzer(
                    engine_path=self.engine_path, game=game, depth=depth
                ).analyze()
                results["_depth"] = depth
                elapsed_ms = int((time.time() - started) * 1000)
                self._store(results, game_id, depth)

            # 3) Desktop notification + best-effort handoff to chess-coach.
            threading.Thread(
                target=lambda: (
                    notify_analysis_done(results, elapsed_ms),
                    push_to_chess_coach(results),
                ),
                daemon=True,
            ).start()

            return summary_of(results, username, cached=False, engine_ms=elapsed_ms)
        finally:
            with self._state_lock:
                self._live_in_flight -= 1

    def status(self) -> dict:
        with self._db_lock:
            row = self._conn.execute(
                "SELECT COUNT(*), MAX(analyzed_at) FROM analyses"
            ).fetchone()
            analyzed, last_analyzed_at = row[0], row[1]
        with self._state_lock:
            busy = self._live_in_flight > 0
            last_live_age = (
                (time.time() * 1000.0 - self._last_live_ms) / 1000.0
                if self._last_live_ms else None
            )
            failures = list(self._recent_failures)
        engine_ok = os.path.isfile(self.engine_path) and os.access(
            self.engine_path, os.X_OK
        )
        return {
            "analyzed": analyzed,
            "backlog_pending": 0,  # on-demand only; the phone drives what it needs
            "busy_live": busy,
            "last_live_age_sec": last_live_age,
            "stockfish": self.engine_path,
            "stockfish_ok": engine_ok,
            "db_path": DB_PATH,
            "last_analyzed_at": last_analyzed_at,
            "recent_failures": failures,
        }

    def note_failure(self, reason: str):
        """Records an analysis failure for the dashboard diagnostics ring."""
        with self._state_lock:
            self._recent_failures.append(
                {"at": time.strftime("%Y-%m-%d %H:%M:%S"), "reason": reason[:200]}
            )
            del self._recent_failures[:-20]  # keep the last 20

    def recent(self, limit: int = 10) -> list:
        """Newest-first analysis history for the dashboard (compact rows)."""
        with self._db_lock:
            rows = self._conn.execute(
                "SELECT game_id, json, depth, analyzed_at FROM analyses"
                " ORDER BY analyzed_at DESC LIMIT ?",
                (max(1, min(int(limit), 50)),),
            ).fetchall()
        out = []
        for game_id, js, depth, analyzed_at in rows:
            try:
                data = json.loads(js)
            except Exception:
                continue  # corrupt row — skip, never crash the dashboard
            meta = data.get("metadata", {})
            stats = data.get("stats", {})
            out.append({
                "game_id": game_id,
                "white": meta.get("White", "?"),
                "black": meta.get("Black", "?"),
                "result": meta.get("Result", ""),
                "date": meta.get("Date", ""),
                "depth": depth,
                "analyzed_at": analyzed_at,
                "white_blunders": stats.get("white_blunders", 0),
                "black_blunders": stats.get("black_blunders", 0),
                "white_acpl": round(stats.get("white_acpl", 0.0), 1),
                "black_acpl": round(stats.get("black_acpl", 0.0), 1),
            })
        return out


# Module-level singleton for the FastAPI endpoints (def endpoints run in the
# threadpool, so blocking engine calls are fine).
_service: ChessAnalysisService | None = None
_service_guard = threading.Lock()


def get_service() -> ChessAnalysisService:
    global _service
    with _service_guard:
        if _service is None:
            _service = ChessAnalysisService()
        return _service
