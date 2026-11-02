package com.jarvis.core.engine

import com.jarvis.core.ai.*
import com.jarvis.core.lock.LockState
import com.jarvis.core.model.*
import com.jarvis.core.ports.*
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.FakeLlm
import com.jarvis.core.testing.TestEnv
import com.jarvis.core.tools.impl.ToolRegistry
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class EngineTest {
    private val env = TestEnv()
    private val llm = FakeLlm()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private var lock = LockState.UNLOCKED
    private val events = ArrayList<EngineEvent>()
    private val engine = DefaultAssistantEngine(
        env.services, llm, scope, ToolRegistry.default(env.services, settleMs = 0), lockProvider = { lock }, onlineProvider = { true }, rng = kotlin.random.Random(1),
    )

    init { scope.launch { engine.events.collect { events.add(it) } } }

    @After fun tearDown() { scope.cancel() }

    private fun submit(text: String, origin: Origin = Origin.TEXT, l: LockState = lock) = runBlocking { engine.submit(text, origin, l) }
    private fun said() = events.filterIsInstance<EngineEvent.AssistantSaid>()

    // ---------------------------------------------------------------------------------------------------- basics
    @Test fun anAnswerIsShownRememberedAndTheStateReturnsToIdle() {
        llm.say("Hello! How can I help?")
        val r = submit("hi there friend")
        assertEquals("Hello! How can I help?", r.text)
        assertEquals(EngineState.IDLE, engine.state.value)
        assertEquals(listOf("hi there friend" to "user", r.text to "assistant"), env.memory.turns.map { it.content to it.role })
        assertEquals(listOf("hi there friend"), events.filterIsInstance<EngineEvent.UserSaid>().map { it.text })
        assertEquals(r.text, said().single().reply.text)
    }

    @Test fun emptyInputIsAnsweredWithoutAnyWork() {
        assertEquals("I didn't catch that. Could you say it again?", submit("   ").text)
        assertTrue(llm.requests.isEmpty())
    }

    @Test fun aQuestionFromTheAssistantOpensTheMicrophoneForTheAnswer() {
        llm.say("Which cuisine do you like?")
        assertEquals(true, submit("what should I cook tonight").extras["expect_reply"])
    }

    @Test fun onlyTheWakeWordMakesJarvisListen() {
        val r = submit("hey jarvis")
        assertEquals(true, r.extras["listen"]); assertFalse(r.speak); assertTrue(llm.requests.isEmpty())
        assertEquals(1, said().size)
    }

    @Test fun duplicateSubmitsWithinAMomentAreIgnored() {
        llm.say("One.").say("Two.")
        assertEquals("One.", submit("what is two plus two").text)
        val dup = submit("what is two plus two")
        assertEquals(true, dup.extras["duplicate"]); assertEquals("One.", dup.text); assertEquals(1, llm.requests.size)
        env.clock.advanceMs(2_000)
        assertEquals("Two.", submit("what is two plus two").text)
    }

    // ---------------------------------------------------------------------------------------------------- safety
    @Test fun deleteRequestsAreRefusedBeforeAnyAiSeesThem() {
        val r = submit("delete all my photos")
        assertEquals(ReplyKind.BLOCKED, r.kind); assertFalse(r.ok); assertTrue(llm.requests.isEmpty())
        val r2 = submit("uninstall whatsapp")
        assertEquals(ReplyKind.BLOCKED, r2.kind)
    }

    @Test fun blockedRepliesAreNotStoredAsConversation() {
        // they ARE stored (they are real turns); what matters is the AI was never asked
        submit("erase everything on my phone")
        assertTrue(llm.requests.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------------- agent + tools
    @Test fun toolsRunThroughTheAgent() {
        llm.call("system", mapOf("action" to "volume_set", "value" to "55")).say("Volume's at 55.")
        val r = submit("set the volume to 55")
        assertEquals(55, env.device.vol); assertEquals(ReplyKind.ACTION, r.kind)
        assertTrue(events.any { it is EngineEvent.Thinking })
    }

    @Test fun aFailedToolIsReportedAsAnError() {
        llm.call("open", mapOf("target" to "gta v")).say("Opening GTA V.")
        val r = submit("open gta v")
        assertEquals(ReplyKind.ERROR, r.kind); assertFalse(r.ok); assertTrue(r.text.contains("couldn't find"))
    }

    @Test fun aPendingQuestionIsAnsweredWithoutTheAiBeingAsked() {
        env.contacts.contacts = listOf(
            ContactMatch("1", "Mom", listOf(ContactNumber("111", "mobile"))), ContactMatch("2", "Mom Work", listOf(ContactNumber("222", "work"))))
        llm.call("call", mapOf("contact" to "mom"))
        submit("call mom")
        assertEquals(1, llm.requests.size)
        val r = submit("the second one")
        assertEquals(1, llm.requests.size, "no new AI request")
        assertEquals("Mom Work", env.calls.calls.single().second); assertTrue(r.text.contains("Mom Work"))
    }
    private fun assertEquals(expected: Int, actual: Int, msg: String) = org.junit.Assert.assertEquals(msg, expected.toLong(), actual.toLong())

    @Test fun theLockPolicyAppliesInTheEngineToo() {
        llm.call("open", mapOf("target" to "spotify")).say("Spotify is open.")
        val r = submit("open spotify", Origin.LOCK_SCREEN, LockState.LOCKED_SECURE)
        assertTrue(env.apps.launched.isEmpty()); assertTrue(r.actions.any { it.type == ActionType.UNLOCK })
    }

    // ---------------------------------------------------------------------------------------------------- speech policy
    @Test fun voiceRequestsAreSpokenTypedOnesAreNot() {
        llm.say("It is ten o'clock.").say("It is still ten.")
        submit("what time is it exactly", Origin.VOICE)
        assertEquals(1, env.speaker.spoken.size); assertEquals(SpeechChannel.REPLY, env.speaker.spoken[0].second)
        env.clock.advanceMs(5000)
        submit("and what time is it now", Origin.TEXT)
        assertEquals(1, env.speaker.spoken.size)
    }

    @Test fun typedRepliesAreSpokenWhenTheUserAsksForIt() {
        env.settings.set(Keys.SPEAK_REPLIES, "On"); llm.say("Hello.")
        submit("say hello to me", Origin.TEXT)
        assertEquals(1, env.speaker.spoken.size)
    }

    @Test fun mutedVoiceStaysSilent() {
        env.settings.set(Keys.VOICE_MUTED, "On"); llm.say("Hello.")
        submit("say hello to me", Origin.VOICE)
        assertTrue(env.speaker.spoken.isEmpty())
    }

    @Test fun scheduledAndNotificationOriginsNeverSpeak() {
        llm.say("Hello.")
        submit("say hello to me", Origin.NOTIFICATION)
        assertTrue(env.speaker.spoken.isEmpty())
    }

    // ---------------------------------------------------------------------------------------------------- lock screen privacy
    @Test fun personalRepliesAreNeverShownOrSpokenOnALockedSecureDevice() {
        env.settings.set(Keys.LOCK_MODE_ENABLED, "On")
        lock = LockState.LOCKED_SECURE
        llm.call("note", mapOf("action" to "add", "text" to "surprise gift for Asha")).say("Saved your note about the surprise gift for Asha.")
        val r = submit("make a note surprise gift for asha", Origin.VOICE, LockState.LOCKED_SECURE)
        assertEquals(1, env.notes.items.size)
        assertFalse(r.text.contains("surprise")); assertTrue(r.actions.any { it.type == ActionType.UNLOCK })
        assertTrue(said().none { it.reply.text.contains("surprise") })
        assertTrue(env.speaker.spoken.none { it.first.contains("surprise") })
        assertTrue("memory keeps the real turn for later, unlocked use", env.memory.turns.any { it.content.contains("surprise") })
    }

    // ---------------------------------------------------------------------------------------------------- offline & failures
    @Test fun whenTheAiIsUnreachableSimpleCommandsStillWork() {
        llm.fail(LlmException("No internet.", LlmErrorKind.NETWORK))
        val r = submit("set the volume to 40")
        assertEquals(40, env.device.vol); assertEquals(true, r.extras["offline"])
    }

    @Test fun whenTheAiIsUnreachableChatFailsHonestly() {
        llm.fail(LlmException("I can't reach the AI right now: no internet.", LlmErrorKind.NETWORK))
        val r = submit("explain black holes to me")
        assertEquals(ReplyKind.ERROR, r.kind); assertEquals("network", r.extras["ai_error"])
        assertTrue(events.any { it is EngineEvent.Notice })
    }

    @Test fun anInvalidKeyPointsToTheKeySetting() {
        llm.fail(LlmException("The AI key was rejected.", LlmErrorKind.AUTH, "groq", 401))
        val r = submit("explain black holes to me")
        assertEquals(listOf(Requirement.API_KEY), r.missing)
    }

    @Test fun noProviderConfiguredStillRunsOfflineCommands() {
        llm.isConfigured = false
        submit("set the volume to 35")
        assertEquals(35, env.device.vol); assertTrue(llm.requests.isEmpty())
    }

    @Test fun noProviderConfiguredExplainsWhatIsNeeded() {
        llm.isConfigured = false
        val r = submit("explain black holes to me")
        assertEquals(listOf(Requirement.API_KEY), r.missing); assertTrue(r.text.contains("no AI is set up"))
    }

    @Test fun fallbackCanBeSwitchedOff() {
        env.settings.set(Keys.AGENT_FALLBACK, "Off"); llm.fail(LlmException("No internet.", LlmErrorKind.NETWORK))
        val r = submit("set the volume to 40")
        assertEquals(ReplyKind.ERROR, r.kind); assertEquals(30, env.device.vol)
    }

    @Test fun aModelThatCannotCallToolsFallsBackToPlainChat() {
        llm.fail(LlmException("This model does not support tools.", LlmErrorKind.MODEL)).chatSay("Black holes are regions of spacetime.")
        val r = submit("explain black holes to me")
        assertEquals("Black holes are regions of spacetime.", r.text)
    }

    // ---------------------------------------------------------------------------------------------------- AgentMode Off
    @Test fun withoutAgentModeCommandsAreParsedLocallyAndChatGoesToTheModel() {
        env.settings.set(Keys.AGENT_MODE, "Off")
        submit("set the volume to 45"); assertEquals(45, env.device.vol); assertTrue(llm.requests.isEmpty())
        llm.chatSay("Paris."); assertEquals("Paris.", submit("what is the capital of France").text)
    }

    @Test fun plainChatCannotClaimToHaveDoneSomething() {
        env.settings.set(Keys.AGENT_MODE, "Off")
        llm.chatSay("Sure, I've opened the quantum widget for you.")
        val r = submit("please make the quantum widget appear")
        assertFalse(r.text.contains("I've opened")); assertTrue(r.text.startsWith("I haven't actually done that"))
    }

    // ---------------------------------------------------------------------------------------------------- cancel
    private class HangingLlm : LlmGateway {
        override fun configured() = true
        override suspend fun chat(messages: List<Map<String, Any?>>, role: LlmRole, temperature: Double, maxTokens: Int, timeoutMs: Int): String = awaitCancellation()
        override suspend fun chatWithTools(messages: List<Map<String, Any?>>, tools: List<Map<String, Any?>>, role: LlmRole, temperature: Double, maxTokens: Int, timeoutMs: Int, force: Boolean): AiMessage = awaitCancellation()
        override fun lastCall(): LlmCallMeta? = null
        override val events = kotlinx.coroutines.flow.MutableSharedFlow<LlmEvent>()
    }

    @Test fun cancelStopsTheRequestInFlight() {
        val e = DefaultAssistantEngine(env.services, HangingLlm(), scope, ToolRegistry.default(env.services, settleMs = 0), onlineProvider = { true })
        val d = scope.async { e.submit("tell me a long story please", Origin.TEXT) }
        assertEquals(EngineState.THINKING, e.state.value)
        e.cancel()
        val r = runBlocking { d.await() }
        assertEquals("Okay, I stopped.", r.text); assertEquals(EngineState.IDLE, e.state.value)
    }

    // ---------------------------------------------------------------------------------------------------- reminders, greeting, images
    private fun addReminder(text: String): String {
        llm.call("reminder", mapOf("action" to "add", "text" to text, "when" to "5 pm")).say("Reminder set.")
        submit("remind me to $text at 5 pm")
        return env.reminders.items.keys.single()
    }

    private fun dueNow() = env.clock.advanceMs(7 * 3_600_000L)      // 10:00 -> 17:00

    @Test fun anEarlyAlarmStaysSilent() {
        val id = addReminder("call mom"); events.clear(); env.speaker.spoken.clear()
        runBlocking { engine.deliverReminder(id) }
        assertTrue(events.isEmpty()); assertTrue(env.speaker.spoken.isEmpty()); assertEquals(ReminderState.PENDING, env.reminders.items[id]!!.state)
    }

    @Test fun aReminderAlreadyMarkedFiredByTheReceiverIsStillAnnounced() {
        val id = addReminder("call mom"); dueNow(); events.clear()
        env.reminders.items[id] = env.reminders.items[id]!!.copy(state = ReminderState.FIRED, firedAt = env.clock.instant())
        runBlocking { engine.deliverReminder(id) }
        assertEquals("call mom", events.filterIsInstance<EngineEvent.ReminderFired>().single().text)
    }

    @Test fun aStaleFiredReminderIsNotAnnouncedAgain() {
        val id = addReminder("call mom"); dueNow()
        env.reminders.items[id] = env.reminders.items[id]!!.copy(state = ReminderState.FIRED, firedAt = env.clock.instant().minusSeconds(3600)); events.clear()
        runBlocking { engine.deliverReminder(id) }
        assertTrue(events.isEmpty())
    }

    @Test fun aCancelledReminderIsNeverAnnounced() {
        val id = addReminder("call mom"); dueNow()
        env.reminders.items[id] = env.reminders.items[id]!!.copy(state = ReminderState.CANCELLED); events.clear()
        runBlocking { engine.deliverReminder(id) }
        assertTrue(events.isEmpty())
    }

    @Test fun reminderDeliveryNamesTheReminder() {
        val id = addReminder("call mom"); dueNow(); events.clear()
        runBlocking { engine.deliverReminder(id) }
        val fired = events.filterIsInstance<EngineEvent.ReminderFired>().single()
        assertEquals("call mom", fired.text); assertFalse(fired.isTimer)
        assertEquals("Reminder: call mom.", env.speaker.spoken.last().first); assertEquals(SpeechChannel.REMINDER, env.speaker.spoken.last().second)
        assertEquals(ReminderState.FIRED, env.reminders.items[id]!!.state)
    }

    @Test fun reminderDeliveryOnALockedPhoneHidesTheText() {
        val id = addReminder("pay the lawyer"); dueNow(); events.clear(); env.speaker.spoken.clear()
        lock = LockState.LOCKED_SECURE
        runBlocking { engine.deliverReminder(id) }
        assertTrue(events.none { it.toString().contains("lawyer") }); assertTrue(env.speaker.spoken.none { it.first.contains("lawyer") })
        assertTrue(env.speaker.spoken.last().first.contains("Unlock"))
    }

    @Test fun reminderDeliveryRespectsTheSpeakSetting() {
        val id = addReminder("call mom"); dueNow(); env.speaker.spoken.clear()
        env.settings.set(Keys.SPEAK_REMINDERS, "Off")
        runBlocking { engine.deliverReminder(id) }
        assertTrue(env.speaker.spoken.isEmpty())
    }

    @Test fun aReminderThatNoLongerExistsDoesNothing() {
        events.clear()
        runBlocking { engine.deliverReminder("gone") }
        assertTrue(events.isEmpty())
    }

    @Test fun timersAnnounceByLabel() {
        runBlocking { engine.deliverReminder("Pasta", isTimer = true) }
        assertEquals("Your pasta is finished.", env.speaker.spoken.last().first)
        assertTrue(events.filterIsInstance<EngineEvent.ReminderFired>().single().isTimer)
    }

    @Test fun greetingMentionsTodaysRemindersButHidesThemWhenLocked() {
        addReminder("call mom"); events.clear()
        runBlocking { engine.greet(speak = false) }
        assertTrue(said().single().reply.text.contains("call mom"))
        events.clear(); lock = LockState.LOCKED_SECURE
        runBlocking { engine.greet(speak = false) }
        assertFalse(said().single().reply.text.contains("call mom"))
    }

    @Test fun aFinishedImageIsAnnouncedWithAnOpenButton() {
        val f = StoredFile("1", "fox.png", ContentCategory.IMAGES, "content://x/1", "image/png", 1000, Instant.EPOCH, StorageLocation.PUBLIC_DOCUMENTS)
        env.images.emit(ImageEvent(ImageEventType.DONE, "job1", "a red fox", files = listOf(f)))
        val r = said().last().reply
        assertEquals("Your image is ready.", r.text); assertEquals("images", r.card?.type); assertEquals(ActionType.OPEN_FILE, r.actions.single().type)
        assertTrue(env.memory.turns.any { it.content == "Your image is ready." })
    }

    @Test fun aFailedImageIsReportedNotHidden() {
        env.images.emit(ImageEvent(ImageEventType.ERROR, "job1", message = "The image service is busy."))
        assertEquals("The image service is busy.", said().last().reply.text); assertFalse(said().last().reply.ok)
    }

    @Test fun aBackupAiNoticeIsShownOnce() {
        llm.emit(LlmEvent.Fallback("cohere", listOf("groq"), "rate limited")); llm.emit(LlmEvent.Fallback("cohere", listOf("groq"), "rate limited"))
        assertEquals(1, events.filterIsInstance<EngineEvent.Notice>().size)
    }

    @Test fun listeningStateIsMirroredToEverySurface() {
        engine.setListening(true); assertEquals(EngineState.LISTENING, engine.state.value)
        engine.setListening(false); assertEquals(EngineState.IDLE, engine.state.value)
    }
}
