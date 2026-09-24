package com.example.tail.data.environment

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Programmatic tap-water-hardness lookup via the user's configured LLM
 * (the same OpenAI-compatible endpoint/credentials as the AI Assistant
 * feature: base URL + API key + model).
 *
 * There is no free global water-hardness API, so the lookup asks the LLM
 * for the municipal hardness at a location label ("Turin, Piedmont, Italy")
 * and expects a bare number back. The result is cached in the environment
 * repository's per-location memory, so the LLM is consulted at most ONCE
 * per place for the lifetime of the app.
 */
object WaterHardnessLlm {

    private const val TAG = "WaterHardnessLlm"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 45_000

    /** LLM endpoint configuration (mirrors the AI Assistant settings). */
    data class Config(val baseUrl: String, val apiKey: String, val model: String) {
        val isConfigured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank()
    }

    /**
     * Asks the LLM for the average municipal tap-water hardness at
     * [locationLabel]. Returns ppm (mg/L CaCO₃) rounded to a whole number,
     * or null on any failure / unparseable answer.
     */
    suspend fun lookup(config: Config, locationLabel: String): Double? {
        if (!config.isConfigured || locationLabel.isBlank()) return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val url = buildEndpointUrl(config.baseUrl)
                val body = JSONObject().apply {
                    put("model", config.model)
                    put(
                        "messages", org.json.JSONArray().put(
                            JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    "What is the average municipal TAP WATER hardness in " +
                                        locationLabel.trim() + "? Answer with ONLY the number " +
                                        "in mg/L CaCO3 (ppm) as a plain integer — no units, " +
                                        "no explanation, no punctuation. If genuinely unknown, " +
                                        "answer with the regional average."
                                )
                            }
                        )
                    )
                    put("temperature", 0)
                    put("max_tokens", 20)
                }
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                if (config.apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
                }
                try {
                    if (conn.responseCode !in 200..299) {
                        Log.w(TAG, "HTTP ${conn.responseCode} from LLM")
                        return@runCatching null
                    }
                    val response = JSONObject(
                        conn.inputStream.bufferedReader().use { it.readText() }
                    )
                    val content = response
                        .getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content")
                    parsePpm(content)
                } finally {
                    conn.disconnect()
                }
            }.onFailure { Log.w(TAG, "lookup failed: ${it.message}") }.getOrNull()
        }
    }

    /** Extracts the first plausible hardness number from an LLM answer. */
    fun parsePpm(content: String): Double? {
        val match = Regex("""\d+(\.\d+)?""").find(content.replace(",", "")) ?: return null
        val ppm = match.value.toDoubleOrNull() ?: return null
        // Sanity band: 0 (distilled) … 500 ppm (extremely hard).
        return if (ppm in 0.0..500.0) Math.round(ppm).toDouble() else null
    }

    private fun buildEndpointUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            Regex("""/v\d+""").containsMatchIn(trimmed) -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }
}
