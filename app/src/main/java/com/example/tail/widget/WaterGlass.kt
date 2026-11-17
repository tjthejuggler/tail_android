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
 *   written ONLY into interior pixels below the surface line and ABOVE
 *   the art's solid glass base (the thick foot holds no water — the
 *   water column is capped just above it), then the finished layer is
 *   scaled into the glass rect and the glass art draws ON TOP. The water
 *   therefore follows the glass's exact tapered silhouette — nothing can
 *   spill outside the crystal.
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

    /**
     * Water body opacity — slightly translucent like real water, while
     * staying clearly visible through the crystal.
     */
    private const val WATER_ALPHA = 200

    /**
     * Fraction of the interior span (bottom-up) occupied by the art's
     * SOLID glass base. Measured from the asset's alpha profile: the
     * interior turns milky-opaque at ≈80% down the art (rows ≈1134–1397
     * of 1417), so the base is ~19% of the interior. The flat floor line
     * sits INSIDE the base's top ellipse (rows ≈1189) so the domed floor
     * below fills the oval hollow down there; the milky art draws OVER
     * the water, keeping the foot solid.
     */
    private const val BASE_FRAC = 0.15f

    /**
     * Depth of the surface dome, as a fraction of art height. The free
     * surface projects as an ellipse with the same perspective as the
     * art's rim opening: highest at the glass centre, meeting the walls
     * at the flat level line. ≈3% keeps the bow subtle but readable.
     */
    private const val SURFACE_BOW_FRAC = 0.030f

    /**
     * Depth of the floor dome, as a fraction of art height. Bottom
     * ellipses open WIDER than top ones when a cylinder is viewed from
     * slightly above, so the water's floor dips deeper at the centre —
     * mirroring the oval hollow of the art's base ring.
     */
    private const val BOTTOM_BOW_FRAC = 0.045f

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
     * The water habit the glass currently REPRESENTS — the one whose
     * clamped level is the lowest (mirrors [levelFor]'s selection) — or
     * null when no water habit with a valid goal is configured. The
     * combined-scene widget binds its glass tap zone to this habit, so a
     * tap opens the same habit the draining level is drawn from.
     */
    fun primaryHabitName(
        db: Map<String, Map<String, Int>>,
        waterHabits: Set<String>,
        waterDailyGoals: Map<String, Int>,
        today: LocalDate = LocalDate.now()
    ): String? {
        if (waterHabits.isEmpty()) return null
        val todayStr = today.toString()
        var bestName: String? = null
        var bestLevel: Float? = null
        for (name in waterHabits) {
            val goal = (waterDailyGoals[name] ?: com.example.tail.data.DEFAULT_WATER_DAILY_GOAL)
            if (goal <= 0) continue
            val intake = db[name]?.get(todayStr) ?: 0
            val level = (1f - intake.toFloat() / goal.toFloat()).coerceIn(0f, 1f)
            if (bestLevel == null || level < bestLevel) {
                bestLevel = level
                bestName = name
            }
        }
        return bestName
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
     * texture fills every interior-span pixel between the two ellipse
     * boundaries — a domed surface up top and a dipping floor over the
     * base hollow — with a luminous band hugging the surface curve. The
     * FULL span is watered (no alpha filter): the water slides UNDER the
     * wall glare strips, and the glass art drawn on top keeps its own
     * highlights while the tint shows through everywhere translucent.
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
        // The solid glass base holds no water — the flat floor line sits
        // just inside the base's top ellipse, then map the depletion
        // level onto the SHORTENED column so "full" = brim of the
        // water-bearing span, empty = its floor.
        val waterBottom = interior.bottom -
            ((interior.bottom - interior.top) * BASE_FRAC).toInt()
        val surfaceY = waterBottom -
            ((waterBottom - interior.top) * frac).toInt()

        // Perspective: both free boundaries are ellipse arcs — the
        // surface domes UP at the glass centre, the floor DIPS down into
        // the base's oval hollow; both meet the walls at their flat
        // level lines. The floor arc is deeper (bottom ellipses open
        // wider when viewed slightly from above).
        val bow = gh * SURFACE_BOW_FRAC
        val bowBot = gh * BOTTOM_BOW_FRAC
        /** Half-ellipse arc (0 at the walls, 1 at the centre). */
        fun arcAt(x: Int, xs: Int, xe: Int): Float {
            val halfW = ((xe - xs) / 2f).coerceAtLeast(1f)
            val dxN = (x - (xs + xe) / 2f) / halfW
            return if (dxN <= -1f || dxN >= 1f) 0f
            else Math.sqrt(1.0 - dxN.toDouble() * dxN).toFloat()
        }
        /** Domed-surface boundary at column [x] within this row's span. */
        fun surfaceBoundaryY(x: Int, xs: Int, xe: Int): Float =
            surfaceY - bow * arcAt(x, xs, xe)
        /** Dipping-floor boundary at column [x] within this row's span. */
        fun floorBoundaryY(x: Int, xs: Int, xe: Int): Float =
            waterBottom + bowBot * arcAt(x, xs, xe)

        // Pre-fetch the water texture once for direct sampling.
        val fw = fill.width
        val fh = fill.height
        val fillPx = IntArray(fw * fh)
        fill.getPixels(fillPx, 0, fw, 0, 0, fw, fh)

        // Tile scale in art pixels (glass-width-proportional).
        val tile = (gw * 0.9f).toInt().coerceAtLeast(8)

        val out = IntArray(gw * gh) // zero-initialised → transparent
        val bodyTop = (surfaceY - bow.toInt()).coerceAtLeast(interior.top)
        val bodyBot = (waterBottom + bowBot.toInt()).coerceAtMost(gh - 1)
        for (y in bodyTop..bodyBot) {
            val xs = interior.rowStart[y]
            val xe = interior.rowEnd[y]
            if (xs < 0) continue
            val ty = ((y % tile) * fh / tile)
            for (x in xs..xe) {
                // Curved clip: between the domed surface above and the
                // dipping floor below. No alpha filter — the FULL span
                // is watered so the body reaches into the wall glare
                // strips (the glass art on top re-adds its highlights).
                if (y < surfaceBoundaryY(x, xs, xe)) continue
                if (y > floorBoundaryY(x, xs, xe)) continue
                val tx = ((x % tile) * fw / tile)
                val c = fillPx[ty * fw + tx]
                // Composite: water colour at WATER_ALPHA over the tile's
                // own luminance modulation (keep tile RGB, set alpha).
                out[y * gw + x] = (c and 0x00FFFFFF) or
                    (WATER_ALPHA shl 24)
            }
        }

        // Luminous meniscus band hugging the CURVED surface line (full
        // span like the body, capped at the flat floor line so a
        // near-empty glass never paints the band onto the solid foot).
        val bandThick = gh * 0.010f
        val bandTop = (surfaceY - bow.toInt()).coerceAtLeast(0)
        val bandBot = (surfaceY + bandThick.toInt())
            .coerceAtMost(waterBottom).coerceAtMost(gh - 1)
        for (y in bandTop..bandBot) {
            val xs = interior.rowStart[y]
            val xe = interior.rowEnd[y]
            if (xs < 0) continue
            for (x in xs..xe) {
                val dy = y - surfaceBoundaryY(x, xs, xe)
                if (dy < 0 || dy > bandThick) continue
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
