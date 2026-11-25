package com.example.tail

import com.example.tail.data.WEIGHT_UNIT_KG
import com.example.tail.data.WEIGHT_UNIT_LB
import com.example.tail.data.decodeWeightsExerciseDefault
import com.example.tail.data.encodeWeightsExerciseDefault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the per-exercise weights-defaults codec ("unit|machine"),
 * the value stored in AppSettings.weightsExerciseDefaults and consumed by
 * WeightsInputDialog to restore the kg/lb + Machine/Free bubbles that were
 * last used with the selected exercise.
 */
class WeightsExerciseDefaultCodecTest {

    // ── Round-trips: every bubble combination survives encode → decode ────

    @Test
    fun kgMachineRoundTrips() {
        val raw = encodeWeightsExerciseDefault(WEIGHT_UNIT_KG, machine = true)
        assertEquals(WEIGHT_UNIT_KG to true, decodeWeightsExerciseDefault(raw))
    }

    @Test
    fun lbMachineRoundTrips() {
        val raw = encodeWeightsExerciseDefault(WEIGHT_UNIT_LB, machine = true)
        assertEquals(WEIGHT_UNIT_LB to true, decodeWeightsExerciseDefault(raw))
    }

    @Test
    fun kgFreeRoundTrips() {
        val raw = encodeWeightsExerciseDefault(WEIGHT_UNIT_KG, machine = false)
        assertEquals(WEIGHT_UNIT_KG to false, decodeWeightsExerciseDefault(raw))
    }

    @Test
    fun lbFreeRoundTrips() {
        val raw = encodeWeightsExerciseDefault(WEIGHT_UNIT_LB, machine = false)
        assertEquals(WEIGHT_UNIT_LB to false, decodeWeightsExerciseDefault(raw))
    }

    @Test
    fun encodedValueIsCompactUnitThenType() {
        assertEquals("kg|machine", encodeWeightsExerciseDefault(WEIGHT_UNIT_KG, true))
        assertEquals("lb|free", encodeWeightsExerciseDefault(WEIGHT_UNIT_LB, false))
    }

    // ── Guards: a malformed/unknown value never picks a bogus bubble ──────
    // decode returns null so the dialog keeps its own fallback instead.

    @Test
    fun nullAndBlankDecodeToNull() {
        assertNull(decodeWeightsExerciseDefault(null))
        assertNull(decodeWeightsExerciseDefault(""))
        assertNull(decodeWeightsExerciseDefault("   "))
    }

    @Test
    fun wrongFieldCountDecodesToNull() {
        assertNull(decodeWeightsExerciseDefault("kg"))
        assertNull(decodeWeightsExerciseDefault("kg|machine|extra"))
        assertNull(decodeWeightsExerciseDefault("|"))
    }

    @Test
    fun unknownUnitDecodesToNull() {
        assertNull(decodeWeightsExerciseDefault("stones|machine"))
        assertNull(decodeWeightsExerciseDefault("|machine"))
    }

    @Test
    fun unknownTypeDecodesToNull() {
        assertNull(decodeWeightsExerciseDefault("kg|dumbbell"))
        assertNull(decodeWeightsExerciseDefault("kg|"))
    }

    @Test
    fun surroundingWhitespaceIsTolerated() {
        assertEquals(WEIGHT_UNIT_LB to false, decodeWeightsExerciseDefault(" lb | free "))
    }

    // ── Lookup semantics the dialog relies on ────────────────────────────
    // Exercises with no record must fall through to the dialog's default
    // (graph unit / edited set's type), never to a hard-coded value.

    @Test
    fun missingExerciseInMapYieldsNoDefault() {
        val defaults = mapOf("Bench Press" to encodeWeightsExerciseDefault(WEIGHT_UNIT_LB, true))
        assertNull(defaults["Squat"]?.let { decodeWeightsExerciseDefault(it) })
        assertEquals(WEIGHT_UNIT_LB to true, decodeWeightsExerciseDefault(defaults["Bench Press"]))
    }
}
