package com.example.tail.widget

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Chess Stop Advisor — the gentle layer before any hard stop
 * ════════════════════════════════════════════════════════════════════════
 *
 * User rule 2026-10-24: since game analysis now runs AUTOMATICALLY in the
 * background (no share-and-wait between games), the system surfaces bad
 * performance as a POPUP-STYLE NOTIFICATION that gently encourages
 * stopping — before the enforcement machinery makes anything a
 * requirement:
 *
 *  - PIVOT_TO_DRILLS (the warning zone): "strongly consider stopping";
 *    rated play is paused and analysis between games is now REQUIRED —
 *    the automatic sweep already provides it, sharing a game manually is
 *    the fallback when the automatic analysis couldn't be obtained.
 *  - TERMINATE_SESSION (the science-backed stop): stop IS required — the
 *    existing Chess Guard enforcement (rest until re-test) applies.
 *
 * Anything milder (in-session watch warnings like tilt / cold-open) stays
 * display-only in the Chess Status overlay — advice, never a demand.
 */
object ChessStopAdvisor {

    private const val TAG = "ChessStopAdvisor"
    private const val CHANNEL_ID = "chess_stop_advisor"
    private const val NOTIFICATION_ID = 41024

    /**
     * Posts (or replaces) the advisory notification. Deduped per game by
     * the game id — only the latest verdict is ever showing. Never throws.
     */
    fun advise(context: Context, gameId: String, outputState: String) {
        try {
            val terminate = outputState == ChessPhase2Engine.OutputState.TERMINATE_SESSION.name
            val pivot = outputState == ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS.name
            if (!terminate && !pivot) return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Chess stop advisory",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Performance-based recommendations to stop playing"
                }
                context.getSystemService(NotificationManager::class.java)
                    ?.createNotificationChannel(channel)
            }

            // Tapping opens the chess status overlay (read-only detail),
            // same as the bubble's "Chess Status" entry point. ChessStatusOverlay
            // is a Service, so the pending intent must be a service-start intent.
            val intent = Intent(context, ChessStatusOverlay::class.java)
            val pi = PendingIntent.getService(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (terminate) "♟ Stop required — session terminated"
            else "♟ Strongly consider stopping"
            val text = if (terminate) {
                "The automatic analysis of your latest game says performance has " +
                    "collapsed. All play is paused — rest, then take a new readiness " +
                    "test when it re-opens."
            } else {
                "The automatic analysis flagged a bad game. Rated play is paused and " +
                    "analysis between games is now required (it runs automatically; " +
                    "share a game manually if the automatic analysis can't fetch it). " +
                    "You can override via a new readiness test."
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val granted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                if (granted != PackageManager.PERMISSION_GRANTED) return
            }
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.w(TAG, "Stop advisory failed: ${e.message}")
        }
    }
}
