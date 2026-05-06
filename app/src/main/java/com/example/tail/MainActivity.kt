package com.example.tail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.tail.data.DatedEntryRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.data.TimedDataRepository
import com.example.tail.data.debug.DebugNoteRepository
import com.example.tail.data.debug.DebugPreferences
import com.example.tail.data.parseDate
import com.example.tail.ui.AppStatsScreen
import com.example.tail.ui.HabitGridScreen
import com.example.tail.ui.HabitViewModel
import com.example.tail.ui.HabitViewModelFactory
import com.example.tail.ui.MapScreen
import com.example.tail.ui.SettingsScreen
import com.example.tail.ui.debug.DebugBubbleOverlay
import com.example.tail.ui.theme.TailTheme

private const val ROUTE_GRID = "grid"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_APP_STATS = "app_stats"
private const val ROUTE_MAP = "map"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val debugPrefs = DebugPreferences(applicationContext)
        val debugNoteRepo = DebugNoteRepository(applicationContext, debugPrefs)

        setContent {
            TailTheme(darkTheme = true) {
                TailApp(
                    habitsRepo = HabitsRepository(),
                    settingsRepo = SettingsRepository(applicationContext),
                    textInputRepo = TextInputRepository(),
                    datedEntryRepo = DatedEntryRepository(),
                    subtypeDataRepo = SubtypeDataRepository(),
                    timedDataRepo = TimedDataRepository(),
                    debugPrefs = debugPrefs,
                    debugNoteRepo = debugNoteRepo
                )
            }
        }
    }
}

@Composable
private fun TailApp(
    habitsRepo: HabitsRepository,
    settingsRepo: SettingsRepository,
    textInputRepo: TextInputRepository,
    datedEntryRepo: DatedEntryRepository,
    subtypeDataRepo: SubtypeDataRepository,
    timedDataRepo: TimedDataRepository,
    debugPrefs: DebugPreferences,
    debugNoteRepo: DebugNoteRepository
) {
    val navController = rememberNavController()
    val context = androidx.compose.ui.platform.LocalContext.current
    val viewModel: HabitViewModel = viewModel(
        factory = HabitViewModelFactory(
            habitsRepo = habitsRepo,
            settingsRepo = settingsRepo,
            textInputRepo = textInputRepo,
            datedEntryRepo = datedEntryRepo,
            subtypeDataRepo = subtypeDataRepo,
            timedDataRepo = timedDataRepo,
            context = context
        )
    )

    // Track current route for the debug bubble
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Trigger a dated-entry sync every time the app comes to the foreground.
    // Uses file-size comparison so it's essentially free when nothing has changed.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onAppForegrounded()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box {
        NavHost(navController = navController, startDestination = ROUTE_GRID) {
            composable(ROUTE_GRID) {
                HabitGridScreen(
                    viewModel = viewModel,
                    onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) },
                    onNavigateToMap = { navController.navigate(ROUTE_MAP) }
                )
            }
            composable(ROUTE_MAP) {
                MapScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(ROUTE_SETTINGS) {
                SettingsScreen(
                    viewModel = viewModel,
                    debugPrefs = debugPrefs,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAppStats = { navController.navigate(ROUTE_APP_STATS) }
                )
            }
            composable(ROUTE_APP_STATS) {
                AppStatsScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToDate = { date ->
                        // Navigate to the date on the main grid, popping back to grid first
                        viewModel.navigateToDate(date)
                        navController.popBackStack(ROUTE_GRID, inclusive = false)
                    }
                )
            }
        }

        // Debug bubble overlay — shown on top of all screens when enabled
        DebugBubbleOverlay(
            currentRoute = currentRoute,
            debugPrefs = debugPrefs,
            debugNoteRepo = debugNoteRepo
        )
    }
}
