package com.example.tail

// Tests for the pill-dialog suggestion builder: time-proximity clustering
// (entries of one submission are stored +1s apart), already-taken-today
// filtering, and keeping deliberately separate doses separate.

import com.example.tail.data.PillLogParser
import com.example.tail.ui.viewmodel.buildPillSuggestions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class PillSuggestionsTest {

    private val catalog = PillLogParser.DEFAULT_CATALOG
    private val today = LocalDate.of(2026, 11, 8)
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun tsAt(dt: LocalDateTime): String = dt.format(fmt)

    /** One stored "submission": pills land as separate entries +1s apart. */
    private fun submit(
        into: MutableMap<String, String>,
        day: LocalDate,
        hour: Int,
        minute: Int,
        vararg pills: String
    ) {
        val base = LocalDateTime.of(day, LocalTime.of(hour, minute, 0))
        pills.forEachIndexed { i, pill -> into[tsAt(base.plusSeconds(i.toLong()))] = pill }
    }

    private val morningBundle = arrayOf(
        "Omega", "B12", "Zinc", "C 1g" // canonicalises to 4 distinct pills
    )
    private val morningBundleCanonical = setOf(
        "Omega-3 Sunday Natural", "B12", "Zinc", "C 1g"
    )

    @Test
    fun `entries seconds apart form one bundled grouping as the top suggestion`() {
        val log = mutableMapOf<String, String>()
        for (d in 1L..6L) submit(log, today.minusDays(d), 8, 0, *morningBundle)

        val suggestions = buildPillSuggestions(log, catalog, LocalTime.of(8, 30), today)

        assertTrue("expected at least one suggestion", suggestions.isNotEmpty())
        val top = suggestions.first()
        assertEquals(morningBundleCanonical, top.counts.keys)
        assertEquals(1, top.counts.values.distinct().single())
    }

    @Test
    fun `grouping already fully logged today is not suggested again`() {
        val log = mutableMapOf<String, String>()
        for (d in 1L..6L) submit(log, today.minusDays(d), 8, 0, *morningBundle)
        // The same bundle was already taken this morning.
        submit(log, today, 8, 0, *morningBundle)

        val suggestions = buildPillSuggestions(log, catalog, LocalTime.of(9, 0), today)

        assertTrue(
            "already-taken bundle must not be suggested",
            suggestions.none { it.counts.keys == morningBundleCanonical }
        )
    }

    @Test
    fun `majority-taken grouping is not suggested but minority-taken is`() {
        val majorityLog = mutableMapOf<String, String>()
        for (d in 1L..6L) submit(majorityLog, today.minusDays(d), 8, 0, *morningBundle)
        // 3 of the 4 bundle pills already logged today → majority satisfied.
        submit(majorityLog, today, 8, 0, "Omega", "B12", "Zinc")
        val majoritySuggestions =
            buildPillSuggestions(majorityLog, catalog, LocalTime.of(9, 0), today)
        assertTrue(
            "majority-taken bundle must not be suggested",
            majoritySuggestions.none { it.counts.keys == morningBundleCanonical }
        )

        val minorityLog = mutableMapOf<String, String>()
        for (d in 1L..6L) submit(minorityLog, today.minusDays(d), 8, 0, *morningBundle)
        // Only 1 of the 4 bundle pills logged today → bundle still offered.
        submit(minorityLog, today, 8, 0, "Omega")
        val minoritySuggestions =
            buildPillSuggestions(minorityLog, catalog, LocalTime.of(9, 0), today)
        assertTrue(
            "minority-taken bundle should still be suggested",
            minoritySuggestions.any { it.counts.keys == morningBundleCanonical }
        )
    }

    @Test
    fun `single pill already taken today is not suggested`() {
        val log = mutableMapOf<String, String>()
        for (d in 1L..6L) submit(log, today.minusDays(d), 20, 0, "Zinc")
        submit(log, today, 20, 0, "Zinc")

        val suggestions = buildPillSuggestions(log, catalog, LocalTime.of(20, 30), today)

        assertTrue(suggestions.none { "Zinc" in it.counts })
    }

    @Test
    fun `doses minutes apart stay separate groupings`() {
        val log = mutableMapOf<String, String>()
        for (d in 1L..6L) {
            submit(log, today.minusDays(d), 8, 0, "Omega")
            submit(log, today.minusDays(d), 8, 20, "Zinc") // 20 min later = deliberate
        }

        val suggestions = buildPillSuggestions(log, catalog, LocalTime.of(8, 10), today)

        assertTrue(
            "separate doses must not merge into one grouping",
            suggestions.none { it.counts.size > 1 }
        )
        // Both singles should still be offered.
        assertTrue(suggestions.any { "Omega-3 Sunday Natural" in it.counts })
        assertTrue(suggestions.any { "Zinc" in it.counts })
    }

    @Test
    fun `repeated pill in one submission keeps its per-pill count`() {
        val log = mutableMapOf<String, String>()
        for (d in 1L..6L) {
            submit(
                log, today.minusDays(d), 8, 0,
                "Omega", "Omega", "Zinc" // Omega ×2 deliberate re-dose
            )
        }

        val suggestions = buildPillSuggestions(log, catalog, LocalTime.of(8, 30), today)

        val top = suggestions.first()
        assertEquals(2, top.counts["Omega-3 Sunday Natural"])
        assertEquals(1, top.counts["Zinc"])
    }

    @Test
    fun `varying bundle yields a consensus chip instead of the last exact grouping`() {
        // Omega + Zinc + B12 every morning; a 4th pill (C 1g) only on 2 of 12 days,
        // including yesterday. The old "most recent grouping" logic led with the C bundle.
        val log = mutableMapOf<String, String>()
        for (d in 1L..12L) {
            if (d == 1L || d == 6L) submit(log, today.minusDays(d), 8, 0, "Omega", "Zinc", "B12", "C 1g")
            else submit(log, today.minusDays(d), 8, 0, "Omega", "Zinc", "B12")
        }

        val top = buildPillSuggestions(log, catalog, LocalTime.of(8, 20), today).first()

        assertEquals(
            setOf("Omega-3 Sunday Natural", "Zinc", "B12"),
            top.counts.keys
        )
    }

    @Test
    fun `evening pills are not suggested in the morning`() {
        val log = mutableMapOf<String, String>()
        for (d in 1L..10L) {
            submit(log, today.minusDays(d), 8, 0, "Omega")
            submit(log, today.minusDays(d), 21, 30, "Zinc")
        }

        val morning = buildPillSuggestions(log, catalog, LocalTime.of(8, 10), today)

        assertTrue(morning.first().counts.keys == setOf("Omega-3 Sunday Natural"))
    }

    @Test
    fun `weekend-only pill is not suggested on a weekday`() {
        // today = Sunday 2026-11-08 → pick a weekday "today" instead.
        val monday = LocalDate.of(2026, 11, 9)
        val log = mutableMapOf<String, String>()
        for (d in 1L..42L) {
            val day = monday.minusDays(d)
            val weekend = day.dayOfWeek.value >= 6
            if (weekend) submit(log, day, 9, 0, "Omega", "Zinc")
            else submit(log, day, 9, 0, "Omega")
        }

        val top = buildPillSuggestions(log, catalog, LocalTime.of(9, 10), monday).first()

        assertEquals(setOf("Omega-3 Sunday Natural"), top.counts.keys)
    }

    @Test
    fun `partially served count still suggests the full bundle`() {
        // Bundle says Omega ×2; only 1 Omega logged today → not satisfied,
        // bundle survives the filter and the chip prefills the remainder.
        val log = mutableMapOf<String, String>()
        for (d in 1L..6L) {
            submit(log, today.minusDays(d), 8, 0, "Omega", "Omega", "Zinc", "B12")
        }
        submit(log, today, 8, 0, "Omega") // 1 of 2 Omegas done

        val suggestions = buildPillSuggestions(log, catalog, LocalTime.of(9, 0), today)

        val bundle = suggestions.firstOrNull {
            it.counts.keys == setOf("Omega-3 Sunday Natural", "Zinc", "B12")
        }
        assertTrue("bundle with unmet Omega ×2 must still be suggested", bundle != null)
        assertEquals(2, bundle?.counts?.get("Omega-3 Sunday Natural"))
    }
}
