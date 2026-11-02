package com.jarvis.assistant

import com.jarvis.assistant.data.AndroidSettingsStore
import com.jarvis.assistant.data.AppLog
import com.jarvis.assistant.data.SecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretsAndLogInstrumentedTest {
    private val ctx = TestSupport.context
    private val secrets = SecretStore(ctx)

    @After fun cleanup() { secrets.put("GroqAPIKey", null); secrets.put("TestSecret", null) }

    @Test fun secretRoundTripsAndOnlyCiphertextIsOnDisk() {
        val key = "gsk_" + "A1b2C3d4E5f6G7h8I9j0K1l2M3n4"
        assertTrue(secrets.put("GroqAPIKey", key))
        assertEquals(key, secrets.get("GroqAPIKey"))
        val raw = ctx.getSharedPreferences("jarvis_secrets", 0).all.values.joinToString()
        assertFalse("the key must never be stored in plain text", raw.contains(key))
        assertFalse(raw.contains("A1b2C3d4"))
    }

    @Test fun blankValueRemovesTheSecret() {
        secrets.put("TestSecret", "x1y2z3")
        secrets.put("TestSecret", "  ")
        assertNull(secrets.get("TestSecret"))
        assertFalse(secrets.has("TestSecret"))
    }

    @Test fun aTamperedValueIsClearedAndReported() {
        secrets.put("TestSecret", "x1y2z3")
        ctx.getSharedPreferences("jarvis_secrets", 0).edit().putString("TestSecret", "AAAA:BBBB").commit()
        assertNull(secrets.get("TestSecret"))
        assertTrue("TestSecret" in secrets.lostSecrets())
    }

    @Test fun exportNeverContainsSecrets() {
        val store = AndroidSettingsStore(ctx, secrets)
        store.putSecret("GroqAPIKey", "gsk_supersecretvalue1234567890")
        store.put("Username", "Tony")
        val json = store.export()
        assertTrue(json.contains("Tony"))
        assertFalse(json.contains("supersecret"))
        assertFalse(json.contains("GroqAPIKey"))
        // importing a file that tries to smuggle a secret in is ignored
        val n = store.import("""{"app":"jarvis","version":1,"settings":{"Username":"Pepper","GroqAPIKey":"gsk_evil"}}""")
        assertEquals(1, n)
        assertEquals("Pepper", store.get("Username"))
        store.put("Username", null)
    }

    @Test fun logLinesAreRedactedBeforeTheyReachTheFile() {
        val log = AppLog(ctx)
        log.clear()
        log.i("Test", "calling +91 98765 43210 with Bearer abcdef1234567890abcdef and key gsk_abcdefghijklmnop1234 for tony@stark.com")
        val tail = log.readTail()
        assertTrue(tail.contains("Test"))
        assertFalse(tail.contains("98765 43210"))
        assertFalse(tail.contains("abcdef1234567890"))
        assertFalse(tail.contains("gsk_abcdefghijklmnop"))
        assertFalse(tail.contains("tony@stark.com"))
        log.clear()
    }
}
