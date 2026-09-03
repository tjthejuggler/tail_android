package com.example.tail.ui

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
import com.example.tail.R
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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

// Sentinel used to track which habit's text-input dialog is open
private data class TextInputDialogState(
    val habit: Habit,
    val showOptions: Boolean,
    val options: List<String>,
    val todayEntries: List<Pair<String, String>> = emptyList(),
    /** Pre-filled text (e.g. from a movie bridge suggestion). Empty by default. */
    val suggestedText: String = "",
    /** Label shown above the text field when suggestedText is non-empty. */
    val suggestionLabel: String = "",
    /** Suggested watch-length in minutes (movie bridge); null = no length section. */
    val suggestedMinutes: Int? = null,
    /** True while the movie suggestion is still resolving (cache → bridge). */
    val suggestionLoading: Boolean = false,
    /** Last watched movies (newest first) for the quick picker. */
    val recentMovies: List<BridgeMovie> = emptyList()
)

// Grid is 8 columns × 10 rows = 80 cells
internal const val GRID_COLUMNS = 8
internal const val TOTAL_CELLS = 80

// How long the finger must hover a screen tab (while dragging a habit in
// edit mode) before the drag switches over to that screen.
internal const val TAB_DRAG_SWITCH_DWELL_MS = 550L

// ── Idle shimmer tuning ───────────────────────────────────────────────────────
// After a random quiet gap (5–15 s) without any interaction, a barely-visible
// brightness wave rolls across the habit squares in a randomly chosen
// direction — from any corner, across from any side, outward from the
// center, or inward from the edges. The opposite direction immediately
// follows with no gap, so the pair reads as one long "there and back"
// shimmer; then a new random direction is chosen after another random gap.
internal const val IDLE_SHIMMER_GAP_MIN_MS = 5_000L   // min quiet time before a wave starts
internal const val IDLE_SHIMMER_GAP_MAX_MS = 15_000L  // max quiet time before a wave starts
internal const val IDLE_SHIMMER_SWEEP_MS = 1_300      // duration of one leg (forward or return) of a pair
internal const val IDLE_SHIMMER_BAND = 0.22f          // wave width (fraction of the sweep span)
internal const val IDLE_SHIMMER_MAX_ALPHA = 0.07f     // peak brightness — deliberately very slight

internal const val GRID_ROWS = TOTAL_CELLS / GRID_COLUMNS

/** Grid diagonal span in cells: (rows − 1) + (columns − 1). */
internal const val GRID_DIAGONAL_SPAN = (GRID_ROWS - 1) + (GRID_COLUMNS - 1)


/** Distance from the grid center to the farthest corner, in cells. */
internal val GRID_CENTER_MAX_DISTANCE = run {
    val dr = (GRID_ROWS - 1) / 2f
    val dc = (GRID_COLUMNS - 1) / 2f
    kotlin.math.sqrt(dr * dr + dc * dc)
}

/**
 * Directions the idle shimmer can travel. Each direction maps a cell's
 * (row, col) to a normalized sweep coordinate [u] in 0..1, where u = 1 marks
 * where the wave front enters (lit first) and u = 0 where it exits
 * (lit last). [opposite] is the return leg that always immediately follows
 * a sweep, retracing it back to where it came from.
 */
internal enum class ShimmerDirection {
    BOTTOM_RIGHT_TO_TOP_LEFT,
    TOP_LEFT_TO_BOTTOM_RIGHT,
    BOTTOM_LEFT_TO_TOP_RIGHT,
    TOP_RIGHT_TO_BOTTOM_LEFT,
    LEFT_TO_RIGHT,
    RIGHT_TO_LEFT,
    TOP_TO_BOTTOM,
    BOTTOM_TO_TOP,
    CENTER_OUT,
    EDGES_IN;

    /** The direction whose wave retraces this one in reverse. */
    val opposite: ShimmerDirection
        get() = when (this) {
            BOTTOM_RIGHT_TO_TOP_LEFT -> TOP_LEFT_TO_BOTTOM_RIGHT
            TOP_LEFT_TO_BOTTOM_RIGHT -> BOTTOM_RIGHT_TO_TOP_LEFT
            BOTTOM_LEFT_TO_TOP_RIGHT -> TOP_RIGHT_TO_BOTTOM_LEFT
            TOP_RIGHT_TO_BOTTOM_LEFT -> BOTTOM_LEFT_TO_TOP_RIGHT
            LEFT_TO_RIGHT -> RIGHT_TO_LEFT
            RIGHT_TO_LEFT -> LEFT_TO_RIGHT
            TOP_TO_BOTTOM -> BOTTOM_TO_TOP
            BOTTOM_TO_TOP -> TOP_TO_BOTTOM
            CENTER_OUT -> EDGES_IN
            EDGES_IN -> CENTER_OUT
        }

    /** Normalized sweep coordinate of a cell: 1 = lit first, 0 = lit last. */
    fun u(row: Int, col: Int): Float = when (this) {
        BOTTOM_RIGHT_TO_TOP_LEFT -> (row + col).toFloat() / GRID_DIAGONAL_SPAN
        TOP_LEFT_TO_BOTTOM_RIGHT -> 1f - (row + col).toFloat() / GRID_DIAGONAL_SPAN
        BOTTOM_LEFT_TO_TOP_RIGHT -> (row + (GRID_COLUMNS - 1 - col)).toFloat() / GRID_DIAGONAL_SPAN
        TOP_RIGHT_TO_BOTTOM_LEFT -> 1f - (row + (GRID_COLUMNS - 1 - col)).toFloat() / GRID_DIAGONAL_SPAN
        LEFT_TO_RIGHT -> 1f - col.toFloat() / (GRID_COLUMNS - 1)
        RIGHT_TO_LEFT -> col.toFloat() / (GRID_COLUMNS - 1)
        TOP_TO_BOTTOM -> 1f - row.toFloat() / (GRID_ROWS - 1)
        BOTTOM_TO_TOP -> row.toFloat() / (GRID_ROWS - 1)
        CENTER_OUT -> 1f - centerDistance(row, col)
        EDGES_IN -> centerDistance(row, col)
    }

    /** Normalized distance from the grid center (0 = center, 1 = corners). */
    internal fun centerDistance(row: Int, col: Int): Float {
        val dr = row - (GRID_ROWS - 1) / 2f
        val dc = col - (GRID_COLUMNS - 1) / 2f
        return kotlin.math.sqrt(dr * dr + dc * dc) / GRID_CENTER_MAX_DISTANCE
    }
}

/**
 * Per-cell shimmer alpha for sweep progress [t] (0..1) at normalized sweep
 * position [u] (1 = wave enters here, 0 = wave exits here).
 */


internal fun idleShimmerAlpha(t: Float, u: Float): Float {
    val front = (1f + IDLE_SHIMMER_BAND) - t * (1f + 2f * IDLE_SHIMMER_BAND)
    val proximity = 1f - kotlin.math.abs(u - front) / IDLE_SHIMMER_BAND
    return proximity.coerceIn(0f, 1f) * IDLE_SHIMMER_MAX_ALPHA
}


internal val DISPLAY_DATE_FMT = DateTimeFormatter.ofPattern("EEE MMM d")

/**
 * Bundles secondary-value-related settings to reduce [EditModeControlBar] parameter count
 * (avoids JVM MethodTooLargeException).
 */


data class SecondaryValueSettings(
    val habits: Set<String> = emptySet(),
    val fallbackHabits: Set<String> = emptySet(),
    val onToggleSecondaryValue: (String) -> Unit = {},
    val onToggleSecondaryValueFallback: (String) -> Unit = {}
)

/**
 * Main screen: 8×10 habit grid with top bar actions and day navigation.
 */


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitGridScreen(
    viewModel: HabitViewModel,
    adviceViewModel: AdviceViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToMap: () -> Unit = {},
    onNavigateToAppStats: () -> Unit = {}
) {
    val habits by viewModel.habits.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val loadingMetrics by viewModel.loadingMetrics.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val selectedDateLocation by viewModel.selectedDateLocation.collectAsState()
    val editMode by viewModel.editMode.collectAsState()
    val graphMode by viewModel.graphMode.collectAsState()
    val scheduleMode by viewModel.scheduleMode.collectAsState()
    val graphSelectedHabits by viewModel.graphSelectedHabits.collectAsState()
    val selectedEditIndex by viewModel.selectedEditIndex.collectAsState()
    val movePendingSourceIndex by viewModel.movePendingSourceIndex.collectAsState()
    val habitScreens by viewModel.habitScreens.collectAsState()
    val activeScreenIndex by viewModel.activeScreenIndex.collectAsState()

    // Screen-switch feedback: a brief haptic tick so the tab press feels
    // acknowledged. The habit squares themselves swap instantly (all screens'
    // lists are pre-warmed in the ViewModel's screenHabitCache).
    val hapticFeedback = LocalHapticFeedback.current
    var screenSwitchInitialized by remember { mutableStateOf(false) }
    LaunchedEffect(activeScreenIndex) {
        if (!screenSwitchInitialized) {
            screenSwitchInitialized = true
        } else {
            hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }

    // ── Edit-mode drag-to-reorder ──────────────────────────────────────────────
    // Long-pressing a habit cell in edit mode lifts it into a drag: the grid
    // live-previews the destination (displaced habits shift right to make
    // room and snap back when the finger moves away), hovering a screen tab
    // for a beat switches screens mid-drag, and only an actual drop commits
    // the move. A cancelled or missed drop reverts everything. All gesture
    // bookkeeping lives in HabitDragState / Modifier.habitDragGesture below
    // so this (already huge) composable stays lean.
    val dragState = remember { HabitDragState() }
    val gridDragState = rememberLazyGridState()
    val dragScope = rememberCoroutineScope()
    val tabRowScrollState = rememberScrollState()
    // The gesture callbacks read these holders so they always see the
    // CURRENT values even when the screen switches mid-drag.
    val currentHabits = rememberUpdatedState(habits)
    val currentActiveScreen = rememberUpdatedState(activeScreenIndex)
    val currentScreenCount = rememberUpdatedState(habitScreens.size)

    // Leaving edit mode must never strand a drag (a removed pointerInput
    // cancels its coroutine without firing onDragCancel).
    LaunchedEffect(editMode) {
        if (!editMode) dragState.reset()
    }

    // Reveal-on-tap: the edit drawer and the graph panel sit below the grid
    // and shrink its viewport when they appear/grow, which can push the
    // tapped habit out from under the finger. Every qualifying tap bumps
    // revealNonce; the effect then slides the grid just enough to bring
    // that cell back into view — bottom-aligned when partially cut off,
    // scrolled into view when fully outside the shrunken viewport.
    var revealCellIndex by remember { mutableIntStateOf(-1) }
    var revealNonce by remember { mutableIntStateOf(0) }
    LaunchedEffect(revealNonce) {
        if (revealNonce == 0 || revealCellIndex < 0) return@LaunchedEffect
        // Let the drawer's/panel's first layout pass land before measuring.
        withFrameNanos { }
        withFrameNanos { }
        val info = gridDragState.layoutInfo
        if (revealCellIndex >= info.totalItemsCount) return@LaunchedEffect
        if (info.visibleItemsInfo.none { it.index == revealCellIndex }) {
            // Fully outside the viewport (the drawer/panel pushed it out) —
            // slide it into view first.
            gridDragState.animateScrollToItem(revealCellIndex)
        }
        // Fine-tune: nudge up only if the cell is still cut off at the bottom.
        val cell = gridDragState.layoutInfo.visibleItemsInfo
            .firstOrNull { it.index == revealCellIndex } ?: return@LaunchedEffect
        val overflow = cell.offset.y + cell.size.height -
            gridDragState.layoutInfo.viewportEndOffset
        if (overflow > 0) gridDragState.animateScrollBy(overflow.toFloat())
    }

    // Display list for the grid: the real habits, or the live drag preview.
    val displayHabits = computeDragDisplayHabits(habits, dragState, activeScreenIndex)

    val garminMonthlyData by viewModel.garminMonthlyData.collectAsState()
    val githubSyncStatus by viewModel.githubSyncStatus.collectAsState()
    val highlightedHabit by viewModel.highlightedHabit.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val pendingNotifications = notifications.size
    val context = LocalContext.current

    // Background AI icon generation: habits with a generation in flight show a
    // spinner on their tile, and one-shot messages (started/applied/failed)
    // surface as toasts — so the user can leave the icon picker anytime.
    val aiIconPendingHabits by viewModel.aiIconPendingHabits.collectAsState()
    LaunchedEffect(viewModel.aiIconMessages) {
        viewModel.aiIconMessages.collect { message ->
            android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    // ── Idle shimmer ─────────────────────────────────────────────────────────
    // After a random 5–15 s gap without any pointer activity anywhere on the
    // screen, a barely-visible brightness wave rolls across the habit
    // squares in a randomly chosen direction (from any corner, across from
    // any side, outward from the center, or inward from the edges). The
    // opposite direction immediately follows with no gap, so the pair reads
    // as one long "there and back" shimmer; a new random direction is then
    // chosen after another random gap. Any interaction stops the wave
    // immediately and restarts the idle timer. The interaction counter is
    // only read inside snapshotFlow and the sweep/direction values only
    // inside draw lambdas, so neither the pointer traffic nor the animation
    // recomposes the grid.
    val shimmerInteractionGen = remember { mutableIntStateOf(0) }
    val shimmerSweep = remember { Animatable(0f) }
    val shimmerDirection = remember { mutableStateOf(ShimmerDirection.BOTTOM_RIGHT_TO_TOP_LEFT) }
    LaunchedEffect(Unit) {
        snapshotFlow { shimmerInteractionGen.intValue }.collectLatest {
            shimmerSweep.snapTo(0f)
            delay(kotlin.random.Random.nextLong(IDLE_SHIMMER_GAP_MIN_MS, IDLE_SHIMMER_GAP_MAX_MS + 1))
            while (true) {
                val forward = ShimmerDirection.entries.random()
                // Forward leg — sweep in the randomly chosen direction.
                shimmerDirection.value = forward
                shimmerSweep.snapTo(0f)
                shimmerSweep.animateTo(1f, tween(IDLE_SHIMMER_SWEEP_MS, easing = LinearEasing))
                // Return leg — the opposite wave follows immediately with no
                // gap, retracing the sweep back to where it came from.
                shimmerDirection.value = forward.opposite
                shimmerSweep.snapTo(0f)
                shimmerSweep.animateTo(1f, tween(IDLE_SHIMMER_SWEEP_MS, easing = LinearEasing))
                // Quiet period before the next random direction is chosen.
                delay(kotlin.random.Random.nextLong(IDLE_SHIMMER_GAP_MIN_MS, IDLE_SHIMMER_GAP_MAX_MS + 1))
            }
        }
    }

    val today = LocalDate.now()
    val isToday = selectedDate == today

    // ── Location permission ───────────────────────────────────────────────────
    val locationPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.refreshTodayLocation()
    }
    // Request permission once on first composition if not yet granted
    LaunchedEffect(Unit) {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            locationPermLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    // Detect landscape orientation
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Programmatic orientation control: allow landscape when graph mode is
    // active with at least one habit selected, and on the schedule screen
    // (the wide timeline benefits from the extra width); otherwise lock to
    // portrait.
    // NOTE: We do NOT force portrait in onDispose here — when navigating to
    // MapScreen, MapScreen owns orientation (landscape) and resetting on
    // dispose would race with MapScreen's DisposableEffect. Each destination
    // composable that cares about orientation sets it on entry.
    val allowLandscape = scheduleMode || (graphMode && graphSelectedHabits.isNotEmpty())
    val activity = context as? Activity
    LaunchedEffect(allowLandscape) {
        activity?.requestedOrientation = if (allowLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    // Schedule timeline refresh counter — bumped when the timestamp editor
    // (opened from a schedule event) closes, so the timeline reloads.
    var scheduleRefresh by remember { mutableIntStateOf(0) }

    // Global search dialog state (query/filters/results live in the ViewModel,
    // so closing the dialog preserves its exact state for the next open)
    var showSearchDialog by remember { mutableStateOf(false) }

    // In-app notification center dialog state
    var showNotificationsDialog by remember { mutableStateOf(false) }

    // Deep links from the tier-bar widget: open the habit grid straight
    // into the notifications popup, and/or switch to a touch-zone's tab.
    // The hand-off object is Compose state, so this reacts both on cold
    // launch and while the activity is already alive; the screen switch is
    // deferred until the screens are loaded so the ViewModel's async
    // settings sync can't overwrite it.
    val deepLink = com.example.tail.MainActivity.NotificationsDeepLink
    androidx.compose.runtime.LaunchedEffect(deepLink.open) {
        if (deepLink.open) {
            deepLink.open = false
            showNotificationsDialog = true
        }
    }
    androidx.compose.runtime.LaunchedEffect(deepLink.screenIndex, habitScreens) {
        val idx = deepLink.screenIndex
        if (idx in habitScreens.indices) {
            deepLink.screenIndex = -1
            viewModel.switchScreen(idx)
        }
    }

    // Location edit dialog state
    var showLocationEditDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var dialogHabit by remember { mutableStateOf<Habit?>(null) }
    // Subtype increment dialog state
    var subtypeDialogHabit by remember { mutableStateOf<Habit?>(null) }
    var subtypeDialogBreakdown by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // Weights input dialog state (weights-type habits)
    var weightsDialogHabit by remember { mutableStateOf<Habit?>(null) }
    var showCalendarPicker by remember { mutableStateOf(false) }
    var showAddScreenDialog by remember { mutableStateOf(false) }
    // Index of screen being renamed (-1 = none)
    var renamingScreenIndex by remember { mutableStateOf(-1) }
    // Grid cell index where "Add Habit" was triggered (-1 = none)
    var addHabitAtIndex by remember { mutableStateOf(-1) }
    // Grid cell index where "Add App Link" was triggered (-1 = none)
    var addAppLinkAtIndex by remember { mutableStateOf(-1) }
    // Habit name for which the app-association picker is open (null = none)
    var appAssociationPickerHabit by remember { mutableStateOf<String?>(null) }
    // Habit name for which the widget-trigger app picker is open (null = none)
    var widgetTriggerPickerHabit by remember { mutableStateOf<String?>(null) }
    // Habit name for which the media app picker is open (null = none)
    var mediaAppPickerHabit by remember { mutableStateOf<String?>(null) }
    // Habit name for which the long-press URL app picker is open (null = none)
    var longPressUrlAppPickerHabit by remember { mutableStateOf<String?>(null) }
    // Habit name for which the multi-app launcher dialog is open (null = none)
    var appLauncherHabit by remember { mutableStateOf<String?>(null) }
    // Habit name pending delete confirmation (null = none)
    var deleteConfirmHabitName by remember { mutableStateOf<String?>(null) }
    // Habit name for which icon picker is open (null = none)
    var iconPickerHabitName by remember { mutableStateOf<String?>(null) }
    // Habit name for which the conditional links picker is open (null = none)
    var conditionalLinksPickerHabit by remember { mutableStateOf<String?>(null) }
    // Habit name for which the conditional backfill confirm dialog is open (null = none)
    var conditionalBackfillHabit by remember { mutableStateOf<String?>(null) }
    // Habit name for which the "1 max" recalc dialog is open (null = none)
    var maxOneRecalcHabit by remember { mutableStateOf<String?>(null) }
    // Habit name for which the "1 max" restore dialog is open (null = none)
    var maxOneRestoreHabit by remember { mutableStateOf<String?>(null) }

    // Roll forward confirmation dialog state
    data class RollForwardDialogState(
        val habitName: String,
        val actionType: String, // "increment" or "text"
        val startDate: LocalDate,
        val initialEndDate: LocalDate,
        val onConfirm: (LocalDate) -> Unit
    )
    var rollForwardDialogState by remember { mutableStateOf<RollForwardDialogState?>(null) }

    // Text-input dialog state: non-null when the dialog should be shown
    var textInputDialogState by remember { mutableStateOf<TextInputDialogState?>(null) }

    // Meal detail dialog state: non-null habit name when the meal panel is open
    var mealDialogHabit by remember { mutableStateOf<String?>(null) }
    // True when the dialog was opened by tapping the habit (increment already happened)
    var mealDialogFromTap by remember { mutableStateOf(false) }
    // Non-null meal log id when the panel should auto-open that meal's editor
    // (set by the timestamp editor's pencil action for meal habits).
    var mealDialogFocusLogId by remember { mutableStateOf<String?>(null) }

    // Timestamp editor dialog state
    var timestampEditorHabitName by remember { mutableStateOf<String?>(null) }
    var timestampEditorList by remember { mutableStateOf<List<String>>(emptyList()) }
    // Per-timestamp minutes (`"HH:mm:ss" -> minutes`) for the open editor —
    // loaded only for minutes-primary habits (see TimestampEditorDialog).
    var timestampEditorMinutes by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    // Text entries for the currently selected edit-mode habit (for view/edit in edit bar)
    var editModeTextEntries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    // Schedule block details popup: the tapped block + its instance texts
    var scheduleDetailsBlock by remember { mutableStateOf<ScheduleBlock?>(null) }
    var scheduleDetailsTexts by remember { mutableStateOf<List<String>>(emptyList()) }
    // Derive the selected edit habit name at top level for LaunchedEffect
    val editHabitName = if (selectedEditIndex >= 0 && selectedEditIndex < habits.size)
        habits[selectedEditIndex].name?.takeIf { it.isNotEmpty() } else null
    // Load text entries when the selected edit habit changes and is a text-input habit
    LaunchedEffect(editHabitName, selectedDate) {
        Log.d("HabitGridScreen", "LaunchedEffect: editHabitName=$editHabitName selectedDate=$selectedDate isTextInput=${editHabitName in settings.textInputHabits}")
        if (editHabitName != null && editHabitName in settings.textInputHabits) {
            viewModel.loadTextEntriesWithTimestamps(editHabitName, selectedDate) { entries ->
                Log.d("HabitGridScreen", "LaunchedEffect callback: entries count=${entries.size}")
                editModeTextEntries = entries
            }
        } else {
            editModeTextEntries = emptyList()
        }
    }
    // Timestamp count for the currently selected edit-mode habit (for showing the button)
    var selectedHabitTimestampCount by remember { mutableIntStateOf(0) }
    val timestampScope = rememberCoroutineScope()

    // ── Quick Capture review banner ─────────────────────────────────────────
    // Unrecognised quick captures (NEEDS_REVIEW vision-queue items) surface
    // as a top banner; tapping opens the Quick Capture History where the
    // intended habit can be assigned and the capture retried.
    var quickCaptureReviewCount by remember { mutableIntStateOf(0) }
    var showQuickCaptureHistory by remember { mutableStateOf(false) }
    val quickCaptureScope = rememberCoroutineScope()
    fun refreshQuickCaptureReviewCount() {
        quickCaptureScope.launch {
            val n = withContext(Dispatchers.IO) {
                VisionQueueRepository(context).reviewItemCount()
            }
            quickCaptureReviewCount = n
        }
    }
    LaunchedEffect(Unit) { refreshQuickCaptureReviewCount() }

    // Increment toast state — shows briefly after tapping a habit
    var incrementToastHabit by remember { mutableStateOf<String?>(null) }
    var incrementToastOriginalTime by remember { mutableStateOf("") }
    var incrementToastIsTimeless by remember { mutableStateOf(false) }
    // Version counter to prevent stale auto-dismiss from clearing a newer toast
    var incrementToastVersion by remember { mutableIntStateOf(0) }
    // Quick timestamp editor dialog state
    var quickEditHabitName by remember { mutableStateOf<String?>(null) }
    var quickEditOriginalTime by remember { mutableStateOf("") }
    var quickEditWasTimeless by remember { mutableStateOf(false) }
    val toastScope = rememberCoroutineScope()

    // Movie confirmation flash state — asks "did you watch X?" once per
    // desktop-detected movie when the app is opened (movie bridge feature).
    var moviePromptChecked by remember { mutableStateOf(false) }
    // Pending ask currently shown in the one-time bottom flash (null = none)
    var flashAsk by remember { mutableStateOf<HabitNotification?>(null) }
    // Bumped to re-check for unseen asks after a flash is closed unanswered
    var flashCycle by remember { mutableIntStateOf(0) }

    // File picker for per-habit text log files (used from EditModeControlBar)
    var textInputPickerHabit by remember { mutableStateOf<String?>(null) }
    val textInputFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val habitName = textInputPickerHabit
        if (uri != null && habitName != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setTextInputFileUri(habitName, uri)
        }
        textInputPickerHabit = null
    }

    // Directory picker for CREATING a new text log file for a habit that has
    // none yet (used from EditModeControlBar). The app creates, names and
    // associates the file inside the chosen directory.
    var textInputCreateHabit by remember { mutableStateOf<String?>(null) }
    val textInputCreateDirPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val habitName = textInputCreateHabit
        if (uri != null && habitName != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.createTextInputFileInDir(habitName, uri)
        }
        textInputCreateHabit = null
    }

    // File picker for per-habit dated-entry source files (read-only is sufficient)
    var datedEntryPickerHabit by remember { mutableStateOf<String?>(null) }
    val datedEntryFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val habitName = datedEntryPickerHabit
        if (uri != null && habitName != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.setDatedEntryFileUri(habitName, uri)
        }
        datedEntryPickerHabit = null
    }

    // ── Restore a single habit from a backup file ─────────────────────────
    // The picker remembers which habit we're restoring for; once a file is
    // chosen we ask the ViewModel for a non-destructive preview, which drives
    // the confirmation dialog below.
    var restoreBackupHabitName by remember { mutableStateOf<String?>(null) }
    val restoreBackupPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val habitName = restoreBackupHabitName
        if (uri != null && habitName != null) {
            viewModel.previewHabitRestore(uri, habitName)
        }
        restoreBackupHabitName = null
    }
    val habitRestorePreview by viewModel.habitRestorePreview.collectAsState()
    val habitRestoreStatus by viewModel.habitRestoreStatus.collectAsState()
    val datedEntryRefreshPreview by viewModel.datedEntryRefreshPreview.collectAsState()
    val datedEntryRefreshStatus by viewModel.datedEntryRefreshStatus.collectAsState()

    // Show errors as snackbar
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage!!)
            viewModel.clearError()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Observe every pointer event (Initial pass — before children
            // handle it, without consuming) so ANY touch counts as user
            // activity for the idle shimmer.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        shimmerInteractionGen.intValue++
                    }
                }
            }
    ) {
    Scaffold(
        topBar = {
            TopAppBar(
                modifier = Modifier.ghostGlassSquares(
                    shimmerSweep = { shimmerSweep.value },
                    shimmerDirection = { shimmerDirection.value }
                ),
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Start,
                        modifier = Modifier
                            .fillMaxWidth()
                            // Nudge the whole date widget a little left to make
                            // room for the sixth action icon on the right.
                            .offset(x = (-4).dp)
                    ) {
                        // Soft red accent shared by the Today label and its arrows;
                        // the date itself turns bright red when viewing a past day.
                        val dateNavTint = lerp(Color.White, Color(0xFFFF5252), 0.35f)

                        // Back arrow — always available, hold to rapid-step
                        RepeatIconButton(
                            onClick = { viewModel.navigateDay(-1) },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous day",
                                tint = dateNavTint
                            )
                        }

                        // Date label — tappable to open the calendar picker
                        val dateLabel = if (isToday) "Today" else selectedDate.format(DISPLAY_DATE_FMT)
                        val dateLabelColor = if (isToday) dateNavTint else Color(0xFFFF5252)
                        Text(
                            text = dateLabel,
                            color = dateLabelColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) { showCalendarPicker = true }
                                .padding(horizontal = 2.dp, vertical = 2.dp)
                        )

                        // Forward arrow — disabled when already on today, hold to rapid-step
                        RepeatIconButton(
                            onClick = { viewModel.navigateDay(+1) },
                            enabled = !isToday,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next day",
                                tint = if (isToday) Color.Gray else dateNavTint
                            )
                        }

                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                actions = {
                    // Six compact actions (edit, graph, schedule, notifications,
                    // search, settings) — sized down and tightly packed so all
                    // six fit in the top bar. Each icon keeps a light but
                    // visible tint of its accent colour.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(0.dp)
                    ) {
                        // Edit mode toggle — slight orange tint
                        IconButton(
                            onClick = { viewModel.toggleEditMode() },
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (editMode) Color(0xFF4A2A00) else Color.Transparent
                            )
                        ) {
                            Icon(
                                Icons.Default.Edit,
                                contentDescription = if (editMode) "Edit mode ON" else "Edit mode OFF",
                                tint = if (editMode) Color(0xFFFFAA00)
                                else lerp(Color.White, Color(0xFFFFAA00), 0.35f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        // Graph mode toggle — slight green tint
                        IconButton(
                            onClick = { viewModel.toggleGraphMode() },
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (graphMode) Color(0xFF0A2A0A) else Color.Transparent
                            )
                        ) {
                            Icon(
                                Icons.Default.BarChart,
                                contentDescription = if (graphMode) "Graph mode ON" else "Graph mode OFF",
                                tint = if (graphMode) Color(0xFF66DD66)
                                else lerp(Color.White, Color(0xFF66DD66), 0.35f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        // Daily schedule (retrospective timeline) — slight blue
                        // tint; highlighted while active
                        IconButton(
                            onClick = { viewModel.toggleScheduleMode() },
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (scheduleMode) Color(0xFF0A2A3A) else Color.Transparent
                            )
                        ) {
                            Icon(
                                Icons.Default.Schedule,
                                contentDescription = if (scheduleMode) "Day timeline ON" else "Day timeline",
                                tint = if (scheduleMode) Color(0xFF66CCFF)
                                else lerp(Color.White, Color(0xFF66CCFF), 0.35f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        // Notifications — slight yellow tint; highlighted while
                        // asks are waiting for an answer
                        IconButton(
                            onClick = { showNotificationsDialog = true },
                            modifier = Modifier.size(34.dp),
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = if (pendingNotifications > 0) Color(0xFF3A320A) else Color.Transparent
                            )
                        ) {
                            BadgedBox(
                                badge = {
                                    if (pendingNotifications > 0) {
                                        // White circle, very slightly transparent, black number
                                        Badge(
                                            containerColor = Color.White.copy(alpha = 0.88f),
                                            contentColor = Color.Black
                                        ) {
                                            Text("$pendingNotifications", fontSize = 9.sp)
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Default.Notifications,
                                    contentDescription = "Notifications",
                                    tint = if (pendingNotifications > 0) Color(0xFFFFD700)
                                    else lerp(Color.White, Color(0xFFFFD700), 0.35f),
                                    modifier = Modifier.size(19.dp)
                                )
                            }
                        }
                        // Global search — slight pink tint
                        IconButton(
                            onClick = {
                                viewModel.refreshSearchableHabits()
                                showSearchDialog = true
                            },
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = "Search",
                                tint = lerp(Color.White, Color(0xFFFF69B4), 0.35f),
                                modifier = Modifier.size(19.dp)
                            )
                        }
                        // Settings — white tint
                        IconButton(
                            onClick = onNavigateToSettings,
                            modifier = Modifier.size(34.dp)
                        ) {
                            Icon(
                                Icons.Default.Settings,
                                contentDescription = "Settings",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp)
                            )
                        }
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            // ── Location row — shown below the top bar, above tabs/grid.
            // Right-aligned globe icon sits directly under the Settings icon
            // in the top bar (same horizontal position).
            if (!isLandscape) {
                val assumedLocation = remember(selectedDate) {
                    viewModel.getAssumedLocationForDate(selectedDate)
                }
                val locationLabel = selectedDateLocation
                    ?: assumedLocation?.let { "$it *" }
                    ?: "No location"
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .ghostGlassSquares(
                            shimmerSweep = { shimmerSweep.value },
                            shimmerDirection = { shimmerDirection.value }
                        )
                        .padding(start = 12.dp, end = 4.dp, top = 0.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Slightly slid up: a small negative offset tightens the
                    // gap to the top bar.
                    Text(
                        text = locationLabel,
                        color = if (selectedDateLocation != null) Color(0xFFAAAAAA) else Color(0xFF666666),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .weight(1f)
                            .offset(y = (-2).dp)
                            .combinedClickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() },
                                onClick = { showLocationEditDialog = true },
                                // Long-press the location name → map view
                                // (replaces the old globe icon button).
                                onLongClick = { onNavigateToMap() }
                            )
                            .padding(vertical = 1.dp)
                    )
                }
            }

            // Screen tabs — shown when multiple screens exist (hidden in
            // landscape and in schedule mode, which aggregates all screens)
            if (habitScreens.size > 1 && !isLandscape && !scheduleMode) {
                ScreenTabRow(
                    shimmerSweep = { shimmerSweep.value },
                    shimmerDirection = { shimmerDirection.value },
                    screens = habitScreens,
                    activeIndex = activeScreenIndex,
                    editMode = editMode,
                    hiddenScreenIds = settings.hiddenScreens,
                    onTabClick = { idx ->
                        if (editMode && idx == activeScreenIndex) {
                            renamingScreenIndex = idx
                        } else {
                            viewModel.switchScreen(idx)
                        }
                    },
                    onMoveScreenLeft = if (editMode) { idx ->
                        viewModel.reorderScreen(idx, idx - 1)
                    } else null,
                    onMoveScreenRight = if (editMode) { idx ->
                        viewModel.reorderScreen(idx, idx + 1)
                    } else null,
                    onTabLayout = { idx, rect -> dragState.tabBounds[idx] = rect },
                    scrollState = tabRowScrollState,
                    onRowLayout = { dragState.tabRowBoundsInWindow = it }
                )
            }

            if (isLoading) {
                // "The Orrery" — triple-metric loading animation. The monthly
                // average drives the core form & colour, the weekly average the
                // orbital halo, today's points the central spark. Reads the
                // retained loadingMetrics StateFlow (not the stale habits list)
                // so the tiers are correct even mid-load.
                // ghostGlassSquares keeps the full-screen background lattice
                // alive while the grid itself is not composed (loading),
                // using the same brightness/fade as the real grid.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .ghostGlassSquares(
                            shimmerSweep = { shimmerSweep.value },
                            shimmerDirection = { shimmerDirection.value },
                            isGridAnchor = true
                        )
                ) {
                    HabitLoadingSpinner(
                        monthlyAverage = loadingMetrics.monthlyAverage,
                        weeklyAverage = loadingMetrics.weeklyAverage,
                        todayPoints = loadingMetrics.todayPoints,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            } else if (habits.isEmpty() && settings.fileUri.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Text(
                        text = "Go to ⚙ Settings to select your habitsdb.txt file",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
            } else if (graphMode && isLandscape) {
                // ── Landscape + Graph mode: fullscreen graph ───────────────
                GraphsPanel(
                    viewModel = viewModel,
                    isLandscape = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    garminHabitLinks = settings.garminHabitLinks
                )
            } else if (scheduleMode) {
                // ── Schedule mode: retrospective hour-by-hour timeline ────
                // Replaces the grid with everything that was timestamped on
                // the selected day. Habits without time data are absent.
                ScheduleTimelineScreen(
                    // ALL habits across ALL screens — the schedule is a
                    // whole-day view and must not filter to the active grid.
                    habitNames = habitScreens.flatMap { it.habitNames }
                        .filter { it.isNotEmpty() && !com.example.tail.data.isAppLink(it) }
                        .distinct(),
                    mealHabits = settings.mealHabits,
                    textInputHabits = settings.textInputHabits,
                    timelineExcludedHabits = settings.timelineExcludedHabits,
                    // Movie-bridge habits: their blocks are sized to the
                    // watched minutes annotated on the day's text entries —
                    // the text log is the source of truth, so past films
                    // appear even without increment timestamps.
                    movieHabits = if (settings.bridgeEnabled) settings.bridgeMovieHabits else emptySet(),
                    loadMovieEntries = { habitName ->
                        viewModel.loadMovieEntriesForDay(habitName, selectedDate)
                    },
                    // Garmin-linked habits: activity blocks are sized (and,
                    // when the watch start time is known, placed) from the
                    // cached daily activity minutes.
                    garminHabits = settings.garminHabitLinks.keys,
                    loadGarminActivity = { habitName ->
                        viewModel.loadGarminActivityForDay(habitName, selectedDate)
                    },
                    // Chip colour encodes the habits screen (tab) each
                    // habit lives on in the main grid.
                    screenIndexOfHabit = habitScreens.flatMapIndexed { idx, screen ->
                        screen.habitNames.mapNotNull { name ->
                            if (name.isEmpty() || com.example.tail.data.isAppLink(name)) null
                            else name to idx
                        }
                    }.toMap(),
                    selectedDate = selectedDate,
                    isToday = isToday,
                    refreshTrigger = scheduleRefresh,
                    // Same "Orrery" loading animation as the main screen/map,
                    // driven by the current day/week/month points tiers.
                    loadingMetrics = loadingMetrics,
                    timestampRepo = viewModel.timestampRepo,
                    onBlockClick = { block ->
                        // Instance popup: show what we know about THIS
                        // block (times, duration, points, logged text) —
                        // not the whole-habit timestamp editor.
                        scheduleDetailsTexts = emptyList()
                        scheduleDetailsBlock = block
                        viewModel.loadTextEntriesWithTimestamps(block.habitName, selectedDate) { entries ->
                            // Guard against a stale out-of-order load from
                            // an earlier tap overwriting the current popup.
                            if (scheduleDetailsBlock == block) {
                                val from = block.firstTime
                                val to = block.lastTime
                                scheduleDetailsTexts = entries
                                    .filter { (fullTs, text) ->
                                        text.isNotBlank() && fullTs.takeLast(8) in from..to
                                    }
                                    .map { it.second }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                )
            } else {
                // ── Portrait (or landscape without graph mode) ─────────────
                // Grid takes up most of the screen
                Box(modifier = Modifier
                    .weight(1f)
                    .then(if (habitScreens.size > 1 && !editMode) {
                        Modifier.pointerInput(habitScreens.size, activeScreenIndex) {
                            var totalDragX = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { totalDragX = 0f },
                                onDragEnd = {
                                    val swipeThreshold = 60f
                                    when {
                                        totalDragX < -swipeThreshold -> {
                                            val next = (activeScreenIndex + 1) % habitScreens.size
                                            viewModel.switchScreen(next)
                                        }
                                        totalDragX > swipeThreshold -> {
                                            val prev = (activeScreenIndex - 1 + habitScreens.size) % habitScreens.size
                                            viewModel.switchScreen(prev)
                                        }
                                    }
                                },
                                onDragCancel = { totalDragX = 0f },
                                onHorizontalDrag = { _, dragAmount -> totalDragX += dragAmount }
                            )
                        }
                    } else Modifier)
                    // ── Edit-mode drag-to-reorder ──────────────────────────────
                    // Long-press a habit cell to lift it; drag over other cells
                    // to preview the shift; hover a screen tab to switch screens
                    // mid-drag; release to commit — or drift away / cancel and
                    // everything snaps back. The gesture itself lives in
                    // Modifier.habitDragGesture (keeps this composable lean).
                    .then(if (editMode) {
                        Modifier.habitDragGesture(
                            state = dragState,
                            gridState = gridDragState,
                            habits = currentHabits,
                            activeScreen = currentActiveScreen,
                            screenCount = currentScreenCount,
                            scope = dragScope,
                            onSwitchScreen = viewModel::switchScreen,
                            onBeginDrag = viewModel::beginHabitDrag,
                            onCommitMove = viewModel::commitHabitMove,
                            onCommitCrossScreen = viewModel::commitCrossScreenDrag,
                            tabScroll = tabRowScrollState
                        )
                    } else Modifier)
                ) {
                    HabitGrid(
                        habits = displayHabits,
                        gridState = gridDragState,
                        dragTargetIndex = if (dragState.isActive) dragState.targetIndex else -1,
                        onGridLayout = { dragState.gridOriginInWindow = it },
                        shimmerSweep = { shimmerSweep.value },
                        shimmerDirection = { shimmerDirection.value },
                        editMode = editMode,
                        graphMode = graphMode,
                        highlightedHabit = highlightedHabit,
                        graphSelectedHabits = graphSelectedHabits,
                        selectedEditIndex = selectedEditIndex,
                        movePendingSourceIndex = movePendingSourceIndex,
                        customIconOverrides = settings.habitIcons,
                        disabledHabits = settings.disabledHabits,
                        aiIconRepo = if (settings.aiIconsEnabled) viewModel.getAiIconRepo() else null,
                        aiIconPendingHabits = aiIconPendingHabits,
                        garminHabitLinks = settings.garminHabitLinks,
                        appLinks = settings.appLinks,
                        habitAppAssociations = settings.habitAppAssociations,
                        mealHabits = settings.mealHabits,
                        weightsHabits = settings.weightsHabits,
                        bridgeMovieHabits = if (settings.bridgeEnabled) settings.bridgeMovieHabits else emptySet(),
                        chessComHabitLinks = settings.chessComHabitLinks,
                        habitLongPressActions = settings.habitLongPressActions,
                        habitLongPressUrls = settings.habitLongPressUrls,
                        onHabitClick = { habit, index ->
                            when {
                                isAppLink(habit.name) -> {
                                    if (editMode) {
                                        viewModel.selectEditHabit(index)
                                        revealCellIndex = index
                                        revealNonce++
                                    } else {
                                        // Launch the linked app
                                        appLinkPackageName(habit.name)?.let { pkg ->
                                            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                                            if (launchIntent != null) {
                                                context.startActivity(launchIntent)
                                            }
                                        }
                                    }
                                }
                                graphMode -> {
                                    viewModel.toggleGraphHabitSelection(habit.name)
                                    // The graph panel grows when the first habit is
                                    // selected — reveal the tapped cell if it gets
                                    // pushed out of the shrunken grid.
                                    revealCellIndex = index
                                    revealNonce++
                                }
                                editMode -> {
                                    viewModel.selectEditHabit(index)
                                    revealCellIndex = index
                                    revealNonce++
                                }
                                habit.name in settings.mealHabits -> {
                                    // Meal tap = merge-or-increment: ALWAYS yields a
                                    // card (placeholder until details are added) and
                                    // opens the meal screen. No increment toast —
                                    // the time is edited on the meal card itself.
                                    viewModel.recordMealTap(habit.name, selectedDate)
                                    mealDialogFromTap = true
                                    mealDialogHabit = habit.name
                                }
                                habit.name in settings.subtypedHabits -> {
                                    viewModel.loadSubtypeBreakdown(habit.name) { breakdown ->
                                        subtypeDialogBreakdown = breakdown
                                        subtypeDialogHabit = habit
                                    }
                                }
                                habit.name in settings.weightsHabits -> weightsDialogHabit = habit
                                habit.name in settings.textInputHabits -> {
                                    val showOpts = habit.name in settings.textInputOptionsHabits
                                    val isMovieLinked = habit.name in settings.bridgeMovieHabits &&
                                        settings.bridgeEnabled

                                    // Helper: open the dialog IMMEDIATELY with what is
                                    // already known; today's entries (and options) stream
                                    // in afterwards — the dialog reacts to state updates,
                                    // so nothing blocks the popup from appearing.
                                    fun showDialog(
                                        suggestedText: String = "",
                                        suggestionLabel: String = "",
                                        suggestedMinutes: Int? = null,
                                        suggestionLoading: Boolean = false,
                                        recentMovies: List<BridgeMovie> = emptyList()
                                    ) {
                                        textInputDialogState = TextInputDialogState(
                                            habit = habit,
                                            showOptions = showOpts,
                                            options = emptyList(),
                                            todayEntries = emptyList(),
                                            suggestedText = suggestedText,
                                            suggestionLabel = suggestionLabel,
                                            suggestedMinutes = suggestedMinutes,
                                            suggestionLoading = suggestionLoading,
                                            recentMovies = recentMovies
                                        )
                                        viewModel.loadTextEntriesWithTimestamps(habit.name, selectedDate) { todayEntries ->
                                            val cur = textInputDialogState
                                            if (cur?.habit?.name == habit.name) {
                                                textInputDialogState = cur.copy(todayEntries = todayEntries)
                                            }
                                            if (showOpts) {
                                                viewModel.loadTextOptions(habit.name) { opts ->
                                                    val c2 = textInputDialogState
                                                    if (c2?.habit?.name == habit.name) {
                                                        textInputDialogState = c2.copy(options = opts)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (isMovieLinked) {
                                        // The dialog opens instantly; the suggestion is
                                        // resolved from the phone-local movie cache (no
                                        // network wait) and topped up by a background
                                        // bridge refresh. While it resolves, the dialog
                                        // shows a small loading indicator.
                                        showDialog(
                                            suggestionLoading = true,
                                            recentMovies = viewModel.currentMovieCache().take(5)
                                        )
                                        viewModel.streamMovieSuggestion(habit.name, selectedDate) { sugg ->
                                            val cur = textInputDialogState
                                            if (cur?.habit?.name == habit.name) {
                                                textInputDialogState = cur.copy(
                                                    suggestedText = sugg.movie?.title ?: "",
                                                    suggestionLabel = sugg.movie?.let { movie ->
                                                        buildString {
                                                            append("🎬 Suggested from desktop")
                                                            if (movie.lastWatched.isNotBlank()) {
                                                                append(" — watched ${movie.lastWatched.take(10)}")
                                                            }
                                                        }
                                                    } ?: "",
                                                    // The file duration (from ffprobe) goes into
                                                    // the separate, wheel-editable Length field.
                                                    suggestedMinutes = sugg.movie?.totalWatchMin
                                                        ?.takeIf { it > 0 },
                                                    suggestionLoading = sugg.loading,
                                                    recentMovies = sugg.recent
                                                )
                                            }
                                        }
                                    } else {
                                        showDialog()
                                    }
                                }
                                habit.useCustomInput -> dialogHabit = habit
                                else -> {
                                    // When viewing a different day or habit is timeless, increment without timestamp
                                    val timeless = !isToday || habit.name in settings.timelessHabits
                                    
                                    // Camera-enabled habit tapped for TODAY → capture-driven
                                    // increment: the camera opens IMMEDIATELY and this tap
                                    // performs NO direct increment — the background vision
                                    // pipeline creates the meal log and performs the
                                    // increment (merging into an active meal group when one
                                    // exists, so multiple courses never double-count).
                                    if (isToday && habit.name in settings.cameraHabits) {
                                        val cameraIntent = android.content.Intent(
                                            context,
                                            com.example.tail.QuickCaptureActivity::class.java
                                        ).apply {
                                            putExtra(
                                                com.example.tail.QuickCaptureActivity.EXTRA_HABIT_NAME,
                                                habit.name
                                            )
                                        }
                                        context.startActivity(cameraIntent)
                                    }
                                    // Check if this is a roll forward habit and we're viewing a past date
                                    else if (habit.name in settings.rollForwardHabits && !isToday) {
                                        // Find the next manual date
                                        val nextManualDate = settings.rollForwardManualDates[habit.name]?.mapNotNull { dateStr ->
                                            com.example.tail.data.parseDate(dateStr)
                                        }?.sorted()?.firstOrNull { it > selectedDate }
                                        
                                        val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()
                                        
                                        // Show roll forward confirmation dialog
                                        rollForwardDialogState = RollForwardDialogState(
                                            habitName = habit.name,
                                            actionType = "increment",
                                            startDate = selectedDate,
                                            initialEndDate = endDate,
                                            onConfirm = { confirmedEndDate ->
                                                viewModel.incrementHabitWithRollForward(
                                                    habitName = habit.name,
                                                    amount = 1,
                                                    recordTimestamp = isToday,
                                                    customEndDate = confirmedEndDate
                                                )
                                                // Show increment toast with edit-time option
                                                incrementToastVersion++
                                                incrementToastHabit = habit.name
                                                incrementToastIsTimeless = !isToday
                                                incrementToastOriginalTime = if (isToday) com.example.tail.data.HabitTimestampRepository.nowTime() else ""
                                                val currentVersion = incrementToastVersion
                                                toastScope.launch {
                                                    delay(3500)
                                                    if (incrementToastVersion == currentVersion) {
                                                        incrementToastHabit = null
                                                    }
                                                }
                                            }
                                        )
                                    } else {
                                        // Normal increment without roll forward — always
                                        // record a timestamp when incrementing for today.
                                        viewModel.incrementHabit(habit.name, 1, recordTimestamp = isToday)
                                        // Manually incrementing the linked Puzzle Rush habit
                                        // = back-filling a rush run the timer missed: open
                                        // the same report overlay the bubble uses, in manual
                                        // mode (extra minutes input).
                                        val rushHabit = com.example.tail.widget.ChessReadinessStore
                                            .linkedRushHabit(context).trim()
                                        if (rushHabit.isNotEmpty() && habit.name == rushHabit) {
                                            try {
                                                com.example.tail.widget.ChessPuzzleRushOverlay(
                                                    context, manual = true
                                                ).show()
                                            } catch (_: Exception) { /* overlay best-effort */ }
                                        }
                                        // Show increment toast with edit-time option
                                        incrementToastVersion++
                                        incrementToastHabit = habit.name
                                        incrementToastIsTimeless = !isToday
                                        incrementToastOriginalTime = if (isToday) com.example.tail.data.HabitTimestampRepository.nowTime() else ""
                                        val currentVersion = incrementToastVersion
                                        toastScope.launch {
                                            delay(3500)
                                            // Only clear if no newer toast has replaced this one
                                            if (incrementToastVersion == currentVersion) {
                                                incrementToastHabit = null
                                            }
                                        }
                                    }
                                }
                            }
                        },
                        onHabitLongClick = { habit ->
                            if (!editMode && !graphMode && !isAppLink(habit.name)) {
                                // URL configured for the LONG_PRESS_URL action (null/blank = not set)
                                val longPressUrl = settings.habitLongPressUrls[habit.name]
                                // Determine the configured long-press action (defaults to "app").
                                // A URL action without a configured URL falls back to the app behaviour.
                                val action = com.example.tail.data.effectiveLongPressAction(
                                    settings.habitLongPressActions[habit.name]
                                ).let { effective ->
                                    if (effective == com.example.tail.data.LONG_PRESS_URL &&
                                        longPressUrl.isNullOrBlank()
                                    ) com.example.tail.data.LONG_PRESS_APP else effective
                                }
                                when (action) {
                                    com.example.tail.data.LONG_PRESS_CAMERA -> {
                                        // Launch camera capture for this meal habit
                                        val intent = android.content.Intent(
                                            context,
                                            com.example.tail.QuickCaptureActivity::class.java
                                        ).apply {
                                            putExtra(
                                                com.example.tail.QuickCaptureActivity.EXTRA_HABIT_NAME,
                                                habit.name
                                            )
                                        }
                                        context.startActivity(intent)
                                    }
                                    com.example.tail.data.LONG_PRESS_DETAILS -> {
                                        // Open the meal details dialog
                                        mealDialogFromTap = false
                                        mealDialogHabit = habit.name
                                    }
                                    com.example.tail.data.LONG_PRESS_URL -> {
                                        // Open the configured URI — inside the chosen app
                                        // when one is set, otherwise via the default handler
                                        // (browser for https, the target app for deep links
                                        // like obsidian://open?vault=…&file=…).
                                        // Normalize again at launch so legacy entries saved
                                        // before URI normalization exist still resolve.
                                        val normalizedUrl = com.example.tail.data.normalizeLongPressUri(longPressUrl!!)
                                        val uri = android.net.Uri.parse(normalizedUrl)
                                        val urlIntent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            uri
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        val urlApp = settings.habitLongPressUrlApps[habit.name]
                                        if (!urlApp.isNullOrBlank()) urlIntent.setPackage(urlApp)
                                        fun showNoHandlerToast() {
                                            val scheme = uri.scheme ?: normalizedUrl.take(20)
                                            android.widget.Toast.makeText(
                                                context,
                                                "No app found that opens \"$scheme\" links",
                                                android.widget.Toast.LENGTH_LONG
                                            ).show()
                                        }
                                        try {
                                            context.startActivity(urlIntent)
                                        } catch (_: Exception) {
                                            // Chosen app can't handle this URL — fall back to any handler
                                            if (!urlApp.isNullOrBlank()) {
                                                // Tell the user WHY the browser opened — the chosen
                                                // app declares no intent filter for this link's host/path.
                                                val appLabel = try {
                                                    context.packageManager.getApplicationLabel(
                                                        context.packageManager.getApplicationInfo(urlApp, 0)
                                                    ).toString()
                                                } catch (_: Exception) { urlApp }
                                                android.widget.Toast.makeText(
                                                    context,
                                                    "$appLabel can't open this link — opening in browser",
                                                    android.widget.Toast.LENGTH_LONG
                                                ).show()
                                                try {
                                                    context.startActivity(
                                                        android.content.Intent(
                                                            android.content.Intent.ACTION_VIEW,
                                                            uri
                                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    )
                                                } catch (_: Exception) {
                                                    // No handler at all — tell the user
                                                    showNoHandlerToast()
                                                }
                                            } else {
                                                // No chosen app and no system handler for this scheme
                                                showNoHandlerToast()
                                            }
                                        }
                                    }
                                    else -> {
                                        // LONG_PRESS_APP (default) — launch associated app(s)
                                        val associations = settings.habitAppAssociations[habit.name]
                                        if (!associations.isNullOrEmpty()) {
                                            if (associations.size == 1) {
                                                // Single entry — launch directly, bypass list.
                                                // Shortcut entries open their specific shortcut;
                                                // plain entries open the app's launch intent.
                                                val entry = associations[0]
                                                if (!launchShortcutEntry(context, entry)) {
                                                    val launchIntent = context.packageManager
                                                        .getLaunchIntentForPackage(entry)
                                                    if (launchIntent != null) {
                                                        context.startActivity(launchIntent)
                                                    }
                                                }
                                            } else {
                                                // Multiple apps — show picker dialog
                                                appLauncherHabit = habit.name
                                            }
                                        } else {
                                            // No app associations — open app picker to set first association
                                            appAssociationPickerHabit = habit.name
                                        }
                                    }
                                }
                            }
                        },
                        onPlaceholderClick = { index ->
                            // In edit mode, selecting a placeholder works like selecting a habit
                            viewModel.selectEditHabit(index)
                            revealCellIndex = index
                            revealNonce++
                        }
                    )

                    // ── Dragged-habit overlay ─────────────────────────────────
                    // The lifted habit floats under the finger (Box children
                    // aren't clipped, so it also draws over the tab bar) while
                    // its vacated landing cell pulses cyan in the grid below.
                    DraggedHabitOverlay(
                        state = dragState,
                        customIconOverrides = settings.habitIcons,
                        aiIconRepo = if (settings.aiIconsEnabled) viewModel.getAiIconRepo() else null
                    )
                }

                // Graph panel — shown below grid when in graph mode (portrait)
                // Capped at half the screen height so the habit grid is never covered
                if (graphMode) {
                    val maxGraphHeight = (configuration.screenHeightDp / 2).dp
                    Box(modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = maxGraphHeight)
                    ) {
                        GraphsPanel(
                            viewModel = viewModel,
                            isLandscape = false,
                            modifier = Modifier.fillMaxWidth(),
                            garminHabitLinks = settings.garminHabitLinks
                        )
                    }
                }

                // Edit mode control bar — shown below grid when in edit mode
                if (editMode) {
                    // A cell is a "real habit" only if it has a non-empty name.
                    // Empty-name entries are embedded placeholders (moved-away habits).
                    val selectedHabitAtIndex = if (selectedEditIndex >= 0 && selectedEditIndex < habits.size)
                        habits[selectedEditIndex] else null
                    val selectedHabitName = selectedHabitAtIndex?.name?.takeIf { it.isNotEmpty() }
                    val isPlaceholderSelected = selectedEditIndex >= 0 &&
                        (selectedEditIndex >= habits.size || selectedHabitAtIndex?.name?.isEmpty() == true)
                    EditModeControlBar(
                        selectedIndex = selectedEditIndex,
                        selectedHabitName = selectedHabitName,
                        selectedHabitRawTodayCount = selectedHabitAtIndex?.rawTodayCount ?: 0,
                        selectedHabitTodayCount = selectedHabitAtIndex?.todayCount ?: 0,
                        isPlaceholderSelected = isPlaceholderSelected,
                        habitScreens = habitScreens,
                        activeScreenIndex = activeScreenIndex,
                        selectedHabitScreenIndex = if (selectedHabitName != null)
                            viewModel.screenIndexForHabit(selectedHabitName) else -1,
                        maxOneHabits = settings.maxOneHabits,
                        invertedBinaryHabits = settings.invertedBinaryHabits,
                        customInputHabits = settings.customInputHabits,
                        customInputAmounts = settings.customInputAmounts,
                        textInputHabits = settings.textInputHabits,
                        textInputOptionsHabits = settings.textInputOptionsHabits,
                        sharableTextHabits = settings.sharableTextHabits,
                        textInputFileUris = settings.textInputFileUris,
                        datedEntryHabits = settings.datedEntryHabits,
                        datedEntryFileUris = settings.datedEntryFileUris,
                        habitDividers = settings.habitDividers,
                        conditionalHabits = settings.conditionalHabits,
                        conditionalLinkedHabits = settings.conditionalLinkedHabits,
                        conditionalLinkValues = settings.conditionalLinkValues,
                        conditionalFeedMaxOneHabits = settings.conditionalFeedMaxOneHabits,
                        conditionalFeedPointsHabits = settings.conditionalFeedPointsHabits,
                        subtypedHabits = settings.subtypedHabits,
                        habitSubtypes = settings.habitSubtypes,
                        allHabitNames = viewModel.getAllHabitNames(),
                        rollForwardHabits = settings.rollForwardHabits,
                        rollForwardManualDates = settings.rollForwardManualDates,
                        onAddHabit = { addHabitAtIndex = selectedEditIndex },
                        onAddAppLink = { addAppLinkAtIndex = selectedEditIndex },
                        onAddScreen = { showAddScreenDialog = true },
                        onDeleteScreen = { viewModel.deleteScreen(activeScreenIndex) },
                        onToggleMaxOne = { name ->
                            if (name in settings.maxOneHabits) {
                                // Disabling — ask whether to restore past entries from timestamps
                                maxOneRestoreHabit = name
                            } else {
                                // Enabling — ask whether to cap all past entries to 1
                                maxOneRecalcHabit = name
                            }
                        },
                        onToggleInvertedBinary = { name -> viewModel.toggleInvertedBinary(name) },
                        onToggleCustomInput = { name -> viewModel.toggleCustomInput(name) },
                        onSetCustomInputAmounts = { name, amounts -> viewModel.setCustomInputAmounts(name, amounts) },
                        onToggleTextInput = { name -> viewModel.toggleTextInput(name) },
                        onToggleTextInputOptions = { name -> viewModel.toggleTextInputOptions(name) },
                        onToggleSharableText = { name -> viewModel.toggleSharableText(name) },
                        onPickTextInputFile = { name ->
                            textInputPickerHabit = name
                            textInputFilePicker.launch(arrayOf("application/json", "*/*"))
                        },
                        onCreateTextInputFile = { name ->
                            textInputCreateHabit = name
                            textInputCreateDirPicker.launch(null)
                        },
                        onToggleDatedEntry = { name -> viewModel.toggleDatedEntry(name) },
                        onPickDatedEntryFile = { name ->
                            datedEntryPickerHabit = name
                            datedEntryFilePicker.launch(arrayOf("text/plain", "text/markdown", "*/*"))
                        },
                        onRefreshDatedEntry = { name -> viewModel.previewDatedEntryRefresh(name) },
                        onDeleteHabit = { name -> deleteConfirmHabitName = name },
                        onChangeIcon = { name -> iconPickerHabitName = name },
                        onSetCount = { name, count -> viewModel.setHabitCount(name, count) },
                        onSetCountWithRollForward = { name, count, endDate -> viewModel.setHabitCountWithRollForward(name, count, endDate) },
                        onSetMinutesCount = { name, count -> viewModel.setHabitMinutesCount(name, count) },
                        selectedHabitMinutesTodayCount = selectedHabitName?.let {
                            viewModel.getMinutesTodayCount(it)
                        } ?: 0,
                        minutesFallbackHabits = settings.secondaryValueFallbackHabits,
                        onToggleMinutesFallback = { name -> viewModel.toggleMinutesFallbackHabit(name) },
                        minutesPrimaryFallbacks = settings.minutesPrimaryFallbacks,
                        onSetMinutesPrimaryFallback = { name, source ->
                            viewModel.setMinutesPrimaryFallback(name, source)
                        },
                        onSetDivider = { name, divisor -> viewModel.setHabitDivider(name, divisor) },
                        onToggleConditional = { name -> viewModel.toggleConditional(name) },
                        onToggleConditionalFeedMaxOne = { name -> viewModel.toggleConditionalFeedMaxOne(name) },
                        onToggleConditionalFeedPoints = { name -> viewModel.toggleConditionalFeedPoints(name) },
                        onSetConditionalLinks = { name -> conditionalLinksPickerHabit = name },
                        onBackfillConditional = { name -> conditionalBackfillHabit = name },
                        onToggleSubtyped = { name -> viewModel.toggleSubtyped(name) },
                        onSetSubtypes = { name, types -> viewModel.setHabitSubtypes(name, types) },
                        mealHabits = settings.mealHabits,
                        onToggleMeal = { name -> viewModel.toggleMealHabit(name) },
                        weightsHabits = settings.weightsHabits,
                        onToggleWeights = { name -> viewModel.toggleWeightsHabit(name) },
                        weightsDayValues = selectedHabitName
                            ?.takeIf { it in settings.weightsHabits }
                            ?.let { viewModel.getWeightsDayValues(it) },
                        weightsUnit = settings.graphWeightUnit,
                        onSetWeightsDayValues = { name, values, exerciseName ->
                            viewModel.setWeightsDayValues(name, values, exerciseName)
                        },
                        weightsRecentExercises = selectedHabitName
                            ?.let { settings.weightsRecentExercises[it] } ?: emptyList(),
                        onDeleteWeightsDay = { name -> viewModel.deleteWeightsDay(name) },
                        onOpenMealDetails = { name ->
                            mealDialogFromTap = false
                            mealDialogHabit = name
                        },
                        timelineExcludedHabits = settings.timelineExcludedHabits,
                        onToggleTimelineExcluded = { name -> viewModel.toggleTimelineExcluded(name) },
                        cameraHabits = settings.cameraHabits,
                        onToggleCamera = { name -> viewModel.toggleCameraHabit(name) },
                        habitLongPressActions = settings.habitLongPressActions,
                        onSetLongPressAction = { name, action ->
                            viewModel.setHabitLongPressAction(name, action)
                        },
                        habitLongPressUrls = settings.habitLongPressUrls,
                        onSetLongPressUrl = { name, url ->
                            viewModel.setHabitLongPressUrl(name, url)
                        },
                        habitLongPressUrlApps = settings.habitLongPressUrlApps,
                        onPickLongPressUrlApp = { name -> longPressUrlAppPickerHabit = name },
                        onClearLongPressUrlApp = { name -> viewModel.setHabitLongPressUrlApp(name, null) },
                        hiddenScreenIds = settings.hiddenScreens,
                        onToggleScreenHidden = { viewModel.toggleScreenHidden(activeScreenIndex) },
                        disabledHabits = settings.disabledHabits,
                        onToggleDisabled = { name -> viewModel.toggleDisabledHabit(name) },
                        noPointsHabits = settings.noPointsHabits,
                        onToggleNoPoints = { name -> viewModel.toggleNoPointsHabit(name) },
                        secondaryValueSettings = SecondaryValueSettings(
                            habits = settings.secondaryValueHabits,
                            onToggleSecondaryValue = { name -> viewModel.toggleSecondaryValueHabit(name) },
                            fallbackHabits = settings.secondaryValueFallbackHabits,
                            onToggleSecondaryValueFallback = { name -> viewModel.toggleSecondaryValueFallbackHabit(name) }
                        ),
                        valueDisplayLabels = settings.valueDisplayLabels,
                        onSetValueDisplayLabel = { name, key, label ->
                            viewModel.setValueDisplayLabel(name, key, label)
                        },
                        chessComEnabled = settings.chessComEnabled,
                        chessComHabitLinks = settings.chessComHabitLinks,
                        onSetChessComLink = { name, type -> viewModel.setChessComHabitLink(name, type) },
                        garminEnabled = settings.garminEnabled,
                        garminHabitLinks = settings.garminHabitLinks,
                        onSetGarminLink = { name, type -> viewModel.setGarminHabitLink(name, type) },
                        garminDateOfBirth = settings.garminDateOfBirth,
                        githubContent = {
                            if (settings.githubEnabled && selectedHabitName != null) {
                                GitHubLinkToggleSection(
                                    habitName = selectedHabitName,
                                    repoUrls = settings.githubRepoUrls,
                                    metrics = settings.githubMetrics,
                                    syncStatus = githubSyncStatus,
                                    onSetRepoUrl = { url -> viewModel.setGithubRepoUrl(selectedHabitName, url) },
                                    onSetMetric = { metric -> viewModel.setGithubMetric(selectedHabitName, GitHubMetric.fromKey(metric)) },
                                    onRefetch = { viewModel.fetchGithubBacklog(selectedHabitName) }
                                )
                            }
                        },
                        movieBridgeContent = {
                            if (settings.bridgeEnabled && selectedHabitName != null &&
                                selectedHabitName in settings.textInputHabits
                            ) {
                                MovieBridgeToggleSection(
                                    isMovieLinked = selectedHabitName in settings.bridgeMovieHabits,
                                    onToggle = { viewModel.toggleBridgeMovieHabit(selectedHabitName) }
                                )
                            }
                        },
                        pcWidgetContent = {
                            if (selectedHabitName != null) {
                                PcWidgetToggleSection(
                                    isOnPcWidget = selectedHabitName in settings.pcWidgetHabits,
                                    syncConfigured = settings.garminProxyUrl.isNotEmpty(),
                                    onToggle = { viewModel.togglePcWidgetHabit(selectedHabitName) }
                                )
                            }
                        },
                        garminMonthlyData = garminMonthlyData,
                        selectedDate = selectedDate,
                        voiceTriggerEnabled = settings.voiceTriggerEnabled,
                        voiceTriggerHabits = settings.voiceTriggerHabits,
                        voiceTriggerWords = settings.voiceTriggerWords,
                        voiceTriggerIncrements = settings.voiceTriggerIncrements,
                        onToggleVoiceTrigger = { name -> viewModel.toggleVoiceTrigger(name) },
                        onSetVoiceTriggerWords = { name, words -> viewModel.setVoiceTriggerWords(name, words) },
                        onSetVoiceTriggerIncrement = { name, amount -> viewModel.setVoiceTriggerIncrement(name, amount) },
                        voiceSubtypeHabits = settings.voiceSubtypeHabits,
                        onToggleVoiceSubtype = { name -> viewModel.toggleVoiceSubtype(name) },
                        timelessHabits = settings.timelessHabits,
                        onToggleTimeless = { name -> viewModel.toggleTimeless(name) },
                        customPointRangesHabits = settings.customPointRangesHabits,
                        customPointRanges = settings.customPointRanges,
                        onToggleCustomPointRanges = { name -> viewModel.toggleCustomPointRanges(name) },
                        onSetCustomPointRanges = { name, ranges -> viewModel.setCustomPointRanges(name, ranges) },
                        selectedHabitTimestampCount = selectedHabitTimestampCount,
                        onShowTimestamps = { name ->
                            timestampScope.launch {
                                timestampEditorList = viewModel.timestampRepo.getTimestampsForDay(name, selectedDate)
                                timestampEditorMinutes =
                                    if (viewModel.isMinutesPrimaryHabit(name)) {
                                        viewModel.timestampRepo.getMinutesForDay(name, selectedDate)
                                    } else emptyMap()
                                timestampEditorHabitName = name
                            }
                            // Refresh text entries so the timestamp cards can show
                            // the text logged at each increment time.
                            if (name in settings.textInputHabits) {
                                viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                    // Discard out-of-order loads so a stale
                                    // result never shows another habit's log.
                                    if (timestampEditorHabitName == name) {
                                        editModeTextEntries = entries
                                    }
                                }
                            }
                        },
                        todayTextEntries = editModeTextEntries,
                        onLoadTextEntries = { name, onResult ->
                            viewModel.loadTextEntriesWithTimestamps(name, selectedDate, onResult)
                        },
                        onEditTextEntry = { name, timestamp, newText ->
                            // Check if this is a roll forward habit and we're viewing a past date
                            if (name in settings.rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
                                // Parse the date from the timestamp
                                val dateStr = timestamp.substring(0, 10)
                                val entryDate = com.example.tail.data.parseDate(dateStr)
                                
                                if (entryDate != null) {
                                    // Find the next manual date
                                    val nextManualDate = settings.rollForwardManualDates[name]?.mapNotNull { dateStr ->
                                        com.example.tail.data.parseDate(dateStr)
                                    }?.sorted()?.firstOrNull { it > entryDate }
                                    
                                    val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()
                                    
                                    // Show roll forward confirmation dialog
                                    rollForwardDialogState = RollForwardDialogState(
                                        habitName = name,
                                        actionType = "text",
                                        startDate = entryDate,
                                        initialEndDate = endDate,
                                        onConfirm = { confirmedEndDate ->
                                            viewModel.updateTextEntryWithRollForward(name, timestamp, newText, confirmedEndDate) {
                                                // Reload entries after edit
                                                viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                                    editModeTextEntries = entries
                                                }
                                            }
                                        }
                                    )
                                    return@EditModeControlBar
                                }
                            }
                            
                            // Normal update without roll forward
                            viewModel.updateTextEntry(name, timestamp, newText)
                            // Reload entries after edit
                            viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                editModeTextEntries = entries
                            }
                        },
                        onAddTextEntry = { name, newText ->
                            // Check if this is a roll forward habit and we're viewing a past date
                            if (name in settings.rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
                                // Find the next manual date
                                val nextManualDate = settings.rollForwardManualDates[name]?.mapNotNull { dateStr ->
                                    com.example.tail.data.parseDate(dateStr)
                                }?.sorted()?.firstOrNull { it > selectedDate }
                                
                                val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()
                                
                                // Show roll forward confirmation dialog
                                rollForwardDialogState = RollForwardDialogState(
                                    habitName = name,
                                    actionType = "text",
                                    startDate = selectedDate,
                                    initialEndDate = endDate,
                                    onConfirm = { confirmedEndDate ->
                                        viewModel.setTextEntryForDateWithRollForward(name, selectedDate, newText, confirmedEndDate) {
                                            // Reload entries after add
                                            viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                                editModeTextEntries = entries
                                            }
                                        }
                                    }
                                )
                                return@EditModeControlBar
                            }
                            
                            // Normal add without roll forward
                            viewModel.setTextEntryForDate(name, selectedDate, newText) {
                                // Reload entries after add
                                viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                    editModeTextEntries = entries
                                }
                            }
                        },
                        onDeleteTextEntry = { name, timestamp ->
                            viewModel.deleteTextEntry(name, timestamp) {
                                // Reload entries after delete completes so the
                                // removed row vanishes instantly from the list.
                                viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                    editModeTextEntries = entries
                                }
                            }
                        },
                        habitNotes = settings.habitNotes,
                        onSetHabitNote = { name, note -> viewModel.setHabitNote(name, note) },
                        onToggleRollForward = { name -> viewModel.toggleRollForward(name) },
                        habitScheduleTimes = settings.habitScheduleTimes,
                        onSetHabitScheduleTime = { name, time ->
                            viewModel.setHabitScheduleTime(name, time)
                        },
                        onRestoreFromBackup = {
                            val name = editHabitName
                            if (name != null) {
                                restoreBackupHabitName = name
                                restoreBackupPicker.launch(arrayOf("application/json", "text/plain", "*/*"))
                            }
                        },
                        onRenameHabit = { oldName, newName -> viewModel.renameHabit(oldName, newName) },
                        habitAppAssociations = settings.habitAppAssociations,
                        onAddAppAssociation = { name -> appAssociationPickerHabit = name },
                        onRemoveAppAssociation = { name, pkg -> viewModel.removeHabitAppAssociation(name, pkg) },
                        onMoveAppAssociation = { name, from, to -> viewModel.moveHabitAppAssociation(name, from, to) },
                        widgetTriggerHabits = settings.widgetTriggerHabits,
                        widgetTriggerApps = settings.widgetTriggerApps,
                        onToggleWidgetTrigger = { name -> viewModel.toggleWidgetTrigger(name) },
                        onSetWidgetTriggerApp = { name -> widgetTriggerPickerHabit = name },
                        hasUsageAccess = viewModel.hasUsageAccess(),
                        onRequestUsageAccess = { viewModel.openUsageAccessSettings() },
                        widgetTimerMinutesPrimary = settings.widgetTimerMinutesPrimary,
                        onSetTimerPrimaryValue = { name, minutesPrimary ->
                            viewModel.setWidgetTimerPrimaryValue(name, minutesPrimary)
                        },
                        minutesEnabled = selectedHabitName?.let {
                            viewModel.isMinutesEnabled(it)
                        } ?: false,
                        minutesForcedByWidget = selectedHabitName?.let {
                            viewModel.isMinutesForcedByWidget(it)
                        } ?: false,
                        onToggleMinutesEnabled = { name -> viewModel.toggleMinutesEnabled(name) },
                        mediaHabits = settings.mediaHabits,
                        mediaApps = settings.mediaApps,
                        onToggleMedia = { name -> viewModel.toggleMediaHabit(name) },
                        onSetMediaApp = { name -> mediaAppPickerHabit = name },
                        hasNotificationAccess = viewModel.hasNotificationListenerAccess(),
                        onRequestNotificationAccess = { viewModel.openNotificationListenerSettings() },
                        mediaTodayShows = viewModel.mediaTodayShows.collectAsState().value,
                        onLoadMediaShows = { name -> viewModel.loadMediaTodayShows(name) },
                        onRemoveMediaShow = { name, show -> viewModel.removeMediaShowFromToday(name, show) },
                        onInvertHabit = { name -> viewModel.invertHabit(name) },
                        onGetInvertPreview = { name -> viewModel.getInvertPreview(name) }
                    )
                }
            }
        }
    }

    // Quick Capture review banner at top of screen — unrecognised captures
    if (quickCaptureReviewCount > 0 && !showQuickCaptureHistory) {
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.tertiaryContainer)
                .clickable { showQuickCaptureHistory = true }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.PhotoCamera,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (quickCaptureReviewCount == 1) "1 quick capture needs review"
                else "$quickCaptureReviewCount quick captures need review",
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                fontSize = 13.sp
            )
        }
    }

    // Quick Capture History full-screen overlay
    if (showQuickCaptureHistory) {
        Box(modifier = Modifier.fillMaxSize()) {
            QuickCaptureHistoryScreen(
                onNavigateBack = {
                    showQuickCaptureHistory = false
                    refreshQuickCaptureReviewCount()
                }
            )
        }
    }

    // Increment toast overlay at bottom of screen
    incrementToastHabit?.let { toastHabit ->
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            HabitIncrementToast(
                habitName = toastHabit,
                visible = true,
                isTimeless = incrementToastIsTimeless,
                onEditTime = {
                    // Dismiss toast and open quick editor
                    quickEditHabitName = toastHabit
                    quickEditOriginalTime = incrementToastOriginalTime
                    quickEditWasTimeless = incrementToastIsTimeless
                    incrementToastVersion++
                    incrementToastHabit = null
                },
                onTimeless = {
                    // Remove the just-recorded timestamps and mark as timeless.
                    // ALL units of the same-moment group must go (a multi-
                    // increment is N duplicate time strings), not just the
                    // last one — otherwise N-1 units linger on the schedule.
                    toastScope.launch {
                        viewModel.timestampRepo.deleteTimestampsAtTime(
                            toastHabit, selectedDate, incrementToastOriginalTime
                        )
                    }
                    incrementToastIsTimeless = true
                }
            )
        }
    }

    // Habit-ask flash overlay at bottom of screen. Shows the oldest ask that
    // has not flashed yet (movie-bridge or scheduled) exactly once; there is
    // NO auto-confirm — an unanswered ask keeps waiting in the notification
    // center (bell icon) and as a system notification until answered.
    flashAsk?.let { ask ->
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        ) {
            HabitAskFlash(
                title = ask.title,
                question = ask.question,
                metaLabel = when {
                    ask.type == HabitNotification.TYPE_MOVIE && ask.payload.isNotBlank() ->
                        "at ${ask.payload}"
                    else -> ask.habitName
                },
                visible = true,
                onConfirm = {
                    flashAsk = null
                    flashCycle++
                    viewModel.answerNotification(ask, yes = true) { entryTime ->
                        if (ask.type == HabitNotification.TYPE_MOVIE && entryTime != null) {
                            // Show the standard increment toast so the time can
                            // still be edited / made timeless, like a manual entry.
                            incrementToastVersion++
                            incrementToastHabit = ask.habitName
                            incrementToastIsTimeless = false
                            incrementToastOriginalTime = entryTime
                            val currentVersion = incrementToastVersion
                            toastScope.launch {
                                delay(3500)
                                if (incrementToastVersion == currentVersion) {
                                    incrementToastHabit = null
                                }
                            }
                        }
                    }
                },
                onDismiss = {
                    flashAsk = null
                    flashCycle++
                    viewModel.answerNotification(ask, yes = false)
                },
                onHide = {
                    // Timeout — hide without answering; the ask keeps waiting
                    // in the notification center and as a system notification.
                    flashAsk = null
                    flashCycle++
                }
            )
        }
    }

    // ── Advice banner at bottom of screen (hidden in edit/graph/schedule modes) ──
    if (!editMode && !graphMode && !scheduleMode) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            AdviceBanner(
                viewModel = adviceViewModel,
                shimmerSweep = { shimmerSweep.value },
                shimmerDirection = { shimmerDirection.value }
            )
        }
    }
    } // end Box

    // Movie-bridge detection: once per session, when the bridge is enabled
    // and a linked movie habit exists, check for an unconfirmed
    // recently-watched desktop movie. The ViewModel registers it as a
    // persistent ask (notification store + system notification); the flash
    // effect below then picks it up via consumeUnseenAskForFlash.
    LaunchedEffect(settings.bridgeEnabled, settings.bridgeMovieHabits, isLoading, moviePromptChecked) {
        if (moviePromptChecked || isLoading || !settings.bridgeEnabled) return@LaunchedEffect
        val habitName = settings.bridgeMovieHabits.firstOrNull {
            it in settings.textInputHabits
        } ?: return@LaunchedEffect
        moviePromptChecked = true
        viewModel.prepareMoviePrompt(habitName, today) { }
    }

    // One-time ask flash: consume the oldest ask whose flash has not been
    // shown yet (registered by the movie bridge or a scheduled alarm).
    // Re-runs whenever the pending set changes or a flash was closed.
    LaunchedEffect(notifications, flashCycle) {
        if (flashAsk == null && notifications.isNotEmpty()) {
            viewModel.consumeUnseenAskForFlash { ask ->
                if (ask != null) flashAsk = ask
            }
        }
    }

    // Load timestamp count when selected edit habit changes (or the viewed day changes)
    LaunchedEffect(selectedEditIndex, editMode, habits, selectedDate) {
        if (editMode && selectedEditIndex >= 0 && selectedEditIndex < habits.size) {
            val name = habits[selectedEditIndex].name
            if (name.isNotEmpty()) {
                val timestamps = viewModel.timestampRepo.getTimestampsForDay(name, selectedDate)
                selectedHabitTimestampCount = timestamps.size
            } else {
                selectedHabitTimestampCount = 0
            }
        } else {
            selectedHabitTimestampCount = 0
        }
    }

    // Timestamp editor dialog — card-based: each same-moment increment group
    // shows its time (underlined = tappable to re-time), the increment amount,
    // and any text logged at that time; the pencil edits amount/text in place
    // ── Schedule block details popup (the tapped instance) ────────────────
    scheduleDetailsBlock?.let { block ->
        ScheduleBlockDetailsDialog(
            habitName = block.habitName,
            movieTitle = block.movieTitle,
            firstTime = block.firstTime,
            lastTime = block.lastTime,
            eventCount = block.eventCount,
            spanMinutes = block.spanMinutes,
            durationMinutes = block.durationMinutes,
            amount = block.amount,
            points = viewModel.scheduleInstancePoints(block.habitName, block.amount),
            textEntries = scheduleDetailsTexts,
            onShowAllTimestamps = {
                val habitName = block.habitName
                scheduleDetailsBlock = null
                timestampScope.launch {
                    timestampEditorList = viewModel.timestampRepo
                        .getTimestampsForDay(habitName, selectedDate)
                    timestampEditorMinutes =
                        if (viewModel.isMinutesPrimaryHabit(habitName)) {
                            viewModel.timestampRepo.getMinutesForDay(habitName, selectedDate)
                        } else emptyMap()
                    timestampEditorHabitName = habitName
                    // Clear stale text entries first so the editor never
                    // briefly shows another habit's log (e.g. movie
                    // titles), then load this habit's — discarding
                    // out-of-order results.
                    editModeTextEntries = emptyList()
                    if (habitName in settings.textInputHabits) {
                        viewModel.loadTextEntriesWithTimestamps(habitName, selectedDate) { entries ->
                            if (timestampEditorHabitName == habitName) {
                                editModeTextEntries = entries
                            }
                        }
                    }
                }
            },
            onDismiss = { scheduleDetailsBlock = null }
        )
    }

    // (or jumps to the meal editor for meal habits).
    timestampEditorHabitName?.let { habitName ->
        TimestampEditorDialog(
            habitName = habitName,
            timestamps = timestampEditorList,
            textEntries = editModeTextEntries.associate { (fullTs, text) ->
                fullTs.takeLast(8) to text
            },
            isMealHabit = habitName in settings.mealHabits,
            canEditText = habitName in settings.textInputHabits,
            isMinutesPrimary = viewModel.isMinutesPrimaryHabit(habitName),
            minutesByTime = timestampEditorMinutes,
            onUpdateTimeGroup = { oldTime, newTime ->
                timestampScope.launch {
                    timestampEditorList = viewModel.timestampRepo.updateTimestampsAtTime(
                        habitName, selectedDate, oldTime, newTime
                    )
                    selectedHabitTimestampCount = timestampEditorList.size
                    // The group's per-timestamp minutes moved with it.
                    if (viewModel.isMinutesPrimaryHabit(habitName)) {
                        timestampEditorMinutes = viewModel.timestampRepo
                            .getMinutesForDay(habitName, selectedDate)
                    }
                    // Group size unchanged — no habit count adjustment needed.
                }
            },
            onDeleteTimeGroup = { time ->
                timestampScope.launch {
                    val removed = timestampEditorList.count { it == time }
                    val minutesPrimary = viewModel.isMinutesPrimaryHabit(habitName)
                    // Minutes-primary: the group's minutes leave the day total too.
                    val groupMinutes = if (minutesPrimary) {
                        timestampEditorMinutes[time] ?: removed
                    } else 0
                    timestampEditorList = viewModel.timestampRepo.deleteTimestampsAtTime(
                        habitName, selectedDate, time
                    )
                    selectedHabitTimestampCount = timestampEditorList.size
                    if (minutesPrimary) {
                        timestampEditorMinutes = viewModel.timestampRepo
                            .getMinutesForDay(habitName, selectedDate)
                        if (groupMinutes > 0) {
                            viewModel.setHabitMinutesCount(
                                habitName,
                                (viewModel.getMinutesTodayCount(habitName) - groupMinutes)
                                    .coerceAtLeast(0)
                            )
                        }
                    }
                    // Decrement the habit count to match the removed units
                    val currentHabit = habits.find { it.name == habitName }
                    if (currentHabit != null && removed > 0 &&
                        currentHabit.rawTodayCount > timestampEditorList.size
                    ) {
                        viewModel.setHabitCount(habitName, currentHabit.rawTodayCount - removed)
                    }
                }
            },
            onSetGroupAmount = { time, newAmount ->
                timestampScope.launch {
                    val before = timestampEditorList.count { it == time }
                    timestampEditorList = viewModel.timestampRepo.setTimestampCountAtTime(
                        habitName, selectedDate, time, newAmount
                    )
                    selectedHabitTimestampCount = timestampEditorList.size
                    // Keep the habit count in step with the edited amount
                    val delta = newAmount - before
                    if (delta != 0) {
                        val currentHabit = habits.find { it.name == habitName }
                        if (currentHabit != null) {
                            if (delta > 0 && currentHabit.rawTodayCount < timestampEditorList.size) {
                                viewModel.setHabitCount(habitName, timestampEditorList.size)
                            } else if (delta < 0 && currentHabit.rawTodayCount > timestampEditorList.size) {
                                viewModel.setHabitCount(habitName, currentHabit.rawTodayCount + delta)
                            }
                        }
                    }
                }
            },
            onSetGroupMinutes = { time, newMinutes ->
                timestampScope.launch {
                    val oldMinutes = timestampEditorMinutes[time]
                        ?: timestampEditorList.count { it == time }
                    timestampEditorMinutes = viewModel.timestampRepo.setMinutesAtTime(
                        habitName, selectedDate, time, newMinutes
                    )
                    // Keep the day's minutes total (minutes:<habit>) in step
                    // with the edited per-timestamp value.
                    val delta = newMinutes - oldMinutes
                    if (delta != 0) {
                        viewModel.setHabitMinutesCount(
                            habitName,
                            (viewModel.getMinutesTodayCount(habitName) + delta)
                                .coerceAtLeast(0)
                        )
                    }
                }
            },
            onUpdateText = { time, newText ->
                val fullTs = "${com.example.tail.data.dateString(selectedDate)} $time"
                fun reloadTextEntries() {
                    viewModel.loadTextEntriesWithTimestamps(habitName, selectedDate) { entries ->
                        editModeTextEntries = entries
                    }
                }
                if (newText.isBlank()) {
                    viewModel.deleteTextEntry(habitName, fullTs) { reloadTextEntries() }
                } else {
                    viewModel.updateTextEntry(habitName, fullTs, newText) { reloadTextEntries() }
                }
            },
            onEditMeal = { time ->
                // Jump to the pre-existing meal editor for the meal logged
                // closest to this increment time (same day).
                val parsedTime = runCatching { java.time.LocalTime.parse(time) }.getOrNull()
                val targetEpoch = parsedTime?.let {
                    java.time.LocalDateTime.of(selectedDate, it)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toInstant().toEpochMilli()
                }
                val logs = viewModel.mealLogsForHabit.value
                val focus = if (targetEpoch != null) {
                    logs.filter {
                        java.time.Instant.ofEpochMilli(it.anchorTime())
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate() == selectedDate
                    }.minByOrNull { kotlin.math.abs(it.anchorTime() - targetEpoch) }
                } else {
                    null
                }
                timestampEditorHabitName = null
                mealDialogFromTap = false
                mealDialogFocusLogId = focus?.id
                mealDialogHabit = habitName
            },
            onAddTimestamp = { time ->
                timestampScope.launch {
                    viewModel.timestampRepo.addTimestamp(habitName, selectedDate, time)
                    timestampEditorList = viewModel.timestampRepo.getTimestampsForDay(habitName, selectedDate)
                    selectedHabitTimestampCount = timestampEditorList.size
                    // Auto-increase the habit count so it is never lower than the
                    // number of timestamps. If the count already meets or exceeds
                    // the timestamp total it is left untouched.
                    val currentHabit = habits.find { it.name == habitName }
                    if (currentHabit != null && currentHabit.rawTodayCount < timestampEditorList.size) {
                        viewModel.setHabitCount(habitName, timestampEditorList.size)
                    }
                }
            },
            onDismiss = {
                timestampEditorHabitName = null
                // Reload the schedule timeline so it reflects any edits
                scheduleRefresh++
            }
        )
    }

    // Location edit dialog — pass the effective location (stored or assumed)
    // so the field is pre-filled even for days with no stored location.
    if (showLocationEditDialog) {
        val effectiveLocation = selectedDateLocation
            ?: viewModel.getAssumedLocationForDate(selectedDate)
        LocationEditDialog(
            currentLocation = effectiveLocation,
            suggestions = viewModel.getAllStoredLocations(),
            // Globe button inside the popup → map view (moved here from the
            // main screen's location row).
            onOpenMap = {
                showLocationEditDialog = false
                onNavigateToMap()
            },
            onConfirm = { label ->
                if (label == null) {
                    viewModel.removeLocationForDate(selectedDate)
                } else {
                    viewModel.setLocationForDate(selectedDate, label)
                }
                showLocationEditDialog = false
            },
            onDismiss = { showLocationEditDialog = false },
            onFetchCandidates = { onResult ->
                viewModel.fetchLocationCandidates(selectedDate, onResult)
            },
            onSavePreferredCandidateIndex = { index ->
                viewModel.savePreferredAutoCandidateIndex(index)
            }
        )
    }

    // Calendar picker dialog
    if (showCalendarPicker) {
        val earliestYear = remember {
            val fromLocations = viewModel.getEarliestLocationDate()?.year
            val fromHabits = viewModel.getEarliestDate(viewModel.getAllHabitNames().toSet())?.year
            listOfNotNull(fromLocations, fromHabits).minOrNull() ?: 2000
        }
        CalendarPickerDialog(
            initialDate     = selectedDate,
            getDailyTotals  = { yr, mo -> viewModel.getDailyTotals(yr, mo) },
            onDateSelected  = { date ->
                showCalendarPicker = false
                viewModel.navigateToDate(date)
            },
            onDismiss       = { showCalendarPicker = false },
            minYear         = earliestYear
        )
    }

    // Custom increment dialog
    dialogHabit?.let { habit ->
        val customAmounts = settings.customInputAmounts[habit.name]
            ?: com.example.tail.data.DEFAULT_CUSTOM_INPUT_AMOUNTS
        val recentAmounts = settings.customInputRecentAmounts[habit.name] ?: emptyList()
        IncrementDialog(
            habitName = habit.name,
            currentCount = habit.todayCount,
            quickAmounts = customAmounts,
            recentAmounts = recentAmounts,
            onConfirm = { amount ->
                viewModel.incrementHabit(habit.name, amount, recordTimestamp = isToday)
                viewModel.recordRecentIncrementAmount(habit.name, amount)
                dialogHabit = null
                // Show increment toast with edit-time option (same as normal increment)
                incrementToastVersion++
                incrementToastHabit = habit.name
                incrementToastIsTimeless = !isToday
                incrementToastOriginalTime = if (isToday) com.example.tail.data.HabitTimestampRepository.nowTime() else ""
                val currentVersion = incrementToastVersion
                toastScope.launch {
                    delay(3500)
                    if (incrementToastVersion == currentVersion) {
                        incrementToastHabit = null
                    }
                }
            },
            onDismiss = { dialogHabit = null }
        )
    }

    // Subtype increment dialog
    subtypeDialogHabit?.let { habit ->
        val subtypes = settings.habitSubtypes[habit.name] ?: emptyList()
        if (subtypes.isNotEmpty()) {
            SubtypeIncrementDialog(
                habitName = habit.name,
                subtypes = subtypes,
                currentTotal = habit.rawTodayCount,
                currentBreakdown = subtypeDialogBreakdown,
                displayLabels = settings.valueDisplayLabels[habit.name] ?: emptyMap(),
                onConfirm = { increments ->
                    viewModel.saveSubtypeIncrement(habit.name, increments)
                    subtypeDialogHabit = null
                },
                onDismiss = { subtypeDialogHabit = null }
            )
        }
    }

    // Weights input dialog (weights-type habits)
    weightsDialogHabit?.let { habit ->
        WeightsInputDialog(
            habitName = habit.name,
            defaultUnit = settings.graphWeightUnit,
            recentExercises = settings.weightsRecentExercises[habit.name] ?: emptyList(),
            onConfirm = { weightGrams, reps, machine, exerciseName ->
                viewModel.saveWeightsEntry(habit.name, weightGrams, reps, machine, exerciseName)
                weightsDialogHabit = null
            },
            onDismiss = { weightsDialogHabit = null }
        )
    }

    // Meal detail dialog
    mealDialogHabit?.let { habitName ->
        MealDetailDialog(
            habitName = habitName,
            viewModel = viewModel,
            onDismiss = {
                mealDialogHabit = null
                mealDialogFocusLogId = null
            },
            incrementAlreadyDone = mealDialogFromTap,
            selectedDate = selectedDate,
            focusLogId = mealDialogFocusLogId
        )
    }

    // Text-input dialog
    textInputDialogState?.let { state ->
        // Default time: current time for today, noon for past dates
        val initHour = if (isToday) java.time.LocalTime.now().hour else 12
        val initMinute = if (isToday) java.time.LocalTime.now().minute else 0

        TextInputDialog(
            habitName = state.habit.name,
            showOptions = state.showOptions,
            options = state.options,
            todayEntries = state.todayEntries,
            initialHour = initHour,
            initialMinute = initMinute,
            initialText = state.suggestedText,
            suggestionLabel = state.suggestionLabel,
            suggestedMinutes = state.suggestedMinutes,
            recentMovies = state.recentMovies,
            suggestionLoading = state.suggestionLoading,
            loadingMetrics = loadingMetrics,
            onConfirm = { entries, hour, minute ->
                val entryTime = java.time.LocalTime.of(hour, minute)
                // Only pass selectedDate if it's not today - for today, use current date
                val dateForEntry = if (selectedDate == today) null else selectedDate

                // Check if this is a roll forward habit and we're viewing a past date
                if (state.habit.name in settings.rollForwardHabits && dateForEntry != null) {
                    // Find the next manual date
                    val nextManualDate = settings.rollForwardManualDates[state.habit.name]?.mapNotNull { dateStr ->
                        com.example.tail.data.parseDate(dateStr)
                    }?.sorted()?.firstOrNull { it > dateForEntry }

                    val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()

                    // Show roll forward confirmation dialog
                    rollForwardDialogState = RollForwardDialogState(
                        habitName = state.habit.name,
                        actionType = "text",
                        startDate = dateForEntry,
                        initialEndDate = endDate,
                        onConfirm = { confirmedEndDate ->
                            viewModel.setTextEntriesForDateWithRollForward(state.habit.name, dateForEntry, entries, confirmedEndDate, entryTime) {
                                // Reload entries after add completes, then dismiss dialog
                                viewModel.loadTextEntriesWithTimestamps(state.habit.name, selectedDate) { _ ->
                                    // Don't reopen the dialog - just dismiss it
                                    textInputDialogState = null
                                    // Show increment toast with edit-time option
                                    incrementToastVersion++
                                    incrementToastHabit = state.habit.name
                                    incrementToastIsTimeless = !isToday
                                    incrementToastOriginalTime = if (isToday) com.example.tail.data.HabitTimestampRepository.nowTime() else ""
                                    val currentVersion = incrementToastVersion
                                    toastScope.launch {
                                        delay(3500)
                                        if (incrementToastVersion == currentVersion) {
                                            incrementToastHabit = null
                                        }
                                    }
                                }
                            }
                        }
                    )
                } else {
                    viewModel.saveTextEntries(state.habit.name, entries, dateForEntry, entryTime)
                    textInputDialogState = null
                    // Show increment toast with edit-time option
                    incrementToastVersion++
                    incrementToastHabit = state.habit.name
                    incrementToastIsTimeless = !isToday
                    incrementToastOriginalTime = if (isToday) com.example.tail.data.HabitTimestampRepository.nowTime() else ""
                    val currentVersion = incrementToastVersion
                    toastScope.launch {
                        delay(3500)
                        if (incrementToastVersion == currentVersion) {
                            incrementToastHabit = null
                        }
                    }
                }
            },
            onDismiss = { textInputDialogState = null },
            onEdit = { oldTimestamp, newText ->
                // Check if this is a roll forward habit and we're viewing a past date
                if (state.habit.name in settings.rollForwardHabits && selectedDate < java.time.LocalDate.now()) {
                    // Parse the date from the timestamp
                    val dateStr = oldTimestamp.substring(0, 10)
                    val entryDate = com.example.tail.data.parseDate(dateStr)
                    
                    if (entryDate != null) {
                        // Find the next manual date
                        val nextManualDate = settings.rollForwardManualDates[state.habit.name]?.mapNotNull { dateStr ->
                            com.example.tail.data.parseDate(dateStr)
                        }?.sorted()?.firstOrNull { it > entryDate }
                        
                        val endDate = nextManualDate?.minusDays(1) ?: java.time.LocalDate.now()
                        
                        // Show roll forward confirmation dialog
                        rollForwardDialogState = RollForwardDialogState(
                            habitName = state.habit.name,
                            actionType = "text",
                            startDate = entryDate,
                            initialEndDate = endDate,
                            onConfirm = { confirmedEndDate ->
                                viewModel.updateTextEntryWithRollForward(state.habit.name, oldTimestamp, newText, confirmedEndDate) {
                                    // Reload entries after edit completes, then dismiss dialog
                                    viewModel.loadTextEntriesWithTimestamps(state.habit.name, selectedDate) { entries ->
                                        // Don't reopen the dialog - just dismiss it
                                        textInputDialogState = null
                                    }
                                }
                            }
                        )
                        return@TextInputDialog
                    }
                }
                
                // Normal update without roll forward
                viewModel.updateTextEntry(state.habit.name, oldTimestamp, newText) {
                    // Reload entries after edit completes
                    viewModel.loadTextEntriesWithTimestamps(state.habit.name, selectedDate) { entries ->
                        textInputDialogState = state.copy(todayEntries = entries)
                    }
                }
            },
            onDelete = { timestamp ->
                viewModel.deleteTextEntry(state.habit.name, timestamp) {
                    // Reload entries after delete completes
                    viewModel.loadTextEntriesWithTimestamps(state.habit.name, selectedDate) { entries ->
                        textInputDialogState = state.copy(todayEntries = entries)
                    }
                }
            }
        )
    }

    // Add screen dialog
    if (showAddScreenDialog) {
        AddScreenDialog(
            onConfirm = { name ->
                viewModel.addScreen(name)
                showAddScreenDialog = false
            },
            onDismiss = { showAddScreenDialog = false }
        )
    }

    // Add habit dialog — triggered when user taps a placeholder in edit mode
    if (addHabitAtIndex >= 0) {
        AddHabitDialog(
            onConfirm = { name ->
                viewModel.addHabit(name, addHabitAtIndex)
                addHabitAtIndex = -1
            },
            onDismiss = { addHabitAtIndex = -1 }
        )
    }

    // App picker dialog — triggered when user taps "+ App" in edit mode
    if (addAppLinkAtIndex >= 0) {
        AppPickerDialog(
            context = context,
            onConfirm = { packageName, label ->
                viewModel.addAppLink(packageName, label, addAppLinkAtIndex)
                addAppLinkAtIndex = -1
            },
            onDismiss = { addAppLinkAtIndex = -1 }
        )
    }

    // App association picker — triggered when user taps "Add App" in the
    // app association section of edit mode for a selected habit
    if (appAssociationPickerHabit != null) {
        val habitName = appAssociationPickerHabit!!
        AppPickerDialog(
            context = context,
            onConfirm = { packageName, _ ->
                viewModel.addHabitAppAssociation(habitName, packageName)
                appAssociationPickerHabit = null
            },
            // A specific shortcut of an app is associated as an encoded entry
            onConfirmShortcut = { packageName, shortcutId, _ ->
                viewModel.addHabitAppAssociation(
                    habitName,
                    encodeShortcutEntry(packageName, shortcutId)
                )
                appAssociationPickerHabit = null
            },
            onDismiss = { appAssociationPickerHabit = null }
        )
    }

    // Widget trigger app picker — triggered when user taps "Select App" in the
    // Use Widget section of edit mode for a selected habit
    if (widgetTriggerPickerHabit != null) {
        val habitName = widgetTriggerPickerHabit!!
        AppPickerDialog(
            context = context,
            onConfirm = { packageName, _ ->
                viewModel.setWidgetTriggerApp(habitName, packageName)
                widgetTriggerPickerHabit = null
            },
            onDismiss = { widgetTriggerPickerHabit = null }
        )
    }

    // Media app picker — triggered when user taps "Select App" in the
    // Media section of edit mode for a selected habit
    if (mediaAppPickerHabit != null) {
        val habitName = mediaAppPickerHabit!!
        AppPickerDialog(
            context = context,
            onConfirm = { packageName, _ ->
                viewModel.setMediaApp(habitName, packageName)
                mediaAppPickerHabit = null
            },
            onDismiss = { mediaAppPickerHabit = null }
        )
    }

    // Long-press URL app picker — triggered when user taps "Select App" in the
    // URL section of the long-press action settings for a selected habit
    if (longPressUrlAppPickerHabit != null) {
        val habitName = longPressUrlAppPickerHabit!!
        AppPickerDialog(
            context = context,
            onConfirm = { packageName, _ ->
                viewModel.setHabitLongPressUrlApp(habitName, packageName)
                longPressUrlAppPickerHabit = null
            },
            onDismiss = { longPressUrlAppPickerHabit = null }
        )
    }

    // Multi-app launcher — triggered when long-pressing a habit with
    // multiple associated apps (single-app habits launch directly)
    if (appLauncherHabit != null) {
        val habitName = appLauncherHabit!!
        val packages = settings.habitAppAssociations[habitName] ?: emptyList()
        if (packages.isNotEmpty()) {
            AssociatedAppLauncherDialog(
                habitName = habitName,
                entries = packages,
                onLaunch = { entry ->
                    // Shortcut entries launch their specific shortcut;
                    // plain entries fall through to the app's launch intent.
                    if (!launchShortcutEntry(context, entry)) {
                        val launchIntent = context.packageManager.getLaunchIntentForPackage(entry)
                        if (launchIntent != null) {
                            context.startActivity(launchIntent)
                        }
                    }
                    appLauncherHabit = null
                },
                onDismiss = { appLauncherHabit = null }
            )
        } else {
            // Associations were cleared while dialog was pending
            appLauncherHabit = null
        }
    }

    // Rename screen dialog
    if (renamingScreenIndex >= 0) {
        val currentName = habitScreens.getOrNull(renamingScreenIndex)?.name ?: ""
        RenameScreenDialog(
            currentName = currentName,
            onConfirm = { newName ->
                viewModel.renameScreen(renamingScreenIndex, newName)
                renamingScreenIndex = -1
            },
            onDismiss = { renamingScreenIndex = -1 }
        )
    }

    // Delete habit confirmation dialog
    deleteConfirmHabitName?.let { habitName ->
        val isLink = isAppLink(habitName)
        val displayName = if (isLink) settings.appLinks[habitName] ?: habitName else habitName
        val dataDays = remember(habitName) { viewModel.getDeleteDataDayCount(habitName) }
        DeleteHabitConfirmDialog(
            habitName = displayName,
            isAppLink = isLink,
            dataDayCount = dataDays,
            onConfirm = { deleteData ->
                val idx = habits.indexOfFirst { it.name == habitName }
                if (idx >= 0) viewModel.deleteHabit(idx)
                if (deleteData && !isLink) viewModel.deleteHabitData(habitName)
                deleteConfirmHabitName = null
            },
            onDismiss = { deleteConfirmHabitName = null }
        )
    }

    // Icon picker dialog
    iconPickerHabitName?.let { habitName ->
        IconPickerDialog(
            habitName = habitName,
            currentIconName = settings.habitIcons[habitName],
            onIconSelected = { iconName ->
                viewModel.setHabitIcon(habitName, iconName)
                iconPickerHabitName = null
            },
            onDismiss = { iconPickerHabitName = null },
            viewModel = viewModel
        )
    }

    // Conditional links picker dialog
    conditionalLinksPickerHabit?.let { habitName ->
        ConditionalLinksPickerDialog(
            habitName = habitName,
            allHabitNames = viewModel.getAllHabitNames(),
            currentLinks = viewModel.getConditionalLinks(habitName),
            currentValues = viewModel.getConditionalLinkValues(habitName),
            secondaryValueHabits = settings.secondaryValueHabits,
            chessComHabitLinks = settings.chessComHabitLinks,
            valueDisplayLabels = settings.valueDisplayLabels,
            onConfirm = { links, values ->
                viewModel.setConditionalLinks(habitName, links)
                viewModel.setConditionalLinkValues(habitName, values)
                conditionalLinksPickerHabit = null
            },
            onDismiss = { conditionalLinksPickerHabit = null }
        )
    }

    // Conditional backfill confirmation dialog
    conditionalBackfillHabit?.let { habitName ->
        val backfillSources = remember(habitName) { viewModel.getConditionalSources(habitName) }
        val backfillTotal = remember(habitName) { viewModel.previewConditionalBackfillTotal(habitName) }
        ConditionalBackfillConfirmDialog(
            habitName = habitName,
            sources = backfillSources,
            totalIncrements = backfillTotal,
            onConfirm = {
                viewModel.performConditionalBackfill(habitName)
                conditionalBackfillHabit = null
            },
            onDismiss = { conditionalBackfillHabit = null }
        )
    }

    // "1 max" recalc confirmation dialog — asks whether to cap all past entries to 1
    maxOneRecalcHabit?.let { habitName ->
        val affectedDays = remember(habitName) { viewModel.previewMaxOneAffectedDays(habitName) }
        MaxOneRecalcConfirmDialog(
            habitName = habitName,
            affectedDays = affectedDays,
            onUpdatePast = {
                viewModel.toggleMaxOne(habitName)
                viewModel.applyMaxOneToHistory(habitName)
                maxOneRecalcHabit = null
            },
            onFutureOnly = {
                viewModel.toggleMaxOne(habitName)
                maxOneRecalcHabit = null
            },
            onDismiss = { maxOneRecalcHabit = null }
        )
    }

    // "1 max" restore confirmation dialog — asks whether to restore past entries from timestamps
    maxOneRestoreHabit?.let { habitName ->
        val restorableDays = remember(habitName) { viewModel.previewMaxOneRestorableDays(habitName) }
        MaxOneRestoreConfirmDialog(
            habitName = habitName,
            restorableDays = restorableDays,
            onRestore = {
                viewModel.toggleMaxOne(habitName)
                viewModel.restoreMaxOneFromTimestamps(habitName)
                maxOneRestoreHabit = null
            },
            onLeaveAsIs = {
                viewModel.toggleMaxOne(habitName)
                maxOneRestoreHabit = null
            },
            onDismiss = { maxOneRestoreHabit = null }
        )
    }

    // Quick timestamp editor dialog — opened from increment toast
    quickEditHabitName?.let { habitName ->
        QuickTimestampEditorDialog(
            habitName = habitName,
            originalTime = quickEditOriginalTime,
            onConfirm = { newTime ->
                timestampScope.launch {
                    if (quickEditWasTimeless) {
                        // Was timeless — add a new timestamp instead of updating
                        viewModel.timestampRepo.addTimestamp(habitName, selectedDate, newTime)
                    } else {
                        // Move the WHOLE same-moment group. A multi-increment of
                        // N units is stored as N duplicate time strings; moving
                        // only the last one (updateLastTimestamp) strands the
                        // other N-1 units at the original time, duplicating the
                        // event on the schedule screen.
                        viewModel.timestampRepo.updateTimestampsAtTime(
                            habitName, selectedDate, quickEditOriginalTime, newTime
                        )
                    }
                }
                quickEditHabitName = null
            },
            onDismiss = { quickEditHabitName = null }
        )
    }

    // Roll forward confirmation dialog
    rollForwardDialogState?.let { state ->
        RollForwardConfirmDialog(
            habitName = state.habitName,
            actionType = state.actionType,
            startDate = state.startDate,
            initialEndDate = state.initialEndDate,
            onConfirm = { endDate ->
                state.onConfirm(endDate)
                rollForwardDialogState = null
            },
            onDismiss = { rollForwardDialogState = null }
        )
    }

    // Restore-from-backup confirmation dialog (single habit only)
    habitRestorePreview?.let { preview ->
        HabitRestoreConfirmDialog(
            preview = preview,
            onConfirm = { viewModel.applyHabitRestore() },
            onDismiss = { viewModel.cancelHabitRestore() }
        )
    }

    // Restore-from-backup status / error toast
    LaunchedEffect(habitRestoreStatus) {
        val status = habitRestoreStatus ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(status)
        viewModel.clearHabitRestoreStatus()
    }

    // Dated-entry refresh confirmation dialog (single habit only)
    datedEntryRefreshPreview?.let { preview ->
        DatedEntryRefreshConfirmDialog(
            preview = preview,
            onConfirm = { viewModel.applyDatedEntryRefresh() },
            onDismiss = { viewModel.cancelDatedEntryRefresh() }
        )
    }

    // Dated-entry refresh status / error toast
    LaunchedEffect(datedEntryRefreshStatus) {
        val status = datedEntryRefreshStatus ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(status)
        viewModel.clearDatedEntryRefreshStatus()
    }

    // Global search dialog — clicking a result closes it (state is preserved
    // in the ViewModel), jumps to the result's date, switches to the habit's
    // screen and pulses the habit cell so the user can spot it.
    if (showSearchDialog) {
        HabitSearchDialog(
            viewModel = viewModel,
            onDismiss = { showSearchDialog = false },
            onResultClick = { result ->
                showSearchDialog = false
                result.date?.let { viewModel.navigateToDate(it) }
                val screenIdx = viewModel.screenIndexForHabit(result.habitName)
                if (screenIdx >= 0) viewModel.switchScreen(screenIdx)
                viewModel.highlightHabit(result.habitName)
            }
        )
    }

    // In-app notification center — lists every pending ask (movie-bridge
    // and scheduled). Answering here applies the effect everywhere at once.
    if (showNotificationsDialog) {
        NotificationsDialog(
            notifications = notifications,
            onAnswer = { ask, yes -> viewModel.answerNotification(ask, yes) },
            onDismiss = { showNotificationsDialog = false },
            onOpenAppStats = {
                showNotificationsDialog = false
                onNavigateToAppStats()
            }
        )
    }
}

// ── Screen tab row ────────────────────────────────────────────────────────────


@Composable
internal fun ScreenTabRow(
    screens: List<HabitScreen>,
    /** The grid's shimmer sweep + direction, mirrored onto the ghost squares. */
    shimmerSweep: (() -> Float)? = null,
    shimmerDirection: (() -> ShimmerDirection)? = null,
    activeIndex: Int,
    editMode: Boolean,
    hiddenScreenIds: Set<String>,
    onTabClick: (Int) -> Unit,
    onMoveScreenLeft: ((Int) -> Unit)? = null,
    onMoveScreenRight: ((Int) -> Unit)? = null,
    /** Reports each tab's window-space bounds (for drag-hover screen switching). */
    onTabLayout: ((index: Int, bounds: Rect) -> Unit)? = null,
    /** Shared scroll state — hoisted so a held-habit drag can auto-scroll the row. */
    scrollState: androidx.compose.foundation.ScrollState = rememberScrollState(),
    /** Reports the whole tab row's window-space bounds (edge auto-scroll zones). */
    onRowLayout: ((Rect) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (shimmerSweep != null && shimmerDirection != null) {
                    Modifier.ghostGlassSquares(
                        shimmerSweep = shimmerSweep,
                        shimmerDirection = shimmerDirection
                    )
                } else {
                    Modifier
                }
            )
            .horizontalScroll(scrollState)
            .onGloballyPositioned { coords ->
                onRowLayout?.invoke(Rect(coords.positionInWindow(), coords.size.toSize()))
            }
            .padding(horizontal = 4.dp, vertical = 1.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        screens.forEachIndexed { index, screen ->
            val isActive = index == activeIndex
            val isHidden = screen.id in hiddenScreenIds
            // Hidden screens: show a small blank clickable area when not active and not in edit mode
            if (!isActive && isHidden && !editMode) {
                TextButton(
                    onClick = { onTabClick(index) },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = Color.Transparent
                    ),
                    modifier = Modifier
                        .height(32.dp)
                        .width(24.dp)
                        .onGloballyPositioned { coords ->
                            onTabLayout?.invoke(
                                index,
                                Rect(coords.positionInWindow(), coords.size.toSize())
                            )
                        },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    // Blank — no text, just a clickable area
                }
                return@forEachIndexed
            }
            if (editMode && isActive && onMoveScreenLeft != null && index > 0) {
                ScreenTabMoveArrow(arrow = "◀", onClick = { onMoveScreenLeft(index) })
            }
            val label = when {
                editMode && isActive -> "✎ ${screen.name}"
                isHidden && isActive -> screen.name  // show name when active even if hidden
                else -> screen.name
            }
            // Each screen name is paired with a small glass square. Clicking
            // the SQUARE selects that screen. The ACTIVE screen's square is
            // bright — brighter than the top/bottom fade rows — replacing the
            // old grey oval highlight on the name.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(32.dp)
                    .onGloballyPositioned { coords ->
                        onTabLayout?.invoke(
                            index,
                            Rect(coords.positionInWindow(), coords.size.toSize())
                        )
                    }
            ) {
                Box(
                    modifier = Modifier
                        .padding(horizontal = 3.dp)
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(
                            if (isActive) {
                                Modifier
                                    .background(
                                        brush = Brush.verticalGradient(
                                            listOf(Color(0xFFD7DEE6), Color(0xFF8C98A6))
                                        )
                                    )
                                    .border(1.dp, Color(0xFFE8EEF4), RoundedCornerShape(6.dp))
                            } else {
                                Modifier
                                    .background(
                                        if (editMode && isHidden) Color(0x1A888888)
                                        else Color(0x1C9AA6B2)
                                    )
                                    .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(6.dp))
                            }
                        )
                        .clickable { onTabClick(index) }
                )
                TextButton(
                    onClick = { onTabClick(index) },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = Color.Transparent,
                        contentColor = when {
                            isActive -> Color.White
                            editMode && isHidden -> Color(0xFF555555)
                            else -> Color(0xFF888888)
                        }
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)
                ) {
                    Text(
                        text = if (editMode && isHidden && !isActive) "👁‍🗨 ${screen.name}" else label,
                        fontSize = 12.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
            if (editMode && isActive && onMoveScreenRight != null && index < screens.size - 1) {
                ScreenTabMoveArrow(arrow = "▶", onClick = { onMoveScreenRight(index) })
            }
        }
    }
}

/**
 * ◀/▶ reorder arrow shown next to the selected screen tab in edit mode.
 * A plain Text with its own tight background — material3 TextButton
 * enforces a 48dp minimum touch target, which made the highlight much
 * larger than the small arrow glyph.
 */


@Composable
internal fun ScreenTabMoveArrow(
    arrow: String,
    onClick: () -> Unit
) {
    Text(
        text = arrow,
        color = Color(0xFFFFAA00),
        fontSize = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF333300))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 7.dp)
    )
}

// ── Habit grid ────────────────────────────────────────────────────────────────

/**
 * The 8-column lazy grid. In edit mode, empty cells (placeholders) are shown as
 * clickable dashed cells. In normal mode they are invisible.
 */

// ── Edit-mode drag-to-reorder support ────────────────────────────────────────

/**
 * All mutable bookkeeping for an edit-mode drag-to-reorder gesture. Held in a
 * dedicated class (and consumed by Modifier.habitDragGesture /
 * computeDragDisplayHabits / DraggedHabitOverlay below) so the already-huge
 * HabitGridScreen composable stays under the JVM 64 KB method limit.
 */
internal class HabitDragState {
    /** The lifted habit; null while no drag is active. */
    var habit by mutableStateOf<Habit?>(null)
    /** Grid index the habit was lifted from (on [originScreen]). */
    var fromIndex by mutableIntStateOf(-1)
    /** Screen the drag started on — a drop elsewhere is a cross-screen move. */
    var originScreen by mutableIntStateOf(-1)
    /** Current landing cell (-1 = none: over the tab bar or just lifted). */
    var targetIndex by mutableIntStateOf(-1)
    /** Finger position in the grid Box's local coordinates (overlay follows). */
    var position by mutableStateOf(Offset.Zero)
    /** Size of one grid cell, for sizing the floating overlay. */
    var cellSize by mutableStateOf(IntSize.Zero)
    /** Screen-tab index currently hovered (-1 = none). */
    var hoverTab by mutableIntStateOf(-1)
    /** Pending dwell job that switches screens after TAB_DRAG_SWITCH_DWELL_MS. */
    var hoverJob: Job? = null
    /** Tab index → window-space bounds, reported by ScreenTabRow. */
    val tabBounds = mutableStateMapOf<Int, Rect>()
    /** The grid Box's origin in window coordinates (pointerInput is Box-local). */
    var gridBoxOriginInWindow by mutableStateOf(Offset.Zero)
    /** The lazy grid's origin in window coordinates (cell hit-testing). */
    var gridOriginInWindow by mutableStateOf(Offset.Zero)
    /** The screen-tab row's window bounds (edge auto-scroll while dragging). */
    var tabRowBoundsInWindow by mutableStateOf(Rect.Zero)
    /** Active tab-row edge auto-scroll direction (-1 left, +1 right, 0 none). */
    var tabScrollDir by mutableIntStateOf(0)
    /** Pending tab-row edge auto-scroll job. */
    var tabScrollJob: Job? = null

    val isActive: Boolean get() = habit != null

    fun reset() {
        hoverJob?.cancel()
        hoverJob = null
        tabScrollJob?.cancel()
        tabScrollJob = null
        tabScrollDir = 0
        habit = null
        fromIndex = -1
        originScreen = -1
        targetIndex = -1
        hoverTab = -1
    }
}

/**
 * The list the grid renders: during an active drag it applies the live shift
 * preview (displaced habits slide right; the vacated source cell and the
 * landing cell stay empty for the floating overlay), otherwise the real
 * habits. Pure function of its inputs so it only recomputes when needed.
 */


internal fun computeDragDisplayHabits(
    habits: List<Habit>,
    drag: HabitDragState,
    activeScreenIndex: Int
): List<Habit> {
    val lifted = drag.habit ?: return habits
    val toIdx = drag.targetIndex
    if (toIdx < 0) return habits
    val names = habits.map { it.name }
    val previewNames = if (drag.originScreen == activeScreenIndex) {
        vacateMovePreview(names, drag.fromIndex, toIdx)
    } else {
        vacateInsertPreview(names, toIdx)
    }
    val byName = habits.associateBy { it.name }
    return previewNames.map { name ->
        if (name.isEmpty()) Habit("") else byName[name] ?: Habit(name)
    }
}

/**
 * Edit-mode drag-to-reorder gesture. Long-press lifts a habit; dragging over
 * cells updates the live preview; hovering a screen tab for
 * TAB_DRAG_SWITCH_DWELL_MS switches screens mid-drag; releasing commits
 * (same screen → onCommitMove, other screen → onCommitCrossScreen). A
 * cancelled or missed drop reverts everything.
 */


internal fun Modifier.habitDragGesture(
    state: HabitDragState,
    gridState: LazyGridState,
    habits: State<List<Habit>>,
    activeScreen: State<Int>,
    screenCount: State<Int>,
    scope: kotlinx.coroutines.CoroutineScope,
    onSwitchScreen: (Int) -> Unit,
    onBeginDrag: (Int) -> Unit,
    onCommitMove: (Int, Int) -> Unit,
    onCommitCrossScreen: (String, Int, Int) -> Unit,
    /** The screen-tab row's scroll state (edge auto-scroll mid-drag). */
    tabScroll: androidx.compose.foundation.ScrollState
): Modifier = onGloballyPositioned { coords ->
    state.gridBoxOriginInWindow = coords.positionInWindow()
}.pointerInput(Unit) {
    detectDragGesturesAfterLongPress(
        onDragStart = { start ->
            val posInWindow = start + state.gridBoxOriginInWindow
            val index = gridCellIndexAt(gridState, posInWindow - state.gridOriginInWindow)
            val habit = habits.value.getOrNull(index)
            if (habit != null && habit.name.isNotEmpty()) {
                state.habit = habit
                state.fromIndex = index
                state.originScreen = activeScreen.value
                state.targetIndex = index
                state.hoverTab = -1
                state.position = start
                state.cellSize = gridState.layoutInfo
                    .visibleItemsInfo.firstOrNull()?.size ?: IntSize.Zero
                onBeginDrag(index)
            }
        },
        onDrag = { change, _ ->
            change.consume()
            if (state.habit == null) return@detectDragGesturesAfterLongPress
            state.position = change.position
            val posInWindow = change.position + state.gridBoxOriginInWindow
            // Hovering a screen tab? Dwell there to switch screens.
            val hoveredTab = state.tabBounds.entries.firstOrNull {
                it.key < screenCount.value &&
                    it.value.contains(posInWindow)
            }?.key ?: -1
            if (hoveredTab != state.hoverTab) {
                state.hoverTab = hoveredTab
                state.hoverJob?.cancel()
                if (hoveredTab >= 0 && hoveredTab != activeScreen.value) {
                    state.hoverJob = scope.launch {
                        delay(TAB_DRAG_SWITCH_DWELL_MS)
                        if (state.hoverTab == hoveredTab && state.habit != null &&
                            hoveredTab != activeScreen.value
                        ) {
                            // Clear the preview; the new screen's habit list
                            // arrives (from cache) and the preview resumes
                            // once the finger moves.
                            state.targetIndex = -1
                            onSwitchScreen(hoveredTab)
                        }
                    }
                }
            }
            val row = state.tabRowBoundsInWindow
            val inTabBand = row != Rect.Zero &&
                posInWindow.y >= row.top && posInWindow.y <= row.bottom
            if (inTabBand) {
                // Auto-scroll the tab row when the finger parks at its far
                // left/right edge, so off-screen tabs can be reached mid-drag.
                val edge = 56f
                val dir = when {
                    posInWindow.x <= row.left + edge -> -1
                    posInWindow.x >= row.right - edge -> 1
                    else -> 0
                }
                if (dir != state.tabScrollDir) {
                    state.tabScrollDir = dir
                    state.tabScrollJob?.cancel()
                    if (dir != 0) {
                        state.tabScrollJob = scope.launch {
                            while (true) {
                                val next = (tabScroll.value + dir * 14).coerceIn(0, tabScroll.maxValue)
                                if (next == tabScroll.value) break
                                tabScroll.scrollTo(next)
                                delay(16)
                            }
                        }
                    }
                }
                // Over the tab bar — shifted habits revert home.
                state.targetIndex = -1
                return@detectDragGesturesAfterLongPress
            }
            if (state.tabScrollDir != 0) {
                state.tabScrollDir = 0
                state.tabScrollJob?.cancel()
            }
            // Only hit-test cells while the finger is over the grid area —
            // the nearest-cell fallback would otherwise grab top-row cells
            // while hovering the header above the grid.
            if (posInWindow.y < state.gridBoxOriginInWindow.y) {
                state.targetIndex = -1
                return@detectDragGesturesAfterLongPress
            }
            val index = gridCellIndexAt(gridState, posInWindow - state.gridOriginInWindow)
            if (index >= 0) state.targetIndex = index
        },
        onDragEnd = {
            val habit = state.habit
            val fromIdx = state.fromIndex
            val toIdx = state.targetIndex
            val originScreen = state.originScreen
            state.reset()
            if (habit != null && toIdx >= 0) {
                if (activeScreen.value == originScreen) {
                    onCommitMove(fromIdx, toIdx)
                } else {
                    onCommitCrossScreen(habit.name, activeScreen.value, toIdx)
                }
            } else if (habit != null && originScreen >= 0 &&
                originScreen != activeScreen.value
            ) {
                // Dropped on the tab bar after crossing screens —
                // revert: jump back to the drag's origin screen.
                onSwitchScreen(originScreen)
            }
        },
        onDragCancel = {
            val originScreen = state.originScreen
            state.reset()
            if (originScreen >= 0 && originScreen != activeScreen.value) {
                onSwitchScreen(originScreen)
            }
        }
    )
}

/**
 * The lifted habit floating under the finger during an edit-mode drag. Box
 * children aren't clipped, so it also draws over the screen tab bar while its
 * vacated landing cell pulses cyan in the grid below.
 */


@Composable
internal fun DraggedHabitOverlay(
    state: HabitDragState,
    customIconOverrides: Map<String, String>,
    aiIconRepo: AiIconRepository?
) {
    val habit = state.habit ?: return
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .offset(
                x = with(density) { (state.position.x - state.cellSize.width / 2f).toDp() },
                y = with(density) { (state.position.y - state.cellSize.height / 2f).toDp() }
            )
            .size(
                width = with(density) { state.cellSize.width.toDp() },
                height = with(density) { state.cellSize.height.toDp() }
            )
            .graphicsLayer {
                scaleX = 1.15f
                scaleY = 1.15f
                alpha = 0.92f
            }
    ) {
        HabitButton(
            habit = habit,
            onClick = {},
            onLongClick = {},
            editMode = true,
            isSelected = true,
            customIconOverrides = customIconOverrides,
            aiIconRepo = aiIconRepo,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * Maps a point (relative to the lazy grid's origin) to a grid cell index
 * using the grid's layout info. Falls back to the nearest visible cell so
 * the drag preview keeps updating while the finger travels between cells.
 */


internal fun gridCellIndexAt(gridState: LazyGridState, positionInGrid: Offset): Int {
    val visible = gridState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return -1
    // Exact containment first.
    visible.forEach { item ->
        val dx = positionInGrid.x - item.offset.x
        val dy = positionInGrid.y - item.offset.y
        if (dx >= 0f && dy >= 0f && dx < item.size.width && dy < item.size.height) {
            return item.index
        }
    }
    // Between cells / past the last row: nearest cell by center distance.
    var bestIndex = -1
    var bestDistance = Float.MAX_VALUE
    visible.forEach { item ->
        val cx = item.offset.x + item.size.width / 2f
        val cy = item.offset.y + item.size.height / 2f
        val ddx = positionInGrid.x - cx
        val ddy = positionInGrid.y - cy
        val d = ddx * ddx + ddy * ddy
        if (d < bestDistance) {
            bestDistance = d
            bestIndex = item.index
        }
    }
    return bestIndex
}

/**
 * Live drag preview for a same-screen move: mirrors applyMove's shift-right
 * semantics but leaves BOTH the vacated source cell and the target cell
 * empty — the dragged habit itself floats under the finger as an overlay.
 */


internal fun vacateMovePreview(names: List<String>, fromIdx: Int, toIdx: Int): List<String> {
    if (fromIdx == toIdx || fromIdx !in names.indices) return names
    val cur = names.toMutableList()
    while (cur.size <= toIdx) cur.add("")
    cur[fromIdx] = ""
    if (cur[toIdx].isNotEmpty()) {
        var emptySlot = -1
        for (i in toIdx until cur.size) {
            if (cur[i].isEmpty()) {
                emptySlot = i
                break
            }
        }
        if (emptySlot < 0) {
            cur.add("")
            emptySlot = cur.size - 1
        }
        for (i in emptySlot downTo toIdx + 1) cur[i] = cur[i - 1]
        cur[toIdx] = ""
    }
    return cur
}

/**
 * Live drag preview for a cross-screen drop: the dragged habit (not in
 * [names] yet) will land at [toIdx]; displaced habits shift right and the
 * landing cell is left empty for the floating overlay.
 */


internal fun vacateInsertPreview(names: List<String>, toIdx: Int): List<String> {
    val cur = names.toMutableList()
    while (cur.size <= toIdx) cur.add("")
    if (cur[toIdx].isNotEmpty()) {
        var emptySlot = -1
        for (i in toIdx until cur.size) {
            if (cur[i].isEmpty()) {
                emptySlot = i
                break
            }
        }
        if (emptySlot < 0) {
            cur.add("")
            emptySlot = cur.size - 1
        }
        for (i in emptySlot downTo toIdx + 1) cur[i] = cur[i - 1]
        cur[toIdx] = ""
    }
    return cur
}


@Composable
internal fun HabitGrid(
    habits: List<Habit>,
    editMode: Boolean,
    graphMode: Boolean = false,
    /** Habit name whose cell should pulse (e.g. after a search-result jump). */
    highlightedHabit: String? = null,
    graphSelectedHabits: Set<String> = emptySet(),
    selectedEditIndex: Int,
    movePendingSourceIndex: Int = -1,
    /** Grid index of the live drag-and-drop landing cell (-1 = none). */
    dragTargetIndex: Int = -1,
    /** Shared grid state — the drag gesture hit-tests cells via its layout info. */
    gridState: LazyGridState = rememberLazyGridState(),
    /** Reports the lazy grid's origin in window coordinates (drag hit-testing). */
    onGridLayout: ((Offset) -> Unit)? = null,
    customIconOverrides: Map<String, String> = emptyMap(),
    disabledHabits: Set<String> = emptySet(),
    aiIconRepo: AiIconRepository? = null,
    /** Habits with an AI icon generation in flight (tile shows a spinner). */
    aiIconPendingHabits: Set<String> = emptySet(),
    garminHabitLinks: Map<String, String> = emptyMap(),
    appLinks: Map<String, String> = emptyMap(),
    habitAppAssociations: Map<String, List<String>> = emptyMap(),
    /** Meal habits (camera/details long-press actions, meal dialog on tap). */
    mealHabits: Set<String> = emptySet(),
    /** Weights-type habits (weights input dialog on tap, 🏋️ corner badge). */
    weightsHabits: Set<String> = emptySet(),
    /** Movie-bridge-linked habits (auto-filled from the tail_bridge watcher). */
    bridgeMovieHabits: Set<String> = emptySet(),
    /** Map of habit name → chess.com time control for chess.com-linked habits. */
    chessComHabitLinks: Map<String, String> = emptyMap(),
    /** Map of habit name → configured long-press action string. */
    habitLongPressActions: Map<String, String> = emptyMap(),
    /** Map of habit name → URL opened on long-press (LONG_PRESS_URL action). */
    habitLongPressUrls: Map<String, String> = emptyMap(),
    onHabitClick: (Habit, Int) -> Unit,
    onHabitLongClick: (Habit) -> Unit,
    onPlaceholderClick: (Int) -> Unit,
    /** Current idle-shimmer sweep progress (0..1); null disables the shimmer. */
    shimmerSweep: (() -> Float)? = null,
    /** Current idle-shimmer direction; null disables the shimmer. */
    shimmerDirection: (() -> ShimmerDirection)? = null
) {
    // Build a list of TOTAL_CELLS nullable items (null = placeholder).
    // Habits with an empty name are embedded placeholders (moved to another screen) —
    // treat them as null so the grid renders a placeholder cell in their position.
    val cells: List<Habit?> = buildList {
        habits.forEach { habit -> add(if (habit.name.isEmpty()) null else habit) }
        repeat(TOTAL_CELLS - habits.size) { add(null) }
    }

    val isMovePending = movePendingSourceIndex >= 0

    LazyVerticalGrid(
        columns = GridCells.Fixed(GRID_COLUMNS),
        state = gridState,
        modifier = Modifier
            .fillMaxSize()
            // Ghost squares continue behind the grid itself, fading out over
            // the first rows and fading back in over the last rows (toward the
            // advice banner). Only visible through empty cells / gaps.
            .then(
                if (shimmerSweep != null && shimmerDirection != null) {
                    Modifier.ghostGlassSquares(
                        shimmerSweep = shimmerSweep,
                        shimmerDirection = shimmerDirection,
                        isGridAnchor = true
                    )
                } else {
                    Modifier
                }
            )
            .padding(4.dp)
            .onGloballyPositioned { coords ->
                onGridLayout?.invoke(coords.positionInWindow())
            }
    ) {
        itemsIndexed(cells) { index, habit ->
            // This cell's shimmer alpha. The sweep progress and direction
            // are both read inside the draw lambda, so a direction change
            // between the forward and return legs only invalidates draw.
            val cellShimmer: (() -> Float)? = shimmerSweep?.let { sweep ->
                shimmerDirection?.let { direction ->
                    val row = index / GRID_COLUMNS
                    val col = index % GRID_COLUMNS
                    { idleShimmerAlpha(sweep(), direction().u(row, col)) }
                }
            }
            if (habit != null) {
                val isEditSelected = editMode && index == selectedEditIndex
                val isGraphSelected = graphMode && habit.name in graphSelectedHabits
                val isMovePendingSource = editMode && index == movePendingSourceIndex
                if (isAppLink(habit.name)) {
                    // App link cell — render with AppLinkButton
                    AppLinkButton(
                        appLinkKey = habit.name,
                        label = appLinks[habit.name] ?: "",
                        onClick = { onHabitClick(habit, index) },
                        // No cell-level long-press in edit mode — the grid's
                        // long-press-drag gesture must always win.
                        onLongClick = if (editMode) null else {
                            { onHabitLongClick(habit) }
                        },
                        modifier = Modifier.padding(2.dp),
                        editMode = editMode,
                        isSelected = isEditSelected,
                        isMovePendingSource = isMovePendingSource,
                        isMovePendingTarget = isMovePending && !isMovePendingSource && editMode,
                        shimmerAlpha = cellShimmer
                    )
                } else {
                    // Effective long-press action — mirrors the grid's long-press handler:
                    // a URL action without a configured URL falls back to the app behaviour.
                    val longPressUrl = habitLongPressUrls[habit.name]
                    val effectiveAction = com.example.tail.data.effectiveLongPressAction(
                        habitLongPressActions[habit.name]
                    ).let { effective ->
                        if (effective == com.example.tail.data.LONG_PRESS_URL && longPressUrl.isNullOrBlank())
                            com.example.tail.data.LONG_PRESS_APP else effective
                    }
                    // Tiny corner badge for special (integration-linked) habits
                    val specialBadge = when {
                        habit.name in bridgeMovieHabits -> HabitSpecialBadge.MOVIE
                        habit.name in mealHabits -> HabitSpecialBadge.MEAL
                        habit.name in garminHabitLinks -> HabitSpecialBadge.GARMIN
                        habit.name in chessComHabitLinks -> HabitSpecialBadge.CHESS
                        habit.name in weightsHabits -> HabitSpecialBadge.WEIGHTS
                        else -> null
                    }
                    HabitButton(
                        habit = habit,
                        onClick = { onHabitClick(habit, index) },
                        // No cell-level long-press in edit mode — the grid's
                        // long-press-drag gesture must always win.
                        onLongClick = if (editMode) null else {
                            { onHabitLongClick(habit) }
                        },
                        modifier = Modifier.padding(2.dp),
                        editMode = editMode,
                        isHighlighted = habit.name == highlightedHabit,
                        isSelected = isEditSelected || isGraphSelected,
                        isMovePendingSource = isMovePendingSource,
                        isMovePendingTarget = isMovePending && !isMovePendingSource && editMode,
                        customIconOverrides = customIconOverrides,
                        graphMode = graphMode,
                        isGraphSelected = isGraphSelected,
                        isDisabled = habit.name in disabledHabits,
                        isAiIconGenerating = habit.name in aiIconPendingHabits,
                        aiIconRepo = aiIconRepo,
                        garminHabitLinks = garminHabitLinks,
                        hasAppAssociation = effectiveAction == com.example.tail.data.LONG_PRESS_APP &&
                            habit.name in habitAppAssociations,
                        hasLongPressUrl = effectiveAction == com.example.tail.data.LONG_PRESS_URL,
                        specialBadge = specialBadge,
                        shimmerAlpha = cellShimmer
                    )
                }
            } else if (editMode) {
                // In edit mode, placeholders are selectable cells
                PlaceholderCell(
                    isSelected = index == selectedEditIndex,
                    isMovePendingTarget = isMovePending || index == dragTargetIndex,
                    onClick = { onPlaceholderClick(index) },
                    modifier = Modifier.padding(2.dp)
                )
            } else {
                Box(modifier = Modifier.aspectRatio(1f))
            }
        }
    }
}

/**
 * A placeholder cell shown in edit mode. Tapping selects it (orange highlight).
 * When [isMovePendingTarget] is true the cell pulses cyan to invite a drop.
 */


@Composable
internal fun PlaceholderCell(
    isSelected: Boolean,
    isMovePendingTarget: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor = when {
        isSelected -> Color(0xFF3A2000)
        isMovePendingTarget -> Color(0xFF003A3A)
        else -> Color(0xFF0D0D0D)
    }
    val textColor = when {
        isSelected -> Color(0xFFFFAA00)
        isMovePendingTarget -> Color(0xFF44FFFF)
        else -> Color(0xFF2A2A2A)
    }
    val text = when {
        isSelected -> "+"
        isMovePendingTarget -> "→"
        else -> "·"
    }
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .background(color = bgColor, shape = RoundedCornerShape(4.dp))
            .then(
                if (isMovePendingTarget) Modifier.border(1.dp, Color(0xFF44FFFF), RoundedCornerShape(4.dp))
                else Modifier
            )
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = if (isSelected || isMovePendingTarget) 18.sp else 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Edit-mode control section shown when an app link cell is selected.
 * Extracted from [EditModeControlBar] to keep the parent composable under the
 * JVM method-size limit.
 */


@Composable
internal fun AppLinkEditSection(
    selectedHabitName: String,
    onDeleteHabit: (String) -> Unit
) {

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🔗 ${appLinkPackageName(selectedHabitName) ?: selectedHabitName}",
            color = Color(0xFF66CCFF),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Button(
            onClick = { onDeleteHabit(selectedHabitName) },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A00)),
            modifier = Modifier.height(32.dp)
        ) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Remove app link",
                tint = Color(0xFFFF6644),
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Remove", fontSize = 11.sp, color = Color(0xFFFF6644))
        }
    }
    // (Reordering moved to long-press-drag — drag the app link up to the
    // screen tab bar to move it between screens.)
}

/**
 * A single row showing an associated app in the edit-mode app association list.
 * Shows the app icon, label, and up/down/remove controls for reordering.
 */
/**
 * "Use Widget" section for a habit in edit mode.
 *
 * When enabled, the user picks a trigger app. Whenever that app is in the
 * foreground, the floating bubble widget appears over it; when the app is
 * left, the bubble disappears.
 */
