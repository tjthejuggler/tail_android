package com.example.tail.data.bloodtest

import android.util.Log
import com.example.tail.data.ai.AiAssistantConfig
import com.example.tail.data.ai.LlmEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-shot AI extraction of structured blood-test items from free text
 * (a pasted description or the text of a shared lab-result document).
 *
 * Uses the SAME LLM endpoints as the AI Assistant (primary + emergency
 * backup via [AiAssistantConfig.candidateEndpoints]) but a plain chat
 * completion — no tool loop. The model must answer with pure JSON.
 */
class BloodTestAiParser {

    companion object {
        private const val TAG = "BloodTestAi"
        private const val MAX_TOKENS = 8000
    }

    /** Successful parse result: optional test date override + items. */
    data class ParsedTest(
        /** "YYYY-MM-DD" when the text states a date, else null (caller defaults). */
        val date: String?,
        val items: List<BloodTestItem>
    )

    /**
     * Parses [rawText] (pasted description) plus optional extracted document
     * text [docText] and document images [images] (data-URLs — photos or
     * rendered PDF pages of a lab report) into structured items. Throws on
     * transport/parse failure — callers surface the message.
     */
    suspend fun parse(
        rawText: String,
        config: AiAssistantConfig,
        docText: String = "",
        images: List<String> = emptyList()
    ): ParsedTest =
        withContext(Dispatchers.IO) {
            val trimmed = rawText.trim()
            if (trimmed.isEmpty() && docText.isBlank() && images.isEmpty()) {
                throw IllegalStateException("nothing to parse")
            }
            if (!config.isConfigured) {
                throw IllegalStateException(
                    "AI Assistant is not configured. Set Base URL, API key and model in " +
                        "Settings → Habit Features → AI Assistant."
                )
            }

            val system = """
                You extract structured blood test results from free text and/or document
                images (lab report pages, photos, PDFs). The input may be a lab report
                (full blood panel, glucose/ketone reading, nutrient panel, hormones,
                anything), a description, or partial notes — possibly split across
                several text sections and page images that ALL belong to ONE blood test.
                Extract EVERY measured analyte. Respond with PURE JSON only, no markdown
                fences, matching:
                {"date": "YYYY-MM-DD or null", "items": [{"name": string, "value": number,
                "unit": string or null, "range": string or null, "flag": "low"|"high"|"normal"|null}]}
                Rules: name = canonical analyte name in Title Case (e.g. "Glucose", "Vitamin D",
                "HbA1c", "Ferritin"); value = numeric (convert commas to dots); omit items with no
                numeric value; deduplicate analytes repeated across pages/images keeping the most
                plausible reading; date only when the input clearly states the test date.
            """.trimIndent()

            val userText = buildString {
                if (trimmed.isNotEmpty()) append(trimmed.take(60000))
                if (docText.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(docText.take(60000))
                }
            }

            var lastError: Exception? = null
            for (endpoint in config.candidateEndpoints()) {
                try {
                    val userContent: Any = if (images.isEmpty()) userText
                    else JSONArray().apply {
                        if (userText.isNotEmpty()) {
                            put(JSONObject().put("type", "text").put("text", userText))
                        }
                        images.forEach { dataUrl ->
                            put(JSONObject().put("type", "image_url").put(
                                "image_url", JSONObject().put("url", dataUrl)
                            ))
                        }
                    }
                    val body = JSONObject().apply {
                        put("model", endpoint.model)
                        put("messages", JSONArray().apply {
                            put(JSONObject().put("role", "system").put("content", system))
                            put(JSONObject().put("role", "user").put("content", userContent))
                        })
                        put("temperature", 0.0)
                        put("max_tokens", MAX_TOKENS)
                    }
                    val reply = call(endpoint, body)
                    return@withContext parseReply(reply)
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "Endpoint failed (${e.message?.take(160)})")
                }
            }
            throw IllegalStateException(
                "AI parse failed: ${lastError?.message ?: lastError?.javaClass?.simpleName ?: "unknown error"}"
            )
        }

    private fun call(endpoint: LlmEndpoint, body: JSONObject): String {
        val conn = (URL(endpoint.chatCompletionsUrl()).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = if (code in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("HTTP $code: ${err.take(300)}")
            }
            return JSONObject(text).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
        } finally {
            conn.disconnect()
        }
    }

    /** Extracts the JSON object from the reply (tolerating markdown fences). */
    private fun parseReply(content: String): ParsedTest {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        if (start < 0 || end <= start) throw IllegalStateException("AI reply contained no JSON")
        val obj = JSONObject(cleaned.substring(start, end + 1))
        val date = obj.optString("date").takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        val items = mutableListOf<BloodTestItem>()
        val arr = obj.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val it = arr.optJSONObject(i) ?: continue
            val name = it.optString("name").trim()
            if (name.isEmpty()) continue
            val value = when (val v = it.opt("value")) {
                is Number -> v.toDouble()
                is String -> v.replace(',', '.').toDoubleOrNull() ?: continue
                else -> continue
            }
            items += BloodTestItem(
                name = name,
                value = value,
                unit = it.optString("unit").trim().ifBlank { null },
                range = it.optString("range").trim().ifBlank { null },
                flag = it.optString("flag").trim().ifBlank { null }
            )
        }
        if (items.isEmpty()) throw IllegalStateException("no test items found in the text")
        return ParsedTest(date, items)
    }
}
