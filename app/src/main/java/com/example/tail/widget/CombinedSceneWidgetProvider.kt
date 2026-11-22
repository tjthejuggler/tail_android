package com.example.tail.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import com.example.tail.MainActivity
import com.example.tail.R
import com.example.tail.data.HabitsLoadResult
import com.example.tail.data.HabitsRepository
import com.example.tail.data.NotificationStore
import com.example.tail.data.SettingsRepository
import com.example.tail.data.computeTaskerStats
import com.example.tail.data.habitPointsTier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * ═══════════════════════════════════════════════════════════════════════
 * COMBINED SCENE WIDGET (4x2) — the tall sibling of the tier bar.
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Same three-layer independent colour system as [TierBarWidgetProvider]
 * (sky = monthly tier, ground = weekly tier, lizard = today's tier + the
 * per-point morph art), rendered with the taller scene_tall_* art, PLUS
 * the two live guest figures pulled from the sibling apps:
 *
 *   · KENGA kangaroo-man (right side, left of the pair)
 *   · OCTO  octopus-man  (right side, rightmost)
 *
 * Both guests cluster on the RIGHT of the widget; the lizard sits left of
 * centre with its tail reaching toward them.
 *
 * Feature parity with the tier bar: notification-count badge, quick-launch
 * buttons ([TierBarWidgetConfig]) and invisible habit-screen zones on the
 * left area; the right guest strip deep-links into the two apps.
 *
 * GROUND VARIETY: for each tier BOTH a legacy tier_bar_env_t{N} (4:1) and
 * the new scene_tall_env_t{N} (3:1) ground exist; the widget picks one
 * pseudo-randomly per (widgetId, date), seeded deterministically so the
 * choice is stable across repaints of the same day.
 *
 * Refresh triggers: widget lifecycle (onUpdate/resize), every habit
 * increment (AppHooks.refreshWidgets), and the ACTION_SCENE_FIGURES_CHANGED
 * broadcast Kenga/Octo send when their figure data changes. Figures are
 * hash-gated ([SceneFigureClient.fetchIfStale]) so a refresh costs one
 * cheap provider query unless the art actually changed.
 */
class CombinedSceneWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val TAG = "CombinedScene"

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Alternating layout ids (AIO rebind-storm defence, see tier bar). */
        private val layoutFlip = java.util.concurrent.atomic.AtomicBoolean(false)

        // ── in-memory caches ─────────────────────────────────────────────
        // ONE entry per guest app, overwritten on every pull. (The previous
        // (app, hash)-keyed map kept stale entries alive and its
        // lastOrNull-based lookup — arbitrary on a HashMap — served the OLD
        // art after a hash change: the "widget never updates" bug.)
        private data class FigureEntry(val hash: String, val bitmap: Bitmap)
        private val figureState = HashMap<SceneFigureClient.App, FigureEntry>()
        private val layerCache = HashMap<String, Bitmap?>()
        private val lizardCache = HashMap<String, Bitmap?>()
        private val backgroundCache = HashMap<String, Bitmap>()
        private val waterGlassCache = HashMap<String, Bitmap?>()
        private val waterFillCache = HashMap<String, Bitmap?>()

        /**
         * Last-known water-glass level (0..1000 = fill fraction ×1000;
         * −1 = no water habit configured), persisted so the synchronous
         * rebind path can paint the glass without a DB round-trip —
         * same never-vanish guarantee as [TierStateStore].
         */
        object WaterLevelStore {
            private const val PREFS = "tier_bar_widget"
            private const val KEY = "scene_water_level_x1000"
            private const val KEY_HABIT = "scene_water_habit_name"

            fun save(context: Context, level: Float?, habitName: String? = null) {
                val v = if (level == null) -1
                        else (level * 1000).toInt().coerceIn(0, 1000)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().apply {
                        putInt(KEY, v)
                        if (habitName != null) putString(KEY_HABIT, habitName)
                    }
                    .apply()
            }

            /**
             * Persists ONLY the habit name (self-heal path for a missing or
             * stale name while the glass level stays valid). Writing the
             * name alone must never resurrect a cleared glass: the level
             * key is left untouched either way.
             */
            fun saveHabitNameOnly(context: Context, habitName: String) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putString(KEY_HABIT, habitName)
                    .apply()
            }

            fun load(context: Context): Float? {
                val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getInt(KEY, -1)
                return if (v < 0) null else (v / 1000f).coerceIn(0f, 1f)
            }

            /** Last-known water habit the glass represents (null = none). */
            fun loadHabitName(context: Context): String? =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY_HABIT, null)
        }

        /**
         * Recomputes tiers, pulls both guest figures (hash-gated) and
         * pushes fresh RemoteViews to every placed combined-scene widget.
         */
        suspend fun refreshAll(context: Context) {
            val appContext = context.applicationContext
            val mgr = AppWidgetManager.getInstance(appContext)
            val ids = mgr.getAppWidgetIds(
                ComponentName(appContext, CombinedSceneWidgetProvider::class.java)
            )

            // Tiers (same math as the tier bar) — persisted so the
            // synchronous rebind path can paint without a DB round-trip.
            var today = 0
            var avg7 = 0.0
            var avg30 = 0.0
            var habitScreenCount = 0
            var waterLevel: Float? = null
            var waterHabitName: String? = null
            try {
                val settings = SettingsRepository(appContext).settingsFlow.first()
                habitScreenCount = settings.habitScreens.size
                if (settings.fileUri.isNotEmpty()) {
                    val result = HabitsRepository().loadDatabaseResult(
                        Uri.parse(settings.fileUri), appContext
                    )
                    val db = (result as? HabitsLoadResult.Success)?.db
                    if (db != null) {
                        // Water glass level (1 = full morning glass, 0 = goal
                        // met, null = no water-type habit configured).
                        waterLevel = WaterGlass.levelFor(
                            db, settings.waterHabits, settings.waterDailyGoals
                        )
                        // The habit the glass represents — the tap zone's
                        // deep link targets this habit's grid square.
                        waterHabitName = WaterGlass.primaryHabitName(
                            db, settings.waterHabits, settings.waterDailyGoals
                        )
                        val stats = computeTaskerStats(
                            db = db,
                            dividers = settings.habitDividers,
                            noPointsHabits = settings.noPointsHabits,
                            secondaryValueFallbackHabits = settings.secondaryValueFallbackHabits,
                            timerMinutesPrimaryHabits = settings.widgetTimerMinutesPrimary,
                            invertedBinaryHabits = settings.invertedBinaryHabits,
                            secondaryValueHabits = settings.secondaryValueHabits,
                            garminLinkedHabits = settings.garminHabitLinks.keys
                        )
                        today = stats.today
                        avg7 = stats.avg7
                        avg30 = stats.avg30
                    }
                }
            } catch (_: Exception) {
                // Keep last-known metrics on DB hiccups — widget stays alive.
            }
            val tiers = TierStateStore.Tiers(
                monthTier = habitPointsTier(avg30.roundToInt()),
                weekTier = habitPointsTier(avg7.roundToInt()),
                dayTier = habitPointsTier(today),
                dayPoints = today
            )
            TierStateStore.save(appContext, tiers)
            // Persist the water level + habit name for the synchronous
            // rebind path.
            WaterLevelStore.save(appContext, waterLevel, waterHabitName)

            // Guest figures — hash-gated provider pull (no-op when unchanged).
            // Overwrites the per-app entry: old art can never resurface.
            for (app in SceneFigureClient.App.entries) {
                val fig = SceneFigureClient.fetchIfStale(appContext, app)
                if (fig.bitmap != null) {
                    figureState[app] = FigureEntry(fig.hash, fig.bitmap)
                }
            }

            if (ids.isEmpty()) return
            for (id in ids) {
                val views = buildRenderViews(
                    appContext, tiers, mgr, intArrayOf(id), id, waterLevel
                )
                mgr.updateAppWidget(id, views)
            }
        }


        /**
         * Builds the COMPLETE widget RemoteViews synchronously from the
         * given tiers + cached figure bitmaps (no provider queries, no
         * DB). Used by both the async refresh and the synchronous rebind
         * answer so host-painted frames match the settled state.
         * [widgetId] keys the per-day ground variety seed.
         * [waterLevelOverride] lets the async refresh pass the freshly
         * computed glass level; when null the persisted last-known level
         * is used (synchronous rebind path).
         */
        fun buildRenderViews(
            appContext: Context,
            tiers: TierStateStore.Tiers,
            mgr: AppWidgetManager,
            ids: IntArray,
            widgetId: Int = ids.firstOrNull() ?: 0,
            waterLevelOverride: Float? = null
        ): RemoteViews {
            val kenga = cachedFigure(appContext, SceneFigureClient.App.KENGA)
            val octo = cachedFigure(appContext, SceneFigureClient.App.OCTO)

            // Tier-bar feature parity: colours + config + badge count.
            val weekColor = tierColor(appContext, tiers.weekTier)
            val dayColor = tierColor(appContext, tiers.dayTier)
            val monthColor = tierColor(appContext, tiers.monthTier)
            val config = TierBarWidgetConfig.load(appContext)
            // Whole settings snapshot (not just habitScreens.size): the
            // water-glass tap zone below also needs waterHabits/waterDailyGoals
            // as a fallback when the persisted habit name is missing/stale.
            val settingsSnapshot = try {
                kotlinx.coroutines.runBlocking {
                    SettingsRepository(appContext).settingsFlow.first()
                }
            } catch (_: Exception) { null }
            val habitScreenCount = settingsSnapshot?.habitScreens?.size ?: 0
            val notifCount = try {
                kotlinx.coroutines.runBlocking {
                    NotificationStore(appContext).notificationsFlow.first().size
                }
            } catch (_: Exception) { 0 }

            // Notifications popup deep link (same target as the tier bar).
            val notifIntent = Intent(appContext, MainActivity::class.java).apply {
                action = "com.example.tail.TIER_BAR.NOTIFICATIONS"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, "grid")
                putExtra(MainActivity.EXTRA_OPEN_NOTIFICATIONS, true)
            }
            val notifPending = PendingIntent.getActivity(
                appContext, 14242, notifIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Water glass level: resolved BEFORE the views are built so the
            // tap zone below can be bound from it (async refresh override,
            // else last persisted).
            val waterLevelFinal = waterLevelOverride ?: WaterLevelStore.load(appContext)
            val layoutId = if (layoutFlip.getAndSet(!layoutFlip.get())) {
                R.layout.widget_scene_combined_b
            } else {
                R.layout.widget_scene_combined
            }
            val views = RemoteViews(appContext.packageName, layoutId).apply {
                // Default tap: anywhere in the habit-zone container opens
                // Tail (individual zones override when configured).
                setOnClickPendingIntent(
                    R.id.scene_habit_zones, openTailPendingIntent(appContext)
                )
                // Water-glass tap zone: visible only while the glass is
                // drawn (a water-type habit with a valid goal exists). The
                // tap deep-links into the grid with THAT habit's input
                // dialog open — as if its square had been tapped. The
                // habit name is resolved with a settings fallback (see
                // waterGlassPendingIntent) so a missing/stale persisted
                // name can never degrade the tap to a plain grid open.
                if (waterLevelFinal != null) {
                    setViewVisibility(R.id.scene_water_zone, android.view.View.VISIBLE)
                    setOnClickPendingIntent(
                        R.id.scene_water_zone,
                        waterGlassPendingIntent(appContext, settingsSnapshot)
                    )
                } else {
                    setViewVisibility(R.id.scene_water_zone, android.view.View.GONE)
                }
                // Notification badge.
                if (notifCount > 0) {
                    setViewVisibility(R.id.scene_notif, android.view.View.VISIBLE)
                    setImageViewBitmap(
                        R.id.scene_notif,
                        buildBadge(dayColor, weekColor, monthColor,
                            if (notifCount > 9) "+" else notifCount.toString())
                    )
                    setOnClickPendingIntent(R.id.scene_notif, notifPending)
                } else {
                    setViewVisibility(R.id.scene_notif, android.view.View.GONE)
                }
                // Quick-launch buttons (TierBarWidgetConfig parity).
                for (spec in TierBarWidgetProvider.BUTTONS) {
                    val viewId = sceneButtonViewId(spec.key)
                    if (viewId == 0) continue
                    if (config[spec.key] == true) {
                        setViewVisibility(viewId, android.view.View.VISIBLE)
                        setImageViewBitmap(
                            viewId,
                            buildBadge(dayColor, weekColor, monthColor, spec.glyph)
                        )
                        setOnClickPendingIntent(
                            viewId, sceneLaunchPendingIntent(appContext, spec)
                        )
                    } else {
                        setViewVisibility(viewId, android.view.View.GONE)
                    }
                }
                // Habit-screen zones (left area).
                val zoneIds = intArrayOf(
                    R.id.scene_zone_0, R.id.scene_zone_1, R.id.scene_zone_2,
                    R.id.scene_zone_3, R.id.scene_zone_4, R.id.scene_zone_5,
                    R.id.scene_zone_6, R.id.scene_zone_7
                )
                val selected = TierBarWidgetConfig.loadScreens(appContext)
                    .filter { it == TierBarWidgetConfig.MAIN || it in 0 until habitScreenCount }
                    .sorted()
                zoneIds.forEachIndexed { i, zoneId ->
                    if (i < selected.size) {
                        setViewVisibility(zoneId, android.view.View.VISIBLE)
                        setOnClickPendingIntent(
                            zoneId, sceneScreenPendingIntent(appContext, selected[i], i)
                        )
                    } else {
                        setViewVisibility(zoneId, android.view.View.GONE)
                    }
                }
                // Guest zones -> their apps (hidden when not installed).
                val kengaIntent = launchIntent(appContext, SceneFigureClient.App.KENGA)
                val octoIntent = launchIntent(appContext, SceneFigureClient.App.OCTO)
                if (kengaIntent != null) {
                    setOnClickPendingIntent(R.id.scene_zone_kenga, kengaIntent)
                } else {
                    setViewVisibility(R.id.scene_zone_kenga, android.view.View.GONE)
                }
                if (octoIntent != null) {
                    setOnClickPendingIntent(R.id.scene_zone_octo, octoIntent)
                } else {
                    setViewVisibility(R.id.scene_zone_octo, android.view.View.GONE)
                }
            }

            // Water glass level: resolved above (before the views build),
            // reused for the scene bitmap below.
            val waterLevel = waterLevelFinal

            // Kenga's confidence (last-known zones count, no provider query):
            // ≥4 colored areas today -> he renders in front of the lizard.
            val kengaInFront = kengaConfident(appContext)

            // Per-widget scene bitmap sized to the widget's real aspect.
            for (id in ids) {
                val opts = mgr.getAppWidgetOptions(id)
                val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 320)
                val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 110)
                val aspect = (wDp.toFloat() / hDp.toFloat()).coerceIn(1.2f, 3.5f)
                views.setImageViewBitmap(
                    R.id.scene_bg,
                    buildScene(
                        appContext, tiers.monthTier, tiers.weekTier,
                        tiers.dayTier, aspect, tiers.dayPoints, id, kenga, octo,
                        waterLevel, kengaInFront
                    )
                )
            }
            return views
        }

        /** Current cached figure bitmap for [app] (memory or disk only). */
        private fun cachedFigure(
            appContext: Context,
            app: SceneFigureClient.App
        ): Bitmap? {
            // Memory fast path (single entry per app).
            figureState[app]?.let { return it.bitmap }
            // DISK-ONLY fallback: this runs on the synchronous rebind path
            // (onUpdate/onAppWidgetOptionsChanged), which must never touch
            // providers. fetchIfStale here could block indefinitely when the
            // guest app is frozen (Samsung Freecess "BG freezed"), hanging
            // the widget update before any RemoteViews land — the widget
            // then sits on the host's "Loading…" placeholder forever. The
            // async refreshAll is responsible for provider pulls; seeding
            // the memory entry from disk lets it render last-known figures.
            val fig = SceneFigureClient.diskFigure(appContext, app)
            return fig.bitmap?.also { figureState[app] = FigureEntry(fig.hash, it) }
        }

        /**
         * Kenga's confidence: how many DISTINCT muscle areas he colored
         * today (provider `zones` column; last-known value, no query here).
         * Threshold mirrors Kenga's KangarooBodyGeometry.CONFIDENT_ZONE_COUNT
         * (kept in sync by the figure-provider ADR): at ≥4 areas he has been
         * active enough today to step IN FRONT of the lizard.
         */
        private const val KENGA_CONFIDENT_ZONES = 4

        private fun kengaConfident(appContext: Context): Boolean =
            SceneFigureClient.cachedZones(appContext, SceneFigureClient.App.KENGA) >=
                KENGA_CONFIDENT_ZONES

        private fun launchIntent(
            context: Context,
            app: SceneFigureClient.App
        ): PendingIntent? {
            val intent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                ?: return null
            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )
            return PendingIntent.getActivity(
                context,
                app.packageName.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun openTailPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.example.tail.SCENE.OPEN"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, "grid")
            }
            return PendingIntent.getActivity(
                context, 4243, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /**
         * Water-glass tap zone: deep-links into the grid with the water
         * habit's square auto-tapped (input dialog open). The habit NAME is
         * resolved at PendingIntent CREATION time (the RemoteViews path
         * can't defer reads), and FLAG_UPDATE_CURRENT refreshes the extra
         * on every widget rebind — it always matches the glass being drawn.
         *
         * Resolution order, with self-healing: the persisted name
         * ([WaterLevelStore]) wins when it still names a configured
         * water-type habit; otherwise the FIRST configured water habit
         * from [settingsSnapshot] is used AND written back to the store,
         * so a missing/stale persisted name (pre-deep-link install
         * upgrade, habit rename, prefs hiccup) can never silently degrade
         * the tap into a plain grid open — the popup regression
         * (2026-11-22: tap opened the app but not the water input).
         */
        private fun waterGlassPendingIntent(
            context: Context,
            settingsSnapshot: com.example.tail.data.AppSettings?
        ): PendingIntent {
            val waterHabits = settingsSnapshot?.waterHabits ?: emptySet()
            val waterGoals = settingsSnapshot?.waterDailyGoals ?: emptyMap()
            val persisted = WaterLevelStore.loadHabitName(context)
            val habitName = when {
                !persisted.isNullOrBlank() && persisted in waterHabits -> persisted
                // Self-heal: first configured water habit with a valid goal.
                else -> waterHabits.firstOrNull {
                    (waterGoals[it] ?: com.example.tail.data.DEFAULT_WATER_DAILY_GOAL) > 0
                }?.also { WaterLevelStore.saveHabitNameOnly(context, it) }
            }
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.example.tail.SCENE.WATER"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_OPEN_ROUTE, "grid")
                if (!habitName.isNullOrBlank()) {
                    putExtra(MainActivity.EXTRA_OPEN_HABIT, habitName)
                }
            }
            return PendingIntent.getActivity(
                context, 4244, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        // ── tier-bar parity: buttons / screen zones / badge ──────────────

        private fun sceneButtonViewId(key: String): Int = when (key) {
            "grid" -> R.id.scene_btn_grid
            "map" -> R.id.scene_btn_map
            "app_stats" -> R.id.scene_btn_app_stats
            "chess_stats" -> R.id.scene_btn_chess_stats
            "settings" -> R.id.scene_btn_settings
            else -> 0
        }

        private fun sceneLaunchPendingIntent(
            context: Context, spec: TierBarWidgetProvider.Companion.ButtonSpec
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = "com.example.tail.TIER_BAR.${spec.key.uppercase()}"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                spec.route?.let { putExtra(MainActivity.EXTRA_OPEN_ROUTE, it) }
            }
            return PendingIntent.getActivity(
                context,
                spec.key.hashCode() + 77,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun sceneScreenPendingIntent(
            context: Context, screenIndex: Int, zoneIndex: Int
        ): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = if (screenIndex == TierBarWidgetConfig.MAIN)
                    "com.example.tail.TIER_BAR.MAIN"
                else "com.example.tail.TIER_BAR.SCREEN$screenIndex"
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (screenIndex != TierBarWidgetConfig.MAIN) {
                    putExtra(MainActivity.EXTRA_OPEN_ROUTE, "grid")
                    putExtra(MainActivity.EXTRA_OPEN_SCREEN_INDEX, screenIndex)
                }
            }
            return PendingIntent.getActivity(
                context,
                9000 + zoneIndex,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        /** Same badge art as the tier bar (fill/border/glyph tier colours). */
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

        private fun tierColor(context: Context, tier: Int): Int {
            val resId = context.resources.getIdentifier(
                "ic_launcher_tier${tier.coerceIn(0, 12)}", "color", context.packageName
            )
            return if (resId != 0) androidx.core.content.ContextCompat.getColor(context, resId)
            else 0xFF33AA55.toInt()
        }

        // ─────────────────────────────────────────────────────────────────
        // Scene compositing (sky / ground / lizard / guests)
        // ─────────────────────────────────────────────────────────────────

        /**
         * Scene layout (w = aspect·h):
         *   sky    — full frame (monthly tier, scene_tall_sky_t{N})
         *   ground — bottom band (weekly tier): per (widgetId, day) a
         *            pseudo-random pick between the legacy 4:1
         *            tier_bar_env_t{N} and the new 3:1 scene_tall_env_t{N}
         *   lizard — left-of-centre, standing on the ground line (daily
         *            tier + per-point morph art), tail reaching right
         *   guests — Kenga then Octo, right-anchored pair, larger
         *   glass  — water-habit depletion glass, LEFT edge, ~3/4 height
         *            (only when a water-type habit is configured)
         */
        private fun buildScene(
            context: Context,
            monthTier: Int,
            weekTier: Int,
            dayTier: Int,
            aspect: Float,
            dayPoints: Int,
            widgetId: Int,
            kenga: Bitmap?,
            octo: Bitmap?,
            waterLevel: Float? = null,
            kengaInFront: Boolean = false
        ): Bitmap {
            val kHash = figureState[SceneFigureClient.App.KENGA]?.hash ?: ""
            val oHash = figureState[SceneFigureClient.App.OCTO]?.hash ?: ""
            // String key — every input occupies its own labelled slot so
            // no bit-packing collisions can serve a stale scene (the
            // earlier OR-fold collided water-level bits with the figure
            // hash folds, hiding the water behind a cached frame).
            val waterKey = if (waterLevel == null) "n"
                else "${(waterLevel * 1000).toInt().coerceIn(0, 1000)}"
            val key = "m${monthTier}w${weekTier}d${dayTier}" +
                "p${dayPoints}a${(aspect * 10).toInt()}" +
                "i${widgetId}k${kHash}o${oHash}g$waterKey" +
                (if (kengaInFront) "f1" else "f0")
            backgroundCache[key]?.let { return it }
            if (backgroundCache.size > 8) backgroundCache.clear()

            val h = 500
            val w = (h * aspect).toInt().coerceIn(600, 1600)
            val radius = h / 8f
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            val clip = Path().apply {
                addRoundRect(
                    0f, 0f, w.toFloat(), h.toFloat(),
                    radius, radius, Path.Direction.CW
                )
            }
            canvas.save()
            canvas.clipPath(clip)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            val full = android.graphics.RectF(0f, 0f, w.toFloat(), h.toFloat())

            // 1. SKY — monthly tier, edge to edge (2:1 source, uniform stretch).
            layerBitmap(context, "scene_tall_sky_t", monthTier)?.let {
                canvas.drawBitmap(it, null, full, paint)
            }

            // 2. GROUND — weekly tier, bottom-anchored band. Variety: the
            //    legacy tier_bar_env art and the new scene_tall_env art are
            //    both in the APK; pick pseudo-randomly per (widgetId, date)
            //    so consecutive repaints never flicker between them.
            val groundH = (w / 3f).coerceAtMost(h * 0.55f)
            groundBitmap(context, weekTier, widgetId)?.let { env ->
                canvas.drawBitmap(
                    env, null,
                    android.graphics.RectF(
                        0f, h - groundH, w.toFloat(), h.toFloat()
                    ),
                    paint
                )
            }

            // Standing line: a little way into the ground band.
            val baseline = h - groundH * 0.30f

            // 2½. WATER GLASS — depletion glass for water-type habits,
            //     LEFT edge, ~3/4 of the widget height (drawn before the
            //     figures so characters can slightly overlap it, exactly
            //     like the guests tuck behind each other).
            WaterGlass.drawGlass(
                canvas, context, w, h, baseline, waterLevel, paint,
                waterGlassCache, waterFillCache
            )
            // Reserve the glass's width so the lizard never stands in it.
            val glassReserved = if (waterLevel != null)
                h * WaterGlass.RESERVED_WIDTH_PER_H + w * 0.01f
            else 0f

            // 4/3. GUESTS and LIZARD — z-order depends on Kenga's confidence:
            //    default: guests first, lizard after -> his tail tucks over
            //    them. Confident Kenga (≥4 areas colored today): lizard
            //    first, guests after -> Kenga steps in FRONT of the tail
            //    (he's been active; he takes the front of the stage).
            val guestH = h * 0.74f
            val guestMargin = w * 0.012f
            val octoW = octo?.let { it.width * (guestH / it.height) } ?: 0f
            val kengaW = kenga?.let { it.width * (guestH / it.height) } ?: 0f
            fun drawGuests() {
                // Octo rightmost, Kenga immediately left of him.
                drawGuest(canvas, octo, guestH, baseline,
                    rightEdge = w - guestMargin, paint = paint)
                drawGuest(canvas, kenga, guestH, baseline,
                    rightEdge = w - guestMargin - octoW - w * 0.008f, paint = paint)
            }
            fun drawLizard() {
                lizardBitmap(context, dayTier, dayPoints)?.let { lizard ->
                    val drawH = h * 0.42f
                    val drawW = drawH * (lizard.width.toFloat() / lizard.height)
                    // Centre the figure in the free space LEFT of the guests,
                    // leaving ~6% width so the tail overlaps their edge.
                    // With the water glass present, shift the free-space LEFT
                    // edge past the glass so the two never collide.
                    val guestsTotal = octoW + kengaW + w * 0.02f
                    val freeLeft = glassReserved
                    val freeRight = w - guestsTotal
                    val left = ((freeLeft + freeRight - drawW) / 2f)
                        .coerceAtLeast(-drawW * 0.10f)
                    canvas.drawBitmap(
                        lizard, null,
                        android.graphics.RectF(
                            left, baseline - drawH, left + drawW, baseline
                        ),
                        paint
                    )
                }
            }
            if (kengaInFront) {
                drawLizard()
                drawGuests()
            } else {
                drawGuests()
                drawLizard()
            }

            canvas.restore()
            backgroundCache[key] = bmp
            return bmp
        }

        /** Right-anchored, uniformly scaled guest figure on [baseline]. */
        private fun drawGuest(
            canvas: Canvas,
            figure: Bitmap?,
            drawH: Float,
            baseline: Float,
            rightEdge: Float,
            paint: Paint
        ) {
            if (figure == null) return
            val scale = drawH / figure.height
            val drawW = figure.width * scale
            canvas.drawBitmap(
                figure, null,
                android.graphics.RectF(
                    rightEdge - drawW, baseline - drawH, rightEdge, baseline
                ),
                paint
            )
        }

        /**
         * Ground variety: deterministic per (tier, widgetId, date) pick
         * between the legacy tier_bar_env_t{N} and the new
         * scene_tall_env_t{N} art (both ship in the APK).
         */
        private fun groundBitmap(context: Context, tier: Int, widgetId: Int): Bitmap? {
            val t = tier.coerceIn(0, 12)
            val daySeed = LocalDate.now().toEpochDay()
            val useLegacy = ((widgetId.toLong() * 31L + daySeed) ushr 1) % 2L == 0L
            val prefix = if (useLegacy) "tier_bar_env_t" else "scene_tall_env_t"
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

        /** Tier art lookup in drawable-nodpi (sky uses the scheme directly). */
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
         * Same morph art as the tier bar (shared assets): per-point growth
         * asset tier_bar_lizard_m{tier}_p{NN} first, milestone strip
         * tier_bar_lizard_t{tier} as the fallback (see TierBarWidgetProvider).
         */
        private fun lizardBitmap(context: Context, tier: Int, points: Int): Bitmap? {
            val t = tier.coerceIn(0, 12)
            val key = if (points >= 0) "m${t}_p${points.coerceAtLeast(0)}" else "t$t"
            lizardCache[key]?.let { return it }
            val resId = context.resources.getIdentifier(
                "tier_bar_lizard_$key", "drawable", context.packageName
            )
            val bmp = if (resId != 0)
                BitmapFactory.decodeResource(context.resources, resId) else null
            val result = if (bmp == null && points >= 0)
                lizardBitmap(context, t, -1) else bmp
            if (lizardCache.size > 24) lizardCache.clear()
            lizardCache[key] = result
            return result
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        // Synchronous complete frame first (tier bar pattern), then the
        // async refresh upgrades figures/tiers, then rebind-storm repaints.
        paintCurrent(context, appWidgetManager, appWidgetIds)
        scope.launch { refreshAll(context) }
        scheduleRebindRepaints(context.applicationContext)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        paintCurrent(context, appWidgetManager, intArrayOf(appWidgetId))
        scope.launch { refreshAll(context) }
        scheduleRebindRepaints(context.applicationContext)
    }

    private val rebindHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun scheduleRebindRepaints(appContext: Context) {
        for (delayMs in longArrayOf(2_500L, 7_000L, 15_000L)) {
            rebindHandler.postDelayed({
                try {
                    scope.launch { refreshAll(appContext) }
                } catch (_: Exception) { /* best-effort */ }
            }, delayMs)
        }
    }

    /** Synchronous render from persisted tier state + disk-cached figures. */
    private fun paintCurrent(
        context: Context,
        mgr: AppWidgetManager,
        ids: IntArray
    ) {
        try {
            val appContext = context.applicationContext
            val tiers = TierStateStore.load(appContext)
            for (id in ids) {
                val views = buildRenderViews(appContext, tiers, mgr, intArrayOf(id), id)
                mgr.updateAppWidget(id, views)
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "paintCurrent failed: ${e.message}")
        }
    }
}
