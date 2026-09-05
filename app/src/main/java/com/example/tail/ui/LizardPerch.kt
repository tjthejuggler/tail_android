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

/**
 * One placed lizard pose.
 *
 * PHASE 1 (legacy strip): the 8 rotated/mirrored variants of the tier strip,
 * footprint derived from the bitmap aspect, one surface per variant.
 *
 * PHASE 2 (generated poses): full N×M cell canvases carrying their own
 * metadata — `dummyCells` are the canvas cells that must land on OCCUPIED
 * habit squares (the baked "dummy squares" were chroma-keyed out of the
 * bitmap), `lizardCells` the canvas cells that must be EMPTY (the lizard's
 * footprint). The pose is drawn at true flat-grid geometry so one canvas
 * cell == one lattice cell, pixel-exact with the real habit squares.
 */
internal data class LizardVariant(
    /** The rotated/mirrored strip or full pose canvas as an ImageBitmap. */
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
    val vertical: Boolean,
    /** Canvas cells (row*cols+col) that must be OCCUPIED habits. */
    val dummyCells: Set<Int> = emptySet(),
    /** When true the bitmap is a FULL cell canvas (one canvas px per cell
     *  division known via [canvasCols]/[canvasRows]) rather than a strip. */
    val isPoseCanvas: Boolean = false
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

/**
 * PROCESS-WIDE perch state, shared by EVERY panel that draws the ghost
 * lizard. Each chrome panel (top bar > location row > tab row > grid) runs
 * the same draw phase on one shared lattice; with per-panel remember{} state
 * each panel kept its OWN roll counter and sweep history, so a single leg
 * could show one spot on the grid panel and another on the tab row, and the
 * forward+return pairing broke. With ONE shared object: whichever panel
 * draws first in a frame performs the leg transition, the others see the
 * updated lastSweepValue and no-op, and every panel renders the SAME perch
 * for the whole forward+return leg pair.
 */
internal object SharedPerchState {
    var perch: LizardPerch? = null
    var legSeen: Int = -1
    var versionSeen: Long = -1L
    var lastSweepValue: Float = 1f
}

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

    // Candidates are collected PER VARIANT first: a variant with zero valid
    // spots is excluded from the draw entirely, so the random pick can never
    // land on a lizard that "doesn't fit anywhere" (which used to leave the
    // shimmer blank).
    val perVariant = ArrayList<List<LizardPerch>>(variants.size)
    var total = 0
    variants.forEachIndexed { vi, v ->
        if (v.rows >= GRID_ROWS || v.cols >= GRID_COLUMNS) {
            perVariant.add(emptyList()); return@forEachIndexed
        }
        val spots = ArrayList<LizardPerch>()
        if (v.isPoseCanvas) {
            // PHASE 2 pose canvas: the canvas must be placed so its chroma
            // "dummy square" cells coincide with REAL habit squares and its
            // remaining (lizard) cells are empty. The physical surface is
            // INSIDE the canvas (the lizard stands on the dummy mass), so
            // no external surface row is checked.
            val dummies = v.dummyCells
            for (c in 0..GRID_COLUMNS - v.cols) {
                for (r in 0..GRID_ROWS - v.rows) {
                    var ok = true
                    for (i in r until r + v.rows) {
                        for (j in c until c + v.cols) {
                            val isDummy = dummies.contains(
                                (i - r) * v.cols + (j - c)
                            )
                            if (isDummy != occ(i, j)) { ok = false; break }
                        }
                        if (!ok) break
                    }
                    if (ok) spots.add(LizardPerch(vi, r, c, v.rows, v.cols))
                }
            }
            perVariant.add(spots); total += spots.size; return@forEachIndexed
        }
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
                            spots.add(LizardPerch(vi, r, c, v.rows, v.cols))
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
                        spots.add(LizardPerch(vi, r, c, v.rows, v.cols))
                    }
                }
            }
        }
        perVariant.add(spots); total += spots.size
    }
    if (total == 0) return null
    // Variant-proportional pick: first choose a spot uniformly among ALL
    // valid spots (equivalent to weighting variants by how many places they
    // fit) — a variant with zero spots can never be chosen.
    var n = kotlin.random.Random.nextInt(total)
    for (spots in perVariant) {
        if (n < spots.size) return spots[n]
        n -= spots.size
    }
    return null
}

/** Source pixels per grid cell in the generated pose canvases. */
internal const val POSE_PX_PER_CELL = 512

/** Metadata for one generated pose bitmap, decoded from the manifest. */
internal data class PoseAsset(
    val bitmap: ImageBitmap,
    val cellRows: List<String>
)

/**
 * PHASE 2 — build pose variants from the generated `lizard_pose_t{tier}_p*`
 * bitmaps. Each pose canvas is an exact N-cols × M-rows grid of cells
 * (512 src px/cell); the manifest tells us N and M and which cells held the
 * chroma-keyed dummy squares ("1" in the row strings).
 *
 * Physical model: the dummy squares form a solid ground mass the lizard
 * stands on (generated poses are all top-facing), so the surface side is
 * BELOW for every current pose: the cells of the canvas's bottom dummy row
 * must land on OCCUPIED habit squares. The solver treats the WHOLE canvas as
 * the footprint — its dummy cells must coincide with real habits and its
 * remaining (lizard) cells must be empty — which is exactly the placement
 * contract of the generation prompts.
 *
 * Wall-edge climbing (LEFT/RIGHT) and hanging (ABOVE) remain covered by the
 * phase-1 strip variants, which callers always bake alongside the poses.
 */
internal fun buildPoseVariants(
    poses: List<PoseAsset>
): List<LizardVariant> {
    val out = ArrayList<LizardVariant>(poses.size)
    for (pose in poses) {
        val bmp = pose.bitmap.asAndroidBitmap()
        val canvasCols = bmp.width / POSE_PX_PER_CELL
        val canvasRows = bmp.height / POSE_PX_PER_CELL
        if (canvasCols == 0 || canvasRows == 0) continue
        if (pose.cellRows.size < canvasRows) continue
        val dummies = HashSet<Int>()
        for (r in 0 until canvasRows) {
            val rowStr = pose.cellRows[r]
            for (c in 0 until canvasCols) {
                if (c < rowStr.length && rowStr[c] == '1') {
                    dummies.add(r * canvasCols + c)
                }
            }
        }
        out.add(
            LizardVariant(
                bitmap = bmp.asImageBitmap(),
                srcX = 0,
                srcY = 0,
                srcW = bmp.width,
                srcH = bmp.height,
                rows = canvasRows.coerceAtMost(GRID_ROWS),
                cols = canvasCols.coerceAtMost(GRID_COLUMNS),
                surface = PerchSurface.BELOW,
                vertical = false,
                dummyCells = dummies,
                isPoseCanvas = true
            )
        )
    }
    return out
}

/**
 * Load every generated pose for [tier]: `lizard_pose_t{tier}_p{NN}` drawables
 * plus their manifest metadata from `assets/lizard_pose_manifest.json`
 * ({"tiers": {"1": [{"file", "cols", "rows", "cells": ["010", ...]}, ...]}}).
 * Missing drawables are skipped; a missing/corrupt manifest yields an empty
 * list (the caller then falls back to the phase-1 strip variants only).
 */
internal fun loadLizardPoseAssets(
    context: android.content.Context,
    tier: Int
): List<PoseAsset> {
    val manifest = runCatching {
        context.assets.open("lizard_pose_manifest.json").bufferedReader()
            .use { it.readText() }
    }.getOrNull() ?: return emptyList()
    val root = org.json.JSONObject(manifest)
    val tiers = root.optJSONObject("tiers") ?: return emptyList()
    val arr = tiers.optJSONArray(tier.toString()) ?: return emptyList()
    val out = ArrayList<PoseAsset>(arr.length())
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val name = obj.optString("file")
        if (name.isEmpty()) continue
        val resId = context.resources.getIdentifier(
            name, "drawable", context.packageName
        )
        if (resId == 0) continue
        val bmp = android.graphics.BitmapFactory.decodeResource(
            context.resources, resId
        ) ?: continue
        val cells = ArrayList<String>(obj.optInt("rows", 0))
        val cellsArr = obj.optJSONArray("cells")
        if (cellsArr != null) {
            for (j in 0 until cellsArr.length()) cells.add(cellsArr.getString(j))
        }
        out.add(PoseAsset(bitmap = bmp.asImageBitmap(), cellRows = cells))
    }
    return out
}
