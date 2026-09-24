package com.example.tail

import com.example.tail.data.environment.WaterHardnessLlm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Tests for the LLM water-hardness answer parser. */
class WaterHardnessLlmTest {

    @Test
    fun `parses bare integer answers`() {
        assertEquals(280.0, WaterHardnessLlm.parsePpm("280")!!, 0.0)
    }

    @Test
    fun `parses answers with units and prose`() {
        assertEquals(305.0, WaterHardnessLlm.parsePpm("305 mg/L CaCO3")!!, 0.0)
        assertEquals(120.0, WaterHardnessLlm.parsePpm("The hardness is about 120 ppm.")!!, 0.0)
    }

    @Test
    fun `parses decimals`() {
        assertEquals(45.0, WaterHardnessLlm.parsePpm("45.2")!!, 0.0)
    }

    @Test
    fun `rejects out-of-band nonsense`() {
        assertNull(WaterHardnessLlm.parsePpm("I don't know"))
        assertNull(WaterHardnessLlm.parsePpm("99999"))
        assertNull(WaterHardnessLlm.parsePpm(""))
    }
}
