package com.example.tail.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
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
 * drawn FULL-SIZE (one square per GRID_COLUMNS cell, edge-to-edge), so the
 * chrome panels read as if the habit grid simply continued past its edges.
 * Each square shimmers INDIVIDUALLY with the same idle-shimmer wave as the
 * real grid, sampled at its own virtual (row, col) position. Drawn at a
 * barely-visible base alpha that brightens as the wave passes. Purely a
 * background layer — content is always drawn on top, unaffected.
 */
internal fun Modifier.ghostGlassSquares(
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
                // per-cell padding, and 6.dp rounded corners — so the ghost
                // squares read as literal continuations of the habit squares.
                val outerPad = 4.dp.toPx()
                val cellPad = 2.dp.toPx()
                val corner = 6.dp.toPx()
                val cell = (size.width - 2 * outerPad) / GRID_COLUMNS
                val side = (cell - 2 * cellPad).toInt().coerceAtLeast(1)
                val sweep = shimmerSweep()
                val dir = shimmerDirection()

                // Global y where the grid's row-0 square tops sit (published by
                // the grid panel). Squares then land at anchor + k*cell for any
                // integer k, so the lattice aligns EXACTLY with the real grid
                // rows everywhere on screen.
                // Anchor resolution order: the live grid's published anchor →
                // the last anchor the grid ever published (loading state, grid
                // absent) → a bottom-edge-aligned lattice so even the very
                // first cold-start frame has a consistent, correctly-faded
                // grid instead of each panel self-anchoring at its own top.
                val anchorBase = GhostGridGeometry.gridTopY
                    ?: GhostGridGeometry.lastKnownGridTopY
                    ?: run {
                        val wh = windowH.toFloat()
                        wh - floor(wh / ((size.width - 2 * outerPad) / GRID_COLUMNS)) *
                            ((size.width - 2 * outerPad) / GRID_COLUMNS)
                    }
                val anchorTop = anchorBase + outerPad + cellPad

                // Vertical brightness profile — anchored to the SCREEN edges
                // (global window coordinates), NOT to each panel's own extent.
                // (Using each panel's last visible row as the bottom anchor
                // made every small header panel brighten its own bottom edge,
                // which inverted the top gradient.)
                //
                // Top: the lattice row at the very top edge of the screen is
                // the brightest (1.0); the falloff is quadratic over ~6 rows,
                // so rows 1-3 read clearly, rows 4-6 are very slightly visible
                // and everything below fades to shimmer-only. Bottom: fades
                // back in over the last ~3 rows, brightest at the very bottom
                // edge (toward the advice banner) — as before.
                // Vertical brightness profile — LINEAR ramps anchored to the
                // screen edges (global window coordinates). The top ramp spans
                // 6 rows peaking at 0.20 alpha; every row steps down by the
                // SAME constant (~0.033), so the fade reads as a smooth
                // gradient — a squared falloff was tried and perceptually
                // collapsed into "flat rows then a hard cut" because only
                // absolute (not relative) steps are visible at these low
                // alphas. The bottom ramp is unchanged: 5 rows peaking at
                // baseAlpha, brightest at the very bottom edge.
                val topEdgeRow = floor((0f - anchorTop) / cell)
                val bottomEdgeRow = floor((windowH - anchorTop) / cell)
                fun topFadeAlpha(gridRow: Int): Float =
                    0.20f * (1f - (gridRow - topEdgeRow) / 6f).coerceIn(0f, 1f)
                fun bottomFadeAlpha(gridRow: Int): Float =
                    baseAlpha * (0.05f + 0.95f *
                        ((gridRow - (bottomEdgeRow - 4)) / 5f).coerceIn(0f, 1f))
                fun rowFadeFraction(gridRow: Int): Float =
                    maxOf(
                        (1f - (gridRow - topEdgeRow) / 6f).coerceIn(0f, 1f),
                        ((gridRow - (bottomEdgeRow - 4)) / 5f).coerceIn(0f, 1f)
                    )

                fun drawSquareRow(gridRow: Int) {
                    val fade = rowFadeFraction(gridRow)
                    val restAlpha = maxOf(topFadeAlpha(gridRow), bottomFadeAlpha(gridRow))
                    for (c in 0 until GRID_COLUMNS) {
                        // Clamp u: extrapolated virtual rows would otherwise produce
                        // out-of-range u values that linger inside the shimmer band
                        // after the sweep ends (squares stuck slightly shimmered).
                        val u = dir.u(gridRow, c).coerceIn(0f, 1f)
                        // Global brightness boost applied to every ghost square,
                        // on top of the fade profile and shimmer alike.
                        // HISTORY: every earlier "+X%" pass was a silent no-op
                        // because `expr * Nf.coerceIn(0f, 1f)` binds .coerceIn
                        // to the LITERAL (→ always 1.0f) — even across a line
                        // break. The multiplier is now applied in a separate
                        // step so the precedence cannot bite again; clamp on
                        // the final value. Boost = ×4 (two requested doublings).
                        val unboosted = restAlpha +
                            idleShimmerAlpha(sweep, u) * 3.0f * (0.45f + 0.55f * fade)
                        val alpha = (unboosted * 4.0f).coerceIn(0f, 1f)
                        if (alpha > 0.004f) {
                            val left = outerPad + c * cell + cellPad
                            val top = anchorTop + gridRow * cell - topY
                            clipPath.reset()
                            clipPath.addRoundRect(
                                RoundRect(
                                    left = left,
                                    top = top,
                                    right = left + side,
                                    bottom = top + side,
                                    cornerRadius = CornerRadius(corner, corner)
                                )
                            )
                            clipPath(clipPath) {
                                drawImage(
                                    image = tile,
                                    srcOffset = IntOffset.Zero,
                                    srcSize = IntSize(tile.width, tile.height),
                                    dstOffset = IntOffset(left.toInt(), top.toInt()),
                                    dstSize = IntSize(side, side),
                                    alpha = alpha
                                )
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
                // compounding alpha — which made rows brighter toward the
                // grid and then hard-cut. Row alpha depends only on gridRow,
                // so clipped halves from the two neighbours match exactly.
                val kFirst = ceil((topY - anchorTop - side) / cell).toInt()
                val kLast = floor((topY + size.height - anchorTop) / cell).toInt()
                clipRect(0f, 0f, size.width, size.height) {
                    for (gridRow in kFirst..kLast) drawSquareRow(gridRow)
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
