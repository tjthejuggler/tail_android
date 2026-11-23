package com.example.tail.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Exact per-increment amounts for custom-input habits (water ml, …).
 *
 * The habits DB only stores day TOTALS, so the individual amount of each
 * increment is otherwise lost. This sidecar (`files/amount_log.json`,
 * `habit → "yyyy-MM-dd HH:mm:ss" → amount`) records every in-app custom
 * amount so the suggestion engine can learn which amounts the user REALLY
 * drinks / logs, instead of echoing the last one. Purely supplemental:
 * suggestions fall back to total-decomposition for older days, and nothing
 * else reads it. Entries older than [RETENTION_DAYS] are pruned on write.
 */
class AmountLogRepository(private val context: Context) {

    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, MutableMap<String, Int>>>() {}.type
    private val file: File get() = File(context.filesDir, "amount_log.json")

    companion object {
        private val mutex = Mutex()
        private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        const val RETENTION_DAYS = 400L
    }

    private fun readAll(): MutableMap<String, MutableMap<String, Int>> =
        try {
            if (!file.exists()) mutableMapOf()
            else file.reader().use { gson.fromJson<MutableMap<String, MutableMap<String, Int>>>(it, type) }
                ?: mutableMapOf()
        } catch (_: Exception) {
            mutableMapOf()
        }

    /** `timestamp key → amount` for [habitName]. */
    suspend fun load(habitName: String): Map<String, Int> = withContext(Dispatchers.IO) {
        mutex.withLock { readAll()[habitName]?.toMap() ?: emptyMap() }
    }

    /** Records [amount] for [habitName] at the current moment. */
    suspend fun record(habitName: String, amount: Int, now: LocalDateTime = LocalDateTime.now()) {
        if (amount <= 0) return
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val parsed = readAll()
                    // A corrupt/unreadable file reads as empty — never overwrite a
                    // non-empty file with it.
                    if (parsed.isEmpty() && file.exists() && file.length() > 2L) return@withLock
                    val all = parsed
                    val day = all.getOrPut(habitName) { mutableMapOf() }
                    var key = now.format(FMT)
                    var n = 0
                    while (day.containsKey(key)) key = now.format(FMT) + "." + (++n)
                    day[key] = amount
                    val cutoff = LocalDate.now().minusDays(RETENTION_DAYS).toString()
                    for (m in all.values) m.keys.removeAll { it.substringBefore(' ') < cutoff }
                    val tmp = File(context.filesDir, "amount_log.json.tmp")
                    tmp.writer().use { gson.toJson(all, it) }
                    if (!tmp.renameTo(file)) {
                        file.delete()
                        tmp.renameTo(file)
                    }
                } catch (_: Exception) {
                    // Best-effort supplemental data.
                }
            }
        }
    }

    /** Moves a renamed habit's log. */
    suspend fun renameHabit(oldName: String, newName: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    val all = readAll()
                    val m = all.remove(oldName) ?: return@withLock
                    all[newName] = m
                    file.writeText(gson.toJson(all))
                } catch (_: Exception) {
                }
            }
        }
    }
}
