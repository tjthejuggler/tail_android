package com.example.tail.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Generic HTTP client for the Tail Bridge server.
 *
 * This is the **reusable communication layer** between the phone and the desktop
 * for all tethered features. It handles:
 *   - API key authentication (X-App-Auth header, same convention as Garmin proxy)
 *   - JSON fetching from any bridge endpoint
 *   - Error handling and logging
 *
 * ## Usage
 * ```kotlin
 * val client = BridgeClient()
 * val json = client.fetch(proxyUrl, token, "movies/latest")
 * val title = json?.optString("title")
 * ```
 *
 * ## Adding a new tethered feature
 * 1. Desktop: register a new source in tail_bridge/sources/__init__.py
 * 2. Android: create a data class and call [fetch] with the source path
 * 3. No changes needed to this class — it's fully generic.
 */
class BridgeClient {

    companion object {
        private const val TAG = "BridgeClient"
        private const val USER_AGENT = "Tail-Android-App/1.0"
        private const val CONNECT_TIMEOUT = 5_000
        private const val READ_TIMEOUT = 10_000
    }

    /**
     * Fetches JSON from a bridge endpoint.
     *
     * @param bridgeUrl  Base URL of the bridge server (e.g. "http://192.168.1.100:8001")
     * @param token      The X-App-Auth shared secret
     * @param path       API path after /api/v1/ (e.g. "movies/latest", "movies/suggest?exclude=Foo")
     * @return Parsed JSONObject, or null on any error / 404
     */
    suspend fun fetch(
        bridgeUrl: String,
        token: String,
        path: String
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = bridgeUrl.trim().trimEnd('/')
            val url = URL("$cleanUrl/api/v1/$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("X-App-Auth", token)
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val code = conn.responseCode
            if (code == 404) {
                // No data available — not an error, just empty
                conn.disconnect()
                return@withContext null
            }
            if (code != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                Log.w(TAG, "HTTP $code for $url: $errorBody")
                conn.disconnect()
                return@withContext null
            }

            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(body)
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for '$path': ${e.message}")
            null
        }
    }

    /**
     * Fetches JSON like [fetch], but reports the HTTP status code and
     * allows a longer read timeout — for long-poll endpoints
     * (pc_widget/events/wait), where the server holds the connection
     * open until data arrives or the poll timeout passes.
     *
     * @return null on transport error, else (status code, parsed body or null)
     */
    suspend fun fetchWithStatus(
        bridgeUrl: String,
        token: String,
        path: String,
        readTimeoutMs: Int = READ_TIMEOUT
    ): Pair<Int, JSONObject?>? = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = bridgeUrl.trim().trimEnd('/')
            val url = URL("$cleanUrl/api/v1/$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("X-App-Auth", token)
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val code = conn.responseCode
            val body = if (code == 200) {
                conn.inputStream.bufferedReader().readText()
            } else {
                conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
            }
            conn.disconnect()
            val json = if (code == 200 && body.isNotEmpty()) {
                try { JSONObject(body) } catch (_: Exception) { null }
            } else null
            code to json
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for '$path': ${e.message}")
            null
        }
    }

    /**
     * POSTs JSON to a bridge endpoint.
     *
     * @param bridgeUrl  Base URL of the bridge server (e.g. "http://192.168.1.100:8001")
     * @param token      The X-App-Auth shared secret
     * @param path       API path after /api/v1/ (e.g. "pc_widget/config")
     * @param body       JSON request body
     * @param readTimeoutMs Read timeout — override for slow endpoints (e.g.
     *                    live Stockfish analysis takes seconds to minutes,
     *                    far beyond the default 10 s)
     * @return Parsed response JSONObject, or null on any error
     */
    suspend fun post(
        bridgeUrl: String,
        token: String,
        path: String,
        body: JSONObject,
        readTimeoutMs: Int = READ_TIMEOUT
    ): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = bridgeUrl.trim().trimEnd('/')
            val url = URL("$cleanUrl/api/v1/$path")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = readTimeoutMs
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("X-App-Auth", token)
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code != 200) {
                val errorBody = conn.errorStream?.bufferedReader()?.readText()?.take(200) ?: ""
                Log.w(TAG, "HTTP $code for POST $url: $errorBody")
                conn.disconnect()
                return@withContext null
            }
            val responseBody = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            JSONObject(responseBody)
        } catch (e: Exception) {
            Log.w(TAG, "POST failed for '$path': ${e.message}")
            null
        }
    }

    /**
     * Checks whether the bridge server is reachable and the token is valid.
     * Returns true if the server responded with a valid health JSON.
     */
    suspend fun checkHealth(bridgeUrl: String, token: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val cleanUrl = bridgeUrl.trim().trimEnd('/')
            // /health doesn't require auth, but we check /api/v1/sources to verify the token
            val url = URL("$cleanUrl/api/v1/sources")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.setRequestProperty("X-App-Auth", token)
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val code = conn.responseCode
            conn.disconnect()
            code == 200
        } catch (e: Exception) {
            Log.w(TAG, "Health check failed: ${e.message}")
            false
        }
    }
}
