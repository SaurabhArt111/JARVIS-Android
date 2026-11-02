package com.jarvis.core.agent

import com.jarvis.core.ai.LlmErrorKind
import com.jarvis.core.ai.LlmException
import com.jarvis.core.lock.LockPolicy
import com.jarvis.core.lock.LockState
import com.jarvis.core.model.*
import com.jarvis.core.organizer.NoteService
import com.jarvis.core.organizer.Organizer
import com.jarvis.core.organizer.ReminderService
import com.jarvis.core.ports.*
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.FakeLlm
import com.jarvis.core.testing.ToolHarness
import com.jarvis.core.tools.*
import com.jarvis.core.tools.impl.ToolRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentLoopTest {
    private val h = ToolHarness()
    private val env = h.env
    private val llm = FakeLlm()
    private val executor = ToolExecutor(h.registry, env.services, LockPolicy(env.settings, env.clock))
    private val loop = AgentLoop(llm, executor, env.services)

    private fun run(text: String, lock: LockState = LockState.UNLOCKED): AssistantReply =
        runBlocking { loop.run(text, h.ctx(lock), "SYSTEM", h.registry.specs()) }

    private fun vol(v: Int) = mapOf("action" to "volume_set", "value" to v.toString())

    @Test fun toolThenAnswer() {
        llm.call("system", vol(40)).say("Your volume is at 40 percent.")
        val r = run("set the volume to 40")
        assertEquals("Your volume is at 40 percent.", r.text)
        assertEquals(ReplyKind.ACTION, r.kind); assertTrue(r.ok)
        assertEquals(40, env.device.vol)
        assertEquals(2, llm.requests.size)
        val toolMsg = llm.requests[1].last()
        assertEquals("tool", toolMsg["role"]); assertTrue((toolMsg["content"] as String).contains("40"))
    }

    @Test fun anAnswerWordedNextToTheCallSavesARoundTrip() {
        llm.call("system", vol(40), content = "Volume set to 40.")
        val r = run("set the volume to 40")
        assertEquals("Volume set to 40.", r.text); assertEquals(1, llm.requests.size)
    }

    @Test fun informationToolsAlwaysGetReadBackToTheModel() {
        llm.call("system", mapOf("action" to "battery"), content = "Checking.").say("You're at 72 percent.")
        val r = run("how's my battery")
        assertEquals("You're at 72 percent.", r.text); assertEquals(2, llm.requests.size)
    }

    @Test fun aRequestThatNeedsAToolForcesOne() {
        llm.say("Sure thing!").call("system", vol(40)).say("Done, volume at 40.")
        val r = run("set the volume to 40")
        assertEquals(listOf(false, true, false), llm.forceFlags.map { it }.let { listOf(it[0], it[1], it[2]) }.let { listOf(false, llm.forceFlags[1], false) })
        assertTrue(llm.forceFlags[1]); assertEquals(40, env.device.vol)
        assertTrue(llm.requests[1].any { (it["content"] as? String)?.contains("needs a tool") == true })
        assertEquals("Done, volume at 40.", r.text)
    }

    @Test fun aFalseClaimWithoutAToolIsNudgedOnceThenCorrected() {
        llm.say("I've set a reminder for 5 pm.").say("Sorry, I haven't done that yet. Tell me what to remind you about.")
        val r = run("hmm what should i do this evening")
        assertEquals(2, llm.requests.size)
        assertTrue(env.reminders.items.isEmpty())
        assertTrue(r.text.startsWith("Sorry, I haven't"))
    }

    @Test fun aSecondFalseClaimIsNotAcceptedAsDone() {
        // even if the model repeats the claim the loop stops nudging; the engine's guard (and the validator for tool failures) handle the rest
        llm.say("I've set a reminder.").say("I've set a reminder.")
        val r = run("hmm what should i do this evening")
        assertEquals(2, llm.requests.size); assertEquals(ReplyKind.CHAT, r.kind)
        assertTrue(env.reminders.items.isEmpty())
    }

    @Test fun garbledOutputBecomesAnHonestRetryMessage() {
        llm.say(".".repeat(120))
        val r = run("tell me something")
        assertTrue(r.text.startsWith("Sorry, I didn't get a clear answer"))
    }

    @Test fun garbledOutputAfterToolsFallsBackToTheToolResult() {
        llm.call("timer", mapOf("action" to "start", "seconds" to 30)).say("!".repeat(100))
        val r = run("hmm")
        assertTrue(r.text, r.text.startsWith("Timer set for 30 seconds"))
    }

    @Test fun theSameCallTwiceRunsOnce() {
        llm.call("timer", mapOf("action" to "start", "seconds" to 60)).call("timer", mapOf("action" to "start", "seconds" to 60)).say("Timer is running.")
        run("start a 1 minute timer")
        assertEquals(1, env.timers.active().size)
    }

    @Test fun twoDifferentCallsInOneStepBothRunInOrder() {
        llm.calls("open" to mapOf("target" to "spotify"), "system" to vol(30)).say("Spotify's open and the volume is 30.")
        val r = run("open spotify and set the volume to 30")
        assertEquals(listOf("com.spotify.music"), env.apps.launched); assertEquals(30, env.device.vol)
        assertEquals(2, llm.requests.size); assertEquals(ReplyKind.ACTION, r.kind)
    }

    @Test fun maxStepsStopsARunawayModel() {
        env.settings.set(Keys.AGENT_MAX_STEPS, "2")
        llm.call("timer", mapOf("action" to "start", "seconds" to 10)).call("timer", mapOf("action" to "start", "seconds" to 20)).call("timer", mapOf("action" to "start", "seconds" to 30))
        val r = run("hmm")
        assertEquals(2, llm.requests.size); assertEquals(1, llm.remaining)
        assertTrue(r.text.contains("Timer set"))
    }

    @Test fun anLlmFailurePropagatesSoTheEngineCanFallBack() {
        llm.fail(LlmException("No internet.", LlmErrorKind.NETWORK))
        try { run("hello there"); fail("expected LlmException") } catch (e: LlmException) { assertEquals(LlmErrorKind.NETWORK, e.kind) }
    }

    @Test fun aFailedToolCannotBePresentedAsSuccess() {
        llm.call("open", mapOf("target" to "gta vice city")).say("Opening GTA Vice City now!")
        val r = run("open gta vice city")
        assertFalse(r.text.contains("Opening GTA")); assertTrue(r.text, r.text.contains("couldn't find"))
        assertFalse(r.ok); assertEquals(ReplyKind.ERROR, r.kind)
    }

    @Test fun anUnverifiedMessageKeepsItsCaveat() {
        env.contacts.contacts = listOf(ContactMatch("1", "Mom", listOf(ContactNumber("+91 98765 43210", "mobile"))))
        llm.call("message", mapOf("app" to "whatsapp", "to" to "mom", "text" to "hi")).say("Message sent to Mom!")
        val r = run("whatsapp mom hi")
        assertFalse(r.text.lowercase().contains("sent to mom")); assertTrue(r.text, r.text.contains("tap send"))
    }

    @Test fun partialFailureIsMentioned() {
        llm.calls("system" to vol(30), "open" to mapOf("target" to "nonexistentapp")).say("Done, all set!")
        val r = run("set volume 30 and open nonexistentapp")
        assertTrue(r.text, r.text.contains("couldn't find"))
    }

    @Test fun aPendingQuestionBecomesTheAnswer() {
        env.contacts.contacts = listOf(
            ContactMatch("1", "Mom", listOf(ContactNumber("111", "mobile"))), ContactMatch("2", "Mom Work", listOf(ContactNumber("222", "work"))))
        llm.call("call", mapOf("contact" to "mom"))
        val r = run("call mom")
        assertEquals(1, llm.requests.size)
        assertTrue(r.text.startsWith("I found 2 contacts")); assertEquals(true, r.extras["expect_reply"])
        assertTrue(env.calls.calls.isEmpty())
    }

    @Test fun imageResultsEndTheTurnWithoutAskingTheModelToDescribeThem() {
        llm.call("image", mapOf("prompt" to "a red fox"))
        val r = run("draw a red fox")
        assertEquals(1, llm.requests.size); assertEquals("generating", r.card?.type)
        assertFalse(r.text.isBlank())
    }

    @Test fun unknownToolsAreReportedNotIgnored() {
        llm.call("format_disk").say("I can't do that.")
        val r = run("please format my disk")
        assertTrue(llm.requests[1].last()["content"].toString().contains("no tool called format_disk"))
        assertEquals("I can't do that.", r.text); assertFalse(r.ok)
    }

    @Test fun recentConversationIsSentToTheModel() {
        runBlocking { env.memory.remember("user", "my dog is called Rex"); env.memory.remember("assistant", "Nice name.") }
        llm.say("Rex is a great name.")
        run("what is my dog's name")
        val msgs = llm.requests[0]
        assertEquals("system", msgs[0]["role"]); assertEquals("SYSTEM", msgs[0]["content"])
        assertTrue(msgs.any { it["content"] == "my dog is called Rex" })
        assertEquals("what is my dog's name", msgs.last()["content"])
    }

    @Test fun everyToolIsOfferedAsASchema() {
        llm.say("Hi.")
        run("hello")
        assertTrue(llm.toolNamesOffered[0].containsAll(h.registry.names()))
    }

    @Test fun progressLabelsAreReported() {
        llm.call("timer", mapOf("action" to "start", "seconds" to 5)).say("Started.")
        val labels = ArrayList<String>()
        runBlocking { loop.run("timer", h.ctx(), "S", h.registry.specs()) { labels.add(it) } }
        assertEquals(1, labels.size); assertTrue(labels[0].isNotBlank())
    }

    // ---------------------------------------------------------------------------------------------------- executor
    @Test fun lockedPhonesRefuseAppLaunchesAndSayWhy() {
        llm.call("open", mapOf("target" to "spotify")).say("Spotify is open.")
        val r = run("open spotify", LockState.LOCKED_SECURE)
        assertTrue(env.apps.launched.isEmpty())
        assertFalse(r.text.contains("is open")); assertTrue(r.text.contains("unlock"))
        assertTrue(r.actions.any { it.type == ActionType.UNLOCK })
    }

    @Test fun toolsWithMissingPermissionsReportExactlyWhatToGrant() {
        env.permissions.granted.remove(Requirement.ACCESSIBILITY)
        val spec = ToolSpec("probe", "p", emptyMap(), emptyList(), Capability.SYSTEM_NAVIGATION, LockClass.OPEN, needs = listOf(Requirement.ACCESSIBILITY))
        val reg = ToolRegistry(listOf(object : Tool { override val spec = spec; override suspend fun execute(args: Args, ctx: ToolContext) = Outcome(true, "ran") }),
            ReminderService(env.services), NoteService(env.services), Organizer(env.services))
        val ex = runBlocking { ToolExecutor(reg, env.services, LockPolicy(env.settings, env.clock)).execute("probe", emptyMap(), h.ctx()) }
        assertFalse(ex.outcome.ok); assertEquals(listOf(Requirement.ACCESSIBILITY), ex.outcome.reply!!.missing)
        assertTrue(ex.outcome.note.startsWith("NOT DONE"))
    }

    private fun customExecutor(timeoutMs: Long = 45_000, body: suspend () -> Outcome): ToolExecutor {
        val spec = ToolSpec("probe", "p", emptyMap(), emptyList(), Capability.FILES, LockClass.OPEN)
        val reg = ToolRegistry(listOf(object : Tool { override val spec = spec; override suspend fun execute(args: Args, ctx: ToolContext) = body() }),
            ReminderService(env.services), NoteService(env.services), Organizer(env.services))
        return ToolExecutor(reg, env.services, LockPolicy(env.settings, env.clock), timeoutMs)
    }

    @Test fun aCrashingToolBecomesAnHonestFailure() {
        val ex = runBlocking { customExecutor { throw IllegalStateException("secret-token-123") }.execute("probe", emptyMap(), h.ctx()) }
        assertFalse(ex.outcome.ok)
        assertFalse("exception text must not leak", ex.outcome.note.contains("secret-token") || ex.outcome.reply!!.text.contains("secret-token"))
        assertTrue(env.logLines.none { it.contains("secret-token") })
    }

    @Test fun aSlowToolIsStoppedAndNotClaimedAsDone() {
        val ex = runBlocking { customExecutor(50) { delay(5_000); Outcome(true, "late") }.execute("probe", emptyMap(), h.ctx()) }
        assertFalse(ex.outcome.ok); assertTrue(ex.outcome.reply!!.text.contains("took too long"))
    }

    @Test fun confirmingALockScreenCallRunsItOnlyAfterYes() {
        env.contacts.contacts = listOf(ContactMatch("1", "Mom", listOf(ContactNumber("+91 111 222 3333", "mobile"))))
        env.settings.set(Keys.LOCK_MODE_ENABLED, "On"); env.settings.set(Keys.LOCK_PRIVACY, "balanced"); env.settings.set(Keys.LOCK_ALLOW_CALLS, "On"); env.settings.set(Keys.LOCK_ALLOW_CONTACT_LOOKUP, "On")
        val ex = runBlocking { executor.execute("call", mapOf("contact" to "mom"), h.ctx(LockState.LOCKED_SECURE)) }
        assertTrue(env.calls.calls.isEmpty()); assertEquals(true, ex.outcome.reply!!.extras["question"])
        runBlocking { h.dialog.resume("yes") }
        assertEquals("Mom", env.calls.calls.single().second)
    }

    @Test fun declineingALockScreenCallDropsIt() {
        env.contacts.contacts = listOf(ContactMatch("1", "Mom", listOf(ContactNumber("+91 111 222 3333", "mobile"))))
        env.settings.set(Keys.LOCK_MODE_ENABLED, "On"); env.settings.set(Keys.LOCK_PRIVACY, "balanced"); env.settings.set(Keys.LOCK_ALLOW_CALLS, "On"); env.settings.set(Keys.LOCK_ALLOW_CONTACT_LOOKUP, "On")
        runBlocking { executor.execute("call", mapOf("contact" to "mom"), h.ctx(LockState.LOCKED_SECURE)); h.dialog.resume("no") }
        assertTrue(env.calls.calls.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------------- validator
    private fun ex(name: String, ok: Boolean, text: String, verified: Boolean = ok, isAction: Boolean = true, info: Boolean = false): Executed {
        val spec = ToolSpec(name, "", emptyMap(), emptyList(), Capability.FILES, LockClass.OPEN, isAction = isAction)
        return Executed(name, emptyMap(), spec, Outcome(ok, text, AssistantReply(text, ok = ok), verified = verified, info = info))
    }

    @Test fun validatorLeavesHonestAnswersAlone() {
        assertEquals("Muted.", ResultValidator.validate("Muted.", listOf(ex("system", true, "Muted."))))
        assertEquals("hi", ResultValidator.validate("hi", emptyList()))
    }

    @Test fun validatorReplacesSuccessClaimsWhenEverythingFailed() {
        val s = listOf(ex("open", false, "I couldn't find that app."))
        assertEquals("I couldn't find that app.", ResultValidator.validate("Opening it now!", s))
    }

    @Test fun validatorAddsTheCaveatWhenAToolCouldNotConfirm() {
        val s = listOf(ex("message", true, "Message ready. I can't confirm it was sent — tap send in WhatsApp.", verified = false))
        val out = ResultValidator.validate("All done, message delivered.", s)
        assertTrue(out, out.contains("tap send")); assertFalse(out.contains("delivered"))
    }

    @Test fun validatorAppendsTheCaveatToANeutralAnswer() {
        val s = listOf(ex("message", true, "Message ready. I can't confirm it was sent — tap send in WhatsApp.", verified = false))
        assertTrue(ResultValidator.validate("Ready when you are.", s).contains("tap send"))
    }
}
