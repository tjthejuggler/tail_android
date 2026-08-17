package com.example.tail

import com.example.tail.data.ACTIVITY_ID_PREFIX
import com.example.tail.data.encodeShortcutEntry
import com.example.tail.data.isActivityEntry
import com.example.tail.data.isShortcutEntry
import com.example.tail.data.packageNameOfEntry
import com.example.tail.data.parseShortcutEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the habit-app-association shortcut entry codec
 * (see AppShortcutRepository.kt).
 *
 * Association entries are plain package names or encoded shortcut
 * references "pkg|escapedId". The encoding must round-trip and must
 * survive the surrounding SettingsRepository value escaping, which
 * escapes commas as "\," inside list values.
 */
class AppShortcutEntryCodecTest {

    @Test
    fun `plain package names are not shortcut entries`() {
        val pkg = "com.example.someapp"
        assertNull(parseShortcutEntry(pkg))
        assertFalse(isShortcutEntry(pkg))
        assertEquals(pkg, packageNameOfEntry(pkg))
    }

    @Test
    fun `simple shortcut entry round trips`() {
        val entry = encodeShortcutEntry("com.spotify.music", "playlist-daily-mix")
        assertEquals("com.spotify.music" to "playlist-daily-mix", parseShortcutEntry(entry))
        assertTrue(isShortcutEntry(entry))
        assertEquals("com.spotify.music", packageNameOfEntry(entry))
    }

    @Test
    fun `shortcut ids with separator characters round trip`() {
        val trickyId = "id,with|commas\\and|pipes"
        val entry = encodeShortcutEntry("com.example.app", trickyId)
        assertEquals("com.example.app" to trickyId, parseShortcutEntry(entry))
    }

    @Test
    fun `entry survives the settings persistence escaping`() {
        // Mirror of SettingsRepository.encodeSubtypesMap / decodeSubtypesMap
        // value handling — proves the two escaping layers compose.
        val id = "weird,id"
        val entry = encodeShortcutEntry("com.example.app", id)

        val persisted = entry.replace(",", "\\,")           // outer encode
        val split = persisted.split(Regex("(?<!\\\\),"))    // outer decode split
        val restored = split.single().replace("\\,", ",")   // outer decode unescape

        assertEquals(entry, restored)
        assertEquals("com.example.app" to id, parseShortcutEntry(restored))
    }

    @Test
    fun `multiple entries with commas in ids stay separate list items`() {
        val e1 = encodeShortcutEntry("com.a", "id,1")
        val e2 = encodeShortcutEntry("com.b", "id2")
        val list = listOf(e1, e2)

        // Outer persistence joins values with "," after escaping them
        val persisted = list.joinToString(",") { it.replace(",", "\\,") }
        val decoded = persisted.split(Regex("(?<!\\\\),")).map { it.replace("\\,", ",") }

        assertEquals(list, decoded)
        assertEquals("com.a" to "id,1", parseShortcutEntry(decoded[0]))
        assertEquals("com.b" to "id2", parseShortcutEntry(decoded[1]))
    }

    @Test
    fun `malformed entries are treated as plain packages`() {
        // separator at the start / end, or double separator with empty id
        assertNull(parseShortcutEntry("|orphan"))
        assertNull(parseShortcutEntry("com.example.app|"))
    }

    @Test
    fun `exported activity entries round trip and are recognised`() {
        val entry = encodeShortcutEntry(
            "com.example.tail",
            ACTIVITY_ID_PREFIX + "com.example.tail.MediaCaptureActivity"
        )
        assertTrue(isShortcutEntry(entry))
        assertTrue(isActivityEntry(entry))
        assertEquals(
            "com.example.tail" to (ACTIVITY_ID_PREFIX + "com.example.tail.MediaCaptureActivity"),
            parseShortcutEntry(entry)
        )
        assertEquals("com.example.tail", packageNameOfEntry(entry))
    }

    @Test
    fun `published shortcut entries are not activity entries`() {
        val entry = encodeShortcutEntry("com.example.app", "media_capture")
        assertFalse(isActivityEntry(entry))
    }

    @Test
    fun `plain packages are not activity entries`() {
        assertFalse(isActivityEntry("com.example.app"))
    }
}
