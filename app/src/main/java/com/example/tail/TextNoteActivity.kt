package com.example.tail

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.tail.data.SpotifyDetector
import com.example.tail.ipc.VoiceNoteService

private const val TAG = "TextNoteActivity"

/**
 * Zero-UI trampoline activity for the **text-passthrough** note shortcut.
 *
 * Unlike [VoiceNoteActivity] (which starts the SpeechRecognizer), this
 * activity always expects pre-recognized text to be supplied in the intent
 * (e.g. from Tasker voice recognition or Samsung Routines text input).
 * The text is forwarded to [VoiceNoteService] via [Intent.EXTRA_TEXT] so
 * the service skips its own SpeechRecognizer and writes the text directly.
 *
 * If no text is supplied, a warning toast is shown and the service is not started.
 *
 * Captures Spotify playback state **before** starting the service.
 */
class TextNoteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Capture Spotify state BEFORE anything else
        val spotifyTrack = SpotifyDetector.getCurrentSpotifyTrack(applicationContext)
        if (spotifyTrack != null) {
            Log.i(TAG, "Spotify detected: ${spotifyTrack.title} - ${spotifyTrack.artist}")
        }

        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "TextNoteActivity launched")

        // Debug: log all intent extras
        val extras = intent?.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                Log.d(TAG, "Intent extra: key=$key value=${extras.get(key)}")
            }
        } else {
            Log.d(TAG, "Intent has no extras")
        }
        Log.d(TAG, "Intent data URI: ${intent?.data}")
        Log.d(TAG, "Intent action: ${intent?.action}")

        val suppliedText = TextTriggerActivity.extractText(intent)

        if (suppliedText.isNullOrEmpty()) {
            Log.w(TAG, "No text supplied — cannot save note")
            Toast.makeText(applicationContext, "📝 No text supplied for note", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, VoiceNoteService::class.java)
        serviceIntent.putExtra(Intent.EXTRA_TEXT, suppliedText)
        // Pass Spotify track info via extras
        if (spotifyTrack != null) SpotifyDetector.putSpotifyTrack(serviceIntent, spotifyTrack)
        Log.i(TAG, "Forwarding supplied text: \"$suppliedText\"")

        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(applicationContext, "📝 Saving note: \"$suppliedText\"", Toast.LENGTH_SHORT).show()
    }
}
