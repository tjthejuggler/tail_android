package com.example.tail.ui

import android.content.Context
import com.example.tail.data.LauncherIconTierManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * In-process event bus for habit increment notifications.
 *
 * When [SmartVoiceService], [HabitIncrementReceiver], or [ShareTextActivity]
 * increment a habit outside the ViewModel, they emit the habit name here.
 * [HabitViewModel] collects the flow and reloads the DB so the UI updates
 * instantly — no Android broadcast permission headaches.
 *
 * Every emit ALSO schedules a debounced launcher-icon tier refresh (see
 * [LauncherIconTierManager]), so the home-screen icon's background colour
 * follows the daily points tier no matter which path incremented the habit
 * — widget tap, IPC broadcast, voice, bubble timer, notification ask — even
 * when no ViewModel is alive. Requires [install] to have been called with
 * the application context (done in TailApplication.onCreate).
 */
object HabitIncrementBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    @Volatile
    private var appContext: Context? = null
    private val iconScope = CoroutineScope(Dispatchers.IO)
    @Volatile
    private var iconRefreshJob: Job? = null

    /** Attach the application context once at process start. */
    fun install(context: Context) {
        appContext = context.applicationContext
    }

    /** Call after an external increment (voice, IPC, share) to notify the UI layer. */
    fun emit(habitName: String) {
        _events.tryEmit(habitName)
        // Debounced icon refresh: bursts of increments coalesce into one
        // DB read + at most one PackageManager switch.
        val ctx = appContext ?: return
        iconRefreshJob?.cancel()
        iconRefreshJob = iconScope.launch {
            delay(1500)
            LauncherIconTierManager.refreshFromDatabase(ctx)
            // Same debounced pass also refreshes the full-width tier bar
            // widget (background colour + point total) if one is placed.
            com.example.tail.widget.TierBarWidgetProvider.refreshAll(ctx)
        }
    }
}
