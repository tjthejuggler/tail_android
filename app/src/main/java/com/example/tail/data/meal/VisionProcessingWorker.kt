package com.example.tail.data.meal

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.LocalDate

private const val TAG = "VisionWorker"
private const val UNIQUE_WORK_NAME = "vision_processing"

/**
 * Background [CoroutineWorker] that drains the [VisionQueueRepository] by
 * sending each pending image through [VisionProcessingService].
 *
 * Requires a connected network (the LLM call needs internet). On success,
 * a [MealLog] is created and the associated meal habit is incremented.
 * On failure, the item is retried up to 3 times before being marked FAILED.
 *
 * Call [enqueue] to trigger processing — safe to call repeatedly (uses
 * [ExistingWorkPolicy.KEEP] so only one processing pass runs at a time).
 */
class VisionProcessingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val appContext = applicationContext
        val settingsRepo = SettingsRepository(appContext)
        val queueRepo = VisionQueueRepository(appContext)
        val mealLogRepo = MealLogRepository(appContext)
        val habitsRepo = HabitsRepository()
        val visionService = VisionProcessingService()

        // Load current settings
        val settings = try {
            settingsRepo.settingsFlow.first()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings", e)
            return Result.retry()
        }

        // Check if meal engine is configured
        if (!settings.mealEnabled || settings.mealApiKey.isBlank() ||
            settings.mealBaseUrl.isBlank() || settings.mealModel.isBlank()
        ) {
            Log.w(TAG, "Meal engine not configured — skipping ${queueRepo.pendingCount()} items")
            return Result.success()
        }

        val config = VisionConfig(
            baseUrl = settings.mealBaseUrl,
            apiKey = settings.mealApiKey,
            model = settings.mealModel,
            userSystemPrompt = settings.mealSystemPrompt
        )

        // Cleanup old completed/failed items periodically
        queueRepo.cleanupOldItems()

        // Process all pending items
        val pending = queueRepo.pendingItems()
        Log.i(TAG, "Processing ${pending.size} pending vision items")

        var processed = 0
        var failed = 0

        for (item in pending) {
            // Claim the item (atomic transition PENDING → PROCESSING)
            if (!queueRepo.markProcessing(item.id)) {
                Log.d(TAG, "Item ${item.id} was already claimed or no longer pending, skipping")
                continue
            }

            try {
                val imageFile = File(appContext.filesDir, item.imagePath)
                if (!imageFile.exists()) {
                    Log.e(TAG, "Image file not found: ${item.imagePath}")
                    queueRepo.markFailedOrRetry(item.id, "Image file not found")
                    failed++
                    continue
                }

                // Run the vision pipeline
                val result = visionService.processImage(imageFile, config)
                if (result == null) {
                    val willRetry = queueRepo.markFailedOrRetry(item.id, "Vision service returned null")
                    failed++
                    Log.w(TAG, "Item ${item.id} failed (willRetry=$willRetry)")
                    continue
                }

                // Determine target habit
                val targetHabit = item.habitId ?: autoRouteHabit(result, settings.mealHabits)

                if (result.classification == VisionClassification.FOOD_MEAL &&
                    result.foodData != null && targetHabit != null
                ) {
                    // Create the meal log
                    val mealLog = result.toMealLog(
                        habitId = targetHabit,
                        timestamp = item.timestamp,
                        imageUri = item.imagePath,
                        rawJson = result.toString()
                    )

                    if (mealLog != null) {
                        mealLogRepo.addLog(mealLog)

                        // Increment the habit count for today
                        if (settings.fileUri.isNotEmpty()) {
                            try {
                                habitsRepo.incrementHabit(
                                    Uri.parse(settings.fileUri),
                                    appContext,
                                    targetHabit,
                                    1
                                )
                                Log.i(TAG, "Incremented habit '$targetHabit' for meal: ${mealLog.title}")
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to increment habit '$targetHabit'", e)
                                // The meal log is still saved; just the count increment failed
                            }
                        }

                        queueRepo.markCompleted(item.id, mealLog.id)
                        processed++
                        Log.i(TAG, "Processed meal: ${mealLog.title} (${mealLog.calories} cal)")
                    }
                } else {
                    // Non-food or uncertain — mark completed without creating a meal log
                    val notes = when (result.classification) {
                        VisionClassification.NON_FOOD_HABIT ->
                            "Non-food detected: ${result.nonFoodData?.detectedActivity ?: "unknown"}"
                        VisionClassification.UNCERTAIN_OTHER ->
                            "Uncertain: ${result.processingNotes}"
                        else -> "No food data extracted"
                    }
                    queueRepo.markCompleted(item.id, "none")
                    Log.i(TAG, "Item ${item.id} classified as ${result.classification}: $notes")
                    processed++
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error processing item ${item.id}", e)
                queueRepo.markFailedOrRetry(item.id, e.message ?: "Unknown error")
                failed++
            }
        }

        Log.i(TAG, "Vision processing complete: $processed processed, $failed failed")
        return Result.success()
    }

    companion object {
        /**
         * Enqueues the vision processing worker with a CONNECTED network constraint.
         * Uses [ExistingWorkPolicy.KEEP] so multiple enqueues collapse into one.
         */
        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<VisionProcessingWorker>()
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Vision processing worker enqueued")
        }

        /**
         * If the queue item has no explicit habit assignment, auto-route based
         * on the LLM classification. If it's food and there's exactly one meal
         * habit configured, route to it. If multiple meal habits exist, route
         * to the first one (user can reassign later).
         *
         * @return The habit name to assign, or null if no meal habits are configured.
         */
        private fun autoRouteHabit(
            result: VisionResult,
            mealHabits: Set<String>
        ): String? {
            if (result.classification != VisionClassification.FOOD_MEAL) return null
            return mealHabits.firstOrNull()
        }
    }
}
