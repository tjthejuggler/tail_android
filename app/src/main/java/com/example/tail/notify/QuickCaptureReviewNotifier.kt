package com.example.tail.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.tail.MainActivity
import com.example.tail.R

/**
 * Posts the system notification for quick captures that could not be
 * processed automatically (NEEDS_REVIEW items in the vision queue).
 *
 * Posted when the app is opened and unreviewed captures exist — the user
 * may have snapped a photo hours earlier, turned the screen off, and left
 * the LLM unable to recognise it. Tapping the notification deep-links to
 * the Quick Capture History, where the intended habit can be assigned and
 * the capture retried.
 */
object QuickCaptureReviewNotifier {

    const val CHANNEL_ID = "quick_capture_reviews"

    /** Single stable id — there is only ever one summary notification. */
    private const val NOTIFICATION_ID = 90210

    /** Creates the channel (no-op below O / when already created). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quick capture review",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Quick capture photos the AI couldn't process — assign a habit and retry"
        }
        nm.createNotificationChannel(channel)
    }

    /**
     * Posts (or updates) the summary notification for [count] unreviewed
     * quick captures. Safe to call when POST_NOTIFICATIONS is not granted —
     * the in-app banner on the habit grid covers that case.
     */
    fun post(context: Context, count: Int) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_QUICK_CAPTURE_HISTORY)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (count == 1) {
            "📸 Quick capture needs review"
        } else {
            "📸 $count quick captures need review"
        }
        val text = if (count == 1) {
            "A photo you captured earlier couldn't be processed. " +
                "Tap to say which habit it was for and retry."
        } else {
            "Some photos you captured earlier couldn't be processed. " +
                "Tap to say which habits they were for and retry."
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_tail)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()

        try {
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — the in-app banner still shows.
        }
    }

    /** Cancels the summary notification (no-op when not shown). */
    fun cancel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(NOTIFICATION_ID)
    }
}
