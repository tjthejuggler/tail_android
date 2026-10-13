package com.example.tail

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.example.tail.data.SettingsRepository
import com.example.tail.ui.theme.TailTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

/**
 * Transparent trampoline activity for the paperclip_journals AI-takeover
 * timeline. Appears in the system share sheet as "📎 Paperclip" alongside
 * the existing "tail" target — the two never interfere.
 *
 * When the user shares text or a link from any app:
 *  1. Reads ACTION_SEND / text-* content from the intent.
 *  2. Detects a URL inside the shared text (browsers share links this way).
 *  3. Shows a confirm dialog with the detected URL / text preview.
 *  4. POSTs {url, text, shared_at} to TailBridge /api/v1/paperclip/submit
 *     via the shared BridgeClient (X-App-Auth, garmin proxy url/token creds).
 *  5. Toast + finish() — stateless, exactly like the other trampolines.
 */
class PaperclipShareActivity : ComponentActivity() {

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val bridgeClient by lazy { com.example.tail.data.BridgeClient() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedText: String? = when {
            intent?.action == Intent.ACTION_SEND &&
                    intent.type?.startsWith("text/") == true ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        }

        if (sharedText.isNullOrBlank()) {
            Toast.makeText(this, "No text to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            TailTheme(darkTheme = true) {
                PaperclipConfirmDialog(
                    sharedText = sharedText,
                    onConfirm = { submitToBridge(sharedText) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    /** Pulls the first URL out of the shared text, if any. */
    private fun extractUrl(text: String): String? {
        val regex = Regex("""https?://\S+""", RegexOption.IGNORE_CASE)
        return regex.find(text)?.value?.trimEnd(',', ')', '.', '>')
    }

    private fun submitToBridge(sharedText: String) {
        lifecycleScope.launch {
            val settings = settingsRepo.settingsFlow.first()
            // The stored proxy URL may point at the Garmin proxy (:8000);
            // the Tail Bridge with the paperclip endpoints listens on :8001.
            val baseUrl = settings.garminProxyUrl
                .trim().trimEnd('/')
                .replace(Regex(":8000$"), ":8001")
            val token = settings.garminAppToken

            if (baseUrl.isEmpty() || token.isEmpty()) {
                Toast.makeText(
                    this@PaperclipShareActivity,
                    "Bridge not configured (set proxy URL/token in settings)",
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }

            val url = extractUrl(sharedText)
            val body = JSONObject().apply {
                put("url", url ?: JSONObject.NULL)
                put("text", sharedText)
                put("shared_at", Instant.now().toString())
                put("source", "tail_android")
            }

            val response = bridgeClient.post(
                baseUrl, token, "paperclip/submit", body
            )

            if (response?.optBoolean("queued") == true) {
                Toast.makeText(
                    this@PaperclipShareActivity,
                    "Queued for Paperclip timeline ✓", Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@PaperclipShareActivity,
                    "Paperclip: bridge unreachable", Toast.LENGTH_LONG
                ).show()
            }
            finish()
        }
    }

    @Composable
    private fun PaperclipConfirmDialog(
        sharedText: String,
        onConfirm: () -> Unit,
        onDismiss: () -> Unit
    ) {
        var sending by remember { mutableStateOf(false) }
        val url = remember { extractUrl(sharedText) }

        // Safety net: if the caller forgets to finish after sending.
        LaunchedEffect(sending) { /* handled in submitToBridge */ }

        Dialog(onDismissRequest = { if (!sending) onDismiss() }) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text(
                    text = "📎 Paperclip Timeline",
                    color = Color(0xFFFFD700),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (url != null) {
                    Text(
                        text = "Article link:",
                        color = Color(0xFF888888), fontSize = 12.sp
                    )
                    Text(
                        text = url,
                        color = Color(0xFF66CCFF), fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Text(
                    text = sharedText,
                    color = Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Send to the AI-takeover timeline?",
                    color = Color(0xFFDDDDDD), fontSize = 13.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss, enabled = !sending) {
                        Text("Cancel", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.padding(4.dp))
                    Button(
                        onClick = {
                            sending = true
                            onConfirm()
                        },
                        enabled = !sending,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF3A2A00)
                        )
                    ) {
                        Text("Send 📎", color = Color(0xFFFFAA00))
                    }
                }
            }
        }
    }
}
