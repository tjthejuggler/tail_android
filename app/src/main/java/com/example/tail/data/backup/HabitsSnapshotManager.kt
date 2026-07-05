package com.example.tail.data.backup

import android.content.Context
import android.util.Log
import com.example.tail.data.HabitsDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

private const val TAG = "HabitsSnapshotManager"

/** Directory (under filesDir) where habit DB snapshots are kept. */
private const val SNAPSHOT_DIR = "habit_snapshots"

/** Snapshot file name pattern: `snap_<epochMillis>_<shortHash>.json`. */
private const val SNAPSHOT_PREFIX = "snap_"
private const val SNAPSHOT_SUFFIX = ".json"

/**
 * Content-addressed, self-pruning snapshot store for the habits database.
 *
 * WHY THIS EXISTS (post-incident hardening, 2026-07-05):
 *   The habits DB is a single JSON file written via SAF with a truncate-then-write
 *   ("wt") stream. If that write is interrupted mid-stream (process killed, OOM,
 *   device sleep) the on-disk file is left truncated — which is exactly how the
 *   several-MB `habitsdb.txt` collapsed to ~100 KB and lost all points overnight.
 *
 *   These snapshots are the safety net. Crucially they live in the app's PRIVATE
 *   internal storage ([Context.getFilesDir]) — NOT in the Syncthing-synced folder
 *   and NOT written through the same SAF path — so a corruption of the main file
 *   can never take the snapshots with it. Every successful DB save drops a
 *   snapshot here, and we ALSO snapshot the healthy on-disk state right before we
 *   overwrite it, so there is always a "last known good" to roll back to.
 *
 * RETENTION — GFS ("grandfather-father-son"), the industry-standard backup
 * thinning scheme, so we get dense recent history without unbounded disk growth:
 *   - keep EVERY snapshot from the last hour                (fine-grained undo)
 *   - keep the newest snapshot per hour for the last day    (hourly)
 *   - keep the newest snapshot per day for the last week     (daily)
 *   - keep the newest snapshot per week beyond that          (weekly)
 *   - hard caps: at most [MAX_SNAPSHOTS] files and [MAX_TOTAL_BYTES] on disk.
 *
 * Snapshots are content-addressed: an identical DB (same JSON hash) as the most
 * recent snapshot is skipped, so repeated no-op saves don't spam the store.
 */
class HabitsSnapshotManager(private val context: Context) {

    private val gson = Gson()
    private val dbType = object : TypeToken<Map<String, Map<String, Int>>>() {}.type

    /** Serialises snapshot writes so concurrent saves can't race on retention. */
    private val mutex = Mutex()

    companion object {
        /** Keep everything newer than this untouched (fine-grained recent undo). */
        private val KEEP_ALL_WINDOW_MS = TimeUnit.HOURS.toMillis(1)
        /** Hourly buckets kept for this long. */
        private val HOURLY_WINDOW_MS = TimeUnit.DAYS.toMillis(1)
        /** Daily buckets kept for this long. */
        private val DAILY_WINDOW_MS = TimeUnit.DAYS.toMillis(7)
        /** Beyond DAILY_WINDOW we keep one per week. */

        /** Never keep more than this many snapshot files. */
        private const val MAX_SNAPSHOTS = 200
        /** Never let the snapshot dir exceed this many bytes (~40 MB). */
        private const val MAX_TOTAL_BYTES = 40L * 1024 * 1024
        /** A snapshot must contain at least this many entries to be worth keeping. */
        private const val MIN_ENTRIES_TO_SNAPSHOT = 1
    }

    private fun dir(): File = File(context.filesDir, SNAPSHOT_DIR).apply { if (!exists()) mkdirs() }

    /**
     * Records a snapshot of [db]. No-ops if [db] is empty/trivial or identical to
     * the most recent snapshot. Always runs retention afterwards. Never throws —
     * snapshotting must never break a legitimate save.
     *
     * @param reason short tag for logs (e.g. "pre-write", "post-save").
     */
    suspend fun snapshot(db: HabitsDatabase, reason: String) = withContext(Dispatchers.IO) {
        val entryCount = db.values.sumOf { it.size }
        if (entryCount < MIN_ENTRIES_TO_SNAPSHOT) {
            // Refuse to snapshot an empty/near-empty DB — it's never a "good" state
            // worth preserving and would just dilute the retained history.
            return@withContext
        }
        try {
            val json = gson.toJson(db)
            val hash = shortHash(json)

            mutex.withLock {
                val existing = listSnapshotFiles()
                // Dedup: if the newest snapshot already has this exact content, skip.
                val newest = existing.maxByOrNull { it.timestamp }
                if (newest != null && newest.hash == hash) {
                    return@withLock
                }
                val fileName = "$SNAPSHOT_PREFIX${System.currentTimeMillis()}_$hash$SNAPSHOT_SUFFIX"
                val out = File(dir(), fileName)
                // Atomic even here: temp + rename, so a killed snapshot write can't
                // leave a half-file that later looks like a valid restore point.
                val tmp = File(dir(), "$fileName.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(out)) {
                    // Fallback: copy then delete tmp.
                    out.writeText(json)
                    tmp.delete()
                }
                Log.i(TAG, "snapshot[$reason]: wrote $fileName ($entryCount entries, ${json.length} bytes)")
                prune()
            }
        } catch (e: Exception) {
            Log.w(TAG, "snapshot[$reason]: failed (non-fatal): ${e.message}")
        }
    }

    /** Metadata for one snapshot file. */
    data class SnapshotInfo(
        val file: File,
        val timestamp: Long,
        val hash: String,
        val sizeBytes: Long
    )

    /** Returns all snapshots, newest first, for the restore UI. */
    suspend fun listSnapshots(): List<SnapshotInfo> = withContext(Dispatchers.IO) {
        listSnapshotFiles().sortedByDescending { it.timestamp }
    }

    /**
     * Loads and parses the snapshot at [file]. Returns null if it can't be read
     * or parsed (so the restore UI can grey it out rather than crash).
     */
    suspend fun readSnapshot(file: File): HabitsDatabase? = withContext(Dispatchers.IO) {
        try {
            val text = file.readText()
            if (text.isBlank()) return@withContext null
            gson.fromJson<HabitsDatabase>(text, dbType)
        } catch (e: Exception) {
            Log.w(TAG, "readSnapshot: failed for ${file.name}: ${e.message}")
            null
        }
    }

    /** Total entry count for a snapshot (for display). Best-effort, 0 on failure. */
    suspend fun entryCountOf(file: File): Int = withContext(Dispatchers.IO) {
        readSnapshot(file)?.values?.sumOf { it.size } ?: 0
    }

    // ─── internals ──────────────────────────────────────────────────────────

    private fun listSnapshotFiles(): List<SnapshotInfo> {
        val d = dir()
        val files = d.listFiles { f ->
            f.isFile && f.name.startsWith(SNAPSHOT_PREFIX) && f.name.endsWith(SNAPSHOT_SUFFIX)
        } ?: return emptyList()
        return files.mapNotNull { f ->
            val parsed = parseName(f.name) ?: return@mapNotNull null
            SnapshotInfo(
                file = f,
                timestamp = parsed.first,
                hash = parsed.second,
                sizeBytes = try { f.length() } catch (_: Exception) { 0L }
            )
        }
    }

    /** Parses `snap_<millis>_<hash>.json` → (millis, hash). */
    private fun parseName(name: String): Pair<Long, String>? {
        return try {
            val core = name.removePrefix(SNAPSHOT_PREFIX).removeSuffix(SNAPSHOT_SUFFIX)
            val us = core.indexOf('_')
            if (us <= 0) return null
            val millis = core.substring(0, us).toLongOrNull() ?: return null
            val hash = core.substring(us + 1)
            millis to hash
        } catch (_: Exception) {
            null
        }
    }

    private fun shortHash(json: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(json.toByteArray())
        return bytes.take(6).joinToString("") { "%02x".format(it) }
    }

    /**
     * GFS thinning + hard caps. Called under [mutex].
     * Determines the set of snapshots to KEEP, deletes the rest.
     */
    private fun prune() {
        val all = listSnapshotFiles().sortedByDescending { it.timestamp }
        if (all.isEmpty()) return
        val now = System.currentTimeMillis()
        val keep = LinkedHashSet<File>()

        // Bucket helpers: for each retention tier, keep the newest snapshot per bucket.
        val seenHourly = HashSet<Long>()
        val seenDaily = HashSet<Long>()
        val seenWeekly = HashSet<Long>()

        for (s in all) {
            val age = now - s.timestamp
            when {
                age <= KEEP_ALL_WINDOW_MS -> keep.add(s.file) // keep everything recent
                age <= HOURLY_WINDOW_MS -> {
                    val bucket = s.timestamp / TimeUnit.HOURS.toMillis(1)
                    if (seenHourly.add(bucket)) keep.add(s.file)
                }
                age <= DAILY_WINDOW_MS -> {
                    val bucket = s.timestamp / TimeUnit.DAYS.toMillis(1)
                    if (seenDaily.add(bucket)) keep.add(s.file)
                }
                else -> {
                    val bucket = s.timestamp / TimeUnit.DAYS.toMillis(7)
                    if (seenWeekly.add(bucket)) keep.add(s.file)
                }
            }
        }

        // Delete everything not in the keep set.
        for (s in all) {
            if (s.file !in keep) {
                try { s.file.delete() } catch (_: Exception) {}
            }
        }

        // Hard cap on count: keep the newest MAX_SNAPSHOTS of what remains.
        var remaining = listSnapshotFiles().sortedByDescending { it.timestamp }
        if (remaining.size > MAX_SNAPSHOTS) {
            remaining.drop(MAX_SNAPSHOTS).forEach { try { it.file.delete() } catch (_: Exception) {} }
            remaining = remaining.take(MAX_SNAPSHOTS)
        }

        // Hard cap on total bytes: delete oldest until under budget.
        var total = remaining.sumOf { it.sizeBytes }
        if (total > MAX_TOTAL_BYTES) {
            val oldestFirst = remaining.sortedBy { it.timestamp }
            for (s in oldestFirst) {
                if (total <= MAX_TOTAL_BYTES) break
                if (remaining.size <= 1) break // never delete the very last snapshot
                try { s.file.delete() } catch (_: Exception) {}
                total -= s.sizeBytes
            }
        }
    }
}
