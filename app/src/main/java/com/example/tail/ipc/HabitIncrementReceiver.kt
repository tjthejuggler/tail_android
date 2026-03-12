package com.example.tail.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "HabitIncrementReceiver"

/**
 * BroadcastReceiver that allows a same-keystore app to increment a habit's today count.
 *
 * Action:  com.example.tail.ACTION_INCREMENT_HABIT
 * Extra:   EXTRA_HABIT_ID  — the habit name (String) or 0-based index (Int) to increment
 *
 * Security: declared in the manifest with android:permission pointing to the
 * com.example.tail.permission.TAIL_INTEGRATION signature permission, so only apps
 * signed with the same keystore can send this broadcast.
 */
class HabitIncrementReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INCREMENT_HABIT = "com.example.tail.ACTION_INCREMENT_HABIT"
        /** String extra: the habit name to increment (preferred). */
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
    }

    // Use a SupervisorJob scope so one failed coroutine doesn't cancel the others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INCREMENT_HABIT) return

        // EXTRA_HABIT_ID may be sent as a String (habit name) or Int (0-based index).
        val habitId: String? = when {
            intent.hasExtra(EXTRA_HABIT_ID) -> {
                val raw = intent.extras?.get(EXTRA_HABIT_ID)
                when (raw) {
                    is String -> raw.takeIf { it.isNotBlank() }
                    is Int -> raw.toString() // will be resolved to name below
                    else -> null
                }
            }
            else -> null
        }

        if (habitId == null) {
            Log.w(TAG, "Received $ACTION_INCREMENT_HABIT with no valid EXTRA_HABIT_ID — ignoring")
            return
        }

        // goAsync() lets us do I/O without the system killing the receiver after onReceive returns.
        val pendingResult = goAsync()

        // Always use applicationContext: it holds the persisted SAF URI permissions
        // that were granted when the user picked the file in the main app.
        val appContext = context.applicationContext

        scope.launch {
            try {
                val settingsRepo = SettingsRepository(appContext)
                val habitsRepo = HabitsRepository()
                val settings = settingsRepo.settingsFlow.first()

                val fileUriString = settings.fileUri
                if (fileUriString.isEmpty()) {
                    Log.w(TAG, "No habits file URI configured — cannot increment '$habitId'")
                    return@launch
                }

                // Resolve habitId: if it's a pure integer string, treat it as an index into
                // the effective habit order; otherwise treat it as a habit name directly.
                val habitName: String = resolveHabitName(habitId, settings) ?: run {
                    Log.w(TAG, "Could not resolve habit '$habitId' — ignoring")
                    return@launch
                }

                val uri = Uri.parse(fileUriString)
                habitsRepo.incrementHabit(uri, appContext, habitName, 1)
                Log.i(TAG, "Incremented habit '$habitName' via IPC broadcast")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to increment habit '$habitId': ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Resolves [habitId] to a habit name.
     * - If [habitId] is a pure integer string, looks up the name at that index in the
     *   effective habit order (screens → flat order → HABIT_ORDER).
     * - Otherwise returns [habitId] as-is (assumed to already be a habit name).
     */
    private fun resolveHabitName(
        habitId: String,
        settings: com.example.tail.data.AppSettings
    ): String? {
        val index = habitId.toIntOrNull()
        if (index == null) {
            // It's already a name string
            return habitId
        }
        // It's an index — resolve to name
        val order = when {
            settings.habitScreens.isNotEmpty() ->
                settings.habitScreens.flatMap { it.habitNames }
            settings.habitOrder.isNotEmpty() -> settings.habitOrder
            else -> com.example.tail.data.HABIT_ORDER
        }
        return order.getOrNull(index)
    }
}
