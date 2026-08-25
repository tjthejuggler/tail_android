#!/usr/bin/env python3
"""Unit tests for the win%-based move classification in chess_analysis.py.

Run:  python3 -m unittest test_chess_analysis -v   (from tail_bridge/)

Regression origin (2026-08-25, game 173512925270): fixed 50/200/500 cp
thresholds + mate scores folded onto ±10000 cp classified four mate-
conversion moves (M2→M7, M3→+9, M3→M11, M11→+6) as "blunders", which
yellow-flagged a clean won game. These tests pin the saturating win% curve
so that can never happen again.
"""

import unittest

import chess_analysis as ca


class CpToWinPercentTest(unittest.TestCase):
    def test_equal_position_is_50(self):
        self.assertAlmostEqual(ca.cp_to_win_percent(0), 50.0)

    def test_mate_scores_saturate_near_100(self):
        # mate-in-2 = 9800, mate-in-11 = 8900 on the folded scale
        self.assertGreater(ca.cp_to_win_percent(9800), 99.99)
        self.assertGreater(ca.cp_to_win_percent(8900), 99.99)

    def test_won_positions_cluster_together(self):
        # The core fix: +9.09 (909 cp) is nearly as won as mate-in-3 (9700).
        m3 = ca.cp_to_win_percent(9700)
        plus9 = ca.cp_to_win_percent(909)
        self.assertLess(m3 - plus9, 5.0)  # < 5 win-points apart

    def test_minus_100cp_is_about_41(self):
        # The old unforced bar (mover cp > -100) expressed on the curve.
        self.assertAlmostEqual(ca.cp_to_win_percent(-100), 40.9, places=1)


class TimeControlTierTest(unittest.TestCase):
    def test_known_tiers(self):
        self.assertEqual(ca.time_control_tier("60"), "bullet")      # 1+0
        self.assertEqual(ca.time_control_tier("120+1"), "bullet")   # 2+1
        self.assertEqual(ca.time_control_tier("180+2"), "blitz")    # 3+2
        self.assertEqual(ca.time_control_tier("300"), "blitz")      # 5+0
        self.assertEqual(ca.time_control_tier("600"), "rapid")      # 10+0
        self.assertEqual(ca.time_control_tier("1800+20"), "rapid")  # 30+20

    def test_unknown_and_daily_default_to_rapid(self):
        self.assertEqual(ca.time_control_tier(""), "rapid")
        self.assertEqual(ca.time_control_tier(None), "rapid")
        self.assertEqual(ca.time_control_tier("1/86400"), "rapid")  # daily
        self.assertEqual(ca.time_control_tier("garbage"), "rapid")


class ClassifyMoveTest(unittest.TestCase):
    def cls(self, before_cp, after_cp, tier="rapid"):
        drop = max(0.0, ca.cp_to_win_percent(before_cp) - ca.cp_to_win_percent(after_cp))
        return ca.GameAnalyzer.classify_move(drop, tier, "X", "Y")

    def test_mate_to_mate_conversion_is_not_a_blunder(self):
        # Today's phantom blunders: still forced mate after the move.
        self.assertEqual(self.cls(9800, 9300), "Best/Good")  # M2 → M7
        self.assertEqual(self.cls(9700, 8900), "Best/Good")  # M3 → M11

    def test_mate_to_big_advantage_is_not_a_blunder(self):
        self.assertNotEqual(self.cls(9700, 909), "Blunder")   # M3 → +9
        self.assertNotEqual(self.cls(8900, 620), "Blunder")   # M11 → +6

    def test_real_blunder_from_equality(self):
        # 0 → -500 cp: 50% → 15.9% win — a 34-point collapse.
        self.assertEqual(self.cls(0, -500), "Blunder")

    def test_moderate_drops_classify_progressively(self):
        # 0 → -150 cp ≈ 13.5 pp drop → Inaccuracy (rapid)
        self.assertEqual(self.cls(0, -150), "Inaccuracy")
        # 0 → -300 cp ≈ 25.1 pp drop → Mistake (rapid)
        self.assertEqual(self.cls(0, -300), "Mistake")

    def test_bullet_forgives_more_than_rapid(self):
        # 0 → -300 cp ≈ 25.1 pp: Mistake in rapid, only Inaccuracy in bullet
        # (bullet bands are ×1.5 → blunder needs 45 pp).
        self.assertEqual(self.cls(0, -300, tier="bullet"), "Inaccuracy")
        # 0 → -400 cp ≈ 29.7 pp: still not a bullet blunder…
        self.assertNotEqual(self.cls(0, -400, tier="bullet"), "Blunder")
        # …nor is 0 → -500 cp ≈ 34.1 pp (bullet bar is 45 pp)…
        self.assertEqual(self.cls(0, -500, tier="bullet"), "Mistake")
        # …but 0 → -1000 cp ≈ 47.5 pp is a blunder even in bullet.
        self.assertEqual(self.cls(0, -1000, tier="bullet"), "Blunder")

    def test_forced_mate_played_is_best(self):
        drop = max(0.0, ca.cp_to_win_percent(9800) - ca.cp_to_win_percent(9800))
        self.assertEqual(
            ca.GameAnalyzer.classify_move(drop, "rapid", "Qh5#", "Qh5#"), "Best/Good"
        )


class BandConstantsTest(unittest.TestCase):
    def test_scramble_seconds_mirror_phone_calibration(self):
        # ChessPhase2Engine.TimeControl.scrambleSec: 10 / 20 / 45.
        self.assertEqual(ca.TC_SCRAMBLE_SEC, {"bullet": 10.0, "blitz": 20.0, "rapid": 45.0})

    def test_band_scale_ordering(self):
        self.assertGreater(ca.TC_BAND_SCALE["bullet"], ca.TC_BAND_SCALE["blitz"])
        self.assertGreater(ca.TC_BAND_SCALE["blitz"], ca.TC_BAND_SCALE["rapid"])

    def test_unforced_bar_matches_old_minus_100cp(self):
        self.assertAlmostEqual(ca.UNFORCED_MIN_WIN_PERCENT, 40.0)
        self.assertAlmostEqual(
            ca.cp_to_win_percent(-100), ca.UNFORCED_MIN_WIN_PERCENT, delta=1.0
        )


if __name__ == "__main__":
    unittest.main()
