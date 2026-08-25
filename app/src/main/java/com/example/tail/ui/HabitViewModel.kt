package com.example.tail.ui

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

/**
 * Parses one media text-log entry written by MediaPlaybackTracker:
 * `"HH:mm Title — Artist/Show (NN min) — mediaUri"` (artist, duration and
 * URI segments are all optional). Group1 = title, group2 = artist/show,
 * group3 = duration minutes. Used by the per-show removal breakdown.
 */
private val MEDIA_LOG_ENTRY_REGEX = Regex(
    "^\\d{1,2}:\\d{2}\\s+(.+?)(?:\\s+—\\s+(.+?))?(?:\\s+\\((\\d+)\\s*min\\))?(?:\\s+—\\s+.*)?$"
)

// Resonance-breathing secondary-value migration: pre-2026-08-08 primary values at or
// below this are legacy session counts; anything larger is real backfilled minutes.
private const val MAX_LEGACY_RESONANCE_SESSION_COUNT = 3
private const val BRIDGE_PORT = 8001

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
    val raw = parts.last()
    // Canonicalise aliases (e.g. "USA" → "United States") so variants of the
    // same country aggregate into a single entry — shared with the stats screen.
    val country = com.example.tail.ui.map.canonicalCountryName(raw)
    // Case-insensitive check: the ignore list stores properly-capitalised names
    // (e.g. "Massachusetts") but location labels may vary in casing. Both the
    // raw and canonical spellings are checked so canonicalisation can never
    // un-ignore a country.
    if (ignoredNames.any { it.equals(raw, ignoreCase = true) || it.equals(country, ignoreCase = true) }) return null
    return country
}

// ── IPC broadcast constants ──────────────────────────────────────────────────
/** Broadcast action sent after every successful habit increment. */
const val ACTION_HABIT_INCREMENTED = "com.example.tail.ACTION_HABIT_INCREMENTED"
/** String extra: the name of the habit that was incremented. */
const val EXTRA_HABIT_NAME = "EXTRA_HABIT_NAME"
/**
 * Int extra: the count delta that was actually applied to the habit.
 * 0 means the increment was a no-op (e.g. a max-1 cap) or a minutes-only
 * adjustment — count-based listeners should ignore those.
 */
const val EXTRA_AMOUNT = "EXTRA_AMOUNT"
/**
 * String extra: the package name of the app that originated the increment,
 * present only when the increment arrived via an external IPC broadcast.
 * Propagated on the outbound broadcast so the originator can recognise and
 * ignore its own echo (prevents VILD ⇄ Tail increment loops).
 */
const val EXTRA_SOURCE = "EXTRA_SOURCE"

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
    private val backupManager: BackupManager? = null,
    private val locationRepo: LocationRepository = LocationRepository(context)
) : ViewModel() {
    
    // Cache for text entries used in graph filtering
    private val _textEntriesCache = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val textEntriesCache: StateFlow<Map<String, Map<String, String>>> = _textEntriesCache.asStateFlow()

    /** Repository for recording habit increment timestamps (internal storage). */
    val timestampRepo = HabitTimestampRepository(context)

    // ── Habit-ask notifications (system + in-app center + one-time flash) ───
    /** Single source of truth for pending asks; system notifications mirror it. */
    val notificationStore = NotificationStore(context)

    /** Pending asks, oldest first. Mirrored from [notificationStore]. */
    private val _notifications = MutableStateFlow<List<HabitNotification>>(emptyList())
    val notifications: StateFlow<List<HabitNotification>> = _notifications.asStateFlow()

    // ── Meal Habit Engine ─────────────────────────────────────────────────
    /** Repository for meal log entries and images (internal storage). */
    val mealLogRepo = com.example.tail.data.meal.MealLogRepository(context)
    /** Repository for the offline vision-processing queue (internal storage). */
    val visionQueueRepo = com.example.tail.data.meal.VisionQueueRepository(context)
    /** Repository for the LLM vision memory (user-taught image→habit associations). */
    val visionMemoryRepo = com.example.tail.data.meal.VisionMemoryRepository(context)

    /** Learned vision memory entries (newest-first), for the Settings screen. */
    private val _visionMemoryEntries = MutableStateFlow<List<com.example.tail.data.meal.VisionMemoryEntry>>(emptyList())
    val visionMemoryEntries: StateFlow<List<com.example.tail.data.meal.VisionMemoryEntry>> =
        _visionMemoryEntries.asStateFlow()

    /** Meal logs for the currently-opened meal habit (newest-first). */
    private val _mealLogsForHabit = MutableStateFlow<List<com.example.tail.data.meal.MealLog>>(emptyList())
    val mealLogsForHabit: StateFlow<List<com.example.tail.data.meal.MealLog>> = _mealLogsForHabit.asStateFlow()

    /** Today's total calories for the currently-opened meal habit. */
    private val _mealTodayCalories = MutableStateFlow(0)
    val mealTodayCalories: StateFlow<Int> = _mealTodayCalories.asStateFlow()

    /** Count of pending items in the vision queue (for UI badge). */
    private val _mealPendingCount = MutableStateFlow(0)
    val mealPendingCount: StateFlow<Int> = _mealPendingCount.asStateFlow()

    /** Status of the last voice-meal parse / photo queue action (null = idle). */
    private val _mealVoiceStatus = MutableStateFlow<String?>(null)
    val mealVoiceStatus: StateFlow<String?> = _mealVoiceStatus.asStateFlow()

    /** Vision endpoint test result (null = not tested, empty = testing, non-empty = result). */
    data class MealTestState(
        val isTesting: Boolean = false,
        val isSuccess: Boolean = false,
        val message: String = ""
    )
    private val _mealTestState = MutableStateFlow(MealTestState())
    val mealTestState: StateFlow<MealTestState> = _mealTestState.asStateFlow()

    // ── Global habit search ──────────────────────────────────────────────────

    /** Persists the last query + filters so search state survives app restarts. */
    private val searchStateStore = SearchStateStore(context)

    /**
     * Current search query text. Lives in the ViewModel (not dialog-local
     * state) so closing the search popup — including by tapping a result —
     * preserves its exact state for the next time the search icon is pressed.
     * The last state is also persisted via [SearchStateStore] and restored
     * on app start.
     */
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Habit names included in the search (filter section). */
    private val _searchFilters = MutableStateFlow<Set<String>>(emptySet())
    val searchFilters: StateFlow<Set<String>> = _searchFilters.asStateFlow()

    /**
     * True once the filter set carries real state — either restored from
     * [SearchStateStore] or defaulted to "all". Until then an empty filter
     * set means "not initialised yet", never "user deselected everything".
     */
    private var searchFiltersInitialized = false

    init {
        searchStateStore.load()?.let { saved ->
            _searchQuery.value = saved.query
            _searchFilters.value = saved.filters
            searchFiltersInitialized = true
        }
    }

    /** Habits that have any searchable text, for the filter section. */
    private val _searchableHabits = MutableStateFlow<List<SearchableHabitInfo>>(emptyList())
    val searchableHabits: StateFlow<List<SearchableHabitInfo>> = _searchableHabits.asStateFlow()

    /** Latest search hits, sorted by relevance then date. */
    private val _searchResults = MutableStateFlow<List<HabitSearchResult>>(emptyList())
    val searchResults: StateFlow<List<HabitSearchResult>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var searchJob: Job? = null

    /**
     * Recomputes the list of habits with searchable text. Defaults the
     * filter to "all" on first run only; afterwards a persisted selection
     * (even an empty one) is honoured, minus habits that no longer exist.
     */
    fun refreshSearchableHabits() {
        val list = HabitSearcher.searchableHabits(_settings.value)
        _searchableHabits.value = list
        val names = list.map { it.habitName }.toSet()
        if (!searchFiltersInitialized) {
            _searchFilters.value = names
            searchFiltersInitialized = true
        } else {
            // Drop filters for habits that were renamed or removed meanwhile.
            val effective = _searchFilters.value intersect names
            if (effective.size != _searchFilters.value.size) _searchFilters.value = effective
        }
        // A restored query with no in-memory results (fresh app start) re-runs
        // so the dialog reopens showing its previous hits.
        if (_searchQuery.value.isNotBlank() && _searchResults.value.isEmpty()) {
            rerunSearchIfActive()
        }
    }

    /** Updates the query and runs a debounced fuzzy search across all text-bearing habits. */
    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        persistSearchState()
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            _isSearching.value = false
            return
        }
        searchJob = viewModelScope.launch {
            delay(300) // debounce keystrokes
            performSearch(query)
        }
    }

    /** Includes/excludes one habit from the search, then re-runs the active query. */
    fun toggleSearchFilter(habitName: String) {
        val current = _searchFilters.value
        _searchFilters.value = if (habitName in current) current - habitName else current + habitName
        persistSearchState()
        rerunSearchIfActive()
    }

    /** Re-selects every searchable habit, then re-runs the active query. */
    fun setAllSearchFilters() {
        _searchFilters.value = _searchableHabits.value.map { it.habitName }.toSet()
        persistSearchState()
        rerunSearchIfActive()
    }

    /** Deselects every habit filter, then re-runs the active query. */
    fun clearSearchFilters() {
        _searchFilters.value = emptySet()
        persistSearchState()
        rerunSearchIfActive()
    }

    private fun persistSearchState() {
        searchStateStore.save(_searchQuery.value, _searchFilters.value)
    }

    private fun rerunSearchIfActive() {
        val q = _searchQuery.value
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { performSearch(q) }
    }

    private suspend fun performSearch(query: String) {
        _isSearching.value = true
        try {
            _searchResults.value = HabitSearcher.search(
                context = context,
                settings = _settings.value,
                textInputRepo = textInputRepo,
                mealLogRepo = mealLogRepo,
                query = query,
                allowedHabits = _searchFilters.value
            )
        } finally {
            _isSearching.value = false
        }
    }

    // ── Habit highlight (search-result "you are here" pulse) ────────────────

    /** Name of the habit whose grid cell should pulse, or null for none. */
    private val _highlightedHabit = MutableStateFlow<String?>(null)
    val highlightedHabit: StateFlow<String?> = _highlightedHabit.asStateFlow()

    private var highlightJob: Job? = null

    /** Pulses the given habit's grid cell for a couple of seconds (e.g. after a search jump). */
    fun highlightHabit(habitName: String) {
        _highlightedHabit.value = habitName
        highlightJob?.cancel()
        highlightJob = viewModelScope.launch {
            delay(2500)
            _highlightedHabit.value = null
        }
    }

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

    /**
     * Today's total habit points (sum of effective per-habit counts for the selected
     * date). Updated in [rebuildHabitList] and retained across loads so the tiered
     * loading spinner can reflect the current day's colour even while a fresh load
     * is in progress (when [habits] is momentarily stale/empty).
     */
    private val _todayPoints = MutableStateFlow(0)
    val todayPoints: StateFlow<Int> = _todayPoints.asStateFlow()

    /**
     * Triple-metric stats (monthly avg, weekly avg, today's points) for "The
     * Orrery" loading animation. Retained across loads — exactly like
     * [todayPoints] — so the animation stays correct even mid-load.
     * Updated together with [todayPoints] in [rebuildHabitList].
     *
     * The initial value is hydrated synchronously from a small
     * SharedPreferences cache (see [readCachedLoadingMetrics]) so a cold
     * start shows likely-correct tiers immediately, before the DB loads.
     */
    private val metricsPrefs = context.getSharedPreferences("loading_metrics_cache", Context.MODE_PRIVATE)

    private val _loadingMetrics = MutableStateFlow(readCachedLoadingMetrics())
    val loadingMetrics: StateFlow<LoadingMetrics> = _loadingMetrics.asStateFlow()

    /**
     * Last persisted metrics for the cold-start hydration. The averages move
     * slowly (1/30th and 1/7th daily weight) so they stay valid across
     * midnight; the daily total is only trusted when the cache was written
     * today — otherwise the spark starts dormant rather than wrong.
     */
    private fun readCachedLoadingMetrics(): LoadingMetrics {
        val month = metricsPrefs.getFloat("monthly_avg", 0f).toDouble()
        val week = metricsPrefs.getFloat("weekly_avg", 0f).toDouble()
        val day = metricsPrefs.getInt("today_points", 0)
        val cachedDate = metricsPrefs.getString("date", null)
        return if (cachedDate == LocalDate.now().toString()) {
            LoadingMetrics(month, week, day)
        } else {
            LoadingMetrics(month, week, 0)
        }
    }

    /** Persists metrics computed for [date] (only ever called for today). */
    private fun cacheLoadingMetrics(m: LoadingMetrics, date: LocalDate) {
        metricsPrefs.edit()
            .putFloat("monthly_avg", m.monthlyAverage.toFloat())
            .putFloat("weekly_avg", m.weeklyAverage.toFloat())
            .putInt("today_points", m.todayPoints)
            .putString("date", date.toString())
            .apply()
    }

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

    /**
     * Habit names with an AI icon generation currently in flight. The habit
     * tile shows a spinner while its name is in this set, so the user can
     * leave the icon picker and still see progress in the grid.
     */
    private val _aiIconPendingHabits = MutableStateFlow<Set<String>>(emptySet())
    val aiIconPendingHabits: StateFlow<Set<String>> = _aiIconPendingHabits.asStateFlow()

    /**
     * One-shot user-facing messages about background AI icon generation
     * (started / applied / failed), consumed as toasts by the grid screen.
     */
    private val _aiIconMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val aiIconMessages: SharedFlow<String> = _aiIconMessages.asSharedFlow()

    // ── Chess.com Integration ─────────────────────────────────────────────────
    private val chessComRepo = ChessComRepository(context)

    /** Status message for chess.com sync operations (shown in settings). */
    private val _chessComSyncStatus = MutableStateFlow("")
    val chessComSyncStatus: StateFlow<String> = _chessComSyncStatus.asStateFlow()

    /** Job for the periodic chess.com polling loop. */
    private var chessComPollingJob: Job? = null

    /** Interval between chess.com polls (15 minutes). */
    private val CHESS_COM_POLL_INTERVAL_MS = 15 * 60 * 1000L

    // ── GitHub Integration ────────────────────────────────────────────────────
    private val githubRepo = GitHubRepository(context)

    /** Status message for GitHub sync operations (shown in edit panel + settings). */
    private val _githubSyncStatus = MutableStateFlow("")
    val githubSyncStatus: StateFlow<String> = _githubSyncStatus.asStateFlow()

    /** Job for the periodic GitHub polling loop. */
    private var githubPollingJob: Job? = null

    /** Interval between GitHub polls (30 minutes — GitHub API is rate-limited). */
    private val GITHUB_POLL_INTERVAL_MS = 30 * 60 * 1000L

    /**
     * In-memory cache of all four GitHub metrics per day, keyed by habit name.
     * Populated during [fetchGithubBacklog] so the graph can display every
     * metric simultaneously without additional API calls.
     */
    private var _githubDailyCache: Map<String, Map<String, GitHubRepository.GithubDailyMetrics>> = emptyMap()

    /**
     * In-memory cache of per-day commit messages (formatted "sha message"),
     * keyed by habit name then date. Populated during [fetchGithubBacklog] and
     * the periodic recent sync; used by the graph to list the actual commit
     * messages when the "Commits" metric is selected.
     */
    private var _githubCommitMessages: Map<String, Map<String, List<String>>> = emptyMap()

    /** Returns true if [habitName] is linked to a GitHub repository. */
    fun isGithubHabit(habitName: String): Boolean {
        return habitName in _settings.value.githubRepoUrls
    }

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

    // ── Tail Bridge Integration (Movies + future tethered features) ──────────
    private val movieBridgeService = MovieBridgeService()

    /** Status message for bridge operations (shown in settings). */
    private val _bridgeStatus = MutableStateFlow("")
    val bridgeStatus: StateFlow<String> = _bridgeStatus.asStateFlow()

    /**
     * The latest movie suggestion fetched from the desktop bridge.
     * Non-null while a movie confirm dialog is showing. Set back to null
     * when the dialog is dismissed.
     */
    private val _movieSuggestion = MutableStateFlow<BridgeMovie?>(null)
    val movieSuggestion: StateFlow<BridgeMovie?> = _movieSuggestion.asStateFlow()

    // ── OMDb / IMDb ratings integration ────────────────────────────────────
    private val omdbService = OmdbService()
    private val imdbCache = ImdbRatingCache(context)

    /** Status message for OMDb operations (shown in settings). */
    private val _omdbStatus = MutableStateFlow("")
    val omdbStatus: StateFlow<String> = _omdbStatus.asStateFlow()

    /** True while the IMDb backlog fetch is running. */
    private val _omdbBacklogRunning = MutableStateFlow(false)
    val omdbBacklogRunning: StateFlow<Boolean> = _omdbBacklogRunning.asStateFlow()

    // Track the last loaded URI to avoid reloading on every settings emission
    private var lastLoadedUri: String = ""

    // Debounce job for day navigation — cancelled on each new arrow tap so we only
    // rebuild the habit list after the user has settled on a date for a moment.
    private var navDebounceJob: Job? = null
    private val NAV_DEBOUNCE_MS = 800L

    // Flag to suppress settingsFlow reaction while we're saving a new habit order / screens
    private var isSavingOrder: Boolean = false

    // Flag to suppress settingsFlow reaction while we're saving the active screen index
    // This prevents a race condition where switching screens triggers a settings emission
    // that overwrites the user's choice back to the previous screen
    @Volatile
    private var isSavingScreenIndex: Boolean = false

    // Cache the full unified DB so we can rebuild the habit list without re-reading the file
    private var cachedPhoneDb: HabitsDatabase = emptyMap()

    // TRUE only after the phone DB has been successfully loaded from disk at least
    // once this session. Background sync writers (chess.com, Garmin) MUST NOT
    // persist cachedPhoneDb while this is false -- otherwise a startup race (or a
    // transient load failure during a Syncthing write) lets them build a DB from
    // an empty cache and clobber the real file (the 2026-07-19 wipe root cause).
    @Volatile
    private var dbLoaded: Boolean = false

    // Tracks the date on which roll forward was last performed.
    // This ensures we only roll forward once per day, not on every DB load.
    private var rollForwardLastDate: LocalDate? = null

    // Per-screen habit list cache — avoids expensive rebuildHabitList() on every screen switch.
    // Keyed by (screen index, selected date) so switching between screens on the same date is instant.
    private val screenHabitCache = mutableMapOf<Pair<Int, LocalDate>, List<Habit>>()

    /** Serializes [rebuildHabitList] runs. Rebuilds are launched from many
     *  asynchronous triggers (screen switches, HabitIncrementBus events,
     *  Garmin/Chess/GitHub syncs, …) and each one publishes the whole habit
     *  list; without serialization an older snapshot can finish last and
     *  clobber the optimistic increment update (the "tap records the
     *  timestamp but the square only updates after switching screens" bug). */
    private val rebuildMutex = Mutex()

    /** Public read-only access to the cached database for stats computation. */
    fun getCachedDatabase(): HabitsDatabase = cachedPhoneDb

    init {
        // Mirror the pending habit-ask notifications into UI state. Answers
        // from ANY surface (flash, in-app center, system notification) remove
        // the record here, so every surface updates automatically.
        viewModelScope.launch {
            try {
                notificationStore.notificationsFlow.collect { asks ->
                    _notifications.value = asks
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to collect notifications: ${e.message}")
            }
        }

        // Load AI icons from disk on startup
        refreshAiIcons()

        // One-time import of the legacy external subtype/timed per-habit files
        // into the internal stores (no-op once the migration flag is set).
        viewModelScope.launch(Dispatchers.IO) {
            try {
                SubtypeTimedMigrator.runIfNeeded(context)
            } catch (e: Exception) {
                Log.w(TAG, "Subtype/timed internalization migration failed: ${e.message}")
            }
        }

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

        // Load cached GitHub daily metrics on startup so all four GitHub graph
        // metrics (lines/commits/additions/deletions) survive process restarts.
        // Without this, only the primary metric (persisted in the habits DB as
        // value1) is available after a restart and the rest appear "forgotten"
        // until a manual backlog re-fetch.
        viewModelScope.launch {
            try {
                val cached = withContext(Dispatchers.IO) { githubRepo.loadDailyMetricsCache() }
                if (cached.isNotEmpty() && _githubDailyCache.isEmpty()) {
                    _githubDailyCache = cached
                    Log.d(TAG, "Loaded GitHub daily metrics cache for ${cached.size} habits")
                }
                val cachedMsgs = withContext(Dispatchers.IO) { githubRepo.loadCommitMessagesCache() }
                if (cachedMsgs.isNotEmpty() && _githubCommitMessages.isEmpty()) {
                    _githubCommitMessages = cachedMsgs
                    Log.d(TAG, "Loaded GitHub commit messages cache for ${cachedMsgs.size} habits")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load cached GitHub daily metrics: ${e.message}")
            }
        }

        // Collect in-process increment events from SmartVoiceService / IPC receivers
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
                        dbLoaded = true
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

            // Drop orphaned conditional-link entries (key no longer marked conditional)
            // so they don't surface as phantom "Fed by" sources in edit mode.
            settingsRepo.pruneOrphanedConditionalLinks()

            var widgetTriggerServiceChecked = false
            settingsRepo.settingsFlow.collect { s ->
                _settings.value = s

                // On first load, ensure the widget-trigger monitoring service
                // is running if any trigger apps are configured (e.g. after a
                // device reboot or app force-stop).
                if (!widgetTriggerServiceChecked) {
                    widgetTriggerServiceChecked = true
                    val triggerCount = s.widgetTriggerApps.values.count { it.isNotBlank() } +
                        if (s.chessReadinessEnabled && s.chessReadinessApp.isNotBlank()) 1 else 0
                    if (triggerCount > 0) {
                        com.example.tail.widget.WidgetTriggerService.updateServiceState(context, triggerCount)
                    }

                    // (Removed Aug-22-2026) The legacy one-time setup that gave
                    // widget-trigger habits the "minutes" secondary value +
                    // points fallback + minutes-primary default. Superseded by
                    // the first-class minutes slot (Aug-18) — timer features now
                    // enable minutes directly, and this block only re-created
                    // stale legacy state the minutes-slot migration had removed.
                }


                if (!isSavingOrder && !isSavingScreenIndex) {
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

                    // ── One-time meditation data import ───────────────────────
                    // Runs HERE (not in onAppForegrounded) because the fileUri is
                    // guaranteed to be loaded and the DB has just been read from disk.
                    try {
                        val meditationFile = File(context.filesDir, "meditation_import.json")
                        if (meditationFile.exists()) {
                            Log.d(TAG, "Found meditation_import.json, importing…")
                            val jsonText = meditationFile.readText()
                            meditationFile.renameTo(File(context.filesDir, "meditation_imported.json"))

                            val root = org.json.JSONObject(jsonText)
                            val daily = root.optJSONObject("daily")
                            if (daily != null) {
                                val mutableDb = cachedPhoneDb.toMutableMap()
                                val pk = "Meditations"
                                val sk = secondaryValueKey(pk)
                                if (pk !in mutableDb) mutableDb[pk] = emptyMap()
                                if (sk !in mutableDb) mutableDb[sk] = emptyMap()
                                val pEntries = mutableDb[pk]!!.toMutableMap()
                                val sEntries = mutableDb[sk]!!.toMutableMap()
                                var minAdded = 0
                                var sesAdded = 0
                                val dates = daily.keys()
                                while (dates.hasNext()) {
                                    val ds = dates.next()
                                    val di = daily.optJSONObject(ds) ?: continue
                                    val gm = di.optInt("minutes", 0)
                                    val gs = di.optInt("sessions", 0)
                                    if (gm > pEntries.getOrDefault(ds, 0)) { pEntries[ds] = gm; minAdded++ }
                                    if (gs > sEntries.getOrDefault(ds, 0)) { sEntries[ds] = gs; sesAdded++ }
                                }
                                mutableDb[pk] = pEntries
                                mutableDb[sk] = sEntries
                                cachedPhoneDb = mutableDb
                                rebuildHabitList()
                                withContext(Dispatchers.IO) {
                                    habitsRepo.persistDatabase(Uri.parse(s.fileUri), context, mutableDb)
                                }
                                Log.d(TAG, "Meditation import complete: $minAdded min entries, $sesAdded session entries. Persisted to ${s.fileUri}")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Meditation import failed: ${e.message}")
                    }

                    // After the DB is loaded, sync any dated-entry habits.
                    if (s.datedEntryHabits.isNotEmpty()) {
                        syncAllDatedEntries(forceReparse = false)
                    }
                    // Write the relay file on startup so the PC widget always has
                    // the latest screen layout AND icon assignments.
                    if (s.screensRelayFileUri.isNotEmpty() && s.habitScreens.isNotEmpty()) {
                        writeScreensRelayFile(s.habitScreens, s.activeScreenIndex, s.screensRelayFileUri)
                    }
                    // Refresh the PC floating-widget config on startup and apply any
                    // PC habit events that queued up while the app was closed.
                    if (s.garminProxyUrl.isNotEmpty()) {
                        pushPcWidgetConfig()
                        withContext(Dispatchers.IO) {
                            PcEventQueueProcessor(context).processOnce()
                        }
                    }
                    // Start chess.com polling if enabled
                    if (s.chessComEnabled && s.chessComUsername.isNotEmpty()) {
                        startChessComPolling()
                    }
                    // Start GitHub polling if enabled and at least one habit is linked
                    if (s.githubEnabled && s.githubRepoUrls.isNotEmpty()) {
                        startGithubPolling()
                    }

                    // Auto-link Garmin habits that have cached data but no link.
                    // This repairs the case where a habit (e.g. "Garmin Swim") was
                    // created but never linked to its GarminType (e.g. SWIM_MINUTES).
                    // Runs in a separate coroutine so it doesn't block the UI.
                    viewModelScope.launch {
                        try {
                            val cachedData = withContext(Dispatchers.IO) {
                                garminRepo.loadAllCachedData()
                            }
                            if (cachedData.isNotEmpty()) {
                                Log.d(TAG, "Init auto-link: checking ${cachedData.size} Garmin types, " +
                                    "current links=${_settings.value.garminHabitLinks.size}")
                                val updatedSettings = autoLinkMissingGarminHabits(cachedData)
                                applyGarminData(cachedData, updatedSettings)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Init auto-link failed: ${e.message}")
                        }
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

    /**
     * One-time migration: moves pre-2026-03-12 session counts from the primary
     * slot to the secondary_value slot for "Apnea apb" and "Apnea practiced".
     *
     * Before wags integration, these habits stored session counts (1-5) in the
     * primary slot. After March 12, 2026, wags started writing minutes there.
     * This migration moves old session-count data to secondary_value so the
     * fallback mechanism can use it for points on days with 0 minutes.
     */
    private suspend fun performApneaSecondaryMigration(uri: Uri) {
        val cutoff = "2026-03-12"
        val habitsToMigrate = listOf("Apnea apb", "Apnea practiced")
        var totalMoved = 0

        val db = cachedPhoneDb.toMutableMap()
        for (habitName in habitsToMigrate) {
            val primary = db[habitName] ?: continue
            val secKey = com.example.tail.data.secondaryValueKey(habitName)
            val secondary = db[secKey]?.toMutableMap() ?: mutableMapOf()
            val mutablePrimary = primary.toMutableMap()

            for ((dateStr, value) in primary) {
                if (dateStr >= cutoff) continue
                if (value <= 0) continue

                // Move to secondary (max merge)
                secondary[dateStr] = maxOf(secondary[dateStr] ?: 0, value)
                // Zero out primary
                mutablePrimary[dateStr] = 0
                totalMoved++
            }

            db[habitName] = mutablePrimary
            db[secKey] = secondary
        }

        if (totalMoved > 0) {
            Log.i(TAG, "Apnea secondary migration: moved $totalMoved dates from primary → secondary (pre-$cutoff)")
            cachedPhoneDb = db
            habitsRepo.persistDatabase(uri, context, db)
        }

        settingsRepo.setApneaSecondaryMigrationDone()
        Log.i(TAG, "Apnea secondary migration complete.")
    }

    /**
     * One-time migration: moves pre-2026-08-08 session counts from the primary
     * slot to the secondary_value slot for "Resonance Breathing".
     *
     * Before 2026-08-08, wags wrote session counts (+1 per session) to the
     * primary slot. After that date, wags writes minutes there. The wags
     * backfill has already replaced the primary values with minutes for every
     * date that has a resonance/RF record, so the only stale session counts
     * remaining are SMALL values (≤ 3) on pre-cutoff dates — days with no wags
     * record (manual increments or sessions whose wags record is gone).
     *
     * Unlike the apnea migration (which moves ALL pre-cutoff values), this one
     * only moves values ≤ [MAX_LEGACY_SESSION_COUNT] because larger pre-cutoff
     * values are legitimate backfilled minutes.
     *
     * Also auto-enables the secondary-value track for the habit so Value 2
     * (sessions) is visible in the UI, mirroring Meditations.
     */
    private suspend fun performResonanceSecondaryMigration(uri: Uri) {
        val cutoff = "2026-08-08"
        val habitName = "Resonance Breathing"
        var totalMoved = 0

        val db = cachedPhoneDb.toMutableMap()
        val primary = db[habitName]
        if (primary != null) {
            val secKey = com.example.tail.data.secondaryValueKey(habitName)
            val secondary = db[secKey]?.toMutableMap() ?: mutableMapOf()
            val mutablePrimary = primary.toMutableMap()

            for ((dateStr, value) in primary) {
                if (dateStr >= cutoff) continue
                if (value <= 0) continue
                if (value > MAX_LEGACY_RESONANCE_SESSION_COUNT) continue // real backfilled minutes

                // Move to secondary (max merge)
                secondary[dateStr] = maxOf(secondary[dateStr] ?: 0, value)
                // Zero out primary
                mutablePrimary[dateStr] = 0
                totalMoved++
            }

            db[habitName] = mutablePrimary
            db[secKey] = secondary
        }

        if (totalMoved > 0) {
            Log.i(TAG, "Resonance secondary migration: moved $totalMoved dates from primary → secondary " +
                    "(pre-$cutoff, values ≤ $MAX_LEGACY_RESONANCE_SESSION_COUNT)")
            cachedPhoneDb = db
            habitsRepo.persistDatabase(uri, context, db)
        }

        // Ensure the secondary-value track is enabled so Value 2 (sessions) shows
        // up in the grid/graph — same setup the user has for Meditations.
        if (habitName !in _settings.value.secondaryValueHabits) {
            val updated = _settings.value.secondaryValueHabits + habitName
            _settings.value = _settings.value.copy(secondaryValueHabits = updated)
            settingsRepo.saveSecondaryValueHabits(updated)
        }

        settingsRepo.setResonanceSecondaryMigrationDone()
        Log.i(TAG, "Resonance secondary migration complete.")
    }

    /**
     * One-time migration to the FIRST-CLASS MINUTES slot (`minutes:<habit>`).
     *
     * Timer-based features (phone bubble, PC widget, trigger apps, media
     * tracking, chess readiness) used to write minutes into the GENERIC
     * secondary-value slot (`secondary_value:<habit>`), which required per-habit
     * setup plus manual "Minutes" display labels. Every habit now has a real
     * minutes slot automatically — no setup, semantically known by the app.
     *
     * For every habit fed by those timer features this moves the
     * `secondary_value:` data into `minutes:` (max-merge), removes the stale
     * legacy settings (secondary-value membership, fallback membership,
     * Sessions/Minutes display labels) and keeps minutes-primary habits
     * minutes-primary. "Good Posture" is additionally switched to
     * minutes-primary (user request, 2026-08-18).
     *
     * Habits that use the generic secondary slot for OTHER data (Meditations/
     * Apnea/Resonance session counts, chess.com games, JugCoach seconds, IMDb
     * ratings) are NOT touched. Idempotent: safe to re-run.
     */
    private suspend fun performMinutesSlotMigration(uri: Uri) {
        try {
            val s = _settings.value
            val chessLinked = setOf(
                com.example.tail.widget.ChessReadinessStore.linkedPuzzleHabit(context),
                com.example.tail.widget.ChessReadinessStore.linkedRushHabit(context)
            ).filter { it.isNotBlank() }
            val timerFed = s.widgetTimerMinutesPrimary +
                s.pcWidgetHabits +
                s.widgetTriggerApps.values.toSet() +
                s.mediaHabits +
                chessLinked +
                setOf("Good Posture")
            val db = cachedPhoneDb.toMutableMap()
            var changed = false
            val secHabits = s.secondaryValueHabits.toMutableSet()
            val fallback = s.secondaryValueFallbackHabits.toMutableSet()
            val labels = s.valueDisplayLabels.toMutableMap()
            val minutesPrimary = s.widgetTimerMinutesPrimary.toMutableSet()
            for (habit in timerFed) {
                val secKey = secondaryValueKey(habit)
                val secData = db[secKey] ?: continue
                val minKey = minutesKey(habit)
                val merged = (db[minKey] ?: emptyMap()).toMutableMap()
                for ((d, v) in secData) merged[d] = maxOf(merged[d] ?: 0, v)
                db[minKey] = merged
                db.remove(secKey)
                changed = true
                secHabits.remove(habit)
                fallback.remove(habit)
                labels.remove(habit)
                if (habit == "Good Posture") minutesPrimary.add(habit)
            }
            if (changed) {
                habitsRepo.saveDatabase(uri, context, db)
                cachedPhoneDb = db
            }
            settingsRepo.saveSecondaryValueHabits(secHabits)
            settingsRepo.saveSecondaryValueFallbackHabits(fallback)
            settingsRepo.saveValueDisplayLabels(labels)
            settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
            _settings.value = _settings.value.copy(
                secondaryValueHabits = secHabits,
                secondaryValueFallbackHabits = fallback,
                valueDisplayLabels = labels,
                widgetTimerMinutesPrimary = minutesPrimary
            )
            settingsRepo.setMinutesSlotMigrationDone()
            Log.i(TAG, "performMinutesSlotMigration: done")
        } catch (e: Exception) {
            Log.e(TAG, "performMinutesSlotMigration failed (will retry next load): ${e.message}")
        }
    }

    /**
     * One-time initialisation of the per-habit minutes-enabled set
     * ([com.example.tail.data.AppSettings.minutesEnabledHabits]).
     *
     * Before the minutes toggle existed, every habit implicitly had minutes.
     * To keep the effective state identical on day one, the explicit set is
     * seeded with every habit that is connected to a timer feature (PC
     * widget, phone bubble trigger, media tracker), is minutes-primary, or
     * already has data in its `minutes:` slot — minus max-1 habits, which
     * never have minutes. Habits with no minutes data and no timer
     * connection start with minutes OFF (the new default).
     */
    private suspend fun performMinutesToggleInit() {
        try {
            val s = _settings.value
            val withData = cachedPhoneDb.keys
                .filter { com.example.tail.data.isMinutesKey(it) }
                .mapNotNull { minutesHabitName(it) }
                .filter { !cachedPhoneDb[minutesKey(it)].isNullOrEmpty() }
            val derived = (
                s.widgetTimerMinutesPrimary +
                    s.pcWidgetHabits +
                    s.widgetTriggerHabits +
                    s.mediaHabits +
                    s.bridgeMovieHabits +
                    withData
                ) - s.maxOneHabits
            settingsRepo.saveMinutesEnabledHabits(derived)
            _settings.value = _settings.value.copy(minutesEnabledHabits = derived)
            settingsRepo.setMinutesToggleInitDone()
            Log.i(TAG, "performMinutesToggleInit: ${derived.size} habits start with minutes enabled")
        } catch (e: Exception) {
            Log.e(TAG, "performMinutesToggleInit failed (will retry next load): ${e.message}")
        }
    }

    /**
     * One-time backfill: every habit connected to a timer widget (PC widget,
     * phone bubble trigger), a media tracker, the movie bridge, or using
     * minutes as its primary value gets its EXPLICIT minutes toggle turned
     * ON. Runs after [performMinutesToggleInit] so habits connected after
     * the initial seeding are also covered.
     */
    private suspend fun performMinutesWidgetBackfill() {
        try {
            val s = _settings.value
            val forced = (
                s.pcWidgetHabits +
                    s.widgetTriggerHabits +
                    s.mediaHabits +
                    s.bridgeMovieHabits +
                    s.widgetTimerMinutesPrimary
                ) - s.maxOneHabits
            val merged = s.minutesEnabledHabits + forced
            settingsRepo.saveMinutesEnabledHabits(merged)
            _settings.value = _settings.value.copy(minutesEnabledHabits = merged)
            settingsRepo.setMinutesWidgetBackfillDone()
            Log.i(TAG, "performMinutesWidgetBackfill: ${merged.size} habits now have minutes enabled")
        } catch (e: Exception) {
            Log.e(TAG, "performMinutesWidgetBackfill failed (will retry next load): ${e.message}")
        }
    }

    /**
     * One-time repair for Wags-fed habits wrongly classified as minutes-primary
     * by the Aug-18-2026 minutes-slot rollout.
     *
     * The Wags IPC protocol stores MINUTES in the primary key and SESSIONS in
     * the legacy `secondary_value:` slot. The minutes-primary role instead
     * expects minutes in the first-class `minutes:` slot — which Wags never
     * writes — so points fell back to the raw undivided primary minutes
     * (16 min ÷ divider 10 showed as 16 points) and the sessions metric
     * disappeared from the graph.
     *
     * This restores the pre-breakage Meditations/Resonance pattern for every
     * false minutes-primary habit
     * ([com.example.tail.data.falseMinutesPrimaryHabits]):
     * • minutes stay in the primary key (Value1; the divider applies for
     *   points),
     * • sessions stay in `secondary_value:` (Value2 metric + zero-minutes
     *   fallback for points — preserving the legacy Apnea apb / Apnea spb /
     *   Apnea practiced session history),
     * • any stray `minutes:` slot data is max-merged into the primary key so
     *   nothing entered via the minutes editor is lost.
     */
    private suspend fun performWagsMinutesPrimaryRepair(uri: Uri) {
        try {
            val s = _settings.value
            val chessLinked = setOf(
                com.example.tail.widget.ChessReadinessStore.linkedPuzzleHabit(context),
                com.example.tail.widget.ChessReadinessStore.linkedRushHabit(context)
            ).filter { it.isNotBlank() }
            val targets = com.example.tail.data.falseMinutesPrimaryHabits(
                widgetTimerMinutesPrimary = s.widgetTimerMinutesPrimary,
                pcWidgetHabits = s.pcWidgetHabits,
                widgetTriggerHabits = s.widgetTriggerHabits,
                mediaHabits = s.mediaHabits,
                bridgeMovieHabits = s.bridgeMovieHabits,
                chessLinked = chessLinked.toSet(),
                db = cachedPhoneDb
            )
            if (targets.isEmpty()) {
                settingsRepo.setWagsMinutesPrimaryRepairDone()
                Log.i(TAG, "performWagsMinutesPrimaryRepair: nothing to repair")
                return
            }
            // Max-merge stray minutes: data into the primary key, then drop
            // the slot so no hand-entered minutes are orphaned.
            val db = cachedPhoneDb.toMutableMap()
            var dbChanged = false
            for (habit in targets) {
                val stray = db[minutesKey(habit)] ?: continue
                val merged = (db[habit] ?: emptyMap()).toMutableMap()
                for ((d, v) in stray) merged[d] = maxOf(merged[d] ?: 0, v)
                db[habit] = merged
                db.remove(minutesKey(habit))
                dbChanged = true
            }
            if (dbChanged) {
                habitsRepo.saveDatabase(uri, context, db)
                cachedPhoneDb = db
            }
            val minutesPrimary = s.widgetTimerMinutesPrimary - targets
            val secHabits = s.secondaryValueHabits + targets
            val fallback = s.secondaryValueFallbackHabits + targets
            val minutesEnabled = s.minutesEnabledHabits - targets
            val primaryFallbacks = s.minutesPrimaryFallbacks - targets
            val labels = s.valueDisplayLabels.toMutableMap()
            for (habit in targets) {
                val inner = labels[habit]?.toMutableMap() ?: mutableMapOf()
                if (inner[GRAPH_METRIC_VALUE1].isNullOrBlank()) {
                    inner[GRAPH_METRIC_VALUE1] = "minutes"
                }
                if (inner[GRAPH_METRIC_VALUE2].isNullOrBlank()) {
                    inner[GRAPH_METRIC_VALUE2] = "sessions"
                }
                labels[habit] = inner
            }
            settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
            settingsRepo.saveSecondaryValueHabits(secHabits)
            settingsRepo.saveSecondaryValueFallbackHabits(fallback)
            settingsRepo.saveMinutesEnabledHabits(minutesEnabled)
            settingsRepo.saveMinutesPrimaryFallbacks(primaryFallbacks)
            settingsRepo.saveValueDisplayLabels(labels)
            _settings.value = _settings.value.copy(
                widgetTimerMinutesPrimary = minutesPrimary,
                secondaryValueHabits = secHabits,
                secondaryValueFallbackHabits = fallback,
                minutesEnabledHabits = minutesEnabled,
                minutesPrimaryFallbacks = primaryFallbacks,
                valueDisplayLabels = labels
            )
            settingsRepo.setWagsMinutesPrimaryRepairDone()
            Log.i(
                TAG,
                "performWagsMinutesPrimaryRepair: repaired ${targets.size} Wags-fed habits: ${targets.sorted()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "performWagsMinutesPrimaryRepair failed (will retry next load): ${e.message}")
        }
    }

    /**
     * One-time repair (Aug-23-2026) for habits broken by the graph long-press
     * "Minutes value" migration: that action MOVED the primary-key history
     * into the first-class `minutes:` slot and DELETED the primary key, which
     * blanks the graph for every metric (the graph loader needs the primary
     * key to exist). Most visibly hit Garmin-linked habits — e.g. a Sleep
     * Length habit whose raw minute values live in the Garmin cache while the
     * JSON key only held the derived per-day points.
     *
     * For every broken habit ([com.example.tail.data.brokenMinutesMigrationHabits]):
     * • the migrated `minutes:` data is max-merged back into the primary key
     *   (nothing entered between breakage and repair is lost), then the slot
     *   is dropped;
     * • the minutes-primary and minutes-enabled flags are cleared;
     * • the graph metric selection is pointed back at Value1 so the restored
     *   history (or the live Garmin series) is visible immediately.
     */
    private suspend fun performBrokenMinutesMigrationRepair(uri: Uri) {
        try {
            val s = _settings.value
            val chessLinked = setOf(
                com.example.tail.widget.ChessReadinessStore.linkedPuzzleHabit(context),
                com.example.tail.widget.ChessReadinessStore.linkedRushHabit(context)
            ).filter { it.isNotBlank() }
            val targets = com.example.tail.data.brokenMinutesMigrationHabits(
                widgetTimerMinutesPrimary = s.widgetTimerMinutesPrimary,
                garminHabitLinks = s.garminHabitLinks,
                pcWidgetHabits = s.pcWidgetHabits,
                widgetTriggerHabits = s.widgetTriggerHabits,
                mediaHabits = s.mediaHabits,
                bridgeMovieHabits = s.bridgeMovieHabits,
                chessLinked = chessLinked.toSet(),
                db = cachedPhoneDb
            )
            if (targets.isEmpty()) {
                settingsRepo.setBrokenMinutesMigrationRepairDone()
                Log.i(TAG, "performBrokenMinutesMigrationRepair: nothing to repair")
                return
            }
            // Max-merge the migrated minutes-slot data back into the primary
            // key, then drop the slot so no data is orphaned.
            val db = cachedPhoneDb.toMutableMap()
            var dbChanged = false
            for (habit in targets) {
                val stray = db[minutesKey(habit)] ?: continue
                val merged = (db[habit] ?: emptyMap()).toMutableMap()
                for ((d, v) in stray) merged[d] = maxOf(merged[d] ?: 0, v)
                db[habit] = merged
                db.remove(minutesKey(habit))
                dbChanged = true
            }
            if (dbChanged) {
                habitsRepo.saveDatabase(uri, context, db)
                cachedPhoneDb = db
            }
            val minutesPrimary = s.widgetTimerMinutesPrimary - targets
            val minutesEnabled = s.minutesEnabledHabits - targets
            settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
            settingsRepo.saveMinutesEnabledHabits(minutesEnabled)
            // Point the graph back at Value1 so the restored history (or the
            // live Garmin series) shows immediately.
            val selection = s.graphMetricSelection.toMutableMap()
            var selectionChanged = false
            for (habit in targets) {
                val metrics = selection[habit]?.toMutableSet() ?: continue
                if (GRAPH_METRIC_MINUTES in metrics) {
                    metrics.remove(GRAPH_METRIC_MINUTES)
                    metrics.add(GRAPH_METRIC_VALUE1)
                    selection[habit] = metrics
                    selectionChanged = true
                }
            }
            if (selectionChanged) {
                settingsRepo.saveGraphMetricSelection(selection)
            }
            _settings.value = _settings.value.copy(
                widgetTimerMinutesPrimary = minutesPrimary,
                minutesEnabledHabits = minutesEnabled,
                graphMetricSelection = selection
            )
            settingsRepo.setBrokenMinutesMigrationRepairDone()
            Log.i(
                TAG,
                "performBrokenMinutesMigrationRepair: restored ${targets.size} habits: ${targets.sorted()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "performBrokenMinutesMigrationRepair failed (will retry next load): ${e.message}")
        }
    }

    /**
     * One-time migration (Aug-21-2026): converts the five Wags-fed apnea
     * habits ([com.example.tail.data.APNEA_SESSIONS_PRIMARY_HABITS]) from the
     * legacy Wags layout (minutes = primary value with divider + sessions in
     * the `secondary_value:` slot with points fallback) to SESSIONS-PRIMARY:
     *
     *  • sessions become the PRIMARY value and the sole points source — the
     *    divider and the points fallback are removed, so points = sessions;
     *  • minutes move to the first-class `minutes:` slot with the built-in
     *    minutes value type enabled, so charts keep showing them;
     *  • the secondary-value feature and its legacy slot are dropped.
     *
     * Data swap via [com.example.tail.data.swapToSessionsPrimary]; days with
     * recorded minutes but no session entry get sessions = 1 so no day loses
     * its done/points status. Runs AFTER [performWagsMinutesPrimaryRepair] so
     * stray minutes-slot data has already been merged into the primary key.
     */
    private suspend fun performApneaSessionsPrimaryMigration(uri: Uri) {
        try {
            val targets = com.example.tail.data.APNEA_SESSIONS_PRIMARY_HABITS
            val swapped = com.example.tail.data.swapToSessionsPrimary(cachedPhoneDb, targets)
            if (swapped != cachedPhoneDb) {
                habitsRepo.saveDatabase(uri, context, swapped)
                cachedPhoneDb = swapped
            }
            val s = _settings.value
            val secHabits = s.secondaryValueHabits - targets
            val fallback = s.secondaryValueFallbackHabits - targets
            val minutesEnabled = s.minutesEnabledHabits + targets
            val minutesPrimary = s.widgetTimerMinutesPrimary - targets
            val mpFallbacks = s.minutesPrimaryFallbacks - targets
            val dividers = s.habitDividers - targets
            val labels = s.valueDisplayLabels.toMutableMap()
            for (habit in targets) {
                val inner = labels[habit]?.toMutableMap() ?: mutableMapOf()
                inner[GRAPH_METRIC_VALUE1] = "sessions"
                inner.remove(GRAPH_METRIC_VALUE2)
                labels[habit] = inner
            }
            settingsRepo.saveSecondaryValueHabits(secHabits)
            settingsRepo.saveSecondaryValueFallbackHabits(fallback)
            settingsRepo.saveMinutesEnabledHabits(minutesEnabled)
            settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
            settingsRepo.saveMinutesPrimaryFallbacks(mpFallbacks)
            settingsRepo.saveHabitDividers(dividers)
            settingsRepo.saveValueDisplayLabels(labels)
            _settings.value = s.copy(
                secondaryValueHabits = secHabits,
                secondaryValueFallbackHabits = fallback,
                minutesEnabledHabits = minutesEnabled,
                widgetTimerMinutesPrimary = minutesPrimary,
                minutesPrimaryFallbacks = mpFallbacks,
                habitDividers = dividers,
                valueDisplayLabels = labels
            )
            settingsRepo.setApneaSessionsPrimaryMigrationDone()
            Log.i(
                TAG,
                "performApneaSessionsPrimaryMigration: migrated ${targets.size} apnea habits to sessions-primary: ${targets.sorted()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "performApneaSessionsPrimaryMigration failed (will retry next load): ${e.message}")
        }
    }

    /**
     * One-time migration (Aug-22-2026): converts the remaining Wags-fed
     * breathing habits ([com.example.tail.data.BREATHING_SESSIONS_PRIMARY_HABITS]
     * — Meditations, Resonance Breathing, Until Contraction) from the legacy
     * Wags layout (minutes = primary value with divider + sessions in the
     * `secondary_value:` slot with points fallback) to SESSIONS-PRIMARY, the
     * same layout the five apnea habits got on Aug-21-2026:
     *
     *  • sessions become the PRIMARY value and the sole points source — the
     *    divider and the points fallback are removed, so points = sessions;
     *  • minutes move to the first-class `minutes:` slot with the built-in
     *    minutes value type enabled, so charts keep showing them;
     *  • the secondary-value feature and its legacy slot are dropped.
     *
     * "Apnea practiced" is deliberately NOT touched: it keeps its historical
     * meaning (fed by the O2/CO2 Tables conditional links, never incremented
     * on its own) and its legacy secondary data stays readable.
     *
     * Data swap via [com.example.tail.data.swapToSessionsPrimary]; days with
     * recorded minutes but no session entry get sessions = 1 so no day loses
     * its done/points status. Runs AFTER [performApneaSessionsPrimaryMigration].
     */
    private suspend fun performBreathingSessionsPrimaryMigration(uri: Uri) {
        try {
            val targets = com.example.tail.data.BREATHING_SESSIONS_PRIMARY_HABITS
            val swapped = com.example.tail.data.swapToSessionsPrimary(cachedPhoneDb, targets)
            if (swapped != cachedPhoneDb) {
                habitsRepo.saveDatabase(uri, context, swapped)
                cachedPhoneDb = swapped
            }
            val s = _settings.value
            val secHabits = s.secondaryValueHabits - targets
            val fallback = s.secondaryValueFallbackHabits - targets
            val minutesEnabled = s.minutesEnabledHabits + targets
            val minutesPrimary = s.widgetTimerMinutesPrimary - targets
            val mpFallbacks = s.minutesPrimaryFallbacks - targets
            val dividers = s.habitDividers - targets
            val labels = s.valueDisplayLabels.toMutableMap()
            for (habit in targets) {
                val inner = labels[habit]?.toMutableMap() ?: mutableMapOf()
                inner[GRAPH_METRIC_VALUE1] = "sessions"
                inner.remove(GRAPH_METRIC_VALUE2)
                labels[habit] = inner
            }
            settingsRepo.saveSecondaryValueHabits(secHabits)
            settingsRepo.saveSecondaryValueFallbackHabits(fallback)
            settingsRepo.saveMinutesEnabledHabits(minutesEnabled)
            settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
            settingsRepo.saveMinutesPrimaryFallbacks(mpFallbacks)
            settingsRepo.saveHabitDividers(dividers)
            settingsRepo.saveValueDisplayLabels(labels)
            _settings.value = s.copy(
                secondaryValueHabits = secHabits,
                secondaryValueFallbackHabits = fallback,
                minutesEnabledHabits = minutesEnabled,
                widgetTimerMinutesPrimary = minutesPrimary,
                minutesPrimaryFallbacks = mpFallbacks,
                habitDividers = dividers,
                valueDisplayLabels = labels
            )
            settingsRepo.setBreathingSessionsPrimaryMigrationDone()
            Log.i(
                TAG,
                "performBreathingSessionsPrimaryMigration: migrated ${targets.size} breathing habits to sessions-primary: ${targets.sorted()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "performBreathingSessionsPrimaryMigration failed (will retry next load): ${e.message}")
        }
    }

    /**
     * One-time cleanup: the chess.com sync used to record one timestamp per
     * MINUTE played (the minutes delta was passed to addTimestamps). Trims
     * each chess.com-linked habit's daily timestamp lists down to that day's
     * game count — one timestamp per game — keeping the earliest entries.
     */
    private suspend fun performChessTimestampTrim() {
        try {
            val s = _settings.value
            if (s.chessComHabitLinks.isNotEmpty()) {
                val all = timestampRepo.loadAll()
                for (habitName in s.chessComHabitLinks.keys) {
                    val days = all[habitName] ?: continue
                    val gamesEntries = cachedPhoneDb[secondaryValueKey(habitName)] ?: continue
                    for ((dateStr, stamps) in days) {
                        val games = gamesEntries[dateStr] ?: 0
                        if (games > 0 && stamps.size > games) {
                            val date = com.example.tail.data.parseDate(dateStr) ?: continue
                            timestampRepo.setTimestampsForDay(habitName, date, stamps.take(games))
                        }
                    }
                }
            }
            settingsRepo.setChessTimestampsTrimDone()
            Log.i(TAG, "performChessTimestampTrim: done")
        } catch (e: Exception) {
            Log.e(TAG, "performChessTimestampTrim failed (will retry next load): ${e.message}")
        }
    }

    private suspend fun catchUpAndLoad(uri: Uri) {
        _isLoading.value = true
        _errorMessage.value = null
        try {
            // All sequential load work (SAF reads, JSON parsing, migrations,
            // roll-forward, day backfill) runs OFF the main thread. This used
            // to run on the Main dispatcher, which blocked the choreographer
            // and made the loading spinner animation visibly choppy.
            withContext(Dispatchers.Default) {
            runAutoRestoreIfNeeded(uri)

            // ── Roll forward MUST run BEFORE ensureDaysExist ──────────────
            // ensureDaysExist fills every missing date (including today) with
            // value 0. If it runs first, performRollForwardIfNeeded sees that
            // today's entry already exists and silently skips the roll forward.
            // By loading the raw DB first and running roll forward before any
            // placeholder 0s are created, yesterday's values are correctly
            // copied to today.
            val loadResult = habitsRepo.loadDatabaseResult(uri, context)
            if (loadResult !is com.example.tail.data.HabitsLoadResult.Success) {
                throw com.example.tail.data.HabitsLoadFailedException(loadResult)
            }
            cachedPhoneDb = loadResult.db

            // ── One-time apnea secondary-value migration ──────────────────────
            // Pre-March-12-2026 apnea session counts were stored in the primary
            // slot (minutes). Move them to secondary_value so the fallback
            // mechanism can use them for points on days with 0 minutes.
            if (!settingsRepo.isApneaSecondaryMigrationDone()) {
                performApneaSecondaryMigration(uri)
            }

            // ── One-time resonance-breathing secondary-value migration ────────
            // Pre-Aug-8-2026 resonance session counts were stored in the primary
            // slot (minutes). Move them to secondary_value so the fallback
            // mechanism can use them for points on days with 0 minutes.
            if (!settingsRepo.isResonanceSecondaryMigrationDone()) {
                performResonanceSecondaryMigration(uri)
            }

            // ── One-time first-class minutes-slot migration ────────────────
            // Timer features used to write minutes into the generic secondary
            // slot; move them to the dedicated minutes: slot.
            if (!settingsRepo.isMinutesSlotMigrationDone()) {
                performMinutesSlotMigration(uri)
            }

            // ── One-time minutes-enabled set initialisation ────────────────
            // Seed the explicit per-habit minutes toggle so the state after
            // the toggle feature ships matches what the user had before.
            if (!settingsRepo.isMinutesToggleInitDone()) {
                performMinutesToggleInit()
            }
            // Backfill: habits already connected to a timer widget, media
            // tracker or the movie bridge get their explicit minutes toggle
            // turned ON (covers habits connected after the init seeding).
            if (!settingsRepo.isMinutesWidgetBackfillDone()) {
                performMinutesWidgetBackfill()
            }

            // ── One-time Wags minutes-primary repair ─────────────────────
            // The minutes-slot rollout wrongly classified Wags-fed habits as
            // minutes-primary: Wags stores minutes in the PRIMARY key and
            // sessions in secondary_value:, per the IPC protocol. Restore the
            // Meditations/Resonance pattern (minutes = Value1 with divider,
            // sessions = Value2 + zero-minutes points fallback).
            if (!settingsRepo.isWagsMinutesPrimaryRepairDone()) {
                performWagsMinutesPrimaryRepair(uri)
            }

            // ── One-time broken minutes-migration repair ────────────────
            // The graph long-press "Minutes value" action used to MOVE the
            // primary-key history into `minutes:` and delete the primary key,
            // blanking the graph (most visibly for Garmin-linked habits).
            // Restore the primary key and clear the flags.
            if (!settingsRepo.isBrokenMinutesMigrationRepairDone()) {
                performBrokenMinutesMigrationRepair(uri)
            }

            // ── One-time apnea sessions-primary migration ────────────────
            // The five Wags-fed apnea habits become sessions-primary:
            // sessions = primary value & points source (no divider, no
            // fallback); minutes move to the built-in minutes slot.
            if (!settingsRepo.isApneaSessionsPrimaryMigrationDone()) {
                performApneaSessionsPrimaryMigration(uri)
            }

            // ── One-time breathing sessions-primary migration ────────────
            // Meditations / Resonance Breathing / Until Contraction become
            // sessions-primary too, completing the secondary-value retirement
            // for non-special habits.
            if (!settingsRepo.isBreathingSessionsPrimaryMigrationDone()) {
                performBreathingSessionsPrimaryMigration(uri)
            }

            // ── One-time chess.com timestamp trim ──────────────────────────
            // The chess.com sync used to record one timestamp per minute;
            // trim to one per game.
            if (!settingsRepo.isChessTimestampsTrimDone()) {
                performChessTimestampTrim()
            }

            // ── Movie-bridge timestamp reconciliation ────────────────────
            // The text log is the source of truth for movie habits: make
            // each entry's watch-start time THE timestamp. Backfills past
            // films and removes confirm-time duplicates. Idempotent — it
            // only writes when the store differs from the log, so running
            // it on every load is cheap.
            runCatching { syncAllMovieHabitTimestamps() }

            // Perform roll forward BEFORE ensureDaysExist creates today=0
            performRollForwardIfNeeded()

            // Now fill in any remaining missing days. Today already has the
            // rolled-forward value, so ensureDaysExist won't overwrite it.
            val db = habitsRepo.ensureDaysExist(uri, context)
            cachedPhoneDb = db
            }

            // Gate opens ONLY here, after a genuinely successful load. Background
            // sync writers check this before persisting cachedPhoneDb.
            dbLoaded = true
            
            rebuildHabitList()

            // ── Movie minutes reconciliation ─────────────────────────────
            // Fill each movie habit's minutes slot from its "(N min)"
            // annotations, then (once per day) backfill lengths for entries
            // still missing one — bridge file durations first, OMDb second.
            viewModelScope.launch {
                runCatching { syncAllMovieMinutesSlots() }
                runCatching { maybeRunMovieMinutesBackfill() }
            }
        } catch (e: Exception) {
            // Load failed (transient SAF/blank file during a Syncthing write, etc.).
            // Leave dbLoaded as-is (do NOT flip it true) so sync writers stay blocked.
            _errorMessage.value = "Failed to load file: ${e.message}"
        } finally {
            _isLoading.value = false
        }
    }

    /**
     * AUTO-RESTORE-ON-CATASTROPHIC-LOSS (requirement 3).
     * Delegates to the repository, which compares the on-disk DB against the best
     * private snapshot and repairs a catastrophic wipe automatically before any
     * downstream write can cement the loss. Surfaces a friendly message on repair.
     */
    private suspend fun runAutoRestoreIfNeeded(uri: Uri) {
        try {
            val restore = habitsRepo.loadWithAutoRestore(uri, context)
            if (restore is com.example.tail.data.HabitsRepository.AutoRestoreResult.Restored) {
                Log.e(
                    TAG,
                    "runAutoRestoreIfNeeded: AUTO-RESTORED from snapshot '" + restore.snapshotName +
                            "' (" + restore.onDiskEntryCount + " to " + restore.restoredEntryCount + " entries)."
                )
                _errorMessage.value =
                    "Recovered " + restore.restoredEntryCount +
                            " entries from an automatic backup after detecting data loss."
            } else if (restore is com.example.tail.data.HabitsRepository.AutoRestoreResult.Unrecoverable) {
                Log.e(
                    TAG,
                    "runAutoRestoreIfNeeded: catastrophic loss (" + restore.onDiskEntryCount +
                            " entries) but no snapshot to restore from."
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "runAutoRestoreIfNeeded: failed: " + e.message)
        }
    }

    /**
     * Performs roll forward for habits that have the roll forward feature enabled.
     * This is called after the DB is loaded to ensure we have the latest data.
     * Only runs once per day (tracked by rollForwardLastDate).
     *
     * Roll forward copies:
     * 1. The increment amount from yesterday to today
     * 2. The text entry (if any) from yesterday to today
     */
    private suspend fun performRollForwardIfNeeded() {
        val today = LocalDate.now()
        
        // Skip if we already performed roll forward today
        if (rollForwardLastDate == today) {
            Log.d(TAG, "Roll forward already performed for $today, skipping")
            return
        }
        
        // Skip if no roll forward habits are configured
        if (_settings.value.rollForwardHabits.isEmpty()) {
            Log.d(TAG, "No roll forward habits configured")
            return
        }
        
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            Log.d(TAG, "No file URI configured, skipping roll forward")
            return
        }
        
        val yesterday = today.minusDays(1)
        val yesterdayStr = com.example.tail.data.dateString(yesterday)
        val todayStr = com.example.tail.data.dateString(today)
        
        var dbChanged = false
        val updatedDb = cachedPhoneDb.toMutableMap()
        
        for (habitName in _settings.value.rollForwardHabits) {
            // Roll forward increment amount
            val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
            val yesterdayValue = habitEntries[yesterdayStr]
            
            // Set today's value if yesterday had a non-zero value and today is
            // either missing entirely or still at the 0 placeholder created by
            // ensureDaysExist (which may have been run by a widget or background
            // service before the main app opened).
            if (yesterdayValue != null && yesterdayValue != 0 &&
                (habitEntries[todayStr] == null || habitEntries[todayStr] == 0)) {
                habitEntries[todayStr] = yesterdayValue
                updatedDb[habitName] = habitEntries
                dbChanged = true
                Log.d(TAG, "Roll forward: copied $habitName increment from $yesterdayStr to $todayStr: $yesterdayValue")
            }
            
            // Roll forward text entry (if this habit has text input enabled)
            if (habitName in _settings.value.textInputHabits) {
                val textUriString = _settings.value.textInputFileUris[habitName]
                if (!textUriString.isNullOrEmpty()) {
                    try {
                        // Find yesterday's text entry (noon timestamp)
                        val yesterdayTimestamp = java.time.LocalDateTime.of(yesterday, java.time.LocalTime.NOON)
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        
                        // Load yesterday's text
                        val textLog = textInputRepo.loadTextLog(Uri.parse(textUriString), context)
                        val yesterdayText = textLog[yesterdayTimestamp]
                        
                        if (yesterdayText != null) {
                            // Check if today already has a text entry
                            val todayTimestamp = java.time.LocalDateTime.of(today, java.time.LocalTime.NOON)
                                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                            
                            if (!textLog.containsKey(todayTimestamp)) {
                                // Roll forward the text
                                textInputRepo.updateTextEntry(Uri.parse(textUriString), context, todayTimestamp, yesterdayText, habitName = habitName)
                                Log.d(TAG, "Roll forward: copied $habitName text from $yesterdayStr to $todayStr: $yesterdayText")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Roll forward: failed to roll forward text for $habitName: ${e.message}")
                    }
                }
            }
        }
        
        if (dbChanged) {
            cachedPhoneDb = updatedDb
            try {
                val uri = Uri.parse(uriString)
                habitsRepo.persistDatabase(uri, context, updatedDb)
                Log.d(TAG, "Roll forward: persisted changes to disk")
            } catch (e: Exception) {
                Log.e(TAG, "Roll forward: failed to save: ${e.message}")
                _errorMessage.value = "Failed to save roll forward: ${e.message}"
            }
        }
        
        // Mark that we've performed roll forward for today
        rollForwardLastDate = today
    }

    fun loadFromFile(uri: Uri) {
        viewModelScope.launch {
            catchUpAndLoad(uri)
        }
    }

    /** Rebuilds the displayed habit list from cached data for the current selectedDate.
     *  Stores the result in the per-screen cache for instant retrieval on switch.
     *
     *  Race-proofed: runs are serialized by [rebuildMutex] and each run
     *  re-checks its snapshot before publishing. Rebuilds are launched from
     *  many asynchronous triggers (screen switches, HabitIncrementBus events,
     *  Garmin/Chess/GitHub syncs, …), so a rebuild that snapshotted the DB
     *  BEFORE a tap used to be able to finish AFTER the increment's own
     *  rebuild and publish the pre-tap list over the optimistic UI update —
     *  the tap then recorded its timestamp but the square only showed the
     *  increment after the next screen switch. The snapshot guard makes
     *  "publish older data than what is currently cached" impossible. */
    private suspend fun rebuildHabitList() = rebuildMutex.withLock {
        while (true) {
            val effectiveOrder = activeHabitOrder()
            // If screens are configured and the active screen is empty, show nothing.
            // We must NOT fall back to HABIT_ORDER in this case.
            if (effectiveOrder.isEmpty() && _habitScreens.value.isNotEmpty()) {
                _habits.value = emptyList()
                _todayPoints.value = 0
                _loadingMetrics.value = LoadingMetrics(0.0, 0.0, 0)
                screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = emptyList()
                return@withLock
            }
            // Snapshot everything the build depends on, consistently.
            val dbSnapshot = cachedPhoneDb
            val targetDate = _selectedDate.value
            val screenIndex = _activeScreenIndex.value
            val settingsWithOrder = _settings.value.copy(habitOrder = effectiveOrder)
            // Run the heavy per-habit calculations on a background CPU thread
            val newList = withContext(Dispatchers.Default) {
                habitsRepo.buildHabitList(
                    db = dbSnapshot,
                    settings = settingsWithOrder,
                    targetDate = targetDate
                )
            }
            // Stale-snapshot guard: if the DB, date or screen changed while we
            // were computing (e.g. a tap just landed), recompute from the
            // latest state instead of publishing a list that would undo it.
            if (dbSnapshot !== cachedPhoneDb ||
                targetDate != _selectedDate.value ||
                screenIndex != _activeScreenIndex.value
            ) {
                continue
            }
            _habits.value = newList
            _todayPoints.value = newList.sumOf { it.todayCount }
            var freshMetrics = getLoadingMetrics(targetDate)
            // The daily spark mirrors the app-open spinner: both derive from
            // the same DB totals, so every spinner in the app — grid, map,
            // reloads — renders the same tiers.
            _loadingMetrics.value = freshMetrics
            // Persist only metrics computed for today so history browsing never
            // poisons the cold-start cache.
            if (targetDate == LocalDate.now()) {
                cacheLoadingMetrics(freshMetrics, targetDate)
            }
            screenHabitCache[Pair(screenIndex, targetDate)] = newList
            return@withLock
        }
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
     * Sends a generic broadcast announcing that a habit was incremented.
     * Protected by the TAIL_INTEGRATION signature permission so only same-keystore
     * apps (e.g. VILD) can receive it. The broadcast is fire-and-forget — if no
     * receiver is registered, it's silently dropped.
     */
    private fun sendHabitIncrementedBroadcast(habitName: String, amount: Int = 1) {
        com.example.tail.ipc.HabitIncrementAnnouncer.announce(context, habitName, amount)
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

    /** Toggles whether [habitName] appears as a timer square on the PC widget. */
    fun togglePcWidgetHabit(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.pcWidgetHabits.toMutableSet()
            val wasEnabled = habitName in current
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.savePcWidgetHabits(current)
            // Connecting a habit to the PC widget forces minutes ON — the
            // widget timer feeds the habit's `minutes:` slot.
            var minutes = _settings.value.minutesEnabledHabits
            if (!wasEnabled && habitName in current && habitName !in minutes && habitName !in _settings.value.maxOneHabits) {
                minutes = minutes + habitName
                settingsRepo.saveMinutesEnabledHabits(minutes)
            }
            _settings.value = _settings.value.copy(
                pcWidgetHabits = current,
                minutesEnabledHabits = minutes
            )
            pushPcWidgetConfig()
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
        // Use all habit names present in the DB (covers all screens),
        // excluding secondary-value storage entries
        val habitNames = db.keys.filter { !isInternalValueKey(it) }

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
                total += effectivePointsForDate(name, raw, ds)
            }
            result[ds] = total
        }
        return result
    }

    /**
     * Increments a habit's count. When [recordTimestamp] is true (default), also
     * records the current time in the timestamp repository. Set to false for
     * "silent" increments (e.g. long-press, edit-mode counter adjustments).
     *
     * @param date The date the increment applies to. Null (default) uses the
     *             currently viewed date — right for tile taps. Callers whose
     *             action is semantically about TODAY regardless of what the
     *             user is viewing (e.g. habit-ask notification answers) must
     *             pass [java.time.LocalDate.now] explicitly.
     */
    fun incrementHabit(
        habitName: String,
        amount: Int = 1,
        recordTimestamp: Boolean = true,
        date: LocalDate? = null
    ) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }

        // The date this increment applies to. When it is NOT the viewed date,
        // the instant UI flip below is skipped (the visible rows belong to
        // another day) and the Step-3 rebuild refreshes everything from the
        // updated DB instead.
        val targetDate = date ?: _selectedDate.value
        val affectsVisibleDate = targetDate == _selectedDate.value

        // Step 1: instant targeted update — just flip todayCount for this one habit.
        // This is O(n) list copy with zero calculations, so it's effectively instant.
        val dateStr = com.example.tail.data.dateString(targetDate)
        val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
        val currentStored = currentEntries[dateStr] ?: 0
        // Custom point ranges: the entered amount is the RAW input for the day.
        // Store the calculated points tier (set semantics, like the Garmin path).
        val rangePoints = customRangePointsForInput(habitName, amount)
        val newCount: Int
        val dbDelta: Int
        if (rangePoints != null) {
            newCount = rangePoints
            dbDelta = newCount - currentStored
            // If the tier didn't actually change, bail out early
            if (dbDelta == 0) return
        } else {
            val rawNewCount = currentStored + amount
            // If this habit has the "1 max" cap, clamp to 1
            newCount = if (habitName in _settings.value.maxOneHabits) rawNewCount.coerceAtMost(1) else rawNewCount
            // If the count didn't actually change (e.g. already at 1 with 1-max), bail out early
            if (newCount == currentStored) return
            dbDelta = amount
        }
        if (affectsVisibleDate) {
            val divider = _settings.value.habitDividers[habitName] ?: 1
            _habits.value = _habits.value.map { h ->
                if (h.name == habitName) h.copy(
                    todayCount = rangePoints ?: if (habitName in _settings.value.invertedBinaryHabits) {
                        com.example.tail.data.invertedBinaryPoints(newCount)
                    } else applyDivider(newCount, divider),
                    rawTodayCount = newCount
                ) else h
            }
            // Keep per-screen cache in sync with the instant update
            screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value
        }

        // Step 2: update in-memory cache
        // For roll forward habits, find the next manually set date BEFORE applying the change
        val nextManualDate = if (habitName in _settings.value.rollForwardHabits) {
            val manualDates = _settings.value.rollForwardManualDates[habitName] ?: emptySet()
            manualDates.mapNotNull { dateStr ->
                com.example.tail.data.parseDate(dateStr)
            }.sorted()
            .firstOrNull { it > targetDate }
        } else null
        
        var updatedDb = habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, dbDelta, targetDate)
        
        // Step 2b: Track this date as manually set for roll forward habits
        if (habitName in _settings.value.rollForwardHabits) {
            val dateStr = com.example.tail.data.dateString(targetDate)
            val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
            currentManualDates.add(dateStr)
            val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
            updatedManualDates[habitName] = currentManualDates
            viewModelScope.launch {
                settingsRepo.saveRollForwardManualDates(updatedManualDates)
                _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)
            }
        }

        // Step 2c: if this is a conditional habit, also increment all linked habits
        val linkedHabits = if (habitName in _settings.value.conditionalHabits) {
            _settings.value.conditionalLinkedHabits[habitName] ?: emptySet()
        } else emptySet()

        // "Feed max1" sub-setting: Points feeds from this habit are capped at
        // 1 point per linked habit per day. currentStored is the source's count
        // for the day BEFORE this increment, so only the first increment of the
        // day feeds; secondary-slot feeds are not capped.
        val feedMaxOne = habitName in _settings.value.conditionalFeedMaxOneHabits

        // "Feed points" sub-setting: feeds send the source's POINTS delta
        // (divider-applied) instead of the raw increment amount, so a minutes
        // habit with a divider feeds its divided point value to linked habits.
        // The delta is computed from the day's totals so rounding accumulates
        // exactly like the displayed points (30+1 min at ÷2 feeds 15 then 1).
        val feedPoints = habitName in _settings.value.conditionalFeedPointsHabits
        val sourceDivider = _settings.value.habitDividers[habitName] ?: 1
        val baseFeedAmount = if (feedPoints && sourceDivider > 1) {
            applyDivider(currentStored + amount, sourceDivider) -
                applyDivider(currentStored, sourceDivider)
        } else amount

        // Linked-habit timestamps are collected here and recorded only after
        // the habits file was durably written (see Step 3) — a failed persist
        // must never leave phantom timestamps for increments that never
        // reached the file.
        val pendingLinkedTimestamps = mutableListOf<String>()
        for (linkedName in linkedHabits) {
            // Resolve which value slot of the linked habit this feed targets:
            // Points (default) = its count; Value2/Value3 = its raw secondary slots.
            val valueKey = effectiveConditionalLinkValueKey(
                _settings.value.conditionalLinkValues,
                _settings.value.secondaryValueHabits,
                _settings.value.chessComHabitLinks,
                habitName, linkedName
            )
            val targetKey = conditionalLinkStorageKey(linkedName, valueKey)
            val feedAmount = if (targetKey == linkedName && feedMaxOne) {
                conditionalCappedFeedAmount(currentStored, baseFeedAmount)
            } else baseFeedAmount
            if (feedAmount == 0) continue
            if (targetKey != linkedName) {
                // Raw secondary slot: no max-1 cap; skip the instant row update
                // (the full rebuild below refreshes secondary displays from the DB).
                updatedDb = habitsRepo.applyIncrementToDb(updatedDb, targetKey, feedAmount, targetDate)
                if (recordTimestamp) {
                    pendingLinkedTimestamps.add(linkedName)
                }
                continue
            }
            val linkedEntries = updatedDb[linkedName] ?: emptyMap()
            val linkedRaw = (linkedEntries[dateStr] ?: 0) + feedAmount
            val linkedClamped = if (linkedName in _settings.value.maxOneHabits) linkedRaw.coerceAtMost(1) else linkedRaw
            if (linkedClamped != (linkedEntries[dateStr] ?: 0)) {
                updatedDb = habitsRepo.applyIncrementToDb(updatedDb, linkedName, feedAmount, targetDate)
                if (affectsVisibleDate) {
                    val linkedDivider = _settings.value.habitDividers[linkedName] ?: 1
                    _habits.value = _habits.value.map { h ->
                        if (h.name == linkedName) h.copy(
                            todayCount = if (linkedName in _settings.value.invertedBinaryHabits) {
                                com.example.tail.data.invertedBinaryPoints(linkedClamped)
                            } else applyDivider(linkedClamped, linkedDivider),
                            rawTodayCount = linkedClamped
                        ) else h
                    }
                }
                // Record timestamp for the linked habit too
                if (recordTimestamp) {
                    pendingLinkedTimestamps.add(linkedName)
                }
            }
        }

        // Step 2d: Roll forward logic - fill subsequent days for roll forward habits
        if (habitName in _settings.value.rollForwardHabits) {
            val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
            val selectedDate = targetDate
            val today = java.time.LocalDate.now()
            
            // Fill all dates from selectedDate to nextManualDate (exclusive) or today (inclusive)
            var currentDate = selectedDate.plusDays(1)
            val endDate = nextManualDate?.minusDays(1) ?: today
            
            while (currentDate <= endDate) {
                val currentDateStr = com.example.tail.data.dateString(currentDate)
                habitEntries[currentDateStr] = newCount
                currentDate = currentDate.plusDays(1)
            }
            
            // Update the database with the filled entries
            updatedDb = updatedDb.toMutableMap()
            updatedDb[habitName] = habitEntries
        }
        
        cachedPhoneDb = updatedDb
        // Keep per-screen cache in sync after conditional updates — only when
        // the visible list actually reflects the incremented date
        if (affectsVisibleDate) {
            screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value
        }

        // Step 5 delta, computed here while the cache values are stable.
        val storedDelta = newCount - currentStored

        // Step 3: disk write FIRST, then every follow-up effect that must never
        // outlive the count. Previously the timestamps (Steps 4/5) were written
        // by separate, faster coroutines while the habits-file persist ran
        // behind an unprotected rebuildHabitList() — so a transient SAF
        // failure, a rebuild crash or process death between the two writes
        // silently left a phantom "timestamp without increment" (the
        // notification-answer bug). Now: persist → emit → rebuild → timed
        // entries → timestamps → broadcast, and NOTHING is recorded when the
        // persist fails.
        viewModelScope.launch {
            val uri = Uri.parse(uriString)
            try {
                habitsRepo.persistDatabase(uri, context, updatedDb)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist increment for '$habitName': ${e.message}", e)
                _errorMessage.value = "Failed to save: ${e.message}"
                // Resync the optimistic cache with the file so a later attempt
                // doesn't early-return against a stale-high count.
                try {
                    cachedPhoneDb = habitsRepo.loadDatabase(uri, context)
                } catch (e2: Exception) {
                    Log.w(TAG, "Cache resync after failed persist failed too: ${e2.message}")
                }
                return@launch
            }
            // Re-assert the just-persisted state: a concurrent disk reload
            // (e.g. the HabitIncrementBus collector's ensureDaysExist) may
            // have replaced cachedPhoneDb with the PRE-persist file while the
            // write was in flight. The rebuild below must never publish that
            // stale snapshot over the optimistic UI update from Step 1.
            cachedPhoneDb = updatedDb
            HabitsDataChangedBus.emit()
            // Full rebuild (streak/ATH recalc) — AFTER the write, and guarded so
            // a rebuild failure can never starve the effects below.
            try {
                rebuildHabitList()
            } catch (e: Exception) {
                Log.e(TAG, "Rebuild after increment failed: ${e.message}", e)
            }
            // Step 4: if this is a timed habit (and NOT subtyped — subtyped timed
            // habits record their timed entries in saveSubtypeIncrement instead),
            // append a timestamped session entry with subtype=null.
            if (habitName in _settings.value.timedHabits && habitName !in _settings.value.subtypedHabits) {
                try {
                    timedDataRepo.appendEntries(habitName, mapOf(null to amount))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to append timed entry for '$habitName': ${e.message}")
                }
            }
            // Step 5: record timestamp(s) if requested. One timestamp PER stored unit
            // (storedDelta, not amount) so the timestamp editor's increment amounts
            // always match the day's count — including max-1 clamps and point tiers.
            if (recordTimestamp && storedDelta > 0) {
                try {
                    timestampRepo.addTimestamps(habitName, storedDelta, targetDate)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record timestamps for '$habitName': ${e.message}")
                }
            }
            for (linkedName in pendingLinkedTimestamps) {
                try {
                    timestampRepo.addTimestamp(linkedName, targetDate)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record linked timestamp for '$linkedName': ${e.message}")
                }
            }
            // Step 6: broadcast a generic "habit incremented" event so same-keystore apps
            // (e.g. VILD) can react — e.g. auto-switch from night to day mode on wake-up.
            sendHabitIncrementedBroadcast(habitName, storedDelta.coerceAtLeast(0))
        }
    }

    /**
     * Increments a habit's count with roll forward to a specified end date.
     * This is used when the user confirms the roll forward dialog.
     */
    fun incrementHabitWithRollForward(
        habitName: String,
        amount: Int = 1,
        recordTimestamp: Boolean = true,
        customEndDate: LocalDate? = null
    ) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }

        // Step 1: instant targeted update — just flip todayCount for this one habit.
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
        val currentStored = currentEntries[dateStr] ?: 0
        // Custom point ranges: the entered amount is the RAW input for the day.
        // Store the calculated points tier (set semantics, like the Garmin path).
        val rangePoints = customRangePointsForInput(habitName, amount)
        val newCount: Int
        val dbDelta: Int
        if (rangePoints != null) {
            newCount = rangePoints
            dbDelta = newCount - currentStored
            if (dbDelta == 0) return
        } else {
            val rawNewCount = currentStored + amount
            newCount = if (habitName in _settings.value.maxOneHabits) rawNewCount.coerceAtMost(1) else rawNewCount
            if (newCount == currentStored) return
            dbDelta = amount
        }
        val divider = _settings.value.habitDividers[habitName] ?: 1
        _habits.value = _habits.value.map { h ->
            if (h.name == habitName) h.copy(
                todayCount = rangePoints ?: if (habitName in _settings.value.invertedBinaryHabits) {
                    com.example.tail.data.invertedBinaryPoints(newCount)
                } else applyDivider(newCount, divider),
                rawTodayCount = newCount
            ) else h
        }
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

        // Step 2: update in-memory cache
        var updatedDb = habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, dbDelta, _selectedDate.value)
        
        // Step 2b: Track this date as manually set for roll forward habits
        if (habitName in _settings.value.rollForwardHabits) {
            val dateStr = com.example.tail.data.dateString(_selectedDate.value)
            val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
            currentManualDates.add(dateStr)
            val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
            updatedManualDates[habitName] = currentManualDates
            viewModelScope.launch {
                settingsRepo.saveRollForwardManualDates(updatedManualDates)
                _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)
            }
        }

        // Step 2c: Roll forward logic - fill subsequent days for roll forward habits
        if (habitName in _settings.value.rollForwardHabits && customEndDate != null) {
            val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
            val selectedDate = _selectedDate.value
            
            // Fill all dates from selectedDate to customEndDate (inclusive)
            var currentDate = selectedDate.plusDays(1)
            val endDate = customEndDate
            
            while (currentDate <= endDate) {
                val currentDateStr = com.example.tail.data.dateString(currentDate)
                habitEntries[currentDateStr] = newCount
                currentDate = currentDate.plusDays(1)
            }
            
            // Update the database with the filled entries
            updatedDb = updatedDb.toMutableMap()
            updatedDb[habitName] = habitEntries
        }
        
        cachedPhoneDb = updatedDb
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

        // Step 5 delta, computed here while the cache values are stable.
        val storedDelta = newCount - currentStored

        // Step 3: disk write FIRST, then the follow-up effects — same durable-
        // first ordering as incrementHabit: a failed persist must never leave
        // phantom timed entries/timestamps for a count that never reached the
        // file, and a rebuild crash must never starve the persist.
        viewModelScope.launch {
            val uri = Uri.parse(uriString)
            try {
                habitsRepo.persistDatabase(uri, context, updatedDb)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist roll-forward increment for '$habitName': ${e.message}", e)
                _errorMessage.value = "Failed to save: ${e.message}"
                try {
                    cachedPhoneDb = habitsRepo.loadDatabase(uri, context)
                } catch (e2: Exception) {
                    Log.w(TAG, "Cache resync after failed persist failed too: ${e2.message}")
                }
                return@launch
            }
            // Re-assert the just-persisted state (same rationale as
            // incrementHabit: a concurrent disk reload during the persist
            // must not become the snapshot the rebuild publishes).
            cachedPhoneDb = updatedDb
            HabitsDataChangedBus.emit()
            try {
                rebuildHabitList()
            } catch (e: Exception) {
                Log.e(TAG, "Rebuild after roll-forward increment failed: ${e.message}", e)
            }
            // Step 4: if this is a timed habit (and NOT subtyped)
            if (habitName in _settings.value.timedHabits && habitName !in _settings.value.subtypedHabits) {
                try {
                    timedDataRepo.appendEntries(habitName, mapOf(null to amount))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to append timed entry for '$habitName': ${e.message}")
                }
            }
            // Step 5: record timestamp(s) if requested. One timestamp PER stored unit
            // (storedDelta, not amount) so the timestamp editor's increment amounts
            // always match the day's count — including max-1 clamps and point tiers.
            if (recordTimestamp && storedDelta > 0) {
                try {
                    timestampRepo.addTimestamps(habitName, storedDelta, _selectedDate.value)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record timestamps for '$habitName': ${e.message}")
                }
            }
            sendHabitIncrementedBroadcast(habitName, storedDelta.coerceAtLeast(0))
        }
    }

    /**
     * Updates a text entry with roll forward to a specified end date.
     * This is used when the user confirms the roll forward dialog.
     */
    fun updateTextEntryWithRollForward(
        habitName: String,
        oldTimestamp: String,
        newText: String,
        customEndDate: LocalDate,
        onComplete: () -> Unit = {}
    ) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            onComplete()
            return
        }
        viewModelScope.launch {
            try {
                // Update the text entry at the old timestamp
                textInputRepo.updateTextEntry(Uri.parse(uriString), context, oldTimestamp, newText, habitName = habitName)
                
                // Parse the date from the oldTimestamp
                val dateStr = oldTimestamp.substring(0, 10)
                val entryDate = com.example.tail.data.parseDate(dateStr)
                
                if (entryDate != null && habitName in _settings.value.rollForwardHabits) {
                    // Roll forward the text to all dates from entryDate+1 to customEndDate
                    if (entryDate < customEndDate) {
                        textInputRepo.rollForwardTextEntry(
                            Uri.parse(uriString),
                            context,
                            oldTimestamp,
                            entryDate.plusDays(1),
                            customEndDate,
                            habitName = habitName
                        )
                    }
                }
                
                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update text entry: ${e.message}"
                onComplete()
            }
        }
    }

    /**
     * Sets a text entry with roll forward to a specified end date.
     * This is used when the user confirms the roll forward dialog.
     */
    fun setTextEntryForDateWithRollForward(
        habitName: String,
        date: LocalDate,
        text: String,
        customEndDate: LocalDate,
        time: java.time.LocalTime? = null,
        onComplete: () -> Unit = {}
    ) {
        setTextEntriesForDateWithRollForward(
            habitName, date, listOf(text), customEndDate, time, onComplete
        )
    }

    /**
     * Multi-entry version of [setTextEntryForDateWithRollForward].
     * Saves all [texts] with unique timestamps (offset by 1 second), rolls forward
     * the first entry's text, and increments the habit count by 1 (selecting
     * multiple options counts as a single action).
     */
    fun setTextEntriesForDateWithRollForward(
        habitName: String,
        date: LocalDate,
        texts: List<String>,
        customEndDate: LocalDate,
        time: java.time.LocalTime? = null,
        onComplete: () -> Unit = {}
    ) {
        if (texts.isEmpty()) {
            onComplete()
            return
        }
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            onComplete()
            return
        }
        val effectiveTime = time ?: java.time.LocalTime.NOON
        val baseTimestamp = java.time.LocalDateTime.of(date, effectiveTime)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        viewModelScope.launch {
            try {
                // Save all entries atomically
                textInputRepo.appendMultipleTextEntries(
                    Uri.parse(uriString), context, texts, date, effectiveTime, habitName = habitName
                )

                // If this is a roll forward habit, also roll forward the text AND increment the habit counts
                if (habitName in _settings.value.rollForwardHabits) {
                    // Roll forward the first entry's text to all dates from date+1 to customEndDate
                    if (date < customEndDate) {
                        textInputRepo.rollForwardTextEntry(
                            Uri.parse(uriString),
                            context,
                            baseTimestamp,
                            date.plusDays(1),
                            customEndDate,
                            habitName = habitName
                        )
                    }

                    // Also increment the habit counts for the roll forward dates
                    val fileUriString = _settings.value.fileUri
                    if (fileUriString.isNotEmpty()) {
                        val dateStr = com.example.tail.data.dateString(date)
                        val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
                        val currentCount = currentEntries[dateStr] ?: 0
                        // Selecting multiple options is a single action — always +1.
                        val incrementAmount = 1
                        val newCount = currentCount + incrementAmount

                        // Update the count for the current date
                        var updatedDb = habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, incrementAmount, date)

                        // Track this date as manually set for roll forward habits
                        val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
                        currentManualDates.add(dateStr)
                        val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
                        updatedManualDates[habitName] = currentManualDates
                        settingsRepo.saveRollForwardManualDates(updatedManualDates)
                        _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)

                        // Roll forward the count to all dates from date+1 to customEndDate
                        val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
                        var currentDate = date.plusDays(1)
                        val endDate = customEndDate

                        while (currentDate <= endDate) {
                            val currentDateStr = com.example.tail.data.dateString(currentDate)
                            habitEntries[currentDateStr] = newCount
                            currentDate = currentDate.plusDays(1)
                        }

                        // Update the database with the filled entries
                        updatedDb = updatedDb.toMutableMap()
                        updatedDb[habitName] = habitEntries
                        cachedPhoneDb = updatedDb

                        // Persist the database
                        try {
                            val uri = Uri.parse(fileUriString)
                            habitsRepo.persistDatabase(uri, context, updatedDb)
                            HabitsDataChangedBus.emit()
                            rebuildHabitList()
                        } catch (e: Exception) {
                            _errorMessage.value = "Failed to save habit counts: ${e.message}"
                        }
                    }
                }

                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to set text entry: ${e.message}"
                onComplete()
            }
        }
    }

    // ── DB snapshots (crash/wipe recovery) ───────────────────────────────────

    /** UI-facing view of one habit-DB snapshot. */
    data class SnapshotUi(
        val fileName: String,
        val timestamp: Long,
        val entryCount: Int,
        val sizeBytes: Long
    )

    private val _snapshots = MutableStateFlow<List<SnapshotUi>>(emptyList())
    val snapshots: StateFlow<List<SnapshotUi>> = _snapshots.asStateFlow()

    private val _snapshotStatus = MutableStateFlow<String?>(null)
    val snapshotStatus: StateFlow<String?> = _snapshotStatus.asStateFlow()

    // ── Single-habit restore from a backup file ───────────────────────────
    /** Non-null while a restore-from-backup confirmation dialog is showing. */
    private val _habitRestorePreview = MutableStateFlow<HabitRestorePreview?>(null)
    val habitRestorePreview: StateFlow<HabitRestorePreview?> = _habitRestorePreview.asStateFlow()

    /** The backup URI pending confirmation (kept so [applyHabitRestore] can use it). */
    private val _pendingRestoreUri = MutableStateFlow<Uri?>(null)
    val pendingRestoreUri: StateFlow<Uri?> = _pendingRestoreUri.asStateFlow()

    /** Status / error message for the most recent single-habit restore. */
    private val _habitRestoreStatus = MutableStateFlow<String?>(null)
    val habitRestoreStatus: StateFlow<String?> = _habitRestoreStatus.asStateFlow()

    /** Loads the list of internal DB snapshots for the restore UI. */
    fun loadSnapshots() {
        viewModelScope.launch {
            try {
                val mgr = habitsRepo.snapshots(context)
                val infos = mgr.listSnapshots()
                _snapshots.value = infos.map { info ->
                    SnapshotUi(
                        fileName = info.file.name,
                        timestamp = info.timestamp,
                        entryCount = mgr.entryCountOf(info.file),
                        sizeBytes = info.sizeBytes
                    )
                }
            } catch (e: Exception) {
                _snapshotStatus.value = "Failed to list snapshots: ${e.message}"
            }
        }
    }

    /**
     * Restores the DB from the snapshot with [fileName], writing it back to the
     * configured habits file and reloading the in-memory state.
     */
    fun restoreSnapshot(fileName: String) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _snapshotStatus.value = "No habits file configured — cannot restore."
            return
        }
        viewModelScope.launch {
            _snapshotStatus.value = "Restoring…"
            try {
                val mgr = habitsRepo.snapshots(context)
                val info = mgr.listSnapshots().firstOrNull { it.file.name == fileName }
                if (info == null) {
                    _snapshotStatus.value = "Snapshot no longer exists."
                    return@launch
                }
                val db = mgr.readSnapshot(info.file)
                if (db == null) {
                    _snapshotStatus.value = "Snapshot is unreadable — cannot restore."
                    return@launch
                }
                val ok = habitsRepo.restoreDatabaseRaw(Uri.parse(uriString), context, db)
                if (ok) {
                    cachedPhoneDb = db
                    rebuildHabitList()
                    val count = db.values.sumOf { it.size }
                    _snapshotStatus.value = "Restored $count entries from ${fileName}."
                    loadSnapshots()
                } else {
                    _snapshotStatus.value = "Restore write failed."
                }
            } catch (e: Exception) {
                _snapshotStatus.value = "Restore failed: ${e.message}"
            }
        }
    }

    /** Clears the transient snapshot status message. */
    fun clearSnapshotStatus() { _snapshotStatus.value = null }

    // ── Single-habit restore from a backup file ───────────────────────────

    /**
     * Reads [backupUri], extracts the data for [habitName], and publishes a
     * non-destructive [HabitRestorePreview] via [habitRestorePreview] so the UI
     * can show a confirmation dialog. Does NOT modify any data.
     */
    fun previewHabitRestore(backupUri: Uri, habitName: String) {
        val mgr = backupManager
        if (mgr == null) {
            _habitRestoreStatus.value = "Backup manager unavailable."
            return
        }
        _habitRestoreStatus.value = "Reading backup…"
        viewModelScope.launch {
            val preview = mgr.previewSingleHabitRestore(backupUri, habitName)
            if (preview == null) {
                _habitRestoreStatus.value =
                    "Could not read that file as a Tail backup."
            } else {
                _pendingRestoreUri.value = backupUri
                _habitRestorePreview.value = preview
                _habitRestoreStatus.value = null
            }
        }
    }

    /** Dismisses the pending restore confirmation (no data is changed). */
    fun cancelHabitRestore() {
        _habitRestorePreview.value = null
        _pendingRestoreUri.value = null
    }

    /** Clears the transient single-habit restore status message. */
    fun clearHabitRestoreStatus() { _habitRestoreStatus.value = null }

    /**
     * Applies the pending single-habit restore (the URI stashed in
     * [_pendingRestoreUri] for the habit in the current preview), then reloads
     * the in-memory habit list so the UI reflects the restored data.
     */
    fun applyHabitRestore() {
        val mgr = backupManager
        val uri = _pendingRestoreUri.value
        val preview = _habitRestorePreview.value
        if (mgr == null || uri == null || preview == null) {
            _habitRestoreStatus.value = "Nothing to restore."
            return
        }
        val habitName = preview.habitName
        _habitRestorePreview.value = null
        _pendingRestoreUri.value = null
        _habitRestoreStatus.value = "Restoring '$habitName'…"
        viewModelScope.launch {
            val res = mgr.restoreSingleHabit(uri, habitName)
            when (res) {
                is BackupResult.Success -> {
                    // Refresh the in-memory cache + UI from the freshly-written DB.
                    val uriString = _settings.value.fileUri
                    if (uriString.isNotEmpty()) {
                        val fresh = habitsRepo.loadDatabase(Uri.parse(uriString), context)
                        cachedPhoneDb = fresh
                    }
                    rebuildHabitList()
                    _habitRestoreStatus.value = res.message
                }
                is BackupResult.Failure ->
                    _habitRestoreStatus.value = res.message
            }
        }
    }

    /**
     * Translates a raw manual input value into the value that should be STORED for
     * [habitName]. For habits with custom point ranges enabled, the stored value is
     * the points tier (the index of the first range containing the raw value) — the
     * same contract used by [applyGarminData] and [recalculateHabitPointsForCustomRanges].
     * Returns null when the habit does not use custom point ranges.
     */
    private fun customRangePointsForInput(habitName: String, rawValue: Int): Int? {
        if (habitName !in _settings.value.customPointRangesHabits) return null
        val ranges = _settings.value.customPointRanges[habitName] ?: return null
        return com.example.tail.data.calculatePointsFromRanges(rawValue, ranges)
    }

    /**
     * Sets the count for [habitName] on the currently selected date to an absolute [newCount].
     * [newCount] is the raw value to store. Clamps to >= 0. Persists to the DB file.
     * For habits with custom point ranges enabled, [newCount] is treated as the raw input
     * ("true value") and the calculated points tier is stored instead — matching the
     * Garmin-linked write path.
     */
    fun setHabitCount(habitName: String, newCount: Int) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }
        val clamped = newCount.coerceAtLeast(0)
        val divider = _settings.value.habitDividers[habitName] ?: 1
        val rangePoints = customRangePointsForInput(habitName, clamped)
        val storedValue = rangePoints ?: clamped

        // Step 1: instant targeted UI update
        _habits.value = _habits.value.map { h ->
            if (h.name == habitName) h.copy(
                todayCount = rangePoints ?: if (habitName in _settings.value.invertedBinaryHabits) {
                    com.example.tail.data.invertedBinaryPoints(clamped)
                } else applyDivider(clamped, divider),
                rawTodayCount = storedValue
            ) else h
        }
        // Keep per-screen cache in sync
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

        // Step 2: update in-memory cache — compute delta from current stored value
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
        val currentCount = currentEntries[dateStr] ?: 0
        val delta = storedValue - currentCount
        
        // For roll forward habits, find the next manually set date BEFORE applying the change
        val nextManualDate = if (habitName in _settings.value.rollForwardHabits && delta != 0) {
            val manualDates = _settings.value.rollForwardManualDates[habitName] ?: emptySet()
            manualDates.mapNotNull { dateStr ->
                com.example.tail.data.parseDate(dateStr)
            }.sorted()
            .firstOrNull { it > _selectedDate.value }
        } else null
        
        var updatedDb = if (delta != 0) {
            habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, delta, _selectedDate.value)
        } else {
            cachedPhoneDb
        }
        
        // Step 2.5: Track this date as manually set for roll forward habits
        if (habitName in _settings.value.rollForwardHabits && delta != 0) {
            val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
            currentManualDates.add(dateStr)
            val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
            updatedManualDates[habitName] = currentManualDates
            viewModelScope.launch {
                settingsRepo.saveRollForwardManualDates(updatedManualDates)
                _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)
            }
        }

        // Step 2.6: Roll forward logic - fill subsequent days for roll forward habits
        if (habitName in _settings.value.rollForwardHabits && delta != 0) {
            val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
            val selectedDate = _selectedDate.value
            val today = java.time.LocalDate.now()
            
            // Fill all dates from selectedDate to nextManualDate (exclusive) or today (inclusive)
            var currentDate = selectedDate.plusDays(1)
            val endDate = nextManualDate?.minusDays(1) ?: today
            
            while (currentDate <= endDate) {
                val currentDateStr = com.example.tail.data.dateString(currentDate)
                habitEntries[currentDateStr] = storedValue
                currentDate = currentDate.plusDays(1)
            }
            
            // Update the database with the filled entries
            updatedDb = updatedDb.toMutableMap()
            updatedDb[habitName] = habitEntries
        }
        
        cachedPhoneDb = updatedDb

        // Step 3: full rebuild + disk write in background
        viewModelScope.launch {
            rebuildHabitList()
            try {
                val uri = Uri.parse(uriString)
                habitsRepo.persistDatabase(uri, context, updatedDb)
                HabitsDataChangedBus.emit()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save: ${e.message}"
            }
        }
    }

    /**
     * Returns the SECONDARY value (e.g. timer minutes, stored under
     * `secondary_value:<habitName>`) for [habitName] on the currently
     * selected date. Returns 0 if the habit has no secondary value.
     */
    fun getSecondaryTodayCount(habitName: String): Int {
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        return cachedPhoneDb[com.example.tail.data.secondaryValueKey(habitName)]?.get(dateStr) ?: 0
    }

    /**
     * Sets the SECONDARY value (e.g. timer minutes, stored under
     * `secondary_value:<habitName>`) for [habitName] on the currently
     * selected date to an absolute [newCount]. Clamps to >= 0 and persists
     * to the DB file. Used by the edit-mode value picker for timer habits.
     */
    fun setHabitSecondaryCount(habitName: String, newCount: Int) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }
        val clamped = newCount.coerceAtLeast(0)
        val secKey = com.example.tail.data.secondaryValueKey(habitName)
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)

        // Step 1: instant in-memory cache update
        val secEntries = cachedPhoneDb[secKey]?.toMutableMap() ?: mutableMapOf()
        secEntries[dateStr] = clamped
        val updatedDb = cachedPhoneDb.toMutableMap()
        updatedDb[secKey] = secEntries.toSortedMap()
        cachedPhoneDb = updatedDb

        // Step 2: rebuild (minutes drive points for minutes-primary habits)
        // + disk write in background
        viewModelScope.launch {
            rebuildHabitList()
            try {
                habitsRepo.persistDatabase(Uri.parse(uriString), context, updatedDb)
                HabitsDataChangedBus.emit()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save: ${e.message}"
            }
        }
    }

    /**
     * Sets the count for [habitName] on the currently selected date to an absolute [newCount]
     * with roll forward to a specified end date.
     * This is used when the user confirms the roll forward dialog for count changes.
     */
    fun setHabitCountWithRollForward(
        habitName: String,
        newCount: Int,
        customEndDate: LocalDate,
        onComplete: () -> Unit = {}
    ) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            onComplete()
            return
        }
        val clamped = newCount.coerceAtLeast(0)
        val divider = _settings.value.habitDividers[habitName] ?: 1
        val rangePoints = customRangePointsForInput(habitName, clamped)
        val storedValue = rangePoints ?: clamped

        // Step 1: instant targeted UI update
        _habits.value = _habits.value.map { h ->
            if (h.name == habitName) h.copy(
                todayCount = rangePoints ?: if (habitName in _settings.value.invertedBinaryHabits) {
                    com.example.tail.data.invertedBinaryPoints(clamped)
                } else applyDivider(clamped, divider),
                rawTodayCount = storedValue
            ) else h
        }
        // Keep per-screen cache in sync
        screenHabitCache[Pair(_activeScreenIndex.value, _selectedDate.value)] = _habits.value

        // Step 2: update in-memory cache — compute delta from current stored value
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val currentEntries = cachedPhoneDb[habitName] ?: emptyMap()
        val currentCount = currentEntries[dateStr] ?: 0
        val delta = storedValue - currentCount
        
        var updatedDb = if (delta != 0) {
            habitsRepo.applyIncrementToDb(cachedPhoneDb, habitName, delta, _selectedDate.value)
        } else {
            cachedPhoneDb
        }
        
        // Step 2.5: Track this date as manually set for roll forward habits
        if (habitName in _settings.value.rollForwardHabits && delta != 0) {
            val currentManualDates = _settings.value.rollForwardManualDates[habitName]?.toMutableSet() ?: mutableSetOf()
            currentManualDates.add(dateStr)
            val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
            updatedManualDates[habitName] = currentManualDates
            viewModelScope.launch {
                settingsRepo.saveRollForwardManualDates(updatedManualDates)
                _settings.value = _settings.value.copy(rollForwardManualDates = updatedManualDates)
            }
        }

        // Step 2.6: Roll forward logic - fill subsequent days for roll forward habits
        if (habitName in _settings.value.rollForwardHabits && delta != 0) {
            val habitEntries = updatedDb[habitName]?.toMutableMap() ?: mutableMapOf()
            val selectedDate = _selectedDate.value
            
            // Fill all dates from selectedDate+1 to customEndDate (inclusive)
            var currentDate = selectedDate.plusDays(1)
            val endDate = customEndDate
            
            while (currentDate <= endDate) {
                val currentDateStr = com.example.tail.data.dateString(currentDate)
                habitEntries[currentDateStr] = storedValue
                currentDate = currentDate.plusDays(1)
            }
            
            // Update the database with the filled entries
            updatedDb = updatedDb.toMutableMap()
            updatedDb[habitName] = habitEntries
        }
        
        cachedPhoneDb = updatedDb

        // Step 3: full rebuild + disk write in background
        viewModelScope.launch {
            rebuildHabitList()
            try {
                val uri = Uri.parse(uriString)
                habitsRepo.persistDatabase(uri, context, updatedDb)
                HabitsDataChangedBus.emit()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save: ${e.message}"
            }
            onComplete()
        }
    }

    /**
     * Toggles the "1 max" cap on/off for [habitName].
     * When enabled, the habit's daily count can never exceed 1 (binary done/not-done).
     */
    fun toggleMaxOne(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.maxOneHabits.toMutableSet()
            val wasMaxOne = habitName in current
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveMaxOneHabits(current)
            // Enabling max-1 turns minutes OFF: a binary done/not-done habit
            // has no duration, so the minutes slot, minutes-primary role and
            // a minutes-sourced points fallback all stop making sense.
            var newSettings = _settings.value.copy(maxOneHabits = current)
            if (!wasMaxOne && habitName in current) {
                var primary = newSettings.widgetTimerMinutesPrimary
                var fallback = newSettings.secondaryValueFallbackHabits
                var minutes = newSettings.minutesEnabledHabits
                if (habitName in primary) primary = primary - habitName
                if (habitName in minutes) minutes = minutes - habitName
                // Only drop the fallback flag when its source is the minutes
                // slot (a legacy secondary_value: fallback keeps working).
                val legacyFallbackSource = habitName in newSettings.secondaryValueHabits ||
                    !cachedPhoneDb[secondaryValueKey(habitName)].isNullOrEmpty()
                if (habitName in fallback && !legacyFallbackSource) fallback = fallback - habitName
                if (primary != newSettings.widgetTimerMinutesPrimary) {
                    settingsRepo.saveWidgetTimerMinutesPrimary(primary)
                }
                if (fallback != newSettings.secondaryValueFallbackHabits) {
                    settingsRepo.saveSecondaryValueFallbackHabits(fallback)
                }
                if (minutes != newSettings.minutesEnabledHabits) {
                    settingsRepo.saveMinutesEnabledHabits(minutes)
                }
                newSettings = newSettings.copy(
                    widgetTimerMinutesPrimary = primary,
                    secondaryValueFallbackHabits = fallback,
                    minutesEnabledHabits = minutes
                )
            }
            _settings.value = newSettings
        }
    }

    /**
     * Toggles the "inverted binary" type on/off for [habitName].
     * When enabled, points and streaks are inverted: a day with no taps earns
     * 1 point and extends the streak; a day with one or more taps earns 0
     * points and breaks the streak (antistreak).
     */
    fun toggleInvertedBinary(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.invertedBinaryHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveInvertedBinaryHabits(current)
            _settings.value = _settings.value.copy(invertedBinaryHabits = current)
            // Rebuild so streaks/points immediately reflect the new semantics
            rebuildHabitList()
        }
    }

    /**
     * Returns the number of historical days for [habitName] whose stored count exceeds 1
     * — i.e. the days that would be capped if "1 max" were applied retroactively.
     * Used to preview the impact before committing.
     */
    fun previewMaxOneAffectedDays(habitName: String): Int {
        val entries = cachedPhoneDb[habitName] ?: return 0
        return entries.values.count { it > 1 }
    }

    /**
     * Caps every historical entry for [habitName] to a maximum of 1.
     * Called after enabling "1 max" when the user chooses to update past totals.
     * Days already at 0 or 1 are left untouched; only counts > 1 are reduced.
     */
    fun applyMaxOneToHistory(habitName: String) {
        val uri = _settings.value.fileUri
        if (uri.isEmpty()) return
        viewModelScope.launch {
            val loadResult = habitsRepo.loadDatabaseResult(
                android.net.Uri.parse(uri),
                context
            )
            if (loadResult !is com.example.tail.data.HabitsLoadResult.Success) return@launch

            val db = loadResult.db.toMutableMap()
            val habitEntries = db[habitName]?.toMutableMap() ?: return@launch

            var changed = false
            for ((dateStr, rawCount) in habitEntries) {
                if (rawCount > 1) {
                    habitEntries[dateStr] = 1
                    changed = true
                }
            }
            if (!changed) return@launch

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

    /**
     * Returns the number of past days for [habitName] whose stored count is lower
     * than the number of recorded timestamps for that day — i.e. days that were
     * capped by "1 max" but whose true increment count is preserved in the
     * timestamp log. Used to preview the restoration impact before committing.
     */
    fun previewMaxOneRestorableDays(habitName: String): Int {
        val entries = cachedPhoneDb[habitName] ?: return 0
        val tsCounts = timestampRepo.getTimestampCountsForHabitSync(habitName)
        var count = 0
        for ((dateStr, rawCount) in entries) {
            val tsCount = tsCounts[dateStr] ?: 0
            if (tsCount > rawCount) count++
        }
        return count
    }

    /**
     * Restores the true increment count for every past day of [habitName] using
     * the recorded timestamps. Called after disabling "1 max" when the user
     * chooses to make past entries count fully toward totals again.
     *
     * Only days where the timestamp count exceeds the current (capped) stored
     * count are updated; all other days are left untouched.
     */
    fun restoreMaxOneFromTimestamps(habitName: String) {
        val uri = _settings.value.fileUri
        if (uri.isEmpty()) return
        viewModelScope.launch {
            val loadResult = habitsRepo.loadDatabaseResult(
                android.net.Uri.parse(uri),
                context
            )
            if (loadResult !is com.example.tail.data.HabitsLoadResult.Success) return@launch

            val db = loadResult.db.toMutableMap()
            val habitEntries = db[habitName]?.toMutableMap() ?: return@launch

            val tsCounts = timestampRepo.getTimestampCountsForHabitSync(habitName)

            var changed = false
            for ((dateStr, rawCount) in habitEntries) {
                val tsCount = tsCounts[dateStr] ?: 0
                if (tsCount > rawCount) {
                    habitEntries[dateStr] = tsCount
                    changed = true
                }
            }
            if (!changed) return@launch

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
     * When disabled, the linked set is removed as well — otherwise the orphaned entry
     * keeps showing up as a phantom "Fed by" source on the habits it used to link to.
     */
    fun toggleConditional(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.conditionalHabits.toMutableSet()
            val links = _settings.value.conditionalLinkedHabits.toMutableMap()
            val values = _settings.value.conditionalLinkValues.toMutableMap()
            val feedMaxOne = _settings.value.conditionalFeedMaxOneHabits.toMutableSet()
            val feedPoints = _settings.value.conditionalFeedPointsHabits.toMutableSet()
            var linksChanged = false
            if (habitName in current) {
                current.remove(habitName)
                if (links.remove(habitName) != null) linksChanged = true
                if (values.remove(habitName) != null) linksChanged = true
                if (feedMaxOne.remove(habitName)) linksChanged = true
                if (feedPoints.remove(habitName)) linksChanged = true
            } else {
                current.add(habitName)
            }
            settingsRepo.saveConditionalHabits(current)
            if (linksChanged) {
                settingsRepo.saveConditionalLinkedHabits(links)
                settingsRepo.saveConditionalLinkValues(values)
                settingsRepo.saveConditionalFeedMaxOneHabits(feedMaxOne)
                settingsRepo.saveConditionalFeedPointsHabits(feedPoints)
                _settings.value = _settings.value.copy(
                    conditionalHabits = current,
                    conditionalLinkedHabits = links,
                    conditionalLinkValues = values,
                    conditionalFeedMaxOneHabits = feedMaxOne,
                    conditionalFeedPointsHabits = feedPoints
                )
            } else {
                _settings.value = _settings.value.copy(conditionalHabits = current)
            }
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
            // Keep feed-value overrides limited to the (new) link set
            val values = _settings.value.conditionalLinkValues.toMutableMap()
            val trimmed = values[habitName]?.filterKeys { it in linkedNames }
            if (trimmed.isNullOrEmpty()) values.remove(habitName) else values[habitName] = trimmed
            settingsRepo.saveConditionalLinkedHabits(current)
            settingsRepo.saveConditionalLinkValues(values)
            _settings.value = _settings.value.copy(
                conditionalLinkedHabits = current,
                conditionalLinkValues = values
            )
        }
    }

    /** Returns the current set of linked habit names for a conditional habit. */
    fun getConditionalLinks(habitName: String): Set<String> =
        _settings.value.conditionalLinkedHabits[habitName] ?: emptySet()

    /** Returns the current per-link feed-value overrides for a conditional habit. */
    fun getConditionalLinkValues(habitName: String): Map<String, String> =
        _settings.value.conditionalLinkValues[habitName] ?: emptyMap()

    /**
     * Sets the per-link feed-value overrides for [habitName] (linked habit name →
     * value key). Only non-default (non-Points) entries for currently linked
     * habits are stored; everything else falls back to Points.
     */
    fun setConditionalLinkValues(habitName: String, values: Map<String, String>) {
        viewModelScope.launch {
            val links = _settings.value.conditionalLinkedHabits[habitName] ?: emptySet()
            val cleaned = values.filterKeys { it in links && it != GRAPH_METRIC_POINTS }
            val current = _settings.value.conditionalLinkValues.toMutableMap()
            if (cleaned.isEmpty()) current.remove(habitName) else current[habitName] = cleaned
            settingsRepo.saveConditionalLinkValues(current)
            _settings.value = _settings.value.copy(conditionalLinkValues = current)
        }
    }

    /**
     * Toggles the "feed max1 point/day" sub-setting for a conditional habit.
     * When enabled, the habit's Points feeds are capped: the first increment
     * of a day feeds each linked habit at most 1 point; later increments that
     * day feed nothing. Secondary-slot (Value2/Value3) feeds are not capped.
     */
    fun toggleConditionalFeedMaxOne(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.conditionalFeedMaxOneHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveConditionalFeedMaxOneHabits(current)
            _settings.value = _settings.value.copy(conditionalFeedMaxOneHabits = current)
        }
    }

    /**
     * Toggles the "feed points" sub-setting for a conditional habit.
     * When enabled, the habit's feeds send its POINTS delta (the divider-
     * applied value, exactly what the habit tile displays) instead of the
     * raw increment amount — e.g. a minutes habit with ÷2 feeding +30
     * sends +15 to its linked habits. Only meaningful when the habit has
     * a divider > 1; otherwise points equal the raw count.
     */
    fun toggleConditionalFeedPoints(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.conditionalFeedPointsHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveConditionalFeedPointsHabits(current)
            _settings.value = _settings.value.copy(conditionalFeedPointsHabits = current)
        }
    }

    /**
     * Returns the list of conditional habits that have [habitName] in their linked set.
     * These are the "source" habits whose increments feed into [habitName] — i.e. every
     * habit that "has [habitName] set as a conditional" for it.
     * Only entries whose key is still an active conditional habit count; orphaned link
     * entries (habit no longer marked conditional) are ignored.
     */
    fun getConditionalSources(habitName: String): List<String> =
        _settings.value.conditionalLinkedHabits.entries
            .filter { it.key in _settings.value.conditionalHabits && habitName in it.value }
            .map { it.key }
            .sorted()

    /**
     * Groups the conditional sources of [habitName] by the storage slot they feed:
     * the habit's own key for Points (the default), or its secondary-value slots
     * for links configured to feed Value2 / Value3.
     */
    private fun conditionalBackfillSlots(habitName: String): Map<String, List<String>> {
        val s = _settings.value
        return getConditionalSources(habitName).groupBy { src ->
            conditionalLinkStorageKey(
                habitName,
                effectiveConditionalLinkValueKey(
                    s.conditionalLinkValues, s.secondaryValueHabits, s.chessComHabitLinks,
                    src, habitName
                )
            )
        }
    }

    /**
     * Computes the total number of increments that a conditional backfill would apply
     * to [habitName] across its entire history, by summing the per-day counts of every
     * source habit (conditional habits that link to [habitName]) into the value slot
     * each link is configured to feed.
     *
     * Respects the "1 max" cap per day when [habitName] is a max-one habit, matching the
     * live conditional-increment behaviour.
     */
    fun previewConditionalBackfillTotal(habitName: String): Int {
        val slots = conditionalBackfillSlots(habitName)
        if (slots.isEmpty()) return 0
        val isMaxOne = habitName in _settings.value.maxOneHabits
        val feedMaxOneSources = _settings.value.conditionalFeedMaxOneHabits
        var total = 0
        for ((slotKey, slotSources) in slots) {
            val capped = slotKey == habitName && isMaxOne
            val dates = mutableSetOf<String>()
            for (src in slotSources) {
                dates.addAll(cachedPhoneDb[src]?.keys ?: emptySet())
            }
            for (d in dates) {
                var sum = 0
                for (src in slotSources) {
                    val c = cachedPhoneDb[src]?.get(d) ?: 0
                    sum += if (slotKey == habitName && src in feedMaxOneSources) c.coerceAtMost(1) else c
                }
                total += if (capped) sum.coerceAtMost(1) else sum
            }
        }
        return total
    }

    /**
     * Performs a complete conditional backfill for [habitName].
     *
     * Overwrites [habitName]'s entire history so that, for every day, its stored count
     * equals the sum of the counts of every source habit (conditional habits that link
     * to it) on that day. This destroys any manually-entered data for [habitName] and
     * persists the recomputed values to the habits file.
     */
    fun performConditionalBackfill(habitName: String) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }
        val slots = conditionalBackfillSlots(habitName)
        if (slots.isEmpty()) {
            _errorMessage.value = "No habits feed into \"$habitName\"."
            return
        }
        val isMaxOne = habitName in _settings.value.maxOneHabits

        // Recompute each fed slot independently: for every day, the slot's stored
        // value equals the sum of the counts of the source habits feeding it.
        // (The max-1 cap only applies to the primary count slot.)
        val updatedDb = cachedPhoneDb.toMutableMap()
        var totalApplied = 0
        var daysTouched = 0
        for ((slotKey, slotSources) in slots) {
            val capped = slotKey == habitName && isMaxOne
            val dates = mutableSetOf<String>()
            for (src in slotSources) {
                dates.addAll(cachedPhoneDb[src]?.keys ?: emptySet())
            }
            val newEntries = sortedMapOf<String, Int>()
            for (d in dates.sorted()) {
                var sum = 0
                for (src in slotSources) {
                    val c = cachedPhoneDb[src]?.get(d) ?: 0
                    sum += if (slotKey == habitName && src in _settings.value.conditionalFeedMaxOneHabits) {
                        c.coerceAtMost(1)
                    } else c
                }
                val stored = if (capped) sum.coerceAtMost(1) else sum
                if (stored > 0) {
                    newEntries[d] = stored
                    totalApplied += stored
                }
            }
            daysTouched = maxOf(daysTouched, newEntries.size)
            updatedDb[slotKey] = newEntries
        }
        cachedPhoneDb = updatedDb

        viewModelScope.launch {
            rebuildHabitList()
            try {
                val uri = Uri.parse(uriString)
                habitsRepo.persistDatabase(uri, context, updatedDb)
                HabitsDataChangedBus.emit()
                _errorMessage.value =
                    "Backfilled \"$habitName\": $totalApplied increments across $daysTouched day(s), ${slots.size} value slot(s)."
            } catch (e: Exception) {
                _errorMessage.value = "Backfill save failed: ${e.message}"
            }
        }
    }

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

    /**
     * Loads today's subtype breakdown for [habitName], then calls [onLoaded] with the result.
     * Returns empty map if no data exists for today.
     */
    fun loadSubtypeBreakdown(habitName: String, onLoaded: (Map<String, Int>) -> Unit) {
        viewModelScope.launch {
            val dateStr = com.example.tail.data.dateString(_selectedDate.value)
            val breakdown = subtypeDataRepo.getBreakdownForDate(habitName, dateStr)
            onLoaded(breakdown)
        }
    }

    /**
     * Saves a subtype increment: adds [increments] to the internal subtype store for today,
     * and increments the main habit count by the total.
     */
    fun saveSubtypeIncrement(habitName: String, increments: Map<String, Int>) {
        val total = increments.values.sum()
        if (total <= 0) return

        // Increment the main habit count
        incrementHabit(habitName, total)

        // Save subtype breakdown (internal store)
        viewModelScope.launch {
            val dateStr = com.example.tail.data.dateString(_selectedDate.value)
            subtypeDataRepo.addToDate(habitName, dateStr, increments)
        }

        // If this is a timed habit, also record timestamped session entries
        if (habitName in _settings.value.timedHabits) {
            viewModelScope.launch {
                // Each subtype increment becomes a separate timed entry
                // (key type widened to String? since plain timed entries have no subtype)
                timedDataRepo.appendEntries(habitName, increments.mapKeys { (k, _) -> k as String? })
            }
        }
    }

    // ── Weights habit type (machine / free weight + reps logging) ─────────

    /** Records an exercise/machine name for quick re-entry (most recent first, capped at 10). */
    private fun recordRecentExercise(habitName: String, trimmedExercise: String) {
        if (trimmedExercise.isEmpty()) return
        viewModelScope.launch {
            val current = _settings.value.weightsRecentExercises.toMutableMap()
            val existing = current[habitName]?.toMutableList() ?: mutableListOf()
            existing.remove(trimmedExercise)          // move-to-front on re-use
            existing.add(0, trimmedExercise)
            if (existing.size > 10) existing.subList(10, existing.size).clear()
            current[habitName] = existing
            settingsRepo.saveWeightsRecentExercises(current)
            _settings.value = _settings.value.copy(weightsRecentExercises = current)
        }
    }

    /**
     * Saves a weights-habit log entry: the slot writes (weight max-merge +
     * reps accumulate) are applied to the in-memory DB FIRST, then the +1 on
     * the habit's own count is routed through the regular increment path —
     * whose single background persist then carries count + slots in ONE
     * atomic disk write. (The previous shape — incrementHabit's async
     * persist racing a separate disk load-modify-save for the slots — could
     * interleave and silently lose the +1 or the slots.)
     */
    fun saveWeightsEntry(
        habitName: String,
        weightGrams: Int,
        reps: Int,
        machine: Boolean,
        exerciseName: String = ""
    ) {
        if (weightGrams <= 0 && reps <= 0) return
        val uriString = _settings.value.fileUri
        if (uriString.isNullOrEmpty()) return

        // Slot writes FIRST: the increment path below snapshots
        // cachedPhoneDb (applyIncrementToDb) and persists that snapshot,
        // so the slots must already be in it.
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val countBefore = cachedPhoneDb[habitName]?.get(dateStr) ?: 0
        cachedPhoneDb = habitsRepo.applyWeightsSlotsToDb(
            cachedPhoneDb, habitName, weightGrams, reps, machine, _selectedDate.value
        )

        // One logged entry → +1 on the habit's own count (instant UI update,
        // timestamps, broadcast, conditional feeds; persists count + slots
        // together in one write).
        incrementHabit(habitName, 1)

        // incrementHabit early-returns WITHOUT persisting when the count
        // cannot change (max-1 cap already at 1, unchanged point tier). In
        // that case persist the slot writes ourselves so they are not lost.
        val countAfter = cachedPhoneDb[habitName]?.get(dateStr) ?: 0
        if (countAfter == countBefore) {
            viewModelScope.launch {
                rebuildHabitList()
                try {
                    habitsRepo.persistDatabase(Uri.parse(uriString), context, cachedPhoneDb)
                    HabitsDataChangedBus.emit()
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to save weights entry: ${e.message}"
                }
            }
        }

        // Remember the exercise/machine name for quick re-entry (most recent first)
        recordRecentExercise(habitName, exerciseName.trim())
    }

    /** Returns the selected date's aggregated weights slots for a weights habit. */
    fun getWeightsDayValues(habitName: String): WeightsDayValues {
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        fun slot(key: String) = cachedPhoneDb[key]?.get(dateStr) ?: 0
        return WeightsDayValues(
            machineWeightGrams = slot(com.example.tail.data.secondaryValueKey(habitName)),
            machineReps = slot(com.example.tail.data.secondaryValueSlotKey(habitName, 2)),
            freeWeightGrams = slot(com.example.tail.data.secondaryValueSlotKey(habitName, 3)),
            freeReps = slot(com.example.tail.data.secondaryValueSlotKey(habitName, 4))
        )
    }

    /**
     * Overwrites the selected date's weights slots absolutely (edit-mode day
     * editor). Zero values clear the slot for the day. The habit's own count
     * (number of logged entries) is not touched.
     */
    fun setWeightsDayValues(habitName: String, values: WeightsDayValues, exerciseName: String = "") {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val updatedDb = cachedPhoneDb.toMutableMap()
        fun setSlot(key: String, value: Int) {
            val entries = updatedDb[key]?.toMutableMap() ?: mutableMapOf()
            if (value > 0) entries[dateStr] = value else entries.remove(dateStr)
            if (entries.isEmpty()) updatedDb.remove(key) else updatedDb[key] = entries.toSortedMap()
        }
        setSlot(com.example.tail.data.secondaryValueKey(habitName), values.machineWeightGrams.coerceAtLeast(0))
        setSlot(com.example.tail.data.secondaryValueSlotKey(habitName, 2), values.machineReps.coerceAtLeast(0))
        setSlot(com.example.tail.data.secondaryValueSlotKey(habitName, 3), values.freeWeightGrams.coerceAtLeast(0))
        setSlot(com.example.tail.data.secondaryValueSlotKey(habitName, 4), values.freeReps.coerceAtLeast(0))
        cachedPhoneDb = updatedDb
        viewModelScope.launch {
            rebuildHabitList()
            try {
                habitsRepo.persistDatabase(Uri.parse(uriString), context, updatedDb)
                HabitsDataChangedBus.emit()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save weights values: ${e.message}"
            }
        }

        // Keep the exercise quick-choices fresh when an edited entry names one
        recordRecentExercise(habitName, exerciseName.trim())
    }

    /**
     * Removes ALL weights data for [habitName] on the selected date: the four
     * slots (machine/free weight + reps), the day's count (increment) and its
     * timestamps — the "totally remove it for a day" edit-screen action.
     */
    fun deleteWeightsDay(habitName: String) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) {
            _errorMessage.value = "No file selected. Please pick a file in Settings."
            return
        }
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        val updatedDb = cachedPhoneDb.toMutableMap()

        // Clear the four weights slots for the day
        for (key in listOf(
            com.example.tail.data.secondaryValueKey(habitName),
            com.example.tail.data.secondaryValueSlotKey(habitName, 2),
            com.example.tail.data.secondaryValueSlotKey(habitName, 3),
            com.example.tail.data.secondaryValueSlotKey(habitName, 4)
        )) {
            val entries = updatedDb[key]?.toMutableMap() ?: continue
            entries.remove(dateStr)
            if (entries.isEmpty()) updatedDb.remove(key) else updatedDb[key] = entries
        }

        // Remove the day's increment (count)
        updatedDb[habitName]?.let { entries ->
            val mutable = entries.toMutableMap()
            mutable.remove(dateStr)
            if (mutable.isEmpty()) updatedDb.remove(habitName) else updatedDb[habitName] = mutable
        }

        cachedPhoneDb = updatedDb
        viewModelScope.launch {
            rebuildHabitList()
            // Also drop the day's timestamps (separate store, no DB race)
            timestampRepo.setTimestampsForDay(habitName, _selectedDate.value, emptyList())
            try {
                habitsRepo.persistDatabase(Uri.parse(uriString), context, updatedDb)
                HabitsDataChangedBus.emit()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete weights day: ${e.message}"
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

    /** Toggles the "roll forward" feature on/off for [habitName]. */
    fun toggleRollForward(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.rollForwardHabits.toMutableSet()
            if (habitName in current) {
                // Disabling: remove from set and clear manual dates
                current.remove(habitName)
                val updatedManualDates = _settings.value.rollForwardManualDates.toMutableMap()
                updatedManualDates.remove(habitName)
                settingsRepo.saveRollForwardManualDates(updatedManualDates)
                _settings.value = _settings.value.copy(
                    rollForwardHabits = current,
                    rollForwardManualDates = updatedManualDates
                )
            } else {
                // Enabling: just add to set
                current.add(habitName)
                settingsRepo.saveRollForwardHabits(current)
                _settings.value = _settings.value.copy(rollForwardHabits = current)
            }
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
     * Begins an edit-mode long-press drag of the cell at [index]. Cancels any
     * tap-to-move pending state (the drag supersedes it) but deliberately does
     * NOT select the cell — the edit drawer must only open on a single tap,
     * never on a long-press drag. The drag itself is tracked in the UI layer;
     * only the final drop is committed back to the ViewModel.
     */
    fun beginHabitDrag(index: Int) {
        if (index < 0) return
        _movePendingSourceIndex.value = -1
    }

    /**
     * Commits a same-screen drag-and-drop move from [fromIdx] to [toIdx].
     * Public wrapper around [applyMove] for the gesture-driven reorder flow.
     */
    fun commitHabitMove(fromIdx: Int, toIdx: Int) {
        if (fromIdx == toIdx) return
        viewModelScope.launch { applyMove(fromIdx, toIdx) }
    }

    /**
     * Commits a cross-screen drag-and-drop move: [habitName] is removed from
     * whichever screen currently holds it (leaving a placeholder behind, like
     * [moveHabitToScreen]) and inserted into [targetScreenIndex] at
     * [targetCellIndex] with the same shift-right semantics as [applyMove].
     */
    fun commitCrossScreenDrag(habitName: String, targetScreenIndex: Int, targetCellIndex: Int) {
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
     * This triggers a full recalculation of all historical data and external files.
     */
    fun toggleNoPointsHabit(habitName: String) {
        val current = _settings.value.noPointsHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        _settings.value = _settings.value.copy(noPointsHabits = current)
        viewModelScope.launch {
            settingsRepo.saveNoPointsHabits(current)
        }
    }

    /**
     * Toggles the "Secondary Value" feature for [habitName].
     * When enabled, the habit stores a second integer value per day (accessible
     * via the graph screen's "Value2" button). Secondary values are stored in
     * habitsdb.txt under the key "secondary_value:<habitName>".
     */
    fun toggleSecondaryValueHabit(habitName: String) {
        val current = _settings.value.secondaryValueHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        _settings.value = _settings.value.copy(secondaryValueHabits = current)
        viewModelScope.launch { settingsRepo.saveSecondaryValueHabits(current) }
    }

    /**
     * Toggles the "Secondary Value Fallback for Points" feature for [habitName].
     *
     * When enabled (and the habit also has Secondary Value enabled), days where
     * the primary value (Value1) is zero will fall back to the secondary value
     * (Value2) for points calculation. The fallback points equal the raw
     * secondary value (no divider applied).
     *
     * Requires the habit to also be in [secondaryValueHabits]. If the habit is
     * not in [secondaryValueHabits], enabling this will also enable Secondary Value.
     */
    fun toggleSecondaryValueFallbackHabit(habitName: String) {
        val current = _settings.value.secondaryValueFallbackHabits.toMutableSet()
        if (habitName in current) {
            current.remove(habitName)
        } else {
            current.add(habitName)
            // Auto-enable Secondary Value if not already enabled
            if (habitName !in _settings.value.secondaryValueHabits) {
                val secValHabits = _settings.value.secondaryValueHabits.toMutableSet()
                secValHabits.add(habitName)
                _settings.value = _settings.value.copy(secondaryValueHabits = secValHabits)
                viewModelScope.launch { settingsRepo.saveSecondaryValueHabits(secValHabits) }
            }
        }
        _settings.value = _settings.value.copy(secondaryValueFallbackHabits = current)
        viewModelScope.launch {
            settingsRepo.saveSecondaryValueFallbackHabits(current)
            rebuildHabitList()
        }
    }

    /** Returns true if [habitName] has the secondary value fallback feature enabled. */
    fun hasSecondaryValueFallback(habitName: String): Boolean {
        return habitName in _settings.value.secondaryValueFallbackHabits
    }

    // ── Display-only value/subtype label overrides ──────────────────────────

    /**
     * Returns the display label for [habitName]'s [valueKey], using the custom
     * override if one exists, otherwise the default label.
     *
     * This is **display-only** — the underlying [valueKey] is never modified.
     */
    fun getValueDisplayLabel(habitName: String, valueKey: String): String {
        return com.example.tail.data.displayLabelForValue(
            habitName, valueKey, _settings.value.valueDisplayLabels
        )
    }

    /**
     * Sets a custom display label for [habitName]'s [valueKey].
     *
     * If [label] is blank the override is removed (falls back to default).
     * The backend [valueKey] (e.g. `"value2"` or a subtype name) is never changed.
     */
    fun setValueDisplayLabel(habitName: String, valueKey: String, label: String) {
        val current = _settings.value.valueDisplayLabels.toMutableMap()
        val inner = current[habitName]?.toMutableMap() ?: mutableMapOf()
        if (label.isBlank()) {
            inner.remove(valueKey)
        } else {
            inner[valueKey] = label
        }
        if (inner.isEmpty()) {
            current.remove(habitName)
        } else {
            current[habitName] = inner
        }
        _settings.value = _settings.value.copy(valueDisplayLabels = current)
        viewModelScope.launch { settingsRepo.saveValueDisplayLabels(current) }
    }

    /**
     * Computes the effective points for [habitName] on the given [dateStr],
     * applying the secondary-value fallback when enabled.
     */
    private fun effectivePointsForDate(habitName: String, rawCount: Int, dateStr: String): Int {
        val divider = _settings.value.habitDividers[habitName] ?: 1
        // Minutes-primary habits: minutes (the first-class minutes slot) drive
        // points (divider applies), sessions are the zero-minutes fallback.
        // Inverted-binary habits: 1 point on not-done days, 0 on done days
        if (habitName in _settings.value.invertedBinaryHabits) {
            return com.example.tail.data.invertedBinaryPoints(rawCount)
        }
        if (habitName in _settings.value.widgetTimerMinutesPrimary) {
            val minutes = cachedPhoneDb[minutesKey(habitName)]?.get(dateStr) ?: 0
            // Minutes-primary: which value covers points on 0-minute days is
            // configurable — sessions (the default), the second value, or none.
            return when (
                _settings.value.minutesPrimaryFallbacks[habitName]
                    ?: com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS
            ) {
                com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE ->
                    applyDivider(minutes, divider)
                com.example.tail.data.MINUTES_PRIMARY_FALLBACK_VALUE2 -> {
                    val v2 = cachedPhoneDb[secondaryValueKey(habitName)]?.get(dateStr) ?: 0
                    com.example.tail.data.effectivePointsWithFallback(minutes, divider, v2, true)
                }
                else -> com.example.tail.data.effectivePointsWithFallback(minutes, divider, rawCount, true)
            }
        }
        val useFallback = habitName in _settings.value.secondaryValueFallbackHabits
        if (!useFallback) return applyDivider(rawCount, divider)
        // Fallback source: the legacy generic secondary slot when the habit
        // uses it or has data there (Meditations/Apnea/Resonance sessions,
        // chess.com games, JugCoach seconds), otherwise the first-class
        // minutes slot.
        val fallbackKey = com.example.tail.data.fallbackSlotKey(
            habitName, _settings.value.secondaryValueHabits, cachedPhoneDb
        )
        val secVal = cachedPhoneDb[fallbackKey]?.get(dateStr) ?: 0
        return com.example.tail.data.effectivePointsWithFallback(rawCount, divider, secVal, true)
    }

    /**
     * Points earned by ONE schedule instance of [habitName] with [amount]
     * increment units (divider applied). Null when points are not
     * attributable per instance — no-points habits, inverted-binary habits
     * and minutes-primary habits all derive points from day-level values
     * the schedule timeline cannot split per block.
     */
    fun scheduleInstancePoints(habitName: String, amount: Int): Int? {
        val s = _settings.value
        if (habitName in s.noPointsHabits) return null
        if (habitName in s.invertedBinaryHabits) return null
        if (habitName in s.widgetTimerMinutesPrimary) return null
        val divider = s.habitDividers[habitName] ?: 1
        return applyDivider(amount, divider)
    }

    /**
     * Returns the selected date's minutes for [habitName] from the first-class
     * minutes slot (`minutes:<habit>`).
     */
    fun getMinutesTodayCount(habitName: String): Int {
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)
        return cachedPhoneDb[minutesKey(habitName)]?.get(dateStr) ?: 0
    }

    /**
     * Sets the selected date's minutes for [habitName] in the first-class
     * minutes slot (clamped to ≥ 0), then refreshes the habit list,
     * widgets and listeners.
     *
     * Mirrors [setHabitSecondaryCount]: the minutes slot is updated in the
     * in-memory cache synchronously and the full-file write happens in the
     * background. The previous implementation reloaded and re-parsed the
     * ENTIRE habits DB on every keystroke (plus a second full reload via
     * the HabitIncrementBus collector); concurrent multi-megabyte Gson
     * parses piled up on Dispatchers.IO and exhausted the heap — an
     * OutOfMemoryError crash when editing a media habit's minutes.
     */
    fun setHabitMinutesCount(habitName: String, newCount: Int) {
        val uriString = _settings.value.fileUri
        if (uriString.isNullOrEmpty()) return
        val clamped = newCount.coerceAtLeast(0)
        val minKey = minutesKey(habitName)
        val dateStr = com.example.tail.data.dateString(_selectedDate.value)

        // Step 1: instant in-memory cache update — no DB reload per keystroke.
        val updatedDb = cachedPhoneDb.toMutableMap()
        val entries = updatedDb[minKey]?.toMutableMap() ?: mutableMapOf()
        if (clamped > 0) entries[dateStr] = clamped else entries.remove(dateStr)
        if (entries.isEmpty()) updatedDb.remove(minKey) else updatedDb[minKey] = entries.toSortedMap()
        cachedPhoneDb = updatedDb

        // Step 2: rebuild + disk write in the background. Serialized so a
        // keystroke burst never overlaps full-file writes, and each write
        // persists the LATEST cached state (read inside the lock, not
        // captured), so a slow older write can never clobber a newer one.
        viewModelScope.launch {
            minutesWriteMutex.withLock {
                rebuildHabitList()
                try {
                    habitsRepo.persistDatabase(Uri.parse(uriString), context, cachedPhoneDb)
                    HabitsDataChangedBus.emit()
                    HabitListWidgetProvider.refreshAll(context)
                } catch (e: Exception) {
                    _errorMessage.value = "Failed to set minutes: ${e.message}"
                }
            }
        }
    }

    /** Serializes minutes-slot disk writes (see [setHabitMinutesCount]). */
    private val minutesWriteMutex = Mutex()

    /**
     * Toggles the "fall back to minutes" points fallback for a sessions-primary
     * habit. Writes the shared fallback set; the fallback SOURCE resolves to
     * the minutes slot unless the habit uses the legacy generic secondary value.
     */
    fun toggleMinutesFallbackHabit(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.secondaryValueFallbackHabits
            val updated = if (habitName in current) current - habitName else current + habitName
            settingsRepo.saveSecondaryValueFallbackHabits(updated)
            _settings.value = _settings.value.copy(secondaryValueFallbackHabits = updated)
            rebuildHabitList()
        }
    }

    /** The fallback source a minutes-primary habit uses on 0-minute days. */
    fun getMinutesPrimaryFallback(habitName: String): String =
        _settings.value.minutesPrimaryFallbacks[habitName]
            ?: com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS

    /**
     * Sets which value covers points on 0-minute days for a MINUTES-PRIMARY
     * habit: none, the sessions value (the default), or the second value.
     * Only non-default choices are stored, so absent = sessions.
     */
    fun setMinutesPrimaryFallback(habitName: String, source: String) {
        viewModelScope.launch {
            val current = _settings.value.minutesPrimaryFallbacks
            val updated = if (source == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS) {
                current - habitName
            } else {
                current + (habitName to source)
            }
            if (updated == current) return@launch
            settingsRepo.saveMinutesPrimaryFallbacks(updated)
            _settings.value = _settings.value.copy(minutesPrimaryFallbacks = updated)
            rebuildHabitList()
        }
    }

    /**
     * The EFFECTIVE minutes-enabled state for [habitName]: the explicit
     * toggle OR any timer-widget connection (which forces minutes on),
     * minus max-1 habits (which force minutes off). See
     * [com.example.tail.data.effectiveMinutesEnabled].
     */
    fun isMinutesEnabled(habitName: String): Boolean {
        val s = _settings.value
        return effectiveMinutesEnabled(
            habitName,
            s.minutesEnabledHabits,
            s.pcWidgetHabits,
            s.widgetTriggerHabits,
            s.mediaHabits,
            s.bridgeMovieHabits,
            s.widgetTimerMinutesPrimary,
            s.maxOneHabits
        )
    }

    /**
     * True when minutes is forced ON by a timer-widget connection (PC
     * widget, phone bubble trigger), a media tracker, or the movie bridge —
     * the edit panel shows the minutes toggle as locked-on for these habits.
     */
    fun isMinutesForcedByWidget(habitName: String): Boolean {
        val s = _settings.value
        return habitName in s.pcWidgetHabits ||
            habitName in s.widgetTriggerHabits ||
            habitName in s.mediaHabits ||
            habitName in s.bridgeMovieHabits
    }

    /**
     * Toggles the per-habit minutes value on/off.
     *
     * Refuses to change habits whose minutes state is forced: max-1 habits
     * (never minutes) and timer-widget/media/movie-connected habits (always
     * minutes). Turning minutes OFF also clears the minutes-primary role,
     * its fallback choice and a minutes-sourced points fallback, since none
     * can work without the minutes value. Stored minutes DATA is never
     * deleted — only the feature flags change.
     */
    fun toggleMinutesEnabled(habitName: String) {
        viewModelScope.launch {
            val s = _settings.value
            if (habitName in s.maxOneHabits) return@launch
            if (isMinutesForcedByWidget(habitName)) return@launch
            val enabling = habitName !in s.minutesEnabledHabits
            val minutes = if (enabling) s.minutesEnabledHabits + habitName else s.minutesEnabledHabits - habitName
            var primary = s.widgetTimerMinutesPrimary
            var fallback = s.secondaryValueFallbackHabits
            var mpFallbacks = s.minutesPrimaryFallbacks
            if (!enabling) {
                if (habitName in primary) primary = primary - habitName
                if (habitName in mpFallbacks) mpFallbacks = mpFallbacks - habitName
                // Only drop the fallback flag when its source is the minutes
                // slot; a legacy secondary_value: fallback keeps working.
                val legacyFallbackSource = habitName in s.secondaryValueHabits ||
                    !cachedPhoneDb[secondaryValueKey(habitName)].isNullOrEmpty()
                if (habitName in fallback && !legacyFallbackSource) fallback = fallback - habitName
            }
            settingsRepo.saveMinutesEnabledHabits(minutes)
            if (primary != s.widgetTimerMinutesPrimary) {
                settingsRepo.saveWidgetTimerMinutesPrimary(primary)
            }
            if (fallback != s.secondaryValueFallbackHabits) {
                settingsRepo.saveSecondaryValueFallbackHabits(fallback)
            }
            if (mpFallbacks != s.minutesPrimaryFallbacks) {
                settingsRepo.saveMinutesPrimaryFallbacks(mpFallbacks)
            }
            _settings.value = s.copy(
                minutesEnabledHabits = minutes,
                widgetTimerMinutesPrimary = primary,
                secondaryValueFallbackHabits = fallback,
                minutesPrimaryFallbacks = mpFallbacks
            )
            rebuildHabitList()
        }
    }

    /** True when minutes (not sessions) is the habit's primary value. */
    fun isMinutesPrimaryHabit(habitName: String): Boolean =
        habitName in _settings.value.widgetTimerMinutesPrimary

    /**
     * Resolves the user-facing display label for a habit's value key,
     * honouring custom labels ([AppSettings.valueDisplayLabels]).
     */
    fun valueDisplayLabel(habitName: String, valueKey: String): String =
        com.example.tail.data.displayLabelForValue(
            habitName, valueKey, _settings.value.valueDisplayLabels
        )

    /** The user's CUSTOM label for a value key, or null when none is set. */
    fun customValueLabel(habitName: String, valueKey: String): String? =
        _settings.value.valueDisplayLabels[habitName]?.get(valueKey)?.takeIf { it.isNotBlank() }

    /** True when the habit has the "1 max" daily cap enabled. */
    fun isMaxOneHabit(habitName: String): Boolean =
        habitName in _settings.value.maxOneHabits

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
     * Adds an app-link entry at grid position [atIndex] within the active screen.
     * Unlike [addHabit], this does NOT write to the habits database — app links
     * are pure launchers, not incrementable habits.
     * The entry is stored in the screen's habitNames with the [APP_LINK_PREFIX]
     * and its display label is saved to [AppSettings.appLinks].
     */
    fun addAppLink(packageName: String, label: String, atIndex: Int) {
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
    fun deleteAppLink(index: Int) {
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
    fun addHabitAppAssociation(habitName: String, packageName: String, insertAt: Int = -1) {
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
    fun removeHabitAppAssociation(habitName: String, packageName: String) {
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
    fun moveHabitAppAssociation(habitName: String, fromIndex: Int, toIndex: Int) {
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
    fun clearHabitAppAssociations(habitName: String) {
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
    fun toggleWidgetTrigger(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.widgetTriggerHabits
            val newHabits: Set<String>
            val newApps: Map<String, String>

            if (habitName in current) {
                // Disabling — remove from both sets
                newHabits = current - habitName
                newApps = _settings.value.widgetTriggerApps - habitName
            } else {
                // Enabling — add to habits set
                newHabits = current + habitName
                newApps = _settings.value.widgetTriggerApps
            }

            settingsRepo.saveWidgetTriggerHabits(newHabits)
            settingsRepo.saveWidgetTriggerApps(newApps)
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
                minutesEnabledHabits = minutes
            )

            updateWidgetTriggerService()
        }
    }

    /**
     * Sets the trigger app [packageName] for [habitName].
     * The habit should already be in [AppSettings.widgetTriggerHabits].
     */
    fun setWidgetTriggerApp(habitName: String, packageName: String) {
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
    fun setWidgetTimerPrimaryValue(habitName: String, minutesPrimary: Boolean) {
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
    fun migrateValue1ToMinutesPrimary(habitName: String) {
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
    fun toggleMediaHabit(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.mediaHabits
            val newHabits: Set<String>
            val newApps: Map<String, String>
            if (habitName in current) {
                newHabits = current - habitName
                newApps = _settings.value.mediaApps - habitName
            } else {
                newHabits = current + habitName
                newApps = _settings.value.mediaApps
            }
            settingsRepo.saveMediaHabits(newHabits)
            settingsRepo.saveMediaApps(newApps)
            // Media habits track listening minutes in the `minutes:` slot —
            // enabling the type turns minutes ON.
            var minutes = _settings.value.minutesEnabledHabits
            if (habitName in newHabits && habitName !in current &&
                habitName !in minutes && habitName !in _settings.value.maxOneHabits
            ) {
                minutes = minutes + habitName
                settingsRepo.saveMinutesEnabledHabits(minutes)
            }
            _settings.value = _settings.value.copy(
                mediaHabits = newHabits,
                mediaApps = newApps,
                minutesEnabledHabits = minutes
            )
            updateWidgetTriggerService()
        }
    }

    /**
     * Sets the media app [packageName] for [habitName] (the app whose media
     * session is watched for playback — a podcast app, Spotify, any audio
     * app). The habit should already be in
     * [com.example.tail.data.AppSettings.mediaHabits].
     *
     * First-time setup mirrors the widget-timer feature: the habit gets a
     * "minutes" secondary value (where both auto-detected listening AND the
     * bubble timer write), the points fallback, and minutes as the PRIMARY
     * value — the raw count (episodes/tracks finished, tapped manually)
     * becomes the fallback used only on days with zero minutes. The bubble
     * widget is also auto-enabled over the media app so the manual timer
     * fallback is available exactly like in other apps.
     */
    fun setMediaApp(habitName: String, packageName: String) {
        viewModelScope.launch {
            val settings = _settings.value
            val apps = settings.mediaApps.toMutableMap()
            apps[habitName] = packageName
            settingsRepo.saveMediaApps(apps)
            _settings.value = _settings.value.copy(mediaApps = apps)

            // First-time setup for the minutes slot + primary/fallback roles.
            if (packageName.isNotBlank() && settings.mediaApps[habitName].isNullOrBlank()) {
                val secVal = settings.secondaryValueHabits + habitName
                val fallback = settings.secondaryValueFallbackHabits + habitName
                val minutesPrimary = settings.widgetTimerMinutesPrimary + habitName
                val labels = settings.valueDisplayLabels.toMutableMap()
                labels[habitName] = mapOf(
                    com.example.tail.data.GRAPH_METRIC_VALUE1 to "Plays",
                    com.example.tail.data.GRAPH_METRIC_VALUE2 to "Minutes"
                )
                settingsRepo.saveSecondaryValueHabits(secVal)
                settingsRepo.saveSecondaryValueFallbackHabits(fallback)
                settingsRepo.saveWidgetTimerMinutesPrimary(minutesPrimary)
                settingsRepo.saveValueDisplayLabels(labels)
                _settings.value = _settings.value.copy(
                    secondaryValueHabits = secVal,
                    secondaryValueFallbackHabits = fallback,
                    widgetTimerMinutesPrimary = minutesPrimary,
                    valueDisplayLabels = labels
                )
            }

            // Auto-enable the bubble over the media app so the manual
            // widget timer fallback works out of the box (same as other
            // apps). An existing, different trigger app is respected.
            if (packageName.isNotBlank() &&
                _settings.value.widgetTriggerApps[habitName].isNullOrBlank()
            ) {
                val trigHabits = _settings.value.widgetTriggerHabits + habitName
                val trigApps = _settings.value.widgetTriggerApps.toMutableMap()
                trigApps[habitName] = packageName
                settingsRepo.saveWidgetTriggerHabits(trigHabits)
                settingsRepo.saveWidgetTriggerApps(trigApps)
                _settings.value = _settings.value.copy(
                    widgetTriggerHabits = trigHabits,
                    widgetTriggerApps = trigApps
                )
            }

            rebuildHabitList()
            updateWidgetTriggerService()
        }
    }

    /**
     * Returns whether the user has granted notification-listener access
     * (enabled MusicNotificationListenerService), required for automatic
     * media playback detection.
     */
    fun hasNotificationListenerAccess(): Boolean =
        com.example.tail.data.SpotifyDetector.isNotificationListenerEnabled(context)

    /**
     * Opens the system notification-access settings screen so the user can
     * grant access for automatic media playback detection.
     */
    fun openNotificationListenerSettings() {
        com.example.tail.data.SpotifyDetector.openNotificationListenerSettings(context)
    }

    // ── Media per-show breakdown (edit screen podcast removal) ─────────────

    /** One show/podcast (or artist-less title) heard today on a media habit. */
    data class MediaShowMinutes(
        val show: String,
        /** Sum of the logged "(NN min)" episode/track durations for today. */
        val minutes: Int,
        /** How many plays were logged for this show today. */
        val plays: Int
    )

    private val _mediaTodayShows = MutableStateFlow<List<MediaShowMinutes>>(emptyList())

    /** Today's per-show listening breakdown for the currently edited media habit. */
    val mediaTodayShows: StateFlow<List<MediaShowMinutes>> = _mediaTodayShows.asStateFlow()

    /**
     * Parses one media text-log entry into (show, minutes). The show is the
     * artist/show segment; entries without one (some music apps) fall back
     * to the title itself. Returns null for entries that don't match the
     * tracker's format (e.g. manual text notes the user typed).
     */
    private fun parseMediaShowEntry(text: String): Pair<String, Int>? {
        val m = MEDIA_LOG_ENTRY_REGEX.matchEntire(text.trim()) ?: return null
        val artist = m.groupValues[2].takeIf { it.isNotBlank() }
        val show = artist ?: m.groupValues[1].takeIf { it.isNotBlank() } ?: return null
        val minutes = m.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        return show to minutes
    }

    /**
     * Loads today's per-show listening breakdown for a media habit from its
     * text-entry log (the play-by-play entries MediaPlaybackTracker writes).
     * Groups today's plays by show and sums the logged durations; sorted by
     * minutes descending. Habits without a text log get an empty list.
     */
    fun loadMediaTodayShows(habitName: String) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            _mediaTodayShows.value = emptyList()
            return
        }
        val datePrefix = dateString(LocalDate.now())
        viewModelScope.launch {
            val shows = withContext(Dispatchers.IO) {
                try {
                    val log = textInputRepo.loadTextLog(Uri.parse(uriString), context)
                    log.entries
                        .filter { (ts, _) -> ts.startsWith(datePrefix) }
                        .mapNotNull { (_, text) -> parseMediaShowEntry(text) }
                        .groupBy { it.first }
                        .map { (show, plays) ->
                            MediaShowMinutes(show, plays.sumOf { it.second }, plays.size)
                        }
                        .sortedByDescending { it.minutes }
                } catch (e: Exception) {
                    Log.w(TAG, "Media show breakdown failed for '$habitName': ${e.message}")
                    emptyList()
                }
            }
            _mediaTodayShows.value = shows
        }
    }

    /**
     * Removes [show] from a media habit's TODAY listening: deletes today's
     * log entries for that show and subtracts their logged minutes from the
     * habit's minutes secondary-value slot for today (the same slot the
     * tracker writes to, so the day total drops by exactly what that show
     * contributed). Then refreshes the breakdown, widgets and listeners.
     */
    fun removeMediaShowFromToday(habitName: String, show: String) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) return
        val dbUriString = _settings.value.fileUri
        val datePrefix = dateString(LocalDate.now())
        viewModelScope.launch {
            try {
                val textUri = Uri.parse(uriString)
                val log = withContext(Dispatchers.IO) {
                    textInputRepo.loadTextLog(textUri, context)
                }
                val doomed = log.entries.filter { (ts, text) ->
                    ts.startsWith(datePrefix) && parseMediaShowEntry(text)?.first == show
                }
                if (doomed.isNotEmpty()) {
                    textInputRepo.deleteTextEntries(
                        textUri, context, doomed.map { it.key }, habitName = habitName
                    )
                }
                val minutes = doomed.sumOf { parseMediaShowEntry(it.value)?.second ?: 0 }
                if (minutes > 0 && dbUriString.isNotEmpty()) {
                    habitsRepo.incrementHabitWithMinutes(
                        Uri.parse(dbUriString), context, habitName, -minutes, 0
                    )
                    HabitIncrementBus.emit(habitName)
                    HabitListWidgetProvider.refreshAll(context)
                }
                Log.i(TAG, "Removed media show '$show' for '$habitName': ${doomed.size} plays, -$minutes min")
                loadMediaTodayShows(habitName)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove media show '$show' for '$habitName': ${e.message}")
            }
        }
    }

    /**
     * Returns whether the user has granted Usage Access permission,
     * required for the widget trigger feature to work.
     */
    fun hasUsageAccess(): Boolean =
        com.example.tail.widget.WidgetTriggerService.hasUsageAccess(context)

    /**
     * Opens the system Usage Access settings screen so the user can grant
     * the permission.
     */
    fun openUsageAccessSettings() {
        val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }

    /**
     * Starts or stops [WidgetTriggerService] based on whether anything needs
     * monitoring: habit trigger apps, media apps (automatic listening-time
     * tracking) OR the Chess Readiness app. Called after every widget-trigger
     * / media / chess-readiness setting change.
     */
    private fun updateWidgetTriggerService() {
        val s = _settings.value
        val validCount = s.widgetTriggerApps.values.count { it.isNotBlank() } +
            s.mediaApps.entries.count { it.value.isNotBlank() && it.key in s.mediaHabits } +
            if (s.chessReadinessEnabled && s.chessReadinessApp.isNotBlank()) 1 else 0
        com.example.tail.widget.WidgetTriggerService.updateServiceState(context, validCount)
    }

    // ── Chess Readiness methods ───────────────────────────────────────────

    /**
     * Enables or disables the Chess Readiness feature (global toggle in the
     * widget section of Settings). Disabling keeps the associated app in
     * settings but stops the bubble from watching it.
     */
    fun setChessReadinessEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.saveChessReadinessEnabled(enabled)
            _settings.value = _settings.value.copy(chessReadinessEnabled = enabled)
            updateWidgetTriggerService()
        }
    }

    /**
     * Enables or disables the in-app stats overlay (StatsOverlayService) — the
     * always-on-top bar showing today / avg7 / avg30, fed by the same
     * computation as the loading spinner. Toggling starts/stops the service.
     */
    fun setStatsOverlayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepo.saveStatsOverlayEnabled(enabled)
            _settings.value = _settings.value.copy(statsOverlayEnabled = enabled)
            if (enabled) {
                com.example.tail.widget.StatsOverlayService.start(context)
            } else {
                com.example.tail.widget.StatsOverlayService.stop(context)
            }
        }
    }

    /**
     * Sets the app associated with Chess Readiness. The floating bubble will
     * appear over this app and its popup menu gains a "Chess Readiness"
     * option. Only meaningful while the feature is enabled.
     */
    fun setChessReadinessApp(packageName: String) {
        viewModelScope.launch {
            settingsRepo.saveChessReadinessApp(packageName)
            _settings.value = _settings.value.copy(chessReadinessApp = packageName)
            // Mirror into the synchronous prefs store for the Chess Guard
            // accessibility service (its callback path cannot read DataStore).
            com.example.tail.widget.ChessReadinessStore.saveChessPackage(context, packageName)
            updateWidgetTriggerService()
        }
    }

    /**
     * Switches the chess readiness engine between "v1" (the original
     * diagnostic) and "v2" (the neurobiological gate). Persisted to DataStore
     * and mirrored into the synchronous v2 prefs store so the floating bubble
     * service can branch without reading DataStore on the window-manager path.
     */
    fun setChessReadinessVersion(version: String) {
        viewModelScope.launch {
            val normalized =
                if (version == com.example.tail.widget.ChessReadinessV2Store.VERSION_V2) {
                    com.example.tail.widget.ChessReadinessV2Store.VERSION_V2
                } else {
                    com.example.tail.widget.ChessReadinessV2Store.VERSION_V1
                }
            settingsRepo.saveChessReadinessVersion(normalized)
            _settings.value = _settings.value.copy(chessReadinessVersion = normalized)
            com.example.tail.widget.ChessReadinessV2Store.saveReadinessVersion(context, normalized)
        }
    }

    /**
     * Switches the POST-GAME (Phase 2) audit engine between "v1" (the
     * adaptive ΔE/strain evidence model) and "v2" (the research-report
     * system: fatigue ceiling, loss-streak stop rules, tilt vector, ACWR,
     * hysteresis). Persisted to DataStore and mirrored into the synchronous
     * v2 prefs store so the share-sheet reconciler can branch without
     * reading DataStore. Fully independent of the pre-game readiness
     * version — the two toggles combine freely.
     */
    fun setChessPhase2Version(version: String) {
        viewModelScope.launch {
            val normalized =
                if (version == com.example.tail.widget.ChessPhase2V2Store.VERSION_V2) {
                    com.example.tail.widget.ChessPhase2V2Store.VERSION_V2
                } else {
                    com.example.tail.widget.ChessPhase2V2Store.VERSION_V1
                }
            settingsRepo.saveChessPhase2Version(normalized)
            _settings.value = _settings.value.copy(chessPhase2Version = normalized)
            com.example.tail.widget.ChessPhase2V2Store.savePhase2Version(context, normalized)
        }
    }

    /**
     * Enables or disables Chess Guard — the hard enforcement layer that
     * keeps the chess app closed while the readiness policy says blocked.
     * The switch-ON timestamp is recorded so games that ended before
     * enforcement existed are never penalized retroactively.
     */
    fun setChessEnforcementEnabled(enabled: Boolean) {
        viewModelScope.launch {
            com.example.tail.widget.ChessReadinessStore.setEnforcementEnabled(context, enabled)
        }
    }

    /**
     * Deletes the habit at [index] from the active screen (or flat order).
     * JSON data is preserved by default — the delete dialog offers
     * [deleteHabitData] as an explicit opt-in purge. Clears the selection
     * after deletion.
     */
    fun deleteHabit(index: Int) {
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
    private fun removeConditionalReferences(deletedName: String) {
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
    fun getDeleteDataDayCount(habitName: String): Int {
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
    fun deleteHabitData(habitName: String) {
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
    fun renameHabit(oldName: String, newName: String) {
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
    fun getInvertPreview(habitName: String): InvertPreview? {
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
    fun invertHabit(habitName: String) {
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
            // Choosing an installed-app icon implies the user wants that app
            // tied to the habit: auto-create the app association so long-press
            // launches it. Idempotent (no-op when already associated); the
            // user can remove it manually in edit mode's app settings.
            appPackageNameOf(iconName)?.let { pkg ->
                addHabitAppAssociation(habitName, pkg)
            }
            // Sync icon change to relay file so PC widget picks it up
            val relayUri = _settings.value.screensRelayFileUri
            if (relayUri.isNotEmpty()) {
                writeScreensRelayFile(_habitScreens.value, _activeScreenIndex.value, relayUri)
            }
            // The PC floating-widget config embeds icon overrides — refresh it
            // when an icon changes for a habit shown on the PC widget.
            if (habitName in _settings.value.pcWidgetHabits) {
                pushPcWidgetConfig()
            }
        }
    }

    /**
     * Sets or clears the note for [habitName].
     * [note] is the note text, or empty string to clear the note.
     */
    fun setHabitNote(habitName: String, note: String) {
        viewModelScope.launch {
            val current = _settings.value.habitNotes.toMutableMap()
            if (note.isEmpty()) {
                current.remove(habitName)
            } else {
                current[habitName] = note
            }
            settingsRepo.saveHabitNotes(current)
            _settings.value = _settings.value.copy(habitNotes = current)
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
     *
     * When [habitName] is given, generation runs as a background job tied to
     * that habit: the caller does NOT need to wait. The habit's tile shows a
     * spinner while generation is in flight, and once the icon is ready it is
     * automatically applied to the habit (same path as [setHabitIcon]) and a
     * toast announces the result — even if the icon picker was closed long ago.
     */
    fun generateAiIcon(prompt: String, habitName: String? = null) {
        val s = _settings.value
        if (!s.aiIconsEnabled || s.aiIconsApiKey.isEmpty() || s.aiIconsBaseUrl.isEmpty()) {
            _aiIconError.value = "AI icons not configured. Check Settings."
            return
        }
        _aiIconGenerating.value = true
        _aiIconError.value = null
        if (habitName != null) {
            _aiIconPendingHabits.value = _aiIconPendingHabits.value + habitName
            _aiIconMessages.tryEmit("Generating AI icon for \"$habitName\"…")
        }
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
                val icon = withContext(Dispatchers.IO) {
                    aiIconRepo.saveIcon(bitmap, prompt)
                }
                refreshAiIcons()
                if (habitName != null) {
                    // Auto-apply the freshly generated icon to the habit
                    setHabitIcon(habitName, icon.id)
                    _aiIconMessages.tryEmit("AI icon applied to \"$habitName\"")
                }
            } catch (e: Exception) {
                Log.e(TAG, "AI icon generation failed", e)
                _aiIconError.value = e.message ?: "Unknown error"
                if (habitName != null) {
                    _aiIconMessages.tryEmit(
                        "AI icon for \"$habitName\" failed: ${_aiIconError.value}"
                    )
                }
            } finally {
                if (habitName != null) {
                    _aiIconPendingHabits.value = _aiIconPendingHabits.value - habitName
                }
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
    private suspend fun pushPcWidgetConfig() {
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
                // Also remove from sharable set — sharable requires text input to be on
                val sharable = _settings.value.sharableTextHabits.toMutableSet()
                sharable.remove(habitName)
                settingsRepo.saveSharableTextHabits(sharable)
                _settings.value = _settings.value.copy(sharableTextHabits = sharable)
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
     * Toggles the "sharable" sub-feature on/off for [habitName].
     * When on, the habit appears in ShareTextActivity's picker so text shared
     * from anywhere on the phone can be saved into it (timestamped + counted).
     * Only has effect when the habit already has text input enabled.
     */
    fun toggleSharableText(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.sharableTextHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveSharableTextHabits(current)
            _settings.value = _settings.value.copy(sharableTextHabits = current)
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
     * Creates a new empty text-log file named after [habitName] inside the SAF
     * directory [treeUri], then associates it as the habit's text-log file.
     *
     * The caller must already hold (and persist) read+write permission on the
     * tree URI; the created document lives under that tree so the grant covers it.
     * The file is initialised with an empty JSON object ("{}"). If the directory
     * already contains a file with the same name the provider auto-renames the
     * new file (e.g. "habit (1).json").
     */
    fun createTextInputFileInDir(habitName: String, treeUri: Uri) {
        viewModelScope.launch {
            val fileUri = withContext(Dispatchers.IO) {
                try {
                    val cr = context.contentResolver
                    val dirUri = DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        DocumentsContract.getTreeDocumentId(treeUri)
                    )
                    val displayName = sanitizeFileDisplayName(habitName) + ".json"
                    val created = DocumentsContract.createDocument(
                        cr, dirUri, "application/json", displayName
                    ) ?: return@withContext null
                    // Initialise with an empty JSON object so the file is a valid log
                    cr.openOutputStream(created, "wt")?.use { stream ->
                        stream.bufferedWriter().use { it.write("{}") }
                    }
                    created
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to create text log file for $habitName: ${e.message}")
                    null
                }
            }
            if (fileUri != null) setTextInputFileUri(habitName, fileUri)
        }
    }

    /** Replaces characters that are problematic in file names with underscores. */
    private fun sanitizeFileDisplayName(name: String): String {
        val sanitized = name.trim().replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        return sanitized.ifEmpty { "habit_log" }
    }

    /**
     * Saves a text entry for [habitName] to its associated log file,
     * then also increments the habit count by 1 (so the habit is marked done for today).
     *
     * @param habitName The name of the habit
     * @param text The text entry to save
     * @param date The date to use for the timestamp. If null, uses current date.
     * @param time The time-of-day for the timestamp. If null, uses current time (when [date]
     *             is also null) or noon (when [date] is provided but [time] is null).
     */
    fun saveTextEntry(
        habitName: String,
        text: String,
        date: LocalDate? = null,
        time: java.time.LocalTime? = null
    ) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            _errorMessage.value = "No text log file set for '$habitName'. Select one in edit mode."
            return
        }
        viewModelScope.launch {
            try {
                // Save the text entry for the current date
                textInputRepo.appendTextEntry(Uri.parse(uriString), context, text, date, time, habitName = habitName)

                // If this is a roll forward habit, also roll forward the text
                if (habitName in _settings.value.rollForwardHabits) {
                    val entryDate = date ?: java.time.LocalDate.now()
                    val effectiveTime = time ?: java.time.LocalTime.NOON
                    val timestamp = java.time.LocalDateTime.of(entryDate, effectiveTime)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                    // Find the next manual date (same logic as incrementHabit)
                    val nextManualDate = _settings.value.rollForwardManualDates[habitName]?.mapNotNull { dateStr ->
                        com.example.tail.data.parseDate(dateStr)
                    }?.sorted()?.firstOrNull { it > entryDate }

                    val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

                    // Roll forward the text to all dates from entryDate+1 to endDate
                    if (entryDate < endDate) {
                        textInputRepo.rollForwardTextEntry(
                            Uri.parse(uriString),
                            context,
                            timestamp,
                            entryDate.plusDays(1),
                            endDate,
                            habitName = habitName
                        )
                    }
                }

                // Also increment the habit count so it registers as done for
                // today. Movie-bridge habits: suppress the "now" stamp — the
                // text entry's watch-start time is THE timestamp (sync below).
                incrementHabit(habitName, 1, recordTimestamp = !isMovieBridgeHabit(habitName))

                // Movie-bridge habits: reconcile the timestamp store to the
                // text log so the entry's watch time is the single timestamp,
                // and recompute the minutes slot from "(N min)" annotations.
                if (isMovieBridgeHabit(habitName)) {
                    syncMovieTimestamps(habitName)
                    syncMovieMinutesSlot(habitName)
                }

                // Trigger async IMDb rating fetch for movie-bridge habits
                triggerImdbFetchForEntry(habitName, text)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save text entry: ${e.message}"
            }
        }
    }

    /**
     * Saves multiple text entries for [habitName] to its associated log file in a single
     * atomic write. Each entry gets a unique timestamp (offset by 1 second). Then
     * increments the habit count by 1 — selecting multiple options is a single action
     * and should not inflate the habit count.
     *
     * @param habitName The name of the habit
     * @param texts The list of text entries to save
     * @param date The date to use for timestamps. If null, uses current date.
     * @param time The base time-of-day. If null, uses current time (when [date] is also null)
     *             or noon (when [date] is provided but [time] is null).
     */
    fun saveTextEntries(
        habitName: String,
        texts: List<String>,
        date: LocalDate? = null,
        time: java.time.LocalTime? = null
    ) {
        if (texts.isEmpty()) return
        // Single entry — delegate to the simpler path
        if (texts.size == 1) {
            saveTextEntry(habitName, texts.first(), date, time)
            return
        }
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            _errorMessage.value = "No text log file set for '$habitName'. Select one in edit mode."
            return
        }
        viewModelScope.launch {
            try {
                textInputRepo.appendMultipleTextEntries(
                    Uri.parse(uriString), context, texts, date, time, habitName = habitName
                )

                // If this is a roll forward habit, also roll forward the text
                if (habitName in _settings.value.rollForwardHabits) {
                    val entryDate = date ?: java.time.LocalDate.now()
                    val effectiveTime = time ?: java.time.LocalTime.NOON
                    val timestamp = java.time.LocalDateTime.of(entryDate, effectiveTime)
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

                    val nextManualDate = _settings.value.rollForwardManualDates[habitName]?.mapNotNull { dateStr ->
                        com.example.tail.data.parseDate(dateStr)
                    }?.sorted()?.firstOrNull { it > entryDate }

                    val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

                    if (entryDate < endDate) {
                        textInputRepo.rollForwardTextEntry(
                            Uri.parse(uriString),
                            context,
                            timestamp,
                            entryDate.plusDays(1),
                            endDate,
                            habitName = habitName
                        )
                    }
                }

                // Increment the habit count by 1 — selecting multiple options
                // is a single action and must not count as multiple increments.
                // Movie-bridge habits: suppress the "now" stamp — the text
                // entries' watch-start times are THE timestamps (sync below).
                incrementHabit(habitName, 1, recordTimestamp = !isMovieBridgeHabit(habitName))

                // Movie-bridge habits: reconcile the timestamp store to the
                // text log so each entry's watch time is the single timestamp,
                // and recompute the minutes slot from "(N min)" annotations.
                if (isMovieBridgeHabit(habitName)) {
                    syncMovieTimestamps(habitName)
                    syncMovieMinutesSlot(habitName)
                }

                // Trigger async IMDb rating fetch for movie-bridge habits
                texts.forEach { triggerImdbFetchForEntry(habitName, it) }
            } catch (e: Exception) {
                _errorMessage.value = "Failed to save text entries: ${e.message}"
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
     * Sets the graph display unit for weights habits ("kg" or "lb"). Stored
     * gram values are converted at graph-read time, so toggling re-renders
     * every weights series in the new unit.
     */
    fun setGraphWeightUnit(unit: String) {
        viewModelScope.launch {
            settingsRepo.saveGraphWeightUnit(unit)
            _settings.value = _settings.value.copy(graphWeightUnit = unit)
        }
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
        // Don't deselect the time period - it should remain selected during pan/swipe
        // Time period is only cleared when user taps a period button (in setGraphTimePeriod)
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
            _scheduleMode.value = false
            // Re-anchor the graph window on the app's currently selected day:
            // drop any pinch-zoom/pan range left over from a previous session.
            _graphZoomStartDate.value = null
            _graphZoomEndDate.value = null
            // Carry the edit-mode selection into graph mode
            val carriedName = _habits.value.getOrNull(_selectedEditIndex.value)
                ?.name?.takeIf { it.isNotEmpty() }
            _selectedEditIndex.value = -1
            _movePendingSourceIndex.value = -1
            _graphSelectedHabits.value = carriedName?.let { setOf(it) } ?: emptySet()
        } else {
            _graphSelectedHabits.value = emptySet()
        }
    }

    fun toggleGraphHabitSelection(habitName: String) {
        val current = _graphSelectedHabits.value.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        _graphSelectedHabits.value = current
    }

    // ── Schedule mode ──────────────────────────────────────────────────────────

    /**
     * Whether daily-schedule (retrospective timeline) mode is active.
     * When on, the habit grid is replaced by an hour-by-hour timeline of
     * everything that was timestamped on the selected day.
     */
    private val _scheduleMode = MutableStateFlow(false)
    val scheduleMode: StateFlow<Boolean> = _scheduleMode.asStateFlow()

    fun toggleScheduleMode() {
        val turningOn = !_scheduleMode.value
        _scheduleMode.value = turningOn
        if (turningOn) {
            // Deactivate other modes
            _editMode.value = false
            _graphMode.value = false
            _selectedEditIndex.value = -1
            _movePendingSourceIndex.value = -1
            _graphSelectedHabits.value = emptySet()
        }
    }

    /**
     * Sets the graph value mode for [habitName].
     * 0 = points, 1 = Value1 (raw value), 2 = Value2 (secondary value).
     * The setting is persisted per-habit.
     */
    fun setGraphValueMode(habitName: String, mode: Int) {
        viewModelScope.launch {
            val current = _settings.value.graphValueModeHabits.toMutableMap()
            if (mode == 0) {
                current.remove(habitName)
            } else {
                current[habitName] = mode
            }
            settingsRepo.saveGraphValueModeHabits(current)
            _settings.value = _settings.value.copy(graphValueModeHabits = current)
        }
    }

    /**
     * Returns the graph value mode for [habitName].
     * 0 = points (default), 1 = Value1 (raw value), 2 = Value2 (secondary value).
     */
    fun getGraphValueMode(habitName: String): Int {
        return _settings.value.graphValueModeHabits[habitName] ?: 0
    }

    /** Returns true if [habitName] has the secondary value feature enabled. */
    fun hasSecondaryValue(habitName: String): Boolean {
        return habitName in _settings.value.secondaryValueHabits
    }

    fun clearGraphSelection() {
        _graphSelectedHabits.value = emptySet()
    }

    // ── Multi-select graph metrics ────────────────────────────────────────

    /** Returns true if [habitName] has the "Meal" type enabled. */
    fun isMealHabit(habitName: String): Boolean {
        return habitName in _settings.value.mealHabits
    }

    /** Returns true if [habitName] has the "Weights" type enabled. */
    fun isWeightsHabit(habitName: String): Boolean {
        return habitName in _settings.value.weightsHabits
    }

    /**
     * Returns the list of selectable graph metrics for [habitName], depending
     * on its type. All habits get Points + Value1. Secondary-value habits also
     * get Value2. Meal habits additionally get Calories, Protein, Carbs, Fat.
     */
    fun getAvailableMetrics(habitName: String): List<GraphMetricOption> {
        val labels = _settings.value.valueDisplayLabels
        val metrics = mutableListOf(
            GraphMetricOption(GRAPH_METRIC_POINTS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_POINTS, labels))
        )
        // GitHub habits use labeled metric buttons instead of generic "Value 1";
        // weights habits get their own labeled buttons below instead
        if (!isGithubHabit(habitName) && !isWeightsHabit(habitName)) {
            val v1 = GraphMetricOption(GRAPH_METRIC_VALUE1, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_VALUE1, labels))
            val v2 = GraphMetricOption(GRAPH_METRIC_VALUE2, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_VALUE2, labels))
            val hasV2 = hasSecondaryValue(habitName) ||
                habitName in _settings.value.chessComHabitLinks
            if (hasV2 && habitName in _settings.value.widgetTimerMinutesPrimary) {
                // Minutes-primary habit: the primary value (Value2 slot) comes
                // right after Points, then the secondary (Value1 slot).
                metrics.add(v2)
                metrics.add(v1)
            } else {
                metrics.add(v1)
                if (hasV2) metrics.add(v2)
            }
        }
        // First-class minutes slot — shown only when the habit's minutes
        // value is enabled (explicit toggle, or forced on by a timer-widget
        // connection / minutes-primary role; max-1 forces it off).
        val hasMinutes = isMinutesEnabled(habitName)
        if (hasMinutes) {
            metrics.add(GraphMetricOption(com.example.tail.data.GRAPH_METRIC_MINUTES, com.example.tail.data.displayLabelForValue(habitName, com.example.tail.data.GRAPH_METRIC_MINUTES, labels)))
        }
        // Value3 (second-slot secondary value, `secondary_value2:`) — written by
        // the chess.com integration (daily win percentage)
        if (habitName in _settings.value.chessComHabitLinks) {
            metrics.add(GraphMetricOption(GRAPH_METRIC_VALUE3, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_VALUE3, labels)))
        }
        // IMDb average rating metric — available for movie-bridge habits with an OMDb API key
        if (hasImdbRatings(habitName)) {
            metrics.add(GraphMetricOption(GRAPH_METRIC_IMDB, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_IMDB, labels)))
        }
        // Runtime minutes metric — available for all movie-bridge habits; values
        // are derived from "(N min)" annotations in the text entries
        if (isMovieBridgeHabit(habitName)) {
            metrics.add(GraphMetricOption(GRAPH_METRIC_RUNTIME, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_RUNTIME, labels)))
        }
        if (isMealHabit(habitName)) {
            metrics.add(GraphMetricOption(GRAPH_METRIC_CALORIES, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_CALORIES, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_PROTEIN, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_PROTEIN, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_CARBS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_CARBS, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_FAT, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_FAT, labels)))
        }
        // GitHub metrics — available for habits linked to a GitHub repository
        if (isGithubHabit(habitName)) {
            metrics.add(GraphMetricOption(GRAPH_METRIC_GITHUB_LINES, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_GITHUB_LINES, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_GITHUB_COMMITS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_GITHUB_COMMITS, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_GITHUB_ADDITIONS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_GITHUB_ADDITIONS, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_GITHUB_DELETIONS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_GITHUB_DELETIONS, labels)))
        }
        // JugCoach juggling metrics — available once JugCoach has written
        // session data (key-presence detection, see isJugcoachHabit)
        if (isJugcoachHabit(habitName)) {
            metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_TIME, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_TIME, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_CATCHES, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_CATCHES, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_TIME_CATCH, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_TIME_CATCH, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_TIME_DROP, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_TIME_DROP, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_CATCHES_CATCH, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_CATCHES_CATCH, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_JUGCOACH_CATCHES_DROP, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_JUGCOACH_CATCHES_DROP, labels)))
        }
        // Weights habit metrics — machine/free weight (stored grams, shown in
        // the graph's kg/lb display unit) and machine/free reps. These reuse
        // the generic secondary-value slots internally, which is why the
        // generic Value 1 / Value 2 buttons are hidden for weights habits.
        if (isWeightsHabit(habitName)) {
            metrics.add(GraphMetricOption(GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_WEIGHTS_FREE_WEIGHT, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_WEIGHTS_FREE_WEIGHT, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_WEIGHTS_MACHINE_REPS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_WEIGHTS_MACHINE_REPS, labels)))
            metrics.add(GraphMetricOption(GRAPH_METRIC_WEIGHTS_FREE_REPS, com.example.tail.data.displayLabelForValue(habitName, GRAPH_METRIC_WEIGHTS_FREE_REPS, labels)))
        }
        return metrics
    }

    /**
     * Returns the set of currently-selected graph metrics for [habitName].
     *
     * Migrates from the legacy single-select [AppSettings.graphValueModeHabits]
     * on first access: old mode 0 → {points}, mode 1 → {value1}, mode 2 → {value2}.
     * Defaults to {points} when nothing is stored.
     */
    fun getSelectedMetrics(habitName: String): Set<String> {
        val stored = _settings.value.graphMetricSelection[habitName]
        if (stored != null) {
            // For GitHub habits, migrate legacy "value1" to the corresponding GitHub metric
            if (isGithubHabit(habitName) && GRAPH_METRIC_VALUE1 in stored) {
                val migrated = stored.toMutableSet()
                migrated.remove(GRAPH_METRIC_VALUE1)
                migrated.add(primaryGithubMetricKey(habitName))
                return migrated
            }
            return stored
        }

        // Weights habits default to BOTH weight curves (machine + free) so the
        // two categories show together; reps are opt-in via the metric buttons.
        if (isWeightsHabit(habitName)) {
            return setOf(GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT, GRAPH_METRIC_WEIGHTS_FREE_WEIGHT)
        }

        // Legacy migration: convert old single-select mode to a set
        val oldMode = _settings.value.graphValueModeHabits[habitName] ?: 0
        return when (oldMode) {
            1 -> if (isGithubHabit(habitName)) setOf(primaryGithubMetricKey(habitName)) else setOf(GRAPH_METRIC_VALUE1)
            2 -> setOf(GRAPH_METRIC_VALUE2)
            else -> if (isGithubHabit(habitName)) setOf(primaryGithubMetricKey(habitName)) else setOf(GRAPH_METRIC_POINTS)
        }
    }

    /**
     * Returns the graph metric key corresponding to the GitHub habit's configured
     * primary metric (the one stored as value1 in the habits DB).
     */
    private fun primaryGithubMetricKey(habitName: String): String {
        val metric = GitHubMetric.fromKey(_settings.value.githubMetrics[habitName])
        return when (metric) {
            GitHubMetric.LINES_CHANGED -> GRAPH_METRIC_GITHUB_LINES
            GitHubMetric.COMMITS -> GRAPH_METRIC_GITHUB_COMMITS
            GitHubMetric.ADDITIONS -> GRAPH_METRIC_GITHUB_ADDITIONS
            GitHubMetric.DELETIONS -> GRAPH_METRIC_GITHUB_DELETIONS
        }
    }

    /**
     * Toggles a graph metric on/off for [habitName]. Multiple metrics can be
     * active simultaneously. At least one metric remains selected (toggling off
     * the last one re-selects Points).
     */
    fun toggleGraphMetric(habitName: String, metric: String) {
        viewModelScope.launch {
            val current = _settings.value.graphMetricSelection.toMutableMap()
            // Use getSelectedMetrics which handles GitHub "value1" migration
            val currentSet = getSelectedMetrics(habitName).toMutableSet()
            if (metric in currentSet) {
                currentSet.remove(metric)
                // Ensure at least one metric stays selected
                if (currentSet.isEmpty()) currentSet.add(GRAPH_METRIC_POINTS)
            } else {
                currentSet.add(metric)
            }
            current[habitName] = currentSet
            settingsRepo.saveGraphMetricSelection(current)
            _settings.value = _settings.value.copy(graphMetricSelection = current)
        }
    }

    /** Returns whether "interpolate zeros" is enabled for [habitName]'s [metric]. */
    fun isGraphInterpolateZeroEnabled(habitName: String, metric: String): Boolean {
        return metric in (_settings.value.graphInterpolateZeroMetrics[habitName] ?: emptySet())
    }

    /**
     * Sets "interpolate zeros" for [habitName]'s [metric]. When enabled, days
     * with a 0 value on that metric are plotted with a linear interpolation
     * between the nearest non-zero values before and after them (for habits
     * like weight where a missing day is not really 0).
     */
    fun setGraphInterpolateZero(habitName: String, metric: String, enabled: Boolean) {
        val current = _settings.value.graphInterpolateZeroMetrics.toMutableMap()
        val metrics = current[habitName]?.toMutableSet() ?: mutableSetOf()
        if (enabled) metrics.add(metric) else metrics.remove(metric)
        if (metrics.isEmpty()) current.remove(habitName) else current[habitName] = metrics
        _settings.value = _settings.value.copy(graphInterpolateZeroMetrics = current)
        viewModelScope.launch { settingsRepo.saveGraphInterpolateZeroMetrics(current) }
    }

    /**
     * Returns the macro/nutrition totals for [habitName] on [date], or null if
     * the habit is not a meal habit. Used by the graph day-details popup.
     */
    fun getMealDayTotals(habitName: String, date: LocalDate): com.example.tail.data.meal.MealLogRepository.DayTotals? {
        if (!isMealHabit(habitName)) return null
        return mealLogRepo.dayTotals(habitName, dateString(date))
    }

    /**
     * Returns the cached commit messages for [habitName] on [date], or an empty
     * list when there is no message cache for the habit. Used by the graph's
     * day-details tooltip when the "Commits" metric is shown.
     */
    fun getCommitMessagesForDate(habitName: String, date: LocalDate): List<String> {
        return _githubCommitMessages[habitName]?.get(dateString(date)) ?: emptyList()
    }

    /**
     * Data point for a single day on the graph.
     */
    /**
     * True when JugCoach integration data exists for [habitName]: any of the
     * numbered secondary-value slots 2–6 (`secondary_value2:` … `secondary_value6:`)
     * is present in the cached DB. Only the JugCoach integration writes slots
     * 3–6 (chess.com writes slot 2 only for chess-linked habits, which are
     * never JugCoach-mapped), so key presence is a reliable detector.
     * Weights habits are excluded — they legitimately own slots 2–4.
     */
    fun isJugcoachHabit(habitName: String): Boolean =
        habitName !in _settings.value.weightsHabits &&
            (2..6).any { cachedPhoneDb.containsKey(secondaryValueSlotKey(habitName, it)) }

    data class GraphDataPoint(
        val date: LocalDate,
        val dateStr: String,
        val rawValue: Int,
        val pointsValue: Int,
        val textEntry: String? = null,  // for text-input habits
        val garminValue: Int? = null,   // for Garmin-linked habits (actual metric value)
        val secondaryValue: Int? = null, // for habits with secondary values enabled
        val tertiaryValue: Int? = null, // second-slot secondary value (secondary_value2:)
        // ── First-class minutes slot (`minutes:<habitName>`) ──
        val minutesValue: Int? = null,
        // ── Meal habit data (populated only for meal-type habits) ──
        val mealCalories: Int? = null,
        val mealProtein: Int? = null,   // rounded grams
        val mealCarbs: Int? = null,     // rounded grams
        val mealFat: Int? = null,       // rounded grams
        // ── GitHub habit data (populated only for GitHub-type habits) ──
        val githubLinesChanged: Int? = null,
        val githubCommits: Int? = null,
        val githubAdditions: Int? = null,
        val githubDeletions: Int? = null,
        // ── Movie-bridge habit data ──
        /** Total watch-minutes for the day (sum of "(N min)" entry annotations). */
        val movieRuntimeMinutes: Int? = null,
        // ── JugCoach habit data (populated only for JugCoach-fed habits) ──
        /** Total seconds spent juggling this day (`secondary_value:`). */
        val jugcoachTime: Int? = null,
        /** Total catches this day (`secondary_value2:`). */
        val jugcoachCatches: Int? = null,
        /** Seconds in runs that ended in a catch (`secondary_value3:`). */
        val jugcoachTimeCatch: Int? = null,
        /** Seconds in runs that ended in a drop (`secondary_value4:`). */
        val jugcoachTimeDrop: Int? = null,
        /** Catches in runs that ended in a catch (`secondary_value5:`). */
        val jugcoachCatchesCatch: Int? = null,
        /** Catches in runs that ended in a drop (`secondary_value6:`). */
        val jugcoachCatchesDrop: Int? = null,
        // ── Weights habit data (grams → display-unit tenths, see getGraphData) ──
        /** Heaviest machine weight of the day, ×10 in the display unit (kg or lb). */
        val weightsMachineWeight: Int? = null,
        /** Heaviest free weight of the day, ×10 in the display unit (kg or lb). */
        val weightsFreeWeight: Int? = null,
        /** Total machine reps of the day. */
        val weightsMachineReps: Int? = null,
        /** Total free reps of the day. */
        val weightsFreeReps: Int? = null
    )

    /**
     * Loads text entries for a text-input habit into the cache for graph filtering.
     * This should be called before using getGraphData with a text filter.
     */
    fun loadTextEntriesForGraph(habitName: String) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            _textEntriesCache.value = _textEntriesCache.value.toMutableMap().apply { remove(habitName) }
            return
        }
        viewModelScope.launch {
            try {
                val log = textInputRepo.loadTextLog(Uri.parse(uriString), context)
                _textEntriesCache.value = _textEntriesCache.value.toMutableMap().apply {
                    put(habitName, log)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load text entries for graph: ${e.message}")
            }
        }
    }
    
    /**
     * Returns the time-series data for a habit within the given date range.
     * Includes text entries for text-input habits if available.
     *
     * @param habitName The name of the habit
     * @param startDate The start date for the data range
     * @param endDate The end date for the data range
     * @param textFilter Optional text filter - for text-input habits, only includes days where
     *                   the text entry contains this filter string (case-insensitive)
     */
    fun getGraphData(
        habitName: String,
        startDate: LocalDate,
        endDate: LocalDate,
        textFilter: String = ""
    ): List<GraphDataPoint> {
        val isMeal = isMealHabit(habitName)
        // Garmin-linked habits keep their raw values in the Garmin cache; the
        // JSON key only holds the derived per-day points (and may not exist
        // yet). A missing key must not blank the whole graph — fall through
        // with empty entries so the Garmin series still renders.
        val isGarminLinked = _settings.value.garminHabitLinks.containsKey(habitName)
        val entries = cachedPhoneDb[habitName]
            ?: if (isMeal || isGarminLinked) emptyMap() else return emptyList()
        val divider = _settings.value.habitDividers[habitName] ?: 1

        // Secondary values (stored under "secondary_value:<habitName>" in the DB)
        // Also loaded for movie-bridge habits (IMDb average ratings stored there)
        val hasSecondary = habitName in _settings.value.secondaryValueHabits ||
            hasImdbRatings(habitName) || habitName in _settings.value.chessComHabitLinks
        val secondaryEntries = if (hasSecondary) cachedPhoneDb[secondaryValueKey(habitName)] else null
        // Second-slot secondary values (stored under "secondary_value2:<habitName>"),
        // written by the chess.com integration (daily win percentage)
        val tertiaryEntries = if (habitName in _settings.value.chessComHabitLinks) {
            cachedPhoneDb[secondaryValue2Key(habitName)]
        } else null
        // JugCoach juggling metrics (numbered slots 2–6; the seconds total lives
        // in the generic secondary_value: slot). Detected purely by key presence
        // so the graph buttons appear automatically once JugCoach has sent
        // session data — no settings toggle required.
        val isJugcoach = isJugcoachHabit(habitName)
        val jugcoachSlotEntries = if (isJugcoach) {
            (2..6).associate { slot -> slot to cachedPhoneDb[secondaryValueSlotKey(habitName, slot)] }
        } else null
        val jugcoachTimeEntries = if (isJugcoach) cachedPhoneDb[secondaryValueKey(habitName)] else null
        // Weights habit slots — machine weight (`secondary_value:`) / reps
        // (`secondary_value2:`), free weight (`secondary_value3:`) / reps
        // (`secondary_value4:`). Weights are STORED in grams and converted to
        // the graph's display unit (×10 scaled) here, so every consumer
        // (series, tooltips, stats, interpolation) works in the selected unit
        // while raw storage stays unit-agnostic.
        val isWeights = isWeightsHabit(habitName)
        val weightsUnit = _settings.value.graphWeightUnit
        val weightsMachineWeightEntries = if (isWeights) cachedPhoneDb[secondaryValueKey(habitName)] else null
        val weightsMachineRepsEntries = if (isWeights) cachedPhoneDb[secondaryValueSlotKey(habitName, 2)] else null
        val weightsFreeWeightEntries = if (isWeights) cachedPhoneDb[secondaryValueSlotKey(habitName, 3)] else null
        val weightsFreeRepsEntries = if (isWeights) cachedPhoneDb[secondaryValueSlotKey(habitName, 4)] else null
        val useSecondaryFallback = habitName in _settings.value.secondaryValueFallbackHabits
        val minutesPrimary = habitName in _settings.value.widgetTimerMinutesPrimary
        // First-class minutes slot (`minutes:<habitName>`) — exists for every
        // habit; populated by all timer-fed sources (phone bubble, PC widget,
        // trigger apps, media tracking, chess readiness).
        val minutesEntries = cachedPhoneDb[minutesKey(habitName)]
        val startStr = dateString(startDate)
        val endStr = dateString(endDate)

        // Check if this is a Garmin-linked habit
        val garminTypeStr = _settings.value.garminHabitLinks[habitName]
        val garminType = garminTypeStr?.let { GarminType.fromKey(it) }
        
        // Check if this is a text-input habit
        val isTextInput = isTextInputHabit(habitName)

        // Load per-day meal aggregates for meal-type habits
        val mealAggregates = if (isMeal) {
            mealLogRepo.dailyAggregates(habitName, startDate, endDate)
        } else {
            emptyMap()
        }
        
        // Use cached text entries for filtering
        val textEntriesMap = if (isTextInput && textFilter.isNotEmpty()) {
            _textEntriesCache.value[habitName] ?: emptyMap()
        } else {
            emptyMap()
        }

        // Per-day total runtime minutes for movie-bridge habits, derived from
        // the "(N min)" annotations in the cached text entries
        val runtimeByDate = if (isMovieBridgeHabit(habitName)) {
            val log = _textEntriesCache.value[habitName] ?: emptyMap()
            val totals = mutableMapOf<String, Int>()
            for ((timestamp, text) in log) {
                val minutes = OmdbService.parseTitle(text).minutes ?: continue
                if (timestamp.length >= 10) {
                    val dateStr = timestamp.substring(0, 10)
                    totals[dateStr] = (totals[dateStr] ?: 0) + minutes
                }
            }
            totals
        } else {
            null
        }

        val result = mutableListOf<GraphDataPoint>()
        var cursor = startDate
        while (!cursor.isAfter(endDate)) {
            val ds = dateString(cursor)
            val raw = entries[ds] ?: 0
            
            // For text-input habits with active filter, convert non-zero values to 0 if text doesn't match
            var filteredRaw = raw
            if (isTextInput && textFilter.isNotEmpty() && raw > 0) {
                val datePrefix = ds // Format: "yyyy-MM-dd"
                val entriesForDate = textEntriesMap.filter { (key, _) -> key.startsWith(datePrefix) }
                val hasMatchingText = entriesForDate.values.any { text ->
                    text.contains(textFilter, ignoreCase = true)
                }
                if (!hasMatchingText) {
                    // Convert to 0 if text doesn't match, but still include the day
                    filteredRaw = 0
                }
            }

            
            // Get Garmin value if this is a Garmin-linked habit
            val garminVal = if (garminType != null) {
                when (garminType) {
                    GarminType.FITNESS_AGE_DISTANCE -> {
                        // Calculate fitness age distance on-demand from FITNESS_AGE
                        // Fitness age is stored as hundredths of a year (e.g., 3704 for 37.04)
                        try {
                            val fitnessAgeData = _garminMonthlyData.value[GarminType.FITNESS_AGE]
                            val dobStr = _settings.value.garminDateOfBirth
                            if (fitnessAgeData != null && dobStr.isNotEmpty()) {
                                val fitnessAge = fitnessAgeData[ds]
                                if (fitnessAge != null) {
                                    val dob = LocalDate.parse(dobStr)
                                    // Calculate biological age in hundredths of a year
                                    val biologicalAgeYears = ChronoUnit.YEARS.between(dob, cursor).toDouble()
                                    val biologicalAgeHundredths = (biologicalAgeYears * 100).toInt()
                                    // Distance = fitness_age - biological_age (both in hundredths of a year)
                                    fitnessAge - biologicalAgeHundredths
                                } else null
                            } else null
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to calculate fitness age distance for graph: ${e.message}")
                            null
                        }
                    }
                    else -> _garminMonthlyData.value[garminType]?.get(ds)
                }
            } else null
            
            val secVal = secondaryEntries?.get(ds)
            val minutesVal = minutesEntries?.get(ds)
            val mealDay = mealAggregates[ds]
            val ghMetrics = _githubDailyCache[habitName]?.get(ds)
            // When cache is empty (before re-fetch), fall back to DB value for the
            // primary GitHub metric so at least that one shows data immediately.
            val ghPrimaryKey = if (isGithubHabit(habitName)) primaryGithubMetricKey(habitName) else null
            result.add(
                GraphDataPoint(
                    date = cursor,
                    dateStr = ds,
                    rawValue = filteredRaw,
                    pointsValue = if (minutesPrimary) {
                        // Minutes primary: minutes (dedicated slot) drive points,
                        // sessions are the fallback
                        com.example.tail.data.effectivePointsWithFallback(
                            minutesVal ?: 0, divider, filteredRaw, true
                        )
                    } else {
                        // Sessions primary: the fallback value lives in the legacy
                        // secondary slot for habits that use it or have data there,
                        // the minutes slot otherwise
                        val fallbackVal = if (
                            habitName in _settings.value.secondaryValueHabits ||
                            !cachedPhoneDb[secondaryValueKey(habitName)].isNullOrEmpty()
                        ) {
                            secVal ?: 0
                        } else {
                            minutesVal ?: 0
                        }
                        com.example.tail.data.effectivePointsWithFallback(
                            filteredRaw, divider, fallbackVal, useSecondaryFallback
                        )
                    },
                    garminValue = garminVal,
                    secondaryValue = secVal,
                    tertiaryValue = tertiaryEntries?.get(ds),
                    minutesValue = minutesVal,
                    mealCalories = mealDay?.calories,
                    mealProtein = mealDay?.proteinGrams?.roundToInt(),
                    mealCarbs = mealDay?.carbsGrams?.roundToInt(),
                    mealFat = mealDay?.fatGrams?.roundToInt(),
                    githubLinesChanged = ghMetrics?.linesChanged
                        ?: if (ghPrimaryKey == GRAPH_METRIC_GITHUB_LINES) filteredRaw else null,
                    githubCommits = ghMetrics?.commits
                        ?: if (ghPrimaryKey == GRAPH_METRIC_GITHUB_COMMITS) filteredRaw else null,
                    githubAdditions = ghMetrics?.additions
                        ?: if (ghPrimaryKey == GRAPH_METRIC_GITHUB_ADDITIONS) filteredRaw else null,
                    githubDeletions = ghMetrics?.deletions
                        ?: if (ghPrimaryKey == GRAPH_METRIC_GITHUB_DELETIONS) filteredRaw else null,
                    movieRuntimeMinutes = runtimeByDate?.get(ds),
                    // JugCoach time metrics are STORED in seconds but DISPLAYED
                    // in minutes — convert here (round-to-nearest) so every
                    // graph consumer (series, tooltips, interpolation) works
                    // in minutes while the raw storage keeps full precision.
                    jugcoachTime = jugcoachTimeEntries?.get(ds)?.let { (it + 30) / 60 },
                    jugcoachCatches = jugcoachSlotEntries?.get(2)?.get(ds),
                    jugcoachTimeCatch = jugcoachSlotEntries?.get(3)?.get(ds)?.let { (it + 30) / 60 },
                    jugcoachTimeDrop = jugcoachSlotEntries?.get(4)?.get(ds)?.let { (it + 30) / 60 },
                    jugcoachCatchesCatch = jugcoachSlotEntries?.get(5)?.get(ds),
                    jugcoachCatchesDrop = jugcoachSlotEntries?.get(6)?.get(ds),
                    weightsMachineWeight = weightsMachineWeightEntries?.get(ds)?.let { gramsToDisplayTenths(it, weightsUnit) },
                    weightsFreeWeight = weightsFreeWeightEntries?.get(ds)?.let { gramsToDisplayTenths(it, weightsUnit) },
                    weightsMachineReps = weightsMachineRepsEntries?.get(ds),
                    weightsFreeReps = weightsFreeRepsEntries?.get(ds)
                )
            )
            cursor = cursor.plusDays(1)
        }

        // ── Per-metric "interpolate zeros" ──────────────────────────────────
        // For each metric the user enabled it for, replace 0-valued days with
        // a linear interpolation between the nearest non-zero values.
        val interpMetrics = _settings.value.graphInterpolateZeroMetrics[habitName]
        if (!interpMetrics.isNullOrEmpty()) {
            for (metric in interpMetrics) {
                interpolateMetricZeros(result, metric)
            }
        }
        return result
    }

    /**
     * Returns the value of [metric] for [dp] — the same mapping the graph
     * uses for display (see GraphsScreen.displayValueForMetric).
     */
    private fun metricValueOf(dp: GraphDataPoint, metric: String): Int = when (metric) {
        GRAPH_METRIC_VALUE1 -> dp.garminValue ?: dp.rawValue
        GRAPH_METRIC_VALUE2 -> dp.secondaryValue ?: 0
        GRAPH_METRIC_VALUE3 -> dp.tertiaryValue ?: 0
        GRAPH_METRIC_MINUTES -> dp.minutesValue ?: 0
        GRAPH_METRIC_IMDB -> dp.secondaryValue ?: 0
        GRAPH_METRIC_RUNTIME -> dp.movieRuntimeMinutes ?: 0
        GRAPH_METRIC_CALORIES -> dp.mealCalories ?: 0
        GRAPH_METRIC_PROTEIN -> dp.mealProtein ?: 0
        GRAPH_METRIC_CARBS -> dp.mealCarbs ?: 0
        GRAPH_METRIC_FAT -> dp.mealFat ?: 0
        GRAPH_METRIC_GITHUB_LINES -> dp.githubLinesChanged ?: 0
        GRAPH_METRIC_GITHUB_COMMITS -> dp.githubCommits ?: 0
        GRAPH_METRIC_GITHUB_ADDITIONS -> dp.githubAdditions ?: 0
        GRAPH_METRIC_GITHUB_DELETIONS -> dp.githubDeletions ?: 0
        GRAPH_METRIC_JUGCOACH_TIME -> dp.jugcoachTime ?: 0
        GRAPH_METRIC_JUGCOACH_CATCHES -> dp.jugcoachCatches ?: 0
        GRAPH_METRIC_JUGCOACH_TIME_CATCH -> dp.jugcoachTimeCatch ?: 0
        GRAPH_METRIC_JUGCOACH_TIME_DROP -> dp.jugcoachTimeDrop ?: 0
        GRAPH_METRIC_JUGCOACH_CATCHES_CATCH -> dp.jugcoachCatchesCatch ?: 0
        GRAPH_METRIC_JUGCOACH_CATCHES_DROP -> dp.jugcoachCatchesDrop ?: 0
        GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT -> dp.weightsMachineWeight ?: 0
        GRAPH_METRIC_WEIGHTS_FREE_WEIGHT -> dp.weightsFreeWeight ?: 0
        GRAPH_METRIC_WEIGHTS_MACHINE_REPS -> dp.weightsMachineReps ?: 0
        GRAPH_METRIC_WEIGHTS_FREE_REPS -> dp.weightsFreeReps ?: 0
        else -> dp.pointsValue
    }

    /**
     * Returns a copy of [dp] with [metric]'s underlying field set to [value].
     * For Value 1 the Garmin value is overwritten when present, since it
     * takes precedence over the raw value on display.
     */
    private fun withMetricValue(dp: GraphDataPoint, metric: String, value: Int): GraphDataPoint = when (metric) {
        GRAPH_METRIC_VALUE1 -> if (dp.garminValue != null) dp.copy(garminValue = value) else dp.copy(rawValue = value)
        GRAPH_METRIC_VALUE2 -> dp.copy(secondaryValue = value)
        GRAPH_METRIC_VALUE3 -> dp.copy(tertiaryValue = value)
        GRAPH_METRIC_MINUTES -> dp.copy(minutesValue = value)
        GRAPH_METRIC_IMDB -> dp.copy(secondaryValue = value)
        GRAPH_METRIC_RUNTIME -> dp.copy(movieRuntimeMinutes = value)
        GRAPH_METRIC_CALORIES -> dp.copy(mealCalories = value)
        GRAPH_METRIC_PROTEIN -> dp.copy(mealProtein = value)
        GRAPH_METRIC_CARBS -> dp.copy(mealCarbs = value)
        GRAPH_METRIC_FAT -> dp.copy(mealFat = value)
        GRAPH_METRIC_GITHUB_LINES -> dp.copy(githubLinesChanged = value)
        GRAPH_METRIC_GITHUB_COMMITS -> dp.copy(githubCommits = value)
        GRAPH_METRIC_GITHUB_ADDITIONS -> dp.copy(githubAdditions = value)
        GRAPH_METRIC_GITHUB_DELETIONS -> dp.copy(githubDeletions = value)
        GRAPH_METRIC_JUGCOACH_TIME -> dp.copy(jugcoachTime = value)
        GRAPH_METRIC_JUGCOACH_CATCHES -> dp.copy(jugcoachCatches = value)
        GRAPH_METRIC_JUGCOACH_TIME_CATCH -> dp.copy(jugcoachTimeCatch = value)
        GRAPH_METRIC_JUGCOACH_TIME_DROP -> dp.copy(jugcoachTimeDrop = value)
        GRAPH_METRIC_JUGCOACH_CATCHES_CATCH -> dp.copy(jugcoachCatchesCatch = value)
        GRAPH_METRIC_JUGCOACH_CATCHES_DROP -> dp.copy(jugcoachCatchesDrop = value)
        GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT -> dp.copy(weightsMachineWeight = value)
        GRAPH_METRIC_WEIGHTS_FREE_WEIGHT -> dp.copy(weightsFreeWeight = value)
        GRAPH_METRIC_WEIGHTS_MACHINE_REPS -> dp.copy(weightsMachineReps = value)
        GRAPH_METRIC_WEIGHTS_FREE_REPS -> dp.copy(weightsFreeReps = value)
        else -> dp.copy(pointsValue = value)
    }

    /**
     * Replaces 0-valued days of [metric] in [points] (a contiguous daily
     * series) with a linear interpolation between the nearest non-zero values
     * before and after them. Days before the first non-zero value extend it
     * backwards; days after the last one extend it forwards. Modifies the
     * list in place; each metric only touches its own field.
     *
     * A stored 1 is treated as zero-like as well: decimal entry strips the
     * separator, so "0.01" is stored as 1 in hundredths-style habits.
     */
    private fun interpolateMetricZeros(points: MutableList<GraphDataPoint>, metric: String) {
        // 0 and 0.01 (stored as 1) both mean "no real measurement"
        fun isZeroLike(v: Int) = v == 0 || v == 1
        // Indices of days with a real (non-zero-like) value for this metric
        val realIdx = ArrayList<Int>()
        for (i in points.indices) {
            if (!isZeroLike(metricValueOf(points[i], metric))) realIdx.add(i)
        }
        if (realIdx.isEmpty()) return
        for (i in points.indices) {
            if (!isZeroLike(metricValueOf(points[i], metric))) continue
            // Insertion point of i among the real indices: realIdx[ins-1] is
            // the nearest real day before it, realIdx[ins] the next after it
            val ins = realIdx.binarySearch(i).let { if (it >= 0) it else -it - 1 }
            val prevPos = ins - 1
            val nextPos = ins
            val newValue = when {
                prevPos >= 0 && nextPos < realIdx.size -> {
                    val prev = realIdx[prevPos]
                    val next = realIdx[nextPos]
                    val vPrev = metricValueOf(points[prev], metric)
                    val vNext = metricValueOf(points[next], metric)
                    val span = next - prev
                    if (span <= 0) {
                        vPrev
                    } else {
                        val fraction = (i - prev).toDouble() / span
                        (vPrev + (vNext - vPrev) * fraction).roundToInt()
                    }
                }
                // after the last real value: hold forward
                prevPos >= 0 -> metricValueOf(points[realIdx[prevPos]], metric)
                // before the first real value: hold backward
                else -> metricValueOf(points[realIdx[nextPos]], metric)
            }
            points[i] = withMetricValue(points[i], metric, newValue)
        }
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
     * Includes all increment timestamps for the day, even those without text entries.
     */
    fun loadTextEntriesWithTimestamps(habitName: String, date: LocalDate, onResult: (List<Pair<String, String>>) -> Unit) {
        val uriString = _settings.value.textInputFileUris[habitName]
        val datePrefix = dateString(date)
        Log.d(TAG, "loadTextEntriesWithTimestamps: habit=$habitName date=$date prefix=$datePrefix uri=${uriString?.take(50)}")
        viewModelScope.launch {
            try {
                // Get all increment timestamps for the day
                val incrementTimestamps = timestampRepo.getTimestampsForDay(habitName, date)
                Log.d(TAG, "loadTextEntriesWithTimestamps: incrementTimestamps=$incrementTimestamps")
                
                // Get text entries if URI is available
                val textEntries = if (uriString.isNullOrEmpty()) {
                    Log.d(TAG, "loadTextEntriesWithTimestamps: no URI for habit=$habitName")
                    emptyMap()
                } else {
                    try {
                        val log = textInputRepo.loadTextLog(Uri.parse(uriString), context)
                        Log.d(TAG, "loadTextEntriesWithTimestamps: loaded ${log.size} total entries, keys sample=${log.keys.take(5)}")
                        log
                    } catch (e: Exception) {
                        Log.e(TAG, "loadTextEntriesWithTimestamps: failed to load text log", e)
                        emptyMap()
                    }
                }
                
                // Merge increment timestamps with text entries
                // Each increment timestamp gets its text if available, or empty string
                val entries: MutableList<Pair<String, String>> = incrementTimestamps.map { timestamp ->
                    val fullTimestamp = "$datePrefix $timestamp"
                    val text = textEntries[fullTimestamp] ?: ""
                    Pair(fullTimestamp, text)
                }.toMutableList()
                
                // Also include any text entries that match the date prefix but don't correspond
                // to increment timestamps (e.g., entries added for past days without increments)
                val usedTimestamps = entries.map { it.first }.toSet()
                val matchingKeys = textEntries.filterKeys { key -> key.startsWith(datePrefix) }
                Log.d(TAG, "loadTextEntriesWithTimestamps: matchingKeys for prefix=$datePrefix: ${matchingKeys.keys}")
                matchingKeys.filterKeys { key -> key !in usedTimestamps }
                    .forEach { (timestamp, text) ->
                        entries.add(Pair(timestamp, text))
                    }
                
                Log.d(TAG, "loadTextEntriesWithTimestamps: final entries count=${entries.size}")
                onResult(entries.sortedBy { it.first })
            } catch (e: Exception) {
                Log.e(TAG, "loadTextEntriesWithTimestamps: exception", e)
                onResult(emptyList())
            }
        }
    }

    /**
     * Reconciles a movie-bridge habit's timestamp store with its text log so
     * each entry's "HH:mm:ss" watch-start time is THE timestamp for its date
     * — one time per movie, never a separate confirm-time increment.
     *
     * Per date: when the day has at most as many distinct timestamps as text
     * entries, every timestamp belongs to a logged movie and the day is
     * rewritten to the entry times; when there are extra timestamps (manual
     * additions via the editor), only missing entry times are added and the
     * extras are preserved. Idempotent — no write when already in sync.
     */
    private suspend fun syncMovieTimestamps(habitName: String) {
        val uriString = _settings.value.textInputFileUris[habitName] ?: return
        val log = try {
            textInputRepo.loadTextLog(Uri.parse(uriString), context)
        } catch (e: Exception) {
            Log.w(TAG, "syncMovieTimestamps: failed to load text log for '$habitName': ${e.message}")
            return
        }
        val byDate = log.keys.groupBy({ it.substringBefore(' ') }, { it.substringAfter(' ') })
        for ((dateStr, times) in byDate) {
            val date = com.example.tail.data.parseDate(dateStr) ?: continue
            val desired = times.distinct().sorted()
            val currentDistinct = timestampRepo.getTimestampsForDay(habitName, date)
                .distinct().sorted()
            if (currentDistinct == desired) continue
            if (currentDistinct.size <= desired.size) {
                timestampRepo.setTimestampsForDay(habitName, date, desired)
            } else {
                val missing = desired.filter { it !in currentDistinct }
                if (missing.isNotEmpty()) {
                    timestampRepo.addTimestampsAt(habitName, date, missing)
                }
            }
        }
    }

    /** Runs [syncMovieTimestamps] for every enabled movie-bridge habit. */
    suspend fun syncAllMovieHabitTimestamps() {
        val s = _settings.value
        if (!s.bridgeEnabled) return
        for (habitName in s.bridgeMovieHabits) {
            syncMovieTimestamps(habitName)
        }
    }

    /**
     * Recomputes a movie-bridge habit's minutes slot (`minutes:<habit>`)
     * from the "(N min)" annotations in its text log: each day's stored
     * value is SET to the sum of that day's annotated watch lengths, so the
     * habit's minutes counter always shows the total minutes watched.
     * Idempotent — days already matching are not rewritten and stale values
     * (e.g. after an entry edit) are cleared.
     */
    private suspend fun syncMovieMinutesSlot(habitName: String) {
        if (!dbLoaded) return
        val uriString = _settings.value.textInputFileUris[habitName] ?: return
        val log = try {
            textInputRepo.loadTextLog(Uri.parse(uriString), context)
        } catch (e: Exception) {
            Log.w(TAG, "syncMovieMinutesSlot: failed to load text log for '$habitName': ${e.message}")
            return
        }
        val desired = OmdbService.aggregateMinutesByDate(log)
        val minKey = minutesKey(habitName)
        val existing = cachedPhoneDb[minKey] ?: emptyMap()

        // Diff only — skip the write when nothing changes.
        val updates = mutableMapOf<String, Int>()
        for ((date, minutes) in desired) {
            if (minutes > 0 && existing[date] != minutes) updates[date] = minutes
        }
        val stale = existing.keys.filter { (desired[it] ?: 0) <= 0 }
        if (updates.isEmpty() && stale.isEmpty()) return

        val mutableDb = cachedPhoneDb.toMutableMap()
        val entries = mutableDb[minKey]?.toMutableMap() ?: mutableMapOf()
        for ((date, minutes) in updates) entries[date] = minutes
        for (date in stale) entries.remove(date)
        mutableDb[minKey] = entries
        cachedPhoneDb = mutableDb

        val fileUri = _settings.value.fileUri
        if (fileUri.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(fileUri), context, mutableDb)
            }
        }
        Log.d(TAG, "Minutes slot synced for '$habitName': ${updates.size} set, ${stale.size} cleared")
    }

    /** Runs [syncMovieMinutesSlot] for every enabled movie-bridge habit. */
    suspend fun syncAllMovieMinutesSlots() {
        val s = _settings.value
        if (!s.bridgeEnabled) return
        for (habitName in s.bridgeMovieHabits) {
            try {
                syncMovieMinutesSlot(habitName)
            } catch (e: Exception) {
                Log.w(TAG, "Minutes slot sync failed for '$habitName': ${e.message}")
            }
        }
    }

    /**
     * One watched movie on the schedule timeline: its "(N min)" length (0
     * when the entry has no length yet) and its display title (the entry
     * text with the length annotation stripped).
     */
    data class MovieScheduleEntry(
        val minutes: Int,
        val title: String
    )

    /**
     * Loads a movie-bridge habit's entries for [date] from its text log:
     * watch time-of-day ("HH:mm:ss") → [MovieScheduleEntry] with the watched
     * minutes parsed from the "(N min)" annotations and the movie's display
     * title. The text-log timestamps are the source of truth for the
     * schedule timeline, so past films appear at their watch time even when
     * no increment timestamp exists for the day.
     */
    suspend fun loadMovieEntriesForDay(
        habitName: String,
        date: LocalDate
    ): Map<String, MovieScheduleEntry> {
        // The text log is the source of truth — reconcile the timestamp
        // store to it so each entry's watch time IS the habit's timestamp.
        syncMovieTimestamps(habitName)
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) return emptyMap()
        val datePrefix = dateString(date)
        return textInputRepo.loadTextLog(Uri.parse(uriString), context)
            .filterKeys { it.startsWith(datePrefix) && it.length >= 16 }
            .mapKeys { (timestamp, _) -> timestamp.substring(11) }
            .mapValues { (_, text) ->
                MovieScheduleEntry(
                    minutes = OmdbService.parseTitle(text).minutes ?: 0,
                    title = OmdbService.stripDurationAnnotation(text)
                )
            }
    }

    /**
     * Watch-recorded start time ("HH:mm:ss", null when unknown) and
     * duration minutes of the Garmin activity linked to [habitName] on
     * [date]; null when the habit is not linked to an activity-minutes
     * type (run/bike/swim) or has no cached data for that date. Used by
     * the schedule timeline to place past activities at their real time
     * and size the block to the duration.
     */
    suspend fun loadGarminActivityForDay(habitName: String, date: LocalDate): Pair<String?, Int>? {
        val type = _settings.value.garminHabitLinks[habitName]
            ?.let { GarminType.fromKey(it) } ?: return null
        if (type != GarminType.RUN_MINUTES && type != GarminType.BIKE_MINUTES &&
            type != GarminType.SWIM_MINUTES
        ) return null
        val minutes = garminRepo.cachedDailyValue(type, date.toString()) ?: return null
        if (minutes <= 0) return null
        return garminRepo.activityStartTime(type, date.toString()) to minutes
    }

    /**
     * Updates an existing text entry for [habitName].
     * [oldTimestamp] is the exact key; [newText] replaces the old value.
     * [onComplete] is called when the update is finished.
     */
    fun updateTextEntry(habitName: String, oldTimestamp: String, newText: String, onComplete: () -> Unit = {}) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            onComplete()
            return
        }
        viewModelScope.launch {
            try {
                // Update the text entry at the old timestamp
                textInputRepo.updateTextEntry(Uri.parse(uriString), context, oldTimestamp, newText, habitName = habitName)
                
                // If this is a roll forward habit, also roll forward the text
                if (habitName in _settings.value.rollForwardHabits) {
                    // Parse the date from the oldTimestamp (format: "YYYY-MM-DD HH:mm:ss")
                    val dateStr = oldTimestamp.substring(0, 10) // "YYYY-MM-DD"
                    val entryDate = com.example.tail.data.parseDate(dateStr)
                    
                    if (entryDate != null) {
                        // Find the next manual date (same logic as incrementHabit)
                        val nextManualDate = _settings.value.rollForwardManualDates[habitName]?.mapNotNull { dateStr ->
                            com.example.tail.data.parseDate(dateStr)
                        }?.sorted()?.firstOrNull { it > entryDate }
                        
                        val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()
                        
                        // Roll forward the text to all dates from entryDate+1 to endDate
                        if (entryDate < endDate) {
                            textInputRepo.rollForwardTextEntry(
                                Uri.parse(uriString),
                                context,
                                oldTimestamp,
                                entryDate.plusDays(1),
                                endDate,
                            habitName = habitName
                            )
                        }
                    }
                }
                
                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to update text entry: ${e.message}"
                onComplete()
            }
        }
    }

    /**
     * Sets the text entry for [habitName] on a specific [date], creating the entry
     * if none exists yet for that day. Uses noon (12:00:00) as the timestamp,
     * matching the convention used by [saveTextEntry] for past dates.
     * Does NOT increment the habit count — this only writes the text log.
     * [onComplete] is called when the write is finished.
     */
    fun setTextEntryForDate(habitName: String, date: LocalDate, text: String, onComplete: () -> Unit = {}) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) {
            onComplete()
            return
        }
        val timestamp = java.time.LocalDateTime.of(date, java.time.LocalTime.NOON)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        viewModelScope.launch {
            try {
                // updateTextEntry adds the key if it is missing
                textInputRepo.updateTextEntry(Uri.parse(uriString), context, timestamp, text, habitName = habitName)
                
                // If this is a roll forward habit, also roll forward the text
                if (habitName in _settings.value.rollForwardHabits) {
                    // Find the next manual date (same logic as incrementHabit)
                    val nextManualDate = _settings.value.rollForwardManualDates[habitName]?.mapNotNull { dateStr ->
                        com.example.tail.data.parseDate(dateStr)
                    }?.sorted()?.firstOrNull { it > date }
                    
                    val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()
                    
                    // Roll forward the text to all dates from date+1 to endDate
                    if (date < endDate) {
                        textInputRepo.rollForwardTextEntry(
                            Uri.parse(uriString),
                            context,
                            timestamp,
                            date.plusDays(1),
                            endDate,
                            habitName = habitName
                        )
                    }
                }
                
                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to set text entry: ${e.message}"
                onComplete()
            }
        }
    }

    /**
     * Completely removes a text entry for [habitName] from history.
     *
     * This deletes BOTH:
     *  1. The text value from the text-input log file (keyed by [timestamp]).
     *  2. The corresponding increment timestamp from the timestamp repository,
     *     so the entry vanishes entirely instead of lingering as an empty "(no text)" row.
     *
     * The habit's count is also decremented to stay in sync with the removed timestamp.
     *
     * [timestamp] is the full "YYYY-MM-DD HH:mm:ss" key.
     * [onComplete] is called when the deletion is finished.
     */
    fun deleteTextEntry(habitName: String, timestamp: String, onComplete: () -> Unit = {}) {
        val uriString = _settings.value.textInputFileUris[habitName]
        viewModelScope.launch {
            try {
                // 1. Delete the text entry from the text log (if URI is available)
                if (!uriString.isNullOrEmpty()) {
                    textInputRepo.deleteTextEntry(Uri.parse(uriString), context, timestamp, habitName = habitName)
                }

                // 2. Also delete the corresponding increment timestamp so the entry is
                //    completely removed from history (not just its text).
                //    The timestamp format is "YYYY-MM-DD HH:mm:ss".
                val timePart = timestamp.substringAfter(' ', "")
                if (timePart.isNotEmpty()) {
                    val datePart = timestamp.substringBefore(' ')
                    val entryDate = com.example.tail.data.parseDate(datePart) ?: _selectedDate.value
                    val dayTimestamps = timestampRepo.getTimestampsForDay(habitName, entryDate)
                    val matchIndex = dayTimestamps.indexOf(timePart)
                    if (matchIndex >= 0) {
                        timestampRepo.deleteTimestamp(habitName, entryDate, matchIndex)
                        // Decrement the habit count to match the removed timestamp
                        val currentHabit = _habits.value.find { it.name == habitName }
                        if (currentHabit != null && currentHabit.rawTodayCount > 0 && entryDate == _selectedDate.value) {
                            setHabitCount(habitName, currentHabit.rawTodayCount - 1)
                        }
                    }
                }

                onComplete()
            } catch (e: Exception) {
                _errorMessage.value = "Failed to delete text entry: ${e.message}"
                onComplete()
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
     * App-link entries are NOT habits (they are grid shortcuts that launch an
     * app), so they are excluded — every habit-selection picker (conditional
     * links, chess readiness, map settings, …) is fed from this list.
     */
    fun getAllHabitNames(): List<String> {
        val screens = _habitScreens.value
        return if (screens.isNotEmpty()) {
            screens.flatMap { it.habitNames }
                .filter { it.isNotEmpty() && !isAppLink(it) }
                .distinct()
        } else {
            val order = _habitOrder.value
            (if (order.isNotEmpty()) order else HABIT_ORDER)
                .filter { it.isNotEmpty() && !isAppLink(it) }
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
     * Toggles whether a habit is the "main" habit that determines map dot colors.
     * Only one habit can be the main habit at a time. If the habit is already the main habit,
     * it is set to null (turning off the feature). Otherwise, it is set to the habit name.
     */
    fun toggleMapMainHabit(habitName: String) {
        viewModelScope.launch {
            val newMainHabit = if (_settings.value.mapMainHabit == habitName) {
                null // Toggle off if already the main habit
            } else {
                habitName // Set as new main habit
            }
            settingsRepo.saveMapMainHabit(newMainHabit)
            _settings.value = _settings.value.copy(mapMainHabit = newMainHabit)
        }
    }

    /**
     * Toggles whether to hide days with 0 value (or 0 monthly average if no main habit).
     */
    fun toggleMapHideZeroDays() {
        viewModelScope.launch {
            val newValue = !_settings.value.mapHideZeroDays
            settingsRepo.saveMapHideZeroDays(newValue)
            _settings.value = _settings.value.copy(mapHideZeroDays = newValue)
        }
    }

    /**
     * Sets the custom start date for the map timeline.
     * Pass empty string to use the default earliest date.
     */
    fun setMapBeginDate(date: String) {
        viewModelScope.launch {
            settingsRepo.saveMapBeginDate(date)
            _settings.value = _settings.value.copy(mapBeginDate = date)
        }
    }

    /**
     * Returns the points value for [habitName] on [date].
     * Returns 0 if the habit has no data for that date.
     */
    fun getHabitValueForDate(habitName: String, date: LocalDate): Int {
        val dateStr = dateString(date)
        val raw = cachedPhoneDb[habitName]?.get(dateStr) ?: 0
        return effectivePointsForDate(habitName, raw, dateStr)
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
        // Scheduled-ask catch-up: refresh the alarms and, for any habit whose
        // ask time already passed today but never fired (phone off, process
        // killed), create the ask now. fireScheduledAsk is once-per-day-per-
        // habit, so this is safe on every ON_START.
        viewModelScope.launch {
            try {
                val s = _settings.value
                if (s.habitScheduleTimes.isEmpty()) return@launch
                com.example.tail.notify.HabitAlarmReceiver.rescheduleAll(context)
                val now = System.currentTimeMillis()
                s.habitScheduleTimes.forEach { (habit, time) ->
                    if (com.example.tail.data.HabitSchedule.passedToday(time, now)) {
                        com.example.tail.notify.HabitAsks.fireScheduledAsk(context, habit, now)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Scheduled-ask catch-up failed: ${e.message}")
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
            // Re-check the widget-trigger monitor service: the user may have
            // just granted Usage Access in system settings (the service is
            // started regardless of permission state, but this also recovers
            // the case where the app process was killed while a trigger app
            // was configured).
            val triggerSettings = _settings.value
            val triggerCount = triggerSettings.widgetTriggerApps.values.count { it.isNotBlank() } +
                if (triggerSettings.chessReadinessEnabled && triggerSettings.chessReadinessApp.isNotBlank()) 1 else 0
            if (triggerCount > 0) {
                com.example.tail.widget.WidgetTriggerService.updateServiceState(context, triggerCount)
            }
            // Drain the PC widget event queue on the bridge BEFORE reloading
            // the phone DB below, so sessions recorded on the PC show up in
            // this same refresh. No-op when the bridge isn't configured.
            try {
                if (_settings.value.garminProxyUrl.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        PcEventQueueProcessor(context).processOnce()
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "onAppForegrounded: PC event queue drain failed: ${e.message}")
            }
            // Re-read the phone DB so external increments (e.g. from ShareTextActivity)
            // are visible immediately when the user returns to the app.
            val phoneUriStr = _settings.value.fileUri
            if (phoneUriStr.isNotEmpty()) {
                try {
                    val uri = Uri.parse(phoneUriStr)

                    // ── Roll forward MUST run BEFORE ensureDaysExist ──────────────
                    // Same ordering as catchUpAndLoad: load the raw DB from disk,
                    // run performRollForwardIfNeeded, THEN let ensureDaysExist fill
                    // missing days. Without this, when the app returns from
                    // background (without being killed), roll forward never runs
                    // and today's value stays at the 0 placeholder created by
                    // ensureDaysExist. Worse: that 0 is persisted, so the next
                    // day's roll forward sees yesterday=0 and skips too — causing
                    // a cascading failure that only a manual set can break.
                    val loadResult = withContext(Dispatchers.IO) {
                        habitsRepo.loadDatabaseResult(uri, context)
                    }
                    if (loadResult is com.example.tail.data.HabitsLoadResult.Success) {
                        cachedPhoneDb = loadResult.db
                        performRollForwardIfNeeded()
                    }

                    // Now fill in any remaining missing days. Today already has
                    // the rolled-forward value, so ensureDaysExist won't overwrite it.
                    val db = withContext(Dispatchers.IO) {
                        habitsRepo.ensureDaysExist(uri, context)
                    }
                    cachedPhoneDb = db
                    rebuildHabitList()
                } catch (e: Exception) {
                    Log.w(TAG, "onAppForegrounded: failed to reload phone DB: ${e.message}")
                }
            }

            // Re-apply ALL cached Garmin data to linked habits. This ensures that
            // historic data from JSON import (e.g. swim activities from months ago)
            // is always reflected in the habit entries, even after a DB reload or
            // cache clear/fetch cycle. The init block loads _garminMonthlyData from
            // cache, but onAppForegrounded replaces cachedPhoneDb from disk — so we
            // must re-sync the two. applyGarminData is idempotent (writes the same
            // computed value), so this is safe to call on every foreground.
            //
            // NOTE: This does NOT require garminEnabled — cached data from a JSON
            // import must be applied even when the proxy is disabled.
            val s = _settings.value
            if (s.fileUri.isNotEmpty()) {
                try {
                    val cachedGarminData = withContext(Dispatchers.IO) {
                        garminRepo.loadAllCachedData()
                    }
                    if (cachedGarminData.isNotEmpty()) {
                        mergeIntoGarminMonthlyData(cachedGarminData)
                        val fgSettings = autoLinkMissingGarminHabits(cachedGarminData)
                        applyGarminData(cachedGarminData, fgSettings)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onAppForegrounded: failed to re-apply cached Garmin data: ${e.message}")
                }
            }

            // Automatically sync Garmin data when app comes to foreground
            // This fetches any new data that the PC fetcher has accumulated
            syncGarminCurrentMonth()
            
            syncAllDatedEntries(forceReparse = false)

            // Bootstrap internal backups of text-input files from external SAF sources.
            // This ensures data survives even if the external file is deleted/corrupted.
            val textUris = _settings.value.textInputFileUris
            if (textUris.isNotEmpty()) {
                try {
                    val backed = textInputRepo.bootstrapInternalBackups(context, textUris)
                    if (backed > 0) {
                        Log.i(TAG, "onAppForegrounded: bootstrapped $backed text-input internal backups")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onAppForegrounded: text-input backup bootstrap failed: ${e.message}")
                }
            }
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
            // Pre-replace values per date — the conditional feed baseline.
            val beforeEntries = habitEntries.toMap()
            for ((dateStr, count) in parsedCounts) {
                habitEntries[dateStr] = count
            }
            mutableDb[habitName] = habitEntries.toSortedMap()

            // Conditional propagation: a dated-entry habit configured as a
            // conditional source feeds its linked habits for POSITIVE day
            // deltas (new entries in the file), mirroring the GitHub sync
            // path. Without this, file-driven habits (e.g. "Chess Video")
            // would never feed their linked aggregates ("Chess").
            val st = _settings.value
            if (habitName in st.conditionalHabits) {
                val linkedNames = st.conditionalLinkedHabits[habitName] ?: emptySet()
                val positiveDayDeltas = positiveSyncDayDeltas(beforeEntries, parsedCounts)
                if (linkedNames.isNotEmpty() && positiveDayDeltas.isNotEmpty()) {
                    val feedPoints = habitName in st.conditionalFeedPointsHabits
                    val sourceDivider = st.habitDividers[habitName] ?: 1
                    for (linkedName in linkedNames) {
                        val valueKey = effectiveConditionalLinkValueKey(
                            st.conditionalLinkValues, st.secondaryValueHabits,
                            st.chessComHabitLinks, habitName, linkedName
                        )
                        val targetKey = conditionalLinkStorageKey(linkedName, valueKey)
                        for ((date, storedBefore, delta) in positiveDayDeltas) {
                            val baseFeedAmount = if (feedPoints && sourceDivider > 1) {
                                applyDivider(storedBefore + delta, sourceDivider) -
                                    applyDivider(storedBefore, sourceDivider)
                            } else delta
                            val feedAmount = if (targetKey == linkedName) {
                                conditionalSyncFeedAmount(
                                    storedBefore, baseFeedAmount,
                                    habitName in st.conditionalFeedMaxOneHabits
                                )
                            } else baseFeedAmount
                            if (feedAmount == 0) continue
                            val targetEntries = (mutableDb[targetKey] ?: emptyMap()).toMutableMap()
                            val existing = targetEntries[date] ?: 0
                            val newVal = if (targetKey == linkedName && linkedName in st.maxOneHabits) {
                                1
                            } else existing + feedAmount
                            if (newVal != existing) {
                                targetEntries[date] = newVal
                                mutableDb[targetKey] = targetEntries.toSortedMap()
                            }
                        }
                    }
                }
            }
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

    /** Saves chess.com settings (enabled, username). */
    fun saveChessComSettings(enabled: Boolean, username: String) {
        viewModelScope.launch {
            settingsRepo.saveChessComSettings(enabled, username)
            _settings.value = _settings.value.copy(
                chessComEnabled = enabled,
                chessComUsername = username
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
            if (chessComType != null) {
                ensureChessComValueLabels(_settings.value)?.let { _settings.value = it }
            } else {
                removeChessComValueLabels(habitName)
            }
        }
    }

    /**
     * Auto-set display labels for the three chess.com value slots:
     * Value1 = Minutes (primary), Value2 = Games, Value3 = Result (win %).
     */
    private val chessComValueLabels = mapOf(
        GRAPH_METRIC_VALUE1 to "Minutes",
        GRAPH_METRIC_VALUE2 to "Games",
        GRAPH_METRIC_VALUE3 to "Result"
    )

    /**
     * Labels set by an earlier auto-label scheme (Value1 = Games,
     * Value2 = Minutes). Still migrated to the current labels on sync so the
     * re-mapped slots are named correctly.
     */
    private val legacyChessComValueLabels = mapOf(
        GRAPH_METRIC_VALUE1 to "Games",
        GRAPH_METRIC_VALUE2 to "Minutes",
        GRAPH_METRIC_VALUE3 to "Result"
    )

    /**
     * Ensures every chess.com-linked habit carries display labels for its three
     * value slots (Minutes / Games / Result). Fills in missing labels and
     * migrates labels set by an earlier auto-label scheme — genuinely custom
     * user labels are preserved. Returns the updated settings, or null when
     * nothing changed.
     */
    private suspend fun ensureChessComValueLabels(s: AppSettings): AppSettings? {
        if (s.chessComHabitLinks.isEmpty()) return null
        val current = s.valueDisplayLabels.toMutableMap()
        var changed = false
        for (habitName in s.chessComHabitLinks.keys) {
            val inner = current[habitName]?.toMutableMap() ?: mutableMapOf()
            for ((key, label) in chessComValueLabels) {
                val existing = inner[key]
                val isAutoLabel = existing.isNullOrBlank() ||
                    existing == legacyChessComValueLabels[key]
                if (isAutoLabel && existing != label) {
                    inner[key] = label
                    changed = true
                }
            }
            current[habitName] = inner
        }
        if (!changed) return null
        settingsRepo.saveValueDisplayLabels(current)
        return s.copy(valueDisplayLabels = current)
    }

    /**
     * Removes the auto-set chess.com value labels when a habit is unlinked.
     * Only labels still equal to an auto-set value (current or legacy) are
     * removed — custom user labels survive.
     */
    private fun removeChessComValueLabels(habitName: String) {
        val current = _settings.value.valueDisplayLabels.toMutableMap()
        val inner = current[habitName]?.toMutableMap() ?: return
        var changed = false
        for ((key, label) in chessComValueLabels) {
            val existing = inner[key]
            if (existing == label || existing == legacyChessComValueLabels[key]) {
                inner.remove(key)
                changed = true
            }
        }
        if (!changed) return
        if (inner.isEmpty()) current.remove(habitName) else current[habitName] = inner
        _settings.value = _settings.value.copy(valueDisplayLabels = current)
        viewModelScope.launch { settingsRepo.saveValueDisplayLabels(current) }
    }

    /** Starts the periodic chess.com polling loop. */
    private fun startChessComPolling() {
        // Cancel any existing polling job
        chessComPollingJob?.cancel()
        chessComPollingJob = viewModelScope.launch {
            // One-time: sweep the user's ENTIRE chess.com history into the
            // Chess Readiness activity log so the stats page covers the
            // whole account, not just the months since this feature shipped.
            backfillChessComHistoryOnce()
            while (true) {
                syncChessComCurrentMonth()
                delay(CHESS_COM_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * One-time automatic backfill of the user's ENTIRE chess.com game
     * history (every monthly archive, back to account creation) into the
     * Chess Readiness activity log. The persisted marker in
     * [ChessReadinessLogStore] makes it a single sweep per username; a
     * sweep with failed months stays unmarked and is retried on the next
     * app start. Only the readiness log is filled — linked habit data is
     * intentionally untouched (the Settings "Fetch Entire Backlog" button
     * remains the deliberate reset+reapply path for habits).
     */
    private suspend fun backfillChessComHistoryOnce() {
        val s = _settings.value
        if (!s.chessComEnabled || s.chessComUsername.isEmpty()) return
        if (ChessReadinessLogStore.isHistoryBackfilled(context, s.chessComUsername)) return
        try {
            _chessComSyncStatus.value = "Backfilling full chess.com history…"
            val result = chessComRepo.fetchAllArchiveGames(
                s.chessComUsername,
                onProgress = { done, total ->
                    _chessComSyncStatus.value = "Backfilling history: $done / $total months"
                },
                onGames = { games ->
                    try {
                        ChessReadinessLogStore.logGames(context, games, s.chessComUsername)
                    } catch (e: Exception) {
                        Log.w(TAG, "Readiness backfill logging failed: ${e.message}")
                    }
                }
            )
            if (result.failedMonths == 0) {
                ChessReadinessLogStore.markHistoryBackfilled(context, s.chessComUsername)
                Log.i(TAG, "Chess.com history backfill complete (${result.totalMonths} months)")
            } else {
                // Keep the marker unset so the next app start retries the missing months.
                Log.w(
                    TAG,
                    "Chess.com history backfill incomplete: ${result.failedMonths}/" +
                        "${result.totalMonths} months failed — will retry next launch"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Chess.com history backfill failed: ${e.message}")
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
     *
     * Even when no habits are linked to chess.com types, the poll still runs
     * so every game is written to the Chess Readiness activity log
     * ([ChessReadinessLogStore]) with its readiness context — the habit
     * application is simply skipped.
     */
    private suspend fun syncChessComCurrentMonth() {
        val s = _settings.value
        if (!s.chessComEnabled || s.chessComUsername.isEmpty()) return
        val applyToHabits = s.chessComHabitLinks.isNotEmpty() && s.fileUri.isNotEmpty()

        // Self-heal: if the initial load failed (dbLoaded==false), try to load the
        // DB now BEFORE syncing, so a one-off startup read failure doesn't leave the
        // sync permanently gated. applyChessComData is still gated as a backstop.
        if (applyToHabits && !dbLoaded) {
            try {
                val db = withContext(Dispatchers.IO) {
                    habitsRepo.ensureDaysExist(Uri.parse(s.fileUri), context)
                }
                cachedPhoneDb = db
                dbLoaded = true
                rebuildHabitList()
            } catch (e: Exception) {
                Log.w(TAG, "syncChessComCurrentMonth: DB still not loadable, skipping sync: ${e.message}")
                return
            }
        }

        try {
            _chessComSyncStatus.value = "Syncing chess.com data…"
            // Raw games are kept alongside the aggregated stats so
            // applyChessComData can stamp each NEW game's timestamp at the
            // game's actual end time instead of N duplicates at sync time.
            val fetchedGames = mutableListOf<com.example.tail.data.ChessComGame>()
            val monthData = chessComRepo.fetchCurrentMonthData(s.chessComUsername) { games ->
                fetchedGames.addAll(games)
                // Chess Readiness activity log: every fetched game is recorded
                // (deduped) with the readiness context at its end time.
                try {
                    ChessReadinessLogStore.logGames(context, games, s.chessComUsername)
                } catch (e: Exception) {
                    Log.w(TAG, "Readiness game logging failed: ${e.message}")
                }
            }
            if (applyToHabits) applyChessComData(monthData, s, fetchedGames)
            _chessComSyncStatus.value = "Last sync: ${java.time.LocalTime.now().toString().take(5)}"
        } catch (e: Exception) {
            Log.e(TAG, "Chess.com sync failed: ${e.message}")
            _chessComSyncStatus.value = "Sync failed: ${e.message?.take(50)}"
        }

        // Deferred game pipeline: retry shares that were parked because the
        // game wasn't in any chess.com archive yet. Each poll re-fetches
        // them; whatever has appeared is classified by the readiness state
        // at the moment the game ended (approved → full Phase 2 audit,
        // otherwise → unapproved in the compliance stats).
        try {
            val summary = ChessDeferredGameReconciler.reconcilePending(
                context, s.chessComUsername
            )
            if (summary.resolved > 0) {
                Log.i(
                    TAG,
                    "Pending chess games reconciled: ${summary.audited} audited, " +
                        "${summary.unauthorized} unauthorized, " +
                        "${summary.stillPending} still waiting"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Pending chess game reconcile failed: ${e.message}")
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
                // Snapshot the PRE-reset stored primary values: conditional
                // feeds must be computed against what was really stored before
                // the reset zeroes the linked habits, so a backlog re-fetch
                // never re-feeds history into conditional linked habits (the
                // same pattern as fetchGithubBacklog). Without this, every
                // backlog fetch re-applies the full minutes history as fresh
                // deltas and multiplies the linked aggregates (e.g. "Chess").
                val preResetEntries = s.chessComHabitLinks.keys.mapNotNull { habit ->
                    cachedPhoneDb[habit]?.takeIf { it.isNotEmpty() }?.let { habit to it }
                }.toMap()
                // Reset all chess.com-linked habits to 0 for all dates
                _chessComSyncStatus.value = "Resetting linked habit data…"
                resetChessComHabitData(s)

                _chessComSyncStatus.value = "Fetching entire backlog…"
                val fetchedGames = mutableListOf<com.example.tail.data.ChessComGame>()
                val result = chessComRepo.fetchAllArchiveGames(
                    s.chessComUsername,
                    onProgress = { done, total ->
                        _chessComSyncStatus.value = "Fetching archives: $done / $total months"
                    },
                    onGames = { games ->
                        fetchedGames.addAll(games)
                        // Backfill the Chess Readiness activity log with every
                        // fetched month (deduped inside the store).
                        try {
                            ChessReadinessLogStore.logGames(context, games, s.chessComUsername)
                        } catch (e: Exception) {
                            Log.w(TAG, "Readiness backlog logging failed: ${e.message}")
                        }
                    }
                )
                _chessComSyncStatus.value = "Applying backlog data to habits…"
                applyChessComData(result.daily, s, fetchedGames, preResetEntries)
                if (result.failedMonths == 0) {
                    ChessReadinessLogStore.markHistoryBackfilled(context, s.chessComUsername)
                    _chessComSyncStatus.value = "Backlog complete! Data applied to linked habits."
                } else {
                    _chessComSyncStatus.value =
                        "Backlog applied, but ${result.failedMonths} month(s) failed — " +
                            "they'll be retried on next app start."
                }
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
        if (!dbLoaded) {
            Log.w(TAG, "resetChessComHabitData: DB not loaded yet, refusing to persist (anti-wipe gate)")
            return
        }

        val mutableDb = cachedPhoneDb.toMutableMap()
        var changed = false

        for ((habitName, _) in s.chessComHabitLinks) {
            // Reset the primary count and both secondary-value slots
            for (key in listOf(habitName, secondaryValueKey(habitName), secondaryValue2Key(habitName))) {
                val entries = mutableDb[key] ?: continue
                val resetEntries = entries.mapValues { 0 }.toSortedMap()
                if (resetEntries != entries) {
                    mutableDb[key] = resetEntries
                    changed = true
                }
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
     * Re-reads the habits DB from disk into [cachedPhoneDb] right before a
     * full-snapshot persist (chess.com / GitHub / Garmin sync paths).
     *
     * Concurrent writers in other processes — most notably the voice-capture
     * service ([com.example.tail.ipc.SmartVoiceService]) — persist their
     * increments directly to disk via read-modify-write. Persisting a
     * snapshot built from a stale in-memory cache silently reverts those
     * writes (this is how the "water 750" voice capture was wiped on
     * 2026-08-19: chess.com polling persisted a seconds-stale snapshot).
     */
    private suspend fun refreshCachedDbFromDisk(fileUri: String) {
        if (fileUri.isEmpty()) return
        try {
            val fresh = withContext(Dispatchers.IO) {
                habitsRepo.loadDatabase(Uri.parse(fileUri), context)
            }
            if (fresh.isNotEmpty()) {
                cachedPhoneDb = fresh
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Never swallow cancellation — the caller's scope is gone and the
            // apply must abort rather than race a dead ViewModel's persist.
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "refreshCachedDbFromDisk failed, keeping cached db: ${e.message}")
        }
    }

    /**
     * Applies chess.com daily stats (minutes, games, wins) to linked habits.
     * For each linked habit, writes three raw values per day:
     *  - minutes → the habit's primary count (rounded, min 1 on days with
     *    games); points are derived from minutes via the habit's divider
     *  - games   → the `secondary_value:` slot (Value2)
     *  - result  → the `secondary_value2:` slot — win percentage (0-100,
     *    rounded; 0 on days with no games)
     * Chess.com data is authoritative — values are always overwritten (not max'd).
     *
     * Also propagates to conditional linked habits: if a chess-linked habit is
     * configured as a conditional habit, any date where its minutes increase
     * also increments each conditional linked habit.
     *
     * [preResetEntries] carries the linked habits' PRE-reset primary values
     * when the caller zeroed them first (fetchChessComBacklog). Conditional
     * feed deltas are computed against that snapshot — NOT the post-reset
     * zeros — so a backlog re-fetch never re-feeds history into linked
     * habits (same contract as applyGithubData's beforeEntries).
     */
    private suspend fun applyChessComData(
        data: Map<ChessComType, DailyStatsMap>,
        s: AppSettings,
        rawGames: List<com.example.tail.data.ChessComGame> = emptyList(),
        preResetEntries: Map<String, Map<String, Int>> = emptyMap()
    ) {
        // Auto-label the three value slots (Minutes / Games / Result) — also
        // covers habits linked before this labeling existed.
        ensureChessComValueLabels(s)?.let { _settings.value = it }

        if (data.isEmpty() || s.chessComHabitLinks.isEmpty()) return

        val phoneUriStr = s.fileUri
        if (phoneUriStr.isEmpty()) return
        // ANTI-WIPE GATE: never merge into and persist cachedPhoneDb before the real
        // DB has been loaded. Otherwise the polling loop can build a chess-only DB
        // from an empty cache and overwrite everything (the 2026-07-19 wipe).
        if (!dbLoaded) {
            Log.w(TAG, "applyChessComData: DB not loaded yet, skipping persist (anti-wipe gate)")
            return
        }

        // Pick up concurrent on-disk writes (e.g. voice-capture increments)
        // before building the snapshot — see refreshCachedDbFromDisk.
        refreshCachedDbFromDisk(phoneUriStr)

        var dbChanged = false
        val mutableDb = cachedPhoneDb.toMutableMap()
        // Track per-habit NEW-game stamp times for timestamp recording
        // (only add NEW timestamps, one per new game at its actual end time)
        val todayStr = dateString(LocalDate.now())
        val todayNewTimes = mutableMapOf<String, List<String>>()

        for ((habitName, typeKey) in s.chessComHabitLinks) {
            val type = ChessComType.fromKey(typeKey) ?: continue
            val dailyStats = data[type] ?: continue

            val habitEntries = (mutableDb[habitName] ?: emptyMap()).toMutableMap() // minutes (primary)
            val gamesEntries = (mutableDb[secondaryValueKey(habitName)] ?: emptyMap()).toMutableMap()
            val resultEntries = (mutableDb[secondaryValue2Key(habitName)] ?: emptyMap()).toMutableMap()
            // Track per-date primary (minutes) deltas for conditional propagation
            // (any date where count increased, not just 0→non-zero)
            val dateDeltas = mutableMapOf<String, Int>()
            // Pre-update primary counts per date — needed by the conditional
            // "feed max1" cap to tell first-activity days from already-fed ones.
            val preExistingCounts = mutableMapOf<String, Int>()
            // Pre-update games count for today — timestamps are recorded per
            // NEW game, not per minute.
            val preGamesToday = gamesEntries[todayStr] ?: 0

            // Pre-reset primary values for THIS habit (backlog path); empty on
            // the poll path, where the current stored values are the baseline.
            val beforeEntries = preResetEntries[habitName] ?: emptyMap()
            for ((dateStr, stats) in dailyStats) {
                val games = stats.games
                // Any day with at least one game records at least 1 minute
                val minutes = if (games > 0) {
                    maxOf(Math.round(stats.minutes).toInt(), 1)
                } else 0
                // Win percentage of games played (0-100); 0 on days with no games
                val winPct = if (games > 0) {
                    Math.round(stats.wins * 100.0 / games).toInt()
                } else 0

                val existingMinutes = habitEntries[dateStr] ?: 0
                // Conditional feed baseline: what was stored BEFORE any reset
                // (poll path: identical to existingMinutes).
                val storedBefore = beforeEntries[dateStr] ?: existingMinutes
                preExistingCounts[dateStr] = storedBefore
                if (minutes != existingMinutes) {
                    val delta = minutes - storedBefore
                    if (delta > 0) dateDeltas[dateStr] = delta
                    habitEntries[dateStr] = minutes
                    dbChanged = true
                }
                val existingGames = gamesEntries[dateStr]
                if (existingGames != games) {
                    gamesEntries[dateStr] = games
                    dbChanged = true
                }
                val existingResult = resultEntries[dateStr]
                if (existingResult != winPct) {
                    resultEntries[dateStr] = winPct
                    dbChanged = true
                }
            }
            mutableDb[habitName] = habitEntries.toSortedMap()
            mutableDb[secondaryValueKey(habitName)] = gamesEntries.toSortedMap()
            mutableDb[secondaryValue2Key(habitName)] = resultEntries.toSortedMap()

            // Track today's timestamps: ONE per NEW game, stamped at the
            // game's actual end time (not sync time) so the schedule view
            // shows each game where it really happened instead of a ×N
            // pile-up at the sync moment. At least one stamp when minutes
            // moved without a new game (e.g. rounding).
            val todayDelta = dateDeltas[todayStr]
            if (todayDelta != null && todayDelta > 0) {
                val newGames = ((gamesEntries[todayStr] ?: 0) - preGamesToday).coerceAtLeast(0)
                val needed = if (newGames > 0) newGames else 1
                val endTimes = if (newGames > 0) {
                    com.example.tail.data.newGameEndTimes(
                        rawGames, s.chessComUsername, type, todayStr, newGames
                    )
                } else emptyList()
                val now = HabitTimestampRepository.nowTime()
                val times = endTimes.toMutableList()
                // Shortfall (missing raw games / rounding) falls back to now.
                repeat(needed - times.size) { times.add(now) }
                todayNewTimes[habitName] = times
            }

            // Propagate to conditional linked habits for dates where count increased
            if (dateDeltas.isNotEmpty() && habitName in s.conditionalHabits) {
                val linkedHabits = s.conditionalLinkedHabits[habitName] ?: emptySet()
                // Match the tap-path feed semantics (incrementHabit step 2c):
                // "Feed points" sources feed their divider-applied POINTS delta
                // instead of the raw minutes delta, so a minutes habit with a
                // divider feeds divided points to its linked habits.
                val feedPoints = habitName in s.conditionalFeedPointsHabits
                val sourceDivider = s.habitDividers[habitName] ?: 1
                for (linkedName in linkedHabits) {
                    // Feed the value slot this link is configured to target
                    // (Points = the linked habit's count; Value2/Value3 = raw slots)
                    val targetKey = conditionalLinkStorageKey(
                        linkedName,
                        effectiveConditionalLinkValueKey(
                            s.conditionalLinkValues, s.secondaryValueHabits, s.chessComHabitLinks,
                            habitName, linkedName
                        )
                    )
                    val linkedEntries = (mutableDb[targetKey] ?: emptyMap()).toMutableMap()
                    for ((dateStr, delta) in dateDeltas) {
                        val preCount = preExistingCounts[dateStr] ?: 0
                        val baseFeedAmount = if (feedPoints && sourceDivider > 1) {
                            applyDivider(preCount + delta, sourceDivider) -
                                applyDivider(preCount, sourceDivider)
                        } else delta
                        // "Feed max1" cap: a feed-max-one source contributes at most
                        // 1 point per day to Points targets (first activity of the
                        // day only); secondary-slot feeds stay uncapped.
                        val feedDelta =
                            if (targetKey == linkedName && habitName in s.conditionalFeedMaxOneHabits) {
                                conditionalCappedFeedAmount(preCount, baseFeedAmount)
                            } else baseFeedAmount
                        if (feedDelta == 0) continue
                        val existing = linkedEntries[dateStr] ?: 0
                        val newVal = if (targetKey == linkedName && linkedName in s.maxOneHabits) {
                            1
                        } else {
                            existing + feedDelta
                        }
                        if (newVal != existing) {
                            linkedEntries[dateStr] = newVal
                            dbChanged = true
                        }
                    }
                    mutableDb[targetKey] = linkedEntries.toSortedMap()
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

            // Record timestamps only for NEW activity — one per new game
            // played today, each at its actual end time (todayNewTimes
            // already carries the per-game times), never one per minute.
            if (todayNewTimes.isNotEmpty()) {
                val today = LocalDate.now()
                for ((habitName, times) in todayNewTimes) {
                    timestampRepo.addTimestampsAt(habitName, today, times)
                }
            }

            Log.d(TAG, "Chess.com data applied to habits")
        }
    }

    // ── GitHub Integration Methods ─────────────────────────────────────────────

    /** Saves GitHub global settings (enabled flag + optional token). */
    fun saveGithubSettings(enabled: Boolean, token: String) {
        viewModelScope.launch {
            val cleanToken = token.trim()
            settingsRepo.saveGithubSettings(enabled, cleanToken)
            _settings.value = _settings.value.copy(
                githubEnabled = enabled,
                githubToken = cleanToken
            )
            if (enabled && _settings.value.githubRepoUrls.isNotEmpty() && lastLoadedUri.isNotEmpty()) {
                startGithubPolling()
            } else {
                stopGithubPolling()
            }
        }
    }

    /**
     * Sets or clears the GitHub repo URL for a habit.
     *
     * When setting a URL, this automatically triggers a full backfill of the
     * repository's commit history into the habit's daily values. When clearing
     * (url is null/blank), the habit is unlinked from GitHub.
     */
    fun setGithubRepoUrl(habitName: String, url: String?) {
        viewModelScope.launch {
            val urls = _settings.value.githubRepoUrls.toMutableMap()
            val metrics = _settings.value.githubMetrics.toMutableMap()

            if (url.isNullOrBlank()) {
                urls.remove(habitName)
                metrics.remove(habitName)
            } else {
                urls[habitName] = url.trim()
                // Default metric is LINES_CHANGED if not already set
                if (habitName !in metrics) {
                    metrics[habitName] = GitHubMetric.LINES_CHANGED.name
                }
            }

            settingsRepo.saveGithubRepoUrls(urls)
            settingsRepo.saveGithubMetrics(metrics)
            _settings.value = _settings.value.copy(
                githubRepoUrls = urls,
                githubMetrics = metrics
            )

            // Auto-backfill when a URL is set
            if (!url.isNullOrBlank()) {
                // Ensure polling is running
                if (_settings.value.githubEnabled && githubPollingJob == null) {
                    startGithubPolling()
                }
                fetchGithubBacklog(habitName)
            }
        }
    }

    /** Sets the GitHub metric for a habit, then re-backfills to update values. */
    fun setGithubMetric(habitName: String, metric: GitHubMetric) {
        viewModelScope.launch {
            val metrics = _settings.value.githubMetrics.toMutableMap()
            metrics[habitName] = metric.name
            settingsRepo.saveGithubMetrics(metrics)
            _settings.value = _settings.value.copy(githubMetrics = metrics)

            // Re-backfill with the new metric
            if (habitName in _settings.value.githubRepoUrls) {
                fetchGithubBacklog(habitName)
            }
        }
    }

    /**
     * Fetches the entire commit history for a habit's linked repo and
     * retroactively fills the habit's daily values.
     *
     * Called automatically when a repo URL is set, or manually from the
     * edit panel's "Re-fetch" button.
     */
    fun fetchGithubBacklog(habitName: String) {
        val s = _settings.value
        val url = s.githubRepoUrls[habitName]
        if (url.isNullOrBlank()) {
            _githubSyncStatus.value = "No repo URL set for $habitName"
            return
        }
        if (s.fileUri.isEmpty()) {
            _githubSyncStatus.value = "Set habit database file first"
            return
        }

        val parsed = githubRepo.parseRepoUrl(url)
        if (parsed == null) {
            _githubSyncStatus.value = "Invalid GitHub URL: $url"
            return
        }

        val (owner, repo) = parsed
        val metric = GitHubMetric.fromKey(s.githubMetrics[habitName])
        val token = s.githubToken.takeIf { it.isNotBlank() }

        viewModelScope.launch {
            try {
                _githubSyncStatus.value = "Fetching $owner/$repo history…"

                var wasRateLimited = false

                // Fetch ALL four metrics in a single pass (same API calls as before)
                val backlog = githubRepo.fetchAllMetricsBacklog(
                    owner, repo, token,
                    onProgress = { done, total ->
                        _githubSyncStatus.value = "Fetching commits: $done / ~$total"
                    },
                    onRateLimited = { resetEpoch ->
                        wasRateLimited = true
                        val mins = ((resetEpoch - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(0)
                        _githubSyncStatus.value = "Rate limited by GitHub. Resets in ~${mins} min."
                    }
                )
                val allMetrics = backlog.dailyMetrics

                // Cache all four metrics for the graph
                _githubDailyCache = _githubDailyCache.toMutableMap().apply {
                    put(habitName, allMetrics)
                }

                // Cache the actual commit messages per day so the graph can
                // list them when the "Commits" metric is selected.
                if (backlog.commitMessages.isNotEmpty()) {
                    _githubCommitMessages = _githubCommitMessages.toMutableMap().apply {
                        put(habitName, backlog.commitMessages)
                    }
                    githubRepo.saveCommitMessagesCache(habitName, backlog.commitMessages)
                }

                // Persist the full metrics cache so all four value types survive
                // process restarts without a manual re-fetch.
                githubRepo.saveDailyMetricsCache(habitName, allMetrics)

                // Extract the selected metric for storing in the habits DB (value1)
                val dailyValues = allMetrics.mapValues { (_, m) ->
                    when (metric) {
                        GitHubMetric.LINES_CHANGED -> m.linesChanged
                        GitHubMetric.COMMITS -> m.commits
                        GitHubMetric.ADDITIONS -> m.additions
                        GitHubMetric.DELETIONS -> m.deletions
                    }
                }.filterValues { it != 0 }

                if (dailyValues.isNotEmpty()) {
                    // Snapshot the PRE-reset stored values: conditional feeds
                    // must be computed against what was really stored before
                    // the reset zeroes the habit, so a backlog re-fetch never
                    // re-feeds history into linked habits.
                    val beforeEntries = cachedPhoneDb[habitName] ?: emptyMap()
                    // Reset this habit's data before applying new data (authoritative source)
                    resetGithubHabitData(habitName, s)
                    _githubSyncStatus.value = "Applying backlog to $habitName…"
                    applyGithubData(habitName, dailyValues, _settings.value, beforeEntries)
                    _githubSyncStatus.value = "GitHub backlog complete: ${dailyValues.size} days for $habitName"
                } else if (allMetrics.isNotEmpty()) {
                    // All metrics data exists but the selected metric has no non-zero days
                    // (e.g. a day with only deletions when metric is ADDITIONS).
                    // Still cache and report success.
                    resetGithubHabitData(habitName, s)
                    _githubSyncStatus.value = "GitHub backlog complete: ${allMetrics.size} days for $habitName"
                } else {
                    // Don't wipe existing data — just report the issue
                    if (wasRateLimited) {
                        // Rate limit message already set by onRateLimited callback
                        Log.w(TAG, "GitHub backlog for $habitName was rate limited, keeping existing data")
                    } else {
                        _githubSyncStatus.value = "No commits found for $owner/$repo. " +
                            "Check the URL — if the repo is private, the token needs " +
                            "the 'repo' scope (classic) or Contents: Read-only (fine-grained)."
                    }
                }
            } catch (e: GitHubRateLimitException) {
                Log.e(TAG, "GitHub backlog rate limited for $habitName: ${e.message}")
                val mins = ((e.resetEpochSeconds - System.currentTimeMillis() / 1000) / 60).coerceAtLeast(0)
                _githubSyncStatus.value = "Rate limited by GitHub. Try again in ~${mins} min."
            } catch (e: GitHubApiException) {
                Log.e(TAG, "GitHub backlog API error for $habitName: HTTP ${e.statusCode}")
                _githubSyncStatus.value = when (e.statusCode) {
                    404 -> "Repo $owner/$repo not found. If it is PRIVATE, the token needs " +
                        "access: classic tokens require the 'repo' scope; fine-grained " +
                        "tokens need the repo selected + Contents: Read-only."
                    401 -> "GitHub rejected the token (401). Check that it is valid and not expired."
                    403 -> "GitHub denied access to $owner/$repo (403). The token may lack " +
                        "permission for this private repo."
                    else -> "GitHub API error ${e.statusCode} for $owner/$repo."
                }
            } catch (e: Exception) {
                Log.e(TAG, "GitHub backlog failed for $habitName: ${e.message}")
                _githubSyncStatus.value = "Backlog failed: ${e.message?.take(80)}"
            }
        }
    }

    /**
     * Resets a single GitHub-linked habit's entries to 0 for all dates.
     * Called before a full backlog re-fetch to ensure clean data.
     */
    private suspend fun resetGithubHabitData(habitName: String, s: AppSettings) {
        val phoneUriStr = s.fileUri
        if (phoneUriStr.isEmpty()) return
        if (!dbLoaded) {
            Log.w(TAG, "resetGithubHabitData: DB not loaded yet, refusing to persist (anti-wipe gate)")
            return
        }

        val mutableDb = cachedPhoneDb.toMutableMap()
        val entries = mutableDb[habitName] ?: return
        val resetEntries = entries.mapValues { 0 }.toSortedMap()
        if (resetEntries != entries) {
            mutableDb[habitName] = resetEntries
            cachedPhoneDb = mutableDb
            rebuildHabitList()
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(phoneUriStr), context, mutableDb)
            }
            Log.d(TAG, "GitHub habit '$habitName' reset to 0")
        }
    }

    /**
     * Applies GitHub daily values to a habit in the database.
     * GitHub data is authoritative — values are always overwritten (not max'd).
     */
    private suspend fun applyGithubData(
        habitName: String,
        dailyValues: Map<String, Int>,
        s: AppSettings,
        beforeEntries: Map<String, Int>? = null
    ) {
        if (dailyValues.isEmpty()) return

        val phoneUriStr = s.fileUri
        if (phoneUriStr.isEmpty()) return
        // ANTI-WIPE GATE
        if (!dbLoaded) {
            Log.w(TAG, "applyGithubData: DB not loaded yet, skipping persist (anti-wipe gate)")
            return
        }

        // Pick up concurrent on-disk writes (e.g. voice-capture increments)
        // before building the snapshot — see refreshCachedDbFromDisk.
        refreshCachedDbFromDisk(phoneUriStr)

        var mutableDb = cachedPhoneDb.toMutableMap()
        val habitEntries = (mutableDb[habitName] ?: emptyMap()).toMutableMap()
        // Pre-change values per date for conditional feeds. Callers that reset
        // the habit first (fetchGithubBacklog) pass the PRE-reset snapshot so a
        // re-fetch never re-feeds history into linked habits.
        val before = beforeEntries ?: habitEntries.toMap()
        var dbChanged = false
        val todayStr = dateString(LocalDate.now())

        for ((dateStr, value) in dailyValues) {
            val existing = habitEntries[dateStr] ?: 0
            if (value != existing) {
                habitEntries[dateStr] = value
                dbChanged = true
            }
        }
        mutableDb[habitName] = habitEntries.toSortedMap()

        // Conditional feeds: a GitHub-linked habit with the Conditional type
        // feeds its linked habits, mirroring applyGarminData (value-slot
        // resolution, feed-max-1 cap, feed-points divider deltas and max-one
        // targets included). Feeds fire only for POSITIVE day deltas — new
        // commits/lines raising a day's stored value. Downward corrections
        // never un-feed; run the conditional backfill on the linked habit to
        // true-up after a correction.
        val linkedTodayDeltas = mutableMapOf<String, Int>()
        if (habitName in s.conditionalHabits) {
            val linkedNames = s.conditionalLinkedHabits[habitName] ?: emptySet()
            val positiveDayDeltas = positiveSyncDayDeltas(before, dailyValues)
            if (linkedNames.isNotEmpty() && positiveDayDeltas.isNotEmpty()) {
                val feedMaxOne = habitName in s.conditionalFeedMaxOneHabits
                // "Feed points" sub-setting: feeds send the source's POINTS
                // delta (divider-applied) instead of the raw metric delta,
                // matching the manual increment path's step 2c semantics.
                val feedPoints = habitName in s.conditionalFeedPointsHabits
                val sourceDivider = s.habitDividers[habitName] ?: 1
                for (linkedName in linkedNames) {
                    val valueKey = effectiveConditionalLinkValueKey(
                        s.conditionalLinkValues,
                        s.secondaryValueHabits,
                        s.chessComHabitLinks,
                        habitName, linkedName
                    )
                    val targetKey = conditionalLinkStorageKey(linkedName, valueKey)
                    for ((date, storedBefore, delta) in positiveDayDeltas) {
                        val baseFeedAmount = if (feedPoints && sourceDivider > 1) {
                            applyDivider(storedBefore + delta, sourceDivider) -
                                applyDivider(storedBefore, sourceDivider)
                        } else delta
                        // Points slot: respect the feed-max-1 cap. Raw secondary
                        // slots (Value2/Value3) are never capped, like the manual path.
                        val feedAmount = if (targetKey == linkedName) {
                            conditionalSyncFeedAmount(storedBefore, baseFeedAmount, feedMaxOne)
                        } else baseFeedAmount
                        if (feedAmount == 0) continue

                        if (targetKey == linkedName) {
                            val linkedEntries = mutableDb[targetKey] ?: emptyMap()
                            val linkedRaw = (linkedEntries[date] ?: 0) + feedAmount
                            val linkedClamped = if (linkedName in s.maxOneHabits)
                                linkedRaw.coerceAtMost(1) else linkedRaw
                            // Max-one target already fed that day — nothing changes.
                            if (linkedClamped == (linkedEntries[date] ?: 0)) continue
                        }

                        mutableDb = habitsRepo.applyIncrementToDb(
                            mutableDb, targetKey, feedAmount, LocalDate.parse(date)
                        ).toMutableMap()
                        dbChanged = true
                        Log.d(TAG, "GitHub conditional feed: '$habitName' +$delta on $date → " +
                            "'$targetKey' +$feedAmount")
                        if (date == todayStr) {
                            linkedTodayDeltas[linkedName] =
                                (linkedTodayDeltas[linkedName] ?: 0) + feedAmount
                        }
                    }
                }
            }
        }

        if (dbChanged) {
            cachedPhoneDb = mutableDb
            rebuildHabitList()
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(phoneUriStr), context, mutableDb)
            }
            // Timestamps for linked habits fed by today's GitHub deltas
            if (linkedTodayDeltas.isNotEmpty()) {
                val now = HabitTimestampRepository.nowTime()
                val today = LocalDate.now()
                for ((linkedName, delta) in linkedTodayDeltas) {
                    timestampRepo.addTimestamps(linkedName, delta, today, now)
                }
            }
            Log.d(TAG, "GitHub data applied to '$habitName' (${dailyValues.size} days)")
        }
    }

    /** Starts the periodic GitHub polling loop. */
    private fun startGithubPolling() {
        githubPollingJob?.cancel()
        githubPollingJob = viewModelScope.launch {
            // Initial sync shortly after start
            delay(5_000)
            while (true) {
                syncGithubRecent()
                delay(GITHUB_POLL_INTERVAL_MS)
            }
        }
    }

    /** Stops the GitHub polling loop. */
    private fun stopGithubPolling() {
        githubPollingJob?.cancel()
        githubPollingJob = null
    }

    /**
     * Fetches recent commits for all GitHub-linked habits and applies new data.
     * Called periodically by the polling loop.
     */
    private suspend fun syncGithubRecent() {
        val s = _settings.value
        if (!s.githubEnabled || s.githubRepoUrls.isEmpty()) return
        if (s.fileUri.isEmpty()) return
        if (!dbLoaded) return

        val token = s.githubToken.takeIf { it.isNotBlank() }

        for ((habitName, url) in s.githubRepoUrls) {
            try {
                val parsed = githubRepo.parseRepoUrl(url) ?: continue
                val (owner, repo) = parsed
                val metric = GitHubMetric.fromKey(s.githubMetrics[habitName])

                val dailyValues = githubRepo.fetchRecent(owner, repo, metric, token)
                if (dailyValues.isNotEmpty()) {
                    applyGithubData(habitName, dailyValues, _settings.value)
                }

                // Refresh the per-day commit messages from the most recent
                // commits so the graph's commit listing stays current.
                try {
                    val recentMessages = githubRepo.fetchRecentCommitMessages(owner, repo, token)
                    if (recentMessages.isNotEmpty()) {
                        val merged = (_githubCommitMessages[habitName] ?: emptyMap()) + recentMessages
                        _githubCommitMessages = _githubCommitMessages.toMutableMap().apply {
                            put(habitName, merged)
                        }
                        githubRepo.saveCommitMessagesCache(habitName, merged)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "GitHub commit message sync failed for $habitName: ${e.message}")
                }
            } catch (e: GitHubApiException) {
                // Persistent auth/access failure — surface it instead of
                // failing silently every poll cycle.
                Log.w(TAG, "GitHub sync API error for $habitName: HTTP ${e.statusCode}")
                _githubSyncStatus.value = when (e.statusCode) {
                    404 -> "Repo not found for $habitName — private repos need a token " +
                        "with 'repo' scope (classic) or Contents: Read-only (fine-grained)."
                    401 -> "GitHub rejected the token (401) for $habitName."
                    else -> "GitHub denied access (${e.statusCode}) for $habitName."
                }
            } catch (e: Exception) {
                Log.w(TAG, "GitHub sync failed for $habitName: ${e.message}")
            }
        }
    }

    // ── Garmin Integration Methods ────────────────────────────────────────────

    /** Saves all Garmin settings at once (called from Settings screen). */
    fun saveGarminSettings(
        enabled: Boolean,
        proxyUrl: String,
        appToken: String,
        dateOfBirth: String
    ) {
        viewModelScope.launch {
            // Normalise the URL/token at the source so a stray trailing newline or
            // space (common when pasting) can never corrupt later URL parsing.
            val cleanProxyUrl = proxyUrl.trim().trimEnd('/')
            val cleanToken = appToken.trim()
            settingsRepo.saveGarminSettings(enabled, cleanProxyUrl, cleanToken, dateOfBirth)
            _settings.value = _settings.value.copy(
                garminEnabled = enabled,
                garminProxyUrl = cleanProxyUrl,
                garminAppToken = cleanToken,
                garminDateOfBirth = dateOfBirth
            )
            // Auto-derive bridge URL/token from the updated Garmin settings so
            // the bridge stays in sync without any manual configuration.
            val derivedBridgeUrl = deriveBridgeUrl(cleanProxyUrl)
            settingsRepo.saveBridgeSettings(
                _settings.value.bridgeEnabled,
                derivedBridgeUrl,
                cleanToken
            )
            _settings.value = _settings.value.copy(
                bridgeUrl = derivedBridgeUrl,
                bridgeToken = cleanToken
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
                // Reset all Garmin-linked habits to 0 for all dates so that
                // stale proxy values are cleared before re-applying.
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

                // Merge proxy data into the persistent cache WITHOUT clearing it.
                // This preserves historic data from JSON import (e.g. swim activities
                // from months ago) that the proxy may not have. Proxy values win
                // for dates they cover; all other cached dates are preserved.
                _garminSyncStatus.value = "Merging with cached data…"
                withContext(Dispatchers.IO) { garminRepo.mergeAndCacheDailyData(allData) }

                // Load the merged cache (proxy + historic import) and apply to habits.
                _garminSyncStatus.value = "Applying backlog data to habits…"
                val mergedData = withContext(Dispatchers.IO) { garminRepo.loadAllCachedData() }
                mergeIntoGarminMonthlyData(mergedData)
                val updatedSettings = autoLinkMissingGarminHabits(mergedData)
                applyGarminData(mergedData, updatedSettings)
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
        if (!dbLoaded) {
            Log.w(TAG, "resetGarminHabitData: DB not loaded yet, refusing to persist (anti-wipe gate)")
            return
        }

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
     *
     * On success, fetches the entire proxy backlog (whatever the laptop has cached)
     * and merges it into the displayed data, with laptop values winning for the dates
     * they cover. This makes "Test Connection" the authoritative "sync from laptop"
     * button — historic JSON data is preserved, but recent days are refreshed from
     * the proxy/fetch pipeline.
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

                    // After a successful test, fetch the full proxy backlog and merge it.
                    // This is the "sync from laptop" path: historic JSON data stays intact,
                    // but any dates the laptop has (recent days, corrected values) overwrite.
                    Log.d(TAG, "Garmin test ok: enabled=${s.garminEnabled}, " +
                        "links=${s.garminHabitLinks.size}, fileUriSet=${s.fileUri.isNotEmpty()}")
                    if (s.garminEnabled && s.garminHabitLinks.isNotEmpty() && s.fileUri.isNotEmpty()) {
                        syncGarminBacklog()
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
     * Fetches the entire proxy backlog (whatever the laptop has cached) and merges it
     * into the displayed data and cache, WITHOUT clearing the historic JSON data.
     *
     * This is the "sync from laptop" path used by "Test Connection". The laptop's
     * values win for the dates they cover; all other dates (deep historic past from
     * JSON) are preserved. This is distinct from `fetchGarminBacklog()` which does
     * a full clear+fetch from the proxy (useful when you want to discard everything
     * and re-fetch from Garmin's API).
     */
    private suspend fun syncGarminBacklog() {
        val s = _settings.value
        if (!s.garminEnabled || s.garminProxyUrl.isEmpty() || s.garminAppToken.isEmpty()) return
        if (s.garminHabitLinks.isEmpty()) return
        if (s.fileUri.isEmpty()) return

        try {
            _garminSyncStatus.value = "Fetching laptop data…"
            val allData = garminRepo.fetchEntireBacklog(
                s.garminProxyUrl,
                s.garminAppToken,
                s.garminDateOfBirth
            ) { done, total ->
                _garminSyncStatus.value = "Fetching: $done / $total months"
            }
            _garminSyncStatus.value = "Merging laptop data…"
            // Persist to cache so laptop data survives restarts.
            withContext(Dispatchers.IO) { garminRepo.mergeAndCacheDailyData(allData) }
            // Load the merged cache (proxy + historic import) and apply to habits.
            // This ensures imported data (e.g. swim activities from months ago) is
            // applied alongside the fresh proxy data.
            val mergedData = withContext(Dispatchers.IO) { garminRepo.loadAllCachedData() }
            mergeIntoGarminMonthlyData(mergedData)
            val syncSettings = autoLinkMissingGarminHabits(mergedData)
            applyGarminData(mergedData, syncSettings)
            _garminSyncStatus.value = "Sync complete! Laptop data merged."
        } catch (e: Exception) {
            Log.e(TAG, "Garmin backlog sync failed: ${e.message}")
            _garminSyncStatus.value = "Failed: ${e.message?.take(50)}"
        }
    }

    /**
     * Returns search keywords for matching a habit name to a GarminType.
     * Used by [autoLinkMissingGarminHabits] to auto-create missing links.
     */
    private fun garminTypeKeywords(type: GarminType): List<String> = when (type) {
        GarminType.RUN_MINUTES -> listOf("run", "jog")
        GarminType.BIKE_MINUTES -> listOf("bike", "cycl")
        GarminType.SWIM_MINUTES -> listOf("swim")
        GarminType.STEPS -> listOf("step")
        // "sleep score" is specific so it never steals a "Sleep Length" habit
        GarminType.SLEEP_SCORE -> listOf("sleep score", "sleep quality")
        GarminType.SLEEP_DURATION_MINUTES -> listOf("sleep length", "sleep duration", "sleep time")
        GarminType.HRV_LAST_NIGHT, GarminType.HRV_WEEKLY_AVG -> listOf("hrv")
        GarminType.RESTING_HR -> listOf("resting hr", "resting heart")
        GarminType.VO2_MAX -> listOf("vo2")
        GarminType.FITNESS_AGE -> listOf("fitness age")
        GarminType.FITNESS_AGE_DISTANCE -> listOf("fitness age distance")
        GarminType.ALTITUDE_ASCENT_METERS -> listOf("ascent", "altitude", "elevation", "climb")
        GarminType.DISTANCE_METERS -> listOf("distance")
        GarminType.CALORIES -> listOf("calorie")
        GarminType.ACTIVE_MINUTES -> listOf("active")
        GarminType.FLOORS_CLIMBED -> listOf("floor")
        GarminType.MIN_HR -> listOf("min hr")
        GarminType.MAX_HR -> listOf("max hr")
        GarminType.STRESS_LEVEL -> listOf("stress")
    }

    /**
     * Auto-links habits to Garmin types when data exists for a type but no habit
     * is linked to it.  Matches by keyword (e.g. a habit named "Garmin Swim" will
     * be auto-linked to SWIM_MINUTES).
     *
     * This repairs the common scenario where a Garmin habit was created but the
     * link was never saved (or was lost), causing [applyGarminData] to silently
     * skip that type.
     *
     * @return the updated [AppSettings] with any new links applied
     */
    private suspend fun autoLinkMissingGarminHabits(
        allData: Map<GarminType, Map<String, Int>>
    ): AppSettings {
        val currentLinks = _settings.value.garminHabitLinks
        val allHabitNames = getAllHabitNames()
        val linkedTypes = currentLinks.values.toSet()
        val linkedHabits = currentLinks.keys.toMutableSet()

        val newLinks = mutableMapOf<String, String>()

        for ((type, dayMap) in allData) {
            if (dayMap.isEmpty()) continue
            if (type.name in linkedTypes) continue  // Already linked to some habit

            val keywords = garminTypeKeywords(type)
            val match = allHabitNames.firstOrNull { habitName ->
                habitName !in linkedHabits &&
                habitName !in newLinks &&
                keywords.any { kw -> habitName.lowercase().contains(kw) }
            }

            if (match != null) {
                newLinks[match] = type.name
                linkedHabits.add(match)
                Log.i(TAG, "Auto-linked habit '$match' → ${type.name}")
            }
        }

        if (newLinks.isEmpty()) return _settings.value

        val updatedLinks = currentLinks + newLinks
        settingsRepo.saveGarminHabitLinks(updatedLinks)
        val updatedSettings = _settings.value.copy(garminHabitLinks = updatedLinks)
        _settings.value = updatedSettings
        Log.i(TAG, "Auto-linked ${newLinks.size} Garmin habit(s): $newLinks")

        // Clear stale entries for newly linked habits so that only Garmin-derived
        // data remains after applyGarminData runs.  Without this, old manual
        // entries (e.g. a bogus value-1 on recent days) would survive and produce
        // incorrect streak/antistreak values.
        if (dbLoaded && updatedSettings.fileUri.isNotEmpty()) {
            val mutableDb = cachedPhoneDb.toMutableMap()
            var dbChanged = false
            for (habitName in newLinks.keys) {
                val existing = mutableDb[habitName]
                if (existing != null && existing.isNotEmpty()) {
                    Log.i(TAG, "Cleared ${existing.size} stale entries " +
                        "for newly linked habit '$habitName'")
                    mutableDb[habitName] = mutableMapOf()
                    dbChanged = true
                }
            }
            if (dbChanged) {
                cachedPhoneDb = mutableDb
                withContext(Dispatchers.IO) {
                    habitsRepo.persistDatabase(
                        Uri.parse(updatedSettings.fileUri), context, mutableDb
                    )
                }
            }
        }

        return updatedSettings
    }

    /**
     * Applies Garmin data to linked habits in the database.
     * For each linked habit, computes increments and applies them.
     */
    private suspend fun applyGarminData(
        allData: Map<GarminType, Map<String, Int>>,
        settings: AppSettings
    ) {
        val linkedHabits = settings.garminHabitLinks
        if (linkedHabits.isEmpty()) return
        if (!dbLoaded) {
            Log.w(TAG, "applyGarminData: DB not loaded yet, skipping persist (anti-wipe gate)")
            return
        }

        // Pick up concurrent on-disk writes (e.g. voice-capture increments)
        // before building the snapshot — see refreshCachedDbFromDisk.
        refreshCachedDbFromDisk(settings.fileUri)

        // Diagnostic: log what data is available and what habits are linked
        Log.d(TAG, "applyGarminData: allData types=${allData.keys.map { it.name }}, " +
            "linkedHabits=$linkedHabits")
        for ((type, dayMap) in allData) {
            if (dayMap.isNotEmpty()) {
                val sortedDates = dayMap.keys.sorted()
                Log.d(TAG, "applyGarminData: ${type.name} has ${dayMap.size} entries " +
                    "(${sortedDates.first()}..${sortedDates.last()})")
            }
        }

        var mutableDb = cachedPhoneDb.toMutableMap()
        var dbChanged = false
        val todayDeltas = mutableMapOf<String, Int>()
        // Today's conditional-feed deltas per linked habit (for timestamps)
        val linkedTodayDeltas = mutableMapOf<String, Int>()

        for ((habitName, garminTypeStr) in linkedHabits) {
            val garminType = GarminType.fromKey(garminTypeStr) ?: continue
            
            // For FITNESS_AGE_DISTANCE, calculate it on-demand from FITNESS_AGE
            // This is a derived metric: distance = fitness_age - biological_age
            // Fitness age is stored as hundredths of a year (e.g., 3704 for 37.04)
            val dailyValues = if (garminType == GarminType.FITNESS_AGE_DISTANCE) {
                try {
                    val fitnessAgeData = allData[GarminType.FITNESS_AGE] ?: emptyMap()
                    if (fitnessAgeData.isEmpty()) {
                        Log.w(TAG, "No fitness age data available to calculate fitness age distance")
                        emptyMap()
                    } else if (settings.garminDateOfBirth.isEmpty()) {
                        Log.w(TAG, "Date of birth not set - cannot calculate fitness age distance")
                        emptyMap()
                    } else {
                        val dob = LocalDate.parse(settings.garminDateOfBirth)
                        val distanceData = mutableMapOf<String, Int>()
                        
                        for ((dateStr, fitnessAge) in fitnessAgeData) {
                            val metricDate = LocalDate.parse(dateStr)
                            // Calculate biological age in hundredths of a year
                            val biologicalAgeYears = ChronoUnit.YEARS.between(dob, metricDate).toDouble()
                            val biologicalAgeHundredths = (biologicalAgeYears * 100).toInt()
                            // Distance = fitness_age - biological_age (both in hundredths of a year)
                            // Negative means younger fitness age than biological age (good)
                            // Positive means older fitness age than biological age (bad)
                            distanceData[dateStr] = fitnessAge - biologicalAgeHundredths
                        }
                        
                        Log.d(TAG, "Calculated ${distanceData.size} fitness age distance values from ${fitnessAgeData.size} fitness age entries (DOB: $dob)")
                        distanceData.toMap()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to calculate fitness age distance: ${e.message}", e)
                    emptyMap()
                }
            } else {
                val dataForType = allData[garminType]
                if (dataForType == null) {
                    Log.w(TAG, "applyGarminData: SKIP habit '$habitName' — " +
                        "no ${garminType.name} data in allData " +
                        "(available: ${allData.keys.map { it.name }})")
                    continue
                }
                dataForType
            }

            if (dailyValues.isEmpty()) {
                Log.w(TAG, "applyGarminData: SKIP habit '$habitName' — " +
                    "${garminType.name} data map is empty")
                continue
            }

            Log.d(TAG, "Processing habit '$habitName' linked to $garminTypeStr, values=${dailyValues.size}")

            // Ensure habit exists in DB
            if (habitName !in mutableDb) {
                mutableDb[habitName] = mutableMapOf()
            }

            // Custom point ranges (if enabled for this habit) map the raw Garmin
            // value directly to a points tier; otherwise we always count as 1 point.
            val useCustomRanges = habitName in settings.customPointRangesHabits
            val customRanges = settings.customPointRanges[habitName]

            val habitData = mutableDb[habitName]!!.toMutableMap()
            var appliedCount = 0
            // Positive per-day changes of this habit: (date, storedBefore, delta)
            val positiveDayDeltas = mutableListOf<Triple<String, Int, Int>>()
            for ((date, value) in dailyValues) {
                // Compute the points for this date DETERMINISTICALLY from the current
                // (read-only) Garmin value. We always write the computed result —
                // including 0 — so that a corrected value from the laptop proxy/fetch
                // pipeline flips the point both UP and DOWN.
                val newValue: Int = if (useCustomRanges && customRanges != null) {
                    com.example.tail.data.calculatePointsFromRanges(value, customRanges)
                } else {
                    // Always accept Garmin data - count as 1 point if data exists
                    1
                }

                val existing = habitData[date] ?: 0
                if (newValue != existing) {
                    habitData[date] = newValue
                    dbChanged = true
                    appliedCount++
                    val delta = newValue - existing

                    // Track delta for today (for timestamp recording)
                    val today = LocalDate.now().toString()
                    if (date == today && delta > 0) {
                        todayDeltas[habitName] = (todayDeltas[habitName] ?: 0) + delta
                    }
                    // Remember positive day deltas (with the pre-change stored
                    // value) so conditional feeds can mirror them onto linked
                    // habits after this habit's values are written.
                    if (delta > 0) {
                        positiveDayDeltas.add(Triple(date, existing, delta))
                    }
                }
            }
            Log.d(TAG, "Applied $appliedCount values for habit '$habitName'")
            mutableDb[habitName] = habitData

            // Conditional feeds: a Garmin-linked habit with the Conditional
            // type feeds its linked habits, mirroring Step 2c of the manual
            // incrementHabit path (value-slot resolution, feed-max-1 cap and
            // max-one targets included). Feeds fire only for POSITIVE day
            // deltas — a new or raised Garmin value. Downward corrections
            // never un-feed; run the conditional backfill on the linked habit
            // to true-up after a correction.
            if (habitName in settings.conditionalHabits && positiveDayDeltas.isNotEmpty()) {
                val linkedNames = settings.conditionalLinkedHabits[habitName] ?: emptySet()
                val feedMaxOne = habitName in settings.conditionalFeedMaxOneHabits
                val todayStr = LocalDate.now().toString()
                for (linkedName in linkedNames) {
                    val valueKey = effectiveConditionalLinkValueKey(
                        settings.conditionalLinkValues,
                        settings.secondaryValueHabits,
                        settings.chessComHabitLinks,
                        habitName, linkedName
                    )
                    val targetKey = conditionalLinkStorageKey(linkedName, valueKey)
                    for ((date, storedBefore, delta) in positiveDayDeltas) {
                        // Points slot: respect the feed-max-1 cap. Raw secondary
                        // slots (Value2/Value3) are never capped, like the manual path.
                        val feedAmount = if (targetKey == linkedName) {
                            conditionalSyncFeedAmount(storedBefore, delta, feedMaxOne)
                        } else delta
                        if (feedAmount == 0) continue

                        if (targetKey == linkedName) {
                            val linkedEntries = mutableDb[targetKey] ?: emptyMap()
                            val linkedRaw = (linkedEntries[date] ?: 0) + feedAmount
                            val linkedClamped = if (linkedName in settings.maxOneHabits)
                                linkedRaw.coerceAtMost(1) else linkedRaw
                            // Max-one target already fed today — nothing changes.
                            if (linkedClamped == (linkedEntries[date] ?: 0)) continue
                        }

                        mutableDb = habitsRepo.applyIncrementToDb(
                            mutableDb, targetKey, feedAmount, LocalDate.parse(date)
                        ).toMutableMap()
                        dbChanged = true
                        Log.d(TAG, "Conditional feed: '$habitName' +$delta on $date → " +
                            "'$targetKey' +$feedAmount")
                        if (date == todayStr) {
                            linkedTodayDeltas[linkedName] =
                                (linkedTodayDeltas[linkedName] ?: 0) + feedAmount
                        }
                    }
                }
            }
        }

        if (dbChanged) {
            cachedPhoneDb = mutableDb
            rebuildHabitList()

            // Persist to disk
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(settings.fileUri), context, mutableDb)
            }

            // Record timestamps only for the NEW increments (delta), not the
            // total count. For activity-based types (run/bike/swim) prefer
            // the watch-recorded activity start time so the schedule screen
            // shows WHEN the activity happened, not when the sync ran.
            if (todayDeltas.isNotEmpty()) {
                val now = HabitTimestampRepository.nowTime()
                val today = LocalDate.now()
                for ((habitName, delta) in todayDeltas) {
                    val garminType = linkedHabits[habitName]?.let { GarminType.fromKey(it) }
                    val activityTime = garminType?.let {
                        garminRepo.activityStartTime(it, today.toString())
                    }
                    timestampRepo.addTimestamps(habitName, delta, today, activityTime ?: now)
                }
            }

            // Timestamps for linked habits fed by today's Garmin deltas
            if (linkedTodayDeltas.isNotEmpty()) {
                val now = HabitTimestampRepository.nowTime()
                val today = LocalDate.now()
                for ((linkedName, delta) in linkedTodayDeltas) {
                    timestampRepo.addTimestamps(linkedName, delta, today, now)
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
                            val importSettings = autoLinkMissingGarminHabits(allData)
                            applyGarminData(allData, importSettings)
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

    // ── Tail Bridge Methods (Movies + future tethered features) ───────────────

    /**
     * Derives the Tail Bridge URL from the Garmin proxy URL.
     *
     * Both services run on the same PC: Garmin proxy on port 8000, the bridge
     * on port 8001. They share the same auth token (ANDROID_PROXY_KEY).
     * This means the user only needs to configure the Garmin connection once —
     * the bridge connection info is auto-derived, no manual setup required.
     */
    private fun deriveBridgeUrl(garminProxyUrl: String): String {
        if (garminProxyUrl.isBlank()) return ""
        return try {
            val clean = garminProxyUrl.trim().trimEnd('/')
            val uri = java.net.URI(clean)
            val scheme = uri.scheme ?: "http"
            val host = uri.host ?: return ""
            "$scheme://$host:$BRIDGE_PORT"
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Returns the auto-derived bridge (url, token) pair from Garmin settings,
     * or null if Garmin isn't configured yet.
     */
    private fun getBridgeConnection(): Pair<String, String>? {
        val s = _settings.value
        val bridgeUrl = deriveBridgeUrl(s.garminProxyUrl)
        val bridgeToken = s.garminAppToken
        if (bridgeUrl.isEmpty() || bridgeToken.isEmpty()) return null
        return bridgeUrl to bridgeToken
    }

    /** Saves bridge enabled state; URL and token are auto-derived from Garmin settings. */
    fun saveBridgeSettings(enabled: Boolean) {
        viewModelScope.launch {
            val s = _settings.value
            val derivedUrl = deriveBridgeUrl(s.garminProxyUrl)
            val derivedToken = s.garminAppToken
            settingsRepo.saveBridgeSettings(enabled, derivedUrl, derivedToken)
            _settings.value = _settings.value.copy(
                bridgeEnabled = enabled,
                bridgeUrl = derivedUrl,
                bridgeToken = derivedToken
            )
        }
    }

    // ── Points Wallpaper ─────────────────────────────────────────────────

    private val _wallpaperStatus = MutableStateFlow("")

    /** Human-readable result of the most recent manual wallpaper apply. */
    val wallpaperStatus: StateFlow<String> = _wallpaperStatus

    /** Updates the wallpaper status text (set by the Settings section). */
    fun setWallpaperStatus(status: String) {
        _wallpaperStatus.value = status
    }

    /**
     * Saves the points-driven wallpaper settings. Every parameter is
     * optional — unspecified values keep their current state.
     */
    fun saveWallpaperSettings(
        enabled: Boolean? = null,
        dirUri: String? = null,
        target: WallpaperTarget? = null,
        metric: WallpaperMetric? = null
    ) {
        viewModelScope.launch {
            val cur = _settings.value
            val newEnabled = enabled ?: cur.wallpaperEnabled
            val newDir = dirUri ?: cur.wallpaperDirUri
            val newTarget = target ?: cur.wallpaperTarget
            val newMetric = metric ?: cur.wallpaperMetric
            settingsRepo.saveWallpaperSettings(
                newEnabled, newDir, newTarget.name, newMetric.name
            )
            _settings.value = _settings.value.copy(
                wallpaperEnabled = newEnabled,
                wallpaperDirUri = newDir,
                wallpaperTarget = newTarget,
                wallpaperMetric = newMetric
            )
        }
    }

    /** Toggles whether [habitName] is linked to the movie bridge. */
    fun toggleBridgeMovieHabit(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.bridgeMovieHabits.toMutableSet()
            if (habitName in current) current.remove(habitName) else current.add(habitName)
            settingsRepo.saveBridgeMovieHabits(current)
            // Movie habits track runtime minutes in the `minutes:` slot —
            // enabling the type turns minutes ON.
            var minutes = _settings.value.minutesEnabledHabits
            if (habitName in current &&
                habitName !in minutes &&
                habitName !in _settings.value.maxOneHabits
            ) {
                minutes = minutes + habitName
                settingsRepo.saveMinutesEnabledHabits(minutes)
            }
            _settings.value = _settings.value.copy(
                bridgeMovieHabits = current,
                minutesEnabledHabits = minutes
            )
        }
    }

    /**
     * Fetches the latest movie suggestion from the desktop bridge.
     * Called when a movie-linked text-input habit is tapped.
     *
     * @param excludeTitles Titles to skip (e.g. entries already logged today)
     * @param onResult Called with the suggested movie (or null if bridge is
     *                 unreachable / no data). Runs on the main thread.
     */
    fun fetchMovieSuggestion(
        excludeTitles: List<String> = emptyList(),
        onResult: (BridgeMovie?) -> Unit
    ) {
        val s = _settings.value
        if (!s.bridgeEnabled) {
            onResult(null)
            return
        }
        val conn = getBridgeConnection()
        if (conn == null) {
            onResult(null)
            return
        }
        viewModelScope.launch {
            val movie = try {
                movieBridgeService.fetchLatestSuggestion(conn.first, conn.second, excludeTitles)
            } catch (e: Exception) {
                Log.w(TAG, "Movie suggestion fetch failed: ${e.message}")
                null
            }
            _movieSuggestion.value = movie
            onResult(movie)
        }
    }

    /** Clears the current movie suggestion (call when the dialog is dismissed). */
    fun clearMovieSuggestion() {
        _movieSuggestion.value = null
    }

    // ── Movie confirmation flash (auto-prompt on app open) ────────────────────

    /** Max age (ms) of a desktop-detected movie before we stop asking about it. */
    private val moviePromptMaxAgeMs = 48 * 60 * 60 * 1000L

    /**
     * Stable marker identifying a prompted movie ("title@watchDate"). Keyed on
     * the watch DAY (not lastWatched) because the desktop watcher can merge or
     * extend sessions of the same play, which shifts lastWatched — the day
     * never changes, so an answered movie is never re-asked.
     */
    private fun moviePromptMarker(movie: BridgeMovie): String {
        val day = movie.date.ifBlank { movie.lastWatched.take(10) }
        return "${movie.title.trim().lowercase()}@$day"
    }

    /**
     * Case/whitespace-insensitive title match against logged entry texts.
     * Compares parsed cache keys so entries carrying a "(N min)" length
     * annotation still match the bare suggested title.
     */
    private fun titleLogged(title: String, entries: List<Pair<String, String>>): Boolean {
        val needle = OmdbService.parseTitle(title).cacheKey
        return entries.any { OmdbService.parseTitle(it.second).cacheKey == needle }
    }

    /** True when the movie's most recent session started within the prompt window. */
    private fun isMoviePromptRecent(movie: BridgeMovie): Boolean {
        val lastStartUnix = movie.sessions.maxOfOrNull { it.startUnix }?.takeIf { it > 0 }
        if (lastStartUnix != null) {
            val ageMs = System.currentTimeMillis() - lastStartUnix * 1000L
            return ageMs in 0..moviePromptMaxAgeMs
        }
        val parsed = try {
            java.time.LocalDateTime.parse(
                movie.lastWatched,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            )
        } catch (e: Exception) { null }
        return parsed?.let {
            java.time.Duration.between(it, java.time.LocalDateTime.now()).toMillis()
                .let { age -> age in 0..moviePromptMaxAgeMs }
        } ?: true // unparseable → assume recent so the user still gets asked once
    }

    /**
     * Looks for an unconfirmed recently-watched desktop movie to ask about in
     * the bottom flash when the app opens. Skips titles already logged today
     * for [habitName], prompts already handled (answered Yes/No or timed out)
     * and watches older than the prompt window.
     *
     * @param onResult Called with the movie to ask about, or null when there
     *                 is nothing to confirm. Runs on the main thread.
     */
    fun prepareMoviePrompt(
        habitName: String,
        date: LocalDate,
        onResult: (BridgeMovie?) -> Unit
    ) {
        if (!_settings.value.bridgeEnabled) {
            onResult(null)
            return
        }
        loadTextEntriesWithTimestamps(habitName, date) { todayEntries ->
            val excludeTitles = todayEntries.map { it.second }
            fetchMovieSuggestion(excludeTitles) { movie ->
                if (movie == null || titleLogged(movie.title, todayEntries)) {
                    // Bridge unreachable / no data, or the suggest endpoint fell
                    // back to a title that is already logged today — nothing to ask.
                    onResult(null)
                    return@fetchMovieSuggestion
                }
                // The suggestion may be for a movie played on an earlier day;
                // skip it when that exact title was already logged on its own
                // watch day (the day the flash is asking about).
                val watchDate = com.example.tail.data.parseDate(movie.date)
                if (watchDate != null && watchDate != date) {
                    loadTextEntriesWithTimestamps(habitName, watchDate) { watchDayEntries ->
                        if (titleLogged(movie.title, watchDayEntries)) {
                            onResult(null)
                        } else {
                            finishMoviePromptCheck(habitName, movie, onResult)
                        }
                    }
                } else {
                    finishMoviePromptCheck(habitName, movie, onResult)
                }
            }
        }
    }

    /** Final gate: already-handled marker and recency window. */
    private fun finishMoviePromptCheck(
        habitName: String,
        movie: BridgeMovie,
        onResult: (BridgeMovie?) -> Unit
    ) {
        viewModelScope.launch {
            val handled = try {
                settingsRepo.getMoviePromptHandled()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read movie prompt markers: ${e.message}")
                emptySet()
            }
            if (moviePromptMarker(movie) in handled || !isMoviePromptRecent(movie)) {
                onResult(null)
            } else {
                // Register the ask in the notification system (in-app center +
                // system notification) BEFORE showing the flash, so an
                // unanswered flash is preserved as a pending notification.
                registerMovieAsk(habitName, movie)
                onResult(movie)
            }
        }
    }

    /**
     * Adds the movie ask to the [NotificationStore] and posts the matching
     * system notification. Idempotent — an ask with the same id is not
     * duplicated.
     */
    private suspend fun registerMovieAsk(habitName: String, movie: BridgeMovie) {
        try {
            val entryTime = moviePromptEntryTime(movie)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
            val ask = HabitNotification(
                id = com.example.tail.notify.HabitAsks.movieAskId(moviePromptMarker(movie)),
                habitName = habitName,
                type = HabitNotification.TYPE_MOVIE,
                title = movie.title,
                question = "Watched this?",
                createdAtMillis = System.currentTimeMillis(),
                // "HH:mm:ss|<minutes>" — the length lets the answer path
                // annotate the entry so the minutes slot fills automatically.
                payload = HabitNotification.moviePayload(entryTime, movie.totalWatchMin ?: 0)
            )
            notificationStore.add(ask)
            com.example.tail.notify.HabitNotifier.postAsk(context, ask)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register movie ask: ${e.message}")
        }
    }

    /**
     * The text logged for a confirmed movie: the title plus its "(N min)"
     * watch length when the bridge knows it, so the schedule block sizes to
     * the film and the minutes slot fills from the annotation.
     */
    private fun annotatedMovieTitle(movie: BridgeMovie): String =
        movie.totalWatchMin?.takeIf { it > 0 }?.let { "${movie.title} ($it min)" }
            ?: movie.title

    /**
     * Logs [movie] as a watched entry for [habitName] — the flash's Yes action
     * and the auto-confirm timeout. The entry is timestamped at the movie's
     * last session start when that was today, otherwise at the current time.
     * Marks the prompt as handled so it is never asked again.
     *
     * @param onLogged Called with the "HH:mm:ss" entry time used (main thread).
     */
    fun confirmMoviePrompt(
        habitName: String,
        movie: BridgeMovie,
        onLogged: (String) -> Unit = {}
    ) {
        markMoviePromptHandled(movie)
        val entryTime = moviePromptEntryTime(movie)
        saveTextEntries(habitName, listOf(annotatedMovieTitle(movie)), null, entryTime)
        onLogged(entryTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
    }

    /** Dismisses the movie prompt without logging anything (the No action). */
    fun dismissMoviePrompt(movie: BridgeMovie) {
        markMoviePromptHandled(movie)
    }

    /** Entry time for a confirmed movie: its last start time today, else now. */
    private fun moviePromptEntryTime(movie: BridgeMovie): java.time.LocalTime {
        val parsed = try {
            java.time.LocalDateTime.parse(
                movie.lastWatched,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            )
        } catch (e: Exception) { null }
        return if (parsed != null && parsed.toLocalDate() == java.time.LocalDate.now()) {
            parsed.toLocalTime()
        } else {
            java.time.LocalTime.now()
        }
    }

    /** Persists the handled marker so the same movie is never re-asked. */
    private fun markMoviePromptHandled(movie: BridgeMovie) {
        viewModelScope.launch {
            try {
                val current = settingsRepo.getMoviePromptHandled()
                settingsRepo.saveMoviePromptHandled(current + moviePromptMarker(movie))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save movie prompt marker: ${e.message}")
            }
        }
    }

    /** Persists a raw handled marker (used when answering from a stored ask). */
    private fun markMovieMarkerHandled(marker: String) {
        viewModelScope.launch {
            try {
                val current = settingsRepo.getMoviePromptHandled()
                settingsRepo.saveMoviePromptHandled(current + marker)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save movie prompt marker: ${e.message}")
            }
        }
    }

    // ── Habit-ask notification answers (in-app surfaces) ────────────────────

    /**
     * Answers a pending ask from an in-app surface (bottom flash or the
     * notification center). Applies the effect, removes the record from the
     * store and cancels the system notification — the answer takes effect
     * everywhere at once.
     *
     * @param onEntryLogged For a movie answered Yes: the "HH:mm:ss" entry time
     *                      used, so the caller can show the increment toast.
     */
    fun answerNotification(
        ask: HabitNotification,
        yes: Boolean,
        onEntryLogged: (String?) -> Unit = {}
    ) {
        viewModelScope.launch {
            try {
                if (ask.type == HabitNotification.TYPE_MOVIE) {
                    markMovieMarkerHandled(ask.id.removePrefix("movie:"))
                    if (yes) {
                        val (payloadTime, payloadMinutes) =
                            HabitNotification.parseMoviePayload(ask.payload)
                        val time = payloadTime?.let {
                            try {
                                java.time.LocalTime.parse(it)
                            } catch (e: Exception) {
                                java.time.LocalTime.now()
                            }
                        } ?: java.time.LocalTime.now()
                        val text = if (payloadMinutes > 0) {
                            "${ask.title} ($payloadMinutes min)"
                        } else {
                            ask.title
                        }
                        saveTextEntries(ask.habitName, listOf(text), null, time)
                        onEntryLogged(time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
                    } else {
                        onEntryLogged(null)
                    }
                } else if (yes && ask.type == HabitNotification.TYPE_SCHEDULE) {
                    // The ask is about TODAY — never the date the user happens
                    // to be viewing. Matches the system-notification answer
                    // path (HabitAsks.applyAnswer → HabitsRepository.incrementHabit).
                    // TYPE_INFO asks carry no effect — acknowledging one only
                    // removes it everywhere (the else branch below).
                    incrementHabit(ask.habitName, date = LocalDate.now())
                    onEntryLogged(null)
                } else {
                    onEntryLogged(null)
                }
                notificationStore.remove(ask.id)
                com.example.tail.notify.HabitNotifier.cancelAsk(context, ask.id)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to answer notification '${ask.id}': ${e.message}")
            }
        }
    }

    /**
     * Returns the oldest ask whose one-time flash has not been shown yet and
     * marks it as flashed, so each ask flashes exactly once (on the first app
     * open after it was created).
     */
    fun consumeUnseenAskForFlash(onAsk: (HabitNotification?) -> Unit) {
        viewModelScope.launch {
            try {
                val unseen = notificationStore.notificationsFlow.first()
                    // Info notices never flash — they wait in the bell center
                    // until acknowledged (no Yes/No semantics to flash with).
                    .firstOrNull { !it.flashShown && it.type != HabitNotification.TYPE_INFO }
                if (unseen != null) {
                    notificationStore.markFlashShown(unseen.id)
                }
                onAsk(unseen)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to consume unseen ask: ${e.message}")
                onAsk(null)
            }
        }
    }

    /**
     * Sets (or removes with null) the daily "HH:mm" ask time for a habit and
     * keeps the alarm in sync.
     */
    fun setHabitScheduleTime(habitName: String, time: String?) {
        viewModelScope.launch {
            val current = _settings.value.habitScheduleTimes.toMutableMap()
            if (time == null) current.remove(habitName) else current[habitName] = time
            settingsRepo.saveHabitScheduleTimes(current)
            _settings.value = _settings.value.copy(habitScheduleTimes = current)
            if (time == null) {
                com.example.tail.notify.HabitAlarmReceiver.cancel(context, habitName)
            } else {
                com.example.tail.notify.HabitAlarmReceiver.schedule(context, habitName, time)
            }
        }
    }

    /** Tests the bridge connection. */
    fun testBridgeConnection() {
        val conn = getBridgeConnection()
        if (conn == null) {
            _bridgeStatus.value = "Configure Garmin connection first (same server, port $BRIDGE_PORT)"
            return
        }
        viewModelScope.launch {
            _bridgeStatus.value = "Testing connection…"
            val ok = try {
                movieBridgeService.testConnection(conn.first, conn.second)
            } catch (e: Exception) {
                false
            }
            _bridgeStatus.value = if (ok) "✓ Bridge connected!" else "✗ Connection failed"
        }
    }

    // ── OMDb / IMDb ratings methods ──────────────────────────────────────────

    /** Saves the OMDb API key. */
    fun saveOmdbApiKey(apiKey: String) {
        viewModelScope.launch {
            settingsRepo.saveOmdbApiKey(apiKey.trim())
            _settings.value = _settings.value.copy(omdbApiKey = apiKey.trim())
        }
    }

    /** Returns true if [habitName] is a movie-bridge-linked text-input habit. */
    fun isMovieBridgeHabit(habitName: String): Boolean {
        val s = _settings.value
        return habitName in s.bridgeMovieHabits && s.bridgeEnabled
    }

    /**
     * Returns true if IMDb ratings are available for [habitName]:
     * the habit is bridge-linked AND an OMDb API key is configured.
     */
    fun hasImdbRatings(habitName: String): Boolean {
        return isMovieBridgeHabit(habitName) && _settings.value.omdbApiKey.isNotBlank()
    }

    /**
     * Looks up the cached IMDb rating for a raw text entry.
     * Returns the rating as a display string (e.g. "8.8") or null if not cached.
     */
    suspend fun getImdbRatingForText(rawText: String): String? {
        val parsed = OmdbService.parseTitle(rawText)
        val rating = imdbCache.getRating(parsed.cacheKey) ?: return null
        if (rating <= 0) return null
        return String.format("%.1f", rating / 10.0)
    }

    /**
     * Fetches the IMDb rating for a single title from OMDb (or returns the
     * cached value). Does NOT exceed the daily API limit. The runtime from the
     * same OMDb response is cached alongside the rating.
     *
     * Uses the OmdbService lookup ladder (exact title → fuzzy IMDb-ID
     * resolution → ID lookup). Only definitive results are cached: transient
     * failures (network, rate limit) are left uncached so they retry later.
     *
     * @param needRuntime When true, a title whose rating is already cached is
     *        still re-fetched if its runtime isn't cached yet (the runtime
     *        lives in the same response but was not stored by older versions).
     * @return The rating x 10, or null if not found / API unavailable.
     */
    private suspend fun fetchAndCacheImdbRating(parsed: ParsedTitle, needRuntime: Boolean = false): Int? {
        if (imdbCache.hasBeenLookedUp(parsed.cacheKey)) {
            if (!needRuntime || imdbCache.hasRuntime(parsed.cacheKey)) {
                return imdbCache.getRating(parsed.cacheKey)?.takeIf { it > 0 }
            }
        }

        val apiKey = _settings.value.omdbApiKey
        if (apiKey.isBlank()) return null

        if (imdbCache.remainingCalls() <= 0) {
            Log.w(TAG, "OMDb daily limit reached, skipping '${parsed.title}'")
            return null
        }

        val resolvedId = imdbCache.getResolvedId(parsed.idCacheKey)
        val outcome = omdbService.fetchRating(parsed, apiKey, resolvedId)
        imdbCache.incrementCallCount(outcome.callsUsed)

        when (outcome) {
            is OmdbOutcome.Found -> {
                // Cache the resolved IMDb ID so future lookups skip fuzzy resolution
                outcome.resolvedId?.let { imdbCache.putResolvedId(parsed.idCacheKey, it) }
                imdbCache.putRating(parsed.cacheKey, outcome.rating)
                imdbCache.putRuntime(parsed.cacheKey, outcome.runtimeMin)
                return outcome.rating?.takeIf { it > 0 }
            }
            is OmdbOutcome.NotFound -> {
                // Definitively not on IMDb — safe to negative-cache
                imdbCache.putRating(parsed.cacheKey, null)
                imdbCache.putRuntime(parsed.cacheKey, null)
                return null
            }
            is OmdbOutcome.Transient -> {
                // Network/rate-limit error — do NOT cache; retried next time
                Log.w(TAG, "OMDb transient failure for '${parsed.title}': ${outcome.message}")
                return null
            }
        }
    }

    /**
     * Recomputes the daily average IMDb rating for a movie habit and stores
     * it as a secondary value (x 10) in the habits database.
     */
    private suspend fun updateImdbSecondaryValues(habitName: String) {
        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) return

        val fileUri = _settings.value.fileUri

        val textLog = try {
            textInputRepo.loadTextLog(Uri.parse(uriString), context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load text log for IMDb update: ${e.message}")
            return
        }

        if (textLog.isEmpty()) return

        val ratingsByDate = mutableMapOf<String, MutableList<Int>>()
        val allRatings = imdbCache.getAllRatings()

        for ((timestamp, text) in textLog) {
            val dateStr = timestamp.substring(0, 10)
            val parsed = OmdbService.parseTitle(text)
            val rating = allRatings[parsed.cacheKey]?.takeIf { it > 0 } ?: continue
            ratingsByDate.getOrPut(dateStr) { mutableListOf() }.add(rating)
        }

        val secEntries = mutableMapOf<String, Int>()
        for ((dateStr, ratings) in ratingsByDate) {
            if (ratings.isEmpty()) continue
            val avg = ratings.sum().toDouble() / ratings.size
            secEntries[dateStr] = Math.round(avg).toInt()
        }

        if (secEntries.isEmpty()) return

        val mutableDb = cachedPhoneDb.toMutableMap()
        val secKey = secondaryValueKey(habitName)
        val existingSec = mutableDb[secKey]?.toMutableMap() ?: mutableMapOf()

        for ((dateStr, avgRating) in secEntries) {
            existingSec[dateStr] = avgRating
        }

        mutableDb[secKey] = existingSec
        cachedPhoneDb = mutableDb

        if (fileUri.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                habitsRepo.persistDatabase(Uri.parse(fileUri), context, mutableDb)
            }
        }

        Log.d(TAG, "IMDb secondary values updated for '$habitName': ${secEntries.size} days")
    }

    /**
     * Called when a new movie text entry is saved. Fetches the IMDb rating
     * asynchronously (if not cached) and updates the secondary values.
     */
    fun triggerImdbFetchForEntry(habitName: String, text: String) {
        if (!hasImdbRatings(habitName)) return
        val parsed = OmdbService.parseTitle(text)
        if (parsed.title.isBlank()) return

        viewModelScope.launch {
            try {
                fetchAndCacheImdbRating(parsed)
                updateImdbSecondaryValues(habitName)
            } catch (e: Exception) {
                Log.w(TAG, "IMDb fetch for '$text' failed: ${e.message}")
            }
        }
    }

    /**
     * Fetches IMDb ratings for all existing movie entries that haven't been
     * looked up yet (the "backlog"). Respects the daily API limit of 990 calls.
     *
     * @param retryFailed When true, first clears all cached "no rating"
     *        entries so previously-failed titles (including ones poisoned by
     *        transient errors under the old logic) are fetched again.
     */
    fun fetchImdbBacklog(retryFailed: Boolean = false, onProgress: ((String) -> Unit)? = null) {
        val apiKey = _settings.value.omdbApiKey
        if (apiKey.isBlank()) {
            _omdbStatus.value = "Enter an OMDb API key first"
            return
        }

        val movieHabits = _settings.value.bridgeMovieHabits.toList()
        if (movieHabits.isEmpty()) {
            _omdbStatus.value = "No movie habits linked"
            return
        }

        if (_omdbBacklogRunning.value) {
            _omdbStatus.value = "Already running..."
            return
        }

        viewModelScope.launch {
            _omdbBacklogRunning.value = true
            try {
                if (retryFailed) {
                    val cleared = imdbCache.clearFailedLookups()
                    Log.i(TAG, "Retry: cleared $cleared failed IMDb lookups")
                }
                _omdbStatus.value = "Scanning movie entries..."

                val uniqueTitles = mutableSetOf<String>()
                val titleMap = mutableMapOf<String, ParsedTitle>()

                for (habitName in movieHabits) {
                    val uriString = _settings.value.textInputFileUris[habitName]
                    if (uriString.isNullOrEmpty()) continue
                    val textLog = try {
                        textInputRepo.loadTextLog(Uri.parse(uriString), context)
                    } catch (e: Exception) { continue }

                    for ((_, text) in textLog) {
                        val parsed = OmdbService.parseTitle(text)
                        if (parsed.title.isBlank()) continue
                        if (parsed.cacheKey !in uniqueTitles) {
                            uniqueTitles.add(parsed.cacheKey)
                            titleMap[parsed.cacheKey] = parsed
                        }
                    }
                }

                val toFetch = mutableListOf<ParsedTitle>()
                for (cacheKey in uniqueTitles) {
                    if (!imdbCache.hasBeenLookedUp(cacheKey)) {
                        toFetch.add(titleMap[cacheKey]!!)
                    }
                }

                val totalToFetch = toFetch.size
                val alreadyCached = uniqueTitles.size - totalToFetch
                val remaining = imdbCache.remainingCalls()
                val willFetch = minOf(totalToFetch, remaining)

                if (totalToFetch == 0) {
                    _omdbStatus.value = "All $alreadyCached titles already have ratings"
                    for (habitName in movieHabits) {
                        updateImdbSecondaryValues(habitName)
                    }
                    return@launch
                }

                _omdbStatus.value = "Fetching $willFetch of $totalToFetch ratings " +
                    "($alreadyCached cached, $remaining calls left today)..."

                var fetched = 0
                var found = 0
                for (parsed in toFetch) {
                    if (imdbCache.remainingCalls() <= 0) break

                    val rating = fetchAndCacheImdbRating(parsed)
                    fetched++
                    if (rating != null && rating > 0) found++

                    if (fetched % 25 == 0) {
                        val msg = "Progress: $fetched / $willFetch ($found with ratings)..."
                        _omdbStatus.value = msg
                        onProgress?.invoke(msg)
                    }
                }

                for (habitName in movieHabits) {
                    updateImdbSecondaryValues(habitName)
                }

                val skipped = totalToFetch - fetched
                _omdbStatus.value = buildString {
                    append("Fetched $fetched ratings ($found with ratings)")
                    if (skipped > 0) {
                        append(", $skipped deferred (daily limit)")
                    }
                    append(". Run again tomorrow for the rest.")
                }

                rebuildHabitList()
            } catch (e: Exception) {
                Log.e(TAG, "IMDb backlog fetch failed", e)
                _omdbStatus.value = "Backlog fetch failed: ${e.message}"
            } finally {
                _omdbBacklogRunning.value = false
            }
        }
    }

    /**
     * Fetches the desktop bridge's recent watch history and indexes the
     * exact file-probed durations by parsed title: cacheKey → (watch day →
     * total minutes). Empty when the bridge is unreachable — the caller
     * then falls back to OMDb runtimes.
     */
    private suspend fun fetchBridgeDurations(): Map<String, Map<String, Int>> {
        if (!_settings.value.bridgeEnabled) return emptyMap()
        val conn = getBridgeConnection() ?: return emptyMap()
        return try {
            movieBridgeService.fetchRecent(conn.first, conn.second, limit = 100)
                .mapNotNull { movie ->
                    val minutes = movie.totalWatchMin?.takeIf { it > 0 } ?: return@mapNotNull null
                    val key = OmdbService.parseTitle(movie.title).cacheKey
                    key to mapOf(movie.date to minutes)
                }
                .groupBy({ it.first }, { it.second })
                .mapValues { (_, pairs) ->
                    pairs.reduce { acc, map -> acc + map }  // same title watched on several days
                }
        } catch (e: Exception) {
            Log.w(TAG, "Bridge duration fetch failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Looks up a title's bridge minutes for [date], tolerating a ±1 day
     * offset (a film watched late at night can be confirmed the next
     * morning, landing the entry on a different day than the bridge's).
     */
    private fun bridgeMinutesFor(
        byDate: Map<String, Int>?,
        date: String
    ): Int? {
        if (byDate == null || byDate.isEmpty()) return null
        byDate[date]?.let { return it }
        val localDate = com.example.tail.data.parseDate(date) ?: return null
        for (offset in listOf(1L, -1L)) {
            byDate[dateString(localDate.plusDays(offset))]?.let { return it }
        }
        return null
    }

    /**
     * Backfills watch-length minutes for movie-habit entries that lack a
     * "(N min)" annotation.
     *
     * ## Resolution order
     *  1. **Bridge durations** — the desktop watcher probes the actual file
     *     with ffprobe, so its lengths are exact. Entries are matched by
     *     parsed title and watch day (±1 day).
     *  2. **OMDb runtimes** (when an API key is configured) — same lookup
     *     ladder as the IMDb ratings, same daily limit.
     *
     * ## Split rule
     * Bridge minutes are per title per day, so same-day duplicates split the
     * day's total evenly. For OMDb (one runtime per title), when the same
     * film/episode was logged on more than one day, its runtime is split
     * evenly across those backlog days — and within a day, across that day's
     * entries — so a title is never counted at full length more than once.
     * Entries that already carry a length are left untouched (the user set
     * those deliberately), as are titles whose runtime could not be resolved.
     *
     * After annotating, each habit's minutes slot is recomputed from the
     * annotations so the minutes counter fills automatically.
     */
    fun fetchMovieMinutesBacklog(onProgress: ((String) -> Unit)? = null) {
        val apiKey = _settings.value.omdbApiKey
        val movieHabits = _settings.value.bridgeMovieHabits.toList()
        if (movieHabits.isEmpty()) {
            _omdbStatus.value = "No movie habits linked"
            return
        }

        if (_omdbBacklogRunning.value) {
            _omdbStatus.value = "Already running..."
            return
        }

        // A movie text entry that still needs a length annotation
        data class PendingEntry(
            val timestamp: String,
            val date: String,
            val rawText: String,
            val parsed: ParsedTitle
        )

        viewModelScope.launch {
            _omdbBacklogRunning.value = true
            try {
                _omdbStatus.value = "Scanning movie entries for missing lengths..."

                val perHabit = mutableMapOf<String, MutableList<PendingEntry>>()

                for (habitName in movieHabits) {
                    val uriString = _settings.value.textInputFileUris[habitName]
                    if (uriString.isNullOrEmpty()) continue
                    val textLog = try {
                        textInputRepo.loadTextLog(Uri.parse(uriString), context)
                    } catch (e: Exception) { continue }

                    val list = perHabit.getOrPut(habitName) { mutableListOf() }
                    for ((timestamp, text) in textLog) {
                        if (timestamp.length < 10) continue
                        val parsed = OmdbService.parseTitle(text)
                        if (parsed.title.isBlank()) continue
                        if (parsed.minutes != null) continue  // already has a length
                        list.add(PendingEntry(timestamp, timestamp.substring(0, 10), text, parsed))
                    }
                }

                val pendingCount = perHabit.values.sumOf { it.size }
                if (pendingCount == 0) {
                    _omdbStatus.value = "All movie entries already have lengths"
                    return@launch
                }

                // ── Pass 1: bridge file durations (exact) ─────────────────
                _omdbStatus.value = "Fetching lengths from desktop bridge..."
                val bridgeDurations = fetchBridgeDurations()
                var bridgeUpdated = 0
                val omdbPerHabit = mutableMapOf<String, MutableList<PendingEntry>>()
                for ((habitName, entries) in perHabit) {
                    val updates = mutableMapOf<String, String>()
                    val stillPending = mutableListOf<PendingEntry>()
                    // Bridge minutes are per (title, day): group accordingly
                    val groups = entries.groupBy { it.parsed.cacheKey to it.date }
                    for ((key, dayEntries) in groups) {
                        val minutes = bridgeMinutesFor(bridgeDurations[key.first], key.second)
                        if (minutes != null && minutes > 0) {
                            val sorted = dayEntries.sortedBy { it.timestamp }
                            val shares = OmdbService.splitEvenly(minutes, sorted.size)
                            sorted.forEachIndexed { idx, entry ->
                                if (shares[idx] > 0) {
                                    updates[entry.timestamp] = "${entry.rawText} (${shares[idx]} min)"
                                }
                            }
                        } else {
                            stillPending += dayEntries
                        }
                    }
                    if (updates.isNotEmpty()) {
                        val uriString = _settings.value.textInputFileUris[habitName]
                        if (!uriString.isNullOrEmpty()) {
                            try {
                                textInputRepo.updateTextEntries(
                                    Uri.parse(uriString), context, updates, habitName
                                )
                                bridgeUpdated += updates.size
                            } catch (e: Exception) {
                                Log.w(TAG, "Bridge minutes write failed for '$habitName': ${e.message}")
                            }
                        }
                    }
                    if (stillPending.isNotEmpty()) {
                        omdbPerHabit[habitName] = stillPending
                    }
                }

                // ── Pass 2: OMDb runtimes for whatever is left ────────────
                var omdbUpdated = 0
                var toFetch = listOf<ParsedTitle>()
                var fetched = 0
                if (omdbPerHabit.isNotEmpty() && apiKey.isNotBlank()) {
                    val titleMap = mutableMapOf<String, ParsedTitle>()
                    for (entries in omdbPerHabit.values) {
                        for (entry in entries) titleMap[entry.parsed.cacheKey] = entry.parsed
                    }

                    // Resolve runtimes: cache first, then OMDb within the
                    // daily limit. needRuntime=true re-fetches titles whose
                    // rating is cached but whose runtime was never stored.
                    val runtimes = mutableMapOf<String, Int>()
                    val toFetchList = mutableListOf<ParsedTitle>()
                    toFetch = toFetchList
                    for (cacheKey in titleMap.keys) {
                        val cached = imdbCache.getRuntime(cacheKey)
                        if (cached != null) {
                            runtimes[cacheKey] = cached
                        } else if (!imdbCache.hasRuntime(cacheKey)) {
                            toFetchList.add(titleMap[cacheKey]!!)
                        }
                    }

                    for (parsed in toFetch) {
                        if (imdbCache.remainingCalls() <= 0) break
                        fetchAndCacheImdbRating(parsed, needRuntime = true)
                        fetched++
                        imdbCache.getRuntime(parsed.cacheKey)?.let { runtimes[parsed.cacheKey] = it }
                        if (fetched % 25 == 0) {
                            val msg = "Lengths: resolved $fetched / ${toFetch.size} titles..."
                            _omdbStatus.value = msg
                            onProgress?.invoke(msg)
                        }
                    }

                    // Split each title's runtime across its distinct dates,
                    // then across the entries within each date.
                    for ((habitName, entries) in omdbPerHabit) {
                        val byTitle = entries.groupBy { it.parsed.cacheKey }
                        val updates = mutableMapOf<String, String>()
                        for ((cacheKey, titleEntries) in byTitle) {
                            val runtime = runtimes[cacheKey] ?: continue
                            val distinctDates = titleEntries.map { it.date }.distinct().sorted()
                            val shareByDate = distinctDates
                                .zip(OmdbService.splitEvenly(runtime, distinctDates.size))
                                .toMap()
                            val byDate = titleEntries.groupBy { it.date }
                            for ((date, dayEntries) in byDate) {
                                val share = shareByDate[date] ?: continue
                                val sortedEntries = dayEntries.sortedBy { it.timestamp }
                                val entryShares = OmdbService.splitEvenly(share, sortedEntries.size)
                                sortedEntries.forEachIndexed { idx, entry ->
                                    val minutes = entryShares[idx]
                                    if (minutes > 0) {
                                        updates[entry.timestamp] = "${entry.rawText} ($minutes min)"
                                    }
                                }
                            }
                        }
                        if (updates.isNotEmpty()) {
                            val uriString = _settings.value.textInputFileUris[habitName]
                            if (!uriString.isNullOrEmpty()) {
                                try {
                                    textInputRepo.updateTextEntries(
                                        Uri.parse(uriString), context, updates, habitName
                                    )
                                    omdbUpdated += updates.size
                                } catch (e: Exception) {
                                    Log.w(TAG, "Minutes backfill write failed for '$habitName': ${e.message}")
                                }
                            }
                        }
                    }
                }

                val updated = bridgeUpdated + omdbUpdated
                val deferred = pendingCount - updated
                _omdbStatus.value = buildString {
                    append("Backfilled lengths on $updated entries")
                    if (bridgeUpdated > 0) append(" ($bridgeUpdated from bridge)")
                    if (toFetch.size - fetched > 0) {
                        append(" (${toFetch.size - fetched} titles deferred — daily limit)")
                    } else if (deferred > 0) {
                        append(" ($deferred entries without a resolvable runtime)")
                    }
                }

                rebuildHabitList()
                // Recompute the minutes slots from the new annotations and
                // refresh the graph text-entry cache so the runtime series
                // reflects the newly-annotated lengths immediately
                for (movieHabit in perHabit.keys) {
                    runCatching { syncMovieMinutesSlot(movieHabit) }
                    loadTextEntriesForGraph(movieHabit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Movie minutes backlog failed", e)
                _omdbStatus.value = "Minutes backfill failed: ${e.message}"
            } finally {
                _omdbBacklogRunning.value = false
            }
        }
    }

    /**
     * Runs [fetchMovieMinutesBacklog] once per day, automatically after
     * load, so entries confirmed without a length get annotated (bridge
     * file durations first, OMDb fallback) and the minutes slot fills
     * without user action.
     */
    private suspend fun maybeRunMovieMinutesBackfill() {
        val today = dateString(java.time.LocalDate.now())
        try {
            if (settingsRepo.getMovieMinutesBackfillDay() == today) return
            settingsRepo.saveMovieMinutesBackfillDay(today)
        } catch (e: Exception) {
            Log.w(TAG, "Movie minutes backfill guard failed: ${e.message}")
        }
        fetchMovieMinutesBacklog()
    }

    /** Returns the remaining OMDb API calls for today. */
    suspend fun getOmdbRemainingCalls(): Int = imdbCache.remainingCalls()

    /**
     * Returns a map of movie text entry to IMDb rating display string for all
     * entries on a given date. Used by the graph popup.
     */
    suspend fun getImdbRatingsForDate(
        habitName: String,
        date: java.time.LocalDate
    ): Map<String, String?> {
        if (!hasImdbRatings(habitName)) return emptyMap()

        val uriString = _settings.value.textInputFileUris[habitName]
        if (uriString.isNullOrEmpty()) return emptyMap()

        val datePrefix = dateString(date)
        val textLog = try {
            textInputRepo.loadTextLog(Uri.parse(uriString), context)
        } catch (e: Exception) { return emptyMap() }

        val result = mutableMapOf<String, String?>()
        for ((timestamp, text) in textLog) {
            if (!timestamp.startsWith(datePrefix)) continue
            result[text] = getImdbRatingForText(text)
        }
        return result
    }

    /**
     * Imports meditation data from a JSON file (meditation_output.json format).
     * Merges minutes → primary "Meditations" slot, sessions → secondary slot.
     * Uses max() merge so existing higher values are never overwritten.
     */
    fun importMeditationData(jsonFile: File, onComplete: (String) -> Unit = {}) {
        viewModelScope.launch {
            try {
                val uriString = _settings.value.fileUri
                if (uriString.isEmpty()) {
                    onComplete("No file URI set")
                    return@launch
                }

                val text = jsonFile.readText()
                val root = org.json.JSONObject(text)
                val daily = root.optJSONObject("daily") ?: run {
                    onComplete("No 'daily' key in JSON")
                    return@launch
                }

                val mutableDb = cachedPhoneDb.toMutableMap()
                val primaryKey = "Meditations"
                val secondaryKey = secondaryValueKey(primaryKey)

                // Ensure entries exist
                if (primaryKey !in mutableDb) mutableDb[primaryKey] = emptyMap()
                if (secondaryKey !in mutableDb) mutableDb[secondaryKey] = emptyMap()

                val primaryEntries = mutableDb[primaryKey]!!.toMutableMap()
                val secondaryEntries = mutableDb[secondaryKey]!!.toMutableMap()

                var minutesAdded = 0
                var sessionsAdded = 0

                val dates = daily.keys()
                while (dates.hasNext()) {
                    val dateStr = dates.next()
                    val dayInfo = daily.optJSONObject(dateStr) ?: continue
                    val garminMinutes = dayInfo.optInt("minutes", 0)
                    val garminSessions = dayInfo.optInt("sessions", 0)

                    if (garminMinutes > primaryEntries.getOrDefault(dateStr, 0)) {
                        primaryEntries[dateStr] = garminMinutes
                        minutesAdded++
                    }
                    if (garminSessions > secondaryEntries.getOrDefault(dateStr, 0)) {
                        secondaryEntries[dateStr] = garminSessions
                        sessionsAdded++
                    }
                }

                mutableDb[primaryKey] = primaryEntries
                mutableDb[secondaryKey] = secondaryEntries
                cachedPhoneDb = mutableDb
                rebuildHabitList()

                withContext(Dispatchers.IO) {
                    habitsRepo.persistDatabase(Uri.parse(uriString), context, mutableDb)
                }

                val msg = "Imported: $minutesAdded minutes entries, $sessionsAdded session entries"
                Log.d(TAG, "Meditation import: $msg")
                onComplete(msg)
            } catch (e: Exception) {
                Log.e(TAG, "Meditation import failed: ${e.message}", e)
                onComplete("Import failed: ${e.message?.take(80)}")
            }
        }
    }

    /**
     * Recalculates fitness age distance for all dates based on current fitness age data
     * and the currently configured date of birth.
     * This is useful when the date of birth is changed or when fitness age data is updated.
     */
    fun recalculateFitnessAgeDistance() {
        val s = _settings.value
        if (s.garminDateOfBirth.isEmpty()) {
            _garminSyncStatus.value = "Date of birth not set - cannot calculate fitness age distance"
            return
        }
        if (s.fileUri.isEmpty()) {
            _garminSyncStatus.value = "Set habit database file first"
            return
        }

        viewModelScope.launch {
            try {
                _garminSyncStatus.value = "Recalculating fitness age distance..."
                
                // Get fitness age data from cache
                val fitnessAgeData = garminRepo.loadAllCachedData()[com.example.tail.data.GarminType.FITNESS_AGE]
                if (fitnessAgeData == null || fitnessAgeData.isEmpty()) {
                    _garminSyncStatus.value = "No fitness age data available"
                    return@launch
                }

                val dob = LocalDate.parse(s.garminDateOfBirth)
                val distanceData = mutableMapOf<String, Int>()
                
                for ((dateStr, fitnessAge) in fitnessAgeData) {
                    val metricDate = LocalDate.parse(dateStr)
                    // Calculate biological age in hundredths of a year
                    val biologicalAgeYears = ChronoUnit.YEARS.between(dob, metricDate).toDouble()
                    val biologicalAgeHundredths = (biologicalAgeYears * 100).toInt()
                    // Distance = fitness_age - biological_age (both in hundredths of a year)
                    distanceData[dateStr] = fitnessAge - biologicalAgeHundredths
                }
                
                Log.d(TAG, "Recalculated ${distanceData.size} fitness age distance values (DOB: $dob)")
                
                // Create a map with just FITNESS_AGE_DISTANCE data
                val allData = mapOf(
                    com.example.tail.data.GarminType.FITNESS_AGE_DISTANCE to distanceData.toMap()
                )
                
                // Apply the recalculated data to linked habits
                applyGarminData(allData, s)
                
                _garminSyncStatus.value = "Recalculated ${distanceData.size} fitness age distance values"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to recalculate fitness age distance: ${e.message}", e)
                _garminSyncStatus.value = "Failed: ${e.message?.take(50)}"
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
     * Returns ALL stored location labels as a map of [LocalDate] → label in
     * ONE SharedPrefs read + parse pass (mirrors [getAllStoredCoordsParsed]).
     * Used by the travel-stats screen for city/country aggregation.
     */
    fun getAllStoredLabelsParsed(): Map<LocalDate, String> {
        val raw = locationRepo.getAllStoredLabels()
        val out = HashMap<LocalDate, String>(raw.size)
        for ((dateStr, label) in raw) {
            val d = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: continue
            out[d] = label
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
        val tracked = trackedHabitNames().ifEmpty { db.keys }
        return tracked
            .mapNotNull { name ->
                val raw = db[name]?.get(dateStr) ?: 0
                val pts = effectivePointsForDate(name, raw, dateStr)
                if (pts > 0) Pair(name, pts) else null
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
        val tracked = trackedHabitNames().ifEmpty { db.keys }

        var totalPoints = 0
        for (name in tracked) {
            val raw = db[name]?.get(dateStr) ?: 0
            val pts = effectivePointsForDate(name, raw, dateStr)
            if (pts > 0) totalPoints += pts
        }

        var monthlySum = 0
        for (i in 0 until 30) {
            val ds = dateString(date.minusDays(i.toLong()))
            for (name in tracked) {
                val raw = db[name]?.get(ds) ?: 0
                val pts = effectivePointsForDate(name, raw, ds)
                if (pts > 0) monthlySum += pts
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
     * Bulk version of the 30-day monthly average from [getDayStatsLight] for a
     * whole collection of dates at once (date → rounded average). Used by the
     * map screen to colour every dated dot in ONE sliding-window pass instead
     * of a 30-day × per-habit re-scan per date — see
     * [com.example.tail.data.monthlyAveragesBulk] for the algorithm.
     */
    fun getMonthlyAveragesBulk(dates: Collection<LocalDate>): Map<LocalDate, Int> =
        com.example.tail.data.monthlyAveragesBulk(
            db = cachedPhoneDb,
            tracked = trackedHabitNames().ifEmpty { cachedPhoneDb.keys },
            settings = _settings.value,
            dates = dates
        )

    /**
     * Triple-metric stats for "The Orrery" loading animation: today's total
     * points, the 7-day weekly average and the 30-day monthly average, all
     * ending on [date]. Computed in a single pass over the 30-day window.
     *
     * The monthly average drives the animation's primary form and colour,
     * the weekly average its orbital halo, and today's points the central
     * spark — see [HabitLoadingSpinner].
     */
    fun getLoadingMetrics(date: LocalDate): LoadingMetrics {
        val db = cachedPhoneDb
        // Habit set MUST mirror computeTaskerStats (the declared single
        // source of truth for today/avg7/avg30): every DB habit except
        // no-points habits and secondary-value storage keys. The previous
        // screen/order-based set (trackedHabitNames) silently dropped
        // habits that exist in the DB but not on any screen, yielding
        // lower totals — so the spinner dropped a tier once the DB load
        // replaced the value.
        val noPoints = _settings.value.noPointsHabits
        val tracked = db.keys.filter { it !in noPoints && !isInternalValueKey(it) }

        var dayTotal = 0
        var weekSum = 0
        var monthSum = 0
        for (i in 0 until 30) {
            val ds = dateString(date.minusDays(i.toLong()))
            var daySum = 0
            for (name in tracked) {
                val raw = db[name]?.get(ds) ?: 0
                val pts = effectivePointsForDate(name, raw, ds)
                if (pts > 0) daySum += pts
            }
            if (i == 0) dayTotal = daySum
            if (i < 7) weekSum += daySum
            monthSum += daySum
        }
        return LoadingMetrics(
            monthlyAverage = monthSum / 30.0,
            weeklyAverage = weekSum / 7.0,
            todayPoints = dayTotal
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
        val tracked = trackedHabitNames().ifEmpty { db.keys.filter { !isInternalValueKey(it) } }
        val fallbackHabits = _settings.value.secondaryValueFallbackHabits

        var totalPoints = 0
        for (name in tracked) {
            val raw = db[name]?.get(dateStr) ?: 0
            val pts = effectivePointsForDate(name, raw, dateStr)
            if (pts > 0) totalPoints += pts
        }

        var monthlySum = 0
        for (i in 0 until 30) {
            val ds = dateString(date.minusDays(i.toLong()))
            for (name in tracked) {
                val raw = db[name]?.get(ds) ?: 0
                val pts = effectivePointsForDate(name, raw, ds)
                if (pts > 0) monthlySum += pts
            }
        }
        val monthlyAverage = monthlySum.toDouble() / 30.0

        // Per-habit streak/anti-streak totals ending on [date].
        // Entries are filtered to <= dateStr so the "end" of the reversed list
        // is always [date], not today (fixes anti-streak stuck on today's value).
        var totalStreakDays = 0
        var totalAntiStreakDays = 0
        val timerMinutesPrimary = _settings.value.widgetTimerMinutesPrimary
        for (name in tracked) {
            val rawEntries = db[name] ?: continue
            // Widget-timer habits with minutes primary: swap the roles so
            // minutes drive the streak; the fallback on 0-minute days is
            // configurable (sessions by default, the second value, or none).
            val swapped = name in timerMinutesPrimary
            val mpFallback = _settings.value.minutesPrimaryFallbacks[name]
                ?: com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS
            val useFallback = name in fallbackHabits || swapped
            // Fallback source: minutes slot for minutes-primary habits; the
            // legacy secondary slot for habits that use it or have data there
            // (chess.com games, JugCoach seconds); minutes slot otherwise.
            val fallbackKey = if (swapped) {
                minutesKey(name)
            } else {
                com.example.tail.data.fallbackSlotKey(
                    name, _settings.value.secondaryValueHabits, db
                )
            }
            // Apply the fallback so days with 0 primary but non-zero fallback
            // count as "done" for streak purposes.
            val secEntries = if (useFallback) db[fallbackKey] ?: emptyMap() else emptyMap()
            // The fallback VALUE for minutes-primary habits: sessions (the
            // default), the second value, or none.
            val swappedFbEntries = when {
                !swapped -> rawEntries
                mpFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE -> emptyMap()
                mpFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_VALUE2 ->
                    db[secondaryValueKey(name)] ?: emptyMap()
                else -> rawEntries
            }
            val swappedUseFb = swapped &&
                mpFallback != com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE
            val entries = if (swapped) {
                com.example.tail.data.effectiveEntriesWithFallback(secEntries, swappedFbEntries, swappedUseFb)
            } else {
                com.example.tail.data.effectiveEntriesWithFallback(rawEntries, secEntries, useFallback)
            }
            // Cap entries at [date] — only include days up to and including [date]
            val capped = entries.filter { it.key <= dateStr }.toMutableMap()
            if (capped.isEmpty()) continue
            // Ensure [date] itself is present (as 0 if not recorded)
            if (!capped.containsKey(dateStr)) capped[dateStr] = 0
            val expanded = expandEntriesToCalendarDaysPublic(capped)
            val reversed = expanded.entries.sortedBy { it.key }.reversed()

            val divider = dividers[name] ?: 1
            var habStreak = 0
            for (entry in reversed) {
                val rawPrimary = rawEntries[entry.key] ?: 0
                val secVal = if (useFallback) secEntries[entry.key] ?: 0 else 0
                val pts = if (swapped) {
                    val fbVal = if (swappedUseFb) swappedFbEntries[entry.key] ?: 0 else 0
                    com.example.tail.data.effectivePointsWithFallback(secVal, divider, fbVal, swappedUseFb)
                } else {
                    com.example.tail.data.effectivePointsWithFallback(rawPrimary, divider, secVal, useFallback)
                }
                if (pts > 0) habStreak++ else break
            }
            var habAntiStreak = 0
            for (entry in reversed) {
                val rawPrimary = rawEntries[entry.key] ?: 0
                val secVal = if (useFallback) secEntries[entry.key] ?: 0 else 0
                val pts = if (swapped) {
                    val fbVal = if (swappedUseFb) swappedFbEntries[entry.key] ?: 0 else 0
                    com.example.tail.data.effectivePointsWithFallback(secVal, divider, fbVal, swappedUseFb)
                } else {
                    com.example.tail.data.effectivePointsWithFallback(rawPrimary, divider, secVal, useFallback)
                }
                if (pts == 0) habAntiStreak++ else break
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
            // Ensure the habit is in the customPointRangesHabits set
            val currentHabits = _settings.value.customPointRangesHabits.toMutableSet()
            if (habitName !in currentHabits) {
                currentHabits.add(habitName)
                _settings.value = _settings.value.copy(customPointRangesHabits = currentHabits)
                settingsRepo.saveCustomPointRangesHabits(currentHabits)
            }
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

    // ════════════════════════════════════════════════════════════════════════
    //  Meal Habit Engine Methods
    // ════════════════════════════════════════════════════════════════════════

    /** Saves all meal engine settings at once (called from Settings screen). */
    fun saveMealSettings(
        enabled: Boolean,
        baseUrl: String,
        apiKey: String,
        model: String,
        systemPrompt: String
    ) {
        viewModelScope.launch {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val cleanKey = apiKey.trim()
            settingsRepo.saveMealSettings(enabled, cleanUrl, cleanKey, model, systemPrompt)
            _settings.value = _settings.value.copy(
                mealEnabled = enabled,
                mealBaseUrl = cleanUrl,
                mealApiKey = cleanKey,
                mealModel = model,
                mealSystemPrompt = systemPrompt
            )
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  AI Assistant (natural-language habit database editing)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Controller for the AI Assistant chat: plans DB changes via an LLM,
     * shows them for confirmation, backs up, executes and can restore.
     * Lazy — created on first use (settings screen or the ⭐ dialog button).
     */
    val aiAssistant: com.example.tail.data.ai.AiAssistantController by lazy {
        com.example.tail.data.ai.AiAssistantController(
            context = context,
            habitsRepo = habitsRepo,
            configProvider = {
                val s = _settings.value
                com.example.tail.data.ai.AiAssistantConfig(
                    baseUrl = s.aiAssistantBaseUrl,
                    apiKey = s.aiAssistantApiKey,
                    model = s.aiAssistantModel
                )
            },
            fileUriProvider = { _settings.value.fileUri.takeIf { it.isNotEmpty() } },
            onDatabaseChanged = { refreshAfterExternalDbChange() }
        )
    }

    /** Saves the AI Assistant endpoint configuration. */
    fun saveAiAssistantSettings(baseUrl: String, apiKey: String, model: String) {
        viewModelScope.launch {
            val cleanUrl = baseUrl.trim().trimEnd('/')
            val cleanKey = apiKey.trim()
            settingsRepo.saveAiAssistantSettings(cleanUrl, cleanKey, model)
            _settings.value = _settings.value.copy(
                aiAssistantBaseUrl = cleanUrl,
                aiAssistantApiKey = cleanKey,
                aiAssistantModel = model
            )
        }
    }

    /**
     * Reloads the whole database from disk. Called after the AI Assistant
     * (or any external writer) modified habitsdb.txt / the timestamp store.
     */
    fun refreshAfterExternalDbChange() {
        val uri = _settings.value.fileUri
        if (uri.isNotEmpty()) {
            loadFromFile(Uri.parse(uri))
        }
    }

    /** Toggles the "Meal" type on/off for a specific habit. */
    fun toggleMealHabit(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.mealHabits.toMutableSet()
            if (habitName in current) {
                current.remove(habitName)
            } else {
                current.add(habitName)
            }
            settingsRepo.saveMealHabits(current)
            _settings.value = _settings.value.copy(mealHabits = current)
        }
    }

    /** Toggles the "Weights" type on/off for [habitName]. */
    fun toggleWeightsHabit(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.weightsHabits.toMutableSet()
            if (habitName in current) {
                current.remove(habitName)
            } else {
                current.add(habitName)
            }
            settingsRepo.saveWeightsHabits(current)
            _settings.value = _settings.value.copy(weightsHabits = current)
        }
    }

    /**
     * Toggles whether [habitName] appears on the day timeline (the
     * retrospective hour-by-hour view). Excluded habits are stored in a
     * set; every habit is shown by default.
     */
    fun toggleTimelineExcluded(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.timelineExcludedHabits.toMutableSet()
            if (habitName in current) {
                current.remove(habitName)
            } else {
                current.add(habitName)
            }
            settingsRepo.saveTimelineExcludedHabits(current)
            _settings.value = _settings.value.copy(timelineExcludedHabits = current)
        }
    }

    /**
     * Toggles the "Camera" type on/off for a specific habit. Camera-enabled
     * habits are the only ones offered to the LLM as choices when a photo is
     * captured (see [com.example.tail.data.meal.VisionHabitExecutor]).
     */
    fun toggleCameraHabit(habitName: String) {
        viewModelScope.launch {
            val current = _settings.value.cameraHabits.toMutableSet()
            if (habitName in current) {
                current.remove(habitName)
            } else {
                current.add(habitName)
            }
            settingsRepo.saveCameraHabits(current)
            _settings.value = _settings.value.copy(cameraHabits = current)
        }
    }

    // ── Vision Memory (LLM's learned image→habit associations) ──────────

    /** Reloads the vision memory entries from internal storage (newest-first). */
    fun refreshVisionMemory() {
        viewModelScope.launch(Dispatchers.IO) {
            _visionMemoryEntries.value = visionMemoryRepo.loadEntries().sortedByDescending { it.timestamp }
        }
    }

    /** Updates an edited vision memory entry in place. */
    fun updateVisionMemoryEntry(entry: com.example.tail.data.meal.VisionMemoryEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            visionMemoryRepo.updateEntry(entry)
            _visionMemoryEntries.value = visionMemoryRepo.loadEntries().sortedByDescending { it.timestamp }
        }
    }

    /** Deletes a vision memory entry by id (also removes its example image). */
    fun deleteVisionMemoryEntry(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            visionMemoryRepo.deleteEntry(id)
            _visionMemoryEntries.value = visionMemoryRepo.loadEntries().sortedByDescending { it.timestamp }
        }
    }

    /**
     * Sets the long-press action for a habit.
     * Pass [com.example.tail.data.LONG_PRESS_APP] to reset to default behaviour
     * (which removes the entry so the default kicks in).
     */
    fun setHabitLongPressAction(habitName: String, action: String) {
        viewModelScope.launch {
            val current = _settings.value.habitLongPressActions.toMutableMap()
            if (action == com.example.tail.data.LONG_PRESS_APP) {
                current.remove(habitName)
            } else {
                current[habitName] = action
            }
            settingsRepo.saveHabitLongPressActions(current)
            _settings.value = _settings.value.copy(habitLongPressActions = current)
        }
    }

    /**
     * Sets the URI opened when long-pressing a habit whose action is
     * [com.example.tail.data.LONG_PRESS_URL]. Passing a blank [url]
     * removes the entry (long-press then falls back to app behaviour).
     *
     * The value is normalized via [com.example.tail.data.normalizeLongPressUri]:
     * any URI scheme is preserved (obsidian://, tel:, spotify:// …), bare
     * domains get an https:// prefix, and pasted-but-unencoded characters
     * (spaces in vault/file names etc.) are percent-encoded.
     */
    fun setHabitLongPressUrl(habitName: String, url: String) {
        viewModelScope.launch {
            val current = _settings.value.habitLongPressUrls.toMutableMap()
            if (url.isBlank()) {
                current.remove(habitName)
            } else {
                current[habitName] = com.example.tail.data.normalizeLongPressUri(url)
            }
            settingsRepo.saveHabitLongPressUrls(current)
            _settings.value = _settings.value.copy(habitLongPressUrls = current)
        }
    }

    /**
     * Sets the app that should handle the long-press URL for a habit
     * (via Intent.setPackage). Pass a null/blank [packageName] to clear it,
     * which makes the URL open in the default browser again.
     */
    fun setHabitLongPressUrlApp(habitName: String, packageName: String?) {
        viewModelScope.launch {
            val current = _settings.value.habitLongPressUrlApps.toMutableMap()
            if (packageName.isNullOrBlank()) {
                current.remove(habitName)
            } else {
                current[habitName] = packageName
            }
            settingsRepo.saveHabitLongPressUrlApps(current)
            _settings.value = _settings.value.copy(habitLongPressUrlApps = current)
        }
    }

    /** Loads meal logs for a habit and updates the StateFlows. */
    fun loadMealLogs(habitName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val logs = mealLogRepo.loadLogs(habitName)
            val today = LocalDate.now().toString()
            val todayCal = logs.filter {
                java.time.Instant.ofEpochMilli(it.timestamp)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate().toString() == today
            }.sumOf { it.calories }
            val pending = visionQueueRepo.pendingCount()

            _mealLogsForHabit.value = logs
            _mealTodayCalories.value = todayCal
            _mealPendingCount.value = pending
        }
    }

    /** "HH:mm:ss" formatter used when recording meal increment timestamps. */
    private val mealTimeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * A tap on a meal habit card: merge-or-increment. When no meal group is
     * active (nothing logged within the group window), a placeholder card is
     * created and the habit incremented (with timestamp) — so EVERY tap
     * yields a card. An active group simply reopens the meal screen.
     */
    fun recordMealTap(habitName: String, date: LocalDate = LocalDate.now()) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val at = if (date == LocalDate.now()) now
            else date.atTime(java.time.LocalTime.now())
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (mealLogRepo.findActiveGroup(habitName, at) == null) {
                val log = com.example.tail.data.meal.MealLog(
                    id = UUID.randomUUID().toString(),
                    habitId = habitName,
                    timestamp = at,
                    title = "Meal",
                    isManual = true,
                    countedIncrement = true,
                    groupStartTimestamp = at
                )
                mealLogRepo.addLog(log)
                recordMealIncrement(habitName, at)
            }
            refreshMealFlows(habitName)
        }
    }

    /**
     * Adds a manual meal log entry (no photo, no LLM call). Records the
     * habit increment timestamp so manually logged meals are timestamped
     * like every other increment path. Fills in the active meal group's
     * placeholder card when one exists (1-hour grouping).
     */
    fun addManualMealLog(
        habitName: String,
        title: String,
        calories: Int,
        skipIncrement: Boolean = false
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val active = mealLogRepo.findActiveGroup(habitName, now)
            if (active != null && active.needsDetails()) {
                // Same meal-group still open without specifics — fill its card
                mealLogRepo.updateLog(
                    active.copy(
                        title = title,
                        calories = calories,
                        isManual = true,
                        timestamp = now,
                        groupStartTimestamp = active.anchorTime()
                    )
                )
            } else {
                val log = com.example.tail.data.meal.MealLog(
                    id = UUID.randomUUID().toString(),
                    habitId = habitName,
                    timestamp = now,
                    title = title,
                    calories = calories,
                    isManual = true,
                    countedIncrement = !skipIncrement,
                    groupStartTimestamp = now
                )
                mealLogRepo.addLog(log)
                if (!skipIncrement) recordMealIncrement(habitName, now)
            }
            refreshMealFlows(habitName)
        }
    }

    /**
     * Saves an edited meal log. When the log's creation counted as a habit
     * increment and the time was changed, the recorded habit timestamp is
     * moved to the new date/time so counts stay consistent.
     */
    fun updateMealLog(
        habitName: String,
        updated: com.example.tail.data.meal.MealLog,
        oldTimestamp: Long
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            mealLogRepo.updateLog(updated)
            if (updated.countedIncrement && updated.timestamp != oldTimestamp) {
                try {
                    val oldZdt = java.time.Instant.ofEpochMilli(oldTimestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                    val newZdt = java.time.Instant.ofEpochMilli(updated.timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                    deleteMealStampNear(habitName, oldZdt)
                    timestampRepo.addTimestamp(
                        habitName, newZdt.toLocalDate(), newZdt.toLocalTime().format(mealTimeFmt)
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to sync meal timestamp for '$habitName'", e)
                }
            }
            refreshMealFlows(habitName)
        }
    }

    /**
     * Deletes a meal log. When the log's creation incremented the habit
     * (countedIncrement), the increment and its timestamp are rolled back.
     */
    fun deleteMealLog(habitName: String, logId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val log = mealLogRepo.loadLogs(habitName).find { it.id == logId }
            mealLogRepo.deleteLog(habitName, logId)
            if (log != null && log.countedIncrement) {
                rollbackMealIncrement(habitName, log.timestamp)
            }
            refreshMealFlows(habitName)
        }
    }

    /**
     * Voice-only meal: the spoken description is parsed by the LLM into
     * title/calories/macros/tags/ratings — no photo needed. Merges into the
     * active meal group when one exists (no extra increment).
     */
    fun processVoiceMeal(habitName: String, transcript: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _mealVoiceStatus.value = "🎤 Parsing \"${transcript.take(60)}\"…"
            val s = _settings.value
            var fd: com.example.tail.data.meal.FoodData? = null
            if (s.mealEnabled && s.mealApiKey.isNotBlank() &&
                s.mealBaseUrl.isNotBlank() && s.mealModel.isNotBlank()
            ) {
                try {
                    val config = com.example.tail.data.meal.VisionConfig(
                        baseUrl = s.mealBaseUrl,
                        apiKey = s.mealApiKey,
                        model = s.mealModel,
                        userSystemPrompt = s.mealSystemPrompt
                    )
                    fd = com.example.tail.data.meal.VisionProcessingService()
                        .processMealText(transcript, config)
                } catch (e: Exception) {
                    Log.e(TAG, "Voice meal parse failed", e)
                }
            }

            val now = System.currentTimeMillis()
            val active = mealLogRepo.findActiveGroup(habitName, now)
            if (active != null) {
                // No newTimestamp: the log must keep the time its habit stamp
                // was recorded at — drifting it orphans the stamp and shows
                // duplicate chips on the schedule.
                mealLogRepo.updateLog(
                    active.mergedWith(foodData = fd, transcript = transcript)
                )
                _mealVoiceStatus.value = "Merged into \"${active.title}\""
            } else {
                val log = com.example.tail.data.meal.MealLog(
                    id = UUID.randomUUID().toString(),
                    habitId = habitName,
                    timestamp = now,
                    title = fd?.title?.takeIf { it.isNotBlank() } ?: transcript.take(40),
                    summary = fd?.summary?.takeIf { it.isNotBlank() },
                    calories = fd?.estimatedCalories ?: 0,
                    macronutrients = fd?.macronutrients
                        ?: com.example.tail.data.meal.Macronutrients(),
                    ingredientsDetected = fd?.ingredientsDetected ?: emptyList(),
                    isVeganVerified = fd?.isVeganVerified ?: false,
                    voiceTranscript = transcript,
                    macroRatings = fd?.macroRatings,
                    countedIncrement = true,
                    groupStartTimestamp = now
                )
                mealLogRepo.addLog(log)
                recordMealIncrement(habitName, now)
                _mealVoiceStatus.value =
                    if (fd != null) "Added \"${log.title}\""
                    else "Added card — tap it to add details"
            }
            refreshMealFlows(habitName)
        }
    }

    /**
     * Gallery photo: copies the picked image into internal storage and queues
     * it for the vision pipeline, attaching to the active meal group when one
     * exists (close-succession grouping → single increment).
     */
    fun addMealPhotoFromUri(habitName: String, uri: android.net.Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _mealVoiceStatus.value = "🖼️ Adding photo…"
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes == null || bytes.isEmpty()) {
                    _mealVoiceStatus.value = "Could not read the selected photo"
                    return@launch
                }
                val relPath = mealLogRepo.saveImageBytes(bytes)
                val active = mealLogRepo.findActiveGroup(habitName, System.currentTimeMillis())
                visionQueueRepo.enqueue(
                    imagePath = relPath,
                    habitId = habitName,
                    attachToMealLogId = active?.id
                )
                com.example.tail.data.meal.VisionProcessingWorker.enqueue(context)
                _mealPendingCount.value = visionQueueRepo.pendingCount()
                _mealVoiceStatus.value =
                    if (active != null) "Photo queued — merging into \"${active.title}\""
                    else "Photo queued for AI…"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add gallery meal photo", e)
                _mealVoiceStatus.value = "Failed to add photo"
            }
        }
    }

    /** Clears the voice/queue status line shown on the meal screen. */
    fun clearMealVoiceStatus() {
        _mealVoiceStatus.value = null
    }

    /**
     * Increments the habit for the meal's date and records the increment
     * timestamp — the shared "a meal happened" bookkeeping used by every
     * meal-creation path (manual, voice, worker).
     */
    private suspend fun recordMealIncrement(habitName: String, atMillis: Long) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) return
        try {
            val zdt = java.time.Instant.ofEpochMilli(atMillis)
                .atZone(java.time.ZoneId.systemDefault())
            val date = zdt.toLocalDate()
            if (date == LocalDate.now()) {
                habitsRepo.incrementHabit(
                    android.net.Uri.parse(uriString), context, habitName, 1
                )
            } else {
                habitsRepo.incrementHabitForDate(
                    android.net.Uri.parse(uriString), context, habitName, 1, date
                )
            }
            timestampRepo.addTimestamp(habitName, date, zdt.toLocalTime().format(mealTimeFmt))
            rebuildHabitList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to increment meal habit '$habitName'", e)
        }
    }

    /** Rolls back a counted meal increment (habit count + timestamp). */
    private suspend fun rollbackMealIncrement(habitName: String, atMillis: Long) {
        val uriString = _settings.value.fileUri
        if (uriString.isEmpty()) return
        try {
            val zdt = java.time.Instant.ofEpochMilli(atMillis)
                .atZone(java.time.ZoneId.systemDefault())
            habitsRepo.incrementHabitForDate(
                android.net.Uri.parse(uriString), context, habitName, -1, zdt.toLocalDate()
            )
            deleteMealStampNear(habitName, zdt)
            rebuildHabitList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to roll back meal increment for '$habitName'", e)
        }
    }

    /**
     * Deletes the meal-increment stamp for [zdt] — exact match first, then the
     * nearest stamp within ±5 minutes. Meal log timestamps can drift a few
     * seconds from their stamp (voice merges, editor saves that round to the
     * minute), so a strict exact match silently orphans stamps and leaves
     * duplicate chips on the schedule.
     */
    private suspend fun deleteMealStampNear(
        habitName: String,
        zdt: java.time.ZonedDateTime
    ) {
        val date = zdt.toLocalDate()
        val day = timestampRepo.getTimestampsForDay(habitName, date)
        val idx = day.indexOf(zdt.toLocalTime().format(mealTimeFmt))
        if (idx >= 0) {
            timestampRepo.deleteTimestamp(habitName, date, idx)
            return
        }
        val targetSec = zdt.toLocalTime().toSecondOfDay()
        val nearest = day.withIndex()
            .mapNotNull { (i, t) ->
                val s = runCatching {
                    java.time.LocalTime.parse(t).toSecondOfDay()
                }.getOrNull() ?: return@mapNotNull null
                val dist = kotlin.math.abs(s - targetSec)
                if (dist <= 300) i to dist else null
            }
            .minByOrNull { (_, dist) -> dist }
        if (nearest != null) {
            timestampRepo.deleteTimestamp(habitName, date, nearest.first)
        }
    }

    /** Reloads the meal StateFlows (logs, today's calories, queue count). */
    private suspend fun refreshMealFlows(habitName: String) {
        val logs = mealLogRepo.loadLogs(habitName)
        _mealLogsForHabit.value = logs
        val today = LocalDate.now().toString()
        _mealTodayCalories.value = logs.filter {
            java.time.Instant.ofEpochMilli(it.timestamp)
                .atZone(java.time.ZoneId.systemDefault())
                .toLocalDate().toString() == today
        }.sumOf { it.calories }
        _mealPendingCount.value = visionQueueRepo.pendingCount()
    }

    /** Triggers the vision processing worker to drain the queue (called after capture). */
    fun triggerVisionProcessing() {
        com.example.tail.data.meal.VisionProcessingWorker.enqueue(context)
        viewModelScope.launch(Dispatchers.IO) {
            _mealPendingCount.value = visionQueueRepo.pendingCount()
        }
    }

    /**
     * Tests the configured vision endpoint by sending a bundled test image
     * (banana.jpeg from assets) through the full pipeline. Updates
     * [_mealTestState] with the result for UI display.
     */
    fun testVisionEndpoint() {
        val s = _settings.value
        if (s.mealBaseUrl.isBlank() || s.mealApiKey.isBlank() || s.mealModel.isBlank()) {
            _mealTestState.value = MealTestState(
                isSuccess = false,
                message = "Please fill in Base URL, API Key, and Model Name first."
            )
            return
        }

        _mealTestState.value = MealTestState(
            isTesting = true,
            message = "Testing… (may retry on rate limits, up to ~15s)"
        )

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Copy banana.jpeg from assets to a temp file
                val tempFile = java.io.File(context.cacheDir, "test_banana.jpeg")
                context.assets.open("banana.jpeg").use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }

                val config = com.example.tail.data.meal.VisionConfig(
                    baseUrl = s.mealBaseUrl,
                    apiKey = s.mealApiKey,
                    model = s.mealModel,
                    userSystemPrompt = s.mealSystemPrompt
                )

                val service = com.example.tail.data.meal.VisionProcessingService()
                val result = service.processImage(tempFile, config)

                tempFile.delete()

                if (result == null) {
                    _mealTestState.value = MealTestState(
                        isSuccess = false,
                        message = "❌ Request failed — check your URL, key, and model. " +
                                  "See logcat (tag: VisionProcessing) for details."
                    )
                } else if (result.classification == com.example.tail.data.meal.VisionClassification.FOOD_MEAL &&
                           result.foodData != null
                ) {
                    val fd = result.foodData
                    _mealTestState.value = MealTestState(
                        isSuccess = true,
                        message = "✅ Success! Detected: ${fd.title}\n" +
                                  "Calories: ${fd.estimatedCalories} kcal\n" +
                                  "Protein: ${fd.macronutrients.proteinGrams}g, " +
                                  "Carbs: ${fd.macronutrients.carbsGrams}g, " +
                                  "Fat: ${fd.macronutrients.fatGrams}g\n" +
                                  "Confidence: ${(result.confidenceScore * 100).toInt()}%"
                    )
                } else {
                    // Distinguish API errors (rate limit, server error) from model classification issues
                    val notes = result.processingNotes
                    val isError = notes.contains("Rate limited", ignoreCase = true) ||
                                  notes.contains("Server error", ignoreCase = true) ||
                                  notes.contains("API error", ignoreCase = true)
                    _mealTestState.value = MealTestState(
                        isSuccess = !isError,
                        message = if (isError) {
                            "❌ ${notes.take(300)}"
                        } else {
                            "⚠️ Got a response but classification was " +
                            "${result.classification}.\n" +
                            "Notes: $notes\n" +
                            "The endpoint works, but the model may not handle food images well."
                        }
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Vision test failed", e)
                _mealTestState.value = MealTestState(
                    isSuccess = false,
                    message = "❌ Error: ${e.message?.take(200)}"
                )
            }
        }
    }
}

class HabitViewModelFactory(
    private val habitsRepo: HabitsRepository,
    private val settingsRepo: SettingsRepository,
    private val textInputRepo: TextInputRepository,
    private val datedEntryRepo: DatedEntryRepository,
    private val subtypeDataRepo: SubtypeDataRepository,
    private val timedDataRepo: TimedDataRepository,
    private val context: Context,
    private val backupManager: BackupManager? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HabitViewModel(
            habitsRepo, settingsRepo, textInputRepo, datedEntryRepo,
            subtypeDataRepo, timedDataRepo, context, backupManager
        ) as T
    }
}
