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
}
