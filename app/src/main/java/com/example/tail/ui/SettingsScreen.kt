package com.example.tail.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
    onNavigateBack: () -> Unit,
    onNavigateToAppStats: () -> Unit = {}
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

            // ── App Stats ─────────────────────────────────────────────────────
            item {
                Text("App Stats", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "View comprehensive statistics about your habit tracking: " +
                           "highest points, streaks, top habits, trends, and more.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onNavigateToAppStats) {
                    Text("View App Stats")
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
    var puzzleSlowMin by remember(settings.chessComMinutesPerIncrement) {
        mutableStateOf((settings.chessComMinutesPerIncrement["PUZZLE_SLOW"] ?: 0).let { if (it == 0) "" else it.toString() })
    }
    var puzzleRushMin by remember(settings.chessComMinutesPerIncrement) {
        mutableStateOf((settings.chessComMinutesPerIncrement["PUZZLE_RUSH"] ?: 0).let { if (it == 0) "" else it.toString() })
    }

    fun getMinFor(type: ChessComType): String = when (type) {
        ChessComType.BULLET -> bulletMin
        ChessComType.BLITZ -> blitzMin
        ChessComType.RAPID -> rapidMin
        ChessComType.PUZZLE_SLOW -> puzzleSlowMin
        ChessComType.PUZZLE_RUSH -> puzzleRushMin
    }

    fun setMinFor(type: ChessComType, value: String) {
        val filtered = value.filter { it.isDigit() }
        when (type) {
            ChessComType.BULLET -> bulletMin = filtered
            ChessComType.BLITZ -> blitzMin = filtered
            ChessComType.RAPID -> rapidMin = filtered
            ChessComType.PUZZLE_SLOW -> puzzleSlowMin = filtered
            ChessComType.PUZZLE_RUSH -> puzzleRushMin = filtered
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
