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
 * Zero-UI trampoline activity that Samsung Routines / Tasker launches via App Shortcuts.
 * Calls finish() before super.onCreate() to prevent any window from being
 * created, so the current foreground app is not disturbed.
 *
 * If the incoming intent contains text data (e.g. from Tasker voice
 * recognition), it is forwarded to [VoiceHabitService] so the app skips its own
 * SpeechRecognizer and processes the text directly.
 *
 * Checks multiple extra keys for the text:
 * - `android.intent.extra.TEXT` (standard Android)
 * - `text` (common shorthand)
 * - `voice_text` (custom)
 * Also checks `intent.data` (URI) as a fallback.
 */
class VoiceTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceTriggerActivity launched — starting VoiceHabitService")

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

        val serviceIntent = Intent(this, VoiceHabitService::class.java)
        if (!suppliedText.isNullOrEmpty()) {
            serviceIntent.putExtra(Intent.EXTRA_TEXT, suppliedText)
            Log.i(TAG, "Forwarding supplied text: \"$suppliedText\"")
        }

        ContextCompat.startForegroundService(this, serviceIntent)

        val toastMsg = if (!suppliedText.isNullOrEmpty())
            "🎤 Processing: \"$suppliedText\""
        else
            "🎤 Voice trigger activated"
        Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * Extracts text from an intent by checking multiple common extra keys
         * and the data URI. Returns the first non-empty value found, or null.
         */
        fun extractText(intent: Intent?): String? {
            if (intent == null) return null

            // Check standard and common extra keys
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
