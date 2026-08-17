package com.example.tail

import com.example.tail.ui.HABIT_ICON
import com.example.tail.ui.ICON_NAME_TO_RES
import com.example.tail.ui.getHabitIconName
import com.example.tail.ui.getHabitIconRes
import com.example.tail.ui.renamedHabitIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for rename-safe habit icons: [renamedHabitIcons] must guarantee
 * that a renamed habit keeps EXACTLY the icon it had before the rename —
 * whether that icon came from a custom override or from the hardcoded
 * HABIT_ICON defaults (which are keyed by the original habit name).
 */
class HabitIconRenameTest {

    // ── getHabitIconName: resolution to icon NAME ───────────────────────────

    @Test
    fun `custom override wins over hardcoded default`() {
        assertEquals("bicycle", getHabitIconName("Chess", mapOf("Chess" to "bicycle")))
    }

    @Test
    fun `default icon is reverse-resolved to a catalogued drawable name`() {
        val name = getHabitIconName("Chess")
        assertTrue("Chess default should resolve to a name", name != null)
        // The resolved name must point back at the same drawable the default uses.
        assertEquals(HABIT_ICON.getValue("Chess"), ICON_NAME_TO_RES.getValue(name!!))
    }

    @Test
    fun `unknown habit has no icon name`() {
        assertNull(getHabitIconName("Totally Unknown Habit"))
    }

    // ── renamedHabitIcons: the rename contract ──────────────────────────────

    @Test
    fun `existing override is re-keyed to the new name`() {
        val renamed = renamedHabitIcons("Chess", "Chess daily", mapOf("Chess" to "moon"))
        assertEquals("moon", renamed["Chess daily"])
        assertNull(renamed["Chess"])
    }

    @Test
    fun `default icon is materialised under the new name on rename`() {
        // "Chess" has no override — its icon comes from the hardcoded defaults.
        val renamed = renamedHabitIcons("Chess", "Chess daily", emptyMap())
        val iconName = renamed["Chess daily"]
        assertTrue("renamed habit should carry an explicit icon", iconName != null)
        // And it must resolve to the SAME drawable the old name resolved to.
        assertEquals(
            getHabitIconRes("Chess"),
            getHabitIconRes("Chess daily", renamed)
        )
    }

    @Test
    fun `other habits overrides are untouched by a rename`() {
        val icons = mapOf("Pushups" to "moon", "Squats" to "star_trek_sc43")
        val renamed = renamedHabitIcons("Chess", "Chess daily", icons)
        assertEquals("moon", renamed["Pushups"])
        assertEquals("star_trek_sc43", renamed["Squats"])
    }

    @Test
    fun `habit without any icon stays icon-less after rename`() {
        val renamed = renamedHabitIcons("Totally Unknown Habit", "New Name", emptyMap())
        assertTrue(renamed.isEmpty())
    }

    @Test
    fun `habit icon wins over orphaned override under the target name`() {
        // Degenerate case: the DB-level rename refuses real habit-name collisions,
        // so an override already sitting under the target name can only be an
        // orphaned leftover — the renamed habit's own icon must win.
        val icons = mapOf("Chess" to "moon", "Target" to "bicycle")
        val renamed = renamedHabitIcons("Chess", "Target", icons)
        assertEquals("moon", renamed["Target"])
        assertNull(renamed["Chess"])
    }

    @Test
    fun `icon-less habit drops orphaned override under the target name`() {
        // The renamed habit had no icon; a stale orphan under the new name must
        // not suddenly give it one.
        val icons = mapOf("Target" to "bicycle")
        val renamed = renamedHabitIcons("Totally Unknown Habit", "Target", icons)
        assertNull(renamed["Target"])
        assertTrue(renamed.isEmpty())
    }
}
