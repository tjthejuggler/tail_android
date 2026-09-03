package com.example.tail.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.net.Uri
import android.widget.RemoteViews
import com.example.tail.MainActivity
import com.example.tail.R
import com.example.tail.data.HabitsLoadResult
import com.example.tail.data.HabitsRepository
import com.example.tail.data.NotificationStore
import com.example.tail.data.SettingsRepository
import com.example.tail.data.computeTaskerStats
import com.example.tail.ui.habitPointsTier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════════════
 * FULL-WIDTH TIER BAR WIDGET
 * ═══════════════════════════════════════════════════════════════════════
 *
 * A home-screen strip that encodes the three headline habit metrics:
 *
 *  · TODAY's points      → the metallic mecha-lizard artwork variant
 *                          (tier_bar_lizard_t0…t12; tiers 0–5 are single
 *                          glow colours, 6–12 are white+colour combos).
 *  · WEEKLY avg (7-day)  → the background gradient colour.
 *  · MONTHLY avg (30-day)→ the rounded border stroke colour.
 *
 * The background bitmap is composited at runtime (gradient + SCREEN-blended
 * lizard + rounded corners + border) and pushed via setImageViewBitmap.
 * Quick-launch buttons for app tabs sit on top; their visibility is
 * configured in Settings via [TierBarWidgetConfig]. Tapping anywhere else
 * opens the app.
 *
 * Updates are pushed from [refreshAll], which the HabitIncrementBus calls
 * (debounced) after every habit increment from any path. The 30-minute OS
 * tick is only a date-rollover fallback.
 */
class TierBarWidgetProvider : AppWidgetProvider() {

    companion object {
        /** One entry per quick-launch button: id → route + label. */
        data class ButtonSpec(val key: String, val route: String?, val glyph: String, val label: String)

        val BUTTONS = listOf(
            ButtonSpec("grid", null, "▦", "Habits grid"),
            ButtonSpec("map", "map", "◈", "Map"),
            ButtonSpec("app_stats", "app_stats", "≡", "App stats"),
            ButtonSpec("chess_stats", "chess_readiness_stats", "♞", "Chess readiness stats"),
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
         * Recomputes today / avg7 / avg30 from the habits DB and pushes a
         * fresh RemoteViews (lizard variant + gradient + border + button
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

            var today = 0
            var avg7 = 0.0
            var avg30 = 0.0
            var habitScreenCount = 0
            try {
                val settings = SettingsRepository(appContext).settingsFlow.first()
                habitScreenCount = settings.habitScreens.size
                if (settings.fileUri.isNotEmpty()) {
                    val result = HabitsRepository().loadDatabaseResult(
                        Uri.parse(settings.fileUri), appContext
                    )
                    val db = (result as? HabitsLoadResult.Success)?.db
                    if (db != null) {
                        val stats = computeTaskerStats(
                            db = db,
                            dividers = settings.habitDividers,
                            noPointsHabits = settings.noPointsHabits,
                            secondaryValueFallbackHabits = settings.secondaryValueFallbackHabits,
                            timerMinutesPrimaryHabits = settings.widgetTimerMinutesPrimary,
                            invertedBinaryHabits = settings.invertedBinaryHabits,
                            secondaryValueHabits = settings.secondaryValueHabits
                        )
                        today = stats.today
                        avg7 = stats.avg7
                        avg30 = stats.avg30
                    }
                }
            } catch (_: Exception) {
                // Keep last-known metrics (0) on DB hiccups — the widget
                // stays alive rather than blanking.
            }

            val tiers = TierStateStore.Tiers(
                monthTier = habitPointsTier(avg30.roundToInt()),
                weekTier = habitPointsTier(avg7.roundToInt()),
                dayTier = habitPointsTier(today)
            )
            // Persist for the SYNCHRONOUS render path: on a host rebind
            // (nav-mode/keyboard config change) onUpdate must paint the
            // COMPLETE widget from this state without a DB round-trip.
            TierStateStore.save(appContext, tiers)

            val views = buildRenderViews(appContext, tiers, mgr, ids)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        }

        /**
         * Builds the COMPLETE tier-bar RemoteViews synchronously from the
         * given tiers (no DB, no suspend calls). Both the async refresh and
         * the synchronous rebind answer go through this one code path, so
         * the frame a host paints during a rebind is visually identical to
         * the settled state — the never-vanish guarantee (see [onUpdate]).
         */
        fun buildRenderViews(
            appContext: Context,
            tiers: TierStateStore.Tiers,
            mgr: AppWidgetManager,
            ids: IntArray
        ): RemoteViews {
            val (monthTier, weekTier, dayTier) = tiers
            val weekColor = tierColor(appContext, weekTier)
            val config = TierBarWidgetConfig.load(appContext)
            val habitScreenCount = try {
                kotlinx.coroutines.runBlocking {
                    SettingsRepository(appContext).settingsFlow.first()
                }.habitScreens.size
            } catch (_: Exception) { 0 }

            // Pending habit-ask notifications → badge count (capped display).
            val notifCount = try {
                kotlinx.coroutines.runBlocking {
                    NotificationStore(appContext).notificationsFlow.first().size
                }
            } catch (_: Exception) { 0 }
            val dayColor = tierColor(appContext, dayTier)
            val monthColor = tierColor(appContext, monthTier)

            // Deep link: open the habit grid straight into the notifications popup.
            val notifIntent = Intent(appContext, MainActivity::class.java).apply {
                action = "com.example.tail.TIER_BAR.NOTIFICATIONS"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, "grid")
                putExtra(MainActivity.EXTRA_OPEN_NOTIFICATIONS, true)
            }
            val notifPending = PendingIntent.getActivity(
                appContext, 4242, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Tapping anywhere that is not a quick-launch button opens the app.
            val openApp = launchPendingIntent(
                appContext, BUTTONS.first { it.key == "grid" }
            )

            // Alternate between two byte-identical layouts: AIO Launcher's
            // host short-circuits RemoteViews it considers "the same view"
            // during rebind storms, leaving the slot stuck on its Loading
            // placeholder. A changed layout id forces a REAL re-inflation
            // of the host view on every push.
            val layoutId = if (layoutFlip.getAndSet(!layoutFlip.get())) {
                R.layout.widget_tier_bar_b
            } else {
                R.layout.widget_tier_bar
            }
            val views = RemoteViews(appContext.packageName, layoutId).apply {
                setOnClickPendingIntent(R.id.tier_bar_root, openApp)
                if (notifCount > 0) {
                    setViewVisibility(R.id.tier_bar_notif, android.view.View.VISIBLE)
                    setImageViewBitmap(
                        R.id.tier_bar_notif,
                        buildBadge(dayColor, weekColor, monthColor,
                            if (notifCount > 9) "+" else notifCount.toString())
                    )
                    setOnClickPendingIntent(R.id.tier_bar_notif, notifPending)
                } else {
                    setViewVisibility(R.id.tier_bar_notif, android.view.View.GONE)
                }
                // Top-bar quick-launch icon circles (runtime bitmaps).
                for (spec in BUTTONS) {
                    val viewId = buttonViewId(spec.key)
                    if (viewId == 0) continue
                    if (config[spec.key] == true) {
                        setViewVisibility(viewId, android.view.View.VISIBLE)
                        setImageViewBitmap(
                            viewId,
                            buildBadge(dayColor, weekColor, monthColor, spec.glyph)
                        )
                        setOnClickPendingIntent(viewId, launchPendingIntent(appContext, spec))
                    } else {
                        setViewVisibility(viewId, android.view.View.GONE)
                    }
                }
                // Invisible horizontal touch zones → selected habit screens
                // (in their tab order). Unused zones are hidden; with none
                // selected the root tap (open app grid) covers everything.
                val zoneIds = intArrayOf(
                    R.id.tier_bar_zone_0, R.id.tier_bar_zone_1, R.id.tier_bar_zone_2,
                    R.id.tier_bar_zone_3, R.id.tier_bar_zone_4, R.id.tier_bar_zone_5,
                    R.id.tier_bar_zone_6, R.id.tier_bar_zone_7
                )
                val selected = TierBarWidgetConfig.loadScreens(appContext)
                    .filter { it in 0 until habitScreenCount }
                    .sorted()
                zoneIds.forEachIndexed { i, zoneId ->
                    if (i < selected.size) {
                        setViewVisibility(zoneId, android.view.View.VISIBLE)
                        setOnClickPendingIntent(
                            zoneId, screenPendingIntent(appContext, selected[i], i)
                        )
                    } else {
                        setViewVisibility(zoneId, android.view.View.GONE)
                    }
                }
            }

            // One bitmap per widget: sized to that widget's actual aspect so
            // fitXY never stretches the art and nothing gets cropped.
            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
                val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 60)
                val aspect = (wDp.toFloat() / hDp.toFloat()).coerceIn(2f, 12f)
                views.setImageViewBitmap(
                    R.id.tier_bar_bg,
                    buildBackground(appContext, monthTier, weekTier, dayTier, aspect)
                )
            }
            return views
        }

        /** Deep link: open the habit grid on the given screen (tab). */
        private fun screenPendingIntent(
            context: Context, screenIndex: Int, zoneIndex: Int
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.example.tail.TIER_BAR.SCREEN$screenIndex"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, "grid")
                putExtra(MainActivity.EXTRA_OPEN_SCREEN_INDEX, screenIndex)
            }
            return PendingIntent.getActivity(
                context,
                5000 + zoneIndex,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
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

        /** Alternates widget_tier_bar ↔ widget_tier_bar_b (see buildRenderViews). */
        private val layoutFlip = java.util.concurrent.atomic.AtomicBoolean(false)

        // ────────────────────────────────────────────────────────────────────
        // Background compositing
        // ────────────────────────────────────────────────────────────────────

        /** (monthTier, weekTier, dayTier, aspect) → composited strip bitmap. */
        private val backgroundCache = HashMap<Long, Bitmap>()
        private val layerCache = HashMap<String, Bitmap?>()
        private val lizardCache = HashMap<Int, Bitmap?>()

        /**
         * Three-layer scene composite:
         *   1. SKY       (monthly points tier) — deepest background, fills frame
         *   2. SURROUNDINGS (weekly points tier) — keyed silhouette over the sky
         *   3. LIZARD    (today's tier) — front layer, right-anchored
         * Everything is clipped to the rounded-corner widget silhouette.
         * All art ships with real alpha (processed offline), so this is pure
         * drawBitmap compositing with zero runtime pixel work.
         */
        private fun buildBackground(
            context: Context,
            monthTier: Int,
            weekTier: Int,
            dayTier: Int,
            aspect: Float
        ): Bitmap {
            val key = (monthTier.toLong() shl 44) or
                (weekTier.toLong() shl 40) or
                (dayTier.toLong() shl 36) or
                (aspect.toInt().toLong())
            backgroundCache[key]?.let { return it }

            val h = 400
            val w = (h * aspect).toInt().coerceIn(800, 4800)
            val radius = h / 6f
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)

            // Rounded-corner clip so every layer gets the same silhouette.
            val clip = Path().apply {
                addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radius, radius, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(clip)

            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val full = android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())

            // 1. Sky — monthly tier, stretched edge-to-edge (sky has no
            //    silhouette so uniform stretch is fine; 4:1-ish source).
            layerBitmap(context, "tier_bar_sky_t", monthTier)?.let {
                canvas.drawBitmap(it, null, full, paint)
            }

            // 2. Surroundings — weekly tier, full-width and bottom-anchored
            //    (silhouette art: transparent sky above, terrain below).
            layerBitmap(context, "tier_bar_env_t", weekTier)?.let { env ->
                val envH = (w / 4f).coerceAtMost(h.toFloat())
                canvas.drawBitmap(
                    env, null,
                    android.graphics.RectF(0f, h - envH, w.toFloat(), h.toFloat()),
                    paint
                )
            }

            // 3. Lizard — today's tier, uniform 4:1 scale, height-filling,
            //    right-anchored with a small margin. (The strips themselves
            //    carry a baked-in right padding for the curled tail — see
            //    wallpaper_gen/pad_lizard_strips.py — so no extra runtime
            //    inset is needed.)
            lizardBitmap(context, dayTier)?.let { lizard ->
                val margin = h / 32f
                val avail = h - margin * 2f
                val drawW = avail * 4f
                canvas.drawBitmap(
                    lizard, null,
                    android.graphics.RectF(
                        w - drawW - margin, margin,
                        w.toFloat() - margin, h.toFloat() - margin
                    ),
                    paint
                )
            }
            canvas.restore()

            backgroundCache[key] = bmp
            return bmp
        }

        private fun layerBitmap(context: Context, prefix: String, tier: Int): Bitmap? {
            val t = tier.coerceIn(0, 12)
            val cacheKey = "$prefix$t"
            layerCache[cacheKey]?.let { return it }
            val resId = context.resources.getIdentifier(
                cacheKey, "drawable", context.packageName
            )
            val bmp = if (resId != 0)
                BitmapFactory.decodeResource(context.resources, resId) else null
            layerCache[cacheKey] = bmp
            return bmp
        }

        /**
         * Circular badge used by both the notification count and the
         * quick-launch buttons: border = weekly-tier colour, fill =
         * daily-tier colour, glyph = monthly-tier colour. [label] is a
         * single digit/glyph ("+" for counts above 9).
         */
        private fun buildBadge(fill: Int, border: Int, text: Int, label: String): Bitmap {
            val size = 120
            val c = size / 2f
            val stroke = 12f
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawCircle(c, c, c - stroke / 2f - 1f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            canvas.drawCircle(c, c, c - stroke / 2f - 1f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = border
                    style = Paint.Style.STROKE
                    strokeWidth = stroke
                })
            val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = text
                textSize = size * 0.5f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                setShadowLayer(4f, 0f, 1f, 0x66000000)
            }
            val fm = tp.fontMetrics
            canvas.drawText(
                label, c,
                c - (fm.ascent + fm.descent) / 2f, tp
            )
            return bmp
        }

        private fun lizardBitmap(context: Context, tier: Int): Bitmap? {
            lizardCache[tier]?.let { return it }
            val resId = context.resources.getIdentifier(
                "tier_bar_lizard_t${tier.coerceIn(0, 12)}",
                "drawable", context.packageName
            )
            val bmp = if (resId != 0)
                BitmapFactory.decodeResource(context.resources, resId) else null
            lizardCache[tier] = bmp
            return bmp
        }


        private fun shiftToward(from: Int, to: Int, fraction: Float): Int {
            fun ch(f: Int, t: Int) = (f + (t - f) * fraction).toInt().coerceIn(0, 255)
            return Color.argb(
                255,
                ch(Color.red(from), Color.red(to)),
                ch(Color.green(from), Color.green(to)),
                ch(Color.blue(from), Color.blue(to))
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
        // ONE fully-formed answer, synchronously (complete art + intents,
        // no DB round-trip) — the host always gets a valid frame inside the
        // broadcast. Then schedule delayed re-pushes: the nav-mode rebind
        // storm on AIO Launcher applies early updates to host views it
        // discards mid-relayout (slots stay "Loading..." forever), while
        // updates arriving AFTER the storm settles render normally —
        // which is why opening the app used to be the only cure.
        paintCurrent(context, appWidgetManager, appWidgetIds)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            refreshAll(context)
        }
        scheduleRebindRepaints(context.applicationContext)
    }

    /**
     * Host-side option changes (resize, and the double re-ask AIO fires
     * during a nav-mode rebind) get the same complete synchronous answer.
     */
    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: android.os.Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        paintCurrent(context, appWidgetManager, intArrayOf(appWidgetId))
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            refreshAll(context)
        }
        scheduleRebindRepaints(context.applicationContext)
    }

    /**
     * Re-pushes the full widget a few seconds after a rebind storm. Cheap
     * (compositing caches; no-op without placed widgets) and it converts
     * "stuck Loading… until the app is opened" into a self-healing repaint.
     * The companion Handler outlives the transient receiver instances; the
     * process is kept alive by the widget-trigger / notification-listener
     * foreground services.
     */
    private fun scheduleRebindRepaints(appContext: Context) {
        for (delayMs in longArrayOf(2_500L, 7_000L, 15_000L)) {
            rebindHandler.postDelayed({
                try {
                    kotlinx.coroutines.CoroutineScope(
                        kotlinx.coroutines.Dispatchers.IO
                    ).launch { refreshAll(appContext) }
                } catch (_: Exception) { /* best-effort */ }
            }, delayMs)
        }
    }

    private val rebindHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Synchronous full render of every listed widget from the last
     * persisted tier state (no DB read). Reuses the exact view-building
     * code path as the async refresh so the painted frame is visually
     * identical to the settled state.
     */
    private fun paintCurrent(
        context: Context,
        mgr: AppWidgetManager,
        ids: IntArray
    ) {
        try {
            val appContext = context.applicationContext
            val tiers = TierStateStore.load(appContext)
            val views = buildRenderViews(appContext, tiers, mgr, ids)
            for (id in ids) {
                mgr.updateAppWidget(id, views)
            }
        } catch (e: Exception) {
            android.util.Log.w("TierBarWidget", "paintCurrent failed: ${e.message}")
        }
    }

    /**
     * Non-suspend entry point for callers outside coroutines (e.g.
     * [WidgetTriggerService]'s nav-mode self-heal repaints). Launches
     * refreshAll on IO; cheap no-op when no widgets are placed.
     */
    fun refreshAllFromAnyThread(context: Context) {
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

    /** Indices of the habit grid screens mapped to the widget's invisible
     *  horizontal touch zones (stored ordered by tab index). */
    fun loadScreens(context: Context): List<Int> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("touch_screens", "")
            .orEmpty()
            .split(',')
            .mapNotNull { it.trim().toIntOrNull() }

    fun setScreens(context: Context, screens: List<Int>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("touch_screens", screens.sorted().joinToString(",")).apply()
    }
}

/**
 * Last-known tier state for the tier-bar widget, persisted so
 * [TierBarWidgetProvider.onUpdate] can paint the COMPLETE widget
 * synchronously during a host rebind (nav-mode config change) without a
 * DB round-trip — the never-vanish guarantee.
 */
object TierStateStore {
    /** monthTier (border), weekTier (gradient), dayTier (lizard variant). */
    data class Tiers(val monthTier: Int, val weekTier: Int, val dayTier: Int)

    private const val PREFS = "tier_bar_widget"
    private const val KEY_MONTH = "last_month_tier"
    private const val KEY_WEEK = "last_week_tier"
    private const val KEY_DAY = "last_day_tier"

    fun save(context: Context, tiers: Tiers) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MONTH, tiers.monthTier)
            .putInt(KEY_WEEK, tiers.weekTier)
            .putInt(KEY_DAY, tiers.dayTier)
            .apply()
    }

    fun load(context: Context): Tiers {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Tiers(
            monthTier = prefs.getInt(KEY_MONTH, 0),
            weekTier = prefs.getInt(KEY_WEEK, 0),
            dayTier = prefs.getInt(KEY_DAY, 0)
        )
    }
}
