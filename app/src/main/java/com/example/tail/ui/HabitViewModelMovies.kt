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

/** Toggles whether [habitName] is linked to the movie bridge. */
fun HabitViewModel.toggleBridgeMovieHabit(habitName: String) {
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


/**
 * Fetches the latest movie suggestion from the desktop bridge.
 * Called when a movie-linked text-input habit is tapped.
 *
 * @param excludeTitles Titles to skip (e.g. entries already logged today)
 * @param onResult Called with the suggested movie (or null if bridge is
 *                 unreachable / no data). Runs on the main thread.
 */
fun HabitViewModel.fetchMovieSuggestion(
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


/** Clears the current movie suggestion (call when the dialog is dismissed). */
fun HabitViewModel.clearMovieSuggestion() {
    _movieSuggestion.value = null
}

// ── Movie confirmation flash (auto-prompt on app open) ────────────────────

/**
 * One emitted step of the increment-dialog suggestion pipeline. The
 * dialog opens instantly and updates as these arrive.
 *
 * @param movie Newest not-yet-logged cached movie, or null.
 * @param recent The last watched movies (picker list), newest first.
 * @param loading True while a bridge refresh is still in flight.
 */
data class MovieSuggestion(
    val movie: BridgeMovie?,
    val recent: List<BridgeMovie>,
    val loading: Boolean
)

/**
 * Keeps the open increment dialog's movie suggestion fed: resolves from
 * the phone-local cache instantly (no network), then refreshes the cache
 * from the bridge in the background and re-emits when newer data lands.
 * Emits at most twice — once from cache, once after the refresh.
 */


/**
 * Keeps the open increment dialog's movie suggestion fed: resolves from
 * the phone-local cache instantly (no network), then refreshes the cache
 * from the bridge in the background and re-emits when newer data lands.
 * Emits at most twice — once from cache, once after the refresh.
 */
fun HabitViewModel.streamMovieSuggestion(
    habitName: String,
    date: LocalDate,
    onUpdate: (MovieSuggestion) -> Unit
) {
    loadTextEntriesWithTimestamps(habitName, date) { entries ->
        val excludeKeys = entries
            .map { OmdbService.parseTitle(it.second).cacheKey }
            .toSet()
        viewModelScope.launch {
            val cached = loadMovieCacheOnce()
            val stale = !MovieCacheStore.Cached(cached, movieCacheFetchedAt).isFresh
            onUpdate(
                MovieSuggestion(
                    movie = suggestMovieFromCache(cached, excludeKeys),
                    recent = cached.take(5),
                    loading = stale
                )
            )
            if (!stale) return@launch
            val fresh = refreshMovieCacheFromBridge()
            val final = if (fresh.isNullOrEmpty()) cached else fresh
            onUpdate(
                MovieSuggestion(
                    movie = suggestMovieFromCache(final, excludeKeys),
                    recent = final.take(5),
                    loading = false
                )
            )
        }
    }
}

/**
 * Looks for an unconfirmed recently-watched desktop movie to ask about in
 * the bottom flash / notification center when the app opens.
 *
 * Cache-first: the check runs instantly against the phone-local cache
 * (kept fresh by the background sync worker), then the cache is refreshed
 * from the bridge and the check re-runs — so a movie that synced moments
 * after the last cache write is still caught, and a transient network
 * failure at app open no longer swallows the ask. One delayed retry
 * covers the "app opened before Wi-Fi reconnected" case.
 */


/**
 * Looks for an unconfirmed recently-watched desktop movie to ask about in
 * the bottom flash / notification center when the app opens.
 *
 * Cache-first: the check runs instantly against the phone-local cache
 * (kept fresh by the background sync worker), then the cache is refreshed
 * from the bridge and the check re-runs — so a movie that synced moments
 * after the last cache write is still caught, and a transient network
 * failure at app open no longer swallows the ask. One delayed retry
 * covers the "app opened before Wi-Fi reconnected" case.
 */
fun HabitViewModel.prepareMoviePrompt(
    habitName: String,
    date: LocalDate,
    onResult: (BridgeMovie?) -> Unit
) {
    if (!_settings.value.bridgeEnabled) {
        onResult(null)
        return
    }
    viewModelScope.launch {
        // 1) Instant check on the local cache.
        val cached = loadMovieCacheOnce()
        val askedFromCache = com.example.tail.notify.HabitAsks
            .checkAndPostMovieAsk(context, habitName, cached)
        if (askedFromCache != null) {
            onResult(askedFromCache)
            return@launch
        }
        // 2) Refresh from the bridge and re-check. The typical failure is
        //    transient: the app is opened seconds after unlock, while
        //    Wi-Fi/Tailscale is still reconnecting — so retry with
        //    backoff (8 s, 16 s, 32 s) instead of giving up after one
        //    attempt and silently dropping the ask for this session.
        var fresh = refreshMovieCacheFromBridge()
        var attempt = 0
        while (fresh == null && attempt < 3) {
            kotlinx.coroutines.delay(8_000L shl attempt)
            fresh = refreshMovieCacheFromBridge()
            attempt++
        }
        if (fresh == null) {
            Log.w(TAG, "Movie prompt: bridge unreachable after $attempt retries — " +
                "no ask this session (background worker will retry)")
            onResult(null)
            return@launch
        }
        onResult(
            com.example.tail.notify.HabitAsks
                .checkAndPostMovieAsk(context, habitName, fresh)
        )
    }
}

/**
 * The text logged for a confirmed movie: the title plus its "(N min)"
 * watch length when the bridge knows it, so the schedule block sizes to
 * the film and the minutes slot fills from the annotation.
 */


/**
 * The text logged for a confirmed movie: the title plus its "(N min)"
 * watch length when the bridge knows it, so the schedule block sizes to
 * the film and the minutes slot fills from the annotation.
 */
internal fun HabitViewModel.annotatedMovieTitle(movie: BridgeMovie): String =
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


/**
 * Logs [movie] as a watched entry for [habitName] — the flash's Yes action
 * and the auto-confirm timeout. The entry is timestamped at the movie's
 * last session start when that was today, otherwise at the current time.
 * Marks the prompt as handled so it is never asked again.
 *
 * @param onLogged Called with the "HH:mm:ss" entry time used (main thread).
 */
fun HabitViewModel.confirmMoviePrompt(
    habitName: String,
    movie: BridgeMovie,
    onLogged: (String) -> Unit = {}
) {
    markMoviePromptHandled(movie)
    val entryTime = com.example.tail.notify.HabitAsks.moviePromptEntryTime(movie)
    saveTextEntries(habitName, listOf(annotatedMovieTitle(movie)), null, entryTime)
    onLogged(entryTime.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")))
}

/** Dismisses the movie prompt without logging anything (the No action). */


/** Dismisses the movie prompt without logging anything (the No action). */
fun HabitViewModel.dismissMoviePrompt(movie: BridgeMovie) {
    markMoviePromptHandled(movie)
}

/** Persists the handled marker so the same movie is never re-asked. */


/** Persists the handled marker so the same movie is never re-asked. */
internal fun HabitViewModel.markMoviePromptHandled(movie: BridgeMovie) {
    viewModelScope.launch {
        try {
            val current = settingsRepo.getMoviePromptHandled()
            settingsRepo.saveMoviePromptHandled(
                current + com.example.tail.notify.HabitAsks.moviePromptMarker(movie)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save movie prompt marker: ${e.message}")
        }
    }
}

/** Persists a raw handled marker (used when answering from a stored ask). */


/** Persists a raw handled marker (used when answering from a stored ask). */
internal fun HabitViewModel.markMovieMarkerHandled(marker: String) {
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


/** Tests the bridge connection. */
fun HabitViewModel.testBridgeConnection() {
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


/**
 * Diagnostics for the v3 post-game audit: verifies the full
 * phone → bridge → Stockfish pipeline with a tiny built-in test game.
 */
fun HabitViewModel.testChessAnalysisPipeline() {
    viewModelScope.launch {
        _chessAnalysisTestStatus.value = "Testing pipeline…"
        val conn = getBridgeConnection()
        if (conn == null) {
            _chessAnalysisTestStatus.value =
                "❌ Bridge not configured — it is auto-derived from the " +
                    "Garmin connection (Settings → Garmin); set that up once " +
                    "and every bridge feature (movies, PC widget, chess " +
                    "analysis) uses it"
            return@launch
        }
        _chessAnalysisTestStatus.value = try {
            com.example.tail.widget.ChessAnalysisFetcher
                .testPipeline(conn.first, conn.second)
        } catch (e: Exception) {
            "❌ Test failed: ${e.message}"
        }
    }
}

// ── OMDb / IMDb ratings methods ──────────────────────────────────────────

/** Saves the OMDb API key. */


/** Saves the OMDb API key. */
fun HabitViewModel.saveOmdbApiKey(apiKey: String) {
    viewModelScope.launch {
        settingsRepo.saveOmdbApiKey(apiKey.trim())
        _settings.value = _settings.value.copy(omdbApiKey = apiKey.trim())
    }
}

/** Returns true if [habitName] is a movie-bridge-linked text-input habit. */


/** Returns true if [habitName] is a movie-bridge-linked text-input habit. */
fun HabitViewModel.isMovieBridgeHabit(habitName: String): Boolean {
    val s = _settings.value
    return habitName in s.bridgeMovieHabits && s.bridgeEnabled
}

/**
 * Returns true if IMDb ratings are available for [habitName]:
 * the habit is bridge-linked AND an OMDb API key is configured.
 */


/**
 * Returns true if IMDb ratings are available for [habitName]:
 * the habit is bridge-linked AND an OMDb API key is configured.
 */
fun HabitViewModel.hasImdbRatings(habitName: String): Boolean {
    return isMovieBridgeHabit(habitName) && _settings.value.omdbApiKey.isNotBlank()
}

/**
 * Looks up the cached IMDb rating for a raw text entry.
 * Returns the rating as a display string (e.g. "8.8") or null if not cached.
 */


/**
 * Looks up the cached IMDb rating for a raw text entry.
 * Returns the rating as a display string (e.g. "8.8") or null if not cached.
 */
suspend fun HabitViewModel.getImdbRatingForText(rawText: String): String? {
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
internal suspend fun HabitViewModel.fetchAndCacheImdbRating(parsed: ParsedTitle, needRuntime: Boolean = false): Int? {
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


/**
 * Recomputes the daily average IMDb rating for a movie habit and stores
 * it as a secondary value (x 10) in the habits database.
 */
internal suspend fun HabitViewModel.updateImdbSecondaryValues(habitName: String) {
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


/**
 * Called when a new movie text entry is saved. Fetches the IMDb rating
 * asynchronously (if not cached) and updates the secondary values.
 */
fun HabitViewModel.triggerImdbFetchForEntry(habitName: String, text: String) {
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


/**
 * Fetches IMDb ratings for all existing movie entries that haven't been
 * looked up yet (the "backlog"). Respects the daily API limit of 990 calls.
 *
 * @param retryFailed When true, first clears all cached "no rating"
 *        entries so previously-failed titles (including ones poisoned by
 *        transient errors under the old logic) are fetched again.
 */
fun HabitViewModel.fetchImdbBacklog(retryFailed: Boolean = false, onProgress: ((String) -> Unit)? = null) {
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


/**
 * Fetches the desktop bridge's recent watch history and indexes the
 * exact file-probed durations by parsed title: cacheKey → (watch day →
 * total minutes). Empty when the bridge is unreachable — the caller
 * then falls back to OMDb runtimes.
 */
internal suspend fun HabitViewModel.fetchBridgeDurations(): Map<String, Map<String, Int>> {
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


/**
 * Looks up a title's bridge minutes for [date], tolerating a ±1 day
 * offset (a film watched late at night can be confirmed the next
 * morning, landing the entry on a different day than the bridge's).
 */
internal fun HabitViewModel.bridgeMinutesFor(
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
fun HabitViewModel.fetchMovieMinutesBacklog(onProgress: ((String) -> Unit)? = null) {
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


/**
 * Runs [fetchMovieMinutesBacklog] once per day, automatically after
 * load, so entries confirmed without a length get annotated (bridge
 * file durations first, OMDb fallback) and the minutes slot fills
 * without user action.
 */
internal suspend fun HabitViewModel.maybeRunMovieMinutesBackfill() {
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


/** Returns the remaining OMDb API calls for today. */
suspend fun HabitViewModel.getOmdbRemainingCalls(): Int = imdbCache.remainingCalls()

/**
 * Returns a map of movie text entry to IMDb rating display string for all
 * entries on a given date. Used by the graph popup.
 */


/**
 * Returns a map of movie text entry to IMDb rating display string for all
 * entries on a given date. Used by the graph popup.
 */
suspend fun HabitViewModel.getImdbRatingsForDate(
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


/**
 * Imports meditation data from a JSON file (meditation_output.json format).
 * Merges minutes → primary "Meditations" slot, sessions → secondary slot.
 * Uses max() merge so existing higher values are never overwritten.
 */
fun HabitViewModel.importMeditationData(jsonFile: File, onComplete: (String) -> Unit = {}) {
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
