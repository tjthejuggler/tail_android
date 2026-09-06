package com.example.tail.widget

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Guard — hard enforcement policy for the Chess Readiness system
 * ════════════════════════════════════════════════════════════════════════
 *
 * [ChessReadinessEngine] decides what chess activity is *advisable*; this
 * policy decides whether the chess APP may be used AT ALL, powering the
 * [ChessGuardService] accessibility gate (which kicks the user out of the
 * app when blocked) and the [ChessGuardLockActivity] full-screen wall.
 *
 * The user's chosen rules (2026-08-23 design session, revised twice same day):
 *  - GREEN session active (≤ [ChessReadinessEngine.SESSION_VALIDITY_MS]
 *    since a GREEN test) → app ALLOWED (rated play authorized) — unless
 *    a Phase 2 audit filed AFTER that test already pulled the brake:
 *    PIVOT_TO_DRILLS downgrades the session to casual-only (yellow
 *    entry warning), TERMINATE_SESSION blocks until a new test opens.
 *  - YELLOW session still inside its validity → app ALLOWED for CASUAL
 *    play only (unrated games, bots, puzzles). Rated play stays
 *    prohibited: the guard shows a full-screen warning on entry, and a
 *    rated game detected by the reconciler triggers the 24-hour
 *    [PENALTY_DURATION_MS] lockout.
 *  - RED / expired session while a new test is still BLOCKED (rest /
 *    cool-down / daily cap) → app BLOCKED entirely — this covers "played
 *    while locked out of taking the test".
 *  - TRUST WINDOW: once the engine's re-test gate OPENS, the app is
 *    ALLOWED even without a GREEN pass — the readiness test itself happens
 *    inside the chess app, so the user must be able to get in. If the
 *    window is abused to play instead, the after-the-fact penalty (see
 *    [ChessGuardPenalty]) locks everything — test entry included — for
 *    [PENALTY_DURATION_MS].
 *  - An IN-PROGRESS readiness test whose current step happens inside the
 *    chess app (rated puzzles / Puzzle Rush) → app ALLOWED, bounded by the
 *    session's 10-minute step timeout. This keeps the gate from
 *    deadlocking when a test runs during an active rest period.
 *  - A VIOLATION PENALTY overrides everything, even a fresh GREEN session.
 *
 * The policy is PURE (no Android imports) so it is unit-testable; all
 * inputs come from [ChessReadinessStore] (synchronous SharedPreferences —
 * the accessibility service must not touch DataStore on its callback path).
 */
object ChessEnforcementPolicy {

    /**
     * How long an unauthorized-game penalty locks the app (user's rule,
     * 2026-08-23: 24 hours — a full day lost, replacing the earlier
     * 2-hour deterrent that proved too easy to sit out).
     */
    const val PENALTY_DURATION_MS = 24L * 60 * 60 * 1000

    /** A persisted violation penalty (deduped by chess.com game id). */
    data class Penalty(
        /** Epoch millis the penalty was applied. */
        val timestamp: Long,
        /** chess.com game id that triggered it ("" for manual penalties). */
        val gameId: String,
        /** Epoch millis after which the penalty no longer blocks. */
        val expiresAt: Long
    )

    /** Why the app is (or is not) usable right now. */
    enum class Reason {
        /** Enforcement toggle off — nothing is blocked. */
        FEATURE_OFF,
        /** Active GREEN session — rated play authorized. */
        GREEN_SESSION,
        /** In-progress readiness test at a chess-app step. */
        TEST_IN_PROGRESS,
        /** Active violation penalty. */
        PENALTY,
        /**
         * Latest test YELLOW, still inside its validity window — casual
         * play (unrated / puzzles) allowed, rated play prohibited.
         */
        YELLOW_SESSION,
        /**
         * Phase 2 audit TERMINATED the session — all play (casual
         * included) stops until a new readiness test is possible.
         */
        SESSION_TERMINATED,
        /** Latest test failed (RED) — mandatory recovery rest. */
        REST_PERIOD,
        /** Re-test cool-down after a passed test. */
        COOLDOWN,
        /** Daily test cap reached. */
        DAILY_CAP,
        /**
         * Trust window: no active authorization, but the engine allows a
         * new test right now — the app opens so the test can be taken.
         */
        TEST_AVAILABLE
    }

    /** The enforcement verdict for "may the chess app be used right now". */
    sealed class Decision {
        /** App usable — see [reason]. */
        data class Allow(val reason: Reason) : Decision()

        /**
         * App blocked. [retryAt] is the epoch-ms moment the underlying
         * cause lifts (0 = no single known moment, e.g. "take a test").
         */
        data class Block(
            val reason: Reason,
            val retryAt: Long,
            val message: String
        ) : Decision()
    }

    /** Steps of the readiness wizard that are solved inside the chess app. */
    private val CHESS_STEPS = setOf(
        SessionStep.PUZZLE_GO, SessionStep.PUZZLE_RESULT,
        SessionStep.RUSH_GO, SessionStep.RUSH_RESULT
    )

    /**
     * The gate decision. Pure function of the persisted readiness state.
     *
     * @param enforcementEnabledAt epoch ms when enforcement was switched on
     *        (0 = enforcement off → everything allowed).
     * @param history recorded Phase 1 tests (drives sessions + rate limits)
     * @param session in-progress readiness wizard session, if any
     * @param penalties persisted violation penalties
     * @param now epoch ms "now"
     * @param lastAudit most recent Phase 2 audit, if any — a PIVOT or
     *        TERMINATE verdict filed after the last Phase 1 test limits
     *        the session that test authorized (mirrors the reconciler's
     *        "no Yellow/Red audit since the green test" rule).
     */
    fun evaluate(
        enforcementEnabledAt: Long,
        history: List<ChessReadinessEngine.ReadinessTest>,
        session: ReadinessSession?,
        penalties: List<Penalty>,
        now: Long,
        lastAudit: ChessPhase2Store.Phase2Audit? = null
    ): Decision {
        if (enforcementEnabledAt <= 0L) {
            return Decision.Allow(Reason.FEATURE_OFF)
        }

        // 1. Violation penalties trump everything — playing an unauthorized
        //    rated game must never be rewarded with "but I just passed".
        penalties
            .filter { now < it.expiresAt }
            .maxByOrNull { it.expiresAt }
            ?.let {
                return Decision.Block(
                    Reason.PENALTY,
                    retryAt = it.expiresAt,
                    message = "Violation penalty — a game was played without authorization. " +
                        "Everything (testing included) stays blocked until the penalty expires."
                )
            }

        // 2. Active GREEN session → the only state where chess is freely
        //    usable (rated play authorized by the engine) — UNLESS a
        //    Phase 2 audit filed after the test already pulled the brake:
        //    PIVOT_TO_DRILLS (rated play prohibited) downgrades the
        //    session to casual-only with the yellow entry warning, and
        //    TERMINATE_SESSION stops all play until a new test is
        //    possible. Without this, a bad audited game right after a
        //    GREEN pass kept the app fully unlocked for the whole
        //    60-minute window.
        val last = history.maxByOrNull { it.timestamp }
        val auditAfterTest = lastAudit
            ?.takeIf { last != null && it.timestamp > last.timestamp }
        if (last != null &&
            last.state == ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name &&
            now - last.timestamp < ChessReadinessEngine.SESSION_VALIDITY_MS
        ) {
            return when {
                auditAfterTest?.outputState ==
                    ChessPhase2Engine.OutputState.TERMINATE_SESSION.name ->
                    Decision.Block(
                        Reason.SESSION_TERMINATED,
                        retryAt = last.timestamp + ChessReadinessEngine.COOLDOWN_MS,
                        message = "Phase 2 audit TERMINATED the session — stop all " +
                            "play and study. The app re-opens for your next readiness " +
                            "test when the cool-down ends."
                    )
                auditAfterTest?.outputState ==
                    ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS.name ->
                    Decision.Allow(Reason.YELLOW_SESSION)
                else -> Decision.Allow(Reason.GREEN_SESSION)
            }
        }

        // 3. In-progress readiness test at a chess-app step → allow, bounded
        //    by the session's own step timeout (10 min of inactivity kills
        //    it). This is the anti-deadlock path: the test needs the app.
        if (session != null &&
            session.step in CHESS_STEPS &&
            now - session.updatedAt <= ChessReadinessStore.STEP_TIMEOUT_MS
        ) {
            return Decision.Allow(Reason.TEST_IN_PROGRESS)
        }

        // 4. YELLOW still inside its validity → CASUAL PLAY ALLOWED. The
        //    engine permits unrated games and puzzles in YELLOW; only
        //    rated play is prohibited. The guard shows a full-screen
        //    warning on app entry ([ChessGuardWallOverlay.showWarning]),
        //    and a rated game detected after the fact costs a full 24 h
        //    ([ChessGuardPenalty]).
        if (last != null &&
            last.state == ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name &&
            now - last.timestamp < ChessReadinessEngine.SESSION_VALIDITY_MS
        ) {
            // A TERMINATE verdict after the test stops even casual play.
            return if (auditAfterTest?.outputState ==
                ChessPhase2Engine.OutputState.TERMINATE_SESSION.name
            ) {
                Decision.Block(
                    Reason.SESSION_TERMINATED,
                    retryAt = last.timestamp + ChessReadinessEngine.COOLDOWN_MS,
                    message = "Phase 2 audit TERMINATED the session — stop all " +
                        "play and study. The app re-opens for your next readiness " +
                        "test when the cool-down ends."
                )
            } else {
                Decision.Allow(Reason.YELLOW_SESSION)
            }
        }

        // 5. Otherwise mirror the engine's re-test gate: while a new test is
        //    BLOCKED (rest / cool-down / daily cap) the app is blocked too —
        //    this covers "played while locked out of taking the test".
        return when (val gate = ChessReadinessEngine.checkGate(history, now)) {
            is ChessReadinessEngine.GateStatus.Blocked -> {
                val (reason, message) = when (gate.error) {
                    is ChessReadinessEngine.GateError.RestPeriodActive ->
                        Reason.REST_PERIOD to
                            "Last readiness test FAILED — mandatory biological recovery " +
                                "rest. The app re-opens for your next test when it ends."
                    is ChessReadinessEngine.GateError.CooldownActive ->
                        Reason.COOLDOWN to
                            "Re-test cool-down active. The app re-opens for your next " +
                                "test when the cool-down ends."
                    is ChessReadinessEngine.GateError.MaxDailyTests ->
                        Reason.DAILY_CAP to
                            "Daily readiness-test cap reached. The app re-opens when " +
                                "tests become possible again."
                }
                Decision.Block(reason, gate.error.retryAt, message)
            }
            is ChessReadinessEngine.GateStatus.Allowed ->
                // TRUST WINDOW (user's rule): a test is possible right now,
                // and the test itself lives inside the chess app — so the
                // app must open. If the window is abused to play instead,
                // the reconciler's penalty (any game ending outside a green
                // window) locks the app — test entry included — for
                // [PENALTY_DURATION_MS].
                Decision.Allow(Reason.TEST_AVAILABLE)
        }
    }

    /**
     * True while a YELLOW or GREEN trust window is live — the latest test
     * is still inside its [ChessReadinessEngine.SESSION_VALIDITY_MS]
     * window. Enforcement OFF counts as "unrestricted" (true) so the gate
     * never hides anything when the whole feature is disabled.
     *
     * UI gate for the floating bubble's habit picker: without a live
     * window (no test / failed test / stale test) the non-readiness
     * habits are not offered — only the readiness test itself.
     */
    fun hasLiveTrustWindow(context: android.content.Context): Boolean {
        return try {
            if (ChessReadinessStore.enforcementEnabledAt(context) <= 0L) return true
            val last = ChessReadinessStore.loadHistory(context)
                .maxByOrNull { it.timestamp } ?: return false
            val now = System.currentTimeMillis()
            now - last.timestamp < ChessReadinessEngine.SESSION_VALIDITY_MS &&
                (last.state == ChessReadinessEngine.ReadinessState.GREEN_LIGHT.name ||
                    last.state == ChessReadinessEngine.ReadinessState.YELLOW_LIGHT.name)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Convenience wrapper for Android callers: loads every input from
     * [ChessReadinessStore] synchronously (SharedPreferences — safe on the
     * accessibility callback path) and evaluates [evaluate] at "now".
     */
    fun evaluateNow(context: android.content.Context): Decision = evaluate(
        enforcementEnabledAt = ChessReadinessStore.enforcementEnabledAt(context),
        history = ChessReadinessStore.loadHistory(context),
        session = ChessReadinessStore.loadSession(context),
        penalties = ChessReadinessStore.loadPenalties(context),
        now = System.currentTimeMillis(),
        lastAudit = ChessPhase2Store.loadAudits(context).maxByOrNull { it.timestamp }
    )
}

/**
 * Broadcasts Chess Guard state changes so EXTERNAL automation (Tasker /
 * MacroDroid "Intent Received") can optionally apply a harder lock, e.g.
 * run `pm suspend <chess pkg>` / `pm unsuspend` over its ADB-WiFi grant.
 * Tail itself never needs a receiver for this — it is purely a hook for
 * user-configured escalation.
 */
object ChessGuardNotifier {

    /** Implicit broadcast action fired on every enforcement-relevant write. */
    const val ACTION_STATE_CHANGED = "com.example.tail.CHESS_GUARD_STATE"

    /**
     * Re-evaluates the policy and broadcasts the verdict. Called after any
     * write that can flip the decision (test appended, session step saved,
     * penalty applied, toggle changed) — cheap (one prefs read + one
     * broadcast) and idempotent.
     */
    fun notifyStateChange(context: android.content.Context) {
        try {
            val decision = ChessEnforcementPolicy.evaluateNow(context)
            val blocked = decision is ChessEnforcementPolicy.Decision.Block
            val retryAt = (decision as? ChessEnforcementPolicy.Decision.Block)?.retryAt ?: 0L
            val reason = when (decision) {
                is ChessEnforcementPolicy.Decision.Allow -> decision.reason.name
                is ChessEnforcementPolicy.Decision.Block -> decision.reason.name
            }
            val intent = android.content.Intent(ACTION_STATE_CHANGED).apply {
                putExtra("blocked", blocked)
                putExtra("reason", reason)
                putExtra("retryAt", retryAt)
                putExtra("package", ChessReadinessStore.chessPackage(context))
            }
            context.sendBroadcast(intent)
        } catch (_: Exception) {
            // Never let a notification helper break a persistence write.
        }
    }
}
