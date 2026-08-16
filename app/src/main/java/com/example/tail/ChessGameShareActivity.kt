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
import com.example.tail.widget.ChessGameAuditMapper
import com.example.tail.widget.ChessPhase2Engine
import com.example.tail.widget.ChessPhase2Store
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
 *  1. Extracts the chess.com game ID from the shared text.
 *  2. Verifies rated play is currently authorized (Phase 1 green light
 *     still inside its 60-minute window, no Yellow/Red audit since).
 *  3. Skips games that were already audited (re-share detection by ID).
 *  4. Fetches the game from the chess.com archive API (ratings, result,
 *     Game Review accuracy, PGN).
 *  5. Maps it onto [ChessPhase2Engine.GameInput] and runs the audit engine.
 *  6. Persists the audit (accuracy window + session minutes accumulate
 *     automatically) and shows the verdict.
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
    }

    private var gameId: Long = -1

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

        var startState: Ui = Ui.Working("Checking authorization…")
        if (!ChessPhase2Store.ratedPlayAuthorized(this)) {
            startState = Ui.Message(
                title = "Rated play not authorized",
                message = "Games can only be audited during an active green-light " +
                    "window.\n\nRun the ♟ Chess Readiness test from the Tail bubble " +
                    "over the chess app first."
            )
        }

        setContent {
            TailTheme(darkTheme = true) {
                var state by remember { mutableStateOf(startState) }
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
     * The full pipeline: username lookup → duplicate check → API fetch →
     * mapping → engine evaluation → persistence. Emits each UI state to
     * [emit]. Only runs the parts not already decided in [onCreate].
     */
    private suspend fun runAudit(emit: (Ui) -> Unit) {
        val username = settingsRepo.settingsFlow.first().chessComUsername.trim()
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
        val game = try {
            chessService.findGameById(username, gameId)
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
            emit(
                Ui.Message(
                    title = "Game not found yet",
                    message = "chess.com can take a minute or two to publish a " +
                        "just-finished game. Try sharing it again shortly.",
                    retry = true
                )
            )
            return
        }

        val accHistories = ChessPhase2Engine.TimeControl.entries.associateWith {
            ChessPhase2Store.accuracyHistory(this, it)
        }
        val mapping = ChessGameAuditMapper.buildInput(
            game = game,
            username = username,
            accuracyHistories = accHistories,
            sessionMinutesBefore = ChessPhase2Store.sessionMinutesUsed(this)
        )
        val ready = when (mapping) {
            is ChessGameAuditMapper.Mapping.NotAuditable -> {
                emit(Ui.Message(title = "Not auditable", message = mapping.reason))
                return
            }
            is ChessGameAuditMapper.Mapping.Ready -> mapping
        }

        val now = System.currentTimeMillis()
        val session = ChessPhase2Store.currentSessionAudits(this, now).map {
            ChessPhase2Engine.SessionGame(it.timestamp, it.timeControl, it.outputState)
        }
        val result = ChessPhase2Engine.evaluate(ready.input, session, now)

        if (ready.accuracyKnown && !ready.input.shortGame) {
            ChessPhase2Store.appendAccuracy(
                this, ready.input.timeControl, ready.input.caps2Accuracy
            )
        }
        ChessPhase2Store.appendAudit(
            this,
            ChessPhase2Store.Phase2Audit(
                timestamp = now,
                timeControl = ready.input.timeControl.name,
                outputState = result.outputState.name,
                deltaE = result.deltaE,
                caps2Accuracy = ready.input.caps2Accuracy,
                accuracyCounted = ready.accuracyKnown && !ready.input.shortGame,
                gameId = gameId.toString(),
                estimatedMinutes = ready.estimatedMinutes
            )
        )

        emit(Ui.Audited(result))
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
                text = ChessPhase2Engine.deltaEClassification(r.deltaE),
                color = Color(0xFF999999),
                fontSize = 13.sp,
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

    @Composable
    private fun Bullet(text: String, color: Color) {
        Text(text = text, color = color, fontSize = 12.sp)
    }
}
