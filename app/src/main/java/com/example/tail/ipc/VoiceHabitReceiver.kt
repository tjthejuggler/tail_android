package com.example.tail.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

private const val TAG = "VoiceHabitReceiver"

/**
 * BroadcastReceiver that starts the [VoiceHabitService] when triggered.
 *
 * Action: com.example.tail.ACTION_VOICE_HABIT
 *
 * Designed to be triggered by Samsung Routines (or any external automation).
 * Exported without a permission restriction because Samsung Routines is not
 * signed with our keystore. The worst case is someone triggers a short
 * voice-listen session — no data is leaked.
 *
 * If the incoming intent contains [Intent.EXTRA_TEXT] (e.g. from Tasker voice
 * recognition), it is forwarded to [VoiceHabitService] so the app skips its
 * own SpeechRecognizer and processes the text directly.
 */
class VoiceHabitReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_VOICE_HABIT = "com.example.tail.ACTION_VOICE_HABIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_VOICE_HABIT) return
        Log.i(TAG, "Received ACTION_VOICE_HABIT — starting VoiceHabitService")

        // Debug: log all intent extras
        val extras = intent.extras
        if (extras != null) {
            for (key in extras.keySet()) {
                Log.d(TAG, "Intent extra: key=$key value=${extras.get(key)}")
            }
        }
        Log.d(TAG, "Intent data URI: ${intent.data}")

        val serviceIntent = Intent(context, VoiceHabitService::class.java)

        // Forward any text supplied by Tasker / external automation
        val suppliedText = com.example.tail.VoiceTriggerActivity.extractText(intent)
        if (!suppliedText.isNullOrEmpty()) {
            serviceIntent.putExtra(Intent.EXTRA_TEXT, suppliedText)
            Log.i(TAG, "Forwarding supplied text: \"$suppliedText\"")
        }

        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
