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
 * A habit-increment action proposed by the LLM for a non-food image —
 * either matched against a learned [VisionMemoryEntry] or unambiguously
 * derived from the image content (e.g. a labeled pill bottle → "Pills"
 * habit with a "Glutamine" subtype).
 *
 * The app **validates** the proposed names against the real habit /
 * subtype configuration before executing anything — the LLM never
 * increments directly.
 */
data class HabitAction(
    val habitName: String,
    val subtypeName: String? = null,
    val amount: Int = 1,
    val reasoning: String = ""
)

/**
 * Parsed result of a tandem (photo + spoken instruction) teaching call.
 * The LLM extracts which habit the user wants associated with this kind
 * of image, plus a generalized visual description for future matching.
 */
data class TeachingResult(
    /** True when the LLM understood the instruction and resolved a habit. */
    val understood: Boolean = false,
    /** The habit the user wants incremented (must be validated by the app). */
    val habitName: String? = null,
    /** Optional subtype within the habit. */
    val subtypeName: String? = null,
    /** Increment amount parsed from the instruction (default 1). */
    val amount: Int = 1,
    /** Generalized description of the photo subject for future recognition. */
    val visualDescription: String = "",
    /** One-line human-readable summary of the learned association. */
    val summary: String = "",
    /** Free-form notes / clarifications from the LLM. */
    val notes: String = ""
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
    /** Habit-increment action proposed by the LLM (validated before execution). */
    val habitAction: HabitAction? = null,
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
