package com.example.tail

import com.example.tail.data.AppSettings
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.applyDivider
import com.example.tail.data.dateString
import com.example.tail.data.effectivePointsWithFallback
import com.example.tail.data.fallbackSlotKey
import com.example.tail.data.invertedBinaryPoints
import com.example.tail.data.minutesKey
import com.example.tail.data.monthlyAveragesBulk
import com.example.tail.data.secondaryValueKey
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * Parity tests for monthlyAveragesBulk() — the sliding-window bulk 30-day
 * average used by the world-map dot colours. The reference implementation
 * below mirrors HabitViewModel.getDayStatsLight()'s monthly-average math
 * (30-day window × tracked habits, effective points, /30.0, rounded), so
 * these tests pin the fast path to the exact behaviour of the slow path it
 * replaced.
 */
class MonthlyAveragesBulkTest {

    // ── Reference (naive) implementation — getDayStatsLight's math ──────────

    private fun effectivePoints(
        db: HabitsDatabase,
        settings: AppSettings,
        name: String,
        dateStr: String
    ): Int {
        val raw = db[name]?.get(dateStr) ?: 0
        val divider = settings.habitDividers[name] ?: 1
        return when {
            name in settings.invertedBinaryHabits -> invertedBinaryPoints(raw)
            // Minutes-primary: minutes live in the first-class `minutes:` slot;
            // sessions (the habit's own slot) are the fallback.
            name in settings.widgetTimerMinutesPrimary -> effectivePointsWithFallback(
                db[minutesKey(name)]?.get(dateStr) ?: 0, divider, raw, true
            )
            name !in settings.secondaryValueFallbackHabits -> applyDivider(raw, divider)
            // Sessions-primary fallback: the legacy generic secondary slot
            // when the habit uses it or has data there, else the minutes slot.
            else -> effectivePointsWithFallback(
                raw, divider,
                db[fallbackSlotKey(name, settings.secondaryValueHabits, db)]?.get(dateStr) ?: 0,
                true
            )
        }
    }

    private fun naiveAverage(
        db: HabitsDatabase,
        tracked: Set<String>,
        settings: AppSettings,
        date: LocalDate
    ): Int {
        var monthlySum = 0
        for (i in 0 until 30) {
            val ds = dateString(date.minusDays(i.toLong()))
            for (name in tracked) {
                val pts = effectivePoints(db, settings, name, ds)
                if (pts > 0) monthlySum += pts
            }
        }
        return kotlin.math.round(monthlySum / 30.0).toInt()
    }

    private fun assertParity(
        db: HabitsDatabase,
        tracked: Set<String>,
        settings: AppSettings,
        dates: List<LocalDate>
    ) {
        val bulk = monthlyAveragesBulk(db, tracked, settings, dates)
        assertEquals(dates.size, bulk.size)
        for (d in dates) {
            assertEquals("parity failed for $d", naiveAverage(db, tracked, settings, d), bulk[d])
        }
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `empty dates return empty map`() {
        val bulk = monthlyAveragesBulk(emptyMap(), emptySet(), AppSettings(), emptyList())
        assertEquals(0, bulk.size)
    }

    @Test
    fun `single date with no data averages zero`() {
        val d = LocalDate.of(2026, 8, 17)
        val bulk = monthlyAveragesBulk(emptyMap(), setOf("Pushups"), AppSettings(), listOf(d))
        assertEquals(0, bulk[d])
    }

    @Test
    fun `bulk matches naive for plain habits with dividers`() {
        val base = LocalDate.of(2026, 1, 1)
        val pushups = (0 until 60).associate { dateString(base.plusDays(it.toLong())) to (it % 5) }
        val meditations = (0 until 60).associate { dateString(base.plusDays(it.toLong())) to 2 }
        val db: HabitsDatabase = mapOf("Pushups" to pushups, "Meditations" to meditations)
        val settings = AppSettings(habitDividers = mapOf("Pushups" to 2))
        val tracked = setOf("Pushups", "Meditations")
        val dates = (0 until 60).map { base.plusDays(it.toLong()) }
        assertParity(db, tracked, settings, dates)
    }

    @Test
    fun `bulk matches naive when habit data is sparse`() {
        val base = LocalDate.of(2025, 6, 1)
        // Entries only every ~5 days — most of the 30-day windows see zeros.
        val sparse = (0 until 60).filter { it % 5 == 0 }
            .associate { dateString(base.plusDays(it.toLong())) to 3 }
        val db: HabitsDatabase = mapOf("Pullups" to sparse)
        val dates = (0 until 60).map { base.plusDays(it.toLong()) }
        assertParity(db, setOf("Pullups"), AppSettings(), dates)
    }

    @Test
    fun `bulk matches naive for inverted binary habits`() {
        // Inverted-binary: not-done days score 1 point — including days with
        // NO entry at all. This is the case that breaks "skip zero entries"
        // optimisations; every day in the window must be evaluated.
        val base = LocalDate.of(2026, 3, 1)
        val done = (0 until 60).filter { it % 7 == 0 }
            .associate { dateString(base.plusDays(it.toLong())) to 1 }
        val db: HabitsDatabase = mapOf("No Alcohol" to done)
        val settings = AppSettings(invertedBinaryHabits = setOf("No Alcohol"))
        val dates = (0 until 60).map { base.plusDays(it.toLong()) }
        assertParity(db, setOf("No Alcohol"), settings, dates)
    }

    @Test
    fun `bulk matches naive for widget timer minutes-primary habits`() {
        // Minutes (first-class `minutes:` slot) drive points; sessions are the fallback.
        val base = LocalDate.of(2026, 2, 1)
        val sessions = (0 until 45).associate { dateString(base.plusDays(it.toLong())) to if (it % 3 == 0) 1 else 0 }
        val minutes = (0 until 45).associate { dateString(base.plusDays(it.toLong())) to if (it % 4 == 0) 25 else 0 }
        val db: HabitsDatabase = mapOf(
            "Meditations" to sessions,
            minutesKey("Meditations") to minutes
        )
        val settings = AppSettings(widgetTimerMinutesPrimary = setOf("Meditations"))
        val dates = (0 until 45).map { base.plusDays(it.toLong()) }
        assertParity(db, setOf("Meditations"), settings, dates)
    }

    @Test
    fun `bulk matches naive for legacy secondary value fallback habits`() {
        // Legacy habits (Wags-fed): sessions/counts live in the generic
        // secondary_value: slot and are the fallback for the primary value.
        val base = LocalDate.of(2026, 4, 1)
        val primary = (0 until 50).associate { dateString(base.plusDays(it.toLong())) to if (it % 6 == 0) 1 else 0 }
        val secondary = (0 until 50).associate { dateString(base.plusDays(it.toLong())) to if (it % 2 == 0) 40 else 0 }
        val db: HabitsDatabase = mapOf(
            "Apnea" to primary,
            secondaryValueKey("Apnea") to secondary
        )
        val settings = AppSettings(
            secondaryValueHabits = setOf("Apnea"),
            secondaryValueFallbackHabits = setOf("Apnea")
        )
        val dates = (0 until 50).map { base.plusDays(it.toLong()) }
        assertParity(db, setOf("Apnea"), settings, dates)
    }

    @Test
    fun `bulk matches naive for minutes-fallback habits`() {
        // Sessions-primary habit (NOT in secondaryValueHabits) with minutes
        // fallback: the fallback value comes from the `minutes:` slot.
        val base = LocalDate.of(2026, 6, 1)
        val sessions = (0 until 50).associate { dateString(base.plusDays(it.toLong())) to if (it % 6 == 0) 1 else 0 }
        val minutes = (0 until 50).associate { dateString(base.plusDays(it.toLong())) to if (it % 2 == 0) 30 else 0 }
        val db: HabitsDatabase = mapOf(
            "Good Posture" to sessions,
            minutesKey("Good Posture") to minutes
        )
        val settings = AppSettings(secondaryValueFallbackHabits = setOf("Good Posture"))
        val dates = (0 until 50).map { base.plusDays(it.toLong()) }
        assertParity(db, setOf("Good Posture"), settings, dates)
    }

    @Test
    fun `bulk matches naive for chess-style legacy slot data without membership`() {
        // chess.com habits keep games in secondary_value: WITHOUT being
        // secondaryValueHabits members; the fallback must still read that
        // legacy data (data-driven slot resolution).
        val base = LocalDate.of(2026, 7, 1)
        val minutes = (0 until 50).associate { dateString(base.plusDays(it.toLong())) to if (it % 3 == 0) 25 else 0 }
        val games = (0 until 50).associate { dateString(base.plusDays(it.toLong())) to if (it % 3 == 0) (it % 3) + 1 else 0 }
        val db: HabitsDatabase = mapOf(
            "Blitz" to minutes,
            secondaryValueKey("Blitz") to games
        )
        val settings = AppSettings(
            habitDividers = mapOf("Blitz" to 10),
            secondaryValueFallbackHabits = setOf("Blitz")
        )
        val dates = (0 until 50).map { base.plusDays(it.toLong()) }
        assertParity(db, setOf("Blitz"), settings, dates)
    }

    @Test
    fun `bulk matches naive for gappy requested dates`() {
        // The map only asks for dates that have coordinates — arbitrary,
        // non-contiguous subsets must still see full 30-day windows.
        val base = LocalDate.of(2025, 11, 1)
        val pushups = (0 until 90).associate { dateString(base.plusDays(it.toLong())) to (it % 4) }
        val db: HabitsDatabase = mapOf("Pushups" to pushups)
        val dates = (0 until 90).filter { it % 13 == 0 }.map { base.plusDays(it.toLong()) }
        assertParity(db, setOf("Pushups"), AppSettings(), dates)
    }

    @Test
    fun `bulk matches naive for mixed habit kinds together`() {
        val base = LocalDate.of(2026, 5, 1)
        val plain = (0 until 70).associate { dateString(base.plusDays(it.toLong())) to (it % 3) }
        val invertedDone = (0 until 70).filter { it % 9 == 0 }
            .associate { dateString(base.plusDays(it.toLong())) to 1 }
        val sessions = (0 until 70).associate { dateString(base.plusDays(it.toLong())) to if (it % 5 == 0) 2 else 0 }
        val minutes = (0 until 70).associate { dateString(base.plusDays(it.toLong())) to if (it % 2 == 0) 15 else 0 }
        val db: HabitsDatabase = mapOf(
            "Pushups" to plain,
            "No Alcohol" to invertedDone,
            "Meditations" to sessions,
            minutesKey("Meditations") to minutes
        )
        val settings = AppSettings(
            habitDividers = mapOf("Pushups" to 3),
            invertedBinaryHabits = setOf("No Alcohol"),
            widgetTimerMinutesPrimary = setOf("Meditations")
        )
        val tracked = setOf("Pushups", "No Alcohol", "Meditations")
        val dates = (0 until 70).map { base.plusDays(it.toLong()) }
        assertParity(db, tracked, settings, dates)
    }
}
