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
     * Backfills multiple dates sequentially (used by the settings-screen
     * backlog action). [coordsFor] resolves the day's position; dates without
     * coords are skipped. Calls [onProgress] after each date.
     * Returns the number of snapshots stored/updated.
     */
    suspend fun backfill(
        dates: List<LocalDate>,
        coordsFor: (LocalDate) -> Pair<Double, Double>?,
        labelFor: (LocalDate) -> String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): Int {
        var stored = 0
        for ((index, date) in dates.withIndex()) {
            val coords = coordsFor(date)
            if (coords != null) {
                try {
                    if (captureForDate(date, coords.first, coords.second, labelFor(date)) != null) {
                        stored++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "backfill $date failed: ${e.message}")
                }
            }
            onProgress(index + 1, dates.size)
        }
        return stored
    }

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
