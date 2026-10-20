package com.example.tail.ui.viewmodel

import android.net.Uri
import androidx.lifecycle.viewModelScope
import com.example.tail.data.BridgeClient
import com.example.tail.data.HabitsDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One restic archive snapshot of the habitsdb folder, as listed by the
 * bridge's `GET /api/v1/backup/habitsdb/snapshots`.
 */
data class ResticSnapshotUi(
    val id: String,
    val time: String,   // ISO-8601 from restic, e.g. "2026-10-19T16:07:53+03:00"
)

/**
 * In-app restore path for the PC-side restic archive (GFS-retained history of
 * the habitsdb syncthing folder). This is the disaster-recovery tier BEHIND
 * the app's own private snapshots ([loadSnapshots]/[restoreSnapshot]): it is
 * used when the phone's file AND its private snapshots are unusable, because
 * the restic repo keeps months of deduplicated history on the desktop.
 *
 * Everything flows through the bridge (tail_bridge/bridge_server.py), which
 * shells out to restic locally — the phone never touches the repo directly,
 * and the restore itself is applied with the exact same confirmed write path
 * ([com.example.tail.data.HabitsRepository.restoreDatabaseRaw]) as every
 * other restore in the app.
 */

/** Fetches the restic snapshot list from the bridge; publishes to [resticSnapshots]. */
fun HabitViewModel.loadResticSnapshots() {
    viewModelScope.launch {
        _resticStatus.value = "Loading archive snapshots…"
        val conn = getBridgeConnection()
        if (conn == null) {
            _resticSnapshots.value = emptyList()
            _resticStatus.value = "Bridge not configured — archive restore unavailable."
            return@launch
        }
        val resp = BridgeClient().fetch(conn.first, conn.second, "backup/habitsdb/snapshots")
        if (resp == null) {
            _resticSnapshots.value = emptyList()
            _resticStatus.value = "Bridge unreachable or no archive snapshots."
            return@launch
        }
        val arr = resp.optJSONArray("snapshots") ?: run {
            _resticSnapshots.value = emptyList()
            _resticStatus.value = "No archive snapshots found."
            return@launch
        }
        val list = buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val time = o.optString("time")
                if (id.isNotEmpty()) add(ResticSnapshotUi(id, time))
            }
        }
        _resticSnapshots.value = list
        _resticStatus.value = null
    }
}

/**
 * Restores the given restic snapshot's habitsdb.txt over the configured
 * habits file, via the app's standard confirmed restore path. The snapshot
 * content is fetched from the bridge (`backup/habitsdb/restore?snapshot=`),
 * which streams the file out of the restic repo with `restic dump`.
 */
fun HabitViewModel.restoreResticSnapshot(snap: ResticSnapshotUi) {
    val uriString = _settings.value.fileUri
    if (uriString.isEmpty()) {
        _resticStatus.value = "No habits file configured — cannot restore."
        return
    }
    val conn = getBridgeConnection()
    if (conn == null) {
        _resticStatus.value = "Bridge not configured — cannot restore."
        return
    }
    viewModelScope.launch {
        _resticStatus.value = "Fetching snapshot ${snap.id} from archive…"
        try {
            // restic dump can take well past the default 10 s read timeout
            // on a cold cache, so use the long-timeout variant.
            val result = BridgeClient().fetchWithStatus(
                conn.first, conn.second,
                "backup/habitsdb/restore?snapshot=${snap.id}",
                readTimeoutMs = 120_000,
            )
            if (result == null || result.first != 200 || result.second == null) {
                _resticStatus.value = "Snapshot fetch failed (bridge/restic error)."
                return@launch
            }
            _resticStatus.value = "Parsing…"
            val db = withContext(Dispatchers.Default) {
                jsonObjectToHabitsDatabase(result.second!!)
            }
            if (db.isEmpty()) {
                _resticStatus.value = "Snapshot is empty — restore aborted."
                return@launch
            }
            _resticStatus.value = "Writing restore…"
            val ok = habitsRepo.restoreDatabaseRaw(Uri.parse(uriString), context, db)
            if (ok) {
                cachedPhoneDb = db
                rebuildHabitList()
                val count = db.values.sumOf { it.size }
                _resticStatus.value = "Restored $count entries from archive snapshot ${snap.id}."
            } else {
                _resticStatus.value = "Restore write failed."
            }
        } catch (e: Exception) {
            _resticStatus.value = "Restore failed: ${e.message}"
        }
    }
}

/** Clears the transient restic status message. */
fun HabitViewModel.clearResticStatus() { _resticStatus.value = null }

/**
 * Converts the bridge's JSON document (the raw habitsdb.txt object) into the
 * in-memory [HabitsDatabase] shape. Runs on a background dispatcher by the
 * caller — a full DB document can hold hundreds of thousands of entries.
 */
private fun jsonObjectToHabitsDatabase(root: JSONObject): HabitsDatabase {
    val out = HashMap<String, HashMap<String, Int>>(root.length())
    for (habit in root.keys()) {
        val inner = root.optJSONObject(habit) ?: continue
        val entries = HashMap<String, Int>(inner.length())
        for (date in inner.keys()) {
            entries[date] = inner.getInt(date)
        }
        out[habit] = entries
    }
    return out
}
