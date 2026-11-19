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
 *    content hash of today's rendered figure), `updated` (Long, epoch ms),
 *    `zones` (Int, DISTINCT muscle areas the guest worked today; Kenga-only —
 *    his "confidence" for the scene z-order, see CombinedSceneWidgetProvider).
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

    /**
     * Hard time-box for provider binder calls. Guest apps frozen by the OS
     * (Samsung Freecess) park provider queries indefinitely — without a
     * timeout the widget refresh loop hangs and the widget stays on the
     * host's "Loading…" placeholder forever.
     */
    private const val PROBE_TIMEOUT_MS = 2_000L
    private const val PULL_TIMEOUT_MS = 8_000L

    /** Broadcast Kenga/Octo send after their figure data changes. */
    const val ACTION_SCENE_FIGURES_CHANGED = "com.example.tail.ACTION_SCENE_FIGURES_CHANGED"

    enum class App(val packageName: String, val authority: String) {
        KENGA("com.example.kenga", "com.example.kenga.figure"),
        OCTO("com.example.octo", "com.example.octo.figure"),
    }

    /**
     * One app's live figure: decoded bitmap + the hash it was served with +
     * today's colored-zone count ([zones], 0 when the provider doesn't
     * advertise one — older providers / Octo).
     */
    data class Figure(val bitmap: Bitmap?, val hash: String, val zones: Int = 0)

    // ── disk cache (no_public_DIRS needed: only this app reads these) ─────

    private fun cacheFile(context: Context, app: App): File =
        File(context.cacheDir, "scene_figure_${app.packageName}.png")

    private fun hashFile(context: Context, app: App): File =
        File(context.cacheDir, "scene_figure_${app.packageName}.hash")

    private fun zonesFile(context: Context, app: App): File =
        File(context.cacheDir, "scene_figure_${app.packageName}.zones")

    /**
     * Last-known colored-zone count for [app] WITHOUT a provider query
     * (the synchronous widget rebind path must not touch providers).
     * Persisted on every successful [fetchIfStale] probe.
     */
    fun cachedZones(context: Context, app: App): Int =
        try {
            zonesFile(context, app).readText().trim().toIntOrNull() ?: 0
        } catch (_: Exception) {
            0
        }

    /**
     * Disk-only figure (NO provider access): cached bitmap + stored hash +
     * last-known zones. Safe for the synchronous widget rebind path, which
     * must never block on a provider — a guest app frozen by the OS
     * (Samsung Freecess "BG freezed") parks the binder call indefinitely,
     * hanging the widget update and leaving the host's "Loading…"
     * placeholder on screen forever. The async refresh path is the only
     * place that talks to providers.
     */
    fun diskFigure(context: Context, app: App): Figure {
        val hash = try {
            val hs = hashFile(context, app)
            if (hs.exists()) hs.readText().trim() else ""
        } catch (_: Exception) { "" }
        return Figure(cachedBitmap(app, cacheFile(context, app)), hash, cachedZones(context, app))
    }

    /**
     * Runs [block] on a short-lived daemon thread with a hard [ms] timeout.
     * Content-provider binder calls cannot be cancelled cooperatively, and a
     * frozen provider process parks them indefinitely — so on timeout we
     * abandon the thread (it finishes whenever the OS unfreezes the target)
     * and continue with cached data instead of hanging the caller.
     */
    private fun <T> callWithTimeout(ms: Long, block: java.util.concurrent.Callable<T?>): T? =
        try {
            val task = java.util.concurrent.FutureTask(block)
            Thread(task, "figureProbe").apply { isDaemon = true }.start()
            task.get(ms, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            null
        }

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
            // Time-boxed (frozen-provider defence) and falls back to the
            // pre-zones projection for older installed providers that
            // reject unknown columns.
            var remoteHash: String? = null
            var remoteUpdated = 0L
            var remoteZones = 0
            val figureUri = Uri.parse("content://${app.authority}/v1/figure")
            var cursor = callWithTimeout(PROBE_TIMEOUT_MS) {
                context.contentResolver.query(
                    figureUri, arrayOf("hash", "updated", "zones"), null, null, null
                )
            }
            if (cursor == null) {
                cursor = callWithTimeout(PROBE_TIMEOUT_MS) {
                    context.contentResolver.query(
                        figureUri, arrayOf("hash", "updated"), null, null, null
                    )
                }
            }
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    remoteHash = c.getString(c.getColumnIndexOrThrow("hash"))
                    remoteUpdated = c.getLong(c.getColumnIndexOrThrow("updated"))
                    // Optional column (older providers lack it).
                    val zi = c.getColumnIndex("zones")
                    if (zi >= 0 && !c.isNull(zi)) remoteZones = c.getInt(zi)
                }
            }
            if (remoteHash == null || remoteHash == FigureSnapshotContract.EMPTY_HASH) {
                // App absent, permission denied, or no figure yet today.
                return Figure(cachedBitmap(app, cache), remoteHash ?: "", 0)
            }
            // Persist the confidence metric for the synchronous rebind path.
            try {
                zonesFile(context, app).writeText(remoteZones.toString())
            } catch (_: Exception) {
            }

            val localHash = if (hashStore.exists()) hashStore.readText().trim() else ""
            if (remoteHash != localHash || !cache.exists()) {
                // 2. Hash differs -> pull the actual PNG bytes (time-boxed:
                // a frozen provider must not stall the refresh loop).
                val bytes = callWithTimeout(PULL_TIMEOUT_MS) {
                    context.contentResolver.openInputStream(
                        Uri.parse("content://${app.authority}/v1/figure")
                    )?.use { it.readBytes() }
                }
                if (bytes != null && bytes.isNotEmpty()) {
                    // Sanity: only cache what decodes and hashes as advertised.
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        cache.writeBytes(bytes)
                        hashStore.writeText(remoteHash!!)
                        // OCTO ONLY: his torso dome is an ENCLOSED transparent
                        // pocket that must render solid. Kenga must NOT get
                        // this — his right arm-body pocket is a real
                        // see-through gap, but at served resolution (~270 px)
                        // its pinch channel seals shut with anti-aliased px,
                        // so the border-flood classified it as a hole and
                        // painted it WHITE (2026-11-19 bug). His figures are
                        // served as-is; the disk cache stores raw bytes, so
                        // gating at decode time needs no cache invalidation.
                        val processed = if (app == App.OCTO) fillInteriorHoles(bmp) else bmp
                        Log.i(TAG, "${app.packageName} figure updated " +
                                "(${bytes.size / 1024} KB, hash=${remoteHash?.take(8)}…)")
                        return Figure(processed, remoteHash!!, remoteZones)
                    }
                }
                Log.w(TAG, "${app.packageName} served undecodable figure; keeping cache")
            }
            return Figure(cachedBitmap(app, cache), remoteHash!!, remoteZones)
        } catch (e: SecurityException) {
            // Signature mismatch — not our app family; degrade silently.
            return Figure(cachedBitmap(app, cache), "")
        } catch (e: Exception) {
            Log.i(TAG, "${app.packageName} figure unavailable: ${e.message}")
            return Figure(cachedBitmap(app, cache), "")
        }
    }

    private fun cachedBitmap(app: App, cache: File): Bitmap? {
        if (!cache.exists()) return null
        return try {
            val bmp = BitmapFactory.decodeFile(cache.absolutePath) ?: return null
            if (app == App.OCTO) fillInteriorHoles(bmp) else bmp
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

    /** Colored body-part areas today (Kenga's confidence; optional column). */
    const val COL_ZONES = "zones"

    /** Provider value when the app has nothing rendered yet. */
    const val EMPTY_HASH = "empty"
}
