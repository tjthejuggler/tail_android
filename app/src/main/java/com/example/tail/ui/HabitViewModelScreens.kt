package com.example.tail.ui


// Split out of HabitViewModel.kt (2026-08-29) to keep individual
// Kotlin source files small enough for IR lowering on this machine.

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tail.data.backup.BackupManager
import com.example.tail.data.backup.BackupResult
import com.example.tail.data.backup.HabitRestorePreview
import com.example.tail.data.AiIcon
import com.example.tail.data.AiIconGeneratorService
import com.example.tail.data.AiIconRepository
import com.example.tail.data.AppSettings
import com.example.tail.data.ChessComRepository
import com.example.tail.data.BridgeMovie
import com.example.tail.data.ChessComType
import com.example.tail.data.GarminRepository
import com.example.tail.data.GarminType
import com.example.tail.data.GitHubApiException
import com.example.tail.data.GitHubMetric
import com.example.tail.data.GitHubRateLimitException
import com.example.tail.data.GitHubRepository
import com.example.tail.data.ImportResult
import com.example.tail.data.MovieBridgeService
import com.example.tail.data.MovieCacheStore
import com.example.tail.data.HabitNotification
import com.example.tail.data.NotificationStore
import com.example.tail.data.DatedEntryRepository
import com.example.tail.data.DayStats
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.LocationRepository
import com.example.tail.data.SecondaryLocation
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.SubtypeTimedMigrator
import com.example.tail.data.TimedDataRepository
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.HabitSearchResult
import com.example.tail.data.HabitSearcher
import com.example.tail.data.SearchableHabitInfo
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.APP_LINK_PREFIX
import com.example.tail.data.appLinkKey
import com.example.tail.data.appLinkPackageName
import com.example.tail.data.appPackageNameOf
import com.example.tail.data.isAppLink
import com.example.tail.data.isInternalValueKey
import com.example.tail.data.isSecondaryValueKey
import com.example.tail.data.minutesKey
import com.example.tail.data.secondaryValueKey
import com.example.tail.data.secondaryValue2Key
import com.example.tail.data.secondaryValueSlotKey
import com.example.tail.data.conditionalCappedFeedAmount
import com.example.tail.data.conditionalLinkStorageKey
import com.example.tail.data.conditionalSyncFeedAmount
import com.example.tail.data.positiveSyncDayDeltas
import com.example.tail.data.effectiveConditionalLinkValueKey
import com.example.tail.data.effectiveMinutesEnabled
import com.example.tail.data.minutesHabitName
import com.example.tail.data.DailyStatsMap
import com.example.tail.data.GRAPH_METRIC_POINTS
import com.example.tail.data.GRAPH_METRIC_VALUE1
import com.example.tail.data.GRAPH_METRIC_VALUE2
import com.example.tail.data.GRAPH_METRIC_VALUE3
import com.example.tail.data.GRAPH_METRIC_MINUTES
import com.example.tail.data.GRAPH_METRIC_CALORIES
import com.example.tail.data.GRAPH_METRIC_PROTEIN
import com.example.tail.data.GRAPH_METRIC_CARBS
import com.example.tail.data.GRAPH_METRIC_FAT
import com.example.tail.data.GRAPH_METRIC_IMDB
import com.example.tail.data.GRAPH_METRIC_RUNTIME
import com.example.tail.data.GRAPH_METRIC_GITHUB_LINES
import com.example.tail.data.GRAPH_METRIC_GITHUB_COMMITS
import com.example.tail.data.GRAPH_METRIC_GITHUB_ADDITIONS
import com.example.tail.data.GRAPH_METRIC_GITHUB_DELETIONS
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME_CATCH
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_TIME_DROP
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES_CATCH
import com.example.tail.data.GRAPH_METRIC_JUGCOACH_CATCHES_DROP
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_FREE_WEIGHT
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_MACHINE_REPS
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_FREE_REPS
import com.example.tail.data.gramsToDisplayTenths
import com.example.tail.data.GraphMetricOption
import com.example.tail.data.OmdbService
import com.example.tail.data.WeightsDayValues
import com.example.tail.data.OmdbOutcome
import com.example.tail.data.ImdbRatingCache
import com.example.tail.data.ParsedTitle
import com.example.tail.data.HabitsRepository
import com.example.tail.data.BridgeClient
import com.example.tail.data.SettingsRepository
import com.example.tail.data.SearchStateStore
import com.example.tail.data.PcEventQueueProcessor
import com.example.tail.data.bridgeConnectionFrom
import com.example.tail.data.TextInputRepository
import com.example.tail.data.applyDivider
import com.example.tail.widget.ChessDeferredGameReconciler
import com.example.tail.widget.ChessReadinessLogStore
import com.example.tail.widget.HabitListWidgetProvider
import com.example.tail.data.dateString
import com.example.tail.data.expandEntriesToCalendarDaysPublic
import com.example.tail.data.parseDate
import com.example.tail.data.HABIT_ORDER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import com.example.tail.wallpaper.WallpaperMetric
import com.example.tail.wallpaper.WallpaperTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val TAG = "HabitVM"

/** Toggles edit (tap-to-select reorder) mode on/off. Clears selection when turning off. */
fun HabitViewModel.toggleEditMode() {
    val turningOn = !_editMode.value
    _editMode.value = turningOn
    if (!turningOn) {
        _selectedEditIndex.value = -1
        _movePendingSourceIndex.value = -1
    } else {
        // Deactivate graph and schedule modes when edit mode is activated
        _graphMode.value = false
        _scheduleMode.value = false
        // Carry the graph-mode selection into edit mode: the first selected
        // habit (in grid order) becomes the selected cell.
        _selectedEditIndex.value = _habits.value.indexOfFirst {
            it.name.isNotEmpty() && it.name in _graphSelectedHabits.value
        }
    }
}

/** Selects (or deselects) a cell by grid index in edit mode (works for habits and placeholders). */


/** Selects (or deselects) a cell by grid index in edit mode (works for habits and placeholders). */
fun HabitViewModel.selectEditHabit(index: Int) {
    // If we are in move-pending mode, this tap is the destination — perform the move
    if (_movePendingSourceIndex.value >= 0) {
        val fromIdx = _movePendingSourceIndex.value
        _movePendingSourceIndex.value = -1
        if (index != fromIdx) {
            viewModelScope.launch { applyMove(fromIdx, index) }
        }
        // After move, keep the destination selected so the user can see where it landed
        _selectedEditIndex.value = index
        return
    }

    val prev = _selectedEditIndex.value
    val next = if (prev == index) -1 else index
    Log.d(TAG, "selectEditHabit: index=$index prev=$prev -> next=$next")
    _selectedEditIndex.value = next
}

/**
 * Enters "move-pending" mode for the currently selected habit.
 * The next tap on any grid cell will move the habit there.
 * Calling again while already pending cancels move mode.
 */


/**
 * Enters "move-pending" mode for the currently selected habit.
 * The next tap on any grid cell will move the habit there.
 * Calling again while already pending cancels move mode.
 */
fun HabitViewModel.startMoveMode() {
    val idx = _selectedEditIndex.value
    if (idx < 0) return
    if (_movePendingSourceIndex.value >= 0) {
        // Already in move mode — cancel it
        _movePendingSourceIndex.value = -1
    } else {
        _movePendingSourceIndex.value = idx
    }
}

/**
 * Begins an edit-mode long-press drag of the cell at [index]. Cancels any
 * tap-to-move pending state (the drag supersedes it) but deliberately does
 * NOT select the cell — the edit drawer must only open on a single tap,
 * never on a long-press drag. The drag itself is tracked in the UI layer;
 * only the final drop is committed back to the ViewModel.
 */


/**
 * Begins an edit-mode long-press drag of the cell at [index]. Cancels any
 * tap-to-move pending state (the drag supersedes it) but deliberately does
 * NOT select the cell — the edit drawer must only open on a single tap,
 * never on a long-press drag. The drag itself is tracked in the UI layer;
 * only the final drop is committed back to the ViewModel.
 */
fun HabitViewModel.beginHabitDrag(index: Int) {
    if (index < 0) return
    _movePendingSourceIndex.value = -1
}

/**
 * Commits a same-screen drag-and-drop move from [fromIdx] to [toIdx].
 * Public wrapper around [applyMove] for the gesture-driven reorder flow.
 */


/**
 * Commits a same-screen drag-and-drop move from [fromIdx] to [toIdx].
 * Public wrapper around [applyMove] for the gesture-driven reorder flow.
 */
fun HabitViewModel.commitHabitMove(fromIdx: Int, toIdx: Int) {
    if (fromIdx == toIdx) return
    viewModelScope.launch { applyMove(fromIdx, toIdx) }
}

/**
 * Commits a cross-screen drag-and-drop move: [habitName] is removed from
 * whichever screen currently holds it (leaving a placeholder behind, like
 * [moveHabitToScreen]) and inserted into [targetScreenIndex] at
 * [targetCellIndex] with the same shift-right semantics as [applyMove].
 */


/**
 * Commits a cross-screen drag-and-drop move: [habitName] is removed from
 * whichever screen currently holds it (leaving a placeholder behind, like
 * [moveHabitToScreen]) and inserted into [targetScreenIndex] at
 * [targetCellIndex] with the same shift-right semantics as [applyMove].
 */
fun HabitViewModel.commitCrossScreenDrag(habitName: String, targetScreenIndex: Int, targetCellIndex: Int) {
    if (habitName.isEmpty()) return
    val screens = _habitScreens.value.toMutableList()
    if (screens.isEmpty() || targetScreenIndex !in screens.indices) return

    // Remove from the source screen — a placeholder stays behind so the
    // source grid layout doesn't shift.
    for (i in screens.indices) {
        val idx = screens[i].habitNames.indexOf(habitName)
        if (idx >= 0) {
            val names = screens[i].habitNames.toMutableList()
            names[idx] = ""
            screens[i] = screens[i].copy(habitNames = names)
            break
        }
    }

    // Insert into the target screen at the requested cell, shifting
    // displaced habits right until an empty slot is found.
    val target = screens[targetScreenIndex]
    val names = target.habitNames.toMutableList()
    while (names.size <= targetCellIndex) names.add("")
    if (names[targetCellIndex].isEmpty()) {
        names[targetCellIndex] = habitName
    } else {
        var emptySlot = -1
        for (i in targetCellIndex until names.size) {
            if (names[i].isEmpty()) {
                emptySlot = i
                break
            }
        }
        if (emptySlot < 0) {
            names.add("")
            emptySlot = names.size - 1
        }
        for (i in emptySlot downTo targetCellIndex + 1) names[i] = names[i - 1]
        names[targetCellIndex] = habitName
    }
    screens[targetScreenIndex] = target.copy(habitNames = names)

    _habitScreens.value = screens
    _activeScreenIndex.value = targetScreenIndex
    viewModelScope.launch {
        rebuildHabitList()
        persistScreens(screens, targetScreenIndex)
    }
}

/**
 * Moves the habit at [fromIdx] to [toIdx].
 *
 * - If [toIdx] is a placeholder (empty string or beyond list end): simple swap/place.
 * - If [toIdx] is occupied by another habit: shift that habit and all subsequent
 *   habits one position to the right until an empty slot (or end of list) is found.
 *
 * After the move the selection lands on [toIdx].
 */


/**
 * Moves the habit at [fromIdx] to [toIdx].
 *
 * - If [toIdx] is a placeholder (empty string or beyond list end): simple swap/place.
 * - If [toIdx] is occupied by another habit: shift that habit and all subsequent
 *   habits one position to the right until an empty slot (or end of list) is found.
 *
 * After the move the selection lands on [toIdx].
 */
internal suspend fun HabitViewModel.applyMove(fromIdx: Int, toIdx: Int) {
    if (fromIdx == toIdx) return

    val screens = _habitScreens.value
    if (screens.isNotEmpty()) {
        val screenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
        val screen = screens[screenIdx]
        val current = screen.habitNames.toMutableList()
        if (fromIdx !in current.indices) return

        // Pad list with empty strings up to toIdx if needed
        while (current.size <= toIdx) current.add("")

        val habitToMove = current[fromIdx]
        current[fromIdx] = ""  // vacate source

        if (current[toIdx].isEmpty()) {
            // Target is empty — just place it there
            current[toIdx] = habitToMove
        } else {
            // Target is occupied — shift habits right until we find an empty slot
            // Find the first empty slot at or after toIdx
            var emptySlot = -1
            for (i in toIdx until current.size) {
                if (current[i].isEmpty()) {
                    emptySlot = i
                    break
                }
            }
            if (emptySlot < 0) {
                // No empty slot found — append one at the end
                current.add("")
                emptySlot = current.size - 1
            }
            // Shift everything from toIdx..emptySlot-1 one step right
            for (i in emptySlot downTo toIdx + 1) {
                current[i] = current[i - 1]
            }
            current[toIdx] = habitToMove
        }

        val updatedScreen = screen.copy(habitNames = current)
        val updatedScreens = screens.toMutableList().also { it[screenIdx] = updatedScreen }
        _habitScreens.value = updatedScreens
        _selectedEditIndex.value = toIdx
        rebuildHabitList()
        persistScreens(updatedScreens)
    } else {
        val current = _habitOrder.value.toMutableList()
        if (fromIdx !in current.indices) return

        // Pad list with empty strings up to toIdx if needed
        while (current.size <= toIdx) current.add("")

        val habitToMove = current[fromIdx]
        current[fromIdx] = ""  // vacate source

        if (current[toIdx].isEmpty()) {
            current[toIdx] = habitToMove
        } else {
            var emptySlot = -1
            for (i in toIdx until current.size) {
                if (current[i].isEmpty()) {
                    emptySlot = i
                    break
                }
            }
            if (emptySlot < 0) {
                current.add("")
                emptySlot = current.size - 1
            }
            for (i in emptySlot downTo toIdx + 1) {
                current[i] = current[i - 1]
            }
            current[toIdx] = habitToMove
        }

        _habitOrder.value = current
        _selectedEditIndex.value = toIdx
        rebuildHabitList()
        isSavingOrder = true
        viewModelScope.launch {
            try {
                settingsRepo.saveHabitOrder(current)
                _settings.value = _settings.value.copy(habitOrder = current)
            } finally {
                isSavingOrder = false
            }
        }
    }
}

// ── Screen management ────────────────────────────────────────────────────

/**
 * Switches to the screen at [index]. Rebuilds the habit list for that screen.
 * Persists the active screen index.
 */


/**
 * Switches to the screen at [index]. Rebuilds the habit list for that screen.
 * Persists the active screen index.
 */
fun HabitViewModel.switchScreen(index: Int) {
    val screens = _habitScreens.value
    if (screens.isEmpty() || index !in screens.indices) return
    _activeScreenIndex.value = index
    _selectedEditIndex.value = -1
    // Use cached habit list for instant screen switch if available
    val cached = screenHabitCache[Pair(index, _selectedDate.value)]
    if (cached != null) {
        _habits.value = cached
    }
    // Always rebuild in background to refresh stats (streaks, etc.)
    viewModelScope.launch {
        isSavingScreenIndex = true
        try {
            rebuildHabitList()
            settingsRepo.saveActiveScreenIndex(index)
        } finally {
            isSavingScreenIndex = false
        }
    }
}

/**
 * Adds a new empty screen with the given [name] (edit mode only).
 * The new screen becomes the active screen.
 */


/**
 * Adds a new empty screen with the given [name] (edit mode only).
 * The new screen becomes the active screen.
 */
fun HabitViewModel.addScreen(name: String) {
    val current = _habitScreens.value.toMutableList()
    // If no screens exist yet, migrate the current flat order into a "general" screen first
    if (current.isEmpty()) {
        val generalHabits = if (_habitOrder.value.isNotEmpty()) _habitOrder.value else HABIT_ORDER
        current.add(HabitScreen(id = UUID.randomUUID().toString(), name = "general", habitNames = generalHabits))
    }
    val newScreen = HabitScreen(
        id = UUID.randomUUID().toString(),
        name = name,
        habitNames = emptyList()
    )
    current.add(newScreen)
    _habitScreens.value = current
    val newIndex = current.size - 1
    _activeScreenIndex.value = newIndex
    _selectedEditIndex.value = -1
    viewModelScope.launch { rebuildHabitList() }
    persistScreens(current, newIndex)
}

/**
 * Deletes the screen at [screenIndex].
 * Habits on the deleted screen are moved to the first remaining screen (index 0 after deletion).
 * Cannot delete if only one screen remains.
 * If all screens are deleted, reverts to flat (no-screens) mode.
 */


/**
 * Deletes the screen at [screenIndex].
 * Habits on the deleted screen are moved to the first remaining screen (index 0 after deletion).
 * Cannot delete if only one screen remains.
 * If all screens are deleted, reverts to flat (no-screens) mode.
 */
fun HabitViewModel.deleteScreen(screenIndex: Int) {
    val screens = _habitScreens.value.toMutableList()
    if (screens.size <= 1) return  // can't delete the last screen
    if (screenIndex !in screens.indices) return

    // Move orphaned habits to screen 0 (before removal, so index math is stable)
    val orphans = screens[screenIndex].habitNames
    val targetIdx = if (screenIndex == 0) 1 else 0
    screens[targetIdx] = screens[targetIdx].copy(
        habitNames = screens[targetIdx].habitNames + orphans
    )

    screens.removeAt(screenIndex)

    // Clamp active index
    val newActive = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
    _habitScreens.value = screens
    _activeScreenIndex.value = newActive
    _selectedEditIndex.value = -1
    viewModelScope.launch { rebuildHabitList() }
    persistScreens(screens, newActive)
}

/**
 * Renames the screen at [screenIndex] to [newName].
 */


/**
 * Renames the screen at [screenIndex] to [newName].
 */
fun HabitViewModel.renameScreen(screenIndex: Int, newName: String) {
    val screens = _habitScreens.value.toMutableList()
    if (screenIndex !in screens.indices) return
    val trimmed = newName.trim()
    if (trimmed.isEmpty()) return
    screens[screenIndex] = screens[screenIndex].copy(name = trimmed)
    _habitScreens.value = screens
    persistScreens(screens)
}

/**
 * Toggles the "hidden" flag for the screen at [screenIndex].
 * A hidden screen's name is not shown in the tab bar when it is not active.
 */


/**
 * Toggles the "hidden" flag for the screen at [screenIndex].
 * A hidden screen's name is not shown in the tab bar when it is not active.
 */
fun HabitViewModel.toggleScreenHidden(screenIndex: Int) {
    val screens = _habitScreens.value
    if (screenIndex !in screens.indices) return
    val screenId = screens[screenIndex].id
    val current = _settings.value.hiddenScreens.toMutableSet()
    if (screenId in current) current.remove(screenId) else current.add(screenId)
    _settings.value = _settings.value.copy(hiddenScreens = current)
    viewModelScope.launch { settingsRepo.saveHiddenScreens(current) }
}

/**
 * Moves the screen at [fromIndex] to [toIndex] in the screen list.
 * Used for reordering screens in the tab bar during edit mode.
 */


/**
 * Moves the screen at [fromIndex] to [toIndex] in the screen list.
 * Used for reordering screens in the tab bar during edit mode.
 */
fun HabitViewModel.reorderScreen(fromIndex: Int, toIndex: Int) {
    val screens = _habitScreens.value.toMutableList()
    if (fromIndex !in screens.indices || toIndex !in screens.indices) return
    if (fromIndex == toIndex) return
    val screen = screens.removeAt(fromIndex)
    screens.add(toIndex, screen)
    _habitScreens.value = screens
    // Keep the active screen pointing at the same screen after reorder
    val currentActive = _activeScreenIndex.value
    val newActive = when (currentActive) {
        fromIndex -> toIndex
        in (minOf(fromIndex, toIndex)..maxOf(fromIndex, toIndex)) -> {
            if (fromIndex < toIndex) currentActive - 1 else currentActive + 1
        }
        else -> currentActive
    }
    _activeScreenIndex.value = newActive
    persistScreens(screens, newActive)
}

/**
 * Toggles the "disabled" flag for [habitName].
 * A disabled habit shows a red ✕ overlay and is excluded from stats aggregates.
 */


/**
 * Moves the currently selected habit to [targetScreenIndex].
 * Removes it from its current screen and appends it to the target screen.
 * Clears the selection after moving.
 */
fun HabitViewModel.moveHabitToScreen(targetScreenIndex: Int) {
    val idx = _selectedEditIndex.value
    if (idx < 0) return
    val screens = _habitScreens.value.toMutableList()
    if (screens.isEmpty() || targetScreenIndex !in screens.indices) return

    val currentScreenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
    if (targetScreenIndex == currentScreenIdx) return

    val currentScreen = screens[currentScreenIdx]
    val habitNames = currentScreen.habitNames.toMutableList()
    if (idx !in habitNames.indices) return
    val habitName = habitNames[idx]
    if (habitName.isEmpty()) return  // can't move a placeholder

    // Leave an empty-string placeholder at the moved habit's position so the
    // grid layout doesn't shift — other habits stay in their cells.
    habitNames[idx] = ""
    screens[currentScreenIdx] = currentScreen.copy(habitNames = habitNames)

    val targetScreen = screens[targetScreenIndex]
    screens[targetScreenIndex] = targetScreen.copy(habitNames = targetScreen.habitNames + habitName)

    _habitScreens.value = screens
    _selectedEditIndex.value = -1
    viewModelScope.launch { rebuildHabitList() }
    persistScreens(screens)
}

/**
 * Adds a new habit with [habitName] at grid position [atIndex] within the active screen
 * (or flat order if no screens). [atIndex] is the cell index in the full TOTAL_CELLS grid.
 *
 * If [atIndex] points to an existing empty-string placeholder in the list, the placeholder
 * is *replaced* in-place (no shifting). Otherwise the habit is inserted at [atIndex]
 * (or appended if beyond the list end).
 * Also writes the new habit to all configured JSON files (phone DB, historical DB, totals DB).
 */


internal fun HabitViewModel.persistScreens(screens: List<HabitScreen>, activeIndex: Int = _activeScreenIndex.value) {
    isSavingOrder = true
    viewModelScope.launch {
        try {
            settingsRepo.saveHabitScreens(screens)
            settingsRepo.saveActiveScreenIndex(activeIndex)
            _settings.value = _settings.value.copy(
                habitScreens = screens,
                activeScreenIndex = activeIndex
            )
            // Write relay file so the PC widget stays in sync
            val relayUri = _settings.value.screensRelayFileUri
            if (relayUri.isNotEmpty()) {
                writeScreensRelayFile(screens, activeIndex, relayUri)
            }
        } finally {
            isSavingOrder = false
        }
    }
}

/**
 * Writes the current screen layout to the screens_layout.json relay file.
 *
 * Format:
 * {
 *   "version": 1,
 *   "active_screen_index": 0,
 *   "screens": [
 *     { "id": "...", "name": "general", "habits": ["Habit A", "", "Habit B", ...] },
 *     ...
 *   ]
 * }
 *
 * Empty strings in the habits list represent placeholder/empty grid cells.
 * The PC widget reads this file to mirror the same multi-screen layout.
 */


/**
 * Writes the current screen layout to the screens_layout.json relay file.
 *
 * Format:
 * {
 *   "version": 1,
 *   "active_screen_index": 0,
 *   "screens": [
 *     { "id": "...", "name": "general", "habits": ["Habit A", "", "Habit B", ...] },
 *     ...
 *   ]
 * }
 *
 * Empty strings in the habits list represent placeholder/empty grid cells.
 * The PC widget reads this file to mirror the same multi-screen layout.
 */
internal suspend fun HabitViewModel.writeScreensRelayFile(
    screens: List<HabitScreen>,
    activeIndex: Int,
    relayUriString: String
) = withContext(Dispatchers.IO) {
    try {
        val root = JSONObject()
        root.put("version", 1)
        root.put("active_screen_index", activeIndex)

        // Include custom icon overrides so the PC widget uses the same icons
        val iconsObj = JSONObject()
        for ((habitName, iconName) in _settings.value.habitIcons) {
            iconsObj.put(habitName, iconName)
        }
        root.put("habit_icons", iconsObj)

        val screensArray = JSONArray()
        for (screen in screens) {
            val screenObj = JSONObject()
            screenObj.put("id", screen.id)
            screenObj.put("name", screen.name)
            val habitsArray = JSONArray()
            for (habitName in screen.habitNames) {
                habitsArray.put(habitName)
            }
            screenObj.put("habits", habitsArray)
            screensArray.put(screenObj)
        }
        root.put("screens", screensArray)
        val json = root.toString(2)  // pretty-print with 2-space indent

        val uri = Uri.parse(relayUriString)
        context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.bufferedWriter().use { it.write(json) }
        }
        Log.d(TAG, "Wrote screens relay file: ${screens.size} screens, ${_settings.value.habitIcons.size} icon overrides")
    } catch (e: Exception) {
        Log.w(TAG, "Failed to write screens relay file: ${e.message}")
    }
}

/**
 * Pushes the PC-widget habit config to the Tail Bridge so the desktop
 * floating bubble knows which habits to show as timer squares. Uses the
 * bridge connection auto-derived from the Garmin proxy settings (same
 * as the movie bridge — no extra setup); silently skips when the
 * bridge isn't configured and retries on the next change/startup.
 *
 * Payload:
 * {
 *   "version": 1,
 *   "updated_at": "<iso-8601>",
 *   "habits": [
 *     { "name": "Meditation", "icon": "lotus", "minutes_primary": true,
 *       "divider": 30, "inverted_binary": false, "no_points": false },
 *     ...
 *   ]
 * }
 *
 * "divider" is the habit's points divisor (habitDividers) — the PC
 * widget needs it to compute effective points (raw ÷ divider) for its
 * square colours; "inverted_binary" / "no_points" mirror the phone's
 * scoring subtypes so the PC scores those habits identically.
 *
 * "all_habits" is the phone's FULL habit catalog — the PC settings
 * screen lists it in its habit picker (toggles queue back as
 * kind="toggle_pc_widget_habit" events on the bridge).
 */


/**
 * Pushes the PC-widget habit config to the Tail Bridge so the desktop
 * floating bubble knows which habits to show as timer squares. Uses the
 * bridge connection auto-derived from the Garmin proxy settings (same
 * as the movie bridge — no extra setup); silently skips when the
 * bridge isn't configured and retries on the next change/startup.
 *
 * Payload:
 * {
 *   "version": 1,
 *   "updated_at": "<iso-8601>",
 *   "habits": [
 *     { "name": "Meditation", "icon": "lotus", "minutes_primary": true,
 *       "divider": 30, "inverted_binary": false, "no_points": false },
 *     ...
 *   ]
 * }
 *
 * "divider" is the habit's points divisor (habitDividers) — the PC
 * widget needs it to compute effective points (raw ÷ divider) for its
 * square colours; "inverted_binary" / "no_points" mirror the phone's
 * scoring subtypes so the PC scores those habits identically.
 *
 * "all_habits" is the phone's FULL habit catalog — the PC settings
 * screen lists it in its habit picker (toggles queue back as
 * kind="toggle_pc_widget_habit" events on the bridge).
 */
internal suspend fun HabitViewModel.pushPcWidgetConfig() {
    val s = _settings.value
    val bridge = bridgeConnectionFrom(s.garminProxyUrl, s.garminAppToken) ?: return
    try {
        val root = JSONObject()
        root.put("version", 1)
        root.put("updated_at", java.time.Instant.now().toString())
        val habitsArray = JSONArray()
        for (habitName in s.pcWidgetHabits) {
            val habitObj = JSONObject()
            habitObj.put("name", habitName)
            s.habitIcons[habitName]?.let { habitObj.put("icon", it) }
            habitObj.put("minutes_primary", habitName in s.widgetTimerMinutesPrimary)
            habitObj.put("divider", s.habitDividers[habitName] ?: 1)
            habitObj.put("inverted_binary", habitName in s.invertedBinaryHabits)
            habitObj.put("no_points", habitName in s.noPointsHabits)
            habitsArray.put(habitObj)
        }
        root.put("habits", habitsArray)
        // full catalog — the PC settings screen's habit-picker source
        val allHabits = JSONArray()
        getAllHabitNames().forEach { allHabits.put(it) }
        root.put("all_habits", allHabits)
        // capability flag: the PC history dialog only queues
        // session_edit/session_delete corrections once it sees this
        // list (older phones that never push it keep the widget in
        // local-only edit mode)
        root.put("event_kinds", JSONArray(
            listOf("session", "tap", "toggle_pc_widget_habit",
                   "session_edit", "session_delete")))

        val resp = BridgeClient().post(bridge.first, bridge.second, "pc_widget/config", root)
        if (resp != null) {
            Log.d(TAG, "Pushed PC widget config: ${s.pcWidgetHabits.size} habits")
        } else {
            Log.w(TAG, "PC widget config push failed (bridge unreachable?)")
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to push PC widget config: ${e.message}")
    }
}

// ── Text input feature ────────────────────────────────────────────────────

/**
 * Toggles the "text input" feature on/off for [habitName].
 * When turned off, also removes the habit from the options and sharable sets
 * (both sub-features require text input to be on).
 */


/**
 * Adds a new habit with [habitName] at grid position [atIndex] within the active screen
 * (or flat order if no screens). [atIndex] is the cell index in the full TOTAL_CELLS grid.
 *
 * If [atIndex] points to an existing empty-string placeholder in the list, the placeholder
 * is *replaced* in-place (no shifting). Otherwise the habit is inserted at [atIndex]
 * (or appended if beyond the list end).
 * Also writes the new habit to all configured JSON files (phone DB, historical DB, totals DB).
 */
fun HabitViewModel.addHabit(habitName: String, atIndex: Int) {
    val trimmed = habitName.trim()
    if (trimmed.isEmpty()) return

    val screens = _habitScreens.value
    if (screens.isNotEmpty()) {
        val screenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
        val screen = screens[screenIdx]
        val current = screen.habitNames.toMutableList()
        val insertAt: Int
        if (atIndex in current.indices && current[atIndex].isEmpty()) {
            // Replace the embedded placeholder in-place — no shifting
            current[atIndex] = trimmed
            insertAt = atIndex
        } else {
            insertAt = atIndex.coerceIn(0, current.size)
            current.add(insertAt, trimmed)
        }
        val updatedScreen = screen.copy(habitNames = current)
        val updatedScreens = screens.toMutableList().also { it[screenIdx] = updatedScreen }
        _habitScreens.value = updatedScreens
        _selectedEditIndex.value = insertAt
        viewModelScope.launch { rebuildHabitList() }
        persistScreens(updatedScreens)
    } else {
        val current = _habitOrder.value.toMutableList()
        val insertAt: Int
        if (atIndex in current.indices && current[atIndex].isEmpty()) {
            // Replace the embedded placeholder in-place — no shifting
            current[atIndex] = trimmed
            insertAt = atIndex
        } else {
            insertAt = atIndex.coerceIn(0, current.size)
            current.add(insertAt, trimmed)
        }
        _habitOrder.value = current
        _selectedEditIndex.value = insertAt
        isSavingOrder = true
        viewModelScope.launch {
            rebuildHabitList()
            try {
                settingsRepo.saveHabitOrder(current)
                _settings.value = _settings.value.copy(habitOrder = current)
            } finally {
                isSavingOrder = false
            }
        }
    }

    // Write the new habit to the unified DB file
    viewModelScope.launch {
        val s = _settings.value
        if (s.fileUri.isNotEmpty()) {
            try {
                habitsRepo.addHabitToFiles(listOf(android.net.Uri.parse(s.fileUri)), context, trimmed)
                // Reload DB so the new habit shows up with today's entry
                val db = habitsRepo.ensureDaysExist(android.net.Uri.parse(s.fileUri), context)
                cachedPhoneDb = db
                rebuildHabitList()
            } catch (e: Exception) {
                _errorMessage.value = "Added habit but failed to write to file: ${e.message}"
            }
        }
    }
}

/**
 * Adds an app-link entry at grid position [atIndex] within the active screen.
 * Unlike [addHabit], this does NOT write to the habits database — app links
 * are pure launchers, not incrementable habits.
 * The entry is stored in the screen's habitNames with the [APP_LINK_PREFIX]
 * and its display label is saved to [AppSettings.appLinks].
 */


/**
 * Adds an app-link entry at grid position [atIndex] within the active screen.
 * Unlike [addHabit], this does NOT write to the habits database — app links
 * are pure launchers, not incrementable habits.
 * The entry is stored in the screen's habitNames with the [APP_LINK_PREFIX]
 * and its display label is saved to [AppSettings.appLinks].
 */
fun HabitViewModel.addAppLink(packageName: String, label: String, atIndex: Int) {
    val key = appLinkKey(packageName)
    val screens = _habitScreens.value
    if (screens.isNotEmpty()) {
        val screenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
        val screen = screens[screenIdx]
        val current = screen.habitNames.toMutableList()
        val insertAt: Int
        if (atIndex in current.indices && current[atIndex].isEmpty()) {
            current[atIndex] = key
            insertAt = atIndex
        } else {
            insertAt = atIndex.coerceIn(0, current.size)
            current.add(insertAt, key)
        }
        val updatedScreen = screen.copy(habitNames = current)
        val updatedScreens = screens.toMutableList().also { it[screenIdx] = updatedScreen }
        _habitScreens.value = updatedScreens
        _selectedEditIndex.value = insertAt
        viewModelScope.launch { rebuildHabitList() }
        persistScreens(updatedScreens)
    } else {
        val current = _habitOrder.value.toMutableList()
        val insertAt: Int
        if (atIndex in current.indices && current[atIndex].isEmpty()) {
            current[atIndex] = key
            insertAt = atIndex
        } else {
            insertAt = atIndex.coerceIn(0, current.size)
            current.add(insertAt, key)
        }
        _habitOrder.value = current
        _selectedEditIndex.value = insertAt
        isSavingOrder = true
        viewModelScope.launch {
            rebuildHabitList()
            try {
                settingsRepo.saveHabitOrder(current)
                _settings.value = _settings.value.copy(habitOrder = current)
            } finally {
                isSavingOrder = false
            }
        }
    }
    // Save the app link label to settings (not to the habits DB)
    viewModelScope.launch {
        val updated = _settings.value.appLinks.toMutableMap()
        updated[key] = label
        settingsRepo.saveAppLinks(updated)
        _settings.value = _settings.value.copy(appLinks = updated)
    }
}

/**
 * Deletes an app-link entry at [index] from the active screen.
 * Also removes it from [AppSettings.appLinks].
 */


/**
 * Deletes an app-link entry at [index] from the active screen.
 * Also removes it from [AppSettings.appLinks].
 */
fun HabitViewModel.deleteAppLink(index: Int) {
    val screens = _habitScreens.value
    val keyToRemove: String?
    if (screens.isNotEmpty()) {
        val screenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
        val screen = screens[screenIdx]
        val current = screen.habitNames.toMutableList()
        if (index !in current.indices) return
        keyToRemove = current[index]
        if (keyToRemove.isEmpty() || !isAppLink(keyToRemove)) return
        current[index] = ""
        val updatedScreen = screen.copy(habitNames = current)
        val updatedScreens = screens.toMutableList().also { it[screenIdx] = updatedScreen }
        _habitScreens.value = updatedScreens
        _selectedEditIndex.value = -1
        viewModelScope.launch { rebuildHabitList() }
        persistScreens(updatedScreens)
    } else {
        val current = _habitOrder.value.toMutableList()
        if (index !in current.indices) return
        keyToRemove = current[index]
        if (keyToRemove.isEmpty() || !isAppLink(keyToRemove)) return
        current[index] = ""
        _habitOrder.value = current
        _selectedEditIndex.value = -1
        isSavingOrder = true
        viewModelScope.launch {
            rebuildHabitList()
            try {
                settingsRepo.saveHabitOrder(current)
                _settings.value = _settings.value.copy(habitOrder = current)
            } finally {
                isSavingOrder = false
            }
        }
    }
    // Remove from appLinks settings
    keyToRemove?.let { key ->
        viewModelScope.launch {
            val updated = _settings.value.appLinks.toMutableMap()
            updated.remove(key)
            settingsRepo.saveAppLinks(updated)
            _settings.value = _settings.value.copy(appLinks = updated)
        }
    }
}

// ── Habit App Association methods ──────────────────────────────────────
/**
 * Associates an app ([packageName]) with [habitName].
 * The app is appended to the end of the ordered list (or inserted at [insertAt]
 * if specified). If the app is already associated, this is a no-op.
 */


// ── Habit App Association methods ──────────────────────────────────────
/**
 * Associates an app ([packageName]) with [habitName].
 * The app is appended to the end of the ordered list (or inserted at [insertAt]
 * if specified). If the app is already associated, this is a no-op.
 */
fun HabitViewModel.addHabitAppAssociation(habitName: String, packageName: String, insertAt: Int = -1) {
    viewModelScope.launch {
        val associations = _settings.value.habitAppAssociations.toMutableMap()
        val current = associations[habitName]?.toMutableList() ?: mutableListOf()
        if (packageName !in current) {
            if (insertAt in current.indices) {
                current.add(insertAt, packageName)
            } else {
                current.add(packageName)
            }
            associations[habitName] = current
            settingsRepo.saveHabitAppAssociations(associations)
            _settings.value = _settings.value.copy(habitAppAssociations = associations)
        }
    }
}

/**
 * Removes an app association from [habitName].
 * If this was the last association, the habit name key is removed entirely.
 */


/**
 * Removes an app association from [habitName].
 * If this was the last association, the habit name key is removed entirely.
 */
fun HabitViewModel.removeHabitAppAssociation(habitName: String, packageName: String) {
    viewModelScope.launch {
        val associations = _settings.value.habitAppAssociations.toMutableMap()
        val current = associations[habitName]?.toMutableList() ?: return@launch
        current.remove(packageName)
        if (current.isEmpty()) {
            associations.remove(habitName)
        } else {
            associations[habitName] = current
        }
        settingsRepo.saveHabitAppAssociations(associations)
        _settings.value = _settings.value.copy(habitAppAssociations = associations)
    }
}

/**
 * Moves an associated app within [habitName]'s ordered list from [fromIndex]
 * to [toIndex]. Used for reordering via up/down arrows.
 */


/**
 * Moves an associated app within [habitName]'s ordered list from [fromIndex]
 * to [toIndex]. Used for reordering via up/down arrows.
 */
fun HabitViewModel.moveHabitAppAssociation(habitName: String, fromIndex: Int, toIndex: Int) {
    viewModelScope.launch {
        val associations = _settings.value.habitAppAssociations.toMutableMap()
        val current = associations[habitName]?.toMutableList() ?: return@launch
        if (fromIndex !in current.indices || toIndex !in current.indices) return@launch
        val item = current.removeAt(fromIndex)
        current.add(toIndex, item)
        associations[habitName] = current
        settingsRepo.saveHabitAppAssociations(associations)
        _settings.value = _settings.value.copy(habitAppAssociations = associations)
    }
}

/**
 * Deletes all app associations for [habitName].
 * Called when a habit is deleted to clean up orphaned settings.
 */


/**
 * Deletes all app associations for [habitName].
 * Called when a habit is deleted to clean up orphaned settings.
 */
fun HabitViewModel.clearHabitAppAssociations(habitName: String) {
    viewModelScope.launch {
        val associations = _settings.value.habitAppAssociations.toMutableMap()
        if (associations.remove(habitName) != null) {
            settingsRepo.saveHabitAppAssociations(associations)
            _settings.value = _settings.value.copy(habitAppAssociations = associations)
        }
    }
}

// ── Widget Trigger methods ────────────────────────────────────────────

/**
 * Toggles the "Use Widget" feature for [habitName].
 * When enabling, the habit is added to [AppSettings.widgetTriggerHabits].
 * When disabling, both the habit and its trigger app are removed.
 */


/**
 * Toggles the "Use Widget" feature for [habitName].
 * When enabling, the habit is added to [AppSettings.widgetTriggerHabits].
 * When disabling, both the habit and its trigger app are removed.
 */
fun HabitViewModel.toggleWidgetTrigger(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.widgetTriggerHabits
        val newHabits: Set<String>
        val newApps: Map<String, String>
        // Disabling the widget feature also drops the persistent-timer
        // sub-option — it is meaningless without a trigger app.
        var newPersistent = _settings.value.widgetPersistentTimerHabits

        if (habitName in current) {
            // Disabling — remove from both sets
            newHabits = current - habitName
            newApps = _settings.value.widgetTriggerApps - habitName
            newPersistent = newPersistent - habitName
        } else {
            // Enabling — add to habits set
            newHabits = current + habitName
            newApps = _settings.value.widgetTriggerApps
        }

        settingsRepo.saveWidgetTriggerHabits(newHabits)
        settingsRepo.saveWidgetTriggerApps(newApps)
        if (newPersistent != _settings.value.widgetPersistentTimerHabits) {
            settingsRepo.saveWidgetPersistentTimerHabits(newPersistent)
        }
        // Connecting a habit to the phone bubble timer forces minutes ON
        // — the bubble timer feeds the habit's `minutes:` slot.
        var minutes = _settings.value.minutesEnabledHabits
        if (habitName in newHabits && habitName !in current &&
            habitName !in minutes && habitName !in _settings.value.maxOneHabits
        ) {
            minutes = minutes + habitName
            settingsRepo.saveMinutesEnabledHabits(minutes)
        }
        _settings.value = _settings.value.copy(
            widgetTriggerHabits = newHabits,
            widgetTriggerApps = newApps,
            widgetPersistentTimerHabits = newPersistent,
            minutesEnabledHabits = minutes
        )

        updateWidgetTriggerService()
    }
}

/**
 * Toggles the "Persistent Timer" sub-option of the Use Widget feature for
 * [habitName]. When enabled, the habit's bubble timer keeps running after the
 * trigger app leaves the foreground (instead of being auto-stopped and
 * recorded) and the bubble reappears — with the live elapsed time — over the
 * trigger app and over the Tail app itself until the user stops the timer.
 */
fun HabitViewModel.toggleWidgetPersistentTimer(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.widgetPersistentTimerHabits
        val newPersistent = if (habitName in current) current - habitName else current + habitName
        settingsRepo.saveWidgetPersistentTimerHabits(newPersistent)
        _settings.value = _settings.value.copy(widgetPersistentTimerHabits = newPersistent)
        // The running monitor caches the persistent-habit set — it MUST be
        // refreshed or the auto-stop keeps using the stale (old) decision.
        updateWidgetTriggerService()
    }
}

/**
 * Sets the trigger app [packageName] for [habitName].
 * The habit should already be in [AppSettings.widgetTriggerHabits].
 */


/**
 * Sets the trigger app [packageName] for [habitName].
 * The habit should already be in [AppSettings.widgetTriggerHabits].
 */
fun HabitViewModel.setWidgetTriggerApp(habitName: String, packageName: String) {
    viewModelScope.launch {
        val apps = _settings.value.widgetTriggerApps.toMutableMap()
        apps[habitName] = packageName
        settingsRepo.saveWidgetTriggerApps(apps)
        // Configuring the trigger app connects the bubble timer, which
        // feeds the habit's `minutes:` slot — minutes turn ON.
        var minutes = _settings.value.minutesEnabledHabits
        if (packageName.isNotBlank() && habitName !in minutes &&
            habitName !in _settings.value.maxOneHabits
        ) {
            minutes = minutes + habitName
            settingsRepo.saveMinutesEnabledHabits(minutes)
        }
        _settings.value = _settings.value.copy(
            widgetTriggerApps = apps,
            minutesEnabledHabits = minutes
        )

        updateWidgetTriggerService()
    }
}

/**
 * Sets which value is PRIMARY for a habit (any habit, not just timer ones):
 *  - [minutesPrimary] = true  → minutes drive points/display; sessions are
 *    the fallback used only on days with zero minutes.
 *  - [minutesPrimary] = false → sessions primary; minutes fallback.
 */


/**
 * Sets which value is PRIMARY for a habit (any habit, not just timer ones):
 *  - [minutesPrimary] = true  → minutes drive points/display; sessions are
 *    the fallback used only on days with zero minutes.
 *  - [minutesPrimary] = false → sessions primary; minutes fallback.
 */
fun HabitViewModel.setWidgetTimerPrimaryValue(habitName: String, minutesPrimary: Boolean) {
    viewModelScope.launch {
        val current = _settings.value.widgetTimerMinutesPrimary
        val updated = if (minutesPrimary) current + habitName else current - habitName
        settingsRepo.saveWidgetTimerMinutesPrimary(updated)
        // Making minutes the primary value requires minutes to exist:
        // the minutes toggle turns ON together with this (also covers
        // the graph screen's long-press "set as minutes value" action).
        var minutes = _settings.value.minutesEnabledHabits
        if (minutesPrimary && habitName !in minutes &&
            habitName !in _settings.value.maxOneHabits
        ) {
            minutes = minutes + habitName
            settingsRepo.saveMinutesEnabledHabits(minutes)
        }
        _settings.value = _settings.value.copy(
            widgetTimerMinutesPrimary = updated,
            minutesEnabledHabits = minutes
        )
        rebuildHabitList()
        // The PC widget config carries the minutes-primary flag — refresh it
        // when a PC-widget habit's primary value changes.
        if (habitName in _settings.value.pcWidgetHabits) {
            pushPcWidgetConfig()
        }
    }
}

/**
 * Transitions a legacy single-value habit to minutes-primary, carrying the
 * historical data over. Such habits had a single generic count that was
 * minutes all along, so choosing "Minutes value" in the graph's long-press
 * chooser COPIES that history from the value-1 slot into the habit's
 * minutes slot — the value-1 slot is ALWAYS kept (deleting it blanks the
 * graph for every metric; see the Aug-23-2026 repair) — and makes minutes
 * the primary value (the minutes toggle turns on with it).
 *
 * The data copy only happens while the minutes slot is empty — a habit
 * with real (timer-fed) minutes data keeps it and only the primary flag
 * flips. The habit's graph metric selection swaps value1 → minutes so the
 * carried-over history is visible immediately.
 *
 * Garmin-linked habits are pure view semantics: their Value1 series comes
 * from the Garmin cache (the JSON key only holds the derived per-day
 * points), so there is no history to carry over and no flags to flip —
 * duration-typed Garmin series (e.g. Sleep Length) already render with
 * the h:mm duration axis in the graph.
 */


/**
 * Transitions a legacy single-value habit to minutes-primary, carrying the
 * historical data over. Such habits had a single generic count that was
 * minutes all along, so choosing "Minutes value" in the graph's long-press
 * chooser COPIES that history from the value-1 slot into the habit's
 * minutes slot — the value-1 slot is ALWAYS kept (deleting it blanks the
 * graph for every metric; see the Aug-23-2026 repair) — and makes minutes
 * the primary value (the minutes toggle turns on with it).
 *
 * The data copy only happens while the minutes slot is empty — a habit
 * with real (timer-fed) minutes data keeps it and only the primary flag
 * flips. The habit's graph metric selection swaps value1 → minutes so the
 * carried-over history is visible immediately.
 *
 * Garmin-linked habits are pure view semantics: their Value1 series comes
 * from the Garmin cache (the JSON key only holds the derived per-day
 * points), so there is no history to carry over and no flags to flip —
 * duration-typed Garmin series (e.g. Sleep Length) already render with
 * the h:mm duration axis in the graph.
 */
fun HabitViewModel.migrateValue1ToMinutesPrimary(habitName: String) {
    val uriStr = _settings.value.fileUri
    if (uriStr.isEmpty()) {
        Log.w(TAG, "migrateValue1ToMinutesPrimary: no habits file configured — flag only")
        setWidgetTimerPrimaryValue(habitName, true)
        return
    }
    viewModelScope.launch {
        // Garmin-linked habits: nothing to migrate — the live Garmin
        // series already IS the value, and flipping the minutes-primary
        // flag would only redirect points at an empty minutes slot.
        if (_settings.value.garminHabitLinks.containsKey(habitName)) {
            Log.i(
                TAG,
                "migrateValue1ToMinutesPrimary: '$habitName' is Garmin-linked — " +
                    "value already treated as minutes, no data migration"
            )
            return@launch
        }
        val minKey = minutesKey(habitName)
        val existingMinutes = cachedPhoneDb[minKey]
        val minutesEmpty = existingMinutes == null || existingMinutes.values.all { it == 0 }
        val value1Data = cachedPhoneDb[habitName]
        var copiedToMinutes = false
        if (minutesEmpty && !value1Data.isNullOrEmpty() && dbLoaded) {
            val mutableDb = cachedPhoneDb.toMutableMap()
            // COPY, never move: the value-1 history stays in place and
            // the minutes slot gets a duplicate. The primary key must
            // survive — deleting it blanks the graph for EVERY metric.
            mutableDb[minKey] = value1Data
            cachedPhoneDb = mutableDb
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(uriStr), context, mutableDb)
            }
            HabitsDataChangedBus.emit()
            copiedToMinutes = true
            Log.i(
                TAG,
                "migrateValue1ToMinutesPrimary: copied ${value1Data.size} days " +
                    "of value-1 data to minutes for '$habitName' (value-1 kept)"
            )
        }
        // Swap the graph selection value1 → minutes only when the minutes
        // slot now holds the carried-over history.
        if (copiedToMinutes) {
            val selection = _settings.value.graphMetricSelection.toMutableMap()
            val currentSet = getSelectedMetrics(habitName).toMutableSet()
            val hadValue1 = currentSet.remove(GRAPH_METRIC_VALUE1)
            val addedMinutes = currentSet.add(GRAPH_METRIC_MINUTES)
            if (hadValue1 || addedMinutes) {
                selection[habitName] = currentSet
                settingsRepo.saveGraphMetricSelection(selection)
                _settings.value = _settings.value.copy(graphMetricSelection = selection)
            }
        }
        // Minutes becomes the primary value (enables the minutes toggle).
        setWidgetTimerPrimaryValue(habitName, true)
    }
}

// ── Media habit methods ───────────────────────────────────────────────

/**
 * Enables or disables the "Media" type for [habitName].
 *
 * Disabling removes the habit's media app configuration; any listening
 * block in flight is flushed (recorded) by the tracker on its next tick.
 * The underlying minutes data and the widget-trigger settings are left
 * untouched.
 */


/**
 * Deletes the habit at [index] from the active screen (or flat order).
 * JSON data is preserved by default — the delete dialog offers
 * [deleteHabitData] as an explicit opt-in purge. Clears the selection
 * after deletion.
 */
fun HabitViewModel.deleteHabit(index: Int) {
    val screens = _habitScreens.value
    if (screens.isNotEmpty()) {
        val screenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
        val screen = screens[screenIdx]
        val current = screen.habitNames.toMutableList()
        if (index !in current.indices) return
        // Empty-string entries are already placeholders — nothing to do.
        if (current[index].isEmpty()) return
        // Delegate to deleteAppLink for app-link entries
        if (isAppLink(current[index])) { deleteAppLink(index); return }
        val deletedName = current[index]
        // Replace with empty placeholder so grid positions of other habits stay fixed
        current[index] = ""
        val updatedScreen = screen.copy(habitNames = current)
        val updatedScreens = screens.toMutableList().also { it[screenIdx] = updatedScreen }
        _habitScreens.value = updatedScreens
        _selectedEditIndex.value = -1
        viewModelScope.launch {
            rebuildHabitList()
            // Deleted habits must no longer feed into (or be fed by) anything.
            removeConditionalReferences(deletedName)
        }
        persistScreens(updatedScreens)
    } else {
        val current = _habitOrder.value.toMutableList()
        if (index !in current.indices) return
        if (current[index].isEmpty()) return
        // Delegate to deleteAppLink for app-link entries
        if (isAppLink(current[index])) { deleteAppLink(index); return }
        val deletedName = current[index]
        // Replace with empty placeholder so grid positions of other habits stay fixed
        current[index] = ""
        _habitOrder.value = current
        _selectedEditIndex.value = -1
        isSavingOrder = true
        viewModelScope.launch {
            rebuildHabitList()
            try {
                settingsRepo.saveHabitOrder(current)
                _settings.value = _settings.value.copy(habitOrder = current)
            } finally {
                isSavingOrder = false
            }
            // Deleted habits must no longer feed into (or be fed by) anything.
            removeConditionalReferences(deletedName)
        }
    }
}

/**
 * Strips a deleted habit from all conditional-link settings so it no longer
 * feeds into (or is fed by) any other habit: removes it as a conditional
 * source, as a linked target of other sources, and from the feed-value and
 * feed-max-one overrides. No-op when the habit had no conditional presence.
 */


/**
 * Strips a deleted habit from all conditional-link settings so it no longer
 * feeds into (or is fed by) any other habit: removes it as a conditional
 * source, as a linked target of other sources, and from the feed-value and
 * feed-max-one overrides. No-op when the habit had no conditional presence.
 */
internal fun HabitViewModel.removeConditionalReferences(deletedName: String) {
    val s = _settings.value
    val newLinked = s.conditionalLinkedHabits.mapNotNull { (src, targets) ->
        if (src == deletedName) null
        else {
            val kept = targets - deletedName
            if (kept.isEmpty()) null else src to kept
        }
    }.toMap()
    val newValues = s.conditionalLinkValues.mapNotNull { (src, inner) ->
        if (src == deletedName) null
        else {
            val kept = inner - deletedName
            if (kept.isEmpty()) null else src to kept
        }
    }.toMap()
    val newConditional = s.conditionalHabits - deletedName
    val newFeedMaxOne = s.conditionalFeedMaxOneHabits - deletedName
    val newFeedPoints = s.conditionalFeedPointsHabits - deletedName
    if (newLinked == s.conditionalLinkedHabits &&
        newValues == s.conditionalLinkValues &&
        newConditional == s.conditionalHabits &&
        newFeedMaxOne == s.conditionalFeedMaxOneHabits &&
        newFeedPoints == s.conditionalFeedPointsHabits
    ) return
    _settings.value = s.copy(
        conditionalHabits = newConditional,
        conditionalLinkedHabits = newLinked,
        conditionalLinkValues = newValues,
        conditionalFeedMaxOneHabits = newFeedMaxOne,
        conditionalFeedPointsHabits = newFeedPoints
    )
    viewModelScope.launch {
        settingsRepo.saveConditionalHabits(newConditional)
        settingsRepo.saveConditionalLinkedHabits(newLinked)
        settingsRepo.saveConditionalLinkValues(newValues)
        settingsRepo.saveConditionalFeedMaxOneHabits(newFeedMaxOne)
        settingsRepo.saveConditionalFeedPointsHabits(newFeedPoints)
    }
}

/**
 * Returns how many distinct days of stored data [habitName] has in the
 * habits JSON — the union of dates across the primary count and all
 * secondary-value slots. 0 when the habit has no data at all. Used by
 * the delete dialog to show the user exactly what is at stake.
 */


/**
 * Returns how many distinct days of stored data [habitName] has in the
 * habits JSON — the union of dates across the primary count and all
 * secondary-value slots. 0 when the habit has no data at all. Used by
 * the delete dialog to show the user exactly what is at stake.
 */
fun HabitViewModel.getDeleteDataDayCount(habitName: String): Int {
    val dates = mutableSetOf<String>()
    dates.addAll(cachedPhoneDb[habitName]?.keys ?: emptySet())
    dates.addAll(cachedPhoneDb[secondaryValueKey(habitName)]?.keys ?: emptySet())
    dates.addAll(cachedPhoneDb[minutesKey(habitName)]?.keys ?: emptySet())
    for (slot in 2..6) {
        dates.addAll(cachedPhoneDb[secondaryValueSlotKey(habitName, slot)]?.keys ?: emptySet())
    }
    return dates.size
}

/**
 * PERMANENTLY removes [habitName]'s data from the habits JSON: the
 * primary count and every secondary-value slot. Invoked only when the
 * user ticks "also delete data" in the delete dialog — the default
 * delete keeps historical data.
 *
 * Guarded by the same anti-wipe gate as the other DB writers: refuses
 * to persist when the database has not finished loading.
 */


/**
 * PERMANENTLY removes [habitName]'s data from the habits JSON: the
 * primary count and every secondary-value slot. Invoked only when the
 * user ticks "also delete data" in the delete dialog — the default
 * delete keeps historical data.
 *
 * Guarded by the same anti-wipe gate as the other DB writers: refuses
 * to persist when the database has not finished loading.
 */
fun HabitViewModel.deleteHabitData(habitName: String) {
    val uriStr = _settings.value.fileUri
    if (uriStr.isEmpty()) {
        Log.w(TAG, "deleteHabitData: no habits file configured — nothing purged")
        return
    }
    if (!dbLoaded) {
        Log.w(TAG, "deleteHabitData: DB not loaded yet, refusing to persist (anti-wipe gate)")
        return
    }
    viewModelScope.launch {
        val keysToRemove = listOf(habitName, secondaryValueKey(habitName), minutesKey(habitName)) +
            (2..6).map { secondaryValueSlotKey(habitName, it) }
        val mutableDb = cachedPhoneDb.toMutableMap()
        var changed = false
        for (key in keysToRemove) {
            if (mutableDb.remove(key) != null) changed = true
        }
        if (!changed) {
            Log.d(TAG, "deleteHabitData: no stored data found for '$habitName'")
            return@launch
        }
        cachedPhoneDb = mutableDb
        rebuildHabitList()
        withContext(Dispatchers.IO) {
            habitsRepo.persistDatabase(Uri.parse(uriStr), context, mutableDb)
        }
        HabitsDataChangedBus.emit()
        Log.i(TAG, "deleteHabitData: purged data of '$habitName' from JSON")
    }
}

/**
 * Renames a habit from [oldName] to [newName].
 * Updates the database and all settings that reference the habit name.
 */


/**
 * Renames a habit from [oldName] to [newName].
 * Updates the database and all settings that reference the habit name.
 */
fun HabitViewModel.renameHabit(oldName: String, newName: String) {
    viewModelScope.launch {
        if (oldName == newName) return@launch
        if (newName.isBlank()) return@launch
        
        try {
            val uri = lastLoadedUri
            if (uri.isEmpty()) {
                Log.e(TAG, "renameHabit: no URI loaded")
                return@launch
            }
            
            // Rename in database. Capture the freshly-written DB and refresh the
            // in-memory cache from it. CRITICAL: cachedPhoneDb is the single source
            // of truth for rebuildHabitList(), every history/graph view, and every
            // subsequent increment/persist. If we DON'T refresh it here, the cache
            // still holds the habit's data under oldName while settings now point to
            // newName — so the renamed habit appears to lose all history, and the
            // next increment writes the stale cache back to disk, permanently
            // reverting the rename (the reported bug).
            cachedPhoneDb = habitsRepo.renameHabit(Uri.parse(uri), context, oldName, newName)
            // Drop any per-screen/per-date display caches that still reference the
            // old name; they are rebuilt lazily from the now-correct cachedPhoneDb.
            screenHabitCache.clear()
            
            // Update all settings that reference the habit name
            val settings = _settings.value
            
            // Update habitOrder
            val newHabitOrder = settings.habitOrder.map { if (it == oldName) newName else it }
            
            // Update habitScreens
            val newHabitScreens = settings.habitScreens.map { screen ->
                screen.copy(habitNames = screen.habitNames.map { if (it == oldName) newName else it })
            }
            
            // Update all maps and sets that reference habit names
            fun <K, V> Map<K, V>.replaceKey(oldKey: K, newKey: K): Map<K, V> {
                if (oldKey !in this) return this
                val mutable = this.toMutableMap()
                mutable[newKey] = mutable.remove(oldKey)!!
                return mutable
            }
            
            fun <T> Set<T>.replaceElement(oldElement: T, newElement: T): Set<T> {
                if (oldElement !in this) return this
                val mutable = this.toMutableSet()
                mutable.remove(oldElement)
                mutable.add(newElement)
                return mutable
            }
            
            fun <K> Map<K, Set<String>>.replaceInValueSets(oldKey: String, newKey: String): Map<K, Set<String>> {
                return mapValues { (_, set) ->
                    set.map { if (it == oldKey) newKey else it }.toSet()
                }
            }
            
            fun <K> Map<K, List<String>>.replaceInValueLists(oldKey: String, newKey: String): Map<K, List<String>> {
                return mapValues { (_, list) ->
                    list.map { if (it == oldKey) newKey else it }
                }
            }

            // Renames a habit both as map key AND inside the value sets — needed
            // for conditionalLinkedHabits, whose keys are conditional habit names.
            fun Map<String, Set<String>>.replaceKeysAndValues(oldName: String, newName: String): Map<String, Set<String>> {
                return mapKeys { (k, _) -> if (k == oldName) newName else k }
                    .mapValues { (_, set) -> set.map { if (it == oldName) newName else it }.toSet() }
            }

            // Renames a habit as outer key and as inner key of a nested map —
            // needed for conditionalLinkValues (source → linked → value key).
            fun Map<String, Map<String, String>>.replaceKeysAndInnerKeys(oldName: String, newName: String): Map<String, Map<String, String>> {
                return mapKeys { (k, _) -> if (k == oldName) newName else k }
                    .mapValues { (_, inner) -> inner.mapKeys { (k, _) -> if (k == oldName) newName else k } }
            }
            
            val newSettings = settings.copy(
                habitOrder = newHabitOrder,
                habitScreens = newHabitScreens,
                customInputHabits = settings.customInputHabits.replaceElement(oldName, newName),
                textInputHabits = settings.textInputHabits.replaceElement(oldName, newName),
                textInputOptionsHabits = settings.textInputOptionsHabits.replaceElement(oldName, newName),
                sharableTextHabits = settings.sharableTextHabits.replaceElement(oldName, newName),
                inuitTextHabits = settings.inuitTextHabits.replaceElement(oldName, newName),
                textInputFileUris = settings.textInputFileUris.replaceKey(oldName, newName),
                // renamedHabitIcons re-keys an existing override AND materialises the
                // hardcoded HABIT_ICON default (keyed by the original name) as an
                // explicit override under the new name — otherwise a renamed habit
                // whose icon came from the defaults loses its icon entirely.
                habitIcons = renamedHabitIcons(oldName, newName, settings.habitIcons),
                datedEntryHabits = settings.datedEntryHabits.replaceElement(oldName, newName),
                datedEntryFileUris = settings.datedEntryFileUris.replaceKey(oldName, newName),
                datedEntryFileSizes = settings.datedEntryFileSizes.replaceKey(oldName, newName),
                habitDividers = settings.habitDividers.replaceKey(oldName, newName),
                conditionalHabits = settings.conditionalHabits.replaceElement(oldName, newName),
                conditionalLinkedHabits = settings.conditionalLinkedHabits.replaceKeysAndValues(oldName, newName),
                conditionalLinkValues = settings.conditionalLinkValues.replaceKeysAndInnerKeys(oldName, newName),
                conditionalFeedMaxOneHabits = settings.conditionalFeedMaxOneHabits.replaceElement(oldName, newName),
                conditionalFeedPointsHabits = settings.conditionalFeedPointsHabits.replaceElement(oldName, newName),
                subtypedHabits = settings.subtypedHabits.replaceElement(oldName, newName),
                habitSubtypes = settings.habitSubtypes.replaceKey(oldName, newName),
                subtypeDataFileUris = settings.subtypeDataFileUris.replaceKey(oldName, newName),
                timedHabits = settings.timedHabits.replaceElement(oldName, newName),
                timedDataFileUris = settings.timedDataFileUris.replaceKey(oldName, newName),
                timelessHabits = settings.timelessHabits.replaceElement(oldName, newName),
                disabledHabits = settings.disabledHabits.replaceElement(oldName, newName),
                noPointsHabits = settings.noPointsHabits.replaceElement(oldName, newName),
                secondaryValueHabits = settings.secondaryValueHabits.replaceElement(oldName, newName),
                secondaryValueFallbackHabits = settings.secondaryValueFallbackHabits.replaceElement(oldName, newName),
                voiceTriggerHabits = settings.voiceTriggerHabits.replaceElement(oldName, newName),
                voiceTriggerWords = settings.voiceTriggerWords.replaceKey(oldName, newName),
                voiceTriggerIncrements = settings.voiceTriggerIncrements.replaceKey(oldName, newName),
                voiceSubtypeHabits = settings.voiceSubtypeHabits.replaceElement(oldName, newName),
                customInputAmounts = settings.customInputAmounts.replaceKey(oldName, newName),
                customInputRecentAmounts = settings.customInputRecentAmounts.replaceKey(oldName, newName),
                mapStatsHabits = settings.mapStatsHabits.replaceElement(oldName, newName),
                mapStatsShowTextHabits = settings.mapStatsShowTextHabits.replaceElement(oldName, newName),
                garminHabitLinks = settings.garminHabitLinks.replaceKey(oldName, newName),
                chessComHabitLinks = settings.chessComHabitLinks.replaceKey(oldName, newName),
                githubRepoUrls = settings.githubRepoUrls.replaceKey(oldName, newName),
                githubMetrics = settings.githubMetrics.replaceKey(oldName, newName),
                mediaHabits = settings.mediaHabits.replaceElement(oldName, newName),
                mediaApps = settings.mediaApps.replaceKey(oldName, newName),
                customPointRangesHabits = settings.customPointRangesHabits.replaceElement(oldName, newName),
                customPointRanges = settings.customPointRanges.replaceKey(oldName, newName),
                graphValueModeHabits = settings.graphValueModeHabits.replaceKey(oldName, newName),
                graphMetricSelection = settings.graphMetricSelection.replaceKey(oldName, newName),
                graphInterpolateZeroMetrics = settings.graphInterpolateZeroMetrics.replaceKey(oldName, newName),
                habitNotes = settings.habitNotes.replaceKey(oldName, newName),
                valueDisplayLabels = settings.valueDisplayLabels.replaceKey(oldName, newName),
                maxOneHabits = settings.maxOneHabits.replaceElement(oldName, newName),
                invertedBinaryHabits = settings.invertedBinaryHabits.replaceElement(oldName, newName),
                bridgeMovieHabits = settings.bridgeMovieHabits.replaceElement(oldName, newName),
                pcWidgetHabits = settings.pcWidgetHabits.replaceElement(oldName, newName),
                rollForwardHabits = settings.rollForwardHabits.replaceElement(oldName, newName),
                rollForwardManualDates = settings.rollForwardManualDates.replaceKey(oldName, newName),
                mealHabits = settings.mealHabits.replaceElement(oldName, newName),
                weightsHabits = settings.weightsHabits.replaceElement(oldName, newName),
                weightsRecentExercises = settings.weightsRecentExercises.replaceKey(oldName, newName),
                cameraHabits = settings.cameraHabits.replaceElement(oldName, newName),
                habitAppAssociations = settings.habitAppAssociations.replaceKey(oldName, newName),
                habitLongPressActions = settings.habitLongPressActions.replaceKey(oldName, newName),
                habitLongPressUrls = settings.habitLongPressUrls.replaceKey(oldName, newName),
                habitLongPressUrlApps = settings.habitLongPressUrlApps.replaceKey(oldName, newName),
                widgetTriggerHabits = settings.widgetTriggerHabits.replaceElement(oldName, newName),
                widgetTriggerApps = settings.widgetTriggerApps.replaceKey(oldName, newName),
                widgetTimerMinutesPrimary = settings.widgetTimerMinutesPrimary.replaceElement(oldName, newName),
                widgetPersistentTimerHabits = settings.widgetPersistentTimerHabits.replaceElement(oldName, newName),
                minutesEnabledHabits = settings.minutesEnabledHabits.replaceElement(oldName, newName),
                minutesPrimaryFallbacks = settings.minutesPrimaryFallbacks.replaceKey(oldName, newName),
                mapMainHabit = if (settings.mapMainHabit == oldName) newName else settings.mapMainHabit
            )
            
            // Save all updated settings
            settingsRepo.saveHabitOrder(newHabitOrder)
            settingsRepo.saveHabitScreens(newHabitScreens)
            settingsRepo.saveCustomInputHabits(newSettings.customInputHabits)
            settingsRepo.saveTextInputHabits(newSettings.textInputHabits)
            settingsRepo.saveTextInputOptionsHabits(newSettings.textInputOptionsHabits)
            settingsRepo.saveSharableTextHabits(newSettings.sharableTextHabits)
            settingsRepo.saveInuitTextHabits(newSettings.inuitTextHabits)
            settingsRepo.saveTextInputFileUris(newSettings.textInputFileUris)
            settingsRepo.saveHabitIcons(newSettings.habitIcons)
            settingsRepo.saveDatedEntryHabits(newSettings.datedEntryHabits)
            settingsRepo.saveDatedEntryFileUris(newSettings.datedEntryFileUris)
            settingsRepo.saveDatedEntryFileSizes(newSettings.datedEntryFileSizes)
            settingsRepo.saveHabitDividers(newSettings.habitDividers)
            settingsRepo.saveConditionalHabits(newSettings.conditionalHabits)
            settingsRepo.saveConditionalLinkedHabits(newSettings.conditionalLinkedHabits)
            settingsRepo.saveConditionalLinkValues(newSettings.conditionalLinkValues)
            settingsRepo.saveSubtypedHabits(newSettings.subtypedHabits)
            settingsRepo.saveHabitSubtypes(newSettings.habitSubtypes)
            settingsRepo.saveSubtypeDataFileUris(newSettings.subtypeDataFileUris)
            settingsRepo.saveTimedHabits(newSettings.timedHabits)
            settingsRepo.saveTimedDataFileUris(newSettings.timedDataFileUris)
            settingsRepo.saveTimelessHabits(newSettings.timelessHabits)
            settingsRepo.saveDisabledHabits(newSettings.disabledHabits)
            settingsRepo.saveNoPointsHabits(newSettings.noPointsHabits)
            settingsRepo.saveSecondaryValueHabits(newSettings.secondaryValueHabits)
            settingsRepo.saveSecondaryValueFallbackHabits(newSettings.secondaryValueFallbackHabits)
            settingsRepo.saveVoiceTriggerHabits(newSettings.voiceTriggerHabits)
            settingsRepo.saveVoiceTriggerWords(newSettings.voiceTriggerWords)
            settingsRepo.saveVoiceTriggerIncrements(newSettings.voiceTriggerIncrements)
            settingsRepo.saveVoiceSubtypeHabits(newSettings.voiceSubtypeHabits)
            settingsRepo.saveCustomInputAmounts(newSettings.customInputAmounts)
            settingsRepo.saveCustomInputRecentAmounts(newSettings.customInputRecentAmounts)
            settingsRepo.saveMapStatsHabits(newSettings.mapStatsHabits)
            settingsRepo.saveMapStatsShowTextHabits(newSettings.mapStatsShowTextHabits)
            settingsRepo.saveGarminHabitLinks(newSettings.garminHabitLinks)
            settingsRepo.saveChessComHabitLinks(newSettings.chessComHabitLinks)
            settingsRepo.saveGithubRepoUrls(newSettings.githubRepoUrls)
            settingsRepo.saveGithubMetrics(newSettings.githubMetrics)
            settingsRepo.saveMediaHabits(newSettings.mediaHabits)
            settingsRepo.saveMediaApps(newSettings.mediaApps)
            settingsRepo.saveCustomPointRangesHabits(newSettings.customPointRangesHabits)
            settingsRepo.saveCustomPointRanges(newSettings.customPointRanges)
            settingsRepo.saveGraphValueModeHabits(newSettings.graphValueModeHabits)
            settingsRepo.saveGraphMetricSelection(newSettings.graphMetricSelection)
            settingsRepo.saveGraphInterpolateZeroMetrics(newSettings.graphInterpolateZeroMetrics)
            settingsRepo.saveHabitNotes(newSettings.habitNotes)
            settingsRepo.saveValueDisplayLabels(newSettings.valueDisplayLabels)
            settingsRepo.saveMaxOneHabits(newSettings.maxOneHabits)
            settingsRepo.saveInvertedBinaryHabits(newSettings.invertedBinaryHabits)
            settingsRepo.saveBridgeMovieHabits(newSettings.bridgeMovieHabits)
            settingsRepo.savePcWidgetHabits(newSettings.pcWidgetHabits)
            settingsRepo.saveRollForwardHabits(newSettings.rollForwardHabits)
            settingsRepo.saveRollForwardManualDates(newSettings.rollForwardManualDates)
            settingsRepo.saveMealHabits(newSettings.mealHabits)
            settingsRepo.saveWeightsHabits(newSettings.weightsHabits)
            settingsRepo.saveWeightsRecentExercises(newSettings.weightsRecentExercises)
            settingsRepo.saveCameraHabits(newSettings.cameraHabits)
            settingsRepo.saveHabitAppAssociations(newSettings.habitAppAssociations)
            settingsRepo.saveHabitLongPressActions(newSettings.habitLongPressActions)
            settingsRepo.saveHabitLongPressUrls(newSettings.habitLongPressUrls)
            settingsRepo.saveHabitLongPressUrlApps(newSettings.habitLongPressUrlApps)
            settingsRepo.saveWidgetTriggerHabits(newSettings.widgetTriggerHabits)
            settingsRepo.saveWidgetTriggerApps(newSettings.widgetTriggerApps)
            settingsRepo.saveWidgetTimerMinutesPrimary(newSettings.widgetTimerMinutesPrimary)
            settingsRepo.saveWidgetPersistentTimerHabits(newSettings.widgetPersistentTimerHabits)
            settingsRepo.saveMinutesEnabledHabits(newSettings.minutesEnabledHabits)
            settingsRepo.saveMinutesPrimaryFallbacks(newSettings.minutesPrimaryFallbacks)
            settingsRepo.saveMapMainHabit(newSettings.mapMainHabit)
            
            // Rename in the internal timestamp file so historical timestamps survive
            timestampRepo.renameHabit(oldName, newName)

            // Rename in the internal subtype/timed stores so breakdowns and
            // timed sessions survive the rename too
            subtypeDataRepo.renameHabit(oldName, newName)
            timedDataRepo.renameHabit(oldName, newName)
            
            _settings.value = newSettings
            _habitOrder.value = newHabitOrder
            _habitScreens.value = newHabitScreens
            
            // Rebuild habit list with new name
            rebuildHabitList()
            
            // Sync to relay file if configured
            val relayUri = newSettings.screensRelayFileUri
            if (relayUri.isNotEmpty()) {
                writeScreensRelayFile(newHabitScreens, _activeScreenIndex.value, relayUri)
            }
            // Keep the PC floating-widget config in step with the rename
            pushPcWidgetConfig()
            
            Log.i(TAG, "renameHabit: successfully renamed '$oldName' to '$newName'")
        } catch (e: Exception) {
            Log.e(TAG, "renameHabit: failed to rename habit", e)
        }
    }
}

/**
 * Preview data for the invert operation.
 * Lets the UI warn the user about data loss before committing.
 */
data class InvertPreview(
    val totalEntries: Int,
    val zeroCount: Int,
    val oneCount: Int,
    val highValueCount: Int,
    val maxValue: Int
) {
    /** True when every value is 0 or 1 — invert is lossless. */
    val isBinaryOnly: Boolean get() = highValueCount == 0
}

/**
 * Returns statistics about a habit's stored values so the UI can show
 * a data-loss warning before inverting. Returns null if the habit has
 * no data at all.
 */


/**
 * Returns statistics about a habit's stored values so the UI can show
 * a data-loss warning before inverting. Returns null if the habit has
 * no data at all.
 */
fun HabitViewModel.getInvertPreview(habitName: String): InvertPreview? {
    val entries = cachedPhoneDb[habitName] ?: return null
    if (entries.isEmpty()) return null
    val zeroCount = entries.values.count { it == 0 }
    val oneCount = entries.values.count { it == 1 }
    val highValueCount = entries.values.count { it > 1 }
    val maxValue = entries.values.maxOrNull() ?: 0
    return InvertPreview(entries.size, zeroCount, oneCount, highValueCount, maxValue)
}

/**
 * Inverts all stored values for [habitName]: 0 → 1, any value ≥ 1 → 0.
 * The caller should check [getInvertPreview] first and warn the user
 * if values > 1 exist (they will be collapsed to 0).
 */


/**
 * Inverts all stored values for [habitName]: 0 → 1, any value ≥ 1 → 0.
 * The caller should check [getInvertPreview] first and warn the user
 * if values > 1 exist (they will be collapsed to 0).
 */
fun HabitViewModel.invertHabit(habitName: String) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _errorMessage.value = "No file selected. Please pick a file in Settings."
        return
    }
    viewModelScope.launch {
        try {
            val uri = Uri.parse(uriString)
            val updatedDb = habitsRepo.invertHabit(uri, context, habitName)
            cachedPhoneDb = updatedDb
            rebuildHabitList()
            Log.i(TAG, "invertHabit: successfully inverted '$habitName'")
        } catch (e: Exception) {
            Log.e(TAG, "invertHabit: failed to invert habit", e)
            _errorMessage.value = "Invert failed: ${e.message}"
        }
    }
}

/**
 * Sets or clears the custom icon for [habitName].
 * [iconName] is the drawable resource name without extension (e.g. "bicycle"),
 * or null to clear the override and revert to the default icon.
 */
