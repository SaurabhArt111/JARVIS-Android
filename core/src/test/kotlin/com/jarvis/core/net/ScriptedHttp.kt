package com.jarvis.core.net

/** Scripted [HttpClient]: routes match by URL substring (+ optional method); each route replays its replies in order, repeating the last. */
class ScriptedHttp : HttpClient {
    sealed class Reply {
        class Resp(val status: Int, val body: ByteArray, val headers: Map<String, String>) : Reply()
        class Fail(val error: NetworkException) : Reply()
        class Hang : Reply()
    }

    private class Route(val needle: String, val method: String?, val replies: List<Reply>) { var used = 0 }

    val requests = ArrayList<HttpRequest>()
    private val routes = ArrayList<Route>()

    fun on(needle: String, vararg replies: Reply, method: String? = null): ScriptedHttp { routes.add(Route(needle, method, replies.toList())); return this }

    fun count(needle: String) = requests.count { needle in it.url }
    fun bodies(needle: String): List<String> = requests.filter { needle in it.url }.map { it.body?.toString(Charsets.UTF_8).orEmpty() }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests.add(request)
        val route = routes.firstOrNull { request.url.contains(it.needle) && (it.method == null || it.method == request.method) }
            ?: throw NetworkException(NetworkException.Kind.UNREACHABLE, "no route for ${request.url}")
        val reply = route.replies[minOf(route.used, route.replies.size - 1)]
        route.used++
        return when (reply) {
            is Reply.Resp -> HttpResponse(reply.status, reply.headers, reply.body)
            is Reply.Fail -> throw reply.error
            is Reply.Hang -> kotlinx.coroutines.awaitCancellation()
        }
    }

    companion object {
        fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
            Reply.Resp(status, body.toByteArray(), headers + ("Content-Type" to "application/json"))
        fun bytes(status: Int, body: ByteArray, contentType: String) = Reply.Resp(status, body, mapOf("Content-Type" to contentType))
        fun text(status: Int, body: String, contentType: String = "text/html") = Reply.Resp(status, body.toByteArray(), mapOf("Content-Type" to contentType))
        fun fail(kind: NetworkException.Kind = NetworkException.Kind.UNREACHABLE) = Reply.Fail(NetworkException(kind, "boom"))
    }
}
