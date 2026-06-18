package com.example.tail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

private const val TAG = "GarminRepo"

typealias DailyValueMap = Map<String, Int>

/**
 * The Garmin health metrics that can be linked to habits.
 * Each metric type corresponds to a specific health data point from Garmin.
 */
enum class GarminType(val label: String, val description: String) {
    VO2_MAX("VO2 Max", "Cardiovascular fitness score"),
    FITNESS_AGE("Fitness Age", "Biological age based on fitness level"),
    RESTING_HR("Resting HR", "Resting heart rate in BPM"),
    HRV_LAST_NIGHT("HRV Last Night", "Heart rate variability from last night"),
    HRV_WEEKLY_AVG("HRV Weekly Avg", "Average heart rate variability over 7 days"),
    SLEEP_SCORE("Sleep Score", "Overall sleep quality score (0-100)");

    companion object {
        fun fromKey(key: String): GarminType? = entries.find { it.name == key }
    }
}

/**
 * Processes Garmin API data into per-day values for each metric type.
 * Caches monthly data to avoid re-fetching historical months.
 *
 * Cache is stored as JSON files in the app's internal storage under `garmin_cache/`.
 * Each file is named `{YYYY}_{MM}.json` and contains the processed per-day values.
 */
class GarminRepository(private val context: Context) {

    private val service = GarminService()
    private val cacheDir: File
        get() = File(context.filesDir, "garmin_cache").also { it.mkdirs() }

    /**
     * Fetches and processes metrics for TODAY only (for regular polling / test-connection).
     * Returns per-day values for each metric type, keyed by today's date.
     *
     * The Garmin proxy exposes a single-date endpoint and performs a full Garmin
     * login on every call, so looping over an entire month is prohibitively slow
     * (≈15 s timeout × 30 days). The metrics we surface ("most recent" VO2 max,
     * fitness age, resting HR, last-night HRV, sleep score) are point-in-time
     * values that only matter for today, so a single fetch is both correct and fast.
     */
    suspend fun fetchCurrentMonthData(
        proxyUrl: String,
        appToken: String
    ): Map<GarminType, DailyValueMap> = withContext(Dispatchers.IO) {
        try {
            val today = LocalDate.now().toString()
            val result = mutableMapOf<GarminType, MutableMap<String, Int>>()
            val metrics = service.fetchDailyMetrics(proxyUrl, appToken, today)
            if (metrics != null) {
                processMetricsToDaily(metrics, result)
            }
            result.mapValues { it.value.toMap() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch today's Garmin data: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Fetches the entire metric history for a user (all monthly archives).
     * Caches each completed month so subsequent calls skip already-fetched months.
     * Returns per-day values for ALL metric types across ALL months.
     *
     * @param onProgress Called with (completedMonths, totalMonths) for UI progress.
     */
    suspend fun fetchEntireBacklog(
        proxyUrl: String,
        appToken: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Map<GarminType, DailyValueMap> = withContext(Dispatchers.IO) {
        val now = LocalDate.now()
        val allDaily = mutableMapOf<GarminType, MutableMap<String, Int>>()

        // Fetch data for each month from 2 years ago to now
        val startDate = now.minusMonths(24)
        val totalMonths = YearMonth.from(startDate).until(YearMonth.from(now), ChronoUnit.MONTHS).toInt() + 1
        var completedMonths = 0

        var current = YearMonth.from(startDate)
        while (!current.isAfter(YearMonth.from(now))) {
            try {
                val monthData = getCachedOrFetch(
                    proxyUrl, appToken, current.year, current.monthValue
                )
                mergeInto(allDaily, monthData)
                completedMonths++
                onProgress(completedMonths, totalMonths)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch ${current.year}-${current.monthValue}: ${e.message}")
                completedMonths++
                onProgress(completedMonths, totalMonths)
            }
            current = current.plusMonths(1)
        }

        allDaily.mapValues { it.value.toMap() }
    }

    /**
     * Validates the Garmin proxy connection.
     */
    suspend fun validateConnection(
        proxyUrl: String,
        appToken: String
    ): Boolean = withContext(Dispatchers.IO) {
        service.validateConnection(proxyUrl, appToken)
    }

    /**
     * Performs a comprehensive health check of the Garmin connection.
     * Tests the full chain: proxy server, app token, Garmin API, and data availability.
     */
    suspend fun performHealthCheck(
        proxyUrl: String,
        appToken: String
    ): GarminService.HealthCheckResult = withContext(Dispatchers.IO) {
        service.performHealthCheck(proxyUrl, appToken)
    }

    /**
     * Returns cached data for a month if available, otherwise fetches from API and caches.
     * Current month is never cached (always fetched fresh).
     */
    private suspend fun getCachedOrFetch(
        proxyUrl: String,
        appToken: String,
        year: Int,
        month: Int
    ): Map<GarminType, DailyValueMap> {
        val now = YearMonth.now()
        val isCurrentMonth = year == now.year && month == now.monthValue

        // Try cache first (but not for current month — it's still in progress)
        if (!isCurrentMonth) {
            val cached = loadFromCache(year, month)
            if (cached != null) return cached
        }

        // Fetch from API - fetch each day of the month
        val daily = fetchMonthData(proxyUrl, appToken, year, month)

        // Cache completed months
        if (!isCurrentMonth) {
            saveToCache(year, month, daily)
        }

        return daily
    }

    /**
     * Fetches all days in a month and processes them into per-day values.
     */
    private suspend fun fetchMonthData(
        proxyUrl: String,
        appToken: String,
        year: Int,
        month: Int
    ): Map<GarminType, DailyValueMap> {
        val result = mutableMapOf<GarminType, MutableMap<String, Int>>()
        val yearMonth = YearMonth.of(year, month)
        val daysInMonth = yearMonth.lengthOfMonth()

        for (day in 1..daysInMonth) {
            val dateStr = String.format("%04d-%02d-%02d", year, month, day)
            try {
                val metrics = service.fetchDailyMetrics(proxyUrl, appToken, dateStr)
                if (metrics != null) {
                    processMetricsToDaily(metrics, result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch $dateStr: ${e.message}")
            }
        }

        return result.mapValues { it.value.toMap() }
    }

    /**
     * Processes a single day's metrics into the daily map.
     */
    private fun processMetricsToDaily(
        metrics: GarminMetricsDto,
        result: MutableMap<GarminType, MutableMap<String, Int>>
    ) {
        val date = metrics.date

        metrics.vo2Max?.let {
            val dayMap = result.getOrPut(GarminType.VO2_MAX) { mutableMapOf() }
            dayMap[date] = it.toInt()
        }

        metrics.fitnessAge?.let {
            val dayMap = result.getOrPut(GarminType.FITNESS_AGE) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.restingHr?.let {
            val dayMap = result.getOrPut(GarminType.RESTING_HR) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.hrvLastNight?.let {
            val dayMap = result.getOrPut(GarminType.HRV_LAST_NIGHT) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.hrvWeeklyAvg?.let {
            val dayMap = result.getOrPut(GarminType.HRV_WEEKLY_AVG) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.sleepScore?.let {
            val dayMap = result.getOrPut(GarminType.SLEEP_SCORE) { mutableMapOf() }
            dayMap[date] = it
        }
    }

    /**
     * Computes habit increments from daily values using the configured threshold.
     * Any value >= threshold gives 1 point. Values below threshold give 0 points.
     *
     * Returns a map of date → increment count (0 or 1).
     */
    fun computeIncrements(
        dailyValues: DailyValueMap,
        threshold: Int
    ): Map<String, Int> {
        if (threshold <= 0) return emptyMap()
        return dailyValues.mapValues { (_, value) ->
            if (value >= threshold) 1 else 0
        }
    }

    // ── Cache I/O ────────────────────────────────────────────────────────────

    private fun cacheFile(year: Int, month: Int): File {
        val monthStr = month.toString().padStart(2, '0')
        return File(cacheDir, "${year}_${monthStr}.json")
    }

    private fun saveToCache(
        year: Int,
        month: Int,
        data: Map<GarminType, DailyValueMap>
    ) {
        try {
            val json = JSONObject()
            for ((type, dayMap) in data) {
                val typeJson = JSONObject()
                for ((date, value) in dayMap) {
                    typeJson.put(date, value)
                }
                json.put(type.name, typeJson)
            }
            cacheFile(year, month).writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write cache: ${e.message}")
        }
    }

    private fun loadFromCache(
        year: Int,
        month: Int
    ): Map<GarminType, DailyValueMap>? {
        val file = cacheFile(year, month)
        if (!file.exists()) return null
        return try {
            val json = JSONObject(file.readText())
            val result = mutableMapOf<GarminType, DailyValueMap>()
            for (typeName in json.keys()) {
                val type = GarminType.fromKey(typeName) ?: continue
                val typeJson = json.getJSONObject(typeName)
                val dayMap = mutableMapOf<String, Int>()
                for (date in typeJson.keys()) {
                    dayMap[date] = typeJson.getInt(date)
                }
                result[type] = dayMap
            }
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cache: ${e.message}")
            null
        }
    }

    /** Clears all cached Garmin data. */
    fun clearCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun mergeInto(
        target: MutableMap<GarminType, MutableMap<String, Int>>,
        source: Map<GarminType, DailyValueMap>
    ) {
        for ((type, dayMap) in source) {
            val targetDay = target.getOrPut(type) { mutableMapOf() }
            for ((date, value) in dayMap) {
                targetDay[date] = value
            }
        }
    }
}