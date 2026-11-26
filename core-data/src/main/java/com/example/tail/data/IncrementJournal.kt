package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "IncrementJournal"

/**
 * ONE increment that still has to reach habitsdb.txt.
 *
 * Written to disk BEFORE the habits file is touched (write-ahead) and
 * removed only after the resulting state was VERIFIED on disk. Whatever
 * interrupts the window in between — a SAF write that returns without
 * landing, the anti-shrinkage guard blocking, the process being killed,
 * a Syncthing conflict copy replacing the file — the increment is still
 * here and is re-applied by [IncrementJournal.replay].
 *
 * Semantics are ADDITIVE and IDEMPOTENT on purpose: an entry counts as
 * satisfied as soon as the stored value has reached the value it was
 * building toward, so replaying it again is a no-op (see
 * [IncrementJournal.satisfied]). That is what makes it safe to journal on
 * EVERY increment path, including ones that already verify their writes.
 */
data class PendingIncrement(
    /** Storage key in the habits DB: the habit name, or a slot key (`minutes:…`, `secondary_value:…`). */
    val key: String,
    /** Signed amount to add to [key] on [date]. */
    val amount: Int,
    /** ISO date the increment applies to. */
    val date: String,
    /** Millis at which the event HAPPENED (stamps on replay use this, not replay time). */
    val eventAt: Long,
    /**
     * The value the slot must REACH for this entry to count as applied.
     * `null` for plain additive increments (target = current + amount at
     * apply time). Set by writers that computed the increment against a
     * known "before" value (`before + amount`) so replay is idempotent even
     * when the original write DID land but its confirmation was lost.
     */
    val target: Int? = null,
    /**
     * Per-unit clock stamps ("HH:mm:ss") — exactly what the timestamp store
     * would have received had the write succeeded immediately; one per
     * stored unit. Only consumed on replay by a path that could not record
     * its stamps itself ([stampsHandledByCaller] = false).
     */
    val stamps: List<String> = emptyList(),
    /** True when the writing path records its own timestamps after a verified write. */
    val stampsHandledByCaller: Boolean = false
)

/** Result of a [IncrementJournal.replay] pass. */
data class ReplayResult(
    /** True when every journaled increment is now reflected on disk. */
    val allApplied: Boolean,
    /** Number of entries that were applied by THIS pass (0 = nothing was missing). */
    val appliedCount: Int,
    /** Storage keys touched by the entries applied in THIS pass (for UI refresh events). */
    val appliedKeys: Set<String> = emptySet(),
    /** Storage keys whose replay had to be deferred (write failed — entries kept). */
    val deferredKeys: Set<String>
) {
    companion object {
        val NOTHING = ReplayResult(allApplied = true, appliedCount = 0, deferredKeys = emptySet())
    }
}

/**
 * Write-ahead journal for habit increments.
 *
 * WHY THIS EXISTS — the "timestamp but no point" bug:
 * The timestamp store is internal, append-only and never synced, while the
 * count lives in a multi-MB SAF file that is rewritten in full on every
 * change. Every path recorded the stamp unconditionally, but the count write
 * could silently not land: `saveDatabase` returned Unit and swallowed both
 * SAF failures and the anti-shrinkage guard, the persist-verify re-read the
 * identity-cached graph it had just published (so it confirmed its own
 * in-memory state, not the disk), and background syncs persisted unlocked
 * whole-file snapshots that reverted increments landing meanwhile. The
 * result was exactly what was observed: a stamp with no point, on rapid
 * taps and on widget/bubble-driven increments alike.
 *
 * The journal closes that class of failure structurally instead of patching
 * each writer: an increment is durable BEFORE the DB write, and is only
 * forgotten once a FRESH disk read proves it is in the file. Anything left
 * behind is re-applied on the next increment or process start.
 */
object IncrementJournal {

    private const val FILE_NAME = "pending_increments.json"
    private const val MAX_ENTRIES = 2000

    /** Bumped after every change to the journal contents. */
    val version = MutableStateFlow(0L)

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<PendingIncrement>>() {}.type

    /** Process-wide mutex: every caller uses its own repository, this must still serialise. */
    private val mutex = Mutex()

    /** Parsed journal, keyed by the file's (mtime, length) so reads are free. */
    private class Snapshot(val stamps: Pair<Long, Long>, val entries: List<PendingIncrement>)

    @Volatile
    private var cache: Snapshot? = null

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, FILE_NAME)

    private fun stamps(f: File): Pair<Long, Long> =
        if (f.exists()) f.lastModified() to f.length() else 0L to 0L

    private fun read(f: File): Snapshot {
        val st = stamps(f)
        cache?.let { if (it.stamps == st) return it }
        val parsed: List<PendingIncrement> = try {
            if (!f.exists()) emptyList()
            else gson.fromJson(f.reader(), listType) ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "journal unreadable — starting empty (${e.javaClass.simpleName}: ${e.message})")
            emptyList()
        }
        // Drop corrupt rows rather than failing the whole journal.
        val valid = parsed.filter { it.amount != 0 && it.key.isNotBlank() && it.date.isNotBlank() }
        return Snapshot(st, valid).also { cache = it }
    }

    private fun write(f: File, entries: List<PendingIncrement>) {
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writer().use { w -> gson.toJson(entries, w) }
        if (!tmp.renameTo(f)) {
            // rename(2) is atomic; fall back only if a stale tmp blocks it.
            f.delete()
            if (!tmp.renameTo(f)) {
                tmp.delete()
                throw IOException("atomic rename failed for $FILE_NAME")
            }
        }
        cache = Snapshot(stamps(f), entries)
        version.value += 1
    }

    /**
     * Journals [inc] BEFORE the caller touches the habits file. Throws if the
     * entry cannot be made durable — the caller must then NOT proceed with an
     * unjournaled write (nothing is lost by aborting: the user sees an error
     * instead of a silently dropped increment).
     */
    suspend fun add(context: Context, inc: PendingIncrement) = addAll(context, listOf(inc))

    /** Journals several increments (one transaction) in one file write. */
    suspend fun addAll(context: Context, incs: List<PendingIncrement>) {
        if (incs.isEmpty()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val f = file(context)
                val list = read(f).entries
                // Bound the journal: oldest entries go first. Entries are
                // removed right after their verified write, so a journal
                // anywhere near this size means writes have been failing
                // for a long time — and the newest entries are the ones the
                // user just made and can still see on screen.
                write(f, (list + incs).takeLast(MAX_ENTRIES))
            }
        }
    }

    /** Current journaled increments (oldest first). */
    suspend fun snapshot(context: Context): List<PendingIncrement> =
        mutex.withLock {
            withContext(Dispatchers.IO) { read(file(context)).entries }
        }

    /**
     * True when [onDisk] (a FRESH read) already accounts for [inc].
     *
     * Additive semantics: the slot must have reached the value this entry
     * was building toward — `target` when the writer knew its "before"
     * value, otherwise simply `amount` (the entry is only ever applied on
     * top of a value that does not yet include it). Negative amounts mirror
     * the comparison.
     */
    fun satisfied(inc: PendingIncrement, onDisk: HabitsDatabase): Boolean {
        val stored = onDisk[inc.key]?.get(inc.date) ?: 0
        return if (inc.amount >= 0) stored >= (inc.target ?: inc.amount)
        else stored <= (inc.target ?: inc.amount)
    }

    /** Forgets [done] entries (their effects are confirmed on disk). */
    suspend fun remove(context: Context, done: List<PendingIncrement>) {
        if (done.isEmpty()) return
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val f = file(context)
                val list = read(f).entries
                val keep = list.filter { it !in done }
                if (keep.size != list.size) write(f, keep)
            }
        }
    }

    /**
     * Re-applies every journaled increment that is not yet on disk, in ONE
     * read-modify-write per attempt, under the habits-file mutex.
     *
     * Idempotent per entry: an entry is only added when [satisfied] is false
     * against the FRESH disk state, so a value that already reached the file
     * (by a retry of the original write, by a sibling path, or by the
     * Syncthing max-merge) is never counted twice. Entries whose slot could
     * not be brought to the journaled value stay in the journal.
     *
     * @param onReplayedStamp invoked for each entry applied by THIS pass that
     *        still carries stamps to record ([PendingIncrement.stampsHandledByCaller]
     *        = false). Runs after the verified write.
     */
    suspend fun replay(
        context: Context,
        repo: HabitsRepository,
        uri: Uri,
        onReplayedStamp: suspend (PendingIncrement) -> Unit = {}
    ): ReplayResult {
        val pending = snapshot(context)
        if (pending.isEmpty()) return ReplayResult.NOTHING
        return applyPending(context, repo, uri, pending, onReplayedStamp)
    }

    /**
     * Applies [entries] (a slice of the journal) to the habits file. Shared by
     * [replay] and by [HabitsRepository]'s verified RMW, which folds the
     * journal's older entries into the SAME write as the new increment — so a
     * lost increment is healed by the very next successful write of ANY
     * habit, with no extra file write.
     *
     * Must NOT be called while holding the habits-file mutex.
     */
    internal suspend fun applyPending(
        context: Context,
        repo: HabitsRepository,
        uri: Uri,
        entries: List<PendingIncrement>,
        onReplayedStamp: suspend (PendingIncrement) -> Unit
    ): ReplayResult {
        // `mutate` may run several times (retries); only the LAST attempt's
        // view counts, so the list is rebuilt inside the lambda.
        var applied: List<PendingIncrement> = emptyList()
        val outcome = repo.mutateHabitFileVerified(uri, context, label = "journal-replay") { disk ->
            val db = disk.toMutableMap()
            val touchedNow = ArrayList<PendingIncrement>()
            // Iterate against the FRESH disk state every time: an earlier
            // entry in this same batch may already satisfy a later one.
            for (inc in entries) {
                if (satisfied(inc, db)) continue
                val entriesForHabit = db[inc.key]?.toMutableMap() ?: mutableMapOf()
                val before = entriesForHabit[inc.date] ?: 0
                // Absolute target: the stored value must end up exactly where
                // the writer intended — never "current + amount" applied to a
                // value that already moved on (that would double-count on a
                // retry whose first write landed but was unconfirmed).
                val after = if (inc.amount >= 0) {
                    maxOf(before, inc.target ?: (before + inc.amount))
                } else {
                    if (inc.target != null) minOf(before, inc.target) else before + inc.amount
                }
                entriesForHabit[inc.date] = after
                db[inc.key] = entriesForHabit.toSortedMap()
                touchedNow += inc
            }
            applied = touchedNow
            if (touchedNow.isEmpty()) null else db
        }
        if (!outcome.wrote) {
            // Nothing confirmed on disk — keep every entry for the next pass.
            return ReplayResult(allApplied = false, appliedCount = 0, appliedKeys = emptySet(),
                deferredKeys = entries.map { it.key }.toSet())
        }
        // Verified on disk: forget every entry whose effect is now present
        // (those we just applied AND those already satisfied on disk).
        val confirmed = entries.filter { satisfied(it, outcome.onDisk) }
        remove(context, confirmed)
        if (applied.isNotEmpty()) {
            val stampTargets = applied.filter { !it.stampsHandledByCaller && it.stamps.isNotEmpty() }
            for (inc in stampTargets) {
                try {
                    onReplayedStamp(inc)
                } catch (e: Exception) {
                    Log.w(TAG, "replay stamp for '${inc.key}' failed: ${e.message}")
                }
            }
            Log.i(TAG, "replayed ${applied.size} increment(s): ${applied.groupBy { it.key }.mapValues { it.value.size }}")
        }
        val stillPending = entries.filter { !satisfied(it, outcome.onDisk) }
        return ReplayResult(
            allApplied = stillPending.isEmpty(),
            appliedCount = applied.size,
            appliedKeys = applied.map { it.key }.toSet(),
            deferredKeys = stillPending.map { it.key }.toSet()
        )
    }

    /** Forces the next read to re-parse (tests / restore). */
    fun invalidate() {
        cache = null
    }
}
