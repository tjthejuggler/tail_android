package com.example.tail.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * SORENESS nightly habit — data layer.
 *
 * The pre-onset precursor signal the injury episode schema cannot capture:
 * a nightly 0–3 rating per body region plus a paired "trained today"
 * boolean, so niggle→injury / niggle→physiology event studies have a
 * graded daily series BEFORE onset (prospective only — cannot be
 * retrofitted).
 *
 * Storage (internal-first, same model as [EpisodeDataRepository]):
 *  - Internal: `filesDir/soreness_data/Soreness_daily.json` — always written.
 *  - External: optional SAF document URI (sibling `Soreness_daily.json`
 *    created in the synced noteVault folder) — mirrored on every write so
 *    the research pipeline sees the rows.
 *
 * File format (array, oldest first):
 * ```json
 * [
 *   {"date": "2026-10-22", "trained": true,
 *    "regions": {"legs": 1, "back": 0, "shoulders": 0, "arms": 0, "other": 0}}
 * ]
 * ```
 * All-zero days are written explicitly when the user taps "Nothing sore"
 * (a real zero row is signal); missed days are simply absent.
 */
class SorenessDataRepository {

    companion object {
        private const val TAG = "SorenessDataRepo"
        private const val DIR = "soreness_data"
        const val FILE_NAME = "Soreness_daily.json"
        private val gson = Gson()
        private val prettyGson = GsonBuilder().setPrettyPrinting().create()
        private val listType = object : TypeToken<List<SorenessDay>>() {}.type

        /** Body regions with a nightly 0–3 rating. Stable machine keys. */
        val REGIONS = listOf("legs", "back", "shoulders", "arms", "other")
    }

    private fun internalFile(context: Context): File {
        val dir = File(context.filesDir, DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, FILE_NAME)
    }

    /** Loads all soreness days (internal + external merged by date, oldest first). */
    suspend fun loadDays(
        context: Context,
        externalUri: Uri? = null
    ): List<SorenessDay> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val internal = runCatching {
            val f = internalFile(context)
            if (f.exists()) gson.fromJson<List<SorenessDay>>(f.readText(), listType) else null
        }.getOrNull() ?: emptyList()

        val external = externalUri?.let { uri ->
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { s ->
                    val text = s.bufferedReader().readText()
                    if (text.isBlank()) null else gson.fromJson<List<SorenessDay>>(text, listType)
                }
            }.getOrNull()
        } ?: emptyList()

        // Union by date; internal wins on collision (latest write).
        val byDate = LinkedHashMap<String, SorenessDay>()
        for (d in external + internal) byDate[d.date] = d
        byDate.values.sortedBy { it.date }
    }

    /** Persists [days]: internal always, external mirror when possible. */
    suspend fun saveDays(
        context: Context,
        days: List<SorenessDay>,
        externalUri: Uri? = null
    ) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val json = prettyGson.toJson(days.sortedBy { it.date })
        runCatching { internalFile(context).writeText(json) }
            .onFailure { Log.w(TAG, "internal soreness save failed: ${it.message}") }

        if (externalUri != null) {
            val ok = runCatching {
                context.contentResolver.openOutputStream(externalUri, "wt")?.use { s ->
                    s.bufferedWriter().write(json)
                }
                true
            }.getOrDefault(false)
            if (ok) {
                runCatching { TailChangeLog.noteChange(context, "Soreness") }
            } else {
                Log.w(TAG, "external soreness mirror failed (internal copy is safe)")
            }
        }
    }

    /**
     * Finds (or creates) the `Soreness_daily.json` document inside [treeUri]'s
     * directory — same discovery pattern as
     * [EpisodeDataRepository.ensureSiblingEpisodeUri]. Returns null on any
     * failure; the internal store then remains the sole copy.
     */
    fun ensureSorenessUri(context: Context, treeUri: Uri): Uri? = runCatching {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val dirUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
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
                if (name == FILE_NAME) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0))
                }
            }
        }
        DocumentsContract.createDocument(context.contentResolver, dirUri, "application/json", FILE_NAME)
    }.getOrNull()
}

/**
 * One nightly soreness rating. [regions] maps a stable region machine key
 * (see [SorenessDataRepository.REGIONS]) to a 0–3 rating:
 * 0 = none, 1 = minor niggle, 2 = noticeable soreness, 3 = pain affecting movement.
 */
data class SorenessDay(
    val date: String,
    val trained: Boolean,
    val regions: Map<String, Int>
)
