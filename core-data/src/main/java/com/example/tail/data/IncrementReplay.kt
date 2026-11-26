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

private const val TAG = "IncrementReplay"

/**
 * Schedules replays of the write-ahead increment journal ([IncrementJournal]):
 *
 *  - at process start (a previous session died mid-write, or left entries it
 *    could not confirm),
 *  - shortly after every journal change (a path just failed its write; the
 *    Syncthing/SAF condition that blocked it has usually cleared by then).
 *
 * Replay itself lives in [HabitsRepository.replayPendingIncrements] — ONE
 * verified read-modify-write for all pending entries, under the file mutex.
 * Healed entries that carry stamps get their timestamps recorded here, so a
 * replayed increment still shows up in the timestamps list at the time the
 * event actually happened.
 */
object IncrementReplay {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    /** Wait after a journal change before replaying (lets a burst settle). */
    private const val SETTLE_MS = 2_500L
    private const val FIRST_BACKOFF_MS = 10_000L
    private const val MAX_BACKOFF_MS = 5 * 60 * 1000L
    private const val MAX_ATTEMPTS = 8

    fun scheduleStartup(appContext: Context) {
        if (started) return
        started = true
        val ctx = appContext.applicationContext
        scope.launch {
            // Let the file-mutex traffic of process start settle first.
            delay(SETTLE_MS)
            replayWithBackoff(ctx)
        }
        // Follow the journal: whenever a path fails a write the journal
        // changes and we try again shortly after (debounced by version).
        scope.launch {
            var lastSeen = IncrementJournal.version.value
            IncrementJournal.version.collect { v ->
                if (v != lastSeen) {
                    lastSeen = v
                    delay(SETTLE_MS)
                    replayWithBackoff(ctx)
                }
            }
        }
    }

    /** Replays until the journal is empty or the attempt budget is spent. */
    private suspend fun replayWithBackoff(ctx: Context) {
        var backoff = 0L
        repeat(MAX_ATTEMPTS) {
            if (backoff > 0) delay(backoff)
            val pending = try {
                IncrementJournal.snapshot(ctx)
            } catch (e: Exception) {
                Log.w(TAG, "journal snapshot failed: ${e.message}"); return
            }
            if (pending.isEmpty()) return
            val settings = try {
                SettingsRepository(ctx).settingsFlow.first()
            } catch (e: Exception) {
                Log.w(TAG, "settings unavailable for replay: ${e.message}"); return
            }
            val uriStr = settings.fileUri
            if (uriStr.isEmpty()) return
            val repo = HabitsRepository()
            val tsRepo = HabitTimestampRepository(ctx)
            val healed = try {
                repo.replayPendingIncrements(Uri.parse(uriStr), ctx) { inc ->
                    // Stamps the original writer could not record itself.
                    val date = parseDate(inc.date) ?: return@replayPendingIncrements
                    for (stamp in inc.stamps) tsRepo.addTimestamp(inc.key, date, stamp)
                }
            } catch (e: Exception) {
                Log.w(TAG, "replay attempt failed (entries kept): ${e.message}")
                emptySet()
            }
            if (healed.isNotEmpty()) {
                Log.i(TAG, "replayed ${healed.size} habit key(s): $healed")
                for (k in healed) if (k.isNotBlank()) HabitIncrementBus.emit(k)
                return
            }
            backoff = if (backoff == 0L) FIRST_BACKOFF_MS else (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }
}
