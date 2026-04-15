package com.example.tail

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.tail.ipc.SmartVoiceService

private const val TAG = "SmartVoiceActivity"

/**
 * Zero-UI trampoline activity for the **smart voice** shortcut.
 *
 * Always starts [SmartVoiceService] **without** supplying text, so the service
 * will use its own [android.speech.SpeechRecognizer] to listen for input.
 * The service then smartly routes the recognized text to either habit
 * incrementing (if most words match trigger words) or note saving (if not).
 */
class SmartVoiceActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // Finish before super.onCreate() to prevent window creation
        finish()

        super.onCreate(savedInstanceState)
        Log.i(TAG, "SmartVoiceActivity launched — starting SmartVoiceService (voice mode)")

        val serviceIntent = Intent(this, SmartVoiceService::class.java)
        // No EXTRA_TEXT — service will use SpeechRecognizer
        ContextCompat.startForegroundService(this, serviceIntent)

        Toast.makeText(applicationContext, "🧠 Smart voice activated", Toast.LENGTH_SHORT).show()
    }
}
