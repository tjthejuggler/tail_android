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
        private val backgroundCache = HashMap<Long, Bitmap>()

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
                val views = buildRenderViews(appContext, tiers, mgr, intArrayOf(id), id)
                mgr.updateAppWidget(id, views)
            }
        }


        /**
         * Builds the COMPLETE widget RemoteViews synchronously from the
         * given tiers + cached figure bitmaps (no provider queries, no
         * DB). Used by both the async refresh and the synchronous rebind
         * answer so host-painted frames match the settled state.
         * [widgetId] keys the per-day ground variety seed.
         */
        fun buildRenderViews(
            appContext: Context,
            tiers: TierStateStore.Tiers,
            mgr: AppWidgetManager,
            ids: IntArray,
            widgetId: Int = ids.firstOrNull() ?: 0
        ): RemoteViews {
            val kenga = cachedFigure(appContext, SceneFigureClient.App.KENGA)
            val octo = cachedFigure(appContext, SceneFigureClient.App.OCTO)

            // Tier-bar feature parity: colours + config + badge count.
            val weekColor = tierColor(appContext, tiers.weekTier)
            val dayColor = tierColor(appContext, tiers.dayTier)
            val monthColor = tierColor(appContext, tiers.monthTier)
            val config = TierBarWidgetConfig.load(appContext)
            val habitScreenCount = try {
                kotlinx.coroutines.runBlocking {
                    SettingsRepository(appContext).settingsFlow.first()
                }.habitScreens.size
            } catch (_: Exception) { 0 }
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
                        tiers.dayTier, aspect, tiers.dayPoints, id, kenga, octo
                    )
                )
            }
            return views
        }

        /** Current cached figure bitmap for [app] (memory or disk). */
        private fun cachedFigure(
            appContext: Context,
            app: SceneFigureClient.App
        ): Bitmap? {
            // Memory fast path (single entry per app); without a fresh hash
            // probe here (synchronous path must not query providers) fall
            // back to the disk cache decode.
            figureState[app]?.let { return it.bitmap }
            val fig = SceneFigureClient.fetchIfStale(appContext, app)
            return fig.bitmap?.also { figureState[app] = FigureEntry(fig.hash, it) }
        }

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
            octo: Bitmap?
        ): Bitmap {
            val kHash = figureState[SceneFigureClient.App.KENGA]?.hash ?: ""
            val oHash = figureState[SceneFigureClient.App.OCTO]?.hash ?: ""
            val key = (monthTier.toLong() shl 56) or
                (weekTier.toLong() shl 52) or
                (dayTier.toLong() shl 48) or
                ((dayPoints.coerceIn(-1, 127).toLong() and 0x7FL) shl 40) or
                (((aspect * 10).toInt().toLong().coerceIn(0, 255)) shl 32) or
                ((widgetId.toLong() and 0x7F) shl 24) or
                (((kHash.hashCode().toLong()) and 0xFFFL) shl 12) or
                ((oHash.hashCode().toLong()) and 0xFFFL)
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

            // 4. GUESTS first (right pair) so the lizard's tail can tuck
            //    slightly behind them. Both bottom-anchored on the baseline.
            val guestH = h * 0.74f
            val guestMargin = w * 0.012f
            val octoW = octo?.let { it.width * (guestH / it.height) } ?: 0f
            val kengaW = kenga?.let { it.width * (guestH / it.height) } ?: 0f
            // Octo rightmost, Kenga immediately left of him.
            drawGuest(canvas, octo, guestH, baseline,
                rightEdge = w - guestMargin, paint = paint)
            drawGuest(canvas, kenga, guestH, baseline,
                rightEdge = w - guestMargin - octoW - w * 0.008f, paint = paint)

            // 3. LIZARD — today's tier / per-point morph, left of centre,
            //    tail reaching toward the guests (drawn after -> on top).
            lizardBitmap(context, dayTier, dayPoints)?.let { lizard ->
                val drawH = h * 0.42f
                val drawW = drawH * (lizard.width.toFloat() / lizard.height)
                // Centre the figure in the free space LEFT of the guests,
                // leaving ~6% width so the tail overlaps their edge.
                val guestsTotal = octoW + kengaW + w * 0.02f
                val freeLeft = 0f
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
