package com.example.tail.ui

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
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
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.RollingHigh
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
    val options: List<String>
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
    val errorMessage by viewModel.errorMessage.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
    val selectedDateLocation by viewModel.selectedDateLocation.collectAsState()
    val infoMode by viewModel.infoMode.collectAsState()
    val editMode by viewModel.editMode.collectAsState()
    val graphMode by viewModel.graphMode.collectAsState()
    val graphSelectedHabits by viewModel.graphSelectedHabits.collectAsState()
    val selectedInfoHabit by viewModel.selectedInfoHabit.collectAsState()
    val selectedEditIndex by viewModel.selectedEditIndex.collectAsState()
    val movePendingSourceIndex by viewModel.movePendingSourceIndex.collectAsState()
    val habitScreens by viewModel.habitScreens.collectAsState()
    val activeScreenIndex by viewModel.activeScreenIndex.collectAsState()
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
    // Habit name pending delete confirmation (null = none)
    var deleteConfirmHabitName by remember { mutableStateOf<String?>(null) }
    // Habit name for which icon picker is open (null = none)
    var iconPickerHabitName by remember { mutableStateOf<String?>(null) }
    // Habit name for which the conditional links picker is open (null = none)
    var conditionalLinksPickerHabit by remember { mutableStateOf<String?>(null) }

    // Text-input dialog state: non-null when the dialog should be shown
    var textInputDialogState by remember { mutableStateOf<TextInputDialogState?>(null) }

    // Timestamp editor dialog state
    var timestampEditorHabitName by remember { mutableStateOf<String?>(null) }
    var timestampEditorList by remember { mutableStateOf<List<String>>(emptyList()) }
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
                        // Back arrow — always available
                        IconButton(onClick = { viewModel.navigateDay(-1) }) {
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

                        // Forward arrow — disabled when already on today
                        IconButton(
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
                    // Info mode toggle button
                    IconButton(
                        onClick = { viewModel.toggleInfoMode() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (infoMode) Color(0xFF1A4A7A) else Color.Transparent
                        )
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = if (infoMode) "Info mode ON" else "Info mode OFF",
                            tint = if (infoMode) Color(0xFF88CCFF) else Color.White
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
                Box(modifier = Modifier.fillMaxSize()) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
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
                        infoMode = infoMode,
                        editMode = editMode,
                        graphMode = graphMode,
                        graphSelectedHabits = graphSelectedHabits,
                        selectedInfoHabit = selectedInfoHabit,
                        selectedEditIndex = selectedEditIndex,
                        movePendingSourceIndex = movePendingSourceIndex,
                        customIconOverrides = settings.habitIcons,
                        disabledHabits = settings.disabledHabits,
                        aiIconRepo = if (settings.aiIconsEnabled) viewModel.getAiIconRepo() else null,
                        onHabitClick = { habit, index ->
                            when {
                                graphMode -> viewModel.toggleGraphHabitSelection(habit.name)
                                editMode -> viewModel.selectEditHabit(index)
                                infoMode -> viewModel.selectInfoHabit(habit)
                                habit.name in settings.subtypedHabits -> {
                                    viewModel.loadSubtypeBreakdown(habit.name) { breakdown ->
                                        subtypeDialogBreakdown = breakdown
                                        subtypeDialogHabit = habit
                                    }
                                }
                                habit.name in settings.textInputHabits -> {
                                    val showOpts = habit.name in settings.textInputOptionsHabits
                                    if (showOpts) {
                                        viewModel.loadTextOptions(habit.name) { opts ->
                                            textInputDialogState = TextInputDialogState(
                                                habit = habit,
                                                showOptions = true,
                                                options = opts
                                            )
                                        }
                                    } else {
                                        textInputDialogState = TextInputDialogState(
                                            habit = habit,
                                            showOptions = false,
                                            options = emptyList()
                                        )
                                    }
                                }
                                habit.useCustomInput -> dialogHabit = habit
                                else -> {
                                    // When viewing a different day or habit is timeless, increment without timestamp
                                    val timeless = !isToday || habit.name in settings.timelessHabits
                                    viewModel.incrementHabit(habit.name, 1, recordTimestamp = !timeless)
                                    // Show increment toast with edit-time option
                                    incrementToastVersion++
                                    incrementToastHabit = habit.name
                                    incrementToastIsTimeless = timeless
                                    incrementToastOriginalTime = if (!timeless) com.example.tail.data.HabitTimestampRepository.nowTime() else ""
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
                        },
                        onHabitLongClick = { habit ->
                            if (!infoMode && !editMode && !graphMode) {
                                // Long-press increments without recording a timestamp
                                viewModel.incrementHabit(habit.name, 1, recordTimestamp = false)
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
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // Info panel — shown below grid when in info mode
                if (infoMode) {
                    HabitInfoPanel(
                        habit = selectedInfoHabit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
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
                        onStartMove = { viewModel.startMoveMode() },
                        onAddHabit = { addHabitAtIndex = selectedEditIndex },
                        onMoveToScreen = { viewModel.moveHabitToScreen(it) },
                        onAddScreen = { showAddScreenDialog = true },
                        onDeleteScreen = { viewModel.deleteScreen(activeScreenIndex) },
                        onToggleMaxOne = { name -> viewModel.toggleMaxOne(name) },
                        onToggleCustomInput = { name -> viewModel.toggleCustomInput(name) },
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
                        onSetDivider = { name, divisor -> viewModel.setHabitDivider(name, divisor) },
                        onToggleConditional = { name -> viewModel.toggleConditional(name) },
                        onSetConditionalLinks = { name -> conditionalLinksPickerHabit = name },
                        onToggleSubtyped = { name -> viewModel.toggleSubtyped(name) },
                        onSetSubtypes = { name, types -> viewModel.setHabitSubtypes(name, types) },
                        onPickSubtypeDataFile = { name ->
                            subtypeDataPickerHabit = name
                            subtypeDataFilePicker.launch(arrayOf("application/json", "*/*"))
                        },
                        hiddenScreenIds = settings.hiddenScreens,
                        onToggleScreenHidden = { viewModel.toggleScreenHidden(activeScreenIndex) },
                        disabledHabits = settings.disabledHabits,
                        onToggleDisabled = { name -> viewModel.toggleDisabledHabit(name) },
                        chessComEnabled = settings.chessComEnabled,
                        chessComHabitLinks = settings.chessComHabitLinks,
                        onSetChessComLink = { name, type -> viewModel.setChessComHabitLink(name, type) },
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
                        selectedHabitTimestampCount = selectedHabitTimestampCount,
                        onShowTimestamps = { name ->
                            timestampScope.launch {
                                timestampEditorList = viewModel.timestampRepo.getTimestampsForDay(name, selectedDate)
                                timestampEditorHabitName = name
                            }
                        }
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

    // ── Advice banner at bottom of screen (hidden in edit/info/graph modes) ──
    if (!editMode && !infoMode && !graphMode) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp)
        ) {
            AdviceBanner(viewModel = adviceViewModel)
        }
    }
    } // end Box

    // Load timestamp count when selected edit habit changes
    LaunchedEffect(selectedEditIndex, editMode, habits) {
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
                    // Increment the habit count to match
                    val currentHabit = habits.find { it.name == habitName }
                    if (currentHabit != null) {
                        viewModel.setHabitCount(habitName, currentHabit.rawTodayCount + 1)
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
            onSavePreferredCandidate = { candidate ->
                viewModel.savePreferredAutoCandidate(candidate)
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
        IncrementDialog(
            habitName = habit.name,
            currentCount = habit.todayCount,
            onConfirm = { amount ->
                viewModel.incrementHabit(habit.name, amount)
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

    // Text-input dialog
    textInputDialogState?.let { state ->
        TextInputDialog(
            habitName = state.habit.name,
            showOptions = state.showOptions,
            options = state.options,
            onConfirm = { text ->
                viewModel.saveTextEntry(state.habit.name, text)
                textInputDialogState = null
            },
            onDismiss = { textInputDialogState = null }
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
    infoMode: Boolean,
    editMode: Boolean,
    graphMode: Boolean = false,
    graphSelectedHabits: Set<String> = emptySet(),
    selectedInfoHabit: Habit?,
    selectedEditIndex: Int,
    movePendingSourceIndex: Int = -1,
    customIconOverrides: Map<String, String> = emptyMap(),
    disabledHabits: Set<String> = emptySet(),
    aiIconRepo: AiIconRepository? = null,
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
                val isInfoSelected = infoMode && selectedInfoHabit?.name == habit.name
                val isGraphSelected = graphMode && habit.name in graphSelectedHabits
                val isMovePendingSource = editMode && index == movePendingSourceIndex
                HabitButton(
                    habit = habit,
                    onClick = { onHabitClick(habit, index) },
                    onLongClick = { onHabitLongClick(habit) },
                    modifier = Modifier.padding(2.dp),
                    infoMode = infoMode,
                    editMode = editMode,
                    isSelected = isEditSelected || isInfoSelected || isGraphSelected,
                    isMovePendingSource = isMovePendingSource,
                    isMovePendingTarget = isMovePending && !isMovePendingSource && editMode,
                    customIconOverrides = customIconOverrides,
                    graphMode = graphMode,
                    isGraphSelected = isGraphSelected,
                    isDisabled = habit.name in disabledHabits,
                    aiIconRepo = aiIconRepo
                )
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
    onStartMove: () -> Unit,
    onAddHabit: () -> Unit,
    onMoveToScreen: (Int) -> Unit,
    onAddScreen: () -> Unit,
    onDeleteScreen: () -> Unit,
    onToggleMaxOne: (String) -> Unit,
    onToggleCustomInput: (String) -> Unit,
    onToggleTextInput: (String) -> Unit,
    onToggleTextInputOptions: (String) -> Unit,
    onPickTextInputFile: (String) -> Unit,
    onToggleDatedEntry: (String) -> Unit,
    onPickDatedEntryFile: (String) -> Unit,
    onDeleteHabit: (String) -> Unit,
    onChangeIcon: (String) -> Unit,
    onSetCount: (String, Int) -> Unit,
    onSetDivider: (String, Int) -> Unit,
    onToggleConditional: (String) -> Unit,
    onSetConditionalLinks: (String) -> Unit,
    onToggleSubtyped: (String) -> Unit,
    onSetSubtypes: (String, List<String>) -> Unit,
    onPickSubtypeDataFile: (String) -> Unit,
    hiddenScreenIds: Set<String> = emptySet(),
    onToggleScreenHidden: () -> Unit = {},
    disabledHabits: Set<String> = emptySet(),
    onToggleDisabled: (String) -> Unit = {},
    chessComEnabled: Boolean = false,
    chessComHabitLinks: Map<String, String> = emptyMap(),
    onSetChessComLink: (String, String?) -> Unit = { _, _ -> },
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
    /** Number of timestamps for the selected habit on the current day. */
    selectedHabitTimestampCount: Int = 0,
    /** Called when the user taps the timestamps button. */
    onShowTimestamps: (String) -> Unit = {}
) {
    val hasSelection = selectedIndex >= 0

    // Other screens for habit move-to-screen
    val otherScreenIndices: List<Int> = if (hasSelection && !isPlaceholderSelected && habitScreens.size > 1) {
        val currentScreen = if (selectedHabitScreenIndex >= 0) selectedHabitScreenIndex else activeScreenIndex
        habitScreens.indices.filter { it != currentScreen }
    } else emptyList()

    var moveToScreenExpanded by remember { mutableStateOf(false) }

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
                }
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
                                onClick = { onSetCount(selectedHabitName, selectedHabitRawTodayCount - 1) },
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
                                modifier = Modifier.width(28.dp),
                                textAlign = TextAlign.Center
                            )
                            Button(
                                onClick = { onSetCount(selectedHabitName, selectedHabitRawTodayCount + 1) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A3A00)),
                                modifier = Modifier.size(28.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) {
                                Text("+", fontSize = 14.sp, color = Color(0xFF88FF88))
                            }
                        }
                    }
                }
                // For divider habits, show editable true value (undivided total) under the counter
                if (selectedHabitName != null && (habitDividers[selectedHabitName] ?: 1) > 1) {
                    Spacer(modifier = Modifier.height(2.dp))
                    var trueValueText by remember(selectedHabitName) {
                        mutableStateOf(selectedHabitRawTodayCount.toString())
                    }
                    // Sync when external count changes (e.g., from [−]/[+] buttons)
                    if (trueValueText.toIntOrNull() != selectedHabitRawTodayCount) {
                        trueValueText = selectedHabitRawTodayCount.toString()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "true value:",
                            color = Color(0xFFAA88FF),
                            fontSize = 10.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
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
                // Timestamps button — shown when the habit has timestamps for today
                if (selectedHabitName != null && selectedHabitTimestampCount > 0) {
                    Button(
                        onClick = { onShowTimestamps(selectedHabitName) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A3A)),
                        modifier = Modifier.height(28.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                    ) {
                        Text("🕐 Timestamps ($selectedHabitTimestampCount)", fontSize = 10.sp, color = Color(0xFFBBBBFF))
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
                    }
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

                    // ── Disabled toggle ────────────────────────────────────
                    val isDisabled = selectedHabitName in disabledHabits
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
                            onCheckedChange = { onToggleDisabled(selectedHabitName) },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFFFF4444),
                                checkedTrackColor = Color(0xFF4A0000),
                                uncheckedThumbColor = Color(0xFF888888),
                                uncheckedTrackColor = Color(0xFF333333)
                            )
                        )
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
                    if (chessComEnabled) {
                        Spacer(modifier = Modifier.height(6.dp))

                        val currentChessLink = chessComHabitLinks[selectedHabitName]
                        val isChessLinked = currentChessLink != null
                        var chessDropdownExpanded by remember { mutableStateOf(false) }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(text = "♟ Chess.com", color = Color(0xFFCCCCCC), fontSize = 12.sp)
                                Text(
                                    text = if (isChessLinked) {
                                        val typeName = ChessComType.fromKey(currentChessLink)?.label ?: currentChessLink
                                        "Linked to: $typeName"
                                    } else "Not linked to chess.com",
                                    color = if (isChessLinked) Color(0xFF66BB6A) else Color(0xFF888888),
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = isChessLinked,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        chessDropdownExpanded = true
                                    } else {
                                        onSetChessComLink(selectedHabitName, null)
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

                        // Chess.com type picker dropdown
                        if (isChessLinked || chessDropdownExpanded) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Box {
                                Button(
                                    onClick = { chessDropdownExpanded = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20)),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    val label = if (currentChessLink != null) {
                                        ChessComType.fromKey(currentChessLink)?.label ?: "Select type"
                                    } else "Select type"
                                    Text(label, fontSize = 11.sp, color = Color(0xFF66BB6A))
                                }
                                DropdownMenu(
                                    expanded = chessDropdownExpanded,
                                    onDismissRequest = { chessDropdownExpanded = false }
                                ) {
                                    ChessComType.entries.forEach { type ->
                                        DropdownMenuItem(
                                            text = { Text(type.label) },
                                            onClick = {
                                                onSetChessComLink(selectedHabitName, type.name)
                                                chessDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
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
    modifier: Modifier = Modifier
) {
    val panelBg = Color(0xFF1A1A2E)
    val labelColor = Color(0xFFADD8E6)
    val valueColor = Color.White
    val dimColor = Color(0xFF888888)

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
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = habit.name,
                    color = Color(0xFFFFD700),
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

                InfoRow(
                    label = "day",
                    value = formatRollingRow(
                        currentVal = habit.currentDayValue.toDouble(),
                        high = RollingHigh(habit.allTimeHighDay.toDouble(), habit.allTimeHighDayDate)
                    ),
                    valueColor = valueColor,
                    labelColor = dimColor
                )
                InfoRow(
                    label = "week",
                    value = formatRollingRow(currentVal = habit.avgLast7Days, high = habit.allTimeHighWeek),
                    valueColor = valueColor,
                    labelColor = dimColor
                )
                InfoRow(
                    label = "month",
                    value = formatRollingRow(currentVal = habit.avgLast30Days, high = habit.allTimeHighMonth),
                    valueColor = valueColor,
                    labelColor = dimColor
                )
                InfoRow(
                    label = "year",
                    value = formatRollingRow(currentVal = habit.avgLast365Days, high = habit.allTimeHighYear),
                    valueColor = valueColor,
                    labelColor = dimColor
                )
            }
        }
    }
}

private fun formatRollingRow(currentVal: Double, high: RollingHigh): String {
    val cur = if (currentVal == currentVal.toLong().toDouble()) {
        currentVal.toLong().toString()
    } else {
        "%.2f".format(currentVal)
    }
    val highVal = if (high.value == high.value.toLong().toDouble()) {
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
