package com.example.tail.data.meal

import org.json.JSONArray
import org.json.JSONObject

/**
 * Robust parser for the LLM's meal-description JSON responses (voice and
 * text-only meal pipeline).
 *
 * LLMs — especially verbose, high-quality ones — do not always return the
 * bare JSON object the prompt asks for, even when they fully understood the
 * meal. This parser tolerates the shapes seen in the wild instead of giving
 * up ("AI could not parse the description"):
 *
 *  - markdown code fences around the JSON
 *  - preamble / trailing conversational text around the JSON
 *  - a top-level ARRAY of per-food objects (common when the description
 *    names several foods) → merged into ONE combined [FoodData]
 *  - wrapper objects: `{ "food_data": {...} }`, `{ "foods": [...] }`,
 *    `{ "items": [...] }`, `{ "meals": [...] }`
 *  - TRUNCATED JSON (response cut off by a max_tokens limit) → salvaged:
 *    the trailing incomplete fragment is dropped and the still-parseable
 *    complete fields (title, calories, macros, …) are kept
 *  - numeric fields sent as strings ("950", "950 kcal") → coerced
 *
 * Pure Kotlin + org.json only (no android.util.Log) so it is unit-testable
 * on the JVM.
 */
object FoodDataJsonParser {

    /**
     * Parses an LLM response into a [FoodData]. Returns null only when
     * nothing substantive could be extracted from any candidate shape.
     */
    fun parseResponse(content: String): FoodData? {
        for (candidate in jsonCandidates(content)) {
            val parsed = tryParseCandidate(candidate)
            if (parsed != null) return parsed
        }
        return null
    }

    /**
     * Parses a food_data JSON object (shared by the vision and text-only
     * pipelines) into a [FoodData]. Tolerates numeric fields sent as
     * strings ("950 kcal") and JSON-null strings.
     */
    fun parseFoodData(fd: JSONObject): FoodData {
        val macros = fd.optJSONObject("macronutrients")
        return FoodData(
            title = fd.optString("title", "").takeIf { it != "null" } ?: "",
            summary = fd.optString("summary", "").takeIf { it != "null" } ?: "",
            isVeganVerified = fd.optBoolean("is_vegan_verified", false),
            estimatedCalories = flexibleInt(fd.opt("estimated_calories")),
            macronutrients = Macronutrients(
                proteinGrams = macros?.let { flexibleDouble(it.opt("protein_grams")) } ?: 0.0,
                carbsGrams = macros?.let { flexibleDouble(it.opt("carbs_grams")) } ?: 0.0,
                fatGrams = macros?.let { flexibleDouble(it.opt("fat_grams")) } ?: 0.0
            ),
            ingredientsDetected = fd.optJSONArray("ingredients_detected")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
            } ?: emptyList(),
            healthNotes = fd.optString("health_notes").takeIf {
                it.isNotBlank() && it != "null"
            },
            macroRatings = MacroRatings.fromJsonObj(fd.optJSONObject("macro_ratings"))
        )
    }

    // ── Candidate extraction ─────────────────────────────────────────────

    /**
     * Ordered JSON candidates extracted from the raw LLM text: the whole
     * (fence-stripped) text, the first balanced object/array (skips
     * preamble), and a best-effort repair when the text is truncated.
     */
    internal fun jsonCandidates(content: String): List<String> {
        val stripped = stripCodeFences(content).trim()
        if (stripped.isEmpty()) return emptyList()
        val candidates = mutableListOf(stripped)
        val start = stripped.indexOfFirst { it == '{' || it == '[' }
        if (start >= 0) {
            balancedSubstringFrom(stripped, start)?.let { candidates += it }
            repairTruncatedJson(stripped.substring(start))?.let { candidates += it }
        }
        return candidates.distinct()
    }

    /** Strips ```json ... ``` or ``` ... ``` fences from the content. */
    internal fun stripCodeFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        // Remove opening fence (with optional language tag)
        val afterOpen = trimmed.substringAfter("```", trimmed)
            .removePrefix("json").removePrefix("JSON")
        // Remove closing fence
        return afterOpen.substringBeforeLast("```").trim()
    }

    /**
     * Returns the first balanced `{...}` / `[...]` block starting at
     * [start], or null when the block never closes (truncation) or the
     * brackets are unbalanced.
     */
    internal fun balancedSubstringFrom(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> inString = true
                '{', '[' -> depth++
                '}', ']' -> {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                    if (depth < 0) return null
                }
            }
        }
        return null
    }

    /**
     * Best-effort repair of JSON truncated mid-token (e.g. the LLM hit its
     * max_tokens limit): drops the trailing incomplete fragment by cutting
     * back to the last structural comma, removes any dangling `"key":`
     * tail, and closes the still-open brackets. Returns null when the text
     * is already balanced (nothing to repair) or nothing usable remains.
     */
    internal fun repairTruncatedJson(text: String): String? {
        val stack = ArrayDeque<Char>()
        var inString = false
        var escaped = false
        var lastCommaIdx = -1
        var lastCommaStack: List<Char> = emptyList()
        var stringStartIdx = -1
        for (i in text.indices) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                continue
            }
            when (c) {
                '"' -> {
                    inString = true
                    stringStartIdx = i
                }
                '{', '[' -> stack.addLast(c)
                '}', ']' -> if (stack.isNotEmpty()) stack.removeLast()
                ',' -> {
                    lastCommaIdx = i
                    lastCommaStack = stack.toList()
                }
            }
        }
        if (stack.isEmpty() && !inString) return null // balanced — not truncated

        val body: String
        val openAtCut: List<Char>
        when {
            // Cut at the last comma: everything before it is structurally
            // complete, the partial element after it is dropped.
            lastCommaIdx >= 0 -> {
                body = text.substring(0, lastCommaIdx)
                openAtCut = lastCommaStack
            }
            // Truncated inside the very first value with no comma yet —
            // drop the unterminated string (and any dangling key after it).
            inString && stringStartIdx >= 0 -> {
                body = text.substring(0, stringStartIdx)
                openAtCut = stack.toList()
            }
            else -> {
                body = text
                openAtCut = stack.toList()
            }
        }
        var repaired = body.trimEnd()
        repaired = Regex(""",?\s*"[^"]*"\s*:\s*$""").replace(repaired, "")
        repaired = repaired.trimEnd().trimEnd(',', ':').trimEnd()
        if (repaired.isEmpty()) return null
        val closers = openAtCut.reversed().joinToString("") { if (it == '{') "}" else "]" }
        return repaired + closers
    }

    // ── Candidate parsing ────────────────────────────────────────────────

    /** Parses one candidate; null when it is not valid JSON or has no data. */
    private fun tryParseCandidate(candidate: String): FoodData? {
        val trimmed = candidate.trim()
        return when {
            trimmed.startsWith("[") -> {
                val arr = try {
                    JSONArray(trimmed)
                } catch (e: Exception) {
                    return null
                }
                val foods = (0 until arr.length())
                    .mapNotNull { arr.opt(it) as? JSONObject }
                    .map { parseFoodData(it) }
                mergeFoodData(foods)
            }
            else -> {
                val obj = try {
                    JSONObject(trimmed)
                } catch (e: Exception) {
                    return null
                }
                // Single food wrapped in "food_data" (vision-style schema)
                obj.optJSONObject("food_data")?.let {
                    return substantiveOrNull(parseFoodData(it))
                }
                // Multiple foods wrapped in an array field
                for (key in listOf("foods", "items", "meals")) {
                    val arr = obj.optJSONArray(key) ?: continue
                    val foods = (0 until arr.length())
                        .mapNotNull { arr.opt(it) as? JSONObject }
                        .map { parseFoodData(it) }
                    mergeFoodData(foods)?.let { return it }
                }
                substantiveOrNull(parseFoodData(obj))
            }
        }
    }

    /**
     * Combines per-food [FoodData]s (from an array response) into ONE meal:
     * summed calories/macros, joined titles/summaries/notes, union of
     * ingredients, vegan only when every item is vegan, highest macro
     * rating per macro. Null when the list is empty or nothing substantive.
     */
    private fun mergeFoodData(foods: List<FoodData>): FoodData? {
        if (foods.isEmpty()) return null
        if (foods.size == 1) return substantiveOrNull(foods[0])
        val titles = foods.map { it.title.trim() }.filter { it.isNotBlank() }
        val summaries = foods.map { it.summary.trim() }.filter { it.isNotBlank() }
        val notes = foods.mapNotNull { it.healthNotes?.trim()?.ifBlank { null } }
        val ratings = foods.mapNotNull { it.macroRatings?.takeIf { r -> r.isSet() } }
        return substantiveOrNull(
            FoodData(
                title = titles.joinToString(", "),
                summary = summaries.joinToString(" "),
                isVeganVerified = foods.all { it.isVeganVerified },
                estimatedCalories = foods.sumOf { it.estimatedCalories },
                macronutrients = Macronutrients(
                    proteinGrams = foods.sumOf { it.macronutrients.proteinGrams },
                    carbsGrams = foods.sumOf { it.macronutrients.carbsGrams },
                    fatGrams = foods.sumOf { it.macronutrients.fatGrams }
                ),
                ingredientsDetected = foods.flatMap { it.ingredientsDetected }.distinct(),
                healthNotes = notes.joinToString(" ").ifBlank { null },
                macroRatings = if (ratings.isEmpty()) null else MacroRatings(
                    protein = ratings.maxOf { it.protein },
                    carbs = ratings.maxOf { it.carbs },
                    fat = ratings.maxOf { it.fat }
                )
            )
        )
    }

    /** Null when the parsed object carries no usable information at all. */
    private fun substantiveOrNull(fd: FoodData): FoodData? {
        val hasContent = fd.title.isNotBlank() ||
            fd.summary.isNotBlank() ||
            fd.estimatedCalories > 0 ||
            fd.macronutrients.proteinGrams > 0.0 ||
            fd.macronutrients.carbsGrams > 0.0 ||
            fd.macronutrients.fatGrams > 0.0 ||
            fd.ingredientsDetected.isNotEmpty() ||
            fd.healthNotes != null ||
            (fd.macroRatings?.isSet() == true)
        return if (hasContent) fd else null
    }

    // ── Numeric coercion ─────────────────────────────────────────────────

    /** Int from a JSON number, or the first integer inside a string. */
    private fun flexibleInt(v: Any?): Int = when (v) {
        is String -> Regex("-?\\d+").find(v)?.value?.toIntOrNull() ?: 0
        // Covers Int/Long/Double/Float and BigDecimal/BigInteger (the JVM
        // org.json test library parses decimals as BigDecimal).
        is Number -> v.toInt()
        else -> 0
    }

    /** Double from a JSON number, or the first decimal inside a string. */
    private fun flexibleDouble(v: Any?): Double = when (v) {
        is String -> Regex("-?\\d+(?:\\.\\d+)?").find(v)?.value?.toDoubleOrNull() ?: 0.0
        // Covers Int/Long/Double/Float and BigDecimal/BigInteger (the JVM
        // org.json test library parses decimals as BigDecimal).
        is Number -> v.toDouble()
        else -> 0.0
    }
}
