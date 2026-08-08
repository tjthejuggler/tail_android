package com.example.tail.data.meal

/**
 * All data models for the Meal Habit Engine and Global Vision Pipeline.
 *
 * This file defines the domain primitives used by:
 *  - [MealLogRepository] — persistent meal log entries (internal storage JSON)
 *  - [VisionQueueRepository] — offline capture queue (internal storage JSON)
 *  - [VisionProcessingService] — LLM inference + JSON parsing
 *  - [VisionProcessingWorker] — WorkManager background sync
 */

// ════════════════════════════════════════════════════════════════════════════
//  Meal Log Models
// ════════════════════════════════════════════════════════════════════════════

/**
 * Estimated macronutrient breakdown for a single meal.
 */
data class Macronutrients(
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0
)

/**
 * A single logged meal entry, created either by the LLM vision pipeline
 * or by manual user entry.
 *
 * Stored as a JSON array per-habit in `files/meal_logs/<sanitised-habit-name>.json`.
 */
data class MealLog(
    /** Unique identifier (UUID-style string). */
    val id: String,
    /** The habit this log belongs to. */
    val habitId: String,
    /** Epoch milliseconds when the meal was logged. */
    val timestamp: Long,
    /** Relative path to the cached image file, or null for manual entries. */
    val imageUri: String? = null,
    /** Short user-facing title (e.g. "Vegan Buddha Bowl"). */
    val title: String,
    /** 1-2 sentence summary from the LLM or user. */
    val summary: String? = null,
    /** Estimated calories for the meal. */
    val calories: Int = 0,
    /** Protein / carbs / fat in grams. */
    val macronutrients: Macronutrients = Macronutrients(),
    /** Individual ingredient names detected by the LLM. */
    val ingredientsDetected: List<String> = emptyList(),
    /** Whether the LLM verified this as vegan (per user dietary rules). */
    val isVeganVerified: Boolean = false,
    /** Optional health note from the LLM. */
    val healthNotes: String? = null,
    /** Raw LLM JSON response, kept for debugging / re-parsing. */
    val rawLlmResponse: String? = null,
    /** True when this entry was created manually (no LLM call). */
    val isManual: Boolean = false
)

// ════════════════════════════════════════════════════════════════════════════
//  Vision Queue Models
// ════════════════════════════════════════════════════════════════════════════

/**
 * Status of a queued vision-processing item.
 */
enum class VisionQueueStatus {
    /** Waiting to be picked up by the [VisionProcessingWorker]. */
    PENDING,
    /** Currently being processed. */
    PROCESSING,
    /** Completed successfully; a [MealLog] was created. */
    COMPLETED,
    /** Failed after all retries. */
    FAILED;

    companion object {
        fun fromString(s: String?): VisionQueueStatus =
            entries.firstOrNull { it.name == s } ?: PENDING
    }
}

/**
 * A single item in the offline vision-processing queue.
 *
 * Stored as a JSON array in `files/vision_queue.json`.
 * When [habitId] is null, the LLM classification step decides which
 * meal habit (if any) the capture should be routed to.
 */
data class VisionQueueItem(
    /** Unique identifier (UUID-style string). */
    val id: String,
    /** Epoch milliseconds when the capture was queued. */
    val timestamp: Long,
    /** Relative path to the cached image file within `files/meal_images/`. */
    val imagePath: String,
    /** Current processing status. */
    val status: VisionQueueStatus = VisionQueueStatus.PENDING,
    /** Target habit name, or null for auto-routing via LLM classification. */
    val habitId: String? = null,
    /** Number of processing attempts so far. */
    val retryCount: Int = 0,
    /** Last error message (if any). */
    val errorLog: String? = null,
    /** ID of the [MealLog] created on success (for traceability). */
    val resultMealLogId: String? = null
)

// ════════════════════════════════════════════════════════════════════════════
//  LLM Vision Result Models (parsed from the multimodal LLM JSON response)
// ════════════════════════════════════════════════════════════════════════════

/**
 * The classification category returned by the LLM.
 */
enum class VisionClassification {
    FOOD_MEAL,
    NON_FOOD_HABIT,
    UNCERTAIN_OTHER;

    companion object {
        fun fromString(s: String?): VisionClassification =
            entries.firstOrNull { it.name == s } ?: UNCERTAIN_OTHER
    }
}

/**
 * Parsed food-specific data from the LLM response.
 */
data class FoodData(
    val title: String = "",
    val summary: String = "",
    val isVeganVerified: Boolean = false,
    val estimatedCalories: Int = 0,
    val macronutrients: Macronutrients = Macronutrients(),
    val ingredientsDetected: List<String> = emptyList(),
    val healthNotes: String? = null
)

/**
 * Parsed non-food data from the LLM response (future-proofing).
 */
data class NonFoodData(
    val detectedActivity: String? = null,
    val suggestedAction: String? = null
)

/**
 * The fully parsed result of a vision-processing LLM call.
 * Maps 1:1 to the JSON schema in the system prompt.
 */
data class VisionResult(
    val classification: VisionClassification = VisionClassification.UNCERTAIN_OTHER,
    val confidenceScore: Double = 0.0,
    val foodData: FoodData? = null,
    val nonFoodData: NonFoodData? = null,
    val processingNotes: String = ""
) {
    /**
     * Converts this vision result into a [MealLog] suitable for persistence.
     * Only meaningful when [classification] is [VisionClassification.FOOD_MEAL].
     */
    fun toMealLog(habitId: String, timestamp: Long, imageUri: String?, rawJson: String): MealLog? {
        if (classification != VisionClassification.FOOD_MEAL || foodData == null) return null
        val fd = foodData
        return MealLog(
            id = java.util.UUID.randomUUID().toString(),
            habitId = habitId,
            timestamp = timestamp,
            imageUri = imageUri,
            title = fd.title.ifBlank { "Unnamed Meal" },
            summary = fd.summary,
            calories = fd.estimatedCalories,
            macronutrients = fd.macronutrients,
            ingredientsDetected = fd.ingredientsDetected,
            isVeganVerified = fd.isVeganVerified,
            healthNotes = fd.healthNotes,
            rawLlmResponse = rawJson,
            isManual = false
        )
    }
}
