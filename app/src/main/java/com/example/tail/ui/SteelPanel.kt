package com.example.tail.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.example.tail.R
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow

/**
 * Dark AI-generated steel panel background. Each surface uses its own
 * distinct texture:
 *
 *  [R.drawable.steel_topbar] — riveted plating for the top date/action bar
 *  [R.drawable.steel_tabs]   — long brushed band for the scrolling tab row
 *  [R.drawable.steel_advice] — diamond tread plate for the advice banner
 *
 * The texture is stretched to the panel's height and tiled/repeated
 * horizontally so panels can be any size (the advice banner grows with its
 * text). Content is drawn on top, over the steel.
 */
fun Modifier.steelPanel(
    drawableRes: Int,
    /** Current horizontal scroll offset (px) — shifts the texture so the
     *  steel scrolls together with the content (e.g. the tab-name row). */
    scrollOffset: () -> Int = { 0 },
    /** Stretch ONE image to fill the panel instead of tiling it — used by
     *  the advice banner so it reads as a single large steel sheet. */
    stretch: Boolean = false
): Modifier = composed {
    val context = LocalContext.current
    val panel: ImageBitmap? = remember(drawableRes) {
        BitmapFactory.decodeResource(context.resources, drawableRes)?.asImageBitmap()
    }
    Modifier.drawWithContent {
        // Solid fallback base (also shows through any transparent seams)
        drawRect(Color(0xFF22262B))
        if (panel != null) {
            if (stretch) {
                drawImage(
                    image = panel,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(panel.width, panel.height),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt())
                )
            } else {
                val tileW = (size.height * panel.width / panel.height).toInt().coerceAtLeast(1)
                // Shift by the scroll offset so the steel moves with the content;
                // start one tile left so scrolled-into-view area is covered.
                val start = -tileW - (scrollOffset() % tileW)
                var x = start
                while (x < size.width.toInt()) {
                    drawImage(
                        image = panel,
                        srcOffset = IntOffset.Zero,
                        srcSize = IntSize(panel.width, panel.height),
                        dstOffset = IntOffset(x, 0),
                        dstSize = IntSize(tileW, size.height.toInt())
                    )
                    x += tileW
                }
            }
        }
        drawContent()
    }
}

/**
 * Silver-metal panel — the SAME treatment as the metallic habit squares:
 * a normalized AI-generated brushed-silver texture (mean-128 grayscale)
 * overlaid in Overlay blend mode on a dark steel-grey base, so it reads as
 * repetitive, tileable metal texture rather than a photo. Used for the top
 * date bar and the screen-name tab row. Optional scroll offset keeps the
 * texture moving with scrolling tab content.
 */
fun Modifier.silverSteelPanel(
    scrollOffset: () -> Int = { 0 }
): Modifier = composed {
    val context = LocalContext.current
    val texture: ImageBitmap? = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.silver_metal)?.asImageBitmap()
    }
    Modifier.drawWithContent {
        drawRect(Color(0xFF3E434B))
        if (texture != null) {
            val tileW = (size.height * texture.width / texture.height).toInt().coerceAtLeast(1)
            val start = -tileW - (scrollOffset() % tileW)
            var x = start
            while (x < size.width.toInt()) {
                drawImage(
                    image = texture,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(texture.width, texture.height),
                    dstOffset = IntOffset(x, 0),
                    dstSize = IntSize(tileW, size.height.toInt()),
                    alpha = 0.6f,
                    blendMode = BlendMode.Overlay
                )
                x += tileW
            }
        }
        drawContent()
    }
}

/**
 * Shared anchor: the window-space y where the habit grid's FIRST square row
 * starts. Set by the grid panel; other panels snap their lattice to it so
 * every ghost square's row index is its true grid row (for brightness fade
 * and shimmer) and all panels form one seamless continuation.
 */
internal object GhostGridGeometry {
    var gridTopY: Float? = null

    /** Last value ever published for [gridTopY]. While the loading spinner
     *  replaces the grid (grid not composed → nothing publishes), chrome
     *  panels use this so the lattice stays exactly where the real grid put
     *  it instead of re-anchoring per panel (the "messed up" cold-start look). */
    var lastKnownGridTopY: Float? = null
}

/**
 * Ghost glass squares — the white-metallic (Glass) habit-square texture
 * drawn as a PERSPECTIVE lattice receding INTO the screen. Every logical
 * (row, col) keeps its uniform-lattice identity (the idle-shimmer wave and
 * the row/col enumeration still use it), but the drawn geometry is
 * projected toward a vanishing point at the centre of the window: a row's
 * depth factor is 1.0 (full size, at the surface) at the top/bottom screen
 * edges and shrinks progressively as the row approaches the vertical
 * centre, with each row's whole column strip scaled about the horizontal
 * centre line — so tiles get narrower, shorter and bunch together more and
 * more toward the middle, reading as a floor/wall grid diving deep into
 * the phone. Rest brightness is tied to the same depth factor (near =
 * bright, deep = dim), replacing the old edge-anchored fade ramps. Each
 * square still shimmers INDIVIDUALLY with the same idle-shimmer wave as
 * the real grid, sampled at its own virtual (row, col) position. Purely a
 * background layer — content is always drawn on top, unaffected.
 */
/**
 * Size-independent frosted-glass backdrop for chips/panels. Renders a copy of
 * the shared ghost lattice aligned to the same window-space phase as the
 * lattice behind the surface (so it shows "what's behind"), then blurs it.
 * The copy is STATIC (no shimmer) and faint (low base alpha), so the frost
 * reads identically at ANY size — no resolvable "reflective grid" on larger
 * instances — with a white fog layer on top at [fogAlpha].
 *
 * Use as children of a Box: the layers fill the box via matchParentSize.
 */
@Composable
internal fun BoxScope.FrostGlassLayer(
    blurRadius: Dp,
    fogAlpha: Float,
    fogShape: Shape = RoundedCornerShape(6.dp),
    /** Optional thin border drawn around the frosted area. */
    borderWidth: Dp = 0.dp,
    borderColor: Color = Color.White.copy(alpha = 0.45f)
) {
    Box(
        modifier = Modifier
            .matchParentSize()
            .ghostGlassSquares(
                pitLizard = false,
                shimmerSweep = { 0f },
                shimmerDirection = { ShimmerDirection.BOTTOM_RIGHT_TO_TOP_LEFT },
                baseAlpha = 0.06f
            )
            .blur(blurRadius)
    )
    Box(
        modifier = Modifier
            .matchParentSize()
            .background(Color.White.copy(alpha = fogAlpha), fogShape)
            .then(
                if (borderWidth > 0.dp) {
                    Modifier.border(borderWidth, borderColor, fogShape)
                } else {
                    Modifier
                }
            )
    )
}

private var pitDebugDumped = false

internal fun Modifier.ghostGlassSquares(
    /** Draw the pit-floor lizard on the horizon wall (skipped for frost
     *  layers, which are tiny static copies). */
    pitLizard: Boolean = true,
    /** The grid's shimmer sweep value (0..1), read inside the draw phase. */
    shimmerSweep: () -> Float,
    /** The grid's current shimmer direction, read inside the draw phase. */
    shimmerDirection: () -> ShimmerDirection,
    /** True when this instance IS the habit grid — it publishes the anchor
     *  the other panels snap to. */
    isGridAnchor: Boolean = false,
    /** Base visibility of the ghost squares (0..1) — faint at rest. */
    baseAlpha: Float = 0.12f
): Modifier = composed {
    val context = LocalContext.current
    // Window height (px) — anchors the bottom fade to the true bottom edge of
    // the screen regardless of which panel is drawing.
    val windowH = LocalWindowInfo.current.containerSize.height
    val tile: ImageBitmap? = remember {
        BitmapFactory.decodeResource(context.resources, R.drawable.habit_tile_glass)?.asImageBitmap()
    }
    // Pit-floor lizard — the SAME tier variant the tier-bar widget is
    // showing right now (dayTier from TierStateStore, which the widget
    // refreshes on every repaint), as a transparent-background strip.
    val lizard: ImageBitmap? = if (!pitLizard) null else remember {
        val tier = com.example.tail.widget.TierStateStore.load(context).dayTier
        val resId = context.resources.getIdentifier(
            "tier_bar_lizard_t${tier.coerceIn(0, 12)}", "drawable", context.packageName
        )
        if (resId != 0) {
            BitmapFactory.decodeResource(context.resources, resId)?.asImageBitmap()
        } else null
    }
    // Slightly SMALLER than the tier-bar widget's height-filling lizard
    // (~100dp): reads as standing far back against the horizon wall.
    // Drawn at the strip's baked 4:1 aspect.
    val lizardHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 64.dp.toPx() }
    val clipPath = remember { Path() }
    // Window-space y of this panel — the grid anchor publishes its own so all
    // panels share ONE lattice phase anchored to the real grid's first row.
    var topY by remember { mutableFloatStateOf(0f) }
    Modifier
        .onGloballyPositioned { coords ->
            topY = coords.positionInWindow().y
            if (isGridAnchor) {
                GhostGridGeometry.gridTopY = topY
                GhostGridGeometry.lastKnownGridTopY = topY
            }
        }
        .drawWithContent {
            if (tile != null) {
                // Mirror the real grid's geometry: 4.dp outer padding, 2.dp
                // per-cell padding, and 6.dp rounded corners — the LOGICAL
                // lattice the perspective projection starts from.
                val outerPad = 4.dp.toPx()
                val cellPad = 2.dp.toPx()
                val corner = 6.dp.toPx()
                val cell = (size.width - 2 * outerPad) / GRID_COLUMNS
                val side = (cell - 2 * cellPad).toInt().coerceAtLeast(1)
                val sweep = shimmerSweep()
                val dir = shimmerDirection()

                // Vanishing point — the centre of the WINDOW (global
                // coordinates), shared by every panel so the projected
                // lattice is one seamless scene across all chrome panels
                // and the loading screen alike.
                val vpx = size.width / 2f
                val vpy = windowH / 2f

                // Global y where the grid's row-0 square tops sit (published by
                // the grid panel). Squares then land at anchor + k*cell for any
                // integer k, so the LOGICAL lattice aligns with the real grid.
                // Anchor resolution order: the live grid's published anchor →
                // the last anchor the grid ever published (loading state, grid
                // absent) → a bottom-edge-aligned lattice so even the very
                // first cold-start frame has a consistent lattice instead of
                // each panel self-anchoring at its own top.
                val anchorBase = GhostGridGeometry.gridTopY
                    ?: GhostGridGeometry.lastKnownGridTopY
                    ?: run {
                        val wh = windowH.toFloat()
                        wh - floor(wh / ((size.width - 2 * outerPad) / GRID_COLUMNS)) *
                            ((size.width - 2 * outerPad) / GRID_COLUMNS)
                    }
                val anchorTop = anchorBase + outerPad + cellPad

                // ── Perspective projection (smoothstep horizon) ───────────
                // The deepest pit is a HORIZONTAL line across the middle of
                // the screen (the vanishing "horizon"). Depth of a point
                // depends on its VERTICAL distance from that midline (vpy):
                //  - depth(y) = 1.0 (surface, full size) at the top/bottom
                //    screen edges, shrinking progressively toward the
                //    midline — the sub-linear exponent makes compression
                //    ACCELERATE toward the centre, so tiles get smaller,
                //    narrower and bunch tighter (rows crowd toward the
                //    horizon) the deeper they go.
                //  - Each tile is a TRAPEZOID: its four corners are projected
                //    independently, so the horizontal edge facing the
                //    midline is narrower (in both width and height) than the
                //    edge at the surface — tiles visibly angle inward
                //    downward into the pit.
                //  - Columns beyond the real grid are enumerated (virtual
                //    col < 0 / ≥ GRID_COLUMNS): as a deep row's width
                //    compresses toward the centre, extra full-size tiles
                //    flow in from the sides so the pit's walls stay
                //    continuous edge to edge.
                // All y values here are GLOBAL window coordinates (the
                // projection is one seamless scene shared by every panel);
                // the panel-local offset is subtracted only at draw time.
                val minDepth = 0.18f
                val halfH = windowH / 2f
                // FLAT ZONE: the first 3 rows at the TOP of the screen stay
                // at depth 1.0 — completely flat, perfect squares at exactly
                // their original size and spacing (same as the habit
                // squares) — before the perspective starts dipping inward.
                val flatPx = 2f * cell
                // Smoothstep falloff over the remaining span: zero slope at
                // BOTH the flat zone's edge (nd = 1) and the horizon
                // (nd = 0). The zero slope at the edge keeps the transition
                // gapless (no derivative kink → no visible jump after the
                // flat rows); the zero slope at the horizon makes rows bunch
                // infinitesimally tight toward the vanishing line.
                fun depthAt(yGlobal: Float): Float {
                    val d = kotlin.math.abs(yGlobal - vpy)
                    val span = if (yGlobal < vpy) halfH - flatPx else halfH
                    if (span <= 0f) return minDepth
                    val nd = d / span
                    if (nd >= 1f) return 1f
                    val t = nd * nd * (3f - 2f * nd)
                    return minDepth + (1f - minDepth) * t
                }
                // Projection toward the horizon. BOTH axes scale by the SAME
                // depth factor, so a tile at depth 1 is an exact square the
                // same size as the habit squares, and partial-depth tiles
                // stay square-ish trapezoids (never elongated).
                fun projY(yGlobal: Float, s: Float): Float = vpy + (yGlobal - vpy) * s
                fun projX(x: Float, s: Float): Float = vpx + (x - vpx) * s

                // Rest brightness is tied to depth and hits ZERO at full
                // depth: the deepest tiles (at the horizon) are invisible at
                // rest and appear ONLY when the shimmer wave passes over
                // them. Surface tiles (top/bottom screen edges) are at full
                // rest brightness. (edgePeak keeps the old top-vs-bottom
                // asymmetry, evaluated per row.)
                fun restAlphaFor(gridRow: Int, depth: Float): Float {
                    val edgePeak = if (anchorTop + gridRow * cell < vpy) 0.20f else baseAlpha
                    val dNorm = ((depth - minDepth) / (1f - minDepth)).coerceIn(0f, 1f)
                    return edgePeak * dNorm.pow(2.6f)
                }

                // Shared projected ROW BOUNDARY lines, built by INTEGRATING
                // the local pitch: Y_{k+1} = Y_k + cell × depth(k↔k+1). A
                // pointwise projection (vpy + (y−vpy)·s) can locally
                // STRETCH (f′ > 1 where |y−vpy|·|s′| > 1−s), which opened
                // the horizontal gap after the flat rows. Integrating the
                // depth-scaled pitch guarantees every row pitch ≤ cell, so
                // rows only ever compress — seams are impossible — while
                // the flat zone (s = 1) still reproduces the exact original
                // lattice. Shared boundaries also mean row k's bottom and
                // row k+1's top are the same value by construction.
                //
                // BOTH ENDS ANCHORED: rows above the horizon integrate
                // downward from row 0 (top of screen, flat), rows below the
                // horizon integrate UPWARD from the last lattice row at the
                // bottom edge — so the surface rows stay glued to BOTH the
                // top and the bottom of the screen while everything
                // compresses toward the horizon from either side. (The two
                // integrations meet at the horizon where tiles are at
                // minimum depth and invisible at rest, so any sub-pixel
                // mismatch there is imperceptible.)
                val rowLineCache = HashMap<Int, Float>()
                val horizonRow = ((vpy - anchorTop) / cell).toInt()
                val bottomAnchorRow = floor((windowH - anchorTop) / cell).toInt()
                fun rowLineY(k: Int): Float {
                    rowLineCache[k]?.let { return it }
                    var y: Float
                    if (k <= horizonRow) {
                        // Downward from the top anchor (row 0 = lattice);
                        // negative rows extend UPWARD from it at their own
                        // depth-scaled pitch (rows above the grid anchor).
                        y = anchorTop.toFloat()
                        for (i in 0 until k) {
                            val mid = anchorTop + (i + 0.5f) * cell
                            y += cell * depthAt(mid)
                        }
                        for (i in k until 0) {
                            val mid = anchorTop + (i - 0.5f) * cell
                            y -= cell * depthAt(mid)
                        }
                    } else {
                        // Upward from the bottom-edge lattice anchor; rows
                        // beyond it extend DOWNWARD at their own pitch.
                        y = anchorTop + bottomAnchorRow * cell
                        for (i in bottomAnchorRow downTo k + 1) {
                            val mid = anchorTop + (i - 0.5f) * cell
                            y -= cell * depthAt(mid)
                        }
                        for (i in bottomAnchorRow until k) {
                            val mid = anchorTop + (i + 0.5f) * cell
                            y += cell * depthAt(mid)
                        }
                    }
                    rowLineCache[k] = y
                    return y
                }
                // Shared projected COLUMN boundary line for a row-scale s.
                fun colLineX(c: Int, s: Float): Float = projX(outerPad + c * cell, s)

                // TEMP DEBUG: one dump of the wall geometry per panel.
                if (pitLizard && !pitDebugDumped) {
                    pitDebugDumped = true
                    android.util.Log.d(
                        "PitWall",
                        "topY=$topY panelH=${size.height} windowH=$windowH " +
                            "cell=$cell anchorTop=$anchorTop vpy=$vpy " +
                            "horizonRow=$horizonRow bottomAnchorRow=$bottomAnchorRow " +
                            "rowY(-3)=${rowLineY(-3)} rowY(0)=${rowLineY(0)} " +
                            "rowY(3)=${rowLineY(3)} rowY(6)=${rowLineY(6)} " +
                            "rowY(9)=${rowLineY(9)} rowY(12)=${rowLineY(12)}"
                    )
                }

                fun drawSquareRow(gridRow: Int) {
                    // Row boundary depths (evaluated on the boundary lines,
                    // shared with the neighbouring rows).
                    val topLineG = anchorTop + gridRow * cell
                    val botLineG = topLineG + cell
                    val sTop = depthAt(topLineG)
                    val sBot = depthAt(botLineG)
                    val depth = depthAt(topLineG + cell / 2f)
                    // Projected boundary lines, then DEPTH-SCALED cell
                    // padding inset from each boundary — the gaps between
                    // tiles thin out as the tiles slope downward (both
                    // horizontally and vertically), tightening the illusion.
                    val topB = rowLineY(gridRow)
                    val botB = rowLineY(gridRow + 1)
                    val y0 = topB + cellPad * sTop
                    val y1 = botB - cellPad * sBot
                    // Cull rows whose PROJECTED extent misses this panel.
                    if (y1 - topY < 0f || y0 - topY > size.height || y1 - y0 < 1f) return
                    val restAlpha = restAlphaFor(gridRow, depth)
                    // WALL ROW SUBDIVISION: the two pitch integrations
                    // (down from the grid anchor, up from the bottom edge)
                    // both stall before reaching the horizon, so the row
                    // that straddles the stall gap (the horizon row) is a
                    // SINGLE tile hundreds of px tall × ~24px wide — it
                    // rendered as tall vertical columns, and because all of
                    // it shared one lattice row's u, vertical / diagonal /
                    // radial sweeps flashed the whole back wall at once.
                    // Subdivide it into SQUARE wall cells (one per
                    // min-depth pitch step) and let each sample the wave at
                    // its own FRACTIONAL lattice row — the same mapping the
                    // pit-floor lizard uses, so wall and lizard stay
                    // locked. Rows whose height already matches their
                    // width (everywhere else) are untouched (nSub = 1).
                    // ONLY the stall-gap row subdivides: its height (~800px)
                    // dwarfs its own projected tile width (~24px). Normal
                    // rows are square-ish (height ≈ cell × depth ≤ their
                    // width), so they stay untouched — nSub = 1.
                    val wallPitch = cell * minDepth
                    val tileW = cell * depth
                    val nSub = if (y1 - y0 > tileW * 1.6f) {
                        ceil((y1 - y0) / maxOf(wallPitch, tileW)).toInt()
                            .coerceAtLeast(1)
                    } else 1
                    val subH = (y1 - y0) / nSub
                    // Expand the column range past the real grid until the
                    // projected tiles clear the panel on BOTH sides — extra
                    // tiles "come in from the sides" to feed the compression.
                    var cFirst = 0
                    while (true) {
                        if (colLineX(cFirst - 1, depth) < -side) break
                        cFirst--
                    }
                    var cLast = GRID_COLUMNS - 1
                    while (true) {
                        if (colLineX(cLast + 1, depth) > size.width + side) break
                        cLast++
                    }
                    // Shimmer u comes STRAIGHT from the direction's formula
                    // with the raw (possibly negative / ≥ GRID_COLUMNS)
                    // column index — the formulas extrapolate linearly (or
                    // radially), so every virtual side column gets its own
                    // distinct position along the wave: no clustering, and
                    // every direction (horizontals, verticals, both diagonal
                    // pairs, centre-out / outer-centre) keeps its true
                    // geometry, including the forward-then-opposite pairing.
                    // u is NOT clamped: tiles whose extrapolated u lies
                    // outside the traversed range are automatically dark at
                    // rest (their distance to the wave front exceeds the
                    // band width), so a finished sweep leaves NOTHING
                    // glowing — clamping to the band edge is exactly what
                    // used to pin the final tiles at full brightness.
                    for (j in 0 until nSub) {
                    val sy0 = y0 + j * subH
                    val sy1 = sy0 + subH
                    // Fractional lattice row for this (sub-)cell. Identity
                    // (gridRow) everywhere except the subdivided wall row:
                    // there the sub-cells INTERPOLATE from the row's top
                    // boundary to its bottom boundary — i.e. the wave
                    // coordinate slides from gridRow's u to (gridRow+1)'s
                    // u across the stall gap. The wall thus lights as the
                    // CONTINUATION of the close grid's sweep (one seamless
                    // shimmer top→wall→bottom), never as a separate flash
                    // with its own timing.
                    val rowF = if (nSub == 1) gridRow.toFloat()
                        else gridRow + (sy0 - y0) / (y1 - y0)
                    for (c in cFirst..cLast) {
                        // Column boundaries projected with each edge's own
                        // depth (sTop for the top edge, sBot for the bottom
                        // edge), with DEPTH-SCALED padding insets — the
                        // horizontal gaps thin as depth increases too.
                        val px0t = colLineX(c, sTop) + cellPad * sTop
                        val px1t = colLineX(c + 1, sTop) - cellPad * sTop
                        val px0b = colLineX(c, sBot) + cellPad * sBot
                        val px1b = colLineX(c + 1, sBot) - cellPad * sBot
                        if (maxOf(px1t, px1b) < 0f || minOf(px0t, px0b) > size.width) continue
                        // One seamless shimmer across close grid AND wall:
                        // the wave coordinate's vertical axis is the cell's
                        // NORMALIZED SCREEN Y, not the logical row. The
                        // wall's stall gap (hundreds of px with no lattice
                        // rows) thus gets a proportional slice of the
                        // sweep — split across the subdivided wall squares
                        // — instead of one row-sized u-slice that flashed
                        // the whole wall at once.
                        val u = dir.u(
                            gridRow, c,
                            yN = (sy0 + sy1) / 2f / windowH
                        )
                        // Global brightness boost applied to every ghost square,
                        // on top of the depth fade and shimmer alike.
                        // HISTORY: every earlier "+X%" pass was a silent no-op
                        // because `expr * Nf.coerceIn(0f, 1f)` binds .coerceIn
                        // to the LITERAL (→ always 1.0f) — even across a line
                        // break. The multiplier is now applied in a separate
                        // step so the precedence cannot bite again; clamp on
                        // the final value. Boost = ×4 (two requested doublings).
                        val unboosted = restAlpha +
                            idleShimmerAlpha(sweep, u) * 1.8f * (0.45f + 0.55f * depth)
                        val alpha = (unboosted * 2.0f).coerceIn(0f, 1f)
                        if (alpha > 0.004f) {
                            // Trapezoid corners from the shared projected
                            // boundaries + scaled padding insets.
                            val p00x = px0t; val p01x = px1t
                            val p10x = px0b; val p11x = px1b
                            val p0y = sy0 - topY
                            val p1y = sy1 - topY
                            val left = minOf(p00x, p10x)
                            val right = maxOf(p01x, p11x)
                            if (right - left < 1f) continue
                            // Rounded corners on the trapezoid: at the
                            // surface (depth 1) the radius matches the real
                            // habit squares' 6.dp, so flat ghost squares are
                            // pixel-identical to them; deeper tiles round
                            // proportionally smaller. Implemented as four
                            // quadratic corner arcs cut into the trapezoid.
                            val rPx = corner * depth
                            fun ax(ax0: Float, ay0: Float, bx: Float, by: Float): Float =
                                ax0 + (bx - ax0) * ((rPx / kotlin.math.hypot(bx - ax0, by - ay0)).coerceIn(0f, 0.5f))
                            fun ay(ax0: Float, ay0: Float, bx: Float, by: Float): Float =
                                ay0 + (by - ay0) * ((rPx / kotlin.math.hypot(bx - ax0, by - ay0)).coerceIn(0f, 0.5f))
                            clipPath.reset()
                            // top edge p00 -> p01
                            clipPath.moveTo(ax(p00x, p0y, p01x, p0y), ay(p00x, p0y, p01x, p0y))
                            clipPath.lineTo(ax(p01x, p0y, p00x, p0y), ay(p01x, p0y, p00x, p0y))
                            // corner at p01
                            clipPath.quadraticTo(p01x, p0y, ax(p01x, p0y, p11x, p1y), ay(p01x, p0y, p11x, p1y))
                            // right edge p01 -> p11
                            clipPath.lineTo(ax(p11x, p1y, p01x, p0y), ay(p11x, p1y, p01x, p0y))
                            // corner at p11
                            clipPath.quadraticTo(p11x, p1y, ax(p11x, p1y, p10x, p1y), ay(p11x, p1y, p10x, p1y))
                            // bottom edge p11 -> p10
                            clipPath.lineTo(ax(p10x, p1y, p11x, p1y), ay(p10x, p1y, p11x, p1y))
                            // corner at p10
                            clipPath.quadraticTo(p10x, p1y, ax(p10x, p1y, p00x, p0y), ay(p10x, p1y, p00x, p0y))
                            // left edge p10 -> p00
                            clipPath.lineTo(ax(p00x, p0y, p10x, p1y), ay(p00x, p0y, p10x, p1y))
                            // corner at p00
                            clipPath.quadraticTo(p00x, p0y, ax(p00x, p0y, p01x, p0y), ay(p00x, p0y, p01x, p0y))
                            clipPath.close()
                            val dstX = left.toInt()
                            val dstY = minOf(p0y, p1y).toInt()
                            val dstW = (right - left).toInt().coerceAtLeast(1)
                            val dstH = (maxOf(p0y, p1y) - minOf(p0y, p1y)).toInt().coerceAtLeast(1)
                            clipPath(clipPath) {
                                drawImage(
                                    image = tile,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(tile.width, tile.height),
                                    dstOffset = IntOffset(dstX, dstY),
                                    dstSize = IntSize(dstW, dstH),
                                    alpha = alpha
                                )
                            }
                        }
                    }
                    }
                }

                // Enumerate every lattice row visible in this panel; gridRow
                // IS the true row index (negative above the grid, GRID_ROWS+
                // below it), so brightness and shimmer are globally consistent.
                // The drawing is CLIPPED to this panel's own bounds: adjacent
                // chrome panels (top bar → location row → tab row → grid) all
                // run this same draw phase on the shared lattice, and without
                // the clip each seam square was painted by BOTH neighbours,
                // compounding alpha. Row geometry depends only on gridRow,
                // so clipped halves from the two neighbours match exactly.
                val kFirst = ceil((topY - anchorTop - side) / cell).toInt()
                val kLast = floor((topY + size.height - anchorTop) / cell).toInt()
                clipRect(0f, 0f, size.width, size.height) {
                    for (gridRow in kFirst..kLast) drawSquareRow(gridRow)
                }

                // ── Pit-floor lizard ──────────────────────────────────────
                // The tier-bar widget's lizard (today's tier variant, the
                // same transparent 4:1 strip the widget draws) sits ON the
                // flat wall at the bottom of the pit — the horizon band at
                // the window midline. It is INVISIBLE at rest and lights up
                // EXACTLY where/when the wall's own shimmer line passes:
                //
                //  · The lizard is drawn smaller than on the widget (64dp
                //    vs the ~100dp widget bar) so it reads as sitting far
                //    back against the horizon wall.
                //  · The strip is sliced into thin vertical slices. Each
                //    slice's on-screen x is mapped back to a LOGICAL
                //    lattice column through the SAME perspective projection
                //    the wall tiles use (inverse-projected at the horizon's
                //    depth), so the slice samples the wave precisely where
                //    the compressed wall tile beside it samples it — the
                //    reveal is locked, time- and space-wise, to the wall's
                //    thin shimmer line. A vertical wave lights the whole
                //    horizon row (lizard included) at once, exactly like
                //    the row of wall tiles; horizontal / diagonal / radial
                //    waves sweep across it slice group by slice group, in
                //    perfect step with the neighbouring wall tiles.
                //  · Brightness peaks below 1 so it stays a faint thing at
                //    the back of the pit, matching the deep tiles.
                //
                // Drawn only by the ONE panel whose bounds contain the
                // horizon, so adjacent chrome panels never double-paint it.
                val vpyLocal = vpy - topY
                if (pitLizard && lizard != null && sweep > 0f &&
                    vpyLocal >= 0f && vpyLocal < size.height
                ) {
                    val lzH = lizardHeightPx
                    val lzW = lzH * 4f
                    val lzLeft = vpx - lzW / 2f
                    val lzTop = vpyLocal - lzH / 2f
                    // The lizard is sampled as a 2D GRID of square
                    // sliver-cells — one compressed wall tile (cell × the
                    // horizon's depth factor) on EACH side — so the reveal
                    // granularity matches the deep wall's squares in BOTH
                    // axes. (Earlier the strip was only sliced by column:
                    // a vertical wavefront sampled a single lattice row and
                    // therefore flashed the whole 64dp-tall lizard at once,
                    // reading as "the entire back wall lights up". Now each
                    // sliver-cell samples the wave at its own fractional
                    // (row, col) and vertical sweeps cross the lizard row
                    // by row, exactly like the wall.)
                    val sWall = minDepth
                    val sliver = (cell * sWall).coerceAtLeast(1f)
                    val nx = ceil(lzW / sliver).toInt().coerceIn(24, 160)
                    val ny = ceil(lzH / sliver).toInt().coerceAtLeast(1)
                    val cw = lzW / nx
                    val chh = lzH / ny
                    // Inverse projections at the horizon's depth (minDepth):
                    // screen x/y → unprojected lattice column/row. This is
                    // what keeps the lizard's reveal locked to the wall's
                    // own wave. Fractional and UNCLAMPED: the formulas
                    // extrapolate cleanly past the grid's span — crucial
                    // for the lizard's head and tail, which inverse-project
                    // to columns well outside the real grid.
                    val pitch = cell * sWall
                    for (j in 0 until ny) {
                        val y0 = lzTop + j * chh
                        val yCenterG = y0 + chh / 2f + topY
                        val rowF = horizonRow + (yCenterG - vpy) / pitch
                        for (s in 0 until nx) {
                            val x0 = lzLeft + s * cw
                            if (x0 + cw < 0f || x0 > size.width) continue
                            val xCenter = x0 + cw / 2f
                            val latticeX = vpx + (xCenter - vpx) / sWall
                            // Column axis: inverse-projected, fractional,
                            // interpolated between the two neighbouring
                            // integer columns (the direction formulas are
                            // linear in col). Vertical axis: NORMALIZED
                            // SCREEN Y (dir.u's yN) — identical mapping to
                            // the wall squares beside the lizard, so both
                            // light in perfect lock-step for every sweep.
                            val colF = (latticeX - outerPad) / cell - 0.5f
                            val c0 = colF.toInt()
                            val f = colF - c0
                            val yN = yCenterG / windowH
                            val u = dir.u(0, c0, yN) * (1f - f) +
                                dir.u(0, c0 + 1, yN) * f
                            val proximity = idleShimmerAlpha(sweep, u) /
                                IDLE_SHIMMER_MAX_ALPHA
                            if (proximity <= 0.01f) continue
                            val sx = (s.toFloat() / nx * lizard.width).toInt()
                            val sy = (j.toFloat() / ny * lizard.height).toInt()
                            val sw = (lizard.width.toFloat() / nx).toInt()
                                .coerceAtLeast(1)
                            val sh = (lizard.height.toFloat() / ny).toInt()
                                .coerceAtLeast(1)
                            clipRect(x0, y0, x0 + cw, y0 + chh) {
                                drawImage(
                                    image = lizard,
                                    srcOffset = IntOffset(sx, sy),
                                    srcSize = IntSize(sw, sh),
                                    dstOffset = IntOffset(x0.toInt(), y0.toInt()),
                                    dstSize = IntSize(
                                        (cw + 0.99f).toInt(),
                                        (chh + 0.99f).toInt()
                                    ),
                                    alpha = (proximity * 0.45f).coerceIn(0f, 1f)
                                )
                            }
                        }
                    }
                }
            }
            drawContent()
        }
}

/**
 * Metallic pill highlight for the ACTIVE screen tab — a subtle polished-steel
 * gradient with a thin bright bevel border, matching the steel theme
 * (replaces the old flat grey oval).
 */
fun Modifier.activeSteelTab(): Modifier = composed {
    val shape = RoundedCornerShape(50)
    Modifier
        .background(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF5C6773), Color(0xFF2C333B))
            ),
            shape = shape
        )
        .border(1.dp, Color(0xFF9AA6B2), shape)
}
