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
     * (system-notification action). The caller is responsible for removing the
     * record from the store and cancelling the system notification.
     *
     * - Movie + Yes  → appends the title as a text entry at the stored entry time
     * - Movie + No   → nothing (marker still persisted so it is never re-asked)
     * - Schedule + Yes → increments today's count by 1 (respecting max-1)
     * - Schedule + No  → nothing
     */
    suspend fun applyAnswer(appContext: Context, ask: HabitNotification, yes: Boolean) {
        // Informational notices carry no effect — the caller removes the
        // record and cancels the system notification (dismiss-everywhere).
        if (ask.type == HabitNotification.TYPE_INFO) return
        val settingsRepo = SettingsRepository(appContext)
        if (ask.type == HabitNotification.TYPE_MOVIE) {
            // Persist the handled marker (id is "movie:<marker>") so the movie
            // is never re-asked, no matter where it was answered.
            try {
                val marker = ask.id.removePrefix("movie:")
                val handled = settingsRepo.getMoviePromptHandled()
                settingsRepo.saveMoviePromptHandled(handled + marker)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save movie handled marker: ${e.message}")
            }
            if (!yes) return
            val settings = settingsRepo.settingsFlow.first()
            val uriStr = settings.textInputFileUris[ask.habitName]
            if (uriStr.isNullOrEmpty()) {
                Log.w(TAG, "No text log URI for '${ask.habitName}' — cannot log movie")
                return
            }
            val (payloadTime, payloadMinutes) = HabitNotification.parseMoviePayload(ask.payload)
            val time = payloadTime?.let { parseTime(it) } ?: LocalTime.now()
            // Carry the watch length onto the logged entry so the minutes
            // slot fills from the annotation at the next sync.
            val text = if (payloadMinutes > 0) "${ask.title} ($payloadMinutes min)" else ask.title
            try {
                TextInputRepository().appendTextEntry(
                    Uri.parse(uriStr), appContext, text, null, time, ask.habitName
                )
                Log.i(TAG, "Logged movie '${ask.title}' for '${ask.habitName}' from system notification")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log movie answer: ${e.message}", e)
                return
            }
            // Mirror the in-app confirm path (HabitViewModel.saveTextEntry):
            // a confirmed movie also increments the habit count so the day
            // registers as watched, records the increment timestamp and
            // notifies any running UI. IMDb rating/runtime enrichment can be
            // filled in afterwards via the IMDb backlog buttons in settings.
            val habitsUriStr = settings.fileUri
            if (habitsUriStr.isEmpty()) {
                Log.w(TAG, "No habits file URI configured — cannot increment '${ask.habitName}'")
                return
            }
            val habitsUri = Uri.parse(habitsUriStr)
            val habitsRepo = HabitsRepository()
            // Respect the "max 1" cap: skip when already done today.
            if (ask.habitName in settings.maxOneHabits) {
                val db = habitsRepo.loadDatabase(habitsUri, appContext)
                val todayCount = db[ask.habitName]?.get(LocalDate.now().toString()) ?: 0
                if (todayCount >= 1) {
                    Log.i(TAG, "Skipping movie increment for '${ask.habitName}' — already at max 1 today")
                    return
                }
            }
            habitsRepo.incrementHabit(habitsUri, appContext, ask.habitName, 1)
            HabitIncrementBus.emit(ask.habitName)
            try {
                HabitTimestampRepository(appContext).addTimestamp(ask.habitName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record timestamp for '${ask.habitName}': ${e.message}")
            }
            Log.i(TAG, "Incremented '${ask.habitName}' for confirmed movie")
            com.example.tail.ipc.HabitIncrementAnnouncer.announce(appContext, ask.habitName, 1)
            return
        }

        if (!yes) return
        val settings = settingsRepo.settingsFlow.first()
        val uriStr = settings.fileUri
        if (uriStr.isEmpty()) {
            Log.w(TAG, "No habits file URI configured — cannot increment '${ask.habitName}'")
            return
        }
        val uri = Uri.parse(uriStr)
        val habitsRepo = HabitsRepository()
        // Respect the "max 1" cap: skip when already done today.
        if (ask.habitName in settings.maxOneHabits) {
            val db = habitsRepo.loadDatabase(uri, appContext)
            val todayCount = db[ask.habitName]?.get(LocalDate.now().toString()) ?: 0
            if (todayCount >= 1) {
                Log.i(TAG, "Skipping answer increment for '${ask.habitName}' — already at max 1 today")
                return
            }
        }
        habitsRepo.incrementHabit(uri, appContext, ask.habitName, 1)
        HabitIncrementBus.emit(ask.habitName)
        try {
            HabitTimestampRepository(appContext).addTimestamp(ask.habitName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record timestamp for '${ask.habitName}': ${e.message}")
        }
        Log.i(TAG, "Incremented '${ask.habitName}' from notification answer")
        com.example.tail.ipc.HabitIncrementAnnouncer.announce(appContext, ask.habitName, 1)
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
