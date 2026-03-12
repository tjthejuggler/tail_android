package com.example.tail.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import com.example.tail.data.HABIT_ORDER
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Read-only ContentProvider that exposes the habit list to other apps signed
 * with the same keystore (enforced via the com.example.tail.permission.TAIL_INTEGRATION
 * signature permission declared in AndroidManifest.xml).
 *
 * URI:     content://com.example.tail.provider/habits
 * Columns: habit_id (Int, 0-based index), habit_name (String)
 *
 * Only query() is supported. All mutation methods throw UnsupportedOperationException.
 */
class HabitsContentProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "com.example.tail.provider"
        const val PATH_HABITS = "habits"
        val CONTENT_URI: Uri = Uri.parse("content://$AUTHORITY/$PATH_HABITS")

        const val COL_HABIT_ID = "habit_id"
        const val COL_HABIT_NAME = "habit_name"

        private const val CODE_HABITS = 1
        private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH).apply {
            addURI(AUTHORITY, PATH_HABITS, CODE_HABITS)
        }
    }

    override fun onCreate(): Boolean = true

    /**
     * Returns a cursor with columns [habit_id, habit_name].
     * The habit list is sourced from the active screen order stored in settings;
     * falls back to the canonical HABIT_ORDER if no custom order is configured.
     */
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        if (uriMatcher.match(uri) != CODE_HABITS) {
            throw IllegalArgumentException("Unknown URI: $uri")
        }

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
        }

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

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.dir/vnd.$AUTHORITY.$PATH_HABITS"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("Tail habits provider is read-only")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Tail habits provider is read-only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Tail habits provider is read-only")
}
