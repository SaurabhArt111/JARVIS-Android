package com.jarvis.core.search

import com.jarvis.core.ai.LlmErrorKind
import com.jarvis.core.ai.LlmException
import com.jarvis.core.ai.LlmRole
import com.jarvis.core.json.Json
import com.jarvis.core.net.NetworkException
import com.jarvis.core.net.ScriptedHttp
import com.jarvis.core.net.ScriptedHttp.Companion.fail
import com.jarvis.core.net.ScriptedHttp.Companion.json
import com.jarvis.core.net.ScriptedHttp.Companion.text
import com.jarvis.core.ports.ChatTurn
import com.jarvis.core.ports.SearchHit
import com.jarvis.core.settings.Keys
import com.jarvis.core.testing.FakeLlm
import com.jarvis.core.testing.FakeWeb
import com.jarvis.core.testing.TestEnv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SearchTest {
    private val http = ScriptedHttp()
    private val env = TestEnv()
    private val search = DefaultWebSearch(http, env.settings, env.log)

    private fun ddgPage(vararg hits: Triple<String, String, String>) = "<html><body>" + hits.joinToString("") { (href, title, snip) ->
        """<div class="result"><h2 class="result__title"><a rel="nofollow" class="result__a" href="$href">$title</a></h2>
           <a class="result__snippet" href="$href">$snip</a></div>"""
    } + "</body></html>"

    private val wrapped = "//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3D2&amp;rut=abc"

    // ---------------------------------------------------------------------------------------------------- parsers
    @Test fun duckDuckGoResultsAreParsedAndUnwrapped() {
        val html = ddgPage(Triple(wrapped, "Example <b>Title</b>", "A <b>bold</b> snippet &amp; more"), Triple("https://plain.org/x", "Plain", "Direct link"))
        val hits = SearchParsers.parseDuckDuckGoHtml(html, 5)
        assertEquals(2, hits.size)
        assertEquals(SearchHit("Example Title", "A bold snippet & more", "https://example.com/a?x=1&y=2"), hits[0])
        assertEquals("https://plain.org/x", hits[1].url)
    }

    @Test fun theResultCountIsRespected() {
        val html = ddgPage(*Array(6) { Triple("https://e.com/$it", "T$it", "S$it") })
        assertEquals(3, SearchParsers.parseDuckDuckGoHtml(html, 3).size)
    }

    @Test fun aPageWithoutResultsGivesAnEmptyList() {
        assertTrue(SearchParsers.parseDuckDuckGoHtml("<html>No results.</html>", 5).isEmpty())
        assertTrue(SearchParsers.parseDuckDuckGoHtml("", 5).isEmpty())
    }

    @Test fun htmlEntitiesAreDecoded() {
        assertEquals("Tom & Jerry's \"fun\" <ok> – é", SearchParsers.htmlUnescape("Tom &amp; Jerry&#39;s &quot;fun&quot; &lt;ok&gt; &ndash; &#xE9;"))
        assertEquals("&unknown; stays", SearchParsers.htmlUnescape("&unknown; stays"))
    }

    @Test fun schemeRelativeLinksGetHttps() {
        assertEquals("https://example.org/p", SearchParsers.unwrapDdgLink("//example.org/p"))
    }

    @Test fun serpJsonIsParsed() {
        val body = Json.stringify(mapOf("organic_results" to listOf(
            mapOf("title" to "One", "snippet" to "s1", "link" to "https://one.example"), mapOf("title" to "Two", "link" to "https://two.example"))))
        val hits = SearchParsers.parseSerpJson(body, 5)!!
        assertEquals(listOf("One", "Two"), hits.map { it.title }); assertEquals("", hits[1].snippet)
    }

    @Test fun serpErrorsAndGarbageAreNull() {
        assertNull(SearchParsers.parseSerpJson("""{"error":"Invalid API key"}""", 5))
        assertNull(SearchParsers.parseSerpJson("not json", 5))
        assertEquals(emptyList<SearchHit>(), SearchParsers.parseSerpJson("""{"organic_results":[]}""", 5))
    }

    // ---------------------------------------------------------------------------------------------------- DefaultWebSearch
    @Test fun withoutAKeyDuckDuckGoIsUsed() {
        http.on("html.duckduckgo.com", text(200, ddgPage(Triple("https://e.com", "T", "S"))))
        val r = runBlocking { search.search("kotlin coroutines", 5) }
        assertEquals("T", r.single().title)
        val req = http.requests.single()
        assertEquals("POST", req.method); assertEquals("q=kotlin+coroutines", req.body!!.toString(Charsets.UTF_8))
        assertTrue(req.headers["User-Agent"]!!.contains("Mozilla"))
    }

    @Test fun aSerpKeyMakesSerpTheFirstChoice() {
        env.settings.set(Keys.SERP_API_KEY, "serp-SECRET-123")
        http.on("serpapi.com", json(200, Json.stringify(mapOf("organic_results" to listOf(mapOf("title" to "S", "snippet" to "x", "link" to "https://s.example"))))))
        val r = runBlocking { search.search("weather news", 3) }
        assertEquals("S", r.single().title); assertEquals(0, http.count("duckduckgo"))
        assertTrue(http.requests.single().url.contains("q=weather+news") && http.requests.single().url.contains("num=3"))
    }

    @Test fun ifSerpFailsDuckDuckGoAnswersInstead() {
        env.settings.set(Keys.SERP_API_KEY, "serp-SECRET-123")
        http.on("serpapi.com", json(403, """{"error":"bad key"}""")).on("duckduckgo", text(200, ddgPage(Triple("https://e.com", "FromDdg", "S"))))
        assertEquals("FromDdg", runBlocking { search.search("q", 5) }.single().title)
    }

    @Test fun anEmptySerpAnswerAlsoFallsBack() {
        env.settings.set(Keys.SERP_API_KEY, "serp-SECRET-123")
        http.on("serpapi.com", json(200, """{"organic_results":[]}""")).on("duckduckgo", text(200, ddgPage(Triple("https://e.com", "FromDdg", "S"))))
        assertEquals("FromDdg", runBlocking { search.search("q", 5) }.single().title)
    }

    @Test fun noNetworkMeansNoResultsNotACrash() {
        http.on("duckduckgo", fail())
        assertTrue(runBlocking { search.search("q", 5) }.isEmpty())
        http.on("serpapi", fail())
    }

    @Test fun aBlockedDuckDuckGoGivesNoResults() {
        http.on("duckduckgo", text(202, "<html>anomaly</html>"))
        assertTrue(runBlocking { search.search("q", 5) }.isEmpty())
    }

    @Test fun blankQueriesNeverTouchTheNetwork() {
        assertTrue(runBlocking { search.search("   ", 5) }.isEmpty()); assertTrue(http.requests.isEmpty())
    }

    @Test fun theSerpKeyNeverReachesTheLog() {
        env.settings.set(Keys.SERP_API_KEY, "serp-SECRET-123")
        http.on("serpapi.com", fail()).on("duckduckgo", fail())
        runBlocking { search.search("q", 5) }
        assertTrue(env.logLines.isNotEmpty()); assertTrue(env.logLines.none { it.contains("SECRET") })
    }

    @Test fun cancellationIsPassedOnNotSwallowed() {
        http.on("duckduckgo", fail(NetworkException.Kind.CANCELLED))
        try { runBlocking { search.search("q", 5) }; fail("expected cancellation") } catch (e: CancellationException) { }
        val hang = ScriptedHttp().on("duckduckgo", ScriptedHttp.Reply.Hang())
        try { runBlocking { withTimeout(100) { DefaultWebSearch(hang, env.settings).search("q", 5) } }; fail() } catch (e: CancellationException) { }
    }

    // ---------------------------------------------------------------------------------------------------- RealtimeAnswer
    private val llm = FakeLlm()
    private val web = FakeWeb()
    private val rt = RealtimeAnswer(web, llm, env.clock)

    @Test fun theModelAnswersFromTheSearchResults() {
        web.hits = listOf(SearchHit("India wins", "India won by 5 wickets.", "https://news.example/1"), SearchHit("No link", "x", ""))
        llm.chatSay("**India** won by five wickets.")
        val r = runBlocking { rt.answer("who won the match", "SYS") }
        assertEquals("India won by five wickets.", r.answer); assertTrue(r.usedWeb)
        assertEquals(listOf("India wins" to "https://news.example/1"), r.sources)
        val sent = llm.chatRequests.single()
        assertTrue(sent[0]["content"].toString().startsWith("SYS")); assertTrue(sent[1]["content"].toString().contains("India won by 5 wickets."))
        assertTrue(sent[1]["content"].toString().contains("Sunday 25 October 2026, 10:00 AM"))
        assertEquals("who won the match", sent.last()["content"])
    }

    @Test fun withoutResultsTheModelIsToldToHedge() {
        llm.chatSay("I believe it was Tuesday, but this may be out of date.")
        val r = runBlocking { rt.answer("when is the festival", "SYS") }
        assertFalse(r.usedWeb); assertTrue(r.sources.isEmpty())
        assertTrue(llm.chatRequests.single()[1]["content"].toString().contains("may be out of date"))
    }

    @Test fun onlyTheLastSixTurnsOfHistoryAreSent() {
        llm.chatSay("ok")
        val hist = (1..10).map { ChatTurn(if (it % 2 == 0) "assistant" else "user", "turn$it") }
        runBlocking { rt.answer("q", "SYS", hist) }
        val sent = llm.chatRequests.single().map { it["content"].toString() }
        assertTrue("turn5" in sent && "turn10" in sent); assertFalse("turn4" in sent)
        assertEquals(2 + 6 + 1, sent.size)
    }

    @Test fun sourcesAreCappedAtFour() {
        web.hits = (1..5).map { SearchHit("T$it", "S$it", "https://e.com/$it") }; llm.chatSay("ok")
        assertEquals(4, runBlocking { rt.answer("q", "S") }.sources.size)
    }

    @Test fun anUnreachableAiPropagatesSoTheCallerCanSayWhy() {
        llm.chatFail(LlmException("No internet.", LlmErrorKind.NETWORK))
        try { runBlocking { rt.answer("q", "S") }; fail() } catch (e: LlmException) { assertEquals(LlmErrorKind.NETWORK, e.kind) }
    }

    @Test fun cleanRemovesMarkdownAndTags() {
        assertEquals("a b\n\nc", RealtimeAnswer.clean("**a** <br/>b\n\n\n\n*c*"))
    }

    @Suppress("unused") private val r = LlmRole.CHAT
}
