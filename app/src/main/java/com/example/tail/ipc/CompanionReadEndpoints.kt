package com.example.tail.ipc

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.example.tail.data.AppSettings
import com.example.tail.data.HABIT_ORDER
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SECONDARY_VALUE_SLOT_PREFIXES
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.TailChangeLog
import com.example.tail.data.TextInputRepository
import com.example.tail.data.TimedDataRepository
import com.example.tail.data.TimedEntry
import com.example.tail.data.appLinkPackageName
import com.example.tail.data.isAppLink
import com.example.tail.data.isMinutesKey
import com.example.tail.data.meal.MealLogRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Query implementations for the v2 companion read API — the endpoints behind
 * [HabitsContentProvider] (which owns the URI matching and permission gate).
 *
 * Everything here is READ-ONLY and honours Tail's consent model:
 *  - `/v2/capabilities` and `/v2/changes` are always served (metadata only);
 *  - the master switch [AppSettings.companionApiEnabled] (ON by default —
 *    kill switch) gates everything; the [AppSettings.companionReadHabits]
 *    restriction set, when non-empty, limits sharing to just those habits
 *    (Settings → Integrations → Companion Read API). Everything shared
 *    by default: same-keystore apps only, same trust model as v1.
 *
 * Request fulfilment (2026-09-19 feature request from the Hoot companion):
 *  - R1  `/v2/habits` — habit_id / habit_name / habit_type / has_options /
 *        is_sharable / subtype_names; `/v2/apps` for app-link groupings.
 *  - R2  `/v2/habits/{id}/entries` — FULL history, oldest first:
 *        text habits (untruncated), meal habits (rich MealLog rows) and
 *        value habits (daily counts + subtype/timed session rows).
 *  - R3  `?from=` / `?to=` / `?after=` query params (inclusive from/to,
 *        strictly-greater after; `after` accepts "yyyy-MM-dd HH:mm:ss"
 *        or epoch millis).
 *  - R4  `/v2/changes` last_change_ts + notifyChange support + the
 *        ACTION_ENTRY_ADDED broadcast (see [TailChangeLog]).
 *  - R5  `entry_id` on every row: MealLog UUIDs for meals, documented
 *        SHA-256 digests for everything else.
 *  - R6  `/v2/capabilities` api_version / min_supported_version / features.
 *
 * §3 (labels): `/v2/habits/{id}/labels` exposes the text-habit option
 * inventory with `textInputOptionDescriptions` as descriptions — the
 * forward-compat columns (default_amount / default_unit / nutrient_tags)
 * exist per the request schema but are always null until the labels
 * feature lands.
 */
internal object CompanionReadEndpoints {

    private const val TAG = "CompanionReadV2"

    // ── Capability flags (R6) ────────────────────────────────────────────────

    const val API_VERSION = 2
    const val MIN_SUPPORTED_VERSION = 1

    /** Feature flags a companion can branch on instead of crashing on change. */
    val FEATURES = listOf(
        "habits_v2",           // /v2/habits with habit_type metadata
        "apps_v2",             // /v2/apps app-link enumeration
        "entries_full",        // /v2/habits/{id}/entries — full history
        "entries_incremental", // ?from= / ?to= / ?after= filters
        "meal_logs",           // meal habits expose rich MealLog rows
        "text_entries",        // text habits expose full untruncated entries
        "value_entries",       // counter/subtyped/timed/dated/sleep daily rows
        "option_descriptions", // /v2/habits/{id}/labels with descriptions
        "change_feed",         // /v2/changes + ContentObserver + broadcast
        "capability_flags"     // this endpoint
    )

    // ── Column names ─────────────────────────────────────────────────────────

    const val COL_API_VERSION = "api_version"
    const val COL_MIN_SUPPORTED_VERSION = "min_supported_version"
    const val COL_FEATURES = "features"

    const val COL_HABIT_ID = "habit_id"
    const val COL_HABIT_NAME = "habit_name"
    const val COL_HABIT_TYPE = "habit_type"
    const val COL_HAS_OPTIONS = "has_options"
    const val COL_IS_SHARABLE = "is_sharable"
    const val COL_SUBTYPE_NAMES = "subtype_names"

    const val COL_APP_ID = "app_id"
    const val COL_APP_NAME = "app_name"
    const val COL_HABIT_COUNT = "habit_count"

    const val COL_ENTRY_ID = "entry_id"
    const val COL_ENTRY_TS = "entry_ts"
    const val COL_VALUE = "value"
    const val COL_SUBTYPE = "subtype"
    const val COL_ENTRY_TEXT = "entry_text"
    const val COL_TIMESTAMP = "timestamp"
    const val COL_GROUP_START_TIMESTAMP = "group_start_timestamp"
    const val COL_TITLE = "title"
    const val COL_SUMMARY = "summary"
    const val COL_CALORIES = "calories"
    const val COL_PROTEIN_GRAMS = "protein_grams"
    const val COL_CARBS_GRAMS = "carbs_grams"
    const val COL_FAT_GRAMS = "fat_grams"
    const val COL_INGREDIENTS = "ingredients"
    const val COL_IS_VEGAN_VERIFIED = "is_vegan_verified"
    const val COL_HEALTH_NOTES = "health_notes"
    const val COL_VOICE_TRANSCRIPT = "voice_transcript"
    const val COL_IS_MANUAL = "is_manual"
    const val COL_COUNTED_INCREMENT = "counted_increment"

    const val COL_LABEL_ID = "label_id"
    const val COL_LABEL_TEXT = "label_text"
    const val COL_DESCRIPTION = "description"
    const val COL_DEFAULT_AMOUNT = "default_amount"
    const val COL_DEFAULT_UNIT = "default_unit"
    const val COL_NUTRIENT_TAGS = "nutrient_tags"

    const val COL_LAST_CHANGE_TS = "last_change_ts"

    /** Default projection of the entries endpoint — the union of all row shapes. */
    val ENTRY_COLUMNS = arrayOf(
        COL_ENTRY_ID, COL_HABIT_NAME, COL_ENTRY_TS, COL_VALUE, COL_SUBTYPE, COL_ENTRY_TEXT,
        COL_TIMESTAMP, COL_GROUP_START_TIMESTAMP, COL_TITLE, COL_SUMMARY, COL_CALORIES,
        COL_PROTEIN_GRAMS, COL_CARBS_GRAMS, COL_FAT_GRAMS, COL_INGREDIENTS,
        COL_IS_VEGAN_VERIFIED, COL_HEALTH_NOTES, COL_VOICE_TRANSCRIPT, COL_IS_MANUAL,
        COL_COUNTED_INCREMENT
    )

    private val HABIT_COLUMNS = arrayOf(
        COL_HABIT_ID, COL_HABIT_NAME, COL_HABIT_TYPE,
        COL_HAS_OPTIONS, COL_IS_SHARABLE, COL_SUBTYPE_NAMES
    )

    private val APP_COLUMNS = arrayOf(COL_APP_ID, COL_APP_NAME, COL_HABIT_COUNT)

    private val LABEL_COLUMNS = arrayOf(
        COL_LABEL_ID, COL_LABEL_TEXT, COL_DESCRIPTION,
        COL_DEFAULT_AMOUNT, COL_DEFAULT_UNIT, COL_NUTRIENT_TAGS
    )

    private val TS_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // ── Endpoint: capabilities (R6) ──────────────────────────────────────────

    /** One row: [COL_API_VERSION, COL_MIN_SUPPORTED_VERSION, COL_FEATURES]. */
    fun queryCapabilities(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(COL_API_VERSION, COL_MIN_SUPPORTED_VERSION, COL_FEATURES)
        val cursor = MatrixCursor(cols)
        cursor.addRow(cols.map { col ->
            when (col) {
                COL_API_VERSION -> API_VERSION
                COL_MIN_SUPPORTED_VERSION -> MIN_SUPPORTED_VERSION
                COL_FEATURES -> JSONArray(FEATURES).toString()
                else -> null
            }
        }.toTypedArray())
        return cursor
    }

    // ── Endpoint: habit enumeration (R1) ─────────────────────────────────────

    /**
     * Rows: [HABIT_COLUMNS] — every habit visible to companions: ALL habits
     * by default; only the restriction set when the user curated it.
     * Empty when the kill switch is off.
     */
    fun queryV2Habits(settings: AppSettings?, projection: Array<out String>?): Cursor {
        val cols = projection ?: HABIT_COLUMNS
        val cursor = MatrixCursor(cols)
        if (settings == null || !settings.companionApiEnabled) return cursor
        for (name in effectiveHabitOrder(settings)) {
            if (!sharedHabit(settings, name)) continue
            val subtypes = settings.habitSubtypes[name].orEmpty()
            emit(cursor, cols, mapOf(
                COL_HABIT_ID to name,
                COL_HABIT_NAME to name,
                COL_HABIT_TYPE to habitType(settings, name),
                COL_HAS_OPTIONS to if (name in settings.textInputOptionsHabits) 1 else 0,
                COL_IS_SHARABLE to if (name in settings.sharableTextHabits) 1 else 0,
                COL_SUBTYPE_NAMES to subtypes.takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }
            ))
        }
        return cursor
    }

    /**
     * Rows: [APP_COLUMNS] — the app-link "app" groupings configured on the
     * habit screens, with a count of habits linked to each package.
     */
    fun queryV2Apps(settings: AppSettings?, projection: Array<out String>?): Cursor {
        val cols = projection ?: APP_COLUMNS
        val cursor = MatrixCursor(cols)
        if (settings == null || !settings.companionApiEnabled) return cursor
        val packages = effectiveHabitOrder(settings)
            .mapNotNull { appLinkPackageName(it) }
            .distinct()
        for (pkg in packages) {
            val linked = (settings.widgetTriggerApps.filterValues { it == pkg }.keys +
                settings.mediaApps.filterValues { it == pkg }.keys)
                .distinct()
                .size
            emit(cursor, cols, mapOf(
                COL_APP_ID to pkg,
                COL_APP_NAME to pkg,
                COL_HABIT_COUNT to linked
            ))
        }
        return cursor
    }

    /** First-match habit type — mirrors the settings sets that define each type. */
    private fun habitType(s: AppSettings, name: String): String = when {
        name in s.mealHabits -> "meal"
        name in s.textInputHabits -> "text"
        name in s.sleepHabits -> "sleep"
        name in s.subtypedHabits -> "subtyped"
        name in s.datedEntryHabits -> "dated_entry"
        name in s.timedHabits -> "timed"
        else -> "counter"
    }

    /** The active habit order (screens → legacy flat order → canonical), cleaned. */
    private fun effectiveHabitOrder(settings: AppSettings): List<String> =
        (when {
            settings.habitScreens.isNotEmpty() -> settings.habitScreens.flatMap { it.habitNames }
            settings.habitOrder.isNotEmpty() -> settings.habitOrder
            else -> HABIT_ORDER
        }).asSequence()
            .filter {
                it.isNotBlank() && !isAppLink(it) && !isMinutesKey(it) &&
                    SECONDARY_VALUE_SLOT_PREFIXES.none(it::startsWith)
            }
            .distinct()
            .toList()

    // ── Endpoint: full history (R2 + R3 + R5) ────────────────────────────────

    /**
     * Rows: [ENTRY_COLUMNS] — every entry of ONE shared habit, oldest
     * first, narrowed by the `from` / `to` / `after` / `limit` params.
     */
    fun queryHabitEntries(
        context: Context?,
        settings: AppSettings?,
        habitId: String,
        uri: Uri,
        projection: Array<out String>?
    ): Cursor {
        val cols = projection ?: ENTRY_COLUMNS
        val cursor = MatrixCursor(cols)
        if (context == null || settings == null || !settings.companionApiEnabled) return cursor
        if (!sharedHabit(settings, habitId)) return cursor
        val filter = parseTimeFilter(uri)
        // Optional row cap — companions (e.g. Hoot's capability probe) may
        // pass ?limit=1; ignored when absent, negative, or unparseable.
        val limit = try { uri.getQueryParameter("limit")?.toIntOrNull() } catch (_: Exception) { null }
            ?.takeIf { it > 0 }
        try {
            val rows = mutableListOf<Pair<LocalDateTime, Map<String, Any?>>>()
            when {
                habitId in settings.textInputHabits ->
                    collectTextEntries(rows, context, settings, habitId, filter)
                habitId in settings.mealHabits ->
                    collectMealEntries(rows, context, habitId, filter)
                else ->
                    collectValueEntries(rows, context, settings, habitId, filter)
            }
            rows.sortBy { it.first }
            val emitted = if (limit != null) rows.take(limit) else rows
            for ((_, row) in emitted) emit(cursor, cols, row)
        } catch (e: Exception) {
            Log.w(TAG, "entries query failed for '$habitId': ${e.message}")
        }
        return cursor
    }

    /** Text habits: the FULL log, untruncated (the 300-char cap is Inuit-only). */
    private fun collectTextEntries(
        rows: MutableList<Pair<LocalDateTime, Map<String, Any?>>>,
        context: Context,
        settings: AppSettings,
        habitId: String,
        filter: TimeFilter
    ) {
        val log = loadTextLog(context, settings, habitId)
        for ((ts, text) in log) {
            val t = try {
                LocalDateTime.parse(ts, TS_FMT)
            } catch (_: Exception) {
                continue
            }
            if (!filter.accepts(t)) continue
            rows += t to mapOf(
                COL_ENTRY_ID to stableId("text", habitId, ts),
                COL_HABIT_NAME to habitId,
                COL_ENTRY_TS to ts,
                COL_ENTRY_TEXT to text
            )
        }
    }

    /** Meal habits: rich MealLog rows — the internal JSON exposed as-is. */
    private fun collectMealEntries(
        rows: MutableList<Pair<LocalDateTime, Map<String, Any?>>>,
        context: Context,
        habitId: String,
        filter: TimeFilter
    ) {
        val logs = try {
            MealLogRepository(context).loadLogs(habitId)
        } catch (e: Exception) {
            Log.w(TAG, "meal log load failed for '$habitId': ${e.message}")
            emptyList()
        }
        for (meal in logs.sortedBy { it.timestamp }) {
            val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(meal.timestamp), ZoneId.systemDefault())
            if (!filter.accepts(t)) continue
            rows += t to mapOf(
                COL_ENTRY_ID to meal.id, // the MealLog UUID — stable across renames (R5)
                COL_HABIT_NAME to habitId,
                COL_ENTRY_TS to t.format(TS_FMT),
                COL_TIMESTAMP to meal.timestamp,
                COL_GROUP_START_TIMESTAMP to meal.groupStartTimestamp,
                COL_TITLE to meal.title,
                COL_SUMMARY to meal.summary,
                COL_CALORIES to meal.calories,
                COL_PROTEIN_GRAMS to meal.macronutrients.proteinGrams,
                COL_CARBS_GRAMS to meal.macronutrients.carbsGrams,
                COL_FAT_GRAMS to meal.macronutrients.fatGrams,
                COL_INGREDIENTS to JSONArray(meal.ingredientsDetected).toString(),
                COL_IS_VEGAN_VERIFIED to if (meal.isVeganVerified) 1 else 0,
                COL_HEALTH_NOTES to meal.healthNotes,
                COL_VOICE_TRANSCRIPT to meal.voiceTranscript,
                COL_IS_MANUAL to if (meal.isManual) 1 else 0,
                COL_COUNTED_INCREMENT to if (meal.countedIncrement) 1 else 0
            )
        }
    }

    /**
     * Value habits (counter / subtyped / timed / dated_entry / sleep):
     * daily count rows from habitsdb.txt, plus per-subtype rows and timed
     * session rows when the habit carries those features.
     */
    private fun collectValueEntries(
        rows: MutableList<Pair<LocalDateTime, Map<String, Any?>>>,
        context: Context,
        settings: AppSettings,
        habitId: String,
        filter: TimeFilter
    ) {
        val fileUri = settings.fileUri
        if (fileUri.isBlank()) return
        val db: HabitsDatabase = runBlocking {
            try {
                HabitsRepository().loadDatabase(Uri.parse(fileUri), context)
            } catch (e: Exception) {
                Log.w(TAG, "habitsdb load failed: ${e.message}")
                emptyMap()
            }
        }
        for ((date, count) in db[habitId].orEmpty()) {
            val t = parseDayStart(date) ?: continue
            if (!filter.accepts(t)) continue
            rows += t to mapOf(
                COL_ENTRY_ID to stableId("count", habitId, date),
                COL_HABIT_NAME to habitId,
                COL_ENTRY_TS to "$date 00:00:00",
                COL_VALUE to count
            )
        }
        if (habitId in settings.subtypedHabits) {
            val data: Map<String, Map<String, Int>> = runBlocking {
                try {
                    SubtypeDataRepository(context).loadSubtypeData(habitId)
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            for ((date, perSubtype) in data) {
                val t = parseDayStart(date) ?: continue
                for ((subtype, count) in perSubtype) {
                    if (!filter.accepts(t)) continue
                    rows += t to mapOf(
                        COL_ENTRY_ID to stableId("subtype", habitId, date, subtype),
                        COL_HABIT_NAME to habitId,
                        COL_ENTRY_TS to "$date 00:00:00",
                        COL_VALUE to count,
                        COL_SUBTYPE to subtype
                    )
                }
            }
        }
        if (habitId in settings.timedHabits) {
            val data: Map<String, TimedEntry> = runBlocking {
                try {
                    TimedDataRepository(context).loadTimedData(habitId)
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            for ((ts, entry) in data) {
                val t = try {
                    LocalDateTime.parse(ts, TS_FMT)
                } catch (_: Exception) {
                    continue
                }
                if (!filter.accepts(t)) continue
                rows += t to mapOf(
                    COL_ENTRY_ID to stableId("timed", habitId, ts, entry.subtype ?: ""),
                    COL_HABIT_NAME to habitId,
                    COL_ENTRY_TS to ts,
                    COL_VALUE to entry.count,
                    COL_SUBTYPE to entry.subtype
                )
            }
        }
    }

    /** Loads a habit's text log: live SAF file first, internal backup as fallback. */
    private fun loadTextLog(
        context: Context,
        settings: AppSettings,
        habitId: String
    ): Map<String, String> {
        val repo = TextInputRepository()
        val live: Map<String, String>? = runBlocking {
            try {
                settings.textInputFileUris[habitId]
                    ?.let { Uri.parse(it) }
                    ?.let { repo.loadTextLog(it, context) }
            } catch (_: Exception) {
                null
            }
        }
        if (!live.isNullOrEmpty()) return live
        return try {
            repo.loadInternalBackup(context, habitId) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    // ── Endpoint: option descriptions / labels (§3 minimum) ──────────────────

    /**
     * Rows: [LABEL_COLUMNS] — the option inventory of a text-input habit:
     * every option seen in the log plus every described option, with
     * `textInputOptionDescriptions` as the description column. The
     * default_amount / default_unit / nutrient_tags columns are forward
     * compat for the upcoming labels feature and are always null today.
     */
    fun queryHabitLabels(
        context: Context?,
        settings: AppSettings?,
        habitId: String,
        projection: Array<out String>?
    ): Cursor {
        val cols = projection ?: LABEL_COLUMNS
        val cursor = MatrixCursor(cols)
        if (context == null || settings == null || !settings.companionApiEnabled) return cursor
        if (!sharedHabit(settings, habitId)) return cursor

        val described = settings.textInputOptionDescriptions[habitId].orEmpty()
        val logOptions = if (habitId in settings.textInputHabits) {
            loadTextLog(context, settings, habitId).values
        } else {
            emptyList()
        }
        val labels = (logOptions + described.keys).distinct().sorted()
        for (text in labels) {
            emit(cursor, cols, mapOf(
                COL_LABEL_ID to stableId("label", habitId, text),
                COL_LABEL_TEXT to text,
                COL_DESCRIPTION to described[text]
                // COL_DEFAULT_AMOUNT / COL_DEFAULT_UNIT / COL_NUTRIENT_TAGS: not built yet
            ))
        }
        return cursor
    }

    /**
     * The consent rule for ONE habit: kill switch on AND (no restriction
     * set → everything is shared; non-empty set → only the listed habits).
     */
    private fun sharedHabit(settings: AppSettings, name: String): Boolean =
        settings.companionReadHabits.isEmpty() || name in settings.companionReadHabits

    // ── Endpoint: last change (R4) ───────────────────────────────────────────

    /** One row: [COL_LAST_CHANGE_TS] — epoch millis, 0 = nothing recorded yet. */
    fun queryChanges(context: Context?, projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(COL_LAST_CHANGE_TS)
        val cursor = MatrixCursor(cols)
        val stamp = context?.let { TailChangeLog.lastChange(it) } ?: 0L
        cursor.addRow(cols.map { col ->
            if (col == COL_LAST_CHANGE_TS) stamp else null
        }.toTypedArray())
        return cursor
    }

    // ── Shared helpers ───────────────────────────────────────────────────────

    /** Inclusive from/to, strictly-greater after (R3). */
    private class TimeFilter(
        val from: LocalDateTime?,
        val to: LocalDateTime?,
        val after: LocalDateTime?
    ) {
        fun accepts(t: LocalDateTime): Boolean =
            (from == null || !t.isBefore(from)) &&
                (to == null || !t.isAfter(to)) &&
                (after == null || t.isAfter(after))
    }

    /**
     * Parses the `from` / `to` / `after` / `limit` query params. `after`
     * accepts the log format "yyyy-MM-dd HH:mm:ss" OR epoch millis
     * (all-digit values). Unparseable values are ignored (treated as absent).
     */
    private fun parseTimeFilter(uri: Uri): TimeFilter {
        fun param(name: String): String? = try {
            uri.getQueryParameter(name)?.trim()
        } catch (_: Exception) {
            null
        }

        fun datetime(raw: String?): LocalDateTime? = raw?.let {
            try {
                LocalDateTime.parse(it, TS_FMT)
            } catch (_: Exception) {
                null
            }
        }

        val afterRaw = param("after")
        val after = datetime(afterRaw) ?: afterRaw?.toLongOrNull()?.let {
            LocalDateTime.ofInstant(Instant.ofEpochMilli(it), ZoneId.systemDefault())
        }
        return TimeFilter(datetime(param("from")), datetime(param("to")), after)
    }

    /** Parses a "YYYY-MM-DD" day key as that day's 00:00:00, or null. */
    private fun parseDayStart(date: String): LocalDateTime? = try {
        LocalDate.parse(date).atStartOfDay()
    } catch (_: Exception) {
        null
    }

    /**
     * Documented stable entry id (R5): SHA-256 over "namespace|part|…",
     * hex, 32 chars. Stable across restarts and Tail updates. NOTE: ids of
     * non-meal rows incorporate the CURRENT habit name — a habit rename
     * changes them (Tail keys habits by name internally). Meal rows carry
     * the MealLog UUID instead, which is rename-proof.
     */
    private fun stableId(namespace: String, vararg parts: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest((listOf(namespace) + parts).joinToString("|").toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(32)

    /** Adds one row honouring the caller's projection (unknown columns → null). */
    private fun emit(cursor: MatrixCursor, cols: Array<out String>, row: Map<String, Any?>) {
        cursor.addRow(cols.map { row[it] }.toTypedArray())
    }
}
