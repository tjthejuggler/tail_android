package com.example.tail

import com.example.tail.widget.WidgetTimerStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-logic tests for the multi-timer group helpers in
 * [WidgetTimerStore] (rounding rules shared with the single timer path).
 */
class MultiTimerStoreTest {

    @Test
    fun `rounds sub-half-minute durations down to zero`() {
        assertEquals(0, WidgetTimerStore.roundMillisToMinutes(0L))
        assertEquals(0, WidgetTimerStore.roundMillisToMinutes(29_999L))
    }

    @Test
    fun `rounds half-minute and above up to one`() {
        assertEquals(1, WidgetTimerStore.roundMillisToMinutes(30_000L))
        assertEquals(1, WidgetTimerStore.roundMillisToMinutes(89_999L))
    }

    @Test
    fun `rounds longer durations to nearest minute`() {
        assertEquals(5, WidgetTimerStore.roundMillisToMinutes(5 * 60_000L))
        assertEquals(5, WidgetTimerStore.roundMillisToMinutes(5 * 60_000L + 29_999L))
        assertEquals(6, WidgetTimerStore.roundMillisToMinutes(5 * 60_000L + 30_000L))
    }

    @Test
    fun `never rounds negative input below zero`() {
        assertEquals(0, WidgetTimerStore.roundMillisToMinutes(-42_000L))
    }

    @Test
    fun `formats banked multi-timer totals like single timers`() {
        // 1h 2m 3s total (bank + running) renders h:mm:ss
        assertEquals("1:02:03", WidgetTimerStore.formatElapsed((3_723 * 1_000L)))
        // 2m 5s renders m:ss
        assertEquals("2:05", WidgetTimerStore.formatElapsed(125_000L))
    }
}
