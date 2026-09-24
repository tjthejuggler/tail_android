package com.example.tail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import com.example.tail.data.environment.EnvironmentMetric
import com.example.tail.data.environment.waterHardnessClass
import com.example.tail.data.environment.ppmToGermanDegrees
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.fetchEnvironmentFullBacklog
import com.example.tail.ui.viewmodel.removeWaterMemory
import com.example.tail.ui.viewmodel.resyncEnvironmentHabits
import com.example.tail.ui.viewmodel.saveEnvironmentEnabled
import com.example.tail.ui.viewmodel.saveEnvironmentTemperatureUnit
import com.example.tail.ui.viewmodel.saveEnvironmentWaterMemoryEnabled

/**
 * Environment settings section.
 *
 * The user-facing explanation of the environment habit: what is recorded,
 * where the data comes from (all keyless free APIs), and the water-hardness
 * memory management. API keys are NOT needed — the section says so explicitly
 * so the user doesn't go hunting for a key field.
 */
@Composable
fun EnvironmentSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings,
    context: android.content.Context = androidx.compose.ui.platform.LocalContext.current
) {
    val envVersion by viewModel.envVersion.collectAsState()
    val envStatus by viewModel.envStatus.collectAsState()
    val loading by viewModel.envLoading.collectAsState()

    // Remembered water values refresh whenever a write happens.
    val waterMemory = remember(envVersion) {
        viewModel.environmentRepo.getAllWaterHardness()
    }

    Column(modifier = Modifier.fillMaxWidth()) {

        Text("Environment Habit", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Records a daily environment snapshot for the day's location: " +
                   "temperature, humidity, precipitation, UV index, pressure, wind, " +
                   "air quality (EU AQI, PM2.5/PM10, ozone, NO₂), pollen (Europe), " +
                   "geomagnetic activity (Kp) and tap-water hardness.\n\n" +
                   "Link a habit to a metric via long-press habit → SPECIAL HABIT " +
                   "TYPES → Environment: the habit's squares auto-populate with the " +
                   "metric value each day and its graph plots it like any other habit.",
            fontSize = 10.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(8.dp))

        // ── Master switch ────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Enable environment capture", fontSize = 13.sp)
            Switch(
                checked = settings.environmentEnabled,
                onCheckedChange = { viewModel.saveEnvironmentEnabled(it) }
            )
        }

        // ── Data sources (no keys!) ──────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Data sources", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            text = "· Weather + UV — Open-Meteo (forecast & archive APIs)\n" +
                   "· Air quality + pollen — Open-Meteo Air Quality API\n" +
                   "· Geomagnetic Kp — NOAA Space Weather Prediction Center\n" +
                   "· Water hardness — manual entry with per-location memory",
            fontSize = 10.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "✓ No API keys required — all network sources are free and " +
                   "keyless. Nothing to configure or sign up for.",
            fontSize = 10.sp,
            color = Color(0xFF81C784)
        )

        // ── History backlog: ONE status card + 4 actions ────────────────────
        val bgPrefs = remember {
            context.getSharedPreferences("tail_env_backlog", android.content.Context.MODE_PRIVATE)
        }
        // Poll prefs every 2 s so the background worker's progress is live.
        var bgTick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(2000)
                bgTick++
            }
        }
        val bgRunning = remember(bgTick) { bgPrefs.getBoolean("running", false) }
        val bgMessage = remember(bgTick) { bgPrefs.getString("lastMessage", "") ?: "" }

        Spacer(modifier = Modifier.height(8.dp))
        // One status card: shows the in-app run OR the background run — never both.
        val inAppActive = loading && envStatus.isNotEmpty()
        val statusText = when {
            inAppActive -> envStatus
            bgRunning -> "● Background run: " + bgMessage
            bgMessage.isNotEmpty() -> "Last background run: " + bgMessage
            else -> ""
        }
        if (statusText.isNotEmpty()) {
            Text(
                text = statusText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = when {
                    inAppActive || bgRunning -> Color(0xFF81C784)
                    else -> Color(0xFFAAAAAA)
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Row 1: in-app (watch it live)
        Row {
            Button(
                onClick = { viewModel.fetchEnvironmentFullBacklog(repair = false) },
                enabled = settings.environmentEnabled && !loading,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1B5E20))
            ) {
                Text(if (loading) "Working…" else "Resume history", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = { viewModel.fetchEnvironmentFullBacklog(repair = true) },
                enabled = settings.environmentEnabled && !loading
            ) {
                Text("Full redo", fontSize = 12.sp)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        // Row 2: background (survives app close)
        Row {
            OutlinedButton(
                onClick = {
                    com.example.tail.notify.EnvironmentBacklogWorker.schedule(context, false)
                },
                enabled = settings.environmentEnabled
            ) { Text("▶ Resume in background", fontSize = 11.sp) }
            Spacer(modifier = Modifier.width(6.dp))
            OutlinedButton(
                onClick = {
                    com.example.tail.notify.EnvironmentBacklogWorker.schedule(context, true)
                },
                enabled = settings.environmentEnabled
            ) { Text("↻ Redo in background", fontSize = 11.sp) }
        }
        Text(
            text = "One range request per ~month per place instead of per day — " +
                   "a multi-year history takes a couple of minutes, not hours. " +
                   "Resume continues where an interrupted run stopped; Full redo " +
                   "wipes and re-fetches everything. Background runs continue " +
                   "with the app closed (WorkManager; no battery cost at idle). " +
                   "Open-Meteo covers ~92 days back; NOAA Kp ~30.",
            fontSize = 9.sp,
            color = Color(0xFF666666)
        )
        if (envStatus.isNotEmpty() && !inAppActive && !bgRunning) {
            Text(
                text = "Last in-app run: " + envStatus,
                fontSize = 9.sp,
                color = Color(0xFF555555)
            )
        }

        // Repair: re-derive all linked squares from stored snapshots (no
        // network). Fixes squares written by older logic.
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = { viewModel.resyncEnvironmentHabits() }) {
            Text("Repair linked squares", fontSize = 12.sp)
        }
        Text(
            text = "Re-derives every environment-linked habit square from the " +
                   "stored snapshots without any network calls.",
            fontSize = 9.sp,
            color = Color(0xFF666666)
        )

        // ── Water hardness memory ────────────────────────────────────────
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Water hardness", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
            text = "Resolved automatically: when a day is captured at a new " +
                   "location, the app asks your AI Assistant LLM for that place's " +
                   "municipal tap-water hardness (mg/L CaCO₃) and remembers it — " +
                   "one lookup per place, then cached forever. Bands: <60 Soft · " +
                   "60–120 Moderately hard · 120–180 Hard · >180 Very hard. " +
                   "Configure the LLM in the AI Assistant settings; manual " +
                   "overrides can be set on any day via the habit's increment " +
                   "dialog.",
            fontSize = 10.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Temperature unit", fontSize = 12.sp)
            Row {
                OutlinedButton(
                    onClick = { viewModel.saveEnvironmentTemperatureUnit("C") },
                    colors = if (settings.environmentTemperatureUnit != "F") ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1B5E20)
                    ) else ButtonDefaults.outlinedButtonColors()
                ) { Text("°C", fontSize = 12.sp) }
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedButton(
                    onClick = { viewModel.saveEnvironmentTemperatureUnit("F") },
                    colors = if (settings.environmentTemperatureUnit == "F") ButtonDefaults.outlinedButtonColors(
                        containerColor = Color(0xFF1B5E20)
                    ) else ButtonDefaults.outlinedButtonColors()
                ) { Text("°F", fontSize = 12.sp) }
            }
        }
        Text(
            text = "Switching rewrites temperature-linked squares in the new " +
                   "unit (no network needed).",
            fontSize = 9.sp,
            color = Color(0xFF666666)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Auto-fill from memory", fontSize = 12.sp)
            Switch(
                checked = settings.environmentWaterMemoryEnabled,
                onCheckedChange = { viewModel.saveEnvironmentWaterMemoryEnabled(it) }
            )
        }

        // Remembered locations list
        if (waterMemory.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            waterMemory.toSortedMap().forEach { (label, ppm) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, fontSize = 11.sp, color = Color(0xFFCCCCCC))
                        Text(
                            "${ppm.toInt()} ppm · ${waterHardnessClass(ppm)} · " +
                                "%.1f °dH".format(ppmToGermanDegrees(ppm)),
                            fontSize = 9.sp,
                            color = Color(0xFF888888)
                        )
                    }
                    TextButton(onClick = { viewModel.removeWaterMemory(label) }) {
                        Text("Remove", fontSize = 10.sp, color = Color(0xFFEF5350))
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "No locations remembered yet. Set a hardness on any day " +
                       "via Map → location label → Environment and tick " +
                       "\"remember for this location\".",
                fontSize = 9.sp,
                color = Color(0xFF666666)
            )
        }
    }
}
