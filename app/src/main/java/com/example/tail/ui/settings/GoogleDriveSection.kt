package com.example.tail.ui

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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.backup.GDriveBackupEntry
import com.example.tail.data.backup.GoogleDriveManager
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.launch

/**
 * "Google Drive Backup" UI block, rendered inside Settings → Backup & Restore.
 *
 * Features:
 *   - **Google login section**: "Sign in with Google" (account picker with
 *     Drive consent), shows the signed-in account, "Sign out".
 *   - **Auto daily backup toggle**: on the first launch of each day Tail
 *     uploads one full backup (`tail_gdrive_backup_YYYY-MM-DD.json`) to Drive.
 *   - **Manual backup**: "Back up to Drive now" uploads a timestamped copy.
 *   - **Restore from Drive**: lists Drive backups, restores the chosen one
 *     through the standard (confirmable) full-restore path.
 */
@Composable
fun GoogleDriveSection(
    gdriveManager: GoogleDriveManager
) {
    val scope = rememberCoroutineScope()
    val config by gdriveManager.configFlow.collectAsState(
        initial = com.example.tail.data.backup.GDriveConfig()
    )

    var signedInAccount by remember { mutableStateOf(gdriveManager.currentAccount()?.email) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }

    var showRestoreList by remember { mutableStateOf(false) }
    var driveEntries by remember { mutableStateOf<List<GDriveBackupEntry>>(emptyList()) }
    var entriesLoading by remember { mutableStateOf(false) }
    var pendingRestore by remember { mutableStateOf<GDriveBackupEntry?>(null) }
    var pendingDelete by remember { mutableStateOf<GDriveBackupEntry?>(null) }

    fun setStatus(msg: String, error: Boolean = false) {
        status = msg
        statusIsError = error
    }

    val consentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        setStatus("Drive permission step finished — try the action again.")
    }

    /** Launches the pending Drive-consent screen, if one is waiting. */
    fun launchPendingConsent(): Boolean {
        val intent = gdriveManager.pendingConsentIntent ?: return false
        consentLauncher.launch(intent)
        return true
    }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        busy = true
        scope.launch {
            val outcome = gdriveManager.handleSignInResult(result.data)
            busy = false
            signedInAccount = outcome.account?.email
            val email = outcome.account?.email
            when {
                !email.isNullOrBlank() ->
                    setStatus("Signed in as $email.")
                outcome.cancelled ->
                    setStatus("Sign-in cancelled.")
                outcome.errorCode == CommonStatusCodes.DEVELOPER_ERROR ->
                    setStatus(
                        "Sign-in failed (code 10: DEVELOPER_ERROR). Google does not " +
                                "recognize this app yet: register package com.example.tail " +
                                "with its signing SHA-1 in Google Cloud Console — see " +
                                "README section 'Google Drive backup setup'.",
                        error = true
                    )
                else ->
                    setStatus(outcome.errorMessage ?: "Sign-in failed.", error = true)
            }
        }
    }

    val isSignedIn = !signedInAccount.isNullOrBlank()

    Column {
        Text("Google Drive Backup", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(
            text = "Upload full backups to your Google Drive (they appear in " +
                    "Drive as files created by Tail) and restore them on any device. " +
                    "Uses your Google account — sign in below.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))

        // ── Google login section ─────────────────────────────────────────
        if (isSignedIn) {
            Text(
                text = "Signed in: $signedInAccount",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Text(
                text = "Not signed in.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (isSignedIn) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            gdriveManager.signOut()
                            busy = false
                            signedInAccount = null
                            setStatus("Signed out of Google.")
                        }
                    }
                ) { Text("Sign out") }
            } else {
                Button(
                    enabled = !busy,
                    onClick = { signInLauncher.launch(gdriveManager.signInIntent()) }
                ) { Text(if (busy) "Working…" else "Sign in with Google") }
            }
        }

        if (isSignedIn) {
            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(6.dp))

            // ── Auto daily backup toggle ─────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Automatic daily Drive backup", fontSize = 12.sp)
                    Text(
                        text = "Last Drive backup: ${
                            if (config.lastBackupDate.isBlank()) "never" else config.lastBackupDate
                        }",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = config.autoEnabled,
                    onCheckedChange = { on ->
                        scope.launch { gdriveManager.saveAutoEnabled(on) }
                    }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            // ── Manual backup / restore ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        setStatus("Uploading to Google Drive…")
                        scope.launch {
                            val res = gdriveManager.backupNow()
                            busy = false
                            res.fold(
                                onSuccess = { setStatus("Uploaded $it to Drive.") },
                                onFailure = {
                                    if (launchPendingConsent()) {
                                        setStatus("Google needs you to approve Drive access…")
                                    } else {
                                        setStatus("Drive backup failed: ${it.message}", error = true)
                                    }
                                }
                            )
                        }
                    }
                ) { Text(if (busy) "Working…" else "Back up to Drive now") }

                OutlinedButton(
                    enabled = !busy,
                    onClick = {
                        showRestoreList = !showRestoreList
                        if (showRestoreList) {
                            entriesLoading = true
                            scope.launch {
                                gdriveManager.listBackups().fold(
                                    onSuccess = { driveEntries = it },
                                    onFailure = {
                                        driveEntries = emptyList()
                                        if (launchPendingConsent()) {
                                            setStatus("Google needs you to approve Drive access…")
                                        } else {
                                            setStatus("Could not list Drive backups: ${it.message}", error = true)
                                        }
                                    }
                                )
                                entriesLoading = false
                            }
                        }
                    }
                ) { Text(if (showRestoreList) "Hide Drive backups" else "Restore / manage") }
            }

            if (showRestoreList) {
                Spacer(modifier = Modifier.height(6.dp))
                if (entriesLoading) {
                    Text("Loading…", fontSize = 11.sp)
                } else if (driveEntries.isEmpty()) {
                    Text(
                        "No backups in Google Drive yet.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        for (entry in driveEntries) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(entry.name, fontSize = 11.sp)
                                    Text(
                                        "${formatDriveSize(entry.sizeBytes)} • ${
                                            entry.createdTime.substringBefore('T')
                                        }",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = { pendingRestore = entry }) {
                                    Text("Restore", fontSize = 11.sp)
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

        status?.let {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = it,
                fontSize = 11.sp,
                color = if (statusIsError) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // Restore confirmation — destructive op.
    val toRestore = pendingRestore
    if (toRestore != null) {
        AlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text("Restore from Drive?") },
            text = {
                Text(
                    "This will OVERWRITE every habit, setting, location, advice item, " +
                            "AI icon, debug note, per-habit log, and meal log on this " +
                            "device with ${toRestore.name}. This cannot be undone. Continue?"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingRestore = null
                    busy = true
                    setStatus("Downloading & restoring…")
                    scope.launch {
                        val res = gdriveManager.restoreFromDrive(toRestore.fileId)
                        busy = false
                        when (res) {
                            is com.example.tail.data.backup.BackupResult.Success ->
                                setStatus("${res.message} — restart the app to refresh all screens.")
                            is com.example.tail.data.backup.BackupResult.Failure ->
                                setStatus(res.message, error = true)
                        }
                    }
                }) { Text("Overwrite") }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestore = null }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation — destructive op.
    val toDelete = pendingDelete
    if (toDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete Drive backup?") },
            text = { Text("Permanently delete ${toDelete.name} from Google Drive?") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    scope.launch {
                        val ok = gdriveManager.deleteBackup(toDelete.fileId)
                        setStatus(
                            if (ok) "Deleted ${toDelete.name}"
                            else "Failed to delete ${toDelete.name}",
                            error = !ok
                        )
                        gdriveManager.listBackups().onSuccess { driveEntries = it }
                    }
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            }
        )
    }

    // Auto-clear status after a while.
    LaunchedEffect(status) {
        if (status != null) {
            kotlinx.coroutines.delay(10_000)
            status = null
        }
    }
}

private fun formatDriveSize(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}
