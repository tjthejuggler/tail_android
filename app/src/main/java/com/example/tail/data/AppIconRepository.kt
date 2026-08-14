package com.example.tail.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** Prefix used in habitIcons values that reference an installed app's icon (e.g. "app:com.spotify.music"). */
private const val APP_ICON_PREFIX = "app:"

/**
 * Returns true when the icon name references an installed app's icon
 * (i.e. it was stored by the "App Icons" section of the icon picker).
 */
fun isAppIconName(iconName: String?): Boolean =
    iconName != null && iconName.startsWith(APP_ICON_PREFIX)

/**
 * Extracts the package name from a habit icon name, or null if the name does
 * not reference an installed app.
 */
fun appPackageNameOf(iconName: String?): String? =
    iconName?.takeIf { it.startsWith(APP_ICON_PREFIX) }?.removePrefix(APP_ICON_PREFIX)

/** Builds the habitIcons value that references the given app's icon. */
fun appIconNameOf(packageName: String): String = APP_ICON_PREFIX + packageName

/**
 * Metadata for one launchable installed app (used by the app-icon picker).
 */
data class AppIconInfo(
    val packageName: String,
    val label: String
)

/**
 * Provides access to the icons of installed apps, backed by [PackageManager].
 *
 * The launchable-app list is queried once and cached in memory because the
 * PackageManager query is relatively expensive (~100-300 ms on some devices).
 */
class AppIconRepository(private val context: Context) {

    @Volatile
    private var cachedApps: List<AppIconInfo>? = null

    /**
     * Returns all launchable installed apps sorted by label (case-insensitive).
     * The result is cached after the first call.
     */
    fun listLaunchableApps(): List<AppIconInfo> {
        cachedApps?.let { return it }
        return try {
            val pm = context.packageManager
            val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { AppIconInfo(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
            cachedApps = apps
            apps
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Loads the app's icon as a [Bitmap], or null if the app is not installed
     * (e.g. it was uninstalled after the icon was assigned).
     */
    fun loadIconBitmap(packageName: String): Bitmap? {
        return try {
            drawableToBitmap(context.packageManager.getApplicationIcon(packageName))
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable): Bitmap {
        if (drawable is BitmapDrawable) return drawable.bitmap
        val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
        val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }
}
