package com.example.tail.ui

// Split out of HabitGridScreen.kt (2026-08-29) to keep individual
// Kotlin source files small enough for IR lowering on this machine.

import kotlin.text.toIntOrNull
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import com.example.tail.data.backup.HabitRestorePreview
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.State
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.window.Dialog
import com.example.tail.data.AiIcon
import com.example.tail.data.AiIconRepository
import com.example.tail.data.HabitNotification
import com.example.tail.data.AppIconInfo
import com.example.tail.data.ACTIVITY_ID_PREFIX
import com.example.tail.data.AppIconRepository
import com.example.tail.data.encodeShortcutEntry
import com.example.tail.data.findShortcutInfo
import com.example.tail.data.isActivityEntry
import com.example.tail.data.launchShortcutEntry
import com.example.tail.data.parseShortcutEntry
import com.example.tail.data.queryAppShortcuts
import com.example.tail.data.appIconMonochromeOf
import com.example.tail.data.appIconNameOf
import com.example.tail.data.isAppIconName
import com.example.tail.data.isTextIconName
import com.example.tail.data.renderTextIconBitmap
import com.example.tail.data.textIconCharOf
import com.example.tail.data.textIconNameOf
import com.example.tail.data.BridgeMovie
import com.example.tail.data.ChessComType
import com.example.tail.data.GarminType
import com.example.tail.data.GitHubMetric
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.RollingHigh
import com.example.tail.data.GRAPH_METRIC_POINTS
import com.example.tail.data.GRAPH_METRIC_VALUE2
import com.example.tail.data.GRAPH_METRIC_VALUE3
import com.example.tail.data.defaultLabelForValueKey
import com.example.tail.data.displayLabelForValue
import com.example.tail.data.effectiveConditionalLinkValueKey
import com.example.tail.data.appLinkPackageName
import com.example.tail.data.isAppLink
import com.example.tail.data.meal.VisionQueueRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun RestoreFromBackupButton(onClick: () -> Unit) {
    Spacer(modifier = Modifier.height(10.dp))
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1A1A3A),
            contentColor = Color(0xFF88AAFF)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = "Restore from backup",
            tint = Color(0xFF88AAFF),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text("↩ Restore from Backup", fontSize = 12.sp)
    }
}

/**
 * Garmin link toggle for the habit edit panel.
 * Extracted to its own composable to keep [EditModeControlBar]
 * under the JVM method-size limit.
 */


@Composable
internal fun GarminLinkToggleSection(
    selectedHabitName: String,
    garminHabitLinks: Map<String, String>,
    onSetGarminLink: (String, String?) -> Unit
) {
    Spacer(modifier = Modifier.height(6.dp))

    val currentGarminLink = garminHabitLinks[selectedHabitName]
    val isGarminLinked = currentGarminLink != null
    var garminDropdownExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "⌚ Garmin", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isGarminLinked) {
                    val typeName = GarminType.fromKey(currentGarminLink)?.label ?: currentGarminLink
                    "Linked to: $typeName"
                } else "Not linked to Garmin",
                color = if (isGarminLinked) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isGarminLinked,
            onCheckedChange = { checked ->
                if (checked) {
                    garminDropdownExpanded = true
                } else {
                    onSetGarminLink(selectedHabitName, null)
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66BB6A),
                checkedTrackColor = Color(0xFF1B5E20),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    // Garmin type picker dropdown
    if (isGarminLinked || garminDropdownExpanded) {
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            Button(
                onClick = { garminDropdownExpanded = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                modifier = Modifier.height(32.dp)
            ) {
                val label = if (currentGarminLink != null) {
                    GarminType.fromKey(currentGarminLink)?.label ?: "Select type"
                } else "Select type"
                Text(label, fontSize = 11.sp, color = Color(0xFF66BB6A))
            }
            DropdownMenu(
                expanded = garminDropdownExpanded,
                onDismissRequest = { garminDropdownExpanded = false }
            ) {
                GarminType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        onClick = {
                            onSetGarminLink(selectedHabitName, type.name)
                            garminDropdownExpanded = false
                        }
                    )
                }
            }
        }
    }
}

/**
 * GitHub link toggle section for the habit edit panel.
 *
 * When enabled, shows a text input for a public GitHub repo URL and a metric
 * selector (Lines Changed, Commits, Additions, Deletions). Entering a URL
 * automatically triggers a full history backfill.
 *
 * Extracted to its own composable to keep [EditModeControlBar]
 * under the JVM method-size limit.
 */


@Composable
internal fun GitHubLinkToggleSection(
    habitName: String,
    repoUrls: Map<String, String>,
    metrics: Map<String, String>,
    syncStatus: String,
    onSetRepoUrl: (String?) -> Unit,
    onSetMetric: (String) -> Unit,
    onRefetch: () -> Unit
) {
    val currentUrl = repoUrls[habitName]
    val isLinked = currentUrl != null
    var urlInput by remember(habitName, currentUrl) {
        mutableStateOf(currentUrl ?: "")
    }
    var showUrlInput by remember(habitName) { mutableStateOf(false) }
    var metricDropdownExpanded by remember { mutableStateOf(false) }
    val currentMetric = GitHubMetric.fromKey(metrics[habitName])

    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "🐙 GitHub", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isLinked) "Linked: ${currentUrl?.take(40)}" else "Track a public GitHub repo",
                color = if (isLinked) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isLinked || showUrlInput,
            onCheckedChange = { checked ->
                if (checked) {
                    showUrlInput = true
                } else {
                    onSetRepoUrl(null)
                    showUrlInput = false
                    urlInput = ""
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFAA88FF),
                checkedTrackColor = Color(0xFF2A1A4A),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    if (isLinked || showUrlInput || urlInput.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))

        // URL input
        OutlinedTextField(
            value = urlInput,
            onValueChange = { urlInput = it },
            label = { Text("GitHub Repo URL", fontSize = 11.sp) },
            placeholder = { Text("https://github.com/owner/repo", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFFCCCCCC))
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Metric selector dropdown
            Box(modifier = Modifier.weight(1f)) {
                Button(
                    onClick = { metricDropdownExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1A4A)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("📊 ${currentMetric.label}", fontSize = 11.sp, color = Color(0xFFAA88FF))
                }
                DropdownMenu(
                    expanded = metricDropdownExpanded,
                    onDismissRequest = { metricDropdownExpanded = false }
                ) {
                    GitHubMetric.entries.forEach { metric ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(metric.label, fontSize = 12.sp)
                                    Text(metric.description, fontSize = 10.sp, color = Color(0xFF888888))
                                }
                            },
                            onClick = {
                                onSetMetric(metric.name)
                                metricDropdownExpanded = false
                            }
                        )
                    }
                }
            }

            // Save/apply URL button
            Button(
                onClick = {
                    if (urlInput.isNotBlank()) {
                        onSetRepoUrl(urlInput.trim())
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A1A4A)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Apply", fontSize = 11.sp, color = Color(0xFFAA88FF))
            }

            // Cancel button (only when not yet linked)
            if (showUrlInput && !isLinked) {
                Button(
                    onClick = {
                        showUrlInput = false
                        urlInput = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A1A)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("Cancel", fontSize = 11.sp, color = Color(0xFF888888))
                }
            }
        }

        // Re-fetch button
        if (isLinked) {
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onRefetch,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A1A3A)),
                modifier = Modifier.fillMaxWidth().height(32.dp)
            ) {
                Text("🔄 Re-fetch History", fontSize = 11.sp, color = Color(0xFF88AAFF))
            }
        }

        // Sync status
        if (syncStatus.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = syncStatus,
                color = Color(0xFFAAAAAA),
                fontSize = 10.sp,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Movie Bridge link toggle for the habit edit panel.
 * Extracted to its own composable to keep [EditModeControlBar]
 * under the JVM method-size limit.
 */


@Composable
internal fun MovieBridgeToggleSection(
    isMovieLinked: Boolean,
    onToggle: () -> Unit
) {
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "🎬 Movie Bridge", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isMovieLinked)
                    "Auto-suggests latest desktop movie"
                else "Not linked to movie bridge",
                color = if (isMovieLinked) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isMovieLinked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66BB6A),
                checkedTrackColor = Color(0xFF1B5E20),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

/**
 * PC Widget toggle for the habit edit panel — adds/removes the habit as a
 * timer square on the desktop floating bubble widget. The phone pushes the
 * list to the Tail Bridge; the PC widget mirrors it live.
 * Extracted to its own composable to keep [EditModeControlBar] under the
 * JVM method-size limit.
 */


@Composable
internal fun PcWidgetToggleSection(
    isOnPcWidget: Boolean,
    syncConfigured: Boolean,
    onToggle: () -> Unit
) {
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "🖥 PC Widget", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = when {
                    !syncConfigured -> "Connect the Tail Bridge (Garmin) in Settings first"
                    isOnPcWidget -> "Timer square on the PC bubble widget"
                    else -> "Not shown on the PC widget"
                },
                color = if (isOnPcWidget && syncConfigured) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isOnPcWidget,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66BB6A),
                checkedTrackColor = Color(0xFF1B5E20),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

// ── Restore-from-backup confirmation (single habit) ───────────────────────────

/**
 * Confirmation dialog shown before restoring a single habit from a backup file.
 * Summarises the increment delta and last date so the user knows exactly what
 * will change. Only the selected habit is affected.
 */


@Composable
internal fun HabitRestoreConfirmDialog(
    preview: HabitRestorePreview,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val delta = preview.incrementDelta
    val deltaColor = when {
        delta > 0 -> Color(0xFF66BB6A)
        delta < 0 -> Color(0xFFEF5350)
        else -> Color(0xFFCCCCCC)
    }
    val deltaText = when {
        delta > 0 -> "+$delta increments will be gained"
        delta < 0 -> "$delta increments will be lost"
        else -> "No net change in total increments"
    }
    val backupDateLabel = preview.backupExportedAt
        .substringBefore('.')
        .replace("T", " ")
        .ifBlank { "unknown date" }
    val canRestore = preview.backupTotal > 0 || preview.hasSubtypeData ||
            preview.hasTimedData || preview.hasTextInputData || preview.hasDatedEntryData

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Restore \"${preview.habitName}\" from backup?",
                color = Color(0xFF88AAFF),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Backup created: $backupDateLabel",
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Current total: ${preview.currentTotal} increments",
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
            Text(
                text = "Backup total: ${preview.backupTotal} increments over ${preview.backupDayCount} day(s)",
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = deltaText,
                color = deltaColor,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            preview.backupLastDate?.let { date ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Data up to: $date",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )
            }
            // List any extra per-habit data that will also be restored.
            val extras = buildList {
                if (preview.hasSubtypeData) add("subtype data")
                if (preview.hasTimedData) add("timed sessions")
                if (preview.hasTextInputData) add("text-input log")
                if (preview.hasDatedEntryData) add("dated-entry file")
            }
            if (extras.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Also restores: ${extras.joinToString(", ")}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "This overwrites the current data for \"${preview.habitName}\" only. " +
                        "Other habits are not touched. This cannot be undone.",
                color = Color(0xFFEF9A9A),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onConfirm,
                    enabled = canRestore,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A1A3A),
                        disabledContainerColor = Color(0xFF2A2A2A)
                    )
                ) {
                    Text(
                        "Restore",
                        color = if (canRestore) Color(0xFF88AAFF) else Color(0xFF555566)
                    )
                }
            }
        }
    }
}

// ── Icon picker dialog ────────────────────────────────────────────────────────


@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun IconPickerDialog(
    habitName: String,
    currentIconName: String?,
    onIconSelected: (String?) -> Unit,
    onDismiss: () -> Unit,
    viewModel: HabitViewModel
) {
    val settings by viewModel.settings.collectAsState()
    val aiIcons by viewModel.aiIcons.collectAsState()
    val aiIconGenerating by viewModel.aiIconGenerating.collectAsState()
    val aiIconError by viewModel.aiIconError.collectAsState()
    var aiPrompt by remember { mutableStateOf("") }
    // AI icon pending delete confirmation (null = no confirmation pending)
    var deleteConfirmAiIcon by remember { mutableStateOf<AiIcon?>(null) }
    // Section: 0 = built-in + AI icons, 1 = installed-app icons, 2 = text/emoji icons.
    // Opens on the section matching the habit's current icon type.
    var pickerSection by remember {
        mutableStateOf(
            when {
                isTextIconName(currentIconName) -> 2
                isAppIconName(currentIconName) -> 1
                else -> 0
            }
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Choose Icon — $habitName",
                color = Color(0xFF88FFFF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))

            // "No icon" option
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onIconSelected(null) }
                    .background(
                        if (currentIconName == null) Color(0xFF003A3A) else Color.Transparent,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✕  No icon",
                    color = if (currentIconName == null) Color(0xFF88FFFF) else Color(0xFF888888),
                    fontSize = 12.sp
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
            Spacer(modifier = Modifier.height(6.dp))

            // Section toggle: built-in/AI icons ↔ installed-app icons ↔ text/emoji
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                IconPickerModeTab(
                    label = "Icons",
                    selected = pickerSection == 0,
                    modifier = Modifier.weight(1f),
                    onClick = { pickerSection = 0 }
                )
                IconPickerModeTab(
                    label = "📱 Apps",
                    selected = pickerSection == 1,
                    modifier = Modifier.weight(1f),
                    onClick = { pickerSection = 1 }
                )
                IconPickerModeTab(
                    label = "🔤 Text",
                    selected = pickerSection == 2,
                    modifier = Modifier.weight(1f),
                    onClick = { pickerSection = 2 }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))

            if (pickerSection == 2) {
                // ── Text (letter/emoji) icons section ────────────────────────
                TextIconPickerSection(
                    currentIconName = currentIconName,
                    onIconSelected = onIconSelected
                )
            } else if (pickerSection == 1) {
                // ── Installed App Icons section ──────────────────────────────
                AppIconPickerSection(
                    currentIconName = currentIconName,
                    onIconSelected = onIconSelected,
                    aiIconsEnabled = settings.aiIconsEnabled
                )
            } else {

                // Scrollable grid of all icons — 6 columns
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (settings.aiIconsEnabled) 260.dp else 400.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(ALL_ICON_NAMES) { iconName ->
                        val resId = ICON_NAME_TO_RES[iconName]
                        val isSelected = iconName == currentIconName
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .background(
                                    if (isSelected) Color(0xFF003A3A) else Color(0xFF2A2A2A),
                                    RoundedCornerShape(4.dp)
                                )
                                .then(
                                    if (isSelected) Modifier.border(1.dp, Color(0xFF88FFFF), RoundedCornerShape(4.dp))
                                    else Modifier
                                )
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { onIconSelected(iconName) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (resId != null) {
                                Image(
                                    painter = painterResource(id = resId),
                                    contentDescription = iconName,
                                    modifier = Modifier.size(28.dp),
                                    colorFilter = ColorFilter.tint(Color.White)
                                )
                            } else {
                                Text("?", color = Color(0xFF666666), fontSize = 10.sp)
                            }
                        }
                    }
                }

                // ── AI Generated Icons section ───────────────────────────────────
                if (settings.aiIconsEnabled) {
                    Spacer(modifier = Modifier.height(6.dp))
                    HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "🤖 AI Generated Icons",
                            color = Color(0xFFAADDFF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        if (aiIcons.isNotEmpty()) {
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "long-press to delete",
                                color = Color(0xFF666666),
                                fontSize = 9.sp
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))

                    // Show existing AI icons in a grid
                    if (aiIcons.isNotEmpty()) {
                        val aiIconRepo = viewModel.getAiIconRepo()
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(6),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(aiIcons) { aiIcon ->
                                val isSelected = aiIcon.id == currentIconName
                                val bitmap = remember(aiIcon.id) {
                                    aiIconRepo.loadBitmap(aiIcon.id)
                                }
                                Box(
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .background(
                                            if (isSelected) Color(0xFF003A3A) else Color(0xFF2A2A2A),
                                            RoundedCornerShape(4.dp)
                                        )
                                        .then(
                                            if (isSelected) Modifier.border(1.dp, Color(0xFFAADDFF), RoundedCornerShape(4.dp))
                                            else Modifier
                                        )
                                        .combinedClickable(
                                            indication = null,
                                            interactionSource = remember { MutableInteractionSource() },
                                            onClick = { onIconSelected(aiIcon.id) },
                                            onLongClick = { deleteConfirmAiIcon = aiIcon }
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (bitmap != null) {
                                        Image(
                                            bitmap = bitmap.asImageBitmap(),
                                            contentDescription = aiIcon.prompt,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    } else {
                                        Text("?", color = Color(0xFF666666), fontSize = 10.sp)
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // Generate new AI icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = aiPrompt,
                            onValueChange = { aiPrompt = it },
                            placeholder = { Text("Describe icon…", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFAADDFF),
                                unfocusedBorderColor = Color(0xFF444444),
                                cursorColor = Color(0xFFAADDFF)
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Button(
                            onClick = {
                                if (aiPrompt.isNotBlank()) {
                                    // Start background generation tied to this habit and
                                    // close the picker — the icon is applied automatically
                                    // when it lands (toast + tile spinner in the grid).
                                    viewModel.generateAiIcon(aiPrompt.trim(), habitName)
                                    onDismiss()
                                }
                            },
                            enabled = !aiIconGenerating && aiPrompt.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1A4A5A)
                            )
                        ) {
                            if (aiIconGenerating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFFAADDFF),
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text("Generate", fontSize = 10.sp)
                            }
                        }
                    }

                    // Error message
                    aiIconError?.let { error ->
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = error,
                            color = Color(0xFFFF6666),
                            fontSize = 10.sp,
                            maxLines = 2
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            }
        }
    }

    // Delete confirmation dialog for AI icons
    deleteConfirmAiIcon?.let { aiIcon ->
        val aiIconRepo = viewModel.getAiIconRepo()
        val bitmap = remember(aiIcon.id) { aiIconRepo.loadBitmap(aiIcon.id) }
        Dialog(onDismissRequest = { deleteConfirmAiIcon = null }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Delete AI Icon?",
                    color = Color(0xFFFF6666),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Show the icon preview
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = aiIcon.prompt,
                            modifier = Modifier.size(48.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // Show the prompt
                Text(
                    text = "\"${aiIcon.prompt}\"",
                    color = Color(0xFF888888),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(onClick = { deleteConfirmAiIcon = null }) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                    Button(
                        onClick = {
                            viewModel.deleteAiIcon(aiIcon.id)
                            deleteConfirmAiIcon = null
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF661111)
                        )
                    ) {
                        Text("Delete", color = Color(0xFFFF6666))
                    }
                }
            }
        }
    }
}

/**
 * One of the two mode tabs at the top of [IconPickerDialog]
 * ("Icons" / "📱 App Icons").
 */


@Composable
internal fun IconPickerModeTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
            .background(
                if (selected) Color(0xFF003A3A) else Color(0xFF2A2A2A),
                RoundedCornerShape(6.dp)
            )
            .then(
                if (selected) Modifier.border(1.dp, Color(0xFF88FFFF), RoundedCornerShape(6.dp))
                else Modifier
            )
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF88FFFF) else Color(0xFF888888),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * The "Text" section of [IconPickerDialog]: lets the user type a single
 * letter or emoji to use as the habit icon. Selecting it stores a
 * "text:<character>" icon name, which renderers resolve to a greyscale
 * bitmap of the character (see [renderTextIconBitmap]).
 */


@Composable
internal fun TextIconPickerSection(
    currentIconName: String?,
    onIconSelected: (String?) -> Unit
) {
    var input by remember { mutableStateOf(textIconCharOf(currentIconName) ?: "") }
    val trimmed = input.trim()
    val isSelected = isTextIconName(currentIconName) && textIconCharOf(currentIconName) == trimmed
    // Preview uses the exact greyscale bitmap the habit grid will render
    val previewBitmap = remember(trimmed) {
        if (trimmed.isNotEmpty()) renderTextIconBitmap(trimmed, 160) else null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Enter a letter or emoji to use as the icon:",
            color = Color(0xFF888888),
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.codePointCount(0, it.length) <= 2) input = it },
                placeholder = { Text("A, 🧘, ♟…", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(fontSize = 16.sp, color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF88FFFF),
                    unfocusedBorderColor = Color(0xFF444444),
                    cursorColor = Color(0xFF88FFFF)
                )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .then(
                        if (isSelected) Modifier.border(1.dp, Color(0xFF88FFFF), RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .background(Color(0xFF2A2A2A), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (previewBitmap != null) {
                    Image(
                        bitmap = previewBitmap.asImageBitmap(),
                        contentDescription = "Greyscale icon preview",
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    Text("?", color = Color(0xFF666666), fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Shown in the grid as a greyscale icon",
            color = Color(0xFF666666),
            fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        Button(
            onClick = { onIconSelected(textIconNameOf(trimmed)) },
            enabled = trimmed.isNotEmpty(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF003A3A),
                disabledContainerColor = Color(0xFF2A2A2A)
            ),
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Use icon", fontSize = 11.sp, color = Color(0xFF88FFFF))
        }
    }
}

/**
 * The "App Icons" section of [IconPickerDialog]: a searchable list of
 * installed apps. Selecting an app stores an "app:<packageName>" icon name,
 * which renderers resolve to the app's launcher icon at draw time. The style
 * toggle at the top chooses WHICH icon of the app is stored: the full-colour
 * launcher icon, or its black/white notification-style icon ("app:<pkg>#mono"
 * — the adaptive icon's monochrome layer, falling back to a greyscale
 * rendering of the launcher icon for apps without one).
 */


@Composable
internal fun AppIconPickerSection(
    currentIconName: String?,
    onIconSelected: (String?) -> Unit,
    aiIconsEnabled: Boolean
) {
    val context = LocalContext.current
    val appIconRepo = remember { AppIconRepository(context) }
    var searchQuery by remember { mutableStateOf("") }
    // Which icon style tapping an app selects: false = full-colour launcher
    // icon, true = black/white notification-style icon. Opens on the style of
    // the habit's current icon when it already is an app icon.
    var monoStyle by remember { mutableStateOf(appIconMonochromeOf(currentIconName)) }
    // Null while the installed-app list is loading (first composition only).
    var allApps by remember { mutableStateOf<List<AppIconInfo>?>(null) }

    LaunchedEffect(Unit) {
        allApps = appIconRepo.listLaunchableApps()
    }

    val filteredApps = remember(searchQuery, allApps) {
        val apps = allApps ?: emptyList()
        if (searchQuery.isBlank()) apps
        else apps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    // Keep the dialog the same total height as the built-in icons grid
    // (the style toggle + hint shrink the list accordingly).
    val listHeight = (if (aiIconsEnabled) 200.dp else 340.dp) - 40.dp

    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search app name…", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = Color(0xFF66CCFF),
                unfocusedBorderColor = Color(0xFF444444),
                cursorColor = Color(0xFF66CCFF)
            )
        )
        Spacer(modifier = Modifier.height(6.dp))

        // Icon style toggle: full-colour app icon ↔ black/white notification icon.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconPickerModeTab(
                label = "🎨 App icon",
                selected = !monoStyle,
                modifier = Modifier.weight(1f),
                onClick = { monoStyle = false }
            )
            IconPickerModeTab(
                label = "🖤 B/W icon",
                selected = monoStyle,
                modifier = Modifier.weight(1f),
                onClick = { monoStyle = true }
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (monoStyle)
                "Uses the app's black/white notification icon (greyscale fallback if it has none)"
            else
                "Uses the app's full-colour launcher icon",
            color = Color(0xFF666666),
            fontSize = 9.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (allApps == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = Color(0xFF66CCFF),
                    strokeWidth = 2.dp
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(listHeight)
            ) {
                lazyItems(filteredApps, key = { it.packageName }) { app ->
                    val isSelected = appIconNameOf(app.packageName, monoStyle) == currentIconName
                    // Preview shows exactly what the habit grid will render
                    // for the currently selected style.
                    val iconBitmap = remember(app.packageName, monoStyle) {
                        appIconRepo.loadIconBitmap(app.packageName, monoStyle)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onIconSelected(appIconNameOf(app.packageName, monoStyle)) }
                            .background(
                                if (isSelected) Color(0xFF003A3A) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap.asImageBitmap(),
                                contentDescription = app.label,
                                modifier = Modifier.size(26.dp)
                            )
                        } else {
                            Box(modifier = Modifier.size(26.dp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = app.label,
                            color = if (isSelected) Color(0xFF88FFFF) else Color.White,
                            fontSize = 12.sp,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// ── Info panel ────────────────────────────────────────────────────────────────


@Composable
fun HabitInfoPanel(
    habit: Habit?,
    modifier: Modifier = Modifier,
    garminHabitLinks: Map<String, String> = emptyMap()
) {
    val panelBg = Color(0xFF1A2E1A)
    val labelColor = Color(0xFF88CC88)
    val valueColor = Color(0xFFCCEECC)
    val dimColor = Color(0xFF889988)

    Box(
        modifier = modifier
            .background(panelBg, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        if (habit == null) {
            Text(
                text = "ℹ Tap any habit button to see its stats",
                color = dimColor,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                Text(
                    text = habit.name,
                    color = Color(0xFF66DD66),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                val streakLabel = if (habit.currentStreak >= 0) "Current streak" else "Current antistreak"
                val streakVal = if (habit.currentStreak >= 0) "+${habit.currentStreak}" else "${habit.currentStreak}"
                val streakColor = if (habit.currentStreak >= 0) Color(0xFF80FF80) else Color(0xFFFF8080)
                InfoRow(label = streakLabel, value = streakVal, valueColor = streakColor)
                InfoRow(label = "Longest streak", value = habit.longestStreak.toString(), valueColor = valueColor)

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "(current) All time high - date:",
                    color = labelColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(3.dp))

                val garminType = garminHabitLinks[habit.name]?.let { GarminType.fromKey(it) }

                InfoRow(
                    label = "day",
                    value = formatRollingRow(
                        currentVal = habit.currentDayValue.toDouble(),
                        high = RollingHigh(habit.allTimeHighDay.toDouble(), habit.allTimeHighDayDate),
                        garminType = garminType
                    ),
                    valueColor = valueColor,
                    labelColor = dimColor
                )
                InfoRow(
                    label = "week",
                    value = formatRollingRow(currentVal = habit.avgLast7Days, high = habit.allTimeHighWeek, garminType = garminType),
                    valueColor = valueColor,
                    labelColor = dimColor
                )
                InfoRow(
                    label = "month",
                    value = formatRollingRow(currentVal = habit.avgLast30Days, high = habit.allTimeHighMonth, garminType = garminType),
                    valueColor = valueColor,
                    labelColor = dimColor
                )
                InfoRow(
                    label = "year",
                    value = formatRollingRow(currentVal = habit.avgLast365Days, high = habit.allTimeHighYear, garminType = garminType),
                    valueColor = valueColor,
                    labelColor = dimColor
                )
            }
        }
    }
}


internal fun formatRollingRow(
    currentVal: Double,
    high: RollingHigh,
    garminType: GarminType? = null
): String {
    val cur = if (garminType == GarminType.DISTANCE_METERS) {
        "${currentVal.toInt() / 1000} km"
    } else if (currentVal == currentVal.toLong().toDouble()) {
        currentVal.toLong().toString()
    } else {
        "%.2f".format(currentVal)
    }
    val highVal = if (garminType == GarminType.DISTANCE_METERS) {
        "${high.value.toInt() / 1000} km"
    } else if (high.value == high.value.toLong().toDouble()) {
        high.value.toLong().toString()
    } else {
        "%.2f".format(high.value)
    }
    val date = high.date.ifEmpty { "—" }
    return "($cur) $highVal - $date"
}


@Composable
internal fun InfoRow(
    label: String,
    value: String,
    valueColor: Color,
    labelColor: Color = Color(0xFFADD8E6)
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = "$label: ",
            color = labelColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            color = valueColor,
            fontSize = 11.sp
        )
    }
}

// ── Voice Trigger info dialog ────────────────────────────────────────────────

/**
 * Info dialog explaining how to set up Samsung Routines for voice trigger.
 * Follows the same pattern as [DatedEntryInfoDialog].
 */


@Composable
internal fun VoiceTriggerInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF0A1A2A), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "🎤 Voice Trigger Setup",
                color = Color(0xFF44BBFF),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "When triggered, Tail listens for ~8 seconds through your microphone. " +
                       "If it hears one of your configured trigger words, it increments the " +
                       "matching habit — even with the screen off.",
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Samsung Routines Setup",
                color = Color(0xFF44BBFF),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            val steps = listOf(
                "1. Open Settings → Modes and Routines\n   (or search \"Routines\" in Settings)",
                "2. Tap \"+\" to create a new routine",
                "3. Set your trigger (\"If\"):\n   • \"Button\" → choose a button combo\n     (e.g. double-press Side key)\n   • Or any other trigger you prefer",
                "4. Set the action (\"Then\"):\n   • Tap \"Then\" → scroll to \"Apps\"\n   • Select \"tail\" from the app list\n   • Choose \"Voice Trigger\" as the action",
                "5. Save the routine and test it!"
            )
            for (step in steps) {
                Text(
                    text = step,
                    color = Color(0xFFAABBCC),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "How it appears in Routines",
                color = Color(0xFF44BBFF),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tail registers a \"Voice Trigger\" app shortcut.\n" +
                       "In Samsung Routines under \"Then\" → \"Apps\",\n" +
                       "you'll see Tail with the \"Voice Trigger\"\n" +
                       "action available to select.",
                color = Color(0xFF889999),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Tips",
                color = Color(0xFF44BBFF),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            val tips = listOf(
                "• Speak clearly within ~8 seconds",
                "• Trigger words are case-insensitive",
                "• Partial matches work: saying \"I did pushups\" matches the trigger word \"pushups\"",
                "• Multiple habits can share the same trigger word — all will be incremented",
                "• A confirmation vibration means it matched"
            )
            for (tip in tips) {
                Text(text = tip, color = Color(0xFFAABBCC), fontSize = 11.sp)
                Spacer(modifier = Modifier.height(2.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Permissions",
                color = Color(0xFF44BBFF),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Tail needs microphone permission to listen for trigger words. " +
                       "You'll be prompted when the service first runs. " +
                       "If denied, grant it in Settings → Apps → Tail → Permissions.",
                color = Color(0xFF889999),
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003355))
                ) {
                    Text("Got it", color = Color(0xFF44BBFF))
                }
            }
        }
    }
}


// ── Roll forward confirmation dialog ─────────────────────────────────────────────
@Composable
internal fun RollForwardConfirmDialog(
    habitName: String,
    actionType: String, // "increment" or "text"
    startDate: LocalDate,
    initialEndDate: LocalDate,
    onConfirm: (LocalDate) -> Unit,
    onDismiss: () -> Unit
) {
    var endDate by remember { mutableStateOf(initialEndDate) }
    var yearText by remember { mutableStateOf(endDate.year.toString()) }
    var monthText by remember { mutableStateOf(endDate.monthValue.toString()) }
    var dayText by remember { mutableStateOf(endDate.dayOfMonth.toString()) }
    
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Roll Forward",
                color = Color(0xFF44BBFF),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            val actionDescription = when (actionType) {
                "increment" -> "Increment \"$habitName\" and roll forward"
                "text" -> "Set text for \"$habitName\" and roll forward"
                else -> "Roll forward for \"$habitName\""
            }
            
            Text(
                text = "$actionDescription\nfrom ${startDate.format(DISPLAY_DATE_FMT)} to:",
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Simple editable date fields
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Year field
                OutlinedTextField(
                    value = yearText,
                    onValueChange = {
                        yearText = it.filter { char -> char.isDigit() }
                        if (yearText.isNotEmpty()) {
                            val newYear = yearText.toIntOrNull() ?: endDate.year
                            val newDate = try {
                                LocalDate.of(newYear, endDate.monthValue, endDate.dayOfMonth)
                            } catch (e: Exception) {
                                endDate
                            }
                            endDate = newDate
                        }
                    },
                    label = { Text("Year", color = Color(0xFF888888), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF44BBFF),
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                
                // Month field
                OutlinedTextField(
                    value = monthText,
                    onValueChange = {
                        monthText = it.filter { char -> char.isDigit() }.take(2)
                        if (monthText.isNotEmpty()) {
                            val newMonth = monthText.toIntOrNull()?.coerceIn(1, 12) ?: endDate.monthValue
                            val newDate = try {
                                LocalDate.of(endDate.year, newMonth, endDate.dayOfMonth)
                            } catch (e: Exception) {
                                endDate.withMonth(newMonth).withDayOfMonth(1)
                            }
                            endDate = newDate
                            monthText = newDate.monthValue.toString()
                        }
                    },
                    label = { Text("Month", color = Color(0xFF888888), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF44BBFF),
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                
                // Day field
                OutlinedTextField(
                    value = dayText,
                    onValueChange = {
                        dayText = it.filter { char -> char.isDigit() }.take(2)
                        if (dayText.isNotEmpty()) {
                            val newDay = dayText.toIntOrNull()?.coerceIn(1, 31) ?: endDate.dayOfMonth
                            val newDate = try {
                                LocalDate.of(endDate.year, endDate.monthValue, newDay)
                            } catch (e: Exception) {
                                endDate.withDayOfMonth(1)
                            }
                            endDate = newDate
                            dayText = newDate.dayOfMonth.toString()
                        }
                    },
                    label = { Text("Day", color = Color(0xFF888888), fontSize = 11.sp) },
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF44BBFF),
                        unfocusedBorderColor = Color(0xFF444444),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    textStyle = TextStyle(fontSize = 14.sp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Display the formatted date
            Text(
                text = endDate.format(DISPLAY_DATE_FMT),
                color = Color(0xFF44BBFF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { onConfirm(endDate) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF004466))
                ) {
                    Text("Roll Forward", color = Color(0xFF44BBFF))
                }
            }
        }
    }
}

// ── App Picker Dialog ──────────────────────────────────────────────────────────

/**
 * Converts an Android [Drawable] to a [Bitmap] for Compose rendering.
 */


internal fun drawableToBitmapForDialog(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/**
 * Data class representing a single installed app for the picker.
 */
private data class AppPickerItem(
    val packageName: String,
    val label: String
)

/**
 * A dialog that shows a searchable list of installed apps.
 * The user can browse and select one to create an app-link cell.
 */


@Composable
internal fun AppPickerDialog(
    context: Context,
    onConfirm: (packageName: String, label: String) -> Unit,
    onDismiss: () -> Unit,
    /**
     * When set, each app row can be expanded to reveal the app's published
     * shortcuts, letting the caller associate a specific shortcut instead of
     * the whole app. Null (default) keeps the dialog app-only.
     */
    onConfirmShortcut: ((packageName: String, shortcutId: String, label: String) -> Unit)? = null
) {
    var searchQuery by remember { mutableStateOf("") }
    // Package whose shortcuts are currently expanded inline (null = none)
    var expandedPackage by remember { mutableStateOf<String?>(null) }

    // Load installed apps once
    val allApps by remember {
        mutableStateOf(
            try {
                val pm = context.packageManager
                val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                packages
                    .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                    .map { AppPickerItem(it.packageName, pm.getApplicationLabel(it).toString()) }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                emptyList()
            }
        )
    }

    val filteredApps = remember(searchQuery, allApps) {
        if (searchQuery.isBlank()) allApps
        else allApps.filter {
            it.label.contains(searchQuery, ignoreCase = true) ||
            it.packageName.contains(searchQuery, ignoreCase = true)
        }
    }

    // Everything the expanded app can be bound to: its published shortcuts
    // (needs default-launcher status) plus its exported activities (always
    // available — the same mechanism Tasker uses).
    val expandedResult = remember(expandedPackage) {
        expandedPackage?.let { queryAppShortcuts(context, it) }
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Select App",
                color = Color(0xFF66CCFF),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search", color = Color(0xFF888888)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF66CCFF),
                    unfocusedBorderColor = Color(0xFF555555)
                )
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Scrollable app list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                lazyItems(filteredApps) { app ->
                    val pm = context.packageManager
                    val isExpanded = expandedPackage == app.packageName
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onConfirm(app.packageName, app.label) }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // App icon
                            val iconBitmap = remember(app.packageName) {
                                try {
                                    drawableToBitmapForDialog(pm.getApplicationIcon(app.packageName))
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            if (iconBitmap != null) {
                                Image(
                                    bitmap = iconBitmap.asImageBitmap(),
                                    contentDescription = app.label,
                                    modifier = Modifier.size(32.dp)
                                )
                            } else {
                                Box(modifier = Modifier.size(32.dp))
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = app.label,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = app.packageName,
                                    color = Color(0xFF888888),
                                    fontSize = 10.sp
                                )
                            }
                            // Expand toggle — reveals the app's shortcuts so a
                            // specific one can be associated instead of the app.
                            if (onConfirmShortcut != null) {
                                TextButton(
                                    onClick = {
                                        expandedPackage = if (isExpanded) null else app.packageName
                                    },
                                    modifier = Modifier.height(28.dp),
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                                ) {
                                    Text(
                                        if (isExpanded) "▾" else "▸",
                                        fontSize = 13.sp,
                                        color = Color(0xFF66CCFF)
                                    )
                                }
                            }
                        }
                        // Inline shortcut/activity rows for the expanded app
                        if (isExpanded && onConfirmShortcut != null && expandedResult != null) {
                            if (!expandedResult.shortcutsAccessible) {
                                Text(
                                    "Published shortcuts need default-launcher status — activities below always work",
                                    color = Color(0xFF888888),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(start = 48.dp, bottom = 2.dp)
                                )
                            }
                            if (expandedResult.isEmpty) {
                                Text(
                                    "No shortcuts or activities",
                                    color = Color(0xFF888888),
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(start = 48.dp, bottom = 6.dp)
                                )
                            } else {
                                expandedResult.shortcuts.forEach { shortcut ->
                                    ShortcutPickerRow(
                                        badge = "⚡",
                                        label = shortcut.label,
                                        sublabel = buildString {
                                            append(shortcut.shortcutId)
                                            when {
                                                shortcut.isManifest -> append(" · static")
                                                shortcut.isDynamic -> append(" · dynamic")
                                                shortcut.isPinned -> append(" · pinned")
                                            }
                                        },
                                        onClick = {
                                            onConfirmShortcut(app.packageName, shortcut.shortcutId, shortcut.label)
                                        }
                                    )
                                }
                                if (expandedResult.activities.isNotEmpty()) {
                                    Text(
                                        "Activities",
                                        color = Color(0xFF666666),
                                        fontSize = 9.sp,
                                        modifier = Modifier.padding(start = 48.dp, top = 6.dp)
                                    )
                                    expandedResult.activities.forEach { act ->
                                        ShortcutPickerRow(
                                            badge = "▶",
                                            label = act.label,
                                            sublabel = act.shortcutId
                                                .removePrefix(ACTIVITY_ID_PREFIX)
                                                .substringAfterLast('.'),
                                            onClick = {
                                                onConfirmShortcut(app.packageName, act.shortcutId, act.label)
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
            }
        }
    }
}

/**
 * One indented entry under an expanded app row in [AppPickerDialog]:
 * a published shortcut (⚡) or an exported activity (▶).
 */


@Composable
internal fun ShortcutPickerRow(
    badge: String,
    label: String,
    sublabel: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 48.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(badge, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                color = Color(0xFF66CCFF),
                fontSize = 12.sp
            )
            Text(
                text = sublabel,
                color = Color(0xFF666666),
                fontSize = 9.sp
            )
        }
    }
}
