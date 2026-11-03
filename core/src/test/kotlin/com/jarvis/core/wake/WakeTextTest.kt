package com.jarvis.core.wake

import org.junit.Assert.*
import org.junit.Test

class WakeTextTest {
    private val jarvis = listOf("jarvis")

    @Test fun extractsTheCommandAfterTheWakeWord() {
        assertEquals(WakeText.Match(true, "open spotify"), WakeText.extractCommand("hey jarvis open spotify", jarvis))
        assertEquals(WakeText.Match(true, "what time is it"), WakeText.extractCommand("Jarvis, what time is it", jarvis))
        assertEquals(WakeText.Match(true, "play faded"), WakeText.extractCommand("ok jarvis: play faded", jarvis))
    }

    @Test fun aloneTheWakeWordGivesAnEmptyCommand() {
        assertEquals(WakeText.Match(true, ""), WakeText.extractCommand("hey jarvis", jarvis))
        assertEquals(WakeText.Match(true, ""), WakeText.extractCommand("jarvis!", jarvis))
    }

    @Test fun misheardSpellingsStillWake() {
        for (w in listOf("jervis", "jarvas", "gervis", "jarvice", "jarvus")) assertTrue(w, WakeText.extractCommand("hey $w open maps", jarvis).matched)
    }

    @Test fun wakeWordInTheMiddleDoesNotCount() {
        assertFalse(WakeText.extractCommand("tell jarvis to open maps", jarvis).matched)
        assertFalse(WakeText.extractCommand("open maps", jarvis).matched)
        assertFalse(WakeText.extractCommand("jarvisfoo open maps", jarvis).matched)
    }

    @Test fun customWakeWordsWork() {
        assertEquals("open maps", WakeText.extractCommand("hey friday open maps", listOf("friday")).rest)
        assertTrue(WakeText.extractCommand("nul open maps", listOf("null")).matched)
        assertFalse(WakeText.extractCommand("hey jarvis open maps", emptyList()).matched)
    }

    @Test fun regexMetaCharactersInWakeWordsAreHarmless() {
        assertFalse(WakeText.extractCommand("anything", listOf(".*")).matched)
        assertTrue(WakeText.extractCommand("c++ open maps", listOf("c++")).matched)
    }

    @Test fun nullAndBlankAreSafe() {
        assertFalse(WakeText.extractCommand(null, jarvis).matched); assertFalse(WakeText.extractCommand("  ", jarvis).matched)
    }

    @Test fun danglingCommandsKeepTheMicOpen() {
        for (s in listOf("close the", "what's the", "open", "set the volume to", "remind me to", "play")) assertTrue(s, WakeText.isDangling(s))
        for (s in listOf("open spotify", "set the volume to 40", "what time is it", "", "thanks")) assertFalse(s, WakeText.isDangling(s))
        assertFalse(WakeText.isDangling(null))
    }

    @Test fun commonMishearingsOfJarvisStillWake() {
        for (s in listOf("hey jarvish open maps", "jarwis open maps", "hey jar vis open maps", "hey jarvez open maps", "hey, Jarvis! open maps"))
            assertEquals(s, "open maps", WakeText.extractCommand(s, jarvis).rest)
    }

    @Test fun ordinaryWordsWakeOnlyAfterAGreeting() {
        assertTrue(WakeText.extractCommand("hey service open maps", jarvis).matched)
        assertTrue(WakeText.extractCommand("ok travis what time is it", jarvis).matched)
        assertFalse("talking about a service must not wake it", WakeText.extractCommand("service is down again", jarvis).matched)
        assertFalse(WakeText.extractCommand("a service call tomorrow", jarvis).matched)
    }

    @Test fun bareJarvisStillWorks() = assertEquals("what time is it", WakeText.extractCommand("jarvis what time is it", jarvis).rest)
}
