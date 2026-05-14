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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.backup.AutoBackupManager
import com.example.tail.data.backup.BackupManager
import com.example.tail.data.backup.BackupResult
import kotlinx.coroutines.launch

/**
 * "Backup & Restore" section of the Settings screen.
 *
 * - **Export Backup** opens the system "create document" picker so the user
 *   chooses where to save the JSON bundle. The file name defaults to
 *   `tail_backup_<ISO timestamp>.json`.
 * - **Import Backup** opens the system "open document" picker. Before applying
 *   the backup we show a confirmation dialog because import OVERWRITES every
 *   user-editable data source.
 *
 * Status of the most recent operation is shown inline in muted text.
 */
@Composable
fun BackupSettingsSection(
    backupManager: BackupManager,
    autoBackupManager: AutoBackupManager
) {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var pendingImportUri by remember { mutableStateOf<Uri?>(null) }
    var inProgress by remember { mutableStateOf(false) }

    val createDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Persist write permission so we can stream into it.
        runCatching {
            // CreateDocument grants write by default but persisting is harmless
            val flags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            // contentResolver.takePersistableUriPermission(uri, flags) — only valid for SAF tree URIs
            // CreateDocument single-file URIs already include the write grant for this Activity.
            flags
        }
        inProgress = true
        scope.launch {
            val res = backupManager.exportBackup(uri)
            inProgress = false
            when (res) {
                is BackupResult.Success -> {
                    status = res.message
                    statusIsError = false
                }
                is BackupResult.Failure -> {
                    status = res.message
                    statusIsError = true
                }
            }
        }
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingImportUri = uri
        }
    }

    Column {
        Text("Backup & Restore", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Export everything you've entered into Tail — habits, settings, advice, " +
                    "locations, ignored countries, AI icons, debug notes, per-habit text logs, " +
                    "subtype data, timed sessions, and more — to a single JSON file. " +
                    "Importing OVERWRITES current data on this device.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                enabled = !inProgress,
                onClick = {
                    status = null
                    createDocLauncher.launch(backupManager.suggestedFileName())
                }
            ) {
                Text(if (inProgress) "Working…" else "Export Backup")
            }
            OutlinedButton(
                enabled = !inProgress,
                onClick = {
                    status = null
                    openDocLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
                }
            ) {
                Text("Import Backup")
            }
        }

        status?.let {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = it,
                fontSize = 12.sp,
                color = if (statusIsError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ── Automatic daily backup sub-section ─────────────────────────────
        // Added after the near-total data-wipe incident. Even if the
        // anti-shrinkage guard in HabitsRepository prevents the wipe, an
        // automatic dated backup gives the user a known-good rollback target.
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        AutoBackupSection(autoBackupManager = autoBackupManager)
    }

    // Confirmation dialog before applying an import — destructive op, must be opt-in.
    val uriToImport = pendingImportUri
    if (uriToImport != null) {
        ImportConfirmDialog(
            onCancel = { pendingImportUri = null },
            onConfirm = {
                pendingImportUri = null
                inProgress = true
                scope.launch {
                    val res = backupManager.importBackup(uriToImport)
                    inProgress = false
                    when (res) {
                        is BackupResult.Success -> {
                            status = res.message + " — restart the app to refresh all screens."
                            statusIsError = false
                        }
                        is BackupResult.Failure -> {
                            status = res.message
                            statusIsError = true
                        }
                    }
                }
            }
        )
    }

    // Auto-clear status after a while so it doesn't linger forever.
    LaunchedEffect(status) {
        if (status != null) {
            kotlinx.coroutines.delay(8_000)
            status = null
        }
    }
}

@Composable
private fun ImportConfirmDialog(
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Import backup?") },
        text = {
            Text(
                "This will OVERWRITE every habit, setting, location, advice item, " +
                        "AI icon, debug note, and per-habit log on this device with the " +
                        "contents of the backup file. This cannot be undone. Continue?"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("Overwrite") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
