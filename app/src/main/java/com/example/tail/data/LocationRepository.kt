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
/** JSON array of user-managed country/region names to exclude from the country count. */
private const val KEY_IGNORED_COUNTRIES = "ignored_country_names"
/** Boolean flag: true after US state names have been seeded into the ignore list. */
private const val KEY_IGNORED_COUNTRIES_SEEDED = "ignored_country_names_seeded"
/**
 * Map of date-string ("YYYY-MM-DD") → "lat,lon" string. Populated by the
 * Python seeder ([scripts/seed_locations_from_timeline.py]) and incrementally
 * by [fetchTodayIfNeeded] when it has just looked up the device's coords.
 * Used by the world-map screen to plot a person marker per day.
 */
private const val KEY_COORDS = "daily_coords"
/**
 * The 0-based index of the candidate the user last chose from the auto-generated list.
 * Stored as an Int so that on the next day we re-run generateLocationCandidates with
 * fresh GPS data and pick the same positional slot — not the same literal string.
 */
private const val KEY_PREFERRED_AUTO_CANDIDATE_INDEX = "preferred_auto_candidate_index"
/**
 * Map of date-string ("YYYY-MM-DD") → JSON array of secondary location objects.
 * Each object has: {"lat": double, "lon": double, "label": string}.
 * Secondary locations are logged each time the app is opened and represent
 * places visited throughout the day beyond the main daily location.
 * Only unique labels are stored per day (duplicates are skipped).
 */
private const val KEY_SECONDARY_LOCATIONS = "secondary_locations"

/** Timeout for an active location request (millis). */
private const val ACTIVE_FIX_TIMEOUT_MS = 15_000L

/**
 * Two secondary samples (or a secondary vs. the day's primary) are considered
 * "the same place" when they are within this many metres. Anything closer is
 * deduped on capture so opening the app multiple times from the same coffee
 * shop / home doesn't fill the day with near-identical pings.
 *
 * 250 m is a city-block-ish radius — big enough to absorb GPS noise on a
 * coarse network fix, small enough that crossing the street to a different
 * neighbourhood still registers.
 */
private const val SECONDARY_DEDUP_METERS = 250.0

/**
 * Great-circle distance between two lat/lon pairs in metres
 * (haversine formula). Used to dedup secondary location pings that may
 * reverse-geocode to the same coarse label even though they're not the
 * same physical place.
 */
internal fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0  // Earth radius in metres
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = kotlin.math.sin(dLat / 2).let { it * it } +
        kotlin.math.cos(Math.toRadians(lat1)) *
        kotlin.math.cos(Math.toRadians(lat2)) *
        kotlin.math.sin(dLon / 2).let { it * it }
    val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
    return r * c
}

/**
 * US state names (properly capitalised) seeded into the ignored-country list
 * on first run so the user can see and optionally remove them.
 */
private val US_STATE_NAMES = listOf(
    "Alabama", "Alaska", "Arizona", "Arkansas", "California", "Colorado",
    "Connecticut", "Delaware", "District of Columbia", "Florida", "Georgia",
    "Hawaii", "Idaho", "Illinois", "Indiana", "Iowa", "Kansas", "Kentucky",
    "Louisiana", "Maine", "Maryland", "Massachusetts", "Michigan", "Minnesota",
    "Mississippi", "Missouri", "Montana", "Nebraska", "Nevada", "New Hampshire",
    "New Jersey", "New Mexico", "New York", "North Carolina", "North Dakota",
    "Ohio", "Oklahoma", "Oregon", "Pennsylvania", "Rhode Island",
    "South Carolina", "South Dakota", "Tennessee", "Texas", "Utah", "Vermont",
    "Virginia", "Washington", "West Virginia", "Wisconsin", "Wyoming"
)

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

    init {
        seedDefaultIgnoredCountriesIfNeeded()
    }

    /**
     * One-time seed: adds US state names to the ignored-country list so they
     * appear in the UI and the user can remove them if desired. Sets a flag so
     * this only runs once — subsequent removals by the user are respected.
     */
    private fun seedDefaultIgnoredCountriesIfNeeded() {
        if (prefs.getBoolean(KEY_IGNORED_COUNTRIES_SEEDED, false)) return
        val existing = loadIgnoredCountries().toMutableSet()
        existing.addAll(US_STATE_NAMES)
        saveIgnoredCountries(existing)
        prefs.edit().putBoolean(KEY_IGNORED_COUNTRIES_SEEDED, true).apply()
        Log.d(TAG, "Seeded ${US_STATE_NAMES.size} US state names into ignored countries")
    }

    /** Returns the stored location label for [date], or null if not yet recorded. */
    fun getLocationForDate(date: LocalDate): String? {
        return loadMap()[date.toString()]
    }

    /** Manually saves a location label for [date] (used for manual edits). */
    fun setLocationForDate(date: LocalDate, label: String) {
        saveLocation(date, label)
    }

    /** Removes both the location label and coords for [date], making it act as if never set. */
    fun removeLocationForDate(date: LocalDate) {
        removeLocation(date)
        removeCoords(date)
    }

    /** Returns all previously stored location labels (deduplicated, sorted). */
    fun getAllStoredLocations(): List<String> {
        return loadMap().values.distinct().sorted()
    }

    /**
     * Saves the 0-based index of the candidate the user chose from the auto list.
     * On the next day, [fetchTodayIfNeeded] will re-run [generateLocationCandidates]
     * with fresh GPS data and pick the candidate at this index.
     */
    fun savePreferredAutoCandidateIndex(index: Int) {
        prefs.edit().putInt(KEY_PREFERRED_AUTO_CANDIDATE_INDEX, index).apply()
        Log.d(TAG, "Saved preferred auto candidate index: $index")
    }

    /**
     * Returns the saved preferred candidate index, or -1 if none has been set.
     */
    fun getPreferredAutoCandidateIndex(): Int {
        return prefs.getInt(KEY_PREFERRED_AUTO_CANDIDATE_INDEX, -1)
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
     * Returns the entire date-string → label map in ONE SharedPrefs read +
     * ONE JSON parse pass. Used by the world-map screen to build its country
     * cache without re-parsing per-date (which would freeze the UI thread).
     */
    fun getAllStoredLabels(): Map<String, String> = loadMap()

    /**
     * Monotonically increasing counter, bumped each time a location label or
     * coords entry is added/changed. Lets observers (e.g. the map screen
     * cache) cheaply detect "data changed since I built my cache".
     */
    @Volatile
    var dataVersion: Int = 0
        private set

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

    /**
     * Actively requests a fresh GPS/network fix, reverse-geocodes it, and
     * saves both the label and coords for [date]. Returns the label or null.
     * Unlike [fetchTodayIfNeeded], this always requests a fresh fix even if a
     * location is already stored for [date].
     */
    suspend fun fetchFreshLocationForDate(date: LocalDate): String? {
        return withContext(Dispatchers.IO) {
            try {
                val coords = getBestLastKnownLocation()
                    ?: requestFreshLocation()
                    ?: return@withContext null

                Log.d(TAG, "Fresh fix for $date: ${coords.first}, ${coords.second}")
                saveCoords(date, coords.first, coords.second)
                val label = reverseGeocode(coords.first, coords.second)
                if (label != null) {
                    saveLocation(date, label)
                }
                label
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch fresh location: ${e.message}")
                null
            }
        }
    }

    /** Manually saves (lat, lon) for [date]. Used by the seeder bridge / debug. */
    fun setCoordsForDate(date: LocalDate, lat: Double, lon: Double) {
        saveCoords(date, lat, lon)
    }

    /**
     * Generates multiple candidate location names for the given coordinates.
     * Each candidate is a different way of formatting the address from the
     * geocoder results, allowing the user to cycle through options.
     *
     * Returns a deduplicated list ordered from most specific to least specific.
     * The GPS coordinates are NOT changed — only the display label varies.
     */
    suspend fun generateLocationCandidates(lat: Double, lon: Double): List<String> {
        val candidates = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // ── Source 1: Android Geocoder (structured fields) ──────────────
        if (Geocoder.isPresent()) {
            val roundedLat = (Math.round(lat * 1000.0)) / 1000.0
            val roundedLon = (Math.round(lon * 1000.0)) / 1000.0
            val geocoder = Geocoder(context, Locale.getDefault())
            val maxResults = 5

            val addresses = fetchGeocoderAddresses(geocoder, roundedLat, roundedLon, maxResults)

            for (addr in addresses) {
                for (candidate in buildPlaceCandidates(addr)) {
                    if (seen.add(candidate)) candidates.add(candidate)
                }
                // Also extract candidates from the Address's formatted address lines
                // which often contain the town name even when individual fields are null
                for (candidate in buildAddressLineCandidates(addr)) {
                    if (seen.add(candidate)) candidates.add(candidate)
                }
            }

            // Also try with raw (unrounded) coords
            if (roundedLat != lat || roundedLon != lon) {
                val rawAddresses = fetchGeocoderAddresses(geocoder, lat, lon, maxResults)
                for (addr in rawAddresses) {
                    for (candidate in buildPlaceCandidates(addr)) {
                        if (seen.add(candidate)) candidates.add(candidate)
                    }
                    for (candidate in buildAddressLineCandidates(addr)) {
                        if (seen.add(candidate)) candidates.add(candidate)
                    }
                }
            }
        }

        // ── Source 2: Nominatim (OpenStreetMap) fallback ─────────────────
        // The Android Geocoder often misses small towns; Nominatim has much
        // better coverage for places like Halesworth, Lowestoft, etc.
        try {
            val nominatimCandidates = fetchNominatimCandidates(lat, lon)
            for (candidate in nominatimCandidates) {
                if (seen.add(candidate)) candidates.add(candidate)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Nominatim fallback failed: ${e.message}")
        }

        Log.d(TAG, "Generated ${candidates.size} location candidates for $lat, $lon: $candidates")
        return candidates
    }

    /**
     * Fetches addresses from the Android Geocoder, handling both the
     * TIRAMISU+ async API and the legacy synchronous API.
     */
    private suspend fun fetchGeocoderAddresses(
        geocoder: Geocoder,
        lat: Double,
        lon: Double,
        maxResults: Int
    ): List<android.location.Address> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(lat, lon, maxResults) { addrs ->
                    cont.resume(addrs)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(lat, lon, maxResults) ?: emptyList()
        }
    }

    /**
     * Extracts candidate names from the Address's formatted address lines.
     * The Android Geocoder often puts the town name in an address line even
     * when the structured locality/featureName fields are null or wrong.
     * E.g. address line "Angel Yd, Halesworth IP19" → extract "Halesworth".
     */
    private fun buildAddressLineCandidates(addr: android.location.Address): List<String> {
        val candidates = mutableListOf<String>()
        val country = addr.countryName?.takeIf { it.isNotBlank() }
        val region = addr.adminArea?.takeIf { it.isNotBlank() }

        // Collect all meaningful tokens from address lines
        val townNames = mutableSetOf<String>()
        for (i in 0 until addr.maxAddressLineIndex.coerceAtMost(3)) {
            val line = addr.getAddressLine(i) ?: continue
            // Split by commas and clean up each part
            line.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
                // Skip parts that are just postcodes (e.g. "IP19"), numbers, or very short
                if (part.length > 3 && looksLikePlaceName(part) && !part.matches(Regex("^[A-Z]{1,2}\\d.*"))) {
                    townNames.add(part)
                }
            }
        }

        for (town in townNames) {
            // "Town, Region, Country"
            val full = listOfNotNull(town, region, country).joinToString(", ")
            if (full.isNotBlank()) candidates.add(full)

            // "Town, Country"
            if (region != null && country != null) {
                val noRegion = listOfNotNull(town, country).joinToString(", ")
                if (noRegion != full && noRegion.isNotBlank()) candidates.add(noRegion)
            }
        }

        return candidates.distinct()
    }

    /**
     * Queries the Nominatim (OpenStreetMap) reverse-geocoding API.
     * Returns candidate location names extracted from the JSON response.
     * Nominatim has excellent coverage for small towns worldwide.
     */
    private suspend fun fetchNominatimCandidates(lat: Double, lon: Double): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val urlStr = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lon&zoom=10&addressdetails=1"
                val conn = java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "TailHabitTracker/1.0")
                conn.connectTimeout = 8000
                conn.readTimeout = 8000

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    Log.w(TAG, "Nominatim returned HTTP $responseCode")
                    return@withContext emptyList()
                }

                val json = conn.inputStream.bufferedReader().readText()
                conn.disconnect()

                val jsonObj = org.json.JSONObject(json)
                val address = jsonObj.optJSONObject("address") ?: return@withContext emptyList()
                val candidates = mutableListOf<String>()
                val seen = mutableSetOf<String>()

                // Extract town/city/village/hamlet — Nominatim uses these keys for settlements
                val settlementKeys = listOf("city", "town", "village", "hamlet", "suburb", "municipality")
                val settlement = settlementKeys.firstNotNullOfOrNull { key ->
                    address.optString(key)?.takeIf { it.isNotBlank() }
                }

                val county = address.optString("county")?.takeIf { it.isNotBlank() }
                val state = address.optString("state")?.takeIf { it.isNotBlank() }
                val countryName = address.optString("country")?.takeIf { it.isNotBlank() }

                if (settlement != null) {
                    // "Settlement, State, Country"
                    val full = listOfNotNull(settlement, state, countryName).joinToString(", ")
                    if (seen.add(full)) candidates.add(full)

                    // "Settlement, Country"
                    if (state != null && countryName != null) {
                        val noState = listOfNotNull(settlement, countryName).joinToString(", ")
                        if (seen.add(noState)) candidates.add(noState)
                    }

                    // "Settlement, County, Country"
                    if (county != null && county != state) {
                        val withCounty = listOfNotNull(settlement, county, countryName).joinToString(", ")
                        if (seen.add(withCounty)) candidates.add(withCounty)
                    }
                }

                // "County, State, Country" as broader option
                if (county != null) {
                    val countyFull = listOfNotNull(county, state, countryName).joinToString(", ")
                    if (seen.add(countyFull)) candidates.add(countyFull)
                }

                // "State, Country"
                if (state != null && countryName != null) {
                    val stateFull = listOf(state, countryName).joinToString(", ")
                    if (seen.add(stateFull)) candidates.add(stateFull)
                }

                // Also try the display_name from Nominatim — extract first few parts
                val displayName = jsonObj.optString("name")?.takeIf { it.isNotBlank() && looksLikePlaceName(it) }
                if (displayName != null && displayName != settlement) {
                    val nameFull = listOfNotNull(displayName, state, countryName).joinToString(", ")
                    if (seen.add(nameFull)) candidates.add(nameFull)
                }

                candidates
            } catch (e: Exception) {
                Log.w(TAG, "Nominatim request failed: ${e.message}")
                emptyList()
            }
        }
    }

    /**
     * Builds a list of candidate names from a single Address at different
     * specificity levels: "Place, Region, Country", "Place, Country",
     * "Region, Country", "Country", etc.
     */
    private fun buildPlaceCandidates(addr: android.location.Address): List<String> {
        val candidates = mutableListOf<String>()
        val country = addr.countryName?.takeIf { it.isNotBlank() }
        val region = addr.adminArea?.takeIf { it.isNotBlank() }
        val subAdmin = addr.subAdminArea?.takeIf { it.isNotBlank() }
        val locality = addr.locality?.takeIf { it.isNotBlank() }
        val subLocality = addr.subLocality?.takeIf { it.isNotBlank() }
        val feature = addr.featureName?.takeIf { it.isNotBlank() && looksLikePlaceName(it) }

        // Determine the best "place" name (most specific to least)
        val placeOptions = listOfNotNull(locality, subLocality, feature, subAdmin)
            .distinct()

        for (place in placeOptions) {
            // "Place, Region, Country"
            val full = listOfNotNull(place, region, country).joinToString(", ")
            if (full.isNotBlank()) candidates.add(full)

            // "Place, Country" (skip region)
            if (region != null && country != null) {
                val noRegion = listOfNotNull(place, country).joinToString(", ")
                if (noRegion != full && noRegion.isNotBlank()) candidates.add(noRegion)
            }
        }

        // "Region, Country" as a broader option
        if (region != null && country != null) {
            val regionFull = listOf(region, country).joinToString(", ")
            if (regionFull !in candidates) candidates.add(regionFull)
        }

        // "Country" only as last resort
        if (country != null && country !in candidates) {
            candidates.add(country)
        }

        return candidates.distinct()
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

    // ── Ignored country names ────────────────────────────────────────────────

    /**
     * Returns the current set of country/region names that should be excluded
     * from the "countries visited" count (e.g. US state names that still slip
     * through, or any other false-positive entries).
     */
    fun getIgnoredCountryNames(): Set<String> = loadIgnoredCountries()

    /** Adds [name] to the ignored-country set and persists it. */
    fun addIgnoredCountryName(name: String) {
        val set = loadIgnoredCountries().toMutableSet()
        if (set.add(name.trim())) {
            saveIgnoredCountries(set)
            dataVersion++
        }
    }

    /** Removes [name] from the ignored-country set and persists it. */
    fun removeIgnoredCountryName(name: String) {
        val set = loadIgnoredCountries().toMutableSet()
        if (set.remove(name.trim())) {
            saveIgnoredCountries(set)
            dataVersion++
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
     *
     * To avoid micro-locations like "Angel Yd" appearing instead of the
     * actual town, we:
     * 1. Round coordinates to 3 decimal places (~111 m grid) to smooth
     *    out GPS jitter that can land on a courtyard or building.
     * 2. Request up to 5 results and scan them for the one with the
     *    best locality-level place name.
     * 3. Format as "Place, Region, Country".
     */
    private suspend fun reverseGeocode(lat: Double, lon: Double): String? {
        // If the user has a preferred candidate index, re-run the full candidate
        // generation with the fresh coords and pick the same positional slot.
        // This ensures we use today's actual location data, not yesterday's name.
        val preferredIndex = getPreferredAutoCandidateIndex()
        if (preferredIndex >= 0) {
            val candidates = generateLocationCandidates(lat, lon)
            if (candidates.isNotEmpty()) {
                val picked = candidates.getOrElse(preferredIndex) { candidates[0] }
                Log.d(TAG, "Using preferred candidate index $preferredIndex → \"$picked\" (${candidates.size} candidates)")
                return picked
            }
        }

        if (!Geocoder.isPresent()) {
            Log.w(TAG, "Geocoder not present on this device")
            return null
        }
        // Round to 3 decimal places (~111 m) to avoid micro-locations
        val roundedLat = (Math.round(lat * 1000.0)) / 1000.0
        val roundedLon = (Math.round(lon * 1000.0)) / 1000.0
        Log.d(TAG, "Reverse-geocoding rounded coords: $roundedLat, $roundedLon (raw: $lat, $lon)")

        val geocoder = Geocoder(context, Locale.getDefault())
        val maxResults = 5

        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            suspendCancellableCoroutine { cont ->
                geocoder.getFromLocation(roundedLat, roundedLon, maxResults) { addrs ->
                    cont.resume(addrs)
                }
            }
        } else {
            @Suppress("DEPRECATION")
            geocoder.getFromLocation(roundedLat, roundedLon, maxResults) ?: emptyList()
        }

        return pickBestAddress(addresses)
    }

    /**
     * Scans a list of Address results and picks the one with the most
     * specific town/city-level place name, skipping micro-locations
     * (building names, courtyards, etc.).
     *
     * Strategy:
     * 1. If any result has `locality`, prefer that (it's the postal town).
     * 2. If no result has `locality`, fall back to `subLocality` or
     *    `featureName` — but only if it looks like a real place name.
     * 3. Last resort: `subAdminArea` (county-level).
     */
    private fun pickBestAddress(addresses: List<android.location.Address>): String? {
        if (addresses.isEmpty()) return null

        // First pass: look for a result with locality set
        for (addr in addresses) {
            val locality = addr.locality?.takeIf { it.isNotBlank() && looksLikePlaceName(it) }
            if (locality != null) {
                return formatPlaceRegionCountry(locality, addr)
            }
        }

        // Second pass: look for subLocality or featureName that looks like a place
        for (addr in addresses) {
            val place = listOfNotNull(
                addr.subLocality?.takeIf { it.isNotBlank() && looksLikePlaceName(it) },
                addr.featureName?.takeIf { it.isNotBlank() && looksLikePlaceName(it) }
            ).firstOrNull()
            if (place != null) {
                return formatPlaceRegionCountry(place, addr)
            }
        }

        // Last resort: use the first result's subAdminArea
        val fallback = addresses.firstOrNull()
        val place = fallback?.subAdminArea?.takeIf { it.isNotBlank() }
            ?: fallback?.adminArea?.takeIf { it.isNotBlank() }
        if (place != null && fallback != null) {
            return formatPlaceRegionCountry(place, fallback)
        }

        return formatAddress(addresses.firstOrNull())
    }

    /** Heuristic: reject micro-locations like "Angel Yd", "12", "Unit 3". */
    private fun looksLikePlaceName(name: String): Boolean {
        if (name.length <= 2) return false
        if (name.all { it.isDigit() }) return false
        // Reject names that look like building/unit identifiers
        val lower = name.lowercase()
        val microPrefixes = listOf("unit ", "flat ", "suite ", "apt ", "room ")
        if (microPrefixes.any { lower.startsWith(it) }) return false
        return true
    }

    /**
     * Formats a known place name with the region and country from an Address.
     * Skips the region if it's the same as the place (e.g. "Suffolk, Suffolk").
     */
    private fun formatPlaceRegionCountry(
        place: String,
        addr: android.location.Address
    ): String {
        val region = addr.adminArea?.takeIf { it.isNotBlank() && it != place }
        val country = addr.countryName?.takeIf { it.isNotBlank() }
        return listOfNotNull(place, region, country).joinToString(", ")
    }

    /**
     * Formats an Address into the most specific "Place, Region, Country" string.
     * Fallback used when pickBestAddress doesn't find a good result.
     * Priority for the "place" part: subLocality > locality > featureName > subAdminArea > adminArea.
     */
    private fun formatAddress(address: android.location.Address?): String? {
        if (address == null) return null

        val place = listOfNotNull(
            address.subLocality?.takeIf { it.isNotBlank() },
            address.locality?.takeIf { it.isNotBlank() },
            address.featureName?.takeIf { it.isNotBlank() },
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
        dataVersion++
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
        dataVersion++
        Log.d(TAG, "Saved coords for $date: $lat, $lon")
    }

    private fun removeLocation(date: LocalDate) {
        val map = loadMap().toMutableMap()
        if (map.remove(date.toString()) != null) {
            val obj = JSONObject(map as Map<*, *>)
            prefs.edit().putString(KEY_LOCATIONS, obj.toString()).apply()
            dataVersion++
            Log.d(TAG, "Removed location for $date")
        }
    }

    private fun removeCoords(date: LocalDate) {
        val map = loadCoordsMap().toMutableMap()
        if (map.remove(date.toString()) != null) {
            val obj = JSONObject(map as Map<*, *>)
            prefs.edit().putString(KEY_COORDS, obj.toString()).apply()
            dataVersion++
            Log.d(TAG, "Removed coords for $date")
        }
    }

    private fun loadIgnoredCountries(): Set<String> {
        val json = prefs.getString(KEY_IGNORED_COUNTRIES, null) ?: return emptySet()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }.toSet()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ignored countries: ${e.message}")
            emptySet()
        }
    }

    private fun saveIgnoredCountries(set: Set<String>) {
        val arr = org.json.JSONArray(set.toList())
        prefs.edit().putString(KEY_IGNORED_COUNTRIES, arr.toString()).apply()
        Log.d(TAG, "Saved ignored countries: $set")
    }

    // ── Secondary locations ──────────────────────────────────────────────────

    /**
     * Returns the list of secondary locations recorded for [date].
     * Each entry has GPS coords and a label. Returns empty list if none stored.
     */
    fun getSecondaryLocationsForDate(date: LocalDate): List<SecondaryLocation> {
        val map = loadSecondaryMap()
        val arr = map[date.toString()] ?: return emptyList()
        return parseSecondaryArray(arr)
    }

    /**
     * Returns ALL stored secondary locations as a map of date-string → list.
     * Used by the world-map screen to plot secondary dots in one read.
     */
    fun getAllSecondaryLocations(): Map<String, List<SecondaryLocation>> {
        val map = loadSecondaryMap()
        return map.mapValues { (_, arr) -> parseSecondaryArray(arr) }
    }

    /**
     * Logs a secondary location for today. Stores it unless we already have a
     * sample at substantially the same physical location (same label OR within
     * [SECONDARY_DEDUP_METERS] of today's primary coords or of any existing
     * secondary), so opening the app multiple times from the same place
     * doesn't fill the day with near-identical pings, but any real movement
     * (e.g. drove across town, same coarse city label) IS captured.
     *
     * The label is derived from the preferred auto candidate index, falling
     * back to a simple reverse-geocode if no preferred index is set.
     */
    suspend fun logSecondaryLocation(lat: Double, lon: Double, label: String, timeMinutes: Int = java.time.LocalTime.now().toSecondOfDay() / 60) {
        val today = LocalDate.now()
        val map = loadSecondaryMap().toMutableMap()
        val existingArr = map[today.toString()]
        val existing = parseSecondaryArray(existingArr ?: "[]")

        // 1. Skip if exact same label already exists for today.
        if (existing.any { it.label == label }) {
            Log.d(TAG, "Secondary location '$label' already exists for $today — skipping")
            return
        }

        // 2. Skip if within SECONDARY_DEDUP_METERS of today's PRIMARY coords —
        //    this means the user just opened the app from "home" / the place
        //    they woke up in; that's already captured as the primary.
        val primaryCoords = getCoordsForDate(today)
        if (primaryCoords != null &&
            haversineMeters(lat, lon, primaryCoords.first, primaryCoords.second) < SECONDARY_DEDUP_METERS
        ) {
            Log.d(TAG, "Secondary location ($lat,$lon) within ${SECONDARY_DEDUP_METERS}m of primary — skipping")
            return
        }

        // 3. Skip if within SECONDARY_DEDUP_METERS of an existing secondary
        //    for today (no point logging two pings from the same coffee shop).
        if (existing.any { haversineMeters(lat, lon, it.lat, it.lon) < SECONDARY_DEDUP_METERS }) {
            Log.d(TAG, "Secondary location ($lat,$lon) within ${SECONDARY_DEDUP_METERS}m of an existing secondary — skipping")
            return
        }

        val newEntry = org.json.JSONObject().apply {
            put("lat", lat)
            put("lon", lon)
            put("label", label)
            put("time", timeMinutes)
        }
        val combined = org.json.JSONArray(existingArr ?: "[]")
        combined.put(newEntry)
        map[today.toString()] = combined.toString()
        prefs.edit().putString(KEY_SECONDARY_LOCATIONS, JSONObject(map as Map<*, *>).toString()).apply()
        dataVersion++
        Log.d(TAG, "Logged secondary location for $today: $label ($lat, $lon) at ${timeMinutes}min")
    }

    /**
     * Fetches the current GPS position and logs it as a secondary location
     * for today. Uses the preferred auto candidate index to derive the label.
     * Returns the label used, or null on failure.
     *
     * Prefers a FRESH fix over the cached last-known location — secondary
     * logging exists specifically to detect movement, and the cached fix is
     * often the morning primary, which would just be deduped against itself.
     * Falls back to last-known only if the active request times out.
     */
    suspend fun logCurrentPositionAsSecondary(): String? {
        return withContext(Dispatchers.IO) {
            try {
                val coords = requestFreshLocation()
                    ?: getBestLastKnownLocation()
                    ?: return@withContext null

                val label = deriveSecondaryLabel(coords.first, coords.second) ?: return@withContext null
                logSecondaryLocation(coords.first, coords.second, label)
                label
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log secondary location: ${e.message}")
                null
            }
        }
    }

    /**
     * Manually adds a secondary location for [date] by forward-geocoding
     * a human-readable address string (e.g. pasted from Google Maps).
     * Returns the resolved label, or null if geocoding fails.
     */
    suspend fun addManualSecondaryLocation(date: LocalDate, address: String, timeMinutes: Int = java.time.LocalTime.now().toSecondOfDay() / 60): String? {
        val coords = geocodeLocationLabel(address) ?: return null
        val label = deriveSecondaryLabel(coords.first, coords.second) ?: address
        val map = loadSecondaryMap().toMutableMap()
        val existingArr = map[date.toString()]
        val existing = parseSecondaryArray(existingArr ?: "[]")

        // Skip if this label already exists for this date OR a sample within
        // SECONDARY_DEDUP_METERS already exists (same physical place).
        if (existing.any { it.label == label }) {
            Log.d(TAG, "Manual secondary location '$label' already exists for $date — skipping")
            return label
        }
        if (existing.any { haversineMeters(coords.first, coords.second, it.lat, it.lon) < SECONDARY_DEDUP_METERS }) {
            Log.d(TAG, "Manual secondary location (${coords.first},${coords.second}) within ${SECONDARY_DEDUP_METERS}m of existing — skipping")
            return label
        }

        val newEntry = org.json.JSONObject().apply {
            put("lat", coords.first)
            put("lon", coords.second)
            put("label", label)
            put("time", timeMinutes)
        }
        val combined = org.json.JSONArray(existingArr ?: "[]")
        combined.put(newEntry)
        map[date.toString()] = combined.toString()
        prefs.edit().putString(KEY_SECONDARY_LOCATIONS, JSONObject(map as Map<*, *>).toString()).apply()
        dataVersion++
        Log.d(TAG, "Added manual secondary location for $date: $label (${coords.first}, ${coords.second}) at ${timeMinutes}min")
        return label
    }

    /**
     * Derives a label for a secondary location using the preferred auto
     * candidate index (same method as the main daily location). Falls back
     * to a simple reverse-geocode if no preferred index is set.
     */
    private suspend fun deriveSecondaryLabel(lat: Double, lon: Double): String? {
        val preferredIndex = getPreferredAutoCandidateIndex()
        if (preferredIndex >= 0) {
            val candidates = generateLocationCandidates(lat, lon)
            if (candidates.isNotEmpty()) {
                return candidates.getOrElse(preferredIndex) { candidates[0] }
            }
        }
        return reverseGeocode(lat, lon)
    }

    /** Parses a JSON array string into a list of SecondaryLocation objects. */
    private fun parseSecondaryArray(jsonArrStr: String): List<SecondaryLocation> {
        if (jsonArrStr.isBlank() || jsonArrStr == "[]") return emptyList()
        return try {
            val arr = org.json.JSONArray(jsonArrStr)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val lat = obj.optDouble("lat", Double.NaN)
                val lon = obj.optDouble("lon", Double.NaN)
                val label = obj.optString("label", "")
                // Backward compatible: default to 0 (midnight) if time field missing
                val timeMinutes = obj.optInt("time", 0)
                if (!lat.isNaN() && !lon.isNaN() && label.isNotBlank()) {
                    SecondaryLocation(lat, lon, label, timeMinutes)
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse secondary locations: ${e.message}")
            emptyList()
        }
    }

    /** Loads the date-string → JSON-array-string map from SharedPrefs. */
    private fun loadSecondaryMap(): Map<String, String> {
        val json = prefs.getString(KEY_SECONDARY_LOCATIONS, null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse secondary locations map: ${e.message}")
            emptyMap()
        }
    }
}

/**
 * A secondary location logged when the app is opened throughout the day.
 * Unlike the main daily location (one per day), secondary locations capture
 * multiple positions as the user moves around.
 *
 * @param lat Latitude
 * @param lon Longitude
 * @param label Human-readable place name (from auto-candidate or manual entry)
 * @param timeMinutes Minutes since midnight (0–1439). Used by the analog clock
 *   on the map screen to show when each location was visited.
 */
data class SecondaryLocation(
    val lat: Double,
    val lon: Double,
    val label: String,
    val timeMinutes: Int = 0
)
