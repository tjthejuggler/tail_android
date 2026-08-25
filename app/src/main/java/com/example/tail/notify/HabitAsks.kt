package com.example.tail.notify

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.HabitNotification
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.NotificationStore
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.ui.HabitIncrementBus
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime

private const val TAG = "HabitAsks"

/**
 * Shared logic for the habit-ask notification system, used by both the
 * background receivers (system-notification answers, scheduled alarms) and
 * the in-app catch-up path in [com.example.tail.ui.HabitViewModel].
 */
object HabitAsks {

    /** Ask id for a movie ask, embedding the existing handled-marker. */
    fun movieAskId(marker: String): String = "movie:$marker"

    /**
     * Posts an informational notice into the notification system (in-app
     * center + system notification). Unlike asks it has no Yes/No effect —
     * acknowledging it just removes it everywhere. Used for things the user
     * must not miss, e.g. quick-capture failures that previously disappeared
     * with a transient toast.
     *
     * @param id Stable unique id; a duplicate id is not re-added (no-op).
     * @param title Headline, e.g. "📸 Quick capture failed".
     * @param message Body text explaining what failed.
     * @param habitLabel Small label shown in the center (defaults to "Notice").
     */
    suspend fun postInfo(
        appContext: Context,
        id: String,
        title: String,
        message: String,
        habitLabel: String = "Notice"
    ): HabitNotification {
        val notice = HabitNotification(
            id = id,
            habitName = habitLabel,
            type = HabitNotification.TYPE_INFO,
            title = title,
            question = message,
            createdAtMillis = System.currentTimeMillis()
        )
        NotificationStore(appContext).add(notice)
        HabitNotifier.postAsk(appContext, notice)
        Log.i(TAG, "Posted info notification '$id': $title")
        return notice
    }

    /**
     * Fires the scheduled daily ask for [habitName]: creates the store record,
     * posts the system notification and marks the habit as fired today.
     * Skips (returns null) when this habit already fired today — this is what
     * keeps alarms, boot catch-up and app-open catch-up from double-asking.
     */
    suspend fun fireScheduledAsk(
        appContext: Context,
        habitName: String,
        nowMillis: Long = System.currentTimeMillis()
    ): HabitNotification? {
        val store = NotificationStore(appContext)
        val today = LocalDate.now().toString()
        val lastFired = store.scheduleLastFired()[habitName]
        if (lastFired == today) return null

        store.setScheduleFired(habitName, today)
        val ask = HabitNotification(
            id = HabitNotification.scheduleId(habitName, today),
            habitName = habitName,
            type = HabitNotification.TYPE_SCHEDULE,
            title = habitName,
            question = "Did you do \"$habitName\"?",
            createdAtMillis = nowMillis
        )
        store.add(ask)
        HabitNotifier.postAsk(appContext, ask)
        Log.i(TAG, "Fired scheduled ask for '$habitName'")
        return ask
    }

    /**
     * Applies the effect of answering [ask] from a background context
     * (system-notification action). The caller is responsible for removing the
     * record from the store and cancelling the system notification.
     *
     * - Movie + Yes  → appends the title as a text entry at the stored entry time
     * - Movie + No   → nothing (marker still persisted so it is never re-asked)
     * - Schedule + Yes → increments today's count by 1 (respecting max-1)
     * - Schedule + No  → nothing
     */
    suspend fun applyAnswer(appContext: Context, ask: HabitNotification, yes: Boolean) {
        // Informational notices carry no effect — the caller removes the
        // record and cancels the system notification (dismiss-everywhere).
        if (ask.type == HabitNotification.TYPE_INFO) return
        val settingsRepo = SettingsRepository(appContext)
        if (ask.type == HabitNotification.TYPE_MOVIE) {
            // Persist the handled marker (id is "movie:<marker>") so the movie
            // is never re-asked, no matter where it was answered.
            try {
                val marker = ask.id.removePrefix("movie:")
                val handled = settingsRepo.getMoviePromptHandled()
                settingsRepo.saveMoviePromptHandled(handled + marker)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save movie handled marker: ${e.message}")
            }
            if (!yes) return
            val settings = settingsRepo.settingsFlow.first()
            val uriStr = settings.textInputFileUris[ask.habitName]
            if (uriStr.isNullOrEmpty()) {
                Log.w(TAG, "No text log URI for '${ask.habitName}' — cannot log movie")
                return
            }
            val (payloadTime, payloadMinutes) = HabitNotification.parseMoviePayload(ask.payload)
            val time = payloadTime?.let { parseTime(it) } ?: LocalTime.now()
            // Carry the watch length onto the logged entry so the minutes
            // slot fills from the annotation at the next sync.
            val text = if (payloadMinutes > 0) "${ask.title} ($payloadMinutes min)" else ask.title
            try {
                TextInputRepository().appendTextEntry(
                    Uri.parse(uriStr), appContext, text, null, time, ask.habitName
                )
                Log.i(TAG, "Logged movie '${ask.title}' for '${ask.habitName}' from system notification")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to log movie answer: ${e.message}", e)
                return
            }
            // Mirror the in-app confirm path (HabitViewModel.saveTextEntry):
            // a confirmed movie also increments the habit count so the day
            // registers as watched, records the increment timestamp and
            // notifies any running UI. IMDb rating/runtime enrichment can be
            // filled in afterwards via the IMDb backlog buttons in settings.
            val habitsUriStr = settings.fileUri
            if (habitsUriStr.isEmpty()) {
                Log.w(TAG, "No habits file URI configured — cannot increment '${ask.habitName}'")
                return
            }
            val habitsUri = Uri.parse(habitsUriStr)
            val habitsRepo = HabitsRepository()
            // Respect the "max 1" cap: skip when already done today.
            if (ask.habitName in settings.maxOneHabits) {
                val db = habitsRepo.loadDatabase(habitsUri, appContext)
                val todayCount = db[ask.habitName]?.get(LocalDate.now().toString()) ?: 0
                if (todayCount >= 1) {
                    Log.i(TAG, "Skipping movie increment for '${ask.habitName}' — already at max 1 today")
                    return
                }
            }
            habitsRepo.incrementHabit(habitsUri, appContext, ask.habitName, 1)
            HabitIncrementBus.emit(ask.habitName)
            try {
                HabitTimestampRepository(appContext).addTimestamp(ask.habitName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record timestamp for '${ask.habitName}': ${e.message}")
            }
            Log.i(TAG, "Incremented '${ask.habitName}' for confirmed movie")
            com.example.tail.ipc.HabitIncrementAnnouncer.announce(appContext, ask.habitName, 1)
            return
        }

        if (!yes) return
        val settings = settingsRepo.settingsFlow.first()
        val uriStr = settings.fileUri
        if (uriStr.isEmpty()) {
            Log.w(TAG, "No habits file URI configured — cannot increment '${ask.habitName}'")
            return
        }
        val uri = Uri.parse(uriStr)
        val habitsRepo = HabitsRepository()
        // Respect the "max 1" cap: skip when already done today.
        if (ask.habitName in settings.maxOneHabits) {
            val db = habitsRepo.loadDatabase(uri, appContext)
            val todayCount = db[ask.habitName]?.get(LocalDate.now().toString()) ?: 0
            if (todayCount >= 1) {
                Log.i(TAG, "Skipping answer increment for '${ask.habitName}' — already at max 1 today")
                return
            }
        }
        habitsRepo.incrementHabit(uri, appContext, ask.habitName, 1)
        HabitIncrementBus.emit(ask.habitName)
        try {
            HabitTimestampRepository(appContext).addTimestamp(ask.habitName)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to record timestamp for '${ask.habitName}': ${e.message}")
        }
        Log.i(TAG, "Incremented '${ask.habitName}' from notification answer")
        com.example.tail.ipc.HabitIncrementAnnouncer.announce(appContext, ask.habitName, 1)
    }

    private fun parseTime(hms: String): LocalTime? {
        return try {
            val parts = hms.split(":")
            LocalTime.of(
                parts.getOrNull(0)?.toIntOrNull() ?: return null,
                parts.getOrNull(1)?.toIntOrNull() ?: 0,
                parts.getOrNull(2)?.toIntOrNull() ?: 0
            )
        } catch (e: Exception) {
            null
        }
    }
}
