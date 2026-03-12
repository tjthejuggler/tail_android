package com.example.tail.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tail.data.AppSettings
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsRepository
import com.example.tail.data.HistoricalTotals
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.data.dateString
import com.example.tail.data.HABIT_ORDER
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

private const val TAG = "HabitVM"

/**
 * Main ViewModel: owns habits list + settings state, delegates I/O to repositories.
 * Supports day navigation: selectedDate can be moved backward/forward relative to today.
 * Supports multiple named screens of habits.
 */
class HabitViewModel(
    private val habitsRepo: HabitsRepository,
    private val settingsRepo: SettingsRepository,
    private val textInputRepo: TextInputRepository,
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

    /** The list of named habit screens. Empty = not yet initialised (use flat habitOrder). */
    private val _habitScreens = MutableStateFlow<List<HabitScreen>>(emptyList())
    val habitScreens: StateFlow<List<HabitScreen>> = _habitScreens.asStateFlow()

    /** Index of the currently displayed screen. */
    private val _activeScreenIndex = MutableStateFlow(0)
    val activeScreenIndex: StateFlow<Int> = _activeScreenIndex.asStateFlow()

    // Track the last loaded URI to avoid reloading on every settings emission
    private var lastLoadedUri: String = ""

    // Debounce state for moveSelectedHabit: accumulate rapid taps, apply after idle period.
    // moveOriginIndex is the index BEFORE the current burst started (fixed for the whole burst).
    private var pendingMoveDelta: Int = 0
    private var moveOriginIndex: Int = -1
    private var moveDebounceJob: Job? = null
    private val MOVE_DEBOUNCE_MS = 300L

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

    private fun catchUpAndLoad(uri: Uri) {
        viewModelScope.launch {
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
    }

    fun loadFromFile(uri: Uri) {
        viewModelScope.launch {
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

    /** Toggles info mode on/off. Clears selected habit when turning off. */
    fun toggleInfoMode() {
        _infoMode.value = !_infoMode.value
        if (!_infoMode.value) {
            _selectedInfoHabit.value = null
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

    /** Toggles edit (tap-to-select reorder) mode on/off. Clears selection when turning off. */
    fun toggleEditMode() {
        _editMode.value = !_editMode.value
        if (!_editMode.value) {
            _selectedEditIndex.value = -1
        }
    }

    /** Selects (or deselects) a cell by grid index in edit mode (works for habits and placeholders). */
    fun selectEditHabit(index: Int) {
        val prev = _selectedEditIndex.value
        val next = if (prev == index) -1 else index
        Log.d(TAG, "selectEditHabit: index=$index prev=$prev -> next=$next")
        _selectedEditIndex.value = next
    }

    /**
     * Moves the placeholder selection cursor by [delta] positions without changing any data.
     * Used when a placeholder is selected and the user taps ← or →.
     */
    fun movePlaceholderSelection(delta: Int) {
        val current = _selectedEditIndex.value
        if (current < 0) return
        val newIdx = (current + delta).coerceIn(0, 79) // 80 cells total (8×10 grid)
        _selectedEditIndex.value = newIdx
    }

    /**
     * Moves the currently selected habit by [delta] positions (+1 = right/down, -1 = left/up).
     *
     * Taps are debounced: rapid consecutive taps accumulate their deltas and the actual
     * list mutation + persist happens once, 300 ms after the last tap. The selection
     * highlight moves immediately on every tap so the UI feels responsive.
     */
    fun moveSelectedHabit(delta: Int) {
        val currentIdx = _selectedEditIndex.value
        if (currentIdx < 0) return

        // Determine the valid range for the current context
        val screens = _habitScreens.value
        val maxIdx = if (screens.isNotEmpty()) {
            val screenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
            screens[screenIdx].habitNames.size - 1
        } else {
            _habitOrder.value.size - 1
        }

        // On the first tap of a new burst, record the origin index
        if (moveDebounceJob == null || moveDebounceJob?.isActive == false) {
            moveOriginIndex = currentIdx
            pendingMoveDelta = 0
        }

        // Accumulate delta and clamp the preview index immediately
        pendingMoveDelta += delta
        val previewIdx = (moveOriginIndex + pendingMoveDelta).coerceIn(0, maxIdx)
        // Clamp accumulated delta to avoid over-accumulation at edges
        pendingMoveDelta = previewIdx - moveOriginIndex
        _selectedEditIndex.value = previewIdx

        // Cancel any existing debounce timer and restart it
        moveDebounceJob?.cancel()
        moveDebounceJob = viewModelScope.launch {
            delay(MOVE_DEBOUNCE_MS)
            applyPendingMove()
        }
    }

    /**
     * Applies the accumulated [pendingMoveDelta] from [moveOriginIndex] in one shot.
     * Called by the debounce timer after tapping stops.
     */
    private suspend fun applyPendingMove() {
        val fromIdx = moveOriginIndex
        val totalDelta = pendingMoveDelta
        pendingMoveDelta = 0
        moveOriginIndex = -1
        if (totalDelta == 0 || fromIdx < 0) return

        val screens = _habitScreens.value
        if (screens.isNotEmpty()) {
            val screenIdx = _activeScreenIndex.value.coerceIn(0, screens.size - 1)
            val screen = screens[screenIdx]
            val current = screen.habitNames.toMutableList()
            val newIdx = (fromIdx + totalDelta).coerceIn(0, current.size - 1)
            if (newIdx == fromIdx) return
            val item = current.removeAt(fromIdx)
            current.add(newIdx, item)
            val updatedScreen = screen.copy(habitNames = current)
            val updatedScreens = screens.toMutableList().also { it[screenIdx] = updatedScreen }
            _habitScreens.value = updatedScreens
            _selectedEditIndex.value = newIdx
            rebuildHabitList()
            persistScreens(updatedScreens)
        } else {
            val current = _habitOrder.value.toMutableList()
            val newIdx = (fromIdx + totalDelta).coerceIn(0, current.size - 1)
            if (newIdx == fromIdx) return
            val item = current.removeAt(fromIdx)
            current.add(newIdx, item)
            _habitOrder.value = current
            _selectedEditIndex.value = newIdx
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

        val habitName = habitNames.removeAt(idx)
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
     * (or flat order if no screens). [atIndex] is the cell index in the full TOTAL_CELLS grid,
     * so it equals the position among existing habits (placeholders don't occupy slots in
     * habitNames — they are the gaps after the last habit).
     *
     * The habit is inserted at [atIndex] if [atIndex] <= current habit count, otherwise appended.
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
            val insertAt = atIndex.coerceIn(0, current.size)
            current.add(insertAt, trimmed)
            val updatedScreen = screen.copy(habitNames = current)
            val updatedScreens = screens.toMutableList().also { it[screenIdx] = updatedScreen }
            _habitScreens.value = updatedScreens
            _selectedEditIndex.value = insertAt
            viewModelScope.launch { rebuildHabitList() }
            persistScreens(updatedScreens)
        } else {
            val current = _habitOrder.value.toMutableList()
            val insertAt = atIndex.coerceIn(0, current.size)
            current.add(insertAt, trimmed)
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
}

class HabitViewModelFactory(
    private val habitsRepo: HabitsRepository,
    private val settingsRepo: SettingsRepository,
    private val textInputRepo: TextInputRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HabitViewModel(habitsRepo, settingsRepo, textInputRepo, context) as T
    }
}
