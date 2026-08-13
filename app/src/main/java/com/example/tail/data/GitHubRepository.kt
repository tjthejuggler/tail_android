package com.example.tail.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val TAG = "GitHubRepo"

/**
 * Per-day integer values for a single metric, keyed by date string "YYYY-MM-DD".
 */
typealias DailyValuesMap = Map<String, Int>

/**
 * Processes GitHub API data into per-day values for a given [GitHubMetric],
 * and caches per-commit line statistics to avoid re-fetching expensive API calls.
 *
 * Cache is stored as a single JSON file per repo in the app's internal storage
 * under `github_cache/`. Each file is named `{owner}_{repo}_stats.json` and
 * contains a map of SHA → { additions, deletions }.
 *
 * The commits list endpoint is cheap (100 commits per request) and is always
 * fetched fresh. The per-commit stats endpoint is expensive (1 request per
 * commit) and is cached permanently per SHA — a commit's stats never change.
 */
class GitHubRepository(private val context: Context) {

    private val service = GitHubService()

    private val cacheDir: File
        get() = File(context.filesDir, "github_cache").also { it.mkdirs() }

    /**
     * Fetches the entire commit history for a repository and computes per-day
     * values for the given [metric].
     *
     * For [GitHubMetric.COMMITS], only the commits list endpoint is used
     * (efficient — 100 commits per request).
     *
     * For line-based metrics ([GitHubMetric.LINES_CHANGED], [GitHubMetric.ADDITIONS],
     * [GitHubMetric.DELETIONS]), each commit's stats are fetched individually.
     * Already-cached SHAs are skipped. Rate-limit-aware: if the GitHub API
     * returns a rate-limit error, fetching stops and the partial results
     * collected so far are returned (the caller can retry later to continue).
     *
     * @param onProgress Called with (commitsProcessed, totalCommitsEstimated).
     * @param onRateLimited Called if the rate limit is hit, with the reset time.
     * @return Map of "YYYY-MM-DD" → metric value.
     */
    suspend fun fetchBacklog(
        owner: String,
        repo: String,
        metric: GitHubMetric,
        token: String? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onRateLimited: (Long) -> Unit = {}
    ): DailyValuesMap = withContext(Dispatchers.IO) {
        // ── Step 1: Fetch all commit dates (cheap — 100 commits per request) ──
        // This gives us per-day commit counts for accurate daily distribution.
        val dailyCommitCounts = mutableMapOf<String, Int>()
        var page = 1
        var totalCommits = 0
        var hitRateLimit = false

        while (!hitRateLimit) {
            val commits = try {
                service.fetchCommits(owner, repo, page = page, token = token)
            } catch (e: GitHubRateLimitException) {
                Log.w(TAG, "Rate limited on commits page $page")
                onRateLimited(e.resetEpochSeconds)
                hitRateLimit = true
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch commits page $page: ${e.message}")
                break
            }

            if (commits.isEmpty()) break

            for (commit in commits) {
                val dateStr = parseCommitDate(commit.date) ?: continue
                dailyCommitCounts[dateStr] = (dailyCommitCounts[dateStr] ?: 0) + 1
                totalCommits++
            }

            val estimatedTotal = if (commits.size == 100) totalCommits + 100 else totalCommits
            onProgress(totalCommits, estimatedTotal)

            if (commits.size < 100) break
            page++
            delay(100)
        }

        Log.d(TAG, "Fetched $totalCommits commits across ${dailyCommitCounts.size} days" +
            if (hitRateLimit) " (rate limited — partial)" else "")

        // For COMMITS metric, we're done — just return daily counts
        if (metric == GitHubMetric.COMMITS) {
            return@withContext dailyCommitCounts
        }

        // ── Step 2: Fetch aggregated weekly stats (1 API call for entire history) ──
        val weeklyStats = try {
            service.fetchContributorStats(owner, repo, token)
        } catch (e: GitHubRateLimitException) {
            Log.w(TAG, "Rate limited fetching contributor stats")
            onRateLimited(e.resetEpochSeconds)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch contributor stats: ${e.message}")
            emptyList()
        }

        if (weeklyStats.isEmpty()) {
            Log.w(TAG, "No weekly stats available — falling back to commits count only")
            // Fall back to per-commit stats with caching (old approach)
            return@withContext fetchBacklogPerCommit(owner, repo, metric, token, dailyCommitCounts, onProgress, onRateLimited)
        }

        // ── Step 3: Distribute weekly totals across days with commits ──
        val dailyValues = mutableMapOf<String, Int>()

        for (week in weeklyStats) {
            val weekValue = when (metric) {
                GitHubMetric.LINES_CHANGED -> week.total
                GitHubMetric.ADDITIONS -> week.additions
                GitHubMetric.DELETIONS -> week.deletions
                else -> 0
            }
            if (weekValue == 0) continue

            // Find which days in this week had commits
            val weekStartDate = Instant.ofEpochSecond(week.weekStart)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            val weekDates = (0..6).map { d ->
                dateString(weekStartDate.plusDays(d.toLong()))
            }
            val commitDays = weekDates.filter { dailyCommitCounts.containsKey(it) }

            if (commitDays.isEmpty()) {
                // No commit data for this week — assign to week start
                dailyValues[weekDates[0]] = (dailyValues[weekDates[0]] ?: 0) + weekValue
            } else {
                // Distribute proportionally based on commit count per day
                val totalCommitsThisWeek = commitDays.sumOf { dailyCommitCounts[it] ?: 0 }
                if (totalCommitsThisWeek > 0) {
                    for (day in commitDays) {
                        val dayCommits = dailyCommitCounts[day] ?: 0
                        val proportion = dayCommits.toDouble() / totalCommitsThisWeek
                        val dayValue = (weekValue * proportion).toInt().coerceAtLeast(0)
                        if (dayValue > 0) {
                            dailyValues[day] = (dailyValues[day] ?: 0) + dayValue
                        }
                    }
                }
            }
        }

        Log.d(TAG, "Backlog for $owner/$repo ($metric): ${dailyValues.size} days, " +
            "$totalCommits commits, ${weeklyStats.size} weeks of stats" +
            if (hitRateLimit) " (partial — rate limited on commits)" else "")

        dailyValues
    }

    /**
     * Fallback: fetches per-commit stats with SHA-keyed caching.
     * Used only when the contributor stats API is unavailable.
     * Reuses already-fetched [dailyCommitCounts] to know which commits to process.
     */
    private suspend fun fetchBacklogPerCommit(
        owner: String,
        repo: String,
        metric: GitHubMetric,
        token: String?,
        dailyCommitCounts: Map<String, Int>,
        onProgress: (Int, Int) -> Unit,
        onRateLimited: (Long) -> Unit
    ): DailyValuesMap = withContext(Dispatchers.IO) {
        val dailyValues = mutableMapOf<String, Int>()
        val statsCache = loadStatsCache(owner, repo)
        var page = 1
        var totalProcessed = 0
        var hitRateLimit = false

        while (!hitRateLimit) {
            val commits = try {
                service.fetchCommits(owner, repo, page = page, token = token)
            } catch (e: GitHubRateLimitException) {
                onRateLimited(e.resetEpochSeconds)
                hitRateLimit = true
                emptyList()
            } catch (e: Exception) {
                break
            }

            if (commits.isEmpty()) break

            for (commit in commits) {
                val dateStr = parseCommitDate(commit.date) ?: continue
                var stats = statsCache[commit.sha]
                if (stats == null) {
                    stats = try {
                        service.fetchCommitStats(owner, repo, commit.sha, token)
                    } catch (e: GitHubRateLimitException) {
                        onRateLimited(e.resetEpochSeconds)
                        hitRateLimit = true
                        null
                    }
                    if (stats != null) statsCache[commit.sha] = stats
                }
                if (stats != null) {
                    val value = when (metric) {
                        GitHubMetric.LINES_CHANGED -> stats.total
                        GitHubMetric.ADDITIONS -> stats.additions
                        GitHubMetric.DELETIONS -> stats.deletions
                        else -> 0
                    }
                    dailyValues[dateStr] = (dailyValues[dateStr] ?: 0) + value
                }
                totalProcessed++
            }

            saveStatsCache(owner, repo, statsCache)
            onProgress(totalProcessed, totalCommitsEstimate(dailyCommitCounts))
            if (commits.size < 100) break
            page++
            delay(200)
        }

        dailyValues
    }

    private fun totalCommitsEstimate(dailyCounts: Map<String, Int>): Int =
        dailyCounts.values.sum()

    /**
     * Fetches recent activity (last ~4 weeks) using the contributor stats API.
     * Only 2 API calls: commits list (page 1) + contributor stats.
     *
     * For COMMITS metric: uses commits list only (1 call).
     * For line-based metrics: uses contributor stats (1 call) + commits list for
     * daily distribution (1 call) = 2 calls total.
     *
     * @return Map of "YYYY-MM-DD" → metric value for recent days.
     */
    suspend fun fetchRecent(
        owner: String,
        repo: String,
        metric: GitHubMetric,
        token: String? = null
    ): DailyValuesMap = withContext(Dispatchers.IO) {
        // Fetch recent commits for daily distribution
        val commits = try {
            service.fetchCommits(owner, repo, page = 1, token = token)
        } catch (e: Exception) {
            Log.e(TAG, "fetchRecent failed: ${e.message}")
            return@withContext emptyMap()
        }

        val dailyCommitCounts = mutableMapOf<String, Int>()
        for (commit in commits) {
            val dateStr = parseCommitDate(commit.date) ?: continue
            dailyCommitCounts[dateStr] = (dailyCommitCounts[dateStr] ?: 0) + 1
        }

        // For COMMITS metric, we're done
        if (metric == GitHubMetric.COMMITS) {
            return@withContext dailyCommitCounts
        }

        // For line-based metrics: use contributor stats (1 API call)
        val weeklyStats = try {
            service.fetchContributorStats(owner, repo, token)
        } catch (e: Exception) {
            Log.e(TAG, "fetchRecent contributor stats failed: ${e.message}")
            return@withContext emptyMap()
        }

        if (weeklyStats.isEmpty()) return@withContext emptyMap()

        // Process the most recent 4 weeks
        val recentWeeks = weeklyStats.takeLast(4)
        val dailyValues = mutableMapOf<String, Int>()

        for (week in recentWeeks) {
            val weekValue = when (metric) {
                GitHubMetric.LINES_CHANGED -> week.total
                GitHubMetric.ADDITIONS -> week.additions
                GitHubMetric.DELETIONS -> week.deletions
                else -> 0
            }
            if (weekValue == 0) continue

            val weekStartDate = Instant.ofEpochSecond(week.weekStart)
                .atZone(ZoneId.systemDefault()).toLocalDate()
            val weekDates = (0..6).map { d -> dateString(weekStartDate.plusDays(d.toLong())) }
            val commitDays = weekDates.filter { dailyCommitCounts.containsKey(it) }

            if (commitDays.isEmpty()) {
                dailyValues[weekDates[0]] = (dailyValues[weekDates[0]] ?: 0) + weekValue
            } else {
                val totalCommitsThisWeek = commitDays.sumOf { dailyCommitCounts[it] ?: 0 }
                if (totalCommitsThisWeek > 0) {
                    for (day in commitDays) {
                        val dayCommits = dailyCommitCounts[day] ?: 0
                        val proportion = dayCommits.toDouble() / totalCommitsThisWeek
                        val dayValue = (weekValue * proportion).toInt().coerceAtLeast(0)
                        if (dayValue > 0) {
                            dailyValues[day] = (dailyValues[day] ?: 0) + dayValue
                        }
                    }
                }
            }
        }

        dailyValues
    }

    /** Validates that a repository exists and is public. */
    suspend fun validateRepo(owner: String, repo: String, token: String? = null): Boolean {
        return service.validateRepo(owner, repo, token)
    }

    /** Parses a GitHub URL or "owner/repo" shorthand into (owner, repo). */
    fun parseRepoUrl(url: String): Pair<String, String>? = service.parseRepoUrl(url)

    /** Clears all cached stats for a specific repository. */
    fun clearCache(owner: String, repo: String) {
        statsCacheFile(owner, repo).delete()
    }

    /** Clears the entire GitHub cache. */
    fun clearAllCache() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    // ── Date parsing ──────────────────────────────────────────────────────────

    /**
     * Parses a GitHub ISO-8601 commit date (e.g. "2024-01-15T10:30:00Z") into
     * a "YYYY-MM-DD" string in the system's local timezone.
     * Returns null if the date cannot be parsed.
     */
    private fun parseCommitDate(isoDate: String): String? {
        return try {
            val instant = Instant.parse(isoDate)
            val localDate = instant.atZone(ZoneId.systemDefault()).toLocalDate()
            dateString(localDate)
        } catch (e: Exception) {
            // Try without trailing Z or timezone offset
            try {
                val datePart = isoDate.substringBefore("T").take(10)
                LocalDate.parse(datePart).format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                )
            } catch (e2: Exception) {
                Log.w(TAG, "Could not parse commit date: $isoDate")
                null
            }
        }
    }

    // ── Stats cache I/O ───────────────────────────────────────────────────────

    private fun statsCacheFile(owner: String, repo: String): File {
        val safeName = "${owner.lowercase()}_${repo.lowercase()}_stats.json"
        return File(cacheDir, safeName)
    }

    private fun loadStatsCache(owner: String, repo: String): MutableMap<String, GitHubCommitStats> {
        val file = statsCacheFile(owner, repo)
        if (!file.exists()) return mutableMapOf()
        return try {
            val json = JSONObject(file.readText())
            val result = mutableMapOf<String, GitHubCommitStats>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val sha = keys.next()
                val obj = json.optJSONObject(sha)
                if (obj != null) {
                    result[sha] = GitHubCommitStats(
                        sha = sha,
                        additions = obj.optInt("additions", 0),
                        deletions = obj.optInt("deletions", 0)
                    )
                }
            }
            Log.d(TAG, "Loaded ${result.size} cached commit stats for $owner/$repo")
            result
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load stats cache: ${e.message}")
            mutableMapOf()
        }
    }

    private fun saveStatsCache(owner: String, repo: String, cache: Map<String, GitHubCommitStats>) {
        val file = statsCacheFile(owner, repo)
        try {
            val json = JSONObject()
            for ((sha, stats) in cache) {
                json.put(sha, JSONObject().apply {
                    put("additions", stats.additions)
                    put("deletions", stats.deletions)
                })
            }
            file.writeText(json.toString())
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save stats cache: ${e.message}")
        }
    }
}
