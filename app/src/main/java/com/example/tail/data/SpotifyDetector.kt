package com.example.tail.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.util.Log
import com.example.tail.ipc.MusicNotificationListenerService

private const val TAG = "SpotifyDetector"
private const val SPOTIFY_PACKAGE = "com.spotify.music"

/** Intent extra key for passing serialized Spotify track between Activity and Service. */
const val EXTRA_SPOTIFY_TITLE = "com.example.tail.EXTRA_SPOTIFY_TITLE"
const val EXTRA_SPOTIFY_ARTIST = "com.example.tail.EXTRA_SPOTIFY_ARTIST"

/**
 * Holds the currently playing Spotify track info.
 */
data class SpotifyTrack(val title: String, val artist: String)

/**
 * Detects whether Spotify is currently playing (or recently paused) music
 * and returns the track info.
 *
 * Uses [MediaSessionManager.getActiveSessions] which requires an enabled
 * [MusicNotificationListenerService]. If the listener isn't enabled by the user,
 * this silently returns null.
 *
 * Accepts both [PlaybackState.STATE_PLAYING] and [PlaybackState.STATE_PAUSED]
 * because Spotify may pause due to audio focus before we can check.
 *
 * Should be called as early as possible — ideally in the Activity before
 * starting the service — because SpeechRecognizer activation mutes Spotify.
 */
object SpotifyDetector {

    fun getCurrentSpotifyTrack(context: Context): SpotifyTrack? {
        return try {
            val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val componentName = ComponentName(context, MusicNotificationListenerService::class.java)
            val sessions = sessionManager.getActiveSessions(componentName)

            val spotifySession = sessions.find { it.packageName == SPOTIFY_PACKAGE }
            if (spotifySession == null) {
                Log.d(TAG, "No active Spotify session found (sessions: ${sessions.map { it.packageName }})")
                return null
            }

            // Accept PLAYING or PAUSED — Spotify may have already paused due to audio focus
            val state = spotifySession.playbackState?.state ?: PlaybackState.STATE_NONE
            if (state != PlaybackState.STATE_PLAYING && state != PlaybackState.STATE_PAUSED) {
                Log.d(TAG, "Spotify session found but not playing/paused (state=$state)")
                return null
            }

            val metadata = spotifySession.metadata
            if (metadata == null) {
                Log.d(TAG, "Spotify session has no metadata")
                return null
            }

            val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: metadata.getString(MediaMetadata.METADATA_KEY_AUTHOR)

            if (title.isNullOrEmpty() || artist.isNullOrEmpty()) {
                Log.d(TAG, "Spotify metadata missing title or artist (title=$title, artist=$artist)")
                return null
            }

            Log.i(TAG, "Spotify detected: $title - $artist (state=$state)")
            SpotifyTrack(title, artist)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot access media sessions — notification listener not enabled. " +
                "Enable in Settings → Apps → Special access → Notification access → Tail")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting Spotify track: ${e.message}")
            null
        }
    }

    /**
     * Returns true if the [MusicNotificationListenerService] is enabled
     * in Android notification access settings.
     */
    fun isNotificationListenerEnabled(context: Context): Boolean {
        val componentName = ComponentName(context, MusicNotificationListenerService::class.java)
        val enabledListeners = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners"
        ) ?: return false
        return enabledListeners.contains(componentName.flattenToString())
    }

    /**
     * Opens the Android notification access settings screen so the user
     * can enable [MusicNotificationListenerService].
     */
    fun openNotificationListenerSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not open notification listener settings: ${e.message}")
        }
    }

    /**
     * Writes [SpotifyTrack] into an [Intent] as extras.
     */
    fun putSpotifyTrack(intent: Intent, track: SpotifyTrack) {
        intent.putExtra(EXTRA_SPOTIFY_TITLE, track.title)
        intent.putExtra(EXTRA_SPOTIFY_ARTIST, track.artist)
    }

    /**
     * Reads [SpotifyTrack] from an [Intent]'s extras, or null if not present.
     */
    fun fromIntent(intent: Intent?): SpotifyTrack? {
        val title = intent?.getStringExtra(EXTRA_SPOTIFY_TITLE)
        val artist = intent?.getStringExtra(EXTRA_SPOTIFY_ARTIST)
        return if (title != null && artist != null) SpotifyTrack(title, artist) else null
    }
}
