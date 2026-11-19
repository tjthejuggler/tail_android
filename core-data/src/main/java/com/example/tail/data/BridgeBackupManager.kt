package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Push-based off-device backup of the habits DB via the Tail Bridge
 * ════════════════════════════════════════════════════════════════════════
 *
 * Replaces the Syncthing folder-share for BACKUP purposes (Phase 1 runs both
 * in parallel; Syncthing removal is Phase 2). Why push beats sync here:
 *
 *   - Syncthing is bidirectional: it propagated corrupt/truncated writes to
 *     the desktop "backup" and could sync a stale desktop copy back over the
 *     phone. Push-only means the desktop store is append-dominant — a bad
 *     upload can never silently overwrite the last good backup (the bridge
 *     keeps immutable timestamped copies and dedups by content hash).
 *   - Every habits save currently dirties the whole file for Syncthing's
 *     watcher; the debounced push bounds that to at most one 3 MB POST per
 *     minute of activity.
 *
 * Debounce design: [onDatabaseSaved] is called after every confirmed DB write
 * (see [AppHooks.refreshBackupAfterSave], wired in TailApplication). A shared
 * coroutine coalesces bursts of increments into a single push at least
 * [DEBOUNCE_MS] apart, and a per-process "already pushed this exact content"
 * hash guard skips redundant uploads entirely.
 *
 * Reliability: a failed push is retried with backoff inside the debounce loop
 * while the app is alive. This is intentionally best-effort in Phase 1 — the
 * on-device [com.example.tail.data.backup.HabitsSnapshotManager] remains the
 * first line of defence; the bridge backup is the OFF-DEVICE second line.
 */
object BridgeBackupManager {

    private const val TAG = "BridgeBackup"
    private const val BACKUP_PATH = "backup/habits"

    /** Timestamp-store backup path (2026-10-05: timestamps get off-device backups too). */
    private const val TIMESTAMPS_BACKUP_PATH = "backup/timestamps"
    private const val TIMESTAMPS_FILE = "habit_timestamps.json"
    private const val MINUTES_FILE = "habit_timestamp_minutes.json"

    /** Minimum interval between actual pushes (coalesces burst increments). */
    private const val DEBOUNCE_MS = 60_000L

    /**
     * Hard floor between two actual network pushes. The habits DB is
     * multi-MB (5.3 MB live), so a save-every-few-minutes pattern (PC
     * widget events, Tasker-launched refreshes, timestamp appends) was
     * uploading ~7 MB per save even while the phone sat idle overnight —
     * 100–270 MB per 2-hour netstats bucket, Tail's #2 battery item
     * (2026-11-19 report). Real changes now land at most 30 min late;
     * unchanged content is never uploaded at all (persistent hash guard).
     * 30 min (battery report round 3 feedback: even the 10-min floor kept
     * the mobile radio ~53% active — this is an off-device SAFETY copy,
     * and the live file is separately Syncthing-synced in real time, so
     * 30-min backup staleness is acceptable).
     */
    private const val MIN_PUSH_INTERVAL_MS = 30L * 60 * 1000

    /** Backoff between retries of a failed push within the debounce loop. */
    private val RETRY_DELAYS_MS = longArrayOf(5_000, 15_000, 45_000)

    /** Prefs persisting the pushed-content hashes across process restarts. */
    private const val STATE_PREFS = "bridge_backup_state"
    private const val KEY_DB_HASH = "last_pushed_db_hash"
    private const val KEY_TS_HASH = "last_pushed_ts_hash"
    private const val KEY_LAST_PUSH_AT = "last_push_at"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushMutex = Mutex()

    /** Hash of the last successfully pushed payload — skips no-op uploads. */
    private var lastPushedHash: Int = 0

    /** Combined hash of the last successfully pushed timestamp files. */
    @Volatile
    private var lastPushedTsHash: Int = 0

    /** Pending request marker for the debounce loop. */
    @Volatile
    private var pending = false

    /**
     * Called by the app module after every confirmed habits DB write.
     * Cheap, non-blocking: just sets a flag and ensures the loop is running.
     */
    fun onDatabaseSaved(context: Context) {
        pending = true
        ensureLoopRunning(context.applicationContext)
    }

    /**
     * Armed by [com.example.tail.data.HabitTimestampRepository] after every
     * confirmed timestamp-store write. The debounce loop pushes the
     * timestamp files (see [pushTimestampFiles]) alongside the habits DB.
     */
    fun onTimestampsSaved(context: Context) {
        pending = true
        ensureLoopRunning(context.applicationContext)
    }

    private var loopStarted = false

    private fun ensureLoopRunning(appContext: Context) {
        synchronized(this) {
            if (loopStarted) return
            loopStarted = true
        }
        scope.launch { debounceLoop(appContext) }
    }

    /**
     * Long-lived debounce loop: whenever [pending] is set, wait out the
     * debounce window, then push (with retries). One loop per process.
     */
    private suspend fun debounceLoop(appContext: Context) {
        while (true) {
            if (!pending) {
                delay(5_000)
                continue
            }
            pending = false
            delay(DEBOUNCE_MS) // coalesce the burst
            enforcePushSpacing(appContext)
            // Retry with backoff; on final failure leave `pending` false but
            // remember nothing was pushed — the next save re-arms the loop.
            var pushed = false
            for (backoff in RETRY_DELAYS_MS) {
                pushed = pushNow(appContext)
                if (pushed) break
                delay(backoff)
            }
            if (!pushed) {
                Log.w(TAG, "backup push failed after retries — will retry on next save")
            }
        }
    }

    /**
     * Waits out any remainder of [MIN_PUSH_INTERVAL_MS] since the last
     * actual network push, so rapid save bursts cannot turn into a
     * rapid-fire series of multi-MB uploads. Called on the debounce
     * coroutine — the extra wait only delays a push, never drops one.
     */
    private suspend fun enforcePushSpacing(appContext: Context) {
        val prefs = appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val since = System.currentTimeMillis() - prefs.getLong(KEY_LAST_PUSH_AT, 0L)
        if (since in 1 until MIN_PUSH_INTERVAL_MS) {
            delay(MIN_PUSH_INTERVAL_MS - since)
        }
    }

    private fun lastPushAt(appContext: Context): Long =
        appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_PUSH_AT, 0L)

    private fun notePush(appContext: Context) {
        appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_LAST_PUSH_AT, System.currentTimeMillis())
            .apply()
    }

    /** Bridge port — mirrors PC_WIDGET_BRIDGE_PORT / BRIDGE_PORT (8001). */
    private const val BRIDGE_PORT = 8001

    /**
     * Candidate (bridgeUrl, token) pairs, most-trusted first:
     *
     * 1. The saved [AppSettings.bridgeUrl] / Garmin-derived URL — what the
     *    rest of the app uses. NOTE: in the live setup these hold a bare
     *    hostname ("twain") that Android's resolver sometimes cannot
     *    resolve (Tailscale MagicDNS off) — hence the probe below.
     * 2. The desktop's last-known-good address persisted after any
     *    successful push (works across Tailscale renumbering / DHCP).
     *
     * [pushNow] walks the candidates and REMEMBERS the first one whose
     * TCP connect succeeds, so the backup path self-heals instead of
     * failing forever on a stale DNS entry.
     */
    private fun candidates(s: AppSettings): List<String> {
        val list = ArrayList<String>(4)
        // Always-on remote server FIRST (remoteHostUrl) — backups must keep
        // flowing when the laptop is off. Falls through to the laptop
        // candidates below when the server is unreachable.
        remoteHostCandidates(s).forEach { if (it !in list) list.add(it) }
        s.bridgeUrl.trim().trimEnd('/').takeIf { it.isNotBlank() }?.let { list.add(it) }
        try {
            val clean = s.garminProxyUrl.trim().trimEnd('/')
            if (clean.isNotEmpty()) {
                val uri = java.net.URI(clean)
                val scheme = uri.scheme ?: "http"
                val host = uri.host
                if (host != null) list.add("$scheme://$host:$BRIDGE_PORT")
            }
        } catch (_: Exception) {
        }
        lastGoodUrl?.takeIf { it.isNotBlank() }?.let { if (it !in list) list.add(it) }
        // Hard-coded last-resort candidate: the desktop's LAN address. When
        // both saved URLs hold hostnames the phone cannot resolve (e.g.
        // Tailscale MagicDNS off), this keeps backups flowing on the LAN.
        DESKTOP_LAN_URL.takeIf { it !in list }?.let { list.add(it) }
        return list
    }

    /**
     * Last-resort candidate: desktop on the home LAN (bridge port). Only
     * used when every configured candidate fails its reachability probe.
     */
    private const val DESKTOP_LAN_URL = "http://192.168.100.38:$BRIDGE_PORT"

    /** Desktop base URL of the last successful push — self-heal memory. */
    @Volatile
    private var lastGoodUrl: String? = null

    /**
     * Bridge URL(s) derived from the always-on remote host setting, if set.
     * The remote bridge is the preferred backup destination.
     */
    private fun remoteHostCandidates(s: AppSettings): List<String> {
        val url = remoteBridgeConnectionFrom(s.remoteHostUrl, "x")?.first ?: return emptyList()
        return listOf(url)
    }

    /**
     * TCP-reachability probe (fast fail on unresolvable hosts). Avoids
     * burning the full HTTP timeouts on a dead candidate.
     */
    private fun isReachable(baseUrl: String): Boolean {
        return try {
            val uri = java.net.URI(baseUrl.trim().trimEnd('/'))
            val host = uri.host ?: return false
            val port = if (uri.port > 0) uri.port else 80
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress(host, port), 2_500)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Immediate, unconditional push attempt (also usable from tests/debug UI).
     * Returns true only when the bridge confirmed the backup.
     */
    suspend fun pushNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        pushMutex.withLock {
            try {
                val settings = SettingsRepository(context).settingsFlow.first()
                val token = settings.bridgeToken.ifBlank { settings.garminAppToken }
                if (token.isBlank()) return@withLock false
                val uriStr = settings.fileUri
                if (uriStr.isBlank()) return@withLock false

                // Walk candidates, prefer one that answers. Self-heals past
                // unresolvable hostnames and address changes.
                val bridgeUrl = candidates(settings).firstOrNull { candidate ->
                    isReachable(candidate).also { reached ->
                        if (!reached) Log.i(TAG, "candidate unreachable, trying next: $candidate")
                    }
                } ?: run {
                    Log.w(TAG, "no reachable bridge candidate (tried ${candidates(settings).size})")
                    return@withLock false
                }

                val cr = context.contentResolver
                val stream = try {
                    cr.openInputStream(Uri.parse(uriStr))
                } catch (e: Exception) {
                    Log.w(TAG, "openInputStream failed: ${e.message}")
                    return@withLock false
                } ?: return@withLock false

                val bytes = try {
                    stream.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.w(TAG, "read failed (Syncthing mid-write?): ${e.message}")
                    return@withLock false
                }
                // Skip pushes of content already on the bridge (common when a
                // save produced identical bytes, e.g. no-op day rollovers).
                // The guard is PERSISTED: an in-memory-only check reset on
                // every process restart and re-uploaded the whole multi-MB
                // DB for unchanged content (the 2026-11-19 TX storm).
                val hash = bytes.contentHashCode()
                val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                if (hash == lastPushedHash ||
                    hash == prefs.getInt(KEY_DB_HASH, 0)
                ) {
                    lastPushedHash = hash
                    return@withLock true
                }

                val ok = postBackup(bridgeUrl, token, bytes)
                if (ok) {
                    lastPushedHash = hash
                    prefs.edit().putInt(KEY_DB_HASH, hash).apply()
                    notePush(context)
                    lastGoodUrl = bridgeUrl
                    Log.i(TAG, "pushed ${bytes.size} bytes to $bridgeUrl")
                }
                // Timestamp stores ride the same debounced push (2026-10-05):
                // off-device coverage for habit_timestamps.json + minutes.
                // Best-effort — a timestamp push failure must not fail the
                // DB push result (and vice-versa below).
                val tsOk = pushTimestampFiles(bridgeUrl, token, context)
                ok || tsOk
            } catch (e: Throwable) {
                if (e is OutOfMemoryError || e is StackOverflowError) throw e
                Log.w(TAG, "pushNow failed: ${e.message}")
                false
            }
        }
    }

    /**
     * Pushes the internal timestamp stores to the bridge's
     * `/api/v1/backup/timestamps` endpoint as
     * `{"timestamps": …, "minutes": …}`. Content-hash-guarded so unchanged
     * stores are skipped. Never throws.
     */
    private fun pushTimestampFiles(bridgeUrl: String, token: String, context: Context): Boolean {
        return try {
            val tsBytes = File(context.filesDir, TIMESTAMPS_FILE).takeIf { it.exists() }?.readBytes()
            val minBytes = File(context.filesDir, MINUTES_FILE).takeIf { it.exists() }?.readBytes()
            if (tsBytes == null && minBytes == null) return false
            // Hash the STORE CONTENTS, not the envelope: the envelope carries
            // a pushedAt timestamp, so envelope bytes never repeat and a
            // content-based guard computed on the envelope could never skip.
            val combined = (tsBytes?.contentHashCode() ?: 0) * 31 + (minBytes?.contentHashCode() ?: 0)
            val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            if (combined == lastPushedTsHash ||
                combined == prefs.getInt(KEY_TS_HASH, 0)
            ) {
                lastPushedTsHash = combined
                return true
            }
            // Wrap both stores in one JSON envelope the bridge can validate.
            val envelope = JSONObject().apply {
                tsBytes?.let { put("timestamps", JSONObject(String(it))) }
                minBytes?.let { put("minutes", JSONObject(String(it))) }
                put("pushedAt", System.currentTimeMillis())
            }.toString().toByteArray(Charsets.UTF_8)
            val ok = postBackupTo("$bridgeUrl/api/v1/$TIMESTAMPS_BACKUP_PATH", token, envelope)
            if (ok) {
                lastPushedTsHash = combined
                prefs.edit().putInt(KEY_TS_HASH, combined).apply()
                notePush(context)
                Log.i(TAG, "pushed timestamp stores (${envelope.size} bytes) to $bridgeUrl")
            }
            ok
        } catch (e: Exception) {
            Log.w(TAG, "timestamp backup push failed: ${e.message}")
            false
        }
    }

    /** POSTs raw bytes to [path]; returns true on HTTP 200 + status:ok. */
    /**
     * POSTs raw bytes to [path], gzip-compressed (`Content-Encoding: gzip`,
     * accepted by the bridge since 2026-11-19). The habit JSON is highly
     * repetitive (~10x compression) — on-air bytes per push drop ~10x,
     * directly cutting mobile radio-active time (the #1 remaining item of
     * the 2026-11-19 battery rounds). Returns true on HTTP 200 + status:ok.
     */
    private fun postBackupTo(path: String, token: String, bytes: ByteArray): Boolean {
        return try {
            val bufOut = java.io.ByteArrayOutputStream(bytes.size / 4)
            java.util.zip.GZIPOutputStream(bufOut).use { gz -> gz.write(bytes) }
            val gzBytes = bufOut.toByteArray()
            val conn = URL(path).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("X-App-Auth", token)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Content-Encoding", "gzip")
            conn.setFixedLengthStreamingMode(gzBytes.size)
            conn.outputStream.use { it.write(gzBytes) }
            val code = conn.responseCode
            val body = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.take(300) ?: ""
            conn.disconnect()
            code == 200 && body.contains("\"status\"")
        } catch (e: Exception) {
            Log.w(TAG, "backup POST to $path failed: ${e.message}")
            false
        }
    }

    /** POSTs the raw DB bytes; returns true on HTTP 200 + status:ok. */
    private fun postBackup(bridgeUrl: String, token: String, bytes: ByteArray): Boolean {
        val cleanUrl = bridgeUrl.trim().trimEnd('/')
        return postBackupTo("$cleanUrl/api/v1/$BACKUP_PATH", token, bytes)
    }

}
