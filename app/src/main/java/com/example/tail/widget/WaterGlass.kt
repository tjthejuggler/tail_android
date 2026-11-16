package com.example.tail.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import java.time.LocalDate

/**
 * ═══════════════════════════════════════════════════════════════════════
 * WATER HABIT — depletion glass for the combined-scene widget.
 * ═══════════════════════════════════════════════════════════════════════
 *
 * A water-type habit ([com.example.tail.data.AppSettings.waterHabits])
 * carries a daily millilitre goal. Progress toward that goal is drawn on
 * the widget as a tall drinking glass on the LEFT side of the scene:
 *
 *   - level = 1 − intake/goal, so the glass starts FULL every morning
 *     (nothing drunk yet) and drains smoothly as the day's total grows,
 *   - once intake ≥ goal the glass is EMPTY and stays there (frozen for
 *     the rest of the day) — extra drinking never refills it,
 *   - no water-type habit configured (or goal ≤ 0) → no glass at all.
 *
 * RENDERING (art-true, direct pixel compositing):
 *   The glass art's ALPHA channel is probed once to derive, per row, the
 *   wall bounds (leftmost/rightmost opaque pixel) — the interior is the
 *   see-through span BETWEEN the walls. The water body is composed in
 *   ART pixel space: a seamless texture (plus a luminous surface band)
 *   written ONLY into interior pixels below the surface line, then the
 *   finished layer is scaled into the glass rect and the glass art draws
 *   ON TOP. The water therefore follows the glass's exact tapered
 *   silhouette — nothing can spill outside the crystal.
 */
object WaterGlass {

    /** Fill quantization steps — keeps depletion visually butter-smooth. */
    private const val FILL_STEPS = 400

    /** Glass height as a fraction of scene height (≈ 3/4, per spec). */
    const val GLASS_HEIGHT_FRAC = 0.74f

    /**
     * Horizontal floor reserved for the glass, as a fraction of scene
     * HEIGHT (the glass is height-driven). Callers shift other left-side
     * figures past this so nothing stands inside the glass.
     */
    const val RESERVED_WIDTH_PER_H = 0.38f

    /** Bottom margin above the scene's bottom edge (fraction of height). */
    private const val GLASS_BOTTOM_PAD_FRAC = 0.03f

    /** Left margin (fraction of width) so the glass hugs the left edge. */
    private const val GLASS_LEFT_PAD_FRAC = 0.015f

    /** Art pixels more opaque than this are walls/highlights → no water. */
    private const val WALL_ALPHA = 160

    /** Water body opacity — strong enough to read through the crystal. */
    private const val WATER_ALPHA = 235

    /** Surface-band colour (luminous ice-blue meniscus). */
    private const val SURFACE_COLOR = 0xCCEFFBFF.toInt()

    /** Per-glass-art interior geometry, computed once. */
    private class Interior(
        /** Per row: exclusive-right bound of the interior span, or −1. */
        val rowEnd: IntArray,
        /** Per row: inclusive-left bound of the interior span, or −1. */
        val rowStart: IntArray,
        /** First row with an interior span. */
        val top: Int,
        /** Last row with an interior span. */
        val bottom: Int
    )

    private val interiorCache = HashMap<String, Interior>()

    /**
     * Today's water level for the scene: 1.0 (full, morning) → 0.0
     * (empty, goal met / frozen). Returns null when no water habit or no
     * valid goal is configured — the caller skips the glass entirely.
     *
     * [db] is the raw habits database; a water habit's daily value IS the
     * millilitre drunk so far (the "Water" habit has stored ml all along).
     */
    fun levelFor(
        db: Map<String, Map<String, Int>>,
        waterHabits: Set<String>,
        waterDailyGoals: Map<String, Int>,
        today: LocalDate = LocalDate.now()
    ): Float? {
        if (waterHabits.isEmpty()) return null
        val todayStr = today.toString()
        var best: Float? = null
        for (name in waterHabits) {
            val goal = (waterDailyGoals[name] ?: com.example.tail.data.DEFAULT_WATER_DAILY_GOAL)
            if (goal <= 0) continue
            val intake = db[name]?.get(todayStr) ?: 0
            // FULL in the morning (intake 0); drains to EMPTY at the goal;
            // over-drinking never refills (clamped at 0).
            val level = 1f - intake.toFloat() / goal.toFloat()
            val clamped = level.coerceIn(0f, 1f)
            best = minOf(best ?: clamped, clamped)
        }
        return best
    }

    /**
     * Draws the depletion glass at the scene's LEFT edge, bottom-anchored
     * like the guest figures. [level] 1.0 = brimful, 0.0 = empty.
     * Returns without drawing when [level] is null (no water habit).
     */
    fun drawGlass(
        canvas: Canvas,
        context: Context,
        w: Int,
        h: Int,
        baseline: Float,
        level: Float?,
        paint: Paint,
        glassCache: HashMap<String, Bitmap?>,
        fillCache: HashMap<String, Bitmap?>
    ) {
        val lv = level ?: return
        val glass = cachedDrawable(context, "scene_water_glass", glassCache) ?: return
        val fill = cachedDrawable(context, "scene_water_fill", fillCache)

        val drawH = h * GLASS_HEIGHT_FRAC
        val drawW = drawH * (glass.width.toFloat() / glass.height)
        val left = (w * GLASS_LEFT_PAD_FRAC).coerceAtMost(w * 0.04f)
        val bottom = (h - h * GLASS_BOTTOM_PAD_FRAC).coerceAtMost(baseline + h * 0.06f)
        val top = bottom - drawH
        val glassRect = RectF(left, top, left + drawW, bottom)

        if (fill != null && lv > 0.001f) {
            val interior = interiorOf(glass)
            if (interior.bottom > interior.top) {
                val water = buildWaterLayer(glass, fill, interior, lv)
                if (water != null) {
                    canvas.drawBitmap(water, null, glassRect, paint)
                    water.recycle()
                }
            }
        }

        // GLASS — drawn ON TOP so its crystal walls/refractions veil the
        // water exactly like the artwork intends.
        canvas.drawBitmap(glass, null, glassRect, paint)
    }

    /**
     * Composes the masked water body at ART resolution: the seamless
     * texture fills every interior pixel below the surface line, with a
     * luminous surface band at the line. Pixels outside the interior
     * (walls, rim, surroundings) stay fully transparent.
     */
    private fun buildWaterLayer(
        glass: Bitmap,
        fill: Bitmap,
        interior: Interior,
        lv: Float
    ): Bitmap? {
        val gw = glass.width
        val gh = glass.height
        // Quantized fill fraction → sub-pixel-smooth depletion.
        val frac = (Math.round(lv * FILL_STEPS).toFloat() / FILL_STEPS)
            .coerceIn(0f, 1f)
        val surfaceY = interior.bottom -
            ((interior.bottom - interior.top) * frac).toInt()

        // Pre-fetch the water texture once for direct sampling.
        val fw = fill.width
        val fh = fill.height
        val fillPx = IntArray(fw * fh)
        fill.getPixels(fillPx, 0, fw, 0, 0, fw, fh)

        // Tile scale in art pixels (glass-width-proportional).
        val tile = (gw * 0.9f).toInt().coerceAtLeast(8)

        val out = IntArray(gw * gh) // zero-initialised → transparent
        val artPx = IntArray(gw)
        for (y in surfaceY..interior.bottom.coerceAtMost(gh - 1)) {
            glass.getPixels(artPx, 0, gw, 0, y, gw, 1)
            val xs = interior.rowStart[y]
            val xe = interior.rowEnd[y]
            if (xs < 0) continue
            val ty = ((y % tile) * fh / tile)
            for (x in xs..xe) {
                // Only see-through art pixels admit water.
                if ((artPx[x] ushr 24) >= WALL_ALPHA) continue
                val tx = ((x % tile) * fw / tile)
                val c = fillPx[ty * fw + tx]
                // Composite: water colour at WATER_ALPHA over the tile's
                // own luminance modulation (keep tile RGB, set alpha).
                out[y * gw + x] = (c and 0x00FFFFFF) or
                    (WATER_ALPHA shl 24)
            }
        }

        // Luminous meniscus band at the surface (clipped by the same
        // interior rule so it hugs the walls too).
        val bandTop = (surfaceY - gh * 0.004f).toInt().coerceAtLeast(0)
        val bandBot = (surfaceY + gh * 0.006f).toInt().coerceAtMost(gh - 1)
        for (y in bandTop..bandBot) {
            val xs = interior.rowStart[y]
            val xe = interior.rowEnd[y]
            if (xs < 0) continue
            glass.getPixels(artPx, 0, gw, 0, y, gw, 1)
            for (x in xs..xe) {
                if ((artPx[x] ushr 24) >= WALL_ALPHA) continue
                out[y * gw + x] = SURFACE_COLOR
            }
        }

        val bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
        bmp.setPixels(out, 0, gw, 0, 0, gw, gh)
        return bmp
    }

    /** Interior geometry of the glass art (cached per name). */
    private fun interiorOf(glass: Bitmap): Interior {
        interiorCache["scene_water_glass"]?.let { return it }
        val gw = glass.width
        val gh = glass.height
        val rowStart = IntArray(gh) { -1 }
        val rowEnd = IntArray(gh) { -1 }
        val px = IntArray(gw)
        var first = -1
        var last = -1
        for (y in 0 until gh) {
            glass.getPixels(px, 0, gw, 0, y, gw, 1)
            var lo = -1
            var hi = -1
            for (x in 0 until gw) {
                if ((px[x] ushr 24) >= WALL_ALPHA) {
                    if (lo < 0) lo = x
                    hi = x
                }
            }
            if (lo >= 0 && hi > lo) {
                // Interior span strictly between the two wall pixels.
                rowStart[y] = lo + 1
                rowEnd[y] = hi - 1
                if (first < 0) first = y
                last = y
            }
        }
        val interior = Interior(
            rowEnd = rowEnd,
            rowStart = rowStart,
            top = first.coerceAtLeast(0),
            bottom = last
        )
        interiorCache["scene_water_glass"] = interior
        return interior
    }

    /** Decode + cache a drawable-nodpi bitmap by resource name. */
    private fun cachedDrawable(
        context: Context,
        name: String,
        cache: HashMap<String, Bitmap?>
    ): Bitmap? {
        cache[name]?.let { return it }
        val resId = context.resources.getIdentifier(
            name, "drawable", context.packageName
        )
        val bmp = if (resId != 0)
            BitmapFactory.decodeResource(context.resources, resId) else null
        cache[name] = bmp
        return bmp
    }
}
