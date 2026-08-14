package com.example.tail.data

import android.content.Context
import android.net.Uri
import com.example.tail.data.meal.MealLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Cross-habit text search engine.
 *
 * Searches every habit that carries text in any form:
 *  - "Text input" habits (e.g. the movie habit) — dated free-text entries
 *    stored in per-habit JSON logs ([TextInputRepository]).
 *  - Meal habits — meal log titles / summaries / ingredients / health notes
 *    ([MealLogRepository]).
 *  - Dated-entry habits (e.g. dream journal) — text chunks parsed from the
 *    linked source files (same format as [DatedEntryRepository]).
 *  - Per-habit notes configured in edit mode (undated).
 *
 * Matching is fuzzy and forgiving of misspellings via [FuzzyMatcher].
 */

/** Where a search hit came from. */
enum class HabitSearchSource {
    /** Free-text entry of a "Text input" habit (e.g. the movie habit). */
    TEXT_ENTRY,
    /** A meal-habit log (title / summary / ingredients / health notes). */
    MEAL_LOG,
    /** The per-habit note configured in edit mode (undated). */
    HABIT_NOTE,
    /** A dated-entry source file chunk (e.g. dream journal). */
    DATED_ENTRY
}

/** A habit that has any searchable text, plus which kinds of text it has. */
data class SearchableHabitInfo(
    val habitName: String,
    val sources: Set<HabitSearchSource>
)

/**
 * A single search hit.
 *
 * [snippetText] is a short one-line context window around the match;
 * [matchStart]/[matchEnd] are character offsets *within [snippetText]*
 * delimiting the part that matched, so the UI can highlight it.
 */
data class HabitSearchResult(
    val habitName: String,
    val date: LocalDate?,
    val snippetText: String,
    val matchStart: Int,
    val matchEnd: Int,
    val source: HabitSearchSource,
    val score: Int
)

/**
 * Fuzzy text matching that is forgiving of misspellings.
 *
 * Strategy (first that succeeds wins):
 *  1. Exact case-insensitive substring — score 100.
 *  2. Token-based fuzzy — the query is split into words; every query word
 *     must fuzzy-match some word of the text (equality, shared prefix, or a
 *     small Levenshtein distance that grows with word length). Score is the
 *     average per-token score (45–85).
 *
 * The returned [Match] range refers to the *original* text so callers can
 * build highlight snippets around it.
 */
object FuzzyMatcher {

    data class Match(val score: Int, val start: Int, val end: Int)
    data class Snippet(val text: String, val start: Int, val end: Int)

    private val whitespaceRe = Regex("\\s+")
    private val nonWordRe = Regex("[^\\p{L}\\p{N}]")

    private fun clean(s: String): String = nonWordRe.replace(s, "").lowercase()

    /** Max tolerated Levenshtein edits for a word of the given length. */
    fun maxEditsFor(len: Int): Int = when {
        len >= 8 -> 2
        len >= 4 -> 1
        else -> 0
    }

    /** Classic Levenshtein distance with an early abort once [max] is exceeded. */
    fun levenshtein(a: String, b: String, max: Int = Int.MAX_VALUE): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        if (kotlin.math.abs(a.length - b.length) > max) return max + 1
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > max) return max + 1
            val tmp = prev
            prev = cur
            cur = tmp
        }
        return prev[b.length]
    }

    /** One word of the text with its character span in the original string. */
    private class WordSpan(val raw: String, val key: String, val start: Int, val end: Int)

    private fun wordSpans(text: String): List<WordSpan> {
        val spans = mutableListOf<WordSpan>()
        var i = 0
        while (i < text.length) {
            if (text[i].isWhitespace()) {
                i++
                continue
            }
            val start = i
            while (i < text.length && !text[i].isWhitespace()) i++
            val raw = text.substring(start, i)
            spans.add(WordSpan(raw, clean(raw), start, i))
        }
        return spans
    }

    /** Similarity score of a query token against a single word, or -1 when unrelated. */
    private fun scoreWord(token: String, word: String): Int {
        if (word == token) return 85
        if (token.length >= 3 && word.length >= 3 &&
            (word.startsWith(token) || token.startsWith(word))
        ) return 75
        val maxEdits = maxEditsFor(maxOf(token.length, word.length))
        if (maxEdits > 0) {
            val d = levenshtein(token, word, maxEdits)
            if (d <= maxEdits) return 65 - d * 10
        }
        return -1
    }

    /**
     * Finds the best fuzzy occurrence of [query] inside [text].
     * Returns null when the text is not a match for the query at all.
     */
    fun findMatch(query: String, text: String): Match? {
        val q = query.trim()
        if (q.isEmpty() || text.isEmpty()) return null

        // 1) Fast path — exact (case-insensitive) substring.
        val lowerText = text.lowercase()
        val lowerQuery = q.lowercase()
        val idx = lowerText.indexOf(lowerQuery)
        if (idx >= 0) return Match(100, idx, idx + q.length)

        // 2) Token fuzzy path — every query token must fuzzy-match some word.
        val tokens = q.split(whitespaceRe).map { clean(it) }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val spans = wordSpans(text)
        if (spans.isEmpty()) return null

        var total = 0
        var bestSpan: WordSpan? = null
        var bestSpanScore = -1
        for (token in tokens) {
            var tokenBest = -1
            var tokenSpan: WordSpan? = null
            for (span in spans) {
                if (span.key.isEmpty()) continue
                val s = scoreWord(token, span.key)
                if (s > tokenBest) {
                    tokenBest = s
                    tokenSpan = span
                }
            }
            if (tokenBest < 0 || tokenSpan == null) return null // a required token is missing
            total += tokenBest
            if (tokenBest > bestSpanScore) {
                bestSpanScore = tokenBest
                bestSpan = tokenSpan
            }
        }
        val span = bestSpan ?: return null
        return Match(total / tokens.size, span.start, span.end)
    }

    /**
     * Builds a one-line context window around [match] in [text].
     * The returned start/end are offsets within the returned snippet string,
     * pointing at the matched portion so the UI can render it highlighted.
     */
    fun buildSnippet(text: String, match: Match, contextChars: Int = 42): Snippet {
        // Flatten newlines 1:1 so match indices stay valid in the snippet source.
        val flat = text.replace('\n', ' ').replace('\r', ' ')
        var start = (match.start - contextChars).coerceAtLeast(0)
        var end = (match.end + contextChars).coerceAtMost(flat.length)
        // Expand to word boundaries so we never cut a word in half.
        while (start > 0 && !flat[start - 1].isWhitespace()) start--
        while (end < flat.length && !flat[end].isWhitespace()) end++
        val prefix = if (start > 0) "…" else ""
        val suffix = if (end < flat.length) "…" else ""
        val snippet = prefix + flat.substring(start, end) + suffix
        return Snippet(
            text = snippet,
            start = match.start - start + prefix.length,
            end = match.end - start + prefix.length
        )
    }
}

/** Search execution + source enumeration. */
object HabitSearcher {

    // Date-header patterns mirrored from DatedEntryRepository (parse_dreams.py format).
    private val shortDateRe = Regex("""^(\d{1,2})/(\d{1,2})/(\d{2})(?![/\d])""")
    private val longDateRe = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})(?!\d)""")
    private val timestampRe = Regex("""^\d{2}:\d{2}:\d{2}$""")

    private const val MAX_RESULTS = 200

    /** Habits that have any searchable text, with the kinds of text they have. */
    fun searchableHabits(settings: AppSettings): List<SearchableHabitInfo> {
        val names = buildSet {
            addAll(settings.textInputHabits)
            addAll(settings.mealHabits)
            addAll(settings.datedEntryHabits)
            settings.habitNotes.forEach { (name, note) -> if (note.isNotBlank()) add(name) }
        }
        return names.map { name ->
            SearchableHabitInfo(
                habitName = name,
                sources = buildSet {
                    if (name in settings.textInputHabits) add(HabitSearchSource.TEXT_ENTRY)
                    if (name in settings.mealHabits) add(HabitSearchSource.MEAL_LOG)
                    if (name in settings.datedEntryHabits) add(HabitSearchSource.DATED_ENTRY)
                    if (settings.habitNotes[name]?.isNotBlank() == true) add(HabitSearchSource.HABIT_NOTE)
                }
            )
        }.sortedBy { it.habitName.lowercase() }
    }

    /**
     * Searches all text-bearing habits for [query], honouring the [allowedHabits]
     * filter (habit names). Runs on [Dispatchers.IO]. Results are sorted by
     * relevance (score, descending), then date (newest first), capped at
     * [MAX_RESULTS].
     */
    suspend fun search(
        context: Context,
        settings: AppSettings,
        textInputRepo: TextInputRepository,
        mealLogRepo: MealLogRepository,
        query: String,
        allowedHabits: Set<String>
    ): List<HabitSearchResult> = withContext(Dispatchers.IO) {
        val out = mutableListOf<HabitSearchResult>()

        // ── Text-input habits (e.g. the movie habit) ────────────────────────
        for (habitName in settings.textInputHabits) {
            if (habitName !in allowedHabits) continue
            val uriStr = settings.textInputFileUris[habitName] ?: continue
            var log: Map<String, String> = emptyMap()
            try {
                log = textInputRepo.loadTextLog(Uri.parse(uriStr), context)
            } catch (_: Exception) {
                // fall through to the internal backup below
            }
            if (log.isEmpty()) {
                log = try {
                    textInputRepo.loadInternalBackup(context, habitName) ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            for ((ts, text) in log) {
                if (text.isBlank()) continue
                val m = FuzzyMatcher.findMatch(query, text) ?: continue
                val date = tryParseDate(ts.take(10))
                out += buildResult(habitName, date, text, m, HabitSearchSource.TEXT_ENTRY)
            }
        }

        // ── Meal habits ─────────────────────────────────────────────────────
        for (habitName in settings.mealHabits) {
            if (habitName !in allowedHabits) continue
            val logs = try {
                mealLogRepo.loadLogs(habitName)
            } catch (_: Exception) {
                emptyList()
            }
            for (meal in logs) {
                val parts = buildList {
                    if (meal.title.isNotBlank()) add(meal.title)
                    if (!meal.summary.isNullOrBlank()) add(meal.summary)
                    if (meal.ingredientsDetected.isNotEmpty()) add(meal.ingredientsDetected.joinToString(", "))
                    if (!meal.healthNotes.isNullOrBlank()) add(meal.healthNotes)
                }
                val text = parts.joinToString(" — ")
                if (text.isBlank()) continue
                val m = FuzzyMatcher.findMatch(query, text) ?: continue
                val date = Instant.ofEpochMilli(meal.timestamp)
                    .atZone(ZoneId.systemDefault()).toLocalDate()
                out += buildResult(habitName, date, text, m, HabitSearchSource.MEAL_LOG)
            }
        }

        // ── Dated-entry habits (e.g. dream journal source files) ────────────
        for (habitName in settings.datedEntryHabits) {
            if (habitName !in allowedHabits) continue
            val uriStr = settings.datedEntryFileUris[habitName] ?: continue
            val lines = try {
                context.contentResolver.openInputStream(Uri.parse(uriStr))
                    ?.bufferedReader()?.readLines()
            } catch (_: Exception) {
                null
            } ?: continue
            for ((dateStr, chunk) in parseDatedChunks(lines)) {
                if (chunk.isBlank()) continue
                val m = FuzzyMatcher.findMatch(query, chunk) ?: continue
                out += buildResult(habitName, tryParseDate(dateStr), chunk, m, HabitSearchSource.DATED_ENTRY)
            }
        }

        // ── Per-habit notes (undated) ───────────────────────────────────────
        for ((habitName, note) in settings.habitNotes) {
            if (note.isBlank() || habitName !in allowedHabits) continue
            val m = FuzzyMatcher.findMatch(query, note) ?: continue
            out += buildResult(habitName, null, note, m, HabitSearchSource.HABIT_NOTE)
        }

        out.sortedWith(
            compareByDescending<HabitSearchResult> { it.score }
                .thenByDescending { it.date ?: LocalDate.MIN }
        ).take(MAX_RESULTS)
    }

    private fun buildResult(
        habitName: String,
        date: LocalDate?,
        text: String,
        m: FuzzyMatcher.Match,
        source: HabitSearchSource
    ): HabitSearchResult {
        val snip = FuzzyMatcher.buildSnippet(text, m)
        return HabitSearchResult(
            habitName = habitName,
            date = date,
            snippetText = snip.text,
            matchStart = snip.start,
            matchEnd = snip.end,
            source = source,
            score = m.score
        )
    }

    private fun tryParseDate(s: String): LocalDate? = try {
        LocalDate.parse(s)
    } catch (_: Exception) {
        null
    }

    /**
     * Parses dated-entry file lines into (dateString, chunkText) pairs.
     * Mirrors [DatedEntryRepository]'s format: date headers (M/D/YY or
     * YYYY-MM-DD, optionally prefixed with markdown #'s) followed by chunks
     * separated by blank lines or ",,," separator lines.
     */
    internal fun parseDatedChunks(lines: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        var currentDate: String? = null
        val currentChunk = mutableListOf<String>()

        fun flushChunk() {
            val d = currentDate ?: return
            val text = currentChunk.joinToString(" ").trim()
            if (text.isNotEmpty()) out += d to text
            currentChunk.clear()
        }

        for (line in lines) {
            val (date, rest) = parseDateHeader(line)
            if (date != null) {
                flushChunk()
                currentDate = date
                if (!rest.isNullOrEmpty()) currentChunk += rest
            } else {
                val s = line.trim()
                if (s.isEmpty() || s == ",,,") {
                    flushChunk()
                } else if (currentDate != null) {
                    currentChunk += s
                }
            }
        }
        flushChunk()
        return out
    }

    private fun parseDateHeader(line: String): Pair<String?, String?> {
        val stripped = line.trim().trimStart('#').trim()

        // Try short format  M/D/YY
        shortDateRe.find(stripped)?.let { m ->
            val month = m.groupValues[1].toIntOrNull() ?: return@let
            val day = m.groupValues[2].toIntOrNull() ?: return@let
            val yr2 = m.groupValues[3].toIntOrNull() ?: return@let
            val year = 2000 + yr2
            if (!isValidDate(year, month, day)) return@let
            val rest = stripped.substring(m.range.last + 1).trim()
            val cleanRest = if (timestampRe.matches(rest)) "" else rest
            return "%04d-%02d-%02d".format(year, month, day) to cleanRest
        }

        // Try long format  YYYY-MM-DD
        longDateRe.find(stripped)?.let { m ->
            val year = m.groupValues[1].toIntOrNull() ?: return@let
            val month = m.groupValues[2].toIntOrNull() ?: return@let
            val day = m.groupValues[3].toIntOrNull() ?: return@let
            if (!isValidDate(year, month, day)) return@let
            val rest = stripped.substring(m.range.last + 1).trim()
            val cleanRest = if (timestampRe.matches(rest)) "" else rest
            return "%04d-%02d-%02d".format(year, month, day) to cleanRest
        }

        return null to null
    }

    private fun isValidDate(year: Int, month: Int, day: Int): Boolean {
        if (month < 1 || month > 12) return false
        if (day < 1 || day > 31) return false
        if (year < 1900 || year > 2200) return false
        return true
    }
}
