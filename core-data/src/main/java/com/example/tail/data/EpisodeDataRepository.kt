package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File
import java.time.LocalDate

/**
 * EPISODE habit type ("Injury (episode)") — data layer.
 *
 * An episode habit models an EVENT with a start and an end (an injury), not a
 * daily status. A new episode is created only on onset; while an episode is
 * OPEN, tapping the habit opens the "Update episode" screen instead of
 * creating a new row (dedupe enforcement). The episode is closed by an
 * explicit "Mark resolved" action which sets [InjuryEpisode.resolvedDate].
 *
 * Storage (internal-first, same model as TextInputRepository):
 *  - Internal: `filesDir/episode_data/<habit>.json` — always written.
 *  - External: optional SAF URI (per-habit, AppSettings.episodeFileUris) —
 *    mirrored on every write so the synced research pipeline sees the
 *    episodes. When no episode URI is configured but the habit has a
 *    text-input file URI in a SAF tree, a sibling `<name>_episodes.json`
 *    document is created automatically on first write
 *    ([ensureSiblingEpisodeUri]).
 *
 * Episode file format (array, oldest first; all dates "yyyy-MM-dd" STRINGS —
 * Gson cannot reflectively serialize java.time types):
 * ```json
 * [
 *   {
 *     "id": "2026-08-06",
 *     "onsetDate": "2026-08-06",
 *     "resolvedDate": "2026-08-27",
 *     "type": "pulled_muscle",
 *     "bodyPart": "upper_leg",
 *     "bodyPartDetail": "",
 *     "side": "left",
 *     "severity": null,
 *     "note": "Pulled muscle in upper left leg"
 *   }
 * ]
 * ```
 *
 * Derived pipeline output (backwards compatibility): the ViewModel rewrites
 * the habit's daily habitsdb values to an `injured_today` flag (1 on every
 * day from onset through resolvedDate inclusive — open episodes run to
 * today — 0 elsewhere), so all existing reports keep working. Onset events
 * for the event-study engine (`event_study`, `event="injury_onset"`) are
 * exactly the `onsetDate` fields of this file.
 */
class EpisodeDataRepository {

    companion object {
        private const val TAG = "EpisodeDataRepo"
        private const val DIR = "episode_data"
        private val gson = Gson()
        private val prettyGson = GsonBuilder().setPrettyPrinting().create()
        private val listType = object : TypeToken<List<InjuryEpisode>>() {}.type

        fun episodeUriOrNull(uriString: String?): Uri? =
            uriString?.takeIf { it.isNotBlank() }?.let(Uri::parse)
    }

    // ── Internal store ────────────────────────────────────────────────────

    private fun internalFile(context: Context, habitName: String): File {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        val safe = habitName.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(100)
        return File(dir, "$safe.json")
    }

    /** Loads all episodes for [habitName] (internal + external merged). Oldest onset first. */
    suspend fun loadEpisodes(
        context: Context,
        habitName: String,
        externalUri: Uri? = null
    ): List<InjuryEpisode> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val internal = runCatching {
            val f = internalFile(context, habitName)
            if (f.exists()) gson.fromJson<List<InjuryEpisode>>(f.readText(), listType) else null
        }.getOrNull() ?: emptyList()

        val external = externalUri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val text = s.bufferedReader().readText()
                    if (text.isBlank()) null else gson.fromJson<List<InjuryEpisode>>(text, listType)
                }
            }.getOrNull()
        } ?: emptyList()

        // Union by id: external-only episodes (e.g. desktop-script-migrated
        // history) merge in; internal wins on id collision (latest write).
        // Gson bypasses Kotlin defaults, so episodes saved before the
        // severityByDay field existed (or by the desktop migration script)
        // deserialize it as NULL — normalize to emptyMap here.
        val byId = LinkedHashMap<String, InjuryEpisode>()
        for (e in external + internal) {
            byId[e.id] = if (e.severityByDay == null) {
                e.copy(severityByDay = emptyMap())
            } else e
        }
        byId.values.sortedBy { it.onsetDate }
    }

    /** Persists [episodes] for [habitName]: internal always, external mirror when possible. */
    suspend fun saveEpisodes(
        context: Context,
        habitName: String,
        episodes: List<InjuryEpisode>,
        externalUri: Uri? = null
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val sorted = episodes.sortedBy { it.onsetDate }
        val json = prettyGson.toJson(sorted)
        runCatching { internalFile(context, habitName).writeText(json) }
            .onFailure { Log.w(TAG, "internal episode save failed: ${it.message}") }

        var uri = externalUri
        if (uri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(uri, "wt")?.use { s ->
                    s.bufferedWriter().write(json)
                }
                true
            }.getOrDefault(false)
            if (ok) {
                runCatching { TailChangeLog.noteChange(context, habitName) }
            } else {
                Log.w(TAG, "external episode mirror failed for '$habitName' (internal copy is safe)")
            }
        }
    }

    /**
     * Builds (and creates if missing) the sibling `<name>_episodes.json`
     * document URI inside [treeUri]'s directory. Returns null on any
     * failure — the internal store then remains the sole copy.
     */
    fun ensureSiblingEpisodeUri(
        context: Context,
        treeUri: Uri,
        habitName: String
    ): Uri? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        val displayName = "${habitName.replace(Regex("[^a-zA-Z0-9._-]"), "_")}_episodes.json"
        // First try to open the existing document.
        val children = runCatching {
            context.contentResolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME
                ),
                null, null, null
            )
        }.getOrNull()
        children?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1) ?: continue
                if (name == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
        // Not found — create it.
        DocumentsContract.createDocument(context.contentResolver, dirUri, "application/json", displayName)
    }.getOrNull()

    /** The episode with no resolvedDate yet, or null. */
    fun openEpisode(episodes: List<InjuryEpisode>): InjuryEpisode? =
        episodes.lastOrNull { it.resolvedDate == null }

    /** True on any date from an episode's onset through its resolved date (or today when open). */
    fun isDateInEpisode(
        episodes: List<InjuryEpisode>,
        date: LocalDate,
        today: LocalDate = LocalDate.now()
    ): Boolean = episodes.any { ep ->
        val onset = runCatching { LocalDate.parse(ep.onsetDate) }.getOrNull() ?: return@any false
        val end = ep.resolvedDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today
        !date.isBefore(onset) && !date.isAfter(end)
    }
}

/**
 * One injury episode. `type`, `bodyPart` and `side` are stored as stable
 * snake_case machine keys (enum-name style) so the research pipeline can
 * match on them; display labels live in the UI. All dates are plain
 * "yyyy-MM-dd" strings (Gson/java.time constraint).
 */
data class InjuryEpisode(
    /** Stable unique id — the onset date string ("yyyy-MM-dd"). */
    val id: String,
    /** Day the injury was first noticed. NEVER repeated daily — one row per episode. */
    val onsetDate: String,
    /** Set only by the explicit "Mark resolved" action; null = still open. */
    val resolvedDate: String? = null,
    /** pulled_muscle / sprain / cut / soreness / other. */
    val type: String = "other",
    /** leg / upper_leg / back / shoulder / hand / other (see UI list). */
    val bodyPart: String = "other",
    /** Free-text detail for body_part = other. */
    val bodyPartDetail: String = "",
    /** left / right / na. */
    val side: String = "na",
    /** 1..5 slider; null = unknown (migrated history). */
    val severity: Int? = null,
    /**
     * Per-day severity ratings ("yyyy-MM-dd" → 1..5) recorded while the
     * episode was open — the "how did intensity change over time" series
     * for the graph. Days without an entry inherit the binary flag only.
     */
    val severityByDay: Map<String, Int> = emptyMap(),
    /** Optional free-text note, kept separate from the structured fields. */
    val note: String = ""
) {
    fun isOpen(today: LocalDate = LocalDate.now()): Boolean =
        resolvedDate == null &&
            !(runCatching { LocalDate.parse(onsetDate) }.getOrNull() ?: today).isAfter(today)
}
