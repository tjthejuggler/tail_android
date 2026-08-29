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

/**
 * Failure handler: consumes a retry attempt; when the budget is exhausted
 * the item moves to NEEDS_REVIEW (Quick Capture History) instead of dying
 * silently as FAILED.
 * @return true when the item will be retried.
 */
private fun failOrReview(
    queueRepo: VisionQueueRepository,
    itemId: String,
    error: String
): Boolean {
    val willRetry = queueRepo.markFailedOrRetry(itemId, error)
    if (!willRetry) {
        queueRepo.markNeedsReview(itemId, error)
    }
    return willRetry
}

/** Delay before the worker re-runs itself after a failed attempt. */
private const val RETRY_PASS_DELAY_MS = 30_000L

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
 * On failure, the item is retried up to 3 times; once the retry budget is
 * exhausted — or the LLM can't act on the image at all — the item moves to
 * NEEDS_REVIEW so it lands in the Quick Capture History with its image
 * kept, ready for the user to assign a habit and retry.
 *
 * Call [enqueue] to trigger processing — safe to call repeatedly (uses
 * [ExistingWorkPolicy.APPEND_OR_REPLACE] so a capture enqueued while a
 * pass is already running still gets its own follow-up pass).
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
            QcDiag.error("WORKER", "pass ABORTED: settings load FAILED — Result.retry()", e)
            Log.e(TAG, "Failed to load settings", e)
            return Result.retry()
        }
        QcDiag.log(
            "WORKER",
            "pass start (runAttempt=$runAttemptCount): ${QcDiag.routingSnapshot(settings)}"
        )
        QcDiag.log("WORKER", QcDiag.mismatchHints(settings))

        // Check if meal engine is configured
        if (!settings.mealEnabled || settings.mealApiKey.isBlank() ||
            settings.mealBaseUrl.isBlank() || settings.mealModel.isBlank()
        ) {
            QcDiag.warn(
                "WORKER",
                "SKIP whole pass: meal engine not configured — ${queueRepo.pendingCount()} " +
                    "item(s) stay PENDING (no review, no toast). " +
                    "mealEnabled=${settings.mealEnabled} " +
                    "baseUrlSet=${settings.mealBaseUrl.isNotBlank()} " +
                    "apiKeySet=${settings.mealApiKey.isNotBlank()} " +
                    "modelSet=${settings.mealModel.isNotBlank()}"
            )
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
        QcDiag.log(
            "WORKER",
            "singleCameraHabit=${singleCameraHabit ?: "NULL (no deterministic default at worker level)"}"
        )

        // Recover items orphaned in PROCESSING by a process death mid-run
        // (only one pass runs at a time, so any PROCESSING item here is
        // stale) and clean up old completed/failed entries.
        val requeuedCount = queueRepo.requeueStaleProcessing()
        if (requeuedCount > 0) {
            QcDiag.warn(
                "WORKER",
                "requeued $requeuedCount stale PROCESSING item(s) (orphaned by process death)"
            )
        }
        queueRepo.cleanupOldItems()

        // Process all pending items
        val pending = queueRepo.pendingItems()
        QcDiag.log("WORKER", "processing ${pending.size} pending item(s)")
        Log.i(TAG, "Processing ${pending.size} pending vision items")

        var processed = 0
        var failed = 0
        var anyWillRetry = false

        for (item in pending) {
            // Claim the item (atomic transition PENDING → PROCESSING)
            if (!queueRepo.markProcessing(item.id)) {
                Log.d(TAG, "Item ${item.id} was already claimed or no longer pending, skipping")
                continue
            }

            try {
                val imageFile = File(appContext.filesDir, item.imagePath)
                QcDiag.log(
                    "ITEM",
                    "item=${QcDiag.short(item.id)} claimed: habitId=${item.habitId ?: "NULL"} " +
                        "attach=${QcDiag.short(item.attachToMealLogId)} retry=${item.retryCount} " +
                        "ageMs=${System.currentTimeMillis() - item.timestamp} " +
                        "image=${item.imagePath} exists=${imageFile.exists()} " +
                        "bytes=${if (imageFile.exists()) imageFile.length() else -1}"
                )
                if (!imageFile.exists()) {
                    QcDiag.error(
                        "ITEM",
                        "item=${QcDiag.short(item.id)} IMAGE MISSING at ${item.imagePath} → review"
                    )
                    Log.e(TAG, "Image file not found: ${item.imagePath}")
                    failOrReview(queueRepo, item.id, "Image file not found")
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
                QcDiag.log(
                    "ROUTE",
                    "item=${QcDiag.short(item.id)} forcedMealHabit=${forcedMealHabit ?: "NULL"} " +
                        "(itemHabitId=${item.habitId ?: "null"} " +
                        "itemHabitInMealHabits=${item.habitId?.let {
                            settings.mealHabits.contains(it)
                        } ?: "n/a"} " +
                        "singleCameraHabit=${singleCameraHabit ?: "null"} " +
                        "singleInMealHabits=${singleCameraHabit?.let {
                            settings.mealHabits.contains(it)
                        } ?: "n/a"} " +
                        "mealHabits=${settings.mealHabits.toList()})"
                )

                val llmStart = System.currentTimeMillis()
                val result = if (forcedMealHabit != null) {
                    QcDiag.log(
                        "LLM",
                        "item=${QcDiag.short(item.id)} DETERMINISTIC meal pipeline for " +
                            "'$forcedMealHabit' (no habit guessing)"
                    )
                    Log.i(TAG, "Item ${item.id}: deterministic meal pipeline for '$forcedMealHabit'")
                    visionService.processImage(imageFile, config)
                } else {
                    // Run the vision pipeline — with the learned memory and the
                    // valid habit list injected so smart auto-detection works
                    // in the background queue too.
                    val memoryPrompt = memoryRepo.buildMemoryPrompt().ifBlank { null }
                    val habitPrompt = VisionHabitExecutor.buildHabitPrompt(settings).ifBlank { null }
                    QcDiag.warn(
                        "LLM",
                        "item=${QcDiag.short(item.id)} CLASSIFICATION pipeline (habit guessing) — " +
                            "memoryPrompt=${memoryPrompt != null} " +
                            "habitPrompt=${habitPrompt != null}(${habitPrompt?.length ?: 0} chars)"
                    )
                    visionService.processImage(imageFile, config, memoryPrompt, habitPrompt)
                }
                QcDiag.log(
                    "LLM",
                    "item=${QcDiag.short(item.id)} vision result in " +
                        "${System.currentTimeMillis() - llmStart}ms: " +
                        "classification=${result?.classification} " +
                        "food=${result?.foodData?.let {
                            "${it.title}/${it.estimatedCalories}cal"
                        } ?: "none"} " +
                        "habitAction=${result?.habitAction?.let { ha ->
                            "${ha.habitName}" + (ha.subtypeName?.let { s -> "/$s" } ?: "") +
                                "x${ha.amount}"
                        } ?: "none"} " +
                        "confidence=${result?.confidenceScore} " +
                        "notes=${result?.processingNotes?.take(150) ?: ""}"
                )
                if (result == null) {
                    val willRetry = failOrReview(queueRepo, item.id, "Vision service returned null")
                    anyWillRetry = anyWillRetry || willRetry
                    failed++
                    QcDiag.warn(
                        "LLM",
                        "item=${QcDiag.short(item.id)} vision returned NULL " +
                            "(network failure?) willRetry=$willRetry"
                    )
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
                        QcDiag.warn(
                            "REVIEW",
                            "item=${QcDiag.short(item.id)} LLM proposed " +
                                "'${action.habitName}' (${action.subtypeName}) — NOT resolvable " +
                                "against camera-eligible habits → needs review"
                        )
                        Log.w(TAG, "Item ${item.id}: proposed habit '${action.habitName}' not found — no action")
                        queueRepo.markNeedsReview(
                            item.id,
                            "No camera habit matched. LLM proposed '${action.habitName}'." +
                                " Saw: ${seen.removePrefix("\n").ifBlank { "unknown" }}"
                        )
                        failed++
                        notifyCameraResult(appContext, "📷 No camera habit matched$seen\nSaved to Quick Capture History")
                    } else {
                        val (realHabit, realSubtype) = resolved
                        QcDiag.log(
                            "INCREMENT",
                            "item=${QcDiag.short(item.id)} auto-detect executing " +
                                "'$realHabit'${realSubtype?.let { "/$it" } ?: ""} x${action.amount}"
                        )
                        val err = VisionHabitExecutor.execute(
                            appContext, settings, realHabit, realSubtype, action.amount
                        )
                        if (err == null) {
                            QcDiag.log(
                                "INCREMENT",
                                "item=${QcDiag.short(item.id)} auto-detect OK → '$realHabit'"
                            )
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
                            QcDiag.error(
                                "INCREMENT",
                                "item=${QcDiag.short(item.id)} auto-detect execute FAILED: $err"
                            )
                            val willRetry = failOrReview(queueRepo, item.id, err)
                            anyWillRetry = anyWillRetry || willRetry
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
                        QcDiag.log(
                            "MEAL",
                            "item=${QcDiag.short(item.id)} ATTACHED analysis to existing meal " +
                                "${QcDiag.short(existing.id)} '${existing.title}' (no new increment)"
                        )
                        Log.i(TAG, "Attached analysis to meal ${existing.id}: ${existing.title}")
                        continue
                    }
                    // Target log vanished (deleted meanwhile) — fall through to
                    // the normal create path so the capture isn't lost.
                    QcDiag.warn(
                        "MEAL",
                        "item=${QcDiag.short(item.id)} attach target " +
                            "${QcDiag.short(item.attachToMealLogId)} NOT found — creating new log"
                    )
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
                    val candidateGroup = mealLogRepo.findActiveGroup(targetHabit, item.timestamp)
                    val activeGroup = candidateGroup
                        ?.takeIf {
                            kotlin.math.abs(item.timestamp - it.anchorTime()) <= MEAL_GROUP_WINDOW_MS
                        }
                    if (candidateGroup != null && activeGroup == null) {
                        QcDiag.log(
                            "MEAL",
                            "item=${QcDiag.short(item.id)} group ${QcDiag.short(candidateGroup.id)} " +
                                "found but OUTSIDE worker window (delta=" +
                                "${kotlin.math.abs(item.timestamp - candidateGroup.anchorTime())}ms " +
                                "> $MEAL_GROUP_WINDOW_MS ms) → new meal"
                        )
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
                        QcDiag.log(
                            "MEAL",
                            "item=${QcDiag.short(item.id)} MERGED into active meal " +
                                "${QcDiag.short(activeGroup.id)} '${activeGroup.title}' " +
                                "(no new increment)"
                        )
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
                        QcDiag.log(
                            "MEAL",
                            "item=${QcDiag.short(item.id)} CREATED meal log " +
                                "${QcDiag.short(mealLog.id)} '${mealLog.title}' " +
                                "(${mealLog.calories} cal) for '$targetHabit'"
                        )

                        // Increment the habit count for the meal's day
                        if (settings.fileUri.isNotEmpty()) {
                            try {
                                val mealDate = Instant.ofEpochMilli(mealLog.timestamp)
                                    .atZone(ZoneId.systemDefault()).toLocalDate()
                                QcDiag.log(
                                    "INCREMENT",
                                    "item=${QcDiag.short(item.id)} incrementing '$targetHabit' " +
                                        "mealDate=$mealDate " +
                                        "(${if (mealDate == LocalDate.now()) "today" else "backfill"})"
                                )
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
                                QcDiag.log(
                                    "INCREMENT",
                                    "item=${QcDiag.short(item.id)} incremented '$targetHabit' +1 " +
                                        "and recorded timestamp for meal '${mealLog.title}'"
                                )
                            } catch (e: Exception) {
                                QcDiag.error(
                                    "INCREMENT",
                                    "item=${QcDiag.short(item.id)} increment FAILED for " +
                                        "'$targetHabit' (meal log still saved): ${e.message}",
                                    e
                                )
                                Log.e(TAG, "Failed to increment habit '$targetHabit'", e)
                                // The meal log is still saved; just the count increment failed
                            }
                        }

                        queueRepo.markCompleted(item.id, mealLog.id)
                        processed++
                        Log.i(TAG, "Processed meal: ${mealLog.title} (${mealLog.calories} cal)")
                    }
                } else {
                    // Non-food or uncertain — the capture couldn't be acted
                    // on automatically. NEVER a silent dead end: keep the
                    // image in the Quick Capture History (NEEDS_REVIEW) so
                    // the user can assign the intended habit and retry.
                    val notes = when (result.classification) {
                        VisionClassification.NON_FOOD_HABIT ->
                            "Non-food detected: ${result.nonFoodData?.detectedActivity ?: "unknown"}"
                        VisionClassification.UNCERTAIN_OTHER ->
                            "Uncertain: ${result.processingNotes}"
                        else -> "No food data extracted"
                    }
                    QcDiag.warn(
                        "REVIEW",
                        "item=${QcDiag.short(item.id)} NOT actionable → NEEDS_REVIEW: " +
                            "classification=${result.classification} " +
                            "forcedMealHabit=${forcedMealHabit != null} notes=$notes"
                    )
                    queueRepo.markNeedsReview(item.id, notes)
                    Log.i(TAG, "Item ${item.id} classified as ${result.classification}: $notes — needs review")
                    failed++
                    val prefix = if (forcedMealHabit != null) {
                        "📷 Not recognised as food"
                    } else {
                        "📷 No camera habit matched"
                    }
                    notifyCameraResult(
                        appContext,
                        prefix + visionSeenDescription(result) + "\nSaved to Quick Capture History"
                    )
                }
            } catch (e: Exception) {
                QcDiag.error(
                    "ITEM",
                    "item=${QcDiag.short(item.id)} EXCEPTION: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                    e
                )
                Log.e(TAG, "Error processing item ${item.id}", e)
                val willRetry = failOrReview(queueRepo, item.id, e.message ?: "Unknown error")
                anyWillRetry = anyWillRetry || willRetry
                failed++
            }
        }

        QcDiag.log(
            "WORKER",
            "pass complete: processed=$processed failed=$failed anyWillRetry=$anyWillRetry " +
                "(pendingRemaining=${queueRepo.pendingCount()} needsReview=${queueRepo.reviewItemCount()})"
        )
        Log.i(TAG, "Vision processing complete: $processed processed, $failed failed")

        // CRITICAL: an item that failed with retries remaining goes back to
        // PENDING, but nothing else would ever trigger another pass — the
        // next capture might be hours away. Schedule our own follow-up pass
        // so retried items actually get retried.
        if (anyWillRetry) {
            QcDiag.log(
                "WORKER",
                "scheduling follow-up pass in ${RETRY_PASS_DELAY_MS / 1000}s " +
                    "(item(s) awaiting retry)"
            )
            enqueueDelayed(appContext, RETRY_PASS_DELAY_MS)
        }
        return Result.success()
    }

    companion object {
        /**
         * Enqueues the vision processing worker with a CONNECTED network constraint.
         * Uses [ExistingWorkPolicy.APPEND_OR_REPLACE] so a capture enqueued
         * while a pass is already running is guaranteed its own follow-up
         * pass (KEEP could leave the newest capture unprocessed).
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
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
            Log.i(TAG, "Vision processing worker enqueued")
        }

        /**
         * Schedules a follow-up pass after [delayMs] — used to retry items
         * that failed with retry budget remaining (nothing else would
         * trigger a new pass otherwise).
         */
        fun enqueueDelayed(context: Context, delayMs: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<VisionProcessingWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME + "_retry",
                ExistingWorkPolicy.REPLACE,
                request
            )
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
