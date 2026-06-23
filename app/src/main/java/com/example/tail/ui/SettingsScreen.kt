package com.example.tail.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import com.example.tail.data.GarminType
import com.example.tail.data.ImportResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import com.example.tail.data.AiModelInfo
import com.example.tail.data.ChessComType
import com.example.tail.data.backup.AutoBackupManager
import com.example.tail.data.backup.BackupManager
import com.example.tail.data.debug.DebugPreferences
import com.example.tail.ui.AdviceDialog
import com.example.tail.ui.AdviceViewModel

/**
 * Grayscale color scheme for the Settings screen.
 * All colors are black/white/gray to create a monochrome look
 * while maintaining text visibility through proper contrast.
 */
@Composable
private fun settingsGrayscaleScheme(): ColorScheme {
    return if (isSystemInDarkTheme()) {
        darkColorScheme(
            primary = Color(0xFFB0B0B0),
            onPrimary = Color.Black,
            primaryContainer = Color(0xFF333333),
            onPrimaryContainer = Color(0xFFE0E0E0),
            secondary = Color(0xFF888888),
            onSecondary = Color.Black,
            secondaryContainer = Color(0xFF333333),
            onSecondaryContainer = Color(0xFFE0E0E0),
            tertiary = Color(0xFF888888),
            onTertiary = Color.Black,
            background = Color(0xFF1A1A1A),
            onBackground = Color(0xFFE0E0E0),
            surface = Color(0xFF1A1A1A),
            onSurface = Color(0xFFE0E0E0),
            surfaceVariant = Color(0xFF2A2A2A),
            onSurfaceVariant = Color(0xFFAAAAAA),
            outline = Color(0xFF666666),
            outlineVariant = Color(0xFF444444),
            error = Color(0xFFCCCCCC),
            onError = Color.Black,
            errorContainer = Color(0xFF333333),
            onErrorContainer = Color(0xFFCCCCCC),
            surfaceTint = Color(0xFF808080),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF444444),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFD0D0D0),
            onPrimaryContainer = Color(0xFF1A1A1A),
            secondary = Color(0xFF666666),
            onSecondary = Color.White,
            secondaryContainer = Color(0xFFD0D0D0),
            onSecondaryContainer = Color(0xFF1A1A1A),
            tertiary = Color(0xFF666666),
            onTertiary = Color.White,
            background = Color.White,
            onBackground = Color(0xFF1A1A1A),
            surface = Color.White,
            onSurface = Color(0xFF1A1A1A),
            surfaceVariant = Color(0xFFF5F5F5),
            onSurfaceVariant = Color(0xFF666666),
            outline = Color(0xFF999999),
            outlineVariant = Color(0xFFD0D0D0),
            error = Color(0xFF333333),
            onError = Color.White,
            errorContainer = Color(0xFFE0E0E0),
            onErrorContainer = Color(0xFF333333),
            surfaceTint = Color(0xFF808080),
        )
    }
}

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
    adviceViewModel: AdviceViewModel,
    debugPrefs: DebugPreferences,
    backupManager: BackupManager,
    autoBackupManager: AutoBackupManager,
    onNavigateBack: () -> Unit,
    onNavigateToAppStats: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsState()
    val debugSnapshot by debugPrefs.snapshot.collectAsState()
    val adviceState by adviceViewModel.state.collectAsState()
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

    // Picker for debug output directory — needs read+write for debug_tail.json
    val debugDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            debugPrefs.debugFileDirUri = uri.toString()
        }
    }

    MaterialTheme(colorScheme = settingsGrayscaleScheme()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToAppStats) {
                        Icon(Icons.Filled.BarChart, contentDescription = "App Stats")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
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
                Text(
                    text = "If the totals look wrong (e.g. after a Garmin import), tap below to " +
                           "rewrite the file now. \"Don't affect points\" habits are excluded.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { viewModel.refreshTaskerStatsFile() },
                    enabled = settings.taskerFileUri.isNotEmpty()
                ) {
                    Text("🔄 Recalculate Stats File Now")
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }


            // ── AI Icon Generation ────────────────────────────────────────────
            item {
                AiIconSettingsSection(viewModel = viewModel, settings = settings)
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Chess.com Integration ─────────────────────────────────────────
            item {
                ChessComSettingsSection(viewModel = viewModel, settings = settings)
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Garmin Integration ────────────────────────────────────────────
            item {
                GarminSettingsSection(viewModel = viewModel, settings = settings, context = context)
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Voice Trigger ────────────────────────────────────────────────
            item {
                VoiceTriggerSettingsSection(viewModel = viewModel, settings = settings)
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Voice Note Dictation ─────────────────────────────────────────
            item {
                VoiceNoteSettingsSection(viewModel = viewModel, settings = settings)
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Advice Banner ─────────────────────────────────────────────────
            item {
                val adviceCount = adviceState.items.size
                var showAdviceDialog by remember { mutableStateOf(false) }
                Text("Advice Banner", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "Add reminders or tips that appear at the top of the habits screen. " +
                           "Swipe left/right on the banner to cycle through advice.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (adviceCount == 0) "No advice set"
                    else "$adviceCount piece${if (adviceCount != 1) "s" else ""} of advice",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { showAdviceDialog = true }) {
                    Text("Manage Advice")
                }
                if (showAdviceDialog) {
                    AdviceDialog(
                        adviceList = adviceState.items,
                        onAdd = { text -> adviceViewModel.addAdvice(text) },
                        onUpdate = { entity, text -> adviceViewModel.updateAdvice(entity, text) },
                        onDelete = { id -> adviceViewModel.deleteAdvice(id) },
                        onDismiss = { showAdviceDialog = false }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Backup & Restore ─────────────────────────────────────────────
            item {
                BackupSettingsSection(
                    backupManager = backupManager,
                    autoBackupManager = autoBackupManager
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
            }

            // ── Debug Mode ───────────────────────────────────────────────────
            item {
                DebugModeCard(
                    debugModeEnabled = debugSnapshot.debugModeEnabled,
                    debugFileDirUri = debugSnapshot.debugFileDirUri,
                    onToggleDebugMode = { debugPrefs.debugModeEnabled = it },
                    onChooseDirectory = { debugDirLauncher.launch(null) },
                    onClearDirectory = { debugPrefs.debugFileDirUri = "" }
                )
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
    } // closes grayscale MaterialTheme
}

/**
 * AI Icon Generation settings section — toggle + API configuration fields.
 * Model and quality are dropdowns. Fetch Models button loads from API.
 */
@Composable
private fun AiIconSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    val aiModels by viewModel.aiModels.collectAsState()

    var enabled by remember(settings.aiIconsEnabled) { mutableStateOf(settings.aiIconsEnabled) }
    var apiKey by remember(settings.aiIconsApiKey) { mutableStateOf(settings.aiIconsApiKey) }
    var baseUrl by remember(settings.aiIconsBaseUrl) { mutableStateOf(settings.aiIconsBaseUrl) }
    var endpoint by remember(settings.aiIconsEndpoint) { mutableStateOf(settings.aiIconsEndpoint) }
    var model by remember(settings.aiIconsModel) { mutableStateOf(settings.aiIconsModel) }
    var quality by remember(settings.aiIconsQuality) { mutableStateOf(settings.aiIconsQuality) }
    var showApiKey by remember { mutableStateOf(false) }
    var modelDropdownExpanded by remember { mutableStateOf(false) }
    var qualityDropdownExpanded by remember { mutableStateOf(false) }

    fun save() {
        viewModel.saveAiIconSettings(enabled, apiKey, baseUrl, endpoint, model, quality)
    }

    // Find the selected model's info for quality options
    val selectedModelInfo = aiModels.find { it.id == model }
    val qualityOptions = selectedModelInfo?.qualities ?: emptyList()

    Column {
        Text("AI Icon Generation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Enable AI-generated icons for habits. When enabled, the icon picker " +
                   "will show a section for generating new icons via an image generation API.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable AI Icons", fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { newVal ->
                    enabled = newVal
                    save()
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // API Key
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API Key") },
                placeholder = { Text("sk-...") },
                singleLine = true,
                visualTransformation = if (showApiKey) VisualTransformation.None
                                       else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showApiKey, onCheckedChange = { showApiKey = it })
                Spacer(modifier = Modifier.width(4.dp))
                Text("Show API key", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.height(4.dp))

            // Base URL
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                placeholder = { Text("https://api.ppq.ai") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Endpoint
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                label = { Text("Endpoint") },
                placeholder = { Text("/v1/images/generations") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Fetch Models button
            Button(
                onClick = { viewModel.fetchAiModels() },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
            ) {
                Text("Fetch Models from API", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Model dropdown
            Text("Model", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Box {
                OutlinedTextField(
                    value = aiModels.find { it.id == model }?.let { "${it.name} (${it.id})" }
                        ?: model.ifEmpty { "Select a model" },
                    onValueChange = {},
                    readOnly = true,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { modelDropdownExpanded = true },
                    enabled = false,
                    colors = OutlinedTextFieldDefaults.colors(
                        disabledTextColor = MaterialTheme.colorScheme.onSurface,
                        disabledBorderColor = MaterialTheme.colorScheme.outline,
                        disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )
                // Invisible clickable overlay
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .clickable { modelDropdownExpanded = true }
                )
                DropdownMenu(
                    expanded = modelDropdownExpanded,
                    onDismissRequest = { modelDropdownExpanded = false }
                ) {
                    aiModels.forEach { m ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(m.name, fontSize = 14.sp)
                                    Text(
                                        text = buildString {
                                            append(m.id)
                                            if (m.pricing.isNotEmpty()) append(" • ${m.pricing}")
                                            if (m.qualities.isNotEmpty()) append(" • quality: ${m.qualities.joinToString(", ")}")
                                        },
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                model = m.id
                                // Reset quality if not available for this model
                                if (quality !in m.qualities) {
                                    quality = m.qualities.firstOrNull() ?: ""
                                }
                                modelDropdownExpanded = false
                                save()
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            // Quality dropdown (only if model has quality options)
            if (qualityOptions.isNotEmpty()) {
                Text("Quality", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Box {
                    OutlinedTextField(
                        value = quality.ifEmpty { qualityOptions.first() },
                        onValueChange = {},
                        readOnly = true,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = false,
                        colors = OutlinedTextFieldDefaults.colors(
                            disabledTextColor = MaterialTheme.colorScheme.onSurface,
                            disabledBorderColor = MaterialTheme.colorScheme.outline,
                            disabledLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .clickable { qualityDropdownExpanded = true }
                    )
                    DropdownMenu(
                        expanded = qualityDropdownExpanded,
                        onDismissRequest = { qualityDropdownExpanded = false }
                    ) {
                        qualityOptions.forEach { q ->
                            DropdownMenuItem(
                                text = { Text(q) },
                                onClick = {
                                    quality = q
                                    qualityDropdownExpanded = false
                                    save()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { save() }) {
                Text("Save AI Settings")
            }
        }
    }
}

/**
 * Chess.com Integration settings section — toggle, username, minutes-per-increment
 * for each game/puzzle type, and a "Fetch Backlog" button.
 */
@Composable
private fun ChessComSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    val chessComSyncStatus by viewModel.chessComSyncStatus.collectAsState()

    var enabled by remember(settings.chessComEnabled) { mutableStateOf(settings.chessComEnabled) }
    var username by remember(settings.chessComUsername) { mutableStateOf(settings.chessComUsername) }

    // Minutes per increment for each type — individual mutableStateOf for recomposition
    val types = ChessComType.entries
    var bulletMin by remember(settings.chessComMinutesPerIncrement) {
        mutableStateOf((settings.chessComMinutesPerIncrement["BULLET"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var blitzMin by remember(settings.chessComMinutesPerIncrement) {
        mutableStateOf((settings.chessComMinutesPerIncrement["BLITZ"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var rapidMin by remember(settings.chessComMinutesPerIncrement) {
        mutableStateOf((settings.chessComMinutesPerIncrement["RAPID"] ?: 0).let { if (it == 0) "" else it.toString() })
    }

    fun getMinFor(type: ChessComType): String = when (type) {
        ChessComType.BULLET -> bulletMin
        ChessComType.BLITZ -> blitzMin
        ChessComType.RAPID -> rapidMin
    }

    fun setMinFor(type: ChessComType, value: String) {
        val filtered = value.filter { it.isDigit() }
        when (type) {
            ChessComType.BULLET -> bulletMin = filtered
            ChessComType.BLITZ -> blitzMin = filtered
            ChessComType.RAPID -> rapidMin = filtered
        }
    }

    fun save() {
        val minutesMap = mutableMapOf<String, Int>()
        types.forEach { type ->
            val value = getMinFor(type).toIntOrNull() ?: 0
            if (value > 0) minutesMap[type.name] = value
        }
        viewModel.saveChessComSettings(enabled, username, minutesMap)
    }

    Column {
        Text("♟ Chess.com Integration", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Link habits to your chess.com activity. Games and puzzles are " +
                   "automatically tracked and converted to habit increments.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable Chess.com", fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { newVal ->
                    enabled = newVal
                    save()
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // Username
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("Chess.com Username") },
                placeholder = { Text("e.g. hikaru") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = { save() }) {
                Text("Save Username", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Minutes per increment for each type
            Text(
                "Minutes per Increment",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Set how many minutes of each activity type equals one habit increment. " +
                       "Leave blank or 0 to disable that type.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            types.forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = type.label,
                        fontSize = 13.sp,
                        modifier = Modifier.width(120.dp)
                    )
                    OutlinedTextField(
                        value = getMinFor(type),
                        onValueChange = { newVal -> setMinFor(type, newVal) },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("min", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { save() }) {
                Text("Save Chess.com Settings", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Sync status
            if (chessComSyncStatus.isNotEmpty()) {
                Text(
                    text = chessComSyncStatus,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Backlog fetch button
            Text(
                "Backlog Sync",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Fetch your entire chess.com game history and retroactively " +
                       "fill in habit data for all past days. This may take a while " +
                       "for accounts with many games.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { viewModel.fetchChessComBacklog() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Fetch Entire Backlog", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Garmin Integration settings section — proxy URL, app token, and thresholds.
 */
@Composable
private fun GarminSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings,
    context: Context
) {
    val garminSyncStatus by viewModel.garminSyncStatus.collectAsState()

    var enabled by remember(settings.garminEnabled) { mutableStateOf(settings.garminEnabled) }
    var proxyUrl by remember(settings.garminProxyUrl) { mutableStateOf(settings.garminProxyUrl) }
    var appToken by remember(settings.garminAppToken) { mutableStateOf(settings.garminAppToken) }
    var dateOfBirth by remember(settings.garminDateOfBirth) { mutableStateOf(settings.garminDateOfBirth) }

    // Thresholds for each type — individual mutableStateOf for recomposition
    val types = GarminType.entries
    var vo2MaxThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["VO2_MAX"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var fitnessAgeThreshold by remember(settings.garminThresholds) {
        // Fitness age is stored as hundredths of a year, display with 2 decimal places
        mutableStateOf((settings.garminThresholds["FITNESS_AGE"] ?: 0).let {
            if (it == 0) "" else {
                val years = it / 100.0
                String.format("%.2f", years)
            }
        })
    }
    var fitnessAgeDistanceThreshold by remember(settings.garminThresholds) {
        // Fitness age distance is stored as hundredths of a year, display with 2 decimal places
        mutableStateOf((settings.garminThresholds["FITNESS_AGE_DISTANCE"] ?: 0).let {
            if (it == 0) "" else {
                val years = it / 100.0
                String.format("%.2f", years)
            }
        })
    }
    var restingHrThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["RESTING_HR"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var hrvLastNightThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["HRV_LAST_NIGHT"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var hrvWeeklyAvgThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["HRV_WEEKLY_AVG"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var sleepScoreThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["SLEEP_SCORE"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var stepsThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["STEPS"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var altitudeAscentThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["ALTITUDE_ASCENT_METERS"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var distanceThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["DISTANCE_METERS"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var caloriesThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["CALORIES"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var activeMinutesThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["ACTIVE_MINUTES"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var floorsClimbedThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["FLOORS_CLIMBED"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var minHrThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["MIN_HR"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var maxHrThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["MAX_HR"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var stressLevelThreshold by remember(settings.garminThresholds) {
        mutableStateOf((settings.garminThresholds["STRESS_LEVEL"] ?: 0).let { if (it == 0) "" else it.toString() })
    }

    fun getThresholdFor(type: GarminType): String = when (type) {
        GarminType.VO2_MAX -> vo2MaxThreshold
        GarminType.FITNESS_AGE -> fitnessAgeThreshold
        GarminType.FITNESS_AGE_DISTANCE -> fitnessAgeDistanceThreshold
        GarminType.RESTING_HR -> restingHrThreshold
        GarminType.HRV_LAST_NIGHT -> hrvLastNightThreshold
        GarminType.HRV_WEEKLY_AVG -> hrvWeeklyAvgThreshold
        GarminType.SLEEP_SCORE -> sleepScoreThreshold
        GarminType.STEPS -> stepsThreshold
        GarminType.ALTITUDE_ASCENT_METERS -> altitudeAscentThreshold
        GarminType.DISTANCE_METERS -> distanceThreshold
        GarminType.CALORIES -> caloriesThreshold
        GarminType.ACTIVE_MINUTES -> activeMinutesThreshold
        GarminType.FLOORS_CLIMBED -> floorsClimbedThreshold
        GarminType.MIN_HR -> minHrThreshold
        GarminType.MAX_HR -> maxHrThreshold
        GarminType.STRESS_LEVEL -> stressLevelThreshold
    }

    fun setThresholdFor(type: GarminType, value: String) {
        // For FITNESS_AGE_DISTANCE, allow negative numbers
        // For FITNESS_AGE and FITNESS_AGE_DISTANCE, allow decimal point
        val filtered = when (type) {
            GarminType.FITNESS_AGE_DISTANCE -> value.filter { it.isDigit() || it == '-' || it == '.' }
            GarminType.FITNESS_AGE -> value.filter { it.isDigit() || it == '.' }
            else -> value.filter { it.isDigit() }
        }
        when (type) {
            GarminType.VO2_MAX -> vo2MaxThreshold = filtered
            GarminType.FITNESS_AGE -> fitnessAgeThreshold = filtered
            GarminType.FITNESS_AGE_DISTANCE -> fitnessAgeDistanceThreshold = filtered
            GarminType.RESTING_HR -> restingHrThreshold = filtered
            GarminType.HRV_LAST_NIGHT -> hrvLastNightThreshold = filtered
            GarminType.HRV_WEEKLY_AVG -> hrvWeeklyAvgThreshold = filtered
            GarminType.SLEEP_SCORE -> sleepScoreThreshold = filtered
            GarminType.STEPS -> stepsThreshold = filtered
            GarminType.ALTITUDE_ASCENT_METERS -> altitudeAscentThreshold = filtered
            GarminType.DISTANCE_METERS -> distanceThreshold = filtered
            GarminType.CALORIES -> caloriesThreshold = filtered
            GarminType.ACTIVE_MINUTES -> activeMinutesThreshold = filtered
            GarminType.FLOORS_CLIMBED -> floorsClimbedThreshold = filtered
            GarminType.MIN_HR -> minHrThreshold = filtered
            GarminType.MAX_HR -> maxHrThreshold = filtered
            GarminType.STRESS_LEVEL -> stressLevelThreshold = filtered
        }
    }

    fun save() {
        val thresholdsMap = mutableMapOf<String, Int>()
        types.forEach { type ->
            val rawValue = getThresholdFor(type)
            val value = when (type) {
                GarminType.FITNESS_AGE, GarminType.FITNESS_AGE_DISTANCE -> {
                    // Convert decimal input to hundredths of a year (e.g., "37.04" -> 3704, "37.5" -> 3750)
                    val parsed = rawValue.toDoubleOrNull() ?: 0.0
                    if (parsed == 0.0) 0 else {
                        (parsed * 100).toInt()
                    }
                }
                else -> rawValue.toIntOrNull() ?: 0
            }
            if (value != 0) thresholdsMap[type.name] = value  // Allow 0 or non-zero for FITNESS_AGE_DISTANCE
        }
        viewModel.saveGarminSettings(enabled, proxyUrl, appToken, dateOfBirth, thresholdsMap)
    }

    Column {
        Text("❤️ Garmin Integration", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Link habits to your Garmin health metrics. Metrics are automatically " +
                   "tracked and converted to habit increments when thresholds are met.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable Garmin", fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { newVal ->
                    enabled = newVal
                    save()
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // Proxy URL
            OutlinedTextField(
                value = proxyUrl,
                onValueChange = { proxyUrl = it },
                label = { Text("Proxy URL") },
                placeholder = { Text("https://your-proxy.onrender.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))

            // App Token
            OutlinedTextField(
                value = appToken,
                onValueChange = { appToken = it },
                label = { Text("App Token") },
                placeholder = { Text("Your secret app token") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))

            // Date of Birth
            OutlinedTextField(
                value = dateOfBirth,
                onValueChange = { dateOfBirth = it },
                label = { Text("Date of Birth") },
                placeholder = { Text("YYYY-MM-DD") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "Required for Fitness Age Distance calculation (e.g., 1990-01-15)",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            
            // Recalculate Fitness Age Distance button
            Button(
                onClick = { viewModel.recalculateFitnessAgeDistance() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Recalculate Fitness Age Distance", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(4.dp))
            
            Button(onClick = { save() }) {
                Text("Save Connection Settings", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Test Connection button - always visible when settings are configured
        if (proxyUrl.isNotEmpty() || appToken.isNotEmpty()) {
            Button(
                onClick = { viewModel.testGarminConnection() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (MaterialTheme.colorScheme.primary == Color.Unspecified)
                        Color(0xFF2196F3)
                    else MaterialTheme.colorScheme.primary
                )
            ) {
                Text("Test Connection", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(12.dp))

            // Thresholds for each type
            Text(
                "Thresholds",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Set the threshold for each metric type to count as 1 habit increment. " +
                       "For most metrics, higher values are better. " +
                       "For Fitness Age Distance, negative values are better (younger fitness age). " +
                       "Leave blank or 0 to disable that type.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            types.forEach { type ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp)
                ) {
                    Text(
                        text = type.label,
                        fontSize = 13.sp,
                        modifier = Modifier.width(140.dp)
                    )
                    OutlinedTextField(
                        value = getThresholdFor(type),
                        onValueChange = { newVal -> setThresholdFor(type, newVal) },
                        placeholder = { Text(when (type) {
                            GarminType.FITNESS_AGE -> "37.04"
                            GarminType.FITNESS_AGE_DISTANCE -> "-5.00"
                            else -> "0"
                        }) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = when (type) {
                                GarminType.FITNESS_AGE -> KeyboardType.Decimal
                                GarminType.FITNESS_AGE_DISTANCE -> KeyboardType.Text  // Allow negative numbers
                                else -> KeyboardType.Number
                            }
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        textStyle = TextStyle(fontSize = 13.sp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = when (type) {
                            GarminType.VO2_MAX -> "ml/kg/min"
                            GarminType.FITNESS_AGE -> "years"
                            GarminType.FITNESS_AGE_DISTANCE -> "years"
                            GarminType.RESTING_HR -> "bpm"
                            GarminType.HRV_LAST_NIGHT -> "ms"
                            GarminType.HRV_WEEKLY_AVG -> "ms"
                            GarminType.SLEEP_SCORE -> "pts"
                            GarminType.STEPS -> "steps"
                            GarminType.ALTITUDE_ASCENT_METERS -> "m"
                            GarminType.DISTANCE_METERS -> "m"
                            GarminType.CALORIES -> "kcal"
                            GarminType.ACTIVE_MINUTES -> "min"
                            GarminType.FLOORS_CLIMBED -> "floors"
                            GarminType.MIN_HR -> "bpm"
                            GarminType.MAX_HR -> "bpm"
                            GarminType.STRESS_LEVEL -> "level"
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { save() }) {
                Text("Save Garmin Settings", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))

            // Sync status
            if (garminSyncStatus.isNotEmpty()) {
                Text(
                    text = garminSyncStatus,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Backlog fetch button
            Text(
                "Backlog Sync",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Fetch your entire Garmin health history and retroactively " +
                       "fill in habit data for all past days. This may take a while.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = { viewModel.fetchGarminBacklog() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Fetch Entire Backlog", fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(8.dp))
            
            // Historic Data Import section
            Text(
                "Historic Data Import",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Import historic Garmin data from a JSON file generated " +
                       "by the desktop import script. Use this to fill in your " +
                       "history with past data from your Garmin GDPR export.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            
            val historicImportLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    viewModel.importGarminHistoricData(
                        jsonFile = uriToFile(context, it),
                        onComplete = { result ->
                            if (result.success) {
                                val summary = result.metricsImported.entries.joinToString("\n") {
                                    "${it.key.label}: ${it.value} entries"
                                }
                                Log.d("GarminImport", "Import succeeded:\n$summary")
                            }
                        }
                    )
                }
            }
            
            Button(
                onClick = { historicImportLauncher.launch("application/json") },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text("Import Historic Data", fontSize = 12.sp)
            }
        }
    }
}

/**
 * Converts a content URI to a File for the Garmin import.
 * Creates a temporary copy if the URI is not a file:// scheme.
 */
private fun uriToFile(context: Context, uri: Uri): File {
    return if (uri.scheme == "file") {
        File(uri.path ?: throw IllegalArgumentException("Invalid file URI"))
    } else {
        // For content URIs, copy to a temp file
        val tempFile = File(context.cacheDir, "garmin_import_${System.currentTimeMillis()}.json")
        context.contentResolver.openInputStream(uri)?.use { input ->
            tempFile.outputStream().use { output ->
                input.copyTo(output)
            }
        } ?: throw IllegalArgumentException("Failed to open URI")
        tempFile
    }
}

/**
 * Voice Trigger settings section — simple global enable/disable toggle.
 * Per-habit trigger word configuration is done in edit mode.
 */
@Composable
private fun VoiceTriggerSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    var enabled by remember(settings.voiceTriggerEnabled) { mutableStateOf(settings.voiceTriggerEnabled) }

    Column {
        Text("🎤 Voice Trigger", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Increment habits by speaking trigger words via Samsung Routines",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("Enable Voice Trigger", fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    viewModel.saveVoiceTriggerEnabled(it)
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Configure trigger words per-habit in Edit Mode (tap ✏ on the main screen, " +
                       "select a habit, scroll to \"🎤 Voice Trigger\" in SETTINGS).",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Voice Note Dictation settings section — enable toggle + file picker for the notes.md file.
 */
@Composable
private fun VoiceNoteSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    var enabled by remember(settings.voiceNoteEnabled) { mutableStateOf(settings.voiceNoteEnabled) }
    val hasFile = settings.voiceNoteFileUri.isNotEmpty()

    val context = androidx.compose.ui.platform.LocalContext.current

    // Resolve the display name from the content URI
    val notesFileName = remember(settings.voiceNoteFileUri) {
        if (settings.voiceNoteFileUri.isEmpty()) ""
        else {
            try {
                val cursor = context.contentResolver.query(
                    android.net.Uri.parse(settings.voiceNoteFileUri),
                    null, null, null, null
                )
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) it.getString(nameIndex) else null
                    } else null
                } ?: run {
                    // Fallback: extract last path segment from URI
                    android.net.Uri.parse(settings.voiceNoteFileUri).lastPathSegment
                        ?.substringAfterLast("/")
                        ?: ""
                }
            } catch (_: Exception) {
                android.net.Uri.parse(settings.voiceNoteFileUri).lastPathSegment
                    ?.substringAfterLast("/")
                    ?: ""
            }
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistent permission so we can access the file later from the service
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.saveVoiceNoteFileUri(uri.toString())
        }
    }

    Column {
        Text("📝 Voice Note Dictation", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Dictate notes by voice via Samsung Routines — prepended to a markdown file",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
        ) {
            Text("Enable Voice Note", fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    viewModel.saveVoiceNoteEnabled(it)
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Notes file", fontSize = 14.sp)
                    Text(
                        text = if (hasFile) "✓ $notesFileName" else "⚠ No file selected",
                        color = if (hasFile) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Button(
                    onClick = {
                        filePicker.launch(arrayOf("text/markdown", "text/plain", "*/*"))
                    }
                ) {
                    Text(if (hasFile) "Change" else "Select File", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Dictated notes are prepended to the top of this file with a " +
                       "\"## YYYY-MM-DD HH:MM:SS\" header. Set up a Samsung Routine with " +
                       "\"Voice Note\" as the app action.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Debug Mode card ──────────────────────────────────────────────────────────

private val DebugGray = Color(0xFF666666)

@Composable
private fun DebugModeCard(
    debugModeEnabled: Boolean,
    debugFileDirUri: String,
    onToggleDebugMode: (Boolean) -> Unit,
    onChooseDirectory: () -> Unit,
    onClearDirectory: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("🐛 Debug Mode", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Show a floating bubble on every screen. Tap it to log bugs, features, or notes " +
                "that are saved with the current screen's source file info to debug_tail.json.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Enable toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable Debug Bubble", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (debugModeEnabled) "Bubble is visible" else "Bubble is hidden",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (debugModeEnabled) DebugGray else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = debugModeEnabled,
                onCheckedChange = onToggleDebugMode,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = DebugGray,
                    checkedThumbColor = Color.White
                )
            )
        }

        // File directory (only shown when debug mode is on)
        if (debugModeEnabled) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Choose the folder where debug_tail.json will be written.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    if (debugFileDirUri.isNotBlank()) {
                        val displayPath = try {
                            Uri.parse(debugFileDirUri).lastPathSegment ?: debugFileDirUri
                        } catch (_: Exception) { debugFileDirUri }
                        Text(
                            text = "📁 $displayPath",
                            style = MaterialTheme.typography.bodySmall,
                            color = DebugGray,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            text = "Using internal storage (default)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (debugFileDirUri.isNotBlank()) {
                        IconButton(onClick = onClearDirectory, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(16.dp))
                        }
                    }
                    Button(
                        onClick = onChooseDirectory,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (debugFileDirUri.isNotBlank()) "Change" else "Choose Folder",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }
}
