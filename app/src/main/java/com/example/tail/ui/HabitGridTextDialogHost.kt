package com.example.tail.ui

import androidx.compose.runtime.Composable
import com.example.tail.data.BridgeMovie
import com.example.tail.data.Habit
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Roll-forward confirmation request. Formerly a local class inside
 * [HabitGridScreen]'s composable; promoted to a top-level type so the
 * extracted text-input dialog host can construct it.
 */
internal data class RollForwardDialogState(
    val habitName: String,
    val actionType: String, // "increment" or "text"
    val startDate: LocalDate,
    val initialEndDate: LocalDate,
    val onConfirm: (LocalDate) -> Unit
)

/**
 * State for the text-input dialog. Formerly a file-private class in
 * [HabitGridScreen]; promoted to internal so the extracted host can take it.
 */
internal data class TextInputDialogState(
    val habit: Habit,
    val showOptions: Boolean,
    val options: List<String>,
    val todayEntries: List<Pair<String, String>> = emptyList(),
    /** Pre-filled text (e.g. from a movie bridge suggestion). Empty by default. */
    val suggestedText: String = "",
    /** Label shown above the text field when suggestedText is non-empty. */
    val suggestionLabel: String = "",
    /** Suggested watch-length in minutes (movie bridge); null = no length section. */
    val suggestedMinutes: Int? = null,
    /** True while the movie suggestion is still resolving (cache → bridge). */
    val suggestionLoading: Boolean = false,
    /** Last watched movies (newest first) for the quick picker. */
    val recentMovies: List<BridgeMovie> = emptyList()
)

/**
 * Opens the text-input dialog for [habit] (movie-bridge-aware). Extracted
 * from HabitGridScreen's onHabitClick lambda so that composable stays under
 * the JVM 64KB method-size limit. Behaviour unchanged.
 */
internal fun openTextInputDialog(
    setState: (TextInputDialogState?) -> Unit,
    getState: () -> TextInputDialogState?,
    viewModel: HabitViewModel,
    habit: Habit,
    selectedDate: java.time.LocalDate,
    showOpts: Boolean,
    isMovieLinked: Boolean
) {
    // Helper: open the dialog IMMEDIATELY with what is already known;
    // today's entries (and options) stream in afterwards — the dialog
    // reacts to state updates, so nothing blocks the popup from appearing.
    fun showDialog(
        suggestedText: String = "",
        suggestionLabel: String = "",
        suggestedMinutes: Int? = null,
        suggestionLoading: Boolean = false,
        recentMovies: List<BridgeMovie> = emptyList()
    ) {
        setState(
            TextInputDialogState(
                habit = habit,
                showOptions = showOpts,
                options = emptyList(),
                todayEntries = emptyList(),
                suggestedText = suggestedText,
                suggestionLabel = suggestionLabel,
                suggestedMinutes = suggestedMinutes,
                suggestionLoading = suggestionLoading,
                recentMovies = recentMovies
            )
        )
        viewModel.loadTextEntriesWithTimestamps(habit.name, selectedDate) { todayEntries ->
            val cur = getState()
            if (cur?.habit?.name == habit.name) {
                setState(cur.copy(todayEntries = todayEntries))
            }
            if (showOpts) {
                viewModel.loadTextOptions(habit.name) { opts ->
                    val c2 = getState()
                    if (c2?.habit?.name == habit.name) {
                        setState(c2.copy(options = opts))
                    }
                }
            }
        }
    }

    if (isMovieLinked) {
        // The dialog opens instantly; the suggestion is resolved from the
        // phone-local movie cache (no network wait) and topped up by a
        // background bridge refresh. While it resolves, the dialog shows a
        // small loading indicator.
        showDialog(
            suggestionLoading = true,
            recentMovies = viewModel.currentMovieCache().take(5)
        )
        viewModel.streamMovieSuggestion(habit.name, selectedDate) { sugg ->
            val cur = getState()
            if (cur?.habit?.name == habit.name) {
                setState(
                    cur.copy(
                        suggestedText = sugg.movie?.title ?: "",
                        suggestionLabel = sugg.movie?.let { movie ->
                            buildString {
                                append("🎬 Suggested from desktop")
                                if (movie.lastWatched.isNotBlank()) {
                                    append(" — watched ${movie.lastWatched.take(10)}")
                                }
                            }
                        } ?: "",
                        // The file duration (from ffprobe) goes into the
                        // separate, wheel-editable Length field.
                        suggestedMinutes = sugg.movie?.totalWatchMin?.takeIf { it > 0 },
                        suggestionLoading = sugg.loading,
                        recentMovies = sugg.recent
                    )
                )
            }
        }
    } else {
        showDialog()
    }
}

/**
 * Edit-mode "Timestamps" button: loads the day's timestamps (+ minutes for
 * minutes-primary habits) into the editor state and refreshes the text-entry
 * list so the cards can show the text logged at each increment time.
 * Extracted from the EditModeControlBar call in HabitGridScreen to keep that
 * lambda under the JVM 64KB method-size limit.
 */
internal fun showTimestampsForHabit(
    name: String,
    viewModel: HabitViewModel,
    selectedDate: java.time.LocalDate,
    settings: com.example.tail.data.AppSettings,
    scope: kotlinx.coroutines.CoroutineScope,
    setList: (List<String>) -> Unit,
    setMinutes: (Map<String, Int>) -> Unit,
    setHabitName: (String?) -> Unit,
    setTextEntries: (List<Pair<String, String>>) -> Unit
) {
    scope.launch {
        setList(viewModel.timestampRepo.getTimestampsForDay(name, selectedDate))
        setMinutes(
            if (viewModel.isMinutesPrimaryHabit(name)) {
                viewModel.timestampRepo.getMinutesForDay(name, selectedDate)
            } else emptyMap()
        )
        setHabitName(name)
    }
    // Refresh text entries so the timestamp cards can show
    // the text logged at each increment time.
    if (name in settings.textInputHabits) {
        viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
            // Discard out-of-order loads so a stale result never shows
            // another habit's log.
            setHabitName(name)
            setTextEntries(entries)
        }
    }
}

/**
 * Edit-mode "edit text entry" handler with roll-forward support. Extracted
 * from the EditModeControlBar call (method-size limit).
 */
internal fun editModeEditEntry(
    name: String,
    timestamp: String,
    newText: String,
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings,
    selectedDate: java.time.LocalDate,
    setTextEntries: (List<Pair<String, String>>) -> Unit,
    onShowRollForward: (RollForwardDialogState) -> Unit
) {
    // Check if this is a roll forward habit and we're viewing a past date
    if (name in settings.rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
        // Parse the date from the timestamp
        val dateStr = timestamp.substring(0, 10)
        val entryDate = com.example.tail.data.parseDate(dateStr)
        if (entryDate != null) {
            // Find the next manual date
            val nextManualDate = settings.rollForwardManualDates[name]?.mapNotNull { dateStr ->
                com.example.tail.data.parseDate(dateStr)
            }?.sorted()?.firstOrNull { it > entryDate }
            val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()
            // Show roll forward confirmation dialog
            onShowRollForward(
                RollForwardDialogState(
                    habitName = name,
                    actionType = "text",
                    startDate = entryDate,
                    initialEndDate = endDate,
                    onConfirm = { confirmedEndDate ->
                        viewModel.updateTextEntryWithRollForward(name, timestamp, newText, confirmedEndDate) {
                            // Reload entries after edit
                            viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                setTextEntries(entries)
                            }
                        }
                    }
                )
            )
            return
        }
    }
    // Normal update without roll forward
    viewModel.updateTextEntry(name, timestamp, newText)
    // Reload entries after edit
    viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
        setTextEntries(entries)
    }
}

/**
 * Edit-mode "add text entry" handler with roll-forward support. Extracted
 * from the EditModeControlBar call (method-size limit).
 */
internal fun editModeAddEntry(
    name: String,
    newText: String,
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings,
    selectedDate: java.time.LocalDate,
    setTextEntries: (List<Pair<String, String>>) -> Unit,
    onShowRollForward: (RollForwardDialogState) -> Unit
) {
    // Check if this is a roll forward habit and we're viewing a past date
    if (name in settings.rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
        // Find the next manual date
        val nextManualDate = settings.rollForwardManualDates[name]?.mapNotNull { dateStr ->
            com.example.tail.data.parseDate(dateStr)
        }?.sorted()?.firstOrNull { it > selectedDate }
        val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()
        // Show roll forward confirmation dialog
        onShowRollForward(
            RollForwardDialogState(
                habitName = name,
                actionType = "text",
                startDate = selectedDate,
                initialEndDate = endDate,
                onConfirm = { confirmedEndDate ->
                    viewModel.setTextEntryForDateWithRollForward(name, selectedDate, newText, confirmedEndDate) {
                        // Reload entries after add
                        viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                            setTextEntries(entries)
                        }
                    }
                }
            )
        )
        return
    }
    // Normal add without roll forward
    viewModel.setTextEntryForDate(name, selectedDate, newText) {
        // Reload entries after add
        viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
            setTextEntries(entries)
        }
    }
}

/**
 * Handles the plain-increment tap branch (camera capture / roll-forward /
 * normal increment + toast + lizard shimmer). Extracted from HabitGridScreen's
 * onHabitClick lambda so that composable stays under the JVM 64KB method-size
 * limit. Behaviour unchanged.
 */
internal fun handlePlainIncrementTap(
    viewModel: HabitViewModel,
    habit: Habit,
    context: android.content.Context,
    isToday: Boolean,
    selectedDate: java.time.LocalDate,
    settings: com.example.tail.data.AppSettings,
    lizardShimmerGen: androidx.compose.runtime.MutableIntState,
    toastScope: kotlinx.coroutines.CoroutineScope,
    onShowRollForward: (RollForwardDialogState) -> Unit,
    onShowIncrementToast: (String, Boolean) -> Unit
) {
    // Camera-enabled habit tapped for TODAY → capture-driven increment: the
    // camera opens IMMEDIATELY and this tap performs NO direct increment —
    // the background vision pipeline creates the meal log and performs the
    // increment (merging into an active meal group when one exists, so
    // multiple courses never double-count).
    if (isToday && habit.name in settings.cameraHabits) {
        val cameraIntent = android.content.Intent(
            context,
            com.example.tail.QuickCaptureActivity::class.java
        ).apply {
            putExtra(com.example.tail.QuickCaptureActivity.EXTRA_HABIT_NAME, habit.name)
        }
        context.startActivity(cameraIntent)
    }
    // Check if this is a roll forward habit and we're viewing a past date
    else if (habit.name in settings.rollForwardHabits && !isToday) {
        // Find the next manual date
        val nextManualDate = settings.rollForwardManualDates[habit.name]?.mapNotNull { dateStr ->
            com.example.tail.data.parseDate(dateStr)
        }?.sorted()?.firstOrNull { it > selectedDate }

        val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

        // Show roll forward confirmation dialog
        onShowRollForward(
            RollForwardDialogState(
                habitName = habit.name,
                actionType = "increment",
                startDate = selectedDate,
                initialEndDate = endDate,
                onConfirm = { confirmedEndDate ->
                    viewModel.incrementHabitWithRollForward(
                        habitName = habit.name,
                        amount = 1,
                        recordTimestamp = isToday,
                        customEndDate = confirmedEndDate
                    )
                    // Popup-gated increment: the roll-forward confirmation was
                    // submitted — now the lizard shimmer may fire.
                    lizardShimmerGen.intValue++
                    onShowIncrementToast(habit.name, !isToday)
                }
            )
        )
    } else {
        // Normal increment without roll forward — always record a timestamp
        // when incrementing for today.
        viewModel.incrementHabit(habit.name, 1, recordTimestamp = isToday)
        // EXPERIMENT: a click-increment is the ONLY trigger for the lizard
        // shimmer cycle.
        lizardShimmerGen.intValue++
        // Manually incrementing the linked Puzzle Rush habit = back-filling a
        // rush run the timer missed: open the same report overlay the bubble
        // uses, in manual mode (extra minutes input).
        val rushHabit = com.example.tail.widget.ChessReadinessStore
            .linkedRushHabit(context).trim()
        if (rushHabit.isNotEmpty() && habit.name == rushHabit) {
            try {
                com.example.tail.widget.ChessPuzzleRushOverlay(
                    context, manual = true
                ).show()
            } catch (_: Exception) { /* overlay best-effort */ }
        }
        onShowIncrementToast(habit.name, !isToday)
    }
}

/**
 * Host wrapper for the text-input dialog and its roll-forward confirm chain.
 *
 * Extracted verbatim from [HabitGridScreen] so that composable stays under the
 * JVM 64KB method-size limit (adding the sleep-suite dialog pushed the
 * lambda over it). Behaviour is unchanged — see the dialog call site in
 * HabitGridScreen's git history for the original inline form.
 */
@Composable
internal fun TextInputDialogHost(
    state: TextInputDialogState,
    isToday: Boolean,
    selectedDate: LocalDate,
    today: LocalDate,
    loadingMetrics: LoadingMetrics?,
    settings: com.example.tail.data.AppSettings,
    viewModel: HabitViewModel,
    lizardShimmerGen: androidx.compose.runtime.MutableIntState,
    toastScope: kotlinx.coroutines.CoroutineScope,
    onDismiss: () -> Unit,
    onUpdateState: (TextInputDialogState) -> Unit,
    onShowRollForward: (RollForwardDialogState) -> Unit,
    onShowIncrementToast: (String, Boolean) -> Unit
) {
    // Default time: current time for today, noon for past dates
    val initHour = if (isToday) java.time.LocalTime.now().hour else 12
    val initMinute = if (isToday) java.time.LocalTime.now().minute else 0

    TextInputDialog(
        habitName = state.habit.name,
        showOptions = state.showOptions,
        options = state.options,
        todayEntries = state.todayEntries,
        initialHour = initHour,
        initialMinute = initMinute,
        initialText = state.suggestedText,
        suggestionLabel = state.suggestionLabel,
        suggestedMinutes = state.suggestedMinutes,
        recentMovies = state.recentMovies,
        suggestionLoading = state.suggestionLoading,
        loadingMetrics = loadingMetrics,
        onConfirm = { entries, hour, minute ->
            val entryTime = java.time.LocalTime.of(hour, minute)
            // Only pass selectedDate if it's not today - for today, use current date
            val dateForEntry = if (selectedDate == today) null else selectedDate

            // Check if this is a roll forward habit and we're viewing a past date
            if (state.habit.name in settings.rollForwardHabits && dateForEntry != null) {
                // Find the next manual date
                val nextManualDate = settings.rollForwardManualDates[state.habit.name]?.mapNotNull { dateStr ->
                    com.example.tail.data.parseDate(dateStr)
                }?.sorted()?.firstOrNull { it > dateForEntry }

                val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

                // Show roll forward confirmation dialog
                onShowRollForward(
                    RollForwardDialogState(
                        habitName = state.habit.name,
                        actionType = "text",
                        startDate = dateForEntry,
                        initialEndDate = endDate,
                        onConfirm = { confirmedEndDate ->
                            viewModel.setTextEntriesForDateWithRollForward(state.habit.name, dateForEntry, entries, confirmedEndDate, entryTime) {
                                // Reload entries after add completes, then dismiss dialog
                                viewModel.loadTextEntriesWithTimestamps(state.habit.name, selectedDate) { _ ->
                                    // Don't reopen the dialog - just dismiss it
                                    onDismiss()
                                    // Popup-gated increment confirmed: fire the
                                    // lizard shimmer now that every dialog in the
                                    // chain (text input + roll-forward) is settled.
                                    lizardShimmerGen.intValue++
                                    // Show increment toast with edit-time option
                                    onShowIncrementToast(state.habit.name, !isToday)
                                }
                            }
                        }
                    )
                )
            } else {
                viewModel.saveTextEntries(state.habit.name, entries, dateForEntry, entryTime)
                onDismiss()
                // Popup-gated increment: the lizard shimmer fires only
                // now, on text-input dialog submission.
                lizardShimmerGen.intValue++
                // Show increment toast with edit-time option
                onShowIncrementToast(state.habit.name, !isToday)
            }
        },
        onDismiss = onDismiss,
        onEdit = { oldTimestamp, newText ->
            // Check if this is a roll forward habit and we're viewing a past date
            if (state.habit.name in settings.rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
                // Parse the date from the timestamp
                val dateStr = oldTimestamp.substring(0, 10)
                val entryDate = com.example.tail.data.parseDate(dateStr)

                if (entryDate != null) {
                    // Find the next manual date
                    val nextManualDate = settings.rollForwardManualDates[state.habit.name]?.mapNotNull { dateStr ->
                        com.example.tail.data.parseDate(dateStr)
                    }?.sorted()?.firstOrNull { it > entryDate }

                    val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

                    // Show roll forward confirmation dialog
                    onShowRollForward(
                        RollForwardDialogState(
                            habitName = state.habit.name,
                            actionType = "text",
                            startDate = entryDate,
                            initialEndDate = endDate,
                            onConfirm = { confirmedEndDate ->
                                viewModel.updateTextEntryWithRollForward(state.habit.name, oldTimestamp, newText, confirmedEndDate) {
                                    // Reload entries after edit completes, then dismiss dialog
                                    viewModel.loadTextEntriesWithTimestamps(state.habit.name, selectedDate) { entries ->
                                        // Don't reopen the dialog - just dismiss it
                                        onDismiss()
                                    }
                                }
                            }
                        )
                    )
                    return@TextInputDialog
                }
            }

            // Normal update without roll forward
            viewModel.updateTextEntry(state.habit.name, oldTimestamp, newText) {
                // Reload entries after edit completes
                viewModel.loadTextEntriesWithTimestamps(state.habit.name, selectedDate) { entries ->
                    onUpdateState(state.copy(todayEntries = entries))
                }
            }
        },
        onDelete = { timestamp ->
            viewModel.deleteTextEntry(state.habit.name, timestamp) {
                // Reload entries after delete completes
                viewModel.loadTextEntriesWithTimestamps(state.habit.name, selectedDate) { entries ->
                    onUpdateState(state.copy(todayEntries = entries))
                }
            }
        }
    )
}
