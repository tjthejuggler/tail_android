package com.example.tail.data

import android.content.Context
import android.location.Geocoder
import android.location.LocationManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "LocationRepo"
private const val PREFS_NAME = "tail_location_prefs"
private const val KEY_LOCATIONS = "daily_locations"

/**
 * Fetches and persists the device's coarse location once per calendar day.
 * Stores a map of date-string → "City, Region, Country" in SharedPreferences.
 *
 * Uses Android's built-in LocationManager (no Google Play Services required)
 * and Geocoder for reverse-geocoding.
 */
class LocationRepository(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /** Returns the stored location label for [date], or null if not yet recorded. */
    fun getLocationForDate(date: LocalDate): String? {
        return loadMap()[date.toString()]
    }

    /** Manually saves a location label for [date] (used for manual edits). */
    fun setLocationForDate(date: LocalDate, label: String) {
        saveLocation(date, label)
    }

    /** Returns all previously stored location labels (deduplicated, sorted). */
    fun getAllStoredLocations(): List<String> {
        return loadMap().values.distinct().sorted()
    }

    /**
     * Fetches today's location if it hasn't been recorded yet.
     * Returns the location label or null on failure / permission denied.
     * Must be called with location permission already granted.
     */
    suspend fun fetchTodayIfNeeded(): String? {
        val today = LocalDate.now()
        val existing = getLocationForDate(today)
        if (existing != null) return existing

        return withContext(Dispatchers.IO) {
            try {
                val coords = getBestLastKnownLocation() ?: return@withContext null
                val label = reverseGeocode(coords.first, coords.second)
                if (label != null) {
                    saveLocation(today, label)
                }
                label
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch location: ${e.message}")
                null
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Returns (lat, lon) from the best available last-known location, or null. */
    private fun getBestLastKnownLocation(): Pair<Double, Double>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (provider in providers) {
            try {
                @Suppress("MissingPermission")
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) {
                    Log.d(TAG, "Got location from $provider: ${loc.latitude}, ${loc.longitude}")
                    return Pair(loc.latitude, loc.longitude)
                }
            } catch (_: Exception) { /* provider not available */ }
        }
        return null
    }

    /**
     * Reverse-geocodes (lat, lon) to a human-readable string.
     * Tries to include the most specific place name available:
     * subLocality → locality → subAdminArea → adminArea, plus countryName.
     */
    private suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder not present on this device")
            return null
        }
        val geocoder = Geocoder(context, Locale.getDefault())

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, 1) { addresses ->
                    cont.resume(formatAddress(addresses.firstOrNull()))
                }
            }
        } else {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            formatAddress(addresses?.firstOrNull())
        }
    }

    /**
     * Formats an Address into the most specific "Place, Region, Country" string.
     * Priority for the "place" part: subLocality > locality > subAdminArea > adminArea.
     */
    private fun formatAddress(address: android.location.Address?): String? {
        if (address == null) return null

        // Pick the most specific city/town/neighbourhood name available
        val place = listOfNotNull(
            address.subLocality?.takeIf { it.isNotBlank() },
            address.locality?.takeIf { it.isNotBlank() },
            address.subAdminArea?.takeIf { it.isNotBlank() }
        ).firstOrNull()

        val region = address.adminArea?.takeIf { it.isNotBlank() }
        val country = address.countryName?.takeIf { it.isNotBlank() }

        val parts = listOfNotNull(place, region, country)
        return parts.joinToString(", ").takeIf { it.isNotBlank() }
    }

    private fun loadMap(): Map<String, String> {
        val json = prefs.getString(KEY_LOCATIONS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse location map: ${e.message}")
            emptyMap()
        }
    }

    private fun saveLocation(date: LocalDate, label: String) {
        val map = loadMap().toMutableMap()
        map[date.toString()] = label
        val obj = JSONObject(map as Map<*, *>)
        prefs.edit().putString(KEY_LOCATIONS, obj.toString()).apply()
        Log.d(TAG, "Saved location for $date: $label")
    }
}
