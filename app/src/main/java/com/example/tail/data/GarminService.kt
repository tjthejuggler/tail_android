package com.example.tail.data

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Low-level API client for Garmin health metrics via Python proxy.
 * All methods run on Dispatchers.IO and return parsed data or throw on error.
 */
class GarminService {

    companion object {
        private const val TAG = "GarminService"
        private const val USER_AGENT = "Tail-Android-App/1.0"
    }

    data class HealthCheckResult(
        val success: Boolean,
        val message: String,
        val proxyRunning: Boolean = false,
        val garminConnected: Boolean = false,
        val dataAvailable: Boolean = false
    )

    /**
     * Fetches daily health metrics for a specific date from the Garmin proxy.
     * Returns null if the date has no data.
     */
    suspend fun fetchDailyMetrics(
        proxyUrl: String,
        appToken: String,
        date: String
    ): GarminMetricsDto? = suspendCoroutine { continuation ->
        try {
            // Trim to strip any stray whitespace/newline saved with the URL — an
            // untrimmed trailing newline corrupts URL port parsing and throws
            // NumberFormatException ("For input string: \"8000\n\"").
            val cleanUrl = proxyUrl.trim().trimEnd('/')
            val url = URL("$cleanUrl/api/v1/health-metrics?date=$date")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-App-Auth", appToken)
            // The proxy performs a full Garmin login per request, which can be
            // slow on a cold connection — allow generous read time. We only fetch
            // a single day now, so a long read timeout can't cause a long hang.
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000

            try {
                val code = conn.responseCode
                if (code == 404) {
                    // No data for this date
                    continuation.resume(null)
                    return@suspendCoroutine
                }
                if (code !in 200..299) {
                    val errorBody = try {
                        conn.errorStream?.bufferedReader()?.readText() ?: ""
                    } catch (_: Exception) { "" }
                    continuation.resumeWithException(
                        GarminApiException(code, "HTTP $code for $url: $errorBody")
                    )
                    return@suspendCoroutine
                }
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                continuation.resume(parseMetrics(json))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            continuation.resumeWithException(e)
        }
    }

    /**
     * Validates the Garmin proxy connection and credentials.
     * Returns true if connection succeeds, false otherwise.
     */
    suspend fun validateConnection(
        proxyUrl: String,
        appToken: String
    ): Boolean = suspendCoroutine { continuation ->
        try {
            val cleanUrl = proxyUrl.trim().trimEnd('/')
            val url = URL("$cleanUrl/api/v1/health-metrics")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-App-Auth", appToken)
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            try {
                val code = conn.responseCode
                continuation.resume(code in 200..299)
            } catch (e: Exception) {
                continuation.resume(false)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            continuation.resume(false)
        }
    }

    /**
     * Performs a comprehensive health check of the Garmin connection.
     * Tests the full chain: proxy server, app token, Garmin API, and data availability.
     */
    suspend fun performHealthCheck(
        proxyUrl: String,
        appToken: String
    ): HealthCheckResult = suspendCoroutine { continuation ->
        try {
            val trimmedUrl = proxyUrl.trim()
            val fullUrl = "$trimmedUrl/api/v1/health-check"
            Log.d(TAG, "Testing Garmin connection to: $fullUrl")
            val url = URL(fullUrl)
            Log.d(TAG, "URL parsed successfully: ${url.protocol}://${url.host}:${url.port}${url.path}")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("X-App-Auth", appToken)
            // The /health-check endpoint also logs into Garmin, so it can be slow.
            conn.connectTimeout = 15_000
            conn.readTimeout = 45_000
            Log.d(TAG, "Connection configured, attempting to connect...")

            try {
                val code = conn.responseCode
                Log.d(TAG, "Response code: $code")
                if (code == 403) {
                    Log.d(TAG, "403 Forbidden - Invalid app token")
                    continuation.resume(
                        HealthCheckResult(
                            success = false,
                            message = "Invalid app token",
                            proxyRunning = true,
                            garminConnected = false
                        )
                    )
                    return@suspendCoroutine
                }
                
                if (code !in 200..299) {
                    val errorBody = try {
                        conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                    } catch (_: Exception) { "HTTP $code" }
                    Log.d(TAG, "Non-200 response: $errorBody")
                    continuation.resume(
                        HealthCheckResult(
                            success = false,
                            message = "Connection failed: $errorBody",
                            proxyRunning = false,
                            garminConnected = false
                        )
                    )
                    return@suspendCoroutine
                }
                
                val body = conn.inputStream.bufferedReader().readText()
                Log.d(TAG, "Response body: $body")
                val json = JSONObject(body)
                
                val status = json.optString("status", "unknown")
                val proxyRunning = json.optString("proxy", "") == "running"
                val garminConnected = json.optString("garmin_connection", "") == "connected"
                val dataAvailable = json.optBoolean("data_available", false)
                
                Log.d(TAG, "Parsed response - status: $status, proxyRunning: $proxyRunning, garminConnected: $garminConnected, dataAvailable: $dataAvailable")
                
                if (status == "healthy") {
                    continuation.resume(
                        HealthCheckResult(
                            success = true,
                            message = "All systems operational",
                            proxyRunning = proxyRunning,
                            garminConnected = garminConnected,
                            dataAvailable = dataAvailable
                        )
                    )
                } else {
                    val error = json.optString("error", "Unknown error")
                    Log.d(TAG, "Unhealthy status, error: $error")
                    continuation.resume(
                        HealthCheckResult(
                            success = false,
                            message = "Garmin connection failed: $error",
                            proxyRunning = proxyRunning,
                            garminConnected = false
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Network error during health check", e)
                continuation.resume(
                    HealthCheckResult(
                        success = false,
                        message = "Network error: ${e.message}",
                        proxyRunning = false,
                        garminConnected = false
                    )
                )
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Connection setup error", e)
            continuation.resume(
                HealthCheckResult(
                    success = false,
                    message = "Connection error: ${e.message}",
                    proxyRunning = false,
                    garminConnected = false
                )
            )
        }
    }

    private fun parseMetrics(json: JSONObject): GarminMetricsDto {
        return GarminMetricsDto(
            date = json.optString("date", ""),
            vo2Max = json.optDouble("vo2_max").takeIf { !it.isNaN() },
            fitnessAge = json.optInt("fitness_age").takeIf { it > 0 },
            restingHr = json.optInt("resting_hr").takeIf { it > 0 },
            hrvWeeklyAvg = json.optInt("hrv_weekly_avg").takeIf { it > 0 },
            hrvLastNight = json.optInt("hrv_last_night").takeIf { it > 0 },
            sleepScore = json.optInt("sleep_score").takeIf { it > 0 }
        )
    }
}

/**
 * Data class representing Garmin health metrics from the proxy API.
 */
data class GarminMetricsDto(
    val date: String,  // YYYY-MM-DD
    val vo2Max: Double?,
    val fitnessAge: Int?,
    val restingHr: Int?,
    val hrvWeeklyAvg: Int?,
    val hrvLastNight: Int?,
    val sleepScore: Int?
)

/**
 * Exception thrown when the Garmin API returns an error.
 */
class GarminApiException(code: Int, message: String) : Exception("Garmin API error $code: $message")