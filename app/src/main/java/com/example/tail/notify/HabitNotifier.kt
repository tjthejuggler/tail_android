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
import com.example.tail.data.HabitNotification

/**
 * Posts and cancels the Android system notifications for pending habit asks.
 *
 * The system notification is a *view* of the [com.example.tail.data.NotificationStore]
 * record — it carries the ask id so [NotificationActionReceiver] can route the
 * Yes/No answer back to the store (answer-anywhere → dismiss-everywhere).
 */
object HabitNotifier {

    const val CHANNEL_ID = "habit_asks"
    const val CHANNEL_ID_MOVIES = "habit_asks_movies"
    const val CHANNEL_ID_INFO = "habit_notices"

    /**
     * Channel for [ask] — one per category so the user (and companion
     * devices like Garmin that mirror per-channel phone settings) can
     * block a single category, e.g. movie prompts, without affecting
     * the other asks.
     */
    fun channelIdFor(ask: HabitNotification): String = when (ask.type) {
        HabitNotification.TYPE_MOVIE -> CHANNEL_ID_MOVIES
        HabitNotification.TYPE_INFO -> CHANNEL_ID_INFO
        else -> CHANNEL_ID
    }

    /** Stable system notification id derived from the ask id string. */
    fun systemNotificationId(askId: String): Int = askId.hashCode()

    /** Creates the channels (no-op below O / when already created). */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channels = listOf(
            NotificationChannel(
                CHANNEL_ID,
                "Habit confirmations",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Asks whether you did a habit, waiting for a Yes/No answer"
            },
            NotificationChannel(
                CHANNEL_ID_MOVIES,
                "Movie confirmations",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Asks whether to log a watched movie — can be turned off per-category (e.g. off the watch) without affecting other asks"
            },
            NotificationChannel(
                CHANNEL_ID_INFO,
                "Notices",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Informational notices waiting for an acknowledgement"
            }
        )
        nm.createNotificationChannels(channels)
    }

    /**
     * Posts (or updates) the system notification for [ask] with Yes/No actions
     * (a single "OK" action for [HabitNotification.TYPE_INFO] notices).
     * Safe to call when permission is not granted — the ask still lives in the
     * in-app notification center.
     */
    fun postAsk(context: Context, ask: HabitNotification) {
        ensureChannel(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        val contentIntent = PendingIntent.getActivity(
            context,
            ask.id.hashCode(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                // App-stats record notices deep-link straight to the App
                // Stats screen (their "App Stats" label is the link target).
                if (ask.id.startsWith("appstats:")) {
                    putExtra(MainActivity.EXTRA_OPEN_ROUTE, MainActivity.ROUTE_APP_STATS)
                }
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val yesIntent = PendingIntent.getBroadcast(
            context,
            (ask.id + ":yes").hashCode(),
            NotificationActionReceiver.answerIntent(context, ask.id, answer = true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val noIntent = PendingIntent.getBroadcast(
            context,
            (ask.id + ":no").hashCode(),
            NotificationActionReceiver.answerIntent(context, ask.id, answer = false),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val emoji = when (ask.type) {
            HabitNotification.TYPE_MOVIE -> "🎬"
            HabitNotification.TYPE_INFO -> "⚠️"
            else -> "❓"
        }
        val builder = Notification.Builder(context, channelIdFor(ask))
            .setSmallIcon(R.drawable.ic_stat_tail)
            .setContentTitle("$emoji ${ask.title}")
            .setContentText(ask.question)
            .setStyle(Notification.BigTextStyle().bigText(ask.question))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (ask.type == HabitNotification.TYPE_INFO) {
            // Informational — a single acknowledge action; the answer itself
            // is a no-op that removes the notice everywhere.
            builder.addAction(Notification.Action.Builder(null, "✓ OK", yesIntent).build())
        } else {
            builder.addAction(Notification.Action.Builder(null, "✓ Yes", yesIntent).build())
            builder.addAction(Notification.Action.Builder(null, "✗ No", noIntent).build())
        }
        val notification = builder.build()

        try {
            nm.notify(systemNotificationId(ask.id), notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted — the ask remains in the in-app center.
        }
    }

    /** Cancels the system notification for [askId] (no-op when not shown). */
    fun cancelAsk(context: Context, askId: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.cancel(systemNotificationId(askId))
    }
}
