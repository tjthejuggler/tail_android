package com.example.tail.data.backup

import android.content.Context
import android.util.Log
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "TimestampSnapshots"

/**
 * GFS-retained, content-addressed safety copies of the internal timestamp
 * stores (`habit_timestamps.json` + `habit_timestamp_minutes.json`).
 *
 * WHY THIS EXISTS (post-incident hardening, 2026-10-05): the 2026-10-04
 * mass timestamp wipe (cross-process torn read persisted as an empty store)
 * was only recoverable because the AI-assistant happened to hold a copy from
 * that afternoon. [com.example.tail.data.backup.HabitsSnapshotManager] has
 * provided exactly this safety net for the habits DB since 2026-07-05; the
 * timestamp store — which cannot be re-derived from any other file — had
 * none. Every successful write to either timestamp file now drops a copy
 * here, in a SEPARATE directory, so a wipe of the live files can never take
 * the snapshots with it.
 *
 * Retention mirrors HabitsSnapshotManager's GFS scheme:
 *   - keep every snapshot from the last hour
 *   - keep the newest per hour for the last day, per day for the last week,
 *     per week beyond that
 *   - hard caps: [MAX_SNAPSHOTS] files / [MAX_TOTAL_BYTES] bytes
 * Identical content to the newest snapshot is skipped (content-addressed).
 */
object TimestampSnapshotManager {

    private const val DIR_NAME = "habit_timestamp_snapshots"
    private const val PREFIX = "snap_"
    private const val SUFFIX = ".json"

    private val KEEP_ALL_WINDOW_MS = TimeUnit.HOURS.toMillis(1)
    private val HOURLY_WINDOW_MS = TimeUnit.DAYS.toMillis(1)
    private val DAILY_WINDOW_MS = TimeUnit.DAYS.toMillis(7)

    private const val MAX_SNAPSHOTS = 200
    private const val MAX_TOTAL_BYTES = 60L * 1024 * 1024

    /** Files covered. Copies are stored as `<name>/snap_*.json`. */
    private val COVERED_FILES = listOf(
        "habit_timestamps.json",
        "habit_timestamp_minutes.json"
    )

    /**
     * Snapshots every covered file that exists. Called after each successful
     * write — never throws, never blocks the caller (best-effort on the
     * caller's dispatcher).
     */
    fun snapshotAll(context: Context) {
        val filesDir = context.filesDir
        for (name in COVERED_FILES) {
            try {
                val src = File(filesDir, name)
                if (!src.exists() || src.length() == 0L) continue
                snapshotFile(filesDir, src)
            } catch (e: Throwable) {
                Log.w(TAG, "snapshot of $name failed (non-fatal): ${e.message}")
            }
        }
    }

    private fun snapshotFile(filesDir: File, src: File) {
        val dir = File(File(filesDir, DIR_NAME), src.name).apply { mkdirs() }
        val bytes = src.readBytes()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val shortHash = digest.joinToString("") { "%02x".format(it) }.take(16)

        val existing = dir.listFiles { f -> f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } ?: return
        // Content-addressed skip: newest snapshot identical → no-op.
        if (existing.isNotEmpty() &&
            existing.first().name.substringAfter(PREFIX).substringBefore("_") == shortHash
        ) {
            prune(dir, 0L)
            return
        }

        val dest = File(dir, "$PREFIX$shortHash${System.currentTimeMillis()}_$shortHash$SUFFIX")
        val tmp = File(dir, dest.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(dest)) {
            tmp.delete()
            return
        }
        prune(dir, dest.length())
    }

    /** GFS thinning + hard caps. [newBytes] is the just-written snapshot size. */
    private fun prune(dir: File, newBytes: Long) {
        val files = dir.listFiles { f -> f.name.endsWith(SUFFIX) }
            ?.sortedByDescending { it.name } ?: return
        val now = System.currentTimeMillis()
        var kept = 0
        var total = newBytes
        var lastHourBucket = -1L
        var lastDayBucket = -1L
        var lastWeekBucket = -1L
        for (f in files) {
            val ts = f.name.split("_").getOrNull(2)?.toLongOrNull() ?: f.lastModified()
            val age = now - ts
            val keep = when {
                age <= KEEP_ALL_WINDOW_MS -> true
                age <= HOURLY_WINDOW_MS -> {
                    val b = ts / TimeUnit.HOURS.toMillis(1)
                    if (b != lastHourBucket) { lastHourBucket = b; true } else false
                }
                age <= DAILY_WINDOW_MS -> {
                    val b = ts / TimeUnit.DAYS.toMillis(1)
                    if (b != lastDayBucket) { lastDayBucket = b; true } else false
                }
                else -> {
                    val b = ts / TimeUnit.DAYS.toMillis(7)
                    if (b != lastWeekBucket) { lastWeekBucket = b; true } else false
                }
            }
            if (keep && kept < MAX_SNAPSHOTS && total <= MAX_TOTAL_BYTES) {
                kept++
                total += f.length()
            } else {
                runCatching { f.delete() }
            }
        }
    }
}
