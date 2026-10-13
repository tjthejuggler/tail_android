package com.example.tail.ui.viewmodel

import android.net.Uri
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.tail.data.minutesKey
import com.example.tail.ui.common.HabitsDataChangedBus
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * COMBO-INPUT HABIT — a pure input convenience (NOT a tracked habit).
 *
 * The user picks a set of habits in Settings (e.g. sauna + cold exposure).
 * The grid then shows a "⚡ Combo" pseudo-tile; tapping it opens a popup
 * where the user logs any number of sessions per habit with their minute
 * lengths, plus ONE anchor time (Start or End of the whole block — the
 * chosen mode is remembered for next time). Submitting the popup walks the
 * sessions in order and increments each habit at the computed wall-clock
 * time: count +1 per session, minutes into the habit's `minutes:` slot
 * (when it tracks minutes), and a matching timestamp (+ minutes-at-time)
 * per session — exactly as if each had been tapped at that moment.
 */

/** One logged combo session: the member habit and its length in minutes. */
data class ComboSession(
    val habitName: String,
    val minutes: Int
)

/**
 * Computes the per-session start times for a submitted combo.
 *
 * Sessions are ordered as entered. In START mode the anchor is the start of
 * the first session and time flows forward; in END mode the anchor is the
 * end of the last session and the whole block is shifted back by its total
 * length. Each session is stamped at its own START time.
 */
fun comboSessionTimes(
    sessions: List<ComboSession>,
    anchorHour: Int,
    anchorMinute: Int,
    endMode: Boolean
): List<LocalTime> {
    val total = sessions.sumOf { it.minutes }
    val anchor = anchorHour * 60 + anchorMinute
    val firstStart = if (endMode) anchor - total else anchor
    val times = ArrayList<LocalTime>(sessions.size)
    var cursor = firstStart
    for (s in sessions) {
        // Wrap around midnight instead of going negative.
        val normalized = ((cursor % 1440) + 1440) % 1440
        times.add(LocalTime.of(normalized / 60, normalized % 60))
        cursor += s.minutes
    }
    return times
}

/** Saves the member habits a combo habit batch-increments. */
fun HabitViewModel.setComboHabitMembers(comboHabit: String, members: Set<String>) {
    viewModelScope.launch {
        val updated = _settings.value.comboHabitMembers.toMutableMap()
        if (members.isEmpty()) updated.remove(comboHabit) else updated[comboHabit] = members
        settingsRepo.saveComboHabitMembers(updated)
        _settings.value = _settings.value.copy(comboHabitMembers = updated)
    }
}

/** Remembers the combo popup's anchor-time mode (true = End time). */
fun HabitViewModel.setComboHabitEndMode(endMode: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveComboHabitEndMode(endMode)
        _settings.value = _settings.value.copy(comboHabitEndMode = endMode)
    }
}

/** Toggles the "⚡ Combo input" TYPE for a habit (edit-mode toggle). */
fun HabitViewModel.toggleComboInputHabit(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.comboInputHabits
        val updated = if (habitName in current) current - habitName else current + habitName
        settingsRepo.saveComboInputHabits(updated)
        _settings.value = _settings.value.copy(comboInputHabits = updated)
        rebuildHabitList()
    }
}

/**
 * Submits a filled-in combo popup: increments every member habit at its
 * computed session time for the currently selected date.
 *
 * Count increment goes through the normal [incrementHabit] path so all
 * existing semantics apply (conditional feeds, max-1, dividers, roll-
 * forward handling, persist verification, widget refresh). The minutes slot
 * is added atomically afterwards via one [incrementHabitSlotsForDate] write
 * per habit, mirroring the bubble-timer stop path.
 */
fun HabitViewModel.submitComboHabit(
    sessions: List<ComboSession>,
    anchorHour: Int,
    anchorMinute: Int,
    endMode: Boolean
) {
    val valid = sessions.filter { it.minutes > 0 }
    if (valid.isEmpty()) return

    val times = comboSessionTimes(valid, anchorHour, anchorMinute, endMode)
    val date = _selectedDate.value

    for ((session, time) in valid.zip(times)) {
        val stampTime = String.format("%02d:%02d:00", time.hour, time.minute)
        // Full increment pipeline (persist + timestamps + feeds + rebuild).
        incrementHabit(
            habitName = session.habitName,
            amount = 1,
            recordTimestamp = true,
            date = date,
            stampTime = stampTime,
            minutes = session.minutes
        )
    }

    // Minutes slots: one atomic multi-key write per habit (habit may have
    // several sessions in this combo). Only for habits that track minutes.
    val settings = _settings.value
    val perHabitMinutes = valid
        .groupBy({ it.habitName }, { it.minutes })
        .filterKeys { habitName ->
            habitName in settings.minutesEnabledHabits ||
                habitName in settings.widgetTimerMinutesPrimary
        }
        .mapValues { (_, mins) -> mins.sum() }
    if (perHabitMinutes.isEmpty()) return

    val uriString = settings.fileUri
    if (uriString.isEmpty()) return
    val uri = Uri.parse(uriString)

    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                val increments = perHabitMinutes.mapKeys { (name, _) -> minutesKey(name) }
                habitsRepo.incrementHabitSlotsForDate(uri, context, increments, date)
            }
            // Annotate each session's timestamp with its minutes so the
            // timestamp editor shows the right per-session lengths.
            for ((session, time) in valid.zip(times)) {
                if (session.habitName in perHabitMinutes) {
                    val stampTime = String.format("%02d:%02d:00", time.hour, time.minute)
                    timestampRepo.addMinutesAtTime(session.habitName, date, stampTime, session.minutes)
                }
            }
            // The slot write bypassed the in-memory cache — resync + refresh.
            try {
                cachedPhoneDb = habitsRepo.loadDatabase(uri, context)
                dbEpoch++
                rebuildHabitList()
            } catch (e: Exception) {
                Log.w(TAG, "Combo post-slot rebuild failed: ${e.message}")
            }
            HabitsDataChangedBus.emit()
        } catch (e: Exception) {
            Log.e(TAG, "Combo minutes-slot write failed: ${e.message}", e)
            _errorMessage.value = "Combo saved counts, but minutes failed for some habits."
        }
    }
}

private const val TAG = "HabitViewModelCombo"
