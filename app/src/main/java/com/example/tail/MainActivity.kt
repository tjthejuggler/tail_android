package com.example.tail

import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.example.tail.data.backup.GoogleDriveManager
import com.example.tail.data.debug.DebugNoteRepository
import com.example.tail.data.debug.DebugPreferences
import com.example.tail.data.parseDate
import com.example.tail.ui.advice.AdviceViewModel
import com.example.tail.ui.advice.AdviceViewModelFactory
import com.example.tail.data.ExperimentStatus
import com.example.tail.data.ExperimentStatusStore
import com.example.tail.ui.chess.ChessReadinessStatsScreen
import com.example.tail.ui.debug.DebugBubbleOverlay
import com.example.tail.ui.experiment.ExperimentFlashOverlay
import com.example.tail.ui.grid.HabitGridScreen
import com.example.tail.ui.map.MapScreen
import com.example.tail.ui.viewmodel.navigateToDate
import com.example.tail.ui.viewmodel.resetToHomeGrid
import com.example.tail.ui.settings.SettingsScreen
import com.example.tail.ui.stats.AppStatsScreen
import com.example.tail.ui.stats.MapStatsScreen
import com.example.tail.ui.theme.TailTheme
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.HabitViewModelFactory
import kotlinx.coroutines.launch

private const val ROUTE_GRID = "grid"
private const val ROUTE_SETTINGS = "settings"
private const val ROUTE_MAP = "map"
private const val ROUTE_MAP_STATS = "map_stats"

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra: navigation route to open on launch (deep link from overlays). */
        const val EXTRA_OPEN_ROUTE = "open_route"

        /** Minimum spacing between foreground-triggered one-shot syncs. */
        private const val FOREGROUND_SYNC_THROTTLE_MS = 10 * 60 * 1000L // 10 min

        /** Timestamp of the last foreground sync (0 = never). */
        @Volatile private var lastForegroundSyncAt = 0L

        /** Chess Readiness stats screen — deep-linked from the floating bubble menu. */
        const val ROUTE_CHESS_READINESS_STATS = "chess_readiness_stats"

        /** App Stats screen — deep-linked from the app-stats record notifications. */
        const val ROUTE_APP_STATS = "app_stats"

        /** Quick Capture History — deep-linked from the review notification. */
        const val ROUTE_QUICK_CAPTURE_HISTORY = "quick_capture_history"

        /** Intent extra: open the habit grid's notification popup on launch
         *  (deep link from the tier-bar widget's notification badge). */
        const val EXTRA_OPEN_NOTIFICATIONS = "open_notifications"

        /** Intent extra: habit screen (grid tab) index to open on launch
         *  (deep link from the tier-bar widget's touch zones). */
        const val EXTRA_OPEN_SCREEN_INDEX = "open_screen_index"

        /** Intent extra: habit NAME whose grid square should be opened on
         *  launch (deep link from the combined-scene widget's water glass —
         *  opens the habit's input dialog as if its square had been tapped). */
        const val EXTRA_OPEN_HABIT = "open_habit_name"
    }

    /** One-shot hand-off: set by the deep-link handler, consumed by
     *  HabitGridScreen to auto-open the in-app notification dialog, switch
     *  to the deep-linked habit screen tab, and/or auto-tap a deep-linked
     *  habit square. Compose state so the grid reacts even when the
     *  activity is already alive (onNewIntent). */
    object NotificationsDeepLink {
        private val _open = androidx.compose.runtime.mutableStateOf(false)
        private val _screenIndex = androidx.compose.runtime.mutableIntStateOf(-1)
        private val _habitName = androidx.compose.runtime.mutableStateOf<String?>(null)
        var open: Boolean
            get() = _open.value
            set(value) { _open.value = value }
        var screenIndex: Int
            get() = _screenIndex.intValue
            set(value) { _screenIndex.intValue = value }

        /** Habit whose square should be tapped programmatically (null = none). */
        var habitName: String?
            get() = _habitName.value
            set(value) { _habitName.value = value }
    }

    /** Home-button reset signal (Tail is the launcher, 2026-11-23): every
     *  warm system HOME press must land on the plain main grid. TailApp
     *  bumps [generation] after resetting the ViewModel-level mode state;
     *  the ROUTE_GRID destination keys the HabitGridScreen subtree on it,
     *  so the bump disposes and recreates the grid — dropping all
     *  composable-local dialog / popup state that the ViewModel can't
     *  reach. */
    object HomeResetSignal {
        private val _generation = androidx.compose.runtime.mutableIntStateOf(0)
        val generation: Int get() = _generation.intValue

        /** One-shot: true exactly for the grid instance created by the most
         *  recent home reset (consumed on first read). Without this, the
         *  recreated grid would rebuild the increment-suggestion flash
         *  ~350 ms after the home press — home must land on the PLAIN
         *  grid, not re-pop a popup. */
        @Volatile private var suppressFlashOnce = false
        fun bump() {
            _generation.intValue += 1
            suppressFlashOnce = true
        }
        fun consumeSuppressFlash(): Boolean {
            val v = suppressFlashOnce
            suppressFlashOnce = false
            return v
        }
    }
    /** Dedup gate for Smart Open: a config-change recreation redelivers the
     *  SAME intent instance, and it must not re-trigger the smart-open
     *  guess (which would yank the user off their current screen on
     *  rotation). Only genuinely fresh plain intents pass. */
    object SmartOpenGate {
        @Volatile private var lastIntent: android.content.Intent? = null
        fun shouldHandle(intent: android.content.Intent): Boolean {
            if (intent === lastIntent) return false
            lastIntent = intent
            return true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Launcher role: system back at the root of the home task must NOT
        // finish the home activity — Android would instantly relaunch it
        // (full recreate; stateNotNeeded discards the saved state), which the
        // user sees as a pointless reload + loading spinner while staying on
        // the same screen. The launcher-standard behaviour is to hide the
        // task instead: moveTaskToBack surfaces whatever task is underneath
        // — i.e. the app the user was in before pressing home — with Tail's
        // state frozen intact for the next home press. Registered here in
        // onCreate so every Compose-level handler (nav stack pops, dialog
        // dismissals) outranks it; this is only the final fallback.
        onBackPressedDispatcher.addCallback(
            this,
            object : androidx.activity.OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    moveTaskToBack(true)
                }
            }
        )

        // Habit-ask notifications (movie bridge + scheduled asks) need the
        // runtime POST_NOTIFICATIONS permission on Android 13+; request it
        // once on first launch.
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            androidx.core.app.ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }

        // Register all notification channels eagerly so per-category controls
        // (e.g. blocking "Movie confirmations" off the Garmin watch) are
        // visible in system settings immediately, before any notification posts.
        com.example.tail.notify.HabitNotifier.ensureChannel(applicationContext)

        // Revive the always-on-top stats overlay if the user enabled it but it
        // died (process kill, APK update, OEM cleanup). No-op when off/running.
        com.example.tail.widget.StatsOverlayService.ensureRunning(applicationContext)

        // Background bridge sync: TailCue predictive warnings (cues) + the
        // movie cache in ONE hourly pass (merged 2026-11-19; was two separate
        // 15-min pollers — the cause of the constant background traffic and
        // battery drain). Skips itself when offline (CONNECTED constraint:
        // airplane mode = zero wakeups) or when battery is low, and stands
        // down within 50 min of any foreground sync (see onResume). UPDATE
        // policy upgrades old installs and cancels the legacy movie poller.
        com.example.tail.notify.TailCueSyncWorker.schedule(applicationContext)

        // Keep the phone-local Garmin cache warm even when the app is closed.
        // Previously Garmin only synced while the main UI was open (foreground
        // hook + a viewModelScope polling loop that died with the Activity and
        // was only started when settings were saved) — so Garmin data silently
        // went stale while movie/PC-widget sync kept working. 2026-09-26.
        com.example.tail.notify.GarminSyncWorker.schedule(applicationContext)

        // Daily environment self-heal: gap-resuming backlog run that captures
        // any day the in-app flow missed (app not opened, network flake,
        // settings-restore dropping the master switch — which silently
        // stalled the env habits for 11 days, 2026-10-05 → 2026-10-16).
        // KEEP policy; already-captured days cost nothing.
        com.example.tail.notify.EnvironmentBacklogWorker.scheduleDaily(applicationContext)

        // Phone usage: ~4 h UsageStats aggregation (limited ~7-day event
        // history means days are lost without frequent runs). KEEP policy.
        com.example.tail.notify.PhoneUsageWorker.schedule(applicationContext)

        val debugPrefs = DebugPreferences(applicationContext)
        val debugNoteRepo = DebugNoteRepository(applicationContext, debugPrefs)

        val adviceRepo = AdviceRepository(applicationContext)

        val habitsRepo = HabitsRepository()
        val settingsRepo = SettingsRepository(applicationContext)
        val textInputRepo = TextInputRepository()
        val subtypeDataRepo = SubtypeDataRepository(applicationContext)
        val timedDataRepo = TimedDataRepository(applicationContext)

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

        val gdriveManager = GoogleDriveManager(
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
                    autoBackupManager = autoBackupManager,
                    gdriveManager = gdriveManager
                )
            }
        }
    }

    /**
     * ROOT FIX for the "drift after visiting the map" bug (2026-11-25).
     *
     * Hard evidence from the drifted state: the platform window was perfect
     * (frame [0,0][1080,2316], insetsChanged=false) and so was the
     * ComposeView, yet the Scaffold content was laid out at [0,206][1038,…]
     * — i.e. padded with the LANDSCAPE insets (top 206, right 42) while the
     * phone was in portrait. So the stale data lived inside Compose:
     * `WindowInsetsHolder` keeps the last insets handed to the ComposeView.
     * MapScreen rotates to landscape + hides the bars; because the manifest
     * handles rotation via configChanges (no Activity recreation), flipping
     * back to portrait re-lays-out the views but never re-dispatches a NEW
     * insets object into the ComposeView, so Compose kept the landscape
     * snapshot forever (it even survived the grid subtree being recreated
     * by a HOME press). requestApplyInsets() re-sends the same settled state,
     * so a plain re-show of the bars never fixed it.
     *
     * Fix: whenever the configuration changes (rotation) — and whenever the
     * window regains focus (returning from the immersive map) — build fresh
     * insets from the window's CURRENT stable-inset state and dispatch them
     * straight into the ComposeView, forcing Compose to re-read real values.
     */
    private fun redispatchFreshInsetsToCompose() {
        val decor = window?.decorView ?: return
        val root = decor.findViewById<ViewGroup>(android.R.id.content) ?: return
        // Current, orientation-correct system-bar insets for THIS window.
        val fresh = ViewCompat.getRootWindowInsets(decor) ?: WindowInsetsCompat.CONSUMED
        // Hand them to every Compose host under the content view so each
        // WindowInsetsHolder re-reads instead of keeping its old snapshot.
        fun dispatch(v: View) {
            if (v is androidx.compose.ui.platform.AbstractComposeView) {
                ViewCompat.dispatchApplyWindowInsets(v, fresh)
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) dispatch(v.getChildAt(i))
        }
        dispatch(root)
        // Follow with a platform re-apply so the ViewRootImpl also re-sends
        // (harmless when the state is unchanged; authoritative when not).
        ViewCompat.requestApplyInsets(decor)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation is handled here (manifest configChanges), so this is the
        // moment the old orientation's insets become wrong. Re-dispatch on
        // the next frame, after the views have been re-laid-out.
        window?.decorView?.post { redispatchFreshInsetsToCompose() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // Covers the return trip from the immersive map screen even when
            // the orientation ends up the same as before it was opened.
            window?.decorView?.post { redispatchFreshInsetsToCompose() }
        }
    }

    override fun onResume() {
        super.onResume()
        // Foreground refresh of the bridge-backed data (TailCue cues, movie
        // cache). Background syncing was cut back to once an hour (battery:
        // the old 15-min polling caused constant background traffic,
        // 2026-11-19) — while the app is actually open and visible we can
        // afford an immediate one-shot pass so it still feels live.
        // Throttled: rapid re-entries (permission dialogs, screen-offs of a
        // few seconds, config changes) must not re-hit the network.
        val now = System.currentTimeMillis()
        if (now - lastForegroundSyncAt >= FOREGROUND_SYNC_THROTTLE_MS) {
            lastForegroundSyncAt = now
            // One combined pass (cues + movies). It also records the sync
            // timestamp, which suppresses the next periodic background pass.
            com.example.tail.notify.TailCueSyncWorker.syncNow(applicationContext)
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
    autoBackupManager: AutoBackupManager,
    gdriveManager: GoogleDriveManager
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

    // TailCue experiment flash: while an experiment is active (mirrored from
    // the PC via the bridge), every app open starts with a brief full-screen
    // "you are in an experiment" reminder. State lives here so the ON_START
    // hook can (re)trigger it on every foreground entry.
    var experimentFlash by remember { mutableStateOf<ExperimentStatus?>(null) }

    // Track current route for the debug bubble
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Deep links from system overlays (e.g. the floating bubble's chess menu):
    // navigate straight to the requested route. Handles both the launch intent
    // and onNewIntent deliveries while the activity is already alive.
    // A PLAIN open (launcher icon, widget "Main"/grid zone — no extras) gets
    // the Smart Open guess instead: resolve the contextually-best habit
    // screen on IO and hand it to the grid like a deep link. Explicit
    // extras always win; a no-confidence guess falls through silently.
    //
    // allowSmartOpen: false for warm onNewIntent deliveries of system HOME /
    // launcher-icon taps. With the old `standard` launch mode those never
    // reached the running activity (the task was simply brought forward);
    // now that MainActivity is also the HOME activity (singleTask), the
    // system routes every home press here, and re-running Smart Open would
    // yank the user off their current screen. Warm deliveries instead get
    // the home reset (below): home press / icon tap lands on the PLAIN main
    // grid — stacked screens popped, edit/graph/schedule modes exited,
    // date snapped to today. A COLD open (fresh process — the post-unlock
    // entry path) still gets the Smart Open guess.
    val smartOpenScope = androidx.compose.runtime.rememberCoroutineScope()
    val handleOpenIntent: (android.content.Intent, Boolean) -> Unit = { intent, allowSmartOpen ->
        var explicit = false
        intent.getStringExtra(MainActivity.EXTRA_OPEN_ROUTE)?.let { route ->
            explicit = true
            if (navController.currentDestination?.route != route) {
                navController.navigate(route)
            }
        }
        if (intent.getBooleanExtra(MainActivity.EXTRA_OPEN_NOTIFICATIONS, false)) {
            explicit = true
            MainActivity.NotificationsDeepLink.open = true
        }
        intent.getIntExtra(MainActivity.EXTRA_OPEN_SCREEN_INDEX, -1).let { idx ->
            if (idx >= 0) {
                explicit = true
                MainActivity.NotificationsDeepLink.screenIndex = idx
            }
        }
        intent.getStringExtra(MainActivity.EXTRA_OPEN_HABIT)?.let { name ->
            if (name.isNotBlank()) {
                explicit = true
                MainActivity.NotificationsDeepLink.habitName = name
            }
        }
        val isSystemHomeOrIconTap =
            intent.action == android.content.Intent.ACTION_MAIN &&
                (
                    intent.hasCategory(android.content.Intent.CATEGORY_HOME) ||
                        intent.hasCategory(android.content.Intent.CATEGORY_LAUNCHER)
                    )
        val skipSmartOpen = !allowSmartOpen && isSystemHomeOrIconTap
        // Warm HOME press (Tail is the launcher): always land on the plain
        // main grid. Pop any stacked screens (settings/map/stats/...), exit
        // the ViewModel-level modes (edit/graph/schedule), snap date +
        // screen tab back to defaults, and bump the reset generation so the
        // grid subtree is recreated without its open dialogs/popups.
        // Guarded by !explicit so genuine deep links (which carry extras)
        // always win over the reset.
        if (!allowSmartOpen && !explicit && isSystemHomeOrIconTap) {
            if (navController.currentDestination?.route != ROUTE_GRID) {
                navController.popBackStack(ROUTE_GRID, inclusive = false)
            }
            viewModel.resetToHomeGrid()
            MainActivity.HomeResetSignal.bump()
        }
        if (!explicit && !skipSmartOpen && MainActivity.SmartOpenGate.shouldHandle(intent)) {
            smartOpenScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val idx = com.example.tail.data.SmartOpenStore.resolveForLaunch(context)
                if (idx >= 0) MainActivity.NotificationsDeepLink.screenIndex = idx
            }
        }
    }
    val activity = context as? androidx.activity.ComponentActivity
    if (activity != null) {
        androidx.compose.runtime.LaunchedEffect(activity) {
            activity.intent?.let { handleOpenIntent(it, true) }
        }
        androidx.compose.runtime.DisposableEffect(activity) {
            val listener = androidx.core.util.Consumer<android.content.Intent> {
                handleOpenIntent(it, false)
            }
            activity.addOnNewIntentListener(listener)
            onDispose { activity.removeOnNewIntentListener(listener) }
        }
    }

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
                    // Flash "you are in an experiment" if one is running.
                    experimentFlash = ExperimentStatusStore.load(context)
                    // Quick captures the AI couldn't act on: notify the user
                    // so the images can be assigned + retried from the history.
                    appScope.launch {
                        try {
                            val appContext = context.applicationContext
                            val count = com.example.tail.data.meal.VisionQueueRepository(appContext)
                                .reviewItemCount()
                            if (count > 0) {
                                com.example.tail.notify.QuickCaptureReviewNotifier.post(appContext, count)
                            } else {
                                com.example.tail.notify.QuickCaptureReviewNotifier.cancel(appContext)
                            }
                        } catch (t: Throwable) {
                            android.util.Log.w("TailApp", "review-count check threw: ${t.message}", t)
                        }
                    }
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
                    // Google Drive auto-backup runs AFTER the local one so a
                    // slow/failing Drive upload never delays the local backup.
                    appScope.launch {
                        try {
                            gdriveManager.runAutoBackupIfNeeded()
                        } catch (t: Throwable) {
                            android.util.Log.w("TailApp", "gdrive auto-backup threw: ${t.message}", t)
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
                // key(HomeResetSignal.generation): a warm HOME press bumps
                // the generation, disposing + recreating the entire grid
                // subtree so every composable-local dialog/popup state is
                // dropped and the plain grid shows (see HomeResetSignal).
                key(MainActivity.HomeResetSignal.generation) {
                    HabitGridScreen(
                        viewModel = viewModel,
                        adviceViewModel = adviceViewModel,
                        onNavigateToSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onNavigateToMap = { navController.navigate(ROUTE_MAP) },
                        onNavigateToAppStats = {
                            navController.navigate(MainActivity.ROUTE_APP_STATS)
                        }
                    )
                }
            }
            composable(ROUTE_MAP) {
                MapScreen(
                    viewModel = viewModel,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToStats = { navController.navigate(ROUTE_MAP_STATS) }
                )
            }
            composable(ROUTE_MAP_STATS) {
                MapStatsScreen(
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
                    gdriveManager = gdriveManager,
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToAppStats = {
                        navController.navigate(MainActivity.ROUTE_APP_STATS)
                    },
                    onNavigateToChessReadinessStats = {
                        navController.navigate(MainActivity.ROUTE_CHESS_READINESS_STATS)
                    }
                )
            }
            composable(MainActivity.ROUTE_APP_STATS) {
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
            composable(MainActivity.ROUTE_CHESS_READINESS_STATS) {
                ChessReadinessStatsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    loadingMetrics = viewModel.loadingMetrics.value
                )
            }
            composable(MainActivity.ROUTE_QUICK_CAPTURE_HISTORY) {
                com.example.tail.ui.common.QuickCaptureHistoryScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }

        // Debug bubble overlay — shown on top of all screens when enabled
        DebugBubbleOverlay(
            currentRoute = currentRoute,
            debugPrefs = debugPrefs,
            debugNoteRepo = debugNoteRepo
        )

        // TailCue experiment reminder flash — above everything, incl. the bubble
        ExperimentFlashOverlay(
            status = experimentFlash,
            onDismiss = { experimentFlash = null }
        )
    }
}
