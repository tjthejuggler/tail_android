package com.example.tail

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.tail.ipc.VoiceNoteService

private const val TAG = "VoiceNoteActivity"

/**
 * Zero-UI trampoline activity for the **voice-listening** note shortcut.
 *
 * Always starts [VoiceNoteService] **without** supplying text, so the service
 * will use its own [android.speech.SpeechRecognizer] to listen for dictation.
 *
 * For the text-passthrough variant (where pre-recognized text is forwarded
 * instead of listening), see [TextNoteActivity].
 */
class VoiceNoteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceNoteActivity launched — starting VoiceNoteService (voice mode)")

        val serviceIntent = Intent(this, VoiceNoteService::class.java)
        // No EXTRA_TEXT — service will use SpeechRecognizer
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(applicationContext, "📝 Voice note activated", Toast.LENGTH_SHORT).show()
    }
}
