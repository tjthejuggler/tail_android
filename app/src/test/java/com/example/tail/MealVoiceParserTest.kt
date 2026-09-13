package com.example.tail

import com.example.tail.data.meal.MealVoiceParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the quick-capture meal utterance parser [MealVoiceParser]:
 * the "i ate" prefix recognition and the meal habit / subtype / title
 * resolution used by the smart voice routing.
 */
class MealVoiceParserTest {

    // ── parse — prefix recognition ────────────────────────────────────────

    @Test
    fun `basic i ate prefix extracts description`() {
        val u = MealVoiceParser.parse("i ate oatmeal with berries")
        assertEquals("oatmeal with berries", u!!.description)
        assertEquals("i ate oatmeal with berries", u.raw)
    }

    @Test
    fun `prefix is case-insensitive`() {
        assertEquals("pizza", MealVoiceParser.parse("I ATE pizza")!!.description)
        assertEquals("pizza", MealVoiceParser.parse("I ate pizza")!!.description)
    }

    @Test
    fun `contraction i've ate is recognised`() {
        assertEquals("oats and fruit", MealVoiceParser.parse("i've ate oats and fruit")!!.description)
    }

    @Test
    fun `expansion i have ate is recognised`() {
        assertEquals("two eggs", MealVoiceParser.parse("I have ate two eggs")!!.description)
    }

    @Test
    fun `punctuation directly after prefix is stripped`() {
        assertEquals("oats", MealVoiceParser.parse("i ate: oats")!!.description)
        assertEquals("soup", MealVoiceParser.parse("i ate, soup")!!.description)
        assertEquals("rice", MealVoiceParser.parse("i ate rice")!!.description)
    }

    @Test
    fun `extra whitespace is normalised`() {
        val u = MealVoiceParser.parse("  I   ate      lentil soup ")
        assertEquals("lentil soup", u!!.description)
        // raw keeps the (normalised) original wording for the transcript
        assertEquals("I ate lentil soup", u.raw)
    }

    @Test
    fun `bare i ate yields empty description`() {
        assertEquals("", MealVoiceParser.parse("i ate")!!.description)
    }

    @Test
    fun `ate without leading i is not a meal utterance`() {
        assertNull(MealVoiceParser.parse("yesterday I ate pizza"))
        assertNull(MealVoiceParser.parse("she ate all the cookies"))
    }

    @Test
    fun `unrelated utterances are not parsed as meals`() {
        assertNull(MealVoiceParser.parse("pushups 25"))
        assertNull(MealVoiceParser.parse("note to self buy rice"))
        assertNull(MealVoiceParser.parse(""))
        assertNull(MealVoiceParser.parse("   "))
    }

    // ── pickMealHabit ─────────────────────────────────────────────────────

    @Test
    fun `single meal habit wins outright`() {
        val habit = MealVoiceParser.pickMealHabit(
            "oatmeal", mealHabits = setOf("Meals"), habitSubtypes = emptyMap()
        )
        assertEquals("Meals", habit)
    }

    @Test
    fun `no meal habits resolves to null`() {
        assertNull(
            MealVoiceParser.pickMealHabit("oatmeal", mealHabits = emptySet(), habitSubtypes = emptyMap())
        )
    }

    @Test
    fun `mentioned subtype selects its habit among multiple meal habits`() {
        val subtypes = mapOf(
            "Meals" to listOf("breakfast", "lunch", "dinner"),
            "Snacks" to listOf("chips", "candy")
        )
        val habit = MealVoiceParser.pickMealHabit(
            "had the usual breakfast oats", setOf("Meals", "Snacks"), subtypes
        )
        assertEquals("Meals", habit)
    }

    @Test
    fun `mentioned habit name selects that habit`() {
        val habit = MealVoiceParser.pickMealHabit(
            "quick snacks at the movies", setOf("Meals", "Snacks"), habitSubtypes = emptyMap()
        )
        assertEquals("Snacks", habit)
    }

    @Test
    fun `no mention falls back to first meal habit`() {
        val habit = MealVoiceParser.pickMealHabit(
            "something tasty", setOf("Meals", "Snacks"), habitSubtypes = emptyMap()
        )
        assertEquals("Meals", habit)
    }

    // ── matchSubtype ──────────────────────────────────────────────────────

    @Test
    fun `subtype mention is matched case-insensitively`() {
        val subtypes = listOf("Breakfast", "Lunch", "Dinner")
        assertEquals("Breakfast", MealVoiceParser.matchSubtype("big breakfast today", subtypes))
        assertEquals("Dinner", MealVoiceParser.matchSubtype("DINNER time", subtypes))
    }

    @Test
    fun `no subtype mentioned resolves to null`() {
        assertNull(MealVoiceParser.matchSubtype("a bowl of oats", listOf("Breakfast", "Lunch")))
    }

    @Test
    fun `empty subtype list resolves to null`() {
        assertNull(MealVoiceParser.matchSubtype("anything", emptyList()))
    }

    // ── buildTitle ────────────────────────────────────────────────────────

    @Test
    fun `title is capitalised and punctuation-trimmed`() {
        assertEquals("Oatmeal with berries", MealVoiceParser.buildTitle("oatmeal with berries."))
        assertEquals("Rice", MealVoiceParser.buildTitle("  rice "))
    }

    @Test
    fun `empty description falls back to Meal`() {
        assertEquals("Meal", MealVoiceParser.buildTitle(""))
        assertEquals("Meal", MealVoiceParser.buildTitle("   "))
    }

    @Test
    fun `long description is capped at 60 chars with ellipsis`() {
        val long = "a".repeat(100)
        val title = MealVoiceParser.buildTitle(long)
        assertEquals(61, title.length)
        assertTrue(title.endsWith("…"))
    }
}
