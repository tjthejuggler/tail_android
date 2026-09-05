package com.example.tail.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlin.math.ceil

/**
 * Random "perching" placement for the shimmer lizard — PHASE 1.
 *
 * The lizard used to sit dead-centre on the deep back wall's horizon. Now it
 * behaves like an actual lizard under gravity: every shimmer leg it is
 * randomly re-placed onto a SURFACE it can physically cling to:
 *
 *  · STAND  — standing on TOP of a horizontal run of occupied habit squares
 *  · HANG   — upside-down, clinging to the UNDERSIDE of occupied squares
 *  · CLIMB  — vertical, belly against the SIDE of a stack of occupied
 *             squares, or against the phone's left/right screen edge
 *
 * Each pose is produced from the SAME 4:1 tier strip by rotation/mirroring
 * (the lizard is happy to be flipped completely upside-down). The lizard's
 * footprint is expressed in GRID CELLS derived from the strip's visible
 * content aspect, so small-tier lizards (thin content inside a padded strip)
 * fit into many more spots than big-tier ones — and future pose variants
 * with different shapes will slot into the same solver untouched, because
 * footprints are computed per-variant from the bitmap, never hard-coded.
 */

/** Which side of the lizard's footprint the clung-to surface is on. */
internal enum class PerchSurface { BELOW, ABOVE, LEFT, RIGHT }

/** One baked orientation of the lizard strip + its cell-footprint. */
internal data class LizardVariant(
    /** The rotated/mirrored strip as an ImageBitmap. */
    val bitmap: ImageBitmap,
    /** Visible-content bounding box inside [bitmap] (source px). */
    val srcX: Int,
    val srcY: Int,
    val srcW: Int,
    val srcH: Int,
    /** Footprint in empty grid cells. Horizontal poses: rows=1. */
    val rows: Int,
    val cols: Int,
    /** Which side of the footprint the surface must be on. */
    val surface: PerchSurface,
    /** True when this is a wall-climbing (vertical) pose. */
    val vertical: Boolean
)

/**
 * A concrete chosen spot: the variant used plus the footprint's top-left
 * lattice cell. The footprint occupies lattice rows [row, row+rows) and
 * columns [col, col+cols); every one of those cells must be EMPTY.
 */
internal data class LizardPerch(
    val variantIndex: Int,
    val row: Int,
    val col: Int,
    val rows: Int,
    val cols: Int
)

/** Shared scene state published by the real habit grid. */
internal object GhostSceneState {
    /** Indices (row*GRID_COLUMNS+col) of cells that hold a real habit. */
    var occupiedCells: Set<Int> = emptySet()
        private set

    /** Bumped only when the occupancy actually changes. */
    var version: Long = 0L
        private set

    fun publish(occupied: Set<Int>) {
        if (occupied != occupiedCells) {
            occupiedCells = occupied
            version++
        }
    }
}

/** Scan the visible (non-transparent) bounding box of a bitmap. */
private fun contentBounds(bmp: Bitmap): IntArray? {
    val w = bmp.width
    val h = bmp.height
    val px = IntArray(w * h)
    bmp.getPixels(px, 0, w, 0, 0, w, h)
    var l = w; var t = h; var r = -1; var b = -1
    var y = 0
    while (y < h) {
        val off = y * w
        var x = 0
        while (x < w) {
            if (px[off + x] ushr 24 != 0) {
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
            }
            x++
        }
        y++
    }
    return if (r < l) null else intArrayOf(l, t, r - l + 1, b - t + 1)
}

/**
 * Bake the 8 pose variants from the tier strip:
 * rotations {0, 90, 180, 270} × {mirrored, not}.
 *
 * Semantics (the strips are right-facing lizards, belly down):
 *  · 0°            → STAND, surface BELOW (head left/right via mirror)
 *  · 180°          → HANG, surface ABOVE (fully upside-down)
 *  · 90° CW        → belly faces LEFT  (climb a wall on the footprint's left)
 *  · 270° CW       → belly faces RIGHT (climb a wall on the footprint's right)
 */
internal fun buildLizardVariants(base: ImageBitmap): List<LizardVariant> {
    val src = base.asAndroidBitmap()
    val out = ArrayList<LizardVariant>(8)
    for (mirrored in booleanArrayOf(false, true)) {
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val m = Matrix()
            if (mirrored) {
                m.setScale(-1f, 1f)
                m.postTranslate(src.width.toFloat(), 0f)
            }
            m.postRotate(rot.toFloat())
            val bmp = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val bb = contentBounds(bmp) ?: continue
            val (cw, ch) = bb[2] to bb[3]
            // Footprint in cells from the content's own aspect ratio:
            // a wide pose is 1 cell tall × N wide, a vertical pose is
            // 1 cell wide × N tall. ceil() guarantees the content FITS
            // inside the footprint without overhanging the surface.
            val rows: Int
            val cols: Int
            if (cw >= ch) {
                rows = 1
                cols = ceil(cw.toFloat() / ch).toInt()
            } else {
                cols = 1
                rows = ceil(ch.toFloat() / cw).toInt()
            }
            val surface = when (rot) {
                0 -> PerchSurface.BELOW
                180 -> PerchSurface.ABOVE
                90 -> PerchSurface.LEFT
                else -> PerchSurface.RIGHT
            }
            out.add(
                LizardVariant(
                    bitmap = bmp.asImageBitmap(),
                    srcX = bb[0],
                    srcY = bb[1],
                    srcW = cw,
                    srcH = ch,
                    rows = rows.coerceAtMost(GRID_ROWS),
                    cols = cols.coerceAtMost(GRID_COLUMNS - 1),
                    surface = surface,
                    vertical = rot == 90 || rot == 270
                )
            )
        }
    }
    return out
}

/**
 * Enumerate every physically-valid spot for every variant and pick one at
 * random. Validity rules (lizard cells must ALL be empty; the surface cells
 * must ALL be occupied habits — except the screen-edge walls, which are
 * always "occupied"):
 *
 *  · BELOW  → all cells of row+1 under the footprint occupied
 *  · ABOVE  → all cells of row-1 over the footprint occupied
 *  · LEFT   → all cells of col-1 beside the footprint occupied OR col == 0
 *             (the phone's left screen edge IS the wall)
 *  · RIGHT  → all cells of col+1 occupied OR col+cols == GRID_COLUMNS
 *             (the phone's right screen edge)
 *
 * Returns null when nothing fits (e.g. a nearly-empty screen) — the caller
 * then falls back to the legacy centred horizon placement.
 */
internal fun randomLizardPerch(
    variants: List<LizardVariant>,
    occupied: Set<Int>
): LizardPerch? {
    if (variants.isEmpty()) return null
    fun occ(r: Int, c: Int) = occupied.contains(r * GRID_COLUMNS + c)
    fun footprintFree(r: Int, c: Int, rows: Int, cols: Int): Boolean {
        for (i in r until r + rows) for (j in c until c + cols) if (occ(i, j)) return false
        return true
    }

    val candidates = ArrayList<LizardPerch>()
    variants.forEachIndexed { vi, v ->
        if (v.rows >= GRID_ROWS || v.cols >= GRID_COLUMNS) return@forEachIndexed
        when (v.surface) {
            PerchSurface.BELOW, PerchSurface.ABOVE -> {
                for (c in 0..GRID_COLUMNS - v.cols) {
                    val surfaceRow = if (v.surface == PerchSurface.BELOW) 1 else -1
                    val r0 = if (v.surface == PerchSurface.BELOW) 0 else 1
                    for (r in r0 until GRID_ROWS) {
                        if (r + surfaceRow < 0 || r + surfaceRow >= GRID_ROWS) continue
                        var solid = true
                        for (j in c until c + v.cols) if (!occ(r + surfaceRow, j)) { solid = false; break }
                        if (solid && footprintFree(r, c, v.rows, v.cols)) {
                            candidates.add(LizardPerch(vi, r, c, v.rows, v.cols))
                        }
                    }
                }
            }
            PerchSurface.LEFT, PerchSurface.RIGHT -> {
                for (c in 0 until GRID_COLUMNS) for (r in 0..GRID_ROWS - v.rows) {
                    val wallCol = if (v.surface == PerchSurface.LEFT) c - 1 else c + v.cols
                    val wallIsEdge = wallCol < 0 || wallCol >= GRID_COLUMNS
                    var solid = wallIsEdge
                    if (!solid) {
                        solid = true
                        for (i in r until r + v.rows) if (!occ(i, wallCol)) { solid = false; break }
                    }
                    if (solid && footprintFree(r, c, v.rows, v.cols)) {
                        candidates.add(LizardPerch(vi, r, c, v.rows, v.cols))
                    }
                }
            }
        }
    }
    return candidates.randomOrNull()
}
