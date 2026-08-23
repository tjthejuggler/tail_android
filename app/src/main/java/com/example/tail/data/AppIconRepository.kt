package com.example.tail.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/** Prefix used in habitIcons values that reference an installed app's icon (e.g. "app:com.spotify.music"). */
private const val APP_ICON_PREFIX = "app:"

/**
 * Suffix marking the black/white notification-style variant of an installed
 * app's icon (e.g. "app:com.spotify.music#mono"). Package names can never
 * contain '#', so the split is unambiguous.
 */
private const val APP_ICON_MONO_SUFFIX = "#mono"

/**
 * Returns true when the icon name references an installed app's icon
 * (i.e. it was stored by the "App Icons" section of the icon picker).
 */
fun isAppIconName(iconName: String?): Boolean =
    iconName != null && iconName.startsWith(APP_ICON_PREFIX)

/**
 * Extracts the package name from a habit icon name, or null if the name does
 * not reference an installed app. The monochrome variant suffix is stripped,
 * so both "app:x" and "app:x#mono" resolve to package "x" (keeping app
 * auto-association and icon loading variant-agnostic).
 */
fun appPackageNameOf(iconName: String?): String? =
    iconName?.takeIf { it.startsWith(APP_ICON_PREFIX) }
        ?.removePrefix(APP_ICON_PREFIX)
        ?.removeSuffix(APP_ICON_MONO_SUFFIX)
        ?.takeIf { it.isNotEmpty() }

/**
 * Returns true when the icon name selects the app's black/white
 * notification-style icon rather than its full-colour launcher icon.
 */
fun appIconMonochromeOf(iconName: String?): Boolean =
    iconName != null &&
        iconName.startsWith(APP_ICON_PREFIX) &&
        iconName.endsWith(APP_ICON_MONO_SUFFIX)

/**
 * Builds the habitIcons value that references the given app's icon.
 * Pass [monochrome] = true to store the black/white notification-style icon.
 */
fun appIconNameOf(packageName: String, monochrome: Boolean = false): String =
    APP_ICON_PREFIX + packageName + if (monochrome) APP_ICON_MONO_SUFFIX else ""

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
     * (e.g. it was uninstalled after the icon was assigned). Pass
     * [monochrome] = true for the black/white notification-style icon.
     */
    fun loadIconBitmap(packageName: String, monochrome: Boolean = false): Bitmap? =
        loadAppIconBitmap(context, packageName, monochrome)
}

/**
 * Loads an installed app's icon as a [Bitmap], or null if the app is not
 * installed (e.g. it was uninstalled after the icon was assigned).
 *
 * When [monochrome] is true the black/white notification-style icon is
 * returned: the adaptive icon's monochrome layer when the app provides one
 * (API 33+; this is the layer launchers use for themed icons and the system
 * uses for notification badges), otherwise a greyscale rendering of the
 * launcher icon — the same desaturation text icons use (see
 * [renderTextIconBitmap]).
 */
fun loadAppIconBitmap(context: Context, packageName: String, monochrome: Boolean): Bitmap? {
    return try {
        val drawable = context.packageManager.getApplicationIcon(packageName)
        // Adaptive-icon monochrome layer, when the app provides one (API 33+).
        val monoLayer: Drawable? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && drawable is AdaptiveIconDrawable)
                drawable.monochrome
            else null
        when {
            !monochrome -> drawableToAppBitmap(drawable)
            monoLayer != null -> whiteMaskBitmap(drawableToAppBitmap(monoLayer))
            else -> greyscaleBitmap(drawableToAppBitmap(drawable))
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * Converts an Android [Drawable] to a [Bitmap].
 * If the drawable is already a [BitmapDrawable], its bitmap is returned directly.
 * Otherwise a new bitmap is created at the drawable's intrinsic size.
 */
private fun drawableToAppBitmap(drawable: Drawable): Bitmap {
    if (drawable is BitmapDrawable) return drawable.bitmap
    val width = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
    val height = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/** Returns a saturation-stripped (pure luminance) copy of [source]. */
private fun greyscaleBitmap(source: Bitmap): Bitmap {
    val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
    val paint = Paint().apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
    Canvas(result).drawBitmap(source, 0f, 0f, paint)
    return result
}

/**
 * Ensures a monochrome glyph is WHITE while preserving its anti-aliased
 * alpha: the adaptive-icon monochrome layer may be authored in any solid
 * colour, and a dark glyph would be invisible on the app's dark habit grid.
 */
private fun whiteMaskBitmap(source: Bitmap): Bitmap {
    var luminanceSum = 0L
    var visiblePixels = 0
    for (x in 0 until source.width) {
        for (y in 0 until source.height) {
            val pixel = source.getPixel(x, y)
            if (Color.alpha(pixel) > 32) {
                luminanceSum += (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
                visiblePixels++
            }
        }
    }
    // Already light (or fully transparent) — keep the glyph as authored.
    if (visiblePixels == 0 || luminanceSum / visiblePixels >= 128) return source
    val result = source.copy(Bitmap.Config.ARGB_8888, true)
    for (x in 0 until result.width) {
        for (y in 0 until result.height) {
            val pixel = result.getPixel(x, y)
            result.setPixel(x, y, Color.argb(Color.alpha(pixel), 255, 255, 255))
        }
    }
    return result
}
