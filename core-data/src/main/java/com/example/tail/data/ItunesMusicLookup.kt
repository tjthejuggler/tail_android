package com.example.tail.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val TAG = "ItunesMusicLookup"

/**
 * Free song-metadata lookup via the **iTunes Search API**.
 *
 * Chosen because it requires NO API key, NO registration and NO auth, and
 * returns exactly the per-song data the media session cannot provide:
 *  - `primaryGenreName` → genre
 *  - `releaseDate`      → release year
 *  - `collectionName`   → album
 *  - `artworkUrl100`    → cover-art URL
 *
 * Rate limit (~20 req/min) is a non-issue for this app: lookups happen at
 * most once per newly played song. Results are matched against the
 * title/artist the media session reported so a generic search miss never
 * stores wrong metadata (see [matchScore]).
 */
object ItunesMusicLookup {

    /** The enrichment data fetched for one song. */
    data class EnrichedTrack(
        val title: String,
        val artist: String,
        val album: String?,
        val year: Int?,
        val genre: String?,
        val artworkUrl: String?
    )

    private const val ENDPOINT = "https://itunes.apple.com/search"
    private const val TIMEOUT_MS = 10_000

    /**
     * Looks up [title]/[artist] and returns the best match, or null when
     * nothing matches confidently enough (never stores a guess).
     */
    suspend fun lookup(title: String, artist: String?): EnrichedTrack? =
        withContext(Dispatchers.IO) {
            try {
                val term = listOfNotNull(artist?.takeIf { it.isNotBlank() }, title)
                    .joinToString(" ")
                val query = URLEncoder.encode(term, "UTF-8")
                val url = URL("$ENDPOINT?term=$query&entity=song&limit=5")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "GET"
                    conn.connectTimeout = TIMEOUT_MS
                    conn.readTimeout = TIMEOUT_MS
                    conn.setRequestProperty("Accept", "application/json")
                    if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                        Log.w(TAG, "iTunes search HTTP ${conn.responseCode}")
                        return@withContext null
                    }
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    pickBestMatch(body, title, artist)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "iTunes lookup failed: ${e.message}")
                null
            }
        }

    /** Parses the search response and returns the best-matching result. */
    private fun pickBestMatch(body: String, title: String, artist: String?): EnrichedTrack? {
        try {
            val root = org.json.JSONObject(body)
            val results = root.optJSONArray("results") ?: return null
            var best: EnrichedTrack? = null
            var bestScore = 0
            for (i in 0 until results.length()) {
                val r = results.optJSONObject(i) ?: continue
                val trackName = r.optString("trackName", "")
                val artistName = r.optString("artistName", "")
                if (trackName.isEmpty()) continue
                val score = matchScore(trackName, artistName, title, artist)
                if (score > bestScore) {
                    bestScore = score
                    best = EnrichedTrack(
                        title = trackName,
                        artist = artistName,
                        album = r.optString("collectionName", "").takeIf { it.isNotEmpty() },
                        year = r.optString("releaseDate", "").take(4).toIntOrNull(),
                        genre = r.optString("primaryGenreName", "").takeIf { it.isNotEmpty() },
                        artworkUrl = r.optString("artworkUrl100", "").takeIf { it.isNotEmpty() }
                    )
                }
            }
            // Require a confident match: both title AND artist must line up.
            return if (bestScore >= 2) best else null
        } catch (e: Exception) {
            Log.w(TAG, "iTunes response parse failed: ${e.message}")
            return null
        }
    }

    /**
     * 2 = title AND artist match (normalized), 1 = partial, 0 = no match.
     * Normalization ignores case, punctuation and spacing so
     * "Don't Stop Me Now" equals "dont stop me now".
     */
    private fun matchScore(
        resultTrack: String, resultArtist: String,
        wantedTrack: String, wantedArtist: String?
    ): Int {
        val t = normalize(wantedTrack)
        val rt = normalize(resultTrack)
        if (t.isEmpty() || rt.isEmpty()) return 0
        var score = 0
        if (rt == t || rt.contains(t) || t.contains(rt)) score++
        if (wantedArtist != null) {
            val a = normalize(wantedArtist)
            val ra = normalize(resultArtist)
            if (a.isNotEmpty() && (ra == a || ra.contains(a) || a.contains(ra))) score++
        }
        return score
    }

    private fun normalize(s: String): String =
        s.lowercase().replace(Regex("[^a-z0-9]"), "")
}
