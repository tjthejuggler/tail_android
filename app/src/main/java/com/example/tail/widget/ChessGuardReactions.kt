package com.example.tail.widget

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.tail.widget.ChessEnforcementPolicy.Decision
import com.example.tail.widget.ChessEnforcementPolicy.Reason

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Guard — shared reaction coordinator for foreground transitions
 * ════════════════════════════════════════════════════════════════════════
 *
 * TWO independent detectors observe the chess app coming to the front:
 *
 *  1. [ChessGuardService] — accessibility TYPE_WINDOW_STATE_CHANGED events.
 *     Instant, but NOT bulletproof: some OEM launchers (observed on
 *     Samsung One UI) deliver the event for a recents-switcher re-entry
 *     as the SystemUI package instead of the chess package, so the
 *     event-based path can silently miss a real foreground transition.
 *  2. [WidgetTriggerService] — a 2 s UsageStats poll that sees EVERY real
 *     foreground transition (the same mechanism that reliably shows the
 *     floating bubble over the chess app).
 *
 * Both feed this coordinator, which owns the per-"stint" reaction state:
 *
 *  - YELLOW entry → the full-screen casual-only warning
 *    ([ChessGuardWallOverlay.showWarning]) EXACTLY ONCE per foreground
 *    stint. Leaving the chess app re-arms it, so every open / bring-to-
 *    front warns again — regardless of how much time passed (the old
 *    30 s time-debounce both over- and under-fired).
 *  - BLOCKED entry (RED rest period, cool-down, daily cap, penalty) →
 *    kick the user out (HOME action, accessibility path only) + the
 *    full-screen lock wall. If a blocked user MANAGES to stay inside the
 *    chess app (missed leave event, guard disabled), the wall re-arms
 *    every [BLOCK_REARM_MS] until they leave — the app must stay
 *    unusable while blocked.
 *
 * All state is process-wide statics: every participant (accessibility
 * service, trigger service, readiness overlay) lives in Tail's main
 * process (no android:process overrides in the manifest).
 */
object ChessGuardReactions {

    private const val TAG = "ChessGuardReactions"

    /**
     * Re-arm window for the BLOCK reaction while the chess app stays in
     * the foreground despite the wall (missed leave events / disabled
     * accessibility gate). Short enough that the app never becomes
     * usable while blocked, long enough that one launch's burst of
     * window events produces one reaction.
     */
    private const val BLOCK_REARM_MS = 5000L

    /** True while the chess app is (believed to be) in the foreground. */
    @Volatile
    var chessInForeground = false
        private set

    /** True once the YELLOW warning fired for the CURRENT stint. */
    @Volatile
    private var yellowWarnedThisStint = false

    /** True once the "readiness test required" notice fired for the CURRENT stint. */
    @Volatile
    private var testRequiredWarnedThisStint = false

    /** Last time a BLOCK reaction fired (0 = never). */
    @Volatile
    private var lastBlockReactAt = 0L

    /**
     * Reports the chess app's foreground state from EITHER detector.
     * Duplicate reports are cheap no-ops — reactions fire only on real
     * false→true transitions (plus the periodic BLOCK re-arm).
     *
     * @param kick optional "yank focus off the chess app" runner. Only
     *        the accessibility service can supply it
     *        ([AccessibilityService.performGlobalAction]
     *        [GLOBAL_ACTION_HOME]); the UsageStats poll path passes
     *        null and relies on the wall alone.
     */
    fun noteChessForeground(context: Context, inForeground: Boolean, kick: (() -> Unit)? = null) {
        if (inForeground) {
            val wasForeground = chessInForeground
            chessInForeground = true
            reactToEntry(context, isNewEntry = !wasForeground, kick = kick)
        } else if (chessInForeground) {
            // The stint ended: re-arm the warnings for the next entry and
            // take any lingering overlays down (the user may have left via
            // gesture without tapping "Got it").
            chessInForeground = false
            yellowWarnedThisStint = false
            testRequiredWarnedThisStint = false
            try {
                ChessGuardWallOverlay.dismissWarning()
            } catch (_: Exception) {
                // Window already gone — nothing to clean up.
            }
            try {
                ChessGuardWallOverlay.dismissTestRequiredWarning()
            } catch (_: Exception) {
                // Window already gone — nothing to clean up.
            }
        }
    }

    /**
     * Marks the YELLOW warning as delivered for the CURRENT stint — used
     * by the readiness overlay's "Back to chess" button, which lands the
     * user in an ALREADY-foreground chess app (no new window-state event
     * will fire, so the detectors must not double-fire it later).
     */
    fun markYellowWarned() {
        yellowWarnedThisStint = true
    }

    private fun reactToEntry(context: Context, isNewEntry: Boolean, kick: (() -> Unit)?) {
        if (ChessReadinessStore.enforcementEnabledAt(context) <= 0L) return

        val decision = try {
            ChessEnforcementPolicy.evaluateNow(context)
        } catch (e: Exception) {
            Log.e(TAG, "Policy evaluation failed — failing open (no block)", e)
            return
        }

        when (decision) {
            is Decision.Allow -> {
                // YELLOW session: the app stays open (casual play
                // allowed), but every entry gets the full-screen
                // rated-games-cost-24h warning — once per stint.
                if (decision.reason == Reason.YELLOW_SESSION && !yellowWarnedThisStint) {
                    yellowWarnedThisStint = true
                    Log.d(TAG, "Chess app entered during YELLOW — showing casual-play warning")
                    try {
                        ChessGuardWallOverlay.showWarning(context)
                    } catch (e: Exception) {
                        Log.w(TAG, "Yellow warning overlay failed: ${e.message}")
                    }
                }
                // TRUST WINDOW: no valid authorization while a new test IS
                // possible — the app must open (the test lives inside it),
                // but every entry gets the full-screen "take the readiness
                // test before anything else" notice — once per stint.
                if (decision.reason == Reason.TEST_AVAILABLE && !testRequiredWarnedThisStint) {
                    testRequiredWarnedThisStint = true
                    Log.d(TAG, "Chess app entered without authorization — test-required notice")
                    try {
                        ChessGuardWallOverlay.showTestRequiredWarning(context)
                    } catch (e: Exception) {
                        Log.w(TAG, "Test-required overlay failed: ${e.message}")
                    }
                }
            }
            is Decision.Block -> {
                val now = System.currentTimeMillis()
                if (!isNewEntry && now - lastBlockReactAt < BLOCK_REARM_MS) return
                lastBlockReactAt = now
                Log.d(
                    TAG,
                    "Chess app ${if (isNewEntry) "entered" else "still foreground"} while " +
                        "blocked (${decision.reason}) — wall${if (kick != null) " + kick" else ""}"
                )
                if (isNewEntry) {
                    ChessReadinessStore.noteGuardBlock(context)
                }

                // 1. Yank focus off the chess app immediately
                //    (accessibility path only — GLOBAL_ACTION_HOME).
                kick?.invoke()

                // 2. Show the wall — OVERLAY FIRST. Background-activity-
                //    launch (BAL) restrictions can silently refuse
                //    startActivity from a service context, while a
                //    SYSTEM_ALERT_WINDOW overlay is not subject to BAL.
                //    The lock activity remains the fallback when the
                //    overlay grant is missing.
                val overlayShown = try {
                    ChessGuardWallOverlay.show(context, decision)
                } catch (e: Exception) {
                    Log.w(TAG, "Wall overlay failed: ${e.message}")
                    false
                }
                if (!overlayShown) {
                    try {
                        context.startActivity(
                            Intent(context, ChessGuardLockActivity::class.java).apply {
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                                )
                            }
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Lock screen launch failed (kick already fired): ${e.message}")
                    }
                }
            }
        }
    }
}
