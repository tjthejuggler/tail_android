package com.example.tail.data.ai

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsLoadResult
import com.example.tail.data.HabitsRepository
import com.example.tail.data.HabitTimestampRepository
import com.example.tail.data.isAppLink
import com.example.tail.data.isInternalValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** Endpoint configuration for the AI Assistant (OpenAI-compatible chat completions). */
data class AiAssistantConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()
}

/**
 * One write operation proposed by the LLM. Operations are plain data — they
 * are shown to the user for confirmation and executed deterministically
 * (never by the model itself).
 */
data class AiPlanOp(
    val type: String,          // set_habit_value | add_timestamps | remove_timestamps | set_timestamps | copy_timestamps
    val habit: String,
    val date: String,          // YYYY-MM-DD
    val value: Int? = null,    // for set_habit_value
    val times: List<String> = emptyList(),  // HH:mm:ss strings
    val sourceHabit: String? = null,        // for copy_timestamps
    val replaceExisting: Boolean = false    // for copy_timestamps
) {
    /** Compact one-line human rendering used in the confirmation card. */
    fun describe(): String = when (type) {
        "set_habit_value" -> "Set \"$habit\" on $date to $value"
        "add_timestamps" -> "Add ${times.size} timestamp(s) to \"$habit\" on $date: ${times.joinToString(", ")}"
        "remove_timestamps" -> "Remove ${times.size} timestamp(s) from \"$habit\" on $date: ${times.joinToString(", ")}"
        "set_timestamps" -> "Replace timestamps of \"$habit\" on $date with ${times.size}: ${times.joinToString(", ")}"
        "copy_timestamps" ->
            "Copy ALL session timestamps from \"${sourceHabit ?: "?"}\" to \"$habit\" on $date" +
                if (replaceExisting) " (replacing existing)" else " (keeping existing)"
        else -> "$type $habit $date $value ${times.joinToString(", ")}"
    }
}

/** A full change plan proposed by the LLM, pending user confirmation. */
data class AiPlan(
    val description: String,
    val operations: List<AiPlanOp>
)

/** Metadata about the safety backup taken before the last executed plan. */
data class AiBackupInfo(
    val createdAtMillis: Long,
    val createdAtLabel: String,
    val description: String,
    val opCount: Int
)

/** One bubble in the assistant chat. */
data class AiChatMessage(
    val role: String,          // "user" | "assistant" | "error"
    val content: String
)

/**
 * ═══════════════════════════════════════════════════════════════════════
 *  AI ASSISTANT — natural-language habit database editing
 * ═══════════════════════════════════════════════════════════════════════
 *
 * Two-phase, safety-first workflow:
 *
 *  PHASE 1 — PLAN. The user describes a desired database change in natural
 *  language. The LLM may call READ-ONLY tools (list_habits, get_habit_values,
 *  get_timestamps) to inspect the data, then must call `propose_plan` with a
 *  precise human-readable description and the exact list of write operations.
 *  Nothing is written in this phase.
 *
 *  PHASE 2 — CONFIRM & EXECUTE. The plan is shown to the user. On confirm:
 *    1. A temporary backup of habitsdb.txt + habit_timestamps.json is taken.
 *    2. The operations are applied deterministically (no LLM in the loop).
 *    3. The app reloads. If the user dislikes the result, the backup can be
 *       restored from Settings → Habit Features → AI Assistant.
 */
class AiAssistantController(
    private val context: Context,
    private val habitsRepo: HabitsRepository,
    private val configProvider: () -> AiAssistantConfig,
    private val fileUriProvider: () -> String?,
    private val onDatabaseChanged: () -> Unit
) {
    companion object {
        private const val TAG = "AiAssistant"
        private const val MAX_TOOL_ROUNDS = 10
        private const val MAX_HISTORY_MESSAGES = 24
        private const val MAX_TOKENS = 8000
        private const val BACKUP_DIR = "ai_assistant"
        private const val BACKUP_DB = "habitsdb.backup"
        private const val BACKUP_TS = "habit_timestamps.backup.json"
        private const val BACKUP_META = "meta.json"
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss")
        private val LABEL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val timestampRepo = HabitTimestampRepository(context)

    /**
     * Running LLM conversation (WITHOUT the system prompt) so multi-turn
     * corrections and follow-ups keep their context. Trimmed by
     * [trimHistory]; cleared by [clearConversation].
     */
    private val llmHistory = mutableListOf<JSONObject>()

    // ── UI state ─────────────────────────────────────────────────────────

    private val _messages = MutableStateFlow<List<AiChatMessage>>(emptyList())
    /** Chat transcript shown in the dialog. */
    val messages: StateFlow<List<AiChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    /** True while an LLM request or an execution is running. */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _pendingPlan = MutableStateFlow<AiPlan?>(null)
    /** Non-null while a plan awaits user confirmation. */
    val pendingPlan: StateFlow<AiPlan?> = _pendingPlan.asStateFlow()

    private val _backupInfo = MutableStateFlow<AiBackupInfo?>(null)
    /** Metadata of the last safety backup (null when none exists). */
    val backupInfo: StateFlow<AiBackupInfo?> = _backupInfo.asStateFlow()

    init {
        refreshBackupInfo()
        if (_messages.value.isEmpty()) {
            _messages.value = listOf(greetingMessage())
        }
    }

    private fun greetingMessage() = AiChatMessage(
        "assistant",
        "Hi! I can make habit database changes for you — just describe " +
            "what you need.\n\nExample: \"I tracked programming today but forgot " +
            "to log my standing sessions — create standing sessions at the same " +
            "times as my programming sessions today.\"\n\nI'll show you exactly " +
            "what I plan to change and wait for your confirmation. A backup is " +
            "saved before anything is touched."
    )

    // ── Public API ───────────────────────────────────────────────────────

    /** Sends the user's message to the LLM and runs the planning tool loop. */
    fun sendMessage(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return

        val config = configProvider()
        if (config == null || !config.isConfigured) {
            postAssistantError("AI Assistant is not configured. Set Base URL, API key and model in Settings → Habit Features → AI Assistant.")
            return
        }
        val fileUri = currentFileUri()
        if (fileUri == null) {
            postAssistantError("No habit database file is selected (Settings → Data Files).")
            return
        }

        _messages.value = _messages.value + AiChatMessage("user", trimmed)
        // The running LLM history is the conversation seed — add the user
        // turn here so runPlanningLoop sees it.
        llmHistory.add(messageJson("user", trimmed))
        _busy.value = true
        scope.launch {
            try {
                runPlanningLoop(config, Uri.parse(fileUri), trimmed)
            } catch (e: Exception) {
                Log.e(TAG, "Planning failed", e)
                postAssistantError("Request failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Executes the pending plan: backup → apply → reload. */
    fun confirmPlan() {
        val plan = _pendingPlan.value ?: return
        val fileUri = currentFileUri() ?: return
        _pendingPlan.value = null
        _busy.value = true
        scope.launch {
            try {
                executePlan(plan, Uri.parse(fileUri))
            } catch (e: Exception) {
                Log.e(TAG, "Execution failed", e)
                postAssistantError("Execution failed: ${e.message ?: e.javaClass.simpleName}. " +
                    "The backup was NOT removed — you can restore it in Settings.")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Discards the pending plan without touching anything. */
    fun cancelPlan() {
        val plan = _pendingPlan.value ?: return
        _pendingPlan.value = null
        llmHistory.add(messageJson(
            "assistant", "The user CANCELLED the plan: ${plan.description}. Nothing was changed."
        ))
        _messages.value = _messages.value +
            AiChatMessage("assistant", "Cancelled — nothing was changed.")
    }

    /** Clears the transcript AND the LLM conversation history. */
    fun clearConversation() {
        if (_busy.value) return
        llmHistory.clear()
        _pendingPlan.value = null
        _messages.value = listOf(greetingMessage())
    }

    /** Restores habitsdb.txt + timestamps from the safety backup. */
    fun restoreBackup() {
        val fileUri = currentFileUri()
        if (fileUri == null) {
            postAssistantError("No habit database file selected — cannot restore.")
            return
        }
        _busy.value = true
        scope.launch {
            try {
                val restored = withContext(Dispatchers.IO) { restoreBackupFiles(Uri.parse(fileUri)) }
                if (restored) {
                    refreshBackupInfo()
                    _messages.value = _messages.value + AiChatMessage(
                        "assistant",
                        "Backup restored — the database is back to the state before my last changes."
                    )
                    onDatabaseChanged()
                } else {
                    postAssistantError("No backup found to restore.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                postAssistantError("Restore failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    /** Deletes the safety backup (it is no longer needed). */
    fun deleteBackup() {
        scope.launch {
            withContext(Dispatchers.IO) { backupDir().deleteRecursively() }
            refreshBackupInfo()
        }
    }

    /** Quick connectivity test used by the settings section. */
    fun testConnection(onResult: (Boolean, String) -> Unit) {
        val config = configProvider()
        if (config == null || !config.isConfigured) {
            onResult(false, "Configure Base URL, API key and model first.")
            return
        }
        scope.launch {
            try {
                val reply = withContext(Dispatchers.IO) { pingEndpoint(config) }
                onResult(true, "Connection OK — model replied.")
            } catch (e: Exception) {
                onResult(false, "Failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    // ── Planning loop (PHASE 1) ──────────────────────────────────────────

    /**
     * Runs the OpenAI-compatible tool-calling loop. Read tools are executed
     * locally; `propose_plan` captures the plan and ends the loop.
     *
     * The conversation is seeded from [llmHistory] so corrections and
     * follow-ups keep context. Empty model turns are nudged once before
     * giving up (some endpoints stall on long batch requests).
     */
    private suspend fun runPlanningLoop(config: AiAssistantConfig, fileUri: Uri, userText: String) {
        val seededHistorySize = llmHistory.size
        val conversation = JSONArray().apply {
            put(messageJson("system", buildSystemPrompt()))
            llmHistory.forEach { put(it) }
        }

        var assistantReply: String? = null
        var capturedPlan: AiPlan? = null
        var nudgedOnce = false

        for (round in 0 until MAX_TOOL_ROUNDS) {
            val responseBody = withContext(Dispatchers.IO) {
                callChatEndpoint(config, requestBody(config, conversation))
            }
            val choiceObj = responseBody.getJSONArray("choices").getJSONObject(0)
            val finishReason = choiceObj.optString("finish_reason", "")
            val choice = choiceObj.getJSONObject("message")

            val toolCalls = choice.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0) {
                val content = choice.optString("content")
                if (content.isBlank() && finishReason == "length") {
                    assistantReply = "The model ran out of output tokens mid-answer. For large " +
                        "batch changes, use the batch operation (copy_timestamps) instead of " +
                        "listing every time individually — or ask for fewer changes at once."
                    break
                }
                if (content.isBlank() && !nudgedOnce) {
                    nudgedOnce = true
                    conversation.put(messageJson(
                        "user",
                        "Continue. You must finish by calling propose_plan with the exact " +
                            "operations (use copy_timestamps for batch timestamp copies), or " +
                            "ask one short clarifying question."
                    ))
                    continue
                }
                assistantReply = content.ifBlank { "(empty reply — try rephrasing the request)" }
                break
            }

            // Record the assistant's tool-call turn, then answer each call.
            conversation.put(JSONObject().apply {
                put("role", "assistant")
                put("content", choice.opt("content") ?: JSONObject.NULL)
                put("tool_calls", toolCalls)
            })

            var planThisRound: AiPlan? = null
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val id = call.optString("id")
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }
                    .getOrDefault(JSONObject())

                if (name == "propose_plan") {
                    planThisRound = parsePlan(args)
                    conversation.put(toolResultJson(id, "PLAN_RECEIVED — the user will now confirm."))
                } else {
                    val result = executeReadTool(name, args, fileUri)
                    conversation.put(toolResultJson(id, result))
                }
            }

            if (planThisRound != null) {
                capturedPlan = planThisRound
                break
            }
        }

        // Persist this exchange into the running history (system prompt and
        // the previously-seeded messages are skipped — only the NEW tail is
        // appended). Old tool outputs are truncated — the model rarely needs
        // the full lists again and they are what makes batch conversations
        // explode in size.
        val tailStart = 1 + seededHistorySize
        for (i in tailStart until conversation.length()) {
            val msg = conversation.getJSONObject(i)
            if (msg.optString("role") == "tool") {
                val c = msg.optString("content")
                if (c.length > 400) msg.put("content", c.take(200) + "…[truncated]")
            }
            llmHistory.add(msg)
        }
        trimHistory()

        if (capturedPlan != null) {
            llmHistory.add(messageJson(
                "assistant",
                "I proposed a plan: ${capturedPlan.description} " +
                    "(${capturedPlan.operations.size} operations). Awaiting user confirmation."
            ))
            trimHistory()
            if (capturedPlan.operations.isEmpty()) {
                _messages.value = _messages.value + AiChatMessage(
                    "assistant",
                    capturedPlan.description.ifBlank { "The plan contained no operations." } +
                        "\n\n(no operations — nothing to change)"
                )
            } else {
                _pendingPlan.value = capturedPlan
                _messages.value = _messages.value + AiChatMessage(
                    "assistant",
                    capturedPlan.description.ifBlank { "Here is my plan:" }
                )
            }
        } else {
            if (assistantReply != null) {
                llmHistory.add(messageJson("assistant", assistantReply))
                trimHistory()
            }
            _messages.value = _messages.value + AiChatMessage(
                "assistant",
                assistantReply ?: "I could not produce a plan. Try rephrasing the request."
            )
        }
    }

    /**
     * Keeps only the newest [MAX_HISTORY_MESSAGES] entries. A tool message
     * must always directly follow its assistant tool-call turn, so after
     * dropping the oldest entry any now-dangling tool messages at the head
     * are dropped too.
     */
    private fun trimHistory() {
        while (llmHistory.size > MAX_HISTORY_MESSAGES) {
            llmHistory.removeAt(0)
            while (llmHistory.isNotEmpty() && llmHistory[0].optString("role") == "tool") {
                llmHistory.removeAt(0)
            }
        }
    }

    /** Executes a read-only inspection tool and returns a JSON string result. */
    private suspend fun executeReadTool(name: String, args: JSONObject, fileUri: Uri): String {
        return try {
            when (name) {
                "list_habits" -> {
                    val db = loadDb(fileUri)
                    val today = LocalDate.now().toString()
                    val arr = JSONArray()
                    db.keys
                        .filter { !isInternalValueKey(it) && !isAppLink(it) }
                        .sorted()
                        .forEach { habit ->
                            arr.put(JSONObject().apply {
                                put("habit", habit)
                                put("today_value", db[habit]?.get(today) ?: 0)
                            })
                        }
                    JSONObject().put("habits", arr).toString()
                }
                "get_habit_values" -> {
                    val habit = args.optString("habit")
                    val from = args.optString("from_date", "")
                    val to = args.optString("to_date", "")
                    val db = loadDb(fileUri)
                    val values = JSONObject()
                    db[habit]?.forEach { (date, value) ->
                        if ((from.isEmpty() || date >= from) && (to.isEmpty() || date <= to)) {
                            values.put(date, value)
                        }
                    }
                    JSONObject().put("habit", habit).put("values", values).toString()
                }
                "get_timestamps" -> {
                    val habit = args.optString("habit")
                    val date = args.optString("date", LocalDate.now().toString())
                    val times = timestampRepo.getTimestampsForDay(habit, LocalDate.parse(date))
                    JSONObject().put("habit", habit).put("date", date)
                        .put("times", JSONArray(times)).toString()
                }
                else -> JSONObject().put("error", "Unknown tool: $name").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message ?: "tool failed").toString()
        }
    }

    // ── Execution (PHASE 2) ──────────────────────────────────────────────

    /** Backup → apply all operations → reload app data. */
    private suspend fun executePlan(plan: AiPlan, fileUri: Uri) {
        // 1. Safety backup (raw bytes of both stores).
        withContext(Dispatchers.IO) { writeBackupFiles(fileUri, plan) }
        refreshBackupInfo()

        // 2. Apply habitsdb operations (if any).
        val dbOps = plan.operations.filter { it.type == "set_habit_value" }
        if (dbOps.isNotEmpty()) {
            val loadResult = habitsRepo.loadDatabaseResult(fileUri, context)
            if (loadResult !is HabitsLoadResult.Success) {
                throw IllegalStateException("could not re-load habitsdb before writing")
            }
            val mutable = loadResult.db.toMutableMap()
            for (op in dbOps) {
                val entries = mutable.getOrPut(op.habit) { mutableMapOf() }.toMutableMap()
                entries[op.date] = op.value ?: 0
                mutable[op.habit] = entries.toSortedMap()
            }
            habitsRepo.saveDatabase(fileUri, context, mutable)
        }

        // 3. copy_timestamps — app-side batch copy. The model never lists
        // the individual times, so mirroring 50+ sessions stays ONE op.
        for (op in plan.operations.filter { it.type == "copy_timestamps" }) {
            val source = op.sourceHabit ?: continue
            val day = LocalDate.parse(op.date)
            val srcTimes = timestampRepo.getTimestampsForDay(source, day)
            val existing = if (op.replaceExisting) emptyList()
                           else timestampRepo.getTimestampsForDay(op.habit, day)
            timestampRepo.setTimestampsForDay(op.habit, day, (existing + srcTimes).sorted())
        }

        // 4. Apply timestamp operations, grouped per habit+date in one write.
        val tsOps = plan.operations.filter { it.type != "set_habit_value" && it.type != "copy_timestamps" }
        val applied = mutableListOf<String>()
        for ((habit, date) in tsOps.groupBy({ it.habit }, { it.date }).flatMap { (h, dates) ->
            dates.distinct().map { h to it }
        }) {
            val opsForDay = tsOps.filter { it.habit == habit && it.date == date }
            var current = timestampRepo.getTimestampsForDay(habit, LocalDate.parse(date)).toMutableList()
            for (op in opsForDay) {
                when (op.type) {
                    "add_timestamps" -> current = (current + op.times).sorted().toMutableList()
                    "remove_timestamps" -> {
                        val toRemove = op.times.toMutableList()
                        current = current.filter { !(toRemove.remove(it)) }.toMutableList()
                    }
                    "set_timestamps" -> current = op.times.sorted().toMutableList()
                }
            }
            timestampRepo.setTimestampsForDay(habit, LocalDate.parse(date), current)
            applied += opsForDay.map { it.describe() }
        }

        // 5. Reload the app so every screen reflects the change.
        onDatabaseChanged()

        _messages.value = _messages.value + AiChatMessage(
            "assistant",
            "Done — ${plan.operations.size} operation(s) applied:\n" +
                plan.operations.joinToString("\n") { "• ${it.describe()}" } +
                "\n\nA backup of the previous state was saved. If you don't like the " +
                "result, restore it in Settings → Habit Features → AI Assistant."
        )
    }

    // ── Backup / restore ─────────────────────────────────────────────────

    private fun backupDir(): File = File(context.filesDir, BACKUP_DIR)

    /** The SAF URI string of habitsdb.txt, or null when no file is selected. */
    private fun currentFileUri(): String? = fileUriProvider()?.takeIf { it.isNotBlank() }

    private fun writeBackupFiles(fileUri: Uri, plan: AiPlan) {
        val dir = backupDir().apply { mkdirs() }
        // habitsdb raw bytes
        context.contentResolver.openInputStream(fileUri)?.use { input ->
            File(dir, BACKUP_DB).outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("cannot read habitsdb for backup")
        // timestamps raw bytes
        val tsFile = File(context.filesDir, "habit_timestamps.json")
        if (tsFile.exists()) tsFile.copyTo(File(dir, BACKUP_TS), overwrite = true)
        else File(dir, BACKUP_TS).writeText("{}")
        // metadata
        val now = System.currentTimeMillis()
        File(dir, BACKUP_META).writeText(
            JSONObject()
                .put("created_at", now)
                .put("created_at_label", LocalDateTime.now().format(LABEL_FMT))
                .put("description", plan.description)
                .put("op_count", plan.operations.size)
                .toString()
        )
    }

    /** Returns true when a backup existed and was restored. */
    private fun restoreBackupFiles(fileUri: Uri): Boolean {
        val dir = backupDir()
        val dbBackup = File(dir, BACKUP_DB)
        val tsBackup = File(dir, BACKUP_TS)
        if (!dbBackup.exists()) return false

        context.contentResolver.openOutputStream(fileUri, "wt")?.use { out ->
            dbBackup.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("cannot write habitsdb (no write permission?)")

        if (tsBackup.exists()) {
            tsBackup.copyTo(File(context.filesDir, "habit_timestamps.json"), overwrite = true)
        }
        return true
    }

    private fun refreshBackupInfo() {
        val meta = File(backupDir(), BACKUP_META)
        _backupInfo.value = if (meta.exists()) {
            runCatching {
                val json = JSONObject(meta.readText())
                AiBackupInfo(
                    createdAtMillis = json.optLong("created_at"),
                    createdAtLabel = json.optString("created_at_label"),
                    description = json.optString("description"),
                    opCount = json.optInt("op_count")
                )
            }.getOrNull()
        } else null
    }

    // ── LLM transport ────────────────────────────────────────────────────

    private fun buildSystemPrompt(): String = buildString {
        append("You are the AI Assistant inside \"tail\", a personal habit-tracking app.\n")
        append("Today is ${LocalDate.now()} (${LocalDate.now().dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}), ")
        append("local time ${LocalTime.now().format(TIME_FMT)}.\n\n")
        append("DATABASE SCHEMA\n")
        append("1. habitsdb: habit name -> date \"YYYY-MM-DD\" -> integer count. The count is the ")
        append("primary daily value (reps, minutes, sessions, etc.).\n")
        append("2. Timestamps store: habit name -> date -> list of \"HH:mm:ss\" strings. Each ")
        append("timestamp marks ONE session/increment of that habit at that time of day.\n\n")
        append("RULES\n")
        append("- Only use habit names returned by list_habits. Never invent or guess names.\n")
        append("- Inspect the data with the read-only tools before planning changes.\n")
        append("- When the user asks for a database change, finish by calling propose_plan with:\n")
        append("  * description: a precise human-readable summary of EXACTLY what will change ")
        append("(habit names, dates, times, values, before -> after where relevant).\n")
        append("  * operations: the exact list of write operations.\n")
        append("- Operation types:\n")
        append("  {\"type\":\"copy_timestamps\",\"source_habit\":\"...\",\"habit\":\"...\",\"date\":\"YYYY-MM-DD\",\"replace_existing\":<bool>} — copy ALL of another habit's session timestamps on that date onto this habit (batch; executed by the app, no times listed)\n")
        append("  {\"type\":\"set_habit_value\",\"habit\":\"...\",\"date\":\"YYYY-MM-DD\",\"value\":<int>} — set the habit's count for a date (absolute value)\n")
        append("  {\"type\":\"add_timestamps\",\"habit\":\"...\",\"date\":\"YYYY-MM-DD\",\"times\":[\"HH:MM:SS\",...]} — add session timestamps\n")
        append("  {\"type\":\"remove_timestamps\",\"habit\":\"...\",\"date\":\"YYYY-MM-DD\",\"times\":[\"HH:MM:SS\",...]} — remove specific timestamps\n")
        append("  {\"type\":\"set_timestamps\",\"habit\":\"...\",\"date\":\"YYYY-MM-DD\",\"times\":[\"HH:MM:SS\",...]} — replace ALL timestamps of that day\n")
        append("- BATCH RULES: when the user wants one habit's sessions mirrored onto another ")
        append("habit (e.g. \"create Standing sessions at the same times as my Programming ")
        append("sessions today\"), ALWAYS use copy_timestamps — NEVER enumerate the individual ")
        append("times with add_timestamps. Call get_timestamps on the source first so your ")
        append("description states the exact count (e.g. \"copies all 55 sessions\").\n")
        append("- If the request is ambiguous or references an unknown habit, ask a short ")
        append("clarifying question instead of proposing a plan.\n")
        append("- Nothing is executed during planning — the user confirms every plan first.\n")
    }

    private fun toolDefinitions(): JSONArray = JSONArray().apply {
        fun fn(name: String, description: String, props: JSONObject, required: List<String>) {
            put(JSONObject().apply {
                put("type", "function")
                put("function", JSONObject().apply {
                    put("name", name)
                    put("description", description)
                    put("parameters", JSONObject().apply {
                        put("type", "object")
                        put("properties", props)
                        put("required", JSONArray(required))
                    })
                })
            })
        }
        fn(
            "list_habits",
            "List every tracked habit with its count for today.",
            JSONObject(),
            emptyList()
        )
        fn(
            "get_habit_values",
            "Get the stored daily values of one habit, optionally restricted to a date range.",
            JSONObject().apply {
                put("habit", JSONObject().put("type", "string"))
                put("from_date", JSONObject().put("type", "string").put("description", "YYYY-MM-DD inclusive, optional"))
                put("to_date", JSONObject().put("type", "string").put("description", "YYYY-MM-DD inclusive, optional"))
            },
            listOf("habit")
        )
        fn(
            "get_timestamps",
            "Get the session timestamps (HH:mm:ss list) of one habit on one date.",
            JSONObject().apply {
                put("habit", JSONObject().put("type", "string"))
                put("date", JSONObject().put("type", "string").put("description", "YYYY-MM-DD, defaults to today"))
            },
            listOf("habit")
        )
        fn(
            "propose_plan",
            "Propose the exact database changes to make. Call this ONCE at the end, after inspecting the data. The user must confirm before anything is executed.",
            JSONObject().apply {
                put("description", JSONObject().put("type", "string").put("description", "Precise human-readable summary of exactly what will change"))
                put("operations", JSONObject().put("type", "array").put("description", "List of write operations").put("items", JSONObject().put("type", "object")))
            },
            listOf("description", "operations")
        )
    }

    private fun requestBody(config: AiAssistantConfig, messages: JSONArray): JSONObject =
        JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            put("tools", toolDefinitions())
            put("tool_choice", "auto")
            put("temperature", 0.2)
            put("max_tokens", MAX_TOKENS)
        }

    private fun messageJson(role: String, content: String): JSONObject =
        JSONObject().apply { put("role", role); put("content", content) }

    private fun toolResultJson(toolCallId: String, content: String): JSONObject =
        JSONObject().apply {
            put("role", "tool")
            put("tool_call_id", toolCallId)
            put("content", content)
        }

    /** Minimal request (no tools) used by the connection test. */
    private fun pingEndpoint(config: AiAssistantConfig): String {
        val body = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(messageJson("user", "Reply with the single word: pong"))
            })
            put("max_tokens", 10)
        }
        val response = callChatEndpoint(config, body)
        return response.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").optString("content")
    }

    private fun callChatEndpoint(config: AiAssistantConfig, body: JSONObject): JSONObject {
        val connection = (URL(buildEndpointUrl(config.baseUrl)).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            connectTimeout = 30_000
            readTimeout = 120_000
            doOutput = true
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val text = if (code in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                val err = connection.errorStream?.bufferedReader()?.readText() ?: ""
                throw IllegalStateException("HTTP $code: ${err.take(300)}")
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /** Same URL-building convention as the meal vision engine. */
    private fun buildEndpointUrl(baseUrl: String): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        val versionPattern = Regex("""/v\d+$""")
        return when {
            trimmed.endsWith("/chat/completions") -> trimmed
            versionPattern.containsMatchIn(trimmed) -> "$trimmed/chat/completions"
            else -> "$trimmed/v1/chat/completions"
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private suspend fun loadDb(fileUri: Uri): HabitsDatabase {
        val result = habitsRepo.loadDatabaseResult(fileUri, context)
        if (result !is HabitsLoadResult.Success) {
            throw IllegalStateException("habitsdb could not be read")
        }
        return result.db
    }

    private fun parsePlan(args: JSONObject): AiPlan {
        val description = args.optString("description", "")
        val ops = mutableListOf<AiPlanOp>()
        val rawOps = args.optJSONArray("operations") ?: JSONArray()
        for (i in 0 until rawOps.length()) {
            val op = rawOps.optJSONObject(i) ?: continue
            val type = op.optString("type")
            val habit = op.optString("habit")
            val date = op.optString("date", LocalDate.now().toString())
            val times = mutableListOf<String>()
            val rawTimes = op.optJSONArray("times")
            if (rawTimes != null) {
                for (j in 0 until rawTimes.length()) times += rawTimes.optString(j)
            }
            val value = if (op.has("value") && !op.isNull("value")) op.optInt("value") else null
            val sourceHabit = op.optString("source_habit").ifBlank { null }
            val replaceExisting = op.optBoolean("replace_existing", false)
            if (habit.isNotBlank()) {
                ops += AiPlanOp(type, habit, date, value, times, sourceHabit, replaceExisting)
            }
        }
        return AiPlan(description, ops)
    }

    private fun postAssistantError(text: String) {
        _messages.value = _messages.value + AiChatMessage("error", text)
    }
}
