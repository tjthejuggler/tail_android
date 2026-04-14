package com.example.tail

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.tail.ipc.VoiceHabitService

private const val TAG = "VoiceTriggerActivity"

/**
 * Zero-UI trampoline activity for the **voice-listening** habit trigger shortcut.
 *
 * Always starts [VoiceHabitService] **without** supplying text, so the service
 * will use its own [android.speech.SpeechRecognizer] to listen for trigger words.
 *
 * For the text-passthrough variant (where pre-recognized text is forwarded
 * instead of listening), see [TextTriggerActivity].
 */
class VoiceTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceTriggerActivity launched — starting VoiceHabitService (voice mode)")

        val serviceIntent = Intent(this, VoiceHabitService::class.java)
        // No EXTRA_TEXT — service will use SpeechRecognizer
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(applicationContext, "🎤 Voice trigger activated", Toast.LENGTH_SHORT).show()
    }
}
