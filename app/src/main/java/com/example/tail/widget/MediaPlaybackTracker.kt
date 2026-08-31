package com.example.tail.widget

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.util.Log
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentLinkedQueue
import com.example.tail.data.HabitsRepository
import com.example.tail.data.ItunesMusicLookup
import com.example.tail.data.MediaLibraryRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.ipc.MusicNotificationListenerService
import com.example.tail.ui.HabitIncrementBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "MediaPlaybackTracker"

/**
 * Tracks media listening time (podcasts, music, audiobooks, …) via media
 * sessions and records finished listening blocks as minutes on the habit.
 *
 * HOW IT WORKS
 * ────────────
 * [WidgetTriggerService]'s poll loop calls [update] every ~2 s with the
 * currently configured `media app → habits` mapping. The tracker queries
 * [MediaSessionManager.getActiveSessions] (which requires the user to have
 * enabled [MusicNotificationListenerService] in Android's notification
 * access settings — the SAME toggle already used for Spotify detection) and
 * checks whether any session owned by a watched media package (a podcast app,
 * Spotify, any audio app) is in an active playback state.
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
 *                      sessions = 0 — the raw count (episodes/tracks
 *                      finished) stays manual and serves as the points
 *                      fallback on days with zero auto-recorded minutes.
 *
 * State is persisted in plain [SharedPreferences] so an in-flight listening
 * block survives monitor restarts (watchdog revival, process kills). The
 * habit's raw count is never touched by auto-detection.
 *
 * TRACK/EPISODE LOGGING
 * ─────────────────────
 * When a watched media app plays something, its media session also exposes
 * identifying metadata (title, artist/show name, duration, and occasionally
 * a media URI — the full description is NOT available via media sessions).
 * Podcast apps put the episode title in TITLE and the show name in
 * ARTIST/ALBUM; music apps like Spotify put the track name in TITLE and the
 * artist in ARTIST — the same extraction works for both.
 *
 * EVERY play is appended to the habit's text-entry log (if one is
 * configured) as `"HH:mm Title — Artist/Show (NN min)"`, so the log reads
 * as a play-by-play list of what was listened to and when. Replays are
 * detected via the session's playback POSITION: pausing/resuming keeps the
 * position (no duplicate entry), while a repeat-one restart or replay jumps
 * the position back to the start (logged again with the new time). The
 * text-log file itself is a JSON map additionally keyed by the full
 * `yyyy-MM-dd HH:mm:ss` timestamp of each write.
 *
 * SONG METADATA LIBRARY
 * ─────────────────────
 * Every logged play is also registered in a global lookup table
 * (filesDir/media_library.json, see [MediaLibraryRepository]) keyed by
 * normalized `title|artist`: canonical per-song facts — genre, release
 * year, album, artwork URL — are stored ONCE per song with a play
 * counter, never duplicated per play. Songs whose genre/year are still
 * unknown are queued for a one-time free iTunes Search lookup (no API
 * key, see [ItunesMusicLookup]); at most one lookup is drained per poll
 * tick and runs off the poll mutex.
 */
object MediaPlaybackTracker {

    /**
     * Kept from the original podcast-only implementation so an in-flight
     * listening block (and the per-habit dedup state) survives the
     * podcast → media upgrade untouched.
     */
    private const val PREFS_NAME = "tail_podcast_tracker"
    private const val KEY_START_PREFIX = "listening_start_"
    private const val KEY_LAST_SEEN_PREFIX = "listening_last_seen_"
    private const val KEY_LAST_EPISODE_PREFIX = "last_episode_"
    private const val KEY_LAST_POS_PREFIX = "last_media_pos_"
    private val ENTRY_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm")

    /** Serialises [update] calls so poll ticks never interleave. */
    private val mutex = Mutex()

    /** Runs song-enrichment network lookups OFF the poll mutex. */
    private val enrichmentScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Songs waiting for a genre/year lookup (drained one per poll tick). */
    private val pendingLookups = ConcurrentLinkedQueue<LookupRequest>()

    /** A song that needs its one-time metadata lookup. */
    private data class LookupRequest(val key: String, val title: String, val artist: String?)

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True when the playback [state] counts as active listening. */
    private fun isActiveListening(state: Int): Boolean =
        state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING

    /**
     * Identifying metadata for the item a media app is playing: episode
     * title + show for podcasts, track name + artist for music.
     * [positionMs] is the session's playback position, used to tell a
     * resume (position preserved) apart from a replay (position reset).
     */
    private data class MediaMeta(
        val title: String,
        val artist: String?,
        val album: String?,
        val durationMin: Int?,
        val mediaUri: String?,
        val positionMs: Long?,
        val year: Int?
    )

    /**
     * Extracts item metadata from a media session. Podcast apps put the
     * episode title in TITLE and the show name in ARTIST/ALBUM/AUTHOR;
     * music apps (Spotify & co.) put the track name in TITLE and the artist
     * in ARTIST/ALBUM. Returns null when there is no usable title yet —
     * metadata can lag playback start by a tick or two, so the next poll
     * retries.
     */
    private fun mediaMetaOf(md: MediaMetadata?, positionMs: Long?, pkg: String? = null): MediaMeta? {
        if (md == null) return null
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim()
        if (title.isNullOrEmpty()) return null
        val artist = listOf(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM,
            MediaMetadata.METADATA_KEY_AUTHOR
        ).firstNotNullOfOrNull { key ->
            md.getString(key)?.trim()?.takeIf { it.isNotEmpty() && it != title }
        }
        val album = md.getString(MediaMetadata.METADATA_KEY_ALBUM)
            ?.trim()?.takeIf { it.isNotEmpty() && it != title }
        val durationMin = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            .takeIf { it > 0 }?.let { Math.round(it / 60000.0).toInt() }
        var mediaUri = md.getString(MediaMetadata.METADATA_KEY_MEDIA_URI)
            ?.trim()?.takeIf { it.isNotEmpty() }
        // Spotify's session rarely exposes MEDIA_URI, but its MEDIA_ID is
        // usually the raw track-id hash — build the deep-link URI from it.
        if (mediaUri == null && pkg == "com.spotify.music") {
            val mediaId = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID)
                ?.trim()?.takeIf { it.isNotEmpty() }
            if (mediaId != null) {
                when {
                    // Full URI form (observed on current Spotify builds)
                    mediaId.startsWith("spotify:track:") -> mediaUri = mediaId
                    // Bare base62 track-id hash
                    !mediaId.contains(':') && mediaId.length in 16..32 ->
                        mediaUri = "spotify:track:$mediaId"
                }
            }
        }
        // Some players expose the release year in the session — free data.
        val year = md.getString(MediaMetadata.METADATA_KEY_YEAR)
            ?.trim()?.take(4)?.toIntOrNull()
        return MediaMeta(title, artist, album, durationMin, mediaUri, positionMs, year)
    }

    /**
     * One poll tick: evaluate media sessions for every configured media
     * habit and advance the per-habit listening state machine.
     *
     * @param habitsByPackage media app package → habits configured for it
     */
    suspend fun update(context: Context, habitsByPackage: Map<String, List<String>>) {
        mutex.withLock {
            val appContext = context.applicationContext

            // Capture Spotify track ids via the metadatachanged broadcast so
            // logged plays carry a spotify:track: URI (tap-to-play deep-link).
            if (habitsByPackage.keys.any { it == "com.spotify.music" }) {
                com.example.tail.data.SpotifyTrackIdCache.ensureRegistered(appContext)
            }

            // Playing packages + item metadata from their media sessions.
            var playingPackages: Set<String> = emptySet()
            var metaByPackage: Map<String, MediaMeta> = emptyMap()
            try {
                val msm = appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
                val component = ComponentName(appContext, MusicNotificationListenerService::class.java)
                val playing = msm.getActiveSessions(component)
                    .filter { isActiveListening(it.playbackState?.state ?: PlaybackState.STATE_NONE) }
                playingPackages = playing.map { it.packageName }.toSet()
                metaByPackage = playing
                    .filter { it.packageName in habitsByPackage }
                    .mapNotNull { c ->
                        mediaMetaOf(c.metadata, c.playbackState?.position, c.packageName)
                            ?.let { c.packageName to it }
                    }
                    .toMap()
            } catch (e: SecurityException) {
                // Notification listener access not granted — auto-detection
                // is dormant; the bubble timer remains the fallback.
                Log.w(TAG, "No notification-listener access — media auto-detection inactive")
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

            // Drain at most one queued metadata lookup per tick.
            pollNextLookup(appContext)
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
        meta: MediaMeta? = null
    ) {
        val p = prefs(context)
        val startKey = KEY_START_PREFIX + habit
        val lastSeenKey = KEY_LAST_SEEN_PREFIX + habit
        val start = p.getLong(startKey, 0L)
        val now = System.currentTimeMillis()

        if (start <= 0L) {
            if (playing) {
                Log.i(TAG, "Media playback started — tracking '$habit'")
                p.edit()
                    .putLong(startKey, now)
                    .putLong(lastSeenKey, now)
                    .apply()
                maybeLogMediaEntry(context, habit, meta)
            }
            return
        }

        if (playing) {
            p.edit().putLong(lastSeenKey, now).apply()
            // Metadata can arrive a tick or two after playback starts, so
            // item logging is retried on every playing tick.
            maybeLogMediaEntry(context, habit, meta)
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
     * Logs the currently-playing item (podcast episode or music track) to
     * the habit's text-entry log on every NEW play:
     *  - a different item than the last logged one, OR
     *  - the same item whose playback position RESET (repeat-one, replay,
     *    seek back to the start) — a pause/resume keeps the position and
     *    does NOT create a duplicate entry.
     * A no-op when the habit has no text-input file configured.
     */
    private suspend fun maybeLogMediaEntry(context: Context, habit: String, meta: MediaMeta?) {
        if (meta == null) return
        val p = prefs(context)
        val lastItemKey = KEY_LAST_EPISODE_PREFIX + habit
        val lastPosKey = KEY_LAST_POS_PREFIX + habit
        val dedupKey = meta.title + "|" + (meta.artist ?: "")
        val pos = meta.positionMs
        val lastPos = p.getLong(lastPosKey, -1L)

        val isNewPlay = p.getString(lastItemKey, null) != dedupKey ||
            // Restarted from (near) the top after having been deep into it.
            (pos != null && pos < 15_000 && lastPos > 45_000) ||
            // Jumped far backwards (seek-to-start / repeat) — a ~2 s poll
            // drift or a resume never moves the position back this far.
            (pos != null && lastPos >= 0 && pos < lastPos - 30_000)

        if (pos != null) {
            p.edit().putLong(lastPosKey, pos).apply()
        }
        if (!isNewPlay) return
        registerPlayInLibrary(context, meta)
        logMediaEntry(context, habit, meta)
        p.edit().putString(lastItemKey, dedupKey).apply()
    }

    /**
     * Records the play in the global media-library lookup table
     * (filesDir/media_library.json): canonical per-song facts (genre,
     * year, album, artwork) are stored ONCE per song — keyed by the
     * normalized `title|artist` — with a play counter and first/last-seen
     * timestamps instead of a duplicate row per play. Songs whose
     * genre/year are still unknown are queued for a one-time iTunes
     * Search lookup (free, no API key) drained one per poll tick.
     */
    private fun registerPlayInLibrary(context: Context, meta: MediaMeta) {
        try {
            val library = MediaLibraryRepository(context)
            val entry = library.registerPlay(meta.title, meta.artist, meta.album, meta.year)
            if (entry.needsEnrichment) {
                val key = library.keyFor(meta.title, meta.artist)
                if (pendingLookups.none { it.key == key }) {
                    pendingLookups.add(LookupRequest(key, meta.title, meta.artist))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register play in media library: ${e.message}")
        }
    }

    /**
     * Kicks off at most ONE queued iTunes lookup per poll tick; the
     * network work runs in [enrichmentScope], off the poll mutex. A
     * successful match fills in the canonical library entry
     * (genre/year/album/artwork) once for every future play of that
     * song; a definitive miss bumps the failure counter so hopeless
     * songs stop being retried after
     * [MediaLibraryRepository.MAX_FAILED_LOOKUPS] attempts. Transient
     * network errors leave the entry untouched — it simply gets
     * re-queued the next time the song is played.
     */
    private fun pollNextLookup(context: Context) {
        val req = pendingLookups.poll() ?: return
        enrichmentScope.launch {
            try {
                val enriched = ItunesMusicLookup.lookup(req.title, req.artist)
                val library = MediaLibraryRepository(context)
                if (enriched != null) {
                    library.applyEnrichment(req.key, enriched)
                } else {
                    library.markFailedLookup(req.key)
                    Log.d(TAG, "No iTunes match for '${req.title}'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "iTunes lookup failed for '${req.title}': ${e.message}")
            }
        }
    }

    /**
     * Appends `"HH:mm Title — Artist/Show (NN min)"` (plus the media URI
     * when the app exposes one) to the habit's text-entry log via the same
     * atomic write + internal backup the manual text dialog uses. The
     * clock time is when the play was first seen, making the log a
     * self-contained play-by-play list.
     */
    private suspend fun logMediaEntry(context: Context, habit: String, meta: MediaMeta) {
        try {
            val settings = SettingsRepository(context).settingsFlow.first()
            val textUri = settings.textInputFileUris[habit]
            if (textUri.isNullOrEmpty()) return
            val entry = buildString {
                append(LocalTime.now().format(ENTRY_TIME_FMT))
                append("  ")
                append(meta.title)
                meta.artist?.let { append(" — ").append(it) }
                meta.durationMin?.let { append(" (").append(it).append(" min)") }
                // Prefer the session's own media URI; Spotify's session rarely
                // exposes one, so fall back to the broadcast-captured track id
                // (title-matched) — this is what makes tap-to-play exact.
                val trackUri = meta.mediaUri
                    ?: com.example.tail.data.SpotifyTrackIdCache.matchingTrackUri(meta.title)
                trackUri?.let { append(" — ").append(it) }
            }
            TextInputRepository().appendTextEntry(
                Uri.parse(textUri), context, entry, habitName = habit
            )
            Log.i(TAG, "Logged media item for '$habit': $entry")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log media item for '$habit': ${e.message}")
        }
    }

    /**
     * Adds [minutes] to the habit's minutes secondary-value slot (same slot
     * and same atomic write the bubble timer uses, but with sessions = 0 so
     * the manually-tapped count is untouched), then refreshes the widget
     * and notifies listeners.
     */
    private suspend fun recordMinutes(context: Context, habit: String, minutes: Int) {
        try {
            val settingsRepo = SettingsRepository(context)
            val settings = settingsRepo.settingsFlow.first()
            val uriStr = settings.fileUri
            if (uriStr.isEmpty()) {
                Log.w(TAG, "No habits file configured — media minutes for '$habit' not saved")
                return
            }

            HabitsRepository().incrementHabitWithMinutes(
                Uri.parse(uriStr), context, habit, minutes, 0
            )
            HabitIncrementBus.emit(habit)
            HabitListWidgetProvider.refreshAll(context)
            Log.i(TAG, "Recorded $minutes media minute(s) for '$habit'")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save media minutes for '$habit': ${e.message}")
        }
    }
}
