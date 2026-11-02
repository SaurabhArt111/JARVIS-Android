package com.jarvis.core.net

import java.io.IOException

class HttpRequest(
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutMs: Int = 20_000,
) {
    constructor(url: String, method: String, headers: Map<String, String>, bodyText: String, timeoutMs: Int = 20_000) :
        this(url, method, headers, bodyText.toByteArray(Charsets.UTF_8), timeoutMs)
}

class HttpResponse(val status: Int, val headers: Map<String, String>, val body: ByteArray) {
    val text: String get() = body.toString(Charsets.UTF_8)
    val ok: Boolean get() = status in 200..299
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

/** I/O level failure (no response at all). HTTP error statuses are NOT exceptions; they come back as [HttpResponse]. */
class NetworkException(val kind: Kind, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Kind { TIMEOUT, UNREACHABLE, TLS, CANCELLED, OTHER }
}

/** A cancelled transport means the caller's coroutine was cancelled: services must pass that on instead of reporting "no results". */
fun NetworkException.rethrowIfCancelled() {
    if (kind == NetworkException.Kind.CANCELLED) throw kotlinx.coroutines.CancellationException("cancelled")
}

/** Suspending HTTP transport. Cancelling the coroutine aborts the request. */
interface HttpClient {
    /** @throws NetworkException when no HTTP response could be obtained. */
    suspend fun execute(request: HttpRequest): HttpResponse
}
