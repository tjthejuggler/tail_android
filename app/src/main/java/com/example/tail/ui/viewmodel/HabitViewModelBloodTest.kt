package com.example.tail.ui.viewmodel

// Blood-test habit type (structured lab-item database behind one square) —
// ViewModel layer. Split out of HabitViewModel.kt following the
// per-feature file convention (see HabitViewModelPillLog.kt).

import androidx.lifecycle.viewModelScope
import com.example.tail.data.bloodtest.BLOOD_METRIC_COUNT
import com.example.tail.data.ai.llmFallbackEndpoint
import com.example.tail.data.bloodtest.BloodTestAiParser
import com.example.tail.data.bloodtest.BloodTestEntry
import com.example.tail.data.bloodtest.BloodTestItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Everything the blood-test dialog needs in one shot. */
data class BloodTestDialogData(
    /** Every item name ever measured, most-recent-first. */
    val knownItems: List<KnownBloodItem>,
    /** Entries already logged for the selected date. */
    val entriesToday: List<BloodTestEntry>
)

/** One known (previously measured) item, for the manual form's rows. */
data class KnownBloodItem(
    val name: String,
    /** Last-used unit (pre-fill hint). */
    val unit: String?,
    /** Last measured value, formatted for display (e.g. "92 mg/dL · 2026-10-02"). */
    val lastValue: String?
)

/** True when [habitName] has the blood-test type enabled. */
fun HabitViewModel.isBloodTestHabit(habitName: String): Boolean =
    habitName in _settings.value.bloodTestHabits

/** Toggles the blood-test type for [habitName] (pill-log toggle pattern). */
fun HabitViewModel.toggleBloodTest(habitName: String) {
    viewModelScope.launch {
        val current = _settings.value.bloodTestHabits.toMutableSet()
        if (habitName in current) current.remove(habitName) else current.add(habitName)
        settingsRepo.saveBloodTestHabits(current)
        _settings.value = _settings.value.copy(bloodTestHabits = current)
    }
}

/** Sync list of every item name ever measured for the habit (graph filter). */
fun HabitViewModel.bloodTestItemNames(habitName: String): List<String> =
    bloodTestRepo.uniqueItemNames(habitName)

/** Loads the dialog data (known items + today's entries) off the main thread. */
fun HabitViewModel.loadBloodTestDialogData(
    habitName: String,
    date: LocalDate,
    onResult: (BloodTestDialogData) -> Unit
) {
    viewModelScope.launch {
        val data = withContext(Dispatchers.IO) {
            val names = bloodTestRepo.uniqueItemNames(habitName)
            val known = names.map { name ->
                val unit = bloodTestRepo.lastUnitFor(habitName, name)
                val last = bloodTestRepo.latestValue(habitName, name)?.let { (entry, item) ->
                    val v = if (item.value == item.value.toLong().toDouble())
                        item.value.toLong().toString() else item.value.toString()
                    "$v${item.unit?.let { " $it" } ?: ""} · ${entry.date}"
                }
                KnownBloodItem(name, unit, last)
            }
            BloodTestDialogData(
                knownItems = known,
                entriesToday = bloodTestRepo.loadEntries(habitName)
                    .filter { it.date == date.toString() }
            )
        }
        onResult(data)
    }
}

/** Loads just the entries logged for [date] (edit-panel results popup). */
fun HabitViewModel.loadBloodTestEntriesForDate(
    habitName: String,
    date: LocalDate,
    onResult: (List<BloodTestEntry>) -> Unit
) {
    viewModelScope.launch {
        val entries = withContext(Dispatchers.IO) {
            bloodTestRepo.loadEntries(habitName).filter { it.date == date.toString() }
        }
        onResult(entries)
    }
}

/**
 * Saves one blood-test entry: appends to the item database and increments
 * the habit's daily count by the entry's item count (one timestamp).
 */
fun HabitViewModel.saveBloodTestEntry(
    habitName: String,
    date: LocalDate,
    time: LocalTime?,
    source: String,
    rawText: String?,
    items: List<BloodTestItem>,
    note: String? = null
) {
    val clean = items.filter { it.name.isNotBlank() }
    if (clean.isEmpty()) return
    viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                bloodTestRepo.addEntry(
                    habitName,
                    BloodTestEntry(
                        id = "",
                        date = date.toString(),
                        time = time?.format(DateTimeFormatter.ofPattern("HH:mm")),
                        source = source,
                        rawText = rawText?.takeIf { it.isNotBlank() },
                        note = note?.takeIf { it.isNotBlank() },
                        items = clean
                    )
                )
            }
            val stampTime = (time ?: LocalTime.now())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"))
            incrementHabit(
                habitName, clean.size,
                recordTimestamp = true,
                date = date,
                stampTime = stampTime
            )
        } catch (e: Exception) {
            _errorMessage.value = "Failed to save blood test entry: ${e.message}"
        }
    }
}

/** Result wrapper for the AI parse call. */
data class BloodTestAiResult(
    val parsed: BloodTestAiParser.ParsedTest? = null,
    val error: String? = null
)

/**
 * Parses free text (pasted description or shared lab-document text) into
 * structured items via the configured AI Assistant endpoints.
 */
fun HabitViewModel.parseBloodTestWithAi(
    text: String,
    docText: String = "",
    docImages: List<String> = emptyList(),
    onResult: (BloodTestAiResult) -> Unit
) {
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            try {
                val s = _settings.value
                val config = com.example.tail.data.ai.AiAssistantConfig(
                    baseUrl = s.aiAssistantBaseUrl,
                    apiKey = s.aiAssistantApiKey,
                    model = s.aiAssistantModel,
                    fallback = s.llmFallbackEndpoint()
                )
                BloodTestAiResult(
                    parsed = BloodTestAiParser().parse(text, config, docText, docImages)
                )
            } catch (e: Exception) {
                BloodTestAiResult(error = e.message ?: e.javaClass.simpleName)
            }
        }
        onResult(result)
    }
}

/** Result of reading user-picked blood-test documents. */
data class BloodTestDocs(
    val text: String,
    /** data-URL images (photos / rendered PDF pages). */
    val images: List<String>,
    /** Display names of the successfully read files. */
    val names: List<String>,
    /** Non-fatal read problems, if any. */
    val errors: String?
)

/**
 * Reads picked documents (text / PDF / images, any count) into text +
 * multimodal images for the AI parse — all merged so multi-file and
 * multi-page reports parse as ONE blood test.
 */
fun HabitViewModel.readBloodTestDocuments(
    uris: List<android.net.Uri>,
    onResult: (BloodTestDocs) -> Unit
) {
    viewModelScope.launch {
        val docs = withContext(Dispatchers.IO) {
            try {
                val c = com.example.tail.data.bloodtest
                    .BloodTestDocumentReader(context).read(uris)
                BloodTestDocs(c.text, c.images, c.names, c.errors)
            } catch (e: Exception) {
                BloodTestDocs("", emptyList(), emptyList(), e.message ?: "failed to read files")
            }
        }
        onResult(docs)
    }
}

/**
 * Replaces the graph metric selection for a blood-test habit
 * (`blood:__count` + `blood:<Item>` keys). At least the count metric stays
 * selected — an empty [metricKeys] falls back to count-only.
 */
fun HabitViewModel.setBloodTestGraphSelection(habitName: String, metricKeys: Set<String>) {
    viewModelScope.launch {
        val selection = metricKeys.filter { it.startsWith("blood:") }
            .ifEmpty { listOf(BLOOD_METRIC_COUNT) }.toSet()
        val current = _settings.value.graphMetricSelection.toMutableMap()
        current[habitName] = selection
        settingsRepo.saveGraphMetricSelection(current)
        _settings.value = _settings.value.copy(graphMetricSelection = current)
    }
}
