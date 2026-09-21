package com.example.tail.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.data.meal.Macronutrients
import com.example.tail.data.meal.MealLog
import com.example.tail.data.meal.MealLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

private const val TAG = "CompanionEntryReceiver"

/**
 * **Protocol v6** — BroadcastReceiver that lets a same-keystore companion
 * (Hoot) WRITE entries into Tail, closing the joint-habit loop: a habit
 * changed in either app now appears in the other for that day.
 *
 * Two actions, both mirroring what the in-app capture pipelines do:
 *
 *  - [ACTION_ADD_TEXT_ENTRY] — appends one line to a text-input habit's log
 *    (pills, water, electrolytes, ...). Mirrors
 *    [com.example.tail.ShareTextActivity]: log append + daily count
 *    increment, so the habit's value AND its text trail both update exactly
 *    as if the entry had been typed in Tail.
 *  - [ACTION_ADD_MEAL_LOG] — inserts a structured [MealLog] into a meal-type
 *    habit's log (title/kcal/macros/ingredients/summary), mirroring Tail's
 *    own vision-pipeline landing path via [MealLogRepository.addLog].
 *
 * **Entry-id preservation (echo contract):** when [EXTRA_ENTRY_ID] is present
 * on a meal push it becomes the [MealLog.id] verbatim. The v2 provider
 * surfaces `MealLog.id` as `entry_id`, so the companion can recognise the
 * Tail-side reflection of its own push when it pulls (`hoot:…` ids) and skip
 * it — no duplicate rows on either side.
 *
 * **Idempotency (retry guard):** every push carries a fresh UUID, but
 * broadcast redelivery is a thing; [CompanionEchoRegistry] records each
 * (habit, entry id, event minute) BEFORE the write and makes a re-delivered
 * broadcast a no-op instead of a double append.
 *
 * Security: manifest-declared with the `com.example.tail.permission.
 * TAIL_INTEGRATION` signature permission — only same-keystore apps can
 * deliver these broadcasts (same trust model as every other receiver in
 * this package).
 *
 * Concurrency: a shared [Mutex] serialises the file read-modify-write
 * cycles (same pattern as [HabitValueSetReceiver]) so rapid-fire pushes
 * never race on the same JSON file. Every successful write ends in
 * [com.example.tail.data.TailChangeLog.noteChange] (via the repositories),
 * so the companion's change feed sees the reflection immediately.
 */
class CompanionEntryReceiver : BroadcastReceiver() {

    companion object {
        // ── Action: text entry append (pills / water / electrolytes / ...) ──
        const val ACTION_ADD_TEXT_ENTRY = "com.example.tail.ACTION_ADD_TEXT_ENTRY"

        // ── Action: structured meal-log insert (meal-type habit) ────────────
        const val ACTION_ADD_MEAL_LOG = "com.example.tail.ACTION_ADD_MEAL_LOG"

        /** String: the target habit name (Tail keys habits by name). */
        const val EXTRA_HABIT_NAME = "EXTRA_HABIT_NAME"

        /** String: entry text (text action). */
        const val EXTRA_TEXT = "EXTRA_TEXT"

        /** Long: epoch-millis the entry happened at (event time, not receive time). */
        const val EXTRA_TIMESTAMP = "EXTRA_TIMESTAMP"

        /**
         * String: the companion's stable entry id (e.g. Hoot's `hoot:<uuid>`).
         * Meals: preserved as the [MealLog.id] (→ surfaces as `entry_id`, the
         * echo contract). Both kinds: the idempotency/retry key.
         */
        const val EXTRA_ENTRY_ID = "EXTRA_ENTRY_ID"

        /** String: JSON object with the meal fields (meal action). */
        const val EXTRA_MEAL_JSON = "EXTRA_MEAL_JSON"

        /** Serialises all companion-write file access (read-modify-write). */
        private val fileMutex = Mutex()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        val habitName = intent.getStringExtra(EXTRA_HABIT_NAME)?.trim()
        if (habitName.isNullOrBlank()) {
            Log.w(TAG, "Companion write without EXTRA_HABIT_NAME — ignoring (${intent.action})")
            return
        }
        val companionEntryId = intent.getStringExtra(EXTRA_ENTRY_ID)
        val timestampMs = if (intent.hasExtra(EXTRA_TIMESTAMP)) {
            intent.getLongExtra(EXTRA_TIMESTAMP, System.currentTimeMillis())
        } else {
            System.currentTimeMillis()
        }

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        scope.launch {
            try {
                // Retry guard FIRST: a re-delivered broadcast (same habit +
                // entry id + event minute) must be a no-op, never a double
                // append. remember() returns false when already recorded.
                val fresh = companionEntryId == null || CompanionEchoRegistry.remember(
                    appContext, habitName, companionEntryId, timestampMs
                )
                if (!fresh) {
                    Log.i(TAG, "Duplicate companion write for '$habitName' " +
                        "(entry=$companionEntryId) — skipping")
                    return@launch
                }
                when (intent.action) {
                    ACTION_ADD_TEXT_ENTRY -> handleTextEntry(
                        appContext, habitName,
                        intent.getStringExtra(EXTRA_TEXT), timestampMs, companionEntryId
                    )
                    ACTION_ADD_MEAL_LOG -> handleMealLog(
                        appContext, habitName,
                        intent.getStringExtra(EXTRA_MEAL_JSON), timestampMs, companionEntryId
                    )
                    else -> Log.w(TAG, "Unknown action ${intent.action} — ignoring")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Companion write failed for '$habitName'", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    // ── Text entry: append + count increment (mirrors ShareTextActivity) ────

    private suspend fun handleTextEntry(
        context: Context,
        habitName: String,
        text: String?,
        timestampMs: Long,
        companionEntryId: String?
    ) {
        if (text.isNullOrBlank()) {
            Log.w(TAG, "ACTION_ADD_TEXT_ENTRY for '$habitName' without text — ignoring")
            return
        }
        val settings = SettingsRepository(context).settingsFlow.first()
        val logUriStr = settings.textInputFileUris[habitName]
        if (logUriStr.isNullOrEmpty()) {
            Log.w(TAG, "Habit '$habitName' has no text log (not a text habit?) — ignoring")
            return
        }

        val moment = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())

        fileMutex.withLock {
            // 1. Append to the text log file (change broadcast + internal
            //    mirror happen inside the repository).
            TextInputRepository().appendTextEntry(
                Uri.parse(logUriStr), context, text,
                date = moment.toLocalDate(), time = moment.toLocalTime(),
                habitName = habitName
            )

            // 2. Increment the habit count in the phone DB (same behaviour as
            //    tapping the habit in-app) so the daily value reflects the
            //    companion's entry on THAT day.
            val phoneUriStr = settings.fileUri
            if (phoneUriStr.isNotEmpty()) {
                val habitsRepo = com.example.tail.data.HabitsRepository()
                val phoneUri = Uri.parse(phoneUriStr)
                val db = habitsRepo.ensureDaysExist(phoneUri, context)
                val updatedDb = habitsRepo.applyIncrementToDb(
                    db, habitName, 1, moment.toLocalDate()
                )
                habitsRepo.persistDatabase(phoneUri, context, updatedDb)
            }
        }
        Log.i(TAG, "Companion text entry appended to '$habitName' (entry=$companionEntryId)")
    }

    // ── Meal log: structured insert (mirrors the vision-pipeline path) ──────

    private suspend fun handleMealLog(
        context: Context,
        habitName: String,
        mealJson: String?,
        timestampMs: Long,
        companionEntryId: String?
    ) {
        if (mealJson.isNullOrBlank()) {
            Log.w(TAG, "ACTION_ADD_MEAL_LOG for '$habitName' without EXTRA_MEAL_JSON — ignoring")
            return
        }
        val settings = SettingsRepository(context).settingsFlow.first()
        if (habitName !in settings.mealHabits) {
            Log.w(TAG, "Habit '$habitName' is not a meal habit — ignoring meal write")
            return
        }

        val log = parseMealJson(habitName, mealJson, timestampMs, companionEntryId)
        if (log == null) {
            Log.w(TAG, "Unparseable EXTRA_MEAL_JSON for '$habitName' — ignoring")
            return
        }

        val moment = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())

        fileMutex.withLock {
            // Structured log insert (the vision-pipeline landing path).
            MealLogRepository(context).addLog(log)

            // The log is countedIncrement=true, so honour the contract: bump
            // the meal habit's daily count for THAT day (mirrors what Tail's
            // own capture pipeline does after creating a counted meal log).
            val phoneUriStr = settings.fileUri
            if (phoneUriStr.isNotEmpty()) {
                val habitsRepo = com.example.tail.data.HabitsRepository()
                val phoneUri = Uri.parse(phoneUriStr)
                val db = habitsRepo.ensureDaysExist(phoneUri, context)
                val updatedDb = habitsRepo.applyIncrementToDb(
                    db, habitName, 1, moment.toLocalDate()
                )
                habitsRepo.persistDatabase(phoneUri, context, updatedDb)
            }
        }
        Log.i(TAG, "Companion meal log added to '$habitName': ${log.title} (entry=$companionEntryId)")
    }

    /**
     * Companion meal JSON → [MealLog]. Tolerant of missing/blank fields
     * (defaults mirror Tail's manual-entry landing path). Recognised keys:
     * `title`, `summary`, `calories`, `protein_grams`, `carbs_grams`,
     * `fat_grams`, `ingredients` (JSON array), `is_vegan`, `health_notes`,
     * `transcript`, `is_manual`.
     *
     * [companionEntryId] becomes the [MealLog.id] when present — the echo
     * contract that lets the companion recognise its own rows on pull.
     */
    private fun parseMealJson(
        habitName: String,
        json: String,
        timestampMs: Long,
        companionEntryId: String?
    ): MealLog? = runCatching {
        val obj = JSONObject(json)
        val ingredients = mutableListOf<String>()
        obj.optJSONArray("ingredients")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr.optString(i).trim().takeIf { s -> s.isNotEmpty() }?.let { ingredients += it }
            }
        }
        MealLog(
            id = companionEntryId ?: "companion:${java.util.UUID.randomUUID()}",
            habitId = habitName,
            timestamp = timestampMs,
            title = obj.optString("title", "").ifBlank { "Meal" },
            summary = obj.optString("summary", "").takeIf { it.isNotBlank() },
            calories = obj.optInt("calories", 0),
            macronutrients = Macronutrients(
                proteinGrams = obj.optDouble("protein_grams", 0.0),
                carbsGrams = obj.optDouble("carbs_grams", 0.0),
                fatGrams = obj.optDouble("fat_grams", 0.0)
            ),
            ingredientsDetected = ingredients,
            isVeganVerified = obj.optBoolean("is_vegan", false),
            healthNotes = obj.optString("health_notes", "").takeIf { it.isNotBlank() },
            voiceTranscript = obj.optString("transcript", "").takeIf { it.isNotBlank() },
            isManual = obj.optBoolean("is_manual", true),
            countedIncrement = true
        )
    }.getOrNull()
}

/**
 * Append-only registry of writes Tail performed ON BEHALF of companions,
 * persisted in `files/companion_echo_registry.json`. Two jobs:
 *
 *  1. **Retry guard** — [remember] is called BEFORE the write and returns
 *     false for an already-recorded (habit, entry id, event minute), turning
 *     broadcast redelivery into a no-op.
 *  2. **Audit trail** — debugging "did the push land?" without digging
 *     through logcat.
 *
 * Capped (oldest evicted) and purely additive — never blocks user actions.
 */
object CompanionEchoRegistry {

    private const val FILE = "companion_echo_registry.json"
    private const val MAX_ENTRIES = 2000

    /**
     * Records a companion write. Returns false when this exact write
     * (habit + entry id + event minute) was already recorded — the caller
     * must then SKIP the write.
     */
    fun remember(context: Context, habitName: String, companionEntryId: String, timestampMs: Long): Boolean {
        val key = "$habitName|$companionEntryId|${timestampMs / 60_000}"
        return runCatching {
            val file = File(context.filesDir, FILE)
            val obj = if (file.exists()) JSONObject(file.readText()) else JSONObject()
            if (obj.has(key)) return false
            obj.put(key, System.currentTimeMillis())
            // Cap the registry: drop oldest entries beyond the window.
            if (obj.length() > MAX_ENTRIES) {
                val keys = mutableListOf<String>()
                for (k in obj.keys()) keys += k
                keys.sortBy { obj.optLong(it, 0L) }
                for (k in keys.take(obj.length() - MAX_ENTRIES)) obj.remove(k)
            }
            file.writeText(obj.toString())
            true
        }.getOrDefault(true)
    }
}
