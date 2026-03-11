package com.example.tail.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tail.data.AppSettings
import com.example.tail.data.Habit
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsRepository
import com.example.tail.data.HistoricalTotals
import com.example.tail.data.SettingsRepository
import com.example.tail.data.dateString
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private const val TAG = "HabitVM"

/**
 * Main ViewModel: owns habits list + settings state, delegates I/O to repositories.
 * Supports day navigation: selectedDate can be moved backward/forward relative to today.
 */
class HabitViewModel(
    private val habitsRepo: HabitsRepository,
    private val settingsRepo: SettingsRepository,
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
    private val _habitOrder = MutableStateFlow<List<String>>(com.example.tail.data.HABIT_ORDER)
    val habitOrder: StateFlow<List<String>> = _habitOrder.asStateFlow()

    /** The index (in the current habit list) of the habit selected for reordering. -1 = none. */
    private val _selectedEditIndex = MutableStateFlow(-1)
    val selectedEditIndex: StateFlow<Int> = _selectedEditIndex.asStateFlow()

    // Track the last loaded URI to avoid reloading on every settings emission
    private var lastLoadedUri: String = ""

    // Flag to suppress settingsFlow reaction while we're saving a new habit order
    // (avoids a feedback loop: saveHabitOrder → DataStore emits → collector updates _habitOrder → etc.)
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
                // Sync habit order from persisted settings, but skip if we're the ones saving it
                // (avoids feedback loop: moveSelectedHabit → saveHabitOrder → flow emits → here)
                if (s.habitOrder.isNotEmpty() && !isSavingOrder) {
                    _habitOrder.value = s.habitOrder
                }
                // Only load from file on first settings emission (app start)
                if (s.fileUri.isNotEmpty() && lastLoadedUri.isEmpty()) {
                    lastLoadedUri = s.fileUri
                    // Load historical db if configured
                    if (s.historicalFileUri.isNotEmpty()) {
                        cachedHistoricalDb = habitsRepo.loadHistoricalDatabase(
                            Uri.parse(s.historicalFileUri), context
                        )
                    }
                    // Load historical totals if configured
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
     * On app start (or file selection): ensure all missing days are filled in up to today,
     * then load and display the habit list for the selected date.
     */
    private fun catchUpAndLoad(uri: Uri) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                // Fill in any missing days up to today, save, and get the updated DB
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
    private fun rebuildHabitList() {
        _habits.value = habitsRepo.buildHabitList(
            db = cachedPhoneDb,
            settings = _settings.value,
            historicalDb = cachedHistoricalDb,
            historicalTotals = cachedHistoricalTotals,
            targetDate = _selectedDate.value
        )
    }

    fun setFileUri(uri: Uri) {
        viewModelScope.launch {
            val uriString = uri.toString()
            lastLoadedUri = uriString
            settingsRepo.saveFileUri(uriString)
            // Reset to today when a new file is picked
            _selectedDate.value = LocalDate.now()
            catchUpAndLoad(uri)
        }
    }

    fun setHistoricalFileUri(uri: Uri) {
        viewModelScope.launch {
            settingsRepo.saveHistoricalFileUri(uri.toString())
            // Load and cache the historical database, then rebuild habit list
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
            // Load and cache the historical totals, then rebuild habit list
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
        // Clamp: don't allow going into the future beyond today
        _selectedDate.value = if (newDate.isAfter(today)) today else newDate
        rebuildHabitList()
    }

    fun incrementHabit(habitName: String, amount: Int = 1) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }
        viewModelScope.launch {
            try {
                val uri = Uri.parse(uriString)
                val db = habitsRepo.incrementHabitForDate(
                    uri, context, habitName, amount, _selectedDate.value
                )
                cachedPhoneDb = db
                rebuildHabitList()
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
            // Rebuild habit list with updated custom input set (no file re-read needed)
            _habits.value = _habits.value.map { habit ->
                habit.copy(useCustomInput = habit.name in current)
            }
            // _settings will be updated by the DataStore flow collector
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

    /** Selects (or deselects) a habit by index for reordering in edit mode. */
    fun selectEditHabit(index: Int) {
        val prev = _selectedEditIndex.value
        val next = if (prev == index) -1 else index
        Log.d(TAG, "selectEditHabit: index=$index prev=$prev -> next=$next habitOrderSize=${_habitOrder.value.size} habitsSize=${_habits.value.size}")
        _selectedEditIndex.value = next
    }

    /**
     * Moves the currently selected habit by [delta] positions (+1 = right/down, -1 = left/up).
     * Clamps to valid range. Persists immediately and keeps the selection tracking the moved item.
     */
    fun moveSelectedHabit(delta: Int) {
        val idx = _selectedEditIndex.value
        Log.d(TAG, "moveSelectedHabit: delta=$delta selectedIdx=$idx habitOrderSize=${_habitOrder.value.size} habitsSize=${_habits.value.size}")
        if (idx < 0) {
            Log.w(TAG, "moveSelectedHabit: no habit selected, ignoring")
            return
        }
        val current = _habitOrder.value.toMutableList()
        val newIdx = (idx + delta).coerceIn(0, current.size - 1)
        Log.d(TAG, "moveSelectedHabit: moving '${current.getOrNull(idx)}' from $idx to $newIdx")
        if (newIdx == idx) {
            Log.d(TAG, "moveSelectedHabit: already at boundary, no-op")
            return
        }
        val item = current.removeAt(idx)
        current.add(newIdx, item)
        _habitOrder.value = current
        _selectedEditIndex.value = newIdx
        // Rebuild display list with new order immediately (don't wait for DataStore round-trip)
        val settingsWithOrder = _settings.value.copy(habitOrder = current)
        _habits.value = habitsRepo.buildHabitList(
            db = cachedPhoneDb,
            settings = settingsWithOrder,
            historicalDb = cachedHistoricalDb,
            historicalTotals = cachedHistoricalTotals,
            targetDate = _selectedDate.value
        )
        Log.d(TAG, "moveSelectedHabit: habits rebuilt, new size=${_habits.value.size}, persisting...")
        // Persist to DataStore — use flag to suppress the settingsFlow feedback loop
        isSavingOrder = true
        viewModelScope.launch {
            try {
                settingsRepo.saveHabitOrder(current)
                // Update _settings in-memory so it stays consistent without re-triggering a rebuild
                _settings.value = _settings.value.copy(habitOrder = current)
                Log.d(TAG, "moveSelectedHabit: persist complete")
            } catch (e: Exception) {
                Log.e(TAG, "moveSelectedHabit: persist FAILED", e)
            } finally {
                isSavingOrder = false
            }
        }
    }
}

class HabitViewModelFactory(
    private val habitsRepo: HabitsRepository,
    private val settingsRepo: SettingsRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HabitViewModel(habitsRepo, settingsRepo, context) as T
    }
}
