package com.example.tail.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.phoneusage.PhoneUsageMetric
import com.example.tail.data.phoneusage.formatMinutesAsClock
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.fetchPhoneUsageBackfill
import com.example.tail.ui.viewmodel.savePhoneUsageEnabled

/**
 * Phone Usage settings section.
 *
 * Master switch for the phone-usage habit family (UsageStats-derived daily
 * metrics), the "Usage access" special-permission grant button, and an
 * explanation of the limited (~7-day) event history that motivates the
 * frequent background aggregation.
 */
@Composable
fun PhoneUsageSettingsSection(
    viewModel: HabitViewModel,
    settings: com.example.tail.data.AppSettings
) {
    val context = LocalContext.current
    val puVersion by viewModel.puVersion.collectAsState()
    val hasAccess = viewModel.phoneUsageRepo.hasUsageAccess()
    val snapshot = viewModel.puSnapshot.collectAsState().value
    val backfilling by viewModel.puBackfilling.collectAsState()
    val backfillStatus by viewModel.puStatus.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {

        Text("Phone Usage Habit", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Records a daily on-device phone-usage snapshot from Android " +
                "UsageStats: first unlock minute, evening last usage, total screen " +
                "minutes, unlock count, median session, long sessions (20 min+), " +
                "pre-sleep and night screen minutes.\n\n" +
                "Link a habit to a metric via long-press habit → SPECIAL HABIT " +
                "TYPES → Phone Usage: the habit's squares auto-populate with the " +
                "metric each day and its graph plots it like any other habit.",
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
            Text("Enable phone usage capture", fontSize = 13.sp)
            Switch(
                checked = settings.phoneUsageEnabled,
                onCheckedChange = { viewModel.savePhoneUsageEnabled(it) }
            )
        }

        // ── Usage access permission ─────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))

        if (!hasAccess) {
            Text(
                "⚠ Usage access permission missing — capture is inactive until granted.",
                fontSize = 11.sp,
                color = Color(0xFFB06000)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1B5E20),
                    contentColor = Color(0xFFDDDDDD)
                )
            ) {
                Text("Grant Usage access", fontSize = 11.sp)
            }
        } else {
            Text(
                "✓ Usage access granted. Aggregation runs on app open and every " +
                    "~4 hours in the background.",
                fontSize = 10.sp,
                color = Color(0xFF81C784)
            )
        }

        Text(
            text = "Android keeps only a limited UsageStats event history (~7 days), " +
                "so days are aggregated frequently; first setup retro-captures " +
                "whatever history remains (flagged retro). All raw usage data " +
                "stays on-device — only daily aggregates are stored.",
            fontSize = 10.sp,
            color = Color(0xFF888888)
        )

        // ── Today's snapshot preview ────────────────────────────────────
        key(puVersion) { snapshot?.let { snap ->
            Spacer(modifier = Modifier.height(6.dp))
            Text("Selected day (${snap.date})", fontWeight = FontWeight.Bold, fontSize = 11.sp)
            val rows = listOf(
                "First unlock" to snap.firstUnlockMinute?.let { formatMinutesAsClock(it) },
                "Last usage" to snap.lastUsageMinute?.let { formatMinutesAsClock(it) },
                "Total screen" to snap.totalScreenMinutes?.let { "$it min" },
                "Unlocks" to snap.unlockCount?.toString(),
                "Median session" to snap.medianSessionTenths?.let { "%.1f min".format(it / 10.0) },
                "Long sessions" to snap.longSessions?.toString(),
                "Pre-sleep screen" to snap.preSleepMinutes?.let { "$it min" + if (snap.preSleepIsFallback) " (fallback)" else "" },
                "Night screen" to snap.nightScreenMinutes?.let { "$it min" },
                "First app" to snap.firstApp.ifEmpty { null },
                "Top categories" to snap.top3Categories.ifEmpty { null }
            )
            for ((label, value) in rows) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(label, fontSize = 10.sp, color = Color(0xFF888888))
                    Text(value ?: "—", fontSize = 10.sp, color = Color(0xFFDDDDDD))
                }
            }
            if (snap.retro) {
                Text("(retro-captured)", fontSize = 9.sp, color = Color(0xFF888888))
            }
        } }

        // ── Backfill last 7 days ─────────────────────────────────────────
        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider(color = Color(0xFF333333), thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { viewModel.fetchPhoneUsageBackfill(days = 7) },
            enabled = hasAccess && settings.phoneUsageEnabled && !backfilling,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF1B5E20),
                contentColor = Color(0xFFDDDDDD)
            )
        ) {
            Text(
                if (backfilling) "Backfilling…" else "Backfill last 7 days",
                fontSize = 11.sp
            )
        }
        if (backfillStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(backfillStatus, fontSize = 10.sp, color = Color(0xFF888888))
        }

        // Linked habits summary
        if (settings.phoneUsageHabitMetrics.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Linked habits: ${settings.phoneUsageHabitMetrics.size}",
                fontWeight = FontWeight.Bold, fontSize = 11.sp
            )
            for ((habit, key) in settings.phoneUsageHabitMetrics) {
                val metric = PhoneUsageMetric.fromKey(key)
                Text(
                    "· $habit → ${metric?.label ?: key}",
                    fontSize = 10.sp, color = Color(0xFF888888)
                )
            }
        }
    }
}
