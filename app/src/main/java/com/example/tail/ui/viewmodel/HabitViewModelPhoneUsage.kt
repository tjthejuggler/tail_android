package com.example.tail.ui.viewmodel

import android.util.Log
import com.example.tail.data.HabitScreen
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val TAG = "PhoneUsageVM"

/**
 * Phone-usage habit-family extension on [HabitViewModel].
 *
 * ## Model (mirrors the environment/Garmin integrations)
 * A habit can be linked to a [com.example.tail.data.phoneusage.PhoneUsageMetric]
 * via [com.example.tail.data.AppSettings.phoneUsageHabitMetrics]. Each day,
 * after the UsageStats snapshot is aggregated, the linked metric's value is
 * written into the habit's daily DB entry — the stored value IS the metric.
 * Graphs therefore work natively next to any other habit or metric.
 *
 * ## Capture triggers
 *  1. ViewModel init (today + the ~7-day retro backfill),
 *  2. selected-date change to a day without a snapshot,
 *  3. the periodic PhoneUsageWorker (~4 h; UsageStats keeps limited event
 *     history, so aggregation must run frequently).
 *
 * ## Privacy
 * All raw usage events stay on-device inside UsageStats; only the aggregated
 * per-day snapshot (see PhoneUsageSnapshot) is persisted, and only the linked
 * metric values flow into the habits DB.
 */

/**
 * Registers the phone-usage capture hooks. Called once from
 * [HabitViewModel.init] after the selected-date collectors exist.
 */
fun HabitViewModel.initPhoneUsageCapture() {
    viewModelScope.launch {
        launch {
            _selectedDate.collect { date -> refreshPhoneUsage(date, autoOnly = true) }
        }
        // Self-heal: once the DB finished loading, re-derive every linked
        // square from the stored snapshots (deterministic, no UsageStats query).
        launch {
            if (!awaitDbLoaded()) return@launch
            awaitSettingsLoaded()
            resyncPhoneUsageHabits()
        }
        // Backfill: one habit square per phone-usage metric option on the
        // "phone usage" screen (only for metrics the user hasn't linked).
        launch { ensureAllPhoneUsageMetricHabits() }
    }
}

/**
 * Loads (and if needed captures) the snapshot for [date], then syncs any
 * phone-usage-linked habits from it. Today is refreshed on every visit; past
 * days are captured once (their UsageStats history evaporates after ~7 days,
 * so uncaptured past days are attempted once and never re-hit when data exists).
 */
fun HabitViewModel.refreshPhoneUsage(date: LocalDate = _selectedDate.value, autoOnly: Boolean = false) {
    if (!_settings.value.phoneUsageEnabled) return

    viewModelScope.launch {
        val existing = phoneUsageRepo.getSnapshot(date)
        _puSnapshot.value = existing
        val isToday = date == LocalDate.now()
        if (autoOnly && existing != null && existing.hasAnyData && !isToday) return@launch
        if (!phoneUsageRepo.hasUsageAccess()) {
            Log.d(TAG, "refresh $date: usage access not granted — skipping capture")
            return@launch
        }

        val retro = date.isBefore(LocalDate.now())
        val snapshot = withContext(Dispatchers.IO) {
            phoneUsageRepo.captureForDate(date, retro = retro)
        }
        if (_selectedDate.value == date) _puSnapshot.value = snapshot
        _puVersion.value++
        snapshot?.let { syncPhoneUsageHabits(mapOf(it.date to it)) }
    }
}

/** Re-derives ALL phone-usage-linked squares from the stored snapshots (no queries). */
fun HabitViewModel.resyncPhoneUsageHabits() {
    viewModelScope.launch {
        val snaps = phoneUsageRepo.getAllSnapshots()
        if (snaps.isEmpty()) return@launch
        syncPhoneUsageHabits(snaps)
        _puVersion.value++
        Log.d(TAG, "resync: ${snaps.size} day(s) re-derived into linked squares")
    }
}

/**
 * Writes the linked metrics from [snapshotsByDate] into the habits DB.
 * Deterministic — safe to re-run; only differing days are rewritten.
 * Mirrors [syncEnvironmentHabits]: linked habits are force-excluded from
 * points, and links pointing at deleted habits are pruned (never resurrected).
 */
internal suspend fun HabitViewModel.syncPhoneUsageHabits(
    snapshotsByDate: Map<String, com.example.tail.data.phoneusage.PhoneUsageSnapshot>
) {
    val s = _settings.value
    val links = s.phoneUsageHabitMetrics
    if (links.isEmpty() || s.fileUri.isEmpty()) return

    val unexcluded = links.keys.filter { it !in _settings.value.noPointsHabits }
    if (unexcluded.isNotEmpty()) {
        val noPoints = _settings.value.noPointsHabits + unexcluded
        _settings.value = _settings.value.copy(noPointsHabits = noPoints)
        settingsRepo.saveNoPointsHabits(noPoints)
        Log.w(TAG, "PhoneUsage sync: re-excluded ${unexcluded.size} linked habit(s) from points")
    }
    if (!dbLoaded) {
        Log.w(TAG, "syncPhoneUsageHabits: DB not loaded yet, skipping (anti-wipe gate)")
        return
    }

    refreshCachedDbFromDisk(s.fileUri)

    var mutableDb = cachedPhoneDb.toMutableMap()
    var dbChanged = false
    val staleLinks = mutableSetOf<String>()

    for ((habitName, metricKey) in links) {
        val metric = com.example.tail.data.phoneusage.PhoneUsageMetric.fromKey(metricKey) ?: continue
        // Resolve the actual DB key: exact match first, then case-insensitive
        // (habit names are user-typed and may differ in case/whitespace from
        // the link key). Without this, a valid link gets pruned as "stale"
        // and the edit-bar toggle flips right back off.
        val dbKey = mutableDb.keys.firstOrNull { it == habitName }
            ?: mutableDb.keys.firstOrNull {
                it.trim().equals(habitName.trim(), ignoreCase = true)
            }
        var habitData: MutableMap<String, Int>
        // A linked habit whose row is missing from the DB is self-healed here:
        // the link is authoritative (it exists in settings), so create the row
        // and fill it. Without this, a habit whose addHabitToFiles write was
        // lost/raced silently shows "no data" forever. Truly deleted habits
        // have their links removed by the habit-deletion flow, so they never
        // reach this branch.
        if (dbKey == null) {
            if (com.example.tail.data.phoneusage.PhoneUsageMetric.entries.none { it.label == habitName } &&
                habitName !in _habitScreens.value.flatMap { it.habitNames }
            ) {
                staleLinks.add(habitName)
                continue
            }
            Log.i(TAG, "PhoneUsage sync: creating missing DB row for '$habitName'")
            mutableDb[habitName] = sortedMapOf()
            habitData = mutableDb[habitName]!!.toMutableMap()
        } else {
            habitData = mutableDb[dbKey]!!.toMutableMap()
        }

        for ((dateStr, snap) in snapshotsByDate) {
            val newValue = metric.scaledValue(snap) ?: continue
            if (habitData[dateStr] != newValue) {
                habitData[dateStr] = newValue
                dbChanged = true
            }
        }
        mutableDb[dbKey ?: habitName] = habitData
    }

    if (dbChanged) {
        cachedPhoneDb = mutableDb
        rebuildHabitList()
        withContext(Dispatchers.IO) {
            habitsRepo.persistDatabase(android.net.Uri.parse(s.fileUri), context, mutableDb)
        }
        Log.d(TAG, "PhoneUsage sync: wrote ${snapshotsByDate.size} day(s) × ${links.size} habit(s)")
    }

    if (staleLinks.isNotEmpty()) {
        val pruned = links.filterKeys { it !in staleLinks }
        settingsRepo.savePhoneUsageHabitMetrics(pruned)
        _settings.value = _settings.value.copy(phoneUsageHabitMetrics = pruned)
        Log.i(TAG, "PhoneUsage sync: pruned ${staleLinks.size} stale link(s): $staleLinks")
    }
}

// ── Habit-link management ───────────────────────────────────────────────────

/** The dedicated screen phone-usage-linked habit squares are placed on. */
const val PHONE_USAGE_SCREEN_NAME = "phone usage"

/**
 * Ensures a square for [habitName] exists on the "phone usage" screen,
 * creating that screen first when it doesn't exist yet (same rules as the
 * environment screen square).
 */
fun HabitViewModel.ensurePhoneUsageScreenSquare(habitName: String): List<HabitScreen>? {
    val screens = _habitScreens.value.toMutableList()
    if (screens.isEmpty()) return null // flat (no-screens) mode: nothing to do
    if (screens.any { habitName in it.habitNames }) return null

    val idx = screens.indexOfFirst {
        it.name.contains("phone", ignoreCase = true) && it.name.contains("usage", ignoreCase = true)
    }
    if (idx >= 0) {
        screens[idx] = screens[idx].copy(habitNames = screens[idx].habitNames + habitName)
    } else {
        screens.add(
            HabitScreen(
                id = java.util.UUID.randomUUID().toString(),
                name = PHONE_USAGE_SCREEN_NAME,
                habitNames = listOf(habitName)
            )
        )
    }
    _habitScreens.value = screens
    screenHabitCache.clear()
    viewModelScope.launch { rebuildHabitList() }
    persistScreens(screens)
    return screens
}

/**
 * Backfills the phone-usage feature: EVERY [com.example.tail.data.phoneusage.PhoneUsageMetric]
 * option gets a habit (named after the metric's label) linked to it, flagged
 * "don't affect points", and placed as a square on the "phone usage" screen.
 * Idempotent; runs at every init once the feature is enabled.
 */
fun HabitViewModel.ensureAllPhoneUsageMetricHabits() {
    viewModelScope.launch {
        if (!awaitDbLoaded()) return@launch
        awaitSettingsLoaded()
        val s = _settings.value
        if (s.fileUri.isEmpty() || !s.phoneUsageEnabled) return@launch

        val linkedMetricKeys = s.phoneUsageHabitMetrics.values.toSet()
        val missing = com.example.tail.data.phoneusage.PhoneUsageMetric.entries
            .filter { it.key !in linkedMetricKeys }
        if (missing.isEmpty()) return@launch

        val uri = android.net.Uri.parse(s.fileUri)
        val links = s.phoneUsageHabitMetrics.toMutableMap()
        val noPoints = s.noPointsHabits.toMutableSet()
        try {
            for (metric in missing) {
                val name = metric.label
                if (name !in cachedPhoneDb) {
                    habitsRepo.addHabitToFiles(listOf(uri), context, name)
                }
                links[name] = metric.key
                noPoints.add(name)
            }
            cachedPhoneDb = habitsRepo.ensureDaysExist(uri, context)
        } catch (e: Exception) {
            Log.w(TAG, "PhoneUsage metric backfill failed: ${e.message}")
            return@launch
        }

        settingsRepo.savePhoneUsageHabitMetrics(links)
        settingsRepo.saveNoPointsHabits(noPoints)
        _settings.value = _settings.value.copy(
            phoneUsageHabitMetrics = links,
            noPointsHabits = noPoints
        )

        // One batched screen update: every metric option without a square
        // gets one on the "phone usage" screen (created when absent).
        val screens = _habitScreens.value.toMutableList()
        if (screens.isNotEmpty()) {
            var idx = screens.indexOfFirst {
                it.name.contains("phone", ignoreCase = true) && it.name.contains("usage", ignoreCase = true)
            }
            if (idx < 0) {
                screens.add(
                    HabitScreen(
                        id = java.util.UUID.randomUUID().toString(),
                        name = PHONE_USAGE_SCREEN_NAME,
                        habitNames = emptyList()
                    )
                )
                idx = screens.size - 1
            }
            val placed = screens.flatMap { it.habitNames }.toSet()
            val toPlace = missing.map { it.label }.filter { it !in placed }
            if (toPlace.isNotEmpty()) {
                screens[idx] = screens[idx].copy(habitNames = screens[idx].habitNames + toPlace)
                _habitScreens.value = screens
                screenHabitCache.clear()
                persistScreens(screens)
            }
        }

        // Fill the new habits' history from stored snapshots, then refresh.
        syncPhoneUsageHabits(phoneUsageRepo.getAllSnapshots())
        rebuildHabitList()
        Log.i(TAG, "PhoneUsage backfill: added ${missing.size} metric habit(s)")
    }
}

/**
 * Links/unlinks a habit to a phone-usage metric. On link the habit is
 * auto-excluded from points, a square is placed on the "phone usage" screen,
 * and all stored snapshots are synced immediately.
 */
fun HabitViewModel.setPhoneUsageHabitMetric(habitName: String, metricKey: String?) {
    viewModelScope.launch {
        val links = _settings.value.phoneUsageHabitMetrics.toMutableMap()
        if (metricKey == null) links.remove(habitName) else links[habitName] = metricKey
        settingsRepo.savePhoneUsageHabitMetrics(links)
        _settings.value = _settings.value.copy(phoneUsageHabitMetrics = links)

        if (metricKey != null) {
            if (habitName !in _settings.value.noPointsHabits) {
                val noPoints = _settings.value.noPointsHabits + habitName
                _settings.value = _settings.value.copy(noPointsHabits = noPoints)
                settingsRepo.saveNoPointsHabits(noPoints)
            }
            ensurePhoneUsageScreenSquare(habitName)
            syncPhoneUsageHabits(phoneUsageRepo.getAllSnapshots())
        }
    }
}

/**
 * Manual backfill button: re-aggregates every day of the last 7 the device
 * still has UsageStats history for (skipping nothing — doubles as a "redo
 * recent days" repair), then syncs linked squares.
 */
fun HabitViewModel.fetchPhoneUsageBackfill(days: Int = 7) {
    if (_puBackfilling.value) return
    viewModelScope.launch {
        _puBackfilling.value = true
        _puStatus.value = "Backfilling last $days day(s)…"
        try {
            awaitSettingsLoaded()
            if (!phoneUsageRepo.hasUsageAccess()) {
                _puStatus.value = "Usage access missing — grant it first"
                return@launch
            }
            val today = LocalDate.now()
            var stored = 0
            for (i in 1..days) {
                val d = today.minusDays(i.toLong())
                if (withContext(Dispatchers.IO) { phoneUsageRepo.captureForDate(d, retro = true) } != null) {
                    stored++
                }
            }
            syncPhoneUsageHabits(phoneUsageRepo.getAllSnapshots())
            refreshPhoneUsage(_selectedDate.value, autoOnly = true)
            _puStatus.value = "Backfill done — $stored of $days day(s) captured (device history permitting)"
            _puVersion.value++
        } finally {
            _puBackfilling.value = false
        }
    }
}

/** Settings pass-through: enables the family and captures today immediately. */
fun HabitViewModel.savePhoneUsageEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.savePhoneUsageEnabled(enabled)
        _settings.value = _settings.value.copy(phoneUsageEnabled = enabled)
        if (enabled) {
            // Retro backfill first (limited UsageStats history), then today.
            val retro = withContext(Dispatchers.IO) { phoneUsageRepo.backfillRecent() }
            if (retro.isNotEmpty()) syncPhoneUsageHabits(retro)
            refreshPhoneUsage(LocalDate.now(), autoOnly = true)
        }
    }
}
