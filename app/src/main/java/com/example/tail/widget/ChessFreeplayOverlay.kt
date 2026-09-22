package com.example.tail.widget

/**
 * ♟ Freeplay — confirm & celebrate overlay, rendered by
 * [FloatingBubbleService] over the chess app (same [ChessOverlayDialog]
 * mechanism as the other chess overlays, so the chess app stays the
 * focused app underneath).
 *
 * Two phases:
 *  1. CONFIRM — shows the current balance and what a freeplay grants
 *     (a GREEN session identical to a passed readiness test). "Use
 *     freeplay" consumes the credit via [ChessReadinessStore.grantFreeplay].
 *  2. GRANTED — celebration mirroring the special-green celebration: what
 *     was unlocked, how long it lasts, and the reminder that the post-game
 *     audit now applies exactly as after a passed test.
 *
 * Rendered only when a credit IS available (the bubble menu gates on
 * [ChessFreeplayStore.available]); the confirm phase defensively
 * re-checks so a double-tap can never spend two credits.
 */
class ChessFreeplayOverlay(service: android.content.Context) {

    private val context = service.applicationContext
    private val dialog = ChessOverlayDialog(context)

    fun show() {
        dialog.show()
        renderConfirm()
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing()

    private fun renderConfirm() {
        val balance = ChessFreeplayStore.available(context)
        dialog.setContent("🎟 Weekly Freeplay", "Rated play without a test") {
            bigScore("$balance", "#FFD54F")
            stateLabel("FREEPLAY CREDIT${if (balance == 1) "" else "S"} AVAILABLE", "#FFD54F")
            spacer(10)
            body(
                "A freeplay unlocks rated play RIGHT NOW, exactly as if you " +
                    "had passed a pre-game readiness test — the same GREEN " +
                    "session, the same post-game audit, the same rules.",
                size = 14
            )
            spacer(8)
            bullet("• 60-minute GREEN session starts now", 0xFF22C55E.toInt())
            bullet("• Rated games authorized (rolling window)", 0xFF22C55E.toInt())
            bullet("• Post-game audit applies — play well", 0xFF999999.toInt())
            bullet(
                "• NET rating gain ≥ +1 in the session REFUNDS the credit",
                0xFF22C55E.toInt()
            )
            bullet("• 1 credit per week, max 3 banked", 0xFF999999.toInt())
            spacer(4)
            primaryButton("Use 1 freeplay — go GREEN") {
                val granted = ChessReadinessStore.grantFreeplay(context)
                if (granted != null) renderGranted() else renderEmpty()
            }
            primaryButton("Keep it") { dismiss() }
        }
    }

    private fun renderGranted() {
        val left = ChessFreeplayStore.available(context)
        dialog.setContent("🎟 Freeplay Activated", null) {
            stateLabel("RATED PLAY AUTHORIZED", "#22C55E")
            spacer(10)
            body(
                "You are GREEN right now. This session is recorded as a " +
                    "FREEPLAY in your stats, so you can compare how you play " +
                    "after a freeplay vs after a passed readiness test. The " +
                    "credit is provisionally spent — finish the session at " +
                    "NET +1 rating or better and it is refunded.",
                size = 14
            )
            spacer(8)
            bullet("• 60-minute GREEN session (rolling window)", 0xFF22C55E.toInt())
            bullet("• Next readiness test unlocks when the session ends", 0xFF999999.toInt())
            bullet("• Net +1 or better → credit refunded", 0xFF22C55E.toInt())
            bullet("• Credits left: $left", 0xFF999999.toInt())
            primaryButton("Play — enjoy!") { dismiss() }
        }
    }

    /** Defensive: the balance emptied between menu render and tap. */
    private fun renderEmpty() {
        dialog.setContent("🎟 Weekly Freeplay", "No credits left") {
            body(
                "Your freeplay credits are used up. One new credit arrives " +
                    "each week (max 3 banked) — pass a readiness test to " +
                    "earn GREEN the regular way.",
                size = 14
            )
            primaryButton("Got it") { dismiss() }
        }
    }
}
