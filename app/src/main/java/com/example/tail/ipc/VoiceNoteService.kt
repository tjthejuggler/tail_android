package com.example.tail.ipc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Build
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import com.example.tail.data.SettingsRepository
import com.example.tail.data.SpotifyDetector
import com.example.tail.data.SpotifyTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val TAG = "VoiceNoteService"
private const val CHANNEL_ID = "voice_note_channel"
private const val NOTIFICATION_ID = 9002
private const val LISTEN_TIMEOUT_MS = 30_000L // 30 seconds for longer dictation

/**
 * Foreground service that writes dictated text as a timestamped note to a
 * markdown file.
 *
 * Two modes of operation:
 *  1. **Text supplied** — If the launching intent contains [Intent.EXTRA_TEXT]
 *     (e.g. from Tasker voice recognition), the text is written directly to
 *     the notes file without starting the SpeechRecognizer.
 *  2. **Voice listening** — If no text is supplied, the service uses Android's
 *     [SpeechRecognizer] to listen for up to [LISTEN_TIMEOUT_MS] ms.
 *
 * The note is prepended (added to the top) with a date/time header like:
 * ```
 * ## 2026-04-13 16:24:50
 * The dictated text goes here.
 *
 * ```
 */
class VoiceNoteService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false

    /** Captured before SpeechRecognizer starts (which mutes Spotify). */
    private var spotifyTrack: SpotifyTrack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read Spotify track from intent extras (captured by Activity before mic activation)
        // Fall back to direct detection if not provided (e.g. started from broadcast receiver)
        spotifyTrack = SpotifyDetector.fromIntent(intent)
            ?: SpotifyDetector.getCurrentSpotifyTrack(applicationContext)

        val suppliedText = com.example.tail.TextTriggerActivity.extractText(intent)
        val notificationText = if (!suppliedText.isNullOrEmpty())
            "📝 Saving note: \"$suppliedText\""
        else
            "📝 Listening for voice note…"
        val notification = buildNotification(notificationText)
        startForeground(NOTIFICATION_ID, notification)

        scope.launch {
            val settingsRepo = SettingsRepository(applicationContext)
            val settings = settingsRepo.settingsFlow.first()

            if (!settings.voiceNoteEnabled) {
                Log.w(TAG, "Voice note is globally disabled — stopping")
                handler.post { Toast.makeText(applicationContext, "📝 Voice note is disabled", Toast.LENGTH_SHORT).show() }
                stopSelfCleanly()
                return@launch
            }

            if (settings.voiceNoteFileUri.isEmpty()) {
                Log.w(TAG, "No notes file configured — stopping")
                handler.post { Toast.makeText(applicationContext, "📝 No notes file selected", Toast.LENGTH_SHORT).show() }
                stopSelfCleanly()
                return@launch
            }

            if (!suppliedText.isNullOrEmpty()) {
                // ── Direct text mode (from Tasker / external automation) ────
                Log.i(TAG, "Processing supplied text: \"$suppliedText\"")
                handler.post { Toast.makeText(applicationContext, "📝 Saving note: \"$suppliedText\"", Toast.LENGTH_SHORT).show() }
                prependNoteToFile(suppliedText, settings.voiceNoteFileUri, spotifyTrack)
            } else {
                // ── Voice listening mode ───────────────────────────────────
                Log.i(TAG, "Starting voice note dictation")
                handler.post { Toast.makeText(applicationContext, "📝 Listening for note…", Toast.LENGTH_SHORT).show() }
                startListening(settings.voiceNoteFileUri, spotifyTrack)
            }
        }

        // Safety timeout
        handler.postDelayed({
            Log.i(TAG, "Listen timeout reached (${LISTEN_TIMEOUT_MS}ms) — stopping")
            handler.post { Toast.makeText(applicationContext, "📝 Timeout — no speech detected", Toast.LENGTH_SHORT).show() }
            stopSelfCleanly()
        }, LISTEN_TIMEOUT_MS)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    // ── Speech recognition ───────────────────────────────────────────────

    private fun startListening(fileUri: String, capturedSpotifyTrack: SpotifyTrack? = null) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available")
            handler.post { Toast.makeText(applicationContext, "📝 Speech recognition not available", Toast.LENGTH_LONG).show() }
            stopSelfCleanly()
            return
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer = recognizer

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "Ready for speech")
                vibrateReady()
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "Speech started")
            }

            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                Log.d(TAG, "Speech ended")
            }

            override fun onError(error: Int) {
                val errorName = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
                    SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
                    SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
                    SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
                    SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
                    else -> "UNKNOWN($error)"
                }
                Log.w(TAG, "Speech recognition error: $errorName")
                handler.post { Toast.makeText(applicationContext, "📝 Error: $errorName", Toast.LENGTH_SHORT).show() }
                stopSelfCleanly()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches.isNullOrEmpty()) {
                    Log.i(TAG, "No speech results — stopping")
                    handler.post { Toast.makeText(applicationContext, "📝 No speech detected", Toast.LENGTH_SHORT).show() }
                    stopSelfCleanly()
                    return
                }

                // Use the best match (first result)
                val dictatedText = matches.first()
                Log.i(TAG, "Dictated text: \"$dictatedText\"")
                prependNoteToFile(dictatedText, fileUri, capturedSpotifyTrack)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        recognizer.startListening(recognizerIntent)
    }

    // ── File writing ─────────────────────────────────────────────────────

    private fun prependNoteToFile(text: String, fileUriString: String, capturedSpotifyTrack: SpotifyTrack? = null) {
        scope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(fileUriString)
                val now = LocalDateTime.now()
                val timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val musicHeader = capturedSpotifyTrack?.let { "\nmusic\n${it.title} - ${it.artist}" } ?: ""
                val newEntry = "## $timestamp$musicHeader\n$text\n\n"

                // Read existing content
                val existingContent = try {
                    applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: ""
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read existing file (may be new): ${e.message}")
                    ""
                }

                // Write new entry + existing content
                applicationContext.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.bufferedWriter().use { writer ->
                        writer.write(newEntry)
                        writer.write(existingContent)
                    }
                }

                Log.i(TAG, "Note prepended to file: \"$text\"")
                handler.post {
                    val preview = if (text.length > 40) text.take(40) + "…" else text
                    Toast.makeText(applicationContext, "📝 Note saved: \"$preview\"", Toast.LENGTH_SHORT).show()
                }

                // Show a notification with the full note text so the user can verify it
                showNoteSavedNotification(text, timestamp)

                vibrateConfirmation()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write note: ${e.message}", e)
                handler.post { Toast.makeText(applicationContext, "📝 Error saving note: ${e.message}", Toast.LENGTH_LONG).show() }
            } finally {
                handler.post { stopSelfCleanly() }
            }
        }
    }

    // ── Note saved notification ──────────────────────────────────────────

    private fun showNoteSavedNotification(noteText: String, timestamp: String) {
        val noteChannelId = "voice_note_saved_channel"
        val channel = NotificationChannel(
            noteChannelId,
            "Voice Note Saved",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows the full text of saved voice notes"
            enableVibration(false)
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)

        val notification = Notification.Builder(this, noteChannelId)
            .setContentTitle("📝 Note saved — $timestamp")
            .setContentText(noteText)
            .setStyle(Notification.BigTextStyle().bigText(noteText))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setAutoCancel(true)
            .build()

        mgr.notify(NOTIFICATION_ID + 1000, notification)
        Log.d(TAG, "Note saved notification shown")
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun vibrateReady() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(VibrationEffect.createOneShot(150, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
            Log.w(TAG, "Ready vibration failed: ${e.message}")
        }
    }

    private fun vibrateConfirmation() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            // Two short pulses for note saved
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 100, 80, 100), -1))
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed: ${e.message}")
        }
    }

    private fun stopSelfCleanly() {
        if (stopped) return
        stopped = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Voice Note Dictation",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Tail is listening for voice note dictation"
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Tail")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "tail:VoiceNoteWakeLock"
        ).apply {
            acquire(LISTEN_TIMEOUT_MS + 5_000)
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }
}
