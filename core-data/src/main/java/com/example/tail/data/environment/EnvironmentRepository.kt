package com.example.tail.data.environment

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

private const val TAG = "EnvironmentRepo"
private const val PREFS_NAME = "tail_environment_prefs"
/** Legacy storage: ONE giant date→JSON map. Migrated to per-day keys on first read. */
private const val KEY_SNAPSHOTS_LEGACY = "environment_snapshots"
/** Per-day snapshot storage: "snap:<YYYY-MM-DD>" → snapshot JSON (one small key per day). */
private const val KEY_PREFIX_SNAP = "snap:"
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

    init {
        migrateLegacyBlobOnce()
    }

    // ── Reads ───────────────────────────────────────────────────────────────

    /** Returns the snapshot for [date], or null if none stored. */
    fun getSnapshot(date: LocalDate): EnvironmentSnapshot? {
        return prefs.getString(KEY_PREFIX_SNAP + date, null)?.let { EnvironmentSnapshot.fromJson(it) }
    }

    /** Returns all stored snapshots keyed by date string. */
    fun getAllSnapshots(): Map<String, EnvironmentSnapshot> {
        val out = mutableMapOf<String, EnvironmentSnapshot>()
        for ((key, value) in prefs.all) {
            if (key.startsWith(KEY_PREFIX_SNAP) && value is String) {
                EnvironmentSnapshot.fromJson(value)?.let { out[it.date] = it }
            }
        }
        return out
    }

    /**
     * THE resume rule, shared by the in-app backlog and the background
     * worker: a day is done once a network fetch succeeded for it
     * ([EnvironmentSnapshot.fetchedAt] is set) AND the day actually carries
     * weather. Weather is globally available (Open-Meteo archive reaches back
     * to 1940), so a "fetched" day with no temperatures at all is a failed or
     * zero-padded capture that must be retried — otherwise phantom 0 °C days
     * would be skipped forever. Other metrics that are legitimately
     * unavailable (Kp older than ~30 days, pollen outside Europe, unknown
     * water hardness…) still do NOT make the day incomplete.
     */
    fun needsFetch(date: LocalDate): Boolean {
        val snap = getSnapshot(date) ?: return true
        if (snap.fetchedAt.isBlank()) return true
        return snap.tempMinC == null && snap.tempMaxC == null && snap.tempMeanC == null
    }

    /**
     * Deletes every stored snapshot (clean wipe / full-redo mode).
     * Water-hardness location memory is intentionally KEPT — it's
     * user-curated data.
     */
    fun clearAllSnapshots() {
        val e = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith(KEY_PREFIX_SNAP)) e.remove(key)
        }
        e.apply()
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
            // null = the request THREW (network/rate limit) → days stay pending
            // for a later retry. A successful response — even one with no data
            // for a given day — is authoritative: the API has nothing for that
            // day, so it is marked fetched and never re-requested.
            val weather = runCatching {
                EnvironmentApis.fetchWeatherRange(coords.first, coords.second, wStart, wEnd)
            }.onFailure { Log.w(TAG, "range $wStart..$wEnd failed: ${it.message}") }
                .getOrNull()
            val air = runCatching {
                EnvironmentApis.fetchAirQualityRange(coords.first, coords.second, wStart, wEnd)
            }.onFailure { Log.w(TAG, "air range $wStart..$wEnd failed: ${it.message}") }
                .getOrNull()
            val authoritative = weather != null || air != null
            val now = java.time.Instant.now().toString()

            val batch = mutableMapOf<String, EnvironmentSnapshot>()
            var d = wStart
            while (!d.isAfter(wEnd)) {
                if (d in wanted) {
                    val existing = getSnapshot(d)
                    val daySnap = snapFromWeather(d, weather?.get(d))?.let { wSnap ->
                        air?.get(d)?.let { mergeAir(wSnap, it) } ?: wSnap
                    }
                    if (daySnap != null || existing != null || authoritative) {
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
                        if (merged.hasAnyData || authoritative) {
                            // Only stamp a day as fetched when it actually
                            // carries weather — weatherless days stay pending
                            // so a later Resume re-fetches real values.
                            val hasWeather = merged.tempMinC != null ||
                                merged.tempMaxC != null || merged.tempMeanC != null
                            if (merged.fetchedAt.isBlank() && hasWeather) {
                                merged = merged.copy(fetchedAt = now)
                            }
                            batch[merged.date] = merged
                        }
                    }
                }
                d = d.plusDays(1)
            }
            if (batch.isNotEmpty()) {
                saveAll(batch.values)
                stored += batch.size
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

    /**
     * Fills the Kp value for already-captured snapshots of the last
     * [windowDays] days that are still missing it (the RANGE capture path
     * skips Kp by design; the NOAA 3-hourly feed only serves ~7 days back).
     * ONE feed fetch, then per-day max merge. Returns the updated snapshots
     * (keyed by date) so callers can re-sync the linked habit squares;
     * empty when there was nothing to fill or the feed failed.
     */
    suspend fun backfillRecentKp(windowDays: Long = 30): Map<String, EnvironmentSnapshot> {
        val cutoff = LocalDate.now().minusDays(windowDays)
        val todo = getAllSnapshots().values.filter {
            runCatching { LocalDate.parse(it.date) }.getOrNull()?.isAfter(cutoff) == true &&
                it.kpIndexMax == null
        }
        if (todo.isEmpty()) return emptyMap()
        val dates = todo.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        val kp = runCatching { EnvironmentApis.fetchKpMaxRange(dates) }.getOrDefault(emptyMap())
        if (kp.isEmpty()) return emptyMap()
        val updated = todo.mapNotNull { snap ->
            val d = runCatching { LocalDate.parse(snap.date) }.getOrNull() ?: return@mapNotNull null
            val v = kp[d] ?: return@mapNotNull null
            val merged = snap.copy(kpIndexMax = v)
            save(merged)
            merged
        }
        Log.i(TAG, "kp backfill: ${updated.size} day(s) filled")
        return updated.associateBy { it.date }
    }

    /**
     * Applies a learned water-hardness value to EVERY stored snapshot with
     * the same location label that has no water value yet. Called when the
     * LLM resolver (or the user) learns a location's ppm, so the value
     * back-fills across the whole stay at that place, not just the viewed
     * day. Returns the updated snapshots (keyed by date) for habit re-sync.
     */
    fun applyWaterHardnessToLabel(label: String, ppm: Double): Map<String, EnvironmentSnapshot> {
        if (label.isBlank()) return emptyMap()
        val updated = mutableListOf<EnvironmentSnapshot>()
        for (snap in getAllSnapshots().values) {
            if (snap.locationLabel != label || snap.waterHardnessPpm != null) continue
            val merged = snap.copy(
                waterHardnessPpm = ppm,
                waterHardnessSource = "location-memory"
            )
            updated += merged
        }
        if (updated.isEmpty()) return emptyMap()
        saveAll(updated)
        Log.i(TAG, "water backfill: ${updated.size} day(s) at '$label' set to $ppm ppm")
        return updated.associateBy { it.date }
    }

    // ── Persistence ─────────────────────────────────────────────────────────

    /** Saves one snapshot as its own tiny prefs key (no blob rewrite, no cross-instance cache). */
    private fun save(snapshot: EnvironmentSnapshot) {
        prefs.edit().putString(KEY_PREFIX_SNAP + snapshot.date, snapshot.toJson()).apply()
    }

    /** Saves a batch of snapshots in one atomic prefs commit (used per range window). */
    private fun saveAll(snapshots: Collection<EnvironmentSnapshot>) {
        val e = prefs.edit()
        for (s in snapshots) e.putString(KEY_PREFIX_SNAP + s.date, s.toJson())
        e.apply()
    }

    /**
     * One-time migration from the legacy single-JSON-blob storage to per-day
     * keys. Existing history is preserved verbatim; the legacy key is removed
     * afterwards so this never runs twice.
     */
    private fun migrateLegacyBlobOnce() {
        if (!prefs.contains(KEY_SNAPSHOTS_LEGACY)) return
        val raw = prefs.getString(KEY_SNAPSHOTS_LEGACY, null)
        val e = prefs.edit()
        if (raw != null) {
            runCatching {
                val o = JSONObject(raw)
                val keys = o.keys()
                while (keys.hasNext()) {
                    val date = keys.next()
                    e.putString(KEY_PREFIX_SNAP + date, o.getString(date))
                }
            }.onFailure { Log.w(TAG, "legacy snapshot migration failed: ${it.message}") }
        }
        e.remove(KEY_SNAPSHOTS_LEGACY).apply()
        Log.i(TAG, "migrated legacy snapshot blob to per-day keys")
    }
}
