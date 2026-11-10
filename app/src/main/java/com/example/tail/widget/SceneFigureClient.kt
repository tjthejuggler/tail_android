package com.example.tail.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import java.io.File
import java.security.MessageDigest

/**
 * Pulls the LIVE mascot figures (Kenga kangaroo-man, Octo octopus-man) from
 * their apps' read-only [FigureSnapshotProvider]s and caches them on disk.
 *
 * Protocol (mirrors the existing Tail integration family):
 *  - Provider authority: "com.example.kenga.figure" / "com.example.octo.figure"
 *  - Endpoint: v1/figure -> columns `figure` (PNG bytes), `hash` (String,
 *    content hash of today's rendered figure), `updated` (Long, epoch ms).
 *  - Access is guarded by the signature-level TAIL_INTEGRATION permission
 *    Tail already holds (same keystore family), so no new permission wiring.
 *
 * Freshness: [fetchIfStale] re-queries only when the cached hash differs —
 * the provider returns the cheap hash column first; PNG bytes are only
 * copied across the binder when the hash changed. Callers ping us on their
 * data changes (ACTION_SCENE_FIGURES_CHANGED broadcast), so a stale cache
 * self-heals within seconds of any logging activity in Kenga/Octo.
 *
 * All failures degrade gracefully: when Kenga/Octo are absent the widget
 * simply renders without that figure (never throws).
 */
object SceneFigureClient {

    private const val TAG = "SceneFigureClient"

    /** Broadcast Kenga/Octo send after their figure data changes. */
    const val ACTION_SCENE_FIGURES_CHANGED = "com.example.tail.ACTION_SCENE_FIGURES_CHANGED"

    enum class App(val packageName: String, val authority: String) {
        KENGA("com.example.kenga", "com.example.kenga.figure"),
        OCTO("com.example.octo", "com.example.octo.figure"),
    }

    /** One app's live figure: decoded bitmap + the hash it was served with. */
    data class Figure(val bitmap: Bitmap?, val hash: String)

    // ── disk cache (no_public_DIRS needed: only this app reads these) ─────

    private fun cacheFile(context: Context, app: App): File =
        File(context.cacheDir, "scene_figure_${app.packageName}.png")

    private fun hashFile(context: Context, app: App): File =
        File(context.cacheDir, "scene_figure_${app.packageName}.hash")

    /** Content hash of arbitrary bytes (matches provider-side hashing). */
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Returns the cached figure for [app], refreshing it first when the
     * provider advertises a different content hash than the cache holds.
     * Bitmap is null when the app is absent or has not rendered yet —
     * callers must treat that as "draw the scene without this figure".
     */
    fun fetchIfStale(context: Context, app: App): Figure {
        val cache = cacheFile(context, app)
        val hashStore = hashFile(context, app)
        try {
            // 1. Cheap probe: what hash is the app serving right now?
            var remoteHash: String? = null
            var remoteUpdated = 0L
            context.contentResolver.query(
                Uri.parse("content://${app.authority}/v1/figure"),
                arrayOf("hash", "updated"), null, null, null
            )?.use { c ->
                if (c.moveToFirst()) {
                    remoteHash = c.getString(c.getColumnIndexOrThrow("hash"))
                    remoteUpdated = c.getLong(c.getColumnIndexOrThrow("updated"))
                }
            }
            if (remoteHash == null || remoteHash == FigureSnapshotContract.EMPTY_HASH) {
                // App absent, permission denied, or no figure yet today.
                return Figure(cachedBitmap(cache), remoteHash ?: "")
            }

            val localHash = if (hashStore.exists()) hashStore.readText().trim() else ""
            if (remoteHash != localHash || !cache.exists()) {
                // 2. Hash differs -> pull the actual PNG bytes.
                val bytes = context.contentResolver.openInputStream(
                    Uri.parse("content://${app.authority}/v1/figure")
                )?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    // Sanity: only cache what decodes and hashes as advertised.
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        cache.writeBytes(bytes)
                        hashStore.writeText(remoteHash!!)
                        // Octo's body art has transparent interior pockets
                        // (the sky showed through his torso). Fill ENCLOSED
                        // transparent regions with white; transparency
                        // connected to the bitmap edge stays (real
                        // background around tentacles etc.). Kenga has no
                        // holes, so this is a no-op for him.
                        val processed = fillInteriorHoles(bmp)
                        Log.i(TAG, "${app.packageName} figure updated " +
                                "(${bytes.size / 1024} KB, hash=${remoteHash?.take(8)}…)")
                        return Figure(processed, remoteHash!!)
                    }
                }
                Log.w(TAG, "${app.packageName} served undecodable figure; keeping cache")
            }
            return Figure(cachedBitmap(cache), remoteHash!!)
        } catch (e: SecurityException) {
            // Signature mismatch — not our app family; degrade silently.
            return Figure(cachedBitmap(cache), "")
        } catch (e: Exception) {
            Log.i(TAG, "${app.packageName} figure unavailable: ${e.message}")
            return Figure(cachedBitmap(cache), "")
        }
    }

    private fun cachedBitmap(cache: File): Bitmap? {
        if (!cache.exists()) return null
        return try {
            fillInteriorHoles(BitmapFactory.decodeFile(cache.absolutePath))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fills transparent pockets that are fully ENCLOSED by the figure with
     * white, keeping edge-connected transparency (true background) intact.
     *
     * Algorithm: flood-fill "outside" from every transparent edge pixel;
     * any transparent pixel NOT reached is an interior hole -> opaque white.
     * The octopus-man's torso dome is exactly such a hole; his tentacle
     * gaps connect to the frame edge and stay transparent. Runs once per
     * pulled figure (hash-gated), so the cost is amortised.
     */
    private fun fillInteriorHoles(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)

        // Fast path: no interior alpha can exist if the figure already
        // covers most pixels — still do the scan, it's O(n) and cheap
        // relative to a cross-process PNG pull.
        val outside = BooleanArray(w * h)
        val stack = ArrayDeque<Int>()
        fun isTransparent(i: Int) = (px[i] ushr 24) < 32

        // Seed: all transparent pixels on the frame border.
        for (x in 0 until w) {
            for (y in intArrayOf(0, h - 1)) {
                val i = y * w + x
                if (isTransparent(i) && !outside[i]) {
                    outside[i] = true
                    stack.addLast(i)
                }
            }
        }
        for (y in 0 until h) {
            for (x in intArrayOf(0, w - 1)) {
                val i = y * w + x
                if (isTransparent(i) && !outside[i]) {
                    outside[i] = true
                    stack.addLast(i)
                }
            }
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            if (x > 0) { val j = i - 1; if (isTransparent(j) && !outside[j]) { outside[j] = true; stack.addLast(j) } }
            if (x < w - 1) { val j = i + 1; if (isTransparent(j) && !outside[j]) { outside[j] = true; stack.addLast(j) } }
            if (y > 0) { val j = i - w; if (isTransparent(j) && !outside[j]) { outside[j] = true; stack.addLast(j) } }
            if (y < h - 1) { val j = i + w; if (isTransparent(j) && !outside[j]) { outside[j] = true; stack.addLast(j) } }
        }

        var filled = 0
        val white = 0xFFFFFFFF.toInt()
        for (i in px.indices) {
            if (isTransparent(i) && !outside[i]) {
                px[i] = white
                filled++
            }
        }
        if (filled == 0) return src
        Log.i(TAG, "Filled $filled interior transparent px with white")
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }
}

/**
 * Column/URI constants shared verbatim with Kenga's and Octo's
 * [FigureSnapshotProvider] implementations (kept in sync by the ADR).
 */
object FigureSnapshotContract {
    const val PATH = "v1/figure"
    const val COL_FIGURE = "figure"
    const val COL_HASH = "hash"
    const val COL_UPDATED = "updated"

    /** Provider value when the app has nothing rendered yet. */
    const val EMPTY_HASH = "empty"
}
