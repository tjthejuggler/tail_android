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
class VisionQueueRepository(private val context: Context) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()
    private val listType = object : TypeToken<MutableList<VisionQueueItem>>() {}.type

    private val queueFile: File
        get() = File(context.filesDir, "vision_queue.json")

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
        if (idx < 0) return false
        items[idx] = items[idx].copy(status = VisionQueueStatus.PROCESSING)
        saveAll(items)
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
        return willRetry
    }

    /**
     * Removes completed and failed items older than the given cutoff.
     * Called periodically to keep the queue file from growing unbounded.
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
