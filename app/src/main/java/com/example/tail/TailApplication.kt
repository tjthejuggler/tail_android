package com.example.tail

import android.app.Application
import android.util.Log
import com.example.tail.data.meal.QcDiag

/**
 * Application entry point.
 *
 * Single responsibility: attach the [QcDiag] file-backed diagnostics logger
 * at the earliest possible moment (process start, before any activity,
 * worker, or receiver runs) so every quick-capture / vision-pipeline event
 * is persisted to `files/qc_diag/qc_diag.log` during NORMAL phone usage —
 * no adb attached, no special steps. The file survives reboots and is
 * retrieved later with:
 *
 * ```
 * adb exec-out run-as com.example.tail cat files/qc_diag/qc_diag.log
 * ```
 */
class TailApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        try {
            QcDiag.attach(this)
        } catch (t: Throwable) {
            Log.e("QC_DIAG", "│ INIT │ QcDiag attach failed — file logging disabled", t)
        }
        // Attach the app context to the increment bus so every habit
        // increment — even with no UI alive — refreshes the launcher
        // icon's daily-points tier colour.
        com.example.tail.data.HabitIncrementBus.install(this)

        // Install the UI-side hooks the data layer invokes via AppHooks —
        // widget refresh and points-driven wallpaper updates — so the data
        // module stays free of widget/wallpaper compile-time dependencies.
        com.example.tail.data.AppHooks.refreshWidgets = { ctx ->
            try {
                com.example.tail.widget.HabitListWidgetProvider.refreshAll(ctx)
                com.example.tail.widget.TierBarWidgetProvider.refreshAll(ctx)
            } catch (_: Exception) {
            }
        }
        com.example.tail.data.AppHooks.refreshWallpaperAfterSave = { ctx, db ->
            try {
                com.example.tail.wallpaper.WallpaperRefresher.onDatabaseSaved(ctx, db)
            } catch (e: Exception) {
                Log.w("TailApp", "post-save wallpaper refresh failed: ${e.message}")
            }
        }

        // Keep the home-screen widgets in sync with the pending-notification
        // list: both widgets repaint on every change — asks created (count
        // up) or answered/read anywhere (count down) — so the tier-bar badge
        // never goes stale behind the app's back.
        com.example.tail.widget.WidgetNotificationSync.start(this)
    }
}
