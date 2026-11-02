package com.jarvis.core.search

import com.jarvis.core.json.Json
import com.jarvis.core.net.HttpClient
import com.jarvis.core.net.HttpRequest
import com.jarvis.core.net.NetworkException
import com.jarvis.core.net.rethrowIfCancelled
import com.jarvis.core.ports.SearchHit
import com.jarvis.core.ports.WebSearch
import com.jarvis.core.settings.Keys
import com.jarvis.core.settings.Settings
import com.jarvis.core.util.JarvisLog
import java.net.URLDecoder
import java.net.URLEncoder

/** Pure parsers (unit-tested with fixtures). */
object SearchParsers {
    private val DDG = Regex(
        "class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>.*?class=\"result__snippet\"[^>]*>(.*?)</a>",
        RegexOption.DOT_MATCHES_ALL,
    )
    private val TAGS = Regex("<[^>]+>")
    private val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|[a-zA-Z]+);")
    private val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…")

    fun htmlUnescape(s: String): String = ENTITY.replace(s) { m ->
        val g = m.groupValues[1]
        when {
            g.startsWith("#x") -> g.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            g.startsWith("#") -> g.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> NAMED[g] ?: m.value
        }
    }

    private fun plain(s: String) = htmlUnescape(TAGS.replace(s, "")).trim()

    /** DuckDuckGo wraps results in `//duckduckgo.com/l/?uddg=<encoded target>`; return the real target. */
    fun unwrapDdgLink(href: String): String {
        val raw = htmlUnescape(href)
        val i = raw.indexOf("uddg=")
        if (i >= 0) {
            val v = raw.substring(i + 5).substringBefore('&')
            return try { URLDecoder.decode(v, "UTF-8") } catch (e: Exception) { raw }
        }
        return if (raw.startsWith("//")) "https:$raw" else raw
    }

    fun parseDuckDuckGoHtml(html: String, n: Int): List<SearchHit> {
        val out = ArrayList<SearchHit>()
        for (m in DDG.findAll(html)) {
            out.add(SearchHit(plain(m.groupValues[2]), plain(m.groupValues[3]), unwrapDdgLink(m.groupValues[1])))
            if (out.size >= n) break
        }
        return out
    }

    fun parseSerpJson(text: String, n: Int): List<SearchHit>? {
        val j = Json.parseObjectOrNull(text) ?: return null
        if (j["error"] != null && j["organic_results"] == null) return null
        return Json.list(j["organic_results"]).orEmpty().mapNotNull { Json.obj(it) }.take(n)
            .map { SearchHit(Json.str(it["title"]).orEmpty(), Json.str(it["snippet"]).orEmpty(), Json.str(it["link"]).orEmpty()) }
    }

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/** SerpAPI when a key is saved (Keys.SERP_API_KEY), otherwise DuckDuckGo's HTML endpoint. Port of Backend/RealtimeSearchEngine.web_search. */
class DefaultWebSearch(
    private val http: HttpClient,
    private val settings: Settings,
    private val log: JarvisLog = JarvisLog.None,
) : WebSearch {

    override suspend fun search(query: String, n: Int): List<SearchHit> {
        if (query.isBlank()) return emptyList()
        return serp(query, n)?.takeIf { it.isNotEmpty() } ?: ddg(query, n)
    }

    private suspend fun serp(query: String, n: Int): List<SearchHit>? {
        val key = Settings.cleanSecret(settings.get(Keys.SERP_API_KEY))
        if (key.isEmpty()) return null
        return try {
            val r = http.execute(HttpRequest("https://serpapi.com/search.json?engine=google&num=$n&q=${SearchParsers.encode(query)}&api_key=$key", "GET", mapOf("Accept" to "application/json"), null as ByteArray?, 10_000))
            if (r.status != 200) { log.w("Search", "SerpAPI answered ${r.status}"); null } else SearchParsers.parseSerpJson(r.text, n)
        } catch (e: NetworkException) {
            e.rethrowIfCancelled()
            log.w("Search", "SerpAPI unreachable (${e.kind})"); null
        }
    }

    private suspend fun ddg(query: String, n: Int): List<SearchHit> = try {
        val r = http.execute(HttpRequest(
            "https://html.duckduckgo.com/html/", "POST",
            mapOf("User-Agent" to UA, "Content-Type" to "application/x-www-form-urlencoded"), "q=${SearchParsers.encode(query)}", 10_000,
        ))
        if (r.status != 200) { log.w("Search", "DuckDuckGo answered ${r.status}"); emptyList() } else SearchParsers.parseDuckDuckGoHtml(r.text, n)
    } catch (e: NetworkException) {
        e.rethrowIfCancelled()
        log.w("Search", "DuckDuckGo unreachable (${e.kind})"); emptyList()
    }

    private companion object {
        const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
    }
}
