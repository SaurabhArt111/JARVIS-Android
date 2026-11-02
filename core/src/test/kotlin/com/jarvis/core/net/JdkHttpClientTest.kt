package com.jarvis.core.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class JdkHttpClientTest {
    private lateinit var server: HttpServer
    private val client = JdkHttpClient(maxBytes = 1024)
    private val base get() = "http://127.0.0.1:${server.address.port}"
    private val seenAuth = ArrayList<String?>()

    @Before fun start() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/ok") { ex -> reply(ex, 200, "hello", mapOf("X-Test" to "yes")) }
        server.createContext("/echo") { ex -> val b = ex.requestBody.readBytes(); reply(ex, 200, "${ex.requestMethod}:${String(b)}:${ex.requestHeaders.getFirst("Content-Type")}") }
        server.createContext("/missing") { ex -> reply(ex, 404, "{\"error\":\"nope\"}") }
        server.createContext("/boom") { ex -> reply(ex, 500, "server error") }
        server.createContext("/ua") { ex -> reply(ex, 200, ex.requestHeaders.getFirst("User-Agent") ?: "") }
        server.createContext("/auth") { ex -> seenAuth.add(ex.requestHeaders.getFirst("Authorization")); reply(ex, 200, "done") }
        server.createContext("/redir") { ex -> ex.responseHeaders.add("Location", "/ok"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/redir-post") { ex -> ex.requestBody.readBytes(); ex.responseHeaders.add("Location", "/echo"); ex.sendResponseHeaders(303, -1); ex.close() }
        server.createContext("/redir-host") { ex -> ex.responseHeaders.add("Location", "http://localhost:${server.address.port}/auth"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/redir-down") { ex -> ex.responseHeaders.add("Location", "http://example.com/x"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/slow") { ex -> Thread.sleep(2000); reply(ex, 200, "late") }
        server.createContext("/big") { ex -> reply(ex, 200, "x".repeat(5000)) }
        server.start()
    }

    @After fun stop() { server.stop(0) }

    private fun reply(ex: HttpExchange, status: Int, body: String, headers: Map<String, String> = emptyMap()) {
        headers.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
        val bytes = body.toByteArray()
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test fun getReturnsBodyAndHeaders() = runBlocking {
        val r = client.execute(HttpRequest("$base/ok"))
        assertEquals(200, r.status); assertEquals("hello", r.text); assertTrue(r.ok)
        assertEquals("yes", r.header("x-test"))
    }

    @Test fun postSendsBodyAndHeaders() = runBlocking {
        val r = client.execute(HttpRequest("$base/echo", "POST", mapOf("Content-Type" to "application/json"), "{\"a\":1}"))
        assertEquals("POST:{\"a\":1}:application/json", r.text)
    }

    @Test fun errorStatusesComeBackAsResponsesNotExceptions() = runBlocking {
        val r = client.execute(HttpRequest("$base/missing"))
        assertEquals(404, r.status); assertFalse(r.ok); assertTrue(r.text.contains("nope"))
        assertEquals(500, client.execute(HttpRequest("$base/boom")).status)
    }

    @Test fun sendsUserAgent() = runBlocking {
        assertEquals("JARVIS-Android/0.1", client.execute(HttpRequest("$base/ua")).text)
    }

    @Test fun followsSameHostRedirect() = runBlocking {
        assertEquals("hello", client.execute(HttpRequest("$base/redir")).text)
    }

    @Test fun postRedirect303BecomesGet() = runBlocking {
        assertEquals("GET::null", client.execute(HttpRequest("$base/redir-post", "POST", emptyMap(), "payload")).text)
    }

    @Test fun authorizationIsDroppedWhenRedirectChangesHost() = runBlocking {
        seenAuth.clear()
        client.execute(HttpRequest("$base/redir-host", "GET", mapOf("Authorization" to "Bearer secret")))
        assertEquals(listOf<String?>(null), seenAuth)
        client.execute(HttpRequest("$base/auth", "GET", mapOf("Authorization" to "Bearer secret")))
        assertEquals("Bearer secret", seenAuth.last())
    }

    @Test fun redirectDowngradeToCleartextIsRefused() = runBlocking {
        try { client.execute(HttpRequest("$base/redir-down")); fail() } catch (e: NetworkException) {
            assertEquals(NetworkException.Kind.OTHER, e.kind); assertTrue(e.message!!.contains("HTTPS"))
        }
    }

    @Test fun cleartextToRemoteHostIsRejectedBeforeAnyConnection() = runBlocking {
        try { client.execute(HttpRequest("http://example.com/")); fail() } catch (e: NetworkException) {
            assertEquals(NetworkException.Kind.OTHER, e.kind); assertTrue(e.message!!.contains("HTTPS"))
        }
        try { client.execute(HttpRequest("ftp://example.com/")); fail() } catch (e: NetworkException) { assertEquals(NetworkException.Kind.OTHER, e.kind) }
        try { client.execute(HttpRequest("not a url")); fail() } catch (e: NetworkException) { assertEquals(NetworkException.Kind.OTHER, e.kind) }
    }

    @Test fun timeoutIsReported() = runBlocking {
        try { client.execute(HttpRequest("$base/slow", timeoutMs = 300)); fail() } catch (e: NetworkException) {
            assertEquals(NetworkException.Kind.TIMEOUT, e.kind)
        }
    }

    @Test fun responsesOverTheCapAreRefused() = runBlocking {
        try { client.execute(HttpRequest("$base/big")); fail() } catch (e: NetworkException) {
            assertEquals(NetworkException.Kind.OTHER, e.kind); assertTrue(e.message!!.contains("too large"))
        }
    }

    @Test fun closedPortIsUnreachable() = runBlocking {
        val port = server.address.port
        server.stop(0)
        try { client.execute(HttpRequest("http://127.0.0.1:$port/ok")); fail() } catch (e: NetworkException) {
            assertEquals(NetworkException.Kind.UNREACHABLE, e.kind)
        }
    }

    @Test fun cancellingTheCallerAbortsTheRequestQuickly() = runBlocking {
        val started = CountDownLatch(1)
        server.createContext("/hang") { ex -> started.countDown(); try { Thread.sleep(5000) } catch (_: InterruptedException) {}; try { reply(ex, 200, "x") } catch (_: Exception) {} }
        val t0 = System.nanoTime()
        val call = async(Dispatchers.Default) { client.execute(HttpRequest("$base/hang", timeoutMs = 10_000)) }
        assertTrue(started.await(3, TimeUnit.SECONDS))
        withTimeout(2000) { call.cancelAndJoin() }
        assertTrue(call.isCancelled)
        assertTrue("cancel took too long", (System.nanoTime() - t0) / 1_000_000 < 3000)
    }
}
