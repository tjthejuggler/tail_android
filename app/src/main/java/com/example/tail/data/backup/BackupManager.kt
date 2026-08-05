package com.example.tail.data.backup

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.example.tail.data.AdviceItem
import com.example.tail.data.AdviceRepository
import com.example.tail.data.AppSettings
import com.example.tail.data.HabitScreen
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
 *
 * Things deliberately NOT backed up (because they are derived / re-fetchable):
 *   - Tasker stats relay file (regenerated after every habit change)
 *   - Screens-layout relay file (regenerated whenever screens change)
 *   - chess.com monthly cache (re-fetched from the API)
 *   - One-time migration flags (let them re-run on a fresh install)
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
            val bundle = buildBundle()
            val json = gson.toJson(bundle)

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
            voiceNoteMarkdown = voiceNoteMd
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
            savedNotes = savedNotes
        )
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

        // subtype data JSONs
        val subtype = mutableMapOf<String, Map<String, Map<String, Int>>>()
        for ((habit, uriStr) in settings.subtypeDataFileUris) {
            if (uriStr.isBlank()) continue
            try {
                val data = subtypeDataRepo.loadSubtypeData(Uri.parse(uriStr), context)
                if (data.isNotEmpty()) subtype[habit] = data
            } catch (e: Exception) {
                Log.w(TAG, "subtype-data read failed for '$habit': ${e.message}")
            }
        }

        // timed data JSONs
        val timed = mutableMapOf<String, Map<String, Map<String, Any?>>>()
        for ((habit, uriStr) in settings.timedDataFileUris) {
            if (uriStr.isBlank()) continue
            try {
                val raw = timedDataRepo.loadTimedData(Uri.parse(uriStr), context)
                if (raw.isNotEmpty()) {
                    timed[habit] = raw.mapValues { (_, entry) ->
                        mapOf<String, Any?>("subtype" to entry.subtype, "count" to entry.count)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "timed-data read failed for '$habit': ${e.message}")
            }
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
    }

    // ─────────────────────────────────────────────────────────────────────
    //  IMPORT — per-section writers
    // ─────────────────────────────────────────────────────────────────────

    private suspend fun applySettings(s: SettingsSection) {
        // file URIs
        settingsRepo.saveFileUri(s.fileUri)
        settingsRepo.saveScreensRelayFileUri(s.screensRelayFileUri)
        settingsRepo.saveTaskerFileUri(s.taskerFileUri)

        // habit type sets
        settingsRepo.saveCustomInputHabits(s.customInputHabits.toSet())
        settingsRepo.saveHabitOrder(s.habitOrder)
        settingsRepo.saveHabitScreens(s.habitScreens.map { HabitScreen(it.id, it.name, it.habitNames) })
        settingsRepo.saveActiveScreenIndex(s.activeScreenIndex)

        settingsRepo.saveMaxOneHabits(s.maxOneHabits.toSet())
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
        settingsRepo.saveChessComMinutesPerIncrement(s.chessComMinutesPerIncrement)
        settingsRepo.saveChessComHabitLinks(s.chessComHabitLinks)

        settingsRepo.saveVoiceTriggerEnabled(s.voiceTriggerEnabled)
        settingsRepo.saveVoiceTriggerHabits(s.voiceTriggerHabits.toSet())
        settingsRepo.saveVoiceTriggerWords(s.voiceTriggerWords.mapValues { it.value.toSet() })
        settingsRepo.saveVoiceTriggerIncrements(s.voiceTriggerIncrements)
        settingsRepo.saveVoiceSubtypeHabits(s.voiceSubtypeHabits.toSet())

        settingsRepo.saveVoiceNoteEnabled(s.voiceNoteEnabled)
        settingsRepo.saveVoiceNoteFileUri(s.voiceNoteFileUri)
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
        // subtype data
        for ((habit, data) in p.subtypeData) {
            val uriStr = s.subtypeDataFileUris[habit] ?: continue
            try {
                subtypeDataRepo.saveSubtypeData(Uri.parse(uriStr), context, data)
            } catch (e: Exception) {
                Log.w(TAG, "subtype-data write failed for '$habit': ${e.message}")
            }
        }
        // timed data
        for ((habit, data) in p.timedData) {
            val uriStr = s.timedDataFileUris[habit] ?: continue
            try {
                val typed = data.mapValues { (_, obj) ->
                    val subtype = obj["subtype"]?.toString()?.takeIf { it != "null" }
                    val count = (obj["count"] as? Number)?.toInt() ?: 0
                    TimedEntry(subtype = subtype, count = count)
                }
                timedDataRepo.saveTimedData(Uri.parse(uriStr), context, typed)
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
        taskerFileUri = s.taskerFileUri,
        customInputHabits = s.customInputHabits.toList(),
        habitOrder = s.habitOrder,
        habitScreens = s.habitScreens.map { HabitScreenBackup(it.id, it.name, it.habitNames) },
        activeScreenIndex = s.activeScreenIndex,
        maxOneHabits = s.maxOneHabits.toList(),
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
        chessComMinutesPerIncrement = s.chessComMinutesPerIncrement,
        chessComHabitLinks = s.chessComHabitLinks,
        voiceTriggerEnabled = s.voiceTriggerEnabled,
        voiceTriggerHabits = s.voiceTriggerHabits.toList(),
        voiceTriggerWords = s.voiceTriggerWords.mapValues { it.value.toList() },
        voiceTriggerIncrements = s.voiceTriggerIncrements,
        voiceSubtypeHabits = s.voiceSubtypeHabits.toList(),
        voiceNoteEnabled = s.voiceNoteEnabled,
        voiceNoteFileUri = s.voiceNoteFileUri
    )

    private fun AdviceItem.toBackup() = AdviceBackupItem(
        id = id, text = text, notes = notes, createdAt = createdAt
    )
}
