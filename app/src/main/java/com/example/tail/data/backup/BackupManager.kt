package com.example.tail.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.tail.data.AdviceItem
import com.example.tail.data.AdviceRepository
import com.example.tail.data.AppSettings
import com.example.tail.data.HabitScreen
import com.example.tail.data.PointRange
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.data.TimedDataRepository
import com.example.tail.data.TimedEntry
import com.example.tail.data.debug.DebugPreferences
import com.example.tail.data.debug.NoteType
import com.example.tail.data.debug.SavedNote
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant

private const val TAG = "BackupManager"

/** Result wrapper for export/import operations. */
sealed class BackupResult {
    data class Success(val message: String) : BackupResult()
    data class Failure(val message: String, val cause: Throwable? = null) : BackupResult()
}

/**
 * Read-only preview of what a single-habit restore would do. Shown to the user
 * for confirmation BEFORE any data is overwritten. Only the requested habit is
 * affected — the rest of the backup file is ignored.
 */
data class HabitRestorePreview(
    /** Name of the habit being restored. */
    val habitName: String,
    /** ISO-8601 timestamp of when the backup file was originally written. */
    val backupExportedAt: String,
    /** Total increment count for this habit in the CURRENT database. */
    val currentTotal: Int,
    /** Total increment count for this habit in the BACKUP. */
    val backupTotal: Int,
    /** backupTotal - currentTotal. Positive = increments gained, negative = lost. */
    val incrementDelta: Int,
    /** Number of dated entries (days) for this habit in the backup. */
    val backupDayCount: Int,
    /** Latest date ("YYYY-MM-DD") present in the backup for this habit, or null. */
    val backupLastDate: String?,
    /** Latest date present in the current data for this habit, or null. */
    val currentLastDate: String?,
    /** Whether the backup contains subtype data for this habit. */
    val hasSubtypeData: Boolean,
    /** Whether the backup contains timed data for this habit. */
    val hasTimedData: Boolean,
    /** Whether the backup contains text-input log entries for this habit. */
    val hasTextInputData: Boolean,
    /** Whether the backup contains a dated-entry source file for this habit. */
    val hasDatedEntryData: Boolean
)

/**
 * Builds a complete [BackupBundle] in memory and writes it to / reads it from
 * a user-chosen SAF Uri.
 *
 * The bundle includes EVERYTHING the user can input or accumulate via the app:
 *   1. App settings (DataStore "tail_settings")
 *   2. Advice banner items (DataStore "tail_advice")
 *   3. Location data (SharedPrefs "tail_location_prefs")
 *   4. Debug-mode prefs + saved notes (SharedPrefs "tail_debug_prefs")
 *   5. Habits database (the habitsdb.txt JSON via the user's SAF URI)
 *   6. Habit timestamps (internal file `habit_timestamps.json`)
 *   7. AI-generated icons (internal dir `ai_icons/` — bytes embedded as base64)
 *   8. Per-habit external files (text-input logs, dated-entry sources,
 *      subtype JSONs, timed JSONs) — content embedded so the bundle is
 *      fully portable.
 *   9. Voice-note markdown content
 *  10. Meal logs + captured meal photos (internal `meal_logs/`, `meal_images/`)
 *  11. Vision-processing queue (`vision_queue.json`) so pending captures survive
 *  12. `debug_tail.json` (submitted debug-notes archive)
 *  13. Extra SharedPreferences stores with user data (chess readiness
 *      history/ATH, chess phase-2 audits/ACC history)
 *  14. EVERY AppSettings field (2026-08 audit: previously ~50 fields —
 *      sharable-text, secondary values, custom amounts, map stats, Garmin,
 *      GitHub, Bridge, OMDb, point ranges, graph metrics, habit notes,
 *      roll-forward, meal engine, app links, long-press actions, widget
 *      triggers, chess readiness, GDrive — were missing and are now included)
 *
 * Things deliberately NOT backed up (because they are derived / re-fetchable):
 *   - Screens-layout relay file (regenerated whenever screens change)
 *   - chess.com monthly cache (re-fetched from the API)
 *   - Garmin / GitHub API caches (re-fetched from the API)
 *   - IMDb rating cache (re-fetched from the API)
 *   - One-time migration flags (let them re-run on a fresh install)
 *   - Widget tap-state DataStore + active widget timers (transient UI state)
 *   - Habit DB snapshots (`habit_snapshots/` — internal recovery copies of the
 *     habits DB itself, which is already backed up in full)
 */
class BackupManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val adviceRepo: AdviceRepository,
    private val habitsRepo: HabitsRepository,
    private val textInputRepo: TextInputRepository,
    private val subtypeDataRepo: SubtypeDataRepository,
    private val timedDataRepo: TimedDataRepository,
    private val debugPrefs: DebugPreferences
) {
    private val gson = GsonBuilder()
        .serializeNulls()
        .setPrettyPrinting()
        .create()

    /** Suggested filename for new export files. */
    fun suggestedFileName(): String {
        val ts = Instant.now().toString().replace(':', '-').substringBefore('.')
        return "tail_backup_$ts.json"
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EXPORT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Builds a full backup bundle and writes it to [destUri] as pretty JSON.
     * Returns a [BackupResult] describing success / failure.
     */
    suspend fun exportBackup(destUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val json = buildBackupJson()

            val cr = context.contentResolver
            cr.openOutputStream(destUri, "wt")?.use { out ->
                out.bufferedWriter().use { it.write(json) }
            } ?: return@withContext BackupResult.Failure("Could not open output stream")

            val sizeKb = json.length / 1024
            BackupResult.Success("Exported backup (${sizeKb} KB)")
        } catch (e: Exception) {
            Log.e(TAG, "Export failed", e)
            BackupResult.Failure("Export failed: ${e.message}", e)
        }
    }

    /**
     * Builds the full backup bundle and returns it as a JSON string. Public so
     * alternative transports (e.g. Google Drive upload) can reuse the exact
     * same wire format as SAF file exports.
     */
    suspend fun buildBackupJson(): String = withContext(Dispatchers.IO) {
        gson.toJson(buildBundle())
    }

    /** Reads every data source and assembles a [BackupBundle] in memory. */
    private suspend fun buildBundle(): BackupBundle {
        val settings = settingsRepo.settingsFlow.first()
        val advice = adviceRepo.observeAll().first()

        val habitsDb = readHabitsDb(settings.fileUri)
        val habitTimestamps = readHabitTimestamps()

        val locations = readLocationsSection()
        val debug = readDebugSection()

        val perHabit = readPerHabitFiles(settings)
        val voiceNoteMd = readSafText(settings.voiceNoteFileUri)
        val aiIcons = readAiIconsSection()

        val meal = readMealSection()
        val visionQueue = readVisionQueueJson()
        val extraPrefs = readExtraPrefs()

        return BackupBundle(
            schemaVersion = BackupBundle.SCHEMA_VERSION,
            appVersion = appVersionLabel(),
            exportedAt = Instant.now().toString(),
            magic = BackupBundle.MAGIC,
            settings = toSettingsSection(settings),
            advice = advice.map { it.toBackup() },
            locations = locations,
            debug = debug,
            habitsDb = habitsDb,
            habitTimestamps = habitTimestamps,
            aiIcons = aiIcons,
            perHabitFiles = perHabit,
            voiceNoteMarkdown = voiceNoteMd,
            meal = meal,
            visionQueueJson = visionQueue,
            extraPrefs = extraPrefs
        )
    }

    private fun appVersionLabel(): String = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, 0)
        info.versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    // ─────────────────────────────────────────────────────────────────────
    //  EXPORT — per-source readers
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun readHabitsDb(fileUri: String): Map<String, Map<String, Int>> {
        if (fileUri.isBlank()) return emptyMap()
        return try {
            habitsRepo.loadDatabase(Uri.parse(fileUri), context)
        } catch (e: Exception) {
            Log.w(TAG, "habitsDb read failed: ${e.message}")
            emptyMap()
        }
    }

    private fun readHabitTimestamps(): Map<String, Map<String, List<String>>> {
        val file = File(context.filesDir, "habit_timestamps.json")
        if (!file.exists()) return emptyMap()
        return try {
            val text = file.readText()
            if (text.isBlank()) return emptyMap()
            val type = object : TypeToken<Map<String, Map<String, List<String>>>>() {}.type
            gson.fromJson<Map<String, Map<String, List<String>>>>(text, type) ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "habit_timestamps.json read failed: ${e.message}")
            emptyMap()
        }
    }

    private fun readLocationsSection(): LocationsSection {
        val prefs = context.getSharedPreferences("tail_location_prefs", Context.MODE_PRIVATE)
        val labels = readJsonObjectMap(prefs.getString("daily_locations", null))
        val coords = readJsonObjectMap(prefs.getString("daily_coords", null))
        val ignored = readJsonStringArray(prefs.getString("ignored_country_names", null))
        val seeded = prefs.getBoolean("ignored_country_names_seeded", false)
        return LocationsSection(
            labels = labels,
            coords = coords,
            ignoredCountries = ignored,
            ignoredCountriesSeeded = seeded
        )
    }

    private fun readDebugSection(): DebugSection {
        val savedNotes = debugPrefs.loadSavedNotes().map { sn ->
            DebugSavedNoteBackup(
                id = sn.id,
                timestamp = sn.timestamp,
                screenRoute = sn.screenRoute,
                screenLabel = sn.screenLabel,
                sourceFile = sn.sourceFile,
                sourceFunctions = sn.sourceFunctions,
                noteType = sn.noteType.name,
                noteText = sn.noteText
            )
        }
        return DebugSection(
            debugModeEnabled = debugPrefs.debugModeEnabled,
            debugFileDirUri = debugPrefs.debugFileDirUri,
            savedNotes = savedNotes,
            debugTailJson = readInternalFileText("debug_tail.json")
        )
    }

    /** Reads a text file from [context.filesDir], or null if missing/unreadable. */
    private fun readInternalFileText(name: String): String? {
        return try {
            val f = File(context.filesDir, name)
            if (f.exists()) f.readText() else null
        } catch (e: Exception) {
            Log.w(TAG, "$name read failed: ${e.message}")
            null
        }
    }

    /** Snapshots meal logs (raw JSON per file) and meal photos (base64). */
    private fun readMealSection(): MealSection {
        val logs = mutableMapOf<String, String>()
        val logsDir = File(context.filesDir, "meal_logs")
        if (logsDir.isDirectory) {
            logsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { f ->
                try {
                    val text = f.readText()
                    if (text.isNotBlank()) logs[f.name] = text
                } catch (e: Exception) {
                    Log.w(TAG, "meal log read failed for '${f.name}': ${e.message}")
                }
            }
        }

        val images = mutableMapOf<String, String>()
        val imagesDir = File(context.filesDir, "meal_images")
        if (imagesDir.isDirectory) {
            imagesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".jpg") }?.forEach { f ->
                try {
                    images[f.name] = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.w(TAG, "meal image read failed for '${f.name}': ${e.message}")
                }
            }
        }
        return MealSection(logs = logs, images = images)
    }

    /** Raw content of `files/vision_queue.json` (pending vision captures). */
    private fun readVisionQueueJson(): String? = readInternalFileText("vision_queue.json")

    /**
     * SharedPreferences files that hold user data outside AppSettings.
     * Each entry is stored with an explicit type tag so restore can use the
     * correct putX() overload.
     */
    private fun readExtraPrefs(): Map<String, List<PrefEntryBackup>> {
        val names = listOf("tail_chess_readiness", "tail_chess_phase2")
        val out = mutableMapOf<String, List<PrefEntryBackup>>()
        for (name in names) {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val entries = mutableListOf<PrefEntryBackup>()
            for ((key, value) in prefs.all) {
                val entry = when (value) {
                    is Boolean -> PrefEntryBackup(key = key, type = "bool", boolValue = value)
                    is Int -> PrefEntryBackup(key = key, type = "int", intValue = value.toLong())
                    is Long -> PrefEntryBackup(key = key, type = "long", intValue = value)
                    is Float -> PrefEntryBackup(key = key, type = "float", floatValue = value.toDouble())
                    is String -> PrefEntryBackup(key = key, type = "string", stringValue = value)
                    is Set<*> -> PrefEntryBackup(
                        key = key,
                        type = "stringset",
                        stringSetValue = value.filterIsInstance<String>()
                    )
                    else -> null
                }
                if (entry != null) entries.add(entry)
            }
            if (entries.isNotEmpty()) out[name] = entries
        }
        return out
    }

    private suspend fun readPerHabitFiles(settings: AppSettings): PerHabitFilesSection {
        // text-input logs — try external SAF first, fall back to internal backup
        val textInput = mutableMapOf<String, Map<String, String>>()
        for ((habit, uriStr) in settings.textInputFileUris) {
            if (uriStr.isBlank()) continue
            var loaded = false
            try {
                val map = textInputRepo.loadTextLog(Uri.parse(uriStr), context)
                if (map.isNotEmpty()) {
                    textInput[habit] = map
                    loaded = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "text-input read failed for '$habit': ${e.message}")
            }
            if (!loaded) {
                // Fall back to internal backup if external file is missing/empty
                val internal = textInputRepo.loadInternalBackup(context, habit)
                if (internal != null && internal.isNotEmpty()) {
                    textInput[habit] = internal
                    Log.i(TAG, "text-input: used internal backup for '$habit' (${internal.size} entries)")
                }
            }
        }

        // dated-entry source files (raw text)
        val datedEntry = mutableMapOf<String, String>()
        for ((habit, uriStr) in settings.datedEntryFileUris) {
            val text = readSafText(uriStr) ?: continue
            datedEntry[habit] = text
        }

        // subtype data (internal store)
        val subtype = mutableMapOf<String, Map<String, Map<String, Int>>>()
        try {
            subtypeDataRepo.loadAll().forEach { (habit, data) ->
                if (data.isNotEmpty()) subtype[habit] = data
            }
        } catch (e: Exception) {
            Log.w(TAG, "subtype-data read failed: ${e.message}")
        }

        // timed data (internal store)
        val timed = mutableMapOf<String, Map<String, Map<String, Any?>>>()
        try {
            timedDataRepo.loadAll().forEach { (habit, entries) ->
                if (entries.isNotEmpty()) {
                    timed[habit] = entries.mapValues { (_, entry) ->
                        mapOf<String, Any?>("subtype" to entry.subtype, "count" to entry.count)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "timed-data read failed: ${e.message}")
        }

        return PerHabitFilesSection(
            textInput = textInput,
            datedEntry = datedEntry,
            subtypeData = subtype,
            timedData = timed
        )
    }

    private fun readAiIconsSection(): AiIconsSection {
        val iconsDir = File(context.filesDir, "ai_icons")
        if (!iconsDir.exists()) return AiIconsSection()

        val indexFile = File(iconsDir, "ai_icons_index.json")
        val indexJson = if (indexFile.exists()) indexFile.readText() else "[]"
        val arr = try { JSONArray(indexJson) } catch (_: Exception) { JSONArray() }

        val index = mutableListOf<AiIconBackup>()
        val files = mutableMapOf<String, String>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isBlank()) continue
            index.add(
                AiIconBackup(
                    id = id,
                    prompt = obj.optString("prompt", ""),
                    createdAt = obj.optString("createdAt", "")
                )
            )
            val pngFile = File(iconsDir, "$id.png")
            if (pngFile.exists()) {
                try {
                    val bytes = pngFile.readBytes()
                    files[id] = Base64.encodeToString(bytes, Base64.NO_WRAP)
                } catch (e: Exception) {
                    Log.w(TAG, "AI icon read failed for '$id': ${e.message}")
                }
            }
        }
        return AiIconsSection(index = index, files = files)
    }

    private fun readSafText(uriStr: String): String? {
        if (uriStr.isBlank()) return null
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { stream ->
                stream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF text read failed for '$uriStr': ${e.message}")
            null
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  IMPORT
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reads a backup file from [srcUri] and applies it to every persistent
     * data source. Existing data is OVERWRITTEN — this is a full restore.
     *
     * SAF URIs from the original device are restored verbatim into
     * AppSettings, but the importing device may not have permission to open
     * them. The user can re-pick those files via the Settings screen if
     * needed; the textual content of each per-habit file has been embedded
     * inside the backup and is written back through the SAME URIs only when
     * they remain accessible (best-effort).
     */
    suspend fun importBackup(srcUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        try {
            val text = context.contentResolver.openInputStream(srcUri)?.use { stream ->
                stream.bufferedReader().readText()
            } ?: return@withContext BackupResult.Failure("Could not open input stream")

            if (text.isBlank()) return@withContext BackupResult.Failure("Backup file is empty")

            val bundle = try {
                gson.fromJson(text, BackupBundle::class.java)
            } catch (e: Exception) {
                return@withContext BackupResult.Failure(
                    "Backup file is not valid JSON: ${e.message}", e
                )
            }

            if (bundle.magic != BackupBundle.MAGIC) {
                return@withContext BackupResult.Failure(
                    "File is missing the Tail backup marker (magic=${bundle.magic})"
                )
            }
            if (bundle.schemaVersion > BackupBundle.SCHEMA_VERSION) {
                return@withContext BackupResult.Failure(
                    "Backup is from a newer version of Tail (schema ${bundle.schemaVersion} > " +
                            "${BackupBundle.SCHEMA_VERSION})"
                )
            }

            applyBundle(bundle)
            BackupResult.Success("Imported backup from ${bundle.exportedAt.ifBlank { "unknown date" }}")
        } catch (e: Exception) {
            Log.e(TAG, "Import failed", e)
            BackupResult.Failure("Import failed: ${e.message}", e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    //  SINGLE-HABIT RESTORE (from a backup file)
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Parses a backup file's raw text into a validated [BackupBundle].
     * Returns null (and logs) if the text is blank, not valid JSON, missing
     * the magic marker, or from a newer schema version.
     */
    private fun parseBackup(text: String): BackupBundle? {
        if (text.isBlank()) {
            Log.w(TAG, "parseBackup: text is blank")
            return null
        }
        val bundle = try {
            gson.fromJson(text, BackupBundle::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "parseBackup: JSON parse failed: ${e.message}")
            return null
        }
        if (bundle == null || bundle.magic != BackupBundle.MAGIC) {
            Log.w(TAG, "parseBackup: missing/invalid magic marker")
            return null
        }
        if (bundle.schemaVersion > BackupBundle.SCHEMA_VERSION) {
            Log.w(TAG, "parseBackup: schema ${bundle.schemaVersion} > current ${BackupBundle.SCHEMA_VERSION}")
            return null
        }
        return bundle
    }

    /** Reads and parses a backup file from [srcUri]. Returns null on any failure. */
    suspend fun readBackupBundle(srcUri: Uri): BackupBundle? = withContext(Dispatchers.IO) {
        val text = try {
            context.contentResolver.openInputStream(srcUri)?.use { stream ->
                stream.bufferedReader().readText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "readBackupBundle: read failed: ${e.message}")
            null
        } ?: return@withContext null
        parseBackup(text)
    }

    /**
     * Builds a non-destructive [HabitRestorePreview] describing what restoring
     * [habitName] from [srcUri] would do, WITHOUT modifying any data. Reads the
     * current on-device state for the habit and compares it to the backup.
     */
    suspend fun previewSingleHabitRestore(srcUri: Uri, habitName: String): HabitRestorePreview? =
        withContext(Dispatchers.IO) {
            val bundle = readBackupBundle(srcUri) ?: return@withContext null
            val settings = settingsRepo.settingsFlow.first()

            // Backup data for this habit
            val backupCounts = bundle.habitsDb[habitName] ?: emptyMap()
            val backupTimestamps = bundle.habitTimestamps[habitName] ?: emptyMap()
            val backupTotal = backupCounts.values.sum()

            // Current data for this habit
            val currentDb = readHabitsDb(settings.fileUri)
            val currentCounts = currentDb[habitName] ?: emptyMap()
            val currentTotal = currentCounts.values.sum()
            val currentTimestamps = readHabitTimestamps()[habitName] ?: emptyMap()

            val backupDates = (backupCounts.keys + backupTimestamps.keys)
                .filter { it.isNotBlank() }
            val currentDates = (currentCounts.keys + currentTimestamps.keys)
                .filter { it.isNotBlank() }

            HabitRestorePreview(
                habitName = habitName,
                backupExportedAt = bundle.exportedAt,
                currentTotal = currentTotal,
                backupTotal = backupTotal,
                incrementDelta = backupTotal - currentTotal,
                backupDayCount = backupDates.size,
                backupLastDate = backupDates.maxOrNull(),
                currentLastDate = currentDates.maxOrNull(),
                hasSubtypeData = bundle.perHabitFiles.subtypeData.containsKey(habitName),
                hasTimedData = bundle.perHabitFiles.timedData.containsKey(habitName),
                hasTextInputData = bundle.perHabitFiles.textInput.containsKey(habitName),
                hasDatedEntryData = bundle.perHabitFiles.datedEntry.containsKey(habitName)
            )
        }

    /**
     * Restores ONLY [habitName] from the backup at [srcUri], overwriting the
     * current on-device data for that single habit. Every other habit and the
     * rest of the backup are left untouched.
     *
     * Data sources restored (each only if present in the backup AND a target
     * file is configured on this device):
     *  1. habits database entry (date → count)
     *  2. habit timestamps (date → [times])
     *  3. subtype data file
     *  4. timed data file
     *  5. text-input log file (+ internal backup)
     *  6. dated-entry source file
     *
     * The habits-DB write goes through [HabitsRepository.restoreDatabaseRaw]
     * (which snapshots the pre-write state first) so an accidental restore is
     * itself recoverable.
     */
    suspend fun restoreSingleHabit(srcUri: Uri, habitName: String): BackupResult =
        withContext(Dispatchers.IO) {
            try {
                val bundle = readBackupBundle(srcUri)
                    ?: return@withContext BackupResult.Failure("Could not read backup file")
                val settings = settingsRepo.settingsFlow.first()

                // 1. Habits DB — replace just this habit's entry inside the full DB.
                val backupCounts = bundle.habitsDb[habitName]
                if (backupCounts != null && settings.fileUri.isNotBlank()) {
                    val merged = readHabitsDb(settings.fileUri).toMutableMap()
                    merged[habitName] = backupCounts
                    habitsRepo.restoreDatabaseRaw(Uri.parse(settings.fileUri), context, merged)
                }

                // 2. Habit timestamps — replace just this habit's timestamps.
                val backupTimestamps = bundle.habitTimestamps[habitName]
                if (backupTimestamps != null) {
                    val merged = readHabitTimestamps().toMutableMap()
                    merged[habitName] = backupTimestamps
                    applyHabitTimestamps(merged)
                }

                // 3-6. Per-habit data (internal stores for subtype/timed; external
                // SAF best-effort for text logs and dated-entry sources).
                bundle.perHabitFiles.subtypeData[habitName]?.let { data ->
                    runCatching {
                        subtypeDataRepo.saveSubtypeData(habitName, data)
                    }.onFailure {
                        Log.w(TAG, "subtype-data restore failed for '$habitName': ${it.message}")
                    }
                }
                bundle.perHabitFiles.timedData[habitName]?.let { data ->
                    runCatching {
                        val typed = data.mapValues { (_, obj) ->
                            val subtype = obj["subtype"]?.toString()?.takeIf { it != "null" }
                            val count = (obj["count"] as? Number)?.toInt() ?: 0
                            TimedEntry(subtype = subtype, count = count)
                        }
                        timedDataRepo.saveTimedData(habitName, typed)
                    }.onFailure {
                        Log.w(TAG, "timed-data restore failed for '$habitName': ${it.message}")
                    }
                }
                bundle.perHabitFiles.textInput[habitName]?.let { log ->
                    settings.textInputFileUris[habitName]?.let { uriStr ->
                        writeJsonToSaf(uriStr, log)
                        // Also refresh the internal backup so the data survives
                        // future external-file loss.
                        runCatching {
                            val dir = java.io.File(context.filesDir, "text_input_backups")
                            if (!dir.exists()) dir.mkdirs()
                            val backupFile = java.io.File(
                                dir,
                                habitName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100) + ".json"
                            )
                            backupFile.writeText(gson.toJson(log.toSortedMap()))
                        }.onFailure {
                            Log.w(TAG, "text-input internal backup restore failed for '$habitName': ${it.message}")
                        }
                    }
                }
                bundle.perHabitFiles.datedEntry[habitName]?.let { content ->
                    settings.datedEntryFileUris[habitName]?.let { uriStr ->
                        writeTextToSaf(uriStr, content)
                    }
                }

                BackupResult.Success(
                    "Restored '$habitName' from backup" +
                            " (${bundle.exportedAt.ifBlank { "unknown date" }})."
                )
            } catch (e: Exception) {
                Log.e(TAG, "Single-habit restore failed", e)
                BackupResult.Failure("Restore failed: ${e.message}", e)
            }
        }

    private suspend fun applyBundle(b: BackupBundle) {
        applySettings(b.settings)
        applyAdvice(b.advice)
        applyLocations(b.locations)
        applyDebug(b.debug)
        applyHabitsDb(b.settings.fileUri, b.habitsDb)
        applyHabitTimestamps(b.habitTimestamps)
        applyAiIcons(b.aiIcons)
        applyPerHabitFiles(b.settings, b.perHabitFiles)
        applyVoiceNote(b.settings.voiceNoteFileUri, b.voiceNoteMarkdown)
        applyMealSection(b.meal)
        applyVisionQueueJson(b.visionQueueJson)
        applyExtraPrefs(b.extraPrefs)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  IMPORT — per-section writers
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun applySettings(s: SettingsSection) {
        // file URIs
        settingsRepo.saveFileUri(s.fileUri)
        settingsRepo.saveScreensRelayFileUri(s.screensRelayFileUri)
        settingsRepo.savePcWidgetHabits(s.pcWidgetHabits.toSet())

        // habit type sets
        settingsRepo.saveCustomInputHabits(s.customInputHabits.toSet())
        settingsRepo.saveHabitOrder(s.habitOrder)
        settingsRepo.saveHabitScreens(s.habitScreens.map { HabitScreen(it.id, it.name, it.habitNames) })
        settingsRepo.saveActiveScreenIndex(s.activeScreenIndex)

        settingsRepo.saveMaxOneHabits(s.maxOneHabits.toSet())
        settingsRepo.saveInvertedBinaryHabits(s.invertedBinaryHabits.toSet())
        settingsRepo.saveMinutesEnabledHabits(s.minutesEnabledHabits.toSet())
        settingsRepo.saveMinutesPrimaryFallbacks(s.minutesPrimaryFallbacks)
        settingsRepo.saveTextInputHabits(s.textInputHabits.toSet())
        settingsRepo.saveTextInputOptionsHabits(s.textInputOptionsHabits.toSet())
        settingsRepo.saveTextInputFileUris(s.textInputFileUris)
        settingsRepo.saveHabitIcons(s.habitIcons)

        settingsRepo.saveDatedEntryHabits(s.datedEntryHabits.toSet())
        settingsRepo.saveDatedEntryFileUris(s.datedEntryFileUris)
        settingsRepo.saveDatedEntryFileSizes(s.datedEntryFileSizes)
        settingsRepo.saveHabitDividers(s.habitDividers)

        settingsRepo.saveConditionalHabits(s.conditionalHabits.toSet())
        settingsRepo.saveConditionalLinkedHabits(
            s.conditionalLinkedHabits.mapValues { it.value.toSet() }
        )
        settingsRepo.saveConditionalLinkValues(s.conditionalLinkValues)
        settingsRepo.saveConditionalFeedMaxOneHabits(s.conditionalFeedMaxOneHabits.toSet())
        settingsRepo.saveConditionalFeedPointsHabits(s.conditionalFeedPointsHabits.toSet())

        settingsRepo.saveSubtypedHabits(s.subtypedHabits.toSet())
        settingsRepo.saveHabitSubtypes(s.habitSubtypes)
        settingsRepo.saveSubtypeDataFileUris(s.subtypeDataFileUris)

        settingsRepo.saveTimedHabits(s.timedHabits.toSet())
        settingsRepo.saveTimedDataFileUris(s.timedDataFileUris)
        settingsRepo.saveTimelessHabits(s.timelessHabits.toSet())

        settingsRepo.saveHiddenScreens(s.hiddenScreens.toSet())
        settingsRepo.saveDisabledHabits(s.disabledHabits.toSet())
        settingsRepo.saveNoPointsHabits(s.noPointsHabits.toSet())

        settingsRepo.saveAiIconSettings(
            enabled = s.aiIconsEnabled,
            apiKey = s.aiIconsApiKey,
            baseUrl = s.aiIconsBaseUrl,
            endpoint = s.aiIconsEndpoint,
            model = s.aiIconsModel,
            quality = s.aiIconsQuality
        )
        settingsRepo.saveChessComEnabled(s.chessComEnabled)
        settingsRepo.saveChessComUsername(s.chessComUsername)
        settingsRepo.saveChessComHabitLinks(s.chessComHabitLinks)

        settingsRepo.saveVoiceTriggerEnabled(s.voiceTriggerEnabled)
        settingsRepo.saveVoiceTriggerHabits(s.voiceTriggerHabits.toSet())
        settingsRepo.saveVoiceTriggerWords(s.voiceTriggerWords.mapValues { it.value.toSet() })
        settingsRepo.saveVoiceTriggerIncrements(s.voiceTriggerIncrements)
        settingsRepo.saveVoiceSubtypeHabits(s.voiceSubtypeHabits.toSet())

        settingsRepo.saveVoiceNoteEnabled(s.voiceNoteEnabled)
        settingsRepo.saveVoiceNoteFileUri(s.voiceNoteFileUri)

        // ── Fields added in the 2026-08 completeness audit ───────────────
        settingsRepo.saveSharableTextHabits(s.sharableTextHabits.toSet())
        settingsRepo.saveSecondaryValueHabits(s.secondaryValueHabits.toSet())
        settingsRepo.saveSecondaryValueFallbackHabits(s.secondaryValueFallbackHabits.toSet())
        settingsRepo.saveValueDisplayLabels(s.valueDisplayLabels)
        settingsRepo.saveCustomInputAmounts(s.customInputAmounts)
        settingsRepo.saveCustomInputRecentAmounts(s.customInputRecentAmounts)
        settingsRepo.saveAutoBackupFolderUri(s.autoBackupFolderUri)
        settingsRepo.saveMapStatsHabits(s.mapStatsHabits.toSet())
        settingsRepo.saveMapStatsShowTextHabits(s.mapStatsShowTextHabits.toSet())
        settingsRepo.saveMapMainHabit(s.mapMainHabit?.takeIf { it.isNotEmpty() })
        settingsRepo.saveMapHideZeroDays(s.mapHideZeroDays)
        settingsRepo.saveMapBeginDate(s.mapBeginDate)
        settingsRepo.saveGarminEnabled(s.garminEnabled)
        settingsRepo.saveGarminProxyUrl(s.garminProxyUrl)
        settingsRepo.saveGarminAppToken(s.garminAppToken)
        settingsRepo.saveGarminDateOfBirth(s.garminDateOfBirth)
        settingsRepo.saveGarminHabitLinks(s.garminHabitLinks)
        settingsRepo.saveGithubEnabled(s.githubEnabled)
        settingsRepo.saveGithubToken(s.githubToken)
        settingsRepo.saveGithubRepoUrls(s.githubRepoUrls)
        settingsRepo.saveGithubMetrics(s.githubMetrics)
        settingsRepo.saveBridgeSettings(s.bridgeEnabled, s.bridgeUrl, s.bridgeToken)
        settingsRepo.saveBridgeMovieHabits(s.bridgeMovieHabits.toSet())
        settingsRepo.saveOmdbApiKey(s.omdbApiKey)
        settingsRepo.saveCustomPointRangesHabits(s.customPointRangesHabits.toSet())
        settingsRepo.saveCustomPointRanges(
            s.customPointRanges.mapValues { (_, ranges) ->
                ranges.map { PointRange(it.min, it.max) }
            }
        )
        settingsRepo.saveGraphValueModeHabits(s.graphValueModeHabits)
        settingsRepo.saveGraphMetricSelection(s.graphMetricSelection.mapValues { it.value.toSet() })
        settingsRepo.saveGraphInterpolateZeroMetrics(s.graphInterpolateZeroMetrics.mapValues { it.value.toSet() })
        settingsRepo.saveHabitNotes(s.habitNotes)
        settingsRepo.saveRollForwardHabits(s.rollForwardHabits.toSet())
        settingsRepo.saveRollForwardManualDates(s.rollForwardManualDates.mapValues { it.value.toSet() })
        settingsRepo.saveMealSettings(
            enabled = s.mealEnabled,
            baseUrl = s.mealBaseUrl,
            apiKey = s.mealApiKey,
            model = s.mealModel,
            systemPrompt = s.mealSystemPrompt
        )
        settingsRepo.saveMealHabits(s.mealHabits.toSet())
        settingsRepo.saveCameraHabits(s.cameraHabits.toSet())
        settingsRepo.saveAppLinks(s.appLinks)
        settingsRepo.saveHabitAppAssociations(s.habitAppAssociations)
        settingsRepo.saveHabitLongPressActions(s.habitLongPressActions)
        settingsRepo.saveHabitLongPressUrls(s.habitLongPressUrls)
        settingsRepo.saveHabitLongPressUrlApps(s.habitLongPressUrlApps)
        settingsRepo.saveWidgetTriggerHabits(s.widgetTriggerHabits.toSet())
        settingsRepo.saveWidgetTriggerApps(s.widgetTriggerApps)
        settingsRepo.saveWidgetTimerMinutesPrimary(s.widgetTimerMinutesPrimary.toSet())
        settingsRepo.saveChessReadinessEnabled(s.chessReadinessEnabled)
        settingsRepo.saveChessReadinessApp(s.chessReadinessApp)
        settingsRepo.saveGdriveAutoEnabled(s.gdriveAutoEnabled)
        settingsRepo.saveGdriveAccountName(s.gdriveAccountName)
        settingsRepo.saveWallpaperSettings(
            s.wallpaperEnabled, s.wallpaperDirUri, s.wallpaperTarget, s.wallpaperMetric
        )
    }

    private suspend fun applyAdvice(items: List<AdviceBackupItem>) {
        // The repo only exposes add/update/delete by id, so rebuild the list
        // by deleting everything currently present and re-adding from the
        // backup. We use the public API exclusively to stay future-safe.
        val existing = adviceRepo.observeAll().first()
        for (e in existing) adviceRepo.delete(e.id)
        for (item in items) {
            // add() ignores id and assigns a new one — to preserve original
            // ids we update right after adding.
            val newId = adviceRepo.add(item.text)
            adviceRepo.update(
                AdviceItem(
                    id = newId,
                    text = item.text,
                    notes = item.notes,
                    createdAt = item.createdAt
                )
            )
        }
    }

    private fun applyLocations(loc: LocationsSection) {
        val prefs = context.getSharedPreferences("tail_location_prefs", Context.MODE_PRIVATE)
        val ed = prefs.edit()
        // JSONObject(Map) only accepts Map<*, *>; cast explicitly so the
        // generics work out without resorting to a runtime helper.
        ed.putString("daily_locations", JSONObject(loc.labels as Map<*, *>).toString())
        ed.putString("daily_coords", JSONObject(loc.coords as Map<*, *>).toString())
        ed.putString("ignored_country_names", JSONArray(loc.ignoredCountries).toString())
        ed.putBoolean("ignored_country_names_seeded", loc.ignoredCountriesSeeded)
        ed.apply()
    }

    private fun applyDebug(d: DebugSection) {
        debugPrefs.debugModeEnabled = d.debugModeEnabled
        debugPrefs.debugFileDirUri = d.debugFileDirUri
        val notes = d.savedNotes.mapNotNull { sn ->
            val type = runCatching { NoteType.valueOf(sn.noteType) }.getOrNull() ?: return@mapNotNull null
            SavedNote(
                id = sn.id,
                timestamp = sn.timestamp,
                screenRoute = sn.screenRoute,
                screenLabel = sn.screenLabel,
                sourceFile = sn.sourceFile,
                sourceFunctions = sn.sourceFunctions,
                noteType = type,
                noteText = sn.noteText
            )
        }
        debugPrefs.saveSavedNotes(notes)
        debugPrefs.refresh()
        d.debugTailJson?.let { json ->
            try {
                File(context.filesDir, "debug_tail.json").writeText(json)
            } catch (e: Exception) {
                Log.w(TAG, "debug_tail.json write failed: ${e.message}")
            }
        }
    }

    /** Restores meal logs (raw JSON) and meal photos (base64 → bytes). */
    private fun applyMealSection(m: MealSection) {
        val logsDir = File(context.filesDir, "meal_logs").apply { mkdirs() }
        for ((name, json) in m.logs) {
            try {
                File(logsDir, name).writeText(json)
            } catch (e: Exception) {
                Log.w(TAG, "meal log write failed for '$name': ${e.message}")
            }
        }
        if (m.images.isNotEmpty()) {
            val imagesDir = File(context.filesDir, "meal_images").apply { mkdirs() }
            for ((name, b64) in m.images) {
                try {
                    val bytes = Base64.decode(b64, Base64.NO_WRAP or Base64.DEFAULT)
                    File(imagesDir, name).writeBytes(bytes)
                } catch (e: Exception) {
                    Log.w(TAG, "meal image write failed for '$name': ${e.message}")
                }
            }
        }
    }

    private fun applyVisionQueueJson(json: String?) {
        if (json.isNullOrBlank()) return
        try {
            File(context.filesDir, "vision_queue.json").writeText(json)
        } catch (e: Exception) {
            Log.w(TAG, "vision_queue.json write failed: ${e.message}")
        }
    }

    /** Restores typed SharedPreferences entries using their type tags. */
    private fun applyExtraPrefs(extra: Map<String, List<PrefEntryBackup>>) {
        for ((name, entries) in extra) {
            try {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val ed = prefs.edit().clear()
                for (e in entries) {
                    when (e.type) {
                        "bool" -> ed.putBoolean(e.key, e.boolValue ?: false)
                        "int" -> ed.putInt(e.key, (e.intValue ?: 0L).toInt())
                        "long" -> ed.putLong(e.key, e.intValue ?: 0L)
                        "float" -> ed.putFloat(e.key, (e.floatValue ?: 0.0).toFloat())
                        "string" -> ed.putString(e.key, e.stringValue)
                        "stringset" -> ed.putStringSet(e.key, e.stringSetValue.toSet())
                        else -> Log.w(TAG, "extraPrefs: unknown type '${e.type}' for key ${e.key}")
                    }
                }
                ed.apply()
            } catch (e: Exception) {
                Log.w(TAG, "extraPrefs restore failed for '$name': ${e.message}")
            }
        }
    }

    private suspend fun applyHabitsDb(fileUri: String, db: Map<String, Map<String, Int>>) {
        if (fileUri.isBlank() || db.isEmpty()) return
        try {
            habitsRepo.saveDatabase(Uri.parse(fileUri), context, db)
        } catch (e: Exception) {
            Log.w(TAG, "habitsDb write failed (URI may not be granted on this device): ${e.message}")
        }
    }

    private fun applyHabitTimestamps(data: Map<String, Map<String, List<String>>>) {
        val file = File(context.filesDir, "habit_timestamps.json")
        try {
            file.writeText(gson.toJson(data))
        } catch (e: Exception) {
            Log.w(TAG, "habit_timestamps.json write failed: ${e.message}")
        }
    }

    private fun applyAiIcons(s: AiIconsSection) {
        val iconsDir = File(context.filesDir, "ai_icons").apply { mkdirs() }

        // wipe existing icons so we don't accumulate stale ones
        iconsDir.listFiles()?.forEach { runCatching { it.delete() } }

        // write PNG files
        for ((id, b64) in s.files) {
            try {
                val bytes = Base64.decode(b64, Base64.NO_WRAP or Base64.DEFAULT)
                File(iconsDir, "$id.png").writeBytes(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "AI icon write failed for '$id': ${e.message}")
            }
        }
        // rewrite the index file
        try {
            val arr = JSONArray()
            s.index.forEach { item ->
                arr.put(JSONObject().apply {
                    put("id", item.id)
                    put("prompt", item.prompt)
                    put("createdAt", item.createdAt)
                })
            }
            File(iconsDir, "ai_icons_index.json").writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "AI icon index write failed: ${e.message}")
        }
    }

    private suspend fun applyPerHabitFiles(s: SettingsSection, p: PerHabitFilesSection) {
        // text-input logs — write to external SAF AND save internal backup
        for ((habit, log) in p.textInput) {
            val uriStr = s.textInputFileUris[habit] ?: continue
            writeJsonToSaf(uriStr, log)
            // Also populate internal backup so the data survives future external-file loss
            try {
                val dir = java.io.File(context.filesDir, "text_input_backups")
                if (!dir.exists()) dir.mkdirs()
                val backupFile = java.io.File(dir, habit.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100) + ".json")
                val sortedLog = log.toSortedMap()
                backupFile.writeText(com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(sortedLog))
            } catch (e: Exception) {
                Log.w(TAG, "text-input internal backup save failed for '$habit': ${e.message}")
            }
        }
        // dated-entry source files (raw text)
        for ((habit, content) in p.datedEntry) {
            val uriStr = s.datedEntryFileUris[habit] ?: continue
            writeTextToSaf(uriStr, content)
        }
        // subtype data (internal store)
        for ((habit, data) in p.subtypeData) {
            try {
                subtypeDataRepo.saveSubtypeData(habit, data)
            } catch (e: Exception) {
                Log.w(TAG, "subtype-data write failed for '$habit': ${e.message}")
            }
        }
        // timed data (internal store)
        for ((habit, data) in p.timedData) {
            try {
                val typed = data.mapValues { (_, obj) ->
                    val subtype = obj["subtype"]?.toString()?.takeIf { it != "null" }
                    val count = (obj["count"] as? Number)?.toInt() ?: 0
                    TimedEntry(subtype = subtype, count = count)
                }
                timedDataRepo.saveTimedData(habit, typed)
            } catch (e: Exception) {
                Log.w(TAG, "timed-data write failed for '$habit': ${e.message}")
            }
        }
    }

    private fun applyVoiceNote(uriStr: String, content: String?) {
        if (uriStr.isBlank() || content == null) return
        writeTextToSaf(uriStr, content)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  helpers
    // ─────────────────────────────────────────────────────────────────────

    private fun writeJsonToSaf(uriStr: String, payload: Any) {
        if (uriStr.isBlank()) return
        try {
            val json = gson.toJson(payload)
            context.contentResolver.openOutputStream(Uri.parse(uriStr), "wt")?.use { out ->
                out.bufferedWriter().use { it.write(json) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF JSON write failed for '$uriStr': ${e.message}")
        }
    }

    private fun writeTextToSaf(uriStr: String, text: String) {
        if (uriStr.isBlank()) return
        try {
            context.contentResolver.openOutputStream(Uri.parse(uriStr), "wt")?.use { out ->
                out.bufferedWriter().use { it.write(text) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SAF text write failed for '$uriStr': ${e.message}")
        }
    }

    private fun readJsonObjectMap(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            obj.keys().asSequence().associateWith { obj.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "JSON object parse failed: ${e.message}")
            emptyMap()
        }
    }

    private fun readJsonStringArray(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            Log.w(TAG, "JSON array parse failed: ${e.message}")
            emptyList()
        }
    }

    private fun toSettingsSection(s: AppSettings) = SettingsSection(
        fileUri = s.fileUri,
        screensRelayFileUri = s.screensRelayFileUri,
        pcWidgetHabits = s.pcWidgetHabits.toList(),
        customInputHabits = s.customInputHabits.toList(),
        habitOrder = s.habitOrder,
        habitScreens = s.habitScreens.map { HabitScreenBackup(it.id, it.name, it.habitNames) },
        activeScreenIndex = s.activeScreenIndex,
        maxOneHabits = s.maxOneHabits.toList(),
        invertedBinaryHabits = s.invertedBinaryHabits.toList(),
        minutesEnabledHabits = s.minutesEnabledHabits.toList(),
        minutesPrimaryFallbacks = s.minutesPrimaryFallbacks,
        textInputHabits = s.textInputHabits.toList(),
        textInputOptionsHabits = s.textInputOptionsHabits.toList(),
        textInputFileUris = s.textInputFileUris,
        habitIcons = s.habitIcons,
        datedEntryHabits = s.datedEntryHabits.toList(),
        datedEntryFileUris = s.datedEntryFileUris,
        datedEntryFileSizes = s.datedEntryFileSizes,
        habitDividers = s.habitDividers,
        conditionalHabits = s.conditionalHabits.toList(),
        conditionalLinkedHabits = s.conditionalLinkedHabits.mapValues { it.value.toList() },
        conditionalLinkValues = s.conditionalLinkValues,
        conditionalFeedMaxOneHabits = s.conditionalFeedMaxOneHabits.toList(),
        conditionalFeedPointsHabits = s.conditionalFeedPointsHabits.toList(),
        subtypedHabits = s.subtypedHabits.toList(),
        habitSubtypes = s.habitSubtypes,
        subtypeDataFileUris = s.subtypeDataFileUris,
        timedHabits = s.timedHabits.toList(),
        timedDataFileUris = s.timedDataFileUris,
        timelessHabits = s.timelessHabits.toList(),
        hiddenScreens = s.hiddenScreens.toList(),
        disabledHabits = s.disabledHabits.toList(),
        noPointsHabits = s.noPointsHabits.toList(),
        aiIconsEnabled = s.aiIconsEnabled,
        aiIconsApiKey = s.aiIconsApiKey,
        aiIconsBaseUrl = s.aiIconsBaseUrl,
        aiIconsEndpoint = s.aiIconsEndpoint,
        aiIconsModel = s.aiIconsModel,
        aiIconsQuality = s.aiIconsQuality,
        chessComEnabled = s.chessComEnabled,
        chessComUsername = s.chessComUsername,
        chessComHabitLinks = s.chessComHabitLinks,
        voiceTriggerEnabled = s.voiceTriggerEnabled,
        voiceTriggerHabits = s.voiceTriggerHabits.toList(),
        voiceTriggerWords = s.voiceTriggerWords.mapValues { it.value.toList() },
        voiceTriggerIncrements = s.voiceTriggerIncrements,
        voiceSubtypeHabits = s.voiceSubtypeHabits.toList(),
        voiceNoteEnabled = s.voiceNoteEnabled,
        voiceNoteFileUri = s.voiceNoteFileUri,

        // ── Fields added in the 2026-08 completeness audit ───────────────
        sharableTextHabits = s.sharableTextHabits.toList(),
        secondaryValueHabits = s.secondaryValueHabits.toList(),
        secondaryValueFallbackHabits = s.secondaryValueFallbackHabits.toList(),
        valueDisplayLabels = s.valueDisplayLabels,
        customInputAmounts = s.customInputAmounts,
        customInputRecentAmounts = s.customInputRecentAmounts,
        autoBackupFolderUri = s.autoBackupFolderUri,
        mapStatsHabits = s.mapStatsHabits.toList(),
        mapStatsShowTextHabits = s.mapStatsShowTextHabits.toList(),
        mapMainHabit = s.mapMainHabit,
        mapHideZeroDays = s.mapHideZeroDays,
        mapBeginDate = s.mapBeginDate,
        garminEnabled = s.garminEnabled,
        garminProxyUrl = s.garminProxyUrl,
        garminAppToken = s.garminAppToken,
        garminDateOfBirth = s.garminDateOfBirth,
        garminHabitLinks = s.garminHabitLinks,
        githubEnabled = s.githubEnabled,
        githubToken = s.githubToken,
        githubRepoUrls = s.githubRepoUrls,
        githubMetrics = s.githubMetrics,
        bridgeEnabled = s.bridgeEnabled,
        bridgeUrl = s.bridgeUrl,
        bridgeToken = s.bridgeToken,
        bridgeMovieHabits = s.bridgeMovieHabits.toList(),
        omdbApiKey = s.omdbApiKey,
        customPointRangesHabits = s.customPointRangesHabits.toList(),
        customPointRanges = s.customPointRanges.mapValues { (_, ranges) ->
            ranges.map { PointRangeBackup(it.min, it.max) }
        },
        graphValueModeHabits = s.graphValueModeHabits,
        graphMetricSelection = s.graphMetricSelection.mapValues { it.value.toList() },
        graphInterpolateZeroMetrics = s.graphInterpolateZeroMetrics.mapValues { it.value.toList() },
        habitNotes = s.habitNotes,
        rollForwardHabits = s.rollForwardHabits.toList(),
        rollForwardManualDates = s.rollForwardManualDates.mapValues { it.value.toList() },
        mealEnabled = s.mealEnabled,
        mealBaseUrl = s.mealBaseUrl,
        mealApiKey = s.mealApiKey,
        mealModel = s.mealModel,
        mealSystemPrompt = s.mealSystemPrompt,
        mealHabits = s.mealHabits.toList(),
        cameraHabits = s.cameraHabits.toList(),
        appLinks = s.appLinks,
        habitAppAssociations = s.habitAppAssociations,
        habitLongPressActions = s.habitLongPressActions,
        habitLongPressUrls = s.habitLongPressUrls,
        habitLongPressUrlApps = s.habitLongPressUrlApps,
        widgetTriggerHabits = s.widgetTriggerHabits.toList(),
        widgetTriggerApps = s.widgetTriggerApps,
        widgetTimerMinutesPrimary = s.widgetTimerMinutesPrimary.toList(),
        chessReadinessEnabled = s.chessReadinessEnabled,
        chessReadinessApp = s.chessReadinessApp,
        gdriveAutoEnabled = s.gdriveAutoEnabled,
        gdriveAccountName = s.gdriveAccountName,
        wallpaperEnabled = s.wallpaperEnabled,
        wallpaperDirUri = s.wallpaperDirUri,
        wallpaperTarget = s.wallpaperTarget.name,
        wallpaperMetric = s.wallpaperMetric.name
    )

    private fun AdviceItem.toBackup() = AdviceBackupItem(
        id = id, text = text, notes = notes, createdAt = createdAt
    )
}
