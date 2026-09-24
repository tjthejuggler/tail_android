package com.example.tail.data.environment

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Network fetchers for the environment habit. All sources are FREE and need
 * NO API KEY (verified 2026-09):
 *
 *  · Open-Meteo Forecast API  — weather history up to ~92 days back
 *    https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&daily=..
 *  · Open-Meteo Archive API   — weather history older than the forecast window
 *    https://archive-api.open-meteo.com/v1/archive?...
 *  · Open-Meteo Air Quality API — European AQI, PM2.5/PM10, O₃, NO₂ + pollen
 *    https://air-quality-api.open-meteo.com/v1/air-quality?...
 *  · NOAA SWPC planetary K-index (27-day / 1-minute feeds, no key)
 *    https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json
 *
 * Every fetch is best-effort: failures return null and the snapshot is still
 * saved with whatever fields succeeded.
 */
object EnvironmentApis {

    private const val TAG = "EnvironmentApis"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000

    /** One GET; returns the body as String or null on any failure. */
    private fun httpGet(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.setRequestProperty("User-Agent", "Tail habit tracker (Android)")
        try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} for ${url.take(80)}")
                null
            } else {
                conn.inputStream.bufferedReader().use { it.readText() }
            }
        } finally {
            conn.disconnect()
        }
    }.onFailure { Log.w(TAG, "GET failed: ${it.message}") }.getOrNull()

    // ── Weather (Open-Meteo) ────────────────────────────────────────────────

    /**
     * Fetches daily weather aggregates for [date] at (lat,lon). Tries the
     * Forecast API first (accurate, covers ~92 days back) and falls back to
     * the Archive API for older dates.
     */
    suspend fun fetchWeather(
        lat: Double,
        lon: Double,
        date: LocalDate
    ): WeatherResult? = withContext(Dispatchers.IO) {
        val params = "latitude=${lat}&longitude=${lon}" +
            "&start_date=${date}&end_date=${date}" +
            "&daily=temperature_2m_max,temperature_2m_min,temperature_2m_mean," +
            "relative_humidity_2m_mean,precipitation_sum,uv_index_max," +
            "surface_pressure_mean,wind_speed_10m_max" +
            "&timezone=auto"
        val forecast = httpGet("https://api.open-meteo.com/v1/forecast?$params")
        val parsed = parseWeather(forecast)
        // The Forecast API only serves ~92 days back — older dates get an
        // error body / null values. Treat an ALL-NULL result as a miss so
        // the Archive API always gets its chance.
        if (parsed != null && parsed.hasAnyData) parsed else {
            val archive = httpGet(
                "https://archive-api.open-meteo.com/v1/archive?$params"
            )
            parseWeather(archive) ?: parsed
        }
    }

    /** Aggregated weather values for one day. */
    data class WeatherResult(
        val tempMinC: Double?,
        val tempMaxC: Double?,
        val tempMeanC: Double?,
        val humidityMean: Double?,
        val precipitationMm: Double?,
        val uvIndexMax: Double?,
        val pressureMeanHpa: Double?,
        val windMaxKmh: Double?
    ) {
        /** True when at least one metric came back non-null. */
        val hasAnyData: Boolean
            get() = tempMinC != null || tempMaxC != null || tempMeanC != null ||
                humidityMean != null || precipitationMm != null || uvIndexMax != null ||
                pressureMeanHpa != null || windMaxKmh != null
    }

    private fun parseWeather(body: String?): WeatherResult? {
        if (body == null) return null
        return runCatching {
            val daily = JSONObject(body).getJSONObject("daily")
            // Each metric is an array with one element (start=end=date).
            fun d(key: String): Double? {
                val arr = daily.optJSONArray(key) ?: return null
                if (arr.length() == 0) return null
                if (arr.isNull(0)) return null
                return arr.getDouble(0)
            }
            WeatherResult(
                tempMaxC = d("temperature_2m_max"),
                tempMinC = d("temperature_2m_min"),
                tempMeanC = d("temperature_2m_mean"),
                humidityMean = d("relative_humidity_2m_mean"),
                precipitationMm = d("precipitation_sum"),
                uvIndexMax = d("uv_index_max"),
                pressureMeanHpa = d("surface_pressure_mean"),
                windMaxKmh = d("wind_speed_10m_max")
            )
        }.onFailure { Log.w(TAG, "weather parse failed: ${it.message}") }.getOrNull()
    }

    // ── Air quality + pollen (Open-Meteo Air Quality) ──────────────────────

    /** Air-quality aggregates for one day (pollen present only in Europe). */
    data class AirResult(
        val aqiEuropeanMax: Int?,
        val pm25Mean: Double?,
        val pm10Mean: Double?,
        val ozoneMean: Double?,
        val no2Mean: Double?,
        val pollenGrassMax: Double?,
        val pollenBirchMax: Double?,
        val pollenAlderMax: Double?,
        val pollenMugwortMax: Double?,
        val pollenOliveMax: Double?,
        val pollenRagweedMax: Double?
    )

    /**
     * Fetches hourly air-quality + pollen for [date] and aggregates to daily
     * values: AQI takes the daily MAX (worst hour), everything else the MEAN.
     */
    suspend fun fetchAirQuality(
        lat: Double,
        lon: Double,
        date: LocalDate
    ): AirResult? = withContext(Dispatchers.IO) {
        val url = "https://air-quality-api.open-meteo.com/v1/air-quality" +
            "?latitude=${lat}&longitude=${lon}" +
            "&start_date=${date}&end_date=${date}&timezone=auto" +
            "&hourly=european_aqi,pm2_5,pm10,ozone,nitrogen_dioxide," +
            "grass_pollen,birch_pollen,alder_pollen,mugwort_pollen," +
            "olive_pollen,ragweed_pollen"
        httpGet(url)?.let { body -> parseAir(body) }
    }

    private fun parseAir(body: String): AirResult? = runCatching {
        val hourly = JSONObject(body).getJSONObject("hourly")
        fun series(key: String): List<Double?> {
            val arr = hourly.optJSONArray(key) ?: return emptyList()
            return (0 until arr.length()).map { if (arr.isNull(it)) null else arr.getDouble(it) }
        }

        fun agg(values: List<Double?>, max: Boolean): Double? {
            val nums = values.filterNotNull()
            if (nums.isEmpty()) return null
            return if (max) nums.max() else nums.sum() / nums.size
        }

        val aqiSeries = series("european_aqi").filterNotNull()
        AirResult(
            aqiEuropeanMax = aqiSeries.maxOrNull()?.toInt(),
            pm25Mean = agg(series("pm2_5"), max = false),
            pm10Mean = agg(series("pm10"), max = false),
            ozoneMean = agg(series("ozone"), max = false),
            no2Mean = agg(series("nitrogen_dioxide"), max = false),
            pollenGrassMax = agg(series("grass_pollen"), max = true),
            pollenBirchMax = agg(series("birch_pollen"), max = true),
            pollenAlderMax = agg(series("alder_pollen"), max = true),
            pollenMugwortMax = agg(series("mugwort_pollen"), max = true),
            pollenOliveMax = agg(series("olive_pollen"), max = true),
            pollenRagweedMax = agg(series("ragweed_pollen"), max = true)
        )
    }.onFailure { Log.w(TAG, "air parse failed: ${it.message}") }.getOrNull()

    // ── Geomagnetic activity (NOAA SWPC) ───────────────────────────────────

    /**
     * Fetches the NOAA 1-minute planetary K-index feed and returns the max Kp
     * observed on [date], or null if the date is outside the feed (~30 days)
     * or the fetch failed.
     *
     * Feed format: header row ["time_tag","Kp","observed","noaa_scale"] then
     * one row per minute with values like "3.33" or "-1.0" (no data).
     */
    suspend fun fetchKpMax(date: LocalDate): Double? = withContext(Dispatchers.IO) {
        val body = httpGet(
            "https://services.swpc.noaa.gov/products/noaa-planetary-k-index.json"
        ) ?: return@withContext null
        runCatching {
            val arr = JSONArray(body)
            val prefix = date.toString()
            var max: Double? = null
            for (i in 1 until arr.length()) { // skip header row
                val row = arr.getJSONArray(i)
                val time = row.getString(0)
                if (!time.startsWith(prefix)) continue
                val kp = row.optString(1, "").toDoubleOrNull() ?: continue
                if (kp < 0) continue // "-1.0" = estimated/missing slot
                if (max == null || kp > max) max = kp
            }
            max
        }.onFailure { Log.w(TAG, "kp parse failed: ${it.message}") }.getOrNull()
    }

    // ── Orchestrator ───────────────────────────────────────────────────────

    /**
     * Fetches all keyless sources in parallel and merges them into a partial
     * [EnvironmentSnapshot] (no water data — that comes from user entry /
     * location memory). Individual source failures leave their fields null.
     */
    suspend fun fetchAll(lat: Double, lon: Double, date: LocalDate, label: String): EnvironmentSnapshot? =
        coroutineScope {
            val weather = async { runCatching { fetchWeather(lat, lon, date) }.getOrNull() }
            val air = async { runCatching { fetchAirQuality(lat, lon, date) }.getOrNull() }
            val kp = async { runCatching { fetchKpMax(date) }.getOrNull() }

            val w = weather.await()
            val a = air.await()
            val k = kp.await()

            val snapshot = EnvironmentSnapshot(
                date = date.toString(),
                lat = lat,
                lon = lon,
                locationLabel = label,
                tempMinC = w?.tempMinC, tempMaxC = w?.tempMaxC, tempMeanC = w?.tempMeanC,
                humidityMean = w?.humidityMean, precipitationMm = w?.precipitationMm,
                uvIndexMax = w?.uvIndexMax, pressureMeanHpa = w?.pressureMeanHpa,
                windMaxKmh = w?.windMaxKmh,
                aqiEuropeanMax = a?.aqiEuropeanMax,
                pm25Mean = a?.pm25Mean, pm10Mean = a?.pm10Mean,
                ozoneMean = a?.ozoneMean, no2Mean = a?.no2Mean,
                pollenGrassMax = a?.pollenGrassMax, pollenBirchMax = a?.pollenBirchMax,
                pollenAlderMax = a?.pollenAlderMax, pollenMugwortMax = a?.pollenMugwortMax,
                pollenOliveMax = a?.pollenOliveMax, pollenRagweedMax = a?.pollenRagweedMax,
                kpIndexMax = k,
                fetchedAt = java.time.Instant.now().toString()
            )
            // Every source failed → null (NOT an empty snapshot). Callers
            // must be able to distinguish "no data" from "captured" so gap
            // repair can retry the day later.
            if (!snapshot.hasAnyData) null else snapshot
        }

    /** Unused-import guard: URLEncoder is used by future geocoding helpers. */
    @Suppress("unused")
    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}
