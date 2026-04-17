package com.example.tail

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.tail.data.SpotifyDetector
import com.example.tail.ipc.VoiceNoteService

private const val TAG = "VoiceNoteActivity"

/**
 * Zero-UI trampoline activity for the **voice-listening** note shortcut.
 *
 * Always starts [VoiceNoteService] **without** supplying text, so the service
 * will use its own [android.speech.SpeechRecognizer] to listen for dictation.
 *
 * Captures Spotify playback state **before** starting the service, since
 * SpeechRecognizer activation mutes Spotify.
 */
class VoiceNoteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Capture Spotify state BEFORE anything else — mic activation will mute it
        val spotifyTrack = SpotifyDetector.getCurrentSpotifyTrack(applicationContext)
        if (spotifyTrack != null) {
            Log.i(TAG, "Spotify detected: ${spotifyTrack.title} - ${spotifyTrack.artist}")
        } else if (!SpotifyDetector.isNotificationListenerEnabled(applicationContext)) {
            Log.w(TAG, "Notification listener not enabled — Spotify detection unavailable")
            Toast.makeText(applicationContext, "🎵 Enable Tail in Notification Access for Spotify detection", Toast.LENGTH_LONG).show()
        }

        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceNoteActivity launched — starting VoiceNoteService (voice mode)")

        val serviceIntent = Intent(this, VoiceNoteService::class.java)
        // No EXTRA_TEXT — service will use SpeechRecognizer
        // Pass Spotify track info via extras
        if (spotifyTrack != null) SpotifyDetector.putSpotifyTrack(serviceIntent, spotifyTrack)
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(applicationContext, "📝 Voice note activated", Toast.LENGTH_SHORT).show()
    }
}
