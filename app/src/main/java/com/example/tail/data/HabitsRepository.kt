package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.backup.HabitsSnapshotManager
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

private const val TAG = "HabitsRepository"

private val gson = Gson()
private val prettyGson = GsonBuilder().setPrettyPrinting().create()
private val dbType = object : TypeToken<Map<String, Map<String, Int>>>() {}.type

/**
 * Result of attempting to load the habits database file. Distinguishing the
 * three states is CRITICAL because the difference between "file is genuinely
 * empty / brand-new" and "file exists but we couldn't read it" determines
 * whether writing back a near-empty skeleton is safe or catastrophic.
 *
 * See [HabitsRepository.loadDatabaseResult] for the full failure taxonomy.
 */
sealed class HabitsLoadResult {
    /** File was opened and parsed successfully. May still be an empty map if the file truly contained `{}`. */
    data class Success(val db: HabitsDatabase) : HabitsLoadResult()

    /**
     * The content resolver returned null for openInputStream (e.g. SAF permission
     * revoked, file deleted, transient SAF failure). The on-disk file may very
     * well still be intact — we just couldn't see it. ABSOLUTELY DO NOT write
     * back to this URI on this code path.
     */
    object UriNotReadable : HabitsLoadResult()

    /**
     * The stream opened and produced text, but the text wasn't valid JSON for
     * our schema (e.g. half-written file caught mid-sync, Syncthing conflict
     * file content, malformed bytes). Same as above: writing here would corrupt.
     */
    data class ParseFailure(val cause: Throwable, val rawBytesLen: Int) : HabitsLoadResult()

    /** A generic I/O exception while reading the stream. Treated as a failure. */
    data class IoFailure(val cause: Throwable) : HabitsLoadResult()
}

/**
 * Thrown by safety-checked write paths ([HabitsRepository.ensureDaysExist],
 * [HabitsRepository.incrementHabitForDate]) when the DB load failed and we
 * refuse to overwrite the on-disk file. Callers should keep their existing
 * cached in-memory DB and surface a non-blocking warning to the user.
 */
class HabitsLoadFailedException(val result: HabitsLoadResult) :
    RuntimeException("Refusing to save because load failed: $result")

/**
 * Handles all read/write operations for habitsdb.txt via SAF URI.
 * This is the single unified database shared between phone and desktop via Syncthing.
 *
 * IMPORTANT — anti-wipe protections (post-incident hardening):
 *   1. [loadDatabaseResult] distinguishes "load failed" from "file genuinely empty".
 *   2. [ensureDaysExist] refuses to save anything if the load failed.
 *   3. [saveDatabase] has an anti-shrinkage guard: if the file currently on disk
 *      has lots of habit data and we're about to write a near-empty skeleton,
 *      we abort and log loudly. This is the last line of defence.
 */
class HabitsRepository {

    companion object {
        /**
         * Process-wide snapshot store, lazily built from the first save's context.
         * Shared across all [HabitsRepository] instances (widgets, receivers,
         * services each create their own repo) so retention is consistent.
         */
        @Volatile
        private var snapshotManagerRef: HabitsSnapshotManager? = null

        /**
         * Process-wide HIGH-WATER MARK of the largest healthy entry count we have
         * ever observed on disk this process. Used by the anti-shrinkage guard as a
         * fallback baseline when the on-disk read itself fails (e.g. a blank/partial
         * file during a Syncthing write). Without this, a failed on-disk read caused
         * the guard to "fail open" and a near-empty DB could clobber a healthy one --
         * exactly the 2026-07-19 chess-sync wipe. -1 means "not yet observed".
         */
        @Volatile
        private var lastKnownGoodEntryCount: Int = -1

        /** Records a healthy on-disk entry count, only ever increasing the mark. */
        fun recordGoodEntryCount(count: Int) {
            if (count > lastKnownGoodEntryCount) lastKnownGoodEntryCount = count
        }

        /** The high-water mark, or -1 if nothing healthy has been seen yet. */
        fun highWaterEntryCount(): Int = lastKnownGoodEntryCount

        /**
         * A snapshot (or on-disk DB) must have more than this many entries to be
         * treated as a trustworthy baseline for the anti-shrinkage guard and for
         * AUTO-RESTORE. Keeps a small brand-new install from "restoring" over
         * legitimately tiny early data.
         */
        const val MIN_RESTORE_BASELINE: Int = 50
    }

    /**
     * Reads and parses the habits JSON file from the given SAF URI.
     * Returns an empty map if the file is missing or malformed — for
     * BACKWARDS COMPATIBILITY with existing callers that only care about the
     * happy path. If you need to know whether the load *failed* (vs the file
     * just being empty), use [loadDatabaseResult] instead.
     */
    suspend fun loadDatabase(uri: Uri, context: Context): HabitsDatabase {
        return when (val r = loadDatabaseResult(uri, context)) {
            is HabitsLoadResult.Success -> r.db
            else -> emptyMap()
        }
    }

    /**
     * Reads and parses the habits JSON file with full failure information.
     * The caller can use this to AVOID writing back to a URI that just failed
     * to load — which would otherwise overwrite a perfectly good on-disk file
     * with an empty skeleton.
     */
    suspend fun loadDatabaseResult(uri: Uri, context: Context): HabitsLoadResult =
        withContext(Dispatchers.IO) {
            val cr = context.contentResolver
            val stream = try {
                cr.openInputStream(uri)
            } catch (e: Exception) {
                Log.w(TAG, "loadDatabaseResult: openInputStream threw ${e.javaClass.simpleName}: ${e.message}")
                return@withContext HabitsLoadResult.IoFailure(e)
            }
            if (stream == null) {
                Log.w(TAG, "loadDatabaseResult: openInputStream returned null for $uri")
                return@withContext HabitsLoadResult.UriNotReadable
            }
            val text = try {
                stream.use { it.bufferedReader().readText() }
            } catch (e: Exception) {
                Log.w(TAG, "loadDatabaseResult: stream read failed: ${e.message}")
                return@withContext HabitsLoadResult.IoFailure(e)
            }
            if (text.isBlank()) {
                // A truly blank file is suspicious (Syncthing partial write, manual
                // deletion, etc.). Treat as parse failure so we don't overwrite it.
                Log.w(TAG, "loadDatabaseResult: file is blank/empty (${text.length} chars) — treating as ParseFailure")
                return@withContext HabitsLoadResult.ParseFailure(
                    IllegalStateException("file is blank"),
                    rawBytesLen = text.length
                )
            }
            try {
                val parsed: HabitsDatabase? = gson.fromJson(text, dbType)
                val db = parsed ?: emptyMap()
                // Track the largest healthy DB we've ever seen so the anti-shrinkage
                // guard has a baseline even when a later read fails mid-Syncthing-write.
                recordGoodEntryCount(db.values.sumOf { it.size })
                HabitsLoadResult.Success(db)
            } catch (e: Exception) {
                Log.w(TAG, "loadDatabaseResult: JSON parse failed (${text.length} chars): ${e.message}")
                HabitsLoadResult.ParseFailure(e, rawBytesLen = text.length)
            }
        }

    /**
     * Writes the habits database back to the SAF URI as formatted JSON.
     * Validates the data before writing to prevent corruption.
     *
     * Also includes an ANTI-SHRINKAGE GUARD: if the file currently on disk is
     * substantially larger than what we're about to write (rough heuristic: the
     * new payload has fewer than half the total entries of the on-disk one),
     * the write is ABORTED and logged. This is the last line of defence against
     * accidentally overwriting a healthy DB with a skeleton/empty one.
     *
     * DURABILITY (post-incident hardening, 2026-07-05):
     *   The SAF write uses truncate-then-stream ("wt"). If it is interrupted
     *   mid-stream the file is left truncated — this is exactly how the DB
     *   collapsed to ~100 KB. Two defences:
     *     1. Every healthy on-disk state we read for the guard is captured to a
     *        [HabitsSnapshotManager] snapshot in PRIVATE internal storage BEFORE
     *        we overwrite it, and the new state is snapshotted AFTER a confirmed
     *        successful write. Snapshots are recoverable via the Settings UI.
     *     2. The output stream is explicitly flushed and its file descriptor
     *        synced ("wts") so the bytes are on stable storage before we return,
     *        shrinking the interruption window as much as SAF allows.
     */
    suspend fun saveDatabase(uri: Uri, context: Context, db: HabitsDatabase) =
        withContext(Dispatchers.IO) {
            val json = prettyGson.toJson(db)
            // Validate round-trip before writing
            val validated: HabitsDatabase? = try {
                gson.fromJson(json, dbType)
            } catch (e: Exception) {
                null
            }
            if (validated == null) {
                Log.w(TAG, "saveDatabase: round-trip JSON validation failed, ABORTING save")
                return@withContext
            }

            // ── Anti-shrinkage guard ──────────────────────────────────────────
            // Read what is currently on disk and reject the write if we'd be
            // catastrophically shrinking it. Errors here fall through to the
            // fail-closed fallback below, which uses the high-water mark.
            val newEntryCount = db.values.sumOf { it.size }
            val onDiskResult = try {
                loadDatabaseResult(uri, context)
            } catch (e: Exception) {
                HabitsLoadResult.IoFailure(e)
            }
            val onDisk: HabitsDatabase? = (onDiskResult as? HabitsLoadResult.Success)?.db

            if (onDisk != null) {
                val onDiskEntryCount = onDisk.values.sumOf { it.size }
                // Tuning: only trigger the guard when the on-disk DB is non-trivial
                // (>50 entries) AND we'd be writing fewer than half as many entries.
                // This catches the full-wipe scenario (writing 0..76 entries on top
                // of thousands) without blocking legitimate small edits or first writes.
                if (onDiskEntryCount > MIN_RESTORE_BASELINE && newEntryCount * 2 < onDiskEntryCount) {
                    Log.e(
                        TAG,
                        "saveDatabase: ANTI-SHRINKAGE GUARD TRIPPED. Refusing to overwrite " +
                                "$onDiskEntryCount on-disk entries with only $newEntryCount new entries. " +
                                "This save has been BLOCKED to prevent data loss."
                    )
                    return@withContext
                }

                // Snapshot the healthy pre-write state so we can always roll back
                // to what was on disk before this overwrite. Never blocks the save.
                snapshotManager(context).snapshot(onDisk, reason = "pre-write")
            } else {
                // ── FAIL-CLOSED FALLBACK (2026-07-19 wipe fix) ────────────────────
                // The on-disk read did NOT succeed (UriNotReadable / ParseFailure /
                // blank file / IoFailure). This is EXACTLY the dangerous window: a
                // Syncthing partial write makes the file momentarily unreadable, and
                // previously the guard "failed open" and let a near-empty payload
                // clobber a healthy DB. We now refuse to write a shrinking payload
                // whenever we cannot positively confirm what is on disk, using the
                // high-water mark of the largest DB we've seen this process as the
                // baseline. A genuinely large write (e.g. real user data) still goes
                // through; only suspiciously small writes are blocked.
                val baseline = highWaterEntryCount()
                if (baseline > MIN_RESTORE_BASELINE && newEntryCount * 2 < baseline) {
                    Log.e(
                        TAG,
                        "saveDatabase: ANTI-SHRINKAGE GUARD TRIPPED (on-disk unreadable: " +
                                "$onDiskResult). Refusing to overwrite with only $newEntryCount " +
                                "entries when high-water mark is $baseline. BLOCKED to prevent " +
                                "the transient-read-failure wipe."
                    )
                    return@withContext
                }
                Log.w(
                    TAG,
                    "saveDatabase: on-disk read not Success ($onDiskResult) but new payload " +
                            "($newEntryCount entries vs high-water $baseline) is not a shrink; proceeding."
                )
            }

            // ── Durable write ─────────────────────────────────────────────────
            // Use the plain "wt" (write+truncate) mode — the same mode the app has
            // always used and the only one guaranteed to be supported by every SAF
            // provider (some, e.g. Samsung's, reject the "wts" sync-mode string with
            // an exception, which previously caused writes to be silently dropped
            // and increments to "disappear"). We still flush explicitly, and if the
            // provider hands us a real FileOutputStream we fsync its descriptor so
            // the bytes reach stable storage before we return.
            val wrote = writeJsonToUri(uri, context, json)

            // Snapshot the newly-written state only after a confirmed write.
            if (wrote) {
                snapshotManager(context).snapshot(db, reason = "post-save")

                // Points-driven wallpaper: recompute after every successful
                // save so the wallpaper tracks the day's points as they
                // accrue. No-op when the feature is disabled or the resolved
                // image hasn't changed. Never allowed to break the save.
                try {
                    com.example.tail.wallpaper.WallpaperRefresher.onDatabaseSaved(context, db)
                } catch (e: Exception) {
                    Log.w(TAG, "post-save wallpaper refresh failed: ${e.message}")
                }
            }
        }

    /**
     * Writes [json] to [uri] using SAF "wt" mode. Flushes and, when possible,
     * fsyncs the underlying file descriptor. Returns true only on a confirmed,
     * exception-free write. Never throws.
     */
    private fun writeJsonToUri(uri: Uri, context: Context, json: String): Boolean {
        val cr = context.contentResolver
        return try {
            val stream = cr.openOutputStream(uri, "wt") ?: return false
            stream.use { out ->
                out.bufferedWriter().use { w ->
                    w.write(json)
                    w.flush()
                }
                // Best-effort durability: fsync the fd if this is a plain file stream.
                if (out is java.io.FileOutputStream) {
                    try { out.fd.sync() } catch (_: Exception) {}
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "writeJsonToUri: write failed: ${e.message}", e)
            false
        }
    }

    // Lazily-created, process-wide snapshot store. Uses the application context
    // so it's safe to build from any call site (widgets, receivers, services).
    private fun snapshotManager(context: Context): HabitsSnapshotManager {
        val existing = snapshotManagerRef
        if (existing != null) return existing
        val created = HabitsSnapshotManager(context.applicationContext)
        snapshotManagerRef = created
        return created
    }

    /** Public accessor so the Settings UI can list/restore snapshots. */
    fun snapshots(context: Context): HabitsSnapshotManager = snapshotManager(context)

    /**
     * DELIBERATE restore: writes [db] to [uri] unconditionally, bypassing the
     * anti-shrinkage guard (the user is intentionally rolling back, which may
     * legitimately shrink the file). The current on-disk state is still
     * snapshotted first so an accidental restore is itself recoverable.
     *
     * Returns true on a confirmed write.
     */
    suspend fun restoreDatabaseRaw(uri: Uri, context: Context, db: HabitsDatabase): Boolean =
        withContext(Dispatchers.IO) {
            val json = prettyGson.toJson(db)
            // Snapshot whatever is currently on disk before we clobber it.
            try {
                when (val r = loadDatabaseResult(uri, context)) {
                    is HabitsLoadResult.Success -> snapshotManager(context).snapshot(r.db, reason = "pre-restore")
                    else -> {}
                }
            } catch (_: Exception) {}

            val ok = writeJsonToUri(uri, context, json)
            if (ok) snapshotManager(context).snapshot(db, reason = "post-restore")
            ok
        }

    /**
     * Ensures every habit has an entry for every date from the latest recorded date
     * up to and including [today]. Missing dates are added with value 0.
     * Iterates ALL habits present in the DB so that habits added outside the canonical
     * list also get their missing days filled in.
     * Returns the updated database (and saves it if any dates were added).
     *
     * SAFETY: If the load fails (URI not readable, parse error, blank file), this
     * function ABORTS without writing anything and returns an empty map. This
     * prevents the catastrophic "load returned empty → save skeleton on top of
     * good data" failure mode that occurred during a Syncthing partial write.
     */
    suspend fun ensureDaysExist(
        uri: Uri,
        context: Context,
        today: LocalDate = LocalDate.now()
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "ensureDaysExist: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        val todayStr = dateString(today)
        var anyAdded = false

        val allHabitNames = db.keys.toSet() + HABIT_ORDER
        for (name in allHabitNames) {
            val entries = db[name]?.toMutableMap() ?: mutableMapOf()

            val latestExisting = entries.keys.maxOrNull()

            if (latestExisting == null) {
                entries[todayStr] = 0
                anyAdded = true
            } else if (latestExisting < todayStr) {
                var cursor = parseDate(latestExisting)?.plusDays(1) ?: today
                while (!cursor.isAfter(today)) {
                    val ds = dateString(cursor)
                    if (!entries.containsKey(ds)) {
                        entries[ds] = 0
                        anyAdded = true
                    }
                    cursor = cursor.plusDays(1)
                }
            }

            db[name] = entries.toSortedMap()
        }

        if (anyAdded) {
            saveDatabase(uri, context, db)
        }
        db
    }

    /**
     * Result of an auto-restore attempt, for logging / UI surfacing.
     */
    sealed class AutoRestoreResult {
        /** On-disk DB was healthy; nothing to do. [db] is the on-disk data. */
        data class Healthy(val db: HabitsDatabase, val entryCount: Int) : AutoRestoreResult()

        /** A catastrophic loss was detected and repaired from a snapshot. */
        data class Restored(
            val db: HabitsDatabase,
            val onDiskEntryCount: Int,
            val restoredEntryCount: Int,
            val snapshotName: String
        ) : AutoRestoreResult()

        /** Loss suspected but no healthy snapshot was available to restore from. */
        data class Unrecoverable(val onDiskEntryCount: Int) : AutoRestoreResult()
    }

    /**
     * AUTOMATIC RESTORE-ON-CATASTROPHIC-LOSS.
     *
     * Loads the on-disk DB and compares its entry count against the newest
     * HEALTHY snapshot in private storage. If the on-disk DB has catastrophically
     * fewer entries than the best snapshot (or is entirely unreadable/blank while
     * a healthy snapshot exists), the snapshot is written back to [uri]
     * automatically via [restoreDatabaseRaw] (which also snapshots the corrupt
     * state first, so the auto-restore is itself reversible).
     *
     * This is the top-level defence for requirement 3: a wipe from ANY cause is
     * self-healed on the next load before the user ever sees empty data.
     *
     * Heuristic: a snapshot with more than [MIN_RESTORE_BASELINE] entries is a
     * trustworthy baseline; the on-disk DB is "catastrophically small" when twice
     * its entry count still does not reach that baseline.
     */
    suspend fun loadWithAutoRestore(uri: Uri, context: Context): AutoRestoreResult =
        withContext(Dispatchers.IO) {
            val loadResult = loadDatabaseResult(uri, context)
            val onDiskDb: HabitsDatabase? =
                if (loadResult is HabitsLoadResult.Success) loadResult.db else null
            val onDiskCount = onDiskDb?.values?.sumOf { it.size } ?: 0

            // Find the best (largest) recent healthy snapshot to compare against.
            val mgr = snapshotManager(context)
            val snapshots = mgr.listSnapshots()
            var bestCount = 0
            var bestName = ""
            // Scan the newest handful; snapshots are content-addressed and GFS-pruned
            // so the healthiest large one is almost always among the most recent.
            for (info in snapshots.take(12)) {
                val cnt = mgr.entryCountOf(info.file)
                if (cnt > bestCount) {
                    bestCount = cnt
                    bestName = info.file.name
                }
            }

            val baselineTrustworthy = bestCount > MIN_RESTORE_BASELINE
            val catastrophic = baselineTrustworthy && (onDiskCount * 2 < bestCount)

            if (!catastrophic) {
                // Healthy, or no trustworthy baseline to justify a scary rollback.
                return@withContext AutoRestoreResult.Healthy(onDiskDb ?: emptyMap(), onDiskCount)
            }

            // Parse the winning snapshot's full contents now that a repair is warranted.
            val winner = snapshots.firstOrNull { it.file.name == bestName }
            val restoreDb = winner?.let { mgr.readSnapshot(it.file) }
            if (restoreDb == null || restoreDb.values.sumOf { it.size } < MIN_RESTORE_BASELINE) {
                Log.e(
                    TAG,
                    "loadWithAutoRestore: CATASTROPHIC LOSS detected (on-disk=" + onDiskCount +
                            ", baseline=" + bestCount + ") but NO usable snapshot to restore from!"
                )
                return@withContext AutoRestoreResult.Unrecoverable(onDiskCount)
            }

            val restoredCount = restoreDb.values.sumOf { it.size }
            Log.e(
                TAG,
                "loadWithAutoRestore: CATASTROPHIC LOSS detected (on-disk=" + onDiskCount +
                        " entries, best snapshot=" + restoredCount + " entries '" + bestName +
                        "'). AUTO-RESTORING from snapshot."
            )
            val ok = restoreDatabaseRaw(uri, context, restoreDb)
            if (!ok) {
                Log.e(TAG, "loadWithAutoRestore: restore write FAILED; returning snapshot in-memory anyway.")
            } else {
                recordGoodEntryCount(restoredCount)
            }
            AutoRestoreResult.Restored(restoreDb, onDiskCount, restoredCount, bestName)
        }

    /**
     * Applies an increment to [db] in memory only — no disk I/O.
     * Returns the updated database. Safe to call on the main thread.
     */
    fun applyIncrementToDb(
        db: HabitsDatabase,
        habitName: String,
        amount: Int,
        date: LocalDate
    ): HabitsDatabase {
        val dateStr = dateString(date)
        val mutable = db.toMutableMap()
        val habitEntries = mutable[habitName]?.toMutableMap() ?: mutableMapOf()
        habitEntries[dateStr] = (habitEntries[dateStr] ?: 0) + amount
        mutable[habitName] = habitEntries.toSortedMap()
        return mutable
    }

    /**
     * Writes [db] to disk at [uri]. Call this after an optimistic in-memory update.
     */
    suspend fun persistDatabase(uri: Uri, context: Context, db: HabitsDatabase) =
        saveDatabase(uri, context, db)

    /**
     * Increments the count for a habit on [date] by [amount], then saves.
     * Performs atomic read-modify-write (reads from disk first).
     *
     * SAFETY: If the load fails for any reason, this method LOGS and returns
     * the empty map WITHOUT writing — so a transient SAF error during a habit
     * tap never wipes out the file. Prefer [applyIncrementToDb] + [persistDatabase]
     * for optimistic UI updates that are guaranteed not to lose data.
     */
    suspend fun incrementHabitForDate(
        uri: Uri,
        context: Context,
        habitName: String,
        amount: Int,
        date: LocalDate
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "incrementHabitForDate: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        val dateStr = dateString(date)

        val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
        val current = habitEntries[dateStr] ?: 0
        habitEntries[dateStr] = current + amount

        db[habitName] = habitEntries.toSortedMap()

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Increments today's count for a habit by [amount], then saves.
     */
    suspend fun incrementHabit(
        uri: Uri,
        context: Context,
        habitName: String,
        amount: Int
    ): HabitsDatabase = incrementHabitForDate(uri, context, habitName, amount, LocalDate.now())

    /**
     * Atomically increments BOTH value slots of a timer-tracked habit for
     * today in a single read-modify-write: +[sessions] in the habit's own
     * slot and +[minutes] in its first-class minutes slot
     * (`minutes:<habitName>`).
     *
     * One atomic write instead of two back-to-back [incrementHabit] calls
     * matters: a concurrent reader/writer (e.g. the app's startup
     * ensure-days fill) can otherwise interleave BETWEEN the two writes,
     * load the half-updated state, and later persist it — silently losing
     * the session increment while keeping the minutes.
     *
     * Returns the saved database (useful for reading back the new day totals).
     */
    suspend fun incrementHabitWithMinutes(
        uri: Uri,
        context: Context,
        habitName: String,
        minutes: Int,
        sessions: Int
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "incrementHabitWithMinutes: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        val dateStr = dateString(LocalDate.now())

        val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
        habitEntries[dateStr] = (habitEntries[dateStr] ?: 0) + sessions
        db[habitName] = habitEntries.toSortedMap()

        val minKey = minutesKey(habitName)
        val minEntries = db[minKey]?.toMutableMap() ?: mutableMapOf()
        minEntries[dateStr] = (minEntries[dateStr] ?: 0) + minutes
        db[minKey] = minEntries.toSortedMap()

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Applies a SIGNED [deltaMinutes] adjustment to the habit's first-class
     * `minutes:<habitName>` slot for TODAY, leaving the primary session count
     * untouched. Used by the Protocol v3 minutes-only broadcast (Wags duration
     * corrections: the user shortens a just-completed session, so minutes must
     * be subtracted without un-counting the session).
     *
     * The resulting day total is clamped at zero — an over-correction can
     * never produce negative minutes. No-op for a zero delta.
     */
    suspend fun adjustHabitMinutesSlot(
        uri: Uri,
        context: Context,
        habitName: String,
        deltaMinutes: Int
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        if (deltaMinutes == 0) return@withContext loadDatabase(uri, context)
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "adjustHabitMinutesSlot: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        val dateStr = dateString(LocalDate.now())
        val minKey = minutesKey(habitName)
        val minEntries = db[minKey]?.toMutableMap() ?: mutableMapOf()
        val current = minEntries[dateStr] ?: 0
        val updated = (current + deltaMinutes).coerceAtLeast(0)
        if (updated != current) {
            if (updated == 0) minEntries.remove(dateStr) else minEntries[dateStr] = updated
            if (minEntries.isEmpty()) db.remove(minKey) else db[minKey] = minEntries.toSortedMap()
            saveDatabase(uri, context, db)
        }
        db
    }

    /**
     * Atomically increments MULTIPLE storage keys for today in a single
     * read-modify-write cycle: +[amount] for each entry of [increments]
     * (storage key → amount). Keys with an amount ≤ 0 are skipped.
     *
     * Used by the JugCoach integration receiver, which feeds the habit's own
     * count plus its six secondary-value slots (juggling time / catches totals
     * and their catch-ended / drop-ended breakdowns) from ONE broadcast. Doing
     * this as one atomic write instead of seven back-to-back [incrementHabit]
     * calls prevents concurrent readers/writers from interleaving between the
     * writes, loading half-updated state and later persisting it — silently
     * losing some of the increments (same rationale as
     * [incrementHabitWithMinutes]).
     */
    suspend fun incrementHabitSlots(
        uri: Uri,
        context: Context,
        increments: Map<String, Int>
    ): HabitsDatabase = incrementHabitSlotsForDate(uri, context, increments, LocalDate.now())

    /**
     * Date-aware variant of [incrementHabitSlots]: applies all increments to
     * [date] instead of today. Used by the PC-widget event queue processor,
     * which may apply events that were queued on the PC while the phone was
     * offline (possibly on a previous day).
     */
    suspend fun incrementHabitSlotsForDate(
        uri: Uri,
        context: Context,
        increments: Map<String, Int>,
        date: LocalDate
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val positive = increments.filterValues { it > 0 }
        if (positive.isEmpty()) return@withContext loadDatabase(uri, context)

        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "incrementHabitSlots: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        val dateStr = dateString(date)

        for ((key, amount) in positive) {
            val entries = db[key]?.toMutableMap() ?: mutableMapOf()
            entries[dateStr] = (entries[dateStr] ?: 0) + amount
            db[key] = entries.toSortedMap()
        }

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Atomically saves the weight/reps SLOTS of one weights-habit log entry
     * for [date] in a single read-modify-write cycle (same anti-interleaving
     * rationale as [incrementHabitSlotsForDate]):
     *
     * - the category's WEIGHT slot (grams) keeps the day's MAXIMUM — logging
     *   "60 kg × 8" twice, or after "50 kg", still shows 60 kg for the day,
     * - the category's REPS slot adds [reps] (total reps of the day).
     *
     * Machine weights live in `secondary_value:` / `secondary_value2:`,
     * free weights in `secondary_value3:` / `secondary_value4:`.
     *
     * The habit's own count (+1 per logged entry) is NOT touched here — the
     * caller routes it through the regular increment path so timestamps,
     * broadcasts and conditional feeds all fire (see HabitViewModel.saveWeightsEntry).
     */
    suspend fun saveWeightsSlotsForDate(
        uri: Uri,
        context: Context,
        habitName: String,
        weightGrams: Int,
        reps: Int,
        machine: Boolean,
        date: LocalDate
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        if (weightGrams <= 0 && reps <= 0) return@withContext loadDatabase(uri, context)

        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "saveWeightsSlots: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        val dateStr = dateString(date)

        // Weight slot: keep the day's heaviest value (max-merge)
        if (weightGrams > 0) {
            val weightKey = if (machine) secondaryValueKey(habitName)
            else secondaryValueSlotKey(habitName, 3)
            val entries = db[weightKey]?.toMutableMap() ?: mutableMapOf()
            entries[dateStr] = maxOf(entries[dateStr] ?: 0, weightGrams)
            db[weightKey] = entries.toSortedMap()
        }

        // Reps slot: accumulate the day's total
        if (reps > 0) {
            val repsKey = if (machine) secondaryValueSlotKey(habitName, 2)
            else secondaryValueSlotKey(habitName, 4)
            val entries = db[repsKey]?.toMutableMap() ?: mutableMapOf()
            entries[dateStr] = (entries[dateStr] ?: 0) + reps
            db[repsKey] = entries.toSortedMap()
        }

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Signed variant of [incrementHabitSlotsForDate] for PC-widget history
     * corrections: NEGATIVE deltas remove, positive ones add, and every
     * value clamps at zero (a correction must be able to undo an earlier
     * increment, but never drive a slot negative). Keys whose value lands
     * on zero drop out entirely, exactly like [adjustHabitMinutesSlot].
     */
    suspend fun adjustHabitSlotsForDate(
        uri: Uri,
        context: Context,
        deltas: Map<String, Int>,
        date: LocalDate
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        if (deltas.values.all { it == 0 }) return@withContext loadDatabase(uri, context)

        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "adjustHabitSlots: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        val dateStr = dateString(date)

        for ((key, amount) in deltas) {
            if (amount == 0) continue
            val entries = db[key]?.toMutableMap() ?: mutableMapOf()
            val updated = ((entries[dateStr] ?: 0) + amount).coerceAtLeast(0)
            if (updated == 0) entries.remove(dateStr) else entries[dateStr] = updated
            if (entries.isEmpty()) db.remove(key) else db[key] = entries.toSortedMap()
        }

        saveDatabase(uri, context, db)
        db
    }

    /**
     * **Protocol v2** — SETS (replaces) the stored value for a habit on [date]
     * to [value], then saves. Unlike [incrementHabitForDate] which adds, this
     * method overwrites whatever value was previously stored for that date.
     *
     * This makes the operation **idempotent**: setting the same date to the same
     * value multiple times produces the same result. Used by the retroactive
     * backfill broadcast ([HabitValueSetReceiver]) to replace old "1" values
     * with actual minute totals.
     *
     * SAFETY: If the load fails for any reason, this method throws
     * [HabitsLoadFailedException] WITHOUT writing — so a transient SAF error
     * never wipes out the file.
     */
    suspend fun setHabitValueForDate(
        uri: Uri,
        context: Context,
        habitName: String,
        value: Int,
        date: LocalDate
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "setHabitValueForDate: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        val dateStr = dateString(date)

        val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
        habitEntries[dateStr] = value  // SET, not add

        db[habitName] = habitEntries.toSortedMap()

        saveDatabase(uri, context, db)
        db
    }

    /**
     * **Protocol v2** — SETS (replaces) the stored values for a habit across
     * multiple dates in a **single** read-modify-write cycle.
     *
     * This is the batch version of [setHabitValueForDate], designed for the
     * retroactive backfill where WAGS sends many dates at once. Using a single
     * load+save avoids the lost-update race condition that would occur if two
     * concurrent broadcasts (e.g. resonance + meditation) interleaved their
     * per-date read-modify-write cycles.
     *
     * SAFETY: If the load fails, throws [HabitsLoadFailedException] WITHOUT writing.
     */
    suspend fun setHabitValuesForDates(
        uri: Uri,
        context: Context,
        habitName: String,
        dateValues: Map<LocalDate, Int>
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "setHabitValuesForDates: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()

        val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
        for ((date, value) in dateValues) {
            if (value <= 0) {
                // 0 = clear: remove the date's entry entirely (Skin Tracker
                // sends 0 for days whose photos were all deleted).
                habitEntries.remove(dateString(date))
            } else {
                habitEntries[dateString(date)] = value  // SET, not add
            }
        }
        db[habitName] = habitEntries.toSortedMap()

        saveDatabase(uri, context, db)
        db
    }

    /**
     * **SET (not increment)** absolute values for multiple keys at once, each
     * key carrying its own date→value map, in ONE atomic read-modify-write.
     * Used by the JugCoach history backfill so re-running it is idempotent.
     *
     * Keys with an empty date map are ignored.
     *
     * SAFETY: If the load fails, throws [HabitsLoadFailedException] WITHOUT writing.
     */
    suspend fun setHabitSlotsForDates(
        uri: Uri,
        context: Context,
        slotValues: Map<String, Map<LocalDate, Int>>
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "setHabitSlotsForDates: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()

        for ((key, dateValues) in slotValues) {
            if (dateValues.isEmpty()) continue
            val entries = db[key]?.toMutableMap() ?: mutableMapOf()
            for ((date, value) in dateValues) {
                entries[dateString(date)] = value  // SET, not add
            }
            db[key] = entries.toSortedMap()
        }

        saveDatabase(uri, context, db)
        db
    }

    /**
     * **Inverts** all stored values for a habit: 0 → 1, any value ≥ 1 → 0.
     *
     * This is a binary flip intended for habits whose data is purely 0/1.
     * Values greater than 1 (2, 3, …) will collapse to 0 — the caller MUST
     * warn the user about this data loss before invoking.
     *
     * SAFETY: If the load fails, throws [HabitsLoadFailedException] WITHOUT writing.
     */
    suspend fun invertHabit(
        uri: Uri,
        context: Context,
        habitName: String
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "invertHabit: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()

        val habitEntries = db[habitName]?.toMutableMap() ?: mutableMapOf()
        for ((date, value) in habitEntries.toList()) {
            habitEntries[date] = if (value == 0) 1 else 0
        }
        db[habitName] = habitEntries.toSortedMap()

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Adds a new habit to the JSON database file.
     * Reads the file, adds the habit with today's date = 0 if not already present, then saves.
     *
     * SAFETY: Each URI is loaded with the failure-aware path; URIs that fail
     * to load are skipped so we never write a skeleton over a working DB.
     */
    suspend fun addHabitToFiles(
        uris: List<Uri>,
        context: Context,
        habitName: String,
        today: LocalDate = LocalDate.now()
    ) = withContext(Dispatchers.IO) {
        val todayStr = dateString(today)
        for (uri in uris) {
            try {
                val loadResult = loadDatabaseResult(uri, context)
                if (loadResult !is HabitsLoadResult.Success) {
                    Log.w(TAG, "addHabitToFiles: skipping $uri because load did not succeed ($loadResult)")
                    continue
                }
                val db = loadResult.db.toMutableMap()
                if (!db.containsKey(habitName)) {
                    db[habitName] = sortedMapOf(todayStr to 0)
                    saveDatabase(uri, context, db)
                }
            } catch (e: Exception) {
                // Best-effort: skip files that can't be written
                Log.w(TAG, "addHabitToFiles: skipping $uri after exception: ${e.message}")
            }
        }
    }

    /**
     * Renames a habit in the database.
     * Reads the file, creates a new entry with the new name, copies all data from the old name,
     * removes the old entry, then saves.
     *
     * SAFETY: If the load fails for any reason, this method LOGS and returns
     * the empty map WITHOUT writing — so a transient SAF error never wipes out the file.
     */
    suspend fun renameHabit(
        uri: Uri,
        context: Context,
        oldName: String,
        newName: String
    ): HabitsDatabase = withContext(Dispatchers.IO) {
        val loadResult = loadDatabaseResult(uri, context)
        if (loadResult !is HabitsLoadResult.Success) {
            Log.w(TAG, "renameHabit: load did not succeed ($loadResult), refusing to save and throwing")
            throw HabitsLoadFailedException(loadResult)
        }
        val db = loadResult.db.toMutableMap()
        
        // Check if old name exists
        if (!db.containsKey(oldName)) {
            Log.w(TAG, "renameHabit: old name '$oldName' not found in database")
            throw IllegalArgumentException("Habit '$oldName' not found in database")
        }
        
        // Check if new name already exists
        if (db.containsKey(newName)) {
            Log.w(TAG, "renameHabit: new name '$newName' already exists in database")
            throw IllegalArgumentException("Habit '$newName' already exists in database")
        }
        
        // Copy data from old name to new name
        val entries = db[oldName] ?: emptyMap()
        db[newName] = entries.toSortedMap()
        
        // Remove old name
        db.remove(oldName)

        // Also rename secondary-value entries if they exist (all slots,
        // including the JugCoach-fed numbered slots 3–6)
        for (prefix in SECONDARY_VALUE_SLOT_PREFIXES) {
            val oldKey = prefix + oldName
            if (db.containsKey(oldKey)) {
                db[prefix + newName] = db[oldKey]!!
                db.remove(oldKey)
            }
        }

        // Also rename the first-class minutes slot (`minutes:<habit>`)
        val oldMinutesKey = minutesKey(oldName)
        if (db.containsKey(oldMinutesKey)) {
            db[minutesKey(newName)] = db[oldMinutesKey]!!
            db.remove(oldMinutesKey)
        }

        saveDatabase(uri, context, db)
        db
    }

    /**
     * Builds the display [Habit] list from raw database + settings for a specific [targetDate].
     * Uses the full unified habitsdb.txt — no merging with separate historical DB needed.
     */
    fun buildHabitList(
        db: HabitsDatabase,
        settings: AppSettings,
        targetDate: LocalDate = LocalDate.now()
    ): List<Habit> {
        val order = if (settings.habitOrder.isNotEmpty()) settings.habitOrder else HABIT_ORDER
        return order.map { name ->
            val entries = db[name] ?: emptyMap()
            val minutesPrimary = name in settings.widgetTimerMinutesPrimary
            val useFallback = name in settings.secondaryValueFallbackHabits || minutesPrimary
            // Minutes-primary: the minutes slot drives points (sessions are the
            // zero-minutes fallback). Sessions-primary fallback: the legacy
            // generic secondary slot when the habit uses it or has data there
            // (e.g. Meditations/Apnea/Resonance sessions, chess.com games,
            // JugCoach seconds), otherwise the minutes slot.
            val fallbackEntries = when {
                minutesPrimary -> db[minutesKey(name)] ?: emptyMap()
                useFallback -> db[fallbackSlotKey(name, settings.secondaryValueHabits, db)]
                    ?: emptyMap()
                else -> emptyMap()
            }
            buildHabit(
                name = name,
                entries = entries,
                useCustomInput = name in settings.customInputHabits,
                divider = settings.habitDividers[name] ?: 1,
                targetDate = targetDate,
                secondaryEntries = fallbackEntries,
                useSecondaryFallback = useFallback,
                swapPrimarySecondary = minutesPrimary,
                invertedBinary = name in settings.invertedBinaryHabits
            )
        }
    }
}
