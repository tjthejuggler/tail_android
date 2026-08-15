package com.example.tail.widget

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.example.tail.data.GarminRepository
import com.example.tail.data.GarminType
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.HabitsRepository
import com.example.tail.data.SettingsRepository
import com.example.tail.ui.HabitIncrementBus
import com.example.tail.ui.theme.TailTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ♟ Chess Readiness — Phase 1 Pre-Session Diagnostic Protocol (spec v2.1).
 *
 * A STEP-BY-STEP flow launched from the floating bubble. The user taps the
 * widget BEFORE doing anything, answers the quick subjective steps, then is
 * sent into the chess app for each objective measurement — returning via the
 * widget each time. Progress is persisted in [ChessReadinessStore] so the
 * activity can be dismissed between steps and resume exactly where it left
 * off (with the puzzle timer anchored to when the step was shown).
 *
 * Flow:
 *  1. SLEEP — today's Garmin sleep score is shown (or asked for) and mapped
 *     to a sleep tier.
 *  2. CLARITY — four 0–10 sliders (focus / calm / energy / alertness); the
 *     average maps to the mental-clarity tier.
 *  3. PUZZLES — three standard Rated Puzzles (catered to the user's rating,
 *     unlike the wildly-varying Daily Puzzle). For each: the app shows
 *     instructions, starts a timer, closes; the user solves ONE puzzle and
 *     taps the widget again to report solved/failed (time auto-computed).
 *  4. RUSH — one 3-minute Puzzle Rush; the user returns and reports how
 *     many puzzles they solved and how many failures (strikes).
 *  5. RESULT — the CCRS 0–100 with the Green / Yellow / Red authorization
 *     and exactly what the user is and is not allowed to do for the next
 *     60 minutes. A Red result offers a "Leave Chess" button that exits to
 *     the home screen instead of returning to the chess app.
 */
class ChessReadinessActivity : ComponentActivity() {

    /** Wizard phase. */
    private enum class Phase { LOADING, BLOCKED, SLEEP, CLARITY, PUZZLE_GO, PUZZLE_RESULT, RUSH_GO, RUSH_RESULT, RESULT }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TailTheme(darkTheme = true) {
                ChessReadinessScreen(onClose = { finish() })
            }
        }
    }

    /**
     * Credits +1 to [habitName] (if linked in Settings) for puzzle/rush
     * activity completed during the readiness test. Fire-and-forget on IO;
     * mirrors the IPC increment path (respects the max-1/day cap, emits the
     * increment bus so open UIs refresh, records a timestamp).
     */
    private fun creditHabit(habitName: String) {
        if (habitName.isBlank()) return
        val appContext = applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val habitsRepo = HabitsRepository()
                val settings = SettingsRepository(appContext).settingsFlow.first()
                val uriStr = settings.fileUri
                if (uriStr.isEmpty()) return@launch
                val uri = Uri.parse(uriStr)

                // Respect the "max 1 per day" cap some habits have
                if (habitName in settings.maxOneHabits) {
                    val db = habitsRepo.loadDatabase(uri, appContext)
                    val today = LocalDate.now().toString()
                    if ((db[habitName]?.get(today) ?: 0) >= 1) return@launch
                }

                habitsRepo.incrementHabit(uri, appContext, habitName, 1)
                HabitIncrementBus.emit(habitName)
                try {
                    HabitTimestampRepository(appContext).addTimestamp(habitName)
                } catch (_: Exception) { /* timestamp optional */ }
            } catch (_: Exception) { /* credit is best-effort */ }
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Screen
    // ──────────────────────────────────────────────────────────────────────

    @Composable
    private fun ChessReadinessScreen(onClose: () -> Unit) {
        var phase by remember { mutableStateOf(Phase.LOADING) }
        var blockedMessage by remember { mutableStateOf("") }

        // Data loaded once at open
        var garminSleepScore by remember { mutableStateOf<Int?>(null) }
        var rushAth by remember { mutableStateOf(0) }

        // Session state (persisted after every transition)
        var sessionStartedAt by remember { mutableStateOf(0L) }
        var stepStartedAt by remember { mutableStateOf(0L) }
        var sleepScore by remember { mutableStateOf<Int?>(null) }
        var sleepFromGarmin by remember { mutableStateOf(false) }
        var sleepScoreText by remember { mutableStateOf("") }
        var focus by remember { mutableStateOf(5) }
        var calm by remember { mutableStateOf(5) }
        var energy by remember { mutableStateOf(5) }
        var alert by remember { mutableStateOf(5) }
        var puzzleIndex by remember { mutableStateOf(0) }
        var puzzleTimes by remember { mutableStateOf(emptyList<Int>()) }
        var puzzleTimeText by remember { mutableStateOf("") }
        var puzzleSolved by remember { mutableStateOf<Boolean?>(null) }
        var rushScoreText by remember { mutableStateOf("") }
        var rushStrikes by remember { mutableStateOf(-1) }
        var result by remember { mutableStateOf<ChessReadinessEngine.ReadinessResult?>(null) }

        /** Persists the current session at [step] so the widget can resume. */
        fun persist(
            step: SessionStep,
            puzzleIdx: Int = puzzleIndex,
            timerAnchor: Long = stepStartedAt
        ) {
            ChessReadinessStore.saveSession(
                this,
                ReadinessSession(
                    startedAt = sessionStartedAt,
                    updatedAt = System.currentTimeMillis(),
                    step = step,
                    puzzleIndex = puzzleIdx,
                    sleepScore = sleepScore,
                    sleepFromGarmin = sleepFromGarmin,
                    clarityScores = listOf(focus, calm, energy, alert),
                    puzzleTimesSec = puzzleTimes,
                    stepStartedAt = timerAnchor
                )
            )
        }

        fun abandon() {
            ChessReadinessStore.clearSession(this)
            onClose()
        }

        // Initial load: resume session if present, else check the rate gate.
        LaunchedEffect(Unit) {
            rushAth = ChessReadinessStore.lastRushAllTimeHigh(this@ChessReadinessActivity)

            // Today's Garmin sleep score from the Tail cache (single month file)
            garminSleepScore = withContext(Dispatchers.IO) {
                try {
                    val today = LocalDate.now()
                    GarminRepository(this@ChessReadinessActivity)
                        .loadFromCache(today.year, today.monthValue)
                        ?.get(GarminType.SLEEP_SCORE)
                        ?.get(today.toString())
                } catch (_: Exception) {
                    null
                }
            }

            val session = ChessReadinessStore.loadSession(this@ChessReadinessActivity)
            if (session != null) {
                // ── Resume exactly where the user left off ──
                sessionStartedAt = session.startedAt
                stepStartedAt = session.stepStartedAt
                sleepScore = session.sleepScore
                sleepFromGarmin = session.sleepFromGarmin
                if (!session.sleepFromGarmin && session.sleepScore != null) {
                    sleepScoreText = session.sleepScore.toString()
                }
                if (session.clarityScores.size == 4) {
                    focus = session.clarityScores[0]
                    calm = session.clarityScores[1]
                    energy = session.clarityScores[2]
                    alert = session.clarityScores[3]
                }
                puzzleIndex = session.puzzleIndex
                puzzleTimes = session.puzzleTimesSec
                when (session.step) {
                    SessionStep.SLEEP -> phase = Phase.SLEEP
                    SessionStep.CLARITY -> phase = Phase.CLARITY
                    SessionStep.PUZZLE_GO -> phase = Phase.PUZZLE_GO
                    SessionStep.PUZZLE_RESULT -> {
                        // Pre-fill the elapsed time from the timer anchor
                        val elapsed = if (session.stepStartedAt > 0)
                            ((System.currentTimeMillis() - session.stepStartedAt) / 1000L).toInt()
                        else 0
                        puzzleTimeText = elapsed.coerceAtLeast(0).toString()
                        phase = Phase.PUZZLE_RESULT
                    }
                    SessionStep.RUSH_GO -> phase = Phase.RUSH_GO
                    SessionStep.RUSH_RESULT -> phase = Phase.RUSH_RESULT
                }
            } else {
                // ── Fresh test: rate-limit gate ──
                sessionStartedAt = System.currentTimeMillis()
                val history = ChessReadinessStore.loadHistory(this@ChessReadinessActivity)
                when (val gate = ChessReadinessEngine.checkGate(history, System.currentTimeMillis())) {
                    is ChessReadinessEngine.GateStatus.Blocked -> {
                        blockedMessage = gate.error.message
                        phase = Phase.BLOCKED
                    }
                    is ChessReadinessEngine.GateStatus.Allowed -> phase = Phase.SLEEP
                }
            }
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
                        Phase.LOADING -> LoadingContent()

                        Phase.BLOCKED -> BlockedContent(message = blockedMessage, onClose = onClose)

                        Phase.SLEEP -> SleepStep(
                            garminScore = garminSleepScore,
                            manualText = sleepScoreText,
                            onManual = { sleepScoreText = it.filter { c -> c.isDigit() }.take(3) },
                            onNext = {
                                val score = garminSleepScore ?: sleepScoreText.toIntOrNull()
                                if (score != null) {
                                    sleepScore = score
                                    sleepFromGarmin = garminSleepScore != null
                                    persist(SessionStep.CLARITY)
                                    phase = Phase.CLARITY
                                }
                            },
                            onAbandon = ::abandon
                        )

                        Phase.CLARITY -> ClarityStep(
                            focus = focus, calm = calm, energy = energy, alert = alert,
                            onFocus = { focus = it }, onCalm = { calm = it },
                            onEnergy = { energy = it }, onAlert = { alert = it },
                            onBack = { phase = Phase.SLEEP },
                            onNext = {
                                persist(SessionStep.PUZZLE_GO, puzzleIdx = 0)
                                puzzleIndex = 0
                                phase = Phase.PUZZLE_GO
                            },
                            onAbandon = ::abandon
                        )

                        Phase.PUZZLE_GO -> PuzzleGoStep(
                            index = puzzleIndex,
                            onStart = {
                                stepStartedAt = System.currentTimeMillis()
                                persist(SessionStep.PUZZLE_RESULT, timerAnchor = stepStartedAt)
                                finish() // user goes to solve the puzzle in the chess app
                            },
                            onAbandon = ::abandon
                        )

                        Phase.PUZZLE_RESULT -> PuzzleResultStep(
                            index = puzzleIndex,
                            timeText = puzzleTimeText,
                            onTime = { puzzleTimeText = it.filter { c -> c.isDigit() }.take(3) },
                            solved = puzzleSolved,
                            onSolved = { puzzleSolved = it },
                            onRestartTimer = {
                                puzzleTimeText = ""
                                puzzleSolved = null
                                persist(SessionStep.PUZZLE_GO)
                                phase = Phase.PUZZLE_GO
                            },
                            onNext = {
                                val effective = if (puzzleSolved == false)
                                    ChessReadinessEngine.PUZZLE_FAIL_TIME_SEC
                                else puzzleTimeText.toIntOrNull() ?: 0
                                puzzleTimes = puzzleTimes + effective
                                // Credit the linked habit for this puzzle
                                creditHabit(
                                    ChessReadinessStore.linkedPuzzleHabit(
                                        this@ChessReadinessActivity
                                    )
                                )
                                puzzleTimeText = ""
                                puzzleSolved = null
                                if (puzzleIndex + 1 < ChessReadinessEngine.RATED_PUZZLE_COUNT) {
                                    puzzleIndex += 1
                                    persist(SessionStep.PUZZLE_GO, puzzleIdx = puzzleIndex)
                                    phase = Phase.PUZZLE_GO
                                } else {
                                    persist(SessionStep.RUSH_GO)
                                    phase = Phase.RUSH_GO
                                }
                            },
                            onAbandon = ::abandon
                        )

                        Phase.RUSH_GO -> RushGoStep(
                            onStart = {
                                persist(SessionStep.RUSH_RESULT)
                                finish() // user goes to play the rush in the chess app
                            },
                            onAbandon = ::abandon
                        )

                        Phase.RUSH_RESULT -> RushResultStep(
                            scoreText = rushScoreText,
                            onScore = { rushScoreText = it.filter { c -> c.isDigit() }.take(3) },
                            strikes = rushStrikes,
                            onStrikes = { rushStrikes = it },
                            allTimeHigh = rushAth,
                            onSubmit = {
                                val input = ChessReadinessEngine.ReadinessInput(
                                    sleepTier = ChessReadinessEngine.sleepTierFromGarminScore(
                                        sleepScore ?: 0
                                    ),
                                    clarityTier = ChessReadinessEngine.clarityTierFromAverage(
                                        listOf(focus, calm, energy, alert).average()
                                    ),
                                    puzzleTimesSec = puzzleTimes,
                                    rushScore = rushScoreText.toIntOrNull() ?: 0,
                                    rushAllTimeHigh = rushAth,
                                    rushStrikes = rushStrikes.coerceAtLeast(0)
                                )
                                val now = System.currentTimeMillis()

                                // Re-verify the rate-limit gate at submission time
                                val history = ChessReadinessStore.loadHistory(this@ChessReadinessActivity)
                                when (val gate = ChessReadinessEngine.checkGate(history, now)) {
                                    is ChessReadinessEngine.GateStatus.Blocked -> {
                                        blockedMessage = gate.error.message
                                        ChessReadinessStore.clearSession(this@ChessReadinessActivity)
                                        phase = Phase.BLOCKED
                                    }
                                    is ChessReadinessEngine.GateStatus.Allowed -> {
                                        val r = ChessReadinessEngine.evaluate(input, now)
                                        ChessReadinessStore.appendTest(
                                            this@ChessReadinessActivity,
                                            ChessReadinessEngine.ReadinessTest(
                                                r.timestamp, r.ccrs, r.state.name
                                            )
                                        )
                                        // Credit the linked habit for the rush run
                                        creditHabit(
                                            ChessReadinessStore.linkedRushHabit(
                                                this@ChessReadinessActivity
                                            )
                                        )
                                        // Auto-update the stored all-time high
                                        // only when this run actually beat it.
                                        val newAth = ChessReadinessEngine.nextAllTimeHigh(
                                            rushAth, input.rushScore
                                        )
                                        if (newAth != rushAth) {
                                            ChessReadinessStore.saveRushAllTimeHigh(
                                                this@ChessReadinessActivity, newAth
                                            )
                                        }
                                        ChessReadinessStore.clearSession(this@ChessReadinessActivity)
                                        result = r
                                        phase = Phase.RESULT
                                    }
                                }
                            },
                            onAbandon = ::abandon
                        )

                        Phase.RESULT -> ResultContent(
                            result = result!!,
                            onBackToChess = onClose,
                            onLeaveChess = {
                                // Exit to the home screen — effectively closes
                                // the chess app session.
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
    //  Step contents
    // ──────────────────────────────────────────────────────────────────────

    @Composable
    private fun LoadingContent() {
        Text("♟ Chess Readiness", color = Color(0xFFBB88FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text("Checking gates…", color = Color(0xFF999999), fontSize = 13.sp)
    }

    @Composable
    private fun BlockedContent(message: String, onClose: () -> Unit) {
        Text("♟ Chess Readiness", color = Color(0xFFBB88FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Test unavailable", color = Color(0xFFEF4444), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(12.dp))
        Text(message, color = Color(0xFFDDDDDD), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text("Close")
        }
    }

    @Composable
    private fun SleepStep(
        garminScore: Int?,
        manualText: String,
        onManual: (String) -> Unit,
        onNext: () -> Unit,
        onAbandon: () -> Unit
    ) {
        StepHeader("Step 1 · Sleep Quality")
        if (garminScore != null) {
            val tier = ChessReadinessEngine.sleepTierFromGarminScore(garminScore)
            Text(
                "✓ Garmin sleep score today: $garminScore → ${tier.label} (${tier.points} pts)",
                color = Color(0xFF66BB6A), fontSize = 14.sp
            )
            Text(tier.description, color = Color(0xFF888888), fontSize = 11.sp)
            Spacer(modifier = Modifier.height(6.dp))
            Text("Taken automatically from your Tail Garmin data.", color = Color(0xFF666666), fontSize = 10.sp)
        } else {
            Text("No Garmin sleep score for today — enter it (0–100):", color = Color(0xFF999999), fontSize = 12.sp)
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = manualText,
                onValueChange = onManual,
                label = { Text("Sleep score") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text("≥ 80 → 25 pts · 60–79 → 15 pts · < 60 → 0 pts", color = Color(0xFF666666), fontSize = 10.sp)
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onNext,
            enabled = garminScore != null || manualText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A5A))
        ) {
            Text("Next", color = Color(0xFFDDBBFF))
        }
        AbandonButton(onAbandon)
    }

    @Composable
    private fun ClarityStep(
        focus: Int, calm: Int, energy: Int, alert: Int,
        onFocus: (Int) -> Unit, onCalm: (Int) -> Unit,
        onEnergy: (Int) -> Unit, onAlert: (Int) -> Unit,
        onBack: () -> Unit, onNext: () -> Unit,
        onAbandon: () -> Unit
    ) {
        StepHeader("Step 2 · Mental Clarity")
        Text("Slide each to how you feel RIGHT NOW:", color = Color(0xFF999999), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(10.dp))

        ClaritySlider("Focus", "scattered", "laser-focused", focus, onFocus)
        ClaritySlider("Calm", "very stressed", "completely calm", calm, onCalm)
        ClaritySlider("Energy", "exhausted", "fully energized", energy, onEnergy)
        ClaritySlider("Alertness", "groggy", "sharp", alert, onAlert)

        val avg = listOf(focus, calm, energy, alert).average()
        val tier = ChessReadinessEngine.clarityTierFromAverage(avg)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            "Average %.1f → ${tier.label} (${tier.points} pts)".format(avg),
            color = Color(0xFF66CCFF), fontSize = 13.sp, fontWeight = FontWeight.Bold
        )
        Text("≥ 7.5 → 25 pts · 5.0–7.4 → 15 pts · < 5.0 → 0 pts", color = Color(0xFF666666), fontSize = 10.sp)

        Spacer(modifier = Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) {
                Text("Back", color = Color(0xFFAAAAAA))
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(2f),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A5A))
            ) {
                Text("Next", color = Color(0xFFDDBBFF))
            }
        }
        AbandonButton(onAbandon)
    }

    @Composable
    private fun ClaritySlider(
        label: String,
        lowLabel: String,
        highLabel: String,
        value: Int,
        onChange: (Int) -> Unit
    ) {
        Column(modifier = Modifier.padding(vertical = 2.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(label, color = Color(0xFFCCCCCC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("$value / 10", color = Color(0xFF66CCFF), fontSize = 13.sp)
            }
            Slider(
                value = value.toFloat(),
                onValueChange = { onChange(it.toInt()) },
                valueRange = 0f..10f,
                steps = 9
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(lowLabel, color = Color(0xFF777777), fontSize = 10.sp)
                Text(highLabel, color = Color(0xFF777777), fontSize = 10.sp)
            }
        }
    }

    @Composable
    private fun PuzzleGoStep(index: Int, onStart: () -> Unit, onAbandon: () -> Unit) {
        val n = index + 1
        StepHeader("Step 3 · Rated Puzzle $n/${ChessReadinessEngine.RATED_PUZZLE_COUNT}")
        Text(
            "Now go solve ONE standard Rated Puzzle in your chess app — no warm-up, " +
                "first attempt only. Rated puzzles are catered to your level, unlike " +
                "the Daily Puzzle.",
            color = Color(0xFFDDDDDD), fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "The timer starts when you tap Start. When you're done, tap the " +
                "♟ widget again to report the result.",
            color = Color(0xFF999999), fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A5A))
        ) {
            Text("Start — timer running", color = Color(0xFFDDBBFF))
        }
        AbandonButton(onAbandon)
    }

    @Composable
    private fun PuzzleResultStep(
        index: Int,
        timeText: String,
        onTime: (String) -> Unit,
        solved: Boolean?,
        onSolved: (Boolean) -> Unit,
        onRestartTimer: () -> Unit,
        onNext: () -> Unit,
        onAbandon: () -> Unit
    ) {
        val n = index + 1
        StepHeader("Step 3 · Rated Puzzle $n/${ChessReadinessEngine.RATED_PUZZLE_COUNT} — result")
        Text("Solved on attempt #1 without clues?", color = Color(0xFFCCCCCC), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip("Solved", solved == true, Modifier.weight(1f)) { onSolved(true) }
            ChoiceChip("Failed", solved == false, Modifier.weight(1f)) { onSolved(false) }
        }
        if (solved == true) {
            Spacer(modifier = Modifier.height(10.dp))
            OutlinedTextField(
                value = timeText,
                onValueChange = onTime,
                label = { Text("Solve time (seconds)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                "Pre-filled from the timer — adjust if you were interrupted.\n" +
                    "avg < 45 s → 25 pts · 45–119 s → 15 pts · ≥ 120 s → 0 pts",
                color = Color(0xFF666666), fontSize = 10.sp
            )
        } else if (solved == false) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "Failed puzzle counts as ${ChessReadinessEngine.PUZZLE_FAIL_TIME_SEC} s " +
                    "toward the average.",
                color = Color(0xFF888888), fontSize = 11.sp
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onNext,
            enabled = solved != null && (solved == false || timeText.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A5A))
        ) {
            Text(
                if (index + 1 < ChessReadinessEngine.RATED_PUZZLE_COUNT) "Next puzzle"
                else "Next: Puzzle Rush",
                color = Color(0xFFDDBBFF)
            )
        }
        TextButton(onClick = onRestartTimer, modifier = Modifier.fillMaxWidth()) {
            Text("Not done yet — restart timer", color = Color(0xFF888888), fontSize = 12.sp)
        }
        AbandonButton(onAbandon)
    }

    @Composable
    private fun RushGoStep(onStart: () -> Unit, onAbandon: () -> Unit) {
        StepHeader("Step 4 · 3-Minute Puzzle Rush")
        Text(
            "Now go play ONE 3-minute Puzzle Rush run in your chess app.",
            color = Color(0xFFDDDDDD), fontSize = 13.sp
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            "When it's over, tap the ♟ widget again and report how many puzzles " +
                "you solved and how many failures you had.",
            color = Color(0xFF999999), fontSize = 12.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A5A))
        ) {
            Text("Start Rush", color = Color(0xFFDDBBFF))
        }
        AbandonButton(onAbandon)
    }

    @Composable
    private fun RushResultStep(
        scoreText: String,
        onScore: (String) -> Unit,
        strikes: Int,
        onStrikes: (Int) -> Unit,
        allTimeHigh: Int,
        onSubmit: () -> Unit,
        onAbandon: () -> Unit
    ) {
        StepHeader("Step 4 · 3-Minute Puzzle Rush — result")
        OutlinedTextField(
            value = scoreText,
            onValueChange = onScore,
            label = { Text("Puzzles solved") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        val baseline = maxOf(allTimeHigh, ChessReadinessEngine.RUSH_BASELINE_FLOOR)
        Text(
            "Measured against your all-time best of $allTimeHigh " +
                "(readiness baseline $baseline" +
                (if (allTimeHigh < ChessReadinessEngine.RUSH_BASELINE_FLOOR)
                    ", cold-start floor applied" else "") +
                "). Beat your best and it updates automatically in Settings.",
            color = Color(0xFF888888), fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Failures (strikes) during the run", color = Color(0xFFCCCCCC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (0..3).forEach { k ->
                ChoiceChip("$k", strikes == k, Modifier.weight(1f)) { onStrikes(k) }
            }
        }
        Text(
            "≥ 80 % of best → 25 pts · 65–79 % → 15 pts · each strike −${ChessReadinessEngine.RUSH_STRIKE_PENALTY} pts",
            color = Color(0xFF666666), fontSize = 10.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = onSubmit,
            enabled = scoreText.isNotBlank() && strikes >= 0,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A5A))
        ) {
            Text("Compute Readiness", color = Color(0xFFDDBBFF))
        }
        AbandonButton(onAbandon)
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Result
    // ──────────────────────────────────────────────────────────────────────

    @Composable
    private fun ResultContent(
        result: ChessReadinessEngine.ReadinessResult,
        onBackToChess: () -> Unit,
        onLeaveChess: () -> Unit
    ) {
        val stateColor = Color(android.graphics.Color.parseColor(result.state.colorHex))

        Text("♟ Chess Readiness", color = Color(0xFFBB88FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(8.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "${result.ccrs}",
                color = stateColor, fontSize = 56.sp, fontWeight = FontWeight.Bold
            )
            Text(
                result.state.name.replace("_", " "),
                color = stateColor, fontSize = 16.sp, fontWeight = FontWeight.Bold
            )
            Text(
                "CCRS 0–100", color = Color(0xFF666666), fontSize = 10.sp
            )
        }
        Spacer(modifier = Modifier.height(10.dp))

        Text(result.state.message, color = Color(0xFFDDDDDD), fontSize = 13.sp)
        Spacer(modifier = Modifier.height(12.dp))

        // Breakdown
        SectionTitle("Score breakdown")
        BreakdownRow("Sleep quality", result.sSleep)
        BreakdownRow("Mental clarity", result.sClarity)
        BreakdownRow("Rated puzzles", result.pPuzzle)
        BreakdownRow("Puzzle Rush", result.pRush)
        Spacer(modifier = Modifier.height(12.dp))

        // Permissions
        SectionTitle("✓ Permitted now")
        result.state.permitted.forEach {
            Text("✓ $it", color = Color(0xFF66BB6A), fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(8.dp))
        SectionTitle("✗ Prohibited now")
        result.state.prohibited.forEach {
            Text("✗ $it", color = Color(0xFFEF4444), fontSize = 12.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))

        val validUntil = Instant.ofEpochMilli(result.validUntil)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm"))
        Text(
            "Authorization valid until $validUntil (60 min). " +
                "After that, a new diagnostic is required.",
            color = Color(0xFF888888), fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (result.state == ChessReadinessEngine.ReadinessState.RED_LIGHT) {
            // Red = no chess at all: leave the chess app entirely.
            Button(
                onClick = onLeaveChess,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF5A1A2A))
            ) {
                Text("Leave", color = Color(0xFFFFAAAA))
            }
        } else {
            Button(onClick = onBackToChess, modifier = Modifier.fillMaxWidth()) {
                Text("Back to chess")
            }
        }
    }

    @Composable
    private fun BreakdownRow(label: String, points: Int) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = Color(0xFFAAAAAA), fontSize = 12.sp)
            Text("$points / 25", color = Color(0xFFDDDDDD), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Small shared pieces
    // ──────────────────────────────────────────────────────────────────────

    @Composable
    private fun StepHeader(text: String) {
        Text("♟ Chess Readiness", color = Color(0xFFBB88FF), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(text, color = Color(0xFF888888), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(14.dp))
    }

    @Composable
    private fun AbandonButton(onAbandon: () -> Unit) {
        TextButton(onClick = onAbandon, modifier = Modifier.fillMaxWidth()) {
            Text("Abandon test", color = Color(0xFF777777), fontSize = 11.sp)
        }
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, color = Color(0xFFCCCCCC), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(6.dp))
    }

    @Composable
    private fun ChoiceChip(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
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
