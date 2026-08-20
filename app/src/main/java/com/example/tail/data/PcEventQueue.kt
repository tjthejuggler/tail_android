package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.tail.ui.HabitIncrementBus
import com.example.tail.widget.HabitListWidgetProvider
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * PC floating-widget event queue — the Tail Bridge transport that lets the
 * desktop bubble widget send habit increments (with the real session time)
 * to the phone.
 *
 * The PC widget talks to the local bridge (tail_bridge/bridge_server.py,
 * same machine, port 8001); the phone talks to the same bridge over the
 * LAN using the connection auto-derived from the Garmin proxy settings —
 * exactly like the movie bridge, no extra setup:
 *
 *  - phone → POST /api/v1/pc_widget/config   which habits the widget shows
 *  - widget → POST /api/v1/pc_widget/event   timer session / tap events
 *  - phone → GET  /api/v1/pc_widget/events   everything not yet acked
 *  - phone → POST /api/v1/pc_widget/acks     applied ids (bridge prunes)
 *
 * The bridge is the single writer of its state files, so there are no sync
 * conflicts and no shared folders. Delivery is at-least-once + acks =
 * effectively-once: a failed ack POST means the phone re-pulls and
 * re-applies on the next poll (increments are additive, so this is safe).
 */

private const val TAG = "PcEventQueue"

/** Port the Tail Bridge listens on (Garmin proxy uses 8000, bridge 8001). */
const val PC_WIDGET_BRIDGE_PORT = 8001

/**
 * Derives the (url, token) bridge connection from Garmin settings, or null
 * when the Garmin proxy URL / app token are not configured. Mirrors
 * HabitViewModel.deriveBridgeUrl so callers need no extra wiring.
 */
fun bridgeConnectionFrom(garminProxyUrl: String, garminAppToken: String): Pair<String, String>? {
    if (garminProxyUrl.isBlank() || garminAppToken.isEmpty()) return null
    return try {
        val clean = garminProxyUrl.trim().trimEnd('/')
        val uri = java.net.URI(clean)
        val scheme = uri.scheme ?: "http"
        val host = uri.host ?: return null
        "$scheme://$host:$PC_WIDGET_BRIDGE_PORT" to garminAppToken
    } catch (e: Exception) {
        null
    }
}

/** Validated, ready-to-apply event. */
data class PcHabitEvent(
    val id: String,
    val habit: String,
    /** true = timer session (carries minutes); false = quick tap (+1). */
    val isSession: Boolean,
    val date: LocalDate,
    /** "HH:mm:ss" when the habit happened (session start / tap time), or null. */
    val startTime: String?,
    val minutes: Int
)

/** Outcome of one [PcEventQueueProcessor.processOnce] pass. */
data class PcEventQueueResult(
    /** Events whose increments were applied. */
    val applied: Int = 0,
    /** Events acknowledged without applying (e.g. max-one cap already met). */
    val skipped: Int = 0
)

/**
 * Pure encode/decode helpers for the wire format. Kept side-effect free and
 * Android-free so they are unit-testable on the JVM.
 */
object PcEventQueueCodec {

    private val gson = Gson()
    private val dateRe = Regex("""^\d{4}-\d{2}-\d{2}$""")
    private val timeRe = Regex("""^([01]\d|2[0-3]):[0-5]\d:[0-5]\d$""")

    /**
     * Parses the GET /pc_widget/events response. Malformed entries are
     * dropped individually; a totally malformed body yields an empty list
     * (callers simply find nothing to do — nothing is ever acked blindly).
     */
    fun parseEvents(json: String): List<PcHabitEvent> {
        val root = try {
            gson.fromJson(json, Map::class.java) ?: return emptyList()
        } catch (e: Exception) {
            return emptyList()
        }
        @Suppress("UNCHECKED_CAST")
        val events = root["events"] as? List<*> ?: return emptyList()
        return events.mapNotNull { raw ->
            val map = raw as? Map<*, *> ?: return@mapNotNull null
            val id = (map["id"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val habit = (map["habit"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val kind = map["kind"] as? String
            val isSession = kind == "session"
            val minutes = when (val m = map["minutes"]) {
                is Number -> m.toInt()
                is String -> m.toIntOrNull() ?: 0
                else -> 0
            }.coerceAtLeast(0)
            val dateStr = map["date"] as? String
            val date = try {
                LocalDate.parse(dateStr)
            } catch (e: Exception) {
                null
            } ?: return@mapNotNull null
            val start = (map["start"] as? String)?.takeIf { timeRe.matches(it) }
            PcHabitEvent(
                id = id,
                habit = habit,
                isSession = isSession,
                date = date,
                startTime = start,
                minutes = minutes
            )
        }
    }

    /** Parses a "processed" id list (the acks request/response shape). */
    fun parseAcks(json: String): Set<String> {
        val root = try {
            gson.fromJson(json, Map::class.java) ?: return emptySet()
        } catch (e: Exception) {
            return emptySet()
        }
        @Suppress("UNCHECKED_CAST")
        val processed = root["processed"] as? List<*> ?: return emptySet()
        return processed.mapNotNull { it as? String }.filter { it.isNotBlank() }.toSet()
    }

    /** Builds the POST /pc_widget/acks request body. */
    fun buildAcksBody(processed: List<String>): String {
        val gsonPretty = GsonBuilder().setPrettyPrinting().create()
        val map = linkedMapOf<String, Any>(
            "processed" to processed
        )
        return gsonPretty.toJson(map)
    }

    /** True when [dateStr]/[timeStr] match the wire formats the PC writes. */
    fun isValidDate(dateStr: String?): Boolean =
        dateStr != null && dateRe.matches(dateStr)

    fun isValidTime(timeStr: String?): Boolean =
        timeStr != null && timeRe.matches(timeStr)
}

/**
 * Applies unacked PC-widget events to the habit database.
 *
 * Instantiated freely (FloatingBubbleService poll, app foreground); a
 * companion [Mutex] serialises [processOnce] so two concurrent callers
 * can never double-apply the same event.
 */
class PcEventQueueProcessor(private val context: Context) {

    companion object {
        private val processMutex = Mutex()
    }

    /**
     * One processing pass: pull unacked events from the bridge, apply
     * everything new, ack it. Safe to call repeatedly; cheap no-op when
     * the bridge is unreachable or nothing is pending.
     */
    suspend fun processOnce(): PcEventQueueResult = processMutex.withLock {
        val settings = try {
            SettingsRepository(context).settingsFlow.first()
        } catch (e: Exception) {
            return PcEventQueueResult()
        }
        val bridge = bridgeConnectionFrom(settings.garminProxyUrl, settings.garminAppToken)
            ?: return PcEventQueueResult()
        if (settings.fileUri.isEmpty()) return PcEventQueueResult()

        var applied = 0
        var skipped = 0
        try {
            val client = BridgeClient()
            val eventsJson = client.fetch(bridge.first, bridge.second, "pc_widget/events")
                ?: return PcEventQueueResult()
            val events = PcEventQueueCodec.parseEvents(eventsJson.toString())
            if (events.isEmpty()) return PcEventQueueResult()

            val habitsUri = Uri.parse(settings.fileUri)
            val habitsRepo = HabitsRepository()
            val tsRepo = HabitTimestampRepository(context)
            val newAckIds = mutableListOf<String>()
            val touchedHabits = mutableSetOf<String>()

            for (event in events) {
                try {
                    val outcome = applyEvent(event, settings, habitsUri, habitsRepo, tsRepo)
                    if (outcome) applied++ else skipped++
                    newAckIds.add(event.id)
                    touchedHabits.add(event.habit)
                } catch (e: Exception) {
                    // Apply failed (e.g. transient DB error) — do NOT ack, retry next poll.
                    Log.w(TAG, "Failed to apply PC event ${event.id} (${event.habit}): ${e.message}")
                }
            }

            if (newAckIds.isNotEmpty()) {
                val ackBody = org.json.JSONObject()
                    .put("processed", org.json.JSONArray(newAckIds))
                val ackResp = client.post(bridge.first, bridge.second, "pc_widget/acks", ackBody)
                if (ackResp == null) {
                    // Acks failed — events will re-apply next poll. Undo nothing
                    // (counts are additive); log loudly instead.
                    Log.e(TAG, "Applied ${newAckIds.size} PC events but FAILED to ack them — " +
                        "they may double-apply on the next poll")
                }
            }

            touchedHabits.forEach { HabitIncrementBus.emit(it) }
            if (touchedHabits.isNotEmpty()) {
                try { HabitListWidgetProvider.refreshAll(context) } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            Log.w(TAG, "PC event queue pass failed: ${e.message}")
        }
        PcEventQueueResult(applied = applied, skipped = skipped)
    }

    /**
     * Applies one event. Mirrors the semantics of HabitIncrementReceiver and
     * the phone bubble's writeMinutesToHabit:
     *  - session  → +1 session AND +minutes on the habit's secondary-value slot
     *               (atomic single write, date-aware)
     *  - tap      → +1, respecting the max-one cap (capped taps are acked as skipped)
     *  - conditional links feed linked habits (with feed-max1 + max-one caps)
     *  - a timestamp is recorded at the event's own start time unless the
     *    habit is timeless
     *
     * Returns true when an increment was applied, false when deliberately
     * skipped (still ackable).
     */
    private suspend fun applyEvent(
        event: PcHabitEvent,
        settings: AppSettings,
        habitsUri: Uri,
        habitsRepo: HabitsRepository,
        tsRepo: HabitTimestampRepository
    ): Boolean = withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dateStr = dateString(event.date)
        // One read up front for all cap checks.
        val db = habitsRepo.loadDatabase(habitsUri, context)
        val increments = mutableMapOf<String, Int>()
        var appliedAny = false

        when {
            event.isSession && event.minutes > 0 -> {
                increments[event.habit] = (increments[event.habit] ?: 0) + 1
                increments[minutesKey(event.habit)] =
                    (increments[minutesKey(event.habit)] ?: 0) + event.minutes
                appliedAny = true
            }
            else -> {
                val current = db[event.habit]?.get(dateStr) ?: 0
                if (event.habit in settings.maxOneHabits && current >= 1) {
                    Log.i(TAG, "PC tap for '${event.habit}' skipped — already at max 1")
                } else {
                    increments[event.habit] = (increments[event.habit] ?: 0) + 1
                    appliedAny = true
                }
            }
        }

        // Conditional linked habits — same rules as the IPC receiver and the
        // manual tap path: the feed amount is the source's POINTS delta when
        // "feed points" is enabled (divider-applied), else the raw increment
        // amount, with the "feed max1" cap on Points targets. A flat +1 here
        // would over-feed linked aggregates (e.g. "Chess") whenever a source
        // has a divider or multi-unit increments.
        if (appliedAny && event.habit in settings.conditionalHabits) {
            val sourceCountBefore = db[event.habit]?.get(dateStr) ?: 0
            val feedPoints = event.habit in settings.conditionalFeedPointsHabits
            val sourceDivider = settings.habitDividers[event.habit] ?: 1
            for (linkedName in settings.conditionalLinkedHabits[event.habit].orEmpty()) {
                val valueKey = effectiveConditionalLinkValueKey(
                    settings.conditionalLinkValues, settings.secondaryValueHabits,
                    settings.chessComHabitLinks, event.habit, linkedName
                )
                val targetKey = conditionalLinkStorageKey(linkedName, valueKey)
                if (targetKey == linkedName) {
                    val linkedAtMax = linkedName in settings.maxOneHabits &&
                        (db[linkedName]?.get(dateStr) ?: 0) >= 1
                    if (linkedAtMax) continue
                }
                // A PC event carries one unit (tap or session) of the source.
                val baseFeedAmount = conditionalTapFeedAmount(
                    sourceCountBefore, 1, feedPoints, sourceDivider
                )
                val feedAmount = if (
                    targetKey == linkedName &&
                    event.habit in settings.conditionalFeedMaxOneHabits
                ) {
                    conditionalCappedFeedAmount(sourceCountBefore, baseFeedAmount)
                } else baseFeedAmount
                if (feedAmount == 0) continue
                increments[targetKey] = (increments[targetKey] ?: 0) + feedAmount
            }
        }

        if (increments.isNotEmpty()) {
            habitsRepo.incrementHabitSlotsForDate(habitsUri, context, increments, event.date)
        }

        if (appliedAny && event.habit !in settings.timelessHabits) {
            val time = event.startTime ?: HabitTimestampRepository.nowTime()
            try {
                tsRepo.addTimestamp(event.habit, event.date, time)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record timestamp for PC event: ${e.message}")
            }
        }
        appliedAny
    }
}
