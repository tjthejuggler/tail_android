package com.example.tail.notify

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.BridgeMovie
import com.example.tail.data.HabitNotification
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.NotificationStore
import com.example.tail.data.OmdbService
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.ui.HabitIncrementBus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

private const val TAG = "HabitAsks"

/**
 * Shared logic for the habit-ask notification system, used by both the
 * background receivers (system-notification answers, scheduled alarms) and
 * the in-app catch-up path in [com.example.tail.ui.HabitViewModel].
 */
object HabitAsks {

    /** Ask id for a movie ask, embedding the existing handled-marker. */
    fun movieAskId(marker: String): String = "movie:$marker"

    /**
     * Posts an informational notice into the notification system (in-app
     * center + system notification). Unlike asks it has no Yes/No effect —
     * acknowledging it just removes it everywhere. Used for things the user
     * must not miss, e.g. quick-capture failures that previously disappeared
     * with a transient toast.
     *
     * @param id Stable unique id; a duplicate id is not re-added (no-op).
     * @param title Headline, e.g. "📸 Quick capture failed".
     * @param message Body text explaining what failed.
     * @param habitLabel Small label shown in the center (defaults to "Notice").
     */
    suspend fun postInfo(
        appContext: Context,
        id: String,
        title: String,
        message: String,
        habitLabel: String = "Notice"
    ): HabitNotification {
        val notice = HabitNotification(
            id = id,
            habitName = habitLabel,
            type = HabitNotification.TYPE_INFO,
            title = title,
            question = message,
            createdAtMillis = System.currentTimeMillis()
        )
        NotificationStore(appContext).add(notice)
        HabitNotifier.postAsk(appContext, notice)
        Log.i(TAG, "Posted info notification '$id': $title")
        return notice
    }

    /**
     * Fires the scheduled daily ask for [habitName]: creates the store record,
     * posts the system notification and marks the habit as fired today.
     * Skips (returns null) when this habit already fired today — this is what
     * keeps alarms, boot catch-up and app-open catch-up from double-asking.
     *
     * Also skips max-1 habits that are already incremented today: "Yes" would
     * be capped away by [applyAnswer] and "No" changes nothing, so the ask is
     * moot. The schedule is still marked as fired so catch-up paths don't
     * re-check (and re-load the habits file) for the rest of the day.
     */
    suspend fun fireScheduledAsk(
        appContext: Context,
        habitName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): HabitNotification? {
        val store = NotificationStore(appContext)
        val today = LocalDate.now().toString()
        val lastFired = store.scheduleLastFired()[habitName]
        if (lastFired == today) return null

        val settings = SettingsRepository(appContext).settingsFlow.first()
        if (habitName in settings.maxOneHabits && settings.fileUri.isNotEmpty()) {
            val todayCount = HabitsRepository()
                .loadDatabase(Uri.parse(settings.fileUri), appContext)
                .get(habitName)?.get(today) ?: 0
            if (todayCount >= 1) {
                store.setScheduleFired(habitName, today)
                Log.i(TAG, "Skipping scheduled ask for '$habitName' — already at max 1 today")
                return null
            }
        }

        store.setScheduleFired(habitName, today)
        val ask = HabitNotification(
            id = HabitNotification.scheduleId(habitName, today),
            habitName = habitName,
            type = HabitNotification.TYPE_SCHEDULE,
            title = habitName,
            question = "Did you do \"$habitName\"?",
            createdAtMillis = nowMillis
        )
        store.add(ask)
        HabitNotifier.postAsk(appContext, ask)
        Log.i(TAG, "Fired scheduled ask for '$habitName'")
        return ask
    }

    /**
     * Applies the effect of answering [ask] from a background context
     * (system-notification action). The caller is responsible for cancelling
     * the system notification, and must only remove the record from the store
     * when this returns true (see [NotificationActionReceiver]).
     *
     * - Movie + Yes  → appends the title as a text entry at the stored entry
     *   time AND increments the habit count (with retry — see below)
     * - Movie + No   → nothing (marker still persisted so it is never re-asked)
     * - Schedule + Yes → increments today's count by 1 (respecting max-1)
     * - Schedule + No  → nothing
     *
     * ROBUSTNESS: the SAF-backed habits/text files can transiently fail to
     * load or write (provider busy, sync in flight). Both effects are
     * therefore retried with backoff and VERIFIED by reading the file back —
     * a write that neither threw nor landed is treated as a failure. The
     * handled marker is only persisted once the movie is fully logged, so a
     * half-applied answer can be retried instead of being silently dropped.
     *
     * @return true when the ask is fully resolved and its store record can be
     *   removed; false when the effect failed and the ask must stay so the
     *   user can answer it again (the caller re-posts the notification).
     */
    suspend fun applyAnswer(appContext: Context, ask: HabitNotification, yes: Boolean): Boolean {
        // Informational notices carry no effect — the caller removes the
        // record and cancels the system notification (dismiss-everywhere).
        if (ask.type == HabitNotification.TYPE_INFO) return true
        val settingsRepo = SettingsRepository(appContext)
        if (ask.type == HabitNotification.TYPE_MOVIE) {
            val marker = ask.id.removePrefix("movie:")
            /** Persists the handled marker so the movie is never re-asked. */
            suspend fun markHandled() {
                try {
                    val handled = settingsRepo.getMoviePromptHandled()
                    settingsRepo.saveMoviePromptHandled(handled + marker)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save movie handled marker: ${e.message}")
                }
            }
            if (!yes) {
                markHandled()
                return true
            }
            val settings = settingsRepo.settingsFlow.first()
            val uriStr = settings.textInputFileUris[ask.habitName]
            if (uriStr.isNullOrEmpty()) {
                Log.w(TAG, "No text log URI for '${ask.habitName}' — cannot log movie")
                postInfo(
                    appContext, "movie-log-failed:${ask.id}",
                    "🎬 Movie not logged",
                    "Could not log '${ask.title}' — no text log file is configured " +
                        "for the habit '${ask.habitName}'.",
                    ask.habitName
                )
                markHandled()
                return true
            }
            val (payloadTime, payloadMinutes) = HabitNotification.parseMoviePayload(ask.payload)
            val time = payloadTime?.let { parseTime(it) } ?: LocalTime.now()
            // Carry the watch length onto the logged entry so the minutes
            // slot fills from the annotation at the next sync.
            val text = if (payloadMinutes > 0) "${ask.title} ($payloadMinutes min)" else ask.title
            val entryUri = Uri.parse(uriStr)
            var entryLogged = false
            var lastEntryError: Exception? = null
            for (attempt in 1..ANSWER_RETRY_ATTEMPTS) {
                try {
                    TextInputRepository().appendTextEntry(
                        entryUri, appContext, text, null, time, ask.habitName
                    )
                    // Verify: the entry must actually be on disk — a silently
                    // dropped SAF write must not count as logged.
                    val dayPrefix = LocalDate.now().toString()
                    val onDisk = TextInputRepository()
                        .loadTextLog(entryUri, appContext)
                        .filterKeys { it.startsWith(dayPrefix) }
                        .values.map { OmdbService.parseTitle(it).cacheKey }
                    if (OmdbService.parseTitle(text).cacheKey in onDisk) {
                        entryLogged = true
                        break
                    }
                    Log.w(TAG, "Movie entry verify failed (attempt $attempt) — write did not land")
                } catch (e: Exception) {
                    lastEntryError = e
                    Log.e(TAG, "Failed to log movie answer (attempt $attempt): ${e.message}", e)
                }
                if (attempt < ANSWER_RETRY_ATTEMPTS) delay(ANSWER_RETRY_BACKOFF_MS shl (attempt - 1))
            }
            if (!entryLogged) {
                // Nothing was logged — keep the ask so the user can answer it
                // again later; do NOT mark handled or we lose the movie.
                Log.e(TAG, "Movie entry failed after $ANSWER_RETRY_ATTEMPTS attempts — keeping ask", lastEntryError)
                return false
            }
            Log.i(TAG, "Logged movie '${ask.title}' for '${ask.habitName}' from system notification")

            // Mirror the in-app confirm path (HabitViewModel.saveTextEntry):
            // a confirmed movie also increments the habit count so the day
            // registers as watched, records the increment timestamp and
            // notifies any running UI. IMDb rating/runtime enrichment can be
            // filled in afterwards via the IMDb backlog buttons in settings.
            val habitsUriStr = settings.fileUri
            if (habitsUriStr.isEmpty()) {
                Log.w(TAG, "No habits file URI configured — cannot increment '${ask.habitName}'")
                postInfo(
                    appContext, "movie-inc-failed:${ask.id}",
                    "🎬 Movie logged, habit not incremented",
                    "'${ask.title}' was logged but the habit '${ask.habitName}' could not " +
                        "be incremented — no habits file is configured. Increment it manually.",
                    ask.habitName
                )
                markHandled()
                return true
            }
            val habitsUri = Uri.parse(habitsUriStr)
            // Respect the "max 1" cap: skip when already done today.
            if (ask.habitName in settings.maxOneHabits) {
                val db = HabitsRepository().loadDatabase(habitsUri, appContext)
                val todayCount = db[ask.habitName]?.get(LocalDate.now().toString()) ?: 0
                if (todayCount >= 1) {
                    Log.i(TAG, "Skipping movie increment for '${ask.habitName}' — already at max 1 today")
                    markHandled()
                    return true
                }
            }
            val incremented = incrementHabitVerified(habitsUri, appContext, ask.habitName)
            if (incremented) {
                HabitIncrementBus.emit(ask.habitName)
                try {
                    HabitTimestampRepository(appContext).addTimestamp(ask.habitName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record timestamp for '${ask.habitName}': ${e.message}")
                }
                Log.i(TAG, "Incremented '${ask.habitName}' for confirmed movie")
                try {
                    com.example.tail.ipc.HabitIncrementAnnouncer.announce(appContext, ask.habitName, 1)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to announce increment for '${ask.habitName}': ${e.message}")
                }
                markHandled()
                return true
            }
            // The title IS logged, so re-asking would duplicate the text
            // entry — mark handled, but surface the missing increment loudly
            // instead of silently dropping it (the old behaviour).
            Log.e(TAG, "Increment for '${ask.habitName}' failed after $ANSWER_RETRY_ATTEMPTS attempts")
            postInfo(
                appContext, "movie-inc-failed:${ask.id}",
                "🎬 Movie logged, habit not incremented",
                "'${ask.title}' was logged but incrementing '${ask.habitName}' failed " +
                    "repeatedly. Please increment it manually.",
                ask.habitName
            )
            markHandled()
            return true
        }

        if (!yes) return true
        val settings = settingsRepo.settingsFlow.first()
        val uriStr = settings.fileUri
        if (uriStr.isEmpty()) {
            Log.w(TAG, "No habits file URI configured — cannot increment '${ask.habitName}'")
            return true
        }
        val uri = Uri.parse(uriStr)
        val habitsRepo = HabitsRepository()
        // Respect the "max 1" cap: skip when already done today.
        if (ask.habitName in settings.maxOneHabits) {
            val db = habitsRepo.loadDatabase(uri, appContext)
            val todayCount = db[ask.habitName]?.get(LocalDate.now().toString()) ?: 0
            if (todayCount >= 1) {
                Log.i(TAG, "Skipping answer increment for '${ask.habitName}' — already at max 1 today")
                return true
            }
        }
        if (!incrementHabitVerified(uri, appContext, ask.habitName)) {
            Log.e(TAG, "Schedule-answer increment for '${ask.habitName}' failed — keeping ask")
            return false
        }
        HabitIncrementBus.emit(ask.habitName)
        try {
            HabitTimestampRepository(appContext).addTimestamp(ask.habitName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record timestamp for '${ask.habitName}': ${e.message}")
        }
        Log.i(TAG, "Incremented '${ask.habitName}' from notification answer")
        try {
            com.example.tail.ipc.HabitIncrementAnnouncer.announce(appContext, ask.habitName, 1)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to announce increment for '${ask.habitName}': ${e.message}")
        }
        return true
    }

    /** Retry attempts for notification-answer effects (text log / increment). */
    private const val ANSWER_RETRY_ATTEMPTS = 3

    /** Base backoff between retries; doubles each attempt (1 s, 2 s, …). */
    private const val ANSWER_RETRY_BACKOFF_MS = 1000L

    /**
     * Increments today's count for [habitName] with retries and read-back
     * verification: each attempt performs the atomic read-modify-write of
     * [HabitsRepository.incrementHabit], then reloads the file and confirms
     * today's count actually increased. Transient SAF failures (which
     * previously escaped as exceptions and silently lost the increment) are
     * retried with backoff instead.
     *
     * @return true when the increment is verified on disk.
     */
    private suspend fun incrementHabitVerified(
        uri: Uri,
        appContext: Context,
        habitName: String
    ): Boolean {
        val repo = HabitsRepository()
        val today = LocalDate.now().toString()
        for (attempt in 1..ANSWER_RETRY_ATTEMPTS) {
            try {
                val before = repo.loadDatabase(uri, appContext)[habitName]?.get(today) ?: 0
                repo.incrementHabit(uri, appContext, habitName, 1)
                val after = repo.loadDatabase(uri, appContext)[habitName]?.get(today) ?: 0
                if (after > before) return true
                Log.w(TAG, "Increment verify failed for '$habitName' (attempt $attempt): " +
                    "before=$before after=$after — write did not land")
            } catch (e: Exception) {
                Log.e(TAG, "Increment attempt $attempt for '$habitName' failed: ${e.message}", e)
            }
            if (attempt < ANSWER_RETRY_ATTEMPTS) delay(ANSWER_RETRY_BACKOFF_MS shl (attempt - 1))
        }
        return false
    }

    private fun parseTime(hms: String): LocalTime? {
        return try {
            val parts = hms.split(":")
            LocalTime.of(
                parts.getOrNull(0)?.toIntOrNull() ?: return null,
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
                parts.getOrNull(2)?.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }

    // ── Movie-ask detection (shared by app-open path and background sync) ────

    /** Max age (ms) of a desktop-detected movie before we stop asking about it. */
    const val MOVIE_PROMPT_MAX_AGE_MS = 48 * 60 * 60 * 1000L

    /**
     * Up to how many new asks one check may post. Bounded so a long unwatched
     * backlog (e.g. first sync after days away) cannot flood the notification
     * shade in a single pass; the rest are picked up by later checks.
     */
    const val MAX_NEW_ASKS_PER_CHECK = 5

    /**
     * Clock skew tolerated by [isMoviePromptRecent]: the desktop's clock can
     * be slightly ahead of the phone's, making a just-started movie look like
     * it starts "in the future" (negative age). Anything within this window
     * still counts as recent.
     */
    private val CLOCK_SKEW_TOLERANCE_MS = 10 * 60 * 1000L

    /**
     * Stable marker identifying a prompted movie ("title@watchDate"). Keyed on
     * the watch DAY (not lastWatched) because the desktop watcher can merge or
     * extend sessions of the same play, which shifts lastWatched — the day
     * never changes, so an answered movie is never re-asked.
     */
    fun moviePromptMarker(movie: BridgeMovie): String {
        val day = movie.date.ifBlank { movie.lastWatched.take(10) }
        return "${movie.title.trim().lowercase()}@$day"
    }

    /** True when the movie's most recent session started within the prompt window. */
    fun isMoviePromptRecent(
        movie: BridgeMovie,
        maxAgeMs: Long = MOVIE_PROMPT_MAX_AGE_MS
    ): Boolean {
        val lastStartUnix = movie.sessions.maxOfOrNull { it.startUnix }?.takeIf { it > 0 }
        if (lastStartUnix != null) {
            val ageMs = System.currentTimeMillis() - lastStartUnix * 1000L
            // Negative age down to the skew tolerance = desktop clock slightly
            // ahead; still a just-started/ongoing watch.
            return ageMs in -CLOCK_SKEW_TOLERANCE_MS..maxAgeMs
        }
        val parsed = try {
            java.time.LocalDateTime.parse(
                movie.lastWatched,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            )
        } catch (e: Exception) { null }
        return parsed?.let {
            java.time.Duration.between(it, java.time.LocalDateTime.now()).toMillis()
                .let { age -> age in -CLOCK_SKEW_TOLERANCE_MS..maxAgeMs }
        } ?: true // unparseable → assume recent so the user still gets asked once
    }

    /**
     * Case/whitespace-insensitive title match against logged entry texts.
     * Compares parsed cache keys so entries carrying a "(N min)" length
     * annotation still match the bare suggested title.
     */
    fun titleLogged(title: String, entries: List<Pair<String, String>>): Boolean {
        val needle = OmdbService.parseTitle(title).cacheKey
        return entries.any { OmdbService.parseTitle(it.second).cacheKey == needle }
    }

    /** Entry time for a confirmed movie: its last start time today, else now. */
    fun moviePromptEntryTime(movie: BridgeMovie): LocalTime {
        val parsed = try {
            java.time.LocalDateTime.parse(
                movie.lastWatched,
                java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            )
        } catch (e: Exception) { null }
        return if (parsed != null && parsed.toLocalDate() == LocalDate.now()) {
            parsed.toLocalTime()
        } else {
            LocalTime.now()
        }
    }

    /**
     * Scans [movies] (newest-first, as served by the bridge / kept in the
     * phone-local cache) for ones worth asking about, and registers an ask in
     * the notification system (in-app center + system notification) for EVERY
     * newly askable movie — not just the first. Shared by the app-open
     * catch-up path and the background [MovieSyncWorker], so the "Watched
     * this?" notification can appear without the app being opened at all.
     *
     * A movie is askable when its most recent session started within the
     * prompt window, its handled marker is absent (never answered), no ask
     * for it is already waiting in the store, and its title is not already
     * logged for the habit on the watch day. Continuing past movies whose
     * ask already exists matters: several episodes are often watched before
     * the phone checks, and stopping at the first pending ask would starve
     * the older ones forever.
     *
     * @return The first movie an ask was newly posted for (the one the
     *   in-app flash should surface), or null when there is nothing to ask.
     */
    suspend fun checkAndPostMovieAsk(
        appContext: Context,
        habitName: String,
        movies: List<BridgeMovie>,
        maxAgeMs: Long = MOVIE_PROMPT_MAX_AGE_MS
    ): BridgeMovie? {
        if (movies.isEmpty()) return null
        val settingsRepo = SettingsRepository(appContext)
        val settings = try {
            settingsRepo.settingsFlow.first()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read settings for movie ask: ${e.message}")
            return null
        }
        val handled = try {
            settingsRepo.getMoviePromptHandled()
        } catch (e: Exception) {
            emptySet()
        }
        val store = NotificationStore(appContext)
        val pendingAskIds = try {
            store.notificationsFlow.first().map { it.id }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read pending asks: ${e.message}")
            emptySet()
        }

        var firstPosted: BridgeMovie? = null
        var postedCount = 0
        for (movie in movies) {
            if (postedCount >= MAX_NEW_ASKS_PER_CHECK) break
            if (!isMoviePromptRecent(movie, maxAgeMs)) continue
            val marker = moviePromptMarker(movie)
            if (marker in handled) continue
            // An ask for this movie is already waiting to be answered —
            // skip it but keep scanning so other watched titles get theirs.
            if (movieAskId(marker) in pendingAskIds) continue
            // Skip titles already logged on the movie's own watch day (the
            // day the ask would log it on).
            val watchDay = parseDateOrNull(movie.date.ifBlank { movie.lastWatched.take(10) })
                ?: LocalDate.now()
            val dayEntries = loadDayTextEntries(settings, habitName, watchDay, appContext)
            if (titleLogged(movie.title, dayEntries)) continue

            val entryTime = moviePromptEntryTime(movie)
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
            val ask = HabitNotification(
                id = movieAskId(marker),
                habitName = habitName,
                type = HabitNotification.TYPE_MOVIE,
                title = movie.title,
                question = "Watched this?",
                createdAtMillis = System.currentTimeMillis(),
                // "HH:mm:ss|<minutes>" — the length lets the answer path
                // annotate the entry so the minutes slot fills automatically.
                payload = HabitNotification.moviePayload(entryTime, movie.totalWatchMin ?: 0)
            )
            store.add(ask)
            HabitNotifier.postAsk(appContext, ask)
            Log.i(TAG, "Posted movie ask '${movie.title}' for '$habitName'")
            if (firstPosted == null) firstPosted = movie
            postedCount++
        }
        return firstPosted
    }

    private fun parseDateOrNull(dateStr: String): LocalDate? = try {
        LocalDate.parse(dateStr)
    } catch (e: Exception) {
        null
    }

    /** Text entries logged for [habitName] on [day], as (timestamp, text) pairs. */
    private suspend fun loadDayTextEntries(
        settings: com.example.tail.data.AppSettings,
        habitName: String,
        day: LocalDate,
        appContext: Context
    ): List<Pair<String, String>> {
        val uriStr = settings.textInputFileUris[habitName] ?: return emptyList()
        if (uriStr.isEmpty()) return emptyList()
        return try {
            val prefix = day.toString()
            TextInputRepository()
                .loadTextLog(Uri.parse(uriStr), appContext)
                .filterKeys { it.startsWith(prefix) }
                .map { (ts, text) -> ts to text }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load text entries for movie ask: ${e.message}")
            emptyList()
        }
    }
}
