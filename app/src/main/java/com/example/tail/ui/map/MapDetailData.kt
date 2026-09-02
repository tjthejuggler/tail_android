package com.example.tail.ui.map

import android.content.Context
import android.util.Log
import org.json.JSONArray

private const val TAG = "MapDetailData"

/**
 * Zoom-dependent detail layers for the world-map screen:
 *  - [loadBorders110]     faint country border lines (visible at every zoom level)
 *  - [loadLand50]         higher-resolution land polygons (swapped in when zoomed)
 *  - [loadBorders50]      higher-resolution country borders (visible when zoomed)
 *  - [loadCities]         populated places ([lon, lat, popRank, name])
 *  - [loadCountryLabels]  country label placemarks ([lon, lat, areaRank, name])
 *
 * All files live in app/src/main/assets and share the same compact JSON
 * encoding as [WorldLandData]: arrays of [lon, lat] pairs (polylines /
 * polygon rings), or flat point arrays for label sets. Each set is parsed
 * once on a background thread and cached for the life of the process.
 */
object MapDetailData {

    /**
     * A labelled map point: city or country-label placemark.
     * Country entries additionally carry their bbox size ([wDeg]/[hDeg] in
     * degrees, 0 for cities) used to gate when a name fits inside the
     * country on screen.
     */
    data class LabelPoint(
        val lon: Double,
        val lat: Double,
        val rank: Int,
        val name: String,
        val wDeg: Double = 0.0,
        val hDeg: Double = 0.0
    )

    @Volatile private var borders110Cache: List<List<Pair<Double, Double>>>? = null
    @Volatile private var land50Cache: List<List<Pair<Double, Double>>>? = null
    @Volatile private var borders50Cache: List<List<Pair<Double, Double>>>? = null
    @Volatile private var citiesCache: List<LabelPoint>? = null
    @Volatile private var countryLabelsCache: List<LabelPoint>? = null

    fun loadBorders110(context: Context): List<List<Pair<Double, Double>>> {
        borders110Cache?.let { return it }
        return synchronized(this) {
            borders110Cache ?: parseRings(context, "world_borders_110m.json")
                .also { borders110Cache = it }
        }
    }

    fun loadLand50(context: Context): List<List<Pair<Double, Double>>> {
        land50Cache?.let { return it }
        return synchronized(this) {
            land50Cache ?: parseRings(context, "world_land_50m.json").also { land50Cache = it }
        }
    }

    fun loadBorders50(context: Context): List<List<Pair<Double, Double>>> {
        borders50Cache?.let { return it }
        return synchronized(this) {
            borders50Cache ?: parseRings(context, "world_borders_50m.json")
                .also { borders50Cache = it }
        }
    }

    fun loadCities(context: Context): List<LabelPoint> {
        citiesCache?.let { return it }
        return synchronized(this) {
            citiesCache ?: parsePoints(context, "world_cities.json").also { citiesCache = it }
        }
    }

    /**
     * Country label placemarks: [lon, lat, areaRank, name] where areaRank is
     * 0 (huge countries) … 4 (small countries) — used to show big-country
     * names earlier while zooming than small-country names.
     */
    fun loadCountryLabels(context: Context): List<LabelPoint> {
        countryLabelsCache?.let { return it }
        return synchronized(this) {
            countryLabelsCache ?: parsePoints(context, "world_country_labels.json")
                .also { countryLabelsCache = it }
        }
    }

    private fun parseRings(context: Context, assetPath: String): List<List<Pair<Double, Double>>> {
        return try {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val outer = JSONArray(text)
            val out = ArrayList<List<Pair<Double, Double>>>(outer.length())
            for (i in 0 until outer.length()) {
                val ring = outer.getJSONArray(i)
                val pts = ArrayList<Pair<Double, Double>>(ring.length())
                for (j in 0 until ring.length()) {
                    val pt = ring.getJSONArray(j)
                    pts.add(Pair(pt.getDouble(0), pt.getDouble(1)))
                }
                out.add(pts)
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $assetPath: ${e.message}")
            emptyList()
        }
    }

    private fun parsePoints(context: Context, assetPath: String): List<LabelPoint> {
        return try {
            val text = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val outer = JSONArray(text)
            val out = ArrayList<LabelPoint>(outer.length())
            for (i in 0 until outer.length()) {
                val entry = outer.getJSONArray(i)
                out.add(
                    if (entry.length() >= 6) LabelPoint(
                        lon = entry.getDouble(0),
                        lat = entry.getDouble(1),
                        rank = entry.getInt(2),
                        name = entry.getString(5),
                        wDeg = entry.getDouble(3),
                        hDeg = entry.getDouble(4)
                    ) else LabelPoint(
                        lon = entry.getDouble(0),
                        lat = entry.getDouble(1),
                        rank = entry.getInt(2),
                        name = entry.getString(3)
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $assetPath: ${e.message}")
            emptyList()
        }
    }
}
