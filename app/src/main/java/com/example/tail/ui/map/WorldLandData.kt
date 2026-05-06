package com.example.tail.ui.map

import android.content.Context
import android.util.Log
import org.json.JSONArray

private const val TAG = "WorldLandData"
private const val ASSET_PATH = "world_land.json"

/**
 * Lazily-loaded simplified world-land polygon data used by the world-map
 * screen.  The data lives in [app/src/main/assets/world_land.json] and is a
 * top-level JSON array of polygons, where each polygon is a JSON array of
 * [lon, lat] pairs (Natural Earth ne_110m_land, simplified).
 *
 * The Compose Canvas renderer treats every entry as a filled polygon (no
 * holes — at this resolution they're not visible).
 */
object WorldLandData {

    /** A single polygon ring as a list of (lon, lat) pairs. */
    private var cached: List<List<Pair<Double, Double>>>? = null

    /**
     * Loads + caches the polygon set.  Returns an empty list if the asset is
     * missing or malformed (the map will still render — just without land).
     */
    fun load(context: Context): List<List<Pair<Double, Double>>> {
        cached?.let { return it }
        val parsed = parseAsset(context)
        cached = parsed
        return parsed
    }

    private fun parseAsset(context: Context): List<List<Pair<Double, Double>>> {
        return try {
            val text = context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
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
            Log.w(TAG, "Failed to load $ASSET_PATH: ${e.message}")
            emptyList()
        }
    }
}
