package com.example.tail.ui.grid

// Pill-log habit dialog ("Took pills"): count-coded dosing entry UI.
// One pill per line; line multiplicity = servings. Features:
//  - Likely-next suggestion strip (past groupings/lines, frequency +
//    time-of-day ranked) — one tap pre-fills the counts.
//  - Pills with pending servings float to the top; the rest alphabetical.
//  - Compact rows to fit more on screen.
//  - Add-new-pill inline (joins the persistent catalog).
//  - LONG-PRESS a pill row → edit its details (name + per-serving
//    composition), including 📷 AI-camera auto-fill from a photo of the
//    bottle/box (same assist as the text-input options editor).
//  - Non-blocking double-tap guard per spec item 3.

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.tail.data.PillDayDoses
import com.example.tail.data.PillLogParser
import com.example.tail.ui.viewmodel.PillSuggestion
import java.time.LocalDate

/** Quiet period before a pending pill floats to the top of the list. */
private const val REORDER_DELAY_MS = 700L

/** Format helper: 1050.0 → "1050", 0.6 → "0.6". */
private fun fmtAmount(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/**
 * @param catalogPills Known pill names (catalog), any order — the dialog
 *   sorts them (pending-first, then alphabetical).
 * @param suggestions Likely-next chips (see [com.example.tail.ui.viewmodel.loadPillSuggestions]).
 * @param onAddPill Called with (name, per-serving description) for a new catalog pill.
 * @param onUpdatePill Called with (oldName, newName, per-serving description)
 *   when a pill's details are edited (long-press → edit → save).
 * @param onGenerateDescription AI-camera assist: analyse [imageFile] + [instruction]
 *   into a per-serving description (photo deleted after use).
 * @param onConfirm Receives the NEW lines to append (one per serving).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PillLogDialog(
    habitName: String,
    date: LocalDate,
    catalogPills: List<String>,
    existingToday: PillDayDoses,
    catalog: Map<String, String>,
    suggestions: List<PillSuggestion> = emptyList(),
    onAddPill: (String, String) -> Unit = { _, _ -> },
    onUpdatePill: (String, String, String) -> Unit = { _, _, _ -> },
    onGenerateDescription: (pillName: String, instruction: String, imageFile: java.io.File, onResult: (String?) -> Unit) -> Unit = { _, _, _, _ -> },
    onConfirm: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    // Pending servings for THIS submission: pill/line name → count.
    val pending = remember { mutableStateMapOf<String, Int>() }
    // Float-to-top snapshot, applied only after a short quiet period: the
    // reorder "hesitates" so the pill just set to 1 can be tapped to 2, 3, 4…
    // before it jumps to the top of the list.
    val floatedSnapshot = remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(pending.toMap()) {
        kotlinx.coroutines.delay(REORDER_DELAY_MS)
        floatedSnapshot.value = pending.keys.filter { (pending[it] ?: 0) > 0 }
    }
    // Display order: floated pills first (in the order they were set), the
    // rest alphabetical. The snapshot (not the live counts) drives the
    // ordering, so rapid +/- taps keep rows in place.
    val orderedPills = remember(catalogPills, floatedSnapshot.value) {
        val set = floatedSnapshot.value.filter { it in catalogPills }
        val rest = catalogPills.filter { it !in set }.sortedBy { it.lowercase() }
        set + rest
    }
    val freeText = remember { mutableStateOf("") }
    // Inline add-new-pill fields
    var showAddPill by remember { mutableStateOf(false) }
    val newPillName = remember { mutableStateOf("") }
    val newPillDesc = remember { mutableStateOf("") }
    // Long-press → edit details
    var editPill by remember { mutableStateOf<String?>(null) }
    val totalPending = pending.values.sum()

    /** Repeated same-day servings are deliberate re-dosing — no confirmation. */
    fun addServing(pill: String) {
        pending[pill] = (pending[pill] ?: 0) + 1
    }

    /** Applies a suggestion's counts on top of anything already pending. */
    fun applySuggestion(s: PillSuggestion) {
        for ((line, count) in s.counts) {
            pending[line] = (pending[line] ?: 0) + count
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = habitName, fontSize = 16.sp)
                Text(
                    text = "One line = one serving · repeats re-dose · long-press a pill to edit",
                    fontSize = 10.sp, color = Color(0xFF888888)
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                // ── Suggestion strip: likely-next groupings, one tap pre-fills ──
                if (suggestions.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        suggestions.forEach { s ->
                            Surface(
                                color = Color(0xFF1E2A38),
                                shape = MaterialTheme.shapes.small,
                                onClick = { applySuggestion(s) }
                            ) {
                                // Up to 5 lines visible; internal vertical scroll
                                // beyond that (one entry per line for scanability).
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 8.dp, vertical = 5.dp)
                                        .then(
                                            if (s.counts.size > 5)
                                                Modifier
                                                    .heightIn(max = 62.dp)
                                                    .verticalScroll(rememberScrollState())
                                            else Modifier
                                        )
                                ) {
                                    s.counts.entries.forEach { (name, count) ->
                                        Text(
                                            text = if (count > 1) "$name ×$count" else name,
                                            fontSize = 10.sp,
                                            lineHeight = 12.sp,
                                            color = Color(0xFF88CCFF),
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // Existing servings today (context)
                if (existingToday.servings.isNotEmpty()) {
                    Text(
                        text = "Logged ${date}: " + existingToday.servings.entries
                            .joinToString(", ") { "${it.key} ×${it.value}" },
                        fontSize = 10.sp, color = Color(0xFF88FFCC),
                        maxLines = 2
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ── Compact pill rows ─────────────────────────────────────────
                orderedPills.forEach { pill ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                onClick = { addServing(pill) },
                                onLongClick = { editPill = pill }
                            ),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pill,
                                fontSize = 11.sp,
                                color = if ((pending[pill] ?: 0) > 0) Color(0xFFBB88FF) else Color(0xFFDDDDDD),
                                maxLines = 1
                            )
                            val extra = pending[pill] ?: 0
                            if (extra > 0) {
                                val perServing = PillLogParser.parseDescription(catalog[pill] ?: "")
                                val preview = perServing.joinToString(", ") {
                                    "${it.name} ${fmtAmount(it.amount * extra)}${it.unit}"
                                }
                                if (preview.isNotBlank()) {
                                    Text(text = preview, fontSize = 9.sp, color = Color(0xFF666666), maxLines = 1)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TextButton(
                                onClick = {
                                    val c = pending[pill] ?: 0
                                    if (c == 1) pending.remove(pill) else if (c > 0) pending[pill] = c - 1
                                },
                                enabled = (pending[pill] ?: 0) > 0,
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.height(26.dp)
                            ) { Text("−", fontSize = 14.sp) }
                            Text(
                                text = "${pending[pill] ?: 0}",
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 3.dp)
                            )
                            TextButton(
                                onClick = { addServing(pill) },
                                contentPadding = PaddingValues(horizontal = 4.dp),
                                modifier = Modifier.height(26.dp)
                            ) { Text("+", fontSize = 14.sp) }
                        }
                    }
                }

                // ── Add new pill ──────────────────────────────────────────────
                if (showAddPill) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newPillName.value,
                            onValueChange = { newPillName.value = it },
                            label = { Text("Pill name", fontSize = 9.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(fontSize = 12.sp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        OutlinedTextField(
                            value = newPillDesc.value,
                            onValueChange = { newPillDesc.value = it },
                            label = { Text("Per-serving (opt.)", fontSize = 9.sp) },
                            placeholder = { Text("e.g. Magnesium 100mg", fontSize = 9.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            textStyle = TextStyle(fontSize = 12.sp)
                        )
                    }
                    Row {
                        TextButton(onClick = {
                            val name = newPillName.value.trim()
                            if (name.isNotEmpty()) {
                                onAddPill(name, newPillDesc.value.trim())
                                pending[name] = (pending[name] ?: 0) + 1
                            }
                            newPillName.value = ""
                            newPillDesc.value = ""
                            showAddPill = false
                        }) { Text("Add", fontSize = 11.sp, color = Color(0xFF88FFCC)) }
                        TextButton(onClick = { showAddPill = false }) {
                            Text("Cancel", fontSize = 11.sp)
                        }
                    }
                } else {
                    TextButton(
                        onClick = { showAddPill = true },
                        contentPadding = PaddingValues(horizontal = 4.dp),
                        modifier = Modifier.height(26.dp)
                    ) { Text("＋ New pill", fontSize = 11.sp, color = Color(0xFF88FFCC)) }
                }

                // ── Free-form line entry (arbitrary pill / typed dose tokens) ──
                OutlinedTextField(
                    value = freeText.value,
                    onValueChange = { freeText.value = it },
                    label = { Text("Other (one line)", fontSize = 10.sp) },
                    placeholder = { Text("e.g. Creatine 5g", fontSize = 10.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        val line = freeText.value.trim()
                        if (line.isNotEmpty()) {
                            pending[line] = (pending[line] ?: 0) + 1
                            freeText.value = ""
                        }
                    }),
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp)
                )

                if (totalPending > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Adding: $totalPending serving(s)",
                        fontSize = 11.sp, color = Color(0xFF44AAFF)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val lines = pending.entries
                        .sortedBy { it.key }
                        .flatMap { (pill, count) -> List(count) { pill } }
                    if (lines.isNotEmpty()) onConfirm(lines)
                },
                enabled = totalPending > 0
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    // ── Long-press editor: rename (user pills) + per-serving details + AI camera ──
    editPill?.let { pill ->
        PillEditDialog(
            pillName = pill,
            description = catalog[pill] ?: "",
            isDefaultPill = pill in PillLogParser.DEFAULT_CATALOG,
            onGenerateDescription = onGenerateDescription,
            onSave = { newName, desc ->
                onUpdatePill(pill, newName, desc)
                editPill = null
            },
            onDismiss = { editPill = null }
        )
    }
}

/**
 * Per-pill detail editor, opened by long-pressing a pill row in
 * [PillLogDialog]. Mirrors the text-input options editor's AI-camera assist:
 * photograph the bottle/box, the vision model fills the per-serving
 * composition ("e.g. Magnesium 150mg, B6 2mg").
 */
@Composable
private fun PillEditDialog(
    pillName: String,
    description: String,
    isDefaultPill: Boolean,
    onGenerateDescription: (String, String, java.io.File, (String?) -> Unit) -> Unit,
    onSave: (newName: String, description: String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var editName by remember { mutableStateOf(pillName) }
    var editDesc by remember { mutableStateOf(description) }
    var aiInstruction by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiCaptureFile by remember { mutableStateOf<java.io.File?>(null) }

    val aiCameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val file = aiCaptureFile
        aiCaptureFile = null
        if (ok && file != null) {
            aiBusy = true
            aiError = null
            onGenerateDescription(pillName, aiInstruction, file) { result ->
                aiBusy = false
                if (result != null) editDesc = result
                else aiError = "AI generation failed — check LLM settings (Meals AI)"
            }
        }
    }
    fun aiFileUri(file: java.io.File) = androidx.core.content.FileProvider.getUriForFile(
        context, "com.example.tail.fileprovider", file
    )
    val aiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            aiCaptureFile?.let { aiCameraLauncher.launch(aiFileUri(it)) }
        } else aiError = "Camera permission denied"
    }
    fun launchAiCamera() {
        val file = java.io.File(
            context.filesDir,
            "meal_images/pill_ai_${java.util.UUID.randomUUID()}.jpg"
        ).apply { parentFile?.mkdirs() }
        aiCaptureFile = file
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) aiCameraLauncher.launch(aiFileUri(file))
        else aiPermissionLauncher.launch(Manifest.permission.CAMERA)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = "Edit pill", fontSize = 16.sp)
                Text(
                    text = if (isDefaultPill)
                        "Default catalog pill — details editable, name fixed"
                    else "Long-press edit · history is kept",
                    fontSize = 10.sp, color = Color(0xFF888888)
                )
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = editName,
                    onValueChange = { editName = it },
                    label = { Text("Pill name", fontSize = 9.sp) },
                    singleLine = true,
                    enabled = !isDefaultPill,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = editDesc,
                    onValueChange = { editDesc = it },
                    label = { Text("Per-serving composition", fontSize = 9.sp) },
                    placeholder = { Text("e.g. Magnesium 150mg, B6 2mg", fontSize = 9.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 12.sp)
                )

                // ── AI camera assist: photo the bottle/box → auto-fill details ──
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = aiInstruction,
                    onValueChange = { aiInstruction = it },
                    label = { Text("AI instruction (e.g. parse the label)", fontSize = 9.sp) },
                    singleLine = true,
                    enabled = !aiBusy,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontSize = 11.sp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = { launchAiCamera() },
                        enabled = !aiBusy,
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(
                            if (aiBusy) "Analyzing photo…" else "📷 AI camera",
                            fontSize = 11.sp,
                            color = if (aiBusy) Color(0xFF888888) else Color(0xFF88CCFF)
                        )
                    }
                    aiError?.let {
                        Text(text = it, fontSize = 9.sp, color = Color(0xFFFF6666), maxLines = 1)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(editName, editDesc) },
                enabled = editName.trim().isNotEmpty()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
