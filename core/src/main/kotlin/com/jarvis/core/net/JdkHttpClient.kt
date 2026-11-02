package com.jarvis.core.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException

/**
 * [HttpClient] on `java.net.HttpURLConnection` (works unchanged on the JVM and on Android).
 *
 *  - HTTPS only, except for [cleartextHosts] (loopback / emulator host, see res/xml/network_security_config.xml)
 *  - redirects are followed by hand (max 5), never to a less secure scheme; `Authorization` is dropped when the host changes
 *  - responses larger than [maxBytes] are refused
 *  - cancelling the calling coroutine disconnects the socket
 *  - never logs anything (it has no logger), so keys and bodies cannot leak from here
 */
class JdkHttpClient(
    private val maxBytes: Int = 25 * 1024 * 1024,
    private val userAgent: String = "JARVIS-Android/0.1",
    private val cleartextHosts: Set<String> = setOf("localhost", "127.0.0.1", "10.0.2.2"),
) : HttpClient {

    override suspend fun execute(request: HttpRequest): HttpResponse = coroutineScope {
        checkUrl(request.url)
        val connection = AtomicReference<HttpURLConnection?>()
        val worker = async(Dispatchers.IO) {
            try {
                blockingExecute(request, connection)
            } catch (e: Throwable) {
                // disconnect() on cancellation makes the blocked socket read throw; that is a cancellation, not a network failure
                if (!isActive) throw CancellationException("HTTP request cancelled", e) else throw e
            }
        }
        try {
            worker.await()
        } catch (e: CancellationException) {
            worker.cancel()                    // first: so the socket error caused by disconnect() is not reported as a failure
            connection.get()?.disconnect()
            throw e
        }
    }

    // ------------------------------------------------------------------------------------------ internals
    private fun checkUrl(url: String): URI {
        val uri = try { URI(url) } catch (e: Exception) { throw NetworkException(NetworkException.Kind.OTHER, "Invalid URL.") }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: throw NetworkException(NetworkException.Kind.OTHER, "Invalid URL (no host).")
        when (scheme) {
            "https" -> {}
            "http" -> if (host !in cleartextHosts) {
                throw NetworkException(NetworkException.Kind.OTHER, "Only HTTPS connections are allowed (blocked http://$host).")
            }
            else -> throw NetworkException(NetworkException.Kind.OTHER, "Unsupported URL scheme.")
        }
        return uri
    }

    private fun blockingExecute(request: HttpRequest, ref: AtomicReference<HttpURLConnection?>): HttpResponse {
        var url = request.url
        var method = request.method.uppercase()
        var body = request.body
        var headers = request.headers
        var hops = 0
        while (true) {
            val uri = checkUrl(url)
            val conn = try {
                uri.toURL().openConnection() as HttpURLConnection
            } catch (e: IOException) {
                throw mapIo(e)
            }
            ref.set(conn)
            try {
                conn.instanceFollowRedirects = false
                conn.connectTimeout = request.timeoutMs
                conn.readTimeout = request.timeoutMs
                conn.requestMethod = method
                conn.setRequestProperty("User-Agent", userAgent)
                for ((k, v) in headers) conn.setRequestProperty(k, v)
                if (body != null && method != "GET" && method != "HEAD") {
                    conn.doOutput = true
                    conn.setFixedLengthStreamingMode(body.size)
                    conn.outputStream.use { it.write(body) }
                }
                val status = conn.responseCode
                if (status in REDIRECTS && hops < MAX_REDIRECTS) {
                    val loc = conn.getHeaderField("Location")
                    if (loc != null) {
                        val next = try { uri.resolve(loc).toString() } catch (e: Exception) {
                            throw NetworkException(NetworkException.Kind.OTHER, "Bad redirect.")
                        }
                        checkUrl(next) // refuses a downgrade to cleartext
                        if (URI(next).host?.lowercase() != uri.host?.lowercase()) {
                            headers = headers.filterKeys { !it.equals("Authorization", true) && !it.equals("x-api-key", true) }
                        }
                        if (status == 303 || ((status == 301 || status == 302) && method == "POST")) {
                            method = "GET"; body = null
                        }
                        url = next; hops++
                        continue
                    }
                }
                val stream = if (status >= 400) conn.errorStream else conn.inputStream
                val declared = conn.contentLengthLong
                if (declared > maxBytes) throw NetworkException(NetworkException.Kind.OTHER, "The response is too large.")
                val data = stream?.use { readCapped(it) } ?: ByteArray(0)
                val out = LinkedHashMap<String, String>()
                for ((k, v) in conn.headerFields) if (k != null && !v.isNullOrEmpty()) out[k] = v.first()
                return HttpResponse(status, out, data)
            } catch (e: NetworkException) {
                throw e
            } catch (e: IOException) {
                throw mapIo(e)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun readCapped(input: InputStream): ByteArray {
        val buf = ByteArray(16 * 1024)
        val out = ByteArrayOutputStream()
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > maxBytes) throw NetworkException(NetworkException.Kind.OTHER, "The response is too large.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun mapIo(e: IOException): NetworkException = when (e) {
        is SocketTimeoutException -> NetworkException(NetworkException.Kind.TIMEOUT, "The connection timed out.", e)
        is UnknownHostException, is ConnectException, is NoRouteToHostException ->
            NetworkException(NetworkException.Kind.UNREACHABLE, "The server could not be reached.", e)
        is SSLException -> NetworkException(NetworkException.Kind.TLS, "A secure connection could not be established.", e)
        else -> NetworkException(NetworkException.Kind.OTHER, "Network error (${e.javaClass.simpleName}).", e)
    }

    private companion object {
        val REDIRECTS = setOf(301, 302, 303, 307, 308)
        const val MAX_REDIRECTS = 5
    }
}
