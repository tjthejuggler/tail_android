package com.example.tail

import com.example.tail.data.PillLogParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance criteria from the TailCue pill-line-semantics spec.
 */
class PillLogParserTest {

    private val catalog = PillLogParser.DEFAULT_CATALOG

    private fun doseOf(day: com.example.tail.data.PillDayDoses, pill: String, nutrient: String): Double =
        day.doses[pill]?.firstOrNull { it.name.equals(nutrient, ignoreCase = true) }?.amount ?: -1.0

    @Test
    fun `three omega lines yield tripled DHA and EPA`() {
        val day = PillLogParser.parseDay(
            listOf(
                "Omega-3 Sunday Natural",
                "Omega-3 Sunday Natural",
                "Omega-3 Sunday Natural"
            ), catalog
        )
        assertEquals(3, day.servings["Omega-3 Sunday Natural"])
        assertEquals(1050.0, doseOf(day, "Omega-3 Sunday Natural", "DHA"), 0.001)
        assertEquals(540.0, doseOf(day, "Omega-3 Sunday Natural", "EPA"), 0.001)
    }

    @Test
    fun `one omega line yields single serving doses`() {
        val day = PillLogParser.parseDay(listOf("Omega-3 Sunday Natural"), catalog)
        assertEquals(350.0, doseOf(day, "Omega-3 Sunday Natural", "DHA"), 0.001)
        assertEquals(180.0, doseOf(day, "Omega-3 Sunday Natural", "EPA"), 0.001)
    }

    /** Total daily dose for a nutrient across all pills (Creatine variants share the nutrient). */
    private fun totalDose(day: com.example.tail.data.PillDayDoses, nutrient: String): Double =
        day.doses.values.sumOf { list ->
            list.filter { it.name.equals(nutrient, ignoreCase = true) }.sumOf { it.amount }
        }

    @Test
    fun `two creatine 3g lines sum to 6g`() {
        val day = PillLogParser.parseDay(listOf("Creatine 3g", "Creatine 3g"), catalog)
        assertEquals(2, day.servings.values.sum())
        assertEquals(6.0, totalDose(day, "Creatine"), 0.001)
        // Explicit typed-dose override: two typed 5g lines = 10g
        val typed = PillLogParser.parseDay(listOf("Creatine 5g", "Creatine 5g"), catalog)
        assertEquals(10.0, totalDose(typed, "Creatine"), 0.001)
    }

    @Test
    fun `bare unknown line yields presence only without error`() {
        val day = PillLogParser.parseDay(listOf("Some exotic herb"), catalog)
        assertTrue(day.unmatched.contains("Some exotic herb"))
        assertTrue(day.servings.isEmpty())
        assertTrue(day.doses.isEmpty())
    }

    @Test
    fun `alias omega resolves to canonical pill`() {
        assertEquals("Omega-3 Sunday Natural", PillLogParser.matchPill("Omega", catalog))
        assertEquals("Omega-3 Sunday Natural", PillLogParser.matchPill("omega-3", catalog))
    }

    @Test
    fun `mineral stack combined line matches full stack`() {
        val day = PillLogParser.parseDay(
            listOf("Calcium, Magnesium, Zinc, D3, K2, Copper, Boron"), catalog
        )
        val pill = day.servings.keys.single()
        assertTrue(pill.startsWith("Calcium"))
        assertEquals(500.0, doseOf(day, pill, "Calcium"), 0.001)
        assertEquals(0.6, doseOf(day, pill, "Boron"), 0.001)
    }

    @Test
    fun `unknown line with typed dose token counts the typed amount`() {
        val day = PillLogParser.parseDay(listOf("Ashwagandha 2g"), catalog)
        // "Ashwagandha 2g" matches the catalog → serving 1, typed dose 2g
        assertEquals(1, day.servings["Ashwagandha"])
        assertEquals(2.0, doseOf(day, "Ashwagandha", "Ashwagandha"), 0.001)
    }

    @Test
    fun `multiline log value splits into lines`() {
        assertEquals(3, PillLogParser.splitLines("Omega\nCreatine 3g\n\nIron").size)
    }

    @Test
    fun `empty day yields empty result`() {
        val day = PillLogParser.parseDay(emptyList(), catalog)
        assertTrue(day.servings.isEmpty())
        assertNull(day.doses["Omega-3 Sunday Natural"])
    }

    @Test
    fun `b complex description parses full B spectrum`() {
        val nutrients = PillLogParser.parseDescription(
            catalog["B Complex Nature Love"]!!
        )
        assertEquals(8, nutrients.size)
        assertEquals(500.0, nutrients.first { it.name == "B12" }.amount, 0.001)
        assertEquals("mcg", nutrients.first { it.name == "B12" }.unit)
    }
}
