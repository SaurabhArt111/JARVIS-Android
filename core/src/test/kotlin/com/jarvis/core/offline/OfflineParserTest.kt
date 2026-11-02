package com.jarvis.core.offline

import com.jarvis.core.agent.ToolExecutor
import com.jarvis.core.lock.LockPolicy
import com.jarvis.core.lock.LockState
import com.jarvis.core.model.Origin
import com.jarvis.core.offline.OfflineParser.Step
import com.jarvis.core.organizer.Organizer
import com.jarvis.core.testing.ToolHarness
import com.jarvis.core.tools.ToolContext
import org.junit.Assert.*
import org.junit.Test

class OfflineParserTest {
    private val h = ToolHarness()
    private val env = h.env
    private val executor = ToolExecutor(h.registry, env.services, LockPolicy(env.settings, env.clock))
    private val parser = OfflineParser(env.services, Organizer(env.services)) { n, a, c -> executor.execute(n, a, c) }

    private fun plan(text: String) = parser.plan(text, h.ctx())
    private fun invokes(text: String) = plan(text)!!.steps.filterIsInstance<Step.Invoke>()
    private fun single(text: String): Step.Invoke = invokes(text).single()
    private fun args(text: String) = single(text).args

    @Test fun timeAndDateAreAnsweredWithoutAnyTool() {
        val s = plan("what time is it")!!.steps.single() as Step.Say
        assertTrue(s.reply.text, s.reply.text.startsWith("It's 10:00 AM"))
        val d = plan("what's the date")!!.steps.single() as Step.Say
        assertTrue(d.reply.text.contains("Sunday, 25 October 2026"))
    }

    @Test fun greetingsAndJokesStayOffline() {
        assertTrue((plan("hello")!!.steps.single() as Step.Say).reply.text.isNotBlank())
        assertTrue((plan("tell me a joke")!!.steps.single() as Step.Say).reply.text.isNotBlank())
    }

    @Test fun volumeAndBrightness() {
        assertEquals("volume_set" to "40", single("set the volume to 40").let { it.args["action"] to it.args["value"] })
        assertEquals("brightness_set" to "70", single("brightness 70").let { it.args["action"] to it.args["value"] })
        assertEquals("volume_up", args("volume up")["action"])
        assertEquals("volume_down", args("lower the volume")["action"])
        assertEquals("brightness_up", args("brighter")["action"])
        assertEquals("mute", args("mute")["action"]); assertEquals("unmute", args("unmute")["action"])
    }

    @Test fun flashlightAndDnd() {
        assertEquals("flashlight_on", args("turn on the flashlight")["action"])
        assertEquals("flashlight_off", args("torch off")["action"])
        assertEquals("dnd_on", args("do not disturb on")["action"])
    }

    @Test fun openingThingsAndTheirRouting() {
        assertEquals("open" to "spotify", single("open spotify").let { it.tool to it.args["target"] })
        assertEquals("youtube" to "brave", single("open youtube in brave").args.let { it["target"] to it["in_app"] })
        assertEquals("wifi settings", args("open wifi settings")["target"])
    }

    @Test fun announcingAnIntentIsARequest() {
        assertEquals("spotify", args("i'm going to open spotify now")["target"])
        assertEquals("open", single("let's open spotify").tool)
        assertEquals("gta vice city", args("i'm going to play gta vice city")["query"])
    }

    @Test fun playingMusic() {
        assertEquals("faded", args("play faded")["query"])
        assertEquals("faded" to "spotify", single("play faded on spotify").args.let { it["query"] to it["where"] })
        assertEquals("pause", args("pause")["action"])
        assertEquals("next", args("skip")["action"]); assertEquals("previous", args("previous song")["action"])
        assertEquals("now_playing", args("what's playing")["action"])
    }

    @Test fun timersAndAlarms() {
        assertEquals("5 minutes", args("set a timer for 5 minutes")["duration"])
        assertEquals("stop", args("stop the timer")["action"])
        assertEquals("alarm", single("set an alarm for 6:30 am").tool)
        assertEquals("6:30 am", args("set an alarm for 6:30 am")["time"])
        assertEquals("tomorrow at 7", args("wake me up at tomorrow at 7")["time"])
    }

    @Test fun callsAndMessages() {
        assertEquals("mom", args("call mom")["contact"])
        assertNull("'call me' is not a contact", plan("call me"))
        val m = args("whatsapp mom saying running late")
        assertEquals(listOf("whatsapp", "mom", "running late"), listOf(m["app"], m["to"], m["text"]))
        val m2 = args("send a message to dad saying hello there")
        assertEquals(listOf("dad", "hello there"), listOf(m2["to"], m2["text"]))
    }

    @Test fun notificationsAndReplies() {
        assertEquals("read_notifications", single("read my notifications").tool)
        assertEquals("Asha" to "on my way", single("reply to Asha with on my way").args.let { it["app"] to it["text"] })
    }

    @Test fun navigationAndClipboard() {
        assertEquals("home", args("go home")["action"]); assertEquals("back", args("go back")["action"])
        assertEquals("recents", args("recent apps")["action"]); assertEquals("screenshot", args("take a screenshot")["action"])
        assertEquals("clipboard", args("what did i copy")["action"]); assertEquals("battery", args("how's my battery")["action"])
    }

    @Test fun weatherParsesTheCity() {
        assertEquals("mumbai", args("weather in mumbai")["city"])
        assertEquals("", args("what's the weather")["city"])
        assertNull("opening the weather app is not a forecast", plan("open weather settings")?.steps?.filterIsInstance<Step.Invoke>()?.firstOrNull { it.tool == "weather" })
    }

    @Test fun calculatorAndDivisionByZero() {
        assertEquals("calculate", single("what's 12 times 5").tool)
        val z = plan("10 / 0")!!.steps.single() as Step.Say
        assertTrue(z.reply.text.contains("divide by zero"))
    }

    @Test fun searchAndYouTube() {
        assertEquals("youtube", args("search lofi beats on youtube")["site"])
        assertEquals("google", args("search kotlin coroutines")["site"])
        assertNull("'find my files' is a library question, not a web search", plan("find my files")?.steps?.filterIsInstance<Step.Invoke>()?.firstOrNull { it.tool == "search_in_browser" })
    }

    @Test fun notesAndRemindersGoToTheOrganizer() {
        assertTrue(plan("remind me to call mom at 5 pm")!!.steps.single() is Step.Organize)
        assertTrue(plan("make a note buy milk and eggs")!!.steps.single() is Step.Organize)
    }

    @Test fun adjustAsksAFollowUpQuestion() {
        val p = plan("adjust the volume")!!
        val say = p.steps.single() as Step.Say
        assertTrue(say.reply.extras["expect_reply"] == true)
        assertNotNull(h.dialog.waiting())
        val answer = kotlinx.coroutines.runBlocking { h.dialog.resume("make it 25") }
        assertNotNull(answer); assertEquals(25, env.device.vol)
    }

    @Test fun compoundCommandsAreSplitIntoRealCommands() {
        val inv = invokes("open chrome and spotify then set volume to 30")
        assertEquals(listOf("open" to "chrome", "open" to "spotify", "system" to "volume_set"), inv.map { it.tool to (it.args["target"] ?: it.args["action"]) })
    }

    @Test fun namesContainingAndAreNotCutInHalf() {
        assertEquals(listOf("play simon and garfunkel"), parser.split("play simon and garfunkel"))
        assertEquals(listOf("make a note buy milk and eggs"), parser.split("make a note buy milk and eggs"))
    }

    @Test fun partsThatCannotBeUnderstoodAreReportedNotIgnored() {
        val p = plan("open spotify and explain quantum physics")!!
        assertEquals(1, p.steps.size)
        assertEquals(1, p.rest.size); assertTrue(p.rest[0].contains("quantum"))
    }

    @Test fun conversationReturnsNullSoTheAiHandlesIt() {
        assertNull(plan("why is the sky blue"))
        assertNull(plan("how are you feeling about the economy"))
        assertNull(plan(""))
    }

    @Test fun theWakeWordIsStrippedFirst() {
        assertEquals("volume_up", args("hey jarvis volume up")["action"])
    }

    @Test fun parsedStepsRunThroughTheSameLockPolicyAsTheAgent() {
        val locked = ToolContext(env.services, Origin.LOCK_SCREEN, LockState.LOCKED_SECURE, h.dialog)
        val step = parser.plan("open spotify", locked)!!.steps.single() as Step.Invoke
        val ex = kotlinx.coroutines.runBlocking { executor.execute(step.tool, step.args, locked) }
        assertFalse(ex.outcome.ok); assertTrue(env.apps.launched.isEmpty())
    }
}
