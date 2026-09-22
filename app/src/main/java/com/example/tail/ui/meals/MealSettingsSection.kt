package com.example.tail.ui

import androidx.compose.runtime.collectAsState

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.AppSettings

/**
 * Settings section for the Meal Habit Engine & LLM Configuration.
 *
 * Allows the user to configure:
 *  - Enable/disable the vision pipeline
 *  - LLM Base URL (OpenAI-compatible endpoint)
 *  - API Key (masked)
 *  - Model name (e.g. "gpt-4o")
 *  - Custom system prompt / dietary rules
 *
 * Follows the same pattern as [AiIconSettingsSection].
 */
@Composable
fun MealSettingsSection(
    viewModel: HabitViewModel,
    settings: AppSettings
) {
    var enabled by remember(settings.mealEnabled) { mutableStateOf(settings.mealEnabled) }
    var baseUrl by remember(settings.mealBaseUrl) { mutableStateOf(settings.mealBaseUrl) }
    var apiKey by remember(settings.mealApiKey) { mutableStateOf(settings.mealApiKey) }
    var model by remember(settings.mealModel) { mutableStateOf(settings.mealModel) }
    var systemPrompt by remember(settings.mealSystemPrompt) { mutableStateOf(settings.mealSystemPrompt) }
    var showApiKey by remember { mutableStateOf(false) }

    fun save() {
        viewModel.saveMealSettings(enabled, baseUrl, apiKey, model, systemPrompt)
    }

    Column {
        Text("🍽️ Meal Habit Engine", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text(
            text = "Vision-driven meal logging. Configure a multimodal LLM endpoint " +
                   "to analyse food photos and extract nutrition data automatically.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Enable Meal Engine", fontSize = 14.sp)
            Spacer(modifier = Modifier.weight(1f))
            Switch(
                checked = enabled,
                onCheckedChange = { newVal ->
                    enabled = newVal
                    save()
                }
            )
        }

        if (enabled) {
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
                placeholder = { Text("glm-4.6v (flash model often 429)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Custom system prompt / dietary rules
            Text("Custom Dietary Rules / System Prompt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            Text(
                text = "These rules are injected into every vision analysis request. " +
                       "e.g. \"Assume all food is strictly vegan unless stated otherwise.\"",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(4.dp))
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("Dietary rules (optional)") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
            )
            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = { save() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Save Meal Settings", fontSize = 12.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))

            // ── Test Vision Endpoint ───────────────────────────────────────
            val testState by viewModel.mealTestState.collectAsState()
            Button(
                onClick = {
                    save()
                    viewModel.testVisionEndpoint()
                },
                enabled = !testState.isTesting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text(
                    if (testState.isTesting) "Testing… 🍌" else "Test Vision (🍌 banana)",
                    fontSize = 12.sp
                )
            }

            if (testState.message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = testState.message,
                    fontSize = 12.sp,
                    color = if (testState.isSuccess) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                )
            }

            // Pending queue status
            val pendingCount by viewModel.mealPendingCount.collectAsState()
            if (pendingCount > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "📋 $pendingCount photo(s) queued for processing",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
    }
}
