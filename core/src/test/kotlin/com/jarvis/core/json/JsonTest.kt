package com.jarvis.core.json

import org.junit.Assert.*
import org.junit.Test

class JsonTest {
    @Test fun parsesNestedStructures() {
        val v = Json.parse("""{"a":1,"b":[true,null,"x\nA"],"c":{"d":-2.5e1}}""")
        assertEquals(1L, Json.path(v, "a"))
        assertEquals(listOf(true, null, "x\nA"), Json.path(v, "b"))
        assertEquals(-25.0, Json.path(v, "c", "d"))
    }

    @Test fun roundTripsAndEscapes() {
        val src = mapOf("s" to "quote\" back\\ tab\t ctrl\u0001", "n" to 3, "l" to listOf(1.5, false, null))
        val parsed = Json.parse(Json.stringify(src)) as Map<*, *>
        assertEquals(src["s"], parsed["s"])
        assertEquals(3L, parsed["n"])
        assertEquals(listOf(1.5, false, null), parsed["l"])
    }

    @Test fun malformedInputNeverThrowsFromLenientParsers() {
        assertNull(Json.parseObjectOrNull("{bad"))
        assertNull(Json.parseObjectOrNull(null))
        assertEquals("v", Json.parseObjectLenient("Sure! {\"k\":\"v\"} done")!!["k"])
        assertNull(Json.parseObjectLenient("no json here"))
    }

    @Test fun strictParseRejectsTrailingGarbage() {
        try { Json.parse("{} x"); fail() } catch (e: Json.JsonException) { /* expected */ }
        try { Json.parse("[1,2"); fail() } catch (e: Json.JsonException) { /* expected */ }
    }

    @Test fun prettyPrintIsParseable() {
        val txt = Json.stringify(mapOf("a" to listOf(1, 2), "b" to emptyMap<String, Any>()), pretty = true)
        assertTrue(txt.contains("\n"))
        assertEquals(listOf(1L, 2L), Json.path(Json.parse(txt), "a"))
    }

    @Test fun accessorsAreTolerant() {
        assertEquals(7, Json.int("7"))
        assertEquals(true, Json.bool("yes"))
        assertNull(Json.bool("maybe"))
        assertEquals("5", Json.str(5L))
    }
}

class JsonRepairTest {
    private fun lenient(s: String?) = com.jarvis.core.json.Json.parseObjectLenient(s)

    @org.junit.Test fun singleQuotesAndTrailingCommas() {
        assertEquals(mapOf("action" to "mute", "n" to 3L), lenient("{'action': 'mute', 'n': 3,}"))
    }

    @org.junit.Test fun pythonConstants() {
        assertEquals(mapOf("a" to true, "b" to false, "c" to null), lenient("{'a': True, 'b': False, 'c': None}"))
    }

    @org.junit.Test fun quotesInsideStringsSurvive() {
        assertEquals(mapOf("text" to "it's \"fine\""), lenient("{'text': 'it\\'s \"fine\"'}"))
        assertEquals(mapOf("text" to "True story, None left"), lenient("{'text': 'True story, None left'}"))
    }

    @org.junit.Test fun proseAroundTheBlockIsIgnored() {
        assertEquals(mapOf("x" to 1L), lenient("Sure! Here you go: {\"x\": 1} hope that helps"))
    }

    @org.junit.Test fun hopelessInputIsNull() {
        assertNull(lenient("{{{ nope")); assertNull(lenient("no braces")); assertNull(lenient(null))
    }
}
