package com.example.tail.widget

import java.util.concurrent.atomic.AtomicInteger

/**
 * Process-wide flag: is ANY Tail chess overlay window currently on screen?
 * (Readiness wizard/status dialogs, the guard wall, the YELLOW warning, the
 * "readiness test required" notice, the full-screen start-a-session menu.)
 *
 * Why this exists (user report 2026-10-22/23): our full-screen overlays are
 * FOCUSABLE windows shown over the chess app. Taking/grabing window focus
 * makes the system (Samsung IME "honeyboard", SystemUI, launcher glimpse)
 * fire TYPE_WINDOW_STATE_CHANGED events with NON-chess packages — the Chess
 * Guard interpreted those as "user left chess", ended the stint, and
 * DISMISSED the very overlay that caused the event. The next chess window
 * event re-opened it → the overlay flashed up and away in a loop.
 *
 * While [isActive] is true, the guard's detectors ([ChessGuardService] and
 * the [WidgetTriggerService] UsageStats backstop) must treat the chess stint
 * as CONTINUING — no stint-end, no overlay teardown — until our own overlay
 * is gone.
 */
object ChessOverlayPresence {
    private val count = AtomicInteger(0)

    /** Called right after an overlay window was successfully added. */
    fun enter() {
        count.incrementAndGet()
    }

    /** Called when an overlay window was removed. Clamped at zero. */
    fun exit() {
        if (count.decrementAndGet() < 0) count.set(0)
    }

    fun isActive(): Boolean = count.get() > 0
}
