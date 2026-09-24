package com.example.tail.data.environment

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

private const val TAG = "EnvironmentRepo"
private const val PREFS_NAME = "tail_environment_prefs"
/** Map of date-string ("YYYY-MM-DD") → snapshot JSON. */
private const val KEY_SNAPSHOTS = "environment_snapshots"
/** Map of location label → water hardness ppm ("hardness:<label>" keys). */
private const val KEY_PREFIX_WATER = "hardness:"

/**
 * Storage + orchestration for the environment habit.
 *
 * ## Storage
 * Snapshots live in ONE SharedPreferences file ([PREFS_NAME]) as a single
 * JSON map of date → [EnvironmentSnapshot.toJson]. This file is included in
 * backups via the generic `extraPrefs` mechanism (BackupManager lists this
 * prefs name), so no bespoke backup code is needed.
 *
 * ## Water-hardness memory
 * Tap-water hardness has no free global API, so the user enters it manually.
 * Values are remembered PER LOCATION LABEL: when a new day is captured at a
 * location with a remembered hardness, the value auto-fills into the
 * snapshot (source = "location-memory"). Municipal water supplies rarely
 * change, so one manual entry keeps paying off.
 *
 * ## Capture
 * [captureForDate] runs after the day's location is known (coords present in
 * [com.example.tail.data.location.LocationRepository]), fetches all keyless
 * APIs in parallel, merges remembered water data, and stores the snapshot.
 * Re-capture overwrites network fields but PRESERVES manual water values
 * unless the user explicitly overwrites them.
 */
class EnvironmentRepository(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Volatile private var cachedSnapshotMap: Map<String, String>? = null

    // ── Reads ───────────────────────────────────────────────────────────────

    /** Returns the snapshot for [date], or null if none stored. */
    fun getSnapshot(date: LocalDate): EnvironmentSnapshot? {
        return loadMap()[date.toString()]?.let { EnvironmentSnapshot.fromJson(it) }
    }

    /** Returns all stored snapshots keyed by date string (one JSON parse pass). */
    fun getAllSnapshots(): Map<String, EnvironmentSnapshot> {
        return loadMap().mapNotNull { (date, json) ->
            EnvironmentSnapshot.fromJson(json)?.let { date to it }
        }.toMap()
    }

    /**
     * Deletes every stored snapshot (full-redo mode). Water-hardness
     * location memory is intentionally KEPT — it's user-curated data.
     */
    fun clearAllSnapshots() {
        prefs.edit().remove(KEY_SNAPSHOTS).apply()
        cachedSnapshotMap = emptyMap()
        Log.i(TAG, "clearAllSnapshots: all snapshots removed")
    }

    // ── Water-hardness memory ───────────────────────────────────────────────

    /** Remembered water hardness (ppm) for [label], or null. */
    fun getWaterHardnessForLocation(label: String): Double? {
        if (label.isBlank()) return null
        val raw = prefs.getString(KEY_PREFIX_WATER + label.lowercase().trim(), null)
        return raw?.toDoubleOrNull()
    }

    /** Saves [ppm] as the remembered hardness for [label]. */
    fun setWaterHardnessForLocation(label: String, ppm: Double) {
        prefs.edit()
            .putString(KEY_PREFIX_WATER + label.lowercase().trim(), ppm.toString())
            .apply()
    }

    /** Removes the remembered hardness for [label]. */
    fun removeWaterHardnessForLocation(label: String) {
        prefs.edit().remove(KEY_PREFIX_WATER + label.lowercase().trim()).apply()
    }

    /** All remembered location → ppm entries (for the settings screen list). */
    fun getAllWaterHardness(): Map<String, Double> {
        val out = mutableMapOf<String, Double>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX_WATER) && value is String) {
                value.toDoubleOrNull()?.let { out[key.removePrefix(KEY_PREFIX_WATER)] = it }
            }
        }
        return out
    }

    // ── Writes ──────────────────────────────────────────────────────────────

    /** Sets the water hardness directly on the day's snapshot (source: manual). */
    fun setWaterHardnessForDate(date: LocalDate, ppm: Double) {
        val existing = getSnapshot(date)
        val updated = (existing ?: EnvironmentSnapshot(
            date = date.toString(), lat = 0.0, lon = 0.0
        )).copy(
            waterHardnessPpm = ppm,
            waterHardnessSource = "manual"
        )
        save(updated)
    }

    /** Removes the water value from the day's snapshot. */
    fun clearWaterHardnessForDate(date: LocalDate) {
        val existing = getSnapshot(date) ?: return
        save(existing.copy(waterHardnessPpm = null, waterHardnessSource = ""))
    }

    /**
     * Full (re)fetch for [date] using the day's coords. Returns the stored
     * snapshot, or null when the coords are unknown (can't fetch without a
     * location) or every source failed AND no snapshot existed yet.
     * Manual water values already on the snapshot are preserved.
     */
    suspend fun captureForDate(date: LocalDate, lat: Double, lon: Double, label: String): EnvironmentSnapshot? =
        withContext(Dispatchers.IO) {
            val existing = getSnapshot(date)
            val fresh = EnvironmentApis.fetchAll(lat, lon, date, label)

            // Every source failed AND nothing prior → do NOT store anything.
            // Storing a marker would let the backlog skip this day forever.
            if (fresh == null && (existing == null || !existing.hasAnyData)) {
                return@withContext existing?.takeIf { it.hasAnyData }
            }

            val merged = merge(existing, fresh, date, lat, lon, label)

            // Auto-fill water hardness from location memory when unset.
            val withWater = if (merged.waterHardnessPpm == null) {
                val remembered = getWaterHardnessForLocation(label)
                if (remembered != null) merged.copy(
                    waterHardnessPpm = remembered,
                    waterHardnessSource = "location-memory"
                ) else merged
            } else merged

            save(withWater)
            withWater
        }

    /**
     * Merges a fresh fetch with the existing snapshot. Fresh network values
     * win when present; existing manual water data is always kept unless the
     * fresh run explicitly supplies new water data (it never does — water is
     * manual-only).
     */
    private fun merge(
        existing: EnvironmentSnapshot?,
        fresh: EnvironmentSnapshot?,
        date: LocalDate,
        lat: Double,
        lon: Double,
        label: String
    ): EnvironmentSnapshot {
        if (fresh == null) {
            return (existing ?: EnvironmentSnapshot(date.toString(), lat, lon, label))
                .copy(lat = lat, lon = lon, locationLabel = label)
        }
        val base = existing ?: EnvironmentSnapshot(date.toString(), lat, lon, label)
        return base.copy(
            lat = lat, lon = lon, locationLabel = label,
            tempMinC = fresh.tempMinC ?: base.tempMinC,
            tempMaxC = fresh.tempMaxC ?: base.tempMaxC,
            tempMeanC = fresh.tempMeanC ?: base.tempMeanC,
            humidityMean = fresh.humidityMean ?: base.humidityMean,
            precipitationMm = fresh.precipitationMm ?: base.precipitationMm,
            uvIndexMax = fresh.uvIndexMax ?: base.uvIndexMax,
            pressureMeanHpa = fresh.pressureMeanHpa ?: base.pressureMeanHpa,
            windMaxKmh = fresh.windMaxKmh ?: base.windMaxKmh,
            aqiEuropeanMax = fresh.aqiEuropeanMax ?: base.aqiEuropeanMax,
            pm25Mean = fresh.pm25Mean ?: base.pm25Mean,
            pm10Mean = fresh.pm10Mean ?: base.pm10Mean,
            ozoneMean = fresh.ozoneMean ?: base.ozoneMean,
            no2Mean = fresh.no2Mean ?: base.no2Mean,
            pollenGrassMax = fresh.pollenGrassMax ?: base.pollenGrassMax,
            pollenBirchMax = fresh.pollenBirchMax ?: base.pollenBirchMax,
            pollenAlderMax = fresh.pollenAlderMax ?: base.pollenAlderMax,
            pollenMugwortMax = fresh.pollenMugwortMax ?: base.pollenMugwortMax,
            pollenOliveMax = fresh.pollenOliveMax ?: base.pollenOliveMax,
            pollenRagweedMax = fresh.pollenRagweedMax ?: base.pollenRagweedMax,
            kpIndexMax = fresh.kpIndexMax ?: base.kpIndexMax,
            waterHardnessPpm = base.waterHardnessPpm,
            waterHardnessSource = base.waterHardnessSource,
            fetchedAt = fresh.fetchedAt
        )
    }

    /**
     * Captures a RANGE of days at ONE location with as few network calls as
     * possible: the range is split into ~35-day windows and each window is
     * fetched with a handful of range requests (weather + air quality) —
     * turning a 2 000-day backlog into ~150 requests instead of ~6 000.
     *
     * Only days present in [dates] are stored (resumable). Water hardness is
     * auto-filled from location memory when known; manual values preserved.
     * Kp is not fetched here (NOAA only serves ~30 days; the single-day
     * capture path covers recent days).
     *
     * @param onWindow called after each window (done, total)
     * @return number of snapshots stored/updated
     */
    suspend fun captureRange(
        dates: List<LocalDate>,
        coords: Pair<Double, Double>,
        label: String,
        onWindow: (Int, Int) -> Unit = { _, _ -> }
    ): Int {
        if (dates.isEmpty()) return 0
        val sorted = dates.sorted()
        val start = sorted.first()
        val end = sorted.last()
        val windows = mutableListOf<Pair<LocalDate, LocalDate>>()
        var cursor = start
        while (!cursor.isAfter(end)) {
            val wEnd = minOf(cursor.plusDays(34), end)
            windows.add(cursor to wEnd)
            cursor = wEnd.plusDays(1)
        }

        val wanted = sorted.toHashSet()
        var stored = 0
        var done = 0
        for ((wStart, wEnd) in windows) {
            val weather = runCatching {
                EnvironmentApis.fetchWeatherRange(coords.first, coords.second, wStart, wEnd)
            }.onFailure { Log.w(TAG, "range $wStart..$wEnd failed: ${it.message}") }
                .getOrDefault(emptyMap())
            val air = runCatching {
                EnvironmentApis.fetchAirQualityRange(coords.first, coords.second, wStart, wEnd)
            }.onFailure { Log.w(TAG, "air range $wStart..$wEnd failed: ${it.message}") }
                .getOrDefault(emptyMap())

            var d = wStart
            while (!d.isAfter(wEnd)) {
                if (d in wanted) {
                    val existing = getSnapshot(d)
                    val daySnap = snapFromWeather(d, weather[d])?.let { wSnap ->
                        air[d]?.let { mergeAir(wSnap, it) } ?: wSnap
                    }
                    if (daySnap != null || existing != null) {
                        var merged = merge(existing, daySnap, d, coords.first, coords.second, label)
                        // Water auto-fill from location memory when unset.
                        if (merged.waterHardnessPpm == null) {
                            getWaterHardnessForLocation(label)?.let { ppm ->
                                merged = merged.copy(
                                    waterHardnessPpm = ppm,
                                    waterHardnessSource = "location-memory"
                                )
                            }
                        }
                        if (merged.hasAnyData) {
                            save(merged)
                            stored++
                        }
                    }
                }
                d = d.plusDays(1)
            }
            done++
            onWindow(done, windows.size)
            if (done < windows.size) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Thread.sleep(1_500)
                }
            }
        }
        return stored
    }

    private fun snapFromWeather(date: LocalDate, w: EnvironmentApis.WeatherResult?): EnvironmentSnapshot? {
        if (w == null) return null
        return EnvironmentSnapshot(
            date = date.toString(), lat = 0.0, lon = 0.0,
            tempMinC = w.tempMinC, tempMaxC = w.tempMaxC, tempMeanC = w.tempMeanC,
            humidityMean = w.humidityMean, precipitationMm = w.precipitationMm,
            uvIndexMax = w.uvIndexMax, pressureMeanHpa = w.pressureMeanHpa,
            windMaxKmh = w.windMaxKmh
        )
    }

    private fun mergeAir(base: EnvironmentSnapshot, a: EnvironmentApis.AirResult): EnvironmentSnapshot =
        base.copy(
            aqiEuropeanMax = a.aqiEuropeanMax ?: base.aqiEuropeanMax,
            pm25Mean = a.pm25Mean ?: base.pm25Mean,
            pm10Mean = a.pm10Mean ?: base.pm10Mean,
            ozoneMean = a.ozoneMean ?: base.ozoneMean,
            no2Mean = a.no2Mean ?: base.no2Mean,
            pollenGrassMax = a.pollenGrassMax ?: base.pollenGrassMax,
            pollenBirchMax = a.pollenBirchMax ?: base.pollenBirchMax,
            pollenAlderMax = a.pollenAlderMax ?: base.pollenAlderMax,
            pollenMugwortMax = a.pollenMugwortMax ?: base.pollenMugwortMax,
            pollenOliveMax = a.pollenOliveMax ?: base.pollenOliveMax,
            pollenRagweedMax = a.pollenRagweedMax ?: base.pollenRagweedMax
        )

    // ── Persistence ─────────────────────────────────────────────────────────

    private fun save(snapshot: EnvironmentSnapshot) {
        val map = loadMap().toMutableMap()
        map[snapshot.date] = snapshot.toJson()
        prefs.edit().putString(KEY_SNAPSHOTS, JSONObject(map).toString()).apply()
        cachedSnapshotMap = map
    }

    /** Parses the stored JSON map once and caches it for the process lifetime. */
    private fun loadMap(): Map<String, String> {
        cachedSnapshotMap?.let { return it }
        val raw = prefs.getString(KEY_SNAPSHOTS, null) ?: return emptyMap()
        val parsed = runCatching {
            val o = JSONObject(raw)
            val out = mutableMapOf<String, String>()
            for (key in o.keys()) out[key] = o.getString(key)
            out.toMap()
        }.getOrDefault(emptyMap())
        cachedSnapshotMap = parsed
        return parsed
    }
}
