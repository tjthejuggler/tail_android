package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.first

/**
 * One-time migration (2026-08-15): imports the legacy per-habit EXTERNAL
 * subtype/timed SAF JSON files (configured via `subtypeDataFileUris` /
 * `timedDataFileUris` in settings) into the app-internal stores
 * (`files/subtype_data.json` / `files/timed_data.json`).
 *
 * Rationale: the app was the only reader/writer of those external files, so
 * they added SAF round-trips and a cross-file consistency risk with the
 * habits DB without any benefit. See the ADR entry for 2026-08-15.
 *
 * Semantics:
 *   - Subtype data is SUM-merged per (date, subtype): external counts are
 *     added on top of whatever is already internal (normally nothing, since
 *     the migration runs before any internal writes on a fresh update).
 *   - Timed data is key-merged per timestamp: external entries are added
 *     unless the exact timestamp key already exists internally.
 *   - The legacy external files are NOT modified or deleted — the user can
 *     remove them manually once the internal data is verified.
 *   - Gated by a DataStore flag, so this runs exactly once per install.
 *     Restoring an old backup does NOT re-run it (the flag lives in DataStore,
 *     which backups can restore — see BackupManager.applySettings, which does
 *     not touch this flag).
 *
 * Called from [com.example.tail.ui.HabitViewModel] init and from the voice
 * services before their first subtype write, so a voice increment can never
 * land in an unmigrated store.
 */
object SubtypeTimedMigrator {

    private const val TAG = "SubtypeTimedMigrator"

    /**
     * Runs the import if it hasn't run yet. Cheap (single DataStore read)
     * once the flag is set. [settingsHint] lets callers that already have
     * the current [AppSettings] avoid a second settings load.
     */
    suspend fun runIfNeeded(
        context: Context,
        settingsHint: AppSettings? = null
    ) {
        val settingsRepo = SettingsRepository(context)
        if (settingsRepo.isSubtypeTimedInternalized()) return

        val settings = settingsHint ?: settingsRepo.settingsFlow.first()
        val subtypeRepo = SubtypeDataRepository(context)
        val timedRepo = TimedDataRepository(context)

        var importedSubtype = 0
        for ((habit, uriStr) in settings.subtypeDataFileUris) {
            if (uriStr.isBlank()) continue
            try {
                val external = subtypeRepo.loadSubtypeDataFromUri(Uri.parse(uriStr))
                if (external.isEmpty()) continue

                // Sum-merge per (date, subtype) on top of existing internal data.
                val internal = subtypeRepo.loadSubtypeData(habit).toMutableMap()
                for ((date, subtypes) in external) {
                    val day = internal[date]?.toMutableMap() ?: mutableMapOf()
                    for ((subtype, count) in subtypes) {
                        if (count > 0) day[subtype] = (day[subtype] ?: 0) + count
                    }
                    internal[date] = day
                }
                subtypeRepo.saveSubtypeData(habit, internal)
                importedSubtype++
                Log.i(TAG, "Imported subtype data for '$habit' (${external.size} dates) from legacy external file")
            } catch (e: Exception) {
                Log.w(TAG, "Subtype import failed for '$habit': ${e.message}")
            }
        }

        var importedTimed = 0
        for ((habit, uriStr) in settings.timedDataFileUris) {
            if (uriStr.isBlank()) continue
            try {
                val external = timedRepo.loadTimedDataFromUri(Uri.parse(uriStr))
                if (external.isEmpty()) continue

                // Key-merge per timestamp; internal wins on exact collision.
                val internal = timedRepo.loadTimedData(habit).toMutableMap()
                for ((ts, entry) in external) {
                    if (!internal.containsKey(ts)) internal[ts] = entry
                }
                timedRepo.saveTimedData(habit, internal)
                importedTimed++
                Log.i(TAG, "Imported timed data for '$habit' (${external.size} entries) from legacy external file")
            } catch (e: Exception) {
                Log.w(TAG, "Timed import failed for '$habit': ${e.message}")
            }
        }

        settingsRepo.setSubtypeTimedInternalized()
        Log.i(
            TAG,
            "Subtype/timed internalization migration complete: $importedSubtype subtype files, " +
                    "$importedTimed timed files imported. Legacy external files left untouched."
        )
    }
}
