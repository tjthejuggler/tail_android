package com.example.tail.widget

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.ipc.MusicNotificationListenerService
import com.example.tail.ui.HabitIncrementBus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "PodcastPlaybackTracker"

/**
 * Tracks podcast listening time via media sessions and records finished
 * listening blocks as minutes on the habit.
 *
 * HOW IT WORKS
 * ────────────
 * [WidgetTriggerService]'s poll loop calls [update] every ~2 s with the
 * currently configured `podcast app → habits` mapping. The tracker queries
 * [MediaSessionManager.getActiveSessions] (which requires the user to have
 * enabled [MusicNotificationListenerService] in Android's notification
 * access settings — the SAME toggle already used for Spotify detection) and
 * checks whether any session owned by a watched podcast package is in an
 * active playback state.
 *
 * A per-habit state machine accumulates listening time:
 *  - idle → playing  : persist the listening-start timestamp
 *  - playing         : refresh the last-seen-playing timestamp (so a process
 *                      death mid-playback only loses the final <2 s)
 *  - playing → idle  : elapsed = lastSeen − start; whole minutes (rounded,
 *                      same rule as the bubble timer) are ATOMICALLY added to
 *                      the habit's minutes secondary-value slot
 *                      (`secondary_value:<habit>`) via
 *                      [HabitsRepository.incrementHabitWithMinutes] with
 *                      sessions = 0 — the raw count (podcasts finished) stays
 *                      manual and serves as the points fallback on days with
 *                      zero auto-recorded minutes.
 *
 * State is persisted in plain [SharedPreferences] so an in-flight listening
 * block survives monitor restarts (watchdog revival, process kills). The
 * habit's raw count is never touched by auto-detection.
 *
 * EPISODE LOGGING
 * ───────────────
 * When a watched podcast app plays an episode, its media session also
 * exposes identifying metadata (episode title, show name, duration, and
 * occasionally a media URI — the full episode description is NOT available
 * via media sessions). The first time a NEW episode (deduplicated by
 * title+show, so pausing/resuming doesn't duplicate) is seen playing, it
 * is appended to the habit's text-entry log (if one is configured) as
 * `"Episode Title — Show Name (NN min)"` — replacing the manual
 * copy-the-description workflow.
 */
object PodcastPlaybackTracker {

    private const val PREFS_NAME = "tail_podcast_tracker"
    private const val KEY_START_PREFIX = "listening_start_"
    private const val KEY_LAST_SEEN_PREFIX = "listening_last_seen_"
    private const val KEY_LAST_EPISODE_PREFIX = "last_episode_"

    /** Serialises [update] calls so poll ticks never interleave. */
    private val mutex = Mutex()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the playback [state] counts as active listening. */
    private fun isActiveListening(state: Int): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    /** Identifying metadata for the episode a podcast app is playing. */
    private data class EpisodeMeta(
        val title: String,
        val show: String?,
        val durationMin: Int?,
        val mediaUri: String?
    )

    /**
     * Extracts episode metadata from a media session. Podcast apps put the
     * episode title in TITLE and the show name in ARTIST/ALBUM/AUTHOR.
     * Returns null when there is no usable title yet — metadata can lag
     * playback start by a tick or two, so the next poll retries.
     */
    private fun episodeMetaOf(md: MediaMetadata?): EpisodeMeta? {
        if (md == null) return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
        if (title.isNullOrEmpty()) return null
        val show = listOf(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_AUTHOR
        ).firstNotNullOfOrNull { key ->
            md.getString(key)?.trim()?.takeIf { it.isNotEmpty() && it != title }
        }
        val durationMin = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            .takeIf { it > 0 }?.let { Math.round(it / 60000.0).toInt() }
        val mediaUri = md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?.trim()?.takeIf { it.isNotEmpty() }
        return EpisodeMeta(title, show, durationMin, mediaUri)
    }

    /**
     * One poll tick: evaluate media sessions for every configured podcast
     * habit and advance the per-habit listening state machine.
     *
     * @param habitsByPackage podcast app package → habits configured for it
     */
    suspend fun update(context: Context, habitsByPackage: Map<String, List<String>>) {
        mutex.withLock {
            val appContext = context.applicationContext

            // Playing packages + episode metadata from their media sessions.
            var playingPackages: Set<String> = emptySet()
            var metaByPackage: Map<String, EpisodeMeta> = emptyMap()
            try {
                val msm = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(appContext, MusicNotificationListenerService::class.java)
                val playing = msm.getActiveSessions(component)
                    .filter { isActiveListening(it.playbackState?.state ?: PlaybackState.STATE_NONE) }
                playingPackages = playing.map { it.packageName }.toSet()
                metaByPackage = playing
                    .filter { it.packageName in habitsByPackage }
                    .mapNotNull { c -> episodeMetaOf(c.metadata)?.let { c.packageName to it } }
                    .toMap()
            } catch (e: SecurityException) {
                // Notification listener access not granted — auto-detection
                // is dormant; the bubble timer remains the fallback.
                Log.w(TAG, "No notification-listener access — podcast auto-detection inactive")
            } catch (e: Exception) {
                Log.w(TAG, "Media session query failed: ${e.message}")
            }

            val watchedHabits = habitsByPackage.entries
                .flatMap { (pkg, habits) -> habits.map { it to pkg } }

            for ((habit, pkg) in watchedHabits) {
                val playing = pkg in playingPackages
                advanceHabitState(appContext, habit, playing, metaByPackage[pkg])
            }

            // Habits that were deconfigured mid-listening: record what was
            // accumulated rather than silently dropping it.
            val watchedNames = watchedHabits.map { it.first }.toSet()
            for (habit in trackedHabits(appContext)) {
                if (habit !in watchedNames) {
                    Log.d(TAG, "Habit '$habit' no longer configured — flushing listening state")
                    advanceHabitState(appContext, habit, playing = false)
                }
            }
        }
    }

    /** Habits that currently have a persisted listening-start timestamp. */
    private fun trackedHabits(context: Context): List<String> =
        prefs(context).all.keys
            .filter { it.startsWith(KEY_START_PREFIX) }
            .map { it.removePrefix(KEY_START_PREFIX) }

    /**
     * State machine for a single habit:
     * idle→playing starts the clock, playing refreshes last-seen,
     * playing→idle records the elapsed whole minutes and clears the state.
     */
    private suspend fun advanceHabitState(
        context: Context,
        habit: String,
        playing: Boolean,
        meta: EpisodeMeta? = null
    ) {
        val p = prefs(context)
        val startKey = KEY_START_PREFIX + habit
        val lastSeenKey = KEY_LAST_SEEN_PREFIX + habit
        val start = p.getLong(startKey, 0L)
        val now = System.currentTimeMillis()

        if (start <= 0L) {
            if (playing) {
                Log.i(TAG, "Podcast playback started — tracking '$habit'")
                p.edit()
                    .putLong(startKey, now)
                    .putLong(lastSeenKey, now)
                    .apply()
                maybeLogEpisode(context, habit, meta)
            }
            return
        }

        if (playing) {
            p.edit().putLong(lastSeenKey, now).apply()
            // Metadata can arrive a tick or two after playback starts, so
            // episode logging is retried on every playing tick.
            maybeLogEpisode(context, habit, meta)
            return
        }

        // Playback stopped — record the finished listening block.
        val lastSeen = p.getLong(lastSeenKey, start)
        p.edit().remove(startKey).remove(lastSeenKey).apply()

        val elapsedMillis = (lastSeen - start).coerceAtLeast(0L)
        val minutes = Math.round(elapsedMillis / 60000.0).toInt()
        if (minutes < 1) {
            Log.d(TAG, "Listening block for '$habit' under a minute — nothing recorded")
            return
        }
        recordMinutes(context, habit, minutes)
    }

    /**
     * Logs the currently-playing episode to the habit's text-entry log the
     * FIRST time it is seen. Deduplicated by title+show, so pausing and
     * resuming the same episode never creates duplicate entries. A no-op
     * when the habit has no text-input file configured.
     */
    private suspend fun maybeLogEpisode(context: Context, habit: String, meta: EpisodeMeta?) {
        if (meta == null) return
        val p = prefs(context)
        val lastKey = KEY_LAST_EPISODE_PREFIX + habit
        val dedupKey = meta.title + "|" + (meta.show ?: "")
        if (p.getString(lastKey, null) == dedupKey) return
        logEpisode(context, habit, meta)
        p.edit().putString(lastKey, dedupKey).apply()
    }

    /**
     * Appends `"Episode Title — Show Name (NN min)"` (plus the media URI
     * when the app exposes one) to the habit's text-entry log via the same
     * atomic write + internal backup the manual text dialog uses.
     */
    private suspend fun logEpisode(context: Context, habit: String, meta: EpisodeMeta) {
        try {
            val settings = SettingsRepository(context).settingsFlow.first()
            val textUri = settings.textInputFileUris[habit]
            if (textUri.isNullOrEmpty()) return
            val entry = buildString {
                append(meta.title)
                meta.show?.let { append(" — ").append(it) }
                meta.durationMin?.let { append(" (").append(it).append(" min)") }
                meta.mediaUri?.let { append(" — ").append(it) }
            }
            TextInputRepository().appendTextEntry(
                Uri.parse(textUri), context, entry, habitName = habit
            )
            Log.i(TAG, "Logged podcast episode for '$habit': $entry")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log podcast episode for '$habit': ${e.message}")
        }
    }

    /**
     * Adds [minutes] to the habit's minutes secondary-value slot (same slot
     * and same atomic write the bubble timer uses, but with sessions = 0 so
     * the manually-tapped podcast count is untouched), then refreshes the
     * widget and notifies listeners.
     */
    private suspend fun recordMinutes(context: Context, habit: String, minutes: Int) {
        try {
            val settingsRepo = SettingsRepository(context)
            val settings = settingsRepo.settingsFlow.first()
            val uriStr = settings.fileUri
            if (uriStr.isEmpty()) {
                Log.w(TAG, "No habits file configured — podcast minutes for '$habit' not saved")
                return
            }

            HabitsRepository().incrementHabitWithMinutes(
                Uri.parse(uriStr), context, habit, minutes, 0
            )
            HabitIncrementBus.emit(habit)
            HabitListWidgetProvider.refreshAll(context)
            Log.i(TAG, "Recorded $minutes podcast minute(s) for '$habit'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save podcast minutes for '$habit': ${e.message}")
        }
    }
}
