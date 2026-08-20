package com.example.tail.wallpaper

import android.app.WallpaperManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.tail.data.AppSettings
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TaskerStats
import com.example.tail.data.computeTaskerStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Which point statistic drives the wallpaper choice.
 *
 * All three values come from [computeTaskerStats] — the single source of
 * truth for today / avg7 / avg30 point totals (same numbers as the stats
 * overlay and the old Tasker relay).
 */
enum class WallpaperMetric(val label: String) {
    TODAY("Today's points"),
    WEEKLY("7-day average"),
    MONTHLY("30-day average");

    companion object {
        /** Decodes a persisted enum name, falling back to TODAY. */
        fun fromName(raw: String?): WallpaperMetric =
            entries.firstOrNull { it.name == raw } ?: TODAY
    }

    /** Extracts this metric's value from the computed stats. */
    fun select(stats: TaskerStats): Double = when (this) {
        TODAY -> stats.today.toDouble()
        WEEKLY -> stats.avg7
        MONTHLY -> stats.avg30
    }
}

/** Which wallpaper surface(s) the image is applied to. */
enum class WallpaperTarget(val label: String) {
    SYSTEM("Home screen"),
    LOCK("Lock screen"),
    BOTH("Both");

    companion object {
        /** Decodes a persisted enum name, falling back to SYSTEM. */
        fun fromName(raw: String?): WallpaperTarget =
            entries.firstOrNull { it.name == raw } ?: SYSTEM
    }
}

// ── Pure resolution logic (unit-tested) ─────────────────────────────────────

private val INDEXED_IMAGE_REGEX =
    Regex("^(.+)_(\\d+)\\.(png|jpe?g|webp|bmp|gif)$", RegexOption.IGNORE_CASE)

/**
 * Parses an indexed image file name such as `result_51.png` into its
 * `("result", 51)` prefix/index pair. Returns null for names that don't
 * follow the `<prefix>_<number>.<image-extension>` convention.
 */
fun parseIndexedImageName(displayName: String): Pair<String, Int>? {
    val m = INDEXED_IMAGE_REGEX.find(displayName) ?: return null
    val index = m.groupValues[2].toIntOrNull() ?: return null
    return m.groupValues[1] to index
}

/**
 * Picks the image prefix with the most indexed files in the folder
 * (e.g. "result" for result_1.png … result_56.png). This keeps the feature
 * working unchanged if the images are later renamed to another prefix.
 */
fun dominantImagePrefix(fileNames: List<String>): String? =
    fileNames
        .mapNotNull { parseIndexedImageName(it) }
        .groupingBy { it.first }
        .eachCount()
        .maxByOrNull { it.value }
        ?.key

/**
 * Maps a point value to an image index: the value is rounded to the nearest
 * integer and clamped to [1, maxIndex]. Returns -1 when no images exist.
 */
fun resolveImageIndex(value: Double, maxIndex: Int): Int {
    if (maxIndex < 1) return -1
    return value.roundToInt().coerceIn(1, maxIndex)
}

// ── Refresher ───────────────────────────────────────────────────────────────

/**
 * Applies the points-driven wallpaper: computes the selected point metric,
 * picks the matching `<prefix>_<N>.<ext>` image from the configured folder
 * and sets it via [WallpaperManager].
 *
 * Refresh triggers:
 *  1. After every successful habits-DB save ([onDatabaseSaved]) — so the
 *     wallpaper tracks the day's points as they accrue. Cheap no-op when
 *     disabled or when the resolved image hasn't changed.
 *  2. A daily alarm shortly after midnight (see [WallpaperAlarmReceiver]).
 *  3. The "Apply now" button in Settings (force = true).
 */
object WallpaperRefresher {

    private const val TAG = "WallpaperRefresher"
    private const val PREFS = "wallpaper_state"
    private const val KEY_LAST_INDEX = "last_index"
    private const val KEY_LAST_DAY = "last_day"
    private const val KEY_LAST_TARGET = "last_target"

    /**
     * Hook called after every successful habits-DB save. Never throws —
     * a wallpaper problem must never block a database write.
     */
    suspend fun onDatabaseSaved(context: Context, db: HabitsDatabase) {
        try {
            refresh(context, preloadedDb = db)
        } catch (e: Exception) {
            Log.w(TAG, "post-save wallpaper refresh failed: ${e.message}")
        }
    }

    /**
     * Full refresh pipeline. Returns a human-readable status string
     * (empty when there was nothing to do in non-forced mode).
     */
    suspend fun refresh(
        context: Context,
        preloadedDb: HabitsDatabase? = null,
        force: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext

        val settings = SettingsRepository(appContext).settingsFlow.first()
        if (!settings.wallpaperEnabled || settings.wallpaperDirUri.isEmpty()) {
            return@withContext if (force) "Wallpaper feature is disabled" else ""
        }

        // ── Compute the selected metric ──────────────────────────────────
        val db = preloadedDb ?: run {
            if (settings.fileUri.isEmpty()) return@withContext "No habit database selected"
            HabitsRepository().loadDatabase(Uri.parse(settings.fileUri), appContext)
        }
        val stats = computeTaskerStats(
            db = db,
            dividers = settings.habitDividers,
            noPointsHabits = settings.noPointsHabits,
            secondaryValueFallbackHabits = settings.secondaryValueFallbackHabits,
            timerMinutesPrimaryHabits = settings.widgetTimerMinutesPrimary,
            invertedBinaryHabits = settings.invertedBinaryHabits,
            secondaryValueHabits = settings.secondaryValueHabits
        )
        val metric = settings.wallpaperMetric
        val value = metric.select(stats)

        // ── Discover the indexed images in the folder ────────────────────
        val dir = DocumentFile.fromTreeUri(appContext, Uri.parse(settings.wallpaperDirUri))
        if (dir == null || !dir.isDirectory) {
            return@withContext "Wallpaper folder is not accessible"
        }
        val children = dir.listFiles()
        val prefix = dominantImagePrefix(children.mapNotNull { it.name })
            ?: return@withContext "No <prefix>_<number> images found in the folder"
        val images: Map<Int, DocumentFile> = children.mapNotNull { f ->
            val name = f.name ?: return@mapNotNull null
            val parsed = parseIndexedImageName(name)
            if (parsed?.first == prefix) parsed.second to f else null
        }.toMap()
        if (images.isEmpty()) {
            return@withContext "No $prefix images found in the folder"
        }

        // ── Resolve the image index ──────────────────────────────────────
        val maxIndex = images.keys.max()
        val index = resolveImageIndex(value, maxIndex)
        val file = images[index]
            ?: return@withContext "No ${prefix}_$index image in the folder"

        // ── Skip if this exact image is already applied today ────────────
        val target = settings.wallpaperTarget
        val today = LocalDate.now().toString()
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!force &&
            prefs.getString(KEY_LAST_DAY, "") == today &&
            prefs.getInt(KEY_LAST_INDEX, -1) == index &&
            prefs.getString(KEY_LAST_TARGET, "") == target.name
        ) {
            return@withContext ""
        }

        // ── Decode and apply ─────────────────────────────────────────────
        val bitmap = appContext.contentResolver.openInputStream(file.uri)?.use {
            BitmapFactory.decodeStream(it)
        }
        if (bitmap == null) return@withContext "Could not decode ${file.name}"

        val wm = WallpaperManager.getInstance(appContext)
        val flags = when (target) {
            WallpaperTarget.SYSTEM -> listOf(WallpaperManager.FLAG_SYSTEM)
            WallpaperTarget.LOCK -> listOf(WallpaperManager.FLAG_LOCK)
            WallpaperTarget.BOTH -> listOf(
                WallpaperManager.FLAG_SYSTEM,
                WallpaperManager.FLAG_LOCK
            )
        }
        val errors = mutableListOf<String>()
        var applied = 0
        for (flag in flags) {
            try {
                wm.setBitmap(bitmap, null, true, flag)
                applied++
            } catch (e: Exception) {
                errors += (e.message ?: "unknown error").take(80)
            }
        }

        if (applied == 0) {
            return@withContext "Failed to set wallpaper: ${errors.joinToString("; ")}"
        }

        prefs.edit()
            .putInt(KEY_LAST_INDEX, index)
            .putString(KEY_LAST_DAY, today)
            .putString(KEY_LAST_TARGET, target.name)
            .apply()

        val valueText = if (value == value.roundToInt().toDouble()) {
            value.roundToInt().toString()
        } else {
            String.format(java.util.Locale.US, "%.1f", value)
        }
        val status = "✓ ${file.name}  (${metric.label.lowercase()}: $valueText)"
        val partial = if (errors.isEmpty()) "" else "  ·  partial: ${errors.joinToString("; ")}"
        return@withContext status + partial
    }

    /** Convenience wrapper used by the settings UI preview. */
    fun currentMetricLabel(settings: AppSettings): String = settings.wallpaperMetric.label
}
