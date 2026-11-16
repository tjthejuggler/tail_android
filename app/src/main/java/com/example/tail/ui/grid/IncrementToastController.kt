package com.example.tail.ui.grid

import com.example.tail.data.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Shows the post-increment toast ("✓ habit / 🕐 Edit Time / ⏰ Timeless")
 * after a POPUP/FORM submission that increments a habit.
 *
 * Previously the toast only fired on the plain-tap and text-input paths:
 * dialog submissions (pill log, subtype, weights, blood test, …) recorded
 * their timestamps but never showed the flash, so the just-logged time could
 * not be edited or made timeless right away. Every popup confirm that
 * increments a habit now routes through here.
 *
 * Semantics copied from the inline blocks previously duplicated in
 * [HabitGridScreen]: bump the version counter (stale auto-dismiss guard),
 * set habit + timelessness, resolve the original stamp time, and schedule
 * the 3.5 s auto-dismiss.
 */
internal fun scheduleIncrementToast(
    habitName: String,
    isToday: Boolean,
    settings: AppSettings,
    version: androidx.compose.runtime.MutableIntState,
    setHabit: (String?) -> Unit,
    setTimeless: (Boolean) -> Unit,
    setOriginalTime: (String) -> Unit,
    scope: CoroutineScope
) {
    val isTimeless = !isToday || habitName in settings.timelessHabits
    version.intValue++
    setHabit(habitName)
    setTimeless(isTimeless)
    setOriginalTime(
        when {
            !isToday -> ""
            habitName in settings.timelessHabits ->
                com.example.tail.data.HabitTimestampRepository.TIMELESS_TIME
            else -> com.example.tail.data.HabitTimestampRepository.nowTime()
        }
    )
    val currentVersion = version.intValue
    scope.launch {
        delay(3500)
        if (version.intValue == currentVersion) {
            setHabit(null)
        }
    }
}
