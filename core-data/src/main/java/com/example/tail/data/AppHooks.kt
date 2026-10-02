package com.example.tail.data

import android.content.Context

/**
 * Indirection hooks installed by the app module (see TailApplication.onCreate)
 * so the data layer can trigger UI-side side effects — widget refreshes and
 * points-driven wallpaper updates — without a compile-time dependency on the
 * widget / wallpaper packages.
 *
 * Installed callbacks must be side-effect-safe and must never throw
 * (the data layer wraps invocations defensively anyway).
 */
object AppHooks {
    /** Refreshes all home-screen widgets (habit list + tier bar). */
    @Volatile
    var refreshWidgets: (suspend (Context) -> Unit)? = null

    /** Recomputes the points-driven wallpaper after a successful DB save. */
    @Volatile
    var refreshWallpaperAfterSave: (suspend (Context, HabitsDatabase) -> Unit)? = null

    /**
     * Requests a (debounced) off-device backup push after a successful DB
     * save. Installed in TailApplication.onCreate → BridgeBackupManager
     * (same module); kept as a hook so the repository save path stays
     * decoupled from the backup implementation.
     */
    @Volatile
    var refreshBackupAfterSave: (suspend (Context) -> Unit)? = null

    /**
     * Fires after a garmin_refresh PC event re-pulled fresh Garmin metrics
     * from the proxy. Installed by the app module so the data layer can
     * ask the ViewModel layer to apply the data to linked habits (the
     * applyGarminData write path lives in HabitViewModelGarmin, which the
     * core-data module must not depend on). Payload is the freshly-pulled
     * type → date → value map.
     */
    @Volatile
    var onGarminDataRefreshed: (suspend (Context, Map<com.example.tail.data.health.GarminType, Map<String, Int>>) -> Unit)? = null
}
