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
internal fun WidgetTriggerSection(
    habitName: String,
    widgetTriggerHabits: Set<String>,
    widgetTriggerApps: Map<String, String>,
    onToggleWidgetTrigger: (String) -> Unit,
    onSetWidgetTriggerApp: (String) -> Unit,
    hasUsageAccess: Boolean,
    onRequestUsageAccess: () -> Unit
) {
    val context = LocalContext.current
    val isEnabled = habitName in widgetTriggerHabits
    val triggerPkg = widgetTriggerApps[habitName]

    // Resolve the trigger app's display label
    val triggerLabel = remember(triggerPkg) {
        triggerPkg?.let { pkg ->
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Main toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "🫧 Use Widget", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Text(
                    text = if (isEnabled) {
                        if (triggerLabel != null) "Bubble appears over $triggerLabel"
                        else "Select a trigger app below"
                    } else {
                        "Show bubble when an app opens"
                    },
                    color = if (isEnabled) Color(0xFF66BB6A) else Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggleWidgetTrigger(habitName) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF44BBFF),
                    checkedTrackColor = Color(0xFF003355),
                    uncheckedThumbColor = Color(0xFF888888),
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }

        // Usage-access permission warning + grant button
        if (isEnabled && !hasUsageAccess) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠️ Usage access needed",
                    color = Color(0xFFFFAA33),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRequestUsageAccess,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A00)),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Grant", fontSize = 10.sp, color = Color(0xFFFFCC66))
                }
            }
        }

        // Trigger app selection row (only when enabled)
        if (isEnabled) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Trigger App", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                    Text(
                        text = triggerLabel ?: "Not set",
                        color = if (triggerLabel != null) Color(0xFF66CCFF) else Color(0xFF888888),
                        fontSize = 10.sp
                    )
                }
                Button(
                    onClick = { onSetWidgetTriggerApp(habitName) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("📱", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (triggerPkg == null) "Select App" else "Change",
                        fontSize = 11.sp,
                        color = Color(0xFF66CCFF)
                    )
                }
            }

            // Primary value selection lives in the universal PrimaryValueSection
            // in the main edit panel (single source of truth for every habit).
        }
    }
}


@Composable
internal fun MediaSection(
    habitName: String,
    mediaHabits: Set<String>,
    mediaApps: Map<String, String>,
    onToggleMedia: (String) -> Unit,
    onSetMediaApp: (String) -> Unit,
    hasNotificationAccess: Boolean,
    onRequestNotificationAccess: () -> Unit,
    todayShows: List<MediaShowMinutes> = emptyList(),
    onLoadShows: (String) -> Unit = {},
    onRemoveShow: (String, String) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val isEnabled = habitName in mediaHabits
    val mediaPkg = mediaApps[habitName]

    // Load today's per-show breakdown whenever this section is shown.
    LaunchedEffect(habitName) { onLoadShows(habitName) }

    // Resolve the media app's display label
    val appLabel = remember(mediaPkg) {
        mediaPkg?.let { pkg ->
            try {
                val pm = context.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) { pkg }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Main toggle row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "🎧 Media", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Text(
                    text = if (isEnabled) {
                        if (appLabel != null) "Auto-records listening time in $appLabel"
                        else "Select a media app below"
                    } else {
                        "Auto-track podcast & music listening time"
                    },
                    color = if (isEnabled) Color(0xFF66BB6A) else Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggleMedia(habitName) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF44BBFF),
                    checkedTrackColor = Color(0xFF003355),
                    uncheckedThumbColor = Color(0xFF888888),
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }

        // Notification-access warning + grant button (needed to see media
        // sessions of other apps; same toggle as Spotify detection)
        if (isEnabled && !hasNotificationAccess) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "⚠️ Notification access needed",
                    color = Color(0xFFFFAA33),
                    fontSize = 10.sp,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onRequestNotificationAccess,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A00)),
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Grant", fontSize = 10.sp, color = Color(0xFFFFCC66))
                }
            }
        }

        // Media app selection row (only when enabled)
        if (isEnabled) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Media App", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                    Text(
                        text = appLabel ?: "Not set",
                        color = if (appLabel != null) Color(0xFF66CCFF) else Color(0xFF888888),
                        fontSize = 10.sp
                    )
                }
                Button(
                    onClick = { onSetMediaApp(habitName) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("🎧", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (mediaPkg == null) "Select App" else "Change",
                        fontSize = 11.sp,
                        color = Color(0xFF66CCFF)
                    )
                }
            }

            // How the values work, once an app is chosen
            if (mediaPkg != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Minutes listened while the app plays are added automatically " +
                        "(primary value for points). Your tapped count stays as the points " +
                        "fallback on days with no listening. The bubble timer still works " +
                        "over the app as a manual fallback. Every song/episode played is " +
                        "logged with its time to the text log when text entry is set up.",
                    color = Color(0xFF999999),
                    fontSize = 9.sp,
                    lineHeight = 12.sp
                )
            }

            // ── Today's shows/podcasts with per-show removal ──────────
            // Hitting ✕ next to a show deletes today's log entries for
            // that show and subtracts its logged minutes from the habit's
            // day total (see HabitViewModel.removeMediaShowFromToday).
            if (todayShows.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Today's shows — ${todayShows.sumOf { it.minutes }} min total " +
                        "(✕ removes a show's minutes)",
                    color = Color(0xFFAAAAAA),
                    fontSize = 11.sp
                )
                todayShows.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.show,
                                color = Color(0xFFCCCCCC),
                                fontSize = 11.sp,
                                maxLines = 1
                            )
                            Text(
                                text = if (entry.plays == 1) "${entry.minutes} min"
                                       else "${entry.plays} plays · ${entry.minutes} min",
                                color = Color(0xFF888888),
                                fontSize = 10.sp
                            )
                        }
                        TextButton(
                            onClick = { onRemoveShow(habitName, entry.show) },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFFF6666)),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Text("✕", fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}


@Composable
internal fun AssociatedAppRow(
    habitName: String,
    packageName: String,
    index: Int,
    totalCount: Int,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager

    // The entry may be a plain package name or an encoded shortcut reference
    val shortcut = remember(packageName) { parseShortcutEntry(packageName) }
    val appPkg = shortcut?.first ?: packageName

    // Load app label and icon
    val appLabel = remember(packageName) {
        try { pm.getApplicationLabel(pm.getApplicationInfo(appPkg, 0)).toString() }
        catch (e: Exception) { appPkg }
    }
    // Resolve the shortcut's display name (null when inaccessible or gone)
    val shortcutLabel = remember(packageName) {
        shortcut?.let { (p, id) ->
            findShortcutInfo(context, p, id)?.label
                // Fallback: class simple name for activities, raw id for shortcuts
                ?: if (id.startsWith(ACTIVITY_ID_PREFIX)) id.substringAfterLast('.') else id
        }
    }
    val iconBitmap = remember(packageName) {
        try { drawableToBitmapForDialog(pm.getApplicationIcon(appPkg)) }
        catch (e: Exception) { null }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // App icon
        if (iconBitmap != null) {
            Image(
                bitmap = iconBitmap.asImageBitmap(),
                contentDescription = appLabel,
                modifier = Modifier.size(20.dp)
            )
        } else {
            Box(modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(6.dp))
        // App / shortcut label (truncated)
        Text(
            text = (
                if (shortcut != null) "⚡ ${shortcutLabel ?: shortcut.second}"
                else appLabel
            ).take(20),
            color = Color(0xFF88CCFF),
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
        // Up button
        Button(
            onClick = onMoveUp,
            enabled = index > 0,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A2A3A),
                disabledContainerColor = Color(0xFF1A1A1A)
            ),
            modifier = Modifier.size(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Text("▲", fontSize = 8.sp, color = if (index > 0) Color(0xFF66CCFF) else Color(0xFF555555))
        }
        Spacer(modifier = Modifier.width(2.dp))
        // Down button
        Button(
            onClick = onMoveDown,
            enabled = index < totalCount - 1,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1A2A3A),
                disabledContainerColor = Color(0xFF1A1A1A)
            ),
            modifier = Modifier.size(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Text("▼", fontSize = 8.sp, color = if (index < totalCount - 1) Color(0xFF66CCFF) else Color(0xFF555555))
        }
        Spacer(modifier = Modifier.width(2.dp))
        // Remove button
        Button(
            onClick = onRemove,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A00)),
            modifier = Modifier.size(24.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
        ) {
            Text("✕", fontSize = 9.sp, color = Color(0xFFFF6644))
        }
    }
}

/**
 * Dialog shown when long-pressing a habit that has multiple associated apps.
 * Lists the apps in their defined order; tapping one launches it.
 */


@Composable
internal fun AssociatedAppLauncherDialog(
    habitName: String,
    /** Association entries — plain package names and/or encoded shortcut entries. */
    entries: List<String>,
    onLaunch: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val pm = context.packageManager

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = habitName,
                color = Color(0xFFFFAA00),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select an app to open",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 400.dp)
            ) {
                lazyItems(entries) { entry ->
                    val shortcut = remember(entry) { parseShortcutEntry(entry) }
                    val pkg = shortcut?.first ?: entry
                    val label = remember(entry) {
                        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        catch (e: Exception) { pkg }
                    }
                    val shortcutLabel = remember(entry) {
                        shortcut?.let { (p, id) ->
                            findShortcutInfo(context, p, id)?.label
                                ?: if (id.startsWith(ACTIVITY_ID_PREFIX)) id.substringAfterLast('.') else id
                        }
                    }
                    val iconBitmap = remember(entry) {
                        try { drawableToBitmapForDialog(pm.getApplicationIcon(pkg)) }
                        catch (e: Exception) { null }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onLaunch(pkg) }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (iconBitmap != null) {
                            Image(
                                bitmap = iconBitmap.asImageBitmap(),
                                contentDescription = label,
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
                            Box(modifier = Modifier.size(32.dp))
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = if (shortcut != null) "⚡ ${shortcutLabel ?: shortcut.second}" else label,
                                color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (shortcut != null)
                                           "$pkg · ${if (isActivityEntry(entry)) "activity" else "shortcut"}"
                                       else pkg,
                                color = Color(0xFF888888), fontSize = 10.sp
                            )
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

// ── Edit mode control bar ─────────────────────────────────────────────────────

/**
 * Control bar shown below the grid in edit mode.
 *
 * Three states:
 *  1. Nothing selected → prompt + Add Screen / Del Screen buttons
 *  2. Placeholder selected → "Add Habit" button
 *  3. Habit selected → MOVE button + screen-jump buttons + SETTINGS section
 *
 * When [movePending] is true (Move button was tapped), the bar shows a cancel prompt
 * and all grid cells become move targets.
 */


@Composable
internal fun MealToggleSection(
    habitName: String,
    isMeal: Boolean,
    onToggleMeal: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "🍽️ Meal Habit", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isMeal) "Tap increments + vision logging" else "Normal counter",
                color = Color(0xFF888888), fontSize = 10.sp
            )
        }
        Switch(
            checked = isMeal,
            onCheckedChange = { onToggleMeal(habitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFF9800),
                checkedTrackColor = Color(0xFF3E2723),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

/**
 * Toggle for the "Weights" habit type: when enabled, tapping the habit opens
 * the weights input dialog (kg/lb unit + weight + reps + machine/free), and
 * the graph shows weight & reps curves filterable by machine/free.
 */


@Composable
internal fun WeightsToggleSection(
    habitName: String,
    isWeights: Boolean,
    onToggleWeights: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "🏋️ Weights Habit", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isWeights) "Tap logs weight × reps (machine/free)" else "Normal counter",
                color = Color(0xFF888888), fontSize = 10.sp
            )
        }
        Switch(
            checked = isWeights,
            onCheckedChange = { onToggleWeights(habitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF8BC34A),
                checkedTrackColor = Color(0xFF1B3A1B),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

/**
 * "Meal Detail" button for meal habits — opens the meal detail editor
 * (vision logging setup). Rendered at the top of the SETTINGS section in
 * [EditModeControlBar] so it is immediately visible instead of buried in
 * the special-habit-types drawer under the meal toggle.
 */


@Composable
internal fun MealDetailButton(
    habitName: String,
    onOpenMealDetails: (String) -> Unit
) {
    Button(
        onClick = { onOpenMealDetails(habitName) },
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3E2723)),
        modifier = Modifier.fillMaxWidth().height(36.dp)
    ) {
        Icon(
            Icons.Default.PhotoCamera,
            contentDescription = null,
            tint = Color(0xFFFF9800),
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            "Meal Detail",
            fontSize = 12.sp,
            color = Color(0xFFFF9800)
        )
    }
}

/**
 * "Camera" eligibility toggle: only habits with this enabled are offered to
 * the LLM as choices when a photo is captured (quick capture / media capture
 * auto-detection). Keeping the eligible set small makes the LLM's choice
 * easy and reliable.
 */


@Composable
internal fun CameraToggleSection(
    habitName: String,
    isCamera: Boolean,
    onToggleCamera: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "📷 Camera", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isCamera) "LLM may pick this from photos" else "Never chosen from photos",
                color = Color(0xFF888888), fontSize = 10.sp
            )
        }
        Switch(
            checked = isCamera,
            onCheckedChange = { onToggleCamera(habitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66CCFF),
                checkedTrackColor = Color(0xFF0A2A3A),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

/**
 * Settings section for configuring the long-press action of a habit.
 *
 * For meal habits the user can choose between App (default), URL, Camera, and Details.
 * For non-meal habits App and URL are available. When URL is selected, a text
 * field below lets the user enter the link to open on long-press.
 */


@Composable
internal fun LongPressActionSection(
    habitName: String,
    isMeal: Boolean,
    currentAction: String,
    onSetAction: (String, String) -> Unit,
    /** URL currently configured for the LONG_PRESS_URL action ("" = none). */
    currentUrl: String = "",
    /** Called when the user edits the long-press URL (habitName, url). */
    onSetUrl: (String, String) -> Unit = { _, _ -> },
    /** Package name of the app chosen to handle the long-press URL (null = browser). */
    currentUrlApp: String? = null,
    /** Called when the user taps the button to pick the app for the long-press URL. */
    onPickUrlApp: (String) -> Unit = {},
    /** Called when the user clears the chosen app (back to default browser). */
    onClearUrlApp: (String) -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }

    val options = if (isMeal) com.example.tail.data.MEAL_LONG_PRESS_ACTIONS
                  else com.example.tail.data.STANDARD_LONG_PRESS_ACTIONS

    val actionLabel = when (currentAction) {
        com.example.tail.data.LONG_PRESS_CAMERA -> "Camera"
        com.example.tail.data.LONG_PRESS_DETAILS -> "Meal Details"
        com.example.tail.data.LONG_PRESS_URL -> "URL"
        else -> "App"
    }

    val actionDesc = when (currentAction) {
        com.example.tail.data.LONG_PRESS_CAMERA -> "Opens camera capture"
        com.example.tail.data.LONG_PRESS_DETAILS -> "Opens meal details for this day"
        com.example.tail.data.LONG_PRESS_URL ->
            if (currentUrl.isNotBlank()) "Opens ${currentUrl.take(40)}"
            else "Opens a link — set the URL below"
        else -> "Launches associated app"
    }

    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Long-press action", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = actionDesc,
                color = Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Box {
            TextButton(onClick = { expanded = true }) {
                Text(actionLabel, fontSize = 12.sp, color = Color(0xFFFF9800))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                options.forEach { option ->
                    val label = when (option) {
                        com.example.tail.data.LONG_PRESS_CAMERA -> "Camera"
                        com.example.tail.data.LONG_PRESS_DETAILS -> "Meal Details"
                        com.example.tail.data.LONG_PRESS_URL -> "URL"
                        else -> "App"
                    }
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            onSetAction(habitName, option)
                            expanded = false
                        }
                    )
                }
            }
        }
    }

    // URL editor — shown only when the long-press action is URL.
    // Saves on every change (same pattern as the voice-trigger words field).
    if (currentAction == com.example.tail.data.LONG_PRESS_URL) {
        Spacer(modifier = Modifier.height(4.dp))
        var urlText by remember(habitName) { mutableStateOf(currentUrl) }
        OutlinedTextField(
            value = urlText,
            onValueChange = { newText ->
                urlText = newText
                onSetUrl(habitName, newText)
            },
            placeholder = { Text("https://… or obsidian://open?vault=…&file=…", fontSize = 11.sp, color = Color(0xFF666666)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFF66CCFF),
                unfocusedTextColor = Color(0xFF66CCFF),
                focusedBorderColor = Color(0xFF66CCFF),
                unfocusedBorderColor = Color(0xFF225577)
            ),
            textStyle = TextStyle(fontSize = 12.sp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )

        // Scheme feedback — deep links (obsidian://, spotify://, tel: …)
        // resolve into their target app; scheme-less input opens as https.
        val hasScheme = com.example.tail.data.hasUriScheme(urlText)
        Text(
            text = when {
                urlText.isBlank() -> "Paste any URI — https:// or a deep link like obsidian://…"
                hasScheme -> "Opens via ${com.example.tail.data.uriSchemeOf(urlText)}: link"
                else -> "No scheme — will open as https://"
            },
            color = if (urlText.isNotBlank() && !hasScheme) Color(0xFFBB8844) else Color(0xFF888888),
            fontSize = 10.sp
        )

        // Obsidian note builder — compose an obsidian://open deep link from
        // vault + file path so vault/file names with spaces never need
        // hand-encoding. Writes the built URI into the field above.
        var showObsidianBuilder by remember { mutableStateOf(false) }
        TextButton(
            onClick = { showObsidianBuilder = !showObsidianBuilder },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
        ) {
            Text(
                if (showObsidianBuilder) "▾ Hide Obsidian builder" else "▸ Build Obsidian note URI",
                fontSize = 11.sp,
                color = Color(0xFF66CCFF)
            )
        }
        if (showObsidianBuilder) {
            var vaultText by remember { mutableStateOf("") }
            var fileText by remember { mutableStateOf("") }
            OutlinedTextField(
                value = vaultText,
                onValueChange = { vaultText = it },
                placeholder = { Text("Vault name", fontSize = 11.sp, color = Color(0xFF666666)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF66CCFF),
                    unfocusedTextColor = Color(0xFF66CCFF),
                    focusedBorderColor = Color(0xFF66CCFF),
                    unfocusedBorderColor = Color(0xFF225577)
                ),
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = fileText,
                onValueChange = { fileText = it },
                placeholder = { Text("notes/my note.md (optional)", fontSize = 11.sp, color = Color(0xFF666666)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFF66CCFF),
                    unfocusedTextColor = Color(0xFF66CCFF),
                    focusedBorderColor = Color(0xFF66CCFF),
                    unfocusedBorderColor = Color(0xFF225577)
                ),
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = {
                    if (vaultText.isNotBlank()) {
                        val built = com.example.tail.data.buildObsidianOpenUri(vaultText, fileText)
                        urlText = built
                        onSetUrl(habitName, built)
                    }
                },
                enabled = vaultText.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Set URI", fontSize = 11.sp, color = Color(0xFF66CCFF))
            }
        }

        // "Open in" selector — route the URL into a specific app (e.g. a
        // Gemini conversation link into the Gemini app) via Intent.setPackage.
        Spacer(modifier = Modifier.height(4.dp))
        val appContext = LocalContext.current
        val urlAppLabel = remember(currentUrlApp) {
            currentUrlApp?.let { pkg ->
                try {
                    val pm = appContext.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) { pkg }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Open in app", color = Color(0xFFAAAAAA), fontSize = 11.sp)
                Text(
                    text = urlAppLabel ?: "Default browser",
                    color = if (urlAppLabel != null) Color(0xFF66CCFF) else Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (currentUrlApp != null) {
                    Button(
                        onClick = { onClearUrlApp(habitName) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A00)),
                        modifier = Modifier.height(32.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("✕", fontSize = 11.sp, color = Color(0xFFFF6644))
                    }
                }
                Button(
                    onClick = { onPickUrlApp(habitName) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("📱", fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (currentUrlApp == null) "Select App" else "Change",
                        fontSize = 11.sp,
                        color = Color(0xFF66CCFF)
                    )
                }
            }
        }
    }
}


@Composable
internal fun HabitToggleSection(
    habitName: String,
    isDisabled: Boolean,
    onToggleDisabled: (String) -> Unit,
    isNoPoints: Boolean,
    onToggleNoPoints: (String) -> Unit,
    isSecondaryValue: Boolean,
    onToggleSecondaryValue: (String) -> Unit,
    isSecondaryValueFallback: Boolean = false,
    onToggleSecondaryValueFallback: (String) -> Unit = {},
    /** Whether the habit appears on the day timeline (clock view). */
    showOnTimeline: Boolean = true,
    onToggleTimeline: (String) -> Unit = {}
) {
    // ── Disabled toggle ─────────────────────────────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "Disabled", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isDisabled) "Red ✕ shown, excluded from stats"
                       else "Habit is active",
                color = if (isDisabled) Color(0xFFFF6666) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isDisabled,
            onCheckedChange = { onToggleDisabled(habitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFF4444),
                checkedTrackColor = Color(0xFF4A0000),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    // ── Don't affect points toggle ──────────────────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "Don't affect points", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isNoPoints) "Excluded from totals"
                       else "Counts toward point totals",
                color = if (isNoPoints) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isNoPoints,
            onCheckedChange = { onToggleNoPoints(habitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66BB6A),
                checkedTrackColor = Color(0xFF2E7D32),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    // (Removed Aug-22-2026) The generic "Secondary value" and "Fallback to
    // secondary" toggles lived here. The secondary-value track is now reserved
    // for the special multi-value integrations (chess.com, JugCoach, movie
    // bridge IMDb ratings), which manage their slots automatically; every
    // other two-value habit uses the built-in Minutes/Sessions feature.

    Spacer(modifier = Modifier.height(6.dp))

    // ── Day timeline toggle ────────────────────────────────────────────
    // Controls whether the habit's timestamped entries appear on the
    // day timeline (the retrospective hour-by-hour clock view).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "Show on day timeline", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (showOnTimeline) "Timestamped entries appear on the clock view"
                       else "Hidden from the clock view",
                color = if (showOnTimeline) Color(0xFF66CCFF) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = showOnTimeline,
            onCheckedChange = { onToggleTimeline(habitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66CCFF),
                checkedTrackColor = Color(0xFF0A2A3A),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

/**
 * Universal per-habit "Primary value" selector for the first-class minutes
 * model. Every habit implicitly has a minutes slot (`minutes:<habit>`);
 * this section picks which value drives points and spells out the fallback
 * semantics. With minutes primary the fallback direction flips: pills pick
 * which OTHER value (sessions, the second value, or none) covers points on
 * 0-minute days. With sessions primary the "Fallback to minutes" switch
 * applies; secondary-value habits keep their dedicated toggle above.
 */


@Composable
internal fun PrimaryValueSection(
    habitName: String,
    isSecondaryValue: Boolean,
    minutesPrimary: Boolean,
    onSetPrimaryValue: (String, Boolean) -> Unit,
    minutesFallback: Boolean,
    onToggleMinutesFallback: (String) -> Unit,
    minutesPrimaryFallback: String = com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS,
    onSetMinutesPrimaryFallback: (String, String) -> Unit = { _, _ -> },
    secondValueLabel: String? = null
) {
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "⭐ Primary value",
        color = Color(0xFF88CCFF),
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PrimaryValuePill(
            text = "Sessions",
            selected = !minutesPrimary,
            onClick = { onSetPrimaryValue(habitName, false) }
        )
        PrimaryValuePill(
            text = "Minutes",
            selected = minutesPrimary,
            onClick = { onSetPrimaryValue(habitName, true) }
        )
    }
    Spacer(modifier = Modifier.height(2.dp))
    Text(
        text = when {
            isSecondaryValue && minutesPrimary ->
                "Minutes drive points; second value tracked alongside"
            isSecondaryValue ->
                "Sessions drive points; second value tracked alongside"
            minutesPrimary -> when {
                minutesPrimaryFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE ->
                    "Minutes drive points; no fallback"
                minutesPrimaryFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_VALUE2 ->
                    "Minutes drive points; ${secondValueLabel ?: "second value"} is the fallback on 0-minute days"
                else -> "Minutes drive points; sessions are the fallback on 0-minute days"
            }
            minutesFallback -> "Sessions drive points; minutes are the fallback on 0-session days"
            else -> "Sessions drive points"
        },
        color = Color(0xFF999999),
        fontSize = 10.sp
    )

    if (minutesPrimary) {
        // Minutes-primary habits pick which OTHER value covers points on
        // 0-minute days — the fallback direction is reversed.
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Fallback on 0-minute days",
            color = Color(0xFFCCCCCC),
            fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PrimaryValuePill(
                text = "None",
                selected = minutesPrimaryFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE,
                onClick = {
                    onSetMinutesPrimaryFallback(habitName, com.example.tail.data.MINUTES_PRIMARY_FALLBACK_NONE)
                }
            )
            PrimaryValuePill(
                text = "Sessions",
                selected = minutesPrimaryFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS,
                onClick = {
                    onSetMinutesPrimaryFallback(habitName, com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS)
                }
            )
            if (isSecondaryValue) {
                PrimaryValuePill(
                    text = secondValueLabel ?: "Second value",
                    selected = minutesPrimaryFallback == com.example.tail.data.MINUTES_PRIMARY_FALLBACK_VALUE2,
                    onClick = {
                        onSetMinutesPrimaryFallback(habitName, com.example.tail.data.MINUTES_PRIMARY_FALLBACK_VALUE2)
                    }
                )
            }
        }
    } else if (!isSecondaryValue) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Fallback to minutes", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Text(
                    text = if (minutesFallback) "Minutes used for points on 0-session days"
                           else "No fallback",
                    color = if (minutesFallback) Color(0xFF66BB6A) else Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = minutesFallback,
                onCheckedChange = { onToggleMinutesFallback(habitName) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF66BB6A),
                    checkedTrackColor = Color(0xFF2E7D32),
                    uncheckedThumbColor = Color(0xFF888888),
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }
    }
}

/**
 * Per-habit toggle for the first-class minutes value (`minutes:<habit>`).
 *
 * OFF: the habit has no minutes at all — no minutes input in the edit bar,
 * no Minutes graph metric, no minutes fallback/primary options.
 * ON: the minutes value exists and can be edited, graphed and made primary.
 *
 * Locked ON for habits connected to a timer widget (PC widget, phone
 * bubble, media tracker) — their timer feeds the minutes slot. Hidden for
 * max-1 habits (a binary habit never has minutes).
 */


@Composable
internal fun MinutesToggleSection(
    habitName: String,
    minutesEnabled: Boolean,
    forcedByWidget: Boolean,
    onToggleMinutesEnabled: (String) -> Unit
) {
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "⏱ Minutes value", color = Color(0xFFAA88FF), fontSize = 11.sp)
            Text(
                text = when {
                    forcedByWidget -> "Always on — widget timer / media / movies feed minutes"
                    minutesEnabled -> "Minutes tracked alongside the count"
                    else -> "No minutes value for this habit"
                },
                color = if (minutesEnabled) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = minutesEnabled,
            onCheckedChange = { if (!forcedByWidget) onToggleMinutesEnabled(habitName) },
            enabled = !forcedByWidget,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66BB6A),
                checkedTrackColor = Color(0xFF2E7D32),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }
}

/**
 * A single row for editing a display-only value/subtype label.
 * Shows the default label as a hint and lets the user type a custom override.
 * When the field is cleared, the override is removed and the default is used again.
 */


@Composable
internal fun ValueLabelRow(
    habitName: String,
    valueKey: String,
    defaultLabel: String,
    currentLabel: String,
    onSetLabel: (String, String, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = defaultLabel,
            color = Color(0xFFAAAAAA),
            fontSize = 11.sp,
            modifier = Modifier.width(70.dp)
        )
        OutlinedTextField(
            value = currentLabel,
            onValueChange = { newLabel ->
                onSetLabel(habitName, valueKey, newLabel)
            },
            placeholder = { Text(defaultLabel, fontSize = 11.sp, color = Color(0xFF666666)) },
            singleLine = true,
            modifier = Modifier.weight(1f).height(44.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
        )
    }
}

/**
 * Section for editing display-only labels for a habit's value columns.
 * Shows when the habit has secondary values or subtypes.
 * Extracted into its own composable to keep [EditModeControlBar] under the
 * JVM method size limit.
 */


@Composable
internal fun ValueLabelsSection(
    habitName: String,
    hasSecondaryValue: Boolean,
    subtypes: List<String>,
    labels: Map<String, String>,
    onSetLabel: (String, String, String) -> Unit
) {
    if (!hasSecondaryValue && subtypes.isEmpty()) return

    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = "🏷️ Display Labels (UI only — backend unchanged)",
        color = Color(0xFF88CCFF),
        fontSize = 11.sp
    )
    Spacer(modifier = Modifier.height(4.dp))

    if (hasSecondaryValue) {
        ValueLabelRow(
            habitName = habitName,
            valueKey = com.example.tail.data.GRAPH_METRIC_VALUE1,
            defaultLabel = com.example.tail.data.defaultLabelForValueKey(
                com.example.tail.data.GRAPH_METRIC_VALUE1
            ),
            currentLabel = labels[com.example.tail.data.GRAPH_METRIC_VALUE1] ?: "",
            onSetLabel = onSetLabel
        )
        Spacer(modifier = Modifier.height(4.dp))
        ValueLabelRow(
            habitName = habitName,
            valueKey = com.example.tail.data.GRAPH_METRIC_VALUE2,
            defaultLabel = com.example.tail.data.defaultLabelForValueKey(
                com.example.tail.data.GRAPH_METRIC_VALUE2
            ),
            currentLabel = labels[com.example.tail.data.GRAPH_METRIC_VALUE2] ?: "",
            onSetLabel = onSetLabel
        )

        // Primary-value selection for the minutes slot moved to the universal
        // PrimaryValueSection in the main edit panel.
    }

    subtypes.forEach { subtype ->
        Spacer(modifier = Modifier.height(4.dp))
        ValueLabelRow(
            habitName = habitName,
            valueKey = subtype,
            defaultLabel = subtype,
            currentLabel = labels[subtype] ?: "",
            onSetLabel = onSetLabel
        )
    }
}

/**
 * Selectable pill for choosing which value type is primary.
 * The selected pill is highlighted; tapping makes that value primary.
 */


@Composable
internal fun PrimaryValuePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(14.dp)
    val bg = if (selected) Color(0xFF2E5A88) else Color(0xFF1E1E1E)
    val borderColor = if (selected) Color(0xFF88CCFF) else Color(0xFF444444)
    Box(
        modifier = Modifier
            .background(bg, shape)
            .border(1.dp, borderColor, shape)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = if (selected) "★ $text" else text,
            fontSize = 11.sp,
            color = if (selected) Color.White else Color(0xFFBBBBBB)
        )
    }
}

/**
 * "Sharable" sub-toggle for text-input habits, shown in edit mode under
 * "Text input". When enabled, the habit appears in ShareTextActivity's picker
 * so text shared from anywhere on the phone can be saved into it
 * (timestamped entry + count increment).
 * Extracted from EditModeControlBar to keep it under the JVM method-size limit.
 */


@Composable
internal fun SharableTextToggle(
    isSharable: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "  Sharable", color = Color(0xFFAAAAAA), fontSize = 12.sp)
            Text(
                text = if (isSharable) "Accepts shared text from any app" else "Not in share sheet",
                color = Color(0xFF666666), fontSize = 10.sp
            )
        }
        Switch(
            checked = isSharable,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFFAA88),
                checkedTrackColor = Color(0xFF4A2A1A),
                uncheckedThumbColor = Color(0xFF666666),
                uncheckedTrackColor = Color(0xFF2A2A2A)
            )
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// HabitScheduleSection — daily "ask me about this habit" time setting
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Edit-mode row configuring the daily scheduled ask for a habit. When a
 * time is set, the habit fires a redundant ask every day at that time
 * (system notification + in-app notification + one-time flash on the next
 * app open). Tapping the row opens an inline wheel picker; "✕" clears it.
 */


@Composable
internal fun HabitScheduleSection(
    habitName: String,
    scheduleTimes: Map<String, String>,
    onSetScheduleTime: (String, String?) -> Unit
) {
    val currentTime = scheduleTimes[habitName]
    var showPicker by remember(habitName) { mutableStateOf(false) }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { showPicker = !showPicker }
                .padding(vertical = 4.dp)
        ) {
            Text("🔔", fontSize = 13.sp)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (currentTime != null) "Ask daily at $currentTime" else "Ask daily…",
                fontSize = 11.sp,
                color = if (currentTime != null) Color(0xFF66CCFF) else Color(0xFF999999)
            )
            if (currentTime != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "✕",
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    modifier = Modifier
                        .clickable { onSetScheduleTime(habitName, null) }
                        .padding(horizontal = 4.dp)
                )
            }
        }
        if (showPicker) {
            var hour24 by remember(habitName) {
                mutableIntStateOf(currentTime?.substringBefore(':')?.toIntOrNull() ?: 20)
            }
            var minute by remember(habitName) {
                mutableIntStateOf(currentTime?.substringAfter(':')?.toIntOrNull() ?: 0)
            }
            TimeWheelPicker(
                hour24 = hour24,
                minute = minute,
                onTimeChange = { h, m -> hour24 = h; minute = m },
                compact = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel", fontSize = 12.sp)
                }
                TextButton(onClick = {
                    onSetScheduleTime(habitName, String.format("%02d:%02d", hour24, minute))
                    showPicker = false
                }) {
                    Text("Save", fontSize = 12.sp)
                }
            }
        }
    }
}


@Composable
internal fun EditModeControlBar(
    selectedIndex: Int,
    selectedHabitName: String?,
    selectedHabitRawTodayCount: Int,
    selectedHabitTodayCount: Int = selectedHabitRawTodayCount,
    isPlaceholderSelected: Boolean,
    habitScreens: List<HabitScreen>,
    activeScreenIndex: Int,
    selectedHabitScreenIndex: Int,
    maxOneHabits: Set<String>,
    /** Habits with the "inverted binary" type (point + streak on NOT-done days). */
    invertedBinaryHabits: Set<String> = emptySet(),
    customInputHabits: Set<String>,
    customInputAmounts: Map<String, List<Int>> = emptyMap(),
    textInputHabits: Set<String>,
    textInputOptionsHabits: Set<String>,
    /** Text-input habits that appear in the system share sheet (ShareTextActivity picker). */
    sharableTextHabits: Set<String> = emptySet(),
    textInputFileUris: Map<String, String>,
    datedEntryHabits: Set<String>,
    datedEntryFileUris: Map<String, String>,
    habitDividers: Map<String, Int>,
    conditionalHabits: Set<String>,
    conditionalLinkedHabits: Map<String, Set<String>>,
    /** Per-link conditional feed-value overrides (source → linked → value key). */
    conditionalLinkValues: Map<String, Map<String, String>> = emptyMap(),
    /** Conditional habits whose Points feeds are capped at 1 point per day. */
    conditionalFeedMaxOneHabits: Set<String> = emptySet(),
    /** Called when the user toggles the "feed max1 point/day" conditional sub-setting. */
    onToggleConditionalFeedMaxOne: (String) -> Unit = {},
    /** Conditional habits whose feeds send points (divider-applied) instead of raw counts. */
    conditionalFeedPointsHabits: Set<String> = emptySet(),
    /** Called when the user toggles the "feed points" conditional sub-setting. */
    onToggleConditionalFeedPoints: (String) -> Unit = {},
    subtypedHabits: Set<String>,
    habitSubtypes: Map<String, List<String>>,
    allHabitNames: List<String>,
    rollForwardHabits: Set<String> = emptySet(),
    rollForwardManualDates: Map<String, Set<String>> = emptyMap(),
    garminMonthlyData: Map<com.example.tail.data.GarminType, Map<String, Int>> = emptyMap(),
    selectedDate: java.time.LocalDate = java.time.LocalDate.now(),
    onAddHabit: () -> Unit,
    onAddAppLink: () -> Unit = {},
    onAddScreen: () -> Unit,
    onDeleteScreen: () -> Unit,
    onToggleMaxOne: (String) -> Unit,
    /** Called when the user toggles the "Inverted binary" type for a habit. */
    onToggleInvertedBinary: (String) -> Unit = {},
    onToggleCustomInput: (String) -> Unit,
    onSetCustomInputAmounts: (String, List<Int>) -> Unit = { _, _ -> },
    onToggleTextInput: (String) -> Unit,
    onToggleTextInputOptions: (String) -> Unit,
    /** Called when the user toggles the "Sharable" sub-feature for a habit. */
    onToggleSharableText: (String) -> Unit = {},
    onPickTextInputFile: (String) -> Unit,
    /** Called when the user wants to CREATE a new text log file in a picked directory. */
    onCreateTextInputFile: (String) -> Unit = {},
    onToggleDatedEntry: (String) -> Unit,
    onPickDatedEntryFile: (String) -> Unit,
    /** Called when the user taps "Refresh" to re-parse the linked dated-entry file. */
    onRefreshDatedEntry: (String) -> Unit = {},
    onDeleteHabit: (String) -> Unit,
    onChangeIcon: (String) -> Unit,
    onRenameHabit: (String, String) -> Unit,
    onSetCount: (String, Int) -> Unit,
    onSetCountWithRollForward: (String, Int, java.time.LocalDate) -> Unit = { _, _, _ -> },
    /** Called when the user sets the MINUTES value (`minutes:<habit>` slot) for a habit. */
    onSetMinutesCount: (String, Int) -> Unit = { _, _ -> },
    /** Today's minutes count (`minutes:<habit>` slot) for the selected habit. */
    selectedHabitMinutesTodayCount: Int = 0,
    /** Habits that use the minutes slot as points fallback on 0-session days. */
    minutesFallbackHabits: Set<String> = emptySet(),
    /** Called when the user toggles the minutes fallback for a habit. */
    onToggleMinutesFallback: (String) -> Unit = {},
    /** Per-habit fallback source for minutes-primary habits (none/sessions/value2). */
    minutesPrimaryFallbacks: Map<String, String> = emptyMap(),
    /** Called when the user picks the fallback source for a minutes-primary habit. */
    onSetMinutesPrimaryFallback: (String, String) -> Unit = { _, _ -> },
    onSetDivider: (String, Int) -> Unit,
    onToggleConditional: (String) -> Unit,
    onSetConditionalLinks: (String) -> Unit,
    onBackfillConditional: (String) -> Unit = {},
    onToggleSubtyped: (String) -> Unit,
    onSetSubtypes: (String, List<String>) -> Unit,
    mealHabits: Set<String> = emptySet(),
    onToggleMeal: (String) -> Unit = {},
    /** Weights-type habits (kg/lb + reps machine/free logging on tap). */
    weightsHabits: Set<String> = emptySet(),
    onToggleWeights: (String) -> Unit = {},
    /** Weights habits: the selected date's slot values (null = not a weights habit). */
    weightsDayValues: com.example.tail.data.WeightsDayValues? = null,
    /** Display unit ("kg"/"lb") for the weights day summary + editor. */
    weightsUnit: String = "kg",
    /** Called when the user saves the weights day editor (habitName, values, exerciseName). */
    onSetWeightsDayValues: (String, com.example.tail.data.WeightsDayValues, String) -> Unit = { _, _, _ -> },
    /** Previously used exercise/machine names for the selected weights habit (quick choices). */
    weightsRecentExercises: List<String> = emptyList(),
    /** Called when the user deletes ALL weights data for a day (habitName). */
    onDeleteWeightsDay: (String) -> Unit = {},
    onOpenMealDetails: (String) -> Unit = {},
    /** Habits excluded from the day timeline (retrospective hour-by-hour view). */
    timelineExcludedHabits: Set<String> = emptySet(),
    /** Called when the user toggles day-timeline visibility for a habit. */
    onToggleTimelineExcluded: (String) -> Unit = {},
    /** Habits eligible for camera/vision auto-detection ("Camera" setting). */
    cameraHabits: Set<String> = emptySet(),
    /** Called when the user toggles the "Camera" setting for a habit. */
    onToggleCamera: (String) -> Unit = {},
    /** Map of habit name → configured long-press action string. */
    habitLongPressActions: Map<String, String> = emptyMap(),
    /** Called when the user changes the long-press action (habitName, action). */
    onSetLongPressAction: (String, String) -> Unit = { _, _ -> },
    /** Map of habit name → URL opened on long-press (LONG_PRESS_URL action). */
    habitLongPressUrls: Map<String, String> = emptyMap(),
    /** Called when the user edits the long-press URL (habitName, url). */
    onSetLongPressUrl: (String, String) -> Unit = { _, _ -> },
    /** Map of habit name → package that handles the long-press URL. */
    habitLongPressUrlApps: Map<String, String> = emptyMap(),
    /** Called when the user wants to pick the app for the long-press URL. */
    onPickLongPressUrlApp: (String) -> Unit = {},
    /** Called when the user clears the long-press URL app (back to browser). */
    onClearLongPressUrlApp: (String) -> Unit = {},
    hiddenScreenIds: Set<String> = emptySet(),
    onToggleScreenHidden: () -> Unit = {},
    disabledHabits: Set<String> = emptySet(),
    onToggleDisabled: (String) -> Unit = {},
    noPointsHabits: Set<String> = emptySet(),
    onToggleNoPoints: (String) -> Unit = {},
    secondaryValueSettings: SecondaryValueSettings = SecondaryValueSettings(),
    /** Display-only label overrides: habitName → (valueKey → customLabel). */
    valueDisplayLabels: Map<String, Map<String, String>> = emptyMap(),
    /** Called when the user edits a display label (habitName, valueKey, label). */
    onSetValueDisplayLabel: (String, String, String) -> Unit = { _, _, _ -> },
    chessComEnabled: Boolean = false,
    chessComHabitLinks: Map<String, String> = emptyMap(),
    onSetChessComLink: (String, String?) -> Unit = { _, _ -> },
    garminEnabled: Boolean = false,
    garminHabitLinks: Map<String, String> = emptyMap(),
    onSetGarminLink: (String, String?) -> Unit = { _, _ -> },
    garminDateOfBirth: String = "",
    // ── GitHub Integration (rendered by caller, like movieBridgeContent) ──
    githubContent: @Composable () -> Unit = {},
    movieBridgeContent: @Composable () -> Unit = {},
    pcWidgetContent: @Composable () -> Unit = {},
    voiceTriggerEnabled: Boolean = false,
    voiceTriggerHabits: Set<String> = emptySet(),
    voiceTriggerWords: Map<String, Set<String>> = emptyMap(),
    voiceTriggerIncrements: Map<String, Int> = emptyMap(),
    onToggleVoiceTrigger: (String) -> Unit = {},
    onSetVoiceTriggerWords: (String, Set<String>) -> Unit = { _, _ -> },
    onSetVoiceTriggerIncrement: (String, Int) -> Unit = { _, _ -> },
    voiceSubtypeHabits: Set<String> = emptySet(),
    onToggleVoiceSubtype: (String) -> Unit = {},
    timelessHabits: Set<String> = emptySet(),
    onToggleTimeless: (String) -> Unit = {},
    customPointRangesHabits: Set<String> = emptySet(),
    customPointRanges: Map<String, List<com.example.tail.data.PointRange>> = emptyMap(),
    onToggleCustomPointRanges: (String) -> Unit = {},
    onSetCustomPointRanges: (String, List<com.example.tail.data.PointRange>) -> Unit = { _, _ -> },
    /** Number of timestamps for the selected habit on the current day. */
    selectedHabitTimestampCount: Int = 0,
    /** Called when the user taps the timestamps button. */
    onShowTimestamps: (String) -> Unit = {},
    /** Today's text entries for the selected habit (timestamp → text pairs). */
    todayTextEntries: List<Pair<String, String>> = emptyList(),
    /** Called to load text entries for a habit. */
    onLoadTextEntries: (String, (List<Pair<String, String>>) -> Unit) -> Unit = { _, _ -> },
    /** Called when the user edits an existing text entry. */
    onEditTextEntry: (String, String, String) -> Unit = { _, _, _ -> },
    /** Called when the user adds a new text entry for the selected day (habitName, text). */
    onAddTextEntry: (String, String) -> Unit = { _, _ -> },
    /** Called when the user deletes an existing text entry. */
    onDeleteTextEntry: (String, String) -> Unit = { _, _ -> },
    /** Map of habit name → note text. */
    habitNotes: Map<String, String> = emptyMap(),
    /** Called when the user edits the note for a habit. */
    onSetHabitNote: (String, String) -> Unit = { _, _ -> },
    /** Called when the user toggles roll forward for a habit. */
    onToggleRollForward: (String) -> Unit = {},
    /** Called when the user taps "Restore from Backup" for the selected habit. */
    onRestoreFromBackup: () -> Unit = {},
    // ── Scheduled ask (daily notification) parameters ──────────────────────
    /** Map of habit name → daily "HH:mm" ask time. */
    habitScheduleTimes: Map<String, String> = emptyMap(),
    /** Called when the user sets (time non-null) or removes (null) the daily ask time. */
    onSetHabitScheduleTime: (String, String?) -> Unit = { _, _ -> },
    // ── Habit App Association parameters ───────────────────────────────────
    /** Map of habit name → ordered list of associated app package names. */
    habitAppAssociations: Map<String, List<String>> = emptyMap(),
    /** Called when the user taps "Add App" to associate an app with the habit. */
    onAddAppAssociation: (String) -> Unit = {},
    /** Called when the user removes an app association (habitName, packageName). */
    onRemoveAppAssociation: (String, String) -> Unit = { _, _ -> },
    /** Called when the user reorders an app association (habitName, fromIndex, toIndex). */
    onMoveAppAssociation: (String, Int, Int) -> Unit = { _, _, _ -> },
    // ── Widget Trigger parameters ──────────────────────────────────────────
    /** Habits that have the "Use Widget" feature enabled. */
    widgetTriggerHabits: Set<String> = emptySet(),
    /** Maps habit name → trigger app package name. */
    widgetTriggerApps: Map<String, String> = emptyMap(),
    /** Called when the user toggles the "Use Widget" feature for a habit. */
    onToggleWidgetTrigger: (String) -> Unit = {},
    /** Called when the user taps to select/change the trigger app. */
    onSetWidgetTriggerApp: (String) -> Unit = {},
    /** Whether the user has granted Usage Access permission. */
    hasUsageAccess: Boolean = true,
    /** Called when the user taps to grant Usage Access. */
    onRequestUsageAccess: () -> Unit = {},
    /** Widget-timer habits where minutes (not sessions) is the primary value. */
    widgetTimerMinutesPrimary: Set<String> = emptySet(),
    /** Called when the user changes which value is primary (true = minutes). */
    onSetTimerPrimaryValue: (String, Boolean) -> Unit = { _, _ -> },
    /** Effective minutes-enabled state for the selected habit. */
    minutesEnabled: Boolean = false,
    /** Minutes forced ON by a timer-widget connection (locked toggle). */
    minutesForcedByWidget: Boolean = false,
    /** Called when the user toggles the per-habit minutes value on/off. */
    onToggleMinutesEnabled: (String) -> Unit = {},
    // ── Media type parameters ─────────────────────────────────────────────
    /** Habits that have the "Media" type enabled. */
    mediaHabits: Set<String> = emptySet(),
    /** Maps habit name → media app package name. */
    mediaApps: Map<String, String> = emptyMap(),
    /** Called when the user toggles the "Media" type for a habit. */
    onToggleMedia: (String) -> Unit = {},
    /** Called when the user taps to select/change the media app. */
    onSetMediaApp: (String) -> Unit = {},
    /** Whether notification-listener access is granted (needed for auto-detection). */
    hasNotificationAccess: Boolean = false,
    /** Called when the user taps to grant notification access. */
    onRequestNotificationAccess: () -> Unit = {},
    /** Today's per-show listening breakdown for the selected media habit. */
    mediaTodayShows: List<MediaShowMinutes> = emptyList(),
    /** Called to (re)load the per-show breakdown for a media habit. */
    onLoadMediaShows: (String) -> Unit = {},
    /** Called when the user removes a show from today's media log (habitName, show). */
    onRemoveMediaShow: (String, String) -> Unit = { _, _ -> },
    /** Called when the user confirms the invert operation for a habit. */
    onInvertHabit: (String) -> Unit = {},
    /** Returns invert preview stats for a habit, or null if it has no data. */
    onGetInvertPreview: (String) -> InvertPreview? = { null }
) {
    val hasSelection = selectedIndex >= 0


    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .verticalScroll(rememberScrollState())
            .background(Color(0xFF1A1000))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {

        when {
            // ── Nothing selected ──────────────────────────────────────────
            !hasSelection -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "✏ Tap a habit or placeholder to select",
                        color = Color(0xFF888888),
                        fontSize = 11.sp
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (habitScreens.size > 1) {
                            Button(
                                onClick = onDeleteScreen,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A0000)),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text("Del Screen", fontSize = 11.sp, color = Color(0xFFFF8888))
                            }
                        }
                        Button(
                            onClick = onAddScreen,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A1A)),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "Add screen",
                                tint = Color(0xFF88FF88),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add Screen", fontSize = 11.sp, color = Color(0xFF88FF88))
                        }
                    }
                }
                // Hide Screen toggle — only when screens exist
                if (habitScreens.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val activeScreen = habitScreens.getOrNull(activeScreenIndex)
                    val isHidden = activeScreen != null && activeScreen.id in hiddenScreenIds
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Hide screen", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            Text(
                                text = if (isHidden) "Name hidden in tab bar when not selected"
                                       else "Name always visible in tab bar",
                                color = Color(0xFF888888), fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isHidden,
                            onCheckedChange = { onToggleScreenHidden() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFAA88FF),
                                checkedTrackColor = Color(0xFF2A1A4A),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }
                }
            }

            // ── Placeholder selected ──────────────────────────────────────
            isPlaceholderSelected -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Placeholder [${selectedIndex}]",
                        color = Color(0xFF888888),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    // Add Habit button
                    Button(
                        onClick = onAddHabit,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A1A)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Add habit",
                            tint = Color(0xFF88FF88),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Add Habit", fontSize = 11.sp, color = Color(0xFF88FF88))
                    }
                    // Add App Link button
                    Button(
                        onClick = onAddAppLink,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("📱", fontSize = 11.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("App", fontSize = 11.sp, color = Color(0xFF66CCFF))
                    }
                }
            }

            // ── App link selected ───────────────────────────────────────────
            selectedHabitName != null && isAppLink(selectedHabitName) -> {
                AppLinkEditSection(
                    selectedHabitName = selectedHabitName,
                    onDeleteHabit = onDeleteHabit
                )
            }

            // ── Habit selected ────────────────────────────────────────────
            else -> {
                EditModeHabitHeaderRow(
                    selectedHabitName = selectedHabitName,
                    selectedHabitTodayCount = selectedHabitTodayCount,
                    selectedHabitRawTodayCount = selectedHabitRawTodayCount,
                    rollForwardHabits = rollForwardHabits,
                    rollForwardManualDates = rollForwardManualDates,
                    selectedDate = selectedDate,
                    onSetCount = onSetCount,
                    onSetCountWithRollForward = onSetCountWithRollForward
                )
                // Weights habits: the selected date's slot summary + day
                // editor, right under the header count row.
                if (selectedHabitName != null && weightsDayValues != null) {
                    EditModeWeightsSummarySection(
                        habitName = selectedHabitName,
                        values = weightsDayValues,
                        unit = weightsUnit,
                        recentExercises = weightsRecentExercises,
                        onSetValues = { v, exerciseName ->
                            onSetWeightsDayValues(selectedHabitName, v, exerciseName)
                        },
                        onDeleteDay = { onDeleteWeightsDay(selectedHabitName) }
                    )
                }
                // Value editor row — for timer habits (two value tracks:
                // sessions + minutes) a dropdown picks which value to edit,
                // defaulting to the habit's PRIMARY value. Single-track
                // habits get a plain labelled field. For Garmin-linked
                // habits, show the read-only Garmin metric value.
                // Extracted to its own composable to keep EditModeControlBar
                // under the JVM method-size limit.
                EditModeValueEditorRow(
                    selectedHabitName = selectedHabitName,
                    garminHabitLinks = garminHabitLinks,
                    garminMonthlyData = garminMonthlyData,
                    garminDateOfBirth = garminDateOfBirth,
                    selectedDate = selectedDate,
                    habitDividers = habitDividers,
                    widgetTriggerApps = widgetTriggerApps,
                    widgetTimerMinutesPrimary = widgetTimerMinutesPrimary,
                    mediaHabits = mediaHabits,
                    minutesEnabled = minutesEnabled,
                    rawTodayCount = selectedHabitRawTodayCount,
                    minutesTodayCount = selectedHabitMinutesTodayCount,
                    onSetCount = onSetCount,
                    onSetMinutesCount = onSetMinutesCount
                )
                // Standalone minutes editor — ONLY for habits whose value
                // editor above does not already cover minutes AND that have
                // a single value track (plain habits). A minutes input is
                // never stacked above a true-value input: any habit with
                // more than one editable value (timer habits, divider
                // habits with minutes on) uses the Sessions/Minutes
                // dropdown inside the value editor instead.
                if (selectedHabitName != null &&
                    minutesEnabled &&
                    selectedHabitName !in garminHabitLinks &&
                    selectedHabitName !in mediaHabits &&
                    widgetTriggerApps[selectedHabitName].isNullOrBlank() &&
                    selectedHabitName !in secondaryValueSettings.habits &&
                    (habitDividers[selectedHabitName] ?: 1) <= 1
                ) {
                    EditModeMinutesEditorRow(
                        habitName = selectedHabitName,
                        minutesToday = selectedHabitMinutesTodayCount,
                        onSetMinutesToday = onSetMinutesCount
                    )
                }
                // Timestamps button — always available so timestamps can be
                // added/edited for any habit on any day (past or current),
                // even when none exist yet.
                if (selectedHabitName != null) {
                    Button(
                        onClick = { onShowTimestamps(selectedHabitName) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A)),
                        modifier = Modifier.height(28.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = if (selectedHabitTimestampCount > 0)
                                "🕐 Timestamps ($selectedHabitTimestampCount)"
                            else "🕐 Add Timestamps",
                            fontSize = 10.sp,
                            color = Color(0xFFBBBBFF)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))

                EditModeHabitActionRows(
                    selectedHabitName = selectedHabitName,
                    onDeleteHabit = onDeleteHabit,
                    onChangeIcon = onChangeIcon,
                    onRenameHabit = onRenameHabit
                )

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFF333300), thickness = 1.dp)
                Spacer(modifier = Modifier.height(6.dp))

                // SETTINGS section
                Text(
                    text = "SETTINGS",
                    color = Color(0xFF888888),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))

                if (selectedHabitName != null) {

                    // ── Meal Detail (meal habits only) ─────────────────────────
                    // Opens the meal detail editor; kept at the top of settings
                    // so meal habits don't need to dig through the special
                    // habit types drawer to find it.
                    if (selectedHabitName in mealHabits) {
                        MealDetailButton(
                            habitName = selectedHabitName,
                            onOpenMealDetails = onOpenMealDetails
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                    }

                    // ── Divider toggle ────────────────────────────────────────
                    val currentDivisor = habitDividers[selectedHabitName] ?: 1
                    val isDivider = currentDivisor > 1
                    // Local state for the divisor text field (only shown when divider is on)
                    var divisorText by remember(selectedHabitName) {
                        mutableStateOf(if (isDivider) currentDivisor.toString() else "")
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Divider", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            Text(
                                text = if (isDivider) "Points = input ÷ $currentDivisor (rounded, min 1)" else "Points = raw input",
                                color = Color(0xFF888888), fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isDivider,
                            onCheckedChange = { on ->
                                if (on) {
                                    // Enable with default divisor of 2 if no text entered yet
                                    val d = divisorText.toIntOrNull()?.coerceAtLeast(2) ?: 2
                                    divisorText = d.toString()
                                    onSetDivider(selectedHabitName, d)
                                } else {
                                    onSetDivider(selectedHabitName, 1)
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFF88FF),
                                checkedTrackColor = Color(0xFF4A004A),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }

                    if (isDivider) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "  Divide by:",
                                color = Color(0xFFAAAAAA),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = divisorText,
                                onValueChange = { v: String ->
                                    divisorText = v.filter { it.isDigit() }
                                    val d = divisorText.toIntOrNull() ?: 0
                                    if (d >= 2) onSetDivider(selectedHabitName, d)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                modifier = Modifier.width(64.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFFF88FF),
                                    unfocusedTextColor = Color(0xFFFF88FF),
                                    focusedBorderColor = Color(0xFFFF88FF),
                                    unfocusedBorderColor = Color(0xFF884488)
                                ),
                                textStyle = TextStyle(
                                    fontSize = 12.sp,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Note / 1-max / Custom input / Text input toggles ──────
                    // Extracted into [HabitInputModesSection] to keep
                    // EditModeControlBar under the JVM method-size limit.
                    val isTextInput = selectedHabitName in textInputHabits
                    HabitInputModesSection(
                        selectedHabitName = selectedHabitName,
                        habitNotes = habitNotes,
                        onSetHabitNote = onSetHabitNote,
                        maxOneHabits = maxOneHabits,
                        onToggleMaxOne = onToggleMaxOne,
                        invertedBinaryHabits = invertedBinaryHabits,
                        onToggleInvertedBinary = onToggleInvertedBinary,
                        customInputHabits = customInputHabits,
                        customInputAmounts = customInputAmounts,
                        onToggleCustomInput = onToggleCustomInput,
                        onSetCustomInputAmounts = onSetCustomInputAmounts,
                        textInputHabits = textInputHabits,
                        textInputOptionsHabits = textInputOptionsHabits,
                        sharableTextHabits = sharableTextHabits,
                        textInputFileUris = textInputFileUris,
                        onToggleTextInput = onToggleTextInput,
                        onToggleTextInputOptions = onToggleTextInputOptions,
                        onToggleSharableText = onToggleSharableText,
                        onPickTextInputFile = onPickTextInputFile,
                        onCreateTextInputFile = onCreateTextInputFile
                    )

                    // ── Today's text entries (view/edit) ──────────────────────
                    if (isTextInput && textInputFileUris.containsKey(selectedHabitName)) {
                        Spacer(modifier = Modifier.height(6.dp))

                        val visibleTextEntries = todayTextEntries.filter { it.second.isNotBlank() }
                        if (visibleTextEntries.isNotEmpty()) {
                            Text(
                                text = "  Today's entries",
                                color = Color(0xFF88CCFF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            for ((timestamp, text) in visibleTextEntries) {
                                var isEditing by remember { mutableStateOf(false) }
                                var editText by remember { mutableStateOf(text) }

                                if (isEditing) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, bottom = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedTextField(
                                            value = editText,
                                            onValueChange = { editText = it },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedTextColor = Color.White,
                                                unfocusedTextColor = Color.White,
                                                focusedBorderColor = Color(0xFF44AAFF),
                                                unfocusedBorderColor = Color(0xFF555555),
                                                cursorColor = Color(0xFF44AAFF)
                                            ),
                                            textStyle = TextStyle(fontSize = 12.sp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        TextButton(
                                            onClick = {
                                                if (editText.trim().isNotEmpty()) {
                                                    onEditTextEntry(selectedHabitName, timestamp, editText.trim())
                                                }
                                                isEditing = false
                                            }
                                        ) {
                                            Text("✓", color = Color(0xFF88FF88), fontSize = 14.sp)
                                        }
                                        TextButton(
                                            onClick = {
                                                editText = text
                                                isEditing = false
                                            }
                                        ) {
                                            Text("✕", color = Color(0xFF888888), fontSize = 13.sp)
                                        }
                                    }
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = 16.dp, bottom = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = text,
                                            color = Color(0xFFCCCCCC),
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 2
                                        )
                                        TextButton(
                                            onClick = { isEditing = true; editText = text },
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                                start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp
                                            )
                                        ) {
                                            Text("✎", color = Color(0xFF888888), fontSize = 14.sp)
                                        }
                                        TextButton(
                                            onClick = { onDeleteTextEntry(selectedHabitName, timestamp) },
                                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                                start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp
                                            )
                                        ) {
                                            Text("✕", color = Color(0xFF666666), fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        } else {
                            // No entries for this day — still offer an edit button so the
                            // user can set text for a past (or empty) day.
                            Text(
                                text = "  Today's entries",
                                color = Color(0xFF88CCFF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            var isAdding by remember { mutableStateOf(false) }
                            var addText by remember { mutableStateOf("") }

                            if (isAdding) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = addText,
                                        onValueChange = { addText = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            focusedBorderColor = Color(0xFF44AAFF),
                                            unfocusedBorderColor = Color(0xFF555555),
                                            cursorColor = Color(0xFF44AAFF)
                                        ),
                                        textStyle = TextStyle(fontSize = 12.sp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    TextButton(
                                        onClick = {
                                            if (addText.trim().isNotEmpty()) {
                                                onAddTextEntry(selectedHabitName, addText.trim())
                                            }
                                            isAdding = false
                                            addText = ""
                                        }
                                    ) {
                                        Text("✓", color = Color(0xFF88FF88), fontSize = 14.sp)
                                    }
                                    TextButton(
                                        onClick = {
                                            addText = ""
                                            isAdding = false
                                        }
                                    ) {
                                        Text("✕", color = Color(0xFF888888), fontSize = 13.sp)
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(start = 16.dp, bottom = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "(no text)",
                                        color = Color(0xFF666666),
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(
                                        onClick = { isAdding = true },
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                            start = 4.dp, end = 4.dp, top = 0.dp, bottom = 0.dp
                                        )
                                    ) {
                                        Text("✎", color = Color(0xFF888888), fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Dated Entry toggle ────────────────────────────────────
                    val isDatedEntry = selectedHabitName in datedEntryHabits
                    var showDatedEntryInfo by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Column {
                                Text(text = "Dated Entry", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                                Text(
                                    text = if (isDatedEntry) "Auto-counts from linked file" else "Manual count only",
                                    color = Color(0xFF888888), fontSize = 10.sp
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { showDatedEntryInfo = true },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.Info,
                                    contentDescription = "Dated Entry format info",
                                    tint = Color(0xFF6699CC),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        Switch(
                            checked = isDatedEntry,
                            onCheckedChange = { onToggleDatedEntry(selectedHabitName) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFFCC44),
                                checkedTrackColor = Color(0xFF4A3A00),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }

                    if (isDatedEntry) {
                        Spacer(modifier = Modifier.height(4.dp))

                        // Dated-entry file picker row
                        val hasDatedFile = datedEntryFileUris.containsKey(selectedHabitName)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "  Source file", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                Text(
                                    text = if (hasDatedFile) "✓ File linked" else "⚠ No file linked",
                                    color = if (hasDatedFile) Color(0xFFFFCC44) else Color(0xFFFF8844),
                                    fontSize = 10.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (hasDatedFile) {
                                    Button(
                                        onClick = { onRefreshDatedEntry(selectedHabitName) },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A1A)),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Refresh,
                                            contentDescription = "Re-parse linked file and refresh values",
                                            tint = Color(0xFF88FF88),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Refresh", fontSize = 11.sp, color = Color(0xFF88FF88))
                                    }
                                }
                                Button(
                                    onClick = { onPickDatedEntryFile(selectedHabitName) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A00)),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Folder,
                                        contentDescription = "Pick dated entry source file",
                                        tint = Color(0xFFFFCC44),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        if (hasDatedFile) "Change" else "Link File",
                                        fontSize = 11.sp,
                                        color = Color(0xFFFFCC44)
                                    )
                                }
                            }
                        }
                    }

                    // Dated Entry format info dialog
                    if (showDatedEntryInfo) {
                        DatedEntryInfoDialog(onDismiss = { showDatedEntryInfo = false })
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Conditional toggle ────────────────────────────────────
                    val isConditional = selectedHabitName in conditionalHabits
                    val linkedCount = conditionalLinkedHabits[selectedHabitName]?.size ?: 0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Conditional", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            Text(
                                text = if (isConditional) "Auto-increments $linkedCount linked habit(s)" else "No auto-increment",
                                color = Color(0xFF888888), fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isConditional,
                            onCheckedChange = { onToggleConditional(selectedHabitName) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFF88CC),
                                checkedTrackColor = Color(0xFF4A0030),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }

                    if (isConditional) {
                        Spacer(modifier = Modifier.height(4.dp))
                        // ── "Feed max1" sub-setting (only while conditional is on) ──
                        val isFeedMaxOne = selectedHabitName in conditionalFeedMaxOneHabits
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "  Feed max1 point/day", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                Text(
                                    text = if (isFeedMaxOne) "Linked habits get at most 1 point per day"
                                           else "Every increment feeds its linked habits",
                                    color = if (isFeedMaxOne) Color(0xFF66BB6A) else Color(0xFF888888),
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = isFeedMaxOne,
                                onCheckedChange = { onToggleConditionalFeedMaxOne(selectedHabitName) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFFFF88CC),
                                    checkedTrackColor = Color(0xFF4A0030),
                                    uncheckedThumbColor = Color(0xFF888888),
                                    uncheckedTrackColor = Color(0xFF333333)
                                )
                            )
                        }

                        // ── "Feed points" sub-setting (only while conditional is
                        // on AND a divider > 1 makes points differ from raw) ──
                        val srcDivider = habitDividers[selectedHabitName] ?: 1
                        if (srcDivider > 1) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val isFeedPoints = selectedHabitName in conditionalFeedPointsHabits
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "  Feed points (÷$srcDivider)", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                    Text(
                                        text = if (isFeedPoints) "Linked habits receive this habit's points"
                                               else "Linked habits receive the raw count",
                                        color = if (isFeedPoints) Color(0xFF66BB6A) else Color(0xFF888888),
                                        fontSize = 10.sp
                                    )
                                }
                                Switch(
                                    checked = isFeedPoints,
                                    onCheckedChange = { onToggleConditionalFeedPoints(selectedHabitName) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFFFF88CC),
                                        checkedTrackColor = Color(0xFF4A0030),
                                        uncheckedThumbColor = Color(0xFF888888),
                                        uncheckedTrackColor = Color(0xFF333333)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "  Linked habits", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                val linkNames = conditionalLinkedHabits[selectedHabitName]
                                Text(
                                    text = if (linkNames.isNullOrEmpty()) "⚠ None selected"
                                           else "✓ ${linkNames.joinToString(", ") { n ->
                                               val vk = effectiveConditionalLinkValueKey(conditionalLinkValues, secondaryValueSettings.habits, chessComHabitLinks, selectedHabitName, n)
                                               if (vk == GRAPH_METRIC_POINTS) n
                                               else "$n (${displayLabelForValue(n, vk, valueDisplayLabels)})"
                                           }}",
                                    color = if (linkNames.isNullOrEmpty()) Color(0xFFFF8844) else Color(0xFFFF88CC),
                                    fontSize = 10.sp
                                )
                            }
                            Button(
                                onClick = { onSetConditionalLinks(selectedHabitName) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A0030)),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    if (linkedCount > 0) "Edit Links" else "Set Links",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFF88CC)
                                )
                            }
                        }
                    }

                    // ── Conditional Backfill (only for habits that other habits link to) ──
                    ConditionalBackfillSection(
                        habitName = selectedHabitName,
                        conditionalHabits = conditionalHabits,
                        conditionalLinkedHabits = conditionalLinkedHabits,
                        conditionalLinkValues = conditionalLinkValues,
                        secondaryValueHabits = secondaryValueSettings.habits,
                        chessComHabitLinks = chessComHabitLinks,
                        valueDisplayLabels = valueDisplayLabels,
                        onBackfill = onBackfillConditional
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Subtyped toggle ────────────────────────────────────
                    val isSubtyped = selectedHabitName in subtypedHabits
                    val currentSubtypes = habitSubtypes[selectedHabitName] ?: emptyList()
                    var subtypesText by remember(selectedHabitName) {
                        mutableStateOf(currentSubtypes.joinToString(", "))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Subtyped", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            Text(
                                text = if (isSubtyped) "${currentSubtypes.size} subtypes configured" else "No subtypes",
                                color = Color(0xFF888888), fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isSubtyped,
                            onCheckedChange = { onToggleSubtyped(selectedHabitName) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF44DDAA),
                                checkedTrackColor = Color(0xFF0A3A2A),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }

                    if (isSubtyped) {
                        Spacer(modifier = Modifier.height(4.dp))

                        // Subtypes editor
                        OutlinedTextField(
                            value = subtypesText,
                            onValueChange = { newText ->
                                subtypesText = newText
                                val types = newText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                onSetSubtypes(selectedHabitName, types)
                            },
                            label = { Text("Subtypes (comma-separated)", fontSize = 10.sp) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = Color(0xFF44DDAA),
                                unfocusedTextColor = Color(0xFF44DDAA),
                                focusedBorderColor = Color(0xFF44DDAA),
                                unfocusedBorderColor = Color(0xFF226655)
                            ),
                            textStyle = TextStyle(fontSize = 12.sp)
                        )

                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Long-press action selector ────────────────────────────
                    LongPressActionSection(
                        habitName = selectedHabitName,
                        isMeal = selectedHabitName in mealHabits,
                        currentAction = com.example.tail.data.effectiveLongPressAction(
                            habitLongPressActions[selectedHabitName]
                        ),
                        onSetAction = onSetLongPressAction,
                        currentUrl = habitLongPressUrls[selectedHabitName] ?: "",
                        onSetUrl = onSetLongPressUrl,
                        currentUrlApp = habitLongPressUrlApps[selectedHabitName],
                        onPickUrlApp = onPickLongPressUrlApp,
                        onClearUrlApp = onClearLongPressUrlApp
                    )

                    // ── Disabled / No-points / Secondary-value / Timeline toggles ──
                    HabitToggleSection(
                        habitName = selectedHabitName,
                        isDisabled = selectedHabitName in disabledHabits,
                        onToggleDisabled = onToggleDisabled,
                        isNoPoints = selectedHabitName in noPointsHabits,
                        onToggleNoPoints = onToggleNoPoints,
                        isSecondaryValue = selectedHabitName in secondaryValueSettings.habits,
                        onToggleSecondaryValue = secondaryValueSettings.onToggleSecondaryValue,
                        isSecondaryValueFallback = selectedHabitName in secondaryValueSettings.fallbackHabits,
                        onToggleSecondaryValueFallback = secondaryValueSettings.onToggleSecondaryValueFallback,
                        showOnTimeline = selectedHabitName !in timelineExcludedHabits,
                        onToggleTimeline = onToggleTimelineExcluded
                    )

                    // ── Minutes value on/off — first-class minutes toggle ──
                    // Hidden for max-1 habits (a binary habit never has
                    // minutes); locked ON for timer-widget habits (the
                    // widget timer feeds the minutes slot).
                    if (selectedHabitName !in maxOneHabits) {
                        MinutesToggleSection(
                            habitName = selectedHabitName,
                            minutesEnabled = minutesEnabled,
                            forcedByWidget = minutesForcedByWidget,
                            onToggleMinutesEnabled = onToggleMinutesEnabled
                        )
                    }

                    // ── Primary value (Sessions vs Minutes) — first-class minutes ──
                    // Only meaningful while the minutes value exists; with
                    // minutes off, sessions are the one and only value.
                    if (minutesEnabled) {
                        PrimaryValueSection(
                            habitName = selectedHabitName,
                            isSecondaryValue = selectedHabitName in secondaryValueSettings.habits,
                            minutesPrimary = selectedHabitName in widgetTimerMinutesPrimary,
                            onSetPrimaryValue = onSetTimerPrimaryValue,
                            minutesFallback = selectedHabitName in minutesFallbackHabits,
                            onToggleMinutesFallback = onToggleMinutesFallback,
                            minutesPrimaryFallback = minutesPrimaryFallbacks[selectedHabitName]
                                ?: com.example.tail.data.MINUTES_PRIMARY_FALLBACK_SESSIONS,
                            onSetMinutesPrimaryFallback = onSetMinutesPrimaryFallback,
                            secondValueLabel = valueDisplayLabels[selectedHabitName]
                                ?.get(com.example.tail.data.GRAPH_METRIC_VALUE2)
                                ?.takeIf { it.isNotBlank() }
                        )
                    }

                    // ── Value Labels (display-only override) ───────────────────
                    ValueLabelsSection(
                        habitName = selectedHabitName,
                        hasSecondaryValue = selectedHabitName in secondaryValueSettings.habits,
                        subtypes = habitSubtypes[selectedHabitName] ?: emptyList(),
                        labels = valueDisplayLabels[selectedHabitName] ?: emptyMap(),
                        onSetLabel = onSetValueDisplayLabel
                    )

                    // ── Custom Point Ranges toggle ────────────────────────────
                    val isCustomPointRanges = selectedHabitName in customPointRangesHabits
                    var showPointRangesDialog by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Custom Point Ranges", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            Text(
                                text = if (isCustomPointRanges) "Points based on value ranges"
                                       else "Standard point calculation",
                                color = if (isCustomPointRanges) Color(0xFFBB88FF) else Color(0xFF888888),
                                fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isCustomPointRanges,
                            onCheckedChange = { onToggleCustomPointRanges(selectedHabitName) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFBB88FF),
                                checkedTrackColor = Color(0xFF4A2A6A),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }

                    if (isCustomPointRanges) {
                        Spacer(modifier = Modifier.height(4.dp))
                        val currentRanges = customPointRanges[selectedHabitName] ?: listOf(com.example.tail.data.PointRange())
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                val rangeCountText = if (currentRanges.size == 7) "Point ranges (0-6)" else "Point ranges (0-${currentRanges.size - 1})"
                                Text(
                                    text = "  $rangeCountText",
                                    color = Color(0xFFAAAAAA), fontSize = 12.sp
                                )
                                val rangeSummary = currentRanges.mapIndexed { idx, range ->
                                    if (range.min == Int.MIN_VALUE && range.max == Int.MAX_VALUE) {
                                        "[$idx]: ∞"
                                    } else if (range.min == Int.MIN_VALUE) {
                                        "[$idx]: ≤${range.max}"
                                    } else if (range.max == Int.MAX_VALUE) {
                                        "[$idx]: ≥${range.min}"
                                    } else {
                                        "[$idx]: ${range.min}-${range.max}"
                                    }
                                }.joinToString(", ")
                                Text(
                                    text = rangeSummary,
                                    color = Color(0xFF888888), fontSize = 10.sp
                                )
                            }
                            Button(
                                onClick = { showPointRangesDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2A6A)),
                                modifier = Modifier.height(32.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                            ) {
                                Text("Edit", fontSize = 11.sp, color = Color(0xFFBB88FF))
                            }
                        }

                        if (showPointRangesDialog) {
                            PointRangesEditorDialog(
                                habitName = selectedHabitName,
                                currentRanges = currentRanges,
                                onSave = { ranges ->
                                    onSetCustomPointRanges(selectedHabitName, ranges)
                                    showPointRangesDialog = false
                                },
                                onDismiss = { showPointRangesDialog = false }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Timeless toggle ────────────────────────────────────
                    val isTimeless = selectedHabitName in timelessHabits
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Timeless", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            Text(
                                text = if (isTimeless) "No timestamp recorded by default" else "Timestamp recorded on increment",
                                color = Color(0xFF888888), fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isTimeless,
                            onCheckedChange = { onToggleTimeless(selectedHabitName) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF88CCFF),
                                checkedTrackColor = Color(0xFF003A5A),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Roll Forward toggle ────────────────────────────────────
                    val isRollForward = selectedHabitName in rollForwardHabits
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(text = "Roll Forward", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            Text(
                                text = if (isRollForward) "Auto-rolls to next day & fills past days" else "No auto-roll behavior",
                                color = Color(0xFF888888), fontSize = 10.sp
                            )
                        }
                        Switch(
                            checked = isRollForward,
                            onCheckedChange = { onToggleRollForward(selectedHabitName) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF88FF88),
                                checkedTrackColor = Color(0xFF1A4A1A),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
                    }

                    // ── Voice Trigger toggle ────────────────────────────────
                    if (voiceTriggerEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))

                        val isVoiceTrigger = selectedHabitName in voiceTriggerHabits
                        val currentTriggerWords = voiceTriggerWords[selectedHabitName] ?: emptySet()
                        var triggerWordsText by remember(selectedHabitName) {
                            mutableStateOf(currentTriggerWords.joinToString(", "))
                        }
                        var showVoiceTriggerInfo by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column {
                                    Text(text = "🎤 Voice Trigger", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                                    Text(
                                        text = if (isVoiceTrigger) "${currentTriggerWords.size} trigger word(s)"
                                               else "Say a word to increment",
                                        color = Color(0xFF888888), fontSize = 10.sp
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                IconButton(
                                    onClick = { showVoiceTriggerInfo = true },
                                    modifier = Modifier.size(20.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Info,
                                        contentDescription = "Voice Trigger setup info",
                                        tint = Color(0xFF6699CC),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            Switch(
                                checked = isVoiceTrigger,
                                onCheckedChange = { onToggleVoiceTrigger(selectedHabitName) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color(0xFF44BBFF),
                                    checkedTrackColor = Color(0xFF003355),
                                    uncheckedThumbColor = Color(0xFF888888),
                                    uncheckedTrackColor = Color(0xFF333333)
                                )
                            )
                        }

                        if (isVoiceTrigger) {
                            Spacer(modifier = Modifier.height(4.dp))

                            OutlinedTextField(
                                value = triggerWordsText,
                                onValueChange = { newText ->
                                    triggerWordsText = newText
                                    val words = newText.split(",")
                                        .map { it.trim().lowercase() }
                                        .filter { it.isNotEmpty() }
                                        .toSet()
                                    onSetVoiceTriggerWords(selectedHabitName, words)
                                },
                                label = { Text("Trigger words (comma-separated)", fontSize = 10.sp) },
                                singleLine = false,
                                maxLines = 3,
                                modifier = Modifier.fillMaxWidth(),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFF44BBFF),
                                    unfocusedTextColor = Color(0xFF44BBFF),
                                    focusedBorderColor = Color(0xFF44BBFF),
                                    unfocusedBorderColor = Color(0xFF225577)
                                ),
                                textStyle = TextStyle(fontSize = 12.sp)
                            )

                            // ── Increment amount ─────────────────────────────
                            Spacer(modifier = Modifier.height(4.dp))
                            val currentIncrement = voiceTriggerIncrements[selectedHabitName] ?: 1
                            var incrementText by remember(selectedHabitName) {
                                mutableStateOf(if (currentIncrement > 1) currentIncrement.toString() else "")
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "  Increment amount", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                    Text(
                                        text = if (currentIncrement > 1) "Adds $currentIncrement per trigger (if no number spoken)"
                                               else "Adds 1 per trigger (default)",
                                        color = Color(0xFF666666), fontSize = 10.sp
                                    )
                                }
                                OutlinedTextField(
                                    value = incrementText,
                                    onValueChange = { v ->
                                        incrementText = v.filter { it.isDigit() }
                                        val amount = incrementText.toIntOrNull() ?: 1
                                        onSetVoiceTriggerIncrement(selectedHabitName, amount)
                                    },
                                    placeholder = { Text("1", fontSize = 12.sp, color = Color(0xFF555577)) },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.width(80.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = Color(0xFF44BBFF),
                                        unfocusedTextColor = Color(0xFF44BBFF),
                                        focusedBorderColor = Color(0xFF44BBFF),
                                        unfocusedBorderColor = Color(0xFF225577)
                                    ),
                                    textStyle = TextStyle(fontSize = 13.sp, textAlign = TextAlign.Center)
                                )
                            }
                        }

                        // ── Use Subtypes Voice toggle ────────────────────────
                        if (isVoiceTrigger && selectedHabitName in subtypedHabits) {
                            Spacer(modifier = Modifier.height(4.dp))

                            val isVoiceSubtype = selectedHabitName in voiceSubtypeHabits

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(text = "🎤 Use Subtypes Voice", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                                    Text(
                                        text = if (isVoiceSubtype) "Hear subtype + number after trigger"
                                               else "Parse subtypes & numbers from voice",
                                        color = if (isVoiceSubtype) Color(0xFF66BB6A) else Color(0xFF888888),
                                        fontSize = 10.sp
                                    )
                                }
                                Switch(
                                    checked = isVoiceSubtype,
                                    onCheckedChange = { onToggleVoiceSubtype(selectedHabitName) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color(0xFF44BBFF),
                                        checkedTrackColor = Color(0xFF003355),
                                        uncheckedThumbColor = Color(0xFF888888),
                                        uncheckedTrackColor = Color(0xFF333333)
                                    )
                                )
                            }
                        }

                        if (showVoiceTriggerInfo) {
                            VoiceTriggerInfoDialog(onDismiss = { showVoiceTriggerInfo = false })
                        }
                    }

                    // ── App Association section ───────────────────────────────
                    // Long-pressing this habit in the grid opens associated apps.
                    // Single app → launches directly; multiple → shows a picker.
                    Spacer(modifier = Modifier.height(6.dp))
                    val currentAppAssociations = habitAppAssociations[selectedHabitName] ?: emptyList()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "📱 App Association", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                            Text(
                                text = if (currentAppAssociations.isEmpty()) "Long-press increments habit"
                                       else if (currentAppAssociations.size == 1)
                                           if (parseShortcutEntry(currentAppAssociations[0]) != null)
                                               "Long-press opens shortcut"
                                           else "Long-press opens app"
                                       else "Long-press shows ${currentAppAssociations.size} apps",
                                color = if (currentAppAssociations.isNotEmpty()) Color(0xFF66CCFF) else Color(0xFF888888),
                                fontSize = 10.sp
                            )
                        }
                        Button(
                            onClick = { onAddAppAssociation(selectedHabitName) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("📱", fontSize = 11.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Add App", fontSize = 11.sp, color = Color(0xFF66CCFF))
                        }
                    }
                    // Show the list of associated apps with reorder/remove controls
                    if (currentAppAssociations.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        currentAppAssociations.forEachIndexed { idx, pkg ->
                            AssociatedAppRow(
                                habitName = selectedHabitName,
                                packageName = pkg,
                                index = idx,
                                totalCount = currentAppAssociations.size,
                                onRemove = { onRemoveAppAssociation(selectedHabitName, pkg) },
                                onMoveUp = { onMoveAppAssociation(selectedHabitName, idx, idx - 1) },
                                onMoveDown = { onMoveAppAssociation(selectedHabitName, idx, idx + 1) }
                            )
                        }
                    }

                    // ── Use Widget (app-triggered bubble) ─────────────────────
                    // When enabled with a trigger app selected, the floating
                    // bubble automatically appears over that app.
                    Spacer(modifier = Modifier.height(6.dp))
                    WidgetTriggerSection(
                        habitName = selectedHabitName,
                        widgetTriggerHabits = widgetTriggerHabits,
                        widgetTriggerApps = widgetTriggerApps,
                        onToggleWidgetTrigger = onToggleWidgetTrigger,
                        onSetWidgetTriggerApp = onSetWidgetTriggerApp,
                        hasUsageAccess = hasUsageAccess,
                        onRequestUsageAccess = onRequestUsageAccess
                    )

                    // ── PC Widget (desktop bubble widget) ─────────────────────
                    // Rendered next to the Use Widget toggle rather than inside
                    // the special-habit-types drawer.
                    pcWidgetContent()

                    // ── Daily ask (scheduled notification) ─────────────────
                    // Asks about this habit at the same time every day via a
                    // system notification + in-app notification + one-time
                    // flash on the next app open.
                    Spacer(modifier = Modifier.height(6.dp))
                    HabitScheduleSection(
                        habitName = selectedHabitName,
                        scheduleTimes = habitScheduleTimes,
                        onSetScheduleTime = onSetHabitScheduleTime
                    )

                    // ── Special habit types (collapsible) ──────────────────────
                    // Meal, Weights, Chess.com, Media, Garmin, GitHub and Movie Bridge —
                    // the integration-backed habit types, grouped behind one
                    // collapsible header so the edit panel stays tidy. Extracted
                    // to its own composable to keep EditModeControlBar under the
                    // JVM method-size limit.
                    val selHabitName = selectedHabitName
                    SpecialHabitTypesSection(
                        mealContent = {
                            MealToggleSection(
                                habitName = selHabitName,
                                isMeal = selHabitName in mealHabits,
                                onToggleMeal = onToggleMeal
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            CameraToggleSection(
                                habitName = selHabitName,
                                isCamera = selHabitName in cameraHabits,
                                onToggleCamera = onToggleCamera
                            )
                        },
                        weightsContent = {
                            WeightsToggleSection(
                                habitName = selHabitName,
                                isWeights = selHabitName in weightsHabits,
                                onToggleWeights = onToggleWeights
                            )
                        },
                        chessComContent = {
                            if (chessComEnabled) {
                                ChessComLinkToggle(
                                    habitName = selHabitName,
                                    links = chessComHabitLinks,
                                    onSetLink = { type -> onSetChessComLink(selHabitName, type) }
                                )
                            }
                        },
                        mediaContent = {
                            MediaSection(
                                habitName = selHabitName,
                                mediaHabits = mediaHabits,
                                mediaApps = mediaApps,
                                onToggleMedia = onToggleMedia,
                                onSetMediaApp = onSetMediaApp,
                                hasNotificationAccess = hasNotificationAccess,
                                onRequestNotificationAccess = onRequestNotificationAccess,
                                todayShows = mediaTodayShows,
                                onLoadShows = onLoadMediaShows,
                                onRemoveShow = onRemoveMediaShow
                            )
                        },
                        garminContent = {
                            if (garminEnabled) {
                                GarminLinkToggleSection(
                                    selectedHabitName = selHabitName,
                                    garminHabitLinks = garminHabitLinks,
                                    onSetGarminLink = onSetGarminLink
                                )
                            }
                        },
                        githubContent = githubContent,
                        movieBridgeContent = movieBridgeContent
                    )

                    // ── Advanced section (invert, restore-from-backup, etc.) ──
                    AdvancedSection(
                        habitName = selectedHabitName,
                        onInvertHabit = onInvertHabit,
                        onGetInvertPreview = onGetInvertPreview,
                        onRestoreFromBackup = onRestoreFromBackup
                    )
                }
            }
        }
    }
}

/**
 * Edit-mode header for the selected habit: name plus the inline
 * [-]/count/[+] adjuster, with the set-count and roll-forward
 * confirmation dialogs.
 *
 * Extracted from EditModeControlBar to keep it under the JVM 64KB
 * method-size limit (hit a MethodTooLargeException after adding the
 * minutes-toggle parameters).
 */


@Composable
internal fun EditModeHabitHeaderRow(
    selectedHabitName: String?,
    selectedHabitTodayCount: Int,
    selectedHabitRawTodayCount: Int,
    rollForwardHabits: Set<String>,
    rollForwardManualDates: Map<String, Set<String>>,
    selectedDate: java.time.LocalDate,
    onSetCount: (String, Int) -> Unit,
    onSetCountWithRollForward: (String, Int, java.time.LocalDate) -> Unit
) {
    var showSetCountDialog by remember { mutableStateOf(false) }
    var pendingCountDelta by remember { mutableStateOf(0) } // 0 = no pending change, 1 = increment, -1 = decrement

    // Header row: name + inline count adjuster
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = selectedHabitName ?: "",
            color = Color(0xFFFFAA00),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        if (selectedHabitName != null) {
            // Count adjuster: [−] points [+]
            // Shows the divided points value; for divider habits the raw value
            // is editable in the "true value" field below.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "today:",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
                Button(
                    onClick = {
                        // Check if this is a roll forward habit and we're viewing a past date
                        if (selectedHabitName in rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
                            // Set pending delta and show roll forward confirmation dialog
                            pendingCountDelta = -1
                            showSetCountDialog = true
                        } else {
                            // Normal decrement without roll forward
                            onSetCount(selectedHabitName, selectedHabitRawTodayCount - 1)
                        }
                    },
                    enabled = selectedHabitRawTodayCount > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3A1A00),
                        disabledContainerColor = Color(0xFF1A1A1A)
                    ),
                    modifier = Modifier.size(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("−", fontSize = 14.sp, color = if (selectedHabitRawTodayCount > 0) Color(0xFFFFAA00) else Color(0xFF555555))
                }
                Text(
                    text = selectedHabitTodayCount.toString(),
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .width(28.dp)
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) { showSetCountDialog = true },
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = {
                        // Check if this is a roll forward habit and we're viewing a past date
                        if (selectedHabitName in rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
                            // Set pending delta and show roll forward confirmation dialog
                            pendingCountDelta = 1
                            showSetCountDialog = true
                        } else {
                            // Normal increment without roll forward
                            onSetCount(selectedHabitName, selectedHabitRawTodayCount + 1)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A00)),
                    modifier = Modifier.size(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text("+", fontSize = 14.sp, color = Color(0xFF88FF88))
                }
            }
        }
        // Set-count dialog — opened by tapping the count number or +/- buttons
        if (showSetCountDialog && selectedHabitName != null) {
            // Check if this is a roll forward habit and we're viewing a past date
            if (selectedHabitName in rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
                // Find the next manual date
                val nextManualDate = rollForwardManualDates[selectedHabitName]?.mapNotNull { dateStr ->
                    com.example.tail.data.parseDate(dateStr)
                }?.sorted()?.firstOrNull { date -> date > selectedDate }

                val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

                // Show roll forward confirmation dialog
                RollForwardConfirmDialog(
                    habitName = selectedHabitName,
                    actionType = "increment",
                    startDate = selectedDate,
                    initialEndDate = endDate,
                    onConfirm = { confirmedEndDate ->
                        // Use pending delta if available, otherwise use current count
                        val newCount = if (pendingCountDelta != 0) {
                            selectedHabitRawTodayCount + pendingCountDelta
                        } else {
                            selectedHabitRawTodayCount
                        }
                        onSetCountWithRollForward(selectedHabitName, newCount, confirmedEndDate)
                        showSetCountDialog = false
                        pendingCountDelta = 0
                    },
                    onDismiss = {
                        showSetCountDialog = false
                        pendingCountDelta = 0
                    }
                )
            } else {
                // Normal set count dialog without roll forward
                // Use pending delta if available, otherwise show the dialog
                if (pendingCountDelta != 0) {
                    // Apply the delta directly without showing the dialog
                    onSetCount(selectedHabitName, selectedHabitRawTodayCount + pendingCountDelta)
                    showSetCountDialog = false
                    pendingCountDelta = 0
                } else {
                    // Show the normal set count dialog
                    SetCountDialog(
                        habitName = selectedHabitName,
                        currentCount = selectedHabitRawTodayCount,
                        onConfirm = { newCount ->
                            onSetCount(selectedHabitName, newCount)
                            showSetCountDialog = false
                        },
                        onDismiss = { showSetCountDialog = false }
                    )
                }
            }
        }
    }
}

/**
 * Edit-mode summary of the selected date's weights slots for a weights
 * habit, shown at the top of the settings. Tapping opens the day editor
 * ([WeightsDayEditorDialog]) which overwrites the day's aggregated
 * machine/free weight + reps values.
 */


@Composable
internal fun EditModeWeightsSummarySection(
    habitName: String,
    values: com.example.tail.data.WeightsDayValues,
    unit: String,
    /** Previously used exercise/machine names on this habit, most recent first. */
    recentExercises: List<String> = emptyList(),
    onSetValues: (com.example.tail.data.WeightsDayValues, String) -> Unit,
    /** Called when the user deletes ALL weights data for the selected day. */
    onDeleteDay: () -> Unit
) {
    var showEditor by remember { mutableStateOf(false) }

    fun weightLabel(grams: Int): String {
        if (grams <= 0) return "—"
        val tenths = com.example.tail.data.gramsToDisplayTenths(grams, unit)
        return if (tenths % 10 == 0) "${tenths / 10} $unit"
        else "${com.example.tail.data.formatWeightTenths(tenths)} $unit"
    }
    val summary = if (!values.hasAny) {
        "No weights logged for this day"
    } else buildString {
        if (values.machineWeightGrams > 0 || values.machineReps > 0) {
            if (isNotEmpty()) append(" · ")
            append("Machine ${weightLabel(values.machineWeightGrams)} × ${values.machineReps} reps")
        }
        if (values.freeWeightGrams > 0 || values.freeReps > 0) {
            if (isNotEmpty()) append(" · ")
            append("Free ${weightLabel(values.freeWeightGrams)} × ${values.freeReps} reps")
        }
    }

    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showEditor = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "🏋️", fontSize = 12.sp)
        Spacer(modifier = Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Today's weights — tap to edit",
                color = Color(0xFF88AACC),
                fontSize = 10.sp
            )
            Text(text = summary, color = Color(0xFFCCCCCC), fontSize = 11.sp)
        }
    }

    if (showEditor) {
        WeightsDayEditorDialog(
            habitName = habitName,
            initial = values,
            recentExercises = recentExercises,
            defaultUnit = unit,
            onConfirm = { newValues, exerciseName ->
                onSetValues(newValues, exerciseName)
                showEditor = false
            },
            onDelete = {
                onDeleteDay()
                showEditor = false
            },
            onDismiss = { showEditor = false }
        )
    }
}

/**
 * Edit-mode action rows for the selected habit: the Move button with
 * the move-to-screen dropdown, and the Delete / Icon / Rename buttons
 * with the rename dialog.
 *
 * Extracted from EditModeControlBar to keep it under the JVM 64KB
 * method-size limit (hit a MethodTooLargeException after adding the
 * minutes-toggle parameters).
 */


@Composable
internal fun EditModeHabitActionRows(
    selectedHabitName: String?,
    onDeleteHabit: (String) -> Unit,
    onChangeIcon: (String) -> Unit,
    onRenameHabit: (String, String) -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }

    // (Reordering moved to long-press-drag on the grid and the screen-tab
    // bar — the old ↕ Move / → Screen controls are gone.)

    Spacer(modifier = Modifier.height(6.dp))

    // ACTIONS section (Delete + Change Icon)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (selectedHabitName != null) {
            Button(
                onClick = { onDeleteHabit(selectedHabitName) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A0000)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("🗑 Delete", fontSize = 11.sp, color = Color(0xFFFF8888))
            }
            Button(
                onClick = { onChangeIcon(selectedHabitName) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003A3A)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("🎨 Icon", fontSize = 11.sp, color = Color(0xFF88FFFF))
            }
            Button(
                onClick = { showRenameDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A00)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("✎ Rename", fontSize = 11.sp, color = Color(0xFFFFCC44))
            }
        }
    }

    // Rename habit dialog
    if (showRenameDialog && selectedHabitName != null) {
        RenameHabitDialog(
            currentName = selectedHabitName,
            onConfirm = { newName ->
                onRenameHabit(selectedHabitName, newName)
                showRenameDialog = false
            },
            onDismiss = { showRenameDialog = false }
        )
    }
}

/**
 * Edit-mode rows for the selected habit's note, "1 max" daily cap, custom
 * input increment amounts, and text-input features (options sub-toggle,
 * sharable text, log-file picker).
 *
 * Extracted from EditModeControlBar to keep it under the JVM 64KB
 * method-size limit (hit a MethodTooLargeException after adding the
 * long-press URL-app parameters).
 */


@Composable
internal fun HabitInputModesSection(
    selectedHabitName: String,
    habitNotes: Map<String, String>,
    onSetHabitNote: (String, String) -> Unit,
    maxOneHabits: Set<String>,
    onToggleMaxOne: (String) -> Unit,
    invertedBinaryHabits: Set<String> = emptySet(),
    onToggleInvertedBinary: (String) -> Unit = {},
    customInputHabits: Set<String>,
    customInputAmounts: Map<String, List<Int>>,
    onToggleCustomInput: (String) -> Unit,
    onSetCustomInputAmounts: (String, List<Int>) -> Unit,
    textInputHabits: Set<String>,
    textInputOptionsHabits: Set<String>,
    sharableTextHabits: Set<String>,
    textInputFileUris: Map<String, String>,
    onToggleTextInput: (String) -> Unit,
    onToggleTextInputOptions: (String) -> Unit,
    onToggleSharableText: (String) -> Unit,
    onPickTextInputFile: (String) -> Unit,
    onCreateTextInputFile: (String) -> Unit = {}
) {
    // ── Note section ───────────────────────────────────────────
    val currentNote = habitNotes[selectedHabitName] ?: ""
    var showNoteDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Note", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (currentNote.isNotEmpty()) "Has note" else "No note",
                color = Color(0xFF888888), fontSize = 10.sp
            )
        }
        Button(
            onClick = { showNoteDialog = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A00)),
            modifier = Modifier.height(32.dp)
        ) {
            Text("Edit", fontSize = 11.sp, color = Color(0xFFFFCC44))
        }
    }

    if (showNoteDialog) {
        HabitNoteDialog(
            habitName = selectedHabitName,
            initialNote = currentNote,
            onConfirm = { newNote ->
                onSetHabitNote(selectedHabitName, newNote)
                showNoteDialog = false
            },
            onDismiss = { showNoteDialog = false }
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    // 1 max toggle
    val isMaxOne = selectedHabitName in maxOneHabits
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "1 max", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isMaxOne) "Capped at 1 per day (binary)" else "No daily cap",
                color = Color(0xFF888888), fontSize = 10.sp
            )
        }
        Switch(
            checked = isMaxOne,
            onCheckedChange = { onToggleMaxOne(selectedHabitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF88FF88),
                checkedTrackColor = Color(0xFF1A4A1A),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    // Inverted binary toggle (e.g. coffee: tap when done, earn points when NOT done)
    val isInvertedBinary = selectedHabitName in invertedBinaryHabits
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "Inverted binary ⊘", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isInvertedBinary) "Skipped day = +1 point & streak; done day (red) breaks it"
                else "Normal points & streaks on done days",
                color = Color(0xFF888888), fontSize = 10.sp
            )
        }
        Switch(
            checked = isInvertedBinary,
            onCheckedChange = { onToggleInvertedBinary(selectedHabitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFE0E0E0),
                checkedTrackColor = Color(0xFF444444),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    // Custom input toggle
    val isCustomInput = selectedHabitName in customInputHabits
    var showIncrementAmountsDialog by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "Custom input", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isCustomInput) "Shows number picker on tap" else "Simple +1 on tap",
                color = Color(0xFF888888), fontSize = 10.sp
            )
        }
        Switch(
            checked = isCustomInput,
            onCheckedChange = { onToggleCustomInput(selectedHabitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFFFFAA00),
                checkedTrackColor = Color(0xFF5A3A00),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    // "Set increment amounts" button — only shown when custom input is on
    if (isCustomInput) {
        Spacer(modifier = Modifier.height(4.dp))
        val currentAmounts = customInputAmounts[selectedHabitName]
            ?: com.example.tail.data.DEFAULT_CUSTOM_INPUT_AMOUNTS
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "  Increment amounts",
                    color = Color(0xFFAAAAAA), fontSize = 12.sp
                )
                Text(
                    text = currentAmounts.joinToString(", "),
                    color = Color(0xFF888888), fontSize = 10.sp
                )
            }
            Button(
                onClick = { showIncrementAmountsDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2800)),
                modifier = Modifier.height(32.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text("Edit", fontSize = 11.sp, color = Color(0xFFFFCC44))
            }
        }

        if (showIncrementAmountsDialog) {
            IncrementAmountsEditorDialog(
                habitName = selectedHabitName,
                currentAmounts = currentAmounts,
                onSave = { amounts ->
                    onSetCustomInputAmounts(selectedHabitName, amounts)
                    showIncrementAmountsDialog = false
                },
                onDismiss = { showIncrementAmountsDialog = false }
            )
        }
    }

    Spacer(modifier = Modifier.height(6.dp))

    // Text input toggle
    val isTextInput = selectedHabitName in textInputHabits
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "Text input", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isTextInput) "Shows text entry on tap" else "No text entry",
                color = Color(0xFF888888), fontSize = 10.sp
            )
        }
        Switch(
            checked = isTextInput,
            onCheckedChange = { onToggleTextInput(selectedHabitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF44AAFF),
                checkedTrackColor = Color(0xFF003A5A),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    if (isTextInput) {
        Spacer(modifier = Modifier.height(4.dp))

        // Options sub-toggle
        val isOptions = selectedHabitName in textInputOptionsHabits
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "  Options", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                Text(
                    text = if (isOptions) "Shows past entries as choices" else "Free-text only",
                    color = Color(0xFF666666), fontSize = 10.sp
                )
            }
            Switch(
                checked = isOptions,
                onCheckedChange = { onToggleTextInputOptions(selectedHabitName) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF88FFCC),
                    checkedTrackColor = Color(0xFF004433),
                    uncheckedThumbColor = Color(0xFF666666),
                    uncheckedTrackColor = Color(0xFF2A2A2A)
                )
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Sharable sub-toggle — rendered by [SharableTextToggle],
        // extracted to keep EditModeControlBar under the JVM method-size limit
        SharableTextToggle(
            isSharable = selectedHabitName in sharableTextHabits,
            onToggle = { onToggleSharableText(selectedHabitName) }
        )

        Spacer(modifier = Modifier.height(4.dp))

        // File picker row
        val hasFile = textInputFileUris.containsKey(selectedHabitName)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "  Text log file", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                Text(
                    text = if (hasFile) "✓ File selected" else "⚠ No file selected",
                    color = if (hasFile) Color(0xFF88FF88) else Color(0xFFFF8844),
                    fontSize = 10.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { onPickTextInputFile(selectedHabitName) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003A5A)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = "Pick text log file",
                        tint = Color(0xFF88CCFF),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (hasFile) "Change" else "Select",
                        fontSize = 11.sp,
                        color = Color(0xFF88CCFF)
                    )
                }
                if (!hasFile) {
                    // Create a new empty log file in a user-picked directory;
                    // the app names it after the habit and associates it.
                    Button(
                        onClick = { onCreateTextInputFile(selectedHabitName) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A4A2A)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Create text log file",
                            tint = Color(0xFF88FF88),
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create", fontSize = 11.sp, color = Color(0xFF88FF88))
                    }
                }
            }
        }
    }
}


// ── Value editor row ────────────────────────────────────────────────────────

/**
 * Edit-mode row for setting a habit's "true value" for the selected date.
 *
 * - Multi-value habits — timer habits (widget trigger app configured) and
 *   divider habits with the minutes value enabled — track TWO values:
 *   sessions (the habit's own slot) and minutes (the first-class
 *   `minutes:<habit>` slot). They get a dropdown that picks which value to
 *   edit; the default selection is the habit's PRIMARY value (per
 *   [widgetTimerMinutesPrimary]). A minutes input is NEVER shown stacked
 *   above the true-value input — the dropdown replaces both.
 * - Garmin-linked habits show a read-only label with the derived Garmin
 *   metric value.
 * - Media habits edit their MINUTES (the `minutes:<habit>` slot the media
 *   tracker auto-records into), labelled "minutes:".
 * - Other single-value habits (e.g. divider habits) get a plain label naming
 *   the value, with an editable field.
 *
 * Extracted from EditModeControlBar to keep it under the JVM method-size limit.
 */
