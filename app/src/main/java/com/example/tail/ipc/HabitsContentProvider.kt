package com.example.tail.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.example.tail.data.AppSettings
import com.example.tail.data.HABIT_ORDER
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.time.LocalDateTime

/**
 * Read-only ContentProvider that exposes habit data to other apps signed
 * with the same keystore (enforced via the com.example.tail.permission.TAIL_INTEGRATION
 * signature permission declared in AndroidManifest.xml).
 *
 * V1 endpoints (unchanged — the tight Inuit slice is a privacy feature):
 *  1. content://com.example.tail.provider/habits
 *     Columns: habit_id (Int, 0-based index), habit_name (String)
 *     The full habit list in active screen order.
 *
 *  2. content://com.example.tail.provider/text_habits
 *     Columns: habit_name (String)
 *     The text-input habits the user has explicitly shared with the Inuit
 *     trivia trainer (Settings → Integrations → Inuit). Empty when the
 *     Inuit integration master switch is off.
 *
 *  3. content://com.example.tail.provider/text_habits/recent?limit=N
 *     Columns: habit_name (String), entry_ts (String "yyyy-MM-dd HH:mm:ss"),
 *              entry_text (String, truncated)
 *     The most recent text entries of every shared habit — a deliberately
 *     TINY slice (see [InuitTextSharing]): last 14 days only, at most N
 *     (default 3, max 5) entries per habit, 300 chars per entry. Empty when
 *     the integration is off.
 *
 * V2 endpoints (companion read API, implemented in [CompanionReadEndpoints]:
 * ON by default — the signature permission is the trust boundary; the
 * kill switch and restriction list live at
 * Settings → Integrations → Companion Read API):
 *
 *  4. content://com.example.tail.provider/v2/capabilities
 *     Columns: api_version (Int), min_supported_version (Int),
 *              features (String — JSON array of feature flags).
 *
 *  5. content://com.example.tail.provider/v2/habits
 *     Columns: habit_id, habit_name, habit_type ("counter"|"text"|"meal"|
 *              "timed"|"dated_entry"|"sleep"|"subtyped"), has_options,
 *              is_sharable, subtype_names (JSON array or null).
 *     Only OPTED-IN habits are listed.
 *
 *  6. content://com.example.tail.provider/v2/apps
 *     Columns: app_id, app_name, habit_count — the app-link groupings.
 *
 *  7. content://com.example.tail.provider/v2/habits/{habit_id}/entries
 *     Columns: the union projection in [CompanionReadEndpoints.ENTRY_COLUMNS]
 *     (entry_id, habit_name, entry_ts, value, subtype, entry_text, timestamp,
 *     group_start_timestamp, title, summary, calories, protein/carbs/fat
 *     grams, ingredients, is_vegan_verified, health_notes, voice_transcript,
 *     is_manual, counted_increment). Full history, oldest first. Query
 *     params: ?from= / ?to= ("yyyy-MM-dd HH:mm:ss", inclusive) and
 *     ?after= (same format or epoch millis, strictly greater).
 *
 *  8. content://com.example.tail.provider/v2/habits/{habit_id}/labels
 *     Columns: label_id, label_text, description (the
 *     textInputOptionDescriptions metadata), default_amount,
 *     default_unit, nutrient_tags (last three null until the labels
 *     feature ships).
 *
 *  9. content://com.example.tail.provider/v2/changes
 *     Columns: last_change_ts (Long epoch millis, 0 = nothing yet).
 *     ContentObservers registered on the v2 URIs (notifyForDescendants)
 *     also fire on every data write, as does the permission-guarded
 *     ACTION_ENTRY_ADDED broadcast — see [TailChangeLog].
 *
 * Only query() is supported. All mutation methods throw UnsupportedOperationException.
 */
class HabitsContentProvider : ContentProvider() {

    companion object {
        const val TAG = "HabitsContentProvider"
        const val AUTHORITY = "com.example.tail.provider"
        const val PATH_HABITS = "habits"
        const val PATH_TEXT_HABITS = "text_habits"
        const val PATH_TEXT_HABITS_RECENT = "text_habits/recent"
        const val PATH_V2 = "v2"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_HABITS")
        val TEXT_HABITS_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_TEXT_HABITS")
        val TEXT_HABITS_RECENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_TEXT_HABITS_RECENT")
        val V2_CAPABILITIES_URI: Uri = Uri.parse("content://$AUTHORITY/v2/capabilities")
        val V2_HABITS_URI: Uri = Uri.parse("content://$AUTHORITY/v2/habits")
        val V2_APPS_URI: Uri = Uri.parse("content://$AUTHORITY/v2/apps")
        val V2_CHANGES_URI: Uri = Uri.parse("content://$AUTHORITY/v2/changes")

        const val COL_HABIT_ID = "habit_id"
        const val COL_HABIT_NAME = "habit_name"
        const val COL_ENTRY_TS = "entry_ts"
        const val COL_ENTRY_TEXT = "entry_text"

        private const val CODE_HABITS = 1
        private const val CODE_TEXT_HABITS = 2
        private const val CODE_TEXT_HABITS_RECENT = 3
        private const val CODE_V2_CAPABILITIES = 10
        private const val CODE_V2_HABITS = 11
        private const val CODE_V2_APPS = 12
        private const val CODE_V2_ENTRIES = 13
        private const val CODE_V2_LABELS = 14
        private const val CODE_V2_CHANGES = 15
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_HABITS, CODE_HABITS)
            addURI(AUTHORITY, PATH_TEXT_HABITS, CODE_TEXT_HABITS)
            addURI(AUTHORITY, PATH_TEXT_HABITS_RECENT, CODE_TEXT_HABITS_RECENT)
            addURI(AUTHORITY, "v2/capabilities", CODE_V2_CAPABILITIES)
            addURI(AUTHORITY, "v2/habits", CODE_V2_HABITS)
            addURI(AUTHORITY, "v2/apps", CODE_V2_APPS)
            addURI(AUTHORITY, "v2/habits/*", CODE_V2_ENTRIES)
            addURI(AUTHORITY, "v2/habits/*/entries", CODE_V2_ENTRIES)
            addURI(AUTHORITY, "v2/habits/*/labels", CODE_V2_LABELS)
            addURI(AUTHORITY, "v2/changes", CODE_V2_CHANGES)
        }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor = when (uriMatcher.match(uri)) {
        CODE_HABITS -> queryHabitList(projection)
        CODE_TEXT_HABITS -> querySharedTextHabits(projection)
        CODE_TEXT_HABITS_RECENT -> queryRecentTextEntries(uri, projection)
        CODE_V2_CAPABILITIES -> CompanionReadEndpoints.queryCapabilities(projection)
        CODE_V2_HABITS -> CompanionReadEndpoints.queryV2Habits(loadSettings(), projection)
        CODE_V2_APPS -> CompanionReadEndpoints.queryV2Apps(loadSettings(), projection)
        CODE_V2_ENTRIES -> {
            // "v2/habits/{id}" (bare) and "v2/habits/{id}/entries" — same rows
            val segments = uri.pathSegments
            val habitId = if (segments.size >= 3) segments[segments.size - 2] else segments.last()
            CompanionReadEndpoints.queryHabitEntries(context, loadSettings(), habitId, uri, projection)
        }
        CODE_V2_LABELS -> {
            val segments = uri.pathSegments
            val habitId = segments[segments.size - 2]
            CompanionReadEndpoints.queryHabitLabels(context, loadSettings(), habitId, projection)
        }
        CODE_V2_CHANGES -> CompanionReadEndpoints.queryChanges(context, projection)
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    /**
     * Returns a cursor with columns [habit_id, habit_name].
     * The habit list is sourced from the active screen order stored in settings;
     * falls back to the canonical HABIT_ORDER if no custom order is configured.
     */
    private fun queryHabitList(projection: Array<out String>?): Cursor {
        val ctx = context ?: return MatrixCursor(arrayOf(COL_HABIT_ID, COL_HABIT_NAME))

        // Resolve the effective habit order from persisted settings (blocking — provider runs on binder thread)
        val habitNames: List<String> = runBlocking {
            try {
                val settings = SettingsRepository(ctx).settingsFlow.first()
                when {
                    settings.habitScreens.isNotEmpty() -> {
                        // Flatten all screens into one ordered list
                        settings.habitScreens.flatMap { it.habitNames }
                    }
                    settings.habitOrder.isNotEmpty() -> settings.habitOrder
                    else -> HABIT_ORDER
                }
            } catch (e: Exception) {
                HABIT_ORDER
            }
        }.filter { it.isNotBlank() && !it.startsWith("app_link:") } // drop empty slots & pseudo-habits
         .distinct()

        val cols = projection ?: arrayOf(COL_HABIT_ID, COL_HABIT_NAME)
        val cursor = MatrixCursor(cols)
        habitNames.forEachIndexed { index, name ->
            val row = cols.map { col ->
                when (col) {
                    COL_HABIT_ID -> index
                    COL_HABIT_NAME -> name
                    else -> null
                }
            }.toTypedArray()
            cursor.addRow(row)
        }
        return cursor
    }

    // ── Inuit text-habit sharing ────────────────────────────────────────────

    /** Loads settings once (blocking — binder thread). */
    private fun loadSettings(): AppSettings? = try {
        val ctx = context ?: return null
        runBlocking { SettingsRepository(ctx).settingsFlow.first() }
    } catch (e: Exception) {
        Log.w(TAG, "settings load failed: ${e.message}")
        null
    }

    /**
     * The text-input habits shareable with Inuit: master switch on, habit
     * selected for sharing AND still a text-input habit. Sorted for stable
     * display in Inuit's per-net picker.
     */
    private fun sharedTextHabits(settings: AppSettings): List<String> =
        if (!settings.inuitIntegrationEnabled) emptyList()
        else settings.inuitTextHabits
            .intersect(settings.textInputHabits)
            .sorted()

    /** Rows: [habit_name] — the shared text habits (empty when integration off). */
    private fun querySharedTextHabits(projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(COL_HABIT_NAME)
        val cursor = MatrixCursor(cols)
        val settings = loadSettings() ?: return cursor
        for (habit in sharedTextHabits(settings)) {
            cursor.addRow(cols.map { col ->
                when (col) {
                    COL_HABIT_NAME -> habit
                    else -> null
                }
            }.toTypedArray())
        }
        return cursor
    }

    /**
     * Rows: [habit_name, entry_ts, entry_text] — the most recent entries of
     * every shared habit (bounded by [InuitTextSharing]). The per-habit entry
     * count comes from the optional `limit` query parameter (default 3, max 5).
     */
    private fun queryRecentTextEntries(uri: Uri, projection: Array<out String>?): Cursor {
        val cols = projection ?: arrayOf(COL_HABIT_NAME, COL_ENTRY_TS, COL_ENTRY_TEXT)
        val cursor = MatrixCursor(cols)
        val ctx = context ?: return cursor
        val settings = loadSettings() ?: return cursor
        val habits = sharedTextHabits(settings)
        if (habits.isEmpty()) return cursor

        val limit = InuitTextSharing.clampLimit(
            try { uri.getQueryParameter("limit")?.toIntOrNull() } catch (_: Exception) { null }
        )
        val repo = TextInputRepository()
        val now = LocalDateTime.now()

        for (habit in habits) {
            // Prefer the live SAF log; fall back to the internal backup when
            // the external file is unreachable (deleted, provider hiccup…).
            val log: Map<String, String> = runBlocking {
                try {
                    settings.textInputFileUris[habit]
                        ?.let { Uri.parse(it) }
                        ?.let { repo.loadTextLog(it, ctx) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
            }.ifEmpty {
                try {
                    repo.loadInternalBackup(ctx, habit) ?: emptyMap()
                } catch (_: Exception) {
                    emptyMap()
                }
            }
            for ((ts, text) in InuitTextSharing.recentEntries(log, limit, now)) {
                cursor.addRow(cols.map { col ->
                    when (col) {
                        COL_HABIT_NAME -> habit
                        COL_ENTRY_TS -> ts
                        COL_ENTRY_TEXT -> text
                        else -> null
                    }
                }.toTypedArray())
            }
        }
        return cursor
    }

    override fun getType(uri: Uri): String = when (uriMatcher.match(uri)) {
        CODE_HABITS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_HABITS"
        CODE_TEXT_HABITS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_TEXT_HABITS"
        CODE_TEXT_HABITS_RECENT -> "vnd.android.cursor.dir/vnd.$AUTHORITY.text_habits_recent"
        CODE_V2_CAPABILITIES -> "vnd.android.cursor.item/vnd.$AUTHORITY.v2_capabilities"
        CODE_V2_HABITS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.v2_habits"
        CODE_V2_APPS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.v2_apps"
        CODE_V2_ENTRIES -> "vnd.android.cursor.dir/vnd.$AUTHORITY.v2_entries"
        CODE_V2_LABELS -> "vnd.android.cursor.dir/vnd.$AUTHORITY.v2_labels"
        CODE_V2_CHANGES -> "vnd.android.cursor.item/vnd.$AUTHORITY.v2_changes"
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Tail habits provider is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Tail habits provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Tail habits provider is read-only")
}
