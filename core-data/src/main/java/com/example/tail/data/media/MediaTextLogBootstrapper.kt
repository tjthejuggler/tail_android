package com.example.tail.data

import android.content.Context
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

private const val TAG = "MediaTextLogBoot"

/**
 * Auto-provisions the per-habit text-entry log file that
 * [com.example.tail.widget.MediaPlaybackTracker] writes its play-by-play
 * entries ("HH:mm Title — Artist (NN min)") into.
 *
 * WHY THIS EXISTS
 * ───────────────
 * The tracker only logs songs/episodes when the habit has a text-input file
 * configured; without one the entries were silently discarded and nothing
 * showed up in the history/graph screens (they read the same log). Rather
 * than forcing the user through the manual SAF file picker, enabling the
 * "Media" type now creates an INTERNAL log automatically.
 *
 * The file lives at `filesDir/text_input_logs/<safeName>.json` and is
 * exposed through FileProvider (authority `<pkg>.fileprovider`, path
 * `text_input_logs/` — see res/xml/file_paths.xml). TextInputRepository
 * reads/writes it through ContentResolver exactly like a SAF document.
 */
object MediaTextLogBootstrapper {

    /** Directory holding the auto-provisioned logs. */
    private const val DIR_NAME = "text_input_logs"

    /**
     * Creates (if needed) the internal text log for [habitName] and returns
     * its content-URI string, or null on failure. Safe to call repeatedly —
     * an existing file is reused untouched.
     */
    fun provision(context: Context, habitName: String): String? {
        return try {
            val dir = File(context.filesDir, DIR_NAME)
            if (!dir.exists()) dir.mkdirs()
            val safe = habitName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
            val file = File(dir, "$safe.json")
            if (!file.exists()) file.writeText("{}")
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            Log.i(TAG, "Media text log for '$habitName' → ${file.name}")
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to provision media text log for '$habitName': ${e.message}")
            null
        }
    }
}
