package com.example.tail

import com.example.tail.ui.MERGE_GAP_MINUTES
import com.example.tail.ui.MIN_SPAN_MINUTES
import com.example.tail.ui.ScheduleEvent
import com.example.tail.ui.buildScheduleBlocks
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the schedule timeline's block merging:
 *  - an isolated timestamp becomes one minimum-height block;
 *  - same-habit timestamps within the merge gap collapse into a single
 *    block spanning first→last (plus the minimum span) with a summed ×count;
 *  - chains keep merging while each consecutive gap stays within the gap;
 *  - genuinely separate sessions and different habits stay separate;
 *  - the result is sorted by start time.
 */
class ScheduleBlockMergeTest {

    private fun event(
        habit: String,
        hour: Int,
        minute: Int,
        amount: Int = 1
    ) = ScheduleEvent(
        habitName = habit,
        time = "%02d:%02d:00".format(hour, minute),
        amount = amount,
        isMeal = false,
        canEditText = false
    )

    @Test
    fun `isolated timestamp becomes one minimum-span block`() {
        val blocks = buildScheduleBlocks(listOf(event("Meditation", 7, 15)))
        assertEquals(1, blocks.size)
        val b = blocks.single()
        assertEquals("Meditation", b.habitName)
        assertEquals(7 * 60 + 15, b.startMinute)
        assertEquals(7 * 60 + 15 + MIN_SPAN_MINUTES, b.endMinute)
        assertEquals(MIN_SPAN_MINUTES, b.spanMinutes)
        assertEquals(1, b.eventCount)
        assertEquals(1, b.amount)
    }

    @Test
    fun `same habit within merge gap collapses into one block`() {
        val blocks = buildScheduleBlocks(
            listOf(
                event("Chess", 14, 0),
                event("Chess", 14, 25) // 25 min later — within the 30 min gap
            )
        )
        assertEquals(1, blocks.size)
        val b = blocks.single()
        assertEquals(2, b.eventCount)
        assertEquals(2, b.amount)
        assertEquals(14 * 60, b.startMinute)
        // Spans from the first timestamp to the last, plus the minimum span.
        assertEquals(14 * 60 + 25 + MIN_SPAN_MINUTES, b.endMinute)
        assertEquals("14:00:00", b.firstTime)
        assertEquals("14:25:00", b.lastTime)
    }

    @Test
    fun `chain of close timestamps merges transitively`() {
        // Each consecutive gap is within the merge gap, so all four merge —
        // even though first→last is wider than the gap itself.
        val blocks = buildScheduleBlocks(
            listOf(
                event("Chess", 14, 0),
                event("Chess", 14, 20),
                event("Chess", 14, 40),
                event("Chess", 14, 55)
            )
        )
        assertEquals(1, blocks.size)
        val b = blocks.single()
        assertEquals(4, b.eventCount)
        assertEquals(14 * 60, b.startMinute)
        assertEquals(14 * 60 + 55 + MIN_SPAN_MINUTES, b.endMinute)
    }

    @Test
    fun `gap beyond merge threshold stays separate`() {
        val far = MERGE_GAP_MINUTES + 5
        val blocks = buildScheduleBlocks(
            listOf(
                event("Chess", 10, 0),
                event("Chess", 10, 0 + far)
            )
        )
        assertEquals(2, blocks.size)
        assertEquals(1, blocks[0].eventCount)
        assertEquals(1, blocks[1].eventCount)
    }

    @Test
    fun `different habits at the same time stay separate`() {
        val blocks = buildScheduleBlocks(
            listOf(
                event("Chess", 9, 30),
                event("Reading", 9, 30)
            )
        )
        assertEquals(2, blocks.size)
        assertEquals(setOf("Chess", "Reading"), blocks.map { it.habitName }.toSet())
    }

    @Test
    fun `multi-unit events sum their amounts when merged`() {
        val blocks = buildScheduleBlocks(
            listOf(
                event("Pushups", 8, 0, amount = 10),
                event("Pushups", 8, 10, amount = 15)
            )
        )
        assertEquals(1, blocks.size)
        assertEquals(25, blocks.single().amount)
        assertEquals(2, blocks.single().eventCount)
    }

    @Test
    fun `blocks are sorted by start minute`() {
        val blocks = buildScheduleBlocks(
            listOf(
                event("Reading", 20, 0),
                event("Chess", 9, 0),
                event("Meditation", 7, 30)
            )
        )
        assertEquals(listOf(7 * 60 + 30, 9 * 60, 20 * 60), blocks.map { it.startMinute })
    }

    @Test
    fun `merge gap boundary is inclusive`() {
        // Exactly MERGE_GAP_MINUTES apart still merges.
        val blocks = buildScheduleBlocks(
            listOf(
                event("Chess", 12, 0),
                event("Chess", 12, MERGE_GAP_MINUTES)
            )
        )
        assertEquals(1, blocks.size)
        assertEquals(2, blocks.single().eventCount)
    }
}
