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
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "VisionWorker"
private const val UNIQUE_WORK_NAME = "vision_processing"

/** Short "what the LLM saw" suffix for toasts/logs. Empty when unknown. */
private fun visionSeenDescription(result: VisionResult): String {
    val desc = result.nonFoodData?.detectedActivity
        ?: result.processingNotes.removePrefix("Description:").trim()
    return if (desc.isBlank()) "" else "\n${desc.take(120)}"
}

/** Shows a toast from the background worker (hops to the main thread). */
private fun notifyCameraResult(context: Context, message: String) {
    try {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to show camera result toast", e)
    }
}

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
        val memoryRepo = VisionMemoryRepository(appContext)
        val habitsRepo = HabitsRepository()
        val timestampRepo = HabitTimestampRepository(appContext)
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

        // Single camera-eligible habit ⇒ every untargeted capture is
        // unambiguously for it (see deterministic meal routing in the loop).
        val singleCameraHabit = VisionHabitExecutor.cameraEligibleHabits(settings).singleOrNull()

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

                // ── Deterministic meal routing ─────────────────────────────────
                // When the capture's target habit is already known — either
                // explicitly (item.habitId from the meal screen / quick capture
                // with EXTRA_HABIT_NAME) or because exactly ONE camera-eligible
                // habit exists — and that habit is a meal habit, the image is
                // unambiguously a meal photo for it. Run the EXACT pipeline the
                // manual editor uses (MealPhotoAnalyser): plain food-analysis
                // prompt, no habit list, no learned memory, no habit guessing.
                // This keeps quick capture 100% identical to "pick the habit →
                // add photo (AI)" and immune to LLM hedging on classification.
                val forcedMealHabit = if (item.habitId != null) {
                    item.habitId?.takeIf { settings.mealHabits.contains(it) }
                } else {
                    singleCameraHabit?.takeIf { settings.mealHabits.contains(it) }
                }

                val result = if (forcedMealHabit != null) {
                    Log.i(TAG, "Item ${item.id}: deterministic meal pipeline for '$forcedMealHabit'")
                    visionService.processImage(imageFile, config)
                } else {
                    // Run the vision pipeline — with the learned memory and the
                    // valid habit list injected so smart auto-detection works
                    // in the background queue too.
                    val memoryPrompt = memoryRepo.buildMemoryPrompt().ifBlank { null }
                    val habitPrompt = VisionHabitExecutor.buildHabitPrompt(settings).ifBlank { null }
                    visionService.processImage(imageFile, config, memoryPrompt, habitPrompt)
                }
                if (result == null) {
                    val willRetry = queueRepo.markFailedOrRetry(item.id, "Vision service returned null")
                    failed++
                    Log.w(TAG, "Item ${item.id} failed (willRetry=$willRetry)")
                    continue
                }

                // Smart auto-detection: execute the LLM's proposed habit
                // action. The candidate list is restricted to camera-enabled
                // habits (VisionHabitExecutor.buildHabitPrompt) and the LLM is
                // instructed to always pick its best guess among them — so no
                // confidence gate is applied. The user sees what was
                // recognized via the toast and can undo a wrong guess.
                // Habit guessing only applies when the target wasn't already
                // deterministic — a forced meal capture goes straight to the
                // meal-log path below.
                if (forcedMealHabit == null &&
                    result.classification != VisionClassification.FOOD_MEAL &&
                    result.habitAction != null
                ) {
                    val action = result.habitAction!!
                    val seen = visionSeenDescription(result)
                    val resolved = VisionHabitExecutor.resolveHabitAction(
                        settings, action.habitName, action.subtypeName
                    )
                    if (resolved == null) {
                        Log.w(TAG, "Item ${item.id}: proposed habit '${action.habitName}' not found — no action")
                        queueRepo.markCompleted(item.id, "none")
                        processed++
                        notifyCameraResult(appContext, "📷 No camera habit matched$seen")
                    } else {
                        val (realHabit, realSubtype) = resolved
                        val err = VisionHabitExecutor.execute(
                            appContext, settings, realHabit, realSubtype, action.amount
                        )
                        if (err == null) {
                            Log.i(TAG, "Item ${item.id}: auto-detected → $realHabit" +
                                (realSubtype?.let { "/$it" } ?: "") + " ×${action.amount}")
                            queueRepo.markCompleted(item.id, "habit:$realHabit")
                            processed++
                            notifyCameraResult(
                                appContext,
                                "📷 $realHabit" + (realSubtype?.let { " ($it)" } ?: "") +
                                    " +${action.amount}$seen"
                            )
                        } else {
                            val willRetry = queueRepo.markFailedOrRetry(item.id, err)
                            failed++
                        }
                    }
                    continue
                }

                // Determine target habit
                val targetHabit = forcedMealHabit
                    ?: item.habitId
                    ?: autoRouteHabit(result, settings.mealHabits)

                // ── Attach path: the image was already attached to an existing
                // meal (close-succession grouping / gallery attach). Merge the
                // LLM's analysis into that meal — no new log, no increment.
                if (result.classification == VisionClassification.FOOD_MEAL &&
                    result.foodData != null &&
                    item.attachToMealLogId != null && targetHabit != null
                ) {
                    val existing = mealLogRepo.loadLogs(targetHabit)
                        .find { it.id == item.attachToMealLogId }
                    if (existing != null) {
                        mealLogRepo.updateLog(existing.mergedWith(foodData = result.foodData))
                        queueRepo.markCompleted(item.id, existing.id)
                        processed++
                        Log.i(TAG, "Attached analysis to meal ${existing.id}: ${existing.title}")
                        continue
                    }
                    // Target log vanished (deleted meanwhile) — fall through to
                    // the normal create path so the capture isn't lost.
                    Log.w(TAG, "Attach target ${item.attachToMealLogId} not found — creating new log")
                }

                if (result.classification == VisionClassification.FOOD_MEAL &&
                    result.foodData != null && targetHabit != null
                ) {
                    // A meal group may have OPENED after this capture was
                    // enqueued (e.g. a voice log landed while the photo sat in
                    // the queue). Merge into it instead of creating a second
                    // log + increment + stamp for the same meal — the source
                    // of duplicate Eat chips at the same minute.
                    val activeGroup = mealLogRepo.findActiveGroup(targetHabit, item.timestamp)
                        ?.takeIf {
                            kotlin.math.abs(item.timestamp - it.anchorTime()) <= MEAL_GROUP_WINDOW_MS
                        }
                    if (activeGroup != null) {
                        mealLogRepo.updateLog(
                            activeGroup.mergedWith(
                                foodData = result.foodData,
                                extraImageUri = item.imagePath
                            )
                        )
                        queueRepo.markCompleted(item.id, activeGroup.id)
                        processed++
                        Log.i(TAG, "Merged queued photo into active meal ${activeGroup.id}: ${activeGroup.title}")
                        continue
                    }

                    // Create the meal log
                    val mealLog = result.toMealLog(
                        habitId = targetHabit,
                        timestamp = item.timestamp,
                        imageUri = item.imagePath,
                        rawJson = result.toString()
                    )

                    if (mealLog != null) {
                        mealLogRepo.addLog(mealLog)

                        // Increment the habit count for the meal's day
                        if (settings.fileUri.isNotEmpty()) {
                            try {
                                val mealDate = Instant.ofEpochMilli(mealLog.timestamp)
                                    .atZone(ZoneId.systemDefault()).toLocalDate()
                                if (mealDate == LocalDate.now()) {
                                    habitsRepo.incrementHabit(
                                        Uri.parse(settings.fileUri),
                                        appContext,
                                        targetHabit,
                                        1
                                    )
                                } else {
                                    habitsRepo.incrementHabitForDate(
                                        Uri.parse(settings.fileUri),
                                        appContext,
                                        targetHabit,
                                        1,
                                        mealDate
                                    )
                                }
                                // Record the increment timestamp so manually
                                // captured meals are timestamped too
                                timestampRepo.addTimestamp(
                                    habitName = targetHabit,
                                    date = mealDate,
                                    time = java.time.LocalTime.ofInstant(
                                        Instant.ofEpochMilli(mealLog.timestamp),
                                        ZoneId.systemDefault()
                                    ).format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
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
                    // The LLM saw something it couldn't tie to any camera
                    // habit — tell the user what it saw instead of failing
                    // silently.
                    if (result.classification != VisionClassification.FOOD_MEAL) {
                        val prefix = if (forcedMealHabit != null) {
                            "📷 Not recognised as food"
                        } else {
                            "📷 No camera habit matched"
                        }
                        notifyCameraResult(appContext, prefix + visionSeenDescription(result))
                    }
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
