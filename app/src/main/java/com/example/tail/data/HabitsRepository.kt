package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.util.Log
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
                HabitsLoadResult.Success(parsed ?: emptyMap())
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
            // catastrophically shrinking it. Errors here fall through to "no
            // guard available" — we still write, because not being able to
            // check shouldn't block legitimate writes.
            val newEntryCount = db.values.sumOf { it.size }
            val onDisk: HabitsDatabase? = try {
                when (val r = loadDatabaseResult(uri, context)) {
                    is HabitsLoadResult.Success -> r.db
                    else -> null
                }
            } catch (_: Exception) { null }

            if (onDisk != null) {
                val onDiskEntryCount = onDisk.values.sumOf { it.size }
                // Tuning: only trigger the guard when the on-disk DB is non-trivial
                // (>50 entries) AND we'd be writing fewer than half as many entries.
                // This catches the full-wipe scenario (writing 0..76 entries on top
                // of thousands) without blocking legitimate small edits or first writes.
                if (onDiskEntryCount > 50 && newEntryCount * 2 < onDiskEntryCount) {
                    Log.e(
                        TAG,
                        "saveDatabase: ANTI-SHRINKAGE GUARD TRIPPED. Refusing to overwrite " +
                                "$onDiskEntryCount on-disk entries with only $newEntryCount new entries. " +
                                "This save has been BLOCKED to prevent data loss."
                    )
                    return@withContext
                }
            }

            val cr = context.contentResolver
            cr.openOutputStream(uri, "wt")?.use { stream ->
                stream.bufferedWriter().use { it.write(json) }
            }
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
            buildHabit(
                name = name,
                entries = entries,
                useCustomInput = name in settings.customInputHabits,
                divider = settings.habitDividers[name] ?: 1,
                targetDate = targetDate
            )
        }
    }
}
