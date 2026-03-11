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
import com.example.tail.data.SettingsRepository
import com.example.tail.data.dateString
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

    // Track the last loaded URI to avoid reloading on every settings emission
    private var lastLoadedUri: String = ""

    // Cache the raw phone DB so we can rebuild the habit list without re-reading the file
    private var cachedPhoneDb: HabitsDatabase = emptyMap()

    // Cache the historical database so we don't re-read it on every increment
    private var cachedHistoricalDb: HabitsDatabase = emptyMap()

    init {
        viewModelScope.launch {
            settingsRepo.settingsFlow.collect { s ->
                _settings.value = s
                // Only load from file on first settings emission (app start)
                if (s.fileUri.isNotEmpty() && lastLoadedUri.isEmpty()) {
                    lastLoadedUri = s.fileUri
                    // Load historical db if configured
                    if (s.historicalFileUri.isNotEmpty()) {
                        cachedHistoricalDb = habitsRepo.loadHistoricalDatabase(
                            Uri.parse(s.historicalFileUri), context
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
