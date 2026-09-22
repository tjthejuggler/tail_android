package com.example.tail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Phone-local cache of the desktop bridge's movie watch history
 * (`filesDir/movie_cache.json`).
 *
 * The bridge is only reachable on the home LAN, and the KDE-side watcher
 * already has the movie seconds after playback starts — so the phone keeps
 * its own copy, refreshed by [com.example.tail.notify.MovieSyncWorker] in
 * the background and by the app whenever it opens. Every movie surface
 * (the "watched this?" ask, the increment-dialog suggestion, the
 * last-watched picker) then reads instantly from disk with zero network
 * round trips.
 *
 * JSON shape mirrors the bridge's `movies/recent` items (see
 * [MovieBridgeService.parseMovie] / [MovieBridgeService.movieToJson]) plus
 * a `fetched_at` timestamp for staleness checks.
 */
object MovieCacheStore {

    private const val TAG = "MovieCacheStore"
    private const val FILE_NAME = "movie_cache.json"

    /** How many recent movies the cache keeps in sync with the bridge. */
    const val CAPACITY = 50

    /** A fresh-enough cache is not re-fetched from the bridge. */
    const val STALE_AFTER_MS = 10 * 60 * 1000L

    /** Cache contents plus when they were pulled from the bridge. */
    data class Cached(val movies: List<BridgeMovie>, val fetchedAtMs: Long) {
        val isFresh: Boolean
            get() = movies.isNotEmpty() &&
                System.currentTimeMillis() - fetchedAtMs < STALE_AFTER_MS
    }

    private val EMPTY = Cached(emptyList(), 0L)

    /** Reads the cache from disk (empty when missing/corrupt). */
    suspend fun load(context: Context): Cached = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (!file.exists()) return@withContext EMPTY
            val json = JSONObject(file.readText())
            val arr = json.optJSONArray("movies") ?: return@withContext EMPTY
            val movies = (0 until arr.length())
                .mapNotNull { MovieBridgeService.parseMovie(arr.getJSONObject(it)) }
            Cached(movies, json.optLong("fetched_at", 0L))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load movie cache: ${e.message}")
            EMPTY
        }
    }

    /** Atomically replaces the cache (temp file + rename), newest-first. */
    suspend fun save(context: Context, movies: List<BridgeMovie>): Unit =
        withContext(Dispatchers.IO) {
            try {
                val arr = JSONArray()
                movies.take(CAPACITY).forEach { arr.put(MovieBridgeService.movieToJson(it)) }
                val json = JSONObject()
                    .put("movies", arr)
                    .put("fetched_at", System.currentTimeMillis())
                val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                tmp.writeText(json.toString())
                val target = File(context.filesDir, FILE_NAME)
                if (!tmp.renameTo(target)) {
                    // Fall back to a direct write when rename fails (e.g. across
                    // mount points) — the window of a partial read is tiny and
                    // the loader tolerates corrupt files anyway.
                    target.writeText(json.toString())
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save movie cache: ${e.message}")
            }
        }
}
