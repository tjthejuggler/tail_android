package com.example.tail

import com.example.tail.data.brokenMinutesMigrationHabits
import com.example.tail.data.minutesKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the one-time broken minutes-migration repair
 * ([com.example.tail.data.brokenMinutesMigrationHabits]).
 *
 * Context: the graph long-press "Minutes value" action used to MOVE the
 * primary-key history into the first-class `minutes:` slot and DELETE the
 * primary key. Without the primary key the graph renders nothing at all for
 * any metric — most visibly for Garmin-linked habits (e.g. Sleep Length),
 * whose raw values live in the Garmin cache while the JSON key only held
 * the derived per-day points.
 */
class BrokenMinutesMigrationRepairTest {

    private fun repair(
        minutesPrimary: Set<String>,
        db: Map<String, Map<String, Int>> = emptyMap(),
        garminLinks: Map<String, String> = emptyMap(),
        pcWidget: Set<String> = emptySet(),
        widgetTrigger: Set<String> = emptySet(),
        media: Set<String> = emptySet(),
        bridgeMovie: Set<String> = emptySet(),
        chessLinked: Set<String> = emptySet()
    ): Set<String> = brokenMinutesMigrationHabits(
        widgetTimerMinutesPrimary = minutesPrimary,
        garminHabitLinks = garminLinks,
        pcWidgetHabits = pcWidget,
        widgetTriggerHabits = widgetTrigger,
        mediaHabits = media,
        bridgeMovieHabits = bridgeMovie,
        chessLinked = chessLinked,
        db = db
    )

    // ── Target identification ────────────────────────────────────────────────

    @Test
    fun `garmin-linked habit with deleted primary key is repaired`() {
        // The exact incident footprint: the migration moved the primary-key
        // data into `minutes:` and removed the primary key.
        val db = mapOf(
            minutesKey("Sleep Length") to mapOf("2026-08-20" to 1, "2026-08-21" to 1)
        )
        assertEquals(
            setOf("Sleep Length"),
            repair(
                setOf("Sleep Length"),
                db,
                garminLinks = mapOf("Sleep Length" to "SLEEP_DURATION_MINUTES")
            )
        )
    }

    @Test
    fun `garmin-linked habit is repaired even after garmin sync recreated the key`() {
        // applyGarminData may have re-created the primary key with fresh
        // per-day points since the breakage — the habit is still wrongly
        // minutes-primary and the stale minutes slot still holds the moved
        // history, so it must still be targeted.
        val db = mapOf(
            "Sleep Length" to mapOf("2026-08-22" to 1),
            minutesKey("Sleep Length") to mapOf("2026-08-20" to 1)
        )
        assertEquals(
            setOf("Sleep Length"),
            repair(
                setOf("Sleep Length"),
                db,
                garminLinks = mapOf("Sleep Length" to "SLEEP_DURATION_MINUTES")
            )
        )
    }

    @Test
    fun `non-garmin habit with missing primary and moved minutes is repaired`() {
        val db = mapOf(
            minutesKey("Old Timer") to mapOf("2026-08-20" to 30, "2026-08-21" to 45)
        )
        assertEquals(
            setOf("Old Timer"),
            repair(setOf("Old Timer"), db)
        )
    }

    @Test
    fun `habit with intact primary key is not repaired`() {
        // Post-fix migrations COPY instead of moving — the primary key
        // survives, so the state is healthy and must be left alone.
        val db = mapOf(
            "Old Timer" to mapOf("2026-08-20" to 30),
            minutesKey("Old Timer") to mapOf("2026-08-20" to 30)
        )
        assertEquals(
            emptySet<String>(),
            repair(setOf("Old Timer"), db)
        )
    }

    @Test
    fun `widget-trigger habit is never repaired`() {
        // Timer-fed habits legitimately run minutes-primary (sessions in
        // the primary key, timer minutes in the minutes slot).
        val db = mapOf(
            "Language studied" to mapOf("2026-08-19" to 2),
            minutesKey("Language studied") to mapOf("2026-08-19" to 30)
        )
        assertEquals(
            emptySet<String>(),
            repair(
                setOf("Language studied"),
                db,
                widgetTrigger = setOf("Language studied")
            )
        )
    }

    @Test
    fun `media habit is never repaired`() {
        val db = mapOf(
            minutesKey("Podcasts") to mapOf("2026-08-19" to 45)
        )
        assertEquals(
            emptySet<String>(),
            repair(
                setOf("Podcasts"),
                db,
                media = setOf("Podcasts")
            )
        )
    }

    @Test
    fun `habit outside minutes-primary set is never repaired`() {
        // Without the minutes-primary flag the missing primary key is not
        // the migration's footprint — nothing to repair.
        val db = mapOf(
            minutesKey("Sleep Length") to mapOf("2026-08-20" to 1)
        )
        assertEquals(
            emptySet<String>(),
            repair(
                emptySet(),
                db,
                garminLinks = mapOf("Sleep Length" to "SLEEP_DURATION_MINUTES")
            )
        )
    }

    @Test
    fun `non-garmin habit with empty minutes slot is not repaired`() {
        // A minutes-primary habit whose minutes slot has no data and whose
        // primary key exists (all zeros) does not match the moved-data
        // footprint.
        val db = mapOf(
            "Plain Habit" to mapOf("2026-08-20" to 0)
        )
        assertEquals(
            emptySet<String>(),
            repair(setOf("Plain Habit"), db)
        )
    }
}
