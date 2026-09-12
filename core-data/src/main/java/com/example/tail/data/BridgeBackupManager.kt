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

    /** Minimum interval between actual pushes (coalesces burst increments). */
    private const val DEBOUNCE_MS = 60_000L

    /** Backoff between retries of a failed push within the debounce loop. */
    private val RETRY_DELAYS_MS = longArrayOf(5_000, 15_000, 45_000)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pushMutex = Mutex()

    /** Hash of the last successfully pushed payload — skips no-op uploads. */
    private var lastPushedHash: Int = 0

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
        val list = ArrayList<String>(3)
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
                val hash = bytes.contentHashCode()
                if (hash == lastPushedHash) return@withLock true

                val ok = postBackup(bridgeUrl, token, bytes)
                if (ok) {
                    lastPushedHash = hash
                    lastGoodUrl = bridgeUrl
                    Log.i(TAG, "pushed ${bytes.size} bytes to $bridgeUrl")
                }
                ok
            } catch (e: Throwable) {
                if (e is OutOfMemoryError || e is StackOverflowError) throw e
                Log.w(TAG, "pushNow failed: ${e.message}")
                false
            }
        }
    }

    /** POSTs the raw DB bytes; returns true on HTTP 200 + status:ok. */
    private fun postBackup(bridgeUrl: String, token: String, bytes: ByteArray): Boolean {
        return try {
            val cleanUrl = bridgeUrl.trim().trimEnd('/')
            val conn = URL("$cleanUrl/api/v1/$BACKUP_PATH").openConnection()
                as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("X-App-Auth", token)
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val body = (if (code == 200) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText()?.take(300) ?: ""
            conn.disconnect()
            if (code == 200 && body.contains("\"status\"")) {
                true
            } else {
                Log.w(TAG, "bridge backup POST → HTTP $code: $body")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "bridge backup POST failed: ${e.message}")
            false
        }
    }
}
