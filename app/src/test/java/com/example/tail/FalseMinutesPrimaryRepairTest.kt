package com.example.tail

import com.example.tail.data.falseMinutesPrimaryHabits
import com.example.tail.data.fallbackSlotKey
import com.example.tail.data.applyDivider
import com.example.tail.data.effectivePointsWithFallback
import com.example.tail.data.minutesKey
import com.example.tail.data.secondaryValueKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for the one-time Wags minutes-primary repair
 * ([com.example.tail.data.falseMinutesPrimaryHabits]) and the points /
 * fallback behaviour it restores.
 *
 * Context: the Wags IPC protocol stores minutes in the PRIMARY key and
 * sessions in the legacy `secondary_value:` slot. The Aug-18-2026
 * minutes-slot rollout wrongly classified Wags-fed habits as
 * minutes-primary, which expects minutes in the first-class `minutes:`
 * slot — so points showed raw undivided minutes and the sessions metric
 * vanished from the graph.
 */
class FalseMinutesPrimaryRepairTest {

    private fun repair(
        minutesPrimary: Set<String>,
        db: Map<String, Map<String, Int>> = emptyMap(),
        pcWidget: Set<String> = emptySet(),
        widgetTrigger: Set<String> = emptySet(),
        media: Set<String> = emptySet(),
        bridgeMovie: Set<String> = emptySet(),
        chessLinked: Set<String> = emptySet()
    ): Set<String> = falseMinutesPrimaryHabits(
        widgetTimerMinutesPrimary = minutesPrimary,
        pcWidgetHabits = pcWidget,
        widgetTriggerHabits = widgetTrigger,
        mediaHabits = media,
        bridgeMovieHabits = bridgeMovie,
        chessLinked = chessLinked,
        db = db
    )

    // ── Target identification ────────────────────────────────────────────────

    @Test
    fun `wags habit with sessions and empty minutes slot is repaired`() {
        val db = mapOf(
            "Progressive O2" to mapOf("2026-08-19" to 16),
            secondaryValueKey("Progressive O2") to mapOf("2026-08-19" to 2)
        )
        assertEquals(
            setOf("Progressive O2"),
            repair(setOf("Progressive O2"), db)
        )
    }

    @Test
    fun `wags habit with stray minutes entry and sessions is repaired`() {
        // Apnea Min Breath on the broken device: one stray hand-entered
        // minutes: value plus the full session history in secondary_value:.
        val db = mapOf(
            "Apnea Min Breath" to mapOf("2026-04-10" to 4),
            minutesKey("Apnea Min Breath") to mapOf("2026-08-19" to 6, "2026-08-20" to 0),
            secondaryValueKey("Apnea Min Breath") to mapOf("2026-04-10" to 1, "2026-05-02" to 2)
        )
        assertEquals(
            setOf("Apnea Min Breath"),
            repair(setOf("Apnea Min Breath"), db)
        )
    }

    @Test
    fun `fresh habit with no data at all is repaired`() {
        // Contraction Count on the broken device: connected to Wags but no
        // data yet — still wrongly minutes-primary.
        assertEquals(
            setOf("Contraction Count"),
            repair(setOf("Contraction Count"))
        )
    }

    @Test
    fun `widget-trigger habit is never repaired`() {
        val db = mapOf(
            "Language studied" to mapOf("2026-08-19" to 30),
            secondaryValueKey("Language studied") to mapOf("2026-08-19" to 2)
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
    fun `pc-widget habit is never repaired`() {
        val db = mapOf(
            minutesKey("Good posture") to mapOf("2026-08-19" to 42)
        )
        assertEquals(
            emptySet<String>(),
            repair(setOf("Good posture"), db, pcWidget = setOf("Good posture"))
        )
    }

    @Test
    fun `habit with real minutes data and no sessions is never repaired`() {
        // Podcast finished / Music listen: deliberately minutes-primary,
        // fed minutes in the minutes: slot, no Wags sessions.
        val db = mapOf(
            minutesKey("Podcast finished") to mapOf("2026-08-18" to 35, "2026-08-19" to 20)
        )
        assertEquals(
            emptySet<String>(),
            repair(setOf("Podcast finished"), db)
        )
    }

    @Test
    fun `hardcoded Good Posture name is never repaired`() {
        assertEquals(
            emptySet<String>(),
            repair(setOf("Good Posture"))
        )
    }

    @Test
    fun `habit outside minutes-primary set is never repaired`() {
        // Meditations / Resonance Breathing: already on the correct pattern.
        val db = mapOf(
            "Meditations" to mapOf("2026-08-19" to 15),
            secondaryValueKey("Meditations") to mapOf("2026-08-19" to 1)
        )
        assertEquals(emptySet<String>(), repair(emptySet(), db))
    }

    @Test
    fun `device replica - exactly the seven wags habits are repaired`() {
        // Replica of the phone state on 2026-08-20: 17 habits in
        // widgetTimerMinutesPrimary, 10 legitimate + 7 Wags-fed.
        val minutesPrimary = setOf(
            "Language studied", "Slow Chess Puzzle", "Fast Chess Puzzle",
            "Podcast finished", "Music listen", "Anki created", "Some anki",
            "Janki used", "Good posture", "Programming sessions",
            "Apnea apb", "O2 Tables", "CO2 Tables", "Progressive O2",
            "Apnea Min Breath", "Until Contraction", "Contraction Count"
        )
        val widgetTrigger = setOf(
            "Language studied", "Slow Chess Puzzle", "Fast Chess Puzzle",
            "Anki created", "Some anki", "Janki used"
        )
        val pcWidget = setOf("Good posture", "Drew", "Programming sessions", "Writing sessions")
        val db = mapOf(
            "Apnea apb" to mapOf("2026-08-17" to 12),
            secondaryValueKey("Apnea apb") to mapOf("2021-10-04" to 1, "2026-08-17" to 2),
            "O2 Tables" to mapOf("2026-04-02" to 6),
            secondaryValueKey("O2 Tables") to mapOf("2026-04-02" to 1),
            "CO2 Tables" to mapOf("2026-07-17" to 16),
            secondaryValueKey("CO2 Tables") to mapOf("2026-07-17" to 1),
            "Progressive O2" to mapOf("2026-04-10" to 25),
            secondaryValueKey("Progressive O2") to mapOf("2026-04-10" to 3),
            "Apnea Min Breath" to mapOf("2026-04-10" to 4),
            minutesKey("Apnea Min Breath") to mapOf("2026-08-19" to 6),
            secondaryValueKey("Apnea Min Breath") to mapOf("2026-04-10" to 1),
            "Until Contraction" to mapOf("2026-08-17" to 8),
            secondaryValueKey("Until Contraction") to mapOf("2026-08-17" to 1),
            "Contraction Count" to mapOf("2026-08-17" to 0),
            minutesKey("Language studied") to mapOf("2026-08-19" to 22),
            minutesKey("Slow Chess Puzzle") to mapOf("2026-08-19" to 15),
            minutesKey("Fast Chess Puzzle") to mapOf("2026-08-19" to 10),
            minutesKey("Podcast finished") to mapOf("2026-08-19" to 35),
            minutesKey("Music listen") to mapOf("2026-08-19" to 20),
            minutesKey("Good posture") to mapOf("2026-08-19" to 42),
            minutesKey("Programming sessions") to mapOf("2026-08-19" to 60)
        )
        assertEquals(
            setOf(
                "Apnea apb", "O2 Tables", "CO2 Tables", "Progressive O2",
                "Apnea Min Breath", "Until Contraction", "Contraction Count"
            ),
            repair(minutesPrimary, db, pcWidget = pcWidget, widgetTrigger = widgetTrigger)
        )
    }

    // ── Restored points / fallback behaviour ─────────────────────────────────

    @Test
    fun `divider applies to primary minutes after repair`() {
        // 16 minutes with divider 10 → 2 points (the reported bug showed 16).
        assertEquals(2, applyDivider(16, 10))
    }

    @Test
    fun `fallback slot resolves to sessions key after repair`() {
        // Post-repair state: habit is a Value2-track member again, so the
        // points fallback reads the legacy secondary_value: slot.
        val db = mapOf(
            secondaryValueKey("Apnea apb") to mapOf("2021-10-04" to 1),
            minutesKey("Apnea apb") to emptyMap()
        )
        assertEquals(
            secondaryValueKey("Apnea apb"),
            fallbackSlotKey("Apnea apb", setOf("Apnea apb"), db)
        )
    }

    @Test
    fun `zero-minute days fall back to raw session count`() {
        // Legacy Apnea apb / spb / practiced behaviour: a day with sessions
        // but no minutes scores the session count as points.
        assertEquals(3, effectivePointsWithFallback(0, 10, 3, true))
        // Minutes present → divider applies, sessions ignored.
        assertEquals(2, effectivePointsWithFallback(16, 10, 3, true))
        // Nothing recorded → 0 points.
        assertEquals(0, effectivePointsWithFallback(0, 10, 0, true))
    }
}
