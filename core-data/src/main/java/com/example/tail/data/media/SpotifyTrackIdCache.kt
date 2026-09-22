package com.example.tail.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Captures the Spotify track id for the CURRENTLY playing song by listening
 * to Spotify's legacy `com.spotify.music.metadatachanged` broadcast (no
 * permissions required — the same mechanism the wags app uses).
 *
 * Spotify's MediaSession metadata usually does NOT include the track URI,
 * but this broadcast carries the track id (`id` extra, e.g. the hash part
 * of `spotify:track:<id>`). [MediaPlaybackTracker][com.example.tail.widget.MediaPlaybackTracker]
 * appends the resolved `spotify:track:<id>` to every logged play so the
 * tap-to-play feature can deep-link the exact song later.
 *
 * The receiver is registered once (from the tracker's poll loop whenever a
 * Spotify-backed media habit is configured) and lives for the process.
 */
object SpotifyTrackIdCache {

    private const val TAG = "SpotifyTrackId"
    const val METADATA_CHANGED_ACTION = "com.spotify.music.metadatachanged"

    /** Cache entries older than this are considered stale. */
    private const val MAX_AGE_MS = 10 * 60_000L

    @Volatile
    private var registered = false

    @Volatile
    private var cachedId: String? = null
    @Volatile
    private var cachedTitle: String? = null
    @Volatile
    private var cachedAt: Long = 0L

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val rawId = intent?.getStringExtra("id") ?: return
            val title = intent.getStringExtra("track")?.trim().orEmpty()
            cachedId = if (rawId.startsWith("spotify:")) rawId else "spotify:track:$rawId"
            cachedTitle = title.takeIf { it.isNotEmpty() }
            cachedAt = System.currentTimeMillis()
            Log.d(TAG, "metadatachanged: id=$cachedId title=$title")
        }
    }

    /** Idempotently registers the broadcast receiver. */
    fun ensureRegistered(context: Context) {
        if (registered) return
        synchronized(this) {
            if (registered) return
            try {
                ContextCompat.registerReceiver(
                    context.applicationContext,
                    receiver,
                    IntentFilter(METADATA_CHANGED_ACTION),
                    ContextCompat.RECEIVER_EXPORTED
                )
                registered = true
                Log.i(TAG, "Spotify metadatachanged receiver registered")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register Spotify receiver: ${e.message}")
            }
        }
    }

    /**
     * Returns `spotify:track:<id>` when the cached broadcast matches [title]
     * (case-insensitive substring either way) and is fresh enough; null
     * otherwise. Title matching guards against logging the URI of the
     * PREVIOUS song when the session metadata lags the broadcast.
     */
    fun matchingTrackUri(title: String): String? {
        val id = cachedId ?: return null
        if (System.currentTimeMillis() - cachedAt > MAX_AGE_MS) return null
        val cached = cachedTitle ?: return id
        val a = title.lowercase()
        val b = cached.lowercase()
        return if (a.contains(b) || b.contains(a)) id else null
    }
}
