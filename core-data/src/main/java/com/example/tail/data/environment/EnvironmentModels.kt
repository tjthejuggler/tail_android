package com.example.tail.data.environment

import org.json.JSONObject

/**
 * One day's environment snapshot — the "environment habit".
 *
 * Everything here is keyed by the day's recorded location (see
 * [com.example.tail.data.location.LocationRepository]) and captured from
 * free, keyless APIs:
 *
 *  · Weather (temp / humidity / precipitation / UV / pressure / wind)
 *    → Open-Meteo Forecast API (recent days) with automatic fallback to the
 *      Open-Meteo Archive API (older dates).
 *  · Air quality (EU AQI / PM2.5 / PM10 / ozone / NO2) + pollen
 *    → Open-Meteo Air Quality API (pollen only in Europe).
 *  · Geomagnetic activity (planetary K-index)
 *    → NOAA SWPC (rolling ~30 days).
 *
 *  · Tap-water hardness has no free global API, so it is entered manually
 *    per location and remembered (see EnvironmentRepository water memory).
 *
 * All metrics are nullable: a snapshot is still stored when some sources
 * fail, so partial data is never lost.
 */
data class EnvironmentSnapshot(
    /** ISO date string ("YYYY-MM-DD") this snapshot belongs to. */
    val date: String,
    /** Latitude the metrics were fetched for. */
    val lat: Double,
    /** Longitude the metrics were fetched for. */
    val lon: Double,
    /** Location label at capture time (informational; the day's label may change later). */
    val locationLabel: String = "",

    // ── Weather (°C, %, mm, hPa, km/h) ────────────────────────────────────
    val tempMinC: Double? = null,
    val tempMaxC: Double? = null,
    val tempMeanC: Double? = null,
    val humidityMean: Double? = null,
    val precipitationMm: Double? = null,
    val uvIndexMax: Double? = null,
    val pressureMeanHpa: Double? = null,
    val windMaxKmh: Double? = null,

    // ── Air quality ───────────────────────────────────────────────────────
    /** European AQI — daily maximum (higher = worse). */
    val aqiEuropeanMax: Int? = null,
    /** PM2.5 daily mean (µg/m³). */
    val pm25Mean: Double? = null,
    /** PM10 daily mean (µg/m³). */
    val pm10Mean: Double? = null,
    /** Ozone daily mean (µg/m³). */
    val ozoneMean: Double? = null,
    /** Nitrogen dioxide daily mean (µg/m³). */
    val no2Mean: Double? = null,

    // ── Pollen — daily max (grains/m³, Europe only) ───────────────────────
    val pollenGrassMax: Double? = null,
    val pollenBirchMax: Double? = null,
    val pollenAlderMax: Double? = null,
    val pollenMugwortMax: Double? = null,
    val pollenOliveMax: Double? = null,
    val pollenRagweedMax: Double? = null,

    // ── Space weather ─────────────────────────────────────────────────────
    /** Max planetary K-index for the day (0–9; ≥5 = geomagnetic storm). */
    val kpIndexMax: Double? = null,

    // ── Tap water ─────────────────────────────────────────────────────────
    /** Water hardness as mg/L CaCO₃ (ppm). Canonical storage unit. */
    val waterHardnessPpm: Double? = null,
    /** Where the hardness came from: "manual", "location-memory", or "". */
    val waterHardnessSource: String = "",

    /** ISO-8601 timestamp of the last (re)fetch from the network APIs. */
    val fetchedAt: String = ""
) {
    /** Serialises to one JSON object (stored in a prefs map keyed by date). */
    fun toJson(): String = JSONObject().apply {
        put("date", date)
        put("lat", lat)
        put("lon", lon)
        if (locationLabel.isNotEmpty()) put("label", locationLabel)
        tempMinC?.let { put("tMin", it) }
        tempMaxC?.let { put("tMax", it) }
        tempMeanC?.let { put("tMean", it) }
        humidityMean?.let { put("rh", it) }
        precipitationMm?.let { put("prcp", it) }
        uvIndexMax?.let { put("uv", it) }
        pressureMeanHpa?.let { put("pres", it) }
        windMaxKmh?.let { put("wind", it) }
        aqiEuropeanMax?.let { put("aqi", it) }
        pm25Mean?.let { put("pm25", it) }
        pm10Mean?.let { put("pm10", it) }
        ozoneMean?.let { put("o3", it) }
        no2Mean?.let { put("no2", it) }
        pollenGrassMax?.let { put("polGrass", it) }
        pollenBirchMax?.let { put("polBirch", it) }
        pollenAlderMax?.let { put("polAlder", it) }
        pollenMugwortMax?.let { put("polMugwort", it) }
        pollenOliveMax?.let { put("polOlive", it) }
        pollenRagweedMax?.let { put("polRagweed", it) }
        kpIndexMax?.let { put("kp", it) }
        waterHardnessPpm?.let { put("waterPpm", it) }
        if (waterHardnessSource.isNotEmpty()) put("waterSrc", waterHardnessSource)
        if (fetchedAt.isNotEmpty()) put("fetchedAt", fetchedAt)
    }.toString()

    /**
     * True when at least one metric field is present. Snapshots with NO
     * data (every source failed) must not be treated as complete captures —
     * otherwise the backlog would skip those days forever.
     */
    val hasAnyData: Boolean
        get() = tempMinC != null || tempMaxC != null || tempMeanC != null ||
            humidityMean != null || precipitationMm != null || uvIndexMax != null ||
            pressureMeanHpa != null || windMaxKmh != null || aqiEuropeanMax != null ||
            pm25Mean != null || pm10Mean != null || ozoneMean != null || no2Mean != null ||
            pollenGrassMax != null || pollenBirchMax != null || pollenAlderMax != null ||
            pollenMugwortMax != null || pollenOliveMax != null || pollenRagweedMax != null ||
            kpIndexMax != null || waterHardnessPpm != null

    companion object {
        /** Parses one snapshot; null when [text] is corrupt (data loss is better than a crash). */
        fun fromJson(text: String): EnvironmentSnapshot? = runCatching {
            val o = JSONObject(text)
            val d = optD(o)
            val s = EnvironmentSnapshot(
                date = o.getString("date"),
                lat = o.getDouble("lat"),
                lon = o.getDouble("lon"),
                locationLabel = o.optString("label", ""),
                tempMinC = d("tMin"), tempMaxC = d("tMax"), tempMeanC = d("tMean"),
                humidityMean = d("rh"), precipitationMm = d("prcp"),
                uvIndexMax = d("uv"), pressureMeanHpa = d("pres"), windMaxKmh = d("wind"),
                aqiEuropeanMax = if (o.has("aqi")) o.getInt("aqi") else null,
                pm25Mean = d("pm25"), pm10Mean = d("pm10"), ozoneMean = d("o3"), no2Mean = d("no2"),
                pollenGrassMax = d("polGrass"), pollenBirchMax = d("polBirch"),
                pollenAlderMax = d("polAlder"), pollenMugwortMax = d("polMugwort"),
                pollenOliveMax = d("polOlive"), pollenRagweedMax = d("polRagweed"),
                kpIndexMax = d("kp"),
                waterHardnessPpm = d("waterPpm"),
                waterHardnessSource = o.optString("waterSrc", ""),
                fetchedAt = o.optString("fetchedAt", "")
            )
            // Strip physically impossible zero-padded weather (min = max =
            // mean = 0 °C with 0 % humidity or 0 hPa pressure) that older
            // captures stored from the Forecast API. Nulled weather makes the
            // day "incomplete" so the backlog re-fetches the real values.
            if (s.tempMinC == 0.0 && s.tempMaxC == 0.0 && s.tempMeanC == 0.0 &&
                (s.humidityMean == 0.0 || s.pressureMeanHpa == 0.0)
            ) s.copy(
                tempMinC = null, tempMaxC = null, tempMeanC = null,
                humidityMean = null, precipitationMm = null, uvIndexMax = null,
                pressureMeanHpa = null, windMaxKmh = null
            ) else s
        }.getOrNull()

        private fun optD(o: JSONObject) = { key: String ->
            if (o.has(key) && !o.isNull(key)) o.getDouble(key) else null
        }
    }
}

// ── Classification helpers (pure functions, unit-tested) ────────────────────

/** USGS hardness bands: Soft < 60, Moderately hard 60–120, Hard 120–180, Very hard > 180 ppm. */
fun waterHardnessClass(ppm: Double): String = when {
    ppm < 60 -> "Soft"
    ppm < 120 -> "Moderately hard"
    ppm <= 180 -> "Hard"
    else -> "Very hard"
}

/** mg/L CaCO₃ → German degrees (°dH). 1 °dH = 17.848 ppm. */
fun ppmToGermanDegrees(ppm: Double): Double = ppm / 17.848

/** German degrees (°dH) → mg/L CaCO₃. */
fun germanDegreesToPpm(dh: Double): Double = dh * 17.848

/** European AQI band label (0–20 Good, 20–40 Fair, 40–60 Moderate, 60–80 Poor, 80–100 Very poor, >100 Extremely poor). */
fun europeanAqiClass(aqi: Int): String = when {
    aqi <= 20 -> "Good"
    aqi <= 40 -> "Fair"
    aqi <= 60 -> "Moderate"
    aqi <= 80 -> "Poor"
    aqi <= 100 -> "Very poor"
    else -> "Extremely poor"
}

/** WHO UV-index band label. */
fun uvIndexClass(uv: Double): String = when {
    uv < 3 -> "Low"
    uv < 6 -> "Moderate"
    uv < 8 -> "High"
    uv < 11 -> "Very high"
    else -> "Extreme"
}

/** Geomagnetic activity label for a planetary K-index value. */
fun kpIndexClass(kp: Double): String = when {
    kp < 3 -> "Quiet"
    kp < 5 -> "Active"
    else -> "Storm"
}

// ── Metric registry ─────────────────────────────────────────────────────────

/**
 * The environment metrics a habit can be linked to (the "environment habit
 * type"). Following the Garmin-link model, a linked habit's daily stored
 * value IS the metric value at ×10 precision (215 = 21.5 °C) — the same
 * storage trick as FITNESS_AGE (×100) and IMDb ratings (×10). Full
 * decimal precision is preserved in the habits DB, so graphs plot the
 * real value once display sites divide by [scale]. Edit-mode/points
 * displays round to the nearest whole number.
 */
enum class EnvironmentMetric(
    val key: String,
    val label: String,
    val unit: String,
    val extract: (EnvironmentSnapshot) -> Double?,
    /** Display unit depends on the temperature setting; °F uses tenths too. */
    val isTemperature: Boolean = false
) {
    TEMP_MAX("temp_max", "Temperature max", "°", { it.tempMaxC }, isTemperature = true),
    TEMP_MIN("temp_min", "Temperature min", "°", { it.tempMinC }, isTemperature = true),
    TEMP_MEAN("temp_mean", "Temperature mean", "°", { it.tempMeanC }, isTemperature = true),
    HUMIDITY("humidity", "Humidity", "%", { it.humidityMean }),
    PRECIPITATION("precipitation", "Precipitation", "mm", { it.precipitationMm }),
    UV_MAX("uv_max", "UV index max", "", { it.uvIndexMax }),
    PRESSURE("pressure", "Pressure", "hPa", { it.pressureMeanHpa }),
    WIND_MAX("wind_max", "Wind max", "km/h", { it.windMaxKmh }),
    AQI_MAX("aqi_max", "EU AQI (max)", "", { it.aqiEuropeanMax?.toDouble() }),
    PM25("pm25", "PM2.5", "µg/m³", { it.pm25Mean }),
    PM10("pm10", "PM10", "µg/m³", { it.pm10Mean }),
    OZONE("ozone", "Ozone", "µg/m³", { it.ozoneMean }),
    NO2("no2", "NO₂", "µg/m³", { it.no2Mean }),
    POLLEN_GRASS("pollen_grass", "Grass pollen", "gr/m³", { it.pollenGrassMax }),
    POLLEN_BIRCH("pollen_birch", "Birch pollen", "gr/m³", { it.pollenBirchMax }),
    POLLEN_ALDER("pollen_alder", "Alder pollen", "gr/m³", { it.pollenAlderMax }),
    POLLEN_MUGWORT("pollen_mugwort", "Mugwort pollen", "gr/m³", { it.pollenMugwortMax }),
    POLLEN_OLIVE("pollen_olive", "Olive pollen", "gr/m³", { it.pollenOliveMax }),
    POLLEN_RAGWEED("pollen_ragweed", "Ragweed pollen", "gr/m³", { it.pollenRagweedMax }),
    KP_MAX("kp_max", "Kp index (max)", "", { it.kpIndexMax }),
    WATER_HARDNESS("water_hardness", "Water hardness", "ppm", { it.waterHardnessPpm });

    /**
     * The day's value ×10 as stored in the habits DB (215 = 21.5 °C),
     * converted to the display unit first when [useFahrenheit] and this is
     * a temperature metric. Null = no data.
     */
    fun scaledValue(snapshot: EnvironmentSnapshot, useFahrenheit: Boolean = false): Int? {
        val celsius = extract(snapshot) ?: return null
        val v = if (useFahrenheit && isTemperature) celsius * 9.0 / 5.0 + 32.0 else celsius
        return Math.round(v * 10.0).toInt()
    }

    /** Formats a stored ×10 Int for graph display ("21.5 °C"). */
    fun formatTenths(scaled: Int, useFahrenheit: Boolean = false): String {
        val unitLabel = if (isTemperature) (if (useFahrenheit) "°F" else "°C") else unit
        return "%.1f $unitLabel".format(scaled / 10.0)
    }

    /** Rounds a stored ×10 Int to the nearest whole number (edit/points). */
    fun rounded(scaled: Int): Int = Math.round(scaled / 10.0).toInt()

    companion object {
        fun fromKey(key: String?): EnvironmentMetric? =
            entries.firstOrNull { it.key == key }
    }
}
