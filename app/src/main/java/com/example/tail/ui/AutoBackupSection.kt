package com.example.tail.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.backup.AutoBackupConfig
import com.example.tail.data.backup.AutoBackupEntry
import com.example.tail.data.backup.AutoBackupManager
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Automatic daily backups" UI block, rendered inside the Settings →
 * Backup & Restore section.
 *
 * Features:
 *   - "Pick auto-backup folder" SAF tree-URI picker (persists permission).
 *   - Status line: shows currently-configured folder + date of last successful
 *     auto-backup.
 *   - "Show existing auto-backups" toggle that lists every
 *     `tail_auto_backup_*.json` file in the folder with size + date and a
 *     per-row delete button (the user prunes old backups manually).
 *
 * This component is intentionally separate from [BackupSettingsSection] so the
 * file stays small and focused (modularity rule: keep files < 500 lines).
 */
@Composable
fun AutoBackupSection(
    autoBackupManager: AutoBackupManager,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config by autoBackupManager.configFlow.collectAsState(
        initial = AutoBackupConfig(folderUri = "", lastDate = "")
    )

    var showList by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<AutoBackupEntry>>(emptyList()) }
    var entriesLoading by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<AutoBackupEntry?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val pickFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            // Persist read+write permission so we can write backups in the background
            // and list/delete them later, even after the app is restarted.
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Already granted on most devices; harmless to ignore here.
            }
            scope.launch {
                autoBackupManager.saveFolderUri(uri.toString())
                // Eagerly trigger today's backup if not done yet — gives the
                // user immediate feedback that the folder works.
                val res = autoBackupManager.runIfNeeded()
                status = "Folder saved. ${
                    when (res) {
                        is AutoBackupManager.RunResult.Backed -> "Wrote ${res.fileName} (${formatBytes(res.sizeBytes)})."
                        is AutoBackupManager.RunResult.AlreadyDoneToday -> "Already backed up today."
                        is AutoBackupManager.RunResult.NoFolderConfigured -> "No folder configured (unexpected)."
                        is AutoBackupManager.RunResult.FolderUnavailable -> "Folder not writable: ${res.reason}"
                        is AutoBackupManager.RunResult.Failed -> "First backup failed: ${res.message}"
                    }
                }"
            }
        }
    }

    Column {
        Text(
            "Automatic Daily Backup",
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Text(
            text = "On the first launch of each day, Tail writes a full backup " +
                    "(habits, settings, advice, locations, AI icons, debug notes, " +
                    "per-habit logs) to a folder you pick. Point this at a Syncthing " +
                    "folder so backups also sync to your PC. Old backups stay until you " +
                    "delete them below.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        val folderUri = config.folderUri
        Text(
            text = if (folderUri.isBlank()) "No backup folder selected — feature DISABLED"
            else "Folder: $folderUri",
            fontSize = 11.sp,
            color = if (folderUri.isBlank()) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        val lastDate = config.lastDate
        Text(
            text = "Last auto-backup: ${if (lastDate.isBlank()) "never" else lastDate}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { pickFolderLauncher.launch(null) }) {
                Text(if (folderUri.isBlank()) "Pick auto-backup folder" else "Change folder")
            }
            OutlinedButton(
                enabled = folderUri.isNotBlank(),
                onClick = {
                    showList = !showList
                    if (showList) {
                        entriesLoading = true
                        scope.launch {
                            entries = try {
                                autoBackupManager.listExistingBackups()
                            } catch (_: Exception) {
                                emptyList()
                            }
                            entriesLoading = false
                        }
                    }
                }
            ) {
                Text(if (showList) "Hide backup list" else "Manage old backups")
            }
        }

        // Manual "run now" button — useful for testing the folder and for users
        // who want an extra backup mid-day.
        if (folderUri.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedButton(
                onClick = {
                    scope.launch {
                        // Force a fresh backup even if today's already exists by
                        // clearing lastDate first. This intentionally replaces
                        // today's file (AutoBackupManager removes the stale one).
                        autoBackupManager.forceNextRun()
                        val res = autoBackupManager.runIfNeeded()
                        status = when (res) {
                            is AutoBackupManager.RunResult.Backed ->
                                "Wrote ${res.fileName} (${formatBytes(res.sizeBytes)})."
                            is AutoBackupManager.RunResult.AlreadyDoneToday ->
                                "Already backed up today."
                            is AutoBackupManager.RunResult.NoFolderConfigured ->
                                "No folder configured."
                            is AutoBackupManager.RunResult.FolderUnavailable ->
                                "Folder not writable: ${res.reason}"
                            is AutoBackupManager.RunResult.Failed ->
                                "Backup failed: ${res.message}"
                        }
                        // Refresh the listing if it's open
                        if (showList) {
                            entries = autoBackupManager.listExistingBackups()
                        }
                    }
                }
            ) {
                Text("Back up now")
            }
        }

        status?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (showList) {
            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Existing auto-backups (${entries.size}):",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            if (entriesLoading) {
                Text("Loading…", fontSize = 11.sp)
            } else if (entries.isEmpty()) {
                Text(
                    "No backups in this folder yet.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(modifier = Modifier.padding(start = 4.dp)) {
                    for (entry in entries) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.name, fontSize = 11.sp)
                                Text(
                                    "${formatBytes(entry.sizeBytes)} • ${formatModified(entry.lastModified)}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = { pendingDelete = entry }) {
                                Text("Delete", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog before deleting a backup — destructive op.
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete backup?") },
            text = {
                Text(
                    "Permanently delete ${toDelete.name} (${formatBytes(toDelete.sizeBytes)})? " +
                            "This cannot be undone."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = autoBackupManager.deleteBackup(toDelete.uri)
                        status = if (ok) "Deleted ${toDelete.name}"
                        else "Failed to delete ${toDelete.name}"
                        entries = autoBackupManager.listExistingBackups()
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Auto-clear status after a while
    LaunchedEffect(status) {
        if (status != null) {
            kotlinx.coroutines.delay(10_000)
            status = null
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun formatModified(epochMs: Long): String {
    if (epochMs <= 0) return "?"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    return fmt.format(Date(epochMs))
}
