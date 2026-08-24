package com.example.tail

import com.example.tail.data.GRAPH_METRIC_WEIGHTS_FREE_REPS
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_FREE_WEIGHT
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_MACHINE_REPS
import com.example.tail.data.GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT
import com.example.tail.data.WEIGHT_UNIT_KG
import com.example.tail.data.WEIGHT_UNIT_LB
import com.example.tail.data.formatWeightTenths
import com.example.tail.data.gramsToDisplayTenths
import com.example.tail.data.isWeightsFreeMetric
import com.example.tail.data.isWeightsWeightMetric
import com.example.tail.data.kgToGrams
import com.example.tail.data.lbToGrams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the weights-habit unit conversion helpers
 * (see HabitModels.kt — weights are stored in grams and converted to a
 * ×10-scaled display unit on the graph).
 */
class WeightsConversionTest {

    // ── Input → grams ─────────────────────────────────────────────────────

    @Test
    fun kgInputConvertsToGrams() {
        assertEquals(62500, kgToGrams(62.5))
        assertEquals(1250, kgToGrams(1.25))
        assertEquals(0, kgToGrams(0.0))
    }

    @Test
    fun lbInputConvertsToGrams() {
        // 1 lb = 453.59237 g exactly
        assertEquals(454, lbToGrams(1.0))
        // 100 lb plate math: 45359.237 g
        assertEquals(45359, lbToGrams(100.0))
        // 2.5 lb small plate → 1133.98 g
        assertEquals(1134, lbToGrams(2.5))
    }

    // ── Grams → display tenths ────────────────────────────────────────────

    @Test
    fun gramsToKgTenthsRoundsToNearest() {
        // 62.5 kg stored as 62500 g → 625 hectograms
        assertEquals(625, gramsToDisplayTenths(62500, WEIGHT_UNIT_KG))
        // 1.25 kg → 1250 g → 12.5 hg → rounds to 13 (round-to-nearest)
        assertEquals(13, gramsToDisplayTenths(1250, WEIGHT_UNIT_KG))
        assertEquals(12, gramsToDisplayTenths(1249, WEIGHT_UNIT_KG))
    }

    @Test
    fun gramsToLbTenths() {
        // 45359 g (100 lb) → 1000 tenths of lb
        assertEquals(1000, gramsToDisplayTenths(45359, WEIGHT_UNIT_LB))
        // 62500 g (62.5 kg) → 137.79 lb → 1378 tenths
        assertEquals(1378, gramsToDisplayTenths(62500, WEIGHT_UNIT_LB))
    }

    @Test
    fun kgAndLbRoundTripWithinOneTenth() {
        // A weight entered in lb, stored in grams, shown in lb must come back
        // to the same tenth of a pound.
        for (lbTenths in listOf(250, 550, 1000, 2250, 4500)) {
            val grams = lbToGrams(lbTenths / 10.0)
            assertEquals(lbTenths, gramsToDisplayTenths(grams, WEIGHT_UNIT_LB))
        }
        // Same for kg
        for (kgTenths in listOf(50, 125, 625, 1000, 1500)) {
            val grams = kgToGrams(kgTenths / 10.0)
            assertEquals(kgTenths, gramsToDisplayTenths(grams, WEIGHT_UNIT_KG))
        }
    }

    // ── Formatting ────────────────────────────────────────────────────────

    @Test
    fun tenthsFormatWithOneDecimal() {
        assertEquals("62.5", formatWeightTenths(625))
        assertEquals("137.8", formatWeightTenths(1378))
        assertEquals("0.0", formatWeightTenths(0))
        assertEquals("100.0", formatWeightTenths(1000))
    }

    // ── Metric classification ─────────────────────────────────────────────

    @Test
    fun weightMetricsAreWeightNotFree() {
        assertTrue(isWeightsWeightMetric(GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT))
        assertTrue(isWeightsWeightMetric(GRAPH_METRIC_WEIGHTS_FREE_WEIGHT))
        assertFalse(isWeightsWeightMetric(GRAPH_METRIC_WEIGHTS_MACHINE_REPS))
        assertFalse(isWeightsWeightMetric(GRAPH_METRIC_WEIGHTS_FREE_REPS))
        assertFalse(isWeightsWeightMetric("points"))
    }

    @Test
    fun freeMetricsAreMachineFreeAndFreeReps() {
        assertTrue(isWeightsFreeMetric(GRAPH_METRIC_WEIGHTS_FREE_WEIGHT))
        assertTrue(isWeightsFreeMetric(GRAPH_METRIC_WEIGHTS_FREE_REPS))
        assertFalse(isWeightsFreeMetric(GRAPH_METRIC_WEIGHTS_MACHINE_WEIGHT))
        assertFalse(isWeightsFreeMetric(GRAPH_METRIC_WEIGHTS_MACHINE_REPS))
    }
}
