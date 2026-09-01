package com.example.tail.ui

import android.content.Context
import android.net.Uri
import android.graphics.Typeface
import android.util.Log
import java.io.File
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import com.example.tail.data.GarminType
import com.example.tail.data.ImportResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import com.example.tail.data.backup.GoogleDriveManager
import com.example.tail.data.debug.DebugPreferences
import com.example.tail.widget.ChessReadinessStore
import com.example.tail.wallpaper.WallpaperAlarmReceiver
import com.example.tail.wallpaper.WallpaperMetric
import com.example.tail.wallpaper.WallpaperRefresher
import com.example.tail.wallpaper.WallpaperTarget
import com.example.tail.ui.AdviceDialog
import com.example.tail.ui.AdviceViewModel
import kotlinx.coroutines.launch

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
    gdriveManager: GoogleDriveManager,
    onNavigateBack: () -> Unit,
    onNavigateToAppStats: () -> Unit = {},
    onNavigateToChessReadinessStats: () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsState()
    val debugSnapshot by debugPrefs.snapshot.collectAsState()
    val context = LocalContext.current

    // AI Assistant chat popup — opened via the 🤖 button in the top bar.
    var showAiAssistant by rememberSaveable { mutableStateOf(false) }

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

    // Picker for the wallpaper image directory (result_1.png … result_N.png) —
    // read-only access is enough, the app only decodes the images.
    val wallpaperDirLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.saveWallpaperSettings(dirUri = uri.toString())
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
                    // AI Assistant — sits next to the stats icon.
                    IconButton(onClick = { showAiAssistant = true }) {
                        Icon(Icons.Filled.SmartToy, contentDescription = "AI Assistant")
                    }
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
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)
        ) {
            // ── Data Files ────────────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Data Files",
                    summary = "Habit database · screens layout",
                    icon = Icons.Filled.FolderOpen,
                    accent = BorderRed
                ) {
                    HabitDatabaseFileSection(
                        fileUri = settings.fileUri,
                        onPickFile = { filePicker.launch(arrayOf("*/*")) }
                    )
                    SettingsSubSectionDivider()
                    ScreensRelayFileSection(
                        fileUri = settings.screensRelayFileUri,
                        onPickFile = { screensRelayFilePicker.launch(arrayOf("*/*")) }
                    )
                }
            }


            // ── Chess ──────────────────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Chess",
                    summary = "Chess.com sync · chess readiness system",
                    icon = Icons.Filled.Psychology,
                    accent = BorderOrange
                ) {
                    ChessComSettingsSection(
                        viewModel = viewModel,
                        settings = settings,
                        onNavigateToStats = onNavigateToChessReadinessStats
                    )
                    SettingsSubSectionDivider()
                    ChessReadinessSettingsSection(
                        viewModel = viewModel,
                        settings = settings
                    )
                }
            }

            // ── Integrations ───────────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Integrations",
                    summary = "GitHub · Garmin · Tail Bridge · Inuit",
                    icon = Icons.Filled.Extension,
                    accent = BorderGreen
                ) {
                    GithubSettingsSection(viewModel = viewModel, settings = settings)
                    SettingsSubSectionDivider()
                    GarminSettingsSection(viewModel = viewModel, settings = settings, context = context)
                    SettingsSubSectionDivider()
                    BridgeSettingsSection(viewModel = viewModel, settings = settings)
                    SettingsSubSectionDivider()
                    InuitSettingsSection(viewModel = viewModel, settings = settings)
                }
            }

            // ── Voice & Input ──────────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Voice & Input",
                    summary = "Voice trigger · voice note dictation",
                    icon = Icons.Filled.Mic,
                    accent = BorderBlue
                ) {
                    VoiceTriggerSettingsSection(viewModel = viewModel, settings = settings)
                    SettingsSubSectionDivider()
                    VoiceNoteSettingsSection(viewModel = viewModel, settings = settings)
                }
            }

            // ── Notifications ───────────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Notifications",
                    summary = "App stats records · habit asks",
                    icon = Icons.Filled.Notifications,
                    accent = BorderGreen
                ) {
                    NotificationsSettingsSection(viewModel = viewModel, settings = settings)
                }
            }

            // ── Overlays & Tools ───────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Overlays & Tools",
                    summary = "Stats overlay · floating bubble · debug mode",
                    icon = Icons.Filled.Layers,
                    accent = BorderPink
                ) {
                    StatsOverlaySettingsSection(viewModel = viewModel, settings = settings)
                    SettingsSubSectionDivider()
                    FloatingBubbleSettingsSection(context = context)
                    SettingsSubSectionDivider()
                    TierBarWidgetSettingsSection(context = context)
                    SettingsSubSectionDivider()
                    DebugModeCard(
                        debugModeEnabled = debugSnapshot.debugModeEnabled,
                        debugFileDirUri = debugSnapshot.debugFileDirUri,
                        onToggleDebugMode = { debugPrefs.debugModeEnabled = it },
                        onChooseDirectory = { debugDirLauncher.launch(null) },
                        onClearDirectory = { debugPrefs.debugFileDirUri = "" }
                    )
                }
            }

            // ── Wallpaper ───────────────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Wallpaper",
                    summary = "Points-driven wallpaper from an image folder",
                    icon = Icons.Filled.Wallpaper,
                    accent = BorderYellow
                ) {
                    WallpaperSettingsSection(
                        context = context,
                        viewModel = viewModel,
                        settings = settings,
                        onPickDirectory = { wallpaperDirLauncher.launch(null) }
                    )
                }
            }

            // ── Habit Features ─────────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Habit Features",
                    summary = "AI icons · meal engine · AI assistant · vision memory · advice banner",
                    icon = Icons.Filled.AutoAwesome,
                    accent = BorderWhite
                ) {
                    AiIconSettingsSection(viewModel = viewModel, settings = settings)
                    SettingsSubSectionDivider()
                    MealSettingsSection(viewModel = viewModel, settings = settings)
                    SettingsSubSectionDivider()
                    AiAssistantSettingsSection(viewModel = viewModel, settings = settings)
                    SettingsSubSectionDivider()
                    VisionMemorySection(viewModel = viewModel, settings = settings)
                    SettingsSubSectionDivider()
                    AdviceBannerSection(adviceViewModel = adviceViewModel)
                }
            }

            // ── Backup & Recovery ──────────────────────────────────────────────
            item {
                SettingsCategory(
                    title = "Backup & Recovery",
                    summary = "Backups · Google Drive · automatic snapshots",
                    icon = Icons.Filled.Backup,
                    accent = BorderWhiteRed
                ) {
                    BackupSettingsSection(
                        backupManager = backupManager,
                        autoBackupManager = autoBackupManager,
                        gdriveManager = gdriveManager
                    )
                    SettingsSubSectionDivider()
                    SnapshotRestoreSection(viewModel = viewModel)
                }
            }
        }
    }

    // AI Assistant chat popup (top-bar 🤖 button).
    if (showAiAssistant) {
        AiAssistantDialog(
            viewModel = viewModel,
            onDismiss = { showAiAssistant = false }
        )
    }
    } // closes grayscale MaterialTheme
}

// ── Collapsible category card ────────────────────────────────────────────────

/**
 * A collapsible settings category card.
 *
 * Collapsed by default — tapping the header expands it with an animated
 * reveal and a rotating chevron. Purely presentational grouping: the
 * [content] lambda is only composed while expanded, so collapsed categories
 * cost nothing to render. Expansion state persists across app launches.
 */
@Composable
private fun SettingsCategory(
    title: String,
    summary: String,
    icon: ImageVector,
    accent: Color,
    content: @Composable () -> Unit
) {
    var expanded by rememberSectionExpansion("settings", title, false)
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "chevronRotation"
    )

    // Section tint — each category carries one accent from the app color
    // system (Red → Orange → Green → Blue → Pink → Yellow → Glass → White →
    // White+Red), applied to the card surface, border and header icon.
    // White is the tier beyond Glass; White+Red ("post-white") renders a
    // mostly-white card with subtle red accents and thin red hairline borders.
    val dark = isSystemInDarkTheme()
    val whiteTier = accent == BorderWhite
    val postWhite = accent == BorderWhiteRed
    val cardColor = when {
        postWhite -> lerp(
            MaterialTheme.colorScheme.surfaceVariant,
            Color.White,
            if (dark) 0.10f else 0.24f
        )
        else -> lerp(
            MaterialTheme.colorScheme.surfaceVariant,
            accent,
            if (dark) 0.20f else 0.14f
        )
    }
    val border = when {
        // Post-white: thin, quietly red hairline.
        postWhite -> BorderStroke(0.75.dp, BorderRed.copy(alpha = if (dark) 0.45f else 0.35f))
        whiteTier -> BorderStroke(1.dp, Color.White.copy(alpha = if (dark) 0.55f else 0.70f))
        else -> BorderStroke(1.dp, accent.copy(alpha = if (dark) 0.60f else 0.50f))
    }
    val iconTint = when {
        postWhite -> BorderRed.copy(alpha = 0.90f)
        whiteTier && dark -> BorderWhite
        whiteTier -> MaterialTheme.colorScheme.outline
        // Glass is near-white — fall back to a theme gray so the icon stays visible.
        accent == BorderGlass -> MaterialTheme.colorScheme.outline
        else -> accent
    }
    val iconBg = when {
        postWhite -> BorderRed.copy(alpha = if (dark) 0.16f else 0.10f)
        whiteTier -> Color.White.copy(alpha = if (dark) 0.22f else 0.65f)
        else -> accent.copy(alpha = if (dark) 0.30f else 0.18f)
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = cardColor,
        border = border,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.animateContentSize(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        ) {
            // Header row — always visible, tap to toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBg)
                        .then(
                            // Post-white: echo the card's thin red hairline on the icon chip.
                            if (postWhite) Modifier.border(
                                width = 0.75.dp,
                                color = BorderRed.copy(alpha = if (dark) 0.35f else 0.30f),
                                shape = RoundedCornerShape(12.dp)
                            ) else Modifier
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        text = summary,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }

            // Content — only composed while expanded
            if (expanded) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

/**
 * Thin divider between sub-sections inside a category card.
 */
@Composable
private fun SettingsSubSectionDivider() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        thickness = 0.5.dp
    )
}

/**
 * Habit database (habitsdb.txt) picker sub-section.
 */
@Composable
private fun HabitDatabaseFileSection(
    fileUri: String,
    onPickFile: () -> Unit
) {
    Column {
        Text("Habit Database (habitsdb.txt)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            text = "The unified habit database shared between this device and the PC via Syncthing. " +
                   "Both devices read and write this single file.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (fileUri.isEmpty()) "No file selected" else fileUri,
            fontSize = 12.sp,
            color = if (fileUri.isEmpty())
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onPickFile) {
            Text("Change File")
        }
    }
}

/**
 * Screens relay (screens_layout.json) picker sub-section.
 */
@Composable
private fun ScreensRelayFileSection(
    fileUri: String,
    onPickFile: () -> Unit
) {
    Column {
        Text("Screens Layout (screens_layout.json)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            text = "Shared with the PC widget to keep screen names and habit arrangement in sync. " +
                   "Pick the screens_layout.json file in your noteVault/tail/ folder. " +
                   "The app writes to it whenever you add, rename, or rearrange screens.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (fileUri.isEmpty()) "No file selected (PC widget won't sync screens)" else fileUri,
            fontSize = 12.sp,
            color = if (fileUri.isEmpty())
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = onPickFile) {
            Text("Change Relay File")
        }
    }
}

/**
 * Advice Banner sub-section — manage the advice items shown on the main screen.
 */
@Composable
private fun AdviceBannerSection(adviceViewModel: AdviceViewModel) {
    val adviceState by adviceViewModel.state.collectAsState()
    val adviceCount = adviceState.items.size
    var showAdviceDialog by remember { mutableStateOf(false) }

    Column {
        Text("Advice Banner", fontWeight = FontWeight.Bold, fontSize = 15.sp)
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
    settings: com.example.tail.data.AppSettings,
    onNavigateToStats: () -> Unit = {}
) {
    val chessComSyncStatus by viewModel.chessComSyncStatus.collectAsState()

    var enabled by remember(settings.chessComEnabled) { mutableStateOf(settings.chessComEnabled) }
    var username by remember(settings.chessComUsername) { mutableStateOf(settings.chessComUsername) }

    fun save() {
        viewModel.saveChessComSettings(enabled, username)
    }

    Column {
        // Header row — title on the left, icon-only shortcut to the chess
        // readiness stats screen pinned to the far right.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("♟ Chess.com Integration", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onNavigateToStats, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Filled.BarChart,
                    contentDescription = "Chess stats",
                    tint = BorderOrange,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Text(
            text = "Link habits to your chess.com activity. Each linked habit stores three " +
                   "raw values per day: games (count), minutes, and wins. Points are derived " +
                   "from the game count via the habit's divider setting.",
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
 * GitHub Integration settings section — global enable toggle and optional
 * Personal Access Token for higher API rate limits.
 *
 * Per-habit configuration (repo URL, metric) is done in the habit edit panel.
 */
@Composable
private fun GithubSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    var enabled by remember(settings.githubEnabled) { mutableStateOf(settings.githubEnabled) }
    var token by remember(settings.githubToken) { mutableStateOf(settings.githubToken) }

    Column {
        Text("🐙 GitHub Integration", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Link habits to public GitHub repositories. Commit activity " +
                   "(lines changed, commits, etc.) is automatically tracked. " +
                   "Configure each habit's repo URL in its edit panel.",
            fontSize = 11.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable GitHub", fontSize = 14.sp)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    viewModel.saveGithubSettings(enabled, token)
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("GitHub Token (optional)") },
                placeholder = { Text("ghp_... — raises limit to 5000/hr") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Without a token, the GitHub API allows 60 requests/hour. " +
                       "A Personal Access Token (no scopes needed for public repos) " +
                       "raises this to 5 000/hour.",
                fontSize = 10.sp,
                color = Color(0xFF888888)
            )
            Text(
                text = "Private repos: classic tokens need the 'repo' scope; " +
                       "fine-grained tokens need the repo selected under Repository " +
                       "access + Contents: Read-only permission.",
                fontSize = 10.sp,
                color = Color(0xFF888888)
            )

            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { viewModel.saveGithubSettings(enabled, token) }) {
                Text("Save GitHub Settings", fontSize = 12.sp)
            }

            val linkedCount = settings.githubRepoUrls.size
            if (linkedCount > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "$linkedCount habit(s) linked to GitHub repos.",
                    fontSize = 11.sp,
                    color = Color(0xFF66BB6A)
                )
            }
        }
    }
}

/**
 * Inuit Integration settings section — share selected text-input habits'
 * RECENT entries with the Inuit trivia/intuition trainer (same keystore).
 * Inuit uses them only as light inspiration for question generation, and
 * picks per-net which shared habits it actually reads (configured in Inuit).
 */
@Composable
private fun InuitSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    var enabled by remember(settings.inuitIntegrationEnabled) {
        mutableStateOf(settings.inuitIntegrationEnabled)
    }

    Column {
        Text("🧭 Inuit Integration", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Let the Inuit trivia trainer read the most recent entries of " +
                   "selected text-input habits, as light inspiration for its " +
                   "questions. Bounded sharing: last 14 days, at most 3 entries " +
                   "per habit, 300 characters each — never the full history. " +
                   "Which habits each Inuit net uses is configured inside Inuit.",
            fontSize = 11.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Share text habits with Inuit", fontSize = 14.sp)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    viewModel.setInuitIntegrationEnabled(it)
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))
            // Only text-input habits with a configured log file can be shared.
            val eligible = settings.textInputHabits
                .filter { it in settings.textInputFileUris }
                .sorted()
            if (eligible.isEmpty()) {
                Text(
                    text = "No text-input habits with a log file yet. Enable " +
                           "\"Text input\" for a habit and pick its log file first.",
                    fontSize = 11.sp,
                    color = Color(0xFF888888)
                )
            } else {
                Text("Shared habits", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                eligible.forEach { habit ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleInuitTextHabit(habit) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = habit in settings.inuitTextHabits,
                            onCheckedChange = { viewModel.toggleInuitTextHabit(habit) }
                        )
                        Text(habit, fontSize = 13.sp)
                    }
                }
                val sharedCount = eligible.count { it in settings.inuitTextHabits }
                if (sharedCount > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$sharedCount habit(s) shared. Inuit sees only their " +
                               "most recent entries.",
                        fontSize = 11.sp,
                        color = Color(0xFF66BB6A)
                    )
                }
            }
        }
    }
}

/**
 * Garmin Integration settings section — proxy URL, app token, and date of birth.
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

    fun save() {
        viewModel.saveGarminSettings(enabled, proxyUrl, appToken, dateOfBirth)
    }

    Column {
        Text("❤️ Garmin Integration", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Link habits to your Garmin health metrics. Metrics are automatically " +
                   "tracked and converted to habit increments.",
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
 * Points-driven wallpaper settings section.
 *
 * The user picks a folder of numbered images (result_1.png … result_N.png),
 * which wallpaper to replace (home / lock / both) and which point statistic
 * drives the choice (today / 7-day avg / 30-day avg). Image N is used for
 * N points, clamped to the available range.
 */
@Composable
private fun WallpaperSettingsSection(
    context: Context,
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings,
    onPickDirectory: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val wallpaperStatus by viewModel.wallpaperStatus.collectAsState()
    var applying by remember { mutableStateOf(false) }

    var enabled by remember(settings.wallpaperEnabled) {
        mutableStateOf(settings.wallpaperEnabled)
    }

    // Human-readable folder label from the SAF tree URI.
    val dirLabel = remember(settings.wallpaperDirUri) {
        if (settings.wallpaperDirUri.isEmpty()) ""
        else Uri.decode(settings.wallpaperDirUri.substringAfterLast('/'))
    }

    Column {
        Text("🖼 Points Wallpaper", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Sets the wallpaper to the image matching your points. " +
                   "Name the images <anything>_<number>.png (e.g. result_51.png) — " +
                   "image N is used for N points.",
            fontSize = 11.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Enable toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable Wallpaper", fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    viewModel.saveWallpaperSettings(enabled = it)
                    if (it) WallpaperAlarmReceiver.scheduleNext(context)
                    else WallpaperAlarmReceiver.cancel(context)
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // ── Image folder ──────────────────────────────────────────────
            Text("Image folder", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(
                text = if (dirLabel.isEmpty()) "No folder selected yet"
                       else "📁 $dirLabel",
                fontSize = 11.sp,
                color = if (dirLabel.isEmpty()) Color(0xFFE65100) else Color(0xFF81C784)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = onPickDirectory) {
                    Text(if (dirLabel.isEmpty()) "Choose Folder" else "Change Folder", fontSize = 12.sp)
                }
            }
            Text(
                text = "Refreshes automatically as points change and once a day after midnight.",
                fontSize = 9.sp,
                color = Color(0xFF666666)
            )

            // ── Wallpaper target ──────────────────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            Text("Apply to", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            WallpaperTarget.entries.forEach { target ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.saveWallpaperSettings(target = target) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.wallpaperTarget == target,
                        onClick = { viewModel.saveWallpaperSettings(target = target) }
                    )
                    Text(target.label, fontSize = 13.sp)
                }
            }

            // ── Metric ────────────────────────────────────────────────────
            Spacer(modifier = Modifier.height(12.dp))
            Text("Based on", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            WallpaperMetric.entries.forEach { metric ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.saveWallpaperSettings(metric = metric) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.wallpaperMetric == metric,
                        onClick = { viewModel.saveWallpaperSettings(metric = metric) }
                    )
                    Text(metric.label, fontSize = 13.sp)
                }
            }

            // ── Manual apply + status ─────────────────────────────────────
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    applying = true
                    scope.launch {
                        val status = WallpaperRefresher.refresh(context, force = true)
                        viewModel.setWallpaperStatus(status.ifEmpty { "No change needed" })
                        applying = false
                    }
                },
                enabled = !applying && settings.wallpaperDirUri.isNotEmpty()
            ) {
                Text(if (applying) "Applying…" else "Apply Now", fontSize = 12.sp)
            }
            if (wallpaperStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = wallpaperStatus,
                    fontSize = 10.sp,
                    color = Color(0xFFAAAAAA)
                )
            }
        }
    }
}

/**
 * Tail Bridge settings section — PC↔Phone communication for movies and future features.
 */
@Composable
private fun BridgeSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    val bridgeStatus by viewModel.bridgeStatus.collectAsState()

    var enabled by remember(settings.bridgeEnabled) { mutableStateOf(settings.bridgeEnabled) }

    // The bridge URL/token are auto-derived from the Garmin connection settings
    // (same PC, same token, port 8001 instead of 8000). No manual entry needed.
    val garminConfigured = settings.garminProxyUrl.isNotEmpty() &&
                           settings.garminAppToken.isNotEmpty()

    Column {
        Text("🎬 Tail Bridge", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Tether desktop data to your phone. The bridge runs on your PC " +
                   "alongside the Garmin proxy and shares the same connection.",
            fontSize = 11.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Enable toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable Bridge", fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    viewModel.saveBridgeSettings(it)
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))

            // Auto-connection status
            if (garminConfigured) {
                Text(
                    text = "✓ Auto-connected via Garmin settings (port 8001)",
                    fontSize = 11.sp,
                    color = Color(0xFF81C784)
                )
            } else {
                Text(
                    text = "⚠ Configure the Garmin connection above first — " +
                           "the bridge shares the same server and auth token.",
                    fontSize = 11.sp,
                    color = Color(0xFFE65100)
                )
            }

            // Test Connection button
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.testBridgeConnection() },
                enabled = garminConfigured,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1B5E20)
                )
            ) {
                Text("Test Connection")
            }

            // Status
            if (bridgeStatus.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = bridgeStatus,
                    fontSize = 11.sp,
                    color = Color(0xFFAAAAAA)
                )
            }

            // Linked habits info
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "To link a habit: long-press a text-input habit → enable " +
                       "“Movie Bridge”. When tapped, the app fetches the latest " +
                       "watched movie from your desktop.",
                fontSize = 10.sp,
                color = Color(0xFF666666)
            )
            if (settings.bridgeMovieHabits.isNotEmpty()) {
                Text(
                    text = "Linked: ${settings.bridgeMovieHabits.joinToString(", ")}",
                    fontSize = 10.sp,
                    color = Color(0xFF81C784)
                )
            }

            // ── IMDb Ratings (OMDb API) ───────────────────────────────────
            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("IMDb Ratings", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                text = "Fetch IMDb ratings for your watched movies and TV episodes. " +
                       "Ratings appear in the graph next to each title, and the daily " +
                       "average is plotted as a separate line.",
                fontSize = 10.sp,
                color = Color(0xFF888888)
            )
            Spacer(modifier = Modifier.height(8.dp))

            // API key input
            var omdbKey by remember(settings.omdbApiKey) {
                mutableStateOf(settings.omdbApiKey)
            }
            var omdbKeyVisible by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = omdbKey,
                onValueChange = { omdbKey = it },
                label = { Text("OMDb API Key", fontSize = 12.sp) },
                singleLine = true,
                visualTransformation = if (omdbKeyVisible) androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { omdbKeyVisible = !omdbKeyVisible }) {
                        Icon(
                            painter = androidx.compose.ui.res.painterResource(
                                if (omdbKeyVisible) android.R.drawable.ic_menu_view
                                else android.R.drawable.ic_secure
                            ),
                            contentDescription = if (omdbKeyVisible) "Hide" else "Show",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.saveOmdbApiKey(omdbKey) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
                ) {
                    Text("Save Key", fontSize = 12.sp)
                }
                Text(
                    text = "Get a free key at omdbapi.com/apikey.aspx",
                    fontSize = 9.sp,
                    color = Color(0xFF666666),
                    modifier = Modifier.align(Alignment.CenterVertically)
                )
            }

            // Backlog fetch button (only if key is set and habits are linked)
            if (settings.omdbApiKey.isNotBlank() && settings.bridgeMovieHabits.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                val omdbStatus by viewModel.omdbStatus.collectAsState()
                val backlogRunning by viewModel.omdbBacklogRunning.collectAsState()

                Button(
                    onClick = { viewModel.fetchImdbBacklog() },
                    enabled = !backlogRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E342E))
                ) {
                    Text(
                        text = if (backlogRunning) "Fetching..." else "Fetch IMDb Backlog",
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = "Fetches ratings for all previously-watched titles. " +
                           "Limited to 990 calls/day — click again the next day to continue.",
                    fontSize = 9.sp,
                    color = Color(0xFF666666)
                )

                // Minutes backfill: appends "(N min)" to old entries lacking a
                // length, using OMDb runtimes split across duplicate days
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.fetchMovieMinutesBacklog() },
                    enabled = !backlogRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E342E))
                ) {
                    Text(
                        text = if (backlogRunning) "Working..." else "Backfill Movie Lengths",
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = "Fetches runtimes (same OMDb API) for old entries without a length. " +
                           "A film logged on several days gets its runtime split evenly across those days.",
                    fontSize = 9.sp,
                    color = Color(0xFF666666)
                )

                // Retry button: clears cached "no rating" entries and refetches them
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = { viewModel.fetchImdbBacklog(retryFailed = true) },
                    enabled = !backlogRunning,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4E342E))
                ) {
                    Text(
                        text = "Retry Failed Lookups",
                        fontSize = 12.sp
                    )
                }
                Text(
                    text = "Clears all cached \"no rating\" results and fetches them again " +
                           "with fuzzy title matching (handles missing apostrophes/colons " +
                           "and release-tag junk). Use once after updating to the new matcher.",
                    fontSize = 9.sp,
                    color = Color(0xFF666666)
                )
                if (omdbStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = omdbStatus,
                        fontSize = 10.sp,
                        color = Color(0xFFAAAAAA)
                    )
                }
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

/**
 * Font options for the stats overlay numbers. Each pair is the Android
 * Typeface family string to persist + a friendly label; the label is
 * rendered in its own font so the selector doubles as a live preview.
 */
private val OVERLAY_FONT_OPTIONS: List<Pair<String, String>> = listOf(
    "monospace" to "Monospace",
    "sans-serif" to "Sans Serif",
    "sans-serif-medium" to "Sans Medium",
    "sans-serif-black" to "Sans Black",
    "sans-serif-light" to "Sans Light",
    "sans-serif-thin" to "Sans Thin",
    "sans-serif-condensed" to "Sans Condensed",
    "sans-serif-smallcaps" to "Sans Smallcaps",
    "serif" to "Serif",
    "serif-monospace" to "Serif Mono",
    "casual" to "Casual",
    "cursive" to "Cursive"
)

/** Friendly label for a stored overlay font family (falls back to the raw string). */
private fun overlayFontLabel(family: String): String =
    OVERLAY_FONT_OPTIONS.firstOrNull { it.first == family }?.second ?: family

/**
 * Stats Overlay settings section — master switch for the always-on-top
 * today / avg7 / avg30 bar (StatsOverlayService).
 *
 * The overlay shows today / avg7 / avg30 (shared computeTaskerStats), each
 * number tier-coloured. Edit mode (below) toggles the background, ◢ handle
 * and dragging. The bar itself is dragged to place it, and its ◢ corner handle
 * resizes it (width + font scale together) — this section covers the master
 * switch, background opacity and a position/size reset.
 */
/**
 * Notifications settings section — master switch for the app-stats record
 * notifications ("close to a new record" / "record broken" notices and the
 * 🏆 Record News feed in the App Stats screen). Habit confirmation asks are
 * unaffected.
 */
@Composable
private fun NotificationsSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("📊 App stats records", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text(
                text = "Notify when an all-time app record is close to breaking " +
                    "or just broken (averages, best day, streak totals…). " +
                    "Also feeds the 🏆 Record News popup in App Stats.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = settings.appStatsRecordNotificationsEnabled,
            onCheckedChange = { viewModel.setAppStatsRecordNotificationsEnabled(it) }
        )
    }
}

@Composable
private fun StatsOverlaySettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    val context = LocalContext.current
    val enabled = settings.statsOverlayEnabled
    var permissionMissing by remember {
        mutableStateOf(!android.provider.Settings.canDrawOverlays(context))
    }
    var geometry by remember {
        mutableStateOf(
            com.example.tail.widget.StatsOverlayStore.loadGeometry(context)
        )
    }

    // Raw text for the exact-position fields — kept as strings so the Y field
    // can hold a leading "-" (number keyboards can't type one; the ± button can).
    var xText by remember {
        mutableStateOf(
            geometry.x.takeIf { it != com.example.tail.widget.StatsOverlayStore.UNSET }
                ?.toString() ?: ""
        )
    }
    var yText by remember {
        mutableStateOf(
            geometry.y.takeIf { it != com.example.tail.widget.StatsOverlayStore.UNSET }
                ?.toString() ?: ""
        )
    }
    var wText by remember { mutableStateOf(geometry.widthDp.toString()) }

    /** Tells the running overlay to re-read its stored geometry/opacity. */
    fun notifyOverlayChanged() {
        if (!com.example.tail.widget.StatsOverlayService.isRunning) return
        try {
            context.startService(
                android.content.Intent(
                    context,
                    com.example.tail.widget.StatsOverlayService::class.java
                ).setAction(com.example.tail.widget.StatsOverlayService.ACTION_SETTINGS_CHANGED)
            )
        } catch (_: Exception) { /* service not running */ }
    }

    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("📊 Stats Overlay", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "Always-on-top bar with today / avg7 / avg30, each number " +
                        "tier-coloured. Turn ✏️ Edit mode on to drag the bar (it can " +
                        "sit right over the status bar), resize via the ◢ corner, " +
                        "long-press to open Tail — or set exact values below.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    permissionMissing =
                        !android.provider.Settings.canDrawOverlays(context)
                    if (want && permissionMissing) {
                        // Open the system "Display over other apps" screen; the
                        // user returns here and flips the switch again.
                        try {
                            context.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:${context.packageName}")
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } catch (_: Exception) { /* settings screen unavailable */ }
                    } else {
                        viewModel.setStatsOverlayEnabled(want)
                    }
                }
            )
        }

        if (enabled && permissionMissing) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "⚠ \"Display over other apps\" permission is missing — the bar " +
                    "cannot show. Toggle the switch again after granting it.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("✏️ Edit mode", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(
                        text = "ON: background + ◢ handle + dragging. OFF: bare " +
                            "coloured numbers that pass touches through.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                var editMode by remember {
                    mutableStateOf(
                        com.example.tail.widget.StatsOverlayStore.isEditMode(context)
                    )
                }
                Switch(
                    checked = editMode,
                    onCheckedChange = { want ->
                        editMode = want
                        com.example.tail.widget.StatsOverlayStore.setEditMode(context, want)
                        notifyOverlayChanged()
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Background opacity: ${(geometry.opacity * 100).toInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = geometry.opacity,
                onValueChange = { geometry = geometry.copy(opacity = it) },
                onValueChangeFinished = {
                    com.example.tail.widget.StatsOverlayStore.saveOpacity(context, geometry.opacity)
                    notifyOverlayChanged()
                },
                valueRange = 0.15f..1f
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Number brightness: ${(geometry.fontBrightness * 100).toInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = geometry.fontBrightness,
                onValueChange = { geometry = geometry.copy(fontBrightness = it) },
                onValueChangeFinished = {
                    // Targeted save: a full saveGeometry() would clobber the
                    // live x/y with this screen's possibly-stale snapshot.
                    com.example.tail.widget.StatsOverlayStore.saveFont(
                        context, geometry.fontFamily, geometry.fontBrightness
                    )
                    notifyOverlayChanged()
                },
                valueRange = 0f..1f
            )
            Text(
                text = "100% = each number at its purest colour (red → pure " +
                    "255,0,0). Slide left for duller, faded tones.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Number font",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            var fontMenuOpen by remember { mutableStateOf(false) }
            Box {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { fontMenuOpen = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // The closed selector previews the current font in itself.
                        Text(
                            text = overlayFontLabel(geometry.fontFamily),
                            fontFamily = FontFamily(
                                Typeface.create(geometry.fontFamily, Typeface.BOLD)
                            ),
                            fontSize = 16.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            Icons.Filled.KeyboardArrowDown,
                            contentDescription = "Choose font",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                DropdownMenu(
                    expanded = fontMenuOpen,
                    onDismissRequest = { fontMenuOpen = false }
                ) {
                    // Every option's name renders in its own font — the menu
                    // doubles as a side-by-side font preview.
                    OVERLAY_FONT_OPTIONS.forEach { (family, label) ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    label,
                                    fontFamily = FontFamily(
                                        Typeface.create(family, Typeface.BOLD)
                                    ),
                                    fontSize = 16.sp
                                )
                            },
                            trailingIcon = {
                                if (family == geometry.fontFamily) {
                                    Text("✓", fontWeight = FontWeight.Bold)
                                }
                            },
                            onClick = {
                                fontMenuOpen = false
                                geometry = geometry.copy(fontFamily = family)
                                com.example.tail.widget.StatsOverlayStore.saveFont(
                                    context, family, geometry.fontBrightness
                                )
                                notifyOverlayChanged()
                            }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Exact position & size",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = xText,
                    onValueChange = { v -> xText = v.filter { it.isDigit() }.take(4) },
                    label = { Text("X (px)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = yText,
                    onValueChange = { v ->
                        yText = (if (v.startsWith("-")) "-" else "") +
                            v.filter { it.isDigit() }.take(4)
                    },
                    label = { Text("Y (px)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    // Number keyboards have no minus key — the ± trailing button
                    // flips the sign so the bar can be nudged over the status bar.
                    trailingIcon = {
                        Text(
                            text = "±",
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clickable {
                                    yText = if (yText.startsWith("-")) yText.removePrefix("-")
                                    else "-$yText"
                                }
                                .padding(6.dp)
                        )
                    },
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = wText,
                    onValueChange = { v -> wText = v.filter { it.isDigit() }.take(4) },
                    label = { Text("Width (dp)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    geometry = geometry.copy(
                        // Blank field → UNSET sentinel → service auto-places top-center.
                        x = xText.toIntOrNull()?.coerceAtLeast(0)
                            ?: com.example.tail.widget.StatsOverlayStore.UNSET,
                        y = yText.toIntOrNull()?.coerceIn(-300, 10_000)
                            ?: com.example.tail.widget.StatsOverlayStore.UNSET,
                        widthDp = (wText.toIntOrNull() ?: geometry.widthDp)
                            .coerceIn(120, 2000)
                    )
                    com.example.tail.widget.StatsOverlayStore.saveGeometry(context, geometry)
                    notifyOverlayChanged()
                }) {
                    Text("Apply Position & Size")
                }
                Button(onClick = {
                    com.example.tail.widget.StatsOverlayStore.resetGeometry(context)
                    geometry = com.example.tail.widget.StatsOverlayStore.loadGeometry(context)
                    xText = ""
                    yText = ""
                    wText = geometry.widthDp.toString()
                    notifyOverlayChanged()
                }) {
                    Text("Reset")
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "X/Y are pixels from the top-left of the screen (0,0 = very top, " +
                    "over the status bar). Y can be negative — use the ± button — to " +
                    "nudge the bar even higher. Width is dp; the font scales with it. " +
                    "Tip: drag the bar roughly in place first, then fine-tune here.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "The bar survives reboots and revives itself if Android kills " +
                    "it. Hide it anytime from its notification.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Floating Bubble settings section — launch button for the overlay bubble.
 * The bubble floats over other apps and can be dragged around or dismissed.
 */
@Composable
private fun FloatingBubbleSettingsSection(context: Context) {
    val hasPermission = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            android.provider.Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    Column {
        Text("🫧 Floating Bubble", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Show a draggable Tail bubble that floats over other apps. " +
                "Drag it to the ✕ at the bottom to dismiss. " +
                "Future versions will let you track habit sessions directly from the bubble.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (!hasPermission) {
            Text(
                text = "⚠ First-time only: you'll be asked to grant \"Display over other apps\" " +
                    "permission. This is required for the bubble to appear on top of other apps.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        Button(onClick = {
            val intent = android.content.Intent(context, com.example.tail.widget.FloatingBubbleActivity::class.java).apply {
                action = com.example.tail.widget.FloatingBubbleActivity.ACTION_LAUNCH_BUBBLE
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }) {
            Text("Launch Floating Bubble")
        }
    }
}

/**
 * ♟ Chess Readiness settings section — global toggle + app association.
 *
 * When enabled with an app chosen (typically the chess app), the floating
 * bubble also appears over that app and its popup menu gains a
 * "Chess Readiness" option that launches the Phase 1 Pre-Session Diagnostic
 * (CCRS 0–100 → Green / Yellow / Red authorization).
 */
@Composable
private fun TierBarWidgetSettingsSection(context: Context) {
    var config by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            com.example.tail.widget.TierBarWidgetConfig.load(context)
        )
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column {
        Text("🎨 Tier Bar Widget", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            "Full-width home-screen widget whose background is the daily points " +
                "tier colour, with quick-launch buttons for app tabs. Toggle which " +
                "buttons appear (changes apply to placed widgets immediately).",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        com.example.tail.widget.TierBarWidgetProvider.BUTTONS.forEach { spec ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("${spec.glyph}  ${spec.label}", fontSize = 14.sp)
                Spacer(modifier = Modifier.weight(1f))
                Switch(
                    checked = config[spec.key] == true,
                    onCheckedChange = { enabled ->
                        com.example.tail.widget.TierBarWidgetConfig.setEnabled(context, spec.key, enabled)
                        config = com.example.tail.widget.TierBarWidgetConfig.load(context)
                        scope.launch {
                            com.example.tail.widget.TierBarWidgetProvider.refreshAll(context)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ChessReadinessSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    val context = LocalContext.current
    val enabled = settings.chessReadinessEnabled
    val chessPkg = settings.chessReadinessApp
    var showAppPicker by remember { mutableStateOf(false) }
    var showPuzzleHabitPicker by remember { mutableStateOf(false) }
    var showRushHabitPicker by remember { mutableStateOf(false) }
    var showSurvivalHabitPicker by remember { mutableStateOf(false) }
    var rushHighText by remember {
        mutableStateOf(
            ChessReadinessStore.lastRushAllTimeHigh(context)
                .takeIf { it > 0 }?.toString() ?: ""
        )
    }
    var rushHighSaved by remember { mutableStateOf(false) }

    // Resolve the associated app's display label
    val appLabel = remember(chessPkg) {
        if (chessPkg.isBlank()) null
        else try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(chessPkg, 0)).toString()
        } catch (e: Exception) {
            chessPkg
        }
    }

    Column {
        Text("♟ Chess Readiness", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Pre-session diagnostic that gates your chess activity. " +
                "Associate an app (your chess app): the bubble appears over it " +
                "and its menu gains a Chess Readiness option. Step-by-step flow: " +
                "Garmin sleep score (asked if missing), clarity sliders, three " +
                "timed rated puzzles, then a 3-minute Puzzle Rush — tap the " +
                "widget between steps to resume where you left off.",
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
                Text("Enable Chess Readiness", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (enabled) {
                        if (appLabel != null) "Bubble appears over $appLabel"
                        else "Select an associated app below"
                    } else "Diagnostic disabled",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (enabled) Color(0xFF66BB6A)
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { viewModel.setChessReadinessEnabled(it) }
            )
        }

        // Associated app picker (only when enabled)
        if (enabled) {
            // Readiness engine version selector (v1 / v2 / v3)
            Spacer(modifier = Modifier.height(12.dp))
            Text("Readiness Test Version", fontSize = 14.sp)
            Text(
                "v1 — the original sleep / clarity / puzzles / rush diagnostic.\n" +
                    "v2 — neurobiological gate: Garmin HRV & resting-HR Z-scores, " +
                    "a 3-minute vigilance test (PVT-B) and cognitive load balancing " +
                    "(ACWR).\n" +
                    "v3 — reflex + survival gate: a 2-minute reflex test (PVT-B) " +
                    "followed by a Puzzle Rush Survival session — solve real " +
                    "puzzles in the chess app and tap PASS per solve; one strike " +
                    "or the 5-minute cap fails the gate. The target scales with " +
                    "your CURRENT rating in the chess type selected below " +
                    "(PB is only the fallback when the rating is unknown). " +
                    "Below the guaranteed target, a pass is still possible at " +
                    "your own 70th-percentile history — but never under the " +
                    "hard minimum. " +
                    "All versions share the same history, Chess Guard " +
                    "enforcement and game-audit rules.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(
                "v1" to "v1 — Original diagnostic",
                "v2" to "v2 — Neurobiological gate",
                "v3" to "v3 — Reflex + Puzzle Rush Survival"
            ).forEach { (value, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.chessReadinessVersion == value,
                        onClick = { viewModel.setChessReadinessVersion(value) }
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // Post-game (Phase 2) audit engine selector — independent of the
            // pre-game readiness version above (any combination works).
            Spacer(modifier = Modifier.height(12.dp))
            Text("Post-Game Audit Version", fontSize = 14.sp)
            Text(
                "Which engine audits every shared rated game — the check that " +
                    "decides whether you keep playing, land in the yellow zone or " +
                    "the red zone.\n" +
                    "v1 — adaptive ΔE/strain evidence model (a single loss never " +
                    "ends the session; accumulated underperformance does).\n" +
                    "v2 — research-report system: 120-min fatigue ceiling, " +
                    "loss-streak stop rules (2 → yellow, 3 → red), tilt vector " +
                    "from your personal speed + accuracy Z-scores with a " +
                    "late-evening circadian adjustment, 7:28-day workload ratio " +
                    "and yellow-state hysteresis. Works with either readiness " +
                    "test version; Chess Guard enforcement is shared.\n" +
                    "v3 — hybrid: v2's rules with ΔE-weighted loss streaks " +
                    "(expected losses count 1.5, upsets 0.5), v1's strain " +
                    "accumulator with a readiness buffer, a readiness-scaled " +
                    "fatigue ceiling, and REAL unforced-blunder counts from " +
                    "desktop Stockfish via the Tail bridge (needs the bridge " +
                    "URL + token below; away from the PC the blunder rule " +
                    "simply stays inactive and everything else still works).\n" +
                    "v4 — data-derived: v3's hybrid audit with every threshold " +
                    "computed from your 6,500+ analyzed games (recency-" +
                    "weighted): personal per-time-control fatigue bars, a " +
                    "continuous loss-weight curve, your own circadian curve " +
                    "and a data-derived rest prescription. The profile is " +
                    "built on the PC (chess-coach build_v4_profile.py) and " +
                    "served via the bridge; without it v4 falls back to " +
                    "exact v3 behavior.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            listOf(
                "v1" to "v1 — Adaptive ΔE / strain audit",
                "v2" to "v2 — Tilt / fatigue / loss-chasing system",
                "v3" to "v3 — Hybrid + desktop Stockfish blunders",
                "v4" to "v4 — Data-derived personal thresholds"
            ).forEach { (value, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = settings.chessPhase2Version == value,
                        onClick = { viewModel.setChessPhase2Version(value) }
                    )
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            // v3 diagnostics — one tap verifies phone → bridge → Stockfish.
            if (settings.chessPhase2Version == "v3" ||
                settings.chessPhase2Version == "v4") {
                val analysisTestStatus by viewModel.chessAnalysisTestStatus.collectAsState()
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(onClick = { viewModel.testChessAnalysisPipeline() }) {
                        Text("♟ Test Analysis Pipeline", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "checks bridge + Stockfish with a tiny test game",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (analysisTestStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = analysisTestStatus,
                        fontSize = 11.sp,
                        color = if (analysisTestStatus.startsWith("✅"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Associated App", fontSize = 14.sp)
                    Text(
                        text = appLabel ?: "Not set",
                        fontSize = 11.sp,
                        color = if (appLabel != null) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error
                    )
                }
                Button(onClick = { showAppPicker = true }) {
                    Text(if (chessPkg.isBlank()) "Select App" else "Change", fontSize = 12.sp)
                }
            }

            // Chess Guard — hard enforcement layer over the readiness gate
            Spacer(modifier = Modifier.height(12.dp))
            Text("Chess Guard — Hard Enforcement", fontSize = 14.sp)
            Text(
                "Actually locks the chess app instead of just advising: " +
                    "opening it while blocked (failed test, lockout, or expired " +
                    "session) instantly returns you to the home screen with a " +
                    "full-screen explanation. GREEN unlocks everything; YELLOW " +
                    "opens the app for casual play only (unrated games & " +
                    "puzzles) with a full-screen warning on entry — a rated " +
                    "game detected in YELLOW, any game during a lockout, or " +
                    "playing instead of testing in the re-test window locks " +
                    "the ENTIRE app for 24 hours. The in-progress test itself " +
                    "is always allowed (its puzzle steps happen inside the " +
                    "chess app). Requires the Tail accessibility service below.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            var enforcementOn by remember {
                mutableStateOf(ChessReadinessStore.enforcementEnabledAt(context) > 0L)
            }
            var guardServiceLive by remember {
                mutableStateOf(com.example.tail.widget.ChessGuardService.isEnabled(context))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Block the chess app when not GREEN", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when {
                            !enforcementOn -> "Enforcement off — advisory only"
                            !guardServiceLive -> "⚠ Accessibility service not enabled"
                            else -> "Guard active — stopped you " +
                                "${ChessReadinessStore.guardBlockCount(context)} time(s)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            !enforcementOn -> MaterialTheme.colorScheme.onSurfaceVariant
                            !guardServiceLive -> MaterialTheme.colorScheme.error
                            else -> Color(0xFF66BB6A)
                        }
                    )
                }
                Switch(
                    checked = enforcementOn,
                    onCheckedChange = {
                        enforcementOn = it
                        viewModel.setChessEnforcementEnabled(it)
                        guardServiceLive =
                            com.example.tail.widget.ChessGuardService.isEnabled(context)
                    }
                )
            }
            if (enforcementOn && !guardServiceLive) {
                Row {
                    Button(onClick = {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS
                            )
                        )
                    }) {
                        Text("Enable Accessibility Service", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        guardServiceLive =
                            com.example.tail.widget.ChessGuardService.isEnabled(context)
                    }) {
                        Text("Re-check", fontSize = 12.sp)
                    }
                }
            }

            // Green pass-rate target — how often the gate should let you
            // through. User-adjustable, but changing it is deliberately
            // friction-loaded: the confirm step shows how long the current
            // target has been held and how much evidence has accumulated
            // under it, because frequent changes make it impossible to see
            // how the rating responds to a given pass rate.
            Spacer(modifier = Modifier.height(12.dp))
            Text("Green Pass-Rate Target", fontSize = 14.sp)
            Text(
                "The share of your readiness tests that should end GREEN " +
                    "(rated play authorized). The Green bar is placed so that, " +
                    "on average, about this share of attempts pass — lower = " +
                    "stricter. The Yellow casual-only band always covers " +
                    "roughly the next 25% below it.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            var greenTarget by remember {
                mutableStateOf(ChessReadinessStore.greenTargetPercent(context))
            }
            var pendingGreenTarget by remember { mutableStateOf<Int?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$greenTarget%",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    modifier = Modifier.width(48.dp)
                )
                Slider(
                    value = greenTarget.toFloat(),
                    onValueChange = { greenTarget = (it / 5f).toInt() * 5 },
                    valueRange = 5f..95f,
                    steps = 17, // snaps to 5% increments (5, 10, …, 95)
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { pendingGreenTarget = greenTarget }) {
                    Text("Apply", fontSize = 12.sp)
                }
            }
            pendingGreenTarget?.let { pending ->
                val changedAt = remember(pending) {
                    ChessReadinessStore.greenTargetChangedAt(context)
                }
                val evidence = remember(pending) {
                    val tests = ChessReadinessStore.loadHistory(context)
                        .count { changedAt == 0L || it.timestamp >= changedAt }
                    val games = com.example.tail.widget.ChessReadinessLogStore
                        .loadGames(context)
                        .count { changedAt == 0L || it.endTimeMs >= changedAt }
                    tests to games
                }
                val heldDays = if (changedAt > 0L) {
                    (System.currentTimeMillis() - changedAt) / 86_400_000L
                } else -1L
                Text(
                    "⚠ Frequent changes make it impossible to tell how your " +
                        "rating responds to a given pass rate — hold one value " +
                        "long enough to log plenty of games (a few weeks at " +
                        "least)." +
                        if (heldDays >= 0) " Current target held for $heldDays " +
                            "day(s): ${evidence.first} test(s) and " +
                            "${evidence.second} game(s) logged under it."
                        else " No change recorded yet: ${evidence.first} " +
                            "test(s) and ${evidence.second} game(s) logged.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row {
                    Button(onClick = {
                        ChessReadinessStore.saveGreenTargetPercent(context, pending)
                        pendingGreenTarget = null
                    }) {
                        Text("Confirm change", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = {
                        greenTarget = ChessReadinessStore.greenTargetPercent(context)
                        pendingGreenTarget = null
                    }) {
                        Text("Cancel", fontSize = 12.sp)
                    }
                }
            }

            // 3-Minute Puzzle Rush all-time best (readiness baseline)
            Spacer(modifier = Modifier.height(12.dp))
            Text("3-Minute Puzzle Rush — All-Time Best", fontSize = 14.sp)
            Text(
                "The readiness test only asks how many puzzles you solved and " +
                    "how many failures — your run is scored against this best " +
                    "(minimum baseline 10). It also updates automatically here " +
                    "whenever a test run beats it.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = rushHighText,
                    onValueChange = {
                        rushHighText = it.filter { c -> c.isDigit() }.take(3)
                        rushHighSaved = false
                    },
                    label = { Text("Best score (puzzles solved)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        ChessReadinessStore.saveRushAllTimeHigh(
                            context, rushHighText.toIntOrNull() ?: 0
                        )
                        rushHighSaved = true
                    },
                    enabled = rushHighText.isNotBlank()
                ) {
                    Text(if (rushHighSaved) "✓ Saved" else "Save", fontSize = 12.sp)
                }
            }
            val storedHigh = rushHighText.toIntOrNull() ?: 0
            if (storedHigh > 0) {
                Text(
                    "Readiness baseline: max($storedHigh, 10) = ${maxOf(storedHigh, 10)}",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // Chess type whose current rating drives the v3 survival target.
            if (settings.chessReadinessVersion == "v3") {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Chess type for the readiness gate", fontSize = 14.sp)
                Text(
                    "Pick the variant that best represents the chess you " +
                        "play. Its CURRENT chess.com rating drives the " +
                        "survival target — a higher rating means a harder " +
                        "gate (roughly +1 puzzle per 30 rating points).",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                val ctx = LocalContext.current
                var selVariant by remember {
                    mutableStateOf(
                        com.example.tail.widget.ChessReadinessV3Store
                            .selectedVariant(ctx)
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("bullet", "blitz", "rapid", "chess960").forEach { v ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selVariant == v,
                                onClick = {
                                    selVariant = v
                                    com.example.tail.widget.ChessReadinessV3Store
                                        .saveSelectedVariant(ctx, v)
                                }
                            )
                            Text(v, fontSize = 12.sp)
                        }
                    }
                }
            }

            // Puzzle Rush Survival — all-time PB (v3 FALLBACK target).
            // Chess.com API sync + manual override (the API cache can lag
            // up to 12 h, so both paths exist).
            if (settings.chessReadinessVersion == "v3") {
                Spacer(modifier = Modifier.height(12.dp))
                Text("Puzzle Rush Survival — All-Time PB", fontSize = 14.sp)
                Text(
                    "The v3 survival gate target scales with your CURRENT " +
                        "rating in the chess type selected above. The " +
                        "all-time PB below is only the FALLBACK target when a " +
                        "variant rating is unknown — pushing your untimed PB " +
                        "high no longer makes the gate harder. Sync the PB " +
                        "from Chess.com (puzzle_rush.best.score — its cache can " +
                        "lag up to 12 h) or enter it manually; a manual value " +
                        "overrides the sync until beaten.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                var survivalPbText by remember {
                    mutableStateOf(
                        com.example.tail.widget.ChessReadinessV3Store.survivalPb(context)
                            .takeIf { it > 0 }?.toString() ?: ""
                    )
                }
                var survivalPbSaved by remember { mutableStateOf(false) }
                val survivalSyncStatus by viewModel.survivalPbSyncStatus.collectAsState()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = survivalPbText,
                        onValueChange = {
                            survivalPbText = it.filter { c -> c.isDigit() }.take(4)
                            survivalPbSaved = false
                        },
                        label = { Text("Survival PB (puzzle rush best)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            com.example.tail.widget.ChessReadinessV3Store.saveSurvivalPbManual(
                                context, survivalPbText.toIntOrNull() ?: 0
                            )
                            survivalPbSaved = true
                        },
                        enabled = survivalPbText.isNotBlank()
                    ) {
                        Text(if (survivalPbSaved) "✓ Saved" else "Save", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { viewModel.syncSurvivalPbFromChessCom() }) {
                        Text("↻ Sync from Chess.com", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    val storedPb = survivalPbText.toIntOrNull() ?: 0
                    val effPb = if (storedPb > 0) storedPb
                        else com.example.tail.widget.ChessReadinessV3Store.survivalPb(context)
                    Text(
                        "Target: ${com.example.tail.widget.ChessReadinessV3Engine.targetScore(effPb)} puzzles",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                if (survivalSyncStatus.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = survivalSyncStatus,
                        fontSize = 11.sp,
                        color = if (survivalSyncStatus.startsWith("✅"))
                            MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
                    )
                }
            }

            // Linked habits — puzzle/rush activity in the readiness test
            // also credits these habits (minutes + 1 session each).
            Spacer(modifier = Modifier.height(12.dp))
            Text("Habit Links", fontSize = 14.sp)
            Text(
                "Get habit credit for the puzzle steps of the readiness test: " +
                    "each step's minutes are added to the habit's minutes value " +
                    "(Rated Puzzles credit their solve time — a failed attempt " +
                    "counts as 3 min — and Puzzle Rush credits its 3-minute " +
                    "run), plus +1 session per puzzle/run.\n" +
                    "The Puzzle Rush habit also drives the results dialog: " +
                    "when its timer stops (or it is manually incremented), " +
                    "the Puzzle Rush report prompt opens to log the run.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            HabitLinkRow(
                label = "Rated Puzzles habit",
                habit = ChessReadinessStore.linkedPuzzleHabit(context),
                onPick = { showPuzzleHabitPicker = true }
            )
            Spacer(modifier = Modifier.height(4.dp))
            HabitLinkRow(
                label = "Puzzle Rush habit",
                habit = ChessReadinessStore.linkedRushHabit(context),
                onPick = { showRushHabitPicker = true }
            )
            if (settings.chessReadinessVersion == "v3") {
                Spacer(modifier = Modifier.height(4.dp))
                HabitLinkRow(
                    label = "Puzzle Rush Survival habit",
                    habit = com.example.tail.widget.ChessReadinessV3Store
                        .linkedSurvivalHabit(context),
                    onPick = { showSurvivalHabitPicker = true }
                )
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            context = context,
            onConfirm = { packageName, _ ->
                viewModel.setChessReadinessApp(packageName)
                showAppPicker = false
            },
            onDismiss = { showAppPicker = false }
        )
    }

    if (showPuzzleHabitPicker) {
        HabitPickerDialog(
            title = "Rated Puzzles habit",
            habits = viewModel.getAllHabitNames(),
            onPick = {
                ChessReadinessStore.saveLinkedPuzzleHabit(context, it)
                showPuzzleHabitPicker = false
            },
            onDismiss = { showPuzzleHabitPicker = false }
        )
    }

    if (showRushHabitPicker) {
        HabitPickerDialog(
            title = "Puzzle Rush habit",
            habits = viewModel.getAllHabitNames(),
            onPick = {
                ChessReadinessStore.saveLinkedRushHabit(context, it)
                showRushHabitPicker = false
            },
            onDismiss = { showRushHabitPicker = false }
        )
    }

    if (showSurvivalHabitPicker) {
        HabitPickerDialog(
            title = "Puzzle Rush Survival habit",
            habits = viewModel.getAllHabitNames(),
            onPick = {
                com.example.tail.widget.ChessReadinessV3Store
                    .saveLinkedSurvivalHabit(context, it)
                showSurvivalHabitPicker = false
            },
            onDismiss = { showSurvivalHabitPicker = false }
        )
    }
}

/**
 * One linked-habit row in the Chess Readiness settings: label, current
 * habit (or "None"), and a pick/change button.
 */
@Composable
private fun HabitLinkRow(label: String, habit: String, onPick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp)
            Text(
                if (habit.isBlank()) "None" else habit,
                fontSize = 11.sp,
                color = if (habit.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.primary
            )
        }
        Button(onClick = onPick) {
            Text(if (habit.isBlank()) "Link" else "Change", fontSize = 12.sp)
        }
    }
}

/**
 * Dialog listing EVERY habit (from [com.example.tail.ui.HabitViewModel.getAllHabitNames])
 * plus a "None" option; tapping one selects it immediately.
 */
@Composable
private fun HabitPickerDialog(
    title: String,
    habits: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                item {
                    Text(
                        "None (no habit)",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick("") }
                            .padding(vertical = 10.dp)
                    )
                    HorizontalDivider()
                }
                itemsIndexed(habits) { i, name ->
                    Text(
                        name,
                        fontSize = 14.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(name) }
                            .padding(vertical = 10.dp)
                    )
                    if (i < habits.lastIndex) HorizontalDivider()
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Cancel") }
        }
    )
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
