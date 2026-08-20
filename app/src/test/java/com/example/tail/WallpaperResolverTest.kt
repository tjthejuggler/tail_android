package com.example.tail

import com.example.tail.data.TaskerStats
import com.example.tail.wallpaper.WallpaperMetric
import com.example.tail.wallpaper.WallpaperTarget
import com.example.tail.wallpaper.dominantImagePrefix
import com.example.tail.wallpaper.parseIndexedImageName
import com.example.tail.wallpaper.resolveImageIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the points-driven wallpaper resolution logic:
 * file-name parsing, prefix discovery, value→index mapping and the
 * metric/target enums.
 */
class WallpaperResolverTest {

    // ── parseIndexedImageName ────────────────────────────────────────────

    @Test
    fun `parses result_51 png into prefix and index`() {
        assertEquals("result" to 51, parseIndexedImageName("result_51.png"))
    }

    @Test
    fun `parses other prefixes and extensions`() {
        assertEquals("img" to 7, parseIndexedImageName("img_7.jpg"))
        assertEquals("tier" to 3, parseIndexedImageName("tier_3.webp"))
        assertEquals("photo" to 12, parseIndexedImageName("photo_12.JPEG"))
        assertEquals("x" to 1, parseIndexedImageName("x_1.PNG"))
    }

    @Test
    fun `rejects non indexed names`() {
        assertNull(parseIndexedImageName("wallpaper.png"))
        assertNull(parseIndexedImageName("result.png"))
        assertNull(parseIndexedImageName("result_51.txt"))
        assertNull(parseIndexedImageName("result_.png"))
        assertNull(parseIndexedImageName("result_fifty.png"))
        assertNull(parseIndexedImageName(""))
    }

    // ── dominantImagePrefix ──────────────────────────────────────────────

    @Test
    fun `picks the prefix with the most indexed files`() {
        val files = listOf(
            "result_1.png", "result_2.png", "result_3.png",
            "img_1.png", "img_2.png",
            "notes.txt", "readme.md"
        )
        assertEquals("result", dominantImagePrefix(files))
    }

    @Test
    fun `returns null when no indexed images exist`() {
        assertNull(dominantImagePrefix(listOf("a.png", "b.txt")))
        assertNull(dominantImagePrefix(emptyList()))
    }

    // ── resolveImageIndex ────────────────────────────────────────────────

    @Test
    fun `maps whole point values directly`() {
        assertEquals(51, resolveImageIndex(51.0, 56))
        assertEquals(1, resolveImageIndex(1.0, 56))
        assertEquals(56, resolveImageIndex(56.0, 56))
    }

    @Test
    fun `rounds fractional averages to nearest`() {
        assertEquals(41, resolveImageIndex(40.5, 56))
        assertEquals(40, resolveImageIndex(40.4, 56))
        assertEquals(3, resolveImageIndex(2.5, 56))
    }

    @Test
    fun `clamps out of range values`() {
        assertEquals(56, resolveImageIndex(99.0, 56))
        assertEquals(1, resolveImageIndex(0.0, 56))
        assertEquals(1, resolveImageIndex(-5.0, 56))
    }

    @Test
    fun `returns minus one when no images exist`() {
        assertEquals(-1, resolveImageIndex(10.0, 0))
    }

    // ── WallpaperMetric ──────────────────────────────────────────────────

    private val stats = TaskerStats(today = 51, avg7 = 40.5, avg30 = 33.2)

    @Test
    fun `metrics select the right stat value`() {
        assertEquals(51.0, WallpaperMetric.TODAY.select(stats), 0.001)
        assertEquals(40.5, WallpaperMetric.WEEKLY.select(stats), 0.001)
        assertEquals(33.2, WallpaperMetric.MONTHLY.select(stats), 0.001)
    }

    @Test
    fun `metric decoding falls back to TODAY`() {
        assertEquals(WallpaperMetric.TODAY, WallpaperMetric.fromName("TODAY"))
        assertEquals(WallpaperMetric.WEEKLY, WallpaperMetric.fromName("WEEKLY"))
        assertEquals(WallpaperMetric.MONTHLY, WallpaperMetric.fromName("MONTHLY"))
        assertEquals(WallpaperMetric.TODAY, WallpaperMetric.fromName("garbage"))
        assertEquals(WallpaperMetric.TODAY, WallpaperMetric.fromName(null))
    }

    // ── WallpaperTarget ──────────────────────────────────────────────────

    @Test
    fun `target decoding falls back to SYSTEM`() {
        assertEquals(WallpaperTarget.SYSTEM, WallpaperTarget.fromName("SYSTEM"))
        assertEquals(WallpaperTarget.LOCK, WallpaperTarget.fromName("LOCK"))
        assertEquals(WallpaperTarget.BOTH, WallpaperTarget.fromName("BOTH"))
        assertEquals(WallpaperTarget.SYSTEM, WallpaperTarget.fromName("garbage"))
        assertEquals(WallpaperTarget.SYSTEM, WallpaperTarget.fromName(null))
    }
}
