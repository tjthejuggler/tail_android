package com.example.tail.data

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GitHubService"
private const val BASE_URL = "https://api.github.com"
private const val USER_AGENT = "TailHabitTracker/1.0 (Android habit tracking app)"

/**
 * The metrics that can be tracked for a GitHub repository.
 *
 * [LINES_CHANGED] is the default — it captures total coding volume
 * (additions + deletions) per day, which is the most meaningful single
 * number for "how much did I code today".
 */
enum class GitHubMetric(val label: String, val description: String) {
    LINES_CHANGED("Lines Changed", "Additions + deletions per day"),
    COMMITS("Commits", "Number of commits per day"),
    ADDITIONS("Additions", "Lines added per day"),
    DELETIONS("Deletions", "Lines deleted per day");

    companion object {
        fun fromKey(key: String?): GitHubMetric =
            entries.find { it.name == key } ?: LINES_CHANGED
    }
}

/**
 * Summary of a single commit from the GitHub commits list endpoint.
 * Does NOT include line statistics — those require a separate API call.
 */
data class GitHubCommitSummary(
    val sha: String,
    /** ISO-8601 date string from commit.author.date, e.g. "2024-01-15T10:30:00Z" */
    val date: String,
    val message: String
)

/**
 * Line statistics for a single commit, fetched from the commit detail endpoint.
 */
data class GitHubCommitStats(
    val sha: String,
    val additions: Int,
    val deletions: Int
) {
    val total: Int get() = additions + deletions
}

/**
 * Aggregated weekly statistics for a repository, from the contributor stats endpoint.
 * A single API call returns the entire history grouped by week.
 *
 * @param weekStart Unix timestamp (seconds) for the start of the week (Sunday).
 * @param additions Lines added during this week (all contributors summed).
 * @param deletions Lines deleted during this week (all contributors summed).
 * @param commits   Number of commits during this week (all contributors summed).
 */
data class WeeklyContributorStats(
    val weekStart: Long,
    val additions: Int,
    val deletions: Int,
    val commits: Int
) {
    val total: Int get() = additions + deletions
}

/**
 * Thrown when the GitHub API returns a rate-limit error (HTTP 403 with
 * X-RateLimit-Remaining: 0). Contains the Unix timestamp (seconds) when the
 * limit will reset, so callers can decide whether to wait or give up.
 */
class GitHubRateLimitException(val resetEpochSeconds: Long) :
    Exception("GitHub API rate limit exceeded. Resets at $resetEpochSeconds")

class GitHubApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * Low-level API client for the GitHub REST API (v3).
 *
 * All methods run on [Dispatchers.IO] and return parsed data or throw on error.
 * An optional [token] (GitHub Personal Access Token) can be supplied to raise
 * the rate limit from 60 to 5 000 requests/hour.
 */
class GitHubService {

    /**
     * Parses a GitHub repository URL or "owner/repo" shorthand into a
     * (owner, repo) pair.
     *
     * Accepted formats:
     *  - https://github.com/owner/repo
     *  - https://github.com/owner/repo.git
     *  - https://github.com/owner/repo/tree/main
     *  - git@github.com:owner/repo.git
     *  - owner/repo
     *
     * Returns null if the input cannot be parsed.
     */
    fun parseRepoUrl(input: String): Pair<String, String>? {
        val trimmed = input.trim().removeSuffix("/")
        if (trimmed.isEmpty()) return null

        // Handle "owner/repo" shorthand
        if (!trimmed.contains("/") || (!trimmed.contains("github.com") && trimmed.count { it == '/' } == 1)) {
            val parts = trimmed.split("/")
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                val repo = parts[1].removeSuffix(".git")
                return parts[0] to repo
            }
        }

        // Extract from URL path
        val afterHost: String = when {
            trimmed.contains("github.com/") -> trimmed.substringAfter("github.com/")
            trimmed.contains("github.com:") -> trimmed.substringAfter("github.com:").removePrefix("/")
            else -> return null
        }

        val cleanPath = afterHost
            .removePrefix("/")
            .removeSuffix("/")
            .substringBefore("?")  // strip query params
            .substringBefore("#")  // strip fragments

        // Remove trailing paths like /tree/main, /blob/..., /issues, etc.
        val segments = cleanPath.split("/").filter { it.isNotEmpty() }
        if (segments.size < 2) return null

        val owner = segments[0]
        val repo = segments[1].removeSuffix(".git")

        if (owner.isBlank() || repo.isBlank()) return null
        return owner to repo
    }

    /**
     * Validates that a repository exists and is accessible.
     * Returns true if the repo is reachable, false otherwise.
     */
    suspend fun validateRepo(owner: String, repo: String, token: String? = null): Boolean =
        withContext(Dispatchers.IO) {
            try {
                httpGetRaw("$BASE_URL/repos/$owner/$repo", token)
                true
            } catch (e: Exception) {
                Log.w(TAG, "validateRepo failed for $owner/$repo: ${e.message}")
                false
            }
        }

    /**
     * Fetches one page of commits (up to 100) from the repository.
     * The GitHub commits endpoint returns a JSON array (not an object).
     *
     * @param page 1-based page number.
     * @return List of commit summaries (sha, date, message). Empty list when
     *         there are no more commits on the requested page.
     */
    suspend fun fetchCommits(
        owner: String,
        repo: String,
        page: Int = 1,
        perPage: Int = 100,
        token: String? = null
    ): List<GitHubCommitSummary> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/repos/$owner/$repo/commits?per_page=$perPage&page=$page"
        val body = httpGetRaw(url, token)
        val arr = JSONArray(body)
        (0 until arr.length()).mapNotNull { i ->
            try {
                val c = arr.getJSONObject(i)
                val sha = c.optString("sha", "")
                val commit = c.optJSONObject("commit")
                val author = commit?.optJSONObject("author")
                val date = author?.optString("date", "") ?: ""
                val message = commit?.optString("message", "") ?: ""
                if (sha.isNotEmpty() && date.isNotEmpty()) {
                    GitHubCommitSummary(sha, date, message)
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse commit at index $i: ${e.message}")
                null
            }
        }
    }

    /**
     * Fetches the line statistics (additions / deletions) for a single commit.
     * Requires a separate API call because the commits list endpoint does not
     * include stats.
     */
    suspend fun fetchCommitStats(
        owner: String,
        repo: String,
        sha: String,
        token: String? = null
    ): GitHubCommitStats? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/repos/$owner/$repo/commits/$sha"
            val body = httpGetRaw(url, token)
            val json = JSONObject(body)
            val stats = json.optJSONObject("stats")
            GitHubCommitStats(
                sha = sha,
                additions = stats?.optInt("additions", 0) ?: 0,
                deletions = stats?.optInt("deletions", 0) ?: 0
            )
        } catch (e: GitHubRateLimitException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch stats for $sha: ${e.message}")
            null
        }
    }

    /**
     * Fetches aggregated weekly statistics for the entire repository using the
     * Contributor Stats API: GET /repos/{owner}/{repo}/stats/contributors
     *
     * This is a SINGLE API call that returns additions, deletions, and commits
     * per week for the entire repo history — far more efficient than fetching
     * per-commit stats (which requires 1 API call per commit).
     *
     * The endpoint may return HTTP 202 (Accepted) while statistics are being
     * computed server-side. This method retries up to 3 times with a delay.
     *
     * @return List of [WeeklyContributorStats], one per week with activity.
     */
    suspend fun fetchContributorStats(
        owner: String,
        repo: String,
        token: String? = null
    ): List<WeeklyContributorStats> = withContext(Dispatchers.IO) {
        val url = "$BASE_URL/repos/$owner/$repo/stats/contributors"
        var attempt = 0
        while (attempt < 4) {
            try {
                val (code, body) = httpGetRawWithCode(url, token)
                if (code == 202) {
                    // Stats are being computed — wait and retry
                    Log.d(TAG, "Contributor stats being computed (202), retry ${attempt + 1}/4")
                    attempt++
                    kotlinx.coroutines.delay(3000L)
                    continue
                }
                if (body.isBlank()) {
                    attempt++
                    kotlinx.coroutines.delay(2000L)
                    continue
                }
                val arr = JSONArray(body)
                val weeklyMap = mutableMapOf<Long, WeeklyContributorStats>()
                // Aggregate across all contributors
                for (i in 0 until arr.length()) {
                    val contributor = arr.getJSONObject(i)
                    val weeks = contributor.optJSONArray("weeks") ?: continue
                    for (w in 0 until weeks.length()) {
                        val week = weeks.getJSONObject(w)
                        val weekStart = week.optLong("w", 0)
                        if (weekStart == 0L) continue
                        val additions = week.optInt("a", 0)
                        val deletions = week.optInt("d", 0)
                        val commits = week.optInt("c", 0)
                        if (commits == 0 && additions == 0 && deletions == 0) continue
                        // Sum across contributors
                        val existing = weeklyMap[weekStart]
                        if (existing == null) {
                            weeklyMap[weekStart] = WeeklyContributorStats(weekStart, additions, deletions, commits)
                        } else {
                            weeklyMap[weekStart] = WeeklyContributorStats(
                                weekStart,
                                existing.additions + additions,
                                existing.deletions + deletions,
                                existing.commits + commits
                            )
                        }
                    }
                }
                val result = weeklyMap.values.sortedBy { it.weekStart }
                Log.d(TAG, "Fetched ${result.size} weeks of contributor stats for $owner/$repo")
                return@withContext result
            } catch (e: GitHubRateLimitException) {
                Log.w(TAG, "Rate limited fetching contributor stats")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch contributor stats: ${e.message}")
                attempt++
                if (attempt >= 4) return@withContext emptyList()
                kotlinx.coroutines.delay(2000L)
            }
        }
        emptyList()
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    /**
     * Makes an HTTP GET request and returns both the HTTP status code and the
     * raw response body. Handles rate-limit detection. Does NOT throw for
     * HTTP 202 (Accepted) — the caller decides what to do with it.
     */
    private fun httpGetRawWithCode(urlStr: String, token: String?): Pair<Int, String> {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        try {
            val code = conn.responseCode

            // Rate limit detection
            val remaining = conn.getHeaderField("X-RateLimit-Remaining")
            if (code == 403 && remaining == "0") {
                val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: 0L
                throw GitHubRateLimitException(reset)
            }

            // For 202 or error codes, try error stream first
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = try {
                stream?.bufferedReader()?.readText() ?: ""
            } catch (_: Exception) { "" }

            return code to body
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Makes an HTTP GET request and returns the raw response body as a string.
     * Throws [GitHubRateLimitException] when rate-limited, [GitHubApiException]
     * for other non-2xx responses.
     */
    private fun httpGetRaw(urlStr: String, token: String?): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        if (!token.isNullOrBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000

        try {
            val code = conn.responseCode

            // Rate limit detection
            val remaining = conn.getHeaderField("X-RateLimit-Remaining")
            if (code == 403 && remaining == "0") {
                val reset = conn.getHeaderField("X-RateLimit-Reset")?.toLongOrNull() ?: 0L
                throw GitHubRateLimitException(reset)
            }

            if (code !in 200..299) {
                val errorBody = try {
                    conn.errorStream?.bufferedReader()?.readText() ?: ""
                } catch (_: Exception) { "" }
                throw GitHubApiException(code, "HTTP $code for $urlStr: ${errorBody.take(200)}")
            }
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
