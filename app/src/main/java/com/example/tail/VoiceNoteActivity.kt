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
 * Zero-UI trampoline activity that Samsung Routines / Tasker launches via App Shortcuts.
 * Calls finish() before super.onCreate() to prevent any window from being
 * created, so the current foreground app is not disturbed.
 *
 * If the incoming intent contains text data (e.g. from Tasker voice
 * recognition), it is forwarded to [VoiceNoteService] so the app skips its own
 * SpeechRecognizer and writes the text directly to the notes file.
 *
 * Checks multiple extra keys for the text:
 * - `android.intent.extra.TEXT` (standard Android)
 * - `text` (common shorthand)
 * - `voice_text` (custom)
 * Also checks `intent.data` (URI) as a fallback.
 */
class VoiceNoteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceNoteActivity launched — starting VoiceNoteService")

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

        val suppliedText = VoiceTriggerActivity.extractText(intent)

        val serviceIntent = Intent(this, VoiceNoteService::class.java)
        if (!suppliedText.isNullOrEmpty()) {
            serviceIntent.putExtra(Intent.EXTRA_TEXT, suppliedText)
            Log.i(TAG, "Forwarding supplied text: \"$suppliedText\"")
        }

        ContextCompat.startForegroundService(this, serviceIntent)

        val toastMsg = if (!suppliedText.isNullOrEmpty())
            "📝 Saving note: \"$suppliedText\""
        else
            "📝 Voice note activated"
        Toast.makeText(applicationContext, toastMsg, Toast.LENGTH_SHORT).show()
    }
}
