package com.example.tail.crash

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.tail.BuildConfig
import com.example.tail.MainActivity
import com.example.tail.R
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Crash-to-Pack bridge: when Tail crashes, package everything known about the
 * error and turn it into a job on the Pack server (packd, which runs jobs via
 * Cline CLI against the tail repo).
 *
 * Two-phase delivery:
 *  1. At crash time — the [uncaught exception handler][install] writes the
 *     report to disk synchronously (crash-safe), then makes one best-effort
 *     direct HTTP attempt (short timeouts; failure is fine — see phase 2).
 *  2. At next launch — [submitPendingAsync] runs at the very top of
 *     `TailApplication.onCreate`, before any UI or heavy init, and flushes
 *     whatever crash reports are still sitting in the pending directory.
 *     This guarantees the report reaches Pack before the app has any chance
 *     of crashing again.
 *
 * When a report is delivered, the user is told so via a system notification
 * ("Crash report sent to Pack — job <id>").
 *
 * All config lives in a small private SharedPreferences file (`pack_crash`)
 * so the feature is fully independent of the main AppSettings/DataStore chain.
 * It can be toggled on/off from Settings → Integrations → Crash Reporter.
 */
object PackCrashReporter {

    private const val TAG = "PackCrashReporter"
    private const val PREFS = "pack_crash"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SERVER = "server"
    private const val KEY_TOKEN = "token"
    private const val KEY_PROJECT = "project"

    /** packd endpoint — same server the Pack app talks to by default. */
    private const val DEFAULT_SERVER = "http://100.64.91.104:8610"
    /** packd project name — must match a name in packd's GET /api/projects list. */
    private const val DEFAULT_PROJECT = "Tail"

    private const val CHANNEL_ID = "pack_crash_reports"
    private const val NOTIFY_ID = 4711

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    // ── Settings (plain prefs — readable from a crashing process) ──────────────

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun getServer(context: Context): String =
        prefs(context).getString(KEY_SERVER, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.PACK_SERVER.ifBlank { DEFAULT_SERVER }

    fun setServer(context: Context, value: String) {
        prefs(context).edit().putString(KEY_SERVER, value.trim().trimEnd('/')).apply()
    }

    fun getToken(context: Context): String =
        prefs(context).getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.PACK_TOKEN

    fun setToken(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TOKEN, value.trim()).apply()
    }

    fun getProject(context: Context): String =
        prefs(context).getString(KEY_PROJECT, null)?.takeIf { it.isNotBlank() } ?: DEFAULT_PROJECT

    fun setProject(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PROJECT, value.trim()).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun pendingDir(context: Context): File =
        File(context.filesDir, "pack_crash/pending").apply { mkdirs() }

    /** Number of crash reports waiting to be delivered (shown in Settings). */
    fun pendingCount(context: Context): Int =
        pendingDir(context).listFiles { f -> f.extension == "json" }?.size ?: 0

    // ── Installation ───────────────────────────────────────────────────────────

    /**
     * Installs the default uncaught-exception handler. Must be called from
     * `Application.onCreate` (process start) so every thread in every
     * component is covered. Chains to the previous handler after persisting
     * the report, so the normal system crash flow is preserved.
     */
    fun install(app: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                if (isEnabled(app)) {
                    val file = persistReport(app, thread.name, throwable)
                    // Best-effort direct attempt — if it fails (offline, server
                    // down), the pending file is picked up on the next launch.
                    if (file != null) {
                        val sender = Thread { try { submitOne(app, file) } catch (_: Throwable) {} }
                        sender.isDaemon = true
                        sender.start()
                        sender.join(5000)
                    }
                }
            } catch (_: Throwable) {
                // Never let reporting break the crash path itself.
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /**
     * Flushes pending crash reports. Call FIRST THING on the next app launch,
     * before any other init — the report must reach Pack before the app has
     * a chance to crash again. Fire-and-forget on the reporter's IO executor.
     */
    fun submitPendingAsync(context: Context) {
        val app = context.applicationContext
        executor.execute {
            try {
                if (!isEnabled(app)) return@execute
                val files = pendingDir(app).listFiles { f -> f.extension == "json" } ?: return@execute
                for (f in files.sortedBy { it.name }) {
                    try { submitOne(app, f) } catch (t: Throwable) {
                        Log.w(TAG, "pending report ${f.name} not delivered yet: ${t.message}")
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "pending flush failed: ${t.message}")
            }
        }
    }

    // ── Report capture ─────────────────────────────────────────────────────────

    /** Writes the crash report to disk synchronously. Returns the file, or null. */
    private fun persistReport(context: Context, threadName: String, t: Throwable): File? {
        val ts = System.currentTimeMillis()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date(ts))
        val report = JSONObject().apply {
            put("time", stamp)
            put("epochMs", ts)
            put("thread", threadName)
            put("exceptionClass", t.javaClass.name)
            put("message", t.message ?: "")
            put("stackTrace", buildString {
                append(t.toString()).append('\n')
                t.stackTrace.forEach { append("    at ").append(it.toString()).append('\n') }
                var cause = t.cause
                var depth = 0
                while (cause != null && depth < 5) {
                    append("Caused by: ").append(cause.toString()).append('\n')
                    cause.stackTrace.take(15).forEach { append("    at ").append(it.toString()).append('\n') }
                    cause = cause.cause
                    depth++
                }
            })
            put("appVersion", BuildConfig.VERSION_NAME)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkInt", Build.VERSION.SDK_INT)
            put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
        }
        return try {
            val f = File(pendingDir(context), "crash_$stamp.json")
            f.writeText(report.toString())
            f
        } catch (e: Exception) {
            Log.e(TAG, "failed to persist crash report", e)
            null
        }
    }

    // ── Delivery ───────────────────────────────────────────────────────────────

    /**
     * Submits one pending report file as a Pack job. On success the file is
     * deleted and the user is notified. Throws on failure so callers can
     * distinguish (crash-time caller swallows; pending flush retries later).
     */
    private fun submitOne(context: Context, file: File) {
        val report = JSONObject(file.readText())
        val jobId = httpCreateJob(
            server = getServer(context),
            token = getToken(context),
            project = getProject(context),
            instruction = buildInstruction(report),
        )
        // Delivered — remove the pending file and tell the user.
        file.delete()
        notifySubmitted(context, report.optString("exceptionClass", "crash"), jobId)
        Log.i(TAG, "crash report delivered to Pack as job $jobId")
    }

    /** POST {server}/api/jobs with {project, instruction} — the packd protocol. */
    private fun httpCreateJob(server: String, token: String, project: String, instruction: String): String {
        val body = JSONObject().put("project", project).put("instruction", instruction)
        val conn = URL("$server/api/jobs").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 4000
        conn.readTimeout = 8000
        conn.doOutput = true
        conn.setRequestProperty("X-Auth-Token", token)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val text = (if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText() ?: ""
        if (conn.responseCode !in 200..299)
            throw IllegalStateException("packd HTTP ${conn.responseCode}: ${text.take(200)}")
        return JSONObject(if (text.isEmpty()) "{}" else text).getString("id")
    }

    /** Builds the natural-language job instruction packd's Cline agent will act on. */
    private fun buildInstruction(r: JSONObject): String = buildString {
        appendLine("Tail crashed on the phone — investigate and fix this crash in the tail Android app repo.")
        appendLine()
        appendLine("## Crash details")
        appendLine("- Time: ${r.optString("time")}")
        appendLine("- Thread: ${r.optString("thread")}")
        appendLine("- Exception: ${r.optString("exceptionClass")}: ${r.optString("message")}")
        appendLine("- App version: ${r.optString("appVersion")} (Android ${r.optString("androidVersion")}, SDK ${r.optInt("sdkInt")}, ${r.optString("device")})")
        appendLine()
        appendLine("## Stack trace")
        appendLine("```")
        appendLine(r.optString("stackTrace"))
        appendLine("```")
        appendLine()
        appendLine("Find the root cause in the code, fix it, and make sure the app builds (`./gradlew assembleDebug`).")
        appendLine("This job was filed automatically by Tail's crash reporter — there is no user context beyond the stack trace above.")
    }

    // ── User notification ──────────────────────────────────────────────────────

    private fun notifySubmitted(context: Context, exceptionClass: String, jobId: String) {
        try {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Crash reports",
                        NotificationManager.IMPORTANCE_DEFAULT
                    ).apply { description = "Confirms a Tail crash report was sent to Pack as a fix job" }
                )
            }
            val pi = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_tail)
                .setContentTitle("Crash report sent to Pack")
                .setContentText("$exceptionClass — created fix job $jobId")
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(NOTIFY_ID, n)
        } catch (t: Throwable) {
            Log.w(TAG, "notify failed: ${t.message}")
        }
    }

    /** Sends a synthetic report so the user can verify the pipeline from Settings. */
    fun sendTestReport(context: Context, onDone: (Result<String>) -> Unit) {
        val app = context.applicationContext
        executor.execute {
            val result = try {
                val report = JSONObject()
                    .put("time", SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()))
                    .put("thread", "test")
                    .put("exceptionClass", "TestReport")
                    .put("message", "Manual test of the Tail → Pack crash pipeline")
                    .put("stackTrace", "(no crash — this is a connectivity test)")
                    .put("appVersion", BuildConfig.VERSION_NAME)
                    .put("androidVersion", Build.VERSION.RELEASE)
                    .put("sdkInt", Build.VERSION.SDK_INT)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
                Result.success(httpCreateJob(getServer(app), getToken(app), getProject(app), buildInstruction(report)))
            } catch (e: Exception) {
                Result.failure(e)
            }
            android.os.Handler(app.mainLooper).post { onDone(result) }
        }
    }
}
