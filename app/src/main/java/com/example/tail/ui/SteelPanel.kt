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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
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
            if (isGridAnchor) GhostGridGeometry.gridTopY = topY
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
                val anchorTop = (GhostGridGeometry.gridTopY ?: topY) + outerPad + cellPad

                // Vertical brightness profile: the top rows (above the grid)
                // stay bright — the fade-from-dark-into-the-tab-row look —
                // but EVERY grid row (0+) is invisible at rest (only the
                // shimmer still shows, faintly). Near the panel's bottom edge
                // the squares fade back in over just ~3 rows, brightest at the
                // very bottom (toward the advice banner).
                fun rowBrightness(gridRow: Int, maxRow: Int): Float {
                    val topB = if (gridRow < 0) ((2 - gridRow) / 3f).coerceIn(0f, 1f) else 0f
                    val bottomB = ((gridRow - (maxRow - 3)) / 4f).coerceIn(0f, 1f)
                    return maxOf(topB, bottomB)
                }

                fun drawSquareRow(gridRow: Int, maxRow: Int) {
                    val b = rowBrightness(gridRow, maxRow)
                    for (c in 0 until GRID_COLUMNS) {
                        // Clamp u: extrapolated virtual rows would otherwise produce
                        // out-of-range u values that linger inside the shimmer band
                        // after the sweep ends (squares stuck slightly shimmered).
                        val u = dir.u(gridRow, c).coerceIn(0f, 1f)
                        val alpha = (
                            baseAlpha * (0.05f + 0.95f * b) +
                                idleShimmerAlpha(sweep, u) * 3.0f * (0.45f + 0.55f * b)
                            ).coerceIn(0f, 1f)
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
                val kFirst = ceil((topY - anchorTop - side) / cell).toInt()
                val kLast = floor((topY + size.height - anchorTop) / cell).toInt()
                for (gridRow in kFirst..kLast) drawSquareRow(gridRow, kLast)
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
