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
 * Zero-UI trampoline activity that Samsung Routines launches via App Shortcuts.
 * Calls finish() before super.onCreate() to prevent any window from being
 * created, so the current foreground app is not disturbed.
 */
class VoiceTriggerActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "VoiceTriggerActivity launched — starting VoiceHabitService")

        val serviceIntent = Intent(this, VoiceHabitService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(applicationContext, "🎤 Voice trigger activated", Toast.LENGTH_SHORT).show()
    }
}
