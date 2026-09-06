package com.example.tail.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.util.Log
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.example.tail.R
import com.example.tail.data.AppSettings
import com.example.tail.data.HABIT_ORDER
import com.example.tail.data.SettingsRepository
import com.example.tail.data.appIconMonochromeOf
import com.example.tail.data.appPackageNameOf
import com.example.tail.data.loadAppIconBitmap
import com.example.tail.data.renderTextIconBitmap
import com.example.tail.data.textIconCharOf
import com.example.tail.ui.getHabitIconRes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

private const val TAG = "HabitListWidgetSvc"

/**
 * Service that powers the widget's scrollable habit list.
 *
 * Each AppWidget instance gets its own [HabitListFactory] (keyed by appWidgetId).
 * The factory snapshots the ordered habit list from settings + per-widget DataStore
 * state on every [onDataSetChanged] call and emits one [RemoteViews] per habit.
 */
class HabitListRemoteViewsService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory {
        val widgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )
        return HabitListFactory(applicationContext, widgetId)
    }
}

/**
 * Lightweight per-row data carried by the factory after [onDataSetChanged].
 */
private data class WidgetRow(
    val habitName: String,
    val iconResId: Int?,
    /** Icon of an installed app, when the habit uses an "app:<package>" icon
     *  (full-colour launcher icon, or its black/white "#mono" variant). */
    val iconBitmap: Bitmap? = null,
    /** Greyed out when true (e.g. max-one habit already done today). */
    val dimmed: Boolean
)

private class HabitListFactory(
    private val context: Context,
    private val widgetId: Int
) : RemoteViewsService.RemoteViewsFactory {

    @Volatile private var rows: List<WidgetRow> = emptyList()

    override fun onCreate() {
        // Nothing — onDataSetChanged() will populate `rows`.
    }

    override fun onDataSetChanged() {
        // Heavy work is allowed here (this method runs on the binder thread off the main thread).
        // Use runBlocking to read the suspending DataStore APIs synchronously.
        try {
            val (settings, recent, max1Today) = runBlocking {
                val s = SettingsRepository(context).settingsFlow.first()
                val r = WidgetPreferences.getRecentOrder(context, widgetId)
                val m = WidgetPreferences.getMax1HabitsToHideToday(context, widgetId)
                Triple(s, r, m)
            }

            val ordered = computeOrderedHabitList(settings, recent, max1Today)
            rows = ordered.map { name ->
                // App icons are resolved via PackageManager (full-colour or the
                // black/white "#mono" variant); text icons are rendered to a
                // greyscale bitmap; skip the resource lookup for both
                // (getHabitIconRes would fall back to the habit's default
                // icon otherwise).
                val appPkg = appPackageNameOf(settings.habitIcons[name])
                val appIconMono = appIconMonochromeOf(settings.habitIcons[name])
                val textChar = textIconCharOf(settings.habitIcons[name])
                val iconRes = if (appPkg == null && textChar == null)
                    getHabitIconRes(name, settings.habitIcons) else null
                val appBitmap = if (appPkg != null) {
                    val bitmap = loadAppIconBitmap(context, appPkg, appIconMono)
                    if (bitmap == null) {
                        Log.w(TAG, "App icon not found for $appPkg on widget=$widgetId")
                    }
                    bitmap
                } else null
                val textBitmap = if (textChar != null) renderTextIconBitmap(textChar, 96) else null
                WidgetRow(
                    habitName = name,
                    iconResId = iconRes,
                    iconBitmap = appBitmap ?: textBitmap,
                    dimmed    = name in max1Today
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "onDataSetChanged failed for widget=$widgetId: ${e.message}", e)
            rows = emptyList()
        }
    }

    /**
     * Builds the final display order:
     *  1. Start with the canonical habit order from settings (screens flattened →
     *     legacy flat order → HABIT_ORDER fallback).
     *  2. Filter out habits the user has hidden via settings.disabledHabits (when present).
     *  3. Move any habits in [recent] to the front, preserving recent's most-recent-first order.
     *  4. Move any habits in [max1Today] to the very bottom (they were tapped today and are
     *     max-one, so they belong out of the way until midnight).
     */
    private fun computeOrderedHabitList(
        settings: AppSettings,
        recent: List<String>,
        max1Today: Set<String>
    ): List<String> {
        val canonical: List<String> = when {
            settings.habitScreens.isNotEmpty() ->
                settings.habitScreens.flatMap { it.habitNames }
            settings.habitOrder.isNotEmpty() -> settings.habitOrder
            else -> HABIT_ORDER
        }

        val disabled = settings.disabledHabits
        val lockExcluded = settings.lockWidgetExcludedHabits
        // Excluded via the per-habit "🔒 Lock Screen Widget" toggle (default ON
        // → excluded set is normally empty). disabledHabits is the global hide.
        val available = canonical.filter { it !in disabled && it !in lockExcluded }.distinct()

        val availableSet = available.toHashSet()

        // Recent-tap habits in widget-local order (already most-recent-first).
        val topGroup = recent.filter { it in availableSet && it !in max1Today }

        // Bottom group: max1 habits tapped today (preserve canonical order among themselves).
        val bottomGroup = available.filter { it in max1Today }

        // Middle group: everything else, in canonical order.
        val midGroup = available.filter { it !in topGroup && it !in bottomGroup }

        return topGroup + midGroup + bottomGroup
    }

    override fun getCount(): Int = rows.size

    override fun getViewAt(position: Int): RemoteViews {
        val row = rows.getOrNull(position) ?: return RemoteViews(
            context.packageName, R.layout.widget_item
        )

        val rv = RemoteViews(context.packageName, R.layout.widget_item)
        rv.setTextViewText(R.id.widget_item_name, row.habitName)

        when {
            row.iconBitmap != null -> {
                rv.setImageViewBitmap(R.id.widget_item_icon, row.iconBitmap)
                rv.setViewVisibility(R.id.widget_item_icon, android.view.View.VISIBLE)
            }
            row.iconResId != null -> {
                rv.setImageViewResource(R.id.widget_item_icon, row.iconResId)
                rv.setViewVisibility(R.id.widget_item_icon, android.view.View.VISIBLE)
            }
            else -> rv.setViewVisibility(R.id.widget_item_icon, android.view.View.INVISIBLE)
        }

        // Dim greyed-out rows (max-one already done today) so they look "done".
        rv.setInt(
            R.id.widget_item_root,
            "setBackgroundResource",
            if (row.dimmed) R.drawable.widget_item_dimmed_bg else R.drawable.widget_item_bg
        )

        // Fill-in intent: the template intent in the provider has the action +
        // appWidgetId; we just supply the per-row habit name.
        val fillIn = Intent().apply {
            putExtra(HabitListWidgetProvider.EXTRA_HABIT_NAME, row.habitName)
        }
        rv.setOnClickFillInIntent(R.id.widget_item_root, fillIn)

        return rv
    }

    override fun getLoadingView(): RemoteViews? = null
    override fun getViewTypeCount(): Int = 1
    override fun getItemId(position: Int): Long = rows.getOrNull(position)?.habitName?.hashCode()?.toLong() ?: position.toLong()
    override fun hasStableIds(): Boolean = true

    override fun onDestroy() {
        rows = emptyList()
    }
}
