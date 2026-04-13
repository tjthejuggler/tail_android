package com.example.tail.ipc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.applyDivider
import com.example.tail.data.dateString
import com.example.tail.ui.ACTION_HABIT_INCREMENTED
import com.example.tail.ui.EXTRA_HABIT_NAME
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

private const val TAG = "VoiceHabitService"
private const val CHANNEL_ID = "voice_habit_channel"
private const val NOTIFICATION_ID = 9001
private const val LISTEN_TIMEOUT_MS = 8_000L

/**
 * Foreground service that uses Android's [SpeechRecognizer] to listen for
 * voice trigger words and increment the matching habit(s).
 *
 * Lifecycle:
 *  1. Started by [VoiceHabitReceiver] via `startForegroundService`
 *  2. Shows a foreground notification ("Listening for habit trigger…")
 *  3. Acquires a partial wake lock (CPU stays on while screen is off)
 *  4. Loads trigger words from [SettingsRepository]
 *  5. Starts speech recognition for up to [LISTEN_TIMEOUT_MS] ms
 *  6. On match → increments habit, vibrates, stops self
 *  7. On timeout / error → stops self
 */
class VoiceHabitService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false

    // ── Service lifecycle ────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("🎤 Listening for habit trigger…")
        startForeground(NOTIFICATION_ID, notification)

        scope.launch {
            val settingsRepo = SettingsRepository(applicationContext)
            val settings = settingsRepo.settingsFlow.first()

            if (!settings.voiceTriggerEnabled) {
                Log.w(TAG, "Voice trigger is globally disabled — stopping")
                handler.post { Toast.makeText(applicationContext, "🎤 Voice trigger is disabled", Toast.LENGTH_SHORT).show() }
                stopSelfCleanly()
                return@launch
            }

            // Build a flat map: trigger word (lowercase) → list of habit names
            val wordToHabits = mutableMapOf<String, MutableList<String>>()
            for (habitName in settings.voiceTriggerHabits) {
                val words = settings.voiceTriggerWords[habitName] ?: continue
                for (word in words) {
                    val lower = word.lowercase().trim()
                    if (lower.isNotEmpty()) {
                        wordToHabits.getOrPut(lower) { mutableListOf() }.add(habitName)
                    }
                }
            }

            if (wordToHabits.isEmpty()) {
                Log.w(TAG, "No trigger words configured — stopping")
                handler.post { Toast.makeText(applicationContext, "🎤 No trigger words configured", Toast.LENGTH_SHORT).show() }
                stopSelfCleanly()
                return@launch
            }

            Log.i(TAG, "Loaded ${wordToHabits.size} trigger words for ${settings.voiceTriggerHabits.size} habits")
            handler.post { Toast.makeText(applicationContext, "🎤 Listening...", Toast.LENGTH_SHORT).show() }
            startListening(wordToHabits, settings)
        }

        // Safety timeout — stop no matter what after LISTEN_TIMEOUT_MS
        handler.postDelayed({
            Log.i(TAG, "Listen timeout reached (${LISTEN_TIMEOUT_MS}ms) — stopping")
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

    private fun startListening(
        wordToHabits: Map<String, List<String>>,
        settings: com.example.tail.data.AppSettings
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.e(TAG, "Speech recognition not available on this device")
            handler.post { Toast.makeText(applicationContext, "🎤 Speech recognition not available", Toast.LENGTH_LONG).show() }
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
                handler.post { Toast.makeText(applicationContext, "🎤 Error: $errorName", Toast.LENGTH_SHORT).show() }
                stopSelfCleanly()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches.isNullOrEmpty()) {
                    Log.i(TAG, "No speech results — stopping")
                    stopSelfCleanly()
                    return
                }

                Log.i(TAG, "Speech results: $matches")
                handleSpeechResults(matches, wordToHabits, settings)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // Prefer offline recognition if available
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        recognizer.startListening(recognizerIntent)
    }

    // ── Trigger word matching + habit increment ──────────────────────────

    private fun handleSpeechResults(
        matches: List<String>,
        wordToHabits: Map<String, List<String>>,
        settings: com.example.tail.data.AppSettings
    ) {
        // Combine all recognized alternatives into one lowercase search string
        val spokenText = matches.joinToString(" ") { it.lowercase() }

        // Find all matching trigger words
        val matchedHabits = mutableSetOf<String>()
        for ((triggerWord, habitNames) in wordToHabits) {
            if (spokenText.contains(triggerWord)) {
                matchedHabits.addAll(habitNames)
                Log.i(TAG, "Trigger word '$triggerWord' matched → habits: $habitNames")
            }
        }

        if (matchedHabits.isEmpty()) {
            Log.i(TAG, "No trigger words matched in: \"$spokenText\"")
            handler.post { Toast.makeText(applicationContext, "🎤 No match: \"${matches.firstOrNull() ?: ""}\"", Toast.LENGTH_SHORT).show() }
            stopSelfCleanly()
            return
        }

        handler.post { Toast.makeText(applicationContext, "🎤 Matched: ${matchedHabits.joinToString(", ")}", Toast.LENGTH_SHORT).show() }

        // Increment all matched habits
        scope.launch(Dispatchers.IO) {
            try {
                val habitsRepo = HabitsRepository()
                val fileUriString = settings.fileUri
                if (fileUriString.isEmpty()) {
                    Log.w(TAG, "No habits file URI configured — cannot increment")
                    stopSelfCleanly()
                    return@launch
                }
                val uri = Uri.parse(fileUriString)
                val todayStr = LocalDate.now().toString()

                for (habitName in matchedHabits) {
                    // Respect "max 1" cap
                    if (habitName in settings.maxOneHabits) {
                        val db = habitsRepo.loadDatabase(uri, applicationContext)
                        val currentCount = db[habitName]?.get(todayStr) ?: 0
                        if (currentCount >= 1) {
                            Log.i(TAG, "Skipping '$habitName' — already at max 1")
                            continue
                        }
                    }

                    habitsRepo.incrementHabit(uri, applicationContext, habitName, 1)
                    Log.i(TAG, "Incremented habit '$habitName' via voice trigger")

                    // Record timestamp for voice-triggered increment
                    try {
                        val tsRepo = HabitTimestampRepository(applicationContext)
                        tsRepo.addTimestamp(habitName)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to record timestamp for '$habitName': ${e.message}")
                    }

                    // Conditional habit propagation
                    if (habitName in settings.conditionalHabits) {
                        val linked = settings.conditionalLinkedHabits[habitName] ?: emptySet()
                        for (linkedName in linked) {
                            if (linkedName in settings.maxOneHabits) {
                                val db = habitsRepo.loadDatabase(uri, applicationContext)
                                val cnt = db[linkedName]?.get(todayStr) ?: 0
                                if (cnt >= 1) {
                                    Log.i(TAG, "Skipping linked '$linkedName' — max 1")
                                    continue
                                }
                            }
                            habitsRepo.incrementHabit(uri, applicationContext, linkedName, 1)
                            Log.i(TAG, "Incremented linked '$linkedName' (conditional on '$habitName')")
                        }
                    }

                    // Broadcast habit-incremented event
                    try {
                        val broadcastIntent = Intent(ACTION_HABIT_INCREMENTED).apply {
                            putExtra(EXTRA_HABIT_NAME, habitName)
                        }
                        applicationContext.sendBroadcast(
                            broadcastIntent,
                            "com.example.tail.permission.TAIL_INTEGRATION"
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send habit-incremented broadcast: ${e.message}")
                    }
                }

                // Update Tasker file
                val taskerUri = settings.taskerFileUri
                if (taskerUri.isNotEmpty()) {
                    writeTaskerFile(applicationContext, habitsRepo, uri, taskerUri, settings.habitDividers)
                }

                // Confirmation vibration
                vibrateConfirmation()

                Log.i(TAG, "Voice trigger complete — incremented ${matchedHabits.size} habit(s)")
            } catch (e: Exception) {
                Log.e(TAG, "Error incrementing habits: ${e.message}", e)
            } finally {
                handler.post { stopSelfCleanly() }
            }
        }
    }

    // ── Tasker file (mirrors HabitIncrementReceiver logic) ───────────────

    private suspend fun writeTaskerFile(
        context: Context,
        habitsRepo: HabitsRepository,
        habitsUri: Uri,
        taskerUriString: String,
        dividers: Map<String, Int>
    ) {
        try {
            val db = habitsRepo.loadDatabase(habitsUri, context)
            val today = LocalDate.now()
            val todayStr = dateString(today)

            val todayCount = db.entries.sumOf { (habitName, entries) ->
                val raw = entries[todayStr] ?: 0
                applyDivider(raw, dividers[habitName] ?: 1)
            }

            fun avgOverDays(days: Int): Double {
                var total = 0
                for (i in 0 until days) {
                    val ds = dateString(today.minusDays(i.toLong()))
                    total += db.entries.sumOf { (habitName, entries) ->
                        val raw = entries[ds] ?: 0
                        applyDivider(raw, dividers[habitName] ?: 1)
                    }
                }
                return total.toDouble() / days
            }

            val avg7 = avgOverDays(7)
            val avg30 = avgOverDays(30)
            val content = "today=$todayCount\navg7=${"%.2f".format(avg7)}\navg30=${"%.2f".format(avg30)}\n"

            val taskerUri = Uri.parse(taskerUriString)
            context.contentResolver.openOutputStream(taskerUri, "wt")?.use { stream ->
                stream.bufferedWriter().use { it.write(content) }
            }
            Log.i(TAG, "Tasker file updated: today=$todayCount")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write Tasker file: ${e.message}")
        }
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
            vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
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
            "Voice Habit Trigger",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Tail is listening for voice trigger words"
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
            "tail:VoiceHabitWakeLock"
        ).apply {
            acquire(LISTEN_TIMEOUT_MS + 2_000) // slightly longer than listen timeout
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}
        wakeLock = null
    }
}
