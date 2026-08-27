package com.example.tail

import com.example.tail.ui.HabitViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the pure diff logic behind the dated-entry manual refresh preview
 * ([HabitViewModel.DatedEntryRefreshPreview.diff]).
 */
class DatedEntryRefreshPreviewTest {

    @Test
    fun `diff detects added removed and changed dates`() {
        val current = mapOf(
            "2025-03-10" to 2, // unchanged
            "2025-03-11" to 1, // changed
            "2025-03-12" to 3  // removed
        )
        val parsed = mapOf(
            "2025-03-10" to 2,
            "2025-03-11" to 4,
            "2025-03-14" to 1  // added
        )

        val p = HabitViewModel.DatedEntryRefreshPreview.diff("Dreams", current, parsed)

        assertEquals("Dreams", p.habitName)
        assertEquals(listOf("2025-03-14" to 1), p.addedDates)
        assertEquals(listOf("2025-03-12" to 3), p.removedDates)
        assertEquals(listOf(Triple("2025-03-11", 1, 4)), p.changedDates)
        assertEquals(6, p.currentTotal)
        assertEquals(7, p.newTotal)
        assertEquals(1, p.totalDelta)
        assertEquals(3, p.currentDayCount)
        assertEquals(3, p.newDayCount)
        assertTrue(p.hasChanges)
        assertEquals(parsed, p.newCounts)
    }

    @Test
    fun `identical maps produce no changes`() {
        val map = mapOf("2025-01-01" to 1, "2025-01-02" to 5)

        val p = HabitViewModel.DatedEntryRefreshPreview.diff("Chess", map, map)

        assertTrue(p.addedDates.isEmpty())
        assertTrue(p.removedDates.isEmpty())
        assertTrue(p.changedDates.isEmpty())
        assertFalse(p.hasChanges)
        assertEquals(6, p.currentTotal)
        assertEquals(6, p.newTotal)
        assertEquals(0, p.totalDelta)
    }

    @Test
    fun `empty file removes everything`() {
        val current = mapOf("2025-03-10" to 2, "2025-03-11" to 1)

        val p = HabitViewModel.DatedEntryRefreshPreview.diff("Dreams", current, emptyMap())

        assertEquals(
            listOf("2025-03-10" to 2, "2025-03-11" to 1),
            p.removedDates
        )
        assertEquals(0, p.newTotal)
        assertEquals(0, p.newDayCount)
        assertEquals(-3, p.totalDelta)
        assertTrue(p.hasChanges)
        assertTrue(p.newCounts.isEmpty())
    }

    @Test
    fun `empty current db counts every parsed date as added`() {
        val parsed = mapOf("2025-03-10" to 2, "2025-03-11" to 1)

        val p = HabitViewModel.DatedEntryRefreshPreview.diff("Dreams", emptyMap(), parsed)

        assertEquals(
            listOf("2025-03-10" to 2, "2025-03-11" to 1),
            p.addedDates
        )
        assertEquals(0, p.currentTotal)
        assertEquals(3, p.newTotal)
        assertEquals(3, p.totalDelta)
        assertTrue(p.hasChanges)
    }

    @Test
    fun `changed dates are sorted chronologically`() {
        val current = mapOf("2025-05-02" to 1, "2025-05-01" to 1, "2025-05-03" to 1)
        val parsed = mapOf("2025-05-02" to 9, "2025-05-01" to 8, "2025-05-03" to 7)

        val p = HabitViewModel.DatedEntryRefreshPreview.diff("Dreams", current, parsed)

        assertEquals(
            listOf(
                Triple("2025-05-01", 1, 8),
                Triple("2025-05-02", 1, 9),
                Triple("2025-05-03", 1, 7)
            ),
            p.changedDates
        )
    }
}
