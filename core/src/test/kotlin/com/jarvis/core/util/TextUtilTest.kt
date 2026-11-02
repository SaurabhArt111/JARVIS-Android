package com.jarvis.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TextUtilTest {
    private val wake = listOf("jarvis")

    @Test fun stripsWakeWordAndKeepsCapitalisation() =
        assertEquals("Remind me to call Mom at 5 pm", stripWake("Hey Jarvis, Remind me to call Mom at 5 pm", wake))

    @Test fun stripsPoliteFillerAtBothEnds() =
        assertEquals("make a note buy milk", stripWake("Jarvis please make a note buy milk please", wake))

    @Test fun leavesPlainTextAlone() = assertEquals("what time is it", stripWake("what time is it", wake))

    @Test fun nullAndBlankAreEmpty() {
        assertEquals("", stripWake(null, wake))
        assertEquals("", stripWake("  ...  ", wake))
    }

    @Test fun wakeWordWithRegexCharactersIsEscaped() = assertEquals("hello", stripWake("a.b. hello", listOf("a.b.")))
}
