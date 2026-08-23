package com.example.tail.widget

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Guard — accessibility gate that physically keeps the user out of
 *  the chess app while [ChessEnforcementPolicy] says the app is blocked
 * ════════════════════════════════════════════════════════════════════════
 *
 * The advisory bubble/overlays were ignorable; this is the "actually lock
 * the app" tier. Whenever the configured chess app brings any window to
 * the front while blocked, the guard:
 *
 *   1. performs GLOBAL_ACTION_HOME — the chess app loses focus at once;
 *   2. shows the full-screen lock wall ([ChessGuardWallOverlay] overlay,
 *      [ChessGuardLockActivity] fallback) explaining WHY the app is
 *      blocked and counting down to the next possible test;
 *   3. counts the blocked attempt (shown in Settings).
 *
 * The reaction is debounced ([REACT_DEBOUNCE_MS]) so a burst of window
 * events from one app launch produces one kick, not a storm.
 *
 * Why accessibility and not just the existing UsageStats polling? Events
 * arrive instantly (no 2 s poll lag), the service is system-bound (survives
 * reboots and process death without any watchdog), and GLOBAL_ACTION_HOME
 * is only available to accessibility services. This is the same mechanism
 * mainstream app blockers (AppBlock, one sec) use — no root, no ADB.
 *
 * The service reads ALL state synchronously from [ChessReadinessStore]
 * (SharedPreferences): the accessibility callback path must stay free of
 * DataStore coroutines. The chess package comes from the store's mirror of
 * the DataStore `chessReadinessApp` setting (kept fresh by the settings
 * view-model and the trigger service).
 *
 * Bypass caveat (accepted by design): the user can disable the service in
 * system settings. [WidgetTriggerService] detects that case (chess app in
 * foreground + blocked + guard disabled) and posts a warning notification,
 * and unauthorized games still trigger violation penalties after the fact.
 */
class ChessGuardService : AccessibilityService() {

    companion object {
        private const val TAG = "ChessGuardService"

        /** Minimum gap between two kick-out reactions (ms). */
        private const val REACT_DEBOUNCE_MS = 2000L

        /**
         * Minimum gap between two YELLOW entry warnings (ms). Longer than
         * the kick debounce so a real leave-and-return still re-warns,
         * while one launch's burst of window events shows it once.
         */
        private const val WARN_DEBOUNCE_MS = 30_000L

        /** True while the system has this service bound & enabled. */
        @Volatile
        var isRunning = false
            private set

        /**
         * Whether the user has enabled this service in system Settings →
         * Accessibility. Used by the Settings UI and by the trigger
         * service's bypass warning.
         */
        fun isEnabled(context: Context): Boolean {
            val expected = ComponentName(context, ChessGuardService::class.java)
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
                as android.view.accessibility.AccessibilityManager
            return am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            ).any { it.resolveInfo?.serviceInfo?.let { s ->
                ComponentName(s.packageName, s.name) == expected
            } == true }
        }
    }

    /** Last time a kick-out reaction fired (0 = never). */
    private var lastReactAt = 0L

    /** Last time the YELLOW entry warning was shown (0 = never). */
    private var lastWarnAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Chess Guard connected — watching for the chess app")
    }

    override fun onDestroy() {
        isRunning = false
        ChessGuardWallOverlay.dismiss()
        ChessGuardWallOverlay.dismissWarning()
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isRunning = false
        ChessGuardWallOverlay.dismiss()
        ChessGuardWallOverlay.dismissWarning()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return

        val pkg = event.packageName?.toString() ?: return
        val chessPkg = ChessReadinessStore.chessPackage(applicationContext)
        if (chessPkg.isBlank() || pkg != chessPkg) return
        if (ChessReadinessStore.enforcementEnabledAt(applicationContext) <= 0L) return

        val decision = try {
            ChessEnforcementPolicy.evaluateNow(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Policy evaluation failed — failing open (no block)", e)
            return
        }

        // YELLOW session: the app stays open (casual play allowed), but a
        // full-screen warning spells out what a rated game would cost —
        // the automatic 24-hour lockout. No kick, no block.
        if (decision is ChessEnforcementPolicy.Decision.Allow &&
            decision.reason == ChessEnforcementPolicy.Reason.YELLOW_SESSION
        ) {
            val now = System.currentTimeMillis()
            if (now - lastWarnAt < WARN_DEBOUNCE_MS) return
            lastWarnAt = now
            Log.d(TAG, "Chess app opened during YELLOW — showing casual-play warning")
            try {
                ChessGuardWallOverlay.showWarning(applicationContext)
            } catch (e: Exception) {
                Log.w(TAG, "Yellow warning overlay failed: ${e.message}")
            }
            return
        }

        if (decision !is ChessEnforcementPolicy.Decision.Block) return

        val now = System.currentTimeMillis()
        if (now - lastReactAt < REACT_DEBOUNCE_MS) return
        lastReactAt = now

        Log.d(TAG, "Chess app opened while blocked (${decision.reason}) — kicking out")
        ChessReadinessStore.noteGuardBlock(applicationContext)

        // 1. Yank focus off the chess app immediately.
        performGlobalAction(GLOBAL_ACTION_HOME)

        // 2. Show the wall — OVERLAY FIRST. Background-activity-launch
        //    (BAL) restrictions can silently refuse startActivity from a
        //    service context (observed on One UI: the chess app "opens
        //    and immediately closes" with no explanation), while a
        //    SYSTEM_ALERT_WINDOW overlay added via the WindowManager is
        //    not subject to BAL — and Tail already holds the overlay
        //    grant for the floating bubble. The lock activity remains
        //    the fallback when the overlay grant is missing.
        val overlayShown = try {
            ChessGuardWallOverlay.show(applicationContext, decision)
        } catch (e: Exception) {
            Log.w(TAG, "Wall overlay failed: ${e.message}")
            false
        }
        if (!overlayShown) {
            try {
                startActivity(
                    Intent(applicationContext, ChessGuardLockActivity::class.java).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                                Intent.FLAG_ACTIVITY_NO_ANIMATION
                        )
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Lock screen launch failed (home action already fired): ${e.message}")
            }
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt — the service never speaks or vibrates.
    }
}
