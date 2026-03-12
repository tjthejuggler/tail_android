package com.example.tail.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.tail.data.Habit
import com.example.tail.data.HabitScreen
import com.example.tail.data.RollingHigh
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// Grid is 8 columns × 10 rows = 80 cells; 76 habits + 4 empty at end
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
    val selectedInfoHabit by viewModel.selectedInfoHabit.collectAsState()
    val selectedEditIndex by viewModel.selectedEditIndex.collectAsState()
    val habitScreens by viewModel.habitScreens.collectAsState()
    val activeScreenIndex by viewModel.activeScreenIndex.collectAsState()
    val context = LocalContext.current

    val today = LocalDate.now()
    val isToday = selectedDate == today

    val snackbarHostState = remember { SnackbarHostState() }
    var dialogHabit by remember { mutableStateOf<Habit?>(null) }
    var showAddScreenDialog by remember { mutableStateOf(false) }
    // Index of screen being renamed (-1 = none)
    var renamingScreenIndex by remember { mutableStateOf(-1) }

    // File picker launcher — requests persistent read+write permission
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

    // Show errors as snackbar
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage!!)
            viewModel.clearError()
        }
    }

    // Determine the active screen name for the title
    val activeScreenName: String? = if (habitScreens.isNotEmpty()) {
        habitScreens.getOrNull(activeScreenIndex)?.name
    } else null

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

                        // Date label
                        val dateLabel = if (isToday) "Today" else selectedDate.format(DISPLAY_DATE_FMT)
                        val dateLabelColor = if (isToday) Color.White else Color(0xFFFFD700)
                        Text(
                            text = dateLabel,
                            color = dateLabelColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 2.dp)
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

                        // Screen name label (shown when screens are configured)
                        if (activeScreenName != null) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = activeScreenName,
                                color = Color(0xFFFFAA44),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
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
                    IconButton(onClick = { filePicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Folder, contentDescription = "Open file")
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
            // Screen tabs — shown when multiple screens exist (always, not just edit mode)
            // In edit mode, tapping the active tab opens a rename dialog
            if (habitScreens.size > 1) {
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
                        text = "Tap 📂 to select your habitsdb_phone.txt file",
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
            } else {
                // Grid takes up most of the screen
                Box(modifier = Modifier.weight(1f)) {
                    HabitGrid(
                        habits = habits,
                        infoMode = infoMode,
                        editMode = editMode,
                        selectedInfoHabit = selectedInfoHabit,
                        selectedEditIndex = selectedEditIndex,
                        onHabitClick = { habit, index ->
                            when {
                                editMode -> viewModel.selectEditHabit(index)
                                infoMode -> viewModel.selectInfoHabit(habit)
                                habit.useCustomInput -> dialogHabit = habit
                                else -> viewModel.incrementHabit(habit.name, 1)
                            }
                        },
                        onHabitLongClick = { habit ->
                            if (!infoMode && !editMode) {
                                viewModel.toggleCustomInput(habit.name)
                            }
                        }
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
                    val selectedHabitName = if (selectedEditIndex >= 0 && selectedEditIndex < habits.size)
                        habits[selectedEditIndex].name else null
                    EditModeControlBar(
                        selectedIndex = selectedEditIndex,
                        selectedHabitName = selectedHabitName,
                        habitCount = habits.size,
                        habitScreens = habitScreens,
                        activeScreenIndex = activeScreenIndex,
                        selectedHabitScreenIndex = if (selectedHabitName != null)
                            viewModel.screenIndexForHabit(selectedHabitName) else -1,
                        customInputHabits = settings.customInputHabits,
                        onMoveLeft = { viewModel.moveSelectedHabit(-1) },
                        onMoveRight = { viewModel.moveSelectedHabit(+1) },
                        onMoveToScreen = { viewModel.moveHabitToScreen(it) },
                        onAddScreen = { showAddScreenDialog = true },
                        onDeleteScreen = { viewModel.deleteScreen(activeScreenIndex) },
                        onToggleCustomInput = { name -> viewModel.toggleCustomInput(name) }
                    )
                }
            }
        }
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
            // In edit mode, active tab shows a pencil hint to indicate it's renameable
            val label = if (editMode && isActive) "✎ ${screen.name}" else screen.name
            TextButton(
                onClick = { onTabClick(index) },
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (isActive) Color(0xFF3A2000) else Color.Transparent,
                    contentColor = if (isActive) Color(0xFFFFAA00) else Color(0xFF888888)
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
 * The 8-column lazy grid. Works for both normal mode and edit mode.
 * In edit mode, tapping selects a habit (highlighted with orange border).
 */
@Composable
private fun HabitGrid(
    habits: List<Habit>,
    infoMode: Boolean,
    editMode: Boolean,
    selectedInfoHabit: Habit?,
    selectedEditIndex: Int,
    onHabitClick: (Habit, Int) -> Unit,
    onHabitLongClick: (Habit) -> Unit
) {
    // Build a list of 80 nullable items (null = empty spacer)
    val cells: List<Habit?> = buildList {
        addAll(habits)
        repeat(TOTAL_CELLS - habits.size) { add(null) }
    }

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
                HabitButton(
                    habit = habit,
                    onClick = { onHabitClick(habit, index) },
                    onLongClick = { onHabitLongClick(habit) },
                    modifier = Modifier.padding(2.dp),
                    infoMode = infoMode,
                    editMode = editMode,
                    isSelected = isEditSelected || isInfoSelected
                )
            } else {
                Box(modifier = Modifier.aspectRatio(1f))
            }
        }
    }
}

// ── Edit mode control bar ─────────────────────────────────────────────────────

/**
 * Control bar shown below the grid in edit mode.
 * When a habit is selected it shows:
 *   • MOVE section: ← / → position buttons + screen-jump buttons for other screens
 *   • SETTINGS section: custom input toggle for the selected habit
 * When no habit is selected it shows a prompt + "Add Screen" button.
 */
@Composable
private fun EditModeControlBar(
    selectedIndex: Int,
    selectedHabitName: String?,
    habitCount: Int,
    habitScreens: List<HabitScreen>,
    activeScreenIndex: Int,
    selectedHabitScreenIndex: Int,   // which screen the selected habit is on (-1 if no screens)
    customInputHabits: Set<String>,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onMoveToScreen: (Int) -> Unit,
    onAddScreen: () -> Unit,
    onDeleteScreen: () -> Unit,
    onToggleCustomInput: (String) -> Unit
) {
    val hasSelection = selectedIndex >= 0
    val canMoveLeft = hasSelection && selectedIndex > 0
    val canMoveRight = hasSelection && selectedIndex < habitCount - 1

    // Other screens = all screens except the one the selected habit is currently on
    val otherScreenIndices: List<Int> = if (hasSelection && habitScreens.size > 1) {
        val currentScreen = if (selectedHabitScreenIndex >= 0) selectedHabitScreenIndex else activeScreenIndex
        habitScreens.indices.filter { it != currentScreen }
    } else emptyList()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1000))
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        if (!hasSelection) {
            // No habit selected — show prompt, Add Screen button, and (if >1 screen) Delete Screen button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "✏ Tap a habit to select it",
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Delete current screen — only shown when there are 2+ screens
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
        } else {
            // Habit selected — show name header
            Text(
                text = "Selected: $selectedHabitName",
                color = Color(0xFFFFAA00),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            // ── MOVE section ──────────────────────────────────────────────
            Text(
                text = "MOVE",
                color = Color(0xFF888888),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // ← position button
                Button(
                    onClick = onMoveLeft,
                    enabled = canMoveLeft,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5A3A00),
                        disabledContainerColor = Color(0xFF2A2A2A)
                    ),
                    modifier = Modifier.size(width = 48.dp, height = 32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(
                        "←",
                        fontSize = 14.sp,
                        color = if (canMoveLeft) Color(0xFFFFAA00) else Color(0xFF666666)
                    )
                }

                // → position button
                Button(
                    onClick = onMoveRight,
                    enabled = canMoveRight,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF5A3A00),
                        disabledContainerColor = Color(0xFF2A2A2A)
                    ),
                    modifier = Modifier.size(width = 48.dp, height = 32.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                ) {
                    Text(
                        "→",
                        fontSize = 14.sp,
                        color = if (canMoveRight) Color(0xFFFFAA00) else Color(0xFF666666)
                    )
                }

                // Screen jump buttons (only shown when there are other screens)
                otherScreenIndices.forEach { screenIdx ->
                    val screenName = habitScreens[screenIdx].name
                    Button(
                        onClick = { onMoveToScreen(screenIdx) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF003A5A)),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text(
                            screenName,
                            fontSize = 11.sp,
                            color = Color(0xFF88CCFF)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(color = Color(0xFF333300), thickness = 1.dp)
            Spacer(modifier = Modifier.height(6.dp))

            // ── SETTINGS section ──────────────────────────────────────────
            Text(
                text = "SETTINGS",
                color = Color(0xFF888888),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(4.dp))

            if (selectedHabitName != null) {
                val isCustomInput = selectedHabitName in customInputHabits
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Custom input",
                            color = Color(0xFFCCCCCC),
                            fontSize = 12.sp
                        )
                        Text(
                            text = if (isCustomInput) "Shows number picker on tap"
                            else "Simple +1 on tap",
                            color = Color(0xFF888888),
                            fontSize = 10.sp
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

// ── Info panel ────────────────────────────────────────────────────────────────

/**
 * Info panel shown below the grid when info mode is active.
 */
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
                // Habit name header
                Text(
                    text = habit.name,
                    color = Color(0xFFFFD700),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))

                // Streak info
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
