package com.example.tail.ui.settings

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.AppSettings
import com.example.tail.data.ai.LlmEndpoint
import com.example.tail.data.ai.LlmFallbackDefaults
import com.example.tail.data.ai.pingLlmEndpoint
import com.example.tail.ui.viewmodel.HabitViewModel
import com.example.tail.ui.viewmodel.saveLlmFallbackSettings
import kotlinx.coroutines.launch

/**
 * Settings section for the EMERGENCY BACKUP LLM.
 *
 * The backup endpoint is used by EVERY LLM feature in the app (AI Assistant
 * chat, meal photo parsing, meal text parsing, tandem teaching, option
 * descriptions, water-hardness lookup) — but ONLY after the primary endpoint
 * that feature is configured to use has actually failed (network error,
 * rate limit, 5xx, auth error, or an unusable answer). A healthy primary is
 * never bypassed, and the backup is never called in parallel.
 *
 * Defaults are pre-filled with the ppq.ai gateway:
 *   base URL `https://api.ppq.ai` → `POST https://api.ppq.ai/chat/completions`
 *   model    `deepseek-ai/DeepSeek-V4.1-Flash-1017`
 */
@Composable
fun LlmFallbackSettingsSection(
    viewModel: HabitViewModel,
    settings: AppSettings
) {
    var enabled by remember(settings.llmFallbackEnabled) {
        mutableStateOf(settings.llmFallbackEnabled)
    }
    var baseUrl by remember(settings.llmFallbackBaseUrl) {
        mutableStateOf(settings.llmFallbackBaseUrl)
    }
    var apiKey by remember(settings.llmFallbackApiKey) {
        mutableStateOf(settings.llmFallbackApiKey)
    }
    var model by remember(settings.llmFallbackModel) {
        mutableStateOf(settings.llmFallbackModel)
    }
    var showApiKey by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }
    var testOk by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun save() {
        viewModel.saveLlmFallbackSettings(enabled, baseUrl, apiKey, model)
    }

    Column {
        Text("🛟 Emergency Backup LLM", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Used by EVERY AI feature (assistant chat, meal photos, meal text, " +
                "teaching, option descriptions, water hardness) — but ONLY when the " +
                "primary endpoint for that feature is down, rate limited, or otherwise " +
                "failing. A working primary is never bypassed.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = { enabled = it; save() })
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (enabled) "Backup enabled" else "Backup disabled",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Base URL
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            placeholder = { Text(LlmFallbackDefaults.BASE_URL) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Endpoint used: ${LlmEndpoint(baseUrl, apiKey, model).chatCompletionsUrl()}",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
            Text(
                "Show API key",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(4.dp))

        // Model name
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model Name") },
            placeholder = { Text(LlmFallbackDefaults.MODEL) },
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
                Text("Save Backup LLM", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    save()
                    val endpoint = LlmEndpoint(baseUrl, apiKey, model)
                    if (!endpoint.isConfigured) {
                        testOk = false
                        testMessage = "Fill in Base URL, API Key and Model Name first."
                        return@OutlinedButton
                    }
                    testing = true
                    testMessage = "Testing…"
                    scope.launch {
                        try {
                            pingLlmEndpoint(endpoint)
                            testOk = true
                            testMessage = "✅ Backup endpoint replied — ${endpoint.label()}"
                        } catch (e: Exception) {
                            testOk = false
                            testMessage = "❌ ${e.message ?: e.javaClass.simpleName}"
                        } finally {
                            testing = false
                        }
                    }
                },
                enabled = !testing
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

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Tip: the backup only needs to be configured once — it is shared by " +
                "all AI features. Leave the API key empty to keep the backup inactive.",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
