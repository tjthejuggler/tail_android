package com.example.tail.data

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.Reader
import java.io.Writer

/**
 * Streaming codec for the habits database (`Map<habit, Map<"YYYY-MM-DD", Int>>`).
 *
 * WHY (OOM crash loop, 2026-09-23):
 *   The DB file is multi-MB and grows forever. The previous save path built
 *   the whole document as a `String` via `Gson.toJson(db)` (≈2 bytes/char,
 *   plus StringBuilder doubling = ~4× the file size in transient heap),
 *   re-read the on-disk file as another full String for the anti-shrinkage
 *   guard, then copied each of those Strings again to hash and write the
 *   snapshots. Peak transient allocation per save was ~6× the file size, on
 *   top of the parsed object graphs held by every concurrent reader. That
 *   exhausted the 256 MB heap during startup bursts.
 *
 *   Everything here streams: JSON goes straight from the object graph to a
 *   [Writer] and straight from a [Reader] into the object graph. No
 *   intermediate document String ever exists, so memory is bounded by the
 *   parsed graph alone regardless of file size.
 *
 * FORMAT COMPATIBILITY:
 *   [write] produces output byte-identical to Gson's `setPrettyPrinting()`
 *   for this shape (2-space indent, `"key": value`, empty inner maps as
 *   `{}`), so the desktop side and the existing Syncthing-shared file see no
 *   format change. [read] accepts anything Gson accepted (lenient numbers,
 *   whitespace, any ordering) and yields the same insertion-ordered maps.
 */
object HabitsDbCodec {

    /**
     * Parses a habits DB document from [reader]. Throws on malformed input
     * (same contract as Gson: callers classify the failure).
     *
     * Date keys are interned through a small per-parse table: the same
     * ~1300 distinct date strings repeat across all ~250 habits, so sharing
     * them cuts the parsed graph's String footprint by an order of magnitude.
     */
    fun read(reader: Reader): HabitsDatabase {
        val json = JsonReader(reader)
        json.isLenient = true
        return readDb(json)
    }

    private fun readDb(json: JsonReader): HabitsDatabase {
        if (json.peek() == JsonToken.NULL) {
            json.nextNull()
            return emptyMap()
        }
        val keyPool = HashMap<String, String>(2048)
        val db = LinkedHashMap<String, Map<String, Int>>(512)
        json.beginObject()
        while (json.hasNext()) {
            val habit = json.nextName()
            if (json.peek() == JsonToken.NULL) {
                json.nextNull()
                db[habit] = emptyMap()
                continue
            }
            val entries = LinkedHashMap<String, Int>()
            json.beginObject()
            while (json.hasNext()) {
                val rawKey = json.nextName()
                val key = keyPool.getOrPut(rawKey) { rawKey }
                entries[key] = json.nextInt()
            }
            json.endObject()
            db[habit] = entries
        }
        json.endObject()
        return db
    }

    /**
     * Serialises [db] to [writer] in Gson pretty-print format. The caller
     * owns the writer (flush/close). Never allocates the document as a whole.
     */
    fun write(db: HabitsDatabase, writer: Writer) {
        val json = JsonWriter(writer)
        json.setIndent("  ")
        json.serializeNulls = true
        // Gson.toJson() defaults to htmlSafe=true (escapes <>&='); match it so
        // output stays byte-identical to the legacy GsonBuilder().setPrettyPrinting() writer.
        json.isHtmlSafe = true
        json.beginObject()
        for ((habit, entries) in db) {
            json.name(habit)
            json.beginObject()
            for ((date, count) in entries) {
                json.name(date).value(count.toLong())
            }
            json.endObject()
        }
        json.endObject()
        json.flush()
    }

    /**
     * Counts total entries (sum of inner-map sizes) in a habits-DB document
     * with O(1) memory. Throws on malformed or foreign JSON.
     */
    fun countEntries(reader: Reader): Int {
        val json = JsonReader(reader)
        json.isLenient = true
        var total = 0
        json.beginObject()
        while (json.hasNext()) {
            json.nextName()
            json.beginObject()
            while (json.hasNext()) {
                json.nextName()
                json.nextInt()
                total++
            }
            json.endObject()
        }
        json.endObject()
        return total
    }

    /** Total entries in an in-memory DB. */
    fun countEntries(db: HabitsDatabase): Int {
        var total = 0
        for (m in db.values) total += m.size
        return total
    }
}
