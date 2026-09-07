package com.example.tail.data.meal

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.AppSettings
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SubtypeDataRepository
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
 * voice increment performs (subtype breakdown, timestamp,
 * increment-bus event).
 */
object VisionHabitExecutor {

    /**
     * Habits eligible for the camera/vision pipeline. When the user has
     * flagged specific habits with the "Camera" edit-mode setting, ONLY those
     * are offered to the LLM as choices (making its decision easy); with none
     * flagged, every habit stays eligible (legacy behaviour).
     */
    fun cameraEligibleHabits(settings: AppSettings): List<String> {
        val allHabits = (settings.habitScreens.flatMap { it.habitNames } + settings.habitOrder)
            .filter { it.isNotBlank() && !it.startsWith("app_link:") }
            .distinct()
        val flagged = settings.cameraHabits
        return if (flagged.isEmpty()) allHabits else allHabits.filter { it in flagged }
    }

    /**
     * Validates an LLM-proposed habit (and optional subtype) against the
     * camera-eligible habit configuration. Matching is case-insensitive and
     * exact. An explicit "NONE" answer (the LLM is certain no available
     * habit matches) resolves to null.
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
        if (habitName.trim().equals("NONE", ignoreCase = true)) return null

        val allHabits = cameraEligibleHabits(settings)

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
     * propose — restricted to the camera-eligible habits (see
     * [cameraEligibleHabits]). Returns an empty string when no habits are
     * eligible.
     */
    fun buildHabitPrompt(settings: AppSettings): String {
        val allHabits = cameraEligibleHabits(settings)
        if (allHabits.isEmpty()) return ""
        return allHabits.joinToString("\n") { habit ->
            val subtypes = settings.habitSubtypes[habit].orEmpty()
            if (subtypes.isEmpty()) "- $habit"
            else "- $habit (subtypes: ${subtypes.joinToString(", ")})"
        }
    }

    /**
     * Increments [habitName] by [amount], records the subtype breakdown
     * and timestamp, and emits on the
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
        QcDiag.log(
            "INCREMENT",
            "execute: habit='$habitName' subtype=${subtypeName ?: "none"} amount=$amount " +
                "fileUri=${if (settings.fileUri.isEmpty()) "EMPTY" else "set"}"
        )
        if (settings.fileUri.isEmpty()) {
            QcDiag.error("INCREMENT", "execute ABORT: no habits file configured (fileUri empty)")
            return "No habits file configured"
        }
        if (amount <= 0) {
            QcDiag.error("INCREMENT", "execute ABORT: invalid amount $amount")
            return "Invalid amount $amount"
        }
        return try {
            val uri = Uri.parse(settings.fileUri)
            val habitsRepo = HabitsRepository()
            habitsRepo.incrementHabit(uri, context, habitName, amount)
            QcDiag.log("INCREMENT", "execute: incrementHabit OK '$habitName' +$amount")
            Log.i(TAG, "Incremented habit '$habitName' by $amount via vision pipeline")

            if (subtypeName != null) {
                try {
                    SubtypeDataRepository(context).addToDate(
                        habitName,
                        LocalDate.now().toString(),
                        mapOf(subtypeName to amount)
                    )
                    QcDiag.log(
                        "INCREMENT",
                        "execute: subtype breakdown saved '$habitName'/$subtypeName → $amount"
                    )
                    Log.i(TAG, "Saved subtype breakdown for '$habitName': $subtypeName → $amount")
                } catch (e: Exception) {
                    QcDiag.error(
                        "INCREMENT",
                        "execute: subtype save FAILED for '$habitName/$subtypeName': ${e.message}",
                        e
                    )
                    Log.w(TAG, "Failed to save subtype data for '$habitName': ${e.message}")
                }
            }

            try {
                HabitTimestampRepository(context).addTimestamp(habitName)
                QcDiag.log("INCREMENT", "execute: timestamp recorded for '$habitName'")
            } catch (e: Exception) {
                QcDiag.error(
                    "INCREMENT",
                    "execute: timestamp record FAILED for '$habitName': ${e.message}",
                    e
                )
                Log.w(TAG, "Failed to record timestamp for '$habitName': ${e.message}")
            }


            HabitIncrementBus.emit(habitName)
            QcDiag.log("INCREMENT", "execute: HabitIncrementBus emitted '$habitName'")
            null
        } catch (e: Exception) {
            QcDiag.error(
                "INCREMENT",
                "execute FAILED for '$habitName': ${e.javaClass.simpleName}: ${e.message}",
                e
            )
            Log.e(TAG, "Failed to execute vision habit action for '$habitName'", e)
            e.message ?: "Unknown error"
        }
    }
}
