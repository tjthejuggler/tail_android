package com.example.tail.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * Companion read API (v2) change feed — the write-side half of the /v2/
 * ContentProvider endpoints served by HabitsContentProvider (app module).
 *
 * Three notification channels, so a companion can pick whichever is cheapest:
 *
 *  1. **Persistent last-change stamp** — `files/companion_change_state.json`
 *     holds the epoch-millis of the last data change; the provider's
 *     `content://com.example.tail.provider/v2/changes` endpoint surfaces it.
 *  2. **ContentObserver support** — [noteChange] calls
 *     `ContentResolver.notifyChange()` on the v2 habits/changes URIs, so a
 *     companion registered with `notifyForDescendants = true` re-syncs
 *     without polling.
 *  3. **Write broadcast** — [ACTION_ENTRY_ADDED] with [EXTRA_HABIT_ID],
 *     sent with the TAIL_INTEGRATION receiver permission (same trust model
 *     as the increment broadcasts — only same-keystore apps receive it).
 *
 * Callers: [HabitsRepository.saveDatabase] (counter/subtype/timed/sleep
 * value writes), [TextInputRepository.saveTextLog] (text entries) and
 * MealLogRepository CRUD (meal rows). All channels are fire-and-forget —
 * a notification failure must never break the underlying save.
 *
 * NOTE: the authority/permission constants here intentionally duplicate the
 * app-module ones (HabitsContentProvider / HabitIncrementAnnouncer) because
 * core-data must not depend on the app module. Keep them in sync.
 */
object TailChangeLog {

    private const val TAG = "TailChangeLog"

    /** Content authority of Tail's HabitsContentProvider (keep in sync with the app module). */
    const val AUTHORITY = "com.example.tail.provider"

    /** Root of the v2 habit data — observers register on this with descendants. */
    val V2_HABITS_URI: Uri = Uri.parse("content://$AUTHORITY/v2/habits")

    /** The last-change endpoint URI. */
    val V2_CHANGES_URI: Uri = Uri.parse("content://$AUTHORITY/v2/changes")

    /** Broadcast action announcing "some habit data changed". */
    const val ACTION_ENTRY_ADDED = "com.example.tail.ACTION_ENTRY_ADDED"

    /** Broadcast extra: the habit whose data changed (String, when known). */
    const val EXTRA_HABIT_ID = "com.example.tail.extra.HABIT_ID"

    /** Receiver permission for the broadcast (signature-level; keep in sync). */
    const val PERMISSION_TAIL_INTEGRATION = "com.example.tail.permission.TAIL_INTEGRATION"

    private const val STATE_FILE = "companion_change_state.json"

    /**
     * Records a data change: persists the stamp, notifies ContentObservers
     * and fires the permission-guarded broadcast. Never throws.
     *
     * @param habitId the habit whose data changed, when known
     */
    fun noteChange(context: Context, habitId: String? = null) {
        val now = System.currentTimeMillis()
        try {
            File(context.filesDir, STATE_FILE)
                .writeText(JSONObject(mapOf("last_change" to now)).toString())
        } catch (e: Exception) {
            Log.w(TAG, "change stamp persist failed: ${e.message}")
        }
        try {
            val cr = context.contentResolver
            cr.notifyChange(V2_HABITS_URI, null)
            cr.notifyChange(V2_CHANGES_URI, null)
        } catch (e: Exception) {
            Log.w(TAG, "notifyChange failed: ${e.message}")
        }
        try {
            val intent = Intent(ACTION_ENTRY_ADDED)
            if (!habitId.isNullOrBlank()) intent.putExtra(EXTRA_HABIT_ID, habitId)
            context.sendBroadcast(intent, PERMISSION_TAIL_INTEGRATION)
        } catch (e: Exception) {
            Log.w(TAG, "change broadcast failed: ${e.message}")
        }
    }

    /**
     * The epoch-millis stamp of the last recorded data change, or 0 when
     * nothing has changed since the feature shipped (the stamp lives in
     * internal storage and does not survive reinstalls).
     */
    fun lastChange(context: Context): Long = try {
        val file = File(context.filesDir, STATE_FILE)
        if (file.exists()) JSONObject(file.readText()).optLong("last_change", 0L) else 0L
    } catch (e: Exception) {
        Log.w(TAG, "change stamp read failed: ${e.message}")
        0L
    }
}
