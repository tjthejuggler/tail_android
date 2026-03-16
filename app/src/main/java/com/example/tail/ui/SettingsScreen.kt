package com.example.tail.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Settings screen: two file pickers only.
 *  1. habitsdb.txt — the single unified habit database (read+write, synced via Syncthing)
 *  2. screens_layout.json — UI layout relay file shared with the PC widget (read+write)
 *
 * Per-habit settings (custom input toggle, etc.) are in the edit mode panel on the main screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: HabitViewModel,
    onNavigateBack: () -> Unit
) {
    val settings by viewModel.settings.collectAsState()
    val context = LocalContext.current

    // Picker for habitsdb.txt — needs read+write so the app can increment habits
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setFileUri(uri)
        }
    }

    // Picker for the Tasker stats txt file — needs read+write so the app can overwrite it
    val taskerFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setTaskerFileUri(uri)
        }
    }

    // Picker for screens_layout.json — needs read+write so the app can update the relay file
    val screensRelayFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setScreensRelayFileUri(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            // ── Habit database file ──────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Habit Database (habitsdb.txt)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "The unified habit database shared between this device and the PC via Syncthing. " +
                           "Both devices read and write this single file.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (settings.fileUri.isEmpty()) "No file selected"
                           else settings.fileUri,
                    fontSize = 12.sp,
                    color = if (settings.fileUri.isEmpty())
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                    Text("Change File")
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Screens relay file ───────────────────────────────────────────
            item {
                Text("Screens Layout (screens_layout.json)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "Shared with the PC widget to keep screen names and habit arrangement in sync. " +
                           "Pick the screens_layout.json file in your noteVault/tail/ folder. " +
                           "The app writes to it whenever you add, rename, or rearrange screens.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (settings.screensRelayFileUri.isEmpty()) "No file selected (PC widget won't sync screens)"
                           else settings.screensRelayFileUri,
                    fontSize = 12.sp,
                    color = if (settings.screensRelayFileUri.isEmpty())
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { screensRelayFilePicker.launch(arrayOf("*/*")) }) {
                    Text("Change Relay File")
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Tasker stats file ────────────────────────────────────────────
            item {
                Text("Tasker Stats File (total_habits.txt)", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "A simple txt file Tasker can read for habit stats. " +
                           "Updated after every habit count change. " +
                           "Format: today=N / avg7=X.XX / avg30=X.XX",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (settings.taskerFileUri.isEmpty()) "No file selected"
                           else settings.taskerFileUri,
                    fontSize = 12.sp,
                    color = if (settings.taskerFileUri.isEmpty())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { taskerFilePicker.launch(arrayOf("*/*")) }) {
                    Text("Change Tasker File")
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Per-habit settings hint ──────────────────────────────────────
            item {
                Text(
                    text = "Per-habit settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "To change settings for a specific habit (e.g. custom input mode), " +
                           "go back to the main screen, tap the ✏ edit button, then tap the habit " +
                           "you want to configure.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
