package com.jarvis.core.island

import com.jarvis.core.engine.EngineEvent
import com.jarvis.core.engine.EngineState
import com.jarvis.core.engine.NoticeLevel
import com.jarvis.core.model.AssistantReply
import com.jarvis.core.model.Origin
import com.jarvis.core.ports.ImageEvent
import com.jarvis.core.ports.ImageEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandReducerTest {
    private val r = IslandReducer(collapseMs = { 4000 })
    private var now = 1_000L
    private fun step(s: IslandUiState, i: IslandInput, advance: Long = 0): IslandUiState { now += advance; return r.reduce(s, i, now) }
    private fun said(text: String, sensitive: Boolean = false, icon: String = "check") =
        IslandInput.Event(EngineEvent.AssistantSaid(AssistantReply(text, icon = icon, sensitive = sensitive), Origin.WAKE))

    @Test
    fun startsHidden() = assertEquals(IslandMode.HIDDEN, IslandUiState().mode)

    @Test
    fun listeningThenThinkingThenSpeakingThenReplyThenHidden() {
        var s = step(IslandUiState(), IslandInput.Engine(EngineState.LISTENING))
        assertEquals(IslandMode.LISTENING, s.mode)
        s = step(s, IslandInput.Event(EngineEvent.UserSaid("what time is it", Origin.WAKE)))
        assertEquals("what time is it", s.text)
        s = step(s, IslandInput.Engine(EngineState.THINKING))
        assertEquals(IslandMode.THINKING, s.mode)
        s = step(s, said("It is 3 pm."))
        assertEquals(IslandMode.REPLY, s.mode)
        assertEquals("It is 3 pm.", s.text)
        s = step(s, IslandInput.Engine(EngineState.SPEAKING))
        assertEquals(IslandMode.SPEAKING, s.mode)
        assertEquals("It is 3 pm.", s.text)
        s = step(s, IslandInput.Engine(EngineState.IDLE))
        assertEquals("the answer stays visible after speech ends", IslandMode.REPLY, s.mode)
        s = step(s, IslandInput.Tick, 3900)
        assertEquals(IslandMode.REPLY, s.mode)
        s = step(s, IslandInput.Tick, 200)
        assertEquals("collapses after the configured time", IslandMode.HIDDEN, s.mode)
    }

    @Test
    fun idleAfterListeningWithNoReplyHidesTheIsland() {
        var s = step(IslandUiState(), IslandInput.Engine(EngineState.LISTENING))
        s = step(s, IslandInput.Engine(EngineState.IDLE))
        assertEquals(IslandMode.HIDDEN, s.mode)
    }

    @Test
    fun tapExpandsAndExtendsTheTimeoutThreefold() {
        var s = step(IslandUiState(), said("hello"))
        s = step(s, IslandInput.Tap)
        assertTrue(s.expanded)
        s = step(s, IslandInput.Tick, 11_000)
        assertEquals("still open at 11 s (limit 12 s expanded)", IslandMode.REPLY, s.mode)
        s = step(s, IslandInput.Tick, 1_500)
        assertEquals(IslandMode.HIDDEN, s.mode)
        assertFalse(s.expanded)
    }

    @Test
    fun tapTwiceCollapsesAgainAndTapOnHiddenDoesNothing() {
        var s = step(IslandUiState(), said("hello"))
        s = step(step(s, IslandInput.Tap), IslandInput.Tap)
        assertFalse(s.expanded)
        assertEquals(IslandUiState(), step(IslandUiState(), IslandInput.Tap))
    }

    @Test
    fun closeHidesImmediately() {
        var s = step(IslandUiState(), said("hello"))
        s = step(s, IslandInput.Tap)
        s = step(s, IslandInput.Close)
        assertEquals(IslandMode.HIDDEN, s.mode)
        assertFalse(s.expanded)
    }

    @Test
    fun lockedPhoneNeverShowsPersonalReplyText() {
        var s = step(IslandUiState(), IslandInput.Lock(true))
        s = step(s, said("Mom's number is 555-0100", sensitive = true))
        assertFalse(s.text.contains("555"))
        assertTrue(s.text.contains("Unlock"))
        assertFalse(s.lastReply.contains("555"))
        val plain = step(s, said("It is sunny.", sensitive = false))
        assertEquals("non-personal replies may still be shown", "It is sunny.", plain.text)
    }

    @Test
    fun lockedPhoneHidesWhatTheUserSaidAndReminderText() {
        var s = step(IslandUiState(), IslandInput.Lock(true))
        s = step(s, IslandInput.Engine(EngineState.LISTENING))
        s = step(s, IslandInput.Event(EngineEvent.UserSaid("call my doctor", Origin.WAKE)))
        assertEquals("", s.text)
        s = step(s, IslandInput.Event(EngineEvent.ReminderFired("1", "take pills", false)))
        assertFalse(s.text.contains("pills"))
        assertEquals("Reminder", s.title)
    }

    @Test
    fun unlockedReminderAndTimerShowTheirText() {
        var s = step(IslandUiState(), IslandInput.Event(EngineEvent.ReminderFired("1", "take pills", false)))
        assertEquals(IslandMode.NOTICE, s.mode)
        assertEquals("take pills", s.text)
        assertEquals("bell", s.icon)
        s = step(IslandUiState(), IslandInput.Event(EngineEvent.ReminderFired("2", "Tea", true)))
        assertEquals("Timer", s.title)
        assertEquals("clock", s.icon)
    }

    @Test
    fun noticesDoNotInterruptListeningButErrorsAreShownWhenIdle() {
        var s = step(IslandUiState(), IslandInput.Engine(EngineState.LISTENING))
        val keep = step(s, IslandInput.Event(EngineEvent.Notice(NoticeLevel.INFO, "Hi", "message")))
        assertEquals(IslandMode.LISTENING, keep.mode)
        s = step(IslandUiState(), IslandInput.Event(EngineEvent.Notice(NoticeLevel.ERROR, "Voice output", "no sound")))
        assertEquals(IslandMode.ERROR, s.mode)
        assertEquals("no sound", s.text)
    }

    @Test
    fun imageGenerationShowsAProgressRingThenAReadyNotice() {
        var s = step(IslandUiState(), IslandInput.Event(EngineEvent.Image(ImageEvent(ImageEventType.START, "j1", prompt = "a red fox"))))
        assertEquals(IslandMode.IMAGE, s.mode)
        assertEquals("a red fox", s.text)
        val p0 = s.progress!!
        s = step(s, IslandInput.Event(EngineEvent.Image(ImageEvent(ImageEventType.STAGE, "j1", message = "Rendering"))))
        assertTrue(s.progress!! > p0 && s.progress!! <= 0.9f)
        s = step(s, IslandInput.Tick, 5 * 60_000L)
        assertEquals("a long image job is not cut short by the short collapse time", IslandMode.IMAGE, s.mode)
        s = step(s, IslandInput.Event(EngineEvent.Image(ImageEvent(ImageEventType.DONE, "j1"))))
        assertEquals(IslandMode.NOTICE, s.mode)
        assertEquals(null, s.progress)
        s = step(s, IslandInput.Event(EngineEvent.Image(ImageEvent(ImageEventType.ERROR, "j2", message = "no provider"))))
        assertEquals(IslandMode.ERROR, s.mode)
    }

    @Test
    fun levelsOnlyMatterWhileListeningAndAreClamped() {
        var s = step(IslandUiState(), IslandInput.Engine(EngineState.LISTENING))
        s = step(s, IslandInput.Level(2.5f))
        assertEquals(1f, s.level, 0f)
        s = step(s, IslandInput.Level(-1f))
        assertEquals(0f, s.level, 0f)
        val thinking = step(s, IslandInput.Engine(EngineState.THINKING))
        assertEquals(thinking, step(thinking, IslandInput.Level(0.7f)))
    }

    @Test
    fun voiceServiceCuesDriveTheIslandBeforeTheEngineReportsAnything() {
        var s = step(IslandUiState(), IslandInput.Cue(CueKind.LISTENING, null, locked = true))
        assertEquals(IslandMode.LISTENING, s.mode)
        assertTrue(s.locked)
        s = step(s, IslandInput.Cue(CueKind.HEARD, "open spotify", locked = false))
        assertEquals("open spotify", s.text)
        s = step(s, IslandInput.Cue(CueKind.FAILED, "I didn't hear a command.", locked = false))
        assertEquals(IslandMode.ERROR, s.mode)
        assertEquals("I didn't hear a command.", s.text)
        s = step(IslandUiState(), IslandInput.Cue(CueKind.DONE, "Done.", locked = false))
        assertEquals(IslandMode.REPLY, s.mode)
    }

    @Test
    fun aStuckActiveModeIsHiddenByTheWatchdog() {
        var s = step(IslandUiState(), IslandInput.Engine(EngineState.THINKING))
        s = step(s, IslandInput.Tick, 89_000)
        assertEquals(IslandMode.THINKING, s.mode)
        s = step(s, IslandInput.Tick, 2_000)
        assertEquals(IslandMode.HIDDEN, s.mode)
    }

    @Test
    fun previewShowsASampleReplyThatCollapses() {
        var s = step(IslandUiState(), IslandInput.Preview("This is how the island looks."))
        assertEquals(IslandMode.REPLY, s.mode)
        assertEquals("This is how the island looks.", s.text)
        s = step(s, IslandInput.Tick, 4001)
        assertEquals(IslandMode.HIDDEN, s.mode)
    }

    @Test
    fun errorStateFromTheEngineIsShown() {
        val s = step(IslandUiState(), IslandInput.Engine(EngineState.ERROR))
        assertEquals(IslandMode.ERROR, s.mode)
        assertNotNull(s.title)
    }

    @Test
    fun needsTickOnlyWhileVisible() {
        assertFalse(r.needsTick(IslandUiState()))
        assertTrue(r.needsTick(IslandUiState(mode = IslandMode.REPLY)))
    }
}
