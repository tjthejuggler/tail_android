package com.example.tail.data.backup

import android.content.Context
import android.util.Log
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsDbCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.Reader
import java.io.StringReader
import java.io.Writer
import java.security.DigestOutputStream
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
    suspend fun snapshot(db: HabitsDatabase, reason: String) {
        val entryCount = HabitsDbCodec.countEntries(db)
        if (entryCount < MIN_ENTRIES_TO_SNAPSHOT) {
            // Refuse to snapshot an empty/near-empty DB — it's never a "good" state
            // worth preserving and would just dilute the retained history.
            return
        }
        try {
            val staged = stage()
            try {
                staged.writer.use { HabitsDbCodec.write(db, it) }
            } catch (e: Throwable) {
                staged.discard()
                throw e
            }
            commit(staged, entryCount, reason)
        } catch (e: Throwable) {
            // Throwable, not Exception: snapshotting must never take the process
            // down (an OutOfMemoryError serializing a multi-MB DB is exactly the
            // crash class fixed on 2026-09-10).
            Log.w(TAG, "snapshot[$reason]: failed (non-fatal): ${e.message}")
        }
    }

    /**
     * A snapshot being written. Bytes go to a temp file inside the snapshot
     * directory while a SHA-256 is accumulated on the fly, so the caller can
     * tee its real output through [stream]/[writer] and finish with
     * [commit] — the snapshot costs no extra heap beyond the I/O buffer.
     */
    class Staged internal constructor(
        internal val tmp: File,
        private val digest: MessageDigest,
        /** Raw byte sink (UTF-8 text expected). */
        val stream: OutputStream
    ) {
        /** UTF-8 character view over [stream]. Closing it closes the stream. */
        val writer: Writer by lazy { BufferedWriter(OutputStreamWriter(stream, Charsets.UTF_8)) }

        internal fun hash(): String =
            digest.digest().take(6).joinToString("") { "%02x".format(it) }

        /** Abandons the staged file (never throws). */
        fun discard() {
            try { stream.close() } catch (_: Exception) {}
            try { tmp.delete() } catch (_: Exception) {}
        }
    }

    /** Opens a new staged snapshot. The caller must [commit] or [Staged.discard]. */
    fun stage(): Staged {
        val digest = MessageDigest.getInstance("SHA-256")
        val tmp = File(dir(), "$SNAPSHOT_PREFIX${System.currentTimeMillis()}_${System.nanoTime()}.tmp")
        val raw = FileOutputStream(tmp)
        val out: OutputStream = DigestOutputStream(raw.buffered(64 * 1024), digest)
        return Staged(tmp, digest, out)
    }

    /**
     * Finalises a [Staged] snapshot whose [Staged.stream]/[Staged.writer] the
     * caller has already CLOSED. [entryCount] is the caller-known total (the
     * count it streamed). Refuses near-empty payloads and dedups against the
     * newest snapshot's hash. Never throws.
     */
    suspend fun commit(staged: Staged, entryCount: Int, reason: String) = withContext(Dispatchers.IO) {
        try {
            try { staged.stream.close() } catch (_: Exception) {}
            if (entryCount < MIN_ENTRIES_TO_SNAPSHOT) {
                Log.w(TAG, "snapshot[$reason]: refused near-empty payload ($entryCount entries)")
                staged.discard()
                return@withContext
            }
            val hash = staged.hash()
            mutex.withLock {
                val newest = listSnapshotFiles().maxByOrNull { it.timestamp }
                if (newest != null && newest.hash == hash) {
                    staged.discard()
                    return@withLock
                }
                val fileName = "$SNAPSHOT_PREFIX${System.currentTimeMillis()}_$hash$SNAPSHOT_SUFFIX"
                val out = File(dir(), fileName)
                if (!staged.tmp.renameTo(out)) {
                    staged.tmp.copyTo(out, overwrite = true)
                    staged.tmp.delete()
                }
                Log.i(TAG, "snapshot[$reason]: wrote $fileName ($entryCount entries, ${out.length()} bytes)")
                prune()
            }
        } catch (e: Throwable) {
            staged.discard()
            Log.w(TAG, "snapshot[$reason]: failed (non-fatal): ${e.message}")
        }
    }

    /**
     * Captures the habits document available on [open] as a snapshot WHILE
     * counting its entries in one streaming pass — the pre-write "last known
     * good" capture. Returns the entry count, or null when the stream could
     * not be opened, was blank, or was not a well-formed habits DB (in which
     * case nothing is kept). Memory use is the I/O buffer only.
     */
    suspend fun captureAndCount(open: () -> InputStream?, reason: String): Int? =
        withContext(Dispatchers.IO) {
            val input = try { open() } catch (e: Exception) { null } ?: return@withContext null
            val staged = try { stage() } catch (e: Exception) {
                try { input.close() } catch (_: Exception) {}
                return@withContext null
            }
            var sawContent = false
            val count: Int = try {
                input.use { ins ->
                    val teeIn = TeeInputStream(ins, staged.stream)
                    val reader = teeIn.bufferedReader(Charsets.UTF_8)
                    // Blank-file detection without materialising anything.
                    val pb = java.io.PushbackReader(reader, 1)
                    while (true) {
                        val c = pb.read()
                        if (c == -1) break
                        if (!c.toChar().isWhitespace()) { pb.unread(c); sawContent = true; break }
                    }
                    if (!sawContent) -1 else {
                        val n = HabitsDbCodec.countEntries(pb)
                        // Drain any trailing bytes so the snapshot copy is complete.
                        val sink = CharArray(4096)
                        while (pb.read(sink) != -1) { /* drain */ }
                        n
                    }
                }
            } catch (e: Throwable) {
                staged.discard()
                return@withContext null
            }
            if (count < 0) {
                staged.discard()
                return@withContext null
            }
            commit(staged, count, reason)
            count
        }

    /** Copies every byte read from [source] into [sink]. */
    private class TeeInputStream(
        private val source: InputStream,
        private val sink: OutputStream
    ) : InputStream() {
        override fun read(): Int {
            val b = source.read()
            if (b != -1) sink.write(b)
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = source.read(b, off, len)
            if (n > 0) sink.write(b, off, n)
            return n
        }
        override fun close() { source.close() }
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
     * or parsed (so the restore UI can grey it out rather than crash). Only used
     * for real restore/preview paths — entry counting uses [entryCountOf].
     */
    suspend fun readSnapshot(file: File): HabitsDatabase? = withContext(Dispatchers.IO) {
        try {
            // Streaming parse: a 3 MB snapshot no longer has to exist as a
            // full String on the heap before the parser sees it — restoring a
            // snapshot was itself an OOM risk in the persistent process.
            file.bufferedReader(Charsets.UTF_8).use { HabitsDbCodec.read(it) }
        } catch (e: Throwable) {
            // Throwable, not Exception: an OutOfMemoryError mid-parse of a
            // multi-MB snapshot must grey the snapshot out, never kill the process.
            Log.w(TAG, "readSnapshot: failed for ${file.name}: ${e.message}")
            null
        }
    }

    /**
     * Total entry count for a snapshot (for display and the auto-restore scan).
     *
     * STREAMING: counts entries with a JSON token reader instead of building the
     * full Gson object graph. The auto-restore scan used to full-parse up to a
     * dozen multi-MB snapshots back-to-back on every process start; together with
     * the save-path allocations that OOM-crash-looped the app on 2026-09-10.
     * O(1) memory now. Best-effort, 0 on failure.
     */
    suspend fun entryCountOf(file: File): Int = withContext(Dispatchers.IO) {
        try {
            file.bufferedReader().use { countHabitDbEntries(it) }
        } catch (e: Throwable) {
            0
        }
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

/**
 * Counts total habit entries (inner map size summed over habits) in a
 * habits-db JSON document using a streaming token reader — O(1) memory, no
 * object graph. Throws on malformed or foreign JSON; callers decide how to
 * treat that.
 */
internal fun countHabitDbEntries(reader: Reader): Int = HabitsDbCodec.countEntries(reader)

/** Convenience overload for in-memory JSON text. */
internal fun countHabitDbEntries(text: String): Int = countHabitDbEntries(StringReader(text))
