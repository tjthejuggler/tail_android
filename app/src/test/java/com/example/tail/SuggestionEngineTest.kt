package com.example.tail

// Tests for the shared suggestion engine: habit scoring (what was done so
// far today, what never happens on comparable days), value prediction
// (the "200 ml water because it was the last one" problem) and amount
// recovery from day totals.

import com.example.tail.data.suggest.AmountHistory
import com.example.tail.data.suggest.HabitScorer
import com.example.tail.data.suggest.StampIndex
import com.example.tail.data.suggest.ValuePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SuggestionEngineTest {

    // A Sunday, so "weekday" vs "weekend" classes are exercised.
    private val today = LocalDate.of(2026, 11, 22)

    private fun history(
        habit: String,
        days: Iterable<Long>,
        vararg times: String,
        into: MutableMap<String, MutableMap<String, List<String>>>
    ) {
        for (d in days) into.getOrPut(habit) { mutableMapOf() }[today.minusDays(d).toString()] = times.toList()
    }

    private fun scorer(raw: Map<String, Map<String, List<String>>>, nowMin: Int) =
        HabitScorer(StampIndex(raw, today, 120), nowMin)

    @Test
    fun `daily morning habit is likely around its usual time`() {
        val raw = mutableMapOf<String, MutableMap<String, List<String>>>()
        history("Meditate", 1L..30L, "08:10:00", into = raw)
        val s = scorer(raw, 8 * 60 + 5).score("Meditate")
        assertNotNull(s)
        assertTrue("expected high score, was ${s!!.score}", s.score > 0.6)
    }

    @Test
    fun `habit never done on weekends is not suggested on a weekend`() {
        val raw = mutableMapOf<String, MutableMap<String, List<String>>>()
        val weekdays = (1L..56L).filter { !today.minusDays(it).dayOfWeek.name.startsWith("S") }
        history("Commute stretch", weekdays, "08:00:00", into = raw)
        // today is Sunday
        val s = scorer(raw, 8 * 60).score("Commute stretch")
        assertTrue("Sunday should not suggest a weekday-only habit: ${s?.score}", s == null || s.score < 0.30)
    }

    @Test
    fun `habit already done today at its usual time is not suggested again`() {
        val raw = mutableMapOf<String, MutableMap<String, List<String>>>()
        history("Vitamins", 1L..30L, "08:00:00", into = raw)
        raw.getOrPut("Vitamins") { mutableMapOf() }[today.toString()] = listOf("08:05:00")
        val s = scorer(raw, 9 * 60).score("Vitamins")
        assertTrue("already done once, never done twice: ${s?.score}", s == null || s.score < 0.30)
    }

    @Test
    fun `habit usually done twice is still suggested after the first time`() {
        val raw = mutableMapOf<String, MutableMap<String, List<String>>>()
        history("Brush teeth", 1L..30L, "07:30:00", "21:30:00", into = raw)
        raw.getOrPut("Brush teeth") { mutableMapOf() }[today.toString()] = listOf("07:35:00")
        val s = scorer(raw, 21 * 60 + 15).score("Brush teeth")
        assertNotNull(s)
        assertTrue("second daily occurrence expected: ${s!!.score}", s.score > 0.5)
    }

    @Test
    fun `overdue daily habit is suggested after its usual time has passed`() {
        val raw = mutableMapOf<String, MutableMap<String, List<String>>>()
        history("Journal", 1L..30L, "09:00:00", into = raw)
        val s = scorer(raw, 13 * 60).score("Journal")
        assertNotNull(s)
        assertTrue(s!!.overdue)
        assertTrue(s.score >= 0.30)
    }

    @Test
    fun `rarely done habit is never suggested`() {
        val raw = mutableMapOf<String, MutableMap<String, List<String>>>()
        history("Rare", listOf(3L, 20L), "08:00:00", into = raw)
        assertNull(scorer(raw, 8 * 60).score("Rare"))
    }

    @Test
    fun `abandoned habit is not suggested`() {
        val raw = mutableMapOf<String, MutableMap<String, List<String>>>()
        history("Old", 60L..100L, "08:00:00", into = raw)
        assertNull(scorer(raw, 8 * 60).score("Old"))
    }

    // ---- value prediction -------------------------------------------------

    private fun amountEvents(vararg perDay: Triple<Long, String, Int>) =
        ValuePicker.events(perDay.map { (d, hm, a) ->
            Triple(today.minusDays(d), hm.substring(0, 2).toInt() * 60 + hm.substring(3, 5).toInt(), a)
        })

    @Test
    fun `habitual amount beats the last used amount`() {
        // 25 days of 500 ml, but yesterday a one-off 200 ml.
        val evs = ArrayList(amountEvents(Triple(1L, "15:00", 200)))
        for (d in 2L..26L) evs += amountEvents(Triple(d, "15:00", 500))
        val top = ValuePicker.rank(evs, today, 15 * 60, 0, null).first()
        assertEquals(500, top.first)
    }

    @Test
    fun `amount follows the time of day`() {
        val evs = ArrayList<com.example.tail.data.suggest.ValueEvent<Int>>()
        for (d in 1L..20L) {
            evs += amountEvents(Triple(d, "08:00", 250))
            evs += amountEvents(Triple(d, "20:00", 500))
        }
        assertEquals(250, ValuePicker.rank(evs, today, 8 * 60, 0, null).first().first)
        assertEquals(500, ValuePicker.rank(evs, today, 20 * 60, 0, null).first().first)
    }

    @Test
    fun `day total decomposition recovers the habitual amount`() {
        // Three 500 ml drinks a day (1500 total) — only totals + stamps stored.
        val stamps = mutableMapOf<String, List<String>>()
        val totals = mutableMapOf<String, Int>()
        for (d in 1L..20L) {
            val ds = today.minusDays(d).toString()
            stamps[ds] = listOf("09:00:00", "13:00:00", "18:00:00")
            totals[ds] = 1500
        }
        val events = AmountHistory.build(
            stamps, totals, emptyMap(), setOf(200, 250, 500), today, 120
        )
        val top = ValuePicker.rank(events, today, 13 * 60, 0, null).first()
        assertEquals(500, top.first)
    }

    @Test
    fun `exact amount log is preferred over decomposition`() {
        val exact = mutableMapOf<String, Int>()
        for (d in 1L..10L) exact["${today.minusDays(d)} 10:00:00"] = 330
        val events = AmountHistory.build(
            emptyMap(), emptyMap(), exact, setOf(200, 330), today, 120
        )
        assertEquals(330, ValuePicker.rank(events, today, 10 * 60, 0, null).first().first)
    }

    @Test
    fun `already-logged value is damped when it is never repeated within a day`() {
        val evs = ArrayList<com.example.tail.data.suggest.ValueEvent<String>>()
        for (d in 1L..15L) {
            evs += ValuePicker.events(listOf(Triple(today.minusDays(d), 8 * 60, "Omelette")))
            evs += ValuePicker.events(listOf(Triple(today.minusDays(d), 13 * 60, "Salad")))
        }
        val ranked = ValuePicker.rank(
            evs, today, 12 * 60, todayOrdinal = 1, todayPrev = "Omelette", todayValues = setOf("Omelette")
        )
        assertEquals("Salad", ranked.first().first)
    }
}
