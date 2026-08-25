package com.example.tail

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.SpotifyDetector
import com.example.tail.data.meal.Macronutrients
import com.example.tail.data.meal.MealLog
import com.example.tail.data.meal.MealLogRepository
import com.example.tail.data.meal.QcDiag
import com.example.tail.data.meal.VisionClassification
import com.example.tail.data.meal.VisionConfig
import com.example.tail.data.meal.VisionHabitExecutor
import com.example.tail.data.meal.VisionMemoryRepository
import com.example.tail.data.meal.VisionProcessingService
import com.example.tail.data.meal.VisionProcessingWorker
import com.example.tail.data.meal.VisionQueueRepository
import com.example.tail.data.meal.VisionResult
import com.example.tail.ipc.SmartVoiceService
import com.example.tail.ui.HabitIncrementBus
import com.example.tail.ui.VoiceNoteBus
import com.example.tail.ui.VoiceTranscriptBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "MediaCapture"
private const val AUTO_FINISH_TIMEOUT_MS = 35_000L
/** Extra auto-finish window while the user speaks a tandem teaching instruction. */
private const val TANDEM_SPEAK_WINDOW_MS = 30_000L

/**
 * UI state for [MediaCaptureActivity].
 */
sealed class CaptureState {
    /**
     * Camera preview is shown full-screen while voice listens in the
     * background. The user can talk (voice handles it), tap the capture
     * button to take a photo, or **hold** the capture button for tandem
     * mode (photo + spoken teaching instruction).
     */
    object CameraWithVoice : CaptureState()
    /**
     * Tandem mode: the photo was taken on long-press and we're waiting for
     * the spoken instruction that teaches the LLM what the photo means.
     */
    data class AwaitingVoice(val imagePath: String) : CaptureState()
    /** Image captured, LLM processing in progress. */
    object Processing : CaptureState()
    /** LLM result ready for the user to read and dismiss. */
    data class Result(
        val displayText: String,
        val isSuccess: Boolean,
        val title: String? = null
    ) : CaptureState()
    /**
     * A food photo was logged automatically with the LLM's best-guess
     * specifics — editable review screen so the user can fine-tune the
     * numbers before finishing (the habit was already incremented).
     */
    data class MealEdit(
        val log: MealLog,
        val note: String? = null
    ) : CaptureState()
    /**
     * The photo couldn't be acted on with certainty. NEVER a dead end:
     * the user tells the app what the photo means (text and/or a voice
     * follow-up plus the habit selector); the answer is persisted to the
     * LLM's vision memory and the habit is incremented right away.
     */
    data class Correcting(
        val imagePath: String,
        /** What the LLM said it saw (shown for context). */
        val llmDescription: String,
        /** Editable description that will go into memory. */
        val description: String,
        val selectedHabit: String? = null,
        val selectedSubtype: String? = null,
        val amount: Int = 1,
        val listening: Boolean = false,
        val voiceError: String? = null
    ) : CaptureState()
}

/**
 * Unified launcher activity that runs **voice and camera simultaneously**.
 *
 * When launched:
 * 1. [SmartVoiceService] starts **immediately** — the user feels a vibration
 *    and can start talking right away (no button press required).
 * 2. The **camera preview fills the screen** at the same time, with a single
 *    capture button at the bottom.
 *
 * The user does whichever they want:
 *  - **Talk** → the voice service processes it (habit increment or note) and
 *    shows its own confirmation overlay. This activity auto-finishes.
 *  - **Tap capture** → the voice service is stopped, the photo is processed
 *    inline through the vision pipeline (same code path as the Settings test
 *    button), and the result is shown for the user to read and dismiss.
 *    If the photo clearly matches a learned association (or an obvious habit
 *    item like a labeled pill bottle), the proposed habit is incremented —
 *    but only when the LLM is very certain; otherwise nothing happens.
 *  - **Hold capture** → tandem teaching mode: the photo is taken while voice
 *    keeps listening. The next spoken utterance is paired with the photo and
 *    both are sent to the LLM together, permanently updating its vision
 *    memory (editable in Settings → Vision Memory). The habit is also
 *    incremented immediately.
 *
 * Works on the lock screen (`showWhenLocked` + `turnScreenOn`).
 *
 * ── FIRE-AND-FORGET TAP CAPTURE ────────────────────────────────────────
 * TAPPING the capture button (or tapping a gallery photo) NEVER blocks:
 * the image is enqueued for the background VisionProcessingWorker and the
 * activity finishes immediately — no Processing screen, no result overlay,
 * no approval. Failures land in the Quick Capture History with a
 * notification on next app open. HOLDING the button keeps the interactive
 * tandem-teaching flow (photo + spoken instruction), and voice input
 * (habit increments / notes) works exactly as before.
 *
 * Pass [EXTRA_DIRECT_CAMERA] = true to skip voice and open camera directly
 * (useful for Tasker / Samsung Routines automation).
 */
class MediaCaptureActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HABIT_NAME = "extra_habit_name"
        const val EXTRA_DIRECT_CAMERA = "extra_direct_camera"
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    internal var imageCapture: ImageCapture? = null
    private var hasCameraPermission = false
    private var targetHabit: String? = null
    private var voiceServiceStarted = false
    private var captureInProgress = false

    /**
     * True while a tandem (hold-to-capture) flow is in progress. Guards the
     * HabitIncrementBus collector: increments triggered by our own teaching
     * flow must not auto-finish the activity before the result is shown.
     */
    private var tandemInProgress by mutableStateOf(false)

    private var captureState by mutableStateOf<CaptureState>(CaptureState.CameraWithVoice)
    private val handler = Handler(Looper.getMainLooper())

    /** Auto-finish after the voice service timeout (no capture, no speech). */
    private val autoFinishTimeout = Runnable {
        Log.d(TAG, "Auto-finish timeout — stopping voice and finishing")
        VoiceTranscriptBus.disarmTandem()
        stopVoiceService()
        finish()
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasCameraPermission = true
            // Recompose to show camera
            captureState = CaptureState.CameraWithVoice
        } else {
            // No camera permission — voice still works, but no camera preview.
            // Keep the activity alive for voice only.
            Toast.makeText(this, "Camera permission denied — voice only", Toast.LENGTH_SHORT).show()
        }
    }

    /** Gallery pick mode: true = teach with a voice note after picking. */
    private var galleryTeachMode = false

    /** Habit list + subtypes available in the correction screen. */
    private var correctableHabits: List<String> = emptyList()
    private var correctableSubtypes: Map<String, List<String>> = emptyMap()

    /**
     * Gallery import (photo picker). Tap on the gallery button = analyse a
     * picked photo like a plain capture; hold = tandem teaching with a
     * spoken instruction, exactly like holding the shutter button.
     */
    private val galleryPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            // Picker cancelled — resume the auto-finish countdown
            handler.postDelayed(autoFinishTimeout, AUTO_FINISH_TIMEOUT_MS)
            return@registerForActivityResult
        }
        handler.removeCallbacks(autoFinishTimeout)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Could not read the selected image")
                val relativePath =
                    MealLogRepository(this@MediaCaptureActivity).saveImageBytes(bytes)
                val teach = galleryTeachMode
                runOnUiThread {
                    if (teach) {
                        // Same as hold-to-capture, but with the gallery photo
                        tandemInProgress = true
                        handler.postDelayed(
                            autoFinishTimeout,
                            AUTO_FINISH_TIMEOUT_MS + TANDEM_SPEAK_WINDOW_MS
                        )
                        if (!voiceServiceStarted) startVoiceMode()
                        VoiceTranscriptBus.armTandem()
                        vibrateTandemReady()
                        captureState = CaptureState.AwaitingVoice(relativePath)
                    } else {
                        // TAP on a gallery photo = fire-and-forget analyse,
                        // identical to a tap-capture: queue it and close.
                        stopVoiceService()
                        enqueueFireAndForget(relativePath)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import gallery image", e)
                runOnUiThread {
                    captureState = CaptureState.Result(
                        "❌ Gallery import failed: ${e.message?.take(200)}",
                        isSuccess = false
                    )
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        targetHabit = intent.getStringExtra(EXTRA_HABIT_NAME)
        val directCamera = intent.getBooleanExtra(EXTRA_DIRECT_CAMERA, false)
        QcDiag.log(
            "CAPTURE",
            "MediaCaptureActivity onCreate (QUICK CAPTURE entry): action=${intent.action} " +
                "targetHabit=${targetHabit ?: "NULL (no extra — auto-target will be used)"} " +
                "directCamera=$directCamera"
        )
        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        if (directCamera) {
            // Skip voice — camera only (for Tasker / automation)
            captureState = CaptureState.CameraWithVoice
        } else {
            // Start voice IMMEDIATELY — no button press required
            startVoiceMode()
        }

        // Listen for habit increments from the voice service → auto-finish.
        // Ignored while a tandem flow is in progress (our own executor emits
        // there too, and the result screen must be allowed to appear).
        // NOTE: the voice service is deliberately NOT stopped here — it still
        // has to speak its TTS confirmation and stops itself afterwards
        // (stopping it here was cutting off the spoken readback).
        lifecycleScope.launch {
            HabitIncrementBus.events.collect { habitName ->
                if (tandemInProgress || captureState !is CaptureState.CameraWithVoice) {
                    Log.d(TAG, "Habit incremented '$habitName' outside camera idle — staying for result")
                    return@collect
                }
                Log.d(TAG, "Habit incremented via voice: $habitName — finishing")
                handler.removeCallbacks(autoFinishTimeout)
                finish()
            }
        }

        // A spoken note was saved by the voice service → the voice input is
        // complete, so this screen has served its purpose and closes. Same as
        // above: the service finishes its own confirmation (overlay, then
        // self-stop) independently of this activity's lifecycle.
        lifecycleScope.launch {
            VoiceNoteBus.events.collect { noteText ->
                if (tandemInProgress || captureState !is CaptureState.CameraWithVoice) {
                    return@collect
                }
                Log.d(TAG, "Note saved via voice: \"${noteText.take(60)}\" — finishing")
                handler.removeCallbacks(autoFinishTimeout)
                finish()
            }
        }

        // Tandem mode: receive the spoken teaching instruction paired with
        // the photo taken on long-press (or the gallery hold). In the
        // correction screen the transcript is appended to the description.
        lifecycleScope.launch {
            VoiceTranscriptBus.transcripts.collect { event ->
                when (val st = captureState) {
                    is CaptureState.AwaitingVoice -> {
                        Log.d(TAG, "Tandem transcript received: \"${event.transcript}\"")
                        VoiceTranscriptBus.disarmTandem()
                        stopVoiceService()
                        captureState = CaptureState.Processing
                        val mealLogRepo = MealLogRepository(this@MediaCaptureActivity)
                        lifecycleScope.launch(Dispatchers.IO) {
                            processTeachingInline(st.imagePath, event.transcript, mealLogRepo)
                        }
                    }
                    is CaptureState.Correcting -> {
                        Log.d(TAG, "Correction voice follow-up: \"${event.transcript}\"")
                        VoiceTranscriptBus.disarmTandem()
                        stopVoiceService()
                        captureState = st.copy(
                            description = (st.description + " " + event.transcript).trim(),
                            listening = false,
                            voiceError = null
                        )
                    }
                    else -> Unit
                }
            }
        }

        // Tandem mode: recognition failed / no speech — fall back to plain
        // photo analysis (smart detection) with a notice. In the correction
        // screen a voice failure just stops the mic and reports the reason.
        lifecycleScope.launch {
            VoiceTranscriptBus.errors.collect { event ->
                when (val st = captureState) {
                    is CaptureState.AwaitingVoice -> {
                        Log.d(TAG, "Tandem voice error: ${event.reason}")
                        VoiceTranscriptBus.disarmTandem()
                        stopVoiceService()
                        captureState = CaptureState.Processing
                        val mealLogRepo = MealLogRepository(this@MediaCaptureActivity)
                        val queueRepo = VisionQueueRepository(this@MediaCaptureActivity)
                        lifecycleScope.launch(Dispatchers.IO) {
                            processCaptureInline(
                                st.imagePath,
                                targetHabit,
                                mealLogRepo,
                                queueRepo,
                                voiceErrorNote = "🎤 No speech heard (${event.reason}) — photo analysed without teaching."
                            )
                        }
                    }
                    is CaptureState.Correcting -> {
                        VoiceTranscriptBus.disarmTandem()
                        stopVoiceService()
                        captureState = st.copy(listening = false, voiceError = event.reason)
                    }
                    else -> Unit
                }
            }
        }

        // Auto-finish after timeout (voice service timeout is 30s)
        handler.postDelayed(autoFinishTimeout, AUTO_FINISH_TIMEOUT_MS)

        setContent {
            MaterialTheme {
                when (val state = captureState) {
                    is CaptureState.CameraWithVoice -> CameraWithVoiceScreen(
                        targetHabit = targetHabit,
                        voiceActive = !directCamera && voiceServiceStarted,
                        onCapture = { capturePhoto(targetHabit) },
                        onHoldCapture = { startTandemCapture(targetHabit) },
                        onPickGallery = { launchGalleryPicker(teach = false) },
                        onHoldPickGallery = { launchGalleryPicker(teach = true) },
                        onCancel = {
                            stopVoiceService()
                            finish()
                        }
                    )
                    is CaptureState.AwaitingVoice -> AwaitingVoiceScreen(
                        onCancel = {
                            VoiceTranscriptBus.disarmTandem()
                            tandemInProgress = false
                            stopVoiceService()
                            // Remove the orphaned teaching photo
                            MealLogRepository(this).deleteImage(state.imagePath)
                            finish()
                        },
                        onAnalyseWithoutVoice = {
                            VoiceTranscriptBus.disarmTandem()
                            stopVoiceService()
                            captureState = CaptureState.Processing
                            val mealLogRepo = MealLogRepository(this)
                            val queueRepo = VisionQueueRepository(this)
                            lifecycleScope.launch(Dispatchers.IO) {
                                processCaptureInline(
                                    state.imagePath, targetHabit, mealLogRepo, queueRepo,
                                    voiceErrorNote = "🎤 Analysed without a teaching instruction."
                                )
                            }
                        }
                    )
                    is CaptureState.Processing -> ProcessingScreen(onCancel = { finish() })
                    is CaptureState.Result -> ResultScreen(
                        displayText = state.displayText,
                        isSuccess = state.isSuccess,
                        title = state.title,
                        onDismiss = { finish() }
                    )
                    is CaptureState.MealEdit -> MealEditScreen(
                        log = state.log,
                        note = state.note,
                        onSave = { updated -> saveMealEdit(updated, state.log.timestamp) },
                        onDiscard = { discardMealEdit(state.log) }
                    )
                    is CaptureState.Correcting -> CorrectionScreen(
                        state = state,
                        habits = correctableHabits,
                        subtypes = correctableSubtypes,
                        onUpdate = { captureState = it },
                        onMicToggle = { start -> if (start) startCorrectionListening() else stopCorrectionListening() },
                        onSave = { saveCorrection() },
                        onDiscard = { discardCorrection(state) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoFinishTimeout)
        VoiceTranscriptBus.disarmTandem()
        cameraExecutor.shutdown()
    }

    // ── Voice mode ──────────────────────────────────────────────────────

    /**
     * Starts [SmartVoiceService] for voice listening **immediately** (smart
     * routing to habits/notes) — the service handles vibration,
     * SpeechRecognizer, TTS, and confirmation overlays.
     */
    private fun startVoiceMode() {
        val spotifyTrack = SpotifyDetector.getCurrentSpotifyTrack(applicationContext)

        val serviceIntent = Intent(this, SmartVoiceService::class.java)
        if (spotifyTrack != null) {
            SpotifyDetector.putSpotifyTrack(serviceIntent, spotifyTrack)
        }
        ContextCompat.startForegroundService(this, serviceIntent)
        voiceServiceStarted = true
    }

    private fun stopVoiceService() {
        if (voiceServiceStarted) {
            try {
                stopService(Intent(this, SmartVoiceService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Could not stop SmartVoiceService", e)
            }
            voiceServiceStarted = false
        }
    }

    // ── Camera mode (tap) ───────────────────────────────────────────────

    /**
     * TAP capture — FIRE-AND-FORGET. Saves the photo, enqueues it for the
     * background [VisionProcessingWorker] (deterministic target: explicit
     * extra wins, else the single camera-eligible habit; attaches to the
     * active meal group so close-succession captures merge into one meal)
     * and finishes IMMEDIATELY. No Processing screen, no result overlay,
     * no approval — anything the AI can't act on lands in the Quick
     * Capture History. HOLD the button for tandem teaching instead.
     */
    private fun capturePhoto(targetHabit: String?) {
        if (captureInProgress) return
        captureInProgress = true
        val capTs = System.currentTimeMillis()
        QcDiag.log(
            "CAPTURE",
            "MediaCaptureActivity shutter tapped (QUICK CAPTURE): capTs=$capTs " +
                "targetHabit=${targetHabit ?: "NULL"} imageCaptureReady=${imageCapture != null}"
        )

        val capture = imageCapture ?: run {
            QcDiag.error("CAPTURE", "capTs=$capTs MediaCaptureActivity: camera not ready — aborting")
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            captureInProgress = false
            return
        }

        // Stop voice service — we're switching to camera mode
        handler.removeCallbacks(autoFinishTimeout)
        stopVoiceService()

        val mealLogRepo = MealLogRepository(this)
        val queueRepo = VisionQueueRepository(this)

        val tempFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val bytes = tempFile.readBytes()
                        tempFile.delete()
                        val relativePath = mealLogRepo.saveImageBytes(bytes)
                        QcDiag.log(
                            "SAVE",
                            "capTs=$capTs photo saved: ${bytes.size} bytes → $relativePath"
                        )

                        // Resolve the deterministic target here on the
                        // camera executor thread (blocking is fine):
                        // explicit extra wins; otherwise exactly-one
                        // camera-eligible habit removes all ambiguity.
                        //
                        // QC_DIAG/RESOLVE logs EVERY outcome — including the
                        // failures that were previously swallowed silently by
                        // runCatching{}.getOrNull().
                        var resolvedHabit: String? = targetHabit
                        if (resolvedHabit != null) {
                            QcDiag.log(
                                "RESOLVE",
                                "capTs=$capTs explicit target from EXTRA_HABIT_NAME=" +
                                    "'$resolvedHabit' (deterministic, no guessing)"
                            )
                        } else {
                            try {
                                runBlocking {
                                    val settings = SettingsRepository(this@MediaCaptureActivity)
                                        .settingsFlow.first()
                                    val eligible =
                                        VisionHabitExecutor.cameraEligibleHabits(settings)
                                    QcDiag.log(
                                        "RESOLVE",
                                        "capTs=$capTs auto-target resolution: " +
                                            "${QcDiag.routingSnapshot(settings)}"
                                    )
                                    QcDiag.log(
                                        "RESOLVE",
                                        "capTs=$capTs ${QcDiag.mismatchHints(settings)}"
                                    )
                                    resolvedHabit = eligible.singleOrNull()
                                    if (resolvedHabit != null) {
                                        QcDiag.log(
                                            "RESOLVE",
                                            "capTs=$capTs auto-target RESOLVED → " +
                                                "'$resolvedHabit' (deterministic meal " +
                                                "pipeline expected)"
                                        )
                                    } else {
                                        QcDiag.warn(
                                            "RESOLVE",
                                            "capTs=$capTs auto-target FAILED " +
                                                "(singleOrNull=null) → item will be enqueued " +
                                                "with habitId=NULL and fall to LLM classification"
                                        )
                                    }
                                }
                            } catch (e: Throwable) {
                                QcDiag.error(
                                    "RESOLVE",
                                    "capTs=$capTs auto-target resolution THREW (previously " +
                                        "swallowed silently by runCatching): " +
                                        "${e.javaClass.simpleName}: ${e.message}",
                                    e
                                )
                                resolvedHabit = null
                            }
                        }

                        val now = System.currentTimeMillis()
                        val activeGroup = resolvedHabit?.let {
                            mealLogRepo.findActiveGroup(it, now)
                        }
                        QcDiag.log(
                            "GROUP",
                            "capTs=$capTs attach-group: " +
                                (activeGroup?.let {
                                    "will attach to ${QcDiag.short(it.id)} " +
                                        "(anchorDeltaMs=${now - it.anchorTime()})"
                                } ?: "no active group — new meal expected")
                        )
                        val item = queueRepo.enqueue(
                            imagePath = relativePath,
                            habitId = resolvedHabit,
                            attachToMealLogId = activeGroup?.id
                        )
                        QcDiag.log(
                            "ENQUEUE",
                            "capTs=$capTs item=${QcDiag.short(item.id)} " +
                                "habitId=${item.habitId ?: "NULL"} " +
                                "attachToMealLogId=${QcDiag.short(item.attachToMealLogId)} " +
                                "imagePath=${item.imagePath}"
                        )
                        VisionProcessingWorker.enqueue(this@MediaCaptureActivity)
                        QcDiag.log(
                            "WORKER",
                            "capTs=$capTs VisionProcessingWorker enqueued after " +
                                "item=${QcDiag.short(item.id)} — capture flow complete"
                        )

                        runOnUiThread {
                            Toast.makeText(
                                this@MediaCaptureActivity,
                                "📸 Captured & Queued",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    } catch (e: Exception) {
                        QcDiag.error(
                            "CAPTURE",
                            "capTs=$capTs MediaCaptureActivity: save/enqueue FAILED: ${e.message}",
                            e
                        )
                        Log.e(TAG, "Failed to save captured image", e)
                        runOnUiThread {
                            Toast.makeText(
                                this@MediaCaptureActivity,
                                "Capture failed: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    QcDiag.error(
                        "CAPTURE",
                        "capTs=$capTs MediaCaptureActivity: CameraX error: ${exception.message}",
                        exception
                    )
                    Log.e(TAG, "Camera capture error", exception)
                    runOnUiThread {
                        Toast.makeText(
                            this@MediaCaptureActivity,
                            "Capture error: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            }
        )
    }

    // ── Camera mode (hold → tandem teaching) ────────────────────────────

    /**
     * Long-press on the capture button: takes the photo **without stopping
     * voice**, arms tandem mode so the next spoken utterance is delivered
     * here instead of being routed, and waits for the teaching instruction.
     */
    private fun startTandemCapture(targetHabit: String?) {
        if (captureInProgress) return
        captureInProgress = true
        tandemInProgress = true

        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            captureInProgress = false
            tandemInProgress = false
            return
        }

        // Extend the auto-finish window so the user has time to speak
        handler.removeCallbacks(autoFinishTimeout)
        handler.postDelayed(autoFinishTimeout, AUTO_FINISH_TIMEOUT_MS + TANDEM_SPEAK_WINDOW_MS)

        // Make sure a recognizer is listening (direct-camera mode starts none)
        if (!voiceServiceStarted) {
            startVoiceMode()
        }

        // Arm tandem BEFORE taking the photo so any speech result arriving
        // during the capture is delivered to us rather than routed.
        VoiceTranscriptBus.armTandem()
        vibrateTandemReady()

        val mealLogRepo = MealLogRepository(this)
        val tempFile = File(cacheDir, "tandem_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val bytes = tempFile.readBytes()
                        tempFile.delete()
                        val relativePath = mealLogRepo.saveImageBytes(bytes)
                        Log.i(TAG, "Tandem photo saved: $relativePath — waiting for speech")

                        runOnUiThread {
                            captureInProgress = false
                            captureState = CaptureState.AwaitingVoice(relativePath)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save tandem image", e)
                        runOnUiThread {
                            tandemInProgress = false
                            VoiceTranscriptBus.disarmTandem()
                            captureState = CaptureState.Result(
                                "❌ Capture failed: ${e.message?.take(200)}",
                                isSuccess = false
                            )
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Tandem camera capture error", exception)
                    runOnUiThread {
                        tandemInProgress = false
                        VoiceTranscriptBus.disarmTandem()
                        captureState = CaptureState.Result(
                            "❌ Capture error: ${exception.message?.take(200)}",
                            isSuccess = false
                        )
                    }
                }
            }
        )
    }

    /**
     * Tandem teaching: sends the photo and the spoken instruction to the LLM
     * **together**, persists the learned association in the vision memory,
     * and increments the habit right away.
     */
    private suspend fun processTeachingInline(
        relativePath: String,
        transcript: String,
        mealLogRepo: MealLogRepository
    ) {
        val settingsRepo = SettingsRepository(this@MediaCaptureActivity)
        val settings = try {
            settingsRepo.settingsFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
            tandemInProgress = false
            showResult("❌ Failed to load settings: ${e.message?.take(200)}", false)
            return
        }

        if (!settings.mealEnabled || settings.mealApiKey.isBlank() ||
            settings.mealBaseUrl.isBlank() || settings.mealModel.isBlank()
        ) {
            Log.w(TAG, "Meal engine not configured — cannot teach")
            val queueRepo = VisionQueueRepository(this@MediaCaptureActivity)
            queueRepo.enqueue(relativePath, targetHabit)
            VisionProcessingWorker.enqueue(this@MediaCaptureActivity)
            tandemInProgress = false
            showResult(
                "📋 Meal engine not configured — teaching needs the LLM endpoint.\n" +
                "Photo queued for background processing.\n" +
                "Configure the LLM endpoint in Settings → Meal Engine.",
                isSuccess = false
            )
            return
        }

        val habitPrompt = VisionHabitExecutor.buildHabitPrompt(settings)
        if (habitPrompt.isBlank()) {
            tandemInProgress = false
            showResult("❌ No habits configured — nothing to teach.", isSuccess = false)
            return
        }

        val config = VisionConfig(
            baseUrl = settings.mealBaseUrl,
            apiKey = settings.mealApiKey,
            model = settings.mealModel,
            userSystemPrompt = settings.mealSystemPrompt
        )

        val imageFile = File(filesDir, relativePath)
        val service = VisionProcessingService()
        val teaching = service.processTeaching(imageFile, transcript, config, habitPrompt)

        tandemInProgress = false

        if (teaching == null) {
            showResult(
                "❌ Teaching request failed — check your URL, key, and model.\n" +
                "See logcat (tag: VisionProcessing) for details.",
                isSuccess = false
            )
            return
        }

        if (!teaching.understood || teaching.habitName == null) {
            showResult(
                buildString {
                    append("🤔 Couldn't learn from that instruction.\n\n")
                    append("You said: \"$transcript\"\n\n")
                    if (teaching.notes.isNotBlank()) append(teaching.notes.take(300))
                },
                isSuccess = false
            )
            return
        }

        // Validate the LLM's proposal against the real habit configuration
        val resolved = VisionHabitExecutor.resolveHabitAction(
            settings, teaching.habitName, teaching.subtypeName
        )
        if (resolved == null) {
            showResult(
                "❌ The LLM proposed habit \"${teaching.habitName}\", which doesn't match " +
                "any configured habit.\n\nYou said: \"$transcript\"",
                isSuccess = false
            )
            return
        }
        val (realHabit, realSubtype) = resolved

        // Persist the learned association — this is the LLM's "memory"
        val memoryRepo = VisionMemoryRepository(this@MediaCaptureActivity)
        val entry = VisionMemoryRepository.newEntry(
            timestamp = System.currentTimeMillis(),
            voiceNote = transcript,
            visualDescription = teaching.visualDescription.ifBlank { "photo associated with $realHabit" },
            habitName = realHabit,
            subtypeName = realSubtype,
            incrementAmount = teaching.amount
        )
        memoryRepo.addEntry(entry)

        // The user is doing the activity right now — increment immediately
        val incError = VisionHabitExecutor.execute(
            this@MediaCaptureActivity, settings, realHabit, realSubtype, teaching.amount
        )

        val display = buildString {
            append("🧠 Learned! ${teaching.summary.ifBlank { "Photos like this → $realHabit" }}\n\n")
            append("Description: ${entry.visualDescription}\n\n")
            append("You said: \"$transcript\"\n\n")
            append(
                "→ $realHabit" + (realSubtype?.let { " / $it" } ?: "") +
                " ×${teaching.amount}\n\n"
            )
            if (incError == null) {
                append("✅ Incremented $realHabit now, too.")
            } else {
                append("⚠️ Saved to memory, but the increment failed: $incError")
            }
            append("\n\nManage memories in Settings → Vision Memory.")
        }
        showResult(display, incError == null, title = "Learned")
    }

    // ── Fire-and-forget queueing ────────────────────────────────────────

    /**
     * FIRE-AND-FORGET: queue [relativePath] for the background
     * [VisionProcessingWorker] and finish. Deterministic target resolution
     * (explicit habit extra wins, else the single camera-eligible habit)
     * and active-meal-group attachment happen here, so the worker runs the
     * plain meal pipeline with no habit guessing. Anything the AI can't
     * act on lands in the Quick Capture History (notification on next app
     * open) — this screen never blocks on a result.
     */
    private fun enqueueFireAndForget(relativePath: String) {
        val capTs = System.currentTimeMillis()
        QcDiag.log(
            "CAPTURE",
            "enqueueFireAndForget (gallery tap path): capTs=$capTs imagePath=$relativePath"
        )
        handler.removeCallbacks(autoFinishTimeout)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settings = runCatching {
                    SettingsRepository(this@MediaCaptureActivity).settingsFlow.first()
                }.getOrNull()
                if (settings == null) {
                    QcDiag.error(
                        "RESOLVE",
                        "capTs=$capTs settings load FAILED in enqueueFireAndForget " +
                            "(was silently null)"
                    )
                } else {
                    QcDiag.log(
                        "RESOLVE",
                        "capTs=$capTs gallery auto-target: ${QcDiag.routingSnapshot(settings)}"
                    )
                    QcDiag.log("RESOLVE", "capTs=$capTs ${QcDiag.mismatchHints(settings)}")
                }
                val resolvedHabit = targetHabit
                    ?: settings?.let { VisionHabitExecutor.cameraEligibleHabits(it).singleOrNull() }
                QcDiag.log(
                    "RESOLVE",
                    "capTs=$capTs resolvedHabit=${resolvedHabit ?: "NULL → LLM classification path"}"
                )
                val mealLogRepo = MealLogRepository(this@MediaCaptureActivity)
                val now = System.currentTimeMillis()
                val activeGroup = resolvedHabit?.let {
                    mealLogRepo.findActiveGroup(it, now)
                }
                QcDiag.log(
                    "GROUP",
                    "capTs=$capTs attach-group: " +
                        (activeGroup?.let {
                            "will attach to ${QcDiag.short(it.id)} " +
                                "(anchorDeltaMs=${now - it.anchorTime()})"
                        } ?: "no active group — new meal expected")
                )
                val item = VisionQueueRepository(this@MediaCaptureActivity).enqueue(
                    imagePath = relativePath,
                    habitId = resolvedHabit,
                    attachToMealLogId = activeGroup?.id
                )
                QcDiag.log(
                    "ENQUEUE",
                    "capTs=$capTs item=${QcDiag.short(item.id)} " +
                        "habitId=${item.habitId ?: "NULL"} " +
                        "attachToMealLogId=${QcDiag.short(item.attachToMealLogId)}"
                )
                VisionProcessingWorker.enqueue(this@MediaCaptureActivity)
                QcDiag.log(
                    "WORKER",
                    "capTs=$capTs VisionProcessingWorker enqueued after " +
                        "item=${QcDiag.short(item.id)}"
                )
            } catch (e: Exception) {
                QcDiag.error(
                    "CAPTURE",
                    "capTs=$capTs enqueueFireAndForget FAILED: ${e.message}",
                    e
                )
                Log.e(TAG, "Failed to enqueue captured image", e)
            }
            runOnUiThread {
                Toast.makeText(
                    this@MediaCaptureActivity,
                    "📸 Captured & Queued",
                    Toast.LENGTH_SHORT
                ).show()
                finish()
            }
        }
    }

    // ── Inline vision processing ────────────────────────────────────────

    /**
     * Processes a captured image through the vision pipeline inline.
     * Same code path as [HabitViewModel.testVisionEndpoint], extended with:
     *  - the learned vision memory + habit list injected into the prompt
     *  - execution of a proposed [com.example.tail.data.meal.HabitAction]
     *    when the LLM is certain enough (smart auto-detection)
     */
    private suspend fun processCaptureInline(
        relativePath: String,
        targetHabit: String?,
        mealLogRepo: MealLogRepository,
        queueRepo: VisionQueueRepository,
        voiceErrorNote: String? = null
    ) {
        val settingsRepo = SettingsRepository(this@MediaCaptureActivity)
        val settings = try {
            settingsRepo.settingsFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
            showResult("❌ Failed to load settings: ${e.message?.take(200)}", false)
            return
        }

        if (!settings.mealEnabled || settings.mealApiKey.isBlank() ||
            settings.mealBaseUrl.isBlank() || settings.mealModel.isBlank()
        ) {
            Log.w(TAG, "Meal engine not configured — enqueuing for background processing")
            queueRepo.enqueue(relativePath, targetHabit)
            VisionProcessingWorker.enqueue(this@MediaCaptureActivity)
            showResult(
                "📋 Meal engine not configured.\n" +
                "Photo queued for background processing.\n" +
                "Configure the LLM endpoint in Settings → Meal Engine.",
                isSuccess = false
            )
            return
        }

        val config = VisionConfig(
            baseUrl = settings.mealBaseUrl,
            apiKey = settings.mealApiKey,
            model = settings.mealModel,
            userSystemPrompt = settings.mealSystemPrompt
        )

        // ── Deterministic meal routing ─────────────────────────────────────
        // Same rule the background worker uses: when the capture's target
        // habit is already known — explicitly (EXTRA_HABIT_NAME) or because
        // exactly ONE camera-eligible habit exists — and that habit is a
        // meal habit, the image is unambiguously a meal photo for it. Run
        // the EXACT pipeline the manual editor uses: plain food-analysis
        // prompt, no habit list, no learned memory, no habit guessing.
        val singleCameraHabit = VisionHabitExecutor.cameraEligibleHabits(settings).singleOrNull()
        val forcedMealHabit = if (targetHabit != null) {
            targetHabit?.takeIf { settings.mealHabits.contains(it) }
        } else {
            singleCameraHabit?.takeIf { settings.mealHabits.contains(it) }
        }
        QcDiag.log(
            "ROUTE",
            "processCaptureInline: targetHabit=${targetHabit ?: "null"} " +
                "singleCameraHabit=${singleCameraHabit ?: "null"} " +
                "forcedMealHabit=${forcedMealHabit ?: "NULL → classification pipeline"}"
        )
        QcDiag.log("ROUTE", "processCaptureInline: ${QcDiag.routingSnapshot(settings)}")
        QcDiag.log("ROUTE", "processCaptureInline: ${QcDiag.mismatchHints(settings)}")

        val imageFile = File(filesDir, relativePath)
        val service = VisionProcessingService()
        val llmStart = System.currentTimeMillis()
        val result = if (forcedMealHabit != null) {
            QcDiag.log(
                "LLM",
                "processCaptureInline: DETERMINISTIC meal pipeline for '$forcedMealHabit' " +
                    "(no habit guessing)"
            )
            Log.i(TAG, "Deterministic meal pipeline for '$forcedMealHabit'")
            service.processImage(imageFile, config)
        } else {
            // Classification pipeline — inject the learned memory + the
            // valid habit list so smart auto-detection works.
            val memoryPrompt = VisionMemoryRepository(this@MediaCaptureActivity)
                .buildMemoryPrompt().ifBlank { null }
            val habitPrompt = VisionHabitExecutor.buildHabitPrompt(settings).ifBlank { null }
            QcDiag.warn(
                "LLM",
                "processCaptureInline: CLASSIFICATION pipeline (habit guessing) — " +
                    "memoryPrompt=${memoryPrompt != null} " +
                    "habitPrompt=${habitPrompt != null}(${habitPrompt?.length ?: 0} chars)"
            )
            service.processImage(imageFile, config, memoryPrompt, habitPrompt)
        }
        QcDiag.log(
            "LLM",
            "processCaptureInline result in ${System.currentTimeMillis() - llmStart}ms: " +
                "classification=${result?.classification} " +
                "food=${result?.foodData?.let { "${it.title}/${it.estimatedCalories}cal" }
                    ?: "none"} " +
                "habitAction=${result?.habitAction?.let { ha ->
                    "${ha.habitName}" + (ha.subtypeName?.let { s -> "/$s" } ?: "") +
                        "x${ha.amount}"
                } ?: "none"} " +
                "confidence=${result?.confidenceScore} " +
                "notes=${result?.processingNotes?.take(150) ?: ""}"
        )

        if (result == null) {
            showResult(
                "❌ Vision request failed — check your URL, key, and model.\n" +
                "See logcat (tag: VisionProcessing) for details.",
                isSuccess = false
            )
            return
        }

        val targetHabitName = forcedMealHabit
            ?: targetHabit
            ?: autoRouteHabit(result, settings.mealHabits)

        // ── FOOD: always log it with the LLM's best-guess specifics and
        // increment the meal habit, then let the user fine-tune the numbers
        // in the editable review screen. Food is NEVER rejected for low
        // confidence — confidence only gates the habit choice.
        if (result.classification == VisionClassification.FOOD_MEAL &&
            result.foodData != null && targetHabitName != null
        ) {
            val now = System.currentTimeMillis()
            val active = mealLogRepo.findActiveGroup(targetHabitName, now)
            var reviewedLog: MealLog? = null
            if (active != null) {
                // Second course within the group window — merge into the
                // existing meal: one card, one increment.
                QcDiag.log(
                    "MEAL",
                    "processCaptureInline: MERGE into active meal " +
                        "${QcDiag.short(active.id)} '${active.title}' " +
                        "(anchorDeltaMs=${now - active.anchorTime()}) — no new increment"
                )
                reviewedLog = active.mergedWith(
                    foodData = result.foodData,
                    extraImageUri = relativePath,
                    newTimestamp = now
                )
                mealLogRepo.updateLog(reviewedLog)
            } else {
                val created = result.toMealLog(
                    habitId = targetHabitName,
                    timestamp = now,
                    imageUri = relativePath,
                    rawJson = result.toString()
                )
                if (created != null) {
                    reviewedLog = created
                    mealLogRepo.addLog(created)
                    QcDiag.log(
                        "MEAL",
                        "processCaptureInline: CREATED meal log ${QcDiag.short(created.id)} " +
                            "'${created.title}' (${created.calories} cal) for '$targetHabitName'"
                    )

                    if (settings.fileUri.isNotEmpty()) {
                        try {
                            val habitsRepo = HabitsRepository()
                            habitsRepo.incrementHabit(
                                Uri.parse(settings.fileUri),
                                this@MediaCaptureActivity,
                                targetHabitName,
                                1
                            )
                            // Record the increment timestamp so captured meals
                            // are timestamped like every other increment path
                            com.example.tail.data.HabitTimestampRepository(this@MediaCaptureActivity)
                                .addTimestamp(habitName = targetHabitName)
                            QcDiag.log(
                                "INCREMENT",
                                "processCaptureInline: incremented '$targetHabitName' +1 " +
                                    "and recorded timestamp for meal '${created.title}'"
                            )
                            Log.i(TAG, "Incremented habit '$targetHabitName' for meal: ${created.title}")
                        } catch (e: Exception) {
                            QcDiag.error(
                                "INCREMENT",
                                "processCaptureInline: increment FAILED for " +
                                    "'$targetHabitName' (meal log still saved): ${e.message}",
                                e
                            )
                            Log.e(TAG, "Failed to increment habit '$targetHabitName'", e)
                        }
                    }
                }
            }

            if (reviewedLog != null) {
                val note = listOfNotNull(
                    voiceErrorNote,
                    "Best guess below — adjust anything before saving."
                ).joinToString("\n\n")
                runOnUiThread { captureState = CaptureState.MealEdit(reviewedLog, note) }
                return
            }
        }

        // ── Smart auto-detection (non-food): execute the LLM's proposed
        // habit action. The candidate list is restricted to camera-enabled
        // habits and the LLM always picks its best guess among them — no
        // confidence gate (the user sees the result and can undo it).
        if (result.classification != VisionClassification.FOOD_MEAL &&
            result.habitAction != null
        ) {
            val action = result.habitAction!!
            val resolved = VisionHabitExecutor.resolveHabitAction(
                settings, action.habitName, action.subtypeName
            )
            if (resolved == null) {
                Log.w(TAG, "LLM proposed unknown habit '${action.habitName}' — correcting")
            } else {
                val (realHabit, realSubtype) = resolved
                val err = VisionHabitExecutor.execute(
                    this@MediaCaptureActivity, settings, realHabit, realSubtype, action.amount
                )
                if (err == null) {
                    val seen = result.nonFoodData?.detectedActivity
                        ?: result.processingNotes.removePrefix("Description:").trim()
                    val display = buildString {
                        voiceErrorNote?.let { append(it); append("\n\n") }
                        append("✅ Incremented $realHabit")
                        append(realSubtype?.let { " ($it)" } ?: "")
                        append(" ×${action.amount}")
                        seen.takeIf { it.isNotBlank() }?.let { append("\n\nSaw: ${it.take(200)}") }
                        action.reasoning.takeIf { it.isNotBlank() }?.let { append("\n\nWhy: $it") }
                        append("\n\nConfidence: ${(result.confidenceScore * 100).toInt()}%")
                    }
                    showResult(display, isSuccess = true, title = "Habit Incremented")
                    return
                }
                showResult("⚠️ Wanted to increment $realHabit but failed: $err", isSuccess = false)
                return
            }
        }

        // ── API-level failures (rate limit / server) — nothing correctable.
        val notes = result.processingNotes
        val isApiError = notes.contains("Rate limited", ignoreCase = true) ||
                         notes.contains("Server error", ignoreCase = true) ||
                         notes.contains("API error", ignoreCase = true)
        if (isApiError) {
            showResult("❌ ${notes.take(300)}", isSuccess = false)
            return
        }

        // ── Everything else: NEVER a dead end. Open the correction screen
        // so the user can say (by text or voice) what the photo means and
        // pick the habit — the answer goes into the LLM's memory.
        QcDiag.warn(
            "REVIEW",
            "processCaptureInline: NOT actionable → Correcting screen " +
                "(classification=${result.classification} " +
                "forcedMealHabit=${forcedMealHabit != null} " +
                "habitAction=${result.habitAction?.habitName ?: "none"})"
        )
        correctableHabits = (settings.habitScreens.flatMap { it.habitNames } + settings.habitOrder)
            .filter { it.isNotBlank() && !it.startsWith("app_link:") }
            .distinct()
            .sortedBy { it.lowercase() }
        correctableSubtypes = settings.habitSubtypes
        val rawSeen = result.nonFoodData?.detectedActivity
            ?: notes.removePrefix("Description:").trim().ifBlank { notes }
        val seenLine = rawSeen.take(200)
        val contextText = listOfNotNull(voiceErrorNote, seenLine.ifBlank { null })
            .joinToString("\n")
        runOnUiThread {
            captureState = CaptureState.Correcting(
                imagePath = relativePath,
                llmDescription = contextText,
                description = seenLine.removePrefix("Detected: ").trim()
            )
        }
    }

    private fun autoRouteHabit(result: VisionResult, mealHabits: Set<String>): String? {
        if (result.classification != VisionClassification.FOOD_MEAL) return null
        return mealHabits.firstOrNull()
    }


    private fun showResult(text: String, isSuccess: Boolean, title: String? = null) {
        runOnUiThread {
            captureState = CaptureState.Result(text, isSuccess, title)
        }
    }

    // ── Meal review (editable) ───────────────────────────────────────────

    private fun saveMealEdit(updated: MealLog, oldTimestamp: Long) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                MealLogRepository(this@MediaCaptureActivity).updateLog(updated)
                // Keep the recorded increment timestamp in sync with time edits
                if (updated.countedIncrement && updated.timestamp != oldTimestamp) {
                    val tsRepo = com.example.tail.data.HabitTimestampRepository(this@MediaCaptureActivity)
                    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    val oldZdt = java.time.Instant.ofEpochMilli(oldTimestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                    val newZdt = java.time.Instant.ofEpochMilli(updated.timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                    val day = tsRepo.getTimestampsForDay(updated.habitId, oldZdt.toLocalDate())
                    val idx = day.indexOf(oldZdt.toLocalTime().format(fmt))
                    if (idx >= 0) {
                        tsRepo.deleteTimestamp(updated.habitId, oldZdt.toLocalDate(), idx)
                    }
                    tsRepo.addTimestamp(updated.habitId, newZdt.toLocalDate(), newZdt.toLocalTime().format(fmt))
                }
                Log.i(TAG, "Meal log updated after review: ${updated.title}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update meal log", e)
            }
            finish()
        }
    }

    private fun discardMealEdit(log: MealLog) {
        lifecycleScope.launch(Dispatchers.IO) {
            val repo = MealLogRepository(this@MediaCaptureActivity)
            try { repo.deleteLog(log.habitId, log.id) } catch (e: Exception) {
                Log.e(TAG, "Failed to delete meal log", e)
            }
            log.imageList().forEach { path ->
                try { repo.deleteImage(path) } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete meal image", e)
                }
            }
            // Undo the automatic increment (count + timestamp) that came with the log
            if (log.countedIncrement) {
                try {
                    val settings = SettingsRepository(this@MediaCaptureActivity).settingsFlow.first()
                    if (settings.fileUri.isNotEmpty()) {
                        HabitsRepository().incrementHabit(
                            Uri.parse(settings.fileUri), this@MediaCaptureActivity, log.habitId, -1
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to undo meal increment", e)
                }
                try {
                    val tsRepo = com.example.tail.data.HabitTimestampRepository(this@MediaCaptureActivity)
                    val zdt = java.time.Instant.ofEpochMilli(log.timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                    val fmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                    val day = tsRepo.getTimestampsForDay(log.habitId, zdt.toLocalDate())
                    val idx = day.indexOf(zdt.toLocalTime().format(fmt))
                    if (idx >= 0) {
                        tsRepo.deleteTimestamp(log.habitId, zdt.toLocalDate(), idx)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to undo meal timestamp", e)
                }
            }
            finish()
        }
    }

    // ── Correction (teach what the photo means) ─────────────────────────

    private fun launchGalleryPicker(teach: Boolean) {
        galleryTeachMode = teach
        handler.removeCallbacks(autoFinishTimeout)
        galleryPicker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun startCorrectionListening() {
        handler.removeCallbacks(autoFinishTimeout)
        handler.postDelayed(autoFinishTimeout, AUTO_FINISH_TIMEOUT_MS + TANDEM_SPEAK_WINDOW_MS)
        if (!voiceServiceStarted) startVoiceMode()
        VoiceTranscriptBus.armTandem()
        vibrateTandemReady()
    }

    private fun stopCorrectionListening() {
        VoiceTranscriptBus.disarmTandem()
    }

    private fun saveCorrection() {
        val st = captureState as? CaptureState.Correcting ?: return
        val habit = st.selectedHabit
        if (habit.isNullOrBlank()) {
            Toast.makeText(this, "Pick a habit first", Toast.LENGTH_SHORT).show()
            return
        }
        VoiceTranscriptBus.disarmTandem()
        stopVoiceService()
        captureState = CaptureState.Processing
        lifecycleScope.launch(Dispatchers.IO) {
            val settings = try {
                SettingsRepository(this@MediaCaptureActivity).settingsFlow.first()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings for correction", e)
                showResult("❌ Failed to load settings: ${e.message?.take(200)}", false)
                return@launch
            }
            val resolved = VisionHabitExecutor.resolveHabitAction(
                settings, habit, st.selectedSubtype
            )
            val realHabit = resolved?.first ?: habit
            val realSubtype = resolved?.second
            val description = st.description.ifBlank { "photo associated with $realHabit" }

            // Persist the corrected association — text-only memory
            VisionMemoryRepository(this@MediaCaptureActivity).addEntry(
                VisionMemoryRepository.newEntry(
                    timestamp = System.currentTimeMillis(),
                    voiceNote = "",
                    visualDescription = description,
                    habitName = realHabit,
                    subtypeName = realSubtype,
                    incrementAmount = st.amount
                )
            )

            // ── Meal habit: the corrected photo + description must ALSO
            // become a meal card (photo attached, LLM best-guess nutrition
            // parsed from the description). Incrementing alone would lose
            // the meal report — photo + voice description is more than
            // enough to fill the card.
            val isMealHabit = realHabit in settings.mealHabits
            var mergedIntoExisting = false
            var mealTitle: String? = null
            if (isMealHabit) {
                val mealLogRepo = MealLogRepository(this@MediaCaptureActivity)
                val now = System.currentTimeMillis()

                // Best-effort nutrition parse of the user's description
                // (voice memo / typed text). Null on any failure — the
                // card is still created with the raw description.
                val fd = if (settings.mealEnabled && settings.mealApiKey.isNotBlank() &&
                    settings.mealBaseUrl.isNotBlank() && settings.mealModel.isNotBlank() &&
                    st.description.isNotBlank()
                ) {
                    try {
                        VisionProcessingService().processMealText(
                            st.description,
                            VisionConfig(
                                baseUrl = settings.mealBaseUrl,
                                apiKey = settings.mealApiKey,
                                model = settings.mealModel,
                                userSystemPrompt = settings.mealSystemPrompt
                            )
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Correction meal-text parse failed: ${e.message}")
                        null
                    }
                } else null

                val active = mealLogRepo.findActiveGroup(realHabit, now)
                if (active != null) {
                    // Same meal group still open — merge photo + details
                    // into the existing card; that group's increment
                    // already happened, so no new increment below.
                    mealLogRepo.updateLog(
                        active.mergedWith(
                            foodData = fd,
                            extraImageUri = st.imagePath,
                            transcript = st.description.takeIf { it.isNotBlank() }
                        )
                    )
                    mergedIntoExisting = true
                    mealTitle = active.title
                } else {
                    val log = MealLog(
                        id = java.util.UUID.randomUUID().toString(),
                        habitId = realHabit,
                        timestamp = now,
                        imageUri = st.imagePath,
                        imageUris = listOf(st.imagePath),
                        title = fd?.title?.takeIf { it.isNotBlank() }
                            ?: st.description.takeIf { it.isNotBlank() }?.take(40)
                            ?: "Meal",
                        summary = fd?.summary?.takeIf { it.isNotBlank() },
                        calories = fd?.estimatedCalories ?: 0,
                        macronutrients = fd?.macronutrients ?: Macronutrients(),
                        ingredientsDetected = fd?.ingredientsDetected ?: emptyList(),
                        isVeganVerified = fd?.isVeganVerified ?: false,
                        healthNotes = fd?.healthNotes,
                        voiceTranscript = st.description.takeIf { it.isNotBlank() },
                        macroRatings = fd?.macroRatings,
                        countedIncrement = true,
                        groupStartTimestamp = now
                    )
                    mealLogRepo.addLog(log)
                    mealTitle = log.title
                }
            }

            val incError = if (mergedIntoExisting) null else VisionHabitExecutor.execute(
                this@MediaCaptureActivity, settings, realHabit, realSubtype, st.amount
            )
            val display = buildString {
                append("🧠 Learned! \"$description\"\n\n")
                append("→ $realHabit" + (realSubtype?.let { " / $it" } ?: "") + " ×${st.amount}\n\n")
                if (mealTitle != null) {
                    append(
                        if (mergedIntoExisting) "🍽 Merged into meal \"$mealTitle\"."
                        else "🍽 Meal card created: \"$mealTitle\"."
                    )
                    append("\n\n")
                }
                append(
                    if (incError == null) "✅ Incremented now, too."
                    else "⚠️ Saved to memory, but the increment failed: $incError"
                )
                append("\n\nFuture photos like this will be recognised automatically.")
            }
            showResult(display, incError == null, title = "Learned")
        }
    }

    private fun discardCorrection(st: CaptureState.Correcting) {
        VoiceTranscriptBus.disarmTandem()
        stopVoiceService()
        MealLogRepository(this).deleteImage(st.imagePath)
        finish()
    }

    /** Distinct double-buzz so the user knows tandem (teach) mode armed. */
    private fun vibrateTandemReady() {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val mgr = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                mgr.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 80, 60, 80), -1)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Tandem vibration failed: ${e.message}")
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
//  Compose Screens
// ════════════════════════════════════════════════════════════════════════════

/**
 * Full-screen camera preview with a capture button. When [voiceActive] is
 * true, a "🎤 Listening…" indicator is shown so the user knows voice is
 * also active and they can talk at any time.
 *
 * The capture button supports two gestures:
 *  - **Tap** → plain photo capture (existing behaviour)
 *  - **Hold** → tandem teaching: photo + spoken instruction together
 */
@Composable
private fun CameraWithVoiceScreen(
    targetHabit: String?,
    voiceActive: Boolean,
    onCapture: () -> Unit,
    onHoldCapture: () -> Unit,
    onPickGallery: () -> Unit,
    onHoldPickGallery: () -> Unit,
    onCancel: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                startCamera(ctx as ComponentActivity, previewView) { capture ->
                    (ctx as? MediaCaptureActivity)?.let { activity ->
                        activity.imageCapture = capture
                    }
                }
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Overlay controls
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .padding(16.dp)
        ) {
            // Cancel button (top-left)
            IconButton(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.White)
            }

            // Voice listening indicator (top-center)
            if (voiceActive) {
                Text(
                    text = "🎤 Listening…",
                    color = Color.White,
                    fontSize = 14.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Habit name label (below voice indicator)
            if (targetHabit != null) {
                Text(
                    text = targetHabit,
                    color = Color.White,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = if (voiceActive) 44.dp else 8.dp)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
            }

            // Hold hint (above capture button)
            Text(
                text = "tap = photo · hold = photo + teach · 🖼 gallery same",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 116.dp)
                    .background(Color.Black.copy(alpha = 0.4f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )

            // Capture button (bottom-center) — tap for a plain photo,
            // hold to pair the photo with a spoken teaching instruction.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(72.dp)
                    .background(Color.White, CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onCapture() },
                            onLongPress = { onHoldCapture() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = "Capture (hold to teach)",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
            }

            // Gallery button (bottom-left) — tap to analyse a picked photo,
            // hold to teach it with a spoken instruction (same as shutter).
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 24.dp, bottom = 38.dp)
                    .size(56.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { onPickGallery() },
                            onLongPress = { onHoldPickGallery() }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PhotoLibrary,
                    contentDescription = "Gallery (hold to teach)",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
/**
 * Tandem teaching screen: the photo is already taken; the camera preview
 * stays visible while we wait for the spoken instruction.
 */
@Composable
private fun AwaitingVoiceScreen(
    onCancel: () -> Unit,
    onAnalyseWithoutVoice: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("📸 Photo taken", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "🎤 Now say what it means…\n\ne.g. \"this is my garden —\nincrement gardening\"",
                color = Color.White,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(28.dp))
            OutlinedButton(
                onClick = onAnalyseWithoutVoice,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Analyse photo without teaching", color = Color.White)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFB00020)
                )
            ) {
                Text("Discard")
            }
        }
    }
}

/**
 * Processing screen — shows a spinner while the LLM analyses the image.
 */
@Composable
private fun ProcessingScreen(onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Analysing image…", color = Color.White, fontSize = 16.sp)
            Text(
                "This may take a few seconds",
                color = Color.White.copy(alpha = 0.6f),
                fontSize = 12.sp
            )
        }
    }
}

/**
 * Result screen — shows the LLM's interpretation and a Done button.
 */
@Composable
private fun ResultScreen(
    displayText: String,
    isSuccess: Boolean,
    title: String?,
    onDismiss: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title ?: if (isSuccess) "Meal Logged" else "Result",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isSuccess) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(12.dp))

                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = displayText,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Start
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSuccess) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("Done", fontSize = 16.sp)
                }
            }
        }
    }
}

/**
 * Editable review of an automatically logged meal. The habit was already
 * incremented; this screen lets the user correct the LLM's best-guess
 * specifics (title, calories, macros, summary) before finishing.
 */
@Composable
private fun MealEditScreen(
    log: MealLog,
    note: String?,
    onSave: (MealLog) -> Unit,
    onDiscard: (MealLog) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "🍽 Meal Logged — Review",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Logged to: ${log.habitId}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Shared full editor — same controls as the meal screen's
                // card editor (time, macros, ratings, tags, transcript…)
                com.example.tail.ui.MealEditorContent(
                    log = log,
                    note = note,
                    filesDir = context.filesDir,
                    allowTimeEdit = true,
                    onSave = onSave,
                    onDelete = onDiscard
                )
            }
        }
    }
}

/**
 * Correction screen — shown when the LLM couldn't act on a photo with
 * certainty. NEVER a dead end: the user describes what the photo means
 * (typing and/or a voice follow-up), picks the habit (+ subtype, amount),
 * and the answer is saved to the LLM's vision memory.
 */
@Composable
private fun CorrectionScreen(
    state: CaptureState.Correcting,
    habits: List<String>,
    subtypes: Map<String, List<String>>,
    onUpdate: (CaptureState.Correcting) -> Unit,
    onMicToggle: (Boolean) -> Unit,
    onSave: () -> Unit,
    onDiscard: () -> Unit
) {
    var habitPickerOpen by remember { mutableStateOf(false) }
    var subtypePickerOpen by remember { mutableStateOf(false) }
    val habitSubtypes = state.selectedHabit?.let { subtypes[it].orEmpty() } ?: emptyList()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    "🧠 Teach the AI",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "This photo wasn't recognised with certainty. Say what it means — " +
                        "your answer goes into the AI's memory for next time.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                if (state.llmDescription.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "It saw: ${state.llmDescription}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = state.description,
                    onValueChange = { onUpdate(state.copy(description = it)) },
                    label = { Text("Description (goes into memory)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { habitPickerOpen = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(state.selectedHabit ?: "Choose habit…") }
                if (habitSubtypes.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { subtypePickerOpen = true },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(state.selectedSubtype ?: "Subtype (optional)…") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (state.amount > 1) onUpdate(state.copy(amount = state.amount - 1)) }
                    ) { Icon(Icons.Default.Remove, contentDescription = "Less") }
                    Text(
                        "×${state.amount}",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                    IconButton(
                        onClick = { onUpdate(state.copy(amount = state.amount + 1)) }
                    ) { Icon(Icons.Default.Add, contentDescription = "More") }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val start = !state.listening
                        onUpdate(state.copy(listening = start, voiceError = null))
                        onMicToggle(start)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (state.listening) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (state.listening) "Listening… tap to stop" else "🎤 Add voice note")
                }
                state.voiceError?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "🎤 $it",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onSave,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.selectedHabit.isNullOrBlank()
                ) { Text("Save & increment") }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Discard photo") }
            }
        }
    }

    if (habitPickerOpen) {
        HabitPickerDialog(
            title = "Choose habit",
            options = habits,
            selected = state.selectedHabit,
            onDismiss = { habitPickerOpen = false },
            onSelect = { habit ->
                onUpdate(state.copy(selectedHabit = habit, selectedSubtype = null))
                habitPickerOpen = false
            }
        )
    }
    if (subtypePickerOpen && habitSubtypes.isNotEmpty()) {
        HabitPickerDialog(
            title = "Subtype",
            options = habitSubtypes,
            selected = state.selectedSubtype,
            onDismiss = { subtypePickerOpen = false },
            onSelect = { sub ->
                onUpdate(state.copy(selectedSubtype = sub))
                subtypePickerOpen = false
            }
        )
    }
}

/** Scrollable single-choice picker dialog: alphabetical + searchable. */
@Composable
private fun HabitPickerDialog(
    title: String,
    options: List<String>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val sorted = remember(options) { options.sortedBy { it.lowercase() } }
    val filtered = remember(sorted, query) {
        if (query.isBlank()) sorted
        else sorted.filter { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    filtered.forEach { option ->
                        Text(
                            text = option + (if (option == selected) "  ✓" else ""),
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(option) }
                                .padding(vertical = 10.dp)
                        )
                    }
                    if (filtered.isEmpty()) {
                        Text(
                            "No matches",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 10.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            OutlinedButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

// ════════════════════════════════════════════════════════════════════════════
//  Camera setup (shared helper)
// ════════════════════════════════════════════════════════════════════════════

private fun startCamera(
    activity: ComponentActivity,
    previewView: PreviewView,
    onReady: (ImageCapture) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    activity,
                    cameraSelector,
                    preview,
                    imageCapture
                )
                onReady(imageCapture)
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Camera provider init failed", e)
        }
    }, ContextCompat.getMainExecutor(activity))
}
