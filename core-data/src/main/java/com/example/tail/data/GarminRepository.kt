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
    // Derived metric: calculated on-demand as FITNESS_AGE - biological_age
    FITNESS_AGE_DISTANCE("Fitness Age Distance", "Difference between fitness age and biological age"),
    RESTING_HR("Resting HR", "Resting heart rate in BPM"),
    HRV_LAST_NIGHT("HRV Last Night", "Heart rate variability from last night"),
    HRV_WEEKLY_AVG("HRV Weekly Avg", "Average heart rate variability over 7 days"),
    SLEEP_SCORE("Sleep Score", "Overall sleep quality score (0-100)"),
    SLEEP_DURATION_MINUTES("Sleep Length", "Total sleep time in minutes"),
    STEPS("Steps", "Daily step count"),
    ALTITUDE_ASCENT_METERS("Altitude Ascent", "Total elevation climbed in meters"),
    DISTANCE_METERS("Distance", "Total distance traveled in meters"),
    CALORIES("Calories", "Total calories burned"),
    ACTIVE_MINUTES("Active Minutes", "Total active minutes"),
    RUN_MINUTES("Run Minutes", "Running minutes from recorded activities"),
    BIKE_MINUTES("Bike Minutes", "Cycling minutes from recorded activities"),
    SWIM_MINUTES("Swim Minutes", "Swimming minutes from recorded activities"),
    FLOORS_CLIMBED("Floors Climbed", "Total floors climbed"),
    MIN_HR("Min HR", "Minimum heart rate in BPM"),
    MAX_HR("Max HR", "Maximum heart rate in BPM"),
    STRESS_LEVEL("Stress Level", "Average daily stress level (0-100)");

    /**
     * Formats a raw stored daily value for human display.
     *
     * Values are stored in their native unit (e.g. distance in metres) so the
     * data layer stays integer-clean and matches the import format. Distance is
     * the only metric we present in a derived unit: metres → kilometres as a
     * whole number (e.g. 12345 → "12 km").
     *
     * Fitness age is stored as hundredths of a year (e.g., 3704 for 37.04) to preserve
     * 2 decimal places from Garmin's API. Display shows 2 decimal places
     * (e.g., 3704 → "37.04", 3750 → "37.50").
     * Fitness Age Distance is also stored as hundredths of a year.
     */
    fun formatDisplayValue(rawValue: Int): String = when (this) {
        DISTANCE_METERS -> "${rawValue / 1000} km"
        SLEEP_DURATION_MINUTES -> "${rawValue / 60}h${(rawValue % 60).toString().padStart(2, '0')}m"
        FITNESS_AGE, FITNESS_AGE_DISTANCE -> {
            // Convert from hundredths to years and display with 2 decimal places
            val years = rawValue / 100.0
            String.format("%.2f", years)
        }
        else -> rawValue.toString()
    }

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
     * Watch-local start times ("HH:mm:ss") of the day's earliest activity per
     * type per date, as served by the proxy. Populated while fetching daily
     * metrics (current-month fetches always hit the proxy); used to place
     * run/bike/swim events on the daily schedule at the ACTIVITY time.
     */
    private val activityStartTimes = mutableMapOf<GarminType, MutableMap<String, String>>()

    /**
     * Fetches and processes metrics for the last 7 days (for regular polling / test-connection).
     * Returns per-day values for each metric type, keyed by date.
     *
     * The Garmin proxy caches the last 7 days of data locally, so we can fetch
     * all of them without hitting Garmin's rate limits. The metrics we surface
     * (VO2 max, fitness age, resting HR, last-night HRV, sleep score) are
     * point-in-time values that matter for recent days.
     */
    suspend fun fetchCurrentMonthData(
        proxyUrl: String,
        appToken: String,
        dateOfBirth: String = ""
    ): Map<GarminType, DailyValueMap> = withContext(Dispatchers.IO) {
        try {
            val result = mutableMapOf<GarminType, MutableMap<String, Int>>()
            val today = LocalDate.now()
            
            // Fetch last 7 days (including today)
            repeat(7) { daysAgo ->
                val date = today.minusDays(daysAgo.toLong()).toString()
                val metrics = service.fetchDailyMetrics(proxyUrl, appToken, date)
                if (metrics != null) {
                    processMetricsToDaily(metrics, result, dateOfBirth)
                }
            }
            result.mapValues { it.value.toMap() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch Garmin data: ${e.message}")
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
        dateOfBirth: String = "",
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
                    proxyUrl, appToken, current.year, current.monthValue, dateOfBirth
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
        month: Int,
        dateOfBirth: String = ""
    ): Map<GarminType, DailyValueMap> {
        val now = YearMonth.now()
        val isCurrentMonth = year == now.year && month == now.monthValue

        // Try cache first (but not for current month — it's still in progress)
        if (!isCurrentMonth) {
            val cached = loadFromCache(year, month)
            if (cached != null) return cached
        }

        // Fetch from API - fetch each day of the month
        val daily = fetchMonthData(proxyUrl, appToken, year, month, dateOfBirth)

        // Cache completed months
        if (!isCurrentMonth) {
            saveToCache(year, month, daily)
            saveActivityTimesToCache()
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
        month: Int,
        dateOfBirth: String = ""
    ): Map<GarminType, DailyValueMap> {
        val result = mutableMapOf<GarminType, MutableMap<String, Int>>()
        val yearMonth = YearMonth.of(year, month)

        // Never request days that haven't happened yet. For the current month
        // we stop at today; for past months we use the full month length.
        val today = LocalDate.now()
        val lastDay = if (year == today.year && month == today.monthValue) {
            today.dayOfMonth
        } else {
            yearMonth.lengthOfMonth()
        }

        for (day in 1..lastDay) {
            val dateStr = String.format("%04d-%02d-%02d", year, month, day)
            try {
                val metrics = service.fetchDailyMetrics(proxyUrl, appToken, dateStr)
                if (metrics != null) {
                    processMetricsToDaily(metrics, result, dateOfBirth)
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
        result: MutableMap<GarminType, MutableMap<String, Int>>,
        dateOfBirth: String = ""
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

        metrics.minHr?.let {
            val dayMap = result.getOrPut(GarminType.MIN_HR) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.maxHr?.let {
            val dayMap = result.getOrPut(GarminType.MAX_HR) { mutableMapOf() }
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

        metrics.sleepDurationMinutes?.let {
            val dayMap = result.getOrPut(GarminType.SLEEP_DURATION_MINUTES) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.steps?.let {
            val dayMap = result.getOrPut(GarminType.STEPS) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.altitudeAscentMeters?.let {
            val dayMap = result.getOrPut(GarminType.ALTITUDE_ASCENT_METERS) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.distanceMeters?.let {
            val dayMap = result.getOrPut(GarminType.DISTANCE_METERS) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.calories?.let {
            val dayMap = result.getOrPut(GarminType.CALORIES) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.activeMinutes?.let {
            val dayMap = result.getOrPut(GarminType.ACTIVE_MINUTES) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.runMinutes?.let {
            val dayMap = result.getOrPut(GarminType.RUN_MINUTES) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.bikeMinutes?.let {
            val dayMap = result.getOrPut(GarminType.BIKE_MINUTES) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.swimMinutes?.let {
            val dayMap = result.getOrPut(GarminType.SWIM_MINUTES) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.runStartTime?.let {
            val times = activityStartTimes.getOrPut(GarminType.RUN_MINUTES) { mutableMapOf() }
            times[date] = it
        }
        metrics.bikeStartTime?.let {
            val times = activityStartTimes.getOrPut(GarminType.BIKE_MINUTES) { mutableMapOf() }
            times[date] = it
        }
        metrics.swimStartTime?.let {
            val times = activityStartTimes.getOrPut(GarminType.SWIM_MINUTES) { mutableMapOf() }
            times[date] = it
        }

        metrics.floorsClimbed?.let {
            val dayMap = result.getOrPut(GarminType.FLOORS_CLIMBED) { mutableMapOf() }
            dayMap[date] = it
        }

        metrics.stressScore?.let {
            val dayMap = result.getOrPut(GarminType.STRESS_LEVEL) { mutableMapOf() }
            dayMap[date] = it
        }
    }

    /**
     * Watch-local start time ("HH:mm:ss") of the earliest activity of [type]
     * on [date], when the proxy served one; null when no time is known for
     * that day (rest day, aggregate metric, or cached historical month).
     */
    fun activityStartTime(type: GarminType, date: String): String? =
        activityStartTimes[type]?.get(date)

    /**
     * Cached daily value for [type] on [date] (ISO "yyyy-MM-dd"), or null
     * when the month holding that date has no cache. Reads the monthly
     * cache only — no network. Used for schedule block durations.
     */
    fun cachedDailyValue(type: GarminType, date: String): Int? {
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        return loadFromCache(parsed.year, parsed.monthValue)?.get(type)?.get(date)
    }

    /**
     * Persists the in-memory activity start times (run/bike/swim) alongside the
     * monthly value cache, so the cached re-apply path (loadAllCachedData +
     * applyGarminData) can also stamp habit timestamps with the ACTUAL activity
     * start time instead of falling back to the sync time. Written in the same
     * pass as [mergeAndCacheDailyData] writes values, keeping the two in sync.
     */
    private fun activityTimesFile(): File = File(cacheDir, "activity_times.json")

    private fun saveActivityTimesToCache() {
        if (activityStartTimes.isEmpty()) return
        try {
            val json = JSONObject()
            for ((type, dayMap) in activityStartTimes) {
                val typeJson = JSONObject()
                for ((date, time) in dayMap) {
                    typeJson.put(date, time)
                }
                json.put(type.name, typeJson)
            }
            activityTimesFile().writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write activity times cache: ${e.message}")
        }
    }

    /** Loads persisted activity start times into the in-memory map. */
    private fun loadActivityTimesFromCache() {
        val file = activityTimesFile()
        if (!file.exists()) return
        try {
            val json = JSONObject(file.readText())
            for (typeName in json.keys()) {
                val type = GarminType.fromKey(typeName) ?: continue
                val typeJson = json.getJSONObject(typeName)
                val times = activityStartTimes.getOrPut(type) { mutableMapOf() }
                for (date in typeJson.keys()) {
                    times[date] = typeJson.getString(date)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read activity times cache: ${e.message}")
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

    fun loadFromCache(
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
                // Skip derived metrics - they are calculated on-demand
                if (type == GarminType.FITNESS_AGE_DISTANCE) continue
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

    /**
     * Persists freshly-fetched daily data (e.g. from the proxy poll / 7-day fetch)
     * into the monthly cache files, MERGING with whatever is already cached.
     *
     * Fresh values WIN over existing cached values for the same date — the laptop
     * proxy/fetch pipeline is the source of truth for recent days, so if the proxy
     * reports a corrected value it overwrites the stale one. Dates not present in
     * [freshData] are left untouched, so the historic backlog is never lost.
     *
     * This is what makes the 7-day poll durable: previously the poll only updated
     * the in-memory StateFlow and never touched disk, so a restart (or a re-merge)
     * could surface stale data. Now every poll is written through to cache.
     */
    fun mergeAndCacheDailyData(freshData: Map<GarminType, DailyValueMap>) {
        // Bucket the incoming (type → date → value) data by year-month so we only
        // rewrite the month files that actually changed.
        val byMonth = mutableMapOf<Pair<Int, Int>, MutableMap<GarminType, MutableMap<String, Int>>>()
        for ((type, dayMap) in freshData) {
            for ((dateStr, value) in dayMap) {
                val date = try {
                    LocalDate.parse(dateStr)
                } catch (e: Exception) {
                    Log.w(TAG, "mergeAndCacheDailyData: bad date '$dateStr', skipping")
                    continue
                }
                val key = date.year to date.monthValue
                byMonth.getOrPut(key) { mutableMapOf() }
                    .getOrPut(type) { mutableMapOf() }[dateStr] = value
            }
        }

        for ((yearMonth, monthFresh) in byMonth) {
            val (year, month) = yearMonth
            val existing = loadFromCache(year, month) ?: emptyMap()

            val merged = mutableMapOf<GarminType, MutableMap<String, Int>>()
            for ((type, dayMap) in existing) {
                merged[type] = dayMap.toMutableMap()
            }
            // Fresh data overwrites existing for the same date.
            for ((type, dayMap) in monthFresh) {
                val target = merged.getOrPut(type) { mutableMapOf() }
                for ((date, value) in dayMap) {
                    target[date] = value
                }
            }
            saveToCache(year, month, merged)
        }
        if (byMonth.isNotEmpty()) {
            Log.d(TAG, "mergeAndCacheDailyData: persisted ${byMonth.size} month(s) to cache")
            saveActivityTimesToCache()
        }
    }

    /**
     * Loads all cached Garmin data from the cache directory.
     * Returns a map of GarminType to date-value pairs for all cached months.
     */
    fun loadAllCachedData(): Map<GarminType, DailyValueMap> {
        // Restore persisted activity start times first so applyGarminData can
        // use them for habit timestamps on this cached (non-fetch) path too.
        loadActivityTimesFromCache()
        val allData = mutableMapOf<GarminType, MutableMap<String, Int>>()
        val cacheFiles = cacheDir.listFiles()
        Log.d(TAG, "loadAllCachedData: Found ${cacheFiles?.size ?: 0} cache files")
        
        cacheFiles?.forEach { file ->
            try {
                // Parse filename format: YYYY_MM.json
                val nameWithoutExt = file.nameWithoutExtension
                val parts = nameWithoutExt.split("_")
                if (parts.size != 2) {
                    Log.w(TAG, "loadAllCachedData: Skipping file with unexpected name: ${file.name}")
                    return@forEach
                }
                
                val year = parts[0].toIntOrNull() ?: return@forEach
                val month = parts[1].toIntOrNull() ?: return@forEach
                
                val monthData = loadFromCache(year, month)
                if (monthData == null) {
                    Log.w(TAG, "loadAllCachedData: Failed to load cache for $year-$month")
                    return@forEach
                }
                
                Log.d(TAG, "loadAllCachedData: Loaded ${monthData.size} types from $year-$month")
                
                // Merge this month's data into allData
                for ((type, dayMap) in monthData) {
                    val typeData = allData.getOrPut(type) { mutableMapOf() }
                    for ((date, value) in dayMap) {
                        typeData[date] = value
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cache file ${file.name}: ${e.message}")
            }
        }
        
        Log.d(TAG, "loadAllCachedData: Returning ${allData.size} Garmin types with ${allData.values.sumOf { it.size }} total dates")
        return allData.mapValues { it.value.toMap() }
    }

    /**
     * Imports historic Garmin data from a JSON file generated by the desktop import script.
     * The JSON file should contain metrics in the same format as the cache files:
     * {
     *   "VO2_MAX": {"2024-01-01": 45, "2024-01-02": 46, ...},
     *   "FITNESS_AGE": {"2024-01-01": 32, ...},
     *   ...
     * }
     *
     * @param jsonFile The JSON file containing the imported data
     * @param onProgress Called with (processedDates, totalDates) for UI progress
     * @return ImportResult containing statistics about the import
     */
    suspend fun importFromJson(
        jsonFile: File,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ImportResult = withContext(Dispatchers.IO) {
        try {
            val jsonText = jsonFile.readText()
            val json = JSONObject(jsonText)
            
            val jsonKeys = mutableListOf<String>()
            json.keys().forEach { jsonKeys.add(it) }
            Log.d(TAG, "Import: JSON keys found: ${jsonKeys.joinToString()}")
            
            val allData = mutableMapOf<GarminType, MutableMap<String, Int>>()
            var totalDates = 0
            
            // Parse all metrics from JSON
            for (typeName in json.keys()) {
                val type = GarminType.fromKey(typeName) ?: continue
                // Skip derived metrics - they are calculated on-demand
                if (type == GarminType.FITNESS_AGE_DISTANCE) continue
                val typeJson = json.getJSONObject(typeName)
                val dayMap = mutableMapOf<String, Int>()
                
                for (date in typeJson.keys()) {
                    dayMap[date] = typeJson.getInt(date)
                    totalDates++
                }
                
                if (dayMap.isNotEmpty()) {
                    allData[type] = dayMap
                    Log.d(TAG, "Import: Parsed ${dayMap.size} dates for $typeName")
                }
            }

            // Optional activity start-times section emitted by garmin_import.py
            // ("ACTIVITY_START_TIMES": { "RUN_MINUTES": { "2026-01-05": "07:12:33" } }).
            // Merged into the persisted activity-times cache so historic
            // run/bike/swim activities can be placed at their real watch
            // start times on the schedule timeline.
            val timesSection = json.optJSONObject("ACTIVITY_START_TIMES")
            if (timesSection != null) {
                for (catName in timesSection.keys()) {
                    val type = GarminType.fromKey(catName) ?: continue
                    val dayTimes = timesSection.getJSONObject(catName)
                    val times = activityStartTimes.getOrPut(type) { mutableMapOf() }
                    for (day in dayTimes.keys()) {
                        times[day] = dayTimes.getString(day)
                    }
                }
                saveActivityTimesToCache()
                Log.d(TAG, "Import: merged activity start times for " +
                    "${timesSection.length()} sport categories")
            }

            Log.d(TAG, "Import: Total types parsed: ${allData.size}, total dates: $totalDates")
            
            if (allData.isEmpty()) {
                return@withContext ImportResult(
                    success = false,
                    message = "No valid Garmin data found in file",
                    metricsImported = emptyMap()
                )
            }
            
            // Group data by month for cache storage
            val monthlyData = mutableMapOf<Pair<Int, Int>, MutableMap<GarminType, MutableMap<String, Int>>>()
            var processedDates = 0
            
            for ((type, dayMap) in allData) {
                for ((dateStr, value) in dayMap) {
                    try {
                        val date = LocalDate.parse(dateStr)
                        val monthKey = date.year to date.monthValue
                        
                        val monthData = monthlyData.getOrPut(monthKey) { mutableMapOf() }
                        val typeData = monthData.getOrPut(type) { mutableMapOf() }
                        typeData[dateStr] = value
                        
                        processedDates++
                        onProgress(processedDates, totalDates)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse date: $dateStr")
                    }
                }
            }
            
            // Merge imported data with existing cache and save
            val metricsImported = mutableMapOf<GarminType, Int>()
            
            for ((yearMonth, monthData) in monthlyData) {
                val (year, month) = yearMonth
                
                // Load existing cache for this month
                val existing = loadFromCache(year, month) ?: emptyMap()
                
                // Merge imported data with existing data
                val merged = mutableMapOf<GarminType, MutableMap<String, Int>>()
                for ((type, dayMap) in existing) {
                    merged[type] = dayMap.toMutableMap()
                }
                
                for ((type, dayMap) in monthData) {
                    val target = merged.getOrPut(type) { mutableMapOf() }
                    for ((date, value) in dayMap) {
                        target[date] = value
                    }
                    
                    // Track counts
                    metricsImported[type] = (metricsImported[type] ?: 0) + dayMap.size
                }
                
                // Save merged data to cache
                saveToCache(year, month, merged)
            }
            
            ImportResult(
                success = true,
                message = "Successfully imported ${metricsImported.values.sum()} data points",
                metricsImported = metricsImported
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import Garmin data: ${e.message}", e)
            ImportResult(
                success = false,
                message = "Import failed: ${e.message}",
                metricsImported = emptyMap()
            )
        }
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

/**
 * Result of a Garmin data import operation.
 */
data class ImportResult(
    val success: Boolean,
    val message: String,
    val metricsImported: Map<GarminType, Int>
)