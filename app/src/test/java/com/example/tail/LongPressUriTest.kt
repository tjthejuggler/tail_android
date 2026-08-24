package com.example.tail

import com.example.tail.data.buildObsidianOpenUri
import com.example.tail.data.encodeUriComponent
import com.example.tail.data.hasUriScheme
import com.example.tail.data.normalizeLongPressUri
import com.example.tail.data.uriSchemeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the long-press URI helpers (see LongPressUri.kt).
 *
 * The habit long-press "URL" action accepts any pasted URI — https links
 * and app deep links (obsidian://open?vault=…&file=…). Normalization must
 * preserve valid URIs byte-for-byte, encode human-pasted-but-illegal
 * characters, and be idempotent.
 */
class LongPressUriTest {

    // ── Scheme detection ────────────────────────────────────────────────────

    @Test
    fun `scheme detection accepts custom deep link schemes`() {
        assertTrue(hasUriScheme("obsidian://open?vault=V&file=F"))
        assertTrue(hasUriScheme("spotify:playlist:abc"))
        assertTrue(hasUriScheme("tel:+39 333 1234567"))
        assertTrue(hasUriScheme("mailto:user@example.com"))
        assertTrue(hasUriScheme("https://example.com"))
        assertEquals("obsidian", uriSchemeOf("obsidian://open?vault=V"))
        assertEquals("tel", uriSchemeOf("tel:+393331234567"))
        assertNull(uriSchemeOf("example.com/path"))
    }

    @Test
    fun `scheme detection rejects bare domains and paths`() {
        assertFalse(hasUriScheme("example.com"))
        assertFalse(hasUriScheme("www.example.com/path?q=1"))
        assertFalse(hasUriScheme("/storage/emulated/0/note.md"))
        assertFalse(hasUriScheme(""))
    }

    // ── Normalization ───────────────────────────────────────────────────────

    @Test
    fun `bare domain gets https prefix`() {
        assertEquals("https://example.com", normalizeLongPressUri("example.com"))
        assertEquals("https://example.com/a?b=c", normalizeLongPressUri("example.com/a?b=c"))
    }

    @Test
    fun `custom scheme uris pass through without https prefix`() {
        // Regression guard: the old check `"://" in it` would have mangled
        // scheme-only URIs like tel: into https://tel:…
        assertEquals("tel:+393331234567", normalizeLongPressUri("tel:+393331234567"))
        assertEquals(
            "obsidian://open?vault=V&file=F",
            normalizeLongPressUri("obsidian://open?vault=V&file=F")
        )
    }

    @Test
    fun `clipboard whitespace and wrapping are stripped`() {
        assertEquals("https://example.com", normalizeLongPressUri("  https://example.com\n"))
        assertEquals("https://example.com", normalizeLongPressUri("<https://example.com>"))
        assertEquals("https://example.com", normalizeLongPressUri("\"https://example.com\""))
    }

    @Test
    fun `spaces in pasted deep links are percent encoded`() {
        assertEquals(
            "obsidian://open?vault=My%20Vault&file=Notes/My%20Note.md",
            normalizeLongPressUri("obsidian://open?vault=My Vault&file=Notes/My Note.md")
        )
    }

    @Test
    fun `existing percent escapes are not double encoded`() {
        assertEquals(
            "obsidian://open?vault=My%20Vault",
            normalizeLongPressUri("obsidian://open?vault=My%20Vault")
        )
        // Lowercase hex escapes are preserved too
        assertEquals(
            "https://example.com/a%2fb",
            normalizeLongPressUri("https://example.com/a%2fb")
        )
    }

    @Test
    fun `stray percent sign is encoded`() {
        assertEquals(
            "https://example.com/100%25",
            normalizeLongPressUri("https://example.com/100%")
        )
    }

    @Test
    fun `uri structure characters are preserved`() {
        val uri = "https://user:pw@example.com:8080/p/a?t=1&x=y,z#frag"
        assertEquals(uri, normalizeLongPressUri(uri))
    }

    @Test
    fun `non ascii characters are percent encoded`() {
        assertEquals(
            "obsidian://open?vault=Caff%C3%A8",
            normalizeLongPressUri("obsidian://open?vault=Caffè")
        )
    }

    @Test
    fun `normalization is idempotent`() {
        val pasted = "  obsidian://open?vault=My Vault&file=Notes/My Note.md  "
        val once = normalizeLongPressUri(pasted)
        assertEquals(once, normalizeLongPressUri(once))
    }

    @Test
    fun `blank input is returned unchanged`() {
        assertEquals("", normalizeLongPressUri(""))
        assertEquals("", normalizeLongPressUri("   "))
    }

    // ── Component encoding + Obsidian builder ───────────────────────────────

    @Test
    fun `component encoder uses percent 20 not plus for spaces`() {
        assertEquals("My%20Vault", encodeUriComponent("My Vault"))
        assertEquals("a%26b", encodeUriComponent("a&b"))
    }

    @Test
    fun `obsidian builder encodes vault and file`() {
        assertEquals(
            "obsidian://open?vault=My%20Vault&file=Notes%2FMy%20Note.md",
            buildObsidianOpenUri("My Vault", "Notes/My Note.md")
        )
    }

    @Test
    fun `obsidian builder omits file param when file blank`() {
        assertEquals(
            "obsidian://open?vault=My%20Vault",
            buildObsidianOpenUri("My Vault", "  ")
        )
    }

    @Test
    fun `obsidian builder output survives normalization`() {
        // The builder output is stored as-is; launching normalizes again.
        // The two must compose without double-encoding.
        val built = buildObsidianOpenUri("My Vault", "Notes/My Note.md")
        assertEquals(built, normalizeLongPressUri(built))
    }
}
