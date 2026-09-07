package com.example.tail.data.meal

import android.content.Context
import android.util.Log
import com.example.tail.data.AppSettings
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Dedicated diagnostics logger for the camera-capture → vision pipeline.
 *
 * Quick capture has a history of hard-to-reproduce failures that previous
 * fix attempts kept missing because the interesting decisions were either
 * completely silent (`runCatching { … }.getOrNull()` swallowing resolution
 * errors) or spread across a dozen log tags. Every capture-related decision
 * point now logs here with a uniform, greppable format:
 *
 * ```
 * adb logcat -s QC_DIAG
 * ```
 *
 * Log lines look like `│ STAGE │ key=value …` where STAGE is one of:
 *  - `CAPTURE`   camera activity launch / shutter tap (both activities)
 *  - `RESOLVE`   deterministic target-habit resolution (the single-camera-
 *                habit shortcut) incl. the full settings snapshot
 *  - `SAVE`      photo bytes → `meal_images/`
 *  - `GROUP`     active-meal-group lookup (merge vs new meal)
 *  - `ENQUEUE`   vision queue insert
 *  - `WORKER`    [VisionProcessingWorker] pass lifecycle
 *  - `ITEM`      per-item state dump at claim time
 *  - `ROUTE`     forced-meal vs classification pipeline decision + why
 *  - `LLM`       the actual vision call (mode, duration, outcome)
 *  - `MEAL`      meal log create / merge / attach
 *  - `INCREMENT` habit count + timestamp side effects
 *  - `REVIEW`    NEEDS_REVIEW transitions (why a capture wasn't acted on)
 *  - `QUEUE`     queue state transitions
 *
 * The MANUAL path (meal screen / habit grid → [com.example.tail.QuickCaptureActivity]
 * with an explicit `EXTRA_HABIT_NAME`) and the QUICK path (launcher shortcut →
 * [com.example.tail.MediaCaptureActivity] with no extra) emit the exact same
 * stages, so a failing quick capture can be diffed line-by-line against a
 * working manual capture taken moments later.
 *
 * This object is diagnostics-only: it must never alter control flow.
 */
object QcDiag {

    const val TAG = "QC_DIAG"

    // ── File persistence (adb-free diagnostics) ─────────────────────────
    // logcat is a ring buffer that is overwritten within hours and only
    // visible with adb attached. The whole point of this logger is that the
    // user captures meals NORMALLY — phone in hand, no computer — and the
    // developer later pulls the persisted trail with:
    //
    //   adb exec-out run-as com.example.tail cat files/qc_diag/qc_diag.log
    //
    // Every line is mirrored to files/qc_diag/qc_diag.log on a background
    // daemon thread. Rotation keeps the file bounded (~1 MB current +
    // ~1 MB .old). File failures are swallowed — diagnostics must never
    // affect the app.
    private const val DIR_NAME = "qc_diag"
    private const val FILE_NAME = "qc_diag.log"
    private const val MAX_FILE_BYTES = 1_000_000L

    @Volatile
    private var appContext: Context? = null

    private val fileTimestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    private val fileExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "qc-diag-writer").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }

    /**
     * Attaches the application context so every subsequent line is ALSO
     * persisted to the on-device log file. Called once from
     * [com.example.tail.TailApplication.onCreate] — i.e. at process start,
     * before any activity / worker / receiver runs, so nothing is missed.
     */
    fun attach(context: Context) {
        appContext = context.applicationContext
        log(
            "INIT",
            "QcDiag attached — quick-capture diagnostics now persist to " +
                "files/$DIR_NAME/$FILE_NAME from NORMAL usage (no adb needed). " +
                "Retrieve later with: adb exec-out run-as com.example.tail " +
                "cat files/$DIR_NAME/$FILE_NAME"
        )
    }

    private fun persist(level: String, stage: String, message: String) {
        val ctx = appContext ?: return
        val line = synchronized(fileTimestamp) {
            "${fileTimestamp.format(Date())} $level │ $stage │ $message\n"
        }
        fileExecutor.execute {
            try {
                val dir = File(ctx.filesDir, DIR_NAME).apply { mkdirs() }
                val file = File(dir, FILE_NAME)
                if (file.length() > MAX_FILE_BYTES) {
                    val old = File(dir, "$FILE_NAME.old")
                    if (old.exists()) old.delete()
                    file.renameTo(old)
                }
                FileOutputStream(file, true).use { out ->
                    out.write(line.toByteArray(Charsets.UTF_8))
                }
            } catch (_: Exception) {
                // Diagnostics must never crash or block the app.
            }
        }
    }

    fun log(stage: String, message: String) {
        Log.i(TAG, "│ $stage │ $message")
        persist("I", stage, message)
    }

    fun warn(stage: String, message: String) {
        Log.w(TAG, "│ $stage │ $message")
        persist("W", stage, message)
    }

    fun error(stage: String, message: String, t: Throwable? = null) {
        Log.e(TAG, "│ $stage │ $message", t)
        persist("E", stage, message + (t?.let { " (${it.javaClass.simpleName})" } ?: ""))
    }

    /** First 8 chars of an id — enough to correlate, short enough to read. */
    fun short(id: String?): String =
        id?.take(8)?.takeIf { it.isNotBlank() } ?: "none"

    /**
     * Full snapshot of every input that feeds the deterministic-routing
     * decision (single camera-enabled habit ⇒ forced meal pipeline, no LLM
     * habit classification). This is the single most important line when a
     * quick capture misbehaves: it shows exactly which habit names the
     * router saw, verbatim — including case and whitespace.
     */
    fun routingSnapshot(settings: AppSettings?): String {
        if (settings == null) return "settings=NULL"
        val eligible = VisionHabitExecutor.cameraEligibleHabits(settings)
        return buildString {
            append("cameraHabits=${settings.cameraHabits.toList()}")
            append(" mealHabits=${settings.mealHabits.toList()}")
            append(" eligible=$eligible")
            append(" eligibleCount=${eligible.size}")
            append(" singleCameraHabit=${eligible.singleOrNull() ?: "NULL"}")
            append(" mealEnabled=${settings.mealEnabled}")
            append(" baseUrl=${if (settings.mealBaseUrl.isBlank()) "BLANK" else "set"}")
            append(" apiKey=${if (settings.mealApiKey.isBlank()) "BLANK" else "set"}")
            append(" model=${if (settings.mealModel.isBlank()) "BLANK" else settings.mealModel}")
            append(" fileUri=${if (settings.fileUri.isBlank()) "BLANK" else "set"}")
        }
    }

    /**
     * Heuristics for the known ways the deterministic route silently breaks.
     * All name comparisons in the pipeline are case-sensitive exact matches
     * (`it in flagged`, `mealHabits.contains`), so a single renamed or
     * re-cased habit quietly defeats the single-camera-habit shortcut while
     * the manual path (explicit habit extra) keeps working — exactly the
     * asymmetry reported with quick capture.
     */
    fun mismatchHints(settings: AppSettings?): String {
        if (settings == null) return "hints: settings=NULL"
        val allHabits = (settings.habitScreens.flatMap { it.habitNames } + settings.habitOrder)
            .filter { it.isNotBlank() && !it.startsWith("app_link:") }
            .distinct()
        val eligible = VisionHabitExecutor.cameraEligibleHabits(settings)
        val problems = mutableListOf<String>()

        // 1. Flagged camera habits that don't exist verbatim in the habit config
        settings.cameraHabits
            .filter { flagged -> allHabits.none { it == flagged } }
            .forEach { flagged ->
                val nearMiss = allHabits.firstOrNull { it.equals(flagged, ignoreCase = true) }
                problems.add(
                    if (nearMiss != null) {
                        "cameraHabit \"$flagged\" exists in NO habit config but case-insensitively " +
                            "matches \"$nearMiss\" — exact-match routing DROPS it"
                    } else {
                        "cameraHabit \"$flagged\" matches no habit at all (renamed or deleted?)"
                    }
                )
            }

        // 2. Flags present but the eligible list still ends up empty
        if (settings.cameraHabits.isNotEmpty() && eligible.isEmpty()) {
            problems.add("cameraHabits non-empty yet eligible list is EMPTY → singleOrNull()=NULL")
        }

        // 3. Ambiguity that defeats singleOrNull()
        if (eligible.size > 1) {
            problems.add(
                "eligible.size=${eligible.size} > 1 → singleOrNull()=NULL " +
                    "(quick capture cannot auto-target: $eligible)"
            )
        }

        // 4. Meal habits that would not be force-routed even when resolved
        settings.mealHabits
            .filter { it !in eligible }
            .forEach { meal ->
                val nearMiss = eligible.firstOrNull { it.equals(meal, ignoreCase = true) }
                problems.add(
                    if (nearMiss != null) {
                        "mealHabit \"$meal\" not camera-eligible (case-mismatch vs eligible " +
                            "\"$nearMiss\") → forcedMealHabit will be NULL for it"
                    } else {
                        "mealHabit \"$meal\" not in eligible=$eligible → forcedMealHabit will be NULL for it"
                    }
                )
            }

        return if (problems.isEmpty()) {
            "hints: none (routing inputs look consistent)"
        } else {
            "hints: " + problems.joinToString(" | ")
        }
    }
}
