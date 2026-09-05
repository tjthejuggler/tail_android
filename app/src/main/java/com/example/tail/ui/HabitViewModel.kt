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

/**
 * Parses one media text-log entry written by MediaPlaybackTracker:
 * `"HH:mm Title — Artist/Show (NN min) — mediaUri"` (artist, duration and
 * URI segments are all optional). Group1 = title, group2 = artist/show,
 * group3 = duration minutes. Used by the per-show removal breakdown.
 */
internal val MEDIA_LOG_ENTRY_REGEX = Regex(
    "^\\d{1,2}:\\d{2}\\s+(.+?)(?:\\s+—\\s+(.+?))?(?:\\s+\\((\\d+)\\s*min\\))?(?:\\s+—\\s+.*)?$"
)

// Resonance-breathing secondary-value migration: pre-2026-08-08 primary values at or
// below this are legacy session counts; anything larger is real backfilled minutes.
internal const val MAX_LEGACY_RESONANCE_SESSION_COUNT = 3
internal const val BRIDGE_PORT = 8001

/** Total cells in the 8×10 habit grid — matches TOTAL_CELLS in HabitGridScreen. */
internal const val TOTAL_GRID_CELLS = 80

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
internal fun extractCountry(label: String, ignoredNames: Set<String> = emptySet()): String? {
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
    internal val habitsRepo: HabitsRepository,
    internal val settingsRepo: SettingsRepository,
    internal val textInputRepo: TextInputRepository,
    internal val datedEntryRepo: DatedEntryRepository,
    internal val subtypeDataRepo: SubtypeDataRepository,
    internal val timedDataRepo: TimedDataRepository,
    internal val context: Context,
    internal val backupManager: BackupManager? = null,
    internal val locationRepo: LocationRepository = LocationRepository(context)
) : ViewModel() {
    
    // Cache for text entries used in graph filtering
    internal val _textEntriesCache = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    val textEntriesCache: StateFlow<Map<String, Map<String, String>>> = _textEntriesCache.asStateFlow()

    /** Repository for recording habit increment timestamps (internal storage). */
    val timestampRepo = HabitTimestampRepository(context)

    // ── Habit-ask notifications (system + in-app center + one-time flash) ───
    /** Single source of truth for pending asks; system notifications mirror it. */
    val notificationStore = NotificationStore(context)

    /** Pending asks, oldest first. Mirrored from [notificationStore]. */
    internal val _notifications = MutableStateFlow<List<HabitNotification>>(emptyList())
    val notifications: StateFlow<List<HabitNotification>> = _notifications.asStateFlow()

    // ── Meal Habit Engine ─────────────────────────────────────────────────
    /** Repository for meal log entries and images (internal storage). */
    val mealLogRepo = com.example.tail.data.meal.MealLogRepository(context)
    /** Repository for the offline vision-processing queue (internal storage). */
    val visionQueueRepo = com.example.tail.data.meal.VisionQueueRepository(context)
    /** Repository for the LLM vision memory (user-taught image→habit associations). */
    val visionMemoryRepo = com.example.tail.data.meal.VisionMemoryRepository(context)

    /** Learned vision memory entries (newest-first), for the Settings screen. */
    internal val _visionMemoryEntries = MutableStateFlow<List<com.example.tail.data.meal.VisionMemoryEntry>>(emptyList())
    val visionMemoryEntries: StateFlow<List<com.example.tail.data.meal.VisionMemoryEntry>> =
        _visionMemoryEntries.asStateFlow()

    /** Meal logs for the currently-opened meal habit (newest-first). */
    internal val _mealLogsForHabit = MutableStateFlow<List<com.example.tail.data.meal.MealLog>>(emptyList())
    val mealLogsForHabit: StateFlow<List<com.example.tail.data.meal.MealLog>> = _mealLogsForHabit.asStateFlow()

    /** Today's total calories for the currently-opened meal habit. */
    internal val _mealTodayCalories = MutableStateFlow(0)
    val mealTodayCalories: StateFlow<Int> = _mealTodayCalories.asStateFlow()

    /** Count of pending items in the vision queue (for UI badge). */
    internal val _mealPendingCount = MutableStateFlow(0)
    val mealPendingCount: StateFlow<Int> = _mealPendingCount.asStateFlow()

    /**
     * Unresolved vision-queue items (pending/processing/failed/needs review),
     * newest first — shown in the meal details screen with per-item status,
     * error info and a force-reprocess control.
     */
    internal val _mealQueueItems = MutableStateFlow<List<com.example.tail.data.meal.VisionQueueItem>>(emptyList())
    val mealQueueItems: StateFlow<List<com.example.tail.data.meal.VisionQueueItem>> = _mealQueueItems.asStateFlow()

    /** Status of the last voice-meal parse / photo queue action (null = idle). */
    internal val _mealVoiceStatus = MutableStateFlow<String?>(null)
    val mealVoiceStatus: StateFlow<String?> = _mealVoiceStatus.asStateFlow()

    /** Vision endpoint test result (null = not tested, empty = testing, non-empty = result). */
    data class MealTestState(
        val isTesting: Boolean = false,
        val isSuccess: Boolean = false,
        val message: String = ""
    )
    internal val _mealTestState = MutableStateFlow(MealTestState())
    val mealTestState: StateFlow<MealTestState> = _mealTestState.asStateFlow()

    // ── Global habit search ──────────────────────────────────────────────────

    /** Persists the last query + filters so search state survives app restarts. */
    internal val searchStateStore = SearchStateStore(context)

    /**
     * Current search query text. Lives in the ViewModel (not dialog-local
     * state) so closing the search popup — including by tapping a result —
     * preserves its exact state for the next time the search icon is pressed.
     * The last state is also persisted via [SearchStateStore] and restored
     * on app start.
     */
    internal val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    /** Habit names included in the search (filter section). */
    internal val _searchFilters = MutableStateFlow<Set<String>>(emptySet())
    val searchFilters: StateFlow<Set<String>> = _searchFilters.asStateFlow()

    /**
     * True once the filter set carries real state — either restored from
     * [SearchStateStore] or defaulted to "all". Until then an empty filter
     * set means "not initialised yet", never "user deselected everything".
     */
    internal var searchFiltersInitialized = false

    init {
        searchStateStore.load()?.let { saved ->
            _searchQuery.value = saved.query
            _searchFilters.value = saved.filters
            searchFiltersInitialized = true
        }
    }

    /** Habits that have any searchable text, for the filter section. */
    internal val _searchableHabits = MutableStateFlow<List<SearchableHabitInfo>>(emptyList())
    val searchableHabits: StateFlow<List<SearchableHabitInfo>> = _searchableHabits.asStateFlow()

    /** Latest search hits, sorted by relevance then date. */
    internal val _searchResults = MutableStateFlow<List<HabitSearchResult>>(emptyList())
    val searchResults: StateFlow<List<HabitSearchResult>> = _searchResults.asStateFlow()

    internal val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    internal var searchJob: Job? = null

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

    internal fun persistSearchState() {
        searchStateStore.save(_searchQuery.value, _searchFilters.value)
    }

    internal fun rerunSearchIfActive() {
        val q = _searchQuery.value
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { performSearch(q) }
    }

    internal suspend fun performSearch(query: String) {
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
    internal val _highlightedHabit = MutableStateFlow<String?>(null)
    val highlightedHabit: StateFlow<String?> = _highlightedHabit.asStateFlow()

    internal var highlightJob: Job? = null

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
    internal val _selectedDateLocation = MutableStateFlow<String?>(null)
    val selectedDateLocation: StateFlow<String?> = _selectedDateLocation.asStateFlow()

    internal val _habits = MutableStateFlow<List<Habit>>(emptyList())
    val habits: StateFlow<List<Habit>> = _habits.asStateFlow()

    /**
     * Today's total habit points (sum of effective per-habit counts for the selected
     * date). Updated in [rebuildHabitList] and retained across loads so the tiered
     * loading spinner can reflect the current day's colour even while a fresh load
     * is in progress (when [habits] is momentarily stale/empty).
     */
    internal val _todayPoints = MutableStateFlow(0)
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
    internal val metricsPrefs = context.getSharedPreferences("loading_metrics_cache", Context.MODE_PRIVATE)

    internal val _loadingMetrics = MutableStateFlow(readCachedLoadingMetrics())
    val loadingMetrics: StateFlow<LoadingMetrics> = _loadingMetrics.asStateFlow()

    /**
     * Last persisted metrics for the cold-start hydration. The averages move
     * slowly (1/30th and 1/7th daily weight) so they stay valid across
     * midnight; the daily total is only trusted when the cache was written
     * today — otherwise the spark starts dormant rather than wrong.
     */
    internal fun readCachedLoadingMetrics(): LoadingMetrics {
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
    internal fun cacheLoadingMetrics(m: LoadingMetrics, date: LocalDate) {
        metricsPrefs.edit()
            .putFloat("monthly_avg", m.monthlyAverage.toFloat())
            .putFloat("weekly_avg", m.weeklyAverage.toFloat())
            .putInt("today_points", m.todayPoints)
            .putString("date", date.toString())
            .apply()
    }

    internal val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    internal val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    internal val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    /** The date currently being viewed/edited. Starts at today. */
    internal val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /** True when selectedDate == today */
    val isToday: Boolean get() = _selectedDate.value == LocalDate.now()

    /** When true, the grid is in tap-to-select reorder edit mode. */
    internal val _editMode = MutableStateFlow(false)
    val editMode: StateFlow<Boolean> = _editMode.asStateFlow()

    /**
     * The current display order of habit names. Starts as HABIT_ORDER, then reflects
     * any custom ordering the user has saved.
     */
    internal val _habitOrder = MutableStateFlow<List<String>>(HABIT_ORDER)
    val habitOrder: StateFlow<List<String>> = _habitOrder.asStateFlow()

    /** The index (in the current habit list) of the habit selected for reordering. -1 = none. */
    internal val _selectedEditIndex = MutableStateFlow(-1)
    val selectedEditIndex: StateFlow<Int> = _selectedEditIndex.asStateFlow()

    /**
     * When >= 0, the user has tapped "Move" on the selected habit and we are waiting for
     * them to tap a destination cell. This stores the source grid index.
     * -1 = not in move-pending mode.
     */
    internal val _movePendingSourceIndex = MutableStateFlow(-1)
    val movePendingSourceIndex: StateFlow<Int> = _movePendingSourceIndex.asStateFlow()

    /** The list of named habit screens. Empty = not yet initialised (use flat habitOrder). */
    internal val _habitScreens = MutableStateFlow<List<HabitScreen>>(emptyList())
    val habitScreens: StateFlow<List<HabitScreen>> = _habitScreens.asStateFlow()

    /** Index of the currently displayed screen. */
    internal val _activeScreenIndex = MutableStateFlow(0)
    val activeScreenIndex: StateFlow<Int> = _activeScreenIndex.asStateFlow()

    // ── AI Icon Generation ───────────────────────────────────────────────────
    internal val aiIconRepo = AiIconRepository(context)
    internal val aiIconGenService = AiIconGeneratorService()

    /** List of AI-generated icon metadata, refreshed after generate/delete. */
    internal val _aiIcons = MutableStateFlow<List<AiIcon>>(emptyList())
    val aiIcons: StateFlow<List<AiIcon>> = _aiIcons.asStateFlow()

    /** True while an AI icon generation request is in flight. */
    internal val _aiIconGenerating = MutableStateFlow(false)
    val aiIconGenerating: StateFlow<Boolean> = _aiIconGenerating.asStateFlow()

    /** Error message from the last AI icon generation attempt (null = no error). */
    internal val _aiIconError = MutableStateFlow<String?>(null)
    val aiIconError: StateFlow<String?> = _aiIconError.asStateFlow()

    /**
     * Habit names with an AI icon generation currently in flight. The habit
     * tile shows a spinner while its name is in this set, so the user can
     * leave the icon picker and still see progress in the grid.
     */
    internal val _aiIconPendingHabits = MutableStateFlow<Set<String>>(emptySet())
    val aiIconPendingHabits: StateFlow<Set<String>> = _aiIconPendingHabits.asStateFlow()

    /**
     * One-shot user-facing messages about background AI icon generation
     * (started / applied / failed), consumed as toasts by the grid screen.
     */
    internal val _aiIconMessages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val aiIconMessages: SharedFlow<String> = _aiIconMessages.asSharedFlow()

    // ── Chess.com Integration ─────────────────────────────────────────────────
    internal val chessComRepo = ChessComRepository(context)

    /** Status message for chess.com sync operations (shown in settings). */
    internal val _chessComSyncStatus = MutableStateFlow("")
    val chessComSyncStatus: StateFlow<String> = _chessComSyncStatus.asStateFlow()

    /** Job for the periodic chess.com polling loop. */
    internal var chessComPollingJob: Job? = null

    /** Interval between chess.com polls (15 minutes). */
    internal val CHESS_COM_POLL_INTERVAL_MS = 15 * 60 * 1000L

    // ── GitHub Integration ────────────────────────────────────────────────────
    internal val githubRepo = GitHubRepository(context)

    /** Status message for GitHub sync operations (shown in edit panel + settings). */
    internal val _githubSyncStatus = MutableStateFlow("")
    val githubSyncStatus: StateFlow<String> = _githubSyncStatus.asStateFlow()

    /** Job for the periodic GitHub polling loop. */
    internal var githubPollingJob: Job? = null

    /** Interval between GitHub polls (30 minutes — GitHub API is rate-limited). */
    internal val GITHUB_POLL_INTERVAL_MS = 30 * 60 * 1000L

    /**
     * In-memory cache of all four GitHub metrics per day, keyed by habit name.
     * Populated during [fetchGithubBacklog] so the graph can display every
     * metric simultaneously without additional API calls.
     */
    internal var _githubDailyCache: Map<String, Map<String, GitHubRepository.GithubDailyMetrics>> = emptyMap()

    /**
     * In-memory cache of per-day commit messages (formatted "sha message"),
     * keyed by habit name then date. Populated during [fetchGithubBacklog] and
     * the periodic recent sync; used by the graph to list the actual commit
     * messages when the "Commits" metric is selected.
     */
    internal var _githubCommitMessages: Map<String, Map<String, List<String>>> = emptyMap()

    internal val garminRepo = GarminRepository(context)

    /** Status message for Garmin sync operations (shown in settings). */
    internal val _garminSyncStatus = MutableStateFlow("")
    val garminSyncStatus: StateFlow<String> = _garminSyncStatus.asStateFlow()

    /** Current month's Garmin data for display (metric type → date → value). */
    internal val _garminMonthlyData = MutableStateFlow<Map<GarminType, Map<String, Int>>>(emptyMap())
    val garminMonthlyData: StateFlow<Map<GarminType, Map<String, Int>>> = _garminMonthlyData.asStateFlow()

    /** Job for the periodic Garmin polling loop. */
    internal var garminPollingJob: Job? = null

    /** Interval between Garmin polls (once a day). */
    internal val GARMIN_POLL_INTERVAL_MS = 24 * 60 * 60 * 1000L

    // ── Tail Bridge Integration (Movies + future tethered features) ──────────
    internal val movieBridgeService = MovieBridgeService()

    /** Status message for bridge operations (shown in settings). */
    internal val _bridgeStatus = MutableStateFlow("")
    val bridgeStatus: StateFlow<String> = _bridgeStatus.asStateFlow()

    /**
     * The latest movie suggestion fetched from the desktop bridge.
     * Non-null while a movie confirm dialog is showing. Set back to null
     * when the dialog is dismissed.
     */
    internal val _movieSuggestion = MutableStateFlow<BridgeMovie?>(null)
    val movieSuggestion: StateFlow<BridgeMovie?> = _movieSuggestion.asStateFlow()

    /**
     * Phone-local copy of the desktop's movie watch history
     * ([MovieCacheStore]), refreshed by the background sync worker and on
     * app open. Null until first loaded from disk. Every movie surface
     * (ask check, increment suggestion, last-watched picker) reads from
     * this — no network round trip on the UI path.
     */
    internal val _movieCache = MutableStateFlow<List<BridgeMovie>?>(null)
    val movieCache: StateFlow<List<BridgeMovie>?> = _movieCache.asStateFlow()

    /** When [movieCache] was last pulled from the bridge (0 = never). */
    internal var movieCacheFetchedAt = 0L

    /** Snapshot of the cache for instant UI use (empty before first load). */
    fun currentMovieCache(): List<BridgeMovie> = _movieCache.value.orEmpty()

    /** Loads the cache from disk once; later calls reuse the in-memory copy. */
    internal suspend fun loadMovieCacheOnce(): List<BridgeMovie> {
        _movieCache.value?.let { return it }
        val cached = try {
            MovieCacheStore.load(context)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load movie cache: ${e.message}")
            null
        } ?: return emptyList()
        movieCacheFetchedAt = cached.fetchedAtMs
        _movieCache.value = cached.movies
        return cached.movies
    }

    /**
     * Pulls the recent watch history from the bridge into the local cache.
     * Returns the fresh list, or null when the bridge was unreachable (the
     * cache is left untouched in that case).
     */
    internal suspend fun refreshMovieCacheFromBridge(): List<BridgeMovie>? {
        val conn = getBridgeConnection() ?: return null
        val fresh = try {
            movieBridgeService.fetchRecent(conn.first, conn.second, MovieCacheStore.CAPACITY)
        } catch (e: Exception) {
            Log.w(TAG, "Movie cache refresh failed: ${e.message}")
            null
        } ?: return null
        if (fresh.isNotEmpty()) {
            MovieCacheStore.save(context, fresh)
            movieCacheFetchedAt = System.currentTimeMillis()
            _movieCache.value = fresh
        }
        return fresh
    }

    /**
     * The newest cached movie whose title is not in [excludeKeys] (parsed
     * cache keys of entries already logged for the day) — the same
     * semantics as the bridge's `movies/suggest` endpoint, but resolved
     * locally with zero network.
     */
    internal fun suggestMovieFromCache(
        movies: List<BridgeMovie>,
        excludeKeys: Set<String>
    ): BridgeMovie? = movies.firstOrNull {
        OmdbService.parseTitle(it.title).cacheKey !in excludeKeys
    }

    // ── OMDb / IMDb ratings integration ────────────────────────────────────
    internal val omdbService = OmdbService()
    internal val imdbCache = ImdbRatingCache(context)

    /** Status message for OMDb operations (shown in settings). */
    internal val _omdbStatus = MutableStateFlow("")
    val omdbStatus: StateFlow<String> = _omdbStatus.asStateFlow()

    /** True while the IMDb backlog fetch is running. */
    internal val _omdbBacklogRunning = MutableStateFlow(false)
    val omdbBacklogRunning: StateFlow<Boolean> = _omdbBacklogRunning.asStateFlow()

    // Track the last loaded URI to avoid reloading on every settings emission
    internal var lastLoadedUri: String = ""

    // Debounce job for day navigation — cancelled on each new arrow tap so we only
    // rebuild the habit list after the user has settled on a date for a moment.
    internal var navDebounceJob: Job? = null
    internal val NAV_DEBOUNCE_MS = 800L

    // Flag to suppress settingsFlow reaction while we're saving a new habit order / screens
    internal var isSavingOrder: Boolean = false

    // Flag to suppress settingsFlow reaction while we're saving the active screen index
    // This prevents a race condition where switching screens triggers a settings emission
    // that overwrites the user's choice back to the previous screen
    @Volatile
    internal var isSavingScreenIndex: Boolean = false

    // Cache the full unified DB so we can rebuild the habit list without re-reading the file
    internal var cachedPhoneDb: HabitsDatabase = emptyMap()

    // TRUE only after the phone DB has been successfully loaded from disk at least
    // once this session. Background sync writers (chess.com, Garmin) MUST NOT
    // persist cachedPhoneDb while this is false -- otherwise a startup race (or a
    // transient load failure during a Syncthing write) lets them build a DB from
    // an empty cache and clobber the real file (the 2026-07-19 wipe root cause).
    @Volatile
    internal var dbLoaded: Boolean = false

    // Tracks the date on which roll forward was last performed.
    // This ensures we only roll forward once per day, not on every DB load.
    internal var rollForwardLastDate: LocalDate? = null

    // Per-screen habit list cache — avoids expensive rebuildHabitList() on every screen switch.
    // Keyed by (screen index, selected date) so switching between screens on the same date is instant.
    internal val screenHabitCache = mutableMapOf<Pair<Int, LocalDate>, List<Habit>>()

    // Guards background warming of screenHabitCache: identifies the
    // (cachedPhoneDb identity, selected date) pair the other screens' lists
    // were last warmed for, so warmScreenCaches() does redundant work at most
    // once per data change instead of on every rebuild.
    internal var screenWarmKey: Pair<Any, LocalDate>? = null

    /** Serializes [rebuildHabitList] runs. Rebuilds are launched from many
     *  asynchronous triggers (screen switches, HabitIncrementBus events,
     *  Garmin/Chess/GitHub syncs, …) and each one publishes the whole habit
     *  list; without serialization an older snapshot can finish last and
     *  clobber the optimistic increment update (the "tap records the
     *  timestamp but the square only updates after switching screens" bug). */
    internal val rebuildMutex = Mutex()


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


    internal val _snapshots = MutableStateFlow<List<SnapshotUi>>(emptyList())
    val snapshots: StateFlow<List<SnapshotUi>> = _snapshots.asStateFlow()

    internal val _snapshotStatus = MutableStateFlow<String?>(null)
    val snapshotStatus: StateFlow<String?> = _snapshotStatus.asStateFlow()

    // ── Single-habit restore from a backup file ───────────────────────────
    /** Non-null while a restore-from-backup confirmation dialog is showing. */
    internal val _habitRestorePreview = MutableStateFlow<HabitRestorePreview?>(null)
    val habitRestorePreview: StateFlow<HabitRestorePreview?> = _habitRestorePreview.asStateFlow()

    /** The backup URI pending confirmation (kept so [applyHabitRestore] can use it). */
    internal val _pendingRestoreUri = MutableStateFlow<Uri?>(null)
    val pendingRestoreUri: StateFlow<Uri?> = _pendingRestoreUri.asStateFlow()

    /** Status / error message for the most recent single-habit restore. */
    internal val _habitRestoreStatus = MutableStateFlow<String?>(null)
    val habitRestoreStatus: StateFlow<String?> = _habitRestoreStatus.asStateFlow()


    fun clearError() {
        _errorMessage.value = null
    }

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
    internal fun conditionalBackfillSlots(habitName: String): Map<String, List<String>> {
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

    internal val minutesWriteMutex = Mutex()


    internal val _mediaTodayShows = MutableStateFlow<List<MediaShowMinutes>>(emptyList())

    /** Today's per-show listening breakdown for the currently edited media habit. */
    val mediaTodayShows: StateFlow<List<MediaShowMinutes>> = _mediaTodayShows.asStateFlow()

    internal val _survivalPbSyncStatus = kotlinx.coroutines.flow.MutableStateFlow("")
    val survivalPbSyncStatus: kotlinx.coroutines.flow.StateFlow<String> = _survivalPbSyncStatus

    internal val _aiModels = MutableStateFlow<List<com.example.tail.data.AiModelInfo>>(
        com.example.tail.data.FALLBACK_IMAGE_MODELS
    )
    val aiModels: StateFlow<List<com.example.tail.data.AiModelInfo>> = _aiModels.asStateFlow()


    internal val _graphMode = MutableStateFlow(false)
    val graphMode: StateFlow<Boolean> = _graphMode.asStateFlow()

    /** Habit names currently selected for graphing. */
    internal val _graphSelectedHabits = MutableStateFlow<Set<String>>(emptySet())
    val graphSelectedHabits: StateFlow<Set<String>> = _graphSelectedHabits.asStateFlow()

    /** Currently selected time period for the graph — survives rotation. */
    internal val _graphTimePeriod = MutableStateFlow<GraphTimePeriod?>(GraphTimePeriod.MONTH)
    val graphTimePeriod: StateFlow<GraphTimePeriod?> = _graphTimePeriod.asStateFlow()

    internal val _graphZoomStartDate = MutableStateFlow<LocalDate?>(null)
    val graphZoomStartDate: StateFlow<LocalDate?> = _graphZoomStartDate.asStateFlow()

    internal val _graphZoomEndDate = MutableStateFlow<LocalDate?>(null)
    val graphZoomEndDate: StateFlow<LocalDate?> = _graphZoomEndDate.asStateFlow()


    internal val _scheduleMode = MutableStateFlow(false)
    val scheduleMode: StateFlow<Boolean> = _scheduleMode.asStateFlow()


    internal fun primaryGithubMetricKey(habitName: String): String {
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
    internal fun metricValueOf(dp: GraphDataPoint, metric: String): Int = when (metric) {
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
    internal fun withMetricValue(dp: GraphDataPoint, metric: String, value: Int): GraphDataPoint = when (metric) {
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
    internal fun interpolateMetricZeros(points: MutableList<GraphDataPoint>, metric: String) {
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
    internal suspend fun syncMovieTimestamps(habitName: String) {
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
    internal suspend fun syncMovieMinutesSlot(habitName: String) {
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
     * Non-destructive preview of what a manual dated-entry refresh would do.
     * Shown to the user for confirmation BEFORE any data is overwritten.
     * Mirrors [com.example.tail.data.backup.HabitRestorePreview].
     */
    data class DatedEntryRefreshPreview(
        /** Name of the habit being refreshed. */
        val habitName: String,
        /** Total (sum over all dates) currently stored for this habit. */
        val currentTotal: Int,
        /** Total after applying the freshly parsed file counts. */
        val newTotal: Int,
        /** Number of dated entries (days) currently stored for this habit. */
        val currentDayCount: Int,
        /** Number of dated entries (days) in the freshly parsed file. */
        val newDayCount: Int,
        /** Dates present in the file but not in the DB: date → new count. */
        val addedDates: List<Pair<String, Int>>,
        /** Dates present in the DB but not in the file: date → old count. */
        val removedDates: List<Pair<String, Int>>,
        /** Dates whose count differs: (date, old count, new count). */
        val changedDates: List<Triple<String, Int, Int>>,
        /** The freshly parsed date → count map that confirmation would apply. */
        val newCounts: Map<String, Int>
    ) {
        val totalDelta: Int get() = newTotal - currentTotal
        val hasChanges: Boolean get() =
            addedDates.isNotEmpty() || removedDates.isNotEmpty() || changedDates.isNotEmpty()

        companion object {
            /** Pure diff between the stored [current] map and the [parsed] file map. */
            fun diff(
                habitName: String,
                current: Map<String, Int>,
                parsed: Map<String, Int>
            ): DatedEntryRefreshPreview {
                val added = (parsed.keys - current.keys)
                    .map { it to parsed.getValue(it) }
                    .sortedBy { it.first }
                val removed = (current.keys - parsed.keys)
                    .map { it to current.getValue(it) }
                    .sortedBy { it.first }
                val changed = current.keys.intersect(parsed.keys)
                    .filter { current.getValue(it) != parsed.getValue(it) }
                    .map { Triple(it, current.getValue(it), parsed.getValue(it)) }
                    .sortedBy { it.first }
                return DatedEntryRefreshPreview(
                    habitName = habitName,
                    currentTotal = current.values.sum(),
                    newTotal = parsed.values.sum(),
                    currentDayCount = current.size,
                    newDayCount = parsed.size,
                    addedDates = added,
                    removedDates = removed,
                    changedDates = changed,
                    newCounts = parsed
                )
            }
        }
    }

    /** Non-null while a dated-entry refresh confirmation dialog is showing. */
    internal val _datedEntryRefreshPreview = MutableStateFlow<DatedEntryRefreshPreview?>(null)
    val datedEntryRefreshPreview: StateFlow<DatedEntryRefreshPreview?> = _datedEntryRefreshPreview.asStateFlow()

    /** Status / error message for the most recent dated-entry refresh. */
    internal val _datedEntryRefreshStatus = MutableStateFlow<String?>(null)
    val datedEntryRefreshStatus: StateFlow<String?> = _datedEntryRefreshStatus.asStateFlow()

    /**
     * Re-parses the dated-entry source file linked to [habitName] (ignoring the
     * file-size change check used by the automatic sync) and publishes a
     * non-destructive [DatedEntryRefreshPreview] via [datedEntryRefreshPreview]
     * so the UI can show a confirmation dialog. Does NOT modify any data.
     */
    fun previewDatedEntryRefresh(habitName: String) {
        val uriStr = _settings.value.datedEntryFileUris[habitName]
        if (uriStr.isNullOrEmpty()) {
            _datedEntryRefreshStatus.value = "No source file linked for '$habitName'."
            return
        }
        viewModelScope.launch {
            try {
                val uri = Uri.parse(uriStr)
                val size = withContext(Dispatchers.IO) {
                    datedEntryRepo.getFileSize(uri, context)
                }
                val parsed = withContext(Dispatchers.IO) {
                    datedEntryRepo.parseFile(uri, context)
                }
                if (parsed.isEmpty() && size > 0) {
                    _datedEntryRefreshStatus.value =
                        "Could not read the linked file for '$habitName'."
                    return@launch
                }
                val current = cachedPhoneDb[habitName] ?: emptyMap()
                _datedEntryRefreshPreview.value =
                    DatedEntryRefreshPreview.diff(habitName, current, parsed)
                _datedEntryRefreshStatus.value = null
            } catch (e: Exception) {
                _datedEntryRefreshStatus.value = "Refresh preview failed: ${e.message}"
            }
        }
    }

    /** Dismisses the pending dated-entry refresh confirmation (no data is changed). */
    fun cancelDatedEntryRefresh() {
        _datedEntryRefreshPreview.value = null
    }

    /** Clears the transient dated-entry refresh status message. */
    fun clearDatedEntryRefreshStatus() {
        _datedEntryRefreshStatus.value = null
    }

    /**
     * Applies the pending dated-entry refresh: REPLACES the habit's entire
     * date → count map with the parsed file counts from the preview (so dates
     * missing from the file are removed, not merely updated), persists the DB,
     * and records the current file size so the next foreground sync is a no-op.
     *
     * Only the refreshed habit's own values are overwritten — conditionally
     * linked habits are left untouched.
     */
    fun applyDatedEntryRefresh() {
        val preview = _datedEntryRefreshPreview.value
        if (preview == null) {
            _datedEntryRefreshStatus.value = "Nothing to refresh."
            return
        }
        _datedEntryRefreshPreview.value = null
        val habitName = preview.habitName
        val uriStr = _settings.value.datedEntryFileUris[habitName]
        val phoneUriStr = _settings.value.fileUri
        _datedEntryRefreshStatus.value = "Refreshing '$habitName'…"
        viewModelScope.launch {
            try {
                val mutableDb = cachedPhoneDb.toMutableMap()
                mutableDb[habitName] = preview.newCounts.toSortedMap()
                cachedPhoneDb = mutableDb

                // Record the file size so the next foreground sync sees no change.
                val currentSize = if (uriStr != null) {
                    withContext(Dispatchers.IO) {
                        datedEntryRepo.getFileSize(Uri.parse(uriStr), context)
                    }
                } else -1L
                val newSizes = _settings.value.datedEntryFileSizes.toMutableMap()
                if (currentSize >= 0) newSizes[habitName] = currentSize
                _settings.value = _settings.value.copy(datedEntryFileSizes = newSizes)

                rebuildHabitList()

                if (phoneUriStr.isNotEmpty()) {
                    withContext(Dispatchers.IO) {
                        habitsRepo.persistDatabase(Uri.parse(phoneUriStr), context, mutableDb)
                        settingsRepo.saveDatedEntryFileSizes(newSizes)
                    }
                }
                val affected = preview.addedDates.size + preview.removedDates.size +
                        preview.changedDates.size
                _datedEntryRefreshStatus.value =
                    "Refreshed '$habitName' ($affected date(s) updated)."
                Log.d(TAG, "DatedEntry[$habitName]: manual refresh applied ($affected dates)")
            } catch (e: Exception) {
                Log.e(TAG, "DatedEntry[$habitName]: refresh failed: ${e.message}")
                _datedEntryRefreshStatus.value = "Refresh failed: ${e.message}"
            }
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
    internal var lastInitializedDate: LocalDate? = null

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
        // App-stats record catch-up: keep the daily evening alarm alive and,
        // if today's 20:30 check never fired (phone off, process killed),
        // run it now. checkAndPost is idempotent per day (near-record ids
        // are per-day; broken records are gated by persisted episode flags).
        viewModelScope.launch {
            try {
                com.example.tail.notify.AppStatsAlarmReceiver.schedule(context)
                com.example.tail.notify.AppStatsRecordNotifier.checkAndPost(context)
            } catch (e: Exception) {
                Log.w(TAG, "App-stats record catch-up failed: ${e.message}")
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
    internal suspend fun syncAllDatedEntries(forceReparse: Boolean) {
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
    internal suspend fun syncSingleDatedEntry(
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
    internal val chessComValueLabels = mapOf(
        GRAPH_METRIC_VALUE1 to "Minutes",
        GRAPH_METRIC_VALUE2 to "Games",
        GRAPH_METRIC_VALUE3 to "Result"
    )

    /**
     * Labels set by an earlier auto-label scheme (Value1 = Games,
     * Value2 = Minutes). Still migrated to the current labels on sync so the
     * re-mapped slots are named correctly.
     */
    internal val legacyChessComValueLabels = mapOf(
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
    internal suspend fun ensureChessComValueLabels(s: AppSettings): AppSettings? {
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
    internal fun removeChessComValueLabels(habitName: String) {
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
    internal fun startChessComPolling() {
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
    internal suspend fun backfillChessComHistoryOnce() {
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
    internal fun stopChessComPolling() {
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
    internal suspend fun syncChessComCurrentMonth() {
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
            // Bridge credentials are auto-derived from the Garmin settings
            // (same as movies/PC-widget) — no separate configuration exists.
            val bridgeConn = getBridgeConnection()
            val summary = ChessDeferredGameReconciler.reconcilePending(
                context, s.chessComUsername,
                bridge = bridgeConn?.let {
                    com.example.tail.widget.ChessAnalysisFetcher.BridgeCredentials(
                        url = it.first, token = it.second
                    )
                }
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
    internal suspend fun resetChessComHabitData(s: AppSettings) {
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
    internal suspend fun refreshCachedDbFromDisk(fileUri: String) {
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
    internal suspend fun applyChessComData(
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
    internal suspend fun resetGithubHabitData(habitName: String, s: AppSettings) {
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
    internal suspend fun applyGithubData(
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
    internal fun startGithubPolling() {
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
    internal fun stopGithubPolling() {
        githubPollingJob?.cancel()
        githubPollingJob = null
    }

    /**
     * Fetches recent commits for all GitHub-linked habits and applies new data.
     * Called periodically by the polling loop.
     */
    internal suspend fun syncGithubRecent() {
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
    internal fun startGarminPolling() {
        // Cancel any existing polling job
        garminPollingJob?.cancel()
        garminPollingJob = viewModelScope.launch {
            while (true) {
                syncGarminCurrentMonth()
                delay(GARMIN_POLL_INTERVAL_MS)
            }
        }
    }


    internal val _wallpaperStatus = MutableStateFlow("")

    /** Human-readable result of the most recent manual wallpaper apply. */
    val wallpaperStatus: StateFlow<String> = _wallpaperStatus

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


    internal val _chessAnalysisTestStatus = kotlinx.coroutines.flow.MutableStateFlow("")
    val chessAnalysisTestStatus: kotlinx.coroutines.flow.StateFlow<String> =
        _chessAnalysisTestStatus

    val locationDataVersion: Int
        get() = locationRepo.dataVersion

    // ── Secondary locations ─────────────────────────────────────────────────


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
            onDatabaseChanged = { refreshAfterExternalDbChange() },
            mealHabitsProvider = { _settings.value.mealHabits }
        )
    }

    internal val mealTimeFmt = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")

}

class HabitViewModelFactory(
    internal val habitsRepo: HabitsRepository,
    internal val settingsRepo: SettingsRepository,
    internal val textInputRepo: TextInputRepository,
    internal val datedEntryRepo: DatedEntryRepository,
    internal val subtypeDataRepo: SubtypeDataRepository,
    internal val timedDataRepo: TimedDataRepository,
    internal val context: Context,
    internal val backupManager: BackupManager? = null
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HabitViewModel(
            habitsRepo, settingsRepo, textInputRepo, datedEntryRepo,
            subtypeDataRepo, timedDataRepo, context, backupManager
        ) as T
    }
}
