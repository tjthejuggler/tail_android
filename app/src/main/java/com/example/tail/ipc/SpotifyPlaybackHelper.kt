package com.example.tail.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaSessionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast

/**
 * Plays a logged media-habit song entry in Spotify.
 *
 * Media habits log every play as `"HH:mm Title — Artist (NN min)"` plus —
 * for Spotify plays — the track URI (`" — spotify:track:…"`, captured at
 * play time by [SpotifyTrackIdCache]). This helper parses the line and
 * makes Spotify play it:
 *
 *  1. **Track-URI deep-link (preferred)** — when the entry carries a
 *     `spotify:track:` URI, it is deep-linked into Spotify (forces the
 *     player to load the song), playback is started via Spotify's
 *     MediaSession, and Tail is brought back to the foreground once the
 *     song is actually playing. Same priming pattern as the wags app.
 *  2. **Search fallback** — entries without a URI fall back to Spotify's
 *     search page; Spotify stays in the foreground so the user can tap
 *     the result (nothing auto-plays reliably from a bare search).
 */
object SpotifyPlaybackHelper {

    private const val TAG = "SpotifyPlayback"
    private const val SPOTIFY_PACKAGE = "com.spotify.music"

    /** Regex extracting a Spotify track URI from a logged entry. */
    private val TRACK_URI_RE = Regex("spotify:track:[A-Za-z0-9]+")

    /** Deep-link → player-load delay before sending the PLAY command. */
    private const val PRIME_DELAY_MS = 1_500L
    /** Extra delay after PLAY before checking playback / returning to Tail. */
    private const val PLAY_SETTLE_DELAY_MS = 1_200L

    /**
     * Parses a media-habit log line into `(title, artist)`, or null when the
     * line is not a media entry. Accepts the full stored form
     * `"HH:mm Title — Artist (NN min) — uri"` as well as bare
     * `"Title — Artist"` fragments.
     */
    fun parseMediaEntry(entry: String): Pair<String, String>? {
        var s = entry.trim()
        // Leading clock time ("HH:mm" or "HH:mm:ss")
        s = s.replace(Regex("^\\d{1,2}:\\d{2}(:\\d{2})?\\s+"), "")
        // Trailing media URI segment (" — spotify:…" / " — https://…")
        s = s.replace(Regex("\\s+—\\s+(spotify:|https?://).*$"), "")
        // Trailing duration ("(NN min)")
        s = s.replace(Regex("\\s+\\(\\d+\\s*min\\)$"), "")
        val idx = s.indexOf(" — ")
        if (idx <= 0) return null
        val title = s.substring(0, idx).trim()
        val artist = s.substring(idx + 3).trim()
        if (title.isEmpty() || artist.isEmpty()) return null
        return title to artist
    }

    /**
     * Display form of a logged entry: the trailing `— spotify:track:…`
     * (or any media URI) is playback metadata, not something to read —
     * hidden from the graph and edit lists.
     */
    fun displayText(entry: String): String =
        entry.replace(Regex("\\s+—\\s+(spotify:|https?://).*$"), "").trim()

    /** Convenience: parse [entry] and play the song it names. */
    fun playFromEntry(context: Context, entry: String): Boolean {
        val parsed = parseMediaEntry(entry) ?: return false
        val trackUri = TRACK_URI_RE.find(entry)?.value
        if (trackUri != null) {
            playTrackUri(context, trackUri, parsed.first)
        } else {
            playSearch(context, "${parsed.first} ${parsed.second}")
        }
        return true
    }

    /**
     * Direct play of a known `spotify:track:` URI:
     *  1. Deep-link the URI into Spotify (loads its player with the song).
     *  2. After a short prime delay, send PLAY to Spotify's MediaSession
     *     (works even while Tail sits behind Spotify).
     *  3. Once the song is confirmed playing, bring Tail back to the
     *     foreground. If playback did NOT start, Spotify is left open on
     *     the track page so the user can tap play themselves.
     */
    fun playTrackUri(context: Context, trackUri: String, title: String? = null) {
        val handler = Handler(Looper.getMainLooper())
        Toast.makeText(context, "▶ ${title ?: trackUri}", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "playTrackUri: $trackUri")

        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse(trackUri))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(deepLink)
        } catch (e: Exception) {
            Log.w(TAG, "Track deep-link failed: ${e.message} — trying search")
            playSearch(context, title ?: "")
            return
        }

        // Give Spotify a moment to load the track page / initialise its
        // player engine, then start playback via its MediaSession.
        handler.postDelayed({
            playViaSession(context)
            // After the play command settles, return to Tail — but only if
            // the song is actually playing; otherwise leave Spotify visible
            // so the user can press play on the loaded track.
            handler.postDelayed({
                if (isSpotifyPlaying(context)) {
                    Log.i(TAG, "Track playing — returning to Tail")
                    bringAppBack(context)
                } else {
                    Log.w(TAG, "Track not playing after PLAY — leaving Spotify open")
                }
            }, PLAY_SETTLE_DELAY_MS)
        }, PRIME_DELAY_MS)
    }

    /**
     * Search fallback for entries without a captured track URI: opens
     * Spotify's search for the song. Spotify stays in the foreground —
     * nothing reliably auto-plays from a bare search, so the user needs
     * to tap the top result.
     */
    fun playSearch(context: Context, query: String) {
        if (query.isBlank()) return
        Toast.makeText(context, "🔍 $query", Toast.LENGTH_SHORT).show()
        val encoded = Uri.encode(query)
        val deepLink = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(deepLink)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$encoded"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (e2: Exception) {
                Log.w(TAG, "Spotify launch failed: ${e2.message}")
                Toast.makeText(context, "Spotify not available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Sends a plain PLAY command to Spotify's active MediaSession. */
    private fun playViaSession(context: Context): Boolean = try {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val ctrl = msm.getActiveSessions(
            ComponentName(context, com.example.tail.ipc.MusicNotificationListenerService::class.java)
        ).firstOrNull { it.packageName == SPOTIFY_PACKAGE } ?: return false
        ctrl.transportControls.play()
        true
    } catch (e: Exception) {
        Log.w(TAG, "playViaSession failed: ${e.message}")
        false
    }

    /** True when Spotify has an active session currently in STATE_PLAYING. */
    private fun isSpotifyPlaying(context: Context): Boolean = try {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val ctrl = msm.getActiveSessions(
            ComponentName(context, com.example.tail.ipc.MusicNotificationListenerService::class.java)
        ).firstOrNull { it.packageName == SPOTIFY_PACKAGE } ?: return false
        ctrl.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING
    } catch (e: Exception) {
        false
    }

    /** Brings Tail back to the foreground (best-effort). */
    private fun bringAppBack(context: Context) {
        try {
            context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(it)
            }
        } catch (_: Exception) {
        }
    }
}
