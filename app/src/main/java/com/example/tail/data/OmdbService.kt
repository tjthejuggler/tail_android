package com.example.tail.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Parsed movie/episode info extracted from a text entry.
 *
 * Text entries stored by the movie bridge look like:
 *   "Inception (148 min)"
 *   "Breaking Bad S05E14"
 *   "The Handmaids Tale S03E03"
 *
 * This data class holds the clean title and optional season/episode numbers
 * needed to query the OMDb API.
 */
data class ParsedTitle(
    val title: String,
    val season: Int? = null,
    val episode: Int? = null
) {
    /** True if this looks like a TV series episode. */
    val isEpisode: Boolean get() = season != null && episode != null

    /**
     * A normalised cache key that uniquely identifies this title+episode
     * combination (case-insensitive, whitespace-collapsed).
     */
    val cacheKey: String
        get() {
            val base = title.trim().lowercase().replace(Regex("\\s+"), " ")
            return if (isEpisode) "${base}::s${season}e${episode}" else base
        }
}

/**
 * Result of an OMDb API lookup.
 *
 * @param rating  IMDb rating × 10 (e.g. 8.8 → 88). Null if no rating available.
 * @param imdbID  The IMDb ID (e.g. "tt1375666"), useful for future deep-links.
 */
data class OmdbResult(
    val rating: Int?,
    val imdbID: String?
)

/**
 * HTTP client for the OMDb API (https://omdbapi.com/).
 *
 * Fetches IMDb ratings for movies and TV episodes. Results are cached by the
 * caller (see [ImdbRatingCache]) to minimise API usage — the free tier is
 * limited to 1 000 calls/day.
 *
 * ## Title parsing
 * Text entries from the movie bridge contain the title plus optional metadata
 * (duration in parentheses, S##E## episode markers). [parseTitle] extracts the
 * clean title and season/episode info needed for the API query.
 *
 * ## Usage
 * ```kotlin
 * val service = OmdbService()
 * val parsed = OmdbService.parseTitle("Breaking Bad S05E14")
 * val result = service.fetchRating(parsed, apiKey)
 * // result.rating == 95  (9.5 × 10)
 * ```
 */
class OmdbService {

    companion object {
        private const val TAG = "OmdbService"
        private const val BASE_URL = "https://www.omdbapi.com/"
        private const val CONNECT_TIMEOUT = 8_000
        private const val READ_TIMEOUT = 10_000

        // ── Title parsing ───────────────────────────────────────────────────

        /** Matches S##E## patterns (e.g. "S05E14", "s1e2"). */
        private val SEASON_EPISODE_RE = Regex("""[Ss](\d{1,2})\s*[Ee](\d{1,3})""")

        /** Matches trailing duration annotations like " (148 min)". */
        private val DURATION_SUFFIX_RE = Regex("""\s*\(\d+\s*min\)\s*$""")

        /**
         * Parses a raw text entry into a [ParsedTitle].
         *
         * Handles:
         *   - "Inception (148 min)"       → ParsedTitle("Inception")
         *   - "Breaking Bad S05E14"       → ParsedTitle("Breaking Bad", 5, 14)
         *   - "The Handmaids Tale S03E03"→ ParsedTitle("The Handmaids Tale", 3, 3)
         *   - "Some Movie"                → ParsedTitle("Some Movie")
         */
        fun parseTitle(rawText: String): ParsedTitle {
            // Strip trailing "(N min)" duration annotations
            var cleaned = DURATION_SUFFIX_RE.replace(rawText, "").trim()

            // Try to extract S##E## pattern
            val seMatch = SEASON_EPISODE_RE.find(cleaned)
            if (seMatch != null) {
                val season = seMatch.groupValues[1].toIntOrNull()
                val episode = seMatch.groupValues[2].toIntOrNull()
                if (season != null && episode != null) {
                    // Remove the S##E## part from the title
                    val title = cleaned.substring(0, seMatch.range.first).trim()
                        .replace(Regex("[._]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    return ParsedTitle(
                        title = if (title.isNotEmpty()) title else cleaned,
                        season = season,
                        episode = episode
                    )
                }
            }

            // Plain title — just clean up dots/underscores
            cleaned = cleaned.replace(Regex("[._]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            return ParsedTitle(title = cleaned)
        }
    }

    /**
     * Fetches the IMDb rating for a parsed title from the OMDb API.
     *
     * For TV episodes, passes season and episode numbers so OMDb returns the
     * episode-specific rating.
     *
     * @param parsed  The parsed title (from [parseTitle]).
     * @param apiKey  The OMDb API key.
     * @return An [OmdbResult] with the rating (× 10) and IMDb ID, or null on
     *         error / not found.
     */
    suspend fun fetchRating(parsed: ParsedTitle, apiKey: String): OmdbResult? =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                Log.w(TAG, "No OMDb API key configured")
                return@withContext null
            }

            try {
                val params = buildString {
                    append("t=")
                    append(URLEncoder.encode(parsed.title, "UTF-8"))
                    if (parsed.isEpisode) {
                        append("&Season=").append(parsed.season)
                        append("&Episode=").append(parsed.episode)
                    }
                    append("&apikey=").append(apiKey)
                }

                val url = URL("$BASE_URL?$params")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.setRequestProperty("User-Agent", "Tail-Android-App/1.0")

                val code = conn.responseCode
                if (code != 200) {
                    Log.w(TAG, "OMDb HTTP $code for '${parsed.title}'")
                    conn.disconnect()
                    return@withContext null
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)

                // OMDb returns {"Response": "False", "Error": "Movie not found!"}
                // when the title isn't found.
                if (json.optString("Response", "True").equals("False", ignoreCase = true)) {
                    Log.d(TAG, "OMDb: not found for '${parsed.title}': ${json.optString("Error")}")
                    return@withContext null
                }

                val ratingStr = json.optString("imdbRating", "")
                val rating = ratingStr.toDoubleOrNull()?.let { (it * 10).roundToInt() }

                val imdbID = json.optString("imdbID", "").takeIf { it.isNotBlank() }

                Log.d(TAG, "OMDb: '${parsed.title}' → rating=$ratingStr (×10=$rating) id=$imdbID")

                OmdbResult(rating = rating, imdbID = imdbID)
            } catch (e: Exception) {
                Log.w(TAG, "OMDb fetch failed for '${parsed.title}': ${e.message}")
                null
            }
        }

    /** Rounds a Double to the nearest Int (helper). */
    private fun Double.roundToInt(): Int = Math.round(this).toInt()
}
