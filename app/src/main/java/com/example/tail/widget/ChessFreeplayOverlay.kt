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
        if (ChessEnforcementPolicy.freeplayBlockedByPostGameYellow(context)) {
            renderBlocked()
        } else {
            renderConfirm()
        }
    }

    fun dismiss() {
        dialog.dismiss()
    }

    fun isShowing(): Boolean = dialog.isShowing()

    private fun renderConfirm() {
        val balance = ChessFreeplayStore.available(context)
        // Provisional spends still awaiting settlement (net rating not yet
        // decided). Surfacing them avoids the 2026-09-24 confusion where a
        // refunded session still LOOKED charged until the next menu render.
        val pending = ChessFreeplayStore.pendingSettlementCount(context)
        dialog.setContent("🎟 Weekly Freeplay", "Rated play without a test") {
            bigScore("$balance", "#FFD54F")
            stateLabel("FREEPLAY CREDIT${if (balance == 1) "" else "S"} AVAILABLE", "#FFD54F")
            if (pending > 0) {
                spacer(6)
                stateLabel(
                    "$pending PROVISIONAL — SETTLING AFTER PLAY",
                    "#EAB308"
                )
            }
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
                "• NET rating gain ≥ +1 in the session REFUNDS the credit " +
                    "(any variant — standard, 960, bullet/blitz/rapid)",
                0xFF22C55E.toInt()
            )
            bullet(
                "• 1 ticket per week — granted only while you hold " +
                    "fewer than 3 (tickets from Puzzle Rush records don't count against this)",
                0xFF999999.toInt()
            )
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

    /**
     * Defensive: the menu can't see a post-game PIVOT downgrade that
     * landed after it rendered — spending a credit here would override
     * exactly the audit signal that just demoted the session to casual
     * play (user rule 2026-09-24). No credit is consumed.
     */
    private fun renderBlocked() {
        dialog.setContent("🎟 Weekly Freeplay", "Blocked by last audit") {
            stateLabel("POST-GAME AUDIT SAYS YELLOW", "#EAB308")
            spacer(10)
            body(
                "Your last game's post-game audit moved this session to " +
                    "YELLOW (casual play only), so freeplay is unavailable " +
                    "right now. Take the next readiness test once it opens " +
                    "— pass it and rated play returns the regular way. " +
                    "The credit stays banked.",
                size = 14
            )
            primaryButton("Got it") { dismiss() }
        }
    }

    /** Defensive: the balance emptied between menu render and tap. */
    private fun renderEmpty() {
        dialog.setContent("🎟 Weekly Freeplay", "No tickets left") {
            body(
                "Your freeplay tickets are used up. A new weekly ticket " +
                    "arrives each week — but only while you hold fewer " +
                    "than 3. New all-time Puzzle Rush records always earn " +
                    "an uncapped bonus ticket, and passing a readiness " +
                    "test earns GREEN the regular way.",
                size = 14
            )
            primaryButton("Got it") { dismiss() }
        }
    }
}
