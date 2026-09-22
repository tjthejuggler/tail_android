package com.example.tail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.ai.AiChatMessage
import com.example.tail.data.ai.AiPlan

/**
 * The AI Assistant chat popup — opened via the 🤖 button next to the stats
 * icon at the top of the Settings screen.
 *
 * Conversation flow:
 *  1. The user types a request ("create standing sessions at the same times
 *     as my programming sessions today").
 *  2. The assistant inspects the DB with read-only tools and proposes a plan.
 *  3. The plan card shows EXACTLY what will change; the user confirms.
 *  4. A backup is taken, the plan is executed and the app reloads. The
 *     backup is restorable from Settings → Habit Features → AI Assistant.
 */
@Composable
fun AiAssistantDialog(
    viewModel: HabitViewModel,
    onDismiss: () -> Unit
) {
    val controller = viewModel.aiAssistant
    val messages by controller.messages.collectAsState()
    val busy by controller.busy.collectAsState()
    val pendingPlan by controller.pendingPlan.collectAsState()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Keep the transcript scrolled to the newest message.
    LaunchedEffect(messages.size, pendingPlan) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 420.dp, max = 620.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ── Header ────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.SmartToy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("AI Assistant", fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(modifier = Modifier.weight(1f))
                    if (busy) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.width(20.dp).height(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    // Clear the transcript AND the LLM conversation history.
                    IconButton(
                        onClick = { controller.clearConversation() },
                        enabled = !busy
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Clear conversation",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                // ── Transcript ────────────────────────────────────────────
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { msg ->
                        ChatBubble(msg)
                    }
                    if (pendingPlan != null) {
                        item { PlanCard(plan = pendingPlan!!, onConfirm = {
                            controller.confirmPlan()
                        }, onCancel = { controller.cancelPlan() }) }
                    }
                }

                // ── Input ─────────────────────────────────────────────────
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        placeholder = { Text("Describe the change…", fontSize = 13.sp) },
                        modifier = Modifier.weight(1f),
                        enabled = !busy && pendingPlan == null,
                        maxLines = 3
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = {
                            controller.sendMessage(input)
                            input = ""
                        },
                        enabled = !busy && pendingPlan == null && input.isNotBlank()
                    ) {
                        Icon(
                            Icons.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

/** One chat bubble — user right-aligned, assistant/error left-aligned. */
@Composable
private fun ChatBubble(msg: AiChatMessage) {
    val isUser = msg.role == "user"
    val isError = msg.role == "error"
    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 12.dp, topEnd = 12.dp,
                bottomStart = if (isUser) 12.dp else 3.dp,
                bottomEnd = if (isUser) 3.dp else 12.dp
            ),
            color = when {
                isUser -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                isError -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier.fillMaxWidth(0.92f)
        ) {
            Text(
                text = msg.content,
                fontSize = 13.sp,
                color = if (isError) MaterialTheme.colorScheme.onErrorContainer
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** Confirmation card for a proposed plan: description + exact operations. */
@Composable
private fun PlanCard(
    plan: AiPlan,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Proposed change — review carefully",
                fontSize = 13.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(plan.description, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Operations (${plan.operations.size}):", fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            plan.operations.take(12).forEach { op ->
                Text("• ${op.describe()}", fontSize = 11.sp)
            }
            if (plan.operations.size > 12) {
                Text("… and ${plan.operations.size - 12} more", fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                "A backup is saved before execution — restorable in Settings → " +
                    "Habit Features → AI Assistant.",
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary
                    )
                ) { Text("Confirm & apply", fontSize = 12.sp) }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onCancel) { Text("Cancel", fontSize = 12.sp) }
            }
        }
    }
}
