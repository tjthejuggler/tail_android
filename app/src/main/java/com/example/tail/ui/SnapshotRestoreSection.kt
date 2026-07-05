package com.example.tail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Restore from snapshot" UI block, rendered inside Settings → Backup & Restore.
 *
 * These snapshots are the automatic, per-change safety net written to the app's
 * PRIVATE internal storage by [com.example.tail.data.backup.HabitsSnapshotManager]
 * every time the habits DB is saved. They are the recovery path for the
 * truncated-file wipe: pick a point with the expected entry count and tap
 * Restore to write it back over habitsdb.txt.
 *
 * Kept in its own file (< 500 lines rule) and driven entirely through
 * [HabitViewModel] so it has no direct dependency on the repositories.
 */
@Composable
fun SnapshotRestoreSection(viewModel: HabitViewModel) {
    val snapshots by viewModel.snapshots.collectAsState()
    val status by viewModel.snapshotStatus.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    var confirmFor by remember { mutableStateOf<HabitViewModel.SnapshotUi?>(null) }

    val dateFmt = remember { SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Automatic snapshots (wipe recovery)",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Every change to the habits database is snapshotted to this " +
                "phone's private storage, immune to file corruption. If the DB " +
                "is ever wiped, restore the newest snapshot with the expected " +
                "point count.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(onClick = {
            expanded = !expanded
            if (expanded) viewModel.loadSnapshots()
        }) {
            Text(if (expanded) "Hide snapshots" else "Show snapshots")
        }

        status?.let { msg ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = msg,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(8.dp))
            if (snapshots.isEmpty()) {
                Text(
                    text = "No snapshots yet. One is created on the next DB change.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Simple non-scrolling column: the parent Settings screen is a
                // LazyColumn, so we cap the number rendered to keep it light.
                snapshots.take(50).forEach { snap ->
                    SnapshotRow(
                        label = dateFmt.format(Date(snap.timestamp)),
                        entryCount = snap.entryCount,
                        sizeKb = (snap.sizeBytes / 1024).coerceAtLeast(0),
                        onRestore = { confirmFor = snap }
                    )
                }
                if (snapshots.size > 50) {
                    Text(
                        text = "…and ${snapshots.size - 50} older snapshots.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    confirmFor?.let { snap ->
        AlertDialog(
            onDismissRequest = { confirmFor = null },
            title = { Text("Restore this snapshot?") },
            text = {
                Text(
                    "This will overwrite the current habits database with the " +
                        "snapshot from ${dateFmt.format(Date(snap.timestamp))} " +
                        "(${snap.entryCount} entries). Your current data is " +
                        "snapshotted first, so this is reversible."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.restoreSnapshot(snap.fileName)
                    confirmFor = null
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFor = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun SnapshotRow(
    label: String,
    entryCount: Int,
    sizeKb: Long,
    onRestore: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "$entryCount entries · ${sizeKb} KB",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        OutlinedButton(onClick = onRestore) { Text("Restore") }
    }
}
