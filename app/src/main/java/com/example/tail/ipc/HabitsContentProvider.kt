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
 * Endpoints:
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
 * Only query() is supported. All mutation methods throw UnsupportedOperationException.
 */
class HabitsContentProvider : ContentProvider() {

    companion object {
        const val TAG = "HabitsContentProvider"
        const val AUTHORITY = "com.example.tail.provider"
        const val PATH_HABITS = "habits"
        const val PATH_TEXT_HABITS = "text_habits"
        const val PATH_TEXT_HABITS_RECENT = "text_habits/recent"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_HABITS")
        val TEXT_HABITS_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_TEXT_HABITS")
        val TEXT_HABITS_RECENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_TEXT_HABITS_RECENT")

        const val COL_HABIT_ID = "habit_id"
        const val COL_HABIT_NAME = "habit_name"
        const val COL_ENTRY_TS = "entry_ts"
        const val COL_ENTRY_TEXT = "entry_text"

        private const val CODE_HABITS = 1
        private const val CODE_TEXT_HABITS = 2
        private const val CODE_TEXT_HABITS_RECENT = 3
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_HABITS, CODE_HABITS)
            addURI(AUTHORITY, PATH_TEXT_HABITS, CODE_TEXT_HABITS)
            addURI(AUTHORITY, PATH_TEXT_HABITS_RECENT, CODE_TEXT_HABITS_RECENT)
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
        else -> throw IllegalArgumentException("Unknown URI: $uri")
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Tail habits provider is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Tail habits provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Tail habits provider is read-only")
}
