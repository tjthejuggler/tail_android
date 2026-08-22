package com.example.tail

import com.example.tail.data.OmdbService
import com.example.tail.data.OmdbService.Companion.scoreCandidate
import com.example.tail.data.OmdbService.Companion.typeFits
import com.example.tail.data.ParsedTitle
import com.example.tail.data.SuggestionCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure title-parsing and fuzzy-matching logic in
 * [OmdbService] (no network / no Android framework calls).
 */
class OmdbServiceTest {

    // ── parseTitle ─────────────────────────────────────────────────────────

    @Test
    fun parseTitle_stripsDurationSuffix() {
        val p = OmdbService.parseTitle("Inception (148 min)")
        assertEquals("Inception", p.title)
        assertNull(p.season)
        assertNull(p.year)
    }

    @Test
    fun parseTitle_extractsMinutesFromSuffix() {
        assertEquals(148, OmdbService.parseTitle("Inception (148 min)").minutes)
        assertEquals(105, OmdbService.parseTitle("A Different Man (2024) (105 min)").minutes)
        assertEquals(47, OmdbService.parseTitle("Breaking Bad S05E14 (47 min)").minutes)
        assertNull(OmdbService.parseTitle("Inception").minutes)
    }

    // ── stripDurationAnnotation ────────────────────────────────────────────

    @Test
    fun stripDurationAnnotation_removesOnlyTheTrailingLength() {
        assertEquals("Inception", OmdbService.stripDurationAnnotation("Inception (148 min)"))
        assertEquals("Breaking Bad S05E14", OmdbService.stripDurationAnnotation("Breaking Bad S05E14 (47 min)"))
        // A release year is NOT a duration annotation — it must survive.
        assertEquals("A Different Man (2024)", OmdbService.stripDurationAnnotation("A Different Man (2024) (105 min)"))
    }

    @Test
    fun stripDurationAnnotation_leavesBareTitlesAlone() {
        assertEquals("Inception", OmdbService.stripDurationAnnotation("Inception"))
        assertEquals("", OmdbService.stripDurationAnnotation(""))
    }

    // ── aggregateMinutesByDate ─────────────────────────────────────────────

    @Test
    fun aggregateMinutesByDate_sumsAnnotatedEntriesPerDay() {
        val log = mapOf(
            "2026-08-20 21:30:00" to "Dune (166 min)",
            "2026-08-20 23:59:00" to "Fleabag S01E04 (27 min)",
            "2026-08-21 20:00:00" to "Inception (148 min)"
        )
        val byDate = OmdbService.aggregateMinutesByDate(log)
        assertEquals(193, byDate["2026-08-20"])
        assertEquals(148, byDate["2026-08-21"])
    }

    @Test
    fun aggregateMinutesByDate_skipsUnannotatedEntriesAndBadKeys() {
        val log = mapOf(
            "2026-08-20 21:30:00" to "Some Movie", // no "(N min)" → skipped
            "short" to "Dune (166 min)"            // not a timestamp key → skipped
        )
        assertTrue(OmdbService.aggregateMinutesByDate(log).isEmpty())
    }

    // ── parseRuntime ───────────────────────────────────────────────────────

    @Test
    fun parseRuntime_omdbFormats() {
        assertEquals(142, OmdbService.parseRuntime("142 min"))
        assertEquals(45, OmdbService.parseRuntime("45 min"))
        assertNull(OmdbService.parseRuntime("N/A"))
        assertNull(OmdbService.parseRuntime(""))
    }

    // ── splitEvenly ────────────────────────────────────────────────────────

    @Test
    fun splitEvenly_evenSplit() {
        assertEquals(listOf(50, 50), OmdbService.splitEvenly(100, 2))
        assertEquals(listOf(33, 33, 33), OmdbService.splitEvenly(99, 3))
    }

    @Test
    fun splitEvenly_remainderGoesToEarlierParts() {
        val parts = OmdbService.splitEvenly(142, 3)
        assertEquals(3, parts.size)
        assertEquals(142, parts.sum())
        assertEquals(48, parts[0])
        assertEquals(47, parts[1])
        assertEquals(47, parts[2])
    }

    @Test
    fun splitEvenly_sumsBackToTotal() {
        assertEquals(97, OmdbService.splitEvenly(97, 4).sum())
        assertEquals(1, OmdbService.splitEvenly(1, 5).sum())
        assertEquals(0, OmdbService.splitEvenly(0, 3).sum())
        assertTrue(OmdbService.splitEvenly(10, 0).isEmpty())
    }

    @Test
    fun parseTitle_extractsYearFromMovies() {
        val p = OmdbService.parseTitle("A Different Man (2024) (105 min)")
        assertEquals("A Different Man", p.title)
        assertEquals(2024, p.year)
        assertFalse(p.isEpisode)
    }

    @Test
    fun parseTitle_episodeWithSeasonEpisode() {
        val p = OmdbService.parseTitle("Breaking Bad S05E14 (47 min)")
        assertEquals("Breaking Bad", p.title)
        assertEquals(5, p.season)
        assertEquals(14, p.episode)
        assertTrue(p.isEpisode)
        assertNull(p.year)
    }

    @Test
    fun parseTitle_showWithProductionYear() {
        val p = OmdbService.parseTitle("Show Name (2019) S01E02")
        assertEquals("Show Name", p.title)
        assertEquals(2019, p.year)
        assertEquals(1, p.season)
        assertEquals(2, p.episode)
    }

    @Test
    fun parseTitle_stripsLeadingBracketJunk() {
        val p = OmdbService.parseTitle("[Torrentcouch Com] Black Mirror S02E03")
        assertEquals("Black Mirror", p.title)
        assertEquals(2, p.season)
        assertEquals(3, p.episode)
    }

    @Test
    fun parseTitle_dotsAndUnderscoresToSpaces() {
        val p = OmdbService.parseTitle("The.Movie_Name (2010)")
        assertEquals("The Movie Name", p.title)
        assertEquals(2010, p.year)
    }

    @Test
    fun parseTitle_bracketedYearAlsoRecognised() {
        val p = OmdbService.parseTitle("Anora [2024]")
        assertEquals("Anora", p.title)
        assertEquals(2024, p.year)
    }

    // ── cache keys ─────────────────────────────────────────────────────────

    @Test
    fun cacheKey_includesYearForMovies() {
        val p = OmdbService.parseTitle("Anora (2024)")
        assertEquals("anora::y2024", p.cacheKey)
    }

    @Test
    fun cacheKey_episodeFormatUnchanged() {
        val p = OmdbService.parseTitle("Breaking Bad S05E14")
        assertEquals("breaking bad::s5e14", p.cacheKey)
    }

    @Test
    fun idCacheKey_sharedAcrossEpisodesOfOneShow() {
        val e1 = OmdbService.parseTitle("Severance S01E01")
        val e2 = OmdbService.parseTitle("Severance S02E05")
        assertEquals(e1.idCacheKey, e2.idCacheKey)
    }

    // ── fuzzy matching helpers ─────────────────────────────────────────────

    @Test
    fun normalize_stripsPunctuationAndCase() {
        assertEquals("the queen s gambit", OmdbService.normalizeForCompare("The Queen's Gambit!"))
        assertEquals("would i lie to you", OmdbService.normalizeForCompare("Would I Lie to You?"))
    }

    @Test
    fun levenshtein_classicDistance() {
        assertEquals(3, OmdbService.levenshtein("kitten", "sitting"))
        assertEquals(0, OmdbService.levenshtein("same", "same"))
        assertEquals(5, OmdbService.levenshtein("", "hello"))
    }

    @Test
    fun similarity_punctuationDifferencesAreCheap() {
        // Apostrophes normalise to a space ("handmaid's" → "handmaid s"), so the
        // distance is tiny — far above the 0.62 acceptance threshold.
        assertTrue(OmdbService.similarity("The Handmaids Tale", "The Handmaid's Tale") >= 0.9)
        assertEquals(1.0, OmdbService.similarity("A Quiet Place Day One", "A Quiet Place: Day One"), 1e-9)
    }

    // ── candidate scoring (validated against real watch history) ───────────

    private fun candidate(title: String, type: String?, year: Int?) =
        SuggestionCandidate(imdbID = "tt0000001", title = title, type = type, year = year)

    @Test
    fun scoring_missingApostropheStillMatches() {
        val parsed = ParsedTitle("The Handmaids Tale")
        val score = scoreCandidate(parsed, candidate("The Handmaid's Tale", "TV series", 2017))
        assertTrue("score=$score", score >= 0.62)
    }

    @Test
    fun scoring_yearBoostsCorrectMovie() {
        val parsed = ParsedTitle("A Quiet Place Day One", year = 2024)
        val good = scoreCandidate(parsed, candidate("A Quiet Place: Day One", "feature", 2024))
        val wrongYear = scoreCandidate(parsed, candidate("A Quiet Place", "feature", 2018))
        assertTrue(good > wrongYear)
        assertTrue(good >= 0.62)
    }

    @Test
    fun scoring_junkTitlesRejected() {
        val parsed = ParsedTitle("04Castling Rules")
        val score = scoreCandidate(parsed, candidate("Some Chess Documentary", "documentary", 2015))
        assertTrue("score=$score", score < 0.62)
    }

    @Test
    fun scoring_countrySuffixStillMatches() {
        val parsed = ParsedTitle("The Diplomat Us")
        val score = scoreCandidate(parsed, candidate("The Diplomat", "TV series", 2023))
        assertTrue("score=$score", score >= 0.62)
    }

    // ── type filtering ─────────────────────────────────────────────────────

    @Test
    fun typeFits_episodesRequireSeries() {
        assertTrue(typeFits(isEpisode = true, type = "TV series"))
        assertTrue(typeFits(isEpisode = true, type = "TV mini-series"))
        assertFalse(typeFits(isEpisode = true, type = "feature"))
        assertFalse(typeFits(isEpisode = true, type = null))
    }

    @Test
    fun typeFits_moviesRequireFilmTypes() {
        assertTrue(typeFits(isEpisode = false, type = "feature"))
        assertTrue(typeFits(isEpisode = false, type = "TV movie"))
        assertFalse(typeFits(isEpisode = false, type = "TV series"))
        assertFalse(typeFits(isEpisode = false, type = "videoGame"))
    }
}
