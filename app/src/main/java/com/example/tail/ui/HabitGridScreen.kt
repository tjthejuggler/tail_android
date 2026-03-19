package com.example.tail.ui

import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.RollingHigh
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    onNavigateToSettings: () -> Unit
) {
    val habits by viewModel.habits.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val selectedDate by viewModel.selectedDate.collectAsState()
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

    // Detect landscape orientation
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val snackbarHostState = remember { SnackbarHostState() }
    var dialogHabit by remember { mutableStateOf<Habit?>(null) }
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

    // Text-input dialog state: non-null when the dialog should be shown
    var textInputDialogState by remember { mutableStateOf<TextInputDialogState?>(null) }

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
                    // Graph mode toggle button
                    IconButton(
                        onClick = { viewModel.toggleGraphMode() },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (graphMode) Color(0xFF0A2A4A) else Color.Transparent
                        )
                    ) {
                        Icon(
                            Icons.Default.BarChart,
                            contentDescription = if (graphMode) "Graph mode ON" else "Graph mode OFF",
                            tint = if (graphMode) Color(0xFF4FC3F7) else Color.White
                        )
                    }
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
        ) {
            // Screen tabs — shown when multiple screens exist (hidden in landscape)
            if (habitScreens.size > 1 && !isLandscape) {
                ScreenTabRow(
                    screens = habitScreens,
                    activeIndex = activeScreenIndex,
                    editMode = editMode,
                    onTabClick = { idx ->
                        if (editMode && idx == activeScreenIndex) {
                            renamingScreenIndex = idx
                        } else {
                            viewModel.switchScreen(idx)
                        }
                    }
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
                Box(modifier = Modifier.weight(1f)) {
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
                        onHabitClick = { habit, index ->
                            when {
                                graphMode -> viewModel.toggleGraphHabitSelection(habit.name)
                                editMode -> viewModel.selectEditHabit(index)
                                infoMode -> viewModel.selectInfoHabit(habit)
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
                                else -> viewModel.incrementHabit(habit.name, 1)
                            }
                        },
                        onHabitLongClick = { habit ->
                            if (!infoMode && !editMode && !graphMode) {
                                viewModel.toggleCustomInput(habit.name)
                            }
                        },
                        onPlaceholderClick = { index ->
                            // In edit mode, selecting a placeholder works like selecting a habit
                            viewModel.selectEditHabit(index)
                        }
                    )
                }

                // Graph panel — shown below grid when in graph mode (portrait)
                if (graphMode) {
                    GraphsPanel(
                        viewModel = viewModel,
                        isLandscape = false,
                        modifier = Modifier
                            .fillMaxWidth()
                    )
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
                        onSetDivider = { name, divisor -> viewModel.setHabitDivider(name, divisor) }
                    )
                }
            }
        }
    }

    // Calendar picker dialog
    if (showCalendarPicker) {
        CalendarPickerDialog(
            initialDate     = selectedDate,
            getDailyTotals  = { yr, mo -> viewModel.getDailyTotals(yr, mo) },
            onDateSelected  = { date ->
                showCalendarPicker = false
                viewModel.navigateToDate(date)
            },
            onDismiss       = { showCalendarPicker = false }
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
            onDismiss = { iconPickerHabitName = null }
        )
    }
}

// ── Screen tab row ────────────────────────────────────────────────────────────

@Composable
private fun ScreenTabRow(
    screens: List<HabitScreen>,
    activeIndex: Int,
    editMode: Boolean,
    onTabClick: (Int) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF111111))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        screens.forEachIndexed { index, screen ->
            val isActive = index == activeIndex
            val label = if (editMode && isActive) "✎ ${screen.name}" else screen.name
            TextButton(
                onClick = { onTabClick(index) },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (isActive) Color(0xFF555555) else Color.Transparent,
                    contentColor = if (isActive) Color.White else Color(0xFF888888)
                ),
                modifier = Modifier.height(32.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                )
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
                    isGraphSelected = isGraphSelected
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
    onSetDivider: (String, Int) -> Unit
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
                        // Count adjuster: [−] rawCount [+]
                        // Always shows the raw input value (before any divider is applied)
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
                                text = selectedHabitRawTodayCount.toString(),
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

                    // Screen jump buttons
                    otherScreenIndices.forEach { screenIdx ->
                        val screenName = habitScreens[screenIdx].name
                        Button(
                            onClick = { onMoveToScreen(screenIdx) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003A5A)),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text(screenName, fontSize = 11.sp, color = Color(0xFF88CCFF))
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
                                onValueChange = { v ->
                                    divisorText = v.filter { it.isDigit() }
                                    val d = divisorText.toIntOrNull() ?: 0
                                    if (d >= 2) onSetDivider(selectedHabitName, d)
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Number
                                ),
                                modifier = Modifier.width(80.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedTextColor = Color(0xFFFF88FF),
                                    unfocusedTextColor = Color(0xFFFF88FF),
                                    focusedBorderColor = Color(0xFFFF88FF),
                                    unfocusedBorderColor = Color(0xFF884488)
                                ),
                                textStyle = TextStyle(
                                    fontSize = 13.sp,
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

@Composable
private fun IconPickerDialog(
    habitName: String,
    currentIconName: String?,
    onIconSelected: (String?) -> Unit,
    onDismiss: () -> Unit
) {
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
                    .height(400.dp),
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
