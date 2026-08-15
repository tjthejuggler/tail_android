package com.example.tail.widget

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.ui.theme.TailTheme

/**
 * ♟ Chess Readiness — Phase 2 Post-Game Performance Audit (spec v1.0).
 *
 * A single-screen modal launched from the floating bubble after every rated
 * game. The user transcribes 8 telemetry values from the Chess.com Game
 * Review screen; the engine computes the Elo-Adjusted Expected Score Delta
 * (ΔE) against time-control calibrated accuracy/blunder thresholds and
 * issues one of:
 *
 *  - CONTINUE_RATED  (green)  — cleared for the next rated match
 *  - PIVOT_TO_DRILLS (yellow) — rated play prohibited, pivot to drills
 *  - TERMINATE_SESSION (red)  — all play/study halted, recover
 *
 * UI memory (spec §2): the time control selector is pre-set to the last
 * used tier (`last_selected_time_control`). The rolling accuracy baseline
 * is maintained automatically per time control in [ChessPhase2Store].
 */
class ChessPhase2Activity : ComponentActivity() {

    private enum class Phase { FORM, RESULT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TailTheme(darkTheme = true) {
                ChessPhase2Screen(onClose = { finish() })
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Screen
    // ──────────────────────────────────────────────────────────────────────

    @Composable
    private fun ChessPhase2Screen(onClose: () -> Unit) {
        var phase by remember { mutableStateOf(Phase.FORM) }

        // Form state (spec §3 input schema)
        var timeControl by remember {
            mutableStateOf(ChessPhase2Engine.TimeControl.BLITZ)
        }
        var userRatingText by remember { mutableStateOf("") }
        var opponentRatingText by remember { mutableStateOf("") }
        var gameResult by remember {
            mutableStateOf<ChessPhase2Engine.GameResult?>(null)
        }
        var accuracyText by remember { mutableStateOf("") }
        var blunderText by remember { mutableStateOf("") }
        var unforced by remember { mutableStateOf(false) }
        var shortGame by remember { mutableStateOf(false) }
        var sessionMinsText by remember { mutableStateOf("") }

        // Loaded data
        var accHistory by remember { mutableStateOf(emptyList<Double>()) }
        var sessionHasPriorGames by remember { mutableStateOf(false) }
        var auditResult by remember {
            mutableStateOf<ChessPhase2Engine.AuditResult?>(null)
        }

        // Pre-set the time control from local storage (spec §2) and
        // pre-fill the session minutes when continuing a session.
        LaunchedEffect(Unit) {
            timeControl = ChessPhase2Store.lastTimeControl(this@ChessPhase2Activity)
                ?: ChessPhase2Engine.TimeControl.BLITZ
            sessionHasPriorGames =
                ChessPhase2Store.currentSessionAudits(this@ChessPhase2Activity).isNotEmpty()
            if (sessionHasPriorGames) {
                val last = ChessPhase2Store.lastSessionMins(this@ChessPhase2Activity)
                if (last > 0) sessionMinsText = last.toString()
            }
        }

        // Rolling accuracy baseline follows the selected time control.
        LaunchedEffect(timeControl) {
            accHistory = ChessPhase2Store.accuracyHistory(this@ChessPhase2Activity, timeControl)
        }

        // Parsed + validated inputs
        val userRating = userRatingText.toIntOrNull()
        val oppRating = opponentRatingText.toIntOrNull()
        val accuracy = accuracyText.toDoubleOrNull()
        val blunders = blunderText.toIntOrNull()
        val mins = sessionMinsText.toIntOrNull()
        val formValid = userRating != null && oppRating != null &&
            gameResult != null &&
            (shortGame || (accuracy != null && accuracy in 0.0..100.0)) &&
            blunders != null && blunders >= 0 &&
            mins != null && mins >= 0

        fun submit() {
            val now = System.currentTimeMillis()
            val input = ChessPhase2Engine.GameInput(
                timeControl = timeControl,
                userRating = userRating ?: 0,
                opponentRating = oppRating ?: 0,
                gameResult = gameResult ?: ChessPhase2Engine.GameResult.LOSS,
                caps2Accuracy = accuracy ?: 0.0,
                blunderCount = blunders ?: 0,
                hasUnforcedBlunder = unforced,
                sessionElapsedMins = mins ?: 0,
                shortGame = shortGame,
                accuracyHistory = accHistory
            )
            val session = ChessPhase2Store.currentSessionAudits(this, now).map {
                ChessPhase2Engine.SessionGame(it.timestamp, it.timeControl, it.outputState)
            }
            val r = ChessPhase2Engine.evaluate(input, session, now)

            // Persist: time control memory, session minutes, rolling
            // accuracy window (short games excluded), and the audit itself.
            ChessPhase2Store.saveTimeControl(this, timeControl)
            ChessPhase2Store.saveLastSessionMins(this, input.sessionElapsedMins)
            if (!shortGame && accuracy != null) {
                ChessPhase2Store.appendAccuracy(this, timeControl, accuracy)
            }
            ChessPhase2Store.appendAudit(
                this,
                ChessPhase2Store.Phase2Audit(
                    timestamp = now,
                    timeControl = timeControl.name,
                    outputState = r.outputState.name,
                    deltaE = r.deltaE,
                    caps2Accuracy = input.caps2Accuracy,
                    accuracyCounted = !shortGame && accuracy != null
                )
            )
            auditResult = r
            phase = Phase.RESULT
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xB3000000))
                .clickable(enabled = false) { /* swallow outside taps */ }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF161616), RoundedCornerShape(16.dp))
                        .padding(20.dp)
                ) {
                    when (phase) {
                        Phase.FORM -> AuditForm(
                            timeControl = timeControl,
                            onTimeControl = { timeControl = it },
                            userRatingText = userRatingText,
                            onUserRating = {
                                userRatingText = it.filter { c -> c.isDigit() }.take(4)
                            },
                            opponentRatingText = opponentRatingText,
                            onOpponentRating = {
                                opponentRatingText = it.filter { c -> c.isDigit() }.take(4)
                            },
                            gameResult = gameResult,
                            onGameResult = { gameResult = it },
                            accuracyText = accuracyText,
                            onAccuracy = {
                                accuracyText = it
                                    .filter { c -> c.isDigit() || c == '.' }
                                    .take(5)
                            },
                            accHistory = accHistory,
                            blunderText = blunderText,
                            onBlunders = {
                                blunderText = it.filter { c -> c.isDigit() }.take(2)
                            },
                            unforced = unforced,
                            onUnforced = { unforced = it },
                            shortGame = shortGame,
                            onShortGame = { shortGame = it },
                            sessionMinsText = sessionMinsText,
                            onSessionMins = {
                                sessionMinsText = it.filter { c -> c.isDigit() }.take(3)
                            },
                            sessionHasPriorGames = sessionHasPriorGames,
                            formValid = formValid,
                            onSubmit = ::submit,
                            onClose = onClose
                        )

                        Phase.RESULT -> AuditResultContent(
                            result = auditResult!!,
                            onClose = onClose,
                            onLeaveChess = {
                                startActivity(
                                    Intent(Intent.ACTION_MAIN).apply {
                                        addCategory(Intent.CATEGORY_HOME)
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                )
                                finish()
                            }
                        )
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Form
    // ──────────────────────────────────────────────────────────────────────

    @Composable
    private fun AuditForm(
        timeControl: ChessPhase2Engine.TimeControl,
        onTimeControl: (ChessPhase2Engine.TimeControl) -> Unit,
        userRatingText: String,
        onUserRating: (String) -> Unit,
        opponentRatingText: String,
        onOpponentRating: (String) -> Unit,
        gameResult: ChessPhase2Engine.GameResult?,
        onGameResult: (ChessPhase2Engine.GameResult) -> Unit,
        accuracyText: String,
        onAccuracy: (String) -> Unit,
        accHistory: List<Double>,
        blunderText: String,
        onBlunders: (String) -> Unit,
        unforced: Boolean,
        onUnforced: (Boolean) -> Unit,
        shortGame: Boolean,
        onShortGame: (Boolean) -> Unit,
        sessionMinsText: String,
        onSessionMins: (String) -> Unit,
        sessionHasPriorGames: Boolean,
        formValid: Boolean,
        onSubmit: () -> Unit,
        onClose: () -> Unit
    ) {
        Text(
            "♟ Phase 2 · Post-Game Audit",
            color = Color(0xFFBB88FF), fontSize = 18.sp, fontWeight = FontWeight.Bold
        )
        Text(
            "Open the Chess.com Game Review of your rated game and transcribe the telemetry below.",
            color = Color(0xFF888888), fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(14.dp))

        // 1. Time Control (pre-set to the last used tier)
        FieldLabel("1 · Time Control Used")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChessPhase2Engine.TimeControl.entries.forEach { tc ->
                ChoiceChip(
                    tc.label.substringBefore(" /"), // "Rapid" for RAPID tier
                    timeControl == tc,
                    Modifier.weight(1f)
                ) { onTimeControl(tc) }
            }
        }
        Text(
            "${timeControl.label}: ${timeControl.formats}",
            color = Color(0xFF666666), fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 2 + 3. Ratings
        FieldLabel("2 · Your Rating")
        OutlinedTextField(
            value = userRatingText,
            onValueChange = onUserRating,
            label = { Text("e.g. 1520") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("From the top of the Game Review screen.", color = Color(0xFF666666), fontSize = 10.sp)
        Spacer(modifier = Modifier.height(10.dp))

        FieldLabel("3 · Opponent Rating")
        OutlinedTextField(
            value = opponentRatingText,
            onValueChange = onOpponentRating,
            label = { Text("e.g. 1545") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text("From the top of the Game Review screen.", color = Color(0xFF666666), fontSize = 10.sp)
        Spacer(modifier = Modifier.height(12.dp))

        // 4. Game result
        FieldLabel("4 · Game Result")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChessPhase2Engine.GameResult.entries.forEach { r ->
                ChoiceChip(r.label.uppercase(), gameResult == r, Modifier.weight(1f)) {
                    onGameResult(r)
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        // 5. CAPS2 accuracy
        FieldLabel("5 · CAPS2 Accuracy Score (%)")
        OutlinedTextField(
            value = accuracyText,
            onValueChange = onAccuracy,
            label = { Text("e.g. 81.5") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            singleLine = true,
            enabled = !shortGame,
            modifier = Modifier.fillMaxWidth()
        )
        val mean = ChessPhase2Engine.rollingMean(accHistory, timeControl)
        Text(
            if (shortGame) {
                "Short game — accuracy check BYPASSED (spec §7.1). ΔE is still evaluated."
            } else if (accHistory.isNotEmpty()) {
                "Found under \"Accuracy\" in Game Review.\n" +
                    "Baseline: rolling ${accHistory.size}-game mean " +
                    "%.1f%% · violation if drop > %.0f%%"
                        .format(mean, timeControl.accTolerance)
            } else {
                "Found under \"Accuracy\" in Game Review.\n" +
                    "Baseline: default %.0f%% (no history yet) · violation if drop > %.0f%%"
                        .format(mean, timeControl.accTolerance)
            },
            color = Color(0xFF666666), fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 6. Blunder count
        FieldLabel("6 · Blunder Count")
        OutlinedTextField(
            value = blunderText,
            onValueChange = onBlunders,
            label = { Text("e.g. 1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Move Classification → red blunder icon.",
            color = Color(0xFF666666), fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 7. Unforced blunders
        FieldLabel("7 · Unforced Blunders")
        CheckRow(
            text = "Yes — at least 1 blunder occurred before time scramble",
            checked = unforced,
            onChecked = onUnforced
        )
        Text(
            "Only count blunders made with plenty of clock left " +
                "(${timeControl.label.substringBefore(" /")}: > ${timeControl.scrambleSec} s " +
                "remaining). Time-scramble blunders are clock errors, not calculation " +
                "failures — leave unchecked.",
            color = Color(0xFF666666), fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Edge case: short game
        CheckRow(
            text = "Game ended in under 10 moves (early resignation)",
            checked = shortGame,
            onChecked = onShortGame
        )
        Text(
            "Bypasses the accuracy check and keeps this game out of the rolling mean.",
            color = Color(0xFF666666), fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 8. Session elapsed time
        FieldLabel("8 · Total Elapsed Playing Time This Session (mins)")
        OutlinedTextField(
            value = sessionMinsText,
            onValueChange = onSessionMins,
            label = { Text("e.g. 35") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            if (sessionHasPriorGames)
                "Pre-filled from your last audit — bump it up. ≥ 60 min ends the session."
            else
                "Include this game. ≥ 60 min ends the session (prefrontal fatigue ceiling).",
            color = Color(0xFF666666), fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            enabled = formValid,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A5A))
        ) {
            Text("Run Audit", color = Color(0xFFDDBBFF))
        }
        TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Cancel", color = Color(0xFF777777), fontSize = 12.sp)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Result
    // ──────────────────────────────────────────────────────────────────────

    @Composable
    private fun AuditResultContent(
        result: ChessPhase2Engine.AuditResult,
        onClose: () -> Unit,
        onLeaveChess: () -> Unit
    ) {
        val stateColor = Color(android.graphics.Color.parseColor(result.outputState.colorHex))

        Text(
            "♟ Phase 2 · Post-Game Audit",
            color = Color(0xFFBB88FF), fontSize = 18.sp, fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                result.outputState.name.replace("_", " "),
                color = stateColor, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Text(result.outputState.title, color = stateColor, fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "%+.3f".format(result.deltaE),
                color = stateColor, fontSize = 44.sp, fontWeight = FontWeight.Bold
            )
            Text("ΔE · Elo expected-score delta", color = Color(0xFF666666), fontSize = 10.sp)
            Text(
                ChessPhase2Engine.deltaEClassification(result.deltaE),
                color = Color(0xFF999999), fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        Text(result.message, color = Color(0xFFDDDDDD), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(12.dp))

        // Telemetry breakdown
        SectionTitle("Audit breakdown")
        BreakdownRow(
            "Expected score E_A",
            "%.1f%%".format(result.expectedScore * 100)
        )
        BreakdownRow(
            if (result.accuracyIgnored) "Accuracy check" else "Accuracy vs baseline",
            if (result.accuracyIgnored) "BYPASSED (short game)"
            else "%+.1f pts (baseline %.1f%%%s)".format(
                -result.accuracyDelta,
                result.rollingMeanUsed,
                if (result.usedDefaultMean) ", default" else ""
            )
        )
        BreakdownRow("Accuracy violation", if (result.accViolation) "YES" else "no")
        BreakdownRow("Unforced blunder violation", if (result.blunderViolation) "YES" else "no")
        BreakdownRow("False success", if (result.isFalseSuccess) "DETECTED" else "no")
        BreakdownRow("Reason", result.reason)
        Spacer(modifier = Modifier.height(12.dp))

        // Permissions
        if (result.outputState.permitted.isNotEmpty()) {
            SectionTitle("✓ Permitted now")
            result.outputState.permitted.forEach {
                Text("✓ $it", color = Color(0xFF66BB6A), fontSize = 12.sp)
            }
        }
        if (result.outputState.prohibited.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            SectionTitle("✗ Prohibited now")
            result.outputState.prohibited.forEach {
                Text("✗ $it", color = Color(0xFFEF4444), fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(14.dp))

        when (result.outputState) {
            ChessPhase2Engine.OutputState.TERMINATE_SESSION -> {
                Button(
                    onClick = onLeaveChess,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A1A2A))
                ) {
                    Text("Leave chess — recover", color = Color(0xFFFFAAAA))
                }
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Close", color = Color(0xFF888888), fontSize = 12.sp)
                }
            }
            ChessPhase2Engine.OutputState.PIVOT_TO_DRILLS -> {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Back to chess (unrated / bots only)")
                }
            }
            ChessPhase2Engine.OutputState.CONTINUE_RATED -> {
                Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
                    Text("Next rated game")
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Small shared pieces
    // ──────────────────────────────────────────────────────────────────────

    @Composable
    private fun FieldLabel(text: String) {
        Text(text, color = Color(0xFFCCCCCC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
    }

    @Composable
    private fun CheckRow(text: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onChecked(!checked) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = checked, onCheckedChange = onChecked)
            Text(text, color = Color(0xFFCCCCCC), fontSize = 12.sp)
        }
    }

    @Composable
    private fun BreakdownRow(label: String, value: String) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color(0xFFAAAAAA), fontSize = 12.sp)
            Text(value, color = Color(0xFFDDDDDD), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, color = Color(0xFFCCCCCC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
    }

    @Composable
    private fun ChoiceChip(
        label: String,
        selected: Boolean,
        modifier: Modifier,
        onClick: () -> Unit
    ) {
        Box(
            modifier = modifier
                .background(
                    if (selected) Color(0xFF2A2A3A) else Color(0xFF1E1E1E),
                    RoundedCornerShape(10.dp)
                )
                .clickable { onClick() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                color = if (selected) Color(0xFF66CCFF) else Color(0xFFAAAAAA),
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
