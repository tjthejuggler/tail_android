package com.example.tail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Local persistent cache for IMDb ratings fetched from the OMDb API.
 *
 * Stores a mapping of normalised cache key (see [ParsedTitle.cacheKey]) →
 * rating (× 10 as Int, e.g. 8.8 → 88) in a JSON file in the app's internal
 * storage. Also tracks the number of OMDb API calls made today so the caller
 * can respect the 1 000/day free-tier limit.
 *
 * Also stores resolved IMDb IDs (tt…) per show/movie title so fuzzy resolution
 * (via IMDb's suggestion endpoint) happens only once per title.
 *
 * ## File format
 * ```json
 * {
 *   "ratings": {
 *     "inception::y2010": 88,
 *     "breaking bad::s5e14": 95
 *   },
 *   "runtimes": {
 *     "inception::y2010": 148,
 *     "breaking bad::s5e14": 47
 *   },
 *   "resolvedIds": {
 *     "breaking bad": "tt0903747"
 *   },
 *   "callTracking": {
 *     "date": "2026-08-13",
 *     "count": 42
 *   }
 * }
 * ```
 *
 * Thread-safe via a [Mutex]; safe to call from multiple coroutines.
 */
class ImdbRatingCache(private val context: Context) {

    companion object {
        private const val TAG = "ImdbRatingCache"
        private const val FILE_NAME = "imdb_ratings_cache.json"

        /** Maximum OMDb API calls per day (free tier is 1 000; we leave head-room). */
        const val DAILY_LIMIT = 990
    }

    private val mutex = Mutex()

    // ── In-memory state (loaded lazily) ──────────────────────────────────────

    @Volatile
    private var ratings: MutableMap<String, Int> = mutableMapOf()

    /** Resolved IMDb IDs (tt…) keyed by [ParsedTitle.idCacheKey]. */
    @Volatile
    private var resolvedIds: MutableMap<String, String> = mutableMapOf()

    /** Runtimes in minutes keyed by [ParsedTitle.cacheKey]. 0 = looked up, none found. */
    @Volatile
    private var runtimes: MutableMap<String, Int> = mutableMapOf()

    @Volatile
    private var trackingDate: String = ""

    @Volatile
    private var trackingCount: Int = 0

    @Volatile
    private var loaded: Boolean = false

    // ── Loading / saving ─────────────────────────────────────────────────────

    /**
     * Loads the cache from disk if not already loaded.
     * Safe to call multiple times — subsequent calls are no-ops.
     */
    private suspend fun ensureLoaded() {
        if (loaded) return
        mutex.withLock {
            if (loaded) return@withLock
            withContext(Dispatchers.IO) {
                try {
                    val file = File(context.filesDir, FILE_NAME)
                    if (file.exists()) {
                        val json = JSONObject(file.readText())
                        val ratingsObj = json.optJSONObject("ratings")
                        if (ratingsObj != null) {
                            val map = mutableMapOf<String, Int>()
                            val keys = ratingsObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                val v = ratingsObj.optInt(k, -1)
                                if (v >= 0) map[k] = v
                            }
                            ratings = map
                        }
                        val idsObj = json.optJSONObject("resolvedIds")
                        if (idsObj != null) {
                            val idMap = mutableMapOf<String, String>()
                            val idKeys = idsObj.keys()
                            while (idKeys.hasNext()) {
                                val k = idKeys.next()
                                val v = idsObj.optString(k, "")
                                if (v.startsWith("tt")) idMap[k] = v
                            }
                            resolvedIds = idMap
                        }
                        val runtimesObj = json.optJSONObject("runtimes")
                        if (runtimesObj != null) {
                            val runtimeMap = mutableMapOf<String, Int>()
                            val runtimeKeys = runtimesObj.keys()
                            while (runtimeKeys.hasNext()) {
                                val k = runtimeKeys.next()
                                val v = runtimesObj.optInt(k, -1)
                                if (v >= 0) runtimeMap[k] = v
                            }
                            runtimes = runtimeMap
                        }
                        val tracking = json.optJSONObject("callTracking")
                        if (tracking != null) {
                            trackingDate = tracking.optString("date", "")
                            trackingCount = tracking.optInt("count", 0)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load cache: ${e.message}")
                }
                loaded = true
            }
        }
    }

    /**
     * Persists the current cache state to disk.
     * Must be called from [Dispatchers.IO] or wrapped in withContext.
     */
    private suspend fun persist() {
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject()
                val ratingsObj = JSONObject()
                for ((k, v) in ratings) {
                    ratingsObj.put(k, v)
                }
                json.put("ratings", ratingsObj)

                val idsObj = JSONObject()
                for ((k, v) in resolvedIds) {
                    idsObj.put(k, v)
                }
                json.put("resolvedIds", idsObj)

                val runtimesObj = JSONObject()
                for ((k, v) in runtimes) {
                    runtimesObj.put(k, v)
                }
                json.put("runtimes", runtimesObj)

                val trackingObj = JSONObject()
                trackingObj.put("date", trackingDate)
                trackingObj.put("count", trackingCount)
                json.put("callTracking", trackingObj)

                File(context.filesDir, FILE_NAME).writeText(json.toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to persist cache: ${e.message}")
            }
        }
    }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Returns the cached rating (× 10) for [cacheKey], or null if not cached.
     */
    suspend fun getRating(cacheKey: String): Int? {
        ensureLoaded()
        return ratings[cacheKey]
    }

    /**
     * Stores a rating in the cache and persists to disk.
     *
     * @param cacheKey The normalised cache key (see [ParsedTitle.cacheKey]).
     * @param rating   The IMDb rating × 10 (e.g. 88 for 8.8).
     */
    suspend fun putRating(cacheKey: String, rating: Int?) {
        ensureLoaded()
        mutex.withLock {
            // Store 0 for "not found" lookups so we don't re-fetch them.
            // Use -1 sentinel internally for "looked up but no rating", mapped to 0.
            ratings[cacheKey] = rating ?: 0
            persist()
        }
    }

    /**
     * Returns true if [cacheKey] has already been looked up (even if the
     * result was "no rating found", which is stored as 0).
     */
    suspend fun hasBeenLookedUp(cacheKey: String): Boolean {
        ensureLoaded()
        return ratings.containsKey(cacheKey)
    }

    /**
     * Returns a previously-resolved IMDb ID for [idCacheKey] (see
     * [ParsedTitle.idCacheKey]), or null if not resolved yet.
     */
    suspend fun getResolvedId(idCacheKey: String): String? {
        ensureLoaded()
        return resolvedIds[idCacheKey]
    }

    /**
     * Stores a resolved IMDb ID (tt…) for a show/movie title and persists.
     */
    suspend fun putResolvedId(idCacheKey: String, imdbID: String) {
        ensureLoaded()
        mutex.withLock {
            resolvedIds[idCacheKey] = imdbID
            persist()
        }
    }

    /**
     * Returns the cached runtime in minutes for [cacheKey], or null if not
     * looked up yet. A stored 0 means "looked up but OMDb had no runtime".
     */
    suspend fun getRuntime(cacheKey: String): Int? {
        ensureLoaded()
        return runtimes[cacheKey]?.takeIf { it > 0 }
    }

    /** True if a runtime lookup already happened for [cacheKey] (even if none found). */
    suspend fun hasRuntime(cacheKey: String): Boolean {
        ensureLoaded()
        return runtimes.containsKey(cacheKey)
    }

    /**
     * Stores a runtime in minutes for [cacheKey] and persists.
     * Null is stored as 0 ("looked up, no runtime") so it is not re-fetched.
     */
    suspend fun putRuntime(cacheKey: String, runtimeMin: Int?) {
        ensureLoaded()
        mutex.withLock {
            runtimes[cacheKey] = runtimeMin ?: 0
            persist()
        }
    }

    /**
     * Removes all cached "no rating" entries (values ≤ 0) so those titles are
     * fetched again on the next backlog run. Used by the "Retry Failed
     * Lookups" action — necessary because transient failures (rate limit,
     * network) were historically cached as permanent negatives.
     *
     * @return The number of failed entries cleared.
     */
    suspend fun clearFailedLookups(): Int {
        ensureLoaded()
        return mutex.withLock {
            val before = ratings.size
            ratings = ratings.filterValues { it > 0 }.toMutableMap()
            persist()
            before - ratings.size
        }
    }

    /**
     * Returns the number of OMDb API calls made today.
     * Resets the counter if the date has changed since the last call.
     */
    suspend fun getTodayCallCount(): Int {
        ensureLoaded()
        val today = todayString()
        if (trackingDate != today) {
            trackingDate = today
            trackingCount = 0
        }
        return trackingCount
    }

    /**
     * Increments the daily call counter by [delta] and persists.
     * Automatically resets if the date has rolled over.
     */
    suspend fun incrementCallCount(delta: Int = 1) {
        ensureLoaded()
        mutex.withLock {
            val today = todayString()
            if (trackingDate != today) {
                trackingDate = today
                trackingCount = 0
            }
            trackingCount += delta
            persist()
        }
    }

    /**
     * Returns how many API calls remain for today (out of [DAILY_LIMIT]).
     */
    suspend fun remainingCalls(): Int {
        val used = getTodayCallCount()
        return (DAILY_LIMIT - used).coerceAtLeast(0)
    }

    /**
     * Returns a snapshot of all cached ratings as a map.
     * Used for bulk re-computation of daily averages.
     */
    suspend fun getAllRatings(): Map<String, Int> {
        ensureLoaded()
        return ratings.toMap()
    }

    /**
     * Clears all cached ratings and resets the call counter.
     * Mainly useful for testing.
     */
    suspend fun clear() {
        mutex.withLock {
            ratings.clear()
            trackingDate = todayString()
            trackingCount = 0
            persist()
        }
    }

    private fun todayString(): String {
        val now = java.time.LocalDate.now()
        return now.toString()
    }
}
