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
import com.example.tail.data.backup.HabitRestorePreview
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.example.tail.data.appIconNameOf
import com.example.tail.data.isAppIconName
import com.example.tail.data.isTextIconName
import com.example.tail.data.renderTextIconBitmap
import com.example.tail.data.textIconCharOf
import com.example.tail.data.textIconNameOf
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput

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
    val suggestedMinutes: Int? = null
)

// Grid is 8 columns × 10 rows = 80 cells
private const val GRID_COLUMNS = 8
private const val TOTAL_CELLS = 80

private val DISPLAY_DATE_FMT = DateTimeFormatter.ofPattern("EEE MMM d")

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
    onNavigateToMap: () -> Unit = {}
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
    val garminMonthlyData by viewModel.garminMonthlyData.collectAsState()
    val githubSyncStatus by viewModel.githubSyncStatus.collectAsState()
    val highlightedHabit by viewModel.highlightedHabit.collectAsState()
    val notifications by viewModel.notifications.collectAsState()
    val pendingNotifications = notifications.size
    val context = LocalContext.current

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

    // Location edit dialog state
    var showLocationEditDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    var dialogHabit by remember { mutableStateOf<Habit?>(null) }
    // Subtype increment dialog state
    var subtypeDialogHabit by remember { mutableStateOf<Habit?>(null) }
    var subtypeDialogBreakdown by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
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
    // Text entries for the currently selected edit-mode habit (for view/edit in edit bar)
    var editModeTextEntries by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
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

    // Show errors as snackbar
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage!!)
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
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
                    containerColor = MaterialTheme.colorScheme.surface
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
                                        Badge { Text("$pendingNotifications", fontSize = 9.sp) }
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
                        .padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = locationLabel,
                        color = if (selectedDateLocation != null) Color(0xFFAAAAAA) else Color(0xFF666666),
                        fontSize = 11.sp,
                        modifier = Modifier
                            .weight(1f)
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { showLocationEditDialog = true }
                            .padding(vertical = 3.dp)
                    )
                    // Globe icon — positioned under the Settings icon in the top bar.
                    IconButton(
                        onClick = onNavigateToMap,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Image(
                            painter = painterResource(id = com.example.tail.R.drawable.globe),
                            contentDescription = "World map timeline",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Screen tabs — shown when multiple screens exist (hidden in
            // landscape and in schedule mode, which aggregates all screens)
            if (habitScreens.size > 1 && !isLandscape && !scheduleMode) {
                ScreenTabRow(
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
                    } else null
                )
            }

            if (isLoading) {
                // "The Orrery" — triple-metric loading animation. The monthly
                // average drives the core form & colour, the weekly average the
                // orbital halo, today's points the central spark. Reads the
                // retained loadingMetrics StateFlow (not the stale habits list)
                // so the tiers are correct even mid-load.
                Box(modifier = Modifier.fillMaxSize()) {
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
                    timestampRepo = viewModel.timestampRepo,
                    onEventClick = { habitName ->
                        timestampScope.launch {
                            timestampEditorList = viewModel.timestampRepo
                                .getTimestampsForDay(habitName, selectedDate)
                            timestampEditorHabitName = habitName
                            // Load any text logged at those times so the
                            // editor popup can show/edit it.
                            if (habitName in settings.textInputHabits) {
                                viewModel.loadTextEntriesWithTimestamps(habitName, selectedDate) { entries ->
                                    editModeTextEntries = entries
                                }
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
                ) {
                    HabitGrid(
                        habits = habits,
                        editMode = editMode,
                        graphMode = graphMode,
                        highlightedHabit = highlightedHabit,
                        graphSelectedHabits = graphSelectedHabits,
                        selectedEditIndex = selectedEditIndex,
                        movePendingSourceIndex = movePendingSourceIndex,
                        customIconOverrides = settings.habitIcons,
                        disabledHabits = settings.disabledHabits,
                        aiIconRepo = if (settings.aiIconsEnabled) viewModel.getAiIconRepo() else null,
                        garminHabitLinks = settings.garminHabitLinks,
                        appLinks = settings.appLinks,
                        habitAppAssociations = settings.habitAppAssociations,
                        mealHabits = settings.mealHabits,
                        bridgeMovieHabits = if (settings.bridgeEnabled) settings.bridgeMovieHabits else emptySet(),
                        chessComHabitLinks = settings.chessComHabitLinks,
                        habitLongPressActions = settings.habitLongPressActions,
                        habitLongPressUrls = settings.habitLongPressUrls,
                        onHabitClick = { habit, index ->
                            when {
                                isAppLink(habit.name) -> {
                                    if (editMode) {
                                        viewModel.selectEditHabit(index)
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
                                graphMode -> viewModel.toggleGraphHabitSelection(habit.name)
                                editMode -> viewModel.selectEditHabit(index)
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
                                habit.name in settings.textInputHabits -> {
                                    val showOpts = habit.name in settings.textInputOptionsHabits
                                    val isMovieLinked = habit.name in settings.bridgeMovieHabits &&
                                        settings.bridgeEnabled

                                    // Helper: build and show the dialog state
                                    fun showDialog(
                                        suggestedText: String = "",
                                        suggestionLabel: String = "",
                                        suggestedMinutes: Int? = null
                                    ) {
                                        viewModel.loadTextEntriesWithTimestamps(habit.name, selectedDate) { todayEntries ->
                                            if (showOpts) {
                                                viewModel.loadTextOptions(habit.name) { opts ->
                                                    textInputDialogState = TextInputDialogState(
                                                        habit = habit,
                                                        showOptions = true,
                                                        options = opts,
                                                        todayEntries = todayEntries,
                                                        suggestedText = suggestedText,
                                                        suggestionLabel = suggestionLabel,
                                                        suggestedMinutes = suggestedMinutes
                                                    )
                                                }
                                            } else {
                                                textInputDialogState = TextInputDialogState(
                                                    habit = habit,
                                                    showOptions = false,
                                                    options = emptyList(),
                                                    todayEntries = todayEntries,
                                                    suggestedText = suggestedText,
                                                    suggestionLabel = suggestionLabel,
                                                    suggestedMinutes = suggestedMinutes
                                                )
                                            }
                                        }
                                    }

                                    if (isMovieLinked) {
                                        // Fetch the latest movie from the desktop bridge.
                                        // Exclude titles already logged today so we suggest the next one.
                                        viewModel.loadTextEntriesWithTimestamps(habit.name, selectedDate) { todayEntries ->
                                            val excludeTitles = todayEntries.map { it.second }
                                            viewModel.fetchMovieSuggestion(excludeTitles) { movie ->
                                                if (movie != null) {
                                                    val label = buildString {
                                                        append("🎬 Suggested from desktop")
                                                        if (movie.lastWatched.isNotBlank()) {
                                                            append(" — watched ${movie.lastWatched.take(10)}")
                                                        }
                                                    }
                                                    // Pre-fill the text with the movie title; the file
                                                    // duration (from ffprobe) goes into the separate,
                                                    // wheel-editable Length field of the dialog.
                                                    showDialog(
                                                        suggestedText = movie.title,
                                                        suggestionLabel = label,
                                                        suggestedMinutes = movie.totalWatchMin?.takeIf { it > 0 }
                                                    )
                                                } else {
                                                    // Bridge unreachable or no data — show normal dialog
                                                    showDialog()
                                                }
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
                                    
                                    // Check if this is a roll forward habit and we're viewing a past date
                                    if (habit.name in settings.rollForwardHabits && !isToday) {
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
                                        // Open the configured URL — inside the chosen app
                                        // when one is set, otherwise in the default browser.
                                        val urlIntent = android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(longPressUrl)
                                        )
                                        val urlApp = settings.habitLongPressUrlApps[habit.name]
                                        if (!urlApp.isNullOrBlank()) urlIntent.setPackage(urlApp)
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
                                                            android.net.Uri.parse(longPressUrl)
                                                        )
                                                    )
                                                } catch (_: Exception) {
                                                    // No handler at all — ignore
                                                }
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
                        }
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
                        movePending = movePendingSourceIndex >= 0,
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
                        onStartMove = { viewModel.startMoveMode() },
                        onAddHabit = { addHabitAtIndex = selectedEditIndex },
                        onAddAppLink = { addAppLinkAtIndex = selectedEditIndex },
                        onMoveToScreen = { viewModel.moveHabitToScreen(it) },
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
                                timestampEditorHabitName = name
                            }
                            // Refresh text entries so the timestamp cards can show
                            // the text logged at each increment time.
                            if (name in settings.textInputHabits) {
                                viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                    editModeTextEntries = entries
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
            AdviceBanner(viewModel = adviceViewModel)
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
            onUpdateTimeGroup = { oldTime, newTime ->
                timestampScope.launch {
                    timestampEditorList = viewModel.timestampRepo.updateTimestampsAtTime(
                        habitName, selectedDate, oldTime, newTime
                    )
                    selectedHabitTimestampCount = timestampEditorList.size
                    // Group size unchanged — no habit count adjustment needed.
                }
            },
            onDeleteTimeGroup = { time ->
                timestampScope.launch {
                    val removed = timestampEditorList.count { it == time }
                    timestampEditorList = viewModel.timestampRepo.deleteTimestampsAtTime(
                        habitName, selectedDate, time
                    )
                    selectedHabitTimestampCount = timestampEditorList.size
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
            onDismiss = { showNotificationsDialog = false }
        )
    }
}

// ── Screen tab row ────────────────────────────────────────────────────────────

@Composable
private fun ScreenTabRow(
    screens: List<HabitScreen>,
    activeIndex: Int,
    editMode: Boolean,
    hiddenScreenIds: Set<String>,
    onTabClick: (Int) -> Unit,
    onMoveScreenLeft: ((Int) -> Unit)? = null,
    onMoveScreenRight: ((Int) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
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
                    modifier = Modifier.height(32.dp).width(24.dp),
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
            TextButton(
                onClick = { onTabClick(index) },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (isActive) Color(0xFF555555)
                        else if (editMode && isHidden) Color(0xFF1A1A1A)
                        else Color.Transparent,
                    contentColor = if (isActive) Color.White
                        else if (editMode && isHidden) Color(0xFF555555)
                        else Color(0xFF888888)
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = if (editMode && isHidden && !isActive) "👁‍🗨 ${screen.name}" else label,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
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
private fun ScreenTabMoveArrow(
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
@Composable
private fun HabitGrid(
    habits: List<Habit>,
    editMode: Boolean,
    graphMode: Boolean = false,
    /** Habit name whose cell should pulse (e.g. after a search-result jump). */
    highlightedHabit: String? = null,
    graphSelectedHabits: Set<String> = emptySet(),
    selectedEditIndex: Int,
    movePendingSourceIndex: Int = -1,
    customIconOverrides: Map<String, String> = emptyMap(),
    disabledHabits: Set<String> = emptySet(),
    aiIconRepo: AiIconRepository? = null,
    garminHabitLinks: Map<String, String> = emptyMap(),
    appLinks: Map<String, String> = emptyMap(),
    habitAppAssociations: Map<String, List<String>> = emptyMap(),
    /** Meal habits (camera/details long-press actions, meal dialog on tap). */
    mealHabits: Set<String> = emptySet(),
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
    onPlaceholderClick: (Int) -> Unit
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
        modifier = Modifier
            .fillMaxSize()
            .padding(4.dp)
    ) {
        itemsIndexed(cells) { index, habit ->
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
                        onLongClick = { onHabitLongClick(habit) },
                        modifier = Modifier.padding(2.dp),
                        editMode = editMode,
                        isSelected = isEditSelected,
                        isMovePendingSource = isMovePendingSource,
                        isMovePendingTarget = isMovePending && !isMovePendingSource && editMode
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
                        else -> null
                    }
                    HabitButton(
                        habit = habit,
                        onClick = { onHabitClick(habit, index) },
                        onLongClick = { onHabitLongClick(habit) },
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
                        aiIconRepo = aiIconRepo,
                        garminHabitLinks = garminHabitLinks,
                        hasAppAssociation = effectiveAction == com.example.tail.data.LONG_PRESS_APP &&
                            habit.name in habitAppAssociations,
                        hasLongPressUrl = effectiveAction == com.example.tail.data.LONG_PRESS_URL,
                        specialBadge = specialBadge
                    )
                }
            } else if (editMode) {
                // In edit mode, placeholders are selectable cells
                PlaceholderCell(
                    isSelected = index == selectedEditIndex,
                    isMovePendingTarget = isMovePending,
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
private fun PlaceholderCell(
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
private fun AppLinkEditSection(
    selectedHabitName: String,
    onDeleteHabit: (String) -> Unit,
    onStartMove: () -> Unit,
    otherScreenIndices: List<Int>,
    habitScreens: List<HabitScreen>,
    onMoveToScreen: (Int) -> Unit
) {
    var moveToScreenExpanded = remember { mutableStateOf(false) }

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
    Spacer(modifier = Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // MOVE button — tap to enter move-pending mode
        Button(
            onClick = onStartMove,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF004A4A)),
            modifier = Modifier.height(32.dp)
        ) {
            Text("↕ Move", fontSize = 11.sp, color = Color(0xFF44FFFF))
        }
        // Screen dropdown — move app link to another screen
        if (otherScreenIndices.isNotEmpty()) {
            Box {
                Button(
                    onClick = { moveToScreenExpanded.value = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003A5A)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("→ Screen ▾", fontSize = 11.sp, color = Color(0xFF88CCFF))
                }
                DropdownMenu(
                    expanded = moveToScreenExpanded.value,
                    onDismissRequest = { moveToScreenExpanded.value = false }
                ) {
                    otherScreenIndices.forEach { screenIdx ->
                        DropdownMenuItem(
                            text = { Text(habitScreens[screenIdx].name, fontSize = 13.sp) },
                            onClick = {
                                moveToScreenExpanded.value = false
                                onMoveToScreen(screenIdx)
                            }
                        )
                    }
                }
            }
        }
    }
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
@Composable
private fun WidgetTriggerSection(
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
private fun MediaSection(
    habitName: String,
    mediaHabits: Set<String>,
    mediaApps: Map<String, String>,
    onToggleMedia: (String) -> Unit,
    onSetMediaApp: (String) -> Unit,
    hasNotificationAccess: Boolean,
    onRequestNotificationAccess: () -> Unit,
    todayShows: List<HabitViewModel.MediaShowMinutes> = emptyList(),
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
private fun AssociatedAppRow(
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
private fun AssociatedAppLauncherDialog(
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
private fun MealToggleSection(
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
 * "Meal Detail" button for meal habits — opens the meal detail editor
 * (vision logging setup). Rendered at the top of the SETTINGS section in
 * [EditModeControlBar] so it is immediately visible instead of buried in
 * the special-habit-types drawer under the meal toggle.
 */
@Composable
private fun MealDetailButton(
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
private fun CameraToggleSection(
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
private fun LongPressActionSection(
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
            placeholder = { Text("https://example.com", fontSize = 11.sp, color = Color(0xFF666666)) },
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
private fun HabitToggleSection(
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

    // ── Secondary value toggle ──────────────────────────────────────────
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "Secondary value", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isSecondaryValue) "Tracks a second value alongside points"
                       else "Single value only",
                color = if (isSecondaryValue) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isSecondaryValue,
            onCheckedChange = { onToggleSecondaryValue(habitName) },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF66BB6A),
                checkedTrackColor = Color(0xFF2E7D32),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    Spacer(modifier = Modifier.height(6.dp))

    // ── Secondary value fallback toggle ─────────────────────────────────
    // Only meaningful when secondary value is enabled
    if (isSecondaryValue) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(text = "Fallback to secondary", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Text(
                    text = if (isSecondaryValueFallback) "Second value used for points when primary is 0"
                           else "Primary value only for points",
                    color = if (isSecondaryValueFallback) Color(0xFF66BB6A) else Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = isSecondaryValueFallback,
                onCheckedChange = { onToggleSecondaryValueFallback(habitName) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color(0xFF66BB6A),
                    checkedTrackColor = Color(0xFF2E7D32),
                    uncheckedThumbColor = Color(0xFF888888),
                    uncheckedTrackColor = Color(0xFF333333)
                )
            )
        }

        Spacer(modifier = Modifier.height(6.dp))
    }

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
private fun PrimaryValueSection(
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
private fun MinutesToggleSection(
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
private fun ValueLabelRow(
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
private fun ValueLabelsSection(
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
private fun PrimaryValuePill(
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
private fun SharableTextToggle(
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
private fun HabitScheduleSection(
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
private fun EditModeControlBar(
    selectedIndex: Int,
    selectedHabitName: String?,
    selectedHabitRawTodayCount: Int,
    selectedHabitTodayCount: Int = selectedHabitRawTodayCount,
    isPlaceholderSelected: Boolean,
    movePending: Boolean,
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
    onStartMove: () -> Unit,
    onAddHabit: () -> Unit,
    onAddAppLink: () -> Unit = {},
    onMoveToScreen: (Int) -> Unit,
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
    mediaTodayShows: List<HabitViewModel.MediaShowMinutes> = emptyList(),
    /** Called to (re)load the per-show breakdown for a media habit. */
    onLoadMediaShows: (String) -> Unit = {},
    /** Called when the user removes a show from today's media log (habitName, show). */
    onRemoveMediaShow: (String, String) -> Unit = { _, _ -> },
    /** Called when the user confirms the invert operation for a habit. */
    onInvertHabit: (String) -> Unit = {},
    /** Returns invert preview stats for a habit, or null if it has no data. */
    onGetInvertPreview: (String) -> HabitViewModel.InvertPreview? = { null }
) {
    val hasSelection = selectedIndex >= 0

    // Other screens for habit move-to-screen
    val otherScreenIndices: List<Int> = if (hasSelection && !isPlaceholderSelected && habitScreens.size > 1) {
        val currentScreen = if (selectedHabitScreenIndex >= 0) selectedHabitScreenIndex else activeScreenIndex
        habitScreens.indices.filter { it != currentScreen }
    } else emptyList()


    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 400.dp)
            .verticalScroll(rememberScrollState())
            .background(if (movePending) Color(0xFF001A1A) else Color(0xFF1A1000))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        // ── Move-pending banner (shown on top of any state when move is active) ──
        if (movePending) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "↕ Tap any cell to move \"$selectedHabitName\" there",
                    color = Color(0xFF44FFFF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Button(
                    onClick = onStartMove,  // second tap cancels
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A3A00)),
                    modifier = Modifier.height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text("Cancel", fontSize = 11.sp, color = Color(0xFFFFFF44))
                }
            }
            return@Column
        }

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
                    onDeleteHabit = onDeleteHabit,
                    onStartMove = onStartMove,
                    otherScreenIndices = otherScreenIndices,
                    habitScreens = habitScreens,
                    onMoveToScreen = onMoveToScreen
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
                    otherScreenIndices = otherScreenIndices,
                    habitScreens = habitScreens,
                    onStartMove = onStartMove,
                    onMoveToScreen = onMoveToScreen,
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
                    // Meal, Chess.com, Media, Garmin, GitHub and Movie Bridge —
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
private fun EditModeHabitHeaderRow(
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
 * Edit-mode action rows for the selected habit: the Move button with
 * the move-to-screen dropdown, and the Delete / Icon / Rename buttons
 * with the rename dialog.
 *
 * Extracted from EditModeControlBar to keep it under the JVM 64KB
 * method-size limit (hit a MethodTooLargeException after adding the
 * minutes-toggle parameters).
 */
@Composable
private fun EditModeHabitActionRows(
    selectedHabitName: String?,
    otherScreenIndices: List<Int>,
    habitScreens: List<HabitScreen>,
    onStartMove: () -> Unit,
    onMoveToScreen: (Int) -> Unit,
    onDeleteHabit: (String) -> Unit,
    onChangeIcon: (String) -> Unit,
    onRenameHabit: (String, String) -> Unit
) {
    var moveToScreenExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // MOVE button — tap to enter move-pending mode
        Button(
            onClick = onStartMove,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF004A4A)),
            modifier = Modifier.height(32.dp)
        ) {
            Text("↕ Move", fontSize = 11.sp, color = Color(0xFF44FFFF))
        }

        // Screen dropdown — move habit to another screen
        if (otherScreenIndices.isNotEmpty()) {
            Box {
                Button(
                    onClick = { moveToScreenExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003A5A)),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text("→ Screen ▾", fontSize = 11.sp, color = Color(0xFF88CCFF))
                }
                DropdownMenu(
                    expanded = moveToScreenExpanded,
                    onDismissRequest = { moveToScreenExpanded = false }
                ) {
                    otherScreenIndices.forEach { screenIdx ->
                        DropdownMenuItem(
                            text = { Text(habitScreens[screenIdx].name, fontSize = 13.sp) },
                            onClick = {
                                moveToScreenExpanded = false
                                onMoveToScreen(screenIdx)
                            }
                        )
                    }
                }
            }
        }
    }

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
private fun HabitInputModesSection(
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
@Composable
private fun EditModeValueEditorRow(
    selectedHabitName: String?,
    garminHabitLinks: Map<String, String>,
    garminMonthlyData: Map<com.example.tail.data.GarminType, Map<String, Int>>,
    garminDateOfBirth: String,
    selectedDate: java.time.LocalDate,
    habitDividers: Map<String, Int>,
    widgetTriggerApps: Map<String, String>,
    widgetTimerMinutesPrimary: Set<String>,
    mediaHabits: Set<String> = emptySet(),
    minutesEnabled: Boolean = false,
    rawTodayCount: Int,
    minutesTodayCount: Int,
    onSetCount: (String, Int) -> Unit,
    onSetMinutesCount: (String, Int) -> Unit
) {
    val habitName = selectedHabitName ?: return
    val isGarminLinked = habitName in garminHabitLinks
    val isDivider = (habitDividers[habitName] ?: 1) > 1
    val isTimerHabit = !widgetTriggerApps[habitName].isNullOrBlank()
    val isMediaHabit = habitName in mediaHabits
    val minutesPrimary = habitName in widgetTimerMinutesPrimary
    if (!(isGarminLinked || isDivider || isTimerHabit || isMediaHabit)) return

    Spacer(modifier = Modifier.height(2.dp))
    // For Garmin habits, derive the value live on every recomposition so it
    // reflects garminMonthlyData updates that arrive asynchronously (e.g. after
    // a "Test Connection" sync). Caching it in remember() keyed only on the
    // habit name would leave a stale "-" when the data lands after selection.
    val garminValueText: String = if (isGarminLinked) {
        val garminType = garminHabitLinks[habitName]?.let { GarminType.fromKey(it) }
        val rawValue: Int? = when (garminType) {
            GarminType.FITNESS_AGE_DISTANCE -> {
                // Calculate fitness age distance on-demand from FITNESS_AGE
                // Fitness age is stored as hundredths of a year (e.g., 3704 for 37.04)
                try {
                    val fitnessAgeData = garminMonthlyData[GarminType.FITNESS_AGE]
                    if (fitnessAgeData != null && garminDateOfBirth.isNotEmpty()) {
                        val fitnessAge = fitnessAgeData[selectedDate.toString()]
                        if (fitnessAge != null) {
                            val dob = java.time.LocalDate.parse(garminDateOfBirth)
                            // Calculate biological age in hundredths of a year
                            val biologicalAgeYears = java.time.temporal.ChronoUnit.YEARS.between(dob, selectedDate).toDouble()
                            val biologicalAgeHundredths = (biologicalAgeYears * 100).toInt()
                            // Distance = fitness_age - biological_age (both in hundredths of a year)
                            fitnessAge - biologicalAgeHundredths
                        } else null
                    } else null
                } catch (e: Exception) {
                    null
                }
            }
            else -> {
                val dailyValues = garminType?.let { garminMonthlyData[it] }
                dailyValues?.get(selectedDate.toString())
            }
        }
        // Format per-metric for display (e.g. distance metres → km, 1 decimal).
        rawValue?.let { garminType?.formatDisplayValue(it) ?: it.toString() } ?: "-"
    } else {
        "-"
    }
    // Which value track the editor edits (timer habits only).
    // Defaults to the habit's PRIMARY value and resets if the
    // primary value setting changes. Media habits always edit
    // their MINUTES track — minutes are what the media tracker
    // auto-records and what the user manages manually here.
    var editingMinutes by remember(habitName, minutesPrimary) {
        mutableStateOf(minutesPrimary || isMediaHabit)
    }
    var valuePickerExpanded by remember { mutableStateOf(false) }
    // The value being edited: minutes live in the first-class
    // `minutes:<habit>` slot, sessions in the habit's own slot.
    val editingValue = if (editingMinutes) minutesTodayCount else rawTodayCount
    // Editable remembered field bound to the edited value. Re-keyed on the
    // edited track so switching Minutes/Sessions reloads the other track's
    // stored value instead of keeping stale text.
    var trueValueText by remember(habitName, editingMinutes) {
        mutableStateOf(editingValue.toString())
    }
    var valueFieldFocused by remember { mutableStateOf(false) }
    // Sync when the edited value changes externally (e.g.
    // [−]/[+] buttons, bubble timer, or switching tracks).
    // While the field is focused, an EMPTY text is a legitimate mid-edit
    // state (the user just cleared it) and must NOT be coerced back to
    // "0" — that programmatic overwrite moved the cursor in front of the
    // injected "0", so the next digit typed landed as "50" instead of "5".
    val parsedTrueValue = trueValueText.toIntOrNull()
    if (!isGarminLinked && parsedTrueValue != editingValue &&
        (parsedTrueValue != null || !valueFieldFocused)
    ) {
        trueValueText = editingValue.toString()
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if ((isTimerHabit || (isDivider && minutesEnabled)) && !isGarminLinked) {
            // Multi-value habit: dropdown choosing which
            // value to set (default = primary value).
            Box {
                Button(
                    onClick = { valuePickerExpanded = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A2A3A)),
                    modifier = Modifier.height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (editingMinutes) "Minutes ▾" else "Sessions ▾",
                        fontSize = 10.sp,
                        color = Color(0xFF66CCFF)
                    )
                }
                DropdownMenu(
                    expanded = valuePickerExpanded,
                    onDismissRequest = { valuePickerExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Minutes", fontSize = 13.sp) },
                        onClick = {
                            valuePickerExpanded = false
                            editingMinutes = true
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Sessions", fontSize = 13.sp) },
                        onClick = {
                            valuePickerExpanded = false
                            editingMinutes = false
                        }
                    )
                }
            }
        } else {
            // Single-value habit: just the name of the value
            // it represents, as a plain label.
            Text(
                text = when {
                    isGarminLinked -> "garmin value:"
                    isMediaHabit -> "minutes:"
                    else -> "true value:"
                },
                color = Color(0xFFAA88FF),
                fontSize = 10.sp
            )
        }
        Spacer(modifier = Modifier.width(4.dp))
        if (isGarminLinked) {
            // Garmin value is read-only — it is derived from
            // garminMonthlyData. Render it as a plain label.
            //
            // It must NOT be an editable text field: a TextField
            // with a no-op onValueChange keeps its own internal
            // text buffer from first composition, which could latch
            // a stale value (e.g. "1") that never refreshed when the
            // real value arrived asynchronously — the user had to
            // type into it to force it to the correct number. A
            // Text always reflects the live derived value.
            Text(
                text = garminValueText,
                color = Color(0xFFAA88FF),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(64.dp)
            )
        } else {
            OutlinedTextField(
                value = trueValueText,
                onValueChange = { v: String ->
                    // Digits only, with leading zeros collapsed ("05" → "5").
                    // An empty string is allowed here: it is a mid-edit state
                    // that commits a count of 0 and stays empty until the user
                    // types the new number.
                    val digits = v.filter { it.isDigit() }
                    trueValueText = digits.toIntOrNull()?.toString() ?: ""
                    val newCount = trueValueText.toIntOrNull() ?: 0
                    if (editingMinutes) {
                        onSetMinutesCount(habitName, newCount)
                    } else {
                        onSetCount(habitName, newCount)
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                modifier = Modifier
                    .width(64.dp)
                    .onFocusChanged { valueFieldFocused = it.isFocused },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFAA88FF),
                    unfocusedTextColor = Color(0xFFAA88FF),
                    focusedBorderColor = Color(0xFFAA88FF),
                    unfocusedBorderColor = Color(0xFF664488)
                ),
                textStyle = TextStyle(
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

/**
 * Direct editor for the first-class `minutes:<habit>` slot, shown at the
 * top of the edit bar beside the value editor for habits whose value
 * editor doesn't already cover minutes (plain and divider habits).
 */
@Composable
private fun EditModeMinutesEditorRow(
    habitName: String,
    minutesToday: Int,
    onSetMinutesToday: (String, Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "minutes:",
            color = Color(0xFFAA88FF),
            fontSize = 10.sp
        )
        Spacer(modifier = Modifier.width(4.dp))
        var minutesText by remember(habitName) { mutableStateOf(minutesToday.toString()) }
        var minutesFieldFocused by remember { mutableStateOf(false) }
        // Sync when today's minutes change externally (timer, PC widget,
        // media tracker). While focused, an EMPTY text is a legitimate
        // mid-edit state and must not be coerced back to "0" (cursor jump).
        val parsedMinutes = minutesText.toIntOrNull()
        if (parsedMinutes != minutesToday && (parsedMinutes != null || !minutesFieldFocused)) {
            minutesText = minutesToday.toString()
        }
        OutlinedTextField(
            value = minutesText,
            onValueChange = { v: String ->
                val digits = v.filter { it.isDigit() }
                minutesText = digits.toIntOrNull()?.toString() ?: ""
                onSetMinutesToday(habitName, minutesText.toIntOrNull() ?: 0)
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier
                .width(64.dp)
                .onFocusChanged { minutesFieldFocused = it.isFocused },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color(0xFFAA88FF),
                unfocusedTextColor = Color(0xFFAA88FF),
                focusedBorderColor = Color(0xFFAA88FF),
                unfocusedBorderColor = Color(0xFF664488)
            ),
            textStyle = TextStyle(fontSize = 12.sp, textAlign = TextAlign.Center)
        )
    }
}

// ── Advanced section ────────────────────────────────────────────────────────

/**
 * Collapsible "Special Habit Types" section in the habit edit panel.
 *
 * Groups the integration-backed habit types — Meal, Chess.com, Media,
 * Garmin, GitHub and Movie Bridge — behind one collapsible header so the
 * edit panel stays tidy. Collapsed by default; the content lambdas are
 * only composed while expanded, so a collapsed section costs nothing.
 * Extracted from EditModeControlBar to keep it under the JVM 64KB
 * method-size limit.
 */
@Composable
private fun SpecialHabitTypesSection(
    mealContent: @Composable () -> Unit,
    chessComContent: @Composable () -> Unit,
    mediaContent: @Composable () -> Unit,
    garminContent: @Composable () -> Unit,
    githubContent: @Composable () -> Unit,
    movieBridgeContent: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(6.dp))
    HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
    Spacer(modifier = Modifier.height(4.dp))

    // Expandable header
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "SPECIAL HABIT TYPES",
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = if (expanded) "▾" else "▸",
            color = Color(0xFF888888),
            fontSize = 12.sp
        )
    }

    if (expanded) {
        Spacer(modifier = Modifier.height(6.dp))
        mealContent()
        Spacer(modifier = Modifier.height(4.dp))
        chessComContent()
        mediaContent()
        garminContent()
        githubContent()
        movieBridgeContent()
    }
}

/**
 * Collapsible "Advanced" section in the habit edit panel.
 * Hosts the Invert Data operation and the per-habit Restore-from-Backup.
 */
@Composable
private fun AdvancedSection(
    habitName: String,
    onInvertHabit: (String) -> Unit,
    onGetInvertPreview: (String) -> HabitViewModel.InvertPreview?,
    onRestoreFromBackup: () -> Unit = {}
) {
    var expanded by remember { mutableStateOf(false) }
    var showInvertDialog by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(6.dp))
    HorizontalDivider(color = Color(0xFF333333), thickness = 1.dp)
    Spacer(modifier = Modifier.height(4.dp))

    // Expandable header
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { expanded = !expanded },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "ADVANCED",
            color = Color(0xFF888888),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = if (expanded) "▾" else "▸",
            color = Color(0xFF888888),
            fontSize = 12.sp
        )
    }

    if (expanded) {
        Spacer(modifier = Modifier.height(6.dp))

        // ── Invert Data ────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "⇄ Invert Data", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                Text(
                    text = "Swap all 0s ↔ 1s",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Button(
                onClick = { showInvertDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A3A)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Invert", fontSize = 11.sp, color = Color(0xFFCC88CC))
            }
        }

        // ── Restore this habit from a backup file ───────────────────
        // Only this habit is affected; the rest of the backup is
        // ignored. Extracted to its own composable to keep
        // EditModeControlBar under the JVM method-size limit.
        RestoreFromBackupButton(onClick = onRestoreFromBackup)
    }

    if (showInvertDialog) {
        InvertConfirmDialog(
            habitName = habitName,
            preview = onGetInvertPreview(habitName),
            onConfirm = {
                onInvertHabit(habitName)
                showInvertDialog = false
            },
            onDismiss = { showInvertDialog = false }
        )
    }
}

/**
 * Confirmation dialog for the invert operation.
 * Shows a data-loss warning when values > 1 are present.
 */
@Composable
private fun InvertConfirmDialog(
    habitName: String,
    preview: HabitViewModel.InvertPreview?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "⇄ Invert Data",
                color = Color(0xFFCC88CC),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "This will flip every value for \"$habitName\":",
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (preview == null) {
                Text(
                    text = "This habit has no data to invert.",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
            } else {
                // Summary of what will happen
                Text(
                    text = "• ${preview.zeroCount} zero(s) → 1\n" +
                           "• ${preview.oneCount + preview.highValueCount} non-zero value(s) → 0\n" +
                           "• ${preview.totalEntries} total date entries",
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp
                )

                if (preview.highValueCount > 0) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.Top) {
                        Text("⚠ ", color = Color(0xFFFFAA00), fontSize = 14.sp)
                        Column {
                            Text(
                                text = "Data loss warning",
                                color = Color(0xFFFFAA00),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "$preview.highValueCount entry(ies) have values " +
                                       "greater than 1 (max: ${preview.maxValue}). " +
                                       "This is NOT a reversible swap — all of these " +
                                       "counts will be permanently destroyed and set to 0. " +
                                       "The original values cannot be recovered.",
                                color = Color(0xFFFF8866),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

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
                    enabled = preview != null && preview.totalEntries > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A1A3A))
                ) {
                    Text(
                        if (preview?.highValueCount ?: 0 > 0) "Invert Anyway" else "Invert",
                        color = Color(0xFFCC88CC)
                    )
                }
            }
        }
    }
}

// ── Conditional links picker dialog ──────────────────────────────────────────

/**
 * A popup that lists all habits (except the conditional habit itself) as checkboxes.
 * The user can select any number of them as the habits to auto-increment.
 */
/**
 * Edit-panel section shown only for habits that other conditional habits link to.
 * Offers a one-tap "backfill" that recomputes this habit's entire history from every
 * source habit that has it set as a conditional. Extracted into its own composable to
 * keep [EditModeControlBar] under the JVM method-size limit.
 */
@Composable
private fun ConditionalBackfillSection(
    habitName: String,
    conditionalHabits: Set<String>,
    conditionalLinkedHabits: Map<String, Set<String>>,
    conditionalLinkValues: Map<String, Map<String, String>> = emptyMap(),
    secondaryValueHabits: Set<String> = emptySet(),
    chessComHabitLinks: Map<String, String> = emptyMap(),
    valueDisplayLabels: Map<String, Map<String, String>> = emptyMap(),
    onBackfill: (String) -> Unit
) {
    // Only entries whose key is still an active conditional habit count as sources;
    // orphaned link entries (habit no longer conditional) must be ignored.
    val conditionalSources = remember(conditionalLinkedHabits, conditionalHabits, habitName) {
        conditionalLinkedHabits.entries
            .filter { it.key in conditionalHabits && habitName in it.value }
            .map { it.key }
            .sorted()
    }
    if (conditionalSources.isNotEmpty()) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1A0A14), RoundedCornerShape(6.dp))
                .padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Conditional Backfill",
                    color = Color(0xFFFF88CC),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Fed by ${conditionalSources.size} habit(s): ${conditionalSources.joinToString(", ") { src ->
                        val vk = effectiveConditionalLinkValueKey(
                            conditionalLinkValues, secondaryValueHabits,
                            chessComHabitLinks, src, habitName
                        )
                        if (vk == GRAPH_METRIC_POINTS) src
                        else "$src→${displayLabelForValue(habitName, vk, valueDisplayLabels)}"
                    }}",
                    color = Color(0xFFAAAAAA),
                    fontSize = 10.sp
                )
                Text(
                    text = "Recompute entire history from these sources",
                    color = Color(0xFF888888),
                    fontSize = 10.sp
                )
            }
            Button(
                onClick = { onBackfill(habitName) },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A0030)),
                modifier = Modifier.height(32.dp)
            ) {
                Text("Backfill", fontSize = 11.sp, color = Color(0xFFFF88CC))
            }
        }
    }
}

/**
 * Confirmation popup for a complete conditional backfill. Tells the user the total
 * number of increments that will be applied across the entire history and lists the
 * source habits that feed the target habit, then overwrites the target on confirm.
 */
@Composable
private fun ConditionalBackfillConfirmDialog(
    habitName: String,
    sources: List<String>,
    totalIncrements: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1A0A14), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Conditional Backfill",
                color = Color(0xFFFF88CC),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "This will completely overwrite ALL data for \"$habitName\" based " +
                    "on the habits that have it set as a conditional:",
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = sources.joinToString(", "),
                color = Color(0xFFFF88CC),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Total increments across entire history: $totalIncrements",
                color = Color(0xFFFFCC44),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
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
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A0030))
                ) {
                    Text("Overwrite & Backfill", color = Color(0xFFFF88CC))
                }
            }
        }
    }
}

@Composable
private fun ConditionalLinksPickerDialog(
    habitName: String,
    allHabitNames: List<String>,
    currentLinks: Set<String>,
    currentValues: Map<String, String> = emptyMap(),
    secondaryValueHabits: Set<String> = emptySet(),
    chessComHabitLinks: Map<String, String> = emptyMap(),
    valueDisplayLabels: Map<String, Map<String, String>> = emptyMap(),
    onConfirm: (Set<String>, Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    // Alphabetically sorted candidate list (case-insensitive).
    val otherHabits = remember(allHabitNames, habitName) {
        allHabitNames.filter { it != habitName && it.isNotEmpty() }
            .sortedBy { it.lowercase() }
    }
    // Search filter: blank query shows everything; otherwise case-insensitive
    // substring match. Selected habits hidden by the filter stay selected.
    var searchQuery by remember { mutableStateOf("") }
    val visibleHabits = remember(otherHabits, searchQuery) {
        val q = searchQuery.trim()
        if (q.isEmpty()) otherHabits else otherHabits.filter { it.contains(q, ignoreCase = true) }
    }
    var selected by remember(currentLinks) { mutableStateOf(currentLinks.toMutableSet()) }
    // Linked habit name → feed-value key override (absent = Points, the default)
    var valueChoices by remember(currentValues) { mutableStateOf(currentValues.toMutableMap()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1A0A14), RoundedCornerShape(12.dp))
                .padding(16.dp)
        ) {
            Text(
                text = "Linked habits for \"$habitName\"",
                color = Color(0xFFFF88CC),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Select habits to auto-increment when this habit is tapped. For each linked habit, pick which value it feeds:",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Search filter box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                singleLine = true,
                placeholder = { Text("Search habits…", fontSize = 12.sp, color = Color(0xFF888888)) },
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color(0xFFFF88CC),
                    unfocusedTextColor = Color(0xFFFF88CC),
                    focusedBorderColor = Color(0xFFFF88CC),
                    unfocusedBorderColor = Color(0xFF663355),
                    cursorColor = Color(0xFFFF88CC)
                ),
                textStyle = TextStyle(fontSize = 12.sp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (otherHabits.isEmpty()) {
                Text(
                    text = "No other habits available.",
                    color = Color(0xFF666666),
                    fontSize = 12.sp
                )
            } else if (visibleHabits.isEmpty()) {
                Text(
                    text = "No habits match \"${searchQuery.trim()}\".",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    visibleHabits.forEach { name ->
                        val isChecked = name in selected
                        // Feed-value options: Points always; Value2/Value3 only when
                        // the linked habit actually has those raw slots available.
                        val feedOptions = buildList {
                            add(GRAPH_METRIC_POINTS)
                            if (name in secondaryValueHabits) add(GRAPH_METRIC_VALUE2)
                            if (name in chessComHabitLinks) add(GRAPH_METRIC_VALUE3)
                        }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isChecked) Color(0xFF2A0020) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(
                                        indication = null,
                                        interactionSource = remember { MutableInteractionSource() }
                                    ) {
                                        val next = selected.toMutableSet()
                                        if (isChecked) {
                                            next.remove(name)
                                            val vals = valueChoices.toMutableMap()
                                            vals.remove(name)
                                            valueChoices = vals
                                        } else {
                                            next.add(name)
                                        }
                                        selected = next
                                    },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = if (isChecked) "☑" else "☐",
                                    color = if (isChecked) Color(0xFFFF88CC) else Color(0xFF666666),
                                    fontSize = 16.sp
                                )
                                Text(
                                    text = name,
                                    color = if (isChecked) Color(0xFFFF88CC) else Color(0xFFCCCCCC),
                                    fontSize = 12.sp
                                )
                            }
                            if (isChecked && feedOptions.size > 1) {
                                Row(
                                    modifier = Modifier.padding(start = 26.dp, top = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    feedOptions.forEach { opt ->
                                        val active = (valueChoices[name] ?: GRAPH_METRIC_POINTS) == opt
                                        Text(
                                            text = displayLabelForValue(name, opt, valueDisplayLabels),
                                            color = if (active) Color(0xFFFF88CC) else Color(0xFF888888),
                                            fontSize = 10.sp,
                                            modifier = Modifier
                                                .background(
                                                    if (active) Color(0xFF4A0030) else Color(0xFF221018),
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable(
                                                    indication = null,
                                                    interactionSource = remember { MutableInteractionSource() }
                                                ) {
                                                    val vals = valueChoices.toMutableMap()
                                                    if (opt == GRAPH_METRIC_POINTS) vals.remove(name) else vals[name] = opt
                                                    valueChoices = vals
                                                }
                                                .padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
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
                    onClick = { onConfirm(selected.toSet(), valueChoices.toMap()) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A0030))
                ) {
                    Text("Save (${selected.size})", color = Color(0xFFFF88CC))
                }
            }
        }
    }
}

// ── Dated Entry format info dialog ────────────────────────────────────────────

@Composable
private fun DatedEntryInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = androidx.compose.ui.Modifier
                .background(Color(0xFF1A1A0A), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Dated Entry Format",
                color = Color(0xFFFFCC44),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Link a plain-text file that contains date headers followed by paragraph blocks. " +
                       "Each blank-line-separated paragraph under a date counts as +1 for that day.",
                color = Color(0xFFCCCCCC),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Accepted date formats:", color = Color(0xFFFFCC44), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = "  M/D/YY   →  7/13/24", color = Color(0xFFAAAAAA), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(text = "  YYYY-MM-DD  →  2025-10-21", color = Color(0xFFAAAAAA), fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Date lines may start with # heading markers and may have a trailing HH:MM:SS timestamp (both are ignored).",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = "Example file:", color = Color(0xFFFFCC44), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "# 2025-03-10\n" +
                       "First paragraph here.\n" +
                       "More lines of the same entry.\n" +
                       "\n" +
                       "Second paragraph — blank line above = new entry.\n" +
                       "\n" +
                       "# 2025-03-11\n" +
                       "Only one paragraph today.",
                color = Color(0xFF88FFCC),
                fontSize = 11.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Result: 2025-03-10 = 2,  2025-03-11 = 1",
                color = Color(0xFFCCCCCC),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Paragraphs can also be separated by a line containing only ,,, instead of a blank line.",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "The file is checked every time the app comes to the foreground. " +
                       "Only files whose size has changed are re-parsed, so this is very efficient.",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(modifier = androidx.compose.ui.Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A3A00))
                ) {
                    Text("Got it", color = Color(0xFFFFCC44))
                }
            }
        }
    }
}

// ── Rename screen dialog ──────────────────────────────────────────────────────

@Composable
private fun RenameScreenDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Rename Screen",
                color = Color(0xFFFFAA00),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Screen name", color = Color(0xFF888888)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFAA00),
                    unfocusedBorderColor = Color(0xFF555555)
                )
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
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) onConfirm(trimmed)
                    },
                    enabled = name.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                ) {
                    Text("Rename", color = Color(0xFFFFAA00))
                }
            }
        }
    }
}

// ── Rename habit dialog ────────────────────────────────────────────────────────

@Composable
private fun RenameHabitDialog(
    currentName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    var showConfirmation by remember { mutableStateOf(false) }

    if (showConfirmation) {
        // Confirmation dialog
        Dialog(onDismissRequest = { showConfirmation = false }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text(
                    text = "Rename Habit",
                    color = Color(0xFFFFAA00),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Are you sure you want to rename \"$currentName\" to \"$name\"?",
                    color = Color(0xFFCCCCCC),
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "This will update the habit name in the database and all settings.",
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { showConfirmation = false }) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            showConfirmation = false
                            onConfirm(name.trim())
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                    ) {
                        Text("Rename", color = Color(0xFFFFAA00))
                    }
                }
            }
        }
    } else {
        // Name entry dialog
        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text(
                    text = "Rename Habit",
                    color = Color(0xFFFFAA00),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Habit name", color = Color(0xFF888888)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFFAA00),
                        unfocusedBorderColor = Color(0xFF555555)
                    )
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
                        onClick = {
                            val trimmed = name.trim()
                            if (trimmed.isNotEmpty() && trimmed != currentName) {
                                showConfirmation = true
                            } else if (trimmed == currentName) {
                                onDismiss()
                            }
                        },
                        enabled = name.trim().isNotEmpty() && name.trim() != currentName,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                    ) {
                        Text("Next", color = Color(0xFFFFAA00))
                    }
                }
            }
        }
    }
}

// ── Add screen dialog ─────────────────────────────────────────────────────────

@Composable
private fun AddScreenDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "New Screen",
                color = Color(0xFFFFAA00),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Screen name", color = Color(0xFF888888)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFAA00),
                    unfocusedBorderColor = Color(0xFF555555)
                )
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
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) onConfirm(trimmed)
                    },
                    enabled = name.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                ) {
                    Text("Add", color = Color(0xFFFFAA00))
                }
            }
        }
    }
}

// ── Increment amounts editor dialog ───────────────────────────────────────────

/**
 * Dialog for editing the quick-increment button amounts for a custom-input habit.
 * Shows the current amounts as editable chips and allows adding/removing values.
 * Saving with an empty list resets to the default amounts.
 */
@Composable
private fun IncrementAmountsEditorDialog(
    habitName: String,
    currentAmounts: List<Int>,
    onSave: (List<Int>) -> Unit,
    onDismiss: () -> Unit
) {
    // Represent each amount as a text field string so the user can edit freely
    var amountsText by remember { mutableStateOf(currentAmounts.joinToString(", ")) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Increment amounts",
                color = Color(0xFFFFCC44),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Enter amounts separated by commas:",
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))

            OutlinedTextField(
                value = amountsText,
                onValueChange = { amountsText = it },
                label = { Text("e.g. 1, 5, 10, 30, 50", color = Color(0xFF666666)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFCC44),
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = Color(0xFFFFCC44)
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Leave empty to use defaults (1, 5, 10, 30, 50)",
                color = Color(0xFF666666),
                fontSize = 10.sp
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
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        val parsed = amountsText
                            .split(",")
                            .mapNotNull { it.trim().toIntOrNull() }
                            .filter { it > 0 }
                            .distinct()
                        onSave(parsed)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2800))
                ) {
                    Text("Save", color = Color(0xFFFFCC44))
                }
            }
        }
    }
}

// ── Custom Point Ranges dialog ─────────────────────────────────────────────────
@Composable
private fun PointRangesEditorDialog(
    habitName: String,
    currentRanges: List<com.example.tail.data.PointRange>,
    onSave: (List<com.example.tail.data.PointRange>) -> Unit,
    onDismiss: () -> Unit
) {
    // State for each range's min and max values - dynamic list
    val rangeStates = remember(currentRanges) {
        mutableStateListOf(
            *currentRanges.map { range ->
                mutableStateOf(Pair(
                    if (range.min == Int.MIN_VALUE) "" else range.min.toString(),
                    if (range.max == Int.MAX_VALUE) "" else range.max.toString()
                ))
            }.toTypedArray()
        )
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "Custom Point Ranges",
                color = Color(0xFFBB88FF),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Enter min/max values for each point level:",
                color = Color(0xFFAAAAAA),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Leave min empty for no minimum, leave max empty for no maximum.",
                color = Color(0xFF666666),
                fontSize = 10.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            // Range rows for each point level (dynamic)
            for (i in rangeStates.indices) {
                val (minText, maxText) = rangeStates[i].value
                var localMin by remember { mutableStateOf(minText) }
                var localMax by remember { mutableStateOf(maxText) }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "$i",
                        color = Color(0xFFBB88FF),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.width(24.dp)
                    )
                    OutlinedTextField(
                        value = localMin,
                        onValueChange = {
                            localMin = it
                            rangeStates[i].value = Pair(localMin, localMax)
                        },
                        label = { Text("Min", fontSize = 10.sp, color = Color(0xFF666666)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFBB88FF),
                            unfocusedBorderColor = Color(0xFF554488),
                            cursorColor = Color(0xFFBB88FF)
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 12.sp)
                    )
                    OutlinedTextField(
                        value = localMax,
                        onValueChange = {
                            localMax = it
                            rangeStates[i].value = Pair(localMin, localMax)
                        },
                        label = { Text("Max", fontSize = 10.sp, color = Color(0xFF666666)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFBB88FF),
                            unfocusedBorderColor = Color(0xFF554488),
                            cursorColor = Color(0xFFBB88FF)
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = TextStyle(fontSize = 12.sp)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Add Range button
            Button(
                onClick = {
                    // Auto-calculate new range based on the previous one
                    val lastIndex = rangeStates.size - 1
                    val (prevMinText, prevMaxText) = rangeStates[lastIndex].value
                    
                    val newMin = if (prevMaxText.isNotEmpty()) {
                        val prevMax = prevMaxText.toIntOrNull() ?: 0
                        (prevMax + 1).toString()
                    } else {
                        ""
                    }
                    
                    val newMax = if (prevMinText.isNotEmpty() && prevMaxText.isNotEmpty()) {
                        val prevMin = prevMinText.toIntOrNull() ?: 0
                        val prevMax = prevMaxText.toIntOrNull() ?: 0
                        val range = prevMax - prevMin
                        (prevMax + 1 + range).toString()
                    } else {
                        ""
                    }
                    
                    rangeStates.add(mutableStateOf(Pair(newMin, newMax)))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2A6A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("+ Add Point Range", color = Color(0xFFBB88FF), fontSize = 12.sp)
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = Color(0xFF888888))
                }
                Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                Button(
                    onClick = {
                        val ranges = rangeStates.map { state -> val (minText, maxText) = state.value
                            com.example.tail.data.PointRange(
                                min = minText.toIntOrNull() ?: Int.MIN_VALUE,
                                max = maxText.toIntOrNull() ?: Int.MAX_VALUE
                            )
                        }
                        onSave(ranges)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A2A6A))
                ) {
                    Text("Save", color = Color(0xFFBB88FF))
                }
            }
        }
    }
}

// ── Add habit dialog ──────────────────────────────────────────────────────────

@Composable
private fun AddHabitDialog(
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Add Habit",
                color = Color(0xFF88FF88),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Habit name", color = Color(0xFF888888)) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF88FF88),
                    unfocusedBorderColor = Color(0xFF555555)
                )
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
                    onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) onConfirm(trimmed)
                    },
                    enabled = name.trim().isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A1A))
                ) {
                    Text("Add", color = Color(0xFF88FF88))
                }
            }
        }
    }
}

// ── Delete habit confirmation dialog ─────────────────────────────────────────

// ── Set count dialog ──────────────────────────────────────────────────────────

/**
 * Dialog for directly typing in a new today-count value for a habit.
 * Pre-filled with the current count. Confirming sets the raw count to the
 * entered value (the displayed points value may differ if a divider is set).
 */
@Composable
private fun SetCountDialog(
    habitName: String,
    currentCount: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    var countText by remember { mutableStateOf(currentCount.toString()) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Set today's count",
                color = Color(0xFFFFAA00),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = countText,
                onValueChange = { countText = it.filter { c -> c.isDigit() } },
                label = { Text("Count", color = Color(0xFF888888)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFAA00),
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = Color(0xFFFFAA00)
                )
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
                    onClick = {
                        val newCount = countText.toIntOrNull() ?: 0
                        onConfirm(newCount)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A3A00))
                ) {
                    Text("Set", color = Color(0xFFFFAA00))
                }
            }
        }
    }
}

// ── Habit note dialog ──────────────────────────────────────────────────────────

/**
 * Dialog for editing a habit's note.
 */
@Composable
private fun HabitNoteDialog(
    habitName: String,
    initialNote: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var noteText by remember { mutableStateOf(initialNote) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Edit Note",
                color = Color(0xFFFFCC44),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = habitName,
                color = Color(0xFF888888),
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                placeholder = { Text("Add a note about this habit...", color = Color(0xFF666666), fontSize = 11.sp) },
                singleLine = false,
                minLines = 4,
                maxLines = 8,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 200.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFFFFCC44),
                    unfocusedBorderColor = Color(0xFF555555),
                    cursorColor = Color(0xFFFFCC44)
                ),
                textStyle = TextStyle(fontSize = 13.sp)
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
                    onClick = { onConfirm(noteText) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A4A00))
                ) {
                    Text("Save", color = Color(0xFFFFCC44))
                }
            }
        }
    }
}

// ── Delete habit confirmation dialog ─────────────────────────────────────────

@Composable
private fun DeleteHabitConfirmDialog(
    habitName: String,
    isAppLink: Boolean = false,
    /** Days of stored data found in the JSON (primary + secondary slots). */
    dataDayCount: Int = 0,
    /** Called with true when the user also opted to purge the JSON data. */
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    // Opt-in purge toggle — defaults OFF so plain delete keeps history.
    var alsoDeleteData by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = if (isAppLink) "Remove App Link" else "Delete Habit",
                color = Color(0xFFFF8888),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = if (isAppLink) {
                    "Remove \"$habitName\" from the grid?"
                } else if (dataDayCount > 0) {
                    "Remove \"$habitName\" from the grid?\n\n" +
                        "This habit has $dataDayCount day${if (dataDayCount == 1) "" else "s"} of data " +
                        "in your JSON files."
                } else {
                    "Remove \"$habitName\" from the grid?\n\n" +
                        "No data for this habit was found in your JSON files."
                },
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
            )

            // Optional data purge — only offered for real habits with data.
            if (!isAppLink && dataDayCount > 0) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Also delete its data",
                            color = Color(0xFFCCCCCC),
                            fontSize = 12.sp
                        )
                        Text(
                            text = "Permanently removes $dataDayCount day" +
                                "${if (dataDayCount == 1) "" else "s"} of history",
                            color = Color(0xFFFF8888),
                            fontSize = 10.sp
                        )
                    }
                    Switch(
                        checked = alsoDeleteData,
                        onCheckedChange = { alsoDeleteData = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFFFF8888),
                            checkedTrackColor = Color(0xFF3A0000),
                            uncheckedThumbColor = Color(0xFF888888),
                            uncheckedTrackColor = Color(0xFF333333)
                        )
                    )
                }
            }

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
                    onClick = { onConfirm(alsoDeleteData) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (alsoDeleteData) Color(0xFF550000) else Color(0xFF3A0000)
                    )
                ) {
                    Text(
                        if (alsoDeleteData) "Delete All" else "Delete",
                        color = Color(0xFFFF8888)
                    )
                }
            }
        }
    }
}

// ── "1 max" recalc confirmation dialog ─────────────────────────────────────────

@Composable
private fun MaxOneRecalcConfirmDialog(
    habitName: String,
    affectedDays: Int,
    onUpdatePast: () -> Unit,
    onFutureOnly: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Enable 1 max?",
                color = Color(0xFF88FF88),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            val msg = if (affectedDays > 0) {
                "\"$habitName\" has $affectedDays past day(s) with a count above 1.\n\n" +
                    "Cap all past entries to 1? This will permanently reduce those point totals."
            } else {
                "\"$habitName\" has no past entries above 1, so no totals need updating."
            }
            Text(
                text = msg,
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
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
                TextButton(onClick = onFutureOnly) {
                    Text("Future only", color = Color(0xFFAAAAAA))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onUpdatePast,
                    enabled = affectedDays > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1A4A1A),
                        disabledContainerColor = Color(0xFF1A2A1A)
                    )
                ) {
                    Text(
                        "Update past",
                        color = if (affectedDays > 0) Color(0xFF88FF88) else Color(0xFF556655)
                    )
                }
            }
        }
    }
}

// ── "1 max" restore confirmation dialog ────────────────────────────────────────

@Composable
private fun MaxOneRestoreConfirmDialog(
    habitName: String,
    restorableDays: Int,
    onRestore: () -> Unit,
    onLeaveAsIs: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Disable 1 max?",
                color = Color(0xFFFFAA00),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            val msg = if (restorableDays > 0) {
                "While \"1 max\" was on, $restorableDays past day(s) were capped to 1.\n\n" +
                    "Their timestamps still record the true count. " +
                    "Restore those entries so they count fully toward totals again?"
            } else {
                "\"$habitName\" has no past entries that can be restored from timestamps " +
                    "(none were capped, or no timestamps were recorded)."
            }
            Text(
                text = msg,
                color = Color(0xFFCCCCCC),
                fontSize = 13.sp
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
                TextButton(onClick = onLeaveAsIs) {
                    Text("Leave as-is", color = Color(0xFFAAAAAA))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onRestore,
                    enabled = restorableDays > 0,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3A2A00),
                        disabledContainerColor = Color(0xFF2A2A1A)
                    )
                ) {
                    Text(
                        "Restore",
                        color = if (restorableDays > 0) Color(0xFFFFCC44) else Color(0xFF665544)
                    )
                }
            }
        }
    }
}

// ── Chess.com link toggle (extracted to keep EditModeControlBar small) ────────

/**
 * Row + dropdown for linking the selected habit to a Chess.com game type.
 * Extracted from EditModeControlBar to stay under the JVM method-size limit.
 */
@Composable
private fun ChessComLinkToggle(
    habitName: String,
    links: Map<String, String>,
    onSetLink: (String?) -> Unit
) {
    val currentLink = links[habitName]
    val isLinked = currentLink != null
    var dropdownExpanded by remember { mutableStateOf(false) }

    Spacer(modifier = Modifier.height(6.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(text = "♟ Chess.com", color = Color(0xFFCCCCCC), fontSize = 12.sp)
            Text(
                text = if (isLinked) {
                    val typeName = ChessComType.fromKey(currentLink)?.label ?: currentLink
                    "Linked to: $typeName"
                } else "Not linked to Chess.com",
                color = if (isLinked) Color(0xFF66BB6A) else Color(0xFF888888),
                fontSize = 10.sp
            )
        }
        Switch(
            checked = isLinked,
            onCheckedChange = { checked ->
                if (checked) {
                    dropdownExpanded = true
                } else {
                    onSetLink(null)
                }
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF44BBFF),
                checkedTrackColor = Color(0xFF003355),
                uncheckedThumbColor = Color(0xFF888888),
                uncheckedTrackColor = Color(0xFF333333)
            )
        )
    }

    // Chess.com type picker dropdown
    if (isLinked || dropdownExpanded) {
        Spacer(modifier = Modifier.height(4.dp))
        Box {
            Button(
                onClick = { dropdownExpanded = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003355)),
                modifier = Modifier.height(32.dp)
            ) {
                val label = if (currentLink != null) {
                    ChessComType.fromKey(currentLink)?.label ?: "Select type"
                } else "Select type"
                Text(label, fontSize = 11.sp, color = Color(0xFF44BBFF))
            }
            DropdownMenu(
                expanded = dropdownExpanded,
                onDismissRequest = { dropdownExpanded = false }
            ) {
                ChessComType.entries.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.label) },
                        onClick = {
                            onSetLink(type.name)
                            dropdownExpanded = false
                        }
                    )
                }
            }
        }
    }
}

// ── Restore-from-backup button (extracted to keep EditModeControlBar small) ───

/**
 * Full-width "Restore from Backup" button shown at the bottom of the habit-type
 * switches in the edit panel. Opens a file picker to choose a backup file, then
 * a confirmation dialog. Only the selected habit is restored.
 */
@Composable
private fun RestoreFromBackupButton(onClick: () -> Unit) {
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
private fun GarminLinkToggleSection(
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
            Text(text = "❤️ Garmin", color = Color(0xFFCCCCCC), fontSize = 12.sp)
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
private fun GitHubLinkToggleSection(
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
private fun MovieBridgeToggleSection(
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
private fun PcWidgetToggleSection(
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
private fun HabitRestoreConfirmDialog(
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
private fun IconPickerDialog(
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
                                    viewModel.generateAiIcon(aiPrompt.trim())
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
private fun IconPickerModeTab(
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
private fun TextIconPickerSection(
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
 * which renderers resolve to the app's launcher icon at draw time.
 */
@Composable
private fun AppIconPickerSection(
    currentIconName: String?,
    onIconSelected: (String?) -> Unit,
    aiIconsEnabled: Boolean
) {
    val context = LocalContext.current
    val appIconRepo = remember { AppIconRepository(context) }
    var searchQuery by remember { mutableStateOf("") }
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

    // Keep the dialog the same total height as the built-in icons grid.
    val listHeight = if (aiIconsEnabled) 200.dp else 340.dp

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
                    val isSelected = appIconNameOf(app.packageName) == currentIconName
                    val iconBitmap = remember(app.packageName) {
                        appIconRepo.loadIconBitmap(app.packageName)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(
                                indication = null,
                                interactionSource = remember { MutableInteractionSource() }
                            ) { onIconSelected(appIconNameOf(app.packageName)) }
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

private fun formatRollingRow(
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
private fun InfoRow(
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
private fun VoiceTriggerInfoDialog(onDismiss: () -> Unit) {
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
private fun RollForwardConfirmDialog(
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
private fun drawableToBitmapForDialog(drawable: Drawable): Bitmap {
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
private fun ShortcutPickerRow(
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
