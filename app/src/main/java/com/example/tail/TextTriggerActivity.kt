package com.example.tail

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.tail.ipc.VoiceHabitService

private const val TAG = "TextTriggerActivity"

/**
 * Zero-UI trampoline activity for the **text-passthrough** habit trigger shortcut.
 *
 * Unlike [VoiceTriggerActivity] (which starts the SpeechRecognizer), this
 * activity always expects pre-recognized text to be supplied in the intent
 * (e.g. from Tasker voice recognition or Samsung Routines text input).
 * The text is forwarded to [VoiceHabitService] via [Intent.EXTRA_TEXT] so
 * the service skips its own SpeechRecognizer and processes the text directly.
 *
 * If no text is supplied, a warning toast is shown and the service is not started.
 */
class TextTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "TextTriggerActivity launched")

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

        val suppliedText = extractText(intent)

        if (suppliedText.isNullOrEmpty()) {
            Log.w(TAG, "No text supplied — cannot process trigger")
            Toast.makeText(applicationContext, "🎤 No text supplied for trigger", Toast.LENGTH_SHORT).show()
            return
        }

        val serviceIntent = Intent(this, VoiceHabitService::class.java)
        serviceIntent.putExtra(Intent.EXTRA_TEXT, suppliedText)
        Log.i(TAG, "Forwarding supplied text: \"$suppliedText\"")

        ContextCompat.startForegroundService(this, serviceIntent)
        Toast.makeText(applicationContext, "🎤 Processing: \"$suppliedText\"", Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * Extracts text from an intent by checking multiple common extra keys
         * and the data URI. Returns the first non-empty value found, or null.
         */
        fun extractText(intent: Intent?): String? {
            if (intent == null) return null

            val extraKeys = listOf(
                Intent.EXTRA_TEXT,          // "android.intent.extra.TEXT"
                "text",                     // common shorthand
                "voice_text",               // custom
                "android.intent.extra.PROCESS_TEXT", // ACTION_PROCESS_TEXT
                "query"                     // some automation tools use this
            )

            for (key in extraKeys) {
                val value = intent.getStringExtra(key)
                if (!value.isNullOrEmpty()) return value
            }

            // Fallback: check data URI
            val data = intent.dataString
            if (!data.isNullOrEmpty()) return data

            return null
        }
    }
}
