package com.example.tail.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.widget.RemoteViews
import com.example.tail.MainActivity
import com.example.tail.R
import com.example.tail.data.DailyPointsCalculator
import com.example.tail.data.HabitsLoadResult
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.dateString
import com.example.tail.ui.habitPointsTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  FULL-WIDTH TIER BAR WIDGET
 * ═══════════════════════════════════════════════════════════════════════
 *
 * A home-screen strip whose entire background is the current daily-points
 * tier colour (same palette as the tier launcher icons), with the live
 * point total on the left and configurable quick-launch buttons for app
 * tabs on the right. Which buttons appear is configured in the app's
 * Settings screen via [TierBarWidgetConfig].
 *
 * Updates are pushed from [refreshAll], which the HabitIncrementBus calls
 * (debounced) after every habit increment from any path, so the colour and
 * point total track the day in real time. The 30-minute OS tick is only a
 * date-rollover fallback.
 */
class TierBarWidgetProvider : AppWidgetProvider() {

    companion object {
        /** One entry per quick-launch button: id → route + label. */
        data class ButtonSpec(val key: String, val route: String?, val glyph: String, val label: String)

        val BUTTONS = listOf(
            ButtonSpec("grid", null, "▦", "Habits grid"),
            ButtonSpec("map", "map", "🗺", "Map"),
            ButtonSpec("app_stats", "app_stats", "📊", "App stats"),
            ButtonSpec("chess_stats", "chess_readiness_stats", "♟", "Chess readiness stats"),
            ButtonSpec("settings", "settings", "⚙", "Settings")
        )

        fun buttonViewId(key: String): Int = when (key) {
            "grid" -> R.id.tier_bar_btn_grid
            "map" -> R.id.tier_bar_btn_map
            "app_stats" -> R.id.tier_bar_btn_app_stats
            "chess_stats" -> R.id.tier_bar_btn_chess_stats
            "settings" -> R.id.tier_bar_btn_settings
            else -> 0
        }

        /**
         * Recomputes today's points from the habits DB and pushes a fresh
         * RemoteViews (tier background colour + point total + button
         * visibility/intents) to every placed tier-bar widget. Cheap no-op
         * (beyond one DB read) when no widget exists.
         */
        suspend fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(
                android.content.ComponentName(appContext, TierBarWidgetProvider::class.java)
            )
            if (ids.isEmpty()) return

            var points = 0
            try {
                val settings = SettingsRepository(appContext).settingsFlow.first()
                if (settings.fileUri.isNotEmpty()) {
                    val result = HabitsRepository().loadDatabaseResult(
                        Uri.parse(settings.fileUri), appContext
                    )
                    val db = (result as? HabitsLoadResult.Success)?.db
                    if (db != null) {
                        points = DailyPointsCalculator.totalPointsForDate(
                            dateString(LocalDate.now()), db, settings
                        )
                    }
                }
            } catch (_: Exception) {
                // Keep last-known points (0) on DB hiccups — the widget
                // stays alive rather than blanking.
            }

            val tier = habitPointsTier(points)
            val bgColor = tierColor(appContext, tier)
            val fgColor = if (isLight(bgColor)) Color.BLACK else Color.WHITE
            val config = TierBarWidgetConfig.load(appContext)

            val views = RemoteViews(appContext.packageName, R.layout.widget_tier_bar).apply {
                setInt(R.id.tier_bar_root, "setBackgroundColor", bgColor)
                setTextColor(R.id.tier_bar_points, fgColor)
                setTextViewText(R.id.tier_bar_points, "Tail · $points pts")
                for (spec in BUTTONS) {
                    val viewId = buttonViewId(spec.key)
                    if (viewId == 0) continue
                    setViewVisibility(
                        viewId,
                        if (config[spec.key] == true)
                            android.view.View.VISIBLE else android.view.View.GONE
                    )
                    setTextColor(viewId, fgColor)
                    setOnClickPendingIntent(viewId, launchPendingIntent(appContext, spec))
                }
            }
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        private fun launchPendingIntent(context: Context, spec: ButtonSpec): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.example.tail.TIER_BAR.${spec.key.uppercase()}"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                spec.route?.let { putExtra(MainActivity.EXTRA_OPEN_ROUTE, it) }
            }
            return PendingIntent.getActivity(
                context,
                spec.key.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun tierColor(context: Context, tier: Int): Int {
            val resId = context.resources.getIdentifier(
                "ic_launcher_tier${tier.coerceIn(0, 12)}", "color", context.packageName
            )
            return if (resId != 0) androidx.core.content.ContextCompat.getColor(context, resId)
            else Color.parseColor("#FF33AA55")
        }

        private fun isLight(color: Int): Boolean {
            val r = Color.red(color) / 255.0
            val g = Color.green(color) / 255.0
            val b = Color.blue(color) / 255.0
            val lum = 0.2126 * r + 0.7152 * g + 0.0722 * b
            return lum > 0.6
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Render immediately with last-known config; refreshAll (called by
        // the increment bus and the 30-min tick) brings the numbers current.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            refreshAll(context)
        }
    }
}

/**
 * SharedPreferences-backed configuration for the tier bar widget's
 * quick-launch buttons. Defaults: grid + settings visible.
 */
object TierBarWidgetConfig {
    private const val PREFS = "tier_bar_widget"

    fun load(context: Context): Map<String, Boolean> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return TierBarWidgetProvider.BUTTONS.associate { spec ->
            spec.key to prefs.getBoolean(spec.key, spec.key == "grid" || spec.key == "settings")
        }
    }

    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(key, enabled).apply()
    }
}
