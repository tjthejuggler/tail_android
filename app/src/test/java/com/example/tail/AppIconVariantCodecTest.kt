package com.example.tail

import com.example.tail.data.appIconMonochromeOf
import com.example.tail.data.appIconNameOf
import com.example.tail.data.appPackageNameOf
import com.example.tail.data.isAppIconName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the installed-app icon name codec: full-colour names
 * ("app:<pkg>") and their black/white notification-style variant
 * ("app:<pkg>#mono") must round-trip, stay distinguishable, and keep the
 * package name extractable for app auto-association regardless of variant.
 */
class AppIconVariantCodecTest {

    // ── Building names ─────────────────────────────────────────────────────

    @Test
    fun `full-colour app icon name is built without suffix`() {
        assertEquals("app:com.spotify.music", appIconNameOf("com.spotify.music"))
    }

    @Test
    fun `monochrome app icon name is built with mono suffix`() {
        assertEquals(
            "app:com.spotify.music#mono",
            appIconNameOf("com.spotify.music", monochrome = true)
        )
    }

    // ── Parsing names ──────────────────────────────────────────────────────

    @Test
    fun `full-colour app icon name round-trips`() {
        val name = appIconNameOf("com.spotify.music")
        assertEquals("com.spotify.music", appPackageNameOf(name))
        assertFalse(appIconMonochromeOf(name))
        assertTrue(isAppIconName(name))
    }

    @Test
    fun `monochrome app icon name round-trips`() {
        val name = appIconNameOf("com.spotify.music", monochrome = true)
        // The package must still resolve (app auto-association depends on it)…
        assertEquals("com.spotify.music", appPackageNameOf(name))
        // …while the variant stays distinguishable.
        assertTrue(appIconMonochromeOf(name))
        assertTrue(isAppIconName(name))
    }

    @Test
    fun `legacy stored names parse as full-colour icons`() {
        assertEquals("com.example.app", appPackageNameOf("app:com.example.app"))
        assertFalse(appIconMonochromeOf("app:com.example.app"))
    }

    @Test
    fun `variants of the same app produce different names`() {
        val full = appIconNameOf("com.example.app")
        val mono = appIconNameOf("com.example.app", monochrome = true)
        assertTrue(full != mono)
        // Both map back to the same package.
        assertEquals(appPackageNameOf(full), appPackageNameOf(mono))
    }

    // ── Rejecting non-app names ────────────────────────────────────────────

    @Test
    fun `non-app icon names are rejected`() {
        assertNull(appPackageNameOf("bicycle"))
        assertNull(appPackageNameOf("text:🧘"))
        assertNull(appPackageNameOf(null))
        assertFalse(isAppIconName("bicycle"))
        assertFalse(appIconMonochromeOf("bicycle"))
        assertFalse(appIconMonochromeOf("text:🧘"))
        assertFalse(appIconMonochromeOf(null))
    }
}
