package com.example.tail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.AppSettings

/**
 * Settings section for the AI Assistant (natural-language habit DB editing).
 *
 * Allows the user to configure:
 *  - LLM Base URL (OpenAI-compatible endpoint)
 *  - API Key (masked)
 *  - Model name
 *
 * Also hosts the safety-backup management: after the assistant executes a
 * confirmed plan, the pre-change state is kept here and can be restored or
 * discarded.
 *
 * Follows the same pattern as [MealSettingsSection].
 */
@Composable
fun AiAssistantSettingsSection(
    viewModel: HabitViewModel,
    settings: AppSettings
) {
    var baseUrl by remember(settings.aiAssistantBaseUrl) { mutableStateOf(settings.aiAssistantBaseUrl) }
    var apiKey by remember(settings.aiAssistantApiKey) { mutableStateOf(settings.aiAssistantApiKey) }
    var model by remember(settings.aiAssistantModel) { mutableStateOf(settings.aiAssistantModel) }
    var showApiKey by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val controller = viewModel.aiAssistant
    val backup by controller.backupInfo.collectAsState()
    val busy by controller.busy.collectAsState()

    fun save() {
        viewModel.saveAiAssistantSettings(baseUrl, apiKey, model)
    }

    Column {
        Text("🤖 AI Assistant", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Describe habit database changes in natural language; the assistant " +
                "proposes exact edits, you confirm, and a backup is saved before anything " +
                "is touched. Open the chat with the 🤖 button in the top bar.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Base URL
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text("https://api.z.ai/api/coding/paas/v4") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))

        // API Key
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API Key") },
            placeholder = { Text("sk-...") },
            singleLine = true,
            visualTransformation = if (showApiKey) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = showApiKey, onCheckedChange = { showApiKey = it })
            Spacer(modifier = Modifier.width(4.dp))
            Text("Show API key", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(modifier = Modifier.height(4.dp))

        // Model name
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model Name") },
            placeholder = { Text("glm-4.6") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row {
            Button(
                onClick = { save() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Save AI Settings", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    save()
                    testMessage = "Testing…"
                    controller.testConnection { ok, msg ->
                        testOk = ok
                        testMessage = msg
                    }
                },
                enabled = !busy
            ) {
                Text("Test", fontSize = 12.sp)
            }
        }

        if (testMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = testMessage,
                fontSize = 12.sp,
                color = if (testOk) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error
            )
        }

        // ── Safety backup management ─────────────────────────────────────
        Spacer(modifier = Modifier.height(12.dp))
        Text("Safety backup", fontSize = 13.sp, fontWeight = FontWeight.Bold)
        if (backup != null) {
            Text(
                text = "Saved ${backup!!.createdAtLabel} — state before the last executed " +
                    "change (${backup!!.opCount} operation(s)).\n\"${backup!!.description}\"",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row {
                Button(
                    onClick = { confirmRestore = true },
                    enabled = !busy,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Restore backup", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { controller.deleteBackup() },
                    enabled = !busy
                ) {
                    Text("Delete backup", fontSize = 12.sp)
                }
            }
            if (confirmRestore) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Restore the database to the state before the AI's last changes? " +
                        "Current data will be overwritten.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.error
                )
                Row {
                    Button(onClick = {
                        confirmRestore = false
                        controller.restoreBackup()
                    }) { Text("Yes, restore", fontSize = 12.sp) }
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(onClick = { confirmRestore = false }) {
                        Text("Cancel", fontSize = 12.sp)
                    }
                }
            }
        } else {
            Text(
                text = "No backup present. One is created automatically before every " +
                    "executed AI change.",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
