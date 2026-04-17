package com.example.tail

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.tail.data.SpotifyDetector
import com.example.tail.ipc.SmartVoiceService

private const val TAG = "SmartVoiceActivity"

/**
 * Zero-UI trampoline activity for the **smart voice** shortcut.
 *
 * Always starts [SmartVoiceService] **without** supplying text, so the service
 * will use its own [android.speech.SpeechRecognizer] to listen for input.
 * The service then smartly routes the recognized text to either habit
 * incrementing (if most words match trigger words) or note saving (if not).
 *
 * Captures Spotify playback state **before** starting the service, since
 * SpeechRecognizer activation mutes Spotify.
 */
class SmartVoiceActivity : Activity() {

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
        Log.i(TAG, "SmartVoiceActivity launched — starting SmartVoiceService (voice mode)")

        val serviceIntent = Intent(this, SmartVoiceService::class.java)
        // No EXTRA_TEXT — service will use SpeechRecognizer
        // Pass Spotify track info via extras
        if (spotifyTrack != null) SpotifyDetector.putSpotifyTrack(serviceIntent, spotifyTrack)
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(applicationContext, "🧠 Smart voice activated", Toast.LENGTH_SHORT).show()
    }
}
