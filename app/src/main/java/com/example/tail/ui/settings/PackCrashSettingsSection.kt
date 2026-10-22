package com.example.tail.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.crash.PackCrashReporter

/**
 * Crash Reporter settings — master switch for the Tail → Pack crash pipeline.
 *
 * When on, an uncaught exception anywhere in the app is packaged (stack trace,
 * thread, device/app info) and submitted to the Pack server as a fix job for
 * the Cline agent. If the phone can't reach the server at crash time, the
 * report is queued on disk and delivered first thing on the next launch,
 * before anything else runs. A notification confirms each delivered report.
 */
@Composable
fun PackCrashSettingsSection() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(PackCrashReporter.isEnabled(context)) }
    var server by remember { mutableStateOf(PackCrashReporter.getServer(context)) }
    var token by remember { mutableStateOf(PackCrashReporter.getToken(context)) }
    var project by remember { mutableStateOf(PackCrashReporter.getProject(context)) }
    var testResult by remember { mutableStateOf<String?>(null) }
    val pending = PackCrashReporter.pendingCount(context)

    Column {
        Text("🐺 Crash Reporter → Pack", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "When Tail crashes, automatically package the error and send " +
                "it to the Pack server as a fix job for the Cline agent. If the " +
                "server can't be reached at crash time, the report is delivered " +
                "first thing on the next launch — before anything else runs. " +
                "A notification confirms each delivered report.",
            fontSize = 11.sp,
            color = Color(0xFF888888)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Send crash reports to Pack", fontSize = 14.sp)
            Switch(
                checked = enabled,
                onCheckedChange = {
                    enabled = it
                    PackCrashReporter.setEnabled(context, it)
                }
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = server,
                onValueChange = { server = it; PackCrashReporter.setServer(context, it) },
                label = { Text("Pack server URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it; PackCrashReporter.setToken(context, it) },
                label = { Text("Auth token (X-Auth-Token)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = project,
                onValueChange = { project = it; PackCrashReporter.setProject(context, it) },
                label = { Text("Pack project name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = {
                    testResult = "Sending…"
                    PackCrashReporter.sendTestReport(context) { result ->
                        testResult = result.fold(
                            onSuccess = { "✓ Test report delivered — job $it" },
                            onFailure = { "✗ ${it.message ?: "failed"}" }
                        )
                    }
                }) { Text("Send test report") }
                if (pending > 0) {
                    Text(
                        "$pending pending report(s) — will send on next launch",
                        fontSize = 11.sp,
                        color = Color(0xFFCCC566)
                    )
                }
            }
            testResult?.let {
                Spacer(modifier = Modifier.height(4.dp))
                Text(it, fontSize = 12.sp)
            }
        }
    }
}
