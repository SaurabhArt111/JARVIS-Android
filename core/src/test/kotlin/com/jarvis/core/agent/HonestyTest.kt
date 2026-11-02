package com.jarvis.core.agent

import org.junit.Assert.*
import org.junit.Test

class HonestyTest {
    @Test fun falseClaimsAreCaught() {
        for (s in listOf(
            "Opening Spotify. Enjoy!", "I've set a reminder for 5 pm.", "Done, I have muted your phone.", "The volume has been set to 40.",
            "Sure, launching YouTube now.", "I've added that to your notes.", "I just sent the message to Mom.", "Your reminder was added.",
            "Alright, I'm turning on the flashlight.", "Okay, calling Mom.",
        )) assertTrue("should be a claim: $s", Honesty.claimsAction(s))
    }

    @Test fun ordinaryTalkIsNotAClaim() {
        for (s in listOf(
            "Playing the piano is relaxing.", "Say 'open Spotify' and I'll start it.", "Spotify is a music streaming service.",
            "I can't open apps from a chat answer.", "The capital of France is Paris.", "Opening a bank account usually requires ID.",
            "", "   ",
        )) assertFalse("should not be a claim: $s", Honesty.claimsAction(s))
    }

    @Test fun commandsNeedATool() {
        for (s in listOf(
            "open spotify", "set the volume to 40", "hey jarvis play faded", "could you please mute the phone", "remind me to call mom at 5",
            "make a note to buy milk", "create a reminder", "what are my reminders", "what's the weather", "which song is playing",
            "read my notifications", "any reminders today?", "turn on the flashlight", "go home", "take a screenshot",
        )) assertTrue("needs a tool: $s", Honesty.needsTool(s))
    }

    @Test fun conversationDoesNotNeedATool() {
        for (s in listOf("tell me a joke", "who wrote Hamlet", "explain how photosynthesis works", "why is the sky blue", "thanks", "")) assertFalse("no tool: $s", Honesty.needsTool(s))
    }

    @Test fun garbledOutputIsRecognised() {
        assertTrue(Honesty.garbled("." .repeat(80)))
        assertTrue(Honesty.garbled("ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok ok"))
        assertTrue(Honesty.garbled("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!"))
        assertTrue(Honesty.garbled("a ... ... ... ... ... ... ... ... ... end of the line here"))
    }

    @Test fun normalAnswersAreNotGarbled() {
        assertFalse(Honesty.garbled("Sure — the meeting is at 5 pm tomorrow, and I will remind you an hour before."))
        assertFalse(Honesty.garbled("Hi"))
        assertFalse(Honesty.garbled(null))
    }

    @Test fun cleanStripsMarkdownAndTags() {
        assertEquals("Hello world", Honesty.clean("**Hello** <br/>world".replace("<br/>", "")).replace("  ", " "))
        assertEquals("a\n\nb", Honesty.clean("a\n\n\n\n\nb"))
        assertEquals("x", Honesty.clean("<s>x</s>"))
    }

    @Test fun hedgeSentencesPickOutCaveats() {
        val t = "Message ready for Mom. I can't confirm it was sent — tap send in WhatsApp."
        val h = Honesty.hedgeSentences(t)
        assertTrue(h.contains("tap send")); assertFalse(h.contains("Message ready"))
    }

    @Test fun fallbackNeverSaysSomethingWasDone() {
        assertTrue(Honesty.HONEST_FALLBACK.startsWith("I haven't actually done that"))
        assertFalse(Honesty.claimsAction(Honesty.HONEST_FALLBACK))
    }
}

class HonestyNegationTest {
    @Test fun denialsAreNotClaims() {
        for (s in listOf("Nothing was opened.", "I haven't set a reminder yet.", "I couldn't open Spotify, so nothing was launched.", "I did not send the message.", "The timer was never started."))
            assertFalse(s, Honesty.claimsAction(s))
    }

    @Test fun aClaimNextToADenialIsStillACatch() {
        assertTrue(Honesty.claimsAction("I couldn't open Spotify, but I've set the volume to 40."))
        assertTrue(Honesty.claimsAction("Opening Chrome. I did not find Edge."))
    }
}
