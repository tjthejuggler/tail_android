package com.example.tail

import com.example.tail.data.meal.FoodDataJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the meal voice/text LLM response parser.
 *
 * Root cause these guard against: a multi-food description ("vegan burger
 * and fries and salad and watermelon") produced a long or array-shaped LLM
 * response that the old bare-JSONObject parser rejected with
 * "AI could not parse the description".
 */
class FoodDataJsonParserTest {

    private val fullJson = """
        {
          "title": "Vegan burger with fries and salad",
          "summary": "Plant-based burger with sides and fruit.",
          "is_vegan_verified": true,
          "estimated_calories": 950,
          "macronutrients": { "protein_grams": 28, "carbs_grams": 110, "fat_grams": 38 },
          "ingredients_detected": ["vegan burger", "fries", "salad", "watermelon"],
          "health_notes": "Balanced plant-based meal.",
          "macro_ratings": { "protein": 2, "carbs": 3, "fat": 3 }
        }
    """.trimIndent()

    @Test
    fun parsesBareJsonObject() {
        val fd = FoodDataJsonParser.parseResponse(fullJson)
        assertNotNull(fd)
        fd!!
        assertEquals("Vegan burger with fries and salad", fd.title)
        assertEquals(950, fd.estimatedCalories)
        assertTrue(fd.isVeganVerified)
        assertEquals(28.0, fd.macronutrients.proteinGrams, 0.01)
        assertEquals(4, fd.ingredientsDetected.size)
        assertEquals(3, fd.macroRatings?.carbs)
    }

    @Test
    fun parsesMarkdownFencedJson() {
        val content = "```json\n$fullJson\n```"
        val fd = FoodDataJsonParser.parseResponse(content)
        assertNotNull(fd)
        assertEquals("Vegan burger with fries and salad", fd!!.title)
    }

    @Test
    fun parsesJsonWithPreamble() {
        val content = "Here is the JSON you asked for:\n$fullJson"
        val fd = FoodDataJsonParser.parseResponse(content)
        assertNotNull(fd)
        assertEquals(950, fd!!.estimatedCalories)
    }

    @Test
    fun parsesJsonWithTrailingCommentary() {
        val content = "$fullJson\nHope this helps — bon appétit!"
        val fd = FoodDataJsonParser.parseResponse(content)
        assertNotNull(fd)
        assertEquals("Vegan burger with fries and salad", fd!!.title)
    }

    @Test
    fun mergesTopLevelArrayOfFoods() {
        val content = """
            [
              { "title": "Vegan burger", "estimated_calories": 500,
                "macronutrients": { "protein_grams": 25, "carbs_grams": 45, "fat_grams": 20 },
                "ingredients_detected": ["vegan burger", "bun"] },
              { "title": "Fries", "estimated_calories": 300,
                "macronutrients": { "protein_grams": 3, "carbs_grams": 40, "fat_grams": 15 },
                "ingredients_detected": ["fries"] }
            ]
        """.trimIndent()
        val fd = FoodDataJsonParser.parseResponse(content)
        assertNotNull(fd)
        fd!!
        assertEquals("Vegan burger, Fries", fd.title)
        assertEquals(800, fd.estimatedCalories)
        assertEquals(28.0, fd.macronutrients.proteinGrams, 0.01)
        assertEquals(85.0, fd.macronutrients.carbsGrams, 0.01)
        assertEquals(listOf("vegan burger", "bun", "fries"), fd.ingredientsDetected)
    }

    @Test
    fun mergesFoodsWrapperArray() {
        val content = """
            { "foods": [
              { "title": "Salad", "estimated_calories": 150 },
              { "title": "Watermelon", "estimated_calories": 90 }
            ] }
        """.trimIndent()
        val fd = FoodDataJsonParser.parseResponse(content)
        assertNotNull(fd)
        fd!!
        assertEquals("Salad, Watermelon", fd.title)
        assertEquals(240, fd.estimatedCalories)
    }

    @Test
    fun parsesFoodDataWrapper() {
        val content = """{ "food_data": { "title": "Buddha bowl", "estimated_calories": 620 } }"""
        val fd = FoodDataJsonParser.parseResponse(content)
        assertNotNull(fd)
        assertEquals("Buddha bowl", fd!!.title)
        assertEquals(620, fd.estimatedCalories)
    }

    @Test
    fun salvagesTruncatedJsonCutInsideIngredients() {
        // Simulates a response cut off by max_tokens mid-ingredients-array
        val cut = fullJson.indexOf("\"watermelon\"")
        assertTrue(cut > 0)
        val truncated = fullJson.substring(0, cut)
        val fd = FoodDataJsonParser.parseResponse(truncated)
        assertNotNull("Truncated JSON should be salvaged, not rejected", fd)
        fd!!
        assertEquals("Vegan burger with fries and salad", fd.title)
        assertEquals(950, fd.estimatedCalories)
        assertTrue(fd.ingredientsDetected.contains("salad"))
    }

    @Test
    fun salvagesTruncatedJsonCutMidValue() {
        // Cut right before the carbs number: "carbs_grams": <EOF>
        val cut = fullJson.indexOf("110")
        assertTrue(cut > 0)
        val truncated = fullJson.substring(0, cut)
        val fd = FoodDataJsonParser.parseResponse(truncated)
        assertNotNull(fd)
        fd!!
        assertEquals(950, fd.estimatedCalories)
        assertEquals(28.0, fd.macronutrients.proteinGrams, 0.01)
        assertEquals(0.0, fd.macronutrients.carbsGrams, 0.01)
    }

    @Test
    fun salvagesTruncatedJsonCutInsideFirstValue() {
        val truncated = """{ "title": "Veg"""
        val fd = FoodDataJsonParser.parseResponse(truncated)
        assertNull("Nothing substantive survives this cut", fd)
    }

    @Test
    fun coercesNumericStrings() {
        val content = """
            { "title": "Curry", "estimated_calories": "620 kcal",
              "macronutrients": { "protein_grams": "18g", "carbs_grams": 75, "fat_grams": 22.5 } }
        """.trimIndent()
        val fd = FoodDataJsonParser.parseResponse(content)
        assertNotNull(fd)
        fd!!
        assertEquals(620, fd.estimatedCalories)
        assertEquals(18.0, fd.macronutrients.proteinGrams, 0.01)
        assertEquals(22.5, fd.macronutrients.fatGrams, 0.01)
    }

    @Test
    fun returnsNullForGarbage() {
        assertNull(FoodDataJsonParser.parseResponse("I could not analyse that."))
        assertNull(FoodDataJsonParser.parseResponse(""))
    }

    @Test
    fun returnsNullForEmptyObject() {
        assertNull(FoodDataJsonParser.parseResponse("{}"))
        assertNull(FoodDataJsonParser.parseResponse("""{ "title": "" }"""))
    }
}
