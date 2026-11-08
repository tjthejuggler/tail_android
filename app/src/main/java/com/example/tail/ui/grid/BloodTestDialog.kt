package com.example.tail.ui.grid

// Blood-test habit dialog: structured lab-item entry.
//  - MANUAL tab: one row per item ever measured (name + unit pre-filled,
//    blank value) — fill in what was tested, add brand-new items inline.
//  - AI tab: paste any blood-test text (description or shared lab-report
//    document text), let the LLM extract the items, review/edit the
//    preview, then save. Saving N items increments the habit by N.

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import com.example.tail.data.bloodtest.BloodTestItem
import com.example.tail.ui.viewmodel.BloodTestAiResult
import com.example.tail.ui.viewmodel.BloodTestDialogData
import com.example.tail.ui.viewmodel.BloodTestDocs
import java.time.LocalDate

/** Formats 92.0 → "92", 21.5 → "21.5". */
private fun fmtValue(d: Double): String =
    if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

/**
 * @param onParseAi Called with the pasted text; replies via [onResult]
 *   handed to [parseBloodTestWithAi].
 * @param onConfirm Called with (source, rawText, items, entryDate, note) once the
 *   user saves. The manual tab always uses [date]; the AI tab uses the date
 *   parsed from the report (editable in the date field), which may differ
 *   from the currently selected app date.
 */
@Composable
fun BloodTestDialog(
    habitName: String,
    date: LocalDate,
    data: BloodTestDialogData,
    onParseAi: (String, String, List<String>, (BloodTestAiResult) -> Unit) -> Unit,
    onReadDocuments: (List<Uri>, (BloodTestDocs) -> Unit) -> Unit,
    onConfirm: (source: String, rawText: String?, items: List<BloodTestItem>, entryDate: LocalDate, note: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var tab by remember { mutableStateOf(0) } // 0 = manual, 1 = AI
    // Manual form state: item name → entered value text
    val manualValues = remember { mutableStateMapOf<String, String>() }
    // Inline add-new-item (manual tab)
    var newItemName by remember { mutableStateOf("") }
    var newItemUnit by remember { mutableStateOf("") }
    var newItemValue by remember { mutableStateOf("") }
    // Extra items added this session (manual tab), name to unit
    val addedItems = remember { mutableStateListOf<Pair<String, String>>() }
    // AI tab state
    var aiText by remember { mutableStateOf("") }
    var aiBusy by remember { mutableStateOf(false) }
    var aiError by remember { mutableStateOf<String?>(null) }
    var aiDate by remember { mutableStateOf(date.toString()) }
    var aiItems by remember { mutableStateOf<List<BloodTestItem>?>(null) }
    // Items removed from the AI preview
    val aiRemoved = remember { mutableStateListOf<Int>() }
    // Attached documents (AI tab): combined text + page/photo images
    // Shared context note (fasting hours, diet, activity…) saved with the entry
    var note by remember { mutableStateOf("") }
    var docText by remember { mutableStateOf("") }
    val docImages = remember { mutableStateListOf<String>() }
    val docNames = remember { mutableStateListOf<String>() }
    var docReading by remember { mutableStateOf(false) }
    var docErrors by remember { mutableStateOf<String?>(null) }
    val docPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            docReading = true; docErrors = null
            onReadDocuments(uris) { docs: BloodTestDocs ->
                docReading = false
                if (docs.text.isNotEmpty()) {
                    docText = if (docText.isEmpty()) docs.text
                    else docText + "\n\n" + docs.text
                }
                docImages.addAll(docs.images)
                docNames.addAll(docs.names)
                docErrors = docs.errors
            }
        }
    }

    fun manualEntryItems(): List<BloodTestItem> {
        val all = data.knownItems.map { it.name to (it.unit ?: "") } + addedItems.toList()
        return all.mapNotNull { (name, unit) ->
            val v = manualValues[name]?.trim()?.replace(',', '.') ?: return@mapNotNull null
            val value = v.toDoubleOrNull() ?: return@mapNotNull null
            BloodTestItem(name = name, value = value, unit = unit.ifBlank { null })
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(text = habitName, fontSize = 16.sp)
                Text(
                    text = "$date · count = number of items tested",
                    fontSize = 10.sp, color = Color(0xFF888888)
                )
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

                // ── Tab selector ──────────────────────────────────────────
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TabButton("Manual", tab == 0) { tab = 0 }
                    TabButton("AI parse", tab == 1) { tab = 1 }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (tab == 0) {
                    // ── Existing entries today (context) ──
                    if (data.entriesToday.isNotEmpty()) {
                        val logged = data.entriesToday.sumOf { it.items.size }
                        Text(
                            text = "Logged $date: $logged item(s) across " +
                                "${data.entriesToday.size} entr${if (data.entriesToday.size == 1) "y" else "ies"}",
                            fontSize = 10.sp, color = Color(0xFF88FFCC)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    // ── One row per known item ──
                    (data.knownItems.map { it.name to it.unit } + addedItems.toList())
                        .forEach { (name, unit) ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Column(modifier = Modifier.weight(1.4f)) {
                                Text(text = name, fontSize = 12.sp, color = Color(0xFFCCCCCC), maxLines = 1)
                                val last = data.knownItems.firstOrNull { it.name == name }?.lastValue
                                if (last != null) {
                                    Text(text = "last: $last", fontSize = 9.sp, color = Color(0xFF667788), maxLines = 1)
                                }
                            }
                            OutlinedTextField(
                                value = manualValues[name] ?: "",
                                onValueChange = { manualValues[name] = it },
                                modifier = Modifier.weight(0.9f),
                                enabled = true,
                                singleLine = true,
                                placeholder = { Text(unit ?: "value", fontSize = 11.sp) },
                                textStyle = MaterialTheme.typography.bodySmall,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Add item", fontSize = 11.sp, color = Color(0xFF888888))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        OutlinedTextField(
                            value = newItemName, onValueChange = { newItemName = it },
                            modifier = Modifier.weight(1.2f), singleLine = true,
                            placeholder = { Text("name", fontSize = 11.sp) },
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = newItemUnit, onValueChange = { newItemUnit = it },
                            modifier = Modifier.weight(0.7f), singleLine = true,
                            placeholder = { Text("unit", fontSize = 11.sp) },
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                        OutlinedTextField(
                            value = newItemValue, onValueChange = { newItemValue = it },
                            modifier = Modifier.weight(0.7f), singleLine = true,
                            placeholder = { Text("value", fontSize = 11.sp) },
                            textStyle = MaterialTheme.typography.bodySmall,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                    TextButton(
                        onClick = {
                            val n = newItemName.trim()
                            if (n.isNotEmpty()) {
                                addedItems.add(n to newItemUnit.trim())
                                if (newItemValue.trim().isNotEmpty()) manualValues[n] = newItemValue.trim()
                                newItemName = ""; newItemUnit = ""; newItemValue = ""
                            }
                        },
                        enabled = newItemName.trim().isNotEmpty()
                    ) { Text("＋ Add", fontSize = 11.sp) }
                } else {
                    // ── AI tab: paste text and/or attach files → parse → review ──
                    OutlinedTextField(
                        value = aiText, onValueChange = { aiText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 70.dp, max = 130.dp),
                        placeholder = { Text("Paste the blood test text or report…", fontSize = 11.sp) },
                        textStyle = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    // ── Document attach row (multi-select, any count) ──
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { docPicker.launch("*/*") },
                            enabled = !docReading
                        ) { Text(if (docReading) "Reading…" else "📄 Attach files", fontSize = 11.sp) }
                        if (docNames.isNotEmpty()) {
                            Text(
                                "${docNames.size} file(s) attached",
                                fontSize = 10.sp, color = Color(0xFF88FFCC)
                            )
                        }
                    }
                    docNames.forEachIndexed { idx, name ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "• $name", fontSize = 10.sp,
                                color = Color(0xFFAAAAAA), modifier = Modifier.weight(1f)
                            )
                            TextButton(
                                onClick = { docNames.removeAt(idx) },
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                            ) { Text("✕", fontSize = 10.sp, color = Color(0xFF888888)) }
                        }
                    }
                    docErrors?.let {
                        Text(it, fontSize = 10.sp, color = Color(0xFFCCAA88), maxLines = 2)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                aiBusy = true; aiError = null; aiItems = null; aiRemoved.clear()
                                onParseAi(aiText, docText, docImages.toList()) { result: BloodTestAiResult ->
                                    aiBusy = false
                                    if (result.error != null) aiError = result.error
                                    else {
                                        aiItems = result.parsed?.items
                                        result.parsed?.date?.let { aiDate = it }
                                    }
                                }
                            },
                            enabled = (aiText.trim().isNotEmpty() || docText.isNotEmpty() ||
                                docImages.isNotEmpty()) && !aiBusy && !docReading
                        ) { Text(if (aiBusy) "Parsing…" else "✨ Parse", fontSize = 11.sp) }
                        OutlinedTextField(
                            value = aiDate, onValueChange = { aiDate = it },
                            modifier = Modifier.weight(1f), singleLine = true,
                            placeholder = { Text("YYYY-MM-DD", fontSize = 11.sp) },
                            textStyle = MaterialTheme.typography.bodySmall
                        )
                    }
                    aiError?.let {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(it, color = Color(0xFFFF8888), fontSize = 10.sp)
                    }
                    if (aiItems != null && runCatching { LocalDate.parse(aiDate.trim()) }
                            .getOrNull()?.equals(date) == false) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            "Will be saved to $aiDate (blood-draw date), not $date",
                            fontSize = 10.sp, color = Color(0xFFFFCC88)
                        )
                    }
                    aiItems?.let { items ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            "${items.size - aiRemoved.size} item(s) — tap a row to drop it",
                            fontSize = 10.sp, color = Color(0xFF88CCFF)
                        )
                        items.forEachIndexed { idx, item ->
                            if (idx in aiRemoved) return@forEachIndexed
                            Surface(
                                color = Color(0xFF1E2A38),
                                shape = MaterialTheme.shapes.small,
                                onClick = { aiRemoved.add(idx) },
                                modifier = Modifier.padding(vertical = 1.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(item.name, fontSize = 11.sp, color = Color(0xFFCCCCCC))
                                    Text(
                                        "${fmtValue(item.value)}${item.unit?.let { " $it" } ?: ""}" +
                                            (item.flag?.let { " · $it" } ?: ""),
                                        fontSize = 11.sp,
                                        color = if (item.flag == "low" || item.flag == "high")
                                            Color(0xFFFFAA66) else Color(0xFF88FFCC)
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Context note (both tabs): fasting hours, diet, etc. ──
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text("Note — e.g. 10hr fast, high carbs yesterday…", fontSize = 11.sp)
                    },
                    textStyle = MaterialTheme.typography.bodySmall,
                    minLines = 1, maxLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val entryNote = note.takeIf { it.isNotBlank() }
                    if (tab == 0) {
                        onConfirm("manual", null, manualEntryItems(), date, entryNote)
                    } else {
                        val items = aiItems
                            ?.filterIndexed { idx, _ -> idx !in aiRemoved }
                            .orEmpty()
                        val entryDate = runCatching { LocalDate.parse(aiDate.trim()) }
                            .getOrDefault(date)
                        onConfirm("ai", aiText.takeIf { it.isNotBlank() }, items, entryDate, entryNote)
                    }
                },
                enabled = if (tab == 0) manualEntryItems().isNotEmpty()
                else (aiItems?.size ?: 0) > aiRemoved.size
            ) {
                Text(
                    if (tab == 0) "Save"
                    else "Save ${((aiItems?.size ?: 0) - aiRemoved.size)} item(s)"
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Edit-panel row for blood-test habits: shows a "Panel results" button that
 * opens a popup listing every blood-test entry logged for the selected date.
 * [onLoad] is (habitName, date, callback) — wire it to
 * [com.example.tail.ui.viewmodel.loadBloodTestEntriesForDate].
 */
@Composable
fun BloodTestResultsButton(
    habitName: String,
    date: LocalDate,
    onLoad: (String, LocalDate, (List<com.example.tail.data.bloodtest.BloodTestEntry>) -> Unit) -> Unit
) {
    var show by remember { mutableStateOf(false) }
    var entries by remember {
        mutableStateOf<List<com.example.tail.data.bloodtest.BloodTestEntry>?>(null)
    }
    if (show && entries == null) {
        LaunchedEffect(habitName, date) {
            onLoad(habitName, date) { entries = it }
        }
    }
    TextButton(
        onClick = { show = true; entries = null },
        modifier = Modifier.padding(0.dp)
    ) { Text("🩸 Panel results…", fontSize = 11.sp, color = Color(0xFFBB88FF)) }
    if (show) {
        AlertDialog(
            onDismissRequest = { show = false },
            title = {
                Column {
                    Text("$habitName — $date", fontSize = 15.sp)
                    Text(
                        "Blood panel results for this day",
                        fontSize = 10.sp, color = Color(0xFF888888)
                    )
                }
            },
            text = {
                val list = entries
                when {
                    list == null -> Text("Loading…", fontSize = 12.sp)
                    list.isEmpty() -> Text(
                        "No blood test entries on this date.",
                        fontSize = 12.sp, color = Color(0xFF888888)
                    )
                    else -> Column(
                        modifier = Modifier.verticalScroll(rememberScrollState())
                    ) {
                        list.forEach { entry ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${entry.time ?: ""} · ${entry.items.size} item(s) · ${entry.source}",
                                    fontSize = 10.sp, color = Color(0xFF88CCFF)
                                )
                            }
                            entry.note?.let { n ->
                                Text(
                                    "📝 $n", fontSize = 10.sp,
                                    color = Color(0xFFCCBB88), maxLines = 4
                                )
                            }
                            entry.items.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        item.name + (item.range?.let { "  ($it)" } ?: ""),
                                        fontSize = 11.sp,
                                        color = Color(0xFFCCCCCC),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${fmtValue(item.value)}${item.unit?.let { " $it" } ?: ""}" +
                                            (item.flag?.let { " · $it" } ?: ""),
                                        fontSize = 11.sp,
                                        color = if (item.flag == "low" || item.flag == "high")
                                            Color(0xFFFFAA66) else Color(0xFF88FFCC)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { show = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun TabButton(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (active) Color(0xFF3A1A5A) else Color(0xFF222222),
        shape = MaterialTheme.shapes.small,
        onClick = onClick
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            fontSize = 11.sp,
            color = if (active) Color(0xFFBB88FF) else Color(0xFF888888)
        )
    }
}
