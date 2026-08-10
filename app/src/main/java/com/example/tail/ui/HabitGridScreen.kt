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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
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
import com.example.tail.data.ChessComType
import com.example.tail.data.GarminType
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.RollingHigh
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
    val todayEntries: List<Pair<String, String>> = emptyList()
)

// Grid is 8 columns × 10 rows = 80 cells
private const val GRID_COLUMNS = 8
private const val TOTAL_CELLS = 80

private val DISPLAY_DATE_FMT = DateTimeFormatter.ofPattern("EEE MMM d")

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
    val todayPoints by viewModel.todayPoints.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val selectedDateLocation by viewModel.selectedDateLocation.collectAsState()
    val editMode by viewModel.editMode.collectAsState()
    val graphMode by viewModel.graphMode.collectAsState()
    val graphSelectedHabits by viewModel.graphSelectedHabits.collectAsState()
    val selectedEditIndex by viewModel.selectedEditIndex.collectAsState()
    val movePendingSourceIndex by viewModel.movePendingSourceIndex.collectAsState()
    val habitScreens by viewModel.habitScreens.collectAsState()
    val activeScreenIndex by viewModel.activeScreenIndex.collectAsState()
    val garminMonthlyData by viewModel.garminMonthlyData.collectAsState()
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

    // Programmatic orientation control: allow landscape only when graph mode is
    // active with at least one habit selected; otherwise lock to portrait.
    // NOTE: We do NOT force portrait in onDispose here — when navigating to
    // MapScreen, MapScreen owns orientation (landscape) and resetting on
    // dispose would race with MapScreen's DisposableEffect. Each destination
    // composable that cares about orientation sets it on entry.
    val allowLandscape = graphMode && graphSelectedHabits.isNotEmpty()
    val activity = context as? Activity
    LaunchedEffect(allowLandscape) {
        activity?.requestedOrientation = if (allowLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

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

    // File picker for per-habit subtype data files
    var subtypeDataPickerHabit by remember { mutableStateOf<String?>(null) }
    val subtypeDataFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val habitName = subtypeDataPickerHabit
        if (uri != null && habitName != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            viewModel.setSubtypeDataFileUri(habitName, uri)
        }
        subtypeDataPickerHabit = null
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
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Back arrow — always available, hold to rapid-step
                        RepeatIconButton(onClick = { viewModel.navigateDay(-1) }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Previous day",
                                tint = Color.White
                            )
                        }

                        // Date label — tappable to open the calendar picker
                        val dateLabel = if (isToday) "Today" else selectedDate.format(DISPLAY_DATE_FMT)
                        val dateLabelColor = if (isToday) Color.White else Color(0xFFFFD700)
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
                                .padding(horizontal = 6.dp, vertical = 4.dp)
                        )

                        // Forward arrow — disabled when already on today, hold to rapid-step
                        RepeatIconButton(
                            onClick = { viewModel.navigateDay(+1) },
                            enabled = !isToday
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowForward,
                                contentDescription = "Next day",
                                tint = if (isToday) Color.Gray else Color.White
                            )
                        }

                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    // Edit mode toggle button
                    IconButton(
                        onClick = { viewModel.toggleEditMode() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (editMode) Color(0xFF4A2A00) else Color.Transparent
                        )
                    ) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = if (editMode) "Edit mode ON" else "Edit mode OFF",
                            tint = if (editMode) Color(0xFFFFAA00) else Color.White
                        )
                    }
                    // Graph mode toggle button
                    IconButton(
                        onClick = { viewModel.toggleGraphMode() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (graphMode) Color(0xFF0A2A0A) else Color.Transparent
                        )
                    ) {
                        Icon(
                            Icons.Default.BarChart,
                            contentDescription = if (graphMode) "Graph mode ON" else "Graph mode OFF",
                            tint = if (graphMode) Color(0xFF66DD66) else Color.White
                        )
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
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

            // Screen tabs — shown when multiple screens exist (hidden in landscape)
            if (habitScreens.size > 1 && !isLandscape) {
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
                // Tiered spinner — colour & sophistication follow today's points.
                // Read the retained todayPoints StateFlow (not the stale habits list)
                // so the tier is correct even mid-load.
                Box(modifier = Modifier.fillMaxSize()) {
                    HabitLoadingSpinner(
                        points = todayPoints,
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
                        graphSelectedHabits = graphSelectedHabits,
                        selectedEditIndex = selectedEditIndex,
                        movePendingSourceIndex = movePendingSourceIndex,
                        customIconOverrides = settings.habitIcons,
                        disabledHabits = settings.disabledHabits,
                        aiIconRepo = if (settings.aiIconsEnabled) viewModel.getAiIconRepo() else null,
                        garminHabitLinks = settings.garminHabitLinks,
                        appLinks = settings.appLinks,
                        habitAppAssociations = settings.habitAppAssociations,
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
                                    viewModel.incrementHabit(habit.name)
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
                                    // Load today's entries for the dialog
                                    viewModel.loadTextEntriesWithTimestamps(habit.name, selectedDate) { todayEntries ->
                                        if (showOpts) {
                                            viewModel.loadTextOptions(habit.name) { opts ->
                                                textInputDialogState = TextInputDialogState(
                                                    habit = habit,
                                                    showOptions = true,
                                                    options = opts,
                                                    todayEntries = todayEntries
                                                )
                                            }
                                        } else {
                                            textInputDialogState = TextInputDialogState(
                                                habit = habit,
                                                showOptions = false,
                                                options = emptyList(),
                                                todayEntries = todayEntries
                                            )
                                        }
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
                                // Check if this habit has app associations
                                val associations = settings.habitAppAssociations[habit.name]
                                if (!associations.isNullOrEmpty()) {
                                    if (associations.size == 1) {
                                        // Single app — launch directly, bypass list
                                        val launchIntent = context.packageManager
                                            .getLaunchIntentForPackage(associations[0])
                                        if (launchIntent != null) {
                                            context.startActivity(launchIntent)
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
                        customInputHabits = settings.customInputHabits,
                        customInputAmounts = settings.customInputAmounts,
                        textInputHabits = settings.textInputHabits,
                        textInputOptionsHabits = settings.textInputOptionsHabits,
                        textInputFileUris = settings.textInputFileUris,
                        datedEntryHabits = settings.datedEntryHabits,
                        datedEntryFileUris = settings.datedEntryFileUris,
                        habitDividers = settings.habitDividers,
                        conditionalHabits = settings.conditionalHabits,
                        conditionalLinkedHabits = settings.conditionalLinkedHabits,
                        subtypedHabits = settings.subtypedHabits,
                        habitSubtypes = settings.habitSubtypes,
                        subtypeDataFileUris = settings.subtypeDataFileUris,
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
                        onToggleCustomInput = { name -> viewModel.toggleCustomInput(name) },
                        onSetCustomInputAmounts = { name, amounts -> viewModel.setCustomInputAmounts(name, amounts) },
                        onToggleTextInput = { name -> viewModel.toggleTextInput(name) },
                        onToggleTextInputOptions = { name -> viewModel.toggleTextInputOptions(name) },
                        onPickTextInputFile = { name ->
                            textInputPickerHabit = name
                            textInputFilePicker.launch(arrayOf("application/json", "*/*"))
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
                        onSetDivider = { name, divisor -> viewModel.setHabitDivider(name, divisor) },
                        onToggleConditional = { name -> viewModel.toggleConditional(name) },
                        onSetConditionalLinks = { name -> conditionalLinksPickerHabit = name },
                        onBackfillConditional = { name -> conditionalBackfillHabit = name },
                        onToggleSubtyped = { name -> viewModel.toggleSubtyped(name) },
                        onSetSubtypes = { name, types -> viewModel.setHabitSubtypes(name, types) },
                        onPickSubtypeDataFile = { name ->
                            subtypeDataPickerHabit = name
                            subtypeDataFilePicker.launch(arrayOf("application/json", "*/*"))
                        },
                        mealHabits = settings.mealHabits,
                        onToggleMeal = { name -> viewModel.toggleMealHabit(name) },
                        onOpenMealDetails = { name ->
                            mealDialogFromTap = false
                            mealDialogHabit = name
                        },
                        hiddenScreenIds = settings.hiddenScreens,
                        onToggleScreenHidden = { viewModel.toggleScreenHidden(activeScreenIndex) },
                        disabledHabits = settings.disabledHabits,
                        onToggleDisabled = { name -> viewModel.toggleDisabledHabit(name) },
                        noPointsHabits = settings.noPointsHabits,
                        onToggleNoPoints = { name -> viewModel.toggleNoPointsHabit(name) },
                        secondaryValueHabits = settings.secondaryValueHabits,
                        onToggleSecondaryValue = { name -> viewModel.toggleSecondaryValueHabit(name) },
                        chessComEnabled = settings.chessComEnabled,
                        chessComHabitLinks = settings.chessComHabitLinks,
                        onSetChessComLink = { name, type -> viewModel.setChessComHabitLink(name, type) },
                        garminEnabled = settings.garminEnabled,
                        garminHabitLinks = settings.garminHabitLinks,
                        onSetGarminLink = { name, type -> viewModel.setGarminHabitLink(name, type) },
                        garminDateOfBirth = settings.garminDateOfBirth,
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
                            viewModel.deleteTextEntry(name, timestamp)
                            // Reload entries after delete
                            viewModel.loadTextEntriesWithTimestamps(name, selectedDate) { entries ->
                                editModeTextEntries = entries
                            }
                        },
                        habitNotes = settings.habitNotes,
                        onSetHabitNote = { name, note -> viewModel.setHabitNote(name, note) },
                        onToggleRollForward = { name -> viewModel.toggleRollForward(name) },
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
                        onMoveAppAssociation = { name, from, to -> viewModel.moveHabitAppAssociation(name, from, to) }
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
                    // Remove the just-recorded timestamp and mark as timeless
                    toastScope.launch {
                        viewModel.timestampRepo.deleteLastTimestamp(toastHabit, selectedDate)
                    }
                    incrementToastIsTimeless = true
                }
            )
        }
    }

    // ── Advice banner at bottom of screen (hidden in edit/graph modes) ──
    if (!editMode && !graphMode) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            AdviceBanner(viewModel = adviceViewModel)
        }
    }
    } // end Box

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

    // Timestamp editor dialog
    timestampEditorHabitName?.let { habitName ->
        TimestampEditorDialog(
            habitName = habitName,
            timestamps = timestampEditorList,
            onUpdateTimestamp = { index, newTime ->
                timestampScope.launch {
                    timestampEditorList = viewModel.timestampRepo.updateTimestamp(habitName, selectedDate, index, newTime)
                    selectedHabitTimestampCount = timestampEditorList.size
                    // Sync the habit count with the number of timestamps
                    // (user may have edited timestamps, so count should match)
                }
            },
            onDeleteTimestamp = { index ->
                timestampScope.launch {
                    timestampEditorList = viewModel.timestampRepo.deleteTimestamp(habitName, selectedDate, index)
                    selectedHabitTimestampCount = timestampEditorList.size
                    // Decrement the habit count to match
                    val currentHabit = habits.find { it.name == habitName }
                    if (currentHabit != null && currentHabit.rawTodayCount > timestampEditorList.size) {
                        viewModel.setHabitCount(habitName, currentHabit.rawTodayCount - 1)
                    }
                }
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
            onDismiss = { timestampEditorHabitName = null }
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
                viewModel.incrementHabit(habit.name, amount)
                viewModel.recordRecentIncrementAmount(habit.name, amount)
                dialogHabit = null
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
            onDismiss = { mealDialogHabit = null },
            incrementAlreadyDone = mealDialogFromTap
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
                                }
                            }
                        }
                    )
                } else {
                    viewModel.saveTextEntries(state.habit.name, entries, dateForEntry, entryTime)
                    textInputDialogState = null
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
            onDismiss = { appAssociationPickerHabit = null }
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
                packageNames = packages,
                onLaunch = { pkg ->
                    val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
                    if (launchIntent != null) {
                        context.startActivity(launchIntent)
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
        DeleteHabitConfirmDialog(
            habitName = habitName,
            onConfirm = {
                val idx = habits.indexOfFirst { it.name == habitName }
                if (idx >= 0) viewModel.deleteHabit(idx)
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
            onConfirm = { links ->
                viewModel.setConditionalLinks(habitName, links)
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
                        viewModel.timestampRepo.updateLastTimestamp(habitName, selectedDate, newTime)
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
                TextButton(
                    onClick = { onMoveScreenLeft(index) },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = Color(0xFF333300),
                        contentColor = Color(0xFFFFAA00)
                    ),
                    modifier = Modifier.height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("◀", fontSize = 12.sp)
                }
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
                TextButton(
                    onClick = { onMoveScreenRight(index) },
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = Color(0xFF333300),
                        contentColor = Color(0xFFFFAA00)
                    ),
                    modifier = Modifier.height(28.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                ) {
                    Text("▶", fontSize = 12.sp)
                }
            }
        }
    }
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
    graphSelectedHabits: Set<String> = emptySet(),
    selectedEditIndex: Int,
    movePendingSourceIndex: Int = -1,
    customIconOverrides: Map<String, String> = emptyMap(),
    disabledHabits: Set<String> = emptySet(),
    aiIconRepo: AiIconRepository? = null,
    garminHabitLinks: Map<String, String> = emptyMap(),
    appLinks: Map<String, String> = emptyMap(),
    habitAppAssociations: Map<String, List<String>> = emptyMap(),
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
                    HabitButton(
                        habit = habit,
                        onClick = { onHabitClick(habit, index) },
                        onLongClick = { onHabitLongClick(habit) },
                        modifier = Modifier.padding(2.dp),
                        editMode = editMode,
                        isSelected = isEditSelected || isGraphSelected,
                        isMovePendingSource = isMovePendingSource,
                        isMovePendingTarget = isMovePending && !isMovePendingSource && editMode,
                        customIconOverrides = customIconOverrides,
                        graphMode = graphMode,
                        isGraphSelected = isGraphSelected,
                        isDisabled = habit.name in disabledHabits,
                        aiIconRepo = aiIconRepo,
                        garminHabitLinks = garminHabitLinks,
                        hasAppAssociation = habit.name in habitAppAssociations
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

    // Load app label and icon
    val appLabel = remember(packageName) {
        try { pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString() }
        catch (e: Exception) { packageName }
    }
    val iconBitmap = remember(packageName) {
        try { drawableToBitmapForDialog(pm.getApplicationIcon(packageName)) }
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
        // App label (truncated)
        Text(
            text = appLabel.take(20),
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
    packageNames: List<String>,
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
                lazyItems(packageNames) { pkg ->
                    val label = remember(pkg) {
                        try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        catch (e: Exception) { pkg }
                    }
                    val iconBitmap = remember(pkg) {
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
                            Text(text = label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text(text = pkg, color = Color(0xFF888888), fontSize = 10.sp)
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
    onToggleMeal: (String) -> Unit,
    onOpenMealDetails: (String) -> Unit
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

    if (isMeal) {
        Spacer(modifier = Modifier.height(4.dp))
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
                "Meal Details & History",
                fontSize = 12.sp,
                color = Color(0xFFFF9800)
            )
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
    onToggleSecondaryValue: (String) -> Unit
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
                text = if (isSecondaryValue) "Extra value track (e.g. session count)"
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
    customInputHabits: Set<String>,
    customInputAmounts: Map<String, List<Int>> = emptyMap(),
    textInputHabits: Set<String>,
    textInputOptionsHabits: Set<String>,
    textInputFileUris: Map<String, String>,
    datedEntryHabits: Set<String>,
    datedEntryFileUris: Map<String, String>,
    habitDividers: Map<String, Int>,
    conditionalHabits: Set<String>,
    conditionalLinkedHabits: Map<String, Set<String>>,
    subtypedHabits: Set<String>,
    habitSubtypes: Map<String, List<String>>,
    subtypeDataFileUris: Map<String, String>,
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
    onToggleCustomInput: (String) -> Unit,
    onSetCustomInputAmounts: (String, List<Int>) -> Unit = { _, _ -> },
    onToggleTextInput: (String) -> Unit,
    onToggleTextInputOptions: (String) -> Unit,
    onPickTextInputFile: (String) -> Unit,
    onToggleDatedEntry: (String) -> Unit,
    onPickDatedEntryFile: (String) -> Unit,
    onDeleteHabit: (String) -> Unit,
    onChangeIcon: (String) -> Unit,
    onRenameHabit: (String, String) -> Unit,
    onSetCount: (String, Int) -> Unit,
    onSetCountWithRollForward: (String, Int, java.time.LocalDate) -> Unit = { _, _, _ -> },
    onSetDivider: (String, Int) -> Unit,
    onToggleConditional: (String) -> Unit,
    onSetConditionalLinks: (String) -> Unit,
    onBackfillConditional: (String) -> Unit = {},
    onToggleSubtyped: (String) -> Unit,
    onSetSubtypes: (String, List<String>) -> Unit,
    onPickSubtypeDataFile: (String) -> Unit,
    mealHabits: Set<String> = emptySet(),
    onToggleMeal: (String) -> Unit = {},
    onOpenMealDetails: (String) -> Unit = {},
    hiddenScreenIds: Set<String> = emptySet(),
    onToggleScreenHidden: () -> Unit = {},
    disabledHabits: Set<String> = emptySet(),
    onToggleDisabled: (String) -> Unit = {},
    noPointsHabits: Set<String> = emptySet(),
    onToggleNoPoints: (String) -> Unit = {},
    secondaryValueHabits: Set<String> = emptySet(),
    onToggleSecondaryValue: (String) -> Unit = {},
    chessComEnabled: Boolean = false,
    chessComHabitLinks: Map<String, String> = emptyMap(),
    onSetChessComLink: (String, String?) -> Unit = { _, _ -> },
    garminEnabled: Boolean = false,
    garminHabitLinks: Map<String, String> = emptyMap(),
    onSetGarminLink: (String, String?) -> Unit = { _, _ -> },
    garminDateOfBirth: String = "",
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
    // ── Habit App Association parameters ───────────────────────────────────
    /** Map of habit name → ordered list of associated app package names. */
    habitAppAssociations: Map<String, List<String>> = emptyMap(),
    /** Called when the user taps "Add App" to associate an app with the habit. */
    onAddAppAssociation: (String) -> Unit = {},
    /** Called when the user removes an app association (habitName, packageName). */
    onRemoveAppAssociation: (String, String) -> Unit = { _, _ -> },
    /** Called when the user reorders an app association (habitName, fromIndex, toIndex). */
    onMoveAppAssociation: (String, Int, Int) -> Unit = { _, _, _ -> }
) {
    val hasSelection = selectedIndex >= 0

    // Other screens for habit move-to-screen
    val otherScreenIndices: List<Int> = if (hasSelection && !isPlaceholderSelected && habitScreens.size > 1) {
        val currentScreen = if (selectedHabitScreenIndex >= 0) selectedHabitScreenIndex else activeScreenIndex
        habitScreens.indices.filter { it != currentScreen }
    } else emptyList()

    var moveToScreenExpanded by remember { mutableStateOf(false) }
    var showSetCountDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var pendingCountDelta by remember { mutableStateOf(0) } // 0 = no pending change, 1 = increment, -1 = decrement

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
                // For divider habits, show editable true value (undivided total) under the counter
                // For Garmin-linked habits, show read-only Garmin metric value
                val isGarminLinked = selectedHabitName != null && selectedHabitName in garminHabitLinks
                val isDivider = selectedHabitName != null && (habitDividers[selectedHabitName] ?: 1) > 1
                if (isGarminLinked || isDivider) {
                    Spacer(modifier = Modifier.height(2.dp))
                    // For Garmin habits, derive the value live on every recomposition so it
                    // reflects garminMonthlyData updates that arrive asynchronously (e.g. after
                    // a "Test Connection" sync). Caching it in remember() keyed only on the
                    // habit name would leave a stale "-" when the data lands after selection.
                    val garminValueText: String = if (isGarminLinked) {
                        val garminType = garminHabitLinks[selectedHabitName]?.let { GarminType.fromKey(it) }
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
                    // For divider habits, keep an editable remembered field bound to the raw count.
                    var trueValueText by remember(selectedHabitName) {
                        mutableStateOf(selectedHabitRawTodayCount.toString())
                    }
                    // Sync when external count changes (e.g., from [−]/[+] buttons)
                    if (!isGarminLinked && trueValueText.toIntOrNull() != selectedHabitRawTodayCount) {
                        trueValueText = selectedHabitRawTodayCount.toString()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isGarminLinked) "garmin value:" else "true value:",
                            color = Color(0xFFAA88FF),
                            fontSize = 10.sp
                        )
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
                                    trueValueText = v.filter { it.isDigit() }
                                    val newCount = trueValueText.toIntOrNull() ?: 0
                                    onSetCount(selectedHabitName, newCount)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                modifier = Modifier.width(64.dp),
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
                            habitName = selectedHabitName!!,
                            initialNote = currentNote,
                            onConfirm = { newNote ->
                                onSetHabitNote(selectedHabitName!!, newNote)
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
                        }
                    }

                    // ── Today's text entries (view/edit) ──────────────────────
                    if (isTextInput && textInputFileUris.containsKey(selectedHabitName)) {
                        Spacer(modifier = Modifier.height(6.dp))

                        if (todayTextEntries.isNotEmpty()) {
                            Text(
                                text = "  Today's entries",
                                color = Color(0xFF88CCFF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            for ((timestamp, text) in todayTextEntries) {
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
                                           else "✓ ${linkNames.joinToString(", ")}",
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
                        conditionalLinkedHabits = conditionalLinkedHabits,
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

                        // Subtype data file picker
                        val hasSubtypeFile = subtypeDataFileUris.containsKey(selectedHabitName)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = "  Data file", color = Color(0xFFAAAAAA), fontSize = 12.sp)
                                Text(
                                    text = if (hasSubtypeFile) "✓ File selected" else "⚠ No file selected",
                                    color = if (hasSubtypeFile) Color(0xFF44DDAA) else Color(0xFFFF8844),
                                    fontSize = 10.sp
                                )
                            }
                            Button(
                                onClick = { onPickSubtypeDataFile(selectedHabitName) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0A3A2A)),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Folder,
                                    contentDescription = "Pick subtype data file",
                                    tint = Color(0xFF44DDAA),
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    if (hasSubtypeFile) "Change" else "Select",
                                    fontSize = 11.sp,
                                    color = Color(0xFF44DDAA)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // ── Meal toggle ──────────────────────────────────────────
                    MealToggleSection(
                        habitName = selectedHabitName,
                        isMeal = selectedHabitName in mealHabits,
                        onToggleMeal = onToggleMeal,
                        onOpenMealDetails = onOpenMealDetails
                    )

                    // ── Disabled / No-points / Secondary-value toggles ──────
                    HabitToggleSection(
                        habitName = selectedHabitName,
                        isDisabled = selectedHabitName in disabledHabits,
                        onToggleDisabled = onToggleDisabled,
                        isNoPoints = selectedHabitName in noPointsHabits,
                        onToggleNoPoints = onToggleNoPoints,
                        isSecondaryValue = selectedHabitName in secondaryValueHabits,
                        onToggleSecondaryValue = onToggleSecondaryValue
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

                    // ── Chess.com link toggle ────────────────────────────────
                    // Extracted to its own composable to keep EditModeControlBar
                    // under the JVM method-size limit.
                    if (chessComEnabled) {
                        ChessComLinkToggle(
                            habitName = selectedHabitName,
                            links = chessComHabitLinks,
                            onSetLink = { type -> onSetChessComLink(selectedHabitName, type) }
                        )
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
                                       else if (currentAppAssociations.size == 1) "Long-press opens app"
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

                    // ── Garmin link toggle ────────────────────────────────────
                    if (garminEnabled) {
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

                    // ── Restore this habit from a backup file ────────────────
                    // Only this habit is affected; the rest of the backup is
                    // ignored. Extracted to its own composable to keep
                    // EditModeControlBar under the JVM method-size limit.
                    RestoreFromBackupButton(onClick = onRestoreFromBackup)
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
    conditionalLinkedHabits: Map<String, Set<String>>,
    onBackfill: (String) -> Unit
) {
    val conditionalSources = remember(conditionalLinkedHabits, habitName) {
        conditionalLinkedHabits.entries
            .filter { habitName in it.value }
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
                    text = "Fed by ${conditionalSources.size} habit(s): ${conditionalSources.joinToString(", ")}",
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
    onConfirm: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val otherHabits = remember(allHabitNames, habitName) {
        allHabitNames.filter { it != habitName && it.isNotEmpty() }
    }
    var selected by remember(currentLinks) { mutableStateOf(currentLinks.toMutableSet()) }

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
                text = "Select habits to auto-increment when this habit is tapped:",
                color = Color(0xFF888888),
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (otherHabits.isEmpty()) {
                Text(
                    text = "No other habits available.",
                    color = Color(0xFF666666),
                    fontSize = 12.sp
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    otherHabits.forEach { name ->
                        val isChecked = name in selected
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(
                                    indication = null,
                                    interactionSource = remember { MutableInteractionSource() }
                                ) {
                                    val next = selected.toMutableSet()
                                    if (isChecked) next.remove(name) else next.add(name)
                                    selected = next
                                }
                                .background(
                                    if (isChecked) Color(0xFF2A0020) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
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
                    onClick = { onConfirm(selected.toSet()) },
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
                text = "Delete Habit",
                color = Color(0xFFFF8888),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Remove \"$habitName\" from the grid?\n\nThe habit data in your JSON files will NOT be deleted.",
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
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A0000))
                ) {
                    Text("Delete", color = Color(0xFFFF8888))
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
private fun AppPickerDialog(
    context: Context,
    onConfirm: (packageName: String, label: String) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

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
                        Column {
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
