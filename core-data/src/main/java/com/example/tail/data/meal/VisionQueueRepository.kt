package com.example.tail.data.meal

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.util.UUID

private const val TAG = "VisionQueueRepo"

/**
 * Manages the offline vision-processing queue in internal storage.
 *
 * The queue is a single JSON file at `files/vision_queue.json` containing a
 * list of [VisionQueueItem]s.  Captured images are stored alongside meal
 * images in `files/meal_images/` (managed by [MealLogRepository]).
 *
 * This replaces the spec's SQLite `pending_vision_queue` table — the app's
 * existing architecture uses internal-storage JSON files for all feature data
 * (see [com.example.tail.data.AiIconRepository] for the same pattern).
 */
class VisionQueueRepository(
    /** Storage root holding `vision_queue.json` (injectable for JVM tests). */
    private val baseDir: File
) {
    /** Convenience constructor for production call sites. */
    constructor(context: Context) : this(context.filesDir)

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<MutableList<VisionQueueItem>>() {}.type

    private val queueFile: File
        get() = File(baseDir, "vision_queue.json")

    /** Loads the full queue. Returns an empty list if the file doesn't exist. */
    @Synchronized
    fun loadAll(): MutableList<VisionQueueItem> {
        return try {
            if (!queueFile.exists()) return mutableListOf()
            val json = queueFile.readText()
            if (json.isBlank()) return mutableListOf()
            gson.fromJson<MutableList<VisionQueueItem>>(json, listType) ?: mutableListOf()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load vision queue", e)
            mutableListOf()
        }
    }

    /** Persists the full queue atomically. */
    @Synchronized
    private fun saveAll(items: MutableList<VisionQueueItem>) {
        try {
            queueFile.writeText(gson.toJson(items))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save vision queue", e)
        }
    }

    /**
     * Enqueues a new capture for processing.
     * @param imagePath Relative path to the cached image (within filesDir).
     * @param habitId Target habit name, or null for LLM auto-routing.
     * @param attachToMealLogId When set, the LLM result is merged into this
     *        existing meal log (close-succession grouping) instead of
     *        creating a new one — and no extra increment happens.
     * @return The created [VisionQueueItem].
     */
    @Synchronized
    fun enqueue(
        imagePath: String,
        habitId: String?,
        attachToMealLogId: String? = null
    ): VisionQueueItem {
        val item = VisionQueueItem(
            id = UUID.randomUUID().toString(),
            timestamp = System.currentTimeMillis(),
            imagePath = imagePath,
            status = VisionQueueStatus.PENDING,
            habitId = habitId,
            attachToMealLogId = attachToMealLogId
        )
        val items = loadAll()
        items.add(item)
        saveAll(items)
        QcDiag.log(
            "QUEUE",
            "enqueue item=${QcDiag.short(item.id)} habitId=${habitId ?: "NULL"} " +
                "attach=${QcDiag.short(attachToMealLogId)} image=$imagePath"
        )
        Log.i(TAG, "Enqueued vision item ${item.id} for habit=$habitId attach=$attachToMealLogId")
        return item
    }

    /** Returns all items with [VisionQueueStatus.PENDING] status. */
    @Synchronized
    fun pendingItems(): List<VisionQueueItem> =
        loadAll().filter { it.status == VisionQueueStatus.PENDING }

    /** Returns the count of pending items (for UI badge / status display). */
    @Synchronized
    fun pendingCount(): Int = pendingItems().size

    /**
     * All items not yet successfully completed, newest first — surfaced in
     * the meal details screen so the user can see exactly what is stuck,
     * why (errorLog / reviewNote), and force a reprocess.
     */
    @Synchronized
    fun unresolvedItems(): List<VisionQueueItem> =
        loadAll()
            .filter { it.status != VisionQueueStatus.COMPLETED }
            .sortedByDescending { it.timestamp }

    /**
     * Forces an item back to PENDING regardless of its current status
     * (FAILED, NEEDS_REVIEW, stuck PENDING/PROCESSING) with a full fresh
     * retry budget — used by the meal details screen's "Analyze now"
     * control. Returns true when the item was found.
     */
    @Synchronized
    fun forceRequeue(id: String): Boolean {
        val items = loadAll()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        items[idx] = items[idx].copy(
            status = VisionQueueStatus.PENDING,
            retryCount = 0,
            errorLog = null,
            reviewNote = null
        )
        saveAll(items)
        QcDiag.log("QUEUE", "item=${QcDiag.short(id)} FORCE re-queued by user (fresh retry budget)")
        return true
    }

    /**
     * Updates a single queue item by id and persists the full list.
     * @return The updated item, or null if not found.
     */
    @Synchronized
    fun updateItem(updated: VisionQueueItem): VisionQueueItem? {
        val items = loadAll()
        val idx = items.indexOfFirst { it.id == updated.id }
        if (idx < 0) return null
        items[idx] = updated
        saveAll(items)
        return updated
    }

    /**
     * Marks an item as processing (claimed by the worker).
     * @return true if the item was successfully claimed.
     */
    @Synchronized
    fun markProcessing(id: String): Boolean {
        val items = loadAll()
        val idx = items.indexOfFirst { it.id == id && it.status == VisionQueueStatus.PENDING }
        if (idx < 0) {
            QcDiag.warn(
                "QUEUE",
                "item=${QcDiag.short(id)} claim FAILED (not PENDING — already claimed or changed)"
            )
            return false
        }
        items[idx] = items[idx].copy(status = VisionQueueStatus.PROCESSING)
        saveAll(items)
        QcDiag.log("QUEUE", "item=${QcDiag.short(id)} PENDING → PROCESSING")
        return true
    }

    /** Marks an item as completed with the resulting meal log id. */
    @Synchronized
    fun markCompleted(id: String, mealLogId: String) {
        val items = loadAll()
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(
                status = VisionQueueStatus.COMPLETED,
                resultMealLogId = mealLogId
            )
            saveAll(items)
            QcDiag.log(
                "QUEUE",
                "item=${QcDiag.short(id)} → COMPLETED resultMealLog=${QcDiag.short(mealLogId)}"
            )
        }
    }

    /**
     * Marks an item as failed (or back to PENDING for retry if retryCount < maxRetries).
     * @param maxRetries Maximum number of retries before giving up.
     * @return true if the item will be retried, false if it's now permanently FAILED.
     */
    @Synchronized
    fun markFailedOrRetry(id: String, error: String, maxRetries: Int = 3): Boolean {
        val items = loadAll()
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return false
        val item = items[idx]
        val newRetryCount = item.retryCount + 1
        val willRetry = newRetryCount < maxRetries
        items[idx] = item.copy(
            status = if (willRetry) VisionQueueStatus.PENDING else VisionQueueStatus.FAILED,
            retryCount = newRetryCount,
            errorLog = "Attempt $newRetryCount: $error"
        )
        saveAll(items)
        QcDiag.warn(
            "QUEUE",
            "item=${QcDiag.short(id)} FAILED attempt=$newRetryCount/$maxRetries " +
                "willRetry=$willRetry error='${error.take(120)}'"
        )
        return willRetry
    }

    /**
     * Moves an item into NEEDS_REVIEW so it shows up in the Quick Capture
     * History with its image kept and [note] explaining what happened.
     */
    @Synchronized
    fun markNeedsReview(id: String, note: String) {
        val items = loadAll()
        val idx = items.indexOfFirst { it.id == id }
        if (idx >= 0) {
            items[idx] = items[idx].copy(
                status = VisionQueueStatus.NEEDS_REVIEW,
                reviewNote = note
            )
            saveAll(items)
            QcDiag.warn(
                "REVIEW",
                "item=${QcDiag.short(id)} → NEEDS_REVIEW: ${note.take(160)}"
            )
            Log.i(TAG, "Item $id needs review: ${note.take(120)}")
        }
    }

    /** All items waiting for the user's review, newest first. */
    @Synchronized
    fun reviewItems(): List<VisionQueueItem> =
        loadAll()
            .filter { it.status == VisionQueueStatus.NEEDS_REVIEW }
            .sortedByDescending { it.timestamp }

    /** Number of items waiting for review (app-open notification / banner). */
    @Synchronized
    fun reviewItemCount(): Int =
        loadAll().count { it.status == VisionQueueStatus.NEEDS_REVIEW }

    /**
     * Re-queues a review item for processing, optionally with the habit the
     * user says the capture was intended for. Resets the retry budget so the
     * item gets a full set of attempts again.
     */
    @Synchronized
    fun retryWithHabit(id: String, habitId: String?): Boolean {
        val items = loadAll()
        val idx = items.indexOfFirst { it.id == id && it.status == VisionQueueStatus.NEEDS_REVIEW }
        if (idx < 0) return false
        items[idx] = items[idx].copy(
            status = VisionQueueStatus.PENDING,
            habitId = habitId ?: items[idx].habitId,
            retryCount = 0,
            errorLog = null,
            reviewNote = null
        )
        saveAll(items)
        QcDiag.log(
            "QUEUE",
            "item=${QcDiag.short(id)} re-queued from review with habit=${habitId ?: "unchanged"}"
        )
        Log.i(TAG, "Item $id re-queued for retry with habit=$habitId")
        return true
    }

    /**
     * Deletes a review item AND its image file. Returns true when the item
     * was found (the image file is deleted best-effort even if missing).
     */
    @Synchronized
    fun deleteReviewItem(id: String): Boolean {
        val items = loadAll()
        val item = items.find { it.id == id }
        if (item == null) return false
        items.removeAll { it.id == id }
        saveAll(items)
        try {
            File(baseDir, item.imagePath).delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete image for item $id", e)
        }
        return true
    }

    /**
     * Returns items stuck in PROCESSING back to PENDING. Called at the start
     * of every [VisionProcessingWorker] pass — since only one pass runs at a
     * time (unique work), any PROCESSING item at that moment was orphaned by
     * a process death mid-run and would otherwise wait forever.
     */
    @Synchronized
    fun requeueStaleProcessing(): Int {
        val items = loadAll()
        var recovered = 0
        for (i in items.indices) {
            if (items[i].status == VisionQueueStatus.PROCESSING) {
                items[i] = items[i].copy(status = VisionQueueStatus.PENDING)
                recovered++
            }
        }
        if (recovered > 0) saveAll(items)
        return recovered
    }

    /**
     * Removes completed and failed items older than the given cutoff.
     * Called periodically to keep the queue file from growing unbounded.
     * NEEDS_REVIEW items are NEVER cleaned up — they stay in the Quick
     * Capture History until the user resolves them.
     */
    @Synchronized
    fun cleanupOldItems(olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        val cutoff = System.currentTimeMillis() - olderThanMs
        val items = loadAll()
        val before = items.size
        items.removeAll {
            (it.status == VisionQueueStatus.COMPLETED || it.status == VisionQueueStatus.FAILED) &&
                    it.timestamp < cutoff
        }
        if (items.size != before) saveAll(items)
    }
}
