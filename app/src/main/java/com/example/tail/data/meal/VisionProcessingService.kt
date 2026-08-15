package com.example.tail.data.meal

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val TAG = "VisionProcessing"

/** Configuration passed to [VisionProcessingService] for each inference call. */
data class VisionConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val userSystemPrompt: String
)

/** Maximum dimension (px) for the long edge of a compressed image. */
private const val MAX_IMAGE_DIMENSION = 1024

/**
 * Calls an OpenAI-compatible multimodal chat-completions API with an image
 * payload, classifies the image, and extracts structured meal data.
 *
 * Uses the same plain [HttpURLConnection] + Bearer-auth pattern as
 * [com.example.tail.data.AiIconGeneratorService] — no Retrofit/OkHttp dependency.
 *
 * The response is parsed from the LLM's JSON output (requested via the system
 * prompt) into a [VisionResult], with strict error boundaries and fallback logging.
 */
class VisionProcessingService {

    /**
     * Processes a single image through the vision pipeline.
     *
     * @param imageFile The captured image file to analyse.
     * @param config LLM endpoint configuration.
     * @param memoryPrompt Optional formatted block of learned image→habit
     *        associations (from [VisionMemoryRepository]) injected into the
     *        system prompt so the LLM applies its "memory".
     * @param habitPrompt Optional formatted list of valid habit / subtype
     *        names. When provided, the LLM may propose a [HabitAction] for
     *        non-food images — gated by a high confidence requirement.
     * @return The parsed [VisionResult], or null on failure.
     */
    suspend fun processImage(
        imageFile: File,
        config: VisionConfig,
        memoryPrompt: String? = null,
        habitPrompt: String? = null
    ): VisionResult? =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) {
                Log.w(TAG, "Cannot process: LLM config incomplete")
                return@withContext null
            }

            val fullUrl = buildEndpointUrl(config.baseUrl)
            Log.i(TAG, "Processing image via $fullUrl model=${config.model}")

            // 1. Compress / resize the image
            val base64Image = compressAndEncode(imageFile)
                ?: run {
                    Log.e(TAG, "Failed to compress/encode image")
                    return@withContext null
                }

            // 2. Build the request body (OpenAI chat completions format with vision)
            val requestBody = buildRequestBody(base64Image, config, memoryPrompt, habitPrompt)

            // 3. Execute the HTTP call with retry on 429 / 5xx
            when (val outcome = chatCompletion(fullUrl, requestBody, config)) {
                is ChatOutcome.Content -> parseVisionResult(outcome.text)
                is ChatOutcome.ErrorNote -> VisionResult(
                    classification = VisionClassification.UNCERTAIN_OTHER,
                    confidenceScore = 0.0,
                    processingNotes = outcome.note
                )
                ChatOutcome.NetworkFailure -> null
            }
        }

    /**
     * Tandem teaching call: the user held the capture button, took a photo,
     * and spoke an instruction. The photo and transcript are sent to the LLM
     * **together** so it can learn (and persist via [VisionMemoryRepository])
     * what this kind of image should mean in the future.
     *
     * @param imageFile The example photo captured during the hold.
     * @param transcript The user's spoken instruction (speech-to-text).
     * @param config LLM endpoint configuration.
     * @param habitPrompt Formatted list of valid habit / subtype names the
     *        LLM may choose from.
     * @return The parsed [TeachingResult], or null on network failure.
     */
    suspend fun processTeaching(
        imageFile: File,
        transcript: String,
        config: VisionConfig,
        habitPrompt: String
    ): TeachingResult? =
        withContext(Dispatchers.IO) {
            if (config.apiKey.isBlank() || config.baseUrl.isBlank() || config.model.isBlank()) {
                Log.w(TAG, "Cannot teach: LLM config incomplete")
                return@withContext null
            }

            val fullUrl = buildEndpointUrl(config.baseUrl)
            Log.i(TAG, "Teaching image association via $fullUrl model=${config.model}")

            val base64Image = compressAndEncode(imageFile)
                ?: run {
                    Log.e(TAG, "Failed to compress/encode teaching image")
                    return@withContext null
                }

            val requestBody = buildTeachingRequestBody(base64Image, transcript, config, habitPrompt)

            when (val outcome = chatCompletion(fullUrl, requestBody, config)) {
                is ChatOutcome.Content -> parseTeachingResult(outcome.text)
                is ChatOutcome.ErrorNote -> TeachingResult(understood = false, notes = outcome.note)
                ChatOutcome.NetworkFailure -> null
            }
        }

    // ── Shared chat-completion retry loop ────────────────────────────────

    /** Outcome of a full chat-completion exchange (with retries). */
    private sealed class ChatOutcome {
        /** Assistant message content extracted from a 2xx response. */
        class Content(val text: String) : ChatOutcome()
        /** Non-retryable API error or retries exhausted — note for processing_notes. */
        class ErrorNote(val note: String) : ChatOutcome()
        /** Network / I/O failure — caller should treat as "request failed". */
        object NetworkFailure : ChatOutcome()
    }

    /**
     * Executes the HTTP call with retry on 429 / 5xx (max 3 attempts) and
     * extracts the assistant message content. Shared by [processImage]
     * and [processTeaching].
     */
    private suspend fun chatCompletion(
        fullUrl: String,
        requestBody: JSONObject,
        config: VisionConfig
    ): ChatOutcome {
        val maxRetries = 3
        var lastHttpResult: HttpResult = HttpResult.OtherError(0, "Not attempted")

        for (attempt in 1..maxRetries) {
            val result = executeHttpCall(fullUrl, requestBody, config)
            when (result) {
                is HttpResult.Success -> {
                    val content = extractAssistantContent(result.content)
                        ?: return ChatOutcome.ErrorNote(
                            "No content in LLM response: ${result.content.take(200)}"
                        )
                    return ChatOutcome.Content(content)
                }
                is HttpResult.RateLimited -> {
                    lastHttpResult = result
                    if (attempt < maxRetries) {
                        val delayMs = 5_000L * attempt  // 5s, 10s
                        Log.w(TAG, "Rate limited (429), retrying in ${delayMs}ms (attempt $attempt/$maxRetries)")
                        delay(delayMs)
                    }
                }
                is HttpResult.ServerError -> {
                    lastHttpResult = result
                    if (attempt < maxRetries) {
                        val delayMs = 3_000L * attempt  // 3s, 6s
                        Log.w(TAG, "Server error ${result.code}, retrying in ${delayMs}ms (attempt $attempt/$maxRetries)")
                        delay(delayMs)
                    }
                }
                is HttpResult.OtherError -> {
                    // Non-retryable error (4xx other than 429), return immediately
                    return ChatOutcome.ErrorNote(
                        "API error ${result.code}: ${result.message.take(200)}"
                    )
                }
                is HttpResult.NetworkException -> {
                    Log.e(TAG, "Vision request failed", result.e)
                    return ChatOutcome.NetworkFailure
                }
            }
        }

        // All retries exhausted
        return ChatOutcome.ErrorNote(
            when (lastHttpResult) {
                is HttpResult.RateLimited ->
                    "Rate limited after $maxRetries attempts: ${lastHttpResult.message.take(200)}"
                is HttpResult.ServerError ->
                    "Server error ${lastHttpResult.code} after $maxRetries attempts: ${lastHttpResult.message.take(200)}"
                else -> "Request failed after $maxRetries attempts"
            }
        )
    }

    // ── HTTP transport ──────────────────────────────────────────────────

    /** Internal sealed result for a single HTTP attempt. */
    private sealed class HttpResult {
        /** HTTP 2xx with a valid response body. */
        class Success(val content: String) : HttpResult()
        /** HTTP 429 — rate limited, should retry after delay. */
        class RateLimited(val message: String) : HttpResult()
        /** HTTP 5xx — server error, should retry after delay. */
        class ServerError(val code: Int, val message: String) : HttpResult()
        /** HTTP 4xx (non-429) — non-retryable client error. */
        class OtherError(val code: Int, val message: String) : HttpResult()
        /** Network / I/O exception. */
        class NetworkException(val e: Exception) : HttpResult()
    }

    /**
     * Executes a single HTTP POST to the chat-completions endpoint and returns
     * a sealed [HttpResult]. The caller is responsible for retry logic.
     */
    private fun executeHttpCall(
        fullUrl: String,
        requestBody: JSONObject,
        config: VisionConfig
    ): HttpResult {
        val connection = (URL(fullUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
        }

        return try {
            connection.outputStream.use { out ->
                out.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode in 200..299) {
                val responseText = connection.inputStream.bufferedReader().readText()
                HttpResult.Success(responseText)
            } else {
                val errorText = connection.errorStream?.bufferedReader()?.readText()
                    ?: "Unknown error (HTTP $responseCode)"
                val shortError = if (errorText.length > 300) errorText.take(300) + "…" else errorText
                Log.e(TAG, "API returned $responseCode: $shortError")
                when (responseCode) {
                    429 -> HttpResult.RateLimited(errorText)
                    in 500..599 -> HttpResult.ServerError(responseCode, errorText)
                    else -> HttpResult.OtherError(responseCode, errorText)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "HTTP call failed", e)
            HttpResult.NetworkException(e)
        } finally {
            connection.disconnect()
        }
    }

    // ── URL building ────────────────────────────────────────────────────

    /**
     * Builds the full chat-completions endpoint URL.
     * If baseUrl already ends with /chat/completions, use it as-is.
     * If it ends with a version path like /v1, /v2, /v4, etc., append /chat/completions.
     * Otherwise append /v1/chat/completions.
     */
    private fun buildEndpointUrl(baseUrl: String): String {
        val trimmed = baseUrl.trimEnd('/')
        val versionPattern = Regex("""/v\d+$""")
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            versionPattern.containsMatchIn(trimmed) -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    // ── Image compression ───────────────────────────────────────────────

    /**
     * Loads, resizes (max [MAX_IMAGE_DIMENSION] on the long edge), and JPEG-compresses
     * the image, then returns it as a base64 data URL.
     */
    private fun compressAndEncode(imageFile: File): String? {
        return try {
            // First decode bounds to check dimensions
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imageFile.absolutePath, bounds)

            // Calculate sample size for memory efficiency
            var sampleSize = 1
            val maxDim = maxOf(bounds.outWidth, bounds.outHeight)
            if (maxDim > MAX_IMAGE_DIMENSION) {
                sampleSize = Math.ceil(maxDim.toDouble() / MAX_IMAGE_DIMENSION).toInt()
            }

            // Decode with sample size
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bitmap = BitmapFactory.decodeFile(imageFile.absolutePath, opts) ?: return null

            // Scale to exact max dimension if still too large
            val longestEdge = maxOf(bitmap.width, bitmap.height)
            if (longestEdge > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / longestEdge
                val newW = (bitmap.width * scale).toInt()
                val newH = (bitmap.height * scale).toInt()
                val scaled = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
                if (scaled != bitmap) bitmap.recycle()
                bitmap = scaled
            }

            // Compress to JPEG and base64-encode
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
            bitmap.recycle()
            val bytes = outputStream.toByteArray()
            val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (e: Exception) {
            Log.e(TAG, "Image compression failed", e)
            null
        }
    }

    // ── Request body building ───────────────────────────────────────────

    /**
     * Builds the OpenAI-compatible chat completions request body with the
     * unified system prompt + image content.
     */
    private fun buildRequestBody(
        base64Image: String,
        config: VisionConfig,
        memoryPrompt: String? = null,
        habitPrompt: String? = null
    ): JSONObject {
        val systemPrompt = buildSystemPrompt(config.userSystemPrompt, memoryPrompt, habitPrompt)

        val userContent = JSONArray().apply {
            // Text part: current datetime context
            put(JSONObject().apply {
                put("type", "text")
                put("text", "Analyse the image below.\n" +
                        "Current local datetime: ${LocalDateTime.now()
                            .atZone(ZoneId.systemDefault())
                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}")
            })
            // Image part
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", base64Image)
                })
            })
        }

        return JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("temperature", 0.2)
            put("max_tokens", 1000)
        }
    }

    // ── Teaching request (tandem voice+camera) ───────────────────────────

    /**
     * Builds the chat-completions request body for a tandem teaching call:
     * the example photo and the user's spoken instruction are sent together
     * so the LLM can extract the image→habit association to remember.
     */
    private fun buildTeachingRequestBody(
        base64Image: String,
        transcript: String,
        config: VisionConfig,
        habitPrompt: String
    ): JSONObject {
        val systemPrompt = buildString {
            append("You are a habit-tracking assistant. The user just took a photo and spoke an ")
            append("instruction that TEACHES you what this kind of photo should mean in the future.\n\n")
            append("AVAILABLE HABITS (the only valid habit_name values):\n$habitPrompt\n\n")
            append("Your tasks:\n")
            append("1. Understand the spoken instruction and decide which habit the user wants ")
            append("incremented whenever this kind of image is seen.\n")
            append("2. If a subtype is mentioned or clearly implied, pick it from that habit's listed subtypes.\n")
            append("3. Parse any increment amount (default 1).\n")
            append("4. Write a generalized \"visual_description\" of the photo subject — describe WHAT TO ")
            append("LOOK FOR in future photos (the subject/category), not this specific photo's incidental ")
            append("details — so similar photos can be recognized later.\n")
            append("5. Write a one-line summary of the learned association.\n")
            append("If the instruction cannot be mapped to any listed habit, set understood=false and ")
            append("explain why in notes.\n\n")
            append("Respond ONLY with raw JSON (no markdown fences, no conversational text):\n")
            append("{\n")
            append("  \"understood\": boolean,\n")
            append("  \"habit_name\": \"String or null\",\n")
            append("  \"subtype_name\": \"String or null\",\n")
            append("  \"amount\": number,\n")
            append("  \"visual_description\": \"String\",\n")
            append("  \"summary\": \"String\",\n")
            append("  \"notes\": \"String\"\n")
            append("}")
        }

        val userContent = JSONArray().apply {
            // Text part: the spoken instruction
            put(JSONObject().apply {
                put("type", "text")
                put(
                    "text",
                    "The user's spoken instruction: \"$transcript\"\n" +
                    "Analyse the image below and learn the association."
                )
            })
            // Image part: the example photo
            put(JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", base64Image)
                })
            })
        }

        return JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userContent)
                })
            })
            put("temperature", 0.2)
            put("max_tokens", 800)
        }
    }

    /**
     * Parses the LLM's JSON output from a teaching call into a
     * [TeachingResult]. Handles markdown fences and malformed JSON gracefully.
     */
    fun parseTeachingResult(content: String): TeachingResult {
        return try {
            val json = JSONObject(stripCodeFences(content).trim())
            TeachingResult(
                understood = json.optBoolean("understood", false),
                habitName = json.optString("habit_name").takeIf {
                    it.isNotBlank() && it != "null"
                },
                subtypeName = json.optString("subtype_name").takeIf {
                    it.isNotBlank() && it != "null"
                },
                amount = json.optInt("amount", 1).takeIf { it > 0 } ?: 1,
                visualDescription = json.optString("visual_description", ""),
                summary = json.optString("summary", ""),
                notes = json.optString("notes", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse teaching result JSON: ${content.take(200)}", e)
            TeachingResult(understood = false, notes = "Parse error: ${e.message?.take(100)}")
        }
    }

    /**
     * Builds the unified system prompt from the spec's template, merged with
     * the user's custom dietary rules.
     */
    companion object {
        val SYSTEM_PROMPT_TEMPLATE = """
You are an advanced, context-aware habit tracking assistant specializing in image recognition, nutritional analysis, and structured metadata extraction.

### USER CONTEXT & DIETARY RULES:
{{USER_CUSTOM_SYSTEM_PROMPT}}

### PROCESSING INSTRUCTIONS:
1. First, classify the primary subject of the provided image into one of the following categories:
   - "FOOD_MEAL": The image contains a dish, snack, beverage, or food item.
   - "NON_FOOD_HABIT": The image depicts a non-food habit activity (e.g., book, gym equipment, task list).
   - "UNCERTAIN_OTHER": The image does not clearly depict a trackable habit or meal.

   IMPORTANT: If the image contains ANY food, drink, snack, or meal — even partially visible, unusual, or unclear — ALWAYS classify it as "FOOD_MEAL" and fill food_data with your BEST-GUESS estimates. Never classify food as UNCERTAIN_OTHER, and never leave food_data empty because exact quantities are unknown: estimate. The confidence_score expresses certainty about a proposed habit_action (which non-food habit to increment) — it must NEVER be lowered or used to hedge on food classification or food_data quality.

2. If the category is "FOOD_MEAL", perform a granular breakdown adhering strictly to any User Dietary Rules above:
   - Identify the meal/snack name.
   - Estimate ingredients and portion sizes.
   - Calculate estimated calories and primary macronutrients (Protein, Carbs, Fats).
   - Summarize the item in 1-2 concise sentences for a habit log entry.

3. If the category is "NON_FOOD_HABIT" or "UNCERTAIN_OTHER", begin processing_notes with a concise one-sentence visual description of what the image actually shows, formatted as "Description: <what the image depicts>". This description is shown directly to the user, so make it specific and useful.

4. Format the response strictly as valid, raw JSON matching the JSON Schema provided below. Do not wrap in markdown code blocks, and do not add conversational text.

### JSON OUTPUT SCHEMA:
{
  "classification": "FOOD_MEAL" | "NON_FOOD_HABIT" | "UNCERTAIN_OTHER",
  "confidence_score": 0.0 to 1.0,
  "food_data": {
    "title": "String (Name of meal)",
    "summary": "String (Short description)",
    "is_vegan_verified": boolean,
    "estimated_calories": number,
    "macronutrients": {
      "protein_grams": number,
      "carbs_grams": number,
      "fat_grams": number
    },
    "ingredients_detected": ["String"],
    "health_notes": "String or null"
  },
  "non_food_data": {
    "detected_activity": "String or null",
    "suggested_action": "String or null"
  },
  "habit_action": {
    "habit_name": "String or null (exact habit name, only when VERY certain)",
    "subtype_name": "String or null",
    "amount": number,
    "reasoning": "String"
  },
  "processing_notes": "String"
}
""".trimIndent()

        /**
         * Extra instructions appended to the system prompt when a habit list
         * is provided — enables the smart auto-detection (habit_action)
         * path with a strict certainty requirement.
         */
        private const val HABIT_ACTION_INSTRUCTIONS = """
### HABIT ACTION INSTRUCTIONS:
- If the image clearly matches one of the learned associations above, OR unambiguously depicts an activity or item tied to one of the available habits (e.g. a supplement bottle clearly labeled "Glutamine" → the Pills habit with subtype Glutamine), include a "habit_action" object in your JSON with the EXACT habit name (and the exact subtype name when one applies).
- confidence_score expresses how certain you are about the habit_action choice. Only include "habit_action" when that certainty is at least 0.85. When unsure WHICH habit applies, omit "habit_action" entirely (null) — the app will ask the user directly, so no information is lost. Low habit certainty must never affect food classification or food_data estimates.
- "habit_action.amount" defaults to 1; only use a larger number when the image clearly shows multiples (e.g. several pills taken at once).
"""

        /** Builds the final system prompt by injecting user custom rules. */
        fun buildSystemPrompt(
            userCustomPrompt: String,
            memoryPrompt: String? = null,
            habitPrompt: String? = null
        ): String {
            val rules = if (userCustomPrompt.isBlank()) {
                "(No custom dietary rules specified. Use general nutritional knowledge.)"
            } else {
                userCustomPrompt.trim()
            }
            var prompt = SYSTEM_PROMPT_TEMPLATE.replace("{{USER_CUSTOM_SYSTEM_PROMPT}}", rules)
            if (!habitPrompt.isNullOrBlank()) {
                prompt += "\n### AVAILABLE HABITS (the ONLY valid values for habit_action.habit_name):\n$habitPrompt\n"
            }
            if (!memoryPrompt.isNullOrBlank()) {
                prompt += "\n### LEARNED IMAGE → HABIT ASSOCIATIONS (user-taught memory):\n$memoryPrompt\n"
            }
            if (!habitPrompt.isNullOrBlank()) {
                prompt += HABIT_ACTION_INSTRUCTIONS
            }
            return prompt
        }
    }

    // ── Response parsing ────────────────────────────────────────────────

    /** Extracts the assistant message content from the chat completions response. */
    private fun extractAssistantContent(responseText: String): String? {
        return try {
            val json = JSONObject(responseText)
            val choices = json.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val firstChoice = choices.getJSONObject(0)
            val message = firstChoice.optJSONObject("message") ?: return null
            // Content can be a string or an array of content parts
            val content = message.opt("content") ?: return null
            when (content) {
                is String -> content
                is JSONArray -> {
                    // Concatenate all text parts
                    val sb = StringBuilder()
                    for (i in 0 until content.length()) {
                        val part = content.optJSONObject(i)
                        if (part?.optString("type") == "text") {
                            sb.append(part.optString("text"))
                        }
                    }
                    sb.toString().ifBlank { null }
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract content from response", e)
            null
        }
    }

    /**
     * Parses the LLM's JSON output into a [VisionResult].
     * Strips markdown code fences if present and handles malformed JSON gracefully.
     */
    fun parseVisionResult(content: String): VisionResult {
        return try {
            // Strip markdown code fences if the LLM wrapped the JSON
            val jsonStr = stripCodeFences(content).trim()
            val json = JSONObject(jsonStr)

            val classification = VisionClassification.fromString(
                json.optString("classification")
            )
            val confidence = json.optDouble("confidence_score", 0.0)

            val foodData = json.optJSONObject("food_data")?.let { fd ->
                val macros = fd.optJSONObject("macronutrients")
                FoodData(
                    title = fd.optString("title", ""),
                    summary = fd.optString("summary", ""),
                    isVeganVerified = fd.optBoolean("is_vegan_verified", false),
                    estimatedCalories = fd.optInt("estimated_calories", 0),
                    macronutrients = Macronutrients(
                        proteinGrams = macros?.optDouble("protein_grams", 0.0) ?: 0.0,
                        carbsGrams = macros?.optDouble("carbs_grams", 0.0) ?: 0.0,
                        fatGrams = macros?.optDouble("fat_grams", 0.0) ?: 0.0
                    ),
                    ingredientsDetected = fd.optJSONArray("ingredients_detected")?.let { arr ->
                        (0 until arr.length()).mapNotNull { arr.opt(it) as? String }
                    } ?: emptyList(),
                    healthNotes = fd.optString("health_notes").takeIf {
                        it.isNotBlank() && it != "null"
                    }
                )
            }

            val nonFoodData = json.optJSONObject("non_food_data")?.let { nfd ->
                NonFoodData(
                    detectedActivity = nfd.optString("detected_activity").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    suggestedAction = nfd.optString("suggested_action").takeIf {
                        it.isNotBlank() && it != "null"
                    }
                )
            }

            val habitAction = json.optJSONObject("habit_action")?.let { ha ->
                val name = ha.optString("habit_name").takeIf { it.isNotBlank() && it != "null" }
                if (name == null) null else HabitAction(
                    habitName = name,
                    subtypeName = ha.optString("subtype_name").takeIf {
                        it.isNotBlank() && it != "null"
                    },
                    amount = ha.optInt("amount", 1).takeIf { it > 0 } ?: 1,
                    reasoning = ha.optString("reasoning", "")
                )
            }

            VisionResult(
                classification = classification,
                confidenceScore = confidence,
                foodData = foodData,
                nonFoodData = nonFoodData,
                habitAction = habitAction,
                processingNotes = json.optString("processing_notes", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse vision result JSON: ${content.take(200)}", e)
            VisionResult(
                classification = VisionClassification.UNCERTAIN_OTHER,
                processingNotes = "Parse error: ${e.message?.take(100)}"
            )
        }
    }

    /** Strips ```json ... ``` or ``` ... ``` fences from the content. */
    private fun stripCodeFences(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("```")) return trimmed
        // Remove opening fence (with optional language tag)
        val afterOpen = trimmed.substringAfter("```", trimmed)
            .removePrefix("json").removePrefix("JSON")
        // Remove closing fence
        return afterOpen.substringBeforeLast("```").trim()
    }
}
