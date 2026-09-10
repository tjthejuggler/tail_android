package com.example.tail

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import com.example.tail.data.SettingsRepository
import com.example.tail.data.TextInputRepository
import com.example.tail.data.assist.AssistActionRepository
import com.example.tail.ui.theme.TailTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Quick Capture share target for IMAGES.
 *
 * When the user shares an image (screenshot, gallery photo, poster…) from
 * anywhere on the phone and picks "Quick Capture" in the system share
 * sheet, Android delivers an ACTION_SEND image intent here.
 *
 * The transparent dialog first offers the three destinations:
 *  - 📝 **Note** — saves the image next to the notes markdown file
 *    (subfolder `note_images/`) and prepends a markdown entry linking it.
 *  - 🧠 **Habit** — same flow as ShareTextActivity: pick a sharable
 *    text-input habit, log an entry, increment the habit.
 *  - 💻 **Action** — opens a sub-selection of the action types configured
 *    in Settings → Quick Capture → Actions. The chosen type's description
 *    is sent VERBATIM together with the image to the PC
 *    quick_capture_assist instance, which performs whatever the
 *    instruction says (e.g. "Wanted movies" → film_organizer API).
 */
class ShareImageActivity : ComponentActivity() {

    private val settingsRepo by lazy { SettingsRepository(applicationContext) }
    private val textInputRepo by lazy { TextInputRepository() }

    /** Shared image bytes, loaded once on create (null while loading). */
    private var imageBytes: ByteArray? = null
    private var imageExt: String = "jpg"
    private var imageLoaded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val imageUri: Uri? = when {
            intent?.action == Intent.ACTION_SEND &&
                    intent.type?.startsWith("image/") == true ->
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }

        if (imageUri == null) {
            Toast.makeText(this, "No image to share", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        imageExt = intent.type?.substringAfter('/')?.takeIf { it.isNotBlank() } ?: "jpg"
        if (imageExt == "jpeg") imageExt = "jpg"

        lifecycleScope.launch(Dispatchers.IO) {
            imageBytes = try {
                contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
            } catch (e: Exception) {
                null
            }
            imageLoaded = true
        }

        setContent {
            TailTheme(darkTheme = true) {
                ShareImageDialog(onDismiss = { finish() })
            }
        }
    }

    private enum class Page { MENU, HABITS, ACTIONS, SENDING }

    @Composable
    private fun ShareImageDialog(onDismiss: () -> Unit) {
        var page by remember { mutableStateOf(Page.MENU) }
        var sharableHabits by remember { mutableStateOf<List<String>?>(null) }
        var actionTypes by remember { mutableStateOf<List<com.example.tail.data.QuickCaptureActionType>>(emptyList()) }

        LaunchedEffect(Unit) {
            val s = settingsRepo.settingsFlow.first()
            sharableHabits = s.textInputHabits
                .filter { it in s.sharableTextHabits }
                .filter { s.textInputFileUris.containsKey(it) }
                .sorted()
            actionTypes = s.quickCaptureActionTypes
        }

        Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .background(Color(0xFF1E1E1E), RoundedCornerShape(12.dp))
                    .padding(20.dp)
            ) {
                Text(
                    text = when (page) {
                        Page.MENU -> "📸 Quick Capture"
                        Page.HABITS -> "Save to habit"
                        Page.ACTIONS -> "💻 PC Action — choose type"
                        Page.SENDING -> "Sending…"
                    },
                    color = Color(0xFFFFD700),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                when (page) {
                    Page.MENU -> {
                        Text(
                            text = "Shared image — where should it go?",
                            color = Color(0xFF888888),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        MenuRow("📝", "Note", "Save image + entry to notes file") { saveAsNote() }
                        MenuRow("🧠", "Habit", "Log to a sharable text-input habit") { page = Page.HABITS }
                        MenuRow("💻", "Action", "Send to PC quick capture assist") { page = Page.ACTIONS }
                    }

                    Page.HABITS -> {
                        when {
                            sharableHabits == null -> Loading()
                            sharableHabits!!.isEmpty() -> EmptyHint(
                                "No habits are sharable yet.\n\nOpen Tail → ✏ Edit → select a " +
                                        "text-input habit → enable \"Sharable\" → link a text log file."
                            )
                            else -> SelectList(sharableHabits!!) { index ->
                                page = Page.SENDING
                                sharableHabits?.getOrNull(index)?.let { saveAsHabitEntry(it) }
                            }
                        }
                    }

                    Page.ACTIONS -> {
                        when {
                            !imageLoaded -> Loading()
                            imageBytes == null -> EmptyHint("Could not read the shared image.")
                            actionTypes.isEmpty() -> EmptyHint(
                                "No action types configured.\n\nCreate them in Tail → Settings → " +
                                        "Quick Capture → Actions (e.g. \"Wanted movies\")."
                            )
                            else -> SelectList(actionTypes.map { it.name }, subtitle = { i ->
                                actionTypes.getOrNull(i)?.description
                            }) { index ->
                                val type = actionTypes[index]
                                page = Page.SENDING
                                sendAsPcAction(type)
                            }
                        }
                    }

                    Page.SENDING -> Loading()
                }

                Spacer(modifier = Modifier.height(12.dp))
                if (page != Page.SENDING) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = {
                            if (page == Page.MENU) onDismiss() else page = Page.MENU
                        }) {
                            Text(
                                text = if (page == Page.MENU) "Cancel" else "Back",
                                color = Color(0xFF888888)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun MenuRow(emoji: String, title: String, subtitle: String, onClick: () -> Unit) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .background(Color(0xFF111111), RoundedCornerShape(8.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text("$emoji  $title", color = Color(0xFFCCCCCC), fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = Color(0xFF777777), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }

    @Composable
    private fun Loading() {
        Box(
            modifier = Modifier.fillMaxWidth().height(60.dp),
            contentAlignment = Alignment.Center
        ) { CircularProgressIndicator(color = Color(0xFFFFAA00)) }
    }

    @Composable
    private fun EmptyHint(text: String) {
        Text(text = text, color = Color(0xFFFF8844), fontSize = 12.sp)
    }

    @Composable
    private fun SelectList(
        entries: List<String>,
        subtitle: ((Int) -> String?)? = null,
        onPick: (Int) -> Unit
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 320.dp)
                .background(Color(0xFF111111), RoundedCornerShape(6.dp))
        ) {
            LazyColumn(modifier = Modifier.padding(vertical = 4.dp)) {
                items(entries.size) { i ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(i) }
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Text(entries[i], color = Color(0xFFCCCCCC), fontSize = 14.sp)
                        subtitle?.invoke(i)?.let {
                            Text(
                                it,
                                color = Color(0xFF777777),
                                fontSize = 11.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider(color = Color(0xFF2A2A2A), thickness = 0.5.dp)
                }
            }
        }
    }

    // ── Destination: Note ────────────────────────────────────────────────

    /**
     * Saves the image next to the notes markdown file (best effort) and
     * prepends a markdown entry linking it. Falls back to a text-only
     * entry when the image can't be placed beside the notes file.
     */
    private fun saveAsNote() {
        lifecycleScope.launch(Dispatchers.IO) {
            var message = "Saved as note"
            try {
                val settings = settingsRepo.settingsFlow.first()
                if (settings.voiceNoteFileUri.isEmpty()) {
                    message = "No notes file configured (Settings → Voice Note Dictation)"
                } else {
                    val noteUri = Uri.parse(settings.voiceNoteFileUri)
                    val bytes = imageBytes

                    // Best effort: place the image in note_images/ beside the notes file
                    var imageRef: String? = null
                    if (bytes != null) {
                        imageRef = saveImageBesideNotes(noteUri, bytes)
                    }

                    val timestamp = LocalDateTime.now()
                        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                    val newEntry = buildString {
                        append("## $timestamp\n")
                        append(imageRef?.let { "![shared image]($it)\n" } ?: "📷 [shared image — file could not be saved]\n")
                        append("\n")
                    }

                    val existing = try {
                        contentResolver.openInputStream(noteUri)?.use {
                            it.bufferedReader().readText()
                        } ?: ""
                    } catch (_: Exception) {
                        ""
                    }
                    contentResolver.openOutputStream(noteUri, "wt")?.use { os ->
                        os.bufferedWriter().use { w ->
                            w.write(newEntry)
                            w.write(existing)
                        }
                    }
                }
            } catch (e: Exception) {
                message = "Failed to save note: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    /**
     * Tries to create `note_images/<name>.jpg` in the same SAF folder as the
     * notes file. Works when the notes file is a plain document whose parent
     * is resolvable via DocumentFile; returns the relative markdown ref, or
     * null when placement failed.
     */
    private fun saveImageBesideNotes(noteUri: Uri, bytes: ByteArray): String? {
        return try {
            val noteDoc = DocumentFile.fromSingleUri(this, noteUri)
            val parent = noteDoc?.parentFile
            val name = "note_image_${System.currentTimeMillis()}.$imageExt"
            val dir = parent?.findFile("note_images")
                ?: parent?.createDirectory("note_images")
                ?: return null
            val file = dir.createFile("image/$imageExt", name) ?: return null
            contentResolver.openOutputStream(file.uri)?.use { os ->
                os.write(bytes)
                os.flush()
            } ?: return null
            "note_images/$name"
        } catch (_: Exception) {
            null
        }
    }

    // ── Destination: Habit ───────────────────────────────────────────────

    /** Mirrors ShareTextActivity: log entry + habit increment, then finish. */
    private fun saveAsHabitEntry(habitName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val settings = settingsRepo.settingsFlow.first()
                val logUriStr = settings.textInputFileUris[habitName]
                if (!logUriStr.isNullOrEmpty()) {
                    textInputRepo.appendTextEntry(
                        Uri.parse(logUriStr),
                        applicationContext,
                        "📷 [shared image]",
                        habitName = habitName
                    )
                }
                val phoneUriStr = settings.fileUri
                if (phoneUriStr.isNotEmpty()) {
                    val habitsRepo = com.example.tail.data.HabitsRepository()
                    val phoneUri = Uri.parse(phoneUriStr)
                    val db = habitsRepo.ensureDaysExist(phoneUri, applicationContext)
                    val updatedDb = habitsRepo.applyIncrementToDb(
                        db, habitName, 1, java.time.LocalDate.now()
                    )
                    habitsRepo.persistDatabase(phoneUri, applicationContext, updatedDb)
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Saved to \"$habitName\"", Toast.LENGTH_SHORT).show()
                    finish()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(applicationContext, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }
    }

    // ── Destination: PC Action ───────────────────────────────────────────

    /**
     * Sends the image + the action type's instruction text to the PC
     * quick_capture_assist instance (bridge first, SAF/syncthing fallback).
     */
    private fun sendAsPcAction(type: com.example.tail.data.QuickCaptureActionType) {
        val bytes = imageBytes
        if (bytes == null) {
            Toast.makeText(this, "Could not read the shared image", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val instruction = buildString {
            append("[Quick Capture ACTION — shared image from phone]\n")
            append("Action type: ${type.name}\n")
            append("Instruction: ${type.description}\n")
            append("The image is attached (see the \"image\" field of this task).")
        }
        lifecycleScope.launch(Dispatchers.IO) {
            AssistActionRepository.submitWithImage(applicationContext, instruction, bytes, imageExt)
            withContext(Dispatchers.Main) {
                Toast.makeText(applicationContext, "💻→ Sent to PC: ${type.name}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}
