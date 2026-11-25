package com.example.tail.ui.viewmodel

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.example.tail.data.AppSettings
import com.example.tail.data.DEFAULT_CUSTOM_INPUT_AMOUNTS
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.TextInputRepository
import com.example.tail.data.dateString
import com.example.tail.data.isAppLink
import com.example.tail.data.minutesKey
import com.example.tail.data.suggest.AmountHistory
import com.example.tail.data.suggest.HabitScore
import com.example.tail.data.suggest.HabitScorer
import com.example.tail.data.suggest.PillSuggester
import com.example.tail.data.suggest.StampIndex
import com.example.tail.data.suggest.SuggestionFeedback
import com.example.tail.data.suggest.SuggestionMath
import com.example.tail.data.suggest.SuggestionSession
import com.example.tail.data.suggest.ValueEvent
import com.example.tail.data.suggest.ValuePicker
import com.example.tail.ui.grid.HabitSuggestion
import com.example.tail.ui.grid.SuggestionKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

/** Maximum number of suggestions shown in the flash (compact chips, 2 rows). */
const val SUGGESTION_MAX_COUNT = 8

/** Days of timestamp / value history feeding the engine. */
private const val SUGGESTION_HISTORY_DAYS = 120

/** Minimum habit score (≈ probability the habit is wanted soon) to be suggested at all. */
private const val SUGGESTION_MIN_SCORE = 0.30

/**
 * Feedback learning (see [SuggestionFeedback]): every shown card resolves to
 * ACCEPTED (✓ / ✎ / a manual increment of the same habit while the flash is
 * open) or IGNORED, and the resulting per-habit acceptance rate becomes a
 * multiplier on the history score. Habits the user never act on sink below
 * the threshold; habits they do act on float up.
 */
private val suggestionSessionLock = Any()
@Volatile
private var suggestionSession: SuggestionSession? = null

/**
 * Starts a new feedback session for the flash about to show [names]. The
 * previous session (if any) is settled first so its cards are never lost.
 */
fun HabitViewModel.beginSuggestionSession(names: List<String>) {
    settleSuggestionSession()
    synchronized(suggestionSessionLock) {
        val s = SuggestionSession(context)
        for (n in names) s.track(n)
        suggestionSession = s
    }
}

/** Ends the current session: every card not yet resolved counts as IGNORED. */
fun HabitViewModel.settleSuggestionSession() {
    synchronized(suggestionSessionLock) {
        val s = suggestionSession ?: return
        suggestionSession = null
        s.settle()
    }
}

/** Resolves [habit]'s card in the open session as ACCEPTED (✓ / ✎ tap). */
fun HabitViewModel.markSuggestionAccepted(habit: String) {
    synchronized(suggestionSessionLock) { suggestionSession?.markAccepted(habit) }
}

/**
 * Called from the increment path on every manual increment: when the
 * habit was on the open flash, the suggestion was right after all.
 */
fun HabitViewModel.onManualIncrementForSuggestions(habit: String) {
    synchronized(suggestionSessionLock) { suggestionSession?.onManualIncrement(habit) }
}

/** Wipes the learned feedback (Settings → "Reset suggestion learning"). */
fun HabitViewModel.resetSuggestionLearning() {
    SuggestionFeedback.clear(context)
}

/** A predicted VALUE (text / subtype) below this share is a coin-flip, not a suggestion. */
private const val SUGGESTION_MIN_VALUE_CONF = 0.20

/** Minimum consensus-bundle strength for a pill suggestion. */
private const val SUGGESTION_MIN_PILL_SCORE = 0.35

/** Candidates resolved to payloads before the final value-confidence re-rank. */
private const val SUGGESTION_RESOLVE_POOL = 14

/** Streak length from which the reason label mentions the streak. */
private const val SUGGESTION_STREAK_REASON_MIN = 7

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
 * True when a tap on the habit is not a plain "log one" action the flash
 * could replay: combo batch popup, floating timer, episode / soreness /
 * blood-test dialogs.
 */
private fun isUnreplayableTap(name: String, s: AppSettings): Boolean =
    name in s.comboInputHabits ||
        name in s.habitTimerHabits ||
        name in s.episodeHabits ||
        name.equals("Sick", ignoreCase = true) ||
        isSorenessHabit(name) ||
        name in s.bloodTestHabits ||
        // Custom point ranges SET the day's value — "+amount" is meaningless.
        name in s.customPointRangesHabits

/** The suggestion kind a tap on [name] corresponds to (mirrors the grid's tap dispatch order). */
private fun suggestionKindOf(name: String, s: AppSettings): SuggestionKind = when {
    name in s.subtypedHabits -> SuggestionKind.SUBTYPE
    name in s.pillLogHabits -> SuggestionKind.PILLS
    name in s.weightsHabits -> SuggestionKind.WEIGHTS
    name in s.textInputHabits -> SuggestionKind.TEXT
    name in s.customInputHabits -> SuggestionKind.CUSTOM_AMOUNT
    else -> SuggestionKind.PLAIN
}

/**
 * Builds the "likely next" suggestions for the ⚡ flash.
 *
 * Goal: SAVE CLICKS. A suggestion is only produced when its ✓ is cheaper
 * than doing the same thing by hand:
 *  - habits whose tap opens a dialog or needs input (custom amount / text /
 *    subtype / pills / weights) save the most clicks and ALWAYS take
 *    precedence — they are ordered to the LEFT of plain ones in the strip;
 *  - PLAIN single-tap habits are also suggested (still one click saved, and
 *    no hunting for the square), but they sort after every input-type
 *    suggestion. Weights visible on the viewed screen are the one
 *    exclusion: the flash has no ✓ for them (dialog ✎ only), so a visible
 *    weights square is already the cheaper path.
 *
 * Candidates: with [screenIndex] == null (app open / ⚡ button) EVERY habit
 * of EVERY screen; with a screen index (user switched to that screen), only
 * that screen's habits.
 *
 * Ranking and payload prediction are delegated to the shared engine in
 * `data.suggest` (see [HabitScorer], [ValuePicker], [AmountHistory],
 * [PillSuggester]): every past day is weighted by recency × weekday
 * similarity × "how many times was it done by now" × "how similar were the
 * other habits done so far", so what has already happened today and what
 * never happens on comparable days drive the ranking.
 */
fun HabitViewModel.buildHabitSuggestions(
    screenIndex: Int? = null,
    onReady: (List<HabitSuggestion>) -> Unit
) {
    // Snapshot main-thread-only state BEFORE hopping to a worker thread.
    val settings = _settings.value
    val today = LocalDate.now()
    val activeIdx = _activeScreenIndex.value
    val streaks = HashMap<String, Int>()
    for (h in _habits.value) streaks[h.name] = h.currentStreak
    for ((key, list) in screenHabitCache.toList()) {
        if (key.second == today) for (h in list) streaks.putIfAbsent(h.name, h.currentStreak)
    }
    val fallbackNames = _habits.value.map { it.name }
    val db = cachedPhoneDb
    viewModelScope.launch(Dispatchers.Default) {
        val result = try {
            computeSuggestions(settings, db, streaks, fallbackNames, screenIndex, activeIdx)
        } catch (e: Exception) {
            Log.w("Suggestions", "computeSuggestions failed: ${e.message}", e)
            emptyList()
        }
        withContext(Dispatchers.Main) { onReady(result) }
    }
}

private suspend fun HabitViewModel.computeSuggestions(
    settings: AppSettings,
    db: HabitsDatabase,
    streaks: Map<String, Int>,
    fallbackNames: List<String>,
    screenIndex: Int?,
    activeIdx: Int
): List<HabitSuggestion> {
    if (!settings.suggestionFlashEnabled) return emptyList()
    val screens = settings.habitScreens.takeIf { it.isNotEmpty() } ?: _habitScreens.value
    val today = LocalDate.now()
    val todayKey = dateString(today)

    // Candidate universe + the set of habits whose square is on screen now.
    val candidateNames: List<String>
    val visible: Set<String>
    if (screens.isNotEmpty()) {
        val viewed = (screenIndex ?: activeIdx).coerceIn(0, screens.size - 1)
        visible = screens[viewed].habitNames.toSet()
        candidateNames = if (screenIndex != null && screenIndex in screens.indices) {
            screens[screenIndex].habitNames
        } else screens.flatMap { it.habitNames }.distinct()
    } else {
        visible = fallbackNames.toSet()
        candidateNames = fallbackNames
    }

    val eligible = candidateNames.filter { name ->
        val doneRaw = db[name]?.get(todayKey) ?: 0
        val waterGoal = settings.waterDailyGoals[name] ?: 0
        !isAppLink(name) &&
            !isSpecialFlowHabit(name, settings) &&
            !isAutoIncrementedHabit(name, settings) &&
            !isUnreplayableTap(name, settings) &&
            name !in settings.datedEntryHabits &&
            name !in settings.disabledHabits &&
            name !in settings.suggestionExcludedHabits &&
            // 1-max / inverted-binary habits already done today are pointless to suggest
            !((name in settings.maxOneHabits || name in settings.invertedBinaryHabits) && doneRaw > 0) &&
            // Water goal reached: stop nagging.
            !(waterGoal > 0 && name in settings.waterHabits && doneRaw >= waterGoal) &&
            // Weights visible on the viewed screen have no ✓ in the flash
            // (✎ opens the same dialog the square opens) — everything else,
            // including visible PLAIN habits, is eligible; precedence is
            // applied later at ordering time (input kinds sort left of PLAIN).
            !(name in visible && suggestionKindOf(name, settings) == SuggestionKind.WEIGHTS)
    }
    if (eligible.isEmpty()) return emptyList()

    val now = LocalTime.now()
    val nowMin = now.hour * 60 + now.minute
    val timestamps = timestampRepo.loadAll() // habit → date → ["HH:mm:ss"]
    val scorer = HabitScorer(StampIndex(timestamps, today, SUGGESTION_HISTORY_DAYS), nowMin)
    // Learned per-habit acceptance rates (SuggestionFeedback): habits the
    // user habitually ignores are damped, habits they act on are boosted.
    val feedback = SuggestionFeedback.multipliers(context)

    val scored = eligible.mapNotNull { name ->
        val sc = scorer.score(name, streaks[name] ?: 0) ?: return@mapNotNull null
        // Re-rank on the feedback-adjusted score so a damped card also
        // loses its rank to better-accepted ones, not just its eligibility.
        val adjusted = sc.score * (feedback[name] ?: 1.0)
        if (adjusted < SUGGESTION_MIN_SCORE) null
        else if (adjusted == sc.score) sc else sc.copy(score = adjusted)
    }.sortedByDescending { it.score }
    // Input-type candidates get reserved resolution slots so a flood of
    // high-scoring PLAIN habits can never squeeze them out of the strip;
    // PLAIN needs at most SUGGESTION_MAX_COUNT resolved payloads.
    val pool = buildList {
        addAll(scored.filter { suggestionKindOf(it.habit, settings) != SuggestionKind.PLAIN }
            .take(SUGGESTION_RESOLVE_POOL))
        addAll(scored.filter { suggestionKindOf(it.habit, settings) == SuggestionKind.PLAIN }
            .take(SUGGESTION_MAX_COUNT))
    }
    if (pool.isEmpty()) return emptyList()

    val resolved = ArrayList<Pair<HabitSuggestion, Double>>()
    for (sc in pool) {
        val kind = suggestionKindOf(sc.habit, settings)
        val reason = reasonFor(sc, streaks[sc.habit] ?: 0)
        val r = runCatching {
            resolvePayload(sc.habit, kind, reason, settings, db, timestamps, today, nowMin)
        }.onFailure { Log.w("Suggestions", "payload for '${sc.habit}' failed: ${it.message}") }
            .getOrNull() ?: continue
        val (suggestion, conf) = r
        // A habit that is likely but whose VALUE is a coin flip is less useful.
        val adj = sc.score * (0.7 + 0.3 * minOf(1.0, conf / 0.5))
        resolved.add(suggestion to adj)
    }
    // Precedence: multi-click / input-type suggestions (dialog pre-fills)
    // always render LEFT of plain one-tap ones; within each group, score.
    return resolved.sortedWith(
        compareByDescending<Pair<HabitSuggestion, Double>> { it.first.kind != SuggestionKind.PLAIN }
            .thenByDescending { it.second }
    ).take(SUGGESTION_MAX_COUNT).map { it.first }
}

private fun reasonFor(sc: HabitScore, streak: Int): String {
    fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    return when {
        sc.overdue && sc.usualFirst >= 0 -> "usually done by ${hm(sc.usualFirst)}"
        sc.countToday == 0 && streak >= SUGGESTION_STREAK_REASON_MIN -> "$streak-day streak"
        sc.usualMinute >= 0 -> "usual around ${hm(sc.usualMinute)}"
        else -> "${(sc.pWindow * 100).toInt().coerceIn(1, 99)}% likely now"
    }
}

/** Predicts the full pre-fill payload; returns (suggestion, value confidence 0..1) or null to drop. */
private suspend fun HabitViewModel.resolvePayload(
    name: String,
    kind: SuggestionKind,
    reason: String,
    settings: AppSettings,
    db: HabitsDatabase,
    timestamps: Map<String, Map<String, List<String>>>,
    today: LocalDate,
    nowMin: Int
): Pair<HabitSuggestion, Double>? = when (kind) {
    SuggestionKind.WEIGHTS -> HabitSuggestion(name, kind, reason = reason) to 1.0
    SuggestionKind.PLAIN -> HabitSuggestion(name, kind, amount = 1, reason = reason) to 1.0
    SuggestionKind.PILLS -> {
        val uri = TextInputRepository.textUriOrNull(settings.textInputFileUris[name])
        val log = textInputRepo.loadTextLog(uri, context, name)
        val bundle = PillSuggester.suggest(log, effectivePillCatalog(), nowMin, today, maxResults = 1)
            .firstOrNull()
        if (bundle == null || bundle.score < SUGGESTION_MIN_PILL_SCORE) null
        else HabitSuggestion(name, kind, pills = bundle.counts, reason = reason) to bundle.score
    }
    SuggestionKind.TEXT -> {
        val uri = TextInputRepository.textUriOrNull(settings.textInputFileUris[name])
        val log = textInputRepo.loadTextLog(uri, context, name)
        val hidden = settings.textInputHiddenGroupings[name] ?: emptySet()
        val raw = ArrayList<Triple<LocalDate, Int, String>>()
        for ((key, text) in log) {
            if (text.isBlank() || text in hidden) continue
            val (d, m) = SuggestionMath.parseLogKey(key) ?: continue
            if (d > today || d < today.minusDays(SUGGESTION_HISTORY_DAYS.toLong())) continue
            raw.add(Triple(d, m, text))
        }
        val events = ValuePicker.events(raw)
        val todays = events.filter { it.date == today }.sortedBy { it.minute }
        val ranked = ValuePicker.rank(
            events, today, nowMin, todayOrdinal = todays.size,
            todayPrev = todays.lastOrNull()?.value,
            todayValues = todays.map { it.value }.toSet()
        )
        val top = ranked.firstOrNull()
        if (top == null || top.second < SUGGESTION_MIN_VALUE_CONF) null
        else HabitSuggestion(name, kind, text = top.first, reason = reason) to top.second
    }
    SuggestionKind.SUBTYPE -> {
        val subtypes = settings.habitSubtypes[name].orEmpty()
        if (subtypes.isEmpty()) null else resolveSubtype(name, subtypes, reason, today, nowMin)
    }
    SuggestionKind.CUSTOM_AMOUNT -> {
        val minutesPrimary = name in settings.widgetTimerMinutesPrimary
        val dayTotals = db[if (minutesPrimary) minutesKey(name) else name].orEmpty()
        val exact = amountLogRepo.load(name)
        val known = (
            (settings.customInputAmounts[name] ?: DEFAULT_CUSTOM_INPUT_AMOUNTS) +
                (settings.customInputRecentAmounts[name] ?: emptyList()) +
                exact.values
            ).toSet()
        val events = AmountHistory.build(
            stampsByDate = timestamps[name].orEmpty(),
            dayTotals = dayTotals,
            exact = exact,
            known = known,
            today = today,
            historyDays = SUGGESTION_HISTORY_DAYS
        )
        val todayStamps = timestamps[name]?.get(today.toString()).orEmpty()
        val ranked = ValuePicker.rank(
            events, today, nowMin, todayOrdinal = todayStamps.size, todayPrev = null
        )
        val top = ranked.firstOrNull()
        val amount = top?.first
            ?: settings.customInputRecentAmounts[name]?.firstOrNull()
            ?: settings.customInputAmounts[name]?.firstOrNull()
            ?: 1
        HabitSuggestion(name, kind, amount = amount, reason = reason) to (top?.second ?: 0.3)
    }
}

/** Predicts (subtype, count) for a subtyped habit from its timed sessions / day breakdowns. */
private suspend fun HabitViewModel.resolveSubtype(
    name: String,
    subtypes: List<String>,
    reason: String,
    today: LocalDate,
    nowMin: Int
): Pair<HabitSuggestion, Double>? {
    val cutoff = today.minusDays(SUGGESTION_HISTORY_DAYS.toLong())
    val raw = ArrayList<Triple<LocalDate, Int, String>>()
    val counts = ArrayList<Triple<LocalDate, Int, Pair<String, Int>>>()
    for ((key, entry) in timedDataRepo.loadTimedData(name)) {
        val st = entry.subtype ?: continue
        if (st !in subtypes) continue
        val (d, m) = SuggestionMath.parseLogKey(key) ?: continue
        if (d > today || d < cutoff) continue
        raw.add(Triple(d, m, st))
        counts.add(Triple(d, m, st to entry.count.coerceAtLeast(1)))
    }
    var events: List<ValueEvent<String>> = ValuePicker.events(raw)
    if (events.isEmpty()) {
        // No timed sessions: fall back to the per-day subtype breakdown (no time of day).
        val perDay = subtypeDataRepo.loadAll()[name].orEmpty()
        val fallback = ArrayList<ValueEvent<String>>()
        for ((ds, bySub) in perDay) {
            val d = runCatching { LocalDate.parse(ds) }.getOrNull() ?: continue
            if (d > today || d < cutoff) continue
            for ((st, n) in bySub) if (st in subtypes && n > 0) {
                fallback.add(ValueEvent(d, SuggestionMath.TIMELESS, st, n.toDouble()))
            }
        }
        events = fallback
    }
    val todays = events.filter { it.date == today }.sortedBy { it.minute }
    val ranked = ValuePicker.rank(
        events, today, nowMin, todayOrdinal = todays.size, todayPrev = todays.lastOrNull()?.value
    )
    val top = ranked.firstOrNull() ?: return null
    if (top.second < SUGGESTION_MIN_VALUE_CONF) return null
    val subtype = top.first
    // Usual count for that subtype (modal, recency-weighted).
    val countEvents = counts.filter { it.third.first == subtype }
        .map { Triple(it.first, it.second, it.third.second) }
    val amount = ValuePicker.rank(
        ValuePicker.events(countEvents), today, nowMin, todayOrdinal = 0, todayPrev = null
    ).firstOrNull()?.first ?: 1
    return HabitSuggestion(name, SuggestionKind.SUBTYPE, amount = amount, subtype = subtype, reason = reason) to
        top.second
}

/**
 * Resolves a [com.example.tail.data.Habit] for a suggestion whose habit lives
 * on a screen other than the visible one (suggestions span every screen), so
 * its ✎ edit dialog can still open. Prefers the cached per-screen lists;
 * synthesises a minimal habit when no cache holds it.
 */
fun HabitViewModel.habitForSuggestion(name: String): com.example.tail.data.Habit? {
    _habits.value.firstOrNull { it.name == name }?.let { return it }
    for ((_, list) in screenHabitCache.toList()) list.firstOrNull { it.name == name }?.let { return it }
    val s = _settings.value
    return if (_habitScreens.value.any { name in it.habitNames })
        com.example.tail.data.Habit(name = name, useCustomInput = name in s.customInputHabits)
    else null
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
