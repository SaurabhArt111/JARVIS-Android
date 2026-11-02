package com.jarvis.core.util

import org.junit.Assert.*
import org.junit.Test

class LogRedactorTest {
    private fun r(s: String) = LogRedactor.redact(s)

    @Test fun bearerTokensAndAuthorizationHeaders() {
        assertFalse(r("Authorization: Bearer abc123DEF456ghi789").contains("abc123"))
        assertTrue(r("sent Bearer abc123DEF456ghi789 to server").contains("Bearer [redacted]"))
    }

    @Test fun keyValuePairsInAllCommonShapes() {
        for (s in listOf("api_key=SECRETVALUE123", "\"apiKey\": \"SECRETVALUE123\"", "token: SECRETVALUE123", "password=SECRETVALUE123&x=1", "x-api-key: SECRETVALUE123"))
            assertFalse(s, r(s).contains("SECRETVALUE123"))
        assertTrue(r("q=weather&api_key=SECRETVALUE123&num=5").contains("&num=5"))
    }

    @Test fun wellKnownKeyShapes() {
        for (k in listOf("gsk_SECRETGROQ1234567890abcd", "sk-SECRETOPENAI1234567890abcd", "hf_SECRETHF1234567890abcdef", "AIzaSyD-SECRET1234567890abcdefgh", "ghp_SECRETGITHUB1234567890abcd"))
            assertFalse(k, r("calling with $k now").contains("SECRET"))
    }

    @Test fun longOpaqueTokensAreMasked() {
        assertFalse(r("id 0123456789abcdef0123456789abcdef0123456789").contains("0123456789abcdef0123"))
    }

    @Test fun emailsAndPhoneNumbers() {
        assertEquals("mail [email] now", r("mail asha.k+x@example.co.in now"))
        for (n in listOf("+91 98765 43210", "(555) 010-9999", "9876543210", "+1-555-010-9999", "022 5555 0000"))
            assertTrue(n, r("calling $n please").contains("[number]"))
    }

    @Test fun ordinaryNumbersAndWordsSurvive() {
        assertEquals("handled in 123 ms, 3 steps, volume 40", r("handled in 123 ms, 3 steps, volume 40"))
        assertEquals("AI groq failed (RATE): rate limiting", r("AI groq failed (RATE): rate limiting"))
        assertEquals("2026-10-25T10:00 reminder r1", r("2026-10-25T10:00 reminder r1"))
    }

    @Test fun longLinesAreClipped() {
        val s = r("word ".repeat(400)); assertTrue(s.length < 700); assertTrue(s.contains("chars]"))
    }

    @Test fun textHelperHidesContent() {
        assertEquals("[11 chars]", LogRedactor.text("hello world"))
    }
}
