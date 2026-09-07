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
 * Simple 1-3 subjective rating per macro (0 = unset).
 * 1 = low, 2 = moderate, 3 = high — relative to the meal's size.
 *
 * Set automatically by the LLM when it analyses a photo/voice description;
 * fully editable by the user for manual entries (works without the AI too).
 */
data class MacroRatings(
    val protein: Int = 0,
    val carbs: Int = 0,
    val fat: Int = 0
) {
    fun isSet(): Boolean = protein > 0 || carbs > 0 || fat > 0

    companion object {
        fun clamp(v: Int): Int = v.coerceIn(0, 3)
        fun fromJsonObj(obj: org.json.JSONObject?): MacroRatings? {
            if (obj == null) return null
            val r = MacroRatings(
                protein = clamp(obj.optInt("protein", 0)),
                carbs = clamp(obj.optInt("carbs", 0)),
                fat = clamp(obj.optInt("fat", 0))
            )
            return if (r.isSet()) r else null
        }
    }
}

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
    @Deprecated("Use imageList() / imageUris — kept for JSON backward compatibility")
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
    val isManual: Boolean = false,
    /** All photos attached to this meal (close-succession captures merge into one meal). */
    val imageUris: List<String>? = null,
    /** Spoken description transcript (voice-only meals / voice memos). */
    val voiceTranscript: String? = null,
    /** Simple 1-3 macro ratings (AI-set, manually editable). Null = unset. */
    val macroRatings: MacroRatings? = null,
    /**
     * Anchor time of the meal group this log belongs to. Null on the FIRST
     * log of a group (its own [timestamp] is the anchor). Set when subsequent
     * captures within the 1-hour window merge into an existing meal.
     */
    val groupStartTimestamp: Long? = null,
    /**
     * True when creating this log incremented the habit counter. Deleting the
     * log then rolls the increment (and its timestamp) back.
     */
    val countedIncrement: Boolean = false
) {
    /** All images attached to this meal (migrates the legacy single [imageUri]). */
    fun imageList(): List<String> =
        imageUris?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(imageUri)

    /** The anchor time of this meal's capture group. */
    fun anchorTime(): Long = groupStartTimestamp ?: timestamp

    /** True while the meal has no nutrition specifics yet (invites editing). */
    fun needsDetails(): Boolean =
        calories == 0 &&
            ingredientsDetected.isEmpty() &&
            summary.isNullOrBlank() &&
            (macroRatings == null || !macroRatings.isSet())

    /**
     * Returns a copy of this log with new capture information merged in —
     * used when photos/voice/LLM data arrive for a meal that is already
     * being tracked (the 1-hour close-succession grouping).
     *
     * When this log [needsDetails], the LLM data REPLACES the placeholder;
     * otherwise (a second course within the group) values are AGGREGATED
     * (calories/macros summed, tags unioned, photos appended).
     */
    fun mergedWith(
        foodData: FoodData? = null,
        extraImageUri: String? = null,
        transcript: String? = null,
        newTimestamp: Long? = null
    ): MealLog {
        val images = imageList().toMutableList()
        if (extraImageUri != null && extraImageUri !in images) images.add(extraImageUri)

        val wasPlaceholder = needsDetails()
        val fd = foodData
        val newCalories = when {
            fd == null -> calories
            wasPlaceholder -> fd.estimatedCalories
            else -> calories + fd.estimatedCalories
        }
        val oldMacros = macronutrients
        val fdMacros = fd?.macronutrients
        val newMacros = if (fdMacros == null) oldMacros else Macronutrients(
            proteinGrams = oldMacros.proteinGrams + fdMacros.proteinGrams,
            carbsGrams = oldMacros.carbsGrams + fdMacros.carbsGrams,
            fatGrams = oldMacros.fatGrams + fdMacros.fatGrams
        )
        val newTitle = when {
            fd == null -> title
            wasPlaceholder -> fd.title.ifBlank { title }
            else -> title // keep the first course's name for the group
        }
        val newSummary = when {
            fd?.summary.isNullOrBlank() -> summary
            wasPlaceholder || summary.isNullOrBlank() -> fd!!.summary
            else -> summary
        }
        val newTranscript = listOfNotNull(voiceTranscript, transcript)
            .filter { it.isNotBlank() }
            .joinToString(" / ")

        return copy(
            timestamp = newTimestamp ?: timestamp,
            imageUri = images.firstOrNull() ?: imageUri,
            imageUris = images,
            title = newTitle,
            summary = newSummary,
            calories = newCalories,
            macronutrients = newMacros,
            ingredientsDetected = (ingredientsDetected + (fd?.ingredientsDetected ?: emptyList()))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            isVeganVerified = isVeganVerified || (fd?.isVeganVerified ?: false),
            healthNotes = healthNotes ?: fd?.healthNotes,
            voiceTranscript = newTranscript.ifBlank { null },
            macroRatings = fd?.macroRatings ?: macroRatings,
            groupStartTimestamp = anchorTime(),
            isManual = isManual && fd == null,
            rawLlmResponse = fd?.toString() ?: rawLlmResponse
        )
    }
}

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
    FAILED,
    /**
     * The image could not be acted on automatically (not recognised as
     * food, no camera habit matched, or processing failed permanently).
     * The image is KEPT and surfaced in the Quick Capture History so
     * the user can assign the intended habit and retry — never a silent
     * dead end. Excluded from [VisionProcessingWorker] processing and
     * from periodic cleanup until resolved.
     */
    NEEDS_REVIEW;

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
    val resultMealLogId: String? = null,
    /**
     * When set, the LLM result is MERGED into this existing meal log
     * (close-succession capture grouping / gallery attach) instead of
     * creating a new log — and no additional habit increment happens.
     */
    val attachToMealLogId: String? = null,
    /**
     * Human-readable reason the item landed in NEEDS_REVIEW (what the
     * LLM saw / why processing failed). Shown in the Quick Capture
     * History so the user can make an informed habit assignment.
     */
    val reviewNote: String? = null
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
    val healthNotes: String? = null,
    /** Simple 1-3 macro ratings the LLM sets automatically. Null when unset. */
    val macroRatings: MacroRatings? = null
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
            imageUris = listOfNotNull(imageUri),
            title = fd.title.ifBlank { "Unnamed Meal" },
            summary = fd.summary,
            calories = fd.estimatedCalories,
            macronutrients = fd.macronutrients,
            ingredientsDetected = fd.ingredientsDetected,
            isVeganVerified = fd.isVeganVerified,
            healthNotes = fd.healthNotes,
            voiceTranscript = null,
            macroRatings = fd.macroRatings,
            rawLlmResponse = rawJson,
            isManual = false,
            countedIncrement = true
        )
    }
}
