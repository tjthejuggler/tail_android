package com.example.tail.data.environment

import android.util.Log
import com.example.tail.data.ai.LlmEndpoint
import com.example.tail.data.ai.llmCandidateEndpoints
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

    /**
     * LLM endpoint configuration (mirrors the AI Assistant settings).
     *
     * @param fallback Emergency backup endpoint, contacted ONLY after the
     *        primary has actually failed (network error, non-2xx, or an
     *        unparseable answer) or when the primary is not configured.
     */
    data class Config(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val fallback: LlmEndpoint? = null
    ) {
        val isPrimaryConfigured: Boolean
            get() = baseUrl.isNotBlank() && model.isNotBlank()

        val isConfigured: Boolean
            get() = isPrimaryConfigured || (fallback?.isConfigured == true)

        /** Primary first; the emergency backup is appended strictly last. */
        fun candidateEndpoints(): List<LlmEndpoint> = llmCandidateEndpoints(
            primary = if (isPrimaryConfigured) LlmEndpoint(baseUrl, apiKey, model) else null,
            backup = fallback
        )
    }

    /**
     * Asks the LLM for the average municipal tap-water hardness at
     * [locationLabel]. Returns ppm (mg/L CaCO₃) rounded to a whole number,
     * or null on any failure / unparseable answer.
     */
    suspend fun lookup(config: Config, locationLabel: String): Double? {
        if (!config.isConfigured || locationLabel.isBlank()) return null
        val endpoints = config.candidateEndpoints()
        if (endpoints.isEmpty()) return null
        return withContext(Dispatchers.IO) {
            for ((index, endpoint) in endpoints.withIndex()) {
                if (index > 0) {
                    Log.w(TAG, "Primary LLM failed — trying EMERGENCY BACKUP ${endpoint.label()}")
                }
                val ppm = runCatching { query(endpoint, locationLabel) }
                    .onFailure { Log.w(TAG, "lookup failed on ${endpoint.label()}: ${it.message}") }
                    .getOrNull()
                if (ppm != null) {
                    if (index > 0) Log.i(TAG, "EMERGENCY BACKUP succeeded: ${endpoint.label()}")
                    return@withContext ppm
                }
            }
            null
        }
    }

    /** Single-endpoint request. Returns null on any failure. */
    private fun query(endpoint: LlmEndpoint, locationLabel: String): Double? {
        val body = JSONObject().apply {
            put("model", endpoint.model)
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
        val conn = URL(endpoint.chatCompletionsUrl()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        if (endpoint.apiKey.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} from ${endpoint.label()}")
                return null
            }
            val response = JSONObject(
                conn.inputStream.bufferedReader().use { it.readText() }
            )
            val content = response
                .getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            return parsePpm(content)
        } finally {
            conn.disconnect()
        }
    }

    /** Extracts the first plausible hardness number from an LLM answer. */
    fun parsePpm(content: String): Double? {
        val match = Regex("""\d+(\.\d+)?""").find(content.replace(",", "")) ?: return null
        val ppm = match.value.toDoubleOrNull() ?: return null
        // Sanity band: 0 (distilled) … 500 ppm (extremely hard).
        return if (ppm in 0.0..500.0) Math.round(ppm).toDouble() else null
    }
}
