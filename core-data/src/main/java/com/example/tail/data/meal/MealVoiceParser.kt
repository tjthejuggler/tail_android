package com.example.tail.data.meal

/**
 * Pure (no-Android) parser that recognises quick-capture utterances which
 * are really meal logs — e.g. **"i ate oatmeal with berries"** — and
 * resolves them to the configured meal habit.
 *
 * Used by the smart voice routing ([com.example.tail.ipc.SmartVoiceService]):
 * when a spoken/typed capture starts with the "i ate" prefix **and** the
 * Meal habit type is in use anywhere (`AppSettings.mealHabits` non-empty),
 * the utterance is routed to that habit instead of the generic
 * trigger-word/note routing.
 */
object MealVoiceParser {

    /**
     * Matches an utterance that *starts with* "i ate" (case-insensitive),
     * tolerating:
     *  - contractions / expansions: "i've ate", "i have ate"
     *  - punctuation directly after the prefix: "i ate: oats"
     *  - repeated whitespace.
     * The remainder of the utterance is the food description.
     */
    private val PREFIX = Regex(
        """^i\s*(?:'ve|have)?\s+ate\b[\s,.:;!?-]*""",
        RegexOption.IGNORE_CASE
    )

    /** A recognised meal utterance. */
    data class MealUtterance(
        /** The normalised full utterance (kept as the meal transcript). */
        val raw: String,
        /** Everything after the "i ate" prefix ("" when none was said). */
        val description: String
    )

    /**
     * Parses [text] and returns a [MealUtterance] when it starts with the
     * "i ate" prefix, or null when it is any other kind of utterance.
     */
    fun parse(text: String): MealUtterance? {
        val normalised = text.trim().replace(Regex("\\s+"), " ")
        val match = PREFIX.find(normalised) ?: return null
        val description = normalised.substring(match.value.length).trim()
        return MealUtterance(raw = normalised, description = description)
    }

    /**
     * Resolves which meal habit this utterance belongs to.
     *
     *  - Exactly one meal habit → that habit (the common case).
     *  - Multiple meal habits → a habit whose *subtype* is mentioned in the
     *    description wins (e.g. subtypes breakfast/lunch/dinner), then a
     *    habit whose *name* is mentioned, then the first configured meal
     *    habit.
     *
     * Returns null when the Meal habit type is not in use at all.
     */
    fun pickMealHabit(
        description: String,
        mealHabits: Set<String>,
        habitSubtypes: Map<String, List<String>>
    ): String? {
        if (mealHabits.isEmpty()) return null
        if (mealHabits.size == 1) return mealHabits.first()

        val desc = description.lowercase()

        for ((habit, subtypes) in habitSubtypes) {
            if (habit !in mealHabits) continue
            if (subtypes.any { desc.contains(it.lowercase()) }) return habit
        }

        mealHabits.firstOrNull { desc.contains(it.lowercase()) }?.let { return it }

        return mealHabits.first()
    }

    /**
     * Finds the meal-habit subtype mentioned in the description
     * (case-insensitive contains), or null when none matches / the habit
     * has no subtypes.
     */
    fun matchSubtype(description: String, subtypes: List<String>): String? {
        if (subtypes.isEmpty()) return null
        val desc = description.lowercase()
        return subtypes.firstOrNull { desc.contains(it.lowercase()) }
    }

    /**
     * Builds a short user-facing meal title from the description:
     * trimmed, trailing punctuation dropped, first letter capitalised and
     * capped at 60 characters.
     */
    fun buildTitle(description: String): String {
        val clean = description.trim().trimEnd('.', '!', '?', ',')
        if (clean.isEmpty()) return "Meal"
        val capped = clean.take(60) + if (clean.length > 60) "…" else ""
        return capped.replaceFirstChar { it.uppercase() }
    }
}
