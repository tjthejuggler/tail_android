package com.example.tail.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tail.data.AppSettings
import com.example.tail.data.DatedEntryRepository
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsRepository
import com.example.tail.data.HistoricalTotals
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.data.dateString
import com.example.tail.data.HABIT_ORDER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

private const val TAG = "HabitVM"

/** Total cells in the 8×10 habit grid — matches TOTAL_CELLS in HabitGridScreen. */
private const val TOTAL_GRID_CELLS = 80

/**
 * Main ViewModel: owns habits list + settings state, delegates I/O to repositories.
 * Supports day navigation: selectedDate can be moved backward/forward relative to today.
 * Supports multiple named screens of habits.
 */
class HabitViewModel(
    private val habitsRepo: HabitsRepository,
    private val settingsRepo: SettingsRepository,
    private val textInputRepo: TextInputRepository,
    private val datedEntryRepo: DatedEntryRepository,
    private val context: Context
) : ViewModel() {

    private val _habits = MutableStateFlow<List<Habit>>(emptyList())
    val habits: StateFlow<List<Habit>> = _habits.asStateFlow()

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** The date currently being viewed/edited. Starts at today. */
    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /** True when selectedDate == today */
    val isToday: Boolean get() = _selectedDate.value == LocalDate.now()

    /** When true, tapping a habit button shows its info panel instead of incrementing. */
    private val _infoMode = MutableStateFlow(false)
    val infoMode: StateFlow<Boolean> = _infoMode.asStateFlow()

    /** The habit currently selected for info display (null = none). */
    private val _selectedInfoHabit = MutableStateFlow<Habit?>(null)
    val selectedInfoHabit: StateFlow<Habit?> = _selectedInfoHabit.asStateFlow()

    /** When true, the grid is in tap-to-select reorder edit mode. */
    private val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    /**
     * The current display order of habit names. Starts as HABIT_ORDER, then reflects
     * any custom ordering the user has saved.
     */
    private val _habitOrder = MutableStateFlow<List<String>>(HABIT_ORDER)
    val habitOrder: StateFlow<List<String>> = _habitOrder.asStateFlow()

    /** The index (in the current habit list) of the habit selected for reordering. -1 = none. */
    private val _selectedEditIndex = MutableStateFlow(-1)
    val selectedEditIndex: StateFlow<Int> = _selectedEditIndex.asStateFlow()

    /**
     * When >= 0, the user has tapped "Move" on the selected habit and we are waiting for
     * them to tap a destination cell. This stores the source grid index.
     * -1 = not in move-pending mode.
     */
    private val _movePendingSourceIndex = MutableStateFlow(-1)
    val movePendingSourceIndex: StateFlow<Int> = _movePendingSourceIndex.asStateFlow()

    /** The list of named habit screens. Empty = not yet initialised (use flat habitOrder). */
    private val _habitScreens = MutableStateFlow<List<HabitScreen>>(emptyList())
    val habitScreens: StateFlow<List<HabitScreen>> = _habitScreens.asStateFlow()

    /** Index of the currently displayed screen. */
    private val _activeScreenIndex = MutableStateFlow(0)
    val activeScreenIndex: StateFlow<Int> = _activeScreenIndex.asStateFlow()

    // Track the last loaded URI to avoid reloading on every settings emission
    private var lastLoadedUri: String = ""

    // Flag to suppress settingsFlow reaction while we're saving a new habit order / screens
    private var isSavingOrder: Boolean = false

    // Cache the raw phone DB so we can rebuild the habit list without re-reading the file
    private var cachedPhoneDb: HabitsDatabase = emptyMap()

    // Cache the historical database so we don't re-read it on every increment
    private var cachedHistoricalDb: HabitsDatabase = emptyMap()

    // Cache the historical totals (pre-computed stats from habitsdb_without_phone_totals.txt)
    private var cachedHistoricalTotals: HistoricalTotals = emptyMap()

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { s ->
                _settings.value = s
                if (!isSavingOrder) {
                    // Sync screens from persisted settings
                    if (s.habitScreens.isNotEmpty()) {
                        _habitScreens.value = s.habitScreens
                        val clampedIdx = s.activeScreenIndex.coerceIn(0, s.habitScreens.size - 1)
                        _activeScreenIndex.value = clampedIdx
                    } else if (s.habitOrder.isNotEmpty()) {
                        _habitOrder.value = s.habitOrder
                    }
                }
                // Only load from file on first settings emission (app start)
                if (s.fileUri.isNotEmpty() && lastLoadedUri.isEmpty()) {
                    lastLoadedUri = s.fileUri
                    if (s.historicalFileUri.isNotEmpty()) {
                        cachedHistoricalDb = habitsRepo.loadHistoricalDatabase(
                            Uri.parse(s.historicalFileUri), context
                        )
                    }
                    if (s.totalsFileUri.isNotEmpty()) {
                        cachedHistoricalTotals = habitsRepo.loadHistoricalTotals(
                            Uri.parse(s.totalsFileUri), context
                        )
                    }
                    catchUpAndLoad(Uri.parse(s.fileUri))
                    // After the phone DB is loaded, sync any dated-entry habits.
                    // We do this here (after settings are confirmed loaded) rather than
                    // in onAppForegrounded() to avoid the race where ON_RESUME fires
                    // before the DataStore settings have been read.
                    if (s.datedEntryHabits.isNotEmpty()) {
                        syncAllDatedEntries(forceReparse = false)
                    }
                }
            }
        }
    }

    /**
     * Returns the effective ordered list of habit names for the currently active screen.
     * When screens are configured, returns the active screen's habit list (may be empty).
     * Falls back to the flat habitOrder (or HABIT_ORDER) only when NO screens exist at all.
     */
    fun activeHabitOrder(): List<String> {
        val screens = _habitScreens.value
        return if (screens.isNotEmpty()) {
            // Screens are configured — use the active screen's list (even if empty)
            val idx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
            screens[idx].habitNames
        } else {
            // No screens at all — fall back to flat order
            val order = _habitOrder.value
            if (order.isNotEmpty()) order else HABIT_ORDER
        }
    }

    /**
     * Returns the screen index that contains the given habit name, or -1 if not found
     * (or if screens are not configured).
     */
    fun screenIndexForHabit(habitName: String): Int {
        val screens = _habitScreens.value
        if (screens.isEmpty()) return -1
        return screens.indexOfFirst { habitName in it.habitNames }
    }

    private suspend fun catchUpAndLoad(uri: Uri) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            val db = habitsRepo.ensureDaysExist(uri, context)
            cachedPhoneDb = db
            rebuildHabitList()
        } catch (e: Exception) {
            _errorMessage.value = "Failed to load file: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    fun loadFromFile(uri: Uri) {
        viewModelScope.launch {
            catchUpAndLoad(uri)
        }
    }

    /** Rebuilds the displayed habit list from cached data for the current selectedDate. */
    private suspend fun rebuildHabitList() {
        val effectiveOrder = activeHabitOrder()
        // If screens are configured and the active screen is empty, show nothing.
        // We must NOT fall back to HABIT_ORDER in this case.
        if (effectiveOrder.isEmpty() && _habitScreens.value.isNotEmpty()) {
            _habits.value = emptyList()
            return
        }
        val settingsWithOrder = _settings.value.copy(habitOrder = effectiveOrder)
        // Run the heavy per-habit calculations on a background CPU thread
        val newList = withContext(Dispatchers.Default) {
            habitsRepo.buildHabitList(
                db = cachedPhoneDb,
                settings = settingsWithOrder,
                historicalDb = cachedHistoricalDb,
                historicalTotals = cachedHistoricalTotals,
                targetDate = _selectedDate.value
            )
        }
        _habits.value = newList
    }

    fun setFileUri(uri: Uri) {
        viewModelScope.launch {
            val uriString = uri.toString()
            lastLoadedUri = uriString
            settingsRepo.saveFileUri(uriString)
            _selectedDate.value = LocalDate.now()
            catchUpAndLoad(uri)
        }
    }

    fun setHistoricalFileUri(uri: Uri) {
        viewModelScope.launch {
            settingsRepo.saveHistoricalFileUri(uri.toString())
            cachedHistoricalDb = habitsRepo.loadHistoricalDatabase(uri, context)
            val phoneUriString = _settings.value.fileUri
            if (phoneUriString.isNotEmpty()) {
                loadFromFile(Uri.parse(phoneUriString))
            }
        }
    }

    fun setTotalsFileUri(uri: Uri) {
        viewModelScope.launch {
            settingsRepo.saveTotalsFileUri(uri.toString())
            cachedHistoricalTotals = habitsRepo.loadHistoricalTotals(uri, context)
            rebuildHabitList()
        }
    }

    /**
     * Navigate the selected date by [deltaDays] (negative = go back, positive = go forward).
     * Cannot navigate past today.
     */
    fun navigateDay(deltaDays: Int) {
        val newDate = _selectedDate.value.plusDays(deltaDays.toLong())
        val today = LocalDate.now()
        _selectedDate.value = if (newDate.isAfter(today)) today else newDate
        viewModelScope.launch { rebuildHabitList() }
    }

    fun incrementHabit(habitName: String, amount: Int = 1) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }

        // Step 1: instant targeted update — just flip todayCount for this one habit.
        // This is O(n) list copy with zero calculations, so it's effectively instant.
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
        val newCount = (currentEntries[dateStr] ?: 0) + amount
        _habits.value = _habits.value.map { h ->
            if (h.name == habitName) h.copy(todayCount = newCount) else h
        }

        // Step 2: update in-memory cache
        val updatedDb = habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, amount, _selectedDate.value)
        cachedPhoneDb = updatedDb

        // Step 3: full rebuild (streak/ATH recalc) + disk write in background
        viewModelScope.launch {
            rebuildHabitList()
            try {
                val uri = Uri.parse(uriString)
                habitsRepo.persistDatabase(uri, context, updatedDb)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save: ${e.message}"
            }
        }
    }

    /**
     * Sets the count for [habitName] on the currently selected date to an absolute [newCount].
     * Clamps to >= 0. Persists to the DB file.
     */
    fun setHabitCount(habitName: String, newCount: Int) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }
        val clamped = newCount.coerceAtLeast(0)

        // Step 1: instant targeted UI update
        _habits.value = _habits.value.map { h ->
            if (h.name == habitName) h.copy(todayCount = clamped) else h
        }

        // Step 2: update in-memory cache — compute delta from current stored value
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
        val currentCount = currentEntries[dateStr] ?: 0
        val delta = clamped - currentCount
        val updatedDb = if (delta != 0) {
            habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, delta, _selectedDate.value)
        } else {
            cachedPhoneDb
        }
        cachedPhoneDb = updatedDb

        // Step 3: full rebuild + disk write in background
        viewModelScope.launch {
            rebuildHabitList()
            try {
                val uri = Uri.parse(uriString)
                habitsRepo.persistDatabase(uri, context, updatedDb)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save: ${e.message}"
            }
        }
    }

    fun toggleCustomInput(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.customInputHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveCustomInputHabits(current)
            _habits.value = _habits.value.map { habit ->
                habit.copy(useCustomInput = habit.name in current)
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /** Toggles info mode on/off. Clears selected habit when turning off.
     *  Acts as a radio button with edit mode — turning info on turns edit off. */
    fun toggleInfoMode() {
        val turningOn = !_infoMode.value
        _infoMode.value = turningOn
        if (!turningOn) {
            _selectedInfoHabit.value = null
        } else {
            // Deactivate edit mode when info mode is activated
            _editMode.value = false
            _selectedEditIndex.value = -1
            _movePendingSourceIndex.value = -1
        }
    }

    /** Called when a habit is tapped in info mode — selects it for display. */
    fun selectInfoHabit(habit: Habit) {
        _selectedInfoHabit.value = habit
    }

    /** Clears the currently selected info habit (e.g. when tapping elsewhere). */
    fun clearInfoHabit() {
        _selectedInfoHabit.value = null
    }

    /** Toggles edit (tap-to-select reorder) mode on/off. Clears selection when turning off.
     *  Acts as a radio button with info mode — turning edit on turns info off. */
    fun toggleEditMode() {
        val turningOn = !_editMode.value
        _editMode.value = turningOn
        if (!turningOn) {
            _selectedEditIndex.value = -1
            _movePendingSourceIndex.value = -1
        } else {
            // Deactivate info mode when edit mode is activated
            _infoMode.value = false
            _selectedInfoHabit.value = null
        }
    }

    /** Selects (or deselects) a cell by grid index in edit mode (works for habits and placeholders). */
    fun selectEditHabit(index: Int) {
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
    fun startMoveMode() {
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
     * Moves the habit at [fromIdx] to [toIdx].
     *
     * - If [toIdx] is a placeholder (empty string or beyond list end): simple swap/place.
     * - If [toIdx] is occupied by another habit: shift that habit and all subsequent
     *   habits one position to the right until an empty slot (or end of list) is found.
     *
     * After the move the selection lands on [toIdx].
     */
    private suspend fun applyMove(fromIdx: Int, toIdx: Int) {
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
    fun switchScreen(index: Int) {
        val screens = _habitScreens.value
        if (screens.isEmpty() || index !in screens.indices) return
        _activeScreenIndex.value = index
        _selectedEditIndex.value = -1
        viewModelScope.launch {
            rebuildHabitList()
            settingsRepo.saveActiveScreenIndex(index)
        }
    }

    /**
     * Adds a new empty screen with the given [name] (edit mode only).
     * The new screen becomes the active screen.
     */
    fun addScreen(name: String) {
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
    fun deleteScreen(screenIndex: Int) {
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
    fun renameScreen(screenIndex: Int, newName: String) {
        val screens = _habitScreens.value.toMutableList()
        if (screenIndex !in screens.indices) return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        screens[screenIndex] = screens[screenIndex].copy(name = trimmed)
        _habitScreens.value = screens
        persistScreens(screens)
    }

    /**
     * Moves the currently selected habit to [targetScreenIndex].
     * Removes it from its current screen and appends it to the target screen.
     * Clears the selection after moving.
     */
    fun moveHabitToScreen(targetScreenIndex: Int) {
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
    fun addHabit(habitName: String, atIndex: Int) {
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

        // Write the new habit to all configured JSON files
        viewModelScope.launch {
            val s = _settings.value
            val uris = buildList {
                if (s.fileUri.isNotEmpty()) add(android.net.Uri.parse(s.fileUri))
                if (s.historicalFileUri.isNotEmpty()) add(android.net.Uri.parse(s.historicalFileUri))
                if (s.totalsFileUri.isNotEmpty()) add(android.net.Uri.parse(s.totalsFileUri))
            }
            if (uris.isNotEmpty()) {
                try {
                    habitsRepo.addHabitToFiles(uris, context, trimmed)
                    // Reload phone DB so the new habit shows up with today's entry
                    val phoneUriString = s.fileUri
                    if (phoneUriString.isNotEmpty()) {
                        val db = habitsRepo.ensureDaysExist(android.net.Uri.parse(phoneUriString), context)
                        cachedPhoneDb = db
                        rebuildHabitList()
                    }
                } catch (e: Exception) {
                    _errorMessage.value = "Added habit but failed to write to files: ${e.message}"
                }
            }
        }
    }

    /**
     * Deletes the habit at [index] from the active screen (or flat order).
     * Does NOT remove data from JSON files — historical data is preserved.
     * Clears the selection after deletion.
     */
    fun deleteHabit(index: Int) {
        val screens = _habitScreens.value
        if (screens.isNotEmpty()) {
            val screenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
            val screen = screens[screenIdx]
            val current = screen.habitNames.toMutableList()
            if (index !in current.indices) return
            // Empty-string entries are already placeholders — just keep them as-is.
            // Only remove real habit names.
            if (current[index].isEmpty()) return
            current.removeAt(index)
            val updatedScreen = screen.copy(habitNames = current)
            val updatedScreens = screens.toMutableList().also { it[screenIdx] = updatedScreen }
            _habitScreens.value = updatedScreens
            _selectedEditIndex.value = -1
            viewModelScope.launch { rebuildHabitList() }
            persistScreens(updatedScreens)
        } else {
            val current = _habitOrder.value.toMutableList()
            if (index !in current.indices) return
            if (current[index].isEmpty()) return
            current.removeAt(index)
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
    }

    /**
     * Sets or clears the custom icon for [habitName].
     * [iconName] is the drawable resource name without extension (e.g. "bicycle"),
     * or null to clear the override and revert to the default icon.
     */
    fun setHabitIcon(habitName: String, iconName: String?) {
        viewModelScope.launch {
            val current = _settings.value.habitIcons.toMutableMap()
            if (iconName == null) {
                current.remove(habitName)
            } else {
                current[habitName] = iconName
            }
            settingsRepo.saveHabitIcons(current)
            _settings.value = _settings.value.copy(habitIcons = current)
        }
    }

    private fun persistScreens(screens: List<HabitScreen>, activeIndex: Int = _activeScreenIndex.value) {
        isSavingOrder = true
        viewModelScope.launch {
            try {
                settingsRepo.saveHabitScreens(screens)
                settingsRepo.saveActiveScreenIndex(activeIndex)
                _settings.value = _settings.value.copy(
                    habitScreens = screens,
                    activeScreenIndex = activeIndex
                )
            } finally {
                isSavingOrder = false
            }
        }
    }

    // ── Text input feature ────────────────────────────────────────────────────

    /**
     * Toggles the "text input" feature on/off for [habitName].
     * When turned off, also removes the habit from the options set (options requires text input).
     */
    fun toggleTextInput(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.textInputHabits.toMutableSet()
            if (habitName in current) {
                current.remove(habitName)
                // Also remove from options set — options requires text input to be on
                val opts = _settings.value.textInputOptionsHabits.toMutableSet()
                opts.remove(habitName)
                settingsRepo.saveTextInputOptionsHabits(opts)
                _settings.value = _settings.value.copy(textInputOptionsHabits = opts)
            } else {
                current.add(habitName)
            }
            settingsRepo.saveTextInputHabits(current)
            _settings.value = _settings.value.copy(textInputHabits = current)
        }
    }

    /**
     * Toggles the "show options" sub-feature on/off for [habitName].
     * Only has effect when the habit already has text input enabled.
     */
    fun toggleTextInputOptions(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.textInputOptionsHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveTextInputOptionsHabits(current)
            _settings.value = _settings.value.copy(textInputOptionsHabits = current)
        }
    }

    /**
     * Associates [uri] as the text-log file for [habitName].
     * Takes a persistent read+write permission on the URI.
     */
    fun setTextInputFileUri(habitName: String, uri: Uri) {
        viewModelScope.launch {
            val uriString = uri.toString()
            val current = _settings.value.textInputFileUris.toMutableMap()
            current[habitName] = uriString
            settingsRepo.saveTextInputFileUris(current)
            _settings.value = _settings.value.copy(textInputFileUris = current)
        }
    }

    /**
     * Saves a text entry for [habitName] to its associated log file,
     * then also increments the habit count by 1 (so the habit is marked done for today).
     */
    fun saveTextEntry(habitName: String, text: String) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            _errorMessage.value = "No text log file set for '$habitName'. Select one in edit mode."
            return
        }
        viewModelScope.launch {
            try {
                textInputRepo.appendTextEntry(Uri.parse(uriString), context, text)
                // Also increment the habit count so it registers as done today
                incrementHabit(habitName, 1)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save text entry: ${e.message}"
            }
        }
    }

    /**
     * Loads the list of unique past text entries for [habitName] from its log file.
     * Returns an empty list if no file is configured or the file is empty.
     * Calls [onResult] on the main thread with the sorted unique options.
     */
    fun loadTextOptions(habitName: String, onResult: (List<String>) -> Unit) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            onResult(emptyList())
            return
        }
        viewModelScope.launch {
            try {
                val options = textInputRepo.loadUniqueOptions(Uri.parse(uriString), context)
                onResult(options)
            } catch (e: Exception) {
                onResult(emptyList())
            }
        }
    }
    // ── Dated Entry feature ───────────────────────────────────────────────────

    /**
     * Toggles the "Dated Entry" feature on/off for [habitName].
     * When turned off the linked file URI is kept (so it can be re-enabled easily)
     * but the cached file size is cleared so the next enable forces a fresh parse.
     */
    fun toggleDatedEntry(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.datedEntryHabits.toMutableSet()
            if (habitName in current) {
                current.remove(habitName)
            } else {
                current.add(habitName)
            }
            settingsRepo.saveDatedEntryHabits(current)
            _settings.value = _settings.value.copy(datedEntryHabits = current)
            // If just enabled and a file is already linked, run a sync immediately
            if (habitName in current) {
                val uriStr = _settings.value.datedEntryFileUris[habitName]
                if (!uriStr.isNullOrEmpty()) {
                    syncSingleDatedEntry(habitName, Uri.parse(uriStr), forceReparse = true)
                }
            }
        }
    }

    /**
     * Associates [uri] as the dated-entry source file for [habitName].
     * Takes a persistent read permission on the URI, then immediately runs a sync.
     */
    fun setDatedEntryFileUri(habitName: String, uri: Uri) {
        viewModelScope.launch {
            val uriString = uri.toString()
            val current = _settings.value.datedEntryFileUris.toMutableMap()
            current[habitName] = uriString
            settingsRepo.saveDatedEntryFileUris(current)
            _settings.value = _settings.value.copy(datedEntryFileUris = current)
            // Force a fresh parse since this is a new/changed file
            syncSingleDatedEntry(habitName, uri, forceReparse = true)
        }
    }

    /**
     * Called when the app comes to the foreground (via lifecycle observer in MainActivity).
     * Checks all dated-entry habits for file changes and syncs any that have changed.
     * Uses file-size comparison to skip unchanged files — very cheap when nothing changed.
     */
    fun onAppForegrounded() {
        viewModelScope.launch {
            syncAllDatedEntries(forceReparse = false)
        }
    }

    /**
     * Iterates over all habits that have Dated Entry enabled and a file URI set.
     * For each one, compares the current file size against the last-seen size.
     * Only re-parses files whose size has changed (or [forceReparse] is true).
     */
    private suspend fun syncAllDatedEntries(forceReparse: Boolean) {
        val s = _settings.value
        val habits = s.datedEntryHabits
        if (habits.isEmpty()) return

        for (habitName in habits) {
            val uriStr = s.datedEntryFileUris[habitName] ?: continue
            val uri = Uri.parse(uriStr)
            syncSingleDatedEntry(habitName, uri, forceReparse)
        }
    }

    /**
     * Syncs a single dated-entry habit:
     *  1. Reads the current file size via SAF metadata (no stream open).
     *  2. If size matches last-seen size and [forceReparse] is false → skip.
     *  3. Otherwise parse the file, update cachedPhoneDb with the new counts,
     *     persist the DB, and save the new file size.
     *
     * The parsed counts *replace* (not add to) the existing values for each date
     * in the phone DB, so the DB always reflects the current file state.
     */
    private suspend fun syncSingleDatedEntry(
        habitName: String,
        uri: Uri,
        forceReparse: Boolean
    ) {
        // Read current state on the calling (main) thread before switching to IO
        val lastSize = _settings.value.datedEntryFileSizes[habitName] ?: -2L
        val phoneUriStr = _settings.value.fileUri
        if (phoneUriStr.isEmpty()) return

        try {
            // ── IO work: file size check + parse ─────────────────────────────
            val currentSize = withContext(Dispatchers.IO) {
                datedEntryRepo.getFileSize(uri, context)
            }

            if (!forceReparse && currentSize == lastSize && currentSize >= 0) {
                Log.d(TAG, "DatedEntry[$habitName]: file unchanged (size=$currentSize), skipping")
                return
            }

            Log.d(TAG, "DatedEntry[$habitName]: parsing file (size=$currentSize, last=$lastSize)")
            val parsedCounts: Map<String, Int> = withContext(Dispatchers.IO) {
                datedEntryRepo.parseFile(uri, context)
            }

            if (parsedCounts.isEmpty() && currentSize > 0) {
                // Parse returned nothing but file is non-empty — likely a permissions issue
                Log.w(TAG, "DatedEntry[$habitName]: parse returned empty for non-empty file")
                return
            }

            // ── Main-thread state mutations ───────────────────────────────────
            // All reads/writes of cachedPhoneDb and _settings happen here on Main.
            val mutableDb = cachedPhoneDb.toMutableMap()
            val habitEntries = (mutableDb[habitName] ?: emptyMap()).toMutableMap()
            for ((dateStr, count) in parsedCounts) {
                habitEntries[dateStr] = count
            }
            mutableDb[habitName] = habitEntries.toSortedMap()
            cachedPhoneDb = mutableDb

            // Save the new file size
            val newSizes = _settings.value.datedEntryFileSizes.toMutableMap()
            newSizes[habitName] = currentSize
            _settings.value = _settings.value.copy(datedEntryFileSizes = newSizes)

            // Rebuild the displayed habit list (also on Main)
            rebuildHabitList()

            // ── IO work: persist to disk + save size to DataStore ─────────────
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(phoneUriStr), context, mutableDb)
                settingsRepo.saveDatedEntryFileSizes(newSizes)
            }

            Log.d(TAG, "DatedEntry[$habitName]: synced ${parsedCounts.size} dates")
        } catch (e: Exception) {
            Log.e(TAG, "DatedEntry[$habitName]: sync failed: ${e.message}")
        }
    }
}

class HabitViewModelFactory(
    private val habitsRepo: HabitsRepository,
    private val settingsRepo: SettingsRepository,
    private val textInputRepo: TextInputRepository,
    private val datedEntryRepo: DatedEntryRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HabitViewModel(habitsRepo, settingsRepo, textInputRepo, datedEntryRepo, context) as T
    }
}
