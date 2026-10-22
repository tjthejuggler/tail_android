package com.example.tail.ui.viewmodel

// Soreness nightly habit — ViewModel logic. Split out of HabitViewModel.kt
// to keep individual Kotlin source files small (same pattern as
// HabitViewModelEpisode.kt).

import android.net.Uri
import com.example.tail.data.SorenessDataRepository
import com.example.tail.data.SorenessDay
import com.example.tail.data.TextInputRepository
import com.example.tail.ui.common.HabitsDataChangedBus
import java.time.LocalDate
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/** Process-wide soreness store (stateless — safe as a file-level singleton). */
private val sorenessRepo = SorenessDataRepository()

/**
 * Convention-based soreness type (like the "Injured" episode migration,
 * which is also keyed on the exact habit name): a habit named "Soreness"
 * gets the nightly 0–3 per-region + trained-today dialog on tap.
 */
fun isSorenessHabit(habitName: String): Boolean = habitName.equals("Soreness", ignoreCase = true)

/**
 * The external Soreness_daily.json URI: a sibling document created inside
 * the same synced SAF folder as the Injured habit's files (or the Soreness
 * habit's own text-input tree when configured). Null when nothing works —
 * the internal store then remains the sole copy.
 */
private fun HabitViewModel.resolveSorenessUri(): Uri? {
    val trees = sequence {
        yield(_settings.value.textInputFileUris["Soreness"])
        yield(_settings.value.textInputFileUris["Injured"])
    }
    for (tree in trees) {
        val treeUri = TextInputRepository.textUriOrNull(tree) ?: continue
        sorenessRepo.ensureSorenessUri(context, treeUri)?.let { return it }
    }
    return null
}

/** Loads the soreness row for [date] (null when the day wasn't logged). */
fun HabitViewModel.loadSorenessDay(date: LocalDate, onLoaded: (SorenessDay?) -> Unit) {
    viewModelScope.launch {
        val days = sorenessRepo.loadDays(context, resolveSorenessUri())
        onLoaded(days.firstOrNull { it.date == com.example.tail.data.dateString(date) })
    }
}

/**
 * Saves the nightly soreness row for [date]: the JSON file (merged by date,
 * internal + external mirror) AND the derived daily habit value in
 * habitsdb, which is set to the MAX region rating (0–3) so existing
 * reports/streaks keep working (>0 = something sore that day).
 */
fun HabitViewModel.saveSorenessDay(
    date: LocalDate,
    trained: Boolean,
    regions: Map<String, Int>
) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }
    viewModelScope.launch {
        val dateStr = com.example.tail.data.dateString(date)
        val ext = resolveSorenessUri()
        val days = sorenessRepo.loadDays(context, ext)
        val updated = days.filter { it.date != dateStr } + SorenessDay(
            date = dateStr,
            trained = trained,
            regions = regions
        )
        sorenessRepo.saveDays(context, updated, ext)

        // Derived daily value: max region rating 0–3.
        val maxValue = regions.values.maxOrNull() ?: 0
        val habitName = "Soreness"
        val entries = (cachedPhoneDb[habitName] ?: emptyMap()).toMutableMap()
        if (entries[dateStr] != maxValue) {
            entries[dateStr] = maxValue
            val updatedDb = cachedPhoneDb.toMutableMap()
            updatedDb[habitName] = entries
            cachedPhoneDb = updatedDb
        }
        rebuildHabitList()
        try {
            habitsRepo.persistDatabase(Uri.parse(uriString), context, cachedPhoneDb)
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save soreness: ${e.message}"
        }
    }
}
