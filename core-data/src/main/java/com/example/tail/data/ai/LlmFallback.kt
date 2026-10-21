package com.example.tail.data.ai

import android.util.Log
import com.example.tail.data.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "LlmFallback"

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  EMERGENCY BACKUP LLM
 * ═══════════════════════════════════════════════════════════════════════
 *
 * One OpenAI-compatible chat-completions endpoint (base URL + bearer key +
 * model). Used both for the "primary" endpoint of a feature and for the
 * user-configured emergency backup.
 *
 * The backup is ONLY ever contacted after the primary endpoint that the
 * feature is supposed to use has failed (network error, 429 after retries,
 * 5xx, auth/4xx error, empty or unparseable answer) — or when the primary
 * endpoint is not configured at all. It is never used speculatively and
 * never in parallel, so a healthy primary is never bypassed.
 */
data class LlmEndpoint(
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    /**
     * A backup endpoint is only usable when it has a URL, a model AND a key.
     * The key is required because the shipped default (ppq.ai) is a paid
     * third-party gateway — a keyless request would just 401.
     */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    /**
     * Full chat-completions URL.
     *
     * - `.../chat/completions` is used verbatim.
     * - Otherwise `/chat/completions` is appended, which is correct for every
     *   shape we support: `https://api.ppq.ai` → `https://api.ppq.ai/chat/completions`,
     *   `https://api.z.ai/api/coding/paas/v4` → `.../v4/chat/completions`,
     *   `https://api.openai.com/v1` → `.../v1/chat/completions`.
     */
    fun chatCompletionsUrl(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) trimmed
        else "$trimmed/chat/completions"
    }

    /** Short label for logs / UI ("model @ host"). */
    fun label(): String = "$model @ ${baseUrl.trim().trimEnd('/')}"
}

/** Shipped defaults for the emergency backup endpoint. */
object LlmFallbackDefaults {
    /** ppq.ai gateway base URL (the endpoint is `<base>/chat/completions`). */
    const val BASE_URL = "https://api.ppq.ai"

    /** Default backup model. */
    const val MODEL = "deepseek-ai/DeepSeek-V4.1-Flash-1017"

    /** No key is shipped — the user must paste their own. */
    const val API_KEY = ""
}

/**
 * The emergency backup endpoint from the user's settings, or null when the
 * backup is disabled / not fully configured. Every LLM call site resolves
 * its fallback through this single function so the "only when the primary
 * is broken" rule is enforced in exactly one place.
 */
fun AppSettings.llmFallbackEndpoint(): LlmEndpoint? {
    if (!llmFallbackEnabled) return null
    return LlmEndpoint(
        baseUrl = llmFallbackBaseUrl,
        apiKey = llmFallbackApiKey,
        model = llmFallbackModel
    ).takeIf { it.isConfigured }
}

/**
 * Builds the ordered endpoint list for one LLM call: the primary FIRST, the
 * emergency backup strictly LAST.
 *
 * - A blank/unconfigured primary is skipped entirely, so a user who only
 *   filled in the backup still gets working LLM features.
 * - An identical backup (same URL + model) is never appended twice.
 *
 * Callers must try the list in order and stop at the first success — that is
 * what guarantees a healthy primary is never bypassed.
 */
fun llmCandidateEndpoints(primary: LlmEndpoint?, backup: LlmEndpoint?): List<LlmEndpoint> {
    val list = mutableListOf<LlmEndpoint>()
    if (primary != null && primary.isConfigured) list += primary
    if (backup != null && backup.isConfigured) {
        val duplicate = list.any {
            it.chatCompletionsUrl() == backup.chatCompletionsUrl() && it.model == backup.model
        }
        if (!duplicate) list += backup
    }
    return list
}

/**
 * Minimal "is this endpoint alive?" probe used by the settings screen's
 * Test button. Throws on any failure so the caller can show the reason.
 */
suspend fun pingLlmEndpoint(endpoint: LlmEndpoint): String = withContext(Dispatchers.IO) {
    val body = JSONObject().apply {
        put("model", endpoint.model)
        put("messages", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", "Reply with the single word: pong")
        }))
        put("max_tokens", 10)
    }
    val connection = (URL(endpoint.chatCompletionsUrl()).openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        setRequestProperty("Content-Type", "application/json")
        setRequestProperty("Authorization", "Bearer ${endpoint.apiKey}")
        connectTimeout = 20_000
        readTimeout = 60_000
        doOutput = true
    }
    try {
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val text = if (code in 200..299) {
            connection.inputStream.bufferedReader().readText()
        } else {
            val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
            throw IllegalStateException("HTTP $code: ${err.take(300)}")
        }
        val reply = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
        Log.i(TAG, "ping OK for ${endpoint.label()}: ${reply.take(40)}")
        reply
    } finally {
        connection.disconnect()
    }
}
