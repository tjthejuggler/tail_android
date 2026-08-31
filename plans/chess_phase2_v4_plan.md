# Chess Phase 2 v4 — Data-Derived Post-Game Audit

**Date:** 2026-08-30
**Status:** Approved design → implementation

## Goal

Replace the v3 post-game audit's hand-picked constants with thresholds derived
from the user's 6,564 analyzed games (chess-coach `analysis/`). UX is unchanged:
share a game → CONTINUE / REST / PIVOT TO DRILLS / TERMINATE verdict.

## Data facts (from `scripts/explore_v4_readiness_data.py`)

- 6,564 games, 2025-11-04 → 2026-08-30, full ACPL/blunder/mistake data.
- Time controls: 600s (2143), 60s (2026), 180s (1225), 300s (839), 30s (293).
- Gap distribution valley at 45–60 min → **session gap = 45 min** (end→start).
- Session degradation is real: avg ACPL 312 (game 0-1) → ~388 (game 6+),
  blunders 1.69 → 2.20.

## Architecture

```
chess-coach (desktop, laptop-only compute)
  scripts/build_v4_profile.py
    reads analysis/*.json  →  data/chess_readiness_v4_profile.json
    (personal thresholds + logistic model, recency-weighted)
        │
tail_bridge (existing HTTP bridge)
  GET /chess/v4-profile  →  serves the profile JSON
        │
tail (phone)
  ChessPhase2V4Profile.kt   — parsed profile, v3-constant fallbacks
  ChessPhase2V4Engine.kt    — v3 rule skeleton, thresholds from profile
  ChessPhase2V4Store.kt     — persistence (mirrors V2/V3 stores)
  Settings toggle: chessPhase2Version = "v4"
```

## Profile contents (all recency-weighted, half-life ≈ 150 days)

1. **sessionGapMin** — 45 (derived from gap valley).
2. **Per time-control baselines** — ACPL mean/sd, blunder rate mean/sd,
   minutes-per-game (for session-minute accumulation when clocks absent).
3. **Fatigue thresholds** — per TC: the session-minute at which historical
   ACPL/blunder degradation crosses "yellow" (+0.5 sd) and "red" (+1.0 sd)
   levels. Replaces hardcoded 90/120. Readiness CCRS boost kept (0/15/30).
4. **Loss-streak thresholds** — from the historical distribution of
   ΔE-weighted streaks vs next-game outcome: yellow/red weights (replaces
   2.0/3.0) and continuous loss-weight curve vs expected score (replaces
   0.5/1.0/1.5 bands).
5. **Rest prescription** — minutes of inter-game rest after which historical
   next-game performance recovered to baseline (replaces fixed hysteresis
   timer); netted against the ~5 min pipeline latency.
6. **Circadian curve** — personal hourly ACPL multipliers (replaces generic
   20:00–04:00 window).
7. **Within-game degradation slope** — per-TC blunder-rate ratio (last third /
   first third); a slope above the personal 90th percentile feeds the tilt rule.
8. **Logistic model** — P(poor next game) from ~12 features (session minutes,
   weighted streak, rest gap, hour, prior ACPL/blunders, ΔE, slope). Exported
   as coefficients + scaler; phone computes a weighted sum. Thresholds on P
   map to yellow/red. Omitted from gating in v4.0 if validation AUC < 0.6 —
   rules alone still run.

## Phone engine rules (v4)

Same skeleton as v3 (fatigue, weighted streak, tilt, ACWR, strain,
hysteresis) but every constant read from the profile with v3 values as
fallback; plus:
- **REST verdict**: yellow + profile rest prescription → "REST X MIN".
- **Slope input**: late/early blunder ratio joins the tilt vector.
- Verdict enum extended: CONTINUE_RATED / REST_THEN_REASSESS /
  PIVOT_TO_DRILLS / TERMINATE_SESSION (REST maps onto PIVOT for enforcement).

## Verification

- Desktop: `build_v4_profile.py` prints derived thresholds + validation stats.
- Phone: `ChessPhase2V4EngineTest.kt` (pure-engine cases incl. fallback path).
- `./gradlew test` + `installDebug`.
