package com.example.tail.ui.viewmodel

import com.example.tail.data.AppSettings
import com.example.tail.data.Habit
import com.example.tail.data.isAppLink
import androidx.lifecycle.viewModelScope
import com.example.tail.ui.grid.HabitSuggestion
import com.example.tail.ui.grid.SuggestionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.coroutines.resume

/** How many days of timestamp history feed the time-of-day scoring. */
private const val SUGGESTION_HISTORY_DAYS = 60L

/** Half-width of the "around now" window, in minutes. */
private const val SUGGESTION_WINDOW_MINUTES = 90

/** Maximum number of suggestions shown in the flash. */
const val SUGGESTION_MAX_COUNT = 6

/** Minimum score a habit must reach to be suggested at all. */
private const val SUGGESTION_MIN_SCORE = 0.35

private val SUGG_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")

/**
 * True when the habit is incremented (fully or mostly) AUTOMATICALLY — by
 * the PC bubble widget, the Android floating bubble, a media tracker, a
 * share target, or an external integration (Garmin / chess.com / GitHub /
 * environment / Inuit / movie bridge) — and must therefore never appear in
 * the manual increment-suggestion flash.
 */
fun isAutoIncrementedHabit(name: String, s: AppSettings): Boolean =
    name in s.pcWidgetHabits ||
        name in s.widgetTriggerHabits ||
        name in s.mediaHabits ||
        name in s.bridgeMovieHabits ||
        name in s.sharableTextHabits ||
        name in s.inuitTextHabits ||
        name in s.garminHabitLinks ||
        name in s.chessComHabitLinks ||
        name in s.githubRepoUrls ||
        name in s.environmentHabitMetrics

/**
 * True when the habit's tap opens a specialised multi-field flow that the
 * suggestion flash cannot pre-fill meaningfully (meal vision pipeline,
 * sleep-suite survey). These are excluded from suggestions.
 */
fun isSpecialFlowHabit(name: String, s: AppSettings): Boolean =
    name in s.mealHabits || name in s.sleepHabits

/**
 * Builds the "likely next increment" suggestions for the flash shown on
 * app open. Scoring per habit:
 *  - time-of-day match: timestamped increments within ±90 min of NOW over
 *    the last 60 days, exponentially decayed by age (recent days count
 *    more);
 *  - a small general-frequency term (any-time increments in the last 14
 *    days) so rarely-stamped habits still surface;
 *  - habits already done TODAY are heavily deprioritised (and 1-max /
 *    inverted-binary habits done today are excluded outright).
 *
 * Excluded outright: meal + sleep special flows, dated-entry (derived
 * counts), disabled habits, app links, and every automatically-incremented
 * habit (see [isAutoIncrementedHabit]).
 *
 * Each suggestion carries everything needed for a one-tap increment:
 * custom-input amount (most recent used), subtype (default = first), text
 * (most frequent past option), and an [HabitSuggestion.reason] label.
 */
fun HabitViewModel.buildHabitSuggestions(onReady: (List<HabitSuggestion>) -> Unit) {
    viewModelScope.launch(Dispatchers.Default) {
        val settings = _settings.value
        val habitList = _habits.value
        val result = runCatching { computeSuggestions(settings, habitList) }
            .getOrDefault(emptyList())
        withContext(Dispatchers.Main) { onReady(result) }
    }
}

private suspend fun HabitViewModel.computeSuggestions(
    settings: AppSettings,
    habitList: List<Habit>
): List<HabitSuggestion> {
    if (!settings.suggestionFlashEnabled) return emptyList()

    // Candidate names: everything on any screen (or the visible list when
    // no screens are configured), minus app links / special flows /
    // automatic habits / disabled habits.
    val candidates: List<Habit> = if (settings.habitScreens.isNotEmpty()) {
        val names = settings.habitScreens.flatMap { it.habitNames }.toSet()
        habitList.filter { it.name in names }
    } else habitList
    val eligible = candidates.filter { h ->
        !isAppLink(h.name) &&
            !isSpecialFlowHabit(h.name, settings) &&
            !isAutoIncrementedHabit(h.name, settings) &&
            h.name !in settings.datedEntryHabits &&
            h.name !in settings.disabledHabits &&
            // 1-max / inverted-binary habits already done today are pointless to suggest
            !((h.name in settings.maxOneHabits || h.name in settings.invertedBinaryHabits) && h.rawTodayCount > 0)
    }
    if (eligible.isEmpty()) return emptyList()

    val now = LocalTime.now()
    val today = LocalDate.now()
    val nowMinutes = now.hour * 60 + now.minute
    val timestamps = timestampRepo.loadAll() // habit → date → ["HH:mm:ss"]

    data class Scored(val habit: Habit, val score: Double, val usualMinutes: Int)

    val scored = eligible.map { habit ->
        val byDate = timestamps[habit.name] ?: emptyMap()
        var score = 0.0
        var weightedMinutes = 0.0
        var weightSum = 0.0
        for ((dateStr, times) in byDate) {
            val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
            val daysAgo = Duration.between(date.atStartOfDay(), today.atStartOfDay()).toDays()
            if (daysAgo < 0 || daysAgo > SUGGESTION_HISTORY_DAYS) continue
            val ageWeight = Math.exp(-daysAgo / 21.0) // ~3-week half-life-ish decay
            for (t in times) {
                val lt = runCatching { LocalTime.parse(t, SUGG_TIME_FMT) }.getOrNull() ?: continue
                val dist = abs(nowMinutes - (lt.hour * 60 + lt.minute))
                if (dist <= SUGGESTION_WINDOW_MINUTES) {
                    val closeness = 1.0 - dist.toDouble() / SUGGESTION_WINDOW_MINUTES
                    score += ageWeight * (0.5 + closeness)
                    weightedMinutes += ageWeight * (lt.hour * 60 + lt.minute)
                    weightSum += ageWeight
                }
                // General-frequency term (last 14 days, any time)
                if (daysAgo <= 14) score += 0.03
            }
        }
        // Already done today → strong deprioritise, but keep suggestible
        // (count habits can legitimately be incremented again)
        if (habit.rawTodayCount > 0) score *= 0.35
        val usual = if (weightSum > 0) (weightedMinutes / weightSum).toInt() else -1
        Scored(habit, score, usual)
    }.filter { it.score >= SUGGESTION_MIN_SCORE }
        .sortedByDescending { it.score }
        .take(SUGGESTION_MAX_COUNT)

    if (scored.isEmpty()) return emptyList()

    // Resolve pre-fill payloads. Text habits need their most frequent past
    // option (async via the existing loader); skip when none exists.
    val out = mutableListOf<HabitSuggestion>()
    for (s in scored) {
        val h = s.habit
        val reason = when {
            s.usualMinutes >= 0 -> "usual around %02d:%02d".format(s.usualMinutes / 60, s.usualMinutes % 60)
            h.rawTodayCount > 0 -> "already %d today".format(h.rawTodayCount)
            else -> "frequent habit"
        }
        when {
            h.name in settings.weightsHabits -> out.add(
                HabitSuggestion(h.name, SuggestionKind.WEIGHTS, reason = reason)
            )
            h.name in settings.subtypedHabits -> {
                val subtypes = settings.habitSubtypes[h.name].orEmpty()
                if (subtypes.isNotEmpty()) out.add(
                    HabitSuggestion(
                        h.name, SuggestionKind.SUBTYPE,
                        subtype = subtypes.first(), reason = reason
                    )
                )
            }
            h.name in settings.textInputHabits -> {
                val options = loadTextOptionsSuspend(h.name)
                // Most frequent recent option = first entry of the loader's
                // output (it returns history-ordered unique entries).
                val text = options.firstOrNull { it.isNotBlank() }
                if (text != null) out.add(
                    HabitSuggestion(h.name, SuggestionKind.TEXT, text = text, reason = reason)
                )
            }
            h.useCustomInput -> {
                val amount = settings.customInputRecentAmounts[h.name]?.firstOrNull()
                    ?: settings.customInputAmounts[h.name]?.firstOrNull()
                    ?: 1
                out.add(HabitSuggestion(h.name, SuggestionKind.CUSTOM_AMOUNT, amount = amount, reason = reason))
            }
            else -> out.add(HabitSuggestion(h.name, SuggestionKind.PLAIN, amount = 1, reason = reason))
        }
    }
    return out
}

/** [loadTextOptions] wrapped as a suspend fun for the suggestion builder. */
private suspend fun HabitViewModel.loadTextOptionsSuspend(habitName: String): List<String> =
    suspendCancellableCoroutine { cont ->
        loadTextOptions(habitName) { opts ->
            if (cont.isActive) cont.resume(opts)
        }
    }

/**
 * Enables or disables the increment-suggestion flash (Settings toggle).
 */
fun HabitViewModel.setSuggestionFlashEnabled(enabled: Boolean) {
    viewModelScope.launch {
        settingsRepo.saveSuggestionFlashEnabled(enabled)
        _settings.value = _settings.value.copy(suggestionFlashEnabled = enabled)
    }
}
