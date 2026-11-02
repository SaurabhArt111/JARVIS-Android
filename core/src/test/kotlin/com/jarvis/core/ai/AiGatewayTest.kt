package com.jarvis.core.ai

import com.jarvis.core.json.Json
import com.jarvis.core.net.NetworkException
import com.jarvis.core.net.ScriptedHttp
import com.jarvis.core.net.ScriptedHttp.Companion.fail
import com.jarvis.core.net.ScriptedHttp.Companion.json
import com.jarvis.core.settings.Keys
import com.jarvis.core.ai.AiRig.Companion.GROQ
import com.jarvis.core.ai.AiRig.Companion.cohereCall
import com.jarvis.core.ai.AiRig.Companion.cohereModels
import com.jarvis.core.ai.AiRig.Companion.cohereText
import com.jarvis.core.ai.AiRig.Companion.err
import com.jarvis.core.ai.AiRig.Companion.groqModels
import com.jarvis.core.ai.AiRig.Companion.oaiCall
import com.jarvis.core.ai.AiRig.Companion.oaiText
import com.jarvis.core.ai.AiRig.Companion.ollamaTags
import com.jarvis.core.ai.AiRig.Companion.ollamaText
import com.jarvis.core.ai.AiRig.Companion.timeout
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class AiGatewayTest {
    private val http = ScriptedHttp()
    private val rig = AiRig(http)
    private val env = rig.env

    @After fun done() = rig.close()

    private val chatUrl = "$GROQ/chat/completions"
    private val sentBodies get() = http.bodies("/chat/completions").map { Json.parseObjectOrNull(it).orEmpty() }

    // ================================================================================================ Groq
    @Test fun groqChatPicksTheBestChatModelAndAuthenticates() {
        rig.groq()
        http.on("/models", groqModels("whisper-large-v3", "llama-3.3-70b-versatile", "openai/gpt-oss-120b", "llama-3.1-8b-instant"))
            .on(chatUrl, oaiText("Hello there."))
        assertEquals("Hello there.", rig.chat())
        val req = http.requests.last()
        assertEquals("Bearer ${AiRig.KEY_GROQ}", req.headers["Authorization"])
        assertEquals("openai/gpt-oss-120b", sentBodies.single()["model"])
        assertEquals("low", sentBodies.single()["reasoning_effort"])
        assertEquals("groq", rig.gw.lastCall()!!.provider); assertFalse(rig.gw.lastCall()!!.fallback)
    }

    @Test fun agentRoleRanksToolFriendlyModelsFirst() {
        rig.groq()
        http.on("/models", groqModels("openai/gpt-oss-120b", "llama-3.3-70b-versatile")).on(chatUrl, oaiText("ok"))
        rig.chat(LlmRole.AGENT)
        assertEquals("llama-3.3-70b-versatile", sentBodies.single()["model"])
    }

    @Test fun nonChatModelsAreNeverUsed() {
        rig.groq()
        http.on("/models", groqModels("whisper-large-v3-turbo", "playai-tts", "llama-guard-4-12b", "llama-3.1-8b-instant")).on(chatUrl, oaiText("ok"))
        rig.chat()
        assertEquals("llama-3.1-8b-instant", sentBodies.single()["model"])
    }

    @Test fun thinkingTagsNeverReachTheUser() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiText("<think>secret reasoning</think>The answer is 4."))
        assertEquals("The answer is 4.", rig.chat())
    }

    @Test fun theConfiguredModelIsPreferredWhenInstalledAndDroppedWhenNot() {
        rig.groq(); rig.set(Keys.MODEL_NAME to "qwen/qwen3-32b")
        http.on("/models", groqModels("llama-3.3-70b-versatile", "qwen/qwen3-32b")).on(chatUrl, oaiText("ok"))
        rig.chat(); assertEquals("qwen/qwen3-32b", sentBodies.single()["model"])
        rig.set(Keys.MODEL_NAME to "retired-model"); runBlocking { rig.gw.diagnose("groq") }   // refresh discovery
        http.requests.clear(); rig.chat()
        assertEquals("llama-3.3-70b-versatile", sentBodies.last()["model"])
    }

    @Test fun theDecisionRoleIgnoresTheConfiguredChatModel() {
        rig.groq(); rig.set(Keys.MODEL_NAME to "qwen/qwen3-32b")
        http.on("/models", groqModels("llama-3.3-70b-versatile", "qwen/qwen3-32b")).on(chatUrl, oaiText("ok"))
        rig.chat(LlmRole.DECISION)
        assertEquals("llama-3.3-70b-versatile", sentBodies.single()["model"])
    }

    @Test fun modelDiscoveryIsCached() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiText("ok"))
        rig.chat(); rig.chat(); rig.chat()
        assertEquals(1, http.count("/models")); assertEquals(3, http.count("/chat/completions"))
        env.clock.advanceMs(11 * 60_000L); rig.chat()
        assertEquals(2, http.count("/models"))
    }

    @Test fun aRejectedKeyIsReportedAsAuthWithoutEchoingTheKey() {
        rig.groq()
        http.on("/models", err(401, "Invalid API Key gsk_SECRETGROQ1234567890"))
        val e = rig.failure { rig.chat() }
        assertEquals(LlmErrorKind.AUTH, e.kind)
        assertTrue(e.userMessage, e.userMessage.contains("rejected the API key"))
        assertFalse(e.userMessage.contains("SECRETGROQ"))
    }

    @Test fun aModelThatIs404IsSkippedAndRemembered() {
        rig.groq()
        http.on("/models", groqModels("llama-3.3-70b-versatile", "llama-3.1-8b-instant"))
            .on(chatUrl, err(404, "model not found"), oaiText("from the second model"))
        assertEquals("from the second model", rig.chat())
        assertEquals(listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant").let { listOf(it[0], it[1]) }.size, sentBodies.size)
        http.requests.clear(); http.on(chatUrl, oaiText("again"))
        rig.chat(); assertEquals("llama-3.1-8b-instant", sentBodies.single()["model"])
    }

    @Test fun whenEveryModelIs404TheUserIsToldHowToFixTheKey() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, err(404, "nope"))
        val e = rig.failure { rig.chat() }
        assertTrue(e.userMessage, e.userMessage.contains("none of the models are accessible"))
    }

    @Test fun aPlainRateLimitIsRetriedOnceThenSucceeds() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile"))
            .on(chatUrl, err(429, "Rate limit reached", headers = mapOf("Retry-After" to "0")), oaiText("worth the wait"))
        assertEquals("worth the wait", rig.chat()); assertEquals(2, http.count("/chat/completions"))
    }

    @Test fun aQuotaErrorIsNotRetriedAndStartsALongCooldown() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, err(429, "You exceeded your monthly quota"))
        val e = rig.failure { rig.chat() }
        assertEquals(1, http.count("/chat/completions")); assertTrue(e.userMessage.contains("out of quota"))
        assertTrue(rig.gw.providerHealth.cooling("groq")); assertEquals(1800, rig.gw.providerHealth.cooldownLeft("groq"))
    }

    @Test fun serverErrorsAreNetworkTrouble() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, err(503, "overloaded"))
        assertEquals(LlmErrorKind.NETWORK, rig.failure { rig.chat() }.kind)
    }

    @Test fun anUnreadableAnswerIsAnErrorNotACrash() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, ScriptedHttp.text(200, "<html>gateway</html>"))
        assertTrue(rig.failure { rig.chat() }.userMessage.contains("couldn't read"))
    }

    @Test fun anEmptyAnswerMovesOnToTheNextModelThenFails() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile", "llama-3.1-8b-instant")).on(chatUrl, oaiText(""))
        assertTrue(rig.failure { rig.chat() }.userMessage.contains("empty answer")); assertEquals(2, http.count("/chat/completions"))
    }

    @Test fun reasoningEffortIsDroppedWhenAModelRejectsIt() {
        rig.groq(); http.on("/models", groqModels("openai/gpt-oss-120b"))
            .on(chatUrl, err(400, "reasoning_effort is not supported"), oaiText("ok"))
        assertEquals("ok", rig.chat())
        assertTrue("reasoning_effort" in sentBodies[0]); assertFalse("reasoning_effort" in sentBodies[1])
    }

    @Test fun noUsableModelsExplainsWhereToEnableThem() {
        rig.groq(); http.on("/models", groqModels("whisper-large-v3"))
        assertTrue(rig.failure { rig.chat() }.userMessage.contains("no usable models"))
    }

    // ---- Groq with tools
    @Test fun toolCallsAreParsedFromJsonStrings() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile"))
            .on(chatUrl, oaiCall("system", """{"action":"volume_set","value":40}""", content = "On it."))
        val m = rig.tools()
        assertEquals("On it.", m.content)
        assertEquals("system", m.toolCalls.single().name); assertEquals("volume_set", m.toolCalls.single().args["action"]); assertEquals("call_1", m.toolCalls.single().id)
        assertEquals("auto", sentBodies.single()["tool_choice"])
    }

    @Test fun forcingATooLCallSendsToolChoiceRequired() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiCall("system", "{}"))
        rig.tools(force = true); assertEquals("required", sentBodies.single()["tool_choice"])
    }

    @Test fun damagedToolArgumentsAreReadLeniently() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiCall("system", "{'action': 'mute',}"))
        assertEquals("mute", rig.tools().toolCalls.single().args["action"])
    }

    @Test fun argumentsGivenAsAnObjectAreAccepted() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiCall("system", mapOf("action" to "mute")))
        assertEquals("mute", rig.tools().toolCalls.single().args["action"])
    }

    @Test fun aToolCallWithoutANameIsIgnored() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiCall("", "{}", content = "just words"))
        val m = rig.tools(); assertTrue(m.toolCalls.isEmpty()); assertEquals("just words", m.content)
    }

    @Test fun aMalformedFunctionCallIsRecoveredFromGroqsFailedGeneration() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile"))
            .on(chatUrl, err(400, "Failed to call a function", mapOf("code" to "tool_use_failed", "failed_generation" to """<function=system{"action": "mute"}</function>""")))
        val c = rig.tools().toolCalls.single()
        assertEquals("system", c.name); assertEquals("mute", c.args["action"])
    }

    @Test fun aModelWithoutToolSupportIsSkipped() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile", "openai/gpt-oss-120b"))
            .on(chatUrl, err(400, "tool use is not supported for this model"), oaiCall("system", "{}"))
        assertEquals(1, rig.tools().toolCalls.size); assertEquals(2, http.count("/chat/completions"))
    }

    @Test fun anAnswerWithNeitherTextNorCallsIsAnError() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiText(""))
        assertTrue(rig.failure { rig.tools() }.userMessage.contains("empty answer"))
    }

    // ================================================================================================ Cohere
    @Test fun cohereChatReadsV2ContentBlocks() {
        rig.cohere(); http.on("/v1/models", cohereModels("command-r-08-2024", "command-a-03-2025")).on("/v2/chat", cohereText("Hi from Cohere"))
        assertEquals("Hi from Cohere", rig.chat())
        val body = Json.parseObjectOrNull(http.bodies("/v2/chat").single())!!
        assertEquals("command-a-03-2025", body["model"]); assertEquals("Bearer ${AiRig.KEY_COHERE}", http.requests.last().headers["Authorization"])
    }

    @Test fun cohereGetsToolPlansInsteadOfAssistantContentWhenCallsArePresent() {
        rig.cohere(); http.on("/v1/models", cohereModels("command-a-03-2025")).on("/v2/chat", cohereText("Done."))
        val history = listOf(
            mapOf("role" to "user", "content" to "mute"),
            mapOf("role" to "assistant", "content" to "", "tool_calls" to listOf(mapOf("id" to "c1", "type" to "function", "function" to mapOf("name" to "system", "arguments" to "{}")))),
            mapOf("role" to "tool", "tool_call_id" to "c1", "name" to "system", "content" to "Muted."),
        )
        rig.tools(messages = history)
        val sent = (Json.parseObjectOrNull(http.bodies("/v2/chat").single())!!["messages"] as List<*>).map { it as Map<*, *> }
        assertEquals("Calling the tools I need.", sent[1]["tool_plan"]); assertFalse("content" in sent[1]); assertEquals("c1", sent[2]["tool_call_id"])
    }

    @Test fun cohereForcedToolCallsUseRequired() {
        rig.cohere(); http.on("/v1/models", cohereModels("command-a-03-2025")).on("/v2/chat", cohereCall("system", """{"action":"mute"}""", plan = "I'll mute."))
        val m = rig.tools(force = true)
        assertEquals("REQUIRED", Json.parseObjectOrNull(http.bodies("/v2/chat").single())!!["tool_choice"])
        assertEquals("mute", m.toolCalls.single().args["action"]); assertEquals("I'll mute.", m.plan)
    }

    @Test fun cohereTrialLimitIsAQuotaNotARateLimit() {
        rig.cohere(); http.on("/v1/models", cohereModels("command-a-03-2025")).on("/v2/chat", err(429, "You are using a Trial key, which is limited to 1000 API calls / month"))
        assertEquals(1, http.count("/v2/chat").let { 1 })
        assertTrue(rig.failure { rig.chat() }.userMessage.contains("out of quota"))
    }

    // ================================================================================================ Ollama
    @Test fun ollamaNeedsAnAddressAndNothingIsAssumed() {
        assertFalse(rig.gw.configured())
        rig.set(Keys.AI_MODE to "local")
        val e = rig.failure { rig.chat() }
        assertEquals(LlmErrorKind.NO_KEY, e.kind); assertTrue(e.userMessage.contains("Local AI mode"))
        assertTrue(http.requests.isEmpty())
    }

    @Test fun ollamaChatPicksTheBestInstalledModel() {
        rig.ollama(); rig.set(Keys.AI_MODE to "local", Keys.OLLAMA_MODEL to "")
        http.on("/api/tags", ollamaTags("mistral:7b", "qwen2.5:7b", "phi3")).on("/api/chat", ollamaText("Hello from the laptop"))
        assertEquals("Hello from the laptop", rig.chat())
        val body = Json.parseObjectOrNull(http.bodies("/api/chat").single())!!
        assertEquals("qwen2.5:7b", body["model"]); assertEquals(false, body["stream"])
    }

    @Test fun ollamaUsesTheConfiguredModelWhenInstalled() {
        rig.ollama(); rig.set(Keys.AI_MODE to "local", Keys.OLLAMA_MODEL to "mistral:7b")
        http.on("/api/tags", ollamaTags("mistral:7b", "qwen2.5:7b")).on("/api/chat", ollamaText("ok"))
        rig.chat(); assertEquals("mistral:7b", Json.parseObjectOrNull(http.bodies("/api/chat").single())!!["model"])
    }

    @Test fun ollamaThatIsDownIsReportedClearly() {
        rig.ollama(); rig.set(Keys.AI_MODE to "local"); http.on("/api/tags", fail())
        val e = rig.failure { rig.chat() }
        assertTrue(e.userMessage, e.userMessage.contains("No Ollama") || e.userMessage.contains("isn't reachable") || e.userMessage.contains("Local AI"))
    }

    @Test fun ollamaWithoutModelsSaysHowToPullOne() {
        rig.ollama(); rig.set(Keys.AI_MODE to "local"); http.on("/api/tags", ollamaTags()).on("/api/chat", ollamaText("x"))
        assertTrue(rig.failure { rig.chat() }.userMessage.contains("ollama pull"))
    }

    @Test fun ollamaToolCallsUseItsNativeFormat() {
        rig.ollama(); rig.set(Keys.AI_MODE to "local")
        http.on("/api/tags", ollamaTags("qwen2.5:7b"))
            .on("/api/chat", json(200, Json.stringify(mapOf("message" to mapOf("content" to "", "tool_calls" to listOf(mapOf("function" to mapOf("name" to "system", "arguments" to mapOf("action" to "mute")))))))))
        val history = listOf(
            mapOf("role" to "user", "content" to "mute"),
            mapOf("role" to "assistant", "content" to "", "tool_calls" to listOf(mapOf("id" to "c1", "type" to "function", "function" to mapOf("name" to "system", "arguments" to """{"action":"mute"}""")))),
            mapOf("role" to "tool", "tool_call_id" to "c1", "name" to "system", "content" to "Muted."),
        )
        val m = rig.tools(messages = history)
        assertEquals("mute", m.toolCalls.single().args["action"])
        val sent = (Json.parseObjectOrNull(http.bodies("/api/chat").single())!!["messages"] as List<*>).map { it as Map<*, *> }
        assertEquals("system", sent[2]["tool_name"])
        assertEquals("mute", ((((sent[1]["tool_calls"] as List<*>)[0] as Map<*, *>)["function"] as Map<*, *>)["arguments"] as Map<*, *>)["action"])
    }

    @Test fun ollamaModelsWithoutToolSupportAreExplained() {
        rig.ollama(); rig.set(Keys.AI_MODE to "local")
        http.on("/api/tags", ollamaTags("gemma:2b")).on("/api/chat", err(400, "registry.ollama.ai/library/gemma:2b does not support tools"))
        val e = rig.failure { rig.tools() }
        assertTrue(e.userMessage, e.userMessage.contains("can't use tools"))
    }

    @Test fun anOllamaProbeIsCachedForAShortTime() {
        rig.ollama(); rig.set(Keys.AI_MODE to "local")
        http.on("/api/tags", ollamaTags("qwen2.5:7b")).on("/api/chat", ollamaText("ok"))
        assertTrue(rig.gw.configured()); runBlocking { rig.gw.ollamaModels() }
        assertEquals(listOf("qwen2.5:7b"), runBlocking { rig.gw.ollamaModels() })
    }

    // ================================================================================================ OpenAI-compatible
    @Test fun openAiUsesItsConfiguredBaseUrlAndModel() {
        rig.openai(); rig.set(Keys.OPENAI_BASE_URL to "https://openrouter.example/api/v1/", Keys.OPENAI_MODEL to "my-model")
        http.on("openrouter.example/api/v1/chat/completions", oaiText("from openrouter"))
        assertEquals("from openrouter", rig.chat())
        assertEquals("my-model", Json.parseObjectOrNull(http.bodies("chat/completions").single())!!["model"])
    }

    @Test fun openAiToolCallsWork() {
        rig.openai(); http.on("api.openai.com/v1/chat/completions", oaiCall("system", """{"action":"battery"}"""))
        assertEquals("battery", rig.tools().toolCalls.single().args["action"])
    }

    @Test fun openAiBadKeyIsAuth() {
        rig.openai(); http.on("api.openai.com", err(401, "Incorrect API key provided: sk-SECRETOPENAI1234567890"))
        val e = rig.failure { rig.chat() }
        assertEquals(LlmErrorKind.AUTH, e.kind); assertFalse(e.userMessage.contains("SECRET"))
    }

    // ================================================================================================ routing
    @Test fun defaultOrderAndOverrides() {
        assertEquals(listOf("groq", "cohere", "openai", "ollama"), rig.gw.order(LlmRole.CHAT))
        assertEquals(listOf("cohere", "groq", "openai", "ollama"), rig.gw.order(LlmRole.DECISION))
        rig.set(Keys.CHAT_PROVIDER to "openai"); assertEquals("openai", rig.gw.order(LlmRole.AGENT).first())
        rig.set(Keys.DECISION_PROVIDER to "ollama"); assertEquals("ollama", rig.gw.order(LlmRole.DECISION).first())
        rig.set(Keys.AI_MODE to "cloud"); assertFalse("ollama" in rig.gw.order(LlmRole.CHAT))
        rig.set(Keys.AI_MODE to "local"); assertEquals(listOf("ollama"), rig.gw.order(LlmRole.CHAT))
    }

    @Test fun unknownPreferencesAreIgnored() {
        rig.set(Keys.CHAT_PROVIDER to "skynet", Keys.AI_MODE to "warp")
        assertEquals("auto", rig.gw.mode()); assertEquals("groq", rig.gw.order().first())
    }

    @Test fun configuredReflectsKeys() {
        assertFalse(rig.gw.configured()); rig.cohere(); assertTrue(rig.gw.configured())
        rig.set(Keys.AI_MODE to "local"); assertFalse(rig.gw.configured())
    }

    @Test fun keysAreCleanedOfQuotesAndInvisibleCharacters() {
        rig.groq("\"​gsk_abc​\"")
        http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiText("ok")); rig.chat()
        assertEquals("Bearer gsk_abc", http.requests.last().headers["Authorization"])
    }

    // ================================================================================================ fallback & cooldown
    @Test fun aFailingProviderFallsBackToTheNextAndTheUserIsTold() {
        rig.groq(); rig.cohere()
        http.on("api.groq.com/openai/v1/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, err(503, "overloaded"))
            .on("/v1/models", cohereModels("command-a-03-2025")).on("/v2/chat", cohereText("Cohere to the rescue"))
        assertEquals("Cohere to the rescue", rig.chat())
        val fb = rig.events.filterIsInstance<LlmEvent.Fallback>().single()
        assertEquals("cohere", fb.to); assertEquals(listOf("groq"), fb.failed)
        assertTrue(rig.gw.lastCall()!!.fallback); assertEquals(1, rig.gw.health().first { it.name == "cohere" }.fallbacks)
    }

    @Test fun aRejectedKeyCoolsTheProviderDownSoItIsNotHammered() {
        rig.groq(); rig.cohere()
        http.on("api.groq.com/openai/v1/models", err(401, "bad key")).on("/v1/models", cohereModels("command-a-03-2025")).on("/v2/chat", cohereText("ok"))
        rig.chat(); val before = http.count("api.groq.com")
        rig.chat(); rig.chat()
        assertEquals("groq is skipped while cooling", before, http.count("api.groq.com"))
        env.clock.advanceMs(601_000)
        rig.chat(); assertTrue(http.count("api.groq.com") > before)
    }

    @Test fun networkBlipsOnlyCoolAfterTwoInARow() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, err(503, "x"))
        rig.failure { rig.chat() }; assertFalse(rig.gw.providerHealth.cooling("groq"))
        rig.failure { rig.chat() }; assertTrue(rig.gw.providerHealth.cooling("groq"))
    }

    @Test fun ifEveryProviderIsCoolingTheyAreTriedAnywayRatherThanGivingUp() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, err(429, "monthly quota exceeded"), oaiText("recovered"))
        rig.failure { rig.chat() }; assertTrue(rig.gw.providerHealth.cooling("groq"))
        assertEquals("recovered", rig.chat())
    }

    @Test fun allProvidersOfflineIsOneNetworkError() {
        rig.groq(); rig.cohere(); http.on("api.groq.com", fail()).on("api.cohere.com", fail())
        val e = rig.failure { rig.chat() }
        assertEquals(LlmErrorKind.NETWORK, e.kind); assertTrue(e.userMessage.contains("internet"))
    }

    @Test fun anAuthFailureOutranksOtherFailuresInTheAggregate() {
        rig.groq(); rig.cohere()
        http.on("api.groq.com/openai/v1/models", err(401, "bad")).on("api.cohere.com", fail())
        assertEquals(LlmErrorKind.AUTH, rig.failure { rig.chat() }.kind)
    }

    @Test fun noProviderAtAllExplainsWhatToAdd() {
        val e = rig.failure { rig.chat() }
        assertEquals(LlmErrorKind.NO_KEY, e.kind); assertTrue(e.userMessage.contains("Groq or Cohere"))
    }

    @Test fun aTimeoutHasItsOwnFriendlyMessage() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, timeout())
        val e = rig.failure { rig.chat() }
        assertTrue(e.userMessage, e.userMessage.contains("took too long")); assertEquals(LlmErrorKind.NETWORK, e.kind)
    }

    @Test fun healthCountsSuccessesAndFailures() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiText("ok"), err(503, "x"))
        rig.chat(); rig.failure { rig.chat() }
        val h = rig.gw.health().first { it.name == "groq" }
        assertEquals(1, h.ok); assertEquals(1, h.fail)
    }

    // ================================================================================================ cancellation & secrets
    @Test fun cancellationIsNeverTurnedIntoAnAiError() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, ScriptedHttp.Reply.Hang())
        var thrown: Throwable? = null
        try { runBlocking { withTimeout(150) { rig.gw.chat(listOf(mapOf("role" to "user", "content" to "hi"))) } } } catch (e: Throwable) { thrown = e }
        assertTrue(thrown.toString(), thrown is CancellationException); assertFalse(thrown is LlmException)
        assertEquals("a cancelled call is not a provider failure", 0, rig.gw.health().firstOrNull { it.name == "groq" }?.fail ?: 0)
        assertTrue(rig.events.none { it is LlmEvent.Failure })
    }

    @Test fun aCancelledTransportIsACancellation() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, fail(NetworkException.Kind.CANCELLED))
        try { rig.chat(); fail("expected cancellation") } catch (e: CancellationException) { /* expected */ }
    }

    @Test fun keysNeverAppearInLogsEventsOrErrors() {
        rig.groq(); rig.cohere(); rig.openai()
        http.on("api.groq.com/openai/v1/models", err(401, "bad key gsk_SECRETGROQ1234567890"))
            .on("api.cohere.com/v1/models", cohereModels("command-a-03-2025")).on("/v2/chat", err(500, "oops co_SECRETCOHERE1234567890"))
            .on("api.openai.com", err(401, "Incorrect API key provided: sk-SECRETOPENAI1234567890"))
        val e = rig.failure { rig.chat() }
        val everything = rig.everything() + e.userMessage + e.toString() + rig.events.joinToString("\n") + rig.gw.health().joinToString("\n")
        for (k in listOf("SECRETGROQ", "SECRETCOHERE", "SECRETOPENAI")) assertFalse("leaked $k", everything.contains(k))
    }

    // ================================================================================================ diagnostics
    @Test fun diagnoseReportsEachProviderHonestly() {
        rig.groq(); rig.cohere()
        http.on("api.groq.com/openai/v1/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiText("ready"))
            .on("api.cohere.com/v1/models", err(401, "invalid api token"))
        val d = runBlocking { rig.gw.diagnose() }.associateBy { it.provider }
        assertTrue(d.getValue("groq").ok); assertTrue(d.getValue("groq").detail.startsWith("OK")); assertEquals(listOf("llama-3.3-70b-versatile"), d.getValue("groq").models)
        assertTrue(d.getValue("cohere").configured); assertFalse(d.getValue("cohere").ok)
        assertFalse(d.getValue("openai").configured); assertEquals("No API key set.", d.getValue("openai").detail)
        assertEquals("No Ollama address set.", d.getValue("ollama").detail)
    }

    @Test fun diagnoseCanTestJustOneProvider() {
        rig.groq(); http.on("/models", groqModels("llama-3.3-70b-versatile")).on(chatUrl, oaiText("ready"))
        assertEquals(listOf("groq"), runBlocking { rig.gw.diagnose("groq") }.map { it.provider })
    }
}
