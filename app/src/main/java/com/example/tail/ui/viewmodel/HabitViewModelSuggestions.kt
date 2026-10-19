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

/** Habits stamped on fewer distinct days (within the history window) are never suggested. */
private const val SUGGESTION_MIN_DAYS = 4

/** Streak length from which the reason label mentions the streak. */
private const val SUGGESTION_STREAK_REASON_MIN = 7

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
 * app open. Scoring per habit (all terms over the last 60 days of
 * timestamped history):
 *
 *  1. REGULARITY GATE — habits stamped on fewer than 4 distinct days are
 *     treated as "rarely done" and never suggested.
 *  2. TIME-OF-DAY MATCH — increments within ±90 min of NOW, exponentially
 *     decayed by age, scaled by a confidence factor so a one-off lucky
 *     timestamp cannot carry a suggestion (needs ~5 matching days for
 *     full confidence).
 *  3. DAY-FREQUENCY TERM — fraction of the last 30 days the habit was
 *     done; near-daily habits surface even without a sharp time pattern.
 *  4. STREAK BOOST — an active streak (habit not yet done today) raises
 *     the score up to 1.5x; keeping a long streak going is exactly when
 *     a nudge is most useful.
 *  5. CO-OCCURRENCE BOOST — habits that historically land on the same
 *     day as a habit ALREADY stamped today get up to 1.6x ("goes with
 *     X"), using lift = P(both on a day) / P(partner on a day).
 *  6. DONE-TODAY PENALTY — habits already done today are heavily
 *     deprioritised (and 1-max / inverted-binary habits done today are
 *     excluded outright).
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
            h.name !in settings.suggestionExcludedHabits &&
            // 1-max / inverted-binary habits already done today are pointless to suggest
            !((h.name in settings.maxOneHabits || h.name in settings.invertedBinaryHabits) && h.rawTodayCount > 0)
    }
    if (eligible.isEmpty()) return emptyList()

    val now = LocalTime.now()
    val today = LocalDate.now()
    val nowMinutes = now.hour * 60 + now.minute
    val timestamps = timestampRepo.loadAll() // habit → date → ["HH:mm:ss"]

    // ---- Pass 1: parse timestamps into per-habit day sets (last 60 days).
    // daySets covers EVERY habit (including auto-incremented ones) because
    // co-occurrence partners may be automatic habits.
    val daySets = HashMap<String, HashSet<LocalDate>>()
    for ((habitName, byDate) in timestamps) {
        val days = daySets.getOrPut(habitName) { HashSet() }
        for (dateStr in byDate.keys) {
            val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
            val daysAgo = Duration.between(date.atStartOfDay(), today.atStartOfDay()).toDays()
            if (daysAgo in 0..SUGGESTION_HISTORY_DAYS) days.add(date)
        }
    }

    val todayStr = today.toString()
    // Habits already stamped today (any increment, manual or automatic).
    val stampedToday: Set<String> = timestamps
        .filter { it.value.containsKey(todayStr) && it.value[todayStr].orEmpty().isNotEmpty() }
        .keys

    // ---- Pass 2: score each eligible habit.
    data class Scored(
        val habit: Habit,
        val score: Double,
        val usualMinutes: Int,
        val dayFreq: Double,
        val coocPartner: String?
    )

    val scored = eligible.mapNotNull { habit ->
        val byDate = timestamps[habit.name] ?: emptyMap()
        val daysDone = daySets[habit.name] ?: emptySet()

        // 1. Regularity gate: rarely-done habits are never suggested.
        if (daysDone.size < SUGGESTION_MIN_DAYS) return@mapNotNull null

        var timeScore = 0.0
        var matchedDays = 0
        var weightedMinutes = 0.0
        var weightSum = 0.0
        for ((dateStr, times) in byDate) {
            val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
            val daysAgo = Duration.between(date.atStartOfDay(), today.atStartOfDay()).toDays()
            if (daysAgo < 0 || daysAgo > SUGGESTION_HISTORY_DAYS) continue
            val ageWeight = Math.exp(-daysAgo / 21.0) // ~3-week half-life-ish decay
            var dayMatched = false
            for (t in times) {
                val lt = runCatching { LocalTime.parse(t, SUGG_TIME_FMT) }.getOrNull() ?: continue
                val dist = abs(nowMinutes - (lt.hour * 60 + lt.minute))
                if (dist <= SUGGESTION_WINDOW_MINUTES) {
                    val closeness = 1.0 - dist.toDouble() / SUGGESTION_WINDOW_MINUTES
                    timeScore += ageWeight * (0.5 + closeness)
                    weightedMinutes += ageWeight * (lt.hour * 60 + lt.minute)
                    weightSum += ageWeight
                    dayMatched = true
                }
            }
            if (dayMatched) matchedDays++
        }
        // Confidence: a sharp time pattern needs several matching days to
        // be trusted; one or two lucky timestamps count for little.
        val timeConfidence = (matchedDays / 5.0).coerceAtMost(1.0)

        // 3. Day-frequency: fraction of the last 30 days the habit was
        // done. Near-daily habits earn a strong base score.
        val daysLast30 = daysDone.count { !it.isBefore(today.minusDays(30)) }
        val dayFreq = daysLast30 / 30.0
        val freqScore = 1.8 * dayFreq

        val doneToday = habit.rawTodayCount > 0

        // 4. Streak boost: only meaningful when the habit is NOT yet done
        // today (that's when keeping the streak alive matters).
        val streak = habit.currentStreak
        val streakBoost = if (!doneToday && streak > 0)
            1.0 + (streak.coerceAtMost(30)) / 60.0 else 1.0

        // 5. Co-occurrence: find the partner already stamped today with
        // the strongest historical lift for this habit.
        var coocBoost = 1.0
        var coocPartner: String? = null
        if (!doneToday) {
            for (partner in stampedToday) {
                if (partner == habit.name) continue
                val partnerDays = daySets[partner] ?: continue
                if (partnerDays.size < 5) continue
                val both = daysDone.count { it in partnerDays }
                if (both < 3) continue
                val lift = (both.toDouble() / partnerDays.size) /
                    (daysDone.size.toDouble() / (SUGGESTION_HISTORY_DAYS + 1))
                if (lift > 1.15) {
                    val boost = (1.0 + 0.5 * (lift - 1.0)).coerceAtMost(1.6)
                    if (boost > coocBoost) {
                        coocBoost = boost
                        coocPartner = partner
                    }
                }
            }
        }

        var score = (timeScore * timeConfidence + freqScore) *
            streakBoost * coocBoost
        // 6. Already done today → strong deprioritise, but keep suggestible
        // (count habits can legitimately be incremented again)
        if (doneToday) score *= 0.35

        val usual = if (weightSum > 0) (weightedMinutes / weightSum).toInt() else -1
        Scored(habit, score, usual, dayFreq, coocPartner)
    }.filter { it.score >= SUGGESTION_MIN_SCORE }
        .sortedByDescending { it.score }
        .take(SUGGESTION_MAX_COUNT)

    if (scored.isEmpty()) return emptyList()

    // Resolve pre-fill payloads. Text habits need their most frequent past
    // option (async via the existing loader); skip when none exists.
    val out = mutableListOf<HabitSuggestion>()
    for (s in scored) {
        val h = s.habit
        val streak = h.currentStreak
        val reason = when {
            h.rawTodayCount == 0 && streak >= SUGGESTION_STREAK_REASON_MIN ->
                "%d-day streak".format(streak)
            s.coocPartner != null -> "goes with %s".format(s.coocPartner)
            s.usualMinutes >= 0 -> "usual around %02d:%02d".format(s.usualMinutes / 60, s.usualMinutes % 60)
            s.dayFreq >= 0.8 -> "daily habit"
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
                val amount = likelyIncrementAmount(
                    h.name, settings, timestamps[h.name] ?: emptyMap()
                )
                out.add(HabitSuggestion(h.name, SuggestionKind.CUSTOM_AMOUNT, amount = amount, reason = reason))
            }
            else -> out.add(HabitSuggestion(h.name, SuggestionKind.PLAIN, amount = 1, reason = reason))
        }
    }
    return out
}

/**
 * The most likely single-increment amount for a custom-input habit, derived
 * from the habit's own history instead of the single most recent entry: for
 * every day in the suggestion window that has both a stored day total and at
 * least one timestamp, the implied per-increment amount is dayTotal ÷ stamps.
 * An implied amount only counts when it divides the day total EXACTLY and is
 * an amount the user actually uses (the habit's quick-amount list united with
 * its recent amounts). That keeps garbage out: day totals SET as absolute
 * values by desktop automation (e.g. the water bottle tracker) rarely divide
 * cleanly into a real amount, and stray quotients never match one. Accepted
 * amounts accumulate an age-decayed weight and the highest total wins — so a
 * one-off outlier (e.g. a random 1400 ml water entry) cannot hijack the
 * suggestion the way "most recent amount" did. Falls back to the most-recent
 * / configured amount when no day qualifies.
 */
private fun HabitViewModel.likelyIncrementAmount(
    habitName: String,
    settings: AppSettings,
    stampsByDate: Map<String, List<String>>
): Int {
    val today = LocalDate.now()
    val dayTotals = runCatching {
        cachedPhoneDb[habitName].orEmpty()
    }.getOrDefault(emptyMap())

    // Amounts the user really uses: quick-amount buttons + recently typed.
    val knownAmounts = (
        settings.customInputAmounts[habitName]
            ?: com.example.tail.data.DEFAULT_CUSTOM_INPUT_AMOUNTS
        ) + (settings.customInputRecentAmounts[habitName] ?: emptyList())
    val known = knownAmounts.toSet()

    val weights = HashMap<Int, Double>()
    for ((dateStr, times) in stampsByDate) {
        val total = dayTotals[dateStr] ?: continue
        val n = times.size
        if (n <= 0 || total <= 0) continue
        val daysAgo = runCatching {
            Duration.between(LocalDate.parse(dateStr).atStartOfDay(), today.atStartOfDay()).toDays()
        }.getOrNull() ?: continue
        if (daysAgo < 0 || daysAgo > SUGGESTION_HISTORY_DAYS) continue
        val unit = total.toDouble() / n
        // Only exact, recognizable quotients count. A day of 2700 ml over 3
        // stamps with 900 in the known set is clearly 3×900; a desktop-set
        // 2736 over 4 stamps (684) is noise — skip it.
        if (unit != Math.floor(unit)) continue
        val amount = unit.toInt()
        // Skip derived 1s: pre-scale count-era days (total == stamp count)
        // divide to exactly 1 and would otherwise drown out real amounts.
        // Genuine +1 habits still get 1 from the recents fallback below.
        if (amount > 1 && amount in known) {
            weights[amount] = (weights[amount] ?: 0.0) + Math.exp(-daysAgo / 21.0)
        }
    }
    return weights.maxByOrNull { it.value }?.key
        ?: settings.customInputRecentAmounts[habitName]?.firstOrNull()
        ?: settings.customInputAmounts[habitName]?.firstOrNull()
        ?: 1
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

/**
 * Adds or removes a habit from the manual suggestion-exclusion list.
 * Excluding a habit from a suggestion card's popup and un-excluding it
 * later in Settings both go through here.
 */
fun HabitViewModel.setSuggestionExcluded(habitName: String, excluded: Boolean) {
    viewModelScope.launch {
        val updated = _settings.value.suggestionExcludedHabits.toMutableSet()
        if (excluded) updated.add(habitName) else updated.remove(habitName)
        settingsRepo.saveSuggestionExcludedHabits(updated)
        _settings.value = _settings.value.copy(suggestionExcludedHabits = updated)
    }
}
