package com.example.tail.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.ui.common.rememberSectionExpansion
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.ResticSnapshotUi
import com.example.tail.ui.viewmodel.loadResticSnapshots
import com.example.tail.ui.viewmodel.restoreResticSnapshot
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Restore from PC archive (restic)" UI block, rendered inside Settings →
 * Backup & Restore, below the on-phone snapshot section.
 *
 * This is the disaster-recovery tier BEHIND the phone's automatic private
 * snapshots: the desktop archives the habitsdb syncthing folder with restic
 * (deduplicated, GFS retention — months of history), and this section lists
 * those snapshots through the tail bridge and restores any of them over the
 * configured habits file using the app's standard confirmed restore path.
 * No scripts, no PC-side manual steps — the whole flow lives in the app.
 */
@Composable
fun ResticRestoreSection(viewModel: HabitViewModel) {
    val snapshots by viewModel.resticSnapshots.collectAsState()
    val status by viewModel.resticStatus.collectAsState()
    var expanded by rememberSectionExpansion("settings", "PC archive (restic)", false)
    var confirmFor by remember { mutableStateOf<ResticSnapshotUi?>(null) }

    val timeFmt = remember {
        DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.getDefault())
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Restore from PC archive (restic)",
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "The desktop keeps deduplicated restic backups of the " +
                "habits database with months of history (daily/weekly/" +
                "monthly/yearly). If this phone's file and its private " +
                "snapshots are both unusable, any archive snapshot can be " +
                "restored from here over the bridge.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(onClick = {
            expanded = !expanded
            if (expanded) viewModel.loadResticSnapshots()
        }) {
            Text(if (expanded) "Hide archive snapshots" else "Show archive snapshots")
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
                    text = "No archive snapshots listed (bridge offline or none yet).",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Parent Settings screen is a LazyColumn; cap rendered rows.
                snapshots.take(30).forEach { snap ->
                    ArchiveSnapshotRow(
                        label = formatResticTime(snap.time, timeFmt),
                        id = snap.id,
                        onRestore = { confirmFor = snap }
                    )
                }
                if (snapshots.size > 30) {
                    Text(
                        text = "…and ${snapshots.size - 30} older archive snapshots.",
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
            title = { Text("Restore from PC archive?") },
            text = {
                Text(
                    "This replaces the habits database on this phone with the " +
                        "archive snapshot from ${formatResticTime(snap.time, timeFmt)} " +
                        "(id ${snap.id}). Data added after that snapshot is lost."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFor = null
                    viewModel.restoreResticSnapshot(snap)
                }) { Text("Restore") }
            },
            dismissButton = {
                TextButton(onClick = { confirmFor = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ArchiveSnapshotRow(label: String, id: String, onRestore: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Spacer(modifier = Modifier.height(4.dp))
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, fontSize = 13.sp)
                Text(
                    text = id,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onRestore) { Text("Restore") }
        }
    }
}

/** restic times are ISO-8601 with offset, e.g. 2026-10-19T16:07:53+03:00. */
private fun formatResticTime(
    iso: String,
    fmt: DateTimeFormatter
): String = try {
    fmt.format(OffsetDateTime.parse(iso))
} catch (_: Exception) {
    iso.take(19)
}
