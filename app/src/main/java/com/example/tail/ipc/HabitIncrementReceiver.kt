package com.example.tail.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tail.ui.EXTRA_SOURCE
import com.example.tail.ui.HabitIncrementBus
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.applyDivider
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

        /**
         * Protocol v5 — Optional Long extra carrying the epoch-millis moment
         * the increment actually HAPPENED at (e.g. the exact time a question
         * was answered in Inuit), used instead of "now" when recording the
         * habit's timestamps. This keeps the schedule timeline accurate even
         * if broadcast delivery is delayed across a midnight boundary, and
         * stamps the correct day for late-delivered increments.
         */
        const val EXTRA_TIMESTAMP = "EXTRA_TIMESTAMP"

        /**
         * Protocol v3 — Optional Int extra carrying the number of SESSIONS to
         * add to the habit's PRIMARY value.
         *
         * Sent by WAGS for the sessions-primary apnea habits (free holds,
         * O₂/CO₂ tables, progressive O₂, min breath). Together with
         * [EXTRA_MINUTES] the receiver performs ONE atomic write: +sessions on
         * the habit's own key (the primary/session count) and +minutes on its
         * first-class `minutes:<habit>` slot. When absent, the receiver falls
         * back to the v2 behaviour ([EXTRA_MINUTES] as the primary amount).
         */
        const val EXTRA_SESSIONS = "EXTRA_SESSIONS"
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

        // Protocol v4 — echo suppression: remember which app sent this increment
        // so the outbound ACTION_HABIT_INCREMENTED broadcast can carry it back.
        val sourcePackage = intent.getStringExtra(EXTRA_SOURCE)

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

                // Protocol v3: sessions-primary increment (Wags apnea and
                // breathing slots). ONE atomic write: +sessions on the habit's
                // own key (the primary value) and +minutes on its first-class
                // minutes: slot — exactly the layout incrementHabitWithMinutes
                // persists. Timestamps follow the SESSION count (one per
                // session), not the minutes.
                //
                // sessions = 0 is the MINUTES-ONLY variant: a possibly signed
                // minutes delta that touches only the minutes: slot (Wags
                // duration corrections, and ended-early sessions whose minutes
                // still count but do not tick the session counter).
                if (intent.hasExtra(EXTRA_SESSIONS)) {
                    val sessions = intent.getIntExtra(EXTRA_SESSIONS, 1).coerceAtLeast(0)
                    val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                    when {
                        sessions == 0 -> habitsRepo.adjustHabitMinutesSlot(uri, appContext, habitName, minutes)
                        minutes > 0 -> habitsRepo.incrementHabitWithMinutes(uri, appContext, habitName, minutes, sessions)
                        else -> habitsRepo.incrementHabit(uri, appContext, habitName, sessions)
                    }
                    HabitIncrementBus.emit(habitName)
                    Log.i(
                        TAG,
                        "Incremented habit '$habitName' by $sessions session(s) + $minutes minute(s) via IPC broadcast"
                    )
                    if (sessions > 0) {
                        try {
                            val tsRepo = HabitTimestampRepository(appContext)
                            val today = java.time.LocalDate.now()
                            val now = HabitTimestampRepository.nowTime()
                            tsRepo.addTimestamps(habitName, sessions, today, now)
                            // Sessions carried minutes (timer-fed habit): record
                            // them AT this timestamp so the timestamp editor can
                            // show/edit per-timestamp minutes for minutes-primary
                            // habits.
                            if (minutes > 0) {
                                tsRepo.addMinutesAtTime(habitName, today, now, minutes)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to record timestamp for '$habitName': ${e.message}")
                        }
                    }
                    HabitIncrementAnnouncer.announce(appContext, habitName, sessions, sourcePackage)
                    return@launch
                }

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

                // Conditional feeds: capture the source's count BEFORE
                // incrementing — needed both for the "feed max1" cap (first
                // increment of the day) and for the "feed points" divider
                // delta (points(before+amount) - points(before)).
                val sourceCountBefore = if (habitName in settings.conditionalHabits) {
                    habitsRepo.loadDatabase(uri, appContext)[habitName]
                        ?.get(java.time.LocalDate.now().toString()) ?: 0
                } else -1

                habitsRepo.incrementHabit(uri, appContext, habitName, amount)
                HabitIncrementBus.emit(habitName)
                Log.i(TAG, "Incremented habit '$habitName' by $amount via IPC broadcast")

                // Record timestamp for IPC-triggered increment — one per unit of
                // amount so the timestamp editor's increment amounts match the
                // day's count even for external app integration. Protocol v5:
                // when the sender provides the epoch-millis moment the event
                // happened (Inuit answer time), stamp THAT date/time instead
                // of "now" so late deliveries land on the correct day.
                try {
                    val tsRepo = HabitTimestampRepository(appContext)
                    val eventMs = if (intent.hasExtra(EXTRA_TIMESTAMP)) {
                        intent.getLongExtra(EXTRA_TIMESTAMP, 0L)
                    } else 0L
                    if (eventMs > 0L) {
                        val zdt = java.time.Instant.ofEpochMilli(eventMs)
                            .atZone(java.time.ZoneId.systemDefault())
                        tsRepo.addTimestamps(
                            habitName,
                            amount,
                            zdt.toLocalDate(),
                            zdt.toLocalTime().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                        )
                    } else {
                        tsRepo.addTimestamps(habitName, amount)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record timestamp for '$habitName': ${e.message}")
                }

                // Also increment any conditional linked habits (mirrors HabitViewModel logic).
                // Each link feeds the value configured for it: Points (the primary
                // count) by default, or one of the linked habit's raw secondary
                // slots when that habit actually has it available. The feed
                // amount follows the manual tap path exactly: "feed points"
                // sources feed their divider-applied POINTS delta, others the
                // raw amount, with the "feed max1" cap on Points targets.
                if (habitName in settings.conditionalHabits) {
                    val linkedHabits = settings.conditionalLinkedHabits[habitName] ?: emptySet()
                    val todayStr = java.time.LocalDate.now().toString()
                    val feedPoints = habitName in settings.conditionalFeedPointsHabits
                    val sourceDivider = settings.habitDividers[habitName] ?: 1
                    for (linkedName in linkedHabits) {
                        val valueKey = com.example.tail.data.effectiveConditionalLinkValueKey(
                            settings.conditionalLinkValues, settings.secondaryValueHabits,
                            settings.chessComHabitLinks, habitName, linkedName
                        )
                        val targetKey = com.example.tail.data.conditionalLinkStorageKey(linkedName, valueKey)
                        val baseFeedAmount = com.example.tail.data.conditionalTapFeedAmount(
                            sourceCountBefore, amount, feedPoints, sourceDivider
                        )
                        // "Feed max1" cap: skip Points feeds when this source
                        // already fed its 1 point today (primary/Points feeds only)
                        val feedAmount = if (
                            targetKey == linkedName &&
                            habitName in settings.conditionalFeedMaxOneHabits
                        ) {
                            com.example.tail.data.conditionalCappedFeedAmount(sourceCountBefore, baseFeedAmount)
                        } else baseFeedAmount
                        if (feedAmount == 0) continue
                        // Respect the "max 1" cap on linked habits (primary/Points feeds only)
                        if (targetKey == linkedName && linkedName in settings.maxOneHabits) {
                            val db = habitsRepo.loadDatabase(uri, appContext)
                            val currentCount = db[linkedName]?.get(todayStr) ?: 0
                            if (currentCount >= 1) {
                                Log.i(TAG, "Skipping linked increment for '$linkedName' — already at max 1")
                                continue
                            }
                        }
                        habitsRepo.incrementHabitForDate(uri, appContext, targetKey, feedAmount, java.time.LocalDate.now())
                        HabitIncrementBus.emit(linkedName)
                        Log.i(TAG, "Incremented linked habit '$linkedName' (conditional on '$habitName', feeds $valueKey +$feedAmount)")

                        // Record timestamp for the linked habit too
                        try {
                            HabitTimestampRepository(appContext).addTimestamp(linkedName)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to record timestamp for linked '$linkedName': ${e.message}")
                        }
                    }
                }


                // Broadcast a generic "habit incremented" event for same-keystore listeners
                HabitIncrementAnnouncer.announce(appContext, habitName, amount, sourcePackage)
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
