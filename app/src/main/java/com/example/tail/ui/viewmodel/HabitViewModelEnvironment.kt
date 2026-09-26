package com.example.tail.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.tail.data.AppSettings
import com.example.tail.data.environment.EnvironmentMetric
import com.example.tail.data.environment.EnvironmentSnapshot
import com.example.tail.data.environment.WaterHardnessLlm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val TAG = "EnvVM"
/** How many days the backlog captures per batch before pausing. */
private const val BACKLOG_BATCH_DAYS = 7
/** Pause between batches so free APIs are hit politely (rate-limit friendly). */
private const val BACKLOG_BATCH_PAUSE_MS = 2_500L

/**
 * Environment-habit extension on [HabitViewModel].
 *
 * ## Model (mirrors the Garmin integration)
 * A habit can be linked to an [EnvironmentMetric] via
 * [AppSettings.environmentHabitMetrics]. Each day, after the environment
 * snapshot is captured, the linked metric's value is written into the
 * habit's daily DB entry — the stored value IS the metric (scaled to Int,
 * e.g. 21.4 °C → 214, Kp 3.33 → 333). Graphs therefore work natively and
 * can be compared against any other habit or metric.
 *
 * ## Capture triggers
 *  1. ViewModel init (today, after its location resolves),
 *  2. selected-date change to a day with coords but no snapshot,
 *  3. explicit [refreshEnvironment] re-fetch,
 *  4. the rate-limited full-history backlog ([fetchEnvironmentFullBacklog]).
 */

/**
 * Registers the environment capture hooks. Called once from
 * [HabitViewModel.init] after the selected-date/location collectors exist.
 */
fun HabitViewModel.initEnvironmentCapture() {
    viewModelScope.launch {
        // Refresh (and if needed capture) whenever the visible date changes…
        launch {
            _selectedDate.collect { date -> refreshEnvironment(date, autoOnly = true) }
        }
        // …or the day's location resolves late (GPS fix after screen open)…
        launch {
            _selectedDateLocation.collect { refreshEnvironment(_selectedDate.value, autoOnly = true) }
        }
        // Self-heal: once the DB finishes loading, re-derive every linked
        // square from the stored snapshots. Deterministic — only days whose
        // stored value differs are rewritten. This repairs squares written
        // by older logic (e.g. the ×10 temperature scaling) and covers the
        // case where an init-time capture was skipped by the anti-wipe gate
        // because the DB was still loading.
        launch { resyncEnvironmentHabits() }
    }
}

/** Waits (up to [timeoutMs]) for the habits DB to finish loading. */
internal suspend fun HabitViewModel.awaitDbLoaded(timeoutMs: Long = 60_000): Boolean {
    val start = System.currentTimeMillis()
    while (!dbLoaded && System.currentTimeMillis() - start < timeoutMs) {
        withContext(Dispatchers.IO) { Thread.sleep(250) }
    }
    return dbLoaded
}

/**
 * Waits until the settings DataStore has emitted at least once. The init
 * resync used to race the settings load: `syncEnvironmentHabits` reads
 * `_settings.value`, which is still the DEFAULT AppSettings (empty links)
 * until the main collect fires — so the resync silently did nothing.
 */
internal suspend fun HabitViewModel.awaitSettingsLoaded() {
    if (_settings.value.fileUri.isNotEmpty() ||
        _settings.value.environmentHabitMetrics.isNotEmpty()
    ) return
    val s = settingsRepo.settingsFlow.first()
    // Publish if the main collect hasn't beaten us to it.
    if (_settings.value == AppSettings()) _settings.value = s
}

/**
 * Re-derives ALL environment-linked squares from the stored snapshots
 * (no network). Called once after DB + settings load at init and from the
 * Settings "Repair squares" button.
 */
fun HabitViewModel.resyncEnvironmentHabits() {
    viewModelScope.launch {
        if (!awaitDbLoaded()) {
            Log.w(TAG, "resync: DB still not loaded after timeout, aborting")
            return@launch
        }
        awaitSettingsLoaded()
        val snaps = environmentRepo.getAllSnapshots()
        if (snaps.isEmpty()) return@launch
        syncEnvironmentHabits(snaps)
        _envSnapshot.value = environmentRepo.getSnapshot(_selectedDate.value)
        _envVersion.value++
        Log.d(TAG, "resync: ${snaps.size} day(s) re-derived into linked squares")
    }
}

/**
 * Loads (and if needed captures) the snapshot for [date], then syncs any
 * environment-linked habits from it.
 *
 * @param autoOnly when true, the network capture only runs when no snapshot
 *        exists yet (silent backfill) or the date is today (daily refresh).
 */
fun HabitViewModel.refreshEnvironment(date: LocalDate = _selectedDate.value, autoOnly: Boolean = false) {
    if (!_settings.value.environmentEnabled) return

    viewModelScope.launch {
        val existing = environmentRepo.getSnapshot(date)
        _envSnapshot.value = existing

        val coords = locationRepo.getCoordsForDate(date) ?: return@launch
        val isToday = date == LocalDate.now()
        if (autoOnly && existing != null && existing.fetchedAt.isNotBlank() && !isToday) {
            return@launch // captured once; don't re-hit the network on date navigation
        }
        if (_envLoading.value) return@launch

        _envLoading.value = true
        try {
            val label = locationRepo.getLocationForDate(date) ?: ""
            var snapshot = environmentRepo.captureForDate(date, coords.first, coords.second, label)
            // Programmatic water hardness: when the day has no water value and
            // location memory has none either, resolve it once via the LLM
            // (AI Assistant endpoint), cache it in the location memory, and
            // write it onto the day.
            val s = _settings.value
            if (snapshot?.waterHardnessPpm == null &&
                s.environmentWaterMemoryEnabled && label.isNotBlank() &&
                environmentRepo.getWaterHardnessForLocation(label) == null
            ) {
                resolveWaterHardnessViaLlm(label)?.let { ppm ->
                    environmentRepo.setWaterHardnessForLocation(label, ppm)
                    environmentRepo.setWaterHardnessForDate(date, ppm)
                    snapshot = environmentRepo.getSnapshot(date)
                }
            }
            if (_selectedDate.value == date) _envSnapshot.value = snapshot
            _envVersion.value++
            snapshot?.let { syncEnvironmentHabits(mapOf(it.date to it)) }
        } catch (e: Exception) {
            Log.w(TAG, "capture $date failed: ${e.message}")
        } finally {
            _envLoading.value = false
        }
    }
}

/**
 * LLM-backed water hardness for [label], using the AI Assistant endpoint
 * settings. Returns null when not configured or the lookup failed.
 * The caller is responsible for caching the result in the location memory.
 */
internal suspend fun HabitViewModel.resolveWaterHardnessViaLlm(label: String): Double? {
    val s = _settings.value
    val config = WaterHardnessLlm.Config(
        baseUrl = s.aiAssistantBaseUrl,
        apiKey = s.aiAssistantApiKey,
        model = s.aiAssistantModel
    )
    if (!config.isConfigured) return null
    return WaterHardnessLlm.lookup(config, label)
}

/**
 * Writes the linked metrics from [snapshotsByDate] into the habits DB and
 * persists (Garmin-style: the stored value IS the metric × metric.scale).
 * Deterministic — safe to re-run; only differing days are rewritten.
 */
internal suspend fun HabitViewModel.syncEnvironmentHabits(
    snapshotsByDate: Map<String, EnvironmentSnapshot>
) {
    val s = _settings.value
    val links = s.environmentHabitMetrics
    if (links.isEmpty() || s.fileUri.isEmpty()) return
    if (!dbLoaded) {
        Log.w(TAG, "syncEnvironmentHabits: DB not loaded yet, skipping (anti-wipe gate)")
        return
    }

    refreshCachedDbFromDisk(s.fileUri)
    val useFahrenheit = s.environmentTemperatureUnit == "F"

    var mutableDb = cachedPhoneDb.toMutableMap()
    var dbChanged = false
    val staleLinks = mutableSetOf<String>()

    for ((habitName, metricKey) in links) {
        val metric = EnvironmentMetric.fromKey(metricKey) ?: continue
        // NEVER (re)create habits here. A link whose habit is absent from the
        // DB is a leftover from a deleted habit (or a rename that pre-dates
        // link re-keying) — writing it would resurrect a phantom habit that
        // keeps refilling itself. Prune the stale link instead.
        if (habitName !in mutableDb) {
            staleLinks.add(habitName)
            continue
        }
        val habitData = mutableDb[habitName]!!.toMutableMap()

        for ((dateStr, snap) in snapshotsByDate) {
            // Stored value = metric ×10 in the configured unit (215 = 21.5 °C);
            // decimals preserved for graphs, rounded only at display sites.
            val newValue = metric.scaledValue(snap, useFahrenheit)
                ?: continue // no data → leave the day untouched
            if (habitData[dateStr] != newValue) {
                habitData[dateStr] = newValue
                dbChanged = true
            }
        }
        mutableDb[habitName] = habitData
    }

    if (dbChanged) {
        cachedPhoneDb = mutableDb
        rebuildHabitList()
        withContext(Dispatchers.IO) {
            habitsRepo.persistDatabase(Uri.parse(s.fileUri), context, mutableDb)
        }
        Log.d(TAG, "Environment sync: wrote ${snapshotsByDate.size} day(s) × ${links.size} habit(s)")
    }

    // Drop links pointing at habits that no longer exist so they can never
    // resurrect a deleted habit on a future sync.
    if (staleLinks.isNotEmpty()) {
        val pruned = links.filterKeys { it !in staleLinks }
        settingsRepo.saveEnvironmentHabitMetrics(pruned)
        _settings.value = _settings.value.copy(environmentHabitMetrics = pruned)
        Log.i(TAG, "Environment sync: pruned ${staleLinks.size} stale link(s): $staleLinks")
    }
}

// ── Habit-link management ───────────────────────────────────────────────────

/**
 * Links/unlinks a habit to an environment metric. On link, all stored
 * snapshots are synced into the habit immediately so its graph is complete
 * without waiting for new days.
 */
fun HabitViewModel.setEnvironmentHabitMetric(habitName: String, metricKey: String?) {
    viewModelScope.launch {
        val links = _settings.value.environmentHabitMetrics.toMutableMap()
        if (metricKey == null) links.remove(habitName) else links[habitName] = metricKey
        settingsRepo.saveEnvironmentHabitMetrics(links)
        _settings.value = _settings.value.copy(environmentHabitMetrics = links)

        // Sync the full stored history into the (un)linked habit's squares.
        if (metricKey != null) {
            syncEnvironmentHabits(
                environmentRepo.getAllSnapshots().mapValues { it.value }
            )
        } else {
            // Unlinking keeps history (like Garmin); users can clear manually.
        }
    }
}

// ── Rate-limited full-history backlog ───────────────────────────────────────

/** True while the full-history backlog worker is running. */
internal val HabitViewModel.envBacklogRunning: Boolean get() = _envBacklogRunning.value

/**
 * Captures the ENTIRE available history, politely:
 *
 *  · walks day-by-day from the earliest day that has recorded coords,
 *  · processes [BACKLOG_BATCH_DAYS] days per batch, pausing
 *    [BACKLOG_BATCH_PAUSE_MS] between batches so free APIs are never
 *    hammered (Open-Meteo is generous but rate-limits bursts; NOAA daily),
 *  · skips days already carrying a completed capture (resumable — if the
 *    app is killed or the network drops, run it again and it continues),
 *  · survives individual day failures (logged, skipped) and already-seen
 *    API gaps (days without coords are skipped forever).
 *
 * Progress is streamed into [_envStatus] for the settings UI.
 */
/**
 * Runs the full-history capture.
 *
 * @param repair false = RESUME: skip days already carrying a fresh capture
 *        (only missing/incomplete days are fetched) — use to continue after
 *        an interruption. true = FULL REDO: re-fetch every day from scratch,
 *        overwriting existing snapshots (clears them first so partial data
 *        can't linger) — use after logic/API changes or to force refresh.
 */
fun HabitViewModel.fetchEnvironmentFullBacklog(repair: Boolean = false) {
    if (_envBacklogRunning.value) return
    if (!_settings.value.environmentEnabled) return
    viewModelScope.launch {
        _envBacklogRunning.value = true
        _envStatus.value = if (repair) "Full redo: preparing…" else "Resume: preparing…"
        try {
            awaitSettingsLoaded()
            // Gap-filled coords: every day between the first and last recorded
            // location inherits the nearest recorded day's position, so the
            // backlog captures weather for coord-less days too instead of
            // leaving holes in the history.
            val allCoords = locationRepo.getAllStoredCoordsFilled()
            val labels = locationRepo.getAllStoredLabels()
            if (allCoords.isEmpty()) {
                _envStatus.value = "No recorded locations with coordinates yet"
                return@launch
            }

            val today = LocalDate.now()
            val fmt = DateTimeFormatter.ISO_LOCAL_DATE
            val recordedDays = allCoords.keys.mapNotNull { runCatching { LocalDate.parse(it, fmt) }.getOrNull() }
                .sorted()
            val spanEnd = if (today.isBefore(recordedDays.last())) today else recordedDays.last()
            val dates = generateSequence(recordedDays.first()) { it.plusDays(1) }
                .takeWhile { !it.isAfter(spanEnd) }
                .toList()

            if (repair) {
                // FULL REDO: drop every stored snapshot so the run starts
                // from a genuinely clean state.
                environmentRepo.clearAllSnapshots()
            }

            // Resume mode: only days whose fetch never succeeded (no snapshot
            // or fetchedAt blank — e.g. the run died mid-window). Days a
            // fetch already answered are never re-requested, even if some
            // metrics are legitimately unavailable for them (Kp >30 days old,
            // pollen outside Europe…). Repair mode: everything.
            val todo = if (repair) dates else dates.filter { environmentRepo.needsFetch(it) }
            if (todo.isEmpty()) {
                _envStatus.value = "History already complete (${dates.size} days)"
                return@launch
            }

            val alreadyComplete = dates.size - todo.size

            // Group remaining days by location (consecutive runs at one place
            // become one range fetch — ~35 days per network window).
            data class Loc(val lat: Double, val lon: Double, val label: String)
            val byLocation = linkedMapOf<Loc, MutableList<LocalDate>>()
            for (d in todo) {
                val c = allCoords[d.toString()] ?: continue
                val l = labels[d.toString()] ?: ""
                byLocation.getOrPut(Loc(c.first, c.second, l)) { mutableListOf() }.add(d)
            }

            var windowsDone = 0
            var stored = 0
            val approxWindows = byLocation.values.sumOf { days ->
                if (days.isEmpty()) 0 else (days.size + 34) / 35
            }
            for ((loc, days) in byLocation) {
                environmentRepo.captureRange(days, Pair(loc.lat, loc.lon), loc.label) { wDone, wTotal ->
                    windowsDone++
                    _envStatus.value =
                        "Capturing ${loc.label.ifEmpty { "unknown place" }}: window $wDone/$wTotal" +
                        " · +$stored stored · $alreadyComplete already complete" +
                        " · overall $windowsDone/~$approxWindows windows"
                    _envVersion.value++
                }.also { stored += it }
                // Sync linked squares after each location's range.
                val snaps = days.mapNotNull { environmentRepo.getSnapshot(it) }
                    .associateBy { it.date }
                if (snaps.isNotEmpty()) syncEnvironmentHabits(snaps)
                _envVersion.value++
            }
            _envStatus.value = "Done — +$stored stored of ${todo.size} remaining" +
                " ($alreadyComplete were already complete)"
            _envSnapshot.value = environmentRepo.getSnapshot(_selectedDate.value)
            _envVersion.value++
        } finally {
            _envBacklogRunning.value = false
        }
    }
}

// ── Legacy single-shot water & status helpers (kept from v1) ────────────────

/** Manual water-hardness entry for the selected date; optionally remembers the location. */
fun HabitViewModel.setEnvironmentWaterHardness(ppm: Double, rememberForLocation: Boolean) {
    val date = _selectedDate.value
    environmentRepo.setWaterHardnessForDate(date, ppm)
    if (rememberForLocation) {
        val label = _selectedDateLocation.value
        if (!label.isNullOrBlank()) {
            environmentRepo.setWaterHardnessForLocation(label, ppm)
        }
    }
    refreshEnvironment(date, autoOnly = true)
}

/** Clears the water value on the selected date's snapshot. */
fun HabitViewModel.clearEnvironmentWaterHardness() {
    environmentRepo.clearWaterHardnessForDate(_selectedDate.value)
    refreshEnvironment(_selectedDate.value, autoOnly = true)
}

/** Removes one remembered location → hardness entry. */
fun HabitViewModel.removeWaterMemory(label: String) {
    environmentRepo.removeWaterHardnessForLocation(label)
    _envVersion.value++
}

// ── Settings pass-throughs ──────────────────────────────────────────────────

fun HabitViewModel.saveEnvironmentEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveEnvironmentEnabled(enabled)
        // First enable: immediately capture today so squares start populating.
        if (enabled) refreshEnvironment(LocalDate.now(), autoOnly = true)
    }
}

fun HabitViewModel.saveEnvironmentWaterMemoryEnabled(enabled: Boolean) {
    viewModelScope.launch { settingsRepo.saveEnvironmentWaterMemoryEnabled(enabled) }
}

/**
 * Switches the temperature unit ("C"/"F"). Storage is in the chosen unit,
 * so the full history is re-synced (no network — snapshots hold °C).
 */
fun HabitViewModel.saveEnvironmentTemperatureUnit(unit: String) {
    viewModelScope.launch {
        settingsRepo.saveEnvironmentTemperatureUnit(unit)
        _settings.value = _settings.value.copy(environmentTemperatureUnit = unit)
        // Re-derive every linked square in the new unit.
        resyncEnvironmentHabits()
    }
}
