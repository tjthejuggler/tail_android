package com.example.tail

import com.example.tail.data.FuzzyMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the short-query exact rule of [FuzzyMatcher]:
 *  - query words of ≤ [FuzzyMatcher.SHORT_QUERY_EXACT_MAX] characters must
 *    hit exactly (case-insensitive substring or exact word);
 *  - longer query words stay forgiving of misspellings (prefix + Levenshtein).
 */
class FuzzyMatcherTest {

    // ── Short queries (≤ 4 chars) require an exact hit ─────────────────────

    @Test
    fun `4-letter misspelling does not match longer word`() {
        // "drem" vs "dream" — 1 edit, but short queries get no tolerance.
        assertNull(FuzzyMatcher.findMatch("drem", "I had a weird dream last night"))
    }

    @Test
    fun `4-letter word matches exactly despite capitalization`() {
        val m = FuzzyMatcher.findMatch("DREAM", "I had a weird dream last night")
        assertNotNull(m)
        assertEquals(100, m!!.score) // exact substring fast path
    }

    @Test
    fun `3-letter word matches as exact substring only`() {
        assertNotNull(FuzzyMatcher.findMatch("dog", "hotdog stand"))
        // "dg" is not a substring of "dog" and gets no edit tolerance.
        assertNull(FuzzyMatcher.findMatch("dg", "dog park"))
    }

    @Test
    fun `short token in multi-word query must also hit exactly`() {
        assertNull(FuzzyMatcher.findMatch("drem journal", "dream journal entry"))
        assertNotNull(FuzzyMatcher.findMatch("dream journal", "dream journal entry"))
    }

    @Test
    fun `short word does not prefix-match a longer word via token path`() {
        // "drem" (4) vs "dreams" — no substring hit, no fuzzy allowed.
        assertNull(FuzzyMatcher.findMatch("drem", "dreams and nightmares"))
    }

    // ── Longer queries (> 4 chars) stay misspelling-tolerant ───────────────

    @Test
    fun `long misspelling matches via levenshtein`() {
        // "meditaion" vs "meditation" — 2 edits, allowed for length ≥ 8.
        val m = FuzzyMatcher.findMatch("meditaion", "morning meditation session")
        assertNotNull(m)
    }

    @Test
    fun `5-letter word tolerates one edit`() {
        // "helo" is short (no match), but "hello"-adjacent 5-letter
        // misspelling "hella" vs "hello" is 1 edit → allowed.
        assertNull(FuzzyMatcher.findMatch("helo", "hello world"))
        assertNotNull(FuzzyMatcher.findMatch("hella", "hello world"))
    }

    @Test
    fun `long word prefix-matches longer word`() {
        assertNotNull(FuzzyMatcher.findMatch("medita", "morning meditation session"))
    }

    // ── Edit-distance ladder ────────────────────────────────────────────────

    @Test
    fun `maxEditsFor is zero for short words only`() {
        assertEquals(0, FuzzyMatcher.maxEditsFor(1))
        assertEquals(0, FuzzyMatcher.maxEditsFor(4))
        assertEquals(1, FuzzyMatcher.maxEditsFor(5))
        assertEquals(1, FuzzyMatcher.maxEditsFor(7))
        assertEquals(2, FuzzyMatcher.maxEditsFor(8))
        assertEquals(2, FuzzyMatcher.maxEditsFor(12))
    }
}
