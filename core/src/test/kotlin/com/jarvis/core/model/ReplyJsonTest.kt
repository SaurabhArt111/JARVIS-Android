package com.jarvis.core.model

import org.junit.Assert.*
import org.junit.Test

class ReplyJsonTest {
    private val full = AssistantReply(
        "Timer set for 5 minutes.", ok = true, kind = ReplyKind.ACTION, icon = "clock",
        card = Card("timer", "5:00", "Pasta", "clock", listOf(CardItem("a", "b")), "https://e.com", mapOf("k" to "v")),
        speak = false, actions = listOf(UiAction("Grant", ActionType.GRANT, mapOf("requirement" to "NOTIFICATIONS"))),
        missing = listOf(Requirement.NOTIFICATIONS, Requirement.EXACT_ALARM), sensitive = true,
        extras = mapOf("expect_reply" to true, "n" to 3, "list" to listOf("a", 1), "nested" to mapOf("x" to 1.5), "obj" to Object()),
    )

    @Test fun roundTripKeepsEverythingJsonSafe() {
        val back = ReplyJson.fromJson(ReplyJson.toJson(full))!!
        assertEquals(full.copy(extras = emptyMap()), back.copy(extras = emptyMap()))
        assertEquals(true, back.extras["expect_reply"]); assertEquals(3L, back.extras["n"]); assertEquals(listOf("a", 1L), back.extras["list"])
        assertFalse("non-JSON values are dropped, not stringified", "obj" in back.extras)
    }

    @Test fun unreadableRowsAreNullNotCrashes() {
        assertNull(ReplyJson.fromJson(null)); assertNull(ReplyJson.fromJson("not json")); assertNull(ReplyJson.fromJson("{}"))
    }

    @Test fun unknownEnumValuesFallBackSafely() {
        val r = ReplyJson.fromJson("""{"text":"hi","kind":"FUTURE_KIND","actions":[{"label":"x","type":"FUTURE"},{"label":"ok","type":"CONFIRM"}],"missing":["NOPE","MICROPHONE"]}""")!!
        assertEquals(ReplyKind.ACTION, r.kind); assertEquals(listOf("ok"), r.actions.map { it.label }); assertEquals(listOf(Requirement.MICROPHONE), r.missing)
    }

    @Test fun minimalRowsGetDefaults() {
        val r = ReplyJson.fromJson("""{"text":"hi"}""")!!
        assertTrue(r.ok && r.speak && !r.sensitive); assertNull(r.card); assertEquals("check", r.icon)
    }
}
