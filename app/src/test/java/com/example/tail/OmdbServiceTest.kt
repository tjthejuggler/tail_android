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
