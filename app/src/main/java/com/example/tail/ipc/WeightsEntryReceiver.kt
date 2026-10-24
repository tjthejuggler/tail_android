package com.example.tail.ipc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.example.tail.data.HabitIncrementBus
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TailChangeLog
import com.example.tail.data.WeightsExerciseRepository
import com.example.tail.data.WeightsRecordsRepository
import com.example.tail.data.dateString
import com.example.tail.data.secondaryValueKey
import com.example.tail.data.secondaryValueSlotKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

private const val TAG = "WeightsEntryReceiver"

/**
 * **Protocol v6 extension (Kenga weights)** — BroadcastReceiver that logs
 * one weights-habit set on a mapped Tail habit from a same-keystore
 * companion app (Kenga), mirroring the repository-level semantics of
 * `HabitViewModel.saveWeightsEntry` without touching the ViewModel.
 *
 * Action: `com.example.tail.ACTION_LOG_WEIGHTS_ENTRY`
 * Extras:
 *  - `EXTRA_HABIT_ID`       — habit name (String). Must exist in the habits
 *    DB AND be in `settings.weightsHabits`, otherwise the broadcast is
 *    logged and ignored.
 *  - `EXTRA_EXERCISE_NAME`  — exercise/machine name (String, optional; when
 *    blank no exercise-name sidecar write and no PR check happens).
 *  - `EXTRA_WEIGHT_GRAMS`   — set weight in grams (Int, ≥ 0).
 *  - `EXTRA_REPS`           — reps in the set (Int; NEGATIVE allowed for
 *    corrections, see below).
 *  - `EXTRA_MACHINE`        — true = machine slot, false = free-weight slot.
 *  - `EXTRA_DATE`           — optional `yyyy-MM-dd` (String), default today.
 *  - `EXTRA_SOURCE`         — informational sender label, e.g. "kenga".
 *
 * Behaviour (exactly [HabitsRepository.applyWeightsSlotsToDb] semantics,
 * applied as one atomic read-modify-write under the shared [fileMutex]):
 *  - weight slot: day MAX-merge (heaviest set of the day wins),
 *  - reps slot: day ACCUMULATE (+reps),
 *  - habit's own count: +1 per logged entry.
 *
 * **Correction support:** a NEGATIVE `EXTRA_REPS` (with weight 0) is a
 * delete/set-correction sent by Kenga when the user removes a logged set
 * there: the reps slot (and the habit count) are decremented with a
 * minimum clamp of 0 — a correction can undo an earlier entry but never
 * drive a slot negative. Corrections do NOT touch the exercise-name
 * sidecar, the recent-exercise list or the PR sidecar (records are
 * derived, not incremented — a correction never re-lowers a past PR).
 *
 * Side effects for a normal (positive) entry with a named exercise:
 *  - `WeightsExerciseRepository.setExerciseName` for the date/machine slot,
 *  - recent-exercise move-to-front (cap 10) via
 *    `SettingsRepository.saveWeightsRecentExercises`,
 *  - `WeightsRecordsRepository.evaluateAndRecord` PR check against the
 *    PRE-write DB-derived day-max PB (same join `getWeightsExerciseStats`
 *    uses) — when a record falls, [HabitIncrementBus.emit] notifies the
 *    running UI. NOTE: Tail's own gold weights-PR flash lives in the
 *    HabitViewModel (`_weightsPrFlash`), which a broadcast receiver cannot
 *    reach — Tail's in-app flash will therefore show on the NEXT weights
 *    save made inside Tail; Kenga shows its own PR flash regardless.
 *
 * Every successful write ends in [HabitIncrementBus.emit] +
 * [HabitIncrementAnnouncer.announce] + [TailChangeLog.noteChange], so
 * widgets, same-keystore listeners and the v2 change feed all see it.
 *
 * Security: manifest-declared with `android:permission` pointing to the
 * `com.example.tail.permission.TAIL_INTEGRATION` signature permission, so
 * only apps signed with the same keystore can send this broadcast.
 *
 * **Concurrency:** a shared [Mutex] serialises the DB read-modify-write so
 * rapid-fire broadcasts (several sets saved in quick succession) each see
 * the previous broadcast's committed changes — the same protection
 * [HabitValueSetReceiver] and [JugCoachSessionReceiver] use.
 */
class WeightsEntryReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_LOG_WEIGHTS_ENTRY = "com.example.tail.ACTION_LOG_WEIGHTS_ENTRY"
        const val EXTRA_HABIT_ID = "EXTRA_HABIT_ID"
        const val EXTRA_EXERCISE_NAME = "EXTRA_EXERCISE_NAME"
        const val EXTRA_WEIGHT_GRAMS = "EXTRA_WEIGHT_GRAMS"
        const val EXTRA_REPS = "EXTRA_REPS"
        const val EXTRA_MACHINE = "EXTRA_MACHINE"
        const val EXTRA_DATE = "EXTRA_DATE"
        const val EXTRA_SOURCE = "EXTRA_SOURCE"

        /** Serialises the habits-DB read-modify-write cycle (lost-write guard). */
        private val fileMutex = Mutex()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOG_WEIGHTS_ENTRY) return

        val habitName = intent.getStringExtra(EXTRA_HABIT_ID)?.trim()
        if (habitName.isNullOrBlank()) {
            Log.w(TAG, "Received $ACTION_LOG_WEIGHTS_ENTRY without EXTRA_HABIT_ID — ignoring")
            return
        }
        val exerciseName = intent.getStringExtra(EXTRA_EXERCISE_NAME)?.trim().orEmpty()
        val weightGrams = intent.getIntExtra(EXTRA_WEIGHT_GRAMS, 0)
        val reps = intent.getIntExtra(EXTRA_REPS, 0)
        val machine = intent.getBooleanExtra(EXTRA_MACHINE, true)
        val source = intent.getStringExtra(EXTRA_SOURCE) ?: "unknown"
        val dateStr = intent.getStringExtra(EXTRA_DATE)
        val date = if (dateStr.isNullOrBlank()) {
            LocalDate.now()
        } else {
            try {
                LocalDate.parse(dateStr)
            } catch (e: Exception) {
                Log.w(TAG, "Unparseable EXTRA_DATE '$dateStr' — defaulting to today")
                LocalDate.now()
            }
        }

        if (weightGrams < 0) {
            Log.w(TAG, "Negative EXTRA_WEIGHT_GRAMS not supported (use negative reps for corrections) — ignoring")
            return
        }
        if (weightGrams == 0 && reps == 0) {
            Log.i(TAG, "Weights entry for '$habitName' carries no values — nothing to do")
            return
        }
        val isCorrection = reps < 0

        val pendingResult = goAsync()
        val appContext = context.applicationContext

        scope.launch {
            try {
                val settingsRepo = SettingsRepository(appContext)
                val habitsRepo = HabitsRepository()
                val settings = settingsRepo.settingsFlow.first()

                // Validation: must be one of the configured weights habits.
                if (habitName !in settings.weightsHabits) {
                    Log.w(
                        TAG,
                        "Habit '$habitName' is not in settings.weightsHabits — ignoring " +
                            "weights entry from '$source'"
                    )
                    return@launch
                }
                val fileUriString = settings.fileUri
                if (fileUriString.isEmpty()) {
                    Log.w(TAG, "No habits file URI configured — cannot record weights entry for '$habitName'")
                    return@launch
                }
                val uri = Uri.parse(fileUriString)

                fileMutex.withLock {
                    val db = habitsRepo.loadDatabase(uri, appContext).toMutableMap()

                    // PRE-write DB-derived PB (day-max joined to the exercise
                    // names) — the standing record this set is judged against.
                    val dayPbGrams = if (exerciseName.isNotBlank()) {
                        try {
                            dbPbWeightGrams(
                                db,
                                WeightsExerciseRepository(appContext).loadAll(),
                                habitName, exerciseName, machine
                            )
                        } catch (_: Exception) {
                            0
                        }
                    } else 0

                    val updatedDb = if (isCorrection) {
                        applyCorrection(db, habitName, reps, machine, date)
                    } else {
                        // Slot writes (weight max-merge, reps accumulate) +
                        // the +1 on the habit's own count, one snapshot.
                        val withSlots = habitsRepo.applyWeightsSlotsToDb(
                            db, habitName, weightGrams, reps, machine, date
                        ).toMutableMap()
                        val dateKey = dateString(date)
                        val entries = withSlots[habitName]?.toMutableMap() ?: mutableMapOf()
                        entries[dateKey] = (entries[dateKey] ?: 0) + 1
                        withSlots[habitName] = entries.toSortedMap()
                        withSlots
                    }

                    habitsRepo.persistDatabase(uri, appContext, updatedDb)

                    if (!isCorrection && exerciseName.isNotBlank()) {
                        // Which exercise this day's slot belongs to (powers
                        // PB display / graph filters), then the PR check.
                        val exerciseRepo = WeightsExerciseRepository(appContext)
                        exerciseRepo.setExerciseName(habitName, date, machine, exerciseName)

                        // Recent-exercise quick-choices: move-to-front, cap 10
                        // (repository-level recordRecentExercise equivalent).
                        val recent = settings.weightsRecentExercises.toMutableMap()
                        val list = recent[habitName]?.toMutableList() ?: mutableListOf()
                        list.remove(exerciseName)
                        list.add(0, exerciseName)
                        if (list.size > 10) list.subList(10, list.size).clear()
                        recent[habitName] = list
                        settingsRepo.saveWeightsRecentExercises(recent)

                        // All-time PR evaluation + sidecar update. The flash
                        // itself lives in the ViewModel (see KDoc) — we only
                        // persist the record; the UI path fires via the bus.
                        val check = WeightsRecordsRepository(appContext).evaluateAndRecord(
                            habitName, machine, exerciseName, weightGrams, reps,
                            date, /* locationLabel = */ null, dayPbGrams
                        )
                        if (check.isPr) {
                            Log.i(
                                TAG,
                                "Weights PR for '$habitName' / '$exerciseName' " +
                                    "(machine=$machine): weightPr=${check.weightPr} " +
                                    "repsPr=${check.repsPr} — Tail's in-app flash shows " +
                                    "on next in-app weights save; sender '$source' shows its own"
                            )
                        }
                    }
                }

                // Notify widgets / running UI / change feed / same-keystore
                // listeners, exactly like the other slot-writing receivers.
                HabitIncrementBus.emit(habitName)
                TailChangeLog.noteChange(appContext, habitName)
                if (!isCorrection) {
                    HabitIncrementAnnouncer.announce(appContext, habitName, 1, source)
                }
                Log.i(
                    TAG,
                    "Recorded weights entry for '$habitName' (${if (machine) "machine" else "free"}, " +
                        "$weightGrams g × $reps reps, '$exerciseName', $date, source=$source" +
                        (if (isCorrection) " [correction]" else "") + ")"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to record weights entry for '$habitName': ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Signed slot adjustment for corrections: the reps slot (and the habit's
     * own count) move by [negativeReps] (a negative delta), clamped at 0 —
     * keys whose value lands on 0 drop out entirely (same semantics as
     * [HabitsRepository.adjustHabitSlotsForDate]). The weight slot is never
     * lowered by a correction (day max-merge cannot be undone reliably).
     */
    private fun applyCorrection(
        db: MutableMap<String, Map<String, Int>>,
        habitName: String,
        negativeReps: Int,
        machine: Boolean,
        date: LocalDate
    ): MutableMap<String, Map<String, Int>> {
        val dateKey = dateString(date)
        val repsKey = if (machine) secondaryValueSlotKey(habitName, 2)
                      else secondaryValueSlotKey(habitName, 4)

        fun adjust(key: String, delta: Int) {
            val entries = db[key]?.toMutableMap() ?: return
            val updated = ((entries[dateKey] ?: 0) + delta).coerceAtLeast(0)
            if (updated == 0) entries.remove(dateKey) else entries[dateKey] = updated
            if (entries.isEmpty()) db.remove(key) else db[key] = entries.toSortedMap()
        }

        adjust(repsKey, negativeReps)
        adjust(habitName, -1) // one logged entry less for the day, min 0
        return db
    }

    /**
     * DB-derived heaviest day weight for one (habit, machine, exercise) —
     * the same join `HabitViewModel.getWeightsExerciseStats` uses: the
     * weight slot's day values filtered to days whose recorded exercise
     * name matches (case-insensitive). Un-named days are skipped.
     */
    private suspend fun dbPbWeightGrams(
        db: Map<String, Map<String, Int>>,
        namesByHabit: Map<String, Map<String, Map<String, String>>>,
        habitName: String,
        exerciseName: String,
        machine: Boolean
    ): Int {
        val typeKey = if (machine) WeightsExerciseRepository.KEY_MACHINE
                      else WeightsExerciseRepository.KEY_FREE
        val weightKey = if (machine) secondaryValueKey(habitName)
                        else secondaryValueSlotKey(habitName, 3)
        val weights = db[weightKey] ?: return 0
        val namesByDate = namesByHabit[habitName] ?: emptyMap()
        var pb = 0
        for ((date, grams) in weights) {
            val named = namesByDate[date]?.get(typeKey) ?: continue
            if (!named.equals(exerciseName, ignoreCase = true)) continue
            if (grams > pb) pb = grams
        }
        return pb
    }
}
