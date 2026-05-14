package com.example.tail.data.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.tail.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private const val TAG = "AutoBackupManager"

/** Auto-backup file prefix. Files are named `<prefix>YYYY-MM-DD.json`. */
const val AUTO_BACKUP_FILE_PREFIX = "tail_auto_backup_"

/** Suffix appended to the date (mime type for SAF write). */
private const val AUTO_BACKUP_MIME = "application/json"

/**
 * Drives the once-per-day automatic backup that runs on the first foregrounding
 * of each calendar day, BEFORE any habit DB read/write so the on-disk file is
 * captured in its sync-stable pre-launch state.
 *
 * Design notes:
 *   - Folder is a SAF tree URI picked by the user once via OpenDocumentTree.
 *     Pointing it at a Syncthing-synced folder makes the backups propagate to
 *     the desktop automatically.
 *   - File name: `tail_auto_backup_YYYY-MM-DD.json` — one per day, never
 *     overwritten on subsequent same-day launches (we just skip).
 *   - We update [SettingsRepository.saveAutoBackupLastDate] ONLY on confirmed
 *     successful write.
 *   - Old backups are NEVER auto-deleted; the user prunes manually via the
 *     [AutoBackupListing] UI in [com.example.tail.ui.BackupSettingsSection].
 */
class AutoBackupManager(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val backupManager: BackupManager
) {
    /**
     * Live view of the auto-backup-relevant settings, for the Settings UI to
     * observe without having to depend on [SettingsRepository] directly.
     */
    val configFlow: Flow<AutoBackupConfig> = settingsRepo.settingsFlow.map {
        AutoBackupConfig(folderUri = it.autoBackupFolderUri, lastDate = it.autoBackupLastDate)
    }

    /** Persist the user-picked SAF tree URI for the auto-backup folder. */
    suspend fun saveFolderUri(uri: String) = settingsRepo.saveAutoBackupFolderUri(uri)

    /**
     * Clear the "last successful auto-backup" date so the next [runIfNeeded]
     * call will run today's backup again (used by the "Back up now" button).
     */
    suspend fun forceNextRun() = settingsRepo.saveAutoBackupLastDate("")

    /** Result of a [runIfNeeded] attempt — useful for logging / Settings UI status text. */
    sealed class RunResult {
        /** Already backed up today — nothing to do. */
        object AlreadyDoneToday : RunResult()
        /** No auto-backup folder picked yet — feature disabled. */
        object NoFolderConfigured : RunResult()
        /** The configured folder URI is no longer accessible (permission revoked, folder deleted). */
        data class FolderUnavailable(val reason: String) : RunResult()
        /** Created today's backup successfully. */
        data class Backed(val fileName: String, val sizeBytes: Long) : RunResult()
        /** Something failed during the export. */
        data class Failed(val message: String, val cause: Throwable? = null) : RunResult()
    }

    /**
     * The "first thing in the morning" gate. Call this on app launch BEFORE
     * any habit DB read/write. Cheap when already-done-today (single
     * DataStore read + date compare).
     *
     * Returns the [RunResult] so the UI can show meaningful status.
     */
    suspend fun runIfNeeded(today: LocalDate = LocalDate.now()): RunResult =
        withContext(Dispatchers.IO) {
            val settings = settingsRepo.settingsFlow.first()
            val folderUri = settings.autoBackupFolderUri
            if (folderUri.isBlank()) {
                Log.i(TAG, "runIfNeeded: no auto-backup folder configured — skipping")
                return@withContext RunResult.NoFolderConfigured
            }
            val todayStr = today.toString()
            if (settings.autoBackupLastDate == todayStr) {
                Log.i(TAG, "runIfNeeded: already backed up today ($todayStr)")
                return@withContext RunResult.AlreadyDoneToday
            }

            val folder = try {
                DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
            } catch (e: Exception) {
                Log.w(TAG, "runIfNeeded: bad folder URI '$folderUri': ${e.message}")
                return@withContext RunResult.FolderUnavailable("Could not parse folder URI: ${e.message}")
            }
            if (folder == null || !folder.exists() || !folder.canWrite()) {
                Log.w(TAG, "runIfNeeded: folder unavailable (exists=${folder?.exists()}, write=${folder?.canWrite()})")
                return@withContext RunResult.FolderUnavailable(
                    "Folder is missing or not writable. Re-pick it in Settings."
                )
            }

            val fileName = "$AUTO_BACKUP_FILE_PREFIX$todayStr.json"

            // If a backup for today already exists (e.g. previous attempt crashed
            // mid-write or the lastDate marker got out of sync), we DELETE the
            // stale file before writing so the new export is clean.
            folder.findFile(fileName)?.let { existing ->
                Log.i(TAG, "runIfNeeded: removing stale same-day backup ${existing.name}")
                try { existing.delete() } catch (e: Exception) {
                    Log.w(TAG, "runIfNeeded: failed to delete stale ${existing.name}: ${e.message}")
                }
            }

            val newFile = try {
                folder.createFile(AUTO_BACKUP_MIME, fileName)
            } catch (e: Exception) {
                Log.e(TAG, "runIfNeeded: createFile failed: ${e.message}", e)
                return@withContext RunResult.Failed("Could not create backup file: ${e.message}", e)
            }
            if (newFile == null) {
                return@withContext RunResult.Failed("createFile returned null for $fileName")
            }

            // Delegate to the existing BackupManager.exportBackup logic.
            val res = backupManager.exportBackup(newFile.uri)
            when (res) {
                is BackupResult.Success -> {
                    settingsRepo.saveAutoBackupLastDate(todayStr)
                    val size = try { newFile.length() } catch (_: Exception) { -1L }
                    Log.i(TAG, "runIfNeeded: wrote $fileName (${size} bytes)")
                    RunResult.Backed(fileName, size)
                }
                is BackupResult.Failure -> {
                    // Clean up the empty/partial file so the user doesn't end up
                    // with a useless 0-byte backup file cluttering the folder.
                    try { newFile.delete() } catch (_: Exception) {}
                    Log.e(TAG, "runIfNeeded: export failed: ${res.message}", res.cause)
                    RunResult.Failed(res.message, res.cause)
                }
            }
        }

    /**
     * Lists every existing `tail_auto_backup_*.json` in the configured folder,
     * sorted newest-first. Intended for the Settings UI list where the user
     * can review and delete old backups.
     */
    suspend fun listExistingBackups(): List<AutoBackupEntry> = withContext(Dispatchers.IO) {
        val settings = settingsRepo.settingsFlow.first()
        val folderUri = settings.autoBackupFolderUri
        if (folderUri.isBlank()) return@withContext emptyList()
        val folder = try {
            DocumentFile.fromTreeUri(context, Uri.parse(folderUri))
        } catch (_: Exception) { null } ?: return@withContext emptyList()
        if (!folder.exists() || !folder.canRead()) return@withContext emptyList()

        folder.listFiles()
            .asSequence()
            .filter { it.isFile && (it.name?.startsWith(AUTO_BACKUP_FILE_PREFIX) == true) }
            .map { df ->
                AutoBackupEntry(
                    uri = df.uri,
                    name = df.name ?: "(unnamed)",
                    sizeBytes = try { df.length() } catch (_: Exception) { -1L },
                    lastModified = try { df.lastModified() } catch (_: Exception) { 0L }
                )
            }
            .sortedByDescending { it.lastModified }
            .toList()
    }

    /** Deletes the given backup file. Used by the Settings UI's manual prune action. */
    suspend fun deleteBackup(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val df = DocumentFile.fromSingleUri(context, uri) ?: return@withContext false
            df.delete()
        } catch (e: Exception) {
            Log.w(TAG, "deleteBackup: failed for $uri: ${e.message}")
            false
        }
    }
}

/** One entry in the user's auto-backup folder listing. */
data class AutoBackupEntry(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val lastModified: Long
)

/** Settings exposed by [AutoBackupManager.configFlow] for the Settings UI. */
data class AutoBackupConfig(
    val folderUri: String,
    val lastDate: String
)
