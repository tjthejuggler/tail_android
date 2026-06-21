package com.example.tail.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.tail.data.AiIcon
import com.example.tail.data.AiIconGeneratorService
import com.example.tail.data.AiIconRepository
import com.example.tail.data.AppSettings
import com.example.tail.data.ChessComRepository
import com.example.tail.data.ChessComType
import com.example.tail.data.GarminRepository
import com.example.tail.data.GarminType
import com.example.tail.data.ImportResult
import com.example.tail.data.DatedEntryRepository
import com.example.tail.data.DayStats
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.LocationRepository
import com.example.tail.data.SecondaryLocation
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.TimedDataRepository
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.data.applyDivider
import com.example.tail.data.dateString
import com.example.tail.data.expandEntriesToCalendarDaysPublic
import com.example.tail.data.parseDate
import com.example.tail.data.HABIT_ORDER
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val TAG = "HabitVM"

/** Total cells in the 8×10 habit grid — matches TOTAL_CELLS in HabitGridScreen. */
private const val TOTAL_GRID_CELLS = 80

/**
 * Extracts a country name from a "Place, Region, Country" location label.
 *
 * Returns null for empty / unparseable labels, or when the resolved country
 * is in [ignoredNames] (user-managed exclusion list, case-insensitive).
 *
 * US states and other false-positive region names are no longer hardcoded —
 * they are seeded into the persistent ignore list on first run and can be
 * removed by the user from the "Edit" dialog in the countries popup.
 */
private fun extractCountry(label: String, ignoredNames: Set<String> = emptySet()): String? {
    val parts = label.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val country = parts.last()
    // Case-insensitive check: the ignore list stores properly-capitalised names
    // (e.g. "Massachusetts") but location labels may vary in casing.
    if (ignoredNames.any { it.equals(country, ignoreCase = true) }) return null
    return country
}

// ── IPC broadcast constants ──────────────────────────────────────────────────
/** Broadcast action sent after every successful habit increment. */
const val ACTION_HABIT_INCREMENTED = "com.example.tail.ACTION_HABIT_INCREMENTED"
/** String extra: the name of the habit that was incremented. */
const val EXTRA_HABIT_NAME = "EXTRA_HABIT_NAME"
/** Signature permission required to receive the broadcast. */
private const val PERMISSION_TAIL_INTEGRATION = "com.example.tail.permission.TAIL_INTEGRATION"

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
    private val subtypeDataRepo: SubtypeDataRepository,
    private val timedDataRepo: TimedDataRepository,
    private val context: Context,
    private val locationRepo: LocationRepository = LocationRepository(context)
) : ViewModel() {

    /** Repository for recording habit increment timestamps (internal storage). */
    val timestampRepo = HabitTimestampRepository(context)

    // ── Location ─────────────────────────────────────────────────────────────
    /**
     * Location label for the currently selected date.
     * Null means "not yet loaded" (only briefly on startup); use [selectedDateLocation]
     * which wraps null as "No location" in the UI.
     */
    private val _selectedDateLocation = MutableStateFlow<String?>(null)
    val selectedDateLocation: StateFlow<String?> = _selectedDateLocation.asStateFlow()

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

    // ── AI Icon Generation ───────────────────────────────────────────────────
    private val aiIconRepo = AiIconRepository(context)
    private val aiIconGenService = AiIconGeneratorService()

    /** List of AI-generated icon metadata, refreshed after generate/delete. */
    private val _aiIcons = MutableStateFlow<List<AiIcon>>(emptyList())
    val aiIcons: StateFlow<List<AiIcon>> = _aiIcons.asStateFlow()

    /** True while an AI icon generation request is in flight. */
    private val _aiIconGenerating = MutableStateFlow(false)
    val aiIconGenerating: StateFlow<Boolean> = _aiIconGenerating.asStateFlow()

    /** Error message from the last AI icon generation attempt (null = no error). */
    private val _aiIconError = MutableStateFlow<String?>(null)
    val aiIconError: StateFlow<String?> = _aiIconError.asStateFlow()

    // ── Chess.com Integration ─────────────────────────────────────────────────
    private val chessComRepo = ChessComRepository(context)

    /** Status message for chess.com sync operations (shown in settings). */
    private val _chessComSyncStatus = MutableStateFlow("")
    val chessComSyncStatus: StateFlow<String> = _chessComSyncStatus.asStateFlow()

    /** Job for the periodic chess.com polling loop. */
    private var chessComPollingJob: Job? = null

    /** Interval between chess.com polls (15 minutes). */
    private val CHESS_COM_POLL_INTERVAL_MS = 15 * 60 * 1000L

    // ── Garmin Integration ────────────────────────────────────────────────────
    private val garminRepo = GarminRepository(context)

    /** Status message for Garmin sync operations (shown in settings). */
    private val _garminSyncStatus = MutableStateFlow("")
    val garminSyncStatus: StateFlow<String> = _garminSyncStatus.asStateFlow()

    /** Current month's Garmin data for display (metric type → date → value). */
    private val _garminMonthlyData = MutableStateFlow<Map<GarminType, Map<String, Int>>>(emptyMap())
    val garminMonthlyData: StateFlow<Map<GarminType, Map<String, Int>>> = _garminMonthlyData.asStateFlow()

    /** Job for the periodic Garmin polling loop. */
    private var garminPollingJob: Job? = null

    /** Interval between Garmin polls (once a day). */
    private val GARMIN_POLL_INTERVAL_MS = 24 * 60 * 60 * 1000L

    // Track the last loaded URI to avoid reloading on every settings emission
    private var lastLoadedUri: String = ""

    // Debounce job for day navigation — cancelled on each new arrow tap so we only
    // rebuild the habit list after the user has settled on a date for a moment.
    private var navDebounceJob: Job? = null
    private val NAV_DEBOUNCE_MS = 800L

    // Flag to suppress settingsFlow reaction while we're saving a new habit order / screens
    private var isSavingOrder: Boolean = false

    // Cache the full unified DB so we can rebuild the habit list without re-reading the file
    private var cachedPhoneDb: HabitsDatabase = emptyMap()

    // Per-screen habit list cache — avoids expensive rebuildHabitList() on every screen switch.
    // Keyed by (screen index, selected date) so switching between screens on the same date is instant.
    private val screenHabitCache = mutableMapOf<Pair<Int, LocalDate>, List<Habit>>()

    /** Public read-only access to the cached database for stats computation. */
    fun getCachedDatabase(): HabitsDatabase = cachedPhoneDb

    init {
        // Load AI icons from disk on startup
        refreshAiIcons()

        // Load cached Garmin data on startup so garminMonthlyData is populated
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cachedData = garminRepo.loadAllCachedData()
                if (cachedData.isNotEmpty()) {
                    _garminMonthlyData.value = cachedData
                    Log.d(TAG, "Loaded ${cachedData.size} Garmin metric types from cache")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cached Garmin data: ${e.message}")
            }
        }

        // Collect in-process increment events from VoiceHabitService / IPC receivers
        // so the UI updates instantly without waiting for ON_RESUME.
        viewModelScope.launch {
            HabitIncrementBus.events.collect { habitName ->
                Log.d(TAG, "HabitIncrementBus event for '$habitName' — reloading DB")
                val phoneUriStr = _settings.value.fileUri
                if (phoneUriStr.isNotEmpty()) {
                    try {
                        val db = withContext(Dispatchers.IO) {
                            habitsRepo.ensureDaysExist(Uri.parse(phoneUriStr), context)
                        }
                        cachedPhoneDb = db
                        rebuildHabitList()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reload DB after increment event: ${e.message}")
                    }
                }
            }
        }

        // Fetch today's location in the background (no-op if already stored for today),
        // then seed the selectedDateLocation for today.
        viewModelScope.launch {
            val loc = locationRepo.fetchTodayIfNeeded()
            // Only update if we're still on today (user hasn't navigated away)
            if (_selectedDate.value == LocalDate.now()) {
                _selectedDateLocation.value = loc
            }
        }

        // Keep selectedDateLocation in sync whenever the selected date changes.
        // When the user is on today and no location is stored yet, actively
        // fetch it (the repo will request a fresh GPS/network fix if needed).
        viewModelScope.launch {
            _selectedDate.collect { date ->
                val stored = locationRepo.getLocationForDate(date)
                _selectedDateLocation.value = stored
                if (stored == null && date == LocalDate.now()) {
                    val fetched = locationRepo.fetchTodayIfNeeded()
                    // Re-check we're still on today before updating
                    if (fetched != null && _selectedDate.value == date) {
                        _selectedDateLocation.value = fetched
                    }
                }
            }
        }

        viewModelScope.launch {
            // One-time migration: rename legacy "Launch … Widget" habit names
            // in persisted DataStore settings before collecting the flow.
            settingsRepo.migrateHabitNames()

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
                    catchUpAndLoad(Uri.parse(s.fileUri))
                    // After the DB is loaded, sync any dated-entry habits.
                    if (s.datedEntryHabits.isNotEmpty()) {
                        syncAllDatedEntries(forceReparse = false)
                    }
                    // Write the relay file on startup so the PC widget always has
                    // the latest screen layout AND icon assignments.
                    if (s.screensRelayFileUri.isNotEmpty() && s.habitScreens.isNotEmpty()) {
                        writeScreensRelayFile(s.habitScreens, s.activeScreenIndex, s.screensRelayFileUri)
                    }
                    // Start chess.com polling if enabled
                    if (s.chessComEnabled && s.chessComUsername.isNotEmpty()) {
                        startChessComPolling()
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

    /** Rebuilds the displayed habit list from cached data for the current selectedDate.
     *  Stores the result in the per-screen cache for instant retrieval on switch. */
    private suspend fun rebuildHabitList() {
        val effectiveOrder = activeHabitOrder()
        // If screens are configured and the active screen is empty, show nothing.
        // We must NOT fall back to HABIT_ORDER in this case.
        if (effectiveOrder.isEmpty() && _habitScreens.value.isNotEmpty()) {
            _habits.value = emptyList()
            screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = emptyList()
            return
        }
        val settingsWithOrder = _settings.value.copy(habitOrder = effectiveOrder)
        // Run the heavy per-habit calculations on a background CPU thread
        val newList = withContext(Dispatchers.Default) {
            habitsRepo.buildHabitList(
                db = cachedPhoneDb,
                settings = settingsWithOrder,
                targetDate = _selectedDate.value
            )
        }
        _habits.value = newList
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = newList
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

    /**
     * Sets the SAF URI for the screens_layout.json relay file.
     * Immediately writes the current screen layout to the file.
     */
    fun setTaskerFileUri(uri: Uri) {
        viewModelScope.launch {
            val uriString = uri.toString()
            settingsRepo.saveTaskerFileUri(uriString)
            _settings.value = _settings.value.copy(taskerFileUri = uriString)
            // Write current stats immediately so the file is up-to-date
            writeTaskerFile(uriString)
        }
    }

    /**
     * Writes today's habit stats to the Tasker relay txt file (if configured).
     * Format:
     *   today=<N>      — habits with count > 0 today
     *   avg7=<X.XX>    — average habits done per day over last 7 days
     *   avg30=<X.XX>   — average habits done per day over last 30 days
     * Runs on Dispatchers.IO; errors are silently logged so they never disrupt the UI.
     */
    private fun writeTaskerFile(taskerUriString: String) {
        if (taskerUriString.isEmpty()) return
        val db = cachedPhoneDb
        val dividers = _settings.value.habitDividers
        // Exclude "Don't affect points" habits (e.g. Garmin imports) from totals
        val noPointsHabits = _settings.value.noPointsHabits

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = com.example.tail.data.buildTaskerStatsContent(
                    db = db,
                    dividers = dividers,
                    noPointsHabits = noPointsHabits
                )

                val uri = Uri.parse(taskerUriString)
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.bufferedWriter().use { it.write(content) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write Tasker file: ${e.message}")
            }
        }
    }

    /**
     * Forces an immediate recalculation of the Tasker stats file from the current
     * database, correctly excluding "Don't affect points" habits.
     *
     * Use this to repair a stale/corrupted stats file (e.g. after the Garmin
     * no-points fix) without waiting for the next habit increment. Surfaces a
     * one-shot message via [_errorMessage] so the UI can confirm to the user.
     */
    fun refreshTaskerStatsFile() {
        val uri = _settings.value.taskerFileUri
        if (uri.isEmpty()) {
            _errorMessage.value = "No Tasker stats file is configured."
            return
        }
        writeTaskerFile(uri)
        _errorMessage.value = "Tasker stats file recalculated."
    }

    /**
     * Sends a generic broadcast announcing that a habit was incremented.
     * Protected by the TAIL_INTEGRATION signature permission so only same-keystore
     * apps (e.g. VILD) can receive it. The broadcast is fire-and-forget — if no
     * receiver is registered, it's silently dropped.
     */
    private fun sendHabitIncrementedBroadcast(habitName: String) {
        try {
            val intent = Intent(ACTION_HABIT_INCREMENTED).apply {
                putExtra(EXTRA_HABIT_NAME, habitName)
            }
            context.sendBroadcast(intent, PERMISSION_TAIL_INTEGRATION)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send habit-incremented broadcast: ${e.message}")
        }
    }

    fun setScreensRelayFileUri(uri: Uri) {
        viewModelScope.launch {
            val uriString = uri.toString()
            settingsRepo.saveScreensRelayFileUri(uriString)
            _settings.value = _settings.value.copy(screensRelayFileUri = uriString)
            // Write current layout immediately so the file is up-to-date
            writeScreensRelayFile(_habitScreens.value, _activeScreenIndex.value, uriString)
        }
    }

    /**
     * Navigate the selected date by [deltaDays] (negative = go back, positive = go forward).
     * Cannot navigate past today.
     *
     * The date label updates instantly, but the heavy habit-list rebuild is debounced:
     * if the user taps the arrow again within [NAV_DEBOUNCE_MS] ms the previous rebuild
     * is cancelled and the timer restarts. This prevents loading data for every
     * intermediate date when the user rapidly taps through many days.
     */
    fun navigateDay(deltaDays: Int) {
        val newDate = _selectedDate.value.plusDays(deltaDays.toLong())
        val today = LocalDate.now()
        // Instant UI update — date label changes immediately
        _selectedDate.value = if (newDate.isAfter(today)) today else newDate
        // Cancel any pending rebuild and restart the debounce timer
        navDebounceJob?.cancel()
        navDebounceJob = viewModelScope.launch {
            delay(NAV_DEBOUNCE_MS)
            rebuildHabitList()
        }
    }

    /**
     * Navigates directly to [date] (clamped to today at the latest).
     * Immediately rebuilds the habit list for that date (no debounce — this is
     * a deliberate jump, not rapid arrow tapping).
     */
    fun navigateToDate(date: LocalDate) {
        val today = LocalDate.now()
        val clamped = if (date.isAfter(today)) today else date
        _selectedDate.value = clamped
        navDebounceJob?.cancel()
        navDebounceJob = viewModelScope.launch {
            rebuildHabitList()
        }
    }

    /**
     * Returns a map of dateString → total habit points for every day in the given
     * [year]/[month]. Points are the sum of applyDivider(raw, divider) across ALL
     * habits that have data for that day.
     *
     * Only habits that are part of any screen (or the flat habitOrder) are included,
     * so the totals match what the user actually tracks.
     */
    fun getDailyTotals(year: Int, month: Int): Map<String, Int> {
        val db = cachedPhoneDb
        if (db.isEmpty()) return emptyMap()

        val dividers = _settings.value.habitDividers
        val noPointsHabits = _settings.value.noPointsHabits
        // Use all habit names present in the DB (covers all screens)
        val habitNames = db.keys

        val result = mutableMapOf<String, Int>()
        // Build date strings for every day in the month
        val firstDay = LocalDate.of(year, month, 1)
        val daysInMonth = firstDay.lengthOfMonth()
        for (d in 1..daysInMonth) {
            val ds = dateString(LocalDate.of(year, month, d))
            var total = 0
            for (name in habitNames) {
                // Skip habits that don't affect points
                if (name in noPointsHabits) continue
                val raw = db[name]?.get(ds) ?: 0
                total += applyDivider(raw, dividers[name] ?: 1)
            }
            result[ds] = total
        }
        return result
    }

    /**
     * Increments a habit's count. When [recordTimestamp] is true (default), also
     * records the current time in the timestamp repository. Set to false for
     * "silent" increments (e.g. long-press, edit-mode counter adjustments).
     */
    fun incrementHabit(habitName: String, amount: Int = 1, recordTimestamp: Boolean = true) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }

        // Step 1: instant targeted update — just flip todayCount for this one habit.
        // This is O(n) list copy with zero calculations, so it's effectively instant.
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
        val rawNewCount = (currentEntries[dateStr] ?: 0) + amount
        // If this habit has the "1 max" cap, clamp to 1
        val newCount = if (habitName in _settings.value.maxOneHabits) rawNewCount.coerceAtMost(1) else rawNewCount
        // If the count didn't actually change (e.g. already at 1 with 1-max), bail out early
        if (newCount == (currentEntries[dateStr] ?: 0)) return
        val divider = _settings.value.habitDividers[habitName] ?: 1
        _habits.value = _habits.value.map { h ->
            if (h.name == habitName) h.copy(
                todayCount = applyDivider(newCount, divider),
                rawTodayCount = newCount
            ) else h
        }
        // Keep per-screen cache in sync with the instant update
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

        // Step 2: update in-memory cache
        var updatedDb = habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, amount, _selectedDate.value)

        // Step 2b: if this is a conditional habit, also increment all linked habits
        val linkedHabits = if (habitName in _settings.value.conditionalHabits) {
            _settings.value.conditionalLinkedHabits[habitName] ?: emptySet()
        } else emptySet()

        for (linkedName in linkedHabits) {
            val linkedEntries = updatedDb[linkedName] ?: emptyMap()
            val linkedRaw = (linkedEntries[dateStr] ?: 0) + 1
            val linkedClamped = if (linkedName in _settings.value.maxOneHabits) linkedRaw.coerceAtMost(1) else linkedRaw
            if (linkedClamped != (linkedEntries[dateStr] ?: 0)) {
                updatedDb = habitsRepo.applyIncrementToDb(updatedDb, linkedName, 1, _selectedDate.value)
                val linkedDivider = _settings.value.habitDividers[linkedName] ?: 1
                _habits.value = _habits.value.map { h ->
                    if (h.name == linkedName) h.copy(
                        todayCount = applyDivider(linkedClamped, linkedDivider),
                        rawTodayCount = linkedClamped
                    ) else h
                }
                // Record timestamp for the linked habit too
                if (recordTimestamp) {
                    viewModelScope.launch {
                        timestampRepo.addTimestamp(linkedName, _selectedDate.value)
                    }
                }
            }
        }

        cachedPhoneDb = updatedDb
        // Keep per-screen cache in sync after conditional updates
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

        // Step 3: full rebuild (streak/ATH recalc) + disk write in background
        viewModelScope.launch {
            rebuildHabitList()
            try {
                val uri = Uri.parse(uriString)
                habitsRepo.persistDatabase(uri, context, updatedDb)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save: ${e.message}"
            }
            // Update Tasker relay file after every count change
            writeTaskerFile(_settings.value.taskerFileUri)
        }

        // Step 4: if this is a timed habit (and NOT subtyped — subtyped timed habits
        // record their timed entries in saveSubtypeIncrement instead), append a
        // timestamped session entry with subtype=null.
        if (habitName in _settings.value.timedHabits && habitName !in _settings.value.subtypedHabits) {
            val timedUri = _settings.value.timedDataFileUris[habitName]
            if (timedUri != null) {
                viewModelScope.launch {
                    timedDataRepo.appendEntries(
                        Uri.parse(timedUri), context,
                        mapOf(null to amount)
                    )
                }
            }
        }

        // Step 5: record timestamp(s) if requested
        if (recordTimestamp && amount > 0) {
            viewModelScope.launch {
                timestampRepo.addTimestamp(habitName, _selectedDate.value)
            }
        }

        // Step 6: broadcast a generic "habit incremented" event so same-keystore apps
        // (e.g. VILD) can react — e.g. auto-switch from night to day mode on wake-up.
        sendHabitIncrementedBroadcast(habitName)
    }

    /**
     * Sets the count for [habitName] on the currently selected date to an absolute [newCount].
     * [newCount] is the raw value to store. Clamps to >= 0. Persists to the DB file.
     */
    fun setHabitCount(habitName: String, newCount: Int) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }
        val clamped = newCount.coerceAtLeast(0)
        val divider = _settings.value.habitDividers[habitName] ?: 1

        // Step 1: instant targeted UI update
        _habits.value = _habits.value.map { h ->
            if (h.name == habitName) h.copy(
                todayCount = applyDivider(clamped, divider),
                rawTodayCount = clamped
            ) else h
        }
        // Keep per-screen cache in sync
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

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
            // Update Tasker relay file after every count change
            writeTaskerFile(_settings.value.taskerFileUri)
        }
    }

    /**
     * Toggles the "1 max" cap on/off for [habitName].
     * When enabled, the habit's daily count can never exceed 1 (binary done/not-done).
     */
    fun toggleMaxOne(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.maxOneHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveMaxOneHabits(current)
            _settings.value = _settings.value.copy(maxOneHabits = current)
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

    /**
     * Saves a custom list of quick-increment button amounts for [habitName].
     * Pass an empty list to revert to the default amounts.
     */
    fun setCustomInputAmounts(habitName: String, amounts: List<Int>) {
        viewModelScope.launch {
            val current = _settings.value.customInputAmounts.toMutableMap()
            if (amounts.isEmpty()) {
                current.remove(habitName)
            } else {
                current[habitName] = amounts
            }
            settingsRepo.saveCustomInputAmounts(current)
            _settings.value = _settings.value.copy(customInputAmounts = current)
        }
    }

    /**
     * Records [amount] as the most recently used increment for [habitName].
     * Keeps up to 3 unique recent amounts, most recent first.
     */
    fun recordRecentIncrementAmount(habitName: String, amount: Int) {
        viewModelScope.launch {
            val current = _settings.value.customInputRecentAmounts.toMutableMap()
            val existing = current[habitName]?.toMutableList() ?: mutableListOf()
            existing.remove(amount)          // remove duplicate if present
            existing.add(0, amount)          // prepend as most recent
            if (existing.size > 3) existing.subList(3, existing.size).clear()
            current[habitName] = existing
            settingsRepo.saveCustomInputRecentAmounts(current)
            _settings.value = _settings.value.copy(customInputRecentAmounts = current)
        }
    }

    /**
     * Sets (or clears) the divider for [habitName].
     * [divisor] must be >= 2 to enable division; pass 1 (or 0) to disable.
     * When changed, the habit list is rebuilt so the displayed count updates immediately.
     */
    fun setHabitDivider(habitName: String, divisor: Int) {
        viewModelScope.launch {
            val current = _settings.value.habitDividers.toMutableMap()
            if (divisor <= 1) {
                current.remove(habitName)
            } else {
                current[habitName] = divisor
            }
            settingsRepo.saveHabitDividers(current)
            _settings.value = _settings.value.copy(habitDividers = current)
            rebuildHabitList()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }

    /**
     * Toggles the "conditional" type on/off for [habitName].
     * When enabled, tapping this habit also auto-increments all habits in its linked set.
     */
    fun toggleConditional(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.conditionalHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveConditionalHabits(current)
            _settings.value = _settings.value.copy(conditionalHabits = current)
        }
    }

    /**
     * Sets the linked habits for a conditional habit.
     * [linkedNames] is the full replacement set of habit names to auto-increment.
     * Pass an empty set to clear all links (but keep the conditional type enabled).
     */
    fun setConditionalLinks(habitName: String, linkedNames: Set<String>) {
        viewModelScope.launch {
            val current = _settings.value.conditionalLinkedHabits.toMutableMap()
            if (linkedNames.isEmpty()) {
                current.remove(habitName)
            } else {
                current[habitName] = linkedNames
            }
            settingsRepo.saveConditionalLinkedHabits(current)
            _settings.value = _settings.value.copy(conditionalLinkedHabits = current)
        }
    }

    /** Returns the current set of linked habit names for a conditional habit. */
    fun getConditionalLinks(habitName: String): Set<String> =
        _settings.value.conditionalLinkedHabits[habitName] ?: emptySet()

    // ── Subtyped habit methods ──────────────────────────────────────────────

    /** Toggles the "subtyped" type on/off for [habitName]. */
    fun toggleSubtyped(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.subtypedHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveSubtypedHabits(current)
            _settings.value = _settings.value.copy(subtypedHabits = current)
        }
    }

    /** Sets the ordered list of subtype names for [habitName]. */
    fun setHabitSubtypes(habitName: String, subtypes: List<String>) {
        viewModelScope.launch {
            val current = _settings.value.habitSubtypes.toMutableMap()
            if (subtypes.isEmpty()) current.remove(habitName) else current[habitName] = subtypes
            settingsRepo.saveHabitSubtypes(current)
            _settings.value = _settings.value.copy(habitSubtypes = current)
        }
    }

    /** Sets the SAF URI for the subtype data file for [habitName]. */
    fun setSubtypeDataFileUri(habitName: String, uri: Uri) {
        viewModelScope.launch {
            val uriString = uri.toString()
            val current = _settings.value.subtypeDataFileUris.toMutableMap()
            current[habitName] = uriString
            settingsRepo.saveSubtypeDataFileUris(current)
            _settings.value = _settings.value.copy(subtypeDataFileUris = current)
        }
    }

    /**
     * Loads today's subtype breakdown for [habitName], then calls [onLoaded] with the result.
     * Returns empty map if no file is configured or no data exists for today.
     */
    fun loadSubtypeBreakdown(habitName: String, onLoaded: (Map<String, Int>) -> Unit) {
        val uriString = _settings.value.subtypeDataFileUris[habitName] ?: run {
            onLoaded(emptyMap()); return
        }
        viewModelScope.launch {
            val dateStr = com.example.tail.data.dateString(_selectedDate.value)
            val breakdown = subtypeDataRepo.getBreakdownForDate(
                Uri.parse(uriString), context, dateStr
            )
            onLoaded(breakdown)
        }
    }

    /**
     * Saves a subtype increment: adds [increments] to the subtype data file for today,
     * and increments the main habit count by the total.
     */
    fun saveSubtypeIncrement(habitName: String, increments: Map<String, Int>) {
        val total = increments.values.sum()
        if (total <= 0) return

        // Increment the main habit count
        incrementHabit(habitName, total)

        // Save subtype breakdown
        val uriString = _settings.value.subtypeDataFileUris[habitName] ?: return
        viewModelScope.launch {
            val dateStr = com.example.tail.data.dateString(_selectedDate.value)
            subtypeDataRepo.addToDate(Uri.parse(uriString), context, dateStr, increments)
        }

        // If this is a timed habit, also record timestamped session entries
        if (habitName in _settings.value.timedHabits) {
            val timedUri = _settings.value.timedDataFileUris[habitName] ?: return
            viewModelScope.launch {
                // Each subtype increment becomes a separate timed entry
                timedDataRepo.appendEntries(
                    Uri.parse(timedUri), context,
                    increments.mapKeys { (k, _) -> k }
                )
            }
        }
    }

    // ── Timed habit settings ──────────────────────────────────────────────

    /** Toggles the "timed" feature on/off for [habitName]. */
    fun toggleTimed(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.timedHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveTimedHabits(current)
            _settings.value = _settings.value.copy(timedHabits = current)
        }
    }

    /** Toggles the "timeless" feature on/off for [habitName]. */
    fun toggleTimeless(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.timelessHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveTimelessHabits(current)
            _settings.value = _settings.value.copy(timelessHabits = current)
        }
    }

    /** Sets the SAF URI for a timed habit's data file. */
    fun setTimedDataFileUri(habitName: String, uri: Uri) {
        viewModelScope.launch {
            val uriString = uri.toString()
            val current = _settings.value.timedDataFileUris.toMutableMap()
            current[habitName] = uriString
            settingsRepo.saveTimedDataFileUris(current)
            _settings.value = _settings.value.copy(timedDataFileUris = current)
        }
    }

    /** Toggles edit (tap-to-select reorder) mode on/off. Clears selection when turning off. */
    fun toggleEditMode() {
        val turningOn = !_editMode.value
        _editMode.value = turningOn
        if (!turningOn) {
            _selectedEditIndex.value = -1
            _movePendingSourceIndex.value = -1
        } else {
            // Deactivate graph mode when edit mode is activated
            _graphMode.value = false
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
        // Use cached habit list for instant screen switch if available
        val cached = screenHabitCache[Pair(index, _selectedDate.value)]
        if (cached != null) {
            _habits.value = cached
        }
        // Always rebuild in background to refresh stats (streaks, etc.)
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
     * Toggles the "hidden" flag for the screen at [screenIndex].
     * A hidden screen's name is not shown in the tab bar when it is not active.
     */
    fun toggleScreenHidden(screenIndex: Int) {
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
    fun reorderScreen(fromIndex: Int, toIndex: Int) {
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
    fun toggleDisabledHabit(habitName: String) {
        val current = _settings.value.disabledHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        _settings.value = _settings.value.copy(disabledHabits = current)
        viewModelScope.launch { settingsRepo.saveDisabledHabits(current) }
    }

    /**
     * Toggles the "no points" flag for [habitName].
     * When enabled, the habit's points are NOT included in any totals.
     */
    fun toggleNoPointsHabit(habitName: String) {
        val current = _settings.value.noPointsHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        _settings.value = _settings.value.copy(noPointsHabits = current)
        viewModelScope.launch { settingsRepo.saveNoPointsHabits(current) }
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
            // Sync icon change to relay file so PC widget picks it up
            val relayUri = _settings.value.screensRelayFileUri
            if (relayUri.isNotEmpty()) {
                writeScreensRelayFile(_habitScreens.value, _activeScreenIndex.value, relayUri)
            }
        }
    }

    // ── AI Icon Generation methods ───────────────────────────────────────────

    /** Available models fetched from the API (or fallback). */
    private val _aiModels = MutableStateFlow<List<com.example.tail.data.AiModelInfo>>(
        com.example.tail.data.FALLBACK_IMAGE_MODELS
    )
    val aiModels: StateFlow<List<com.example.tail.data.AiModelInfo>> = _aiModels.asStateFlow()

    /** Saves AI icon generation settings to DataStore. */
    fun saveAiIconSettings(
        enabled: Boolean, apiKey: String, baseUrl: String, endpoint: String,
        model: String, quality: String = ""
    ) {
        viewModelScope.launch {
            settingsRepo.saveAiIconSettings(enabled, apiKey, baseUrl, endpoint, model, quality)
            _settings.value = _settings.value.copy(
                aiIconsEnabled = enabled,
                aiIconsApiKey = apiKey,
                aiIconsBaseUrl = baseUrl,
                aiIconsEndpoint = endpoint,
                aiIconsModel = model,
                aiIconsQuality = quality
            )
        }
    }

    /** Fetches available models from the API and updates the models list. */
    fun fetchAiModels() {
        val s = _settings.value
        if (s.aiIconsApiKey.isEmpty() || s.aiIconsBaseUrl.isEmpty()) return
        viewModelScope.launch {
            try {
                val models = aiIconGenService.fetchModels(s.aiIconsApiKey, s.aiIconsBaseUrl)
                if (models.isNotEmpty()) _aiModels.value = models
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch AI models", e)
                // Keep fallback models
            }
        }
    }

    /** Refreshes the list of stored AI icons from disk. */
    fun refreshAiIcons() {
        viewModelScope.launch(Dispatchers.IO) {
            _aiIcons.value = aiIconRepo.listIcons()
        }
    }

    /**
     * Generates a new AI icon from the given prompt, post-processes it to
     * white-on-transparent, saves it to the local database, and refreshes the list.
     */
    fun generateAiIcon(prompt: String) {
        val s = _settings.value
        if (!s.aiIconsEnabled || s.aiIconsApiKey.isEmpty() || s.aiIconsBaseUrl.isEmpty()) {
            _aiIconError.value = "AI icons not configured. Check Settings."
            return
        }
        _aiIconGenerating.value = true
        _aiIconError.value = null
        viewModelScope.launch {
            try {
                val bitmap = aiIconGenService.generateIcon(
                    prompt = prompt,
                    apiKey = s.aiIconsApiKey,
                    baseUrl = s.aiIconsBaseUrl,
                    endpoint = s.aiIconsEndpoint.ifEmpty { "/v1/images/generations" },
                    model = s.aiIconsModel.ifEmpty { "nano-banana-pro" },
                    quality = s.aiIconsQuality
                )
                withContext(Dispatchers.IO) {
                    aiIconRepo.saveIcon(bitmap, prompt)
                }
                refreshAiIcons()
            } catch (e: Exception) {
                Log.e(TAG, "AI icon generation failed", e)
                _aiIconError.value = e.message ?: "Unknown error"
            } finally {
                _aiIconGenerating.value = false
            }
        }
    }

    /** Deletes an AI-generated icon by its id. */
    fun deleteAiIcon(iconId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            aiIconRepo.deleteIcon(iconId)
            _aiIcons.value = aiIconRepo.listIcons()
        }
    }

    /** Returns the AiIconRepository for loading bitmaps in the UI. */
    fun getAiIconRepo(): AiIconRepository = aiIconRepo

    /** Clears the AI icon error message. */
    fun clearAiIconError() { _aiIconError.value = null }

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
    private suspend fun writeScreensRelayFile(
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
    // ── Graph mode ────────────────────────────────────────────────────────────

    /** Whether graph mode is active. */
    private val _graphMode = MutableStateFlow(false)
    val graphMode: StateFlow<Boolean> = _graphMode.asStateFlow()

    /** Habit names currently selected for graphing. */
    private val _graphSelectedHabits = MutableStateFlow<Set<String>>(emptySet())
    val graphSelectedHabits: StateFlow<Set<String>> = _graphSelectedHabits.asStateFlow()

    /** Currently selected time period for the graph — survives rotation. */
    private val _graphTimePeriod = MutableStateFlow<GraphTimePeriod?>(GraphTimePeriod.MONTH)
    val graphTimePeriod: StateFlow<GraphTimePeriod?> = _graphTimePeriod.asStateFlow()

    fun setGraphTimePeriod(period: GraphTimePeriod) {
        _graphTimePeriod.value = period
        // Clear any custom zoom range when a period button is tapped
        _graphZoomStartDate.value = null
        _graphZoomEndDate.value = null
    }

    /**
     * Custom zoom date range set by pinch-to-zoom gesture.
     * When non-null, overrides the time period selection (which becomes null/deselected).
     */
    private val _graphZoomStartDate = MutableStateFlow<LocalDate?>(null)
    val graphZoomStartDate: StateFlow<LocalDate?> = _graphZoomStartDate.asStateFlow()

    private val _graphZoomEndDate = MutableStateFlow<LocalDate?>(null)
    val graphZoomEndDate: StateFlow<LocalDate?> = _graphZoomEndDate.asStateFlow()

    fun setGraphZoomRange(startDate: LocalDate, endDate: LocalDate) {
        _graphZoomStartDate.value = startDate
        _graphZoomEndDate.value = endDate
        // Deselect the time period bubble
        _graphTimePeriod.value = null
    }

    fun clearGraphZoom() {
        _graphZoomStartDate.value = null
        _graphZoomEndDate.value = null
        // Restore default period
        _graphTimePeriod.value = GraphTimePeriod.MONTH
    }

    fun toggleGraphMode() {
        val turningOn = !_graphMode.value
        _graphMode.value = turningOn
        if (turningOn) {
            // Deactivate other modes
            _editMode.value = false
            _selectedEditIndex.value = -1
            _movePendingSourceIndex.value = -1
        } else {
            _graphSelectedHabits.value = emptySet()
        }
    }

    fun toggleGraphHabitSelection(habitName: String) {
        val current = _graphSelectedHabits.value.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        _graphSelectedHabits.value = current
    }

    fun clearGraphSelection() {
        _graphSelectedHabits.value = emptySet()
    }

    /**
     * Data point for a single day on the graph.
     */
    data class GraphDataPoint(
        val date: LocalDate,
        val dateStr: String,
        val rawValue: Int,
        val pointsValue: Int,
        val textEntry: String? = null  // for text-input habits
    )

    /**
     * Returns the time-series data for a habit within the given date range.
     * Includes text entries for text-input habits if available.
     */
    fun getGraphData(
        habitName: String,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<GraphDataPoint> {
        val entries = cachedPhoneDb[habitName] ?: return emptyList()
        val divider = _settings.value.habitDividers[habitName] ?: 1
        val startStr = dateString(startDate)
        val endStr = dateString(endDate)

        val result = mutableListOf<GraphDataPoint>()
        var cursor = startDate
        while (!cursor.isAfter(endDate)) {
            val ds = dateString(cursor)
            val raw = entries[ds] ?: 0
            result.add(
                GraphDataPoint(
                    date = cursor,
                    dateStr = ds,
                    rawValue = raw,
                    pointsValue = applyDivider(raw, divider)
                )
            )
            cursor = cursor.plusDays(1)
        }
        return result
    }

    /**
     * Returns the earliest date with data for any of the given habits.
     */
    fun getEarliestDate(habitNames: Set<String>): LocalDate? {
        var earliest: LocalDate? = null
        for (name in habitNames) {
            val entries = cachedPhoneDb[name] ?: continue
            val firstKey = entries.keys.minOrNull() ?: continue
            val date = parseDate(firstKey) ?: continue
            if (earliest == null || date.isBefore(earliest)) {
                earliest = date
            }
        }
        return earliest
    }

    /**
     * Returns the latest date with data for any of the given habits.
     */
    fun getLatestDate(habitNames: Set<String>): LocalDate? {
        var latest: LocalDate? = null
        for (name in habitNames) {
            val entries = cachedPhoneDb[name] ?: continue
            val lastKey = entries.keys.maxOrNull() ?: continue
            val date = parseDate(lastKey) ?: continue
            if (latest == null || date.isAfter(latest)) {
                latest = date
            }
        }
        return latest
    }

    /**
     * Loads text entries for a text-input habit on a specific date.
     * Returns all text entries whose timestamp starts with the given date string.
     */
    fun loadTextEntriesForDate(habitName: String, date: LocalDate, onResult: (List<String>) -> Unit) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            onResult(emptyList())
            return
        }
        val datePrefix = dateString(date)
        viewModelScope.launch {
            try {
                val log = textInputRepo.loadTextLog(Uri.parse(uriString), context)
                val entries = log.filter { (key, _) -> key.startsWith(datePrefix) }
                    .values.toList()
                onResult(entries)
            } catch (e: Exception) {
                onResult(emptyList())
            }
        }
    }

    /**
     * Loads text entries for a text-input habit on a specific date,
     * returning both timestamps and values (for editing).
     * Returns pairs of (timestamp, text) sorted by timestamp.
     */
    fun loadTextEntriesWithTimestamps(habitName: String, date: LocalDate, onResult: (List<Pair<String, String>>) -> Unit) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            onResult(emptyList())
            return
        }
        val datePrefix = dateString(date)
        viewModelScope.launch {
            try {
                val log = textInputRepo.loadTextLog(Uri.parse(uriString), context)
                val entries = log.filter { (key, _) -> key.startsWith(datePrefix) }
                    .toList()
                    .sortedBy { it.first }
                onResult(entries)
            } catch (e: Exception) {
                onResult(emptyList())
            }
        }
    }

    /**
     * Updates an existing text entry for [habitName].
     * [oldTimestamp] is the exact key; [newText] replaces the old value.
     */
    fun updateTextEntry(habitName: String, oldTimestamp: String, newText: String) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) return
        viewModelScope.launch {
            try {
                textInputRepo.updateTextEntry(Uri.parse(uriString), context, oldTimestamp, newText)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update text entry: ${e.message}"
            }
        }
    }

    /**
     * Deletes an existing text entry for [habitName].
     * [timestamp] is the exact key to remove.
     */
    fun deleteTextEntry(habitName: String, timestamp: String) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) return
        viewModelScope.launch {
            try {
                textInputRepo.deleteTextEntry(Uri.parse(uriString), context, timestamp)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete text entry: ${e.message}"
            }
        }
    }

    /**
     * Loads the text chunks from the dated-entry source file for [habitName] on [date].
     * Each chunk is a paragraph block from the file under that date's header.
     * Returns an empty list if the habit has no dated-entry file configured, or on error.
     */
    fun loadDatedEntriesForDate(habitName: String, date: LocalDate, onResult: (List<String>) -> Unit) {
        val uriString = _settings.value.datedEntryFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            onResult(emptyList())
            return
        }
        val dateStr = dateString(date)
        viewModelScope.launch {
            try {
                val chunks = datedEntryRepo.parseChunksForDate(Uri.parse(uriString), context, dateStr)
                onResult(chunks)
            } catch (e: Exception) {
                onResult(emptyList())
            }
        }
    }

    /**
     * Checks if a habit is a dated-entry habit.
     */
    fun isDatedEntryHabit(habitName: String): Boolean {
        return habitName in _settings.value.datedEntryHabits
    }

    /**
     * Returns all habit names across all screens (for graph mode selection).
     */
    fun getAllHabitNames(): List<String> {
        val screens = _habitScreens.value
        return if (screens.isNotEmpty()) {
            screens.flatMap { it.habitNames }.filter { it.isNotEmpty() }.distinct()
        } else {
            val order = _habitOrder.value
            (if (order.isNotEmpty()) order else HABIT_ORDER).filter { it.isNotEmpty() }
        }
    }

    /**
     * Checks if a habit is a text-input habit.
     */
    fun isTextInputHabit(habitName: String): Boolean {
        return habitName in _settings.value.textInputHabits
    }

    // ── Map screen stats settings ──────────────────────────────────────────

    /**
     * Toggles a habit in/out of the map stats panel selection.
     * When removed from mapStatsHabits, also removes from mapStatsShowTextHabits.
     */
    fun toggleMapStatsHabit(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.mapStatsHabits.toMutableSet()
            if (habitName in current) {
                current.remove(habitName)
                // Also remove from show-text set
                val showText = _settings.value.mapStatsShowTextHabits.toMutableSet()
                showText.remove(habitName)
                settingsRepo.saveMapStatsShowTextHabits(showText)
                _settings.value = _settings.value.copy(mapStatsShowTextHabits = showText)
            } else {
                current.add(habitName)
            }
            settingsRepo.saveMapStatsHabits(current)
            _settings.value = _settings.value.copy(mapStatsHabits = current)
        }
    }

    /**
     * Toggles whether a text-input habit's text entries should be shown
     * in the map stats panel. Only meaningful for habits in mapStatsHabits
     * that are also text-input habits.
     */
    fun toggleMapStatsShowText(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.mapStatsShowTextHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveMapStatsShowTextHabits(current)
            _settings.value = _settings.value.copy(mapStatsShowTextHabits = current)
        }
    }

    /**
     * Returns the points value for [habitName] on [date].
     * Returns 0 if the habit has no data for that date.
     */
    fun getHabitValueForDate(habitName: String, date: LocalDate): Int {
        val raw = cachedPhoneDb[habitName]?.get(dateString(date)) ?: 0
        val divider = _settings.value.habitDividers[habitName] ?: 1
        return applyDivider(raw, divider)
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
     * Tracks the date on which [onAppStarted] last snapped the selected date.
     * Null means it has never run yet.
     *
     * The ViewModel survives configuration changes (orientation, etc.) and
     * in-app navigation, but is recreated on a true cold start. By storing the
     * *date* (not just a boolean) we can detect when a new day has arrived
     * while the app was in the background and snap to today on the next
     * ON_START — while still avoiding redundant snaps on same-day Activity
     * recreations (e.g. MapScreen forcing landscape).
     */
    private var lastInitializedDate: LocalDate? = null

    /**
     * Called on ON_START. Snaps the selected date back to today when:
     *  - This is the very first invocation (cold launch), OR
     *  - The day has changed since the last invocation (app was backgrounded
     *    overnight and reopened the next morning).
     *
     * Does NOT snap on same-day ON_START events caused by Activity recreation
     * (e.g. config changes from MapScreen's landscape orientation).
     */
    fun onAppStarted() {
        val today = LocalDate.now()
        val lastDate = lastInitializedDate
        lastInitializedDate = today
        if (lastDate == null || lastDate != today) {
            if (_selectedDate.value.isBefore(today)) {
                _selectedDate.value = today
            }
        }
    }

    /**
     * Called when the app comes to the foreground (via ON_RESUME lifecycle event).
     * Reloads the phone DB, syncs dated entries, and fetches new Garmin data from the proxy.
     * Does NOT reset the selected date so that in-app navigation (e.g. map → grid) preserves the current date.
     */
    fun onAppForegrounded() {
        viewModelScope.launch {
            // Re-read the phone DB so external increments (e.g. from ShareTextActivity)
            // are visible immediately when the user returns to the app.
            val phoneUriStr = _settings.value.fileUri
            if (phoneUriStr.isNotEmpty()) {
                try {
                    val db = withContext(Dispatchers.IO) {
                        habitsRepo.ensureDaysExist(Uri.parse(phoneUriStr), context)
                    }
                    cachedPhoneDb = db
                    rebuildHabitList()
                } catch (e: Exception) {
                    Log.w(TAG, "onAppForegrounded: failed to reload phone DB: ${e.message}")
                }
            }
            
            // Automatically sync Garmin data when app comes to foreground
            // This fetches any new data that the PC fetcher has accumulated
            syncGarminCurrentMonth()
            
            syncAllDatedEntries(forceReparse = false)
        }
        // Log current position as a secondary location for today.
        // Runs in the background — silently no-ops if permission is missing
        // or the label duplicates an existing entry.
        logSecondaryLocationOnForeground()
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
    // ── Chess.com Integration Methods ─────────────────────────────────────────

    /** Saves chess.com settings (enabled, username, minutes-per-increment). */
    fun saveChessComSettings(enabled: Boolean, username: String, minutesPerIncrement: Map<String, Int>) {
        viewModelScope.launch {
            settingsRepo.saveChessComSettings(enabled, username, minutesPerIncrement)
            _settings.value = _settings.value.copy(
                chessComEnabled = enabled,
                chessComUsername = username,
                chessComMinutesPerIncrement = minutesPerIncrement
            )
            // Start or stop polling based on enabled state
            if (enabled && username.isNotEmpty() && lastLoadedUri.isNotEmpty()) {
                startChessComPolling()
            } else {
                stopChessComPolling()
            }
        }
    }

    /** Links or unlinks a habit to a chess.com activity type. */
    fun setChessComHabitLink(habitName: String, chessComType: String?) {
        viewModelScope.launch {
            val links = _settings.value.chessComHabitLinks.toMutableMap()
            if (chessComType != null) {
                links[habitName] = chessComType
            } else {
                links.remove(habitName)
            }
            settingsRepo.saveChessComHabitLinks(links)
            _settings.value = _settings.value.copy(chessComHabitLinks = links)
        }
    }

    /** Starts the periodic chess.com polling loop. */
    private fun startChessComPolling() {
        // Cancel any existing polling job
        chessComPollingJob?.cancel()
        chessComPollingJob = viewModelScope.launch {
            while (true) {
                syncChessComCurrentMonth()
                delay(CHESS_COM_POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the chess.com polling loop. */
    private fun stopChessComPolling() {
        chessComPollingJob?.cancel()
        chessComPollingJob = null
    }

    /**
     * Fetches current month chess.com data and applies increments to linked habits.
     * Called periodically by the polling loop.
     */
    private suspend fun syncChessComCurrentMonth() {
        val s = _settings.value
        if (!s.chessComEnabled || s.chessComUsername.isEmpty()) return
        if (s.chessComHabitLinks.isEmpty()) return
        if (s.fileUri.isEmpty()) return

        try {
            _chessComSyncStatus.value = "Syncing chess.com data…"
            val monthData = chessComRepo.fetchCurrentMonthData(s.chessComUsername)
            applyChessComData(monthData, s)
            _chessComSyncStatus.value = "Last sync: ${java.time.LocalTime.now().toString().take(5)}"
        } catch (e: Exception) {
            Log.e(TAG, "Chess.com sync failed: ${e.message}")
            _chessComSyncStatus.value = "Sync failed: ${e.message?.take(50)}"
        }
    }

    /**
     * Fetches the entire chess.com game history and retroactively fills habit data.
     * Called from the Settings screen "Fetch Entire Backlog" button.
     */
    fun fetchChessComBacklog() {
        val s = _settings.value
        if (!s.chessComEnabled || s.chessComUsername.isEmpty()) {
            _chessComSyncStatus.value = "Enable chess.com and set username first"
            return
        }
        if (s.fileUri.isEmpty()) {
            _chessComSyncStatus.value = "Set habit database file first"
            return
        }

        viewModelScope.launch {
            try {
                // Clear cache so we get completely fresh data
                _chessComSyncStatus.value = "Clearing cache…"
                chessComRepo.clearCache()

                // Reset all chess.com-linked habits to 0 for all dates
                _chessComSyncStatus.value = "Resetting linked habit data…"
                resetChessComHabitData(s)

                _chessComSyncStatus.value = "Fetching entire backlog…"
                val allData = chessComRepo.fetchEntireBacklog(s.chessComUsername) { done, total ->
                    _chessComSyncStatus.value = "Fetching archives: $done / $total months"
                }
                _chessComSyncStatus.value = "Applying backlog data to habits…"
                applyChessComData(allData, s)
                _chessComSyncStatus.value = "Backlog complete! Data applied to linked habits."
            } catch (e: Exception) {
                Log.e(TAG, "Chess.com backlog failed: ${e.message}")
                _chessComSyncStatus.value = "Backlog failed: ${e.message?.take(80)}"
            }
        }
    }

    /**
     * Resets all chess.com-linked habit entries to 0 for every date.
     * Called before a full backlog re-fetch to ensure clean data.
     */
    private suspend fun resetChessComHabitData(s: AppSettings) {
        val phoneUriStr = s.fileUri
        if (phoneUriStr.isEmpty()) return

        val mutableDb = cachedPhoneDb.toMutableMap()
        var changed = false

        for ((habitName, _) in s.chessComHabitLinks) {
            val entries = mutableDb[habitName] ?: continue
            val resetEntries = entries.mapValues { 0 }.toSortedMap()
            if (resetEntries != entries) {
                mutableDb[habitName] = resetEntries
                changed = true
            }
        }

        if (changed) {
            cachedPhoneDb = mutableDb
            rebuildHabitList()
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(phoneUriStr), context, mutableDb)
            }
            Log.d(TAG, "Chess.com linked habits reset to 0")
        }
    }

    /**
     * Applies chess.com daily minutes data to linked habits in the database.
     * For each linked habit, computes increments from minutes and sets the daily count.
     * Chess.com data is authoritative — values are always overwritten (not max'd).
     *
     * Also propagates to conditional linked habits: if a chess-linked habit is configured
     * as a conditional habit, any date where its count goes from 0 → non-zero will also
     * set each conditional linked habit to at least 1 for that date.
     */
    private suspend fun applyChessComData(
        data: Map<ChessComType, Map<String, Double>>,
        s: AppSettings
    ) {
        if (data.isEmpty() || s.chessComHabitLinks.isEmpty()) return

        val phoneUriStr = s.fileUri
        if (phoneUriStr.isEmpty()) return

        var dbChanged = false
        val mutableDb = cachedPhoneDb.toMutableMap()
        // Track per-habit today-delta for timestamp recording (only add NEW timestamps)
        val todayStr = dateString(LocalDate.now())
        val todayDeltas = mutableMapOf<String, Int>()

        for ((habitName, typeKey) in s.chessComHabitLinks) {
            val type = ChessComType.fromKey(typeKey) ?: continue
            val dailyMinutes = data[type] ?: continue
            val minutesPerIncrement = s.chessComMinutesPerIncrement[typeKey] ?: continue
            if (minutesPerIncrement <= 0) continue

            val increments = chessComRepo.computeIncrements(dailyMinutes, minutesPerIncrement)
            if (increments.isEmpty()) continue

            val habitEntries = (mutableDb[habitName] ?: emptyMap()).toMutableMap()
            // Track per-date deltas for conditional propagation
            // (any date where count increased, not just 0→non-zero)
            val dateDeltas = mutableMapOf<String, Int>()

            for ((dateStr, count) in increments) {
                val existing = habitEntries[dateStr] ?: 0
                if (count != existing) {
                    val delta = count - existing
                    if (delta > 0) dateDeltas[dateStr] = delta
                    habitEntries[dateStr] = count
                    dbChanged = true
                }
            }
            mutableDb[habitName] = habitEntries.toSortedMap()

            // Track today's delta for timestamp recording
            val todayDelta = dateDeltas[todayStr]
            if (todayDelta != null && todayDelta > 0) {
                todayDeltas[habitName] = todayDelta
            }

            // Propagate to conditional linked habits for dates where count increased
            if (dateDeltas.isNotEmpty() && habitName in s.conditionalHabits) {
                val linkedHabits = s.conditionalLinkedHabits[habitName] ?: emptySet()
                for (linkedName in linkedHabits) {
                    val linkedEntries = (mutableDb[linkedName] ?: emptyMap()).toMutableMap()
                    for ((dateStr, delta) in dateDeltas) {
                        val existing = linkedEntries[dateStr] ?: 0
                        val newVal = if (linkedName in s.maxOneHabits) {
                            1
                        } else {
                            existing + delta
                        }
                        if (newVal != existing) {
                            linkedEntries[dateStr] = newVal
                            dbChanged = true
                        }
                    }
                    mutableDb[linkedName] = linkedEntries.toSortedMap()
                }
            }
        }

        if (dbChanged) {
            cachedPhoneDb = mutableDb
            rebuildHabitList()

            // Persist to disk
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(phoneUriStr), context, mutableDb)
            }

            // Record timestamps only for the NEW increments (delta), not the total count
            if (todayDeltas.isNotEmpty()) {
                val now = HabitTimestampRepository.nowTime()
                val today = LocalDate.now()
                for ((habitName, delta) in todayDeltas) {
                    timestampRepo.addTimestamps(habitName, delta, today, now)
                }
            }

            Log.d(TAG, "Chess.com data applied to habits")
        }
    }

    // ── Garmin Integration Methods ────────────────────────────────────────────

    /** Saves all Garmin settings at once (called from Settings screen). */
    fun saveGarminSettings(
        enabled: Boolean,
        proxyUrl: String,
        appToken: String,
        dateOfBirth: String,
        thresholds: Map<String, Int>
    ) {
        viewModelScope.launch {
            // Normalise the URL/token at the source so a stray trailing newline or
            // space (common when pasting) can never corrupt later URL parsing.
            val cleanProxyUrl = proxyUrl.trim().trimEnd('/')
            val cleanToken = appToken.trim()
            settingsRepo.saveGarminSettings(enabled, cleanProxyUrl, cleanToken, dateOfBirth, thresholds)
            _settings.value = _settings.value.copy(
                garminEnabled = enabled,
                garminProxyUrl = cleanProxyUrl,
                garminAppToken = cleanToken,
                garminDateOfBirth = dateOfBirth,
                garminThresholds = thresholds
            )
            // Start or stop polling based on enabled state
            if (enabled && proxyUrl.isNotEmpty() && appToken.isNotEmpty() && lastLoadedUri.isNotEmpty()) {
                startGarminPolling()
            } else {
                stopGarminPolling()
            }
        }
    }

    /** Links or unlinks a habit to a Garmin metric type. */
    fun setGarminHabitLink(habitName: String, garminType: String?) {
        viewModelScope.launch {
            val links = _settings.value.garminHabitLinks.toMutableMap()
            if (garminType != null) {
                links[habitName] = garminType
            } else {
                links.remove(habitName)
            }
            settingsRepo.saveGarminHabitLinks(links)
            _settings.value = _settings.value.copy(garminHabitLinks = links)
        }
    }

    /** Starts the periodic Garmin polling loop. */
    private fun startGarminPolling() {
        // Cancel any existing polling job
        garminPollingJob?.cancel()
        garminPollingJob = viewModelScope.launch {
            while (true) {
                syncGarminCurrentMonth()
                delay(GARMIN_POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the Garmin polling loop. */
    private fun stopGarminPolling() {
        garminPollingJob?.cancel()
        garminPollingJob = null
    }

    /**
     * Fetches current month Garmin data and applies increments to linked habits.
     * Called periodically by the polling loop.
     */
    private suspend fun syncGarminCurrentMonth() {
        val s = _settings.value
        if (!s.garminEnabled || s.garminProxyUrl.isEmpty() || s.garminAppToken.isEmpty()) return
        if (s.garminHabitLinks.isEmpty()) return
        if (s.fileUri.isEmpty()) return

        try {
            _garminSyncStatus.value = "Syncing Garmin data…"
            val monthData = garminRepo.fetchCurrentMonthData(s.garminProxyUrl, s.garminAppToken, s.garminDateOfBirth)
            val today = LocalDate.now().toString()
            Log.d(TAG, "Garmin sync: fetched types=${monthData.keys}, " +
                "links=${s.garminHabitLinks}, " +
                "todayValues=" + monthData.mapValues { it.value[today] })
            // Persist the freshly-fetched recent days to cache so they survive
            // restarts. The proxy/fetch pipeline is the source of truth for recent
            // days, so these values overwrite any stale cached value for the same date.
            withContext(Dispatchers.IO) { garminRepo.mergeAndCacheDailyData(monthData) }
            // MERGE into the displayed map — never REPLACE. Replacing here was the
            // bug that wiped historic "garmin value" fields down to the last 7 days.
            mergeIntoGarminMonthlyData(monthData)
            applyGarminData(monthData, s)
            _garminSyncStatus.value = "Last sync: ${java.time.LocalTime.now().toString().take(5)}"
        } catch (e: Exception) {
            Log.e(TAG, "Garmin sync failed: ${e.message}", e)
            _garminSyncStatus.value = "Sync failed: ${e.message?.take(50)}"
        }
    }

    /**
     * Merges freshly-fetched Garmin data into the displayed [_garminMonthlyData]
     * StateFlow WITHOUT discarding the historic backlog.
     *
     * This is the fix for the "all garmin values show '-'" regression: the 7-day
     * poll used to do `_garminMonthlyData.value = monthData`, which replaced the
     * full 5-year map with just the last week, so every older date rendered '-'.
     * Fresh values win for the dates they cover; every other date is preserved.
     */
    private fun mergeIntoGarminMonthlyData(fresh: Map<GarminType, Map<String, Int>>) {
        if (fresh.isEmpty()) return
        val merged = _garminMonthlyData.value.mapValues { it.value.toMutableMap() }.toMutableMap()
        for ((type, dayMap) in fresh) {
            val target = merged.getOrPut(type) { mutableMapOf() }
            for ((date, value) in dayMap) {
                target[date] = value
            }
        }
        _garminMonthlyData.value = merged.mapValues { it.value.toMap() }
    }

    /**
     * Fetches the entire Garmin health history and retroactively fills habit data.
     * Called from the Settings screen "Fetch Entire Backlog" button.
     */
    fun fetchGarminBacklog() {
        val s = _settings.value
        if (!s.garminEnabled || s.garminProxyUrl.isEmpty() || s.garminAppToken.isEmpty()) {
            _garminSyncStatus.value = "Enable Garmin and set connection settings first"
            return
        }
        if (s.fileUri.isEmpty()) {
            _garminSyncStatus.value = "Set habit database file first"
            return
        }

        viewModelScope.launch {
            try {
                // Clear cache so we get completely fresh data
                _garminSyncStatus.value = "Clearing cache…"
                garminRepo.clearCache()

                // Reset all Garmin-linked habits to 0 for all dates
                _garminSyncStatus.value = "Resetting linked habit data…"
                resetGarminHabitData(s)

                _garminSyncStatus.value = "Fetching entire backlog…"
                val allData = garminRepo.fetchEntireBacklog(
                    s.garminProxyUrl,
                    s.garminAppToken,
                    s.garminDateOfBirth
                ) { done, total ->
                    _garminSyncStatus.value = "Fetching archives: $done / $total months"
                }
                _garminSyncStatus.value = "Applying backlog data to habits…"
                // Merge (cache was already cleared above for a full refresh, so this
                // is effectively a fresh population, but merging keeps it consistent
                // with the poll path and avoids dropping any concurrently-loaded data).
                mergeIntoGarminMonthlyData(allData)
                applyGarminData(allData, s)
                _garminSyncStatus.value = "Backlog sync complete!"
            } catch (e: Exception) {
                Log.e(TAG, "Garmin backlog fetch failed: ${e.message}")
                _garminSyncStatus.value = "Failed: ${e.message?.take(50)}"
            }
        }
    }

    /**
     * Resets all Garmin-linked habits to 0 for all dates in the cached database.
     * Called before fetching backlog to avoid double-counting.
     */
    private suspend fun resetGarminHabitData(settings: AppSettings) {
        val linkedHabits = settings.garminHabitLinks.keys
        if (linkedHabits.isEmpty()) return

        val mutableDb = cachedPhoneDb.toMutableMap()
        var dbChanged = false

        for (habitName in linkedHabits) {
            if (habitName !in mutableDb) continue
            val habitData = mutableDb[habitName]!!.toMutableMap()
            for (date in habitData.keys) {
                habitData[date] = 0
                dbChanged = true
            }
            mutableDb[habitName] = habitData
        }

        if (dbChanged) {
            cachedPhoneDb = mutableDb
            rebuildHabitList()

            // Persist to disk
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(settings.fileUri), context, mutableDb)
            }
        }
    }

    /**
     * Tests the Garmin connection by performing a comprehensive health check.
     * Validates the full chain: proxy server, app token, Garmin API, and data availability.
     */
    fun testGarminConnection() {
        val s = _settings.value
        if (s.garminProxyUrl.isEmpty() || s.garminAppToken.isEmpty()) {
            _garminSyncStatus.value = "Set proxy URL and app token first"
            return
        }

        viewModelScope.launch {
            try {
                _garminSyncStatus.value = "Testing connection…"
                val result = garminRepo.performHealthCheck(s.garminProxyUrl, s.garminAppToken)
                
                if (result.success) {
                    val message = buildString {
                        append("✓ Connection successful!\n")
                        append("  Proxy: ${if (result.proxyRunning) "Running" else "Not running"}\n")
                        append("  Garmin: ${if (result.garminConnected) "Connected" else "Not connected"}\n")
                        if (result.dataAvailable) {
                            append("  Data: Available")
                        }
                    }
                    _garminSyncStatus.value = message

                    // After a successful test, immediately fetch and apply Garmin data
                    // to linked habits (in addition to the once-a-day automatic poll).
                    Log.d(TAG, "Garmin test ok: enabled=${s.garminEnabled}, " +
                        "links=${s.garminHabitLinks.size}, fileUriSet=${s.fileUri.isNotEmpty()}")
                    if (s.garminEnabled && s.garminHabitLinks.isNotEmpty() && s.fileUri.isNotEmpty()) {
                        syncGarminCurrentMonth()
                    } else {
                        Log.w(TAG, "Garmin sync skipped after test — guard not satisfied")
                    }
                } else {
                    _garminSyncStatus.value = "✗ Connection failed: ${result.message}"
                }
            } catch (e: Exception) {
                _garminSyncStatus.value = "✗ Error: ${e.message}"
            }
        }
    }

    /**
     * Applies Garmin data to linked habits in the database.
     * For each linked habit, computes increments based on the threshold and applies them.
     */
    private suspend fun applyGarminData(
        allData: Map<GarminType, Map<String, Int>>,
        settings: AppSettings
    ) {
        val linkedHabits = settings.garminHabitLinks
        if (linkedHabits.isEmpty()) return

        val mutableDb = cachedPhoneDb.toMutableMap()
        var dbChanged = false
        val todayDeltas = mutableMapOf<String, Int>()

        for ((habitName, garminTypeStr) in linkedHabits) {
            val garminType = GarminType.fromKey(garminTypeStr) ?: continue
            
            // For FITNESS_AGE_DISTANCE, calculate it on-demand from FITNESS_AGE
            val dailyValues = if (garminType == GarminType.FITNESS_AGE_DISTANCE) {
                try {
                    val fitnessAgeData = allData[GarminType.FITNESS_AGE] ?: emptyMap()
                    if (fitnessAgeData.isEmpty()) {
                        Log.w(TAG, "No fitness age data available to calculate fitness age distance")
                        emptyMap()
                    } else {
                        val dob = if (settings.garminDateOfBirth.isNotEmpty()) {
                            LocalDate.parse(settings.garminDateOfBirth)
                        } else {
                            // Fallback: use a reasonable default age (30 years old) based on first fitness age entry
                            val firstDate = fitnessAgeData.keys.first()
                            LocalDate.parse(firstDate).minusYears(30)
                        }
                        val distanceData = mutableMapOf<String, Int>()
                        
                        for ((dateStr, fitnessAge) in fitnessAgeData) {
                            val metricDate = LocalDate.parse(dateStr)
                            val biologicalAge = ChronoUnit.YEARS.between(dob, metricDate).toInt()
                            distanceData[dateStr] = fitnessAge - biologicalAge
                        }
                        
                        Log.d(TAG, "Calculated ${distanceData.size} fitness age distance values from ${fitnessAgeData.size} fitness age entries (DOB: $dob)")
                        distanceData.toMap()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to calculate fitness age distance: ${e.message}", e)
                    emptyMap()
                }
            } else {
                allData[garminType] ?: continue
            }
            
            if (dailyValues.isEmpty()) continue
            
            val threshold = settings.garminThresholds[garminTypeStr] ?: continue
            if (threshold == 0) continue  // Allow negative thresholds for FITNESS_AGE_DISTANCE

            Log.d(TAG, "Processing habit '$habitName' linked to $garminTypeStr, threshold=$threshold, values=${dailyValues.size}")

            // Ensure habit exists in DB
            if (habitName !in mutableDb) {
                mutableDb[habitName] = mutableMapOf()
            }

            // Custom point ranges (if enabled for this habit) map the raw Garmin
            // value directly to a points tier; otherwise we fall back to the simple
            // threshold → 0/1 rule.
            val useCustomRanges = habitName in settings.customPointRangesHabits
            val customRanges = settings.customPointRanges[habitName]

            val habitData = mutableDb[habitName]!!.toMutableMap()
            var appliedCount = 0
            for ((date, value) in dailyValues) {
                // Compute the points for this date DETERMINISTICALLY from the current
                // (read-only) Garmin value. We always write the computed result —
                // including 0 — so that a corrected value from the laptop proxy/fetch
                // pipeline flips the point both UP and DOWN. Previously we only wrote
                // on threshold-met, so a downward correction left a stale point.
                val newValue: Int = if (useCustomRanges && customRanges != null) {
                    com.example.tail.data.calculatePointsFromRanges(value, customRanges)
                } else {
                    val meetsThreshold = if (garminType == GarminType.FITNESS_AGE_DISTANCE) {
                        // For fitness age distance, more negative is better.
                        value <= threshold
                    } else {
                        value >= threshold
                    }
                    if (meetsThreshold) 1 else 0
                }

                val existing = habitData[date] ?: 0
                if (newValue != existing) {
                    habitData[date] = newValue
                    dbChanged = true
                    appliedCount++

                    // Track delta for today (for timestamp recording)
                    val today = LocalDate.now().toString()
                    if (date == today) {
                        val delta = newValue - existing
                        if (delta > 0) {
                            todayDeltas[habitName] = (todayDeltas[habitName] ?: 0) + delta
                        }
                    }
                }
            }
            Log.d(TAG, "Applied $appliedCount values for habit '$habitName'")
            mutableDb[habitName] = habitData
        }

        if (dbChanged) {
            cachedPhoneDb = mutableDb
            rebuildHabitList()

            // Persist to disk
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(settings.fileUri), context, mutableDb)
            }

            // Record timestamps only for the NEW increments (delta), not the total count
            if (todayDeltas.isNotEmpty()) {
                val now = HabitTimestampRepository.nowTime()
                val today = LocalDate.now()
                for ((habitName, delta) in todayDeltas) {
                    timestampRepo.addTimestamps(habitName, delta, today, now)
                }
            }

            Log.d(TAG, "Garmin data applied to habits")
        }
    }

    /**
     * Imports historic Garmin data from a JSON file generated by the desktop import script.
     * The JSON file should contain metrics in the same format as the cache files.
     *
     * @param jsonFile The JSON file containing the imported data
     * @param onComplete Called with the import result when complete
     */
    fun importGarminHistoricData(jsonFile: File, onComplete: (ImportResult) -> Unit = {}) {
        viewModelScope.launch {
            try {
                _garminSyncStatus.value = "Clearing old cache…"
                garminRepo.clearCache()
                
                _garminSyncStatus.value = "Importing historic data…"
                val result = garminRepo.importFromJson(jsonFile) { processed, total ->
                    _garminSyncStatus.value = "Processing: $processed / $total dates"
                }
                
                if (result.success) {
                    _garminSyncStatus.value = result.message
                    Log.d(TAG, "Import result: success=${result.success}, message=${result.message}")
                    
                    // Apply the imported data to linked habits
                    val s = _settings.value
                    Log.d(TAG, "Import: garminHabitLinks=${s.garminHabitLinks}, fileUri=${s.fileUri.isNotEmpty()}")
                    
                    if (s.garminHabitLinks.isNotEmpty() && s.fileUri.isNotEmpty()) {
                        // Load all imported data from cache using the new method
                        val allData = garminRepo.loadAllCachedData()
                        Log.d(TAG, "Import: Loaded ${allData.size} Garmin types from cache")
                        
                        if (allData.isNotEmpty()) {
                            _garminSyncStatus.value = "Applying data to habits…"
                            mergeIntoGarminMonthlyData(allData)
                            applyGarminData(allData, s)
                            _garminSyncStatus.value = "Import complete! Data applied to linked habits."
                            Log.d(TAG, "Import: Successfully applied data to ${allData.size} Garmin types")
                        } else {
                            _garminSyncStatus.value = "Import complete but no data found in cache."
                            Log.w(TAG, "Import: No data found in cache after import")
                        }
                    } else {
                        _garminSyncStatus.value = "Import complete but no habits linked or no file set."
                        Log.w(TAG, "Import: No habits linked (${s.garminHabitLinks.size}) or no file (${s.fileUri.isEmpty()})")
                    }
                } else {
                    _garminSyncStatus.value = "Import failed: ${result.message}"
                }
                
                onComplete(result)
            } catch (e: Exception) {
                Log.e(TAG, "Garmin historic import failed: ${e.message}", e)
                _garminSyncStatus.value = "Import failed: ${e.message?.take(50)}"
                onComplete(ImportResult(false, e.message ?: "Unknown error", emptyMap()))
            }
        }
    }

    // ── Voice Trigger Methods ────────────────────────────────────────────────

    /** Saves the global voice trigger enabled flag (called from Settings screen). */
    fun saveVoiceTriggerEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.saveVoiceTriggerEnabled(enabled)
            _settings.value = _settings.value.copy(voiceTriggerEnabled = enabled)
        }
    }

    /** Toggles the per-habit voice trigger on/off. */
    fun toggleVoiceTrigger(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.voiceTriggerHabits.toMutableSet()
            if (habitName in current) {
                current.remove(habitName)
                // Also clean up trigger words when disabling
                val words = _settings.value.voiceTriggerWords.toMutableMap()
                words.remove(habitName)
                settingsRepo.saveVoiceTriggerWords(words)
                _settings.value = _settings.value.copy(
                    voiceTriggerHabits = current,
                    voiceTriggerWords = words
                )
            } else {
                current.add(habitName)
                _settings.value = _settings.value.copy(voiceTriggerHabits = current)
            }
            settingsRepo.saveVoiceTriggerHabits(current)
        }
    }

    /** Sets the trigger words for a specific habit. */
    fun setVoiceTriggerWords(habitName: String, words: Set<String>) {
        viewModelScope.launch {
            val allWords = _settings.value.voiceTriggerWords.toMutableMap()
            if (words.isEmpty()) {
                allWords.remove(habitName)
            } else {
                allWords[habitName] = words
            }
            settingsRepo.saveVoiceTriggerWords(allWords)
            _settings.value = _settings.value.copy(voiceTriggerWords = allWords)
        }
    }

    /** Sets the fixed voice increment amount for a specific habit (0 or 1 = default). */
    fun setVoiceTriggerIncrement(habitName: String, amount: Int) {
        viewModelScope.launch {
            val allIncrements = _settings.value.voiceTriggerIncrements.toMutableMap()
            if (amount <= 1) {
                allIncrements.remove(habitName)
            } else {
                allIncrements[habitName] = amount
            }
            settingsRepo.saveVoiceTriggerIncrements(allIncrements)
            _settings.value = _settings.value.copy(voiceTriggerIncrements = allIncrements)
        }
    }

    /** Toggles the "use subtypes voice" feature on/off for [habitName]. */
    fun toggleVoiceSubtype(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.voiceSubtypeHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveVoiceSubtypeHabits(current)
            _settings.value = _settings.value.copy(voiceSubtypeHabits = current)
        }
    }

    // ── Voice Note Dictation Methods ─────────────────────────────────────────

    /** Saves the global voice note enabled flag (called from Settings screen). */
    fun saveVoiceNoteEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.saveVoiceNoteEnabled(enabled)
            _settings.value = _settings.value.copy(voiceNoteEnabled = enabled)
        }
    }

    /**
     * Re-attempts fetching today's location (called after the user grants permission).
     * No-op if the location is already stored for today.
     */
    fun refreshTodayLocation() {
        viewModelScope.launch {
            val result = locationRepo.fetchTodayIfNeeded()
            if (result != null && _selectedDate.value == LocalDate.now()) {
                _selectedDateLocation.value = result
            }
        }
    }

    /**
     * Manually saves a location label for the given date and refreshes the displayed value.
     *
     * Always forward-geocodes the new label so the world-map marker is updated to
     * match the new location, even if coords were already stored for this date
     * (e.g. the user corrected a previously wrong entry).
     */
    fun setLocationForDate(date: java.time.LocalDate, label: String) {
        locationRepo.setLocationForDate(date, label)
        if (_selectedDate.value == date) {
            _selectedDateLocation.value = label
        }
        // Always re-geocode on a manual edit so the map marker reflects the new label
        viewModelScope.launch {
            val coords = locationRepo.geocodeLocationLabel(label)
            if (coords != null) {
                locationRepo.setCoordsForDate(date, coords.first, coords.second)
            }
        }
    }

    /**
     * Removes the location label and coords for the given date, making it act
     * as if a location was never manually set. The day will then be assumed
     * to be at whatever the previous known location was.
     */
    fun removeLocationForDate(date: java.time.LocalDate) {
        locationRepo.removeLocationForDate(date)
        if (_selectedDate.value == date) {
            // Re-derive the location from the previous known day
            _selectedDateLocation.value = locationRepo.getLocationForDate(date)
        }
    }

    /**
     * Fetches a fresh GPS/network fix, reverse-geocodes it, and saves both
     * the label and coords for [date]. Calls [onComplete] when finished
     * (success or failure) so the caller can dismiss loading spinners.
     * Used by the "Auto Set" button in the location edit dialog.
     */
    fun fetchFreshLocationForDate(date: java.time.LocalDate, onComplete: () -> Unit) {
        viewModelScope.launch {
            try {
                val label = locationRepo.fetchFreshLocationForDate(date)
                if (label != null && _selectedDate.value == date) {
                    _selectedDateLocation.value = label
                }
            } catch (_: Exception) { /* already logged by repo */ }
            onComplete()
        }
    }

    /**
     * Generates multiple candidate location names for [date] by using the
     * stored coords (or fetching fresh ones if none exist). Returns a list
     * of candidate labels ordered from most specific to least specific.
     * The GPS coords are NOT changed — only the display label varies.
     * Used by the cycling "auto" button in the location edit dialog.
     */
    fun fetchLocationCandidates(date: java.time.LocalDate, onResult: (List<String>) -> Unit) {
        viewModelScope.launch {
            val candidates = try {
                // Use existing coords if available, otherwise fetch fresh
                var coords = locationRepo.getCoordsForDate(date)
                if (coords == null) {
                    // Fetch fresh location (this also saves coords + label)
                    val label = locationRepo.fetchFreshLocationForDate(date)
                    if (label != null && _selectedDate.value == date) {
                        _selectedDateLocation.value = label
                    }
                    coords = locationRepo.getCoordsForDate(date)
                }
                if (coords != null) {
                    locationRepo.generateLocationCandidates(coords.first, coords.second)
                } else {
                    emptyList()
                }
            } catch (_: Exception) { /* already logged by repo */ emptyList() }
            onResult(candidates)
        }
    }

    /** Saves the user's preferred auto-detected location candidate. */
    /**
     * Saves the index of the candidate the user chose from the auto list.
     * On the next day, [fetchTodayIfNeeded] will re-run candidate generation
     * with fresh GPS data and pick the same positional slot.
     */
    fun savePreferredAutoCandidateIndex(index: Int) {
        locationRepo.savePreferredAutoCandidateIndex(index)
    }

    /** Returns all previously stored location labels (for the edit dialog suggestions). */
    fun getAllStoredLocations(): List<String> = locationRepo.getAllStoredLocations()

    /** Returns the full date-string → label map in one SharedPrefs read. */
    fun getAllStoredLabels(): Map<String, String> = locationRepo.getAllStoredLabels()

    // ── World-map screen helpers ─────────────────────────────────────────────

    /** Returns (lat, lon) for [date] if known, else null. */
    fun getCoordsForDate(date: LocalDate): Pair<Double, Double>? =
        locationRepo.getCoordsForDate(date)

    /** Manually sets (lat, lon) for [date]. Used for coordinate editing from the map. */
    fun setCoordsForDate(date: LocalDate, lat: Double, lon: Double) =
        locationRepo.setCoordsForDate(date, lat, lon)

    /** Returns the location label stored for [date] (or null). */
    fun getLocationLabelForDate(date: LocalDate): String? =
        locationRepo.getLocationForDate(date)

    /**
     * Returns the assumed location label for [date] — the most recent
     * preceding stored label when no exact entry exists for [date].
     * Returns null only if no preceding labels exist at all.
     *
     * Only looks at dates strictly before [date], so setting a location
     * for one day never changes the assumed location for earlier days.
     */
    fun getAssumedLocationForDate(date: LocalDate): String? {
        val allLabels = locationRepo.getAllStoredLabels()
        return allLabels.entries
            .mapNotNull { (k, v) ->
                runCatching { LocalDate.parse(k) }.getOrNull()?.let { it to v }
            }
            .filter { (d, _) -> d.isBefore(date) }
            .maxByOrNull { (d, _) -> d }
            ?.second
    }

    /** Returns all date-strings for which we have plottable coords, sorted ascending. */
    fun getDatesWithCoords(): List<String> =
        locationRepo.getAllStoredCoords().keys.sorted()

    /**
     * Returns ALL stored coords as a map of [LocalDate] → (lat, lon) in ONE
     * SharedPrefs read + ONE JSON parse pass. Used by the world-map screen so
     * we don't pay per-date parse cost (which would freeze the UI thread for
     * thousands of entries).
     */
    fun getAllStoredCoordsParsed(): Map<LocalDate, Pair<Double, Double>> {
        val raw = locationRepo.getAllStoredCoords()
        val out = HashMap<LocalDate, Pair<Double, Double>>(raw.size)
        for ((dateStr, coord) in raw) {
            val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
            out[d] = coord
        }
        return out
    }

    /**
     * One-shot, off-thread snapshot of (date, country) pairs for every stored
     * location label, sorted ascending by date. Used by the world-map screen
     * to compute "countries visited up to date X" in O(N) without re-parsing
     * SharedPrefs on every slider tick.
     *
     * Country names that match an entry in the user-managed ignore list
     * (seeded with US states on first run) are excluded from the count.
     *
     * Pair caller with [locationDataVersion] to know when to rebuild this
     * snapshot (the value bumps whenever a label or coords entry is saved).
     */
    fun buildCountryTimeline(): List<Pair<LocalDate, String>> {
        val labels = locationRepo.getAllStoredLabels()  // single SharedPrefs read
        val ignored = locationRepo.getIgnoredCountryNames()
        val out = ArrayList<Pair<LocalDate, String>>(labels.size)
        for ((dateStr, label) in labels) {
            val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
            val country = extractCountry(label, ignored) ?: continue
            out.add(d to country)
        }
        out.sortBy { it.first }
        return out
    }

    /** Returns the current set of country/region names excluded from the country count. */
    fun getIgnoredCountryNames(): Set<String> = locationRepo.getIgnoredCountryNames()

    /** Adds [name] to the ignored-country set (persisted). Bumps locationDataVersion. */
    fun addIgnoredCountryName(name: String) = locationRepo.addIgnoredCountryName(name)

    /** Removes [name] from the ignored-country set (persisted). Bumps locationDataVersion. */
    fun removeIgnoredCountryName(name: String) = locationRepo.removeIgnoredCountryName(name)

    /**
     * Current data version of the location store. Bumped on every save.
     * The map screen recomputes its country cache when this changes.
     */
    val locationDataVersion: Int
        get() = locationRepo.dataVersion

    // ── Secondary locations ─────────────────────────────────────────────────

    /** Returns secondary locations for a specific date. */
    fun getSecondaryLocationsForDate(date: LocalDate): List<SecondaryLocation> =
        locationRepo.getSecondaryLocationsForDate(date)

    /** Returns ALL secondary locations as a map of date-string → list. */
    fun getAllSecondaryLocations(): Map<String, List<SecondaryLocation>> =
        locationRepo.getAllSecondaryLocations()

    /**
     * Logs the current GPS position as a secondary location for today.
     * Called when the app is foregrounded. Silently no-ops if location
     * permission is not granted or the label duplicates an existing entry.
     */
    fun logSecondaryLocationOnForeground() {
        viewModelScope.launch {
            try {
                locationRepo.logCurrentPositionAsSecondary()
            } catch (e: Exception) {
                Log.w(TAG, "logSecondaryLocationOnForeground failed: ${e.message}")
            }
        }
    }

    /**
     * Manually adds a secondary location for [date] by forward-geocoding
     * a pasted address (e.g. from Google Maps). Returns the resolved label
     * on success, or null on failure. Runs on Dispatchers.IO.
     */
    suspend fun addManualSecondaryLocation(date: LocalDate, address: String, timeMinutes: Int = java.time.LocalTime.now().toSecondOfDay() / 60): String? {
        return withContext(Dispatchers.IO) {
            try {
                locationRepo.addManualSecondaryLocation(date, address, timeMinutes)
            } catch (e: Exception) {
                Log.w(TAG, "addManualSecondaryLocation failed: ${e.message}")
                null
            }
        }
    }

    fun removeSecondaryLocation(date: LocalDate, index: Int) {
        locationRepo.removeSecondaryLocation(date, index)
    }

    fun updateSecondaryLocationTime(date: LocalDate, index: Int, newTimeMinutes: Int) {
        locationRepo.updateSecondaryLocationTime(date, index, newTimeMinutes)
    }

    /**
     * Returns the list of habits done on [date] with their point values,
     * sorted descending by points. Only habits with points > 0 are included.
     */
    fun getDayHabitBreakdown(date: LocalDate): List<Pair<String, Int>> {
        val db = cachedPhoneDb
        val dateStr = dateString(date)
        val dividers = _settings.value.habitDividers
        val tracked = trackedHabitNames().ifEmpty { db.keys }
        return tracked
            .mapNotNull { name ->
                val raw = db[name]?.get(dateStr) ?: 0
                if (raw > 0) Pair(name, applyDivider(raw, dividers[name] ?: 1)) else null
            }
            .sortedByDescending { it.second }
    }

    /**
     * Returns the earliest date for which a location label is stored.
     * Used by the calendar picker to set the minimum selectable year.
     */
    fun getEarliestLocationDate(): LocalDate? {
        val allCoords = locationRepo.getAllStoredCoords()
        return allCoords.keys
            .mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            .minOrNull()
    }

    /**
     * Fast (cheap) stats for [date]: only total points and 30-day monthly average.
     * No streak computation. Used on every slider tick for accent colour updates.
     */
    fun getDayStatsLight(date: LocalDate): DayStats {
        val db = cachedPhoneDb
        val dateStr = dateString(date)
        val dividers = _settings.value.habitDividers
        val tracked = trackedHabitNames().ifEmpty { db.keys }

        var totalPoints = 0
        for (name in tracked) {
            val raw = db[name]?.get(dateStr) ?: 0
            if (raw > 0) totalPoints += applyDivider(raw, dividers[name] ?: 1)
        }

        var monthlySum = 0
        for (i in 0 until 30) {
            val ds = dateString(date.minusDays(i.toLong()))
            for (name in tracked) {
                val raw = db[name]?.get(ds) ?: 0
                if (raw > 0) monthlySum += applyDivider(raw, dividers[name] ?: 1)
            }
        }
        val monthlyAverage = monthlySum.toDouble() / 30.0

        return DayStats(
            date = date,
            totalPoints = totalPoints,
            monthlyAverage = monthlyAverage,
            streakDays = 0,
            antiStreakDays = 0
        )
    }

    /**
     * Full stats for [date]: total points, 30-day monthly average, and per-habit
     * streak/anti-streak totals. Expensive — only call when the user has paused
     * on a day (debounced in the UI).
     *
     * Streak/anti-streak are computed as the sum of each tracked habit's individual
     * streak/anti-streak ending on [date], matching computeAppStats behaviour.
     * Entries are capped at [date] so historical days are not affected by today's data.
     */
    fun getDayStats(date: LocalDate): DayStats {
        val db = cachedPhoneDb
        val dateStr = dateString(date)
        val dividers = _settings.value.habitDividers
        val tracked = trackedHabitNames().ifEmpty { db.keys }

        var totalPoints = 0
        for (name in tracked) {
            val raw = db[name]?.get(dateStr) ?: 0
            if (raw > 0) totalPoints += applyDivider(raw, dividers[name] ?: 1)
        }

        var monthlySum = 0
        for (i in 0 until 30) {
            val ds = dateString(date.minusDays(i.toLong()))
            for (name in tracked) {
                val raw = db[name]?.get(ds) ?: 0
                if (raw > 0) monthlySum += applyDivider(raw, dividers[name] ?: 1)
            }
        }
        val monthlyAverage = monthlySum.toDouble() / 30.0

        // Per-habit streak/anti-streak totals ending on [date].
        // Entries are filtered to <= dateStr so the "end" of the reversed list
        // is always [date], not today (fixes anti-streak stuck on today's value).
        var totalStreakDays = 0
        var totalAntiStreakDays = 0
        for (name in tracked) {
            val entries = db[name] ?: continue
            // Cap entries at [date] — only include days up to and including [date]
            val capped = entries.filter { it.key <= dateStr }.toMutableMap()
            if (capped.isEmpty()) continue
            // Ensure [date] itself is present (as 0 if not recorded)
            if (!capped.containsKey(dateStr)) capped[dateStr] = 0
            val expanded = expandEntriesToCalendarDaysPublic(capped)
            val reversed = expanded.entries.sortedBy { it.key }.reversed()

            var habStreak = 0
            for (entry in reversed) {
                if (applyDivider(entry.value, dividers[name] ?: 1) > 0) habStreak++ else break
            }
            var habAntiStreak = 0
            for (entry in reversed) {
                if (applyDivider(entry.value, dividers[name] ?: 1) == 0) habAntiStreak++ else break
            }

            totalStreakDays += habStreak
            totalAntiStreakDays += habAntiStreak
        }

        return DayStats(
            date = date,
            totalPoints = totalPoints,
            monthlyAverage = monthlyAverage,
            streakDays = totalStreakDays,
            antiStreakDays = totalAntiStreakDays
        )
    }

    /**
     * All habit names that appear on any screen (or in habitOrder if no screens),
     * EXCLUDING habits flagged "Don't affect points" (noPointsHabits).
     *
     * Used by the point/total calculations behind the world-map day stats
     * (getDayStats / getDayStatsLight / getDayHabitBreakdown). Garmin-imported
     * metric habits (steps, altitude, distance, …) live on real screens but store
     * raw metric values; including them here inflated the map's daily / weekly /
     * monthly totals so heavily that every day saturated to the top colour tier
     * (all-white map). Excluding noPointsHabits keeps these totals consistent with
     * the in-app stats (computeAppStats / getDailyTotals), which already exclude them.
     */
    private fun trackedHabitNames(): Set<String> {
        val s = _settings.value
        val noPoints = s.noPointsHabits
        val fromScreens = s.habitScreens.flatMap { it.habitNames }.toSet()
        val base = if (fromScreens.isNotEmpty()) fromScreens
                   else s.habitOrder.toSet().ifEmpty { cachedPhoneDb.keys }
        return base - noPoints
    }

    /** Saves the SAF URI for the voice note markdown file. */
    fun saveVoiceNoteFileUri(uri: String) {
        viewModelScope.launch {
            settingsRepo.saveVoiceNoteFileUri(uri)
            _settings.value = _settings.value.copy(voiceNoteFileUri = uri)
        }
    }

    /**
     * Toggles custom point ranges on/off for [habitName].
     * When enabled, the habit's points are calculated based on which range
     * the "true value" or "garmin value" falls into.
     */
    fun toggleCustomPointRanges(habitName: String) {
        val current = _settings.value.customPointRangesHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
            val ranges = _settings.value.customPointRanges.toMutableMap()
            ranges.remove(habitName)
            _settings.value = _settings.value.copy(
                customPointRangesHabits = current,
                customPointRanges = ranges
            )
            viewModelScope.launch {
                settingsRepo.saveCustomPointRangesHabits(current)
                settingsRepo.saveCustomPointRanges(ranges)
            }
        } else {
            current.add(habitName)
            val ranges = _settings.value.customPointRanges.toMutableMap()
            if (habitName !in ranges) {
                ranges[habitName] = List(7) { com.example.tail.data.PointRange() }
            }
            _settings.value = _settings.value.copy(
                customPointRangesHabits = current,
                customPointRanges = ranges
            )
            viewModelScope.launch {
                settingsRepo.saveCustomPointRangesHabits(current)
                settingsRepo.saveCustomPointRanges(ranges)
                recalculateHabitPointsForCustomRanges(habitName)
            }
        }
    }

    /**
     * Sets the custom point ranges for [habitName].
     * When ranges change, all historical entries for the habit are recalculated.
     */
    fun setCustomPointRanges(habitName: String, ranges: List<com.example.tail.data.PointRange>) {
        val rangesMap = _settings.value.customPointRanges.toMutableMap()
        rangesMap[habitName] = ranges
        _settings.value = _settings.value.copy(customPointRanges = rangesMap)
        viewModelScope.launch {
            settingsRepo.saveCustomPointRanges(rangesMap)
            recalculateHabitPointsForCustomRanges(habitName)
        }
    }

    /**
     * Recalculates all historical entries for [habitName] based on custom point ranges.
     * This is called when custom point ranges are enabled or modified.
     */
    private suspend fun recalculateHabitPointsForCustomRanges(habitName: String) {
        val settings = _settings.value
        if (habitName !in settings.customPointRangesHabits) return

        val ranges = settings.customPointRanges[habitName] ?: return
        val uri = settings.fileUri
        if (uri.isEmpty()) return

        val loadResult = habitsRepo.loadDatabaseResult(
            android.net.Uri.parse(uri),
            context
        )
        if (loadResult !is com.example.tail.data.HabitsLoadResult.Success) return

        val db = loadResult.db.toMutableMap()
        val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
        if (habitEntries.isEmpty()) return

        val isGarminLinked = habitName in settings.garminHabitLinks
        val isDivider = (settings.habitDividers[habitName] ?: 1) > 1

        for ((dateStr, rawCount) in habitEntries) {
            val trueValue: Int = when {
                isGarminLinked -> {
                    val garminType = com.example.tail.data.GarminType.fromKey(settings.garminHabitLinks[habitName]!!)
                    val monthlyData = _garminMonthlyData.value[garminType]
                    monthlyData?.get(dateStr) ?: rawCount
                }
                isDivider -> rawCount
                else -> rawCount
            }

            val newPoints = com.example.tail.data.calculatePointsFromRanges(trueValue, ranges)
            habitEntries[dateStr] = newPoints
        }

        db[habitName] = habitEntries.toSortedMap()

        habitsRepo.saveDatabase(
            android.net.Uri.parse(uri),
            context,
            db
        )

        cachedPhoneDb = db
        rebuildHabitList()
    }
}

class HabitViewModelFactory(
    private val habitsRepo: HabitsRepository,
    private val settingsRepo: SettingsRepository,
    private val textInputRepo: TextInputRepository,
    private val datedEntryRepo: DatedEntryRepository,
    private val subtypeDataRepo: SubtypeDataRepository,
    private val timedDataRepo: TimedDataRepository,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HabitViewModel(habitsRepo, settingsRepo, textInputRepo, datedEntryRepo, subtypeDataRepo, timedDataRepo, context) as T
    }
}
