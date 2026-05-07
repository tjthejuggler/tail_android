package com.example.tail.data

import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale
import kotlin.coroutines.resume

private const val TAG = "LocationRepo"
private const val PREFS_NAME = "tail_location_prefs"
private const val KEY_LOCATIONS = "daily_locations"
/**
 * Map of date-string ("YYYY-MM-DD") → "lat,lon" string. Populated by the
 * Python seeder ([scripts/seed_locations_from_timeline.py]) and incrementally
 * by [fetchTodayIfNeeded] when it has just looked up the device's coords.
 * Used by the world-map screen to plot a person marker per day.
 */
private const val KEY_COORDS = "daily_coords"

/** Timeout for an active location request (millis). */
private const val ACTIVE_FIX_TIMEOUT_MS = 15_000L

/**
 * Fetches and persists the device's coarse location once per calendar day.
 * Stores a map of date-string → "City, Region, Country" in SharedPreferences.
 *
 * Uses Android's built-in LocationManager (no Google Play Services required)
 * and Geocoder for reverse-geocoding.
 *
 * When no cached location is available (common after overnight / reboot),
 * actively requests a single fresh fix from NETWORK_PROVIDER with a 15 s timeout.
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

    /** Returns (lat, lon) for [date] if known (from the seeder or a real-time fix). */
    fun getCoordsForDate(date: LocalDate): Pair<Double, Double>? {
        return parseCoordString(loadCoordsMap()[date.toString()])
    }

    /** Returns ALL stored daily coords as a map of date-string → (lat, lon). */
    fun getAllStoredCoords(): Map<String, Pair<Double, Double>> {
        return loadCoordsMap().mapNotNull { (date, str) ->
            parseCoordString(str)?.let { date to it }
        }.toMap()
    }

    /**
     * Fetches today's location if it hasn't been recorded yet.
     * Returns the location label or null on failure / permission denied.
     * Must be called with location permission already granted.
     *
     * Strategy:
     * 1. Check cached (last-known) locations from all providers.
     * 2. If none available, actively request a single fresh fix (15 s timeout).
     * 3. Reverse-geocode the result and persist both coords and label.
     */
    suspend fun fetchTodayIfNeeded(): String? {
        val today = LocalDate.now()
        val existing = getLocationForDate(today)
        if (existing != null) return existing

        return withContext(Dispatchers.IO) {
            try {
                // Try cached first, then fall back to an active request
                val coords = getBestLastKnownLocation()
                    ?: requestFreshLocation()
                    ?: return@withContext null

                Log.d(TAG, "Resolved coords for $today: ${coords.first}, ${coords.second}")
                // Persist the coords so the world-map screen can plot today.
                saveCoords(today, coords.first, coords.second)
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

    /** Manually saves (lat, lon) for [date]. Used by the seeder bridge / debug. */
    fun setCoordsForDate(date: LocalDate, lat: Double, lon: Double) {
        saveCoords(date, lat, lon)
    }

    /**
     * Forward-geocodes a human-readable location [label] to (lat, lon).
     * Uses Android's built-in [Geocoder] — no extra library or API key needed.
     * Returns null if the Geocoder is unavailable or no result is found.
     *
     * Called when the user manually enters a location name that has no stored
     * coords, so the world-map screen can still plot a marker for that day.
     */
    suspend fun geocodeLocationLabel(label: String): Pair<Double, Double>? {
        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder not present — cannot forward-geocode '$label'")
            return null
        }
        val geocoder = Geocoder(context, Locale.getDefault())
        return withContext(Dispatchers.IO) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    suspendCancellableCoroutine { cont ->
                        geocoder.getFromLocationName(label, 1) { addresses ->
                            val addr = addresses.firstOrNull()
                            cont.resume(if (addr != null) Pair(addr.latitude, addr.longitude) else null)
                        }
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocationName(label, 1)
                    val addr = addresses?.firstOrNull()
                    if (addr != null) Pair(addr.latitude, addr.longitude) else null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Forward-geocode failed for '$label': ${e.message}")
                null
            }
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /** Returns (lat, lon) from the best available last-known location, or null. */
    private fun getBestLastKnownLocation(): Pair<Double, Double>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        for (provider in providers) {
            try {
                @Suppress("MissingPermission")
                val loc = lm.getLastKnownLocation(provider)
                if (loc != null) {
                    Log.d(TAG, "Got cached location from $provider: ${loc.latitude}, ${loc.longitude}")
                    return Pair(loc.latitude, loc.longitude)
                }
            } catch (_: Exception) { /* provider not available */ }
        }
        Log.d(TAG, "No cached location from any provider — will request fresh fix")
        return null
    }

    /**
     * Actively requests a single location fix from NETWORK_PROVIDER (fast, coarse)
     * with a [ACTIVE_FIX_TIMEOUT_MS] timeout. Falls back to GPS_PROVIDER if
     * NETWORK_PROVIDER is unavailable.
     *
     * Returns (lat, lon) or null if no fix arrives in time.
     */
    private suspend fun requestFreshLocation(): Pair<Double, Double>? {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Prefer network (fast, coarse) then GPS (slower but works without cell)
        val provider = when {
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ->
                LocationManager.NETWORK_PROVIDER
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ->
                LocationManager.GPS_PROVIDER
            else -> {
                Log.w(TAG, "No location provider enabled")
                return null
            }
        }

        Log.d(TAG, "Requesting fresh location from $provider (timeout ${ACTIVE_FIX_TIMEOUT_MS}ms)")

        return withTimeoutOrNull(ACTIVE_FIX_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        Log.d(TAG, "Fresh fix from $provider: ${location.latitude}, ${location.longitude}")
                        lm.removeUpdates(this)
                        cont.resume(Pair(location.latitude, location.longitude))
                    }
                    @Deprecated("Deprecated in API")
                    override fun onStatusChanged(p: String?, s: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(p: String) {}
                    override fun onProviderDisabled(p: String) {}
                }

                cont.invokeOnCancellation {
                    lm.removeUpdates(listener)
                }

                try {
                    @Suppress("MissingPermission")
                    lm.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                } catch (e: Exception) {
                    Log.w(TAG, "requestSingleUpdate failed: ${e.message}")
                    cont.resume(null)
                }
            }
        }
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

    private fun loadCoordsMap(): Map<String, String> {
        val json = prefs.getString(KEY_COORDS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse coords map: ${e.message}")
            emptyMap()
        }
    }

    /** "lat,lon" → (lat, lon). Returns null on any parse failure. */
    private fun parseCoordString(value: String?): Pair<Double, Double>? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(",")
        if (parts.size != 2) return null
        return try {
            Pair(parts[0].trim().toDouble(), parts[1].trim().toDouble())
        } catch (_: NumberFormatException) {
            null
        }
    }

    private fun saveCoords(date: LocalDate, lat: Double, lon: Double) {
        val map = loadCoordsMap().toMutableMap()
        map[date.toString()] = "$lat,$lon"
        val obj = JSONObject(map as Map<*, *>)
        prefs.edit().putString(KEY_COORDS, obj.toString()).apply()
        Log.d(TAG, "Saved coords for $date: $lat, $lon")
    }
}
