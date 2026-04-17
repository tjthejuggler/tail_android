package com.example.tail.ipc

import android.service.notification.NotificationListenerService
import android.util.Log

private const val TAG = "MusicNotificationListener"

/**
 * Minimal [NotificationListenerService] required for
 * [android.media.session.MediaSessionManager.getActiveSessions] to work.
 *
 * The user must enable this service in Android Settings → Apps → Special access
 * → Notification access. Once enabled, [SpotifyDetector][com.example.tail.data.SpotifyDetector]
 * can query active media sessions to detect the currently playing Spotify track.
 *
 * This service does not intercept or modify any notifications.
 */
class MusicNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected — Spotify detection available")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "Notification listener disconnected — Spotify detection unavailable")
    }
}
