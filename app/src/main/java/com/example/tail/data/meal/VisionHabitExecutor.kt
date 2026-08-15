package com.example.tail.data.meal

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.AppSettings
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.buildTaskerStatsContent
import com.example.tail.ui.HabitIncrementBus
import java.time.LocalDate

private const val TAG = "VisionHabitExec"

/**
 * Executes and validates habit-increment actions proposed by the vision
 * pipeline (both the tandem teaching flow and the smart auto-detection
 * on plain photo captures).
 *
 * The LLM never increments anything directly — every proposal is first
 * validated against the real habit/subtype configuration via
 * [resolveHabitAction], then executed here with the same side-effects a
 * voice increment performs (subtype breakdown, timestamp, Tasker file,
 * increment-bus event).
 */
object VisionHabitExecutor {

    /**
     * Validates an LLM-proposed habit (and optional subtype) against the
     * real habit configuration. Matching is case-insensitive and exact.
     *
     * @return The normalized (real) habit name + subtype pair, or null
     *         when the habit (or subtype) does not exist.
     */
    fun resolveHabitAction(
        settings: AppSettings,
        habitName: String?,
        subtypeName: String?
    ): Pair<String, String?>? {
        if (habitName.isNullOrBlank()) return null

        val allHabits = (settings.habitScreens.flatMap { it.habitNames } + settings.habitOrder)
            .filter { it.isNotBlank() && !it.startsWith("app_link:") }
            .distinct()

        val realHabit = allHabits.firstOrNull { it.equals(habitName.trim(), ignoreCase = true) }
            ?: return null

        val subtypes = settings.habitSubtypes[realHabit].orEmpty()
        val realSubtype = if (subtypeName.isNullOrBlank()) null
            else subtypes.firstOrNull { it.equals(subtypeName.trim(), ignoreCase = true) }

        return realHabit to realSubtype
    }

    /**
     * Builds the "available habits" context block injected into vision
     * prompts so the LLM knows the only valid habit/subtype names it may
     * propose. Returns an empty string when no habits are configured.
     */
    fun buildHabitPrompt(settings: AppSettings): String {
        val allHabits = (settings.habitScreens.flatMap { it.habitNames } + settings.habitOrder)
            .filter { it.isNotBlank() && !it.startsWith("app_link:") }
            .distinct()
        if (allHabits.isEmpty()) return ""
        return allHabits.joinToString("\n") { habit ->
            val subtypes = settings.habitSubtypes[habit].orEmpty()
            if (subtypes.isEmpty()) "- $habit"
            else "- $habit (subtypes: ${subtypes.joinToString(", ")})"
        }
    }

    /**
     * Increments [habitName] by [amount], records the subtype breakdown
     * and timestamp, updates the Tasker stats file, and emits on the
     * [HabitIncrementBus] — mirroring what the smart voice service does
     * for a spoken increment.
     *
     * @return null on success, or a human-readable error description.
     */
    suspend fun execute(
        context: Context,
        settings: AppSettings,
        habitName: String,
        subtypeName: String?,
        amount: Int
    ): String? {
        if (settings.fileUri.isEmpty()) {
            return "No habits file configured"
        }
        if (amount <= 0) {
            return "Invalid amount $amount"
        }
        return try {
            val uri = Uri.parse(settings.fileUri)
            val habitsRepo = HabitsRepository()
            habitsRepo.incrementHabit(uri, context, habitName, amount)
            Log.i(TAG, "Incremented habit '$habitName' by $amount via vision pipeline")

            if (subtypeName != null) {
                try {
                    SubtypeDataRepository(context).addToDate(
                        habitName,
                        LocalDate.now().toString(),
                        mapOf(subtypeName to amount)
                    )
                    Log.i(TAG, "Saved subtype breakdown for '$habitName': $subtypeName → $amount")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to save subtype data for '$habitName': ${e.message}")
                }
            }

            try {
                HabitTimestampRepository(context).addTimestamp(habitName)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record timestamp for '$habitName': ${e.message}")
            }

            if (settings.taskerFileUri.isNotEmpty()) {
                try {
                    val db = habitsRepo.loadDatabase(uri, context)
                    val content = buildTaskerStatsContent(db, settings.habitDividers, settings.noPointsHabits)
                    context.contentResolver.openOutputStream(Uri.parse(settings.taskerFileUri), "wt")?.use { stream ->
                        stream.bufferedWriter().use { it.write(content) }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to write Tasker file: ${e.message}")
                }
            }

            HabitIncrementBus.emit(habitName)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute vision habit action for '$habitName'", e)
            e.message ?: "Unknown error"
        }
    }
}
