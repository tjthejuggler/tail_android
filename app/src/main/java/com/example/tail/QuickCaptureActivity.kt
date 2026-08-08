package com.example.tail

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.tail.data.meal.MealLogRepository
import com.example.tail.data.meal.VisionProcessingWorker
import com.example.tail.data.meal.VisionQueueRepository
import java.io.File
import java.util.concurrent.Executors

private const val TAG = "QuickCapture"

/**
 * Lightweight camera activity for the Meal Habit's "quick capture" flow.
 *
 * Accessible system-wide via a launcher shortcut (see [shortcuts.xml]) or
 * launched in-app from the Meal Detail screen. Shows a minimal CameraX
 * preview with a single-tap capture button. On capture:
 *   1. Saves the JPEG to internal storage (`files/meal_images/`)
 *   2. Enqueues it in the [VisionQueueRepository]
 *   3. Triggers [VisionProcessingWorker] for background LLM processing
 *   4. Finishes immediately with a "Captured & Queued" toast
 *
 * Accepts an optional `EXTRA_HABIT_NAME` to pre-assign the capture to a
 * specific meal habit (used when launched from the Meal Detail screen).
 * When omitted, the LLM auto-routes via classification.
 */
class QuickCaptureActivity : ComponentActivity() {

    companion object {
        const val EXTRA_HABIT_NAME = "extra_habit_name"
    }

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    internal var imageCapture: ImageCapture? = null
    private var hasCameraPermission = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            hasCameraPermission = true
            // Recompose to show camera
            recreate()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
            return
        }

        val targetHabit = intent.getStringExtra(EXTRA_HABIT_NAME)

        setContent {
            QuickCaptureScreen(
                targetHabit = targetHabit,
                onCapture = { capturePhoto(targetHabit) },
                onCancel = { finish() }
            )
        }
    }

    /**
     * Captures a photo using the current [ImageCapture] use case, saves it
     * to internal storage, enqueues it for vision processing, and finishes.
     */
    private fun capturePhoto(targetHabit: String?) {
        val capture = imageCapture ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        val mealLogRepo = MealLogRepository(this)
        val queueRepo = VisionQueueRepository(this)

        // Use a temporary file in cache, then move to meal_images
        val tempFile = File(cacheDir, "capture_${System.currentTimeMillis()}.jpg")
        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile).build()

        capture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        // Read bytes and save to meal_images via the repository
                        val bytes = tempFile.readBytes()
                        tempFile.delete()
                        val relativePath = mealLogRepo.saveImageBytes(bytes)

                        // Enqueue for vision processing
                        queueRepo.enqueue(relativePath, targetHabit)

                        // Trigger the background worker
                        VisionProcessingWorker.enqueue(this@QuickCaptureActivity)

                        runOnUiThread {
                            Toast.makeText(
                                this@QuickCaptureActivity,
                                "📸 Captured & Queued",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save captured image", e)
                        runOnUiThread {
                            Toast.makeText(
                                this@QuickCaptureActivity,
                                "Capture failed: ${e.message}",
                                Toast.LENGTH_SHORT
                            ).show()
                            finish()
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Camera capture error", exception)
                    runOnUiThread {
                        Toast.makeText(
                            this@QuickCaptureActivity,
                            "Capture error: ${exception.message}",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    }
                }
            }
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

/**
 * Compose screen for the quick capture camera view.
 * Shows a full-screen CameraX preview with a capture button and cancel button.
 */
@androidx.compose.runtime.Composable
private fun QuickCaptureScreen(
    targetHabit: String?,
    onCapture: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    var isCapturing by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // Camera preview
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                startCamera(ctx as ComponentActivity, previewView) { capture ->
                    // Store the ImageCapture instance for later use
                    (ctx as? QuickCaptureActivity)?.let { activity ->
                        activity.imageCapture = capture
                    }
                }
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Top bar: cancel button + habit name
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Cancel button (top-left)
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .size(48.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Cancel",
                        tint = Color.White
                    )
                }

                // Habit name label (top-center)
                if (targetHabit != null) {
                    Text(
                        text = targetHabit,
                        color = Color.White,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 8.dp)
                            .background(Color.Black.copy(alpha = 0.5f))
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                // Capture button (bottom-center)
                IconButton(
                    onClick = {
                        if (!isCapturing) {
                            isCapturing = true
                            onCapture()
                        }
                    },
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

                // Capturing indicator
                if (isCapturing) {
                    Text(
                        text = "Capturing…",
                        color = Color.White,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 120.dp)
                    )
                }
            }
        }
    }
}

/**
 * Starts the CameraX preview and stores the [ImageCapture] use case
 * via the [onReady] callback.
 */
private fun startCamera(
    activity: ComponentActivity,
    previewView: PreviewView,
    onReady: (ImageCapture) -> Unit
) {
    val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)

    cameraProviderFuture.addListener({
        try {
            val cameraProvider = cameraProviderFuture.get()

            // Preview use case
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            // Image capture use case
            val imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()

            // Select back camera
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                // Unbind any previous use cases
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
