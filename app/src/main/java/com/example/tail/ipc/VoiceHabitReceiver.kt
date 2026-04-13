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
 */
class VoiceHabitReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_VOICE_HABIT = "com.example.tail.ACTION_VOICE_HABIT"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_VOICE_HABIT) return
        Log.i(TAG, "Received ACTION_VOICE_HABIT — starting VoiceHabitService")
        val serviceIntent = Intent(context, VoiceHabitService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
