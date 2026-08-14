package com.example.tail.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs
import kotlin.math.max

/**
 * Parsed movie/episode info extracted from a text entry.
 *
 * Text entries stored by the movie bridge look like:
 *   "Inception (148 min)"
 *   "A Different Man (2024) (105 min)"
 *   "Breaking Bad S05E14"
 *   "The Handmaids Tale S03E03"
 *
 * This data class holds the clean title, optional season/episode numbers and
 * optional release year needed to query the OMDb API.
 */
data class ParsedTitle(
    val title: String,
    val season: Int? = null,
    val episode: Int? = null,
    val year: Int? = null
) {
    /** True if this looks like a TV series episode. */
    val isEpisode: Boolean get() = season != null && episode != null

    /**
     * A normalised cache key that uniquely identifies this title+episode
     * combination (case-insensitive, whitespace-collapsed). The release year
     * is included for movies because the same title can exist in multiple
     * years (e.g. remakes).
     */
    val cacheKey: String
        get() {
            val base = title.trim().lowercase().replace(Regex("\\s+"), " ")
            val yearPart = year?.let { "::y$it" } ?: ""
            return if (isEpisode) "${base}::s${season}e${episode}" else "$base$yearPart"
        }

    /**
     * Key under which the resolved IMDb ID (tt…) for the show/movie is cached
     * in [ImdbRatingCache]. Episodes of one show share this key so the show is
     * resolved only once.
     */
    val idCacheKey: String
        get() {
            val base = title.trim().lowercase().replace(Regex("\\s+"), " ")
            return if (year != null) "$base::y$year" else base
        }
}

/**
 * Outcome of an OMDb lookup. Distinguishes definitive "not on IMDb" results
 * (safe to negative-cache) from transient failures (must NOT be cached, so
 * they are retried later). [OmdbOutcome.callsUsed] reports how many OMDb HTTP
 * requests were consumed (the lookup ladder may spend 2: one failed `t=` plus
 * one `i=` retry) for daily-limit tracking.
 */
sealed class OmdbOutcome {
    /** OMDb found the item. [rating] is null when it exists but has no rating ("N/A"). */
    data class Found(
        val rating: Int?,
        val imdbID: String?,
        /** IMDb ID resolved via fuzzy search (for the show/movie, not the episode). */
        val resolvedId: String? = null,
        override val callsUsed: Int = 1
    ) : OmdbOutcome()

    /** Definitively not on IMDb (exact + fuzzy resolution both failed). */
    data class NotFound(override val callsUsed: Int = 1) : OmdbOutcome()

    /** Transient failure — network error, HTTP failure, rate limit, bad key. */
    data class Transient(val message: String, override val callsUsed: Int = 1) : OmdbOutcome()

    /** Number of OMDb HTTP requests this outcome consumed. */
    abstract val callsUsed: Int
}

/** A candidate title returned by IMDb's suggestion (fuzzy search) endpoint. */
data class SuggestionCandidate(
    val imdbID: String,
    val title: String,
    val type: String?,
    val year: Int?
)

/**
 * HTTP client for the OMDb API (https://omdbapi.com/) with a fuzzy-resolution
 * fallback via IMDb's public suggestion endpoint.
 *
 * ## Why the fallback exists
 * OMDb's `t=` parameter is an exact-title match. Scene-release filenames lose
 * apostrophes ("The Handmaids Tale"), colons ("A Quiet Place Day One") and
 * question marks ("Would I Lie To You"), so exact matches fail often. When
 * `t=` fails, [fetchRating] asks IMDb's own search endpoint
 * (https://v2.sg.media-imdb.com/suggestion/ — free, no API key, no quota) to
 * resolve the title to an IMDb ID, scores the candidates (normalised title
 * similarity + year + type), and fetches the rating by ID (`i=`), which is
 * always reliable.
 *
 * ## Lookup ladder
 *  1. `i=<resolvedId>` (+ Season/Episode) if the ID was resolved previously.
 *  2. `t=<title>` + `y=<year>` + `type=` (+ Season/Episode).
 *  3. Fuzzy resolve via the suggestion endpoint → `i=<ttID>`.
 *
 * ## Usage
 * ```kotlin
 * val service = OmdbService()
 * val parsed = OmdbService.parseTitle("The Handmaids Tale S03E03")
 * val outcome = service.fetchRating(parsed, apiKey)
 * ```
 */
class OmdbService {

    companion object {
        private const val TAG = "OmdbService"
        private const val BASE_URL = "https://www.omdbapi.com/"
        private const val SUGGEST_BASE_URL = "https://v2.sg.media-imdb.com/suggestion/"
        private const val CONNECT_TIMEOUT = 8_000
        private const val READ_TIMEOUT = 10_000

        /** Minimum fuzzy-match score (0..~1.3) to accept a suggestion candidate. */
        private const val MIN_MATCH_SCORE = 0.62

        // ── Title parsing ───────────────────────────────────────────────────

        /** Matches S##E## patterns (e.g. "S05E14", "s1e2"). */
        private val SEASON_EPISODE_RE = Regex("""[Ss](\d{1,2})\s*[Ee](\d{1,3})""")

        /** Matches trailing duration annotations like " (148 min)". */
        private val DURATION_SUFFIX_RE = Regex("""\s*\(\d+\s*min\)\s*$""")

        /** Matches leading bracketed junk like "[Torrentcouch Com] ". */
        private val LEADING_BRACKET_RE = Regex("""^\[.*?\]\s*""")

        /** Matches a trailing parenthesised/bracketed release year (1950–2049). */
        private val TRAILING_YEAR_RE = Regex("""\s*[\(\[]((?:19[5-9]|20[0-4])\d)[\)\]]\s*$""")

        /** Release-group country tags appended to show names ("The Diplomat Us"). */
        private val COUNTRY_SUFFIX_RE = Regex("""^(.{3,}?)\s+(Us|Uk|Usa|Au|Ca|Nz)$""", RegexOption.IGNORE_CASE)

        /**
         * Parses a raw text entry into a [ParsedTitle].
         *
         * Handles:
         *   - "Inception (148 min)"        → ParsedTitle("Inception")
         *   - "A Different Man (2024)"     → ParsedTitle("A Different Man", year=2024)
         *   - "Breaking Bad S05E14"        → ParsedTitle("Breaking Bad", 5, 14)
         *   - "Show (2019) S01E02 (42 min)"→ ParsedTitle("Show", 1, 2, year=2019)
         *   - "[Torrentcouch Com] Black Mirror S02E03" → ParsedTitle("Black Mirror", 2, 3)
         */
        fun parseTitle(rawText: String): ParsedTitle {
            // Strip trailing "(N min)" duration annotations
            var cleaned = DURATION_SUFFIX_RE.replace(rawText, "").trim()

            // Strip leading bracketed release-site junk
            cleaned = LEADING_BRACKET_RE.replace(cleaned, "").trim()

            // Try to extract S##E## pattern
            val seMatch = SEASON_EPISODE_RE.find(cleaned)
            if (seMatch != null) {
                val season = seMatch.groupValues[1].toIntOrNull()
                val episode = seMatch.groupValues[2].toIntOrNull()
                if (season != null && episode != null) {
                    var title = cleaned.substring(0, seMatch.range.first).trim()
                    // Strip a trailing "(YYYY)" year from the show name if present
                    var year: Int? = null
                    val ym = TRAILING_YEAR_RE.find(title)
                    if (ym != null) {
                        year = ym.groupValues[1].toIntOrNull()
                        title = title.substring(0, ym.range.first).trim()
                    }
                    title = title.replace(Regex("[._]"), " ")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                    return ParsedTitle(
                        title = if (title.isNotEmpty()) title else cleaned,
                        season = season,
                        episode = episode,
                        year = year
                    )
                }
            }

            // Movie: extract a trailing "(YYYY)" year
            var year: Int? = null
            val ym = TRAILING_YEAR_RE.find(cleaned)
            if (ym != null) {
                year = ym.groupValues[1].toIntOrNull()
                cleaned = cleaned.substring(0, ym.range.first).trim()
            }

            cleaned = cleaned.replace(Regex("[._]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

            return ParsedTitle(title = cleaned, year = year)
        }

        // ── Fuzzy matching helpers (pure, unit-testable) ────────────────────

        /** Lowercases and reduces to alphanumeric words: "The Queen's Gambit!" → "the queen s gambit". */
        fun normalizeForCompare(s: String): String {
            return s.lowercase()
                .replace(Regex("[^a-z0-9]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }

        /** Classic Levenshtein edit distance. */
        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                val tmp = prev
                prev = cur
                cur = tmp
            }
            return prev[b.length]
        }

        /** Similarity ratio 0..1 between two strings after normalisation. */
        fun similarity(a: String, b: String): Double {
            val na = normalizeForCompare(a)
            val nb = normalizeForCompare(b)
            if (na.isEmpty() || nb.isEmpty()) return 0.0
            return 1.0 - levenshtein(na, nb).toDouble() / max(na.length, nb.length)
        }

        /** True if the candidate's IMDb type fits what we're looking for. */
        fun typeFits(isEpisode: Boolean, type: String?): Boolean {
            if (type.isNullOrBlank()) return false
            return if (isEpisode) {
                type.contains("series", ignoreCase = true)
            } else {
                type.equals("feature", true) ||
                    type.equals("TV movie", true) ||
                    type.equals("movie", true) ||
                    type.equals("documentary", true) ||
                    type.equals("short", true) ||
                    type.equals("TV short", true) ||
                    type.equals("special", true)
            }
        }

        /**
         * Scores a suggestion candidate against the parsed title (0..~1.3).
         * Empirically validated against a real watch-history corpus: correct
         * candidates score ≥ 0.8, junk scores < 0.62.
         */
        fun scoreCandidate(parsed: ParsedTitle, candidate: SuggestionCandidate): Double {
            var score = similarity(parsed.title, candidate.title)

            // Token containment: every query word appears in the candidate title
            val queryTokens = normalizeForCompare(parsed.title).split(" ").filter { it.isNotBlank() }.toSet()
            val candTokens = normalizeForCompare(candidate.title).split(" ").filter { it.isNotBlank() }.toSet()
            if (queryTokens.isNotEmpty() && candTokens.containsAll(queryTokens)) {
                score = max(score, 0.85)
            }

            // Year agreement is a strong signal for movies
            if (parsed.year != null && candidate.year != null) {
                score += when {
                    candidate.year == parsed.year -> 0.20
                    abs(candidate.year - parsed.year) <= 1 -> 0.10
                    else -> -0.15
                }
            }

            if (typeFits(parsed.isEpisode, candidate.type)) score += 0.05

            return score
        }
    }

    // ── Public API ─────────────────────────────────────────────────────────

    /**
     * Fetches the IMDb rating for a parsed title using the lookup ladder
     * described in the class docs.
     *
     * @param parsed     The parsed title (from [parseTitle]).
     * @param apiKey     The OMDb API key.
     * @param resolvedId A previously-resolved IMDb ID for this show/movie, if any.
     * @return The [OmdbOutcome]. Never throws for network/API failures.
     */
    suspend fun fetchRating(parsed: ParsedTitle, apiKey: String, resolvedId: String? = null): OmdbOutcome =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext OmdbOutcome.Transient("No OMDb API key configured")
            }

            // 1. Direct ID lookup when we already resolved this title before
            if (!resolvedId.isNullOrBlank()) {
                return@withContext fetchById(resolvedId, parsed.season, parsed.episode, apiKey, resolvedId = null)
            }

            // 2. Exact title match with year/type hints
            val byTitle = fetchByTitle(parsed, apiKey)
            if (byTitle !is OmdbOutcome.NotFound) return@withContext byTitle

            // 3. Fuzzy resolve via IMDb's suggestion endpoint (free), then fetch by ID
            val candidate = resolveImdbId(parsed)
            if (candidate == null) {
                Log.d(TAG, "OMDb: no IMDb match for '${parsed.title}' (exact + fuzzy)")
                return@withContext OmdbOutcome.NotFound(callsUsed = byTitle.callsUsed)
            }
            val byId = fetchById(candidate.imdbID, parsed.season, parsed.episode, apiKey, resolvedId = candidate.imdbID)
            val totalCalls = byTitle.callsUsed + byId.callsUsed
            when (byId) {
                is OmdbOutcome.Found -> byId.copy(callsUsed = totalCalls)
                is OmdbOutcome.NotFound -> byId.copy(callsUsed = totalCalls)
                is OmdbOutcome.Transient -> byId.copy(callsUsed = totalCalls)
            }
        }

    /**
     * Resolves a parsed title to an IMDb ID via IMDb's suggestion endpoint.
     * Returns the best-scoring candidate above [MIN_MATCH_SCORE], or null.
     * Makes no OMDb calls (does not consume daily quota).
     */
    suspend fun resolveImdbId(parsed: ParsedTitle): SuggestionCandidate? =
        withContext(Dispatchers.IO) {
            if (parsed.title.isBlank()) return@withContext null

            val attempts = mutableListOf(parsed.title)
            COUNTRY_SUFFIX_RE.find(parsed.title.trim())?.let { attempts.add(it.groupValues[1]) }

            for (query in attempts) {
                val candidates = fetchSuggestions(query)
                if (candidates.isEmpty()) continue

                val scored = candidates.map { it to scoreCandidate(parsed, it) }
                val best = scored
                    .filter { typeFits(parsed.isEpisode, it.first.type) }
                    .maxByOrNull { it.second }
                    ?: scored.maxByOrNull { it.second }
                    ?: continue

                if (best.second >= MIN_MATCH_SCORE) {
                    Log.d(TAG, "IMDb suggest: '${parsed.title}' → '${best.first.title}' " +
                        "(${best.first.imdbID}, score=${"%.2f".format(best.second)})")
                    return@withContext best.first
                }
            }
            null
        }

    // ── OMDb queries ───────────────────────────────────────────────────────

    /** Exact-title OMDb query (`t=`) with year/type hints and episode params. */
    private suspend fun fetchByTitle(parsed: ParsedTitle, apiKey: String): OmdbOutcome {
        val params = buildString {
            append("t=").append(URLEncoder.encode(parsed.title, "UTF-8"))
            parsed.year?.let { append("&y=").append(it) }
            if (parsed.isEpisode) {
                append("&type=series")
                append("&Season=").append(parsed.season)
                append("&Episode=").append(parsed.episode)
            } else if (parsed.year != null) {
                append("&type=movie")
            }
            append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
        }
        return omdbGet(params, apiKey, "'${parsed.title}'")
    }

    /** Direct OMDb query by IMDb ID (`i=`), with episode params when applicable. */
    private suspend fun fetchById(
        imdbID: String,
        season: Int?,
        episode: Int?,
        apiKey: String,
        resolvedId: String?
    ): OmdbOutcome {
        val params = buildString {
            append("i=").append(URLEncoder.encode(imdbID, "UTF-8"))
            if (season != null && episode != null) {
                append("&Season=").append(season)
                append("&Episode=").append(episode)
            }
            append("&apikey=").append(URLEncoder.encode(apiKey, "UTF-8"))
        }
        val outcome = omdbGet(params, apiKey, "id=$imdbID")
        return if (outcome is OmdbOutcome.Found && resolvedId != null) {
            outcome.copy(resolvedId = resolvedId)
        } else {
            outcome
        }
    }

    /** Performs one OMDb GET and maps the response to an [OmdbOutcome]. */
    private suspend fun omdbGet(params: String, apiKey: String, logLabel: String): OmdbOutcome =
        withContext(Dispatchers.IO) {
            try {
                val url = URL("$BASE_URL?$params")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.setRequestProperty("User-Agent", "Tail-Android-App/1.0")

                val code = conn.responseCode
                if (code != 200) {
                    val body = try {
                        conn.errorStream?.bufferedReader()?.readText().orEmpty()
                    } finally {
                        conn.disconnect()
                    }
                    Log.w(TAG, "OMDb HTTP $code for $logLabel: $body")
                    return@withContext OmdbOutcome.Transient("HTTP $code")
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                if (json.optString("Response", "True").equals("False", ignoreCase = true)) {
                    val error = json.optString("Error", "Unknown error")
                    return@withContext when {
                        error.contains("not found", ignoreCase = true) -> OmdbOutcome.NotFound()
                        // Bad key / limit reached: retryable once fixed, must not poison the cache
                        else -> OmdbOutcome.Transient(error)
                    }
                }

                val ratingStr = json.optString("imdbRating", "")
                val rating = ratingStr.toDoubleOrNull()?.let { Math.round(it * 10).toInt() }
                val imdbID = json.optString("imdbID", "").takeIf { it.isNotBlank() }

                Log.d(TAG, "OMDb: $logLabel → rating=$ratingStr (×10=$rating) id=$imdbID")
                OmdbOutcome.Found(rating = rating, imdbID = imdbID)
            } catch (e: Exception) {
                Log.w(TAG, "OMDb fetch failed for $logLabel: ${e.message}")
                OmdbOutcome.Transient(e.message ?: "network error")
            }
        }

    // ── IMDb suggestion endpoint (fuzzy resolution) ────────────────────────

    /**
     * Queries IMDb's public suggestion endpoint (the one powering imdb.com's
     * search box). Free, no API key, CDN-cached — does not consume OMDb quota.
     */
    private suspend fun fetchSuggestions(query: String): List<SuggestionCandidate> =
        withContext(Dispatchers.IO) {
            val q = query.lowercase().trim().replace(Regex("\\s+"), "_")
            val first = q.firstOrNull { it.isLetterOrDigit() } ?: return@withContext emptyList()
            try {
                val url = URL("$SUGGEST_BASE_URL$first/${URLEncoder.encode(q, "UTF-8")}.json")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = CONNECT_TIMEOUT
                conn.readTimeout = READ_TIMEOUT
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Tail)")
                conn.setRequestProperty("Accept", "application/json")

                val code = conn.responseCode
                if (code != 200) {
                    conn.disconnect()
                    Log.d(TAG, "IMDb suggest HTTP $code for '$query'")
                    return@withContext emptyList()
                }

                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val json = JSONObject(body)
                val arr = json.optJSONArray("d") ?: return@withContext emptyList()
                val result = mutableListOf<SuggestionCandidate>()
                for (i in 0 until minOf(arr.length(), 8)) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id", "")
                    if (!id.startsWith("tt")) continue
                    val type = obj.optString("q", "").ifBlank { obj.optString("qid", "") }.ifBlank { null }
                    result.add(
                        SuggestionCandidate(
                            imdbID = id,
                            title = obj.optString("l", ""),
                            type = type,
                            year = obj.optInt("y", -1).takeIf { it > 0 }
                        )
                    )
                }
                result
            } catch (e: Exception) {
                Log.d(TAG, "IMDb suggest failed for '$query': ${e.message}")
                emptyList()
            }
        }
}
