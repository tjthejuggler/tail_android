package com.example.tail.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.example.tail.ui.habitPointsTier
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  TIER-COLOURED LAUNCHER ICON
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Android cannot recolor a launcher icon at runtime, so the app ships 13
 * activity-aliases (LauncherAliasTier0…12 in AndroidManifest.xml), each with
 * an adaptive icon whose background is the colour of one daily-points tier
 * (see habitPointsTier() in HabitLoadingSpinner.kt and
 * res/values/ic_launcher_tier_colors.xml).
 *
 * [applyDailyTier] enables exactly the alias matching the current tier and
 * disables all others via PackageManager, making the home-screen icon's
 * background always mirror the daily habit points colour. The last applied
 * tier is cached in SharedPreferences so unchanged tiers are a no-op —
 * setComponentEnabledSetting is not free and rebuildHabitList() runs often.
 *
 * Note: launchers pick up component changes with a short delay and some
 * cache icons aggressively; a launcher restart may be needed to see a
 * change immediately.
 */
object LauncherIconTierManager {
    private const val TAG = "LauncherIconTier"
    private const val PREFS = "launcher_icon_tier"
    private const val KEY_TIER = "tier"
    private const val ALIAS_COUNT = 13

    /** Component name of the alias for [tier] (0–12). */
    fun aliasComponent(context: Context, tier: Int): ComponentName =
        ComponentName(context, "com.example.tail.LauncherAliasTier$tier")

    /**
     * Enables the alias for [tier] and disables every other tier alias.
     * Safe to call on any thread; cheap no-op when the tier is unchanged.
     */
    fun applyDailyTier(context: Context, tier: Int) {
        val clamped = tier.coerceIn(0, ALIAS_COUNT - 1)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_TIER, 0) == clamped) return
        val pm = context.packageManager
        for (i in 0 until ALIAS_COUNT) {
            val newState =
                if (i == clamped) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            try {
                pm.setComponentEnabledSetting(
                    aliasComponent(context, i),
                    newState,
                    PackageManager.DONT_KILL_APP
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set alias tier $i: ${e.message}")
            }
        }
        prefs.edit().putInt(KEY_TIER, clamped).apply()
        Log.i(TAG, "Launcher icon switched to tier $clamped")
    }

    /**
     * Recomputes today's total points straight from the habits database and
     * applies the matching tier — the ViewModel-free path used by
     * HabitIncrementBus so the icon updates on EVERY increment (widget taps,
     * IPC broadcasts, voice, bubble timer, notification asks) even when the
     * app UI is not running. No-op when the tier is unchanged.
     */
    suspend fun refreshFromDatabase(context: Context) {
        try {
            val settings = SettingsRepository(context).settingsFlow.first()
            val uriStr = settings.fileUri
            if (uriStr.isEmpty()) return
            val result = HabitsRepository().loadDatabaseResult(Uri.parse(uriStr), context)
            val db = (result as? HabitsLoadResult.Success)?.db ?: return
            val todayPoints = DailyPointsCalculator.totalPointsForDate(
                dateString(LocalDate.now()), db, settings
            )
            applyDailyTier(context, habitPointsTier(todayPoints))
        } catch (e: Exception) {
            Log.w(TAG, "refreshFromDatabase failed: ${e.message}")
        }
    }
}
