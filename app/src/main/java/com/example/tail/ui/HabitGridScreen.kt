package com.example.tail.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.tail.data.Habit

// Grid is 8 columns × 10 rows = 80 cells; 76 habits + 4 empty at end
private const val GRID_COLUMNS = 8
private const val TOTAL_CELLS = 80

/**
 * Main screen: 8×10 habit grid with top bar actions.
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
    val context = LocalContext.current

    val snackbarHostState = remember { SnackbarHostState() }
    var dialogHabit by remember { mutableStateOf<Habit?>(null) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tail") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else if (habits.isEmpty() && settings.fileUri.isEmpty()) {
                Text(
                    text = "Tap 📂 to select your habitsdb_phone.txt file",
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                )
            } else {
                HabitGrid(
                    habits = habits,
                    onHabitClick = { habit ->
                        if (habit.useCustomInput) {
                            dialogHabit = habit
                        } else {
                            viewModel.incrementHabit(habit.name, 1)
                        }
                    },
                    onHabitLongClick = { habit ->
                        viewModel.toggleCustomInput(habit.name)
                    }
                )
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
}

/**
 * The 8-column lazy grid. Renders 76 habit buttons + 4 empty spacers = 80 cells.
 */
@Composable
private fun HabitGrid(
    habits: List<Habit>,
    onHabitClick: (Habit) -> Unit,
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
        items(cells) { habit ->
            if (habit != null) {
                HabitButton(
                    habit = habit,
                    onClick = { onHabitClick(habit) },
                    onLongClick = { onHabitLongClick(habit) },
                    modifier = Modifier.padding(2.dp)
                )
            } else {
                Box(modifier = Modifier.aspectRatio(1f))
            }
        }
    }
}

