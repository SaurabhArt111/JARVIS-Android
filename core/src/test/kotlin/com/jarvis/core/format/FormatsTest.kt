package com.jarvis.core.format

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse

class FormatsTest {
    @Test fun clockShowsMinutesAndSecondsThenHours() {
        assertEquals("0:00", Formats.clock(0))
        assertEquals("1:23", Formats.clock(83_000))
        assertEquals("59:59", Formats.clock(3_599_000))
        assertEquals("1:00:00", Formats.clock(3_600_000))
        assertEquals("1:02:05", Formats.clock(3_725_000))
    }

    @Test fun clockRoundsToTheNearestSecondAndNeverGoesNegative() {
        assertEquals("0:01", Formats.clock(600))
        assertEquals("0:00", Formats.clock(400))
        assertEquals("0:00", Formats.clock(-5_000))
    }

    @Test fun countdownIsTimeLeft() {
        assertEquals("0:42", Formats.countdown(1_042_000, 1_000_000))
        assertEquals("0:00", Formats.countdown(1_000_000, 1_500_000))
    }

    @Test fun bytesUseBinarySteps() {
        assertEquals("0 B", Formats.bytes(0))
        assertEquals("1023 B", Formats.bytes(1023))
        assertEquals("1.0 KB", Formats.bytes(1024))
        assertEquals("1.5 KB", Formats.bytes(1536))
        assertEquals("12 KB", Formats.bytes(12 * 1024))
        assertEquals("2.5 MB", Formats.bytes((2.5 * 1024 * 1024).toLong()))
        assertEquals("3.0 GB", Formats.bytes(3L * 1024 * 1024 * 1024))
    }

    @Test fun oneLineCollapsesWhitespaceAndCuts() {
        assertEquals("a b c", Formats.oneLine("  a\n b\t\tc "))
        val cut = Formats.oneLine("x".repeat(100), 20)
        assertEquals(20, cut.length)
        assertEquals('…', cut.last())
        assertEquals("short", Formats.oneLine("short", 20))
    }

    @Test fun maskedSecretsRevealAtMostTheLastFourCharacters() {
        assertEquals("", Formats.maskSecret(""))
        assertEquals("••••••••", Formats.maskSecret("short-key"))
        val k = "gsk_abcdefghijklmnop1234"
        val m = Formats.maskSecret(k)
        assertEquals("••••••••1234", m)
        assertFalse(m.contains("abcdef"))
    }
}
