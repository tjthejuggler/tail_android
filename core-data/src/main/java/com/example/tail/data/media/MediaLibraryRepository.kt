package com.example.tail.data

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.Instant

private const val TAG = "MediaLibraryRepository"

/**
 * Global lookup table for played media (songs/episodes), stored as ONE
 * JSON file in app-internal storage: `filesDir/media_library.json`.
 *
 * WHY A LOOKUP TABLE
 * ──────────────────
 * The per-play text-entry log stays lean (`"HH:mm Title — Artist"`).
 * Canonical per-song facts — genre, release year, album, artwork URL —
 * are stored here exactly ONCE per song (keyed by normalized
 * `title|artist`), together with a play counter and first/last-seen
 * timestamps. A song played 200 times costs one library entry, not 200
 * copies of its metadata.
 *
 * The library is GLOBAL (not per habit): the same song heard via two
 * different media habits shares a single entry.
 *
 * ENRICHMENT SOURCES
 * ──────────────────
 *  - `session` — album/year captured opportunistically from the media
 *    session metadata (free, no network).
 *  - `itunes`  — genre/year/album/artwork from [ItunesMusicLookup],
 *    fetched once per song, retried on later plays after failures
 *    (capped at [MAX_FAILED_LOOKUPS] attempts).
 */
class MediaLibraryRepository(private val context: Context) {

    /** One song/episode in the library. */
    data class LibraryEntry(
        val title: String,
        val artist: String?,
        /** From the media session or the enrichment API. */
        val album: String? = null,
        /** Release year. */
        val year: Int? = null,
        /** Genre (iTunes `primaryGenreName`). */
        val genre: String? = null,
        /** Cover-art URL. */
        val artworkUrl: String? = null,
        /** Where the richest data came from: "none" | "session" | "itunes". */
        val source: String = "none",
        val firstSeen: String = Instant.now().toString(),
        val lastSeen: String = Instant.now().toString(),
        /** Total registered plays. */
        val plays: Int = 0,
        /** Consecutive enrichment failures (reset on success). */
        val failedLookups: Int = 0
    ) {
        /** True while genre/year are still worth fetching. */
        val needsEnrichment: Boolean
            get() = (genre == null || year == null) && failedLookups < MAX_FAILED_LOOKUPS
    }

    private val gson = Gson()
    private val lock = Any()

    /** In-memory cache; loaded once, mutated under [lock]. */
    private var cache: MutableMap<String, LibraryEntry>? = null

    private fun libraryFile(): File = File(context.filesDir, LIBRARY_FILE_NAME)

    private fun load(): MutableMap<String, LibraryEntry> = synchronized(lock) {
        cache?.let { return it }
        val loaded: MutableMap<String, LibraryEntry> = try {
            val f = libraryFile()
            if (f.exists()) {
                val type = object : TypeToken<MutableMap<String, LibraryEntry>>() {}.type
                gson.fromJson<MutableMap<String, LibraryEntry>>(f.readText(), type) ?: mutableMapOf()
            } else mutableMapOf()
        } catch (e: Exception) {
            Log.w(TAG, "Library load failed — starting fresh: ${e.message}")
            mutableMapOf()
        }
        cache = loaded
        loaded
    }

    /** Atomic write (temp file + rename) so a crash never truncates the library. */
    private fun persist(map: Map<String, LibraryEntry>) = synchronized(lock) {
        try {
            val f = libraryFile()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(gson.toJson(map))
            if (!tmp.renameTo(f)) {
                tmp.copyTo(f, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Library persist failed: ${e.message}")
        }
    }

    /** Stable lookup key: normalized `title|artist`. */
    fun keyFor(title: String, artist: String?): String {
        val t = title.lowercase().replace(Regex("[^a-z0-9]"), "")
        val a = artist?.lowercase()?.replace(Regex("[^a-z0-9]"), "") ?: ""
        return "$t|$a"
    }

    /**
     * Records a play of [title]/[artist]. Creates the entry on first sight
     * (seeding [album]/[year] from the media session when available) and
     * bumps the play counter + last-seen timestamp.
     */
    fun registerPlay(
        title: String,
        artist: String?,
        album: String? = null,
        year: Int? = null
    ): LibraryEntry {
        val map = load()
        val key = keyFor(title, artist)
        val now = Instant.now().toString()
        val entry: LibraryEntry
        synchronized(lock) {
            entry = map[key]?.let { existing ->
                existing.copy(
                    // Session data fills gaps but never overwrites API data.
                    album = existing.album ?: album,
                    year = existing.year ?: year,
                    lastSeen = now,
                    plays = existing.plays + 1
                )
            } ?: LibraryEntry(
                title = title,
                artist = artist,
                album = album,
                year = year,
                source = if (album != null || year != null) "session" else "none",
                plays = 1
            ).let { e ->
                e.copy(firstSeen = now, lastSeen = now)
            }
            map[key] = entry
        }
        persist(map)
        return entry
    }

    /** Merges a successful [ItunesMusicLookup] result into the entry. */
    fun applyEnrichment(key: String, enriched: ItunesMusicLookup.EnrichedTrack) {
        val map = load()
        synchronized(lock) {
            val existing = map[key] ?: return
            map[key] = existing.copy(
                title = enriched.title,
                artist = enriched.artist,
                album = existing.album ?: enriched.album,
                year = existing.year ?: enriched.year,
                genre = enriched.genre,
                artworkUrl = enriched.artworkUrl,
                source = "itunes",
                failedLookups = 0
            )
        }
        persist(map)
        Log.i(TAG, "Enriched '${enriched.title}': genre=${enriched.genre} year=${enriched.year}")
    }

    /** Counts one failed enrichment attempt; retries stop at the cap. */
    fun markFailedLookup(key: String) {
        val map = load()
        var changed = false
        synchronized(lock) {
            val existing = map[key] ?: return
            map[key] = existing.copy(failedLookups = existing.failedLookups + 1)
            changed = true
        }
        if (changed) persist(map)
    }

    companion object {
        private const val LIBRARY_FILE_NAME = "media_library.json"

        /** Give up enriching a song after this many failed attempts. */
        const val MAX_FAILED_LOOKUPS = 3
    }
}
