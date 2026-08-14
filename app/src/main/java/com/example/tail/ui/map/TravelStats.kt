package com.example.tail.ui.map

import com.example.tail.data.SecondaryLocation
import com.example.tail.data.haversineMeters
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure computation layer for the Travel Stats screen (see [com.example.tail.ui.MapStatsScreen]).
 *
 * All functions here are side-effect free and UI-independent so they can be
 * unit-tested and run on [kotlinx.coroutines.Dispatchers.Default] without
 * touching SharedPrefs or Compose.
 */

// ── Place extraction ──────────────────────────────────────────────────────────

/**
 * Extracts the city (most specific part) from a "City, Region, Country"
 * location label. Returns null when the label has fewer than two parts
 * (a bare country name carries no city information).
 */
fun extractCityFromLabel(label: String): String? {
    val parts = label.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    return if (parts.size >= 2) parts.first() else null
}

/**
 * Country-name aliases canonicalised before ANY counting, so variants like
 * "USA" and "United States" aggregate into a single entry everywhere.
 * Keys are lowercase; add more pairs here as label sources vary.
 */
private val COUNTRY_ALIASES: Map<String, String> = mapOf(
    "usa" to "United States",
    "us" to "United States",
    "u.s." to "United States",
    "u.s.a." to "United States",
    "united states of america" to "United States"
)

/** Canonical form of a country name, e.g. "USA" → "United States". */
fun canonicalCountryName(name: String): String =
    COUNTRY_ALIASES[name.trim().lowercase()] ?: name.trim()

/**
 * Extracts the country (last part) from a "City, Region, Country" label,
 * canonicalising aliases first and returning null when the country is in
 * the user-managed [ignoredNames] exclusion list (case-insensitive,
 * checked against BOTH the raw and canonical spellings so canonicalisation
 * can never un-ignore a country).
 */
fun extractCountryFromLabel(label: String, ignoredNames: Set<String>): String? {
    val parts = label.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.isEmpty()) return null
    val raw = parts.last()
    val country = canonicalCountryName(raw)
    if (ignoredNames.any { it.equals(raw, ignoreCase = true) || it.equals(country, ignoreCase = true) }) return null
    return country
}

// ── Continent classification ──────────────────────────────────────────────────

/**
 * Pragmatic bounding-box continent classifier for a (lat, lon) pair.
 *
 * Ordered checks, first match wins:
 *  1. Antarctica  — everything below −60° latitude.
 *  2. Europe      −25..40 lon, 36..72 lat (Iceland included at lon ≥ −25).
 *  3. Africa      −37..37 lat, −20..52 lon (after Europe so the Maghreb's
 *                  thin Mediterranean strip above 36° falls to Europe first).
 *  4. Oceania     112..180 lon, −50..0 lat (Australia, NZ, PNG, Fiji).
 *  5. North America −170..−50 lon, lat ≥ 13 — or lat ≥ 7 west of −77
 *                  (so Central America down to Panama is NA while Colombia /
 *                  Venezuela, which sit east of −77, fall through to SA).
 *  6. South America −85..−32 lon, −60..13 lat.
 *  7. Asia        25..180 lon, −12..82 lat (everything left over).
 *  8. "Other"     — open ocean / unclassifiable islands.
 *
 * Edge cases (transcontinental Turkey, far-eastern Indonesia) resolve to one
 * side deterministically, which is fine for aggregate statistics.
 */
fun continentForCoord(lat: Double, lon: Double): String = when {
    lat < -60.0 -> "Antarctica"
    lon in -25.0..40.0 && lat in 36.0..72.0 -> "Europe"
    lat in -37.0..37.0 && lon in -20.0..52.0 -> "Africa"
    lon in 112.0..180.0 && lat in -50.0..0.0 -> "Oceania"
    lon in -170.0..-50.0 && (lat >= 13.0 || (lat >= 7.0 && lon <= -77.0)) -> "North America"
    lon in -85.0..-32.0 && lat in -60.0..13.0 -> "South America"
    lon in 25.0..180.0 && lat in -12.0..82.0 -> "Asia"
    else -> "Other"
}

// ── Result models ─────────────────────────────────────────────────────────────

/** A single displacement between two consecutive tracked days. */
data class HopStat(
    val km: Double,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val toPlace: String?
)

/**
 * One bucket on a period chart. Carries the full detail lists behind each
 * bar so tapping it can show exactly which places/values it aggregates.
 */
data class PeriodStat(
    /** Display label, e.g. "24", "Aug 24" or "14/8". */
    val label: String,
    /** Chronological sort key (epoch day / year*12+month−1 / year). */
    val sortKey: Int,
    /** Countries first visited in this period, with their first-visit date. */
    val countryFirsts: List<Pair<String, LocalDate>> = emptyList(),
    /** Cities first visited in this period, with their first-visit date. */
    val cityFirsts: List<Pair<String, LocalDate>> = emptyList(),
    /** Continents first reached in this period, with their first-visit date. */
    val continentFirsts: List<Pair<String, LocalDate>> = emptyList(),
    /** Displacements whose arrival day falls in this period, biggest first. */
    val hops: List<HopStat> = emptyList(),
    val distanceKm: Double = 0.0
) {
    val newCountries: Int get() = countryFirsts.size
    val newCities: Int get() = cityFirsts.size
    val newContinents: Int get() = continentFirsts.size
}

/** A named place with the number of days spent there. */
data class PlaceDays(
    val name: String,
    val days: Int,
    val firstVisit: LocalDate,
    val lastVisit: LocalDate
)

/** A lat/lon extreme (northern-/southernmost point reached). */
data class CoordsExtreme(
    val lat: Double,
    val lon: Double,
    val date: LocalDate,
    val place: String?
)

/** Full aggregate computed by [computeTravelStats]. */
data class TravelStatsData(
    val daysTracked: Int,
    val firstDate: LocalDate?,
    val lastDate: LocalDate?,
    val totalCountries: Int,
    val totalCities: Int,
    val totalContinents: Int,
    val uniquePlaces: Int,
    val totalDistanceKm: Double,
    val secondaryPings: Int,
    /** Daily buckets (sortKey = epoch day) — powers the 1M range. */
    val daily: List<PeriodStat>,
    val monthly: List<PeriodStat>,
    val yearly: List<PeriodStat>,
    val topCountries: List<PlaceDays>,
    val topCities: List<PlaceDays>,
    val continentDays: List<PlaceDays>,
    val biggestHop: HopStat?,
    val longestStayDays: Int,
    val longestStayPlace: String?,
    val northernmost: CoordsExtreme?,
    val southernmost: CoordsExtreme?
) {
    val hasData: Boolean get() = daysTracked > 0 || totalCountries > 0
}

// ── Computation ───────────────────────────────────────────────────────────────

private val MONTH_ABBR = arrayOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)

/** Finest known label for a day: the city when the label has one, else the country. */
private fun finestPlace(city: String?, country: String?): String? = city ?: country

private fun monthKey(d: LocalDate): Int = d.year * 12 + (d.monthValue - 1)

/**
 * Computes every travel statistic shown on the Travel Stats screen in one
 * pass over the daily labels + coords snapshots.
 *
 * @param labelsByDate date → "City, Region, Country" label (primary location)
 * @param coordsByDate date → (lat, lon) (primary coords; drives distance and
 *        continent classification)
 * @param secondariesByDate date-string → extra places logged that day
 * @param ignoredCountries user-managed country exclusion list
 */
fun computeTravelStats(
    labelsByDate: Map<LocalDate, String>,
    coordsByDate: Map<LocalDate, Pair<Double, Double>>,
    secondariesByDate: Map<String, List<SecondaryLocation>>,
    ignoredCountries: Set<String>
): TravelStatsData {
    // ── Per-day resolved place parts ──────────────────────────────────────
    // Days with no record of their own inherit the last known place — the
    // same "assumed (*)" model the map screen uses for its top-bar label.
    // The user confirms assumed days are trustworthy, so they count toward
    // days-spent, longest-stay and continent stats. Inheritance only kicks
    // in when the day has NO data for that field: a label whose country is
    // on the ignore list must not inherit some other country.
    data class DayPlace(val city: String?, val country: String?, val continent: String?)

    val rawDates = (labelsByDate.keys + coordsByDate.keys).sorted()
    val placeByDate = LinkedHashMap<LocalDate, DayPlace>()
    val spanStart = rawDates.firstOrNull()
    // Fill forward to today at the earliest — the map screen treats days
    // after the last record as "assumed" and they are counted here too.
    val spanEnd = rawDates.lastOrNull()?.let { maxOf(it, LocalDate.now()) }
    spanStart?.let { start ->
        spanEnd?.let { end ->
            var lastCity: String? = null
            var lastCountry: String? = null
            var lastContinent: String? = null
            var d = start
            while (d <= end) {
                val label = labelsByDate[d]
                val coords = coordsByDate[d]
                val ownCity = label?.let { extractCityFromLabel(it) }
                val ownCountry = label?.let { extractCountryFromLabel(it, ignoredCountries) }
                val ownContinent = coords?.let { continentForCoord(it.first, it.second) }
                ownCity?.let { lastCity = it }
                ownCountry?.let { lastCountry = it }
                ownContinent?.let { lastContinent = it }
                val city = ownCity ?: if (label == null) lastCity else null
                val country = ownCountry ?: if (label == null) lastCountry else null
                val continent = ownContinent ?: if (coords == null) lastContinent else null
                placeByDate[d] = DayPlace(city, country, continent)
                d = d.plusDays(1)
            }
        }
    }
    val allDates = placeByDate.keys.toList()

    // ── First/last visits + days spent ────────────────────────────────────
    val countryFirst = HashMap<String, LocalDate>()
    val cityFirst = HashMap<String, LocalDate>()
    val continentFirst = HashMap<String, LocalDate>()
    val countryLast = HashMap<String, LocalDate>()
    val cityLast = HashMap<String, LocalDate>()
    val countryDays = HashMap<String, Int>()
    val cityDays = HashMap<String, Int>()
    val continentDays = HashMap<String, Int>()
    val uniquePlaces = HashSet<String>()

    for ((d, p) in placeByDate) {
        p.country?.let {
            countryFirst.putIfAbsent(it, d)
            countryLast[it] = d
            countryDays.merge(it, 1, Int::plus)
        }
        p.city?.let {
            cityFirst.putIfAbsent(it, d)
            cityLast[it] = d
            cityDays.merge(it, 1, Int::plus)
        }
        p.continent?.let {
            continentFirst.putIfAbsent(it, d)
            continentDays.merge(it, 1, Int::plus)
        }
        // A "place" is the finest label we have for the day (city if known,
        // else country) — used for the unique-places stat.
        finestPlace(p.city, p.country)?.let { uniquePlaces.add("$it|${p.country ?: ""}") }
    }

    // ── Distance between consecutive tracked days ─────────────────────────
    // Each hop is bucketed under its ARRIVAL day for all three granularities
    // so the tap-popup can list the biggest hops of any period.
    val coordDates = coordsByDate.keys.sorted()
    var totalKm = 0.0
    var biggestHop: HopStat? = null
    val kmByDay = HashMap<Int, Double>()
    val kmByMonth = HashMap<Int, Double>()
    val kmByYear = HashMap<Int, Double>()
    val hopsByDay = HashMap<Int, MutableList<HopStat>>()
    val hopsByMonth = HashMap<Int, MutableList<HopStat>>()
    val hopsByYear = HashMap<Int, MutableList<HopStat>>()
    for (i in 1 until coordDates.size) {
        val a = coordsByDate[coordDates[i - 1]] ?: continue
        val b = coordsByDate[coordDates[i]] ?: continue
        val km = haversineMeters(a.first, a.second, b.first, b.second) / 1000.0
        if (km <= 0.0) continue
        val to = coordDates[i]
        totalKm += km
        val hop = HopStat(km, coordDates[i - 1], to, placeByDate[to]?.let { finestPlace(it.city, it.country) })
        kmByDay.merge(to.toEpochDay().toInt(), km, Double::plus)
        kmByMonth.merge(monthKey(to), km, Double::plus)
        kmByYear.merge(to.year, km, Double::plus)
        hopsByDay.getOrPut(to.toEpochDay().toInt()) { mutableListOf() }.add(hop)
        hopsByMonth.getOrPut(monthKey(to)) { mutableListOf() }.add(hop)
        hopsByYear.getOrPut(to.year) { mutableListOf() }.add(hop)
        if (km > (biggestHop?.km ?: 0.0)) biggestHop = hop
    }

    // ── Period series (zero-filled buckets, details attached) ─────────────
    val firstCandidates = buildList {
        addAll(countryFirst.values); addAll(cityFirst.values)
        addAll(continentFirst.values); addAll(coordDates)
        spanStart?.let { add(it) }
    }
    val lastCandidates = buildList {
        addAll(countryLast.values); addAll(cityLast.values)
        addAll(continentFirst.values); addAll(coordDates)
        spanEnd?.let { add(it) }
    }
    val firstDate = firstCandidates.minOrNull()
    val lastDate = lastCandidates.maxOrNull()

    fun buildSeries(
        keys: List<Int>,
        keyOfDate: (LocalDate) -> Int,
        labelOfKey: (Int) -> String,
        kmByKey: Map<Int, Double>,
        hopsByKey: Map<Int, List<HopStat>>
    ): List<PeriodStat> {
        val byKey = HashMap<Int, PeriodStat>(keys.size)
        for (k in keys) byKey[k] = PeriodStat(label = labelOfKey(k), sortKey = k)
        for ((name, first) in countryFirst) {
            val k = keyOfDate(first)
            byKey[k]?.let { byKey[k] = it.copy(countryFirsts = it.countryFirsts + (name to first)) }
        }
        for ((name, first) in cityFirst) {
            val k = keyOfDate(first)
            byKey[k]?.let { byKey[k] = it.copy(cityFirsts = it.cityFirsts + (name to first)) }
        }
        for ((name, first) in continentFirst) {
            val k = keyOfDate(first)
            byKey[k]?.let { byKey[k] = it.copy(continentFirsts = it.continentFirsts + (name to first)) }
        }
        for ((k, km) in kmByKey) {
            byKey[k]?.let { byKey[k] = it.copy(distanceKm = km) }
        }
        for ((k, hops) in hopsByKey) {
            byKey[k]?.let { byKey[k] = it.copy(hops = hops.sortedByDescending { it.km }) }
        }
        return byKey.values.sortedBy { it.sortKey }
    }

    val daily = if (firstDate != null && lastDate != null) {
        val keys = (firstDate.toEpochDay()..lastDate.toEpochDay()).map { it.toInt() }
        buildSeries(
            keys = keys,
            keyOfDate = { it.toEpochDay().toInt() },
            labelOfKey = { k ->
                // Day-of-month only: the 1M window is short enough that the
                // month is obvious, and bare day numbers leave room for a
                // label under every bar.
                LocalDate.ofEpochDay(k.toLong()).dayOfMonth.toString()
            },
            kmByKey = kmByDay,
            hopsByKey = hopsByDay
        )
    } else emptyList()

    val monthly = if (firstDate != null && lastDate != null) {
        val keys = (monthKey(firstDate)..monthKey(lastDate)).toList()
        buildSeries(
            keys = keys,
            keyOfDate = ::monthKey,
            labelOfKey = { k -> "${MONTH_ABBR[k % 12]} ${(k / 12).toString().takeLast(2)}" },
            kmByKey = kmByMonth,
            hopsByKey = hopsByMonth
        )
    } else emptyList()

    val yearly = if (firstDate != null && lastDate != null) {
        val keys = (firstDate.year..lastDate.year).toList()
        buildSeries(
            keys = keys,
            keyOfDate = { it.year },
            labelOfKey = { it.toString().takeLast(2) },
            kmByKey = kmByYear,
            hopsByKey = hopsByYear
        )
    } else emptyList()

    // ── Longest stay in one place (consecutive days, same finest label) ───
    var longestStayDays = 0
    var longestStayPlace: String? = null
    var runPlace: String? = null
    var runStart: LocalDate? = null
    var prevDate: LocalDate? = null
    for ((d, p) in placeByDate) {
        val place = finestPlace(p.city, p.country)
        val consecutive = prevDate != null && d.toEpochDay() - prevDate!!.toEpochDay() <= 1L
        if (place != null && place == runPlace && consecutive) {
            val len = ChronoUnit.DAYS.between(runStart!!, d).toInt() + 1
            if (len > longestStayDays) {
                longestStayDays = len
                longestStayPlace = place
            }
        } else {
            runPlace = place
            runStart = d
            if (place != null && longestStayDays == 0) {
                longestStayDays = 1
                longestStayPlace = place
            }
        }
        prevDate = d
    }

    // ── Lat extremes ──────────────────────────────────────────────────────
    var north: CoordsExtreme? = null
    var south: CoordsExtreme? = null
    for ((d, c) in coordsByDate) {
        val place = placeByDate[d]?.let { listOfNotNull(it.city, it.country).joinToString(", ").ifBlank { null } }
        if (north == null || c.first > north!!.lat) north = CoordsExtreme(c.first, c.second, d, place)
        if (south == null || c.first < south!!.lat) south = CoordsExtreme(c.first, c.second, d, place)
    }

    /** Full leaderboard, sorted by days desc then name — the UI slices/truncates. */
    fun topList(days: Map<String, Int>, firsts: Map<String, LocalDate>, lasts: Map<String, LocalDate>) =
        days.entries
            .map { (name, cnt) -> PlaceDays(name, cnt, firsts[name] ?: LocalDate.EPOCH, lasts[name] ?: LocalDate.EPOCH) }
            .sortedWith(compareByDescending<PlaceDays> { it.days }.thenBy { it.name })

    return TravelStatsData(
        daysTracked = allDates.size,
        firstDate = allDates.firstOrNull(),
        lastDate = allDates.lastOrNull(),
        totalCountries = countryDays.size,
        totalCities = cityDays.size,
        totalContinents = continentDays.size,
        uniquePlaces = uniquePlaces.size,
        totalDistanceKm = totalKm,
        secondaryPings = secondariesByDate.values.sumOf { it.size },
        daily = daily,
        monthly = monthly,
        yearly = yearly,
        topCountries = topList(countryDays, countryFirst, countryLast),
        topCities = topList(cityDays, cityFirst, cityLast),
        continentDays = topList(continentDays, continentFirst, continentFirst),
        biggestHop = biggestHop,
        longestStayDays = longestStayDays,
        longestStayPlace = longestStayPlace,
        northernmost = north,
        southernmost = south
    )
}
