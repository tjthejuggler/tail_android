package com.example.tail

import com.example.tail.data.environment.EnvironmentSnapshot
import com.example.tail.data.environment.europeanAqiClass
import com.example.tail.data.environment.EnvironmentMetric
import com.example.tail.data.environment.germanDegreesToPpm
import com.example.tail.data.environment.kpIndexClass
import com.example.tail.data.environment.ppmToGermanDegrees
import com.example.tail.data.environment.uvIndexClass
import com.example.tail.data.environment.waterHardnessClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for the environment habit: snapshot JSON round-trip,
 * classification helpers and unit conversions.
 */
class EnvironmentModelsTest {

    // ── JSON round-trip ─────────────────────────────────────────────────────

    @Test
    fun `snapshot json round trip preserves all fields`() {
        val snap = EnvironmentSnapshot(
            date = "2026-09-23",
            lat = 45.07,
            lon = 7.68,
            locationLabel = "Turin, Piedmont, Italy",
            tempMinC = 13.2, tempMaxC = 24.7, tempMeanC = 18.9,
            humidityMean = 61.0, precipitationMm = 0.4,
            uvIndexMax = 6.5, pressureMeanHpa = 1013.2, windMaxKmh = 14.8,
            aqiEuropeanMax = 24, pm25Mean = 8.1, pm10Mean = 14.2,
            ozoneMean = 64.0, no2Mean = 11.5,
            pollenGrassMax = 3.0, pollenBirchMax = null,
            kpIndexMax = 3.33,
            waterHardnessPpm = 280.0,
            waterHardnessSource = "manual",
            fetchedAt = "2026-09-23T10:00:00Z"
        )

        val parsed = EnvironmentSnapshot.fromJson(snap.toJson())

        assertEquals(snap, parsed)
    }

    @Test
    fun `snapshot json with only required fields parses to null metrics`() {
        val minimal = """{"date":"2026-01-01","lat":10.0,"lon":20.0}"""
        val parsed = EnvironmentSnapshot.fromJson(minimal)

        assertEquals("2026-01-01", parsed!!.date)
        assertEquals(10.0, parsed.lat, 0.0)
        assertEquals(20.0, parsed.lon, 0.0)
        assertNull(parsed.tempMeanC)
        assertNull(parsed.aqiEuropeanMax)
        assertNull(parsed.kpIndexMax)
        assertNull(parsed.waterHardnessPpm)
        assertEquals("", parsed.fetchedAt)
    }

    @Test
    fun `corrupt json parses to null instead of crashing`() {
        assertNull(EnvironmentSnapshot.fromJson("not json {"))
        assertNull(EnvironmentSnapshot.fromJson("""{"date":"2026-01-01"}""")) // missing lat/lon
    }

    // ── Water hardness classification (USGS bands) ──────────────────────────

    @Test
    fun `water hardness bands`() {
        assertEquals("Soft", waterHardnessClass(0.0))
        assertEquals("Soft", waterHardnessClass(59.9))
        assertEquals("Moderately hard", waterHardnessClass(60.0))
        assertEquals("Moderately hard", waterHardnessClass(119.9))
        assertEquals("Hard", waterHardnessClass(120.0))
        assertEquals("Hard", waterHardnessClass(180.0))
        assertEquals("Very hard", waterHardnessClass(180.1))
        assertEquals("Very hard", waterHardnessClass(400.0))
    }

    // ── Unit conversions ────────────────────────────────────────────────────

    @Test
    fun `ppm to german degrees round trips`() {
        assertEquals(1.0, ppmToGermanDegrees(17.848), 0.001)
        assertEquals(17.848, germanDegreesToPpm(1.0), 0.001)
        // Round-trip
        val ppm = 280.0
        assertEquals(ppm, germanDegreesToPpm(ppmToGermanDegrees(ppm)), 0.0001)
    }

    // ── EU AQI bands ────────────────────────────────────────────────────────

    @Test
    fun `european aqi bands`() {
        assertEquals("Good", europeanAqiClass(0))
        assertEquals("Good", europeanAqiClass(20))
        assertEquals("Fair", europeanAqiClass(40))
        assertEquals("Moderate", europeanAqiClass(60))
        assertEquals("Poor", europeanAqiClass(80))
        assertEquals("Very poor", europeanAqiClass(100))
        assertEquals("Extremely poor", europeanAqiClass(150))
    }

    // ── UV bands ────────────────────────────────────────────────────────────

    @Test
    fun `uv index bands`() {
        assertEquals("Low", uvIndexClass(2.9))
        assertEquals("Moderate", uvIndexClass(3.0))
        assertEquals("High", uvIndexClass(6.0))
        assertEquals("Very high", uvIndexClass(8.0))
        assertEquals("Extreme", uvIndexClass(11.0))
    }

    // ── Kp bands ────────────────────────────────────────────────────────────

    @Test
    fun `kp index bands`() {
        assertEquals("Quiet", kpIndexClass(0.0))
        assertEquals("Quiet", kpIndexClass(2.9))
        assertEquals("Active", kpIndexClass(3.0))
        assertEquals("Active", kpIndexClass(4.9))
        assertEquals("Storm", kpIndexClass(5.0))
        assertEquals("Storm", kpIndexClass(9.0))
    }

    // ── Metric registry (habit-type links) ──────────────────────────────────

    @Test
    fun `metric fromKey round trips for every entry`() {
        for (metric in EnvironmentMetric.entries) {
            assertEquals(metric, EnvironmentMetric.fromKey(metric.key))
        }
        assertNull(EnvironmentMetric.fromKey("nope"))
        assertNull(EnvironmentMetric.fromKey(null))
    }

    @Test
    fun `metric values store x10 and round only at display`() {
        val snap = EnvironmentSnapshot(
            date = "2026-09-23", lat = 0.0, lon = 0.0,
            tempMaxC = 21.5, tempMeanC = 21.44,
            humidityMean = 67.6,
            kpIndexMax = 3.33, waterHardnessPpm = 280.0
        )
        // Storage: ×10 — 21.5 °C → 215, 21.44 → 214 (decimals preserved)
        assertEquals(215, EnvironmentMetric.TEMP_MAX.scaledValue(snap))
        assertEquals(214, EnvironmentMetric.TEMP_MEAN.scaledValue(snap))
        assertEquals(676, EnvironmentMetric.HUMIDITY.scaledValue(snap))
        assertEquals(33, EnvironmentMetric.KP_MAX.scaledValue(snap))
        assertEquals(2800, EnvironmentMetric.WATER_HARDNESS.scaledValue(snap))
        // Fahrenheit conversion happens before scaling: 21.5 °C = 70.7 °F
        assertEquals(707, EnvironmentMetric.TEMP_MAX.scaledValue(snap, useFahrenheit = true))
        // Graph formatting divides by 10
        assertEquals("21.5 °C", EnvironmentMetric.TEMP_MAX.formatTenths(215))
        assertEquals("70.7 °F", EnvironmentMetric.TEMP_MAX.formatTenths(707, useFahrenheit = true))
        assertEquals("280.0 ppm", EnvironmentMetric.WATER_HARDNESS.formatTenths(2800))
        // Edit-mode/points display rounds to the nearest whole number
        assertEquals(22, EnvironmentMetric.TEMP_MAX.rounded(215))
        assertEquals(21, EnvironmentMetric.TEMP_MEAN.rounded(214))
        assertEquals(68, EnvironmentMetric.HUMIDITY.rounded(676))
        assertEquals(3, EnvironmentMetric.KP_MAX.rounded(33))
        assertEquals(280, EnvironmentMetric.WATER_HARDNESS.rounded(2800))
    }

    @Test
    fun `metric extract returns null for missing data`() {
        val empty = EnvironmentSnapshot(date = "2026-09-23", lat = 0.0, lon = 0.0)
        assertNull(EnvironmentMetric.TEMP_MAX.scaledValue(empty))
        assertNull(EnvironmentMetric.POLLEN_BIRCH.scaledValue(empty))
    }
}
