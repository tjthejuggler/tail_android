package com.example.tail.widget

import android.content.Context
import android.net.Uri
import com.example.tail.data.HabitIncrementBus
import com.example.tail.data.HabitsRepository
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.SettingsRepository
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Credits time spent on a readiness-test step to [habitName] (if linked in
 * Settings): adds [minutes] to the habit's first-class MINUTES slot
 * (`minutes:<habitName>` in habitsdb.txt) and +[sessions] to the
 * habit's own session-count slot, in one atomic read-modify-write — the
 * same write the bubble timer uses
 * ([HabitsRepository.incrementHabitWithMinutes]).
 *
 * Minute-based credits are cumulative durations, not binary "did it"
 * counts, so they bypass the max-1/day cap (mirroring the IPC v2
 * EXTRA_MINUTES rule); a pure session credit ([minutes] <= 0) still
 * respects it. Fire-and-forget on IO; emits the increment bus so open UIs
 * refresh, records a timestamp.
 *
 * The v1/v2 readiness overlay dialogs that used to live in this file were
 * retired on 2026-09-07 together with the v1/v2 pre-game engines; only
 * this shared credit helper remains (used by the v3 flow).
 */
object ChessHabitCredit {
    fun grant(context: Context, habitName: String, minutes: Int, sessions: Int = 1) {
        if (habitName.isBlank()) return
        if (minutes <= 0 && sessions <= 0) return
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val habitsRepo = HabitsRepository()
                val settings = SettingsRepository(appContext).settingsFlow.first()
                val uriStr = settings.fileUri
                if (uriStr.isEmpty()) return@launch
                val uri = Uri.parse(uriStr)

                // The max-1/day cap only makes sense for count-based credits;
                // minutes are cumulative durations (same rule as IPC v2).
                if (minutes <= 0 && habitName in settings.maxOneHabits) {
                    val db = habitsRepo.loadDatabase(uri, appContext)
                    val today = LocalDate.now().toString()
                    if ((db[habitName]?.get(today) ?: 0) >= 1) return@launch
                }

                habitsRepo.incrementHabitWithMinutes(
                    uri, appContext, habitName, minutes.coerceAtLeast(0), sessions
                )
                HabitIncrementBus.emit(habitName)
                try {
                    HabitTimestampRepository(appContext).addTimestamp(habitName)
                } catch (_: Exception) { /* timestamp optional */ }
            } catch (_: Exception) { /* credit is best-effort */ }
        }
    }
}
