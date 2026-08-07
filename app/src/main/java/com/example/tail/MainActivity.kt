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
import com.example.tail.data.AdviceRepository
import com.example.tail.data.DatedEntryRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.SubtypeDataRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.data.TimedDataRepository
import com.example.tail.data.backup.AutoBackupManager
import com.example.tail.data.backup.BackupManager
import com.example.tail.data.debug.DebugNoteRepository
import com.example.tail.data.debug.DebugPreferences
import com.example.tail.data.parseDate
import com.example.tail.ui.AdviceViewModel
import com.example.tail.ui.AdviceViewModelFactory
import com.example.tail.ui.AppStatsScreen
import com.example.tail.ui.HabitGridScreen
import com.example.tail.ui.HabitViewModel
import com.example.tail.ui.HabitViewModelFactory
import com.example.tail.ui.MapScreen
import com.example.tail.ui.SettingsScreen
import com.example.tail.ui.debug.DebugBubbleOverlay
import com.example.tail.ui.theme.TailTheme
import kotlinx.coroutines.launch

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

        val adviceRepo = AdviceRepository(applicationContext)

        val habitsRepo = HabitsRepository()
        val settingsRepo = SettingsRepository(applicationContext)
        val textInputRepo = TextInputRepository()
        val subtypeDataRepo = SubtypeDataRepository()
        val timedDataRepo = TimedDataRepository()

        val backupManager = BackupManager(
            context = applicationContext,
            settingsRepo = settingsRepo,
            adviceRepo = adviceRepo,
            habitsRepo = habitsRepo,
            textInputRepo = textInputRepo,
            subtypeDataRepo = subtypeDataRepo,
            timedDataRepo = timedDataRepo,
            debugPrefs = debugPrefs
        )

        val autoBackupManager = AutoBackupManager(
            context = applicationContext,
            settingsRepo = settingsRepo,
            backupManager = backupManager
        )

        setContent {
            TailTheme(darkTheme = true) {
                TailApp(
                    habitsRepo = habitsRepo,
                    settingsRepo = settingsRepo,
                    textInputRepo = textInputRepo,
                    datedEntryRepo = DatedEntryRepository(),
                    subtypeDataRepo = subtypeDataRepo,
                    timedDataRepo = timedDataRepo,
                    adviceRepo = adviceRepo,
                    debugPrefs = debugPrefs,
                    debugNoteRepo = debugNoteRepo,
                    backupManager = backupManager,
                    autoBackupManager = autoBackupManager
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
    adviceRepo: AdviceRepository,
    debugPrefs: DebugPreferences,
    debugNoteRepo: DebugNoteRepository,
    backupManager: BackupManager,
    autoBackupManager: AutoBackupManager
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
            context = context,
            backupManager = backupManager
        )
    )
    val adviceViewModel: AdviceViewModel = viewModel(
        factory = AdviceViewModelFactory(adviceRepo)
    )

    // Track current route for the debug bubble
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // ON_START: snap date to today if stale from a previous session (overnight),
    //           AND run the once-per-day automatic backup BEFORE any DB read/write.
    //           Only fires when the app truly returns from background, not on
    //           in-app navigation — so map→grid date sync is preserved.
    // ON_RESUME: reload phone DB and sync dated entries (cheap when unchanged).
    //
    // Ordering is critical: the auto-backup MUST complete (or no-op because no
    // folder is configured) before any habit DB load/save runs, so that a
    // backup captures the on-disk state in its sync-stable pre-launch form.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val appScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START  -> {
                    viewModel.onAppStarted()
                    // Run today's auto-backup in the background. Cheap (~1 DataStore
                    // read) when already done today; otherwise it streams the full
                    // bundle to the SAF folder. Errors are logged inside the manager.
                    appScope.launch {
                        try {
                            autoBackupManager.runIfNeeded()
                        } catch (t: Throwable) {
                            android.util.Log.w("TailApp", "auto-backup threw: ${t.message}", t)
                        }
                    }
                }
                Lifecycle.Event.ON_RESUME -> viewModel.onAppForegrounded()
                else -> Unit
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
                    adviceViewModel = adviceViewModel,
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
                    adviceViewModel = adviceViewModel,
                    debugPrefs = debugPrefs,
                    backupManager = backupManager,
                    autoBackupManager = autoBackupManager,
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
