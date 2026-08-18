package com.example.tail.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** DataStore holding the pending habit-ask notifications. */
private val Context.notificationsDataStore by preferencesDataStore(name = "habit_notifications")

/**
 * Single source of truth for pending "ask" notifications ([HabitNotification]).
 *
 * Every ask exists here first; the Android system notification and the in-app
 * center are both derived views of this list. Answering anywhere removes the
 * record here and cancels the matching system notification — that is what
 * makes an answer "dismiss everywhere".
 *
 * Also tracks the last date each scheduled habit ask fired ([scheduleLastFired])
 * so catch-up logic never re-asks the same habit twice in one day, even if the
 * exact alarm was missed (phone off, app killed) and the app only opens later.
 */
class NotificationStore(private val context: Context) {

    private val dataStore = context.notificationsDataStore

    companion object {
        private val KEY_NOTIFICATIONS = stringPreferencesKey("pending_notifications")
        private val KEY_SCHEDULE_LAST_FIRED = stringSetPreferencesKey("schedule_last_fired")

        /** Encoded as "habit\u001Fyyyy-MM-dd" entries in a string set. */
        private const val FIRED_SEP = "\u001F"
    }

    /** Live list of pending asks, oldest first. */
    val notificationsFlow: Flow<List<HabitNotification>> = dataStore.data
        .map { HabitNotificationCodec.decode(it[KEY_NOTIFICATIONS]).sortedBy { n -> n.createdAtMillis } }

    /** Adds [notification]; no-op when an ask with the same id already exists. */
    suspend fun add(notification: HabitNotification) {
        dataStore.edit { prefs ->
            val current = HabitNotificationCodec.decode(prefs[KEY_NOTIFICATIONS])
            if (current.any { it.id == notification.id }) {
                return@edit
            }
            prefs[KEY_NOTIFICATIONS] = HabitNotificationCodec.encode(current + notification)
        }
    }

    /** Removes the ask with [id] (no-op when absent). */
    suspend fun remove(id: String) {
        dataStore.edit { prefs ->
            val current = HabitNotificationCodec.decode(prefs[KEY_NOTIFICATIONS])
            if (current.none { it.id == id }) return@edit
            prefs[KEY_NOTIFICATIONS] = HabitNotificationCodec.encode(current.filter { it.id != id })
        }
    }

    /** Marks the one-time flash as shown for [id] (no-op when absent). */
    suspend fun markFlashShown(id: String) {
        dataStore.edit { prefs ->
            val current = HabitNotificationCodec.decode(prefs[KEY_NOTIFICATIONS])
            val target = current.indexOfFirst { it.id == id }
            if (target < 0) return@edit
            val updated = current.toMutableList()
            updated[target] = updated[target].copy(flashShown = true)
            prefs[KEY_NOTIFICATIONS] = HabitNotificationCodec.encode(updated)
        }
    }

    /** Returns the pending ask with [id], or null. */
    suspend fun get(id: String): HabitNotification? {
        return notificationsFlow.first().firstOrNull { it.id == id }
    }

    /** habit → "yyyy-MM-dd" of the last day its scheduled ask fired. */
    suspend fun scheduleLastFired(): Map<String, String> {
        val raw = dataStore.data.first()[KEY_SCHEDULE_LAST_FIRED] ?: emptySet()
        return raw.mapNotNull { entry ->
            val idx = entry.indexOf(FIRED_SEP)
            if (idx <= 0) null else entry.substring(0, idx) to entry.substring(idx + 1)
        }.toMap()
    }

    /** Records that [habit]'s scheduled ask fired on [date] ("yyyy-MM-dd"). */
    suspend fun setScheduleFired(habit: String, date: String) {
        dataStore.edit { prefs ->
            val raw = (prefs[KEY_SCHEDULE_LAST_FIRED] ?: emptySet())
                .filterNot { it.startsWith(habit + FIRED_SEP) }
                .toMutableSet()
            raw.add(habit + FIRED_SEP + date)
            prefs[KEY_SCHEDULE_LAST_FIRED] = raw.toSet()
        }
    }
}
