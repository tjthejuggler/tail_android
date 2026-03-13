package com.example.tail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.lifecycleScope
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.ui.theme.TailTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Transparent trampoline activity that appears in the system share sheet.
 *
 * When the user highlights text anywhere on the phone and taps "Share → tail",
 * Android delivers an ACTION_SEND / text/plain intent here.
 *
 * The activity:
 *  1. Reads the shared text from the intent.
 *  2. Loads the list of habits that have "text input" enabled (from DataStore).
 *  3. Shows a dialog listing those habits.
 *  4. When the user picks one, appends the shared text to that habit's log file
 *     and also increments the habit count by 1 (same as tapping the habit in-app).
 *  5. Finishes immediately after saving (or on cancel).
 *
 * The activity uses a transparent theme so only the dialog is visible — it looks
 * like a floating sheet rather than a full-screen activity.
 */
class ShareTextActivity : ComponentActivity() {

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val textInputRepo by lazy { TextInputRepository() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Extract the shared text from the incoming intent
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
                ShareHabitPickerDialog(
                    sharedText = sharedText,
                    onHabitSelected = { habitName ->
                        saveEntryAndFinish(habitName, sharedText)
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    /**
     * Loads the text-input habits from settings, then shows the picker dialog.
     * Handles the async loading with a loading state.
     */
    @Composable
    private fun ShareHabitPickerDialog(
        sharedText: String,
        onHabitSelected: (String) -> Unit,
        onDismiss: () -> Unit
    ) {
        // null = still loading, empty list = loaded but no text-input habits configured
        var textInputHabits by remember { mutableStateOf<List<String>?>(null) }

        LaunchedEffect(Unit) {
            val settings = settingsRepo.settingsFlow.first()
            // Collect only habits that have BOTH text input enabled AND a file URI set.
            // Without a file URI the entry can't be saved, so we filter those out.
            val eligible = settings.textInputHabits
                .filter { settings.textInputFileUris.containsKey(it) }
                .sorted()
            textInputHabits = eligible
        }

        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                // ── Title ──────────────────────────────────────────────────────
                Text(
                    text = "Save to habit",
                    color = Color(0xFFFFD700),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ── Preview of the shared text ─────────────────────────────────
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

                when {
                    // ── Loading ────────────────────────────────────────────────
                    textInputHabits == null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFFFFAA00))
                        }
                    }

                    // ── No text-input habits configured ────────────────────────
                    textInputHabits!!.isEmpty() -> {
                        Text(
                            text = "No habits have Text Input enabled with a log file set.\n\n" +
                                    "To set one up: open Tail → tap ✏ Edit → select a habit → " +
                                    "enable \"Text input\" → link a log file.",
                            color = Color(0xFFFF8844),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            Button(
                                onClick = onDismiss,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3A2A00))
                            ) {
                                Text("OK", color = Color(0xFFFFAA00))
                            }
                        }
                    }

                    // ── Habit list ─────────────────────────────────────────────
                    else -> {
                        Text(
                            text = "Choose a habit:",
                            color = Color(0xFF888888),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 300.dp)
                                .background(Color(0xFF111111), RoundedCornerShape(6.dp))
                        ) {
                            LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                                items(textInputHabits!!) { habitName ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) { onHabitSelected(habitName) }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = habitName,
                                            color = Color(0xFFCCCCCC),
                                            fontSize = 14.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "→",
                                            color = Color(0xFF555555),
                                            fontSize = 14.sp
                                        )
                                    }
                                    HorizontalDivider(
                                        color = Color(0xFF2A2A2A),
                                        thickness = 0.5.dp
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End
                        ) {
                            TextButton(onClick = onDismiss) {
                                Text("Cancel", color = Color(0xFF888888))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Saves the shared text as a text entry for [habitName], then also increments
     * the habit count by 1 in the phone DB (same behaviour as tapping the habit in-app).
     * Finishes the activity when done.
     */
    private fun saveEntryAndFinish(habitName: String, text: String) {
        lifecycleScope.launch {
            try {
                val settings = settingsRepo.settingsFlow.first()

                // 1. Append to the text log file
                val logUriStr = settings.textInputFileUris[habitName]
                if (!logUriStr.isNullOrEmpty()) {
                    textInputRepo.appendTextEntry(Uri.parse(logUriStr), applicationContext, text)
                }

                // 2. Increment the habit count in the phone DB
                val phoneUriStr = settings.fileUri
                if (phoneUriStr.isNotEmpty()) {
                    val habitsRepo = com.example.tail.data.HabitsRepository()
                    val phoneUri = Uri.parse(phoneUriStr)
                    val db = habitsRepo.ensureDaysExist(phoneUri, applicationContext)
                    val updatedDb = habitsRepo.applyIncrementToDb(
                        db, habitName, 1,
                        java.time.LocalDate.now()
                    )
                    habitsRepo.persistDatabase(phoneUri, applicationContext, updatedDb)
                }

                Toast.makeText(
                    applicationContext,
                    "Saved to \"$habitName\"",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    applicationContext,
                    "Failed to save: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                finish()
            }
        }
    }
}
