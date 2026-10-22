package com.example.tail.ui.viewmodel

// Episode habit type ("Injury (episode)") — ViewModel logic.
// Split out of HabitViewModel.kt to keep individual Kotlin source files
// small enough for IR lowering on this machine.

import android.net.Uri
import com.example.tail.data.EpisodeDataRepository
import com.example.tail.data.InjuryEpisode
import com.example.tail.data.HabitIncrementBus
import com.example.tail.data.TextInputRepository
import com.example.tail.ui.common.HabitsDataChangedBus
import java.time.LocalDate
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Process-wide episode store (stateless — safe as a file-level singleton). */
private val episodeRepo = EpisodeDataRepository()

/**
 * The effective external episode-file URI for [habitName]: the configured
 * episode URI when present, else a sibling `<name>_episodes.json` document
 * created next to the habit's TEXT-INPUT file (same synced SAF folder, so
 * the PC research pipeline sees episode onsets). Null when neither works —
 * the internal store then remains the sole copy.
 */
private fun HabitViewModel.resolveEpisodeUri(habitName: String): Uri? {
    EpisodeDataRepository.episodeUriOrNull(_settings.value.episodeFileUris[habitName])
        ?.let { return it }
    // Own text-input tree first; the "Sick" illness habit (which has no
    // text-input file of its own) falls back to the Injured habit's synced
    // folder so Sick_episodes.json lands in the same noteVault directory.
    val textUri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
        ?: TextInputRepository.textUriOrNull(_settings.value.textInputFileUris["Injured"])
        ?: return null
    val sibling = episodeRepo.ensureSiblingEpisodeUri(context, textUri, habitName) ?: return null
    // Persist the discovered sibling so later writes skip the directory scan.
    if (_settings.value.episodeFileUris[habitName] != sibling.toString()) {
        val updated = _settings.value.episodeFileUris.toMutableMap()
        updated[habitName] = sibling.toString()
        viewModelScope.launch {
            settingsRepo.saveEpisodeFileUris(updated)
            _settings.value = _settings.value.copy(episodeFileUris = updated)
        }
    }
    return sibling
}

/**
 * True when [habitName] runs the ILLNESS variant of the episode dialog:
 * an explicit "Sick (illness episode)" type toggle, or the literal habit
 * name "Sick" (convention, dev#22).
 */
fun HabitViewModel.isIllnessEpisodeHabit(habitName: String): Boolean =
    habitName in _settings.value.sickEpisodeHabits || habitName.equals("Sick", ignoreCase = true)

/**
 * True when [habitName] has an episode type enabled (injury or illness
 * variant). Illness habits are episode habits by definition — onset /
 * silent continuation / explicit clear, exported to <name>_episodes.json.
 */
fun HabitViewModel.isEpisodeHabit(habitName: String): Boolean =
    habitName in _settings.value.episodeHabits || isIllnessEpisodeHabit(habitName)

/** Loads all episodes for [habitName], then calls [onLoaded] (oldest onset first). */
fun HabitViewModel.loadEpisodes(habitName: String, onLoaded: (List<InjuryEpisode>) -> Unit) {
    viewModelScope.launch {
        val episodes = episodeRepo.loadEpisodes(
            context, habitName, resolveEpisodeUri(habitName)
        )
        _episodesCache.value = _episodesCache.value.toMutableMap().apply {
            put(habitName, episodes)
        }
        onLoaded(episodes)
    }
}

/**
 * Loads episodes into the graph cache (no callback) — mirrors
 * loadTextEntriesForGraph for episode habits.
 */
fun HabitViewModel.loadEpisodesForGraph(habitName: String) {
    loadEpisodes(habitName) { }
}

/** The episode covering [date] (onset..resolved-or-today), or null. */
fun HabitViewModel.episodeForDate(
    habitName: String,
    date: LocalDate
): InjuryEpisode? {
    val episodes = _episodesCache.value[habitName] ?: return null
    return episodes.firstOrNull { ep ->
        episodeRepo.isDateInEpisode(listOf(ep), date)
    }
}

/**
 * Records (or overwrites) the per-day severity rating for [episodeId] on
 * [date] — the "how did the intensity change over time" series. The onset
 * [InjuryEpisode.severity] field is updated too when rating the onset day.
 */
fun HabitViewModel.recordEpisodeDaySeverity(
    habitName: String,
    episodeId: String,
    date: LocalDate,
    severity: Int
) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) return
    viewModelScope.launch {
        val ext = resolveEpisodeUri(habitName)
        val episodes = episodeRepo.loadEpisodes(context, habitName, ext)
        val dateStr = com.example.tail.data.dateString(date)
        val updated = episodes.map { ep ->
            if (ep.id == episodeId) ep.copy(
                severityByDay = ep.severityByDay.toMutableMap().apply { put(dateStr, severity) },
                severity = if (ep.onsetDate == dateStr) severity else ep.severity
            ) else ep
        }
        episodeRepo.saveEpisodes(context, habitName, updated, ext)
        _episodesCache.value = _episodesCache.value.toMutableMap().apply {
            put(habitName, updated)
        }
        // Episode data changes don't alter the binary daily flag, but bump the
        // change bus so dependent views refresh.
        HabitsDataChangedBus.emit()
    }
}

/**
 * Starts a NEW episode for [habitName] (the "onset" action). DEDUPE
 * ENFORCEMENT: refuses when an episode is already open — the UI must route
 * an open episode to [updateEpisode] instead, but the writer guards too so
 * a race can never create two open episodes.
 */
fun HabitViewModel.startInjuryEpisode(
    habitName: String,
    onsetDate: LocalDate,
    type: String,
    bodyPart: String,
    bodyPartDetail: String,
    side: String,
    severity: Int?,
    note: String
) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }
    viewModelScope.launch {
        val ext = resolveEpisodeUri(habitName)
        val episodes = episodeRepo.loadEpisodes(context, habitName, ext)
        if (episodeRepo.openEpisode(episodes) != null) {
            _errorMessage.value =
                "An injury episode is already open — update or resolve it first."
            return@launch
        }
        val onsetStr = com.example.tail.data.dateString(onsetDate)
        val updated = episodes.filter { it.id != onsetStr } + InjuryEpisode(
            id = onsetStr,
            onsetDate = onsetStr,
            resolvedDate = null,
            type = type,
            bodyPart = bodyPart,
            bodyPartDetail = bodyPartDetail,
            side = side,
            severity = severity,
            // Seed the severity-over-time series with the onset rating so
            // the graph shows the first data point immediately.
            severityByDay = severity?.let { mapOf(onsetStr to it) } ?: emptyMap(),
            note = note
        )
        episodeRepo.saveEpisodes(context, habitName, updated, ext)
        rewriteEpisodeDerivedFlag(habitName, updated)
        rebuildHabitList()
        try {
            habitsRepo.persistDatabase(Uri.parse(uriString), context, cachedPhoneDb)
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save episode: ${e.message}"
        }
    }
}

/**
 * Updates fields of an existing episode (identified by [episodeId]); used by
 * the "Update episode" screen (severity change, note). Pass through the
 * current values for unchanged fields.
 */
fun HabitViewModel.updateInjuryEpisode(
    habitName: String,
    episodeId: String,
    type: String,
    bodyPart: String,
    bodyPartDetail: String,
    side: String,
    severity: Int?,
    note: String
) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) return
    viewModelScope.launch {
        val ext = resolveEpisodeUri(habitName)
        val episodes = episodeRepo.loadEpisodes(context, habitName, ext)
        val updated = episodes.map { ep ->
            if (ep.id == episodeId) ep.copy(
                type = type,
                bodyPart = bodyPart,
                bodyPartDetail = bodyPartDetail,
                side = side,
                severity = severity,
                note = note
            ) else ep
        }
        episodeRepo.saveEpisodes(context, habitName, updated, ext)
        rewriteEpisodeDerivedFlag(habitName, updated)
        rebuildHabitList()
        try {
            habitsRepo.persistDatabase(Uri.parse(uriString), context, cachedPhoneDb)
            HabitsDataChangedBus.emit()
        } catch (_: Exception) {
        }
    }
}

/**
 * Marks the episode [episodeId] RESOLVED on [resolvedDate] (the explicit
 * "Mark resolved" action). The derived daily flag goes to 0 from
 * resolvedDate+1 onward.
 */
fun HabitViewModel.resolveInjuryEpisode(
    habitName: String,
    episodeId: String,
    resolvedDate: LocalDate
) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) return
    viewModelScope.launch {
        val ext = resolveEpisodeUri(habitName)
        val episodes = episodeRepo.loadEpisodes(context, habitName, ext)
        val updated = episodes.map { ep ->
            if (ep.id == episodeId) ep.copy(resolvedDate = com.example.tail.data.dateString(resolvedDate))
            else ep
        }
        episodeRepo.saveEpisodes(context, habitName, updated, ext)
        rewriteEpisodeDerivedFlag(habitName, updated)
        rebuildHabitList()
        try {
            habitsRepo.persistDatabase(Uri.parse(uriString), context, cachedPhoneDb)
            HabitsDataChangedBus.emit()
        } catch (_: Exception) {
        }
    }
}

/**
 * Rewrites the DERIVED daily flag for [habitName] from [episodes] — the
 * backwards-compatible `injured_today` output: every EXISTING habitsdb date
 * key of the habit becomes 1 when it falls inside an episode (onset..
 * resolved inclusive; open episodes run to today), else 0. Never invents new
 * dates — day placeholders are created by the regular ensureDaysExist path,
 * and the daily roll-forward hook keeps "today" correct for open episodes.
 */
fun HabitViewModel.rewriteEpisodeDerivedFlag(habitName: String, episodes: List<InjuryEpisode>) {
    val habitEntries = cachedPhoneDb[habitName] ?: return
    val today = LocalDate.now()
    val newEntries = habitEntries.mapValues { (dateStr, _) ->
        val d = runCatching { LocalDate.parse(dateStr) }.getOrNull()
        if (d != null && episodeRepo.isDateInEpisode(episodes, d, today)) 1 else 0
    }
    val updatedDb = cachedPhoneDb.toMutableMap()
    updatedDb[habitName] = newEntries
    cachedPhoneDb = updatedDb
}

/**
 * Daily maintenance for episode habits (called from the roll-forward pass):
 * an OPEN episode keeps today's derived flag at 1; no open episode leaves it
 * at 0. Runs before [performRollForwardIfNeeded] marks the day done.
 */
internal suspend fun HabitViewModel.performEpisodeDailyFlagMaintenance() {
    val episodeHabits = _settings.value.episodeHabits
    if (episodeHabits.isEmpty()) return
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) return
    val today = LocalDate.now()
    val todayStr = com.example.tail.data.dateString(today)
    var dbChanged = false
    val updatedDb = cachedPhoneDb.toMutableMap()
    for (habitName in episodeHabits) {
        val episodes = episodeRepo.loadEpisodes(
            context, habitName, EpisodeDataRepository.episodeUriOrNull(_settings.value.episodeFileUris[habitName])
        )
        val open = episodeRepo.openEpisode(episodes)
        val wanted = if (open != null) 1 else 0
        val entries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
        if ((entries[todayStr] ?: 0) != wanted) {
            entries[todayStr] = wanted
            updatedDb[habitName] = entries
            dbChanged = true
        }
    }
    if (dbChanged) {
        cachedPhoneDb = updatedDb
        try {
            habitsRepo.persistDatabase(Uri.parse(uriString), context, updatedDb)
            HabitsDataChangedBus.emit()
        } catch (_: Exception) {
        }
    }
}

/**
 * Toggles the "Sick (illness episode)" variant on/off for [habitName]
 * (edit-mode control bar). Enabling also enables the base episode type and
 * clears the same conflicting type flags as [toggleEpisodeHabit]; disabling
 * only removes the illness variant.
 */
fun HabitViewModel.toggleSickEpisodeHabit(habitName: String) {
    viewModelScope.launch {
        val sick = _settings.value.sickEpisodeHabits.toMutableSet()
        if (habitName in sick) {
            sick.remove(habitName)
            settingsRepo.saveSickEpisodeHabits(sick)
            _settings.value = _settings.value.copy(sickEpisodeHabits = sick)
        } else {
            sick.add(habitName)
            // Illness implies the episode type; clear conflicting daily-log
            // type flags exactly like the base episode toggle.
            val episode = (_settings.value.episodeHabits + habitName)
            val textInput = _settings.value.textInputHabits - habitName
            val textUris = _settings.value.textInputFileUris.toMutableMap().also { it.remove(habitName) }
            val custom = _settings.value.customInputHabits - habitName
            val roll = _settings.value.rollForwardHabits - habitName
            val rollDates = _settings.value.rollForwardManualDates.toMutableMap().also { it.remove(habitName) }
            settingsRepo.saveSickEpisodeHabits(sick)
            settingsRepo.saveEpisodeHabits(episode)
            settingsRepo.saveTextInputHabits(textInput)
            settingsRepo.saveTextInputFileUris(textUris)
            settingsRepo.saveCustomInputHabits(custom)
            settingsRepo.saveRollForwardHabits(roll)
            settingsRepo.saveRollForwardManualDates(rollDates)
            _settings.value = _settings.value.copy(
                sickEpisodeHabits = sick,
                episodeHabits = episode,
                textInputHabits = textInput,
                textInputFileUris = textUris,
                customInputHabits = custom,
                rollForwardHabits = roll,
                rollForwardManualDates = rollDates
            )
        }
        rebuildHabitList()
    }
}

/** Toggles the "episode" type on/off for [habitName] (edit-mode control bar). */
fun HabitViewModel.toggleEpisodeHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.episodeHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
            _settings.value = _settings.value.copy(episodeHabits = current)
            settingsRepo.saveEpisodeHabits(current)
        } else {
            current.add(habitName)
            // Episode capture is exclusive with text-input/custom-input/roll-
            // forward daily logging — remove those flags when enabling.
            val textInput = _settings.value.textInputHabits - habitName
            val textUris = _settings.value.textInputFileUris.toMutableMap().also { it.remove(habitName) }
            val custom = _settings.value.customInputHabits - habitName
            val roll = _settings.value.rollForwardHabits - habitName
            val rollDates = _settings.value.rollForwardManualDates.toMutableMap().also { it.remove(habitName) }
            settingsRepo.saveEpisodeHabits(current)
            settingsRepo.saveTextInputHabits(textInput)
            settingsRepo.saveTextInputFileUris(textUris)
            settingsRepo.saveCustomInputHabits(custom)
            settingsRepo.saveRollForwardHabits(roll)
            settingsRepo.saveRollForwardManualDates(rollDates)
            _settings.value = _settings.value.copy(
                episodeHabits = current,
                textInputHabits = textInput,
                textInputFileUris = textUris,
                customInputHabits = custom,
                rollForwardHabits = roll,
                rollForwardManualDates = rollDates
            )
        }
        rebuildHabitList()
    }
}

// ── Data-integrity check: text rows on unchecked days ─────────────────────

/**
 * Finds all text-log keys of [habitName] that fall on a day whose stored
 * habit points are ZERO — the exact desync class that produced 39 phantom
 * "Pulled muscle…" rows after the injury ended (2026-08-28 → 2026-10-05).
 * Returns the offending timestamp keys; empty = clean.
 */
fun HabitViewModel.findTextRowsWithoutPoints(habitName: String, onResult: (List<String>) -> Unit) {
    viewModelScope.launch {
        val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
        val log = textInputRepo.loadTextLog(uri, context, habitName)
        val entries = cachedPhoneDb[habitName] ?: emptyMap()
        val offenders = log.keys.filter { ts ->
            val day = ts.take(10) // "yyyy-MM-dd" prefix of the key
            (entries[day] ?: 0) == 0
        }
        onResult(offenders)
    }
}

/**
 * Deletes every text row found by [findTextRowsWithoutPoints] — the repair
 * half of the integrity check. Used by the one-time Injury migration; safe
 * to re-run (idempotent).
 */
fun HabitViewModel.deleteTextRowsWithoutPoints(habitName: String) {
    viewModelScope.launch {
        val uri = TextInputRepository.textUriOrNull(_settings.value.textInputFileUris[habitName])
        val log = textInputRepo.loadTextLog(uri, context, habitName)
        val entries = cachedPhoneDb[habitName] ?: emptyMap()
        val offenders = log.keys.filter { ts -> (entries[ts.take(10)] ?: 0) == 0 }
        if (offenders.isNotEmpty()) {
            textInputRepo.deleteTextEntries(uri, context, offenders, habitName = habitName)
            android.util.Log.i(
                "EpisodeIntegrity",
                "Removed ${offenders.size} zero-point text rows for '$habitName'"
            )
        }
    }
}
