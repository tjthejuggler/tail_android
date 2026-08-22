package com.example.tail.widget

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * ════════════════════════════════════════════════════════════════════════
 *  Pending chess.com game shares (deferred-audit queue)
 * ════════════════════════════════════════════════════════════════════════
 *
 * When a shared game cannot be fetched yet — chess.com publishes a
 * just-finished game to the monthly archives of the two players
 * INDEPENDENTLY, and sometimes BOTH sides lag behind the share link — the
 * share is parked here instead of being lost. The queue is drained by
 * [ChessDeferredGameReconciler.reconcilePending] on every chess.com poll
 * (and when the share sheet opens): once the game appears under ANY of the
 * recorded players, it is classified against the readiness state at the
 * moment it ENDED (approved → full Phase 2 audit; otherwise → unapproved in
 * the compliance stats) and removed from the queue.
 *
 * Plain [android.content.SharedPreferences] (not DataStore) so the bubble
 * service, the share activity and the ViewModel can all read/write it
 * synchronously — same trade-off as [ChessPhase2Store].
 */
object ChessPendingGameStore {

    private const val PREFS_NAME = "tail_chess_pending_games"
    private const val KEY_PENDING = "pending"

    /** A parked share: enough to re-fetch the game later. */
    data class PendingGame(
        /** Numeric chess.com game ID from the shared link. */
        val gameId: Long,
        /** Epoch millis when the share was first accepted into the queue. */
        val sharedAt: Long,
        /**
         * Players to search (configured username first, then the pair from
         * the share text) — lowercased, deduped.
         */
        val usernames: List<String>
    )

    /** Queue cap — shares older than [MAX_AGE_MS] are dropped by the reconciler anyway. */
    private const val MAX_PENDING = 50

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All parked shares, oldest first. */
    fun pending(context: Context): List<PendingGame> {
        val raw = prefs(context).getString(KEY_PENDING, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                PendingGame(
                    gameId = o.getLong("gameId"),
                    sharedAt = o.getLong("sharedAt"),
                    usernames = o.optJSONArray("usernames")?.let { ua ->
                        (0 until ua.length()).mapNotNull { j ->
                            ua.optString(j).takeIf { it.isNotBlank() }
                        }
                    } ?: emptyList()
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Parks a share. Re-sharing an already-queued game keeps the original
     * `sharedAt` and merges the searchable usernames (a later share may know
     * the opponent from the share text).
     */
    fun enqueue(context: Context, gameId: Long, usernames: List<String>, now: Long) {
        if (gameId <= 0) return
        val existing = pending(context).toMutableList()
        val names = usernames.mapNotNull { it.trim().lowercase().takeIf { n -> n.isNotEmpty() } }
        val idx = existing.indexOfFirst { it.gameId == gameId }
        if (idx >= 0) {
            val old = existing[idx]
            existing[idx] = old.copy(
                usernames = (old.usernames + names).distinct()
            )
        } else {
            existing.add(
                PendingGame(
                    gameId = gameId,
                    sharedAt = now,
                    usernames = names.distinct()
                )
            )
        }
        persist(context, existing.sortedBy { it.sharedAt }.takeLast(MAX_PENDING))
    }

    /** Drops a game from the queue (audited / classified / not auditable). */
    fun remove(context: Context, gameId: Long) {
        val remaining = pending(context).filterNot { it.gameId == gameId }
        persist(context, remaining)
    }

    private fun persist(context: Context, games: List<PendingGame>) {
        val arr = JSONArray()
        games.forEach {
            arr.put(JSONObject().apply {
                put("gameId", it.gameId)
                put("sharedAt", it.sharedAt)
                put("usernames", JSONArray(it.usernames))
            })
        }
        prefs(context).edit().putString(KEY_PENDING, arr.toString()).apply()
    }
}
