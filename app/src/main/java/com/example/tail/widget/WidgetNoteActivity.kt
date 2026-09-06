package com.example.tail.widget

import android.appwidget.AppWidgetManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.example.tail.data.SettingsRepository
import com.example.tail.ui.theme.TailTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Transparent trampoline activity launched by the lock-screen habit widget's
 * sticky "✎ Add Note" bar. Shows a Compose note composer styled after the
 * in-app [com.example.tail.ui.TextInputDialog]; on confirm the note is
 * prepended to the markdown file configured for voice-note quick capture
 * (Settings → Voice Note dictation, `voiceNoteFileUri`), using the exact
 * same "## yyyy-MM-dd HH:mm:ss" entry format as [handleAsNote] in
 * SmartVoiceService, so dictated and typed notes land interleaved in one file.
 *
 * Cancelling the dialog finishes without touching anything.
 */
class WidgetNoteActivity : ComponentActivity() {

    companion object {
        const val ACTION_SHOW = "com.example.tail.widget.SHOW_NOTE"
        private const val TAG = "WidgetNoteActivity"
    }

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TailTheme(darkTheme = true) {
                NoteComposerDialog(
                    onConfirm = { text -> saveAndFinish(text) },
                    onDismiss = { finish() }
                )
            }
        }
    }

    @Composable
    private fun NoteComposerDialog(
        onConfirm: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        var noteText by remember { mutableStateOf("") }

        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                // ── Title ───────────────────────────────────────────────
                Text(
                    text = "✎ New Note",
                    color = Color(0xFFFFD700),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Saved to your notes file",
                    color = Color(0xFF888888),
                    fontSize = 11.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                // ── Text field ──────────────────────────────────────────
                BasicTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                    textStyle = TextStyle(
                        color = Color(0xFFEEEEEE),
                        fontSize = 14.sp
                    ),
                    cursorBrush = SolidColor(Color(0xFFFFD700)),
                    decorationBox = { innerField ->
                        if (noteText.isEmpty()) {
                            Text(
                                text = "Type your note…",
                                color = Color(0xFF666666),
                                fontSize = 14.sp
                            )
                        }
                        innerField()
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // ── Actions ─────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(text = "Cancel", color = Color(0xFF888888))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(
                        onClick = { if (noteText.isNotBlank()) onConfirm(noteText.trim()) },
                        enabled = noteText.isNotBlank()
                    ) {
                        Text(
                            text = "Save",
                            color = if (noteText.isNotBlank()) Color(0xFF66BB6A) else Color(0xFF555555),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }

    private fun saveAndFinish(noteText: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepo.settingsFlow.first()
                val uriStr = settings.voiceNoteFileUri
                if (uriStr.isEmpty()) {
                    Toast.makeText(
                        applicationContext,
                        "🧠 No notes file selected",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@launch
                }

                val uri = Uri.parse(uriStr)
                val now = LocalDateTime.now()
                val timestamp = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                val newEntry = "## $timestamp (widget)\n$noteText\n\n"

                // Read existing content (missing/unreadable file → start fresh)
                val existingContent = try {
                    applicationContext.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.bufferedReader().readText()
                    } ?: ""
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read existing notes file (may be new): ${e.message}")
                    ""
                }

                // Prepend new entry above existing content (matches SmartVoiceService)
                applicationContext.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.bufferedWriter().use { writer ->
                        writer.write(newEntry)
                        writer.write(existingContent)
                    }
                }

                Log.i(TAG, "Note prepended from lock-screen widget: \"$noteText\"")

                // Mark originating widget as recently used so it re-renders fresh.
                val widgetId = intent?.getIntExtra(
                    AppWidgetManager.EXTRA_APPWIDGET_ID,
                    AppWidgetManager.INVALID_APPWIDGET_ID
                ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
                if (widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
                    HabitListWidgetProvider.refreshAll(applicationContext)
                }

                runOnUiThread {
                    Toast.makeText(applicationContext, "📝 Note saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write note: ${e.message}", e)
                runOnUiThread {
                    Toast.makeText(
                        applicationContext,
                        "🧠 Error saving note: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                }
            }
        }
    }
}
