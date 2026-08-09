package com.example.tail

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.example.tail.data.meal.MealLogRepository
import com.example.tail.data.meal.VisionClassification
import com.example.tail.data.meal.VisionConfig
import com.example.tail.data.meal.VisionProcessingService
import com.example.tail.data.meal.VisionProcessingWorker
import com.example.tail.data.meal.VisionQueueRepository
import com.example.tail.data.meal.VisionResult
import com.example.tail.ipc.SmartVoiceService
import com.example.tail.ui.HabitIncrementBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "MediaCapture"
private const val AUTO_FINISH_TIMEOUT_MS = 35_000L

/**
 * UI state for [MediaCaptureActivity].
 */
sealed class CaptureState {
    /**
     * Camera preview is shown full-screen while voice listens in the
     * background. The user can talk (voice handles it) or tap the capture
     * button to take a photo.
     */
    object CameraWithVoice : CaptureState()
    /** Image captured, LLM processing in progress. */
    object Processing : CaptureState()
    /** LLM result ready for the user to read and dismiss. */
    data class Result(val displayText: String, val isSuccess: Boolean) : CaptureState()
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
 *
 * Works on the lock screen (`showWhenLocked` + `turnScreenOn`).
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

    private var captureState by mutableStateOf<CaptureState>(CaptureState.CameraWithVoice)
    private val handler = Handler(Looper.getMainLooper())

    /** Auto-finish after the voice service timeout (no capture, no speech). */
    private val autoFinishTimeout = Runnable {
        Log.d(TAG, "Auto-finish timeout — stopping voice and finishing")
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setShowWhenLocked(true)
        setTurnScreenOn(true)

        targetHabit = intent.getStringExtra(EXTRA_HABIT_NAME)
        val directCamera = intent.getBooleanExtra(EXTRA_DIRECT_CAMERA, false)
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

        // Listen for habit increments from the voice service → auto-finish
        lifecycleScope.launch {
            HabitIncrementBus.events.collect { habitName ->
                Log.d(TAG, "Habit incremented via voice: $habitName — finishing")
                handler.removeCallbacks(autoFinishTimeout)
                stopVoiceService()
                finish()
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
                        onCancel = {
                            stopVoiceService()
                            finish()
                        }
                    )
                    is CaptureState.Processing -> ProcessingScreen(onCancel = { finish() })
                    is CaptureState.Result -> ResultScreen(
                        displayText = state.displayText,
                        isSuccess = state.isSuccess,
                        onDismiss = { finish() }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(autoFinishTimeout)
        cameraExecutor.shutdown()
    }

    // ── Voice mode ──────────────────────────────────────────────────────

    /**
     * Starts [SmartVoiceService] for voice listening **immediately** (smart
     * routing to habits/notes). Mirrors [SmartVoiceActivity] — the service
     * handles vibration, SpeechRecognizer, TTS, and confirmation overlays.
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

    // ── Camera mode ─────────────────────────────────────────────────────

    /**
     * Captures a photo, stops voice, then processes the image **inline**
     * through the vision pipeline (same code path as the Settings test
     * button). The result is shown in a [CaptureState.Result] overlay.
     */
    private fun capturePhoto(targetHabit: String?) {
        if (captureInProgress) return
        captureInProgress = true

        val capture = imageCapture ?: run {
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

                        runOnUiThread { captureState = CaptureState.Processing }

                        lifecycleScope.launch(Dispatchers.IO) {
                            processCaptureInline(
                                relativePath,
                                targetHabit,
                                mealLogRepo,
                                queueRepo
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save captured image", e)
                        runOnUiThread {
                            captureState = CaptureState.Result(
                                "❌ Capture failed: ${e.message?.take(200)}",
                                isSuccess = false
                            )
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Camera capture error", exception)
                    runOnUiThread {
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
     * Processes a captured image through the vision pipeline inline.
     * Same code path as [HabitViewModel.testVisionEndpoint].
     */
    private suspend fun processCaptureInline(
        relativePath: String,
        targetHabit: String?,
        mealLogRepo: MealLogRepository,
        queueRepo: VisionQueueRepository
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

        val imageFile = File(filesDir, relativePath)
        val service = VisionProcessingService()
        val result = service.processImage(imageFile, config)

        if (result == null) {
            showResult(
                "❌ Vision request failed — check your URL, key, and model.\n" +
                "See logcat (tag: VisionProcessing) for details.",
                isSuccess = false
            )
            return
        }

        val targetHabitName = targetHabit ?: autoRouteHabit(result, settings.mealHabits)

        if (result.classification == VisionClassification.FOOD_MEAL &&
            result.foodData != null && targetHabitName != null
        ) {
            val mealLog = result.toMealLog(
                habitId = targetHabitName,
                timestamp = System.currentTimeMillis(),
                imageUri = relativePath,
                rawJson = result.toString()
            )
            if (mealLog != null) {
                mealLogRepo.addLog(mealLog)

                if (settings.fileUri.isNotEmpty()) {
                    try {
                        val habitsRepo = HabitsRepository()
                        habitsRepo.incrementHabit(
                            Uri.parse(settings.fileUri),
                            this@MediaCaptureActivity,
                            targetHabitName,
                            1
                        )
                        Log.i(TAG, "Incremented habit '$targetHabitName' for meal: ${mealLog.title}")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to increment habit '$targetHabitName'", e)
                    }
                }
            }
        }

        val displayText = formatResultText(result, targetHabitName)
        val isSuccess = result.classification == VisionClassification.FOOD_MEAL &&
                        result.foodData != null
        showResult(displayText, isSuccess)
    }

    private fun autoRouteHabit(result: VisionResult, mealHabits: Set<String>): String? {
        if (result.classification != VisionClassification.FOOD_MEAL) return null
        return mealHabits.firstOrNull()
    }

    private fun formatResultText(result: VisionResult, targetHabit: String?): String {
        return when (result.classification) {
            VisionClassification.FOOD_MEAL -> {
                val fd = result.foodData ?: return "⚠️ Food detected but no data extracted."
                buildString {
                    append("✅ Detected: ${fd.title}\n\n")
                    append("Calories: ${fd.estimatedCalories} kcal\n")
                    append("Protein: ${fd.macronutrients.proteinGrams}g, ")
                    append("Carbs: ${fd.macronutrients.carbsGrams}g, ")
                    append("Fat: ${fd.macronutrients.fatGrams}g\n")
                    if (fd.summary.isNotBlank()) {
                        append("\n${fd.summary}\n")
                    }
                    if (fd.ingredientsDetected.isNotEmpty()) {
                        append("\nIngredients: ${fd.ingredientsDetected.joinToString(", ")}\n")
                    }
                    fd.healthNotes?.let { append("\n$it\n") }
                    if (targetHabit != null) {
                        append("\n→ Logged to: $targetHabit")
                    }
                    append("\n\nConfidence: ${(result.confidenceScore * 100).toInt()}%")
                }
            }
            VisionClassification.NON_FOOD_HABIT -> {
                val activity = result.nonFoodData?.detectedActivity ?: "unknown"
                "⚠️ Non-food detected: $activity\n\n${result.processingNotes}"
            }
            VisionClassification.UNCERTAIN_OTHER -> {
                val notes = result.processingNotes
                val isError = notes.contains("Rate limited", ignoreCase = true) ||
                              notes.contains("Server error", ignoreCase = true) ||
                              notes.contains("API error", ignoreCase = true)
                if (isError) {
                    "❌ ${notes.take(300)}"
                } else {
                    "⚠️ Uncertain classification.\nNotes: $notes"
                }
            }
        }
    }

    private fun showResult(text: String, isSuccess: Boolean) {
        runOnUiThread {
            captureState = CaptureState.Result(text, isSuccess)
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
 */
@Composable
private fun CameraWithVoiceScreen(
    targetHabit: String?,
    voiceActive: Boolean,
    onCapture: () -> Unit,
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

            // Capture button (bottom-center) — the ONLY button the user needs to press
            IconButton(
                onClick = onCapture,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp)
                    .size(72.dp)
                    .background(Color.White, CircleShape)
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = "Capture",
                    tint = Color.Black,
                    modifier = Modifier.size(36.dp)
                )
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
                    text = if (isSuccess) "Meal Logged" else "Result",
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
