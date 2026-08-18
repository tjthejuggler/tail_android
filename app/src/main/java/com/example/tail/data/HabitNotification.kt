package com.example.tail.data

/**
 * One pending "ask" notification — a habit confirmation the system is waiting
 * for an answer on ("Did you floss?", "Watched this movie?").
 *
 * Asks are redundant across three surfaces:
 *  1. an Android system notification with Yes/No actions,
 *  2. the in-app notification center (bell icon in the top bar),
 *  3. a single bottom flash the first time the app is opened after the ask
 *     was created.
 *
 * Answering on ANY surface removes the record everywhere ([NotificationStore]
 * is the single source of truth; the system notification is cancelled by id).
 *
 * @param id Stable identifier. "movie:<title>@<day>" for movie asks,
 *           "schedule:<habit>:<yyyy-MM-dd>" for scheduled asks.
 * @param habitName Habit the ask belongs to.
 * @param type [TYPE_MOVIE] or [TYPE_SCHEDULE].
 * @param title Headline (movie title, or the habit name for scheduled asks).
 * @param question The yes/no question shown to the user.
 * @param createdAtMillis When the ask was created (alarm fire / detection).
 * @param flashShown Whether the one-time flash already happened for this ask.
 * @param payload Extra data: for movie asks the pre-computed "HH:mm:ss" entry
 *                time to log the title at; empty for scheduled asks.
 */
data class HabitNotification(
    val id: String,
    val habitName: String,
    val type: String,
    val title: String,
    val question: String,
    val createdAtMillis: Long,
    val flashShown: Boolean = false,
    val payload: String = ""
) {
    companion object {
        const val TYPE_MOVIE = "movie"
        const val TYPE_SCHEDULE = "schedule"

        /** Builds the stable id for a scheduled ask of [habitName] on [date]. */
        fun scheduleId(habitName: String, date: String): String =
            "schedule:$habitName:$date"
    }
}

/**
 * Pure encode/decode for persisting [HabitNotification] lists as one string
 * (DataStore preferences have no list-of-objects type). Uses the same
 * delimiter+escape style as the other settings maps so it stays unit-testable
 * without Android.
 */
object HabitNotificationCodec {

    private const val FIELD_SEP = "\u001F" // unit separator
    private const val REC_SEP = "\u001E"   // record separator
    private const val ESCAPE = "\u001D"    // escape marker

    /** Serializes [notifications] into one compact string. */
    fun encode(notifications: List<HabitNotification>): String {
        return notifications.joinToString(REC_SEP) { n ->
            listOf(
                n.id, n.habitName, n.type, n.title, n.question,
                n.createdAtMillis.toString(), n.flashShown.toString(), n.payload
            ).joinToString(FIELD_SEP) { escape(it) }
        }
    }

    /** Parses a string produced by [encode]. Skips malformed records. */
    fun decode(raw: String?): List<HabitNotification> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(REC_SEP).mapNotNull { rec ->
            val fields = rec.split(FIELD_SEP).map { unescape(it) }
            if (fields.size < 8) return@mapNotNull null
            val createdAt = fields[5].toLongOrNull() ?: return@mapNotNull null
            HabitNotification(
                id = fields[0],
                habitName = fields[1],
                type = fields[2],
                title = fields[3],
                question = fields[4],
                createdAtMillis = createdAt,
                flashShown = fields[6] == "true",
                payload = fields[7]
            )
        }
    }

    private fun escape(s: String): String = s
        .replace(ESCAPE, ESCAPE + ESCAPE)
        .replace(FIELD_SEP, ESCAPE + "F")
        .replace(REC_SEP, ESCAPE + "R")

    private fun unescape(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == ESCAPE[0] && i + 1 < s.length) {
                when (s[i + 1]) {
                    ESCAPE[0] -> out.append(ESCAPE)
                    'F' -> out.append(FIELD_SEP)
                    'R' -> out.append(REC_SEP)
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}

/**
 * Pure schedule-time math for the daily habit asks.
 */
object HabitSchedule {

    /**
     * Parses a "HH:mm" (or "HH:mm:ss") schedule string. Returns null when
     * malformed.
     */
    fun parseTime(time: String): Pair<Int, Int>? {
        val parts = time.trim().split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        if (h !in 0..23 || m !in 0..59) return null
        return h to m
    }

    /**
     * Next epoch-millis occurrence of the daily [time] ("HH:mm") at or after
     * [nowMillis]. If today's slot already passed, returns tomorrow's.
     * Returns null for a malformed time.
     */
    fun nextOccurrenceMillis(time: String, nowMillis: Long): Long? {
        val (h, m) = parseTime(time) ?: return null
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.Instant.ofEpochMilli(nowMillis).atZone(zone)
        var next = now.toLocalDate().atTime(h, m).atZone(zone)
        if (!next.toInstant().isAfter(java.time.Instant.ofEpochMilli(nowMillis))) {
            next = next.plusDays(1)
        }
        return next.toInstant().toEpochMilli()
    }

    /**
     * True when [time] ("HH:mm") already passed today relative to [nowMillis]
     * (i.e. the scheduled moment is at or before now).
     */
    fun passedToday(time: String, nowMillis: Long): Boolean {
        val next = nextOccurrenceMillis(time, nowMillis) ?: return false
        // If the next occurrence is still today, the slot has not passed.
        val nextDay = java.time.Instant.ofEpochMilli(next).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val nowDay = java.time.Instant.ofEpochMilli(nowMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return nextDay != nowDay
    }
}
