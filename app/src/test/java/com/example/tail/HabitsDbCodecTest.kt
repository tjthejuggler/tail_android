package com.example.tail

import com.example.tail.data.HabitsDatabase
import com.example.tail.data.HabitsDbCodec
import com.google.gson.GsonBuilder
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader
import java.io.StringWriter

/**
 * Pins the streaming [HabitsDbCodec] to the exact on-disk behaviour of the
 * Gson pretty-printer it replaced (the DB file is shared with the desktop side
 * via Syncthing, so the byte format must not drift).
 */
class HabitsDbCodecTest {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val dbType = object : TypeToken<Map<String, Map<String, Int>>>() {}.type

    private fun sampleDb(): HabitsDatabase = linkedMapOf(
        "Meditation" to linkedMapOf("2026-01-01" to 1, "2026-01-02" to 0, "2026-01-03" to 7),
        "Empty habit" to linkedMapOf(),
        "Weird <name> & 'quotes' = \"dq\"" to linkedMapOf("2026-01-01" to -3, "2026-01-02" to 2_000_000_000),
        "Pull-ups minutes" to linkedMapOf("2026-01-01" to 0),
        "Ünïcödé 日本" to linkedMapOf("2026-02-28" to 5),
    )

    private fun encode(db: HabitsDatabase): String {
        val sw = StringWriter()
        HabitsDbCodec.write(db, sw)
        return sw.toString()
    }

    @Test
    fun write_isByteIdenticalToGsonPrettyPrinting() {
        val db = sampleDb()
        assertEquals(gson.toJson(db, dbType), encode(db))
    }

    @Test
    fun write_emptyDb_matchesGson() {
        val db: HabitsDatabase = emptyMap()
        assertEquals(gson.toJson(db, dbType), encode(db))
    }

    @Test
    fun read_roundTripPreservesOrderAndValues() {
        val db = sampleDb()
        val decoded = HabitsDbCodec.read(StringReader(encode(db)))
        assertEquals(db, decoded)
        assertEquals(db.keys.toList(), decoded.keys.toList())
        for ((habit, entries) in db) {
            assertEquals(entries.keys.toList(), decoded.getValue(habit).keys.toList())
        }
    }

    @Test
    fun read_matchesGsonOnGsonOutput() {
        val db = sampleDb()
        val text = gson.toJson(db, dbType)
        val viaGson: Map<String, Map<String, Int>> = gson.fromJson(text, dbType)
        val viaCodec = HabitsDbCodec.read(StringReader(text))
        assertEquals(viaGson, viaCodec)
    }

    @Test
    fun read_internsDateKeysAcrossHabits() {
        val text = """{"a":{"2026-01-01":1},"b":{"2026-01-01":2}}"""
        val db = HabitsDbCodec.read(StringReader(text))
        val ka = db.getValue("a").keys.first()
        val kb = db.getValue("b").keys.first()
        assertTrue("date keys should be the same String instance", ka === kb)
    }

    @Test
    fun read_acceptsNullDocumentAndNullHabit() {
        assertEquals(emptyMap<String, Map<String, Int>>(), HabitsDbCodec.read(StringReader("null")))
        val db = HabitsDbCodec.read(StringReader("""{"a":null,"b":{"2026-01-01":3}}"""))
        assertEquals(emptyMap<String, Int>(), db["a"])
        assertEquals(mapOf("2026-01-01" to 3), db["b"])
    }

    @Test
    fun countEntries_streamMatchesInMemory() {
        val db = sampleDb()
        val expected = db.values.sumOf { it.size }
        assertEquals(expected, HabitsDbCodec.countEntries(db))
        assertEquals(expected, HabitsDbCodec.countEntries(StringReader(encode(db))))
        assertEquals(0, HabitsDbCodec.countEntries(StringReader("{}")))
    }

    @Test
    fun countEntries_throwsOnTruncatedDocument() {
        val full = encode(sampleDb())
        val truncated = full.substring(0, full.length / 2)
        try {
            HabitsDbCodec.countEntries(StringReader(truncated))
            fail("expected an exception on truncated JSON")
        } catch (_: Exception) {
            // expected: caller treats this as "unreadable" and fails closed
        }
    }

    @Test
    fun read_throwsOnForeignShape() {
        try {
            HabitsDbCodec.read(StringReader("""{"a":{"2026-01-01":"notAnInt"}}"""))
            fail("expected an exception on non-integer value")
        } catch (_: Exception) {
        }
        try {
            HabitsDbCodec.read(StringReader("""[1,2,3]"""))
            fail("expected an exception on array root")
        } catch (_: Exception) {
        }
    }

    @Test
    fun gsonAlsoRejectsForeignShape_sanity() {
        try {
            gson.fromJson<Map<String, Map<String, Int>>>("""[1,2,3]""", dbType)
            fail()
        } catch (_: JsonSyntaxException) {
        }
    }
}
