package com.example.tail.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tail.ui.ACTION_HABIT_INCREMENTED
import com.example.tail.ui.EXTRA_HABIT_NAME
import com.example.tail.ui.HabitIncrementBus
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.applyDivider
import com.example.tail.data.buildTaskerStatsContent
import com.example.tail.data.dateString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

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

        /**
         * Protocol v2 — Optional Int extra carrying the number of minutes to
         * add instead of the default increment of 1.
         *
         * Sent by WAGS for resonance-breathing, meditation, and apnea sessions
         * (free holds, table training, progressive O₂, min breath) so Tail
         * records the actual session/hold duration rather than a simple
         * "did it" = 1.
         * If absent (or if the sending app is old), the receiver falls back to 1.
         */
        const val EXTRA_MINUTES = "EXTRA_MINUTES"
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

                // Protocol v2: resolve the increment amount from EXTRA_MINUTES.
                // If absent (old sender or count-based slot), default to 1.
                val hasMinutesExtra = intent.hasExtra(EXTRA_MINUTES)
                val amount = if (hasMinutesExtra) {
                    intent.getIntExtra(EXTRA_MINUTES, 1).coerceAtLeast(1)
                } else {
                    1
                }

                // Respect the "max 1" cap: if the habit is capped at 1 and today's
                // count is already >= 1, skip the increment entirely.
                // Minute-based increments (EXTRA_MINUTES present) bypass this cap
                // because they are cumulative durations, not binary "did it" counts.
                // We check hasMinutesExtra (not amount == 1) so that a 1-minute
                // hold is still recorded even if the habit is configured as max-1.
                if (!hasMinutesExtra && habitName in settings.maxOneHabits) {
                    val db = habitsRepo.loadDatabase(uri, appContext)
                    val todayStr = java.time.LocalDate.now().toString()
                    val currentCount = db[habitName]?.get(todayStr) ?: 0
                    if (currentCount >= 1) {
                        Log.i(TAG, "Skipping increment for '$habitName' — already at max 1 for today")
                        return@launch
                    }
                }

                // "Feed max1" conditional sub-setting: capture the source's count
                // BEFORE incrementing so the cap can tell first-of-day increments
                // from repeat ones.
                val sourceCountBefore = if (
                    habitName in settings.conditionalHabits &&
                    habitName in settings.conditionalFeedMaxOneHabits
                ) {
                    habitsRepo.loadDatabase(uri, appContext)[habitName]
                        ?.get(java.time.LocalDate.now().toString()) ?: 0
                } else -1

                habitsRepo.incrementHabit(uri, appContext, habitName, amount)
                HabitIncrementBus.emit(habitName)
                Log.i(TAG, "Incremented habit '$habitName' by $amount via IPC broadcast")

                // Record timestamp for IPC-triggered increment
                try {
                    val tsRepo = HabitTimestampRepository(appContext)
                    tsRepo.addTimestamp(habitName)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record timestamp for '$habitName': ${e.message}")
                }

                // Also increment any conditional linked habits (mirrors HabitViewModel logic).
                // Each link feeds the value configured for it: Points (the primary
                // count) by default, or one of the linked habit's raw secondary
                // slots when that habit actually has it available.
                if (habitName in settings.conditionalHabits) {
                    val linkedHabits = settings.conditionalLinkedHabits[habitName] ?: emptySet()
                    val todayStr = java.time.LocalDate.now().toString()
                    for (linkedName in linkedHabits) {
                        val valueKey = com.example.tail.data.effectiveConditionalLinkValueKey(
                            settings.conditionalLinkValues, settings.secondaryValueHabits,
                            settings.chessComHabitLinks, habitName, linkedName
                        )
                        val targetKey = com.example.tail.data.conditionalLinkStorageKey(linkedName, valueKey)
                        // "Feed max1" cap: skip Points feeds when this source
                        // already fed its 1 point today (primary/Points feeds only)
                        if (targetKey == linkedName && sourceCountBefore > 0) {
                            Log.i(TAG, "Skipping linked increment for '$linkedName' — '$habitName' already fed max1 point today")
                            continue
                        }
                        // Respect the "max 1" cap on linked habits (primary/Points feeds only)
                        if (targetKey == linkedName && linkedName in settings.maxOneHabits) {
                            val db = habitsRepo.loadDatabase(uri, appContext)
                            val currentCount = db[linkedName]?.get(todayStr) ?: 0
                            if (currentCount >= 1) {
                                Log.i(TAG, "Skipping linked increment for '$linkedName' — already at max 1")
                                continue
                            }
                        }
                        habitsRepo.incrementHabitForDate(uri, appContext, targetKey, 1, java.time.LocalDate.now())
                        HabitIncrementBus.emit(linkedName)
                        Log.i(TAG, "Incremented linked habit '$linkedName' (conditional on '$habitName', feeds $valueKey)")

                        // Record timestamp for the linked habit too
                        try {
                            HabitTimestampRepository(appContext).addTimestamp(linkedName)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to record timestamp for linked '$linkedName': ${e.message}")
                        }
                    }
                }

                // Update the Tasker stats file so external apps see the new total immediately
                val taskerUri = settings.taskerFileUri
                if (taskerUri.isNotEmpty()) {
                    writeTaskerFile(appContext, habitsRepo, uri, taskerUri, settings.habitDividers, settings.noPointsHabits, settings.invertedBinaryHabits)
                }

                // Broadcast a generic "habit incremented" event for same-keystore listeners
                try {
                    val broadcastIntent = Intent(ACTION_HABIT_INCREMENTED).apply {
                        putExtra(EXTRA_HABIT_NAME, habitName)
                    }
                    appContext.sendBroadcast(
                        broadcastIntent,
                        "com.example.tail.permission.TAIL_INTEGRATION"
                    )
                    Log.d(TAG, "Sent ACTION_HABIT_INCREMENTED broadcast for '$habitName'")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send habit-incremented broadcast: ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to increment habit '$habitId': ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Writes today's habit totals to the Tasker relay txt file.
     * Mirrors the logic in HabitViewModel.writeTaskerFile() so external apps
     * (e.g. Tasker) see an up-to-date count immediately after an IPC increment.
     */
    private suspend fun writeTaskerFile(
        context: Context,
        habitsRepo: HabitsRepository,
        habitsUri: Uri,
        taskerUriString: String,
        dividers: Map<String, Int>,
        noPointsHabits: Set<String>,
        invertedBinaryHabits: Set<String>
    ) {
        try {
            val db = habitsRepo.loadDatabase(habitsUri, context)
            // Shared helper excludes "Don't affect points" habits (e.g. Garmin imports)
            val content = buildTaskerStatsContent(db, dividers, noPointsHabits, invertedBinaryHabits = invertedBinaryHabits)

            val taskerUri = Uri.parse(taskerUriString)
            context.contentResolver.openOutputStream(taskerUri, "wt")?.use { stream ->
                stream.bufferedWriter().use { it.write(content) }
            }
            Log.i(TAG, "Tasker file updated")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write Tasker file: ${e.message}")
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
