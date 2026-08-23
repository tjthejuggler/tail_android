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
 * Reactions are coordinated by [ChessGuardReactions] (shared with the
 * [WidgetTriggerService] UsageStats poll, which backstops this event
 * path — OEM launchers do not always deliver TYPE_WINDOW_STATE_CHANGED
 * for an app brought back to the front via the recents switcher). The
 * coordinator reacts once per foreground "stint": a burst of window
 * events from one launch produces one reaction, and leaving the chess
 * app re-arms the YELLOW entry warning.
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
        if (chessPkg.isBlank()) return

        if (pkg == chessPkg) {
            // A chess window came to the front. One launch fires a burst
            // of these (splash → main → in-app dialogs); the coordinator
            // reacts once per foreground stint and re-arms only after a
            // real leave. The HOME kick is only possible from here.
            ChessGuardReactions.noteChessForeground(applicationContext, true) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } else {
            // Any other window on top (launcher, SystemUI, another app)
            // ends the chess stint: re-arms the YELLOW entry warning and
            // takes a lingering warning overlay down.
            ChessGuardReactions.noteChessForeground(applicationContext, false)
        }
    }

    override fun onInterrupt() {
        // Nothing to interrupt — the service never speaks or vibrates.
    }
}
