package com.example.tail

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.example.tail.data.ChessComService
import com.example.tail.data.SettingsRepository
import com.example.tail.ui.theme.TailTheme
import com.example.tail.widget.ChessAnalysisFetcher
import com.example.tail.widget.ChessDeferredGameReconciler
import com.example.tail.widget.ChessGameAuditMapper
import com.example.tail.widget.ChessPendingGameStore
import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessPhase2Store
import com.example.tail.widget.ChessPhase2V2Engine
import com.example.tail.widget.ChessPhase2V3Engine
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ♟ Share-sheet target for the Phase 2 Post-Game Audit.
 *
 * When a rated chess.com game ends, the user taps the game's Share button
 * (or copies the link) and shares the text to Tail — e.g.
 *
 *   Check out this #chess game: jugglah vs darknessdecay -
 *   https://www.chess.com/live/game/173067813820
 *
 * This transparent activity then runs the ENTIRE audit automatically:
 *
 *  1. Extracts the chess.com game ID (and both player names) from the
 *     shared text.
 *  2. Skips games that were already audited (re-share detection by ID).
 *  3. Fetches the game from the chess.com archive API — searching BOTH
 *     players' archives, since chess.com publishes a finished game to the
 *     two players' monthly archives independently (the opponent's often
 *     lists it long before the owner's does).
 *  4. When the game is not in ANY archive yet, parks it in the pending
 *     queue ([ChessPendingGameStore]) — the deferred pipeline audits it
 *     automatically once chess.com releases it.
 *  5. Otherwise [ChessDeferredGameReconciler.processGame] classifies it by
 *     the readiness state AT THE MOMENT IT ENDED: authorized → full Phase 2
 *     audit (stamped at the game's end time); unauthorized → recorded as
 *     unapproved play in the Chess Readiness compliance stats.
 *
 * No manual data entry is required anywhere in the flow.
 */
class ChessGameShareActivity : ComponentActivity() {

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val chessService by lazy { ChessComService() }

    /** What the dialog currently shows. */
    private sealed class Ui {
        data class Working(val label: String) : Ui()
        data class Message(
            val title: String,
            val message: String,
            val retry: Boolean = false
        ) : Ui()
        data class Audited(val result: ChessPhase2Engine.AuditResult) : Ui()
        data class AuditedV2(val result: ChessPhase2V2Engine.AuditResultV2) : Ui()
        data class AuditedV3(val result: ChessPhase2V3Engine.AuditResultV3) : Ui()
    }

    private var gameId: Long = -1

    /** The raw shared text (kept for the player names around " vs "). */
    private var sharedText: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText: String? = when {
            intent?.action == Intent.ACTION_SEND &&
                    intent.type?.startsWith("text/") == true ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }

        val parsed = sharedText?.let { ChessGameAuditMapper.parseSharedGameId(it) }
        if (parsed == null) {
            Toast.makeText(this, "No chess.com game link found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        gameId = parsed
        this.sharedText = sharedText

        // Drain previously queued shares in the background. This game is
        // excluded — the dialog below handles it (avoids a duplicate audit).
        lifecycleScope.launch {
            try {
                val s = settingsRepo.settingsFlow.first()
                val username = s.chessComUsername.trim()
                if (username.isNotEmpty()) {
                    ChessDeferredGameReconciler.reconcilePending(
                        this@ChessGameShareActivity, username, chessService,
                        excludeGameId = gameId,
                        // Auto-derived from Garmin settings, like every other
                        // bridge feature.
                        bridge =
                            com.example.tail.data.bridgeConnectionFrom(
                                s.garminProxyUrl, s.garminAppToken
                            )?.let { (url, token) ->
                                ChessAnalysisFetcher.BridgeCredentials(url = url, token = token)
                            }
                    )
                }
            } catch (_: Exception) { /* best-effort */ }
        }

        setContent {
            TailTheme(darkTheme = true) {
                var state by remember { mutableStateOf<Ui>(Ui.Working("Checking authorization…")) }
                LaunchedEffect(Unit) {
                    if (state is Ui.Working) runAudit { state = it }
                }
                AuditDialog(
                    state = state,
                    onRetry = {
                        state = Ui.Working("Fetching game from chess.com…")
                        lifecycleScope.launch { runAudit { state = it } }
                    },
                    onDone = { finish() },
                    onLeaveChess = { leaveChessAndFinish() }
                )
            }
        }
    }

    /**
     * The full pipeline: username lookup → duplicate check → two-player
     * archive fetch → game-time classification & audit
     * ([ChessDeferredGameReconciler.processGame]) — or queueing when chess.com
     * hasn't published the game yet. Emits each UI state to [emit].
     */
    private suspend fun runAudit(emit: (Ui) -> Unit) {
        val settings = settingsRepo.settingsFlow.first()
        val username = settings.chessComUsername.trim()
        // Bridge credentials for the v3 desktop-Stockfish analysis,
        // auto-derived from the Garmin settings exactly like the movie and
        // PC-widget features (null when unconfigured → the audit falls back
        // to engine-less rules).
        val bridge =
            com.example.tail.data.bridgeConnectionFrom(
                settings.garminProxyUrl, settings.garminAppToken
            )?.let { (url, token) ->
                ChessAnalysisFetcher.BridgeCredentials(url = url, token = token)
            }
        if (username.isEmpty()) {
            emit(
                Ui.Message(
                    title = "No chess.com username set",
                    message = "Set your username in Tail → Settings → Chess.com " +
                        "integration so the shared game can be matched to your account."
                )
            )
            return
        }

        ChessPhase2Store.findAuditByGameId(this, gameId)?.let { previous ->
            emit(
                Ui.Message(
                    title = "Already audited",
                    message = "This game was already reported — verdict: " +
                        "${previous.outputState.replace('_', ' ').lowercase()}."
                )
            )
            return
        }

        emit(Ui.Working("Fetching game from chess.com…"))
        // Search BOTH players' archives: chess.com publishes a finished
        // game to the two players' monthly archives independently — the
        // opponent's often lists it long before the owner's does.
        val searchPlayers = (
            listOf(username) +
                ChessGameAuditMapper.parseShareUsernames(sharedText ?: "")
            )
            .mapNotNull { it.trim().lowercase().takeIf { it.isNotEmpty() } }
            .distinct()
        val game = try {
            chessService.findGameById(searchPlayers, gameId)
        } catch (e: Exception) {
            emit(
                Ui.Message(
                    title = "chess.com unreachable",
                    message = "${e.message ?: "Network error"}\n\nCheck your connection " +
                        "and try again.",
                    retry = true
                )
            )
            return
        }
        if (game == null) {
            // Not published under either player yet — park it. The
            // deferred pipeline audits it automatically once chess.com
            // releases it (no need to share it again).
            ChessPendingGameStore.enqueue(
                this, gameId, searchPlayers, System.currentTimeMillis()
            )
            emit(
                Ui.Message(
                    title = "Game queued for audit",
                    message = "chess.com hasn't published this game to any archive " +
                        "yet (the share link can appear before the archives " +
                        "update). Tail has queued it — the audit will run " +
                        "automatically once it's available, classified by your " +
                        "readiness state at the moment the game ended.",
                    retry = true
                )
            )
            return
        }

        when (val outcome =
            ChessDeferredGameReconciler.processGame(this, username, game, bridge)) {
            is ChessDeferredGameReconciler.GameOutcome.Audited ->
                emit(Ui.Audited(outcome.result))

            is ChessDeferredGameReconciler.GameOutcome.AuditedV2 ->
                emit(Ui.AuditedV2(outcome.result))

            is ChessDeferredGameReconciler.GameOutcome.AuditedV3 ->
                emit(Ui.AuditedV3(outcome.result))

            is ChessDeferredGameReconciler.GameOutcome.AlreadyAudited -> emit(
                Ui.Message(
                    title = "Already audited",
                    message = "This game was already reported — verdict: " +
                        "${outcome.previous.outputState.replace('_', ' ').lowercase()}."
                )
            )

            is ChessDeferredGameReconciler.GameOutcome.NotAuditable -> emit(
                Ui.Message(title = "Not auditable", message = outcome.reason)
            )

            is ChessDeferredGameReconciler.GameOutcome.Unauthorized -> emit(
                Ui.Message(
                    title = "Played outside authorization",
                    message = "This rated game ended outside a valid green-light " +
                        "window" +
                        (
                            outcome.stateAtPlay?.let {
                                " (latest test at play time: " +
                                    "${it.replace('_', ' ').lowercase()})"
                            } ?: " (no readiness test was on file yet)"
                            ) +
                        ". It has been recorded as unapproved play in your " +
                        "Chess Readiness stats. Run the ♟ Chess Readiness test " +
                        "to authorize rated play."
                )
            )
        }
    }

    /** Red verdict: exit to the home screen (closes the chess session). */
    private fun leaveChessAndFinish() {
        try {
            startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            )
        } catch (_: Exception) { /* best-effort */ }
        finish()
    }

    // ── Dialog UI ──────────────────────────────────────────────────────────

    @Composable
    private fun AuditDialog(
        state: Ui,
        onRetry: () -> Unit,
        onDone: () -> Unit,
        onLeaveChess: () -> Unit
    ) {
        Dialog(onDismissRequest = onDone) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                when (state) {
                    is Ui.Working -> {
                        Text(
                            text = "♟ Chess Audit",
                            color = Color(0xFFFFD700),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                color = Color(0xFFFFAA00),
                                modifier = Modifier.padding(end = 12.dp)
                            )
                            Text(state.label, color = Color(0xFFAAAAAA), fontSize = 13.sp)
                        }
                    }

                    is Ui.Message -> {
                        Text(
                            text = state.title,
                            color = Color(0xFFFF8844),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(state.message, color = Color(0xFFCCCCCC), fontSize = 13.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            if (state.retry) {
                                Button(
                                    onClick = onRetry,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF3A2A00)
                                    )
                                ) { Text("Retry", color = Color(0xFFFFAA00)) }
                                Spacer(modifier = Modifier.padding(4.dp))
                            }
                            TextButton(onClick = onDone) {
                                Text("Close", color = Color(0xFF888888))
                            }
                        }
                    }

                    is Ui.Audited -> ResultContent(state.result, onDone, onLeaveChess)
                    is Ui.AuditedV2 -> ResultContentV2(state.result, onDone, onLeaveChess)
                    is Ui.AuditedV3 -> ResultContentV3(state.result, onDone, onLeaveChess)
                }
            }
        }
    }

    @Composable
    private fun ResultContent(
        r: ChessPhase2Engine.AuditResult,
        onDone: () -> Unit,
        onLeaveChess: () -> Unit
    ) {
        val color = Color(android.graphics.Color.parseColor(r.outputState.colorHex))
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "♟ Post-Game Audit",
                color = Color(0xFFFFD700),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = r.outputState.name.replace("_", " "),
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = r.outputState.title,
                color = Color(0xFF999999),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "%+.3f".format(r.deltaE),
                color = color,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = ChessPhase2Engine.deltaEClassification(r.deltaE, r.floors),
                color = Color(0xFF999999),
                fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = "Strain ${r.strain.roundToInt()} · session " +
                    "${r.sessionStrain.roundToInt()}/${r.strainTerminateAt.roundToInt()}" +
                    if (r.readinessBuffer > 0) " (+${r.readinessBuffer} readiness)" else "",
                color = Color(0xFF888888),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Text(r.message, color = Color(0xFFDDDDDD), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))

            r.outputState.permitted.forEach {
                Bullet("✓ $it", Color(0xFF66BB6A))
            }
            r.outputState.prohibited.forEach {
                Bullet("✗ $it", Color(0xFFEF4444))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (r.outputState) {
                    ChessPhase2Engine.OutputState.TERMINATE_SESSION ->
                        Button(
                            onClick = onLeaveChess,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF5A1A2A)
                            )
                        ) { Text("Leave chess — recover", color = Color(0xFFFFAAAA)) }

                    ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS ->
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4A3A10)
                            )
                        ) { Text("Back to chess (unrated / bots only)", color = Color(0xFFEAB308)) }

                    ChessPhase2Engine.OutputState.CONTINUE_RATED ->
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1A3A1A)
                            )
                        ) { Text("Next rated game", color = Color(0xFF66BB6A)) }
                }
            }
        }
    }

    /**
     * v2 audit result — shows the verdict, every rule that fired (with the
     * personal Z-scores / streak / session / ACWR telemetry behind them)
     * and the report's intervention guidance. Buttons match the v1 result
     * screen so the enforcement behavior is identical.
     */
    @Composable
    private fun ResultContentV2(
        r: ChessPhase2V2Engine.AuditResultV2,
        onDone: () -> Unit,
        onLeaveChess: () -> Unit
    ) {
        val color = Color(android.graphics.Color.parseColor(r.outputState.colorHex))
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "♟ Post-Game Audit v2",
                color = Color(0xFFFFD700),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = r.outputState.name.replace("_", " "),
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = r.outputState.title,
                color = Color(0xFF999999),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = r.reason.replace('_', ' '),
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = listOfNotNull(
                    "session ${r.sessionMinutes} min",
                    "${r.consecutiveLosses} consecutive loss(es)",
                    r.zMoveTime?.let { "speed Z %+.2f".format(it) },
                    r.zDeficit?.let { "accuracy Z %+.2f".format(it) },
                    r.acwr?.let {
                        "ACWR " + if (it.isInfinite()) "∞" else "%.2f".format(it)
                    }
                ).joinToString("  ·  "),
                color = Color(0xFF888888),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            if (r.circadianAdjusted) {
                Text(
                    text = "circadian adjustment applied (evening play)",
                    color = Color(0xFF777777),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            Text(r.message, color = Color(0xFFDDDDDD), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))

            r.outputState.permitted.forEach {
                Bullet("✓ $it", Color(0xFF66BB6A))
            }
            r.outputState.prohibited.forEach {
                Bullet("✗ $it", Color(0xFFEF4444))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (r.outputState) {
                    ChessPhase2Engine.OutputState.TERMINATE_SESSION ->
                        Button(
                            onClick = onLeaveChess,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF5A1A2A)
                            )
                        ) { Text("Leave chess — recover", color = Color(0xFFFFAAAA)) }

                    ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS ->
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4A3A10)
                            )
                        ) { Text("Back to chess (unrated / bots only)", color = Color(0xFFEAB308)) }

                    ChessPhase2Engine.OutputState.CONTINUE_RATED ->
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1A3A1A)
                            )
                        ) { Text("Next rated game", color = Color(0xFF66BB6A)) }
                }
            }
        }
    }

    /**
     * v3 hybrid audit result — the v2 verdict layout plus the hybrid
     * telemetry: ΔE-weighted streak, strain accumulator with readiness
     * buffer, and whether desktop Stockfish analysis backed this audit.
     */
    @Composable
    private fun ResultContentV3(
        r: ChessPhase2V3Engine.AuditResultV3,
        onDone: () -> Unit,
        onLeaveChess: () -> Unit
    ) {
        val color = Color(android.graphics.Color.parseColor(r.outputState.colorHex))
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState())
        ) {
            Text(
                text = "♟ Post-Game Audit v3",
                color = Color(0xFFFFD700),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = r.outputState.name.replace("_", " "),
                color = color,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = r.outputState.title,
                color = Color(0xFF999999),
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = r.reason.replace('_', ' '),
                color = color,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = listOfNotNull(
                    "session ${r.sessionMinutes} min " +
                        "(Y>${r.fatigueYellowAt} R>${r.fatigueRedAt})",
                    if (r.weightedStreak > 0.0)
                        "loss streak %.1f".format(r.weightedStreak) else null,
                    r.zMoveTime?.let { "speed Z %+.2f".format(it) },
                    r.zDeficit?.let { "accuracy Z %+.2f".format(it) },
                    r.acwr?.let {
                        "ACWR " + if (it.isInfinite()) "∞" else "%.2f".format(it)
                    },
                    "strain ${r.strain.roundToInt()} · session " +
                        "${r.sessionStrain.roundToInt()}/${r.strainTerminateAt.roundToInt()}" +
                        if (r.readinessBuffer > 0) " (+${r.readinessBuffer} readiness)" else ""
                ).joinToString("  ·  "),
                color = Color(0xFF888888),
                fontSize = 11.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Text(
                text = if (r.engineBacked) "♟ DESKTOP STOCKFISH VERDICT — engine data used"
                       else "⚠ FALLBACK VERDICT — no engine data (bridge unreachable)",
                color = if (r.engineBacked) Color(0xFF66BB6A) else Color(0xFFEAB308),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            if (r.circadianAdjusted) {
                Text(
                    text = "circadian adjustment applied (evening play)",
                    color = Color(0xFF777777),
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))

            Text(r.message, color = Color(0xFFDDDDDD), fontSize = 13.sp)
            Spacer(modifier = Modifier.height(10.dp))

            r.outputState.permitted.forEach {
                Bullet("✓ $it", Color(0xFF66BB6A))
            }
            r.outputState.prohibited.forEach {
                Bullet("✗ $it", Color(0xFFEF4444))
            }

            Spacer(modifier = Modifier.height(16.dp))
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (r.outputState) {
                    ChessPhase2Engine.OutputState.TERMINATE_SESSION ->
                        Button(
                            onClick = onLeaveChess,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF5A1A2A)
                            )
                        ) { Text("Leave chess — recover", color = Color(0xFFFFAAAA)) }

                    ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS ->
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF4A3A10)
                            )
                        ) { Text("Back to chess (unrated / bots only)", color = Color(0xFFEAB308)) }

                    ChessPhase2Engine.OutputState.CONTINUE_RATED ->
                        Button(
                            onClick = onDone,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF1A3A1A)
                            )
                        ) { Text("Next rated game", color = Color(0xFF66BB6A)) }
                }
            }
        }
    }

    @Composable
    private fun Bullet(text: String, color: Color) {
        Text(text = text, color = color, fontSize = 12.sp)
    }
}
