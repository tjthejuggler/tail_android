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
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.Toast
import com.example.tail.TextTriggerActivity
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val TAG = "SmartVoiceService"
private const val CHANNEL_ID = "smart_voice_channel"
private const val NOTIFICATION_ID = 9003
private const val LISTEN_TIMEOUT_MS = 30_000L // 30s — could be habits or a note

/**
 * Foreground service that smartly routes voice input to either habit
 * incrementing or note saving based on trigger word density.
 *
 * **Routing logic:**
 *  - Split the spoken text into individual words.
 *  - Count how many words match a configured habit trigger word.
 *  - If **more than half** of the words are trigger words → habit mode
 *    (increment matched habits, same as [VoiceHabitService]).
 *  - If **half or fewer** words are trigger words → note mode
 *    (prepend text to notes file, same as [VoiceNoteService]).
 *
 * Two modes of operation:
 *  1. **Text supplied** — If the launching intent contains [Intent.EXTRA_TEXT],
 *     the text is processed directly without starting the SpeechRecognizer.
 *  2. **Voice listening** — If no text is supplied, the service uses Android's
 *     [SpeechRecognizer] to listen for up to [LISTEN_TIMEOUT_MS] ms.
 */
class SmartVoiceService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // ── Service lifecycle ────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireWakeLock()
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val suppliedText = TextTriggerActivity.extractText(intent)
        val notificationText = if (!suppliedText.isNullOrEmpty())
            "🧠 Processing: \"$suppliedText\""
        else
            "🧠 Listening…"
        val notification = buildNotification(notificationText)
        startForeground(NOTIFICATION_ID, notification)

        scope.launch {
            val settingsRepo = SettingsRepository(applicationContext)
            val settings = settingsRepo.settingsFlow.first()

            // Build trigger word map (needed for routing decision)
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

            if (!suppliedText.isNullOrEmpty()) {
                Log.i(TAG, "Processing supplied text: \"$suppliedText\"")
                handler.post { Toast.makeText(applicationContext, "🧠 Processing: \"$suppliedText\"", Toast.LENGTH_SHORT).show() }
                routeText(suppliedText, wordToHabits, settings)
            } else {
                Log.i(TAG, "Starting voice listening (smart mode)")
                handler.post { Toast.makeText(applicationContext, "🧠 Listening…", Toast.LENGTH_SHORT).show() }
                startListening(wordToHabits, settings)
            }
        }

        // Safety timeout
        handler.postDelayed({
            Log.i(TAG, "Listen timeout reached (${LISTEN_TIMEOUT_MS}ms) — stopping")
            handler.post { Toast.makeText(applicationContext, "🧠 Timeout — no speech detected", Toast.LENGTH_SHORT).show() }
            stopSelfCleanly()
        }, LISTEN_TIMEOUT_MS)

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        try { speechRecognizer?.destroy() } catch (_: Exception) {}
        speechRecognizer = null
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
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
            handler.post { Toast.makeText(applicationContext, "🧠 Speech recognition not available", Toast.LENGTH_LONG).show() }
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
                handler.post { Toast.makeText(applicationContext, "🧠 Error: $errorName", Toast.LENGTH_SHORT).show() }
                stopSelfCleanly()
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches.isNullOrEmpty()) {
                    Log.i(TAG, "No speech results — stopping")
                    handler.post { Toast.makeText(applicationContext, "🧠 No speech detected", Toast.LENGTH_SHORT).show() }
                    stopSelfCleanly()
                    return
                }

                Log.i(TAG, "Speech results: $matches")
                // Use the best match for routing
                routeText(matches.first(), wordToHabits, settings)
            }

            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

        recognizer.startListening(recognizerIntent)
    }

    // ── Smart routing ────────────────────────────────────────────────────

    /**
     * Decides whether [text] represents habits or a note, then delegates
     * to the appropriate handler.
     *
     * **Algorithm:**
     *  1. Split text into lowercase words.
     *  2. Count how many words match a trigger word in [wordToHabits].
     *  3. If matched / total > 0.5 → habit mode.
     *  4. Otherwise → note mode.
     */
    private fun routeText(
        text: String,
        wordToHabits: Map<String, List<String>>,
        settings: com.example.tail.data.AppSettings
    ) {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (words.isEmpty()) {
            Log.i(TAG, "Empty text — stopping")
            handler.post { Toast.makeText(applicationContext, "🧠 No words detected", Toast.LENGTH_SHORT).show() }
            stopSelfCleanly()
            return
        }

        // Count how many words match trigger words
        val matchedHabits = mutableSetOf<String>()
        val matchedTriggers = mutableSetOf<String>()
        var matchedWordCount = 0

        for (word in words) {
            val matchingTrigger = wordToHabits.keys.find { trigger -> word.contains(trigger) || trigger.contains(word) }
            if (matchingTrigger != null) {
                matchedHabits.addAll(wordToHabits[matchingTrigger]!!)
                matchedTriggers.add(matchingTrigger)
                matchedWordCount++
            }
        }

        val ratio = matchedWordCount.toDouble() / words.size
        val isHabitMode = ratio > 0.5

        Log.i(TAG, "Routing: $matchedWordCount/${words.size} words matched triggers (ratio=${"%.2f".format(ratio)}) → ${if (isHabitMode) "HABIT" else "NOTE"} mode")

        if (isHabitMode) {
            handleAsHabit(text, matchedHabits, matchedTriggers, settings)
        } else {
            handleAsNote(text, settings)
        }
    }

    // ── Habit mode (mirrors VoiceHabitService) ───────────────────────────

    private fun handleAsHabit(
        spokenText: String,
        matchedHabits: Set<String>,
        matchedTriggers: Set<String>,
        settings: com.example.tail.data.AppSettings
    ) {
        if (matchedHabits.isEmpty()) {
            Log.i(TAG, "Habit mode but no habits matched — stopping")
            handler.post { Toast.makeText(applicationContext, "🧠 No habits matched", Toast.LENGTH_SHORT).show() }
            stopSelfCleanly()
            return
        }

        val triggerWordsStr = matchedTriggers.joinToString(", ")
        val habitsStr = matchedHabits.joinToString(", ")
        handler.post { Toast.makeText(applicationContext, "🧠→🎤 Habits: \"$triggerWordsStr\" → $habitsStr", Toast.LENGTH_SHORT).show() }

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
                    if (habitName in settings.maxOneHabits) {
                        val db = habitsRepo.loadDatabase(uri, applicationContext)
                        val currentCount = db[habitName]?.get(todayStr) ?: 0
                        if (currentCount >= 1) {
                            Log.i(TAG, "Skipping '$habitName' — already at max 1")
                            continue
                        }
                    }

                    habitsRepo.incrementHabit(uri, applicationContext, habitName, 1)
                    Log.i(TAG, "Incremented habit '$habitName' via smart voice")

                    // Record timestamp
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

                // Confirmation vibration (single pulse — habit style)
                vibrateConfirmation()

                // TTS confirmation
                val ttsText = matchedTriggers.joinToString(", ")
                Log.i(TAG, "Smart voice (habit mode) complete — incremented ${matchedHabits.size} habit(s)")
                speakAndThenStop(ttsText)
            } catch (e: Exception) {
                Log.e(TAG, "Error incrementing habits: ${e.message}", e)
                handler.post { stopSelfCleanly() }
            }
        }
    }

    // ── Note mode (mirrors VoiceNoteService) ─────────────────────────────

    private fun handleAsNote(text: String, settings: com.example.tail.data.AppSettings) {
        if (settings.voiceNoteFileUri.isEmpty()) {
            Log.w(TAG, "No notes file configured — cannot save note")
            handler.post { Toast.makeText(applicationContext, "🧠 No notes file selected", Toast.LENGTH_SHORT).show() }
            stopSelfCleanly()
            return
        }

        handler.post { Toast.makeText(applicationContext, "🧠→📝 Saving as note…", Toast.LENGTH_SHORT).show() }

        scope.launch(Dispatchers.IO) {
            try {
                val uri = Uri.parse(settings.voiceNoteFileUri)
                val now = LocalDateTime.now()
                val timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val newEntry = "## $timestamp\n$text\n\n"

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
                    Toast.makeText(applicationContext, "🧠→📝 Note saved: \"$preview\"", Toast.LENGTH_SHORT).show()
                }

                // Show notification with full note text
                showNoteSavedNotification(text, timestamp)

                // Double-pulse vibration — note style
                vibrateNoteConfirmation()

                // No TTS for notes — just stop
                handler.post { stopSelfCleanly() }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write note: ${e.message}", e)
                handler.post { Toast.makeText(applicationContext, "🧠 Error saving note: ${e.message}", Toast.LENGTH_LONG).show() }
                handler.post { stopSelfCleanly() }
            }
        }
    }

    // ── Note saved notification ──────────────────────────────────────────

    private fun showNoteSavedNotification(noteText: String, timestamp: String) {
        val noteChannelId = "smart_voice_note_saved_channel"
        val channel = NotificationChannel(
            noteChannelId,
            "Smart Voice Note Saved",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Shows the full text of notes saved via smart voice"
            enableVibration(false)
        }
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.createNotificationChannel(channel)

        val notification = Notification.Builder(this, noteChannelId)
            .setContentTitle("🧠→📝 Note saved — $timestamp")
            .setContentText(noteText)
            .setStyle(Notification.BigTextStyle().bigText(noteText))
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setAutoCancel(true)
            .build()

        mgr.notify(NOTIFICATION_ID + 1000, notification)
        Log.d(TAG, "Note saved notification shown")
    }

    // ── Tasker file (mirrors VoiceHabitService) ─────────────────────────

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

    // ── TTS ──────────────────────────────────────────────────────────────

    private fun initTts() {
        tts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.US)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "TTS language not supported")
                } else {
                    ttsReady = true
                    Log.d(TAG, "TTS initialized")
                }
            } else {
                Log.w(TAG, "TTS initialization failed: $status")
            }
        }
    }

    private fun speakAndThenStop(text: String) {
        if (ttsReady && tts != null) {
            val utteranceId = "tts_confirm_${System.currentTimeMillis()}"
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "TTS started speaking")
                }
                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "TTS finished speaking — stopping service")
                    handler.post { stopSelfCleanly() }
                }
                override fun onError(utteranceId: String?) {
                    Log.w(TAG, "TTS error — stopping service")
                    handler.post { stopSelfCleanly() }
                }
            })
            tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            Log.d(TAG, "TTS speaking: \"$text\"")
        } else {
            Log.w(TAG, "TTS not ready — stopping with delay")
            handler.postDelayed({ stopSelfCleanly() }, 500)
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

    /** Single pulse — habit confirmation style */
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

    /** Double pulse — note confirmation style */
    private fun vibrateNoteConfirmation() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
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
            "Smart Voice",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while Tail is listening for smart voice input"
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
            "tail:SmartVoiceWakeLock"
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
