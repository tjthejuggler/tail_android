package com.example.tail.widget

import android.util.Log
import com.example.tail.data.BridgeClient
import org.json.JSONObject

/**
 * Fetches desktop Stockfish analysis for one game through the tail bridge
 * (phone → bridge:8001 → the bridge's OWN Stockfish service; no chess-coach
 * dependency).
 *
 * The bridge deduplicates by chess.com game id in its SQLite registry — an
 * already-analyzed game returns instantly, a fresh one runs live Stockfish
 * (seconds to ~a minute at depth 12), which is why this call uses a long
 * read timeout.
 *
 * FALLBACK CONTRACT: every failure path (no bridge configured, PC off,
 * service down, timeout, malformed response) returns null. The v3 engine
 * treats a null analysis as "unknown" — the blunder rule doesn't gate and
 * every other rule still works. Being away from the PC degrades v3 to v2+,
 * never blocks the audit.
 */
object ChessAnalysisFetcher {

    private const val TAG = "ChessAnalysisFetcher"

    private val client = BridgeClient()

    /** Live games analyze shallower than the idle backlog (speed over depth). */
    const val LIVE_DEPTH = 12

    /** Fresh Stockfish analysis can take a while on a long game. */
    const val READ_TIMEOUT_MS = 90_000

    /** Bridge credentials, resolved by the caller from app settings. */
    data class BridgeCredentials(val url: String, val token: String)

    /** Per-side stats the analysis service returns. */
    data class SideStats(
        val acpl: Double?,
        val blunders: Int?,
        val unforcedBlunders: Int?
    )

    /** The fields the v3 engine consumes. */
    data class Analysis(
        /** Canonical chess.com game id the service deduped on. */
        val gameId: String?,
        /** True when the service answered from its cache (backlog overlap). */
        val cached: Boolean,
        val userStats: SideStats
    )

    /**
     * Runs the analysis for [gameId]/[pgn] and returns the [isWhite] side's
     * stats, or null when no analysis could be obtained (see fallback
     * contract above). Never throws.
     */
    suspend fun fetch(
        credentials: BridgeCredentials?,
        gameId: Long,
        pgn: String,
        username: String,
        isWhite: Boolean
    ): Analysis? {
        if (credentials == null) return null
        if (credentials.url.isBlank() || credentials.token.isBlank()) return null
        if (pgn.isBlank()) return null
        return try {
            val body = JSONObject().apply {
                put("game_id", gameId.toString())
                put("pgn", pgn)
                put("username", username)
                put("depth", LIVE_DEPTH)
            }
            val resp = client.post(
                bridgeUrl = credentials.url,
                token = credentials.token,
                path = "chess_analysis/analyze",
                body = body,
                readTimeoutMs = READ_TIMEOUT_MS
            ) ?: return null
            if (resp.has("error")) {
                Log.w(TAG, "Analysis service error: ${resp.optString("error")}")
                return null
            }
            val side = resp.optJSONObject(if (isWhite) "white" else "black")
                ?: return null
            Analysis(
                gameId = resp.optString("game_id").takeIf { it.isNotBlank() },
                cached = resp.optBoolean("cached", false),
                userStats = SideStats(
                    acpl = if (side.isNull("acpl")) null else side.optDouble("acpl"),
                    blunders = if (side.isNull("blunders")) null
                        else side.optInt("blunders"),
                    unforcedBlunders = if (side.isNull("unforced_blunders")) null
                        else side.optInt("unforced_blunders")
                )
            )
        } catch (e: Exception) {
            Log.w(TAG, "Analysis fetch failed (falling back): ${e.message}")
            null
        }
    }

    /**
     * Tiny built-in game (fool's mate, 4 plies) used by the Settings
     * "Test Pipeline" button. The fixed Link header gives it the game id
     * "tail-pipeline-test", so the first run exercises a real live Stockfish
     * analysis and every later run returns from the bridge's cache in
     * milliseconds — both prove the pipeline end-to-end.
     */
    private const val TEST_PGN =
        "[Event \"Tail Pipeline Test\"]\n" +
            "[Site \"tail_bridge\"]\n" +
            "[Date \"2026.08.25\"]\n" +
            "[White \"Tail\"]\n" +
            "[Black \"Pipeline\"]\n" +
            "[Result \"0-1\"]\n" +
            "[Link \"https://www.chess.com/game/live/tail-pipeline-test\"]\n" +
            "\n" +
            "1. f3 e5 2. g4 Qh4# 0-1"

    /**
     * Settings diagnostics: verifies the full v3 pipeline — bridge reachable
     * + auth OK (status), then a real (or cached) Stockfish analysis of the
     * built-in test game. Returns a human-readable result string, never
     * throws.
     */
    suspend fun testPipeline(url: String, token: String): String {
        // 1) Status — proves bridge reachable, auth accepted, service alive.
        val status = client.fetch(url, token, "chess_analysis/status")
            ?: return "❌ Bridge unreachable — check URL/token, PC on, " +
                "and the tail-bridge service"
        if (status.has("error")) {
            return "❌ Analysis service error: ${status.optString("error")}"
        }
        val cachedCount = status.optInt("analyzed", -1)

        // 2) Live analysis of the tiny test game — proves Stockfish runs and
        //    the response contract parses.
        val started = System.currentTimeMillis()
        val resp = try {
            client.post(
                bridgeUrl = url,
                token = token,
                path = "chess_analysis/analyze",
                body = JSONObject().apply {
                    put("pgn", TEST_PGN)
                    put("depth", 12)
                },
                readTimeoutMs = READ_TIMEOUT_MS
            )
        } catch (e: Exception) {
            null
        } ?: return "❌ Analysis request failed — bridge up but the Stockfish " +
            "service errored (see bridge logs)"
        if (resp.has("error")) {
            return "❌ Analysis error: ${resp.optString("error")}"
        }
        val white = resp.optJSONObject("white")
        val black = resp.optJSONObject("black")
        if (white == null || black == null) {
            return "❌ Unexpected analysis response (no side stats)"
        }
        val engineMs = resp.optInt("engine_ms", 0)
        val cached = resp.optBoolean("cached", false)
        val totalMs = System.currentTimeMillis() - started
        return "✅ Pipeline OK — Stockfish " +
            (if (cached) "answered from cache" else "ran live (${engineMs} ms)") +
            ", round-trip ${totalMs} ms · blunders w/b " +
            "${white.optInt("blunders")}/${black.optInt("blunders")}" +
            if (cachedCount >= 0) " · bridge cache: $cachedCount games" else ""
    }
}
